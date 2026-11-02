// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Medium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11, Skills connections in Québec French (ConnStrings, the connections area; docs/plans/skills-connections-plan.md §6.5):
 * the page's path names Robot's screens, every failure has its own French sentence with your connection's name as you
 * typed it, Home Assistant's check says the state through « c'est » and « ça » (nothing to agree with), the Settings rows,
 * a skill's Risk line, and « tu » everywhere. Aloud, no French quotes or path arrows.
 */
class ConnFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private val nb = ' '

    @Test fun thePageIsRobotsOwnPath() {
        assertEquals("Réglages › Compétences › Connexions", ConnStrings.page.render(Lang.FR))
        assertEquals("Réglages › Mode auto", ConnStrings.carPage.render(Lang.FR))
        assertEquals("Réglages, Compétences, Connexions", ConnStrings.page.render(Lang.FR, Medium.VOICE))
    }

    @Test fun everyFailureHasItsOwnFrenchSentence() {
        val texts = ConnFailure.entries.map { it.text("Maison", status = 403, limit = 30).render(fr) }
        assertEquals(texts.size, texts.toSet().size)
        for (t in texts) {
            assertTrue(t, t.endsWith("."))
            assertFalse(t, Regex("\\b(vous|votre|vos)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t))
            // Not the English: every one is translated.
            assertFalse(t, t.startsWith("Robot can") || t.startsWith("Your "))
        }
        assertEquals("Robot ne peut pas joindre Maison en ce moment.", ConnFailure.UNREACHABLE.text("Maison").render(fr))
        assertEquals("Ta connexion Maison a besoin de sa clé. Colle-la dans Réglages › Compétences › Connexions.",
            ConnFailure.NO_KEY.text("Maison").render(fr))
        assertEquals("Robot n’enverra pas ta clé Maison en http non chiffré$nb: utilise une adresse https.", ConnFailure.KEY_OVER_HTTP.text("Maison").render(fr))
        assertEquals("La limite de Robot pour Maison est atteinte$nb: 30 par minute.", ConnFailure.LIMIT_MINUTE.text("Maison", limit = 30).render(fr))
        assertEquals("Maison a dit non (erreur 403).", ConnFailure.SAID_NO.text("Maison", status = 403).render(fr))
        assertEquals("Enregistre d’abord ton domicile dans Réglages › Mode auto.", ConnFailure.NO_HOME.text("Maison").render(fr))
        // Aloud: the path is a pause, no arrow.
        val spoken = ConnFailure.NO_KEY.text("Maison").render(Langs(Lang.FR, Lang.FR, Lang.FR, Medium.VOICE))
        assertTrue(spoken, spoken.endsWith("Réglages, Compétences, Connexions.") && '›' !in spoken)
    }

    @Test fun aStepChangedSinceItsCardHasItsOwnLine() {
        // C13: C12's L3 refusal said "This connection is gone" for a connection that is still there.
        val en = Langs.EN
        assertEquals("This step changed after its card showed, so Robot didn't run it. Ask again.", ConnFailure.CHANGED.text("Maison").render(en))
        assertEquals("Cette étape a changé après l’affichage de sa carte, alors Robot ne l’a pas lancée. Redemande-moi.",
            ConnFailure.CHANGED.text("Maison").render(fr))
        for (lang in listOf(en, fr)) assertTrue(ConnFailure.CHANGED.text("Maison").render(lang) != ConnFailure.GONE.text("Maison").render(lang))
        assertEquals(ConnFailure.CHANGED, ConnFailure.of(38))
    }

    @Test fun homeAssistantsCheckAgreesWithNothing() {
        val off = HaCheck.Outcome(mapOf("light.downstairs" to HaPreset.Seen.RIGHT, "switch.kettle" to HaPreset.Seen.RIGHT), "turn_off")
        assertEquals("la lumière downstairs et l’interrupteur kettle, c’est éteint", HaCheck.doneWords(off)!!.render(fr))
        assertEquals("Maison a dit oui$nb: la lumière downstairs et l’interrupteur kettle, c’est éteint.",
            ConnStrings.haDone("name" to "Maison", "what" to HaCheck.doneWords(off)!!).render(fr))
        val stuck = HaCheck.Outcome(mapOf("lock.front_door" to HaPreset.Seen.WRONG), "lock")
        assertEquals("la serrure front door, ça ne s’est pas verrouillé", HaCheck.failedWords(stuck)!!.render(fr))
        // An id that names its domain already, and one Robot can't say.
        assertEquals("living room lights", HaCheck.thing("light.living_room_lights").render(fr))
        assertEquals("la lumière", HaCheck.thing("light.0x00158d0001a2b3c4").render(fr))
        assertEquals("3 lumières, 1 serrure", ConnStrings.haCountLights(3).render(fr) + ", " + ConnStrings.haCountLocks(1).render(fr))
    }

    @Test fun theRowsAndASkillsRiskLine() {
        assertEquals("Il lui faut sa clé", ConnStrings.stateNeedsKey().render(fr))
        assertEquals("Home Assistant · Jarvis's ntfy · 1 a besoin de ton attention",
            ConnSetup.summary(listOf(ConnFixtures.homeAssistant(), ConnFixtures.ntfy()), 1).render(fr))
        assertEquals("Délicate$nb: 3 étapes, envoie une notification",
            ConnStrings.riskLine("tier" to ConnStrings.tierCareful(), "steps" to ConnStrings.stepsCount(3), "effects" to "envoie une notification").render(fr))
        assertEquals("Sa clé est supprimée, et 2 compétences auront besoin de ton attention.", ConnStrings.removeText(2).render(fr))
    }
}
