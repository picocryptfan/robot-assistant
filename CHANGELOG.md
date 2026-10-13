# Changelog

What changed in each version of Robot, newest first. Each version's full notes, with what was checked on a phone, are in
[`robot/README.md`](robot/README.md) ("What's new in …" and the feature sections). Versions before 1.0 were test
builds.

## Moving from 0.9 to 1.0

The full steps are in the [README](README.md#moving-from-09-to-10). In short: install 1.0 beside 0.9 (0.9's updater
refuses it: "The downloaded app isn't Robot, so it won't be installed."); in 0.9 export People (Settings › People, notes
and calendar › People › More options › Export (.vcf)) and import that file in 1.0 (Import contacts file (.vcf)); export
Memory too (Settings › Memory › Export) and import it in 1.0 (Settings › Memory › Import, ticking "These are my own
memories" for your own file), then delete the file; let 1.0 download its models again, or import Robot's model file (the
model › Import a file); make 1.0 your digital assistant; uninstall 0.9; then do the ADB fixes again in 1.0. Settings,
History, notes, transcripts, keys and routines don't move: share or save what you want to keep first.

## 1.0.0 (unreleased)

- **Fixed after rc1** (a security review of everything changed since the 1.0 audit):
  - a Termux command run through Shizuku's `rish` shows "Runs with ADB's rights through Shizuku" and runs only when
    you tap, never on a spoken yes;
  - continuing from History a conversation begun on the lock screen shows its earlier turns as said while the phone was
    locked, so they can't act as your own words;
  - when you say "search in my browser for …", your words open without a card only in the engine you named, or your
    default.
- **New app ID, `ai.robot_app`** (after Robot's domain, robot-app.ai; it was `app.heyrobot`): 1.0 installs as a new app
  beside 0.9, which can't update to it. The ADB fixes and Robot's web requests name the new ID.
- Downloads name Robot and its version (`User-Agent: Robot/<version>`) instead of Android's default, which names the
  phone model, and every other request that names Robot gives the same version (`Robot/1.0.0-rc1`, not `1.0.0rc1`); a
  download over 200 MB asks first whether to wait for Wi-Fi or download anyway, since Robot can't see whether you're on
  mobile data.
- "Robot closed unexpectedly": after the app or the assistant panel crashes or stops responding, Robot says so the next
  time you open it and offers Send feedback with a report of codes only. Debug builds run with StrictMode.
- Accessibility: larger touch targets on transcript chips, the consent tick box as one TalkBack stop, the "More" menu
  drawn with a "more" icon, a higher-contrast "not ready" screen button, and the panel's answer read by TalkBack once it
  is complete when replies aren't spoken.
- The third-party source bundle now also holds slimt and its parts, llama.cpp and KleidiAI, and Robot's own native
  sources; `tools/repro_check.sh` builds the APK twice and compares; build-tools are pinned to 35.0.0.
- **"Ask Claude" is now "Ask a helper".** Say "ask the helper …", "ask a helper …" or "ask my helper …" (« demande à
  l'assistant … », « demande à un assistant … ») and Robot asks the helper it checks with (Settings › Helpers › When
  Robot needs help › Ask). "Ask Claude …" still works when spoken; the app no longer shows it.
- The Home screen's corner-swipe tip no longer says Robot sees the screen on the lock screen.
- Public README, CONTRIBUTING, CHANGELOG, issue templates; PRIVACY and SECURITY brought up to date.
- **Setup guide**: on first launch (and once more whenever a new version of the guide comes), a guide walks through
  each feature: what it does, what stays on the phone and what goes online, and whether you want it, with the current
  setting chosen and where to find it later in Settings. It starts with what Robot can do (ten examples), checks this
  phone and offers its downloads, and ends with what's on and off now. Skip this step keeps the default; Finish later
  resumes from Home ("Continue the setup guide"); Settings › About › Setup guide and Home run it again. Nothing is turned
  on unless you choose it, and Android asks for a permission only when you tap Allow. Turning Messages on there asks
  which apps first, as Settings › Messages does, and never turns it on with no app.

- **Memory import**: Settings › Memory › Import reads a memory export from 0.9 or 1.0 and shows what it would add before
  anything is written. Items you already have, and any that hold a password, a PIN, a code or a card number, are
  skipped; private items stay private; and imported items count as something Robot read (actions they lead to ask first)
  until you say they are yours, on the preview or item by item ("This is mine").

- **Termux**: a weekly check for Termux updates (Termux's GitHub releases and F-Droid's entry; Robot never downloads or
  installs it); after an update Termux is paused until you trust that version; the GitHub build, signed with a public
  test key, runs nothing unless you choose "Run anyway", and Settings › Termux shows how to switch to F-Droid as soon as
  it sees that build; a command or argument holding one of Robot's own keys is refused; the ADB fixes can be done
  without a computer, with Shizuku's `rish` in Termux.

- **The lock screen's turns**: a conversation begun on the lock screen never comes back locked once you used it
  unlocked, and never steers your unlocked one: the next unlocked "hey robot" starts afresh, unless Robot asked you to
  unlock and you come back within 2 minutes, when the locked turns stay as quoted information, never as your words.

- **Fixes from the 1.0 security audit**:
  - Another app on screen can no longer open Robot's panel with instructions of its own.
  - Unlocking the phone now reaches Robot (first-unlock routines, the brief finished at the unlock, car mode).
  - Fake Clock alarms can no longer flood the voice log.
  - Termux's safety rules now apply to every call, and `git` left the list of commands that may skip the card.
  - Your calendar read out in the brief and a note's text count as content Robot didn't write, so actions and searches
    they lead to ask first.
  - Memory kept from something Robot read stays content when it comes back.
  - A message sender's name is never taken as your words.
  - A "remember that …" Robot refused isn't kept in History.
  - The brief's, Ready cards', routine summaries' and From your drive's notifications are hidden on the lock screen.
  - Settings › Memory and a private answer can't be captured, and Memory's fields don't teach your keyboard.
  - A web search names Wikipedia and Open Library on its card, its answer's "Sent to" line and History when they get
    your words; "search the web for who is …" counts as a who question for Wikipedia.
  - Your own `.litertlm` file is checked strictly before it is read, and importing one warns you first.
  - The model process's replies have a size cap.
  - On the locked phone nothing makes, deletes or lists a routine or adds a brief reminder (said in French, Robot
    answers in French).
  - A contact's number keeps its "+" when dialled, and the locked car session calls only People and contacts.
  - A search in your browser for "that dentist" or « ce resto » lets the model write the words and shows them on a card
    first.

- **Search engines with their privacy levels**: "search DuckDuckGo for …", « cherche sur Startpage … » open the engine
  in your browser (Startpage by default; also Brave Search, Mojeek, Qwant, Ecosia, Google, Bing), with nothing to set
  up; Robot never reads their results. Web search gains DuckDuckGo's free Instant Answers and a short list of public
  SearXNG servers. Settings › Web search and the setup guide list every engine and service with a High, Medium or Low
  level made from its own policy.

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
- **Helpers**: the paid AI helper became Settings › Helpers: any company's API with your key, or a model on your own
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

- **Paid AI helper**: hand a question to a paid AI service through its API with your own key, or open it in that
  service's app, only when you ask or allow it.

## 0.2.3

- Voice reliability, wake phrases of your own, and texting fixes; the voice log.

## 0.2.0 to 0.2.2

- Drafts texts (Cheogram) and emails (Proton Mail), keeps and finds notes, reads and adds calendar events, looks up
  contacts; Cheogram-only people.

## 0.1.x

- The first daily-usable Robot: "hey robot" and the corner swipe, speech recognition (Parakeet) and spoken replies
  (Kokoro) on the phone, Gemma 4 E2B on the Tensor G5's TPU.
