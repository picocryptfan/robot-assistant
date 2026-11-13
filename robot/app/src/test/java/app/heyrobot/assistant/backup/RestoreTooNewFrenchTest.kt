// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** fix-seal-lows-g 1: the French restore results for a newer Robot's files say the files can't be read here (no pronoun that could mean the newer Robot) and name the pages Historique and Notes, like BackupStrings. */
class RestoreTooNewFrenchTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun fr(name: String): String =
        checkNotNull(Regex("""name="$name">([^<]*)<""").find(File(root, "app/src/main/res/values-fr-rCA/strings_backup.xml").readText())).groupValues[1]

    @Test fun historyResult() {
        assertEquals("pas restauré, et rien n’a changé&#160;: une partie de ton historique a été enregistrée par un Robot plus récent et ne peut pas être lue ici. Mets Robot à jour, ou supprime ces conversations avec Supprimer celles-ci dans Historique, puis restaure de nouveau. Ou restaure sans l’Historique.",
            fr("backup_restore_result_history_too_new"))
    }

    @Test fun notesResult() {
        assertEquals("pas restauré, et rien n’a changé&#160;: certaines de tes notes ont été enregistrées par un Robot plus récent et ne peuvent pas être lues ici. Mets Robot à jour, ou supprime ces notes avec Supprimer celles-ci dans Notes, puis restaure de nouveau. Ou restaure sans les Notes.",
            fr("backup_restore_result_notes_too_new"))
    }

    @Test fun theNotesRowNamesThePageNotes() {
        assertEquals("Certaines ne peuvent pas être lues&#160;: un Robot plus récent les a enregistrées. Mets Robot à jour ou utilise Supprimer celles-ci dans Notes, ou laisse les Notes de côté", fr("backup_notes_too_new"))
        for (n in listOf("backup_notes_too_new", "backup_restore_result_history_too_new", "backup_restore_result_notes_too_new")) assertFalse(n, "celui-ci" in fr(n))
    }
}
