// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.i18n.Lang

// Pure. Which items your words name (lists-plan.md §3.2, §3.6; LS3), for a tick, a put back or a check: "take eggs off"
// finds Eggs; "the eggs", « les œufs » too (ItemText takes the article off); "egg" finds Eggs (a plural's s or x aside);
// "milk" finds Oat milk when it's the only milk. Two that match as well ("Oat milk or milk?") are both given, and the reply
// asks which. In that order: the same item first, then the same but for a plural, then an item your words are part of.
//
// LS12 (LT3 on the Pixel 8, where "is milk on my list" counted Milk thistle): a word you say alone is part of an item only
// as its main word, the last in English ("oat milk" is milk; "milk thistle", "milk chocolate" and "bread flour" aren't milk
// or bread) and the first in French (« lait d'avoine » is « lait »; « café au lait » isn't), a number or size after it
// aside ("Milk 2%"). Two words or more are part of any item that holds them in a row ("call mom" finds "call mom about
// Sunday"). Which language: the one you said the words in.
//
// [ListsSeen] is what the panel keeps of your lists between turns, so the router (on the main thread, never opening the
// vault) can tell a bare "take eggs off" from "take my shoes off": each list's open and ticked items as folded words. It is
// item content in RAM: the panel drops it when the panel hides, a new conversation starts or the phone locks.

object ListsMatch {
    /** [s] folded into words, each without a plural's s or x ("Œufs" → "oeuf", "eggs" → "egg"). */
    fun words(s: String): List<String> = ListsText.fold(s).split(' ').filter { it.isNotEmpty() }.map(::one)

    private fun one(w: String): String = if (w.length > 3 && (w.endsWith('s') || w.endsWith('x')) && !w.endsWith("ss")) w.dropLast(1) else w

    /**
     * The items of [items] that [said] (one item's words, as said: "the eggs") names, at the first level that names any;
     * [lang]: the language you said them in (which word of an item is its main word).
     */
    fun find(items: List<ListItem>, said: String, lang: Lang = Lang.EN): List<ListItem> {
        val text = ItemText.item(said).text
        if (text.isEmpty()) return emptyList()
        exact(items, text).takeIf { it.isNotEmpty() }?.let { return it }
        val w = words(text)
        return level(items.map { it to words(it.text) }, w, lang)
    }

    private fun exact(items: List<ListItem>, text: String): List<ListItem> = items.filter { ItemText.same(it.text, text) }

    /** The same words but for plurals; else the items [w] is part of ([part]). */
    private fun <T> level(items: List<Pair<T, List<String>>>, w: List<String>, lang: Lang): List<T> {
        if (w.isEmpty()) return emptyList()
        items.filter { it.second == w }.takeIf { it.isNotEmpty() }?.let { m -> return m.map { it.first } }
        return items.filter { (_, iw) -> part(iw, w, lang) }.map { it.first }
    }

    /**
     * LS12: whether [w] (your words, folded) is part of the item [item] as a person would hear it. One word: the item's main
     * word, its last in English and its first in French, a number or size after it aside. Two or more: anywhere, in a row.
     */
    internal fun part(item: List<String>, w: List<String>, lang: Lang): Boolean {
        if (w.isEmpty() || w.size > item.size) return false
        if (w.size >= 2) return (0..item.size - w.size).any { i -> item.subList(i, i + w.size) == w }
        if (lang == Lang.FR) return item[0] == w[0]
        val main = item.dropLastWhile { x -> x.any(Char::isDigit) }
        return main.isNotEmpty() && main.last() == w[0]
    }

    /** Whether [said] names at least one of [items] (folded words), as [find] would, in [lang]. */
    internal fun names(items: List<List<String>>, said: String, lang: Lang): Boolean {
        val text = ItemText.item(said).text
        if (text.isEmpty()) return false
        val w = words(text)
        return level(items.map { it to it }, w, lang).isNotEmpty()
    }
}

/**
 * Your lists' items as the panel last saw them: per list (yours only), the folded words of its open and ticked items, and
 * which list is in focus. Built by [of] from the lists the vault opened; holds no text as said.
 */
class ListsSeen private constructor(private val focus: ListsId?, private val lists: Map<ListsId, Pair<List<List<String>>, List<List<String>>>>) {
    /**
     * Whether [words] (one or more items: "eggs and bread", said in [lang]) name items, open or ticked, on the list in focus,
     * or else all on exactly one of your lists.
     */
    fun has(words: String, lang: Lang = Lang.EN): Boolean {
        val pieces = ItemText.split(words)
        if (pieces.isEmpty()) return false
        fun on(l: Pair<List<List<String>>, List<List<String>>>) = pieces.all { ListsMatch.names(l.first + l.second, it, lang) }
        focus?.let { lists[it] }?.let { if (on(it)) return true }
        return lists.values.count(::on) == 1
    }

    override fun toString(): String = "ListsSeen(${lists.size} lists)" // l10n: not text (never an item's words in a log)

    companion object {
        val NONE = ListsSeen(null, emptyMap())

        fun of(file: ListsFile, focus: ListsId?): ListsSeen = ListsSeen(focus, file.lists.filter { it.yours }.associate { l ->
            l.id to (l.open.map { ListsMatch.words(it.text) } to l.done.map { ListsMatch.words(it.text) })
        })
    }
}
