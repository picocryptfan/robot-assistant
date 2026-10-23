// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** skills.json v1 (plan §3, D2): a strict reading, a skill dropped whole, never read in part. */
class SkillCodecTest {
    private val home = SkillFixtures.headingHome()
    private val pasta = SkillFixtures.pasta()

    private fun file(vararg skills: JSONObject, v: Int = 1) = JSONObject().put("v", v).put("skills", JSONArray(skills.toList())).toString()

    private fun json(s: Skill) = SkillCodec.toJson(s)

    @Test fun roundTrip() {
        val text = SkillCodec.encode(listOf(home, pasta))
        assertEquals(SkillFile.Ok(listOf(home, pasta)), SkillCodec.decode(text))
        // The plan's example, as written there.
        val example = """{"v":1,"skills":[{"id":"s-k3f9q2","rev":3,"name":"Heading home","names":{"en":["heading home","on my way home"],"fr":["je rentre"]},
            "params":[{"name":"who","type":"person","default":"Sam","ask":{"en":"Who should I tell?","fr":"À qui je le dis?"}}],
            "steps":[{"use":"send_text","args":{"to":"{{who}}","message":"On my way home."}},{"use":"maps_navigate","args":{"destination":"home"}}],
            "level":"ask_first","provenance":{"made":"voice","from_content":false,"created":1760800000000,"edited":1760800000000}}]}"""
        assertEquals(SkillFile.Ok(listOf(home)), SkillCodec.decode(example))
        // A duration's default is a number in the file.
        assertEquals(720L, json(pasta).getJSONArray("params").getJSONObject(0).get("default"))
    }

    @Test fun anUnknownKeyAtTheTopDamagesTheFile() {
        val o = JSONObject(SkillCodec.encode(listOf(home))).put("extra", 1)
        assertEquals(SkillFile.Damaged, SkillCodec.decode(o.toString()))
        assertEquals(SkillFile.Damaged, SkillCodec.decode("""{"v":1}"""))
        assertEquals(SkillFile.Damaged, SkillCodec.decode("""{"v":"1","skills":[]}"""))
        assertEquals(SkillFile.Damaged, SkillCodec.decode("not json"))
        assertEquals(SkillFile.Damaged, SkillCodec.decode(null))
        assertEquals(SkillFile.Ok(emptyList()), SkillCodec.decode("""{"v":1,"skills":[]}"""))
    }

    @Test fun anUnknownKeyAnywhereInASkillDropsThatSkillWhole() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("color", "red") },
            // A later Robot's language (fix 1.1): kept when it is well formed (moreLanguages below), dropped when it isn't.
            { it.getJSONObject("names").put("es", "a casa") },
            { it.getJSONObject("names").put("es", JSONArray().put(1)) },
            { it.getJSONObject("names").put("es", JSONArray().put("a casa!")) },
            { it.getJSONObject("names").put("ES", JSONArray()) },
            { it.getJSONObject("names").put("es_MX", JSONArray()) },
            { it.getJSONObject("names").put("spanish language", JSONArray()) },
            { it.getJSONObject("names").put("es", JSONArray((1..7).map { n -> "nombre $n" })) },
            { o -> o.getJSONObject("names").also { n -> (1..17).forEach { i -> n.put("x" + ('a' + i), JSONArray()) } } },
            { it.getJSONObject("names").put("en", JSONObject.NULL) },
            { it.getJSONArray("params").getJSONObject(0).put("hint", "x") },
            { it.getJSONArray("params").getJSONObject(0).getJSONObject("ask").put("es", 5) },
            { it.getJSONArray("params").getJSONObject(0).getJSONObject("ask").put("es", " ") },
            { it.getJSONArray("params").getJSONObject(0).getJSONObject("ask").put("es", "x".repeat(SkillCheck.MAX_ASK + 1)) },
            { it.getJSONArray("params").getJSONObject(0).getJSONObject("ask").put("es", "¿A quién?\n") },
            { it.getJSONArray("params").getJSONObject(0).getJSONObject("ask").put("Spanish", "¿A quién se lo digo?") },
            { it.getJSONArray("params").getJSONObject(0).getJSONObject("ask").remove("fr") },
            { it.getJSONArray("steps").getJSONObject(0).put("when", "now") },
            { it.getJSONArray("steps").getJSONObject(0).getJSONObject("args").put("via", "sms") },
            { it.getJSONObject("provenance").put("by", "me") },
            // Wrong types and missing keys.
            { it.put("rev", "3") },
            { it.remove("level") },
            { it.getJSONObject("names").remove("fr") },
            { it.put("level", "sometimes") },
            { it.getJSONArray("steps").getJSONObject(0).put("use", "call") },
            { it.getJSONArray("steps").getJSONObject(0).put("use", "web_search") },
            { it.getJSONArray("steps").getJSONObject(0).getJSONObject("args").put("to", JSONObject.NULL) },
        )
        for ((i, change) in changes.withIndex()) {
            val bad = json(home).also(change)
            assertEquals("change $i", SkillFile.Ok(listOf(pasta), 1), SkillCodec.decode(file(bad, json(pasta))))
        }
    }

    /**
     * Fix 1.1 (languages-1.3-design §5, option a): a later Robot's skills.json with Spanish and German names and questions
     * loads, runs here by its English and French names only, and is written back with those languages as they were.
     */
    @Test fun moreLanguagesAreKeptAndWrittenBack() {
        val o = json(home)
        o.getJSONObject("names").put("es", JSONArray(listOf("voy a casa", "de vuelta a casa"))).put("de", JSONArray(listOf("ich fahre heim"))).put("fr-FR", JSONArray())
        o.getJSONArray("params").getJSONObject(0).getJSONObject("ask").put("es", "¿A quién se lo digo?").put("de", "Wem soll ich es sagen?")
        val read = SkillCodec.decode(file(o, json(pasta))) as SkillFile.Ok
        assertEquals(0, read.dropped)
        val kept = read.skills[0]
        assertEquals(mapOf("es" to listOf("voy a casa", "de vuelta a casa"), "de" to listOf("ich fahre heim"), "fr-FR" to emptyList()), kept.namesMore)
        assertEquals(mapOf("es" to "¿A quién se lo digo?", "de" to "Wem soll ich es sagen?"), kept.params[0].askMore)
        // Everything 1.1 uses is as it was.
        assertEquals(home, kept.copy(namesMore = emptyMap(), params = kept.params.map { it.copy(askMore = emptyMap()) }))
        assertEquals(home.keys, kept.keys)
        // Written back: the same languages, the same words, and the same file again after another read.
        val text = SkillCodec.encode(read.skills)
        val again = JSONObject(text).getJSONArray("skills").getJSONObject(0)
        assertEquals(setOf("en", "fr", "es", "de", "fr-FR"), again.getJSONObject("names").keys().asSequence().toSet())
        assertEquals("ich fahre heim", again.getJSONObject("names").getJSONArray("de").getString(0))
        assertEquals("¿A quién se lo digo?", again.getJSONArray("params").getJSONObject(0).getJSONObject("ask").getString("es"))
        assertEquals(text, SkillCodec.encode((SkillCodec.decode(text) as SkillFile.Ok).skills))
        // A skill of this Robot's own writes no other language.
        assertEquals(setOf("en", "fr"), json(home).getJSONObject("names").keys().asSequence().toSet())
        assertEquals(setOf("en", "fr"), json(home).getJSONArray("params").getJSONObject(0).getJSONObject("ask").keys().asSequence().toSet())
        // Its names in other languages don't take a name from another skill here (they never run it).
        val other = SkillFixtures.simple("s-other1", "Voy a casa")
        assertEquals(SkillFile.Ok(listOf(kept, other)), SkillCodec.decode(file(o, json(other))))
    }

    @Test fun aNewerFileIsLeftAlone() {
        assertEquals(SkillFile.TooNew(2), SkillCodec.decode(file(json(home), v = 2)))
        // Even with fields this version doesn't know.
        assertEquals(SkillFile.TooNew(2), SkillCodec.decode("""{"v":2,"skills":[],"model_picks":true}"""))
    }

    @Test fun duplicateIdsAndNamesDropTheSecond() {
        val sameId = SkillFixtures.simple(home.id, "Bedtime")
        assertEquals(SkillFile.Ok(listOf(home), 1), SkillCodec.decode(file(json(home), json(sameId))))
        // "On my way home" is one of Heading home's other names.
        val sameName = SkillFixtures.simple("s-other1", "On my way home")
        assertEquals(SkillFile.Ok(listOf(home), 1), SkillCodec.decode(file(json(home), json(sameName))))
    }

    @Test fun capsOf100SkillsAnd512KiB() {
        val many = (0 until 101).map { SkillFixtures.simple("s-" + it.toString().padStart(6, '0'), "Timer number $it") }
        val r = SkillCodec.decode(SkillCodec.encode(many)) as SkillFile.Ok
        assertEquals(100, r.skills.size)
        assertEquals(1, r.dropped)
        val big = JSONObject().put("v", 1).put("skills", JSONArray()).put("pad", "x".repeat(SkillCheck.MAX_BYTES)).toString()
        assertEquals(SkillFile.Damaged, SkillCodec.decode(big))
    }

    @Test fun argsAreReadBackWithTheirDefaults() {
        // Templates read through ToolCalls.parse with the defaults put in.
        assertEquals(Action.SendText("Sam", "On my way home."), SkillCheck.action(home, home.steps[0]))
        assertEquals(Action.SetTimer(720), SkillCheck.action(pasta, pasta.steps[0]))
        assertEquals(Action.SetTimer(480), SkillCheck.action(pasta, pasta.steps[0], mapOf("length" to "480")))
        // A default the tool refuses (a timer over 24 hours), or reads differently ("near me" is dropped), drops the skill.
        val tooLong = json(pasta).also { it.getJSONArray("params").getJSONObject(0).put("default", 90_000) }
        assertEquals(SkillFile.Ok(emptyList(), 1), SkillCodec.decode(file(tooLong)))
        val place = home.copy(params = listOf(SkillParam("where", ParamType.PLACE, "pharmacy near me", "Where to?", "Où?")),
            steps = listOf(SkillStep.of(StepKind.MAPS_SEARCH, mapOf("query" to "{{where}}"))))
        assertEquals(SkillFile.Ok(emptyList(), 1), SkillCodec.decode(file(json(place))))
        // A fixed value the tool would read differently is refused too.
        val alarm = SkillFixtures.simple("s-alarm1", "Wake").copy(steps = listOf(SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to 25, "minute" to 0))))
        assertEquals(SkillFile.Ok(emptyList(), 1), SkillCodec.decode(file(json(alarm))))
        // A duration given as text is the wrong type.
        val text = json(pasta).also { it.getJSONArray("params").getJSONObject(0).put("default", "720") }
        assertEquals(SkillFile.Ok(emptyList(), 1), SkillCodec.decode(file(text)))
    }

    @Test fun madeFromContentNeverReadsAsJustDoIt() {
        val s = json(SkillFixtures.headingHome(fromContent = true)).put("level", "just_do_it")
        assertEquals(SkillFile.Ok(emptyList(), 1), SkillCodec.decode(file(s)))
        assertTrue(SkillCodec.decode(file(json(SkillFixtures.headingHome(fromContent = true)))) == SkillFile.Ok(listOf(SkillFixtures.headingHome(fromContent = true))))
    }
}
