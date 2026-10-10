# Robot

A private voice assistant for GrapheneOS. The AI runs on the phone: Gemma 4 E2B on the Pixel's TPU, and bigger models on the CPU when you ask for them. It goes online only when you ask it to. Say "hey robot" and it answers.

Status: phase 1, the first daily-usable Robot (`robot/`, spec in `docs/phase-1.md`). Phase 0 (`probe/`) checked the risky parts on a real phone first. The game plan (models, voice stack, actions, safety, licences, roadmap) is in the project doc "Hey Robot game plan".

## Layout

| Path | What it is |
| --- | --- |
| `robot/` | Robot, the assistant (phase 1): Gradle project with `:app`, `:brain`, `:voice`, `:core`; see `robot/README.md` |
| `probe/` | Robot Probe, the phase 0 test app (default-assistant role, always-on "hey robot", battery, E2B on the TPU, quick checks) |
| `labs/gpulab/` | Off Grid GPU lab: the test app that measured LiteRT-LM on the Pixel 10 Pro's GPU, TPU and CPU |

## Building Robot Probe

Needs JDK 17+, the Android SDK (platform 36) and Gradle 8.x.

```
cd probe
echo "sdk.dir=/path/to/android-sdk" > local.properties
tools/fetch_third_party.sh --install   # sherpa-onnx native libs, wake-word and VAD models, each checked by SHA-256
python3 tools/make_keywords.py .       # writes the "HEY ROBOT" keywords file
gradle :app:assembleRelease
```

The native libraries and models are not stored in git; the fetch script downloads the exact versions listed in `probe/THIRD_PARTY.md`.

## Licence

GPL-3.0-only (see `LICENSE`). Third-party components keep their own licences; see `probe/THIRD_PARTY.md`.
