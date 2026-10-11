#!/usr/bin/env bash
# Robot's source offer for the GPL native code it ships (GPL-3.0 section 6), made once per release.
#
# Robot ships sherpa-onnx's prebuilt libsherpa-onnx-jni.so (statically linking espeak-ng, GPL-3.0-or-later, and the other
# sources below) and libonnxruntime.so, plus 17 compiled espeak-ng data files. Robot's repository holds none of their
# source, so every release attaches the complete source as one archive, next to the APK:
#
#   robot-<version>-third-party-sources.tar.gz   and   robot-<version>-third-party-sources.tar.gz.sha256
#
# It holds:
#   - sherpa-onnx at the commit of v1.13.8 (11afbd00), as `git archive` of that commit (verified by its commit id);
#   - every source sherpa-onnx's Android build compiles into the library (espeak-ng at ed530aa1, piper-phonemize,
#     kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst, simple-sentencepiece, JSON for Modern C++, hclust-cpp,
#     Eigen): the very archives sherpa-onnx's CMake files pin, read from that verified source tree and checked against
#     the SHA-256 they pin. Where none of the archive's links can be reached, the script takes the same release from git
#     instead and checks its commit id (pinned below), and MANIFEST.tsv says so;
#   - ONNX Runtime 1.28.2 (the version sherpa-onnx's Android build links; its prebuilt library is csukuangfj's
#     onnxruntime-libs build), as `git archive` of the v1.28.2 tag's commit;
#   - README.md (what it is and how the library is built) and MANIFEST.tsv (every file, where it came from, its pin and
#     its SHA-256).
#
# Usage: tools/make_source_bundle.sh [--version 0.4.3] [--out DIR] [--work DIR] [--without-onnxruntime]
#   --version  defaults to app/build.gradle's versionName
#   --out      defaults to build/source-bundle (gitignored); never commit the archive
#   --work     a scratch folder (default: a new temporary one, deleted at the end)
#   --without-onnxruntime  leaves out ONNX Runtime's source (MIT, which asks for no source; but sherpa-onnx's library is
#              built to require it, so the GPL's "Corresponding Source" is safest with it, the default)
# Needs bash, curl, git, sha256sum, tar (GNU) and gzip, and the network (GitHub, GitLab, Hugging Face).
# The archive is about 310 MB (ONNX Runtime's source is 280 MB of it; about 50 MB without); attach it and its .sha256
# to the GitHub release (README, "Releasing").
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROBOT=$(dirname "$HERE")
VERSION=""
OUT="$ROBOT/build/source-bundle"
WORK=""
WITH_ORT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --without-onnxruntime) WITH_ORT=0; shift ;;
    -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || VERSION=$(sed -n "s/^ *versionName '\([^']*\)'.*/\1/p" "$ROBOT/app/build.gradle" | head -1)
[ -n "$VERSION" ] || { echo "no version: pass --version" >&2; exit 2; }
for tool in curl git sha256sum tar gzip; do command -v "$tool" >/dev/null || { echo "needs $tool" >&2; exit 2; }; done

# --- Pins (what Robot ships: THIRD_PARTY.md). -------------------------------------------------------------------------
SHERPA_REPO=https://github.com/k2-fsa/sherpa-onnx
SHERPA_VERSION=1.13.8
SHERPA_COMMIT=11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
ORT_REPO=https://github.com/microsoft/onnxruntime
ORT_VERSION=1.28.2
ORT_COMMIT=33ca9628233dc8f002435e868d4c2e9f82766ca1
# Every source the Android build (TTS and speaker clustering on, no binaries, no websocket) compiles into the library:
# name | where sherpa-onnx pins its archive | the CMake variable | git fallback: repository | ref | the ref's commit
DEPS='
espeak-ng|cmake/espeak-ng-for-piper.cmake|espeak_ng|https://github.com/csukuangfj/espeak-ng|ed530aa113046142eb5115cf2fc9157854d0ffe1|ed530aa113046142eb5115cf2fc9157854d0ffe1
piper-phonemize|cmake/piper-phonemize.cmake|piper_phonemize|https://github.com/csukuangfj/piper-phonemize|f3ff95afc03640bc1399e113e83361192a2fafb4|f3ff95afc03640bc1399e113e83361192a2fafb4
kaldi-native-fbank|cmake/kaldi-native-fbank.cmake|kaldi_native_fbank|https://github.com/csukuangfj/kaldi-native-fbank|refs/tags/v1.22.3|b09e686fe2084732ddd30d1ef80acfc0f13eaf01
kaldi-decoder|cmake/kaldi-decoder.cmake|kaldi_decoder|https://github.com/k2-fsa/kaldi-decoder|refs/tags/v0.3.0|62fda2d74246a90db9570e46fb16b012f813fae7
kaldifst|cmake/download-all-deps.py|kaldifst|https://github.com/k2-fsa/kaldifst|refs/tags/v1.8.0|ab5bdd013bdf13921e6aeee77db5722ebf9955fb
openfst|cmake/openfst.cmake|openfst|https://github.com/csukuangfj/openfst|refs/tags/v1.8.5-2026-07-09|f702a6317d600ac953fdbb4978674c3a28b6115c
simple-sentencepiece|cmake/simple-sentencepiece.cmake|simple-sentencepiece|https://github.com/pkufool/simple-sentencepiece|refs/tags/v0.7|62dd423df1d51da5ea06f1c3a046fc04f01b4f39
json|cmake/json.cmake|json|https://github.com/nlohmann/json|refs/tags/v3.12.0|55f93686c01528224f448c19128836e7df245f72
hclust-cpp|cmake/hclust-cpp.cmake|hclust_cpp|https://github.com/csukuangfj/hclust-cpp|refs/tags/2026-02-25|db76039198979ce8d03632ab6ef14fe6accb127c
eigen|cmake/eigen.cmake|eigen|https://gitlab.com/libeigen/eigen.git|refs/tags/5.0.1|bc3b39870ecb690a623a3f49149a358b95c5781d
'

NAME="robot-${VERSION}-third-party-sources"
if [ -z "$WORK" ]; then WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT; else mkdir -p "$WORK"; fi
STAGE="$WORK/$NAME"
rm -rf "$STAGE" "$WORK/git"
mkdir -p "$STAGE/deps" "$WORK/git" "$OUT"
MANIFEST="$STAGE/MANIFEST.tsv"
printf 'file\tsha256\tsource\tpin\tchecked\n' > "$MANIFEST"
say() { echo "== $*" >&2; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

# git archive of [commit] of [repo] (fetched by [ref]), checked by its commit id; written to [out] as tar.gz.
git_archive() {
  local repo=$1 ref=$2 commit=$3 prefix=$4 out=$5 dir
  dir="$WORK/git/$(basename "$out" .tar.gz)"
  rm -rf "$dir"; git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 "$repo" "$ref"
  local got; got=$(git -C "$dir" rev-parse 'FETCH_HEAD^{commit}')
  [ "$got" = "$commit" ] || { echo "$repo $ref is $got, not the pinned $commit" >&2; exit 1; }
  git -C "$dir" archive --format=tar --prefix="$prefix/" "$commit" | gzip -n -9 > "$out"
  rm -rf "$dir"
}

# --- sherpa-onnx itself: its source, and from it the pins of everything it compiles in. ---------------------------
say "sherpa-onnx $SHERPA_VERSION ($SHERPA_COMMIT)"
SHERPA_TGZ="$STAGE/sherpa-onnx-$SHERPA_VERSION.tar.gz"
git_archive "$SHERPA_REPO" "$SHERPA_COMMIT" "$SHERPA_COMMIT" "sherpa-onnx-$SHERPA_VERSION" "$SHERPA_TGZ"
printf '%s\t%s\t%s\t%s\t%s\n' "$(basename "$SHERPA_TGZ")" "$(sha "$SHERPA_TGZ")" "$SHERPA_REPO" "commit $SHERPA_COMMIT (tag v$SHERPA_VERSION)" "git commit id" >> "$MANIFEST"
SRC="$WORK/sherpa-src"
rm -rf "$SRC"; mkdir -p "$SRC"
tar -xzf "$SHERPA_TGZ" -C "$SRC" --strip-components=1

# The ONNX Runtime the Android build links: its version must be the pinned one.
grep -q "onnxruntime-libs/releases/download/v$ORT_VERSION/onnxruntime-android-$ORT_VERSION.zip" "$SRC/cmake/onnxruntime-android-aarch64.cmake" \
  || { echo "sherpa-onnx's Android build no longer links ONNX Runtime $ORT_VERSION: update the pins" >&2; exit 1; }
ORT_PREBUILT_SHA=$(sed -n 's/^set(onnxruntime_HASH "SHA256=\([0-9a-f]\{64\}\)")/\1/p' "$SRC/cmake/onnxruntime-android-aarch64.cmake")

# The value of set(<var>_<KEY> "...") in a CMake file.
cmake_value() { sed -n "s/^ *set(${2}_${3} *\"\([^\"]*\)\")/\1/p" "$SRC/$1" | head -1; }

DEPS_TABLE=""
while IFS='|' read -r name file var repo ref commit; do
  [ -n "$name" ] || continue
  if [ "$file" = cmake/download-all-deps.py ]; then
    # kaldi-decoder's own CMake fetches kaldifst; sherpa-onnx pins the same archive here for offline builds.
    block=$(grep -A4 "\"$var\"," "$SRC/$file")
    url=$(echo "$block" | grep -o 'https://[^"]*' | head -1); url2=""
    want=$(echo "$block" | grep -o '[0-9a-f]\{64\}' | head -1)
  else
    url=$(cmake_value "$file" "$var" URL); url2=$(cmake_value "$file" "$var" URL2)
    want=$(cmake_value "$file" "$var" HASH | sed 's/^SHA256=//')
  fi
  [ -n "$url" ] && [ ${#want} -eq 64 ] || { echo "no pinned archive for $name in $file" >&2; exit 1; }
  say "$name: $url"
  got=""
  for u in "$url" "$url2" "${url2/hf-mirror.com/huggingface.co}"; do
    [ -n "$u" ] || continue
    base=$(basename "${u%%\?*}")
    case "$base" in "$name"*) ;; *) base="$name-${base#v}" ;; esac
    dest="$STAGE/deps/$base"
    if curl -fsSL --retry 2 --connect-timeout 20 -o "$dest" "$u" 2>/dev/null && [ "$(sha "$dest")" = "$want" ]; then got="$dest"; break; fi
    rm -f "$dest"
  done
  if [ -n "$got" ]; then
    printf '%s\t%s\t%s\t%s\t%s\n' "deps/$(basename "$got")" "$want" "$url" "sha256 $want in sherpa-onnx's $file" "sha256 (pinned)" >> "$MANIFEST"
    DEPS_TABLE="$DEPS_TABLE| $name | \`deps/$(basename "$got")\` | the archive sherpa-onnx pins (SHA-256 checked) |
"
  else
    # Every link failed: the same release from git, checked by its commit id.
    say "$name: archive unreachable, taking $repo $ref from git"
    out="$STAGE/deps/$name-git-${commit:0:12}.tar.gz"
    git_archive "$repo" "$ref" "$commit" "$name-${commit:0:12}" "$out"
    printf '%s\t%s\t%s\t%s\t%s\n' "deps/$(basename "$out")" "$(sha "$out")" "$repo" "$ref = commit $commit (the same source as the archive sherpa-onnx pins, $url, sha256 $want, which this machine couldn't download)" "git commit id" >> "$MANIFEST"
    DEPS_TABLE="$DEPS_TABLE| $name | \`deps/$(basename "$out")\` | \`git archive\` of $ref (commit \`$commit\`); sherpa-onnx's pinned archive \`$url\` (SHA-256 \`$want\`) holds the same source |
"
  fi
done <<< "$DEPS"

# --- ONNX Runtime. ------------------------------------------------------------------------------------------------------
if [ "$WITH_ORT" = 1 ]; then
  say "onnxruntime $ORT_VERSION ($ORT_COMMIT)"
  ORT_TGZ="$STAGE/onnxruntime-$ORT_VERSION.tar.gz"
  git_archive "$ORT_REPO" "refs/tags/v$ORT_VERSION" "$ORT_COMMIT" "onnxruntime-$ORT_VERSION" "$ORT_TGZ"
  printf '%s\t%s\t%s\t%s\t%s\n' "$(basename "$ORT_TGZ")" "$(sha "$ORT_TGZ")" "$ORT_REPO" "tag v$ORT_VERSION = commit $ORT_COMMIT" "git commit id" >> "$MANIFEST"
  ORT_ROW="| ONNX Runtime $ORT_VERSION | \`onnxruntime-$ORT_VERSION.tar.gz\` | \`git archive\` of tag v$ORT_VERSION (commit \`$ORT_COMMIT\`) |"
else
  ORT_ROW="| ONNX Runtime $ORT_VERSION | not in this archive | $ORT_REPO, tag v$ORT_VERSION (commit \`$ORT_COMMIT\`) |"
fi

# --- What Robot ships, and how it is built. ----------------------------------------------------------------------------
cat > "$STAGE/README.md" <<EOF
# Source of the third-party native code in Robot $VERSION

Robot is free software under the GNU GPL version 3. Its own source is in its repository at tag v$VERSION. Robot's APK also
contains native code it doesn't build itself: sherpa-onnx's prebuilt library \`libsherpa-onnx-jni.so\`, into which
espeak-ng (GPL-3.0-or-later) and the libraries below are statically linked, ONNX Runtime's \`libonnxruntime.so\`, and 17
compiled espeak-ng data files. This archive is their complete corresponding source, offered alongside Robot $VERSION's
APK, at the same place, for as long as that APK is offered (GPL-3.0 section 6). Each component keeps its own licence, in
its own source; Robot's Settings › About › Licences lists them all.

## What Robot ships (sha256)

| File in the APK | sha256 | From |
|---|---|---|
| \`lib/arm64-v8a/libsherpa-onnx-jni.so\` | \`3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f\` | sherpa-onnx v$SHERPA_VERSION's release \`sherpa-onnx-v$SHERPA_VERSION-android.tar.bz2\` (sha256 \`2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44\`) |
| \`lib/arm64-v8a/libonnxruntime.so\` | \`33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1\` | the same archive: ONNX Runtime $ORT_VERSION as built by csukuangfj/onnxruntime-libs (\`onnxruntime-android-$ORT_VERSION.zip\`, sha256 \`$ORT_PREBUILT_SHA\`, pinned in sherpa-onnx's \`cmake/onnxruntime-android-aarch64.cmake\`) |
| \`assets/espeak-ng-data/\` (17 files) | listed in Robot's THIRD_PARTY.md | sherpa-onnx's \`tts-models/espeak-ng-data.tar.bz2\` (sha256 \`4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533\`), unchanged |

Robot also copies sherpa-onnx's Kotlin API files unchanged (from \`sherpa-onnx/kotlin-api/\` in the source below); they are
in Robot's own repository.

## In this archive

| Component | File | How it was taken |
|---|---|---|
| sherpa-onnx v$SHERPA_VERSION | \`sherpa-onnx-$SHERPA_VERSION.tar.gz\` | \`git archive\` of commit \`$SHERPA_COMMIT\` (the tag) |
${DEPS_TABLE}${ORT_ROW}

\`MANIFEST.tsv\` has every file's SHA-256, where it came from and the pin it was checked against.

## Building the library

sherpa-onnx's \`build-android-arm64-v8a.sh\` builds \`libsherpa-onnx-jni.so\` (shared libraries on, TTS and speaker
diarization on, JNI on, \`-DANDROID_PLATFORM=android-21\` by default; read the script for every option). Its CMake files
(\`cmake/*.cmake\`) fetch each dependency above at the pinned archive and SHA-256. To build offline, put sherpa-onnx's
pinned archives in \`~/Downloads\` (or next to \`CMakeLists.txt\`), where those files look first: the files in \`deps/\`
marked "the archive sherpa-onnx pins" are those, byte for byte; the ones taken with \`git archive\` hold the same source
(the same commit) in another archive, so CMake's hash check needs the pinned archive, or its \`URL_HASH\` changed to
theirs. The script downloads the prebuilt ONNX Runtime it links (csukuangfj/onnxruntime-libs v$ORT_VERSION);
\`onnxruntime-$ORT_VERSION.tar.gz\` is its source (its own dependencies are pinned in its \`cmake/deps.txt\`). Whether that prebuilt library carries patches over the upstream
tag is not stated by its publisher.

espeak-ng's data files (\`phontab\`, \`phonindex\`, \`phondata\`, \`intonations\`, \`en_dict\`, \`fr_dict\` and the
language files) are compiled from espeak-ng's \`phsource/\` and \`dictsource/\`, in the espeak-ng source above (\`make\` in
espeak-ng builds them).

Made by Robot's \`tools/make_source_bundle.sh\`.
EOF

# --- The archive and its SHA-256 (reproducible: sorted, no owners, no times). -----------------------------------------
say "packing"
TGZ="$OUT/$NAME.tar.gz"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$WORK" -cf - "$NAME" | gzip -n -9 > "$TGZ"
(cd "$OUT" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")
rm -rf "$STAGE" "$SRC"
say "done: $TGZ ($(du -h "$TGZ" | cut -f1))"
cat "$OUT/$NAME.tar.gz.sha256"
