// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.skills.AttentionWhy
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillParam
import app.heyrobot.core.termux.ScriptParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * C9, plan §6.1 to §6.3: a new connection of each kind from what you typed (never a key), each row's state, the skills
 * that use one, Remove's count, Settings › Skills' line, and the words for a step that needs attention and a script
 * Robot can't type.
 */
class ConnSetupTest {
    private val now = ZonedDateTime.of(2026, 10, 27, 12, 0, 0, 0, ZoneId.of("America/Halifax"))

    @Test fun eachKindIsMadeAsItsRulesSay() {
        val (ha, p) = ConnSetup.create("c-ha0001", ConnKind.HOME_ASSISTANT, " Home Assistant ", "home.example.org:8123")
        assertNull(p)
        assertEquals(ConnAuth.Bearer, ha!!.auth)
        assertEquals("Home Assistant", ha.name)
        assertEquals("https://home.example.org:8123", ha.address!!.text)
        // A token never goes over plain http (D2).
        assertEquals(ConnProblem.KEY_OVER_HTTP, ConnSetup.create("c-ha0002", ConnKind.HOME_ASSISTANT, "HA", "http://192.168.1.5:8123").second)
        assertEquals(ConnProblem.ADDRESS, ConnSetup.create("c-ha0003", ConnKind.HOME_ASSISTANT, "HA", "ftp://x").second)
        val ntfy = ConnSetup.create("c-nt0001", ConnKind.NTFY, "ntfy", "https://ntfy.example.org", ConnAuth.Bearer, ntfyTitle = "Tell the family").first!!
        assertEquals(setOf(SecretPart.TOKEN, SecretPart.TOPIC), ntfy.secrets)
        assertEquals("Tell the family", ntfy.ops.single().title)
        assertEquals(ParamType.TEXT, ntfy.ops.single().params.getValue(ConnSetup.MESSAGE).type)
        // A webhook without a secret may be http at home; with a token, never.
        assertNotNull(ConnSetup.create("c-wh0001", ConnKind.WEBHOOK, "n8n", "http://192.168.1.9:5678").first)
        assertEquals(ConnProblem.KEY_OVER_HTTP, ConnSetup.create("c-wh0002", ConnKind.WEBHOOK, "n8n", "http://192.168.1.9:5678", ConnAuth.Bearer).second)
        // Routing: where you are never goes over http to a name on your network (C6).
        assertEquals(ConnProblem.LOCATION_OVER_HTTP, ConnSetup.create("c-rt0001", ConnKind.ROUTING, "OSRM", "http://192.168.1.9:5000", routing = RoutingKind.OSRM).second)
        assertNotNull(ConnSetup.create("c-rt0002", ConnKind.ROUTING, "OSRM", "http://100.101.102.103:5000", routing = RoutingKind.OSRM).first)
        // A hand-off is made by the picker (it pins a signer), never from an address.
        assertEquals(ConnProblem.COULDNT_SAVE, ConnSetup.create("c-ho0001", ConnKind.HAND_OFF, "Signal", "https://x.org").second)
        assertEquals(ConnProblem.COULDNT_SAVE, ConnSetup.create("c-ha0004", ConnKind.HOME_ASSISTANT, "", "https://x.org").second)
    }

    @Test fun newIdsAreNeverTaken() {
        val taken = setOf("c-aaaaaa")
        val r = java.util.Random(1)
        repeat(50) { val id = ConnSetup.newId(taken, r); assertTrue(id, Regex("^c-[a-z0-9]{6}$").matches(id) && id !in taken) }
    }

    @Test fun aRowSaysWhatTheConnectionNeeds() {
        val ha = ConnSetup.create("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", "https://home.example.org").first!!
        assertEquals(ConnState.NEEDS_KEY, ConnSetup.state(ha, keySaved = false))
        assertEquals(ConnState.NOT_TESTED, ConnSetup.state(ha, keySaved = true))
        val tested = ha.copy(tested = ConnTested(now.toInstant().toEpochMilli() - 3_600_000))
        assertEquals(ConnState.TESTED, ConnSetup.state(tested, keySaved = true))
        assertEquals("Home Assistant · Tested today", ConnSetup.row(tested, ConnState.TESTED, now).en())
        assertEquals("Home Assistant · Needs its key", ConnSetup.row(ha, ConnState.NEEDS_KEY, now).en())
        val hand = Connection("c-ho0001", ConnKind.HAND_OFF, "Signal", handOff = HandOffApp("org.thoughtcrime.securesms", "a".repeat(64)))
        assertEquals(ConnState.APP_REPLACED, ConnSetup.state(hand, true, AppPinState.REPLACED))
        assertEquals("Signal was replaced", ConnSetup.stateText(hand, ConnState.APP_REPLACED, now).en())
        assertEquals(ConnState.READY, ConnSetup.state(hand, true, AppPinState.TRUSTED))
        assertTrue(ConnSetup.needsYou(ConnState.APP_MISSING))
        // C10: a hand-off from a backup that isn't yours waits for you to pick its app again, whatever is installed.
        val unpinned = hand.copy(handOff = hand.handOff!!.copy(signer = null))
        assertEquals(ConnState.PICK_AGAIN, ConnSetup.state(unpinned, true, AppPinState.UNPINNED))
        assertEquals(ConnState.PICK_AGAIN, ConnSetup.state(unpinned, true, AppPinState.TRUSTED))
        assertEquals("From a backup that isn't yours: pick Signal again", ConnSetup.stateText(unpinned, ConnState.PICK_AGAIN, now).en())
        assertTrue(ConnSetup.needsYou(ConnState.PICK_AGAIN))
    }

    @Test fun usedByRemoveAndTheSkillsLine() {
        val ntfy = ConnSetup.create("c-nt0001", ConnKind.NTFY, "ntfy", "https://ntfy.example.org", ntfyTitle = "Tell").first!!
        val osrm = Connection("c-rt0001", ConnKind.ROUTING, "OSRM", ConnUrl.parse("https://osrm.example.org")!!, routing = RoutingKind.OSRM)
        val conns = listOf(ntfy, osrm)
        val o = SkillEditor.offers(conns, emptyList())
        val tell = SkillEditor.setConstant(SkillEditor.newSkill("s-tell01", "Tell", SkillEditor.step(o.first { it is StepOffer.Operation }), 1L, Lang.EN), 0,
            ArgSlot(ConnSetup.MESSAGE, ParamType.TEXT, maxLen = 4096), "Home.")!!
        val eta = SkillEditor.newSkill("s-eta001", "Eta", SkillEditor.step(o.first { it is StepOffer.Travel }), 1L, Lang.EN)
        val skills = listOf(tell, eta)
        assertEquals(listOf("s-tell01"), ConnSetup.usedBy(ntfy.id, skills, conns).map { it.id })
        // Travel time naming no server uses your only one.
        assertEquals(listOf("s-eta001"), ConnSetup.usedBy(osrm.id, skills, conns).map { it.id })
        assertEquals(1, ConnSetup.afterRemove(ntfy.id, skills, conns))
        assertEquals("Its key is deleted, and 1 skill will need attention.", ConnSetup.removeText(1).en())
        assertEquals("Its key is deleted. No skill needs it.", ConnSetup.removeText(0).en())
        assertEquals("ntfy · OSRM · 1 needs attention", ConnSetup.summary(conns, 1).en())
        assertEquals("ntfy · OSRM", ConnSetup.summary(conns, 0).en())
        assertEquals("None yet", ConnSetup.summary(emptyList(), 0).en())
        assertEquals("Step 2 needs attention: its connection or operation is gone.", ConnSetup.attention(1, AttentionWhy.CONNECTION_GONE).en())
    }

    @Test fun aScriptRobotCantTypeSaysWhichLine() {
        assertNull(ScriptParams.why(listOf("# param: days int 1..30", "# param: folder choice a|b")))
        assertEquals(ScriptParams.Why.Unreadable(2), ScriptParams.why(listOf("# param: days int 1..30", "# param: x float")))
        assertEquals(ScriptParams.Why.Repeated(2, "days"), ScriptParams.why(listOf("# param: days int 1..30", "# param: days int 1..9")))
        assertEquals(ScriptParams.Why.TooMany, ScriptParams.why(List(4) { "# param: a$it yes_no" }))
        assertEquals("Robot can't read its # param: line 2.", ConnSetup.scriptWhy(ScriptParams.Why.Unreadable(2)).en())
        // Each why matches parse's own verdict.
        for (lines in listOf(listOf("# param: x float"), listOf("# param: a yes_no", "# param: a yes_no"), List(4) { "# param: a$it yes_no" }))
            assertTrue(!ScriptParams.parse(lines, emptyList()).ok && ScriptParams.why(lines) != null)
        val q = SkillParam("days", ParamType.INTEGER, "7", "Days?", "Jours?", min = 1.0, max = 60.0)
        assertEquals("This question allows 1 to 60, but the script takes 1 to 30.", ConnSetup.rangeWide(q, 1.0, 30.0)!!.en())
        assertNull(ConnSetup.rangeWide(q, 0.0, 60.0))
    }
}
