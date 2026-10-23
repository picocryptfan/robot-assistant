// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.FrTypo
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Medium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1 (WP4a): routines in Québec French. What Robot says follows the turn's reply language, the buttons and Robot's pages
 * a sentence names follow the menus', and the English is 1.0's (the other routine tests).
 */
class RoutineFrenchTest {
    private val zone = ZoneId.of("America/Toronto")
    private val now = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, zone)
    private val fr = Langs.of(Lang.FR)
    /** A French speaker on English menus. */
    private val frOnEnglish = Langs(Lang.FR, Lang.EN, Lang.EN)
    private fun f(s: String) = FrTypo.fix(s)

    private fun routine(name: String = "Matin", trigger: RoutineTrigger = RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS),
        steps: List<RoutineStep> = listOf(RoutineStep.Brief), enabled: Boolean = true) =
        Routine("r-1", name, trigger, steps, enabled = enabled, created = 1)

    @Test fun theCardSaysItInFrenchAndNamesTheButtonInTheMenusLanguage() {
        val d = RoutineRouter.route("Chaque matin de semaine à 7 h, dis-moi la météo pis mon agenda", now, emptyList(), id = "d-1", fromContent = false)
            .let { (it as RoutineCommand.Create).draft }
        assertEquals(Lang.FR, d.lang)
        assertEquals("Routine du matin", d.routine.name)
        val card = d.cardText().render(fr)
        assertEquals(f("Enregistrer la routine « Routine du matin » ?"), card.title)
        assertEquals(f("Quand : en semaine à 7 h"), card.lines[0])
        assertEquals(f("Parle : Écouteurs, sinon le haut-parleur du téléphone"), card.lines[3])
        assertEquals(f("En ligne d'elle-même : Open-Meteo (la météo). Rien sur toi n'est envoyé."), card.lines[4])
        assertEquals("Dis oui ou touche Enregistrer.", card.footer)
        assertEquals("Dis oui ou touche Save.", d.cardText().render(frOnEnglish).footer)
        // The English card is 1.0's.
        assertEquals("Say yes or tap Save.", d.card().footer)
        assertEquals(RoutineRouter.lang("Chaque matin de semaine à 7 h, dis-moi la météo pis mon agenda", RoutineCommand.Create(d)), Lang.FR)
    }

    @Test fun whenARoutineRuns() {
        assertEquals("tous les jours à 6 h 30", RoutineCards.triggerText(RoutineTrigger.At(LocalTime.of(6, 30), RoutineWords.ALL)).render(fr).replace(' ', ' '))
        assertEquals("chaque lundi et vendredi à 17 h",
            RoutineCards.triggerText(RoutineTrigger.At(LocalTime.of(17, 0), setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY))).render(fr).replace(' ', ' '))
        assertEquals("la fin de semaine à 9 h", RoutineCards.triggerText(RoutineTrigger.At(LocalTime.of(9, 0), RoutineWords.WEEKEND)).render(fr).replace(' ', ' '))
        assertEquals("quand ton auto se connecte, au plus une fois par jour", RoutineCards.whenPhrase(routine(trigger = RoutineTrigger.Device(DeviceKind.CAR))).render(fr))
        assertEquals("seulement quand tu le demandes", RoutineCards.whenPhrase(routine(trigger = RoutineTrigger.Asked)).render(fr))
        assertEquals("En semaine à 7 h · 1 étape · désactivée", RoutineTexts.row(routine(enabled = false), fr).replace(' ', ' '))
    }

    @Test fun listingDeletingAndTheLockedPhone() {
        val list = RoutineCards.listText(listOf(routine(), routine("Soir", RoutineTrigger.Asked, enabled = false))).render(fr)
        assertEquals(f("Tu as 2 routines : Matin, en semaine à 7 h; Soir, seulement quand tu le demandes (désactivée)."), list)
        assertEquals(f("Tu n'as pas encore de routine. Dis par exemple : en semaine à 7 h, dis-moi la météo et mon agenda."),
            RoutineCards.listText(emptyList()).render(fr))
        assertEquals(f("Déverrouille d'abord : la liste de tes routines n'est donnée que téléphone déverrouillé."),
            RoutineLockedPhone.refuse(RoutineCommand.ListAll, locked = true, fr = true))
        // Said aloud, the voice gets no typography.
        val said = RoutineLockedPhone.refuseText(RoutineCommand.ListAll, true)!!.render(fr.voice)
        assertFalse(said, said.contains(' ') || said.contains(' ') || said.contains('«'))
    }

    @Test fun readyCardsAndTheLastRun() {
        val day = LocalDate.of(2026, 10, 12)
        val card = ReadyCard("r-1", "Départ", RoutineStep.Say("bonne route"), 1_000L, day)
        assertEquals(f("Ta routine « Départ » est prête à faire ceci : Dire « bonne route ». Touche pour le faire."), card.phrase().render(fr))
        val brief = ReadyCard(BriefRoutine.ID, BriefRoutine.NAME, RoutineStep.Brief, 1_000L, day, missed = LocalTime.of(7, 0))
        assertEquals(f("Ta routine « Résumé du matin » ne s'est pas faite à 7 h. Touche pour la lancer maintenant."), brief.phrase().render(fr))
        assertEquals("Your “Morning brief” routine didn't run at 07:00. Tap to run it now.", brief.text())
        assertEquals(f("Robot a 2 choses prêtes pour toi"), ReadyCards.publicText(2).render(fr))
        val at = ZonedDateTime.of(2026, 10, 11, 17, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(f("Dernière fois : hier 17 h · une heure précise · 2 étapes, 1 prête"),
            RoutineTexts.lastRun(RunRecord(at, TriggerKind.TIME, 2, ready = 1), now, fr))
        assertEquals("Pas encore lancée", RoutineTexts.lastRun(null, now, fr))
        assertEquals(f("Routine « Matin » faite · 1 faite, 1 prête"), ReadyCards.runSummaryText(RoutineTexts.name(routine()), 1, 1, 0, 0)!!.render(fr))
    }

    @Test fun theEditorsWords() {
        assertEquals(listOf(f("Donne un nom à la routine."), f("Ajoute au moins une étape.")),
            RoutineCheck.problemTexts(routine(name = " ", steps = emptyList())).map { it.render(fr) })
        assertEquals("Écris ce que Robot dit.", StepChoice.SAY.problem("", fr))
        assertEquals(f("Un nombre de minutes, de 1 à 1440."), StepChoice.TIMER.problem("0", fr))
        assertEquals("se fait seule · va en ligne : Open-Meteo (la météo)", RoutineCards.badgeText(RoutineStep.Brief).render(fr))
        assertEquals(f("« Hey robot » est désactivée, mais « robot » est activée et l'entend quand même. Désactive « robot » aussi."),
            WakePhrases.insideWarningText(WakePhrase(2, "Hey robot", WakeSensitivity.MEDIUM, false),
                listOf(WakePhrase(1, "robot", WakeSensitivity.LOW, true) to WakePhrase(2, "Hey robot", WakeSensitivity.MEDIUM, false)))!!.render(fr))
    }

    @Test fun theBriefsLinesShownAndSaid() {
        val input = BriefInput(now, Lang.FR, events = emptyList(), reminders = listOf("appeler la banque", "sortir le bac"))
        val line = BriefComposer.lines(BriefItem.REMINDERS, input, place = true).single()
        assertEquals(f("Rappels : appeler la banque; sortir le bac."), line.shown)
        assertEquals("Rappels: appeler la banque; sortir le bac.", line.text)
        assertEquals("Rien à ton agenda aujourd’hui.", BriefComposer.lines(BriefItem.CALENDAR, input, place = true).single().text)
        assertEquals(Medium.VOICE, fr.voice.medium)
    }
}
