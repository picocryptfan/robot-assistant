// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SettingsStrings

// Pure. Hearing the user while Robot speaks (0.2.3): the microphone mode while Robot talks, how her voice is played,
// the volume key that stops her, and the barge-in test's result (written by :voice, shown by Settings › Voice).

/**
 * Settings › Voice › "Hear me while Robot talks". 1.1: [titleText] and [detailText] are the words without a language yet
 * (Settings shows them with `str()`).
 */
enum class BargeIn(private val titleMsg: Msg, private val detailMsg: Msg) {
    ECHO_CANCEL(SettingsStrings.bargeEcho, SettingsStrings.bargeEchoDetail),
    OFF(SettingsStrings.bargeOff, SettingsStrings.bargeOffDetail),
    ALWAYS(SettingsStrings.bargeAlways, SettingsStrings.bargeAlwaysDetail);

    val titleText: Phrase get() = titleMsg()
    val detailText: Phrase get() = detailMsg()
}

/** How Robot's voice is played (Settings › Voice). 1.1: [titleText] and [detailText] without a language yet. */
enum class Playback(private val titleMsg: Msg, private val detailMsg: Msg) {
    ASSISTANT(SettingsStrings.playbackAssistant, SettingsStrings.playbackAssistantDetail),
    CALL(SettingsStrings.playbackCall, SettingsStrings.playbackCallDetail);

    val titleText: Phrase get() = titleMsg()
    val detailText: Phrase get() = detailMsg()
}

/** Which volume key stops Robot talking while the panel shows (it then doesn't change the volume). 1.1: [titleText]. */
enum class StopKey(private val titleMsg: Msg) {
    VOLUME_DOWN(SettingsStrings.stopVolumeDown),
    EITHER(SettingsStrings.stopEither),
    OFF(SettingsStrings.stopOff);

    val titleText: Phrase get() = titleMsg()
}

/** The pure part of "a volume key stops Robot talking" (the session handles the key events). */
object VolumeKeyStop {
    /** KeyEvent.KEYCODE_VOLUME_UP and KEYCODE_VOLUME_DOWN. */
    const val KEY_VOLUME_UP = 24
    const val KEY_VOLUME_DOWN = 25

    fun matches(keyCode: Int, setting: StopKey): Boolean = when (setting) {
        StopKey.VOLUME_DOWN -> keyCode == KEY_VOLUME_DOWN
        StopKey.EITHER -> keyCode == KEY_VOLUME_DOWN || keyCode == KEY_VOLUME_UP
        StopKey.OFF -> false
    }

    /**
     * Does pressing [keyCode] now stop Robot's voice (and leave the volume alone)? Only while she speaks and the panel
     * is on screen: otherwise (screen off, the panel closed, the lock screen without the panel) the key is the
     * system's, and it changes the volume as always.
     */
    fun stops(keyCode: Int, setting: StopKey, speaking: Boolean, panelShown: Boolean, interactive: Boolean): Boolean =
        speaking && panelShown && interactive && matches(keyCode, setting)
}

/**
 * The barge-in test's runs (0.3.1): stop at the first keyword heard, count every keyword while Robot reads on, nobody
 * speaks, or nobody speaks while she reads [BargeTestLogic.TRICKY_PARAGRAPH] (words like "stop").
 */
enum class BargeRun { FIRST, COUNT, SILENT, TRICKY }

/** One keyword heard during the barge-in test: [phrase] id (0: "stop"), [atMs] after Robot's voice started (-1: before), what came of it. */
data class BargeHit(val phrase: Int, val atMs: Long, val kind: BargeHitKind)

/**
 * Settings › Voice › "Barge-in test": Robot reads [PARAGRAPH] (about 20 s, no word like "stop", no wake phrase) and the
 * user says "stop" or a wake phrase at any point; or a silent run counts false detections. The :voice process writes
 * the result to voice_barge_test.json (VoiceFiles.BARGE); the main app shows [BargeTestLogic.lines]. Numbers and fixed
 * texts only. Since 0.3.1 every keyword heard is kept ([hits], phrase ids only), and a count run ([count]) lets Robot
 * read on, so you can say "stop" and the wake phrases several times and see how many got through.
 */
data class BargeTestResult(
    val state: BenchState,
    val silent: Boolean,
    val started: Long,
    val mode: BargeIn,
    val playback: Playback,
    /** MicKind and AecState ids while Robot spoke (0: unknown). */
    val mic: Int = 0,
    val aec: Int = 0,
    /** Normal run: what was heard (HEARD_*). */
    val heard: Int = HEARD_NOTHING,
    /** When it was heard, ms after Robot's voice started (-1: not heard). */
    val atMs: Long = -1,
    /** From hearing it to silence, ms (-1: not heard). */
    val stopMs: Long = -1,
    /** Silent run: detections while nobody spoke. */
    val falseStops: Int = 0,
    val falseWakes: Int = 0,
    /** Keywords set aside by EchoGuard (Robot saying a word like it). */
    val suppressed: Int = 0,
    /** How long Robot's voice played, ms. */
    val spokeMs: Long = 0,
    /** RUNNING: what happens now; FAILED: why (fixed texts from the code). */
    val note: String = "",
    /** A count run: Robot reads on after a keyword, and [counted] keywords were heard. */
    val count: Boolean = false,
    val counted: Int = 0,
    /** Every keyword heard, in order (at most [MAX_HITS]). */
    val hits: List<BargeHit> = emptyList(),
    /** Robot spoke with the phone's voice (its EchoGuard and its echo differ from Kokoro's). */
    val phoneVoice: Boolean = false,
    /** A silent run of [BargeTestLogic.TRICKY_PARAGRAPH]: what fires is Robot's own words. */
    val tricky: Boolean = false,
    /**
     * Settings › Voice › "Interrupt sensitivity" as the voice process's listener had it (0.4.6): the runs at Low, Medium and
     * High can be told apart, and a level that never reached the listener shows.
     */
    val interrupt: InterruptSensitivity = InterruptSensitivity.MEDIUM,
) {
    val run: BargeRun get() = when {
        silent && tricky -> BargeRun.TRICKY
        silent -> BargeRun.SILENT
        count -> BargeRun.COUNT
        else -> BargeRun.FIRST
    }

    /** With [h] added (the first [MAX_HITS] are kept). */
    fun plus(h: BargeHit): BargeTestResult = if (hits.size >= MAX_HITS) this else copy(hits = hits + h)

    /**
     * 1.1 (WP7): why it FAILED, without a language (PhraseWire in the file), so Settings says it in the menus' language of
     * the day; [note] keeps its English. Not part of equality; a copy() drops it ([withNote] after the last copy).
     */
    var noteText: app.heyrobot.core.i18n.Phrase? = null
        private set

    /** The same with [p] as its note. */
    fun withNote(p: app.heyrobot.core.i18n.Phrase): BargeTestResult = copy(note = p.en()).also { it.noteText = p }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("state", state.name); put("silent", silent); put("started", started); put("mode", mode.name); put("playback", playback.name)
        put("mic", mic); put("aec", aec); put("heard", heard); put("atMs", atMs); put("stopMs", stopMs)
        put("falseStops", falseStops); put("falseWakes", falseWakes); put("suppressed", suppressed); put("spokeMs", spokeMs); put("note", note)
        put("count", count); put("counted", counted); put("phoneVoice", phoneVoice); put("tricky", tricky); put("interrupt", interrupt.name)
        put("hits", org.json.JSONArray().apply { for (h in hits) put(org.json.JSONArray().put(h.phrase).put(h.atMs).put(h.kind.id)) })
        noteText?.let { put("notePhrase", app.heyrobot.core.i18n.PhraseWire.toJson(it)) }
    }

    companion object {
        const val HEARD_NOTHING = 0
        const val HEARD_STOP = 1
        const val HEARD_WAKE = 2
        const val MAX_HITS = 40

        fun fromJson(text: String?): BargeTestResult? = try {
            val o = org.json.JSONObject(text ?: return null)
            val hits = o.optJSONArray("hits")?.let { a ->
                (0 until minOf(a.length(), MAX_HITS)).mapNotNull { i ->
                    val h = a.optJSONArray(i) ?: return@mapNotNull null
                    val kind = BargeHitKind.entries.firstOrNull { it.id == h.optInt(2) } ?: return@mapNotNull null
                    BargeHit(h.optInt(0), h.optLong(1, -1), kind)
                }
            } ?: emptyList()
            BargeTestResult(
                state = enumOr(o.optString("state"), BenchState.FAILED), silent = o.optBoolean("silent"), started = o.optLong("started"),
                mode = enumOr(o.optString("mode"), BargeIn.ECHO_CANCEL), playback = enumOr(o.optString("playback"), Playback.ASSISTANT),
                mic = o.optInt("mic"), aec = o.optInt("aec"), heard = o.optInt("heard"), atMs = o.optLong("atMs", -1), stopMs = o.optLong("stopMs", -1),
                falseStops = o.optInt("falseStops"), falseWakes = o.optInt("falseWakes"), suppressed = o.optInt("suppressed"),
                spokeMs = o.optLong("spokeMs"), note = o.optString("note"),
                count = o.optBoolean("count"), counted = o.optInt("counted"), hits = hits, phoneVoice = o.optBoolean("phoneVoice"),
                tricky = o.optBoolean("tricky"),
                // A result from before 0.4.6 ran at Medium, the only level a tap could save.
                interrupt = enumOr(o.optString("interrupt"), InterruptSensitivity.MEDIUM),
            ).also { r -> app.heyrobot.core.i18n.PhraseWire.fromJson(o.optJSONObject("notePhrase"))?.let { r.noteText = it } }
        } catch (_: Exception) { null }
    }
}

object BargeTestLogic {
    /** About 20 seconds read aloud: no "stop", "step", "top", "shop" or the like, and no wake phrase. */
    const val PARAGRAPH = "The morning light moved slowly across the quiet valley. A farmer walked beside the river with his old brown dog, " +
        "counting the geese as they landed on the water. Later, clouds gathered over the hills, and a soft rain began to fall " +
        "on the fields and gardens. By evening the air was cool and clear again, and lights came on in the windows."

    /**
     * About 20 seconds of words like "stop" (0.3.1): "step", "steep", "stoop", "stamp", "stomp", "stump", "stupid",
     * "strip", and "stop" itself. Read in a silent run, it shows how often Robot's own words fire "stop" and how many of
     * those EchoGuard sets aside; any it lets through would stop her in real use.
     */
    const val TRICKY_PARAGRAPH = "Take one step at a time on the steep trail. Stoop under the branch, then stamp and stomp the snow off your " +
        "boots by the old stump. It would be stupid to strip off your coat at the bus stop. The stopwatch ran nonstop as footsteps crossed " +
        "the doorstep. Step by step, we stopped."

    /** Longer than the paragraph takes at the slowest speed, so a test always ends. */
    const val TIMEOUT_MS = 60_000L

    private fun s(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    /** What the runs ask of you while Robot reads. */
    fun note(run: BargeRun): String = when (run) {
        BargeRun.FIRST -> "Robot is reading the test paragraph. Say “stop” or one wake phrase, once."
        BargeRun.COUNT -> "Robot is reading the test paragraph to the end. Say “stop” and your wake phrases as often as you like, a few seconds apart."
        BargeRun.SILENT -> "Robot is reading the test paragraph. Stay quiet until she's done."
        BargeRun.TRICKY -> "Robot is reading words like “stop”. Stay quiet until she's done."
    }

    /**
     * The settings a test runs with (and ran with): "Hear me while Robot talks", "Interrupt sensitivity" (0.4.6), how her
     * voice plays, and whether the phone's voice speaks ([missing]: the voice model chosen isn't installed, so the phone's
     * voice stands in).
     */
    fun setup(mode: BargeIn, playback: Playback, phoneVoice: Boolean, missing: String? = null,
        interrupt: InterruptSensitivity = InterruptSensitivity.MEDIUM): String =
        "Hear me while Robot talks: ${mode.titleText.en()} · Interrupt sensitivity: ${interrupt.titleText.en()} · Robot's voice plays as ${playback.titleText.en()}" +
            (if (phoneVoice) " · the phone's voice" + (missing?.let { " ($it isn't installed)" } ?: "") else "")

    /**
     * The result in words. [name]: a wake phrase's text by id, for display only (the result keeps ids); null shows
     * its number.
     */
    fun lines(r: BargeTestResult, now: Long = System.currentTimeMillis(), name: (Int) -> String? = { null }): List<String> {
        fun word(id: Int) = if (id == 0) "“stop”" else name(id)?.let { "“$it”" } ?: "wake phrase no. $id"
        val out = mutableListOf<String>()
        when (r.state) {
            BenchState.RUNNING -> out += if (now - r.started > TIMEOUT_MS + 10_000) "The test stopped without a result. Try again." else r.note
            BenchState.FAILED -> out += r.note
            BenchState.DONE -> {
                val first = r.hits.firstOrNull { it.kind == BargeHitKind.STOPPED_HER }
                val fired = r.falseStops + r.falseWakes + r.suppressed
                out += when {
                    r.tricky -> if (fired == 0) "Tricky words: Robot's own words fired nothing in ${s(r.spokeMs)} of talking."
                        else "Tricky words: Robot's own words fired $fired times in ${s(r.spokeMs)} of talking: EchoGuard set aside ${r.suppressed}, " +
                            "${r.falseStops + r.falseWakes} got through (in real use each would stop her or start a question)."
                    r.silent -> if (r.falseStops == 0 && r.falseWakes == 0) "Silent run: no false “stop” or wake word in ${s(r.spokeMs)} of Robot talking."
                        else "Silent run: ${r.falseStops} false “stop”, ${r.falseWakes} false wake word in ${s(r.spokeMs)} of Robot talking."
                    r.count -> if (r.counted == 0) "Count run: Robot heard nothing in ${s(r.spokeMs)} of talking."
                        else "Count run: Robot heard ${r.counted} in ${s(r.spokeMs)} of talking. Compare with how many you said."
                    r.heard == BargeTestResult.HEARD_STOP -> "Heard your “stop” ${s(r.atMs)} in; Robot was silent ${r.stopMs} ms later."
                    r.heard == BargeTestResult.HEARD_WAKE -> "Heard your wake phrase${first?.let { " " + word(it.phrase) } ?: ""} ${s(r.atMs)} in; " +
                        "Robot was silent ${r.stopMs} ms later."
                    else -> "Robot didn't hear you (she talked for ${s(r.spokeMs)})."
                }
                // Every keyword, when there is more to say than the first line does.
                val more = r.hits.size > 1 || r.hits.any { it.kind != BargeHitKind.STOPPED_HER }
                if (more) out += "Heard: " + r.hits.joinToString(" · ") { h ->
                    (if (h.atMs >= 0) s(h.atMs) + " " else "") + word(h.phrase) + when (h.kind) {
                        BargeHitKind.SET_ASIDE -> " (set aside)"
                        BargeHitKind.LATE -> " (after the first)"
                        BargeHitKind.EARLY -> " (before she spoke)"
                        else -> ""
                    }
                } + if (r.hits.size >= BargeTestResult.MAX_HITS) " · …" else ""
                if (r.suppressed > 0 && !r.tricky) out += "${r.suppressed} set aside as possibly Robot's own voice."
            }
        }
        if (r.state != BenchState.FAILED) {
            val mic = MicKind.entries.firstOrNull { it.id == r.mic }?.title
            val aec = AecState.entries.firstOrNull { it.id == r.aec }?.title
            out += setup(r.mode, r.playback, r.phoneVoice, interrupt = r.interrupt) + (mic?.let { " · mic: $it" } ?: "") + (aec?.let { " · echo cancellation $it" } ?: "")
        }
        return out
    }
}
