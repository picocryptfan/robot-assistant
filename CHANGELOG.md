# Changelog

What changed in each version of Robot, newest first. Each version's full notes, with what was checked on a phone, are in
[`robot/README.md`](robot/README.md) ("What's new in …" and the feature sections). Versions before 1.0 were test
builds.

## 1.1.1 (2026-10-23)

- During a phone lock, asking Robot to call an emergency number (typed, or said outside car mode) opens the phone's own
  emergency dialer with the number filled in; you tap Call. In 1.1.0 it answered "Unlock to do this". The same on
  Android's lock screen. When a call you typed opens a dialer, Robot's panel steps aside.
- Phone lock's edit session ends when you leave Robot by Home, Recents, the power button or another app, and at
  screen-off; a code pad left unfinished closes. During a lock nothing is put over Android's lock screen.

## 1.1.0 (2026-10-23)

Everything built since 1.0.1, in one release: Robot Files, the groundwork for other Android phones, Québec French,
Phone lock, Back up and restore, Skills, a few settings, fixes, web search working on phones again, and automatic
update checks that now run. It updates 1.0.1 in place.

- Robot Files, a private filing cabinet on the phone. The Files tab takes the Model tab's place (the model's page is now Settings › Model, at the top of Settings), and a second icon, Robot Files, opens it. Add a file from any app's share sheet (Robot Files), scan paper with the camera (several pages into one PDF, corners set by hand), say "file this" about a photo or the screen you showed Robot, or tap Save to Robot Files on a transcript. Robot reads each file on the phone (the text layer first, then pictures with its own model) and files it in a drawer: Receipts, Bills, Documents, Medical, Taxes, Trips and events, Photos and media, Conversations, or one of yours. A file it isn't sure about waits in Inbox with a question; a total it can't check against the subtotal and taxes is marked "Check the total"; a file that could install or run something stays in Inbox, unread, with a warning. After each filing: Undo and Move. Moves and spoken rules ("Esso receipts go under Car") become filing rules kept in Memory. Each file is encrypted with its own key, sealed by an Android Keystore key that works only while the phone is unlocked; nothing in Robot Files shows or is said on the lock screen. By voice: find a file, ask about one, spending by category, merchant or month (added up in code), bills due, returns, warranties and expiry dates, subscriptions, in English and French. Reminders from files go in your brief or, after a card you tap, your calendar. A tax pack (one year's tax slips and tax files) and an expense report (files tagged Business) export copies and a table of their details to a folder you pick; those copies aren't encrypted. Share and Open with another app hand over a one-use copy, without a photo's location while Strip location from photos I send is on (the default).
- Settings › Files: Robot Files on or off, a filing level for each kind (File it on its own, Ask me first, Off), Read text in images, Strip location from photos I send, reminders from files, the morning brief, clean-up suggestions (deleting always asks, with 10 seconds to undo), the backup reminder, and Delete them all.
- Travel mode (Settings › Files): the drawers you choose are hidden everywhere in Robot (Files, the panel, History, Memory's rules, the brief, backups) until you confirm with the phone's PIN, pattern or password, never a fingerprint or face. Shown drawers hide again after 10 minutes, when you leave Robot or when the screen goes off. A privacy screen, not encryption. Robot asks for USE_BIOMETRIC, one of its two new permissions, for that confirm screen.
- Backups carry your files: a Files row on Back up now's list (on by default, with Videos under it). Each file goes in inside the backup's encryption and is sealed again under the new phone's key on restore. Files from a backup that isn't yours arrive in Inbox without tags, people or reminders, and that backup can't turn Robot Files or reading text in images back on. While travel mode is on, backups and restores leave Robot Files out, and History too while it holds answers from your files.
- Other Android phones (none tested yet): Settings › Model, the problem report and the feedback report show the chip as Android names it, and name Snapdragon, Exynos and MediaTek chips. Robot checks the GPU (or a beta TPU build) with three short questions on the phone before your first question, once per Android build, and moves to the CPU only for a real fault. On the CPU, Robot reads back fewer earlier exchanges, and on a slow CPU gives its model shorter instructions. The setup guide and Settings › Model warn when answers will be slow or memory is tight, with buttons for what helps. Settings › Model shows speeds measured on your phone after 3 answers. The system voice is named from the phone's own text-to-speech engine; ADB fixes says "Not needed" for the recognizer fix when another recognizer is set; on a phone without the GrapheneOS Clock app, a routine that starts after your alarm says it won't start; and Phone lock keeps app stores and the phone's own file, care and wellbeing apps covered during a lock, whatever a lock lists.
- Web search works on phones again. In 1.0.x, one of Robot's patterns (keeping spelled-out e-mail addresses out of searches) had a bare `}` that Android's regex engine refuses, though the JVM the tests run on accepts it, so Robot's web search failed every time on a phone, and so did a browser search with words Robot's model wrote. A new test checks every regex in Robot for what Android's engine is known to refuse. Before 1.1.0 shipped, the phone found the same kind of pattern in Robot Files' reading; it's fixed, and the test knows that kind too. Robot's logs now name a failure's causes by their class, never their message.
- Automatic update checks run. In 1.0.x, Android refused to schedule the daily check for updates, the download on Wi-Fi and Termux's weekly check, so they never ran by themselves, though their switches showed On; only Check now worked. Android requires ACCESS_NETWORK_STATE before a scheduled job can wait for the network, and 1.0.x didn't declare it. 1.1.0 does: a normal permission, granted at install, that lets an app see whether the phone is online. Robot uses it for these jobs and nothing else. A phone on 1.0.x won't find 1.1.0 by itself: open Settings › About › Updates and tap Check now.
- Québec French. Settings › Language sets the menus (the phone's language, English or French) and the language Robot answers in. When you set that language, Robot's model is told to answer in it too; its answers can still drift, and a web answer is in English or French, picked from your question's words, whatever the setting. Screens, cards, notifications and what Robot says are in French, written with « tu »; the voice log and a bug report's diagnostic lines stay in English. Robot still works in English and Québec French only.
- Phone lock (Settings › Phone lock, off until you set it up). During a lock, every app but emergency calls, incoming calls, Clock and Robot is covered. Three kinds: Time of day, After the alarm (GrapheneOS Clock) and One app first, with a Daily limit. Locks are for you or for someone else, who needs a lock code to change them. Calls to the People you choose, Maps and a music app can stay open during a lock. The lock covers the dialer, so a call to one of those People goes from the call card's Call (with calls on Ask first, the default), with the Phone permission that lock's People row asks for; an emergency number you ask Robot for during a lock opens the phone's own emergency dialer, which the lock never covers, as the cover's Emergency call button does. You can also lock by voice ("lock the phone until 7", also in French). A second lock by voice never makes the one that's on end sooner, and during a lock for someone else nothing starts or changes one by voice: only the lock code does. If the phone's storage is full, locks keep working and a notification says Phone lock couldn't save its state. A passing Keystore or storage error never deletes the lock code, and a new code takes over only once it's saved and checked; until then the old one works. It runs in a small process of its own (`:lock`) and uses one Android accessibility service that sees only which app is in front, never what is on the screen. One app first and Daily limit also read Android's usage access, to count your time. It is a habit, not a wall: Settings › Phone lock › What a lock can't stop lists what it can't do at this strength, such as a restart in safe mode.
- Back up and restore (Settings › Back up and restore). Back up now saves one encrypted file, protected by a passphrase you choose or Robot generates, to a place you pick. You choose what goes in; keys and your voiceprint are off unless you tick them. Robot never keeps the passphrase and never uploads the file. Restore checks the whole file, shows a preview, and writes each category whole or not at all. A file that isn't your own restores with the safer settings: a helper you have never moves to another company, address or model maker, a new helper arrives off with no key, someone you confirmed in People never changes in Add to what's here (the default), Start from the lock screen stays off when it's off here, an update this phone hasn't skipped stays offered, and its brief reminders don't come back. A category that was empty when the preview opened keeps what you added since (not yet Phone lock's locks). Replace numbers the backup's headsets and cars after this phone's, so a routine here never waits for another device. A monthly reminder is off by default. The setup guide offers Restore from a backup on a new phone's first page.
- Skills (Settings › Skills; « compétence » in French). Teach Robot a short routine by voice ("teach a skill called heading home: text Sam I'm on my way, then open the route home") or say "save that as a skill" after Robot did the steps. Then say its name. Ask what a skill does, list your skills, or delete one. Each step still shows its own card, and skills never run on a locked phone.
- Robot's model picks skills (Settings › Skills › Let Robot's model pick skills, on by default). When your words fit up to 3 of your skills, Robot's model on the phone can pick one and fill its questions. If your words didn't name it, Robot asks "Did you mean …?" first. Values the model wrote are marked on the card, and you can change them. A skill that texts or emails someone shows its card until the model filled it right 9 of the last 10 times; a riskier one always asks for a tap. The model is told a skill's name, a one-line summary of its kinds of steps and its questions, never the steps or their fixed values. How often the model filled each skill right stays on the phone, isn't backed up and starts over after a restore; the switch is backed up with your skills.
- Security updates to two libraries Robot ships, Jackson (2.21.8) and jsoup (1.23.2), and a newer Android build toolchain. Material 3 stays at 1.3.2 on purpose, so Robot's look doesn't change.
- Wait before answering (Settings › Voice): how long Robot waits after you stop talking before it takes your question as finished. Short (0.6 s), Normal (1 s, the default), Long (1.6 s) or Very long (2.5 s), instead of a fixed half second. Holding the mic button keeps Robot listening until you let go, and the headset's and car mode's follow-up window waits for a question that is under way.
- Robot pauses your music or video while you ask, and starts it again after the answer: from the moment Robot listens until the answer is said or shown, you stop it or close the panel, nothing was heard, or a headset's follow-up window closes. If a turn hangs, the music comes back after a minute with nothing heard or said. A long answer read aloud pauses other sound too, instead of only lowering it. During a call nothing changes.
- Support Robot (Settings › About › Support Robot): Ko-fi, then five crypto addresses, each with Copy and a QR code Robot draws itself. It makes no network call until you tap a link.
- In French, Robot's two built-in models are called « Rapide » and « Plus futé », and Uncensored is « Sans censure ».
- Calendar events and calendar questions understand French dates and times (« vendredi à midi », « demain de 9 h à 10 h 30 », « lundi en huit », « la fin de semaine »), whatever language Robot answers in. Before, Robot read only English ones.
- Ask Robot to remind you on a date and it adds it to your calendar with an alert. It no longer sets an alarm you didn't give a time for, and for a timer, an alarm, an event or a note its reply is the card's own words, so it can't promise a day or time the card doesn't show. "Remind me to call the bank on the twentieth of November" (or « rappelle-moi d'appeler la banque le vingt novembre ») shows add_event's card, which always asks first and names the calendar set in Settings › People, notes and calendar › Calendar for new events: an all-day event with an alert at 9:00, or the event at the time you said with an alert then. An alarm runs only for a time you said, on the day it rings; for another day Robot offers the calendar reminder instead. After a timer, an alarm, an event or a note, Robot says what its card says in its own words. Dates like "the twentieth of November", "November 20th", "the 20th" and « le vingtième novembre » now read.
- "Remind me" or "remind me about it" (« rappelle-moi ça ») with nothing else, at the start of a conversation, makes Robot ask "What should I remind you about, and when?" instead of its model saving a note of your words.
- "Cancel my 6:30 alarm", "stop the timer" (« annule ma minuterie »): Android lets an app set alarms and timers, not cancel them, so Robot says it can't and offers your clock app on a card that only a tap opens. Before, the model could take the words as a request to set one.
- « Ajoute un film ce soir de 22 h à 1 h » set a one-minute timer. French parts of the day (« ce soir », « matin »), noon, midnight and times written « 22 h » now count as calendar words, so Robot's model gets the calendar's tools and can add the event. The model still picks the tool: when your words ask for an event or name a time range and it picks a timer or an alarm you didn't name, its card asks first.
- Esc on a hardware keyboard works like Back on Robot's panel: a press hides the on-screen keyboard if it's showing, or else closes the panel. Before, it left the panel unable to open again until Robot was force-stopped.
- Robot's voice says clock times right. In French, « 7 h 02 » is said « 7 heures 2 », « 12 h 30 » and the model's « 12:30 » or « 12h30 » « 12 heures 30 », « 1 h » « une heure », « 0 h 15 » « minuit 15 »; before, the voice read the « h » as a letter (« sept ache zéro deux ») and the colon as a decimal comma (« douze virgule trente »). In English, "7:02" is said "seven oh two" and "7:00" "seven o'clock", not "seven zero two" and "seven zero zero". The screen shows times as before.
- Bouncy Castle's Argon2 and BLAKE2b sources (MIT licence, listed on Settings › About › Licences) are copied into Robot for the backup file's passphrase; the rest of Bouncy Castle is not in the APK. Every source file of Robot's own now carries its GPL-3.0-only licence line.
- With Automatic help, Robot answers from its own notes first and asks the helper only when its answer is missing or unsure.
- Conversation mode: a paused transcript resumes only where a new one could start, so on the lock screen only with Start from the lock screen on, and never during a lock for someone else. The summary screen, Your voice and the Transcription check look at the phone's lock again at every button, and a summary or a share to a helper's app waits until you unlock. The summary screen sends nothing of a transcript deleted meanwhile, and shows its card again if the helper's settings changed since. Recents keeps no picture of a transcript or a summary.
- Replace names, on a helper's summary card, now covers every name you gave a speaker: earlier names, names from merged speakers, and each word of a full name. A name word is replaced wherever it appears in the transcript, even as an everyday word; a particle like « de » alone is left, and a word two speakers' names share becomes "someone".
- A reply you stopped in its 3 seconds stays a card: a tap sends it, or a spoken yes only with Answer reply cards by voice on. A reply card takes a yes or a no only while it's the last thing Robot asked about.
- A messaging address is taken for one of your People by its number only at the SMS gateway Robot texts through, for the panel's names, replies and the message quarantine. The same number at another server isn't that person there.
- A helper's card says your question goes with today's date and your country. "Free up memory" says how many recent apps are likely in memory and names them only on the card's buttons. Web search's consent names every recipient: the service, Wikipedia for a who, what or where question, Open Library for a question about books, and the result pages this phone may open. A place named for the weather goes to Open-Meteo only when it passes the nearest place's check (no address, number or long run of words). A routine that's off runs only after a tap on its card. An answer from Robot Files counts as personal: a helper gets it only through a card you tap, and a car or headset session treats it as personal from its first sentence.
- Fixes: when you cancel an action, Robot says nothing was done and removes any sentence that claims otherwise; the weather for a named place works with a day ("Paris tomorrow"); an email to someone not in your contacts isn't reported as drafted; memories you pinned count as personal for helpers; the panel's text field stays above the keyboard in landscape; a key Robot won't send over plain http says so; Settings › Memory shows what you change from the panel; after a refused message, a conversation with your own GGUF model goes on.
- PRIVACY.md describes Robot Files, travel mode, files in backups, the speed and GPU-check records kept on the phone, the accessibility service, usage access for screen time, backups, skills (and what Robot's model is told about them), the Support page, the two new permissions, the date and country a helper's first message carries, up to 5 result pages a web search may open (3 by default), that GitHub receives a feedback issue's text when its page opens (Send feedback's note says so too), and the Phone permission for a phone lock's call card; SECURITY.md covers Robot Files' encryption, its isolated reader, the share target, the accessibility service and backups.

## Moving from 0.9 to 1.0

The full steps are in the [README](README.md#moving-from-09-to-10). In short: install 1.0 beside 0.9 (0.9 looks for updates in the old private repository, so it won't offer it: install 1.0 by hand from the release page); in 0.9 export People (Settings › People, notes
and calendar › People › More options › Export (.vcf)) and import that file in 1.0 (Import contacts file (.vcf)); export
Memory too (Settings › Memory › Export) and import it in 1.0 (Settings › Memory › Import, ticking "These are my own
memories" for your own file), then delete the file; let 1.0 download its models again, or import Robot's model file (the
model › Import a file); make 1.0 your digital assistant; uninstall 0.9; then do the ADB fixes again in 1.0. Settings,
History, notes, transcripts, keys and routines don't move: share or save what you want to keep first.

## 1.0.1 (2026-10-15)

Fixes from my own security review after 1.0, and an APK that's easier to check. It updates 1.0.0 in place. After the
update, open Robot once: it moves its model into its own storage, and until it has, each model load takes 10 to 30
seconds longer.

- A key never goes over plain http. If a helper or your own search server has an http:// address and a key, Robot refuses before connecting and tells you to use an https address or remove the key. Your key stays saved. A server on your own network with no key works as before.
- Robot's model lives in its own private storage, which only Robot can write. A new download is copied in as it's checked. A model from 1.0 moves in once, the first time you open Robot after updating, when there's room for a second copy (the model's size plus 0.5 GB). Until it has moved, Robot checks the whole file before each load. Your own model file is imported the same way.
- After Robot reads something in a conversation (a web page, a helper's answer, the screen or a photo), every action that writes or opens something asks first, even when you asked for it. Text you didn't type could have chosen its details. Settings › Safety & privacy › Safety cards has the other choices.
- The headset button's way into Robot over the lock screen opens only for your headset, your car or Robot itself, never for another app.
- Termux's read-only commands: a `find` that follows links (`-L`, `-H`) shows its card first, since a link can lead out of your home folder.
- Your own GGUF model: a message that holds one of the model's control texts is refused before the model reads it. The rest of that conversation fails too; start a new one.
- The APK no longer carries Google's encrypted dependency block, and the llama.cpp library builds byte for byte the same in any folder. The source builds with a pinned Gradle (8.14.6), and every library it downloads is checked against a recorded SHA-256.
- Send feedback's "Open a GitHub issue" opens the bug report or idea form with your report filled in.

## 1.0.0 (2026-10-13)

Setup and updates:

- New app ID, `ai.robot_app` (after Robot's domain, robot-app.ai; it was `app.heyrobot`). 1.0 installs as a new app beside 0.9, which can't update to it. The ADB fixes and Robot's web requests name the new ID.
- Robot now checks for updates in the public repository, `picocryptfan/robot-assistant`. 0.9 looks in the old private one, so you install 1.0 by hand.
- A setup guide runs on first launch. It walks through each feature: what it does, what stays on the phone, what goes online, and where the setting is. It starts with ten examples of what Robot can do, checks your phone and offers its downloads. Nothing is turned on unless you choose it, and Android asks for a permission only when you tap Allow. Skip this step keeps the default. Finish later resumes from Home. Settings › About › Setup guide runs it again.
- Downloads and web requests name Robot and its version (`Robot/<version>`) instead of Android's default, which names your phone model. A download over 200 MB asks first whether to wait for Wi-Fi or download anyway.

Features:

- Ask a helper. Say "ask the helper …", "ask a helper …" or "ask my helper …" (« demande à l'assistant … », « demande à un assistant … ») and Robot asks the helper it checks with (Settings › Helpers › When Robot needs help › Ask). The old spoken phrase still works.
- Helpers work with any AI company. With your API key Robot asks it itself; without one, a helper whose company has an Android app opens that app with your question, and you press Send there. No helper is set up until you add one. Each helper answers to its own name, which you can change ("ask Jarvis …"). "Summarize with a stronger AI" can use any helper with an API key, with that helper's model, price and spending caps.
- Search in your browser with nothing to set up: "search DuckDuckGo for …" or « cherche sur Startpage … » opens the engine in your browser (Startpage by default, or Brave Search, Mojeek, Qwant, Ecosia, Google or Bing). Robot never reads the results. Web search also gains DuckDuckGo's Instant Answers and a short list of public SearXNG servers. Settings › Web search and the setup guide show every engine and service with a High, Medium or Low privacy level.
- Memory import. Settings › Memory › Import reads a memory export from 0.9 or 1.0 and shows what it would add before anything is written. Items you already have, and any that hold a password, a PIN, a code or a card number, are skipped. Imported items count as something Robot read, so actions they lead to ask first, until you say they are yours.
- Car mode doesn't hold or refuse anything while you drive, since a passenger may be the one asking. Opening an app, drafts, new texts, a helper's app and replies that need a tap work as they do outside the car, and a call or reply Robot can't finish by voice shows its card on the screen. Settings › Car mode › New texts while driving and the "From your drive" list are gone. Lock-screen rules and consent cards are the same as outside the car.
- Termux: a weekly check for Termux updates (Robot never downloads or installs it), Termux paused after an update until you trust that version, and the ADB fixes without a computer, through Shizuku. A command or argument holding one of Robot's own keys is refused.

Look and wording:

- The assistant panel always uses the classic look (round mic, chat bubbles), and History shows conversations the same way. The Classic look switch in Settings › Display is gone.
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
- The brief's, Ready cards' and routine summaries' notifications are hidden on the lock screen.
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
