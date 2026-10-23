// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.parse.Outgoing
import app.heyrobot.core.parse.ParseLimits

// Pure. 1.4, Robot Files: a copy of a file handed to another app (files-1.4-plan.md §5.2, §6.4; job F7): Share, or Open
// with another app. Never the sealed file: a temporary copy the main app writes in its own cache folder for one hand-off,
// served by its own FileProvider with a one-time read grant, revoked and deleted when you come back to Robot, at
// screen-off, or after [MAX_MS] (sys/FilesOut.kt). Which copy goes is Settings › Files' "Strip location from photos I send"
// (core parse Outgoing), for Share and Open alike: an app that opens a photo gets it as much as one it is shared to. What
// the copy is called is decided here, from Robot's name for the file and the type sniffed from its bytes (never the
// sender's name or claim), with characters a file name can't hold replaced.

/** What happens to a file before it leaves (Share or Open). */
sealed class OutPlan {
    /** It goes as [copy] (ORIGINAL or STRIPPED), named [name], typed [mime]. */
    data class Go(val copy: OutgoingCopy, val name: String, val mime: String) : OutPlan()
    /** Robot can't remove its location (a video, an image it doesn't decode, one too big for :files): ask first. */
    data class AskFirst(val name: String, val mime: String) : OutPlan()
}

object FileOut {
    /** A copy handed out lasts at most this long (15 minutes), even if you never come back to Robot. */
    const val MAX_MS = 15 * 60_000L

    /** Extensions a name may already end with, replaced by the type's own. */
    private val KNOWN: Set<String> by lazy { FileType.entries.mapNotNull(::extension).toSet() + setOf("jpeg", "tif", "htm", "html", "bin") } // l10n: not text (file extensions)

    /** A copy's file name, at most this many characters before its extension. */
    private const val MAX_BASE = 120

    /** The type Robot sniffed for [e] (its provenance's), or null when it has none. */
    fun typeOf(e: FileEntry): FileType? = e.from.type?.let { t -> FileType.entries.firstOrNull { it.mime == t } }

    /** What goes for [e] of [type] with [stripLocation] on or off. */
    fun plan(e: FileEntry, type: FileType, stripLocation: Boolean): OutPlan {
        var copy = Outgoing.copy(stripLocation, type)
        // :files reads at most 64 MiB: a larger image can't be re-encoded, so its location can't be removed.
        if (copy == OutgoingCopy.STRIPPED && e.from.size > ParseLimits.MAX_INPUT) copy = OutgoingCopy.CANT_STRIP
        val shownType = if (copy == OutgoingCopy.STRIPPED && type != FileType.PNG) FileType.JPEG else type
        val name = name(e.name, shownType)
        val mime = mime(shownType)
        return if (copy == OutgoingCopy.CANT_STRIP) OutPlan.AskFirst(name, mime) else OutPlan.Go(copy, name, mime)
    }

    /**
     * What another app is told the copy is: the sniffed type's, except for a file that could install or run something
     * (an "application/octet-stream" no installer takes on its own) and a type Robot doesn't know.
     */
    fun mime(type: FileType): String = when (type.group) {
        FileGroup.DANGEROUS, FileGroup.UNKNOWN, FileGroup.EMPTY -> FileType.UNKNOWN.mime
        else -> type.mime
    }

    /** A known type's extension ("pdf"), or null (dangerous and unknown files keep the name they came with). */
    fun extension(type: FileType): String? = when (type) {
        FileType.JPEG -> "jpg"
        FileType.PNG -> "png"
        FileType.WEBP -> "webp"
        FileType.HEIC -> "heic"
        FileType.HEIF -> "heif"
        FileType.PDF -> "pdf"
        FileType.TEXT -> "txt"
        FileType.GIF -> "gif"
        FileType.AVIF -> "avif"
        FileType.BMP -> "bmp"
        FileType.TIFF -> "tiff"
        FileType.MP4 -> "mp4"
        FileType.QUICKTIME -> "mov"
        FileType.THREE_GP -> "3gp"
        FileType.WEBM -> "webm"
        FileType.MATROSKA -> "mkv"
        FileType.AVI -> "avi"
        FileType.MP3 -> "mp3"
        FileType.M4A -> "m4a"
        FileType.OGG -> "ogg"
        FileType.FLAC -> "flac"
        FileType.WAV -> "wav"
        else -> null
    }

    /**
     * The copy's file name: [name] (Robot's, or yours) with characters no file name may hold made "_", cut to 120
     * characters, and the type's extension in place of any it had ("receipt.PDF" → "receipt.pdf", "IMG_1.HEIC" stripped →
     * "IMG_1.jpg"). A type Robot doesn't name keeps the name as it is. Never empty, never a dot file.
     */
    fun name(name: String, type: FileType): String {
        val ext = extension(type)
        var base = FileIndexCodec.cleanName(name).map { c -> if (c in "/\\:*?\"<>|" || c.code < 0x20) '_' else c }.joinToString("").trim()
        // An extension it already had goes (only a known one: "84.12" in "Costco 84.12" stays).
        if (ext != null) {
            val dot = base.lastIndexOf('.')
            if (dot > 0 && base.substring(dot + 1).lowercase() in KNOWN) base = base.substring(0, dot)
        }
        base = base.trim('.', ' ')
        if (base.length > MAX_BASE) base = base.substring(0, if (Character.isHighSurrogate(base[MAX_BASE - 1])) MAX_BASE - 1 else MAX_BASE)
        base = base.trim('.', ' ')
        if (base.isEmpty()) base = "file" // l10n: not text (a file name when the file has none)
        return if (ext != null) "$base.$ext" else base
    }
}
