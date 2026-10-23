// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import java.io.File

// MAIN PROCESS. Scanning's camera hand-off (files-1.4-plan.md §3.2, D4, F5): the system camera writes its photo into
// cache/scan/ through this provider (a write grant for that one photo, as the panel's camera does in :voice); the scan
// screen reads it into RAM and deletes it at once, and the folder is emptied when the screen opens and goes, and at the
// main app's start (after a crash). Nothing else is ever served, and nothing is decoded here: the photo goes to :files.

/** The main app's provider for the camera's photo (cache/scan only). Not exported; the grant is the camera's alone. */
class ScanProvider : androidx.core.content.FileProvider()

object ScanFiles {
    /** The manifest's `${applicationId}.scan`. */
    const val AUTHORITY = app.heyrobot.BuildConfig.APPLICATION_ID + ".scan"

    fun dir(ctx: Context): File = File(ctx.cacheDir, "scan")

    /** Where the camera writes the page's photo. */
    fun photo(ctx: Context): File = File(dir(ctx).apply { mkdirs() }, "page.jpg")

    /** Nothing of a scan stays on disk. */
    fun clear(ctx: Context) {
        dir(ctx).listFiles().orEmpty().forEach { it.delete() }
    }

    /** FX2 (files-1.4-review.md K3), from :voice: a photo the camera wrote more than 15 minutes ago (its scan long gone), deleted. */
    fun clearStale(ctx: Context, now: Long = System.currentTimeMillis()): Int =
        dir(ctx).listFiles().orEmpty().count { now - it.lastModified() > STALE_MS && it.delete() }

    /** A photo older than this is stale: the 15 minutes a copy handed out lives (FileOut.MAX_MS; FilesOutTest checks they agree). */
    const val STALE_MS = 15 * 60_000L
}
