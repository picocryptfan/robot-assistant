#!/usr/bin/env bash
# Downloads the voice models the app downloads itself (Settings › Voice / Model › Voice models) to a directory on
# a computer and checks every byte count and SHA-256. Same pinned Hugging Face commits, files and hashes as
# core/.../VoiceModels.kt (ManifestTest keeps the two in step). For checking the manifest, for tools/voice_bench.py
# and tools/make_test_clip.py; the APK never contains these files. Resumes partial files (curl -C -).
# Needs curl and sha256sum. Usage: tools/fetch_voice_models.sh <dir> [bundle...]   (default: every bundle)
set -euo pipefail
DEST="${1:?usage: fetch_voice_models.sh <dir> [bundle...]}"
shift
WANT=" ${*:-stt-parakeet-v3 tts-kokoro-v1.0 tts-kitten-nano-0.8} "

# bundle repo commit file bytes sha256
MANIFEST=$(cat <<'LIST'
stt-parakeet-v3 csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 2bda32ec70b097a55adaa07d9a7173915b43cc78 encoder.int8.onnx 652184281 acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247
stt-parakeet-v3 csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 2bda32ec70b097a55adaa07d9a7173915b43cc78 decoder.int8.onnx 11845275 179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e
stt-parakeet-v3 csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 2bda32ec70b097a55adaa07d9a7173915b43cc78 joiner.int8.onnx 6355277 3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3
stt-parakeet-v3 csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 2bda32ec70b097a55adaa07d9a7173915b43cc78 tokens.txt 93939 d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d
tts-kokoro-v1.0 csukuangfj/kokoro-multi-lang-v1_0 f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b model.onnx 325560556 b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11
tts-kokoro-v1.0 csukuangfj/kokoro-multi-lang-v1_0 f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b voices.bin 28200960 1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29
tts-kokoro-v1.0 csukuangfj/kokoro-multi-lang-v1_0 f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b tokens.txt 687 6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425
tts-kitten-nano-0.8 csukuangfj2/kitten-nano-en-v0_8-fp32 eb4fe4f70801b2eb1c3f45be046ccd553c7575e7 model.fp32.onnx 56768002 2174dbf67b58b7b50d7b65294f89c2c53c172834533519b853c579879a04cc22
tts-kitten-nano-0.8 csukuangfj2/kitten-nano-en-v0_8-fp32 eb4fe4f70801b2eb1c3f45be046ccd553c7575e7 voices.bin 3276800 d520519c4a3519d44fcfcd943ed0b1e3c5da5cee0eea501d922fac1a93cd24dc
tts-kitten-nano-0.8 csukuangfj2/kitten-nano-en-v0_8-fp32 eb4fe4f70801b2eb1c3f45be046ccd553c7575e7 tokens.txt 1064 934a4188addc7665dd3410256bb622169242357fbb99d840d9351209b486dabb
LIST
)

fails=0
while read -r bundle repo commit file bytes sha; do
  case "$WANT" in *" $bundle "*) ;; *) continue ;; esac
  dir="$DEST/$bundle"
  mkdir -p "$dir"
  out="$dir/$file"
  if [ ! -f "$out" ] || [ "$(stat -c %s "$out")" != "$bytes" ]; then
    curl -sSfL -C - --retry 5 --retry-delay 3 -o "$out" "https://huggingface.co/$repo/resolve/$commit/$file"
  fi
  got_bytes=$(stat -c %s "$out")
  got_sha=$(sha256sum "$out" | cut -d' ' -f1)
  if [ "$got_bytes" = "$bytes" ] && [ "$got_sha" = "$sha" ]; then
    echo "ok  $bundle/$file $bytes"
  else
    echo "BAD $bundle/$file: $got_bytes bytes, sha256 $got_sha (want $bytes, $sha)"
    fails=$((fails + 1))
  fi
done <<<"$MANIFEST"
[ "$fails" -eq 0 ] && echo "all voice model files verified" || { echo "$fails file(s) failed"; exit 1; }
