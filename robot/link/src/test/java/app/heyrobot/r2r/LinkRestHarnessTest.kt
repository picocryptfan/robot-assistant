// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.Person
import app.heyrobot.core.PersonSource
import app.heyrobot.core.link.ContactCard
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskState
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.PairCode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.QAsk
import app.heyrobot.core.link.requests.QReply
import app.heyrobot.core.link.requests.RemOffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * robot-to-robot-plan.md §4.1, §4.6, §9.1, D8, D17 (job L10): ETA, reminders, contact cards and questions between two Robots in
 * one JVM. An ETA is shown, nothing done; a reminder lands in your brief reminders (never a calendar), 3 a day on its own
 * from one person, then a card; a contact card is always your tap and adds a new, unconfirmed person, never changing one
 * you have, never with a third person's Robot link; a question waits for your words unless what you shared answers it, and
 * an answer is kept only when it answers a question you sent.
 */
class LinkRestHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L // 2026-09-21 21:33 UTC
    private val robots = ArrayList<TestRobot>()

    @After fun tearDown() { for (r in robots) r.node?.close() }

    class FakeReminders : DeskReminders {
        val list = CopyOnWriteArrayList<Triple<String, String, Long>>()
        @Volatile var writable = true
        override fun add(pairing: String, text: String, whenAt: Long): String? =
            if (!writable) null else "rem-${list.size}".also { list += Triple(it, text, whenAt) }
        override fun remove(token: String): Boolean = list.removeIf { it.first == token }
    }

    class FakePeople : DeskPeople {
        val list = CopyOnWriteArrayList<Person>()
        override fun list(): List<Person> = list.toList()
        override fun add(p: Person): Boolean = list.add(p)
        override fun newId(): String = "p-" + list.size
    }

    private class Bot(val r: TestRobot, val env: TestDeskEnv, val reminders: FakeReminders, val people: FakePeople, val shares: MutableMap<String, String>)

    private fun robot(name: String, push: FakePushServer): Bot {
        val env = TestDeskEnv()
        val rem = FakeReminders(); val people = FakePeople(); val shares = java.util.concurrent.ConcurrentHashMap<String, String>()
        val r = TestRobot(tmp.newFolder(), FakeEnv(t0), null, 0, null, push, name, deskEnv = env,
            more = { st -> listOf(RemindersHandler(st, rem), CardsHandler(people), QuestionsHandler(st) { _, q -> shares[q] }) })
        robots += r; r.n().start(); r.n().wake()
        return Bot(r, env, rem, people, shares)
    }

    private fun until(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle(vararg bs: Bot) {
        repeat(3) { for (b in bs) { b.r.n().pump().get(10, TimeUnit.SECONDS); b.r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot, not: Set<String> = emptySet()): String {
        until("an active pairing") { r.n().pairings().get().any { it.value == PairingState.ACTIVE && it.key !in not } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE && it.key !in not }.key
    }

    private fun pair(a: Bot, b: Bot): Pair<String, String> {
        val beforeA = a.r.n().pairings().get().keys; val beforeB = b.r.n().pairings().get().keys
        val code = a.r.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.r.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        return active(a.r, beforeA) to active(b.r, beforeB)
    }

    private fun send(from: Bot, pairing: String, body: LinkBody): LinkRequest {
        val r = LinkRequest.of(body, from.r.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.r.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun <T> on(b: Bot, block: (LinkDesk) -> T): T = b.r.n().onLinkThread { block(b.r.desk!!) }.get(10, TimeUnit.SECONDS)

    private fun level(b: Bot, pairing: String, cap: LinkCapability, level: LinkLevel) {
        if (level == LinkLevel.AUTO) assertTrue(b.r.n().setLevel(pairing, cap, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        assertTrue(b.r.n().setLevel(pairing, cap, level).get(10, TimeUnit.SECONDS))
    }

    private fun replies(b: Bot) = on(b) { it.view()!!.lines.filter { l -> l.entry.way == DeskWay.REPLY } }

    private fun handled(b: Bot) = on(b) { it.view()!!.lines.filter { l -> l.entry.way == DeskWay.IN && l.entry.state == DeskState.HANDLED } }

    @Test fun anEtaOnItsOwnIsShownAndNothingElseIsDone() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.ETA, LinkLevel.AUTO)
        send(matt, samOnMatt, EtaTell(10, "home"))
        until("Sam's Robot shows it") { handled(sam).size == 1 }
        assertEquals(EtaTell(10, "home"), handled(sam).single().request!!.body)
        assertTrue(sam.reminders.list.isEmpty() && sam.people.list.isEmpty())
        assertTrue(on(sam) { it.notices() }.notices.any { it.kind == DeskNoticeKind.HANDLED })
        // The panel said it: marked seen, not said again.
        val id = handled(sam).single().entry.id
        assertTrue(on(sam) { it.seen(id) })
        assertTrue(handled(sam).single().entry.seen)
    }

    @Test fun aReminderAtAskMeFirstIsACardWhoseYesPutsItInYourBriefAndUndoTakesItOut() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.REMINDERS, LinkLevel.ASK)
        send(matt, samOnMatt, RemOffer("bring the charger", (t0 / 1000 / 60 + 60) * 60))
        until("a card on Sam's side") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        assertEquals(DeskYes.ADD_REMINDER, card.yes)
        sam.reminders.writable = false
        assertFalse(on(sam) { it.answer(card.id, DeskChoice.YES) })
        sam.reminders.writable = true
        assertTrue(on(sam) { it.answer(card.id, DeskChoice.YES) })
        assertEquals("bring the charger", sam.reminders.list.single().second)
    }

    @Test fun onItsOwnAtMostThreeRemindersADayFromOnePersonThenACard() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.REMINDERS, LinkLevel.AUTO)
        for (i in 1..4) send(matt, samOnMatt, RemOffer("reminder $i", (t0 / 1000 / 60 + 60) * 60))
        until("three added, the fourth a card") { sam.reminders.list.size == 3 && sam.r.box.count() == 1 }
        idle(matt, sam)
        assertEquals(3, sam.reminders.list.size)
        val e = handled(sam).first()
        assertTrue(on(sam) { it.undo(e.entry.id) })
        assertEquals(2, sam.reminders.list.size)
    }

    @Test fun aContactCardIsAlwaysATapAndAddsSomeoneNewUnconfirmedWithoutRobotLinks() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        // Even at Answer on its own (refused for contact cards), a card waits for your tap.
        level(sam, mattOnSam, LinkCapability.CONTACT_CARDS, LinkLevel.ASK)
        send(matt, samOnMatt, CardOffer.of(ContactCard("Jo Lee", listOf("+19025550100"), listOf("jo@example.com"), listOf("jo@abcdefghij.onion"))))
        sam.people.list += Person("p-bob", "Bob", emails = listOf("bob@example.com"))
        send(matt, samOnMatt, CardOffer.of(ContactCard("Robert", emails = listOf("bob@example.com"))))
        until("two cards on Sam's side") { sam.r.box.count() == 2 }
        val cards = on(sam) { it.view()!!.cards }
        assertTrue(cards.all { it.yes == DeskYes.ADD_CONTACT })
        for (c in cards) assertTrue(on(sam) { it.answer(c.id, DeskChoice.YES) })
        assertEquals(2, sam.people.list.size)
        val jo = sam.people.list.single { it.name == "Jo Lee" }
        assertFalse(jo.confirmed)
        assertEquals(PersonSource.LINK_CARD, jo.source)
        assertTrue(jo.xmpp.isEmpty())
        // Bob, whom Sam has, is never changed.
        assertEquals(Person("p-bob", "Bob", emails = listOf("bob@example.com")), sam.people.list.single { it.id == "p-bob" })
        // F144: and the yes that added nobody says so, where Jo's says you answered.
        assertEquals(1, on(sam) { it.view()!!.lines.count { l -> l.entry.state == DeskState.ALREADY_HAVE } })
        assertTrue(handled(sam).isEmpty())
        assertTrue(PairCode.PREFIX.isNotEmpty())
    }

    @Test fun aQuestionWaitsForYourWordsAndTheAnswerIsKeptOnlyForWhatYouAsked() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.QUESTIONS, LinkLevel.AUTO)
        // Nothing shared answers it: a card, even at Answer on its own (D17).
        val q = send(matt, samOnMatt, QAsk("where is the party?"))
        until("a card on Sam's side") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        assertNull(card.yes)
        assertFalse(on(sam) { it.answerWith(card.id, "   ") })
        assertTrue(on(sam) { it.answerWith(card.id, "At the park, 7 pm") })
        until("Matt keeps Sam's answer") { replies(matt).isNotEmpty() }
        assertEquals(QReply(q.id, "At the park, 7 pm"), replies(matt).single().request!!.body)
        // Shared: answered on its own with what you shared.
        sam.shares["what time?"] = "7 pm"
        val q2 = send(matt, samOnMatt, QAsk("what time?"))
        until("Matt keeps the second answer") { replies(matt).size == 2 }
        assertEquals(QReply(q2.id, "7 pm"), replies(matt).first { (it.request!!.body as QReply).re == q2.id }.request!!.body)
        // An answer to nothing Matt asked is never kept.
        send(sam, mattOnSam, QReply(LinkIds.newId(), "hello"))
        idle(matt, sam)
        assertEquals(2, replies(matt).size)
    }

    @Test fun aQuestionCanBeAnsweredWithAFileOnlyOnYourTapAndOnlyWhileOpen() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.QUESTIONS, LinkLevel.AUTO)
        val q = send(matt, samOnMatt, QAsk("where is the invoice?"))
        until("a card on Sam's side") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        val offer = LinkIds.newId()
        // Not an id, or locked: refused, and the card stays.
        assertFalse(on(sam) { it.answerWithFile(card.id, "../../etc/passwd") })
        sam.env.locked = true
        assertFalse(on(sam) { it.answerWithFile(card.id, offer) })
        sam.env.locked = false
        assertEquals(1, on(sam) { it.view()!!.cards.size })
        assertTrue(on(sam) { it.answerWithFile(card.id, offer) })
        until("Matt keeps Sam's answer") { replies(matt).isNotEmpty() }
        assertEquals(QReply(q.id, file = offer), replies(matt).single().request!!.body)
        assertEquals(0, on(sam) { it.view()!!.cards.size })
    }
}
