// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 1.1 (the l10n design's WP3): what Robot answers about its memory in Québec French, the lock screen's and the settings'
 * words, and what History keeps instead of a secret. A Robot screen named in a reply is in the menus' language; the English stays 1.0's, word for word.
 */
class MemoryInFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private val frSpokenEnMenus = Langs(say = Lang.FR, ui = Lang.EN, system = Lang.EN)
    private fun remember(s: String) = MemoryCommands.parse(s) as MemoryCommand.Remember

    @Test fun memoryFullNamesTheMenusPage() {
        val c = remember("Souviens-toi que Julie aime les tulipes")
        val full = MemoryReplies.savedText(c, AddOutcome.FULL)
        assertEquals("Ma mémoire est pleine. Efface des choses dans Settings › Memory d’abord.", full.render(frSpokenEnMenus))
        assertEquals("Ma mémoire est pleine. Efface des choses dans Réglages › Mémoire d’abord.", full.render(fr))
        assertEquals("My memory is full. Delete some things in Settings › Memory first.", full.en())
        assertEquals("Ma mémoire est pleine. Efface des choses dans Réglages, Mémoire d’abord.", full.render(fr.voice))
    }

    @Test fun repliesInFrench() {
        assertEquals("C’est oublié.", MemoryReplies.forgottenText(1).render(fr))
        assertEquals("C’est oublié : 3 choses.", MemoryReplies.forgottenText(3).render(fr))
        assertEquals("Forgotten: 3 things.", MemoryReplies.forgottenText(3).en())
        assertEquals("Laquelle dois-je oublier ?", MemoryReplies.whichText().render(fr))
        assertEquals("Je retiens 2 choses. Par exemple : « Julie aime les tulipes ».",
            MemoryReplies.aboutMeText(2, listOf(MemoryItem("m_aaaaaaaaaaaa", "Julie aime les tulipes", createdAt = 1)), fr = true).render(fr))
        assertEquals("Je ne me souviens de rien sur Julie. Et 1 chose privée, dans Réglages › Mémoire.",
            MemoryReplies.recallText("Julie", emptyList(), 1, fr = true).render(fr))
        assertEquals("Déverrouille d’abord pour l’enregistrer en mémoire.", MemoryReplies.unlockText(saving = true).render(fr))
        assertEquals("Ouvre Réglages › Mémoire pour tout effacer.", MemoryReplies.forgetAllText().render(fr))
        assertEquals("Touche Undo, ou modifie-le dans Memory.", MemoryReplies.cardSavedDetailText().render(frSpokenEnMenus))
        assertEquals("Undo, or change it in Memory.", MemoryReplies.CARD_SAVED_DETAIL)
        assertEquals("Retenu, utilisé dans chaque réponse",
            MemoryReplies.savedCardText(MemoryItem("m_aaaaaaaaaaaa", "Call me Sam", MemoryKind.PREFERENCE, pinned = true, createdAt = 1)).render(fr))
    }

    @Test fun aSecretIsNeverSaidBackInEitherLanguage() {
        val nip = remember("Souviens-toi que mon NIP est 4821")
        assertEquals("C’est noté : un NIP, gardé en mémoire.", MemoryReplies.savedText(nip, AddOutcome.SAVED).render(fr))
        assertEquals("Retiens : [un NIP, gardé en mémoire]", MemoryHygiene.question(nip, "Souviens-toi que mon NIP est 4821"))
        assertEquals("[a PIN, kept in Memory]", SecretDetector.placeholder(setOf(SecretKind.PIN)))
        assertEquals("[un NIP, gardé en mémoire]", SecretDetector.placeholderText(setOf(SecretKind.PIN)).render(fr))
        assertFalse(MemoryReplies.savedText(nip, AddOutcome.SAVED).render(fr).contains("4821"))
        assertEquals("[An answer with something private from Memory: not kept in History.]", MemoryHygiene.SECRET_ANSWER)
    }

    @Test fun settingsWordsAndDates() {
        val zone = ZoneOffset.UTC
        val oct9 = LocalDate.of(2026, 10, 9).atStartOfDay(zone).toInstant().toEpochMilli()
        val nov1 = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val item = MemoryItem("m_aaaaaaaaaaaa", "x", pinned = true, createdAt = oct9, expiresAt = nov1, uses = 2)
        assertEquals("Pinned · saved Oct 9 · until Nov 1 · used in 2 answers", MemoryDates.line(item, oct9, zone))
        assertEquals("Épinglé · enregistré le 9 octobre · jusqu’au 1er novembre · utilisé dans 2 réponses", MemoryDates.lineText(item, oct9, zone).render(fr))
        assertEquals("Saved Oct 9", MemoryDates.line(item.copy(pinned = false, expiresAt = null, uses = 0), oct9, zone))
        assertEquals("Avec les IA externes en qui j’ai confiance", HelperMemory.TRUSTED_HELPERS.text.render(fr))
        assertEquals("With helpers I trust", HelperMemory.TRUSTED_HELPERS.title)
        assertEquals("De quelque chose que Robot a lu", MemorySource.CONTENT.text.render(fr))
        assertEquals("Une liste dans les réglages (discrète)", SuggestionStyle.QUIET_LIST.text.render(fr))
        assertEquals("Télécharger le modèle de recherche (165 Mo, EmbeddingGemma 2 de Google, Apache-2.0)", SearchModel.titleText().render(fr))
    }
}
