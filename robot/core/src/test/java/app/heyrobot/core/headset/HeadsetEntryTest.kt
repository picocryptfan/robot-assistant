// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice button's three intents (design §3.1, §3.9 HeadsetEntry.decide) and Settings › Headset (§3.4). */
class HeadsetEntryTest {
    @Test fun theActionsAreAndroidsOwn() {
        // Compile-time constants from android.jar: a typo in core fails here, not on the phone.
        assertEquals(Intent.ACTION_VOICE_COMMAND, HeadsetEntry.VOICE_COMMAND)
        assertEquals(RecognizerIntent.ACTION_WEB_SEARCH, HeadsetEntry.WEB_SEARCH)
        assertEquals(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE, HeadsetEntry.VOICE_SEARCH_HANDS_FREE)
        assertEquals(RecognizerIntent.EXTRA_SECURE, HeadsetEntry.EXTRA_SECURE)
        // Never the browsers' web search.
        assertFalse(Intent.ACTION_WEB_SEARCH in HeadsetEntry.ACTIONS)
        assertEquals(3, HeadsetEntry.ACTIONS.size)
        assertEquals(KeyEvent.KEYCODE_HEADSETHOOK, MediaKey.HEADSETHOOK.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaKey.PLAY_PAUSE.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaKey.NEXT.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaKey.PREVIOUS.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, MediaKey.PLAY.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, MediaKey.PAUSE.keyCode)
        assertEquals(MediaKey.PLAY_PAUSE, MediaKey.of(85))
        assertEquals(null, MediaKey.of(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun decide() {
        fun d(action: String?, secure: Boolean, locked: Boolean, running: Boolean, buttonOn: Boolean, car: Boolean, ack: Boolean) =
            HeadsetEntry.decide(action, secure, locked, running, buttonOn, car, ack)
        assertEquals(EntryDecision.Show(EntryKind.BVRA, false, false, false), d(HeadsetEntry.VOICE_COMMAND, false, false, true, true, false, false))
        assertEquals(EntryDecision.Show(EntryKind.BVRA, false, false, true), d(HeadsetEntry.VOICE_COMMAND, false, false, true, true, false, true))
        assertEquals(EntryDecision.Show(EntryKind.HOOK, false, false, false), d(HeadsetEntry.WEB_SEARCH, false, false, true, true, false, true))
        // Locked: from the keyguard or EXTRA_SECURE.
        assertEquals(EntryDecision.Show(EntryKind.HOOK, true, false, false), d(HeadsetEntry.VOICE_SEARCH_HANDS_FREE, true, false, true, true, false, false))
        assertEquals(EntryDecision.Show(EntryKind.HOOK, true, false, false), d(HeadsetEntry.VOICE_SEARCH_HANDS_FREE, false, true, true, true, false, false))
        // The steering wheel's button in car mode is the car's session.
        assertEquals(EntryDecision.Show(EntryKind.BVRA, false, true, false), d(HeadsetEntry.VOICE_COMMAND, false, false, true, true, true, false))
        assertEquals(EntryDecision.NotAssistant, d(HeadsetEntry.VOICE_COMMAND, false, false, false, true, false, false))
        assertEquals(EntryDecision.Ignore, d(HeadsetEntry.VOICE_COMMAND, false, false, true, false, false, false))
        for (a in listOf(null, "", Intent.ACTION_WEB_SEARCH, "android.intent.action.VOICE_ASSIST", Intent.ACTION_ASSIST, "android.intent.action.MAIN")) {
            assertEquals(a, EntryDecision.Ignore, d(a, false, false, true, true, false, false))
        }
        assertEquals(EntryReferrer.BLUETOOTH, EntryReferrer.of("com.android.bluetooth"))
        assertEquals(EntryReferrer.SYSTEM, EntryReferrer.of("android"))
        assertEquals(EntryReferrer.OTHER, EntryReferrer.of("com.example.spoof"))
        assertEquals(EntryReferrer.NONE, EntryReferrer.of(null))
    }

    @Test fun onlyTheSystemsButtonPathsAndRobotItselfMayStartIt() {
        // 1.0.1: the activity shows over the lock screen and is exported, so its caller is checked as Android records it:
        // the system (the wired or USB long-press, a voice-assist key), Bluetooth (a headset's or car's voice button), Robot.
        val own = "app.heyrobot"
        fun t(referrer: String?, claimed: Boolean = false, fromOwnUid: Boolean = false) = EntryCaller.trusted(referrer, claimed, own, fromOwnUid)
        assertTrue(t("android"))
        assertTrue(t("com.android.bluetooth"))
        assertTrue(t("com.google.android.bluetooth"))
        assertTrue(t("com.android.systemui"))
        assertTrue(t(own))
        assertTrue(t(null, fromOwnUid = true))
        // Any other app, no caller at all, or a start that names its own referrer (EXTRA_REFERRER can say "android").
        assertFalse(t("com.example.spoof"))
        assertFalse(t(null))
        assertFalse(t(""))
        assertFalse(t("android", claimed = true))
        assertFalse(t("com.android.bluetooth.evil"))
        // An untrusted caller gets nothing: no panel, no toast.
        assertEquals(EntryDecision.NotTrusted, HeadsetEntry.decide(HeadsetEntry.VOICE_SEARCH_HANDS_FREE, true, true, true, callerTrusted = false))
        assertEquals(EntryDecision.Ignore, HeadsetEntry.decide("android.intent.action.MAIN", false, false, true, callerTrusted = false))
    }

    @Test fun settingsDefaultsAndJson() {
        val s = HeadsetSettings()
        // The design's defaults (§3.4).
        assertTrue(s.buttonOpensRobot && s.fallbackToPhoneMic && s.speakInHeadset && s.stopOnDisconnect)
        assertEquals(ListenWith.HEADSET_MIC, s.listenWith)
        assertEquals(6, s.followUpSeconds)
        assertEquals(6_000L, s.followUpMs)
        assertFalse(s.wakeThroughHeadset || s.announceLocked || s.answerVoiceButton || s.buttonsWhileTalking)
        // 0.8: the headset's part of announcements is on; nothing is announced until Settings › Messages' switch (off) is on.
        assertTrue(s.announceMessages)
        assertFalse(app.heyrobot.core.messages.MessageSettings().announce)
        assertEquals(AnnounceFrom.CHOSEN_PEOPLE, s.announceFrom)
        assertEquals(AnnounceRead.ASK_FIRST, s.announceRead)
        // An older settings file (no fragment) and an empty one read as the defaults.
        assertEquals(s, HeadsetSettings.fromJson(null))
        assertEquals(s, HeadsetSettings.fromJson(JSONObject()))
        val changed = HeadsetSettings(false, ListenWith.PHONE_MIC, false, false, 12, false, true, true, AnnounceFrom.EVERYONE, AnnounceRead.ALWAYS, true, true, true)
        assertEquals(changed, HeadsetSettings.fromJson(JSONObject(changed.toJson().toString())))
        // Out of range and unknown values: clamped or the default.
        val odd = HeadsetSettings.fromJson(JSONObject().put("followUpSeconds", 99).put("listenWith", "EAR").put("announceFrom", 3))
        assertEquals(15, odd.followUpSeconds)
        assertEquals(ListenWith.HEADSET_MIC, odd.listenWith)
        assertEquals(AnnounceFrom.CHOSEN_PEOPLE, odd.announceFrom)
        assertEquals(0, HeadsetSettings.fromJson(JSONObject().put("followUpSeconds", -3)).followUpSeconds)
        // OutputGuard's settings from here and Safety & privacy.
        val g = s.copy(stopOnDisconnect = false, announceLocked = true).guard(app.heyrobot.core.output.HandsFreeSafety(personalOnlyPrivate = false))
        assertFalse(g.personalOnlyPrivate || g.stopOnDisconnect)
        assertTrue(g.announceLocked)
    }
}

/** Single, double and long presses (design §3.1 platform facts, §3.7 rule 3). */
class ButtonGesturesTest {
    private fun key(k: MediaKey, down: Boolean, at: Long, src: KeySource = KeySource.BLUETOOTH, long: Boolean = false) = ButtonInput.Key(k, down, at, src, long)

    /** A tap (down then up) at [at]. */
    private fun tap(s: GestureState, at: Long, phase: RobotPhase, src: KeySource = KeySource.BLUETOOTH, k: MediaKey = MediaKey.PLAY_PAUSE, hold: Long = 50): ButtonResult {
        val d = ButtonGestures.onInput(s, key(k, true, at, src), phase, true)
        assertEquals(ButtonAction.IGNORE, d.action)
        return ButtonGestures.onInput(d.state, key(k, false, at + hold, src), phase, true)
    }

    @Test fun theVoiceButtonListensOrStops() {
        val s = GestureState()
        val expect = mapOf(RobotPhase.IDLE to ButtonAction.LISTEN, RobotPhase.FOLLOW_UP to ButtonAction.LISTEN, RobotPhase.CARD to ButtonAction.LISTEN,
            RobotPhase.LISTENING to ButtonAction.STOP_LISTENING, RobotPhase.THINKING to ButtonAction.CANCEL, RobotPhase.SPEAKING to ButtonAction.STOP_AND_LISTEN)
        for ((phase, action) in expect) {
            val r = ButtonGestures.onInput(s, ButtonInput.Entry(EntryKind.BVRA, 10_000), phase, holdsSession = false)
            assertEquals(phase.name, Gesture.ENTRY, r.gesture)
            assertEquals(phase.name, action, r.action)
        }
    }

    @Test fun twoStartsForOnePressAreOne() {
        val r1 = ButtonGestures.onInput(GestureState(), ButtonInput.Entry(EntryKind.BVRA, 10_000), RobotPhase.IDLE, false)
        assertEquals(ButtonAction.LISTEN, r1.action)
        val r2 = ButtonGestures.onInput(r1.state, ButtonInput.Entry(EntryKind.HOOK, 10_400), RobotPhase.LISTENING, false)
        assertEquals(ButtonAction.IGNORE, r2.action)
        assertEquals(null, r2.gesture)
        // After the debounce, a press stops listening.
        val r3 = ButtonGestures.onInput(r2.state, ButtonInput.Entry(EntryKind.BVRA, 11_000), RobotPhase.LISTENING, false)
        assertEquals(ButtonAction.STOP_LISTENING, r3.action)
    }

    @Test fun keysAreTheMusicAppsWhenRobotIsQuiet() {
        for (k in MediaKey.entries) {
            assertEquals(ButtonAction.PASS, ButtonGestures.onInput(GestureState(), key(k, true, 0), RobotPhase.IDLE, holdsSession = true).action)
            assertEquals(ButtonAction.PASS, ButtonGestures.onInput(GestureState(), key(k, true, 0), RobotPhase.SPEAKING, holdsSession = false).action)
        }
    }

    @Test fun tapStopsDoubleTapRepeats() {
        val stop = tap(GestureState(), 1_000, RobotPhase.SPEAKING)
        assertEquals(Gesture.SINGLE, stop.gesture)
        assertEquals(ButtonAction.STOP_SPEAKING, stop.action)
        // The second tap comes while Robot is quiet but still holds the session for the double tap.
        val again = tap(stop.state, 1_300, RobotPhase.IDLE)
        assertEquals(Gesture.DOUBLE, again.gesture)
        assertEquals(ButtonAction.REPEAT, again.action)
        // Too late for a double tap: when quiet, it's the music app's.
        assertEquals(ButtonAction.PASS, ButtonGestures.onInput(stop.state, key(MediaKey.PLAY_PAUSE, true, 1_600), RobotPhase.IDLE, true).action)
        // A tap while listening stops listening; while thinking, cancels.
        assertEquals(ButtonAction.STOP_LISTENING, tap(GestureState(), 0, RobotPhase.LISTENING).action)
        assertEquals(ButtonAction.STOP_LISTENING, tap(GestureState(), 0, RobotPhase.FOLLOW_UP).action)
        assertEquals(ButtonAction.CANCEL, tap(GestureState(), 0, RobotPhase.THINKING).action)
        assertEquals(ButtonAction.IGNORE, tap(GestureState(), 0, RobotPhase.CARD).action)
        // Next and previous while she reads (the brief): next item, repeat.
        assertEquals(ButtonAction.NEXT, tap(GestureState(), 0, RobotPhase.SPEAKING, k = MediaKey.NEXT).action)
        assertEquals(ButtonAction.REPEAT, tap(GestureState(), 0, RobotPhase.SPEAKING, k = MediaKey.PREVIOUS).action)
    }

    @Test fun aLongPressOnlyFromAWiredKey() {
        // Bluetooth (AVRCP): however long between down and up, it's a tap.
        val bt = tap(GestureState(), 0, RobotPhase.SPEAKING, KeySource.BLUETOOTH, hold = 2_000)
        assertEquals(Gesture.SINGLE, bt.gesture)
        assertEquals(ButtonAction.STOP_SPEAKING, bt.action)
        // ... and a long-press flag from Bluetooth is not believed.
        assertEquals(ButtonAction.IGNORE, ButtonGestures.onInput(GestureState(), key(MediaKey.PLAY_PAUSE, true, 0, KeySource.BLUETOOTH, long = true), RobotPhase.SPEAKING, true).action)
        // Wired: held long enough, or flagged, it listens.
        val wired = tap(GestureState(), 0, RobotPhase.SPEAKING, KeySource.WIRED, k = MediaKey.HEADSETHOOK, hold = ButtonGestures.LONG_PRESS_MS)
        assertEquals(Gesture.LONG, wired.gesture)
        assertEquals(ButtonAction.STOP_AND_LISTEN, wired.action)
        val flagged = ButtonGestures.onInput(GestureState(), key(MediaKey.HEADSETHOOK, true, 0, KeySource.WIRED, long = true), RobotPhase.FOLLOW_UP, true)
        assertEquals(Gesture.LONG, flagged.gesture)
        assertEquals(ButtonAction.LISTEN, flagged.action)
    }
}
