// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import app.heyrobot.core.OneShotRules
import app.heyrobot.core.files.FileLimits

// Pure. Every cap and time of the isolated reader, :files (files-1.4-plan.md §2.5, §4.3; EST until F0b measures them on
// the phone). :files keeps to them, and FileParseLink checks every output against them again where it reads it: what
// :files returns is untrusted.

/** A size in pixels (or, for a PDF page, points). */
data class Dim(val width: Int, val height: Int) {
    val long: Int get() = maxOf(width, height)
    val short: Int get() = minOf(width, height)
    val pixels: Long get() = width.toLong() * height

    override fun toString(): String = "${width}x$height"  // l10n: not text (a size for tests and logs)
}

/** A box an output must fit, either way round: its long edge at most [long], its short edge at most [short]. */
data class Box(val long: Int, val short: Int) {
    init {
        require(long >= short && short >= ParseLimits.MIN_EDGE)
    }

    fun holds(d: Dim): Boolean = d.width >= 1 && d.height >= 1 && d.long <= long && d.short <= short
}

object ParseLimits {
    private const val MIB = 1024 * 1024

    /** What :files reads of one file (§2.5): a larger file is kept and named, not read. */
    const val MAX_INPUT = 64 * MIB

    /** A source image's declared size, refused from its header before anything is decoded: a decompression bomb ends here. */
    const val MAX_SOURCE_EDGE = 32_768
    /** 260 megapixels: a 200 MP photo still decodes (to a capped target size, §4.3). */
    const val MAX_SOURCE_PIXELS = 260_000_000L

    /** Pages rendered for filing (§2.5: receipts and bills), and the pages whose text layer is read. */
    const val READ_PAGES = 3
    const val TEXT_PAGES = 10
    /** Text out of :files, as UTF-8 bytes (§4.3), cut on a character's boundary. */
    const val MAX_TEXT = 64 * 1024
    /** A PDF's page count as reported: a larger count is reported as this, flagged. */
    const val MAX_PAGE_COUNT = 100_000

    /** A page's JPEG for :brain (§4.2): 1,024 px at most, and F4's check (OneShotRules: FF D8 FF, at most 4 MiB). */
    val PAGE = Box(1_024, 1_024)
    val MAX_PAGE_JPEG: Int = OneShotRules.MAX_IMAGE_BYTES
    /** The thumbnail (§2.2): 320 px, a sealed file of at most 64 KiB. */
    val THUMB = Box(320, 320)
    val MAX_THUMB_JPEG: Int = FileLimits.MAX_THUMB.toInt()

    /** Raw pixels for showing (D19): a page at most 1,600 by 2,400 (either way round), a thumbnail 320 by 320. */
    val VIEW_PAGE = Box(2_400, 1_600)
    val VIEW_THUMB = Box(320, 320)

    /** A stripped copy for sending: re-encoded from the pixels, at most 16 megapixels (EST: a larger photo is scaled down). */
    const val COPY_PIXELS = 16_777_216L
    const val MAX_COPY = MAX_INPUT

    /** Scanning (§3.2): the camera's photo decoded at most 3,200 px, a straightened page at most 2,400 px. */
    val SCAN_SOURCE = Box(3_200, 3_200)
    val SCAN_PAGE = Box(2_400, 2_400)
    const val MAX_SCAN_JPEG = 8 * MIB
    /** Pages in one scanned PDF, and the PDF's size. */
    const val MAX_SCAN_PAGES = 20
    const val MAX_PDF = MAX_INPUT
    /** A scanned page's pixels per inch in its PDF (so a 2,400 px page is 12 inches). */
    const val SCAN_DPI = 200
    /** PDF's largest page side, in points (200 inches). */
    const val MAX_PDF_POINTS = 14_400

    /** No output edge is ever thinner than this (an absurd page's aspect is clamped, never rendered 0 px wide). */
    const val MIN_EDGE = 16

    /** JPEG qualities: pages, scanned pages and copies; a thumbnail tries each of [THUMB_QUALITIES] until it fits. */
    const val PAGE_QUALITY = 85
    const val SCAN_QUALITY = 90
    const val COPY_QUALITY = 92
    val THUMB_QUALITIES = intArrayOf(80, 65, 50, 35)

    /** Binding and HELLO; one request in :files (the plan's 20 s watchdog), and the link's own wait, a little longer. */
    const val BIND_MS = 10_000L
    const val SERVICE_MS = 20_000L
    const val LINK_MS = 22_000L

    /** A source size :files may decode at all. */
    fun sourceOk(d: Dim): Boolean =
        d.width in 1..MAX_SOURCE_EDGE && d.height in 1..MAX_SOURCE_EDGE && d.pixels <= MAX_SOURCE_PIXELS

    /**
     * [src] scaled to fit [box] with its aspect (never larger than [src] unless [up], as a PDF page's vectors may be), each
     * edge at least [MIN_EDGE] and at most the box's: an extreme aspect is clamped, not rendered to nothing.
     */
    fun fit(src: Dim, box: Box, up: Boolean = false): Dim {
        require(src.width >= 1 && src.height >= 1)
        val landscape = src.width >= src.height
        val maxW = if (landscape) box.long else box.short
        val maxH = if (landscape) box.short else box.long
        var scale = minOf(maxW.toDouble() / src.width, maxH.toDouble() / src.height)
        if (!up) scale = minOf(scale, 1.0)
        val w = Math.round(src.width * scale).toInt().coerceIn(MIN_EDGE, maxW)
        val h = Math.round(src.height * scale).toInt().coerceIn(MIN_EDGE, maxH)
        return Dim(w, h)
    }

    /** [src] scaled down (never up) to at most [maxPixels], aspect kept, each edge at least [MIN_EDGE]. */
    fun fitPixels(src: Dim, maxPixels: Long): Dim {
        require(src.width >= 1 && src.height >= 1)
        if (src.pixels <= maxPixels) return src
        val scale = Math.sqrt(maxPixels.toDouble() / src.pixels)
        var w = maxOf(MIN_EDGE, (src.width * scale).toInt())
        var h = maxOf(MIN_EDGE, (src.height * scale).toInt())
        while (w.toLong() * h > maxPixels && (w > MIN_EDGE || h > MIN_EDGE)) {
            if (w >= h && w > MIN_EDGE) w-- else if (h > MIN_EDGE) h-- else w--
        }
        return Dim(w, h)
    }

    /** A scanned page's size in its PDF, in points, from its pixels at [SCAN_DPI]. */
    fun scanPoints(px: Dim): Dim = Dim(
        (px.width * 72L / SCAN_DPI).toInt().coerceIn(1, MAX_PDF_POINTS),
        (px.height * 72L / SCAN_DPI).toInt().coerceIn(1, MAX_PDF_POINTS))

    /** The output region a request's outputs go into (FileParseLink makes it this size, :files writes into it). */
    fun outCap(op: ParseOp, flags: Int): Int = when (op) {
        ParseOp.READ -> READ_PAGES * MAX_PAGE_JPEG + MAX_THUMB_JPEG
        ParseOp.PIXELS -> (if (flags and ParseRequest.VIEW_THUMB != 0) VIEW_THUMB else VIEW_PAGE).let { it.long * it.short * 4 }
        ParseOp.STRIP -> MAX_COPY
        ParseOp.STRAIGHTEN -> MAX_SCAN_JPEG
        ParseOp.SCAN_PDF -> MAX_PDF
    }
}
