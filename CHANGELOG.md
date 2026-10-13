# Changelog

What changed in each version of Robot, newest first. Each version's full notes, with what was checked on a phone, are in
[`robot/README.md`](robot/README.md) ("What's new in …" and the feature sections). Versions before 1.0 were test
builds.

## Moving from 0.9 to 1.0

The full steps are in the [README](README.md#moving-from-09-to-10). In short: install 1.0 beside 0.9 (0.9 looks for updates in the old private repository, so it won't offer it: install 1.0 by hand from the release page); in 0.9 export People (Settings › People, notes
and calendar › People › More options › Export (.vcf)) and import that file in 1.0 (Import contacts file (.vcf)); export
Memory too (Settings › Memory › Export) and import it in 1.0 (Settings › Memory › Import, ticking "These are my own
memories" for your own file), then delete the file; let 1.0 download its models again, or import Robot's model file (the
model › Import a file); make 1.0 your digital assistant; uninstall 0.9; then do the ADB fixes again in 1.0. Settings,
History, notes, transcripts, keys and routines don't move: share or save what you want to keep first.

## 1.0.0 (unreleased)

Setup and updates:

- New app ID, `ai.robot_app` (after Robot's domain, robot-app.ai; it was `app.heyrobot`). 1.0 installs as a new app beside 0.9, which can't update to it. The ADB fixes and Robot's web requests name the new ID.
- Robot now checks for updates in the public repository, `picocryptfan/robot-assistant`. 0.9 looks in the old private one, so you install 1.0 by hand.
- A setup guide runs on first launch. It walks through each feature: what it does, what stays on the phone, what goes online, and where the setting is. It starts with ten examples of what Robot can do, checks your phone and offers its downloads. Nothing is turned on unless you choose it, and Android asks for a permission only when you tap Allow. Skip this step keeps the default. Finish later resumes from Home. Settings › About › Setup guide runs it again.
- Downloads and web requests name Robot and its version (`Robot/<version>`) instead of Android's default, which names your phone model. A download over 200 MB asks first whether to wait for Wi-Fi or download anyway.

Features:

- Ask a helper. Say "ask the helper …", "ask a helper …" or "ask my helper …" (« demande à l'assistant … », « demande à un assistant … ») and Robot asks the helper it checks with (Settings › Helpers › When Robot needs help › Ask). The old spoken phrase still works.
- Search in your browser with nothing to set up: "search DuckDuckGo for …" or « cherche sur Startpage … » opens the engine in your browser (Startpage by default, or Brave Search, Mojeek, Qwant, Ecosia, Google or Bing). Robot never reads the results. Web search also gains DuckDuckGo's Instant Answers and a short list of public SearXNG servers. Settings › Web search and the setup guide show every engine and service with a High, Medium or Low privacy level.
- Memory import. Settings › Memory › Import reads a memory export from 0.9 or 1.0 and shows what it would add before anything is written. Items you already have, and any that hold a password, a PIN, a code or a card number, are skipped. Imported items count as something Robot read, so actions they lead to ask first, until you say they are yours.
- Termux: a weekly check for Termux updates (Robot never downloads or installs it), Termux paused after an update until you trust that version, and the ADB fixes without a computer, through Shizuku. A command or argument holding one of Robot's own keys is refused.

Look and wording:

- New icon: a robot head shaped as a speech bubble, on blue, with a themed version and the same head in notifications. The robot on the panel shows whether it is listening, thinking or speaking.
- Explanations describe the kind of app ("your messaging app", "your maps app") instead of naming one. A name stays only on a row or step that checks, opens or sets up that app.
- Settings › Voice and the guide say once that Robot works in English and Québec French, and only writes down and reads other languages.
- The Home screen's corner-swipe tip no longer says Robot sees the screen on the lock screen.
- "Robot closed unexpectedly": after the app or the panel crashes or stops responding, Robot says so the next time you open it and offers Send feedback with a report of codes only.
- Accessibility: larger touch targets on transcript chips, the consent tick box as one TalkBack stop, the "More" menu drawn with a "more" icon, a higher-contrast "not ready" button, and the panel's answer read by TalkBack once it is complete when replies aren't spoken.
- Trademark marks: Settings › About and the setup guide's first page show Robot™, About ends with "Robot™ and Robot Assistant™ are trademarks of the Robot project.", and the README and these notes carry the same mark. The launcher name, spoken text and notifications are unchanged.

Security:

- Another app on screen can no longer open Robot's panel with instructions of its own.
- A conversation begun on the lock screen never comes back locked once you used it unlocked, and never steers your unlocked one. The next unlocked "hey robot" starts afresh, unless Robot asked you to unlock and you come back within 2 minutes. Then the locked turns stay as quoted information, never as your words. Continuing one from History shows its earlier turns as said while the phone was locked.
- A Termux command run through Shizuku's `rish` shows "Runs with ADB's rights through Shizuku" and runs only when you tap, never on a spoken yes.
- When you say "search in my browser for …", your words open without a card only in the engine you named, or your default. A search for "that dentist" or « ce resto » lets the model write the words and shows them on a card first.
- Termux's safety rules now apply to every call, and `git` left the list of commands that may skip the card.
- Your calendar read out in the brief and a note's text count as content Robot didn't write, so actions and searches they lead to ask first. Memory kept from something Robot read stays content when it comes back, and a message sender's name is never taken as your words.
- A "remember that …" Robot refused isn't kept in History.
- The brief's, Ready cards', routine summaries' and From your drive's notifications are hidden on the lock screen.
- Settings › Memory and a private answer can't be captured, and Memory's fields don't teach your keyboard.
- A web search names Wikipedia and Open Library on its card, its answer's "Sent to" line and History when they get your words.
- Your own `.litertlm` file is checked strictly before it is read, and importing one warns you first. The model process's replies have a size cap.
- On the locked phone nothing makes, deletes or lists a routine or adds a brief reminder.
- A contact's number keeps its "+" when dialled, and the locked car session calls only People and contacts.
- Unlocking the phone now reaches Robot (first-unlock routines, the brief finished at the unlock, car mode). Fake Clock alarms can no longer flood the voice log.

Building:

- The source bundle for the GPL code now also holds slimt and its parts, llama.cpp and KleidiAI, and Robot's own native sources. `tools/repro_check.sh` builds the APK twice and compares the two. Build-tools are pinned to 35.0.0.
- The public README, CONTRIBUTING, CHANGELOG, issue templates, PRIVACY and SECURITY.

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
