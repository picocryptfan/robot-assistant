#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Robot's source offer for the copyleft native code it ships (GPL-3.0 section 6), made once per release.
#
# Robot's APK holds native code built outside Gradle:
#   - sherpa-onnx's prebuilt libsherpa-onnx-jni.so (statically linking espeak-ng, GPL-3.0-or-later, and the other sources
#     below), its libonnxruntime.so, and 17 compiled espeak-ng data files: Robot's repository holds none of their source;
#   - librobotmt.so (since 0.6.0: slimt, GPL-2.0-or-later, with ruy, cpuinfo and SentencePiece, and Robot's own wrapper in
#     mt/src/main/cpp), built by tools/build_robotmt.sh from commits it pins;
#   - librobot_llama.so (since 0.4.5, when built: llama.cpp and KleidiAI, MIT and Apache-2.0, and Robot's own bridge in
#     brain/src/main/cpp), built by tools/build_llama.sh from the tag and release it pins.
# So every release attaches the complete source of all of it as one archive, next to the APK:
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
#   - mt/: slimt, ruy, cpuinfo and SentencePiece exactly as tools/build_robotmt.sh takes them (the `git archive` tar of
#     each pinned commit, checked against the SHA-256 that script pins; the pins are read from the script itself);
#   - llama/: llama.cpp and KleidiAI exactly as tools/build_llama.sh takes them (llama.cpp's `git archive` tar of the
#     tag's commit, checked against its pinned SHA-256; KleidiAI's release tarball, or the tag's tar when that can't be
#     downloaded, each checked against its pin; the pins are read from that script);
#   - robot/: Robot's own native sources and build scripts at this commit (mt/src/main/cpp, brain/src/main/cpp,
#     tools/build_robotmt.sh, tools/build_llama.sh, tools/fetch_third_party.sh), so the archive builds on its own;
#   - README.md (what it is and how each library is built) and MANIFEST.tsv (every file, where it came from, its pin and
#     its SHA-256).
#
# Usage: tools/make_source_bundle.sh [--version 1.0.0] [--out DIR] [--work DIR] [--without-onnxruntime]
#                                    [--without-llama] [--list]
#   --version  defaults to app/build.gradle's versionName
#   --out      defaults to build/source-bundle (gitignored); never commit the archive
#   --work     a scratch folder (default: a new temporary one, deleted at the end)
#   --without-onnxruntime  leaves out ONNX Runtime's source (MIT, which asks for no source; but sherpa-onnx's library is
#              built to require it, so the GPL's "Corresponding Source" is safest with it, the default)
#   --without-llama  leaves out llama.cpp and KleidiAI (MIT and Apache-2.0, which ask for no source): for a release built
#              without librobot_llama.so, or when size matters. The default keeps them, so the archive is the whole
#              native source of the APK
#   --list     prints every pinned source and where it comes from, and exits: no network, nothing written
# Needs bash, curl, git, sha256sum, md5sum, tar (GNU) and gzip, and the network (GitHub, GitLab, Hugging Face). Run it by
# hand when releasing; nothing runs it in the tests (they only read it).
# The archive is about 360 MB (ONNX Runtime's source is 280 MB of it, llama.cpp's about 40 MB); attach it and its .sha256
# to the GitHub release (README, "Releasing").
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROBOT=$(dirname "$HERE")
VERSION=""
OUT="$ROBOT/build/source-bundle"
WORK=""
WITH_ORT=1
WITH_LLAMA=1
LIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --without-onnxruntime) WITH_ORT=0; shift ;;
    --without-llama) WITH_LLAMA=0; shift ;;
    --list) LIST=1; shift ;;
    -h|--help) sed -n '2,48p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || VERSION=$(sed -n "s/^ *versionName '\([^']*\)'.*/\1/p" "$ROBOT/app/build.gradle" | head -1)
[ -n "$VERSION" ] || { echo "no version: pass --version" >&2; exit 2; }

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

# librobotmt.so's sources: tools/build_robotmt.sh's own pins (name repository commit sha256-of-the-tar directory), read
# from that script so the two can't drift apart.
MT_SOURCES=$(sed -n "/^SOURCES=\$(cat <<'LIST'\$/,/^LIST\$/p" "$HERE/build_robotmt.sh" | sed '1d;$d')
[ "$(grep -c . <<<"$MT_SOURCES")" -ge 4 ] || { echo "couldn't read the pins in tools/build_robotmt.sh" >&2; exit 2; }
# librobot_llama.so's sources: tools/build_llama.sh's own pins, read from that script.
llama_pin() { local v; v=$(sed -n "s/^$1=\(.*\)$/\1/p" "$HERE/build_llama.sh" | head -1); [ -n "$v" ] || { echo "no $1 in tools/build_llama.sh" >&2; exit 2; }; echo "$v"; }
LLAMA_TAG=$(llama_pin LLAMA_TAG); LLAMA_COMMIT=$(llama_pin LLAMA_COMMIT); LLAMA_TAR_SHA256=$(llama_pin LLAMA_TAR_SHA256)
LLAMA_GIT=$(llama_pin LLAMA_GIT)
KLEIDIAI_TAG=$(llama_pin KLEIDIAI_TAG); KLEIDIAI_COMMIT=$(llama_pin KLEIDIAI_COMMIT); KLEIDIAI_GIT=$(llama_pin KLEIDIAI_GIT)
KLEIDIAI_TGZ_SHA256=$(llama_pin KLEIDIAI_TGZ_SHA256); KLEIDIAI_TGZ_MD5=$(llama_pin KLEIDIAI_TGZ_MD5); KLEIDIAI_TAR_SHA256=$(llama_pin KLEIDIAI_TAR_SHA256)
KLEIDIAI_URL="https://github.com/ARM-software/kleidiai/releases/download/$KLEIDIAI_TAG/kleidiai-$KLEIDIAI_TAG-src.tar.gz"
# Robot's own files the archive carries (its native sources and the scripts that build and fetch the native parts).
ROBOT_FILES="mt/src/main/cpp brain/src/main/cpp tools/build_robotmt.sh tools/build_llama.sh tools/fetch_third_party.sh tools/make_source_bundle.sh"

if [ "$LIST" = 1 ]; then
  echo "sherpa-onnx $SHERPA_VERSION: $SHERPA_REPO @ $SHERPA_COMMIT"
  while IFS='|' read -r name file var repo ref commit; do
    [ -n "$name" ] && echo "  $name: the archive sherpa-onnx pins in $file (git fallback: $repo $ref = $commit)"
  done <<< "$DEPS"
  [ "$WITH_ORT" = 1 ] && echo "onnxruntime $ORT_VERSION: $ORT_REPO @ $ORT_COMMIT"
  while read -r name repo commit want dir; do echo "mt/$name: $repo @ $commit (tar sha256 $want)"; done <<<"$MT_SOURCES"
  if [ "$WITH_LLAMA" = 1 ]; then
    echo "llama/llama.cpp $LLAMA_TAG: $LLAMA_GIT @ $LLAMA_COMMIT (tar sha256 $LLAMA_TAR_SHA256)"
    echo "llama/kleidiai $KLEIDIAI_TAG: $KLEIDIAI_URL (sha256 $KLEIDIAI_TGZ_SHA256), or $KLEIDIAI_GIT @ $KLEIDIAI_COMMIT (tar sha256 $KLEIDIAI_TAR_SHA256)"
  fi
  echo "robot/: $ROBOT_FILES"
  exit 0
fi
for tool in curl git sha256sum md5sum tar gzip; do command -v "$tool" >/dev/null || { echo "needs $tool" >&2; exit 2; }; done

NAME="robot-${VERSION}-third-party-sources"
if [ -z "$WORK" ]; then WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT; else mkdir -p "$WORK"; fi
STAGE="$WORK/$NAME"
rm -rf "$STAGE" "$WORK/git"
mkdir -p "$STAGE/deps" "$STAGE/mt" "$STAGE/robot" "$WORK/git" "$OUT"
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

# The uncompressed `git archive --format=tar [--prefix=<prefix>/] <commit>` of [repo] (fetched by [ref]), checked by its
# commit id and by the SHA-256 Robot's build script pins for that very tar; written to [out]. [prefix] "-": none.
git_tar() {
  local repo=$1 ref=$2 commit=$3 prefix=$4 want=$5 out=$6 dir got
  dir="$WORK/git/$(basename "$out" .tar)"
  rm -rf "$dir"; git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 "$repo" "$ref"
  got=$(git -C "$dir" rev-parse 'FETCH_HEAD^{commit}')
  [ "$got" = "$commit" ] || { echo "$repo $ref is $got, not the pinned $commit" >&2; exit 1; }
  if [ "$prefix" = - ]; then git -C "$dir" archive --format=tar "$commit" > "$out"
  else git -C "$dir" archive --format=tar --prefix="$prefix/" "$commit" > "$out"; fi
  got=$(sha "$out")
  [ "$got" = "$want" ] || { echo "SHA-256 MISMATCH $(basename "$out"): $got != $want" >&2; exit 1; }
  rm -rf "$dir"
}

# The SHA-256 of the library [2] this machine built into module [1]'s jniLibs for the APK, or what to say when it isn't there.
built_sha() { if [ -f "$ROBOT/$1/src/main/jniLibs/arm64-v8a/$2" ]; then echo "\`$(sha "$ROBOT/$1/src/main/jniLibs/arm64-v8a/$2")\` (the copy in $1/src/main/jniLibs when this archive was made)"; else echo "not built on the machine that made this archive"; fi; }

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

# --- librobotmt.so: slimt and its parts, as tools/build_robotmt.sh takes them. -----------------------------------------
MT_TABLE=""
while read -r name repo commit want dir; do
  say "mt/$name ($commit)"
  out="$STAGE/mt/$name-${commit:0:12}.tar"
  git_tar "$repo" "$commit" "$commit" - "$want" "$out"
  printf '%s\t%s\t%s\t%s\t%s\n' "mt/$(basename "$out")" "$want" "$repo" "commit $commit (tools/build_robotmt.sh; unpacked at $dir in slimt's tree)" "git commit id and sha256 (pinned)" >> "$MANIFEST"
  MT_TABLE="$MT_TABLE| $name | \`mt/$(basename "$out")\` | $repo at \`$commit\`, unpacked at \`$dir\` of slimt's tree |
"
done <<<"$MT_SOURCES"

# --- librobot_llama.so: llama.cpp and KleidiAI, as tools/build_llama.sh takes them. -------------------------------------
LLAMA_TABLE=""
if [ "$WITH_LLAMA" = 1 ]; then
  mkdir -p "$STAGE/llama"
  say "llama.cpp $LLAMA_TAG ($LLAMA_COMMIT)"
  out="$STAGE/llama/llama.cpp-$LLAMA_TAG.tar"
  git_tar "$LLAMA_GIT" "refs/tags/$LLAMA_TAG" "$LLAMA_COMMIT" "llama.cpp-$LLAMA_TAG" "$LLAMA_TAR_SHA256" "$out"
  printf '%s\t%s\t%s\t%s\t%s\n' "llama/$(basename "$out")" "$LLAMA_TAR_SHA256" "$LLAMA_GIT" "tag $LLAMA_TAG = commit $LLAMA_COMMIT (tools/build_llama.sh)" "git commit id and sha256 (pinned)" >> "$MANIFEST"
  LLAMA_TABLE="| llama.cpp $LLAMA_TAG (with ggml) | \`llama/$(basename "$out")\` | \`git archive\` of tag $LLAMA_TAG (commit \`$LLAMA_COMMIT\`), the tar tools/build_llama.sh checks |
"
  say "KleidiAI $KLEIDIAI_TAG"
  out="$STAGE/llama/kleidiai-$KLEIDIAI_TAG-src.tar.gz"
  if curl -fsSL --retry 2 --connect-timeout 20 -o "$out" "$KLEIDIAI_URL" 2>/dev/null && [ "$(sha "$out")" = "$KLEIDIAI_TGZ_SHA256" ] \
      && [ "$(md5sum "$out" | cut -d' ' -f1)" = "$KLEIDIAI_TGZ_MD5" ]; then
    printf '%s\t%s\t%s\t%s\t%s\n' "llama/$(basename "$out")" "$KLEIDIAI_TGZ_SHA256" "$KLEIDIAI_URL" "release $KLEIDIAI_TAG (llama.cpp's ggml-cpu CMake names it)" "sha256 and md5 (pinned)" >> "$MANIFEST"
    LLAMA_TABLE="$LLAMA_TABLE| KleidiAI $KLEIDIAI_TAG | \`llama/$(basename "$out")\` | its release tarball, the one llama.cpp's CMake fetches (SHA-256 and MD5 checked) |
"
  else
    rm -f "$out"
    say "KleidiAI: release tarball unreachable, taking $KLEIDIAI_TAG from git"
    out="$STAGE/llama/kleidiai-$KLEIDIAI_TAG.tar"
    git_tar "$KLEIDIAI_GIT" "refs/tags/$KLEIDIAI_TAG" "$KLEIDIAI_COMMIT" "kleidiai-$KLEIDIAI_TAG" "$KLEIDIAI_TAR_SHA256" "$out"
    printf '%s\t%s\t%s\t%s\t%s\n' "llama/$(basename "$out")" "$KLEIDIAI_TAR_SHA256" "$KLEIDIAI_GIT" "tag $KLEIDIAI_TAG = commit $KLEIDIAI_COMMIT (the release tarball couldn't be downloaded)" "git commit id and sha256 (pinned)" >> "$MANIFEST"
    LLAMA_TABLE="$LLAMA_TABLE| KleidiAI $KLEIDIAI_TAG | \`llama/$(basename "$out")\` | \`git archive\` of tag $KLEIDIAI_TAG (commit \`$KLEIDIAI_COMMIT\`), the same source as its release tarball |
"
  fi
fi

# --- Robot's own native sources and build scripts, at this commit. -------------------------------------------------------
ROBOT_COMMIT=$(git -C "$ROBOT" rev-parse HEAD 2>/dev/null || echo unknown)
ROBOT_TGZ="$STAGE/robot/robot-native-${ROBOT_COMMIT:0:12}.tar.gz"
# shellcheck disable=SC2086
(cd "$ROBOT" && tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -cf - $ROBOT_FILES) | gzip -n -9 > "$ROBOT_TGZ"
[ -z "$(git -C "$ROBOT" status --porcelain -- $ROBOT_FILES 2>/dev/null)" ] && ROBOT_STATE="as committed" || ROBOT_STATE="with uncommitted changes"
printf '%s\t%s\t%s\t%s\t%s\n' "robot/$(basename "$ROBOT_TGZ")" "$(sha "$ROBOT_TGZ")" "Robot's repository" "commit $ROBOT_COMMIT, $ROBOT_STATE: $ROBOT_FILES" "-" >> "$MANIFEST"
MT_SHIPPED=$(built_sha mt librobotmt.so)
LLAMA_SHIPPED=$(built_sha brain librobot_llama.so)

# --- What Robot ships, and how it is built. ----------------------------------------------------------------------------
cat > "$STAGE/README.md" <<EOF
# Source of the third-party native code in Robot $VERSION

Robot is free software under the GNU GPL version 3. Its own source is in its repository at tag v$VERSION. Robot's APK also
contains native code it doesn't build itself: sherpa-onnx's prebuilt library \`libsherpa-onnx-jni.so\`, into which
espeak-ng (GPL-3.0-or-later) and the libraries below are statically linked, ONNX Runtime's \`libonnxruntime.so\`, and 17
compiled espeak-ng data files; and two libraries Robot builds from pinned third-party source, \`librobotmt.so\` (slimt,
GPL-2.0-or-later, with ruy, cpuinfo and SentencePiece) and, when built, \`librobot_llama.so\` (llama.cpp and KleidiAI).
This archive is their complete corresponding source, with the scripts that build them, offered alongside Robot
$VERSION's APK, at the same place, for as long as that APK is offered (GPL-3.0 section 6). Each component keeps its own licence, in
its own source; Robot's Settings › About › Licences lists them all.

## What Robot ships (sha256)

| File in the APK | sha256 | From |
|---|---|---|
| \`lib/arm64-v8a/libsherpa-onnx-jni.so\` | \`3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f\` | sherpa-onnx v$SHERPA_VERSION's release \`sherpa-onnx-v$SHERPA_VERSION-android.tar.bz2\` (sha256 \`2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44\`) |
| \`lib/arm64-v8a/libonnxruntime.so\` | \`33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1\` | the same archive: ONNX Runtime $ORT_VERSION as built by csukuangfj/onnxruntime-libs (\`onnxruntime-android-$ORT_VERSION.zip\`, sha256 \`$ORT_PREBUILT_SHA\`, pinned in sherpa-onnx's \`cmake/onnxruntime-android-aarch64.cmake\`) |
| \`assets/espeak-ng-data/\` (17 files) | listed in Robot's THIRD_PARTY.md | sherpa-onnx's \`tts-models/espeak-ng-data.tar.bz2\` (sha256 \`4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533\`), unchanged |
| \`lib/arm64-v8a/librobotmt.so\` | $MT_SHIPPED | built by Robot's \`tools/build_robotmt.sh\` from \`mt/\` and Robot's \`mt/src/main/cpp\` (below) |
| \`lib/arm64-v8a/librobot_llama.so\` (only in a build that has it) | $LLAMA_SHIPPED | built by Robot's \`tools/build_llama.sh\` from \`llama/\` and Robot's \`brain/src/main/cpp\` (below) |

Robot also copies sherpa-onnx's Kotlin API files unchanged (from \`sherpa-onnx/kotlin-api/\` in the source below); they are
in Robot's own repository.

## In this archive

| Component | File | How it was taken |
|---|---|---|
| sherpa-onnx v$SHERPA_VERSION | \`sherpa-onnx-$SHERPA_VERSION.tar.gz\` | \`git archive\` of commit \`$SHERPA_COMMIT\` (the tag) |
${DEPS_TABLE}${ORT_ROW}
${MT_TABLE}${LLAMA_TABLE}| Robot's own native code and build scripts | \`robot/$(basename "$ROBOT_TGZ")\` | Robot's repository at commit \`$ROBOT_COMMIT\` ($ROBOT_STATE): $ROBOT_FILES |

\`MANIFEST.tsv\` has every file's SHA-256, where it came from and the pin it was checked against.

## Building libsherpa-onnx-jni.so

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

## Building librobotmt.so (slimt)

slimt is GPL-2.0-or-later (used under GPL-3.0) and holds MPL-2.0 code from browsermt/bergamot-translator and
browsermt/marian-dev and Apache-2.0 code from browsermt/ssplit; ruy and SentencePiece are Apache-2.0, cpuinfo is
BSD-2-Clause. Unpack \`robot/robot-native-*.tar.gz\` into Robot's source tree (or use the repository at tag v$VERSION) and
run \`tools/build_robotmt.sh\`: it fetches the four trees in \`mt/\` (the same tars, checked against the same SHA-256),
unpacks each where slimt's \`.gitmodules\` puts it (ruy at \`3rd-party/ruy\`, cpuinfo at
\`3rd-party/ruy/third_party/cpuinfo\`, SentencePiece at \`3rd-party/sentencepiece\`), and builds Robot's
\`mt/src/main/cpp\` with them (Android NDK 27.1.12297006, CMake 3.22.1, \`ANDROID_PLATFORM=android-34\`, \`c++_static\`,
\`-O3 -ffp-contract=off\`, slimt's NEON float code off) into one library. To build without the network, unpack the tars
yourself into \`mt/native/src/slimt\` at those places and run the script's build step (read the script: it is short).

## Building librobot_llama.so (llama.cpp)

llama.cpp and ggml are MIT, KleidiAI is Apache-2.0. Run Robot's \`tools/build_llama.sh\`: it fetches llama.cpp (the tar in
\`llama/\`, the same SHA-256) and KleidiAI (the release tarball in \`llama/\`, or the tag's tar), and builds Robot's
\`brain/src/main/cpp\` with them (Android NDK 27.1.12297006, \`ANDROID_PLATFORM=android-34\`, \`c++_static\`, CPU backend
only, KleidiAI kernels on). Without the network, unpack them into \`build-native/src/\` as the script names them
(\`llama.cpp-$LLAMA_TAG\`, \`kleidiai-$KLEIDIAI_TAG\`) with the \`.robot-checked\` mark it writes, and run it again.
$( [ "$WITH_LLAMA" = 1 ] || echo "This archive was made with --without-llama: llama.cpp ($LLAMA_GIT, tag $LLAMA_TAG, commit \`$LLAMA_COMMIT\`) and KleidiAI ($KLEIDIAI_GIT, tag $KLEIDIAI_TAG) are not in it." )

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
