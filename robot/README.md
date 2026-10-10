# Robot (phases 1 and 2, Ask Claude)

A private voice assistant for GrapheneOS on the Pixel 10 Pro. Open it with the corner swipe, from the lock
screen, or by saying "hey robot" (or wake phrases of your own); talk, type, or show it a photo or the screen; Gemma 4 E2B on the Tensor G5
TPU answers, streams the reply and reads it aloud. Since 0.2.0 it also drafts texts (Cheogram) and emails (Proton
Mail), keeps and finds notes, reads and adds calendar events and looks up contacts. Everything runs on the phone.
Since 0.3.0, and only when you ask (or let it), Robot can hand a question to Claude: through the Claude API with your
key, or by opening the Claude app (see "Ask Claude" below). The build specs are `../docs/phase-1.md`,
`../docs/phase-2.md` and `../docs/ask-claude.md`; the voice models and how they are used, `../docs/voice-plan.md`.

Licence: GPL-3.0-only, see `../LICENSE`. Third-party parts and their licences: `THIRD_PARTY.md`.

## Modules and processes

| Module | Runs in | Holds |
|---|---|---|
| `:app` | main + `:voice` | `ui/` (main: home, history, model manager, settings, assistant setup), `assistant/` (`:voice`: the VoiceInteractionService, the Compose panel in the session, the recognizer stub, tools, the brain binding), `sys/` (storage, model store, restart check), `boot/` |
| `:brain` | `:brain` | `BrainService` (Messenger) and `GemmaEngine`, the only code that touches LiteRT-LM |
| `:voice` | `:voice` | microphone loop (with echo cancellation while Robot speaks), Silero VAD + the wake phrases (and "STOP" while speaking), speech to text (Parakeet v3) and text to speech (Kokoro, Kitten) on sherpa-onnx, the placeholders while the voice models are missing, the voice speed test |
| `:claude` | `:voice` | Ask Claude's API client on the official Anthropic Java SDK (`ClaudeClient`, `ClaudeRequests`, `ClaudeSession`, `ClaudeHistory`, `ClaudeTurn`), the only code that links the SDK |
| `:core` | everywhere | pure logic: prompt and context window, tool specs and validation, tool groups and their router, approval gate, the date and time parser, contact ranking and phone numbers, the hand-off links, the notes store, calendar formatting, model state, the voice model manifest, IPC contract, history format, settings, no-content logging; Ask Claude's router, pre-router, hedge check, escalation policy, what Claude receives, prices and cost, failures, the Claude app link and answer extraction |

- The main app never loads an engine or the Anthropic SDK; `ProcessIsolationTest` checks sources, Gradle files and
  the merged manifest, and `tools/verify_apk.sh` checks the dex.
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
- Context (4,096 tokens): the new message is cut to fit (the panel says so). A question about times or dates, an
  alarm or a timer, or any question in a conversation using the calendar tools, carries the time after it
  ("(Current time: 14:05.)"); others don't (0.2.1 put "[Now 14:05]" before every question and the model began its
  replies with it). A copy of the clock at the start of a reply, or of its text after a tool round, is removed before
  it is shown or spoken (`ClockEcho`), and a saved reply that begins with one is replayed without it. :brain checks
  its own token count after a rebuild and before each tool round, and steps tools down only on the tool-parse error.
  The turn, gate and :brain decisions are pure code in `:core` (`Turn.kt`, `BrainPolicy.kt`), tested.

## Building

Needs JDK 17+, the Android SDK (platform 36, build-tools 36) and Gradle 8.x.

```
cd robot
echo "sdk.dir=/path/to/android-sdk" > local.properties
tools/fetch_third_party.sh --install          # sherpa-onnx libs, wake-word and VAD models (and its bpe.model), espeak-ng subset, SHA-256 checked
gradle :core:testReleaseUnitTest :voice:testReleaseUnitTest :claude:testReleaseUnitTest :app:testReleaseUnitTest
gradle :app:assembleRelease -Probot.keystore=/path/outside/repo/robot-release.p12 \
                            -Probot.keystorePassFile=/path/outside/repo/robot-release.pass
tools/verify_apk.sh app/build/outputs/apk/release/app-release.apk
```

The release key is `../keys/robot-release.p12.enc` (decrypt it outside the repository, see `../keys/README.md`).
The two paths can also come from the environment (`ROBOT_KEYSTORE`, `ROBOT_KEYSTORE_PASS_FILE`); the password
file holds the password on its first line. Without them the release APK is unsigned. Nothing about the key
goes into the repository or the build output.

## Ask Claude (0.3.0)

Robot stays offline by default. Nothing goes to Claude unless you ask for it ("hey robot, ask Claude why the sky is
blue", "go get Claude"), or you let Robot check hard questions with Claude ("When Robot needs help" below). Voice stays
on the phone: speech to text and text to speech run here, and only text leaves it. Ask Claude is on by default; without
an API key, questions for Claude open the Claude app. Spec: `../docs/ask-claude.md`.

### Two ways to Claude

- **Claude API** (the default once a key is saved): Robot asks Claude itself, streams the answer into the panel and
  reads it in Claude's voice. Needs an Anthropic API key with API credits.
- **Claude app** (the default without a key): Robot opens the official Claude app with your question, the way it opens
  Organic Maps, and steps aside; your claude.ai plan answers it there and you press Send if the app doesn't. Robot tries,
  in order, `claude://claude.ai/new?q=…`, `https://claude.ai/new?q=…` (both with the package set), a share of the text,
  and last the app alone with the question on the clipboard ("Paste your question in Claude"); whichever resolves first on
  the phone. `q` is RFC 3986 encoded and cut at 2,000 characters (on a sentence). Assistant setup › Ask Claude › "Claude
  app" shows which of the three the installed version accepts (✓/✗). "Go get Claude" with no question opens Claude with
  a one-to-three-sentence summary of the conversation in its own words. Never on the lock screen.

### Getting an API key (the API route)

1. Sign in at **console.anthropic.com**, add API credits (Billing), and create a key (API keys).
2. **API credits are separate from a claude.ai subscription:** a Pro or Max plan does not cover API calls. Without
   credits Robot says "Your Anthropic account is out of API credits".
3. Robot › Settings › Ask Claude › **Add key**, paste it, Save. The key is pasted into a small window that runs in the
   assistant's process (`:voice`), encrypted there with an Android Keystore key (AES-GCM, not exportable) and decrypted
   only there, just before the API client is made. It never appears in a log, a crash text, the history, an intent or
   an IPC message; Settings shows it masked ("sk-ant-…AB12") and has **Delete key** (it asks first, and the assistant
   drops the key at once). The window can't be captured in screenshots or the recent-apps thumbnail, is kept out of
   autofill, and clears the clipboard if it still holds the key. A key is "sk-ant-" and ASCII letters, digits, "-" and
   "_" only. If Android's keystore ever loses the key that encrypts it, Robot removes the saved key and Settings says so.

GrapheneOS's per-app **Network** toggle must stay on for Robot. When Android reports Robot's network permission off, the
panel shows a card saying so (and says it aloud); when the toggle shows as no network at all, "No internet" says to
check Robot's Network permission. Robot's INTERNET permission is used for two things only: the model downloads and Ask
Claude (also on the Assistant setup screen). It is still Robot's only network permission.

### What Claude receives, and when

| You say | What is sent |
|---|---|
| "ask Claude …", "Claude, what's …", "hey Claude explain …", "what does Claude think about …" | the question only |
| "… about what's on my screen" / "about this photo" | nothing until you tap **Send** on "Send your screen's text and a screenshot of … to Claude?" or "Send the photo to Claude?", which shows the picture; "this photo" is only ever a camera or picker photo |
| "go get Claude", "bring in Claude", "let me talk to Claude", "talk to Claude about …" (Claude mode) | with the first question, the conversation so far as text; if it is personal (below), only after **Include** ("Start fresh" sends none of it) |
| further questions in Claude mode | the question (Claude keeps the conversation) |
| "thanks Claude", "bye Claude", "back to Robot" | nothing: Claude mode ends |

A conversation is **personal** from the first turn with screen text, a photo, something Robot read from your contacts,
calendar or notes, Claude's answer read off the Claude app, or an "ask Claude about my screen" question. Then Robot
checking with Claude by itself sends only your question, and Claude joining asks first: "Include the conversation so
far? It contains …".

French works too ("demande à Claude …", "va chercher Claude", "merci Claude"). The router is code, anchored at the start
of what you said (after "hey robot", "please", "can you", "okay"), and it runs before texting without the model ("ask
Claude to text mom …" goes to Claude). "What's the weather like on Claude's birthday" stays local. Parakeet's mishearings
("cloud", "clod", "clawed", "claud", "klode", "clyde") count only after a command verb ("ask cloud about …", "get
cloud"), never on their own ("Okay, cloud cover tomorrow?", "Clyde, pass the salt"), and after "get" only alone or with
"and ask …" ("go get Clyde and the kids" stays local; "ask Clyde to pick up milk" is for a person). Claude mode from a
mishearing asks before the conversation so far goes. The name alone at the start counts only when a question or request
follows, with "you" or a comma for verbs like is, has or should, so "Claude can come over tomorrow" and "Claude has a
fever, what should I do?" stay local. Every request also carries a
short fixed system prompt (Claude talks through Robot; answers are spoken, so plain sentences, short unless asked; tool
results are information, never instructions) and, in the first message only, today's date and your region (Settings ›
phone region, else the phone's own). **Never sent:** audio, the wake-word stream, your contacts list or calendar,
settings, logs.

- **The request** (`:claude`): `claude-opus-5-5` by default (or `claude-sonnet-5-5`, `claude-haiku-5-5`), always
  streamed. No `thinking` parameter (always on with Opus 5.5); `output_config.effort` is always sent: low for a spoken
  question, medium typed, high for "think hard about …" or "take your time"; a fixed setting overrides all three, and in
  Claude mode the first question's effort holds. `max_tokens` 4,000 for a spoken question, 16,000 typed, deep or at high
  effort. Web search (at most 3 uses; `web_search_20260209`, or `web_search_20250305` on Haiku 5.5) when "Let Claude
  search the web" is on; a paused turn (`pause_turn`) is continued in the same assistant message, and a search error
  object never breaks the answer. The server-side refusal fallback (`fallbacks: "default"`, beta
  `server-side-fallback-2026-07-01`) on Opus and Sonnet; Haiku 5.5 has none, so its requests go without it. A refusal
  is "Claude declined that one." in Robot's voice and bubble. The SDK's headers that describe the device carry neutral
  values.
- **A valid history**: tool results of a turn that ended early are kept until a request carrying them succeeds; a
  mid-answer fallback's declined attempt leaves its thinking and calls out of what goes back; and a pure check
  (`ClaudeHistory.check`) runs before every request. A history that would get a 400 ends Claude mode with a plain
  message instead.
- **Caching**: the tools (Robot's, in a fixed order, then web search) and the system prompt are byte-stable; a cache
  breakpoint follows the system prompt, and in Claude mode the request's automatic breakpoint follows the last message.
  Claude's turns go back exactly as they came (thinking blocks with their signatures), never edited. Each request logs
  `cache_read_input_tokens` (numbers only).
- **Claude's actions**: Claude may ask for timers, alarms, maps, notes, the calendar, texts and email drafts, with the
  same schemas as Gemma's (strict). Every call is checked against its schema and **always shows a card**, read tools included
  ("Share with Claude"); the lock-screen rules apply; at most 2 calls and 3 tool rounds per answer; results go back as
  `tool_result` blocks with `is_error` when the action failed. Not open_app, the flashlight or contact lookups.
- **Errors**: a refused key ("Your Claude key was refused"), a rate limit ("Claude is busy, try again in a minute"),
  overloaded or 5xx (one retry, then said), no network ("No internet", with the Network permission hint), 60 s without
  any stream event ("Claude took too long to answer"; a thinking block in progress counts as progress). The SDK's own
  retries and logging are off. The voice process never crashes on them. The voice log records each request (sent,
  first event, done or failed with its code), never words.

### The panel

Claude's replies carry a small "Claude" label and the dynamic colour's tertiary tone, and are read in Claude's voice:
another voice of the same model, chosen in Settings (Kokoro "Michael" by default; with Kitten, Kitten's other voice;
with the phone's voice, a lower pitch). A French sentence from Claude is read by Kokoro's French voice. "Claude is
thinking…" shows until the first words. In Claude mode a chip reads
"Talking with Claude · Back to Robot"; Claude mode ends with "thanks Claude", the chip, the panel closing, 10 minutes
without a turn, or the lock screen (unless "Allow on lock screen" is on). Stop and "stop" work as for Robot. Under a
reply: "Claude searched the web · nasa.gov, esa.int" and the **cost line**, "≈ 1¢" (Settings › Show cost), estimated
from `usage` and the published prices per million tokens (one table in `:core`, `ClaudeModel`): Opus 5.5 $4 in, $20
out, cache reads $0.20; Sonnet 5.5 $2 / $10; Haiku 5.5 $0.10 / $0.50 (up to 100K prompt tokens); a fallback model at
its own rates; web search $10 per 1,000. A stopped, failed or timed-out request counts too (its input is known as it
starts), and today's spend is written as each part becomes known. A conversation with Claude in it is saved under the usual history rules and shows a small cloud in History.
Claude's answers join Robot's own conversation (cut to about 600 tokens for Gemma), so "add that to my calendar" works
locally afterwards; they count as content Robot didn't write, so later actions ask first.

### When Robot needs help

Settings › Ask Claude › "When Robot needs help": **Off**, **Ask me first** (Robot offers "Want me to check with
Claude?" under its reply and waits for a tap or a yes), or **Automatic** (the default once a key is saved; only the API
escalates by itself, so without a key it is always "Ask me first" and opens the Claude app, if it is installed). Three triggers:
deterministic words first (explain why/how, compare, plan, write me, draft, code, calculate, solve, step by step,
latest, news, prices, scores, weather, or more than 40 words; never a timer, alarm, text, note, event, app, map or
flashlight question, a door or wifi code, news from someone you know, or your credit score), Gemma's `ask_claude` tool
(described only with Ask Claude on and somewhere for the question to go), and a hedged local answer ("I don't know",
"I can't browse", "as an AI", or nothing). At most one escalation per turn; a Claude answer is never re-escalated.
Automatically Robot sends only your question and the last two turns of plain conversation, and in a personal
conversation only your question. A question about the screen or a photo always asks first. "Checking with Claude…" is
said in Robot's voice, then Claude answers in Claude's. An **offer** lists what **Send** would send (with the start of
any screen text), and that is fixed when it is made; it expires when the panel closes, you ask something else, or Ask
Claude is turned off. A spoken or typed "yes" sends it only when nothing personal is in it; otherwise Robot says "Tap
Send to include …". A **daily budget** (default $1.00; Settings shows today's estimated spend) turns Automatic into Ask
me first for the rest of the day, said once.

### The Claude app's terms, and reading its answer back

Anthropic's Consumer Terms (effective 8 October 2025, §3 item 7) forbid using the Claude apps "through automated or
non-human means, whether through a bot, script, or otherwise", except through an API key. So Robot never automates the
app: no accessibility service, no typing, tapping or sending in it, no reading it in the background. It only prefills,
and you press Send. To hear the answer, open the panel **with the corner swipe** over the Claude app and tap **"Read
Claude's answer"** (shown only when an answer can be found in the Claude app's own window): Robot takes the last answer
from the screen text the swipe hands to every assistant (pure heuristics in `ClaudeAppAnswer`, tested), reads it in
Claude's voice and adds it to the conversation as screen content, so "add that to my calendar" or "text that to Mom"
then work through cards. Nothing usable found: "Ask about this screen"
instead. "Hey robot, read Claude's answer" can't work on Android 17 (a wake-word session gets no screen data, phase 0):
Robot asks you to swipe from the corner.

### The SDK on Android

`com.anthropic:anthropic-java` 2.71.0 (MIT) with its OkHttp client, Jackson and kotlin-reflect (`THIRD_PARTY.md`).
Checked without a device: R8 traced from the `:claude` module through every SDK jar against android.jar (min API 34)
finds no missing class on Robot's path (no `java.net.http`); in the whole SDK only `java.beans.*` (Jackson's optional
support, loaded defensively) and `java.lang.reflect.AnnotatedType` (the structured-output schema helpers, unused) are
missing. The network tests run the real SDK against a local mock server. Release builds stay unminified (as since phase
1), so the SDK costs about 33 MB of dex (the APK went from 58.7 MB to 91.4 MB). `claude/consumer-rules.pro` holds
narrow rules for the day R8 is turned on: only what Jackson binds by reflection (annotated members, the models'
serializers and deserializers, type references, Kotlin metadata), not the whole SDK. That could bring the SDK and its
libraries to roughly 8 to 12 MB, an estimate to be measured, and a minified build would first need an on-device test
that replays every kind of stream event. `tools/verify_apk.sh` checks that the dex refers to no JVM-only API. The SDK's
classes load in the background when the panel opens.

### On the phone (0.3.0)

1. Settings › Ask Claude: **Add key** (paste a key from console.anthropic.com with credits); the key shows masked.
   Assistant setup › Ask Claude: Network allowed, the key, and the Claude app row (note which of ✓/✗ appear).
2. "Hey robot, ask Claude why the sky is blue": Claude's answer is spoken in Claude's voice within about 3 s of the end
   of the question, labelled "Claude", with "≈ …¢" under it. Note the time to the first word.
3. "Go get Claude", then two follow-ups (one referring to the first, e.g. "and at sunset?"), then "thanks Claude": the
   chip shows while Claude is in, and the next question goes to Robot (Gemma).
4. "Ask Claude to set a timer for 10 minutes": a card, and only "Do it" sets it. "Ask Claude what's on my calendar
   tomorrow": the read card says "Share with Claude".
5. Wi-Fi and mobile data off: "ask Claude anything" says "No internet" and the Network permission hint. Then turn
   Robot's Network toggle off (Wi-Fi back on) and note what Robot does: the "can't use the network" card, or "No
   internet" with the hint; and what Assistant setup › Network shows.
6. Lock the phone: "ask Claude …" says to unlock (or works with "Allow on lock screen" on); Claude mode ends when locked.
7. "Ask Claude about what's on my screen" from a corner swipe: the Send card first, nothing sent on Cancel.
8. "Explain why bread rises" (Automatic): "Checking with Claude…" then Claude. Set Ask me first and ask something
   Gemma hedges on: the offer card, accepted by "yes". Set a 25¢ budget and use it up: Robot says so once.
9. Delete the key (it asks first): Ask Claude stays on and questions go to the Claude app. "Ask Claude what a black hole
   is": "Open Claude with: «…»", the app opens with the question (or the clipboard note). Answer in Claude, then
   corner-swipe and "Read Claude's answer".
10. History: the conversation shows the cloud; the cost line follows "Show cost".
11. Send `adb logcat -s Robot | grep claude_` lines: counts and codes only, never words or the key. Settings › Voice ›
    Voice log shows "Question sent to Claude", "Claude's first event" and how each request ended.
12. Ask "what's on my calendar tomorrow", then "go get Claude" and a question: the "Include the conversation so far? It
    contains your calendar." card; try Start fresh once (Claude knows nothing of the calendar) and Include once.
13. Ask Me first, a screen question Gemma hedges on: the offer lists your screen's text; say "yes": Robot says "Tap Send
    to include screen text." and nothing goes until the tap. Close the panel: the offer is gone next time.
14. "Hey robot, ask Claude to think hard about why the Roman empire fell" (Opus, High): "Claude is thinking…" for as
    long as it thinks, then the answer, no timeout. Note the time to the first word. While Claude talks, say "stop" and
    "hey robot" (barge-in), and press volume down.
15. "Ask Claude to answer in French: why is the sky blue": the French sentences in Kokoro's French voice. Add a key:
    check that the autofill service doesn't offer to save it, and that a screenshot of the key window is blocked.

## Voice reliability, wake words and texting (0.2.3)

### Voice log

Settings › Voice › Voice log lists the last 300 things the voice side did, newest first, with relative times, and
**Copy** puts them on the clipboard as plain text. When Robot doesn't respond, take a screenshot (or Copy) right away:
the log shows where it stopped. It holds event codes and small numbers only, never words, transcripts, audio or
names (`core/VoiceLog.kt`: the recording API takes only fixed enums and numbers, and `VoiceLogTest` checks that). It
lives in the `:voice` process's memory and in `files/robot/voice_log.txt`, so it survives a restart.

It records: wake phrase heard (its number in Settings › Voice › Wake words; sherpa-onnx 1.13.8 reports no score), and
wakes ignored (within 2 s of the last, Robot saying the word herself, no running assistant, the panel couldn't open);
"stop" heard, or set aside by EchoGuard; listening started or stopped and why (assistant ready, settings, charger,
retry, restart after a stall, microphone busy or dead, no permission, not charging), with the microphone mode and
echo cancellation; another app taking the microphone; the wake-word model ready (and how long it waited for another
model's load); speech the wake-word gate heard (thinned to one line per 10 s); the question's speech start and end;
speech to text started, done (characters only), empty, or ended early (nothing said, watchdog, microphone stopped);
the panel shown (wake word, corner swipe, lock screen, app button) and hidden; the model request sent, first words,
finished or failed with its code; the model's process stopping; Robot's voice starting and stopping and why (done,
"stop", tap, volume key, new question, stop button, panel closed, wake word); "panel opened but no question heard";
"question heard but no reply"; the chime or "Sorry, I didn't catch that."; screen on and off; the barge-in test.

Silent drops found and fixed while adding it: an empty transcription did nothing (now: notice plus "Sorry, I didn't
catch that." or a chime); an empty model answer left an empty bubble; a model that never answered left "Thinking…"
for good (now given up after 60 s without any event); a `:brain` binding that Android had given up on was kept, so
every later question waited 15 s and failed until the voice process restarted; a capture that stopped delivering
audio without an error was never restarted (now after 15 s); a listener that died right after starting waited up to
5 minutes for its retry (now 60 s, three times); the listener's audio loop waited behind another model's load before
reading any audio, so a question starting then got nothing for seconds (the wake-word model now loads on its own
thread); the voice's prewarm could queue its load ahead of the question's model; a voice whose audio track couldn't
be created dropped the sentence silently (now the phone's voice says it).

### Hearing you while Robot talks

The microphone used the speech recognition mode, which has no echo cancellation: while Robot spoke, her own voice
drowned "stop" and the wake word. Settings › Voice › **Hear me while Robot talks**:

- **Echo cancellation** (default): when Robot starts speaking the capture moves to the call mode
  (`VOICE_COMMUNICATION`) with the platform's `AcousticEchoCanceler` and `NoiseSuppressor` on its session (when
  `isAvailable()`), and back to speech recognition 0.8 s after she stops. It never switches in the middle of a
  question. The new `AudioRecord` has the same format; the audio ring and sample counts carry on; the keyword stream
  starts afresh after the few-ms gap, keeping its 1 s pre-roll. The switch back waits (up to 8 s) while the wake gate
  hears speech, so a follow-up "hey robot" isn't cut in two (`CapturePolicy`, tested). Each old capture's recording
  callback is unregistered before it is released.
- **Off**: as before.
- **Always echo cancellation**: the call mode the whole time the panel is open (no switching; questions a little less
  accurate).

While echo cancellation is on, "stop" uses a slightly lower threshold (0.20 instead of 0.25); EchoGuard still sets
aside a "stop" heard while Robot says a word like it, and now also a wake phrase heard while she says its word
("robot"). All keyword thresholds live in `core/WakePhrases.kt` (`KwsThresholds`).

**Robot's voice plays as**: *Assistant audio* (default, as before) or *Call audio* (the phone in communication mode
with the loudspeaker as the call device while Robot speaks, as calling apps do; it uses the call volume and needs
`MODIFY_AUDIO_SETTINGS`, a normal permission). On Pixel the echo canceller runs in the audio DSP and takes everything
the speaker plays as its reference, so no playback change should be needed; this could not be checked on a phone
yet, which is what the barge-in test is for.

**Barge-in test** (same screen): Robot reads a fixed 20-second paragraph with no word like "stop" and no wake phrase;
say "stop" or a wake phrase at any point. The result says whether she heard you, when, and how soon she went quiet;
**Silent run** plays the paragraph while nobody speaks and counts false "stop"s and wakes. Run both in each mode.

**Volume key**: while Robot speaks and the panel is on screen, volume down (or either key, or off: Settings › Voice)
stops her and doesn't change the volume. The panel's window handles it (its dialog's key listener, and the
session's `onKeyDown`/`onKeyUp`, which `VoiceInteractionSession` implements as a `KeyEvent.Callback` on API 34 to 36).
With the screen off or the panel closed, the key is the system's as always.

### Wake words

Settings › Voice › Wake words: a list of phrases (default "hey robot"), each with Add, Remove, on/off and a
sensitivity. A phrase is 1 to 4 words of letters A to Z, spaces and apostrophes, at most 24 characters; "stop" is
reserved; up to 8 phrases. A single word shows "Short words like 'robot' trigger by accident more often." and starts
at Low.

| Sensitivity | threshold | boost | for |
|---|---|---|---|
| Low | 0.35 | 1.0 | single words, noisy rooms |
| Medium | 0.25 | 1.5 | the setting "hey robot" was tested with (10 of 10 close, 5 of 5 across the room, no false wake in 2 h) |
| High | 0.18 | 2.0 | a quiet voice or a far phone; more accidental wakes |

The keyword model needs each phrase as its SentencePiece tokens. `kws/bpe.model` (a unigram model, despite the name)
is now in the APK, and `core/SentencePiece.kt` reads its protobuf by hand and runs the unigram Viterbi in pure Kotlin,
so the main app checks phrases without sherpa-onnx. `SentencePieceTest` compares it with sherpa-onnx's `text2token`
on 115 phrases (`core/src/test/resources/kws/text2token-reference.tsv`), exactly. A phrase whose tokens aren't all in
`tokens.txt` is refused. The `:voice` process builds one line per enabled phrase, `<tokens> :<boost> #<threshold>
@W<id>`, and gives them (plus `STOP` while Robot speaks) to `KeywordSpotter.createStream`; the spotter's own
`keywords.txt` is one empty line, because sherpa-onnx adds it to every stream. A changed list starts a new stream
without a restart. Any enabled phrase opens the panel exactly like "hey robot", question audio included.

**Test wake words** (same screen): while it shows, Robot lists each phrase it hears, with the time, instead of opening.
It ends when the screen is no longer visible (the app in the background, or the screen off).

### Texting without the model

"text mom I'm running late" (typed or spoken), "message Sarah: see you at 5", "send a text to Bob saying hi", "send
mom a text saying hi", "tell mom I'm on my way" and "let Bob know I'll be late" become send_text directly
(`core/Texting.kt`): the same preparation, card, Cheogram-pick fallback, approval gate and lock-screen rule as the
model's call, but no model. The name ends where the longest 1 to 3 words match a contact's name or nickname (exactly
or as its start); otherwise one word, or two when you typed a capitalised surname; family words like "mom" are names
of their own. "text mom" asks "What should the text to Mom say?" and takes your next typed or spoken answer ("cancel"
or "never mind" drops it). "tell me a joke", "message me later", "what's the text on this page", "text recognition" (no
message, and no contact or family word by that name) and "write a message to Bob about the rent" (a description, not
the text) stay with the model. Two- or three-word names must be a contact's whole words ("text Dan Can you…" is Dan).
On the lock screen nothing is read or asked: Robot says to unlock first.

When lookup_contact finds no one and Cheogram is the texting app, the model is told
`not_in_phone_contacts_may_be_in_cheogram` and the card offers **Open Cheogram**; when send_text is offered, the system
prompt says to text people by name even if they aren't in the contacts.

### Survive restarts

At every boot Android keeps a non-system assistant only if Settings.Secure `voice_recognition_service` names an
installed recognizer (`VoiceInteractionManagerService.initForUser`); empty, it picks a system app and Robot is off.
GrapheneOS has no screen to set it. Settings › Advanced › **ADB fixes** lists the one-time commands with their status
and a Copy button. With a computer (Settings › About phone, tap Build number seven times; Developer options, USB
debugging on):

```
adb shell settings put secure voice_recognition_service app.heyrobot/.assistant.RobotRecognitionService
adb shell pm grant app.heyrobot android.permission.WRITE_SECURE_SETTINGS
```

Turn USB debugging off afterwards. With the second one, Robot keeps that key set itself (only that key: the assistant
itself belongs to RoleManager): at boot (also before the first unlock; the boot receiver is direct-boot aware), when
the app or Assistant setup opens, and when the assistant starts, if it holds the assistant role and no other installed
recognizer is set. Each write goes to the voice log. Assistant setup shows "Survives restarts".

The phone region for numbers without a country code now comes from the SIM, then the network, then the language
setting, unless one is set in Settings. Earlier versions saved "CA" whether it was chosen or not, so a settings file
from before 0.2.3 with "CA" reads as unset (once: the settings file now carries a schema version); any other saved
region is kept.

### On the phone (0.2.3)

1. Settings › Voice › Barge-in test with "Echo cancellation": run it and say "stop" a few seconds in; then the wake
   phrase. Then Silent run. Repeat with "Off" and "Always echo cancellation", then with "Call audio". Note which mode
   hears you and the false counts.
2. Ask something with a long spoken answer; say "stop" while Robot talks; then "hey robot" while she talks (a new
   question should start).
3. While Robot talks with the panel open, press volume down: she stops and the volume doesn't change. With the panel
   closed (or the screen off), volume down changes the volume as usual.
4. Settings › Voice › Wake words: add "robot" (see the warning and Low), and "hey computer". Open Test wake words and
   say each one, near and across the room; then say other words to see what triggers by accident. Turn "hey robot"
   off and check it no longer wakes Robot (no restart needed).
5. After any "she didn't respond": open Settings › Voice › Voice log right away and take a screenshot.
6. Typed and spoken "text mom I'm running late": a card for Mom with the message. Then "text mom": Robot asks what
   the text should say; answer it (and once, say "cancel"). Then text someone who is only in Cheogram: "Choose in
   Cheogram". Ask "look up Bob" for someone only in Cheogram: the card offers Open Cheogram.
7. Run the two ADB fixes, restart the phone: Robot is still the assistant, and the voice log shows no write (already
   set). Settings › Advanced › ADB fixes shows both done.

## People, notes and calendar (0.2.0; fixes 0.2.1; Cheogram-only people 0.2.2)

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
  `PhoneNumberUtils.formatNumberToE164` and the phone region (the phone's own by default). Without Cheogram, or by choice, the SMS
  app opens with `smsto:` and `sms_body`. With both Cheogram builds installed, the F-Droid build
  (`com.cheogram.android`) is always used. A JID with a character Cheogram would cut at (`" & ' / : < > ? # %`) is
  refused, and a contact's XMPP address is offered before its numbers, never instead of them.
- **Cheogram can keep its own draft.** If that chat already has a draft, Cheogram keeps it and drops Robot's text (it
  shows "already drafting message"); if the number is a contact in several Cheogram accounts, it shows a search first
  and the text is lost. Robot can't see either; the card says "If that chat already had a draft, Cheogram keeps the
  old one."
- **People only in Cheogram (0.2.2).** Cheogram keeps its roster in its own database and only reads the phone's
  contacts, so someone who is only in Cheogram isn't found. With Cheogram as the texting app, a text to a name that no
  contact (or no contact with a number or XMPP address) fits shows "No contact matches “Bob”. Choose who to text in
  Cheogram" with the message and a **Choose in Cheogram** button (always a card; Unlock on the lock screen). It opens
  Cheogram's share screen (`ACTION_SEND` text/plain with the message, to the installed Cheogram): pick the person
  there, and the message is in their chat's box. The model is told `draft_opened_not_sent`. With the SMS app it stays
  "not found". To have Robot find them directly, open the contact in Cheogram, then ⋮ › "Add to address book": that
  saves the XMPP address in the phone's contacts (an IM entry, protocol Jabber), which Robot reads.
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
  level, so reading more doesn't start asking. The system prompt says tool results
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
URI made safe by `Uri.toSafeString()`. That masks `mailto:` and `smsto:`. Older Android printed the whole `xmpp:` link
(the gateway JID with the phone number, and the once-decoded message) to logcat; since Android 14 `toSafeString`
prints only the host and port for schemes other than the telephony ones (AOSP's android14 to android16 branches), so
neither should appear. Which one this phone does is not checked yet (step 11
below). Robot can't avoid it either way: Cheogram only takes the text in the link. The log is readable only with adb
or by apps holding READ_LOGS.

**verify_apk.sh** checks, besides the signer, version, SDK levels, processes, permissions, native libraries and
assets, that MainActivity and the Robot Notes entry stay in the main process, that the exported components are
exactly an allowlist (the app, Robot Notes, the three assistant services and AndroidX's profile installer), and that
no `ui/`, `sys/`, `boot/` or `RobotApp` class in the dex refers to sherpa-onnx, LiteRT or the assistant, voice and
brain packages. Since 0.3.0 also: no main-process class refers to the Anthropic SDK, OkHttp or the `:claude` module while
the SDK's client is packaged; the key window runs in `:voice` and isn't exported; the Claude app's `<queries>`; INTERNET
stays the only network permission; no accessibility service; nothing shaped like an API key anywhere in the APK.

### On the phone (0.2.0, with the 0.2.1 and 0.2.2 fixes)

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
13. With spoken replies on, ask "tell me a joke", then "what time is it", then "wake me in two hours": no reply starts
    with the time or a bracket, on screen or aloud; the second says the right time and the alarm is two hours on.
14. Text someone who exists only in Cheogram: tap "Choose in Cheogram", pick them, the message is in the box. (Tip: in
    Cheogram, open the contact, then ⋮ › "Add to address book". That saves their XMPP address into the phone's
    contacts, as an IM entry with protocol Jabber, which Robot already reads.)

## Voice (0.1.1; memory rule 0.1.2; fixes 0.1.3)

- Speech to text: NVIDIA Parakeet TDT 0.6B v3 int8 (`SherpaSpeechToText`). Push-to-talk, or right after "hey robot";
  a Silero VAD of its own ends the question after 0.5 s of silence (20 s max speech, 30 s cap), or the button does.
  The words appear while you talk (the buffer is decoded again about every 0.7 s, one decode at a time; can be
  turned off). Audio stays in memory.
- Text to speech: Kokoro v1.0 fp32, voice "Heart" (`af_heart`) by default, "Siwis" (`ff_siwis`, French) for later;
  Kitten nano 0.8 fp32 as the small fast voice; the phone's own voice as the fallback (`VoiceOutput`). The first
  piece of a reply is short (5+ words to the first comma, or 12 words) so audio starts fast; the rest goes
  sentence by sentence into a streaming AudioTrack. Tap the conversation, press volume down, or say "stop", while it speaks: the rest of
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
