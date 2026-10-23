// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.1 (the l10n design's WP2b, its first part): what Robot says about a helper and why a helper failed, in Québec French
 * (a helper is « une IA externe »), and 1.0's English unchanged.
 */
class HelperWordsInFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private val nb = ' '
    private val fine = ' '
    private val jarvis = HelperWho("Jarvis", "Your own server", null, null, home = true)

    @Test fun theEnglishIsOnePointZeros() {
        val w = HelperWords(HelperWho.CLAUDE)
        assertEquals("Claude is thinking…", w.thinking)
        assertEquals("Want Claude's take as well?", w.offerAsWell)
        assertEquals("Tap Send to include your calendar and your notes.", w.tapToInclude(setOf(Personal.NOTES, Personal.CALENDAR)))
        assertEquals("Add your Claude API key in Robot's settings first, or choose the Claude app there.", HelperFailure.NO_KEY.text)
        assertEquals("Your Anthropic account is out of API credits. Add credits at console.anthropic.com.", HelperFailure.NO_CREDITS.text)
        assertEquals("No internet. If you're online, check that Robot's Network permission is on (Settings › Apps › Robot › Permissions).",
            HelperFailure.NO_INTERNET.text)
        assertEquals("Jarvis isn't answering. Is the computer on, and is the phone on the same network?", HelperFailure.UNREACHABLE.say(jarvis))
        assertEquals("Back to Robot.", ClaudeWords.LEFT)
        assertEquals("screen text, a photo and your calendar", Personal.list(setOf(Personal.CALENDAR, Personal.SCREEN, Personal.PHOTO)))
    }

    @Test fun inFrench() {
        val w = HelperWords(HelperWho.CLAUDE)
        assertEquals("Claude réfléchit…", w.thinkingText.render(fr))
        assertEquals("Veux-tu aussi l’avis de Claude$fine?", w.offerAsWellText.render(fr))
        assertEquals("Touche Envoyer pour inclure ton agenda et tes notes.", w.tapToIncludeText(setOf(Personal.NOTES, Personal.CALENDAR)).render(fr))
        assertEquals("Ajoute d’abord ta clé d’API Claude dans les réglages de Robot, ou choisis l’appli Claude là.",
            HelperFailure.NO_KEY.sayText(HelperWho.CLAUDE).render(fr))
        assertEquals("Pas d’Internet. Si tu es en ligne, vérifie que l’autorisation Réseau de Robot est activée (Paramètres › Applications › Robot › Autorisations).",
            HelperFailure.NO_INTERNET.sayText(HelperWho.CLAUDE).render(fr))
        assertEquals("Jarvis ne répond pas. L’ordinateur est-il allumé, et le téléphone est-il sur le même réseau$fine?",
            HelperFailure.UNREACHABLE.sayText(jarvis).render(fr))
        assertEquals("Robot ne sait pas encore où trouver Jarvis. Ouvre sa page dans les réglages de Robot, sous IA externes, et touche Tester la connexion.",
            HelperFailure.NO_ADDRESS.sayText(jarvis).render(fr))
        assertEquals("L’IA externe Jarvis est désactivée. Active-la dans les réglages de Robot, sous IA externes.", HelperWords(jarvis).offText.render(fr))
    }
}
