// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.skills.RoutineMigration
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillCodec
import app.heyrobot.core.skills.SkillCodec12
import app.heyrobot.core.skills.SkillFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Skills 1.3 plan §4 "Downgrade" and §5 item 3 (S3.1's first test): 1.2's routines reader, frozen below as it shipped,
 * reads a v1 routines.json with `"skill": "<id>"` added to each routine as the same routines. So 1.3 keeps routines.json at
 * v1 with that key (no routine_skills.json), and after a downgrade 1.2 still runs your routines; it leaves the v3 skills
 * file alone (SkillCodec12: TooNew), so nothing is lost.
 */
class RoutineDowngradeTest {
    private fun tool(call: String, vararg args: Pair<String, Any?>): RoutineStep =
        RoutineStep.Tool((ToolCalls.parse(RawToolCall(call, mapOf(*args))) as ParsedCall.Ok).action)

    private val routines = listOf(
        Routine("evening", "Evening", RoutineTrigger.At(LocalTime.of(21, 0), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            listOf(tool("flashlight", "on" to true), RoutineStep.Say("Lights out soon")), created = 1760800000000,
            conditions = RoutineConditions(oncePerDay = false), lock = RoutineLock.WAIT_FOR_UNLOCK),
        Routine("wake", "Wake up", RoutineTrigger.AlarmDismissed(), listOf(RoutineStep.Brief, RoutineStep.Reminders, RoutineStep.Messages,
            tool("weather")), created = 1760800000001, online = setOf(OnlineUse.OPEN_METEO)),
        Routine("car", "In the car", RoutineTrigger.Charging(), listOf(tool("set_timer", "minutes" to 5)), created = 1760800000002, enabled = false),
    )

    @Test fun a12RobotReadsTheLinkedRoutinesFileAsTheSameRoutines() {
        val plain = RoutineCodec.encode(routines)
        val linked = RoutineCodec.encode(routines.mapIndexed { i, r -> r.copy(skill = "s-link0$i") })
        // The key is added, nothing else: v1, each routine with "skill".
        val o = JSONObject(linked)
        assertEquals(1, o.getInt("v"))
        for (i in routines.indices) assertEquals("s-link0$i", o.getJSONArray("routines").getJSONObject(i).getString("skill"))
        assertEquals(RoutineFile.Ok(routines), RoutineCodec12.decode(plain))
        assertEquals(RoutineFile.Ok(routines), RoutineCodec12.decode(linked))
        // This Robot reads the links back; a file without them is written as 1.2 writes it.
        assertEquals(RoutineFile.Ok(routines.mapIndexed { i, r -> r.copy(skill = "s-link0$i") }), RoutineCodec.decode(linked))
        assertEquals(plain, RoutineCodec.encode((RoutineCodec.decode(plain) as RoutineFile.Ok).routines))
    }

    @Test fun afterTheMigrationA12RobotStillRunsYourRoutinesAndLeavesTheSkillsAlone() {
        val m = RoutineMigration.migrate(routines, emptyList())
        assertEquals(0, m.refused)
        val r12 = RoutineCodec12.decode(RoutineCodec.encode(m.routines)) as RoutineFile.Ok
        assertEquals(routines, r12.routines)
        val skills = SkillCodec.encode(m.skills)
        assertEquals(3, JSONObject(skills).getInt("v"))
        assertEquals(SkillFile.TooNew(3), SkillCodec12.decode(skills))
        // 1.2 writes the routines back without the links; 1.3 links them to the same skills again, and makes none.
        val again = RoutineMigration.migrate(r12.routines, (SkillCodec.decode(skills) as SkillFile.Ok).skills)
        assertEquals(m.routines, again.routines)
        assertEquals(m.skills, again.skills)
        assertTrue(again.routines.all { it.skill != null })
    }
    /** 1.3, then 1.2 in place (it drops the links when it saves), a routine edited there, back to 1.3: its own skill gets the edit. */
    @Test fun aRoutineEditedUnder12ChangesItsOwnSkillNotANewOne() {
        val m = RoutineMigration.migrate(routines, emptyList())
        val r12 = (RoutineCodec12.decode(RoutineCodec.encode(m.routines)) as RoutineFile.Ok).routines
        val edited = r12.map { if (it.id == "evening") it.copy(name = "Bedtime", steps = listOf(tool("flashlight", "on" to false)), edited = it.created + 5) else it }
        val again = RoutineMigration.migrate(edited, m.skills)
        assertEquals(m.skills.size, again.skills.size)
        assertEquals(m.links, again.links)
        val s = again.skills.single { it.id == m.links.getValue("evening") }
        assertEquals("Bedtime", s.name)
        assertEquals(listOf(RoutineMigration.step(tool("flashlight", "on" to false))), s.steps)
        assertEquals(m.skills.single { it.id == s.id }.level, s.level)
        assertTrue(again.changed)
        // And running it again changes nothing.
        assertFalse(RoutineMigration.migrate(again.routines, again.skills).changed)
    }

    /** A routine deleted under 1.2: back in 1.3 its skill is turned Off (never deleted, so nothing is lost), the others left as they were. */
    @Test fun aRoutineDeletedUnder12TurnsItsSkillOff() {
        val m = RoutineMigration.migrate(routines, emptyList())
        val r12 = (RoutineCodec12.decode(RoutineCodec.encode(m.routines)) as RoutineFile.Ok).routines.filter { it.id != "car" }
        val again = RoutineMigration.migrate(r12, m.skills)
        val car = m.links.getValue("car")
        assertEquals(m.skills.map { it.id }, again.skills.map { it.id })
        assertEquals(SkillLevel.OFF, again.skills.single { it.id == car }.level)
        for (s in again.skills.filter { it.id != car }) assertEquals(s.name, m.skills.single { it.id == s.id }, s)
        assertFalse(RoutineMigration.migrate(again.routines, again.skills).changed)
    }

    @Test fun aLinkThatIsntASkillIdIsARoutineThatCantBeRead() {
        val bad = JSONObject(RoutineCodec.encode(routines.take(1).map { it.copy(skill = "s-link00") }))
        bad.getJSONArray("routines").getJSONObject(0).put("skill", "evening")
        assertEquals(RoutineFile.Ok(emptyList(), 1), RoutineCodec.decode(bad.toString()))
    }

    /** Test only. 1.2's RoutineCodec reader as it shipped (release/1.2, the same at feature/today abf387402). Never change it to make a test pass. */
    private object RoutineCodec12 {
    fun decode(json: String?): RoutineFile {
        val o = try { JSONObject(json ?: return RoutineFile.Damaged) } catch (_: Exception) { return RoutineFile.Damaged }
        val v = (o.opt("v") as? Number)?.toInt() ?: return RoutineFile.Damaged
        if (v > 1) return RoutineFile.TooNew(v)
        if (v < 1) return RoutineFile.Damaged
        val a = o.optJSONArray("routines") ?: return RoutineFile.Damaged
        val out = mutableListOf<Routine>()
        var dropped = 0
        val ids = HashSet<String>()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i)?.let(::fromJson)
            if (r == null || !ids.add(r.id)) dropped++ else out += r
        }
        return RoutineFile.Ok(out, dropped)
    }

    /** One routine, or null when anything in it can't be read or it fails [RoutineCheck]. */
    fun fromJson(o: JSONObject): Routine? = try {
        val c = o.optJSONObject("conditions")
        val between = if (c != null && c.has("from")) TimeWindow(RoutineCodec.time(RoutineCodec.str(c, "from"))!!, RoutineCodec.time(RoutineCodec.str(c, "to"))!!) else null
        val r = Routine(
            id = RoutineCodec.str(o, "id")!!,
            name = RoutineCodec.str(o, "name")!!,
            trigger = RoutineCodec.trigger(o.getJSONObject("trigger"))!!,
            steps = o.getJSONArray("steps").let { a -> (0 until a.length()).map { RoutineCodec.step(a.getJSONObject(it))!! } },
            enabled = if (o.has("enabled")) RoutineCodec.bool(o, "enabled")!! else true,
            created = RoutineCodec.long(o, "created")!!,
            edited = RoutineCodec.long(o, "edited") ?: RoutineCodec.long(o, "created")!!,
            conditions = RoutineConditions(
                between = between,
                days = c?.optJSONArray("days")?.let { RoutineCodec.days(it)!! },
                oncePerDay = if (c != null && c.has("once_per_day")) RoutineCodec.bool(c, "once_per_day")!! else true,
            ),
            output = if (o.has("output")) RoutineOutput.of(RoutineCodec.str(o, "output"))!! else RoutineOutput.HEADSET_OR_SPEAKER,
            lock = if (o.has("lock")) RoutineLock.of(RoutineCodec.str(o, "lock"))!! else RoutineLock.FOLLOW,
            online = o.optJSONArray("online")?.let { a -> (0 until a.length()).map { OnlineUse.of(a.opt(it) as? String)!! }.toSet() } ?: emptySet(),
        )
        r.takeIf { RoutineCheck.valid(it) }
    } catch (_: Exception) { null }

    }
}
