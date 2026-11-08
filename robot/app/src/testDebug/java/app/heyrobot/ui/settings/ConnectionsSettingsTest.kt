// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnOp
import app.heyrobot.core.connect.ConnSetup
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HttpMethod
import app.heyrobot.core.connect.OpParam
import app.heyrobot.core.connect.OpSpec
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.core.connect.SkillEditor
import app.heyrobot.core.connect.StepOffer
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.skills.ParamType
import app.heyrobot.sys.ConnWindow
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * C9, Settings › Skills › Connections (plan §6.2, §6.3, Test Bench CN1's set-up): the list with each connection's state,
 * Add a connection by kind, a token refused over http before anything is saved, the key pasted and Test run in the
 * connection window (never on these pages), Home Assistant's operations picked there, a secret webhook address typed
 * there too, the webhook's request form, Forget, Used by, and Remove with what it leaves needing attention.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ConnectionsSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val calls = ArrayList<String>()
    private val saved = ArrayList<Connection>()
    private val now = ZonedDateTime.of(2026, 10, 27, 12, 0, 0, 0, ZoneId.of("America/Halifax"))

    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(ConnOp("light_off", "Turn off the downstairs lights", OpSpec.HaCall("light", "turn_off"),
            mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.downstairs"), default = "light.downstairs")))))
    private val ntfy = ConnSetup.create("c-nt0001", ConnKind.NTFY, "family ntfy", "https://ntfy.example.org", ntfyTitle = "Tell the family").first!!
        .copy(tested = ConnTested(now.toInstant().toEpochMilli() - 60_000), answers = mapOf("https://ntfy.example.org" to app.heyrobot.core.connect.ConsentAnswer.ALLOW))
    private val skill = SkillEditor.newSkill("s-good01", "Good night", SkillEditor.step(SkillEditor.offers(listOf(ha), emptyList()).first { it is StepOffer.Operation }),
        1L, Lang.EN)

    private fun data(list: List<Connection> = listOf(ha, ntfy)) = ConnData(list, keys = mapOf(ha.id to false, ntfy.id to true),
        masks = mapOf(ntfy.id to mapOf(SecretPart.TOPIC to "…AB12")), skills = listOf(skill))

    private fun actions() = ConnSettingsActions(open = { calls += "open $it" }, save = { saved += it; calls += "save ${it.name}"; true },
        readdress = { calls += "readdress"; true }, remove = { calls += "remove $it" }, window = { id, mode, name -> calls += "window $mode ${name ?: id}" },
        forget = { id, o -> calls += "forget $id $o" }, startOver = { calls += "startOver" })

    /** C11: a connections.json Robot can't read offers Start over, which asks first; nothing can be added until then. */
    @Test fun theWebhookFormsChoicesSurviveARecreate() {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        fun <T : Any> kept(s: androidx.compose.runtime.saveable.Saver<T, ArrayList<String>>, v: T): T? = s.restore(with(s) { scope.save(v) }!!)
        val marks = setOf(app.heyrobot.core.skills.SkillEffect.MONEY, app.heyrobot.core.skills.SkillEffect.SECURITY)
        assertEquals(marks, kept(MARKS_SAVER, marks))
        val types = mapOf("room" to ParamType.CHOICE, "n" to ParamType.INTEGER)
        assertEquals(types, kept(ARG_TYPES_SAVER, types))
        val limits = mapOf("room" to "a, b", "n" to "")
        assertEquals(limits, kept(ARG_LIMITS_SAVER, limits))
    }

    @Test fun aFileThatCantBeReadOffersStartOver() {
        rule.show { ConnectionsScreen(ConnData(problem = app.heyrobot.core.connect.ConnFile.Damaged), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Robot couldn't read your connections, so none is used. Skills that need one wait.").assertExists()
        rule.onNodeWithText("Add a connection").assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Start over").performClick()
        rule.onNodeWithText("Robot keeps the file it couldn't read aside as connections.json.bad, deletes your connections' keys, and you start with no connections.")
            .assertExists()
        rule.inDialog("Start over").performClick()
        assertEquals(listOf("startOver"), calls)
    }

    /** A newer Robot's file is left as it is: update Robot, no Start over. */
    @Test fun aNewerFileIsLeftAlone() {
        rule.show { ConnectionsScreen(ConnData(problem = app.heyrobot.core.connect.ConnFile.TooNew(2)), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Your connections are from a newer Robot. Update Robot to use them.").assertExists()
        rule.onNodeWithText("Start over").assertDoesNotExist()
    }

    @Test fun theListShowsEachStateAndTheKindsToAdd() {
        rule.show { ConnectionsScreen(data(), actions(), SnackbarHostState(), onBack = {}, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Home Assistant · Needs its key").assertExists()
        rule.onNodeWithText("ntfy · Tested today").assertExists()
        rule.onRow("family ntfy").performClick()
        assertEquals("open skills/connections/c-nt0001", calls.last())
        rule.scrollToText("App hand-off")
        rule.onNodeWithText("Gets the travel time home or to work from a routing server you run. Where you are goes to it, to about 1 km.").assertExists()
        rule.onRow("Webhook").performClick()
        assertEquals("open skills/connections/add/webhook", calls.last())
    }

    /** fix-skills13-security-c 2 (the user, 7 Nov: "Make sure its known it's not encrypted"): the list row says it too. */
    @Test fun anMcpServerOnThisPhoneOverPlainHttpSaysNotEncryptedOnItsListRow() {
        val local = Connection("c-mc0001", ConnKind.MCP, "Phone tools", ConnUrl.parse("http://127.0.0.1:8080/mcp")!!, auth = ConnAuth.Bearer)
        val tls = Connection("c-mc0002", ConnKind.MCP, "Tailnet tools", ConnUrl.parse("https://tools.tail1234.ts.net/mcp")!!, auth = ConnAuth.Bearer)
        rule.show { ConnectionsScreen(data(listOf(local, tls)), actions(), SnackbarHostState(), onBack = {}, now = now) }
        val label = " · Not encrypted (on this phone)"
        assertEquals(1, rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText(label, substring = true).assertTextContains("Phone tools")
        rule.onNodeWithText("Tailnet tools").assertExists()
    }

    @Test fun aTokenIsRefusedOverHttpAndTheKeyIsPastedInTheWindow() {
        rule.show { AddConnectionScreen(ConnKind.HOME_ASSISTANT, data(emptyList()), actions(), SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText("Robot needs https with a certificate Android trusts: Tailscale HTTPS, your own domain, or Home Assistant Cloud.").assertExists()
        val address = hasText("Address") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        rule.onNode(address).performTextReplacement("http://192.168.1.5:8123")
        rule.onRow("Save").performClick()
        rule.onNodeWithText("Robot won't send your Home Assistant key over plain http: use an https address.").assertExists()
        assertTrue(saved.isEmpty())
        rule.onNode(address).performTextReplacement("https://home.tail1234.ts.net")
        rule.onRow("Save").performClick()
        val c = saved.single()
        assertEquals("https://home.tail1234.ts.net", c.address!!.text)
        assertEquals(ConnAuth.Bearer, c.auth)
        assertEquals(listOf("save Home Assistant", "open skills/connections/${c.id}", "window ${ConnWindow.MODE_KEY} ${c.id}"), calls)
    }

    /** fix-skills13-security 1 and 4: an MCP token over http only to this phone or Tailscale; a server on this phone can be stood in for. */
    @Test fun anMcpServerSaysWhereItsTokenGoesAndThatOneOnThisPhoneCanBeStoodInFor() {
        rule.show { AddConnectionScreen(ConnKind.MCP, data(emptyList()), actions(), SnackbarHostState(), onBack = {}) }
        val address = hasText("Address") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        val plain = "Robot reaches an MCP server only over https with a valid certificate, even at home: plain http isn't encrypted. Only a server on this phone may use plain http."
        val clear = "Not encrypted (on this phone): plain http, which other apps on the phone could read."
        val phone = "This server is on this phone. While it's stopped, another app could listen in its place and receive your token."
        rule.onNode(address).performTextReplacement("http://192.168.1.5:8123/api/mcp")
        rule.onNodeWithText(plain).assertExists()
        rule.onNodeWithText(phone).assertDoesNotExist()
        rule.onRow("Save").performClick()
        assertTrue(saved.isEmpty())
        rule.onNode(address).performTextReplacement("http://127.0.0.1:8080/mcp")
        rule.onNodeWithText(phone).assertExists()
        rule.onNodeWithText(plain).assertDoesNotExist()
        rule.onNodeWithText(clear).assertExists()
        rule.onNode(address).performTextReplacement("https://127.0.0.1:8080/mcp")
        rule.onNodeWithText(phone).assertExists()
        rule.onNodeWithText(plain).assertDoesNotExist()
        rule.onNodeWithText(clear).assertDoesNotExist()
    }

    @Test fun aSecretWebhookAddressIsTypedOnlyInTheWindow() {
        rule.show { AddConnectionScreen(ConnKind.WEBHOOK, data(emptyList()), actions(), SnackbarHostState(), onBack = {}) }
        rule.onRow("This address holds a secret").performClick()
        rule.onNodeWithText("You paste the whole address next, in the key window. Only its server's name is kept here.").assertExists()
        rule.onNode(hasText("Address") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertDoesNotExist()
        rule.onRow("Save").performClick()
        assertTrue(saved.isEmpty())
        assertEquals("window ${ConnWindow.MODE_NEW_HOOK} Webhook", calls.single())
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aConnectionsPageOpensTheWindowForKeysTestAndOperations() {
        rule.show { ConnectionPageScreen(ha, data(), actions(), SnackbarHostState(), onBack = { calls += "back" }, now = now) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Paste the key").performClick()
        assertEquals("window ${ConnWindow.MODE_KEY} c-ha0001", calls.last())
        rule.onRow("Test").performClick()
        assertEquals("window ${ConnWindow.MODE_TEST} c-ha0001", calls.last())
        rule.scrollToText("Turn off the downstairs lights")
        rule.onNodeWithText("changes your home").assertExists()
        rule.scrollToText("Add an operation")
        rule.onRow("Add an operation").performClick()
        assertEquals("window ${ConnWindow.MODE_PICK} c-ha0001", calls.last())
        rule.scrollToText("Good night")
        rule.onRow("Good night").performClick()
        assertEquals("open skills/s-good01", calls.last())
        rule.scrollToText("Remove connection")
        rule.onRow("Remove connection").performClick()
        rule.inDialog("Its key is deleted, and 1 skill will need attention.").assertExists()
        rule.inDialog("Remove connection").performClick()
        assertEquals(listOf("remove c-ha0001", "back"), calls.takeLast(2))
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theHttpsHelpNamesTheUserGuidesSteps() {
        // C13 (C9's difference 4): the footer points to the guide's steps in words; nothing opens a page.
        rule.show { ConnectionPageScreen(ha, data(), actions(), SnackbarHostState(), onBack = {}, now = now) }
        val footer = "Robot needs https with a certificate Android trusts: Tailscale HTTPS, your own domain, or Home Assistant Cloud. " +
            "The user guide's “Connections over https” steps show how to set up each one."
        rule.scrollToText(footer)
        rule.onNodeWithText(footer).assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theTopicStaysMaskedAndAnAnswerCanBeForgotten() {
        rule.show { ConnectionPageScreen(ntfy, data(), actions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Saved (…AB12)").assertExists()
        rule.scrollToText("https://ntfy.example.org", substring = false)
        rule.scrollToText("Forget")
        rule.onRow("Forget").performClick()
        assertEquals("forget c-nt0001 https://ntfy.example.org", calls.last())
        rule.scrollToText("A spoken yes may approve its changes")
        rule.onRow("A spoken yes may approve its changes").performClick()
        assertEquals(false, saved.last().spokenYes)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theWebhooksRequestIsSavedOnlyWhenItsRulesPass() {
        val hook = Connection("c-wh0001", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://n8n.example.org")!!)
        rule.show { WebhookFormScreen(hook, actions(), SnackbarHostState(), onBack = { calls += "back" }) }
        rule.onNode(hasText("Title") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("Log a run")
        rule.onNode(hasText("Path") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("/hook/{{room}}")
        rule.scrollToText("room")
        rule.scrollToText("Save")
        rule.onRow("Save").performClick()
        val op = saved.single().ops.single()
        assertEquals("Log a run", op.title)
        assertEquals(HttpMethod.POST, (op.spec as OpSpec.Webhook).method)
        assertEquals(setOf("room"), op.params.keys)
        assertEquals("back", calls.last())
    }
}
