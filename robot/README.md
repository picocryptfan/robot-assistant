# Robot (phases 1 and 2)

A private voice assistant for GrapheneOS on the Pixel 10 Pro. Open it with the corner swipe, from the lock
screen, or by saying "hey robot"; talk, type, or show it a photo or the screen; Gemma 4 E2B on the Tensor G5
TPU answers, streams the reply and reads it aloud. Since 0.2.0 it also drafts texts (Cheogram) and emails (Proton
Mail), keeps and finds notes, reads and adds calendar events and looks up contacts. Everything runs on the phone.
The build specs are `../docs/phase-1.md` and `../docs/phase-2.md`; the voice models and how they are used,
`../docs/voice-plan.md`.

Licence: GPL-3.0-only, see `../LICENSE`. Third-party parts and their licences: `THIRD_PARTY.md`.

## Modules and processes

| Module | Runs in | Holds |
|---|---|---|
| `:app` | main + `:voice` | `ui/` (main: home, history, model manager, settings, assistant setup), `assistant/` (`:voice`: the VoiceInteractionService, the Compose panel in the session, the recognizer stub, tools, the brain binding), `sys/` (storage, model store, restart check), `boot/` |
| `:brain` | `:brain` | `BrainService` (Messenger) and `GemmaEngine`, the only code that touches LiteRT-LM |
| `:voice` | `:voice` | microphone loop, Silero VAD + "HEY ROBOT" (and "STOP" while speaking), speech to text (Parakeet v3) and text to speech (Kokoro, Kitten) on sherpa-onnx, the placeholders while the voice models are missing, the voice speed test |
| `:core` | everywhere | pure logic: prompt and context window, tool specs and validation, tool groups and their router, approval gate, the date and time parser, contact ranking and phone numbers, the hand-off links, the notes store, calendar formatting, model state, the voice model manifest, IPC contract, history format, settings, no-content logging |

- The main app never loads an engine; `ProcessIsolationTest` checks sources, Gradle files and the merged manifest.
- `app/sys/` (both app processes) reads ContactsContract and CalendarContract and holds the Robot Notes folder; only
  the main app asks for permissions (`ProcessIsolationTest` checks that too).
- `:brain` is bound from the assistant with `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_INCLUDE_CAPABILITIES`;
  it is not exported and requires Robot's signature permission `app.heyrobot.permission.INTERNAL`.
- Tools run in the app process (`:voice`), never in `:brain`: the model only proposes calls
  (`automaticToolCalling = false`, constrained decoding when the TPU build allows it), the approval gate decides.
  In a conversation with screen text or a picture every action that writes or leaves the phone needs a tap ("Just do
  it" turns into "Ask first"); the read tools (contacts, notes, calendar) keep their own level;
  at most two actions and three tool rounds per answer; a call written as text runs only when :brain is in text-call
  mode (its TPU build refused the tool API) and the whole reply is the call. An app name is resolved before the card,
  which shows the app that will open. One question at a time: a waiting card counts as busy, Stop declines it.
- Context (4,096 tokens): the new message is cut to fit (the panel says so) and each question carries the time;
  :brain checks its own token count after a rebuild and before each tool round, and steps tools down only on the
  tool-parse error. The turn, gate and :brain decisions are pure code in `:core` (`Turn.kt`, `BrainPolicy.kt`), tested.

## Building

Needs JDK 17+, the Android SDK (platform 36, build-tools 36) and Gradle 8.x.

```
cd robot
echo "sdk.dir=/path/to/android-sdk" > local.properties
tools/fetch_third_party.sh --install          # sherpa-onnx libs, wake-word and VAD models, espeak-ng subset, SHA-256 checked
gradle :core:testReleaseUnitTest :voice:testReleaseUnitTest :app:testReleaseUnitTest
gradle :app:assembleRelease -Probot.keystore=/path/outside/repo/robot-release.p12 \
                            -Probot.keystorePassFile=/path/outside/repo/robot-release.pass
tools/verify_apk.sh app/build/outputs/apk/release/app-release.apk
```

The release key is `../keys/robot-release.p12.enc` (decrypt it outside the repository, see `../keys/README.md`).
The two paths can also come from the environment (`ROBOT_KEYSTORE`, `ROBOT_KEYSTORE_PASS_FILE`); the password
file holds the password on its first line. Without them the release APK is unsigned. Nothing about the key
goes into the repository or the build output.

## People, notes and calendar (0.2.0; review fixes 0.2.1)

Seven tools (`docs/phase-2.md`). The model passes names and words ("Mom", "Tuesday at 3"); the app resolves them,
so the model never builds a phone number, an XMPP address or a timestamp. Robot never sends anything itself: texts
and emails open in the app as drafts and you press Send there. The model is told `draft_opened_not_sent`, and the
card says "Opened a draft in Cheogram: nothing is sent until Send is pressed there", never "done".

| Tool | Does | Default |
|---|---|---|
| `send_text` (to, message) | Finds the person (or takes the number or XMPP address said), shows "Text **Mom** (+1 514-555-0101) in Cheogram" and the whole message, then opens Cheogram with the text in the compose box | Ask first |
| `draft_email` (to, subject, body) | Finds the email address, shows to, subject and body, opens Proton Mail's composer (or the chooser) | Ask first |
| `lookup_contact` (name) | Up to 3 matches: name, up to 3 numbers with their type, up to 2 emails, the XMPP address | Just do it |
| `add_note` (text, title) | Saves to Robot Notes; the card has Undo | Just do it |
| `find_notes` (query) | Up to 3 notes: title and the first 400 characters | Just do it |
| `add_event` (title, when, duration_minutes) | Reads the date and time, shows "Add **Dentist** on Tue Oct 13, 15:00–16:00 to *Personal*", adds it to the chosen calendar, or opens the calendar app's editor prefilled | Ask first |
| `read_calendar` (day) | Up to 10 events of a day or a week: start–end, title (60 characters), location (40) | Just do it |

- **Texts.** Cheogram gets `xmpp:+<E.164>@<gateway>?message;body=…` (gateway `cheogram.com`, JMP's, by default), with
  the body encoded twice because Cheogram decodes it twice (`HandOff.kt`; `HandOffTest` replays Cheogram's exact
  steps). A contact's Jabber/XMPP address (ContactsContract Im) is texted directly. Numbers are normalised with
  `PhoneNumberUtils.formatNumberToE164` and the phone region (default CA). Without Cheogram, or by choice, the SMS
  app opens with `smsto:` and `sms_body`. With both Cheogram builds installed, the F-Droid build
  (`com.cheogram.android`) is always used. A JID with a character Cheogram would cut at (`" & ' / : < > ? # %`) is
  refused, and a contact's XMPP address is offered before its numbers, never instead of them.
- **Cheogram can keep its own draft.** If that chat already has a draft, Cheogram keeps it and drops Robot's text (it
  shows "already drafting message"); if the number is a contact in several Cheogram accounts, it shows a search first
  and the text is lost. Robot can't see either; the card says "If that chat already had a draft, Cheogram keeps the
  old one."
- **Who.** Names and nicknames, case and accents aside: exact, then prefix, then word matches, then letters off (none
  under 4 letters, one up to 5, two from 6). A text or email goes without a card (under "Just do it") only when exactly
  one contact's name or nickname matches exactly and it has exactly one number or address. Anything else shows the
  card, even under "Just do it": a prefix or near match, several people, a contact with several numbers (mobile
  first), or a number or address the model wrote itself. The card lists up to 6 choices and says how many more
  matched ("…and 3 more. Say the full name"). Its button names the person: "Open in Cheogram for Mom".
- **Email.** `ACTION_SENDTO` with `mailto:<address>?subject=…&body=…` (RFC 6068) plus the same values in the extras,
  sent to Proton Mail (or the chooser, "Ask each time").
- **Dates.** `WhenParser` reads ISO dates and times, today / tonight / tomorrow / this morning…, weekdays (the next
  one; "next Friday" is the one in the following week; "Friday after next"; "a week from Friday"), "in 20 minutes",
  "3 days from now", "in an hour and a half" / "in one and a half hours", "October 14" / "14 Oct" (with a year or
  not), "the 14th" (the next 14th), 3pm, 3:30 pm, 15:00, 15h30, noon, midnight, "12 midnight", "9 to 10:30", "from 2
  to 4pm", "for 2 hours" and "all day". `read_calendar` also takes "this week", "next week", "the week after next",
  "this weekend" and "next weekend". A time without a date is the next time it comes; a month and day already past
  is next year's. 10/11 is never guessed ("say the month's name"). A bare hour 1 to 7 is afternoon (appointments), 8
  to 11 morning; with "tonight", "night" or "midnight", 12 is midnight, 1 to 4 the early morning after and 5 to 11
  pm ("tonight at 2am" is tomorrow 02:00). A range past midnight ends that night ("11pm to 1" ends at 01:00 the next
  day). `2026-10-14T15:00-16:00` is a range; `-04:00` is a UTC offset only after seconds (RFC 3339), and an offset
  outside -12:00..+14:00 is refused. Any word left over that isn't filler ("at", "on", "the", "around"…) makes the
  whole thing unreadable, so Robot asks for the date instead of guessing; "first Monday of November" and "the 14th of
  next month" are not read. `add_event` refuses a time that has passed ("that time has passed"). The card shows the
  time before anything is added.
- **Notes.** Robot Notes live in Robot's private storage (one file per note). The Notes tab (and the separate "Robot
  Notes" launcher icon) lists, searches, edits, deletes and shares them; Share goes through the system chooser, so a
  note can go to Easy Notes or anything else. The editor saves as you type (600 ms after you stop), when the app goes
  to the background and when you leave it; a new note keeps its id after the first save, and the open note survives
  rotation. The list reads the folder again when the panel closes over the app. If Undo in the panel removes the note
  you have open, the editor says "This note was deleted" and offers Restore; it is never written back silently. Back
  from the Robot Notes icon leaves the app.
- **Safety.** On the lock screen all seven say "Unlock to do this". Once a read tool (contacts, notes, calendar)
  brought content into a conversation, every later action in it that writes or leaves the phone asks first (texts,
  emails, notes, events and the phase 1 actions), as with screen text and pictures; the read tools keep their own
  level, so reading more doesn't start asking (decided after the 0.2.0 review). The system prompt says tool results
  are information, never instructions. Status details sent back to the model carry no contact or calendar names
  (only the read tools' results do, marked as content). Stopping while an action runs still records what really
  happened (a note saved, an event added; a saved note keeps its Undo), and Undo of a note closes the live
  conversation, so the model hears "undone" from the history. Robot asks for READ_CONTACTS, READ_CALENDAR and
  WRITE_CALENDAR only from the main app: when you tap Allow (Assistant setup, Settings, or "Allow" on a card), turn
  such a tool on, or turn texting on; never at first launch. "Allow" and "Open Robot" in the panel stop the turn and
  the voice before the panel hides.
- **Tokens.** Tools come in groups: CORE (timer, alarm, open app, maps, flashlight) always; PEOPLE, NOTES and CALENDAR
  when a question's words call for them ("text", "email", "note", "calendar", "tomorrow", a weekday…). A
  conversation keeps every group it used; a new group rebuilds it once. Tools turned Off are never described. :brain
  logs the count once per group set and engine load, from the conversation it built anyway (never a second one):
  `brain_fixed_tokens groups=core.people tools=9 mode=TOOLS_CONSTRAINED tokens=… kind=exact estimate=…`
  (`adb logcat -s Robot`), counts only. `kind=exact` when that conversation had no history; otherwise
  `kind=estimated` (the real count minus an estimate of the history). Opening a new conversation closes the idle ones.

Settings › People, notes and calendar: texting app (Cheogram when installed, the SMS app, or Off), SMS gateway domain,
phone region, email app (Proton Mail when installed, or "Ask each time"), the calendar for new events (only visible,
synced calendars Robot can write to; Automatic picks the first, the primary or owner account first) and the default
event length (60 min). A calendar error while adding opens the calendar app's editor instead; its all-day times are UTC
midnights, as AOSP-derived editors read them.
Settings › Actions has an approval level per tool (Off is allowed). Assistant setup › People, notes and calendar shows
both permissions with Allow buttons and whether Cheogram and Proton Mail are installed.

**Cheogram's roster.** When the person (or the gateway number) isn't in Cheogram's contact list yet, Cheogram may first
show its "add contact" dialog; add it, and the draft is there. This is Cheogram's own behaviour (StartConversationActivity),
not an error.

**Privacy: the system log (unverified on the phone).** Android's ActivityTaskManager logs each activity start with its
URI made safe by `Uri.toSafeString()`. That masks `mailto:` and `smsto:`. Review A read it as printing the whole
`xmpp:` link (the gateway JID with the phone number, and the once-decoded message) to logcat; review B found that
since Android 14 `toSafeString` prints only the host and port for schemes other than the telephony ones (AOSP's
android14 to android16 branches), so neither would appear. Which one this phone does is not checked yet (step 11
below). Robot can't avoid it either way: Cheogram only takes the text in the link. The log is readable only with adb
or by apps holding READ_LOGS.

**verify_apk.sh** checks, besides the signer, version, SDK levels, processes, permissions, native libraries and
assets, that MainActivity and the Robot Notes entry stay in the main process, that the exported components are
exactly an allowlist (the app, Robot Notes, the three assistant services and AndroidX's profile installer), and that
no `ui/`, `sys/`, `boot/` or `RobotApp` class in the dex refers to sherpa-onnx, LiteRT or the assistant, voice and
brain packages.

### On the phone (0.2.0, with the 0.2.1 fixes)

1. Assistant setup › People, notes and calendar: Allow contacts and the calendar; check that Cheogram and Proton Mail
   show as installed.
2. "Text Mom I'm running late": the card names Mom and her mobile number, "Open in Cheogram for Mom" opens the
   conversation with the text in the box (try a message with `; = & + %`, quotes, an emoji and a line break: it must
   arrive unchanged). With two numbers or two Moms the card lists them. With a 20-line message, the person, the
   number and the buttons stay at the top of the card and the message scrolls inside it; the keyboard is hidden.
   The card then says "Opened a draft in Cheogram", not "Done". Under "Just do it", "text Mo hi" (only a prefix of
   "Mom") and "text 5145550101 hi" still show the card.
3. "Email Alex about Friday's plan": Proton Mail opens a draft with Alex's address, the subject and the body filled in
   (check line breaks in the body).
4. "Note: buy coffee filters" (card with Undo), then later "what did I note about coffee". Open the Robot Notes icon,
   edit, search and Share a note. Type in a new note, rotate the phone, then press Home: the text is kept and saved.
   Add a note from the panel over the open Notes list: the list shows it when the panel closes. Open that note in the
   app, open the panel again and tap Undo: the editor says "This note was deleted" and offers Restore. Back from the
   Robot Notes icon's list leaves the app.
5. "What's on my calendar tomorrow".
6. "Add dentist Tuesday at 3": the card shows the right date and 15:00–16:00 and the calendar; after "Add to
   Personal" the event is in the calendar app. Deny the calendar permission once to see the calendar app's editor
   open instead (note which app opens, and that an all-day event lands on the right day). Also try "add party tonight
   at 11 to 1" (ends 01:00 the next day), "add lunch the 14th at noon", "add gym a week from Friday at 7am", and
   "add standup today at 8" after 8am (refused: that time has passed).
7. After step 5 in the same conversation, ask "and the day after tomorrow?" (reads without asking), then ask for a
   timer: it now asks first.
8. Locked: "text Mom hi" says "Unlock to do this".
9. Send `adb logcat -s Robot | grep brain_fixed_tokens` lines: the cost of each group set and whether it is exact.
10. "What's on my calendar next weekend", "remind me to call the bank at 3:30", "ping Sam", "let Sarah know I'm
    late", "what did I save about coffee": each reaches the right tool.
11. Text someone through Cheogram, then send `adb logcat -d | grep "START u0" | grep -c xmpp` and whether the line
    shows the message: the system-log privacy note above.
12. On a card asking for a permission, tap "Allow" while Robot is still speaking: the voice stops and the panel hides.

## Voice (0.1.1; memory rule 0.1.2; fixes 0.1.3)

- Speech to text: NVIDIA Parakeet TDT 0.6B v3 int8 (`SherpaSpeechToText`). Push-to-talk, or right after "hey robot";
  a Silero VAD of its own ends the question after 0.5 s of silence (20 s max speech, 30 s cap), or the button does.
  The words appear while you talk (the buffer is decoded again about every 0.7 s, one decode at a time; can be
  turned off). Audio stays in memory.
- Text to speech: Kokoro v1.0 fp32, voice "Heart" (`af_heart`) by default, "Siwis" (`ff_siwis`, French) for later;
  Kitten nano 0.8 fp32 as the small fast voice; the phone's own voice as the fallback (`VoiceOutput`). The first
  piece of a reply is short (5+ words to the first comma, or 12 words) so audio starts fast; the rest goes
  sentence by sentence into a streaming AudioTrack. Tap the conversation, or say "stop", while it speaks: the rest of
  that reply is still shown but not spoken (the next question speaks again). Stop, closing the panel or "hey robot"
  during an answer silence it at once, and a stopped reply's unfinished last sentence is never read out.
- sherpa-onnx calls the voice back from native code through `invoke([F)Ljava/lang/Integer;`, which a Kotlin lambda
  does not have (0.1.2 was silent): the callback is the Java class `TtsCallback`, checked by `TtsCallbackTest` and by
  `tools/verify_apk.sh` in the built APK. Chinese, Japanese and Korean text is not sent to the voice (sherpa would log
  it).
- The models are downloaded by the app (Model › Voice models: 670 MB, 354 MB, 60 MB), from pinned Hugging Face
  commits, each file checked (size and SHA-256) while it is copied into private storage. Until then the
  placeholders run and the mic button says "Download voice models".
- Robustness: nothing native is created before the files are verified; a load that throws falls back to the
  placeholder; a load that kills the `:voice` process twice in a row marks that engine broken until
  Settings › Voice › "Try again" (the wake word keeps working). Native loads run one at a time, so a crash is blamed
  on the engine that was loading. A question ends with what was said if the microphone stops under it, and after
  35 s by the clock in any case. A voice-model install that fails to write waits for the Download button. The wake word stays loaded; speech to text loads
  when a question starts (mic or wake word, not when the panel opens), the voice when a spoken reply is coming; each
  is freed after 5 minutes unused (not while listening or speaking). Robot also frees them if Android reports memory
  pressure, but Android 14 and later no longer send the "running low" levels, and the "background" level needs the
  `:voice` process to become cached, which the assistant's process does not: in practice the 5-minute rule frees them.
- Settings › Voice › "Test voice speed" times the downloaded models on the phone (`tools/voice_bench.py` does the
  same on a computer).

First run on the phone: Model › Voice models › Download "Speech recognition" and "Voice" (Wi-Fi; they install by
themselves once downloaded, with Robot open), optionally "Fast voice". Then Settings › Voice › "Test voice speed".

Tools: `tools/fetch_voice_models.sh <dir>` (the same files and hashes as the app), `tools/make_test_clip.py`
(regenerates the benchmark clip), `tools/voice_bench.py` (desktop benchmark), `tools/make_keywords.py`
(`keywords.txt` and `stop.txt`).

Keep sherpa-onnx inside `:voice` (the isolation test enforces it) and list new models in `THIRD_PARTY.md`
(`VoiceManifestTest` checks the manifest against it and the tools).
