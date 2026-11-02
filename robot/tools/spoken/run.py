#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Runs the spoken tests against the test build (ai.robot_app.spoken) on a phone over adb (README.md here).

For each script it makes or finds the clips, pushes them to the test build's private folder, plays them through the
build's frame feed, reads what the build wrote down, checks it, and writes a report (never audio). It gives the
assistant role to the test build for the run and always gives it back, and deletes the clips from the phone.

Usage: ROBOT_SPOKEN_SERIALS=<serial> python3 -I tools/spoken/run.py --serial <serial> [--models <dir>] [--clips <dir>]
           [--report-dir <dir>] [--dry] [script.json ...]        (default: every script in tests/)
       python3 -I tools/spoken/run.py --serial <serial> --restore-role   (after a run that couldn't give the role back)
       python3 -I tools/spoken/run.py --serial <serial> --models <dir> --import-models   (the models, onto the test build)
       python3 -I tools/spoken/run.py --serial <serial> --pull-voice     (the recorder's files: to the computer, off the phone)

Exit: 0 every test passed; 1 a test failed; 2 a test couldn't run (blocked), or the guard refused; 3 the role wasn't
given back: the phone needs you. A phone not listed in ROBOT_SPOKEN_SERIALS is refused before any command reaches it.
"""
import argparse
import datetime
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import clips as clipmaker  # noqa: E402
import scripts  # noqa: E402

ROLE = "android.app.role.ASSISTANT"
SAFE_ARG = re.compile(r"[A-Za-z0-9._:-]{1,200}")
ACK_SECONDS = 8.0
RECORDING = re.compile(r"(en|fr)-[0-9]{3}\.s16")


class Blocked(Exception):
    """A step couldn't run: nothing was learned about Robot."""


class Refused(Blocked):
    """The guard refused, or something about the phone is wrong: the whole run stops."""


class Failed(Exception):
    """A check came out wrong."""


class Adb:
    def __init__(self, serial: str, path: str):
        self.serial, self.path = serial, path

    def _run(self, args: list[str], data=None, timeout: float = 60) -> subprocess.CompletedProcess:
        """`data` is bytes, or an open file for one too big to hold in memory."""
        where = {"stdin": data} if hasattr(data, "read") else {"input": data}
        try:
            return subprocess.run([self.path, "-s", self.serial, *args], capture_output=True, timeout=timeout, **where)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise Refused(f"adb didn't answer: {e}") from e

    def shell(self, *args: str, timeout: float = 60) -> tuple[int, str]:
        p = self._run(["shell", " ".join(shlex.quote(a) for a in args)], timeout=timeout)
        return p.returncode, p.stdout.decode("utf-8", "replace")

    def exec_in(self, data: bytes, *args: str) -> int:
        return self._run(["exec-in", " ".join(shlex.quote(a) for a in args)], data=data, timeout=120).returncode

    def exec_in_file(self, path: Path, *args: str) -> int:
        """Feeds a file of any size: 120 s plus a second for each 5 MB (about 21 MB/s was measured)."""
        with open(path, "rb") as f:
            return self._run(["exec-in", " ".join(shlex.quote(a) for a in args)], data=f, timeout=120 + path.stat().st_size / 5e6).returncode

    def exec_out(self, *args: str) -> tuple[int, bytes]:
        p = self._run(["exec-out", " ".join(shlex.quote(a) for a in args)], timeout=120)
        return p.returncode, p.stdout


class Role:
    """Gives the assistant role to the test build and back, with a journal on the PC: after a crash, the next run (or
    --restore-role) still knows whose it was."""

    def __init__(self, adb: Adb, package: str, journal: Path):
        self.adb, self.package, self.journal, self.taken = adb, package, journal, False

    def holders(self) -> list[str]:
        code, out = self.adb.shell("cmd", "role", "get-role-holders", ROLE)
        if code != 0:
            raise Refused("can't read who holds the assistant role")
        return re.findall(r"[A-Za-z][\w.]*", out)

    def take(self) -> None:
        if not self.restore():  # an earlier run's, if it left one
            raise Refused("an earlier run's role couldn't be given back")
        before = self.holders()
        if self.package in before:
            raise Refused(f"{self.package} already holds the assistant role and no record says whose it was. "
                          f"Give it back by hand: adb shell cmd role add-role-holder {ROLE} <package>")
        self.journal.parent.mkdir(parents=True, exist_ok=True)
        self.journal.write_text(json.dumps({"serial": self.adb.serial, "holders": before}), encoding="utf-8")
        self.taken = True
        code, _ = self.adb.shell("cmd", "role", "add-role-holder", ROLE, self.package)
        if code != 0 or self.holders() != [self.package]:
            raise Refused("couldn't give the assistant role to the test build")

    def restore(self) -> bool:
        """True when nothing is owed: the role is as it was before the run. Keeps the journal until that's so."""
        try:
            saved = json.loads(self.journal.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return True
        if saved.get("serial") != self.adb.serial:
            raise Refused(f"the role record {self.journal} is another phone's: restore that phone first")
        before = saved["holders"]
        try:
            for pkg in before:
                self.adb.shell("cmd", "role", "add-role-holder", ROLE, pkg)
            if not before:
                self.adb.shell("cmd", "role", "remove-role-holder", ROLE, self.package)
            ok = self.holders() == before
        except Refused:
            ok = False
        if ok:
            self.journal.unlink()
        return ok


class Run:
    def __init__(self, adb: Adb, cfg: dict, args, say=print):
        self.adb, self.cfg, self.args, self.say = adb, cfg, args, say
        self.pkg = cfg["package"]
        self.seq = 0
        self.poll = args.poll
        self.lines: list[dict] = []
        self.mark = 0  # how many turns there were when the last clip was played
        self.listen_due = False
        self.test_start = ""
        self.listening_status = None  # (when, the answer) of wait_listening's last status, for play's card check
        self.stopped = ""  # why the whole run must stop (the guard refused, or the phone went wrong)
        self.env = {**os.environ, "ANDROID_SERIAL": adb.serial, "ROBOT_SPOKEN_ADB": adb.path}

    # The guard and the commands.

    def guard(self, *extra: str) -> None:
        p = subprocess.run(["bash", str(HERE / "guard.sh"), self.pkg, *extra], env=self.env, capture_output=True, text=True)
        if p.returncode != 0:
            raise Refused(p.stdout.strip() or "the guard refused")

    def _read(self) -> None:
        code, out = self.adb.shell("run-as", self.pkg, "cat", self.cfg["results"])
        if code != 0:
            return  # not written yet
        lines = out.split("\n")[:-1]  # a last line with no newline is still being written
        self.lines = []
        for line in lines:
            try:
                self.lines.append(json.loads(line))
            except ValueError as e:
                raise Blocked(f"the results file has a line that isn't JSON: {e}") from e

    def turns(self) -> list[dict]:
        return [x for x in self.lines if x.get("type") == "turn"]

    def command(self, cmd: str, arg: str | None = None, value: str | None = None, guarded: bool = True) -> dict:
        """One broadcast, and the receiver's answer in the results file. No answer means the command didn't happen."""
        if guarded:
            self.guard()
        self.seq += 1
        extras = ["--ei", "seq", str(self.seq), "--es", "cmd", cmd]
        for key, v in (("arg", arg), ("value", value)):
            if v is not None:
                if not SAFE_ARG.fullmatch(v):
                    raise Blocked(f"{key} {v!r} isn't safe to pass over adb")
                extras += ["--es", key, v]
        code, _ = self.adb.shell("am", "broadcast", "-n", f"{self.pkg}/{self.cfg['receiver']}", *extras)
        if code != 0:
            raise Blocked(f"{cmd}: the broadcast failed")
        end = time.monotonic() + self.args.ack_seconds
        while True:
            self._read()
            for x in self.lines:
                if x.get("type") == "ack" and x.get("seq") == self.seq:
                    if x.get("ok") is not True:
                        raise Blocked(f"{cmd}: refused: {x.get('why', 'no reason given')}")
                    return x
            if time.monotonic() > end:
                raise Blocked(f"{cmd}: no answer from the test build")
            time.sleep(self.poll)

    # What must hold before anything runs.

    def preflight(self) -> dict:
        self.guard("--before-start")
        code, dump = self.adb.shell("dumpsys", "package", self.pkg)
        if code != 0 or f"Package [{self.pkg}]" not in dump:
            raise Refused(f"{self.pkg} isn't installed")
        if "DEBUGGABLE" not in dump:
            raise Refused(f"{self.pkg} isn't debuggable: this isn't the test build")
        for perm in self.cfg["neverGranted"]:
            if re.search(re.escape(perm) + r": granted=true", dump):
                raise Refused(f"the test build holds {perm}: revoke it first; it must never have it")
        # The listener opens the real microphone (its frames are thrown away for the clips'): without this it never starts.
        code, _ = self.adb.shell("pm", "grant", self.pkg, "android.permission.RECORD_AUDIO")
        if code != 0:
            raise Refused("the test build couldn't be given the microphone")
        code, listeners = self.adb.shell("settings", "get", "secure", "enabled_notification_listeners")
        if code != 0 or f"{self.pkg}/" in listeners:
            raise Refused("the test build has notification access (or the phone wouldn't say): it must never have it")
        code, out = self.adb.shell("am", "start", "-W", "-n", f"{self.pkg}/{self.cfg['activity']}")
        if code != 0:
            raise Refused("couldn't open the test build")
        self.guard()
        self.adb.shell("run-as", self.pkg, "rm", "-f", self.cfg["results"])  # an earlier run's answers mustn't count
        status = self.command("status", guarded=False).get("data") or {}
        if status.get("protocol") != self.cfg["protocol"]:
            raise Refused(f"the test build speaks protocol {status.get('protocol')}, this runner {self.cfg['protocol']}")
        version = re.search(r"versionName=(\S+)", dump)
        status["versionName"] = version.group(1) if version else "?"
        return status

    def landed(self, target: str, size: int) -> bool:
        """adb exec-in can return while the phone's cat is still writing (seen: 9.3 of 11.8 MB), so a short file gets a moment."""
        end = time.monotonic() + 2 + size / 20e6
        while True:
            code, got = self.adb.shell("run-as", self.pkg, "stat", "-c", "%s", target)
            if code == 0 and got.strip() == str(size):
                return True
            if time.monotonic() > end:
                return False
            time.sleep(min(self.poll, 0.2))

    def push_clips(self, names: list[str], folder: Path) -> None:
        clips = self.cfg["clipsDir"]
        code, _ = self.adb.shell("run-as", self.pkg, "mkdir", "-p", clips)
        if code != 0:
            raise Refused("run-as doesn't work on the test build")
        for name in names:
            data = (folder / name).read_bytes()
            target = f"{clips}/{name}"
            if self.adb.exec_in(data, "run-as", self.pkg, "sh", "-c", f"cat > {shlex.quote(target)}") != 0:
                raise Refused(f"couldn't push {name}")
            if not self.landed(target, len(data)):
                raise Refused(f"{name} arrived damaged")

    def delete_clips(self) -> bool:
        clips = self.cfg["clipsDir"]
        self.adb.shell("run-as", self.pkg, "rm", "-rf", clips)
        code, out = self.adb.shell("run-as", self.pkg, "ls", clips)
        return code != 0 or not out.strip()

    # The steps.

    def play(self, step: dict) -> None:
        name = clipmaker.name_of(clipmaker.spec_of(step), self.cfg)
        if not name.startswith("no-"):
            # A second lock beside the receiver's own: a card we don't know to be harmless means nothing plays but a no.
            # wait_listening's last answer counts if it's fresh: the guard and two broadcasts cost 5 s, and Robot gives up at 5.7 s.
            fresh, self.listening_status = self.listening_status, None
            status = fresh[1] if fresh and time.monotonic() - fresh[0] < 2.5 else self.command("status")
            shown = status.get("data", {}).get("cards")
            if not isinstance(shown, list):
                raise Blocked("the test build wouldn't say which cards are showing")
            risky = [k for k in shown if k not in self.cfg["harmlessCards"]]
            if risky:
                raise Failed(f"a risky card is showing ({', '.join(map(str, risky))}): {name} wasn't played")
        self._read()
        self.mark = len(self.turns())
        cards_open = self.args.silent and self.command("status").get("data", {}).get("cards")
        if self.listen_due or (self.args.silent and self.mark and not cards_open):
            self.listen_due = False
            # With no speech to end, Robot doesn't listen again by itself: the mic tap does it. A card listens by itself at once.
            self.command("open", "button")
            time.sleep(self.args.open_settle)
        self.command("play", name)

    def wait(self, seconds: float, found) -> dict | None:
        end = time.monotonic() + seconds
        while True:
            self._read()
            for t in self.turns()[self.mark:]:
                if found(t):
                    return t
            if time.monotonic() > end:
                return None
            time.sleep(self.poll)

    @staticmethod
    def matches(step: dict, text: str) -> bool:
        if "contains" in step:
            return scripts.norm(step["contains"]) in scripts.norm(text)
        if "regex" in step:
            return re.search(step["regex"], text) is not None
        if "equals" in step:
            return step["equals"] == text
        return True

    def last_turn(self) -> dict:
        self._read()
        new = self.turns()[self.mark:]
        if not new:
            raise Failed("no turn came after the last clip")
        return new[-1]

    def log_count(self, pattern: str) -> int:
        code, out = self.adb.shell("pm", "list", "packages", "-U", self.pkg)
        uid = re.search(rf"package:{re.escape(self.pkg)} uid:(\d+)", out)
        if code != 0 or not uid:
            raise Blocked("can't find the test build's uid")
        code, out = self.adb.shell("logcat", "-d", f"--uid={uid.group(1)}", "-T", self.test_start)
        if code != 0:
            raise Blocked("can't read the test build's log")
        return sum(1 for line in out.splitlines() if re.search(pattern, line))

    def check(self, step: dict) -> None:
        what, absent = step["what"], step.get("absent", False)
        if what == "log":
            n = self.log_count(step["regex"])
            seen, shown = n > 0, f"{n} lines"
        elif what == "showing":  # A card waiting now, with no new turn needed: "Stop." and a declined card make none.
            cards = self.command("status").get("data", {}).get("cards")
            if not isinstance(cards, list):
                raise Blocked("the test build wouldn't say which cards are showing")
            seen, shown = step["equals"] in cards, repr(cards)
        else:
            t = self.last_turn()
            if what in ("heard", "reply", "router"):
                text = str(t.get(what, ""))
                seen, shown = self.matches(step, text), repr(text)
            else:
                items = t.get("tools", []) if what == "tool" else [c.get("kind") for c in t.get("cards", [])]
                seen, shown = step["equals"] in items, repr(items)
        if seen == absent:
            want = next(step[k] for k in ("contains", "regex", "equals") if k in step)
            raise Failed(f"{what} {'has' if absent else 'lacks'} {want!r}: got {shown}")

    def step(self, s: dict) -> None:
        do = s["do"]
        if self.args.step_times:
            self.say(f"  {time.strftime('%H:%M:%S')} {do} {s.get('text', s.get('what', ''))}")
        if do == "open":
            self.command("open", s["how"])
            if s["how"] == "button":  # The app's button only shows the panel; the mic tap that listens is the same command again.
                time.sleep(self.args.open_settle)
                self.listen_due = True  # Pressed just before the clip (play): the listener gives up after 5.7 s of no speech.
        elif do == "say":
            self.play(s)
        elif do == "wait_reply":
            if self.wait(s["seconds"], lambda t: str(t.get("reply", "")) and self.matches(s, str(t.get("reply", "")))) is None:
                raise Failed(f"no matching reply in {s['seconds']} s")
        elif do == "wait_card":
            kinds = lambda t: [c.get("kind") for c in t.get("cards", [])]  # noqa: E731
            if self.wait(s["seconds"], lambda t: s["kind"] in kinds(t)) is None:
                raise Failed(f"no {s['kind']} card in {s['seconds']} s")
        elif do == "check":
            self.check(s)
        elif do == "pause":
            time.sleep(min(s["seconds"], self.args.max_pause))
        elif do == "wait_listening":  # As a person waits for the panel to show Robot listening before they answer.
            end = time.monotonic() + s["seconds"]
            while True:
                status = self.command("status")
                if status.get("data", {}).get("listening"):
                    self.listening_status = (time.monotonic(), status)
                    break
                if time.monotonic() > end:
                    raise Failed(f"Robot wasn't listening within {s['seconds']} s")
                time.sleep(self.poll)
        elif do == "end":
            self.command("end")

    def test(self, script: dict, metas: dict, status: dict) -> dict:
        """One script's result: pass, fail or blocked, with the step and why."""
        res = {"id": script["id"], "title": script["title"], "safety": script["safety"], "testBench": script.get("testBench", []),
               "result": "pass", "steps": 0, "turns": []}
        started = time.monotonic()
        step_no = 0
        try:
            if script.get("needs", {}).get("model") and not (status.get("models") or {}).get("gemma"):
                raise Blocked("needs the model, and the test build has none")
            for s in scripts.clip_specs(script):
                meta = metas[clipmaker.name_of(clipmaker.spec_of(s), self.cfg)]
                if meta["dropped"]:
                    raise Blocked(f"the recognizer can't read {s['text']!r} even clean (heard {meta['heardClean']!r})")
            code, now = self.adb.shell("date", "+%s")
            self.test_start = now.strip() + ".000" if code == 0 and now.strip().isdigit() else ""
            if not self.test_start:
                raise Blocked("can't read the phone's clock")
            self.adb.shell("run-as", self.pkg, "rm", "-f", self.cfg["results"])
            self.lines, self.mark = [], 0
            self.command("reset")
            for s in script.get("setup", []):
                self.command("set", s["set"], json.dumps(s["value"]) if not isinstance(s["value"], str) else s["value"])
            if self.args.silent:  # Last, so a script's own setup can't turn speech back on.
                self.command("set", "speakReplies", "NEVER")
            for step_no, s in enumerate(script["steps"], 1):
                self.step(s)
                res["steps"] = step_no
        except Refused as e:
            self.stopped = str(e)
            res.update(result="blocked", step=step_no, why=str(e))
        except Failed as e:
            res.update(result="fail", step=step_no, why=str(e))
        except Blocked as e:
            res.update(result="blocked", step=step_no, why=str(e))
        finally:
            res["seconds"] = round(time.monotonic() - started, 1)
            res["turns"] = [{k: t.get(k) for k in ("heard", "router", "reply", "cards", "tools", "times")} for t in self.turns()]
        if res["result"] != "pass" and not self.stopped:
            try:
                self.command("end")  # never leave a conversation or a card open for the next test
            except Refused as e:
                self.stopped = str(e)
            except Blocked as e:
                res["endFailed"] = str(e)
        return res


def report(results: list[dict], metas: dict, status: dict, notes: list[str], out: Path, stamp: str) -> Path:
    """No audio: what the test build heard and replied (its data is the demo set), and what each clip decoded to on the PC."""
    out.mkdir(parents=True, exist_ok=True)
    count = {k: sum(r["result"] == k for r in results) for k in ("pass", "fail", "blocked")}
    passed = sorted({b for r in results if r["result"] == "pass" for b in r["testBench"]})
    (out / f"{stamp}.json").write_text(json.dumps(
        {"build": status.get("versionName"), "count": count, "notes": notes, "passedTestBench": passed, "results": results,
         "clips": sorted(metas.values(), key=lambda m: m["name"])}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    md = [f"# Spoken tests, {stamp}", "", f"Build {status.get('versionName', '?')}. {count['pass']} passed, {count['fail']} failed, "
          f"{count['blocked']} blocked. {len(metas)} clips, {sum(m['dropped'] for m in metas.values())} dropped.", ""]
    md += [f"- {n}" for n in notes]
    md += ["", "| Test | Safety | Result | Seconds | Why |", "|---|---|---|---|---|"]
    for r in results:
        why = f"step {r['step']}: {r['why']}" if "why" in r else ""
        md.append(f"| {r['id']} | {r['safety']} | {r['result']} | {r.get('seconds', '')} | {why.replace('|', '/')} |")
    md += ["", f"Test Bench rows passed: {', '.join(passed) or 'none'}.", ""]
    path = out / f"{stamp}.md"
    path.write_text("\n".join(md), encoding="utf-8")
    return path


def load_scripts(paths: list[Path], say) -> list[dict] | None:
    out, bad = [], False
    for p in paths:
        script, problems = scripts.load(p)
        for problem in problems:
            say(f"{p.name}: {problem}")
            bad = True
        out.append(script)
    return None if bad or not out else out


def pull_voice(adb: Adb, cfg: dict, dest: Path, say=print) -> int:
    """Your recordings (the test build's recorder): to the computer, outside the repository, then off the phone. A file is deleted
    from the phone only once its full size has landed here."""
    pkg, folder = cfg["package"], cfg["recordingsDir"]
    code, listing = adb.shell("run-as", pkg, "ls", folder)
    names = [n for n in listing.split() if RECORDING.fullmatch(n)] if code == 0 else []
    dest.mkdir(parents=True, exist_ok=True)
    dest.chmod(0o700)
    failed = 0
    for n in names:
        path = f"{folder}/{n}"
        code, size = adb.shell("run-as", pkg, "stat", "-c", "%s", path)
        got, blob = adb.exec_out("run-as", pkg, "cat", path)
        if code != 0 or got != 0 or not blob or size.strip() != str(len(blob)):
            say(f"{n} didn't come across whole: it stays on the phone")
            failed += 1
            continue
        tmp = dest / f".{n}.part"
        tmp.write_bytes(blob)
        tmp.replace(dest / n)
        if adb.shell("run-as", pkg, "rm", path)[0] != 0:
            say(f"{n} couldn't be deleted from the phone")
            failed += 1
    say(f"{len(names) - failed} recordings in {dest}, {failed} problems")
    return 2 if failed else 0


def import_models(run: Run, models: Path, say=print, seconds: float = 900) -> int:
    """The computer's model files onto the test build: `<bundle id>/<file>` folders and the Gemma files beside them. The build
    checks each against its catalogue (size, SHA-256) and deletes the source either way."""
    files = [(f, f"{d.name}/{f.name}") for d in sorted(models.iterdir()) if d.is_dir() for f in sorted(d.iterdir()) if f.is_file()]
    files += [(f, f.name) for f in sorted(models.glob("*.litertlm"))]
    if not files:
        say(f"no models in {models}")
        return 2
    pkg, base = run.cfg["package"], run.cfg["importDir"]
    run.preflight()
    for f, rel in files:
        target = f"{base}/{rel}"
        run.adb.shell("run-as", pkg, "mkdir", "-p", target.rsplit("/", 1)[0])
        if run.adb.exec_in_file(f, "run-as", pkg, "sh", "-c", f"cat > {shlex.quote(target)}") != 0:
            raise Refused(f"couldn't push {rel}")
        if not run.landed(target, f.stat().st_size):
            raise Refused(f"{rel} arrived damaged")
        say(f"pushed {rel}")
    run.command("import-models")
    end, nudge = time.monotonic() + seconds, time.monotonic() + 15
    while True:
        if time.monotonic() > nudge:  # Android freezes the idle :voice process mid-import (seen: 13 minutes); a broadcast thaws it.
            run.command("status")
            nudge = time.monotonic() + 15
        run._read()
        lines = [x for x in run.lines if x.get("type") == "import"]
        done = next((x for x in lines if x.get("done")), None)
        if done:
            break
        if time.monotonic() > end:
            raise Blocked("the import didn't finish")
        time.sleep(run.poll)
    bad = [x.get("file") for x in lines if "ok" in x and x["ok"] is not True]
    say(f"imported {len(lines) - 1 - len(bad)} files, refused {bad}; listen {done.get('listen')}, gemma {done.get('gemma')}")
    return 2 if bad or not done.get("listen") else 0


def execute(args, say=print) -> int:
    cfg = scripts.config()
    paths = args.scripts or sorted(scripts.TESTS.glob("*.json"))
    allowed = {x for x in re.split(r"[,\s]+", os.environ.get("ROBOT_SPOKEN_SERIALS", "")) if x}
    phone_only = args.restore_role or args.pull_voice or args.import_models
    loaded = None if phone_only else load_scripts(paths, say)
    if not phone_only and loaded is None:
        say("the scripts have problems: nothing was run")
        return 2
    specs = [clipmaker.spec_of(s) for script in (loaded or []) for s in scripts.clip_specs(script)]
    if args.dry:
        names = {clipmaker.name_of(sp, cfg) for sp in specs}
        say(f"dry: {len(loaded)} scripts, {len(names)} clips, no phone touched")
        return 0
    if not args.serial:
        say("--serial is required")
        return 2
    if args.serial not in allowed:
        say(f"{args.serial} isn't in ROBOT_SPOKEN_SERIALS: nothing was sent to it")
        return 2
    adb = Adb(args.serial, args.adb)
    if args.pull_voice:
        return pull_voice(adb, cfg, args.voice_dir, say)
    if args.import_models:
        if not args.models:
            say("--import-models needs --models")
            return 2
        try:
            return import_models(Run(adb, cfg, args, say), args.models, say)
        except Blocked as e:
            say(f"the import stopped: {e}")
            return 2
    role = Role(adb, cfg["package"], args.clips / f"role-journal-{args.serial}.json")
    if args.restore_role:
        ok = role.restore()
        say("the role is as it was" if ok else f"the role couldn't be given back: see {role.journal}")
        return 0 if ok else 3

    def stop(signum, _):
        raise SystemExit(128 + signum)  # so that the finally below still gives the role back
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGHUP, stop)

    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d-%H%M%S")
    run, results, notes, metas, status, pushed = Run(adb, cfg, args, say), [], [], {}, {}, False
    code = 0
    try:
        try:
            metas = clipmaker.ensure(specs, args.clips, args.models, args.espeak, cfg, say)
        except (ImportError, OSError, RuntimeError) as e:
            raise Blocked(f"the clips aren't ready: {e}") from e
        run.guard("--before-start")
        role.take()
        status = run.preflight()
        names = sorted(n for n, m in metas.items() if not m["dropped"])
        pushed = True
        run.push_clips(names, args.clips)
        for script in loaded:
            r = run.test(script, metas, status)
            say(f"{r['id']}: {r['result']}" + (f" at step {r['step']}: {r['why']}" if "why" in r else ""))
            if args.turns:
                for t in r["turns"]:
                    say(f"  {json.dumps(t, ensure_ascii=False)}")
            results.append(r)
            if run.stopped:
                raise Blocked(run.stopped)
    except Blocked as e:  # also Refused: the run stops, and says why
        notes.append(f"The run stopped: {e}")
        say(f"the run stopped: {e}")
        code = 2
    finally:
        if pushed:
            try:
                if not run.delete_clips():
                    notes.append("The clips couldn't be deleted from the phone.")
                    code = max(code, 2)
            except Blocked as e:
                notes.append(f"The clips may still be on the phone: {e}")
                code = max(code, 2)
        try:
            given_back = role.restore()
        except Blocked:
            given_back = False
        if not given_back:
            notes.append(f"The assistant role wasn't given back: run {Path(__file__).name} --restore-role.")
            say(notes[-1])
            code = 3
    if any(r["result"] == "blocked" for r in results):
        code = max(code, 2)
    if any(r["result"] == "fail" for r in results):
        code = max(code, 1)
    if not results and code == 0:
        code = 2
    path = report(results, metas, status, notes, args.report_dir, stamp)
    say(f"report: {path}")
    return code


def parse(argv: list[str]):
    ap = argparse.ArgumentParser(description="Runs the spoken tests on a phone over adb.")
    ap.add_argument("--serial")
    ap.add_argument("--adb", default=os.environ.get("ROBOT_SPOKEN_ADB", "adb"))
    ap.add_argument("--models", type=Path, default=Path(os.environ["ROBOT_VOICE_MODELS"]) if os.environ.get("ROBOT_VOICE_MODELS") else None)
    ap.add_argument("--clips", type=Path, default=clipmaker.default_out())
    ap.add_argument("--espeak", type=Path, default=clipmaker.ROBOT / "voice/src/main/assets/espeak-ng-data")
    ap.add_argument("--report-dir", type=Path, default=clipmaker.ROBOT.parent / "docs/ops/spoken")
    ap.add_argument("--dry", action="store_true", help="check the scripts and count the clips; touch no phone")
    ap.add_argument("--restore-role", action="store_true")
    ap.add_argument("--turns", action="store_true", help="print each test's turns: what was heard and said, on the test build's demo data")
    ap.add_argument("--step-times", action="store_true", help="print when each step starts, to read beside the log")
    ap.add_argument("--silent", action="store_true", help="never speak replies, whatever a script sets: for hours the phone must stay quiet")
    ap.add_argument("--import-models", action="store_true", help="push --models to the test build and import them")
    ap.add_argument("--pull-voice", action="store_true", help="move the recorder's files from the phone to --voice-dir")
    ap.add_argument("--voice-dir", type=Path, default=Path(os.environ.get("ROBOT_SPOKEN_VOICE", Path.home() / "Robot/spoken-voice")))
    ap.add_argument("--poll", type=float, default=0.25, help=argparse.SUPPRESS)  # A slow answer eats the listener's 5.7 s no-speech window.
    ap.add_argument("--open-settle", type=float, default=1.5, help=argparse.SUPPRESS)
    ap.add_argument("--ack-seconds", type=float, default=ACK_SECONDS, help=argparse.SUPPRESS)
    ap.add_argument("--max-pause", type=float, default=60.0, help=argparse.SUPPRESS)
    ap.add_argument("scripts", nargs="*", type=Path)
    return ap.parse_args(argv)


if __name__ == "__main__":
    sys.exit(execute(parse(sys.argv[1:])))
