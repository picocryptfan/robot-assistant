package app.heyrobot.core.connect

import app.heyrobot.core.AskWhy
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Home Assistant operation carries your marks as the webhook form's does: a switch to a door's motor marked security. */
class HaOpMarksTest {
    private val now = 1_761_100_000_000L
    private val door = ConnOp("garage_switch", "Garage door", OpSpec.HaCall("switch", "turn_on"),
        mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("switch.garage_door"), default = "switch.garage_door")))
    private val ha = ConnFixtures.homeAssistant("c-ha0001", door, ConnFixtures.lightOff)

    private fun why(c: Connection): Set<AskWhy> {
        val p = ConnStep.prepare(ConnStepAction(ConnStepCall(StepKind.CONN, c.id, door.id, emptyMap(), "Open the garage", 1, null)),
            StepContext(c, true, null, true, mapOf(SecretPart.TOKEN to "…AB12"), LimitState(), now))
        return (p as? Prepared.Ready ?: throw AssertionError(p.toString())).why
    }

    @Test fun aSwitchMarkedSecurityAsksEveryTime() {
        assertEquals(emptySet<AskWhy>(), why(ha))
        val marked = HaPick.withMarks(ha, door.id, setOf(SkillEffect.SECURITY))!!
        assertEquals(setOf(SkillEffect.SECURITY), marked.op(door.id)!!.marks)
        assertEquals(ConnFixtures.lightOff, marked.op(ConnFixtures.lightOff.id))
        assertTrue(SkillEffect.SECURITY in HaPick.effects(marked, marked.op(door.id)!!))
        // A tap each time, never a spoken yes (D14): the same answer on every run.
        repeat(3) { assertEquals(setOf(AskWhy.CONNECTION_TAP), why(marked)) }
        // Taken off again, it's a switch as before.
        assertEquals(emptySet<AskWhy>(), why(HaPick.withMarks(marked, door.id, emptySet())!!))
        assertNull(HaPick.withMarks(ha, "gone", setOf(SkillEffect.SECURITY)))
    }

    @Test fun onlyTheWebhookFormsMarksAreKept() {
        val m = HaPick.withMarks(ha, door.id, setOf(SkillEffect.MONEY, SkillEffect.MESSAGES_SOMEONE, SkillEffect.CHANGES_HOME, SkillEffect.RUNS_CODE))!!
        assertEquals(setOf(SkillEffect.MONEY, SkillEffect.MESSAGES_SOMEONE), m.op(door.id)!!.marks)
    }

    /** M2: you can mark an operation as one that deletes; it then asks with a tap every time, and is saved with it. */
    @Test fun anOperationCanBeMarkedDeletes() {
        val marked = HaPick.withMarks(ha, door.id, setOf(SkillEffect.DELETES))!!
        assertEquals(setOf(SkillEffect.DELETES), marked.op(door.id)!!.marks)
        assertEquals(setOf(AskWhy.CONNECTION_TAP), why(marked))
        assertEquals(ConnFile.Ok(listOf(marked)), ConnectionCodec.decode(ConnectionCodec.encode(listOf(marked))))
    }

    /** L1: an operation picked (again, after a rename) carries the pick window's marks before it is saved. */
    @Test fun aPickedOperationCarriesItsMarks() {
        val picked = HaPick.marked(door, setOf(SkillEffect.SECURITY, SkillEffect.CHANGES_HOME))
        assertEquals(setOf(SkillEffect.SECURITY), picked.marks)
        assertTrue(SkillEffect.SECURITY in HaPick.effects(ha, picked))
        val saved = ha.copy(ops = ha.ops.filter { it.id != door.id } + picked)
        assertEquals(setOf(AskWhy.CONNECTION_TAP), why(saved))
    }

    @Test fun theMarksAreSavedAndRestoredWithTheConnection() {
        val marked = HaPick.withMarks(ha, door.id, setOf(SkillEffect.SECURITY))!!
        val text = ConnectionCodec.encode(listOf(marked))
        assertEquals(ConnFile.Ok(listOf(marked)), ConnectionCodec.decode(text))
        // A Robot from before reads the mark too, so going back keeps the door asking.
        assertEquals(ConnFile.Ok(listOf(marked)), OldConnectionCodec.decode(text))
    }

    @Test fun anOldSavedConnectionWithNoMarksReadsAsBefore() {
        val read = (ConnectionCodec.decode(OldConnectionCodec.encode(listOf(ha))) as ConnFile.Ok).connections.single()
        assertEquals(ha, read)
        assertTrue(read.ops.all { it.marks.isEmpty() })
        assertFalse(SkillEffect.SECURITY in HaPick.effects(read, read.op(door.id)!!))
        assertEquals(emptySet<AskWhy>(), why(read))
    }
}
