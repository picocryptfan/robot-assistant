#!/usr/bin/env python3
"""Builds the language-ID table for conversation mode (core LangId.kt) from public-domain sentences.

Writes (NAME is the languages joined by "-", "fr-en" today):
- core/src/main/assets/langid/NAME.tsv: the model. A naive Bayes over words and character 1-4-grams (docs/
  conversation-mode.md 2.3): the most frequent TOP words and TOP n-grams of each language, with each language's
  smoothed log-probability, times SCALE and rounded. Lines starting with "#" are comments, "@" settings, the rest
  "feature<TAB>value per language". Words are written "w:word"; n-grams as they are, spaces included (a word is
  padded with one space on each side).
- core/src/test/resources/langid/heldout-NAME.tsv: HELD sentences per language and length that the model never
  saw, each with this script's answer and margin. LangIdTest checks the accuracy on them, and that the Kotlin port
  gives exactly the same answer and margin for every line.

Training data: public domain (CC0) text only, so the table and its test file carry no conditions.
- Tatoeba's CC0 1.0 export (https://downloads.tatoeba.org/exports/sentences_CC0.tar.bz2), the sentences their authors
  released into the public domain. Never the per-language or full exports, which are mostly CC-BY 2.0 FR. Plenty of
  French, English and Russian; almost nothing for most other languages (docs/languages.md 3.1 has the counts).
- For any other language, --text: plain text, one sentence per line, such as Common Voice's sentence files
  (github.com/common-voice/common-voice, server/data/<locale>/*.txt, CC0 1.0). A directory means every *.txt in it
  except europarl-*.txt, which come from the Europarl corpus and are not CC0.
THIRD_PARTY.md records each source's SHA-256 and date; this script prints them and writes them in the table's header.

The features and the cue rules here must stay the same as LangId.kt's (features(), CUES): LangIdTest checks the
Kotlin side against the held-out lines, so a change on one side only shows up there.

Usage:
  python3 -I tools/make_langid.py <sentences_CC0.tar.bz2> <robot project dir>          (today's fr-en table)
  python3 -I tools/make_langid.py --langs fr,en,es --tatoeba <sentences_CC0.tar.bz2> --text es=<cv>/server/data/es \
      [--max N] [--top N] [--out <dir>] [--dry-run] [<robot project dir>]
--langs: the table's languages, in order (ISO 639-1, as LangId and Parakeet's text use). --max: at most N training
sentences per language (a deterministic sample; 0 = all). --out: write under <dir> instead of the project (a trial).
--dry-run: print the measurements, write nothing. docs/languages.md is the playbook for adding a language.
Standard library only. Deterministic: the same sources and options give the same files.
"""
import argparse
import csv
import hashlib
import io
import math
import random
import re
import sys
import tarfile
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

# Tatoeba's language codes (ISO 639-3) for the table's (ISO 639-1). Parakeet v3's 25 languages, and a few more.
TATOEBA = {
    "bg": "bul", "ca": "cat", "cs": "ces", "da": "dan", "de": "deu", "el": "ell", "en": "eng", "es": "spa", "et": "est",
    "eu": "eus", "fi": "fin", "fr": "fra", "ga": "gle", "gl": "glg", "hr": "hrv", "hu": "hun", "it": "ita", "lt": "lit",
    "lv": "lvs", "mt": "mlt", "nb": "nob", "nl": "nld", "oc": "oci", "pl": "pol", "pt": "por", "ro": "ron", "ru": "rus",
    "sk": "slk", "sl": "slv", "sv": "swe", "uk": "ukr", "br": "bre", "cy": "cym", "eo": "epo", "be": "bel",
}
LANGS = [("fra", "fr"), ("eng", "en")]
TOP = 3000
ALPHA = 0.5
SCALE = 10
WORD_WEIGHT = 2
GRAM_WEIGHT = 1
SEED = 20261011
# Held out per language: (words from, words to, count).
HELD = [(2, 2, 50), (3, 3, 50), (4, 12, 100)]
# The margin under which LangId says "not sure" (score units: natural log, words weighted twice), and the stricter one
# for a line of one word. Chosen on the first one or two words of validation sentences (2026-10-11 export): one word
# 82.8 % right, 16.7 % not sure (mostly a capitalised word the table doesn't know, which could be a name), 0.5 % wrong;
# two words 99.0 % right, 0.8 % not sure, 0.1 % wrong. Without the lone-name rule one word was 94 / 4.6 / 1.4.
MARGIN = 1.0
MARGIN_ONE_WORD = 2.0

WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")
# Cue rules, as in LangId.CUES: (language, pattern, bonus), each counted once per line.
CUES = [
    ("fr", re.compile(r"[éèêëàâçùûüôœî]"), 2.0),
    ("fr", re.compile(r"(?<![^\W_])(?:[ldjmnstc]|qu)'[a-zéèêàâîôûh]"), 3.0),
    ("en", re.compile(r"[a-z]'(?:s|t|m|re|ll|ve|d)(?![^\W_])"), 3.0),
]


def norm(s):
    return unicodedata.normalize("NFC", s).lower().replace("’", "'").replace("ʼ", "'")


def words(s):
    return WORD.findall(norm(s))


def features(s):
    out = []
    for w in words(s):
        out.append("w:" + w)
        p = " " + w + " "
        for n in (1, 2, 3, 4):
            for i in range(len(p) - n + 1):
                g = p[i:i + n]
                if g != " ":
                    out.append(g)
    return out


# A sentence's id: ("tatoeba", Tatoeba's id) or (file name, line number), written as "123" or "es.txt:45".
def show_id(i):
    return str(i[1]) if i[0] == "tatoeba" else f"{i[0]}:{i[1]}"


def read_cc0(path):
    data = path.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("sentences_CC0.csv"))
        text = tar.extractfile(member).read().decode("utf-8")
    csv.field_size_limit(10 ** 7)
    by = defaultdict(list)
    newest = ""
    codes = {t: c for t, c in LANGS}
    for row in csv.reader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE):
        if len(row) >= 3 and row[1] in codes:
            by[codes[row[1]]].append((("tatoeba", int(row[0])), row[2].strip()))
            if len(row) >= 4 and row[3] > newest:
                newest = row[3]
    return by, sha, newest


def read_text(lang, path):
    """One sentence per line from a file, or from every *.txt in a directory but europarl-*.txt. Returns the rows and
    one provenance line per file (name, lines, SHA-256)."""
    files = [path] if path.is_file() else sorted(f for f in path.glob("*.txt") if not f.name.startswith("europarl"))
    rows, notes = [], []
    for f in files:
        data = f.read_bytes()
        lines = data.decode("utf-8").splitlines()
        rows += [((f"{lang}/{f.name}", n + 1), t.strip()) for n, t in enumerate(lines) if t.strip()]
        notes.append(f"{lang}: {f.name}, {len(lines)} lines, sha256 {hashlib.sha256(data).hexdigest()}")
    return rows, notes


def dedupe(by):
    """One copy of each sentence (same words, any case or punctuation), and none that two languages share."""
    keys = {}
    for lang, rows in by.items():
        seen = {}
        for i, s in sorted(rows):
            k = " ".join(words(s))
            if k and k not in seen:
                seen[k] = (i, s)
        keys[lang] = seen
    seen_in = Counter(k for v in keys.values() for k in v)
    both = {k for k, n in seen_in.items() if n > 1}
    return {lang: [v for k, v in sorted(seen.items(), key=lambda kv: kv[1][0]) if k not in both] for lang, seen in keys.items()}


# The held-out lines are stored in the repository as test data, so they are plain everyday sentences: no capitalised
# word after the first (no people, places, nations or brands; "I" aside), and none of Tatoeba's sentences about sex,
# religion, politics, drugs, violence, illness or identity, or with swearing. Training keeps every sentence: the table
# holds only counts.
QUIET = re.compile(
    r"(?<![^\W_])(?:"
    r"fuck|shit|suck|bitch|sex|cuddl|kiss|relig|jew|juif|juiv|muslim|musulm|christ|chrét|hindu|transgender|agender|gay|lesb|"
    r"kabyl|algér|alger|afric|ameri|améri|catalo|kurd|bourgeois|politic|politiq|manifest|monarch|marijuana|drug|drogu|"
    r"medicat|schizo|anorex|anxi|testoster|kill|murder|euthanas|cancer|drunk|ivrogn|alcool|alcohol|legaliz|prison|missile|"
    r"bomb|weapon|guerr|zarathustr|espion|pagan|zoroastr|satan|communis|blasphem|fascis|racis|antisemit|islam|annex|lgbt|"
    r"chaplain|tortur|terror|covid|adhd|burlesq|decoloni|manspread|abolish|idiot|enflur|foutu|diarrh|choléra|drone|militar|"
    r"surveill|nazi|hitler|rape|suicid|depress|crybull|shoot|hatred|stink|merd|atheis|queer|asexual|slay|secular|chauvin|"
    r"pegging|pregnan|grippy|prêtre|victim|castrat|extrémis|extremis|radical|plague|treatment|répugn|insult|trump|israel|"
    r"ukrain|russ|austral|cult|bullshit|socialis|capitalis|demon|soul|faith|spiritu|smok|ugly|propagand|countr|pays|"
    r"(?:pope|pape|church|église|bible|coran|koran|allah|gods?|dieu|jésus|jesus|pray|puja|witch|moonies|cops?|police|theft|"
    r"slammer|guns?|armes?|wars?|mort|morts|dead|die|died|death|blood|sang|ghost|aids|sida|weed|indica|lithium|unstable|"
    r"ace|vers|coke|od'd|shot|fired|forced|hate|trans|nation|resistance|rent|viol|violée?|tue|tuer|secret|secrets|crise|"
    r"bi|holy|evil|tax|spy|messe|high|cross|cibles?|spectrum|baby|(?:far|the) (?:right|left))"
    r"(?![^\W_]))",
    re.IGNORECASE)


# ... and every word is among the language's COMMON most frequent words in the export. That also keeps out the rare
# vocabulary that carries most of the rest ("throuple", "fentanyl"). It makes the test a little easier than Tatoeba at
# large; docs/conversation-mode.md 2.3 has the fair comparison with other detectors.
COMMON = 4000


def everyday(s, common):
    raw = WORD.findall(unicodedata.normalize("NFC", s).replace("\u2019", "'"))
    # A script without capitals (Hebrew, Arabic, Devanagari...) has no names to spot this way.
    caseless = not any(c.isupper() or c.islower() for c in s)
    return (not QUIET.search(s) and all(caseless or w[0].islower() or w == "I" or w.startswith("I'") for w in raw[1:])
            and all(w in common for w in words(s)))


def split(by):
    rng = random.Random(SEED)
    held, train = {}, {}
    for lang, rows in by.items():
        picked = set()
        out = []
        freq = Counter(w for _, s in rows for w in words(s))
        common = {w for w, _ in freq.most_common(COMMON)}
        for lo, hi, n in HELD:
            pool = [r for r in rows if lo <= len(words(r[1])) <= hi and everyday(r[1], common)]
            for r in rng.sample(pool, min(n, len(pool))):
                picked.add(r[0])
                out.append(r)
        held[lang] = out
        train[lang] = [r for r in rows if r[0] not in picked]
    return held, train


class Model:
    def __init__(self, train):
        self.langs = [code for _, code in LANGS]
        counts = {l: Counter() for l in self.langs}
        for l in self.langs:
            for _, s in train[l]:
                counts[l].update(features(s))
        keep = set()
        for l in self.langs:
            ranked = [f for f, _ in counts[l].most_common()]
            keep.update([f for f in ranked if f.startswith("w:")][:TOP])
            keep.update([f for f in ranked if not f.startswith("w:")][:TOP])
        self.vocab = sorted(keep)
        nw = sum(1 for f in keep if f.startswith("w:"))
        ng = len(keep) - nw
        self.q = {}
        for l in self.langs:
            tw = sum(c for f, c in counts[l].items() if f in keep and f.startswith("w:"))
            tg = sum(c for f, c in counts[l].items() if f in keep and not f.startswith("w:"))
            for f in keep:
                tot, n = (tw, nw) if f.startswith("w:") else (tg, ng)
                lp = math.log((counts[l][f] + ALPHA) / (tot + ALPHA * n))
                self.q[(l, f)] = int(round(lp * SCALE))

    def scores(self, s):
        """Each language's score times SCALE (whole numbers, as LangId.kt adds them), and how many features matched."""
        sc = {l: 0 for l in self.langs}
        n = 0
        for f in features(s):
            if (self.langs[0], f) not in self.q:
                continue
            n += 1
            w = WORD_WEIGHT if f.startswith("w:") else GRAM_WEIGHT
            for l in self.langs:
                sc[l] += w * self.q[(l, f)]
        t = norm(s)
        for l, rx, bonus in CUES:
            if l in sc and rx.search(t):
                sc[l] += int(round(bonus * SCALE))
        return sc, n

    def known(self, w):
        return (self.langs[0], "w:" + w) in self.q

    def detect(self, s):
        """(language or None, margin x SCALE): LangId.detect. None for no word of two letters or more, a lone name, or a
        margin under MARGIN (MARGIN_ONE_WORD for one word)."""
        sc, n = self.scores(s)
        ranked = sorted(self.langs, key=lambda l: (-sc[l], self.langs.index(l)))
        margin = sc[ranked[0]] - sc[ranked[1]] if n else 0
        ws = [w for w in words(s) if len(w) >= 2]
        if not ws or n == 0 or self.name_like(s):
            return None, margin
        need = MARGIN_ONE_WORD if len(ws) == 1 else MARGIN
        return (ranked[0] if margin >= need * SCALE else None), margin

    def name_like(self, s):
        """At most three words, every one capitalised and none of them a word the table knows: "Montréal", "Gatineau",
        "Marie-Ève Gagnon". One known word ("Merci", "Bonjour Marie", "That's Vega") leaves it to the scores."""
        raw = WORD.findall(unicodedata.normalize("NFC", s).replace("\u2019", "'").replace("\u02bc", "'"))
        if not raw or len(raw) > 3 or not all(w[0].isupper() for w in raw):
            return False
        return not any(self.known(w.lower()) for w in raw)

    def write(self, path, header):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            for h in header:
                f.write("# " + h + "\n")
            f.write("@langs\t" + "\t".join(self.langs) + "\n")
            f.write(f"@scale\t{SCALE}\n@weights\t{WORD_WEIGHT}\t{GRAM_WEIGHT}\n@margin\t{MARGIN}\t{MARGIN_ONE_WORD}\n")
            for feat in self.vocab:
                f.write(feat + "\t" + "\t".join(str(self.q[(l, feat)]) for l in self.langs) + "\n")


def report(model, held, label):
    print(label)
    for lo, hi, _ in HELD + [(1, 1, 0)]:
        ok = tot = unsure = 0
        for lang, rows in held.items():
            for _, s in rows:
                if lo <= len(words(s)) <= hi:
                    g, _ = model.detect(s)
                    tot += 1
                    ok += g == lang
                    unsure += g is None
        if tot:
            print(f"  {lo}-{hi} words: {100 * ok / tot:.1f}% right, {unsure} not sure, of {tot}")


def short_lines(check, val):
    """Short lines are rare in Tatoeba: the first one and two words of the validation sentences stand in for them."""
    for k in (1, 2):
        ok = unsure = tot = 0
        wrong = Counter()
        for lang, rows in val.items():
            for _, s in rows:
                if len(s.split()) > k:
                    g, _ = check.detect(" ".join(s.split()[:k]))
                    tot += 1
                    ok += g == lang
                    unsure += g is None
                    if g is not None and g != lang:
                        wrong[f"{lang}->{g}"] += 1
        print(f"  first {k} word(s): {100 * ok / tot:.1f}% right, {100 * unsure / tot:.1f}% not sure, {100 * (tot - ok - unsure) / tot:.1f}% wrong, of {tot}")
        if len(check.langs) > 2 and wrong:
            print("    mistaken for: " + ", ".join(f"{k} {v}" for k, v in wrong.most_common(8)))


def options(argv):
    a = argparse.ArgumentParser(description="Build Robot's language-ID table (docs/languages.md).")
    a.add_argument("paths", nargs="*", help="<sentences_CC0.tar.bz2> <robot project dir>, or just <robot project dir>")
    a.add_argument("--langs", default="fr,en", help="the table's languages in order, ISO 639-1 (default fr,en)")
    a.add_argument("--tatoeba", type=Path, help="Tatoeba's sentences_CC0.tar.bz2")
    a.add_argument("--text", action="append", default=[], metavar="LANG=PATH",
                   help="CC0 text, one sentence per line: a file, or a directory of *.txt (europarl-*.txt skipped)")
    a.add_argument("--max", type=int, default=0, help="at most this many training sentences per language (0 = all)")
    a.add_argument("--top", type=int, default=TOP, help=f"words and n-grams kept per language (default {TOP})")
    a.add_argument("--out", type=Path, help="write under this directory instead of the project")
    a.add_argument("--dry-run", action="store_true", help="measure only, write nothing")
    o = a.parse_args(argv)
    if len(o.paths) == 2 and o.tatoeba is None:
        o.tatoeba, root = Path(o.paths[0]), Path(o.paths[1])
    elif len(o.paths) == 1:
        root = Path(o.paths[0])
    elif not o.paths and (o.out or o.dry_run):
        root = None
    else:
        a.error("give the robot project dir (or --out / --dry-run)")
    o.root = o.out or root
    o.langs = [x.strip() for x in o.langs.split(",") if x.strip()]
    if len(o.langs) < 2:
        a.error("--langs needs two languages or more")
    o.text = [t.split("=", 1) for t in o.text]
    if any(len(t) != 2 or t[0] not in o.langs for t in o.text):
        a.error("--text is LANG=PATH, LANG one of --langs")
    if o.tatoeba is None and not o.text:
        a.error("no sentences: give --tatoeba and/or --text")
    return o


def main(argv=None):
    global LANGS, TOP
    o = options(sys.argv[1:] if argv is None else argv)
    LANGS = [(TATOEBA.get(c, c), c) for c in o.langs]
    TOP = o.top
    by, sha, newest = defaultdict(list), None, ""
    sources = []
    if o.tatoeba:
        by, sha, newest = read_cc0(o.tatoeba)
        sources.append("tatoeba")
    notes = []
    for lang, path in o.text:
        rows, n = read_text(lang, Path(path))
        by[lang] += rows
        notes += n
    missing = [c for c in o.langs if len(by.get(c, [])) < 50]
    if missing:
        sys.exit(f"too few sentences for {', '.join(missing)} (fewer than 50); docs/languages.md 3.1 lists where to find more")
    # In the order the sources first name each language (Tatoeba's file order, then --text): the held-out draw goes
    # language by language from one seed, so this order is part of what makes a rebuild give the same files.
    by = dedupe({c: by[c] for c in by if c in o.langs})
    held, train = split(by)
    if o.max:
        train = {l: sorted(random.Random(SEED + 2).sample(rows, min(o.max, len(rows)))) for l, rows in train.items()}
    model = Model(train)
    # A validation look before writing: a second split of the training sentences, so the margins above are not
    # tuned on the held-out file.
    rng = random.Random(SEED + 1)
    val = {l: rng.sample(rows, min(1500, len(rows))) for l, rows in train.items()}
    val_ids = {i for rows in val.values() for i, _ in rows}
    check = Model({l: [r for r in rows if r[0] not in val_ids] for l, rows in train.items()})
    report(check, val, "validation (trained without it):")
    short_lines(check, val)
    report(model, held, "held-out file:")
    counts = ", ".join(f"{l} {len(train[l])}" for l in model.langs)
    name = "-".join(model.langs)
    if sources == ["tatoeba"] and not notes:
        header = [
            "robot-langid 1: language ID for conversation mode (core LangId.kt), written by tools/make_langid.py.",
            "Trained only on Tatoeba's CC0 1.0 sentences (public domain): https://downloads.tatoeba.org/exports/sentences_CC0.tar.bz2",
            f"export sha256 {sha}, newest sentence {newest}; after removing duplicates and the held-out lines: {counts}.",
        ]
    else:
        header = ["robot-langid 1: language ID for conversation mode (core LangId.kt), written by tools/make_langid.py.",
                  "Trained only on public domain (CC0 1.0) sentences:"]
        if sha:
            header.append(f"Tatoeba https://downloads.tatoeba.org/exports/sentences_CC0.tar.bz2 sha256 {sha}, newest sentence {newest}")
        header += notes
        header.append(f"After removing duplicates and the held-out lines{f' (at most {o.max} each)' if o.max else ''}: {counts}.")
    header.append(f"Top {TOP} words and top {TOP} character 1-4-grams per language, add-{ALPHA} smoothing, log-probabilities x {SCALE}.")
    if o.dry_run:
        print(f"dry run: {name}, {len(model.vocab)} features; nothing written")
        return
    asset = o.root / "core" / "src" / "main" / "assets" / "langid" / f"{name}.tsv"
    asset.parent.mkdir(parents=True, exist_ok=True)
    model.write(asset, header)
    test = o.root / "core" / "src" / "test" / "resources" / "langid" / f"heldout-{name}.tsv"
    test.parent.mkdir(parents=True, exist_ok=True)
    with open(test, "w", encoding="utf-8", newline="\n") as f:
        if header[1].startswith("Trained only on Tatoeba"):
            f.write(f"# Tatoeba CC0 1.0 sentences (public domain) held out of core/src/main/assets/langid/{name}.tsv by tools/make_langid.py.\n")
            f.write(f"# export sha256 {sha}. Columns: Tatoeba sentence id, language, this script's answer (? = not sure), its margin x {SCALE}, sentence.\n")
        else:
            f.write(f"# CC0 1.0 sentences (public domain) held out of core/src/main/assets/langid/{name}.tsv by tools/make_langid.py;\n")
            f.write("# sources as in the table's header. Read every line before committing: docs/languages.md 3.1.\n")
            f.write(f"# Columns: sentence id (Tatoeba id, or file:line), language, this script's answer (? = not sure), its margin x {SCALE}, sentence.\n")
        for lang in model.langs:
            for i, s in sorted(held[lang], key=lambda r: (len(words(r[1])), r[0])):
                g, m = model.detect(s)
                f.write(f"{show_id(i)}\t{lang}\t{g or '?'}\t{m}\t{s}\n")
    print(f"{asset}: {asset.stat().st_size} bytes, {len(model.vocab)} features, sha256 {hashlib.sha256(asset.read_bytes()).hexdigest()}")
    print(f"{test}: {sum(len(v) for v in held.values())} lines")
    if sha:
        print(f"export: {o.tatoeba.name} sha256 {sha}, newest sentence {newest}")
    for n in notes:
        print(f"text: {n}")


if __name__ == "__main__":
    main()
