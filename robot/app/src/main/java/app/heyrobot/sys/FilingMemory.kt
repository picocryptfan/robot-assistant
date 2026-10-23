// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.files.FilingRule
import app.heyrobot.core.files.FilingRules
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.memory.AddOutcome
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySource

// BOTH APP PROCESSES. 1.4, Robot Files' filing rules in memory (files-1.4-plan.md §4.5, D6; job F8): the one place Files
// opens memory. :voice reads the rules for filing (FilingWork) and saves one from your words (PanelFiles); the main app
// saves one from your move or your tags (the Files screens). Memory's vault does the rest: its key needs the phone unlocked,
// its lock covers both processes, and a change shows in the other process at its next read (the file is read each time,
// never held here).
//
// Memory off: no rule is saved and none is read (the plan's answer: rules aren't kept anywhere else). Memory full: the rule
// isn't saved, and the reply says so. A rule never goes to the model: only Files reads it, here.

object FilingMemory {
    /** What saving a rule came to. */
    sealed class Saved {
        /** Kept: [item], replacing [replaced] (Undo puts them back). */
        class Kept(val item: MemoryItem, val replaced: List<MemoryItem>) : Saved()
        /** The same rule was already there. */
        class Already(val item: MemoryItem) : Saved()
        /** Memory is off in Settings › Memory. */
        data object Off : Saved()
        /** 2,000 items: nothing saved. */
        data object Full : Saved()
        /** Memory couldn't be opened (locked, its key gone, damaged, newer): nothing saved. */
        data object NotOpen : Saved()
    }

    private fun on(ctx: Context): Boolean = MemorySettingsStore.current(ctx).on

    /** Your rules for filing: none while Memory is off or can't be opened. */
    fun rules(ctx: Context): List<FilingRule> {
        if (!on(ctx)) return emptyList()
        val f = (MemoryFiles.vault(ctx).load() as? MemoryLoad.Loaded)?.file ?: return emptyList()
        return FilingRules.of(f.items)
    }

    /** Your drawer [old] was renamed [new] (the Files screens): your rules that name it follow. False when memory couldn't be opened. */
    fun renameDrawer(ctx: Context, old: String, new: String): Boolean {
        if (old == new) return true
        val l = MemoryFiles.vault(ctx).update { f -> FilingRules.renamed(f, old, new) }
        return l is MemoryLoad.Loaded || l == MemoryLoad.Empty
    }

    /** How many rules memory holds, or null when it can't be read (Settings › Files' line). */
    fun count(ctx: Context, hidden: (String) -> Boolean = { false }): Int? = when (val l = MemoryFiles.vault(ctx).load()) {
        // FX1: a rule naming a drawer travel mode hides now ([hidden]) isn't counted.
        is MemoryLoad.Loaded -> l.file.items.count { it.filing != null && it.filing?.drawer?.let(hidden) != true }
        MemoryLoad.Empty -> 0
        else -> null
    }

    /** Saves [rule], worded in [lang], made by [source] (SAID or TYPED: your words; SETTINGS: your taps). Background thread. */
    fun save(ctx: Context, rule: MemoryFiling, lang: Lang, source: MemorySource, now: Long = System.currentTimeMillis()): Saved {
        if (!on(ctx)) return Saved.Off
        val item = FilingRules.item(rule, lang, source, now)
        var r: app.heyrobot.core.memory.RuleAdded? = null
        val l = MemoryFiles.vault(ctx).update { f -> MemoryEdits.addRule(f, item).also { r = it }.file }
        val done = r
        if ((l !is MemoryLoad.Loaded && l != MemoryLoad.Empty) || done == null) return Saved.NotOpen
        return when (done.outcome) {
            AddOutcome.SAVED, AddOutcome.SAVED_PIN_FULL -> Saved.Kept(done.item!!, done.replaced)
            AddOutcome.ALREADY_KEPT -> Saved.Already(done.item!!)
            AddOutcome.FULL -> Saved.Full
            AddOutcome.EMPTY -> Saved.NotOpen
        }
    }

    /** Undo: the rule [saved] made goes, the rules it replaced come back. False when memory couldn't be opened. */
    fun undo(ctx: Context, saved: Saved.Kept): Boolean {
        val l = MemoryFiles.vault(ctx).update { f -> MemoryEdits.undoRule(f, saved.item.id, saved.replaced) }
        return l is MemoryLoad.Loaded
    }
}
