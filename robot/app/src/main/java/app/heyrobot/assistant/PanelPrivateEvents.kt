// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.BriefText
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.CalendarFormat
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.EventKind
import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Langs

/**
 * Today fix (review-today-first, finding 3): private events are sealed on this phone, so a reply that says their titles
 * ("how's my day", the brief) is kept with "a private event" in place of each title, in the conversation, in History and
 * in its backups, as PanelFileAnswer keeps a private file's answer. What was shown and said at the time is unchanged.
 */
object PanelPrivateEvents {
    private val titles = LinkedHashMap<String, Set<String>>()
    /** fix-126-review 6: titles dropped with a reply that hadn't ended, still hidden should that reply end after all. */
    private val dropped = HashSet<String>()

    /** [events] were read for [replyId]: the private ones' titles, as written and as Robot says them, are kept out of History. */
    fun read(replyId: String, events: List<CalendarEvent>?) {
        val t = events.orEmpty().filter { it.kind == EventKind.PRIVATE }
            .flatMap { listOf(it.title.trim(), BriefText.clean(it.title, CalendarFormat.TITLE)) }.filter { it.isNotBlank() }.toSet()
        if (t.isNotEmpty()) synchronized(titles) { titles[replyId] = titles[replyId].orEmpty() + t }
    }

    /** [turn] as the conversation and History keep it. */
    fun forHistory(turn: ChatTurn, langs: Langs): ChatTurn {
        val t = synchronized(titles) {
            // fix-today-review-b: the reply ends here, and with it every one read before it (a reply it replaced, whose turn
            // never comes here), so no title waits in memory for a reply that won't end.
            // fix-126-review 6: fail closed, their titles stay hidden in this conversation.
            titles[turn.id]?.also { while (titles.keys.first().let { k -> titles.remove(k)?.let { if (k != turn.id) dropped += it }; k != turn.id }) Unit }
                .orEmpty().plus(dropped).takeIf { it.isNotEmpty() }
        } ?: return turn
        return turn.copy(text = hide(turn.text, t, BriefStrings.privateEvent().render(langs)))
    }

    /** [text] with each of [titles] (the longest first, so a title inside another is never left half) as [placeholder]. */
    fun hide(text: String, titles: Set<String>, placeholder: String): String =
        // fix-today-review 5: whole words only, so a private "Gym" leaves "Gymnastics" as it is.
        titles.filter { it.isNotBlank() }.sortedByDescending { it.length }
            .fold(text) { s, t -> s.replace(Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(t) + "(?![\\p{L}\\p{N}_])"), Regex.escapeReplacement(placeholder)) }

    /** The replies whose titles wait (for tests). */
    internal fun waiting(): Int = synchronized(titles) { titles.size }

    /** A new conversation: nothing waits. */
    fun forget() = synchronized(titles) { titles.clear(); dropped.clear() }
}
