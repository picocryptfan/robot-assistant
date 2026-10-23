// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.TermuxCalls
import app.heyrobot.core.TermuxScriptAction
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux's words in Québec French (1.1, the l10n design's WP6): Robot's warnings and why a command needs a tap, the set-up
 * checklist, where Termux came from, how a run ended, the card's lines and History's line, with the OQLF's spaces and
 * tutoiement (TermuxPolicy's « vous » is gone). The buttons a sentence names are the menus' (« Lancer » with French menus,
 * "Run" with English ones), and what the model reads stays English.
 */
class TermuxFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private fun Phrase.fr() = render(fr)

    @Test fun eachWarningSaysWhatItDoesAfterThisCommand() {
        // The verb form is the chip's, cut at its first ":" or "." and lowercased: the English the card said before 1.1.
        for (w in CommandWarning.entries) {
            val what = w.what ?: continue
            assertEquals(w.name, w.en.substringBefore(':').substringBefore('.').trim().replaceFirstChar { it.lowercaseChar() }, what().en())
            assertFalse(w.name, what().fr().first().isUpperCase())
        }
        assertEquals(listOf("Modifie, écrase ou déplace des fichiers", "Utilise Internet"), CommandAnalyzer.analyze("git -C ~/notes pull --ff-only").chipTexts.map { it.fr() })
    }

    @Test fun whyACommandNeedsATapInFrench() {
        val s = TermuxSettings(enabled = true)
        val d = TermuxPolicy.command(CommandAnalyzer.analyze("rm -rf ~/tmp/old"), CommandSource.MODEL, s, locked = false, untrustedConversation = false)
        assertEquals("Cette commande supprime ou vide des fichiers : vérifie la carte, puis touche Lancer.", d.sayText()!!.fr())
        assertEquals("Cette commande supprime ou vide des fichiers : vérifie la carte, puis touche Lancer. Ou dis non.", d.promptText()!!.fr())
        // French words with English menus: the button keeps the name it has on the screen.
        assertEquals("Cette commande supprime ou vide des fichiers : vérifie la carte, puis touche Run.",
            d.sayText()!!.render(Langs(Lang.FR, Lang.EN, Lang.EN)))
        // And the model reads English.
        assertEquals("This command deletes files or empties them: check the card, then tap Run.", d.sayText()!!.en())
        assertEquals("Termux est désactivé dans Réglages › Termux.", TermuxReason.OFF.text.fr())
        assertEquals("Ton script, réglé sur Faire sans demander.", TermuxReason.YOUR_SCRIPT.text.fr())
        assertEquals("Cette commande est trop longue pour que Robot la vérifie (4 096 caractères au plus).", TermuxReason.TOO_LONG.text.fr())
        assertEquals("This command is too long for Robot to check (at most 4,096 characters).", TermuxReason.TOO_LONG.en)
        for (r in TermuxReason.entries) assertFalse(r.name, Regex("\\b(vous|votre|vos)\\b").containsMatchIn(r.text.fr()))
    }

    @Test fun theActionsCardsInFrench() {
        val a = app.heyrobot.core.TermuxCommandAction("du -sh ~")
        assertEquals("Lancer une commande dans Termux", a.summary().fr())
        assertEquals("Run a command in Termux", a.describe())
        assertEquals("Lancer", a.confirmText.fr())
        assertEquals("Run", a.confirmLabel)
    }

    @Test fun theSetUpChecklistInFrench() {
        val none = TermuxSetup.lines(TermuxCheck(TermuxStatus.NOT_INSTALLED, null, null, null), false, TermuxSetupStatus(), null)
        assertEquals("Termux installé", none.first().step.label().fr())
        assertEquals("Pas installé. Obtiens-le sur F-Droid (pas sur Google Play : cette version n’accepte pas de commandes d’autres applis).",
            none.first().phrase.fr())
        assertEquals("Installe d’abord Termux", none.last().phrase.fr())
        val scripts = TermuxSetupStatus(listAt = 1, scripts = listOf(TermuxScriptInfo("backup", null), TermuxScriptInfo("hello", null)))
        val folder = TermuxSetup.lines(TermuxCheck(TermuxStatus.NOT_INSTALLED, null, null, null), false, scripts, null).single { it.step == SetupStep.FOLDER }
        assertEquals("~/robot : 2 scripts", folder.phrase.fr())
        assertEquals("~/robot: 2 scripts", folder.text)
        assertEquals("Avec ceci, toute appli à qui tu donnes l’autorisation de Termux peut y lancer des commandes. Donne-la seulement à Robot.",
            TermuxSetup.allowWarning.fr())
    }

    @Test fun whereTermuxCameFromInFrench() {
        val fdroid = TermuxCheck(TermuxStatus.READY, TermuxSource.F_DROID, "abc", "0.118.3")
        assertEquals("Termux 0.118.3 depuis F-Droid", fdroid.lineText().fr())
        val old = TermuxCheck(TermuxStatus.TOO_OLD, TermuxSource.F_DROID, "abc", "0.117")
        assertEquals("Termux 0.117 depuis F-Droid est trop ancien : mets-le à jour vers 0.118.0 ou plus récent.", old.lineText().fr())
        assertEquals("Termux 0.117 from F-Droid is too old: update it to 0.118.0 or newer.", old.line())
    }

    @Test fun howARunEndedInFrench() {
        assertEquals("Code 0 · 0,8\u00A0s", TermuxCards.exitText(TermuxOutcome(0, 812)).fr())
        assertEquals("Code 124 · 75 s : a pris plus de 60\u00A0s et a été arrêtée", TermuxCards.exitText(TermuxOutcome(124, 75_000, limitSeconds = 60)).fr())
        assertEquals("Exit 124 · 75 s: took longer than 60 s and was stopped", TermuxCards.exitLine(TermuxOutcome(124, 75_000, limitSeconds = 60)))
        assertEquals("Terminé en 1,2\u00A0s.", TermuxCards.saidText(TermuxOutcome(0, 1_200)).fr())
        assertEquals("Termux n’a pas répondu : vérifie que son utilisation de la batterie n’est pas restreinte.",
            TermuxCards.saidText(TermuxOutcome(null, 1, failure = TermuxFailure.NO_ANSWER)).fr())
        assertEquals("Commande lancée dans Termux (code 0)", TermuxCards.savedText(null, 0).fr())
        assertEquals("Script « backup » lancé (arrêté)", TermuxCards.savedText("backup", null, stopped = true).fr())
        assertEquals("4,1 ko", TermuxCards.sizeText(4_200).fr())
        assertEquals("512 caractères", TermuxCards.sizeText(512).fr())
    }

    @Test fun theCardsLinesInFrench() {
        val c = TermuxCards.script("show-args", listOf("one", "two words"), TermuxDecision(TermuxVerdict.CARD, listOf(TermuxReason.ASK_FIRST)), 30)
        assertEquals("2 arguments : “one”, “two words”", c.readsAsText!!.fr())
        assertEquals("Dans : ~/robot · Limite de temps : 30 s", c.whereText().fr())
        assertEquals("Lancer ton script « backup »", TermuxScriptAction("backup").describeText.fr())
        assertEquals("Run your script “backup”", TermuxScriptAction("backup").describe())
        assertEquals("Termux n’est pas prêt : ouvre Réglages › Termux.", TermuxCalls.unprepared.fr())
        assertTrue(ScriptRegistry.listShown.fr().startsWith("find ~/robot -maxdepth 1 -type f -perm -u+x  (et la première ligne"))
    }
}
