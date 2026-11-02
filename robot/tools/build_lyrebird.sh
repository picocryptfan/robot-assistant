#!/usr/bin/env bash
# Builds liblyrebird.so, Tor's bridges for Robot to Robot, from source: lyrebird 0.9.0, the Tor Project's pluggable
# transport program, for arm64 Android, with go.mod and go.sum moved by third_party/patches/lyrebird-0.9.0-deps.patch to
# the first versions of x/net, pion/stun, pion/dtls that govulncheck finds nothing reachable in (THIRD_PARTY.md). The
# output is byte for byte the same on any machine: third_party/link-carriers.sha256 pins it, and this script stops if it
# isn't that. It lands in third_party/lyrebird/ (a .so, so git-ignored), where :link's lyrebirdBinary task reads it.
#
# Everything is pinned: Go's tarball by go.dev's SHA-256, lyrebird by its commit, each Go module by the patched go.sum
# (and Go's checksum database when it's first downloaded), the compiler by the NDK's version. Nothing is installed outside
# the folders below.
#
# Usage: tools/build_lyrebird.sh [--vendor-to FILE.tar.gz]
#   --vendor-to  also writes the patched tree with vendor/, as the source bundle carries it, and doesn't need the NDK
# Environment: ROBOT_GO, a folder holding this Go, or where to unpack it (default ~/.cache/robot/go1.27.3);
#   ANDROID_NDK_HOME (default $ANDROID_HOME/ndk/27.1.12297006); ROBOT_LYREBIRD_NOPIN=1 prints the hash without stopping,
#   only for moving to a new version.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROBOT=$(dirname "$HERE")
GO_VERSION=1.27.3
GO_SHA256=65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40 # go.dev's for go1.27.3.linux-amd64.tar.gz
LYREBIRD_VERSION=0.9.0
LYREBIRD_COMMIT=89d54872b176f936232ff39f7a8189b77d83d65e
LYREBIRD_GIT=https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git
NDK_VERSION=27.1.12297006
MIN_API=$(sed -nE 's/.*robotMinSdk *= *([0-9]+).*/\1/p' "$ROBOT/build.gradle" | head -1)
PATCH="$ROBOT/third_party/patches/lyrebird-$LYREBIRD_VERSION-deps.patch"
PINS="$ROBOT/third_party/link-carriers.sha256"
OUT="$ROBOT/third_party/lyrebird/liblyrebird.so"
VENDOR_TO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --vendor-to) VENDOR_TO="$2"; shift 2 ;;
    *) echo "usage: $0 [--vendor-to FILE.tar.gz]" >&2; exit 2 ;;
  esac
done

GODIR="${ROBOT_GO:-$HOME/.cache/robot/go$GO_VERSION}"
if [ ! -x "$GODIR/bin/go" ]; then
  mkdir -p "$GODIR"
  tgz=$(mktemp); trap 'rm -f "$tgz"' EXIT
  curl -fsSL --retry 2 --connect-timeout 20 -o "$tgz" "https://go.dev/dl/go$GO_VERSION.linux-amd64.tar.gz"
  [ "$(sha256sum "$tgz" | cut -d' ' -f1)" = "$GO_SHA256" ] || { echo "SHA-256 MISMATCH on Go's tarball" >&2; exit 1; }
  tar -xzf "$tgz" -C "$GODIR" --strip-components=1
  rm -f "$tgz" # /tmp is memory here, and the next trap replaces this one's
fi
[ "$("$GODIR/bin/go" env GOVERSION)" = "go$GO_VERSION" ] || { echo "$GODIR isn't go$GO_VERSION" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/lyrebird.XXXXXX"); trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT
git -C "$WORK" init -q src
git -C "$WORK/src" fetch -q --depth 1 "$LYREBIRD_GIT" "refs/tags/$LYREBIRD_VERSION"
git -C "$WORK/src" checkout -q FETCH_HEAD
[ "$(git -C "$WORK/src" rev-parse HEAD)" = "$LYREBIRD_COMMIT" ] || { echo "tag $LYREBIRD_VERSION isn't $LYREBIRD_COMMIT" >&2; exit 1; }
git -C "$WORK/src" apply "$PATCH"

export GOROOT="$GODIR" GOTOOLCHAIN=local GOFLAGS=-mod=readonly GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org
export GOPATH="$WORK/gopath" GOCACHE="$WORK/cache"
GO="$GODIR/bin/go"
cd "$WORK/src"
"$GO" mod download
"$GO" mod verify > /dev/null

if [ -n "$VENDOR_TO" ]; then
  cp go.mod "$WORK/go.mod.patched"
  GOFLAGS=-mod=mod "$GO" mod vendor
  cmp -s go.mod "$WORK/go.mod.patched" || { echo "vendoring changed go.mod" >&2; exit 1; }
  rm -rf .git
  tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-09-23 00:00:00Z' -czf "$VENDOR_TO" -C "$WORK" --transform "s|^src|lyrebird-$LYREBIRD_VERSION|" src
  echo "$VENDOR_TO"
  exit 0
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/$NDK_VERSION}"
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || { echo "$NDK isn't NDK $NDK_VERSION" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -n "$MIN_API" ] || { echo "no robotMinSdk in build.gradle" >&2; exit 1; }
export GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$BIN/aarch64-linux-android$MIN_API-clang"
export CGO_CFLAGS="-O2 -ffile-prefix-map=$NDK=ndk" CGO_LDFLAGS=""
# -checklinkname=0: pion's wlynxg/anet reaches net.zoneCache by go:linkname, for Android's blocked netlink route read;
# Go has refused that by default since 1.23, and pion's ICE needs it to list the network interfaces for Snowflake.
"$GO" build -p 4 -trimpath -buildvcs=false -buildmode=pie -o "$WORK/liblyrebird.so" \
  -ldflags "-w -s -buildid= -checklinkname=0 -linkmode=external -extldflags '-pie -Wl,-z,max-page-size=16384 -Wl,--build-id=none' -X main.lyrebirdVersion=$LYREBIRD_VERSION" \
  ./cmd/lyrebird

# 16 KB pages: every LOAD segment aligned to at least that.
"$BIN/llvm-readelf" -lW "$WORK/liblyrebird.so" | awk '$1 == "LOAD" { n++; if (strtonum($NF) < 16384) bad = 1 } END { exit (n == 0 || bad) }' \
  || { echo "liblyrebird.so isn't 16 KB-aligned" >&2; exit 1; }

got=$(sha256sum "$WORK/liblyrebird.so" | cut -d' ' -f1)
pin=$(sed -nE 's|^([0-9a-f]{64})  lib/arm64-v8a/liblyrebird\.so$|\1|p' "$PINS")
if [ "$got" != "$pin" ]; then
  if [ "${ROBOT_LYREBIRD_NOPIN:-}" != 1 ]; then echo "liblyrebird.so is $got, pinned '$pin'" >&2; exit 1; fi
  echo "unpinned: $got"
fi
mkdir -p "$(dirname "$OUT")"
cp "$WORK/liblyrebird.so" "$OUT"
echo "ok  $OUT $got $(stat -c %s "$OUT") bytes"
