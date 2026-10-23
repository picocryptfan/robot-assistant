// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Robot's screens and buttons as sentences name them, and Android's: every one in both languages, paths
 * joined by "›" on screen and a pause aloud, Android's in « Paramètres », never Robot's « Réglages ».
 */
class UiLabelTest {
    /** The same word in both languages, on purpose (l10n/same-in-french.txt). */
    private val same = File(File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile, "l10n/same-in-french.txt").readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    @Test fun everyTitleAndButtonHasItsFrench() {
        assertTrue(UiStrings.all.size >= 60)
        for (m in UiStrings.all) {
            val en = Phrase.M(m).render(Langs.EN)
            val fr = Phrase.M(m).render(Langs.of(Lang.FR))
            assertTrue(m.id, m.fr != null)
            if (en !in same) assertNotEquals(m.id, en, fr)
        }
    }

    @Test fun paths() {
        val memoryImport = UiStrings.inSettings(UiStrings.memory) / UiStrings.importRow
        assertEquals("Settings › Memory › Import", memoryImport.render(Lang.EN))
        assertEquals("Réglages › Mémoire › Importer", memoryImport.render(Lang.FR))
        assertEquals("Réglages, Mémoire, Importer", memoryImport.render(Lang.FR, Medium.VOICE))
        assertEquals("Mot d’éveil", UiLabel(UiStrings.wakeWord).render(Lang.FR))
        assertEquals(UiLabel(UiStrings.settings, UiStrings.memory), UiStrings.inSettings(UiStrings.memory))
    }

    @Test fun androidsScreensInAndroidsWords() {
        for (p in AndroidPath.entries) {
            val en = p.render(Lang.EN)
            val fr = p.render(Lang.FR)
            // A path starts at Settings, except the parts of one a sentence continues (Build number, App info › Permissions › …).
            assertTrue(p.name, p.partial || (en.startsWith("Settings") && fr.startsWith("Paramètres")))
            assertTrue(p.name, !p.partial || !(en.startsWith("Settings") || fr.startsWith("Paramètres")))
            assertTrue(p.name, "Réglages" !in fr)
            assertEquals(p.name, en.count { it == '›' }, fr.count { it == '›' })
            assertTrue(p.name, '›' !in p.render(Lang.FR, Medium.VOICE))
        }
    }
}
