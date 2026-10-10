# Robot (phase 1)

A private voice assistant for GrapheneOS on the Pixel 10 Pro. Open it with the corner swipe, from the lock
screen, or by saying "hey robot"; type (voice input comes with the next build) or show it a photo or the
screen; Gemma 4 E2B on the Tensor G5 TPU answers, streams the reply and can read it aloud. Everything runs
on the phone. The build spec is `../docs/phase-1.md`.

Licence: GPL-3.0-only, see `../LICENSE`. Third-party parts and their licences: `THIRD_PARTY.md`.

## Modules and processes

| Module | Runs in | Holds |
|---|---|---|
| `:app` | main + `:voice` | `ui/` (main: home, history, model manager, settings, assistant setup), `assistant/` (`:voice`: the VoiceInteractionService, the Compose panel in the session, the recognizer stub, tools, the brain binding), `sys/` (storage, model store, restart check), `boot/` |
| `:brain` | `:brain` | `BrainService` (Messenger) and `GemmaEngine`, the only code that touches LiteRT-LM |
| `:voice` | `:voice` | microphone loop, Silero VAD + "HEY ROBOT" (sherpa-onnx), `SpeechToText` / `TextToSpeech` interfaces with the phase 1 placeholders |
| `:core` | everywhere | pure logic: prompt and context window, tool specs and validation, approval gate, model state, IPC contract, history format, settings, no-content logging |

- The main app never loads an engine; `ProcessIsolationTest` checks sources, Gradle files and the merged manifest.
- `:brain` is bound from the assistant with `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_INCLUDE_CAPABILITIES`;
  it is not exported and requires Robot's signature permission `app.heyrobot.permission.INTERNAL`.
- Tools run in the app process (`:voice`), never in `:brain`: the model only proposes calls
  (`automaticToolCalling = false`, constrained decoding when the TPU build allows it), the approval gate decides.

## Building

Needs JDK 17+, the Android SDK (platform 36, build-tools 36) and Gradle 8.x.

```
cd robot
echo "sdk.dir=/path/to/android-sdk" > local.properties
tools/fetch_third_party.sh --install          # sherpa-onnx libs, wake-word and VAD models, SHA-256 checked
gradle :core:testReleaseUnitTest :app:testReleaseUnitTest
gradle :app:assembleRelease -Probot.keystore=/path/outside/repo/robot-release.p12 \
                            -Probot.keystorePassFile=/path/outside/repo/robot-release.pass
tools/verify_apk.sh app/build/outputs/apk/release/app-release.apk
```

The release key is `../keys/robot-release.p12.enc` (decrypt it outside the repository, see `../keys/README.md`).
The two paths can also come from the environment (`ROBOT_KEYSTORE`, `ROBOT_KEYSTORE_PASS_FILE`); the password
file holds the password on its first line. Without them the release APK is unsigned. Nothing about the key
goes into the repository or the build output.

## Hand-off points for the voice spike

- `voice/.../SpeechToText.kt`: start / stop / cancel with onListening, onLevel, onPartial, onFinal, onError.
  `PlaceholderSpeechToText` answers "Voice input arrives in the next build" and the panel falls back to the keyboard.
- `voice/.../TextToSpeech.kt`: speak(sentence) / stop / isSpeaking / configure(rate, voice). `AndroidTextToSpeech`
  uses the system engine.
- `VoiceEngines` picks the implementations; `Listener` is an `AudioFrames` source, so speech to text can share
  the always-on microphone instead of opening a second one (pause the wake word with `Listener.wakePaused`).
- Keep sherpa-onnx inside `:voice` (the isolation test enforces it) and list new models in `THIRD_PARTY.md`.
