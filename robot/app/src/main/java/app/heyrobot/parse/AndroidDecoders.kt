// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.parse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.heyrobot.core.parse.Box
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ScanQuad
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// :FILES PROCESS ONLY. The platform's decoders behind FileParser's seam (files-1.4-plan.md §4.3), each one a thing F0b
// confirms in an isolated process on the phone: ImageDecoder (JPEG, PNG, WebP, HEIC: HEIC may need media services an
// isolated process can't reach), PdfRenderer and its text API (Android 15 and later), PdfDocument, and the memfd that
// PdfRenderer reads (a SharedMemory has no descriptor PdfRenderer can take). Every input is Robot's read-only region;
// nothing here opens a file by name, logs, or goes online. ProcessIsolationTest keeps these classes in app/parse/.

internal class AndroidDecoders : Decoders {
    /** The header's size was read: nothing more is decoded. */
    private class HeaderRead(val d: Dim) : Exception()

    override fun size(input: ByteBuffer): Dim? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { _, info, _ -> throw HeaderRead(Dim(info.size.width, info.size.height)) }
        null
    } catch (h: HeaderRead) {
        h.d.takeIf { it.width > 0 && it.height > 0 }
    }

    override fun decode(input: ByteBuffer, box: Box): Picture = decodeTo(input) { src -> ParseLimits.fit(src, box) }

    override fun decodePixels(input: ByteBuffer, maxPixels: Long): Picture = decodeTo(input) { src -> ParseLimits.fitPixels(src, maxPixels) }

    /**
     * The target size is set in the header's callback, before any pixel is decoded, from the size the decoder read (its
     * orientation applied); a size past the source caps is refused there. Software memory, sRGB, then 8 bits a channel.
     */
    private fun decodeTo(input: ByteBuffer, target: (Dim) -> Dim): Picture {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { d, info, _ ->
            val src = Dim(info.size.width, info.size.height)
            if (!ParseLimits.sourceOk(src)) throw TooLarge()
            val t = target(src)
            d.setTargetSize(t.width, t.height)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            d.isMutableRequired = false
            // No partial image: a cut or broken file throws (the default without a listener), it isn't shown half-read.
            d.setOnPartialImageListener(null)
        }
        return BitmapPicture(eightBit(bmp))
    }

    override fun pdf(input: ByteBuffer): PdfDoc {
        val pfd = memfd(input)
        val r = try {
            PdfRenderer(pfd)
        } catch (_: SecurityException) {
            pfd.close()
            throw Encrypted()
        } catch (t: Throwable) {
            pfd.close()
            throw t
        }
        return AndroidPdf(r, pfd)
    }

    override fun pdfWriter(): PdfWriter = AndroidPdfWriter()

    /**
     * [input] copied into a memfd made here, as PdfRenderer needs a seekable descriptor with a real size (F0b: memfd_create
     * in an isolated process). It holds only these bytes, lives only as long as the renderer, and is never a file on disk.
     */
    private fun memfd(input: ByteBuffer): ParcelFileDescriptor {
        val fd = Os.memfd_create("robot-pdf", OsConstants.MFD_CLOEXEC)  // l10n: not text (a memfd's name)
        try {
            val src = input.duplicate()
            val chunk = ByteArray(64 * 1024)
            while (src.hasRemaining()) {
                val n = minOf(chunk.size, src.remaining())
                src.get(chunk, 0, n)
                var off = 0
                while (off < n) off += Os.write(fd, chunk, off, n - off)
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    private companion object {
        /** Whatever config the decoder chose (F16 for some HDR or 16-bit images), as ARGB_8888. */
        fun eightBit(bmp: Bitmap): Bitmap {
            if (bmp.config == Bitmap.Config.ARGB_8888) return bmp
            val c = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            return c ?: throw IllegalStateException()
        }

        /**
         * What [write] wrote, or null past [max] bytes. The platform's native writers (Bitmap.compress, PdfDocument.writeTo)
         * swallow a stream's exception and stop, so [Capped.over] is what says so, never the exception alone.
         */
        fun capped(max: Int, write: (ByteArrayOutputStream) -> Boolean): ByteArray? {
            val out = Capped(max)
            val ok = try {
                write(out)
            } catch (_: Capped.Over) {
                false
            }
            if (out.over) return null
            check(ok)
            return out.toByteArray()
        }
    }

    /** A byte stream that stops at [max] bytes, and remembers that it did. */
    private class Capped(private val max: Int) : ByteArrayOutputStream() {
        class Over : RuntimeException()

        @Volatile var over = false
            private set

        override fun write(b: Int) {
            if (over || size() + 1 > max) stop()
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (over || size().toLong() + len > max) stop()
            super.write(b, off, len)
        }

        private fun stop(): Nothing {
            over = true
            throw Over()
        }
    }

    private class BitmapPicture(val bmp: Bitmap) : Picture {
        override val width: Int get() = bmp.width
        override val height: Int get() = bmp.height

        override fun scaled(box: Box): Picture {
            val t = ParseLimits.fit(Dim(width, height), box)
            return BitmapPicture(eightBit(Bitmap.createScaledBitmap(bmp, t.width, t.height, true)))
        }

        override fun straightened(quad: ScanQuad, size: Dim): Picture {
            val src = FloatArray(8) { if (it % 2 == 0) quad.x(it / 2, width) else quad.y(it / 2, height) }
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            val m = Matrix()
            check(m.setPolyToPoly(src, 0, dst, 0, 4))
            val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.WHITE)
                drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return BitmapPicture(out)
        }

        override fun jpeg(quality: Int, max: Int): ByteArray? {
            val flat = if (bmp.hasAlpha()) onWhite() else bmp
            try {
                return capped(max) { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            } finally {
                if (flat !== bmp) flat.recycle()
            }
        }

        override fun png(max: Int): ByteArray? = capped(max) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        override fun rgba(out: ByteBuffer) = bmp.copyPixelsToBuffer(out)

        override fun close() = bmp.recycle()

        private fun onWhite(): Bitmap {
            val c = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(c).apply {
                drawColor(Color.WHITE)
                drawBitmap(bmp, 0f, 0f, null)
            }
            return c
        }
    }

    private class AndroidPdf(private val r: PdfRenderer, private val pfd: ParcelFileDescriptor) : PdfDoc {
        override val pageCount: Int get() = r.pageCount

        override fun pageSize(i: Int): Dim = r.openPage(i).use { Dim(it.width, it.height) }

        override fun render(i: Int, size: Dim): Picture {
            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            r.openPage(i).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            return BitmapPicture(bmp)
        }

        /** Android 15 and later (PdfRenderer.Page.getTextContents); on Android 14 the pages are read as pictures instead. */
        override fun text(i: Int): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
            return r.openPage(i).use { p -> p.textContents.joinToString("\n") { it.text } }
        }

        override fun close() {
            try { r.close() } finally { pfd.close() }
        }
    }

    private class AndroidPdfWriter : PdfWriter {
        private val doc = PdfDocument()
        private var pages = 0

        override fun page(picture: Picture, points: Dim) {
            val bmp = (picture as BitmapPicture).bmp
            val page = doc.startPage(PdfDocument.PageInfo.Builder(points.width, points.height, ++pages).create())
            page.canvas.drawBitmap(bmp, null, Rect(0, 0, points.width, points.height), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
        }

        override fun finish(max: Int): ByteArray? = capped(max) {
            doc.writeTo(it)
            true
        }

        override fun close() = doc.close()
    }
}
