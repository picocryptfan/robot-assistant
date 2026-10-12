package app.heyrobot.assistant.headset

import android.app.Activity
import android.os.Bundle
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.core.RLog

// VOICE PROCESS (0.8). The headset button's trampoline (design §3.6): exported, because Android starts it for a
// Bluetooth headset's or car's voice button (VOICE_COMMAND) and a wired or USB headset's long press or a voice-assist key
// (android.speech.action.WEB_SEARCH, VOICE_SEARCH_HANDS_FREE with EXTRA_SECURE). Translucent, no history, not in Recents,
// shown over the lock screen only to pass the press on: it never shows anything of its own and finishes in onCreate. A
// spoofed start can only open Robot's own session under the same gates (HeadsetEntry.decide); "Headset button opens
// Robot" off disables it, so other apps get the button.

class HeadsetEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            VoiceHub.init(this)
            HandsFreeHub.onButton(this, intent)
        } catch (t: Throwable) {
            RLog.w("headset_entry", t)
        } finally {
            finish()
        }
    }
}
