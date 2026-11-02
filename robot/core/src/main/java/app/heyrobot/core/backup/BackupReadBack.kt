// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

/**
 * F20: what Back up's check holds a backup to: not only that it reads back, but that a restore would take all of it. A
 * category the reader leaves out would otherwise say "saved and checked" and be missed only at the restore.
 */
object BackupReadBack {
    sealed class Result {
        data object Ok : Result()
        /** The file didn't read back as it was written. */
        data object Mismatch : Result()
        /** It read back, but a restore would leave [category] out. */
        data class Refused(val category: BackupCategory) : Result()
    }

    fun check(v: VerifiedBackup, written: Long): Result {
        if (v.entries != written) return Result.Mismatch
        val first = v.skipped.firstOrNull() ?: return Result.Ok
        return first.category?.let { Result.Refused(it) } ?: Result.Mismatch
    }

    /** The first category of [entries] (category and name) over what the reader takes, before anything is written; null: none. */
    fun overCap(entries: List<Pair<BackupCategory, String>>): BackupCategory? {
        val byCategory = entries.groupBy({ it.first }, { it.second })
        val transcripts = byCategory[BackupCategory.TRANSCRIPTS].orEmpty().mapTo(HashSet()) { BackupCategory.transcriptOf(it) }
        if (transcripts.size > BackupCaps.MAX_TRANSCRIPTS) return BackupCategory.TRANSCRIPTS
        return byCategory.entries.firstOrNull { (c, names) -> names.size > c.maxEntries }?.key
    }
}
