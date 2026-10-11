# Robot's privacy policy

> **Draft for review.** Written against Robot 0.4.3's code (the `feature/compliance` branch). Re-check it against the
> merged 1.0 before publishing: conversation mode and transcripts (0.5.0) and the other helpers (0.6.0) add to it.
> Replace CONTACT with a real address.

## The short version

Robot does not collect anything about you. There is no account, no analytics, no ads, no crash reporting and no tracking.
The people who make Robot receive nothing from your phone. Robot goes online only for the things listed below, and only
when you start them, except the daily check for updates, which you can turn off.

## What stays on your phone

- **What you say.** Robot listens for its wake words on the phone. Your voice is turned into text on the phone, kept in
  memory, and never written to storage. No recording is made.
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
- **Names learned from Cheogram** (off unless you turn on Settings › People › "Learn names from Cheogram
  notifications"): who messages you in Cheogram (name, address, account), never the messages.
- **API keys** you add for Claude, and the **access token** you may add for a private update repository (Settings ›
  About › Updates › Advanced). They are encrypted with a key that never leaves your phone's secure hardware (Android
  Keystore, AES-GCM), and decrypted only when Robot calls Claude, or GitHub's API for the token.
- **Robot's diagnostic logs** (Settings › Voice › Voice log, and Android's log) hold codes and numbers only, never what
  you said or what Robot answered. They stay on the phone; you can copy the Voice log yourself for a bug report.

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
   - Without a key, "Open in the Claude app" only fills in the Claude app (and may put your question on the clipboard);
     you press Send there.
5. **Other apps you hand things to** (Cheogram, Proton Mail, your SMS or calendar app, Organic Maps, the Claude app).
   Robot fills in a draft or opens a route; that app sends something only when you tap Send, under its own policy.
6. **Checking for updates** (Settings › About › Updates): once a day while "Check for updates automatically" is on (the
   default), and when you tap Check now. Robot asks GitHub (api.github.com) for the newest releases of Robot's repository,
   or of the one you set in Advanced, and downloads an update from GitHub's servers (objects.githubusercontent.com,
   release-assets.githubusercontent.com) when you tap Download, or by itself on Wi-Fi if "Download updates on Wi-Fi" is on
   (the default). To GitHub: your IP address and Robot's version in the request ("Robot/0.4.5"); nothing else is sent. An
   access token, if you add one, goes to api.github.com only. GitHub handles the requests under its own privacy statement.
   Turn the automatic check off and Robot asks GitHub only when you tap Check now.

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
- **Notifications:** alarms, timers and reminders, such as the one after a restart, and that an update is available.
- **Alarms** (at install): timers and alarms.
- **Usage access** (optional, in Android's settings): which apps use the most space.
- **Notification access** (optional, only for learning names from Cheogram).
- **Network** (at install): the online features above.
- **Install unknown apps** (optional, in Android's settings, which Settings › Updates opens): so Robot can install its own
  updates. It installs nothing else. "Install updates without asking" (off by default) uses a permission granted at
  install that lets Robot ask Android to skip its confirmation for Robot's own update.

## Children

Robot is not made for children.

## Changes and contact

Changes to this policy are listed in the release notes and in this file's history. Questions: CONTACT.
