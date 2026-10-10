#!/usr/bin/env python3
"""Writes voice/src/main/assets/kws/keywords.txt: "HEY ROBOT" tokenised with the KWS model's BPE.

Uses sherpa-onnx's own text2token (pip install sherpa-onnx==1.13.8 sentencepiece pypinyin), the tool
the sherpa-onnx KWS docs prescribe (same as the probe's script). The keyword line carries no :boost or
#threshold, so the app's ListenerConfig (keywordsScore / keywordsThreshold) applies.

Usage: python3 tools/make_keywords.py <robot project dir>
"""
import sys
from pathlib import Path

from sherpa_onnx import text2token

PHRASE = "HEY ROBOT"


def main() -> None:
    root = Path(sys.argv[1])
    build = root / "tools" / "kws-build"
    tokens = text2token([PHRASE], tokens=str(build / "tokens.txt"), tokens_type="bpe",
                        bpe_model=str(build / "bpe.model"))[0]
    line = " ".join(tokens) + " @" + PHRASE.replace(" ", "_")
    out = root / "voice" / "src" / "main" / "assets" / "kws" / "keywords.txt"
    out.write_text(line + "\n", encoding="utf-8")
    print(f"{out}: {line}")


if __name__ == "__main__":
    main()
