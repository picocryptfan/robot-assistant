#!/usr/bin/env bash
# Re-downloads the third-party files listed in THIRD_PARTY.md into a fresh temp directory, checks every
# SHA-256, and (with --install) copies them into the :voice module. Needs curl, tar (bzip2) and sha256sum.
# Same files and hashes as the phase 0 probe; Robot packages only the fp32 keyword models it loads.
# Usage: tools/fetch_third_party.sh [--install]
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
VOICE="$HERE/voice/src/main"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download

check() { # file sha
  local got; got=$(sha256sum "$1" | cut -d' ' -f1)
  [ "$got" = "$2" ] || { echo "SHA-256 MISMATCH $1: $got != $2"; exit 1; }
  echo "ok  $(basename "$1")"
}

mkdir -p "$TMP/jni" "$TMP/kws" "$TMP/vad"
curl -sSfL -o "$TMP/jni/a.tar.bz2" "$REL/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
check "$TMP/jni/a.tar.bz2" 2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44
tar xjf "$TMP/jni/a.tar.bz2" -C "$TMP/jni" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so ./jniLibs/arm64-v8a/libonnxruntime.so
check "$TMP/jni/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" 3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
check "$TMP/jni/jniLibs/arm64-v8a/libonnxruntime.so" 33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1

M=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
curl -sSfL -o "$TMP/kws/m.tar.bz2" "$REL/kws-models/$M.tar.bz2"
check "$TMP/kws/m.tar.bz2" f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
tar xjf "$TMP/kws/m.tar.bz2" -C "$TMP/kws"
while read -r f sha; do check "$TMP/kws/$M/$f" "$sha"; done <<'LIST'
encoder-epoch-12-avg-2-chunk-16-left-64.onnx 063fbc1aeae8a9b574607a331a00e60371846ef9eaa3c1d9ea48176665dfc693
decoder-epoch-12-avg-2-chunk-16-left-64.onnx f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3
joiner-epoch-12-avg-2-chunk-16-left-64.onnx 0d7a37e749d8055223029318d6ffae82db1dae2d315d0892a68ba5dad17c1d2d
tokens.txt fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53
bpe.model c8a2a0129c4ab8e463164c142f82d25649661b122c8cd0b7aab5c9e80b90ad24
LIST

curl -sSfL -o "$TMP/vad/silero_vad.onnx" "$REL/asr-models/silero_vad.onnx"
check "$TMP/vad/silero_vad.onnx" 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6

if [ "${1:-}" = "--install" ]; then
  mkdir -p "$VOICE/jniLibs/arm64-v8a" "$VOICE/assets/kws" "$VOICE/assets/vad" "$HERE/tools/kws-build"
  cp "$TMP"/jni/jniLibs/arm64-v8a/*.so "$VOICE/jniLibs/arm64-v8a/"
  for p in encoder decoder joiner; do cp "$TMP/kws/$M/$p-epoch-12-avg-2-chunk-16-left-64.onnx" "$VOICE/assets/kws/"; done
  cp "$TMP/kws/$M/tokens.txt" "$VOICE/assets/kws/"
  cp "$TMP/kws/$M/bpe.model" "$TMP/kws/$M/tokens.txt" "$HERE/tools/kws-build/"
  cp "$TMP/vad/silero_vad.onnx" "$VOICE/assets/vad/"
  echo "installed (keywords.txt is in git; to regenerate it: python3 tools/make_keywords.py .)"
fi
echo "all third-party files verified"
