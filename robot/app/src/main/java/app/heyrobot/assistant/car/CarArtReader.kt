// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.net.Uri
import android.util.Size
import androidx.core.graphics.drawable.toBitmap
import app.heyrobot.core.car.CarArt

// VOICE PROCESS. Album art for the car screen (docs/pm/handoff-car-mode-drive-fixes.md, item 1): the bitmaps a session's
// metadata holds, in order, then a content: address only if the app lets Robot open it; never http(s), so nothing goes on the
// network. At most 512 px, held in memory only while shown (CarArtHold), never logged, saved, cached or sent.

object CarArtReader {
    private val BITMAPS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    private val URIS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

    fun of(ctx: Context, m: MediaMetadata?): Bitmap? = try {
        m?.let { d -> CarArt.pick(BITMAPS.map { d.getBitmap(it) }, URIS.map { d.getString(it) }) { open(ctx, it) }?.let(::small) }
    } catch (_: Exception) { null }

    private fun small(b: Bitmap): Bitmap {
        val (w, h) = CarArt.scaled(b.width, b.height)
        return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** An address the media app named, opened as Robot, already small: never Robot's own files, and a failure is no picture. */
    private fun open(ctx: Context, address: String): Bitmap? {
        val uri = Uri.parse(address)
        if (uri.authority?.startsWith(ctx.packageName) != false) return null
        return ctx.contentResolver.loadThumbnail(uri, Size(CarArt.MAX_SIDE, CarArt.MAX_SIDE), null)
    }

    /** An app's own icon, [px] square, for the tile and the lists; null when Robot can't see it. */
    fun icon(ctx: Context, pkg: String, px: Int = ICON_PX): Bitmap? = try {
        ctx.packageManager.getApplicationIcon(pkg).toBitmap(px, px)
    } catch (_: Exception) { null }

    private const val ICON_PX = 192
}
