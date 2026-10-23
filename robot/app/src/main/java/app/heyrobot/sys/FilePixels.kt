// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.graphics.Bitmap
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParsedPixels
import java.nio.ByteBuffer

// MAIN AND VOICE PROCESSES. The one place :files' raw pixels become a Bitmap (files-1.4-plan.md D19): the Files screens
// and the panel show a file's pages and thumbnails from here, and no decoder runs outside :files. Nothing is decoded:
// RGBA bytes are copied into a bitmap of their size, once the caps are checked again.

object FilePixels {
    /** [p] as a bitmap, or null when it isn't within a thumbnail's box ([thumb]) or a page's, or its bytes aren't exactly its size. */
    fun bitmap(p: ParsedPixels, thumb: Boolean): Bitmap? {
        val box = if (thumb) ParseLimits.VIEW_THUMB else ParseLimits.VIEW_PAGE
        if (!box.holds(Dim(p.width, p.height)) || p.rgba.size.toLong() != p.width.toLong() * p.height * 4) return null
        val b = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        b.copyPixelsFromBuffer(ByteBuffer.wrap(p.rgba))
        return b
    }
}
