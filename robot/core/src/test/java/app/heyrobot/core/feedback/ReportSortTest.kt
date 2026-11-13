// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import app.heyrobot.core.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.4, report a problem (RP1): the feature and the problem with no model, from English and French words. */
class ReportSortTest {
    private fun sort(entry: FeedbackEntry = FeedbackEntry.ABOUT, words: String = "", tools: List<ToolName> = emptyList(),
        reply: String? = null, heard: String? = null, replied: String? = null) =
        ReportSort.sort(ReportSort.Input(entry, words, tools, reply, heard, replied))

    @Test fun theLastToolNamesTheFeature() {
        assertEquals(ReportFeature.TIMERS, sort(tools = listOf(ToolName.SET_TIMER)).feature)
        assertEquals(ReportFeature.PEOPLE, sort(tools = listOf(ToolName.SET_TIMER, ToolName.SEND_TEXT)).feature)
        assertEquals(ReportFeature.HELPERS, sort(tools = listOf(ToolName.ASK_HELPER)).feature)
        assertEquals(ReportFeature.MAPS, sort(tools = listOf(ToolName.MAPS_NAVIGATE)).feature)
        assertEquals(ReportFeature.WEATHER, sort(entry = FeedbackEntry.MODEL_PROBLEM, tools = listOf(ToolName.WEATHER)).feature)
    }

    @Test fun everyToolHasAFeature() {
        for (t in ToolName.entries) assertEquals(t.name, true, ReportSort.feature(t) in ReportFeature.entries)
        assertEquals(ReportFeature.FILES, ReportSort.feature(ToolName.FIND_FILES))
        assertEquals(ReportFeature.TERMUX, ReportSort.feature(ToolName.TERMUX_COMMAND))
    }

    @Test fun withNoToolThePageNamesIt() {
        assertEquals(ReportFeature.MODEL, sort(entry = FeedbackEntry.MODEL_PROBLEM).feature)
        assertEquals(ReportFeature.VOICE, sort(entry = FeedbackEntry.VOICE_LOG).feature)
        assertEquals(ReportFeature.UPDATES, sort(entry = FeedbackEntry.UPDATER).feature)
        assertEquals(ReportFeature.ANSWERS, sort(entry = FeedbackEntry.VOICE, reply = "It's 14 degrees.").feature)
        assertEquals(ReportFeature.VOICE, sort(entry = FeedbackEntry.VOICE).feature)
        assertEquals(ReportFeature.OTHER, sort().feature)
    }

    @Test fun aCrashIsACrashWhateverElse() {
        assertEquals(ReportProblem.CRASHED, sort(entry = FeedbackEntry.CRASH, words = "it was slow", tools = listOf(ToolName.CALL)).problem)
        assertEquals(ReportProblem.CRASHED, sort(words = "Robot crashed when I opened it").problem)
        assertEquals(ReportProblem.CRASHED, sort(words = "L'appli a planté").problem)
        assertEquals(ReportProblem.CRASHED, sort(words = "Robot s’est fermé tout seul").problem)
    }

    @Test fun theStructureGivesTheDefault() {
        assertEquals(ReportProblem.WRONG_ACTION, sort(tools = listOf(ToolName.SET_ALARM), reply = "Alarm set for 7:00.").problem)
        assertEquals(ReportProblem.WRONG_ANSWER, sort(reply = "Paris is in Spain.").problem)
        assertEquals(ReportProblem.WRONG_ANSWER, sort().problem)
        assertEquals(ReportProblem.DIDNT_UNDERSTAND, sort(reply = "Sorry, I didn't catch that.").problem)
        assertEquals(ReportProblem.DIDNT_UNDERSTAND, sort(reply = "Désolé, je n'ai pas compris.").problem)
        assertEquals(ReportProblem.WRONG_ACTION, sort(tools = listOf(ToolName.OPEN_APP), reply = "Sorry, I didn't catch that.").problem)
    }

    @Test fun yourWordsInEnglish() {
        assertEquals(ReportProblem.SLOW, sort(words = "The answer was really slow", tools = listOf(ToolName.WEATHER)).problem)
        assertEquals(ReportProblem.SOUND, sort(words = "The voice cut out halfway").problem)
        assertEquals(ReportProblem.SOUND, sort(words = "No sound at all").problem)
        assertEquals(ReportProblem.LANGUAGE, sort(words = "It answered in French").problem)
        assertEquals(ReportProblem.DIDNT_UNDERSTAND, sort(words = "It didn’t understand me", reply = "Here's a recipe.").problem)
        // Whole words only: "slowdown" and "flag" aren't "slow" and "lag".
        assertEquals(ReportProblem.WRONG_ANSWER, sort(words = "The flag on the slowdown page", reply = "x").problem)
    }

    @Test fun yourWordsInFrench() {
        assertEquals(ReportProblem.SLOW, sort(words = "C'est beaucoup trop lent").problem)
        assertEquals(ReportProblem.SLOW, sort(words = "La réponse était lente").problem)
        assertEquals(ReportProblem.SOUND, sort(words = "La voix est coupée").problem)
        assertEquals(ReportProblem.SOUND, sort(words = "Il n'y avait pas de son").problem)
        assertEquals(ReportProblem.LANGUAGE, sort(words = "Il a répondu en anglais").problem)
        assertEquals(ReportProblem.LANGUAGE, sort(words = "Mauvaise LANGUE").problem)
        assertEquals(ReportProblem.DIDNT_UNDERSTAND, sort(words = "Il a mal compris ma question", tools = listOf(ToolName.CALL)).problem)
        // "son" alone is "his" or "her", not sound.
        assertEquals(ReportProblem.WRONG_ACTION, sort(words = "Il a envoyé son message", tools = listOf(ToolName.SEND_TEXT)).problem)
    }

    @Test fun anotherLanguageHeard() {
        assertEquals(ReportProblem.LANGUAGE, sort(reply = "x", heard = "en-US", replied = "fr-CA").problem)
        assertEquals(ReportProblem.WRONG_ANSWER, sort(reply = "x", heard = "fr", replied = "fr-CA").problem)
        assertEquals(ReportProblem.WRONG_ANSWER, sort(reply = "x", heard = null, replied = "fr-CA").problem)
    }

    @Test fun headerWordsReadBack() {
        for (f in ReportFeature.entries) assertEquals(f, ReportFeature.of(f.header.uppercase()))
        for (p in ReportProblem.entries) assertEquals(p, ReportProblem.of(" " + p.header + " "))
        assertEquals(ReportProblem.WRONG_ACTION, ReportProblem.of("wrong_action"))
        assertNull(ReportFeature.of("Teleport"))
        assertNull(ReportProblem.of(null))
    }

    @Test fun notUnderstoodBeforeSoundAndTheReplysLanguage() {
        assertEquals(ReportProblem.DIDNT_UNDERSTAND, sort(words = "It didn't understand my voice").problem)
        assertEquals(ReportProblem.DIDNT_UNDERSTAND, sort(words = "Elle n’a pas compris ma voix").problem)
        // Spoken in English and answered in English, whatever the menus' language: not a language problem.
        assertEquals(ReportProblem.WRONG_ACTION, sort(tools = listOf(ToolName.CALL), heard = "en-CA", replied = "en").problem)
        assertEquals(ReportProblem.LANGUAGE, sort(reply = "x", heard = "fr-CA", replied = "en").problem)
    }
}
