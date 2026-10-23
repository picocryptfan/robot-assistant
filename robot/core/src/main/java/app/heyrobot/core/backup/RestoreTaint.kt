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
// (memory's), shown in Settings › Memory and never applied (FilingRules.of).

object RestoreTaint {
    fun memory(i: MemoryItem, mine: Boolean): MemoryItem = if (mine) i else i.copy(imported = MemoryImported.CONTENT)

    fun person(p: Person, mine: Boolean): Person = if (mine) p else p.copy(confirmed = false)

    /** Off, until you turn it on after reading it ("Turn each on after reading it"). */
    fun routine(r: Routine, mine: Boolean): Routine = if (mine) r else r.copy(enabled = false)

    /** 1.1, Skills (plan §4): someone else's skill arrives off; turning it on in Settings sets Ask first. Yours, as saved. */
    fun skill(s: Skill, mine: Boolean): Skill = if (mine) s else s.copy(level = SkillLevel.OFF)

    /**
     * A helper this phone doesn't have: off, untrusted, not on the lock screen, under [id] (RestoreSafety.settings gives
     * it one no key on this phone is stored under), unless the backup is yours.
     */
    fun newHelper(h: HelperConfig, mine: Boolean, id: String = h.id): HelperConfig =
        if (mine) h else h.copy(id = id, enabled = false, trust = false, locked = false)

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2.8): someone else's file lands in Inbox with "From a backup that isn't yours"
     * (source BACKUP, the time it came here), waiting for you (Filing ASK, NOT_YOURS): Robot never files it on its own, and
     * the drawer it was in there is only offered ([app.heyrobot.core.files.Filing.suggest]). What would act or decide
     * for you is dropped: its tags and People links (a rule could learn from them), its brief reminders, "you named it", the
     * sending app; a field they set reads as Robot's reading, never "Set by you". Yours come back as saved.
     */
    fun file(e: app.heyrobot.core.files.FileEntry, mine: Boolean, now: Long): app.heyrobot.core.files.FileEntry =
        if (mine) e else FilesMerge.theirs(e, now)

    /** "Is it headphones only you can hear?" is asked again (a car is never private). */
    fun device(d: RememberedDevice, mine: Boolean): RememberedDevice =
        if (mine || d.role == DeviceRole.CAR) d else d.copy(private = null)

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
