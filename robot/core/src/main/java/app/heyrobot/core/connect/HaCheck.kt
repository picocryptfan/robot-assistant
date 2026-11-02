// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue

// Pure. Home Assistant's own check of a service call (plan §3.1, §5.5; C5): after `POST /api/services/<domain>/<service>`
// each entity the step named should be in the state the domain table gives (HaPreset.expected). The reply lists the
// states that changed during the call; an entity missing from it (already in that state, or a device that reports later)
// is read back once, `GET /api/states/<entity>`, after [READ_BACK_WAIT_MS] (EST), by ConnRunner. Then:
// - every entity Robot can check is in its state: PASSED, and the step's card says so in fixed words ("the downstairs
//   light turned off");
// - one is still moving there (a garage door "closing"): not a failure, never "done" for it either;
// - one is in another state: FAILED, and the skill stops with "Step 1 didn't do what it should: the downstairs light
//   didn't turn off" (ConnRan.what). Robot never says "done" for it.
// - a read-back that didn't come back, or a service whose result Robot can't tell (toggle, a scene, a thermostat's
//   temperature): Home Assistant's yes is all Robot knows, and it says only that.
// Scripts are never checked: `script.turn_on` returns before the script ends (VERIFIED, HaPreset's header).
//
// The words name an entity by its id, which you or your Home Assistant gave it (`light.downstairs` is "the downstairs
// light"), never by its friendly name: that is text from your Home Assistant, which other integrations fill (CN6), and
// stays information. An id Robot can't say (a Zigbee address in it, or too long) is "the light". A server's state text is
// never said: only the state Robot expected, from its own table.

object HaCheck {
    /** How long after the call Robot reads back an entity the reply didn't list (EST, plan §3.1). */
    const val READ_BACK_WAIT_MS = 2_000L

    /** At most this many entities are read back (EST): the rest count as not checked. */
    const val READ_BACK_MAX = 4

    /** The entities of a Home Assistant service step [s] with [values] whose reply [json] doesn't say where they are: read back after the wait. */
    fun toReadBack(s: OpSpec.HaCall, json: Any?, values: Map<String, SkillValue>): List<String> =
        HaPreset.seen(s, json, HaPreset.entities(values)).filterValues { it == HaPreset.Seen.NOT_IN_REPLY }.keys.take(READ_BACK_MAX)

    /** The read-back request for [entity] of connection [c] (`GET /api/states/<entity>`), built and checked as any step's. */
    fun readBack(c: Connection, entity: String): HttpRequest? {
        if (!ConnCheck.validEntity(entity)) return null
        val op = ConnOp(READ_BACK, READ_BACK, OpSpec.HaRead, mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf(entity), default = entity)))
        return (HaPreset.op(op).build(c, emptyMap()) as? Built.Ok)?.request
    }

    /** How a service step went, by entity: what Robot can say of each. */
    class Outcome(val seen: Map<String, HaPreset.Seen>, val service: String) {
        /** Some entity isn't in the state it should be. */
        val failed: Boolean get() = seen.values.any { it == HaPreset.Seen.WRONG }

        /** Every entity Robot could check is there (and at least one could be). */
        val passed: Boolean get() = !failed && seen.values.any { it == HaPreset.Seen.RIGHT } &&
            seen.values.all { it == HaPreset.Seen.RIGHT || it == HaPreset.Seen.CANT_CHECK }

        /** Robot checked something: the check counts (SKILL_CHECK), passed or not. */
        val checked: Boolean get() = failed || passed

        override fun toString(): String = "HaCheck.Outcome(${seen.values.groupingBy { it }.eachCount()})" // l10n: not text (toString, for the logs)
    }

    /**
     * Service step [s]'s check from its reply [json] and the states read back ([readBack]: entity to its state, null for a
     * read-back that didn't come back), for the entities in [values].
     */
    fun outcome(s: OpSpec.HaCall, json: Any?, values: Map<String, SkillValue>, readBack: Map<String, String?>): Outcome {
        val seen = HaPreset.seen(s, json, HaPreset.entities(values)).mapValues { (e, v) ->
            if (v != HaPreset.Seen.NOT_IN_REPLY) return@mapValues v
            val want = HaPreset.expected(s.service, e.substringBefore('.')) ?: return@mapValues HaPreset.Seen.CANT_CHECK
            // Not read back (over the cap, or it didn't come back): Home Assistant's yes is all Robot knows.
            readBack[e]?.let { HaPreset.judge(want, it) } ?: HaPreset.Seen.CANT_CHECK
        }
        return Outcome(seen, s.service)
    }

    /** "the downstairs light and the kitchen light didn't turn off": a failed check's words (ConnRan.what). */
    fun failedWords(o: Outcome): Phrase? = words(o, HaPreset.Seen.WRONG, done = false)

    /** "the downstairs light turned off": a passed check's words, for the step's card. */
    fun doneWords(o: Outcome): Phrase? = words(o, HaPreset.Seen.RIGHT, done = true)

    private fun words(o: Outcome, which: HaPreset.Seen, done: Boolean): Phrase? {
        val byState = o.seen.filterValues { it == which }.keys.groupBy { HaPreset.expected(o.service, it.substringBefore('.')) }
        val parts = byState.mapNotNull { (state, entities) ->
            val things = Phrase.ListOf(entities.map(::thing))
            val m = (if (done) DONE else NOT_DONE)[stateKind(state ?: return@mapNotNull null)] ?: return@mapNotNull null
            m(things)
        }
        return if (parts.isEmpty()) null else Phrase.ListOf(parts)
    }

    /** How Robot names [entity] (see the file's header): "the downstairs light", "the living room lights", "the light". */
    fun thing(entity: String): Phrase {
        val domain = entity.substringBefore('.')
        val obj = entity.substringAfter('.', "")
        val noun = NOUNS[domain]
        val words = obj.split('_').filter { it.isNotEmpty() }
        val sayable = obj.length in 1..MAX_ID && words.size <= MAX_WORDS && words.none { HEXISH.matches(it) }
        if (!sayable) return noun?.let { ConnStrings.haThe("words" to it()) } ?: ConnStrings.haIt()
        val said = words.joinToString(" ")
        // The id's own words in English (ids are written so): "living_room_lights" names its domain already.
        val word = noun?.invoke()?.en()
        val named = word == null || words.any { it.removeSuffix("s") == word }
        return if (named) ConnStrings.haThe("words" to said) else ConnStrings.haTheNoun("words" to said, "noun" to noun!!())
    }

    /** The expected states grouped by what Robot says of them. */
    private enum class Kind { ON, OFF, LOCKED, UNLOCKED, OPEN, CLOSED, ARMED, DISARMED }

    private fun stateKind(state: String): Kind? = when {
        state == "on" -> Kind.ON // l10n: not text (Home Assistant's state)
        state == "off" -> Kind.OFF // l10n: not text (Home Assistant's state)
        state == "locked" -> Kind.LOCKED // l10n: not text (Home Assistant's state)
        state == "unlocked" -> Kind.UNLOCKED // l10n: not text (Home Assistant's state)
        state == "open" -> Kind.OPEN // l10n: not text (Home Assistant's state)
        state == "closed" -> Kind.CLOSED // l10n: not text (Home Assistant's state)
        state == "disarmed" -> Kind.DISARMED // l10n: not text (Home Assistant's state)
        state.startsWith("armed_") -> Kind.ARMED // l10n: not text (Home Assistant's states)
        else -> null
    }

    private val DONE: Map<Kind, (Phrase) -> Phrase> = mapOf(
        Kind.ON to { t -> ConnStrings.haTurnedOn("things" to t) }, Kind.OFF to { t -> ConnStrings.haTurnedOff("things" to t) },
        Kind.LOCKED to { t -> ConnStrings.haLocked("things" to t) }, Kind.UNLOCKED to { t -> ConnStrings.haUnlocked("things" to t) },
        Kind.OPEN to { t -> ConnStrings.haOpened("things" to t) }, Kind.CLOSED to { t -> ConnStrings.haClosed("things" to t) },
        Kind.ARMED to { t -> ConnStrings.haArmed("things" to t) }, Kind.DISARMED to { t -> ConnStrings.haDisarmed("things" to t) },
    )

    private val NOT_DONE: Map<Kind, (Phrase) -> Phrase> = mapOf(
        Kind.ON to { t -> ConnStrings.haNotTurnedOn("things" to t) }, Kind.OFF to { t -> ConnStrings.haNotTurnedOff("things" to t) },
        Kind.LOCKED to { t -> ConnStrings.haNotLocked("things" to t) }, Kind.UNLOCKED to { t -> ConnStrings.haNotUnlocked("things" to t) },
        Kind.OPEN to { t -> ConnStrings.haNotOpened("things" to t) }, Kind.CLOSED to { t -> ConnStrings.haNotClosed("things" to t) },
        Kind.ARMED to { t -> ConnStrings.haNotArmed("things" to t) }, Kind.DISARMED to { t -> ConnStrings.haNotDisarmed("things" to t) },
    )

    /** Each checked domain's word, when its ids rarely say it ("the downstairs light"); covers and the rest go without. */
    private val NOUNS: Map<String, () -> Phrase> = mapOf(
        "light" to { ConnStrings.haLight() }, "switch" to { ConnStrings.haSwitch() }, "fan" to { ConnStrings.haFan() }, // l10n: not text (Home Assistant's domains)
        "lock" to { ConnStrings.haLock() }, "valve" to { ConnStrings.haValve() }, "alarm_control_panel" to { ConnStrings.haAlarm() }, // l10n: not text (Home Assistant's domains)
        "siren" to { ConnStrings.haSiren() }, // l10n: not text (Home Assistant's domain)
    )

    private const val READ_BACK = "read_back" // l10n: not text (an internal operation's id, never saved)
    private const val MAX_ID = 40
    private const val MAX_WORDS = 5
    /** A word Robot can't say: a Zigbee or MAC address's hex, or a long run of digits. */
    private val HEXISH = Regex("^(0x[0-9a-f]+|(?=[0-9a-f]*[0-9])(?=[0-9a-f]*[a-f])[0-9a-f]{6,}|[0-9]{5,})$")
}
