# Security policy

## Reporting a security problem

Please report it privately: use **Report a vulnerability** on this repository's Security tab (GitHub's private
vulnerability reporting), or email hey@robot-app.ai. Please don't open a public issue.

Include:

- Robot's version (Settings › About) and how you installed it;
- your phone model and Android build (GrapheneOS or another);
- what you did, what happened and what you expected;
- if it helps, a copy of Settings › Voice › Voice log (it holds codes and numbers only, never what you said).

## What to expect

- An answer within 7 days.
- For a serious problem, a fix or a plan within 30 days, and a patch release as soon as the fix is ready.
- Credit in the release notes, if you want it.

Only the latest release is supported. There is no bug bounty.

## Scope

**In scope:** the Robot app (its code in this repository) and its build and release scripts (`robot/tools/`), including:

- anything that lets another app or a website reach Robot's data, its assistant session, its model process (`:brain`)
  or its stored API key;
- content Robot reads (the screen, a photo, a message, a tool's result, a web answer) making Robot act without the card
  its settings promise ("prompt injection" that gets past Settings › Safety & privacy);
- Robot sending something online that its settings and PRIVACY.md say it doesn't;
- a model, voice or data file being used without its pinned SHA-256 check;
- a Termux command running without the card your settings promise, or a web search sending what Robot remembers, a
  message's words or anything but your search words;
- an update installed without its checks (the release's `SHA256SUMS`, Robot's package, a newer version, Robot's signing
  key), or the updater's access token sent anywhere but api.github.com;
- what you say or type ending up in a log or on storage;
- a backup file opened without its passphrase, or a restore that writes something its checks should have refused;
- a file in Robot Files read while the phone is locked, or by another app; the `:files` process reaching anything but
  the bytes it was given; the share target taking a file it should refuse; hidden drawers shown without the phone's PIN,
  pattern or password;
- Lists: a list read, changed or ticked while the phone is locked beyond what Settings › Lists allows,
  or during a phone lock for someone else; the list widget's components letting another app change a list, or reading
  anything but a tick of an item on that widget's own list; an item reaching the model as instructions, or a helper
  without your tap;
- Skills connections: a connection's key sent anywhere but the address it was saved for, or over
  plain http; a request reaching a host of the other kind (home or internet) than its lookup allowed, or following a
  redirect; a connection step running on a locked phone, from a routine, a helper, a server's answer or the model
  alone, or without the card its effects call for; a step sending something other than what its card showed; a
  hand-off opening an app signed by someone else, or carrying anything but plain words, numbers and switches.

**Out of scope:** someone who knows your PIN showing travel mode's hidden drawers (it's a privacy screen, not
encryption), the ways around Phone lock that Settings › Phone lock › "What a lock can't stop" already lists (safe
mode, a computer with USB debugging, uninstalling Robot and the like: at Standard strength it is a habit, not a wall),
the models' answers themselves (what a model says, or refuses to say, is not a vulnerability unless it
gets past Robot's cards), what your own Termux scripts do, and third-party services and apps (Hugging Face, Mozilla's
model storage, Open-Meteo, OpenStreetMap's Nominatim, the search services, the helpers' providers,
Termux, your messaging, email and maps apps, GitHub): report those to them. A problem in a library Robot ships
(sherpa-onnx, ONNX Runtime, LiteRT-LM, llama.cpp, slimt, the helper's Java SDK, OkHttp, Jackson, jsoup, and for Robot
to Robot Tor, lyrebird, libsignal, Tink and the UnifiedPush connector) is welcome here
too, so Robot can update it; please also tell its maintainers.

## How Robot is built to be safe

- The AI model runs in its own process (`:brain`), which has no network code; voice, tools and online helpers run in
  `:voice`; the main app never loads an engine or a helper's SDK. Tests check that from the sources and the merged
  manifest. (Android grants the network per app, not per process: the separation is in the code, and the tests keep it.
  The same holds for Install unknown apps once you allow it: only the main process's updater installs, and only Robot's
  own update.)
- The model only proposes actions. Robot's approval rules decide, and an action that writes or leaves the phone after
  Robot read untrusted content (the screen, a message, a web page, a command's output) asks you first. Web search and
  Termux are off until you set them up, and every Termux command shows its card first unless you chose otherwise for
  your own script.
- Every downloaded file is pinned to a commit, a size and a SHA-256, checked before it is loaded. A model file you import
  is untrusted input: Robot checks its header before loading it, and runs it on the TPU only after you allow it. A
  `.gguf` file runs in llama.cpp inside an isolated process with no permissions, whose answers Robot reads only as plain
  numbers and bytes in a fixed layout (any other answer ends that process, as a crash would); a `.litertlm` file's
  container (its header, section table, section types, sizes and the file's size) is checked strictly in Kotlin first,
  and anything odd is refused before LiteRT-LM reads it.
- API keys (and the updater's access token) are encrypted with a non-exportable Android Keystore key. Backups and device
  transfer are off. Robot's own backup (1.1, Settings › Back up and restore) is one file you save yourself, encrypted
  with your passphrase (Argon2id, then AES-256-GCM in chunks that are each checked); a restore reads and checks the whole
  file before it writes anything, and writes each category whole or not at all. Keys and your voiceprint are in it only
  if you tick them, and a file you don't mark as your own restores with the safer settings.
- Robot Files (1.1) seals every file, thumbnail and each write of its catalogue with a fresh random 256-bit key
  (AES-256-GCM in 64 KiB chunks, the backup's own code, with a 96-byte header every chunk authenticates). That key is
  wrapped by `robot_files_v1`, an AES-256-GCM Android Keystore key made with `setUnlockedDeviceRequired`, never
  exported, with the purpose and the file's id as additional data, so a wrapped key can't be moved to another file. Each
  process drops its open store at every screen-off and reopens it only after an unlock; the Files screens are
  `FLAG_SECURE`, and nothing of Robot Files is shown, said or searched on a locked phone or during a phone lock. Deleting
  overwrites a file's wrapped key before the file is unlinked.
- Every file is decoded in `:files`, an `android:isolatedProcess` service with no permissions, not exported, bound with
  `bindIsolatedService` and a fresh instance per link. It proves it runs under an isolated uid before anything is sent,
  speaks a small fixed binary format that Robot decodes strictly (fuzzed with 20,000 mutations), and gets the bytes in
  a read-only shared memory region. Inputs are capped (64 MiB, 32,768 px a side, 260 MP, 3 pages rendered) and each call
  has a 20 s watchdog. The type comes from the file's first bytes, never its name or the sender's claim; archives,
  HTML, SVG, scripts, a ZIP appended to a picture or a PDF, and markup deep in a text are held as dangerous: never
  parsed, never quoted to the model. A file's text reaches the model framed as information, never instructions, and
  amounts, dates and taxes are read again by code.
- The share target (`FilesShareActivity`) reads nothing before you tap Add. It refuses `file:` URIs, other users' and
  Robot's own providers, and any share while the phone is locked or a phone lock is on, checked when it opens and again
  at the tap; it ignores touches while another app draws over it, and names the sending app only when Android confirms
  it. Copies for Share and Open go through Robot's own provider with a one-time read grant on one file, revoked when you
  come back.
- Files in backups (1.1): each file's bytes go inside the backup's encryption, never sealed with the old phone's key.
  Verify recounts every file's size and SHA-256 against the backup's catalogue. A restore seals each file again under
  the new phone's key in a staging folder that counts as sealed from the start, checks each restored file's type again as
  intake does, and moves them in only at the end, in steps that can be repeated after a crash. `RestoreSafety` keeps a
  backup that isn't marked as yours from turning Robot Files, reading text in images or the brief's Files lines back on,
  sends its files to Inbox without tags, people or reminders, and keeps travel mode on with the backup's hidden drawers
  added, even from your own backup. While travel mode is on, backups and restores leave Robot Files out.
- Lists are sealed the way memory is: one file, AES-256-GCM by `robot_lists_v1`, an Android
  Keystore key made with `setUnlockedDeviceRequired`, in the TEE, never exported, with its own frame and additional data
  (`robot-lists-v1|<file name>`), so a memory file or the journal can't stand in for the lists. Adds said while the phone
  is locked wait in a journal sealed by a second key, `robot_lists_lock_v1`, which works while locked and exists only
  while "Add to lists while locked" or "Hear and tick on the lock screen" is on; the journal is never read to answer and
  is merged into the lists by item id at unlock. Turning a switch seals both files under the new key, writes them, and
  deletes the old key last, never one that a copy kept aside still needs. Nothing is written over a file Robot couldn't
  read, and a Keystore error that may pass reads as "not now", never as "gone". Both processes drop the lists from RAM at
  every screen-off. No tool reads a list, and an answer from your lists is replayed to the model framed as information,
  never instructions, so later actions in that conversation ask first; it goes to a helper only on a tap.
- The list widget has two components another app can reach, both checked by tests and `tools/verify_apk.sh`:
  `ListsWidgetProvider`, exported as every widget provider must be with the `APPWIDGET_UPDATE` filter alone, reads
  nothing from what it is sent, not even widget ids, and only redraws the widgets Android lists for Robot (a burst of
  broadcasts is one redraw); `ListsWidgetSetup`, exported with the `APPWIDGET_CONFIGURE` filter alone, closes unless the
  id it is given is one of Robot's widgets, is `FLAG_SECURE`, never shows over the lock screen and saves only which list
  a widget shows, after your tap. Ticks come through `ListsTickReceiver`, which isn't exported: only the widget's own
  template, explicit and with its action set, reaches it, and it takes a tick or untick of an item on that widget's own
  list, never during a phone lock, while locked only with "Hear and tick on the lock screen" on. Every other
  PendingIntent the widget uses is immutable and has an identity of its own.
- Travel mode's Off, Unhide and Show take the PIN, pattern or password only, through `BiometricPrompt` with
  `DEVICE_CREDENTIAL` alone (hence `USE_BIOMETRIC`, a normal permission); if that screen can't be shown, nothing changes.
  A damaged or newer Files settings file fails closed: travel mode on, every drawer hidden.
- `RegexPortabilityTest` (1.1) checks every static regex in Robot's classes for the patterns Android's ICU regex engine
  is known to refuse and the JVM accepts. In 1.0.x one such pattern made web search fail on every phone; the phone found
  a second, in Robot Files' reading, before 1.1.0 shipped, and the test now knows both kinds. Robot's logs name a
  failure's causes by class, never their messages.
- The GPU check (1.1) runs in `:voice` through `GpuCheckService`, not exported and bound only with Robot's own
  signature permission; its three questions are fixed and never leave the phone.
- Phone lock (1.1) uses one accessibility service, in its own process (`:lock`), declared for window-state events only
  and without access to window content. Tests and `tools/verify_apk.sh` check that it is the only accessibility service,
  that only the system can bind it, and which paths reach that process.
- Updates come only from the repository's GitHub releases, over HTTPS to GitHub's hosts, and are installed only after the
  file matches the release's `SHA256SUMS` and Android reads it as Robot, newer, signed with Robot's key (or a key rotated
  from it with APK Signature Scheme v3). Android's own window confirms the install unless you turn that off. `SHA256SUMS`
  comes from the same release, so it guards against a damaged download, not against someone who can publish to the
  repository: the signing key is what does that.
- Skills connections. Every request goes through one client, `ConnHttp`, in `:voice`: a fresh client
  per call, no redirects, retries, cookies, cache or proxy, Android's system certificates only, a 10 s limit and a
  256 KiB cap on answers. Every lookup and the socket actually connected are classed as your home network (private,
  loopback, link-local, Tailscale's 100.64/10, unique-local IPv6) or the internet, and anything else is dropped; plain
  http may reach only home addresses, on every call, so a name that starts pointing outside your network is refused.
  https may be either, since TLS's name check stops a rebinding. Any connection that holds a secret (a token, a
  password, a header's value, an ntfy topic, a webhook address that is itself the key) is https only, checked when you
  type it, before a client exists and again when the secret is added, last, by the client. Keys are encrypted per
  connection with their own non-exportable Android Keystore key, decrypted only in `:voice` just before a request,
  pasted only in a `FLAG_SECURE` window, never logged, never in an intent, History or a preview, deleted when you change
  the address or remove the connection, and in a backup only with Keys and private codes and a strong passphrase,
  restored only from your own backup to a connection with the same address. Tests pin where the client and the keys may
  be named.
- A connection step is a typed call through the same approval gate as every action, on two internal tools no model is
  ever told about. It never runs on a locked phone or from a routine, and only a skill you run can start one. The first
  request to a new host, anything that locks, unlocks or opens, deletes, can spend money or runs code Robot can't see,
  and any value Robot's model wrote or read from an answer, take a tap, never a spoken yes. A server's answer is
  content: only the outputs you declared are kept, typed, and its free text never reaches a condition, a script's
  argument or a send without your tap. At the run, the request is built again and sent only if it matches what the
  card showed. Values are encoded for their place (JSON, a path or query, one argument each for Termux, a URI slot); a
  webhook's text body goes only as plain text or a form. A hand-off is an explicit intent to the package you picked,
  whose signer is checked again just before it opens, with plain extras only (no URI grants, `ClipData`, parcels or
  nested intents).
- Logs hold codes and numbers only; audio is never written to storage.

Known limits: the TPU runs Google's proprietary vendor library on the phone; ONNX Runtime and, for Robot to Robot, Tor
and libsignal come as other people's prebuilt files, pinned by SHA-256 and trusted from their publishers' releases
(Robot builds its sherpa-onnx library itself, from pinned source with its own patch to espeak-ng, and lyrebird from
pinned source); whatever you send to an online helper is
handled under that provider's terms. A `.litertlm` file you import yourself is read by LiteRT-LM inside the `:brain`
process, which runs with Robot's own permissions (LiteRT-LM needs the GPU and the TPU, which an isolated process can't
reach): Robot's strict check covers the file's container, not the TFLite models, weights and tokenizer inside it, so a
file crafted against a bug in LiteRT-LM could act as Robot. Robot warns you before you pick one: only import files you
trust. Robot's own models are pinned by SHA-256 and aren't affected.

Robot Files' known limits (1.1): travel mode's PIN-only screen has been tested on a computer only, not yet run on a phone
by a person. Travel mode is a privacy screen, not encryption, and the names of hidden drawers are kept in
Robot's private storage without Robot's own encryption. Share, Open and Export write a plain copy: Robot deletes its
temporary one when you return, at screen-off or after 15 minutes, and at the next start after a crash. The shared
memory regions between Robot and `:files` aren't sealed against resizing yet, so a compromised `:files` could crash the
app or the panel; that's a denial of service, not a leak, and a phone check decides the fix.

Skills connections' known limits: its checks have run on a Pixel 8 against stand-in Home Assistant and ntfy servers, but
not every step has been run by a person: a real routing server, a connection over mobile data and the Termux step are
still to do. A plain http address on your
own network can't be told apart from the same address on someone else's Wi-Fi, so a webhook without a secret could
send its request to whatever that network answers: use https or your server's Tailscale address. Robot can't see what
a Home Assistant switch or button is wired to, or what a webhook does on the server: a switch that opens a garage door
counts as a change at home, and a GET webhook as a read, unless you make it a cover or a lock in Home Assistant, or
mark the webhook "It locks, unlocks or opens something". Anyone who can make your Home Assistant's devices' names can
put words in what Robot shows you; they are shown as information and never act.

Robot's voices for other apps (Robot as the phone's text-to-speech engine, Settings › Voice) read text that any app can
send, and they read it in the `:voice` process, beside the assistant, not in an isolated process: the speech library
(sherpa-onnx, with espeak-ng inside it) opens its voice files and espeak-ng's data by name, which an isolated process
can't do. Until a build of that library can load them from files Robot opens for it, a filter stands in front of it.
Every text that reaches the library, Robot's own or another app's, first loses its control, format and private-use
characters and anything past 40 characters in one word. Another app's text also loses its tags and its letters outside
the Latin alphabet, and long sentences are split. Turning "Robot's voices for other apps" off takes Robot out of
Android's list of engines.

Robot to Robot: Tor runs as a child of the `:link` process, with its control and SOCKS ports on unix
sockets in Robot's private storage, never a TCP port. With a Tor bridge on (Settings › Robot to Robot › Tor bridges, off
by default), Tor also starts lyrebird (0.9.0, built from source by `tools/build_lyrebird.sh`, pinned by SHA-256 like Tor itself) for the bridge's
transport, and lyrebird listens on a loopback TCP port while that bridge is in use. Another app allowed the network
could find that port, which tells it you use a Tor bridge, connect to it and send its own traffic out through the bridge,
which Android then counts under Robot's uid; it never sees Robot's traffic. This is accepted, because the bridge program's
listener can't be changed from here (PRIVACY.md says the same). Bridge lines you
paste are checked word by word, so a line can't add any other Tor setting.

Robot to Robot's push app: the app you choose in Settings › Robot to Robot › Push app carries each push to Robot, and it
sees only ciphertext. But an app you chose can still forge a push event of its own or hand Robot's connector to another push
app, so choose one you trust; only your tap saves the choice. Robot refuses a push address that points at a local network or
at your own phone, and treats one it can't accept as no address. Each pairing takes at most two connections coming in: a new one closes the
ones that haven't proven themselves first, then the oldest, so someone who only saw a token on the network can't push out
your newest proven one.

## Dependencies

Every dependency is pinned (versions, and SHA-256 for native libraries, models and data). They are reviewed for security
advisories at least once a month; a high or critical advisory in a part Robot uses gets a patch release as soon as a fix
is available.
