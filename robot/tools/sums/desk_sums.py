#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""E2's desk run (docs/plans/everyday-set-plan.md §4.8): how many of 50 sums the desk model gets right with and without
Robot's calculate tool, and how many Robot's own SumsRouter answers right by itself.

The prompts and tools are Robot's own (SumsDeskTest, SUMS_DESK=dump: Prompt.system, ToolRouter, ToolSpecs), declared to the
model through Gemma 4's chat template (llama-server /apply-template, thinking off), temperature 0, as the 1.3 desk checks did.
The model's calculate calls are read and worked out by Robot's own code (SumsDeskTest, SUMS_DESK=calls). Expected answers are
computed here with Python's Decimal from questions.tsv, never with Robot's code.

  ask   <llama-server binary> <model.gguf> <prompts.jsonl> <replies.jsonl>   (starts and stops the server itself)
  score <questions.tsv> <prompts.jsonl> <replies.jsonl> <calls.jsonl>

The server runs only while no Gradle build runs, the release flag file named by RELEASE_FLAG (if set) is absent and 6 GB stay
free: it is stopped the moment one of those stops holding, and started again when they hold (release builds come first).
"""
import ast
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from decimal import Decimal, getcontext
from pathlib import Path

getcontext().prec = 34
PORT = 8093
URL = f"http://127.0.0.1:{PORT}"
FLAG = Path(os.environ["RELEASE_FLAG"]) if os.environ.get("RELEASE_FLAG") else None


def free_mb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) // 1024
    return 0


def gradle_running():
    return subprocess.run(["pgrep", "-f", "gradle-cli-m[a]in|gradle-wrapper\\.ja[r]"], capture_output=True).returncode == 0


def quiet(margin):
    return not (FLAG and FLAG.exists()) and not gradle_running() and free_mb() >= margin


class Server:
    def __init__(self, binary, model):
        self.binary, self.model, self.p = binary, model, None

    def up(self):
        while self.p is None:
            # 6 GB free before it starts (it takes about 3.5 GB); while it runs, 2.5 GB more must stay free.
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
        if self.p is not None and not quiet(2500):
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


def openai_tools(specs):
    return [{"type": "function", "function": s} for s in specs]


def ask(binary, model, prompts_path, out_path):
    prompts = [json.loads(l) for l in Path(prompts_path).read_text().splitlines() if l.strip()]
    done = set()
    if Path(out_path).exists():
        done = {(r["id"], r["cond"]) for r in map(json.loads, Path(out_path).read_text().splitlines()) if r}
    srv = Server(binary, model)
    try:
        with open(out_path, "a", encoding="utf-8") as out:
            for p in prompts:
                for cond, tools in (("without", p["tools_without"]), ("with", p["tools_with"])):
                    if (p["id"], cond) in done or cond not in os.environ.get("DESK_CONDS", "without,with").split(","):
                        continue
                    while True:
                        srv.up()
                        try:
                            prompt = post("/apply-template", {"messages": [{"role": "system", "content": p["system"]}, {"role": "user", "content": p["user"]}],
                                                              "tools": openai_tools(tools), "chat_template_kwargs": {"enable_thinking": False}})["prompt"]
                            t = time.perf_counter()
                            r = post("/completion", {"prompt": prompt, "temperature": 0, "n_predict": 200, "cache_prompt": True})
                            break
                        except Exception as e:
                            print("retry", p["id"], cond, e, file=sys.stderr)
                            srv.down()
                    rec = {"id": p["id"], "lang": p["lang"], "cond": cond, "raw": r["content"], "ms": round((time.perf_counter() - t) * 1000),
                           "prompt_tokens": r.get("tokens_evaluated"), "tools": [s["name"] for s in tools]}
                    out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                    out.flush()
                    print(p["id"], cond, rec["ms"], "ms", repr(r["content"][:90]), file=sys.stderr)
                    srv.check()
    finally:
        srv.down()


# ---------------------------------------------------------------- scoring

OPS = {ast.Add: lambda a, b: a + b, ast.Sub: lambda a, b: a - b, ast.Mult: lambda a, b: a * b, ast.Div: lambda a, b: a / b, ast.Pow: lambda a, b: a ** b}


def value(src):
    """An expected answer from questions.tsv: numbers, + - * / **, parentheses and sqrt(), read as Decimal (no eval)."""
    def walk(n):
        if isinstance(n, ast.Expression):
            return walk(n.body)
        if isinstance(n, ast.Constant) and isinstance(n.value, (int, float)):
            return Decimal(repr(n.value)) if isinstance(n.value, float) else Decimal(n.value)
        if isinstance(n, ast.BinOp) and type(n.op) in OPS:
            return OPS[type(n.op)](walk(n.left), walk(n.right))
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.USub):
            return -walk(n.operand)
        if isinstance(n, ast.Call) and getattr(n.func, "id", "") == "sqrt" and len(n.args) == 1:
            return walk(n.args[0]).sqrt()
        raise ValueError(src)
    return walk(ast.parse(src, mode="eval"))


NUM = re.compile(r"(?<![\d.,])-?\d{1,3}(?:[   ,]\d{3})+(?:[.,]\d+)?(?![\d])|(?<![\d.,])-?\d+(?:[.,]\d+)?(?![\d])")


def numbers(text, lang):
    """The numbers written in [text], read the way [lang] writes them (« 1 500,25 », "1,500.25")."""
    out = []
    for m in NUM.finditer(text or ""):
        s = m.group(0)
        if lang == "fr":
            s = re.sub(r"[   ]", "", s).replace(",", ".")
        else:
            s = s.replace(",", "") if re.search(r",\d{3}(?!\d)", s) else s.replace(",", ".")
            s = re.sub(r"[   ]", "", s)
        try:
            out.append(Decimal(s))
        except Exception:
            pass
    return out


def right(text, lang, judge, expected):
    t = (text or "").lower()
    if judge == "words":
        e = expected.lower()
        month = re.sub(r"\d+", "", e).strip()
        day = re.findall(r"\d+", e)
        return month in t and all(re.search(rf"\b{d}\b", t) for d in day)
    exps = [value(x) for x in expected.split("|")]
    for m in numbers(text, lang):
        for e in exps:
            d = max(0, -m.as_tuple().exponent)
            # Money to the cent: the amount rounded to the cent, or a more precise one within half a cent (172.4625 for $172.46).
            if judge == "money" and (abs(m - e.quantize(Decimal("0.01"), rounding="ROUND_HALF_UP")) < Decimal("0.0001") or (d > 2 and abs(m - e) <= Decimal("0.005"))):
                return True
            if judge == "temp" and abs(m - e) <= Decimal("0.5"):
                return True
            if judge == "kitchen" and abs(m - e) <= Decimal("0.05"):
                return True
            if judge == "unit" and abs(m - e) <= abs(e) * Decimal("0.005"):
                return True
            if judge == "exact":
                # The exact answer's decimals; 99 for one that doesn't end (450/7, the square root of 50).
                ed = max(0, -e.normalize().as_tuple().exponent) if len(e.normalize().as_tuple().digits) < 30 else 99
                if ed <= d and abs(m - e) < Decimal("1e-12"):
                    return True
                if ed > d and d >= 2 and abs(m - e) <= Decimal(5) / Decimal(10) ** (d + 1):
                    return True
    return False


def score(questions_path, prompts_path, replies_path, calls_path):
    qs = [l.split("\t") for l in Path(questions_path).read_text().splitlines() if l.strip() and not l.startswith("#")]
    prompts = {p["id"]: p for p in map(json.loads, Path(prompts_path).read_text().splitlines()) if p}
    replies = {(r["id"], r["cond"]): r for r in map(json.loads, Path(replies_path).read_text().splitlines()) if r}
    calls = {(c["id"], c["cond"]): c for c in map(json.loads, Path(calls_path).read_text().splitlines()) if c}
    rows = []
    for qid, lang, kind, judge, expected, text in qs:
        p = prompts[qid]
        router = p["router"]
        r_ok = router is not None and right(router, lang, judge, expected)
        wo = replies[(qid, "without")]
        wo_text = "" if "<|tool_call>" in wo["raw"] else wo["raw"]
        wo_ok = right(wo_text, lang, judge, expected)
        wi = replies[(qid, "with")]
        c = calls.get((qid, "with"), {"calls": [], "code": []})
        if c["calls"]:
            wi_how = "called, right" if any(right(x, lang, judge, expected) for x in c["code"]) else "called, wrong"
        else:
            wi_how = "no call, prose right" if right(wi["raw"], lang, judge, expected) else "no call, prose wrong"
        rows.append(dict(id=qid, lang=lang, kind=kind, router=("taken, right" if r_ok else "taken, wrong") if router is not None else "not taken",
                         without="right" if wo_ok else "wrong", with_=wi_how, described=p["sums_group"], q=text,
                         wo=wo["raw"].replace("\n", " ")[:160], wi=wi["raw"].replace("\n", " ")[:160], code=" | ".join(c["code"])[:160]))
    for r in rows:
        print("\t".join(str(r[k]) for k in ("id", "lang", "kind", "described", "router", "without", "with_", "q", "wo", "wi", "code")))
    print()
    for lang in ("en", "fr", None):
        sel = [r for r in rows if lang is None or r["lang"] == lang]
        n = len(sel)
        name = {"en": "English", "fr": "French", None: "All"}[lang]
        print(f"{name}: {n} sums")
        print(f"  Robot's router: {sum(r['router'] == 'taken, right' for r in sel)} taken and right, {sum(r['router'] == 'taken, wrong' for r in sel)} taken and wrong, {sum(r['router'] == 'not taken' for r in sel)} left to the model")
        print(f"  model without calculate: {sum(r['without'] == 'right' for r in sel)} right of {n}")
        print(f"  model with calculate (as the phone describes it): {sum(r['with_'] == 'called, right' for r in sel)} called and right, "
              f"{sum(r['with_'] == 'called, wrong' for r in sel)} called with the wrong numbers, {sum(r['with_'] == 'no call, prose right' for r in sel)} no call but right, "
              f"{sum(r['with_'] == 'no call, prose wrong' for r in sel)} no call and wrong; calculate described for {sum(bool(r['described']) for r in sel)}")
        model_only = [r for r in sel if r["router"] == "not taken"]
        if model_only:
            print(f"  the {len(model_only)} the router leaves to the model: without calculate {sum(r['without'] == 'right' for r in model_only)} right; "
                  f"with it {sum(r['with_'] in ('called, right', 'no call, prose right') for r in model_only)} right")
        both = sum((r["router"] == "taken, right") or (r["router"] == "not taken" and r["with_"] in ("called, right", "no call, prose right")) for r in sel)
        print(f"  Robot after E2 (router first, then the model with calculate): {both} right of {n}")


if __name__ == "__main__":
    if sys.argv[1] == "ask":
        ask(*sys.argv[2:6])
    elif sys.argv[1] == "score":
        score(*sys.argv[2:6])
    else:
        sys.exit(__doc__)
