#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# guard.sh PACKAGE [--before-start]: exits 0 only when PACKAGE's own window is the one on screen, the shade and the lock
# screen aren't over it, no call is active, the screen is on and upright. The spoken-test runner runs it before every
# input, so nothing it sends can land in another app. Fails closed: a missing or empty answer from the phone is a refusal.
# --before-start checks everything but which app is on screen, for the one moment before the runner opens the test build.
# ANDROID_SERIAL picks the phone; ROBOT_SPOKEN_ADB replaces adb (the self-test's stand-in).
adb=${ROBOT_SPOKEN_ADB:-adb}
pkg=${1:?usage: guard.sh PACKAGE [--before-start]}
refuse() { echo "GUARD: $*"; exit 1; }
if [ "${2:-}" != "--before-start" ]; then
  top=$("$adb" shell dumpsys activity activities | grep -m1 topResumedActivity)
  # The space and the slash make the match exact: ai.robot_app is not ai.robot_app.spoken.
  case "$top" in
    *" $pkg/"*) ;;
    *) refuse "not $pkg on screen" ;;
  esac
  [ -n "$("$adb" shell pidof "$pkg")" ] || refuse "$pkg isn't running"
fi
win=$("$adb" shell dumpsys window | grep -E -m3 "mCurrentFocus|isKeyguardShowing")
[ -n "$win" ] || refuse "no answer about the windows"
case "$win" in
  *NotificationShade*|*StatusBar*|*"isKeyguardShowing=true"*) refuse "the shade or the lock screen is over it" ;;
esac
if [ "${2:-}" != "--before-start" ]; then
  foc=$(printf '%s\n' "$win" | grep -m1 mCurrentFocus)
  case "$foc" in
    *" $pkg/"*|*VoiceInteractionSession*|*"mCurrentFocus=null"*|*"Pop-up window"*|*"Pop-Up Window"*) ;;
    *) refuse "the focus isn't $pkg's" ;;
  esac
fi
calls=$("$adb" shell dumpsys telecom) || refuse "no answer about calls"
[ -n "$calls" ] || refuse "no answer about calls"
grep -A2 "mCalls:" <<<"$calls" | grep -q "Call \[" && refuse "a call is active"
grep -q "mCurrentRotation=ROTATION_0" <<<"$("$adb" shell dumpsys window displays)" || refuse "the screen isn't upright"
grep -m1 -q "mScreenState=ON" <<<"$("$adb" shell dumpsys display)" || refuse "the display is off"
exit 0
