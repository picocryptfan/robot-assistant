// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import java.time.LocalDate
import java.time.LocalTime

// Pure. What core returns where it returns a sentence today: a sentence that has no language
// yet. The code that shows or speaks it picks the language at the last moment, `text.render(langs)`, as many times as it
// needs (the card in French, the tool result for the model in English: `text.en()`).

/** A sentence without a language yet. Named so it never clashes with Compose's Text. */
sealed interface Phrase {
    /** The sentence in [l]: its own language [Langs.say], Robot's screens named in [Langs.ui], Android's in [Langs.system]. */
    fun render(l: Langs): String

    /** English, for the model and the logs. */
    fun en(): String = render(Langs.EN)

    /** A catalog entry with its arguments by name. */
    data class M(val msg: Msg, val args: Map<String, Arg> = emptyMap()) : Phrase {
        override fun render(l: Langs): String {
            // An entry not translated yet is a whole English sentence: its numbers and lists are English too.
            val lang = if (msg.translated(l.say)) l.say else Lang.EN
            val form = msg.form(lang, l.medium)
            var text = when (form) {
                is Form.One -> form.text
                is Form.Counted -> form.forms.pick(category(lang))
            }
            // Typography and pseudo-locale marks go on the entry's own words, never on your words in its arguments.
            if (lang == Lang.FR) text = FrTypo.fix(text)
            if (l.pseudo != null) text = l.pseudo.mark(text)
            val inner = l.copy(say = lang)
            val out = Msg.PLACEHOLDER.replace(text) { m -> args[m.groupValues[1]]?.render(inner) ?: m.value }
            return if (l.medium == Medium.VOICE) VoiceForm.clean(out, lang) else out
        }

        private fun category(lang: Lang): PluralCategory = when (val a = args[msg.count]) {
            is Arg.Count -> Plural.category(lang, a.n)
            is Arg.Num -> Plural.category(lang, a.x, a.decimals)
            else -> PluralCategory.OTHER
        }
    }

    /** Your words, a name, a title: never translated, never marked. */
    data class Plain(val s: String) : Phrase {
        override fun render(l: Langs): String = s
    }

    /** Sentences one after the other, joined by [sep] (a space: "Done. Robot set 2 timers."). */
    data class Join(val parts: List<Phrase>, val sep: String = " ") : Phrase {
        override fun render(l: Langs): String = parts.joinToString(sep) { it.render(l) }
    }

    /** "a, b and c" / « a, b et c »; "a, b or c" / « a, b ou c ». */
    data class ListOf(val items: List<Phrase>, val kind: ListKind = ListKind.AND) : Phrase {
        override fun render(l: Langs): String {
            val each = items.map { it.render(l) }
            // 1.4 FX2 (files-1.4-review.md R4): amounts said in French each hold « et » already (« 312 dollars et 40 »), so they
            // are added with « plus »: « 312 dollars et 40, plus 45 dollars américains et 50 ».
            if (kind == ListKind.SUM && l.say == Lang.FR && l.medium == Medium.VOICE && each.size > 1) return each.joinToString(", plus ")
            return Fmt.list(each, kind, l.say)
        }
    }

    companion object {
        fun plain(s: String): Phrase = Plain(s)
        fun join(vararg parts: Phrase, sep: String = " "): Phrase = Join(parts.toList(), sep)
    }
}

/** How a list's last two items are joined. FX2: [SUM], amounts added up: as [AND], but « , plus » between them in French aloud. */
enum class ListKind { AND, OR, SUM }

/**
 * A typed argument of a [Phrase.M] (§4.3), formatted for the sentence's language and medium by [Fmt] (« 1,5 Go » on
 * screen, « 1,5 gigaoctet » aloud).
 */
sealed interface Arg {
    fun render(l: Langs): String

    /** Your words, a name, a title, a number written by someone else: as they are. */
    data class Plain(val s: String) : Arg {
        override fun render(l: Langs): String = s
    }

    /** A whole count; it picks the plural form when it is the entry's count. */
    data class Count(val n: Long) : Arg {
        override fun render(l: Langs): String = Fmt.int(n, l.say)
    }

    /** A decimal shown with at most [decimals] digits after the point. */
    data class Num(val x: Double, val decimals: Int = 1) : Arg {
        override fun render(l: Langs): String = Fmt.num(x, decimals, l.say)
    }

    /** A size: "820 MB", « 1,5 Go », « 1,5 gigaoctet » aloud. */
    data class Bytes(val n: Long) : Arg {
        override fun render(l: Langs): String = Fmt.bytes(n, l.say, l.medium)
    }

    /** A duration: "1 hour and 5 minutes", « 1 heure et 5 minutes ». */
    data class Seconds(val s: Long) : Arg {
        override fun render(l: Langs): String = Fmt.seconds(s, l.say)
    }

    /** A time of day: "7:02", « 7 h 02 »; "noon", « midi », « 7 heures 2 » aloud. */
    data class Clock(val t: LocalTime) : Arg {
        override fun render(l: Langs): String = Fmt.clock(t, l.say, l.medium)
    }

    /** A date: "Monday October 12", « lundi 12 octobre ». */
    data class Day(val d: LocalDate) : Arg {
        override fun render(l: Langs): String = Fmt.day(d, l.say)
    }

    /** 1.4, Robot Files: an amount a file holds, [cents] in [currency] (a code, or null): "$84.12", « 84,12 $ », "45.00 USD" (Fmt.money). */
    data class Money(val cents: Long, val currency: String?) : Arg {
        override fun render(l: Langs): String = Fmt.money(cents, currency, l.say, l.medium)
    }

    /** 1.4, Robot Files: a file's date with its year, "August 14, 2026" / « 14 août 2026 » (Fmt.date). */
    data class Date(val d: LocalDate) : Arg {
        override fun render(l: Langs): String = Fmt.date(d, l.say)
    }

    /** 1.4, Robot Files: a month, "September 2026" / « septembre 2026 » (Fmt.month). */
    data class Month(val year: Int, val month: Int) : Arg {
        override fun render(l: Langs): String = Fmt.month(year, month, l.say)
    }

    /** Another sentence, in the same languages. */
    data class Sub(val p: Phrase) : Arg {
        override fun render(l: Langs): String = p.render(l)
    }

    /** A Robot screen or button, in the app's language ("Settings › Memory", « Réglages › Mémoire »). */
    data class Ui(val label: UiLabel) : Arg {
        override fun render(l: Langs): String = label.render(l.ui, l.medium, l.pseudo)
    }

    /** An Android screen, in the phone's language. */
    data class Android(val path: AndroidPath) : Arg {
        override fun render(l: Langs): String = path.render(l.system, l.medium)
    }

    companion object {
        /** A value as its argument: an [Arg] as it is; a String your words; an Int or a Long a count; a Double one decimal. */
        fun of(v: Any?): Arg = when (v) {
            is Arg -> v
            null -> Plain("")
            is String -> Plain(v)
            is Int -> Count(v.toLong())
            is Long -> Count(v)
            is Double -> Num(v)
            is Float -> Num(v.toDouble())
            is Phrase -> Sub(v)
            is UiLabel -> Ui(v)
            is AndroidPath -> Android(v)
            is LocalTime -> Clock(v)
            is LocalDate -> Day(v)
            else -> Plain(v.toString())
        }

        /** "a, b and c" as one argument. */
        fun list(items: List<Phrase>, kind: ListKind = ListKind.AND): Arg = Sub(Phrase.ListOf(items, kind))
    }
}
