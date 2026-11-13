// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.PromptTurn
import app.heyrobot.core.web.WebOneShot
import app.heyrobot.core.web.WebPrompt
import java.time.ZonedDateTime

// Pure. The panel's turn with the copy of Wikipedia on this phone (wikipedia-offline-plan.md §6, job P4): asked after
// commands and memory (every router in PanelController.send ran first and took nothing) and before the model alone, only
// for a question WikiRoute sends (FACT: never personal, fresh or a command), your own words, nothing attached, outside a
// helper's conversation, with a copy on the phone. What the model writes from it reaches you only through
// WikiAnswer.reply (the month, "nothing on <subject>") and WikiCard (P4b: the articles, their links, the licence, the copy's month).
object WikiTurn {
    /** The panel asks the copy at all. */
    fun asks(question: String, copies: Int, attached: Boolean, helperMode: Boolean, untrusted: Boolean, own: Own = Own.NONE): Boolean =
        refusal(question, copies, attached, helperMode, untrusted, own) == null

    /** fix-wiki-rc1-b: which check keeps the copy out of a turn (logged by the panel, numbers and codes only), or null when it asks. */
    enum class Gate { ROUTE, COPIES, SOMEONE, RECALLS }

    fun refusal(question: String, copies: Int, attached: Boolean, helperMode: Boolean, untrusted: Boolean, own: Own = Own.NONE): Gate? = when {
        !routes(question, attached, helperMode, untrusted) -> Gate.ROUTE
        copies <= 0 -> Gate.COPIES
        WikiRoute.someone(question, own.names()) -> Gate.SOMEONE
        own.recalls(question) -> Gate.RECALLS
        else -> null
    }

    /** L5 (review-wikipedia-p4): the copy's kind of question at all, before anything on the phone is read (copies, contacts, memory). */
    fun routes(question: String, attached: Boolean, helperMode: Boolean, untrusted: Boolean): Boolean =
        !attached && !helperMode && !untrusted && WikiRoute.goes(question)

    /** H1 (review-wikipedia-p4): what on this phone a question may be about, read only for the copy's question. */
    interface Own {
        /** People's and the phone's contacts' names. */
        fun names(): List<app.heyrobot.core.ContactName>
        /** Memory has an item for [question]. */
        fun recalls(question: String): Boolean

        companion object {
            val NONE = object : Own {
                override fun names() = emptyList<app.heyrobot.core.ContactName>()
                override fun recalls(question: String) = false
            }
        }
    }

    /**
     * M2 (review-wikipedia-p4): the one-shot's words when it finished, else null (the turn is Stopped): a timed-out one-shot's
     * partial words are never given as the copy's answer.
     */
    fun said(o: app.heyrobot.core.OneShotResult): String? = (o as? app.heyrobot.core.OneShotResult.Done)?.text

    /** A reply from the copy: [shown] on screen with [card] under it (P4b: the articles, their links and the licence), [spoken] aloud, [articles] read. */
    /**
     * review-wikipedia-p4-b (Low, PanelWiki): what a stopped turn keeps of the words streamed so far: its whole sentences the
     * passages hold (WikiAnswer.checked), up to the first they don't; unchecked words never stay under the copy's name.
     * L4: so it keeps what a car or a headset heard (WikiSpeech).
     */
    fun kept(found: WikiAnswer.Found?, partial: String): String = found?.let { WikiSpeech.kept(it, partial) } ?: ""

    data class Reply(val shown: String, val spoken: String, val articles: List<String>, val card: WikiCard)

    /** The reply from the model's [text] for what the copy [found]. */
    fun reply(found: WikiAnswer.Found, text: String): Reply {
        val said = WikiAnswer.reply(found, text)
        return Reply(said, said, found.articles.map { it.title }, WikiCard.of(found))
    }

    sealed class Result {
        /** Not the copy's question, or nothing found in it: the model answers alone, as before. */
        object NotAsked : Result()
        /** The model stopped or failed on the copy's one-shot: so does the reply. */
        object Stopped : Result()
        data class Answered(val reply: Reply) : Result()
    }

    /** The whole turn: [copies] opened only once the question is the copy's ([open]); the copy's one-shot goes to [model]. */
    suspend fun run(question: String, copies: Int, open: (lang: String) -> List<WikiCopy>, now: ZonedDateTime, recent: List<PromptTurn>,
        spoken: Boolean, budget: Int = WebPrompt.INPUT_TOKENS, attached: Boolean = false, helperMode: Boolean = false,
        untrusted: Boolean = false, own: Own = Own.NONE,
        // M4 (review-wikipedia-p4): the copy's open and search, under the panel's deadline (WikiGuard.within).
        find: suspend (() -> WikiAnswer.Found?) -> WikiAnswer.Found? = { it() }, model: suspend (WebOneShot) -> String?): Result {
        if (!asks(question, copies, attached, helperMode, untrusted, own)) return Result.NotAsked
        val found = find { WikiAnswer.answer(question, open(WikiRoute.lang(question)), now, recent, spoken, budget) } ?: return Result.NotAsked
        return model(found.shot)?.let { Result.Answered(reply(found, it)) } ?: Result.Stopped
    }
}
