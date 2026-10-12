# Robot

> **Draft for review** (written for 1.0 against Robot 0.9.0's code). Check the repository links, the certificate
> fingerprint and the sizes against the release before publishing.

Robot is a private voice assistant for GrapheneOS on Google Pixel phones. Its AI model runs on the phone, so what you
say and what it answers stay there. It goes online only when you ask it to: for the weather, a web search you set up, a
question you hand to an online helper with your own key, or a download. Say "hey robot", or swipe up from a bottom
corner, and ask.

Robot is free software under the GNU GPL version 3 (GPL-3.0-only). There is no account, no analytics, no ads and no
crash reporting.

## Status and devices

- **Tested on:** Pixel 10 Pro with GrapheneOS. Robot's fast model (Gemma 4 E2B) runs on the Tensor G5's TPU there.
- **Other Pixels** (Pixel 9a and the Tensor G1 to G4 phones): the same model runs on the CPU or GPU. It works, more
  slowly; these phones have had less testing.
- **Pixel 11 (Tensor G6):** the TPU build is in beta.
- Android 14 or later, arm64 only. Robot is built and tested on GrapheneOS; other Android builds are untested.

## Install

1. **Download** the APK from this repository's [GitHub Releases](https://github.com/picocryptfan/robot-gos-assistant/releases)
   (`Robot-<version>.apk`), or add the repository to [Obtainium](https://github.com/ImranR98/Obtainium) (Add App, paste
   `https://github.com/picocryptfan/robot-gos-assistant`) so it tells you about new releases. Robot can also check for
   its own updates (Settings › About › Updates).
2. **Check the signing certificate** before you install the first time. Its SHA-256 fingerprint is:

   ```
   40:25:57:A4:6A:49:17:50:68:8A:3D:FF:71:41:00:FE:1D:20:B2:F6:11:93:A9:12:35:8C:2E:DD:34:1A:6B:6A
   ```

   On a computer: `apksigner verify --print-certs Robot-<version>.apk` ("Signer #1 certificate SHA-256 digest", without
   the colons). Obtainium and AppVerifier can check it on the phone. Android refuses any later update signed with
   another key, and so does Robot's own updater.
3. **Make Robot your digital assistant** when it asks (Settings › Apps › Default apps › Digital assistant app). The
   corner swipe and "hey robot" then open it, also from the lock screen.
4. **Download what you need**, from the app's first screen, once (Wi-Fi recommended: Robot warns before any download
   over 200 MB and lets you wait for Wi-Fi):

   | What | Size | Needed for |
   |---|---|---|
   | Robot's model, Fast (Gemma 4 E2B) | 2.6–3.3 GB, by phone | answering |
   | Speech recognition (NVIDIA Parakeet TDT 0.6B v3) | 670 MB | talking to Robot |
   | Voice (Kokoro 82M) | 354 MB | spoken replies (or a 60 MB fallback voice, or your phone's own voice) |
   | Fast translation engine (Mozilla Bergamot fr↔en) | 52 MB | optional: conversation mode's quick translations |
   | Speaker model (NVIDIA TitaNet-S) | 40 MB | optional: telling speakers apart in transcripts |

   Every file comes from a pinned commit and is checked against its SHA-256 before Robot uses it.

## What it can do

- **Answer by voice or text**, on the phone, and about a photo or the screen you're on (once the phone is unlocked).
  Choose a smarter, slower model, or your own `.litertlm` or `.gguf` file.
- **Do things, asking first when it should:** timers and alarms, texts and emails as drafts (you press Send), notes,
  your calendar, contacts, directions in Organic Maps, the weather (Open-Meteo), the nearest place of a kind
  (OpenStreetMap), your phone's storage, battery and memory, and freeing space. Every rule about when Robot asks first
  is a switch in Settings › Safety & privacy.
- **Remember what you ask it to**, encrypted on the phone, and **read, summarize, translate and answer your messages**
  from the apps you choose (a reply goes only after you tap Send).
- **Conversation mode:** transcribes a conversation in English and French, on the phone, translates it (also face to
  face, aloud) and summarizes it.
- **Routines and a morning brief**, **headset mode** (your headset's button opens Robot) and **car mode**.
- **Web search** with the service you choose (your own SearXNG, Brave, Kagi, Mojeek or a public SearXNG), off until you
  set it up; answers name their sources.
- **Termux:** runs your own scripts and short commands, each command on a card you approve first; off until you set it
  up.
- **Helpers:** hand a question to Claude or another model with your own API key, or to a model on your own computer,
  only when you ask or allow it.
- **Send feedback** writes a bug report or an idea for you to send yourself; Robot sends nothing on its own.

The full description of every feature, with its checks, is in [`robot/README.md`](robot/README.md).

## Privacy, in short

- Your voice is turned into text on the phone and never recorded. Answers are made on the phone.
- Nothing about you is collected. The people who make Robot receive nothing from your phone.
- Robot goes online only for what you start (a download, the weather, the nearest place, a web search, a helper) and for
  the daily update check, which you can turn off. You can turn off Robot's Network permission in GrapheneOS and
  everything on the phone keeps working.
- The nearest-place search uses OpenStreetMap's Nominatim server unless you pick another one in Settings › Actions ›
  Search server; you can switch servers there at any time without an update, or choose Never.
- Logs hold codes and numbers only, never what you said.

The full policy: [`PRIVACY.md`](PRIVACY.md).

## Models and licences

| Model | Publisher | Licence |
|---|---|---|
| Gemma 4 E2B (Fast; Tensor G5, Tensor G6 beta and general builds) | litert-community (Google) | Apache-2.0 |
| Gemma 4 E4B (Smarter, slower) | litert-community (Google) | Apache-2.0 |
| Gemma 4 E4B abliterated (Uncensored, slower) | olekk (community build, not from Google) | Apache-2.0, with its publisher's terms, which Robot asks you to accept before the download |
| Parakeet TDT 0.6B v3 (speech recognition) | NVIDIA, converted by the sherpa-onnx project | CC BY 4.0 |
| Kokoro 82M v1.0, Kitten TTS nano 0.8 (voices) | hexgrad, KittenML | Apache-2.0 |
| TitaNet-S (speakers) | NVIDIA | Apache-2.0 |
| Bergamot fr↔en (fast translation) | Mozilla | MPL-2.0 |
| Your own model file | you | its own licence (MedGemma, for example: Google's Health AI Developer Foundations terms, which you accept yourself on Hugging Face) |

The uncensored model has had its refusals removed; it is a community build, and what you do with it is your
responsibility. Robot is not a medical device.

Settings › About › Licences lists every part of Robot with its version, licence and full licence text, and
[`robot/THIRD_PARTY.md`](robot/THIRD_PARTY.md) lists them with their pins and hashes. Each release also attaches the
complete source of the native code Robot ships (sherpa-onnx with espeak-ng, ONNX Runtime, slimt and llama.cpp) as
`robot-<version>-third-party-sources.tar.gz`.

## Build from source

You need JDK 21 (or 17), the Android SDK (platform 36, build-tools 35.0.0, NDK 27.1.12297006, CMake 3.22.1) and Gradle
8.14.3.

```
cd robot
echo "sdk.dir=/path/to/android-sdk" > local.properties
tools/fetch_third_party.sh --install    # sherpa-onnx libraries, wake-word and VAD models, espeak-ng data, each checked by SHA-256
tools/build_robotmt.sh                  # librobotmt.so, the fast translation engine, from pinned sources
tools/build_llama.sh                    # optional: librobot_llama.so, for your own GGUF files
gradle :core:testReleaseUnitTest :voice:testReleaseUnitTest :claude:testReleaseUnitTest :app:testReleaseUnitTest
gradle :app:testDebugUnitTest           # Compose UI tests and screenshots (Robolectric, offline)
gradle :app:assembleRelease             # unsigned without a key: see robot/README.md, "Building"
tools/verify_apk.sh app/build/outputs/apk/release/app-release.apk
tools/repro_check.sh                    # builds the APK twice and compares them
```

Native libraries and models are never stored in git: the scripts fetch the exact pinned files and check them. Details,
and how a release is made, are in [`robot/README.md`](robot/README.md) ("Building", "Releasing").

## Contributing, security and licence

- How to build, test and send changes: [`CONTRIBUTING.md`](CONTRIBUTING.md). What changed in each version:
  [`CHANGELOG.md`](CHANGELOG.md).
- Found a security problem? Please report it privately: [`SECURITY.md`](SECURITY.md).
- Robot is Copyright (C) 2026 the Robot authors, and free software: you can redistribute it and/or modify it under the
  terms of the GNU General Public License version 3 ([`LICENSE`](LICENSE)). It comes with NO WARRANTY. Third-party parts
  keep their own licences ([`robot/THIRD_PARTY.md`](robot/THIRD_PARTY.md)).
