// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import app.heyrobot.core.RLog
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FilesForHelper
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.TravelMode
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseReply
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.read.FileRead
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilePixels
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.RobotFiles

// VOICE PROCESS. 1.3 point and read (point-and-read-plan.md, job P3): Read aloud on a Files page. The Files screen hands the
// panel the file's id and the page (core FileRead), never their content; here the file is opened behind Robot Files' gate
// (the phone unlocked, no phone lock, as travel mode shows it), parsed in :files alone, and its bytes wiped. A text layer comes
// back as words (no model); a page that is only pixels as a bitmap for PanelRead's turn. Nothing is written or logged.
object PanelFileRead {
    sealed interface Got {
        /** [private]: a private file (core FilesForHelper.private), answered as read_file's are (PanelFileAnswer.privateAnswer). */
        class Words(val text: String, val private: Boolean) : Got
        class Picture(val bmp: Bitmap, val private: Boolean) : Got
        data object Locked : Got
        data object Unreadable : Got
    }

    /** The page Files asked for, from Robot's own show alone (SRC_APP, through PanelLaunchActivity). */
    fun take(source: String?, args: Bundle?): FileRead.Page? =
        if (source == RobotVoiceService.SRC_APP) FileRead.parse(args?.getString(FileRead.ARG)) else null

    /** [p]'s words or pixels; [locked] is asked first. Off the main thread. */
    fun load(ctx: Context, p: FileRead.Page, locked: () -> Boolean): Got {
        if (locked() || RobotFiles.locked(ctx)) return Got.Locked
        val vault = RobotFiles.vault(ctx)
        // Unlocked, yet the index didn't load: a file that can't be read, not the unlock line.
        val idx = (vault.load() as? IndexLoad.Loaded)?.index ?: return Got.Unreadable
        val e = TravelMode.shown(idx, FilesSettingsStore.current(ctx)).entry(p.id) ?: return Got.Unreadable
        val private = FilesForHelper.private(e)
        if (e.damaged || e.kind == FileKind.DANGEROUS || e.from.size !in 1..ParseLimits.MAX_INPUT) return Got.Unreadable
        val link = FileParseLink(ctx)
        var bytes: ByteArray? = null
        try {
            // Inside the try: a file too large for :voice's memory (an OutOfMemoryError) is one that can't be read.
            val b = try { vault.open(e.id).use { it.readAll(ParseLimits.MAX_INPUT.toLong()) } } catch (_: FileException) { return Got.Unreadable }
            bytes = b
            if (!link.start()) return Got.Unreadable
            val out = (link.read(b, ParseRequest.TEXT) as? ParseResult.Ok)?.out
            FileRead.words(out?.text, out?.has(ParseReply.HAS_TEXT) == true, p.page, out?.pages ?: 0)?.let { return Got.Words(it, private) }
            val px = (link.pixels(b, thumb = false, page = p.page) as? ParseResult.Ok)?.out?.pixels ?: return Got.Unreadable
            return FilePixels.bitmap(px, thumb = false)?.let { Got.Picture(it, private) } ?: Got.Unreadable
        } catch (t: Throwable) {
            RLog.w("read_file", t) // l10n: not text (a log code)
            return Got.Unreadable
        } finally {
            bytes?.fill(0)
            link.end("read_file") // l10n: not text (a log code)
        }
    }
}
