// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.headset.AnnounceCode
import app.heyrobot.core.messages.VoiceReply
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.ReplyResult
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.SpokenChoice
import app.heyrobot.core.output.SpokenLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val EN = SpokenLang.EN
private val FR = SpokenLang.FR

/** Calls hands-free (design §4.5 flow 2, §4.11 CallTarget). */
class CallTargetTest {
    private val momMobile = PhoneCandidate("Martine Martin", "+1 514 555 0101", PhoneLabel.MOBILE)
    private val momWork = PhoneCandidate("Martine Martin", "+1 514 555 0202", PhoneLabel.WORK)
    private val momHome = PhoneCandidate("Martine Martin", "+1 514 555 0303", PhoneLabel.HOME)
    private val dialer = CallRules()
    private val direct = CallRules(CallSetting.CALL_AFTER_YES, hasCallPermission = true)
    private val emergency = { n: String -> EmergencyNumbers.basic(n, "CA") }

    private fun plan(asked: String, c: List<PhoneCandidate>, rules: CallRules = dialer, locked: Boolean = false, trusted: Boolean = false, lang: SpokenLang = EN) =
        CallTarget.plan(asked, c, rules, locked, trusted, lang, emergency)

    @Test fun oneNumberIsReadBack() {
        val p = plan("Mom", listOf(momMobile)) as CallPlan.Confirm
        assertEquals("Call Martine Martin, mobile, ending 0101?", app.heyrobot.core.output.SpokenCards.prompt(p.card, EN))
        assertEquals(CallWay.DIAL, p.way)
        assertEquals("Appeler Martine Martin, cellulaire, qui finit par 0101", (plan("maman", listOf(momMobile), lang = FR) as CallPlan.Confirm).card.action)
        // A direct call only with the setting, the permission and the Safety & privacy switch.
        assertEquals(CallWay.CALL, (plan("Mom", listOf(momMobile), direct) as CallPlan.Confirm).way)
        assertEquals(CallWay.DIAL, (plan("Mom", listOf(momMobile), direct.copy(hasCallPermission = false)) as CallPlan.Confirm).way)
        assertEquals(CallWay.DIAL, (plan("Mom", listOf(momMobile), direct.copy(callAfterSpokenYes = false)) as CallPlan.Confirm).way)
        // "Yes" or "call" places it; "no" doesn't; silence twice shows the call tool's card on the screen.
        for (s in listOf("yes", "call", "call her", "oui", "appelle", "appelle-la")) {
            assertEquals(s, CallNext.Place("+15145550101", CallWay.DIAL), CallTarget.answer(p, SpokenChoice.answer(s, p.card), 0, 0, EN))
        }
        assertEquals(CallNext.Cancelled("Okay, no call."), CallTarget.answer(p, SpokenChoice.answer("no", p.card), 0, 0, EN))
        assertEquals(CallNext.Cancelled("D'accord, pas d'appel."), CallTarget.answer(p, SpokenChoice.answer("non", p.card), 0, 0, FR))
        assertEquals(CallNext.OnScreen("It's on the screen."), CallTarget.answer(p, SpokenChoice.answer("", p.card), 1, 0, EN))
        assertEquals(CallNext.OnScreen("C'est à l'écran."), CallTarget.answer(p, SpokenChoice.answer("", p.card), 1, 0, FR))
        assertEquals(CallNext.Reread, CallTarget.answer(p, SpokenChoice.answer("repeat", p.card), 0, 0, EN))
    }

    @Test fun severalNumbersAreSpokenChoicesThenReadBack() {
        val p = plan("Mom", listOf(momMobile, momWork)) as CallPlan.Choose
        assertEquals("Call Mom: Martine Martin, mobile, or Martine Martin, work?", app.heyrobot.core.output.SpokenCards.prompt(p.card, EN))
        for ((s, i) in mapOf("the second one" to 1, "la deuxième" to 1, "the work one" to 1, "her cell" to 0, "le cell" to 0, "mobile" to 0, "number two" to 1,
                "celui du travail" to 1, "the first" to 0)) {
            val next = CallTarget.answer(p, SpokenChoice.answer(s, p.card), 0, 0, EN)
            assertEquals(s, CallNext.Ask(CallTarget.confirm(listOf(momMobile, momWork)[i], CallWay.DIAL, EN)), next)
        }
        // A yes doesn't say which: asked again.
        assertEquals(CallNext.AskAgain("Say the first or the second, or no."), CallTarget.answer(p, SpokenChoice.answer("yes", p.card), 0, 0, EN))
        // The chosen one is read back before it is called.
        val confirm = (CallTarget.answer(p, SpokenChoice.answer("the second one", p.card), 0, 0, EN) as CallNext.Ask).plan
        assertEquals("Call Martine Martin, work, ending 0202", confirm.card.action)
        // Three is the most read aloud; two with the same label say their endings.
        assertTrue(plan("Mom", listOf(momMobile, momWork, momHome)) is CallPlan.Choose)
        val twins = plan("Mom", listOf(momMobile, momMobile.copy(number = "+1 514 555 0999"))) as CallPlan.Choose
        assertEquals(listOf("Martine Martin, mobile, ending 0101", "Martine Martin, mobile, ending 0999"), twins.card.options.map { it.label })
        // More than three: the call tool's card on the screen, as a typed "call Mom".
        assertEquals(CallPlan.OnScreen("Several people match. Pick one on the screen.", CallOnScreen.TOO_MANY),
            plan("Mom", listOf(momMobile, momWork, momHome, momHome.copy(number = "5145550404"))))
    }

    @Test fun emergencyNumbersAlwaysOpenTheDialer() {
        val e = plan("911", emptyList(), direct)
        assertEquals(CallPlan.Emergency("911", "I can't place emergency calls. The dialer is open with 911; tap Call."), e)
        assertEquals("Je ne peux pas faire d'appels d'urgence. Le composeur est ouvert avec le 911; appuie sur Appeler.", (plan("911", emptyList(), lang = FR) as CallPlan.Emergency).say)
        // Locked, untrusted: still the dialer (Android's emergency dialer), never refused.
        assertTrue(plan("9 1 1", emptyList(), direct, locked = true) is CallPlan.Emergency)
        assertTrue(plan("112", emptyList(), direct) is CallPlan.Emergency)
        // A contact whose number is an emergency number too.
        assertTrue(plan("Police", listOf(PhoneCandidate("Police", "911")), direct) is CallPlan.Emergency)
        // An ordinary number said aloud: read back like a contact.
        val n = plan("514 555 0101", emptyList(), direct) as CallPlan.Confirm
        assertEquals("Call 5145550101, ending 0101", n.card.action)
        assertEquals(CallWay.CALL, n.way)
        assertTrue(EmergencyNumbers.basic("17", "FR"))
        assertFalse(EmergencyNumbers.basic("17", "CA"))
        assertTrue(EmergencyNumbers.basic("999", "GB"))
        assertFalse(EmergencyNumbers.basic("", "CA"))
        assertEquals("+15145550101", CallTarget.dialable(" +1 (514) 555-0101 "))
    }

    /** 1.0 audit (physical-call-e164-plus-dropped): a non-+1 contact is called with its "+", never as national digits. */
    @Test fun anInternationalNumberKeepsItsPlus() {
        val marie = PhoneCandidate("Marie", "+33612345678", PhoneLabel.MOBILE)
        val p = plan("Marie", listOf(marie), direct) as CallPlan.Confirm
        assertEquals("Call Marie, mobile, ending 5678?", app.heyrobot.core.output.SpokenCards.prompt(p.card, EN))
        assertEquals(CallNext.Place("+33612345678", CallWay.CALL), CallTarget.answer(p, SpokenChoice.answer("yes", p.card), 0, 0, EN))
        assertEquals("+33612345678", CallTarget.dialable("+33612345678"))
        // A number typed with its "+" keeps it too.
        assertEquals("+33612345678", ((plan("+33 6 12 34 56 78", emptyList(), direct) as CallPlan.Confirm).target.dialable))
    }

    /**
     * 1.0 audit (physical-car-trusted-any-number): the trusted car session on the locked phone calls People and contacts
     * only; a number said aloud needs the unlock (an emergency number still opens the dialer).
     */
    @Test fun aLockedTrustedSessionCallsOnlyPeopleAndContacts() {
        assertEquals(CallPlan.Refuse("Unlock to call.", CallRefuse.LOCKED), plan("1 900 555 0199", emptyList(), direct, locked = true, trusted = true))
        assertTrue(plan("Mom", listOf(momMobile), direct, locked = true, trusted = true) is CallPlan.Confirm)
        assertTrue(plan("1 900 555 0199", emptyList(), direct) is CallPlan.Confirm)
        assertTrue(plan("9 1 1", emptyList(), direct, locked = true, trusted = true) is CallPlan.Emergency)
    }

    @Test fun whatCantBeCalled() {
        assertEquals(CallPlan.Refuse("Unlock to call.", CallRefuse.LOCKED), plan("Mom", listOf(momMobile), locked = true))
        assertTrue(plan("Mom", listOf(momMobile), locked = true, trusted = true) is CallPlan.Confirm)
        assertEquals(CallPlan.Refuse("Robot can't call that address.", CallRefuse.XMPP_ONLY), plan("Bob", listOf(PhoneCandidate("Bob", "bob@jabber.example", xmpp = true))))
        // An XMPP address next to a number: the number.
        assertEquals(momMobile, (plan("Mom", listOf(PhoneCandidate("Martine", "m@x.example", xmpp = true), momMobile)) as CallPlan.Confirm).target)
        assertEquals(CallPlan.Refuse("I don't have a number for Bob.", CallRefuse.NOT_FOUND), plan("Bob", emptyList()))
        assertEquals(CallPlan.Refuse("Je n'ai pas de numéro pour Bob.", CallRefuse.NOT_FOUND), plan("Bob", emptyList(), lang = FR))
        // A name learned from Cheogram: the call tool's card on the screen, which checks it.
        assertEquals(CallPlan.OnScreen("Check who it is on the screen.", CallOnScreen.LEARNED), plan("Marie", listOf(momMobile.copy(learned = true))))
        assertEquals(CallPlan.OnScreen("Vérifie qui c'est à l'écran.", CallOnScreen.LEARNED), plan("Marie", listOf(momMobile.copy(learned = true)), lang = FR))
        assertFalse(momMobile.toString().contains("Martine") || momMobile.toString().contains("0101"))
    }
}

/** Messages in the car (0.7; §4.5 flow 3, §4.11 "Reply payload"). */
class CarMessagesTest {
    private val marie = MessageNotice("CHEOGRAM:me#marie", "Marie", "k1", "On se voit à 5?", "Cheogram", replyable = true)
    private val s = CarSettings()
    private val safety = HandsFreeSafety()

    @Test fun announcing() {
        assertEquals(CarAnnouncement.Announce("Message from Marie. Read it?"), CarMessages.announce(marie, s, false, false, true, EN))
        assertEquals(CarAnnouncement.Announce("Message de Marie. Je te le lis?"), CarMessages.announce(marie, s, false, false, true, FR))
        assertEquals(CarAnnouncement.Skip(AnnounceCode.OFF), CarMessages.announce(marie, s.copy(announce = CarAnnounce.OFF), false, false, true, EN))
        assertEquals(CarAnnouncement.Skip(AnnounceCode.LOCKED), CarMessages.announce(marie, s, true, false, true, EN))
        assertTrue(CarMessages.announce(marie, s, true, true, true, EN) is CarAnnouncement.Announce)
        assertEquals(CarAnnouncement.Skip(AnnounceCode.DND), CarMessages.announce(marie.copy(dndAllows = false), s, false, false, true, EN))
        assertTrue(CarMessages.announce(marie.copy(dndAllows = false), s.copy(announceFrom = CarAnnounceFrom.EVERYONE), false, false, false, EN) is CarAnnouncement.Announce)
        assertEquals(CarAnnouncement.Skip(AnnounceCode.NOT_ALLOWED), CarMessages.announce(marie, s.copy(announceFrom = CarAnnounceFrom.CHOSEN_PEOPLE), false, false, false, EN))
        // The whole message only when both settings say so: the car is never private.
        assertTrue(CarMessages.announce(marie, s.copy(announce = CarAnnounce.READ_WHOLE), false, false, true, EN) is CarAnnouncement.Announce)
        assertEquals(CarAnnouncement.ReadNow("Marie says: On se voit à 5?"),
            CarMessages.announce(marie, s.copy(announce = CarAnnounce.READ_WHOLE, readAloud = ReadAloud.ALWAYS), false, false, true, EN))
        assertEquals("Marie dit : On se voit à 5?", CarMessages.says(marie, FR))
        assertTrue(CarMessages.offersReply(marie, s, safety))
        assertFalse(CarMessages.offersReply(marie.copy(group = "Famille"), s, safety))
        assertFalse(CarMessages.offersReply(marie.copy(replyable = false), s, safety))
        assertFalse(CarMessages.offersReply(marie, s.copy(voiceReplies = false), safety))
        assertFalse(CarMessages.offersReply(marie, s, safety.copy(replyAfterSpokenYes = false)))
    }

    @Test fun whatIsSentIsExactlyWhatWasReadBack() {
        for ((said, body) in listOf("Tell her I'm 10 minutes away" to "I'm 10 minutes away", "I'm 10 minutes away" to "I'm 10 minutes away",
                "reply that I'll be late, sorry!" to "I'll be late, sorry!", "Dis-lui que j'arrive dans 10 minutes" to "j'arrive dans 10 minutes",
                "Réponds-lui que c'est correct" to "c'est correct", "hey robot, tell her ok" to "ok", "Say: see you at 5." to "see you at 5.")) {
            val start = CarMessages.startReply(marie, said, s, safety, false, false, EN, VoiceReply.BY_VOICE) as ReplyStart.ReadBack
            assertEquals(said, body, start.draft.text)
            // The read-back says those exact words, and "send" sends those exact words.
            assertTrue(said, start.say.contains(body))
            val card = CarMessages.card(start.draft, EN)
            for (yes in listOf("send", "send it", "yes", "envoie", "oui, envoie-le")) {
                assertEquals(yes, ReplyNext.Send("CHEOGRAM:me#marie", body), CarMessages.answer(start.draft, SpokenChoice.answer(yes, card), 0, 0, EN))
            }
        }
        val d = (CarMessages.startReply(marie, "tell her I'm 10 minutes away", s, safety, false, false, EN, VoiceReply.BY_VOICE) as ReplyStart.ReadBack)
        assertEquals("Reply to Marie: I'm 10 minutes away. Send?", d.say)
        assertEquals("Réponse à Marie : I'm 10 minutes away. J'envoie?", CarMessages.readBack(d.draft, FR))
        assertEquals("Reply to Marie: See you at 5? Send?", CarMessages.readBack(d.draft.copy(text = "See you at 5?"), EN))
        // "No" at the read-back sends nothing; silence twice hands the words to the screen's reply card (MessageFlow).
        val card = CarMessages.card(d.draft, EN)
        assertEquals(ReplyNext.Done("Okay, not sent.", ReplyEnd.DECLINED), CarMessages.answer(d.draft, SpokenChoice.answer("no", card), 0, 0, EN))
        assertEquals(ReplyNext.Done("Your reply is on the screen.", ReplyEnd.SILENCE), CarMessages.answer(d.draft, SpokenChoice.answer("", card), 1, 0, EN))
        assertEquals(ReplyNext.Done("Ta réponse est à l'écran.", ReplyEnd.SILENCE), CarMessages.answer(d.draft, SpokenChoice.answer("", card), 1, 0, FR))
        assertEquals(ReplyNext.AskAgain("Say yes or no."), CarMessages.answer(d.draft, SpokenChoice.answer("send it to Bob", card), 0, 0, EN))
        // Robot's own read-back heard back is never a "send".
        assertFalse(CarMessages.answer(d.draft, SpokenChoice.answer("I'm 10 minutes away send", card, d.say), 0, 0, EN) is ReplyNext.Send)
        assertEquals("Handed to Cheogram.", CarMessages.result(ReplyResult.HANDED_OVER, "Cheogram", EN))
        assertEquals("Remis à Cheogram.", CarMessages.result(ReplyResult.HANDED_OVER, "Cheogram", FR))
        // Not handed over: never "when you park"; the screen's card has the words (but on the lock screen).
        assertEquals("That didn't work. Your reply is on the screen.", CarMessages.result(ReplyResult.FAILED, "Cheogram", EN))
        assertEquals("A new message just came. Your reply is on the screen.", CarMessages.result(ReplyResult.NEW_MESSAGES, "Cheogram", EN))
        assertEquals("I can't send that by voice. Your reply is on the screen.", CarMessages.result(ReplyResult.NO_REPLY_ACTION, "Cheogram", EN))
        assertEquals(CarGate.UNLOCK_ONCE.en, CarMessages.result(ReplyResult.LOCKED, "Cheogram", EN))
        for (r in ReplyResult.entries) for (l in SpokenLang.entries) {
            val line = CarMessages.result(r, "Cheogram", l)
            assertFalse(line, line.contains("park") || line.contains("stationn"))
        }
        assertFalse(d.draft.toString().contains("minutes"))
    }

    /**
     * 1.0 (the user, 13 Oct 2026: no driving restrictions): what the car can't read back and send by voice goes to the
     * screen's own reply to the conversation, with your words, exactly as outside the car. Nothing is kept for parking.
     */
    @Test fun repliesNotSentByVoiceGoToTheScreen() {
        assertEquals(ReplyStart.OnScreen(ReplyEnd.NOT_REPLYABLE, "ok"), CarMessages.startReply(marie.copy(replyable = false), "ok", s, safety, false, false, EN, VoiceReply.BY_VOICE))
        assertEquals(ReplyStart.OnScreen(ReplyEnd.GROUP, "ok"), CarMessages.startReply(marie.copy(group = "Famille"), "ok", s, safety, false, false, EN, VoiceReply.TAP_ONLY))
        val long = CarMessages.startReply(marie, "x ".repeat(200), s, safety, false, false, EN, VoiceReply.BY_VOICE) as ReplyStart.OnScreen
        assertEquals(ReplyEnd.TOO_LONG, long.why)
        assertTrue(long.text.length > CarMessages.MAX_REPLY)
        // A tap-only reply, and one that opens the chat: the screen's card or chat, with the words.
        assertEquals(ReplyStart.OnScreen(ReplyEnd.NEEDS_TAP, "I'm late"), CarMessages.startReply(marie, "tell her I'm late", s, safety, false, false, EN, VoiceReply.TAP_ONLY))
        assertEquals(ReplyStart.OnScreen(ReplyEnd.NEEDS_TAP, "I'm late"), CarMessages.startReply(marie, "tell her I'm late", s, safety, false, false, EN, VoiceReply.OPEN_CHAT))
        // "Replies by voice" off and "Send a reply after a spoken yes" off: the screen's card, as outside the car.
        assertEquals(ReplyStart.OnScreen(ReplyEnd.VOICE_OFF, "ok"), CarMessages.startReply(marie, "ok", s.copy(voiceReplies = false), safety, false, false, EN, VoiceReply.BY_VOICE))
        assertEquals(ReplyStart.OnScreen(ReplyEnd.VOICE_OFF, "ok"), CarMessages.startReply(marie, "ok", s, safety.copy(replyAfterSpokenYes = false), false, false, EN, VoiceReply.BY_VOICE))
        // 0.7's refusal: its own refusal on the screen.
        assertEquals(ReplyStart.OnScreen(ReplyEnd.LOCKED, "ok"), CarMessages.startReply(marie, "ok", s, safety, false, false, EN, VoiceReply.REFUSED))
        assertEquals(ReplyStart.OnScreen(ReplyEnd.LOCKED, "ok"), CarMessages.startReply(marie, "ok", s, safety, true, true, EN, VoiceReply.REFUSED))
        // Locked without a trusted session: 0.7's own lock-screen rule decides, as on a locked phone outside the car.
        assertEquals(ReplyStart.OnScreen(ReplyEnd.LOCKED, "ok"), CarMessages.startReply(marie, "ok", s, safety, true, false, EN, VoiceReply.BY_VOICE))
        assertEquals(ReplyStart.OnScreen(ReplyEnd.LOCKED, "ok"), CarMessages.startReply(marie.copy(replyable = false), "ok", s, safety, true, false, EN, VoiceReply.TAP_ONLY))
        // Without words where the screen takes it: "" (0.7 then drafts one for a tap, as a "reply" without words does).
        assertEquals(ReplyStart.OnScreen(ReplyEnd.VOICE_OFF, ""), CarMessages.startReply(marie, "tell her", s.copy(voiceReplies = false), safety, false, false, EN, VoiceReply.BY_VOICE))
        // Read back and sent by voice where everything allows it; the trusted session counts as unlocked; empty asks.
        assertTrue(CarMessages.startReply(marie, "ok", s, safety, false, false, EN, VoiceReply.BY_VOICE) is ReplyStart.ReadBack)
        assertTrue(CarMessages.startReply(marie, "ok", s, safety, true, true, EN, VoiceReply.BY_VOICE) is ReplyStart.ReadBack)
        assertEquals(ReplyStart.AskText("What should the reply say?"), CarMessages.startReply(marie, "tell her", s, safety, false, false, EN, VoiceReply.BY_VOICE))
        assertEquals(ReplyStart.AskText("What should the reply say?"), CarMessages.startReply(marie.copy(replyable = false), "tell her", s, safety, false, false, EN, VoiceReply.TAP_ONLY))
    }
}

/** Car mode's output, navigation and the drive brief (§4.3, §4.5). */
class DrivingTest {
    @Test fun shortAnswers() {
        assertEquals(CarSpeech.Short("It's 4 degrees.", null), CarSpeech.shorten("It's 4 degrees.", EN))
        assertEquals(CarSpeech.Short("One. Two.", null), CarSpeech.shorten("One.  Two.", EN))
        assertEquals(CarSpeech.Short("One. Two? Want more?", "Three! Four."), CarSpeech.shorten("One. Two? Three! Four.", EN))
        assertEquals(CarSpeech.Short("Un. Deux. Tu en veux plus?", "Trois."), CarSpeech.shorten("Un. Deux. Trois.", FR))
        val long = "word ".repeat(60).trim() + "."
        val s = CarSpeech.shorten(long, EN)
        assertTrue(s.spoken.endsWith("… Want more?"))
        assertTrue(s.spoken.length <= 180 + " Want more?".length + 1)
        assertEquals(long.split(' ').size, (s.spoken.removeSuffix("… Want more?").split(' ') + s.rest!!.split(' ')).size)
        assertEquals(CarSpeech.Short("", null), CarSpeech.shorten("  ", EN))
        assertTrue(CarSpeech.PROMPT_LINE.length < 120)
        // The style only: nothing in the model's car line keeps it from a tool, a card or the screen.
        for (w in listOf("driving", "read", "tap", "screen", "never")) assertFalse(w, CarSpeech.PROMPT_LINE.lowercase().contains(w))
    }

    @Test fun theCarScreen() {
        assertEquals("Calling Mom", CarScreen.status("Calling  Mom"))
        val st = CarScreen.status("Calling Martine Martin, mobile, and a very long tail that goes on")
        assertTrue(st.length <= CarScreen.MAX_STATUS)
        assertTrue(st.endsWith("…"))
        assertEquals(listOf(CarScreen.Button.TALK, CarScreen.Button.CALL, CarScreen.Button.END), CarScreen.buttons(messages = false, home = false))
        assertEquals(5, CarScreen.buttons(messages = true, home = true).size)
    }

    @Test fun navigation() {
        val home = SavedPlace.of("Maison", 45.5, -73.6)!!
        val s = CarSettings(home = home)
        val here = 45.52 to -73.58
        val r = CarNavigate.plan(NavDestination.Home, s, here, locked = false, trusted = false, lang = EN) as NavPlan.Route
        assertEquals("om://route?sll=45.520000,-73.580000&saddr=Here&dll=45.500000,-73.600000&daddr=Maison&type=vehicle", r.uri)
        assertEquals("Route ready in your maps app. Tap Start to go.", r.say)
        assertEquals("L'itinéraire est prêt dans ton application de cartes. Appuie sur Démarrer pour partir.",
            CarNavigate.plan(NavDestination.Home, s, here, false, false, FR).say)
        assertEquals(NavKind.HOME, r.kind)
        // Without a position: the place itself.
        assertTrue((CarNavigate.plan(NavDestination.Home, s, null, false, false, EN) as NavPlan.Route).uri.startsWith("geo:45.500000,-73.600000"))
        assertEquals(NavPlan.NoPlace("Save your work address in Settings › Car mode first."), CarNavigate.plan(NavDestination.Work, s, here, false, false, EN))
        assertEquals("Enregistre d'abord l'adresse de la maison dans Réglages › Mode auto.", CarNavigate.plan(NavDestination.Home, CarSettings(), here, false, false, FR).say)
        // Locked: a saved place in a trusted session; otherwise unlock.
        assertEquals(NavPlan.Unlock("Unlock to start the route."), CarNavigate.plan(NavDestination.Home, s, here, true, false, EN))
        assertEquals(CarNavigate.ROUTE_LOCKED.en, CarNavigate.plan(NavDestination.Home, s, here, true, true, EN).say)
        assertEquals(NavPlan.Unlock("Unlock to start the route."), CarNavigate.plan(NavDestination.Other("airport"), s, here, true, true, EN))
        assertEquals(NavPlan.Search("airport", "Pick it in your maps app."), CarNavigate.plan(NavDestination.Other("airport"), s, here, false, false, EN))
        // Nothing waits for when you're stopped.
        for (l in listOf(CarNavigate.ROUTE_READY, CarNavigate.ROUTE_LOCKED, CarNavigate.PICK_IN_MAPS)) {
            assertFalse(l.en, l.en.contains("stopped") || l.fr.contains("arrêté"))
        }
        assertTrue(CarNavigate.plan(NavDestination.Nearest("gas station"), s, here, false, false, EN) is NavPlan.Nearest)
    }

    @Test fun theDriveBrief() {
        val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
        fun ctx(locked: Boolean, trusted: Boolean) = OutputContext.of(SessionKind.CAR, car, DeviceMemory(), PhoneState(locked), car, commRoute = true, carTrusted = trusted)
        val on = CarSettings(driveBrief = true)
        assertNull(DriveBrief.plan(CarSettings(), ctx(false, false), GuardSettings()))
        assertEquals(DriveBriefPlan(listOf(DriveItem.WEATHER), listOf(DriveItem.NEXT_EVENT), emptyList()), DriveBrief.plan(on, ctx(false, false), GuardSettings()))
        assertEquals(DriveBriefPlan(listOf(DriveItem.WEATHER, DriveItem.NEXT_EVENT), emptyList(), emptyList()),
            DriveBrief.plan(on, ctx(false, false), GuardSettings(readAloud = ReadAloud.ALWAYS)))
        assertEquals(DriveBriefPlan(listOf(DriveItem.WEATHER), emptyList(), listOf(DriveItem.NEXT_EVENT)), DriveBrief.plan(on, ctx(true, false), GuardSettings()))
        assertEquals(DriveBriefPlan(listOf(DriveItem.WEATHER), listOf(DriveItem.NEXT_EVENT), emptyList()), DriveBrief.plan(on, ctx(true, true), GuardSettings()))
    }
}

/** The call and message flows as state machines, driven by what is heard (§4.2 stories 3 and 4, §4.11). */
class CarFlowMachinesTest {
    private val mom = listOf(PhoneCandidate("Martine Martin", "5145550101", PhoneLabel.MOBILE), PhoneCandidate("Martine Martin", "5145550202", PhoneLabel.WORK))
    private val marie = MessageNotice("CHEOGRAM:me#marie", "Marie", "k1", "T'es où?", "Cheogram", replyable = true)
    private val c = MessageFlowContext(CarSettings(), HandsFreeSafety(), locked = false, trusted = false, lang = EN, voice = VoiceReply.BY_VOICE)

    private fun call(vararg heard: String, plan: CallPlan): Pair<CallFlowState, List<List<FlowEffect>>> {
        var (st, fx) = CallFlow.start(plan, "Mom", EN)
        val out = mutableListOf(fx)
        for (h in heard) { val (n, f) = CallFlow.on(st, h, EN); st = n; out += f }
        return st to out
    }

    @Test fun twoMomsTheSecondThenYes() {
        val plan = CallTarget.plan("Mom", mom, CallRules(), false, false, EN) { false }
        val (st, fx) = call("la deuxième", "yes", plan = plan)
        assertEquals(listOf(FlowEffect.Say("Call Mom: Martine Martin, mobile, or Martine Martin, work?", true)), fx[0])
        assertEquals(listOf(FlowEffect.Say("Call Martine Martin, work, ending 0202?", true)), fx[1])
        assertEquals(listOf(FlowEffect.Place("5145550202", CallWay.DIAL)), fx[2])
        assertTrue(st.done)
        // Nothing more once done.
        assertEquals(emptyList<FlowEffect>(), CallFlow.on(st, "yes", EN).second)
    }

    @Test fun robotsOwnPromptIsNotAnAnswerAndSilenceTwiceShowsTheCallCard() {
        val plan = CallTarget.plan("Mom", mom, CallRules(), false, false, EN) { false }
        val (st, fx) = call("Martine Martin mobile or Martine Martin work", "", plan = plan)
        assertEquals(listOf(FlowEffect.Say("Say the first or the second, or no.", true)), fx[1])
        assertEquals(listOf(FlowEffect.Say("It's on the screen.", false), FlowEffect.CallCard(CallOnScreen.NO_ANSWER)), fx[2])
        assertTrue(st.done)
    }

    @Test fun emergencyAndRefusals() {
        val (st, fx) = CallFlow.start(CallTarget.plan("911", emptyList(), CallRules(CallSetting.CALL_AFTER_YES, true), false, false, EN) { it == "911" }, "911", EN)
        assertEquals(FlowEffect.Place("911", CallWay.DIAL), fx[0])
        assertTrue(st.done)
        // A learned name, or more than three numbers: the call tool's own card on the screen, as a typed "call Marie".
        val (_, learned) = CallFlow.start(CallTarget.plan("Marie", listOf(mom[0].copy(learned = true)), CallRules(), false, false, EN) { false }, "Marie", EN)
        assertEquals(listOf(FlowEffect.Say("Check who it is on the screen.", false), FlowEffect.CallCard(CallOnScreen.LEARNED)), learned)
        val four = mom + mom.map { it.copy(number = it.number.replace("01", "09").replace("02", "08")) }
        val (_, many) = CallFlow.start(CallTarget.plan("Mom", four, CallRules(), false, false, EN) { false }, "Mom", EN)
        assertEquals(FlowEffect.CallCard(CallOnScreen.TOO_MANY), many.last())
        val (_, refused) = CallFlow.start(CallTarget.plan("Mom", mom, CallRules(), true, false, EN) { false }, "Mom", EN)
        assertEquals(listOf(FlowEffect.Say("Unlock to call.", false)), refused)
        // "Repeat" re-reads; "no" ends it.
        val plan = CallTarget.plan("Mom", mom.take(1), CallRules(), false, false, EN) { false }
        val (st2, fx2) = call("repeat", "no", plan = plan)
        assertEquals(fx2[0], fx2[1])
        assertEquals(listOf(FlowEffect.Say("Okay, no call.", false)), fx2[2])
        assertTrue(st2.done)
    }

    private fun msg(start: Pair<MessageFlowState?, List<FlowEffect>>, vararg heard: String, ctx: MessageFlowContext = c): Pair<MessageFlowState?, List<List<FlowEffect>>> {
        var st = start.first
        val out = mutableListOf(start.second)
        for (h in heard) { val (n, f) = MessageFlow.on(st!!, h, ctx); st = n; out += f }
        return st to out
    }

    @Test fun announceReadReplyReadBackSend() {
        val (st, fx) = msg(MessageFlow.start(marie, c, senderAllowed = true), "yes", "yes", "tell her I'm 10 minutes away", "send")
        assertEquals(listOf(FlowEffect.Say("Message from Marie. Read it?", true)), fx[0])
        assertEquals(listOf(FlowEffect.Say("Marie says: T'es où?", false), FlowEffect.Say("Reply?", true)), fx[1])
        assertEquals(listOf(FlowEffect.Say("What should the reply say?", true)), fx[2])
        assertEquals(listOf(FlowEffect.Say("Reply to Marie: I'm 10 minutes away. Send?", true)), fx[3])
        // What is sent is exactly what was read back.
        assertEquals(listOf(FlowEffect.Send("CHEOGRAM:me#marie", "I'm 10 minutes away")), fx[4])
        assertEquals(MessageStep.DONE, st!!.step)
    }

    @Test fun theReplyCanComeAtOnceAndNoSendsNothing() {
        val (_, fx) = msg(MessageFlow.start(marie, c, true), "oui", "dis-lui que j'arrive", "non")
        assertEquals(listOf(FlowEffect.Say("Reply to Marie: j'arrive. Send?", true)), fx[2])
        assertEquals(listOf(FlowEffect.Say("Okay, not sent.", false)), fx[3])
        assertFalse(fx.flatten().any { it is FlowEffect.Send })
        // Robot's read-back heard back is not a "send"; nothing heard twice puts the reply on the screen's card, with your words.
        val (st2, fx2) = msg(MessageFlow.start(marie, c, true), "yes", "tell her I'm late", "Reply to Marie I'm late send", "")
        assertFalse(fx2.flatten().any { it is FlowEffect.Send })
        assertEquals(listOf(FlowEffect.Say("Your reply is on the screen.", false), FlowEffect.OnScreen("I'm late", ReplyEnd.SILENCE)), fx2.last())
        assertEquals(MessageStep.DONE, st2!!.step)
    }

    @Test fun messagesThatEndEarly() {
        // "No" to "Read it?": quiet, the message stays in its app.
        val (st, fx) = msg(MessageFlow.start(marie, c, true), "no")
        assertEquals(emptyList<FlowEffect>(), fx[1])
        assertEquals(MessageStep.DONE, st!!.step)
        // No reply offered for a group: read, then done.
        val (gst, gfx) = msg(MessageFlow.start(marie.copy(group = "Famille"), c, true), "yes")
        assertEquals(listOf(FlowEffect.Say("Marie says: T'es où?", false)), gfx[1])
        assertEquals(MessageStep.DONE, gst!!.step)
        // Off, or locked without trust: nothing at all.
        assertEquals(null to emptyList<FlowEffect>(), MessageFlow.start(marie, c.copy(settings = CarSettings(announce = CarAnnounce.OFF)), true))
        assertEquals(null to emptyList<FlowEffect>(), MessageFlow.start(marie, c.copy(locked = true), true))
        // Read at once (both settings), then the reply offer.
        val whole = c.copy(settings = CarSettings(announce = CarAnnounce.READ_WHOLE, readAloud = ReadAloud.ALWAYS))
        assertEquals(listOf(FlowEffect.Say("Marie says: T'es où?", false), FlowEffect.Say("Reply?", true)), MessageFlow.start(marie, whole, true).second)
        // "Reply …" straight from the router, for an app without a Reply action: the screen's reply (it opens the chat).
        val (_, rfx) = MessageFlow.reply(marie.copy(replyable = false), "reply I'm late", c)
        assertEquals(listOf(FlowEffect.OnScreen("I'm late", ReplyEnd.NOT_REPLYABLE)), rfx)
        // Dictation that says nothing twice: no reply, nothing kept.
        val (dst, dfx) = msg(MessageFlow.start(marie, c, true), "yes", "yes", "", "")
        assertEquals(listOf(FlowEffect.Say("Okay, not sent.", false)), dfx.last())
        assertEquals(MessageStep.DONE, dst!!.step)
        // "Read my messages".
        assertEquals(MessageStep.OFFERED, MessageFlow.read(marie, c).first.step)
    }

    /**
     * 1.0: "text Bob I'm late" in the car is not a car command, so the panel's own text flow takes it, exactly as outside the
     * car, unlocked and locked (the app's route no longer keeps it for parking: ProcessIsolationTest).
     */
    @Test fun newTextsAreNotACarCommand() {
        for (q in listOf("text Bob I'm late", "texte à Marie que j'arrive", "send a text to Mom saying I'm on my way")) {
            assertNull(q, app.heyrobot.core.output.HandsFreeRouter.route(q, carMode = true))
            assertEquals(q, app.heyrobot.core.output.HandsFreeRouter.route(q, carMode = false), app.heyrobot.core.output.HandsFreeRouter.route(q, carMode = true))
        }
    }

    /** 1.0: in the car a reply that can't go by voice is the screen's, unlocked and locked, as outside the car; nothing is parked. */
    @Test fun theScreenTakesWhatTheCarDoesntSendByVoice() {
        // "Reply …" for an app without a Reply action: the screen's own reply, with the words.
        val (st, fx) = MessageFlow.reply(marie.copy(replyable = false), "reply I'm late", c)
        assertEquals(listOf(FlowEffect.OnScreen("I'm late", ReplyEnd.NOT_REPLYABLE)), fx)
        assertEquals(MessageStep.DONE, st.step)
        // Read, "Reply?", then words only a tap may send: the screen's card.
        val tap = c.copy(voice = VoiceReply.TAP_ONLY)
        val (tst, tfx) = msg(MessageFlow.start(marie, tap, true), "yes", "tell her I'm late", ctx = tap)
        assertEquals(listOf(FlowEffect.OnScreen("I'm late", ReplyEnd.NEEDS_TAP)), tfx.last())
        assertFalse(tfx.flatten().any { it is FlowEffect.Send })
        assertEquals(MessageStep.DONE, tst!!.step)
        // Locked in a trusted session with 0.7's "Read and reply": the same; locked without one, 0.7's lock rule on the screen.
        val trusted = tap.copy(locked = true, trusted = true)
        assertEquals(listOf(FlowEffect.OnScreen("I'm late", ReplyEnd.NEEDS_TAP)), MessageFlow.reply(marie, "reply I'm late", trusted).second)
        val locked = c.copy(locked = true)
        assertEquals(listOf(FlowEffect.OnScreen("I'm late", ReplyEnd.LOCKED)), MessageFlow.reply(marie, "reply I'm late", locked).second)
        // A reply that may go by voice is still read back and sent.
        val (_, vfx) = msg(MessageFlow.start(marie, c, true), "yes", "tell her I'm late", "send")
        assertEquals(listOf(FlowEffect.Send("CHEOGRAM:me#marie", "I'm late")), vfx.last())
    }
}
