#!/usr/bin/env bash
# Checks a built Robot APK: signer (Robot's release key), package and version, SDK levels, the assistant
# components and their processes, the recognizer that keeps the assistant across restarts, permissions,
# and the arm64-only native libraries. Usage: tools/verify_apk.sh <apk>
set -u -o pipefail
APK="$1"
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.0.0"
EXPECT_CERT=402557a46a491750688a3dff714100fe1d20b2f61193a912358c2edd341a6b6a
fails=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
has() { echo "$2" | grep -qF -- "$1" && pass "$3" || fail "$3"; }
hasnt() { echo "$2" | grep -qF -- "$1" && fail "$3" || pass "$3"; }
# A component's xmltree lines from its android:name on, children included, up to the next component.
subtree() { echo "$mf" | awk -v n="\"$1\"" '/^ *E: (service|receiver|activity|provider) /{ on=0 } index($0, n){ on=1 } on'; }
# The element's own attribute lines (before its first child element).
elem() { echo "$mf" | awk -v n="\"$1\"" '/^ *E: /{ if (index(b, n)) print b; b="" } { b = b "\n" $0 } END { if (index(b, n)) print b }'; }

cert=$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[ "$cert" = "$EXPECT_CERT" ] && pass "signer $cert (Robot release key)" || fail "signer '$cert'"
# minSdk 34: AGP signs with v3 (API 28+) and may skip v2; either covers every phone Robot installs on.
"$BT/apksigner" verify -v "$APK" 2>/dev/null | grep -qE "Verified using v[23] scheme \(APK Signature Scheme v[23]\): true" && pass "v2/v3 signature verifies" || fail "v2/v3 signature"
badging=$("$BT/aapt2" dump badging "$APK" 2>/dev/null)
has "package: name='app.heyrobot' versionCode='4' versionName='0.1.3'" "$badging" "package app.heyrobot 0.1.3 (4)"
has "minSdkVersion:'34'" "$badging" "minSdk 34"
has "targetSdkVersion:'36'" "$badging" "targetSdk 36"
has "application-label:'Robot'" "$badging" "label Robot"
has "native-code: 'arm64-v8a'" "$badging" "arm64-v8a only"
hasnt "application-debuggable" "$badging" "not debuggable"
mf=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)
for c in assistant.RobotVoiceService assistant.RobotSessionService assistant.RobotRecognitionService assistant.CaptureActivity brain.BrainService boot.BootReceiver boot.BootCheckReceiver ui.MainActivity; do
  has "\"app.heyrobot.$c\"" "$mf" "manifest has $c"
done
has '"android.service.voice.VoiceInteractionService"' "$mf" "VIS intent filter"
has '"android.voice_interaction"' "$mf" "voice_interaction meta-data"
rec=$(subtree app.heyrobot.assistant.RobotRecognitionService)
has '"android.speech.RecognitionService"' "$rec" "recognizer has the RecognitionService intent filter (keeps the assistant across restarts)"
has 'exported(0x01010010)=true' "$(elem app.heyrobot.assistant.RobotRecognitionService)" "recognizer exported (discoverable)"
has '"android.speech"' "$rec" "recognizer has the android.speech meta-data"
has ':voice' "$(elem app.heyrobot.assistant.RobotVoiceService)" "assistant in :voice"
has ':brain' "$(elem app.heyrobot.brain.BrainService)" "BrainService in :brain"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.brain.BrainService)" "BrainService not exported"
has '"app.heyrobot.permission.INTERNAL"' "$(elem app.heyrobot.brain.BrainService)" "BrainService guarded by the signature permission"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.boot.BootReceiver)" "BootReceiver not exported"
for p in CAMERA READ_EXTERNAL_STORAGE READ_MEDIA_IMAGES QUERY_ALL_PACKAGES SEND_SMS READ_CONTACTS ACCESS_FINE_LOCATION; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
vi=$(for f in $(unzip -Z1 "$APK" 'res/*.xml'); do "$BT/aapt2" dump xmltree --file "$f" "$APK" 2>/dev/null; done)
has '"app.heyrobot.assistant.RobotSessionService"' "$vi" "voice-interaction sessionService"
has '"app.heyrobot.assistant.RobotRecognitionService"' "$vi" "voice-interaction recognitionService"
hasnt 'selectableAsDefault' "$vi" "recognizer selectableAsDefault left at its default (true)"
has 'supportsAssist(0x010104f0)=true' "$vi" "supportsAssist"
has 'supportsLaunchVoiceAssistFromKeyguard(0x010104f1)=true' "$vi" "supportsLaunchVoiceAssistFromKeyguard"
libs=$(unzip -Z1 "$APK" 'lib/*')
for l in libsherpa-onnx-jni.so libonnxruntime.so liblitertlm_jni.so libLiteRtDispatch_GoogleTensor.so; do has "lib/arm64-v8a/$l" "$libs" "packages $l"; done
echo "$libs" | grep -v '^lib/arm64-v8a/' | grep -q . && fail "non-arm64 libraries" || pass "no other ABIs"
echo "$libs" | grep -q CompilerPlugin && fail "compiler plugin packaged" || pass "no compiler plugin"
# sherpa-onnx's JNI calls the TTS callback through GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")
# (v1.13.8 jni/offline-tts.cc). A Kotlin lambda has no such method and every voice is silent (0.1.2), so the
# callback class must really declare it, public and not as a bridge, in the dex code that ships.
dexdir=$(mktemp -d)
unzip -q -o "$APK" 'classes*.dex' -d "$dexdir" 2>/dev/null
cbclass=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } index($0, "Class descriptor  : '\''Lapp/heyrobot/voice/TtsCallback;'\''"){ on=1 } on')
rm -rf "$dexdir"
# One "name type access" line per field and method of the class.
cbmembers=$(echo "$cbclass" | awk '/^ *#[0-9]+ *: \(in /{ if (n != "") print n, t, a; n = t = a = "" }
  /^ *name *: /{ n=$3 } /^ *type *: /{ t=$3 } /^ *access *: /{ a=$3 } END { if (n != "") print n, t, a }')
has "'Lkotlin/jvm/functions/Function1;'" "$cbclass" "TtsCallback (sherpa TTS callback) implements kotlin Function1"
echo "$cbmembers" | grep -qxF "'invoke' '([F)Ljava/lang/Integer;' 0x0001" \
  && pass "TtsCallback declares public invoke([F)Ljava/lang/Integer; (what sherpa's JNI looks up)" \
  || fail "TtsCallback lacks public invoke([F)Ljava/lang/Integer;: downloaded voices would be silent"
assets=$(unzip -Z1 "$APK" 'assets/*')
for a in kws/encoder-epoch-12-avg-2-chunk-16-left-64.onnx kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx kws/joiner-epoch-12-avg-2-chunk-16-left-64.onnx kws/tokens.txt kws/keywords.txt kws/stop.txt vad/silero_vad.onnx bench/test-clip.s16 bench/test-clip.txt; do
  has "assets/$a" "$assets" "asset $a"
done
# The espeak-ng English + French subset (17 files) the voices need; nothing more of espeak-ng-data.
n=$(echo "$assets" | grep -c '^assets/espeak-ng-data/')
[ "$n" -eq 17 ] && pass "espeak-ng-data subset (17 files)" || fail "espeak-ng-data has $n files, want 17"
has "assets/espeak-ng-data/phontab" "$assets" "asset espeak-ng-data/phontab"
has "assets/espeak-ng-data/lang/gmw/en-US" "$assets" "asset espeak-ng-data/lang/gmw/en-US"
# The speech recognition and voice models are downloaded by the app, never packaged.
echo "$assets" | grep -qE '(encoder|decoder|joiner)\.int8\.onnx|/model(\.fp32|\.int8)?\.onnx|voices\.bin' && fail "a downloadable voice model is packaged" || pass "no downloadable voice model packaged"
clip=$(unzip -l "$APK" assets/bench/test-clip.s16 | awk 'NR==4{print $1}')
[ -n "$clip" ] && [ "$clip" -le 200000 ] && pass "benchmark clip ${clip} bytes (<= 200 KB)" || fail "benchmark clip size '$clip'"
unzip -Z1 "$APK" | grep -qiE '\.(p12|jks|keystore|pass|litertlm)$' && fail "key or model file inside the APK" || pass "no key or model file inside the APK"
echo; [ "$fails" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$fails CHECK(S) FAILED"
exit $((fails > 0))
