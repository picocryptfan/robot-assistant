package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The empty panel's tips: three at a time, a new three each open, only what can work now. */
class TipsTest {
    private val d = RobotSettings()

    @Test fun threeDifferentEachOpenAndAllInTurn() {
        val can = Tips.Can(d, locked = false, claude = true, screen = true)
        val all = Tips.available(can)
        assertTrue(all.size > 9)
        val seen = mutableSetOf<Tip>()
        for (round in 0 until all.size) {
            val t = Tips.pick(can, round)
            assertEquals(3, t.size)
            assertEquals(3, t.distinct().size)
            assertTrue(Tips.pick(can, round) == t)
            seen += t
        }
        assertEquals(all.toSet(), seen)
        assertFalse(Tips.pick(can, 0) == Tips.pick(can, 1))
        // Any round, even a negative one from a wrapped counter.
        assertEquals(3, Tips.pick(can, -7).size)
    }

    @Test fun onlyWhatIsOnAndCanWorkNow() {
        val off = d.copy(approvals = d.approvals + (ToolName.WEATHER to Approval.OFF), textApp = TextApp.OFF)
        val tips = Tips.available(Tips.Can(off, locked = false)).map { it.text }
        assertFalse(tips.any { it.contains("weather") || it.startsWith("Text ") })
        assertFalse("no Claude, no screen", tips.any { it.contains("Claude") || it.contains("screen") })
        assertTrue(Tips.available(Tips.Can(d, false, claude = true)).any { it.text.contains("Claude") })
        // Locked: questions, and only what works on the lock screen (timers and alarms by default); no photos.
        val locked = Tips.available(Tips.Can(d, locked = true, claude = false, screen = false))
        assertEquals(listOf("Set a timer for 10 minutes", "Wake me up at 7:00", "Tell me a fun fact"), locked.map { it.text })
        assertEquals(locked, Tips.pick(Tips.Can(d, locked = true), 5))
        assertTrue(Tips.available(Tips.Can(d.copy(lockedTools = setOf(ToolName.FLASHLIGHT)), locked = true)).any { it.text.contains("flashlight") })
    }

    /** A tip that would do something or go online only fills the text field; a question that reads on the phone asks at once. */
    @Test fun tipsThatActOrGoOnlineOnlyFillTheField() {
        val all = Tips.available(Tips.Can(d, locked = false, claude = true, screen = true)).associate { it.text to it.kind }
        for (t in listOf("Set a timer for 10 minutes", "Wake me up at 7:00", "Remember that I parked on level 3", "Turn on the flashlight",
                "Text Mom I'm on my way", "Email Sam about dinner on Friday", "Add lunch with Alex on Friday at noon", "Help me free up space",
                "Directions to the nearest pharmacy", "What's the weather tomorrow?", "Ask Claude why the sky is blue")) {
            assertEquals(t, TipKind.FILL, all[t])
        }
        for (t in listOf("What's on my calendar today?", "How's my battery?", "How much storage is free?", "Find my note about the wifi",
                "Tell me a fun fact")) {
            assertEquals(t, TipKind.ASK, all[t])
        }
        assertEquals(TipKind.SCREEN, all["What's on my screen?"])
        assertEquals(TipKind.PHOTO, all["Ask about a photo"])
        // Every tip is one of those: nothing new slips through as a question that acts.
        assertEquals(18, all.size)
    }

    @Test fun theScreenTipComesWithTheScreen() {
        val tips = Tips.pick(Tips.Can(d, locked = false), 0)
        val now = Tips.withScreen(tips)
        assertEquals(Tips.SCREEN, now.last())
        assertEquals(tips.take(2), now.take(2))
        assertEquals(now, Tips.withScreen(now))
        assertEquals(TipKind.SCREEN, Tips.SCREEN.kind)
    }
}
