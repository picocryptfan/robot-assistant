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
# Usage: tools/repro_check.sh [--keep] [--signed Robot-x.y.z.apk] [--gradle /path/to/gradle]
#   --keep    keep the second copy's folder (printed at the end)
#   --signed  a published, signed APK of this commit: also run `apksigcopier compare` against the rebuild
#   --gradle  the Gradle to use (default: gradle on the PATH, else /opt/gradle/bin/gradle)
# Needs git, unzip, sha256sum and cmp. Takes two release builds' time; run it by hand (no test runs it). Uncommitted
# changes are left out of the second build, so commit first: the script stops when the tree isn't clean.
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
KEEP=0
SIGNED=""
GRADLE=$(command -v gradle || echo /opt/gradle/bin/gradle)
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --signed) SIGNED="$2"; shift 2 ;;
    --gradle) GRADLE="$2"; shift 2 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
for t in git unzip sha256sum cmp; do command -v "$t" >/dev/null || { echo "needs $t" >&2; exit 2; }; done
[ -x "$GRADLE" ] || { echo "no Gradle at $GRADLE (--gradle)" >&2; exit 2; }
TOP=$(git -C "$HERE" rev-parse --show-toplevel)
SUB=${HERE#"$TOP"/}
[ -z "$(git -C "$TOP" status --porcelain --untracked-files=no)" ] || { echo "commit your changes first: the second build uses the committed tree" >&2; exit 2; }

APK_REL=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=$(mktemp -d)
COPY="$OUT/tree"
cleanup() { [ "$KEEP" = 1 ] || rm -rf "$COPY"; }
trap cleanup EXIT

build() { # dir out.apk
  (cd "$1" && "$GRADLE" --offline -q clean :app:assembleRelease)
  local apk="$1/$APK_REL"
  [ -f "$apk" ] || apk="$1/app/build/outputs/apk/release/app-release.apk"
  [ -f "$apk" ] || { echo "no release APK in $1/app/build/outputs/apk/release" >&2; exit 1; }
  cp "$apk" "$2"
}

echo "== build 1: $HERE"
build "$HERE" "$OUT/first.apk"

echo "== build 2: a clean copy of $(git -C "$TOP" rev-parse --short HEAD) in $COPY"
mkdir -p "$COPY"
git -C "$TOP" archive --format=tar HEAD "$SUB" | tar -xf - -C "$COPY"
# LICENSE is packaged too (assets/licenses/GPL-3.0.txt is checked against it).
git -C "$TOP" archive --format=tar HEAD LICENSE | tar -xf - -C "$COPY"
DEST="$COPY/$SUB"
for p in local.properties brain/src/main/jniLibs mt/src/main/jniLibs voice/src/main/jniLibs \
         voice/src/main/assets/espeak-ng-data voice/src/main/assets/vad voice/src/main/assets/kws; do
  [ -e "$HERE/$p" ] || continue
  mkdir -p "$(dirname "$DEST/$p")"
  if [ -d "$HERE/$p" ] && [ ! -L "$HERE/$p" ]; then cp -al "$HERE/$p/." "$DEST/$p/" 2>/dev/null || cp -a "$HERE/$p/." "$DEST/$p/"
  else cp -al "$HERE/$p" "$DEST/$p" 2>/dev/null || cp -a "$HERE/$p" "$DEST/$p"; fi
done
build "$DEST" "$OUT/second.apk"

a=$(sha256sum "$OUT/first.apk" | cut -d' ' -f1)
b=$(sha256sum "$OUT/second.apk" | cut -d' ' -f1)
echo "first:  $a"
echo "second: $b"
status=0
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
