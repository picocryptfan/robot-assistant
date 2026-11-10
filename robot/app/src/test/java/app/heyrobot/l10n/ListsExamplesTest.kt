// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.lists.ListsRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lists LS8: what Lists' screens tell you to say is something Robot hears. Every example quoted in strings_lists.xml
 * (“…” in English, « … » in French) is taken by core ListsRouter, in that language; and Settings › Lists' title is the
 * screen sentences name (core UiStrings.lists), so « Réglages › Listes » in a sentence finds the page by its name.
 */
class ListsExamplesTest {
    private val quoted = mapOf(
        "values" to Regex("“([^”]+)”"),
        "values-fr-rCA" to Regex("«[\\s  ]*([^»]+?)[\\s  ]*»"),
    )

    @Test fun everyExampleTheScreensQuoteIsHeard() {
        var n = 0
        val problems = mutableListOf<String>()
        for ((dir, rx) in quoted) {
            val lang = if (dir == "values") Lang.EN else Lang.FR
            for (e in L10nFiles.entries(dir, "strings_lists.xml")) for (v in e.values.values) for (m in rx.findAll(v)) {
                val said = m.groupValues[1]
                n++
                val c = ListsRouter.route(said)
                if (c == null || c.lang != lang) problems += "${e.file}: ${e.name}: “$said” is ${c ?: "not heard"}"
            }
        }
        assertTrue("no example found", n >= 4)
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test fun settingsListsTitleIsTheScreenSentencesName() {
        for ((dir, lang) in listOf("values" to Lang.EN, "values-fr-rCA" to Lang.FR)) {
            val title = L10nFiles.entries(dir, "strings_lists.xml").single { it.name == "lists_title" }.values.getValue("")
            assertEquals(dir, Phrase.M(UiStrings.lists).render(Langs.of(lang)), title)
        }
    }

    @Test fun frenchListsTooNewNamesTheNewerRobotNotThisOne() {
        val fr = L10nFiles.entries("values-fr-rCA", "strings_lists.xml").single { it.name == "lists_too_new" }.values.getValue("")
        assertEquals("Tes listes ont été enregistrées par un Robot plus récent et ne peuvent pas être lues ni changées ici. Mets Robot à jour, ou recommence.", fr)
        assertTrue(fr, !fr.contains("celle-ci") && !fr.contains("celui-ci"))
    }

    @Test fun frenchFilesAndMemoryTooNewNameTheNewerRobotNotThisOne() {
        val files = L10nFiles.entries("values-fr-rCA", "strings_files.xml").single { it.name == "files_too_new" }.values.getValue("")
        assertEquals("La liste de tes fichiers a été enregistrée par un Robot plus récent et ne peut pas être lue ni changée ici. Mets Robot à jour, ou recommence.", files)
        val memory = L10nFiles.entries("values-fr-rCA", "strings_memory.xml").single { it.name == "memory_problem_too_new" }.values.getValue("")
        assertEquals("Une version plus récente de Robot a enregistré ta mémoire, et elle ne peut pas être lue ni changée ici.", memory)
        for (fr in listOf(files, memory)) assertTrue(fr, !fr.contains("celle-ci") && !fr.contains("celui-ci"))
    }
}
