#!/usr/bin/env bash
# Re-downloads the third-party files listed in THIRD_PARTY.md into a fresh temp directory, checks every
# SHA-256, and (with --install) copies them into the :voice module. Needs curl, tar (bzip2) and sha256sum.
# Same files and hashes as the phase 0 probe; Robot packages only the fp32 keyword models it loads and (since 0.2.3)
# the keyword model's bpe.model, which the app reads to spell the user's wake phrases, plus the 17-file English +
# French espeak-ng-data subset the Kokoro and Kitten voices need (docs/voice-plan.md 2.5).
# The downloadable voice models themselves are not packaged: see tools/fetch_voice_models.sh.
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

mkdir -p "$TMP/jni" "$TMP/kws" "$TMP/vad" "$TMP/espeak"
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

# espeak-ng-data (GPL-3.0-or-later): the archive sherpa-onnx publishes, identical to the copies inside the Kokoro and
# Kitten archives. Only the files espeak reads for English and French go into the APK.
curl -sSfL -o "$TMP/espeak/e.tar.bz2" "$REL/tts-models/espeak-ng-data.tar.bz2"
check "$TMP/espeak/e.tar.bz2" 4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533
tar xjf "$TMP/espeak/e.tar.bz2" -C "$TMP/espeak"
ESPEAK_FILES=$(cat <<'LIST'
phontab 886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6
phonindex 3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4
phondata 4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3
intonations 3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc
en_dict 71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35
fr_dict e399ab924c4d10beef1fc310b30ea56e4ddfd8b4b64b8ed978e9c65394d49b2d
lang/gmw/en 4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83
lang/gmw/en-US 41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704
lang/gmw/en-US-nyc e8ee9168376d1f7d9e7dcda7c4644c36c529a8bea816e6380895364db46ea2e3
lang/gmw/en-029 faeb8cb201056775f733acbd908c66555fe57bf01cb063920f178f389cbf85d3
lang/gmw/en-GB-scotland 1ce4282c1f4385dbaf0035e799b2ab1ee9e9a3ab4829100594a3047727b52353
lang/gmw/en-GB-x-gbclan 2040e176f1f7f27bdce81c32d7e2b1662449d54d264afd3c0ed871b8e42b6e46
lang/gmw/en-GB-x-gbcwmd 927a5ab891c65b30b4426b151d1623a01024f6b773a38497ba1c90cd77a95747
lang/gmw/en-GB-x-rp d0625af7f58561b1b8cf96fd7f93eee6553bcb3eadb9020ae0757bf96e5115e5
lang/roa/fr 95f44834b48c075dad13eace54d2c98ff79b81aa0074dd67eebaf66c2909eef8
lang/roa/fr-BE 72496a316e47b997f893500b8953dc01a10b86a2d4238e13c1260f50b47d792a
lang/roa/fr-CH b449b0fd99d9cd93261fe1c6b816448b99b4a10420bee3f301e669568ad0de55
LIST
)
while read -r f sha; do check "$TMP/espeak/espeak-ng-data/$f" "$sha"; done <<<"$ESPEAK_FILES"

if [ "${1:-}" = "--install" ]; then
  mkdir -p "$VOICE/jniLibs/arm64-v8a" "$VOICE/assets/kws" "$VOICE/assets/vad" "$HERE/tools/kws-build"
  cp "$TMP"/jni/jniLibs/arm64-v8a/*.so "$VOICE/jniLibs/arm64-v8a/"
  for p in encoder decoder joiner; do cp "$TMP/kws/$M/$p-epoch-12-avg-2-chunk-16-left-64.onnx" "$VOICE/assets/kws/"; done
  cp "$TMP/kws/$M/tokens.txt" "$TMP/kws/$M/bpe.model" "$VOICE/assets/kws/"
  cp "$TMP/kws/$M/bpe.model" "$TMP/kws/$M/tokens.txt" "$HERE/tools/kws-build/"
  cp "$TMP/vad/silero_vad.onnx" "$VOICE/assets/vad/"
  rm -rf "$VOICE/assets/espeak-ng-data"
  while read -r f sha; do
    mkdir -p "$(dirname "$VOICE/assets/espeak-ng-data/$f")"
    cp "$TMP/espeak/espeak-ng-data/$f" "$VOICE/assets/espeak-ng-data/$f"
  done <<<"$ESPEAK_FILES"
  echo "installed (keywords.txt and stop.txt are in git; to regenerate them: python3 tools/make_keywords.py .)"
fi
echo "all third-party files verified"
