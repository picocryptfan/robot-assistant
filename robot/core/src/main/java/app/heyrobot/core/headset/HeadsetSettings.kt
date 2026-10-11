package app.heyrobot.core.headset

import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.enumOr
import org.json.JSONObject

// Pure (org.json). Settings › Headset (docs/plans/routines-headset-car-design.md §3.4), every row a switch with the
// design's default. They become RobotSettings fields under the next settings schema when the Headset screen is wired
// (Settings.kt is not changed on this branch): [toJson] and [fromJson] are the fragment as it would sit there (under
// "headset"), an absent field reading as its default, so an older file reads as "headset button on, everything else as
// the design says". "Personal answers only in private headphones" is a Safety & privacy row (output HandsFreeSafety).

/**
 * Where Robot listens in a headset session (§3.4 "Listen with", §3.1 "Microphone and output routing"). The trade-off:
 * - [HEADSET_MIC] (default): the headset's own microphone, over SCO (Bluetooth classic) or LE Audio. Music pauses or
 *   drops to call quality while Robot listens and answers; the route takes 0.3 to 1.5 s (EST) and SCO's narrow band
 *   (CVSD 8 kHz, mSBC 16 kHz, LC3-SWB 32 kHz, which one is UNVERIFIED: H0.5) may raise the word error rate. Works with the
 *   phone in a pocket or a bag.
 * - [PHONE_MIC]: the phone's microphone, Robot heard in the headset over A2DP or LE: no route switch (faster), music keeps
 *   its quality and the headset's battery lasts longer, but the phone must be near you.
 */
enum class ListenWith(val title: String) {
    HEADSET_MIC("Headset microphone (if it has one)"),
    PHONE_MIC("Phone microphone"),
}

/** "Announce from" (0.7): people you choose, or everyone. */
enum class AnnounceFrom(val title: String) {
    CHOSEN_PEOPLE("People you choose"),
    EVERYONE("Everyone"),
}

/** "Read the text" (0.7): ask first ("Message from Marie. Read it?"), or read it at once. */
enum class AnnounceRead(val title: String) {
    ASK_FIRST("Ask first"),
    ALWAYS("Always"),
}

data class HeadsetSettings(
    /** "Headset button opens Robot": off disables HeadsetEntryActivity, so other apps get the three intents. */
    val buttonOpensRobot: Boolean = true,
    val listenWith: ListenWith = ListenWith.HEADSET_MIC,
    /** "Fall back to the phone mic" when the headset's route doesn't come up within [RoutePlanner.ROUTE_TIMEOUT_MS]. */
    val fallbackToPhoneMic: Boolean = true,
    /** "Speak replies in the headset". Off: replies show only (the panel's text). */
    val speakInHeadset: Boolean = true,
    /** "Keep listening for a follow-up", 0 to 15 s. */
    val followUpSeconds: Int = 6,
    /** "Stop when the headset disconnects": nothing more is spoken once the headset is gone. */
    val stopOnDisconnect: Boolean = true,
    /**
     * "Hey robot through the headset microphone" (off): keeps a communication route open whenever an enabled headset is
     * connected (worse music, headset battery). The only time Robot holds the headset microphone outside a question.
     */
    val wakeThroughHeadset: Boolean = false,
    /** "Announce new messages in the headset" (0.7, off). */
    val announceMessages: Boolean = false,
    val announceFrom: AnnounceFrom = AnnounceFrom.CHOSEN_PEOPLE,
    val announceRead: AnnounceRead = AnnounceRead.ASK_FIRST,
    /** "Announce while locked" (0.7, off, §3.7 rule 5). */
    val announceLocked: Boolean = false,
    /**
     * "Answer the headset's voice button properly": asks for Nearby devices (BLUETOOTH_CONNECT) and then tells the headset
     * Robot is listening within HeadsetService's 5 s. Off until you grant it; hidden if phone check H0.2 finds no headset
     * that misbehaves without it.
     */
    val answerVoiceButton: Boolean = false,
    /**
     * "Headset buttons control Robot while she talks" (off; not in the design, which takes no media keys, §3.7 rule 3):
     * while Robot speaks or listens she holds a media session, so a tap stops her and a double tap repeats; it is released
     * the moment she is quiet, so play and pause go back to your music app.
     */
    val buttonsWhileTalking: Boolean = false,
) {
    /** What OutputGuard reads from here, with Safety & privacy's row and car mode's read-aloud choice. */
    fun guard(safety: HandsFreeSafety, carReadAloud: ReadAloud = ReadAloud.ASK): GuardSettings =
        GuardSettings(safety.personalOnlyPrivate, stopOnDisconnect, announceLocked, carReadAloud)

    val followUpMs: Long get() = clampFollowUp(followUpSeconds) * 1_000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("buttonOpensRobot", buttonOpensRobot)
        put("listenWith", listenWith.name)
        put("fallbackToPhoneMic", fallbackToPhoneMic)
        put("speakInHeadset", speakInHeadset)
        put("followUpSeconds", followUpSeconds)
        put("stopOnDisconnect", stopOnDisconnect)
        put("wakeThroughHeadset", wakeThroughHeadset)
        put("announceMessages", announceMessages)
        put("announceFrom", announceFrom.name)
        put("announceRead", announceRead.name)
        put("announceLocked", announceLocked)
        put("answerVoiceButton", answerVoiceButton)
        put("buttonsWhileTalking", buttonsWhileTalking)
    }

    companion object {
        const val MAX_FOLLOW_UP = 15

        fun clampFollowUp(s: Int): Int = s.coerceIn(0, MAX_FOLLOW_UP)

        fun fromJson(o: JSONObject?): HeadsetSettings {
            val d = HeadsetSettings()
            if (o == null) return d
            return HeadsetSettings(
                buttonOpensRobot = o.optBoolean("buttonOpensRobot", d.buttonOpensRobot),
                listenWith = o.enumOr("listenWith", d.listenWith),
                fallbackToPhoneMic = o.optBoolean("fallbackToPhoneMic", d.fallbackToPhoneMic),
                speakInHeadset = o.optBoolean("speakInHeadset", d.speakInHeadset),
                followUpSeconds = clampFollowUp(o.optInt("followUpSeconds", d.followUpSeconds)),
                stopOnDisconnect = o.optBoolean("stopOnDisconnect", d.stopOnDisconnect),
                wakeThroughHeadset = o.optBoolean("wakeThroughHeadset", d.wakeThroughHeadset),
                announceMessages = o.optBoolean("announceMessages", d.announceMessages),
                announceFrom = o.enumOr("announceFrom", d.announceFrom),
                announceRead = o.enumOr("announceRead", d.announceRead),
                announceLocked = o.optBoolean("announceLocked", d.announceLocked),
                answerVoiceButton = o.optBoolean("answerVoiceButton", d.answerVoiceButton),
                buttonsWhileTalking = o.optBoolean("buttonsWhileTalking", d.buttonsWhileTalking),
            )
        }
    }
}
