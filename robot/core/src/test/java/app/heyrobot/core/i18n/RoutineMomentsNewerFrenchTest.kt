// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix-fr-routine: the French line says the reminders can't be changed here; "celui-ci" could be read as the newer Robot. */
class RoutineMomentsNewerFrenchTest {
    @Test fun theRemindersCantBeChangedHere() {
        assertEquals("Tes rappels pour un moment viennent d’un Robot plus récent et ne peuvent pas être modifiés ici. Mets Robot à jour pour en ajouter un.",
            RoutineStrings.momentsNewer().render(Langs.of(Lang.FR)))
    }
}
