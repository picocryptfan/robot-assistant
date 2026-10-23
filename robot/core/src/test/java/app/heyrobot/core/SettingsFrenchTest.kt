// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.update.CheckOutcome
import app.heyrobot.core.update.InstallOutcome
import app.heyrobot.core.update.UpdateFail
import app.heyrobot.core.update.UpdateState
import app.heyrobot.core.update.UpdateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import app.heyrobot.core.update.AVAILABLE_TEXT

/**
 * 1.1 (the l10n design's WP8): Settings' choices, the ADB fixes, the updater's lines and Send feedback's form in Québec
 * French (SettingsStrings, UpdateStrings, FeedbackStrings), with the French spaces and Android's screens in Android's
 * words; their English stays 1.0's, and the feedback message itself stays English for the developer.
 */
class SettingsFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private val nb = ' '
    private val fine = ' '
    private val zone = ZoneId.of("America/Toronto")
    private val now = ZonedDateTime.of(2026, 10, 11, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun settingsChoices() {
        assertEquals(listOf("Faire sans demander", "Demander d’abord", "Désactivé"), Approval.entries.map { it.titleText.render(fr) })
        assertEquals(listOf("Just do it", "Ask first", "Off"), Approval.entries.map { it.title })
        assertEquals("Quand je demande à voix haute", SpeakReplies.VOICE.titleText.render(fr))
        assertEquals("Voix du téléphone", VoiceChoice.SYSTEM.titleText.render(fr))
        assertEquals("Anglais, naturelle (Kokoro)", VoiceChoice.HEART.detailText.render(fr))
        assertEquals("Appli SMS", TextApp.SMS.titleText.render(fr))
        assertEquals("Demander chaque fois", EmailApp.ASK.titleText.render(fr))
        assertEquals("Demander la première fois", NearestOnline.ASK.titleText.render(fr))
        assertEquals("Rien n’est envoyé en ligne.", NearestOnline.NEVER.detailText.render(fr).substringAfter("autour de toi. "))
        assertEquals("Dans l’appli", LicenceWhere.APP.titleText.render(fr))
        assertEquals("Annulation d’écho", BargeIn.ECHO_CANCEL.titleText.render(fr))
        assertEquals("Volume bas", StopKey.VOLUME_DOWN.titleText.render(fr))
        assertEquals("Echo cancellation", BargeIn.ECHO_CANCEL.title)
    }

    @Test fun adbFixes() {
        val header = AdbFixes.headerText.render(fr)
        assertTrue(header, header.startsWith("Android ne laisse qu’un ordinateur changer certaines choses."))
        assertTrue(header, "Sur le téléphone$nb: Paramètres › À propos du téléphone, touche Numéro de build" in header)
        assertTrue(header, "puis Paramètres › Système › Options pour les développeurs, active Débogage USB" in header)
        assertEquals(AdbFixes.HEADER, AdbFixes.headerText.en())
        assertTrue(AdbFixes.HEADER.startsWith("Some things Android only lets a computer change. Each fix below is done once, with adb. On the phone: Settings › About phone"))
        val repair = AdbFixes.ALL.first { it.id == "repair" }
        assertEquals("Laisser Robot réparer ça lui-même", repair.titleText.render(fr))
        assertEquals("Nécessite Robot 0.2.3+", AdbFixes.labelText(repair, AdbFixState.NEEDS_NEWER).render(fr))
        assertEquals("Needs Robot 0.2.3+", AdbFixes.label(repair, AdbFixState.NEEDS_NEWER))
    }

    @Test fun theUpdatersLines() {
        val at = ZonedDateTime.of(2026, 10, 11, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("La limite de vérifications de GitHub depuis ton réseau est atteinte (60 par heure sans jeton). Réessaie après 9${nb}h${nb}05.",
            UpdateText.fail(UpdateFail.RATE_LIMITED, at, zone, Lang.FR))
        // The English keeps 1.0's "09:05".
        assertTrue(UpdateText.fail(UpdateFail.RATE_LIMITED, at, zone).endsWith("Try again after 09:05."))
        assertEquals("L’autorisation Réseau de Robot est désactivée. Active-la dans Paramètres › Applications › Robot › Autorisations pour vérifier les mises à jour.",
            UpdateText.failText(UpdateFail.NO_NETWORK_PERMISSION).render(fr))
        assertEquals("Robot 1.1.0 est installé.", UpdateText.installText(InstallOutcome.SUCCESS, "1.1.0").render(fr))
        assertEquals("Dernière vérification$nb: aujourd’hui à 9${nb}h${nb}05. Tu es à jour.",
            UpdateText.lastCheck(UpdateState(lastCheck = at, outcome = CheckOutcome.UP_TO_DATE), now, zone, Lang.FR))
        val old = ZonedDateTime.of(2026, 9, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Dernière vérification$nb: mercredi 2 septembre à 8${nb}h.", UpdateText.lastCheck(UpdateState(lastCheck = old), now, zone, Lang.FR))
        assertEquals("Pas encore vérifié", UpdateText.lastCheck(UpdateState(), now, zone, Lang.FR))
        assertEquals("dimanche 11 octobre 2026", UpdateText.published("2026-10-11T16:00:00Z", zone, Lang.FR))
        assertEquals("97${nb}Mo", UpdateText.size(97_000_000, Lang.FR))
        assertEquals("Touche pour voir les nouveautés dans Réglages › Mises à jour.", UpdateText.availableText.render(fr))
        assertEquals("Tap to see what's new in Settings › Updates.", UpdateText.AVAILABLE_TEXT)
        // Every failure and install outcome has its own French sentence.
        val texts = UpdateFail.entries.map { UpdateText.failText(it).render(fr) } + InstallOutcome.entries.map { UpdateText.installText(it, "1.1.0").render(fr) }
        assertEquals(texts.size, texts.toSet().size)
        for (t in texts) assertTrue(t, t.endsWith(".") && " vous " !in t)
    }

    @Test fun sendFeedbacksForm() {
        assertEquals(listOf("Bogue", "Idée", "Autre"), FeedbackKind.entries.map { it.labelText.render(fr) })
        assertEquals("Qu’est-ce qui s’est passé$fine?", FeedbackKind.BUG.promptText.render(fr))
        assertEquals("Les 60 derniers événements, codes et nombres seulement", Diagnostic.VOICE_LOG.detailText.render(fr))
        assertEquals("The last 60 events, codes and numbers only", Diagnostic.VOICE_LOG.detail)
        // What the developer reads stays English.
        assertEquals("Bug report", FeedbackKind.BUG.header)
        assertEquals("the voice log, the settings summary", Diagnostic.words(6))
    }
}
