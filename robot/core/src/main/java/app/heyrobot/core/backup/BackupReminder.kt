// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

// Pure. "Remind me to back up every month": the main app's daily job (UpdateJobService,
// job 4804) asks [BackupReminder.due] and shows a quiet notification, "Time to back up Robot" / "Your last backup was 34
// days ago". Robot never backs up by itself: it never keeps your passphrase.

object BackupReminder {
    const val DAY_MS = 86_400_000L
    /** A reminder once the last backup (or the switch turned on) is this many days old. */
    const val DAYS = 30
    /** And at most one reminder in this many days. */
    const val QUIET_DAYS = 7

    /**
     * Whether to remind now ([now], wall clock ms): the switch is [on], the later of the last backup ([lastBackup], null:
     * never) and when the switch was turned on ([onSince]) is [DAYS] days old, and no reminder showed in the last
     * [QUIET_DAYS] days ([lastShown]). A clock set back (a time in the future) reminds nothing.
     */
    fun due(now: Long, lastBackup: Long?, onSince: Long, lastShown: Long?, on: Boolean): Boolean {
        if (!on) return false
        val since = maxOf(lastBackup ?: 0L, onSince)
        if (since > now || now - since < DAYS * DAY_MS) return false
        if (lastShown != null && lastShown <= now && now - lastShown < QUIET_DAYS * DAY_MS) return false
        return true
    }

    /** Whole days since the last backup, for the notification's text; null when there was none. */
    fun daysSince(now: Long, lastBackup: Long?): Int? = lastBackup?.let { ((now - it).coerceAtLeast(0) / DAY_MS).toInt() }
}
