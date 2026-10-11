// See robotmt.h. Robot's wrapper over slimt's synchronous frontend (slimt::Blocking): no worker threads, no cache, so
// memory is the model and the decode of one line at a time, and the caller decides when to stop between lines.
#include "robotmt.h"

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <exception>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "slimt/Frontend.hh"
#include "slimt/Io.hh"
#include "slimt/Model.hh"
#include "slimt/Response.hh"
#include "slimt/Types.hh"

namespace robotmt {

class Engine {
 public:
  Engine(slimt::Config config, std::shared_ptr<slimt::Model> model)
      : service_(config), model_(std::move(model)) {}

  // One sentence.
  std::string translate(const std::string& sentence) {
    slimt::Options options;  // no alignment, no alternatives
    std::vector<slimt::Response> responses = service_.translate(model_, {sentence}, options);
    return responses.empty() ? std::string() : responses.front().target.text;
  }

 private:
  slimt::Blocking service_;
  std::shared_ptr<slimt::Model> model_;
};

namespace {

// The code point at s[i] and its length in bytes; a malformed byte counts as one code point of its own.
std::pair<char32_t, size_t> at(const std::string& s, size_t i) {
  auto b = static_cast<unsigned char>(s[i]);
  size_t n = b < 0x80 ? 1 : (b >> 5) == 0x6 ? 2 : (b >> 4) == 0xE ? 3 : (b >> 3) == 0x1E ? 4 : 0;
  if (n == 0 || i + n > s.size()) return {b, 1};
  if (n == 1) return {b, 1};
  char32_t c = b & (n == 2 ? 0x1F : n == 3 ? 0x0F : 0x07);
  for (size_t k = 1; k < n; ++k) {
    auto x = static_cast<unsigned char>(s[i + k]);
    if ((x >> 6) != 0x2) return {b, 1};
    c = (c << 6) | (x & 0x3F);
  }
  return {c, n};
}

bool is_space(char32_t c) {
  return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f' || c == 0xA0 || c == 0x2007 || c == 0x202F ||
         c == 0x3000 || (c >= 0x2000 && c <= 0x200A);
}

bool is_terminal(char32_t c) { return c == '.' || c == '!' || c == '?' || c == 0x2026; }
bool is_strong(char32_t c) { return c == '!' || c == '?'; }
// Closing quotes and brackets stay with the sentence they end; opening ones go with the next.
bool is_closer(char32_t c) { return c == '"' || c == '\'' || c == 0x201D || c == 0x2019 || c == ')' || c == ']' || c == 0xBB; }
bool is_opener(char32_t c) {
  return c == '"' || c == '\'' || c == 0x201C || c == 0x2018 || c == '(' || c == '[' || c == 0xBF || c == 0xA1 || c == 0xAB;
}

// Capital letters of the Latin, Greek and Cyrillic alphabets (the languages Mozilla's models cover with capitals).
bool is_upper(char32_t c) {
  if (c >= 'A' && c <= 'Z') return true;
  if (c >= 0xC0 && c <= 0xDE) return c != 0xD7;
  if (c >= 0x100 && c <= 0x137) return c % 2 == 0;
  if (c >= 0x139 && c <= 0x148) return c % 2 == 1;
  if (c >= 0x14A && c <= 0x177) return c % 2 == 0;
  if (c == 0x178) return true;
  if (c >= 0x179 && c <= 0x17E) return c % 2 == 1;
  if (c >= 0x391 && c <= 0x3A9) return c != 0x3A2;
  return c >= 0x400 && c <= 0x42F;
}

bool has_word(const std::string& s, size_t begin, size_t end) {
  for (size_t i = begin; i < end; ++i) {
    auto b = static_cast<unsigned char>(s[i]);
    if (b >= 0x80 || (b >= '0' && b <= '9') || ((b | 0x20) >= 'a' && (b | 0x20) <= 'z')) return true;
  }
  return false;
}

// Words that end with a full stop inside a sentence, before a name: English and French titles. A lone capital (an
// initial, or French "M." for Monsieur) is checked apart.
const char* const kTitles[] = {"Mr", "Mrs", "Ms", "Dr", "Drs", "Dre", "Prof", "Pr", "St", "Ste", "Jr", "Sr", "Mt", "vs",
                               "Mme", "Mmes", "Mlle", "Mlles", "MM", "Me", "Mgr", "e.g", "i.e"};

// The word right before byte p (back to a space), without opening punctuation, is a title or an initial.
bool before_title(const std::string& s, size_t p) {
  size_t b = p;
  while (b > 0 && s[b - 1] != ' ' && s[b - 1] != '\t' && s[b - 1] != '\n') --b;
  while (b < p && static_cast<unsigned char>(s[b]) < 0x80 && !std::isalnum(static_cast<unsigned char>(s[b]))) ++b;
  if (b == p) return false;
  auto [c, n] = at(s, b);
  if (b + n == p && is_upper(c)) return true;
  std::string word = s.substr(b, p - b);
  for (const char* t : kTitles) {
    if (word == t) return true;
  }
  return false;
}

// The largest N in the model's "encoder_lN_…" and "decoder_lN_…" parameter names: slimt doesn't read the layer counts
// from the model, and its presets have 2 decoder layers where Mozilla's base-memory models have 4.
void count_layers(const std::string& model_path, size_t* encoder, size_t* decoder) {
  slimt::io::MmapFile file(model_path);
  std::vector<slimt::io::Item> items = slimt::io::load_items(file.data());
  auto layer = [](const std::string& name, const std::string& prefix) -> size_t {
    if (name.compare(0, prefix.size(), prefix) != 0) return 0;
    size_t n = 0;
    size_t i = prefix.size();
    for (; i < name.size() && name[i] >= '0' && name[i] <= '9'; ++i) n = n * 10 + static_cast<size_t>(name[i] - '0');
    return i == prefix.size() ? 0 : n;
  };
  *encoder = 0;
  *decoder = 0;
  for (const slimt::io::Item& item : items) {
    *encoder = std::max(*encoder, layer(item.name, "encoder_l"));
    *decoder = std::max(*decoder, layer(item.name, "decoder_l"));
  }
}

}  // namespace

Engine* open(const std::string& model, const std::string& vocabulary, const std::string& shortlist, int* error) {
  *error = kOk;
  size_t encoder = 0;
  size_t decoder = 0;
  try {
    count_layers(model, &encoder, &decoder);
  } catch (...) {
    *error = kFiles;
    return nullptr;
  }
  if (encoder == 0 || decoder == 0) {
    *error = kModel;
    return nullptr;
  }
  try {
    slimt::Model::Config mc = slimt::preset::base();
    mc.encoder_layers = encoder;
    mc.decoder_layers = decoder;
    slimt::Package<std::string> package{
        .model = model,
        .vocabulary = vocabulary,
        .target_vocabulary = std::string(),  // fr↔en share one vocabulary
        .shortlist = shortlist,
        .ssplit = std::string(),
    };
    auto m = std::make_shared<slimt::Model>(mc, package);
    slimt::Config config;
    config.workers = 1;
    config.cache_size = 0;  // no translation cache: bounded memory, and a timing test measures the engine
    return new Engine(config, std::move(m));
  } catch (...) {
    *error = kModel;
    return nullptr;
  }
}

void close(Engine* engine) { delete engine; }

std::vector<Sentence> split_sentences(const std::string& text) {
  std::vector<Sentence> out;
  size_t start = 0;
  while (start < text.size() && is_space(at(text, start).first)) start += at(text, start).second;
  size_t stop = text.size();  // after the last character that isn't a space
  for (size_t i = start; i < text.size();) {
    auto [c, n] = at(text, i);
    if (!is_space(c)) stop = i + n;
    i += n;
  }
  if (start >= stop) return out;
  size_t i = start;
  while (i < stop) {
    auto [c, n] = at(text, i);
    if (!is_terminal(c)) {
      i += n;
      continue;
    }
    // A run of . ! ? …, then closing quotes and brackets, then spaces, then opening ones, then the next sentence.
    size_t p = i;
    bool strong = false;
    size_t q = i;
    while (q < stop && is_terminal(at(text, q).first)) {
      strong = strong || is_strong(at(text, q).first);
      q += at(text, q).second;
    }
    bool lone_stop = q - p == 1 && text[p] == '.';
    size_t r = q;
    while (r < stop && is_closer(at(text, r).first)) r += at(text, r).second;
    size_t sp = r;
    while (sp < stop && is_space(at(text, sp).first)) sp += at(text, sp).second;
    size_t t = sp;
    while (t < stop && is_opener(at(text, t).first)) t += at(text, t).second;
    i = q;
    if (sp == r || t >= stop) continue;  // no space after it, or nothing left: the line's last sentence
    if (!strong) {
      if (lone_stop && before_title(text, p)) continue;
      if (!is_upper(at(text, t).first)) continue;
    }
    out.push_back({start, r, sp});
    start = sp;
    i = sp;
  }
  out.push_back({start, stop, stop});
  return out;
}

bool translate(Engine* engine, const std::string& source, std::string* target, int* error) {
  *error = kOk;
  if (engine == nullptr) {
    *error = kHandle;
    return false;
  }
  try {
    std::string out;
    for (const Sentence& s : split_sentences(source)) {
      // A "sentence" with no letter or digit ("…", "?!") is kept as it is.
      if (has_word(source, s.begin, s.end)) out += engine->translate(source.substr(s.begin, s.end - s.begin));
      else out.append(source, s.begin, s.end - s.begin);
      out.append(source, s.end, s.next - s.end);
    }
    *target = std::move(out);
    return true;
  } catch (...) {
    *error = kTranslate;
    return false;
  }
}

}  // namespace robotmt
