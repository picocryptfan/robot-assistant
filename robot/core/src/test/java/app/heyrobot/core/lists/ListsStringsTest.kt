// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ListsStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/** The default lists' names that Robot shows (core i18n ListsStrings) are the ones ListRole compares, in both languages (§2.2). */
class ListsStringsTest {
    @Test fun theRolesNamesAreTheCatalogs() {
        val fr = Langs.of(Lang.FR)
        assertEquals(listOf(ListsStrings.shopping().en(), ListsStrings.shopping().render(fr)), ListRole.SHOPPING.names)
        assertEquals(listOf(ListsStrings.todo().en(), ListsStrings.todo().render(fr)), ListRole.TODO.names)
    }
}
