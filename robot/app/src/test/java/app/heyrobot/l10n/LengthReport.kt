// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import app.heyrobot.core.i18n.Catalogs
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import org.junit.Test

/**
 * French runs longer than English. This prints, and never fails: the short labels whose
 * French is more than 1.5 times their English (20 characters or less: buttons, chips, rows), and every catalog entry with
 * a maxChars, for the area's package and WP9's review to look at on screen.
 */
class LengthReport {
    @Test fun report() {
        val lines = mutableListOf<String>()
        for (f in L10nFiles.frenchFiles()) {
            val en = L10nFiles.entries("values", f).associateBy { it.name }
            for (e in L10nFiles.entries("values-fr-rCA", f)) for ((k, fr) in e.values) {
                val english = en[e.name]?.values?.get(k) ?: continue
                if (english.length <= 20 && fr.length > english.length * 1.5) lines += "${e.file}: ${e.name}: \"$english\" ${english.length} → \"$fr\" ${fr.length}"
            }
        }
        for (c in Catalogs.all) for (m in c.all) {
            val max = m.maxChars ?: continue
            val fr = Phrase.M(m).render(Langs.of(Lang.FR))
            lines += "${m.id}: at most $max: \"$fr\" ${fr.length}" + if (fr.length > max) " (over)" else ""
        }
        println("LengthReport: ${lines.size} line(s)" + lines.joinToString("") { "\n  $it" })
    }
}
