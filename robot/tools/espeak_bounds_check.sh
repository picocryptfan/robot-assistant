#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# The check behind third_party/patches/espeak-ng-ed530aa1-robot.patch: builds espeak-ng for this computer with
# AddressSanitizer, once as sherpa-onnx pins it and once patched, and runs the same texts through both. Every text below
# must crash the first and not the second, and ordinary English and French must give the same phonemes from both. It runs
# by hand (the JVM suites have no compiler for it), and again when the patch changes.
#
# The texts: the ones Robot's patch adds (b-*, ph-* and many-words, made here), and espeak-ng's own crash vectors for what it backports, read
# from its repository at a pinned commit. Needs what tools/build_sherpa.sh needs, cmake, ninja, clang with ASan
# and python3. About 3 minutes; everything goes into voice/native/check/ (delete it with build_sherpa.sh --clean). A build
# folder there whose CMake cache names another source path (copied from another worktree) is removed and rebuilt.
#
# Usage: tools/espeak_bounds_check.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SHERPA_WORK:-$HERE/voice/native}"
export SHERPA_WORK="$WORK"
"$HERE/tools/build_sherpa.sh" --source || exit 2
C="$WORK/check"; V="$C/vectors"
UPSTREAM=ba90c8e9f440ad544f674a790bb5f53878b6ffc5 # espeak-ng/espeak-ng, the commit whose tests/crash_vectors are used
mkdir -p "$V"

# name  which fix it shows
TEXTS=$(cat <<'LIST'
b-long the "B" command's list bound (j)
b-list-repeat the "B" command's buffer bound (ix)
b-repeat the "B" command's buffer bound (ix)
ph-announce the bound on punctuation announcements: only the "B" command turns them on
many-words the word count passed down (espeak-ng 17ca9c9c): plain text, no commands
ph-list-start the start of the phoneme list (espeak-ng 8afe77ba): plain text, through the call voices use
cve-2023-49990 CVE-2023-49990
cve-2023-49991 CVE-2023-49991
cve-2023-49992 CVE-2023-49992
cve-2023-49993 CVE-2023-49993
cve-2023-49994 CVE-2023-49994
dotted-abbrev-stack-oob dotted abbreviations (espeak-ng 17ca9c9c)
dotted-abbrev-skipwords-oob dotted abbreviations (espeak-ng 17ca9c9c)
suffix-chain-end-phonemes-oob stacked suffixes (espeak-ng 210dcced)
LIST
)
python3 - "$V" <<'PY'
import sys
v = sys.argv[1] + '/'
open(v + 'b-long.txt', 'wb').write(b'\x01B' + b'x' * 400 + b' ok.\n')
open(v + 'b-list-repeat.txt', 'wb').write(b'\x01Bx ' * 400 + b'ok.\n')
open(v + 'b-repeat.txt', 'wb').write(b'\x01B1' * 400 + b' ok.\n')
open(v + 'ph-announce.txt', 'wb').write(b'1' + b'\x01B1' * 66 + b'\x01B' + b']' * 63 + b'1' + b'\x01B1' * 125 + b'\x01BB'
                                         + b'\x01B1' * 8 + b'\xff' * 40)
open(v + 'many-words.txt', 'wb').write(b'a ' * 320 + b'\n')
open(v + 'ph-list-start.txt', 'wb').write(b'"[[---\xe0\xa4\x88\xe0\xa4\xb5-!-V-0,-\n')
PY
while read -r n _; do
  case "$n" in b-*|many-words|ph-*) ;; *)
    [ -s "$V/$n.txt" ] || curl -sSfL -o "$V/$n.txt" "https://raw.githubusercontent.com/espeak-ng/espeak-ng/$UPSTREAM/tests/crash_vectors/$n.txt" \
      || { echo "couldn't fetch the vector $n" >&2; exit 2; } ;;
  esac
done <<<"$TEXTS"

build() { # source build-dir targets...
  local s=$1 b=$2; shift 2
  # A build folder configured from another source path (copied from another worktree, or the worktree moved) makes CMake
  # stop: removed and configured again, never reused.
  if [ -f "$b/CMakeCache.txt" ] && ! grep -qxF "CMAKE_HOME_DIRECTORY:INTERNAL=$s" "$b/CMakeCache.txt"; then
    echo "removing $b: its CMake cache was made from another source path" >&2
    rm -rf "${b:?}"
  fi
  cmake -S "$s" -B "$b" -G Ninja -DCMAKE_C_COMPILER=clang -DCMAKE_BUILD_TYPE=Debug -DCMAKE_C_FLAGS='-fsanitize=address -fno-omit-frame-pointer -g -O1' \
    -DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address -DUSE_ASYNC=OFF -DUSE_MBROLA=OFF -DUSE_LIBSONIC=OFF -DUSE_LIBPCAUDIO=OFF \
    -DUSE_KLATT=OFF -DUSE_SPEECHPLAYER=OFF -DBUILD_SHARED_LIBS=OFF > "$b.log" 2>&1 \
    && ninja -C "$b" -j "${SHERPA_JOBS:-2}" "$@" >> "$b.log" 2>&1 || { tail -3 "$b.log" >&2; exit 2; }
}
export ASAN_OPTIONS=detect_leaks=0
# Patched first and with the voice data, which both then read: under clang's ASan, unpatched can't compile it.
build "$WORK/src/espeak-ng" "$C/fixed" espeak-ng-bin data
build "$WORK/src/espeak-ng-orig" "$C/orig" espeak-ng-bin
# The call Robot's voices make, which the espeak-ng program doesn't: some texts only go wrong through it.
cat > "$C/phon.c" <<'SRC'
#include <stdio.h>
#include <espeak-ng/speak_lib.h>
int main(int argc, char **argv) {
  static char s[70000];
  FILE *f = fopen(argv[2], "rb");
  if (!f) return 2;
  s[fread(s, 1, sizeof s - 1, f)] = 0;
  espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, argv[1], 0);
  espeak_SetVoiceByName("en-us");
  const void *p = s;
  while (p) espeak_TextToPhonemes(&p, espeakCHARS_UTF8, espeakPHONEMES_IPA);
  return 0;
}
SRC
for b in orig fixed; do
  clang -fsanitize=address -g -I"$WORK/src/espeak-ng/src/include" "$C/phon.c" -Wl,--start-group $(find "$C/$b" -name 'lib*.a') \
    -Wl,--end-group -lm -o "$C/$b/phon" || exit 2
done
run() { # build text
  case "$2" in ph-*) timeout 60 "$C/$1/phon" "$C/fixed" "$V/$2.txt" > /dev/null 2> "$C/run.err"; return ;; esac
  ESPEAK_DATA_PATH="$C/fixed" timeout 60 "$C/$1/src/espeak-ng-bin" -w /dev/null -f "$V/$2.txt" > /dev/null 2> "$C/run.err"; local a=$?
  ESPEAK_DATA_PATH="$C/fixed" timeout 60 "$C/$1/src/espeak-ng-bin" -q --ipa -f "$V/$2.txt" > /dev/null 2>> "$C/run.err"; local b=$?
  [ $a -eq 0 ] && [ $b -eq 0 ]
}
bad=0
printf '%-32s %-9s %-9s %s\n' text unpatched patched shows
while read -r n why; do
  if run orig "$n"; then o=no-crash; bad=1; else o=crash; fi
  if run fixed "$n"; then f=clean; else f=CRASH; bad=1; fi
  printf '%-32s %-9s %-9s %s\n' "$n" "$o" "$f" "$why"
done <<<"$TEXTS"

cat > "$C/en.txt" <<'TXT'
The quick brown fox jumps over the lazy dog. Dr. Smith's U.S.A. trip cost $1,234.56 on Jan. 3rd, 2026!
Set a timer for 10 minutes; call Mom at 555-0123? "Yes," she said -- it's 3:45 p.m. e.g. a.b.c. i.e. n't don't we'll.
Running walked happily nations's dogs' boxes unbelievably re-enter 1st 2nd 3rd 21st CXIV Henry VIII.
TXT
cat > "$C/fr.txt" <<'TXT'
Bonjour, je m'appelle Robot. Quelle heure est-il ? Il est 15 h 30, l'été dernier, à Montréal : c'était très bien !
M. Tremblay a acheté 1 234,56 $ de pâtisseries ; aujourd'hui, c'est-à-dire demain, n'est-ce pas ?
TXT
for p in "en-us en" "fr fr"; do
  set -- $p
  a=$(ESPEAK_DATA_PATH="$C/fixed" "$C/orig/src/espeak-ng-bin" -q --ipa -v "$1" -f "$C/$2.txt" 2>&1)
  b=$(ESPEAK_DATA_PATH="$C/fixed" "$C/fixed/src/espeak-ng-bin" -q --ipa -v "$1" -f "$C/$2.txt" 2>&1)
  if [ -n "$a" ] && [ "$a" = "$b" ]; then echo "phonemes $1: the same (${#a} characters)"; else echo "phonemes $1: DIFFER"; bad=1; fi
done
[ $bad = 0 ] && echo "all as expected" || { echo "NOT as expected" >&2; exit 1; }
