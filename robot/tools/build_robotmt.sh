#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Builds librobotmt.so, the fast translation engine of the :mt process (docs/conversation-mode.md 4.3), for arm64-v8a
# from Robot's own sources in mt/src/main/cpp and pinned third-party sources:
#   - slimt (DavidVentura's fork, GPL-2.0-or-later with MPL-2.0 and Apache-2.0 parts), with the commits its submodules
#     pin: google/ruy, ruy's pytorch/cpuinfo, browsermt/sentencepiece (THIRD_PARTY.md).
# Each source is fetched with git at its pinned commit (a shallow fetch of that one commit), written out with
# `git archive --format=tar`, and that tar's SHA-256 is checked before anything is unpacked or built (GitHub's commit
# tarballs carry the same files; the build box here could reach GitHub's git service only). Everything goes into
# mt/native/ (not in git; ROBOTMT_WORK=<dir> puts it elsewhere). The library goes into mt/src/main/jniLibs/arm64-v8a/
# (not in git), where Gradle packages it for the :mt process. Without it the app still builds, and Robot says the fast
# engine isn't in this build.
#
# Needs git, tar, sha256sum, curl (--check) and the Android SDK's NDK and CMake at the versions below (ANDROID_HOME,
# default /opt/android-sdk); --check also needs a C++ compiler for this computer. About 30 s on 4 cores; mt/native/
# holds about 100 MB afterwards.
#
# Usage: tools/build_robotmt.sh                 fetch, check and build librobotmt.so
#        tools/build_robotmt.sh --check         also build the same wrapper for this computer (robotmt_check), check
#                                               its sentence splitter, download the Bergamot fr-en/en-fr files
#                                               (checked as the app checks them) and translate tools/lang/fr-CA/mt-test.tsv,
#                                               comparing each output with the fast engine's reference column
#        tools/build_robotmt.sh --models DIR    only download and check the model files into DIR
#        tools/build_robotmt.sh --clean         delete mt/native/ (sources, models, build trees)
# ROBOTMT_NEON=ON also turns on slimt's NEON float code: maybe faster on the phone, but some translations then differ
# from the build machine's reference (mt/src/main/cpp/CMakeLists.txt), so Test translation speed counts them as different.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
MT="$HERE/mt"
WORK="${ROBOTMT_WORK:-$MT/native}"
OUT="$MT/src/main/jniLibs/arm64-v8a"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
NDK_VERSION=27.1.12297006
CMAKE_VERSION=3.22.1
ANDROID_PLATFORM=android-34
NDK="$SDK/ndk/$NDK_VERSION"
CM="$SDK/cmake/$CMAKE_VERSION/bin"

# name repository commit sha256-of-`git archive --format=tar <commit>` directory-in-the-slimt-tree
SOURCES=$(cat <<'LIST'
slimt https://github.com/DavidVentura/slimt 2beb403abfff5b2f4c165a6d51362c973d037e97 7cc3027038b1553eca66523bb758abcb12527002bd20c94d459ed1ac3d55f6f9 .
ruy https://github.com/google/ruy c04e5e52ae6b144f74ac032652e3c538bda15c9b 76c5f5b732a853ab2abbd08bea17d29e03a7b1bca6f6631f231bccdc74005e10 3rd-party/ruy
cpuinfo https://github.com/pytorch/cpuinfo 082deffc80ce517f81dc2f3aebe6ba671fcd09c9 8b169d6e14c83c6ee14f3f5f39db254ad60916417d4d88c3dcb7ff2458d92747 3rd-party/ruy/third_party/cpuinfo
sentencepiece https://github.com/browsermt/sentencepiece 8cbdf13794284c30877936f91c6f31e2c1d5aef7 0e18f5be9f6e0cd6aee3ce9364c6c3ff9c97dee1c02b1c137232c0c3830228ff 3rd-party/sentencepiece
LIST
)

# The Bergamot files the app downloads (core MtBundle.kt; MtManifestTest keeps the two in step), under
# https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models/ :
# installed name, path, .gz bytes, .gz sha256, bytes, sha256. One vocabulary serves both directions.
MODEL_BASE=https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models
MODELS=$(cat <<'LIST'
model.fren.intgemm.alphas.bin fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/model.fren.intgemm.alphas.bin.gz 23175075 06b1eeedd3944260d00a393d12ec7192490c5b7c930b97212faf4d5f5c90a2ee 31561787 15f997bc0d13808b0b0fbd0786e684a3c8a52adcd8071844b76123fdacbf2b90
lex.50.50.fren.s2t.bin fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/lex.50.50.fren.s2t.bin.gz 2649934 395aa7767220e1bcfc085f2b2787ff98005d0075e55451f1e0832885e3d9642a 4824120 87c6752ea908f5f0347c10ac0cf7d80d9c2f4f20c81c90168f3e8230b56d4440
vocab.fren.spm fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/vocab.fren.spm.gz 409706 8d15b219ffd32327b4cabf0d94a05a4fecb8923a4f386badbcbe5e86ede453a7 814404 783abf3abe075afdf8d85d233994bef2c3a064e935ab1bed946820aff6ac002a
model.enfr.intgemm.alphas.bin en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/model.enfr.intgemm.alphas.bin.gz 23045432 22cdaf3dc9a1da6e5818b5576737182467f3cd97e6fd6f94e4273eb7b56d1e3f 31561787 6322e296d4fecfe395a8d5723da4ec37ecbe6d7613bb1dfcf4b28e2a47498b68
lex.50.50.enfr.s2t.bin en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/lex.50.50.enfr.s2t.bin.gz 2297334 055987f0b585b61c4914fb4469e2392fda0b25d3f7784275974f920e7b631997 4372936 2585ed98d3af0bc949865aedeb390493d591f56870814376e73e4144c41ed059
LIST
)

sha() { sha256sum "$1" | cut -d' ' -f1; }
check() { # file sha256
  local got; got=$(sha "$1")
  [ "$got" = "$2" ] || { echo "SHA-256 MISMATCH $1: $got != $2" >&2; exit 1; }
  echo "ok  $(basename "$1")"
}

fetch_sources() {
  mkdir -p "$WORK/git" "$WORK/tar"
  while read -r name repo commit want dir; do
    local g="$WORK/git/$name"
    if ! git -C "$g" cat-file -e "$commit^{commit}" 2>/dev/null; then
      rm -rf "$g"
      git init -q --bare "$g"
      git -C "$g" fetch -q --depth 1 "$repo" "$commit"
    fi
    git -C "$g" archive --format=tar "$commit" > "$WORK/tar/$name.tar"
    check "$WORK/tar/$name.tar" "$want"
  done <<<"$SOURCES"
  # One tree: slimt, with its submodules where its .gitmodules put them (ruy's own cpuinfo inside ruy).
  rm -rf "$WORK/src"
  while read -r name repo commit want dir; do
    mkdir -p "$WORK/src/slimt/$dir"
    tar -xf "$WORK/tar/$name.tar" -C "$WORK/src/slimt/$dir"
  done <<<"$SOURCES"
  rm -rf "$WORK/tar"
}

fetch_models() { # dir: the five files, unpacked and checked as the app does (the .gz first, then what it unpacks to)
  local dest="$1" fails=0
  mkdir -p "$dest"
  while read -r name path gzbytes gzsha bytes want; do
    if [ -f "$dest/$name" ] && [ "$(stat -c %s "$dest/$name")" = "$bytes" ] && [ "$(sha "$dest/$name")" = "$want" ]; then
      echo "ok  $name (already here)"; continue
    fi
    curl -sSfL --retry 5 --retry-delay 3 -o "$dest/$name.gz" "$MODEL_BASE/$path"
    if [ "$(stat -c %s "$dest/$name.gz")" != "$gzbytes" ] || [ "$(sha "$dest/$name.gz")" != "$gzsha" ]; then
      echo "BAD $name.gz (size or SHA-256)" >&2; fails=$((fails + 1)); rm -f "$dest/$name.gz"; continue
    fi
    gzip -dc "$dest/$name.gz" > "$dest/$name.part"
    rm -f "$dest/$name.gz"
    if [ "$(stat -c %s "$dest/$name.part")" != "$bytes" ] || [ "$(sha "$dest/$name.part")" != "$want" ]; then
      echo "BAD $name (unpacked size or SHA-256)" >&2; fails=$((fails + 1)); rm -f "$dest/$name.part"; continue
    fi
    mv "$dest/$name.part" "$dest/$name"
    echo "ok  $name $bytes"
  done <<<"$MODELS"
  [ "$fails" = 0 ] || { echo "$fails model file(s) failed their check" >&2; exit 1; }
}

build_android() {
  [ -x "$CM/cmake" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "needs NDK $NDK_VERSION and CMake $CMAKE_VERSION in $SDK" >&2; exit 1; }
  local b="$WORK/build-arm64-v8a"
  rm -rf "$b"
  "$CM/cmake" -S "$MT/src/main/cpp" -B "$b" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
    -DSLIMT_SOURCE_DIR="$WORK/src/slimt" -DROBOTMT_NEON="${ROBOTMT_NEON:-OFF}" > "$WORK/cmake-arm64-v8a.log"
  "$CM/ninja" -C "$b" robotmt > "$WORK/ninja-arm64-v8a.log"
  local so="$b/librobotmt.so" re="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
  # What the library may need and export: Android's libc, libm, libdl and liblog (cpuinfo's own error lines about the
  # processor; Robot's code logs nothing); exactly the three JNI functions; 16 KB pages.
  local needed; needed=$("$re" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
  [ "$needed" = "libc.so libdl.so liblog.so libm.so " ] || { echo "unexpected NEEDED: $needed" >&2; exit 1; }
  local exports; exports=$("$re" --dyn-syms "$so" | awk '$5 == "GLOBAL" && $7 != "UND" { print $8 }' | sort | tr '\n' ' ')
  [ "$exports" = "Java_app_heyrobot_mt_Slimt_nativeClose Java_app_heyrobot_mt_Slimt_nativeOpen Java_app_heyrobot_mt_Slimt_nativeTranslate " ] \
    || { echo "unexpected exports: $exports" >&2; exit 1; }
  "$re" -l "$so" | awk '$1 == "LOAD" { if ($NF != "0x4000") bad = 1 } END { exit bad }' || { echo "LOAD segments not 16 KB aligned" >&2; exit 1; }
  mkdir -p "$OUT"
  cp "$so" "$OUT/librobotmt.so"
  rm -rf "$b"
  echo "built $OUT/librobotmt.so $(stat -c %s "$OUT/librobotmt.so") bytes sha256 $(sha "$OUT/librobotmt.so")"
  echo "needs: $needed"
}

build_check() {
  local b="$WORK/build-host"
  rm -rf "$b"
  local cc=gcc cxx=g++ cmake="$CM/cmake" ninja="$CM/ninja"
  command -v clang++ >/dev/null && { cc=clang; cxx=clang++; }
  command -v cmake >/dev/null && cmake=cmake
  command -v ninja >/dev/null && ninja=ninja
  "$cmake" -S "$MT/src/main/cpp" -B "$b" -G Ninja -DCMAKE_MAKE_PROGRAM="$(command -v "$ninja")" -DCMAKE_C_COMPILER="$cc" \
    -DCMAKE_CXX_COMPILER="$cxx" -DCMAKE_BUILD_TYPE=Release -DSLIMT_SOURCE_DIR="$WORK/src/slimt" > "$WORK/cmake-host.log"
  "$ninja" -C "$b" robotmt_check > "$WORK/ninja-host.log"
  local rc=0
  "$b/robotmt_check" --split-test || rc=$?
  fetch_models "$WORK/models"
  # slimt prints a harmless "[warn] Failed to ingest expected load of special:model.yml" per model to stderr.
  "$b/robotmt_check" "$WORK/models" "$HERE/tools/lang/fr-CA/mt-test.tsv" || rc=$?
  rm -rf "$b"
  return $rc
}

case "${1:-}" in
  --clean) rm -rf "$WORK"; echo "removed $WORK" ;;
  --models) fetch_models "${2:?usage: build_robotmt.sh --models <dir>}" ;;
  --check) fetch_sources; build_android; build_check ;;
  "") fetch_sources; build_android ;;
  *) echo "usage: tools/build_robotmt.sh [--check | --models <dir> | --clean]" >&2; exit 2 ;;
esac
