#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
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
# OpenCL libraries (the GPU, for the general Gemma 4 E2B) and its TPU libraries declared optional, never packaged. 0.7:
# Send feedback needs no permission (no SEND_SMS: "Text it" opens your SMS app), and its classes refer to no network class
# and no Android log (Robot sends nothing itself; the message is never logged). 0.9: the Termux agent's permission, <queries>
# entry and result receiver (:voice, not exported). 1.1: Phone lock's :lock process, its one accessibility service (bound only
# by the system), the cover, the code pad and the receiver (not exported), and no overlay or device admin permission. 1.1,
# Skills: the continuation's receiver (:voice, not exported, no filter). 1.2: the setup guide's GPU self-test service (:voice,
# not exported, Robot's signature permission, no filter). 1.4: Robot Files' isolated reader, :files (isolated, not exported,
# no permission, no filter, nothing else in its process), and the image and PDF decoders reached only from its classes; F5:
# the share target (main process, exported with exactly SEND and SEND_MULTIPLE of any type, no permission, never over the
# lock screen) and scanning's camera provider (main process, not exported).
# Usage: tools/verify_apk.sh <apk>
set -u -o pipefail
# Byte order for every sort (the allowlists below are written in it), whatever the shell's locale.
unset LC_ALL; export LC_COLLATE=C
APK="$1"
ROBOT="$(cd "$(dirname "$0")/.." && pwd)"
# The build tools the build itself uses (build.gradle's robotBuildTools).
BT="${ANDROID_HOME:-/opt/android-sdk}/build-tools/$(sed -n "s/^ *robotBuildTools = '\([^']*\)'.*/\1/p" "$ROBOT/build.gradle")"
[ -x "$BT/aapt2" ] || { echo "No build tools at $BT (set ANDROID_HOME)"; exit 1; }
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
# 1.0.1: no dependency-info block (id 0x504b4453, Google's encrypted list of the libraries) in the signing block, and no
# META-INF/version-control-info.textproto (the commit the APK came from): app/build.gradle's dependenciesInfo and vcsInfo.
# The signing block ends just before the central directory, whose offset the zip's end record gives (an APK has no zip
# comment): its id-value pairs, then its size again and its magic. An unsigned APK has no block, so no ids to check.
u() { od -An -t "u$2" -j "$1" -N "$2" "$APK" | tr -d ' \n'; }
eocd=$(( $(stat -c %s "$APK") - 22 ))
blockids=""
if [ "$(u "$eocd" 4)" = $((0x06054b50)) ]; then
  cdir=$(u $((eocd + 16)) 4)
  if [ "$(od -An -c -j $((cdir - 16)) -N 16 "$APK" | tr -d ' \n')" = "APKSigBlock42" ]; then
    p=$((cdir - $(u $((cdir - 24)) 8)))
    while [ "$p" -lt $((cdir - 24)) ]; do
      blockids="$blockids $(printf '0x%08x' "$(u $((p + 8)) 4)")"; p=$((p + 8 + $(u "$p" 8)))
    done
  fi
  pass "signing block read (ids:${blockids:- none, unsigned})"
else
  fail "no zip end record in the last 22 bytes (a zip comment?): the signing block isn't checked"
fi
grep -qw 0x504b4453 <<<"$blockids" && fail "dependency-info block 0x504b4453 in the signing block" || pass "no dependency-info block (0x504b4453) in the signing block"
grep -qx 'META-INF/version-control-info.textproto' <<<"$(unzip -Z1 "$APK")" && fail "META-INF/version-control-info.textproto in the APK" || pass "no META-INF/version-control-info.textproto"
badging=$("$BT/aapt2" dump badging "$APK" 2>/dev/null)
# The version is app/build.gradle's (tools/bump_version.sh sets it), never repeated here.
vcode=$(sed -nE "s/^ +versionCode ([0-9]+)$/\1/p" "$ROBOT/app/build.gradle")
vname=$(sed -nE "s/^ +versionName '([^']+)'$/\1/p" "$ROBOT/app/build.gradle")
[ -n "$vcode" ] && [ -n "$vname" ] || fail "no versionCode and versionName in app/build.gradle"
has "package: name='ai.robot_app' versionCode='$vcode' versionName='$vname'" "$badging" "package ai.robot_app $vname ($vcode, app/build.gradle's)"
has "minSdkVersion:'34'" "$badging" "minSdk 34"
has "targetSdkVersion:'36'" "$badging" "targetSdk 36"
has "application-label:'Robot'" "$badging" "label Robot"
has "native-code: 'arm64-v8a'" "$badging" "arm64-v8a only"
hasnt "application-debuggable" "$badging" "not debuggable"
mf=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)
# 1.0: the application ID is ai.robot_app (0.9 and earlier: app.heyrobot, still the classes' package), so 1.0 installs beside
# 0.9: its signature permission, every provider's authority and every explicit task affinity are the new ID's, none 0.9's.
internal=$(elem ai.robot_app.permission.INTERNAL)
has 'E: permission ' "$internal" "declares ai.robot_app.permission.INTERNAL"
has 'protectionLevel(0x01010009)=0x00000002' "$internal" "ai.robot_app.permission.INTERNAL is a signature permission"
idattrs=$(grep -E 'android:(authorities|taskAffinity)\(' <<<"$mf")
# 1.4: scanning's camera hand-off (ai.robot_app.scan) makes four; F7's copies handed to another app (ai.robot_app.files_out) five.
[ "$(grep -c 'authorities(' <<<"$idattrs")" = 5 ] && pass "five providers" || fail "providers: $(grep 'authorities(' <<<"$idattrs")"
has '="ai.robot_app.exports"' "$idattrs" "export provider's authority ai.robot_app.exports"
has '="ai.robot_app.scan"' "$idattrs" "scan provider's authority ai.robot_app.scan"
has '="ai.robot_app.files_out"' "$idattrs" "Robot Files' hand-off provider's authority ai.robot_app.files_out"
has '="ai.robot_app.capture"' "$idattrs" "capture provider's authority ai.robot_app.capture"
has '="ai.robot_app.androidx-startup"' "$idattrs" "AndroidX Startup's provider named after the ID too (its library's own placeholder)"
# 1.1: the Phone lock cover's own task (ai.robot_app.lock) makes ten.
[ "$(grep -c 'taskAffinity(' <<<"$idattrs")" = 10 ] && pass "ten task affinities" || fail "task affinities: $(grep 'taskAffinity(' <<<"$idattrs")"
[ -z "$(grep -v '="ai\.robot_app\.' <<<"$idattrs")" ] && pass "authorities and task affinities all ai.robot_app.*" || fail "not ai.robot_app: $(grep -v '="ai\.robot_app\.' <<<"$idattrs")"
hasnt '"app.heyrobot.permission.' "$mf" "no app.heyrobot permission (0.9's)"
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
has '"ai.robot_app.permission.INTERNAL"' "$(elem app.heyrobot.brain.BrainService)" "BrainService guarded by the signature permission"
# 0.5.0: the fast translation engine runs in a process of its own (an abort in slimt ends only that one), bound only by
# Robot's own processes. It never goes online (ProcessIsolationTest checks its module's code).
mtsvc=$(elem app.heyrobot.mt.MtService)
has ':mt' "$mtsvc" "MtService in :mt (the fast translation engine)"
has 'exported(0x01010010)=false' "$mtsvc" "MtService not exported"
has '"ai.robot_app.permission.INTERNAL"' "$mtsvc" "MtService guarded by the signature permission"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.boot.BootReceiver)" "BootReceiver not exported"
# 1.2: the setup guide's GPU self-test, in :voice (where BrainKeeper, :brain's only client, runs it), bound only by Robot:
# not exported, the signature permission on the service itself, and no intent filter (the main app binds it by class name).
chk=$(elem app.heyrobot.assistant.GpuCheckService)
has '"app.heyrobot.assistant.GpuCheckService"' "$mf" "manifest has GpuCheckService"
has 'process(0x01010011)=":voice"' "$chk" "GpuCheckService in :voice"
has 'exported(0x01010010)=false' "$chk" "GpuCheckService not exported"
has 'permission(0x01010006)="ai.robot_app.permission.INTERNAL"' "$chk" "GpuCheckService guarded by the signature permission"
hasnt 'intent-filter' "$(subtree app.heyrobot.assistant.GpuCheckService)" "GpuCheckService has no intent filter"
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
# 1.4, Robot Files: the isolated reader, :files (files-1.4-plan.md §4.3, §5.5), set up as :llama is: bound only by Robot's
# FileParseLink (a fresh isolated instance each time), not exported, no permission either way, no shared isolated process,
# not from the app zygote, no intent filter, and nothing else in its process.
fps=$(elem app.heyrobot.parse.FileParseService)
has '"app.heyrobot.parse.FileParseService"' "$mf" "manifest has parse.FileParseService"
has 'process(0x01010011)=":files"' "$fps" "FileParseService in :files"
has 'isolatedProcess(0x010103a9)=true' "$fps" "FileParseService runs in an isolated process"
has 'exported(0x01010010)=false' "$fps" "FileParseService not exported"
for a in 'permission(0x01010006)' allowSharedIsolatedProcess useAppZygote externalService foregroundServiceType; do
  hasnt "$a" "$fps" "FileParseService has no $a"
done
hasnt '"android.intent.action' "$(subtree app.heyrobot.parse.FileParseService)" "FileParseService has no intent filter"
[ "$(grep -c 'process(0x01010011)=":files"' <<<"$mf")" = 1 ] && pass "nothing else runs in :files" || fail "components in :files: $(grep -c 'process(0x01010011)=":files"' <<<"$mf")"
# 1.4, F5: "Robot Files" in another app's Share (files-1.4-plan.md §3.1): the main process, exported (the share sheet must
# reach it), one filter of exactly SEND and SEND_MULTIPLE with the DEFAULT category and any type, no scheme, no permission,
# never over the lock screen, out of Recents. And scanning's camera provider: the main process, not exported.
fsa=$(elem app.heyrobot.ui.files.FilesShareActivity)
fss=$(subtree app.heyrobot.ui.files.FilesShareActivity)
has '"app.heyrobot.ui.files.FilesShareActivity"' "$mf" "manifest has ui.files.FilesShareActivity"
hasnt 'process(0x01010011)' "$fsa" "FilesShareActivity in the main process"
has 'exported(0x01010010)=true' "$fsa" "FilesShareActivity exported (the share sheet)"
for a in 'permission(0x01010006)' showWhenLocked turnScreenOn directBootAware; do
  hasnt "$a" "$fsa" "FilesShareActivity has no $a"
done
has 'excludeFromRecents(0x01010017)=true' "$fsa" "FilesShareActivity out of Recents"
[ "$(grep -c 'E: intent-filter' <<<"$fss")" = 1 ] && pass "FilesShareActivity has one intent filter" || fail "FilesShareActivity filters: $(grep -c 'E: intent-filter' <<<"$fss")"
fsacts=$(grep -A1 'E: action' <<<"$fss" | grep -o 'name(0x01010003)="[^"]*"' | sed 's/.*="//; s/"$//' | sort | tr '\n' ' ')
[ "$fsacts" = "android.intent.action.SEND android.intent.action.SEND_MULTIPLE " ] && pass "FilesShareActivity takes exactly SEND and SEND_MULTIPLE" || fail "FilesShareActivity actions: $fsacts"
has '"android.intent.category.DEFAULT"' "$fss" "FilesShareActivity's filter has the DEFAULT category"
has 'mimeType(0x01010026)="*/*"' "$fss" "FilesShareActivity takes any type"
for a in scheme host path port; do
  hasnt "$a(0x" "$fss" "FilesShareActivity's filter has no $a"
done
# 1.4 F7: Share and Open with another app: the main process's own provider for the copies it hands out (cache/files_out
# alone), not exported, one-time read grants.
fop=$(elem app.heyrobot.sys.FilesOutProvider)
has '"app.heyrobot.sys.FilesOutProvider"' "$mf" "manifest has sys.FilesOutProvider"
hasnt 'process(0x01010011)' "$fop" "FilesOutProvider in the main process"
has 'exported(0x01010010)=false' "$fop" "FilesOutProvider not exported"
has 'grantUriPermissions(0x0101001b)=true' "$fop" "FilesOutProvider grants per URI"
hasnt 'permission(0x01010006)' "$fop" "FilesOutProvider guarded by no permission (grants only)"
scp=$(elem app.heyrobot.sys.ScanProvider)
has '"app.heyrobot.sys.ScanProvider"' "$mf" "manifest has sys.ScanProvider"
hasnt 'process(0x01010011)' "$scp" "ScanProvider in the main process"
has 'exported(0x01010010)=false' "$scp" "ScanProvider not exported"
# The two isolated services, and no other isolated component (1.4: FileParseService joined LlamaService).
[ "$(grep -c 'isolatedProcess(0x010103a9)=true' <<<"$mf")" = 2 ] && pass "LlamaService and FileParseService are the only isolated components" || fail "isolated components: $(grep -c 'isolatedProcess(0x010103a9)=true' <<<"$mf")"
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
# Helpers need nothing new: INTERNET stays the only permission that goes online (a server on your network too, at
# targetSdk 36), and nothing reads other apps' screens. 1.1, deliberately: ACCESS_NETWORK_STATE, which Android 14+ requires
# for a job with a network constraint (the update check, the Wi-Fi download, Termux's update check: update/UpdateJobs.kt).
has '"android.permission.ACCESS_NETWORK_STATE"' "$mf" "declares ACCESS_NETWORK_STATE (Android 14+ for UpdateJobs' network-constrained jobs)"
for p in ACCESS_WIFI_STATE CHANGE_NETWORK_STATE CHANGE_WIFI_STATE ACCESS_LOCAL_NETWORK; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# 1.1: BIND_ACCESSIBILITY_SERVICE is now named in the manifest, as the permission that guards Phone lock's service (only the
# system holds it), so the check is that Robot never asks for it, nor for an overlay or a device admin.
used=" $(grep -A1 'E: uses-permission' <<<"$mf" | grep -o 'name(0x01010003)="[^"]*"' | sed 's/.*="//; s/"$//' | sort -u | tr '\n' ' ')"
for p in BIND_ACCESSIBILITY_SERVICE SYSTEM_ALERT_WINDOW BIND_DEVICE_ADMIN; do
  hasnt " android.permission.$p " "$used" "doesn't ask for $p"
done
# 0.4.0: the weather (Open-Meteo) and the nearest place (OpenStreetMap's Nominatim) need nothing more: both go online
# from the :voice process's tools only, which ProcessIsolationTest checks in the source.
has '"android.permission.INTERNET"' "$mf" "declares INTERNET (model downloads, helpers, your own server, the weather, the nearest place)"
# 1.1: one accessibility service, Phone lock's, in its own :lock process, bound only by the system, its XML the one the
# design gives (window-state events, no window content: ProcessIsolationTest reads it). Robot still never drives an app.
[ "$(grep -c '"android.accessibilityservice.AccessibilityService"' <<<"$mf")" = 1 ] && pass "one accessibility service" || fail "accessibility services: $(grep -c '"android.accessibilityservice.AccessibilityService"' <<<"$mf")"
has '"android.accessibilityservice.AccessibilityService"' "$(subtree app.heyrobot.lock.LockAccessibilityService)" "the accessibility service is Phone lock's"
lka=$(elem app.heyrobot.lock.LockAccessibilityService)
has 'process(0x01010011)=":lock"' "$lka" "LockAccessibilityService in :lock"
has '"android.permission.BIND_ACCESSIBILITY_SERVICE"' "$lka" "LockAccessibilityService guarded by BIND_ACCESSIBILITY_SERVICE"
has '"android.accessibilityservice"' "$(subtree app.heyrobot.lock.LockAccessibilityService)" "LockAccessibilityService has its meta-data"
for c in app.heyrobot.lock.LockCoverActivity app.heyrobot.lock.LockCodeActivity app.heyrobot.lock.LockAlarmReceiver; do
  has 'process(0x01010011)=":lock"' "$(elem $c)" "$c in :lock"
  has 'exported(0x01010010)=false' "$(elem $c)" "$c not exported"
  hasnt 'showWhenLocked' "$(elem $c)" "$c not shown over the lock screen"
done
has '"android.permission.MODIFY_AUDIO_SETTINGS"' "$mf" "declares MODIFY_AUDIO_SETTINGS (call audio playback)"
has '"android.permission.WRITE_SECURE_SETTINGS"' "$mf" "declares WRITE_SECURE_SETTINGS (adb grant only: keeps the recognizer set)"
# 1.4 FX1: travel mode's confirms ask for the phone's PIN, pattern or password only, through BiometricPrompt (Android checks
# USE_BIOMETRIC for every prompt); never the older USE_FINGERPRINT.
has '"android.permission.USE_BIOMETRIC"' "$mf" "declares USE_BIOMETRIC (travel mode's PIN-only confirm)"
hasnt '"android.permission.USE_FINGERPRINT"' "$mf" "no USE_FINGERPRINT permission"
boot=$(subtree app.heyrobot.boot.BootReceiver)
has '"android.intent.action.LOCKED_BOOT_COMPLETED"' "$boot" "BootReceiver gets LOCKED_BOOT_COMPLETED"
has 'directBootAware(0x01010505)=true' "$(elem app.heyrobot.boot.BootReceiver)" "BootReceiver direct-boot aware"
# (0.7: Send feedback's "Text it" opens your SMS app, so still no SEND_SMS.)
for p in CAMERA READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE MANAGE_EXTERNAL_STORAGE KILL_BACKGROUND_PROCESSES QUERY_ALL_PACKAGES SEND_SMS READ_SMS WRITE_CONTACTS READ_CALL_LOG ACCESS_FINE_LOCATION READ_PHONE_STATE; do
  hasnt "android.permission.$p\"" "$mf" "no $p permission"
done
# 0.4.0: photos and videos (all, or the ones you pick), asked for by the main app only, for free_storage's largest files.
for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED; do
  has "\"android.permission.$p\"" "$mf" "declares $p"
done
has ':voice' "$(elem app.heyrobot.assistant.MediaDeleteActivity)" "MediaDeleteActivity in :voice"
has ':voice' "$(elem app.heyrobot.assistant.WebSearchActivity)" "WebSearchActivity (web search's key and Test) in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.WebSearchActivity)" "WebSearchActivity not exported"
# 1.1: the backup window (backup design §6.1, §12): in :voice, where keys, the voiceprint and the passphrase are handled; not
# exported, and never over the lock screen.
has ':voice' "$(elem app.heyrobot.assistant.backup.BackupActivity)" "BackupActivity (back up and restore) in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.backup.BackupActivity)" "BackupActivity not exported"
hasnt 'showWhenLocked' "$(elem app.heyrobot.assistant.backup.BackupActivity)" "BackupActivity not shown over the lock screen"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.MediaDeleteActivity)" "MediaDeleteActivity not exported"
# 1.1, Skills (plan D7): a skill's "Continue" tap reaches this receiver in :voice, where its one-time token is kept; not
# exported and with no intent filter, so only Robot's own PendingIntent reaches it.
skr=$(elem app.heyrobot.assistant.skills.SkillContinueReceiver)
has 'process(0x01010011)=":voice"' "$skr" "SkillContinueReceiver in :voice"
has 'exported(0x01010010)=false' "$skr" "SkillContinueReceiver not exported"
hasnt 'intent-filter' "$(subtree app.heyrobot.assistant.skills.SkillContinueReceiver)" "SkillContinueReceiver has no intent filter"
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
# 1.4 F7: "Robot Files" on the launcher, an activity-alias of MainActivity as Robot Notes is (files-1.4-plan.md Q1).
falias=$(echo "$mf" | awk '/^ *E: activity-alias /{ on=0 } index($0, "\"app.heyrobot.ui.FilesLauncher\""){ on=1 } /^ *E: (service|receiver|activity|provider) /{ on=0 } on')
has 'targetActivity(0x01010202)="app.heyrobot.ui.MainActivity"' "$falias" "Robot Files alias targets MainActivity"
has 'exported(0x01010010)=true' "$falias" "Robot Files alias exported (launcher)"
has '"android.intent.action.MAIN"' "$falias" "Robot Files alias has MAIN"
has '"android.intent.category.LAUNCHER"' "$falias" "Robot Files alias has LAUNCHER"
hasnt 'E: data' "$falias" "Robot Files alias takes no data"
[ "$(grep -c 'E: activity-alias' <<<"$mf")" = 2 ] && pass "two launcher aliases (Robot Notes, Robot Files)" || fail "aliases: $(grep -c 'E: activity-alias' <<<"$mf")"
grep -A1 'string/files_share_label$' <<<"$res" | grep -qF '"Robot Files"' && pass "Robot Files label" || fail "Robot Files label"
grep -qE 'resource 0x[0-9a-f]+ mipmap/ic_files$' <<<"$res" && pass "Robot Files icon" || fail "Robot Files icon"
# Package visibility for the hand-offs, by name and by intent (no QUERY_ALL_PACKAGES).
queries=$(echo "$mf" | awk '/^ *E: queries /{ on=1 } /^ *E: application /{ on=0 } on')
# One line per <intent> inside <queries>, with everything it declares, so an action is checked with its own data.
intents=$(echo "$queries" | awk '/^ *E: intent /{ if (b != "") print b; b = "intent" ; next } /^ *E: (package|provider) /{ if (b != "") print b; b = ""; next } b != "" { gsub(/^ +/, ""); b = b " " $0 } END { if (b != "") print b }')
# An <intent> that declares all of the given strings.
intent_has() { local line; while IFS= read -r line; do local ok=1; for w in "$@"; do [[ "$line" == *"$w"* ]] || ok=0; done; [ $ok = 1 ] && return 0; done <<<"$intents"; return 1; }
# The main process (no android:process): the app and its Robot Notes entry, where no engine may ever load.
for c in app.heyrobot.ui.MainActivity app.heyrobot.ui.NotesLauncher app.heyrobot.ui.FilesLauncher; do
  hasnt 'process(0x01010011)' "$(elem $c)" "$c in the main process"
done
# Exported components: exactly this allowlist (the app, its Notes and (1.4) Files entries, the three assistant services the system
# binds, the Cheogram notification listener (0.4.0, also only the system binds it), the "Transcribe" Quick Settings tile
# (0.5.0, only the system binds it), Phone lock's accessibility service (1.1, only the system binds it), Robot Files' share
# target (1.4, any app's share sheet: it copies only after your tap) and AndroidX's profile installer receiver).
exported=$(echo "$mf" | awk '
  /^ *E: (activity|activity-alias|service|receiver|provider) / { if (n != "" && ex) print n; n = ""; ex = 0; own = 1; next }
  /^ *E: / { own = 0 }
  own && /:name\(0x01010003\)="/ { v = $0; sub(/^.*:name\(0x01010003\)="/, "", v); sub(/".*$/, "", v); n = v }
  own && /exported\(0x01010010\)=true/ { ex = 1 }
  END { if (n != "" && ex) print n }' | sort | tr '\n' ' ')
allow="androidx.profileinstaller.ProfileInstallReceiver app.heyrobot.assistant.RobotRecognitionService app.heyrobot.assistant.RobotSessionService app.heyrobot.assistant.RobotVoiceService app.heyrobot.assistant.conversation.ConversationTileService app.heyrobot.assistant.headset.HeadsetEntryActivity app.heyrobot.lock.LockAccessibilityService app.heyrobot.people.CheogramListener app.heyrobot.ui.FilesLauncher app.heyrobot.ui.MainActivity app.heyrobot.ui.NotesLauncher app.heyrobot.ui.files.FilesShareActivity "
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
# 0.7, messages: the panel's way to them (MessagesBridge) runs in the main process beside the listener that holds them, isn't
# exported, has no intent filter, and needs Robot's signature permission (it also answers Robot's own uid only, in code).
bridge=$(elem app.heyrobot.people.MessagesBridge)
has '"app.heyrobot.people.MessagesBridge"' "$mf" "manifest has MessagesBridge"
hasnt 'process(0x01010011)' "$bridge" "MessagesBridge in the main process"
has 'exported(0x01010010)=false' "$bridge" "MessagesBridge not exported"
has '"ai.robot_app.permission.INTERNAL"' "$bridge" "MessagesBridge guarded by the signature permission"
hasnt 'intent-filter' "$(subtree app.heyrobot.people.MessagesBridge)" "MessagesBridge has no intent filter"
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
# 0.9, the Termux agent: Termux's own RUN_COMMAND permission (asked for from Settings › Termux), <queries> for com.termux,
# and the result receiver in :voice, not exported. Nothing else: no SMS, phone or location permission comes with it.
has '"com.termux.permission.RUN_COMMAND"' "$mf" "declares com.termux.permission.RUN_COMMAND (the Termux agent)"
has '"com.termux"' "$queries" "<queries> has Termux's package"
has ':voice' "$(elem app.heyrobot.assistant.TermuxResultReceiver)" "TermuxResultReceiver in :voice"
has 'exported(0x01010010)=false' "$(elem app.heyrobot.assistant.TermuxResultReceiver)" "TermuxResultReceiver not exported"
uperms=$(grep -A1 'E: uses-permission' <<<"$mf" | grep -o 'name(0x01010003)="[^"]*"' | sed 's/.*="//; s/"$//' | grep -v '^android\.permission\.\|^com\.android\.\|^ai\.robot_app\.' | sort -u | tr '\n' ' ')
[ "$uperms" = "com.termux.permission.RUN_COMMAND " ] && pass "the only other app's permission is Termux's RUN_COMMAND" || fail "other apps' permissions: $uperms"
vi=$(for f in $(unzip -Z1 "$APK" 'res/*.xml'); do "$BT/aapt2" dump xmltree --file "$f" "$APK" 2>/dev/null; done)
has '"app.heyrobot.assistant.RobotSessionService"' "$vi" "voice-interaction sessionService"
# Launcher shortcuts: the application ID as targetPackage (a resource can't take the placeholder), never 0.9's.
shortcut_pkgs=$(grep -o 'targetPackage([^)]*)="[^"]*"' <<<"$vi" | sed 's/.*="//; s/"$//' | sort | uniq -c | awk '{ print $2 "x" $1 }' | tr '\n' ' ')
[ "$shortcut_pkgs" = "ai.robot_appx2 " ] && pass "both shortcuts target ai.robot_app" || fail "shortcut targetPackage: $shortcut_pkgs"
has '"app.heyrobot.assistant.RobotRecognitionService"' "$vi" "voice-interaction recognitionService"
hasnt 'selectableAsDefault' "$vi" "recognizer selectableAsDefault left at its default (true)"
has 'supportsAssist(0x010104f0)=true' "$vi" "supportsAssist"
has 'supportsLaunchVoiceAssistFromKeyguard(0x010104f1)=true' "$vi" "supportsLaunchVoiceAssistFromKeyguard"
# 0.6.0: the network security config. Cleartext only where Robot's own rule allows it (a server on your network, checked
# in code), never to a company or the model downloads; only the system's CAs, never ones a user added.
has 'networkSecurityConfig(' "$(echo "$mf" | awk '/^ *E: application /{ on=1 } on && /^ *E: / && !/application/{ exit } on')" "application has a networkSecurityConfig"
# 1.1: Android's per-app language (AGP's generated locale config: en-US, fr-CA) and layouts that may mirror right to left.
has 'localeConfig(' "$(echo "$mf" | awk '/^ *E: application /{ on=1 } on && /^ *E: / && !/application/{ exit } on')" "application has a localeConfig (per-app language)"
has 'supportsRtl(' "$(echo "$mf" | awk '/^ *E: application /{ on=1 } on && /^ *E: / && !/application/{ exit } on')" "application supportsRtl"
nsc=$(echo "$vi" | awk '/E: network-security-config/{ on=1 } on')
has 'E: domain-config' "$nsc" "network security config has a domain-config"
nscdomain=$(grep -A3 'E: domain-config' <<<"$nsc")
grep -qE 'cleartextTrafficPermitted.*false' <<<"$nscdomain" && pass "domain-config denies cleartext" || fail "domain-config doesn't deny cleartext"
# 0.9: and the web search services' and lookups' domains (your own SearXNG is decided by LanRule in code, as a helper);
# 1.0: and F-Droid's (Termux's weekly update check).
for d in anthropic.com openai.com googleapis.com mistral.ai x.ai deepseek.com groq.com openrouter.ai huggingface.co open-meteo.com openstreetmap.org github.com githubusercontent.com \
  search.brave.com kagi.com mojeek.com wikipedia.org openlibrary.org f-droid.org duckduckgo.com; do
  has "'$d'" "$nsc" "no cleartext to $d"
done
has '"system"' "$nsc" "trusts the system's CAs"
hasnt '"user"' "$nsc" "never trusts user-added CAs"
libs=$(unzip -Z1 "$APK" 'lib/*')
# 0.8: every native library and dex file compressed in the APK (Android unpacks the libraries into nativeLibraryDir at
# install: extractNativeLibs), so the APK stays under GitHub's 100 MB per file; nothing loads them from the APK.
stored=$(unzip -v "$APK" | awk '$2 == "Stored" && ($8 ~ /^lib\/.*\.so$/ || $8 ~ /^classes[0-9]*\.dex$/) { print $8 }' | tr '\n' ' ')
[ -z "$stored" ] && pass "native libraries and dex files are compressed" || fail "stored uncompressed: $stored"
has 'extractNativeLibs(0x010104ea)=true' "$mf" "extractNativeLibs is true (the libraries unpacked at install)"
size=$(stat -c %s "$APK")
[ "$size" -lt 100000000 ] && pass "APK is $size bytes (under GitHub's 100 MB)" || fail "APK is $size bytes (GitHub refuses files over 100 MB)"
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
# sources; this checks the dex). 0.9: nor to jsoup or web search's clients and page reader (app.heyrobot.helpers.web).
engine_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/(ui|sys|boot|people|core|update)\//{ on=1 } /Class descriptor  : .Lapp\/heyrobot\/RobotApp;/{ on=1 } on' |
  grep -cE 'Lcom/k2fsa/|Lcom/google/ai/edge/|Lcom/anthropic/|Lokhttp3/|Lorg/jsoup/|Lapp/heyrobot/(assistant|voice|brain|helpers|mt)/')
[ "$engine_refs" = "0" ] && pass "no main-process class refers to an engine, the Anthropic SDK or jsoup (dex)" || fail "main-process classes refer to engines, the SDK or jsoup: $engine_refs"
# 0.9 web search: jsoup and the copy of Readability4J are packaged (for the :voice process), and its set-up window, where a
# search key is pasted, runs in :voice and isn't exported.
web_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -E "Class descriptor  : 'L(org/jsoup/Jsoup|app/heyrobot/helpers/web/readability/Readability4J|app/heyrobot/helpers/web/PageFetcher);'"; done | wc -l)
[ "$web_classes" = "3" ] && pass "jsoup, Readability4J's copy and the page fetcher in the dex" || fail "web search classes in the dex: $web_classes"
# 1.1: backups' Argon2id is Robot's copy of Bouncy Castle's generator (core's app.heyrobot.thirdparty.bouncycastle), in the
# dex with core's BackupKdf, the one class that uses it; no Bouncy Castle library is (no provider, no JCA class), and of the
# copy only the 20 files Robot took (and D8's synthetic "-IA" classes of them).
bc_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -E "Class descriptor  : 'L(app/heyrobot/thirdparty/bouncycastle/crypto/generators/Argon2BytesGenerator|app/heyrobot/core/backup/BackupKdf);'"; done | wc -l)
[ "$bc_classes" = "2" ] && pass "Bouncy Castle's Argon2BytesGenerator (Robot's copy) and core's BackupKdf in the dex" || fail "backup KDF classes in the dex: $bc_classes"
bc_other=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -oE "Class descriptor  : 'L(org|app/heyrobot/thirdparty)/bouncycastle/[^';]*" | sed "s/.*'//"; done |
  grep -vE '^Lapp/heyrobot/thirdparty/bouncycastle/(crypto/(generators/Argon2BytesGenerator|params/Argon2Parameters|digests/Blake2bDigest|CharToByteConverter|CipherParameters|CryptoServicePurpose|DataLengthException|Digest|ExtendedDigest|OutputLengthException|PBEParametersGenerator|PasswordConverter|RuntimeCryptoException)|util/(Arrays|Longs|Objects|Pack|StringList|Strings|encoders/UTF8))([$-][A-Za-z0-9_$-]+)?$' | tr '\n' ' ')
[ -z "$bc_other" ] && pass "no Bouncy Castle class but the 20 Robot copied (no library, no provider)" || fail "other Bouncy Castle classes in the dex: $bc_other"
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
# 1.4, Robot Files: a file's bytes are decoded only by :files' own classes (app/heyrobot/parse/): no other Robot class
# refers to the platform's image or PDF decoders, but for today's two users, which a file's bytes never reach (the panel's
# camera and picker, Capture.kt's Images and CaptureActivity, in :voice; Chat's pictures, ui/common/ChatKt, in the main app).
dec_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\//{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/(parse\/|assistant\/(Images|CaptureActivity)[;$]|ui\/common\/ChatKt[;$])/) } on' |
  grep -cE 'Landroid/graphics/(ImageDecoder|BitmapFactory|BitmapRegionDecoder)[;$]|Landroid/graphics/pdf/|Landroid/media/(ExifInterface|MediaMetadataRetriever)[;$]')
[ "$dec_refs" = "0" ] && pass "only :files' classes (and the panel's camera, Chat's pictures) refer to image and PDF decoders (dex)" || fail "other classes refer to decoders: $dec_refs"
# Of Robot's classes, only :files' render or write PDFs (no exception here). AndroidX's PrintHelper, which nothing of Robot
# uses, is the one library class that refers to PdfDocument.
pdf_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\//{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/parse\//) } on' |
  grep -cE 'Landroid/graphics/pdf/(PdfRenderer|PdfRendererPreV|PdfDocument)[;$]')
[ "$pdf_refs" = "0" ] && pass "of Robot's classes, only :files' render or write PDFs (dex)" || fail "other Robot classes refer to PdfRenderer or PdfDocument: $pdf_refs"
# Nothing outside app/heyrobot/parse refers to :files' classes: FileParseLink binds the service by its name.
parse_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .L/{ on = ($0 !~ /Class descriptor  : .Lapp\/heyrobot\/parse\//) } on' |
  grep -c 'Lapp/heyrobot/parse/')
[ "$parse_refs" = "0" ] && pass "no class outside app/heyrobot/parse refers to :files' classes (dex)" || fail "classes outside parse refer to it: $parse_refs"
# :files' classes reach no network, no log, no file by name, no content provider and none of Robot's stores.
parse_bad=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/parse\//{ on=1 } on' |
  grep -cE 'Ljava/net/|Ljavax/net/|Lokhttp3/|Landroid/webkit/|Landroid/util/Log;|Lapp/heyrobot/core/RLog;|Ljava/io/File;|Ljava/io/File(Input|Output)Stream;|Ljava/io/RandomAccessFile;|Landroid/content/ContentResolver;|Lapp/heyrobot/(sys|assistant|ui)/|Lapp/heyrobot/core/(files|memory|backup)/|Landroid/security/keystore/|Ljava/security/KeyStore;')
[ "$parse_bad" = "0" ] && pass ":files' classes refer to no network, log, file path, provider, key or store (dex)" || fail ":files' classes refer to forbidden classes: $parse_bad"
parse_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -cE "Class descriptor  : 'Lapp/heyrobot/parse/(FileParseService|FileParser|AndroidDecoders);'"; done | awk '{ s += $1 } END { print s + 0 }')
[ "$parse_classes" = "3" ] && pass ":files' service, parser and decoders in the dex" || fail ":files' classes in the dex: $parse_classes"
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
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/(assistant|voice|brain|helpers|mt)\//{ on=1 } on' |
  grep -cE 'Lapp/heyrobot/(update|core/update)/')
[ "$upd_refs" = "0" ] && pass "no voice, model, helper or translation class refers to the updater (dex)" || fail "voice-side or engine classes refer to the updater: $upd_refs"
# 0.7, Send feedback: core's feedback package, the page (ui/settings/Feedback*) and sys/Feedback* refer to no network class,
# no WebView or download manager, and never to Android's log: the message leaves only through the app you pick.
fb_refs=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" -d "$d" 2>/dev/null; done |
  awk '/^Class #/{ on=0 } /Class descriptor  : .Lapp\/heyrobot\/(core\/feedback\/|sys\/Feedback|ui\/settings\/Feedback)/{ on=1 } on' |
  grep -cE 'Ljava/net/(URL;|URLConnection|HttpURLConnection|Socket)|Ljavax/net/|Lokhttp3/|Landroid/app/DownloadManager|Landroid/webkit/|Landroid/util/Log;')
[ "$fb_refs" = "0" ] && pass "Send feedback's classes refer to no network class and no log (dex)" || fail "Send feedback's classes refer to network or log classes: $fb_refs"
fb_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -cE "Class descriptor  : 'Lapp/heyrobot/(core/feedback/FeedbackReport|sys/FeedbackSend|ui/settings/FeedbackSettingsKt);'"; done | awk '{ s += $1 } END { print s + 0 }')
[ "$fb_classes" = "3" ] && pass "Send feedback's classes in the dex" || fail "Send feedback's classes in the dex: $fb_classes"
# The SDK is packaged (the :helpers module's client and the SDK's OkHttp client), for the :voice process to load, and
# (0.6.0) the other helpers' client, on plain OkHttp.
sdk_classes=$(for d in "$dexdir"/classes*.dex; do "$BT/dexdump" "$d" 2>/dev/null | grep -E "Class descriptor  : 'L(com/anthropic/client/okhttp/AnthropicOkHttpClient|app/heyrobot/helpers/anthropic/AnthropicSession|app/heyrobot/helpers/openai/OpenAiSession);'"; done | wc -l)
[ "$sdk_classes" = "3" ] && pass "Anthropic SDK client, AnthropicSession and OpenAiSession in the dex" || fail "helper client classes in the dex: $sdk_classes"
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
# 1.0: every part the APK packs is on the list with its text: the MIT and BSD texts carry each holder's copyright line, so
# each ships as its own file (LicencesTest checks the list against the build; this checks the APK that came out of it).
for a in licenses/MIT-anthropic-sdk-java.txt licenses/MIT-onnxruntime.txt licenses/MIT-silero-vad.txt licenses/MIT-piper-phonemize.txt \
    licenses/MIT-jsoup.txt licenses/MIT-termux-constants.txt licenses/MIT-slf4j.txt licenses/MIT-standardwebhooks.txt \
    licenses/MIT-fastdoubleparser.txt licenses/MIT-schubfach.txt licenses/notices/readability4j-NOTICE.txt licenses/CC0-1.0.txt; do
  has "assets/$a" "$assets" "asset $a"
done
for id in sherpa-onnx espeak-ng onnxruntime silero-vad kws-zipformer litert-lm litert-npu-google-tensor anthropic-sdk-java okhttp \
    jsoup readability4j termux-constants jackson kotlin kotlinx-coroutines androidx material-symbols gson tatoeba-cc0 \
    parakeet kokoro kitten titanet-small bergamot-fr-en gemma-4-e2b openstreetmap open-meteo; do
  grep -qF "\"id\": \"$id\"" <<<"$list" && pass "the Licences list names $id" || fail "the Licences list lacks $id"
done
# 1.1: Bouncy Castle (backups' Argon2id, Robot's copy) is on the list with its text and the notice of what was copied.
has "assets/licenses/MIT-bouncycastle.txt" "$assets" "asset licenses/MIT-bouncycastle.txt"
has "assets/licenses/notices/bouncycastle-NOTICE.txt" "$assets" "asset licenses/notices/bouncycastle-NOTICE.txt"
grep -qF '"id": "bouncycastle"' <<<"$list" && pass "the Licences list names bouncycastle" || fail "the Licences list lacks bouncycastle"
n=$(grep -c '"id": ' <<<"$list" || true)
[ "$n" -ge 70 ] && pass "the Licences list has $n entries (>= 70)" || fail "the Licences list has $n entries, want >= 70"
echo; [ "$fails" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$fails CHECK(S) FAILED"
exit $((fails > 0))
