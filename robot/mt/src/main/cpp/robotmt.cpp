// SPDX-License-Identifier: GPL-3.0-only
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

// A letter or digit, roughly: ASCII ones, and any code point outside the punctuation, symbol and emoji blocks.
bool is_word_char(char32_t c) {
  if (c < 0x80) return (c >= '0' && c <= '9') || ((c | 0x20) >= 'a' && (c | 0x20) <= 'z');
  if (c < 0xC0) return false;                     // Latin-1 punctuation and symbols: « » ¿ ¡ °
  if (c == 0xD7 || c == 0xF7) return false;       // × ÷
  if (c >= 0x2000 && c <= 0x2BFF) return false;   // punctuation (… “ ”), symbols, arrows, dingbats
  if (c >= 0x3000 && c <= 0x303F) return false;   // CJK punctuation
  if (c >= 0xFE00 && c <= 0xFE0F) return false;   // variation selectors
  return c < 0x1F000;                             // emoji and pictographs
}

bool has_word(const std::string& s, size_t begin, size_t end) {
  for (size_t i = begin; i < end;) {
    auto [c, n] = at(s, i);
    if (is_word_char(c)) return true;
    i += n;
  }
  return false;
}

// Bergamot's base models write a short sentence twice when it ends with a full stop ("Oui." gives "Yes. Yes.", "Je ne
// sais pas." gives "I don't know. I don't know."). Each sentence of a line is decoded on its own, so each gets the guard
// core MtGuard gives a whole line (which no longer sees the sentences inside one): a sentence of at most kShortWords
// words loses its final full stop on the way in and gets it back after, and an output that is one sentence written twice
// or more is kept once.
constexpr size_t kShortWords = 2;

// Words: runs between spaces with a letter or digit in them ("D'accord" is one).
size_t count_words(const std::string& s) {
  size_t words = 0;
  bool in_word = false;
  bool has = false;
  for (size_t i = 0; i < s.size();) {
    auto [c, n] = at(s, i);
    if (is_space(c)) {
      words += in_word && has ? 1 : 0;
      in_word = has = false;
    } else {
      in_word = true;
      has = has || is_word_char(c);
    }
    i += n;
  }
  return words + (in_word && has ? 1 : 0);
}

// Without its trailing spaces.
std::string trim_end(std::string s) {
  while (!s.empty()) {
    size_t i = s.size() - 1;
    while (i > 0 && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80) --i;  // the last code point's first byte
    if (!is_space(at(s, i).first)) break;
    s.resize(i);
  }
  return s;
}

bool ends_terminal(const std::string& s) {
  std::string t = trim_end(s);
  if (t.empty()) return false;
  size_t i = t.size() - 1;
  while (i > 0 && (static_cast<unsigned char>(t[i]) & 0xC0) == 0x80) --i;
  return is_terminal(at(t, i).first);
}

// The sentences of an output as core MtGuard.sentences cuts them: a run of anything but . ! ? … with the marks after
// it, trimmed, those without a letter or digit left out.
std::vector<std::string> pieces(const std::string& s) {
  std::vector<std::string> out;
  size_t i = 0;
  while (i < s.size()) {
    size_t b = i;
    while (i < s.size() && !is_terminal(at(s, i).first)) i += at(s, i).second;
    if (i == b) {  // marks with no text before them
      i += at(s, i).second;
      continue;
    }
    while (i < s.size() && is_terminal(at(s, i).first)) i += at(s, i).second;
    size_t first = b;
    while (first < i && is_space(at(s, first).first)) first += at(s, first).second;
    std::string piece = trim_end(s.substr(first, i - first));
    if (has_word(piece, 0, piece.size())) out.push_back(std::move(piece));
  }
  return out;
}

// What two sentences are compared by: lower case (ASCII), without the marks and spaces at the end.
std::string repeat_key(const std::string& s) {
  std::string k = s;
  for (char& ch : k) {
    if (ch >= 'A' && ch <= 'Z') ch = static_cast<char>(ch - 'A' + 'a');
  }
  while (!k.empty()) {
    std::string t = trim_end(k);
    if (t.size() >= 3 && t.compare(t.size() - 3, 3, "\xE2\x80\xA6") == 0) t.resize(t.size() - 3);
    else if (!t.empty() && (t.back() == '.' || t.back() == '!' || t.back() == '?')) t.pop_back();
    if (t == k) break;
    k = std::move(t);
  }
  return k;
}

// [output], a translation of the one sentence [source]: once, when it is the same sentence written twice or more.
std::string collapse_repeats(const std::string& source, const std::string& output) {
  if (pieces(source).size() != 1) return output;
  std::vector<std::string> p = pieces(output);
  if (p.size() < 2) return output;
  const std::string first = repeat_key(p[0]);
  for (const std::string& x : p) {
    if (repeat_key(x) != first) return output;
  }
  return p[0];
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

namespace {

// One sentence through the engine, with the short-sentence guard above.
std::string translate_sentence(Engine* engine, const std::string& sentence) {
  std::string in = sentence;
  bool stop = false;
  if (!in.empty() && in.back() == '.' && (in.size() < 2 || in[in.size() - 2] != '.') && count_words(in) <= kShortWords) {
    in = trim_end(in.substr(0, in.size() - 1));
    stop = !in.empty();
    if (!stop) in = sentence;
  }
  std::string out = collapse_repeats(sentence, engine->translate(in));
  if (stop && !trim_end(out).empty() && !ends_terminal(out)) out = trim_end(out) + ".";
  return out;
}

}  // namespace

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
      if (has_word(source, s.begin, s.end)) out += translate_sentence(engine, source.substr(s.begin, s.end - s.begin));
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
