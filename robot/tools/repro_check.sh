#!/usr/bin/env bash
# Builds Robot's release APK twice, unsigned, and checks that the two are byte for byte the same (reproducible builds).
#
# The first build runs in this checkout; the second in a fresh copy of the committed tree (`git archive HEAD`) in another
# folder, with the git-ignored inputs Gradle packages hard-linked (or copied) in: local.properties, the native libraries
# (brain/, mt/ and voice/src/main/jniLibs) and voice's models and espeak-ng data. So a difference that comes from the
# folder, the time or leftovers of an earlier build shows up. Both builds use `--offline` and the same Gradle, JDK and
# Android SDK, so this proves the build reproducible on this machine; another machine is the next check (README,
# "Releasing").
#
# When they differ, it lists the APK entries whose bytes differ. With apksigcopier (pip install apksigcopier) and a signed
# APK it also checks the way F-Droid does: copies the signed APK's signature onto the rebuild and compares.
#
# Two modes:
#   default   the APKs only. Both builds package the same native libraries, librobot_llama.so and librobotmt.so, the
#             ones already in this checkout (the second build gets hard links to them), so the APKs can match while
#             the libraries would rebuild differently. Two release builds' time.
#   --native  also rebuilds both libraries in each build, from their pinned sources with the pinned NDK and CMake
#             (tools/build_llama.sh, tools/build_robotmt.sh), each in a work folder at another path, compares the two
#             builds' libraries, and packages each build's own libraries into its APK. The second build starts from the
#             first one's checked sources (copied, so it needs no network; build_robotmt.sh checks its tars again).
#             About 9 more minutes on 8 cores; this checkout's libraries are replaced by the ones it builds.
#
# Usage: tools/repro_check.sh [--native [--libs-only]] [--keep] [--signed Robot-x.y.z.apk] [--gradle /path/to/gradle]
#   --native     rebuild and compare the native libraries too (above)
#   --libs-only  with --native: stop after comparing the libraries (no APKs)
#   --keep       keep the second copy's folder (printed at the end)
#   --signed     a published, signed APK of this commit: also run `apksigcopier compare` against the rebuild
#   --gradle     the Gradle to use (default: gradle on the PATH, else /opt/gradle/bin/gradle)
# Needs git, unzip, sha256sum and cmp (--native: what the two build scripts need). Run it by hand (no test runs it).
# The second build goes in a new folder under $TMPDIR (default /tmp; point it at a disk when /tmp is in memory, as
# --native's builds need about 2 GB). Uncommitted changes are left out of the second build, so commit first: the script
# stops when the tree isn't clean.
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
KEEP=0
NATIVE=0
LIBS_ONLY=0
SIGNED=""
GRADLE=$(command -v gradle || echo /opt/gradle/bin/gradle)
while [ $# -gt 0 ]; do
  case "$1" in
    --native) NATIVE=1; shift ;;
    --libs-only) LIBS_ONLY=1; shift ;;
    --keep) KEEP=1; shift ;;
    --signed) SIGNED="$2"; shift 2 ;;
    --gradle) GRADLE="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ "$LIBS_ONLY" = 0 ] || [ "$NATIVE" = 1 ] || { echo "--libs-only goes with --native" >&2; exit 2; }
for t in git unzip sha256sum cmp; do command -v "$t" >/dev/null || { echo "needs $t" >&2; exit 2; }; done
[ "$LIBS_ONLY" = 1 ] || [ -x "$GRADLE" ] || { echo "no Gradle at $GRADLE (--gradle)" >&2; exit 2; }
TOP=$(git -C "$HERE" rev-parse --show-toplevel)
SUB=${HERE#"$TOP"/}
[ -z "$(git -C "$TOP" status --porcelain --untracked-files=no)" ] || { echo "commit your changes first: the second build uses the committed tree" >&2; exit 2; }

APK_REL=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=$(mktemp -d)
COPY="$OUT/tree"
cleanup() { rm -rf "$OUT/w2"; [ "$KEEP" = 1 ] || rm -rf "$COPY"; }
trap cleanup EXIT

build() { # dir out.apk
  (cd "$1" && "$GRADLE" --offline -q clean :app:assembleRelease)
  local apk="$1/$APK_REL"
  [ -f "$apk" ] || apk="$1/app/build/outputs/apk/release/app-release.apk"
  [ -f "$apk" ] || { echo "no release APK in $1/app/build/outputs/apk/release" >&2; exit 1; }
  cp "$apk" "$2"
}

LIBS="brain/src/main/jniLibs/arm64-v8a/librobot_llama.so mt/src/main/jniLibs/arm64-v8a/librobotmt.so"
# Both libraries, built in the tree [dir] with its own scripts, in the work folders [llama work] and [mt work]: a fresh
# CMake build each time (no earlier objects reused).
native() { # dir llama-work mt-work
  rm -rf "$2/build"
  ROBOT_LLAMA_WORK="$2" "$1/tools/build_llama.sh" --clean
  ROBOTMT_WORK="$3" "$1/tools/build_robotmt.sh"
}

DEST="$COPY/$SUB"
if [ "$NATIVE" = 1 ]; then
  LW1="${ROBOT_LLAMA_WORK:-$HERE/build-native}"; MW1="${ROBOTMT_WORK:-$HERE/mt/native}"
  echo "== native 1: $HERE (work folders $LW1, $MW1)"
  native "$HERE" "$LW1" "$MW1"
fi

echo "== copy: a clean copy of $(git -C "$TOP" rev-parse --short HEAD) in $COPY"
mkdir -p "$COPY"
git -C "$TOP" archive --format=tar HEAD "$SUB" | tar -xf - -C "$COPY"
# LICENSE is packaged too (assets/licenses/GPL-3.0.txt is checked against it).
git -C "$TOP" archive --format=tar HEAD LICENSE | tar -xf - -C "$COPY"
inputs="local.properties voice/src/main/jniLibs voice/src/main/assets/espeak-ng-data voice/src/main/assets/vad voice/src/main/assets/kws"
[ "$NATIVE" = 1 ] || inputs="$inputs brain/src/main/jniLibs mt/src/main/jniLibs"
for p in $inputs; do
  [ -e "$HERE/$p" ] || continue
  mkdir -p "$(dirname "$DEST/$p")"
  if [ -d "$HERE/$p" ] && [ ! -L "$HERE/$p" ]; then cp -alf "$HERE/$p/." "$DEST/$p/" 2>/dev/null || cp -af "$HERE/$p/." "$DEST/$p/"
  else cp -alf "$HERE/$p" "$DEST/$p" 2>/dev/null || cp -af "$HERE/$p" "$DEST/$p"; fi
done

status=0
if [ "$NATIVE" = 1 ]; then
  # Another path, outside any git repository: the first build's checked sources, copied in.
  LW2="$OUT/w2/llama"; MW2="$OUT/w2/mt"
  mkdir -p "$LW2" "$MW2"
  cp -a "$LW1/src" "$LW2/src"
  cp -a "$MW1/git" "$MW2/git"
  echo "== native 2: $DEST (work folders $LW2, $MW2)"
  native "$DEST" "$LW2" "$MW2"
  for l in $LIBS; do
    x=$(sha256sum "$HERE/$l" | cut -d' ' -f1); y=$(sha256sum "$DEST/$l" | cut -d' ' -f1)
    echo "$(basename "$l"): first $x"
    echo "$(basename "$l"): second $y"
    if [ "$x" = "$y" ]; then echo "REPRODUCIBLE: $(basename "$l")"; else echo "NOT REPRODUCIBLE: $(basename "$l")"; status=1; fi
  done
  rm -rf "$OUT/w2"
  if [ "$LIBS_ONLY" = 1 ]; then
    [ "$KEEP" = 1 ] && echo "kept: $COPY"
    exit $status
  fi
fi

echo "== build 1: $HERE"
build "$HERE" "$OUT/first.apk"
echo "== build 2: $DEST"
build "$DEST" "$OUT/second.apk"

a=$(sha256sum "$OUT/first.apk" | cut -d' ' -f1)
b=$(sha256sum "$OUT/second.apk" | cut -d' ' -f1)
echo "first:  $a"
echo "second: $b"
if [ "$a" = "$b" ]; then
  echo "REPRODUCIBLE: the two unsigned APKs are identical"
else
  status=1
  echo "NOT REPRODUCIBLE: the entries that differ:"
  mkdir -p "$OUT/x1" "$OUT/x2"
  unzip -q "$OUT/first.apk" -d "$OUT/x1"; unzip -q "$OUT/second.apk" -d "$OUT/x2"
  diff <(cd "$OUT/x1" && find . -type f | sort) <(cd "$OUT/x2" && find . -type f | sort) || true
  (cd "$OUT/x1" && find . -type f | sort) | while read -r f; do
    [ -f "$OUT/x2/$f" ] && ! cmp -s "$OUT/x1/$f" "$OUT/x2/$f" && echo "  differs: ${f#./}"
  done
  echo "  (zip metadata alone differs when no entry is listed)"
fi
if [ -n "$SIGNED" ]; then
  if command -v apksigcopier >/dev/null; then
    apksigcopier compare "$SIGNED" --unsigned "$OUT/first.apk" && echo "apksigcopier: $SIGNED matches the rebuild" \
      || { echo "apksigcopier: $SIGNED does NOT match the rebuild"; status=1; }
  else
    echo "NOTE  no apksigcopier on the PATH: the signed APK wasn't compared"
  fi
fi
[ "$KEEP" = 1 ] && echo "kept: $COPY"
echo "APKs: $OUT/first.apk $OUT/second.apk"
exit $status
