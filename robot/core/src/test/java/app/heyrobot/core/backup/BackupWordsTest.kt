// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Medium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §9.4: every code a backup or a restore gives has its sentence, from core's catalog (BackupStrings). */
class BackupWordsTest {
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN, Medium.SCREEN)

    @Test fun everyProblemHasItsOwnSentence() {
        val said = OpenProblem.entries.map { BackupWords.problem(it).render(en) }
        assertEquals(said.distinct(), said)
        assertEquals("That passphrase doesn't open this backup. Check it and try again.", BackupWords.problem(OpenProblem.WRONG_PASSPHRASE).render(en))
        assertEquals("This backup is incomplete: it ends too early. Copy it again from where you saved it.", BackupWords.problem(OpenProblem.INCOMPLETE).render(en))
        // Codes stay stable: logs keep them.
        assertEquals((1..8).toList(), OpenProblem.entries.map { it.id })
    }

    @Test fun skippedCategoriesAreNamed() {
        fun line(c: BackupCategory, s: PlanSkip?) = CategoryPlan(c, RestoreMode.ADD, s, 1)
        assertEquals("History needs Robot 1.3.0 or later: update Robot, then restore again to bring it.",
            BackupWords.skip(line(BackupCategory.HISTORY, PlanSkip.NEEDS_NEWER), "1.3.0")!!.render(en))
        assertEquals("History is off on this phone: turn it on to restore your conversations.", BackupWords.skip(line(BackupCategory.HISTORY, PlanSkip.HISTORY_OFF), "1.1.0")!!.render(en))
        assertNull(BackupWords.skip(line(BackupCategory.NOTES, null), "1.1.0"))
        assertNull(BackupWords.skip(line(BackupCategory.NOTES, PlanSkip.NOT_CHOSEN), "1.1.0"))
        for (s in PlanSkip.entries - PlanSkip.NOT_IN_BACKUP - PlanSkip.NOT_CHOSEN) assertTrue(s.name, BackupWords.skip(line(BackupCategory.KEYS, s), "1.1.0") != null)
        assertEquals("Part of this backup needs a newer Robot: update Robot, then restore again to bring it.",
            BackupWords.skipped(CategorySkip("skills", null, SkipWhy.UNKNOWN), "1.3.0").render(en))
        assertEquals("Headset, car and places in this backup is damaged, so Robot leaves it out.",
            BackupWords.skipped(CategorySkip("handsfree", BackupCategory.HANDSFREE, SkipWhy.REFUSED), "1.1.0").render(en))
        assertEquals(BackupCategory.entries.size, BackupCategory.entries.map { BackupWords.category(it).id }.distinct().size)
        assertTrue(BackupWords.noSpace(1_200_000_000).render(en).startsWith("There isn't enough free space: Robot needs about 1"))
    }
}
