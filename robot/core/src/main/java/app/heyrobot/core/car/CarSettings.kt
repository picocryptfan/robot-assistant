// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.headset.ListenWith
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.enumOr
import org.json.JSONObject

// Pure (org.json). Settings › Car mode, every row with its
// default. Your cars are DeviceMemory's cars (devices.json): car mode is off until one is added. They become RobotSettings
// fields under the next settings schema when the Car mode screen is wired (Settings.kt is not changed on this branch):
// [toJson] and [fromJson] are the fragment as it would sit there (under "car"), an absent field reading as its default.
//
// 1.0 (the user, 13 Oct 2026: "I do not want any driving related restrictions"): car mode never holds, parks or refuses
// anything because you are driving. The 0.8 row "New texts while driving" ("newTexts") and the 1.0 test builds' "Hold
// screen actions until I park" ("holdWhileDriving") are gone; a file that still has them loads, and they are ignored.

/** "Use car mode without unlocking" (§4.6). */
enum class CarLockUse(private val msg: Msg) {
    /** The default: after one unlock while your car is connected, car functions work locked until it disconnects (or 4 h). */
    AFTER_ONE_UNLOCK(HandsFreeStrings.lockAfterOne),
    ALWAYS(HandsFreeStrings.lockAlways),
    NEVER(HandsFreeStrings.never),
    ;

    /** 1.1: Settings' words without a language yet; [title] is their English. */
    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** "Calls" (§4.4): the dialer, where you tap Call, or a direct call after a spoken yes (needs the Phone permission). */
enum class CallSetting(private val msg: Msg) {
    OPEN_DIALER(HandsFreeStrings.openDialer),
    CALL_AFTER_YES(HandsFreeStrings.callAfterYes),
    ;

    /** 1.1: Settings' words without a language yet; [title] is their English. */
    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** "Announce new messages" (0.7). */
enum class CarAnnounce(private val msg: Msg) {
    OFF(HandsFreeStrings.announceOff),
    SENDER_THEN_ASK(HandsFreeStrings.senderThenAsk),
    READ_WHOLE(HandsFreeStrings.readWhole),
    ;

    /** 1.1: Settings' words without a language yet; [title] is their English. */
    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** "Announce from" (0.7). */
enum class CarAnnounceFrom(private val msg: Msg) {
    DND_ALLOWS(HandsFreeStrings.fromDnd),
    EVERYONE(HandsFreeStrings.everyone),
    CHOSEN_PEOPLE(HandsFreeStrings.onlyChosen),
    ;

    /** 1.1: Settings' words without a language yet; [title] is their English. */
    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** A saved place for "navigate home" and "take me to work": its label as you typed it and where it is. */
data class SavedPlace(val label: String, val lat: Double, val lon: Double) {
    override fun toString(): String = "SavedPlace(…)" // l10n: not text (toString, for the logs)

    companion object {
        const val MAX_LABEL = 80

        /** A place, or null when the label is empty or the coordinates aren't on the globe. */
        fun of(label: String, lat: Double, lon: Double): SavedPlace? {
            val l = label.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().replace(Regex("\\s+"), " ").take(MAX_LABEL)
            if (l.isEmpty() || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return SavedPlace(l, lat, lon)
        }
    }
}

data class CarSettings(
    /** "Start car mode when my car connects" (on once a car is added). */
    val startOnConnect: Boolean = true,
    /** "End car mode": when the car disconnects, after this grace (a car restarting its radio isn't the end). */
    val endGraceSeconds: Int = 30,
    /** "Speak through the car as a call" (SCO: cars mute the radio for it; A2DP plays only if the car's source is Bluetooth). */
    val speakAsCall: Boolean = true,
    /** "After “hey robot” in the car, listen with": the phone microphone (the car's adds about 1 s, EST). */
    val wakeListenWith: ListenWith = ListenWith.PHONE_MIC,
    val lockUse: CarLockUse = CarLockUse.AFTER_ONE_UNLOCK,
    /** "Trusted session lasts": until the car disconnects, at most this many hours. */
    val trustedHours: Int = 4,
    val calls: CallSetting = CallSetting.OPEN_DIALER,
    val announce: CarAnnounce = CarAnnounce.SENDER_THEN_ASK,
    val announceFrom: CarAnnounceFrom = CarAnnounceFrom.DND_ALLOWS,
    /** "Replies by voice" (0.7): always read back, then "send". */
    val voiceReplies: Boolean = true,
    /** "Read message text aloud when others may hear": the car is never private (§1.3). */
    val readAloud: ReadAloud = ReadAloud.ASK,
    /** "Do Not Disturb while driving": Robot's own "Driving (Robot)" rule (needs Do Not Disturb access). */
    val dndWhileDriving: Boolean = false,
    /** "Use Android's car mode" (UiModeManager.enableCarMode): system-wide, so off. */
    val androidCarMode: Boolean = false,
    val keepScreenOn: Boolean = false,
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    /** "Drive brief when the car connects": the preset routine "what's next" plus the weather (DriveBrief). */
    val driveBrief: Boolean = false,
) {
    val endGraceMs: Long get() = clampGrace(endGraceSeconds) * 1_000L
    val trustedMs: Long get() = clampHours(trustedHours) * 3_600_000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("startOnConnect", startOnConnect)
        put("endGraceSeconds", endGraceSeconds)
        put("speakAsCall", speakAsCall)
        put("wakeListenWith", wakeListenWith.name)
        put("lockUse", lockUse.name)
        put("trustedHours", trustedHours)
        put("calls", calls.name)
        put("announce", announce.name)
        put("announceFrom", announceFrom.name)
        put("voiceReplies", voiceReplies)
        put("readAloud", readAloud.name)
        put("dndWhileDriving", dndWhileDriving)
        put("androidCarMode", androidCarMode)
        put("keepScreenOn", keepScreenOn)
        home?.let { put("home", place(it)) }
        work?.let { put("work", place(it)) }
        put("driveBrief", driveBrief)
    }

    companion object {
        val GRACE_CHOICES = listOf(0, 10, 30, 60, 120)
        val HOUR_CHOICES = listOf(1, 2, 4, 8)

        fun clampGrace(s: Int): Int = s.coerceIn(0, 300)
        fun clampHours(h: Int): Int = h.coerceIn(1, 12)

        /** Car mode can run only once a car is remembered and enabled ("Car mode is off until one is added"). */
        fun hasCar(memory: DeviceMemory): Boolean = memory.cars.any { it.enabled }

        private fun place(p: SavedPlace) = JSONObject().put("label", p.label).put("lat", p.lat).put("lon", p.lon)

        private fun place(o: JSONObject?): SavedPlace? = o?.let { SavedPlace.of(it.optString("label"), it.optDouble("lat"), it.optDouble("lon")) }

        fun fromJson(o: JSONObject?): CarSettings {
            val d = CarSettings()
            if (o == null) return d
            return CarSettings(
                startOnConnect = o.optBoolean("startOnConnect", d.startOnConnect),
                endGraceSeconds = clampGrace(o.optInt("endGraceSeconds", d.endGraceSeconds)),
                speakAsCall = o.optBoolean("speakAsCall", d.speakAsCall),
                wakeListenWith = o.enumOr("wakeListenWith", d.wakeListenWith),
                lockUse = o.enumOr("lockUse", d.lockUse),
                trustedHours = clampHours(o.optInt("trustedHours", d.trustedHours)),
                calls = o.enumOr("calls", d.calls),
                announce = o.enumOr("announce", d.announce),
                announceFrom = o.enumOr("announceFrom", d.announceFrom),
                voiceReplies = o.optBoolean("voiceReplies", d.voiceReplies),
                readAloud = o.enumOr("readAloud", d.readAloud),
                dndWhileDriving = o.optBoolean("dndWhileDriving", d.dndWhileDriving),
                androidCarMode = o.optBoolean("androidCarMode", d.androidCarMode),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                home = place(o.optJSONObject("home")),
                work = place(o.optJSONObject("work")),
                driveBrief = o.optBoolean("driveBrief", d.driveBrief),
            )
        }
    }
}
