# Third-party components in Robot 0.1.0 (phase 1)

Robot itself is GPL-3.0-only (see `../LICENSE`). Everything below keeps its own licence; all of them
(Apache-2.0, MIT) are compatible with GPL-3.0. Native libraries and models are not stored in git:
`tools/fetch_third_party.sh` downloads the exact files below and checks every SHA-256 (same sources and
hashes as the phase 0 probe, see `../probe/THIRD_PARTY.md`).

## In the `:voice` process only

### sherpa-onnx v1.13.8 (Apache-2.0): VAD and wake-word runtime

- Release https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8, archive `sherpa-onnx-v1.13.8-android.tar.bz2`
  (sha256 `2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44`), arm64-v8a only:
  - `voice/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` sha256 `3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f`
  - `voice/src/main/jniLibs/arm64-v8a/libonnxruntime.so` (ONNX Runtime 1.28.2, **MIT**) sha256 `33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1`
- Kotlin API copied verbatim from the repository at that tag (`sherpa-onnx/kotlin-api/`), package
  `com.k2fsa.sherpa.onnx` kept because the JNI binds to those names: `FeatureConfig.kt`,
  `HomophoneReplacerConfig.kt`, `KeywordSpotter.kt`, `OnlineRecognizer.kt`, `OnlineStream.kt`, `QnnConfig.kt`, `Vad.kt`.
- Licence text: `third_party/LICENSE-sherpa-onnx.txt`.

### Keyword model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (Apache-2.0)

- https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
  (sha256 `f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a`). Robot packages the fp32 files it loads:

| file in `voice/src/main/assets/kws/` | sha256 |
|---|---|
| encoder-epoch-12-avg-2-chunk-16-left-64.onnx | `063fbc1aeae8a9b574607a331a00e60371846ef9eaa3c1d9ea48176665dfc693` |
| decoder-epoch-12-avg-2-chunk-16-left-64.onnx | `f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3` |
| joiner-epoch-12-avg-2-chunk-16-left-64.onnx | `0d7a37e749d8055223029318d6ffae82db1dae2d315d0892a68ba5dad17c1d2d` |
| tokens.txt (in git) | `fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53` |
| keywords.txt (in git, generated) | `41f3df8797744a108b9802dc54adb57616928affe168c58a89aa05780884f0db` |

- `keywords.txt` = `▁HE Y ▁RO B O T @HEY_ROBOT`, written by `tools/make_keywords.py` with sherpa-onnx's
  `text2token` from `tools/kws-build/bpe.model` (sha256 `c8a2a0129c4ab8e463164c142f82d25649661b122c8cd0b7aab5c9e80b90ad24`,
  build time only, not in the APK).

### Silero VAD (MIT)

- `voice/src/main/assets/vad/silero_vad.onnx` from https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx,
  sha256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`. Licence: `third_party/LICENSE-silero-vad.txt`.

### Text to speech (not bundled)

Phase 1 speaks through Android's TextToSpeech API, which on GrapheneOS is the system's GrapheneOS Speech
Services; nothing is packaged. The voice spike replaces it with a sherpa-onnx voice (listed here then).

## In the `:brain` process only

- `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (Apache-2.0), Google Maven; `liblitertlm_jni.so` arm64-v8a.
  Its LICENSE and THIRD_PARTY_NOTICE.txt ship inside the AAR.
- `com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.0` (Apache-2.0), non-transitive;
  `libLiteRtDispatch_GoogleTensor.so` is packaged, `libLiteRtCompilerPlugin_google_tensor.so` excluded (as in the probe and the lab).
- The Tensor TPU vendor libraries (`libedgetpu_*`) are the phone's own, declared with `<uses-native-library required="false">`, not bundled.
- Transitive: `com.google.code.gson:gson:2.14.0`, `org.jetbrains.kotlin:kotlin-reflect:2.4.0` (Apache-2.0).

## Gemma 4 E2B (not in the APK; downloaded or imported by the app on request)

- `gemma-4-E2B-it_Google_Tensor_G5.litertlm`, https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm,
  revision `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`, 3,113,545,589 bytes,
  sha256 `af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc` (checked by the app before it is ever loaded).
  Model card licence: Apache-2.0.

## Every process (Apache-2.0)

| dependency | version |
|---|---|
| Jetpack Compose (ui, foundation, animation, runtime, material-ripple), BOM `androidx.compose:compose-bom` | 2025.06.01 (1.8.3) |
| `androidx.compose.material3:material3` | 1.3.2 |
| `androidx.compose.material:material-icons-core` (transitive) | 1.7.8 |
| `androidx.activity:activity-compose` | 1.10.1 |
| `androidx.core:core-ktx` (FileProvider) | 1.16.0 |
| `androidx.lifecycle` (runtime, runtime-compose, viewmodel, process) | 2.8.7 |
| `androidx.savedstate:savedstate-ktx` | 1.2.1 |
| other AndroidX (annotation, collection, arch-core, emoji2, startup, profileinstaller, tracing, autofill, graphics-path, customview-poolingcontainer, interpolator, versionedparcelable, concurrent-futures) | as resolved by the above |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 |
| `com.google.errorprone:error_prone_annotations`, `com.google.guava:listenablefuture`, `org.jetbrains:annotations`, `org.jspecify:jspecify` | annotations only |

The icons in `app/src/main/java/app/heyrobot/ui/common/Icons.kt` are path data from Google's Material
Icons (Apache-2.0).

Build and test only (not in the APK): Android Gradle Plugin 8.8.2, Kotlin and its Compose compiler plugin
2.4.0, JUnit 4.13.2 (EPL-1.0), org.json 20180813 (JSON licence, tests only).

## Code adapted from the phase 0 probe and the GPU lab (same owner, GPL-3.0)

The listener and wake word (probe `Listener.kt`, `WakeWord.kt`), the assistant service, recognizer stub and
session (probe `ProbeVoiceService.kt`, `ProbeSession.kt`), the brain binding and TPU load (probe
`BrainKeeper.kt`, `BrainClient.kt`, `BrainService.kt`, `Gemma.kt`, lab `Workload.kt` / `Configs.T`), the model
store (probe `ModelStore.kt`), the restart check (probe `BootReceiver.kt`, `BootCheck.kt`), the fetch,
keyword and APK check scripts, and the process isolation test.
