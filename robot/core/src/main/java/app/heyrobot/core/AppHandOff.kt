// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings

/**
 * 1.0.0: the hand-off to any helper's app (the Claude app's route, for every company with an Android app). Robot opens
 * the app with the question and steps aside; you press Send there. The Claude app keeps its links first (a, b); every
 * app is tried with a share of the text (SEND text/plain to its package), else just opened. The question always goes on
 * the clipboard too, so it can be pasted when an app ignores the share. Never automation, never reading the app.
 */
object AppHandOff {
    /** At most this many characters of the question go in the link. */
    const val MAX_CHARS = 2_000

    /** a, b, c, d in the order Robot tries them; the first that resolves to the app is used. */
    enum class Way {
        /** VIEW `claude://claude.ai/new?q=…` (documented for Claude Desktop; unverified on Android). */
        APP_LINK,
        /** VIEW `https://claude.ai/new?q=…` with the package set: only with verified app links for that path. */
        WEB_LINK,
        /** SEND text/plain with EXTRA_TEXT to the package. */
        SEND,
        /** Its launch intent only; the question goes on the clipboard and Robot says to paste it there. */
        LAUNCH,
    }

    /** The ways tried for [pkg], in order; [Way.LAUNCH] last. */
    fun ways(pkg: String): List<Way> =
        if (pkg == ClaudeAppLink.PACKAGE) listOf(Way.APP_LINK, Way.WEB_LINK, Way.SEND, Way.LAUNCH)
        else listOf(Way.SEND, Way.LAUNCH)

    /** The first way that resolves to [pkg], or null when it isn't installed. */
    fun choose(pkg: String, installed: Boolean, resolves: (Way) -> Boolean): Way? {
        if (!installed) return null
        val w = ways(pkg)
        return w.dropLast(1).firstOrNull(resolves) ?: w.last().takeIf(resolves)
    }

    /** The card's line while it waits: what goes, to which app, and that you send it ([app]: "ChatGPT"). In English: [note]. */
    fun noteText(app: String, text: String): Phrase =
        if (text.isBlank()) ToolStrings.handOffNote("appName" to app) else ToolStrings.handOffNoteText("appName" to app)

    fun note(app: String, text: String): String = noteText(app, text).en()

    /** What happened, for the card and the voice: [way] was used, [text] went with it (empty: the app only). */
    fun doneText(app: String, way: Way, text: String): Phrase = when {
        text.isEmpty() -> ToolStrings.appOpened("appName" to app)
        way == Way.LAUNCH -> ToolStrings.appPaste("appName" to app)
        else -> ToolStrings.appWithQuestion("appName" to app)
    }

    fun done(app: String, way: Way, text: String): String = doneText(app, way, text).en()

    /** No helper on opens an app, or its app isn't installed. */
    fun installText(app: String?): Phrase = if (app == null) ToolStrings.noHelperApp() else ToolStrings.installApp("appName" to app)

    fun install(app: String?): String = installText(app).en()

    /**
     * At most [max] characters, cut after the last sentence that fits when there is one in the second half, otherwise
     * on a space, never inside an emoji.
     */
    fun cap(s: String, max: Int = MAX_CHARS): String {
        val t = s.trim()
        if (t.length <= max) return t
        val head = ToolCalls.safeTake(t, max)
        val sentence = Regex("""[.!?…](?=\s)""").findAll(head).lastOrNull()?.range?.last
        if (sentence != null && sentence >= max / 2) return head.substring(0, sentence + 1)
        val space = head.lastIndexOf(' ')
        return if (space >= max / 2) head.substring(0, space).trimEnd() else head
    }

    /**
     * "Go get" a helper with no question: one to three sentences from the conversation's own words (never written by the
     * local model): what the user last asked and the start of Robot's answer. Null for an empty conversation (the app
     * opens without text). In a personal conversation (screen text, a photo, what Robot read, a read-back) Robot's
     * answer is not quoted: only the user's own question goes on the card.
     */
    fun summary(turns: List<ChatTurn>): String? {
        val asked = turns.lastOrNull { it.role == TurnRole.USER && it.text.isNotBlank() && HelperRouter.route(it.text) == null } ?: return null
        val q = oneLine(asked.text).let { ToolCalls.safeTake(it, 400).trimEnd() + if (it.length > 400) "…" else "" }
        val reply = turns.drop(turns.indexOf(asked) + 1).firstOrNull { it.role == TurnRole.ASSISTANT && !it.error && it.text.isNotBlank() }
        val said = reply?.takeIf { HelperContext.personal(turns).isEmpty() }?.let { firstSentence(it.text) }
        return buildString {
            append("I asked my phone's assistant: “").append(q.trimEnd('.', '?', '!')).append("”.") // l10n: not text (what the helper's app is given)
            if (said != null) append(" It said: “").append(said.trimEnd('.')).append("”.") // l10n: not text (what the helper's app is given)
            append(" Can you help with that?") // l10n: not text (what the helper's app is given)
        }
    }

    private fun firstSentence(s: String): String {
        val t = oneLine(Markdown.plain(s))
        val end = Regex("""[.!?](?=\s|$)""").find(t)?.range?.last
        val first = if (end != null) t.substring(0, end + 1) else t
        return if (first.length <= 300) first else ToolCalls.safeTake(first, 300).trimEnd() + "…"
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
