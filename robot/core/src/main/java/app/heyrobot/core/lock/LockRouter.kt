// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.i18n.Lang
import java.text.Normalizer
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

// Pure. Phone lock by voice, English and Québec French, decided by code before anything reaches the model,
// anchored at the start of what you said (only "hey robot", "please" and the like in front), like RoutineRouter. It runs
// only on what you said, never on text Robot reads aloud. "Lock the phone until 7" becomes a card; "how long is left"
// an answer; "end the lock" the code pad on the screen (Robot never takes a spoken code). It must not misfire: "lock the
// door", "I'm locked out", "the screen is locked", "until 7 I was at work", « barre la porte », « mon téléphone est
// barré ». "Lock the phone" with no time isn't one: it goes to the model as before.

sealed class LockCommand {
    abstract val lang: Lang

    /**
     * "Lock the phone until 7" ([until]; [ambiguous]: "7" alone, the next 7:00, AM or PM, whichever comes first) or "for an
     * hour" ([forLength]).
     */
    data class Start(val until: LocalTime?, val forLength: Duration?, override val lang: Lang, val ambiguous: Boolean = false) : LockCommand() {
        /** When the lock ends, said at [now]. */
        fun end(now: ZonedDateTime): ZonedDateTime = forLength?.let { now.plus(it) } ?: LockRouter.next(until!!, now, ambiguous)
    }

    /** "How long is left", "when does my phone open", "is the phone locked". */
    data class Ask(override val lang: Lang) : LockCommand()

    /** "End the lock", "stop the phone lock", "unlock the phone". */
    data class End(override val lang: Lang) : LockCommand()

    /** "Skip tonight's lock" ([name] null: the next one), "skip Bedtime" ([name]: one of your locks' names, as saved). */
    data class Skip(val name: String?, override val lang: Lang) : LockCommand()
}

object LockRouter {
    /** Words that may come before a command and change nothing. */
    private val LEAD = Regex("""^(?:(?:hey|ok|okay|hi)\s+)?(?:robot[,!.]?\s+)?(?:(?:please|can you|could you|would you|will you|peux-tu|pourrais-tu|est-ce que tu peux|s'il te plait|stp)[,]?\s+)*""")
    private val TAIL = Regex("""[\s,]*(?:please|s'il te plait|stp|svp)?[\s.!?]*$""")

    private fun plain(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace('’', '\'').replace(Regex("\\s+"), " ").trim()

    /** The phone, as you name it. */
    private const val PHONE_EN = """(?:the|my|this) (?:phone|cell ?phone|cell)"""
    private const val PHONE_FR = """(?:le|mon|ce) (?:telephone|cell|cellulaire)"""
    private const val LOCK_FR = """(?:barre|bloque|verrouille)"""

    private val START_EN = Regex("""^lock $PHONE_EN (until|till|til|for) (.+)$""")
    private val START_FR = Regex("""^$LOCK_FR $PHONE_FR (jusqu'a|jusqu'au|pour|pendant|durant) (.+)$""")
    /** "How long is left" alone could be a timer's: it is Phone lock's only while a lock is on. */
    private val ASK_BARE = listOf(
        Regex("""^how (?:long|much time) is (?:left|remaining)$"""),
        Regex("""^combien (?:de temps )?(?:il )?(?:reste|il reste)(?:-t-il)?(?: de temps)?$"""),
        Regex("""^combien de temps (?:il )?reste(?:-t-il)?$"""),
    )
    private val ASK = listOf(
        Regex("""^how (?:long|much time) is (?:left|remaining) on (?:the|my) phone lock$"""),
        Regex("""^how long (?:until|till|before) (?:the|my) phone (?:opens|unlocks)$"""),
        Regex("""^when (?:does|will) (?:the|my) phone (?:open|unlock)$"""),
        Regex("""^is (?:the|my) phone locked$"""),
        Regex("""^(?:a|vers) quelle heure (?:mon|le) telephone (?:ouvre|s'ouvre|se debarre|debarre)$"""),
        // A question only: « mon téléphone est barré » alone says it is, and goes to the model.
        Regex("""^est-ce que (?:mon|le) telephone est (?:bloque|barre)$"""),
    )
    private val END = listOf(
        Regex("""^(?:end|stop|cancel) (?:the|my) (?:phone )?lock$"""),
        Regex("""^(?:end|stop|cancel) (?:the|my) phone lock$"""),
        Regex("""^unlock $PHONE_EN$"""),
        Regex("""^(?:arrete|annule|termine) (?:le|mon) blocage(?: du telephone)?$"""),
        Regex("""^(?:debarre|debloque) $PHONE_FR$"""),
    )
    private val SKIP_EN = Regex("""^skip (?:(?:tonight's|today's|the next|my next|the|my) (?:phone )?lock|(?:the|my) next one|(.+?)(?: lock)?)$""")
    private val SKIP_FR = Regex("""^saute (?:le (?:prochain )?blocage(?: de ce soir| d'aujourd'hui)?|(?:le blocage )?(.+))$""")

    /**
     * The command [text] is, or null (the rest of the panel answers). [now]: for "until 7", the next 7:00. [names]: your
     * locks' names ("skip Bedtime" only names one of them, so "skip this song" stays the model's). [phoneLock]: Phone lock
     * is on (asking and skipping need it; starting says to set it up). [lockOn]: a lock is on now ("end the lock" and
     * "unlock the phone" mean nothing else to Robot then).
     */
    fun route(text: String, now: ZonedDateTime, names: List<String> = emptyList(), phoneLock: Boolean = true, lockOn: Boolean = true): LockCommand? {
        val t = plain(text).replace(LEAD, "").replace(TAIL, "")
        if (t.isEmpty()) return null
        START_EN.matchEntire(t)?.let { m ->
            return if (m.groupValues[1] == "for") length(m.groupValues[2])?.let { LockCommand.Start(null, it, Lang.EN) }
            else clock(m.groupValues[2], Lang.EN)?.let { (c, amb) -> LockCommand.Start(c, null, Lang.EN, amb) }
        }
        START_FR.matchEntire(t)?.let { m ->
            return if (m.groupValues[1].startsWith("jusqu")) clock(m.groupValues[2], Lang.FR)?.let { (c, _) -> LockCommand.Start(c, null, Lang.FR) }
            else length(m.groupValues[2])?.let { LockCommand.Start(null, it, Lang.FR) }
        }
        if ((phoneLock && ASK.any { it.matches(t) }) || (lockOn && ASK_BARE.any { it.matches(t) })) return LockCommand.Ask(if (french(t)) Lang.FR else Lang.EN)
        if (lockOn && END.any { it.matches(t) }) return LockCommand.End(if (french(t)) Lang.FR else Lang.EN)
        if (!phoneLock) return null
        val skip = SKIP_EN.matchEntire(t)?.let { it to Lang.EN } ?: SKIP_FR.matchEntire(t)?.let { it to Lang.FR } ?: return null
        val said = skip.first.groupValues[1]
        if (said.isEmpty()) return LockCommand.Skip(null, skip.second)
        val name = names.firstOrNull { plain(it) == said || plain(it) == said.removeSuffix(" lock") } ?: return null
        return LockCommand.Skip(name, skip.second)
    }

    private fun french(t: String) = Regex("""\b(combien|telephone|blocage|quelle|heure|reste|debarre|debloque|arrete|annule|termine)\b""").containsMatchIn(t)

    // ------------------------------------------------------------------------------------------------ times and lengths

    private val CLOCK_EN = Regex("""^(\d{1,2})(?::(\d{2}))?(?: ?([ap])\.? ?m\.?)?(?: (?:o'clock|tonight|this evening|in the morning|tomorrow morning))?$""")
    private val CLOCK_FR = Regex("""^(\d{1,2})(?: ?h ?(\d{2})?)?(?: (?:ce soir|demain matin|du matin|du soir))?$""")

    /**
     * "7", "7:30", "7 pm", "19:00"; « 19 h », « 7 h 30 », « midi », « minuit »: the time, and whether it is English "7"
     * alone (the next 7:00, AM or PM). Null: not a time.
     */
    fun clock(s: String, lang: Lang): Pair<LocalTime, Boolean>? {
        val w = s.trim()
        when (w) {
            "noon", "midday", "midi" -> return LocalTime.NOON to false
            "midnight", "minuit" -> return LocalTime.MIDNIGHT to false
        }
        val m = (if (lang == Lang.EN) CLOCK_EN else CLOCK_FR).matchEntire(w) ?: (if (lang == Lang.EN) CLOCK_FR else null)?.matchEntire(w) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (min > 59) return null
        val ap = if (lang == Lang.EN) m.groupValues.getOrNull(3).orEmpty() else ""
        val evening = w.endsWith("tonight") || w.endsWith("this evening") || w.endsWith("ce soir") || w.endsWith("du soir")
        val morning = w.endsWith("in the morning") || w.endsWith("tomorrow morning") || w.endsWith("demain matin") || w.endsWith("du matin")
        var ambiguous = false
        when {
            ap.isNotEmpty() -> {
                if (h !in 1..12) return null
                h = (h % 12) + if (ap == "p") 12 else 0
            }
            evening && h in 1..11 -> h += 12
            morning -> if (h !in 0..11) return null
            else -> {
                if (h > 23) return null
                // Québec French and a 24-hour time ("19:00", "07:30") are exact; English "7" or "7:30" is the next one, AM or PM.
                ambiguous = lang == Lang.EN && h in 1..12 && !w.startsWith("0") && !w.contains('h')
            }
        }
        return LocalTime.of(h, min) to ambiguous
    }

    /** The next [t] after [now]; with [ambiguous] (English "7"), also 12 hours later, whichever comes first. */
    fun next(t: LocalTime, now: ZonedDateTime, ambiguous: Boolean): ZonedDateTime {
        fun after(x: LocalTime): ZonedDateTime {
            val today = now.toLocalDate().atTime(x).atZone(now.zone)
            return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(x).atZone(now.zone)
        }
        val a = after(t)
        if (!ambiguous || t.hour >= 12) return a
        val b = after(t.plusHours(12))
        return if (b.isBefore(a)) b else a
    }

    private val NUMBERS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty-five" to 45, "forty five" to 45,
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10,
        "onze" to 11, "douze" to 12, "quinze" to 15, "vingt" to 20, "trente" to 30, "quarante-cinq" to 45)
    private val LENGTH = Regex("""^(\d{1,3}|[a-z' -]+?) (minutes?|mins?|hours?|hrs?|heures?)(?: (?:and a half|et demie))?$""")

    /** "an hour", "30 minutes", "2 hours", "half an hour", "an hour and a half"; « une heure », « 30 minutes », « une demi-heure ». */
    fun length(s: String): Duration? {
        val w = s.trim()
        if (w == "half an hour" || w == "a half hour" || w == "une demi-heure" || w == "une demi heure") return Duration.ofMinutes(30)
        val m = LENGTH.matchEntire(w) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: NUMBERS[m.groupValues[1]] ?: return null
        if (n <= 0) return null
        val unit = m.groupValues[2]
        val half = w.endsWith("and a half") || w.endsWith("et demie")
        val minutes = if (unit.startsWith("h")) n * 60L + (if (half) 30 else 0) else n.toLong()
        return Duration.ofMinutes(minutes)
    }
}
