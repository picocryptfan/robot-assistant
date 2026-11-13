// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.content.Context
import android.os.ParcelFileDescriptor
import app.heyrobot.core.feedback.ReportFile
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.r2r.ReportSeal
import java.io.File

// THE :link PROCESS. RP5 (LinkIpcProtocol.SEAL_REPORT): a report's bytes from the main app, read whole from the pipe (at most
// ReportFile.MAX_PLAIN), checked as a report with the reference asked for, sealed (ReportSeal, libsignal) and written as
// cache/reports/RFB-XXXXXXXX.robotreport (a temporary file, then renamed). Nothing is kept here; no network.
internal object LinkReports {
    /** libsignal's HPKE (ReportSeal), the one sealer outside tests. */
    val LIBSIGNAL: (ByteArray) -> ReportFile.Sealer = { key -> ReportSeal.sealer(key) }

    /** Tests only: the app's unit tests load no libsignal, so they seal with a stand-in. */
    @androidx.annotation.VisibleForTesting
    internal var sealer: (ByteArray) -> ReportFile.Sealer = LIBSIGNAL

    fun seal(ctx: Context, key: ByteArray, ref: String, plain: ParcelFileDescriptor): Int? {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(plain).use { it.readNBytes(ReportFile.MAX_PLAIN + 1) }
        val r = (ReportFile.read(bytes) as? ReportFile.Read.Ok)?.report?.takeIf { it.ref == ref } ?: return null
        val sealed = try { ReportFile.seal(r, sealer(key)) } catch (_: Exception) { return null }
        val dir = File(ctx.cacheDir, LinkIpcProtocol.REPORT_DIR).apply { mkdirs() }
        val tmp = File(dir, "$ref.tmp") // l10n: not text (a file name)
        return try {
            tmp.writeBytes(sealed)
            if (tmp.renameTo(File(dir, ReportFile.name(ref)))) sealed.size else null
        } finally { tmp.delete() }
    }
}
