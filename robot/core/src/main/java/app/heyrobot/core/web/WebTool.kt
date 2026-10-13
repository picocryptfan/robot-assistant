package app.heyrobot.core.web

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Choice
import app.heyrobot.core.HelperContext
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.Prepared
import app.heyrobot.core.Priority
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import org.json.JSONObject
import java.time.LocalDate

// Pure. The web_search tool's rules between the model's call and the network (design A4.2, A5, A10.2, A10.3): web search
// on and set up, today's cap, your Home for weather town for "near me" when that switch is on, the query's check
// (QueryCheck: refused, a card, or it goes), and "Ask before searching" (the first search's tap-only card, Always ask's
// plain card, or none). Then what a search brings back to the panel (WebOutcome), the one-shot request it answers with,
// what Robot says while it works, and the count of today's searches.

/** What a web search brought back: the panel answers from [Found] in a tool-free one-shot, or says [Failed]'s words. */
sealed class WebOutcome {
    /**
     * [query] as it went, [lang] the question's language, [results] the service's (and Wikipedia's), [passages] the
     * pages' (or the service's own page text) best parts for the question, [pagesRead] how many pages gave text,
     * [throughService] read through the search service rather than from this phone, [skipped] the pages passed over and
     * why (domain and reason, for the card), [answers] the short answers a SearXNG computed itself, [also] who else was
     * sent the words (1.0, network-1: [WebAlso.WIKIPEDIA], [WebAlso.OPEN_LIBRARY]), for the card's and History's lines.
     */
    data class Found(
        val service: SearchService,
        val query: String,
        val lang: String,
        val results: List<SearchResult>,
        val passages: List<Passage> = emptyList(),
        val pagesRead: Int = 0,
        val throughService: Boolean = false,
        val skipped: List<Pair<String, PageSkip>> = emptyList(),
        val answers: List<String> = emptyList(),
        val also: List<String> = emptyList(),
    ) : WebOutcome()

    /** Nothing to answer from: [why] in fixed words, naming [service]. */
    data class Failed(val service: SearchService?, val why: WebFail) : WebOutcome() {
        val text: String get() = why.text(service).let { if (it.endsWith(".")) it else "$it." }
    }
}

/** Today's searches (web_count.json, written by the voice process): the daily cap and Settings' count. */
data class WebCount(val day: String = "", val count: Int = 0) {
    fun on(today: LocalDate): Int = if (day == today.toString()) count else 0

    fun add(today: LocalDate): WebCount = WebCount(today.toString(), on(today) + 1)

    fun toJson(): String = JSONObject().put("day", day).put("count", count).toString()

    companion object {
        const val FILE = "web_count.json"

        fun fromJson(text: String?): WebCount = try {
            val o = JSONObject(text ?: return WebCount())
            WebCount(o.optString("day"), o.optInt("count").coerceAtLeast(0))
        } catch (_: Exception) {
            WebCount()
        }
    }
}

object WebGate {
    /** "near me" and its French: replaced by your Home for weather town's name when "Add my town to local searches" is on. */
    private val NEAR_ME = Regex("\\b(?:near me|nearby|close to me|around me|pres de chez moi|pres de moi|proche de moi|autour de moi)\\b", RegexOption.IGNORE_CASE)

    /** Pages a search may open from this phone with [s] (0 when it never reads pages, or reads them through the service). */
    fun pagesFromPhone(s: WebSettings): Int = when {
        s.readPages == ReadPages.NEVER -> 0
        // 1.0: DuckDuckGo's Instant Answers are short answers with their source; no page is read.
        s.service != null && !WebSetup.readsPages(s.service) -> 0
        s.readThrough == ReadThrough.SERVICE && (s.service == SearchService.BRAVE || s.service == SearchService.KAGI) -> 0
        else -> s.pages
    }

    /**
     * [query] with "near me" as "near <town>" when [addTown] is on and your Home for weather is set ([home]: its name as
     * Settings has it, "Saint-Jean-sur-Richelieu, Quebec, Canada": only the town goes). Never coordinates.
     */
    fun withTown(query: String, addTown: Boolean, home: String?): String {
        val town = home?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: return query
        if (!addTown) return query
        val folded = WebWords.fold(query)
        val m = NEAR_ME.find(folded) ?: return query
        // The fold keeps the length of plain words, so the match's place is the query's (accents decomposed aside).
        return if (folded.length == query.length) query.replaceRange(m.range, "near $town") else NEAR_ME.replace(folded, "near $town")
    }

    /**
     * The web_search call [a] made ready for the gate, or refused before anything leaves the phone: web search Off (or
     * turned off by a "Don't ask again" No), today's cap reached ([searchesToday]), or a query that never goes. Otherwise
     * the query (cleaned, with your town when asked) and the reasons for a card: QueryCheck's ([AskWhy.SEARCH], or
     * [AskWhy.SEARCH_PERSONAL] answered by a tap) and "Ask before searching" (Ask the first time: [AskWhy.ONLINE] with
     * Allow always, Just this time and No, and Don't ask again; Always ask: [AskWhy.SEARCH]). The gate adds its own
     * (the level, the lock, content in the conversation).
     */
    fun prepare(a: Action.WebSearch, s: WebSettings, answer: WebConsentAnswer?, ctx: QueryContext, searchesToday: Int, home: String? = null,
        question: String = a.query): Prepared {
        if (!WebConsent.on(s, answer)) return Prepared.Refused(ActionStatus.DISABLED, "Web search is off: turn it on in Robot's Settings › Web search.")
        val service = s.service ?: return Prepared.Refused(ActionStatus.DISABLED, WebFail.OFF.text(null) + ".")
        if (searchesToday >= s.dailySearches) {
            return Prepared.Refused(ActionStatus.FAILED, "${WebFail.DAILY_CAP.text(service)} (${s.dailySearches}). Settings › Web search can raise it.")
        }
        val why = LinkedHashSet<AskWhy>()
        val notes = ArrayList<String>()
        val query = when (val v = QueryCheck.check(withTown(a.query, s.addTown, home), ctx)) {
            is QueryVerdict.Refuse -> return Prepared.Refused(ActionStatus.FAILED, v.why.text)
            is QueryVerdict.Card -> {
                why += if (v.why.tapOnly) AskWhy.SEARCH_PERSONAL else AskWhy.SEARCH
                notes += v.why.note
                v.query
            }
            is QueryVerdict.Send -> v.query
        }
        val action = a.copy(query = query, consent = null)
        // 1.0 (network-1): the card names everyone the words go to (Wikipedia, Open Library: WebAlso), not only the service.
        val also = WebAlso.names(s, service, question.ifBlank { query })
        val card = WebConsent.card(WebConsent.ask(s, answer), service, query, pagesFromPhone(s), also)
        if (card == null) {
            // A query card with no consent card still says who besides the service would get the words.
            if (notes.isNotEmpty() && also.isNotEmpty()) notes += WebConsent.note(service, pagesFromPhone(s), also)
            return Prepared.Ready(action, why, note = notes.joinToString(" ").ifEmpty { null })
        }
        notes += card.note
        if (!card.tapOnly) return Prepared.Ready(action, why + AskWhy.SEARCH, note = notes.joinToString(" "))
        val always = action.copy(consent = WebConsentChoice.ALWAYS)
        return Prepared.Ready(
            action,
            why = why + AskWhy.ONLINE,
            choices = listOf(
                Choice(WebConsent.ALLOW_ALWAYS, always, always),
                Choice(WebConsent.JUST_THIS_TIME, action.copy(consent = WebConsentChoice.ONCE), always),
                Choice(WebConsent.NO, action.copy(consent = WebConsentChoice.NO), action.copy(consent = WebConsentChoice.NEVER)),
            ),
            note = notes.joinToString(" "),
            remember = card.remember,
        )
    }

    /** The search runs ([consent] null: no card was needed; Allow always or Just this time). "No" and "No, don't ask again" don't. */
    fun searches(consent: WebConsentChoice?): Boolean = consent != WebConsentChoice.NO && consent != WebConsentChoice.NEVER

    /** What the card says when you answered No: no search, and Gemma answers without it. */
    const val NOT_SEARCHED = "Not searched: Robot answers without the web."
}

/**
 * Where a conversation's words came from, for QueryCheck (A10.3): your own words (every question's text, never the screen's
 * or a photo's), content Robot didn't write (a helper's answers, an answer read off the Claude app, Robot's answers made
 * from web pages, and screen text), and personal content (Robot's answers after a read tool found something on your
 * phone, with what the cards showed of it, [personalExtra]).
 */
object WebQueryContext {
    /**
     * 0.9 with 0.7: a turn holding messages (read, summarized, a reply card, or a message's words heard back while Robot read
     * it, which came in as a "question") is other people's words: personal content a tap answers, never your own words.
     * [memory]: the texts of the memory items the model was given in this conversation (QueryContext.memory). [fresh]:
     * [personalExtra] holds everything every read tool returned to the model in this conversation (the panel's live
     * conversation: [readTexts]), so earlier reads leave no words unaccounted for.
     */
    fun of(turns: List<ChatTurn>, said: String, screen: String? = null, personalExtra: List<String> = emptyList(), memory: List<String> = emptyList(),
        fresh: Boolean = false): QueryContext {
        val heardBack = turns.lastOrNull { it.role == TurnRole.USER }?.let { it.messages && it.text == said } == true
        val mine = turns.filter { it.role == TurnRole.USER && !it.messages && !it.locked }.map { it.text } + (if (heardBack) "" else said)
        val untrusted = ArrayList<String>()
        val personal = ArrayList<String>(personalExtra)
        // 1.0 audit (injection-querycheck-notes-personal): sources whose words aren't all here (a photo's, a screen not kept,
        // what a read tool returned to the model in an earlier turn: only the reply and the card are kept) make every word
        // that isn't yours a card, word by word.
        var blindPersonal = false
        var blindContent = false
        for (t in turns) {
            // 1.0: what was said while the phone was locked (ChatTurn.locked) may be anyone's words: content, never yours.
            if (t.locked) untrusted += t.text
            t.screenText?.let { untrusted += it }
            if (t.attachment == Attachment.PHOTO) blindPersonal = true
            if (t.attachment == Attachment.SCREEN && t.screenText == null) blindContent = true
            if (t.messages) {
                personal += t.text
                // From History: only the placeholder ("Robot read 2 messages from Mom") is left of what was read.
                if (t.kept != null && t.text == t.kept) blindPersonal = true
                continue
            }
            if (t.role != TurnRole.ASSISTANT) continue
            if (t.actions.any { it.content && it.tool != ToolName.WEB_SEARCH } && !fresh) blindPersonal = true
            val web = t.actions.any { it.tool == ToolName.WEB_SEARCH && it.content }
            if (t.claude || t.readBack || web) untrusted += t.text
            val read = t.actions.filter { it.content && it.tool != ToolName.WEB_SEARCH }
            if (read.isNotEmpty()) {
                personal += t.text
                read.forEach { r -> r.detail?.let { personal += it } }
            }
        }
        screen?.takeIf { it.isNotBlank() }?.let { untrusted += it }
        val content = TurnRules.untrusted(turns) || untrusted.isNotEmpty()
        val isPersonal = HelperContext.personal(turns).isNotEmpty() || personal.isNotEmpty()
        return QueryContext(
            said = mine.filter { it.isNotBlank() },
            untrusted = untrusted,
            personal = personal,
            content = content,
            isPersonal = isPersonal,
            memory = memory.filter { it.isNotBlank() },
            blindPersonal = blindPersonal || (isPersonal && personal.isEmpty()),
            blindContent = blindContent || (content && untrusted.isEmpty()),
        )
    }
}

/**
 * 1.0 audit (injection-querycheck-notes-personal): every string a read tool returned to the model ([Executed.results]: a
 * note's text, an event's place, a contact's number), for QueryCheck's personal words. The card's body (find_notes': the
 * titles) is only part of it.
 */
fun readTexts(results: List<Map<String, Any?>>?): List<String> {
    val out = ArrayList<String>()
    fun walk(v: Any?) {
        when (v) {
            is String -> if (v.isNotBlank()) out += v
            is Map<*, *> -> v.values.forEach(::walk)
            is Iterable<*> -> v.forEach(::walk)
            is Array<*> -> v.forEach(::walk)
        }
    }
    results?.forEach(::walk)
    return out
}

/** What Robot says and shows while a search works (A4.3), in the question's language. */
object WebStatus {
    fun searching(lang: String?): String = if (lang == "fr") "Je cherche sur le web…" else "Searching the web…"

    /** "Reading 3 pages…" (spoken once), and with [done] the panel's count ("Reading pages 1/3…"). */
    fun reading(pages: Int, lang: String?, done: Int? = null): String = when {
        done != null -> if (lang == "fr") "Je lis les pages $done/$pages…" else "Reading pages $done/$pages…"
        lang == "fr" -> "Je lis $pages page${if (pages == 1) "" else "s"}…"
        else -> "Reading $pages page${if (pages == 1) "" else "s"}…"
    }
}

/** The WEB_ANSWER one-shot as :brain's request (REQ_ONE_SHOT, the panel's priority, no tools, no history). */
fun WebOneShot.request(): BrainMsg.OneShot = BrainMsg.OneShot(OneShotKind.WEB_ANSWER, Priority.PANEL, system, text, maxOutputTokens)

/** The voice log's web search lines as VoiceEvent numbers (A10.5): codes and counts only. */
object WebLogNumbers {
    /** WEB_PAGES' b: pages skipped (at most 9) and the first skip's [PageSkip.code] in the tens. */
    fun skipped(skipped: List<PageSkip>): Int = skipped.size.coerceAtMost(9) + 10 * (skipped.firstOrNull()?.code ?: 0)

    fun service(code: Int): String = SearchService.entries.firstOrNull { it.code == code }?.title ?: "#$code"

    fun skip(code: Int): String = PageSkip.entries.firstOrNull { it.code == code }?.text ?: "#$code"

    fun fail(id: Int): String = WebFail.entries.firstOrNull { it.id == id }?.title ?: "#$id"

    fun engine(code: Int): String = BrowserEngine.entries.firstOrNull { it.code == code }?.title ?: "#$code"

}

/** What a search costs, in dollars (ServiceTerms' prices; Mojeek's pounds at about 1.3 dollars): it joins the helpers' daily budget. */
object WebPrices {
    const val USD_PER_GBP = 1.3

    fun dollars(service: SearchService, extractedPages: Int = 0): Double = when (service) {
        SearchService.BRAVE -> ServiceTerms.Brave.USD_PER_1000 / 1000
        SearchService.KAGI -> ServiceTerms.Kagi.USD_PER_1000 / 1000 + extractedPages * ServiceTerms.Kagi.EXTRACT_USD_PER_1000 / 1000
        SearchService.MOJEEK -> ServiceTerms.Mojeek.GBP_PER_1000 * USD_PER_GBP / 1000
        else -> 0.0
    }
}

/** A search service's key as pasted in the set-up window: where to get one, whether it can be one, and its masked form. */
object WebKeyFormat {
    /** Where each service's key comes from (design A4.1, "Where do I get one?"). */
    fun where(service: SearchService): String = when (service) {
        SearchService.BRAVE -> "Create a key at api-dashboard.search.brave.com (Search plan; a card on file even for the free credit) and paste it here."
        SearchService.KAGI -> "Create an API token at kagi.com/settings › API (pay per use) and paste it here."
        SearchService.MOJEEK -> "Mojeek gives keys through its sales contact (mojeek.com/services/search/web-search-api); paste yours here."
        else -> ""
    }

    /** Why [key] can't be a key (empty, spaces, too short or long), or null. */
    fun problem(key: String): String? {
        val k = key.trim()
        return when {
            k.isEmpty() -> "Paste the key."
            k.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E } -> "A key has no spaces or special characters: copy it again."
            k.length < 10 -> "That's too short for a key."
            k.length > 300 -> "That's too long for a key."
            else -> null
        }
    }

    /** "BSA…x7Q2": what Settings shows of a saved key. */
    fun mask(key: String): String {
        val k = key.trim()
        return if (k.length <= 8) "…" + k.takeLast(2) else k.take(3) + "…" + k.takeLast(4)
    }
}
