# Robot™

I made Robot because I couldn't find a complete voice assistant that doesn't come from Google or Apple.

Robot is a private voice assistant for GrapheneOS on Google Pixel phones. I start with Pixels because GrapheneOS runs
on them, and the point is to keep Google off the phone. Robot's AI model runs on the phone, so what you say and what it
answers stay there. Robot goes online for what you ask: the weather, a web search you set up, a question you hand to
an online helper with your own key, or a download. It also checks for its own updates once a day and downloads them on
Wi-Fi, checks for Termux updates once a week after you set Termux up, and lets routines go online if you allow it.
Each can be turned off: Settings › About › Updates (Check for updates automatically, Download updates on Wi-Fi),
Settings › Termux (Check for Termux updates), Settings › Routines (Routines may go online by themselves).
Say "hey robot", or swipe up from a bottom corner, and ask.
With "hey robot" on, Robot used about 3% of the battery overnight, and the first word of an answer
comes after about 0.25 s (both measured on a Pixel 10 Pro).

Robot is free software under the GNU GPL version 3 (GPL-3.0-only). There is no account, no analytics, no ads and no
crash reporting.

## Phones

| Phone | Status |
|---|---|
| Pixel 10, 10 Pro, 10 Pro XL and 10 Pro Fold (Tensor G5) with GrapheneOS | Robot's fast model (Gemma 4 E2B) runs on the Tensor G5's TPU. I test on the Pixel 10 Pro; the others have the same chip, and I haven't tried them. |
| Other Pixels (Pixel 6 to 9 and their a models, the 10a, Fold and Tablet: Tensor G1 to G4) | The same model runs on the CPU or GPU. It works, more slowly. I've tested these phones less. The 10a has a Tensor G4, so it is in this row. |
| Pixel 11 (Tensor G6) | The TPU build is in beta. |
| Snapdragon and other Android phones | Planned, after the Pixels. Other Android builds are untested for now. |

Robot needs Android 14 or later, arm64 only. I build and test it on GrapheneOS.

## Install

1. Download the APK from this repository's [GitHub Releases](https://github.com/picocryptfan/robot-assistant/releases)
   (`Robot-<version>.apk`), or add the repository to [Obtainium](https://github.com/ImranR98/Obtainium) (Add App, paste
   `https://github.com/picocryptfan/robot-assistant`) so it tells you about new releases. Robot can also check for
   its own updates (Settings › About › Updates).
2. Check the signing certificate before you install the first time. Its SHA-256 fingerprint is:

   ```
   40:25:57:A4:6A:49:17:50:68:8A:3D:FF:71:41:00:FE:1D:20:B2:F6:11:93:A9:12:35:8C:2E:DD:34:1A:6B:6A
   ```

   On a computer: `apksigner verify --print-certs Robot-<version>.apk` ("Signer #1 certificate SHA-256 digest", without
   the colons). Obtainium and AppVerifier can check it on the phone. Android refuses any later update signed with
   another key, and so does Robot's own updater.
3. Make Robot your digital assistant when it asks (Settings › Apps › Default apps › Digital assistant app). The
   corner swipe and "hey robot" then open it, also from the lock screen. Android drops Robot as your assistant at the
   next restart unless you also do Settings › Advanced › ADB fixes › "Stay the assistant after a restart".
4. Download what you need, once, from the app's first screen. Wi-Fi is best: Robot warns before any download over
   200 MB and lets you wait for Wi-Fi.

   | What | Size | Needed for |
   |---|---|---|
   | Robot's model, Fast (Gemma 4 E2B) | 2.6–3.3 GB, by phone | answering |
   | Speech recognition (NVIDIA Parakeet TDT 0.6B v3) | 670 MB | talking to Robot |
   | Voice (Kokoro 82M) | 354 MB | spoken replies (or a 60 MB fallback voice, or your phone's own voice) |
   | Fast translation engine (Mozilla Bergamot fr↔en) | 52 MB | optional: conversation mode's quick translations |
   | Speaker model (NVIDIA TitaNet-S) | 40 MB | optional: telling speakers apart in transcripts |

   Every file comes from a pinned commit and is checked against its SHA-256 before Robot uses it.

## Verify the APK

Three checks, each proving something different. None replaces the others.

| Check | What it proves | What it doesn't |
|---|---|---|
| The signing certificate: `apksigner verify --print-certs Robot-<version>.apk` shows the fingerprint above | The APK is signed with the key that signed every Robot release, and Android and Robot's updater refuse an update signed with another key. | That the code is the code in this repository. |
| In the folder with your downloads: `sha256sum -c --ignore-missing SHA256SUMS` | The APK is the file the release lists, and the download isn't damaged. | `SHA256SUMS` comes from the same release, so it doesn't protect you from someone who can publish to the repository. The signing key does that. |
| In `robot/`: `tools/repro_check.sh --native` | This repository's code builds the same unsigned APK twice on your machine, byte for byte, native libraries included: `--native` rebuilds them from their pinned sources in each build and compares them too (without it, both builds use the libraries already in your checkout). With `--signed Robot-<version>.apk` it also copies the release's signature onto your build and compares the two (needs apksigcopier), which ties the published APK to the code. | That the build is reproducible on another machine, or that the libraries it pins are free of bugs. |

Coming from 0.9? Robot 1.0 installs beside it under a new app ID, and you move your data by hand: see
[`MIGRATING-0.9.md`](MIGRATING-0.9.md).

## Features

- Robot answers by voice or text, on the phone, and about a photo or the screen you're on (once the phone is
  unlocked). You can choose a smarter, slower model, or your own `.litertlm` or `.gguf` file.
- It sets timers and alarms. It drafts texts and emails: it writes the text, you press Send. It also handles notes,
  your calendar and contacts, directions in your maps app, the weather (Open-Meteo), the nearest place of a kind
  (OpenStreetMap), your phone's storage, battery and memory, and freeing space. Robot asks first when an action
  needs it, and every rule about when it asks is a switch in Settings › Safety & privacy.
- It remembers what you ask it to, encrypted on the phone.
- It reads, summarizes, translates and answers your messages from the apps you choose. A reply
  goes only after you tap Send.
- Conversation mode transcribes a conversation in English and French, on the phone, translates it (also face to
  face, aloud) and summarizes it.
- Robot works in English and Québec French. It can also write down and read other languages, but it answers in English or French.
- Routines and a morning brief. Headset mode, where your headset's button opens Robot, and car mode.
- Web search uses the service you choose: your own SearXNG, Brave, Kagi, Mojeek, DuckDuckGo's Instant Answers or a
  public SearXNG. It is off until you set it up, and answers name their sources. With nothing to set up, you can
  search in your browser with Startpage, DuckDuckGo, Google and five others, each listed with its privacy level.
- Termux runs your own scripts and short commands. You approve each command on a card first, and it is off until you
  set it up.
- Helpers take a question to a paid AI service with your own API key, to another AI's app on your phone (you press
  Send there), or to a model on your own computer. None is set up until you add one, and they do that only when you
  ask or allow it.
- Send feedback writes a bug report or an idea for you to send yourself. Robot sends nothing on its own.

My development notes, with the design, the checks and the history, are in [`robot/README.md`](robot/README.md).

## Privacy

- Your voice is turned into text on the phone and never recorded. Answers are made on the phone.
- Nothing about you is collected. I receive nothing from your phone.
- Robot goes online for what you start (a download, the weather, the nearest place, a web search, a helper), for the
  daily update check and its download on Wi-Fi, for the weekly Termux check once Termux is set up, and for routines
  you allow to; each of those can be turned off. You can turn off Robot's Network permission in GrapheneOS and
  everything on the phone keeps working.
- The nearest-place search uses OpenStreetMap's Nominatim server unless you pick another one in Settings › Actions ›
  Search server. You can switch servers there at any time without an update, or choose Never.
- Logs hold codes and numbers only, never what you said.

The full policy is in [`PRIVACY.md`](PRIVACY.md).

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

One limit to know: a `.litertlm` file you import yourself runs with Robot's own permissions (LiteRT-LM reads it inside
Robot's model process, which needs the GPU and the TPU), unlike a `.gguf` file, which runs in an isolated process with
none. Robot checks the file's layout strictly before LiteRT-LM reads it, but not the model inside, and warns you before
you pick one: only import files you trust. See [`SECURITY.md`](SECURITY.md).

Settings › About › Licences lists every part of Robot with its version, licence and full licence text, and
[`robot/THIRD_PARTY.md`](robot/THIRD_PARTY.md) lists them with their pins and hashes. Each release also attaches the
complete source of the copyleft native code Robot ships (sherpa-onnx with espeak-ng, ONNX Runtime, slimt and llama.cpp)
as `robot-<version>-third-party-sources.tar.gz`. LiteRT-LM's native libraries come as Google's prebuilt files, not as source.

## Build from source

You need JDK 21 (or 17), the Android SDK (platform 36, build-tools 35.0.0, NDK 27.1.12297006, CMake 3.22.1) and Gradle
8.14.3.

```
cd robot
echo "sdk.dir=/path/to/android-sdk" > local.properties
tools/fetch_third_party.sh --install    # sherpa-onnx libraries, wake-word and VAD models, espeak-ng data, each checked by SHA-256
tools/build_robotmt.sh                  # librobotmt.so, the fast translation engine, from pinned sources
tools/build_llama.sh                    # optional: librobot_llama.so, for your own GGUF files
gradle :core:testReleaseUnitTest :voice:testReleaseUnitTest :helpers:testReleaseUnitTest :app:testReleaseUnitTest
gradle :app:testDebugUnitTest           # Compose UI tests and screenshots (Robolectric, offline)
gradle :app:assembleRelease             # unsigned without a key: see robot/README.md, "Building"
tools/verify_apk.sh app/build/outputs/apk/release/app-release.apk
tools/repro_check.sh                    # builds the APK twice and compares them
```

Native libraries and models are never stored in git: the scripts fetch the exact pinned files and check them. Details,
and how a release is made, are in [`robot/README.md`](robot/README.md) ("Building", "Releasing").

## Support Robot

Robot is free, and I make it alone. If it's useful to you, you can support it on Ko-fi, once or monthly, or with one
of the addresses below. Either way, you get the same app as everyone else.

- Ko-fi: https://ko-fi.com/picocryptfan
- Bitcoin: `bc1qq9qe5ckwju6dkjddmf2vpfrcu7cakjw7ld2gtk`
- Bitcoin: `sp1qqf85ltcy8al2flnwm2v8kszm2rplcs8a6xa6uw5rpfqmy7sa7srr6qafj6jwd3jj2fcmq8agna23syl6vjwzpjjg5yhwalyqm7d7ydxjhvqtgt0v`
- Lightning: `dopeycheese1839@cake.cash`
- Monero: `46t4rJYd7XgXeCPG24izVW3FwKPgrmhg74E6GMuT8VUG68ZJvCnDQLHaMsHGzT6YTJiVwUqA1Qd9yMKkWw96jDXXTHaBaQm`
- Ethereum: `0x8245306C33c92630Af30aCFc542307dbD17f47D1`

The same list is on [robot-app.ai](https://robot-app.ai/#donate).

## Contributing, security and licence

- How to build, test and send changes is in [`CONTRIBUTING.md`](CONTRIBUTING.md). What changed in each version is in
  [`CHANGELOG.md`](CHANGELOG.md).
- If you find a security problem, please report it privately: [`SECURITY.md`](SECURITY.md).
- Robot™ and Robot Assistant™ are trademarks of the Robot project.
- Robot is Copyright (C) 2026 the Robot authors, and free software: you can redistribute it and/or modify it under the
  terms of the GNU General Public License version 3 ([`LICENSE`](LICENSE)). It comes with NO WARRANTY. Third-party parts
  keep their own licences ([`robot/THIRD_PARTY.md`](robot/THIRD_PARTY.md)).
