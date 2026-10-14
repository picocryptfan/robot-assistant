#!/usr/bin/env bash
# Sets Robot's version in its one place, app/build.gradle (versionName and versionCode), and makes sure fastlane has a
# changelog for the new versionCode (IzzyOnDroid and F-Droid show it; FastlaneMetadataTest needs it). tools/verify_apk.sh
# and ProcessIsolationTest read the version from app/build.gradle, so nothing else changes by hand.
#
# The versionCode, when not given: major*1000000 + minor*10000 + patch*100, plus the release candidate's number (rc1 = 1)
# or 99 for the release itself (app/build.gradle says the same). 1.0.1 = 1000199, 1.1.0-rc2 = 1010002.
# A changelog that's missing gets a stub line to replace before the release; one that exists is left alone.
#
# Usage: tools/bump_version.sh x.y.z[-rcN] [versionCode]
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$HERE/app/build.gradle"
CHANGELOGS="$HERE/../fastlane/metadata/android/en-US/changelogs"

usage() { echo "usage: tools/bump_version.sh x.y.z[-rcN] [versionCode]" >&2; exit 2; }
[ $# -ge 1 ] && [ $# -le 2 ] || usage
name="$1"
[[ "$name" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-rc([1-9][0-9]?))?$ ]] || { echo "not a version: '$name' (x.y.z or x.y.z-rcN)" >&2; exit 2; }
major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]})); rc="${BASH_REMATCH[5]}"
[ "$minor" -le 99 ] && [ "$patch" -le 99 ] || { echo "minor and patch go up to 99" >&2; exit 2; }
[ -z "$rc" ] || { [ $((10#$rc)) -ge 1 ] && [ $((10#$rc)) -le 98 ]; } || { echo "a release candidate's number goes from 1 to 98" >&2; exit 2; }
last=99; [ -z "$rc" ] || last=$((10#$rc))
code="${2:-$((major * 1000000 + minor * 10000 + patch * 100 + last))}"
[[ "$code" =~ ^[1-9][0-9]*$ ]] && [ "$code" -le 2100000000 ] || { echo "not a versionCode: '$code'" >&2; exit 2; }

[ "$(grep -cE "^ +versionCode [0-9]+$" "$GRADLE")" = 1 ] && [ "$(grep -cE "^ +versionName '[^']+'$" "$GRADLE")" = 1 ] \
  || { echo "app/build.gradle doesn't have exactly one versionCode and one versionName line" >&2; exit 1; }
old_code=$(sed -nE "s/^ +versionCode ([0-9]+)$/\1/p" "$GRADLE")
old_name=$(sed -nE "s/^ +versionName '([^']+)'$/\1/p" "$GRADLE")
# Android won't install a lower versionCode over a higher one.
[ "$code" -ge "$old_code" ] || { echo "versionCode $code is lower than the current $old_code: phones with $old_name wouldn't update" >&2; exit 1; }

sed -i -E "s/^( +versionCode )[0-9]+$/\1$code/; s/^( +versionName ')[^']+'$/\1$name'/" "$GRADLE"
echo "app/build.gradle: $old_name ($old_code) -> $name ($code)"

log="$CHANGELOGS/$code.txt"
if [ -f "$log" ]; then
  echo "changelog: ${log#"$HERE/../"} is already there, left as it is"
else
  mkdir -p "$CHANGELOGS"
  echo "Robot $name: replace this line with what changed (500 characters at most)." > "$log"
  echo "changelog: wrote a stub at ${log#"$HERE/../"}: replace it before the release"
fi
