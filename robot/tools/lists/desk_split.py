#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""LS4's desk run (docs/plans/lists-plan.md §3.5, §8.3): what the desk model makes of 200 runs of words with no separators, in
English and Québec French, through Robot's own LIST_SPLIT prompt.

The system prompt is read from core lists ListSplit.kt (its SYSTEM constant, which PromptFrozenTest pins in prompts/lists.txt);
the user turn quotes the run as ListSplit.request does. The model is asked through its own chat template (llama-server
/apply-template, thinking off), temperature 0, at most ListSplit.MAX_CHUNKS tokens, as the 1.3 and E2 desk checks did. The
replies are scored by Robot's own word check (ListSplitDeskTest, which reads the replies file), never here.

  ask <llama-server binary> <model.gguf> <ListSplit.kt> <split-desk.tsv> <replies.jsonl>   (starts and stops the server itself)

The server runs only while no Gradle build and no other llama-server runs, /home/user/Robot/RELEASE-BUILD-WAITING is absent and
6 GB stay free: it steps aside the moment one of those stops holding, and starts again when they hold (release builds first).
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PORT = 8094
# LLAMA_URL: ask a llama-server already running with the same model (another desk job's), and never start or stop one.
SHARED = os.environ.get("LLAMA_URL")
URL = SHARED or f"http://127.0.0.1:{PORT}"
FLAG = Path("/home/user/Robot/RELEASE-BUILD-WAITING")
MAX_CHUNKS = 160


def free_mb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) // 1024
    return 0


def running(pattern):
    return subprocess.run(["pgrep", "-f", pattern], capture_output=True).returncode == 0


def quiet(margin, own=False):
    return (not FLAG.exists() and not running("gradle-cli-m[a]in|gradle-wrapper\\.ja[r]") and free_mb() >= margin
            and (own or not running("llama-serve[r] -m ")))


class Server:
    def __init__(self, binary, model):
        self.binary, self.model, self.p = binary, model, None

    def up(self):
        if SHARED:
            return
        while self.p is None:
            if not quiet(6000):
                time.sleep(30)
                continue
            self.p = subprocess.Popen([self.binary, "-m", self.model, "--port", str(PORT), "-c", "4096", "-t", "4", "--jinja", "-np", "1"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for _ in range(240):
                try:
                    urllib.request.urlopen(URL + "/health", timeout=2)
                    return
                except Exception:
                    time.sleep(1)
            self.down()

    def check(self):
        if SHARED:
            return
        if self.p is not None and not quiet(2500, own=True):
            print("stepping aside for a build or low memory", file=sys.stderr)
            self.down()

    def down(self):
        if self.p is not None:
            self.p.terminate()
            try:
                self.p.wait(15)
            except subprocess.TimeoutExpired:
                self.p.kill()
            self.p = None


def post(path, body):
    r = urllib.request.Request(URL + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=900))


def system_prompt(kt):
    """ListSplit.SYSTEM: the string literals of its `const val SYSTEM =` statement, joined (they hold no escapes)."""
    src = Path(kt).read_text(encoding="utf-8")
    start = src.index("const val SYSTEM =")
    end = src.index("\n\n", start)
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', src[start:end]))


def runs(tsv):
    out = []
    for line in Path(tsv).read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            lang, run, items = line.split("\t")
            out.append({"lang": lang, "run": run, "items": items})
    return out


def ask(binary, model, kt, tsv, out_path):
    system = system_prompt(kt)
    done = set()
    if Path(out_path).exists():
        done = {r["run"] for r in map(json.loads, Path(out_path).read_text(encoding="utf-8").splitlines()) if r}
    srv = Server(binary, model)
    try:
        with open(out_path, "a", encoding="utf-8") as out:
            for r in runs(tsv):
                if r["run"] in done:
                    continue
                user = '"""\n' + r["run"] + '\n"""'
                while True:
                    srv.up()
                    try:
                        prompt = post("/apply-template", {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                                                          "chat_template_kwargs": {"enable_thinking": False}})["prompt"]
                        t = time.perf_counter()
                        res = post("/completion", {"prompt": prompt, "temperature": 0, "n_predict": MAX_CHUNKS, "cache_prompt": True})
                        break
                    except Exception as e:
                        print("retry", r["run"], e, file=sys.stderr)
                        srv.down()
                        if SHARED:
                            time.sleep(30)
                rec = {"lang": r["lang"], "run": r["run"], "raw": res["content"], "ms": round((time.perf_counter() - t) * 1000),
                       "prompt_tokens": res.get("tokens_evaluated"), "limit": bool(res.get("stopped_limit"))}
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out.flush()
                print(rec["ms"], "ms", repr(r["run"]), repr(res["content"][:100]), file=sys.stderr)
                srv.check()
    finally:
        srv.down()


if __name__ == "__main__":
    if len(sys.argv) == 7 and sys.argv[1] == "ask":
        ask(*sys.argv[2:])
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
