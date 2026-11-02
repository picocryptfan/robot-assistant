// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.everyday

import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listening for other apps (everyday-set-plan.md §6.2, E5): the order Robot checks a request in, so the caller's microphone
 * use is noted only for a request Robot could serve and only a caller in front learns whether Robot is busy; the languages it
 * hears; the pause an app may ask for; the prompt the sheet shows; the level; the voice log's codes and words.
 */
class ListenForAppsTest {
    /** Which lazy facts were asked, in order. */
    private val asked = mutableListOf<String>()

    private fun facts(on: Boolean = true, assistant: Boolean = true, ownAudio: Boolean = false, language: String? = null, robotMic: Boolean = true,
        caller: Boolean = true, model: Boolean = true, busy: Boolean = false) = ListenFacts(on, assistant, ownAudio, language,
        robotMic = { asked += "robotMic"; robotMic }, caller = { asked += "caller"; caller }, modelReady = { asked += "model"; model }, busy = { asked += "busy"; busy })

    @Test fun theSheetListensOnlyOnceResumedAndFocusedAndHoweverTheyComeInOrder() {
        val a = SheetMic()
        assertEquals(SheetMic.Step.NONE, a.resumed())
        assertEquals(SheetMic.Step.LISTEN, a.focus(true))
        val b = SheetMic()
        assertEquals(SheetMic.Step.NONE, b.focus(true))
        assertEquals(SheetMic.Step.LISTEN, b.resumed())
        // Told it isn't on top: it waits, and top again is enough.
        val c = SheetMic()
        assertEquals(SheetMic.Step.NONE, c.top(false))
        c.resumed()
        assertEquals(SheetMic.Step.NONE, c.focus(true))
        assertEquals(SheetMic.Step.LISTEN, c.top(true))
        // Only once.
        assertEquals(SheetMic.Step.NONE, c.focus(true))
    }

    @Test fun aCoverThatLeavesTheSheetVisibleEndsItBeforeAnyAudio() {
        // E9's item 1: the app starts its own activity with a translucent theme as soon as it has started the sheet. Android
        // pauses the sheet and never stops it. With no focus yet, nothing has begun, and nothing ever does.
        val m = SheetMic()
        m.resumed()
        assertEquals(SheetMic.Step.END, m.paused())
        assertEquals(SheetMic.Step.NONE, m.resumed())
        assertEquals(SheetMic.Step.NONE, m.focus(true))
    }

    @Test fun eachThingThatSaysItIsNoLongerInFrontEndsItAndItNeverComesBack() {
        val ends = listOf<SheetMic.() -> SheetMic.Step>({ paused() }, { focus(false) }, { top(false) }, { obscured() })
        for (end in ends) {
            val m = SheetMic()
            m.resumed()
            assertEquals(SheetMic.Step.LISTEN, m.focus(true))
            assertEquals(SheetMic.Step.END, m.end())
            // Whatever comes after, once: an app can't cover it, uncover it and have it listen unseen.
            for (again in ends) assertEquals(SheetMic.Step.NONE, m.again())
            assertEquals(SheetMic.Step.NONE, m.resumed())
            assertEquals(SheetMic.Step.NONE, m.focus(true))
            assertEquals(SheetMic.Step.NONE, m.top(true))
        }
    }

    @Test fun aCoveredWindowEndsItEvenBeforeItBegan() {
        val m = SheetMic()
        m.resumed()
        assertEquals(SheetMic.Step.END, m.obscured())
        assertEquals(SheetMic.Step.NONE, m.focus(true))
    }

    @Test fun losingTheFocusBeforeItBeganOnlyWaits() {
        val m = SheetMic()
        m.resumed()
        assertEquals(SheetMic.Step.NONE, m.focus(false))
        assertEquals(SheetMic.Step.LISTEN, m.focus(true))
    }

    @Test fun aSheetThatClosedOrRefusedNeverListens() {
        val m = SheetMic()
        m.over()
        m.resumed()
        assertEquals(SheetMic.Step.NONE, m.focus(true))
        assertEquals(SheetMic.Step.NONE, m.paused())
        assertEquals(SheetMic.Step.NONE, m.obscured())
    }

    @Test fun bothOfAndroidsObscuredFlagsCountAsCovered() {
        // MotionEvent.FLAG_WINDOW_IS_OBSCURED, FLAG_WINDOW_IS_PARTIALLY_OBSCURED.
        assertTrue(SheetMic.covered(0x1))
        assertTrue(SheetMic.covered(0x2))
        assertTrue(SheetMic.covered(0x3))
        assertFalse(SheetMic.covered(0))
        assertFalse(SheetMic.covered(0x4))
    }

    @Test fun aRequestIsCheckedInOrderAndTheCallerOnlyWhenRobotCouldServeIt() {
        assertNull(ListenForApps.decide(facts()))
        assertEquals(listOf("robotMic", "caller", "model", "busy"), asked)
        // Off, not the assistant, its own audio or another language: the caller is never checked (nothing noted for it).
        for ((f, why) in listOf(facts(on = false) to ListenRefusal.OFF, facts(assistant = false) to ListenRefusal.NOT_ASSISTANT,
            facts(ownAudio = true) to ListenRefusal.OWN_AUDIO, facts(language = "es-ES") to ListenRefusal.LANGUAGE)) {
            asked.clear()
            assertEquals(why, ListenForApps.decide(f))
            assertEquals(why.name, emptyList<String>(), asked)
        }
        // The switch comes first, whatever else is true.
        assertEquals(ListenRefusal.OFF, ListenForApps.decide(facts(on = false, assistant = false, ownAudio = true, language = "de", caller = false, busy = true)))
        asked.clear()
        assertEquals(ListenRefusal.ROBOT_MIC, ListenForApps.decide(facts(robotMic = false, caller = false)))
        assertEquals(listOf("robotMic"), asked)
        // A caller that isn't in front learns nothing about the model or whether Robot is busy (a call, you talking to it).
        asked.clear()
        assertEquals(ListenRefusal.CALLER, ListenForApps.decide(facts(caller = false, model = false, busy = true)))
        assertEquals(listOf("robotMic", "caller"), asked)
        assertEquals(ListenRefusal.NO_MODEL, ListenForApps.decide(facts(model = false, busy = true)))
        assertEquals(ListenRefusal.BUSY, ListenForApps.decide(facts(busy = true)))
        assertEquals(ListenRefusal.entries.toList(), listOf(ListenRefusal.OFF, ListenRefusal.NOT_ASSISTANT, ListenRefusal.OWN_AUDIO, ListenRefusal.LANGUAGE,
            ListenRefusal.ROBOT_MIC, ListenRefusal.CALLER, ListenRefusal.NO_MODEL, ListenRefusal.BUSY))
    }

    @Test fun theSheetIsCheckedInOrder() {
        fun s(caller: Boolean = true, on: Boolean = true, keyguard: Boolean = false, lock: Boolean = false, language: String? = null, model: Boolean = true,
            busy: Boolean = false) = SheetFacts(caller, on, keyguard, lock, language, { model }, { busy })
        assertNull(ListenForApps.decide(s()))
        assertEquals(SheetRefusal.NO_CALLER, ListenForApps.decide(s(caller = false, on = false, keyguard = true)))
        assertEquals(SheetRefusal.OFF, ListenForApps.decide(s(on = false, keyguard = true)))
        assertEquals(SheetRefusal.LOCKED, ListenForApps.decide(s(keyguard = true, lock = true)))
        assertEquals(SheetRefusal.LOCK_APP, ListenForApps.decide(s(lock = true, language = "it")))
        assertEquals(SheetRefusal.LANGUAGE, ListenForApps.decide(s(language = "it", model = false)))
        assertEquals(SheetRefusal.NO_MODEL, ListenForApps.decide(s(model = false, busy = true)))
        assertEquals(SheetRefusal.BUSY, ListenForApps.decide(s(busy = true)))
    }

    @Test fun englishAndFrenchWithAnyRegionOrNoneNamed() {
        for (t in listOf(null, "", "  ", "en", "en-US", "en-CA", "EN-gb", "fr", "fr-CA", "fr_FR", "fr-Latn-CA")) assertTrue("$t", ListenForApps.hears(t))
        for (t in listOf("es", "es-ES", "de-DE", "it", "eng", "fra", "e", "english", "-en", "zh-Hant")) assertFalse(t, ListenForApps.hears(t))
    }

    @Test fun thePauseAnAppAsksForStaysWithinSettingsRange() {
        assertEquals(1_000, ListenForApps.pauseMs(null, 1_000))
        assertEquals(1_600, ListenForApps.pauseMs(0, 1_600))
        assertEquals(1_600, ListenForApps.pauseMs(-5, 1_600))
        assertEquals(1_000, ListenForApps.pauseMs(100, 1_000))
        assertEquals(1_600, ListenForApps.pauseMs(800, 1_600))
        assertEquals(600, ListenForApps.pauseMs(100, 600))
        assertEquals(1_500, ListenForApps.pauseMs(1_500, 1_000))
        assertEquals(2_500, ListenForApps.pauseMs(10_000, 1_000))
        assertEquals(2_500, ListenForApps.pauseMs(Long.MAX_VALUE, 1_000))
    }

    @Test fun thePromptIsPlainTextOfAtMost80Characters() {
        assertNull(ListenForApps.prompt(null))
        assertNull(ListenForApps.prompt(" \n\t​‮ "))
        assertEquals("Say the name of a city", ListenForApps.prompt("  Say the name\n\nof a   city "))
        // A right-to-left override, a zero-width space and a control character are dropped; the words stay as written.
        assertEquals("Speak now", ListenForApps.prompt("‮Speak\u0007 ​now"))
        assertEquals("Dis le nom d’une ville", ListenForApps.prompt("Dis le nom d’une ville"))
        val long = "a".repeat(200)
        val cut = ListenForApps.prompt(long)!!
        assertEquals(80, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("…"))
        assertEquals("b".repeat(80), ListenForApps.prompt("b".repeat(80)))
        // Cut by characters, never through one: 100 emoji give 79 and "…".
        val emoji = ListenForApps.prompt("🎵".repeat(100))!!
        assertEquals(80, emoji.codePointCount(0, emoji.length))
        assertTrue(emoji.dropLast(1).chunked(2).all { it == "🎵" })
    }

    @Test fun theLevelIsOnTheRecognizersScale() {
        assertEquals(-2f, ListenForApps.rmsDb(-120f))
        assertEquals(0f, ListenForApps.rmsDb(-60f))
        assertEquals(5f, ListenForApps.rmsDb(-30f))
        assertEquals(10f, ListenForApps.rmsDb(0f))
        assertEquals(10f, ListenForApps.rmsDb(6f))
    }

    @Test fun theVoiceLogHoldsCodesAndTenthsOfASecond() {
        assertEquals(0, ListenForApps.tenths(-1))
        assertEquals(23, ListenForApps.tenths(2_345))
        assertEquals(999, ListenForApps.tenths(3_600_000))
        for (codes in listOf(ListenForAppCode.entries, ListenSheetCode.entries)) assertEquals(codes.size, codes.map { it.id }.toSet().size)
        // Every refusal has its own code.
        assertEquals(ListenRefusal.entries.size, ListenRefusal.entries.map { ListenForAppCode.of(it) }.toSet().size)
        assertEquals(SheetRefusal.entries.size, SheetRefusal.entries.map { ListenSheetCode.of(it) }.toSet().size)
        assertEquals("Listening for another app: words returned · 2.3 s",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.DONE.id, 23, 0)))
        assertEquals("Listening for another app: the app isn't in front or has no microphone access",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.CALLER.id, 0, 0)))
        assertEquals("Listening sheet for another app: you cancelled", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.LISTEN_SHEET, ListenSheetCode.CANCELLED.id, 0, 0)))
    }
}
