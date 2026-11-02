#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""The spoken tests' script format and its checker (README.md here describes the format).

Usage: python3 -I tools/spoken/scripts.py [script.json ...]   (default: every file in tools/spoken/tests/)
Prints one line per problem; exits 1 if there is any. Python's standard library only.
"""
import json
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
TESTS = HERE / "tests"
FORMAT = 1
TOP_KEYS = {"format", "id", "title", "safety", "testBench", "needs", "setup", "steps", "refusedWithNoCard"}
STEP_KEYS = {
    "open": {"how"},
    "say": {"text", "voice", "speed", "noise", "snr"},
    "wait_reply": {"seconds", "contains", "regex"},
    "wait_card": {"seconds", "kind"},
    "check": {"what", "contains", "regex", "equals", "absent"},
    "pause": {"seconds"},
    "wait_listening": {"seconds"},
    "end": set(),
}
CHECKS = {"heard", "reply", "router", "tool", "card", "log", "showing"}
MAX_WAIT = 180


def config() -> dict:
    return json.loads((HERE / "spoken.json").read_text(encoding="utf-8"))


def norm(s: str) -> str:
    """Lower case, no accents, anything but letters and digits a single space: how texts are compared."""
    s = unicodedata.normalize("NFKD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def has_phrase(text: str, phrases: list[str]) -> bool:
    t = f" {norm(text)} "
    return any(f" {norm(p)} " in t for p in phrases)


def is_decline(text: str, lang: str, cfg: dict) -> bool:
    """Only a text that is nothing but a "no": it's the one kind of clip that may play while a risky card shows."""
    return norm(text) in {norm(p) for p in cfg["declines"][lang]}


def touches_other_app(text: str, lang: str, cfg: dict) -> bool:
    """Timers, alarms, opening apps, maps, a search in your own words and music keys run at once, with no card (DEFAULT_APPROVALS)."""
    words = norm(text).split(" ")
    return has_phrase(text, cfg["otherAppWords"][lang]) or words[0] in cfg["otherAppStarts"][lang]


def clip_specs(script: dict) -> list[dict]:
    return [s for s in script["steps"] if s.get("do") == "say"]


def check_step(step, prior: list[dict], cfg: dict) -> list[str]:
    if not isinstance(step, dict) or step.get("do") not in STEP_KEYS:
        return [f"unknown step {step!r}"]
    do = step["do"]
    out = [f"{do}: unknown key {k}" for k in sorted(set(step) - STEP_KEYS[do] - {"do"})]
    said = any(p["do"] == "say" for p in prior)
    if do == "open":
        if step.get("how") not in ("wake", "button"):
            out.append("open: how must be wake or button")
    elif do == "say":
        if not any(p["do"] == "open" for p in prior):
            out.append("say: no open before it")
        text = step.get("text")
        if not isinstance(text, str) or not norm(text) or len(text) > 200:
            out.append("say: text must be 1 to 200 characters")
        if step.get("voice") not in cfg["voices"]:
            out.append(f"say: voice must be one of {sorted(cfg['voices'])}")
        if step.get("speed", 1.0) not in cfg["speeds"]:
            out.append(f"say: speed must be one of {cfg['speeds']}")
        noise = step.get("noise", "none")
        if noise not in cfg["noises"]:
            out.append(f"say: noise must be one of {sorted(cfg['noises'])}")
        elif noise == "none" and "snr" in step:
            out.append("say: snr with no noise")
        elif noise != "none" and step.get("snr") not in cfg["noises"][noise]:
            out.append(f"say: snr must be one of {cfg['noises'][noise]}")
    elif do in ("wait_reply", "wait_card", "pause", "wait_listening"):
        limit = 60 if do in ("pause", "wait_listening") else MAX_WAIT
        sec = step.get("seconds")
        if not isinstance(sec, (int, float)) or isinstance(sec, bool) or not 0 < sec <= limit:
            out.append(f"{do}: seconds must be above 0, at most {limit}")
        if do not in ("pause", "wait_listening") and not said:
            out.append(f"{do}: nothing said before it")
        if do == "wait_card" and step.get("kind") not in cfg["harmlessCards"] + cfg["riskyCards"]:
            out.append(f"wait_card: unknown kind {step.get('kind')!r}")
        if do == "wait_reply":
            out += check_regex(step)
    elif do == "check":
        out += check_check(step, said, cfg)
    return out


def check_regex(step: dict) -> list[str]:
    if "regex" not in step:
        return []
    try:
        re.compile(step["regex"])
        return []
    except (re.error, TypeError) as e:
        return [f"{step['do']}: bad regex: {e}"]


def check_check(step: dict, said: bool, cfg: dict) -> list[str]:
    what = step.get("what")
    if what not in CHECKS:
        return [f"check: what must be one of {sorted(CHECKS)}"]
    out = check_regex(step)
    if what not in ("log", "showing") and not said:
        out.append(f"check {what}: nothing said before it")
    given = [k for k in ("contains", "regex", "equals") if k in step]
    if len(given) != 1:
        out.append(f"check {what}: needs one of contains, regex, equals")
    if "absent" in step and step["absent"] is not True:
        out.append(f"check {what}: absent can only be true")
    if what == "log" and given != ["regex"]:
        out.append("check log: needs a regex (it reports a count, never the lines)")
    if what in ("router", "tool", "card") and given != ["equals"]:
        out.append(f"check {what}: needs equals")
    if what == "router" and "equals" in step and step["equals"] not in cfg["routers"]:
        out.append(f"check router: unknown router {step['equals']!r}")
    if what == "router" and step.get("equals") in cfg["routersNoTurn"]:
        out.append(f"check router: {step['equals']} never takes a turn itself, its turns report as model")
    if what == "router" and step.get("equals") in cfg["routersOff"] and step.get("absent") is not True:
        out.append(f"check router: {step['equals']} is off in the test build, so it can only be absent")
    kinds = cfg["harmlessCards"] + cfg["riskyCards"]
    if what in ("tool", "card") and "equals" in step and step["equals"] not in kinds:
        out.append(f"check {what}: unknown kind {step['equals']!r}")
    return out


def check_safety(script: dict, cfg: dict) -> list[str]:
    """The rules that keep a spoken test from sending, calling, deleting or paying (README, "Safety")."""
    out, safety, steps = [], script.get("safety"), [s for s in script["steps"] if isinstance(s, dict)]
    if safety not in cfg["safetyClasses"]:
        return [f"safety must be one of {cfg['safetyClasses']}"]
    if not steps or steps[-1].get("do") != "end":
        out.append("the last step must be end")
    risky_card_at = None
    harmless_card_open = False
    for i, s in enumerate(steps):
        if s.get("do") == "wait_card":
            if s.get("kind") in cfg["harmlessCards"]:
                harmless_card_open = True
            elif risky_card_at is None:
                risky_card_at = i
        elif s.get("do") == "end":
            risky_card_at, harmless_card_open = None, False
        elif s.get("do") == "say" and s.get("voice") in cfg["voices"] and isinstance(s.get("text"), str):
            lang, text = cfg["voices"][s["voice"]]["lang"], s["text"]
            decline = is_decline(text, lang, cfg)
            if risky_card_at is not None and not decline:
                out.append(f"step {i + 1}: only a plain no may be said at a risky card, not {text!r}")
            if touches_other_app(text, lang, cfg):
                out.append(f"step {i + 1}: {text!r} could run another app, or a real alarm or timer, with no card: it stays out of unattended runs")
            if safety != "stops-at-card" and not decline and has_phrase(text, cfg["riskyWords"][lang]):
                out.append(f"step {i + 1}: {text!r} could send, call, delete or pay: safety must be stops-at-card")
            if has_phrase(text, cfg["yesWords"][lang]):
                if safety != "robot-data":
                    out.append(f"step {i + 1}: a yes is only for safety robot-data, at a harmless card")
                elif not harmless_card_open:
                    out.append(f"step {i + 1}: a yes with no harmless card waited for before it")
                harmless_card_open = False
    kinds = [s.get("kind") for s in steps if s.get("do") == "wait_card"]
    refused = script.get("refusedWithNoCard") is True and any(s.get("do") == "check" and s.get("what") == "card" and s.get("absent") for s in steps)
    if safety == "stops-at-card" and not refused and not any(k in cfg["riskyCards"] for k in kinds):
        out.append("safety stops-at-card needs a wait_card of a risky kind, or refusedWithNoCard and a check that no card came")
    if safety == "read" and kinds:
        out.append("safety read waits for no card")
    return out


def check_script(script, name: str, cfg: dict) -> list[str]:
    if not isinstance(script, dict):
        return ["not a JSON object"]
    out = [f"unknown key {k}" for k in sorted(set(script) - TOP_KEYS)]
    if script.get("format") != FORMAT:
        out.append(f"format must be {FORMAT}")
    if script.get("id") != name or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", name):
        out.append(f"id must be the file's name in lower case, letters, digits and dashes ({name})")
    if not isinstance(script.get("title"), str) or not script["title"].strip():
        out.append("needs a title")
    bench = script.get("testBench", [])
    if not isinstance(bench, list) or not all(isinstance(b, str) for b in bench):
        out.append("testBench must be a list of Test Bench ids")
    needs = script.get("needs", {})
    if not isinstance(needs, dict) or set(needs) - {"model"} or not all(v is True or v is False for v in needs.values()):
        out.append('needs may only be {"model": true or false}')
    setup = script.get("setup", [])
    if not isinstance(setup, list):
        out.append("setup must be a list")
    else:
        for s in setup:
            out += check_setting(s, cfg)
    steps = script.get("steps")
    if not isinstance(steps, list) or not steps:
        return out + ["needs steps"]
    prior: list[dict] = []
    for i, s in enumerate(steps):
        out += [f"step {i + 1}: {p}" for p in check_step(s, prior, cfg)]
        if isinstance(s, dict) and s.get("do") in STEP_KEYS:
            prior.append(s)
    return out + check_safety(script, cfg)


def check_setting(s, cfg: dict) -> list[str]:
    if not isinstance(s, dict) or set(s) != {"set", "value"}:
        return [f'setup: {s!r} must be {{"set": name, "value": value}}']
    kind = cfg["settings"].get(s["set"])
    v = s["value"]
    if kind is None:
        return [f"setup: {s['set']} isn't on the short list of settings"]
    ok = (v in kind if isinstance(kind, list) else
          v is True or v is False if kind == "boolean" else
          isinstance(v, (int, float)) and not isinstance(v, bool) if kind == "number" else isinstance(v, str))
    return [] if ok else [f"setup: {s['set']} can't be {v!r}"]


def load(path: Path) -> tuple[dict | None, list[str]]:
    """The script and its problems. Anything unreadable is a problem, never a skipped file."""
    try:
        script = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        return None, [f"can't read it: {e}"]
    return script, check_script(script, path.stem, config())


def check_coverage(paths: list[Path]) -> list[str]:
    """coverage.json and the scripts agree: each script names its Test Bench rows, and each row names exactly its scripts."""
    cov = json.loads((HERE / "coverage.json").read_text(encoding="utf-8"))
    named: dict[str, list[str]] = {}
    out = []
    for p in paths:
        script = load(p)[0] or {}
        bench = script.get("testBench") or []
        if not bench:
            out.append(f"{p.name}: names no Test Bench row")
        for b in bench:
            named.setdefault(b, []).append(p.stem)
    for b in sorted(set(named) | set(cov["rows"])):
        if sorted(named.get(b, [])) != sorted(cov["rows"].get(b, {}).get("scripts", [])):
            out.append(f"coverage.json: {b} doesn't list the scripts that name it")
    left = [r for group in cov["notScripted"].values() for r in group["rows"]]
    out += [f"coverage.json: {r} is both covered and not scripted, or listed twice" for r in sorted(set(left))
            if left.count(r) > 1 or r in cov["rows"]]
    return out


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv] or sorted(TESTS.glob("*.json"))
    bad = 0
    if not argv:
        for problem in check_coverage(paths):
            print(problem)
            bad += 1
    for p in paths:
        for problem in load(p)[1]:
            print(f"{p.name}: {problem}")
            bad += 1
    print(f"{len(paths)} scripts, {bad} problems")
    return 1 if bad or not paths else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
