// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** CLDR's plural rules: English one is exactly 1; French one is 0 and 1, many an exact million. */
class PluralTest {
    @Test fun english() {
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.EN, 0))
        assertEquals(PluralCategory.ONE, Plural.category(Lang.EN, 1))
        assertEquals(PluralCategory.ONE, Plural.category(Lang.EN, -1))
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.EN, 2))
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.EN, 1_000_000))
        // "1.5 GB" and "1.0 GB" are other; written without decimals, 1 is one.
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.EN, 1.5, 1))
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.EN, 1.0, 1))
        assertEquals(PluralCategory.ONE, Plural.category(Lang.EN, 1.0, 0))
    }

    @Test fun french() {
        assertEquals(PluralCategory.ONE, Plural.category(Lang.FR, 0))
        assertEquals(PluralCategory.ONE, Plural.category(Lang.FR, 1))
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.FR, 2))
        assertEquals(PluralCategory.MANY, Plural.category(Lang.FR, 1_000_000))
        assertEquals(PluralCategory.MANY, Plural.category(Lang.FR, 3_000_000))
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.FR, 1_000_001))
        assertEquals(PluralCategory.ONE, Plural.category(Lang.FR, 1.5, 1))
        assertEquals(PluralCategory.ONE, Plural.category(Lang.FR, 0.5, 1))
        assertEquals(PluralCategory.OTHER, Plural.category(Lang.FR, 2.5, 1))
    }

    @Test fun manyFallsBackToOther() {
        val p = Plural(one = "{n} minute", other = "{n} minutes")
        assertEquals("{n} minutes", p.pick(PluralCategory.MANY))
        assertEquals("{n} de minutes", p.copy(many = "{n} de minutes").pick(PluralCategory.MANY))
    }
}
