// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.SkillStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillCheck (plan §3): every limit, only 1.1's step kinds, templates of the argument's type, no secrets. Skills
 * connections (plan §5.1, §7.1): templates naming something earlier of a type their place takes, free text never deciding
 * or becoming a script's argument, "go on" only after a read, and a link that's gone marking the skill, never dropping it.
 */
class SkillCheckTest {
    private val home = SkillFixtures.headingHome()

    private fun bad(s: Skill) = assertFalse(s.toString(), SkillCheck.valid(s))

    @Test fun theFixturesAreValid() {
        assertEquals(emptyList<Any>(), SkillCheck.problems(home))
        assertEquals(emptyList<Any>(), SkillCheck.problems(SkillFixtures.pasta()))
    }

    @Test fun everyLimit() {
        bad(home.copy(id = "k3f9q2"))
        bad(home.copy(id = "s-K3F9Q2"))
        bad(home.copy(rev = 0))
        bad(home.copy(name = ""))
        bad(home.copy(name = "x".repeat(41)))
        assertTrue(SkillCheck.valid(home.copy(name = "x".repeat(40))))
        bad(home.copy(name = "Heading\nhome"))
        bad(home.copy(name = "Heading​home"))
        bad(home.copy(name = " Heading home"))
        bad(home.copy(name = "!!!"))
        bad(home.copy(namesEn = (1..7).map { "name $it" }))
        bad(home.copy(namesFr = listOf("un deux trois quatre cinq six")))
        bad(home.copy(namesEn = listOf("heading, home")))
        assertTrue(SkillCheck.valid(home.copy(namesFr = listOf("je m'en viens"))))
        // Questions: at most 3, a name of the right shape, unique, used by a step, an ask of at most 60 characters.
        val p = home.params[0]
        bad(home.copy(params = listOf(p, p.copy(name = "a"), p.copy(name = "b"), p.copy(name = "c"))))
        bad(home.copy(params = listOf(p.copy(name = "Who"))))
        bad(home.copy(params = listOf(p, p)))
        bad(home.copy(params = listOf(p, p.copy(name = "unused"))))
        bad(home.copy(params = listOf(p.copy(askEn = "x".repeat(61)))))
        bad(home.copy(params = listOf(p.copy(askFr = ""))))
        // Steps: 1 to 8.
        bad(home.copy(steps = emptyList()))
        val timer = SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to 60L))
        val nine = SkillFixtures.simple("s-nine99", "Nine").copy(steps = List(9) { timer })
        bad(nine)
        assertTrue(SkillCheck.valid(nine.copy(steps = List(8) { timer })))
        // A sentence to say: one line, at most 200 characters.
        val say = SkillFixtures.simple("s-say123", "Good night")
        assertTrue(SkillCheck.valid(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "good night"))))))
        bad(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "x".repeat(201))))))
        bad(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "a\nb")))))
        bad(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("words" to "hi")))))
    }

    @Test fun everyStepKindSkillsNeverUseIsRefused() {
        for (t in StepKind.NEVER) assertNull(t.id, StepKind.of(t))
        for (t in listOf(ToolName.CALL, ToolName.REMEMBER, ToolName.FORGET, ToolName.ASK_HELPER, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE,
                ToolName.TERMUX_COMMAND, ToolName.TERMUX_SCRIPT, ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH)) assertTrue(t in StepKind.NEVER)
        // Every other tool is a step kind.
        for (t in ToolName.entries - StepKind.NEVER) assertEquals(t, StepKind.of(t)?.tool)
    }

    @Test fun templatesNameAQuestionOfTheArgumentsType() {
        // A person question can't fill the message.
        bad(home.copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "{{who}}")))))
        // A template naming no question.
        bad(home.copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{whom}}", "message" to "hi")))))
        // Something that only looks like a template.
        bad(home.copy(params = emptyList(), steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{ who }}", "message" to "hi")))))
        // An argument that is never a question (the flashlight's switch).
        val on = SkillParam("on", ParamType.TEXT, "true", "On?", "Allumée?")
        bad(home.copy(params = listOf(on), steps = listOf(SkillStep.of(StepKind.FLASHLIGHT, mapOf("on" to "{{on}}")))))
        // An argument the tool doesn't have.
        bad(home.copy(steps = listOf(SkillStep.of(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home", "mode" to "walk")))))
    }

    @Test fun aQuestionAlwaysHasAValidDefault() {
        val p = home.params[0]
        bad(home.copy(params = listOf(p.copy(default = ""))))
        bad(home.copy(params = listOf(p.copy(default = "[Recipient Name]"))))
        assertTrue(SkillCheck.validDefault(ParamType.DURATION, "720"))
        assertFalse(SkillCheck.validDefault(ParamType.DURATION, "0"))
        assertFalse(SkillCheck.validDefault(ParamType.DURATION, "86401"))
        assertFalse(SkillCheck.validDefault(ParamType.DURATION, "12 minutes"))
        assertTrue(SkillCheck.validDefault(ParamType.TIME, "07:30"))
        assertFalse(SkillCheck.validDefault(ParamType.TIME, "7:30"))
        assertFalse(SkillCheck.validDefault(ParamType.TIME, "24:00"))
    }

    @Test fun aTimeQuestionFillsTheAlarmsHour() {
        val wake = SkillFixtures.simple("s-wake12", "Wake").copy(params = listOf(SkillParam("t", ParamType.TIME, "07:30", "What time?", "À quelle heure?")),
            steps = listOf(SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to "{{t}}"))))
        assertTrue(SkillCheck.problems(wake).toString(), SkillCheck.valid(wake))
        assertEquals(Action.SetAlarm(7, 30), SkillCheck.action(wake, wake.steps[0]))
        assertEquals(Action.SetAlarm(6, 5), SkillCheck.action(wake, wake.steps[0], mapOf("t" to "06:05")))
        bad(wake.copy(steps = listOf(SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to "{{t}}", "minute" to 0L)))))
    }

    @Test fun nothingThatLooksLikeAPasswordOrKey() {
        val secret = SkillStrings.secret()
        val text = home.copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "the door code is 4512")), home.steps[1]),
            params = emptyList())
        assertTrue(secret in SkillCheck.problems(text))
        assertTrue(secret in SkillCheck.problems(home.copy(params = listOf(home.params[0].copy(default = "sk-ant-api03-abcdef1234567890ABCDEF")))))
        val say = SkillFixtures.simple("s-say123", "Wifi").copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "my password is Tr0ub4dor"))))
        assertTrue(secret in SkillCheck.problems(say))
        // A phone number isn't a secret.
        assertTrue(SkillCheck.valid(home.copy(params = emptyList(), steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "+1 514 555 0101", "message" to "hi"))))))
    }

    @Test fun madeFromContentStaysAtAskFirstOrOff() {
        bad(SkillFixtures.headingHome(fromContent = true, level = SkillLevel.JUST_DO_IT))
        assertTrue(SkillCheck.valid(SkillFixtures.headingHome(fromContent = true, level = SkillLevel.OFF)))
        assertTrue(SkillCheck.valid(SkillFixtures.headingHome(level = SkillLevel.JUST_DO_IT)))
    }

    // ------------------------------------------------------------------ skills connections (plan §5.1, §7.1)

    private val links = SkillFixtures.links
    private val night = SkillFixtures.goodNight()
    private val back = SkillFixtures.backUp()
    private val hh = SkillFixtures.headingHomeV2()

    @Test fun theV2FixturesAreValidAndRunWithTheirLinks() {
        for (s in listOf(night, back, hh)) {
            assertEquals(s.name, emptyList<Any>(), SkillCheck.problems(s))
            assertEquals(s.name, emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        }
    }

    @Test fun templatesNameSomethingEarlierOfATypeTheirPlaceTakes() {
        fun msg(m: String) = hh.copy(steps = hh.steps.mapIndexed { i, st -> if (i == 1) st.copy(args = st.args + ("message" to m)) else st })
        assertTrue(SkillCheck.valid(msg("Hi {{who}}, about {{eta.minutes}} min ({{eta.km}} km).")))
        // An output as the whole message.
        assertTrue(SkillCheck.valid(msg("{{eta.minutes}}")))
        bad(msg("About {{eta.hours}} h."))
        bad(msg("About {{nobody.minutes}} min."))
        bad(msg("About {{eta.minutes} min."))
        bad(msg("About {{ eta.minutes }} min."))
        bad(msg("About }}eta.minutes{{ min."))
        // A step's output is from an earlier step only.
        val later = hh.copy(steps = listOf(hh.steps[1], hh.steps[0]) + hh.steps.drop(2))
        bad(later)
        // A step without an id has no outputs to name.
        bad(hh.copy(steps = hh.steps.mapIndexed { i, st -> if (i == 0) st.copy(id = null) else st }))
        // Inside words a question needs words: a whole number yes, a duration's seconds or a yes or no no.
        val n = SkillFixtures.level.copy(name = "n")
        assertTrue(SkillCheck.valid(home.copy(params = home.params + n, steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "{{n}} min"))))))
        val secs = SkillParam("secs", ParamType.DURATION, "60", "How long?", "Combien de temps?")
        bad(home.copy(params = home.params + secs, steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "{{secs}} s")))))
        // Outputs never fill a typed argument of Robot's own tools, whole or inside words.
        bad(hh.copy(steps = hh.steps + SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to "{{eta.minutes}}"))))
        // One pass (as 1.1): a default holding a template is words, never read again.
        val text = SkillParam("words", ParamType.TEXT, "{{who}}", "What?", "Quoi?")
        val words = home.copy(params = home.params + text, steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "{{words}}"))))
        assertTrue(SkillCheck.valid(words))
        assertEquals(mapOf("to" to "Sam", "message" to "{{who}}"), words.withValues(words.steps[0]))
    }

    @Test fun freeTextNeverDecidesAndNothingAStepReadIsAScriptsArgument() {
        // A condition: never on free text (a text, person, app or place question, or a script's words).
        for (p in listOf(SkillParam("t", ParamType.TEXT, "x", "What?", "Quoi?"), SkillParam("t", ParamType.PLACE, "home", "Where?", "Où?")))
            bad(home.copy(params = home.params + p, steps = home.steps.mapIndexed { i, st ->
                if (i == 0) st.copy(args = st.args + ("message" to "{{t}}")) else st.copy(cond = SkillIf(SkillRef("t"), IfTest.Set)) }))
        val withOut = back.copy(steps = back.steps + SkillStep.of(StepKind.SAY, mapOf("text" to "Done."), cond = SkillIf(SkillRef("run", "out"), IfTest.Set)))
        bad(withOut)
        // A typed output may decide: the script's exit, a travel time, Home Assistant's state.
        assertTrue(SkillCheck.valid(back.copy(steps = back.steps + SkillStep.of(StepKind.SAY, mapOf("text" to "Done."), cond = SkillIf(SkillRef("run", "exit"), IfTest.Below(1.0))))))
        val read = SkillStep.of(StepKind.CONN, mapOf("entity" to "light.kitchen"), id = "k", conn = SkillFixtures.HA, op = "state", onFail = OnFail.GO_ON)
        fun after(c: SkillIf) = night.copy(params = night.params.filter { it.name != "lock" },
            steps = listOf(read) + night.steps.take(1) + SkillStep.of(StepKind.SAY, mapOf("text" to "On."), cond = c))
        assertTrue(SkillCheck.valid(after(SkillIf(SkillRef("k", "state"), IfTest.Is("on")))))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(after(SkillIf(SkillRef("k", "state"), IfTest.Is("on"))), links))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(after(SkillIf(SkillRef("k", "level"), IfTest.Above(3.0))), links))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(after(SkillIf(SkillRef("k", "on"), IfTest.Yes(true))), links))
        // A connection's free text, a state it never has, or a test that doesn't fit: the skill needs attention.
        for (c in listOf(SkillIf(SkillRef("k", "name"), IfTest.Set), SkillIf(SkillRef("k", "state"), IfTest.Is("dim")),
                SkillIf(SkillRef("k", "state"), IfTest.Below(2.0)), SkillIf(SkillRef("k", "nothing"), IfTest.Set)))
            assertEquals(c.toString(), listOf(SkillAttention(2, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(after(c), links))
        // D15: nothing a step brought back is a Termux script's argument, typed or not.
        for (o in listOf("out", "exit"))
            bad(back.copy(steps = back.steps + SkillStep.of(StepKind.TERMUX_SCRIPT, mapOf("script" to "backup", "folder" to "{{folder}}", "days" to "{{run.$o}}"))))
        // A question may be one (yours, or the model's within its type).
        assertTrue(SkillCheck.valid(back))
    }

    @Test fun goOnOnlyAfterARead() {
        assertTrue(SkillCheck.valid(hh))
        assertTrue(SkillCheck.valid(back))
        bad(hh.copy(steps = hh.steps.map { it.copy(onFail = OnFail.GO_ON) }))
        bad(back.copy(steps = back.steps.mapIndexed { i, st -> if (i == 1) st.copy(onFail = OnFail.GO_ON) else st }))
        // A connection's operation says for itself: a read may go on, a change may not.
        val read = SkillStep.of(StepKind.CONN, mapOf("entity" to "light.kitchen"), id = "k", conn = SkillFixtures.HA, op = "state", onFail = OnFail.GO_ON)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(night.copy(steps = listOf(read) + night.steps), links))
        val change = night.copy(steps = night.steps.mapIndexed { i, st -> if (i == 0) st.copy(onFail = OnFail.GO_ON) else st })
        assertTrue(SkillCheck.valid(change))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(change, links))
    }

    @Test fun keyShapedFixedValuesAreRefusedEverywhere() {
        val key = "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        fun has(s: Skill) = assertTrue(s.toString(), SkillStrings.secret() in SkillCheck.problems(s))
        has(back.copy(steps = back.steps.mapIndexed { i, st -> if (i == 4) st.copy(args = mapOf("text" to key)) else st }))
        has(back.copy(steps = back.steps.mapIndexed { i, st -> if (i == 4) st.copy(args = mapOf("text" to "Token $key for {{folder}}")) else st }))
        has(night.copy(steps = night.steps.mapIndexed { i, st -> if (i == 0) st.copy(check = StepCheck.Equals("/0/state", key)) else st }))
        has(night.copy(params = listOf(SkillFixtures.room.copy(options = SkillFixtures.room.options + SkillOption(key, "Key", "Clé"))) + night.params.drop(1)))
        has(hh.copy(steps = hh.steps.mapIndexed { i, st -> if (i == 1) st.copy(args = st.args + ("message" to "$key {{eta.minutes}}")) else st }))
    }

    /**
     * Design §3.8, plan §5.2: a skill whose connection, operation, app, script or routing server is gone is kept (never
     * dropped from the file), and needs attention: it doesn't run, isn't described to the model, until it's fixed.
     */
    @Test fun aMissingLinkNeedsAttentionAndTheSkillIsKept() {
        // Before connections exist (C1), every such step needs attention.
        assertEquals(listOf(SkillAttention(0, AttentionWhy.CONNECTION_GONE), SkillAttention(1, AttentionWhy.CONNECTION_GONE)), SkillCheck.attention(night))
        assertEquals(listOf(SkillAttention(1, AttentionWhy.SCRIPT_GONE), SkillAttention(2, AttentionWhy.CONNECTION_GONE),
            SkillAttention(3, AttentionWhy.CONNECTION_GONE), SkillAttention(4, AttentionWhy.APP_GONE)), SkillCheck.attention(back))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.NO_ROUTING)), SkillCheck.attention(hh))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.NO_ROUTING)),
            SkillCheck.attention(hh.copy(steps = listOf(hh.steps[0].copy(args = mapOf("to" to "home", "conn" to "c-zz9999"))) + hh.steps.drop(1)), links))
        // Kept in the file all the same, and Robot's own skills need none.
        assertTrue(SkillCheck.valid(night))
        assertEquals(SkillFile.Ok(listOf(night)), SkillCodec.decode(SkillCodec.encode(listOf(night))))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(home))
        // Arguments that no longer fit the operation.
        fun step0(args: Map<String, Any?>) = night.copy(steps = listOf(night.steps[0].copy(args = args)) + night.steps.drop(1))
        val fit = listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT))
        assertEquals(fit, SkillCheck.attention(step0(mapOf("entity" to "{{room}}", "brightness" to "{{room}}")), links))
        assertEquals(fit, SkillCheck.attention(step0(mapOf("entity" to "{{room}}")), links))
        assertEquals(fit, SkillCheck.attention(step0(mapOf("entity" to "{{room}}", "brightness" to "{{level}}", "color" to "red")), links))
        assertEquals(fit, SkillCheck.attention(step0(mapOf("entity" to "{{room}}", "brightness" to "bright")), links))
        assertEquals(fit, SkillCheck.attention(step0(mapOf("entity" to "light.attic", "brightness" to 20L)), links))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(step0(mapOf("entity" to "light.porch", "brightness" to 20L)), links))
        // A choice whose values aren't all the slot's.
        val wide = SkillFixtures.room.copy(options = SkillFixtures.room.options + SkillOption("light.attic", "Attic", "Grenier"))
        assertEquals(fit, SkillCheck.attention(night.copy(params = listOf(wide) + night.params.drop(1)), links))
        // Home Assistant: an entity Robot can't tell (from free text) or "all" on a lock.
        val what = SkillParam("what", ParamType.TEXT, "lock.front_door", "Which?", "Lequel?")
        val free = night.copy(params = night.params + what, steps = night.steps.take(1) +
            SkillStep.of(StepKind.CONN, mapOf("entity" to "{{what}}"), conn = SkillFixtures.HA, op = "lock") + night.steps.drop(2))
        assertEquals(listOf(SkillAttention(1, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(free.copy(params = free.params.filter { it.name != "lock" }), links))
        val all = night.copy(steps = night.steps.mapIndexed { i, st -> if (i == 1) st.copy(args = mapOf("entity" to "all")) else st })
        assertEquals(listOf(SkillAttention(1, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(all, links))
        // It doesn't run, and isn't described to the model.
        assertTrue(SkillPolicy.decide(night, locked = false, phoneLock = false, messages = false) is SkillGo.Refused)
        assertEquals("“Good night” needs attention. Check its steps in Settings › Skills.",
            (SkillPolicy.decide(night, locked = false, phoneLock = false, messages = false) as SkillGo.Refused).why.render(app.heyrobot.core.i18n.Langs.EN))
        assertEquals(SkillGo.Card(tapOnly = false), SkillPolicy.decide(night, locked = false, phoneLock = false, messages = false, links = links))
        assertTrue(SkillRisk.decide(night, named = true, filled = true, tier = SkillTier.SIMPLE, trusted = false, locked = false, phoneLock = false,
            messages = false) is ModelGo.Refused)
        assertEquals(emptyList<Skill>(), SkillSelect.Index(listOf(night)).pick("good night", named = night))
        assertEquals(listOf(night), SkillSelect.Index(listOf(night), links).pick("good night", named = night))
    }
}
