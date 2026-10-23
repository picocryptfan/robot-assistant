// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.memory.MemoryIds
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemorySource

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.5, D6 and D10; job F8): your filing rules, kept in memory (MemoryFiling) and
// read by filing (FileRules.yours). Robot organizes on its own unless you tell it otherwise or it sees how you organize: your
// words ("Esso receipts go under Car", FilesRouter) or your taps (a move in Files, the same tag on two files of one merchant)
// make a rule, saved at once and confirmed once with Undo. The model, a file's text and another Robot never make one.
//
// A rule from your taps takes the file's merchant (content: Robot read it) only through a fixed template and capped at 40
// letters, digits and spaces (MemoryFiling.cleanWords), never the file's words otherwise. Its words in memory are a template
// too ("Esso receipts go in Car"), in the language you used, so Settings › Memory shows it plainly; deleting it there
// deletes the rule. With Memory off Robot keeps no rules and applies none (the plan's answer: not kept elsewhere).

object FilingRules {
    /** Kinds a rule from your taps can be about: the ones whose merchant Robot reads. */
    val LEARNABLE = setOf(FileKind.RECEIPT, FileKind.BILL, FileKind.DOCUMENT, FileKind.TAX_SLIP, FileKind.TICKET)

    /**
     * The rules filing applies, from memory's [items]: each item's `filing`, as FileRules reads it. One from content (an
     * import or someone else's backup), from a newer Robot, or for a kind this Robot doesn't know is kept as [FilingRule.tainted]
     * (never applied). Callers pass no items when Memory is off. A rule's drawer is filed into even when it holds no file yet
     * (a drawer is a view: the first file makes it show); renaming your drawer renames it in your rules too ([renamed]).
     */
    fun of(items: List<MemoryItem>): List<FilingRule> = items.mapNotNull { item ->
        val f = item.filing ?: return@mapNotNull null
        val kind = f.kind?.let { k -> FileKind.entries.firstOrNull { it.name == k && it != FileKind.UNKNOWN && it != FileKind.DANGEROUS } }
        val known = f.kind == null || kind != null
        FilingRule(
            kind = kind,
            merchant = f.merchant,
            words = f.words,
            drawer = f.drawer?.takeIf(FileIndexCodec::label),
            tags = f.tags.filter(FileIndexCodec::label),
            nameFirst = when (f.name) { "name" -> true; "date" -> false; else -> null },
            at = item.updatedAt,
            tainted = item.tainted || !f.usable || !known,
        )
    }

    /**
     * Memory's [file] after your drawer [old] was renamed [new]: each of your rules that names it names the new one, its words
     * written again in its language. A newer Robot's rule is left as it was.
     */
    fun renamed(file: app.heyrobot.core.memory.MemoryFile, old: String, new: String): app.heyrobot.core.memory.MemoryFile =
        file.copy(items = file.items.map { i ->
            val f = i.filing
            if (f == null || f.foreign != null || f.drawer != old || !MemoryFiling.label(new)) i
            else f.copy(drawer = new).let { g -> i.copy(filing = g, text = app.heyrobot.core.memory.MemoryEdits.clean(text(g, if (i.language == "fr") Lang.FR else Lang.EN))) }
        })

    /** A rule's words in [lang], as memory keeps them: "Esso receipts go in Car.", « Les reçus d'Esso vont dans Auto. » */
    fun text(f: MemoryFiling, lang: Lang): String = clause(f, lang).replaceFirstChar { it.uppercaseChar() }

    /** The same words inside a sentence: « …, les reçus d'Esso vont dans Auto. » */
    fun clause(f: MemoryFiling, lang: Lang): String {
        val langs = Langs.of(lang)
        val kind = f.kind?.let { k -> FileKind.entries.firstOrNull { it.name == k } }
        val kinds = kinds(kind)
        val things = when {
            f.merchant != null -> (if (elides(f.merchant)) FilesStrings.ruleThingsOfVowel else FilesStrings.ruleThingsOf)("kinds" to Arg.Sub(kinds), "merchant" to f.merchant)
            f.words != null -> FilesStrings.ruleThingsWith("kinds" to Arg.Sub(kinds), "words" to f.words)
            else -> FilesStrings.ruleThingsAll("kinds" to Arg.Sub(kinds))
        }
        val parts = ArrayList<Phrase>()
        f.drawer?.let { parts += FilesStrings.ruleDrawer("things" to Arg.Sub(things), "drawer" to Arg.Sub(FilesStrings.drawer(it))) }
        // F12: a tag Robot knows in its words in [lang] (« affaires »), any other as you typed it.
        if (f.tags.isNotEmpty()) parts += FilesStrings.ruleTags("things" to Arg.Sub(things), "tags" to f.tags.joinToString(", ") { FilesStrings.tag(it).render(langs) })
        f.name?.let { parts += (if (it == "name") FilesStrings.ruleNameFirst else FilesStrings.ruleDateFirst)("things" to Arg.Sub(things)) }
        return parts.joinToString(" ") { it.render(langs) }
    }

    /** French elides « de » before a vowel or a mute h: « d'Esso », « d'Hydro-Québec », but « de Costco », « de Home Depot » (FX2: FilesStrings.elides). */
    private fun elides(word: String): Boolean = FilesStrings.elides(word)

    /** A kind in the plural: "receipts", « reçus »; "files" for none. */
    fun kinds(k: FileKind?): Phrase = when (k) {
        FileKind.RECEIPT -> FilesStrings.kindsReceipt()
        FileKind.BILL -> FilesStrings.kindsBill()
        FileKind.DOCUMENT -> FilesStrings.kindsDocument()
        FileKind.ID -> FilesStrings.kindsId()
        FileKind.TAX_SLIP -> FilesStrings.kindsTaxSlip()
        FileKind.TICKET -> FilesStrings.kindsTicket()
        FileKind.MEDICAL -> FilesStrings.kindsMedical()
        FileKind.PHOTO -> FilesStrings.kindsPhoto()
        FileKind.VIDEO -> FilesStrings.kindsVideo()
        FileKind.NOTE -> FilesStrings.kindsNote()
        FileKind.TRANSCRIPT -> FilesStrings.kindsTranscript()
        else -> FilesStrings.kindsFile()
    }

    /**
     * The memory item that keeps [f], said in [lang] ([source]: SAID or TYPED for your words, SETTINGS for your taps: sources
     * a 1.1 Robot knows, so it reads the item as your own words too).
     */
    fun item(f: MemoryFiling, lang: Lang, source: MemorySource, now: Long, id: String = MemoryIds.newId(now)): MemoryItem =
        MemoryItem(id, text(f, lang), source = source, language = if (lang == Lang.FR) "fr" else "en", createdAt = now, filing = f)

    // What your taps teach.

    /**
     * The rule a move of [e] (as it was before) to [drawer] teaches, or null: a file of a [LEARNABLE] kind with a merchant
     * Robot read, moved somewhere other than where your rules and Robot's table would put it now. A move into another kind's
     * own starter drawer (a bill moved to Receipts) corrects the kind rather than saying where its merchant's files go: no rule.
     */
    fun fromMove(e: FileEntry, drawer: String, rules: List<FilingRule>): MemoryFiling? {
        if (e.kind !in LEARNABLE || drawer == e.drawer || !FileIndexCodec.label(drawer)) return null
        val merchant = MemoryFiling.cleanWords(e.fields["merchant"]?.value) ?: return null
        if (drawer in Drawers.STARTER && drawer != Drawers.DOCUMENTS && drawer != Drawers.TAXES && drawer != Drawers.of(e.kind)) return null
        val now = FileRules.yours(rules, e.kind, merchant, null)?.drawer ?: Drawers.of(e.kind, e.tags)
        if (drawer == now || !MemoryFiling.label(drawer)) return null
        return MemoryFiling(kind = e.kind.name, merchant = merchant, drawer = drawer, learned = true)
    }

    /**
     * The rule your [tag] on file [id] teaches, or null: the second file of a [LEARNABLE] kind from one merchant that has it
     * ("you tag two Hydro bills business"), unless your rules give that tag already. Robot's own tags (the month, the
     * merchant) teach nothing.
     */
    fun fromTag(idx: FileIndex, id: FileId, tag: String, rules: List<FilingRule>): MemoryFiling? {
        val e = idx.entry(id) ?: return null
        if (e.kind !in LEARNABLE || tag !in e.tags || !MemoryFiling.label(tag) || MONTH.matches(tag)) return null
        val merchant = MemoryFiling.cleanWords(e.fields["merchant"]?.value) ?: return null
        val key = MemoryFiling.key(merchant)
        if (MemoryFiling.key(tag) == key) return null
        val others = idx.entries.count { o -> o.id != id && o.kind == e.kind && tag in o.tags && MemoryFiling.key(MemoryFiling.cleanWords(o.fields["merchant"]?.value)) == key }
        if (others < 1) return null
        if (FileRules.yours(rules, e.kind, merchant, null)?.tags?.contains(tag) == true) return null
        return MemoryFiling(kind = e.kind.name, merchant = merchant, tags = listOf(tag), learned = true)
    }

    private val MONTH = Regex("\\d{4}-\\d{2}") // l10n: not text (a parser's pattern)
}
