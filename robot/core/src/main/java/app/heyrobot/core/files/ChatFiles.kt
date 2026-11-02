// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.ScreenText
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.core.web.Invisible

// Pure. A file attached to a question in the panel (chat-files; the File button beside Camera and Photos, app
// assistant/PanelAttach): which kinds Robot reads, the one plain line for any other, and the file's name as the chip and
// History show it. The file itself is read in :files only; this decides from its first bytes, as :files does
// (FileSniff), before anything is sent there. A file that could install or run something is refused, even behind a
// picture's or a PDF's bytes (FileSniff.hiddenIn).
object ChatFiles {
    /** How an attached file reaches the model: a picture as a photo is, a PDF by its text (else its first page's picture), a text file by its text. */
    enum class Way { PICTURE, PDF, TEXT }

    sealed class Check {
        data class Read(val way: Way, val type: FileType) : Check()
        data class Refused(val why: Phrase) : Check()
    }

    /** What the picker offers (ACTION_OPEN_DOCUMENT's EXTRA_MIME_TYPES): a provider's word only; the bytes decide. */
    val PICKER_TYPES = arrayOf("image/*", "application/pdf", "text/*")

    /** A file's name on the chip and in History: at most this many characters, one line. */
    const val NAME_CHARS = 80

    /** Whether Robot reads [bytes] (null: they couldn't be read; [tooBig]: over what :files reads), and which way. */
    fun check(bytes: ByteArray?, tooBig: Boolean): Check {
        if (tooBig) return Check.Refused(PanelStrings.fileTooBig())
        if (bytes == null || bytes.isEmpty()) return Check.Refused(PanelStrings.fileUnreadable())
        val type = FileSniff.sniff(bytes.copyOf(minOf(bytes.size, FileSniff.PREFIX)), bytes.size.toLong())
        val hidden = FileSniff.hiddenIn(java.nio.ByteBuffer.wrap(bytes), type)
        if (hidden != null) return Check.Refused(if (hidden.group == FileGroup.DANGEROUS || hidden == FileType.ZIP) PanelStrings.fileRuns() else PanelStrings.fileKinds())
        return way(type)?.let { Check.Read(it, type) } ?: Check.Refused(refusal(type))
    }

    fun way(type: FileType): Way? = when (type.group) {
        FileGroup.IMAGE -> Way.PICTURE
        FileGroup.PDF -> Way.PDF
        FileGroup.TEXT -> Way.TEXT
        else -> null
    }

    /** The plain line for a file of [type] Robot doesn't read. */
    fun refusal(type: FileType): Phrase = when (type.group) {
        FileGroup.DANGEROUS -> PanelStrings.fileRuns()
        FileGroup.EMPTY -> PanelStrings.fileUnreadable()
        else -> PanelStrings.fileKinds()
    }

    /** :files' refusal of a file, in the same words. */
    fun refusal(status: ParseStatus, type: FileType): Phrase = when {
        status == ParseStatus.DANGEROUS || type.group == FileGroup.DANGEROUS -> PanelStrings.fileRuns()
        status == ParseStatus.TOO_LARGE -> PanelStrings.fileTooBig()
        status == ParseStatus.NOT_READ && way(type) == null -> PanelStrings.fileKinds()
        else -> PanelStrings.fileUnreadable()
    }

    /** [raw] (a provider's display name) as one clean line: no folders, no hidden characters, at most [NAME_CHARS]; null when nothing's left. */
    fun cleanName(raw: String?): String? {
        if (raw == null) return null
        val n = ScreenText.sanitize(Invisible.strip(raw)).substringAfterLast('/').replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) return null
        return if (n.length <= NAME_CHARS) n else n.take(NAME_CHARS)
    }

    /** The reply's first line when Robot read only the start of [name]: :files cut its text ([textCut]) or the prompt did ([promptCut]). */
    fun partLine(name: String, textCut: Boolean, promptCut: Boolean): Phrase? =
        if (textCut || promptCut) PanelStrings.fileFirstPart("name" to name) else null
}
