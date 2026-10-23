// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.parse.ParseFixtures.bytes
import app.heyrobot.core.parse.ParseFixtures.ftyp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The type from the first bytes only, with a fixed table (files-1.4-plan.md §3.1, §4.1 step 2). */
class FileSniffTest {
    private fun sniff(b: ByteArray, size: Long = b.size.toLong()) = FileSniff.sniff(b, size)
    private fun text(s: String) = sniff(s.toByteArray(Charsets.UTF_8))

    @Test fun theTableIsFixedAndEveryTypeFitsAFilesProvenance() {
        assertEquals(FileType.entries.size, FileType.entries.map { it.code }.toSet().size)
        assertTrue(FileType.entries.all { it.code in 0..255 })
        for (t in FileType.entries) {
            assertTrue(t.mime, FileIndexCodec.MIME.matches(t.mime) && t.mime.length <= FileIndexCodec.MAX_TYPE)
            assertEquals(t, FileType.of(t.code))
        }
        assertEquals(null, FileType.of(200))
    }

    @Test fun aZeroByteFileIsEmptyAndNothingElse() {
        assertEquals(FileType.EMPTY, sniff(ByteArray(0), 0))
        assertEquals(FileType.EMPTY, sniff(ByteArray(0), 10))
        assertEquals(FileType.EMPTY, sniff(bytes(0xFF, 0xD8, 0xFF), 0))
    }

    @Test fun imagesByTheirMagic() {
        assertEquals(FileType.JPEG, sniff(ParseFixtures.jpeg(640, 480)))
        assertEquals(FileType.PNG, sniff(ParseFixtures.png(10, 10)))
        assertEquals(FileType.WEBP, sniff(ParseFixtures.webp(ParseFixtures.vp8(10, 10))))
        assertEquals(FileType.GIF, sniff("GIF89a".toByteArray() + ByteArray(10)))
        assertEquals(FileType.GIF, sniff("GIF87a".toByteArray() + ByteArray(10)))
        assertEquals(FileType.TIFF, sniff(bytes(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)))
        assertEquals(FileType.TIFF, sniff(bytes(0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8)))
        val bmp = bytes(0x42, 0x4D, 0x46, 0, 0, 0, 0, 0, 0, 0, 0x36, 0, 0, 0, 40, 0, 0, 0) + ByteArray(40)
        assertEquals(FileType.BMP, sniff(bmp))
    }

    @Test fun isoMediaByItsBrands() {
        assertEquals(FileType.HEIC, sniff(ftyp("heic", "mif1", "heic")))
        assertEquals(FileType.HEIC, sniff(ftyp("heix")))
        assertEquals(FileType.HEIC, sniff(ftyp("mif1", "mif1", "heic")))
        assertEquals(FileType.AVIF, sniff(ftyp("avif", "mif1")))
        assertEquals(FileType.AVIF, sniff(ftyp("mif1", "avif")))
        assertEquals(FileType.HEIF, sniff(ftyp("mif1", "miaf")))
        assertEquals(FileType.MP4, sniff(ftyp("isom", "iso2", "mp41")))
        assertEquals(FileType.MP4, sniff(ftyp("mp42")))
        assertEquals(FileType.QUICKTIME, sniff(ftyp("qt  ")))
        assertEquals(FileType.THREE_GP, sniff(ftyp("3gp5")))
        assertEquals(FileType.M4A, sniff(ftyp("M4A ")))
        assertEquals(FileType.UNKNOWN, sniff(ftyp("zzzz")))
        // A box size that isn't an ftyp's (too small, too large, not whole brands): unknown, never guessed.
        for (size in listOf(8, 15, 18, 1_000_000)) {
            val b = ftyp("heic", "mif1")
            b[0] = (size ushr 24).toByte(); b[1] = (size ushr 16).toByte(); b[2] = (size ushr 8).toByte(); b[3] = size.toByte()
            assertEquals("$size", FileType.UNKNOWN, sniff(b))
        }
    }

    @Test fun pdfOnlyFromItsFirstByte() {
        assertEquals(FileType.PDF, sniff("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1)))
        // Anything before the header makes it something else (here text): pdfium would look further, Robot doesn't.
        assertEquals(FileType.TEXT, text("  %PDF-1.4\n"))
    }

    @Test fun mediaIsKeptNotRead() {
        assertEquals(FileType.WEBM, sniff(bytes(0x1A, 0x45, 0xDF, 0xA3) + "....webm....".toByteArray()))
        assertEquals(FileType.MATROSKA, sniff(bytes(0x1A, 0x45, 0xDF, 0xA3) + "..matroska..".toByteArray()))
        assertEquals(FileType.AVI, sniff("RIFF".toByteArray() + ByteArray(4) + "AVI LIST".toByteArray()))
        assertEquals(FileType.WAV, sniff("RIFF".toByteArray() + ByteArray(4) + "WAVEfmt ".toByteArray()))
        assertEquals(FileType.MP3, sniff("ID3".toByteArray() + ByteArray(10)))
        assertEquals(FileType.MP3, sniff(bytes(0xFF, 0xFB, 0x90, 0x00)))
        assertEquals(FileType.OGG, sniff("OggS".toByteArray() + ByteArray(10)))
        assertEquals(FileType.FLAC, sniff("fLaC".toByteArray() + ByteArray(10)))
        for (t in listOf(FileType.WEBM, FileType.MP4, FileType.MP3)) assertTrue(t.group == FileGroup.VIDEO || t.group == FileGroup.AUDIO)
    }

    @Test fun whatCouldRunSomethingIsDangerous() {
        val cases = mapOf(
            bytes(0x50, 0x4B, 0x03, 0x04) + "AndroidManifest.xml".toByteArray() to FileType.ZIP,
            bytes(0x50, 0x4B, 0x03, 0x04) + "[Content_Types].xml".toByteArray() to FileType.ZIP,
            bytes(0x50, 0x4B, 0x05, 0x06) + ByteArray(18) to FileType.ZIP,
            bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) to FileType.OLE,
            "{\\rtf1\\ansi".toByteArray() to FileType.RTF,
            bytes(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1) to FileType.ELF,
            "dex\n035\u0000".toByteArray() to FileType.DEX,
            "MZ\u0090\u0000".toByteArray() to FileType.PE,
            bytes(0xCF, 0xFA, 0xED, 0xFE) to FileType.MACHO,
            bytes(0xCA, 0xFE, 0xBA, 0xBE) to FileType.MACHO,
            "Rar!".toByteArray() + bytes(0x1A, 0x07, 0x01, 0x00) to FileType.RAR,
            bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) to FileType.SEVEN_Z,
            bytes(0x1F, 0x8B, 0x08) to FileType.GZIP,
            "BZh91AY".toByteArray() to FileType.BZIP2,
            bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) to FileType.XZ,
            bytes(0x28, 0xB5, 0x2F, 0xFD) to FileType.ZSTD,
            "MSCF".toByteArray() + ByteArray(8) to FileType.CAB,
            ByteArray(257) + "ustar\u000000".toByteArray() to FileType.TAR,
            "#!/system/bin/sh\nrm -rf /\n".toByteArray() to FileType.SCRIPT,
            "<?php system(\$_GET['c']); ?>".toByteArray() to FileType.SCRIPT,
            "<!DOCTYPE html><title>Receipt</title>".toByteArray() to FileType.HTML,
            "﻿<html><body>Total 12.00</body></html>".toByteArray() to FileType.HTML,
            "Receipt\n<script>fetch('x')</script>".toByteArray() to FileType.HTML,
            "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".toByteArray() to FileType.SVG,
        )
        for ((b, t) in cases) {
            assertEquals(String(b, Charsets.ISO_8859_1), t, sniff(b))
            assertEquals(FileGroup.DANGEROUS, t.group)
        }
    }

    /** A file that lies about its type: the name and the sender's MIME type are never read, only the bytes. */
    @Test fun aFileThatLiesAboutItsTypeIsWhatItsBytesSay() {
        // "receipt.pdf" that is a ZIP (or an APK): dangerous. "photo.jpg" that is a PNG: a PNG. "notes.txt" that is an ELF.
        assertEquals(FileType.ZIP, sniff(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0) + "receipt.pdf".toByteArray()))
        assertEquals(FileType.PNG, sniff(ParseFixtures.png(4, 4)))
        assertEquals(FileType.ELF, sniff(bytes(0x7F, 0x45, 0x4C, 0x46) + "text".toByteArray()))
        // A text file that starts like a PDF's comment but isn't one: text.
        assertEquals(FileType.TEXT, text("%PD receipt total 12.00"))
        // Two letters alone don't make a bitmap: a note that starts "BM" is text.
        assertEquals(FileType.TEXT, text("BMW service invoice, total 412.00 $"))
    }

    @Test fun textIsStrictUtf8() {
        assertEquals(FileType.TEXT, text("Costco Wholesale\nTOTAL 84.12\n"))
        assertEquals(FileType.TEXT, text("Reçu · Facture n° 12 — TPS 5 % · TVQ 9,975 %\r\n\tmerci 🎉\u000C"))
        assertEquals(FileType.TEXT, text("﻿notes"))
        assertEquals(FileType.TEXT, text(""""json": {"a": 1}"""))
        // NUL, other control characters, Latin-1, overlong forms, surrogates, past U+10FFFF: not text.
        assertEquals(FileType.UNKNOWN, sniff("abc\u0000def".toByteArray()))
        assertEquals(FileType.UNKNOWN, sniff("abc\u0007def".toByteArray()))
        assertEquals(FileType.UNKNOWN, sniff("reçu".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(FileType.UNKNOWN, sniff(bytes(0x61, 0xC0, 0xAF)))
        assertEquals(FileType.UNKNOWN, sniff(bytes(0x61, 0xE0, 0x80, 0xAF)))
        assertEquals(FileType.UNKNOWN, sniff(bytes(0x61, 0xED, 0xA0, 0x80)))
        assertEquals(FileType.UNKNOWN, sniff(bytes(0x61, 0xF4, 0x90, 0x80, 0x80)))
        assertEquals(FileType.UNKNOWN, sniff(bytes(0x61, 0x80)))
        // UTF-16 isn't read in 1.4.
        assertEquals(FileType.UNKNOWN, sniff("﻿hi".toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun aCharacterCutByThePrefixsEndIsTextOnlyWhenTheFileGoesOn() {
        val s = "a".repeat(FileSniff.PREFIX - 1) + "é"
        val b = s.toByteArray(Charsets.UTF_8)
        assertEquals(FileSniff.PREFIX + 1, b.size)
        val prefix = b.copyOf(FileSniff.PREFIX)
        assertEquals(FileType.TEXT, sniff(prefix, b.size.toLong()))
        assertEquals(FileType.UNKNOWN, sniff(prefix, prefix.size.toLong()))
        // Only the first PREFIX bytes are looked at, whatever is passed.
        assertEquals(FileType.TEXT, sniff("x".repeat(FileSniff.PREFIX).toByteArray() + bytes(0, 0, 0), 9_999_999))
    }

    @Test fun onlyThreeGroupsAreEverRead() {
        val read = FileType.entries.filter { it.group in setOf(FileGroup.IMAGE, FileGroup.PDF, FileGroup.TEXT) }
        assertEquals(listOf(FileType.JPEG, FileType.PNG, FileType.WEBP, FileType.HEIC, FileType.HEIF, FileType.PDF, FileType.TEXT), read)
    }

    /** A ZIP's end of central directory record with [comment] bytes of comment after it, as an archive tool writes it. */
    private fun zipEnd(comment: Int = 0): ByteArray = byteArrayOf(0x50, 0x4B, 5, 6) + ByteArray(16) + byteArrayOf((comment and 0xFF).toByte(), (comment shr 8).toByte()) +
        ByteArray(comment) { 'c'.code.toByte() }

    /**
     * FX2 (files-1.4-review.md finding 14): the first 4 KiB say JPEG or PDF, but a ZIP's end record closes the file: an
     * archive tool opens it as one, so it is a ZIP (DANGEROUS). Its signature alone, not reaching the end, isn't one.
     */
    @Test fun aZipAppendedToAJpegOrAPdfIsAZip() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100_000) { (it % 251).toByte() }
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(5_000)
        for (head in listOf(jpeg, pdf)) for (comment in listOf(0, 12, 65_535)) {
            val f = head + ByteArray(300) + zipEnd(comment)
            val type = FileSniff.sniff(f, f.size.toLong())
            assertTrue(type.group == FileGroup.IMAGE || type == FileType.PDF)
            assertEquals(FileType.ZIP, FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(f), type))
            // Streamed in chunks of any size, as intake reads it.
            for (chunk in listOf(1, 7, 4096, 70_000)) {
                val w = FileSniff.Watch()
                var at = 0
                while (at < f.size) { val n = minOf(chunk, f.size - at); w.feed(f, at, n); at += n }
                assertEquals("$chunk", FileType.ZIP, w.hidden(type))
            }
        }
        // The signature in a picture's bytes, not closing the file, or with a comment too long or too short: not a ZIP.
        assertEquals(null, FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(jpeg + zipEnd(0) + ByteArray(40)), FileType.JPEG))
        assertEquals(null, FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(jpeg + zipEnd(10).dropLast(1).toByteArray()), FileType.JPEG))
        assertEquals(null, FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(jpeg), FileType.JPEG))
        // Only a picture or a PDF is looked at so: a video's end is its own.
        assertEquals(null, FileSniff.hidden(FileType.MP4, ByteArray(10) + zipEnd(), null))
    }

    /** FX2 (finding 14): markup past a text's first 4 KiB makes it HTML or SVG, as in its first 4 KiB; case aside, across chunks. */
    @Test fun markupDeepInATextRuns() {
        val plain = "Groceries: milk, eggs, bread.\n".repeat(400).toByteArray()
        assertEquals(FileType.TEXT, FileSniff.sniff(plain, plain.size.toLong() + 100))
        assertEquals(null, FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(plain), FileType.TEXT))
        for ((tag, type) in listOf("<SCRIPT>alert(1)</script>" to FileType.HTML, "<svg onload=x>" to FileType.SVG, "<!DOCTYPE html>" to FileType.HTML)) {
            val f = plain + tag.toByteArray()
            assertEquals(FileType.TEXT, FileSniff.sniff(f.copyOf(FileSniff.PREFIX), f.size.toLong()))
            assertEquals(tag, type, FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(f), FileType.TEXT))
            // Cut across two chunks.
            val w = FileSniff.Watch()
            val cut = plain.size + 3
            w.feed(f, 0, cut)
            w.feed(f, cut, f.size - cut)
            assertEquals(tag, type, w.hidden(FileType.TEXT))
        }
        // A picture's bytes that spell markup are a picture's.
        assertEquals(null, FileSniff.hidden(FileType.JPEG, ByteArray(0), FileType.HTML))
    }
}
