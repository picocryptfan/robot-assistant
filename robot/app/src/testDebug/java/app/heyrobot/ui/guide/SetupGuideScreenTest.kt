package app.heyrobot.ui.guide

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.heyrobot.core.Approval
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.TextApp
import app.heyrobot.core.ToolName
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.GuideStep
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.MtModelStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.ui.HomeScreen
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.settings.AboutSettingsScreen
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.PIXEL_10_PRO
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * 1.0: the setup guide. Next and Back through every page (a heading, a privacy line and where Settings has it on each),
 * Skip this step and Finish later with the place kept, each page's choice written where Settings keeps it (and nothing
 * written by looking or skipping), the summary from the files as they are, Home's and About's ways back, and five goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SetupGuideScreenTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    /** The guide's stores in memory: what each call asked, in order. */
    private class Stores(start: GuideStep = GuideStep.WELCOME, var f: GuideFiles = GuideFiles()) : GuideStores {
        var state = SetupGuide.open(GuideState()).copy(at = start)
        val events = ArrayList<String>()
        /** The texting apps on the phone (Cheogram by default). */
        var installed: Set<MessageApp> = setOf(MessageApp.CHEOGRAM)
        override fun guide() = state
        override fun saveGuide(s: GuideState) { state = s }
        override fun files() = f
        override fun messages(on: Boolean) {
            if (!(on && f.messages.allowlist.apps.isEmpty())) f = f.copy(messages = f.messages.copy(on = on))
            events += "messages $on"
        }
        override fun messageApps() = installed
        override fun turnMessagesOn(apps: Set<MessageApp>) {
            f = f.copy(messages = f.messages.copy(on = true, allowlist = MessageAllowlist(apps)))
            events += "messages on " + apps.joinToString { it.title }
        }
        override fun announce(on: Boolean) { f = f.copy(messages = f.messages.copy(announce = on)); events += "announce $on" }
        override fun memory(s: MemorySettings) { f = f.copy(memory = s); events += "memory on=${s.on} locked=${s.onLockScreen}" }
        override fun rekeyMemory(lockScreen: Boolean): Boolean { events += "rekey $lockScreen"; return true }
        override fun routines(s: RoutineSettings) { f = f.copy(routines = s); events += "routines brief=${s.briefEnabled} online=${s.briefOnline}" }
        override fun headset(s: HeadsetSettings) { f = f.copy(headset = s); events += "headset button=${s.buttonOpensRobot}" }
        override fun updates(s: UpdateSettings) { f = f.copy(updates = s); events += "updates auto=${s.autoCheck} test=${s.testBuilds}" }
        override fun browserEngine(e: BrowserEngine) { f = f.copy(browserEngine = e); events += "browser ${e.id}" }
    }

    /** A Pixel 10 Pro on a new install: nothing downloaded, Robot not the assistant yet, nothing allowed. */
    private val fresh = Snapshot(
        facts = facts(roleHeld = false, active = false, micGranted = false, notificationsGranted = false),
        phone = PIXEL_10_PRO,
        models = listOf(ModelStore.Snap(BrainModel.FAST, ModelStatus.MISSING)),
        voice = VoiceBundle.entries.map { VoiceModelStore.Snap(it, ModelStatus.MISSING, null, -1, null) },
        mt = MtModelStore.Snap(MtBundle.FR_EN, ModelStatus.MISSING, null, -1, null, true),
    )

    private var closed = 0

    private fun guide(stores: Stores, host: SettingsHost = SettingsHost(), snap: Snapshot = fresh, dark: Boolean = false) {
        rule.show(dark = dark) {
            SetupGuideScreen(host.now, snap, host::update, log.actions, stores, SnackbarHostState(), onClose = { closed++ })
        }
    }

    private fun next() = rule.onNode(hasText("Next") and hasClickAction()).performClick()

    private fun heading(t: String) = rule.onNode(hasText(t) and isHeading()).assertExists()

    /** A summary line: its title and its state with where Settings has it. */
    private fun line(title: String, state: String) = rule.onNode(hasText(title) and hasText(state)).assertExists()

    private val titles = listOf(
        "Welcome to Robot™", "What Robot can do", "Your phone and Robot's brain", "Voice", "Opening Robot", "How Robot asks first",
        "People, texts, email, calendar and notes", "Messages", "Memory", "Weather and places", "Conversation mode", "Routines and the morning brief",
        "Headset and car", "Helpers (optional)", "Web search and Termux (optional)", "Updates and feedback", "You're set up",
    )

    @Test fun nextGoesThroughEveryPageAndDoneFinishes() {
        val stores = Stores()
        val host = SettingsHost()
        guide(stores, host)
        assertEquals(SetupGuide.COUNT, titles.size)
        for ((i, t) in titles.withIndex()) {
            heading(t)
            rule.onNode(hasText("Step ${i + 1} of ${SetupGuide.COUNT}", substring = true)).assertExists()
            assertEquals(t, emptyList<String>(), rule.touchTargetProblems())
            rule.scrollToText("Privacy")
            rule.scrollToText("Find it later: ", substring = true)
            assertEquals(GuideStep.entries[i], stores.state.at)
            if (i < titles.lastIndex) next()
        }
        // Looking at every page wrote nothing.
        assertEquals(emptyList<RobotSettings>(), host.saved)
        assertEquals(emptyList<String>(), stores.events)
        assertTrue(log.isEmpty())
        rule.onNode(hasText("Skip this step")).assertDoesNotExist()
        rule.onNode(hasText("Done") and hasClickAction()).performClick()
        assertEquals(1, closed)
        assertNull(stores.state.at)
        assertEquals(SetupGuide.VERSION, stores.state.finished)
    }

    @Test fun backGoesUpAPageAndFromWelcomeCloses() {
        val stores = Stores(GuideStep.VOICE)
        guide(stores)
        rule.onNode(hasText("Back") and hasClickAction()).performClick()
        heading("Your phone and Robot's brain")
        assertEquals(GuideStep.PHONE, stores.state.at)
        rule.onNode(hasText("Back") and hasClickAction()).performClick()
        rule.onNode(hasText("Back") and hasClickAction()).performClick()
        heading("Welcome to Robot™")
        // Welcome has no Back button.
        rule.onNode(hasText("Back") and hasClickAction()).assertDoesNotExist()
        assertEquals(0, closed)
    }

    @Test fun skipKeepsTheDefaultsAndFinishLaterResumes() {
        val stores = Stores(GuideStep.OPENING)
        val host = SettingsHost()
        var shown by mutableStateOf(true)
        rule.show { if (shown) SetupGuideScreen(host.now, fresh, host::update, log.actions, stores, SnackbarHostState(), onClose = { shown = false }) }
        rule.onNode(hasText("Skip this step") and hasClickAction()).performClick()
        heading("How Robot asks first")
        assertEquals(GuideStep.ASKS_FIRST, stores.state.at)
        assertEquals(setOf(GuideStep.OPENING), stores.state.skipped)
        assertEquals(emptyList<RobotSettings>(), host.saved)
        rule.onNode(hasText("Finish later") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertFalse(shown)
        assertEquals(GuideStep.ASKS_FIRST, stores.state.at)
        // Opened again (Home's "Continue the setup guide"): the same page.
        stores.state = SetupGuide.open(stores.state)
        shown = true
        rule.waitForIdle()
        heading("How Robot asks first")
    }

    @Test fun thePhonePageNamesTheChipAndDownloadsItsFast() {
        guide(Stores(GuideStep.PHONE))
        rule.onNode(hasText("Pixel 10 Pro (Tensor G5)")).assertExists()
        rule.onNode(hasText("Fast: Gemma 4 E2B (Tensor G5), on the TPU", substring = true)).assertExists()
        rule.scrollToText("Robot's brain · 3.11 GB")
        rule.onNodeWithContentDescription("Download Robot's brain").performClick()
        assertEquals(listOf("download ${BrainModel.FAST}"), log)
    }

    @Test fun theVoicePageWritesTheVoiceAndPlaysASample() {
        val host = SettingsHost()
        guide(Stores(GuideStep.VOICE), host)
        rule.onNodeWithContentDescription("Download speech recognition").performClick()
        rule.scrollToText("Play a sample")
        rule.onRow("Play a sample").performClick()
        rule.onRow("Voice").performClick()
        rule.inDialog("Kitten").performClick()
        assertEquals(VoiceChoice.KITTEN.id, host.last.voice)
        assertEquals(listOf("voiceDownload ${VoiceBundle.STT}", "speakTest"), log)
    }

    @Test fun theOpeningPageSetsTheAssistantAndTheWakeWord() {
        val host = SettingsHost()
        guide(Stores(GuideStep.OPENING), host)
        rule.onNodeWithContentDescription("Set Robot as the digital assistant").performClick()
        rule.onNodeWithContentDescription("Allow the microphone and notifications").performClick()
        rule.scrollToText("Only while charging")
        rule.onRow("Only while charging").performClick()
        assertTrue(host.last.wakeOnlyWhileCharging)
        rule.onRow("Listen for “hey robot”").performClick()
        assertFalse(host.last.wakeWord)
        rule.onRow("Only while charging").assertIsNotEnabled()
        rule.scrollToText("Talk to Robot")
        rule.onRow("Talk to Robot").performClick()
        assertEquals(listOf("openAssistantSettings", "askPermissions", "talk null"), log)
    }

    @Test fun theAskFirstPageWritesTheCardsAndOffersTheDefaultsBack() {
        val host = SettingsHost(RobotSettings(approvals = RobotSettings().approvals + (ToolName.SET_TIMER to Approval.ASK)))
        guide(Stores(GuideStep.ASKS_FIRST), host)
        rule.onRow("When Robot asks first").performClick()
        rule.inDialog("Always after Robot reads something").performClick()
        assertEquals(SafetyCards.AFTER_READING, host.last.safetyCards)
        rule.onRow("Answer cards by voice").performClick()
        assertFalse(host.last.voiceCards)
        rule.scrollToText("Back to the defaults")
        rule.onRow("Back to the defaults").performClick()
        val d = RobotSettings()
        assertEquals(d.approvals, host.last.approvals)
        assertEquals(d.safetyCards, host.last.safetyCards)
        assertTrue(host.last.voiceCards)
        rule.onNode(hasText("Back to the defaults")).assertDoesNotExist()
        rule.onRow("Review each action").performClick()
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.ACTIONS)}"), log)
    }

    @Test fun thePeoplePageAsksOnlyWhenYouTap() {
        val host = SettingsHost()
        guide(Stores(GuideStep.PEOPLE), host)
        assertTrue(log.isEmpty())
        rule.onRow("Texting app").performClick()
        rule.inDialog("Off").performClick()
        assertEquals(TextApp.OFF, host.last.textApp)
        rule.scrollToText("Contacts")
        rule.onNodeWithContentDescription("Allow contacts").performClick()
        rule.onNodeWithContentDescription("Allow the calendar").performClick()
        rule.scrollToText("People")
        rule.onRow("People").performClick()
        assertEquals(listOf("askContacts", "askCalendar", "openPage ${Page.People}"), log)
    }

    @Test fun theMessagesPageTurnsReadingAndAnnouncementsOn() {
        val stores = Stores(GuideStep.MESSAGES)
        guide(stores)
        rule.onRow("Announce new messages").assertIsNotEnabled()
        rule.onRow("Read my messages").assert(hasText("First asks which of your apps Robot reads, as Settings › Messages does")).performClick()
        // 1.0: Settings › Messages' own window first (what Robot will do, your texting app ticked); nothing is written before Turn on.
        rule.inDialog("Read your messages?").assertExists()
        rule.inDialog("Robot will read the notifications of the apps you tick here", substring = true).assertExists()
        rule.inDialog("Cheogram").assertIsOn()
        assertEquals(emptyList<String>(), stores.events)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.inDialog("Turn on").performClick()
        rule.onRow("Notification access").assert(hasText("Needed: three steps in Android's settings")).performClick()
        rule.onRow("Announce new messages").performClick()
        assertEquals(listOf("messages on Cheogram", "announce true"), stores.events)
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)}"), log)
        // Off, and on again with the apps you had: no window.
        rule.onRow("Read my messages").performClick()
        rule.onRow("Read my messages").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf("messages false", "messages true"), stores.events.drop(2))
    }

    /** 1.0: another texting app only: nothing is ticked, and Turn on waits until you tick one; Cancel writes nothing. */
    @Test fun messagesNeverTurnOnWithNoApp() {
        val stores = Stores(GuideStep.MESSAGES).apply { installed = setOf(MessageApp.SIGNAL) }
        guide(stores)
        rule.onRow("Read my messages").performClick()
        rule.inDialog("Cancel").performClick()
        assertEquals(emptyList<String>(), stores.events)
        rule.onRow("Read my messages").performClick()
        rule.inDialog("Signal").assertIsOff()
        rule.inDialog("Turn on").assertIsNotEnabled()
        rule.inDialog("Signal").performClick()
        rule.inDialog("Turn on").assertIsEnabled().performClick()
        assertEquals(listOf("messages on Signal"), stores.events)
    }

    /** 1.0: none of the apps Robot reads is installed: no window, Messages stays off, and the page says so. */
    @Test fun withNoTextingAppMessagesStaysOff() {
        val stores = Stores(GuideStep.MESSAGES).apply { installed = emptySet() }
        guide(stores)
        rule.onRow("Read my messages").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithText("None of the apps Robot reads is installed", substring = true).assertExists()
        rule.onRow("Read my messages").assertIsOff()
        rule.onRow("Announce new messages").assertIsNotEnabled()
        assertEquals(emptyList<String>(), stores.events)
    }

    @Test fun theMemoryPageChangesTheKeyBeforeTheLockScreenSwitch() {
        val stores = Stores(GuideStep.MEMORY)
        guide(stores)
        rule.onRow("Use memory on the lock screen").performClick()
        rule.waitUntil(5_000) { stores.events.size == 2 }
        assertEquals(listOf("rekey true", "memory on=true locked=true"), stores.events)
        rule.onRow("Use memory").performClick()
        assertEquals("memory on=false locked=true", stores.events.last())
        rule.onRow("Use memory on the lock screen").assertIsNotEnabled()
    }

    @Test fun theWeatherPageWritesTheNearestPlaceAndAsksForLocationOnATap() {
        val host = SettingsHost()
        guide(Stores(GuideStep.WEATHER), host)
        rule.onRow("Home for weather").assert(hasText("Not set"))
        rule.onNodeWithContentDescription("Allow approximate location").performClick()
        rule.onRow("Find the nearest place online").performClick()
        rule.inDialog("Never").performClick()
        assertEquals(NearestOnline.NEVER, host.last.nearestOnline)
        assertEquals(listOf("askLocation"), log)
    }

    @Test fun theConversationPageTranslatesAndOffersItsDownloads() {
        val host = SettingsHost()
        guide(Stores(GuideStep.CONVERSATION), host)
        rule.onRow("Translate transcripts").performClick()
        assertTrue(host.last.convTranslate)
        rule.onNodeWithContentDescription("Download the speaker model").performClick()
        rule.onNodeWithContentDescription("Download the fast translation engine").performClick()
        assertEquals(listOf("voiceDownload ${VoiceBundle.SPEAKERS}", "mtDownload"), log)
    }

    @Test fun theBriefShowsItsCardBeforeItTurnsOn() {
        val stores = Stores(GuideStep.ROUTINES)
        guide(stores)
        rule.onRow("Morning brief by itself").performClick()
        rule.inDialog("Cancel").performClick()
        assertEquals(emptyList<String>(), stores.events)
        rule.onRow("Morning brief by itself").performClick()
        rule.inDialog("Online by itself", substring = true).assertExists()
        rule.inDialog("Turn on").performClick()
        assertEquals(listOf("routines brief=true online=true"), stores.events)
        // Off again goes straight through: the card was the consent.
        rule.onRow("Morning brief by itself").performClick()
        assertEquals("routines brief=false online=true", stores.events.last())
    }

    @Test fun theHeadsetAndUpdatesPagesWriteTheirFiles() {
        val stores = Stores(GuideStep.HEADSET_CAR)
        guide(stores)
        rule.onRow("Headset button opens Robot").performClick()
        rule.onRow("Car mode").performClick()
        repeat(3) { next() }
        heading("Updates and feedback")
        rule.onRow("Include test builds").performClick()
        rule.onRow("Check for updates automatically").performClick()
        rule.scrollToText("Send feedback")
        rule.onRow("Send feedback").assert(hasText("hey@robot-app.ai", substring = true))
        assertEquals(listOf("headset button=false", "updates auto=true test=true", "updates auto=false test=true"), stores.events)
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.CAR)}"), log)
    }

    @Test fun theOptionalPagesOnlyOpenTheirSettings() {
        guide(Stores(GuideStep.HELPERS))
        rule.onRow("Helpers").assert(hasText("None yet: add one")).performClick()
        next()
        rule.scrollToText("Web search")
        rule.onRow("Web search").performClick()
        rule.scrollToText("Termux")
        rule.onRow("Termux").performClick()
        assertEquals(listOf("openPage ${Page.Helpers}", "openPage ${Page.Settings(SettingsRoutes.WEB_SEARCH)}",
            "openPage ${Page.Settings(SettingsRoutes.TERMUX)}"), log)
    }

    /**
     * 1.0: the web page lists the engines a search in your browser opens, each with its privacy level, the default picked
     * here as in Settings; and the free services Robot's own search can use, with theirs.
     */
    @Test fun theWebPageListsTheEnginesAndTheFreeServicesWithTheirLevels() {
        val stores = Stores(GuideStep.WEB_TERMUX)
        guide(stores)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasText("Startpage") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(hasContentDescription("Privacy: High"))
        rule.scrollToText("DuckDuckGo")
        rule.onNode(hasText("DuckDuckGo") and hasClickAction()).performClick()
        assertEquals(listOf("browser duckduckgo"), stores.events)
        rule.onNode(hasText("DuckDuckGo") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        for ((name, level) in listOf("Google" to "Low", "Bing" to "Low", "Qwant" to "Medium", "Ecosia" to "Medium", "Mojeek" to "High", "Brave Search" to "High")) {
            rule.scrollToText(name)
            rule.onNode(hasText(name) and hasClickAction()).assert(hasContentDescription("Privacy: $level"))
        }
        for ((name, level) in listOf("DuckDuckGo Instant Answers" to "High", "Wikipedia only" to "Medium", "A public SearXNG" to "Low",
            "Brave Search API" to "Medium", "Your own SearXNG" to "High")) {
            rule.scrollToText(name)
            rule.onNode(hasText(name)).assert(hasContentDescription("Privacy: $level"))
        }
        rule.scrollToText("A search in your browser goes from your browser", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun whatRobotCanDoListsTenExamples() {
        guide(Stores(GuideStep.CAN_DO))
        assertEquals(10, EXAMPLES.size)
        for (say in listOf("“Transcribe this meeting”, then “summarize this conversation”", "“Be my interpreter”", "“Read my messages”, then " +
            "“reply in French: I'll be there at six”", "“Run backup”", "In the car: “navigate home”")) {
            rule.scrollToText(say, substring = true)
        }
        rule.scrollToText("Off until you set it up: Settings › Web search")
        rule.scrollToText("Online only to send your search words and read up to 3 pages")
    }

    @Test fun theSummaryShowsWhatIsOnNowAndWhere() {
        val host = SettingsHost(RobotSettings(wakeWord = false, voice = VoiceChoice.KITTEN.id))
        val stores = Stores(GuideStep.SUMMARY, GuideFiles(messages = MessageSettings(on = true), routines = RoutineSettings(briefEnabled = true),
            updates = UpdateSettings(testBuilds = true)))
        guide(stores, host)
        line("“Hey robot”", "Off · Settings › Wake word")
        line("Robot's voice", "Kitten · Settings › Voice")
        line("Robot's brain", "Not downloaded yet · Settings › Model (the Model tab)")
        rule.scrollToText("Read my messages")
        line("Read my messages", "On · Settings › Messages")
        rule.scrollToText("Morning brief by itself")
        line("Morning brief by itself", "On · Settings › Routines")
        rule.scrollToText("Web search")
        line("Web search", "Not set up · Settings › Web search")
        // 1.0: a search in your browser needs no set-up: the engine it uses.
        rule.scrollToText("Search in your browser")
        line("Search in your browser", "Startpage · Settings › Web search")
        rule.scrollToText("Test builds")
        line("Test builds", "On · Settings › About › Updates")
        rule.scrollToText("“Read my messages” · « Lis mes messages »")
        rule.scrollToText("“Be my interpreter”")
    }

    @Test fun homeOffersToContinueOrRunItAgain() {
        var g by mutableStateOf(GuideState(seen = 1, at = GuideStep.MEMORY))
        rule.show { HomeScreen(fresh, emptyList(), log.actions, androidx.compose.ui.Modifier, guide = g) }
        rule.onNode(hasText("Step 9 of 17: pick up where you left off")).assertExists()
        rule.onNode(hasText("Continue the setup guide") and hasClickAction()).performClick()
        rule.onNode(hasText("Run the setup guide again")).assertDoesNotExist()
        g = SetupGuide.finish(g)
        rule.waitForIdle()
        rule.onNode(hasText("Continue the setup guide")).assertDoesNotExist()
        rule.onNode(hasClickAction() and hasText("Run the setup guide again")).performScrollTo().performClick()
        assertEquals(listOf("openGuide", "openGuide"), log)
    }

    @Test fun aboutOpensTheGuide() {
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        rule.onRow("Setup guide").performClick()
        assertEquals(listOf("openGuide"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    // Goldens: What Robot can do (light), Voice (dark), How Robot asks first at 200 % font, the summary (light).

    @Test fun canDoLight() {
        guide(Stores(GuideStep.CAN_DO))
        rule.shot("guide_can_do_light")
    }

    @Test fun voiceDark() {
        guide(Stores(GuideStep.VOICE), dark = true)
        rule.shot("guide_voice_dark")
    }

    @Test @Config(fontScale = 2f)
    fun asksFirstFont200() {
        guide(Stores(GuideStep.ASKS_FIRST))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Find it later: ", substring = true)
        rule.shot("guide_asks_font200")
    }

    @Test fun summaryLight() {
        guide(Stores(GuideStep.SUMMARY))
        rule.shot("guide_summary_light")
    }
}
