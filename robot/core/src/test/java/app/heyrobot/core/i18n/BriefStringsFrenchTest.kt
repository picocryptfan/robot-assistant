package app.heyrobot.core.i18n

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-today-review 6: the brief's French on screen (the panel, the widget) has U+00A0 before ":", never a plain space.
 * The catalog types ordinary spaces (CatalogsTest); render() puts the French ones in, so this reads what it shows.
 */
class BriefStringsFrenchTest {
    @Test fun theBriefsFrenchShowsANoBreakSpaceBeforeEachColon() {
        var colons = 0
        for (m in BriefStrings.all) {
            if (!m.translated(Lang.FR)) continue
            val names = m.placeholders(m.en) + m.placeholders(m.fr)
            val args = names.associateWith { if (it == m.count) Arg.Count(2L) else Arg.Plain("Marie") }
            val out = Phrase.M(m, args).render(Langs.of(Lang.FR))
            assertTrue("${m.id}: $out", " :" !in out)
            colons += Regex("\u00A0:").findAll(out).count()
        }
        assertTrue("$colons", colons > 20)
    }
}
