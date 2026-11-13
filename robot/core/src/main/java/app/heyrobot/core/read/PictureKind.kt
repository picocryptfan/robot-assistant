// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReadStrings
import java.util.Locale

/** 1.3: what a picture is, the first word of READ_ALOUD's reply (ReadAloud.PROMPT's list). OTHER is said as nothing. */
enum class PictureKind(val word: String) {
    LETTER("letter"), LABEL("label"), SIGN("sign"), RECEIPT("receipt"), MENU("menu"), PAGE("page"), NOTE("note"), SCREEN("screen"), // l10n: not text (model words)
    OTHER("other"); // l10n: not text (model words)

    fun phrase(): Phrase? = when (this) {
        LETTER -> ReadStrings.letter; LABEL -> ReadStrings.label; SIGN -> ReadStrings.sign; RECEIPT -> ReadStrings.receipt
        MENU -> ReadStrings.menu; PAGE -> ReadStrings.page; NOTE -> ReadStrings.note; SCREEN -> ReadStrings.screen
        OTHER -> null
    }?.let { Phrase.M(it) }

    companion object {
        fun of(word: String): PictureKind? = entries.firstOrNull { it.word == word.lowercase(Locale.ROOT) }
    }
}
