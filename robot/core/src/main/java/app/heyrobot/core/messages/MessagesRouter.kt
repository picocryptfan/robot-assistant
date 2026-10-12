package app.heyrobot.core.messages

import java.text.Normalizer

// Pure. What you say about your messages (design §B7), in English and Québec French, decided by code before the model
// is ever asked: "read my messages", "what did Mom say?", "summarize my messages", "translate Pierre's message", "reply
// I'll be late", "réponds à maman : j'arrive", "mark it as read", and while Robot reads, "next" and "stop". The model never
// chooses who a reply goes to: "Mom" here is words, and the panel finds her conversation among the notifications (People
// aliases first, MessagePeople). A question about the screen ("what does this message say?") is never one of these.

/** One thing you asked about your messages; [lang] is the language you asked in (Robot answers in it). */
sealed class MessageCommand {
    abstract val lang: Lang

    /** "Read my messages", "any new messages?", "lis mes messages". */
    data class Read(override val lang: Lang) : MessageCommand()

    /** "What did Mom say?", "qu'est-ce que maman a dit?": [who] as said ("Mom"). */
    data class FromWho(val who: String, override val lang: Lang) : MessageCommand()

    /** "Summarize my messages", "résume mes messages non lus". */
    data class Summarize(override val lang: Lang) : MessageCommand()

    /** "Translate Pierre's message"; [who] null: the conversation read last ("translate it"). */
    data class Translate(val who: String?, override val lang: Lang) : MessageCommand()

    /**
     * "Reply I'll be late", "reply to Mom: I'll be late", "réponds que je vais être en retard". [to]: who, as said (null:
     * the conversation read last, else the newest one). [words]: what to send, as said ("that I'm late" included; core
     * ReplyText makes it a sentence); null: Robot drafts a reply ("answer her for me"). [split]: [to] holds the name and
     * the words together ("reply to Mom I'll be late"): the panel splits them with the names it knows ([MessagesRouter.split]).
     * [inLang]: "reply in French: …", the words go in that language.
     */
    data class Reply(val to: String?, val words: String?, override val lang: Lang, val inLang: Lang? = null, val split: Boolean = false) : MessageCommand()

    /** "Mark it as read", "clear it", "marque comme lu", "efface la notification": the conversation read last. */
    data class Mark(val kind: MarkKind, override val lang: Lang) : MessageCommand()

    /** "Next", "suivant": the next conversation, while Robot reads. */
    data class Next(override val lang: Lang) : MessageCommand()

    /** "Stop", "arrête": no more reading. */
    data class Stop(override val lang: Lang) : MessageCommand()
}

object MessagesRouter {
    /**
     * [text] as a messages command, or null. [reading]: Robot is reading your messages in this panel (then "next",
     * "stop", "translate it" and "summarize them" count too).
     */
    fun route(text: String, reading: Boolean = false): MessageCommand? {
        val o = text.replace(Regex("\\s+"), " ").trim()
        if (o.isEmpty()) return null
        val f = fold(o)
        val (s, e) = core(f)
        if (s >= e) return null
        // A question about the screen or a picture is the screen's ("what does this message say?").
        if (SCREEN.containsMatchIn(f.substring(s, e)) && !reading) return null
        return english(Said(f, o, s, e), reading) ?: french(Said(f, o, s, e), reading)
    }

    /**
     * Whether [cmd] is the panel's to answer, with "Read my messages" [on] and a conversation read in this panel conversation
     * ([readHere]). Off (the default), only an unmistakable ask about messages ("read my messages", "summarize my
     * messages") is answered, by saying it's off: "reply in one word: …", "respond in French", "translate Pierre's message"
     * or "clear it" are ordinary questions for the model. "Clear it", "remove that", « efface-la » are about a conversation
     * only once Robot read one here; "mark it as read" is about messages either way.
     */
    fun applies(cmd: MessageCommand, on: Boolean, readHere: Boolean): Boolean = when (cmd) {
        is MessageCommand.Read, is MessageCommand.Summarize -> true
        is MessageCommand.Mark -> on && (readHere || cmd.kind == MarkKind.READ)
        else -> on
    }

    /**
     * What was said: [f] folded and [o] as said (the same length), the command between [s] and [e] (no wake word, no
     * "please", no final punctuation). A reply's words run to the very end, punctuation kept ([rest]).
     */
    private class Said(val f: String, val o: String, val s: Int, val e: Int) {
        val fc: String get() = f.substring(s, e)
        val oc: String get() = o.substring(s, e)
        /** From [from] (in the command) to the end of what was said, folded and as said. */
        fun rest(from: Int): Pair<String, String> = f.substring(s + from) to o.substring(s + from)
    }

    // ------------------------------------------------------------------ English

    private val READ_EN = listOf(
        Regex("^(?:read|check|play|go through)(?: me)? (?:my |the |all my |all the )?(?:new |unread |latest |last |recent )?(?:messages|texts|text messages|chats)$"),
        Regex("^(?:do i have|have i got|did i get|i got|are there|is there|any|got any|did anyone send me)(?: any)? (?:new |unread )?(?:messages?|texts?)(?: for me)?$"),
        Regex("^(?:new|any new|any) (?:messages|texts)$"),
        Regex("^(?:who|anyone) (?:messaged|texted|wrote to|wrote|has messaged|has texted|has written to) me$"),
        Regex("^what(?: are|'s|s)? (?:my )?(?:new |unread )?messages$"),
    )
    private val FROM_EN = listOf(
        Regex("^what (?:did|has|does) (.+?) (?:just )?(?:say|said|write|wrote|text|texted|send|sent|want|wants)(?: me| to me)?$"),
        Regex("^(?:read|play)(?: me)? (.+?)(?:'s|s') (?:last |latest |new )?(?:message|messages|text|texts)$"),
        Regex("^(?:read|play)(?: me)? (?:the |my )?(?:last |latest |new )?(?:message|messages|text|texts) from (.+)$"),
        Regex("^(?:any|are there any|do i have any|did i get any) (?:new )?(?:messages?|texts?) from (.+)$"),
        Regex("^(?:did|has) (.+?) (?:write|written|wrote|text|texted|message|messaged|reply|replied|answer|answered)(?: me| back| to me)?$"),
    )
    private val SUMMARY_EN = Regex("^(?:summari[sz]e|sum up|recap|give me a summary of|make a summary of)(?: all)? (?:my |the )?(?:new |unread |latest )?(?:messages|texts)$")
    private val SUMMARY_IT_EN = Regex("^(?:summari[sz]e|sum up|recap)(?: them| it| all| all of them| everything)?$")
    private val TRANSLATE_WHO_EN = listOf(
        Regex("^translate (.+?)(?:'s|s') (?:last |latest |new )?(?:message|messages|text|texts)$"),
        Regex("^translate (?:the |my )?(?:last |latest )?(?:message|messages|text|texts) from (.+)$"),
    )
    private val TRANSLATE_IT_EN = Regex("^translate(?: it| that| this| them| the message| that message| this message| the last message| her message| his message| their message)?$")
    private val REPLY_EN = Regex("^(?:reply|respond|write back|text back|answer back)(?: back)?(?=$| |:|,)")
    private val ANSWER_EN = Regex("^answer(?= )")
    private val MARK_EN = listOf(
        Regex("^(?:mark|set)(?: it| that| this| them| those| the messages?| the conversation| the chat)? (?:as )?read$"),
        Regex("^(?:clear|dismiss|remove)(?: it| that| this| them| the notification| this notification| that notification| the notifications)$"),
    )
    private val NEXT_EN = Regex("^(?:next|next one|next conversation|next message|next please|skip|skip it|skip that|go on|continue|keep going)$")
    private val STOP_EN = Regex("^(?:stop|stop reading|stop it|that's enough|thats enough|enough|that's all|thats all|ok stop|okay stop|no more|cancel)$")

    private fun english(said: Said, reading: Boolean): MessageCommand? {
        val en = Lang.EN
        val f = said.fc
        val o = said.oc
        if (READ_EN.any { it.matches(f) }) return MessageCommand.Read(en)
        if (SUMMARY_EN.matches(f) || (reading && SUMMARY_IT_EN.matches(f))) return MessageCommand.Summarize(en)
        for (r in TRANSLATE_WHO_EN) r.matchEntire(f)?.let { m -> return who(m, o)?.let { MessageCommand.Translate(it, en) } }
        if (reading && TRANSLATE_IT_EN.matches(f)) return MessageCommand.Translate(null, en)
        if (MARK_EN[0].matches(f)) return MessageCommand.Mark(MarkKind.READ, en)
        if (MARK_EN[1].matches(f)) return MessageCommand.Mark(MarkKind.CLEAR, en)
        if (reading && NEXT_EN.matches(f)) return MessageCommand.Next(en)
        if (reading && STOP_EN.matches(f)) return MessageCommand.Stop(en)
        REPLY_EN.find(f)?.let { m -> return said.rest(m.range.last + 1).let { (rf, ro) -> replyEn(rf, ro) } }
        // "Answer" alone is too common ("answer this question"): only "answer her (for me)" and "answer to Mom: …".
        ANSWER_EN.find(f)?.let { m ->
            val r = said.rest(m.range.last + 1).let { (rf, ro) -> replyEn(rf, ro) }
            if (r.words == null || (r.to != null && !r.split)) return r
        }
        for (r in FROM_EN) r.matchEntire(f)?.let { m -> return who(m, o)?.let { MessageCommand.FromWho(it, en) } }
        return null
    }

    private val PRONOUN_EN = Regex("^(?:to )?(?:him|her|them)(?= |:|,|$)")
    private val DRAFT_EN = Regex("^(?:(?:to )?(?:him|her|them) )?for me$|^(?:to )?(?:him|her|them)$|^for me$")
    /** A name of at most three words, then ":" or ",", then the words (", " inside the words is theirs). */
    private val NAMED_SEP_EN = Regex("^([^ :,]+(?: [^ :,]+){0,2}?)(?: in (french|english))?\\s*[:,]\\s*(.+)$")
    private val NAMED_THAT_EN = Regex("^([^ :,]+(?: [^ :,]+){0,2}?) (?:that|saying|to say) (.+)$")
    private val IN_LANG_EN = Regex("^in (french|english)\\s*[:,]?\\s*")

    /** After "reply": [f0] folded, [o0] the same span as said, to the end. */
    private fun replyEn(f0: String, o0: String): MessageCommand.Reply {
        val en = Lang.EN
        var (f, o) = trimBoth(f0, o0)
        if (f.isEmpty() || DRAFT_EN.matches(bare(f))) return MessageCommand.Reply(null, null, en)
        PRONOUN_EN.find(f)?.let { m -> f = f.substring(m.range.last + 1); o = o.substring(m.range.last + 1); trimBoth(f, o).let { f = it.first; o = it.second } }
        if (f.startsWith("to ")) {
            f = f.substring(3)
            o = o.substring(3)
            NAMED_SEP_EN.matchEntire(f)?.let { m ->
                return MessageCommand.Reply(cleanWho(o.substring(m.groups[1]!!.range)), o.substring(m.groups[3]!!.range).trim().ifEmpty { null }, en,
                    m.groups[2]?.value?.let(::langOf))
            }
            NAMED_THAT_EN.matchEntire(f)?.let { m -> return MessageCommand.Reply(cleanWho(o.substring(m.groups[1]!!.range)), "that " + o.substring(m.groups[2]!!.range), en) }
            return MessageCommand.Reply(o.trim(), null, en, split = true)
        }
        var inLang: Lang? = null
        IN_LANG_EN.find(f)?.let { m ->
            inLang = langOf(m.groupValues[1])
            f = f.substring(m.range.last + 1)
            o = o.substring(m.range.last + 1)
        }
        val words = o.trim().trimStart(':', ',').trim()
        return MessageCommand.Reply(null, words.ifEmpty { null }, en, inLang)
    }

    /** Without the final punctuation. */
    private fun bare(f: String): String = f.trimEnd('.', '!', '?', ' ')

    // ------------------------------------------------------------------ French (Québec)

    private val READ_FR = listOf(
        Regex("^(?:lis|lire|lis-moi|lis moi|verifie|verifier|regarde|checke)(?:-moi| moi)? (?:mes|les|tous mes) (?:nouveaux |derniers )?(?:messages|textos|texto|sms)(?: non lus)?$"),
        Regex("^(?:est-ce que j'ai|est ce que j'ai|esque j'ai|j'ai|j'ai-tu|j'ai tu|ai-je|as-tu|tu as|y a-t-il|y a t il|y a-tu|y a tu|y'a-tu|y'a tu|il y a|y'a)(?: des| de| du)?(?: nouveaux)? (?:messages?|textos?|sms)(?: pour moi)?$"),
        Regex("^(?:des )?nouveaux (?:messages|textos)$"),
        Regex("^qui m'a (?:ecrit|texte|envoye un message|envoye des messages)$"),
    )
    private val FROM_FR = listOf(
        Regex("^(?:qu'est-ce que|qu'est ce que|quest-ce que|qu'est-ce qu'|qu'est ce qu')\\s?(.+?) (?:a dit|a ecrit|m'a ecrit|m'a dit|m'a envoye|a envoye|m'a texte|a repondu|m'a repondu|veut|voulait)$"),
        Regex("^(?:que|qu'est-ce que) (?:dit|disait|veut) (.+)$"),
        Regex("^(?:lis|lis-moi|lis moi|lire)(?: le| les| son| ses)(?: dernier| derniers)? (?:messages?|textos?) (?:de |d')(.+)$"),
        Regex("^(?:est-ce que |est ce que |est-ce qu'|est ce qu')(.+?) m'a (?:ecrit|texte|repondu|envoye un message)$"),
        Regex("^(?:des |j'ai des |j'ai-tu des |est-ce que j'ai des )?(?:nouveaux )?messages (?:de |d')(.+)$"),
    )
    private val SUMMARY_FR = Regex("^(?:resume|resumer|resume-moi|resume moi|fais-moi un resume de|fais moi un resume de|un resume de)(?: tous)? (?:mes|les) (?:nouveaux )?(?:messages|textos)(?: non lus)?$")
    private val SUMMARY_IT_FR = Regex("^(?:resume|resume-les|resume les|resume-le|resume tout|fais un resume)$")
    private val TRANSLATE_WHO_FR = Regex("^traduis(?:-moi| moi)? (?:le |les |son |ses )?(?:dernier |derniers )?(?:messages?|textos?) (?:de |d')(.+)$")
    private val TRANSLATE_IT_FR = Regex("^traduis(?:-le|-les|-moi ca| moi ca| ca| ce message| le message| son message| le dernier message| les messages)?$")
    private val REPLY_FR = Regex("^(?:reponds|repond|repondre|ecris-lui|ecris lui|reecris-lui|reecris lui)(?:-lui|-leur| lui| leur)?(?=$| |:|,)")
    private val MARK_FR = listOf(
        Regex("^(?:marque|marquer|marques)(?:-le|-la|-les| le| la| les| ca| la conversation| les messages)? (?:comme )?lus?$"),
        Regex("^(?:efface|supprime|enleve|ferme)(?:-la|-le|-les| la| le| la notification| cette notification| les notifications| ca)$"),
    )
    private val NEXT_FR = Regex("^(?:suivant|suivante|le suivant|la suivante|prochain|prochaine|le prochain|la prochaine|passe|passe au suivant|continue|vas-y|vas y|envoye)$")
    private val STOP_FR = Regex("^(?:arrete|arrete de lire|arrete ca|ca suffit|c'est assez|c'est tout|stop|annule|pas plus)$")

    private fun french(said: Said, reading: Boolean): MessageCommand? {
        val fr = Lang.FR
        val f = said.fc
        val o = said.oc
        if (READ_FR.any { it.matches(f) }) return MessageCommand.Read(fr)
        if (SUMMARY_FR.matches(f) || (reading && SUMMARY_IT_FR.matches(f))) return MessageCommand.Summarize(fr)
        TRANSLATE_WHO_FR.matchEntire(f)?.let { m -> return who(m, o)?.let { MessageCommand.Translate(it, fr) } }
        if (reading && TRANSLATE_IT_FR.matches(f)) return MessageCommand.Translate(null, fr)
        if (MARK_FR[0].matches(f)) return MessageCommand.Mark(MarkKind.READ, fr)
        if (MARK_FR[1].matches(f)) return MessageCommand.Mark(MarkKind.CLEAR, fr)
        if (reading && NEXT_FR.matches(f)) return MessageCommand.Next(fr)
        if (reading && STOP_FR.matches(f)) return MessageCommand.Stop(fr)
        REPLY_FR.find(f)?.let { m -> return said.rest(m.range.last + 1).let { (rf, ro) -> replyFr(rf, ro) } }
        for (r in FROM_FR) r.matchEntire(f)?.let { m -> return who(m, o)?.let { MessageCommand.FromWho(it, fr) } }
        return null
    }

    private val NAMED_SEP_FR = Regex("^([^ :,]+(?: [^ :,]+){0,2}?)(?: en (anglais|francais))?\\s*[:,]\\s*(.+)$")
    private val NAMED_QUE_FR = Regex("^([^ :,]+(?: [^ :,]+){0,2}?) (que |qu'(?=\\p{L}))(.+)$")
    private val IN_LANG_FR = Regex("^en (anglais|francais)\\s*[:,]?\\s*")

    private fun replyFr(f0: String, o0: String): MessageCommand.Reply {
        val fr = Lang.FR
        var (f, o) = trimBoth(f0, o0)
        if (f.isEmpty() || Regex("^(?:pour moi|a ma place)$").matches(bare(f))) return MessageCommand.Reply(null, null, fr)
        Regex("^(?:a|au|aux) ").find(f)?.let { m ->
            f = f.substring(m.range.last + 1)
            o = o.substring(m.range.last + 1)
            NAMED_SEP_FR.matchEntire(f)?.let { n ->
                return MessageCommand.Reply(cleanWho(o.substring(n.groups[1]!!.range)), o.substring(n.groups[3]!!.range).trim().ifEmpty { null }, fr,
                    n.groups[2]?.value?.let(::langOf))
            }
            NAMED_QUE_FR.matchEntire(f)?.let { n -> return MessageCommand.Reply(cleanWho(o.substring(n.groups[1]!!.range)), o.substring(n.groups[2]!!.range.first), fr) }
            return MessageCommand.Reply(o.trim(), null, fr, split = true)
        }
        var inLang: Lang? = null
        IN_LANG_FR.find(f)?.let { m ->
            inLang = langOf(m.groupValues[1])
            f = f.substring(m.range.last + 1)
            o = o.substring(m.range.last + 1)
        }
        val words = o.trim().trimStart(':', ',').trim()
        return MessageCommand.Reply(null, words.ifEmpty { null }, fr, inLang)
    }

    // ------------------------------------------------------------------ names

    /**
     * "Mom I'll be late" with the [names] Robot knows (conversation titles, senders, your People aliases): the longest
     * name it starts with, and the rest as the words. No name fits: the first word is taken for the name.
     */
    fun split(raw: String, names: Collection<String>): Pair<String, String> {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "" to ""
        val folded = words.map { nameKey(it) }
        var best = 0
        for (n in names) {
            val k = nameKey(n).split(' ').filter { it.isNotEmpty() }
            if (k.isEmpty() || k.size >= words.size || k.size <= best) continue
            if (k.indices.all { folded[it] == k[it] }) best = k.size
        }
        val take = if (best == 0) 1 else best
        return cleanWho(words.take(take).joinToString(" ")) to words.drop(take).joinToString(" ").trimStart(':', ',', ' ')
    }

    /** A name as compared: accents, case, a possessive and punctuation aside. */
    fun nameKey(s: String): String = fold(s).replace(Regex("(?:'s|s')$"), "").replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun who(m: MatchResult, o: String): String? = m.groups[1]?.range?.let { cleanWho(o.substring(it)) }
        ?.takeIf { it.isNotEmpty() && fold(it) !in PRONOUNS }

    /** Not a name: "what did you say?" is a question for Robot. */
    private val PRONOUNS = setOf("you", "i", "he", "she", "they", "it", "we", "robot", "claude", "that", "this", "tu", "il", "elle", "ils", "elles",
        "on", "vous", "nous", "je", "ca", "cela", "ce", "lui")

    /** "my mom" → "mom", "maman" stays, a trailing "'s" goes. */
    private fun cleanWho(s: String): String = s.trim().trimEnd('?', '.', '!', ',', ':').trim()
        .replace(Regex("^(?i)(?:my|ma|mon|the|la|le)\\s+"), "").replace(Regex("(?:'s|’s)$"), "").trim()

    private fun langOf(w: String): Lang = if (w.startsWith("fr")) Lang.FR else Lang.EN

    // ------------------------------------------------------------------ text

    private val SCREEN = Regex("\\b(?:this|ce|cette) (?:message|text|texto|screen|ecran|page|email|courriel)\\b|\\bon (?:the|my) screen\\b|\\ba l'ecran\\b")

    private val LEAD = Regex("^(?:(?:hey|hi|ok|okay|dis|salut|allo)\\s+)?robot\\b[,!.]?\\s*|^(?:please|can you|could you|would you|will you|peux-tu|peux tu|pourrais-tu|pourrais tu|tu peux|est-ce que tu peux|est-ce que tu pourrais)\\s+")
    private val TAIL = Regex("[\\s,]*(?:please|s'il te plait|s'il vous plait|svp)?[\\s.!?]*$")

    /** The span of [f] without a wake word or politeness at either end. */
    private fun core(f: String): Pair<Int, Int> {
        var s = 0
        while (true) {
            val m = LEAD.find(f.substring(s)) ?: break
            if (m.value.isEmpty()) break
            s += m.value.length
        }
        var e = f.length
        TAIL.find(f.substring(s))?.let { m -> if (m.range.first >= 0) e = s + m.range.first }
        // Leftover spaces either side.
        while (s < e && f[s] == ' ') s++
        while (e > s && f[e - 1] == ' ') e--
        return s to e
    }

    /** [f] and [o] without spaces, ":" or "," at the start and spaces at the end (the same characters off both). */
    private fun trimBoth(f: String, o: String): Pair<String, String> {
        val lead = f.length - f.trimStart(' ', ':', ',').length
        val ft = f.trimStart(' ', ':', ',').trimEnd()
        return ft to o.substring(lead, lead + ft.length)
    }

    /** Lower case, accents off, typographic apostrophes as "'": one character for one, so a match's place is the same in what was said. */
    fun fold(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                '’', 'ʼ', '‘' -> '\''
                '«', '»', '“', '”' -> '"'
                ' ', ' ' -> ' '
                else -> ch
            }
            b.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
        }
        return b.toString()
    }
}
