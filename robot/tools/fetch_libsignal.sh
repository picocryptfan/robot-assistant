#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Fills third_party/maven-local/ with libsignal 0.105.2 (the AAR, the client jar, their POMs and module files), the local
# Maven repository settings.gradle reads for org.signal only, so the build stays offline. Every file is checked against
# third_party/libsignal-0.105.2.sha256 before it lands there, and the AAR and the jar against Signal's own .sha256 files
# too. Nothing is installed unless all of them match.
#
#   tools/fetch_libsignal.sh               downloads them once from Signal's repository (online; Gradle never does)
#   tools/fetch_libsignal.sh --from DIR    copies them from DIR (searched by file name), e.g. a copy already checked
#
# Needs curl (to download), sha256sum and find.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PINS="$HERE/third_party/libsignal-0.105.2.sha256"
REPO="$HERE/third_party/maven-local"
URL=https://build-artifacts.signal.org/libraries/maven
FROM=""
case "${1:-}" in
  --from) FROM="${2:?--from needs a folder}" ;;
  "") ;;
  *) echo "usage: tools/fetch_libsignal.sh [--from DIR]" >&2; exit 2 ;;
esac
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The pinned files: "<sha256>  org/signal/<artifact>/<version>/<file>".
mapfile -t LINES < <(grep -E '^[0-9a-f]{64}  org/' "$PINS")
[ "${#LINES[@]}" = 6 ] || { echo "expected 6 pinned files in $PINS, found ${#LINES[@]}" >&2; exit 1; }

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
  # The AAR and the jar: Signal's published .sha256 must say the same.
  case "$rel" in
    *.aar|*.jar)
      fetch "$rel.sha256"
      published="$(tr -d ' \n' < "$TMP/$rel.sha256")"
      [ "$published" = "$sha" ] || { echo "Signal's $(basename "$rel").sha256 says $published, the pin $sha" >&2; exit 1; }
      ;;
  esac
  echo "ok  $(basename "$rel")"
done

for line in "${LINES[@]}"; do
  rel="${line#*  }"
  mkdir -p "$REPO/$(dirname "$rel")"
  cp "$TMP/$rel" "$REPO/$rel"
  [ -f "$TMP/$rel.sha256" ] && cp "$TMP/$rel.sha256" "$REPO/$rel.sha256"
done
(cd "$REPO" && grep -E '^[0-9a-f]{64}  org/' "$PINS" | sha256sum -c --quiet)
echo "libsignal 0.105.2 installed in third_party/maven-local and checked"
