// Build-machine check (tools/build_robotmt.sh --check): the same wrapper as the phone's librobotmt.so, built for the
// computer, runs a translation test set (tools/lang/<tag>/mt-test.tsv: direction, source, meaning, Bergamot output, ...,
// fast engine output) and compares each output with the fast engine's reference column, as Settings › Conversation ›
// Test translation speed does on the phone. Usage: robotmt_check <dir with the five model files the app downloads> <test.tsv>
// Prints one line per test line (same/DIFF, ms) and a summary; exits 1 if any output differs, or if a line without a
// letter or a short sentence the models would double comes out wrong. --split-test checks the sentence splitter on its
// own cases.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "robotmt.h"

namespace {

// The reference is the fast engine's own x86 output (column 8) when the set has one, else Bergamot's (column 4).
struct Row {
  std::string direction, source, bergamot, reference;
};

std::vector<std::string> split(const std::string& line, char sep) {
  std::vector<std::string> out;
  std::stringstream ss(line);
  std::string part;
  while (std::getline(ss, part, sep)) out.push_back(part);
  return out;
}

long peak_rss_kb() {
  std::ifstream status("/proc/self/status");
  std::string line;
  while (std::getline(status, line)) {
    if (line.rfind("VmHWM:", 0) == 0) return std::stol(line.substr(6));
  }
  return -1;
}

// The sentence splitter's cases (robotmt::split_sentences): a line and the sentences it must give.
int split_test() {
  const std::vector<std::pair<std::string, std::vector<std::string>>> cases = {
      {"J'ai mon voyage! Le char est encore brisé.", {"J'ai mon voyage!", "Le char est encore brisé."}},
      {"Y'é où ton chum? Y'é pas encore arrivé?", {"Y'é où ton chum?", "Y'é pas encore arrivé?"}},
      {"how are you? i'm fine", {"how are you?", "i'm fine"}},
      {"Quoi ? Non.", {"Quoi ?", "Non."}},
      {"Oui. Non.", {"Oui.", "Non."}},
      {"C'est fini. À demain.", {"C'est fini.", "À demain."}},
      {"Wait... What?", {"Wait...", "What?"}},
      {"Attends… Quoi?", {"Attends…", "Quoi?"}},
      {"He said \"Stop.\" Then he left.", {"He said \"Stop.\"", "Then he left."}},
      {"Il a dit : « Viens. » Puis il est parti.", {"Il a dit : « Viens. » Puis il est parti."}},
      {"It costs 3.50 now. ok", {"It costs 3.50 now. ok"}},
      {"On arrive à 5 h. ensuite on mange.", {"On arrive à 5 h. ensuite on mange."}},
      {"M. Tremblay est là. Il attend.", {"M. Tremblay est là.", "Il attend."}},
      {"Dr. Smith is here. He waits.", {"Dr. Smith is here.", "He waits."}},
      {"Mme Roy et Mlle. Côté arrivent.", {"Mme Roy et Mlle. Côté arrivent."}},
      {"J. K. Rowling wrote it.", {"J. K. Rowling wrote it."}},
      {"Bonjour !Ça va", {"Bonjour !Ça va"}},
      {"  Bonjour.  ", {"Bonjour."}},
      {"Hi!  (Really?) Yes.", {"Hi!", "(Really?)", "Yes."}},
      {"", {}},
      {"   ", {}},
  };
  int bad = 0;
  for (const auto& [line, want] : cases) {
    std::vector<std::string> got;
    for (const robotmt::Sentence& s : robotmt::split_sentences(line)) got.push_back(line.substr(s.begin, s.end - s.begin));
    if (got != want) {
      ++bad;
      std::printf("SPLIT DIFF  [%s] ->", line.c_str());
      for (const std::string& g : got) std::printf(" [%s]", g.c_str());
      std::printf("\n");
    }
  }
  std::printf("sentence splitter: %zu of %zu cases right\n", cases.size() - bad, cases.size());
  return bad == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--split-test") return split_test();
  if (argc != 3) {
    std::fprintf(stderr, "usage: robotmt_check <models dir> <test.tsv> | robotmt_check --split-test\n");
    return 2;
  }
  const std::string dir = argv[1];
  // The app's file names (core MtBundle.kt): one vocabulary for both directions.
  const std::map<std::string, std::string> code = {{"fr-en", "fren"}, {"en-fr", "enfr"}};
  std::map<std::string, robotmt::Engine*> engines;
  for (const auto& [direction, c] : code) {
    int error = 0;
    auto t0 = std::chrono::steady_clock::now();
    robotmt::Engine* e = robotmt::open(dir + "/model." + c + ".intgemm.alphas.bin", dir + "/vocab.fren.spm",
                                       dir + "/lex.50.50." + c + ".s2t.bin", &error);
    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    if (e == nullptr) {
      std::fprintf(stderr, "%s: load failed (error %d)\n", direction.c_str(), error);
      return 1;
    }
    std::printf("load %s %ld ms\n", direction.c_str(), ms);
    engines[direction] = e;
  }
  std::ifstream in(argv[2]);
  std::string line;
  std::vector<Row> rows;
  while (std::getline(in, line)) {
    if (line.empty() || line[0] == '#') continue;
    std::vector<std::string> p = split(line, '\t');
    if (p.size() < 4 || engines.count(p[0]) == 0) continue;
    rows.push_back({p[0], p[1], p[3], p.size() >= 8 ? p[7] : p[3]});
  }
  // One warm-up line per direction, not counted (the phone's test does the same).
  for (auto& [direction, e] : engines) {
    std::string out;
    int error = 0;
    robotmt::translate(e, direction == "fr-en" ? "Bonjour." : "Hello.", &out, &error);
  }
  // A line with no letter or digit is kept as it is, without the engine.
  int kept_wrong = 0;
  for (const std::string& s : {std::string("…"), std::string("?!"), std::string("🙂"), std::string("« ? »")}) {
    std::string out;
    int error = 0;
    if (!robotmt::translate(engines.begin()->second, s, &out, &error) || out != s) {
      ++kept_wrong;
      std::printf("KEPT DIFF [%s] -> [%s]\n", s.c_str(), out.c_str());
    }
  }
  // Bergamot's doubled short sentence ("Oui." gives "Yes. Yes.", "Je ne sais pas." "I don't know. I don't know.") comes
  // out once, inside a line too (robotmt::translate's guard).
  const std::vector<Row> once = {
      {"fr-en", "Oui.", "", "Yes."},
      {"fr-en", "Oui. Je viens demain.", "", "Yes. I'm coming tomorrow."},
      {"fr-en", "Je ne sais pas.", "", "I don't know."},
      {"fr-en", "Le prix est 3.5 dollars. Ok.", "", "The price is 3.5 dollars. Okay."},
      {"en-fr", "Nope.", "", "Non."},
  };
  for (const Row& r : once) {
    std::string out;
    int error = 0;
    if (!robotmt::translate(engines[r.direction], r.source, &out, &error) || out != r.reference) {
      ++kept_wrong;
      std::printf("ONCE DIFF [%s] -> [%s], want [%s]\n", r.source.c_str(), out.c_str(), r.reference.c_str());
    }
  }
  std::vector<long> times;
  int same = 0;
  int bergamot = 0;
  for (const Row& r : rows) {
    std::string out;
    int error = 0;
    auto t0 = std::chrono::steady_clock::now();
    bool ok = robotmt::translate(engines[r.direction], r.source, &out, &error);
    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    times.push_back(ms);
    bool equal = ok && out == r.reference;
    same += equal ? 1 : 0;
    bergamot += ok && out == r.bergamot ? 1 : 0;
    std::printf("%s %s %3ld ms  %s\n", equal ? "same" : "DIFF", r.direction.c_str(), ms, ok ? out.c_str() : "(error)");
    if (!equal) std::printf("     want  %s\n", r.reference.c_str());
  }
  for (auto& [direction, e] : engines) robotmt::close(e);
  std::vector<long> sorted = times;
  std::sort(sorted.begin(), sorted.end());
  long median = sorted.empty() ? 0 : (sorted.size() % 2 == 1 ? sorted[sorted.size() / 2]
                                                              : (sorted[sorted.size() / 2 - 1] + sorted[sorted.size() / 2]) / 2);
  long max = sorted.empty() ? 0 : sorted.back();
  std::printf("%d of %zu the same as the reference (%d the same as the bergamot wheel's); median %ld ms, max %ld ms per line; "
              "peak RSS %ld MB (both directions)\n", same, rows.size(), bergamot, median, max, peak_rss_kb() / 1024);
  return same == static_cast<int>(rows.size()) && kept_wrong == 0 ? 0 : 1;
}
