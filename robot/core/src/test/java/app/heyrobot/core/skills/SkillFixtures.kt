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

    // ------------------------------------------------------------------ skills connections (plan §5.1, C1): v2 skills

    /** The connections plan's example (§5.1), as written there: a travel time, two texts on its condition, the route. */
    fun headingHomeV2(id: String = "s-k3f9q2") = Skill(
        id = id, rev = 5, name = "Heading home", namesEn = listOf("heading home"), namesFr = listOf("je rentre"),
        params = listOf(SkillParam("who", ParamType.PERSON, "Sam", "Who should I tell?", "À qui je le dis?")),
        steps = listOf(
            SkillStep.of(StepKind.TRAVEL_TIME, mapOf("to" to "home"), id = "eta", onFail = OnFail.GO_ON),
            SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "On my way home, about {{eta.minutes}} min."),
                cond = SkillIf(SkillRef("eta", "minutes"), IfTest.Set)),
            SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{who}}", "message" to "On my way home."), cond = SkillIf(SkillRef("eta", "minutes"), IfTest.Unset)),
            SkillStep.of(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home")),
        ),
        level = SkillLevel.ASK_FIRST, provenance = SkillProvenance(SkillMade.SETTINGS, false, 1_761_000_000_000L, 1_761_000_000_000L), perHour = 12,
    )

    const val HA = "c-ha1234"
    const val NTFY = "c-nt5678"
    const val SIGNAL = "c-ap9012"

    val room = SkillParam("room", ParamType.CHOICE, "light.downstairs", "Which room?", "Quelle pièce?",
        options = listOf(SkillOption("light.downstairs", "Downstairs", "En bas"), SkillOption("light.kitchen", "Kitchen", "Cuisine", mapOf("es" to "Cocina"))))
    val level = SkillParam("level", ParamType.INTEGER, "20", "How bright?", "Quelle intensité?", min = 0.0, max = 100.0, unit = "percent")
    val lockUp = SkillParam("lock", ParamType.YES_NO, "false", "Lock up too?", "Barrer aussi?")

    /** Home Assistant: dim a room's light (a choice and a whole number), lock up on a yes, then say so. */
    fun goodNight(id: String = "s-night1") = Skill(
        id = id, rev = 2, name = "Good night", namesEn = listOf("good night"), namesFr = listOf("bonne nuit"),
        params = listOf(room, level, lockUp),
        steps = listOf(
            SkillStep.of(StepKind.CONN, mapOf("entity" to "{{room}}", "brightness" to "{{level}}"), id = "lights", conn = HA, op = "light_dim",
                check = StepCheck.Equals("/0/state", "on")),
            SkillStep.of(StepKind.CONN, mapOf("entity" to "lock.front_door"), conn = HA, op = "lock", cond = SkillIf(SkillRef("lock"), IfTest.Yes(true))),
            SkillStep.of(StepKind.SAY, mapOf("text" to "Good night: {{room}} at {{level}} percent.")),
        ),
        level = SkillLevel.ASK_FIRST, provenance = SkillProvenance(SkillMade.SETTINGS, false, 2_000L, 3_000L),
    )

    val temp = SkillParam("temp", ParamType.NUMBER, "20.5", "What temperature?", "Quelle température?", min = 15.0, max = 28.0, unit = "degrees")
    val folder = SkillParam("folder", ParamType.CHOICE, "documents", "Which folder?", "Quel dossier?",
        options = listOf(SkillOption("photos", "Photos", "Photos"), SkillOption("documents", "Documents", "Documents")))
    val days = SkillParam("days", ParamType.INTEGER, "7", "Keep how many days?", "Garder combien de jours?", min = 1.0, max = 30.0)

    /** Your script with two typed values, a check on its output, then a hand-off and a notification: every other new step. */
    fun backUp(id: String = "s-back01") = Skill(
        id = id, rev = 1, name = "Back up", namesEn = listOf("back up"), namesFr = listOf("sauvegarde"),
        params = listOf(folder, days, temp),
        steps = listOf(
            SkillLibrary.fixed(StepKind.READ_CALENDAR, mapOf("day" to "today")).copy(onFail = OnFail.GO_ON),
            SkillStep.of(StepKind.TERMUX_SCRIPT, mapOf("script" to "backup", "folder" to "{{folder}}", "days" to "{{days}}"), id = "run",
                check = StepCheck.Matches("^done")),
            SkillStep.of(StepKind.CONN, mapOf("message" to "Backed up {{folder}}: exit {{run.exit}}"), conn = NTFY, op = "publish"),
            SkillStep.of(StepKind.CONN, mapOf("temperature" to "{{temp}}"), conn = HA, op = "set_temp", cond = SkillIf(SkillRef("temp"), IfTest.Above(18.0))),
            SkillStep.of(StepKind.APP, mapOf("text" to "Backed up {{folder}}"), conn = SIGNAL),
        ),
        level = SkillLevel.ASK_FIRST, provenance = SkillProvenance(SkillMade.SETTINGS, false, 4_000L, 4_000L), perHour = 3,
    )

    /** Links for the skills above: Home Assistant's light, lock and thermostat, ntfy's publish, Signal's hand-off, the backup script, a routing server. */
    val links: SkillLinks = object : SkillLinks {
        override fun op(conn: String, op: String): LinkedOp? = when (conn to op) {
            HA to "light_dim" -> LinkedOp(mapOf("entity" to SlotType(ParamType.CHOICE, listOf("light.downstairs", "light.kitchen", "light.porch")),
                "brightness" to SlotType(ParamType.INTEGER)), outputs = mapOf("state" to SlotType(ParamType.CHOICE, listOf("on", "off")),
                "name" to SlotType(ParamType.TEXT)), ha = HaCall("light", "turn_on"))
            HA to "lock" -> LinkedOp(mapOf("entity" to SlotType(ParamType.TEXT)), ha = HaCall("lock", "lock"))
            HA to "set_temp" -> LinkedOp(mapOf("temperature" to SlotType(ParamType.NUMBER)), ha = HaCall("climate", "set_temperature"))
            HA to "state" -> LinkedOp(mapOf("entity" to SlotType(ParamType.CHOICE, listOf("light.kitchen"))), read = true, effects = setOf(SkillEffect.READS_ONLINE),
                outputs = mapOf("state" to SlotType(ParamType.CHOICE, listOf("on", "off")), "name" to SlotType(ParamType.TEXT),
                    "level" to SlotType(ParamType.NUMBER), "on" to SlotType(ParamType.YES_NO)))
            NTFY to "publish" -> LinkedOp(mapOf("message" to SlotType(ParamType.TEXT)), effects = SkillEffects.ntfy(onlyMyDevices = false))
            else -> null
        }

        override fun handOff(conn: String): LinkedHandOff? =
            if (conn == SIGNAL) LinkedHandOff(mapOf("text" to SlotType(ParamType.TEXT)), SkillEffects.handOff("SEND", null)) else null

        override fun script(name: String): LinkedScript? = if (name == "backup") LinkedScript(listOf("folder" to SlotType(ParamType.CHOICE, listOf("photos", "documents", "music")),
            "days" to SlotType(ParamType.INTEGER)), SkillEffects.script(listOf("online"))) else null

        override fun routing(conn: String?): Boolean = conn == null
    }
}
