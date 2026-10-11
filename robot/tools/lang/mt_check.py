#!/usr/bin/env python3
"""Runs a language's translation test set through the desk engines, for a person to mark (docs/languages.md 4.3).

The test set is a TSV like tools/lang/fr-CA/mt-test.tsv: lines starting with "#" are comments, the rest
"direction<TAB>source<TAB>meaning[<TAB>anything else]", direction "xx-yy" (ISO 639-1). This script writes
"direction, source, meaning, engine output, verdict (empty), milliseconds" per engine to <out>.tsv; a person who
speaks the language fills in the verdict: right, partly or wrong (was the meaning kept?).

Engines (give one or both):
- --bergamot xx-yy=<config.yml>: Mozilla Bergamot through the `bergamot` wheel (pip install bergamot==0.4.5), with a
  config.yml beside the model, lexical shortlist and vocabulary from Mozilla's models.json (translation-plan.md 1.4).
- --llama <llama.cpp binary> --gguf <model>: Gemma through llama.cpp, raw Gemma 4 turn format, temperature 0, the
  system prompts below (the ones the 2026-10-10 Québec test used). The phone runs Gemma's LiteRT build, not a GGUF:
  the in-app check is the one that counts (docs/languages.md 4.3); this is the quick desk look.
--name sets the language the prompts name ("Quebec French"); --style adds a line to the to-that-language prompt
("the way a Quebecer would write it in a text message. Use 'tu'.").

Usage: python3 -I tools/lang/mt_check.py <test.tsv> <out prefix> [--bergamot fr-en=cfg.yml ...] [--llama BIN --gguf M]
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

NAMES = {"en": "English", "fr": "French", "es": "Spanish", "de": "German", "it": "Italian", "pt": "Portuguese"}


def read_set(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            p = line.split("\t")
            rows.append((p[0], p[1], p[2] if len(p) > 2 else ""))
    return rows


def prompts(direction, name, style):
    src, tgt = direction.split("-")
    s = name.get(src) or NAMES.get(src, src)
    t = name.get(tgt) or NAMES.get(tgt, tgt)
    if src in name:
        return f"Translate the user's message from {s} to natural {t}. Keep the tone and register. Reply with the translation only."
    extra = f", {style.get(tgt)}" if style.get(tgt) else "."
    return f"Translate the user's message from {s} to {t}{extra} Reply with the translation only."


def llama(binary, gguf, system, text):
    t = time.perf_counter()
    p = subprocess.run(
        [binary, "-m", gguf, "-p", f"<|turn>system\n{system}<turn|>\n<|turn>user\n{text}<turn|>\n<|turn>model\n",
         "-no-cnv", "-n", "96", "--temp", "0", "-t", "4", "-c", "1024", "--no-display-prompt"],
        capture_output=True, text=True, timeout=300, stdin=subprocess.DEVNULL)
    out = re.sub(r"\s*\[end of text\]\s*$", "", p.stdout.strip())
    return out, (time.perf_counter() - t) * 1000


def main():
    a = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    a.add_argument("test")
    a.add_argument("out")
    a.add_argument("--bergamot", action="append", default=[], metavar="xx-yy=CONFIG")
    a.add_argument("--llama")
    a.add_argument("--gguf")
    a.add_argument("--name", action="append", default=[], metavar="xx=NAME", help='e.g. fr="Quebec French"')
    a.add_argument("--style", action="append", default=[], metavar="xx=TEXT")
    o = a.parse_args()
    rows = read_set(o.test)
    name = dict(x.split("=", 1) for x in o.name)
    style = dict(x.split("=", 1) for x in o.style)
    results = {}
    if o.bergamot:
        import bergamot  # only when asked: the wheel is a desk tool, never part of the app
        service = bergamot.Service(bergamot.ServiceConfig(numWorkers=1, logLevel="off"))
        opts = bergamot.ResponseOptions(alignment=False, qualityScores=False, HTML=False)
        models = {d: service.modelFromConfigPath(c) for d, c in (x.split("=", 1) for x in o.bergamot)}
        out = []
        for d, src, meaning in rows:
            if d not in models:
                continue
            t = time.perf_counter()
            r = service.translate(models[d], bergamot.VectorString([src]), opts)
            out.append((d, src, meaning, r[0].target.text, "", f"{(time.perf_counter() - t) * 1000:.0f}"))
        results["bergamot"] = out
    if o.llama:
        if not o.gguf:
            a.error("--llama needs --gguf")
        results["gemma"] = [(d, src, meaning, *(lambda r: (r[0], "", f"{r[1]:.0f}"))(llama(o.llama, o.gguf, prompts(d, name, style), src)))
                            for d, src, meaning in rows]
    if not results:
        a.error("no engine: give --bergamot and/or --llama")
    for engine, out in results.items():
        path = Path(f"{o.out}-{engine}.tsv")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"# {engine} on {Path(o.test).name}; fill the verdict column: right, partly or wrong.\n")
            f.write("# Columns: direction, source, meaning, output, verdict, milliseconds.\n")
            for r in out:
                f.write("\t".join(r) + "\n")
        print(f"{path}: {len(out)} lines")


if __name__ == "__main__":
    sys.exit(main())
