#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""The spoken tools' own tests, on a stand-in for adb: they never touch a phone. SpokenToolsTest runs them in the suite.

Usage: python3 -I tools/spoken/selftest.py     (the noise tests need numpy, and are skipped without it)
"""
import copy
import io
import json
import os
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import clips  # noqa: E402
import run as runner  # noqa: E402
import scripts  # noqa: E402

CFG = scripts.config()
PKG = CFG["package"]
SERIAL = "TESTSERIAL"
ROBOT = "ai.robot_app"

FAKE_ADB = r'''#!/usr/bin/env -S python3 -I -S
import json, os, shlex, sys
from pathlib import Path
root = Path(os.environ["FAKE_ADB_DIR"])
args = sys.argv[1:]
serial = None
if args[:1] == ["-s"]:
    serial, args = args[1], args[2:]
with open(root / "calls.log", "a") as f:
    f.write(f"{serial or os.environ.get('ANDROID_SERIAL')}|{' '.join(args)}\n")
st = json.loads((root / "state.json").read_text())
phone = root / "phone"
pkg = st["pkg"]
def save():
    t = root / f"state.json.{os.getpid()}"  # whole file or nothing: the test reads it while this runs
    t.write_text(json.dumps(st))
    os.replace(t, root / "state.json")
def results_add(x):
    p = phone / "files/spoken/results.jsonl"
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "a") as f:
        f.write(json.dumps(x) + "\n")
def out(s, code=0):
    sys.stdout.write(s)
    sys.exit(code)
if st.get("dead"):
    sys.exit(1)
if args[0] == "exec-in":
    t = shlex.split(args[1])
    target = phone / t[4].split("> ", 1)[1].strip("'")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(sys.stdin.buffer.read())
    sys.exit(0)
t = shlex.split(" ".join(args[1:]))
c = " ".join(t)
if c.startswith("dumpsys activity"):
    out(f"  topResumedActivity=ActivityRecord{{a u0 {st['top']}/.Main t1}}\n")
if t[0] == "pidof":
    out("1234\n" if t[1] == st["top"] else "")
if c == "dumpsys window":
    out(f"  mCurrentFocus={st['focus']}\n  isKeyguardShowing={str(st['keyguard']).lower()}\n")
if c == "dumpsys window displays":
    out(f"mCurrentRotation=ROTATION_{st['rotation']}\n")
if c == "dumpsys telecom":
    out("mCalls:\n  Call [1]\n" if st["call"] else "mCalls:\n")
if c == "dumpsys display":
    out(f"mScreenState={st['screen']}\n")
if c.startswith("dumpsys package"):
    if not st["installed"]:
        out("")
    flags = "DEBUGGABLE" if st["debuggable"] else "HAS_CODE"
    grants = "".join(f"  {p}: granted=true\n" for p in st["granted"])
    out(f"Package [{pkg}] (abc):\n  pkgFlags=[ {flags} ]\n  versionName=1.1-test\n{grants}")
if c.startswith("settings get"):
    out(st["listeners"] + "\n")
if t[:2] == ["am", "start"]:
    st["top"], st["focus"] = pkg, f"Window{{a u0 {pkg}/.Main}}"
    save()
    out("Status: ok\n")
if t[:2] == ["am", "broadcast"]:
    e = {t[i + 1]: t[i + 2] for i in range(len(t) - 2) if t[i] in ("--es", "--ei")}
    cmd, seq = e["cmd"], int(e["seq"])
    st["commands"].append([cmd, e.get("arg")])
    data = None
    if cmd in st["silent"]:
        save(); out("Broadcast completed\n")
    if cmd in st["refuse"]:
        results_add({"type": "ack", "seq": seq, "ok": False, "why": "refused by the stand-in"})
        save(); out("Broadcast completed\n")
    if cmd == "status":
        data = {"protocol": st["protocol"], "models": st["models"], "cards": st["cards"], "listening": st.get("listening", True)}
    if cmd == "play":
        on = st["onClip"].get(e["arg"], {})
        results_add({"type": "turn", **on.get("turn", {"heard": "?", "reply": "?", "router": "model", "cards": [], "tools": []})})
        st["cards"] = on.get("cards", st["cards"])
    if cmd == "end":
        st["cards"] = []
    if cmd == "import-models":
        src = [x for x in (phone / "files/spoken/import").rglob("*") if x.is_file()]
        data = {"files": len(src)}
        for x in src:
            results_add({"type": "import", "file": x.name, "ok": x.name not in st.get("importBad", [])})
            x.unlink()
        results_add({"type": "import", "done": True, "listen": True, "gemma": False})
    results_add({"type": "ack", "seq": seq, "ok": True, **({"data": data} if data else {})})
    if st["leaveAfter"] is not None:
        st["leaveAfter"] -= 1
        if st["leaveAfter"] <= 0:
            st["top"], st["focus"] = "com.other.app", "Window{a u0 com.other.app/.Main}"
    save()
    out("Broadcast completed\n")
if t[:3] == ["cmd", "role", "get-role-holders"]:
    out("".join(h + "\n" for h in st["holders"]))
if t[:3] == ["cmd", "role", "add-role-holder"]:
    if st["roleStuck"] and t[4] != pkg:
        out("", 1)
    st["holders"] = [t[4]]
    save(); out("")
if t[:3] == ["cmd", "role", "remove-role-holder"]:
    st["holders"] = [h for h in st["holders"] if h != t[4]]
    save(); out("")
if t[0] == "run-as":
    if t[1] != pkg or not st["debuggable"]:
        out("", 1)
    v = t[2:]
    p = phone / v[-1]
    if v[0] == "mkdir":
        p.mkdir(parents=True, exist_ok=True); out("")
    if v[0] == "cat":
        out(p.read_text(), 0) if p.exists() else out("", 1)
    if v[0] == "rm":
        if p.is_dir():
            for x in p.iterdir():
                x.unlink()
            p.rmdir()
        elif p.exists():
            p.unlink()
        out("")
    if v[0] == "stat":
        short = st.get("shortStat", 0)
        out(f"{p.stat().st_size + short}\n" if p.exists() else "", 0 if p.exists() else 1)
    if v[0] == "ls":
        out("".join(x.name + "\n" for x in p.iterdir())) if p.exists() else out("", 1)
if t[0] == "date":
    out("1700000000\n")
if t[:2] == ["pm", "grant"]:
    st["micGranted"] = t[2] == pkg and t[3] == "android.permission.RECORD_AUDIO"
    save(); out("")
if t[:3] == ["pm", "list", "packages"]:
    out(f"package:{pkg} uid:10999\n")
if t[0] == "logcat":
    out(st["log"])
out("unknown command\n", 1)
'''

FAKE_CODE = compile(FAKE_ADB, "fake-adb", "exec")


def fake_in_process(adb, args, data=None, timeout=60):
    """The same stand-in as a function call: starting Python thousands of times took minutes."""
    saved = sys.argv, sys.stdout, sys.stdin
    out = io.StringIO()
    sys.argv, sys.stdout, sys.stdin = ["adb", "-s", adb.serial, *args], out, io.TextIOWrapper(io.BytesIO(data.read() if hasattr(data, "read") else data or b""))
    try:
        exec(FAKE_CODE, {"__name__": "fake"})
        code = 0
    except SystemExit as e:
        code = e.code or 0
    finally:
        sys.argv, sys.stdout, sys.stdin = saved
    return subprocess.CompletedProcess(args, code, out.getvalue().encode(), b"")


GOOD = {"format": 1, "id": "good", "title": "A test", "safety": "read", "steps": [
    {"do": "open", "how": "button"},
    {"do": "say", "text": "What time is it?", "voice": "heart"},
    {"do": "wait_reply", "seconds": 1},
    {"do": "check", "what": "heard", "contains": "WHAT time"},
    {"do": "check", "what": "router", "equals": "model"},
    {"do": "end"}]}

CARD = {"format": 1, "id": "card", "title": "A card", "safety": "stops-at-card", "steps": [
    {"do": "open", "how": "button"},
    {"do": "say", "text": "Send a text to Sam.", "voice": "heart"},
    {"do": "wait_card", "kind": "send_text", "seconds": 1},
    {"do": "say", "text": "No.", "voice": "heart"},
    {"do": "wait_reply", "seconds": 1},
    {"do": "end"}]}


def changed(base: dict, **top) -> dict:
    s = copy.deepcopy(base)
    s.update(top)
    return s


def steps_of(base: dict, index: int, step) -> dict:
    s = copy.deepcopy(base)
    s["steps"][index] = step
    return s


def problems(script: dict) -> list[str]:
    return scripts.check_script(script, script.get("id", "x"), CFG)


class ScriptChecker(unittest.TestCase):
    def assert_refused(self, script: dict, word: str) -> None:
        found = problems(script)
        self.assertTrue(any(word in p for p in found), f"{word!r} not in {found}")

    def test_the_shipped_scripts_pass(self):
        paths = sorted(scripts.TESTS.glob("*.json"))
        self.assertTrue(paths)
        for p in paths:
            self.assertEqual([], scripts.load(p)[1], p.name)

    def test_every_shipped_script_is_mapped_to_the_test_bench(self):
        self.assertEqual([], scripts.check_coverage(sorted(scripts.TESTS.glob("*.json"))))

    def test_what_runs_another_app_is_refused_in_every_class(self):
        for text, voice in (("Set a timer for 5 minutes", "heart"), ("Open the Clock app", "heart"), ("Call 911", "heart"),
                            ("Play Radiohead", "heart"), ("Google the weather", "heart"), ("Mets un minuteur de 5 minutes", "siwis"),
                            ("Joue du jazz", "siwis"), ("Cherche sur Startpage", "siwis")):
            for safety in CFG["safetyClasses"]:
                s = {"format": 1, "id": "x", "title": "t", "safety": safety, "steps": [
                    {"do": "open", "how": "button"}, {"do": "say", "text": text, "voice": voice}, {"do": "end"}]}
                self.assertTrue(any("another app" in p for p in problems(s)), (text, safety))

    def test_a_misfire_that_merely_mentions_play_passes(self):
        s = {"format": 1, "id": "x", "title": "t", "safety": "read", "steps": [
            {"do": "open", "how": "button"}, {"do": "say", "text": "Role play a job interview", "voice": "heart"}, {"do": "end"}]}
        self.assertEqual([], problems(s))

    def test_good_scripts_pass(self):
        self.assertEqual([], problems(GOOD))
        self.assertEqual([], problems(CARD))

    def test_a_yes_at_a_risky_card_is_refused(self):
        self.assert_refused(steps_of(CARD, 3, {"do": "say", "text": "Yes.", "voice": "heart"}), "only a plain no")
        self.assert_refused(steps_of(CARD, 3, {"do": "say", "text": "No, send it.", "voice": "heart"}), "only a plain no")
        self.assert_refused(steps_of(CARD, 3, {"do": "say", "text": "Oui.", "voice": "siwis"}), "only a plain no")

    def test_a_risky_request_must_be_classed(self):
        self.assert_refused(changed(steps_of(GOOD, 1, {"do": "say", "text": "Call Sam.", "voice": "heart"})), "stops-at-card")
        self.assert_refused(changed(steps_of(GOOD, 1, {"do": "say", "text": "Appelle Sam.", "voice": "siwis"})), "stops-at-card")
        self.assert_refused(changed(steps_of(GOOD, 1, {"do": "say", "text": "Supprime la note.", "voice": "siwis"})), "stops-at-card")

    def test_stops_at_card_needs_its_card_and_end(self):
        self.assert_refused(changed(GOOD, safety="stops-at-card"), "wait_card of a risky kind")
        s = copy.deepcopy(CARD)
        s["steps"].pop()
        self.assert_refused(s, "must be end")

    def test_a_yes_needs_robot_data_and_a_harmless_card(self):
        self.assert_refused(steps_of(GOOD, 1, {"do": "say", "text": "Yes.", "voice": "heart"}), "only for safety robot-data")
        s = changed(steps_of(GOOD, 1, {"do": "say", "text": "Yes.", "voice": "heart"}), safety="robot-data")
        self.assert_refused(s, "no harmless card")
        ok = {"format": 1, "id": "ok", "title": "t", "safety": "robot-data", "steps": [
            {"do": "open", "how": "button"}, {"do": "say", "text": "Note that the sky is blue.", "voice": "heart"},
            {"do": "wait_card", "kind": "add_note", "seconds": 5}, {"do": "say", "text": "Yes.", "voice": "heart"}, {"do": "end"}]}
        self.assertEqual([], problems(ok))

    def test_the_format_is_strict(self):
        self.assert_refused(changed(GOOD, extra=1), "unknown key extra")
        self.assert_refused(changed(GOOD, format=2), "format must be")
        self.assert_refused(changed(GOOD, setup=[{"set": "voiceYesPersonal", "value": True}]), "short list")
        self.assert_refused(changed(GOOD, setup=[{"set": "voiceCards", "value": "on"}]), "can't be")
        self.assert_refused(changed(GOOD, needs={"phone": True}), "needs may only")
        self.assert_refused(steps_of(GOOD, 1, {"do": "say", "text": "Hi", "voice": "someone"}), "voice must be")
        self.assert_refused(steps_of(GOOD, 1, {"do": "say", "text": "Hi", "voice": "heart", "speed": 3}), "speed must be")
        self.assert_refused(steps_of(GOOD, 1, {"do": "say", "text": "Hi", "voice": "heart", "noise": "pink", "snr": 3}), "snr must be")
        self.assert_refused(steps_of(GOOD, 4, {"do": "check", "what": "router", "equals": "NoRouter"}), "unknown router")
        # These two never take a turn themselves: their turns report as model, so a script naming them could never pass.
        self.assert_refused(steps_of(GOOD, 4, {"do": "check", "what": "router", "equals": "ToolRouter"}), "never takes a turn")
        self.assert_refused(steps_of(GOOD, 4, {"do": "check", "what": "router", "equals": "PreRouter"}), "never takes a turn")
        # Music by voice is off in the test build: its router can be checked as absent, never as the one that answered.
        self.assert_refused(steps_of(GOOD, 4, {"do": "check", "what": "router", "equals": "MusicRouter"}), "off in the test build")
        self.assertEqual([], scripts.check_script(steps_of(GOOD, 4, {"do": "check", "what": "router", "equals": "MusicRouter", "absent": True}), GOOD["id"], CFG))
        self.assert_refused(steps_of(GOOD, 4, {"do": "check", "what": "log", "contains": "x"}), "needs a regex")
        self.assert_refused(steps_of(GOOD, 4, {"do": "check", "what": "reply", "regex": "("}), "bad regex")
        self.assert_refused(steps_of(GOOD, 2, {"do": "wait_reply", "seconds": 999}), "seconds must be")
        self.assert_refused(steps_of(GOOD, 0, {"do": "tap", "how": "x"}), "unknown step")
        self.assert_refused(steps_of(GOOD, 0, {"do": "pause", "seconds": 1}), "no open before")
        self.assertTrue(scripts.check_script(GOOD, "other-name", CFG))

    def test_an_unreadable_script_is_a_problem(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "bad.json"
            p.write_text("{")
            self.assertEqual(1, scripts.main([str(p)]))


class RefusedWithNoCard(unittest.TestCase):
    REFUSED = {"format": 1, "id": "good", "title": "A refusal", "safety": "stops-at-card", "refusedWithNoCard": True, "steps": [
        {"do": "open", "how": "button"},
        {"do": "say", "text": "Call Sam.", "voice": "heart"},
        {"do": "wait_reply", "seconds": 1},
        {"do": "check", "what": "card", "equals": "call", "absent": True},
        {"do": "end"}]}

    def test_a_refusal_with_its_check_passes(self):
        self.assertEqual([], problems(self.REFUSED))

    def test_it_must_check_that_no_card_came(self):
        doc = changed(self.REFUSED, steps=[s for s in self.REFUSED["steps"] if s["do"] != "check"])
        self.assertTrue(any("risky kind" in p for p in problems(doc)))

    def test_without_the_key_the_old_rule_holds(self):
        doc = {k: v for k, v in self.REFUSED.items() if k != "refusedWithNoCard"}
        self.assertTrue(any("risky kind" in p for p in problems(doc)))


class ClipNames(unittest.TestCase):
    def test_only_a_plain_no_gets_the_no_prefix(self):
        def name(text, voice="heart"):
            return clips.name_of(clips.spec_of({"text": text, "voice": voice}), CFG)
        self.assertTrue(name("No.").startswith("no-"))
        self.assertTrue(name("Non merci !", "siwis").startswith("no-"))
        for text in ("Yes.", "No, send it.", "No problem, call them.", "Not now"):
            self.assertTrue(name(text).startswith("say-"), text)
        self.assertRegex(name("No."), r"^no-[0-9a-f]{16}\.s16$")

    def test_a_name_follows_every_part_of_the_clip(self):
        base = {"text": "Hello", "voice": "heart", "speed": 1.0, "noise": "pink", "snr": 10}
        names = {clips.name_of(base, CFG)} | {clips.name_of({**base, k: v}, CFG) for k, v in
                                              (("text", "Bye"), ("voice", "kitten"), ("speed", 1.2), ("noise", "fan"), ("snr", 5))}
        self.assertEqual(6, len(names))

    def test_word_accuracy(self):
        self.assertEqual(1.0, clips.word_accuracy("Set a timer for five minutes.", "set a timer for 5 minutes"))
        self.assertEqual(1.0, clips.word_accuracy("Quelle heure est-il ?", "quelle heure est il", "fr"))
        self.assertEqual(0.5, clips.word_accuracy("one two", "one three"))
        self.assertEqual(0.0, clips.word_accuracy("one two", "three four five six"))


class Phone(unittest.TestCase):
    """A temporary folder with the stand-in adb, the phone's files and its state."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.adb = self.root / "adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(self.adb.stat().st_mode | stat.S_IEXEC)
        os.environ["FAKE_ADB_DIR"] = str(self.root)
        (self.root / "phone").mkdir()
        (self.root / "calls.log").write_text("")
        self.clipdir = self.root / "clips"
        self.clipdir.mkdir()
        self.state = {"pkg": PKG, "top": PKG, "focus": f"Window{{a u0 {PKG}/.Main}}", "keyguard": False, "rotation": 0, "call": False,
                      "screen": "ON", "installed": True, "debuggable": True, "granted": [], "listeners": "null", "commands": [],
                      "silent": [], "refuse": [], "protocol": CFG["protocol"], "models": {"listen": True, "gemma": False}, "cards": [],
                      "onClip": {}, "leaveAfter": None, "holders": [ROBOT], "roleStuck": False, "log": ""}
        self.save()

    def tearDown(self):
        os.environ.pop("FAKE_ADB_DIR", None)
        self.tmp.cleanup()

    def save(self):
        t = self.root / "state.json.test"  # whole file or nothing: a fake adb may read it while this runs
        t.write_text(json.dumps(self.state))
        os.replace(t, self.root / "state.json")

    def now(self) -> dict:
        return json.loads((self.root / "state.json").read_text())

    def calls(self) -> list[str]:
        return (self.root / "calls.log").read_text().splitlines()


class Guard(Phone):
    def guard(self, *args, pkg=PKG) -> int:
        self.save()
        env = {**os.environ, "ROBOT_SPOKEN_ADB": str(self.adb), "ANDROID_SERIAL": SERIAL}
        return subprocess.run(["bash", str(HERE / "guard.sh"), pkg, *args], env=env, capture_output=True).returncode

    def test_the_test_build_on_screen_passes(self):
        self.assertEqual(0, self.guard())

    def test_the_panel_and_a_popup_pass(self):
        self.state["focus"] = "Window{a u0 VoiceInteractionSession}"
        self.assertEqual(0, self.guard())
        self.state["focus"] = "Window{a u0 Pop-Up Window}"
        self.assertEqual(0, self.guard())

    def test_your_own_robot_on_screen_is_refused(self):
        self.state.update(top=ROBOT, focus=f"Window{{a u0 {ROBOT}/.Main}}")
        self.assertEqual(1, self.guard())
        self.state.update(top=PKG)  # its focus still your Robot's
        self.assertEqual(1, self.guard())

    def test_the_test_build_doesnt_pass_for_your_robot(self):
        self.assertEqual(1, self.guard(pkg=ROBOT))

    def test_anything_else_is_refused(self):
        for change in ({"top": "com.other.app"}, {"focus": "Window{a u0 com.other.app/.Main}"}, {"focus": "Window{a u0 NotificationShade}"},
                       {"keyguard": True}, {"call": True}, {"rotation": 90}, {"screen": "OFF"}, {"dead": True}):
            saved = copy.deepcopy(self.state)
            self.state.update(change)
            self.assertEqual(1, self.guard(), change)
            self.state = saved

    def test_before_start_skips_only_which_app(self):
        self.state.update(top="com.other.app", focus="Window{a u0 com.other.app/.Main}")
        self.assertEqual(0, self.guard("--before-start"))
        for change in ({"call": True}, {"keyguard": True}, {"screen": "OFF"}, {"dead": True}):
            saved = copy.deepcopy(self.state)
            self.state.update(change)
            self.assertEqual(1, self.guard("--before-start"), change)
            self.state = saved


class Runner(Phone):
    def put_clip(self, step: dict, dropped=False) -> str:
        spec = clips.spec_of(step)
        name = clips.name_of(spec, CFG)
        (self.clipdir / (name + ".json")).write_text(json.dumps(
            {**spec, "name": name, "dropped": dropped, "heardClean": spec["text"], "cleanAccuracy": 0.2 if dropped else 1.0}))
        if not dropped:
            (self.clipdir / name).write_bytes(b"\0\1" * 100)
        return name

    def fast_guard(self, _run, *extra: str) -> None:
        """The checks that matter here, without guard.sh's eight adb calls per input: Guard tests guard.sh itself, and the
        runs with real=True use it end to end. These tests would take minutes otherwise."""
        st = self.now()
        with (self.root / "calls.log").open("a") as f:
            f.write(f"{SERIAL}|shell dumpsys display\n")
        if st["call"] or (not extra and st["top"] != PKG) or st.get("dead"):
            raise runner.Refused("GUARD: the stand-in refused")

    def go(self, *docs: dict, serial=SERIAL, allow=SERIAL, extra=(), make=True, real=False) -> int:
        self.save()
        real_guard = runner.Run.guard
        self.addCleanup(setattr, runner.Run, "guard", real_guard)
        if not real:
            test = self
            real_run = runner.Adb._run
            self.addCleanup(setattr, runner.Adb, "_run", real_run)
            runner.Run.guard = lambda run, *a: test.fast_guard(run, *a)
            runner.Adb._run = fake_in_process
        paths = []
        for doc in docs:
            p = self.root / f"{doc['id']}.json"
            p.write_text(json.dumps(doc))
            paths.append(str(p))
            if make:
                for s in scripts.clip_specs(doc):
                    self.put_clip(s)
        os.environ["ROBOT_SPOKEN_SERIALS"] = allow
        self.addCleanup(os.environ.pop, "ROBOT_SPOKEN_SERIALS", None)
        self.out: list[str] = []
        args = runner.parse(["--serial", serial, "--adb", str(self.adb), "--clips", str(self.clipdir), "--report-dir", str(self.root / "report"),
                             "--poll", "0.01", "--ack-seconds", "0.3", "--open-settle", "0", *extra, *paths])
        return runner.execute(args, self.out.append)

    def turn(self, step: dict, **turn) -> None:
        self.state["onClip"][clips.name_of(clips.spec_of(step), CFG)] = {"turn": {"cards": [], "tools": [], "router": "model", **turn}}

    def good_turn(self):
        self.turn(GOOD["steps"][1], heard="What time is it", reply="It's noon.", router="model")

    def clips_on_phone(self) -> list[str]:
        d = self.root / "phone" / CFG["clipsDir"]
        return [p.name for p in d.iterdir()] if d.exists() else []

    def report_text(self) -> str:
        return "\n".join(p.read_text() for p in sorted((self.root / "report").iterdir()))

    def assert_phone_clean(self):
        self.assertEqual([ROBOT], self.now()["holders"], "the role must be back")
        self.assertEqual([], self.clips_on_phone(), "the clips must be gone")

    def test_a_phone_off_the_list_gets_nothing(self):
        self.assertEqual(2, self.go(GOOD, allow="OTHER"))
        self.assertEqual([], self.calls())
        self.assertEqual(2, self.go(GOOD, allow=""))
        self.assertEqual([], self.calls())

    def test_a_dry_run_touches_no_phone(self):
        self.assertEqual(0, self.go(GOOD, extra=["--dry"]))
        self.assertEqual([], self.calls())

    def test_a_script_with_problems_runs_nothing(self):
        self.assertEqual(2, self.go(changed(GOOD, safety="nope"), make=False))
        self.assertEqual([], self.calls())

    def test_a_good_run(self):
        self.good_turn()
        self.assertEqual(0, self.go(GOOD, real=True), self.out)
        self.assert_phone_clean()
        cmds = [c for c, _ in self.now()["commands"]]
        self.assertEqual(["status", "reset", "open", "status", "open", "play", "end"], cmds)
        self.assertIn("1 passed, 0 failed, 0 blocked", self.report_text())
        self.assertEqual(sorted(p.suffix for p in (self.root / "report").iterdir()), [".json", ".md"])

    def test_every_call_names_the_phone(self):
        self.good_turn()
        self.go(GOOD)
        self.assertTrue(self.calls())
        self.assertEqual({SERIAL}, {c.split("|")[0] for c in self.calls()})

    def test_every_input_comes_right_after_the_guard(self):
        self.good_turn()
        self.go(GOOD, real=True)
        last_was_guard = False
        inputs = 0
        for c in (c.split("|")[1] for c in self.calls()):
            if "am broadcast" in c or "am start" in c:
                inputs += 1
                self.assertTrue(last_was_guard, f"input with no guard before it: {c}")
                last_was_guard = False
            elif "dumpsys display" in c:
                last_was_guard = True
        self.assertGreater(inputs, 5)

    def test_it_only_ever_targets_the_test_build(self):
        self.good_turn()
        self.go(GOOD)
        for c in self.calls():
            if "am broadcast" in c or "am start" in c or "run-as" in c:
                self.assertIn(PKG, c)
        roles = [c for c in self.calls() if "add-role-holder" in c]
        self.assertTrue(roles[0].endswith(PKG) and roles[-1].endswith(ROBOT), roles)

    def test_a_wrong_answer_fails_and_tidies_up(self):
        self.turn(GOOD["steps"][1], heard="What time is it", reply="It's noon.", router="MusicRouter")
        self.assertEqual(1, self.go(GOOD), self.out)
        self.assert_phone_clean()
        self.assertIn("MusicRouter", self.report_text())
        self.assertEqual("end", self.now()["commands"][-1][0])

    def test_a_clip_the_recognizer_cant_read_blocks_the_test(self):
        self.put_clip(GOOD["steps"][1], dropped=True)
        self.assertEqual(2, self.go(GOOD, make=False), self.out)
        self.assert_phone_clean()
        self.assertNotIn("play", [c for c, _ in self.now()["commands"]])
        self.assertIn("can't read", self.report_text())

    def test_clips_it_cant_make_block_the_run_before_the_phone(self):
        self.assertEqual(2, self.go(GOOD, make=False), self.out)
        self.assertEqual([], self.calls())
        self.assertIn("aren't made yet", self.report_text())

    def test_a_test_that_needs_the_model_waits_for_it(self):
        self.good_turn()
        self.assertEqual(2, self.go(changed(GOOD, needs={"model": True})), self.out)
        self.assertIn("needs the model", self.report_text())
        self.assert_phone_clean()

    def test_leaving_the_app_stops_the_run_and_gives_everything_back(self):
        self.good_turn()
        self.state["leaveAfter"] = 3  # status, reset, open: then another app comes to the front
        self.assertEqual(2, self.go(GOOD, real=True), self.out)
        self.assertNotIn("play", [c for c, _ in self.now()["commands"]])
        self.assertEqual([ROBOT], self.now()["holders"])
        self.assertEqual([], self.clips_on_phone())
        self.assertIn("The run stopped", self.report_text())

    def test_a_refusal_stops_every_later_test(self):
        self.good_turn()
        self.state["leaveAfter"] = 3
        self.assertEqual(2, self.go(GOOD, changed(GOOD, id="good2")), self.out)
        self.assertEqual(1, sum('"id": "good' in line for line in self.report_text().splitlines()))

    def test_no_answer_from_the_build_is_blocked_not_passed(self):
        self.good_turn()
        self.state["silent"] = ["play"]
        self.assertEqual(2, self.go(GOOD), self.out)
        self.assert_phone_clean()

    def test_a_refused_command_is_blocked(self):
        self.good_turn()
        self.state["refuse"] = ["play"]
        self.assertEqual(2, self.go(GOOD), self.out)
        self.assertIn("refused by the stand-in", self.report_text())

    def test_old_answers_dont_count(self):
        old = self.root / "phone" / CFG["results"]
        old.parent.mkdir(parents=True)
        old.write_text("".join(json.dumps({"type": "ack", "seq": n, "ok": True, "data": {"protocol": 1, "cards": []}}) + "\n" for n in range(9)))
        self.state["silent"] = ["status"]
        self.assertEqual(2, self.go(GOOD), self.out)
        self.assertEqual(0, len([c for c, _ in self.now()["commands"] if c == "play"]))

    def test_at_a_risky_card_only_a_no_plays(self):
        self.turn(CARD["steps"][1], heard="Send a text to Sam", reply="Send it?", cards=[{"kind": "send_text"}])
        self.state["onClip"][clips.name_of(clips.spec_of(CARD["steps"][1]), CFG)]["cards"] = ["send_text"]
        self.turn(CARD["steps"][3], heard="No", reply="Cancelled.")
        self.assertEqual(0, self.go(CARD), self.out)
        played = [a for c, a in self.now()["commands"] if c == "play"]
        self.assertEqual(2, len(played))
        self.assertTrue(played[1].startswith("no-"))

    def test_a_card_nobody_expected_stops_the_next_clip(self):
        script = {"format": 1, "id": "two", "title": "t", "safety": "read", "steps": [
            {"do": "open", "how": "button"}, {"do": "say", "text": "First thing", "voice": "heart"},
            {"do": "say", "text": "Second thing", "voice": "heart"}, {"do": "end"}]}
        self.state["onClip"][clips.name_of(clips.spec_of(script["steps"][1]), CFG)] = {"cards": ["send_text"]}
        self.assertEqual(1, self.go(script), self.out)
        self.assertEqual(1, len([c for c, _ in self.now()["commands"] if c == "play"]))
        self.assertIn("a risky card is showing", self.report_text())
        self.assertEqual("end", self.now()["commands"][-1][0])

    def test_a_card_of_an_unknown_kind_counts_as_risky(self):
        script = {"format": 1, "id": "two", "title": "t", "safety": "read", "steps": [
            {"do": "open", "how": "button"}, {"do": "say", "text": "First thing", "voice": "heart"},
            {"do": "say", "text": "Second thing", "voice": "heart"}, {"do": "end"}]}
        self.state["onClip"][clips.name_of(clips.spec_of(script["steps"][1]), CFG)] = {"cards": ["a_new_tool"]}
        self.assertEqual(1, self.go(script), self.out)
        self.assertEqual(1, len([c for c, _ in self.now()["commands"] if c == "play"]))

    def test_a_build_that_wont_say_its_cards_plays_nothing(self):
        self.good_turn()
        self.state["refuse"] = []
        self.state["cards"] = None
        self.assertEqual(2, self.go(GOOD), self.out)
        self.assertNotIn("play", [c for c, _ in self.now()["commands"]])

    def test_what_the_build_must_never_hold_stops_the_run(self):
        for perm in CFG["neverGranted"]:
            self.state["granted"] = [perm]
            self.assertEqual(2, self.go(GOOD), perm)
            self.assertEqual([], [c for c, _ in self.now()["commands"]])
            self.assertEqual([ROBOT], self.now()["holders"])
        self.state["granted"] = []
        self.state["listeners"] = f"{PKG}/app.Listener:other/x"
        self.assertEqual(2, self.go(GOOD))
        self.assertEqual([ROBOT], self.now()["holders"])

    def test_the_release_build_or_none_is_refused(self):
        self.state["debuggable"] = False
        self.assertEqual(2, self.go(GOOD))
        self.assertEqual([ROBOT], self.now()["holders"])
        self.state.update(debuggable=True, installed=False)
        self.assertEqual(2, self.go(GOOD))
        self.assertEqual([ROBOT], self.now()["holders"])

    def test_a_build_on_another_protocol_is_refused(self):
        self.state["protocol"] = CFG["protocol"] + 1
        self.assertEqual(2, self.go(GOOD))
        self.assertEqual([ROBOT], self.now()["holders"])

    def test_a_call_before_the_start_changes_nothing(self):
        self.state["call"] = True
        self.assertEqual(2, self.go(GOOD, real=True))
        self.assertFalse([c for c in self.calls() if "add-role-holder" in c or "am " in c])

    def test_the_runner_gives_the_build_the_microphone_only(self):
        self.good_turn()
        self.assertEqual(0, self.go(GOOD), self.out)
        self.assertTrue(self.now()["micGranted"])
        self.assertEqual(1, len([c for c in self.calls() if "pm grant" in c]))

    def _recordings(self, *names):
        d = self.root / "phone" / CFG["recordingsDir"]
        d.mkdir(parents=True)
        for n in names:
            (d / n).write_text("sound of " + n)
        return d

    def test_pull_voice_moves_recordings_off_the_phone(self):
        d = self._recordings("en-001.s16", "fr-002.s16", "other.txt")
        to = self.root / "voice"
        self.assertEqual(0, self.go(extra=["--pull-voice", "--voice-dir", str(to)]), self.out)
        self.assertEqual("sound of en-001.s16", (to / "en-001.s16").read_text())
        self.assertEqual(["fr-002.s16"], [p.name for p in to.glob("fr-*")])
        self.assertEqual(["other.txt"], [p.name for p in d.iterdir()], "only the recorder's own names, and only those deleted")
        self.assertFalse([c for c in self.calls() if "am " in c or "role" in c])

    def test_pull_voice_keeps_what_didnt_come_across_whole(self):
        d = self._recordings("en-001.s16")
        self.state["shortStat"] = 5
        to = self.root / "voice"
        self.assertEqual(2, self.go(extra=["--pull-voice", "--voice-dir", str(to)]))
        self.assertTrue((d / "en-001.s16").exists())
        self.assertFalse((to / "en-001.s16").exists())

    def test_silent_turns_speech_off_after_the_scripts_setup(self):
        doc = changed(GOOD, setup=[{"set": "speakReplies", "value": "ALWAYS"}])
        self.turn(GOOD["steps"][1], heard="What time is it", reply="It's noon.", router="model")
        self.assertEqual(0, self.go(doc, extra=["--silent"]), self.out)
        sets = [c[1] for c in self.now()["commands"] if c[0] == "set"]
        self.assertEqual(["speakReplies", "speakReplies"], sets)
        calls = "\n".join(self.calls())
        self.assertLess(calls.rindex("--es arg speakReplies --es value ALWAYS"), calls.rindex("--es arg speakReplies --es value NEVER"))

    def models_folder(self) -> Path:
        m = self.root / "models"
        (m / "stt-parakeet-v3").mkdir(parents=True)
        (m / "stt-parakeet-v3" / "tokens.txt").write_bytes(b"abc")
        (m / "gemma-4-E2B-it.litertlm").write_bytes(b"defg")
        return m

    def test_import_models_pushes_both_shapes_and_waits_for_the_build(self):
        self.assertEqual(0, self.go(extra=["--import-models", "--models", str(self.models_folder())]), self.out)
        self.assertIn(["import-models", None], self.now()["commands"])
        calls = "\n".join(self.calls())
        self.assertIn("stt-parakeet-v3/tokens.txt", calls)
        self.assertIn("files/spoken/import/gemma-4-E2B-it.litertlm", calls)

    def test_import_models_reports_a_file_the_build_refused(self):
        self.state["importBad"] = ["tokens.txt"]
        self.save()
        self.assertEqual(2, self.go(extra=["--import-models", "--models", str(self.models_folder())]))

    def test_import_models_stops_when_a_file_arrives_short(self):
        self.state["shortStat"] = 1
        self.save()
        self.assertEqual(2, self.go(extra=["--import-models", "--models", str(self.models_folder())]))
        self.assertNotIn(["import-models", None], self.now()["commands"])

    def test_import_models_refuses_a_phone_not_named(self):
        self.assertEqual(2, self.go(extra=["--import-models", "--models", str(self.models_folder())], serial="OTHERPHONE"))
        self.assertEqual([], self.calls())

    def test_pull_voice_refuses_a_phone_not_named(self):
        d = self._recordings("en-001.s16")
        self.assertEqual(2, self.go(allow="OTHER", extra=["--pull-voice", "--voice-dir", str(self.root / "voice")]))
        self.assertTrue((d / "en-001.s16").exists())
        self.assertEqual([], self.calls())

    def test_a_role_that_wont_come_back_is_loud_and_can_be_retried(self):
        self.good_turn()
        self.state["roleStuck"] = True
        self.assertEqual(3, self.go(GOOD), self.out)
        self.assertIn("--restore-role", self.report_text())
        journal = self.clipdir / f"role-journal-{SERIAL}.json"
        self.assertTrue(journal.exists())
        st = self.now()
        st["roleStuck"] = False
        self.state = st
        self.assertEqual(0, self.go(extra=["--restore-role"]), self.out)
        self.assertEqual([ROBOT], self.now()["holders"])
        self.assertFalse(journal.exists())

    def test_the_next_run_restores_what_a_crash_left(self):
        self.good_turn()
        (self.clipdir / f"role-journal-{SERIAL}.json").write_text(json.dumps({"serial": SERIAL, "holders": [ROBOT]}))
        self.state["holders"] = [PKG]
        self.assertEqual(0, self.go(GOOD), self.out)
        self.assertEqual([ROBOT], self.now()["holders"])

    def test_holding_the_role_with_no_record_is_refused(self):
        self.state["holders"] = [PKG]
        self.assertEqual(2, self.go(GOOD), self.out)
        self.assertEqual([PKG], self.now()["holders"])
        self.assertIn("by hand", self.report_text())

    def test_no_holder_before_means_no_holder_after(self):
        self.good_turn()
        self.state["holders"] = []
        self.assertEqual(0, self.go(GOOD), self.out)
        self.assertEqual([], self.now()["holders"])

    def test_being_stopped_gives_the_role_back(self):
        self.good_turn()
        script = changed(GOOD, id="slow", steps=[GOOD["steps"][0], {"do": "pause", "seconds": 30}, GOOD["steps"][-1]])
        self.save()
        path = self.root / "slow.json"
        path.write_text(json.dumps(script))
        env = {**os.environ, "ROBOT_SPOKEN_SERIALS": SERIAL}
        p = subprocess.Popen([sys.executable, "-I", str(HERE / "run.py"), "--serial", SERIAL, "--adb", str(self.adb), "--clips", str(self.clipdir),
                              "--report-dir", str(self.root / "report"), "--poll", "0.01", str(path)], env=env, stdout=subprocess.DEVNULL)
        try:
            end = time.monotonic() + 30
            while time.monotonic() < end and ["open", "button"] not in self.now()["commands"]:
                time.sleep(0.05)
            self.assertIn(["open", "button"], self.now()["commands"])
            p.send_signal(signal.SIGTERM)
            p.wait(30)
        finally:
            p.kill()
        self.assertEqual([ROBOT], self.now()["holders"])
        self.assertEqual([], self.clips_on_phone())

    def test_a_log_check_reports_a_count_never_the_lines(self):
        self.good_turn()
        self.state["log"] = "SECRET-LINE focus lost\n"
        s = steps_of(GOOD, 4, {"do": "check", "what": "log", "regex": "focus gained"})
        self.assertEqual(1, self.go(s), self.out)
        text = self.report_text()
        self.assertIn("0 lines", text)
        self.assertNotIn("SECRET-LINE", text)
        calls = [c for c in self.calls() if "logcat" in c]
        self.assertTrue(calls and "--uid=10999" in calls[0])


@unittest.skipUnless(__import__("importlib.util").util.find_spec("numpy"), "needs numpy")
class Noise(unittest.TestCase):
    def setUp(self):
        import numpy
        import noise
        self.np, self.noise = numpy, noise

    def test_same_seed_same_noise(self):
        np, noise = self.np, self.noise
        for make in (noise.pink, noise.fan):
            a, b = make(16000, np.random.default_rng(1)), make(16000, np.random.default_rng(1))
            self.assertTrue(np.array_equal(a, b))
            self.assertFalse(np.array_equal(a, make(16000, np.random.default_rng(2))))
            self.assertEqual(16000, len(a))
            self.assertLessEqual(float(np.max(np.abs(a))), 1.0)

    def test_pink_has_more_low_than_high(self):
        np, noise = self.np, self.noise
        s = np.abs(np.fft.rfft(noise.pink(32000, np.random.default_rng(3)))) ** 2
        self.assertGreater(s[10:400].mean(), s[8000:12000].mean() * 10)

    def test_the_fan_is_low(self):
        np, noise = self.np, self.noise
        s = np.abs(np.fft.rfft(noise.fan(32000, np.random.default_rng(3)))) ** 2
        f = np.fft.rfftfreq(32000, 1 / 16000)
        self.assertGreater(s[f < 1500].sum(), s[f >= 1500].sum() * 50)

    def test_mix_hits_its_level_over_the_speech_only(self):
        np, noise = self.np, self.noise
        speech = np.concatenate([np.zeros(8000), 0.3 * np.sin(np.arange(16000) / 5), np.zeros(8000)]).astype(np.float32)
        n = noise.pink(len(speech), np.random.default_rng(4))
        for snr in (20, 10, 5):
            out = noise.mix(speech, n, snr)
            got = 20 * np.log10(noise.rms(speech[8000:24000]) / noise.rms((out - speech)[8000:24000]))
            self.assertAlmostEqual(snr, float(got), delta=1.0)

    def test_a_loud_mix_doesnt_clip(self):
        np, noise = self.np, self.noise
        speech = (0.99 * np.sin(np.arange(16000) / 5)).astype(np.float32)
        out = noise.mix(speech, noise.pink(16000, np.random.default_rng(5)), 5)
        self.assertLessEqual(float(np.max(np.abs(out))), 0.96)

    def test_babble_overlaps_the_voices_it_was_given(self):
        np, noise = self.np, self.noise
        v = [(np.sin(np.arange(3000) / d)).astype(np.float32) for d in (3, 7, 11)]
        a = noise.babble(16000, np.random.default_rng(6), v)
        self.assertEqual(16000, len(a))
        self.assertLessEqual(float(np.max(np.abs(a))), 1.0)
        with self.assertRaises(ValueError):
            noise.babble(100, np.random.default_rng(6), [])

    def test_no_speech_cant_be_mixed(self):
        np, noise = self.np, self.noise
        with self.assertRaises(ValueError):
            noise.mix(np.zeros(100, np.float32), np.ones(100, np.float32), 10)


if __name__ == "__main__":
    unittest.main(verbosity=0)
