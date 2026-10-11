#!/usr/bin/env bash
# Checks a built Robot APK: signer (Robot's release key), package and version, SDK levels, the assistant
# components and their processes, the recognizer that keeps the assistant across restarts, permissions,
# the arm64-only native libraries, and (phase 2) the Robot Notes launcher entry, the hand-off apps' <queries>
# and the three runtime permissions; that the main-process entries stay in the main process, that nothing beyond an
# allowlist is exported, and that no main-process class in the dex refers to an engine. 0.4.4: the native libraries are
# exactly an allowlist of names, and Robot's llama.cpp library (when this build has it) exports only its JNI bridge, is
# reached only from :brain's classes and can't go online or start a process. Usage: tools/verify_apk.sh <apk>
set -u -o pipefail
APK="$1"
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.0.0"
NDK_BIN="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/opt/android-sdk}/ndk/27.1.12297006}/toolchains/llvm/prebuilt/linux-x86_64/bin"
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
has "package: name='app.heyrobot' versionCode='42' versionName='0.4.2'" "$badging" "package app.heyrobot 0.4.2 (42)"
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
# Ask Claude needs nothing new: INTERNET stays the only network permission, and nothing reads other apps' screens.
for p in ACCESS_NETWORK_STATE ACCESS_WIFI_STATE CHANGE_NETWORK_STATE CHANGE_WIFI_STATE BIND_ACCESSIBILITY_SERVICE; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# 0.4.0: the weather (Open-Meteo) and the nearest place (OpenStreetMap's Nominatim) need nothing more: both go online
# from the :voice process's tools only, which ProcessIsolationTest checks in the source.
has '"android.permission.INTERNET"' "$mf" "declares INTERNET (model downloads, Ask Claude, the weather, the nearest place)"
hasnt '"android.accessibilityservice.AccessibilityService"' "$mf" "no accessibility service (Robot never drives the Claude app)"
has '"android.permission.MODIFY_AUDIO_SETTINGS"' "$mf" "declares MODIFY_AUDIO_SETTINGS (call audio playback)"
has '"android.permission.WRITE_SECURE_SETTINGS"' "$mf" "declares WRITE_SECURE_SETTINGS (adb grant only: keeps the recognizer set)"
boot=$(subtree app.heyrobot.boot.BootReceiver)
has '"android.intent.action.LOCKED_BOOT_COMPLETED"' "$boot" "BootReceiver gets LOCKED_BOOT_COMPLETED"
has 'directBootAware(0x01010505)=true' "$(elem app.heyrobot.boot.BootReceiver)" "BootReceiver direct-boot aware"
for p in CAMERA READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE MANAGE_EXTERNAL_STORAGE KILL_BACKGROUND_PROCESSES QUERY_ALL_PACKAGES SEND_SMS READ_SMS WRITE_CONTACTS READ_CALL_LOG ACCESS_FINE_LOCATION; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# 0.4.0: photos and videos (all, or the ones you pick), asked for by the main app only, for free_storage's largest files.
for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED; do
  has "\"android.permission.$p\"" "$mf" "declares $p"
done
has ':voice' "$(elem app.heyrobot.assistant.MediaDeleteActivity)" "MediaDeleteActivity in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.MediaDeleteActivity)" "MediaDeleteActivity not exported"
# Phase 2: contacts and the calendar, asked for at runtime by the main app only; 0.4.0: approximate location for the
# weather "here" (never fine or background location).
for p in READ_CONTACTS READ_CALENDAR WRITE_CALENDAR ACCESS_COARSE_LOCATION; do
  has "\"android.permission.$p\"" "$mf" "declares $p"
done
hasnt 'android.permission.ACCESS_BACKGROUND_LOCATION"' "$mf" "no ACCESS_BACKGROUND_LOCATION permission"
# 0.4.0: Usage access (a special app access you turn on in Android's settings) for the apps using the most space.
has '"android.permission.PACKAGE_USAGE_STATS"' "$mf" "declares PACKAGE_USAGE_STATS (Usage access, phone status)"
# "Robot Notes" on the launcher: an activity-alias of MainActivity with its own MAIN/LAUNCHER filter, label and icon.
alias=$(echo "$mf" | awk '/^ *E: activity-alias /{ on=0 } index($0, "\"app.heyrobot.ui.NotesLauncher\""){ on=1 } /^ *E: (service|receiver|activity|provider) /{ on=0 } on')
has 'targetActivity(0x01010202)="app.heyrobot.ui.MainActivity"' "$alias" "Robot Notes alias targets MainActivity"
has 'exported(0x01010010)=true' "$alias" "Robot Notes alias exported (launcher)"
has '"android.intent.action.MAIN"' "$alias" "Robot Notes alias has MAIN"
has '"android.intent.category.LAUNCHER"' "$alias" "Robot Notes alias has LAUNCHER"
# (Here-strings: with pipefail, "echo | grep -q" on this large dump can fail on SIGPIPE.)
res=$("$BT/aapt2" dump resources "$APK" 2>/dev/null)
grep -A1 'string/notes_name$' <<<"$res" | grep -qF '"Robot Notes"' && pass "Robot Notes label" || fail "Robot Notes label"
grep -qE 'resource 0x[0-9a-f]+ mipmap/ic_notes$' <<<"$res" && pass "Robot Notes icon" || fail "Robot Notes icon"
# Package visibility for the hand-offs, by name and by intent (no QUERY_ALL_PACKAGES).
queries=$(echo "$mf" | awk '/^ *E: queries /{ on=1 } /^ *E: application /{ on=0 } on')
# One line per <intent> inside <queries>, with everything it declares, so an action is checked with its own data.
intents=$(echo "$queries" | awk '/^ *E: intent /{ if (b != "") print b; b = "intent" ; next } /^ *E: (package|provider) /{ if (b != "") print b; b = ""; next } b != "" { gsub(/^ +/, ""); b = b " " $0 } END { if (b != "") print b }')
# An <intent> that declares all of the given strings.
intent_has() { local line; while IFS= read -r line; do local ok=1; for w in "$@"; do [[ "$line" == *"$w"* ]] || ok=0; done; [ $ok = 1 ] && return 0; done <<<"$intents"; return 1; }
# The main process (no android:process): the app and its Robot Notes entry, where no engine may ever load.
for c in app.heyrobot.ui.MainActivity app.heyrobot.ui.NotesLauncher; do
  hasnt 'process(0x01010011)' "$(elem $c)" "$c in the main process"
done
# Exported components: exactly this allowlist (the app, its Notes entry, the three assistant services the system
# binds, the Cheogram notification listener (0.4.0, also only the system binds it) and AndroidX's profile installer receiver).
exported=$(echo "$mf" | awk '
  /^ *E: (activity|activity-alias|service|receiver|provider) / { if (n != "" && ex) print n; n = ""; ex = 0; own = 1; next }
  /^ *E: / { own = 0 }
  own && /:name\(0x01010003\)="/ { v = $0; sub(/^.*:name\(0x01010003\)="/, "", v); sub(/".*$/, "", v); n = v }
  own && /exported\(0x01010010\)=true/ { ex = 1 }
  END { if (n != "" && ex) print n }' | sort | tr '\n' ' ')
allow="androidx.profileinstaller.ProfileInstallReceiver app.heyrobot.assistant.RobotRecognitionService app.heyrobot.assistant.RobotSessionService app.heyrobot.assistant.RobotVoiceService app.heyrobot.people.CheogramListener app.heyrobot.ui.MainActivity app.heyrobot.ui.NotesLauncher "
[ "$exported" = "$allow" ] && pass "exported components are the allowlist" || fail "exported components: $exported"
# People (0.4.0): the notification listener stays in the main process, behind the system-only bind permission.
hasnt 'process(0x01010011)' "$(elem app.heyrobot.people.CheogramListener)" "CheogramListener in the main process"
has '"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"' "$(elem app.heyrobot.people.CheogramListener)" "CheogramListener guarded by BIND_NOTIFICATION_LISTENER_SERVICE"
has '"android.service.notification.NotificationListenerService"' "$(subtree app.heyrobot.people.CheogramListener)" "CheogramListener has the listener intent filter"
# "Pick in Cheogram" runs in :voice and isn't exported (only the panel's card starts it).
has ':voice' "$(elem app.heyrobot.assistant.CheogramPickActivity)" "CheogramPickActivity in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.CheogramPickActivity)" "CheogramPickActivity not exported"
# Ask Claude: the key window runs in :voice and isn't exported (the main app never sees the key).
has ':voice' "$(elem app.heyrobot.assistant.ClaudeKeyActivity)" "ClaudeKeyActivity in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.ClaudeKeyActivity)" "ClaudeKeyActivity not exported"
has '"com.anthropic.claude"' "$queries" "<queries> has the Claude app's package"
intent_has '"android.intent.action.VIEW"' 'scheme(0x01010027)="claude"' && pass "<queries> has VIEW claude:// (Claude app hand-off a)" || fail "<queries> VIEW claude://"
intent_has '"android.intent.action.VIEW"' 'scheme(0x01010027)="https"' 'host(0x01010028)="claude.ai"' && pass "<queries> has VIEW https://claude.ai (hand-off b)" || fail "<queries> VIEW https://claude.ai"
intent_has '"android.intent.action.SEND"' 'mimeType(0x01010026)="text/plain"' && pass "<queries> has SEND text/plain (hand-off c)" || fail "<queries> SEND text/plain"
for q in '"com.cheogram.android"' '"com.cheogram.android.playstore"' '"ch.protonmail.android"' 'scheme(0x01010027)="xmpp"' 'scheme(0x01010027)="mailto"' \
  'scheme(0x01010027)="smsto"' 'mimeType(0x01010026)="vnd.android.cursor.dir/event"' '"android.intent.action.INSERT"' '"android.intent.action.SENDTO"'; do
  has "$q" "$queries" "<queries> has $q"
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
# Exactly these native libraries, by name. librobot_llama.so (0.4.4): Robot's llama.cpp for your own GGUF file, built by
# tools/build_llama.sh and loaded by :brain only; a build without it is fine (Settings › Model then says GGUF files can't run).
for l in $(echo "$libs" | sed -n 's#^lib/arm64-v8a/##p'); do
  case "$l" in
    libsherpa-onnx-jni.so|libonnxruntime.so|liblitertlm_jni.so|libLiteRtDispatch_GoogleTensor.so|libandroidx.graphics.path.so|librobot_llama.so) ;;
    *) fail "unexpected native library $l" ;;
  esac
done
llama=0
if echo "$libs" | grep -qx 'lib/arm64-v8a/librobot_llama.so'; then
  llama=1
  pass "packages librobot_llama.so (GGUF files can run)"
  if [ -x "$NDK_BIN/llvm-nm" ]; then
    so=$(mktemp)
    unzip -p "$APK" lib/arm64-v8a/librobot_llama.so > "$so"
    others=$("$NDK_BIN/llvm-nm" -D --defined-only "$so" | awk '{ print $3 }' | grep -vE '^(JNI_OnLoad|Java_app_heyrobot_brain_LlamaNative_[A-Za-z]+)$' || true)
    [ -z "$others" ] && pass "librobot_llama.so exports only its JNI bridge" || fail "librobot_llama.so exports more: $(echo $others | head -c 200)"
    net=$("$NDK_BIN/llvm-nm" -D --undefined-only "$so" | awk '{ print $2 }' | sed 's/@.*//' |
      grep -xE 'socket|connect|getaddrinfo|gethostbyname|bind|listen|accept4?|sendto|recvfrom|fork|vfork|clone|daemon|execv[pe]?|execl[pe]?|execve|posix_spawnp?|popen|system' || true)
    [ -z "$net" ] && pass "librobot_llama.so imports no network or process functions" || fail "librobot_llama.so imports $(echo $net)"
    needed=$("$NDK_BIN/llvm-readelf" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | grep -vxE 'libc\.so|libm\.so|libdl\.so|liblog\.so' || true)
    [ -z "$needed" ] && pass "librobot_llama.so needs only the C library and the loader" || fail "librobot_llama.so needs $(echo $needed)"
    rm -f "$so"
  else
    echo "NOTE  no NDK at $NDK_BIN: librobot_llama.so's symbols not checked"
  fi
else
  pass "no librobot_llama.so (this build can't run GGUF files; Settings › Model says so)"
fi
echo "$libs" | grep -q CompilerPlugin && fail "compiler plugin packaged" || pass "no compiler plugin"
# sherpa-onnx's JNI calls the TTS callback through GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")
# (v1.13.8 jni/offline-tts.cc). A Kotlin lambda has no such method and every voice is silent (0.1.2), so the
# callback class must really declare it, public and not as a bridge, in the dex code that ships.
dexdir=$(mktemp -d)
trap 'rm -rf "$dexdir"' EXIT
unzip -q -o "$APK" 'classes*.dex' -d "$dexdir" 2>/dev/null
cbclass=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } index($0, "Class descriptor  : '\''Lapp/heyrobot/voice/TtsCallback;'\''"){ on=1 } on')
# The main process never reaches an engine: no class of ui/, sys/, boot/, people/, core/ (pure, loaded everywhere) or RobotApp
# refers to sherpa-onnx, LiteRT or the assistant, voice and brain packages in its code (ProcessIsolationTest checks the
# sources; this checks the dex).
engine_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/(ui|sys|boot|people|core)\//{ on=1 } /Class descriptor  : .Lapp\/heyrobot\/RobotApp;/{ on=1 } on' |
  grep -cE 'Lcom/k2fsa/|Lcom/google/ai/edge/|Lcom/anthropic/|Lokhttp3/|Lapp/heyrobot/(assistant|voice|brain|claude)/')
[ "$engine_refs" = "0" ] && pass "no main-process class refers to an engine or the Anthropic SDK (dex)" || fail "main-process classes refer to engines or the SDK: $engine_refs"
# 0.4.4: llama.cpp's bridge is :brain's alone: no class outside app/heyrobot/brain refers to it or to its engine.
llama_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .L/{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/brain\//) } on' |
  grep -cE 'Lapp/heyrobot/brain/(LlamaNative|LlamaEngine)')
[ "$llama_refs" = "0" ] && pass "only :brain's classes refer to LlamaNative and LlamaEngine (dex)" || fail "classes outside :brain refer to llama.cpp's bridge: $llama_refs"
# The SDK is packaged (the :claude module's client and the SDK's OkHttp client), for the :voice process to load.
sdk_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -E "Class descriptor  : 'L(com/anthropic/client/okhttp/AnthropicOkHttpClient|app/heyrobot/claude/ClaudeSession);'"; done | wc -l)
[ "$sdk_classes" = "2" ] && pass "Anthropic SDK client and ClaudeSession in the dex" || fail "Anthropic SDK classes in the dex: $sdk_classes"
# No JVM-only API that ART lacks: none of these packages is referred to anywhere in the dex, and of java.beans only
# what Android has, plus the two annotations Jackson looks up defensively (its optional Java 7 support).
jvm_only=$(LC_ALL=C grep -aohE 'L(java/net/http|java/awt|javax/swing|javax/naming|java/lang/management|javax/management|java/rmi|javax/script|java/util/prefs|javax/xml/bind)/[A-Za-z0-9_$/]*;' "$dexdir"/classes*.dex | sort -u | tr '\n' ' ')
[ -z "$jvm_only" ] && pass "no JVM-only API referred to in the dex" || fail "JVM-only API in the dex: $jvm_only"
beans=$(LC_ALL=C grep -aohE 'Ljava/beans/[A-Za-z0-9_$]*;' "$dexdir"/classes*.dex | sort -u | grep -vxE 'Ljava/beans/(ConstructorProperties|Transient|PropertyChange(Event|Listener|ListenerProxy|Support)|IndexedPropertyChangeEvent);' | tr '\n' ' ')
[ -z "$beans" ] && pass "java.beans: only what ART has and Jackson's optional annotations" || fail "java.beans types ART lacks: $beans"
# No API key anywhere in the APK (resources, assets, dex): nothing shaped like sk-ant-<20+ characters>.
keys=0
for e in $(unzip -Z1 "$APK" | grep -vE '^lib/|\.onnx$'); do
  unzip -p "$APK" "$e" 2>/dev/null | LC_ALL=C grep -aqE 'sk-ant-[A-Za-z0-9_-]{20,}' && { echo "      key-shaped string in $e"; keys=$((keys + 1)); }
done
[ "$keys" = "0" ] && pass "no API key string in the APK" || fail "API key-shaped strings in $keys entries"
rm -rf "$dexdir"
# One "name type access" line per field and method of the class.
cbmembers=$(echo "$cbclass" | awk '/^ *#[0-9]+ *: \(in /{ if (n != "") print n, t, a; n = t = a = "" }
  /^ *name *: /{ n=$3 } /^ *type *: /{ t=$3 } /^ *access *: /{ a=$3 } END { if (n != "") print n, t, a }')
has "'Lkotlin/jvm/functions/Function1;'" "$cbclass" "TtsCallback (sherpa TTS callback) implements kotlin Function1"
echo "$cbmembers" | grep -qxF "'invoke' '([F)Ljava/lang/Integer;' 0x0001" \
  && pass "TtsCallback declares public invoke([F)Ljava/lang/Integer; (what sherpa's JNI looks up)" \
  || fail "TtsCallback lacks public invoke([F)Ljava/lang/Integer;: downloaded voices would be silent"
assets=$(unzip -Z1 "$APK" 'assets/*')
for a in kws/encoder-epoch-12-avg-2-chunk-16-left-64.onnx kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx kws/joiner-epoch-12-avg-2-chunk-16-left-64.onnx kws/tokens.txt kws/keywords.txt kws/stop.txt kws/bpe.model vad/silero_vad.onnx bench/test-clip.s16 bench/test-clip.txt; do
  has "assets/$a" "$assets" "asset $a"
done
# 0.2.3: the wake phrases are spelled on the phone with the keyword model's own SentencePiece file (THIRD_PARTY.md),
# and the spotter's own keyword list is one empty line (every stream gets its keywords at runtime).
bpe=$(unzip -p "$APK" assets/kws/bpe.model | sha256sum | cut -d' ' -f1)
[ "$bpe" = c8a2a0129c4ab8e463164c142f82d25649661b122c8cd0b7aab5c9e80b90ad24 ] && pass "kws/bpe.model sha256 matches" || fail "kws/bpe.model sha256 $bpe"
[ "$(unzip -p "$APK" assets/kws/keywords.txt | od -An -c | tr -d ' ')" = '\n' ] && pass "kws/keywords.txt is one empty line" || fail "kws/keywords.txt is not one empty line"
[ "$(unzip -p "$APK" assets/kws/stop.txt | tr -d '\n')" = "▁ST O P @STOP" ] && pass "kws/stop.txt is STOP" || fail "kws/stop.txt"
# The espeak-ng English + French subset (17 files) the voices need; nothing more of espeak-ng-data.
n=$(echo "$assets" | grep -c '^assets/espeak-ng-data/')
[ "$n" -eq 17 ] && pass "espeak-ng-data subset (17 files)" || fail "espeak-ng-data has $n files, want 17"
has "assets/espeak-ng-data/phontab" "$assets" "asset espeak-ng-data/phontab"
has "assets/espeak-ng-data/lang/gmw/en-US" "$assets" "asset espeak-ng-data/lang/gmw/en-US"
# The speech recognition and voice models are downloaded by the app, never packaged.
echo "$assets" | grep -qE '(encoder|decoder|joiner)\.int8\.onnx|/model(\.fp32|\.int8)?\.onnx|voices\.bin' && fail "a downloadable voice model is packaged" || pass "no downloadable voice model packaged"
clip=$(unzip -l "$APK" assets/bench/test-clip.s16 | awk 'NR==4{print $1}')
[ -n "$clip" ] && [ "$clip" -le 200000 ] && pass "benchmark clip ${clip} bytes (<= 200 KB)" || fail "benchmark clip size '$clip'"
unzip -Z1 "$APK" | grep -qiE '\.(p12|jks|keystore|pass|litertlm|gguf)$' && fail "key or model file inside the APK" || pass "no key or model file inside the APK"
echo; [ "$fails" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$fails CHECK(S) FAILED"
exit $((fails > 0))
