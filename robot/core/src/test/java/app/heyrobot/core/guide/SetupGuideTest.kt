package app.heyrobot.core.guide

import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.update.UpdateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0: the setup guide's order and moves, its file, when it opens by itself, what Home offers, and what the summary lists. */
class SetupGuideTest {
    @Test fun seventeenPagesFromWelcomeToSummary() {
        assertEquals(17, SetupGuide.COUNT)
        assertEquals(GuideStep.WELCOME, GuideStep.entries.first())
        assertEquals(GuideStep.CAN_DO, GuideStep.WELCOME.next)
        assertEquals(GuideStep.SUMMARY, GuideStep.entries.last())
        assertNull(GuideStep.SUMMARY.next)
        assertNull(GuideStep.WELCOME.previous)
        assertEquals(3, GuideStep.PHONE.number)
        assertEquals(GuideStep.entries.size, GuideStep.entries.map { it.id }.toSet().size)
    }

    @Test fun opensByItselfOncePerVersion() {
        val fresh = GuideState()
        assertTrue(SetupGuide.opensAtLaunch(fresh))
        val opened = SetupGuide.open(fresh)
        assertEquals(GuideStep.WELCOME, opened.at)
        assertFalse(SetupGuide.opensAtLaunch(opened))
        // Finish later doesn't bring it back at the next launch: Home offers to continue instead.
        val later = SetupGuide.moved(opened, GuideStep.WELCOME, GuideStep.CAN_DO)
        assertFalse(SetupGuide.opensAtLaunch(later))
        assertEquals(HomeGuide.CONTINUE, SetupGuide.home(later))
        // An older guide seen, finished or not: the new version opens once.
        assertTrue(SetupGuide.opensAtLaunch(GuideState(seen = SetupGuide.VERSION - 1, finished = SetupGuide.VERSION - 1)))
    }

    @Test fun resumesWhereItWasLeftElseStartsOver() {
        val under = GuideState(seen = 1, at = GuideStep.MEMORY, skipped = setOf(GuideStep.VOICE))
        assertEquals(under, SetupGuide.open(under))
        val done = SetupGuide.finish(under)
        assertNull(done.at)
        assertEquals(SetupGuide.VERSION, done.finished)
        assertEquals(HomeGuide.RUN_AGAIN, SetupGuide.home(done))
        val again = SetupGuide.open(done)
        assertEquals(GuideStep.WELCOME, again.at)
        assertEquals(emptySet<GuideStep>(), again.skipped)
    }

    @Test fun skipMovesOnAndRemembersNextAndBackDont() {
        var s = SetupGuide.open(GuideState())
        s = SetupGuide.skip(s, GuideStep.WELCOME)
        assertEquals(GuideStep.CAN_DO, s.at)
        assertEquals(setOf(GuideStep.WELCOME), s.skipped)
        s = SetupGuide.moved(s, GuideStep.CAN_DO, GuideStep.WELCOME)
        assertEquals(setOf(GuideStep.WELCOME), s.skipped)
        // Gone through with Next this time: no longer skipped.
        s = SetupGuide.moved(s, GuideStep.WELCOME, GuideStep.CAN_DO)
        assertEquals(emptySet<GuideStep>(), s.skipped)
        // Skipping the last page stays there.
        assertEquals(GuideStep.SUMMARY, SetupGuide.skip(s, GuideStep.SUMMARY).at)
    }

    @Test fun theFileRoundTripsAndSurvivesDamage() {
        val s = GuideState(seen = 1, at = GuideStep.WEATHER, finished = 0, skipped = setOf(GuideStep.MESSAGES, GuideStep.PHONE))
        assertEquals(s, GuideState.fromJson(s.toJson()))
        assertEquals(GuideState(), GuideState.fromJson(null))
        assertEquals(GuideState(), GuideState.fromJson("{not json"))
        assertEquals(GuideState(seen = 1), GuideState.fromJson("""{"v":1,"seen":1,"at":"nowhere","skipped":["gone"]}"""))
        assertEquals(GuideState(seen = 1, finished = 1), GuideState.fromJson(GuideState(seen = 1, finished = 1).toJson()))
    }

    @Test fun theSummaryReadsEveryFile() {
        val r = RobotSettings(wakeOnlyWhileCharging = true, safetyCards = SafetyCards.AFTER_READING, weatherHome = WeatherPlace("Rimouski", 48.4, -68.5),
            lockedTools = setOf(ToolName.SET_TIMER))
        val lines = GuideSummary.lines(GuideChoices(robot = r, messages = MessageSettings(on = true, announce = true),
            memory = MemorySettings(on = false, onLockScreen = true), updates = UpdateSettings(testBuilds = true), webOn = true, cheogramInstalled = true))
            .associateBy { it.item }
        assertEquals(GuideItem.entries.toSet(), lines.keys)
        assertEquals(true, lines.getValue(GuideItem.ONLY_WHILE_CHARGING).on)
        assertEquals("Always after Robot reads something", lines.getValue(GuideItem.ASK_FIRST).value)
        assertEquals("questions and timers", lines.getValue(GuideItem.LOCK_SCREEN).value)
        assertEquals("Cheogram", lines.getValue(GuideItem.TEXT_APP).value)
        assertEquals("Ask each time", lines.getValue(GuideItem.EMAIL_APP).value)
        assertEquals(true, lines.getValue(GuideItem.ANNOUNCE).on)
        // Memory off: its lock-screen use is off with it.
        assertEquals(false, lines.getValue(GuideItem.MEMORY).on)
        assertEquals(false, lines.getValue(GuideItem.MEMORY_LOCKED).on)
        assertEquals("Rimouski", lines.getValue(GuideItem.WEATHER_HOME).value)
        assertEquals("Ask the first time", lines.getValue(GuideItem.NEAREST).value)
        assertEquals(true, lines.getValue(GuideItem.WEB_SEARCH).on)
        assertEquals(true, lines.getValue(GuideItem.TEST_BUILDS).on)
        assertEquals(true, lines.getValue(GuideItem.AUTO_UPDATES).on)
    }

    @Test fun aNewInstallsSummaryIsTheDefaults() {
        val lines = GuideSummary.lines(GuideChoices()).associateBy { it.item }
        // On by default: the wake word, answering cards by voice, memory, the headset button, the update check.
        for (on in listOf(GuideItem.WAKE_WORD, GuideItem.VOICE_CARDS, GuideItem.MEMORY, GuideItem.HEADSET_BUTTON, GuideItem.AUTO_UPDATES)) {
            assertEquals(on.name, true, lines.getValue(on).on)
        }
        // Off until you choose: messages, announcements, the brief, car mode, helpers, web search, Termux, test builds.
        for (off in listOf(GuideItem.MESSAGES, GuideItem.ANNOUNCE, GuideItem.BRIEF, GuideItem.CAR_MODE, GuideItem.HELPERS, GuideItem.WEB_SEARCH,
            GuideItem.TERMUX, GuideItem.TEST_BUILDS, GuideItem.MEMORY_LOCKED, GuideItem.ONLY_WHILE_CHARGING, GuideItem.TRANSLATE_TRANSCRIPTS)) {
            assertEquals(off.name, false, lines.getValue(off).on)
        }
        assertNull(lines.getValue(GuideItem.WEATHER_HOME).value)
        assertEquals("Heart", lines.getValue(GuideItem.ROBOT_VOICE).value)
    }
}
