// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.4, §4.5; job F12): the tags Robot itself knows. `tax` (slips, and what your
// rules tag), `business` and `personal`. Robot adds `business` or `personal` only when you or one of your rules say so (a
// rule comes only from your words or your taps, never from a file's text: FilingRules); a file is one or the other, never
// both. They are stored as these codes in every language, so a French phone and an English one file the same way, and
// FilesStrings.tag names them in the menus' language (« affaires », « personnel », « impôts »). A tag you type in either
// language ("Business", « affaires », « perso ») is stored as its code; any other tag is kept as you typed it.

object FileTags {
    const val TAX = Drawers.TAX_TAG
    const val BUSINESS = "business"
    const val PERSONAL = "personal"

    /** The tags Robot names in each language, in the order a file's page offers them. */
    val KNOWN = listOf(TAX, BUSINESS, PERSONAL)

    /** Words for them, folded (lower case, no accents), in English and French. */
    private val SAID = mapOf(
        "tax" to TAX, "taxes" to TAX, "impot" to TAX, "impots" to TAX, // l10n: not text (words Robot reads)
        "business" to BUSINESS, "work" to BUSINESS, "affaires" to BUSINESS, "travail" to BUSINESS, // l10n: not text (words Robot reads)
        "personal" to PERSONAL, "personnel" to PERSONAL, "perso" to PERSONAL, // l10n: not text (words Robot reads)
    )

    /** The tag [typed] makes: a known tag's code for its words in either language, else the label as cleaned; null when it isn't one. */
    fun of(typed: String): String? {
        val t = FileIndexCodec.cleanName(typed).take(FileIndexCodec.MAX_LABEL).trim()
        if (!FileIndexCodec.label(t)) return null
        return SAID[FilesRouter.fold(t)] ?: t
    }

    /** The tag a file can't hold beside [tag] (business and personal), or null. */
    fun other(tag: String): String? = when (tag) {
        BUSINESS -> PERSONAL
        PERSONAL -> BUSINESS
        else -> null
    }

    /**
     * [tags] with business and personal never both: [wins] (the one you or a rule just gave) stays, else the first of them
     * kept. Order otherwise unchanged.
     */
    fun exclusive(tags: List<String>, wins: String? = null): List<String> {
        val keep = wins?.takeIf { other(it) != null && it in tags } ?: tags.firstOrNull { other(it) != null } ?: return tags
        val drop = other(keep) ?: return tags
        return tags.filter { it != drop }
    }
}
