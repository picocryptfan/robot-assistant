// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.sys.RobotTimers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fix-everyday-e (everyday-1.2.2-a, finding 4): "how much time is left on my timer" got the model's "I don't have access to
 * the current status of your timers". Now the panel answers it from the timers Robot set (sys RobotTimers), with no model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerLeftTest {
    internal class Fake(override val locked: Boolean = false, val lang: Lang = Lang.EN, override val handsFree: Boolean = false) : PanelRoutines.Host {
        val said = ArrayList<String>()
        val launched = ArrayList<String>()
        var text = ""
        var left = false
        override val speak = true
        override fun text(replyId: String, text: String) { this.text = text }
        override fun append(replyId: String, text: String) = Unit
        override fun say(sentence: String, personal: Boolean) { said += sentence }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean = false
        override fun show(replyId: String, card: Card) = Unit
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() { left = true }
        override fun launch(intent: Intent): Boolean { launched += intent.action.orEmpty(); return true }
        override fun langs(said: String?, router: Lang?): Langs = Langs.of(lang)
    }

    private val ctx: Context get() = VoiceHub.app

    @Before fun hub() { VoiceHub.init(ApplicationProvider.getApplicationContext()); RobotTimers.clear(ctx) }

    private fun ask(host: Fake, words: String = "how much time is left on my timer"): Fake {
        val cmd = PanelRoutines.route(words, fromContent = false)
        assertEquals(RoutineCommand.TimerLeft(french = host.lang == Lang.FR), cmd)
        runBlocking { PanelRoutines.handle("reply", cmd!!, host, words) }
        return host
    }

    @Test fun aTimerRobotSetIsAnsweredWithItsTimeLeft() {
        RobotTimers.set(ctx, 60, System.currentTimeMillis() - 28_000)
        val h = ask(Fake())
        assertTrue(h.text, h.text.matches(Regex("""Your timer for 1 minute has 3[12] seconds left\. It ends at .+\.""")))
        assertEquals(1, h.said.size)
        assertEquals(emptyList<String>(), h.launched)
    }

    @Test fun noneSetSaysSoAndThatTheClocksOwnArentSeen() {
        val h = ask(Fake())
        assertEquals("Robot hasn't set a timer. It can't see one set in your clock app: check there.", h.text)
    }

    @Test fun inFrenchAndOnTheLockScreen() {
        RobotTimers.set(ctx, 300, System.currentTimeMillis() - 60_000)
        val h = ask(Fake(locked = true, lang = Lang.FR), "il reste combien de temps à ma minuterie")
        assertTrue(h.text, h.text.startsWith("Il reste 4 minutes à ta minuterie de 5 minutes."))
    }
}
