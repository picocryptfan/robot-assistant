package app.heyrobot.core.memory

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Pure. What your words mean for memory, decided by code before anything reaches the model (design §A3.1, the router
// part of "MemoryRouter"). Anchored at the start of what you said, with only filler in front ("hey robot", "please",
// "okay", "can you", "s'il te plaît", "peux-tu"), so "my sister said remember that…" or a sentence about forgetting is
// never a command. English and Québec French: "remember that …", "souviens-toi que …", "retiens que …", "n'oublie pas
// que …" and the spoken "oublie pas que …" save; "forget that" and "oublie ça" remove the item of this conversation;
// "forget my sister's birthday", "oublie la fête de ma sœur" search; "what do you remember about Julie?", "qu'est-ce
// tu sais sur moi?" list. "Remember to buy milk", "n'oublie pas d'acheter du lait" stay notes: [parse] gives null and
// today's route (the model's add_note) runs as before. "Forget it" and "oublie ça, c'est pas grave" mean never mind.
//
// Matching runs on a folded copy (lower case, no accents) aligned character for character with what you said, so the
// item keeps your exact words and accents.

/** When an item ends ("until tonight", "jusqu'à demain"). */
enum class Until {
    /** The end of today: "until tonight", "for today", "jusqu'à ce soir". */
    TONIGHT,
    /** The end of tomorrow: "until tomorrow", "jusqu'à demain". */
    TOMORROW,
    /** The end of Sunday: "until the end of the week", "pour cette semaine", "jusqu'à la fin de semaine". */
    END_OF_WEEK;

    /** The last millisecond of that day in [zone]. */
    fun expiresAt(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (this) {
            TONIGHT -> today
            TOMORROW -> today.plusDays(1)
            END_OF_WEEK -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        }
        return day.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        /**
         * The end the remember tool's "until" names ("tonight", "for today", "tomorrow", "this week", "ce soir", "demain",
         * "cette semaine"), or null for none or anything else (an item without an end).
         */
        fun of(text: String?): Until? {
            val t = MemoryCommands.foldAligned(text?.trim().orEmpty()).replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
            return when {
                t.isEmpty() -> null
                Regex("""\b(tonight|today|this evening|end of (the )?day|ce soir|aujourd'hui|aujourdhui|soir)\b""").containsMatchIn(t) -> TONIGHT
                Regex("""\b(tomorrow|demain)\b""").containsMatchIn(t) -> TOMORROW
                Regex("""\b(week|weekend|sunday|semaine|week-end|dimanche)\b""").containsMatchIn(t) -> END_OF_WEEK
                else -> null
            }
        }
    }
}

/** A memory command; [language] ("en", "fr") picks the words Robot answers with. */
sealed class MemoryCommand {
    abstract val language: String

    /**
     * Save [text] (your words, "that"/"que" removed, first letter capital). [secret]: [SecretDetector] found one.
     * [until]: it ends then. Robot answers "Got it: …" with Undo; from content (a message, the screen) it is a card.
     */
    data class Remember(val text: String, val kind: MemoryKind, val secret: Boolean, val until: Until?, override val language: String) : MemoryCommand() {
        override fun toString(): String = "Remember(kind=$kind, secret=$secret, until=$until, language=$language, chars=${text.length})"
    }

    /** "Forget that": the item saved or used last in this conversation, with Undo (nothing then: ordinary talk). */
    data class ForgetLast(override val language: String) : MemoryCommand()

    /** "Forget my sister's birthday": search; one clear match is deleted with Undo, several make a pick card. */
    data class Forget(val query: String, override val language: String) : MemoryCommand() {
        override fun toString(): String = "Forget(language=$language, chars=${query.length})"
    }

    /** "Forget everything": never by voice. Robot says to open Settings › Memory. */
    data class ForgetEverything(override val language: String) : MemoryCommand()

    /** "What do you remember about Julie?": the items about [about] (up to 10), read aloud. */
    data class Recall(val about: String, override val language: String) : MemoryCommand() {
        override fun toString(): String = "Recall(language=$language, chars=${about.length})"
    }

    /** "What do you know about me?": a count, the most used read aloud, and an Open Memory button. */
    data class AboutMe(override val language: String) : MemoryCommand()
}

object MemoryCommands {
    private class P(val re: Regex, val fr: Boolean)

    private fun en(p: String) = P(Regex(p), false)
    private fun fr(p: String) = P(Regex(p), true)

    private val LEAD = Regex("""^(?:(?:hey|hi|ok|okay|robot|please|so|alors|bon|euh|um+|uh+|eh|ecoute|s'il te plait|s'il vous plait|stp|can you|could you|would you|will you|est-ce que tu peux|est-ce que tu pourrais|peux-tu|pourrais-tu|tu peux|tu pourrais)(?=[\s,.!:;-]|$)[\s,.!:;-]*)+""")
    private val TRAIL = Regex("""(?:[\s,]+(?:please|s'il te plait|s'il vous plait|stp|merci|thanks|thank you|ok|okay))*[\s.!?…]*$""")

    /** "Remember to …", "n'oublie pas de …", "remind me …", "rappelle-moi de …": a note or a reminder, not memory. */
    private val NOTE = Regex("""^(?:(?:remember|don't forget|do not forget|dont forget) to\b|remind me\b|(?:make|take) a note\b|note (?:down|that)\b|(?:jot|write) (?:it |this |that )?down\b|(?:n'oublie pas|oublie pas|souviens[- ]toi|rappelle[- ]toi|retiens)(?: bien)? (?:de |d')|pense (?:a |aux? )|rappelle[- ]moi\b|prends (?:en )?note\b)""")

    private val REMEMBER = listOf(
        en("""^(?:remember|memori[sz]e)(?: this| the following)?\s*[:,]\s*(?<rest>.+)$"""),
        en("""^(?:remember|memori[sz]e) that (?<rest>.+)$"""),
        en("""^(?:keep|bear) (?:this )?in mind(?: that)?\s*[:,]?\s*(?<rest>.+)$"""),
        en("""^(?:don't|do not|dont) forget that (?<rest>.+)$"""),
        fr("""^(?:souviens[- ]toi|rappelle[- ]toi|retiens|memorise|garde en memoire|n'oublie pas|oublie pas)(?: bien)? (?:que |qu')(?<rest>.+)$"""),
        fr("""^(?:souviens[- ]toi|rappelle[- ]toi|retiens|memorise|garde en memoire)(?: (?:ceci|ca|cela|bien))?\s*[:,]\s*(?<rest>.+)$"""),
        // "Remember I parked on P3 until tonight": a statement right after the verb (who it is about, or a verb in it).
        en("""^(?:remember|memori[sz]e) (?<rest>(?:i|i'm|i've|i'd|i'll|my|our|we|we're|we've|it's|there's|his|her|their)\b.+)$"""),
        en("""^(?:remember|memori[sz]e) (?<rest>(?!(?:to|when|where|what|who|how|why|if|whether|which|me|this|that|the following)\b)[^?]+\b(?:is|are|was|were|has|have|had|lives|works|likes|loves|hates|prefers|goes|takes|plays)\b[^?]*)$"""),
        fr("""^(?:souviens[- ]toi|rappelle[- ]toi|retiens) (?<rest>(?:je|j'|mon|ma|mes|notre|nos|on|nous)\b.+)$"""),
    )

    private val FORGET_ALL = listOf(
        en("""^(?:forget|delete|erase|wipe|clear) (?:everything|it all|all of it|all (?:of )?(?:your |my )?memor(?:y|ies)|everything you (?:know|remember)(?: about me)?|your (?:whole |entire )?memory)$"""),
        fr("""^(?:oublie|efface|supprime|vide) (?:tout|toute ta memoire|tout ce que tu sais(?: sur moi| de moi)?|tous tes souvenirs|ta memoire)$"""),
    )

    private val FORGET_LAST = listOf(
        en("""^forget (?:that|this|what i (?:just )?said|the last (?:one|thing)(?: i said)?)$"""),
        fr("""^(?:oublie[- ](?:ca|cela|ceci|le|la)|oublie ce que (?:je viens de (?:te )?dire|j'ai dit)|oublie la derniere chose)(?: la)?$"""),
    )

    private val FORGET = listOf(
        en("""^forget (?>(?:about )?)(?>(?:that )?)(?<q>(?!(?:it|i|me|you|this|that|everything|all)\b).+)$"""),
        fr("""^oublie (?>(?:que |qu'|ce que je t'ai dit (?:sur|a propos de|au sujet de) |tout ce qui concerne )?)(?<q>(?!(?:ca|cela|ceci|pas|tout|ce que)\b)(?!(?:le|la)$).+)$"""),
    )

    private val ABOUT_ME = listOf(
        en("""^what (?:else )?(?:do|did) you (?:know|remember) about me(?:self)?$"""),
        en("""^what (?:do|did) you (?:know|remember)$"""),
        en("""^what (?:have you|did i ask you to|did i tell you to) (?:remembered|kept|remember|keep)$"""),
        en("""^(?:list|show me|tell me|read me) (?:everything |all )?(?:you remember|what you remember|you know about me|what you know about me|your memor(?:y|ies))$"""),
        fr("""^(?:qu'est-ce que|qu'est-ce|qu'est ce que|que) (?:tu sais|tu connais|tu te souviens|tu te rappelles)(?:-tu)? (?:sur|de|a propos de|au sujet de) moi$"""),
        fr("""^que (?:sais-tu|connais-tu) (?:sur|de) moi$"""),
        fr("""^de quoi (?:tu te souviens|tu te rappelles|te souviens-tu|te rappelles-tu)(?:-tu)?$"""),
    )

    private val RECALL = listOf(
        en("""^what (?:else )?(?:do|did) you (?:know|remember) (?:about|of|on) (?<q>.+)$"""),
        en("""^what did i (?:tell you|say) about (?<q>.+)$"""),
        en("""^(?:do you remember|tell me what you (?:know|remember)|what can you tell me) (?:anything )?about (?<q>.+)$"""),
        fr("""^(?:qu'est-ce que|qu'est-ce|qu'est ce que) tu (?:sais|connais|te souviens|te rappelles)(?:-tu)? (?:sur |de |d'|a propos de |a propos d'|au sujet de |au sujet d')(?<q>.+)$"""),
        fr("""^que (?:sais-tu|connais-tu) (?:sur |de |d')(?<q>.+)$"""),
        fr("""^de quoi (?:tu te souviens|tu te rappelles|te souviens-tu|te rappelles-tu)(?:-tu)? (?:sur |a propos de |a propos d'|au sujet de |au sujet d'|concernant )(?<q>.+)$"""),
        fr("""^(?:est-ce que )?tu te (?:souviens|rappelles)(?:-tu)? (?:de |d')(?<q>.+)$"""),
        fr("""^(?:te souviens-tu|te rappelles-tu) (?:de |d')(?<q>.+)$"""),
    )

    private val ME = setOf("me", "myself", "moi", "moi-meme")

    /** A question that clearly asks for memory, for the locked phone's "Unlock first" (anything else skips memory silently). */
    private val ASKS = Regex("""\b(?:do you remember|did i tell you|what did i tell you|what do you (?:know|remember) about|tu te (?:souviens|rappelles)|te (?:souviens|rappelles)-tu|est-ce que je t'ai dit|qu'est-ce que je t'ai dit|tu sais (?:sur|de) moi)\b""")

    private val PREFERENCE = Regex("""^(?:i (?:really |much |always |usually )?(?:prefer|like|love|hate|dislike|don't like|do not like|would rather|want (?!to\b))|i'd (?:rather|prefer|like)|(?:please )?call me\b|my name is\b|i go by\b|always |never |use (?:metric|imperial|celsius|fahrenheit|kilomet|miles|the metric|the imperial|24|12)|answer |reply |respond |speak |talk to me\b|keep (?:your |the )?answers|(?:short|shorter|long|longer|brief|detailed) answers|je (?:prefere|n'aime pas|aime pas|deteste|veux des|voudrais des|veux que tu|voudrais que tu)|j'aime|j'adore|j'prefere|appelle[- ]moi\b|mon nom est\b|je m'appelle\b|tutoie[- ]moi|vouvoie[- ]moi|toujours |jamais |reponds|parle[- ]moi|utilise |des reponses)""")

    private val UNTIL = listOf(
        Regex("""[\s,]+(?:until|till|til) (?:tonight|this evening|the end of (?:the|to)day|end of day)$""") to Until.TONIGHT,
        Regex("""[\s,]+(?:just )?for (?:tonight|today|this evening)$""") to Until.TONIGHT,
        Regex("""[\s,]+(?:until|till|til) tomorrow$""") to Until.TOMORROW,
        Regex("""[\s,]+(?:until|till|til) (?:the end of (?:the|this) week|this weekend|the weekend|sunday)$""") to Until.END_OF_WEEK,
        Regex("""[\s,]+for this week$""") to Until.END_OF_WEEK,
        Regex("""[\s,]+(?:jusqu'a ce soir|pour ce soir|pour la soiree|(?:juste )?pour aujourd'hui|jusqu'a la fin de la journee)$""") to Until.TONIGHT,
        Regex("""[\s,]+jusqu'a demain$""") to Until.TOMORROW,
        Regex("""[\s,]+(?:jusqu'a la fin de (?:la )?semaine|pour cette semaine|jusqu'au week-end|jusqu'a dimanche)$""") to Until.END_OF_WEEK,
    )

    /** What you said, and its folded copy: same length, so a match in one is the same span in the other. */
    private class Said(val original: String, val key: String) {
        fun cut(from: Int, to: Int = original.length) = Said(original.substring(from, to), key.substring(from, to))
    }

    /** One character at a time (so positions stay aligned): its base letter, lower case; typographic apostrophes plain. */
    internal fun foldAligned(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val base = if (c.code < 128) c else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            sb.append(when (base) { '’', '‘', 'ʼ' -> '\''; else -> base.lowercaseChar() })
        }
        return sb.toString()
    }

    private fun said(text: String): Said {
        val t = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()
        var s = Said(t, foldAligned(t))
        LEAD.find(s.key)?.let { s = s.cut(it.range.last + 1) }
        TRAIL.find(s.key)?.let { if (it.range.first < s.key.length) s = s.cut(0, it.range.first) }
        return s
    }

    /** "Remember to buy milk", "N'oublie pas d'acheter du lait": a note (or a reminder), never memory. */
    fun staysNote(text: String): Boolean = NOTE.containsMatchIn(said(text).key)

    /** The memory command in [text], or null for anything else (a note, a question, ordinary talk). */
    fun parse(text: String): MemoryCommand? {
        val s = said(text)
        if (s.key.isEmpty() || NOTE.containsMatchIn(s.key)) return null
        for (p in REMEMBER) {
            val m = p.re.find(s.key) ?: continue
            return remember(s.cut(m.groups["rest"]!!.range.first), p.fr) ?: continue
        }
        val lang = { p: P -> if (p.fr) "fr" else "en" }
        FORGET_ALL.firstOrNull { it.re.matches(s.key) }?.let { return MemoryCommand.ForgetEverything(lang(it)) }
        FORGET_LAST.firstOrNull { it.re.matches(s.key) }?.let { return MemoryCommand.ForgetLast(lang(it)) }
        for (p in FORGET) {
            val m = p.re.find(s.key) ?: continue
            val q = tidy(s.cut(m.groups["q"]!!.range.first).original)
            if (q.isNotEmpty()) return MemoryCommand.Forget(q, lang(p))
        }
        ABOUT_ME.firstOrNull { it.re.matches(s.key) }?.let { return MemoryCommand.AboutMe(lang(it)) }
        for (p in RECALL) {
            val m = p.re.find(s.key) ?: continue
            val q = tidy(s.cut(m.groups["q"]!!.range.first).original)
            if (q.isEmpty()) continue
            return if (foldAligned(q) in ME) MemoryCommand.AboutMe(lang(p)) else MemoryCommand.Recall(q, lang(p))
        }
        return null
    }

    /** A question that clearly asks what Robot remembers ("do you remember…", "tu te souviens…"): on a locked phone it hears "Unlock first". */
    fun asksMemory(text: String): Boolean {
        val c = parse(text)
        return c is MemoryCommand.Recall || c is MemoryCommand.AboutMe || ASKS.containsMatchIn(said(text).key)
    }

    /** A preference by its words ("I prefer …", "call me …", "appelle-moi …", "always answer …"), checked on the saved text. */
    fun prefers(text: String): Boolean = PREFERENCE.containsMatchIn(foldAligned(text.trim()))

    private fun remember(rest: Said, fr: Boolean): MemoryCommand.Remember? {
        var r = rest
        var until: Until? = null
        val trimmed = r.key.trimEnd(' ', '.', '!', ',', ';')
        r = r.cut(0, trimmed.length)
        for ((re, u) in UNTIL) {
            val m = re.find(r.key) ?: continue
            until = u
            r = r.cut(0, m.range.first)
            break
        }
        val text = tidy(r.original)
        if (text.isEmpty()) return null
        val kind = if (prefers(text)) MemoryKind.PREFERENCE else MemoryKind.FACT
        return MemoryCommand.Remember(text, kind, SecretDetector.isSecret(text), until, if (fr) "fr" else "en")
    }

    /** Spaces tidied, closing punctuation and quotes dropped, the first letter a capital. */
    private fun tidy(s: String): String {
        val t = s.trim().trim('"', '“', '”', '«', '»', ' ').trimEnd('.', '!', '?', ',', ';', ':', ' ').trim()
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private val FLIP = mapOf("i" to "you", "me" to "you", "my" to "your", "mine" to "yours", "myself" to "yourself",
        "i'm" to "you're", "i've" to "you've", "i'd" to "you'd", "i'll" to "you'll", "am" to "are", "our" to "your", "ours" to "yours")
    /** A first word written with a capital only because it starts the sentence. */
    private val LOWER_FIRST = setOf("the", "a", "an", "it", "it's", "there", "there's", "this", "that", "these", "those", "she", "he", "they", "we", "his", "her", "their", "its")

    /**
     * What Robot says after saving: "Got it: your sister's birthday is May 3." (your words turned to "you"), or in French
     * "C'est noté : « … »." A secret is never read back: "Got it: a code, kept in Memory."
     */
    fun saidBack(c: MemoryCommand.Remember): String {
        if (c.secret) {
            val k = SecretDetector.placeholder(SecretDetector.find(c.text), c.language == "fr").trim('[', ']')
            return if (c.language == "fr") "C'est noté : $k." else "Got it: $k."
        }
        if (c.language == "fr") return "C'est noté : « ${c.text} »."
        val body = toYou(c.text)
        val end = when (c.until) {
            Until.TONIGHT -> " until tonight"
            Until.TOMORROW -> " until tomorrow"
            Until.END_OF_WEEK -> " until the end of the week"
            null -> ""
        }
        return "Got it: $body$end."
    }

    /**
     * Your English words turned to Robot's ("My sister's birthday is May 3." → "your sister's birthday is May 3"): "I" and
     * "my" become "you" and "your", a first word written with a capital only because it starts the sentence goes lower
     * case, closing punctuation goes.
     */
    fun toYou(text: String): String {
        val words = text.trim().trimEnd('.', '!', '?', ';', ':', ' ').split(' ').mapIndexed { i, w ->
            val core = w.trimEnd('.', ',', '!', '?', ';', ':')
            val f = FLIP[foldAligned(core)]
            when {
                f != null -> f + w.substring(core.length)
                i == 0 && foldAligned(core) in LOWER_FIRST -> w.lowercase(Locale.ROOT)
                else -> w
            }
        }
        return words.joinToString(" ")
    }
}
