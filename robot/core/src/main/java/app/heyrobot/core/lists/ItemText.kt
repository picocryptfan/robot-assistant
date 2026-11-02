// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.memory.MemoryText
import app.heyrobot.core.memory.SecretDetector
import java.util.Locale

// Pure. Words becoming items (lists-plan.md §2.1, §2.3, §3.5, §8.4), in English and Québec French, by code: no model.
//
//   "eggs, bread and 2 litres of milk"  → eggs · bread · milk (2 L)
//   « des pommes, du fromage et deux litres de jus » → pommes · fromage · jus (2 L)
//   "salt and pepper" → one item; "2 pints of milk" → as said (a pint isn't a unit Robot reads)
//
// Cleanup: one line (control and direction characters out, as Files' names), the end's full stop or question mark off, a
// leading article off ("some", "the", "a"; « du », « des », « les », « la », « de la », « un »…). Quantities: a number at
// the start (digits, "one" to "twelve", « un » to « douze », a dozen, half a dozen), then a unit Robot knows or none;
// nothing is converted or added up. The split: commas, then "and" or « et » when each side is three words or fewer once
// its quantity and article are off, and the pair isn't a known compound. A run with no separators ("eggs oat milk bread")
// stays one item here; LS4's model one-shot may split it. Secrets (SecretDetector: a PIN, a card, a password) are refused,
// never added: "Robot keeps those in Memory, not on a list" (§8.4). Every word set below is words Robot hears.

/** An item to add: its words and quantity, as [ItemText] read them. */
data class NewItem(val text: String, val qty: String? = null) {
    override fun toString(): String = "NewItem(${text.length} chars, qty=${qty != null})" // l10n: not text (never an item's words in a log)
}

/** Why words can't become items. */
enum class ItemProblem {
    /** Nothing left once cleaned. */
    EMPTY,
    /** An item over 120 characters, a quantity over 16, or a list name over 40. */
    TOO_LONG,
    /** More than 20 items in one add. */
    TOO_MANY,
    /** It looks like a code, a card or a password. */
    SECRET,
}

/** What reading the words of an add gave. */
sealed class ItemsRead {
    data class Ok(val items: List<NewItem>) : ItemsRead()
    data class No(val why: ItemProblem) : ItemsRead()
}

object ItemText {
    /** [s] as one clean line: control and direction characters out, spaces collapsed, the end's punctuation off. */
    fun clean(s: String): String = FileIndexCodec.cleanName(s).trimEnd { it in END || it.isWhitespace() }

    /**
     * The items in [words] (the item part of what you said or typed: "eggs, bread and 2 litres of milk"), or why there
     * are none: nothing at all, more than [ListsLimits.MAX_ADD], one too long, or a secret anywhere in them.
     */
    fun read(words: String): ItemsRead {
        val all = clean(words)
        if (all.isEmpty()) return ItemsRead.No(ItemProblem.EMPTY)
        if (SecretDetector.isSecret(all)) return ItemsRead.No(ItemProblem.SECRET)
        val pieces = split(all)
        if (pieces.size > ListsLimits.MAX_ADD) return ItemsRead.No(ItemProblem.TOO_MANY)
        val items = pieces.map(::item).filter { it.text.isNotEmpty() }
        if (items.isEmpty()) return ItemsRead.No(ItemProblem.EMPTY)
        for (i in items) check(i)?.let { return ItemsRead.No(it) }
        return ItemsRead.Ok(items)
    }

    /** What's wrong with [item] as it would be stored, or null: one plain line within its caps, and no secret. */
    fun check(item: NewItem): ItemProblem? {
        if (item.text.isBlank()) return ItemProblem.EMPTY
        if (item.text.length > ListsLimits.MAX_TEXT || (item.qty?.length ?: 0) > ListsLimits.MAX_QTY) return ItemProblem.TOO_LONG
        if (!ListsText.line(item.text, ListsLimits.MAX_TEXT) || (item.qty != null && !ListsText.line(item.qty, ListsLimits.MAX_QTY))) return ItemProblem.EMPTY
        if (SecretDetector.isSecret(item.text) || SecretDetector.isSecret(listOfNotNull(item.qty, item.text).joinToString(" "))) return ItemProblem.SECRET
        return null
    }

    /**
     * [text] as a list shows it: a capital first letter ("oat milk" → "Oat milk", « œufs » → « Œufs »), except when the
     * first word has a capital of its own further in ("iPhone cable", "eBay return").
     */
    fun shown(text: String): String {
        val first = text.substringBefore(' ')
        if (first.drop(1).any { it.isUpperCase() }) return text
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    /** Whether two items are the same item: case, accents, spaces and punctuation aside. */
    fun same(a: String, b: String): Boolean = ListsText.key(a) == ListsText.key(b)

    // The split.

    /** [words] cut into the pieces that are items (§3.5), each as said. */
    fun split(words: String): List<String> {
        val out = ArrayList<String>()
        for (part in clean(words).split(SEPARATORS)) {
            val w = part.trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
            // "eggs, bread, and milk": the list's own "and" after the last comma.
            if (w.size > 1 && fold(w[0]) in AND) w.removeAt(0)
            if (w.isEmpty()) continue
            out += byAnd(w)
        }
        return out
    }

    /** One comma's piece cut at "and" or « et » where both sides are short and the pair isn't a compound. */
    private fun byAnd(w: List<String>): List<String> {
        val sides = ArrayList<List<String>>()
        val joins = ArrayList<String>()
        var cur = ArrayList<String>()
        for (x in w) {
            if (fold(x) in AND && cur.isNotEmpty()) {
                sides += cur
                joins += x
                cur = ArrayList()
            } else {
                cur += x
            }
        }
        if (cur.isEmpty()) return listOf(w.joinToString(" "))  // "eggs and": nothing after it
        sides += cur
        val out = ArrayList<String>()
        var acc = sides[0].joinToString(" ")
        for (k in 1 until sides.size) {
            val right = sides[k].joinToString(" ")
            val l = item(acc).text
            val r = item(right).text
            if (words(l) in 1..3 && words(r) in 1..3 && "${fold(l)} ${fold(joins[k - 1])} ${fold(r)}" !in COMPOUNDS) {
                out += acc
                acc = right
            } else {
                acc = "$acc ${joins[k - 1]} $right"
            }
        }
        out += acc
        return out
    }

    private fun words(s: String): Int = s.split(' ').count { it.isNotEmpty() }

    // One item.

    /** One piece as an item: its leading article off and its quantity read. When nothing would be left, it is kept as said. */
    fun item(piece: String): NewItem {
        val said = clean(piece)
        val w = said.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (w.isEmpty()) return NewItem("")
        var i = article(w, 0)
        val q = quantity(w, i)
        if (q === AS_SAID) return NewItem(said)
        val rest: MutableList<String>
        if (q != null) {
            rest = w.subList(q.next, w.size).toMutableList()
            if (q.of && rest.isNotEmpty()) {
                // "2 litres of milk", « deux litres de jus », « une douzaine d'œufs »
                val f = fold(rest[0])
                if (f in OF) rest.removeAt(0) else elided(rest[0], "d")?.let { rest[0] = it }
            }
        } else {
            if (i < w.size && fold(w[i]) in ONE_ARTICLE && i + 1 < w.size) i++
            rest = w.subList(i, w.size).toMutableList()
        }
        val text = rest.joinToString(" ")
        if (text.isEmpty() || (q != null && q.qty.length > ListsLimits.MAX_QTY)) return NewItem(said)
        return NewItem(text, q?.qty)
    }

    /**
     * The index after a leading article that never means a number: "some", "the"; « du », « des », « les », « la », « de la »;
     * an elided « l' » or « de l' » is taken off its word (« l'huile » → « huile »). Never leaves nothing.
     */
    private fun article(w: MutableList<String>, at: Int): Int {
        if (at + 1 >= w.size) return elidedL(w, at)
        val f = fold(w[at])
        // LS4: compared with its accents, so « thé vert » keeps its « thé » (folded, it is "the").
        if (f in ARTICLES && w[at].lowercase(Locale.ROOT) == f) return at + 1
        if (f == "de") {
            if (fold(w[at + 1]) == "la" && at + 2 < w.size) return at + 2
            if (elided(w[at + 1], "l") != null) return elidedL(w, at + 1)
        }
        return elidedL(w, at)
    }

    private fun elidedL(w: MutableList<String>, at: Int): Int {
        elided(w[at], "l")?.let { w[at] = it }
        return at
    }

    /** [word] without its elided [article] ("d'œufs" → "œufs", "l'eau" → "eau"), or null when it doesn't start with it. */
    private fun elided(word: String, article: String): String? {
        if (word.length <= article.length + 1) return null
        val head = word.substring(0, article.length + 1)
        if (!head.equals("$article'", ignoreCase = true) && !head.equals("$article’", ignoreCase = true)) return null
        return word.substring(article.length + 1)
    }

    private class Q(val qty: String, val next: Int, val of: Boolean)

    /** "Two pints of milk": a number, a word that isn't a unit, then "of": kept as said. */
    private val AS_SAID = Q("", 0, false)

    /** The quantity at [at] in [w], null when there's none, [AS_SAID] when the words must stay as said. */
    private fun quantity(w: List<String>, at: Int): Q? {
        if (at >= w.size) return null
        val f = fold(w[at])
        // "2l", "500g", "1.5kg"
        GLUED.matchEntire(f)?.let { m ->
            val unit = METRIC[m.groupValues[2]] ?: return null
            return Q("${digits(w[at], m.groupValues[1].length)} $unit", at + 1, of = true)
        }
        // "half a dozen", « une demi-douzaine »
        if (f == "half" && at + 2 < w.size && fold(w[at + 1]) in setOf("a", "an") && fold(w[at + 2]) in DOZEN) return Q("6", at + 3, of = true)
        if (f in HALF_DOZEN) return Q("6", at + 1, of = true)
        if (f in ONE_ARTICLE && at + 1 < w.size && fold(w[at + 1]) in HALF_DOZEN) return Q("6", at + 2, of = true)
        val article = f in ONE_ARTICLE
        val n: String = when {
            NUMBER.matches(f) -> w[at]
            f in WORDS -> WORDS.getValue(f).toString()
            article -> "1"
            else -> return null
        }
        val next = at + 1
        if (next >= w.size) return null  // "2" alone is an item, not a quantity
        val g = fold(w[next])
        if (g in DOZEN) {
            val count = n.toIntOrNull() ?: return null
            return Q((count * 12).toString(), next + 1, of = true)
        }
        unit(w, next)?.let { return Q("$n $it", next + 1, of = true) }
        if (article) return null  // "an apple", « une pomme »: an article, not a count
        // A number, a word, then "of" (« de »): only a unit counts, so the words stay as said.
        if (next + 1 < w.size && (fold(w[next + 1]) in OF || elided(w[next + 1], "d") != null)) return AS_SAID
        return Q(n, next, of = false)
    }

    /** The unit at [at] as a quantity shows it ("litres" → "L"; "boxes" as said), or null when it isn't one. */
    private fun unit(w: List<String>, at: Int): String? {
        val f = fold(w[at])
        if (f in POUND_FR) {
            // « livre » is a pound only before « de »: « deux livres de beurre », not « deux livres » (books).
            val after = w.getOrNull(at + 1) ?: return null
            return if (fold(after) in OF || elided(after, "d") != null) "lb" else null
        }
        METRIC[f]?.let { return it }
        return if (f in COUNT_UNITS) w[at].lowercase(Locale.ROOT) else null
    }

    /** The first [n] characters of [said] (the digits as written: "1.5", « 1,5 »). */
    private fun digits(said: String, n: Int): String = said.substring(0, n)

    private fun fold(s: String): String = MemoryText.fold(s)

    private const val END = ".!?;:,…"  // l10n: not text (punctuation taken off an item's end)

    /** Commas and semicolons, but not a decimal comma (« 1,5 kg »). */
    private val SEPARATORS = Regex("(?<![0-9]),|,(?![0-9])|;")
    private val NUMBER = Regex("[0-9]{1,6}(?:[.,][0-9]{1,3})?")
    private val GLUED = Regex("([0-9]{1,6}(?:[.,][0-9]{1,3})?)(kg|g|lb|lbs|l|ml)")

    private val AND = setOf("and", "et")
    private val OF = setOf("of", "de", "d'")
    private val ARTICLES = setOf("some", "any", "the", "du", "des", "les", "le", "la")
    private val ONE_ARTICLE = setOf("a", "an", "un", "une")
    private val DOZEN = setOf("dozen", "dozens", "douzaine", "douzaines")
    private val HALF_DOZEN = setOf("demi-douzaine")
    private val POUND_FR = setOf("livre", "livres")

    private val WORDS: Map<String, Int> = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12,
        "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10,
        "onze" to 11, "douze" to 12,
    )

    /** Metric units and the pound, as a quantity shows them. */
    private val METRIC: Map<String, String> = mapOf(
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "kilos" to "kg", "kilogram" to "kg", "kilograms" to "kg", "kilogramme" to "kg", "kilogrammes" to "kg",
        "g" to "g", "gram" to "g", "grams" to "g", "gramme" to "g", "grammes" to "g",
        "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb",
        "l" to "L", "litre" to "L", "litres" to "L", "liter" to "L", "liters" to "L",
        "ml" to "mL", "millilitre" to "mL", "millilitres" to "mL", "milliliter" to "mL", "milliliters" to "mL",
    )

    /** Units counted in things, kept as said ("2 boxes", « 2 boîtes »). */
    private val COUNT_UNITS = setOf(
        "pack", "packs", "box", "boxes", "bag", "bags", "can", "cans", "bottle", "bottles", "jar", "jars",
        "paquet", "paquets", "boite", "boites", "sac", "sacs", "canne", "cannes", "bouteille", "bouteilles", "pot", "pots",
    )

    /** Pairs that are one item ("salt and pepper"), folded. */
    private val COMPOUNDS = setOf(
        "salt and pepper", "pepper and salt", "mac and cheese", "macaroni and cheese", "fish and chips", "peanut butter and jam",
        "peanut butter and jelly", "sel et poivre", "poivre et sel",
    )
}
