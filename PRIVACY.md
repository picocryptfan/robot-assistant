# Robot's privacy policy

This policy applies to Robot 1.0.0 and later. Last changed 23 Oct 2026 (1.1: Robot Files and travel mode, files in backups, the speeds and GPU check kept on the phone, the chip in reports, Phone lock, backups, skills and what Robot's model is told about them, the Support page, the two new permissions, the date and country a helper's first message carries, up to 5 result pages for a web search, when GitHub receives a feedback issue, and the Phone permission during a phone lock).

## The short version

Robot does not collect anything about you. There is no account, no analytics, no ads, no crash reporting and no tracking.
I receive nothing from your phone. Robot goes online only for the things listed below, and only
when you start them, except the daily check for updates and, on Wi-Fi, the download of an update it found, and, once
you set up Termux, a weekly check for a newer Termux, which you can each turn off.

## What stays on your phone

- What you say. Robot listens for its wake words on the phone. Your voice is turned into text on the phone, kept in
  the phone's working memory (RAM) only, and never written to storage. No recording is made.
- What Robot answers. The AI model runs on the phone. Your questions and its answers don't leave the phone unless you
  ask a helper (below).
- What you keep. Your conversations (Settings › History › Save conversations, on by default; screenshots, photos and
  screen text only if you turn on "Keep screenshots and photos in history" in Settings › Safety & privacy), your notes,
  the people you add or let Robot learn, and your settings. They are kept in Robot's private storage on the phone. Robot
  turns off Android backup and device-to-device transfer, so nothing is copied to a cloud, and uninstalling Robot deletes
  them. You can delete conversations in Settings › History.
- What Robot reads for you. Your contacts and calendar (to text "Mom" or say what's on tomorrow), the screen when you
  ask about it, a photo you attach, your largest photos and videos when you ask it to free space: read on the phone, for
  the question you asked.
- What Robot remembers (Settings › Memory, on by default): only what you ask it to remember ("remember that …"),
  what you keep from a card, or what you import from a memory export. It is kept in one file encrypted again, beyond
  Android's own storage encryption, with a key in your phone's secure hardware (Android Keystore, AES-256-GCM) that
  works only while the phone is unlocked, so it can't be read on a locked phone, not even after the first unlock, unless
  you turn on "Use memory on the lock screen" (which lets it be read there, never changed: nothing is saved to or
  removed from memory on a locked phone). Something that looks like a password, a PIN, a code or a card number is kept
  as a private item: never spoken unless you allow it, never on the lock screen, never sent to a helper. You can see,
  edit, export and delete every item in Settings › Memory; Export writes a plain, unencrypted file to the place you
  pick, after a warning; Import reads only the export file you pick in Android's file picker, on an unlocked phone,
  shows what it would add before anything is written, keeps nothing of the file but the memories you import (encrypted
  as the rest), and then offers to delete it. History keeps which items an answer used (their ids), never their words.
  Nothing from a message becomes memory by itself.
- Your messages (off unless you turn on Settings › Messages, and then only for the apps you choose there): Robot
  reads the messages in those apps' notifications, through Android's notification access, to read them to you,
  summarize, translate or answer them. It needs no SMS permission and reads no message history. Their words stay in the
  phone's working memory (RAM) only, while the notification is there (or for the hour or day you choose), and are never
  written to storage: History keeps a line such as "Robot read 2 messages from Mom" (the name at most 40
  characters), and the voice log only numbers. The assistant's panel lets them go when it closes, or after 2 minutes
  without a question. Robot reads an app only while it is signed with the key it had when you turned it on (it keeps
  that key's fingerprint and the name of the store that installed it, nothing else about the app). Your choices, the
  people you let replies reach without a card (by an id, never a name) and those fingerprints are kept in a small
  settings file on the phone.
- Conversation transcripts (conversation mode, when you start it): Robot writes down a conversation, translates it
  and can summarize it, all on the phone. The audio is never kept; the transcript is kept in Robot's private storage
  until you delete it, and leaves the phone only if you share it or send it to a helper after its card (below). "Tell
  speakers apart" and "Your voice" keep voice fingerprints (lists of numbers, not audio) on the phone.
- Routines, headset and car mode (off until you set them up): your routines, the morning brief's settings, and the
  headsets and cars you add (their Bluetooth name and address, kept so Robot knows them again) stay in Robot's private
  storage. New messages you ask Robot to announce in the headset or the car are read aloud there, from memory, never
  stored.
- Termux (off until you set it up): your scripts' names and settings stay in Robot's settings, with the Termux you
  trusted (its signing key's fingerprint, version and install time, so Robot notices when it changes). A command Robot
  sends goes to the Termux app on the same phone, which runs it with Termux's own permissions; Robot itself sends
  nothing online for it (what your script or command does, including going online, is yours) except the weekly check
  for a newer Termux (9 below). The output is shown, cleaned of secrets; History never keeps a command. The ADB fixes
  Robot can send through Shizuku (Settings › Advanced › ADB fixes) go to Termux the same way, after your tap.
- Skills (1.1, Settings › Skills): the skills you teach or save, with their names, questions and steps (a step can hold
  a name or the words of a text you chose), are kept in `skills.json` in Robot's private storage, and in a backup if you
  include Skills. Robot's record of each skill's last run (`skills_state.json`: counts and times only) is never in a
  backup, a log, a bug report or sent anywhere, and the words of a skill never go in the voice log. A skill runs on the
  phone, never on a locked phone, and each step shows the card it would show on its own. With Settings › Skills › "Let
  Robot's model pick skills" (on by default), up to 3 of your skills that fit your words are described to Robot's model
  on the phone: each skill's name, a one-line summary of its kinds of steps ("Texts someone, then opens a route.") and
  its questions, never its steps or their fixed values (the person, the words of the text). Skills are never described
  to a helper. How often each model filled each skill right (the last 10 fills, right or wrong) is kept in
  `skills_state.json` too: on the phone, never backed up, logged or sent, and it starts over after a restore. The switch
  itself is kept in `skill_settings.json` and goes into a backup with your skills.
- Phone lock (1.1, off until you set it up): your locks, their times, the apps you let stay open, and a check of the
  lock code (not the code; its key is in your phone's secure hardware and never leaves it) stay in Robot's private
  storage. "What happened" lists when locks started and ended and anything Robot noticed, for 30 days, on the phone
  only and never in a backup. To cover apps during a lock, Phone lock uses an Android accessibility service. It receives
  only the event that an app came to the front, which gives it that app's name. It cannot read what is on the screen
  (Android is told it may not retrieve window content), cannot tap or type, and asks for no events at all while no lock
  is on or due within 10 minutes. The app names it sees are used on the phone to decide whether to cover the app; they
  are not written to "What happened" (which holds only the kinds of events, lock ids and numbers) and are not sent. One app first and Daily limit also read Android's usage access to count the time you spend in
  an app; that stays on the phone too (below).
- Backups (1.1, Settings › Back up and restore). Back up now writes one file to the place you choose in Android's file
  picker. The file is encrypted with a passphrase you choose or Robot generates (Argon2id, then AES-256-GCM). Robot
  never keeps the passphrase and never uploads the file: nobody can open it without the passphrase, Robot included. You
  choose what goes in. It can hold your settings (without keys), People, Memory, headset and car mode settings,
  routines, Phone lock's locks (not the lock code), skills, notes, History, transcripts, Robot Files (each file as it is,
  with the list of your files and Settings › Files; videos have their own row) and, only if you tick them,
  recordings, API keys with Memory's private items, and your voiceprint (biometric: it comes back only from a backup you
  mark as your own). It never holds the models, the updater's token or the apps Robot trusts. If the place you pick is a
  folder that another app syncs to a cloud, that app uploads the file; it stays encrypted. Restore reads a file you pick
  the same way and checks all of it before it changes anything; restored files are encrypted again under the new phone's
  key, and files from a backup that isn't yours arrive in Inbox. While travel mode is on, Robot Files stays out of
  backups and restores. A monthly reminder, if you turn it on, is a notification on the phone. Android's own backup
  stays off.
- Robot Files (1.1, Settings › Files; on, and empty until you add a file). The files you share to Robot Files, scan,
  file from the panel or save from a transcript are kept in Robot's private storage, each encrypted with its own key,
  sealed by a key in your phone's secure hardware (Android Keystore, AES-256-GCM) that works only while the phone is
  unlocked. The list of your files is encrypted the same way. It holds what Robot read from each file (up to 4,000
  characters of its text, and details such as a merchant, a date or a total) and where the file came from: how it came
  in, the app that shared it when Android confirms which, the time, its size and its SHA-256. Robot keeps a file as it
  came, with its location if it had one. It reads files on the phone, in an isolated process with no permissions, and
  with its own model; nothing about them goes online. Nothing in Robot Files shows or is said on the lock screen. The
  filing rules Robot learns from you ("Esso receipts go under Car") are kept in Memory, encrypted as the rest of it, and
  not kept at all while Memory is off. A file leaves the phone only when you share it, open it with another app or export
  it (5 below), or when you tap a card that sends what Robot read from it to a helper (4 below). Delete removes a file
  after 10 seconds you can undo; Settings › Files › Delete them all removes every file and its key. Copies stay in
  backups you made before. Robot Files never deletes a file by itself: clean-up suggestions are a list for your tap.
- Travel mode (1.1, Settings › Files, off by default). The drawers you choose are out of sight in Robot (Files, the
  panel, History, Memory's filing rules and its Export, the morning brief, notifications) until you confirm with your phone's
  PIN, pattern or password on Android's own screen; Robot never sees what you enter. While it's on, backups and restores
  leave Robot Files out, and History too while it holds answers from your files. Which drawers are hidden is kept in
  Robot's private storage without Robot's own encryption. It's a privacy screen, not encryption: the files stay
  encrypted as before, and anyone who knows your PIN can show them again.
- Measured speeds and the GPU check (1.1). To show how fast answers start on your phone, Robot keeps numbers only in
  `speeds.txt` (the reading and writing speeds of the last 20 answers per model, with the model file, where it ran and
  the Android build), and the result of its three-question check of the GPU in `gpu_check.txt`, in Robot's private
  storage. Robot reads them to show speeds in Settings › Model and to decide how much of a conversation its model reads
  back on a slow phone. They are never in a backup and never sent.
- Names learned from notifications (off unless you turn on Settings › People › "Learn names from message
  notifications"): who messages you in the one messaging app this works with today (the switch names it): name, address
  and account, never the messages.
- API keys you add for a helper, and the access token you may add for a private update
  repository (Settings › About › Updates › Advanced). They are encrypted with a key that never leaves your phone's secure
  hardware (Android Keystore, AES-GCM), and decrypted only when Robot calls that helper, or GitHub's API for the token.
  Since 1.0.1 a key goes only over https: for an http address with a key, Robot refuses before it connects and says why.
  Since 1.0 a helper's or web search key is also compared, on the phone, with a Termux command about to be sent, so one
  holding it is refused; the update token never is (a command holding a GitHub token is refused by its shape).
- Robot's diagnostic logs (Settings › Voice › Voice log, and Android's log) hold codes and numbers only, never what
  you said or what Robot answered. They stay on the phone; you can copy the Voice log yourself (wake phrases by number)
  or add it to a bug report (below).
- "Robot closed unexpectedly." When the app or the assistant panel crashed or stopped responding, Robot reads
  Android's own record of it (the reason, the time, and for a native crash the library names and offsets) when you next
  open it, and offers Send feedback. Nothing is sent unless you send it yourself.
- Model problem reports. When Robot's model fails or crashes, Robot keeps a note of it (codes and numbers: which
  model, where it ran, at which stage, the memory) and the last 10 of them, in Settings › Model. The report you can
  show there adds Robot's version, the phone, its chip as Android names it and its Android build, Android's record of how the model's process ended (for a
  native crash: the signal and the names of the libraries it was in, never memory contents or paths of yours) and the last
  50 voice log events. It stays on the phone; you may copy or share it, and Robot never sends it.
- Feedback (Settings › About › Send feedback). A bug report or an idea you write, with only what you tick added: the
  problem report, the last 60 voice log events, a summary of your settings' switches and levels, the updater's error
  codes. Each part has a Preview of exactly what it adds, and none of them holds your conversations or History,
  transcripts, messages or their senders, what Robot remembers, your files, notes, people or contacts, calendar, wake phrases' words,
  helpers' names, addresses or keys, the updater's repository or token, Wi-Fi or Bluetooth names, or where you are. Robot sends nothing: you send the message with
  the app you pick (below), or copy it (the clipboard copy is marked sensitive and cleared after a minute if nothing
  replaced it). The phone number you may set for "Text it" stays on the phone.

## When Robot goes online, and what is sent

1. Downloading a model or a voice, when you tap Download (Settings › Model, Voice or Conversation). The files come
   from Hugging Face (huggingface.co and its download servers), and the fast translation engine's from Mozilla's
   storage (storage.googleapis.com), through Android's download manager. They see your IP address and the request, whose
   User-Agent names only Robot and its version ("Robot/1.1.0"). Nothing you say or type is sent. Before a download over
   200 MB Robot asks whether to wait for Wi-Fi or download anyway. Robot checks every file against a fingerprint
   (SHA-256) before using it.
2. The weather, when you ask about it. Robot sends the place you named, or your approximate position (rounded to two
   decimals, about 1 km), to Open-Meteo (api.open-meteo.com and geocoding-api.open-meteo.com). Saving a "Home for
   weather" in Settings sends the place name you typed, once, to look it up. Open-Meteo says it keeps server logs, which
   can include coordinates, for up to 90 days. Robot never uses your precise or background location.
3. The nearest place ("directions to the nearest pharmacy"), only if you allow it: Robot asks the first time by
   default (Settings › Actions › Find the nearest place online). It sends the kind of place (a few words, never an
   address, a phone number or an email) and your area (about 1 km) to OpenStreetMap's Nominatim server
   (nominatim.openstreetmap.org), or to the Nominatim server you choose in Settings › Actions › Search server. The card
   names the server before anything goes. You can choose Never.
4. Helpers, only if you ask for one, or let Robot check a hard question with it (Settings › Helpers): a paid AI
   service with your own API key, or a model on your own computer:
   - With your own API key, Robot sends your question and the part of the conversation it shows you it will send to
     that company's API. That can include screen text or a photo you attached; personal content goes only with your tap
     unless you change that in Settings › Safety & privacy. The company handles it under its own terms and privacy
     policy, and you pay it directly. If you let the helper search the web, that company's search does that.
   - With a key or your own server, the first message of each conversation with a helper, and of a summary sent to one,
     also carries today's date and, when Robot knows it, your country: the Phone region in Settings › People, notes and
     calendar, or else the country of your SIM card, your mobile network or your language setting. The country only,
     never a town or a position.
   - Which AI service you use is your choice, and that company's own privacy policy covers what it receives. Read it
     before you add your key.
   - What Robot remembers goes to a helper only on a card that lists it and only with your tap, unless you choose
     Settings › Memory › "Share memory with helpers: With helpers I trust"; private items never go. Your messages go to a
     helper only on a card that lists them, with your tap, whatever you trust; a question Robot sends by itself goes
     without them. The same holds for the other helpers (any company's API, or a server on your network), each under that
     company's terms, or on your own server.
   - A conversation's summary goes to a helper only after its card shows what will be sent and you tap.
   - What Robot found in or read from your files (1.1) goes to a helper only on a card that lists it, with your tap,
     whatever you trust. A medical file, an ID, or a file that holds a password, a code or a card number is private:
     Robot shows its answer on the screen only, never reads it aloud, keeps a placeholder in History, and never sends it
     to a helper.
   - Without a key, the button that opens the service's own app only fills in that app (and may put your question on
     the clipboard); you press Send there.
5. Other apps you hand things to (your messaging app, your email app, your SMS or calendar app, your maps app, an AI service's
   own app). Robot fills in a draft or opens a route; that app sends something only when you tap Send, under its own
   policy.
   A reply to a message (Settings › Messages) is the exception you choose: Robot shows it on a card and, after you tap
   Send (or, for someone you chose in People, after 3 seconds in which you can say "stop"), hands your words to that
   app's own reply action, as Android Auto and watches do; the app sends them to that conversation, under its own policy
   (an SMS may cost what your carrier charges). With "Open the chat with my reply; I press Send" Robot never sends. "Mark
   as read", if you choose it, lets the app tell the sender you read the message. This
   is also how feedback leaves (Settings › About › Send feedback): Share hands the message to the app you pick in
   Android's share sheet (your SMS or messaging app…), "Text it" opens your SMS app with the number you set, and "Open a GitHub
   issue" opens GitHub's new issue page in your browser with the message filled in. The message is part of the page's
   address, so GitHub receives it as soon as the page opens, whether or not you submit the issue, and handles it under
   its own privacy statement. Saying "report a bug" or "I have an idea" only opens Send feedback.
   Robot Files (1.1) works the same way: Share and Open with another app hand that app a copy of the file, and Export
   (the tax pack, the expense report) writes copies and a table of their details to a folder you pick. These copies
   aren't encrypted. With Settings › Files › "Strip location from photos I send" (on by default), a photo goes without
   its location and other details; a video, or a picture Robot can't re-encode, asks before it goes as it is; other files
   go as they are. Robot deletes its own temporary copy when you come back to Robot, when the screen goes off or after 15
   minutes; the other app, or whatever syncs the folder you picked, may keep its copy.
6. Checking for updates (Settings › About › Updates): once a day while "Check for updates automatically" is on (the
   default), and when you tap Check now. Robot asks GitHub (api.github.com) for the newest releases of Robot's repository,
   or of the one you set in Advanced, and downloads an update through api.github.com from GitHub's servers
   (objects.githubusercontent.com, release-assets.githubusercontent.com, or github.com if GitHub sends it there) when you
   tap Download, or by itself on Wi-Fi if "Download updates on Wi-Fi" is on (the default). To GitHub: your IP address and
   Robot's version in the request ("Robot/1.1.0"); nothing else is sent. An access token, if you add one, goes to
   api.github.com only. GitHub handles the requests under its own privacy statement. Turn the automatic check off and
   Robot asks GitHub only when you tap Check now (and downloads only when you tap Download). In 1.0.x, Android refused
   to schedule the daily check and the download on Wi-Fi, so they never ran by themselves; 1.1 declares the permission
   they need (Network state, below).

7. Web search, only after you set it up (Settings › Web search, off by default) and agree to what it sends, and
   then only when you ask, or when Robot's model calls for current facts and your settings allow it. Robot sends your
   search words (with the question's language, the safe-search level and, for Brave, Kagi and Mojeek, your key) to the
   one service you chose: your own SearXNG, Brave Search, Kagi, Mojeek, a public SearXNG (one of a short list Robot
   shows, or one you type), DuckDuckGo's Instant Answers (1.0: short answers only; no page is read) or Wikipedia. With Encyclopedia
   lookups on (the default), a who, what or where question's words also go to Wikipedia, and with Book lookups on (off by
   default) a question about books also goes to Open Library; the search's card and its "Sent to" line name each of them.
   Robot's requests to these services name Robot and its contact address (hey@robot-app.ai), the same for everyone,
   never you or your phone. To answer, it may open up to
   5 of the result pages from the phone (3 by default; Settings › Web search › Pages per answer), like a browser without cookies: those sites see your IP address. What Robot
   remembers is never sent to a search service, and after a message every search waits for your tap. The service
   handles your words under its own policy (Brave says it keeps searches for up to 90 days). History keeps your words,
   the answer and the sources' domains, never the pages. Settings › Web search lists each service and engine with a
   privacy level (High, Medium or Low) made from its own policy. In 1.0.x, one of Robot's checks on the words failed on
   phones, so every web search stopped before anything was sent; 1.1 fixes the check, and what a search sends didn't
   change.

   A search in your browser (1.0, nothing to set up): when you say "search DuckDuckGo for …" or « cherche sur
   Startpage … », Robot opens your browser at that engine's own search page with your words (Startpage by default:
   Startpage, DuckDuckGo, Brave Search, Mojeek, Qwant, Ecosia, Google or Bing). Robot itself sends nothing and never sees
   the results: your browser sends your words, with its cookies, to the engine, which handles them under its own policy
   (Google and Bing keep searches with your IP address and use them for ads). When Robot's model wrote the words ("look
   it up on Google"), they pass the same check as a web search and a card shows them before anything opens; never on
   the lock screen.
8. The morning brief and routines go online only for the weather (Open-Meteo, as in 2) and a nearest-place step
   (Nominatim, as in 3), when a routine or the brief includes it; a routine's card names the service, and one that runs
   by itself goes online only while "Routines may go online by themselves" is on. A routine never searches the web,
   runs Termux or asks a helper.
9. Checking for Termux updates (Settings › Termux › "Check for Termux updates"): once Termux is set up (on by
   default then; the set-up says so), once a week, and when you tap Check now. Robot asks two addresses and nothing
   else, over HTTPS: GitHub (api.github.com) for Termux's newest releases, without any token, and F-Droid (f-droid.org)
   for Termux's entry. Each sees your IP address and Robot's version in the request ("Robot/1.1.0"), no cookie and
   nothing about you; the answers are compared on the phone with the Termux you have. Robot never downloads or installs
   Termux or any other app: it tells you to update from your F-Droid app. GitHub and F-Droid handle the requests under
   their own policies. Turn the switch off and Robot doesn't ask. In 1.0.x, Android refused to schedule this weekly
   check, so it ran only when you tapped Check now; 1.1 fixes that.

10. The Support page (Settings › About › Support Robot, 1.1). Opening it makes no network call. Tapping Ko-fi opens your
    browser at Ko-fi's page, where Ko-fi and your browser handle your visit under their own policies. The crypto
    addresses are text on the page, with Copy and a QR code Robot draws on the phone; Robot never sends anything about
    a payment and never learns whether you paid.

Nothing else goes online. You can turn off Robot's Network permission in GrapheneOS: everything on the phone keeps
working, and the online features above say they can't connect.

## Permissions, and why

Robot asks for the ones you can refuse only when you turn a feature on or tap Allow in the setup guide. The alarm and
network permissions are granted at install; GrapheneOS lets you turn the network off for Robot.

- Microphone: wake words and speech.
- Contacts: "text Mom", "what's Alex's number".
- Calendar: "what's on tomorrow", "add lunch with Sam on Friday".
- Approximate location: the weather "here" and the nearest place.
- Nearby devices (Bluetooth, asked for only from Settings › Headset or Car mode): to answer your headset's voice
  button and know your car when it connects. It doesn't allow scanning for devices.
- Phone calls (optional, asked for from Settings › Car mode › Calls, or from a phone lock's People row in 1.1): in car
  mode, to call after you said yes to the number Robot read back; during a phone lock, to call one of the People that
  lock lets you call when you tap Call on the call card, since the lock covers the dialer. Never an emergency number:
  during a lock, Robot opens the phone's own emergency dialer with it, which the lock never covers, and you tap Call
  there. Without the permission, every call opens the dialer and you tap Call there.
- Do Not Disturb access (optional, Settings › Car mode): only to switch Robot's own "Driving (Robot)" rule.
- Write secure settings (optional, and only if you grant it yourself with adb or Shizuku, in Settings › Advanced › ADB
  fixes): so Robot can keep its own recognizer set as your digital assistant after a restart. It changes that one setting
  and nothing else.
- Run commands in Termux (asked for only from Settings › Termux): so Robot can send the commands you approve to
  Termux.
- Photos and videos (or only the ones you pick): finding large files to free space. Deleting one always asks you
  again, in Android's own window.
- Notifications (Robot's own): that Android turned Robot off as the assistant after a restart or an update, a model
  problem, an update that is available or ready, and the one that shows while a conversation is transcribed or a
  routine speaks. With Phone lock: a warning before a lock starts and a quiet notice while one is on.
- Exact alarms (at install): one alarm 3 minutes after the phone restarts (or Robot updates), to check that Robot is
  still your digital assistant and tell you if Android turned it off, and, with Phone lock, one alarm for the next lock's
  warning, start or end. The timers and alarms you ask for are set by your
  Clock app, which Robot only asks (the "set an alarm" permission); Robot keeps no alarm of its own for them.
- Usage access (optional, in Android's settings): which apps use the most space, and, when you ask Robot to free up
  memory, which apps you used in the last few hours (read on the phone, for that answer only). With Phone lock's One
  app first or Daily limit (1.1), the time you spend in the app you chose and today's screen time, counted on the phone;
  only the running totals of minutes are kept, in Phone lock's own file, and nothing is sent.
- Accessibility access (optional, 1.1, Settings › Phone lock): so Phone lock can see which app comes to the front. It
  never reads the screen or taps anything. Android calls this a restricted setting for an app installed from a file,
  and says Robot can "view and control the screen" in its standard wording for every app with this access. Turn it off
  in Android's settings and Phone lock stops.
- Notification access (optional): learning names from your messaging app, and reading your messages from the apps you turn on in
  Settings › Messages (nothing from any other app is read).
- Network (at install): the online features above.
- Network state (at install, 1.1): lets an app see whether the phone is online. Android requires it before a scheduled
  job can wait for the network, as the daily update check, the download on Wi-Fi and Termux's weekly check do. Robot
  uses it for those jobs and nothing else.
- Biometric prompt (at install, 1.1): so travel mode can ask for your PIN, pattern or password on Android's own confirm
  screen. Robot accepts only those there, never a fingerprint or face, and never sees what you enter.
- Install unknown apps (optional, in Android's settings, which Settings › About › Updates opens): so Robot can install its own
  updates. It installs nothing else. "Install updates without asking" (off by default) uses a permission granted at
  install that lets Robot ask Android to skip its confirmation for Robot's own update.

## Children

Robot is not made for children.

## Changes and contact

Changes to this policy are listed in the release notes and in this file's history. Questions: hey@robot-app.ai.
