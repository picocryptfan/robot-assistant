#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Builds libsherpa-onnx-jni.so, the speech library of the :voice process, for arm64-v8a: sherpa-onnx v1.13.8 as its own
# build-android-arm64-v8a.sh builds it (the same CMake options), except that espeak-ng, the fork sherpa-onnx pins, has
# third_party/patches/espeak-ng-ed530aa1-robot.patch applied. The patch bounds the writes text can reach in that fork
# (the patch's first lines list them, THIRD_PARTY.md says where each comes from). sherpa-onnx's own release of the library
# doesn't have them: tools/fetch_third_party.sh still installs that one, which is enough for the unit tests, this script
# replaces it, and tools/verify_apk.sh refuses any other build of it in an APK.
#
# Every source is checked before anything is built: sherpa-onnx by its commit id, the espeak-ng archive and the prebuilt
# ONNX Runtime by their SHA-256, sherpa-onnx's other dependencies by the SHA-256 its CMake files pin. Everything goes into
# voice/native/ (not in git; SHERPA_WORK=<dir> puts it elsewhere). The library goes into voice/src/main/jniLibs/arm64-v8a/
# (not in git), where Gradle packages it. libonnxruntime.so stays the one tools/fetch_third_party.sh installs: this build
# links against the same file.
#
# Needs git, curl, unzip, patch, sha256sum and the Android SDK's NDK and CMake at the versions below (ANDROID_HOME,
# default /opt/android-sdk). About 15 minutes with the default two jobs (SHERPA_JOBS=<n>); voice/native/ holds about 1 GB.
#
# Usage: tools/build_sherpa.sh            fetch, check, patch and build
#        tools/build_sherpa.sh --source   stop after unpacking: voice/native/src/espeak-ng (patched) and espeak-ng-orig
#        tools/build_sherpa.sh --clean    delete voice/native/
set -euo pipefail
# A copy goes to a new file renamed into place, never written into the old one: a hard link to the old file elsewhere
# (a worktree made by wt-local.sh) keeps its bytes, and a failed copy leaves the old file. cp_new SRC... DEST (a file, or
# a folder that exists).
cp_new() {
  local d="${!#}" s t tmp
  for s in "${@:1:$#-1}"; do
    t=$d; [ -d "$d" ] && t="$d/${s##*/}"
    # A hidden name (.NAME.new.PID): one left by a run cut short is never packaged (AGP skips dotfiles).
    [[ $t == */* ]] && tmp="${t%/*}/.${t##*/}.new.$$" || tmp=".$t.new.$$"
    cp -- "$s" "$tmp" || { rm -f -- "$tmp"; return 1; }
    mv -f -- "$tmp" "$t" || return 1
  done
}
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SHERPA_WORK:-$HERE/voice/native}"
OUT="$HERE/voice/src/main/jniLibs/arm64-v8a"
PATCH="$HERE/third_party/patches/espeak-ng-ed530aa1-robot.patch"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
NDK_VERSION=27.1.12297006
CMAKE_VERSION=3.22.1
ANDROID_PLATFORM=android-21 # what sherpa-onnx's script uses
NDK="$SDK/ndk/$NDK_VERSION"
CM="$SDK/cmake/$CMAKE_VERSION/bin"
JOBS="${SHERPA_JOBS:-2}"

SHERPA_REPO=https://github.com/k2-fsa/sherpa-onnx
SHERPA_COMMIT=11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf # tag v1.13.8
ESPEAK_COMMIT=ed530aa113046142eb5115cf2fc9157854d0ffe1 # cmake/espeak-ng-for-piper.cmake in that commit
ESPEAK_ZIP_SHA=e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd
ORT_VERSION=1.28.2
ORT_SO_SHA=33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1 # the file fetch_third_party.sh installs
# What this script builds, from any directory (verify_apk.sh refuses anything else in a release).
BUILT_SHA=8d3a191cdf05cd712c5b912545b3c7a0cd02101de84e4765579a483e5694c7ab

sha() { sha256sum "$1" | cut -d' ' -f1; }
check() { [ "$(sha "$1")" = "$2" ] || { echo "SHA-256 MISMATCH $1: $(sha "$1") != $2" >&2; exit 1; }; echo "ok  $(basename "$1")"; }

if [ "${1:-}" = "--clean" ]; then rm -rf "$WORK"; echo "deleted $WORK"; exit 0; fi

mkdir -p "$WORK/src"

# --- espeak-ng: the archive sherpa-onnx pins, and a patched copy of it. -----------------------------------------------
ZIP="$WORK/src/espeak-ng-$ESPEAK_COMMIT.zip"
[ -f "$ZIP" ] || curl -sSfL -o "$ZIP" "https://github.com/csukuangfj/espeak-ng/archive/$ESPEAK_COMMIT.zip"
check "$ZIP" "$ESPEAK_ZIP_SHA"
rm -rf "$WORK/src/espeak-ng" "$WORK/src/espeak-ng-orig"
unzip -q "$ZIP" -d "$WORK/src"
mv "$WORK/src/espeak-ng-$ESPEAK_COMMIT" "$WORK/src/espeak-ng-orig"
cp -a "$WORK/src/espeak-ng-orig" "$WORK/src/espeak-ng"
patch -s -p1 --fuzz=0 -d "$WORK/src/espeak-ng" < "$PATCH"
echo "ok  $(basename "$PATCH") applied"
[ "${1:-}" = "--source" ] && exit 0

for t in "$NDK/build/cmake/android.toolchain.cmake" "$CM/cmake" "$CM/ninja"; do
  [ -e "$t" ] || { echo "missing $t (Android SDK: NDK $NDK_VERSION, CMake $CMAKE_VERSION)" >&2; exit 2; }
done

# --- sherpa-onnx at its commit. ----------------------------------------------------------------------------------------
SRC="$WORK/src/sherpa-onnx"
if [ ! -f "$SRC/CMakeLists.txt" ]; then
  G="$WORK/src/sherpa.git"
  rm -rf "$G" "$SRC"; git init -q --bare "$G"
  git -C "$G" fetch -q --depth=1 "$SHERPA_REPO" "$SHERPA_COMMIT"
  [ "$(git -C "$G" rev-parse FETCH_HEAD)" = "$SHERPA_COMMIT" ] || { echo "sherpa-onnx is not at $SHERPA_COMMIT" >&2; exit 1; }
  mkdir -p "$SRC"; git -C "$G" archive FETCH_HEAD | tar -x -C "$SRC"
  rm -rf "$G"
fi
grep -q "archive/$ESPEAK_COMMIT.zip" "$SRC/cmake/espeak-ng-for-piper.cmake" || { echo "sherpa-onnx pins another espeak-ng" >&2; exit 1; }
grep -q "SHA256=$ESPEAK_ZIP_SHA" "$SRC/cmake/espeak-ng-for-piper.cmake" || { echo "sherpa-onnx pins another espeak-ng archive" >&2; exit 1; }

# --- The ONNX Runtime it links: the prebuilt its Android build downloads, with the SHA-256 its CMake file pins. ---------
ORT="$WORK/src/onnxruntime-$ORT_VERSION"
ORT_ZIP_SHA=$(sed -n 's/^set(onnxruntime_HASH "SHA256=\([0-9a-f]\{64\}\)")/\1/p' "$SRC/cmake/onnxruntime-android-aarch64.cmake")
[ -n "$ORT_ZIP_SHA" ] || { echo "couldn't read ONNX Runtime's pin" >&2; exit 1; }
if [ ! -f "$ORT/jni/arm64-v8a/libonnxruntime.so" ]; then
  Z="$WORK/src/onnxruntime-android-$ORT_VERSION.zip"
  curl -sSfL -o "$Z" "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ORT_VERSION/onnxruntime-android-$ORT_VERSION.zip"
  check "$Z" "$ORT_ZIP_SHA"
  rm -rf "$ORT"; mkdir -p "$ORT"; unzip -q "$Z" -d "$ORT"; rm -f "$Z"
fi
check "$ORT/jni/arm64-v8a/libonnxruntime.so" "$ORT_SO_SHA"

# --- The build: sherpa-onnx's script's options, with the patched espeak-ng in place of the download. --------------------
# The prefix maps keep this computer's paths out of the library, so the same sources give the same bytes anywhere.
B="$WORK/build"
rm -rf "$B"; mkdir -p "$B"
MAPS="-ffile-prefix-map=$WORK=. -ffile-prefix-map=$NDK=ndk"
export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT/jni/arm64-v8a/" SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT/headers/"
"$CM/cmake" -S "$SRC" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DFETCHCONTENT_SOURCE_DIR_ESPEAK_NG="$WORK/src/espeak-ng" \
  -DCMAKE_C_FLAGS="$MAPS" -DCMAKE_CXX_FLAGS="$MAPS" \
  -DSHERPA_ONNX_ENABLE_TTS=ON -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DBUILD_PIPER_PHONMIZE_EXE=OFF -DBUILD_PIPER_PHONMIZE_TESTS=OFF -DBUILD_ESPEAK_NG_EXE=OFF -DBUILD_ESPEAK_NG_TESTS=OFF \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_CHECK=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF -DSHERPA_ONNX_ENABLE_C_API=OFF -DCMAKE_INSTALL_PREFIX="$B/install" \
  -DSHERPA_ONNX_ENABLE_RKNN=OFF -DSHERPA_ONNX_ENABLE_QNN=OFF \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$ANDROID_PLATFORM" -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON > "$B/configure.log"
"$CM/cmake" --build "$B" -j "$JOBS" --target sherpa-onnx-jni > "$B/build.log"
"$CM/cmake" --build "$B" -j "$JOBS" --target install/strip >> "$B/build.log"
LIB="$B/install/lib/libsherpa-onnx-jni.so"

# --- What Robot needs of it: 16 KB pages, and only the libraries it has always needed. ---------------------------------
RE="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$RE" -lW "$LIB" | awk '$1 == "LOAD" && $NF != "0x4000" { bad = 1 } END { exit bad }' || { echo "not aligned for 16 KB pages: $LIB" >&2; exit 1; }
needed=$("$RE" -d "$LIB" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
[ "$needed" = "libandroid.so libc.so libdl.so liblog.so libm.so libonnxruntime.so " ] || { echo "unexpected NEEDED: $needed" >&2; exit 1; }

mkdir -p "$OUT"
rm -f "$OUT/libsherpa-onnx-jni.so" # never write through a hard link to another worktree's copy
cp_new "$LIB" "$OUT/libsherpa-onnx-jni.so"
got=$(sha "$OUT/libsherpa-onnx-jni.so")
echo "installed $OUT/libsherpa-onnx-jni.so"
echo "sha256 $got"
[ "$got" = "$BUILT_SHA" ] || echo "NOTE: not the pinned build ($BUILT_SHA), so verify_apk.sh will refuse it."
