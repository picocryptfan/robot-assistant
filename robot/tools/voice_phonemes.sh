#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# What Robot's Kokoro voice really says for each line, on a computer: the phonemes espeak-ng gives sherpa-onnx's Kokoro
# front end, read from sherpa's debug output and turned back into symbols. A round trip through speech recognition
# hides a misreading ("12,30" heard back as "12:30" while the voice said « douze virgule trente »); the phonemes don't.
#
# It runs sherpa-onnx 1.13.8's own x64 JNI build (the release Robot pins, checked here by SHA-256), voice's vendored
# Kotlin API (voice/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt), Robot's espeak-ng data
# (voice/src/main/assets/espeak-ng-data) and the Kokoro v1.0 files tools/fetch_voice_models.sh fetches (checked here by
# SHA-256), configured as voice's SherpaTextToSpeech: Heart for English, Siwis with "fr" for French. The Kotlin compiler
# is the one in Gradle's cache (the project's 2.4.21). Nothing is downloaded.
#
# Usage: tools/voice_phonemes.sh SHERPA_X64_JNI_TARBALL KOKORO_DIR TEXTS.tsv [WORK_DIR]
#   SHERPA_X64_JNI_TARBALL  sherpa-onnx-v1.13.8-linux-x64-jni.tar.bz2 from the v1.13.8 GitHub release
#   KOKORO_DIR              model.onnx, voices.bin and tokens.txt of kokoro-multi-lang-v1_0
#   TEXTS.tsv               one line per text: id, "en" or "fr", text (tab-separated; "#" starts a comment)
#   WORK_DIR                where it builds and logs (default: a new folder under ${TMPDIR:-/tmp}, removed at the end)
# Prints: id, language, text, what the front end passed on, phonemes (tab-separated). Example: tools/voice_phonemes/times.tsv.
set -euo pipefail

JNI_SHA=f18bf581b408d8c28539c1b28899b4d4811faebb74eede08db21fd6e9243e346
KOKORO_SHA="model.onnx b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11
voices.bin 1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29
tokens.txt 6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"
KOTLIN=2.4.21

[ $# -ge 3 ] || { sed -n '13,18p' "$0"; exit 2; }
tarball=$(realpath "$1"); kokoro=$(realpath "$2"); texts=$(realpath "$3")
here=$(cd "$(dirname "$0")" && pwd)
robot=$(dirname "$here")
espeak="$robot/voice/src/main/assets/espeak-ng-data"
[ -f "$espeak/phontab" ] || { echo "No espeak-ng data in $espeak (copy voice's assets in first)." >&2; exit 1; }

[ "$(sha256sum "$tarball" | cut -d' ' -f1)" = "$JNI_SHA" ] || { echo "$tarball isn't sherpa-onnx 1.13.8's x64 JNI build." >&2; exit 1; }
while read -r f sha; do
  [ "$(sha256sum "$kokoro/$f" | cut -d' ' -f1)" = "$sha" ] || { echo "$kokoro/$f isn't Kokoro v1.0's." >&2; exit 1; }
done <<< "$KOKORO_SHA"

if [ $# -ge 4 ]; then work=$(realpath "$4"); mkdir -p "$work"; keep=1; else work=$(mktemp -d "${TMPDIR:-/tmp}/voice-phonemes.XXXXXX"); keep=0; fi
[ "$keep" = 1 ] || trap 'rm -rf "$work"' EXIT

jar() { # the newest cached jar of a Maven artifact at a version
  find "$HOME/.gradle/caches/modules-2/files-2.1/$1/$2/$3" -name "$2-$3.jar" 2>/dev/null | head -1
}
cp_compiler="$(jar org.jetbrains.kotlin kotlin-compiler-embeddable $KOTLIN):$(jar org.jetbrains.kotlin kotlin-stdlib $KOTLIN):$(jar org.jetbrains.kotlin kotlin-script-runtime $KOTLIN):$(jar org.jetbrains.kotlin kotlin-daemon-embeddable $KOTLIN)"
reflect=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-reflect" -name 'kotlin-reflect-*.jar' | sort | tail -1)
coroutines=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name 'kotlinx-coroutines-core-jvm-*.jar' | sort | tail -1)
annotations=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations" -name 'annotations-*.jar' | sort | tail -1)
stdlib=$(jar org.jetbrains.kotlin kotlin-stdlib $KOTLIN)
for j in ${cp_compiler//:/ } "$reflect" "$coroutines" "$annotations"; do
  [ -f "$j" ] || { echo "Kotlin $KOTLIN's compiler isn't in Gradle's cache (build the project once)." >&2; exit 1; }
done

tar -xjf "$tarball" -C "$work"
lib="$work/sherpa-onnx-v1.13.8-linux-x64-jni/lib"
[ -f "$lib/libsherpa-onnx-jni.so" ] || { echo "No libsherpa-onnx-jni.so in the tarball." >&2; exit 1; }

java -Xmx1g -cp "$cp_compiler:$reflect:$coroutines:$annotations" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -no-stdlib -cp "$stdlib" -jvm-target 17 -nowarn -d "$work/classes" \
  "$here/voice_phonemes/Phonemes.kt" "$here/voice_phonemes/AssetManager.kt" "$robot/voice/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt"

java -Xmx1g -Djava.library.path="$lib" -cp "$work/classes:$stdlib" PhonemesKt "$kokoro" "$espeak" "$texts" 2> "$work/sherpa.log" > /dev/null
python3 -I "$here/voice_phonemes/phonemes.py" "$kokoro/tokens.txt" "$work/sherpa.log"
