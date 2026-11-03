// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.heyrobot.core.Person
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.BridgeChoice
import app.heyrobot.core.link.BridgeLine
import app.heyrobot.core.link.CodeState
import app.heyrobot.core.link.EventAlone
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkReach
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.Verified
import app.heyrobot.sys.LinkStatus
import app.heyrobot.sys.MadeCodeReply
import app.heyrobot.sys.UsedCodeReply
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import androidx.compose.ui.test.onAllNodesWithText
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 1.5, Robot to Robot's screens (robot-to-robot-plan.md §6.1 to §6.3; job L5) over a port that stands in for :link: turning
 * it on asks the name other Robots see and makes the keys; the people paired show by their People name, else as what their
 * Robot says; a pairing's Robot link raises at once and asks to lower, answers a move only after comparing, keeps an ended
 * note with Remove; the permissions page never offers Answer on its own for contact cards; the safety number reads in 12
 * groups; and pairing at a distance goes code, who is this, safety number, preset, then the permissions page. L-bridges:
 * Tor bridges, Off until you choose, and your own bridge saved only as a bridge line Tor can use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = PHONE)
class LinkScreensTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val sam = Person(UUID.randomUUID().toString(), "Sam Tremblay")

    internal class FakePort(var settings: LinkSettings = LinkSettings()) : LinkPort {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        var views = listOf<PairingView>()
        var people = listOf<Person>()
        var codeState: Pair<CodeState, String?> = CodeState.OPEN to null
        var ready = false
        var vault: Int? = null
        var holds = false
        override fun holdsState() = holds
        override fun settings() = settings
        override fun saveSettings(change: (LinkSettings) -> LinkSettings): Boolean { settings = change(settings); calls += "save"; return true }
        override fun status() = LinkStatus(vault ?: if (ready) LinkIpcProtocol.VAULT_READY else LinkIpcProtocol.VAULT_OFF, settings.on, true, views.size, 0, 0, 0)
        override fun setUp(): Boolean { calls += "setUp"; ready = true; return true }
        override fun startOver(): Boolean { calls += "startOver"; return true }
        override fun pairings() = views
        override fun blocked() = listOf(BlockedView(LinkIds.newId(), "Bob", 1_790_000_000L))
        override fun people() = people
        override fun addPerson(name: String): Person? = Person(UUID.randomUUID().toString(), name).also { people = people + it; calls += "addPerson" }
        override fun makeCode(mode: LinkMode, inPerson: Boolean): MadeCodeReply { calls += "makeCode $mode $inPerson"; return MadeCodeReply(MakeCodeResult.OK, CODE_ID, "ROBOT-PAIR-AbCdEf") }
        override fun useCode(text: String, form: PairCodeForm, mode: LinkMode): UsedCodeReply { calls += "useCode $form"; return UsedCodeReply(UseCodeResult.EXPIRED, null) }
        override fun codeState(id: String) = codeState
        override fun cancelCode(id: String): Boolean { calls += "cancelCode"; return true }
        override fun cancelCodes(): Boolean { calls += "cancelCodes"; return true }
        override fun readQr(photo: ByteArray): String? = null
        override fun safety(pairing: String) = "12345678901234567890123456789012345678901234567890123456789".let { it + "0" }
        override fun verify(pairing: String): Boolean { calls += "verify"; return true }
        override fun setPerson(pairing: String, person: String?): Boolean { calls += "setPerson $person"; return true }
        override fun setPreset(pairing: String, preset: LinkPreset): Boolean { calls += "setPreset $preset"; return true }
        override fun setLevel(pairing: String, cap: LinkCapability, level: LinkLevel): Boolean { calls += "setLevel $cap $level"; return true }
        override fun raise(pairing: String): Boolean { calls += "raise"; return true }
        override fun lower(pairing: String): Boolean { calls += "lower"; return true }
        override fun keepMode(pairing: String): Boolean { calls += "keepMode"; return true }
        override fun unpair(pairing: String): Boolean { calls += "unpair"; return true }
        override fun block(pairing: String, label: String): Boolean { calls += "block $label"; return true }
        override fun unblock(id: String): Boolean { calls += "unblock"; return true }
        override fun answerMoved(pairing: String, accept: Boolean): Boolean { calls += "answerMoved $accept"; return true }
        override fun sendMoved(pairing: String): Boolean { calls += "sendMoved $pairing"; return true }
        var push: app.heyrobot.core.link.PushApps? = null
        override fun pushApps() = push
        override fun choosePush(pkg: String): Boolean { calls += "choosePush $pkg"; return true }
        override fun close() {}
    }

    private fun view(id: String = LinkIds.newId(), phase: PairingPhase = PairingPhase.ACTIVE, verified: Verified = Verified.NO, person: String? = null,
        claimed: String = "Sam", mode: LinkMode = LinkMode.PRIVATE, preset: LinkPreset? = null, lowerOffered: Boolean = false) =
        PairingView(id, phase, mode, 1_790_000_000L, claimed, person, verified, if (verified == Verified.NO) 0 else 1_790_000_100L, preset,
            LinkLevels.of(preset ?: LinkPreset.SOMEONE_ELSE), false, lowerOffered, 0, if (phase == PairingPhase.MOVED) "Sam" else "", if (phase == PairingPhase.ACTIVE) 0 else 1_790_000_500L)

    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** F59: a state that won't read shows its warning, not an empty healthy page, and Start over says no one is told. */
    @Test fun damagedStateWarnsAndStartOverSaysNoOneIsTold() {
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply { vault = LinkIpcProtocol.VAULT_GONE }
        rule.setContent { LinkSettingsScreen(port, ActionLog().actions, SnackbarHostState()) {} }
        waitFor("can't be read")
        rule.onNode(hasText("can't be read", substring = true)).performClick()
        waitFor("no one is told")
        assertEquals(emptyList<String>(), port.calls.filter { it == "startOver" })
    }

    /** F59: with the switch off, Start over is there when something is sealed here, and only then. */
    @Test fun startOverWithTheSwitchOff() {
        val port = FakePort(LinkSettings(on = false, name = "Matt")).apply { holds = true }
        rule.setContent { LinkSettingsScreen(port, ActionLog().actions, SnackbarHostState()) {} }
        waitFor("everything it saved")
        rule.onNode(hasText("everything it saved", substring = true)).performClick()
        waitFor("no one is told")
        rule.onAllNodes(hasText("Start over")).onLast().performClick()
        rule.waitUntil(5_000) { "startOver" in port.calls && "save" in port.calls }
        // "Everything it saved": the name goes too, so turning on again asks for it.
        assertEquals("", port.settings.name)
        assertEquals(false, port.settings.on)
    }

    /** "Everything it saved" means the file too: your own bridge lines and every other setting go back to their defaults. */
    @Test fun startOverClearsEveryLinkSetting() {
        val own = listOf(BridgeLine.parse("192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567")!!)
        val saved = LinkSettings(on = false, name = "Matt", reach = LinkReach.TOR, whileLocked = false,
            hours = EventAlone.Hours(LocalTime.of(10, 0), LocalTime.of(16, 0)), brief = false, busyCalendars = setOf(3L),
            bridges = BridgeChoice.OWN, ownBridges = own)
        val port = FakePort(saved).apply { holds = true }
        rule.setContent { LinkSettingsScreen(port, ActionLog().actions, SnackbarHostState()) {} }
        waitFor("everything it saved")
        rule.onNode(hasText("everything it saved", substring = true)).performClick()
        waitFor("no one is told")
        rule.onAllNodes(hasText("Start over")).onLast().performClick()
        rule.waitUntil(5_000) { "startOver" in port.calls && "save" in port.calls }
        assertEquals(LinkSettings.CLOSED, port.settings)
    }

    /** F59: turned on over a state that won't read, the switch stays on so the warning shows. */
    @Test fun turningOnOverDamageStaysOnToWarn() {
        val port = object : LinkPort by FakePort() {
            val inner = FakePort(LinkSettings(on = false, name = "Matt")).apply { vault = LinkIpcProtocol.VAULT_GONE; holds = true }
            override fun settings() = inner.settings
            override fun saveSettings(change: (LinkSettings) -> LinkSettings) = inner.saveSettings(change)
            override fun status() = inner.status()
            override fun holdsState() = inner.holdsState()
            override fun setUp() = false
        }
        rule.setContent { LinkSettingsScreen(port, ActionLog().actions, SnackbarHostState()) {} }
        waitFor("everything it saved")
        rule.onNode(hasText("Use Robot to Robot", substring = true)).performClick()
        waitFor("can't be read")
        assertEquals(true, port.inner.settings.on)
    }

    // Robolectric settles with a text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun turningItOnAsksTheNameAndMakesTheKeys() {
        val port = FakePort()
        rule.show { LinkSettingsScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Use Robot to Robot")
        rule.onNodeWithText("Use Robot to Robot").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Matt")
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "setUp" in port.calls }
        assertTrue(port.settings.on)
        assertEquals("Matt", port.settings.name)
    }

    @Test fun thePeoplePairedShowByTheirPeopleNameElseAsWhatTheirRobotSays() {
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true; people = listOf(sam); views = listOf(view(person = sam.id, verified = Verified.IN_PERSON), view(claimed = "Bob")) }
        rule.show { LinkSettingsScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Sam Tremblay")
        rule.scrollToText("Says they're “Bob”")
        rule.onRow("Sam Tremblay").performClick()
        assertTrue(log.any { it.startsWith("openPage Settings(route=link/p/") })
        rule.scrollToText("Unblock")
        // L7: the page grew below Blocked (plans' hours, the calendars other Robots see as busy): back up to Pair.
        rule.scrollToText("Pair with someone")
        rule.onRow("Pair with someone").performClick()
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.PAIR)}"))
    }

    @Test fun torBridgesAreOffUntilYouChoose() {
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true }
        rule.show { LinkSettingsScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Matt")
        rule.scrollToText("Tor bridges")
        rule.onRow("Tor bridges").performClick()
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.BRIDGES)}"))
    }

    // Robolectric settles with a text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theBridgesPageSavesAChoiceAndOnlyARealBridgeLine() {
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true }
        rule.show { BridgesScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Snowflake")
        rule.onRow("Snowflake").performClick()
        rule.waitUntil(5_000) { port.settings.bridges == app.heyrobot.core.link.BridgeChoice.SNOWFLAKE }
        // Your own bridge with no line yet asks for one; what isn't a bridge line can't be saved.
        rule.scrollToText("Your own bridge")
        rule.onRow("Your own bridge").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("obfs4 not a bridge\nControlPort 9051")
        waitFor("Robot can't use that as a bridge line")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(app.heyrobot.core.link.BridgeChoice.SNOWFLAKE, port.settings.bridges)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun yourOwnBridgeLineIsSavedAndChosen() {
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true }
        rule.show { BridgesScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Your own bridge")
        rule.scrollToText("Your own bridge")
        rule.onRow("Your own bridge").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Bridge obfs4 203.0.113.7:9443 0123456789ABCDEF0123456789ABCDEF01234567 cert=AbCd iat-mode=0")
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { port.settings.bridges == app.heyrobot.core.link.BridgeChoice.OWN }
        assertEquals(listOf("obfs4 203.0.113.7:9443 0123456789ABCDEF0123456789ABCDEF01234567 cert=AbCd iat-mode=0"), port.settings.ownBridges.map { it.text })
    }

    @Test fun aPairingsRobotLinkRaisesAtOnceAndAsksToLower() {
        val id = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true)).apply { people = listOf(sam); views = listOf(view(id, person = sam.id)) }
        rule.show { PairingScreen(port, id, log.actions, SnackbarHostState(), {}, pollMs = 0) }
        waitFor("Not verified")
        rule.scrollToText("Raise to Anonymous")
        rule.onRow("Raise to Anonymous").performClick()
        rule.waitUntil(5_000) { "raise" in port.calls }
        port.views = listOf(view(id, person = sam.id, mode = LinkMode.ANONYMOUS, lowerOffered = true))
        rule.onRow("Compare safety number").performClick()
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.safety(id))}"))
    }

    /** F27: Robot never picks a push app, even the only one: you see its name and package, and your tap saves it. */
    @Test fun aPushAppIsSavedOnlyByYourTapWithItsNameShown() {
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply {
            ready = true
            push = app.heyrobot.core.link.PushApps(null, 0, listOf(app.heyrobot.core.link.PushApp("org.example.push", "Example Push")))
        }
        rule.show { LinkSettingsScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Push app")
        rule.onNode(hasText("Push app") and hasClickAction()).performClick()
        waitFor("Example Push")
        rule.onNodeWithText("org.example.push").assertExists()
        assertTrue(port.calls.none { it.startsWith("choosePush") })
        rule.onNodeWithText("Example Push").performClick()
        rule.waitUntil(5_000) { "choosePush org.example.push" in port.calls }
    }

    /** L12: restored pairings wait for this tap, and only then does anything go out. No such pairing, no row. */
    @Test fun restoredPairingsAreToldOnlyByYourTap() {
        val a = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true; people = listOf(sam); views = listOf(view(a, phase = PairingPhase.MOVING, person = sam.id), view()) }
        rule.show { LinkSettingsScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Sam Tremblay")
        rule.scrollToText("came from your backup", substring = true)
        assertTrue(port.calls.none { it.startsWith("sendMoved") })
        rule.onNode(hasText("Tell my Robots I moved") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "sendMoved $a" in port.calls }
        assertEquals(1, port.calls.count { it.startsWith("sendMoved") })
    }

    @Test fun aMoveIsAnsweredOnlyAfterComparingAndAnEndedNoteCanBeRemoved() {
        val id = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true)).apply { people = listOf(sam); views = listOf(view(id, phase = PairingPhase.MOVED, person = sam.id)) }
        rule.show { PairingScreen(port, id, log.actions, SnackbarHostState(), {}, pollMs = 0) }
        waitFor("says it moved to a new phone")
        // Accepting goes through the safety number; keeping the old phone is here.
        rule.onNode(hasText("Compare safety number") and hasClickAction()).performClick()
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.safety(id))}"))
        rule.onNode(hasText("Keep the old phone") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "answerMoved false" in port.calls }
        assertTrue(port.calls.none { it == "answerMoved true" })
    }

    @Test fun anEndedNoteOffersRemoveAndBlock() {
        val id = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true)).apply { people = listOf(sam); views = listOf(view(id, phase = PairingPhase.ENDED, person = sam.id)) }
        rule.show { PairingScreen(port, id, log.actions, SnackbarHostState(), {}, pollMs = 0) }
        waitFor("Sam Tremblay's Robot unpaired")
        rule.onNode(hasText("Remove") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "unpair" in port.calls }
    }

    @Test fun thePermissionsPageNeverOffersContactCardsOnTheirOwn() {
        val id = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true)).apply { people = listOf(sam); views = listOf(view(id, person = sam.id, preset = LinkPreset.FAMILY_OR_FRIEND)) }
        rule.show { PermissionsScreen(port, id, log.actions, SnackbarHostState()) {} }
        waitFor("Free and busy times")
        rule.scrollToText("Contact cards")
        rule.onRow("Contact cards").performClick()
        rule.inDialog("Ask me first")
        assertTrue(rule.onAllNodes(hasText("Answer on its own")).fetchSemanticsNodes().isEmpty())
        rule.inDialog("Off").performClick()
        rule.waitUntil(5_000) { "setLevel CONTACT_CARDS OFF" in port.calls }
        rule.scrollToText("Free and busy times")
        rule.onRow("Free and busy times").performClick()
        rule.inDialog("Answer on its own").performClick()
        rule.waitUntil(5_000) { "setLevel FREE_BUSY AUTO" in port.calls }
    }

    @Test fun theSafetyNumberReadsInGroupsAndTheyMatchVerifies() {
        val id = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true)).apply { people = listOf(sam); views = listOf(view(id, person = sam.id)) }
        var back = false
        rule.show { SafetyScreen(port, id, log.actions, SnackbarHostState()) { back = true } }
        waitFor("12345   67890   12345")
        rule.onNode(hasText("They match") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "verify" in port.calls && back }
    }

    /** L5-fix: in person, a hello that came before your photo of their code waits for it, and the screen says so. */
    @Test fun inPersonAHeldHelloAsksForYourPhoto() {
        val port = FakePort(LinkSettings(on = true)).apply { ready = true; people = listOf(sam) }
        rule.show { PairScreen(port, log.actions, SnackbarHostState(), {}, pollMs = 20) }
        waitFor("Anonymous")
        rule.onRow("Meet in person").performClick()
        waitFor("Your code")
        assertTrue("makeCode PRIVATE true" in port.calls)
        port.codeState = CodeState.HELD to null
        waitFor("Their Robot said hello. Now take a photo of their code")
    }

    /** F148: a code out of tries says so and offers a new one, never an endless "making your code". */
    @Test fun inPersonACodeOutOfTriesGetsTryAgain() {
        val port = FakePort(LinkSettings(on = true)).apply { ready = true; people = listOf(sam) }
        rule.show { PairScreen(port, log.actions, SnackbarHostState(), {}, pollMs = 20) }
        waitFor("Anonymous")
        rule.onRow("Meet in person").performClick()
        waitFor("Your code")
        port.codeState = CodeState.MISMATCH to null
        waitFor("This code doesn't match")
        assertTrue(rule.onAllNodesWithText("Making your code…").fetchSemanticsNodes().isEmpty())
        port.codeState = CodeState.OPEN to null
        rule.onNode(hasText("Try again") and hasClickAction()).performClick()
        waitFor("Your code")
        assertEquals(2, port.calls.count { it == "makeCode PRIVATE true" })
    }

    @Test fun atADistanceTheCodeThenWhoThenTheNumberThenAPreset() {
        val id = LinkIds.newId()
        val port = FakePort(LinkSettings(on = true)).apply { ready = true; people = listOf(sam) }
        var back = false
        rule.show { PairScreen(port, log.actions, SnackbarHostState(), { back = true }, pollMs = 20) }
        waitFor("Anonymous")
        rule.onRow("At a distance").performClick()
        rule.onRow("Share code").performClick()
        waitFor("ROBOT-PAIR-AbCdEf")
        waitFor("Works once, for 24 hours.")
        assertTrue("makeCode PRIVATE false" in port.calls)
        // Someone used it: their claim is shown as what it says, and picks nobody.
        port.views = listOf(view(id))
        port.codeState = CodeState.USED to id
        waitFor("They call themselves “Sam”.")
        rule.onRow("Sam Tremblay").performClick()
        rule.waitUntil(5_000) { "setPerson ${sam.id}" in port.calls }
        waitFor("67890")
        rule.onNode(hasText("Compare later") and hasClickAction()).performClick()
        waitFor("Family or friend")
        assertTrue("verify" !in port.calls)
        rule.onNode(hasText("Family or friend") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "setPreset FAMILY_OR_FRIEND" in port.calls && back }
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.permissions(id))}"))
    }

    private companion object {
        val CODE_ID: String = LinkIds.newId()
    }
}
