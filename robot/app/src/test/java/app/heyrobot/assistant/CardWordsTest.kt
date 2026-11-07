// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fix-124c (phone-1.2.4-c): a waiting card's question and buttons follow the conversation's language, not the menus'. */
class CardWordsTest {
    private val frenchOnEnglishMenus = Langs(Lang.FR, Lang.EN, Lang.EN)
    private val englishOnFrenchMenus = Langs(Lang.EN, Lang.FR, Lang.FR)
    private val alarm = UiAction("a1", ToolName.SET_ALARM, "Désactiver ton alarme de 12 h 31", ActionStatus.PENDING, null)

    @Test fun aFrenchTurnOnEnglishMenusAsksInFrench() {
        val l = CardWords.langs(frenchOnEnglishMenus)
        assertEquals(Lang.FR, l.say)
        val a = CardWords.dress(alarm, l)
        assertEquals("Veux-tu que Robot fasse ceci\u202F?", a.ask)
        assertEquals("Annuler", a.cancel)
        assertEquals("Fais-le", a.doIt)
        assertEquals("Lequel\u202F?", CardWords.dress(alarm.copy(choices = listOf("A", "B")), l).ask)
    }

    @Test fun englishOnFrenchMenusKeepsTheMenusFrenchAsTheTitleDoes() {
        assertEquals(Lang.FR, CardWords.langs(englishOnFrenchMenus).say)
        assertEquals("Do you want Robot to do this?", CardWords.dress(alarm, CardWords.langs(Langs.EN)).ask)
    }

    @Test fun aCardsOwnWordsStayAndADoneCardIsLeftAlone() {
        val own = CardWords.dress(alarm.copy(cancel = "Start fresh", confirm = "Turn off"), CardWords.langs(frenchOnEnglishMenus))
        assertEquals("Start fresh", own.cancel)
        assertEquals("Turn off", own.confirm)
        assertNull(CardWords.dress(alarm.copy(status = ActionStatus.DONE), CardWords.langs(frenchOnEnglishMenus)).ask)
    }
}
