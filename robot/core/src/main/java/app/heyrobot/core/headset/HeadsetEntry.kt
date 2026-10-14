package app.heyrobot.core.headset

import app.heyrobot.core.LogCode

// Pure. How a headset's button reaches Robot on Android 17 (design §3.1, VERIFIED in AOSP android-17.0.0_r1), and what
// HeadsetEntryActivity does with it (§3.6). The platform never routes a headset button to the assistant itself:
//
// | Button | What Android does | Robot gets |
// |---|---|---|
// | Bluetooth HFP headset's or car's voice button (AT+BVRA=1) | HeadsetSystemInterface starts Intent.ACTION_VOICE_COMMAND, then HeadsetService waits 5 s for BluetoothHeadset.startVoiceRecognition (needs BLUETOOTH_CONNECT) or reports failure to the headset | android.intent.action.VOICE_COMMAND |
// | Wired or USB-C long-press (KEYCODE_HEADSETHOOK, or MEDIA_PLAY_PAUSE on a phone) | MediaSessionService.handleLongPressLocked → startVoiceInput | android.speech.action.WEB_SEARCH (unlocked, screen on) or VOICE_SEARCH_HANDS_FREE with EXTRA_SECURE (locked and secure, or screen off) |
// | KEYCODE_VOICE_ASSIST (some headsets and remotes) | PhoneWindowManager.launchVoiceAssistWithWakeLock | the same two |
// | KEYCODE_ASSIST | SearchManager.launchAssist | the assistant's session directly (not this activity) |
// | Bluetooth AVRCP play/pause held | one KeyEvent down and one up, no repeat, no FLAG_LONG_PRESS (AvrcpTargetService) | nothing: a long press can't be seen |
//
// android.speech.action.WEB_SEARCH (RecognizerIntent) is not android.intent.action.WEB_SEARCH (Intent), which browsers
// handle; Robot declares only the speech one. These are ordinary implicit activity starts: if another app handles one
// too, Android shows a chooser (which apps do on stock GrapheneOS is UNVERIFIED: H0.1). 1.0.1: the activity shows over
// the lock screen, so only those paths and Robot itself may start it (EntryCaller): the caller Android recorded (the
// referrer, when the start doesn't name its own) is the system, Bluetooth or Robot; any other start does nothing.

/** Which button press started the session. Also the voice log's HEADSET_ENTRY a. */
enum class EntryKind(override val id: Int, override val title: String) : LogCode {
    /** A Bluetooth headset's or car's voice button (AT+BVRA): VOICE_COMMAND. */
    BVRA(1, "Bluetooth voice button"),
    /** A wired or USB-C long-press, or a voice-assist key: WEB_SEARCH or VOICE_SEARCH_HANDS_FREE. */
    HOOK(2, "wired or USB long-press, or voice-assist key"),
    /** KEYCODE_ASSIST, seen as an assist session while a headset is connected. */
    ASSIST(3, "assist key"),
}

/** Where the start came from (Activity.getReferrer), for diagnostics only. */
enum class EntryReferrer(override val id: Int, override val title: String) : LogCode {
    BLUETOOTH(1, "Bluetooth"), SYSTEM(2, "system"), OTHER(3, "another app"), NONE(4, "none given");

    companion object {
        /** From the referrer's package (never logged itself). */
        fun of(pkg: String?): EntryReferrer = when {
            pkg.isNullOrBlank() -> NONE
            pkg == "com.android.bluetooth" || pkg == "com.google.android.bluetooth" -> BLUETOOTH
            pkg == "android" || pkg == "com.android.systemui" || pkg == "com.android.server.telecom" -> SYSTEM
            else -> OTHER
        }
    }
}

/**
 * 1.0.1: whether HeadsetEntryActivity's caller may open Robot over the lock screen. [referrer]: the package Android recorded
 * as starting it (Activity.getReferrer, read only when the start names no referrer of its own); [claimed]: the start carries
 * EXTRA_REFERRER or EXTRA_REFERRER_NAME, which any app can set (never trusted, whatever it says); [fromOwnUid]: Android says
 * Robot itself started it (Activity.getLaunchedFromUid).
 */
object EntryCaller {
    fun trusted(referrer: String?, claimed: Boolean, ownPackage: String, fromOwnUid: Boolean): Boolean = when {
        fromOwnUid -> true
        claimed -> false
        !referrer.isNullOrBlank() && referrer == ownPackage -> true
        else -> EntryReferrer.of(referrer) == EntryReferrer.BLUETOOTH || EntryReferrer.of(referrer) == EntryReferrer.SYSTEM
    }
}

sealed class EntryDecision {
    /** Open Robot's session for a headset ([car]: car mode is on, so it is the car's session), [locked] as Android said. */
    data class Show(val kind: EntryKind, val locked: Boolean, val car: Boolean, val ackBvra: Boolean) : EntryDecision()

    /** Robot isn't the running assistant: a toast says so ("Robot isn't the digital assistant"). */
    data object NotAssistant : EntryDecision()

    /** Not for Robot (an unknown action), or "Headset button opens Robot" is off. Nothing happens. */
    data object Ignore : EntryDecision()

    /** 1.0.1: started by another app, not the system's button paths ([EntryCaller]). Nothing happens. */
    data object NotTrusted : EntryDecision()
}

object HeadsetEntry {
    const val VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
    const val WEB_SEARCH = "android.speech.action.WEB_SEARCH"
    const val VOICE_SEARCH_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"
    const val EXTRA_SECURE = "android.speech.extras.EXTRA_SECURE"

    /** Exactly the three actions HeadsetEntryActivity declares (ProcessIsolationTest will check the manifest against this). */
    val ACTIONS: List<String> = listOf(VOICE_COMMAND, WEB_SEARCH, VOICE_SEARCH_HANDS_FREE)

    /** HeadsetService waits this long for BluetoothHeadset.startVoiceRecognition before telling the headset it failed. */
    const val BVRA_ACK_MS = 5_000L

    const val NOT_ASSISTANT = "Robot isn't the digital assistant"

    fun kind(action: String?): EntryKind? = when (action) {
        VOICE_COMMAND -> EntryKind.BVRA
        WEB_SEARCH, VOICE_SEARCH_HANDS_FREE -> EntryKind.HOOK
        else -> null
    }

    /**
     * What the trampoline does with [action] (design §3.9 `HeadsetEntry.decide`). [secure]: EXTRA_SECURE. [locked]: the
     * keyguard is up. [assistantRunning]: RobotVoiceService.instance exists. [buttonOn]: "Headset button opens Robot"
     * (the activity is disabled when off, so this only guards a stale start). [carMode]: car mode is on, so a voice
     * button is the car's. [mayAck]: "Answer the headset's voice button properly" is on and BLUETOOTH_CONNECT is granted:
     * then a BVRA start is acknowledged with startVoiceRecognition within [BVRA_ACK_MS]. [callerTrusted]: [EntryCaller]
     * (1.0.1). Robot never turns the screen on for it, and the activity shows nothing of its own.
     */
    fun decide(action: String?, secure: Boolean, locked: Boolean, assistantRunning: Boolean, buttonOn: Boolean = true,
        carMode: Boolean = false, mayAck: Boolean = false, callerTrusted: Boolean = true): EntryDecision {
        val kind = kind(action) ?: return EntryDecision.Ignore
        if (!callerTrusted) return EntryDecision.NotTrusted
        if (!buttonOn) return EntryDecision.Ignore
        if (!assistantRunning) return EntryDecision.NotAssistant
        // VOICE_SEARCH_HANDS_FREE comes when locked or with the screen off; EXTRA_SECURE says locked and secure.
        val isLocked = locked || secure
        return EntryDecision.Show(kind, isLocked, car = carMode, ackBvra = kind == EntryKind.BVRA && mayAck)
    }
}
