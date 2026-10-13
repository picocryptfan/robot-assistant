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

1. **Download** the APK from this repository's [GitHub Releases](https://github.com/picocryptfan/robot-assistant/releases)
   (`Robot-<version>.apk`), or add the repository to [Obtainium](https://github.com/ImranR98/Obtainium) (Add App, paste
   `https://github.com/picocryptfan/robot-assistant`) so it tells you about new releases. Robot can also check for
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

## Moving from 0.9 to 1.0

Robot 1.0 has a new app ID, `ai.robot_app` (0.9 and the test builds before it were `app.heyrobot`), so Android installs
it as a new app beside 0.9, not as an update, and nothing you have in 0.9 moves by itself. 0.9's own updater can't
install it: it downloads 1.0, says "The downloaded app isn't Robot, so it won't be installed." and then stops offering
it. Both apps are called Robot; long-press an icon › App info shows which version it is.

1. **Install 1.0** from the [release page](https://github.com/picocryptfan/robot-assistant/releases), beside 0.9,
   and check its certificate as above: it is the same key.
2. **People.** In 0.9: Settings › People, notes and calendar › People › More options (⋮) › Export (.vcf), and save
   `Robot People.vcf`. In 1.0: the same page › More options › Import contacts file (.vcf), pick that file, then Import.
   It holds the people you confirmed, with their names, nicknames, numbers, emails and XMPP addresses. People Robot
   learned from Cheogram that you haven't confirmed, and which number or address Robot uses first for someone, don't
   come along.
3. **Memory.** In 0.9: Settings › Memory › Export › Choose a file, and save `robot-memory.json` (it isn't encrypted).
   In 1.0, unlocked: Settings › Memory › Import, pick that file, and check the preview: what it adds, what is already
   there, and what it skips (a password, a PIN, a code or a card number is never imported: add those yourself with
   Settings › Memory › Add something, where they stay private). Tick "These are my own memories, from my own export"
   when it is your own file, then tap Import. Without the tick, Robot treats the imported items as something it read
   (an action they lead to asks first) until you open one in Settings › Memory and tap This is mine. Then let Robot
   delete the file (it offers to), or delete it yourself.
4. **Models.** 1.0 downloads its models again, from its first screen (or the Model tab, Settings › Voice and Settings ›
   Conversation). Robot's model can come from a file you already have instead: the Model tab › the model › Import a file
   copies it and checks its SHA-256 as a download is. The copy 0.9 downloaded is in 0.9's own folder, which the file
   picker can't open; with adb you can copy it out first (`adb shell ls /sdcard/Android/data/app.heyrobot/files/`, then
   `adb shell cp /sdcard/Android/data/app.heyrobot/files/<file> /sdcard/Download/`), import it, and delete the copy in
   Download afterwards. The voices, speech recognition and the translation engine always download again.
5. **Make 1.0 your digital assistant**: Settings › Apps › Default apps › Digital assistant app, and pick Robot. Both are
   listed as Robot: in 1.0, Settings › Assistant setup says "Android lists Robot as your assistant" once you picked the
   right one; if it doesn't, pick the other.
6. **Uninstall 0.9**: long-press its icon › App info (it says version 0.9.0) › Uninstall. This deletes everything 0.9
   kept.
7. **Do the ADB fixes again** in 1.0 (Settings › Advanced › ADB fixes), with a computer or with Shizuku: 0.9's were for
   its app ID, so both show "Not done", and their commands now name `ai.robot_app`. Until then Android may stop using
   Robot as the assistant after a restart.

What doesn't move: your settings, History, notes, transcripts, the keys of your helpers and web search, routines,
headsets and cars, the Termux set-up, the updater's access token, your voiceprint, and Android's permissions and special
accesses (Robot asks again as you turn things on). Before step 6, share the notes you want to keep (a note › Share) and
save the transcripts you want (a transcript › More options › Save as Markdown or Save as text). If Obtainium follows
Robot for you, it follows 0.9's app ID: remove that entry and add the repository again.

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
- **Web search** with the service you choose (your own SearXNG, Brave, Kagi, Mojeek, DuckDuckGo's Instant Answers or a
  public SearXNG), off until you set it up; answers name their sources. Or, with nothing to set up, a search in your
  browser with Startpage, DuckDuckGo, Google and five others, each listed with its privacy level.
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

Known limitation: a `.litertlm` file you import yourself runs with Robot's own permissions (LiteRT-LM reads it inside
Robot's model process, which needs the GPU and the TPU), unlike a `.gguf` file, which runs in an isolated process with
none. Robot checks the file's layout strictly before LiteRT-LM reads it, but not the model inside, and warns you before
you pick one: only import files you trust. See [`SECURITY.md`](SECURITY.md).

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
