#!/usr/bin/env bash
# Checks a built Robot APK: signer (Robot's release key), package and version, SDK levels, the assistant
# components and their processes, the recognizer that keeps the assistant across restarts, permissions,
# the arm64-only native libraries, and (phase 2) the Robot Notes launcher entry, the hand-off apps' <queries>
# and the three runtime permissions; that the main-process entries stay in the main process, that nothing beyond an
# allowlist is exported, and that no main-process class in the dex refers to an engine. 0.4.5: the native libraries are
# exactly an allowlist of names, and Robot's llama.cpp library (when this build has it) exports only its JNI bridge, is
# reached only from the isolated :llama service's classes and can't go online or start a process; that service is
# declared isolated and not exported. Since 0.5.0, the fast translation engine's :mt process and its library, allowed by
# exact name and loaded only by the :mt module's class (0.6.0: required). The updater (Settings › About › Updates): its two
# permissions, its job service and receivers in the main process and not exported, GitHub's addresses only in its core
# code, no class of the voice or engine processes referring to it, and no GitHub token in the APK. 0.4.7: the phone's
# OpenCL libraries (the GPU, for the general Gemma 4 E2B) and its TPU libraries declared optional, never packaged.
# Usage: tools/verify_apk.sh <apk>
set -u -o pipefail
APK="$1"
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.0.0"
NDK_BIN="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/opt/android-sdk}/ndk/27.1.12297006}/toolchains/llvm/prebuilt/linux-x86_64/bin"
EXPECT_CERT=402557a46a491750688a3dff714100fe1d20b2f61193a912358c2edd341a6b6a
fails=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
# A here-string, not a pipe: with pipefail, grep -q leaving early made a long echo fail with SIGPIPE, so a string near the
# start of the manifest dump could read as missing, and (in a "must not contain" check) a match as absent. Every
# "grep -q" below reads a here-string or a file for the same reason, never a pipe.
has() { grep -qF -- "$1" <<<"$2" && pass "$3" || fail "$3"; }
hasnt() { grep -qF -- "$1" <<<"$2" && fail "$3" || pass "$3"; }
# A component's xmltree lines from its android:name on, children included, up to the next component.
subtree() { echo "$mf" | awk -v n="\"$1\"" '/^ *E: (service|receiver|activity|provider) /{ on=0 } index($0, n){ on=1 } on'; }
# The element's own attribute lines (before its first child element).
elem() { echo "$mf" | awk -v n="\"$1\"" '/^ *E: /{ if (index(b, n)) print b; b="" } { b = b "\n" $0 } END { if (index(b, n)) print b }'; }

cert=$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[ "$cert" = "$EXPECT_CERT" ] && pass "signer $cert (Robot release key)" || fail "signer '$cert'"
# minSdk 34: AGP signs with v3 (API 28+) and may skip v2; either covers every phone Robot installs on.
sigv=$("$BT/apksigner" verify -v "$APK" 2>/dev/null)
grep -qE "Verified using v[23] scheme \(APK Signature Scheme v[23]\): true" <<<"$sigv" && pass "v2/v3 signature verifies" || fail "v2/v3 signature"
badging=$("$BT/aapt2" dump badging "$APK" 2>/dev/null)
has "package: name='app.heyrobot' versionCode='60' versionName='0.6.0'" "$badging" "package app.heyrobot 0.6.0 (60)"
has "minSdkVersion:'34'" "$badging" "minSdk 34"
has "targetSdkVersion:'36'" "$badging" "targetSdk 36"
has "application-label:'Robot'" "$badging" "label Robot"
has "native-code: 'arm64-v8a'" "$badging" "arm64-v8a only"
hasnt "application-debuggable" "$badging" "not debuggable"
mf=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)
for c in assistant.RobotVoiceService assistant.RobotSessionService assistant.RobotRecognitionService assistant.CaptureActivity brain.BrainService mt.MtService boot.BootReceiver boot.BootCheckReceiver ui.MainActivity \
    boot.UpdatedReceiver update.UpdateJobService update.InstallStatusReceiver; do
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
# 0.5.0: the fast translation engine runs in a process of its own (an abort in slimt ends only that one), bound only by
# Robot's own processes. It never goes online (ProcessIsolationTest checks its module's code).
mtsvc=$(elem app.heyrobot.mt.MtService)
has ':mt' "$mtsvc" "MtService in :mt (the fast translation engine)"
has 'exported(0x01010010)=false' "$mtsvc" "MtService not exported"
has '"app.heyrobot.permission.INTERNAL"' "$mtsvc" "MtService guarded by the signature permission"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.boot.BootReceiver)" "BootReceiver not exported"
# 0.4.5: llama.cpp runs in its own isolated process (a uid of its own: no permissions, no network, none of Robot's files),
# which only :brain binds: not exported, no permission either way, no shared isolated process, not from the app zygote.
llm=$(elem app.heyrobot.brain.LlamaService)
has '"app.heyrobot.brain.LlamaService"' "$mf" "manifest has brain.LlamaService"
has 'process(0x01010011)=":llama"' "$llm" "LlamaService in :llama"
has 'isolatedProcess(0x010103a9)=true' "$llm" "LlamaService runs in an isolated process"
has 'exported(0x01010010)=false' "$llm" "LlamaService not exported"
for a in 'permission(0x01010006)' allowSharedIsolatedProcess useAppZygote externalService; do
  hasnt "$a" "$llm" "LlamaService has no $a"
done
hasnt '"android.intent.action' "$(subtree app.heyrobot.brain.LlamaService)" "LlamaService has no intent filter"
[ "$(grep -c 'isolatedProcess(0x010103a9)=true' <<<"$mf")" = 1 ] && pass "LlamaService is the only isolated component" || fail "isolated components: $(grep -c 'isolatedProcess(0x010103a9)=true' <<<"$mf")"
# 0.4.7: LiteRT-LM's GPU backend uses the phone's own OpenCL (a Pixel 9a's Mali), the TPU the phone's own Tensor libraries:
# each declared, and every declaration optional (required defaults to true), so a phone without one still installs Robot
# (the native library allowlist below keeps them out of the APK).
natives=" $(grep -A1 'E: uses-native-library' <<<"$mf" | grep -o 'name(0x01010003)="[^"]*"' | sed 's/.*="//; s/"$//' | sort -u | tr '\n' ' ')"
for l in libOpenCL.so libOpenCL-pixel.so libvndksupport.so libedgetpu_util.so libedgetpu_client.google.so libedgetpu_tachyon.google.so; do
  has " $l " "$natives" "declares the phone's $l"
done
for l in $natives; do
  nl=$(elem "$l")
  [ "$(grep -c 'E: uses-native-library' <<<"$nl")" = "$(grep -c 'required(0x0101028e)=false' <<<"$nl")" ] && pass "$l is optional" || fail "$l is required somewhere"
done
# Helpers need nothing new: INTERNET stays the only network permission (a server on your network too, at targetSdk 36),
# and nothing reads other apps' screens.
for p in ACCESS_NETWORK_STATE ACCESS_WIFI_STATE CHANGE_NETWORK_STATE CHANGE_WIFI_STATE ACCESS_LOCAL_NETWORK BIND_ACCESSIBILITY_SERVICE; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# 0.4.0: the weather (Open-Meteo) and the nearest place (OpenStreetMap's Nominatim) need nothing more: both go online
# from the :voice process's tools only, which ProcessIsolationTest checks in the source.
has '"android.permission.INTERNET"' "$mf" "declares INTERNET (model downloads, helpers, your own server, the weather, the nearest place)"
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
# binds, the Cheogram notification listener (0.4.0, also only the system binds it), the "Transcribe" Quick Settings tile
# (0.5.0, only the system binds it) and AndroidX's profile installer receiver).
exported=$(echo "$mf" | awk '
  /^ *E: (activity|activity-alias|service|receiver|provider) / { if (n != "" && ex) print n; n = ""; ex = 0; own = 1; next }
  /^ *E: / { own = 0 }
  own && /:name\(0x01010003\)="/ { v = $0; sub(/^.*:name\(0x01010003\)="/, "", v); sub(/".*$/, "", v); n = v }
  own && /exported\(0x01010010\)=true/ { ex = 1 }
  END { if (n != "" && ex) print n }' | sort | tr '\n' ' ')
allow="androidx.profileinstaller.ProfileInstallReceiver app.heyrobot.assistant.RobotRecognitionService app.heyrobot.assistant.RobotSessionService app.heyrobot.assistant.RobotVoiceService app.heyrobot.assistant.conversation.ConversationTileService app.heyrobot.assistant.headset.HeadsetEntryActivity app.heyrobot.people.CheogramListener app.heyrobot.ui.MainActivity app.heyrobot.ui.NotesLauncher "
[ "$exported" = "$allow" ] && pass "exported components are the allowlist" || fail "exported components: $exported"
# The updater: Robot installs only its own updates (Install unknown apps, a special access you turn on), and asks Android to
# skip its confirmation only with "Install updates without asking". It never installs or deletes other apps.
has '"android.permission.REQUEST_INSTALL_PACKAGES"' "$mf" "declares REQUEST_INSTALL_PACKAGES (the updater installs Robot's own update)"
has '"android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"' "$mf" "declares UPDATE_PACKAGES_WITHOUT_USER_ACTION (Install updates without asking)"
for p in INSTALL_PACKAGES DELETE_PACKAGES REQUEST_DELETE_PACKAGES INSTALL_SELF_UPDATES INSTALL_PACKAGE_UPDATES ENFORCE_UPDATE_OWNERSHIP RUN_USER_INITIATED_JOBS; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# Its job service only the system binds, its receivers nothing else reaches; all three in the main process.
for c in app.heyrobot.update.UpdateJobService app.heyrobot.update.InstallStatusReceiver app.heyrobot.boot.UpdatedReceiver; do
  has 'exported(0x01010010)=false' "$(elem $c)" "$c not exported"
  hasnt 'process(0x01010011)' "$(elem $c)" "$c in the main process"
done
has '"android.permission.BIND_JOB_SERVICE"' "$(elem app.heyrobot.update.UpdateJobService)" "UpdateJobService guarded by BIND_JOB_SERVICE"
has '"android.intent.action.MY_PACKAGE_REPLACED"' "$(subtree app.heyrobot.boot.UpdatedReceiver)" "UpdatedReceiver gets MY_PACKAGE_REPLACED"
hasnt 'intent-filter' "$(subtree app.heyrobot.update.InstallStatusReceiver)" "InstallStatusReceiver has no intent filter (only its PendingIntent reaches it)"
# People (0.4.0): the notification listener stays in the main process, behind the system-only bind permission.
hasnt 'process(0x01010011)' "$(elem app.heyrobot.people.CheogramListener)" "CheogramListener in the main process"
has '"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"' "$(elem app.heyrobot.people.CheogramListener)" "CheogramListener guarded by BIND_NOTIFICATION_LISTENER_SERVICE"
has '"android.service.notification.NotificationListenerService"' "$(subtree app.heyrobot.people.CheogramListener)" "CheogramListener has the listener intent filter"
# "Pick in Cheogram" runs in :voice and isn't exported (only the panel's card starts it).
has ':voice' "$(elem app.heyrobot.assistant.CheogramPickActivity)" "CheogramPickActivity in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.CheogramPickActivity)" "CheogramPickActivity not exported"
# 0.5.0, conversation mode: the session is a microphone foreground service in :voice, not exported; its screens and the
# Transcription check run there too; the tile is guarded by the system-only bind permission; the export provider (main
# process) isn't exported. Nothing new goes online.
for p in FOREGROUND_SERVICE FOREGROUND_SERVICE_MICROPHONE WAKE_LOCK; do
  has "\"android.permission.$p\"" "$mf" "declares $p (conversation mode)"
done
conv=$(elem app.heyrobot.assistant.conversation.ConversationService)
has ':voice' "$conv" "ConversationService in :voice"
has 'exported(0x01010010)=false' "$conv" "ConversationService not exported"
has 'foregroundServiceType' "$conv" "ConversationService declares its foreground service type (microphone)"
for c in ConversationActivity TranscriptionCheckActivity VoiceprintActivity SummaryActivity; do
  has ':voice' "$(elem app.heyrobot.assistant.conversation.$c)" "$c in :voice"
  has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.conversation.$c)" "$c not exported"
done
has ':voice' "$(elem app.heyrobot.assistant.conversation.ConversationTileService)" "ConversationTileService in :voice"
has '"android.permission.BIND_QUICK_SETTINGS_TILE"' "$(elem app.heyrobot.assistant.conversation.ConversationTileService)" "tile guarded by BIND_QUICK_SETTINGS_TILE"
hasnt 'process(0x01010011)' "$(elem app.heyrobot.sys.ExportProvider)" "ExportProvider in the main process"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.sys.ExportProvider)" "ExportProvider not exported"
# 0.8, routines: the exact alarm's receiver and the speaking service in :voice, neither exported; the service is
# mediaPlayback (Android 17's background audio), with its normal permission; the receiver gets boot, time and time zone
# changes and updates only. Nothing else is new: USE_EXACT_ALARM (not SCHEDULE_EXACT_ALARM), no phone, Bluetooth or Do Not
# Disturb access, and Clock's alarm broadcasts are heard by a runtime receiver, never declared here.
for c in RoutineAlarmReceiver RoutineSpeakingService; do
  has "\"app.heyrobot.assistant.routines.$c\"" "$mf" "manifest has routines.$c"
  has ':voice' "$(elem app.heyrobot.assistant.routines.$c)" "$c in :voice"
  has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.routines.$c)" "$c not exported"
done
# (its type, mediaPlayback, is checked in the merged manifest by ProcessIsolationTest)
has 'foregroundServiceType' "$(elem app.heyrobot.assistant.routines.RoutineSpeakingService)" "RoutineSpeakingService declares its foreground service type"
has '"android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"' "$mf" "declares FOREGROUND_SERVICE_MEDIA_PLAYBACK (a routine speaking with the screen off)"
has '"android.permission.USE_EXACT_ALARM"' "$mf" "declares USE_EXACT_ALARM (the restart check, routines' one exact alarm)"
rtn=$(subtree app.heyrobot.assistant.routines.RoutineAlarmReceiver)
for a in BOOT_COMPLETED TIME_SET TIMEZONE_CHANGED MY_PACKAGE_REPLACED; do
  has "\"android.intent.action.$a\"" "$rtn" "RoutineAlarmReceiver gets $a"
done
hasnt 'LOCKED_BOOT_COMPLETED' "$rtn" "RoutineAlarmReceiver waits for the first unlock (routines.json is credential-encrypted)"
hasnt 'com.android.deskclock' "$mf" "Clock's alarm broadcasts aren't a manifest receiver (a runtime one in :voice)"
for p in SCHEDULE_EXACT_ALARM FOREGROUND_SERVICE_SHORT_SERVICE; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# 0.8, headset and car mode: the headset button's trampoline in :voice, exported with exactly its three actions (Android
# starts it for a headset's button), and the car screen in :voice, not exported; Nearby devices, the Phone permission and
# Do Not Disturb access declared, each asked only from its own settings row; still no phone state, scanning or SMS.
has ':voice' "$(elem app.heyrobot.assistant.headset.HeadsetEntryActivity)" "HeadsetEntryActivity in :voice"
has 'exported(0x01010010)=true' "$(elem app.heyrobot.assistant.headset.HeadsetEntryActivity)" "HeadsetEntryActivity exported (the headset button)"
hea=$(subtree app.heyrobot.assistant.headset.HeadsetEntryActivity)
for a in android.intent.action.VOICE_COMMAND android.speech.action.WEB_SEARCH android.speech.action.VOICE_SEARCH_HANDS_FREE; do
  has "\"$a\"" "$hea" "HeadsetEntryActivity gets $a"
done
[ "$(grep -c 'E: action ' <<<"$hea")" = 3 ] && pass "HeadsetEntryActivity has exactly three actions" || fail "HeadsetEntryActivity actions: $(grep -c 'E: action ' <<<"$hea")"
hasnt 'android.intent.action.WEB_SEARCH"' "$hea" "HeadsetEntryActivity doesn't take the browsers' WEB_SEARCH"
has ':voice' "$(elem app.heyrobot.assistant.car.CarModeActivity)" "CarModeActivity in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.car.CarModeActivity)" "CarModeActivity not exported"
for p in BLUETOOTH_CONNECT CALL_PHONE ACCESS_NOTIFICATION_POLICY; do
  has "\"android.permission.$p\"" "$mf" "declares $p (asked only from its settings row)"
done
for p in READ_PHONE_STATE BLUETOOTH_SCAN MANAGE_OWN_CALLS ANSWER_PHONE_CALLS; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
intent_has '"android.intent.action.DIAL"' 'scheme(0x01010027)="tel"' && pass "<queries> has DIAL tel: (calls)" || fail "<queries> DIAL tel:"
intent_has '"android.intent.action.VOICE_COMMAND"' && pass "<queries> has VOICE_COMMAND (who answers the headset button)" || fail "<queries> VOICE_COMMAND"
# Helpers (0.6.0): the connection window (keys, Test connection) runs in :voice and isn't exported (the main app never sees a key).
has ':voice' "$(elem app.heyrobot.assistant.HelperConnectionActivity)" "HelperConnectionActivity in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.HelperConnectionActivity)" "HelperConnectionActivity not exported"
hasnt '"app.heyrobot.assistant.ClaudeKeyActivity"' "$mf" "no ClaudeKeyActivity (replaced by HelperConnectionActivity)"
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
# 0.6.0: the network security config. Cleartext only where Robot's own rule allows it (a server on your network, checked
# in code), never to a company or the model downloads; only the system's CAs, never ones a user added.
has 'networkSecurityConfig(' "$(echo "$mf" | awk '/^ *E: application /{ on=1 } on && /^ *E: / && !/application/{ exit } on')" "application has a networkSecurityConfig"
nsc=$(echo "$vi" | awk '/E: network-security-config/{ on=1 } on')
has 'E: domain-config' "$nsc" "network security config has a domain-config"
nscdomain=$(grep -A3 'E: domain-config' <<<"$nsc")
grep -qE 'cleartextTrafficPermitted.*false' <<<"$nscdomain" && pass "domain-config denies cleartext" || fail "domain-config doesn't deny cleartext"
for d in anthropic.com openai.com googleapis.com mistral.ai x.ai deepseek.com groq.com openrouter.ai huggingface.co open-meteo.com openstreetmap.org github.com githubusercontent.com; do
  has "'$d'" "$nsc" "no cleartext to $d"
done
has '"system"' "$nsc" "trusts the system's CAs"
hasnt '"user"' "$nsc" "never trusts user-added CAs"
libs=$(unzip -Z1 "$APK" 'lib/*')
for l in libsherpa-onnx-jni.so libonnxruntime.so liblitertlm_jni.so libLiteRtDispatch_GoogleTensor.so; do has "lib/arm64-v8a/$l" "$libs" "packages $l"; done
other_abis=$(grep -v '^lib/arm64-v8a/' <<<"$libs")
[ -z "$other_abis" ] && pass "no other ABIs" || fail "non-arm64 libraries: $other_abis"
# Every packaged library by its exact name: sherpa-onnx's two (:voice), LiteRT-LM's and the TPU dispatch library (:brain),
# Compose's graphics-path helper (androidx.graphics:graphics-path, every process), librobot_llama.so (0.4.5: Robot's
# llama.cpp for your own GGUF file, built by tools/build_llama.sh and loaded by :brain only; a build without it is fine,
# Settings › Model then says GGUF files can't run) and librobotmt.so (0.5.0: the fast translation engine, :mt only).
extra=$(sed 's|^lib/arm64-v8a/||' <<<"$libs" | grep -vxE 'libsherpa-onnx-jni\.so|libonnxruntime\.so|liblitertlm_jni\.so|libLiteRtDispatch_GoogleTensor\.so|libandroidx\.graphics\.path\.so|librobot_llama\.so|librobotmt\.so' | tr '\n' ' ')
[ -z "$extra" ] && pass "native libraries are the allowlist" || fail "native libraries not on the allowlist: $extra"
llama=0
if grep -qx 'lib/arm64-v8a/librobot_llama.so' <<<"$libs"; then
  llama=1
  pass "packages librobot_llama.so (GGUF files can run)"
  if [ -x "$NDK_BIN/llvm-nm" ]; then
    so=$(mktemp)
    unzip -p "$APK" lib/arm64-v8a/librobot_llama.so > "$so"
    others=$("$NDK_BIN/llvm-nm" -D --defined-only "$so" | awk '{ print $3 }' | grep -vE '^(JNI_OnLoad|Java_app_heyrobot_brain_LlamaNative_[A-Za-z]+)$' || true)
    [ -z "$others" ] && pass "librobot_llama.so exports only its JNI bridge" || fail "librobot_llama.so exports more: $(echo $others | head -c 200)"
    net=$("$NDK_BIN/llvm-nm" -D --undefined-only "$so" | awk '{ print $2 }' | sed 's/@.*//' |
      grep -xE 'socket|connect|getaddrinfo|gethostbyname|bind|listen|accept4?|sendto|recvfrom|fork|vfork|clone|daemon|execv[pe]?|execvpe|execl[pe]?|execve|execveat|fexecve|posix_spawnp?|popen|system' || true)
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
# 0.6.0 ships the fast translation engine: a build without librobotmt.so (tools/build_robotmt.sh) is not this release.
if grep -qx 'lib/arm64-v8a/librobotmt.so' <<<"$libs"; then
  pass "packages librobotmt.so (the fast translation engine)"
  RE="$NDK_BIN/llvm-readelf"
  [ -x "$RE" ] || RE=$(ls "${ANDROID_HOME:-/opt/android-sdk}"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf 2>/dev/null | head -1)
  [ -n "$RE" ] || RE=readelf
  mtdir=$(mktemp -d)
  unzip -q -o "$APK" lib/arm64-v8a/librobotmt.so -d "$mtdir"
  needed=$("$RE" -d "$mtdir/lib/arm64-v8a/librobotmt.so" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
  [ "$needed" = "libc.so libdl.so liblog.so libm.so " ] && pass "librobotmt.so needs only libc, libdl, liblog and libm" || fail "librobotmt.so needs: $needed"
  exports=$("$RE" --dyn-syms "$mtdir/lib/arm64-v8a/librobotmt.so" 2>/dev/null | awk '$5 == "GLOBAL" && $7 != "UND" { print $8 }' | sort | tr '\n' ' ')
  [ "$exports" = "Java_app_heyrobot_mt_Slimt_nativeClose Java_app_heyrobot_mt_Slimt_nativeOpen Java_app_heyrobot_mt_Slimt_nativeTranslate " ] \
    && pass "librobotmt.so exports only its three JNI functions" || fail "librobotmt.so exports: $exports"
  rm -rf "$mtdir"
else
  fail "no librobotmt.so: 0.6.0 ships the fast translation engine (tools/build_robotmt.sh builds it before the APK)"
fi
grep -q CompilerPlugin <<<"$libs" && fail "compiler plugin packaged" || pass "no compiler plugin"
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
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/(ui|sys|boot|people|core|update)\//{ on=1 } /Class descriptor  : .Lapp\/heyrobot\/RobotApp;/{ on=1 } on' |
  grep -cE 'Lcom/k2fsa/|Lcom/google/ai/edge/|Lcom/anthropic/|Lokhttp3/|Lapp/heyrobot/(assistant|voice|brain|claude|helpers|mt)/')
[ "$engine_refs" = "0" ] && pass "no main-process class refers to an engine or the Anthropic SDK (dex)" || fail "main-process classes refer to engines or the SDK: $engine_refs"
# 0.4.5: llama.cpp's bridge is :brain's alone: no class outside app/heyrobot/brain refers to it or to its engine.
llama_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .L/{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/brain\//) } on' |
  grep -cE 'Lapp/heyrobot/brain/(LlamaNative|LlamaEngine)')
[ "$llama_refs" = "0" ] && pass "only :brain's classes refer to LlamaNative and LlamaEngine (dex)" || fail "classes outside :brain refer to llama.cpp's bridge: $llama_refs"
# 0.4.5: of those, only the isolated service's own classes reach the bridge (it loads the library only in that process).
native_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .L/{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/brain\/(LlamaNative|LlamaService)[;$]/) } on' |
  grep -c 'Lapp/heyrobot/brain/LlamaNative')
[ "$native_refs" = "0" ] && pass "only LlamaService's classes refer to LlamaNative: llama.cpp loads only in the isolated process (dex)" || fail "classes besides LlamaService refer to LlamaNative: $native_refs"
# Only the :mt module's JNI class loads the fast translation engine (its library name appears in no other class's code).
mt_loaders=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/Class descriptor  :/{ c = $4 } /const-string/ && /"robotmt"/ { print c }' | sort -u | tr '\n' ' ')
[ "$mt_loaders" = "'Lapp/heyrobot/mt/Slimt;' " ] && pass "only app.heyrobot.mt.Slimt loads librobotmt (dex)" || fail "classes that name librobotmt: $mt_loaders"
# The updater: of Robot's own classes, only core's update package writes GitHub's addresses (the hosts, the API's and a
# release page's URLs), and no class of the voice, model, helper or translation processes refers to the updater.
gh_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\//{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/core\/update\//) } on' |
  grep -cE 'const-string.*(https://(api\.)?github\.com|githubusercontent\.com)')
[ "$gh_refs" = "0" ] && pass "GitHub's addresses only in core's update package (dex)" || fail "other Robot classes write GitHub's addresses: $gh_refs"
upd_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/(assistant|voice|brain|claude|helpers|mt)\//{ on=1 } on' |
  grep -cE 'Lapp/heyrobot/(update|core/update)/')
[ "$upd_refs" = "0" ] && pass "no voice, model, helper or translation class refers to the updater (dex)" || fail "voice-side or engine classes refer to the updater: $upd_refs"
# The SDK is packaged (the :claude module's client and the SDK's OkHttp client), for the :voice process to load, and
# (0.6.0) the other helpers' client, on plain OkHttp.
sdk_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -E "Class descriptor  : 'L(com/anthropic/client/okhttp/AnthropicOkHttpClient|app/heyrobot/claude/ClaudeSession|app/heyrobot/helpers/openai/OpenAiSession);'"; done | wc -l)
[ "$sdk_classes" = "3" ] && pass "Anthropic SDK client, ClaudeSession and OpenAiSession in the dex" || fail "helper client classes in the dex: $sdk_classes"
# No JVM-only API that ART lacks: none of these packages is referred to anywhere in the dex, and of java.beans only
# what Android has, plus the two annotations Jackson looks up defensively (its optional Java 7 support).
jvm_only=$(LC_ALL=C grep -aohE 'L(java/net/http|java/awt|javax/swing|javax/naming|java/lang/management|javax/management|java/rmi|javax/script|java/util/prefs|javax/xml/bind)/[A-Za-z0-9_$/]*;' "$dexdir"/classes*.dex | sort -u | tr '\n' ' ')
[ -z "$jvm_only" ] && pass "no JVM-only API referred to in the dex" || fail "JVM-only API in the dex: $jvm_only"
beans=$(LC_ALL=C grep -aohE 'Ljava/beans/[A-Za-z0-9_$]*;' "$dexdir"/classes*.dex | sort -u | grep -vxE 'Ljava/beans/(ConstructorProperties|Transient|PropertyChange(Event|Listener|ListenerProxy|Support)|IndexedPropertyChangeEvent);' | tr '\n' ' ')
[ -z "$beans" ] && pass "java.beans: only what ART has and Jackson's optional annotations" || fail "java.beans types ART lacks: $beans"
# No API key anywhere in the APK (resources, assets, dex): nothing shaped like an Anthropic, OpenAI, OpenRouter, Groq, xAI
# or Google key.
keys=0
for e in $(unzip -Z1 "$APK" | grep -vE '^lib/|\.onnx$'); do
  # grep -c reads the whole entry (grep -q leaving early would end unzip with SIGPIPE, and pipefail would hide a match).
  n=$(unzip -p "$APK" "$e" 2>/dev/null | LC_ALL=C grep -acE 'sk-(ant|proj|or)-[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9]{20,}|xai-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{35}' || true)
  [ "${n:-0}" -gt 0 ] && { echo "      key-shaped string in $e"; keys=$((keys + 1)); }
done
[ "$keys" = "0" ] && pass "no API key string in the APK" || fail "API key-shaped strings in $keys entries"
# Nor a GitHub access token (the updater's is entered on the phone and kept encrypted there). grep -c, as above.
tokens=0
for e in $(unzip -Z1 "$APK" | grep -vE '^lib/|\.onnx$'); do
  n=$(unzip -p "$APK" "$e" 2>/dev/null | LC_ALL=C grep -acE 'github_pat_[A-Za-z0-9_]{60,}|gh[pousr]_[A-Za-z0-9]{36}' || true)
  [ "${n:-0}" -gt 0 ] && { echo "      token-shaped string in $e"; tokens=$((tokens + 1)); }
done
[ "$tokens" = "0" ] && pass "no GitHub token string in the APK" || fail "GitHub token-shaped strings in $tokens entries"
rm -rf "$dexdir"
# One "name type access" line per field and method of the class.
cbmembers=$(echo "$cbclass" | awk '/^ *#[0-9]+ *: \(in /{ if (n != "") print n, t, a; n = t = a = "" }
  /^ *name *: /{ n=$3 } /^ *type *: /{ t=$3 } /^ *access *: /{ a=$3 } END { if (n != "") print n, t, a }')
has "'Lkotlin/jvm/functions/Function1;'" "$cbclass" "TtsCallback (sherpa TTS callback) implements kotlin Function1"
grep -qxF "'invoke' '([F)Ljava/lang/Integer;' 0x0001" <<<"$cbmembers" \
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
grep -qE '(encoder|decoder|joiner)\.int8\.onnx|/model(\.fp32|\.int8)?\.onnx|voices\.bin' <<<"$assets" && fail "a downloadable voice model is packaged" || pass "no downloadable voice model packaged"
clip=$(unzip -l "$APK" assets/bench/test-clip.s16 | awk 'NR==4{print $1}')
[ -n "$clip" ] && [ "$clip" -le 200000 ] && pass "benchmark clip ${clip} bytes (<= 200 KB)" || fail "benchmark clip size '$clip'"
# (0.5.0: the fast translation engine's Bergamot files too, which the app downloads.)
entries=$(unzip -Z1 "$APK")
[ -n "$entries" ] && ! grep -qiE '\.(p12|jks|keystore|pass|litertlm|gguf|spm)$|intgemm|\.s2t\.bin$' <<<"$entries" && pass "no key or model file inside the APK" \
  || fail "key or model file inside the APK (or no entries)"
# 0.4.5: Settings › About › Licences. The list, the licence texts and the notices ship (LiteRT's copied from its AARs by
# :brain's aarNotices task), and Robot's GPL is the repository's LICENSE.
for a in licenses/components.json licenses/GPL-3.0.txt licenses/Apache-2.0.txt licenses/MPL-2.0.txt licenses/CC-BY-4.0.txt \
    licenses/notices/onnxruntime-ThirdPartyNotices.txt licenses/notices/java-libraries-NOTICE.txt \
    licenses/notices/litertlm-android-LICENSE.txt licenses/notices/litertlm-android-THIRD_PARTY_NOTICE.txt \
    licenses/notices/litert-npu-runtime-google-tensor-LICENSE.txt licenses/notices/litert-npu-runtime-google-tensor-THIRD_PARTY_NOTICE.txt; do
  has "assets/$a" "$assets" "asset $a"
done
lic=$(unzip -p "$APK" assets/licenses/GPL-3.0.txt | sha256sum | cut -d' ' -f1)
[ "$lic" = "$(sha256sum "$(dirname "$0")/../../LICENSE" | cut -d' ' -f1)" ] && pass "licenses/GPL-3.0.txt is Robot's LICENSE" || fail "licenses/GPL-3.0.txt differs from LICENSE"
notice=$(unzip -l "$APK" assets/licenses/notices/litertlm-android-THIRD_PARTY_NOTICE.txt | awk 'NR==4{print $1}')
[ -n "$notice" ] && [ "$notice" -ge 1000000 ] && pass "LiteRT-LM's notices ${notice} bytes" || fail "LiteRT-LM's notices size '$notice'"
list=$(unzip -p "$APK" assets/licenses/components.json)
# 0.4.5: with Robot's llama.cpp library, the Licences list names its parts and their texts ship.
if [ "$llama" = 1 ]; then
  for a in licenses/MIT-llama.cpp.txt licenses/MIT-nlohmann-json.txt licenses/LLVM-libcxx-LICENSE.txt; do
    has "assets/$a" "$assets" "asset $a (librobot_llama.so)"
  done
  for id in llama-cpp kleidiai llvm-libcxx nlohmann-json; do
    grep -qF "\"id\": \"$id\"" <<<"$list" && pass "the Licences list names $id (librobot_llama.so)" || fail "the Licences list lacks $id"
  done
fi
# 0.6.0: the fast translation engine's parts (librobotmt.so) and the language ID table's data are on the list, with their texts.
for a in licenses/GPL-2.0-or-later-slimt.txt licenses/BSD-2-Clause-cpuinfo.txt licenses/sentencepiece-LICENSE.txt licenses/CC0-1.0.txt; do
  has "assets/$a" "$assets" "asset $a"
done
for id in slimt ruy cpuinfo sentencepiece-browsermt tatoeba-cc0; do
  grep -qF "\"id\": \"$id\"" <<<"$list" && pass "the Licences list names $id" || fail "the Licences list lacks $id"
done
echo; [ "$fails" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$fails CHECK(S) FAILED"
exit $((fails > 0))
