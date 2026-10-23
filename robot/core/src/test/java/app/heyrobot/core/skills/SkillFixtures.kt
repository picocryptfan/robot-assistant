// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

/** Skills the tests share: "Heading home" (plan §3's example) and "Pasta" (SK3). */
object SkillFixtures {
    fun headingHome(id: String = "s-k3f9q2", level: SkillLevel = SkillLevel.ASK_FIRST, fromContent: Boolean = false) = Skill(
        id = id, rev = 3, name = "Heading home", namesEn = listOf("heading home", "on my way home"), namesFr = listOf("je rentre"),
        params = listOf(SkillParam("who", ParamType.PERSON, "Sam", "Who should I tell?", "À qui je le dis?")),
        steps = listOf(
            SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "On my way home.")),
            SkillStep.of(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home")),
        ),
        level = level, provenance = SkillProvenance(SkillMade.VOICE, fromContent, 1_760_800_000_000L, 1_760_800_000_000L),
    )

    fun pasta(id: String = "s-pasta1") = Skill(
        id = id, rev = 1, name = "Pasta", namesEn = listOf("pasta"), namesFr = emptyList(),
        params = listOf(SkillParam("length", ParamType.DURATION, "720", "How long?", "Combien de temps?")),
        steps = listOf(
            SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to "{{length}}")),
            SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Marie", "message" to "pasta's on")),
        ),
        level = SkillLevel.ASK_FIRST, provenance = SkillProvenance(SkillMade.DEMO, false, 1_000L, 1_000L),
    )

    /** A one-step skill named [name] (a fixed timer). */
    /** Heading home as a later Robot (1.3) would write it: Spanish and German names and question beside 1.1's. */
    fun headingHomeMore(id: String = "s-k3f9q2", level: SkillLevel = SkillLevel.ASK_FIRST) = headingHome(id, level).let { h ->
        h.copy(namesMore = mapOf("es" to listOf("voy a casa"), "de" to listOf("ich fahre heim")),
            params = h.params.map { it.copy(askMore = mapOf("es" to "¿A quién se lo digo?", "de" to "Wem soll ich es sagen?")) })
    }

    fun simple(id: String, name: String, seconds: Long = 60L) = Skill(id, 1, name, listOf(name.lowercase()), emptyList(), emptyList(),
        listOf(SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to seconds))), SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.SETTINGS, false, 1L, 1L))
}
