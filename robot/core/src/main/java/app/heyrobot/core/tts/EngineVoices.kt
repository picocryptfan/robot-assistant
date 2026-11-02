// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.tts

import app.heyrobot.core.LogCode
import app.heyrobot.core.VoiceChoice

// Pure. The everyday set, E6 (docs/plans/everyday-set-plan.md §6.3, D16): Robot's voices as Android's text-to-speech
// engine offers them to other apps (app tts/RobotTtsService, ui/tts's three engine activities): Heart and Kitten (English,
// US) and Siwis (French, from France), only once their models are downloaded. Android passes languages and countries as
// ISO 639-2 and ISO 3166 alpha-3 codes ("eng", "USA"); some callers send the two-letter ones, read here too.

/** One of Robot's voices as the engine names it to Android. [tag]: its locale as a BCP 47 tag. */
enum class EngineVoice(val choice: VoiceChoice, val voiceName: String, val lang: String, val country: String, val tag: String) {
    HEART(VoiceChoice.HEART, "en-us-x-robot-heart", "eng", "USA", "en-US"),
    KITTEN(VoiceChoice.KITTEN, "en-us-x-robot-kitten", "eng", "USA", "en-US"),
    SIWIS(VoiceChoice.SIWIS, "fr-fr-x-robot-siwis", "fra", "FRA", "fr-FR"),
    ;

    /** As CHECK_TTS_DATA lists a voice: "eng-USA", "fra-FRA". */
    val dataName: String get() = "$lang-$country"
}

/** What onIsLanguageAvailable answers (RobotTtsService maps it to TextToSpeech's LANG_ codes). */
enum class LangSupport {
    /** The language and the country: English (US) with an English voice, French (France) with Siwis. */
    COUNTRY,
    /** The language only: English for another country; French for Canada, Belgium or Switzerland (Siwis is from France). */
    LANGUAGE,
    /** A language Robot speaks, whose voice isn't downloaded yet (INSTALL_TTS_DATA opens Settings › Voice). */
    MISSING_DATA,
    NOT_SUPPORTED,
}

object EngineVoices {
    /** "eng" or "fra" for English or French in either form, any case; null for any other language. */
    fun lang(code: String?): String? = when (code?.trim()?.lowercase()) {
        "en", "eng" -> "eng"
        "fr", "fra", "fre" -> "fra"
        else -> null
    }

    /** A country in its alpha-3 form where Robot cares ("USA", "FRA"), else as given, upper case ("" for none). */
    fun country(code: String?): String = when (val c = code?.trim()?.uppercase().orEmpty()) {
        "US" -> "USA"
        "FR" -> "FRA"
        "CA" -> "CAN"
        "GB" -> "GBR"
        else -> c
    }

    /** The voices of [lang] ("eng", "fra" or either two-letter form), Robot's own first for English (Kitten if it is Kitten). */
    fun voicesOf(lang: String?, robotVoice: VoiceChoice): List<EngineVoice> = when (lang(lang)) {
        "eng" -> if (robotVoice == VoiceChoice.KITTEN) listOf(EngineVoice.KITTEN, EngineVoice.HEART) else listOf(EngineVoice.HEART, EngineVoice.KITTEN)
        "fra" -> listOf(EngineVoice.SIWIS)
        else -> emptyList()
    }

    fun support(lang: String?, country: String?, usable: (EngineVoice) -> Boolean): LangSupport {
        val voices = voicesOf(lang, VoiceChoice.DEFAULT)
        if (voices.isEmpty()) return LangSupport.NOT_SUPPORTED
        if (voices.none(usable)) return LangSupport.MISSING_DATA
        return if (country(country) == voices.first().country) LangSupport.COUNTRY else LangSupport.LANGUAGE
    }

    fun byName(name: String?): EngineVoice? = EngineVoice.entries.firstOrNull { it.voiceName == name }

    /**
     * Which voice speaks a request: the voice it names when that one is downloaded; else [lang]'s first downloaded voice,
     * Robot's own first ([robotVoice]); a language Robot doesn't speak is read by the English voice (an app that asked for
     * it was already told it isn't supported). Null when no fitting voice is downloaded.
     */
    fun pick(voiceName: String?, lang: String?, robotVoice: VoiceChoice, usable: (EngineVoice) -> Boolean): EngineVoice? {
        byName(voiceName)?.let { if (usable(it)) return it }
        val wanted = lang(lang) ?: "eng"
        return voicesOf(wanted, robotVoice).firstOrNull(usable)
    }

    /** The voice Android uses for [lang] by default (onGetDefaultVoiceNameFor), downloaded or not; null for another language. */
    fun default(lang: String?, robotVoice: VoiceChoice, usable: (EngineVoice) -> Boolean): EngineVoice? {
        val voices = voicesOf(lang, robotVoice)
        return voices.firstOrNull(usable) ?: voices.firstOrNull()
    }

    /** CHECK_TTS_DATA's lists: the voices' "eng-USA" and "fra-FRA" with a downloaded voice, and those without one. */
    fun dataLists(usable: (EngineVoice) -> Boolean): Pair<List<String>, List<String>> {
        val byData = EngineVoice.entries.groupBy { it.dataName }
        val available = byData.filter { (_, v) -> v.any(usable) }.keys.toList()
        return available to (byData.keys - available.toSet()).toList()
    }

    /**
     * A request's speech rate (SynthesisRequest.getSpeechRate: 100 is normal) as the voices' speed, within what Robot's own
     * voice allows (0.5 to 2.0, Settings › Voice): a screen reader set faster than twice normal speaks at twice normal.
     */
    fun speed(rate: Int): Float = (rate / 100f).takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 2.0f) ?: 1.0f
}

/** VOICE_FOR_APP's first number: how another app's request ended. */
enum class VoiceForAppResult(override val id: Int, override val title: String) : LogCode {
    SPOKEN(1, "spoken"),
    STOPPED(2, "stopped by the app"),
    OFF(3, "Robot's voices for other apps are off"),
    NOT_DOWNLOADED(4, "its voice isn't downloaded"),
    OWN_APP(5, "Robot's own request, refused (Robot never speaks through itself)"),
    NOTHING_TO_SAY(6, "nothing sayable after the filter"),
    FAILED(7, "the voice failed"),
}

/** VOICE_FOR_APP's second number: the request's length. */
enum class CharsBucket(override val id: Int, override val title: String, val below: Int) : LogCode {
    UNDER_50(1, "under 50 characters", 50),
    UNDER_200(2, "50 to 199 characters", 200),
    UNDER_1000(3, "200 to 999 characters", 1_000),
    LONGER(4, "1,000 characters or more", Int.MAX_VALUE),
    ;

    companion object {
        fun of(chars: Int): CharsBucket = entries.first { chars < it.below }
    }
}

/** VOICE_FOR_APP_LOAD's first number: where the voice loaded (D17: an isolated process waits for a sherpa-onnx that can). */
enum class VoiceForAppProcess(override val id: Int, override val title: String) : LogCode {
    VOICE(1, ":voice, beside Robot's own voice"),
}

/** VOICE_FOR_APP_LOAD's second number: how long the voice took to load. */
enum class LoadBucket(override val id: Int, override val title: String, val belowMs: Long) : LogCode {
    UNDER_1S(1, "under 1 s", 1_000),
    UNDER_3S(2, "1 to 3 s", 3_000),
    UNDER_10S(3, "3 to 10 s", 10_000),
    LONGER(4, "10 s or more", Long.MAX_VALUE),
    ;

    companion object {
        fun of(ms: Long): LoadBucket = entries.first { ms < it.belowMs }
    }
}

/**
 * How often another app's requests reach the voice log: a screen reader asks many times a minute, and the log keeps a
 * few thousand events for everything Robot does. Each result is written at most once per [EVERY_MS], with how many requests
 * ended that way since the last line (VOICE_FOR_APP's third number). Not thread-safe: RobotTtsService's synthesis thread only.
 */
class VoiceForAppPace {
    private val last = HashMap<VoiceForAppResult, Long>()
    private val counts = HashMap<VoiceForAppResult, Int>()

    /** The count to write now for [r] at [nowMs], or null to only count it. */
    fun note(r: VoiceForAppResult, nowMs: Long): Int? {
        val n = (counts[r] ?: 0) + 1
        val at = last[r]
        if (at != null && nowMs - at < EVERY_MS) {
            counts[r] = n
            return null
        }
        last[r] = nowMs
        counts[r] = 0
        return n.coerceAtMost(MAX_COUNT)
    }

    companion object {
        const val EVERY_MS = 60_000L
        const val MAX_COUNT = 9_999
    }
}
