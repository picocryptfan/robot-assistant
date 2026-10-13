# Contributing to Robot

Thank you for helping. Robot is a small project: bug reports, ideas and focused pull requests are all welcome. For a
security problem, please don't open an issue: see [`SECURITY.md`](SECURITY.md).

## Reporting a bug or an idea

The easiest way is from the phone: Settings › About › Send feedback writes the report for you (it starts with a
`ROBOT FEEDBACK v1` header) and adds only the diagnostics you tick, which hold codes and numbers, never your
conversations. Paste it into a new issue ("Bug report" or "Idea"), or use "Open a GitHub issue" on that page. Please
read it before you send it and remove anything personal.

## Building and testing

See the README's "Build from source" and [`robot/README.md`](robot/README.md) ("Building"). Before you send a change:

```
cd robot
gradle :core:testReleaseUnitTest :voice:testReleaseUnitTest :helpers:testReleaseUnitTest :app:testReleaseUnitTest
gradle :app:testDebugUnitTest      # Compose UI tests and screenshots; -Droborazzi.record=true rewrites the goldens
```

The tests run offline. A change to what you see needs its screenshot goldens re-recorded and looked at. A change to
behaviour on the phone should say how you checked it there (the feature sections of `robot/README.md` list their phone
checks).

## Rules the code keeps

- Process isolation. The AI model runs in `:brain` (and GGUF files in `:llama`), which have no network code; voice,
  tools and online helpers run in `:voice`; the main app never loads an engine or a helper's SDK. `ProcessIsolationTest`
  checks this from the sources and the merged manifest and fails a change that breaks it.
- No content in logs. Log with `RLog` (codes and numbers) and the voice log's fixed events. Never log what was said,
  typed, read, answered or sent, a name, a number, an address, a path of yours or a key.
- Ask first. The model only proposes actions; `ApprovalGate` decides. Anything that writes, sends, deletes or goes
  online after Robot read content it didn't write (the screen, a message, a web page, a command's output) asks you first.
- Wording. Robot speaks to "you"; Robot is "it". Plain sentences.
- Pin everything. Every dependency has an exact version; every native library, model and data file has a pinned
  URL or commit, a size and a SHA-256, checked before use. No dynamic versions, no unpinned downloads.
- Licences. A new dependency, model, data file or online service needs a row in
  [`robot/THIRD_PARTY.md`](robot/THIRD_PARTY.md) and an entry in `robot/app/src/main/assets/licenses/components.json`
  with its licence text; `LicencesTest` and `tools/verify_apk.sh` fail otherwise. Its licence must be compatible with
  GPL-3.0.
- Copyleft source. Native code under a copyleft licence goes into the release's source bundle
  (`tools/make_source_bundle.sh`).

## Dependencies

- Dependencies are reviewed once a month (sherpa-onnx, LiteRT-LM and the NPU runtime; the helper's Java SDK, OkHttp and
  Jackson; Kotlin, AGP and the Compose BOM) against the GitHub Advisory Database and OSV.
- A high or critical advisory in a part Robot ships gets a patch release as soon as a fix is available; anything else
  waits for the next release.
- Every bump re-runs the fetch scripts' hash checks and updates `THIRD_PARTY.md`, the Licences list and
  `tools/verify_apk.sh`, then the tests.

## Licence of contributions

Robot is GPL-3.0-only. By sending a contribution you agree that it is licensed under the GPL version 3 only, and that
you have the right to contribute it. A `Signed-off-by` line (the Developer Certificate of Origin) is welcome but not
required.
