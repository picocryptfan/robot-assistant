# Changelog

What changed in each version of Robot, newest first. Each version's full notes, with what was checked on a phone, are in
[`robot/README.md`](robot/README.md) ("What's new in …" and the feature sections). Versions before 1.0 were test
builds.

## Unreleased (1.0 hardening)

- Downloads name Robot and its version (`User-Agent: Robot/<version>`) instead of Android's default, which names the
  phone model; a download over 200 MB asks first whether to wait for Wi-Fi or download anyway, since Robot can't see
  whether you're on mobile data.
- "Robot closed unexpectedly": after the app or the assistant panel crashes or stops responding, Robot says so the next
  time you open it and offers Send feedback with a report of codes only. Debug builds run with StrictMode.
- Accessibility: larger touch targets on transcript chips, the consent tick box as one TalkBack stop, the "More" menu
  drawn with a "more" icon, a higher-contrast "not ready" screen button, and the panel's answer read by TalkBack once it
  is complete when replies aren't spoken.
- The third-party source bundle now also holds slimt and its parts, llama.cpp and KleidiAI, and Robot's own native
  sources; `tools/repro_check.sh` builds the APK twice and compares; build-tools are pinned to 35.0.0.
- The Home screen's corner-swipe tip no longer says Robot sees the screen on the lock screen.
- Public README, CONTRIBUTING, CHANGELOG, issue templates; PRIVACY and SECURITY brought up to date.
- **Setup guide**: on first launch (and once more whenever a new version of the guide comes), a guide walks through
  each feature: what it does, what stays on the phone and what goes online, and whether you want it, with the current
  setting chosen and where to find it later in Settings. It starts with what Robot can do (ten examples), checks this
  phone and offers its downloads, and ends with what's on and off now. Skip this step keeps the default; Finish later
  resumes from Home ("Continue the setup guide"); Settings › About › Setup guide and Home run it again. Nothing is turned
  on unless you choose it, and Android asks for a permission only when you tap Allow.

## 0.9.0

- **Web search**, off until you set it up: one service you choose (your own SearXNG, Brave, Kagi, Mojeek or a public
  SearXNG), "search the web for …", answers from up to three result pages with numbered sources. Every query is checked
  before it leaves; what Robot remembers never goes to a search service.
- **Termux agent**, off until you set it up: "run backup" runs your script in `~/robot`; a short command the model writes
  is shown on a Run / Edit / Cancel card first, runs under a time limit, and its output is cleaned of secrets.
- Web answers and command output count as content Robot didn't write: later actions ask first, and after a message every
  search and command is a card only a tap answers. Routines never search or run Termux.
- Tool groups give way so a question always keeps room in the model's context.

## 0.8.0

- **Routines and a morning brief**: fixed lists of steps that start at a time, when you turn off an alarm, at the first
  unlock after a time, when your headset or car connects, when charging starts, or when you ask; the brief says the
  time, the weather, today's calendar and your reminders.
- **Headset mode**: your headset's voice button opens Robot, which listens and answers through the headset.
- **Car mode**: when your car connects over Bluetooth (once you added it), Robot talks through the car with big buttons
  on the screen and short answers; what needs the screen waits until you park.
- **New messages, announced** in the headset and the car, when you turn that on.
- Pixel 11 (Tensor G6) TPU build in beta; a smaller APK (compressed dex files).

## 0.7.0

- **Memory**: "remember that …", "forget that", "what do you know about me?", kept encrypted on the phone; private items
  (passwords, codes) are never spoken without your say-so or sent to a helper.
- **Messages**: reads, summarizes, translates and answers the messages of the apps you choose, from their
  notifications, in memory only; a reply goes after you tap Send.
- Nothing from a message becomes memory by itself.
- **Send feedback**: a bug report or an idea, with only the diagnostics you tick, sent by you through the app you pick.
- Model problems: the last 10, with a report you can copy or share, including a native crash's tombstone as codes.

## 0.6.0

- **Conversation mode**: transcribes a conversation in French and English on the phone, with speaker labels.
- **Fast translation engine** (Mozilla Bergamot fr↔en, run by slimt), the interpreter (face to face, aloud) and summaries.
- **Helpers**: Ask Claude became Settings › Helpers: Claude, any company's API with your key, or a model on your own
  computer.
- **Updates**: Settings › About › Updates finds Robot's new releases on GitHub and installs them after checking each one.
- Engine hardening: llama.cpp in its own isolated process.

## 0.5.0

Built conversation mode and the fast translation engine; released as part of 0.6.0.

## 0.4.7

- Fast on every phone: the general Gemma 4 E2B build on the CPU or GPU for Pixels without a Tensor G5 (Pixel 9a and
  others); Settings › Model shows the phone and its chip.

## 0.4.6

- Fixes from the 0.4.4 phone tests: the interrupt sensitivity is saved as tapped, "stop" ends speech reliably, and more.

## 0.4.5

- **GGUF models**: your own `.gguf` file, run by llama.cpp on the CPU; "Let the model think first" switch.
- **Settings › About › Licences**: every part of Robot, its licence and full text; the source links.
- A choice of Nominatim server for the nearest place; the uncensored model's terms to accept before its download.
- The look began to move to stock Android (Material 3), with the classic look kept as an option.

## 0.4.4 and 0.4.3

- Short-context models (2,048 to 4,095 tokens), such as MedGemma, run as chat only.
- 0.4.4: test build of the GGUF work.

## 0.4.2 and 0.4.1

- **Model choice**: Fast stays the default; Smarter (Gemma 4 E4B), Uncensored, or your own model file, each falling
  back to Fast with a reason when it isn't ready.

## 0.4.0

- **Weather** (Open-Meteo), **phone status** (storage, battery, memory) and **freeing space**.
- **The nearest place** online (OpenStreetMap's Nominatim), for Organic Maps' route, asked first by default.
- **Safety & privacy**: every rule about when Robot asks first is a switch.
- **People**: Robot's own list, so "text Mom" works for someone only in Cheogram or Proton Mail.

## 0.3.1 and 0.3.0

- **Ask Claude**: hand a question to Claude through the API with your own key, or open it in the Claude app, only when
  you ask or allow it.

## 0.2.3

- Voice reliability, wake phrases of your own, and texting fixes; the voice log.

## 0.2.0 to 0.2.2

- Drafts texts (Cheogram) and emails (Proton Mail), keeps and finds notes, reads and adds calendar events, looks up
  contacts; Cheogram-only people.

## 0.1.x

- The first daily-usable Robot: "hey robot" and the corner swipe, speech recognition (Parakeet) and spoken replies
  (Kokoro) on the phone, Gemma 4 E2B on the Tensor G5's TPU.
