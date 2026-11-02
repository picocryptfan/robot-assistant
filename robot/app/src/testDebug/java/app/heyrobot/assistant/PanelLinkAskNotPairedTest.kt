// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.link.LinkCommand
import app.heyrobot.sys.LinkSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * rc4: "ask Zed's robot …" reached the model and a helper because the panel's route stopped at Robot to Robot off or nobody
 * paired. It's said to be not paired with the switch on or off, and nothing else in the words counts as Robot to Robot's.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PanelLinkAskNotPairedTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() { PanelLinkAsk.forget() }
    @After fun off() { LinkSettingsStore.update(ctx) { it.copy(on = false) }; PanelLinkAsk.forget() }

    private fun bothSwitches(check: () -> Unit) {
        for (on in listOf(false, true)) {
            LinkSettingsStore.update(ctx) { it.copy(on = on) }
            check()
        }
    }

    @Test fun anUnpairedRobotIsSaidSoWithTheSwitchOnOrOff() = bothSwitches {
        for (q in listOf("Ask Zed's robot for a pasta recipe", "ask Zed's robot for the weather", "ask Zed's robot when Zed is free Friday", "ask Zed's robot what time it is"))
            assertEquals(q, LinkCommand.NotPaired("Zed", Lang.EN), PanelLinkAsk.route(ctx, q))
        assertEquals(LinkCommand.NotPaired("Zed", Lang.FR), PanelLinkAsk.route(ctx, "demande au robot de Zed la recette de lasagne"))
    }

    @Test fun otherWordsStayOrdinaryTalk() = bothSwitches {
        for (q in listOf("what's a robot", "is Sam free Friday", "ask this robot for a recipe", "demande au robot de chercher une recette", "plan a trip with Mom"))
            assertNull(q, PanelLinkAsk.route(ctx, q))
    }
}
