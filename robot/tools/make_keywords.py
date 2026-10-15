#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Writes voice/src/main/assets/kws/keywords.txt and kws/stop.txt for the keyword model.

- keywords.txt: the keyword spotter's own list, one empty line on purpose. sherpa-onnx adds a stream's keywords to
  this list, and since 0.2.3 Robot gives every stream its keywords at runtime (the wake phrases from Settings, spelled
  on the phone by core SentencePiece.kt, plus "STOP" while Robot speaks), so nothing is spotted that the settings
  don't ask for. Not 0 bytes: Android's asset reader in sherpa-onnx takes a 0-length buffer for a C string.
- stop.txt: "STOP" tokenised with the model's BPE, as sherpa-onnx's own text2token does it.

Also prints the spelling of "HEY ROBOT" (the default wake phrase), which SentencePieceTest checks the on-phone
encoder against with 114 other phrases (core/src/test/resources/kws/text2token-reference.tsv).

Uses sherpa-onnx's text2token (pip install sherpa-onnx==1.13.8 sentencepiece pypinyin), the tool the sherpa-onnx KWS
docs prescribe (same as the probe's script).

Usage: python3 tools/make_keywords.py <robot project dir>
"""
import sys
from pathlib import Path

from sherpa_onnx import text2token


def spell(build: Path, phrase: str) -> list:
    return text2token([phrase], tokens=str(build / "tokens.txt"), tokens_type="bpe", bpe_model=str(build / "bpe.model"))[0]


def main() -> None:
    root = Path(sys.argv[1])
    build = root / "tools" / "kws-build"
    kws = root / "voice" / "src" / "main" / "assets" / "kws"
    (kws / "keywords.txt").write_text("\n", encoding="utf-8")
    stop = " ".join(spell(build, "STOP")) + " @STOP"
    (kws / "stop.txt").write_text(stop + "\n", encoding="utf-8")
    print(f"{kws / 'keywords.txt'}: (one empty line)")
    print(f"{kws / 'stop.txt'}: {stop}")
    print("HEY ROBOT: " + " ".join(spell(build, "HEY ROBOT")))


if __name__ == "__main__":
    main()
