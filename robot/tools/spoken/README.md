# Spoken tests

Tests that speak to Robot and check what it did, with nobody holding the phone. They run on a separate test build,
`ai.robot_app.spoken`, which installs beside your Robot and never ships: `tools/verify_apk.sh` and `SpokenToolsTest` fail if
any of it reaches a release. The clips are synthetic voices made on your computer. Nothing here records you.

| File | What it does |
|---|---|
| `spoken.json` | Everything the tools and the test build must agree on: the command protocol, voices, noises, card and safety lists |
| `scripts.py` | The script format's checker: `python3 -I tools/spoken/scripts.py` |
| `clips.py`, `noise.py` | Makes the clips and checks them with the phone's recognizer on the computer |
| `run.py` | Runs the tests on a phone and writes a report (never audio) |
| `guard.sh` | Run before every input: the test build on screen, no shade or lock screen over it, no call active |
| `tests/` | One JSON file per test |
| `coverage.json` | Which Test Bench rows the scripts cover, all or part, and the rows no script can do, with why |
| `selftest.py` | The tools' own tests, on a stand-in for adb: `python3 -I tools/spoken/selftest.py` |

## Running them

    python3 -m venv ~/.cache/robot-spoken/venv
    ~/.cache/robot-spoken/venv/bin/pip install sherpa-onnx==1.13.8 numpy
    tools/fetch_voice_models.sh <models> stt-parakeet-v3 tts-kokoro-v1.0 tts-kitten-nano-0.8
    ROBOT_SPOKEN_SERIALS=<serial> ~/.cache/robot-spoken/venv/bin/python -I tools/spoken/run.py --serial <serial> --models <models>

`--silent` keeps replies unspoken whatever a script sets, for hours the phone must stay quiet; a script that needs speech (saying "stop" over a reply) can't pass that way. A phone not named in `ROBOT_SPOKEN_SERIALS` is refused before any command reaches it, so it can't run on your own phone by
accident. `--dry` checks the scripts and counts the clips, with no phone. Exit codes: 0 all passed, 1 a test failed, 2 a test
couldn't run or the guard refused, 3 the assistant role wasn't given back (`run.py --serial <serial> --restore-role`).

## A script

    {
      "format": 1,
      "id": "send-stops-at-card",            the file's name
      "title": "What it shows",
      "safety": "stops-at-card",
      "testBench": ["K12"],                  Test Bench rows it covers
      "needs": {"model": true},              blocked, not failed, if the test build has no model
      "setup": [{"set": "voiceCards", "value": true}],     only the short list in spoken.json
      "refusedWithNoCard": true,             optional: a stops-at-card script where Robot should refuse, with a check that no card came
      "steps": [ ... ]                       the last one is always end
    }

| Step | Keys | Meaning |
|---|---|---|
| `open` | `how`: `wake` or `button` | Opens the panel as "hey robot" or the button would |
| `say` | `text`, `voice`; `speed`, `noise`, `snr` | Plays that clip into the listener. Voices `heart`, `kitten`, `siwis` (French); speeds 0.85, 1.0, 1.2; noises `pink`, `fan`, `babble` at 20, 10 or 5 dB |
| `wait_reply` | `seconds`; `contains` or `regex` | A turn after the last clip with a reply, matching if given |
| `wait_card` | `seconds`, `kind` | A turn after the last clip with that card (a tool's id, or `other`) |
| `check` | `what`: `heard`, `reply`, `router`, `tool`, `card`, `log`, `showing`; `contains`, `regex` or `equals`; `absent` | About the last turn. `log` takes a regex over the test build's own log, and reports only a count. `showing` takes `equals`, a card waiting now, and needs no new turn |
| `pause` | `seconds` | Up to 60 |
| `wait_listening` | `seconds` | Up to 60. Until the panel shows Robot listening, as a person waits before they answer. Robot listens by itself only for a card and a question it asked; after a plain reply, or a "Stop.", use `open` first |
| `end` | | Ends the conversation |

`contains` ignores case, accents and punctuation. `router`, `tool` and `card` take `equals`.

## Clips

Raw signed 16-bit little-endian mono at 16 kHz, with 0.25 s of silence before and 0.6 s after. They stay outside the
repository (`--clips`, by default `~/.cache/robot-spoken/clips`). A clip's name comes from what it says, and is `no-…` for a
text that is nothing but a "no" and `say-…` for anything else. Before noise goes in, the clean clip is decoded with Parakeet:
under 80% of the words right and it's dropped, and its tests are blocked. Short words can do that ("No." in Heart's voice reads
as "now", "Non." in Siwis's too), so write "No thanks." and "Non merci.".

Synthetic speech is easier to hear than people are, and French clips sound like France, not Québec. Nothing here covers the room,
Robot's own voice coming back into the microphone, a headset or the car, or how a voice sounds.

## Your voice

The test build's recorder shows about 120 English and 60 French phrases, one at a time, and records you through the same microphone
path Robot hears with. Start it with `adb shell am start -n ai.robot_app.spoken/app.heyrobot.spoken.SpokenRecorderActivity`; then
`run.py --serial <serial> --pull-voice` moves the files to `~/Robot/spoken-voice/` (`--voice-dir` for another place) and deletes each
from the phone once it has landed. They never go in git, an APK or a backup.

## Safety

- The test build is never granted calls, contacts, your calendar, notification access or Termux; the runner stops if it finds one.
- A timer, an alarm, the flashlight, opening an app, maps and a search run at once when you ask for them by name, with no card,
  and music keys reach your music app. No script may say those words, in any class: `otherAppWords` and `otherAppStarts` in
  `spoken.json`. A row that needs them stays on your list; `coverage.json` says what's left of each row, and a passed script ticks
  only the part it covers.
- A script's class is `read`, `robot-data` (Robot's own demo data; a yes only at a harmless card waited for first), or
  `stops-at-card`. Anything that could send, call, delete or pay is `stops-at-card`: it must reach a risky card, and only a plain
  no may be said after that. The checker refuses anything else, by the word lists in `spoken.json`.
- Before each clip that isn't a `no-` clip, the runner asks which cards are showing, and plays nothing if one isn't on the
  harmless list, or if the build won't say. The receiver holds the same rule on its side.
- On the test build, everything that reaches another app or the web (timers, alarms, opening an app, maps, searches) stops at its card, and
  those cards aren't harmless: only a no plays over one. Music by voice is off, as its keys have no card. These are that build's
  own settings, set at its first command and on every save; a release's defaults don't change.
- The microphone is the one permission the runner gives it: the listener opens it and throws its frames away while clips play.
- The guard runs before every input and stops the whole run if it refuses. The assistant role always goes back, even after a
  failure or a stop, with a record on the computer in case of a crash. Clips are deleted from the phone after every run.

## The protocol (version 1)

The test build holds `SpokenReceiver`, which requires `android.permission.DUMP`. Android hides `am broadcast`'s sender (`getSentFromUid()` is -1), so DUMP is what keeps apps out; a sender that names itself is refused unless it's the shell. It must hold the string `robot-spoken-harness`, so `verify_apk.sh` can find it.

    adb shell am broadcast -n ai.robot_app.spoken/app.heyrobot.spoken.SpokenReceiver --ei seq N --es cmd CMD [--es arg A] [--es value V]

| `cmd` | `arg`, `value` | Does |
|---|---|---|
| `status` | | Answers with `data`: `{"protocol": 1, "models": {"listen": bool, "gemma": bool}, "cards": [kinds showing], "listening": bool}` |
| `reset` | | Puts the short list of settings back to their defaults, ends any conversation |
| `set` | name, value | One setting from the short list |
| `open` | `wake` or `button` | Opens the panel |
| `play` | clip name | Feeds `files/spoken/clips/<name>` to the listener. While a card not on `harmlessCards` shows, refuses anything but `no-…` |
| `end` | | Ends the conversation; a card still waiting is cancelled, never confirmed |
| `import-models` | | Takes the models left in `files/spoken/import/` (`<bundle id>/<file>` or the model's file), catalogued names only, each checked by size and SHA-256 as a download is, the sources deleted either way. The answer has `data`: `{"files": n}`; then a line `{"type": "import", "file": ..., "ok": bool}` for each and a last `{"type": "import", "done": true, "listen": bool, "gemma": bool}`. `run.py --serial <serial> --models <models> --import-models` pushes the folder and calls it |

Every command gets one answer in `files/spoken/results.jsonl`, as soon as it's taken or refused; no answer in 8 s counts as a
failure of the run:

    {"type": "ack", "seq": N, "ok": true, "data": {...}}
    {"type": "ack", "seq": N, "ok": false, "why": "..."}

and each turn gets a line once its reply is settled or its card is showing:

    {"type": "turn", "heard": "...", "router": "ListsRouter", "reply": "...", "cards": [{"kind": "send_text"}], "tools": ["send_text"], "times": {...}}

`router` is the class's simple name, or `model`, also for a turn ToolRouter or PreRouter helped with: neither takes a turn itself, so a script can't name them. A router in `routersOff` (MusicRouter) can only be checked as absent. A card's `kind` is its tool's id, or `other`. Each line ends with a newline and is
written in one go; the runner deletes the file before each test.
