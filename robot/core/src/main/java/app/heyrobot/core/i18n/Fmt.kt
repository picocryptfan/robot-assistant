// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// Pure. Numbers, units, durations, times and dates in English and Québec French, written here so the JVM
// tests and the phone give the same output: core never asks the platform (Java's and Android's ICU data differ in the
// French grouping space). The times and dates start from the brief's (core BriefClock), which already speaks both.

object Fmt {
    /** U+00A0, the no-break space French puts in "1 234", "30 %" and before ":". */
    const val NBSP = '\u00A0'

    private val DAYS_EN = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val DAYS_FR = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val MONTHS_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December")
    private val MONTHS_FR = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre",
        "novembre", "décembre")

    /** "1,234" / « 1 234 » (a no-break space); "-5". */
    fun int(n: Long, lang: Lang): String {
        val digits = abs(n).toString()
        val sep = if (lang == Lang.FR) NBSP else ','
        val grouped = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) grouped.append(sep)
            grouped.append(c)
        }
        return (if (n < 0) "-" else "") + grouped
    }

    /** "1.5" / « 1,5 », at most [decimals] digits after the point, trailing zeros dropped ("2", not "2.0"). */
    fun num(x: Double, decimals: Int, lang: Lang): String {
        if (decimals <= 0 || !x.isFinite()) return int(x.roundToLong(), lang)
        val s = String.format(Locale.US, "%.${decimals}f", abs(x)).trimEnd('0').trimEnd('.')
        val whole = s.substringBefore('.')
        val frac = s.substringAfter('.', "")
        val negative = x < 0 && s.any { it in '1'..'9' }
        val w = int(whole.toLong(), lang)
        return (if (negative) "-" else "") + w + if (frac.isEmpty()) "" else (if (lang == Lang.FR) "," else ".") + frac
    }

    /** "30%" / « 30 % »; aloud "30 percent" / « 30 pour cent ». */
    fun percent(p: Int, lang: Lang, medium: Medium = Medium.SCREEN): String = int(p.toLong(), lang) + when {
        medium == Medium.VOICE -> if (lang == Lang.FR) " pour cent" else " percent"
        lang == Lang.FR -> "$NBSP%"
        else -> "%"
    }

    /**
     * A size in decimal units, as Android's storage settings: "57 GB", "1.2 GB", "340 MB", "12 KB" / « 1,2 Go », « 340 Mo »,
     * « 12 ko »; aloud the unit in words (« 1,2 gigaoctet »: French says the singular below 2).
     */
    fun bytes(n: Long, lang: Lang, medium: Medium = Medium.SCREEN): String {
        val b = n.coerceAtLeast(0).toDouble()
        val (v, unit) = when {
            b >= 1e9 -> b / 1e9 to 3
            b >= 1e6 -> b / 1e6 to 2
            b >= 1e3 -> b / 1e3 to 1
            else -> b to 0
        }
        val decimals = if (v < 10 && unit > 0) 1 else 0
        val shown = num(v, decimals, lang)
        val fraction = shown.contains(if (lang == Lang.FR) ',' else '.')
        val one = (if (fraction) Plural.category(lang, v, decimals) else Plural.category(lang, v.roundToLong())) == PluralCategory.ONE
        val sep = if (lang == Lang.FR) NBSP else ' '
        val word = if (medium == Medium.VOICE) {
            val en = listOf("byte", "kilobyte", "megabyte", "gigabyte")
            val fr = listOf("octet", "kilooctet", "mégaoctet", "gigaoctet")
            (if (lang == Lang.FR) fr else en)[unit] + if (one) "" else "s"
        } else (if (lang == Lang.FR) listOf(if (one) "octet" else "octets", "ko", "Mo", "Go") else listOf(if (one) "byte" else "bytes", "KB", "MB", "GB"))[unit]
        return shown + (if (medium == Medium.VOICE) ' ' else sep) + word
    }

    /** A helper's price in US dollars: "$10", "$0.25" / « 10 $ US », « 0,25 $ US ». */
    fun usd(cents: Long, lang: Lang): String {
        val amount = if (cents % 100 == 0L) int(cents / 100, lang) else num(cents / 100.0, 2, lang).let { if (it.substringAfter(if (lang == Lang.FR) ',' else '.', "").length == 1) it + "0" else it }
        return if (lang == Lang.FR) "$amount$NBSP\$${NBSP}US" else "$$amount"
    }

    /**
     * A time of day (§4.5): on screen "7:02", "19:30" / « 7 h 02 », « 19 h 30 », « 16 h » (no-break spaces); aloud "7:02",
     * "4", "noon", "midnight" (a 12-hour clock, as people say it) / « 7 heures 2 », « 16 heures », « midi », « minuit »,
     * « minuit 15 » (core BriefClock's words; VoiceForm.clock's form, which the French voice reads right).
     */
    fun clock(t: LocalTime, lang: Lang, medium: Medium = Medium.SCREEN): String = when (lang) {
        Lang.EN -> if (medium == Medium.VOICE) when {
            t.hour == 0 && t.minute == 0 -> "midnight"
            t.hour == 12 && t.minute == 0 -> "noon"
            else -> h12(t).let { h -> if (t.minute == 0) "$h" else String.format(Locale.US, "%d:%02d", h, t.minute) }
        } else String.format(Locale.US, "%d:%02d", t.hour, t.minute)
        Lang.FR -> when {
            medium == Medium.VOICE && t.hour == 12 && t.minute == 0 -> "midi"
            medium == Medium.VOICE -> VoiceForm.frClock(t.hour, t.minute, duration = false)!!
            t.minute == 0 -> "${t.hour}${NBSP}h"
            else -> "${t.hour}${NBSP}h$NBSP" + String.format(Locale.US, "%02d", t.minute)
        }
    }

    /** "Monday October 12" / « lundi 12 octobre », « jeudi 1er octobre ». */
    fun day(d: LocalDate, lang: Lang): String = when (lang) {
        Lang.EN -> "${DAYS_EN[d.dayOfWeek.value - 1]} ${MONTHS_EN[d.monthValue - 1]} ${d.dayOfMonth}"
        Lang.FR -> "${DAYS_FR[d.dayOfWeek.value - 1]} ${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth} ${MONTHS_FR[d.monthValue - 1]}"
    }

    /** 1.4, Robot Files: a file's date, "August 14, 2026" / « 14 août 2026 », « 1er août 2026 ». */
    fun date(d: LocalDate, lang: Lang): String = when (lang) {
        Lang.EN -> "${MONTHS_EN[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"
        Lang.FR -> "${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth} ${MONTHS_FR[d.monthValue - 1]} ${d.year}"
    }

    /** 1.4, Robot Files: a month, "September 2026" / « septembre 2026 ». */
    fun month(year: Int, month: Int, lang: Lang): String = (if (lang == Lang.FR) MONTHS_FR else MONTHS_EN)[month - 1] + " " + year

    /**
     * 1.4, Robot Files: an amount a file holds, [cents] in [currency] (a code, or null when the file didn't say): Canadian
     * dollars as Canadians write them, "$84.12" / « 84,12 $ »; any other code after the number, "84.12 USD" / « 84,12 USD »;
     * none, the number alone. Never converted (§7.3).
     */
    fun money(cents: Long, currency: String?, lang: Lang, medium: Medium = Medium.SCREEN): String {
        if (lang == Lang.FR && medium == Medium.VOICE) frMoneySaid(cents, currency)?.let { return it }
        val whole = int(abs(cents) / 100, lang) + (if (lang == Lang.FR) "," else ".") + (abs(cents) % 100).toString().padStart(2, '0')
        val n = (if (cents < 0) "-" else "") + whole
        return when {
            currency == "CAD" -> if (lang == Lang.FR) "$n$NBSP$" else "$$n"
            currency != null -> "$n$NBSP$currency"
            else -> n
        }
    }

    /**
     * 1.4 F13: an amount as Robot's French voice says it, as people say it in Québec: « 84 dollars et 12 », « 142 dollars »,
     * « 1 dollar », « 45 dollars américains et 50 », « 20 euros », « 84 livres sterling et 12 »; null for another currency or
     * none, which the screen's form reads well enough (« 84,12 USD » would be « virgule »: only CAD, USD, EUR and GBP get words). Never « cents » (the voice
     * reads it as « cent », a hundred).
     */
    private fun frMoneySaid(cents: Long, currency: String?): String? {
        val dollars = abs(cents) / 100
        val rest = abs(cents) % 100
        val many = dollars >= 2
        val unit = when (currency) {
            "CAD" -> if (many) "dollars" else "dollar"
            "USD" -> if (many) "dollars américains" else "dollar américain"
            "EUR" -> if (many) "euros" else "euro"
            // FX2 (files-1.4-review.md R4): never « 84,12 GBP » read as letters.
            "GBP" -> if (many) "livres sterling" else "livre sterling"
            else -> return null
        }
        val sign = if (cents < 0) "moins " else ""
        return sign + int(dollars, Lang.FR) + " " + unit + if (rest > 0) " et $rest" else ""
    }

    /**
     * A duration (core Durations.spoken's English): "1 hour and 5 minutes", "2 hours, 5 minutes and 3 seconds", "0 seconds"
     * / « 1 heure et 5 minutes », « 0 seconde ».
     */
    fun seconds(total: Long, lang: Lang): String {
        val s = total.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        val units = if (lang == Lang.FR) listOf("heure", "minute", "seconde") else listOf("hour", "minute", "second")
        fun part(n: Long, unit: String) = int(n, lang) + " " + unit + if (Plural.category(lang, n) == PluralCategory.ONE) "" else "s"
        val parts = buildList {
            if (h > 0) add(part(h, units[0]))
            if (m > 0) add(part(m, units[1]))
            if (sec > 0 || isEmpty()) add(part(sec, units[2]))
        }
        return list(parts, ListKind.AND, lang)
    }

    /** "4 degrees", "1 degree" / « 4 degrés », « 0 degré », « 1 degré » (French counts below 2 as one); "minus 3" aloud. */
    fun degrees(n: Int, lang: Lang, medium: Medium = Medium.SCREEN): String {
        val number = if (n < 0 && medium == Medium.VOICE) (if (lang == Lang.FR) "moins " else "minus ") + int(-n.toLong(), lang) else int(n.toLong(), lang)
        return number + when (lang) {
            Lang.EN -> if (abs(n) == 1) " degree" else " degrees"
            Lang.FR -> if (abs(n) < 2) " degré" else " degrés"
        }
    }

    /** "a", "a and b", "a, b and c" / « a, b et c »; with [ListKind.OR] "or" / « ou ». */
    fun list(items: List<String>, kind: ListKind, lang: Lang): String {
        val word = when (kind) {
            ListKind.AND, ListKind.SUM -> if (lang == Lang.FR) "et" else "and"
            ListKind.OR -> if (lang == Lang.FR) "ou" else "or"
        }
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " $word " + items.last()
        }
    }

    private fun h12(t: LocalTime): Int = (t.hour % 12).let { if (it == 0) 12 else it }
}
