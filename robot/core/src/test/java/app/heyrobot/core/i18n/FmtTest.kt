// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.BriefClock
import app.heyrobot.core.Durations
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** §4.5's table, in both languages and both media: the JVM and the phone give the same words, so this is the reference. */
class FmtTest {
    private val nb = Fmt.NBSP

    @Test fun numbers() {
        assertEquals("1,234", Fmt.int(1234, Lang.EN))
        assertEquals("1${nb}234", Fmt.int(1234, Lang.FR))
        assertEquals("1${nb}234${nb}567", Fmt.int(1_234_567, Lang.FR))
        assertEquals("999", Fmt.int(999, Lang.EN))
        assertEquals("-5", Fmt.int(-5, Lang.FR))
        assertEquals("1.5", Fmt.num(1.5, 1, Lang.EN))
        assertEquals("1,5", Fmt.num(1.5, 1, Lang.FR))
        assertEquals("2", Fmt.num(2.0, 1, Lang.EN))
        assertEquals("1${nb}234,5", Fmt.num(1234.5, 1, Lang.FR))
        assertEquals("0", Fmt.num(-0.04, 1, Lang.EN))
        assertEquals("0,25", Fmt.num(0.25, 2, Lang.FR))
        assertEquals("30%", Fmt.percent(30, Lang.EN))
        assertEquals("30$nb%", Fmt.percent(30, Lang.FR))
        assertEquals("30 percent", Fmt.percent(30, Lang.EN, Medium.VOICE))
        assertEquals("30 pour cent", Fmt.percent(30, Lang.FR, Medium.VOICE))
    }

    @Test fun sizesInDecimalUnits() {
        assertEquals("820 MB", Fmt.bytes(820_000_000, Lang.EN))
        assertEquals("820${nb}Mo", Fmt.bytes(820_000_000, Lang.FR))
        assertEquals("1.5 GB", Fmt.bytes(1_500_000_000, Lang.EN))
        assertEquals("1,5${nb}Go", Fmt.bytes(1_500_000_000, Lang.FR))
        assertEquals("12 KB", Fmt.bytes(12_000, Lang.EN))
        assertEquals("12${nb}ko", Fmt.bytes(12_000, Lang.FR))
        assertEquals("1 byte", Fmt.bytes(1, Lang.EN))
        assertEquals("0${nb}octet", Fmt.bytes(0, Lang.FR))
        assertEquals("500${nb}octets", Fmt.bytes(500, Lang.FR))
        // Aloud, the unit in words; French says the singular below 2.
        assertEquals("1,5 gigaoctet", Fmt.bytes(1_500_000_000, Lang.FR, Medium.VOICE))
        assertEquals("1.5 gigabytes", Fmt.bytes(1_500_000_000, Lang.EN, Medium.VOICE))
        assertEquals("1 gigabyte", Fmt.bytes(1_000_000_000, Lang.EN, Medium.VOICE))
        assertEquals("820 mégaoctets", Fmt.bytes(820_000_000, Lang.FR, Medium.VOICE))
        // The same numbers as core PhoneStatus.size, which Android's storage settings match.
        for (b in listOf(0L, 999L, 12_000L, 340_000_000L, 1_200_000_000L, 57_000_000_000L)) {
            if (b >= 1000) assertEquals(app.heyrobot.core.PhoneStatus.size(b), Fmt.bytes(b, Lang.EN))
        }
    }

    @Test fun dollars() {
        assertEquals("$10", Fmt.usd(1000, Lang.EN))
        assertEquals("$0.25", Fmt.usd(25, Lang.EN))
        assertEquals("$1.50", Fmt.usd(150, Lang.EN))
        assertEquals("10$nb\$${nb}US", Fmt.usd(1000, Lang.FR))
        assertEquals("1,50$nb\$${nb}US", Fmt.usd(150, Lang.FR))
    }

    @Test fun timesOfDay() {
        val t = LocalTime.of(7, 2)
        assertEquals("7:02", Fmt.clock(t, Lang.EN))
        assertEquals("19:30", Fmt.clock(LocalTime.of(19, 30), Lang.EN))
        assertEquals("7${nb}h${nb}02", Fmt.clock(t, Lang.FR))
        assertEquals("16${nb}h", Fmt.clock(LocalTime.of(16, 0), Lang.FR))
        assertEquals("12${nb}h", Fmt.clock(LocalTime.NOON, Lang.FR))
        assertEquals("midi", Fmt.clock(LocalTime.NOON, Lang.FR, Medium.VOICE))
        assertEquals("noon", Fmt.clock(LocalTime.NOON, Lang.EN, Medium.VOICE))
        // Aloud, as the French voice reads right (VoiceForm.clock): not « sept ache zéro deux ».
        assertEquals("7 heures 2", Fmt.clock(t, Lang.FR, Medium.VOICE))
        assertEquals("16 heures", Fmt.clock(LocalTime.of(16, 0), Lang.FR, Medium.VOICE))
        assertEquals("12 heures 30", Fmt.clock(LocalTime.of(12, 30), Lang.FR, Medium.VOICE))
        assertEquals("minuit", Fmt.clock(LocalTime.MIDNIGHT, Lang.FR, Medium.VOICE))
        assertEquals("minuit 15", Fmt.clock(LocalTime.of(0, 15), Lang.FR, Medium.VOICE))
        assertEquals("une heure", Fmt.clock(LocalTime.of(1, 0), Lang.FR, Medium.VOICE))
        assertEquals("vingt et une heures vingt et une", Fmt.clock(LocalTime.of(21, 21), Lang.FR, Medium.VOICE))
        // Aloud, exactly the brief's clock, which already speaks both; on screen, the brief's French clock is Fmt's.
        for (h in 0..23) for (m in listOf(0, 2, 30)) {
            val x = LocalTime.of(h, m)
            assertEquals(x.toString(), BriefClock.en(x), Fmt.clock(x, Lang.EN, Medium.VOICE))
            assertEquals(x.toString(), VoiceForm.clean(BriefClock.fr(x), Lang.FR), Fmt.clock(x, Lang.FR, Medium.VOICE))
            if (x != LocalTime.MIDNIGHT && x != LocalTime.NOON) assertEquals(x.toString(), Fmt.clock(x, Lang.FR), BriefClock.fr(x))
        }
    }

    @Test fun dates() {
        val d = LocalDate.of(2026, 10, 12)
        assertEquals("Monday October 12", Fmt.day(d, Lang.EN))
        assertEquals("lundi 12 octobre", Fmt.day(d, Lang.FR))
        assertEquals("jeudi 1er octobre", Fmt.day(LocalDate.of(2026, 10, 1), Lang.FR))
        for (i in 0L..40L) {
            val x = d.plusDays(i)
            assertEquals(BriefClock.dateEn(x), Fmt.day(x, Lang.EN))
            assertEquals(BriefClock.dateFr(x), Fmt.day(x, Lang.FR))
        }
    }

    @Test fun durations() {
        assertEquals("1 hour and 5 minutes", Fmt.seconds(3900, Lang.EN))
        assertEquals("1 heure et 5 minutes", Fmt.seconds(3900, Lang.FR))
        assertEquals("2 heures, 5 minutes et 3 secondes", Fmt.seconds(7503, Lang.FR))
        assertEquals("0 seconde", Fmt.seconds(0, Lang.FR))
        // English is today's Durations.spoken, word for word.
        for (s in listOf(0L, 1L, 2L, 59L, 60L, 61L, 3600L, 3661L, 7503L, 86_400L)) assertEquals(Durations.spoken(s), Fmt.seconds(s, Lang.EN))
    }

    @Test fun degreesAndLists() {
        assertEquals("4 degrees", Fmt.degrees(4, Lang.EN))
        assertEquals("1 degree", Fmt.degrees(1, Lang.EN))
        assertEquals("0 degré", Fmt.degrees(0, Lang.FR))
        assertEquals("1 degré", Fmt.degrees(-1, Lang.FR).removePrefix("-"))
        assertEquals("4 degrés", Fmt.degrees(4, Lang.FR))
        assertEquals("minus 3 degrees", Fmt.degrees(-3, Lang.EN, Medium.VOICE))
        assertEquals("moins 1 degré", Fmt.degrees(-1, Lang.FR, Medium.VOICE))
        assertEquals("-3 degrees", Fmt.degrees(-3, Lang.EN))
        assertEquals("", Fmt.list(emptyList(), ListKind.AND, Lang.EN))
        assertEquals("a", Fmt.list(listOf("a"), ListKind.AND, Lang.EN))
        assertEquals("a and b", Fmt.list(listOf("a", "b"), ListKind.AND, Lang.EN))
        assertEquals("a, b et c", Fmt.list(listOf("a", "b", "c"), ListKind.AND, Lang.FR))
        assertEquals("a, b or c", Fmt.list(listOf("a", "b", "c"), ListKind.OR, Lang.EN))
        assertEquals("a ou b", Fmt.list(listOf("a", "b"), ListKind.OR, Lang.FR))
    }
}
