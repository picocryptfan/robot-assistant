// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.EventKind
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.Provenance
import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReadyAnswersTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 10, 13, 2, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int, day: Int = 10) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun event(title: String, begin: Long, end: Long, kind: EventKind = EventKind.PHONE, allDay: Boolean = false) =
        CalendarEvent(begin, end, allDay, title, null, kind = kind)
    private fun file(n: Int, name: String, kind: FileKind, date: String) = FileEntry(FileId.of("%032x".format(n))!!, name, kind, FileState.FILED,
        Provenance(FileSource.SHARE, at = 1, size = 10), fields = mapOf("date" to FileField(date, FieldBy.READ)))
    private val land = ReadyQuestions.read("What time do you land?") as ReadyQuestion.Ask
    private val noFiles = { emptyList<FileEntry>() }
    private val sam = Person("p1", "Sam Roy")

    private fun thread(group: Boolean = false, hidden: HiddenWhy? = null, reply: Boolean = true) = MessageThread(MessageApp.values().first(), "c1", "k1", "Sam",
        group, emptyList(), if (reply) ActionRef(0, "text_reply", true, false, false) else null, null, hidden, now)

    @Test fun questionsRead() {
        assertEquals(ReadyQuestion.Ask(ReadyKind.LANDS, Lang.EN), land)
        assertEquals(ReadyQuestion.Ask(ReadyKind.LEAVES, Lang.EN), ReadyQuestions.read("when does your flight leave"))
        assertEquals(ReadyQuestion.Ask(ReadyKind.APPOINTMENT, Lang.EN), ReadyQuestions.read("When's your appointment?"))
        assertEquals(ReadyQuestion.Ask(ReadyKind.BOOKING, Lang.EN), ReadyQuestions.read("what time is the reservation?"))
        assertNull(ReadyQuestions.read("Love you, see you soon"))
        assertNull(ReadyQuestions.read("when are you leaving the office?"))
    }

    @Test fun moneyCodesAndPasswordsNeverAnswered() {
        for (q in listOf("What's the code for the flight booking?", "When do you land? Can you send me the password", "when do I pay for the reservation?",
                "C'est quoi le NIP? Tu atterris quand?", "Quand est-ce que tu atterris, je te dois combien d'argent?", "what time do you land, it cost $40"))
            assertEquals(q, ReadyQuestion.Refused, ReadyQuestions.read(q))
    }

    @Test fun calendarAlone() {
        val a = ReadyAnswers.answer(land, listOf(event("AC 604", at(12, 0), at(14, 35))), noFiles, now, zone)!!
        assertEquals(ReadySource.CALENDAR, a.source)
        assertEquals("From my calendar: AC 604, lands at 14:35.", a.draft)
        assertEquals("Read from your calendar at 13:02.", a.note)
    }

    @Test fun calendarQuotesTheTitleAsWrittenAndSaysTomorrow() {
        val appt = ReadyQuestions.read("what time is your dentist appointment?") as ReadyQuestion.Ask
        val a = ReadyAnswers.answer(appt, listOf(event("Dentist – Dr. Léger", at(9, 15, 11), at(10, 0, 11))), noFiles, now, zone)!!
        assertEquals("From my calendar: Dentist – Dr. Léger, tomorrow at 09:15.", a.draft)
    }

    @Test fun filesAlone() {
        val a = ReadyAnswers.answer(land, emptyList(), { listOf(file(1, "Boarding pass AC 604", FileKind.TICKET, "2026-11-10")) }, now, zone)!!
        assertEquals(ReadySource.FILES, a.source)
        assertEquals("From my files: Boarding pass AC 604, dated 2026-11-10.", a.draft)
        assertEquals("Read from your Files at 13:02.", a.note)
    }

    @Test fun filesReadOnlyWhenTheCalendarHasNone() {
        var read = false
        ReadyAnswers.answer(land, listOf(event("AC 604", at(12, 0), at(14, 35))), { read = true; emptyList() }, now, zone)
        assertFalse(read)
    }

    @Test fun noMatchNoAnswer() {
        assertNull(ReadyAnswers.answer(land, listOf(event("Lunch with Pat", at(12, 0), at(14, 35))), noFiles, now, zone))
        // Landed already, three days out, all day, or one of Robot's private events: none.
        assertNull(ReadyAnswers.answer(land, listOf(event("AC 604", at(9, 0), at(11, 0)), event("AC 606", at(9, 0, 13), at(11, 0, 13)),
            event("Flight home", at(0, 0), at(0, 0, 11), allDay = true), event("WS 220", at(15, 0), at(16, 0), kind = EventKind.PRIVATE)), noFiles, now, zone))
        assertNull(ReadyAnswers.answer(land, emptyList(), { listOf(file(1, "Boarding pass", FileKind.TICKET, "2026-11-20"), file(2, "Receipt", FileKind.RECEIPT, "2026-11-10")) }, now, zone))
    }

    @Test fun twoMatchesBothNamedThreeNone() {
        val two = listOf(event("WS 220", at(16, 0), at(18, 10)), event("AC 604", at(12, 0), at(14, 35)))
        assertEquals("From my calendar: AC 604, lands at 14:35; WS 220, lands at 18:10.", ReadyAnswers.answer(land, two, noFiles, now, zone)!!.draft)
        assertNull(ReadyAnswers.answer(land, two + event("PD 301", at(19, 0), at(20, 0)), noFiles, now, zone))
        val files = listOf(file(1, "Ticket A", FileKind.TICKET, "2026-11-10"), file(2, "Ticket B", FileKind.TICKET, "2026-11-11"), file(3, "Ticket C", FileKind.TICKET, "2026-11-11"))
        assertNull(ReadyAnswers.answer(land, emptyList(), { files }, now, zone))
    }

    @Test fun french() {
        val q = ReadyQuestions.read("Tu atterris à quelle heure ?") as ReadyQuestion.Ask
        assertEquals(ReadyQuestion.Ask(ReadyKind.LANDS, Lang.FR), q)
        val a = ReadyAnswers.answer(q, listOf(event("AC 604", at(12, 0), at(14, 35))), noFiles, now, zone)!!
        assertEquals("D’après mon agenda\u00A0: AC 604, atterrit à 14\u00A0h\u00A035.", a.draft)
        assertEquals("Lu dans ton agenda à 13\u00A0h\u00A002.", a.note)
        val rdv = ReadyQuestions.read("c'est quand ton rendez-vous chez le médecin?") as ReadyQuestion.Ask
        assertEquals(Lang.FR, rdv.lang)
        assertNull(ReadyAnswers.answer(rdv, emptyList(), { listOf(file(1, "Rendez-vous clinique", FileKind.MEDICAL, "2026-11-11")) }, now, zone))
        val resa = ReadyQuestions.read("c'est à quelle heure ta réservation?") as ReadyQuestion.Ask
        val f = ReadyAnswers.answer(resa, emptyList(), { listOf(file(1, "Réservation hôtel", FileKind.TICKET, "2026-11-11")) }, now, zone)!!
        assertEquals("D’après mes fichiers\u00A0: Réservation hôtel, daté du 2026-11-11.", f.draft)
    }

    @Test fun gate() {
        assertNull(ReadyGate.skip(true, false, thread(), sam))
        assertEquals(ReadySkip.OFF, ReadyGate.skip(false, false, thread(), sam))
        assertEquals(ReadySkip.LOCKED, ReadyGate.skip(true, true, thread(), sam))
        assertEquals(ReadySkip.NOT_IN_PEOPLE, ReadyGate.skip(true, false, thread(), null))
        assertEquals(ReadySkip.GROUP, ReadyGate.skip(true, false, thread(group = true), sam))
        assertEquals(ReadySkip.HIDDEN, ReadyGate.skip(true, false, thread(hidden = HiddenWhy.OTP_REDACTED), sam))
        assertEquals(ReadySkip.NO_REPLY, ReadyGate.skip(true, false, thread(reply = false), sam))
    }

    /** review Medium 4: a flight's code is upper case as written; other titles with a letter and digits aren't flights. */
    @Test fun aFlightCodeIsCaseSensitive() {
        val leave = ReadyQuestions.read("when do you take off?") as ReadyQuestion.Ask
        for (t in listOf("Therapy at 1400", "Q3 2026 review", "lunch at 1230")) assertNull(t, ReadyAnswers.answer(leave, listOf(event(t, at(15, 0), at(16, 0))), noFiles, now, zone))
        assertNotNull(ReadyAnswers.answer(leave, listOf(event("AC 604", at(15, 0), at(16, 0))), noFiles, now, zone))
        assertNotNull(ReadyAnswers.answer(leave, listOf(event("Flight to Paris", at(15, 0), at(16, 0))), noFiles, now, zone))
    }

    /** review Medium 5: a title or file name holding a code, a booking number or money words is never quoted. */
    @Test fun aTitleWithACodeIsNeverQuoted() {
        val appt = ReadyQuestions.read("when's your appointment?") as ReadyQuestion.Ask
        for (t in listOf("Dentist, door code 4521", "Dentist ref ABC123", "Doctor 1234567", "Clinic, pay \$40"))
            assertNull(t, ReadyAnswers.answer(appt, listOf(event(t, at(15, 0), at(16, 0))), noFiles, now, zone))
        val book = ReadyQuestions.read("what time is the reservation?") as ReadyQuestion.Ask
        assertNull(ReadyAnswers.answer(book, emptyList(), { listOf(file(1, "Hotel reservation XKQ7TZ1", FileKind.TICKET, "2026-11-11")) }, now, zone))
        assertNotNull(ReadyAnswers.answer(book, emptyList(), { listOf(file(1, "Hotel reservation", FileKind.TICKET, "2026-11-11")) }, now, zone))
    }

    /** review Medium 6 (STATUS question 97): an appointment is answered from the calendar only; medical files are never read. */
    @Test fun anAppointmentNeverReadsMedicalFiles() {
        val appt = ReadyQuestions.read("when's your appointment?") as ReadyQuestion.Ask
        var read = 0
        assertNull(ReadyAnswers.answer(appt, emptyList(), { read++; listOf(file(1, "Clinic visit", FileKind.MEDICAL, "2026-11-11")) }, now, zone))
        assertEquals(0, read)
    }

    /** review Medium 7: by address only; a display name alone (Signal gives no address) finds nobody. */
    @Test fun theAskerByAddressOnly() {
        val p = Person("p1", "Sam Roy", phones = listOf(ContactPhone("+1 902 555 0142", PhoneKind.MOBILE, "")))
        fun from(key: String?) = MessageThread(MessageApp.values().first(), "c1", "k1", "Sam Roy", false,
            listOf(IncomingMessage("m1", MessageApp.values().first(), "c1", "Sam Roy", key, "when do you land?", now, false, true)),
            ActionRef(0, "text_reply", true, false, false), null, null, now)
        assertEquals(p, ReadyGate.person(from("+19025550142"), listOf(p)))
        assertNull(ReadyGate.person(from("Sam Roy"), listOf(p)))
        assertNull(ReadyGate.person(from(null), listOf(p)))
    }

    @Test fun settingsOffByDefault() {
        assertFalse(ReadyAnswerSettings.decode(null).on)
        assertFalse(ReadyAnswerSettings.decode("not json").on)
        assertTrue(ReadyAnswerSettings.decode(ReadyAnswerSettings(on = true).encode()).on)
        assertEquals("ready-answers.json", ReadyAnswerSettings.FILE)
    }

    /** review-ready-answers-b Low B: when one of two matches can't be quoted, the draft doesn't answer with the other. */
    @Test fun oneUnquotableMatchMeansNoAnswer() {
        val appt = ReadyQuestions.read("when's your appointment?") as ReadyQuestion.Ask
        val ok = event("Dentist", at(15, 0), at(16, 0))
        assertNotNull(ReadyAnswers.answer(appt, listOf(ok), noFiles, now, zone))
        assertNull(ReadyAnswers.answer(appt, listOf(ok, event("Doctor, door code 4521", at(14, 0), at(14, 30))), noFiles, now, zone))
        val book = ReadyQuestions.read("what time is the reservation?") as ReadyQuestion.Ask
        val files = { listOf(file(1, "Hotel reservation", FileKind.TICKET, "2026-11-11"), file(2, "Car reservation XKQ7TZ1", FileKind.TICKET, "2026-11-11")) }
        assertNull(ReadyAnswers.answer(book, emptyList(), files, now, zone))
    }

    /** review-ready-answers-b Low A: an all-letter booking reference, a 4-digit or split number, or money words: never quoted. */
    @Test fun refsNumbersAndMoneyWordsNeverQuoted() {
        val appt = ReadyQuestions.read("when's your appointment?") as ReadyQuestion.Ask
        for (t in listOf("Dentist KXJQPL", "Dentist, buzz 4521", "Doctor 123-456", "Dentist 12.34.56", "Dentist, 40 dollars", "Dentist fee",
            "Dentist, frais 40", "Dentist prix", "Doctor, facture"))
            assertNull(t, ReadyAnswers.answer(appt, listOf(event(t, at(15, 0), at(16, 0))), noFiles, now, zone))
        assertNotNull(ReadyAnswers.answer(appt, listOf(event("Dentist, Dr Roy", at(15, 0), at(16, 0))), noFiles, now, zone))
        // A flight's own code, 4 digits included, is still a flight and still quoted.
        for (t in listOf("Flight AC 1234", "WS1234 to Calgary"))
            assertNotNull(t, ReadyAnswers.answer(land, listOf(event(t, at(13, 0), at(15, 0))), noFiles, now, zone))
    }
}
