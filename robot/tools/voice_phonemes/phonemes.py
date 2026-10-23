#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Reads Phonemes.kt's log: per line, the text sherpa's Kokoro front end passed on and the phonemes (token ids turned
back into symbols with the model's tokens.txt). Prints a tab-separated table: id, language, text, front end, phonemes.

Usage: python3 -I phonemes.py TOKENS.txt LOG
"""
import sys


def symbols(path):
    m = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        if line[0] == " ":  # the space token
            m[int(line.split()[-1])] = " "
        else:
            s, i = line.rsplit(" ", 1)
            m[int(i)] = s
    return m


def main():
    sym = symbols(sys.argv[1])
    rows, cur, want_front = [], None, False
    for raw in open(sys.argv[2], encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        if line.startswith("@@LINE\t"):
            _, i, lang, text = line.split("\t", 3)
            cur = {"id": i, "lang": lang, "text": text, "front": [], "ipa": []}
            continue
        if line == "@@END":
            rows.append(cur)
            cur = None
            continue
        if cur is None:
            continue
        if want_front:
            cur["front"].append(line.strip())
            want_front = False
        elif "After replacing punctuations" in line:
            want_front = True
        else:
            parts = line.split()
            if parts and all(p.isdigit() for p in parts):
                # 0 starts and ends each piece; it isn't a sound.
                cur["ipa"].append("".join(sym.get(int(p), "?") for p in parts if p != "0").strip())
    for r in rows:
        print("\t".join([r["id"], r["lang"], r["text"], " | ".join(r["front"]), " | ".join(r["ipa"])]))


if __name__ == "__main__":
    main()
