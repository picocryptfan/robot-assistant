// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.BackupStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase

// Pure. The words for backup's codes, from core's catalog (core/i18n/BackupStrings.kt): the app renders
// them in the screen's language. The codes themselves (OpenProblem, PlanSkip, SkipWhy) are what logs keep.

object BackupWords {
    fun category(c: BackupCategory): Msg = when (c) {
        BackupCategory.SETTINGS -> BackupStrings.settings
        BackupCategory.PEOPLE -> BackupStrings.people
        BackupCategory.MEMORY -> BackupStrings.memory
        BackupCategory.HANDSFREE -> BackupStrings.handsfree
        BackupCategory.ROUTINES -> BackupStrings.routines
        BackupCategory.SKILLS -> BackupStrings.skills
        BackupCategory.PHONE_LOCK -> BackupStrings.phoneLock
        BackupCategory.NOTES -> BackupStrings.notes
        BackupCategory.HISTORY -> BackupStrings.history
        BackupCategory.TRANSCRIPTS -> BackupStrings.transcripts
        BackupCategory.KEYS -> BackupStrings.keys
        BackupCategory.VOICEPRINT -> BackupStrings.voiceprint
        BackupCategory.FILES -> BackupStrings.files
        BackupCategory.LISTS -> BackupStrings.lists
        BackupCategory.EVERYDAY -> BackupStrings.everyday
        BackupCategory.LINKS -> BackupStrings.links
        // C10: English until C11's French (the connections area); Connection keys is shown in Keys and private codes' row.
        BackupCategory.CONNECTIONS -> app.heyrobot.core.i18n.ConnStrings.title
        BackupCategory.CONN_KEYS -> app.heyrobot.core.i18n.ConnStrings.backupKeys
    }

    /** Why a file can't be opened or restored. */
    fun problem(p: OpenProblem): Phrase = when (p) {
        OpenProblem.NOT_A_BACKUP -> BackupStrings.notABackup()
        OpenProblem.NEWER_FORMAT -> BackupStrings.newerFormat()
        OpenProblem.KDF_REFUSED -> BackupStrings.kdfRefused()
        OpenProblem.WRONG_PASSPHRASE -> BackupStrings.wrongPassphrase()
        OpenProblem.CHANGED -> BackupStrings.changed()
        OpenProblem.INCOMPLETE -> BackupStrings.incomplete()
        OpenProblem.TOO_BIG -> BackupStrings.tooBig()
        OpenProblem.DAMAGED -> BackupStrings.damaged()
    }

    /**
     * Why [line]'s category won't be restored, or null when it will (or the line needs no words: not in the backup, not
     * chosen). [madeBy]: the version name of the Robot that made the backup, for "needs Robot 1.3 or later".
     */
    fun skip(line: CategoryPlan, madeBy: String): Phrase? {
        val c = Arg.Sub(category(line.category)())
        return when (line.skip) {
            null, PlanSkip.NOT_IN_BACKUP, PlanSkip.NOT_CHOSEN -> null
            PlanSkip.RECORDING -> BackupStrings.transcriptsRecording()
            PlanSkip.NEEDS_NEWER -> BackupStrings.categoryNewer("category" to c, "version" to madeBy)
            PlanSkip.REFUSED -> BackupStrings.categoryRefused("category" to c)
            PlanSkip.TOO_BIG -> BackupStrings.categoryTooBig("category" to c)
            PlanSkip.HISTORY_OFF -> BackupStrings.historyOff()
            PlanSkip.MEMORY_OFF -> BackupStrings.memoryOff()
            PlanSkip.NOT_YOURS -> BackupStrings.notYours()
            PlanSkip.NEEDS_CODE -> BackupStrings.needsLockCode()
            PlanSkip.VOICEPRINT_HERE -> BackupStrings.voiceprintHere()
            PlanSkip.FILES_UNREADABLE -> BackupStrings.filesUnreadableHere("page" to Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.files)))
            PlanSkip.FILES_TRAVEL -> BackupStrings.filesTravelHere("page" to Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.files)))
            PlanSkip.LISTS_UNREADABLE -> BackupStrings.listsUnreadableHere("page" to Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.lists)))
            PlanSkip.CONNECTIONS_UNREADABLE -> app.heyrobot.core.i18n.ConnStrings.backupUnreadableHere()
        }
    }

    /**
     * A part of the file skipped by name: a category this Robot knows needs a newer one; one it doesn't know is named by
     * [name], the file's own word for it in the screen's language (LS12, BackupManifest.name), else "part".
     */
    fun skipped(s: CategorySkip, madeBy: String, name: String? = null): Phrase = when {
        s.category == null -> CategoryNames.clean(name)?.let { BackupStrings.categoryNewer("category" to Arg.Sub(Phrase.plain(it)), "version" to madeBy) }
            ?: BackupStrings.categoryUnknown()
        s.why == SkipWhy.NEWER -> BackupStrings.categoryNewer("category" to Arg.Sub(category(s.category)()), "version" to madeBy)
        s.why == SkipWhy.TOO_BIG -> BackupStrings.categoryTooBig("category" to Arg.Sub(category(s.category)()))
        else -> BackupStrings.categoryRefused("category" to Arg.Sub(category(s.category)()))
    }

    /** LS12: [c]'s name in each language Robot has, for the manifest (BackupManifest.names). */
    fun names(c: BackupCategory): Map<String, String> =
        app.heyrobot.core.i18n.Lang.entries.associate { it.id to category(c)().render(app.heyrobot.core.i18n.Langs.of(it)) }

    /** 1.4: a backup over [BackupCaps.MAX_TOTAL] ([bytes] about its size), refused before anything is written. */
    fun tooBigToSave(bytes: Long): Phrase = BackupStrings.tooBigToSave("size" to Arg.Bytes(bytes), "max" to Arg.Bytes(BackupCaps.MAX_TOTAL))

    /** F20: a backup just written, whose [c] a restore would leave out. */
    fun readBackRefused(c: BackupCategory): Phrase = BackupStrings.readBackRefused("category" to Arg.Sub(category(c)()))

    /** F20: [c] holds more than a restore takes, refused before anything is written. */
    fun overCap(c: BackupCategory): Phrase = BackupStrings.overCap("category" to Arg.Sub(category(c)()))

    /** "There isn't enough free space: Robot needs about 1.2 GB more…" */
    fun noSpace(bytes: Long): Phrase = BackupStrings.noSpace("size" to Arg.Bytes(bytes))
}
