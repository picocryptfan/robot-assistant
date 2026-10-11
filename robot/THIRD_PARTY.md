# Third-party components in Robot

Robot itself is GPL-3.0-only (see `../LICENSE`; Copyright (C) 2026 the Robot authors). Everything below keeps its own
licence; all of them (Apache-2.0, MIT, BSD-2-Clause, MPL-2.0, CC-BY-4.0 for model weights and data, GPL-3.0-or-later for
espeak-ng) are compatible with GPL-3.0. Native libraries, models and the espeak-ng data are not stored in git:
`tools/fetch_third_party.sh` downloads the exact files below and checks every SHA-256 (same sources and hashes as the
phase 0 probe, see `../probe/THIRD_PARTY.md`). The speech recognition and voice models are not in the APK at all: the app
downloads them on request (see "Downloaded by the app" below); `tools/fetch_voice_models.sh` fetches the same files on a
computer and checks them.

## In the app: Settings › About › Licences

The app shows every component below, with its version, licence, copyright line, notes and the full licence text, from
one data file, `app/src/main/assets/licenses/components.json`, next to the texts it names (`app/src/main/assets/licenses/`:
the GPL-3.0, Apache-2.0, MPL-2.0 and CC BY 4.0 texts, each MIT and BSD text with its own copyright line, ONNX Runtime's
`ThirdPartyNotices.txt` and the Java libraries' NOTICE files). LiteRT-LM's and the NPU runtime's `LICENSE` and
`THIRD_PARTY_NOTICE.txt` (2.2 MB and 148 KB) ship inside their AARs and would not reach the APK by themselves: `:brain`'s
`aarNotices` task copies them from those very AARs into its assets at build time (`licenses/notices/`). The screen also
carries Robot's own notice (free software, no warranty, the GPL a tap away), the link to Robot's source for that version
and to the release's third-party source bundle (below).

**Keep the list in step.** A new dependency, model or service needs a row in the table below and an entry in
`components.json`; the app's `LicencesTest` fails otherwise. It checks that every row here is in the list with the same
licence and place (and the reverse), that every heading below and every Maven artifact named here belongs to an entry,
that every library the release build packs (`releaseRuntimeClasspath`) belongs to one, and that every text and notice
file the list names exists.

**Source for the GPL components.** espeak-ng (GPL-3.0-or-later) is statically linked into the prebuilt
`libsherpa-onnx-jni.so`, and its compiled data files are in the APK. Robot's own repository doesn't hold that source, so
each release attaches `robot-<version>-third-party-sources.tar.gz` (and its `.sha256`), made by
`tools/make_source_bundle.sh`: sherpa-onnx at the commit of v1.13.8, the exact espeak-ng archive (fork at `ed530aa1`) and
every other source sherpa-onnx's Android build compiles in, each checked against the SHA-256 sherpa-onnx's CMake files pin
(or, where only git could reach it, against its commit), and ONNX Runtime 1.28.2's source. See the README's "Releasing".

| Component | Version | Licence | Where |
|---|---|---|---|
| sherpa-onnx | 1.13.8 | Apache-2.0 | in the APK |
| espeak-ng | csukuangfj/espeak-ng at ed530aa1 | GPL-3.0-or-later | in the APK |
| piper-phonemize | csukuangfj/piper-phonemize at f3ff95af | MIT | in the APK |
| kaldi-native-fbank | 1.22.3 | Apache-2.0 | in the APK |
| kaldi-decoder | 0.3.0 | Apache-2.0 | in the APK |
| kaldifst | 1.8.0 | Apache-2.0 | in the APK |
| OpenFst | 1.8.5 (csukuangfj/openfst, 2026-07-09) | Apache-2.0 | in the APK |
| simple-sentencepiece | 0.7 | Apache-2.0 | in the APK |
| JSON for Modern C++ | 3.12.0 | MIT | in the APK |
| hclust-cpp (fastcluster) | 2026-02-25 | BSD-2-Clause | in the APK |
| Eigen | 5.0.1 | MPL-2.0 | in the APK |
| ONNX Runtime | 1.28.2 | MIT | in the APK |
| Silero VAD | silero_vad.onnx (9e2449e1) | MIT | in the APK |
| Wake word model (zipformer, GigaSpeech, 3.3M) | 2024-01-01 | Apache-2.0 | in the APK |
| Anthropic Java SDK | 2.71.0 | MIT | in the APK |
| OkHttp | 4.12.0 | Apache-2.0 | in the APK |
| Okio | 3.6.0 | Apache-2.0 | in the APK |
| Public Suffix List (in OkHttp) | as in OkHttp 4.12.0 | MPL-2.0 | in the APK |
| Jackson | 2.19.4 | Apache-2.0 | in the APK |
| FastDoubleParser (in jackson-core) | as in jackson-core 2.19.4 | MIT | in the APK |
| ClassMate | 1.7.0 | Apache-2.0 | in the APK |
| jsonschema-generator | 4.38.0 | Apache-2.0 | in the APK |
| Swagger Core annotations | 2.2.50 | Apache-2.0 | in the APK |
| SLF4J API | 2.0.16 | MIT | in the APK |
| Standard Webhooks | 1.2.0 | MIT | in the APK |
| LiteRT-LM | 0.18.0 | Apache-2.0 | in the APK |
| LiteRT NPU runtime for Google Tensor | 2.3.0 | Apache-2.0 | in the APK |
| Gson | 2.14.0 | Apache-2.0 | in the APK |
| Kotlin standard library and kotlin-reflect | 2.4.0 | Apache-2.0 | in the APK |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | in the APK |
| Jetpack Compose and AndroidX | Compose BOM 2025.06.01 (1.8.3), Material 3 1.3.2 | Apache-2.0 | in the APK |
| Material Icons | path data | Apache-2.0 | in the APK |
| Annotation libraries | as resolved | Apache-2.0 | in the APK |
| Gemma 4 E2B (Tensor G5) | litert-community, b3ca0d2f | Apache-2.0 | downloaded when you ask |
| Gemma 4 E4B | litert-community, 2eee7ac3 | Apache-2.0 | downloaded when you ask |
| Gemma 4 E4B abliterated | olekk, a4eecccd | Apache-2.0 | downloaded when you ask |
| Your own model file | the file you import | LicenseRef-own-file | downloaded when you ask |
| Parakeet TDT 0.6B v3 | int8, csukuangfj, 2bda32ec | CC-BY-4.0 | downloaded when you ask |
| Kokoro-82M | 1.0 (csukuangfj, f7b96bb6) | Apache-2.0 | downloaded when you ask |
| SIWIS French Speech Synthesis Database | training data of Kokoro's ff_siwis voice | CC-BY-4.0 | downloaded when you ask |
| Kitten TTS nano | 0.8 (csukuangfj2, eb4fe4f7) | Apache-2.0 | downloaded when you ask |
| OpenStreetMap data, through Nominatim | service | ODbL-1.0 | online service |
| Open-Meteo | service | CC-BY-4.0 | online service |
| Android Gradle Plugin | 8.8.2 | Apache-2.0 | build and tests only |
| Kotlin compiler and its Compose plugin | 2.4.0 | Apache-2.0 | build and tests only |
| JUnit | 4.13.2 | EPL-1.0 | build and tests only |
| org.json (JSON-java) | 20180813 | JSON | build and tests only |
| OkHttp MockWebServer | 4.12.0 | Apache-2.0 | build and tests only |
| sherpa-onnx Python package | 1.13.8 | Apache-2.0 | build and tests only |
| NumPy | as installed | BSD-3-Clause | build and tests only |
| SentencePiece (Python) | as installed | Apache-2.0 | build and tests only |
| pypinyin | as installed | MIT | build and tests only |

## In the `:voice` process only

### sherpa-onnx v1.13.8 (Apache-2.0): VAD, wake word, speech to text, text to speech

- Release https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8, archive `sherpa-onnx-v1.13.8-android.tar.bz2`
  (sha256 `2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44`), arm64-v8a only:
  - `voice/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` sha256 `3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f`
  - `voice/src/main/jniLibs/arm64-v8a/libonnxruntime.so` (ONNX Runtime 1.28.2, **MIT**; its `ThirdPartyNotices.txt` at
    v1.28.2 is in the APK) sha256 `33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1`. Built by
    sherpa-onnx's author (`csukuangfj/onnxruntime-libs` release v1.28.2, `onnxruntime-android-1.28.2.zip`, sha256
    `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d` in sherpa-onnx's
    `cmake/onnxruntime-android-aarch64.cmake`); its source is microsoft/onnxruntime at tag v1.28.2 (commit `33ca9628`).
- Kotlin API copied verbatim from the repository at that tag (`sherpa-onnx/kotlin-api/`, commit
  `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`), package `com.k2fsa.sherpa.onnx` kept because the JNI binds to those
  names: `FeatureConfig.kt`, `HomophoneReplacerConfig.kt`, `KeywordSpotter.kt`, `OnlineRecognizer.kt`,
  `OnlineStream.kt`, `QnnConfig.kt`, `Vad.kt`, and for speech to text and text to speech (docs/voice-plan.md 4.1):

| file | sha256 |
|---|---|
| `OfflineRecognizer.kt` | `85054fa9722913bce96c94c5c7eaaa7fd99606b79fbfbeac6cfb60040eae2164` |
| `OfflineStream.kt` | `5c90b7c060cdd0a2222cc935b54f69465a4a7544de29240bafe4d3a8d86dbb6e` |
| `Tts.kt` | `1a2a19e247553f774c5abdfda8319ce9e1a44e21279b16dc2c63e963a82ea9b1` |

- Licence text: `third_party/LICENSE-sherpa-onnx.txt`.
- Statically linked into `libsherpa-onnx-jni.so` (nothing extra is packaged for them; the versions are the ones
  sherpa-onnx v1.13.8's CMake files pin, read from its source):
  **espeak-ng** (GPL-3.0-or-later; built from the `csukuangfj/espeak-ng` fork at
  `ed530aa113046142eb5115cf2fc9157854d0ffe1`, per `cmake/espeak-ng-for-piper.cmake`), which turns text into phonemes for
  the Kokoro and Kitten voices; piper-phonemize (MIT, fork at `f3ff95af`), kaldi-native-fbank 1.22.3, kaldi-decoder
  0.3.0, kaldifst 1.8.0 and OpenFst 1.8.5 (csukuangfj's 2026-07-09 copy), simple-sentencepiece 0.7 (all Apache-2.0), JSON
  for Modern C++ 3.12.0 (MIT), hclust-cpp 2026-02-25 (fastcluster, BSD-2-Clause, for speaker clustering) and Eigen 5.0.1
  (MPL-2.0, header-only, through kaldi-decoder). (cppjieba, listed here before, is not part of v1.13.8.) espeak-ng's
  GPL needs its Corresponding Source to go with the library: Robot's repository doesn't hold it, so every release
  attaches the third-party source bundle (above); the GPL text itself is in the APK (Settings › About › Licences).

### Keyword model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (Apache-2.0)

- https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
  (sha256 `f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a`). Robot packages the fp32 files it loads:

| file in `voice/src/main/assets/kws/` | sha256 |
|---|---|
| encoder-epoch-12-avg-2-chunk-16-left-64.onnx | `063fbc1aeae8a9b574607a331a00e60371846ef9eaa3c1d9ea48176665dfc693` |
| decoder-epoch-12-avg-2-chunk-16-left-64.onnx | `f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3` |
| joiner-epoch-12-avg-2-chunk-16-left-64.onnx | `0d7a37e749d8055223029318d6ffae82db1dae2d315d0892a68ba5dad17c1d2d` |
| tokens.txt (in git) | `fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53` |
| bpe.model (not in git; in the APK since 0.2.3) | `c8a2a0129c4ab8e463164c142f82d25649661b122c8cd0b7aab5c9e80b90ad24` |
| keywords.txt (in git, generated) | `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b` |

- `bpe.model` is the model's SentencePiece file (a unigram model, despite its name; 244,837 bytes), from the same
  archive. Since 0.2.3 the app reads it to spell the user's wake phrases on the phone (core `SentencePiece.kt`, pure
  Kotlin, checked against sherpa-onnx's `text2token` on 115 phrases); `tools/fetch_third_party.sh --install` copies it
  to `voice/src/main/assets/kws/` and to `tools/kws-build/` (for `tools/make_keywords.py`).
- `keywords.txt` is one empty line since 0.2.3: sherpa-onnx adds every stream's keywords to this list, and Robot gives
  each stream its keywords at runtime (the wake phrases from Settings, "STOP" while it speaks). Written by
  `tools/make_keywords.py`, which also prints "HEY ROBOT" spelled by sherpa-onnx's `text2token`
  (`▁HE Y ▁RO B O T`, the 0.2.2 keywords.txt).
- `stop.txt` (in git, generated the same way) = `▁ST O P @STOP`, sha256
  `03d4910c6036aee31953f9146d8d7ab9e78aa68cbc06ce9c242bf45a2d7a5802`: added to the keyword stream only while Robot
  is speaking, so "stop" said aloud ends the reply.

### Silero VAD (MIT)

- `voice/src/main/assets/vad/silero_vad.onnx` from https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx,
  sha256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`. Licence: `third_party/LICENSE-silero-vad.txt`.

### espeak-ng-data, English + French subset (GPL-3.0-or-later)

From https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2 (sha256
`4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533`; identical to the copies inside the Kokoro and
Kitten archives). Only the 17 files espeak reads for English and French are packaged, unchanged, in
`voice/src/main/assets/espeak-ng-data/` (880,227 bytes; docs/voice-plan.md 2.5). The app copies them to its private
storage and checks each hash before a voice is created.

| file | bytes | sha256 |
|---|---|---|
| phontab | 55,796 | `886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6` |
| phonindex | 39,074 | `3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4` |
| phondata | 550,424 | `4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3` |
| intonations | 2,040 | `3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc` |
| en_dict | 166,944 | `71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35` |
| fr_dict | 63,727 | `e399ab924c4d10beef1fc310b30ea56e4ddfd8b4b64b8ed978e9c65394d49b2d` |
| lang/gmw/en | 140 | `4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83` |
| lang/gmw/en-US | 257 | `41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704` |
| lang/gmw/en-US-nyc | 271 | `e8ee9168376d1f7d9e7dcda7c4644c36c529a8bea816e6380895364db46ea2e3` |
| lang/gmw/en-029 | 335 | `faeb8cb201056775f733acbd908c66555fe57bf01cb063920f178f389cbf85d3` |
| lang/gmw/en-GB-scotland | 295 | `1ce4282c1f4385dbaf0035e799b2ab1ee9e9a3ab4829100594a3047727b52353` |
| lang/gmw/en-GB-x-gbclan | 238 | `2040e176f1f7f27bdce81c32d7e2b1662449d54d264afd3c0ed871b8e42b6e46` |
| lang/gmw/en-GB-x-gbcwmd | 188 | `927a5ab891c65b30b4426b151d1623a01024f6b773a38497ba1c90cd77a95747` |
| lang/gmw/en-GB-x-rp | 249 | `d0625af7f58561b1b8cf96fd7f93eee6553bcb3eadb9020ae0757bf96e5115e5` |
| lang/roa/fr | 79 | `95f44834b48c075dad13eace54d2c98ff79b81aa0074dd67eebaf66c2909eef8` |
| lang/roa/fr-BE | 84 | `72496a316e47b997f893500b8953dc01a10b86a2d4238e13c1260f50b47d792a` |
| lang/roa/fr-CH | 86 | `b449b0fd99d9cd93261fe1c6b816448b99b4a10420bee3f301e669568ad0de55` |

### Benchmark clip (in git, generated)

`voice/src/main/assets/bench/test-clip.s16` (170,452 bytes, sha256
`b3234e1db5bf98d3413d3df787e4a9a10b7f9cd4aedca688b9b097fdbefcd3de`): synthetic speech, 5.3 s at 16 kHz, raw 16-bit,
made on the build machine by `tools/make_test_clip.py` with Kokoro v1.0 (voice af_heart, Apache-2.0) reading the
sentence in `bench/test-clip.txt`. No person's voice. Used only by Settings › Voice › "Test voice speed".

### Text to speech fallback (not bundled)

While a sherpa-onnx voice is not downloaded, or when it is chosen, Robot speaks through Android's TextToSpeech API,
which on GrapheneOS is the system's GrapheneOS Speech Services; nothing is packaged.

### Anthropic Java SDK 2.71.0 (MIT): Ask Claude's API client (`:claude` module)

The official SDK for the Claude API (https://github.com/anthropics/anthropic-sdk-java), from Maven Central, used only
by the `:claude` module, which only the assistant package (the `:voice` process) depends on; the main process never
loads it (`ProcessIsolationTest`, `tools/verify_apk.sh`). It talks to `api.anthropic.com` only when you ask for
Claude, or let Robot check a hard question with Claude (docs/ask-claude.md). Built in as Java/Kotlin classes (no native code). Licence: MIT.

| artifact | licence | sha256 of the jar |
|---|---|---|
| `com.anthropic:anthropic-java:2.71.0` | MIT | `f460c0b24bd6d72c17acd479024490b4f7b39da014f58e482621bcd69bb4dcfc` |
| `com.anthropic:anthropic-java-core:2.71.0` | MIT | `9958b59277bf01bcd70d3c8856a7ca20bfe78a35e87b82e26f432d0f185c500f` |
| `com.anthropic:anthropic-java-client-okhttp:2.71.0` | MIT | `ab0870957cf8abf401ed8a0be3d823a894b9156fd3350a5c58e305b3fec09ac1` |

What it brings with it at run time (resolved by Gradle, versions as built):

| artifact | licence |
|---|---|
| `com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okio:okio:3.6.0`, `com.squareup.okio:okio-jvm:3.6.0` | Apache-2.0 |
| the Public Suffix List, compiled into OkHttp (`publicsuffixes.gz`; source: publicsuffix.org) | MPL-2.0 |
| `com.fasterxml.jackson.core:jackson-core`, `-databind`, `-annotations` 2.19.4; `jackson-datatype-jdk8`, `-jsr310`, `jackson-module-kotlin` 2.19.4 | Apache-2.0 |
| FastDoubleParser (bundled inside jackson-core 2.19.4, its LICENSE in the APK; with code from fast_float, MIT, and bigint, BSD-2-Clause) | MIT |
| `org.jetbrains.kotlin:kotlin-reflect` (for jackson-module-kotlin, which asks for 1.9.25; Gradle resolves the 2.4.0 that LiteRT-LM brings, and that one is in the APK) | Apache-2.0 |
| `com.github.victools:jsonschema-generator`, `-module-jackson`, `-module-swagger-2` 4.38.0; `com.fasterxml:classmate:1.7.0` | Apache-2.0 |
| `io.swagger.core.v3:swagger-annotations:2.2.50`, `com.google.errorprone:error_prone_annotations:2.50.0` | Apache-2.0 |
| `org.slf4j:slf4j-api:2.0.16` (no binding: it logs nothing) | MIT |
| `com.standardwebhooks:standardwebhooks:1.2.0` | MIT |

Tests only (not in the APK): `com.squareup.okhttp3:mockwebserver:4.12.0` (Apache-2.0), the local mock server the
network tests talk to through the SDK's base-URL override.

## Downloaded by the app (not in the APK; Model › Voice models)

Single files from Hugging Face mirrors made by the sherpa-onnx project, at pinned commits
(`https://huggingface.co/<repo>/resolve/<commit>/<file>`), each checked against the size and SHA-256 below before
the voice process may load it (core `VoiceModels.kt`; docs/voice-plan.md 2.4). Stored in Robot's private storage,
`files/models/<bundle>/`. All hashes were re-checked on the build machine on 2026-10-10 with
`tools/fetch_voice_models.sh`.

### Speech recognition: NVIDIA Parakeet TDT 0.6B v3, int8 (CC-BY-4.0)

> "Parakeet TDT 0.6B v3" by NVIDIA, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3, licensed under the Creative
> Commons Attribution 4.0 International licence (CC BY 4.0, https://creativecommons.org/licenses/by/4.0/).
> Changed: converted to ONNX and quantised to int8 by the sherpa-onnx project
> (https://github.com/k2-fsa/sherpa-onnx); Robot uses it unchanged from that conversion.

Repo `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` @ `2bda32ec70b097a55adaa07d9a7173915b43cc78`
(bundle `stt-parakeet-v3`, 670,478,772 bytes). The attribution, with the licence's link and its "as is" disclaimer, is
also shown in Settings › About › Licences.

| file | bytes | sha256 |
|---|---|---|
| encoder.int8.onnx | 652,184,281 | `acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247` |
| decoder.int8.onnx | 11,845,275 | `179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e` |
| joiner.int8.onnx | 6,355,277 | `3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3` |
| tokens.txt | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |

### Voice: Kokoro-82M v1.0, fp32 (Apache-2.0)

hexgrad/Kokoro-82M (https://huggingface.co/hexgrad/Kokoro-82M), Apache-2.0; trained on permissively licensed data.
Voices used: `af_heart` (speaker 3, English, the default) and `ff_siwis` (speaker 30, French, for later), whose
training data is the SIWIS French speech corpus (CC-BY-4.0, University of Edinburgh). Repo
`csukuangfj/kokoro-multi-lang-v1_0` @ `f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b` (bundle `tts-kokoro-v1.0`,
353,762,203 bytes; the lexicons and espeak-ng data of that repo are not downloaded).

| file | bytes | sha256 |
|---|---|---|
| model.onnx | 325,560,556 | `b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11` |
| voices.bin | 28,200,960 | `1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29` |
| tokens.txt | 687 | `6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425` |

### Fast voice (fallback): Kitten TTS nano 0.8, fp32 (Apache-2.0)

KittenML (https://huggingface.co/KittenML), Apache-2.0. Voice used: `expr-voice-5-f` (speaker 7). Repo
`csukuangfj2/kitten-nano-en-v0_8-fp32` @ `eb4fe4f70801b2eb1c3f45be046ccd553c7575e7` (bundle `tts-kitten-nano-0.8`,
60,045,866 bytes).

| file | bytes | sha256 |
|---|---|---|
| model.fp32.onnx | 56,768,002 | `2174dbf67b58b7b50d7b65294f89c2c53c172834533519b853c579879a04cc22` |
| voices.bin | 3,276,800 | `d520519c4a3519d44fcfcd943ed0b1e3c5da5cee0eea501d922fac1a93cd24dc` |
| tokens.txt | 1,064 | `934a4188addc7665dd3410256bb622169242357fbb99d840d9351209b486dabb` |

## In the `:brain` process only

- `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (Apache-2.0), Google Maven; `liblitertlm_jni.so` arm64-v8a. It runs
  the model you choose on the TPU (NPU backend) or the CPU (XNNPACK); nothing new is linked for the CPU models (0.4.1).
  Its LICENSE and THIRD_PARTY_NOTICE.txt ship inside the AAR; `:brain`'s `aarNotices` task copies both into the APK's
  assets (as it does the NPU runtime's two).
- `com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.0` (Apache-2.0), non-transitive;
  `libLiteRtDispatch_GoogleTensor.so` is packaged, `libLiteRtCompilerPlugin_google_tensor.so` excluded (as in the probe and the lab).
- The Tensor TPU vendor libraries (`libedgetpu_*`) are the phone's own, declared with `<uses-native-library required="false">`, not bundled.
- Transitive: `com.google.code.gson:gson:2.14.0`, `org.jetbrains.kotlin:kotlin-reflect:2.4.0` (Apache-2.0).

## Models (not in the APK; downloaded or imported by the app on request, Settings › Model)

Robot downloads a model only when you tap Download, from Hugging Face, pinned to one commit, and checks the file's size and
SHA-256 before it is ever loaded. The weights are not part of Robot and are not linked into it: they are data files that
LiteRT-LM reads at run time, so Robot's GPL-3.0 is not affected. Gemma 4 is released by Google under Apache-2.0 (Google's
Gemma 4 licence page is the Apache-2.0 text; the repositories below say `apache-2.0`).

- **Fast (default): Gemma 4 E2B for the Tensor G5 TPU.** `gemma-4-E2B-it_Google_Tensor_G5.litertlm`,
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm, revision `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`,
  3,113,545,589 bytes, sha256 `af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc`. Published by Google
  (litert-community). Licence: Apache-2.0.
- **Smarter, slower: Gemma 4 E4B (CPU).** `gemma-4-E4B-it.litertlm`,
  https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm, revision `2eee7ac325f20eb8c9ac1d0e972f7c84663062da`,
  3,659,530,240 bytes, sha256 `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`. Published by Google
  (litert-community). Licence: Apache-2.0.
- **Uncensored, slower: Gemma 4 E4B abliterated (CPU).** `gemma-4-E4B-it-abliterated.litertlm`,
  https://huggingface.co/olekk/gemma-4-E4B-it-abliterated-litert-lm, revision `a4eecccd3b0ba1777660180cda60a396eedcb8aa`,
  3,659,530,240 bytes, sha256 `f5a6625e0368e853743c91b39fa6ac6a8add1fc4825b18f98f518966610f2a88`. A derivative of Google's
  E4B file above by olekk (community build, not from Google; method and code at codeberg.org/oknyshuk/gemma4-e4b-abliterated).
  Licence: Apache-2.0 (its model card). The card also asks that it be used for research and personal experimentation,
  says you are responsible for its outputs, and says the base Gemma 4 Prohibited Use Policy
  (https://ai.google.dev/gemma/prohibited_use_policy) applies.
- **Your own model file.** Whatever `.litertlm` you import keeps its own licence. Robot doesn't ship it; it shows the
  file's SHA-256 and size and reads what its header says it is (`core/LiteRtLm.kt`, written for Robot from LiteRT-LM's
  published header schema and LlmMetadata protos, Apache-2.0; no code copied). For example MedGemma 1.5 4B (0.4.3, README
  "Short-context models"): Google's Health AI Developer Foundations terms of use, not Apache-2.0; Robot neither ships nor
  downloads it.
- Not offered: no Gemma 4 E4B build for the Tensor TPU exists. `safecircleai/horizon-edge-4b` (a renamed CPU export of a
  child-safety classifier fine-tune, under the non-commercial "SafeCircle Research License") is never offered.

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

Phase 2 adds no dependency. Robot hands drafts to apps you install (Cheogram, Proton Mail, an SMS app, a
calendar app) through Android intents only; nothing of them is bundled. Their source (Cheogram: GPL-3.0; Proton Mail
for Android: GPL-3.0) was read to match what they accept (`../docs/phase-2.md`); no code was copied.

Build and test only (not in the APK): Android Gradle Plugin 8.8.2, Kotlin and its Compose compiler plugin
2.4.0, JUnit 4.13.2 (EPL-1.0), org.json 20180813 (JSON licence, tests only). The tools in `tools/` use the
`sherpa-onnx==1.13.8` Python wheel (Apache-2.0), numpy (BSD-3-Clause), sentencepiece (Apache-2.0) and pypinyin (MIT)
on the build machine only.

## Online services the app asks (0.4.0; nothing bundled)

- **OpenStreetMap's Nominatim** (`nominatim.openstreetmap.org`, operated by the OpenStreetMap Foundation, or the Nominatim
  server you choose in Settings › Actions › Search server): "directions to the nearest …" when Settings › Actions › Find
  the nearest place online allows it. Map data © OpenStreetMap
  contributors, available under the Open Database License (ODbL 1.0, https://opendatacommons.org/licenses/odbl/); the
  card credits it ("Data © OpenStreetMap contributors, ODbL"). Used under the Nominatim usage policy
  (https://operations.osmfoundation.org/policies/nominatim/); see the README's "The nearest place". Robot keeps none
  of the data beyond a 10-minute cache in memory.
- **Open-Meteo** (`api.open-meteo.com`, `geocoding-api.open-meteo.com`): the weather. Weather data by Open-Meteo.com
  (https://open-meteo.com/), under Creative Commons Attribution 4.0 (CC BY 4.0,
  https://creativecommons.org/licenses/by/4.0/); the weather card credits it ("Weather data by Open-Meteo.com, CC BY
  4.0"), and Robot shortens the forecast into its own lines. The free API is for non-commercial use, with limits of
  10,000 calls a day, 5,000 an hour and 600 a minute (Open-Meteo's terms, https://open-meteo.com/en/terms); Robot is a
  free, non-commercial app and asks only when you ask about the weather. See the README's "Weather".

## Code adapted from the phase 0 probe and the GPU lab (same author, GPL-3.0)

The listener and wake word (probe `Listener.kt`, `WakeWord.kt`), the assistant service, recognizer stub and
session (probe `ProbeVoiceService.kt`, `ProbeSession.kt`), the brain binding and TPU load (probe
`BrainKeeper.kt`, `BrainClient.kt`, `BrainService.kt`, `Gemma.kt`, lab `Workload.kt` / `Configs.T`), the model
store (probe `ModelStore.kt`), the restart check (probe `BootReceiver.kt`, `BootCheck.kt`), the fetch,
keyword and APK check scripts, and the process isolation test.
