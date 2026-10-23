// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import kotlin.math.abs

// Pure. Core's catalog: one Kotlin object per area ([Strings]), each entry a [Msg] with its English and its
// Québec French, keyed by an id ("brief.good_morning"). Placeholders are named, in braces ("{n} conversations: {list}."),
// never positional glue: word order differs in French. An entry whose French is null is not translated yet: it renders
// in English (a half-done tree shows a mix, never a crash), and its area can't be marked done (l10n/areas-done.txt).

/** CLDR's plural categories that English and French use (§4.5). */
enum class PluralCategory { ONE, MANY, OTHER }

/**
 * Plural forms of one entry. [many] is French's "exact millions" (« 1 000 000 de minutes »): absent, [other] serves.
 * Android's rule holds here too: [one] names its count ("{n}"), so "1" never hides in a word.
 */
data class Plural(val one: String, val other: String, val many: String? = null) {
    fun pick(c: PluralCategory): String = when (c) {
        PluralCategory.ONE -> one
        PluralCategory.MANY -> many ?: other
        PluralCategory.OTHER -> other
    }

    val texts: List<String> get() = listOfNotNull(one, other, many)

    companion object {
        /**
         * The category of a whole count (CLDR plurals.xml, VERIFIED in the design): English `one` is exactly 1; French
         * `one` is 0 and 1, `many` an exact non-zero multiple of a million, `other` the rest. The sign doesn't count.
         */
        fun category(lang: Lang, n: Long): PluralCategory {
            val i = abs(n)
            return when (lang) {
                Lang.EN -> if (i == 1L) PluralCategory.ONE else PluralCategory.OTHER
                Lang.FR -> when {
                    i <= 1L -> PluralCategory.ONE
                    i % 1_000_000L == 0L -> PluralCategory.MANY
                    else -> PluralCategory.OTHER
                }
            }
        }

        /**
         * The category of a decimal as written with [decimals] digits after the point (CLDR's v): "1.5 GB" is `other` in
         * English, « 1,5 Go » is `one` in French (its whole part is 1). Written without decimals, as a count.
         */
        fun category(lang: Lang, x: Double, decimals: Int): PluralCategory {
            if (decimals <= 0) return category(lang, Math.round(x))
            return when (lang) {
                Lang.EN -> PluralCategory.OTHER
                Lang.FR -> if (abs(x) < 2.0) PluralCategory.ONE else PluralCategory.OTHER
            }
        }
    }
}

/** One language's text of an entry: one sentence, or plural forms chosen by a count argument. */
sealed interface Form {
    val texts: List<String>

    data class One(val text: String) : Form {
        override val texts: List<String> get() = listOf(text)
    }

    data class Counted(val forms: Plural) : Form {
        override val texts: List<String> get() = forms.texts
    }
}

/**
 * A catalog entry. [id]: "<catalog prefix>.<key>". [en], [fr]: the screen's text in each language ([fr] null: not
 * translated yet, English shows). [count]: the argument that picks a plural form, for a [Form.Counted] entry.
 * [voiceEn], [voiceFr]: where the spoken form really differs from the written one (« 30 % » on screen, « 30 pour cent »
 * aloud); otherwise the voice says [VoiceForm.clean] of the screen's. [maxChars]: a length the layout can't exceed (car
 * screen, notification actions), for the length report.
 */
class Msg(
    val id: String,
    val en: Form,
    val fr: Form?,
    val count: String? = null,
    val voiceEn: Form? = null,
    val voiceFr: Form? = null,
    val maxChars: Int? = null,
) {
    /** The form [lang] and [medium] use; English when the French isn't written yet ([translated]). */
    fun form(lang: Lang, medium: Medium): Form {
        val fr = lang == Lang.FR && this.fr != null
        return when {
            medium == Medium.VOICE && fr && voiceFr != null -> voiceFr
            medium == Medium.VOICE && !fr && voiceEn != null -> voiceEn
            fr -> this.fr!!
            else -> en
        }
    }

    /** Whether [lang] has its own text (English always has). */
    fun translated(lang: Lang): Boolean = lang == Lang.EN || fr != null

    /** Every text of the entry, English and French, screen and voice. */
    val texts: List<String> get() = listOfNotNull(en, fr, voiceEn, voiceFr).flatMap { it.texts }

    /** The placeholders the entry names ("{n}", "{name}"), in [form]. */
    fun placeholders(form: Form?): Set<String> = form?.texts.orEmpty().flatMap { t -> PLACEHOLDER.findAll(t).map { it.groupValues[1] } }.toSet()

    /**
     * A sentence of this entry with [args] by name: a [Arg], or a value [Arg.of] turns into one (a String is your words,
     * never translated; an Int or a Long a count; a [Phrase] a sentence of its own; a [UiLabel], an [AndroidPath]).
     */
    operator fun invoke(vararg args: Pair<String, Any?>): Phrase = Phrase.M(this, args.associate { (k, v) -> k to Arg.of(v) })

    /** A [Form.Counted] entry with its count [n] (under [count]'s name) and the other [args]. */
    operator fun invoke(n: Long, vararg args: Pair<String, Any?>): Phrase =
        Phrase.M(this, mapOf((count ?: "n") to Arg.Count(n)) + args.associate { (k, v) -> k to Arg.of(v) })

    operator fun invoke(n: Int, vararg args: Pair<String, Any?>): Phrase = invoke(n.toLong(), *args)

    override fun toString(): String = "Msg($id)"

    companion object {
        /** A placeholder: "{name}", a name in lower camel case or digits. */
        val PLACEHOLDER = Regex("""\{([a-zA-Z][a-zA-Z0-9_]*)\}""")
    }
}

/**
 * An area's catalog: an object whose properties are its entries, made with [s] and [plural]. [prefix] starts every id
 * ("brief" for "brief.good_morning"); [area] is the area it belongs to (its resource file is values/strings_<area>.xml,
 * its line in l10n/areas-done.txt once every entry has its French). Each object is owned by one work package; [Catalogs] lists them all.
 */
abstract class Strings(val prefix: String, val area: String) {
    private val entries = mutableListOf<Msg>()

    /** Every entry, in the order written. */
    val all: List<Msg> get() = entries

    /** One sentence; [fr] null while its French is drafted. */
    protected fun s(key: String, en: String, fr: String?, voiceEn: String? = null, voiceFr: String? = null, maxChars: Int? = null): Msg =
        add(Msg("$prefix.$key", Form.One(en), fr?.let { Form.One(it) }, null, voiceEn?.let { Form.One(it) }, voiceFr?.let { Form.One(it) }, maxChars))

    /** Plural forms chosen by the argument [count] ("n"). */
    protected fun plural(key: String, count: String, en: Plural, fr: Plural?, voiceEn: Plural? = null, voiceFr: Plural? = null, maxChars: Int? = null): Msg =
        add(Msg("$prefix.$key", Form.Counted(en), fr?.let { Form.Counted(it) }, count, voiceEn?.let { Form.Counted(it) }, voiceFr?.let { Form.Counted(it) }, maxChars))

    private fun add(m: Msg): Msg = m.also { entries += it }
}
