# Robot's privacy policy

> **Draft for review.** Written against Robot 0.4.3's code (the `feature/compliance` branch). Re-check it before
> publishing: 0.6.0 adds what it doesn't cover yet: conversation mode and its transcripts (kept on the phone), the fast
> translation engine's model download (Mozilla's storage.googleapis.com bucket), the speaker model's (huggingface.co), a
> summary sent to Claude after its card, and the other helpers (any company's API, or a server on your own network).
> 0.7's memory, messages, model problem reports and feedback are covered below. Replace CONTACT with a real address.

## The short version

Robot does not collect anything about you. There is no account, no analytics, no ads, no crash reporting and no tracking.
The people who make Robot receive nothing from your phone. Robot goes online only for the things listed below, and only
when you start them, except the daily check for updates and, on Wi-Fi, the download of an update it found, which you
can turn off.

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
- **Names learned from Cheogram** (off unless you turn on Settings › People › "Learn names from Cheogram
  notifications"): who messages you in Cheogram (name, address, account), never the messages.
- **API keys** you add for Claude (or another helper), and the **access token** you may add for a private update
  repository (Settings › About › Updates › Advanced). They are encrypted with a key that never leaves your phone's secure
  hardware (Android Keystore, AES-GCM), and decrypted only when Robot calls that helper, or GitHub's API for the token.
- **Robot's diagnostic logs** (Settings › Voice › Voice log, and Android's log) hold codes and numbers only, never what
  you said or what Robot answered. They stay on the phone; you can copy the Voice log yourself (wake phrases by number)
  or add it to a bug report (below).
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

1. **Downloading a model or a voice**, when you tap Download in Settings › Model. The files come from Hugging Face
   (huggingface.co and its download servers), through Android's download manager. Hugging Face sees your IP address and
   the request, including the User-Agent Android's download manager sends (it names the Android version and the phone
   model). Nothing you say or type is sent. Robot checks every file against a fingerprint (SHA-256) before using it.
2. **The weather**, when you ask about it. Robot sends the place you named, or your approximate position (rounded to two
   decimals, about 1 km), to Open-Meteo (api.open-meteo.com and geocoding-api.open-meteo.com). Saving a "Home for
   weather" in Settings sends the place name you typed, once, to look it up. Open-Meteo says it keeps server logs, which
   can include coordinates, for up to 90 days. Robot never uses your precise or background location.
3. **The nearest place** ("directions to the nearest pharmacy"), only if you allow it: Robot asks the first time by
   default (Settings › Actions › Find the nearest place online). It sends the kind of place (a few words, never an
   address, a phone number or an email) and your area (about 1 km) to OpenStreetMap's Nominatim server
   (nominatim.openstreetmap.org), or to the Nominatim server you choose in Settings › Actions › Search server. The card
   names the server before anything goes. You can choose Never.
4. **Claude**, only if you ask for it, or let Robot check a hard question with it (Settings › Ask Claude):
   - With your own API key, Robot sends your question and the part of the conversation it shows you it will send to
     Anthropic's API (api.anthropic.com). That can include screen text or a photo you attached; personal content goes
     only with your tap unless you change that in Settings › Safety & privacy. Anthropic handles it under its own terms
     and privacy policy, and you pay Anthropic directly. If you let Claude search the web, Anthropic's search does that.
   - What Robot remembers goes to a helper only on a card that lists it and only with your tap, unless you choose
     Settings › Memory › "Share memory with helpers: With helpers I trust"; private items never go. Your messages go to a
     helper only on a card that lists them, with your tap, whatever you trust; a question Robot sends by itself goes
     alone. The same holds for the other helpers (any company's API, or a server on your network).
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

Nothing else goes online. You can turn off Robot's Network permission in GrapheneOS: everything on the phone keeps
working, and the online features above say they can't connect.

## Permissions, and why

Robot asks for the ones you can refuse when you turn on or first use the feature, never at first launch. The alarm and
network permissions are granted at install; GrapheneOS lets you turn the network off for Robot.

- **Microphone:** wake words and speech.
- **Contacts:** "text Mom", "what's Alex's number".
- **Calendar:** "what's on tomorrow", "add lunch with Sam on Friday".
- **Approximate location:** the weather "here" and the nearest place.
- **Photos and videos** (or only the ones you pick): finding large files to free space. Deleting one always asks you
  again, in Android's own window.
- **Notifications:** Robot's own: that Android turned Robot off as the assistant after a restart or an update, a model
  problem, an update that is available or ready, and the one that shows while a conversation is transcribed.
- **Exact alarms** (at install): one alarm 3 minutes after the phone restarts (or Robot updates), to check that Robot is
  still your digital assistant and tell you if Android turned it off. The timers and alarms you ask for are set by your
  Clock app, which Robot only asks (the "set an alarm" permission); Robot keeps no alarm of its own for them.
- **Usage access** (optional, in Android's settings): which apps use the most space.
- **Notification access** (optional): learning names from Cheogram, and reading your messages from the apps you turn on in
  Settings › Messages (nothing from any other app is read).
- **Network** (at install): the online features above.
- **Install unknown apps** (optional, in Android's settings, which Settings › About › Updates opens): so Robot can install its own
  updates. It installs nothing else. "Install updates without asking" (off by default) uses a permission granted at
  install that lets Robot ask Android to skip its confirmation for Robot's own update.

## Children

Robot is not made for children.

## Changes and contact

Changes to this policy are listed in the release notes and in this file's history. Questions: CONTACT.
