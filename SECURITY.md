# Security policy

> **Draft for review.** Replace CONTACT with a real address, and turn on private vulnerability reporting for the public
> repository (Settings › Advanced Security › Private vulnerability reporting) before publishing.

## Reporting a security problem

Please report it privately: use **Report a vulnerability** on this repository's Security tab (GitHub's private
vulnerability reporting), or email CONTACT. Please don't open a public issue.

Include:

- Robot's version (Settings › About) and how you installed it;
- your phone model and GrapheneOS build;
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
- an update installed without its checks (the release's `SHA256SUMS`, Robot's package, a newer version, Robot's signing
  key), or the updater's access token sent anywhere but api.github.com;
- what you say or type ending up in a log or on storage.

**Out of scope:** the models' answers themselves (what a model says, or refuses to say, is not a vulnerability unless it
gets past Robot's cards), and third-party services and apps (Hugging Face, Open-Meteo, OpenStreetMap's Nominatim,
Anthropic, Cheogram, Proton Mail, Organic Maps, GitHub): report those to them. A problem in a library Robot ships (sherpa-onnx,
ONNX Runtime, LiteRT-LM, the Anthropic SDK, OkHttp, Jackson) is welcome here too, so Robot can update it; please also
tell its maintainers.

## How Robot is built to be safe

- The AI model runs in its own process (`:brain`), which has no network code; voice, tools and online helpers run in
  `:voice`; the main app never loads an engine or the Claude SDK. Tests check that from the sources and the merged
  manifest. (Android grants the network per app, not per process: the separation is in the code, and the tests keep it.
  The same holds for Install unknown apps once you allow it: only the main process's updater installs, and only Robot's
  own update.)
- The model only proposes actions. Robot's approval rules decide, and an action that writes or leaves the phone after
  Robot read untrusted content asks you first.
- Every downloaded file is pinned to a commit, a size and a SHA-256, checked before it is loaded. A model file you import
  is untrusted input: Robot checks its header before loading it, and runs it on the TPU only after you allow it.
- API keys (and the updater's access token) are encrypted with a non-exportable Android Keystore key. Backups and device
  transfer are off.
- Updates come only from the repository's GitHub releases, over HTTPS to GitHub's hosts, and are installed only after the
  file matches the release's `SHA256SUMS` and Android reads it as Robot, newer, signed with Robot's key (or a key rotated
  from it with APK Signature Scheme v3). Android's own window confirms the install unless you turn that off. `SHA256SUMS`
  comes from the same release, so it guards against a damaged download, not against someone who can publish to the
  repository: the signing key is what does that.
- Logs hold codes and numbers only; audio is never written to storage.

Known limits: the TPU runs Google's proprietary vendor library on the phone; the prebuilt sherpa-onnx and ONNX Runtime
libraries are trusted from their publishers' releases (pinned by SHA-256); whatever you send to an online helper is
handled under that provider's terms.

## Dependencies

Every dependency is pinned (versions, and SHA-256 for native libraries, models and data). They are reviewed for security
advisories at least once a month; a high or critical advisory in a part Robot uses gets a patch release as soon as a fix
is available.
