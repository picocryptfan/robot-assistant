// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * files-1.4-plan.md §3.1, §3.3 (F5): what a share may name (another app's content provider only: never file:, another
 * user's provider or Robot's own), how many files one share brings, the sender's claims shown only when they look like
 * what they claim, shared text as a capped file, the names Robot gives, and the words that ask Robot to file what the
 * panel holds, in English and Québec French, with the misfires that must stay someone else's.
 */
class ShareRulesTest {
    private val own = setOf("ai.robot_app.exports", "ai.robot_app.capture", "ai.robot_app.scan")
    private fun check(scheme: String?, authority: String?) = ShareRules.check(SharedRef(scheme, authority)) { it in own }

    @Test fun onlyAnotherAppsContentProvider() {
        assertNull(check("content", "com.android.providers.downloads.documents"))
        assertNull(check("CONTENT", "org.example.mail.files"))
        assertEquals(ShareRefusal.NOT_CONTENT, check("file", null))
        assertEquals(ShareRefusal.NOT_CONTENT, check("file", ""))
        assertEquals(ShareRefusal.NOT_CONTENT, check("http", "example.org"))
        assertEquals(ShareRefusal.NOT_CONTENT, check(null, null))
        assertEquals(ShareRefusal.NO_PROVIDER, check("content", null))
        assertEquals(ShareRefusal.NO_PROVIDER, check("content", ""))
        assertEquals(ShareRefusal.OTHER_USER, check("content", "10@media"))
        for (a in own) assertEquals(a, ShareRefusal.OWN, check("content", a))
    }

    @Test fun twentyAtATimeEachOnce() {
        val (taken, left) = ShareRules.cap((1..30).map { "u$it" } + "u1" + "u2")
        assertEquals((1..20).map { "u$it" }, taken)
        assertEquals(10, left)
        assertEquals(listOf("a") to 0, ShareRules.cap(listOf("a", "a")))
    }

    @Test fun claimsShownOnlyWhenTheyLookRight() {
        assertEquals("application/pdf", ShareRules.claimedType(" Application/PDF "))
        assertEquals("image/*", ShareRules.claimedType("image/*"))
        assertNull(ShareRules.claimedType("receipt.pdf‮exe"))
        assertNull(ShareRules.claimedType("text/plain; charset=utf-8\nX"))
        assertNull(ShareRules.claimedType(""))
        assertNull(ShareRules.claimedSize(-1))
        assertEquals(10L, ShareRules.claimedSize(10))
    }

    @Test fun sharedTextBecomesACappedFile() {
        assertNull(ShareRules.textBytes(null))
        assertNull(ShareRules.textBytes(" \n\t"))
        assertArrayEquals("https://example.org/a".toByteArray(), ShareRules.textBytes("https://example.org/a"))
        // Cut at the cap on a character's boundary: never half of « é ».
        val long = "é".repeat(IntakeLimits.MAX_TEXT)
        val b = ShareRules.textBytes(long)!!
        assertTrue(b.size <= IntakeLimits.MAX_TEXT)
        assertEquals(b.size / 2, String(b, Charsets.UTF_8).length)
        assertTrue(String(b, Charsets.UTF_8).all { it == 'é' })
    }

    @Test fun namesRobotGives() {
        val at = 1_792_000_000_000L
        assertEquals("2026-10-14 17.46", IntakeNames.stamp(at, ZoneId.of("UTC")))
        assertEquals("Lunch plans.txt", IntakeNames.text("  Lunch\nplans ", "Shared text"))
        assertEquals("Shared text 2026-10-14 17.46.txt", IntakeNames.text(null, "Shared text 2026-10-14 17.46"))
        assertEquals("x.txt", IntakeNames.text("‮x", "y"))
        val names = FilesStrings.nameScan("when" to "2026-10-14 17.46")
        assertEquals("Scan 2026-10-14 17.46", names.render(Langs.EN))
        assertEquals("Numérisation 2026-10-14 17.46", names.render(Langs.of(Lang.FR)))
    }

    @Test fun fileThisInBothLanguages() {
        for (s in listOf("file this", "File this.", "Please file that", "save this to my files", "keep this receipt", "Hey Robot, file this!",
            "file this receipt please"))
            assertEquals(s, FileThis.Heard(FileThis.Ask.FILE, Lang.EN), FileThis.heard(s))
        for (s in listOf("classe ça", "Classe ça.", "garde ça dans mes fichiers", "range ce reçu", "classe ça s'il te plaît", "Range ce reçu, stp"))
            assertEquals(s, FileThis.Heard(FileThis.Ask.FILE, Lang.FR), FileThis.heard(s))
        assertEquals(FileThis.Heard(FileThis.Ask.ANYWAY, Lang.EN), FileThis.heard("File it anyway"))
        assertEquals(FileThis.Heard(FileThis.Ask.ANYWAY, Lang.FR), FileThis.heard("classe-le quand même"))
        assertEquals(FileThis.Heard(FileThis.Ask.ANYWAY, Lang.FR), FileThis.heard("Ajoute-le quand même."))
    }

    @Test fun misfiresStaySomeoneElses() {
        // free_storage, notes, memory, web, rules and moves (F8's), other talk.
        for (s in listOf("delete big files", "free up space", "libère de l'espace", "save this note", "remember that I file taxes in April",
            "find a pharmacy", "file this under Car", "Esso receipts go under Car", "move the Costco receipt to Taxes", "file a bug",
            "what's on this screen", "classe mes reçus", "file", "this", "range ta chambre", "can you file this for me later"))
            assertNull(s, FileThis.heard(s))
    }
}
