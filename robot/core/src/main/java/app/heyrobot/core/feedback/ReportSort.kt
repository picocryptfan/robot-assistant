// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import java.text.Normalizer
import java.util.Locale

// Pure (1.4, report-a-problem-plan.md "What's new" 3). Report a problem sorts what you report with no model: the feature
// from the tool that ran or the page the report came from, the problem from where you came from and your words, English
// or French. The card shows both as chip rows you can change; the report's header gets "Feature:" and "Problem:"
// (ReportHeader). [header] is the report's English, read by the developer and the PC tool's grouping.

/** What the report is about. The card's labels come with the card (RP2); [header] is the report's English. */
enum class ReportFeature(val header: String) {
    TIMERS("Timers and alarms"), // l10n: not text (the report's header: English)
    APPS("Opening apps"), // l10n: not text (the report's header: English)
    MAPS("Maps"), // l10n: not text (the report's header: English)
    FLASHLIGHT("Flashlight"), // l10n: not text (the report's header: English)
    PEOPLE("Texts, email and calls"), // l10n: not text (the report's header: English)
    NOTES("Notes"), // l10n: not text (the report's header: English)
    CALENDAR("Calendar"), // l10n: not text (the report's header: English)
    WEATHER("Weather"), // l10n: not text (the report's header: English)
    PHONE("Phone status"), // l10n: not text (the report's header: English)
    MEMORY("Memory"), // l10n: not text (the report's header: English)
    WEB("Web search"), // l10n: not text (the report's header: English)
    TERMUX("Termux"), // l10n: not text (the report's header: English)
    SKILLS("Skills"), // l10n: not text (the report's header: English)
    FILES("Robot Files"), // l10n: not text (the report's header: English)
    CONNECTIONS("Connections"), // l10n: not text (the report's header: English)
    LISTS("Lists"), // l10n: not text (the report's header: English)
    SUMS("Sums"), // l10n: not text (the report's header: English)
    LINK("Robot to Robot"), // l10n: not text (the report's header: English)
    HELPERS("Helpers"), // l10n: not text (the report's header: English)
    /** A turn with no tool: my answer itself. */
    ANSWERS("Answers"), // l10n: not text (the report's header: English)
    VOICE("Voice"), // l10n: not text (the report's header: English)
    MODEL("Model"), // l10n: not text (the report's header: English)
    UPDATES("Updates"), // l10n: not text (the report's header: English)
    OTHER("Other"), // l10n: not text (the report's header: English)
    ;

    companion object {
        /** The header's word back to the feature (or its name); null for one this version doesn't know. */
        fun of(s: String?): ReportFeature? = s?.trim()?.let { t -> entries.firstOrNull { it.header.equals(t, true) || it.name.equals(t, true) } }
    }
}

/** What went wrong, in the plan's English and French (strings_feedback.xml carries the card's words, RP2). */
enum class ReportProblem(val header: String) {
    DIDNT_UNDERSTAND("Didn't understand"), // l10n: not text (the report's header: English)
    WRONG_ANSWER("Wrong answer"), // l10n: not text (the report's header: English)
    WRONG_ACTION("Wrong action"), // l10n: not text (the report's header: English)
    CRASHED("Crashed"), // l10n: not text (the report's header: English)
    SLOW("Slow"), // l10n: not text (the report's header: English)
    SOUND("Sound"), // l10n: not text (the report's header: English)
    LANGUAGE("Language"), // l10n: not text (the report's header: English)
    ;

    companion object {
        fun of(s: String?): ReportProblem? = s?.trim()?.let { t -> entries.firstOrNull { it.header.equals(t, true) || it.name.equals(t, true) } }
    }
}

object ReportSort {
    /**
     * What the sort reads. [words]: what you wrote about the problem. [tools]: the turn's tools in the order they ran (the
     * last one names the feature). [reply]: my answer, if the report came from a turn. [heard] and [replied]: the language
     * you spoke and the one I answered in (BCP 47 tags); a reply in another language is a language problem, whatever
     * the menus' language (a bilingual turn, English spoken under French menus, isn't one).
     */
    data class Input(
        val entry: FeedbackEntry,
        val words: String = "", // l10n: not text (matched words or a pattern, not shown)
        val tools: List<ToolName> = emptyList(),
        val reply: String? = null,
        val heard: String? = null,
        val replied: String? = null,
    )

    data class Sorted(val feature: ReportFeature, val problem: ReportProblem)

    fun sort(input: Input): Sorted = Sorted(feature(input), problem(input))

    fun feature(input: Input): ReportFeature {
        input.tools.lastOrNull()?.let { return feature(it) }
        return when (input.entry) {
            FeedbackEntry.MODEL_PROBLEM -> ReportFeature.MODEL
            FeedbackEntry.VOICE_LOG -> ReportFeature.VOICE
            FeedbackEntry.UPDATER -> ReportFeature.UPDATES
            else -> when {
                input.reply != null -> ReportFeature.ANSWERS
                input.entry == FeedbackEntry.VOICE -> ReportFeature.VOICE
                else -> ReportFeature.OTHER
            }
        }
    }

    fun feature(tool: ToolName): ReportFeature = when (tool) {
        ToolName.SET_TIMER, ToolName.SET_ALARM -> ReportFeature.TIMERS
        ToolName.OPEN_APP -> ReportFeature.APPS
        ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE -> ReportFeature.MAPS
        ToolName.FLASHLIGHT -> ReportFeature.FLASHLIGHT
        ToolName.ASK_HELPER -> ReportFeature.HELPERS
        else -> when (tool.group) {
            ToolGroup.PEOPLE -> ReportFeature.PEOPLE
            ToolGroup.NOTES -> ReportFeature.NOTES
            ToolGroup.CALENDAR -> ReportFeature.CALENDAR
            ToolGroup.WEATHER -> ReportFeature.WEATHER
            ToolGroup.PHONE -> ReportFeature.PHONE
            ToolGroup.MEMORY -> ReportFeature.MEMORY
            ToolGroup.WEB -> ReportFeature.WEB
            ToolGroup.TERMUX -> ReportFeature.TERMUX
            ToolGroup.SKILLS -> ReportFeature.SKILLS
            ToolGroup.FILES -> ReportFeature.FILES
            ToolGroup.CONNECTIONS -> ReportFeature.CONNECTIONS
            ToolGroup.LISTS -> ReportFeature.LISTS
            ToolGroup.SUMS -> ReportFeature.SUMS
            ToolGroup.LINK -> ReportFeature.LINK
            else -> ReportFeature.OTHER
        }
    }

    // Folded like the words they're matched against: lower case, no accents, straight apostrophes.
    private val CRASH = listOf("crash", "crashed", "crashes", "crashing", "closed by itself", "closed on its own", "plante", // l10n: not text (matched words or a pattern, not shown)
        "a plante", "s'est ferme", "s'est fermee", "s'est arrete", "ferme tout seul") // l10n: not text (matched words, English and French)
    private val SLOW = listOf("slow", "slowly", "slower", "took forever", "takes forever", "too long", "lag", "laggy", "lent", // l10n: not text (matched words or a pattern, not shown)
        "lente", "lents", "lentes", "lentement", "lenteur", "trop long", "trop longtemps") // l10n: not text (matched words, English and French)
    private val SOUND = listOf("sound", "sounds", "voice", "audio", "volume", "too loud", "too quiet", "speaker", "voix", // l10n: not text (matched words or a pattern, not shown)
        "le son", "du son", "pas de son", "trop fort", "haut-parleur") // l10n: not text (matched words, English and French)
    private val LANGUAGE = listOf("language", "wrong language", "in french", "in english", "langue", "en anglais", "en francais") // l10n: not text (matched words, English and French)
    private val NOT_UNDERSTOOD = listOf("didn't understand", "did not understand", "misunderstood", "misheard", "didn't catch", // l10n: not text (matched words or a pattern, not shown)
        "didn't get it", "didn't hear", "pas compris", "mal compris", "mal entendu", "pas saisi", "pas entendu") // l10n: not text (matched words, English and French)
    /** My own "Sorry, I didn't catch that." in English and French (PanelStrings.sorry). */
    private val SORRY = listOf("didn't catch that", "je n'ai pas compris") // l10n: not text (matched words, English and French)

    fun problem(input: Input): ReportProblem {
        val w = fold(input.words)
        if (input.entry == FeedbackEntry.CRASH || has(w, CRASH)) return ReportProblem.CRASHED
        if (has(w, SLOW)) return ReportProblem.SLOW
        // "It didn't understand my voice" is about understanding, not sound.
        if (has(w, NOT_UNDERSTOOD)) return ReportProblem.DIDNT_UNDERSTAND
        if (has(w, SOUND)) return ReportProblem.SOUND
        if (has(w, LANGUAGE) || otherLanguage(input.heard, input.replied)) return ReportProblem.LANGUAGE
        if (input.tools.isEmpty() && input.reply != null && has(fold(input.reply), SORRY)) return ReportProblem.DIDNT_UNDERSTAND
        if (input.tools.isNotEmpty()) return ReportProblem.WRONG_ACTION
        return ReportProblem.WRONG_ANSWER
    }

    private fun otherLanguage(heard: String?, replied: String?): Boolean {
        val a = primary(heard) ?: return false
        val b = primary(replied) ?: return false
        return a != b
    }

    private fun primary(tag: String?): String? = tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** Lower case, accents off, curly apostrophes straight, so "Planté" matches "plante" and "didn’t" matches "didn't". */
    internal fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'').replace('ʼ', '\'').lowercase(Locale.ROOT) // l10n: not text (matched words or a pattern, not shown)

    private fun has(folded: String, phrases: List<String>): Boolean = phrases.any { p ->
        var i = folded.indexOf(p)
        while (i >= 0) {
            val before = i == 0 || !folded[i - 1].isLetterOrDigit()
            val end = i + p.length
            val after = end == folded.length || !folded[end].isLetterOrDigit()
            if (before && after) return@any true
            i = folded.indexOf(p, i + 1)
        }
        false
    }
}
