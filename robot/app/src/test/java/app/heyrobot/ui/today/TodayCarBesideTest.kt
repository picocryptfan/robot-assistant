// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HS1 (home-screens-plan.md): Today's Car mode button beside Talk, Talk filled at twice the width, both 56 dp; stacked
 * below 360 dp wide or from 1.5x text; Talk first in both forms (TalkBack's order); the tap is Getting started's, which
 * asks CarButton.open. Today only: Getting started keeps its own buttons.
 */
class TodayCarBesideTest {
    private val today = File("src/main/java/app/heyrobot/ui/today/TodayScreen.kt").readText()
    private fun body(src: String, head: String): String {
        val at = src.indexOf(head)
        assertTrue("no $head", at >= 0)
        val next = src.indexOf("\n@Composable", at + head.length).let { if (it < 0) src.length else it }
        return src.substring(at, next)
    }
    private fun assertInOrder(src: String, vararg parts: String) {
        var from = 0
        for (p in parts) {
            val i = src.indexOf(p, from)
            assertTrue("missing or out of order: $p", i >= 0)
            from = i + p.length
        }
    }

    @Test fun sideBySideOnAPhoneAtNormalText() {
        assertFalse(TalkRow.stacked(411f, 1f)) // Pixel 8
        assertFalse(TalkRow.stacked(360f, 1f))
        assertFalse(TalkRow.stacked(411f, 1.3f))
    }

    @Test fun stackedWhenNarrowOrAtLargeText() {
        assertTrue(TalkRow.stacked(359.9f, 1f))
        assertTrue(TalkRow.stacked(320f, 1f))
        assertTrue(TalkRow.stacked(411f, 1.5f))
        assertTrue(TalkRow.stacked(411f, 2f)) // 200 %, the phone row's stacked case
        assertTrue(TalkRow.stacked(840f, 1.5f))
    }

    @Test fun talkFirstTwiceAsWideAndCarModeTonal() {
        val talk = body(today, "private fun Talk(talk: () -> Unit, car: () -> Unit, stacked: Boolean)")
        assertInOrder(talk, "if (stacked) Column(", "TalkButton(talk, Modifier.fillMaxWidth())", "CarModeButton(car, Modifier.fillMaxWidth())",
            "} else Row(", "TalkButton(talk, Modifier.weight(2f)", "CarModeButton(car, Modifier.weight(1f)")
        assertInOrder(body(today, "private fun TalkButton("), "Button(onClick = talk, modifier = modifier.heightIn(min = 56.dp)", "R.string.home_talk_to_robot")
        assertInOrder(body(today, "private fun CarModeButton("), "FilledTonalButton(onClick = car, modifier = modifier.heightIn(min = 56.dp)",
            "RIcons.Car", "R.string.home_car_mode")
    }

    @Test fun pinnedOrScrollingAsTalkWas() {
        val screen = body(today, "fun TodayScreen(")
        assertInOrder(screen, "val stacked = TalkRow.stacked(maxWidth.value, fontScale)", "if (!pinned) Talk(t.talk, t.carMode, stacked)",
            "if (pinned) Box(", "Talk(t.talk, t.carMode, stacked)")
        assertEquals(2, Regex("""\bTalk\(t\.talk""").findAll(today).count())
    }

    @Test fun theTapIsGettingStartedsCarButton() {
        assertEquals(1, Regex("""carMode = a\.carMode,""").findAll(today).count())
        val main = File("src/main/java/app/heyrobot/ui/MainActivity.kt").readText()
        assertTrue(main.contains("carMode = { CarButton.open(ctx)?.let { page = Page.Settings(SettingsRoutes.CAR) } }"))
        val start = File("src/main/java/app/heyrobot/ui/Screens.kt").readText()
        assertEquals(1, Regex("""FilledTonalButton\(onClick = a\.carMode""").findAll(start).count())
    }
}
