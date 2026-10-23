// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import app.heyrobot.core.i18n.FrTypo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The OQLF's typography in the app's French: every value of values-fr-rCA is
 * what core's FrTypo makes of it (a no-break space before ":" and inside « », a narrow one before ; ! ?, ’ and …), so the
 * resources and core's catalog follow one rule. tools/fr_typo.py --fix writes the spaces as entities (&#160;, &#8239;).
 * English isn't checked for « »: the setup guide's "Try saying…" quotes French examples in them, on purpose. Core's
 * catalogs are checked by core's CatalogsTest the same way.
 */
class FrenchTypographyTest {
    private fun visible(s: String) = s.replace("\u00A0", "⍽").replace("\u202F", "·")

    @Test fun frenchValuesFollowTheOqlf() {
        val problems = mutableListOf<String>()
        for (f in L10nFiles.frenchFiles()) for (e in L10nFiles.entries("values-fr-rCA", f)) for ((k, v) in e.values) {
            val masked = L10nFiles.maskPlaceholders(v)
            val fixed = FrTypo.fix(masked)
            if (fixed != masked) problems += "${e.file}: ${e.name}${if (k.isEmpty()) "" else "[$k]"}: \"${visible(masked)}\" should be \"${visible(fixed)}\""
        }
        assertEquals(problems.joinToString("\n", postfix = "\n(⍽ U+00A0, · U+202F) python3 -I robot/tools/fr_typo.py robot --fix writes them"), 0, problems.size)
    }
}
