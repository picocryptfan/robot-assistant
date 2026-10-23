// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ToolStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1 (the l10n design's WP1): the turn, its cards and the read tools' answers in Québec French. What a card says follows
 * the turn's reply language, its buttons the menus' language, and the model always reads English.
 */
class CardsInFrenchTest {
    private val fr = Langs.of(Lang.FR)
    /** A French speaker with English menus. */
    private val frSpokenEnMenus = Langs(say = Lang.FR, ui = Lang.EN, system = Lang.EN)

    private class Model(private val calls: List<RawToolCall>) : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(calls)
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            answers += results
            return ModelOutcome.Done(DoneReason.END)
        }
        override fun close() {}
    }

    private class Actions(override val settings: RobotSettings = RobotSettings(), val ready: (Action) -> Prepared = { Prepared.Ready(it) }) : TurnActions {
        override val locked = false
        override suspend fun prepare(action: Action): Prepared = ready(action)
        override suspend fun run(action: Action): Executed = when (action) {
            is Action.SetTimer -> Executed(ActionStatus.DONE, ToolStrings.timerSet("duration" to Arg.Seconds(action.seconds)))
            else -> Executed(ActionStatus.DONE, ToolStrings.somethingWrong())
        }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) {
            cards[c.id] = c
            if (c.status == ActionStatus.PENDING) pending.trySend(c.id)
        }
        override val text: String = ""
        override fun replaceText(text: String) {}
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private var ids = 0

    private fun timer(minutes: Int) = RawToolCall("set_timer", mapOf("minutes" to minutes))

    @Test fun theCardsSpeakFrenchAndTheModelReadsEnglish() = runBlocking {
        val model = Model(listOf(timer(5), timer(10), timer(3)))
        val view = View()
        TurnRunner(model, Actions(), view, Confirmations(), untrusted = false, langs = fr) { "c${++ids}" }.run()
        val cards = view.cards.values.toList()
        assertEquals("Régler une minuterie de 5 minutes", cards[0].summary)
        assertEquals("Minuterie de 5 minutes réglée.", cards[0].detail)
        // The third action is over the limit: the card says why in French.
        assertEquals("Régler une minuterie de 3 minutes", cards[2].summary)
        assertEquals(ActionStatus.SKIPPED, cards[2].status)
        assertEquals("Robot fait au plus 2 actions par réponse.", cards[2].detail)
        // Gemma receives the English.
        val results = model.answers.single()
        assertEquals("Timer set for 5 minutes.", results[0].second["detail"])
        assertEquals("Robot does at most 2 actions per answer.", results[2].second["detail"])
    }

    @Test fun anInvalidOrDisabledActionSaysWhyInFrench() = runBlocking {
        val off = RobotSettings().let { it.copy(approvals = it.approvals + (ToolName.FLASHLIGHT to Approval.OFF)) }
        val model = Model(listOf(RawToolCall("set_timer", mapOf("minutes" to "five")), RawToolCall("flashlight", mapOf("on" to true))))
        val view = View()
        TurnRunner(model, Actions(off), view, Confirmations(), untrusted = false, langs = fr) { "c${++ids}" }.run()
        val (bad, disabled) = view.cards.values.toList()
        assertEquals("Impossible de faire « set_timer »", bad.summary)
        assertEquals("minutes doit être un nombre", bad.detail)
        assertEquals("Allumer la lampe de poche", disabled.summary)
        assertEquals("Lampe de poche : action désactivée dans les réglages de Robot.", disabled.detail)
        val results = model.answers.single()
        assertEquals("minutes must be a number", results[0].second["detail"])
        assertEquals("Flashlight are turned off in Robot's settings.", results[1].second["detail"])
    }

    @Test fun aWaitingCardKeepsItsButtonsInTheMenusLanguage() = runBlocking {
        val ask = RobotSettings().let { it.copy(approvals = it.approvals + (ToolName.SEND_TEXT to Approval.ASK)) }
        val target = TextTarget("Marie", "+15145550101@cheogram.com", "+1 514-555-0101", "Cheogram", TextVia.CHEOGRAM)
        fun card(langs: Langs): Card = runBlocking {
            val view = View()
            val conf = Confirmations()
            val actions = Actions(ask) { a -> Prepared.Ready((a as Action.SendText).copy(target = target)) }
            val turn = async { TurnRunner(Model(listOf(RawToolCall("send_text", mapOf("to" to "Marie", "message" to "J'arrive")))), actions, view, conf,
                untrusted = false, langs = langs) { "c${++ids}" }.run() }
            val id = withTimeout(5_000) { view.pending.receive() }
            val waiting = view.cards.getValue(id)
            conf.answer(id, false)
            turn.await()
            waiting
        }
        // A French speaker on English menus: the card speaks French, its button stays English (decided by default, §14 q. 1).
        val mixed = card(frSpokenEnMenus)
        assertEquals("Texter **Marie** (+1 514-555-0101) dans Cheogram", mixed.summary)
        assertEquals("Open in Cheogram for Marie", mixed.confirm)
        // French menus: the button is French too.
        assertEquals("Ouvrir dans Cheogram pour Marie", card(fr).confirm)
        // English everywhere: 1.0's words.
        val en = card(Langs.EN)
        assertEquals("Text **Marie** (+1 514-555-0101) in Cheogram", en.summary)
        assertEquals("Open in Cheogram for Marie", en.confirm)
    }

    @Test fun summariesWithTimesAndDates() {
        assertEquals("Régler une alarme pour 7\u00A0h\u00A030", Action.SetAlarm(7, 30).summary().render(fr))
        assertEquals("Set an alarm for 07:30", Action.SetAlarm(7, 30).describe())
        assertEquals("Régler une alarme pour 19\u00A0h", Action.SetAlarm(19, 0).summary().render(fr))
        assertEquals("Set an alarm for 19:00", Action.SetAlarm(19, 0).describe())
        val zone = ZoneId.of("America/Montreal")
        val start = ZonedDateTime.of(2026, 10, 13, 15, 0, 0, 0, zone)
        val event = Action.AddEvent("Dentiste", "mardi 15 h", plan = EventPlan(start, start.plusHours(1), false, null, null, 2026))
        assertEquals("Ajouter **Dentiste** le mardi 13 octobre, 15\u00A0h à 16\u00A0h dans ton appli d’agenda", event.summary().render(fr))
        assertEquals("Add **Dentiste** on Tue Oct 13, 15:00–16:00 in your calendar app", event.describe())
        assertEquals("jeudi 1er octobre 2026", EventText.dayText(LocalDate.of(2026, 10, 1), 2025).render(fr))
        assertEquals("Thu Oct 1 2026", EventText.day(LocalDate.of(2026, 10, 1), 2025))
        val day = DayRange(start.toLocalDate().atStartOfDay(zone), start.toLocalDate().plusDays(1).atStartOfDay(zone))
        assertEquals("3 événements le mardi 13 octobre.", CalendarFormat.detailText(3, day).render(fr))
        assertEquals("1 événement le mardi 13 octobre.", CalendarFormat.detailText(1, day).render(fr))
        assertEquals("Rien le mardi 13 octobre.", CalendarFormat.detailText(0, day).render(fr))
    }

    @Test fun theReadToolsAnswersInFrench() {
        val d = WeatherDay(LocalDate.of(2026, 10, 13), code = 3, high = 13.4, low = 2.6, chance = 67, precip = 1.3, rain = 1.3, showers = 0.0,
            snow = 0.0, wind = 21.0, gusts = 39.0, cloud = 98)
        assertEquals("couvert, max. 13 °C, min. 3 °C, 67 % de risque de pluie (environ 1,3 mm), vent jusqu’à 21 km/h (rafales 39), " +
            "couverture nuageuse 98 %", WeatherText.lineText(d, null, WeatherUnits.METRIC).render(fr))
        // The model's line is 1.0's English.
        assertEquals(WeatherText.line(d, null, WeatherUnits.METRIC), WeatherText.lineText(d, null, WeatherUnits.METRIC).en())
        val phone = PhoneFacts(storage = StorageFacts(total = 238_000_000_000, free = 57_000_000_000), usageAccess = true)
        assertEquals("Stockage : 57 Go libres sur 238 Go (76 % utilisés)", PhoneStatus.linesText(phone, PhonePart.STORAGE).render(fr))
        assertEquals(PhoneStatus.lines(PhoneStatus.results(phone, PhonePart.STORAGE)), PhoneStatus.linesText(phone, PhonePart.STORAGE).en())
        assertEquals("environ 1,2 km", Nearby.distanceText(1_234.0, WeatherUnits.METRIC).render(fr))
        assertEquals("about 1.0 mi", Nearby.distance(1_610.0, WeatherUnits.IMPERIAL))
        assertEquals("environ 1,0 mi", Nearby.distanceText(1_610.0, WeatherUnits.IMPERIAL).render(fr))
        assertEquals("Vérifier la batterie du téléphone", PhoneStatus.summaryText(PhonePart.BATTERY).render(fr))
    }

    @Test fun spokenAnswersToACard() {
        assertEquals("Touche ton choix sur la carte, ou dis non.", CardAnswer.promptText(CardAnswer.Waiting(choices = 2)).render(fr))
        // The buttons a sentence names are in the menus' language.
        assertEquals("Touche Allow always, Just this time ou No sur la carte.", CardAnswer.tapText(AskWhy.ONLINE).render(frSpokenEnMenus))
        assertEquals("Touche Toujours autoriser, Juste cette fois ou Non sur la carte.", CardAnswer.tapText(AskWhy.ONLINE).render(fr))
        assertEquals(CardAnswer.TAP_ONLINE, CardAnswer.tapText(AskWhy.ONLINE).en())
    }

    @Test fun androidsScreensAreInThePhonesLanguage() {
        val hint = ScreenAccess.notSharedHint(textOn = false, shotOn = true).phrase
        assertTrue(hint.render(fr).contains("Paramètres › Applications › Applications par défaut › Application d’assistant numérique"))
        assertTrue(hint.render(frSpokenEnMenus).contains("Settings › Apps › Default apps › Digital assistant app"))
    }

    @Test fun theVoiceGetsNoTypographyCharacters() {
        val said = FreeUp.memoryNowText(MemoryFacts(total = 12_000_000_000, available = 5_100_000_000, low = false)).render(fr.voice)
        assertEquals("5,1 gigaoctets libres sur 12 gigaoctets de mémoire.", said)
        assertFalse(Regex("[  «»]").containsMatchIn(said))
    }

    @Test fun whatTheReplyReadOutIsKnownInEitherLanguage() {
        val turn = ChatTurn("t", TurnRole.ASSISTANT, "", 0, actions = listOf(Quoted.record(fr)))
        assertEquals("Lire ton agenda et tes rappels", turn.actions.single().summary)
        assertTrue(Quoted.of(turn))
        assertTrue(Quoted.of(turn.copy(actions = listOf(Quoted.record()))))
    }

    @Test fun anInvalidCallStillEqualsItsEnglish() {
        assertEquals(ParsedCall.Invalid("send_sms", "unknown tool"), ParsedCall.Invalid("send_sms", ToolStrings.unknownTool()))
        assertEquals("outil inconnu", ParsedCall.Invalid("send_sms", ToolStrings.unknownTool()).why.render(fr))
    }
}
