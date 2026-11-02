// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.content.Context
import android.graphics.Bitmap
import app.heyrobot.assistant.Images
import app.heyrobot.core.RLog
import app.heyrobot.core.ask.AskRefusal
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilePixels

// VOICE PROCESS. Ask Robot's shared photo (docs/plans/everyday-set-plan.md §7.5; E7): an image's bytes, read once from the
// sending app, decoded only in Robot Files' isolated reader (:files, FileParseLink), never in :voice. A deliberate
// difference from the plan's "decoded as a picked photo is (Images.decode)": a picked photo comes through Android's picker,
// a shared one from whichever app is in front, and Robot Files' rule (files-1.4-plan.md D5, D19) is that bytes from
// another app are decoded in :files alone. :files gives back what the panel needs: the picture's raw pixels, encoded here for
// the model at Images.MAX_EDGE's size, and the thumbnail's (PIXELS, VIEW_THUMB); both become
// a bitmap without a decoder (FilePixels). Blocks: never on the main thread.

object SharedPhoto {
    sealed class Read {
        class Ok(val jpeg: ByteArray, val thumb: Bitmap) : Read()
        class Refused(val why: AskRefusal) : Read()
    }

    fun read(ctx: Context, bytes: ByteArray, link: FileParseLink = FileParseLink(ctx)): Read {
        // Only an image :files decodes goes there at all (a PDF or text claimed as "image/*" is refused here, unread).
        val head = bytes.copyOf(minOf(bytes.size, FileSniff.PREFIX))
        if (FileSniff.sniff(head, bytes.size.toLong()).group != FileGroup.IMAGE) {
            link.end("ask_photo_type")
            return Read.Refused(AskRefusal.WRONG_TYPE)
        }
        try {
            if (!link.start()) return Read.Refused(AskRefusal.UNREADABLE)
            // The JPEG the model sees is encoded here from :files' pixels, as a filed page's and an attached picture's are: a
            // JPEG :files made never reaches :brain (F16).
            val full = when (val page = link.pixels(bytes, thumb = false)) {
                is ParseResult.Ok -> page.out.pixels?.let { FilePixels.bitmap(it, thumb = false) }
                is ParseResult.Refused -> return Read.Refused(refusal(page.status))
                is ParseResult.Failed -> null
            } ?: return Read.Refused(AskRefusal.UNREADABLE)
            val px = (link.pixels(bytes, thumb = true) as? ParseResult.Ok)?.out?.pixels ?: return Read.Refused(AskRefusal.UNREADABLE)
            val thumb = FilePixels.bitmap(px, thumb = true) ?: return Read.Refused(AskRefusal.UNREADABLE)
            return Read.Ok(Images.jpeg(full), thumb)
        } catch (t: Throwable) {
            RLog.w("ask_photo", t)
            return Read.Refused(AskRefusal.UNREADABLE)
        } finally {
            link.end("ask_photo")
        }
    }

    /** :files' refusal as Ask Robot's: not an image it decodes, past a cap, or unreadable. */
    private fun refusal(s: ParseStatus): AskRefusal = when (s) {
        ParseStatus.TOO_LARGE, ParseStatus.OUTPUT_TOO_LARGE -> AskRefusal.TOO_LARGE
        ParseStatus.DANGEROUS, ParseStatus.NOT_READ, ParseStatus.EMPTY, ParseStatus.BAD_REQUEST -> AskRefusal.WRONG_TYPE
        else -> AskRefusal.UNREADABLE
    }
}
