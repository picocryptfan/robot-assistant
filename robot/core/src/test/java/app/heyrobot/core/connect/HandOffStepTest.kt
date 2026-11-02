// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.HandOffCall
import app.heyrobot.core.HandOffStepAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.messages.SignerNow
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C7, plan §3.5, D14: a hand-off step before its card and after its launch. Its app pinned by its signer (a replaced app is
 * never opened), its card showing exactly what goes, a tap for what isn't yours, a spoken yes only when all it does is open
 * the app, a draft "opened" and never "sent"; and the picker's rules.
 */
class HandOffStepTest {
    @Test fun anotherAppTakesOverTheNameUnlessYouTypedOne() {
        assertEquals("Maps", HandOffPicker.nameAfterPick("", null, "Maps"))
        assertEquals("Maps", HandOffPicker.nameAfterPick("Files", "Files", "Maps"))
        assertEquals("My hand-off", HandOffPicker.nameAfterPick("My hand-off", "Files", "Maps"))
    }

    @Test fun onlyAnotherSignerOnTheSameAppTakesYourWord() {
        val a = HandOffApp("com.example.a", "aa")
        assertTrue(HandOffPicker.signerChanged(a, a.copy(signer = "bb")))
        assertFalse(HandOffPicker.signerChanged(a, a))
        assertFalse(HandOffPicker.signerChanged(a, HandOffApp("com.example.b", "bb")))
        assertFalse(HandOffPicker.signerChanged(a.copy(signer = null), a))
        assertFalse(HandOffPicker.signerChanged(null, a))
    }

    @Test fun aHiddenFieldsSlotDoesntCount() {
        assertEquals(emptyList<String>(), IntentSpecs.slots(HandOffAction.MAIN, "geo:{{a}}", "{{b}}"))
        assertEquals(listOf("b"), IntentSpecs.slots(HandOffAction.SEND, "geo:{{a}}", "{{b}}"))
        assertEquals(listOf("a"), IntentSpecs.slots(HandOffAction.VIEW, "geo:{{a}}", "{{b}}"))
        assertEquals(listOf("a", "b"), IntentSpecs.slots(HandOffAction.SENDTO, "smsto:{{a}}", "{{b}}"))
    }

    private val signal = ConnFixtures.signal()
    private val page = ConnFixtures.signal("c-app002", ConnFixtures.viewPage).copy(name = "Wiki")
    private fun call(conn: String = signal.id, vararg args: Pair<String, StepArg>) = HandOffStepAction(HandOffCall(conn, mapOf(*args), "Heading home", 2))
    private fun fixed(s: String) = StepArg.Fixed(s)
    private fun value(s: String, src: ValueSource) = StepArg.Value(SkillValue.of(s, ParamType.TEXT, src))
    private fun prep(a: HandOffStepAction, c: Connection? = signal, app: AppPinState = AppPinState.TRUSTED) = HandOffStep.prepare(a, HandOffContext(c, app))

    @Test fun theSignerIsPinned() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        assertEquals(AppPinState.TRUSTED, HandOffStep.state(a, SignerNow(setOf(a)), stillSigns = false))
        assertEquals(AppPinState.TRUSTED, HandOffStep.state(a, SignerNow(setOf(a.uppercase())), stillSigns = false))
        // A key rotated with Android's own scheme: the pinned one is still in its lineage.
        assertEquals(AppPinState.TRUSTED, HandOffStep.state(a, SignerNow(setOf(b)), stillSigns = true))
        assertEquals(AppPinState.REPLACED, HandOffStep.state(a, SignerNow(setOf(b)), stillSigns = false))
        assertEquals(AppPinState.REPLACED, HandOffStep.state(a, SignerNow(null), stillSigns = false))
        assertEquals(AppPinState.REPLACED, HandOffStep.state(a, SignerNow(emptySet()), stillSigns = false))
        assertEquals(AppPinState.MISSING, HandOffStep.state(a, null, stillSigns = false))
    }

    @Test fun aReplacedOrMissingAppIsNeverOpened() {
        val replaced = prep(call(args = arrayOf("text" to fixed("On my way."))), app = AppPinState.REPLACED) as Prepared.Refused
        assertEquals("Signal was replaced by an app signed by someone else. Pick it again in Settings › Skills › Connections.", replaced.detail)
        val missing = prep(call(args = arrayOf("text" to fixed("On my way."))), app = AppPinState.MISSING) as Prepared.Refused
        assertEquals("Signal isn't installed.", missing.detail)
        assertTrue(prep(call(args = arrayOf("text" to fixed("x"))), c = null) is Prepared.Refused)
        // Another kind of connection under the same id isn't a hand-off.
        assertTrue(prep(call(conn = signal.id, "text" to fixed("x")), c = ConnFixtures.ntfy().copy(id = signal.id)) is Prepared.Refused)
    }

    @Test fun anAppNotPinnedHereIsNeverOpened() {
        // C10: a hand-off from a backup that isn't yours has no signer here: nothing to check against, so never opened.
        assertEquals(AppPinState.UNPINNED, HandOffStep.state(null, SignerNow(setOf("a".repeat(64))), stillSigns = true))
        assertEquals(AppPinState.UNPINNED, HandOffStep.state(null, null, stillSigns = false))
        val unpinned = signal.copy(handOff = signal.handOff!!.copy(signer = null))
        val r = prep(call(args = arrayOf("text" to fixed("On my way."))), c = unpinned, app = AppPinState.UNPINNED) as Prepared.Refused
        assertEquals("Signal came from a backup that isn't yours, so Robot won't open it yet. Pick it again in Settings › Skills › Connections.", r.detail)
        // Even when the state it was given said otherwise.
        assertTrue(prep(call(args = arrayOf("text" to fixed("On my way."))), c = unpinned, app = AppPinState.TRUSTED) is Prepared.Refused)
    }

    @Test fun theCardShowsExactlyWhatGoes() {
        val p = prep(call(args = arrayOf("text" to fixed("On my way.")))) as Prepared.Ready
        val a = p.action as HandOffStepAction
        assertEquals("Open Signal with “On my way.”", a.summary().en())
        val body = a.body()!!
        assertTrue(body, body.contains("Opens Signal (org.thoughtcrime.securesms), signed by the same key as when you picked it"))
        assertTrue(body, body.contains("text: On my way. · saved in the skill"))
        assertTrue(body, body.contains("Asks it to: SEND"))
        assertTrue(body, body.contains("Hands over android.intent.extra.TEXT: On my way."))
        assertTrue(body, body.contains("You still send it yourself in Signal."))
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), a.prepared!!.effects)
        val view = (prep(call(page.id, "page" to fixed("Groceries")), c = page) as Prepared.Ready).action as HandOffStepAction
        assertEquals("Open Wiki", view.summary().en())
        assertTrue(view.body()!!.contains("Address: https://wiki.example.org/page/Groceries"))
    }

    @Test fun whatIsntYoursTakesATapAndASpokenYesOnlyOpensAnApp() {
        // Fixed words and only opening a page: no reason to ask, and a card (Ask first) a spoken yes may answer.
        val open = prep(call(page.id, "page" to fixed("Groceries")), c = page) as Prepared.Ready
        assertEquals(emptySet<AskWhy>(), open.why)
        assertNull((open.action as HandOffStepAction).tapOnly)
        // A draft for someone: never on a spoken yes (D14), even with your own words.
        val draft = prep(call(args = arrayOf("text" to value("On my way.", ValueSource.YOURS)))) as Prepared.Ready
        assertEquals(emptySet<AskWhy>(), draft.why)
        assertEquals(AskWhy.CONNECTION_TAP, (draft.action as HandOffStepAction).tapOnly)
        // Words Robot's model wrote, or read from an answer or on the phone: a tap.
        for (src in listOf(ValueSource.MODEL, ValueSource.ONLINE, ValueSource.PHONE)) {
            val p = prep(call(args = arrayOf("text" to value("On my way.", src)))) as Prepared.Ready
            assertEquals(src.name, setOf(AskWhy.CONNECTION_TAP), p.why)
        }
        val read = prep(call(page.id, "page" to value("Groceries", ValueSource.ONLINE)), c = page) as Prepared.Ready
        assertEquals(setOf(AskWhy.CONNECTION_TAP), read.why)
        // Your switch "A spoken yes may approve its changes" off: every card is a tap.
        val off = prep(call(page.id, "page" to fixed("Groceries")), c = page.copy(spokenYes = false)) as Prepared.Ready
        assertEquals(AskWhy.CONNECTION_TAP, (off.action as HandOffStepAction).tapOnly)
        // Unprepared, it is tap only too.
        assertEquals(AskWhy.CONNECTION_TAP, call().tapOnly)
        assertFalse(call().worksLocked(emptySet()))
    }

    @Test fun aModelsPickFromYourListAsksACard() {
        val c = ConnFixtures.signal("c-app003", ConnFixtures.viewPage.copy(params = mapOf("page" to OpParam(ParamType.CHOICE, listOf("Groceries", "Chores")))))
        val p = prep(call(c.id, "page" to StepArg.Value(SkillValue.of("Chores", ParamType.CHOICE, ValueSource.MODEL))), c = c) as Prepared.Ready
        assertEquals(setOf(AskWhy.CONNECTION), p.why)
        assertTrue(p.noteText!!.en().contains("picked a value"))
    }

    @Test fun aValueThatDoesntFitIsntSent() {
        assertTrue(prep(call(page.id, "page" to fixed("../admin")), c = page) is Prepared.Refused)
        assertTrue(prep(call(page.id, "page" to StepArg.Missing), c = page) is Prepared.Refused)
        assertTrue(prep(call(page.id, "nope" to fixed("x")), c = page) is Prepared.Refused)
        assertTrue(prep(call(page.id), c = page) is Prepared.Refused)
        assertTrue(prep(call(args = arrayOf("text" to fixed("x".repeat(501))))) is Prepared.Refused)
    }

    @Test fun theOriginPinsTheAppItsSignerAndWhatItAsks() {
        val o = HandOffStep.origin(signal)!!
        assertEquals(o, HandOffStep.origin(signal.copy(name = "My Signal", tested = ConnTested(5))))
        assertNotEquals(o, HandOffStep.origin(signal.copy(handOff = signal.handOff!!.copy(signer = "c".repeat(64)))))
        assertNotEquals(o, HandOffStep.origin(signal.copy(handOff = signal.handOff!!.copy(pkg = "org.example.other"))))
        assertNotEquals(o, HandOffStep.origin(signal.copy(handOff = signal.handOff!!.copy(intent = ConnFixtures.viewPage))))
        assertNull(HandOffStep.origin(ConnFixtures.ntfy()))
    }

    @Test fun aDraftIsOpenedNeverSent() {
        val d = HandOffStep.opened(signal, launched = true)
        assertEquals(ActionStatus.DRAFT_OPENED, d.status)
        assertTrue(d.launchedApp)
        assertEquals("Opened Signal with your words. Send it there when you're ready.", d.detail)
        val v = HandOffStep.opened(page, launched = true)
        assertEquals(ActionStatus.DONE, v.status)
        assertEquals("Opened Wiki.", v.detail)
        val no = HandOffStep.opened(page, launched = false)
        assertEquals(ActionStatus.FAILED, no.status)
        assertFalse(no.launchedApp)
        assertEquals("Wiki can't open this. Check the hand-off in Settings › Skills › Connections.", no.detail)
    }

    @Test fun thePicker() {
        val a = "a".repeat(64)
        assertEquals(a to null, HandOffPicker.signer(SignerNow(setOf(a.uppercase()))))
        assertEquals(null to PickProblem.SEVERAL_SIGNERS, HandOffPicker.signer(SignerNow(setOf(a, "b".repeat(64)))))
        assertEquals(null to PickProblem.NO_SIGNER, HandOffPicker.signer(SignerNow(null)))
        assertEquals(null to PickProblem.NO_SIGNER, HandOffPicker.signer(null))
        var asked: HandOffIntent? = null
        val (c, no) = HandOffPicker.connection("c-app009", "Wiki", "org.example.wiki", SignerNow(setOf(a)), ConnFixtures.viewPage) { asked = it; true }
        assertNull(no)
        assertEquals(HandOffApp("org.example.wiki", a, ConnFixtures.viewPage), c!!.handOff)
        // The app is asked to take the address's fixed words, before its first slot.
        assertEquals("https://wiki.example.org/page/", asked!!.data)
        assertEquals("android.intent.action.VIEW", asked!!.action)
        assertEquals(null to PickProblem.CANT_TAKE, HandOffPicker.connection("c-app009", "Wiki", "org.example.wiki", SignerNow(setOf(a)), ConnFixtures.viewPage) { false })
        assertEquals(null to PickProblem.NOT_VALID, HandOffPicker.connection("c-app009", "Wiki", "org.example.wiki", SignerNow(setOf(a)),
            IntentSpec(HandOffAction.VIEW, data = "content://x")) { true })
        assertEquals("Robot can't pin Wiki: it's signed by more than one key.", HandOffPicker.text(PickProblem.SEVERAL_SIGNERS, "Wiki").en())
    }
}
