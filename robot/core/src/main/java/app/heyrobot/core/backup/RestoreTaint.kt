// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.Person
import app.heyrobot.core.Quoted
import app.heyrobot.core.Routine
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.memory.MemoryImported
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillLevel

// Pure. Content marks for a backup that may be someone else's: with "This backup is my own, from my
// own phone" off ([mine] false, the default), what a restore brings is treated as something Robot read, as Memory's
// Import treats a file. Memories are content ("Imported"), each conversation carries a content record on the last turn
// the file brought (every later action you didn't ask for asks first, and its turns replay as information), people are
// unconfirmed (a card before any text or email, and they never displace anyone confirmed), routines and helpers new to
// this phone arrive off, a headset's "only you can hear it" is asked again, and keys and private codes are skipped
// (RestorePlanner). With the tick, everything comes back as saved; an item the file itself marks as content stays so.
// 1.4: someone else's files land in Inbox and wait for you ([file]); their filing rules are memory items, so content
// (memory's), shown in Settings › Memory and never applied (FilingRules.of). C10: someone else's connections arrive
// untrusted until you look at them ([connection]), and their keys never come (Connection keys is a Keys category).

object RestoreTaint {
    /** L12, D14: someone else's backup never restores pairings, permissions or what passed between Robots (RestorePlanner too). */
    fun links(f: app.heyrobot.core.link.LinkBackupFile, mine: Boolean): app.heyrobot.core.link.LinkBackupFile? = if (mine) f else null

    /** moments-backup-c (L6): reminders for a moment only from a backup that is yours (the plan's ONLY_YOURS guard, again here). */
    fun moments(l: List<app.heyrobot.core.MomentReminder>, mine: Boolean): List<app.heyrobot.core.MomentReminder>? = if (mine) l else null

    fun memory(i: MemoryItem, mine: Boolean): MemoryItem = if (mine) i else i.copy(imported = MemoryImported.CONTENT)

    fun person(p: Person, mine: Boolean): Person = if (mine) p else p.copy(confirmed = false)

    /** Off, until you turn it on after reading it ("Turn each on after reading it"); review-auto-open 1: no ticked apps. */
    fun routine(r: Routine, mine: Boolean): Routine = if (mine) r else r.copy(enabled = false, opens = emptySet())

    /** 1.1, Skills (plan §4): someone else's skill arrives off; turning it on in Settings sets Ask first. Yours, as saved. */
    /** review-auto-open 1: and without its ticked apps (auto-open-plan §4), which you tick again on its save card. */
    fun skill(s: Skill, mine: Boolean): Skill = if (mine) s else s.copy(level = SkillLevel.OFF, opens = emptySet())

    /**
     * A helper this phone doesn't have: off, untrusted, not on the lock screen, no web search and no actions (turning it on
     * changes neither, so you choose them on its page), under [id] (RestoreSafety.settings gives it one no key on this phone
     * is stored under), unless the backup is yours.
     */
    fun newHelper(h: HelperConfig, mine: Boolean, id: String = h.id): HelperConfig =
        if (mine) h else h.copy(id = id, enabled = false, trust = false, locked = false, search = false, actions = false)

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2.8): someone else's file lands in Inbox with "From a backup that isn't yours"
     * (source BACKUP, the time it came here), waiting for you (Filing ASK, NOT_YOURS): Robot never files it on its own, and
     * the drawer it was in there is only offered ([app.heyrobot.core.files.Filing.suggest]). What would act or decide
     * for you is dropped: its tags and People links (a rule could learn from them), its brief reminders, "you named it", the
     * sending app; a field they set reads as Robot's reading, never "Set by you". Yours come back as saved.
     */
    fun file(e: app.heyrobot.core.files.FileEntry, mine: Boolean, now: Long): app.heyrobot.core.files.FileEntry =
        if (mine) e else FilesMerge.theirs(e, now)

    /**
     * 1.4, Robot Lists (lists-plan.md §6): someone else's list arrives as its own list, marked "From a backup that isn't
     * yours" (from: backup), with no role (so your Shopping still takes your voice adds; named by its role in [roleName]'s
     * language instead), every item and change by "backup"; ListsMerge never merges it into yours. Yours come back as saved.
     */
    fun list(l: app.heyrobot.core.lists.ListsList, mine: Boolean, roleName: (app.heyrobot.core.lists.ListRole) -> String): app.heyrobot.core.lists.ListsList =
        if (mine) l else app.heyrobot.core.lists.ListsMerge.theirs(l, roleName)

    /**
     * C10, Skills connections (plan §5.7; the main session's 26 Oct decision for hand-offs): a connection this phone doesn't
     * have, from a backup that isn't yours, arrives untrusted until you look at it: no first-use answers (every host asks
     * again), "A spoken yes may approve its changes" off, untested and with no home-or-internet fact ("Test it again": no
     * step runs until it passes here), at most 30 requests a minute, and a hand-off's app not pinned (pick it again: trust
     * is never restored). Its address, operations and your titles come as the file has them. Yours, as saved.
     */
    fun connection(c: app.heyrobot.core.connect.Connection, mine: Boolean): app.heyrobot.core.connect.Connection = if (mine) c else c.copy(
        answers = emptyMap(), spokenYes = false, tested = null, where = null,
        perMinute = minOf(c.perMinute, app.heyrobot.core.connect.Connection.DEFAULT_PER_MINUTE),
        handOff = c.handOff?.copy(signer = null))

    /**
     * "Is it headphones only you can hear?" is asked again (a car is never private). A car stays off until you turn it on:
     * connecting to one starts car mode, which sets your own Phone lock aside. [here]: the same device on this phone, whose
     * switch a car keeps.
     */
    fun device(d: RememberedDevice, mine: Boolean, here: RememberedDevice? = null): RememberedDevice = when {
        mine -> d
        d.role == DeviceRole.CAR -> d.copy(enabled = here?.takeIf { it.role == DeviceRole.CAR }?.enabled ?: false)
        else -> d.copy(private = null)
    }

    /**
     * The conversation with one content record, "Restored from a backup file", on its last turn (once): TurnRules.untrusted
     * is then true for it, and toPromptTurns frames every turn up to that one ([Quoted.RESTORED]).
     */
    fun conversation(c: StoredConversation, mine: Boolean): StoredConversation {
        if (mine || c.turns.isEmpty()) return c
        // History saves a turn with messages in it with its records' kinds only, which would lose this record's words: the
        // last other turn carries it (a turn of messages is framed and content already).
        val at = c.turns.indexOfLast { !it.messages }.takeIf { it >= 0 } ?: c.turns.lastIndex
        if (Quoted.restoredUpTo(c.turns) >= at) return c
        return c.copy(turns = c.turns.mapIndexed { i, t -> if (i == at) t.copy(actions = t.actions + Quoted.restoredRecord()) else t })
    }
}
