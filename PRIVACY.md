# Robot's privacy policy

> **Draft for review.** Brought up to date with Robot 0.9.0 and the 1.0 hardening (conversation mode, helpers, memory,
> messages, routines, headset and car mode, web search, Termux and its update check, the updater and feedback).

## The short version

Robot does not collect anything about you. There is no account, no analytics, no ads, no crash reporting and no tracking.
The people who make Robot receive nothing from your phone. Robot goes online only for the things listed below, and only
when you start them, except the daily check for updates and, on Wi-Fi, the download of an update it found, and, once
you set up Termux, a weekly check for a newer Termux, which you can each turn off.

## What stays on your phone

- **What you say.** Robot listens for its wake words on the phone. Your voice is turned into text on the phone, kept in
  the phone's working memory (RAM) only, and never written to storage. No recording is made.
- **What Robot answers.** The AI model runs on the phone. Your questions and its answers don't leave the phone unless you
  ask a helper (below).
- **What you keep.** Your conversations (Settings › History › Save conversations, on by default; screenshots, photos and
  screen text only if you turn on "Keep screenshots and photos in history" in Settings › Safety & privacy), your notes,
  the people you add or let Robot learn, and your settings. They are kept in Robot's private storage on the phone. Robot
  turns off Android backup and device-to-device transfer, so nothing is copied to a cloud, and uninstalling Robot deletes
  them. You can delete conversations in Settings › History.
- **What Robot reads for you.** Your contacts and calendar (to text "Mom" or say what's on tomorrow), the screen when you
  ask about it, a photo you attach, your largest photos and videos when you ask it to free space: read on the phone, for
  the question you asked.
- **What Robot remembers** (Settings › Memory, on by default): only what you ask it to remember ("remember that …"), or
  what you keep from a card. It is kept in one file encrypted again, beyond Android's own storage encryption, with a key
  in your phone's secure hardware (Android Keystore, AES-256-GCM) that works only while the phone is unlocked, so it can't
  be read on a locked phone, not even after the first unlock, unless you turn on "Use memory on the lock screen" (which
  lets it be read there, never changed: nothing is saved to or removed from memory on a locked phone). Something that
  looks like a password, a PIN, a code or a card number is kept as a private item: never spoken unless you allow it,
  never on the lock screen, never sent to a helper. You can see, edit, export and delete every item in Settings › Memory;
  Export writes a plain, unencrypted file to the place you pick, after a warning. History keeps which items an answer
  used (their ids), never their words. Nothing from a message becomes memory by itself.
- **Your messages** (off unless you turn on Settings › Messages, and then only for the apps you choose there): Robot
  reads the messages in those apps' notifications, through Android's notification access, to read them to you,
  summarize, translate or answer them. It needs no SMS permission and reads no message history. Their words stay in the
  phone's working memory (RAM) only, while the notification is there (or for the hour or day you choose), and are never
  written to storage: History keeps a line such as "Robot read 2 messages from Mom (Cheogram)" (the name at most 40
  characters), and the voice log only numbers. The assistant's panel lets them go when it closes, or after 2 minutes
  without a question. Robot reads an app only while it is signed with the key it had when you turned it on (it keeps
  that key's fingerprint and the name of the store that installed it, nothing else about the app). Your choices, the
  people you let replies reach without a card (by an id, never a name) and those fingerprints are kept in a small
  settings file on the phone.
- **Conversation transcripts** (conversation mode, when you start it): Robot writes down a conversation, translates it
  and can summarize it, all on the phone. The audio is never kept; the transcript is kept in Robot's private storage
  until you delete it, and leaves the phone only if you share it or send it to a helper after its card (below). "Tell
  speakers apart" and "Your voice" keep voice fingerprints (lists of numbers, not audio) on the phone.
- **Routines, headset and car mode** (off until you set them up): your routines, the morning brief's settings, and the
  headsets and cars you add (their Bluetooth name and address, kept so Robot knows them again) stay in Robot's private
  storage. New messages you ask Robot to announce in the headset or the car are read aloud there, from memory, never
  stored.
- **Termux** (off until you set it up): your scripts' names and settings stay in Robot's settings, with the Termux you
  trusted (its signing key's fingerprint, version and install time, so Robot notices when it changes). A command Robot
  sends goes to the Termux app on the same phone, which runs it with Termux's own permissions; Robot itself sends
  nothing online for it (what your script or command does, including going online, is yours) except the weekly check
  for a newer Termux (9 below). The output is shown, cleaned of secrets; History never keeps a command. The ADB fixes
  Robot can send through Shizuku (Settings › Advanced › ADB fixes) go to Termux the same way, after your tap.
- **Names learned from Cheogram** (off unless you turn on Settings › People › "Learn names from Cheogram
  notifications"): who messages you in Cheogram (name, address, account), never the messages.
- **API keys** you add for Claude (or another helper), and the **access token** you may add for a private update
  repository (Settings › About › Updates › Advanced). They are encrypted with a key that never leaves your phone's secure
  hardware (Android Keystore, AES-GCM), and decrypted only when Robot calls that helper, or GitHub's API for the token.
  Since 1.0 a helper's or web search key is also compared, on the phone, with a Termux command about to be sent, so one
  holding it is refused; the update token never is (a command holding a GitHub token is refused by its shape).
- **Robot's diagnostic logs** (Settings › Voice › Voice log, and Android's log) hold codes and numbers only, never what
  you said or what Robot answered. They stay on the phone; you can copy the Voice log yourself (wake phrases by number)
  or add it to a bug report (below).
- **"Robot closed unexpectedly."** When the app or the assistant panel crashed or stopped responding, Robot reads
  Android's own record of it (the reason, the time, and for a native crash the library names and offsets) when you next
  open it, and offers Send feedback. Nothing is sent unless you send it yourself.
- **Model problem reports.** When Robot's model fails or crashes, Robot keeps a note of it (codes and numbers: which
  model, where it ran, at which stage, the memory) and the last 10 of them, on the Model tab. The report you can show
  there adds Robot's version, the phone and its Android build, Android's record of how the model's process ended (for a
  native crash: the signal and the names of the libraries it was in, never memory contents or paths of yours) and the last
  50 voice log events. It stays on the phone; you may copy or share it, and Robot never sends it.
- **Feedback** (Settings › About › Send feedback). A bug report or an idea you write, with only what you tick added: the
  problem report, the last 60 voice log events, a summary of your settings' switches and levels, the updater's error
  codes. Each part has a Preview of exactly what it adds, and none of them holds your conversations or History,
  transcripts, messages or their senders, what Robot remembers, notes, people or contacts, calendar, wake phrases' words,
  helpers' names, addresses or keys, the updater's repository or token, Wi-Fi or Bluetooth names, or where you are. Robot sends nothing: you send the message with
  the app you pick (below), or copy it (the clipboard copy is marked sensitive and cleared after a minute if nothing
  replaced it). The phone number you may set for "Text it" stays on the phone.

## When Robot goes online, and what is sent

1. **Downloading a model or a voice**, when you tap Download (Settings › Model, Voice or Conversation). The files come
   from Hugging Face (huggingface.co and its download servers), and the fast translation engine's from Mozilla's
   storage (storage.googleapis.com), through Android's download manager. They see your IP address and the request, whose
   User-Agent names only Robot and its version ("Robot/1.0.0"). Nothing you say or type is sent. Before a download over
   200 MB Robot asks whether to wait for Wi-Fi or download anyway. Robot checks every file against a fingerprint
   (SHA-256) before using it.
2. **The weather**, when you ask about it. Robot sends the place you named, or your approximate position (rounded to two
   decimals, about 1 km), to Open-Meteo (api.open-meteo.com and geocoding-api.open-meteo.com). Saving a "Home for
   weather" in Settings sends the place name you typed, once, to look it up. Open-Meteo says it keeps server logs, which
   can include coordinates, for up to 90 days. Robot never uses your precise or background location.
3. **The nearest place** ("directions to the nearest pharmacy"), only if you allow it: Robot asks the first time by
   default (Settings › Actions › Find the nearest place online). It sends the kind of place (a few words, never an
   address, a phone number or an email) and your area (about 1 km) to OpenStreetMap's Nominatim server
   (nominatim.openstreetmap.org), or to the Nominatim server you choose in Settings › Actions › Search server. The card
   names the server before anything goes. You can choose Never.
4. **Claude and the other helpers**, only if you ask for one, or let Robot check a hard question with it (Settings ›
   Helpers): Claude, another company's model with your own API key, or a model on your own computer:
   - With your own API key, Robot sends your question and the part of the conversation it shows you it will send to
     Anthropic's API (api.anthropic.com). That can include screen text or a photo you attached; personal content goes
     only with your tap unless you change that in Settings › Safety & privacy. Anthropic handles it under its own terms
     and privacy policy, and you pay Anthropic directly. If you let Claude search the web, Anthropic's search does that.
   - What Robot remembers goes to a helper only on a card that lists it and only with your tap, unless you choose
     Settings › Memory › "Share memory with helpers: With helpers I trust"; private items never go. Your messages go to a
     helper only on a card that lists them, with your tap, whatever you trust; a question Robot sends by itself goes
     alone. The same holds for the other helpers (any company's API, or a server on your network), each under that
     company's terms, or on your own server.
   - A conversation's summary goes to a helper only after its card shows what will be sent and you tap.
   - Without a key, "Open in the Claude app" only fills in the Claude app (and may put your question on the clipboard);
     you press Send there.
5. **Other apps you hand things to** (Cheogram, Proton Mail, your SMS or calendar app, Organic Maps, the Claude app).
   Robot fills in a draft or opens a route; that app sends something only when you tap Send, under its own policy. A
   **reply to a message** (Settings › Messages) is the exception you choose: Robot shows it on a card and, after you tap
   Send (or, for someone you chose in People, after 3 seconds in which you can say "stop"), hands your words to that
   app's own reply action, as Android Auto and watches do; the app sends them to that conversation, under its own policy
   (an SMS may cost what your carrier charges). With "Open the chat with my reply; I press Send" Robot never sends. "Mark
   as read", if you choose it, lets the app tell the sender you read the message. This
   is also how feedback leaves (Settings › About › Send feedback): Share hands the message to the app you pick in
   Android's share sheet (SMS, Signal, Molly…), "Text it" opens your SMS app with the number you set, and "Open a GitHub
   issue" opens GitHub's new issue page in your browser with the message filled in (GitHub gets it only if you submit it
   there, under its own privacy statement). Saying "report a bug" or "I have an idea" only opens that page.
6. **Checking for updates** (Settings › About › Updates): once a day while "Check for updates automatically" is on (the
   default), and when you tap Check now. Robot asks GitHub (api.github.com) for the newest releases of Robot's repository,
   or of the one you set in Advanced, and downloads an update through api.github.com from GitHub's servers
   (objects.githubusercontent.com, release-assets.githubusercontent.com, or github.com if GitHub sends it there) when you
   tap Download, or by itself on Wi-Fi if "Download updates on Wi-Fi" is on (the default). To GitHub: your IP address and
   Robot's version in the request ("Robot/0.6.0"); nothing else is sent. An access token, if you add one, goes to
   api.github.com only. GitHub handles the requests under its own privacy statement. Turn the automatic check off and
   Robot asks GitHub only when you tap Check now (and downloads only when you tap Download).

7. **Web search**, only after you set it up (Settings › Web search, off by default) and agree to what it sends, and
   then only when you ask, or when Robot's model calls for current facts and your settings allow it. Robot sends your
   search words (with the question's language, the safe-search level and, for Brave, Kagi and Mojeek, your key) to the
   one service you chose: your own SearXNG, Brave Search, Kagi, Mojeek, a public SearXNG or Wikipedia. With Encyclopedia
   lookups on (the default), a who, what or where question's words also go to Wikipedia, and with Book lookups on (off by
   default) a question about books also goes to Open Library; the search's card and its "Sent to" line name each of them.
   Robot's requests to these services name Robot and its contact address (hey@robot-app.ai), the same for everyone,
   never you or your phone. To answer, it may open up to
   3 of the result pages from the phone, like a browser without cookies: those sites see your IP address. What Robot
   remembers is never sent to a search service, and after a message every search waits for your tap. The service
   handles your words under its own policy (Brave says it keeps searches for up to 90 days). History keeps your words,
   the answer and the sources' domains, never the pages.
8. **The morning brief and routines** go online only for the weather (Open-Meteo, as in 2) and a nearest-place step
   (Nominatim, as in 3), when a routine or the brief includes it; a routine's card names the service, and one that runs
   by itself goes online only while "Routines may go online by themselves" is on. A routine never searches the web,
   runs Termux or asks a helper.
9. **Checking for Termux updates** (Settings › Termux › "Check for Termux updates"): once Termux is set up (on by
   default then; the set-up says so), once a week, and when you tap Check now. Robot asks two addresses and nothing
   else, over HTTPS: GitHub (api.github.com) for Termux's newest releases, without any token, and F-Droid (f-droid.org)
   for Termux's entry. Each sees your IP address and Robot's version in the request ("Robot/1.0.0"), no cookie and
   nothing about you; the answers are compared on the phone with the Termux you have. Robot never downloads or installs
   Termux or any other app: it tells you to update from your F-Droid app. GitHub and F-Droid handle the requests under
   their own policies. Turn the switch off and Robot doesn't ask.

Nothing else goes online. You can turn off Robot's Network permission in GrapheneOS: everything on the phone keeps
working, and the online features above say they can't connect.

## Permissions, and why

Robot asks for the ones you can refuse when you turn on or first use the feature, never at first launch. The alarm and
network permissions are granted at install; GrapheneOS lets you turn the network off for Robot.

- **Microphone:** wake words and speech.
- **Contacts:** "text Mom", "what's Alex's number".
- **Calendar:** "what's on tomorrow", "add lunch with Sam on Friday".
- **Approximate location:** the weather "here" and the nearest place.
- **Nearby devices** (Bluetooth, asked for only from Settings › Headset or Car mode): to answer your headset's voice
  button and know your car when it connects. It doesn't allow scanning for devices.
- **Phone calls** (optional, Settings › Car mode › Calls): to call after you said yes to the number Robot read back,
  never an emergency number. Without it, every call opens the dialer and you tap Call.
- **Do Not Disturb access** (optional, Settings › Car mode): only to switch Robot's own "Driving (Robot)" rule.
- **Run commands in Termux** (asked for only from Settings › Termux): so Robot can send the commands you approve to
  Termux.
- **Photos and videos** (or only the ones you pick): finding large files to free space. Deleting one always asks you
  again, in Android's own window.
- **Notifications:** Robot's own: that Android turned Robot off as the assistant after a restart or an update, a model
  problem, an update that is available or ready, and the one that shows while a conversation is transcribed or a
  routine speaks.
- **Exact alarms** (at install): one alarm 3 minutes after the phone restarts (or Robot updates), to check that Robot is
  still your digital assistant and tell you if Android turned it off. The timers and alarms you ask for are set by your
  Clock app, which Robot only asks (the "set an alarm" permission); Robot keeps no alarm of its own for them.
- **Usage access** (optional, in Android's settings): which apps use the most space, and, when you ask Robot to free up
  memory, which apps you used in the last few hours (read on the phone, for that answer only).
- **Notification access** (optional): learning names from Cheogram, and reading your messages from the apps you turn on in
  Settings › Messages (nothing from any other app is read).
- **Network** (at install): the online features above.
- **Install unknown apps** (optional, in Android's settings, which Settings › About › Updates opens): so Robot can install its own
  updates. It installs nothing else. "Install updates without asking" (off by default) uses a permission granted at
  install that lets Robot ask Android to skip its confirmation for Robot's own update.

## Children

Robot is not made for children.

## Changes and contact

Changes to this policy are listed in the release notes and in this file's history. Questions: hey@robot-app.ai.
