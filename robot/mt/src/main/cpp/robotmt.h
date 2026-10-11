// Robot's fast translation engine (docs/conversation-mode.md 4.3): one translation direction of a Mozilla Bergamot
// model, run by slimt (DavidVentura's fork, pinned in tools/build_robotmt.sh). Robot's own wrapper over slimt's C++
// API, used instead of slimt-sys's C wrapper, which logs input text when a translation fails.
//
// Nothing here logs or keeps a message: failures are a code only, so no path, sentence or translation can reach a log.
// Not thread-safe: the :mt process calls it from one thread.
#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace robotmt {

enum Error {
  kOk = 0,
  // A file could not be opened or mapped.
  kFiles = 1,
  // slimt refused the model files (an exception while loading).
  kModel = 2,
  // slimt threw while translating.
  kTranslate = 3,
  // The engine handle was null.
  kHandle = 4,
};

class Engine;

// Loads one direction: the model, its SentencePiece vocabulary and its lexical shortlist. The number of encoder and
// decoder layers is read from the model file itself (base-memory models have 6 and 4). Null on failure, with *error set.
Engine* open(const std::string& model, const std::string& vocabulary, const std::string& shortlist, int* error);

// Frees the engine and its memory (null is allowed).
void close(Engine* engine);

// Translates one line (UTF-8) into *target: each sentence on its own ([split_sentences]), joined with the line's own
// spaces between them, as Bergamot does. One sentence per decode, so a sentence's translation never depends on what
// else was asked. False on failure, with *error set.
bool translate(Engine* engine, const std::string& source, std::string* target, int* error);

// A sentence of a line: its bytes [begin, end), and the bytes [end, next) of the spaces after it.
struct Sentence {
  size_t begin, end, next;
};

// Where a line's sentences are, as ssplit-cpp draws them without a prefix file (the slimt fork leaves splitting to its
// caller): after one or more of . ! ? … and any closing quotes or brackets, then a space, before the next sentence
// (with its opening quotes or brackets). "!" and "?" always end a sentence; "." and "…" only before a capital letter,
// and not after a title or an initial ("M. Tremblay", "Dr. Smith", "J. K."). Leading and trailing spaces are left out.
// Empty for an empty or blank line.
std::vector<Sentence> split_sentences(const std::string& text);

}  // namespace robotmt
