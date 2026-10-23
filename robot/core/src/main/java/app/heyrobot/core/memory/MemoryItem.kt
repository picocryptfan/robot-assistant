// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.ToolCalls
import kotlin.random.Random

// Pure. What Robot remembers because you asked (0.7 "Memory and messages"): one item per thing you
// said to keep ("Remember that my sister Julie's birthday is May 3"), each one you can see, edit, pin or delete in
// Settings › Memory. Not memory: the History of conversations, Robot Notes ("remember to buy milk" stays a note),
// People, and message text. The items live in one encrypted file (MemoryCodec, MemoryEnvelope); this file holds the
// model and the pure list changes the vault applies under its file lock.

/** What an item is. A [PREFERENCE] is pinned by itself while there is room ([MemoryLimits.MAX_PINNED]). */
enum class MemoryKind {
    /** "My sister Julie's birthday is May 3": retrieved when a question needs it. */
    FACT,
    /** "Call me Sam", "I prefer Celsius": pinned, given to the model with every answer. */
    PREFERENCE,
}

/** Where an item came from. Only [CONTENT] isn't your own words. [text]: how Settings › Memory says it (core i18n); [title] its English. */
enum class MemorySource(private val msg: app.heyrobot.core.i18n.Msg) {
    SAID(app.heyrobot.core.i18n.MemoryStrings.sourceSaid),
    TYPED(app.heyrobot.core.i18n.MemoryStrings.sourceTyped),
    SUGGESTED(app.heyrobot.core.i18n.MemoryStrings.sourceSuggested),
    SETTINGS(app.heyrobot.core.i18n.MemoryStrings.sourceSettings),
    /** Screen text, a message, a transcript or a helper's words, kept through a card: content when retrieved. */
    CONTENT(app.heyrobot.core.i18n.MemoryStrings.sourceContent);

    val text: app.heyrobot.core.i18n.Phrase get() = msg()
    val title: String get() = text.en()

    /** Your own words (or your tap on your own words): retrieving it never makes a conversation content. */
    val own: Boolean get() = this != CONTENT
}

/**
 * 1.0: an item that came from a memory export (Settings › Memory › Import). Settings › Memory labels it "Imported" until
 * you tap "This is mine" ([MemoryEdits.mine]).
 */
enum class MemoryImported {
    /**
     * The default: a file can be made by someone else, so the item counts as content, as one kept from something Robot
     * read does, until you say it is yours. Also an item the file itself marked as content, whatever you ticked.
     */
    CONTENT,
    /** Imported with "These are my own memories, from my own export" ticked: your own words, as its source says. */
    YOURS,
}

/**
 * One remembered thing. [text] is as said or edited (at most [MemoryLimits.MAX_CHARS]). [secret]: a PIN, a password, a
 * card or ID number ([SecretDetector], or marked by you): saved, but never pinned, never spoken unless you ask for it by
 * name, never put in a prompt unless the question names it, never sent to a helper. [sourceLabel]: for a [MemorySource.CONTENT]
 * item, where it came from in words ("a message from Mom"), shown and given to the model with it; never the message
 * itself. [about]: People ids it mentions. [language]: "en" or "fr". [expiresAt]: "until tonight" (hidden from then,
 * deleted a day later). [uses]: a counter only, no text of the answers. [imported]: it came from a memory export (1.0), and
 * whether it counts as content for that.
 *
 * [toString] never prints the text: an item that reaches a log line or a crash report says only its id and size.
 */
data class MemoryItem(
    val id: String,
    val text: String,
    val kind: MemoryKind = MemoryKind.FACT,
    val secret: Boolean = false,
    val pinned: Boolean = false,
    val source: MemorySource = MemorySource.SAID,
    val sourceLabel: String? = null,
    val language: String? = null,
    val about: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val expiresAt: Long? = null,
    val uses: Int = 0,
    val usedAt: Long? = null,
    val imported: MemoryImported? = null,
    /**
     * 1.4: a filing rule (files-1.4-plan.md §4.5): Robot Files reads it, Settings › Memory shows it as "Filing rule", and
     * it never goes into a question's prompt ([MemoryEdits.forQuestions]). Always a fact, never pinned.
     */
    val filing: MemoryFiling? = null,
) {
    /**
     * Retrieved into a conversation, it makes that conversation content (later actions follow Safety cards): kept from
     * something Robot read, or imported and not yet confirmed as yours (1.0 audit, MemoryTaint).
     */
    val tainted: Boolean get() = !source.own || imported == MemoryImported.CONTENT

    /** Past its end ("until tonight"): never retrieved, deleted by [MemoryEdits.sweep] a day later. */
    fun expired(now: Long): Boolean = expiresAt != null && now >= expiresAt

    override fun toString(): String = "MemoryItem(id=$id, kind=$kind, secret=$secret, pinned=$pinned, source=$source, imported=$imported, rule=${filing != null}, chars=${text.length})" // l10n: not text (toString for logs and tests)
}

/** A suggestion waiting in Settings › Memory › Suggestions: never spoken, never saved without your tap. */
data class MemorySuggestion(val id: String, val text: String, val kind: MemoryKind, val createdAt: Long) {
    override fun toString(): String = "MemorySuggestion(id=$id, kind=$kind, chars=${text.length})" // l10n: not text (toString for logs and tests)
}

/**
 * Everything memory.enc holds: the [items], the [suggestions] waiting for a tap, and [negatives], the suggestions you
 * answered with "Don't suggest things like this" (kept only to compare against; never sent anywhere).
 */
data class MemoryFile(
    val items: List<MemoryItem> = emptyList(),
    val suggestions: List<MemorySuggestion> = emptyList(),
    val negatives: List<String> = emptyList(),
)

object MemoryLimits {
    /** Items at most; a new one past this is refused ("Memory is full: delete some in Settings › Memory"). */
    const val MAX_ITEMS = 2_000
    /** Settings › Memory says the end is near from here. */
    const val NOTE_AT = 1_500
    /** Characters per item. */
    const val MAX_CHARS = 300
    const val MAX_SUGGESTIONS = 100
    /** "Don't suggest things like this" examples kept. */
    const val MAX_NEGATIVES = 200
    /** Pinned items at most: the 7th asks which to unpin. */
    const val MAX_PINNED = 6
    /** A suggestion nobody answered is dropped after this many days. */
    const val SUGGESTION_DAYS = 14
    const val DAY_MS = 86_400_000L
}

object MemoryIds {
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz" // l10n: not text (an id's alphabet)
    private val ID = Regex("^[ms]_[0-9a-z]{6,40}$")

    /** An item id ("m_…") or a suggestion id ("s_…"): letters and digits only, so it is safe in a file or a log. */
    fun valid(id: String?): Boolean = id != null && ID.matches(id)

    /** "m_" + the time in base 36 (fixed width, so ids sort by time) + 6 random characters. */
    fun newId(now: Long, random: Random = Random.Default, prefix: Char = 'm'): String {
        val t = now.coerceAtLeast(0).toString(36).padStart(9, '0')
        val r = CharArray(6) { DIGITS[random.nextInt(DIGITS.length)] }
        return "${prefix}_$t${String(r)}"
    }
}

/** What adding an item did. */
enum class AddOutcome {
    SAVED,
    /** A preference saved unpinned: six are pinned already, Settings asks which to unpin. */
    SAVED_PIN_FULL,
    /** The same text was already kept: nothing new, [Added.item] is the old one. */
    ALREADY_KEPT,
    /** [MemoryLimits.MAX_ITEMS] reached: nothing saved. */
    FULL,
    /** Nothing left to save once trimmed. */
    EMPTY,
}

/** What [MemoryEdits.addRule] did: as [Added], and the older rules the new one [replaced] (Undo puts them back). */
data class RuleAdded(val file: MemoryFile, val item: MemoryItem?, val outcome: AddOutcome, val replaced: List<MemoryItem> = emptyList())

data class Added(val file: MemoryFile, val item: MemoryItem?, val outcome: AddOutcome) {
    /** Settings › Memory shows "Robot remembers 1,500 things" with the limit from here. */
    val nearFull: Boolean get() = file.items.size >= MemoryLimits.NOTE_AT
}

/**
 * The changes the vault makes, as pure functions of the file (the vault reads, applies one of these and writes, under
 * its lock). Every change keeps the order of the other items: pinned items keep yours (Settings › Memory drags them).
 */
object MemoryEdits {
    /** [text] tidied for keeping: whitespace collapsed, at most [MemoryLimits.MAX_CHARS] characters. */
    fun clean(text: String): String = ToolCalls.safeTake(text.replace(Regex("\\s+"), " ").trim(), MemoryLimits.MAX_CHARS).trim()

    /** The same words, ignoring case, accents and punctuation (so "May 3." and "may 3" are one item). */
    fun sameText(a: String, b: String): Boolean = textKey(a) == textKey(b)

    /** What [sameText] compares: folded, letters and digits only. Import keeps them in a set (thousands of items). */
    fun textKey(text: String): String = MemoryText.fold(text).replace(NOT_WORD, "")

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Adds [item] (its text cleaned, a secret marked by [SecretDetector] as well). A preference is pinned while fewer than
     * [MemoryLimits.MAX_PINNED] are; a secret never is. The same text already kept is not added twice.
     */
    fun add(file: MemoryFile, item: MemoryItem): Added {
        val text = clean(item.text)
        if (text.isEmpty()) return Added(file, null, AddOutcome.EMPTY)
        file.items.firstOrNull { sameText(it.text, text) }?.let { return Added(file, it, AddOutcome.ALREADY_KEPT) }
        if (file.items.size >= MemoryLimits.MAX_ITEMS) return Added(file, null, AddOutcome.FULL)
        val secret = item.secret || SecretDetector.isSecret(text)
        val wantsPin = !secret && (item.pinned || item.kind == MemoryKind.PREFERENCE)
        val room = file.items.count { it.pinned } < MemoryLimits.MAX_PINNED
        val kept = item.copy(text = text, secret = secret, pinned = wantsPin && room)
        return Added(file.copy(items = file.items + kept), kept, if (wantsPin && !room) AddOutcome.SAVED_PIN_FULL else AddOutcome.SAVED)
    }

    /** The file without [ids], and the items removed (for Undo, in their old places). */
    fun forget(file: MemoryFile, ids: Collection<String>): Pair<MemoryFile, List<MemoryItem>> {
        val gone = file.items.filter { it.id in ids }
        return file.copy(items = file.items.filter { it.id !in ids }) to gone
    }

    /** Undo of [forget]: the items back (none twice), at the end of the list. */
    fun restore(file: MemoryFile, items: List<MemoryItem>): MemoryFile {
        val have = file.items.mapTo(HashSet()) { it.id }
        return file.copy(items = file.items + items.filter { it.id !in have })
    }

    /** Pin or unpin [id]. Null when it is a secret, unknown, or six are already pinned (the UI asks which to unpin). */
    fun pin(file: MemoryFile, id: String, pinned: Boolean): MemoryFile? {
        val item = file.items.firstOrNull { it.id == id } ?: return null
        if (item.pinned == pinned) return file
        // 1.4: a filing rule is never pinned (pinned items go into every prompt; a rule never goes into one).
        if (pinned && item.filing != null) return null
        if (pinned && (item.secret || file.items.count { it.pinned } >= MemoryLimits.MAX_PINNED)) return null
        return file.copy(items = file.items.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    /**
     * An edit ("Actually her birthday is May 4" on a card, or Settings): new text in the same place, re-checked for a
     * secret (which also unpins it). Null when [id] is unknown or the text is empty.
     */
    fun edit(file: MemoryFile, id: String, text: String, now: Long): MemoryFile? {
        val t = clean(text)
        // 1.4: a filing rule's words are its template: deleted, never edited.
        if (t.isEmpty() || file.items.none { it.id == id } || file.items.any { it.id == id && it.filing != null }) return null
        return file.copy(items = file.items.map {
            if (it.id != id) it else {
                val secret = it.secret || SecretDetector.isSecret(t)
                it.copy(text = t, secret = secret, pinned = it.pinned && !secret, updatedAt = now)
            }
        })
    }

    /**
     * "This is mine" (1.0, an imported item's dialog in Settings › Memory): the Imported label goes, and with it the
     * content mark the import gave it. Where the item came from stays as its source says (one kept from something Robot
     * read is still content). Null when [id] is unknown.
     */
    fun mine(file: MemoryFile, id: String): MemoryFile? {
        if (file.items.none { it.id == id }) return null
        return file.copy(items = file.items.map { if (it.id == id) it.copy(imported = null) else it })
    }

    /** One more answer used these items (a counter and a time, no text). */
    fun used(file: MemoryFile, ids: Collection<String>, now: Long): MemoryFile =
        if (ids.isEmpty()) file else file.copy(items = file.items.map { if (it.id in ids) it.copy(uses = it.uses + 1, usedAt = now) else it })

    /**
     * Housekeeping on load: items that ended more than a day ago, suggestions older than [MemoryLimits.SUGGESTION_DAYS]
     * days or past [MemoryLimits.MAX_SUGGESTIONS] (the newest kept), negatives past [MemoryLimits.MAX_NEGATIVES].
     */
    fun sweep(file: MemoryFile, now: Long): MemoryFile = file.copy(
        items = file.items.filter { it.expiresAt == null || now < it.expiresAt + MemoryLimits.DAY_MS },
        suggestions = file.suggestions.filter { now - it.createdAt < MemoryLimits.SUGGESTION_DAYS * MemoryLimits.DAY_MS }
            .sortedByDescending { it.createdAt }.take(MemoryLimits.MAX_SUGGESTIONS),
        negatives = file.negatives.takeLast(MemoryLimits.MAX_NEGATIVES),
    )

    /** Items a question may use now: not expired. */
    fun live(items: List<MemoryItem>, now: Long): List<MemoryItem> = items.filter { !it.expired(now) }

    /**
     * 1.4: what a question may ever be given, or have found for it: [live] items without filing rules. A rule's words may
     * come from a file (a merchant), so they never reach a prompt, a reply, a helper or the model's forget (§4.5).
     */
    fun forQuestions(items: List<MemoryItem>, now: Long): List<MemoryItem> = items.filter { !it.expired(now) && it.filing == null }

    /**
     * 1.4: a filing rule kept ([item] with its [MemoryItem.filing]). Not [add]: a rule is a fact, never pinned, and the
     * newer of two rules with the same match and the same kind of action (a drawer, a name style) replaces the older one,
     * which [RuleAdded.replaced] gives back for Undo. The same rule again is [AddOutcome.ALREADY_KEPT]; a tag rule whose
     * tags an older rule with that match already gives is too. Full memory refuses it ([AddOutcome.FULL]).
     */
    fun addRule(file: MemoryFile, item: MemoryItem): RuleAdded {
        val rule = requireNotNull(item.filing)
        val text = clean(item.text)
        if (text.isEmpty() || !rule.valid) return RuleAdded(file, null, AddOutcome.EMPTY)
        val mine = file.items.filter { it.filing != null && !it.tainted && it.filing.sameMatch(rule) }
        mine.firstOrNull { it.filing!!.sameAs(rule) }?.let { return RuleAdded(file, it, AddOutcome.ALREADY_KEPT) }
        if (rule.drawer == null && rule.name == null && rule.tags.isNotEmpty() && mine.any { o -> o.filing!!.tags.containsAll(rule.tags) })
            return RuleAdded(file, mine.first { o -> o.filing!!.tags.containsAll(rule.tags) }, AddOutcome.ALREADY_KEPT)
        val replaced = mine.filter { o -> (rule.drawer != null && o.filing!!.drawer != null) || (rule.name != null && o.filing!!.name != null) }
        if (file.items.size - replaced.size >= MemoryLimits.MAX_ITEMS) return RuleAdded(file, null, AddOutcome.FULL)
        val kept = item.copy(text = text, kind = MemoryKind.FACT, pinned = false, secret = item.secret || SecretDetector.isSecret(text))
        val ids = replaced.mapTo(HashSet()) { it.id }
        return RuleAdded(file.copy(items = file.items.filter { it.id !in ids } + kept), kept, AddOutcome.SAVED, replaced)
    }

    /** Undo of [addRule]: the rule gone, the rules it replaced back. */
    fun undoRule(file: MemoryFile, added: String, replaced: List<MemoryItem>): MemoryFile = restore(forget(file, setOf(added)).first, replaced)

    /** "Keep" on a suggestion: it becomes an item (origin [MemorySource.SUGGESTED]) and leaves the list. */
    fun keep(file: MemoryFile, suggestionId: String, now: Long, newId: () -> String = { MemoryIds.newId(now) }): Added {
        val s = file.suggestions.firstOrNull { it.id == suggestionId } ?: return Added(file, null, AddOutcome.EMPTY)
        val rest = file.copy(suggestions = file.suggestions - s)
        return add(rest, MemoryItem(newId(), s.text, s.kind, source = MemorySource.SUGGESTED, createdAt = now))
    }

    /** "Not this" ([dontSuggest]: also "Don't suggest things like this", kept as a negative example). */
    fun dismiss(file: MemoryFile, suggestionId: String, dontSuggest: Boolean): MemoryFile {
        val s = file.suggestions.firstOrNull { it.id == suggestionId } ?: return file
        return file.copy(suggestions = file.suggestions - s, negatives = if (dontSuggest) (file.negatives + s.text).takeLast(MemoryLimits.MAX_NEGATIVES) else file.negatives)
    }
}
