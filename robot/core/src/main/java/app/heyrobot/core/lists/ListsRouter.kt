// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.memory.MemoryText

// Pure. Robot Lists heard by code (lists-plan.md §3, D5; job LS3): your words about your lists, in English and Québec
// French, without the model. The panel asks it right after Files and before hands-free (PanelController, PanelLists.route,
// D9). It claims a phrase only when the phrase names a list:
//   - the noun "list" or "checklist" (« liste »), as a whole word: never "playlist", "listings" or "list" as a verb ("list
//     my notes", which MemoryCommands hears), and never "this list" or « cette liste » (a screen's);
//   - a default list's usual names (§2.2): shopping list, grocery list, groceries, the store list; to-do list, things to
//     do, task list; « liste d'épicerie », « liste de courses », « liste de choses à faire »…;
//   - one of your lists' own names after to, on, onto, off (« à », « sur », « de »).
// The one exception: a short follow-up about the list in focus (the last list this conversation used): "take eggs off",
// "put bread back", "add bread too", "undo that". A bare tick or put back is claimed only when its words match an item on
// the list in focus or on exactly one of your lists ([ListsSeen]), or, while the items can't be seen (the phone locked),
// when a list is in focus: else the phrase goes on down the chain ("take my shoes off"). « Oublie ça » stays memory's.
//
//   Add        "add oat milk to the shopping list", "put eggs on my grocery list"; « ajoute du lait à ma liste d'épicerie »
//   Read       "what's on my packing list", "read me my to-do list"; « qu'est-ce qu'il y a sur ma liste d'épicerie »
//   Check      "is oat milk on the shopping list", "do I have eggs on my list"; « j'ai-tu des œufs sur ma liste »,
//              « le lait est-tu sur ma liste »
//   Tick       "take eggs off (the shopping list)", "cross off bread"; « enlève les œufs », « ôte les œufs d'la liste »,
//              « raye le pain », « coche … »
//   Put back   "put eggs back on the list"; « remets les œufs sur la liste »
//   Make       "make a camping list", "start a new list called Christmas"; « fais une liste de camping »
//   Clear      "clear the done items on the shopping list", "clear the shopping list" (ticked items only, with Undo)
//   Empty      "empty the shopping list": a card only a tap confirms
//   My lists   "what lists do I have"; « quelles listes j'ai », « j'ai combien de listes »
//   Undo       "undo that", « annule ça »: only right after a list change in this conversation
//
// Never: "remind me to add eggs to the list" (routines' first), "add dentist Tuesday to my calendar", "make a list of
// things to do in Montréal" (an answer, not a list), "share the shopping list with Sam" or "send Sam my packing list" (a
// list sent to a person is Robot to Robot's, 1.5), "add this to my list" or "add the ingredients to my shopping list" (what
// a screen holds: the model's add_to_list, LS4), « mets la table », « ajoute du sel » (no « liste »).
//
// Items are your words, kept as said (ItemText reads them later); nothing here logs them: the commands' toString holds
// codes only.

/** Which list your words named. */
sealed class ListRef {
    /** A default list by its role: "the shopping list", "groceries", « ma liste d'épicerie ». */
    data class Role(val role: ListRole) : ListRef()

    /** A list by a name: "the packing list" (packing), « la liste de bagages » (bagages), your list "Costco". [name]: your words. */
    data class Named(val name: String) : ListRef() {
        override fun toString(): String = "Named" // l10n: not text (never your words in a log)
    }

    /** "The list", "my list", « ma liste »: the list in focus; with none, Shopping for an add or a read, any list for a check or a tick. */
    data object Unnamed : ListRef()

    /**
     * fix-list-it: "it" after a list turn ("take the milk off it", "is milk on it"; « enlève le lait de celle-là », « le lait
     * est-tu dessus »), and "milk is done": the list in focus and no other. With none in focus, or the item not on it, a
     * tick asks which list (never a guess); a check with none in focus looks at every list.
     */
    data object It : ListRef()
}

/** What your words ask of your lists. [lang]: the language they were said in. */
sealed class ListsCommand {
    abstract val lang: Lang

    /** Add [words] (the item part: "eggs, bread and 2 litres of milk") to [list]; [said], your words for the list ("shopping list"). */
    data class Add(val words: String, val list: ListRef, val said: String, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "Add($list, $lang)" // l10n: not text (never your words in a log)
    }

    /** Read [list] aloud. */
    data class Read(val list: ListRef, val said: String, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "Read($list, $lang)" // l10n: not text (never your words in a log)
    }

    /** Whether [words] (one item) is on [list]. */
    data class Check(val words: String, val list: ListRef, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "Check($list, $lang)" // l10n: not text (never your words in a log)
    }

    /** Tick [words] off [list]. Voice never deletes an item (D8, Q4): "remove eggs from the list" ticks too. */
    data class Tick(val words: String, val list: ListRef, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "Tick($list, $lang)" // l10n: not text (never your words in a log)
    }

    /** Put [words] back on [list] (untick, or add when it isn't there). */
    data class PutBack(val words: String, val list: ListRef, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "PutBack($list, $lang)" // l10n: not text (never your words in a log)
    }

    /** Make a list: [role]'s, or one called [name] (your words). */
    data class Make(val name: String?, val role: ListRole?, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "Make($role, $lang)" // l10n: not text (never your words in a log)
    }

    /** Clear [list]'s ticked items (Undo puts them back). */
    data class ClearDone(val list: ListRef, override val lang: Lang) : ListsCommand()

    /** Empty [list], open items too: a card only a tap confirms. */
    data class Empty(val list: ListRef, override val lang: Lang) : ListsCommand()

    /** Which lists you have. */
    data class MyLists(override val lang: Lang) : ListsCommand()

    /** Undo the last list change of this conversation. */
    data class Undo(override val lang: Lang) : ListsCommand()

    /** recipe-list (core RecipeList): a recipe's ingredients for [list] (your words); [dish] null with a shared [recipe]. */
    data class Recipe(val dish: String?, val list: String, val drops: List<String>, override val lang: Lang, val recipe: String? = null) : ListsCommand() {
        override fun toString(): String = "Recipe(dish=${dish != null}, drops=${drops.size}, recipe=${recipe != null}, $lang)" // l10n: not text (never your words in a log)
    }

    /** recipe-list: untick [words] on the open recipe card ("not the oil"). */
    data class Drop(val words: List<String>, override val lang: Lang) : ListsCommand() {
        override fun toString(): String = "Drop(${words.size}, $lang)" // l10n: not text (never your words in a log)
    }
}

/**
 * What the router knows besides your words. [names]: your lists' own names (not a role list's), heard after to, on, onto,
 * off without the word "list"; [focus]: a list is in focus in this conversation; [undo]: a list change of this
 * conversation can be undone; [lastTurn]: the turn just before this one was Lists' (only then is "it" a list:
 * list-it-fix, the review found a stale "it" ticking an item on a list you'd moved on from); [seen]: your lists' items as the panel last saw them, to tell a bare "take eggs off" from
 * "take my shoes off" (null: they can't be seen now, the phone being locked).
 */
class ListsHeard(
    val names: List<String> = emptyList(),
    val focus: Boolean = false,
    val undo: Boolean = false,
    val seen: ListsSeen? = null,
    val lastTurn: Boolean = false,
)

object ListsRouter {
    /** Your words as said (end punctuation off, plain apostrophes), each with its folded form: matched folded, taken as said. */
    private class Said(val words: List<String>) {
        val folded: List<String> = words.map(MemoryText::fold)
        val text: String = folded.joinToString(" ")
    }

    /** What [text] asks of your lists, or null when it isn't about them. */
    fun route(text: String, heard: ListsHeard = ListsHeard()): ListsCommand? {
        val s = said(text) ?: return null
        return english(s, heard) ?: french(s, heard)
    }

    /**
     * LS4: the list add_to_list's `list` names (the model's words: "Shopping", "the grocery list", « liste d'épicerie »,
     * "packing"), read as your own words for a list are; null or blank, Shopping (lists-plan.md §8.2). A name that is no
     * default list's is a list by that name ([ListRef.Named]): the panel finds yours, or its card says it makes one.
     */
    fun named(name: String?): ListRef {
        val s = name?.let(::said) ?: return ListRef.Role(ListRole.SHOPPING)
        val heard = ListsHeard()
        (refEn(s.folded, s.words, heard) ?: refFr(s.folded, s.words, heard, anyName = true))?.let { return it.ref }
        val at = if (s.folded.size > 1 && (s.folded[0] in DET_EN || s.folded[0] in DET_FR)) 1 else 0
        val f = s.folded.drop(at).joinToString(" ")
        val o = s.words.drop(at).joinToString(" ")
        (ROLE_EN[f] ?: ROLE_FR[f] ?: ListRole.entries.firstOrNull { r -> r.names.any { ListsText.key(it) == ListsText.key(f) } })?.let { return ListRef.Role(it) }
        return if (ListsText.key(o).isNotEmpty()) ListRef.Named(o) else ListRef.Role(ListRole.SHOPPING)
    }

    // ------------------------------------------------------------------ words

    private val LEAD = listOf("hey robot", "robot", "ok robot", "okay robot", "please", "can you", "could you", "would you", "will you", "ok", "okay", // l10n: not text (words Robot hears)
        "s'il te plait", "stp", "peux-tu", "peux tu", "est-ce que tu peux", "tu peux", "pourrais-tu", "veux-tu") // l10n: not text (words Robot hears)
    private val TAIL = listOf("please", "for me", "thanks", "thank you", "s'il te plait", "stp", "pour moi", "merci") // l10n: not text (words Robot hears)

    /**
     * Review-c Low 3, for SkillRouter: [name] (said after "add to") is a list to Lists' resolver, with [names] your lists'
     * (none while Lists is off, so never then): one of yours, or Shopping's or To-do's own name said bare ("groceries").
     */
    fun isList(name: String, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val s = said(name) ?: return false
        val heard = ListsHeard(names = names)
        return (refEn(s.folded, s.words, heard) ?: refFr(s.folded, s.words, heard)) != null
    }

    /** [text] as words: trimmed, the end's punctuation and the fillers around it off. Null when nothing is left. */
    private fun said(text: String): Said? {
        var t = text.replace('’', '\'').replace('ʼ', '\'').replace('‘', '\'').trim().trimEnd('.', '!', '?', '…', ' ').trim() // l10n: not text (punctuation)
        if (t.isEmpty() || t.length > 600) return null
        var w = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // "Hey Robot, add …": a comma after the filler goes with it.
        fun fold(i: Int) = MemoryText.fold(w[i]).trimEnd(',')
        var changed = true
        while (changed && w.isNotEmpty()) {
            changed = false
            for (lead in LEAD) {
                val n = lead.count { it == ' ' } + 1
                if (w.size > n && (0 until n).joinToString(" ") { fold(it) } == lead) {
                    w = w.drop(n).toMutableList()
                    changed = true
                    break
                }
            }
        }
        changed = true
        while (changed && w.size > 1) {
            changed = false
            for (tail in TAIL) {
                val n = tail.count { it == ' ' } + 1
                if (w.size > n && (w.size - n until w.size).joinToString(" ") { MemoryText.fold(w[it]).trimStart(',') } == tail) {
                    w = w.dropLast(n).toMutableList()
                    w[w.size - 1] = w.last().trimEnd(',')
                    changed = true
                    break
                }
            }
        }
        t = w.joinToString(" ")
        return if (t.isBlank()) null else Said(w)
    }

    // ------------------------------------------------------------------ which list

    /** A list your words named, [said] your words for it without its article ("shopping list", « liste d'épicerie »). */
    private class Ref(val ref: ListRef, val said: String)

    private val ROLE_EN: Map<String, ListRole> = mapOf(
        "shopping" to ListRole.SHOPPING, "grocery" to ListRole.SHOPPING, "groceries" to ListRole.SHOPPING, "store" to ListRole.SHOPPING, // l10n: not text (words Robot hears)
        "the store" to ListRole.SHOPPING, "grocery store" to ListRole.SHOPPING, "shopping and grocery" to ListRole.SHOPPING, // l10n: not text (words Robot hears)
        // everyday-1.2.2-a bug 2: more of Shopping's usual names.
        "grocery shopping" to ListRole.SHOPPING, "supermarket" to ListRole.SHOPPING, // l10n: not text (words Robot hears)
        "to-do" to ListRole.TODO, "to do" to ListRole.TODO, "todo" to ListRole.TODO, "to-dos" to ListRole.TODO, "task" to ListRole.TODO, // l10n: not text (words Robot hears)
        "tasks" to ListRole.TODO, "things to do" to ListRole.TODO, // l10n: not text (words Robot hears)
    )
    /** A default list's names said without "list" (folded). */
    private val ROLE_BARE_EN: Map<String, ListRole> = mapOf("groceries" to ListRole.SHOPPING, "things to do" to ListRole.TODO) // l10n: not text (words Robot hears)
    private val ROLE_FR: Map<String, ListRole> = mapOf(
        "epicerie" to ListRole.SHOPPING, "l'epicerie" to ListRole.SHOPPING, "courses" to ListRole.SHOPPING, "achats" to ListRole.SHOPPING, // l10n: not text (words Robot hears)
        "commissions" to ListRole.SHOPPING, "magasinage" to ListRole.SHOPPING, // l10n: not text (words Robot hears)
        // LS8: « liste de commission », « liste d'épiceries », « liste de choses à acheter ».
        "commission" to ListRole.SHOPPING, "epiceries" to ListRole.SHOPPING, "choses a acheter" to ListRole.SHOPPING, "trucs a acheter" to ListRole.SHOPPING, // l10n: not text (words Robot hears)
        "choses a faire" to ListRole.TODO, "taches" to ListRole.TODO, "faire" to ListRole.TODO, "trucs a faire" to ListRole.TODO, // l10n: not text (words Robot hears)
        // LS8: « liste d'affaires à faire », « liste de tâches à faire ».
        "affaires a faire" to ListRole.TODO, "taches a faire" to ListRole.TODO, // l10n: not text (words Robot hears)
    )
    private val DET_EN = setOf("the", "my", "our", "a", "an") // l10n: not text (words Robot hears)
    private val DET_FR = setOf("la", "ma", "notre", "une", "ta") // l10n: not text (words Robot hears)
    /** Never your list: a screen's ("what's on this list"). */
    private val NOT_YOURS = setOf("this", "that", "these", "those", "your", "his", "her", "their", "cette", "ces", "sa", "leur") // l10n: not text (words Robot hears)
    /** Words a list's name never holds when heard (they join an item to the list, or are the sentence's own). */
    private val NOT_IN_NAME = setOf("to", "on", "onto", "in", "into", "of", "for", "from", "off", "with", "at", "and", "this", "that", "it", "list", // l10n: not text (words Robot hears)
        "a", "sur", "dans", "de", "des", "du", "pour", "avec", "et", "liste", "ca", "play") // l10n: not text (words Robot hears)
    private const val MAX_NAME_WORDS = 4

    /** The list [w] (folded words, [o] as said) names, all of them; null when they don't name one. */
    private fun refEn(w: List<String>, o: List<String>, heard: ListsHeard): Ref? {
        if (w.isEmpty()) return null
        if (w[0] in NOT_YOURS) return null
        val at = if (w.size > 1 && w[0] in DET_EN) 1 else 0
        val f = w.subList(at, w.size)
        val s = o.subList(at, o.size)
        if (f.last() == "list" || f.last() == "checklist") {
            val name = f.dropLast(1)
            if (name.isEmpty()) return Ref(ListRef.Unnamed, s.joinToString(" "))
            ROLE_EN[name.joinToString(" ")]?.let { return Ref(ListRef.Role(it), s.joinToString(" ")) }
            if (name.size > MAX_NAME_WORDS || name.any { it in NOT_IN_NAME || it in DET_EN || it in NOT_YOURS }) return null
            return Ref(ListRef.Named(s.dropLast(1).joinToString(" ")), s.joinToString(" "))
        }
        // Review Low 3: your own list of that name before Shopping's or To-do's built-in one ("Groceries" beside Shopping).
        yours(f, s, heard)?.let { return it }
        return ROLE_BARE_EN[f.joinToString(" ")]?.let { Ref(ListRef.Role(it), s.joinToString(" ")) }
    }

    /** One of your lists' own names, said without "list" ("Costco"). */
    private fun yours(f: List<String>, s: List<String>, heard: ListsHeard): Ref? {
        if (f.isEmpty() || f.size > MAX_NAME_WORDS || heard.names.isEmpty()) return null
        val k = ListsText.key(f.joinToString(" "))
        if (k.isEmpty()) return null
        val name = heard.names.firstOrNull { ListsText.key(it) == k } ?: return null
        return Ref(ListRef.Named(s.joinToString(" ")), name)
    }

    /**
     * As [refEn] in French. LS10 (L2): « la liste des gagnants » and « la liste des finalistes » ask about a list of things in
     * the world, so « liste des X » names only a default list (« des courses ») or one of your lists ([ListsHeard.names]:
     * « la liste des invités » with a list called Invités); « liste de lecture » is a playlist, a list only when you have one
     * called Lecture. [anyName]: the model's add_to_list names a list it means, whatever its words (§8.2).
     */
    private fun refFr(w: List<String>, o: List<String>, heard: ListsHeard, anyName: Boolean = false): Ref? {
        if (w.isEmpty()) return null
        if (w[0] in NOT_YOURS) return null
        val at = if (w.size > 1 && w[0] in DET_FR) 1 else 0
        val f = w.subList(at, w.size)
        val s = o.subList(at, o.size)
        if (f[0] == "liste") {
            if (f.size == 1) return Ref(ListRef.Unnamed, s.joinToString(" "))
            // « liste de bagages », « liste d'épicerie », « liste de l'épicerie », « liste des courses », « liste à faire »
            var rest = f.drop(1).toMutableList()
            var restSaid = s.drop(1).toMutableList()
            val des = rest[0] == "des"
            when {
                rest[0] == "de" && rest.size > 2 && rest[1] == "la" -> { rest = rest.drop(2).toMutableList(); restSaid = restSaid.drop(2).toMutableList() }
                rest[0] in setOf("de", "des", "du", "pour") && rest.size > 1 -> { rest = rest.drop(1).toMutableList(); restSaid = restSaid.drop(1).toMutableList() } // l10n: not text (words Robot hears)
                rest[0].startsWith("d'") && rest[0].length > 2 -> { rest[0] = rest[0].substring(2); restSaid[0] = restSaid[0].substring(2) }
                rest[0] == "a" && rest.size > 1 -> {}
                else -> return null
            }
            if (rest.isNotEmpty() && rest[0].startsWith("l'") && rest[0].length > 2) {
                rest[0] = rest[0].substring(2)
                restSaid[0] = restSaid[0].substring(2)
            }
            val key = rest.joinToString(" ")
            ROLE_FR[key]?.let { return Ref(ListRef.Role(it), s.joinToString(" ")) }
            if (rest.firstOrNull() == "a") ROLE_FR[rest.drop(1).joinToString(" ")]?.let { return Ref(ListRef.Role(it), s.joinToString(" ")) }
            if (rest.isEmpty() || rest.size > MAX_NAME_WORDS || rest.any { it in NOT_IN_NAME || it in DET_FR || it in NOT_YOURS }) return null
            if (!anyName && (des || key == "lecture") && heard.names.none { ListsText.key(it) == ListsText.key(restSaid.joinToString(" ")) }) return null
            return Ref(ListRef.Named(restSaid.joinToString(" ")), s.joinToString(" "))
        }
        return yours(f, s, heard)
    }

    /**
     * The rightmost place in [s] (folded words) after [from] where one of [preps] starts a list's words that name a list:
     * the item's words before it, and the list. "add go to the gym to my to-do list" gives "go to the gym" and the to-do
     * list; "add call the bank to my to-do list" gives "call the bank".
     */
    private fun split(s: Said, from: Int, preps: Set<String>, fr: Boolean, heard: ListsHeard, takes: (List<String>, Ref) -> Boolean = { _, _ -> true }): Pair<String, Ref>? {
        val w = s.folded
        for (i in w.size - 2 downTo from + 1) {
            if (w[i] !in preps) continue
            val r = (if (fr) refFr(w.subList(i + 1, w.size), s.words.subList(i + 1, w.size), heard)
                else refEn(w.subList(i + 1, w.size), s.words.subList(i + 1, w.size), heard)) ?: continue
            if (!takes(w.subList(i + 1, w.size), r)) continue
            return s.words.subList(from, i).joinToString(" ") to r
        }
        return null
    }

    /** The whole of [s] from word [from] as a list, or null. */
    private fun whole(s: Said, from: Int, fr: Boolean, heard: ListsHeard): Ref? {
        if (from >= s.words.size) return null
        val w = s.folded.subList(from, s.folded.size)
        val o = s.words.subList(from, s.words.size)
        return if (fr) refFr(w, o, heard) else refEn(w, o, heard)
    }

    private val MINE = setOf("my", "our", "ma", "mes", "notre") // l10n: not text (words Robot hears)

    /**
     * Whether a question's list words [w] mean a list of yours, not one of the world's ("what's on the Billboard list", "is
     * there tax on groceries"). A name counts when you have that list or said "my" or "our"; "groceries" alone only with them.
     */
    private fun yoursAsked(w: List<String>, r: Ref, heard: ListsHeard): Boolean = when (val ref = r.ref) {
        is ListRef.Named -> w[0] in MINE || (ListsText.key(ref.name)).let { k ->
            heard.names.any { ListsText.key(it) == k } || ListRole.entries.any { role -> role.names.any { ListsText.key(it) == k } }
        }
        is ListRef.Role -> w[0] in MINE || w.any { it == "list" || it == "checklist" || it == "liste" }
        ListRef.Unnamed, ListRef.It -> true
    }

    /** The whole of [s] from word [from] as a list of yours, for a question. */
    private fun wholeAsked(s: Said, from: Int, fr: Boolean, heard: ListsHeard): Ref? =
        whole(s, from, fr, heard)?.takeIf { yoursAsked(s.folded.subList(from, s.folded.size), it, heard) }

    private val ANYTHING = setOf("anything", "something", "any items", "any item", "quelque chose") // l10n: not text (words Robot hears)

    /** A question about no item in particular: "is there anything on my list" asks what's on it. */
    private fun asksAnything(item: String): Boolean = MemoryText.fold(item).trim() in ANYTHING

    /** How many words [prefix] (folded words joined by spaces) takes at the start of [s], or -1 when it isn't there. */
    private fun starts(s: Said, prefix: String): Int {
        val p = prefix.split(' ')
        if (s.folded.size < p.size) return -1
        for (i in p.indices) if (s.folded[i] != p[i]) return -1
        return p.size
    }

    private fun startsAny(s: Said, prefixes: List<String>): Int = prefixes.sortedByDescending { it.length }.firstNotNullOfOrNull { p -> starts(s, p).takeIf { it >= 0 } } ?: -1

    // ------------------------------------------------------------------ items

    /** Item words that point at something else (a screen, a page, the conversation): the model's (add_to_list, LS4), never added as said. */
    /** LS10 (L2): "me", "us", "him", "her" and "you" too: "put me on the guest list" is about a person, never an item "Me". */
    private val POINTING = setOf("this", "that", "these", "those", "it", "them", "everything", "all", "all of it", "all of them", "the rest", "whatever", // l10n: not text (words Robot hears)
        "me", "us", "him", "her", "you", // l10n: not text (words Robot hears)
        "the items", "these items", "those items", "the things", "ca", "ceci", "cela", "tout", "tout ca", "le reste", "tout le reste", "les elements") // l10n: not text (words Robot hears)
    private val POINTING_START = setOf("this", "that", "these", "those", "what", "whatever", "everything", "ce", "ces", "cet", "cette", "quoi") // l10n: not text (words Robot hears)
    private val POINTING_WORDS = setOf("ingredients", "ingredient") // l10n: not text (words Robot hears)
    private val POINTING_TAIL = listOf("on this page", "on the page", "on the screen", "on this screen", "on my screen", "from this recipe", "from the recipe", // l10n: not text (words Robot hears)
        "in this recipe", "in the recipe", "sur cette page", "sur la page", "sur l'ecran", "sur mon ecran", "de la recette", "de cette recette", "dans la recette") // l10n: not text (words Robot hears)

    /** Whether [words] (as said) are items of your own, not a pointer to something the panel holds ("this", "the ingredients"). */
    private fun ownItems(words: String): Boolean {
        val f = MemoryText.fold(words).trim().trimEnd(',')
        if (f.isEmpty()) return false
        if (f in POINTING || f.removePrefix("the ").removePrefix("all ") in POINTING) return false // l10n: not text (words Robot hears)
        val ws = f.split(' ').map { it.trim(',') }
        if (ws[0] in POINTING_START || ws[0].startsWith("qu'")) return false
        // « mets pas de lait sur ma liste », « mets plus d'œufs sur ma liste »: not an item.
        if (ws[0] == "pas" || (ws[0] == "plus" && ws.getOrNull(1).let { it == "de" || it == "du" || it == "des" || it?.startsWith("d'") == true })) return false // l10n: not text (words Robot hears)
        if (ws.any { it in POINTING_WORDS }) return false
        if (POINTING_TAIL.any { f.endsWith(it) }) return false
        return true
    }

    // ------------------------------------------------------------------ English

    private val ADD_EN = listOf("add", "put", "stick", "pop", "throw", "jot down", "write down", "put down", "also add", "and add", "please add") // l10n: not text (words Robot hears)
    private val TO_EN = setOf("to", "on", "onto", "in", "into") // l10n: not text (words Robot hears)
    private val READ_ON_EN = listOf("what's on", "whats on", "what is on", "what's in", "whats in", "what is in", "what's left on", "whats left on", // l10n: not text (words Robot hears)
        "what is left on", "what's left in", "what else is on", "what do i have on", "what do i have in", "what have i got on", "what do i need from", // l10n: not text (words Robot hears)
        "what's still on", "whats still on", "what is still on", // l10n: not text (words Robot hears)
        // everyday-1.2.2-a bug 2: how many things a list holds is a read (its reply counts them), never an app search.
        "how many items are on", "how many things are on", "how many items are in", "how many things are in", "how many items on", // l10n: not text (words Robot hears)
        "how many things on", "how many items do i have on", "how many things do i have on", "how many items have i got on", // l10n: not text (words Robot hears)
        "how many items are there on", "how many things are there on", "how much is on", "how much stuff is on") // l10n: not text (words Robot hears)
    private val READ_EN = listOf("read", "read me", "read out", "read back", "read out loud", "tell me", "show", "show me", "go over", "go through", // l10n: not text (words Robot hears)
        "what's", "whats", "what is", "open") // l10n: not text (words Robot hears)
    private val MY_LISTS_EN = setOf("what lists do i have", "which lists do i have", "what are my lists", "what lists have i got", "read me my lists", // l10n: not text (words Robot hears)
        "show me my lists", "what lists have i made", "my lists", "what lists are there", "tell me my lists", "read my lists") // l10n: not text (words Robot hears)
    private val CHECK_EN = listOf("is", "are", "do i have", "have i got", "did i put", "did i add", "have i added", "have i put", "is there", "are there") // l10n: not text (words Robot hears)
    private val TICK_VERB_EN = listOf("take", "cross", "tick", "check", "scratch", "strike", "knock", "mark") // l10n: not text (words Robot hears)
    private val REMOVE_EN = listOf("remove", "delete", "take") // l10n: not text (words Robot hears)
    private val MAKE_EN = listOf("make", "make me", "start", "create", "begin", "set up", "new", "start me", "create me") // l10n: not text (words Robot hears)
    private val DONE_EN = setOf("done", "ticked", "checked", "crossed off", "checked off", "ticked off", "completed", "finished", "bought", // l10n: not text (words Robot hears)
        "crossed-off", "checked-off", "ticked-off", "crossed out", "crossed") // l10n: not text (words Robot hears)
    private val ITEMS_EN = setOf("items", "things", "ones", "stuff", "entries") // l10n: not text (words Robot hears)

    /** fix-124c: a default list by its bare name after "clear the ticked items from" ("shopping", "the to-do"), where "list" is often left out. */
    /**
     * fix-skill-step-voice-lows-b: "add to groceries: milk, eggs", « ajoute à épicerie : lait, œufs »: the list right after
     * the verb's "to" (« à »), a colon, then the items. Taken only when the words before the colon name a list (a default
     * list's words or one of yours); else null and the rest of the router reads them as before.
     */
    private fun colonAdd(s: Said, n: Int, to: Set<String>, fr: Boolean, heard: ListsHeard): ListsCommand? {
        if (n + 2 > s.words.size || s.folded[n] !in to) return null
        // Review Low 4: a colon or a comma (as speech gives it); with neither, the words after "to" start with one of your lists' names.
        val c = (n + 1 until s.words.size).firstOrNull { s.words[it].endsWith(':') || s.words[it].endsWith(',') } ?: return bareAdd(s, n, fr, heard) // l10n: not text (punctuation)
        val name = s.words.subList(n + 1, c + 1).map { it.removeSuffix(":").removeSuffix(",") }.filter { it.isNotEmpty() } // l10n: not text (punctuation)
        val items = s.words.subList(c + 1, s.words.size).joinToString(" ")
        if (name.isEmpty() || name.size > MAX_NAME_WORDS + 2 || items.isBlank() || !ownItems(items)) return null
        val w = name.map(MemoryText::fold)
        val r = (if (fr) refFr(w, name, heard) ?: bareRole(Said(name), 0, DET_FR + setOf("les", "mes"), ROLE_FR) else refEn(w, name, heard)) ?: return bareAdd(s, n, fr, heard) // l10n: not text (words Robot hears)
        return ListsCommand.Add(items, r.ref, r.said, if (fr) Lang.FR else Lang.EN)
    }

    /** "add to Costco paper towels": no punctuation, so only one of your lists' own names (the longest) right after "to". */
    private fun bareAdd(s: Said, n: Int, fr: Boolean, heard: ListsHeard): ListsCommand? {
        for (k in minOf(s.words.size - 1, n + 1 + MAX_NAME_WORDS) downTo n + 2) {
            val r = yours(s.folded.subList(n + 1, k), s.words.subList(n + 1, k), heard) ?: continue
            val items = s.words.subList(k, s.words.size).joinToString(" ")
            return if (items.isBlank() || !ownItems(items)) null else ListsCommand.Add(items, r.ref, r.said, if (fr) Lang.FR else Lang.EN)
        }
        return null
    }

    private fun bareRole(s: Said, from: Int, det: Set<String>, roles: Map<String, ListRole>): Ref? {
        if (from >= s.folded.size) return null
        val at = if (s.folded[from] in det && from + 1 < s.folded.size) from + 1 else from
        return roles[s.folded.subList(at, s.folded.size).joinToString(" ")]?.let { Ref(ListRef.Role(it), s.words.subList(at, s.words.size).joinToString(" ")) }
    }
    private val UNDO_EN = setOf("undo", "undo that", "undo it", "undo the last one", "undo the last change", "take that back", "scratch that", "never mind that") // l10n: not text (words Robot hears)

    private fun english(s: Said, heard: ListsHeard): ListsCommand? {
        val t = s.text
        if (heard.undo && t in UNDO_EN) return ListsCommand.Undo(Lang.EN)
        if (t in MY_LISTS_EN) return ListsCommand.MyLists(Lang.EN)
        val en = Lang.EN

        // Make: "make a camping list", "start a new list called Christmas".
        startsAny(s, MAKE_EN).takeIf { it > 0 }?.let { n -> make(s, n, fr = false)?.let { return it } }

        // Add: "add oat milk to the shopping list"; "add bread too" for the list in focus.
        // "put eggs back on the list" is a put back, "add a back scrubber to the list" an add.
        putBackEn(s, heard)?.let { return it }
        startsAny(s, ADD_EN).takeIf { it > 0 }?.let { n ->
            colonAdd(s, n, TO_EN, fr = false, heard)?.let { return it }
            val f = s.folded
            val trimmed = if (f.size > n + 1 && f.last() in setOf("too", "also")) 1 else if (f.size > n + 2 && f.takeLast(2) == listOf("as", "well")) 2 else 0 // l10n: not text (words Robot hears)
            val core = Said(s.words.dropLast(trimmed))
            split(core, n, TO_EN, fr = false, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.Add(items, r.ref, r.said, en) else null }
            if (trimmed > 0 && heard.focus && core.words.size > n) {
                val items = core.words.subList(n, core.words.size).joinToString(" ")
                if (ownItems(items) && core.folded.subList(n, core.folded.size).none { it in TO_EN }) return ListsCommand.Add(items, ListRef.Unnamed, "list", en) // l10n: not text (the list's word, said back)
            }
        }

        // Read: "what's on the shopping list", "read me my packing list", "what's my to-do list".
        startsAny(s, READ_ON_EN).takeIf { it > 0 }?.let { n -> wholeAsked(s, n, fr = false, heard)?.let { r -> return ListsCommand.Read(r.ref, r.said, en) } }
        startsAny(s, READ_EN).takeIf { it > 0 }?.let { n ->
            if (n < s.folded.size && s.folded[n] in setOf("my", "the", "our")) wholeAsked(s, n, fr = false, heard)?.takeIf { it.ref != ListRef.Unnamed || s.folded[n] != "the" || heard.focus }
                ?.let { r -> if (hasNoun(s, n) || r.ref is ListRef.Role) return ListsCommand.Read(r.ref, r.said, en) }
        }

        // Check: "is oat milk on the shopping list", "do I have eggs on my list"; "is milk on it" after a list turn.
        startsAny(s, CHECK_EN).takeIf { it > 0 }?.let { n ->
            val f = s.folded
            if (heard.lastTurn && f.size > n + 2 && f.last() == "it" && f[f.size - 2] == "on") { // l10n: not text (words Robot hears)
                val item = s.words.subList(n, f.size - 2).joinToString(" ")
                if (ownItems(item) && !asksAnything(item)) return ListsCommand.Check(item, ListRef.It, en)
            }
            split(s, n, setOf("on", "in"), fr = false, heard) { w, r -> yoursAsked(w, r, heard) }?.let { (item, r) ->
                if (ownItems(item) && (hasNoun(s, n) || r.ref is ListRef.Role)) return if (asksAnything(item)) ListsCommand.Read(r.ref, r.said, en) else ListsCommand.Check(item, r.ref, en)
            }
        }

        // Clear and empty.
        clearEn(s, heard)?.let { return it }

        // Tick: "take eggs off the shopping list", "cross off bread", "remove eggs from the list"; "take the milk off it",
        // "milk is done" for the list in focus.
        tickItEn(s, heard)?.let { return it }
        doneIt(s, DONE_IT_EN, heard, en)?.let { return it }
        tickEn(s, heard)?.let { return it }

        return null
    }

    /** Whether the words from [n] hold the noun itself ("list", "checklist", « liste »). */
    private fun hasNoun(s: Said, n: Int): Boolean = s.folded.drop(n).any { it == "list" || it == "checklist" || it == "liste" || it == "groceries" }

    /**
     * fix-list-it: "take the milk off it", "cross milk off of it", "remove milk from it", "cross off milk from it": a tick on
     * the list in focus ([ListRef.It]), claimed only right after a list turn, with a list in focus or the items on one of your
     * lists as seen ("take the lid off it" stays out otherwise).
     */
    private fun tickItEn(s: Said, heard: ListsHeard): ListsCommand? {
        val f = s.folded
        if (f.size < 4 || f.last() != "it" || (f[0] !in TICK_VERB_EN && f[0] !in REMOVE_EN)) return null
        var end = f.size - 1
        // list-it-fix: "off of" is "off"; a bare "of" ("take care of it", "take a note of it") is no tick.
        if (f[end - 1] == "of" && f[end - 2] == "off") end-- // l10n: not text (words Robot hears)
        if (f[end - 1] !in setOf("off", "from")) return null // l10n: not text (words Robot hears)
        end--
        val start = if (f[1] == "off") 2 else 1
        if (end <= start) return null
        val items = s.words.subList(start, end).joinToString(" ")
        if (!ownItems(items) || !takesIt(items, heard, Lang.EN)) return null
        return ListsCommand.Tick(items, ListRef.It, Lang.EN)
    }

    /**
     * "It" is claimed only right after a list turn (list-it-fix: a list in focus from earlier in the conversation isn't "it"),
     * with a list in focus or [items] on one of your lists as the panel saw them.
     */
    private fun takesIt(items: String, heard: ListsHeard, lang: Lang): Boolean =
        heard.lastTurn && (heard.focus || heard.seen?.anywhere(items, lang) == true)

    private val DONE_IT_EN = listOf(listOf("is", "done"), listOf("are", "done"), listOf("'s", "done")) // l10n: not text (words Robot hears)
    private val DONE_IT_FR = listOf(listOf("c'est", "fait"), listOf("est", "fait"), listOf("est", "faite"), listOf("sont", "faits"), listOf("sont", "faites")) // l10n: not text (words Robot hears)

    /**
     * fix-list-it: "milk is done", « le lait, c'est fait »: a tick on the list in focus ([ListRef.It]), claimed only for items on
     * one of your lists as seen, or, while they can't be seen (locked), with a list in focus; and only right after a list turn
     * (list-it-fix): "dinner is done" stays out.
     */
    private fun doneIt(s: Said, ends: List<List<String>>, heard: ListsHeard, lang: Lang): ListsCommand? {
        val f = s.folded.map { it.trimEnd(',') }
        val w = s.words.map { it.trimEnd(',') }.toMutableList()
        // "milk's done": the 's is its own word here.
        if (lang == Lang.EN && f.size >= 2 && f.last() == "done" && f[f.size - 2].endsWith("'s") && f[f.size - 2].length > 2) {
            return doneItems(w.subList(0, w.size - 2) + w[w.size - 2].dropLast(2), heard, lang)
        }
        val end = ends.firstOrNull { e -> f.size > e.size && f.takeLast(e.size) == e } ?: return null
        return doneItems(w.subList(0, w.size - end.size), heard, lang)
    }

    private fun doneItems(words: List<String>, heard: ListsHeard, lang: Lang): ListsCommand? {
        val items = words.joinToString(" ").trim()
        if (items.isEmpty() || !ownItems(items)) return null
        // list-it-fix: and only right after a list turn ("the chicken is done" while cooking isn't a tick).
        if (!heard.lastTurn || !(heard.seen?.anywhere(items, lang) ?: heard.focus)) return null
        return ListsCommand.Tick(items, ListRef.It, lang)
    }

    private fun tickEn(s: Said, heard: ListsHeard): ListsCommand? {
        val f = s.folded
        val en = Lang.EN
        if (f.size < 2) return null
        val v = f[0]
        if (v in TICK_VERB_EN) {
            // "take eggs off (the list)", "cross bread off my grocery list"
            val off = f.indexOf("off")
            if (off > 1) {
                val items = s.words.subList(1, off).joinToString(" ")
                if (!ownItems(items)) return null
                if (off == f.size - 1) return bare(items, done = false, heard, en)?.let { ListsCommand.Tick(items, it, en) }
                var from = off + 1
                if (f[from] in setOf("of", "from", "on")) from++ // l10n: not text (words Robot hears)
                return whole(s, from, fr = false, heard)?.let { ListsCommand.Tick(items, it.ref, en) }
            }
            // "cross off bread (from the shopping list)", "check off sunscreen"
            if (off == 1 && f.size > 2) {
                split(s, 2, setOf("from", "on", "off"), fr = false, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.Tick(items, r.ref, en) else null }
                val items = s.words.subList(2, f.size).joinToString(" ")
                if (!ownItems(items)) return null
                return bare(items, done = false, heard, en)?.let { ListsCommand.Tick(items, it, en) }
            }
            // "mark eggs as done (on the shopping list)"
            if (v == "mark") {
                for (d in DONE_EN.sortedByDescending { it.length }) {
                    val words = d.split(' ')
                    for (k in 2 until f.size) {
                        val asAt = if (f[k] == "as") k + 1 else k
                        if (asAt + words.size > f.size || f.subList(asAt, asAt + words.size) != words) continue
                        val items = s.words.subList(1, k).joinToString(" ")
                        if (!ownItems(items)) return null
                        val end = asAt + words.size
                        if (end == f.size) return bare(items, done = false, heard, en)?.let { ListsCommand.Tick(items, it, en) }
                        if (f[end] in setOf("on", "in")) return whole(s, end + 1, fr = false, heard)?.let { ListsCommand.Tick(items, it.ref, en) }
                        return null
                    }
                }
            }
        }
        // "remove eggs from the shopping list": a tick, never a delete (D8).
        if (v in REMOVE_EN) split(s, 1, setOf("from"), fr = false, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.Tick(items, r.ref, en) else null }
        return null
    }

    private fun putBackEn(s: Said, heard: ListsHeard): ListsCommand? {
        val f = s.folded
        val en = Lang.EN
        if (f.size < 3) {
            if (f.size == 2 && f[0] in setOf("untick", "uncheck")) return bare(s.words[1], done = true, heard, en)?.let { ListsCommand.PutBack(s.words[1], it, en) } // l10n: not text (words Robot hears)
            return null
        }
        if (f[0] in setOf("put", "add")) {
            val back = f.indexOf("back")
            // "put back eggs on the list"; "add back brace to my list" is an add unless your lists hold it.
            if (back == 1 && f.size > 2) {
                split(s, 2, setOf("on", "onto", "to", "in"), fr = false, heard)?.let { (items, r) ->
                    if (!ownItems(items) || (f[0] == "add" && heard.seen?.has(items, en) != true)) return null
                    return ListsCommand.PutBack(items, r.ref, en)
                }
                val items = s.words.subList(2, f.size).joinToString(" ")
                return if (ownItems(items)) bare(items, done = true, heard, en)?.let { ListsCommand.PutBack(items, it, en) } else null
            }
            if (back < 2) return null
            val items = s.words.subList(1, back).joinToString(" ")
            if (!ownItems(items)) return null
            if (back == f.size - 1) return bare(items, done = true, heard, en)?.let { ListsCommand.PutBack(items, it, en) }
            if (f[back + 1] !in setOf("on", "onto", "to", "in")) return null // l10n: not text (words Robot hears)
            return whole(s, back + 2, fr = false, heard)?.let { ListsCommand.PutBack(items, it.ref, en) }
        }
        if (f[0] in setOf("untick", "uncheck")) { // l10n: not text (words Robot hears)
            split(s, 1, setOf("on", "in", "from"), fr = false, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.PutBack(items, r.ref, en) else null }
            val items = s.words.drop(1).joinToString(" ")
            return if (ownItems(items)) bare(items, done = true, heard, en)?.let { ListsCommand.PutBack(items, it, en) } else null
        }
        return null
    }

    private fun clearEn(s: Said, heard: ListsHeard): ListsCommand? {
        val f = s.folded
        val en = Lang.EN
        // "empty the shopping list", "empty out my packing list"
        val e = startsAny(s, listOf("empty", "empty out", "wipe", "clear everything on", "clear everything from", "clear all the items on", "clear all the items from")) // l10n: not text (words Robot hears)
        if (e > 0) return whole(s, e, fr = false, heard)?.takeIf { it.ref != ListRef.Unnamed || heard.focus }?.let { ListsCommand.Empty(it.ref, en) }
        val c = startsAny(s, listOf("clear", "clear out", "clean up", "remove", "delete", "get rid of")) // l10n: not text (words Robot hears)
        if (c <= 0) return null
        var i = c
        if (i < f.size && f[i] == "all") i++
        if (i < f.size && f[i] in setOf("the", "my")) i++
        // "clear the done items (on the shopping list)"
        val d = DONE_EN.sortedByDescending { it.length }.firstOrNull { d -> val w = d.split(' '); i + w.size <= f.size && f.subList(i, i + w.size) == w }
        if (d != null) {
            i += d.split(' ').size
            // fix-124c: "clear the ticked shopping items", "clear the done to-do list items"
            if (f.size - i >= 2 && f.last() in ITEMS_EN) {
                val mid = f.subList(i, f.size - 1).let { if (it.size > 1 && it.last() == "list") it.dropLast(1) else it }
                ROLE_EN[mid.joinToString(" ")]?.let { return ListsCommand.ClearDone(ListRef.Role(it), en) }
            }
            if (i < f.size && f[i] in ITEMS_EN) i++
            if (i == f.size) return if (heard.focus) ListsCommand.ClearDone(ListRef.Unnamed, en) else null
            if (f[i] !in setOf("on", "from", "in", "off", "of")) return null // l10n: not text (words Robot hears)
            return (whole(s, i + 1, fr = false, heard) ?: bareRole(s, i + 1, DET_EN, ROLE_EN))
                ?.takeIf { it.ref != ListRef.Unnamed || heard.focus }?.let { ListsCommand.ClearDone(it.ref, en) }
        }
        // "clear the shopping list": its ticked items only, with Undo.
        if (f[0] != "clear") return null
        return whole(s, c, fr = false, heard)?.takeIf { (it.ref != ListRef.Unnamed || heard.focus) && hasNoun(s, c) }?.let { ListsCommand.ClearDone(it.ref, en) }
    }

    /**
     * A bare tick or put back: the list in focus or the one list that has [items], ticked or not (a tick of a ticked item
     * says so), when it does; else nothing (it goes on down the chain). [done]: which way, for the reply only.
     */
    private fun bare(items: String, done: Boolean, heard: ListsHeard, lang: Lang): ListRef? {
        val seen = heard.seen ?: return if (heard.focus) ListRef.Unnamed else null
        return if (seen.has(items, lang)) ListRef.Unnamed else null
    }

    private fun make(s: Said, n: Int, fr: Boolean): ListsCommand? {
        val f = s.folded
        val o = s.words
        var i = n
        val lang = if (fr) Lang.FR else Lang.EN
        if (!fr) {
            if (i < f.size && f[i] in setOf("a", "an", "me")) i++
            if (i < f.size && f[i] == "new") i++
            if (i >= f.size) return null
            // "a new list called Christmas"
            if (f[i] == "list" || f[i] == "checklist") {
                if (i + 2 >= f.size || f[i + 1] !in setOf("called", "named")) return null // l10n: not text (words Robot hears)
                return named(o.subList(i + 2, o.size), f.subList(i + 2, f.size), lang)
            }
            // "a camping list"
            if (f.last() != "list" && f.last() != "checklist") return null
            val name = f.subList(i, f.size - 1)
            ROLE_EN[name.joinToString(" ")]?.let { return ListsCommand.Make(null, it, lang) }
            return named(o.subList(i, o.size - 1), name, lang)
        }
        if (i < f.size && f[i] in setOf("une", "moi")) i++
        if (i < f.size && f[i] == "une") i++
        if (i < f.size && f[i] in setOf("nouvelle", "autre")) i++
        if (i >= f.size || f[i] != "liste") return null
        i++
        if (i >= f.size) return null
        // « une liste des films à voir »: what a list would hold, an answer (the model's).
        if (f[i] == "des") return null
        var name = f.subList(i, f.size)
        var nameSaid = o.subList(i, o.size)
        val lead = listOf("qui s'appelle", "appelee", "appele", "nommee", "nomme", "de", "du", "pour") // l10n: not text (words Robot hears)
        for (l in lead) {
            val w = l.split(' ')
            if (name.size > w.size && name.subList(0, w.size) == w) {
                name = name.drop(w.size)
                nameSaid = nameSaid.drop(w.size)
                break
            }
        }
        if (name.isNotEmpty() && name[0].startsWith("d'") && name[0].length > 2) {
            name = listOf(name[0].substring(2)) + name.drop(1)
            nameSaid = listOf(nameSaid[0].substring(2)) + nameSaid.drop(1)
        }
        ROLE_FR[name.joinToString(" ")]?.let { return ListsCommand.Make(null, it, lang) }
        // « fais-moi une liste de recettes faciles » asks for an answer, « une liste de lecture » for a playlist; « une liste de camping » is a list.
        if ((f[i] == "de" || f[i].startsWith("d'")) && (name.size > 1 || name[0] == "lecture")) return null // l10n: not text (words Robot hears)
        return named(nameSaid, name, lang)
    }

    /** A new list's name as said ([o]; [f] folded), within the words a name may hold. */
    private fun named(o: List<String>, f: List<String>, lang: Lang): ListsCommand? {
        if (f.isEmpty() || f.size > MAX_NAME_WORDS || f.any { it in NOT_IN_NAME || it in DET_EN || it in NOT_YOURS }) return null
        return ListsCommand.Make(o.joinToString(" "), null, lang)
    }

    // ------------------------------------------------------------------ Québec French

    private val ADD_FR = listOf("ajoute", "ajouter", "rajoute", "rajouter", "mets", "met", "mettre", "inscris", "ecris", "ajoute aussi", "mets aussi", // l10n: not text (words Robot hears)
        // LS8: « mets-moi du lait sur ma liste », « peux-tu me mettre des bananes sur ma liste » (« peux-tu » is a filler).
        "mets-moi", "mets moi", "ajoute-moi", "ajoute moi", "rajoute-moi", "rajoute moi", "me mettre", "m'ajouter", "me rajouter") // l10n: not text (words Robot hears)
    private val TO_FR = setOf("a", "sur", "dans", "au") // l10n: not text (words Robot hears)
    private val READ_ON_FR = listOf(
        // everyday-1.2.2-a bug 2: « combien … sur ma liste » is a read (its reply counts them).
        "combien d'items sur", "combien d'articles sur", "combien de choses sur", "combien de trucs sur", "combien d'affaires sur", // l10n: not text (words Robot hears)
        "combien d'items il y a sur", "combien d'articles il y a sur", "combien de choses il y a sur", "combien de trucs il y a sur", // l10n: not text (words Robot hears)
        "combien il y a d'items sur", "combien il y a de choses sur", "combien y a-t-il de choses sur", "combien y a-t-il d'items sur", // l10n: not text (words Robot hears)
        "j'ai combien d'items sur", "j'ai combien de choses sur", "j'ai combien d'affaires sur", "j'ai combien de trucs sur", // l10n: not text (words Robot hears)
        "qu'est-ce qu'il y a sur", "qu'est-ce qu'il y a dans", "qu'est-ce qui est sur", "qu'est-ce qui reste sur", // l10n: not text (words Robot hears)
        "qu'est-ce qu'il reste sur", "qu'est-ce qu'il reste dans", "qu'y a-t-il sur", "qu'y a-t-il dans", "c'est quoi sur", "c'est quoi dans", // l10n: not text (words Robot hears)
        "il y a quoi sur", "il y a quoi dans", "y a quoi sur", "y a quoi dans", "quoi sur", "qu'est-ce que j'ai sur", "qu'est-ce que j'ai dans", // l10n: not text (words Robot hears)
        // LS8: Québec's spoken forms.
        "j'ai quoi sur", "j'ai quoi dans", "qu'est-ce qu'y a sur", "qu'est-ce qu'y a dans", "y'a quoi sur", "y'a quoi dans", // l10n: not text (words Robot hears)
        "qu'est-ce qui me reste sur", "qu'est-ce qu'il me reste sur", "qu'est-ce qui me reste dans", "qu'est-ce qu'il me reste dans", // l10n: not text (words Robot hears)
        "dis-moi ce qu'il y a sur", "dis-moi ce qu'il y a dans", "dis-moi ce qui reste sur", "lis-moi ce qu'il y a sur", // l10n: not text (words Robot hears)
        "c'est quoi qu'il y a sur", "c'est quoi qu'y a sur", "c'est quoi qu'il y a dans") // l10n: not text (words Robot hears)
    private val READ_FR = listOf("lis", "lis-moi", "lire", "lis moi", "montre", "montre-moi", "montre moi", "dis-moi", "dis moi", "c'est quoi", "ouvre") // l10n: not text (words Robot hears)
    private val MY_LISTS_FR = setOf("quelles listes j'ai", "quelles sont mes listes", "c'est quoi mes listes", "quelles listes est-ce que j'ai", // l10n: not text (words Robot hears)
        "quelles listes ai-je", "lis-moi mes listes", "montre-moi mes listes", "mes listes", "j'ai quelles listes", "j'ai-tu des listes", // l10n: not text (words Robot hears)
        "j'ai combien de listes", "combien de listes j'ai", "combien j'ai de listes", "combien de listes est-ce que j'ai") // l10n: not text (words Robot hears)
    private val CHECK_FR = listOf("est-ce que j'ai", "j'ai-tu", "j'ai tu", "est-ce qu'il y a", "y a-tu", "y a tu", "il y a-tu", "est-ce que j'ai mis", // l10n: not text (words Robot hears)
        "j'ai-tu mis", "ai-je", "y'a-tu", "y'a tu", "as-tu", "as tu", "est-ce que tu as", "est-ce qu'on a", "on a-tu", "on a tu") // l10n: not text (words Robot hears)
    /** « le lait est-tu sur ma liste », « les œufs sont-ils sur ma liste »: the item, then one of these, then « sur » or « dans » a list. */
    private val BE_TU = setOf("est-tu", "es-tu", "sont-tu", "est-il", "est-elle", "sont-ils", "sont-elles") // l10n: not text (words Robot hears)
    /** The same said as two words (« est tu »). */
    private val BE = setOf("est", "sont") // l10n: not text (words Robot hears)
    private val TU = setOf("tu", "il", "elle", "ils", "elles") // l10n: not text (words Robot hears)
    /** « de la » and « de ma » as Québec says them before « liste »: « enlève le pain d'la liste ». */
    private val OFF_FR = setOf("de", "sur", "dans", "des", "du", "d'la", "d'ma") // l10n: not text (words Robot hears)
    private val TICK_FR = setOf("enleve", "enlever", "retire", "retirer", "raye", "rayer", "coche", "cocher", "ote", "oter", "biffe", "barre") // l10n: not text (words Robot hears)
    private val PUT_BACK_FR = setOf("remets", "remet", "remettre", "decoche", "decocher") // l10n: not text (words Robot hears)
    private val MAKE_FR = listOf("fais", "fais-moi", "fait", "faire", "cree", "creer", "cree-moi", "commence", "commencer", "demarre", "part", "nouvelle", "fais moi") // l10n: not text (words Robot hears)
    private val UNDO_FR = setOf("annule", "annule ca", "annule-le", "annule le", "defais ca", "defais", "annule la derniere") // l10n: not text (words Robot hears)
    private val DONE_FR = listOf("ce qui est coche", "ce qui est fait", "ce que j'ai coche", "les elements coches", "les elements faits", "les choses cochees", // l10n: not text (words Robot hears)
        "les items coches", "ce qui est raye", "les cochees", "les coches", // l10n: not text (words Robot hears)
        // fix-124c: crossed off (« rayé ») and done, as the English has them.
        "les elements rayes", "les items rayes", "les choses rayees", "les choses faites", "les items faits", "les rayes", "ce que j'ai raye", // l10n: not text (words Robot hears)
        "ce qui est barre", "les elements barres") // l10n: not text (words Robot hears)

    private fun french(s: Said, heard: ListsHeard): ListsCommand? {
        val t = s.text
        val fr = Lang.FR
        if (heard.undo && t in UNDO_FR) return ListsCommand.Undo(fr)
        if (t in MY_LISTS_FR) return ListsCommand.MyLists(fr)

        // « fais une liste de camping », « crée une nouvelle liste Noël »
        startsAny(s, MAKE_FR).takeIf { it > 0 }?.let { n -> make(s, n, fr = true)?.let { return it } }

        // « ajoute du lait d'avoine à la liste d'épicerie »; « ajoute aussi du pain » for the list in focus.
        startsAny(s, ADD_FR).takeIf { it > 0 }?.let { n ->
            colonAdd(s, n, TO_FR, fr = true, heard)?.let { return it }
            val f = s.folded
            val aussi = f.size > n + 1 && (f.last() == "aussi" || f[n - 1] == "aussi")
            val core = if (f.last() == "aussi") Said(s.words.dropLast(1)) else s
            split(core, n, TO_FR, fr = true, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.Add(items, r.ref, r.said, fr) else null }
            if (aussi && heard.focus && core.words.size > n) {
                val items = core.words.subList(n, core.words.size).joinToString(" ")
                if (ownItems(items) && core.folded.subList(n, core.folded.size).none { it in TO_FR }) return ListsCommand.Add(items, ListRef.Unnamed, "liste", fr) // l10n: not text (the list's word, said back)
            }
        }

        // « qu'est-ce qu'il y a sur ma liste d'épicerie », « lis-moi ma liste de choses à faire »
        startsAny(s, READ_ON_FR).takeIf { it > 0 }?.let { n -> wholeAsked(s, n, fr = true, heard)?.let { r -> return ListsCommand.Read(r.ref, r.said, fr) } }
        startsAny(s, READ_FR).takeIf { it > 0 }?.let { n ->
            if (n < s.folded.size && s.folded[n] in setOf("ma", "la", "notre")) wholeAsked(s, n, fr = true, heard)?.takeIf { hasNoun(s, n) }?.let { r -> return ListsCommand.Read(r.ref, r.said, fr) }
        }

        // « est-ce que j'ai des œufs sur ma liste », « j'ai-tu des œufs sur ma liste »
        startsAny(s, CHECK_FR).takeIf { it > 0 }?.let { n ->
            split(s, n, setOf("sur", "dans"), fr = true, heard) { w, r -> yoursAsked(w, r, heard) }?.let { (item, r) ->
                if (ownItems(item) && (hasNoun(s, n) || r.ref is ListRef.Role)) return if (asksAnything(item)) ListsCommand.Read(r.ref, r.said, fr) else ListsCommand.Check(item, r.ref, fr)
            }
        }
        // « est-ce que les œufs sont sur ma liste »
        startsAny(s, listOf("est-ce que")).takeIf { it > 0 }?.let { n ->
            val f = s.folded
            val be = (n until f.size).firstOrNull { f[it] in setOf("est", "sont") } // l10n: not text (words Robot hears)
            if (be != null && be > n && be + 2 < f.size && f[be + 1] in setOf("sur", "dans")) {
                val item = s.words.subList(n, be).joinToString(" ")
                wholeAsked(s, be + 2, fr = true, heard)?.takeIf { hasNoun(s, be) }?.let { r -> if (ownItems(item)) return ListsCommand.Check(item, r.ref, fr) }
            }
        }
        // LS8: « le lait est-tu sur ma liste d'épicerie », « les œufs sont-ils sur ma liste », « le beurre est tu dans ma liste »
        isItOn(s, heard)?.let { return it }
        // fix-list-it: « le lait est-tu dessus », « j'ai-tu du lait sur celle-là » after a list turn.
        checkItFr(s, heard)?.let { return it }
        doneIt(s, DONE_IT_FR, heard, fr)?.let { return it }

        clearFr(s, heard)?.let { return it }

        // « enlève les œufs de la liste d'épicerie », « raye le pain », « coche la crème solaire »
        val f = s.folded
        if (f.size >= 2 && f[0] in TICK_FR) {
            // fix-list-it: « enlève le lait de celle-là », « retire le pain de là », « ôte les œufs de dessus »: the list in focus.
            IT_OFF_FR.firstOrNull { e -> f.size > e.size + 1 && f.takeLast(e.size) == e }?.let { e ->
                val items = s.words.subList(1, f.size - e.size).joinToString(" ")
                return if (ownItems(items) && takesIt(items, heard, fr)) ListsCommand.Tick(items, ListRef.It, fr) else null
            }
            // « enlève les œufs d'la liste » too (LS8).
            split(s, 1, OFF_FR, fr = true, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.Tick(items, r.ref, fr) else null }
            val items = s.words.drop(1).joinToString(" ")
            if (!ownItems(items)) return null
            return bare(items, done = false, heard, fr)?.let { ListsCommand.Tick(items, it, fr) }
        }
        // « remets les œufs sur la liste », « décoche le pain »
        if (f.size >= 2 && f[0] in PUT_BACK_FR) {
            split(s, 1, setOf("sur", "dans", "a", "de"), fr = true, heard)?.let { (items, r) -> return if (ownItems(items)) ListsCommand.PutBack(items, r.ref, fr) else null }
            val items = s.words.drop(1).joinToString(" ")
            if (!ownItems(items)) return null
            return bare(items, done = true, heard, fr)?.let { ListsCommand.PutBack(items, it, fr) }
        }
        return null
    }

    private val IT_OFF_FR = listOf(listOf("de", "la"), listOf("de", "celle-la"), listOf("de", "dessus"), listOf("d'la", "dessus")) // l10n: not text (words Robot hears)
    private val IT_ON_FR = listOf(listOf("dessus"), listOf("sur", "celle-la"), listOf("dans", "celle-la"), listOf("la-dessus")) // l10n: not text (words Robot hears)

    /** fix-list-it: « le lait est-tu dessus », « est-ce que le lait est dessus », « j'ai-tu du lait sur celle-là »: the list in focus. */
    private fun checkItFr(s: Said, heard: ListsHeard): ListsCommand? {
        if (!heard.lastTurn) return null // list-it-fix: only right after a list turn
        val f = s.folded
        val tail = IT_ON_FR.firstOrNull { t -> f.size > t.size && f.takeLast(t.size) == t } ?: return null
        val end = f.size - tail.size
        val n = startsAny(s, CHECK_FR)
        val item: List<String> = if (n > 0) s.words.subList(n, end) else {
            val from = startsAny(s, listOf("est-ce que")).coerceAtLeast(0) // l10n: not text (words Robot hears)
            val i = (from until end).firstOrNull { f[it] in BE_TU || f[it] in BE } ?: return null
            val after = if (f[i] in BE && i + 1 < end && f[i + 1] in TU) i + 2 else i + 1
            if (after != end || i <= from) return null
            s.words.subList(from, i)
        }
        val words = item.joinToString(" ")
        if (words.isBlank() || !ownItems(words) || asksAnything(words)) return null
        return ListsCommand.Check(words, ListRef.It, Lang.FR)
    }

    /** « le lait est-tu sur ma liste »: the words before « est-tu » are the item, those after « sur » or « dans » the list. */
    private fun isItOn(s: Said, heard: ListsHeard): ListsCommand? {
        val f = s.folded
        for (i in 1 until f.size) {
            val n = when {
                f[i] in BE_TU -> 1
                f[i] in BE && i + 1 < f.size && f[i + 1] in TU -> 2
                else -> continue
            }
            val on = i + n
            if (on + 1 >= f.size || f[on] !in setOf("sur", "dans")) return null // l10n: not text (words Robot hears)
            val item = s.words.subList(0, i).joinToString(" ")
            if (!ownItems(item)) return null
            return wholeAsked(s, on + 1, fr = true, heard)?.takeIf { hasNoun(s, on) || it.ref is ListRef.Role }?.let { ListsCommand.Check(item, it.ref, Lang.FR) }
        }
        return null
    }

    private fun clearFr(s: Said, heard: ListsHeard): ListsCommand? {
        val f = s.folded
        val fr = Lang.FR
        // « vide la liste d'épicerie », « efface tout sur ma liste »
        val e = startsAny(s, listOf("vide", "vider", "efface tout sur", "efface tout dans", "efface tout de", "enleve tout de", "enleve tout sur")) // l10n: not text (words Robot hears)
        if (e > 0) return whole(s, e, fr = true, heard)?.takeIf { it.ref != ListRef.Unnamed || heard.focus }?.let { ListsCommand.Empty(it.ref, fr) }
        val c = startsAny(s, listOf("efface", "effacer", "enleve", "retire", "supprime", "vide")) // l10n: not text (words Robot hears)
        if (c <= 0) return null
        // fix-124c: « efface tout ce qui est coché »
        val at = if (c + 1 < f.size && f[c] == "tout" && f[c + 1] == "ce") c + 1 else c
        val d = DONE_FR.sortedByDescending { it.length }.firstOrNull { d -> val w = d.split(' '); at + w.size <= f.size && f.subList(at, at + w.size) == w }
        if (d != null) {
            val i = at + d.split(' ').size
            if (i == f.size) return if (heard.focus) ListsCommand.ClearDone(ListRef.Unnamed, fr) else null
            if (f[i] !in OFF_FR) return null
            return (whole(s, i + 1, fr = true, heard) ?: bareRole(s, i + 1, DET_FR + setOf("les", "mes"), ROLE_FR))
                ?.takeIf { it.ref != ListRef.Unnamed || heard.focus }?.let { ListsCommand.ClearDone(it.ref, fr) }
        }
        // « efface la liste d'épicerie »: its ticked items only, with Undo.
        if (f[0] !in setOf("efface", "effacer")) return null
        return whole(s, c, fr = true, heard)?.takeIf { (it.ref != ListRef.Unnamed || heard.focus) && hasNoun(s, c) }?.let { ListsCommand.ClearDone(it.ref, fr) }
    }
}
