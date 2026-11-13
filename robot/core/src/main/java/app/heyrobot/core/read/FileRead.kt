// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.files.FileId
import app.heyrobot.core.i18n.Lang

// 1.3 point and read (point-and-read-plan.md, job P3): Read aloud on a Files page. The panel (:voice) is handed the file's id
// and the page, never their content (the Files screen through PanelLaunchActivity, [ARG]), and opens the file itself behind
// Robot Files' gate. A text layer is read as it is, without the model; a page that is only pixels goes to READ_ALOUD.
object FileRead {
    /** The panel's argument: "robot_" so ShowTrust keeps it on the assistant service's own show, and only there. */
    const val ARG = "robot_read_file" // l10n: not text (an intent key)
    /** A text layer with fewer letters than this (a scan's stray marks) is read as a picture. */
    const val MIN_LETTERS = 3
    /** Pages counted from 0, at most this many digits. */
    private const val PAGE_DIGITS = 4

    data class Page(val id: FileId, val page: Int)

    fun arg(id: FileId, page: Int): String = "${id.hex}:$page" // l10n: not text (an id and a number)

    /** [v] as a file's id and a page, or null when it is anything else. */
    fun parse(v: String?): Page? {
        val parts = v?.split(':')?.takeIf { it.size == 2 } ?: return null
        val id = FileId.of(parts[0]) ?: return null
        val page = parts[1].takeIf { it.length in 1..PAGE_DIGITS && it.all { c -> c in '0'..'9' } }?.toInt() ?: return null
        return Page(id, page)
    }

    /**
     * What to read of :files' text [text] for [page] of a file of [pages] pages ([hasText]: it came from a text layer or a text
     * file): the page's own part when the text marks its pages (form feeds), all of it for a one-page file; null when there is
     * none worth reading, or when several pages' text runs together (:files joins a PDF's pages): then that page's pixels, so
     * page 7 is page 7, never pages 1 to 10.
     */
    fun words(text: String?, hasText: Boolean, page: Int, pages: Int): String? {
        if (!hasText || text == null) return null
        val parts = text.split('\u000C')
        val t = when {
            parts.size > 1 -> parts.getOrNull(page)
            pages <= 1 -> text
            else -> null
        }?.trim() ?: return null
        return t.takeIf { s -> s.count(Char::isLetter) >= MIN_LETTERS }
    }

    /**
     * What is said of a text layer's [words]: the words alone, sentence by sentence as a picture's (ReadStream), in your voice
     * [you] (no kind, no offer: the model wrote none).
     */
    fun said(words: String, you: Lang): List<ReadAloud.Said> = ReadAloud.spoken(ReadAloud.Reading(null, null, words), you).flatMap { s ->
        if (s is ReadAloud.Said.Words) ReadStream.sentences(s.text).map { ReadAloud.Said.Words(it, s.lang) } else listOf(s)
    }
}
