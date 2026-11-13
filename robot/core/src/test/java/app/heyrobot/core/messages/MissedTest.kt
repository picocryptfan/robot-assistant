// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.3, "What did I miss?" (what-did-i-miss-plan.md): what is held, for which apps, and the sentence in both languages. */
class MissedTest {
    private val signal = "org.thoughtcrime.securesms"
    private val mail = "com.example.mail"
    private val bank = "com.example.bank"
    private val s = MissedSettings(on = true, apps = listOf(signal, mail))

    private fun n(pkg: String, at: Long, kind: SenderKind = SenderKind.APP, title: String = "", label: String = pkg.substringAfterLast('.')) =
        MissedNotice(pkg, label, kind, title, at)

    private fun en(a: MissedAnswer) = MissedSummary.reply(a).render(Langs(Lang.EN, Lang.EN, Lang.EN))
    private fun fr(a: MissedAnswer) = MissedSummary.reply(a).render(Langs(Lang.FR, Lang.FR, Lang.FR))

    private fun held(vararg ns: Pair<String, MissedNotice>) = MissedNotices().apply { for ((k, v) in ns) posted(k, v, s) }

    @Test fun nothingFromAnAppNotPickedIsHeldOrSaid() {
        val h = MissedNotices()
        assertFalse(h.posted("k1", n(bank, 10, SenderKind.PERSON, "Your balance is low"), s))
        assertFalse(h.posted("k2", n(signal, 11, SenderKind.PERSON, "Alex"), MissedSettings(on = false, apps = listOf(signal))))
        assertEquals(0, h.size)
        // Even a list that holds one (a caller's mistake) says nothing of it.
        val list = MissedSummary.of(listOf(n(bank, 10, SenderKind.PERSON, "Your balance is low", "Bank"), n(mail, 9, label = "Mail")), s, countsOnly = false)
        assertEquals(listOf("Mail"), list.map { it.app })
        val said = en(MissedAnswer(true, 2, true, false, list))
        assertFalse(said, said.contains("Bank") || said.contains("balance"))
    }

    @Test fun unpickingOrTurningOffDropsWhatWasHeld() {
        val h = held("a" to n(signal, 1), "b" to n(mail, 2))
        h.settings(s.with(signal, false))
        assertEquals(1, h.size)
        h.settings(s.copy(on = false))
        assertEquals(0, h.size)
    }

    @Test fun countsByAppNewestFirstWithTheLatestLines() {
        val h = held("a" to n(signal, 1, SenderKind.PERSON, "Alex", "Signal"), "b" to n(signal, 5, SenderKind.GROUP, "Hiking club", "Signal"),
            "c" to n(signal, 3, SenderKind.PERSON, "Sam", "Signal"), "d" to n(mail, 7, SenderKind.APP, "Your order shipped\nTrack it", "Mail"))
        val a = MissedSummary.answer(h, s, access = true, locked = false, now = 100)
        assertEquals("Since you last looked, 4 notifications: 1 in Mail and 3 in Signal. The latest in Mail: Your order shipped Track it. " +
            "The latest in Signal is in Hiking club.", en(a))
        assertEquals("Depuis la dernière fois que tu as regardé, 4 notifications : 1 dans Mail et 3 dans Signal. La plus récente dans Mail : " +
            "Your order shipped Track it. La plus récente dans Signal est dans Hiking club.", fr(MissedSummary.fromJson(MissedSummary.toJson(a))!!))
        // Answered unlocked: the mark moved, so nothing is new now.
        assertEquals("Nothing new since you last looked.", en(MissedSummary.answer(h, s, true, false, 200)))
        h.posted("e", n(mail, 300, SenderKind.PERSON, "Pat", "Mail"), s)
        assertEquals("Since you last looked, 1 notification: 1 in Mail. The latest in Mail is from Pat.", en(MissedSummary.answer(h, s, true, false, 400)))
    }

    @Test fun lockedSaysCountsOnlyAndKeepsTheMark() {
        val h = held("a" to n(signal, 1, SenderKind.PERSON, "Alex", "Signal"))
        h.heard(mail, s) // Mail has posted before: only Signal's count is new, so no "nothing has come from" line.
        val a = MissedSummary.answer(h, s, access = true, locked = true, now = 100)
        val wire = MissedSummary.toJson(a)
        assertFalse(wire, wire.contains("Alex"))
        assertEquals("Since you last looked, 1 notification: 1 in Signal. Unlock your phone to hear more.", en(MissedSummary.fromJson(wire)!!))
        assertEquals(0L, h.lookedAt)
        // A forged answer that says counts only drops any title it carries.
        val forged = wire.replace("\"title\":\"\"", "\"title\":\"Alex\"").replace("\"kind\":\"APP\"", "\"kind\":\"PERSON\"")
        assertTrue(MissedSummary.fromJson(forged)!!.list.all { it.title.isEmpty() && it.kind == SenderKind.APP })
    }

    @Test fun theVoiceLogGetsCodesAndCountsNeverANameOrATitle() {
        val h = held("a" to n(signal, 1, SenderKind.PERSON, "Alex", "Signal"), "b" to n(signal, 2, SenderKind.PERSON, "Sam", "Signal"))
        val locked = MissedSummary.answer(h, s, access = true, locked = true, now = 100, label = { "Mail" })
        val (a, b, c) = MissedCode.of(locked)
        assertEquals(Triple(MissedCode.NEW.id, 2, 1 * 2 + 1), Triple(a, b, c))
        val said = app.heyrobot.core.VoiceLogText.describe(app.heyrobot.core.VoiceLogEntry(0, app.heyrobot.core.VoiceEvent.MISSED_ANSWER, a, b, c))
        assertEquals("What did I miss?: new notifications · 2 notifications · 1 picked app nothing has come from (phone locked)", said)
        assertFalse(said, listOf("Alex", "Sam", "Signal", "Mail").any { said.contains(it) })
        h.heard(mail, s)
        MissedSummary.answer(h, s, true, false, 100) // unlocked: the mark moves
        assertEquals(Triple(MissedCode.NOTHING_NEW.id, 0, 0), MissedCode.of(MissedSummary.answer(h, s, true, false, 200)))
        assertEquals(MissedCode.OFF.id, MissedCode.of(MissedSummary.answer(h, s.copy(on = false), true, false, 300)).first)
        assertEquals(MissedCode.NO_ACCESS.id, MissedCode.of(MissedSummary.answer(h, s, false, false, 300)).first)
        assertEquals(MissedCode.NO_APPS.id, MissedCode.of(MissedSummary.answer(h, MissedSettings(true, emptyList()), true, false, 300)).first)
    }

    @Test fun cappedAtFourAppsAndTwoLatestLines() {
        val pkgs = (1..6).map { "com.example.app$it" }
        val st = MissedSettings(true, pkgs)
        val h = MissedNotices().apply { pkgs.forEachIndexed { i, p -> posted("k$i", n(p, i.toLong() + 1, SenderKind.APP, "Title $i", "App$i"), st) } }
        val said = en(MissedSummary.answer(h, st, true, false, 100))
        assertEquals("Since you last looked, 6 notifications: 1 in App5, 1 in App4, 1 in App3, 1 in App2 and 2 in other apps. " +
            "The latest in App5: Title 5. The latest in App4: Title 4.", said)
    }

    @Test fun offNoAppsAndNoAccessSayWhere() {
        assertTrue(en(MissedAnswer(false, 0, true, false, emptyList())).startsWith("What did I miss is off."))
        assertTrue(en(MissedAnswer(true, 0, true, false, emptyList())).startsWith("No app is picked"))
        assertTrue(en(MissedAnswer(true, 1, false, false, emptyList())).startsWith("Robot doesn't have notification access"))
        assertEquals(emptyList<AppMissed>(), MissedSummary.answer(held("a" to n(signal, 1)), s, access = false, locked = false, now = 5).list)
    }

    @Test fun settingsFileRoundTripsAndFailsToOff() {
        assertEquals(s, MissedSettings.parse(MissedSettings.write(s)))
        assertEquals(MissedSettings(), MissedSettings.parse("{not json"))
        assertEquals(MissedSettings(), MissedSettings.parse(null))
        assertNull(MissedSummary.fromJson("nope"))
    }

    @Test fun thePhraseIsAheadOfTheModelAndItsMisfiresAreNot() {
        for (t in listOf("What did I miss?", "did I miss anything", "Robot, what have I missed on my phone?")) assertEquals(t, MessageCommand.Missed(Lang.EN), MessagesRouter.route(t))
        for (t in listOf("Qu'est-ce que j'ai manqué?", "j'ai-tu manqué quelque chose?")) assertEquals(t, MessageCommand.Missed(Lang.FR), MessagesRouter.route(t))
        for (t in listOf("what did I miss in the meeting", "did I miss the bus", "qu'est-ce que j'ai manqué à la réunion", "j'ai manqué l'autobus")) assertNull(t, MessagesRouter.route(t))
        assertTrue(MessagesRouter.applies(MessageCommand.Missed(Lang.EN), on = false, readHere = false))
    }

    /** fix-what-did-i-miss, finding 3: a picked app Android never delivered from gets "nothing has come from it yet", never "Nothing new". */
    @Test fun aPickedAppNeverHeardFromIsNamedNotNothingNew() {
        val h = MissedNotices()
        val first = MissedSummary.answer(h, s, access = true, locked = false, now = 10L) { if (it == mail) "Mail" else "Signal" }
        assertEquals(listOf("Signal", "Mail"), first.unheard)
        val e = en(first)
        assertFalse(e, e.contains("Nothing new"))
        assertEquals("Nothing has come from Signal and Mail yet. If it isn't a messaging app, allow “Notifications” for Robot: Settings › Messages shows how.", e)
        // Signal delivered something (even one that isn't counted): only Mail is left, after the counts.
        h.heard(signal, s)
        h.posted("k1", n(signal, 20L, SenderKind.PERSON, "Alex", label = "Signal"), s)
        val second = MissedSummary.answer(h, s, access = true, locked = false, now = 30L) { if (it == mail) "Mail" else "Signal" }
        assertEquals(listOf("Mail"), second.unheard)
        assertTrue(en(second), en(second).startsWith("Since you last looked, 1 notification: 1 in Signal.") && en(second).endsWith("Settings › Messages shows how."))
        assertEquals(second, MissedSummary.fromJson(MissedSummary.toJson(second)))
        assertTrue(fr(second), fr(second).contains("Mail") && fr(second).contains("Notifications"))
        // The router's time tail (finding 5; the corpus can't hold these, their tools column moves with the day words).
        for (q in listOf("what did I miss today", "What did I miss this morning?", "qu'ai-je raté aujourd'hui", "qu'est-ce que j'ai manqué ce matin"))
            assertTrue(q, MessagesRouter.route(q, false) is MessageCommand.Missed)
        assertFalse(MessagesRouter.route("what did I miss in the news today", false) is MessageCommand.Missed)
        // Both heard, nothing new: "Nothing new" again. An app you no longer pick isn't heard any more.
        h.heard(mail, s)
        assertEquals("Nothing new since you last looked.", en(MissedSummary.answer(h, s, true, false, 40L)))
        h.settings(MissedSettings(on = true, apps = listOf(signal)))
        assertEquals(emptyList<String>(), MissedSummary.answer(h, MissedSettings(on = true, apps = listOf(signal)), true, false, 50L).unheard)
        h.settings(s)
        assertEquals(listOf("mail"), MissedSummary.answer(h, s, true, false, 60L).unheard)
    }

    /** fix-what-did-i-miss-c, A: a picked app that was uninstalled (no label) is left out of "nothing has come from". */
    @Test fun anUninstalledPickedAppIsLeftOutOfNothingHasCome() {
        val a = MissedSummary.answer(MissedNotices(), s, access = true, locked = false, now = 10L) { if (it == mail) "Mail" else "" }
        assertEquals(listOf("Mail"), a.unheard)
        assertEquals("Nothing new since you last looked.", en(MissedSummary.answer(MissedNotices(), s, true, false, 20L) { "" }))
    }

    /** fix-what-did-i-miss-c, B: picked apps heard before a restart stay heard (missed.json), so a quiet one is "Nothing new". */
    @Test fun appsHeardBeforeARestartStayHeard() {
        val saved = MissedSettings.parse(MissedSettings.write(s.copy(heard = listOf(signal, mail, "gone.app"))))
        assertEquals(listOf(signal, mail), saved.heard)
        assertEquals("Nothing new since you last looked.", en(MissedSummary.answer(MissedNotices(), saved, true, false, 10L)))
        assertEquals(listOf(mail), saved.with(signal, false).heard)
        assertEquals(listOf(signal), MissedSummary.answer(MissedNotices(), saved.copy(heard = listOf(mail)), true, false, 20L).unheard.map { it.lowercase() }.map { if (it == "securesms") signal else it })
    }

    /** fix-what-did-i-miss-c, C: a conversation title makes a group only for an app targeting API 27 or older, as Android decides. */
    @Test fun aTitleMakesAGroupOnlyForAnOldApp() {
        assertEquals(SenderKind.PERSON, SenderKind.of(messages = true, group = false, title = true, targetSdk = 34))
        assertEquals(SenderKind.GROUP, SenderKind.of(messages = true, group = false, title = true, targetSdk = 27))
        assertEquals(SenderKind.GROUP, SenderKind.of(messages = true, group = true, title = false, targetSdk = 34))
        assertEquals(SenderKind.PERSON, SenderKind.of(messages = true, group = false, title = false, targetSdk = 27))
        assertEquals(SenderKind.APP, SenderKind.of(messages = false, group = false, title = true, targetSdk = 27))
    }

    /** fix-what-did-i-miss, finding 4: after a reconnect, what left the shade while unbound is gone; when you last looked stays. */
    @Test fun aReconnectKeepsOnlyWhatIsStillInTheShade() {
        val h = held("a" to n(signal, 20L, title = "Alex"), "b" to n(mail, 30L, title = "Invoice"))
        h.looked(10L)
        h.keep(setOf("b", "z"))
        assertEquals(listOf("Invoice"), h.since(s).map { it.title })
        assertEquals(10L, h.lookedAt)
    }
}
