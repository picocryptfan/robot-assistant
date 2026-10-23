// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plan §7.2, D10, D11: the values in your words after a skill's name, English and French; leftover words aren't claimed. */
class SkillValuesTest {
    private fun skill(vararg params: SkillParam): Skill {
        val steps = params.map { p ->
            when (p.type) {
                ParamType.DURATION -> SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to "{{${p.name}}}"))
                ParamType.TIME -> SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to "{{${p.name}}}"))
                ParamType.PERSON -> SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{${p.name}}}", "message" to "hi"))
                ParamType.TEXT -> SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "{{${p.name}}}"))
                ParamType.APP -> SkillStep.of(StepKind.OPEN_APP, mapOf("name" to "{{${p.name}}}"))
                ParamType.PLACE -> SkillStep.of(StepKind.MAPS_SEARCH, mapOf("query" to "{{${p.name}}}"))
            }
        }
        return Skill("s-value1", 1, "Thing", listOf("thing"), listOf("truc"), params.toList(), steps, SkillLevel.ASK_FIRST,
            SkillProvenance(SkillMade.SETTINGS, false, 1L, 1L))
    }

    private val length = SkillParam("length", ParamType.DURATION, "720", "How long?", "Combien de temps?")
    private val time = SkillParam("time", ParamType.TIME, "07:00", "What time?", "À quelle heure?")
    private val who = SkillParam("who", ParamType.PERSON, "Sam", "Who to?", "À qui?")
    private val message = SkillParam("message", ParamType.TEXT, "hi", "What should it say?", "Ça dit quoi?")
    private val app = SkillParam("app", ParamType.APP, "Camera", "Which app?", "Quelle appli?")
    private val place = SkillParam("place", ParamType.PLACE, "coffee", "Where to?", "Où?")

    private fun run(text: String, s: Skill) = (SkillRouter.route(text, listOf(s), people = listOf("Marie", "Sam", "Mom"), apps = listOf("Strava", "Organic Maps"))
        as? SkillCommand.Run)?.values

    @Test fun durations() {
        val s = skill(length)
        assertEquals(mapOf("length" to "480"), run("thing for 8 minutes", s))
        assertEquals(mapOf("length" to "480"), run("truc pour 8 minutes", s))
        assertEquals(mapOf("length" to "3600"), run("thing for an hour", s))
        assertEquals(mapOf("length" to "5400"), run("thing for 1 hour and 30 minutes", s))
        assertEquals(mapOf("length" to "2700"), run("thing for forty five minutes", s))
        assertEquals(mapOf("length" to "90"), run("lance truc pour 90 secondes", s))
        assertNull(run("thing for 30 hours", s))
        assertNull(run("thing for a bit", s))
    }

    @Test fun times() {
        val s = skill(time)
        assertEquals(mapOf("time" to "06:30"), run("thing at 6:30", s))
        assertEquals(mapOf("time" to "19:00"), run("thing at 7 pm", s))
        assertEquals(mapOf("time" to "07:00"), run("truc à 7 h", s))
        assertEquals(mapOf("time" to "12:00"), run("truc à midi", s))
    }

    @Test fun peopleAppsAndPlaces() {
        assertEquals(mapOf("who" to "Marie"), run("thing to Marie", skill(who)))
        assertEquals(mapOf("who" to "Marie"), run("truc à Marie", skill(who)))
        assertEquals(mapOf("who" to "Mom"), run("run thing to mom", skill(who)))
        // Someone not in People: not read.
        assertNull(run("thing to Bob", skill(who)))
        assertEquals(mapOf("app" to "Strava"), run("thing with Strava", skill(app)))
        assertEquals(mapOf("place" to "the library"), run("thing to the library", skill(place)))
        assertEquals(mapOf("place" to "la bibliothèque"), run("truc vers la bibliothèque", skill(place)))
    }

    @Test fun aTextOnlyAfterAnExplicitWord() {
        val s = skill(message)
        assertEquals(mapOf("message" to "the standup moved to 10"), run("thing saying the standup moved to 10", s))
        assertEquals(mapOf("message" to "the standup moved to 10"), run("thing that says the standup moved to 10", s))
        assertEquals(mapOf("message" to "le standup est à 10 h"), run("truc qui dit le standup est à 10 h", s))
        assertEquals(mapOf("message" to "on arrive"), run("truc que on arrive", s))
        // Without the word, the leftover words go on as ordinary talk (phase B gives them to the model).
        assertNull(run("thing the standup moved to 10", s))
        // Two text questions: none is taken from words.
        assertNull(run("thing saying hi", skill(message, message.copy(name = "other"))))
    }

    @Test fun severalValuesInOrder() {
        val s = skill(length, who)
        assertEquals(mapOf("length" to "600", "who" to "Sam"), run("thing for 10 minutes to Sam", s))
        assertEquals(mapOf("who" to "Sam", "length" to "600"), run("thing to Sam for 10 minutes", s))
        // A value of a type the skill doesn't ask for.
        assertNull(run("thing at 7", s))
        assertEquals(Lang.FR, (SkillRouter.route("truc pour 8 minutes", listOf(skill(length))) as SkillCommand.Run).lang)
    }
}
