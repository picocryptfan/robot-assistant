// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * skills.json v1 (plan §3, D2): a strict reading, a skill dropped whole, never read in part. And v2 (skills connections
 * plan D6, §5.1, §7.1): what connections add, the lowest version that holds a file, and what a 1.1 Robot does with each.
 */
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
        // C1 (connections plan D6) moved this pin from 2 to 3 on purpose: this Robot reads v2; 1.1's answer to a v2 file is
        // in a11RobotReadsV1WholeAndLeavesV2Alone.
        // S3.1 moved it from 3 to 4 on purpose: this Robot reads v3 (v3RoundTripsAndOnlyItsSkillsNeedIt).
        assertEquals(SkillFile.TooNew(5), SkillCodec.decode(file(json(home), v = 5)))
        // Even with fields this version doesn't know.
        assertEquals(SkillFile.TooNew(5), SkillCodec.decode("""{"v":5,"skills":[],"model_picks":true}"""))
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

    // ------------------------------------------------------------------ v2 (skills connections plan D6, §5.1)

    private val v2 = listOf(SkillFixtures.headingHomeV2("s-v2home"), SkillFixtures.goodNight(), SkillFixtures.backUp())

    @Test fun v2RoundTrips() {
        for (s in v2) assertEquals(s.name, 2, SkillCodec.needs(s))
        val text = SkillCodec.encode(v2)
        assertEquals(2, JSONObject(text).getInt("v"))
        assertEquals(SkillFile.Ok(v2), SkillCodec.decode(text))
        assertEquals(text, SkillCodec.encode((SkillCodec.decode(text) as SkillFile.Ok).skills))
        // The plan's example, as written there.
        val example = """{"v":2,"skills":[{ "id": "s-k3f9q2", "rev": 5, "name": "Heading home",
            "names": { "en": ["heading home"], "fr": ["je rentre"] },
            "params": [ { "name": "who", "type": "person", "default": "Sam", "ask": { "en": "Who should I tell?", "fr": "À qui je le dis?" } } ],
            "steps": [
              { "id": "eta", "use": "travel_time", "args": { "to": "home" }, "on_fail": "go_on" },
              { "use": "send_text", "args": { "to": "{{who}}", "message": "On my way home, about {{eta.minutes}} min." }, "if": { "value": "eta.minutes", "is": "set" } },
              { "use": "send_text", "args": { "to": "{{who}}", "message": "On my way home." }, "if": { "value": "eta.minutes", "is": "unset" } },
              { "use": "maps_navigate", "args": { "destination": "home" } } ],
            "limits": { "per_hour": 12 }, "level": "ask_first",
            "provenance": { "made": "settings", "from_content": false, "created": 1761000000000, "edited": 1761000000000 } }]}"""
        assertEquals(SkillFile.Ok(listOf(SkillFixtures.headingHomeV2())), SkillCodec.decode(example))
        // Every new field as the file writes it.
        val night = json(SkillFixtures.goodNight())
        val q = night.getJSONArray("params")
        assertEquals("light.downstairs", q.getJSONObject(0).get("default"))
        assertEquals("Cocina", q.getJSONObject(0).getJSONArray("options").getJSONObject(1).getString("es"))
        assertEquals(20L, (q.getJSONObject(1).get("default") as Number).toLong())
        assertEquals(0L, (q.getJSONObject(1).get("min") as Number).toLong())
        assertEquals("percent", q.getJSONObject(1).getString("unit"))
        assertEquals(false, q.getJSONObject(2).get("default"))
        val st = night.getJSONArray("steps")
        assertEquals("conn:c-ha1234:light_dim", st.getJSONObject(0).getString("use"))
        assertEquals("lights", st.getJSONObject(0).getString("id"))
        assertEquals("/0/state", st.getJSONObject(0).getJSONObject("check").getString("pointer"))
        assertEquals(true, st.getJSONObject(1).getJSONObject("if").get("is"))
        assertTrue(!st.getJSONObject(0).has("on_fail"))
        val back = json(SkillFixtures.backUp())
        assertEquals(20.5, back.getJSONArray("params").getJSONObject(2).getDouble("default"), 0.0)
        assertEquals(18L, (back.getJSONArray("steps").getJSONObject(3).getJSONObject("if").get("more_than") as Number).toLong())
        assertEquals("app:c-ap9012", back.getJSONArray("steps").getJSONObject(4).getString("use"))
        assertEquals(3, back.getJSONObject("limits").getInt("per_hour"))
    }

    @Test fun theWriterPicksTheLowestVersionThatHoldsTheFile() {
        assertEquals(1, JSONObject(SkillCodec.encode(listOf(home, pasta))).getInt("v"))
        assertEquals(1, JSONObject(SkillCodec.encode(emptyList())).getInt("v"))
        assertEquals(2, JSONObject(SkillCodec.encode(listOf(home, SkillFixtures.goodNight()))).getInt("v"))
        // A v2 file holding only what 1.1 reads is written back as v1.
        val read = SkillCodec.decode(file(json(home), json(pasta), v = 2)) as SkillFile.Ok
        assertEquals(listOf(home, pasta), read.skills)
        assertEquals(1, JSONObject(SkillCodec.encode(read.skills)).getInt("v"))
        // Each new thing alone is enough for v2.
        val one = listOf<Skill>(
            home.copy(perHour = 12),
            home.copy(steps = home.steps.mapIndexed { i, st -> if (i == 0) st.copy(id = "text") else st }),
            home.copy(steps = home.steps.mapIndexed { i, st -> if (i == 1) st.copy(cond = SkillIf(SkillRef("who"), IfTest.Set)) else st }),
            home.copy(steps = home.steps + SkillFixtures.goodNight().steps[0]),
            home.copy(steps = listOf(home.steps[0].copy(args = mapOf("to" to "{{who}}", "message" to "Hi {{who}}")))),
            home.copy(steps = listOf(SkillLibrary.fixed(StepKind.WEATHER, mapOf("day" to "today")).copy(onFail = OnFail.GO_ON)) + home.steps),
        )
        for ((i, s) in one.withIndex()) assertEquals("change $i", 2, SkillCodec.needs(s))
        assertEquals(1, SkillCodec.needs(home))
        assertEquals(1, SkillCodec.needs(SkillFixtures.headingHomeMore()))
    }

    /**
     * D6 and §7.1: a 1.1 Robot (its codec as it shipped, frozen in SkillCodec11) reads a v1 file this Robot wrote whole, and
     * leaves a v2 file alone: "Your skills are from a newer Robot", nothing run, never written over (1.1's SkillStore only
     * writes after an Ok read). A v1 file with something that needs v2 in it drops that skill here exactly as 1.1 does.
     */
    @Test fun a11RobotReadsV1WholeAndLeavesV2Alone() {
        val v1 = SkillCodec.encode(listOf(home, pasta, SkillFixtures.headingHomeMore("s-more01").copy(name = "Going home", namesEn = listOf("going home"),
            namesFr = emptyList())))
        val old = SkillCodec11.decode(v1) as SkillFile.Ok
        assertEquals(0, old.dropped)
        assertEquals(SkillCodec.decode(v1), old)
        val mixed = SkillCodec.encode(listOf(home, SkillFixtures.goodNight()))
        assertEquals(SkillFile.TooNew(2), SkillCodec11.decode(mixed))
        assertEquals(SkillFile.TooNew(2), SkillCodec11.decode(SkillCodec.encode(v2)))
        // A v1 file holding v2's fields: each such skill dropped, here as in 1.1.
        val needsV2 = listOf<(JSONObject) -> Unit>(
            { it.put("limits", JSONObject().put("per_hour", 12)) },
            { it.getJSONArray("steps").getJSONObject(0).put("id", "text") },
            { it.getJSONArray("steps").getJSONObject(1).put("if", JSONObject().put("value", "who").put("is", "set")) },
            { it.getJSONArray("steps").getJSONObject(1).put("on_fail", "stop") },
            { it.getJSONArray("steps").getJSONObject(0).getJSONObject("args").put("message", "Hi {{who}}") },
            { it.getJSONArray("steps").put(json(SkillFixtures.headingHomeV2()).getJSONArray("steps").getJSONObject(0)) },
            { it.getJSONArray("params").getJSONObject(0).put("type", "choice") },
            { it.getJSONArray("params").getJSONObject(0).put("unit", "minutes") },
        )
        for ((i, change) in needsV2.withIndex()) {
            val text = file(json(home).also(change), json(pasta))
            assertEquals("change $i (1.1)", SkillFile.Ok(listOf(pasta), 1), SkillCodec11.decode(text))
            assertEquals("change $i", SkillFile.Ok(listOf(pasta), 1), SkillCodec.decode(text))
        }
    }

    private val sha = "0123456789abcdef".repeat(4)
    private fun imported(signer: String? = "fedcba9876543210".repeat(4)) =
        home.copy(id = "s-imp001", provenance = SkillProvenance(SkillMade.IMPORT, false, 5L, 5L, SkillImport(sha, signer)))

    /** Skills 1.3 plan S3.1: v3 holds a routine's skill, a draft, an import and a routine's own steps; nothing else needs it. */
    @Test fun v3RoundTripsAndOnlyItsSkillsNeedIt() {
        val brief = SkillFixtures.simple("s-brief1", "Wake up").let { s ->
            s.copy(steps = listOf(SkillStep.of(StepKind.BRIEF, emptyMap()), SkillStep.of(StepKind.REMINDERS, emptyMap()),
                SkillStep.of(StepKind.MESSAGES, emptyMap())) + s.steps)
        }
        val three = listOf(
            home.copy(provenance = home.provenance.copy(made = SkillMade.ROUTINE)),
            home.copy(provenance = home.provenance.copy(made = SkillMade.DRAFT_DEVICE)),
            home.copy(provenance = home.provenance.copy(made = SkillMade.DRAFT_HELPER)),
            imported(), imported(signer = null), brief,
        )
        for ((i, s) in three.withIndex()) {
            assertEquals("skill $i", 3, SkillCodec.needs(s))
            assertTrue("skill $i", SkillCheck.valid(s))
            val text = SkillCodec.encode(listOf(s, pasta))
            assertEquals("skill $i", 3, JSONObject(text).getInt("v"))
            assertEquals("skill $i", SkillFile.Ok(listOf(s, pasta)), SkillCodec.decode(text))
            // 1.2 leaves the whole file alone (plan §4 "Downgrade").
            assertEquals("skill $i", SkillFile.TooNew(3), SkillCodec12.decode(text))
            assertEquals("skill $i", SkillFile.TooNew(3), SkillCodec11.decode(text))
            // Written as a v2 file (as 1.2 would never write it), the skill is dropped whole.
            val two = file(json(s), json(pasta), v = 2)
            assertEquals("skill $i", SkillFile.Ok(listOf(pasta), 1), SkillCodec.decode(two))
        }
        val o = json(imported(signer = null)).getJSONObject("provenance").getJSONObject("import")
        assertEquals(sha, o.getString("file_sha256"))
        assertTrue(o.isNull("signer"))
        assertEquals(setOf("file_sha256", "signer"), o.keys().asSequence().toSet())
        // A routine's own step gives its category: the brief reads online and the phone.
        assertEquals(setOf(SkillEffect.READS_ONLINE, SkillEffect.READS_PHONE, SkillEffect.CHANGES_PHONE), SkillEffects.of(brief, SkillLinks.NONE))
    }

    /** 1.1's and 1.2's files: a file without v3's content is written byte for byte as 1.2 writes it, and read as 1.2 reads it. */
    @Test fun a12RobotsFilesAreWrittenAsBefore() {
        val lists = listOf(listOf(home, pasta), emptyList(), listOf(home, SkillFixtures.goodNight()), listOf(SkillFixtures.headingHomeV2(), pasta),
            listOf(SkillFixtures.headingHomeMore()))
        for ((i, l) in lists.withIndex()) {
            val text = SkillCodec.encode(l)
            assertEquals("list $i", SkillCodec12.encode(l), text)
            assertEquals("list $i", SkillCodec12.decode(text), SkillCodec.decode(text))
            assertTrue("list $i", JSONObject(text).getInt("v") <= 2)
        }
    }

    /** Design §3.7: only an import says which file it came from, in its shape; anything else drops the skill whole. */
    @Test fun anImportsProvenanceIsStrict() {
        assertFalse(SkillCheck.valid(home.copy(provenance = home.provenance.copy(made = SkillMade.IMPORT))))
        assertFalse(SkillCheck.valid(home.copy(provenance = home.provenance.copy(imported = SkillImport(sha, null)))))
        assertFalse(SkillCheck.valid(imported(signer = "AB12")))
        assertFalse(SkillCheck.valid(imported().copy(provenance = imported().provenance.copy(imported = SkillImport(sha.uppercase(), null)))))
        val bad = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("provenance").getJSONObject("import").put("at", 5) },
            { it.getJSONObject("provenance").getJSONObject("import").remove("signer") },
            { it.getJSONObject("provenance").getJSONObject("import").put("signer", 7) },
            { it.getJSONObject("provenance").getJSONObject("import").put("file_sha256", "x") },
            { it.getJSONObject("provenance").remove("import") },
            { it.getJSONObject("provenance").put("extra", 1) },
        )
        for ((i, change) in bad.withIndex()) {
            val text = file(json(imported()).also(change), json(pasta), v = 3)
            assertEquals("change $i", SkillFile.Ok(listOf(pasta), 1), SkillCodec.decode(text))
        }
        // A routine's own step takes no arguments.
        val withArg = SkillFixtures.simple("s-brief2", "Wake").copy(steps = listOf(SkillStep.of(StepKind.BRIEF, mapOf("text" to "x"))))
        assertFalse(SkillCheck.valid(withArg))
    }

    /** §5.1's limits: every new field out of its limits, or in a place it doesn't fit, drops its skill whole. */
    @Test fun everyNewFieldsLimits() {
        fun night() = json(SkillFixtures.goodNight())
        fun back() = json(SkillFixtures.backUp())
        fun hh() = json(SkillFixtures.headingHomeV2())
        fun JSONObject.step(i: Int) = getJSONArray("steps").getJSONObject(i)
        fun JSONObject.param(i: Int) = getJSONArray("params").getJSONObject(i)
        val bad = listOf<Pair<String, () -> JSONObject>>(
            "per_hour 0" to { hh().put("limits", JSONObject().put("per_hour", 0)) },
            "per_hour 61" to { hh().put("limits", JSONObject().put("per_hour", 61)) },
            "per_hour as text" to { hh().put("limits", JSONObject().put("per_hour", "12")) },
            "limits' unknown key" to { hh().put("limits", JSONObject().put("per_hour", 12).put("per_day", 100)) },
            "a step id's shape" to { hh().also { it.step(0).put("id", "Eta") } },
            "two steps, one id" to { hh().also { it.step(3).put("id", "eta") } },
            "a template naming a later step" to { hh().also { it.step(0).remove("id"); it.step(3).put("id", "eta") } },
            "an output its kind doesn't have" to { hh().also { it.step(1).getJSONObject("args").put("message", "About {{eta.hours}} h.") } },
            "a template naming no question" to { hh().also { it.step(2).getJSONObject("args").put("to", "{{whom}}") } },
            "an output in a typed argument" to { hh().also { it.step(3).getJSONObject("args").put("destination", "{{eta.minutes}}") } },
            "words in a typed argument" to { hh().also { it.step(3).getJSONObject("args").put("destination", "home {{who}}") } },
            "a stray brace" to { hh().also { it.step(1).getJSONObject("args").put("message", "About {{eta.minutes} min.") } },
            "a yes or no inside words" to { night().also { it.step(2).getJSONObject("args").put("text", "Lock: {{lock}}") } },
            "a condition on free text" to { hh().also { it.step(2).put("if", JSONObject().put("value", "who").put("is", "set")) } },
            "a condition naming nothing" to { hh().also { it.step(2).put("if", JSONObject().put("value", "nobody").put("is", "set")) } },
            "a condition with two tests" to { hh().also { it.step(2).getJSONObject("if").put("less_than", 5) } },
            "a condition's unknown test" to { hh().also { it.step(2).put("if", JSONObject().put("value", "eta.minutes").put("equals", 5)) } },
            "a choice not listed" to { night().also { it.step(1).put("if", JSONObject().put("value", "room").put("is", "light.porch")) } },
            "a number test on a choice" to { night().also { it.step(1).put("if", JSONObject().put("value", "room").put("less_than", 3)) } },
            "yes on a number" to { night().also { it.step(1).put("if", JSONObject().put("value", "level").put("is", true)) } },
            "on_fail's unknown value" to { hh().also { it.step(0).put("on_fail", "maybe") } },
            "go on after a send" to { hh().also { it.step(1).put("on_fail", "go_on") } },
            "a pointer check on a built-in step" to { hh().also { it.step(1).put("check", JSONObject().put("pointer", "/0").put("equals", 1)) } },
            "a regex check on a connection" to { night().also { it.step(0).put("check", JSONObject().put("matches", "on")) } },
            "a pointer without its slash" to { night().also { it.step(0).getJSONObject("check").put("pointer", "0/state") } },
            "a pointer's lone tilde" to { night().also { it.step(0).getJSONObject("check").put("pointer", "/a~2b") } },
            "a check with two keys" to { back().also { it.step(1).getJSONObject("check").put("pointer", "/0") } },
            "a regex Java can't read" to { back().also { it.step(1).put("check", JSONObject().put("matches", "(done")) } },
            "one option" to { night().also { it.param(0).put("options", JSONArray().put(it.param(0).getJSONArray("options").get(0))) } },
            "13 options" to { night().also { o -> o.param(0).put("options", JSONArray((0 until 13).map { i ->
                JSONObject().put("value", if (i == 0) "light.downstairs" else "light.r$i").put("en", "Room $i").put("fr", "Pièce $i") })) } },
            "two options, one value" to { night().also { it.param(0).getJSONArray("options").getJSONObject(1).put("value", "light.downstairs") } },
            "an option without its French" to { night().also { it.param(0).getJSONArray("options").getJSONObject(1).remove("fr") } },
            "an option named like a test" to { night().also { it.param(0).getJSONArray("options").getJSONObject(1).put("value", "unset") } },
            "a default not listed" to { night().also { it.param(0).put("default", "light.porch") } },
            "a choice without options" to { night().also { it.param(0).remove("options") } },
            "options on a whole number" to { night().also { it.param(1).put("options", it.param(0).getJSONArray("options")) } },
            "min above max" to { night().also { it.param(1).put("min", 101) } },
            "no max" to { night().also { it.param(1).remove("max") } },
            "a half in a whole number's limits" to { night().also { it.param(1).put("min", 0.5) } },
            "a whole number's default out of range" to { night().also { it.param(1).put("default", 101) } },
            "a whole number's default as text" to { night().also { it.param(1).put("default", "20") } },
            "a unit Robot doesn't know" to { night().also { it.param(1).put("unit", "parsecs") } },
            "a min on a choice" to { night().also { it.param(0).put("min", 1) } },
            "a yes or no's default as text" to { night().also { it.param(2).put("default", "no") } },
            "a number beyond its limits" to { back().also { it.param(2).put("default", 30) } },
            "a script's name from a question" to { back().also { it.step(1).getJSONObject("args").put("script", "{{folder}}") } },
            "a script's name Termux can't run" to { back().also { it.step(1).getJSONObject("args").put("script", "../backup") } },
            "a step's output as a script's argument (D15)" to { back().also { it.getJSONArray("steps").put(JSONObject().put("use", "termux_script")
                .put("args", JSONObject().put("script", "backup").put("folder", "{{folder}}").put("days", "{{run.exit}}"))) } },
            "a travel time to Mars" to { hh().also { it.step(0).getJSONObject("args").put("to", "mars") } },
            "a travel time with words" to { hh().also { it.step(0).getJSONObject("args").put("via", "the bridge") } },
            "a connection's id" to { night().also { it.step(0).put("use", "conn:c-HA1234:light_dim") } },
            "a connection without its operation" to { night().also { it.step(0).put("use", "conn:c-ha1234") } },
            "conn alone" to { night().also { it.step(0).put("use", "conn") } },
            "an app hand-off with an operation" to { back().also { it.step(4).put("use", "app:c-ap9012:send") } },
            "an argument's name" to { back().also { it.step(4).getJSONObject("args").put("Text", "hi") } },
            "an unknown key in a step" to { hh().also { it.step(0).put("marks", JSONArray().put("money")) } },
            "an unknown key in a question" to { night().also { it.param(0).put("hint", "x") } },
            "a key-shaped fixed value" to { back().also { it.step(4).getJSONObject("args").put("text", "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") } },
            "a key-shaped check value" to { night().also { it.step(0).getJSONObject("check").put("equals", "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") } },
        )
        for ((what, make) in bad) {
            val o = make()
            assertEquals(what, SkillFile.Ok(listOf(pasta), 1), SkillCodec.decode(file(o, json(pasta), v = 2)))
        }
        // And each base is fine as it is.
        for (o in listOf(night(), back(), hh())) assertEquals(1, (SkillCodec.decode(file(o, json(pasta), v = 2)) as SkillFile.Ok).skills.size - 1)
    }
}
