// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1 (the l10n design's WP3): reading messages and replying in Québec French. Robot's own words follow the reply
 * language, Robot's screens and buttons named in them the menus' language, the messages' words stay as written, the voice
 * gets no typography, and what the model reads stays English.
 */
class MessagesInFrenchTest {
    private val fr = Langs.of(Lang.FR)
    /** A French speaker with English menus. */
    private val frSpokenEnMenus = Langs(say = Lang.FR, ui = Lang.EN, system = Lang.EN)
    private val mom = thread(Snapshots.cheogram1to1("I'll be home at 6", "Bring bread"))
    private val group = thread(Snapshots.cheogramGroup)
    private val on = MessageSettings(on = true, allowlist = Snapshots.ALL_APPS)
    private val typography = Regex("[  «»“”›]")

    @Test fun aSettingsPageNamedInARepliesInTheMenusLanguage() {
        assertEquals("Aucune appli n’est choisie dans Settings › Messages.", MessageReader.noAppsText().render(frSpokenEnMenus))
        assertEquals("Aucune appli n’est choisie dans Réglages › Messages.", MessageReader.noAppsText().render(fr))
        assertEquals("No app is turned on in Settings › Messages.", MessageReader.noAppsText().en())
        assertEquals("La lecture des messages est désactivée. Active-la dans Réglages › Messages.", MessageAccess.offText().render(fr))
        // Aloud, the path is a pause and the French spaces are plain ones.
        assertEquals("Aucune appli n’est choisie dans Réglages, Messages.", MessageReader.noAppsText().render(fr.voice))
    }

    @Test fun readingAConversationInFrench() {
        val p = MessageReader.start(listOf(mom, group), Lang.FR, langs = frSpokenEnMenus)
        assertTrue(p.shown, p.shown.startsWith("2 conversations : Mom Martin et Famille.\n\n**Mom Martin**, dans Cheogram, 2 messages :"))
        // The messages' words as written, never touched by French typography.
        assertTrue(p.shown.contains("- I'll be home at 6\n- Bring bread"))
        assertTrue(p.shown.endsWith("Répondre, suivant ou arrêter ?"))
        // Robot's words for the voice: French, without typography characters; the messages in their own voice.
        assertEquals(listOf("2 conversations: Mom Martin et Famille.", "Mom Martin, dans Cheogram, 2 messages:", "I'll be home at 6", "Bring bread",
            "Répondre, suivant ou arrêter?"), p.spoken.map { it.text })
        assertTrue(p.spoken.none { typography.containsMatchIn(it.text) })
        assertEquals(listOf(true, true, false, false, true), p.spoken.map { it.french })
        // History keeps who and how many, in the turn's language.
        assertEquals("Robot a lu 2 messages de Mom Martin (Cheogram)", p.kept)
        // A group's lines say who wrote, with French spacing around Robot's colon only.
        assertEquals(listOf("Marc : Souper dimanche à 18 h chez maman", "Julie : J'apporte le dessert", "Julie a envoyé une photo."),
            MessageReading.lines(group, Lang.FR))
        assertEquals("1 conversation : Mom Martin.", MessageReading.countText(listOf(mom)).render(fr))
        assertEquals("Résumé de 1 conversation", MessageSummaryText.kept(1, Lang.FR))
        assertEquals("Résumé de 3 conversations", MessageSummaryText.kept(3, Lang.FR))
    }

    @Test fun aReplyCardInFrenchNamesTheButtonsInTheMenusLanguage() {
        val p = ReplyPlanner.plan(mom, "J'arrive", ReplySource.DICTATED, on, locked = false) as ReplyPlan.Send
        assertEquals("Répondre à Mom Martin : J'arrive. Touche Send pour l’envoyer.", ReplyPlanner.spokenText(p).render(frSpokenEnMenus))
        assertEquals("Répondre à Mom Martin : J'arrive. Touche Envoyer pour l’envoyer.", ReplyPlanner.spokenText(p).render(fr))
        assertEquals("Répondre à **Mom Martin** · Cheogram\n« J'arrive »", ReplyPlanner.card(p, fr))
        assertEquals("C’est un texto : des frais de l’opérateur peuvent s’appliquer.", ReplyPlanner.noteText(ReplyNote.SMS)!!.render(fr))
        assertEquals("Réponse à Mom Martin annulée", MessageReading.replyKeptText(ReplyKept.CANCELLED, "Mom Martin", MessageApp.CHEOGRAM).render(fr))
        assertEquals("Messagerie (SMS)", MessageApp.MESSAGING.titleText.render(fr))
        assertEquals("Messaging (SMS)", MessageApp.MESSAGING.title)
    }

    @Test fun theQuarantineSpeaksFrenchAndTheModelReadsEnglish() {
        val forbidden = MessageQuarantine.forbiddenText(ToolName.SEND_TEXT)
        assertEquals("Je n’agis pas selon ce que dit un message.", forbidden.render(fr))
        assertEquals("I don't act on what a message says.", forbidden.en())
        assertEquals(MessageQuarantine.forbidden(ToolName.SEND_TEXT), forbidden.en())
        assertEquals("Vérifie les mots sur la carte, puis touche Remember pour les garder.", MessageQuarantine.checkAndTapText().render(frSpokenEnMenus))
        assertEquals("Touche Envoyer, ou redis oui.", MessageQuarantine.SAY_YES_AGAIN_FR)
        assertEquals("A message is in this conversation: check this, then tap.", MessageQuarantine.CARD_LINE)
        assertEquals("Un message fait partie de cette conversation : vérifie, puis touche.", MessageQuarantine.cardLineText().render(fr))
    }

    @Test fun settingsChoicesAndHistorysMarks() {
        assertEquals("Jusqu’à ce que la notification disparaisse", KeepMessages.UNTIL_GONE.text.render(fr))
        assertEquals("Until the notification goes away", KeepMessages.UNTIL_GONE.title)
        assertEquals("Ouvrir la conversation avec ma réponse ; je touche Envoyer", ReplyRoute.OPEN_CHAT.text.render(fr))
        assertEquals("Qui a écrit", MessagesLocked.WHO_MESSAGED.text.render(fr))
        assertEquals("Marquer comme lu", AfterReading.MARK_READ.text.render(fr))
        // History's marks stay English (Conversation matches them); a screen shows them in its own language.
        assertEquals("Messages Robot read you (not kept)", MessageReading.NOT_KEPT)
        assertEquals("Messages que Robot t’a lus (pas gardés)", MessageReading.keptText(MessageReading.NOT_KEPT)!!.render(fr))
        assertEquals(null, MessageReading.keptText("Robot read 2 messages from Mom (Cheogram)"))
        assertEquals("Cheogram a changé de clé de signature : réactive-la pour lui faire confiance",
            MessageSigners.problemText("com.cheogram.android", PinVerdict.CHANGED)!!.render(fr))
        assertEquals("Cheogram (version Play Store)", MessageSigners.labelText("com.cheogram.android.playstore").render(fr))
        assertFalse(MessageSigners.problem("com.cheogram.android", PinVerdict.CHANGED)!!.contains("clé"))
    }
}
