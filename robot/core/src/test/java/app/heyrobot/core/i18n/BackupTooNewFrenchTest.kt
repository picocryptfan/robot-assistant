// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix-seal-lows-f 2: in French the files are the newer Robot's and can't be read here, with no pronoun that could mean the newer Robot, and the pages are Historique and Notes, as in the glossary. */
class BackupTooNewFrenchTest {
    private val fr = Langs.of(Lang.FR)

    @Test fun historyIsNamedAsNewerAndUnreadableHere() {
        assertEquals("Une partie de ton historique a été enregistrée par un Robot plus récent et ne peut pas être lue ici. Mets Robot à jour, ou supprime ces conversations avec Supprimer celles-ci dans Historique, puis réessaie. Ou sauvegarde sans l’Historique.",
            BackupStrings.historyTooNew().render(fr))
    }

    @Test fun notesAreNamedAsNewerAndUnreadableHere() {
        assertEquals("Certaines de tes notes ont été enregistrées par un Robot plus récent et ne peuvent pas être lues ici. Mets Robot à jour, ou supprime ces notes avec Supprimer celles-ci dans Notes, puis réessaie. Ou sauvegarde sans les Notes.",
            BackupStrings.notesTooNew().render(fr))
    }
}
