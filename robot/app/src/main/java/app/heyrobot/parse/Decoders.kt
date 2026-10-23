// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.parse

import app.heyrobot.core.parse.Box
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.ScanQuad
import java.nio.ByteBuffer

// :FILES PROCESS ONLY. The one seam between FileParser (what to read, every cap) and the platform's decoders
// (AndroidDecoders: ImageDecoder, PdfRenderer, PdfDocument, Bitmap, Canvas and Matrix). Each call is one of the things
// F0b checks on the phone in an isolated process; FileParserTest drives FileParser through a fake of it. Every input is a
// read-only buffer of Robot's own region, never a path: nothing here can open a file by name.

/** An image or page, decoded and held by the platform (an ARGB_8888 Bitmap in sRGB on the phone). */
internal interface Picture : AutoCloseable {
    val width: Int
    val height: Int

    /** A copy scaled down to fit [box] (never up); this one stays open. */
    fun scaled(box: Box): Picture

    /** The quadrilateral [quad] of this picture mapped onto a [size] page (Matrix.setPolyToPoly). */
    fun straightened(quad: ScanQuad, size: Dim): Picture

    /** As a JPEG at [quality] (flattened on white if it has transparency), or null when it would pass [max] bytes. */
    fun jpeg(quality: Int, max: Int): ByteArray?

    /** As a PNG, or null when it would pass [max] bytes. */
    fun png(max: Int): ByteArray?

    /** width × height × 4 bytes, RGBA as Bitmap.Config.ARGB_8888 stores them, written into [out] at its position. */
    fun rgba(out: ByteBuffer)

    override fun close()
}

/** An open PDF (PdfRenderer on the phone). */
internal interface PdfDoc : AutoCloseable {
    val pageCount: Int

    /** Page [i]'s size in points. */
    fun pageSize(i: Int): Dim

    /** Page [i] drawn on white at [size] pixels. */
    fun render(i: Int, size: Dim): Picture

    /** Page [i]'s text layer, or null when the platform has no text API (Android 14 without PdfRenderer's extension). */
    fun text(i: Int): String?

    override fun close()
}

/** A PDF being written (PdfDocument on the phone): one page per picture. */
internal interface PdfWriter : AutoCloseable {
    /** [picture] drawn over a whole page of [points]. */
    fun page(picture: Picture, points: Dim)

    /** The PDF, or null when it would pass [max] bytes. */
    fun finish(max: Int): ByteArray?

    override fun close()
}

/** A source image's header passed a cap (ParseLimits.sourceOk): nothing was decoded. */
internal class TooLarge : Exception()

/** A PDF that needs a password. */
internal class Encrypted : Exception()

internal interface Decoders {
    /** The image's size as the platform decoder reads its header (HEIC's and HEIF's; JPEG, PNG and WebP are read in core). */
    fun size(input: ByteBuffer): Dim?

    /**
     * The image decoded to fit [box]: the target size set from the header before any pixel is decoded (TooLarge past
     * ParseLimits' source caps), its orientation applied, sRGB, 8 bits a channel, software memory.
     */
    fun decode(input: ByteBuffer, box: Box): Picture

    /** The image decoded to at most [maxPixels] pixels, the same way. */
    fun decodePixels(input: ByteBuffer, maxPixels: Long): Picture

    /** The PDF in [input] (Encrypted when it needs a password). */
    fun pdf(input: ByteBuffer): PdfDoc

    fun pdfWriter(): PdfWriter
}
