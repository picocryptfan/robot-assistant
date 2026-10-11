#!/usr/bin/env bash
# Builds Robot's GGUF engine (0.4.5): librobot_llama.so for the :brain process, from the pinned llama.cpp source and
# Robot's own JNI bridge (brain/src/main/cpp), with the Android NDK. Nothing built here is ever committed: the sources
# go to build-native/ and the library to brain/src/main/jniLibs/arm64-v8a/, both gitignored. Without the library the
# app still builds and runs; Settings › Model then says GGUF files can't run in that build.
#
# The sources are checked before anything is built (THIRD_PARTY.md):
#   llama.cpp: the tag's commit, and the SHA-256 of its source tar (the tar `git archive` makes of the commit with the
#              prefix llama.cpp-<tag>/, which is what GitHub's .tar.gz of the tag holds, uncompressed);
#   KleidiAI:  the release tarball llama.cpp's own CMake names (its SHA-256 and the MD5 llama.cpp pins), or the tag's
#              commit and its source tar when that download isn't reachable.
# Then CMake builds llama.cpp, ggml and KleidiAI as static libraries and links them with robot_llama.cpp into one
# shared library that exports only its JNI functions; the script checks what it exports and imports before copying it.
#
# Usage: tools/build_llama.sh [--git] [--clean]
#   --git    fetch llama.cpp with git (the commit, then the same tar check) instead of GitHub's tarball
#   --clean  delete the CMake build folder afterwards (about 300 MB; the checked sources stay for the next build)
# Needs: curl, git, tar, gzip, sha256sum, md5sum, cmake (3.22+), ninja, and the NDK (ANDROID_NDK_HOME, or the default below).
set -euo pipefail

LLAMA_TAG=b11200
LLAMA_COMMIT=81bc6b83f827df746eb129235488d325c49cae52
# SHA-256 of `git archive --format=tar --prefix=llama.cpp-b11200/ 81bc6b83…` (176,046,080 bytes).
LLAMA_TAR_SHA256=26aa9265184fb08bf847f3fe843a2d7351985e67757abf270ea366bbe451e07b
LLAMA_URL=https://github.com/ggml-org/llama.cpp/archive/refs/tags/$LLAMA_TAG.tar.gz
LLAMA_GIT=https://github.com/ggml-org/llama.cpp

# The KleidiAI release llama.cpp b11200's ggml-cpu CMake fetches (KLEIDIAI_COMMIT_TAG, KLEIDIAI_RELEASE_ARCHIVE_MD5).
KLEIDIAI_TAG=v1.24.0
KLEIDIAI_COMMIT=0b2ee513397578a272a4e8a6c12589ef6534371c
KLEIDIAI_URL=https://github.com/ARM-software/kleidiai/releases/download/$KLEIDIAI_TAG/kleidiai-$KLEIDIAI_TAG-src.tar.gz
KLEIDIAI_TGZ_SHA256=9348b969e042d8890a54b01a463dbe71f5a4c074b5329e9c26a85ef3b68aa19b
KLEIDIAI_TGZ_MD5=2f02ebe29573d45813e671eb304f2a00
# SHA-256 of `git archive --format=tar --prefix=kleidiai-v1.24.0/ 0b2ee513…`, for the git fallback.
KLEIDIAI_TAR_SHA256=7665267ade0042fe158d2a9778c09563f3f93b759c7b0daa82ba28d709afd537
KLEIDIAI_GIT=https://github.com/ARM-software/kleidiai

HERE="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-/opt/android-sdk/ndk/27.1.12297006}}"
WORK="$HERE/build-native"
SRC="$WORK/src"
BUILD="$WORK/build"
OUT="$HERE/brain/src/main/jniLibs/arm64-v8a"
LIB=librobot_llama.so
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

use_git=0
clean=0
for a in "$@"; do
  case "$a" in
    --git) use_git=1 ;;
    --clean) clean=1 ;;
    *) echo "usage: $0 [--git] [--clean]"; exit 2 ;;
  esac
done

[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "No Android NDK at $NDK (set ANDROID_NDK_HOME)"; exit 1; }
for t in curl git tar gzip sha256sum md5sum cmake ninja; do command -v "$t" >/dev/null || { echo "missing: $t"; exit 1; }; done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
sha() { sha256sum | cut -d' ' -f1; }

# A shallow fetch of [tag] from [repo] into $TMP/<name>.git; checks the commit and the tar `git archive` makes of it,
# and leaves that very tar at $TMP/<name>.tar.
git_tar() { # name repo tag commit prefix tar_sha256
  local d="$TMP/$1.git"
  git init -q --bare "$d"
  git -C "$d" fetch -q --depth 1 "$2" "refs/tags/$3:refs/tags/$3"
  local got; got=$(git -C "$d" rev-parse "$3^{commit}")
  [ "$got" = "$4" ] || { echo "COMMIT MISMATCH $1 $3: $got != $4"; exit 1; }
  git -C "$d" archive --format=tar --prefix="$5/" "$4" > "$TMP/$1.tar"
  got=$(sha < "$TMP/$1.tar")
  [ "$got" = "$6" ] || { echo "SHA-256 MISMATCH $1 source tar: $got != $6"; exit 1; }
}

# ------------------------------------------------------------------ llama.cpp
LLAMA_DIR="$SRC/llama.cpp-$LLAMA_TAG"
if [ "$(cat "$LLAMA_DIR/.robot-checked" 2>/dev/null)" != "$LLAMA_TAR_SHA256" ]; then
  rm -rf "$LLAMA_DIR"
  mkdir -p "$SRC"
  fetched=0
  if [ $use_git = 0 ] && curl -sSfL -o "$TMP/llama.tar.gz" "$LLAMA_URL"; then
    got=$(gzip -dc "$TMP/llama.tar.gz" | sha)
    [ "$got" = "$LLAMA_TAR_SHA256" ] || { echo "SHA-256 MISMATCH llama.cpp $LLAMA_TAG tarball (uncompressed): $got != $LLAMA_TAR_SHA256 (try --git)"; exit 1; }
    gzip -dc "$TMP/llama.tar.gz" > "$TMP/llama.tar"
    fetched=1
  fi
  if [ $fetched = 0 ]; then
    [ $use_git = 1 ] || echo "GitHub's tarball isn't reachable: fetching the tag with git instead"
    git_tar llama "$LLAMA_GIT" "$LLAMA_TAG" "$LLAMA_COMMIT" "llama.cpp-$LLAMA_TAG" "$LLAMA_TAR_SHA256"
  fi
  tar xf "$TMP/llama.tar" -C "$SRC"
  echo "$LLAMA_TAR_SHA256" > "$LLAMA_DIR/.robot-checked"
  echo "ok  llama.cpp $LLAMA_TAG ($LLAMA_COMMIT), source tar $LLAMA_TAR_SHA256"
else
  echo "ok  llama.cpp $LLAMA_TAG already checked in $LLAMA_DIR"
fi

# ------------------------------------------------------------------ KleidiAI
KLEIDIAI_DIR="$SRC/kleidiai-$KLEIDIAI_TAG"
if [ "$(cat "$KLEIDIAI_DIR/.robot-checked" 2>/dev/null)" != "$KLEIDIAI_COMMIT" ]; then
  rm -rf "$KLEIDIAI_DIR"
  if curl -sSfL -o "$TMP/kleidiai.tar.gz" "$KLEIDIAI_URL"; then
    got=$(sha < "$TMP/kleidiai.tar.gz")
    [ "$got" = "$KLEIDIAI_TGZ_SHA256" ] || { echo "SHA-256 MISMATCH KleidiAI $KLEIDIAI_TAG: $got != $KLEIDIAI_TGZ_SHA256"; exit 1; }
    got=$(md5sum < "$TMP/kleidiai.tar.gz" | cut -d' ' -f1)
    [ "$got" = "$KLEIDIAI_TGZ_MD5" ] || { echo "MD5 MISMATCH KleidiAI $KLEIDIAI_TAG: $got != $KLEIDIAI_TGZ_MD5"; exit 1; }
    gzip -dc "$TMP/kleidiai.tar.gz" > "$TMP/kleidiai.tar"
  else
    echo "KleidiAI's release tarball isn't reachable: fetching the tag with git instead"
    git_tar kleidiai "$KLEIDIAI_GIT" "$KLEIDIAI_TAG" "$KLEIDIAI_COMMIT" "kleidiai-$KLEIDIAI_TAG" "$KLEIDIAI_TAR_SHA256"
  fi
  tar xf "$TMP/kleidiai.tar" -C "$SRC"
  [ -f "$KLEIDIAI_DIR/CMakeLists.txt" ] || { echo "KleidiAI's source isn't where expected: $KLEIDIAI_DIR"; exit 1; }
  echo "$KLEIDIAI_COMMIT" > "$KLEIDIAI_DIR/.robot-checked"
  echo "ok  KleidiAI $KLEIDIAI_TAG ($KLEIDIAI_COMMIT)"
else
  echo "ok  KleidiAI $KLEIDIAI_TAG already checked in $KLEIDIAI_DIR"
fi

# ------------------------------------------------------------------ build
# FETCHCONTENT_FULLY_DISCONNECTED: CMake uses the checked KleidiAI folder and never downloads anything itself.
cmake -G Ninja -S "$HERE/brain/src/main/cpp" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_SRC="$LLAMA_DIR" \
  -DFETCHCONTENT_SOURCE_DIR_KLEIDIAI="$KLEIDIAI_DIR" -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
  > "$WORK/cmake-configure.log"
cmake --build "$BUILD" --target robot_llama -j "$(nproc)" > "$WORK/cmake-build.log" || { tail -30 "$WORK/cmake-build.log"; exit 1; }
grep -q 'Using KleidiAI optimized kernels' "$WORK/cmake-configure.log" && echo "ok  KleidiAI kernels built in" || { echo "KleidiAI was not enabled"; exit 1; }

built="$BUILD/$LIB"
stripped="$TMP/$LIB"
"$BIN/llvm-strip" --strip-unneeded -o "$stripped" "$built"

# ------------------------------------------------------------------ checks
fails=0
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
hdr=$("$BIN/llvm-readelf" -h "$stripped")
grep -q 'Machine:.*AArch64' <<<"$hdr" || fail "not an AArch64 library"
# 16 KB pages (Android 15 and later may use them): every LOAD segment aligned to at least 16 KB.
for align in $("$BIN/llvm-readelf" -lW "$stripped" | awk '$1 == "LOAD" { print $NF }'); do
  [ $((align)) -ge 16384 ] || fail "a LOAD segment is aligned to $align, below 16 KB"
done
# Exports: only Robot's JNI functions.
exports=$("$BIN/llvm-nm" -D --defined-only "$stripped" | awk '{ print $3 }' | sort)
others=$(grep -vE '^(JNI_OnLoad|Java_app_heyrobot_brain_LlamaNative_[A-Za-z]+)$' <<<"$exports" || true)
[ -z "$others" ] || fail "exports more than its JNI functions: $(echo $others | head -c 300)"
for f in load free cancel trainContext prepare generate; do
  grep -qx "Java_app_heyrobot_brain_LlamaNative_$f" <<<"$exports" || fail "doesn't export LlamaNative.$f"
done
# Imports: the C library and the dynamic loader only; no network, no GPU, no process spawning.
needed=$("$BIN/llvm-readelf" -d "$stripped" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
for n in $needed; do
  case "$n" in libc.so|libm.so|libdl.so|liblog.so) ;; *) fail "needs $n" ;; esac
done
imports=$("$BIN/llvm-nm" -D --undefined-only "$stripped" | awk '{ print $2 }' | sed 's/@.*//')
net=$(grep -xE 'socket|connect|getaddrinfo|gethostbyname|bind|listen|accept4?|sendto|recvfrom|fork|vfork|clone|daemon|execv[pe]?|execl[pe]?|execve|posix_spawnp?|popen|system' <<<"$imports" || true)
[ -z "$net" ] || fail "imports network or process functions: $(echo $net)"
[ "$fails" = 0 ] || { echo "$fails check(s) failed: $LIB not installed"; exit 1; }

mkdir -p "$OUT"
cp "$stripped" "$OUT/$LIB"
echo "ok  $LIB: exports $(wc -l <<<"$exports") JNI symbols, needs $needed"
echo "    $(stat -c %s "$OUT/$LIB") bytes, SHA-256 $(sha < "$OUT/$LIB")"
echo "    installed at ${OUT#"$HERE/"}/$LIB (gitignored)"
if [ $clean = 1 ]; then
  cp "$built" "$WORK/$LIB.unstripped"
  rm -rf "$BUILD"
  echo "    build folder removed; the unstripped copy (for a crash's symbols) is at ${WORK#"$HERE/"}/$LIB.unstripped"
else
  echo "    the unstripped copy (for a crash's symbols) is at ${built#"$HERE/"}"
fi
