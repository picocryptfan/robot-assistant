// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.LogCode
import org.json.JSONObject
import java.util.Locale

// Pure. What headset mode and car mode share: the language Robot speaks
// in, the kind of session a sentence belongs to, and the Safety & privacy rows of §1.5 that are theirs.
//
// Voice log ids 200–219 are reserved for headset and car mode (the design proposed 150–169, which routines (140–159) and
// web (160–169) took; Termux has 170–179, memory 180–189, messages 190–199). Since 0.8 they are VoiceEvent entries,
// codes and numbers only (never a device name, address, place, contact or message text: a device is its
// RememberedDevice.number):
// 200 HEADSET_ENTRY (a = headset EntryKind, b = 1 if locked or EXTRA_SECURE, c = 1 if car mode was on),
// 201 HEADSET_ROUTE (a = headset MicRoute, b = ms until the route was up, c = codec, 0 while unknown),
// 202 HEADSET_ROUTE_FAILED (a = headset RouteFail, b = 1 if it fell back to the phone microphone),
// 203 OUTPUT_HELD (a = [HoldWhy], b = [SessionKind] id, c = device number, 0 for none),
// 204 BVRA_ACK (a = 1 if the headset was told Robot listens, b = ms after the button),
// 205 DEVICE_PRESENCE (a = 1 connected, 0 disconnected, b = device number, c = role: 1 headset, 2 car),
// 206 PRIVATE_ASKED (a = device number, b = 1 yes, 0 no, -1 no answer),
// 207 BUTTON (a = headset Gesture, b = headset ButtonAction, c = headset RobotPhase),
// 208 ANNOUNCE (a = headset AnnounceCode, b = 1 if the text was read),
// 209 SPOKEN_CARD (a = [CardVoiceCode], b = attempts, c = [AnswerCode]),
// 210 CAR_MODE (a = 1 on, 0 off, b = car CarWhy), 211 CAR_TRUSTED (a = 1 on, 0 off, b = car TrustEnd),
// 212 retired (1.0's test builds' CAR_PARKED), 213 CAR_CALL (a = car CallWay, b = 1 for an emergency number),
// 214 CAR_REPLY (a = 1 handed over, 0 not, b = car ReplyEnd), 215 CAR_DND (a = 1 set, 0 cleared, b = 1 if Android took it),
// 216 CAR_NAV (a = car NavKind), 217 CAR_MUSIC (a = MusicAction ordinal + 1), 218 HEADSET_END (a = headset EndWhy, b = seconds,
// c = [SessionKind] id), 219 spare.

/** The language a hands-free line is spoken in: English, or Québec French (tu, as Robot's French elsewhere). */
enum class SpokenLang {
    EN, FR;

    companion object {
        /** From a language tag ("fr-CA", "fr", "en-US"); anything not French is English. */
        fun of(tag: String?): SpokenLang = if (tag?.trim()?.lowercase(Locale.ROOT)?.startsWith("fr") == true) FR else EN
    }
}

/** A line in both languages. */
data class Line(val en: String, val fr: String) {
    fun get(lang: SpokenLang): String = if (lang == SpokenLang.FR) fr else en
}

/** Who a sentence is spoken for: the panel on the phone, a headset session, or car mode. Its id is the voice log's. */
enum class SessionKind(override val id: Int, override val title: String) : LogCode {
    PHONE(1, "phone"), HEADSET(2, "headset"), CAR(3, "car"),
}

/**
 * Settings › Safety & privacy rows for headset and car (design §1.5), defaults on. They become RobotSettings fields under
 * the next settings schema when this is wired (Settings.kt is not changed on this branch); [toJson] and [fromJson] are
 * the fields as they would sit there, an absent one reading as its default.
 */
data class HandsFreeSafety(
    /** "Personal answers only in private headphones" (OutputGuard, §1.3). */
    val personalOnlyPrivate: Boolean = true,
    /** "Send a reply after a spoken yes (car, headset)" (0.7): the read-back is always spoken first. Off: every reply waits. */
    val replyAfterSpokenYes: Boolean = true,
    /** "Place a call after a spoken yes": only with the Phone permission granted; otherwise the dialer opens. */
    val callAfterSpokenYes: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("personalOnlyPrivate", personalOnlyPrivate).put("replyAfterSpokenYes", replyAfterSpokenYes)
        .put("callAfterSpokenYes", callAfterSpokenYes)

    companion object {
        fun fromJson(o: JSONObject?): HandsFreeSafety {
            val d = HandsFreeSafety()
            if (o == null) return d
            return HandsFreeSafety(
                personalOnlyPrivate = o.optBoolean("personalOnlyPrivate", d.personalOnlyPrivate),
                replyAfterSpokenYes = o.optBoolean("replyAfterSpokenYes", d.replyAfterSpokenYes),
                callAfterSpokenYes = o.optBoolean("callAfterSpokenYes", d.callAfterSpokenYes),
            )
        }
    }
}

/** A JSON helper for the settings fragments: an enum by its name, else [default]. */
inline fun <reified T : Enum<T>> JSONObject.enumOr(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == optString(key) } ?: default

/**
 * The one-time line after updating to 0.8, on Home and Settings (the 0.4.0 pattern; headset.json keeps that it was
 * closed): what is new, that nothing runs until you turn it on, and that the headset's button opens Robot from now on (it
 * only answers a press, so it needs no switch first).
 */
object HandsFreeNotice {
    const val TEXT = "New: routines, the morning brief, the headset button and car mode. Nothing runs until you turn it on; " +
        "your headset's voice button opens Robot from now on."

    /** Shown to someone who had Robot before 0.8 ([hadSettings]: settings.json was there) until they close it. */
    fun shows(hadSettings: Boolean, seen: Boolean): Boolean = hadSettings && !seen
}
