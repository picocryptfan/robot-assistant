// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContextWindow
import java.time.ZoneId

// Pure. Memory in a panel chat turn (design §A6): what goes with the question, found by the [Retriever] (BM25 until the
// search model of phase 7.4 adds its vectors through the same seam) and packed by PromptPacking within the model's
// context, short-context (chat-only) models included. Only the panel's chat turns get it: never a one-shot, a
// translation, a summary or a helper. Also the searches the router's commands need, and the system prompt's lines.

object MemoryRecall {
    /**
     * The budget for a model loaded with [maxTokens] of context: the design's at 4,096; a short-context (chat-only) model
     * gets less of its smaller window (60 and 100 estimated tokens, four items).
     */
    fun budget(maxTokens: Int): PackBudget =
        if (ContextWindow.chatOnly(maxTokens)) PackBudget(pinnedTokens = 60, retrievedTokens = 100, maxRetrieved = 4) else PackBudget()

    /**
     * What of memory goes with [question]: the pinned line and the notes found, or [PackedMemory.NONE] when memory is off,
     * not used in answers, or not usable on a locked phone ([locked] without "Use memory on the lock screen"). Locked
     * with it on, secret items never come up. [alreadyGiven]: items this live conversation already holds.
     */
    fun forTurn(question: String, file: MemoryFile, s: MemorySettings, locked: Boolean, now: Long, alreadyGiven: Set<String> = emptySet(),
        maxTokens: Int = ContextWindow.MAX_TOKENS, retriever: Retriever = Bm25Retriever(), zone: ZoneId = ZoneId.systemDefault()): Recall {
        if (!s.on || !s.useInAnswers || (locked && !s.onLockScreen)) return Recall(PackedMemory.NONE, RecallBy.WORDS)
        val live = MemoryEdits.live(file.items, now)
        if (live.isEmpty()) return Recall(PackedMemory.NONE, RecallBy.WORDS)
        val budget = budget(maxTokens)
        val ranked = retriever.retrieve(RecallQuery(question), live, budget.maxRetrieved * 2)
        val packed = PromptPacking.pack(question, live, ranked, now, unlocked = !locked, alreadyGiven = alreadyGiven, budget = budget, zone = zone)
        val by = ranked.filter { it.item.id in packed.retrievedIds }.map { it.by }.toSet()
        return Recall(packed, if (by.size > 1 || RecallBy.BOTH in by) RecallBy.BOTH else by.firstOrNull() ?: RecallBy.WORDS,
            secret = (packed.retrievedIds.toSet()).let { ids -> live.any { it.id in ids && it.secret } })
    }

    /** What [forTurn] found, how ([by], for the voice log), and whether a secret item went in ([secret]: the answer isn't kept). */
    data class Recall(val packed: PackedMemory, val by: RecallBy, val secret: Boolean = false) {
        /** Every item given: pinned and found. */
        val ids: List<String> get() = (packed.pinnedIds + packed.retrievedIds).distinct()
    }
}

/** The searches the router's commands run ("forget my sister's birthday", "what do you remember about Julie?"). */
object MemorySearch {
    /**
     * The live items [query] names, best first, at most [limit]: those sharing a search term with it (BM25, no floor), then
     * any whose folded words hold it whole. A query of only small words ("the", "ça") finds nothing.
     */
    fun find(query: String, items: List<MemoryItem>, now: Long, limit: Int = 10): List<MemoryItem> {
        val live = MemoryEdits.live(items, now)
        if (live.isEmpty() || MemoryText.tokens(query).isEmpty()) return emptyList()
        val byId = live.associateBy { it.id }
        val hits = Bm25Index.build(live.sortedByDescending { it.createdAt }.map { it.id to it.text }).search(query, limit).mapNotNull { byId[it.id] }
        fun words(s: String) = " " + MemoryText.fold(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
        val q = words(query)
        val whole = live.filter { words(it.text).contains(q) }
        return (hits + whole).distinctBy { it.id }.take(limit)
    }

    /**
     * Which item(s) [query] clearly means: one when it is the only hit or its score stands well above the next one's;
     * otherwise all the hits (a pick card), at most [limit].
     */
    fun clear(query: String, items: List<MemoryItem>, now: Long, limit: Int = 4): List<MemoryItem> {
        val live = MemoryEdits.live(items, now)
        val hits = Bm25Index.build(live.sortedByDescending { it.createdAt }.map { it.id to it.text }).search(query, limit + 1)
        if (hits.size >= 2 && hits[0].score >= hits[1].score * 2 && hits[0].matched.size > hits[1].matched.size) {
            return listOfNotNull(live.firstOrNull { it.id == hits[0].id })
        }
        return find(query, items, now, limit)
    }

    /** "What do you know about me?": the [n] items most used (then newest), never a secret one. */
    fun examples(items: List<MemoryItem>, now: Long, n: Int = 3): List<MemoryItem> =
        MemoryEdits.live(items, now).filter { !it.secret }.sortedWith(compareByDescending<MemoryItem> { it.uses }.thenByDescending { it.createdAt }).take(n)
}

/** The system prompt's memory lines (design §A6.4), added at its end so the rest stays as Prompt.kt writes it. */
object MemoryPrompt {
    /** Only while remember and forget are described. */
    const val TOOLS = "To keep or drop something about the person when they ask, call remember or forget."

    /** [base], then [tools]' line, then the pinned line ([pinned]: PromptPacking's, byte-stable while the pinned list is). */
    fun system(base: String, tools: Boolean, pinned: String?): String = buildString {
        append(base)
        if (tools) append(' ').append(TOOLS)
        if (pinned != null) append('\n').append(pinned)
    }
}

/** "Forget that": the item saved or used last in this conversation (design §A3.1). */
object ForgetLast {
    /**
     * The ids "forget that" removes: the item saved last in this conversation when it came after the last answer that
     * used memory; else the items that answer used (the pinned ones aside), if one; several make a pick card.
     */
    fun target(savedLast: String?, savedAt: Long, usedLast: List<String>, usedAt: Long): List<String> = when {
        savedLast != null && savedAt >= usedAt -> listOf(savedLast)
        usedLast.isNotEmpty() -> usedLast
        savedLast != null -> listOf(savedLast)
        else -> emptyList()
    }
}

/**
 * 1.0 audit (injection-memory-recall-taint): memory items from content stay content wherever they show up in a conversation.
 */
object MemoryTaint {
    /**
     * The items already in this conversation: every turn's, the answers' too. "What do you remember about …" puts an item's
     * words in Robot's reply, not in your question: a later question that doesn't find it again still goes with it, and a
     * content item makes the conversation content (MemoryPanel.forTurn).
     */
    fun given(turns: List<ChatTurn>): Set<String> = turns.flatMapTo(LinkedHashSet()) { it.memoryIds }

    /**
     * Whether something remembered now came from content: the conversation holds content ([conversation]:
     * TurnRules.untrusted), the last turn's actions brought some, or this turn went with a content memory item ([memory]: its
     * MemoryTurn.content). Remembered from a content item, it stays content, never your own words.
     */
    fun fromContent(conversation: Boolean, lastTurn: Boolean, memory: Boolean): Boolean = conversation || lastTurn || memory
}
