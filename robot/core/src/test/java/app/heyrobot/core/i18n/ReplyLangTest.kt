// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.LangGuess
import app.heyrobot.core.LangId
import app.heyrobot.core.LangUnsure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * One reply language per turn: a fixed setting wins; then what you said when it is sure; a short line that
 * isn't sure keeps the previous turn's language for ten minutes; then the app language. Stories 2 and 3 of §2 with the
 * real language table.
 */
class ReplyLangTest {
    private val now = 1_000_000_000L
    private val min = 60_000L
    private val id: LangId by lazy { LangId.load(File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream()) }

    private fun decide(heard: Lang?, sure: Boolean, previous: ReplyLang.Last? = null, ui: Lang = Lang.EN, setting: ReplyLangChoice = ReplyLangChoice.SPOKEN) =
        ReplyLang.decide(heard, sure, previous, now, ui, setting)

    @Test fun aFixedSettingWins() {
        assertEquals(Lang.EN, decide(Lang.FR, true, setting = ReplyLangChoice.EN))
        assertEquals(Lang.FR, decide(Lang.EN, true, setting = ReplyLangChoice.FR))
        assertEquals(Lang.FR, decide(Lang.EN, true, ui = Lang.FR, setting = ReplyLangChoice.MENUS))
        assertEquals(Lang.EN, decide(Lang.FR, true, ui = Lang.EN, setting = ReplyLangChoice.MENUS))
    }

    /** l10n design §4.8: the model is told the language only when the setting fixes it; the default tells it nothing. */
    @Test fun theModelIsToldOnlyAFixedLanguage() {
        for (say in Lang.entries) assertEquals(say.name, null, ReplyLang.answerIn(ReplyLangChoice.SPOKEN, say))
        // A fixed setting's turn language is the setting's (decide), so the line says what Robot's own lines say.
        assertEquals(Lang.EN, ReplyLang.answerIn(ReplyLangChoice.EN, decide(Lang.FR, true, setting = ReplyLangChoice.EN)))
        assertEquals(Lang.FR, ReplyLang.answerIn(ReplyLangChoice.FR, decide(Lang.EN, true, setting = ReplyLangChoice.FR)))
        assertEquals(Lang.FR, ReplyLang.answerIn(ReplyLangChoice.MENUS, decide(Lang.EN, true, ui = Lang.FR, setting = ReplyLangChoice.MENUS)))
        assertEquals(Lang.EN, ReplyLang.answerIn(ReplyLangChoice.MENUS, decide(Lang.FR, true, ui = Lang.EN, setting = ReplyLangChoice.MENUS)))
    }

    @Test fun whatYouSaidWhenSure() {
        assertEquals(Lang.FR, decide(Lang.FR, true, ui = Lang.EN))
        assertEquals(Lang.EN, decide(Lang.EN, true, previous = ReplyLang.Last(Lang.FR, now - min), ui = Lang.FR))
    }

    @Test fun aShortLineKeepsThePreviousLanguageForTenMinutes() {
        assertEquals(Lang.FR, decide(Lang.EN, false, previous = ReplyLang.Last(Lang.FR, now - 5 * min)))
        assertEquals(Lang.FR, decide(null, false, previous = ReplyLang.Last(Lang.FR, now - 9 * min)))
        assertEquals(Lang.EN, decide(null, false, previous = ReplyLang.Last(Lang.FR, now - 10 * min)))
        assertEquals(Lang.FR, decide(null, false, ui = Lang.FR))
        // A clock that went back is no memory.
        assertEquals(Lang.EN, decide(null, false, previous = ReplyLang.Last(Lang.FR, now + min)))
    }

    @Test fun nothingHeard() {
        assertEquals(Lang.FR, ReplyLang.unheard(Lang.FR, ReplyLangChoice.SPOKEN))
        assertEquals(Lang.EN, ReplyLang.unheard(Lang.FR, ReplyLangChoice.EN))
        assertEquals(Lang.FR, ReplyLang.unheard(Lang.EN, ReplyLangChoice.FR))
    }

    @Test fun aRoutersMatchWinsOverTheTable() {
        val fr = LangGuess("fr", 5.0, 5, "fr")
        assertEquals(Lang.EN, ReplyLang.decide(fr, Lang.EN, null, now, Lang.FR, ReplyLangChoice.SPOKEN))
        assertEquals(Lang.FR, ReplyLang.decide(fr, null, null, now, Lang.EN, ReplyLangChoice.SPOKEN))
        val unsure = LangGuess(null, 0.0, 1, "fr", LangUnsure.CLOSE)
        assertEquals(Lang.EN, ReplyLang.decide(unsure, null, ReplyLang.Last(Lang.EN, now - min), now, Lang.FR, ReplyLangChoice.SPOKEN))
    }

    /** §2 stories 2 and 3: a French timer on English menus, then "OK" stays French, then a sure English question, then a name. */
    @Test fun theStories() {
        var last: ReplyLang.Last? = null
        fun turn(heard: String, at: Long): Lang = ReplyLang.decide(id.detect(heard), null, last, at, Lang.EN, ReplyLangChoice.SPOKEN)
            .also { last = ReplyLang.Last(it, at) }
        assertEquals(Lang.FR, turn("mets une minuterie de 5 minutes", now))
        assertEquals(Lang.FR, turn("OK", now + min))
        assertEquals(Lang.EN, turn("what's the weather like today", now + 2 * min))
        assertEquals(Lang.EN, turn("Marie-Ève", now + 3 * min))
        // With nothing to keep, a short line LangId is sure of has its own language.
        last = null
        assertEquals(Lang.FR, turn("bonjour Robot", now + 4 * min))
    }

    @Test fun theSettingsIds() {
        assertEquals(ReplyLangChoice.SPOKEN, ReplyLangChoice.of(null))
        assertEquals(ReplyLangChoice.SPOKEN, ReplyLangChoice.of("klingon"))
        for (c in ReplyLangChoice.entries) assertEquals(c, ReplyLangChoice.of(c.id))
    }
}
