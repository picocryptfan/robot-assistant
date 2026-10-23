// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.messages.MessagesFile

// Pure. Ids that point at each other, and values that belong to one phone. A restore can give People,
// memory items and devices new ids (Add mode); what points at them follows where each landed, and a link to nobody is
// dropped. The settings that name something of this phone (its calendar, its voiceprint, the model terms and notices
// you saw here) keep this phone's values, whatever the file says.

object RestoreRefs {
    /**
     * settings.json's fields that belong to this phone ([FieldRule.PHONE]): the calendar new events go to (an id of this
     * phone's calendars), whether a voiceprint is enrolled here (none is ever restored), the model terms you confirmed
     * before a download here, the reminder before a recording, the one-time safety notice and Home's notice about the
     * menus' language (1.1: Android keeps the per-app language, which a backup doesn't carry).
     */
    fun phoneSpecific(phone: RobotSettings, s: RobotSettings): RobotSettings = s.copy(
        eventCalendar = phone.eventCalendar,
        meVoiceprint = phone.meVoiceprint,
        convReminderAt = phone.convReminderAt,
        safetyNotice = phone.safetyNotice,
        confirmedTerms = phone.confirmedTerms,
        menusNoticeSeen = phone.menusNoticeSeen,
    )

    /** 1.1: [s] once :voice restored your voiceprint ([restored]): it is enrolled here now. */
    fun voiceprint(s: RobotSettings, restored: Boolean): RobotSettings = if (restored && !s.meVoiceprint) s.copy(meVoiceprint = true) else s

    /** messages.json's People ids ([MessagesFile.noCard], [MessagesFile.replyLang], [MessagesFile.announcePeople]) where each landed. */
    fun messages(f: MessagesFile, people: (String) -> String?): MessagesFile = f.copy(
        noCard = f.noCard.mapNotNull(people).toSet(),
        replyLang = f.replyLang.mapNotNull { (id, l) -> people(id)?.let { it to l } }.toMap(),
        announcePeople = f.announcePeople.mapNotNull(people).toSet(),
    )

    /** A restored conversation's memory links ([memory]: where each item landed); a link to nothing is dropped. */
    fun conversation(c: StoredConversation, memory: (String) -> String?): StoredConversation =
        c.copy(turns = c.turns.map { t -> if (t.memoryIds.isEmpty()) t else t.copy(memoryIds = t.memoryIds.mapNotNull(memory).distinct()) })

    /**
     * A routine whose trigger is a remembered headset or car, pointed at where that device landed ([devices]: "dev-3" →
     * "dev-7"). Null when its device didn't land: the caller restores the routine off, with a line saying why.
     */
    fun routine(r: Routine, devices: (String) -> String?): Routine? {
        val t = r.trigger as? RoutineTrigger.Device ?: return r
        val id = t.deviceId ?: return r
        val landed = devices(id) ?: return null
        return r.copy(trigger = t.copy(deviceId = landed))
    }

    /**
     * 1.4: a restored file's People links where each landed (a link to nobody dropped), its bytes checked whole (not
     * damaged), and no thumbnail: none is carried, so it is made again (FilesBackup.RETHUMB).
     */
    fun file(e: app.heyrobot.core.files.FileEntry, people: (String) -> String?): app.heyrobot.core.files.FileEntry =
        e.copy(people = e.people.mapNotNull(people).distinct(), thumb = false, damaged = false)

    /** Where each id landed, for Replace: the same id when it is still there ([kept]), else nothing. */
    fun same(kept: Set<String>): (String) -> String? = { id -> id.takeIf { it in kept } }
}
