// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Prepared
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.web.WebGate
import org.json.JSONArray
import org.json.JSONObject

// Pure. Signal later (docs/plans/signal-later-plan.md, idea 15): a web search asked with no connection, or "… when we're
// back in range", is kept on the phone and runs once when a validated connection returns. Here: the phrases (LaterRouter,
// in the everyday slot after What went online's), and the queue's rules (Later): at most [Later.CAP] wait, each for at most
// [Later.WAIT_MS], a question taken off the queue before it runs (once only), the ask-first rule it had when asked kept
// (Later.mustAsk: the stricter of then and now), the cancel match, and its JSON. The sealed file is the app's (LaterVault).

/** Where a kept question is. */
enum class LaterState(val code: String) {
    /** Waiting for a connection. */
    WAITING("waiting"), // l10n: not text (a stored code)
    /** Back online, but its ask-first rule asks: it goes only from the panel, with its card. */
    READY("ready"), // l10n: not text (a stored code)
    /** Ran: [LaterItem.answer] waits for the next unlocked chat. */
    ANSWERED("answered"), // l10n: not text (a stored code)
    /** Waited [Later.WAIT_MS] without a connection: the next chat says so once. */
    EXPIRED("expired"); // l10n: not text (a stored code)

    companion object {
        fun of(code: String?): LaterState? = entries.firstOrNull { it.code == code }
    }
}

/** [said]: your words as kept; [query]: what goes to the search; [asks]: its ask-first rule asked when it was kept. */
data class LaterItem(
    val id: String,
    val said: String,
    val query: String,
    val lang: Lang,
    val askedAt: Long,
    val asks: Boolean,
    val state: LaterState = LaterState.WAITING,
    val answer: String? = null,
)

/** What web search's gate says of a kept question now (WebGate.prepare): off, so nothing is kept or sent; it asks first; or it goes. */
sealed interface LaterGate {
    /** Web search is off or has no service: keep nothing, send nothing, and say [why] (how to turn it on). */
    data class Off(val why: Phrase) : LaterGate
    object Asks : LaterGate
    object Goes : LaterGate

    companion object {
        /** A refusal for anything but "off" asks (fail closed); a card of any kind asks. */
        fun of(p: Prepared): LaterGate = when (p) {
            is Prepared.Refused -> if (p.status == ActionStatus.DISABLED) Off(WebGate.OFF_TURN_ON) else Asks
            is Prepared.Ready -> if (p.why.isNotEmpty() || p.choices.isNotEmpty()) Asks else Goes
            else -> Asks
        }
    }
}

object Later {
    /** At most this many wait (the plan's default). */
    const val CAP = 5
    /** Each waits at most a day. */
    const val WAIT_MS = 24L * 3600 * 1000

    /** Kept, or refused: the queue is full. */
    sealed interface Add {
        data class Kept(val items: List<LaterItem>) : Add
        object Full : Add
    }

    /** Still to run or to send ([WAITING][LaterState.WAITING], [READY][LaterState.READY]). */
    fun waiting(items: List<LaterItem>): List<LaterItem> = items.filter { it.state == LaterState.WAITING || it.state == LaterState.READY }

    fun add(items: List<LaterItem>, item: LaterItem): Add =
        if (waiting(items).size >= CAP) Add.Full else Add.Kept(items + item)

    /**
     * Questions that waited longer than [WAIT_MS] (by the wall clock, which survives a reboot), for a connection or for
     * your OK, are expired; an expired one keeps only its time (not your words, not its search words).
     */
    fun expire(items: List<LaterItem>, now: Long): List<LaterItem> = items.map {
        if ((it.state == LaterState.WAITING || it.state == LaterState.READY) && now - it.askedAt >= WAIT_MS)
            it.copy(said = "", query = "", state = LaterState.EXPIRED, answer = null) else it
    }

    /** The next question to run, oldest first; null when none waits. */
    fun next(items: List<LaterItem>): LaterItem? = items.filter { it.state == LaterState.WAITING }.minByOrNull { it.askedAt }

    /** The oldest waiting question and the queue with it taken off; [item] null when none waits. */
    data class Claim(val items: List<LaterItem>, val item: LaterItem?)

    /**
     * Once only: the oldest [WAITING][LaterState.WAITING] question taken off the queue in one change (inside one
     * LaterVault.update), answered with no answer yet; a crash mid-search leaves it so, and it never goes again.
     */
    fun claim(items: List<LaterItem>): Claim {
        val n = next(items) ?: return Claim(items, null)
        return Claim(settle(items, n.id, LaterState.ANSWERED, null), n)
    }

    /** The ask-first rule: it asked when kept, or it would ask now. A question that would ask never runs silently. */
    fun mustAsk(item: LaterItem, asksNow: Boolean): Boolean = item.asks || asksNow

    /** [item] with its outcome: answered with [answer], or ready to send from the panel. */
    fun settle(items: List<LaterItem>, id: String, state: LaterState, answer: String? = null): List<LaterItem> =
        items.map { if (it.id == id) it.copy(state = state, answer = answer) else it }

    /** What the next unlocked chat shows (answers and expiries, oldest first); they are deleted once shown. */
    fun toShow(items: List<LaterItem>): List<LaterItem> =
        items.filter { it.state == LaterState.ANSWERED || it.state == LaterState.EXPIRED }.sortedBy { it.askedAt }

    /** What the panel takes off the queue ([drop]) and the one it sends ([item]), or none. */
    data class Send(val drop: Set<String>, val item: LaterItem?)

    /**
     * The oldest question back online that needs your OK, sent from the panel; while web search is off ([gate] says
     * [LaterGate.Off]) each is dropped instead and nothing is sent (never a browser search in its place).
     */
    fun toSend(items: List<LaterItem>, gate: (LaterItem) -> LaterGate): Send {
        val drop = LinkedHashSet<String>()
        for (i in items.filter { it.state == LaterState.READY }.sortedBy { it.askedAt }) {
            drop += i.id
            if (gate(i) !is LaterGate.Off) return Send(drop, i)
        }
        return Send(drop, null)
    }

    fun drop(items: List<LaterItem>, ids: Set<String>): List<LaterItem> = items.filterNot { it.id in ids }

    /** The waiting questions [words] names ("the ferry one": any word of 3 letters or more in its words); all with [all]. */
    fun matching(items: List<LaterItem>, words: String?, all: Boolean): List<LaterItem> {
        val w = waiting(items)
        if (all) return w
        val want = OnlineRouter.plain(words ?: return emptyList()).split(' ').filter { it.length >= 3 && it !in STOP }.toSet()
        if (want.isEmpty()) return emptyList()
        return w.filter { i -> OnlineRouter.plain(i.said + " " + i.query).split(' ').any { it in want } }
    }

    private val STOP = setOf("the", "one", "that", "this", "question", "search", "about", "for", "les", "des", "une", "sur", "pour", "recherche") // l10n: not text (words Robot hears)

    fun toJson(items: List<LaterItem>): String = JSONObject().put("v", 1).put("items", JSONArray().apply { // l10n: not text (JSON keys)
        items.forEach { i ->
            put(JSONObject().put("id", i.id).put("said", i.said).put("query", i.query).put("lang", i.lang.id) // l10n: not text (JSON keys)
                .put("at", i.askedAt).put("asks", i.asks).put("state", i.state.code).apply { i.answer?.let { put("answer", it) } }) // l10n: not text (JSON keys)
        }
    }).toString()

    /** The file's items; an entry it can't read is skipped, never guessed. */
    fun fromJson(s: String?): List<LaterItem> {
        val arr = try { JSONObject(s ?: return emptyList()).optJSONArray("items") } catch (_: Exception) { null } ?: return emptyList() // l10n: not text (a JSON key)
        return (0 until arr.length()).mapNotNull { n ->
            val o = arr.optJSONObject(n) ?: return@mapNotNull null
            val state = LaterState.of(o.optString("state")) ?: return@mapNotNull null // l10n: not text (a JSON key)
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null // l10n: not text (a JSON key)
            LaterItem(id, o.optString("said"), o.optString("query"), if (o.optString("lang") == Lang.FR.id) Lang.FR else Lang.EN, // l10n: not text (JSON keys)
                o.optLong("at"), o.optBoolean("asks", true), state, o.optString("answer").takeIf { o.has("answer") }) // l10n: not text (JSON keys)
        }
    }
}

/** The job's seams: the sealed file, the connection, Android's stop, web search's gate and run, the notices and the clock. */
interface LaterPort {
    /** [change] applied to the sealed file in one locked step; null when it can't be read or written now. */
    fun update(change: (List<LaterItem>) -> List<LaterItem>): List<LaterItem>?
    /** A validated connection now. */
    fun online(): Boolean
    /** Android stopped the job. */
    fun stopped(): Boolean
    fun gate(item: LaterItem): LaterGate
    fun search(item: LaterItem): String
    fun ask(item: LaterItem)
    fun answered(item: LaterItem, text: String)
    fun now(): Long
}

/** The job's loop (SignalLater.run in the voice process). */
object LaterRun {
    /**
     * One run; true to try again later (no validated connection, Android stopped it, or the file can't be read). The
     * connection and the stop are checked again before each question, and each is claimed ([Later.claim]) in one change
     * before anything goes, so a restarted job and an old thread never both search it, and a cancelled one never goes.
     */
    fun run(p: LaterPort): Boolean {
        if (!p.online()) return true
        p.update { Later.expire(it, p.now()) } ?: return true
        while (true) {
            if (p.stopped() || !p.online()) return true
            var claimed: LaterItem? = null
            p.update { all -> Later.claim(all).also { claimed = it.item }.items } ?: return true
            val item = claimed ?: return false
            val g = p.gate(item)
            if (g is LaterGate.Off) {
                // Web search went off while it waited: dropped, and nothing goes.
                p.update { Later.drop(it, setOf(item.id)) } ?: return true
                continue
            }
            if (Later.mustAsk(item, g != LaterGate.Goes)) {
                p.update { Later.settle(it, item.id, LaterState.READY) } ?: return true
                p.ask(item)
                continue
            }
            val text = p.search(item)
            p.update { Later.settle(it, item.id, LaterState.ANSWERED, text) } ?: return true
            p.answered(item, text)
        }
    }
}

/** Signal later's phrases, in the everyday slot with What went online's. */
sealed interface LaterCommand : OnlineCommand

/** "Look up the ferry times to Digby when we're back in range": [said] your words without the tail, [query] the search's. */
data class LaterKeep(override val lang: Lang, val said: String, val query: String) : LaterCommand

/** "Call mom when we're back in range": a tail with no look-up in front; Robot says it can only hold a search. */
data class LaterCant(override val lang: Lang) : LaterCommand

/** "What's waiting?" */
data class LaterList(override val lang: Lang) : LaterCommand

/** "Cancel the ferry one", "cancel all the waiting questions". */
data class LaterCancel(override val lang: Lang, val words: String?, val all: Boolean) : LaterCommand

object LaterRouter {
    private val PREFACE = Regex("^(?:(?:there s |theres |there is )?no|we have no|weve got no|i have no|no more) (?:signal|service|reception|internet|connection|data|network)" + // l10n: not text (words Robot hears)
        "(?: out here| here| right now| now)? (.+)$") // l10n: not text (words Robot hears)
    private val PREFACE_FR = Regex("^(?:pas de|il n y a pas de|il y a pas de|y a pas de|ya pas de|on a pas de|jai pas de) (?:reseau|signal|connexion|internet|service)" + // l10n: not text (words Robot hears)
        "(?: ici| icitte| pour l instant| pour linstant)? (.+)$") // l10n: not text (words Robot hears)
    private val TAIL_EN = Regex("^(.+?) (?:when|once|as soon as) (?:(?:were|we are|im|i am|youre|you are|the phone is|its|it is) back (?:in range|online|in service|on the network|in signal)" + // l10n: not text (words Robot hears)
        "|(?:theres|there is|we have|we get|i have|i get|we got) (?:signal|service|a signal|a connection|internet|data|reception)(?: again)?)$") // l10n: not text (words Robot hears)
    private val TAIL_FR = Regex("^(.+?) (?:quand|des que|lorsque|aussitot que) (?:(?:on|je|tu) (?:sera|seras|serai|aura|auras|aurai|va avoir|retrouvera|retrouve) " + // l10n: not text (words Robot hears)
        "(?:de retour |a nouveau |encore )?(?:du reseau|du signal|en ligne|de la connexion|internet|une connexion|connecte|connectes)|il y aura (?:du reseau|du signal|une connexion|internet))$") // l10n: not text (words Robot hears)
    private val LEAD_EN = Regex("^(?:(?:can you|could you|please) )?(?:look up|search (?:the web |online )?for|search|find out|find|check|google) (.+)$") // l10n: not text (words Robot hears)
    private val LEAD_FR = Regex("^(?:(?:peux tu|pourrais tu) )?(?:cherche(?: sur le web| en ligne)?|chercher|trouve|regarde|verifie) (.+)$") // l10n: not text (words Robot hears)
    // A question is a look-up too: "what time does the ferry leave when we're back in range".
    private val ASK_EN = Regex("^(?:what|whats|when|where|who|whos|how|which|why|is there|are there|is the|are the" + // l10n: not text (words Robot hears)
        // A bare do/does/did needs its subject: "do the transfer to Sam" is a command, not a question.
        "|do (?:i|you|we|they|people|these|those)|does (?:it|he|she|the|a|an|this|that|anyone|anybody|someone)" + // l10n: not text (words Robot hears)
        "|did (?:i|you|we|they|it|he|she|the|a|an|this|that|anyone|anybody|someone)" + // l10n: not text (words Robot hears)
        // Or a short subject then a common verb: "does Costco open at 9", "do the ferries run Sunday" (not "do the dishes").
        "|(?:do|does|did) (?:the )?\\w+(?: \\w+)? (?:open|close|run|leave|sail|fly|play|start|win|come|cost|sell|have|take|deliver|accept)) .+$") // l10n: not text (words Robot hears)
    // What the phone answers itself (the time, the date, alarms, timers, reminders, the battery, your own things): never kept for the web.
    private val LOCAL_EN = Regex("^(?:what time is it|whats the time|what is the time|what day is it|what day is today|whats the date|what is the date|whats todays date|what is todays date)\\b" + // l10n: not text (words Robot hears)
        "|\\b(?:alarms?|timers?|reminders?|battery)\\b" + // l10n: not text (words Robot hears)
        // "my" only before the phone's own things: "when does my flight leave" is a web question.
        "|\\bmy (?:next |own )?(?:alarms?|timers?|reminders?|battery|phone|calendar|contacts?|messages?|notes?)\\b") // l10n: not text (words Robot hears)
    private val LOCAL_FR = Regex("^(?:quelle heure (?:est il|il est|quil est)|quel jour (?:on est|sommes nous|est on)|quelle (?:est la )?date)\\b" + // l10n: not text (words Robot hears)
        "|\\b(?:alarmes?|minuteries?|minuteurs?|rappels?|reveils?|batterie)\\b" + // l10n: not text (words Robot hears)
        "|\\b(?:mon|ma|mes) (?:prochaine? |prochaines? )?(?:alarmes?|minuteries?|minuteurs?|rappels?|reveils?|batterie|telephone|cellulaire|calendrier|agenda|contacts?|messages?|textos?|notes?)\\b") // l10n: not text (words Robot hears)
    private val ASK_FR = Regex("^(?:quand|quel|quelle|quels|quelles|quest ce que|qu est ce que|combien|comment|ou|qui|est ce que|y a t il|a quelle heure) .+$") // l10n: not text (words Robot hears)
    private val LIST = Regex("^(?:(?:whats|what is|what are|which questions are|what questions are) (?:still )?waiting(?: for (?:a connection|signal|the internet|service))?" + // l10n: not text (words Robot hears)
        "|(?:quest ce qui|qu est ce qui|quoi qui) (?:attend|est en attente)(?: une connexion| du reseau)?|quelles questions (?:attendent|sont en attente))$") // l10n: not text (words Robot hears)
    private val ALL_EN = Regex("^(?:cancel|forget|drop|delete) (?:all )?(?:the |my )?(?:waiting|saved|kept) (?:questions|searches)$") // l10n: not text (words Robot hears)
    private val ONE_EN = Regex("^(?:cancel|forget|drop) (?:the|my) (.+?) (?:one|question|search)$") // l10n: not text (words Robot hears)
    private val ALL_FR = Regex("^(?:annule|oublie) (?:toutes )?(?:les|mes) (?:questions|recherches) en attente$") // l10n: not text (words Robot hears)
    private val ONE_FR = Regex("^(?:annule|oublie) (?:la|ma) (?:question|recherche) (?:sur|de|du|des|pour|a propos de|a propos du) (.+)$") // l10n: not text (words Robot hears)

    fun route(q: String): LaterCommand? {
        val p = OnlineRouter.plain(q)
        if (p.isEmpty()) return null
        if (LIST.matches(p)) return LaterList(if (p.startsWith("what") || p.startsWith("which")) Lang.EN else Lang.FR) // l10n: not text (words Robot hears)
        ALL_EN.matchEntire(p)?.let { return LaterCancel(Lang.EN, null, all = true) }
        ALL_FR.matchEntire(p)?.let { return LaterCancel(Lang.FR, null, all = true) }
        ONE_EN.matchEntire(p)?.let { return LaterCancel(Lang.EN, it.groupValues[1], all = false) }
        ONE_FR.matchEntire(p)?.let { return LaterCancel(Lang.FR, it.groupValues[1], all = false) }
        val unprefaced = PREFACE.matchEntire(p)?.groupValues?.get(1) ?: PREFACE_FR.matchEntire(p)?.groupValues?.get(1) ?: p
        TAIL_EN.matchEntire(unprefaced)?.let { return keep(q, it.groupValues[1], Lang.EN, LEAD_EN, ASK_EN, LOCAL_EN) }
        // « dès qu'il y aura » loses its apostrophe in plain(): « dès que il y aura » for the tail.
        val fr = unprefaced.replace(" quil ", " que il ").replace(" lorsquil ", " lorsque il ") // l10n: not text (words Robot hears)
        TAIL_FR.matchEntire(fr)?.let { return keep(q, it.groupValues[1], Lang.FR, LEAD_FR, ASK_FR, LOCAL_FR) }
        return null
    }

    /**
     * Only a look-up ("look up", "search", "find out" …, or a question the phone can't answer itself, [local]) is kept;
     * anything else is [LaterCant], never a search.
     */
    private fun keep(original: String, rest: String, lang: Lang, lead: Regex, ask: Regex, local: Regex): LaterCommand? {
        val query = lead.matchEntire(rest)?.groupValues?.get(1) ?: rest.takeIf { ask.matches(it) && !local.containsMatchIn(it) } ?: return LaterCant(lang)
        if (query.split(' ').size < 2) return null
        return LaterKeep(lang, said(original, rest), query)
    }

    /** [rest]'s words as you said them (their capitals kept) when the original's words line up; else [rest]. */
    internal fun said(original: String, rest: String): String {
        val words = original.trim().split(Regex("\\s+"))
        val n = rest.split(' ').size
        for (start in 0..(words.size - n).coerceAtLeast(0)) {
            val cut = words.subList(start, (start + n).coerceAtMost(words.size)).joinToString(" ")
            if (OnlineRouter.plain(cut) == rest) return cut.trimEnd('.', ',', '!', '?', ';', ':')
        }
        return rest
    }
}
