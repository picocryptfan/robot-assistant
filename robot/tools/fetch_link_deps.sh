#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Fills third_party/maven-link/ with Robot to Robot's carriers (job L3): Tor 0.4.9.14 (tor-android's jar and POM), the
# UnifiedPush connector 3.3.5 (AAR, POM, module file) and Tink 1.23.0's jar, the local Maven repository
# settings.gradle reads for those three modules only,
# so the build stays offline. Every file is checked against third_party/link-carriers.sha256 before it lands there, and
# the jars Maven Central publishes a .sha256 for (tor-android, Tink) against that too. Nothing is
# installed unless all of them match.
#
#   tools/fetch_link_deps.sh               downloads them once from Maven Central (online; Gradle never does)
#   tools/fetch_link_deps.sh --from DIR    copies them from DIR (searched by file name), e.g. R0's ~/Robot/labs/r0
#
# Needs curl (to download), sha256sum, unzip and find.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PINS="$HERE/third_party/link-carriers.sha256"
REPO="$HERE/third_party/maven-link"
URL=https://repo1.maven.org/maven2
FROM=""
case "${1:-}" in
  --from) FROM="${2:?--from needs a folder}" ;;
  "") ;;
  *) echo "usage: tools/fetch_link_deps.sh [--from DIR]" >&2; exit 2 ;;
esac
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The pinned files: "<sha256>  <group path>/<artifact>/<version>/<file>".
mapfile -t LINES < <(grep -E '^[0-9a-f]{64}  (org|com)/' "$PINS")
[ "${#LINES[@]}" = 8 ] || { echo "expected 8 pinned files in $PINS, found ${#LINES[@]}" >&2; exit 1; }

fetch() { # relative-path -> $TMP/relative-path
  local rel="$1" name; name="$(basename "$rel")"
  mkdir -p "$TMP/$(dirname "$rel")"
  if [ -n "$FROM" ]; then
    local src; src="$(find "$FROM" -type f -name "$name" -print -quit)"
    [ -n "$src" ] || { echo "not found under $FROM: $name" >&2; exit 1; }
    cp "$src" "$TMP/$rel"
  else
    curl -sSfL -o "$TMP/$rel" "$URL/$rel"
  fi
}

for line in "${LINES[@]}"; do
  sha="${line%%  *}"; rel="${line#*  }"
  fetch "$rel"
  got="$(sha256sum "$TMP/$rel" | cut -d' ' -f1)"
  [ "$got" = "$sha" ] || { echo "SHA-256 MISMATCH $rel: $got != $sha" >&2; exit 1; }
  # Maven Central publishes a .sha256 for these three jars: it must say the same.
  case "$rel" in
    */tor-android-0.4.9.14.jar|*/tink-1.23.0.jar)
      fetch "$rel.sha256"
      published="$(tr -d ' \n' < "$TMP/$rel.sha256" | cut -c1-64)"
      [ "$published" = "$sha" ] || { echo "Central's $(basename "$rel").sha256 says $published, the pin $sha" >&2; exit 1; }
      ;;
  esac
  echo "ok  $(basename "$rel")"
done

# libtor.so inside the jar: the one native file the APK takes from it.
torjar="$(grep -E 'tor-android-0\.4\.9\.14\.jar$' <<<"$(printf '%s\n' "${LINES[@]}")" | sed 's/^[0-9a-f]*  //')"
torpin="$(sed -nE 's|^([0-9a-f]{64})  lib/arm64-v8a/libtor\.so$|\1|p' "$PINS")"
gotso="$(unzip -p "$TMP/$torjar" arm64-v8a/libtor.so | sha256sum | cut -d' ' -f1)"
[ -n "$torpin" ] && [ "$gotso" = "$torpin" ] || { echo "arm64-v8a/libtor.so in the jar is $gotso, pinned '$torpin'" >&2; exit 1; }
echo "ok  arm64-v8a/libtor.so"

# Briar's lyrebird jar, from before tools/build_lyrebird.sh: not used any more.
rm -rf "$REPO/org/briarproject/lyrebird-android"

for line in "${LINES[@]}"; do
  rel="${line#*  }"
  mkdir -p "$REPO/$(dirname "$rel")"
  cp "$TMP/$rel" "$REPO/$rel"
done
(cd "$REPO" && grep -E '^[0-9a-f]{64}  (org|com)/' "$PINS" | sha256sum -c --quiet)
echo "Tor 0.4.9.14, the UnifiedPush connector 3.3.5 and Tink 1.23.0 installed in third_party/maven-link and checked"
