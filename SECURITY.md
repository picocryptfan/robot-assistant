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
  hand-off opening an app signed by someone else, or carrying anything but plain words, numbers and switches;
- Today: a private event read or written while the phone is locked, or reaching the phone's calendar or Android's
  backups; a news feed's bytes read anywhere but the `:feedparse` process, or that process reaching the network or your
  data; a feed fetched more often or by a weaker route than its setting allows, or a request going from your network to
  the internet or back; a feed address that holds a token sent anywhere but over https with a valid certificate (or to a
  server on this phone), or showing up in a log, History, a backup or what the model reads;
- Skills 1.3: an MCP server reached through a public address, or a token sent anywhere but its own server; a server's
  words (a tool's title or description) reaching a model as instructions; a tool that changed staying on, or a call whose
  arguments don't match the tool as you turned it on; a plan, a draft or an imported skill running, or saved turned on,
  without the card its steps call for; a helper receiving anything a plan's or draft's card didn't show, or without your
  tap; a signature accepted that doesn't match, or a skill file or trusted key restored from a backup you didn't mark as
  your own.

**Out of scope:** someone who knows your PIN showing travel mode's hidden drawers (it's a privacy screen, not
encryption), the ways around Phone lock that Settings › Phone lock › "What a lock can't stop" already lists (safe
mode, a computer with USB debugging, uninstalling Robot and the like: at Standard strength it is a habit, not a wall),
the models' answers themselves (what a model says, or refuses to say, is not a vulnerability unless it
gets past Robot's cards), what your own Termux scripts do, and third-party services and apps (Hugging Face, Mozilla's
model storage, Open-Meteo, OpenStreetMap's Nominatim, the search services, the news feeds you add, the helpers' providers,
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
- ACCESS_NETWORK_STATE is a normal permission, granted at install, that lets Robot see what network the phone is on; it
  sends nothing and reads no network's name. It has three readers: the scheduled jobs that wait for a network (the
  update checks, since 1.1, which Android 14 and later won't schedule without it); the downloads (1.4), where Robot asks
  whether the phone is roaming to say why a held download waits, and where "Wait for Wi-Fi" is Android's own hold, not
  something Robot reads; and Robot to Robot (`:link`), which looks at the networks the phone is on, and its own
  addresses on them, to listen only on Wi-Fi, Ethernet or a VPN and to tell a Private pairing your new address.
  `ProcessIsolationTest` and `verify_apk.sh` check that the permission is declared and that no other network permission
  than INTERNET sends anything.
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
- Every file in Robot Files is decoded in `:files`, an `android:isolatedProcess` service with no permissions, not exported, bound with
  `bindIsolatedService` and a fresh instance per link. It proves it runs under an isolated uid before anything is sent,
  speaks a small fixed binary format that Robot decodes strictly (fuzzed with 20,000 mutations), and gets the bytes in
  a read-only shared memory region. Inputs are capped (64 MiB, 32,768 px a side, 260 MP, 3 pages rendered) and each call
  has a 20 s watchdog. The type comes from the file's first bytes, never its name or the sender's claim; archives,
  HTML, SVG, scripts, a ZIP appended to a picture or a PDF, and markup deep in a text are held as dangerous: never
  parsed, never quoted to the model. A file's text reaches the model framed as information, never instructions, and
  amounts, dates and taxes are read again by code.
- The one exception is audio you share with Ask Robot (a voice message) or import for the Transcription check. It is not
  decoded in `:files`, because an isolated process can't reach Android's media services (an isolated app may talk to
  only three system services, a rule Android's own build enforces). It is read into memory in `:voice`, at most 25 MB,
  and Robot checks its type from the first bytes. Android's own media services (`media.extractor` and `media.swcodec`,
  each in a sandbox of its own) then parse and decode it, in memory, with no file or link kept. Robot itself reads only
  the format's two numbers and the sound, both capped.
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
- The logbook is sealed the way Notes are: files/logbook/log.enc, AES-256-GCM by `robot_logbook_v1`, an Android
  Keystore key usable only while the device is unlocked, with the file's name and header in the additional data
  (`robot-logbook-v1|<file name>|`, magic "RLOG"). An entry logged while locked is one file, `<id>.locked.enc`, sealed by
  `robot_logbook_lock_v1` (usable while locked) and merged at the next unlock; the journal is never read to answer. A
  file that doesn't open is kept aside (`.bad`), never deleted, and a key Android invalidated is replaced. The router
  takes nothing during a phone lock for someone else, and the voice log keeps only the kind of turn and its result.
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
- Private events (Today's Calendar tab) are sealed in one file by `robot_calendar_v1`, an AES-256-GCM Android Keystore
  key made with `setUnlockedDeviceRequired`, so Robot reads and writes them only while the phone is unlocked. The file
  names its key, which is checked and bound in as additional data. They never go into the phone's calendar or Android's
  backups, and come back from Robot's backup only when you mark it as your own. A file Robot can't read is never
  written over: Start over keeps it aside (`events.enc.bad`). Robot changes only private events and the calendar events
  it added itself.
- Wikipedia on this phone (1.4) is read in `:wiki`, an `android:isolatedProcess` service with no permissions, no network
  and none of Robot's files. A downloaded ZIM file (checked against its SHA-256 pin before it's used) reaches it only as an
  open file descriptor, and `librobotzim.so` (libzim with Xapian, Zstandard, liblzma and ICU, built from pinned release
  archives by `tools/build_libzim.sh`; it exports only its own JNI functions) parses it there. It is 1.4's largest new
  native surface, so it sits in a process that can reach nothing of yours. `tools/verify_apk.sh` holds the APK to the
  pinned build and to five isolated services.
- The report from Report a problem (1.4) is sealed on the phone (HPKE) to the developer's public key, together with its
  screenshot, into one file only the developer's private key opens. It waits in Robot's cache for up to 24 hours,
  and nothing is sent until you hand it to the app you pick. The card that shows the turn and the screenshot is a
  secure window (`FLAG_SECURE`): kept out of screenshots, screen recordings, casting and Recents.
- News feeds on Today are read in `:feedparse`, an `android:isolatedProcess` service with no permissions, no network and
  none of Robot's files, not exported, bound with `bindIsolatedService`: a fresh instance for each round of fetching and
  after any failure, so a crash or a hostile feed takes down only that feed. It proves an isolated uid before it's sent
  anything, gets each feed's bytes in a read-only shared memory region of exactly their size, and answers in one frame
  that Robot checks and cleans. The fetch itself (the main app or `:voice`) is https, plain http only on your own
  network, at most 512 KB and 15 seconds a feed, each request all on your network or all on the internet. A feed
  address that holds a token is kept encrypted under a Keystore key of its own (`robot_feed_<id>`), shown masked, and
  left out of logs, History, backups and what the model reads. With "Only through Tor" every feed goes through Tor,
  each server on its own circuit, and nothing is fetched while Tor can't connect.
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
  Since 1.3.2 a home address (other than this phone's own) goes only over Wi-Fi or Ethernet, or over a VPN that is up
  and whose routes cover it, on a socket bound to that network so it can't fall back to mobile data; a 100.64/10
  address only through such a VPN, and only by a route at least as long as /10 (a VPN that carries everything doesn't
  count for 100.64/10, even written as shorter routes such as 0.0.0.0/1 and 128.0.0.0/1, so a full tunnel on mobile
  data doesn't take plain http to the VPN provider's network). Only the VPN that applies to Robot counts, not a work
  profile's or one that leaves Robot out. When that VPN doesn't route a home address and Wi-Fi or Ethernet holds it,
  the call goes unbound and the phone sends it outside the VPN, as before 1.3.2 (most VPN apps can't be bypassed, so a
  bound call would fail); once it connects, Robot checks it left from that Wi-Fi or Ethernet network's own address,
  and if it didn't (the phone sent it on mobile data, as it can when that Wi-Fi has no internet, whatever networks the
  VPN declares) closes it before anything is written, so only the connection's opening and closing packets went out,
  no data. When the VPN rides on mobile data alone, or blocks the address (an unreachable route), the call is refused
  before that. A refused call gets a line saying why and sends nothing, so What went online shows no
  row for it. A VPN that carries everything and doesn't leave your home network out also carries home addresses:
  plain http to a 192.168.x address goes into the VPN, as it did before 1.3.2. The same holds for helpers on your
  network, feeds on your network and Robot to Robot's direct connections.
  Four https paths aren't held to this yet and rely on TLS alone: your own nearby-search server, a feed's https
  address, a cloud helper whose name resolves to a home address, and a meek or WebTunnel bridge of your own given by a
  name that resolves to a home address (only a bridge given as a home address goes through the relay below, so lyrebird
  reaches that one directly); on mobile data a stranger could see the attempt
  and the host name, never what's sent. A Tor bridge of your own at a home address is held to it too (1.4): while one
  is saved, Tor and lyrebird reach every saved bridge except Snowflake ones through a relay inside Robot (a SOCKS5 proxy on a loopback port, with a
  username and password made fresh at each Tor start, that connects only where your saved bridge lines lead), so the
  home one goes out under the same rule and on mobile data nothing is sent to it. Every address lyrebird can dial
  from a line counts (meek's targets list too), and a line Robot can't read for sure (a port given by name, say)
  starts the relay as well, which lets nothing through for it. Another app on the phone could keep the relay's 16
  handshake slots full (each is closed after 10 seconds without the password): Tor then reaches no bridge, but nothing
  is sent anywhere. Snowflake lines among your own bridges
  aren't used beside it (lyrebird can't send them through the relay), and the bridges screen says so; choosing
  Snowflake itself is unchanged.
  https may be either, since TLS's name check stops a rebinding. Any connection that holds a secret (a token, a
  password, a header's value, an ntfy topic, a webhook address that is itself the key) is https only, checked when you
  type it, before a client exists and again when the secret is added, last, by the client. A token (an MCP
  server's bearer token, a feed's) goes only over https with a valid certificate, even on your own network or at a
  Tailscale address. The one exception is a server on this phone, which the page marks "Not encrypted (on this phone)". Keys are encrypted per
  connection with their own non-exportable Android Keystore key, decrypted only in `:voice` just before a request,
  pasted only in a `FLAG_SECURE` window, never logged, never in an intent, History or a preview, deleted when you change
  the address or remove the connection, and in a backup only with Keys and private codes and a strong passphrase,
  restored only from your own backup to a connection with the same address. Tests pin where the client and the keys may
  be named.
- MCP servers (1.3) are connections of a kind that is never on the internet. The address must be on your home network,
  a Tailscale address (100.64/10) or this phone's loopback, checked when you type it and again on every lookup and on
  the socket really connected, https or not; a server on the phone needs a token, since any app on the phone can reach
  loopback. Same client as above: no redirects, a 10 s limit on a tool list and 30 s on a call, answers cut at 1,500
  characters. Every tool starts off. Turning one on pins a SHA-256 of each part of it (title, description, input and
  output schemas, annotations); on every list a tool that differs is turned off and says what changed, and a server that
  reports another name at the same address turns all of them off. Arguments are checked against the pinned input
  schema before they are sent, never the one a later list gives, and a call whose card can't show them in 1,200 characters
  is refused. A server's words are shown in Settings and never given to a model. A tool's own claim that it only
  reads counts only when you also tick that it does; a tool that spends money, touches security or tells people always
  takes a tap.
- Plans and drafts (1.3). Robot's model proposes a plan or a drafted skill, and Robot's code checks every step before
  you see it: a drafted skill's step that doesn't exist or isn't one of your connections is left out and named, a plan
  that can't be checked is refused whole, and a step that messages, spends or touches security always gets its own
  card. A plan or draft made after untrusted content (a web page, a message, your screen) takes a tap, never a spoken yes,
  and a drafted skill is saved at Ask first with no trigger. Only a helper you picked is asked, after a card shows exactly
  what would go (a fixed instruction, the tools that are on, the date, time and language, your words this turn; not your
  contacts, notes, calendar, conversation or other skills) and you tap Send; its answer is checked like the model's. In
  car mode the card waits until you have stopped, and gives up after 30 minutes.
- Sharing skills (1.3). A skill file is signed ES256 (ECDSA on P-256 with SHA-256, one low-s form) by a key in the Android
  Keystore (`robot_skill_signing`), never exportable; the phone's own fingerprint is the SHA-256 of its public key. The
  file is at most 512 KiB, and a connection's address and key, a skill's history and its level never go in it. Keys you
  trust are a name and a fingerprint, at most 20 (`skill_keys.json`), and change only the words on an import: whoever
  signed it, an imported skill always starts off, and a file changed after it was signed, newer, unsigned or holding a
  key-shaped value is refused or marked.
- What a backup holds of this (1.3). Your skills, your routines' skills and your plan settings go in with the Skills tick,
  and your connections without their tokens with Connections. Connection tokens and helper keys go in only with Keys and
  private codes and a strong passphrase. Trusted keys and each MCP tool file (`skill_trust`, on the Skills row) are never
  restored from a backup that isn't marked as yours, and a tool file only for a connection that restore brought back. Your
  signing key is never in a backup: it cannot leave the Keystore, so a restored phone signs with a new key. The helper
  budget's spend, helper facts and `skills_state.json` are not in it.
- The apps a skill may open without asking (1.4). A backup keeps them apart from `skills.json`, in `skill_opens.json`
  (the `skill_opens` category, on the Skills row; skill ids and package names only, at most the skills' own ticks). A
  file that breaks a rule is refused whole. They go back onto their skills in the Skills step and only from a backup that
  is marked as yours: someone else's backup brings none, and its skills arrive off. A 1.3 Robot skips `skill_opens` by
  name and restores every skill, without the ticks.
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

MCP servers' known limits: Robot's client is tested against stand-in servers; a run on a phone against a real Home
Assistant is still to do. A tool's description, its read-only claim and its schemas are the server's own words: Robot pins
them and shows them, but cannot tell whether a tool does what it says, so a tool that spends money, touches security or
tells people is marked by you. A token is never sent over plain http, except to a server on this phone, where nothing crosses a network. A server's
self-reported name is a pin, not proof of who it is. A skill signature shows which key signed a file, not that the
skill is safe: that is why an import always starts off and shows its steps.

History: each conversation is sealed on its own (AES-256-GCM, the file's name and header in the additional data) under an Android
Keystore key of History's own, `robot_history_v1`, which needs the phone unlocked. A conversation saved while the phone is
locked (over the lock screen) is sealed under a second key, `robot_history_lock_v1`, which doesn't, and is moved under the
first at the next unlock, as Lists' journal is; so what was said over the lock screen since the last unlock is protected
by a key usable while locked. In a conversation started while unlocked, only the turns since the lock go under the
second key (when Robot can't tell which turns are new, the whole conversation does). Conversations saved by an older Robot are sealed at the first unlock, read back and
compared, and only then is the plain file removed; a cut at any point leaves one whole copy. A backup holds History as it
did (its own seal), and a restore stages each conversation already sealed; while a conversation can't be opened, a backup
stops and says History is why (try again unlocked, or back up without History) and a restore changes none of History and says
why, rather than leave it out or write over it. A conversation saved by a newer Robot is left where it is, counted in History, and
removed by its Delete these. Opened conversations are dropped from History's cache when the screen goes off, and the list
Robot's window shows is dropped when the window stops (Home, screen off). History › Delete all removes every file and
both keys. If Android removes a key (for example when the screen lock is turned off), what it sealed can never be opened:
those files are kept aside, never deleted by Robot, History says how many, and its Delete these (or Delete all) removes
them (Memory, Lists, Robot Files and Link also read Android's invalidated key as gone and keep what it sealed); new conversations
get a new key.

Conversation transcripts (1.4) are sealed the same way: one under `robot_transcripts_v1`, which needs the phone unlocked, and one written while locked (a session started from the lock screen, or one that goes on after the screen locks) under `robot_transcripts_lock_v1`, re-sealed under the first at the next unlock.

Scam warnings (1.4) are rules in code in `:core` run on a message already in the panel's memory. They use no network and open or fetch no link, block, delete or send nothing, and keep nothing. The warning is fixed text, never the model's words, and a locked phone gets only a count. Patterns can be evaded and a text with no hit is not safe; the warning is a hint.

Notes: each note is sealed on its own the same way (the framing and the folder's handling are History's), under a key of
Notes' own, `robot_notes_v1`, which needs the phone unlocked. A note taken over the lock screen is sealed under a second key,
`robot_notes_lock_v1`, which doesn't, and is moved under the first at the next unlock. While the phone is locked Robot opens
no note: asked for your notes over the lock screen, it says they open once you unlock. Notes saved by an older Robot are sealed
at the first unlock, read back and compared, and only then is the plain file removed; a cut at any point leaves one whole copy.
A backup holds notes as it did (its own seal), and a restore stages each note already sealed; while a note can't be opened,
a backup stops and says Notes is why, and a restore changes none of Notes. If Android removes a key, the notes it sealed
are kept aside, never deleted by Robot; the Notes list says how many, and its Delete these removes them. New notes get a new
key. The Notes screens are `FLAG_SECURE`: kept out of screenshots, screen recordings, casting and Recents.

People: sealed whole in one file the same way (the framing and the folder's handling are History's and Notes'), under a key
of People's own, `robot_people_v1`, which needs the phone unlocked. While the phone is locked Robot opens no one in People:
over the lock screen it finds people in the phone's contacts only. Phone lock's "Calls to People you choose" then checks a
third file, `lock_calls.json`: for each person a lock names, one (person id, HMAC-SHA256) pair per number, made with a
Keystore key, `robot_people_calls_v1`, that never leaves the phone and doesn't need it unlocked. It holds no names and no
numbers; a number is let through only when its hash matches an entry for someone the locks name now, so a person taken
off a lock is refused at once. Someone holding the locked phone can learn only whether a number they say is allowed, one
try at a time, as before. The file is rewritten with every People write, at each unlock and each lock save, and removed
when that fails. The changes Robot makes while locked (a person learned from a notification, a person a linked computer
sends) are kept in a second file under a second key, `robot_people_lock_v1`, which doesn't need the phone unlocked; the
file holds only those changes, never People, and they are made at the next unlock. People saved by an older Robot is
sealed at the first unlock, read back and compared, and only then is the plain file removed; a cut at any point leaves
one whole copy. A plain file Robot can't read stays as it is until Start over in People keeps it aside, still plain, as
`people.json.bad`. A backup holds People as it did (its own seal), and a restore writes it through the sealed store;
while People can't be opened, Back up now's People row says so (never Empty), a backup with People ticked stops and says
People is why, and the restore preview leaves People out with its reason and changes none of it. If Android removes the
key, or a newer Robot saved the file, People shows its "can't read" card, and Start over keeps the file aside. Delete all
removes the sealed files, every copy kept aside (the plain `people.json.bad` too), the call list and its key, and People's
keys once nothing is sealed under them.

Skills connections' known limits: its checks have run on a Pixel 8 against stand-in Home Assistant and ntfy servers, but
not every step has been run by a person: a real routing server, a connection over mobile data and the Termux step are
still to do. A plain http address on your
own network can't be told apart from the same address on someone else's Wi-Fi, so a webhook without a secret could
send its request to whatever that network answers: use https or your server's Tailscale address. On mobile data Robot
sends no plain http request to a home address (1.3.2): at most a connection's opening and closing packets, no data,
when a VPN that doesn't route the address is on, your Wi-Fi or Ethernet holds the address, and the phone sends on
mobile data anyway (Wi-Fi without internet, say), whatever networks the VPN declares. Robot can't see what
a Home Assistant switch or button is wired to, or what a webhook does on the server: a switch that opens a garage door
counts as a change at home, and a GET webhook as a read, unless you make it a cover or a lock in Home Assistant, or
mark it in its connection's settings. Each Home Assistant operation and each webhook can carry four marks, and a mark
only ever adds to what Robot found itself: "It can spend money", "It locks, unlocks or opens something" and "It deletes
something" each take a tap every time, never a spoken yes; "It tells people" is named on the card and takes no spoken
yes. A Home Assistant domain Robot doesn't know (a switch, a button, a number, a select), a `send_command` in any
domain, and a script, scene, group or automation count as code Robot can't see: a tap, never a spoken yes. Anyone who can make your Home Assistant's devices' names can
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
at your own phone, by its text and, before each direct post, by every address its name looks up to, and treats one it can't
accept as no address. Each post to a push server is a TLS session of its own, never resumed from another post, so the push
server can't tie an Anonymous post to a direct one, or two pairings to each other; a post that takes more than a minute is
dropped and tried again later. Each pairing takes at most two connections coming in: a new one closes the
ones that haven't proven themselves first, then the oldest, so someone who only saw a token on the network can't push out
your newest proven one.

## Dependencies

Every dependency is pinned (versions, and SHA-256 for native libraries, models and data). They are reviewed for security
advisories at least once a month; a high or critical advisory in a part Robot uses gets a patch release as soon as a fix
is available.

Wikipedia on this phone (1.4) adds `librobotzim.so`, loaded only by the isolated `:wiki` process: libzim 9.3.0 and Xapian
1.4.29 (GPL-2.0-or-later) with Zstandard 1.5.7, liblzma 5.8.1 and ICU 77.1, statically linked, built by
`tools/build_libzim.sh` from release archives pinned by SHA-256. Their licences and sources are in `robot/THIRD_PARTY.md`.
