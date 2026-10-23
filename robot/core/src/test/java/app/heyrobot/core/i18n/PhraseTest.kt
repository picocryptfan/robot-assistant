// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * A Phrase renders at the edge: in its sentence's language, with Robot's screens in the app's and
 * Android's in the phone's, English for the model; plural forms by CLDR's categories; an entry without its French yet
 * is a whole English sentence; the voice gets no typography characters; a pseudo-locale marks only the entry's words.
 */
class PhraseTest {
    private object T : Strings("test", area = "test") {
        val hello = s("hello", "Hello.", "Bonjour.")
        val notYet = s("not_yet", "Only in English: {size}.", null)
        val events = plural("events", count = "n", en = Plural(one = "You have {n} event: {list}.", other = "You have {n} events: {list}."),
            fr = Plural(one = "Tu as {n} rendez-vous : {list}.", other = "Tu as {n} rendez-vous : {list}.", many = "Tu as {n} de rendez-vous : {list}."))
        val full = s("full", "My memory is full. Delete things in {where}.", "Ma mémoire est pleine. Efface des choses dans {where}.")
        val allow = s("allow", "Allow it in {where}.", "Autorise-le dans {where}.")
        val question = s("question", "Reply, next, or stop?", "Répondre, suivant ou arrêter ?")
        val hint = s("hint", "Say “{phrase}”.", "Dis « {phrase} ».")
        val rain = s("rain", "{p}% chance of rain.", "{p} % de risque de pluie.", voiceFr = "{p} pour cent de risque de pluie.")
        val at = s("at", "At {time}.", "À {time}.")
    }

    private val fr = Langs.of(Lang.FR)
    private val nb = Fmt.NBSP

    @Test fun eachLanguageAndTheModelsEnglish() {
        assertEquals("Hello.", T.hello().render(Langs.EN))
        assertEquals("Bonjour.", T.hello().render(fr))
        assertEquals("Hello.", T.hello().en())
        assertEquals("Hello.", T.hello().render(fr.copy(say = Lang.EN)))
    }

    @Test fun pluralFormsByCount() {
        val list = Arg.list(listOf(Phrase.plain("dentist"), Phrase.plain("gym")))
        assertEquals("You have 1 event: dentist and gym.", T.events(1, "list" to list).render(Langs.EN))
        assertEquals("You have 0 events: dentist and gym.", T.events(0, "list" to list).render(Langs.EN))
        assertEquals("Tu as 0 rendez-vous$nb: dentist et gym.", T.events(0, "list" to list).render(fr))
        assertEquals("Tu as 1${nb}000${nb}000 de rendez-vous$nb: dentist et gym.", T.events(1_000_000, "list" to list).render(fr))
        assertEquals("You have 1,000,000 events: dentist and gym.", T.events(1_000_000, "list" to list).render(Langs.EN))
    }

    @Test fun anEntryWithoutItsFrenchIsAWholeEnglishSentence() {
        assertFalse(T.notYet.translated(Lang.FR))
        // English words and English numbers, never « 1,5 Go » inside an English sentence.
        assertEquals("Only in English: 1.5 GB.", T.notYet("size" to Arg.Bytes(1_500_000_000)).render(fr))
    }

    @Test fun robotsScreensInTheAppLanguageAndAndroidsInThePhones() {
        val memory = UiStrings.inSettings(UiStrings.memory)
        // A French sentence on English menus names the screen you will see.
        assertEquals("Ma mémoire est pleine. Efface des choses dans Settings › Memory.",
            T.full("where" to memory).render(Langs(say = Lang.FR, ui = Lang.EN, system = Lang.EN)))
        assertEquals("Ma mémoire est pleine. Efface des choses dans Réglages › Mémoire.", T.full("where" to memory).render(fr))
        assertEquals("My memory is full. Delete things in Réglages › Mémoire.",
            T.full("where" to memory).render(Langs(say = Lang.EN, ui = Lang.FR, system = Lang.EN)))
        assertEquals("Autorise-le dans Paramètres › Applications › Robot › Autorisations.",
            T.allow("where" to AndroidPath.APP_PERMISSIONS).render(Langs(say = Lang.FR, ui = Lang.EN, system = Lang.FR)))
        assertEquals("Allow it in Settings › Apps › Robot › Permissions.", T.allow("where" to AndroidPath.APP_PERMISSIONS).en())
    }

    @Test fun frenchTypographyOnScreenAndPlainSpeech() {
        assertEquals("Répondre, suivant ou arrêter${FrTypo.FINE}?", T.question().render(fr))
        assertEquals("Dis «${nb}hey robot$nb».", T.hint("phrase" to "hey robot").render(fr))
        assertEquals("Dis hey robot.", T.hint("phrase" to "hey robot").render(fr.voice))
        assertEquals("Say hey robot.", T.hint("phrase" to "hey robot").render(Langs.EN.voice))
        assertEquals("30$nb% de risque de pluie.", T.rain("p" to 30).render(fr))
        assertEquals("30 pour cent de risque de pluie.", T.rain("p" to 30).render(fr.voice))
        assertEquals("30 percent chance of rain.", T.rain("p" to 30).render(Langs.EN.voice))
        assertEquals("À 7${nb}h${nb}02.", T.at("time" to LocalTime.of(7, 2)).render(fr))
        assertEquals("À midi.", T.at("time" to LocalTime.NOON).render(fr.voice))
        // Your words are never changed: a straight apostrophe in a name stays one.
        assertEquals("Dis «${nb}O'Brien$nb».", T.hint("phrase" to "O'Brien").render(fr))
        assertEquals("Ma mémoire est pleine. Efface des choses dans Réglages, Mémoire.",
            T.full("where" to UiStrings.inSettings(UiStrings.memory)).render(fr.voice))
    }

    @Test fun joinsAndLists() {
        val p = Phrase.join(T.hello(), Phrase.plain("Marie"), sep = ", ")
        assertEquals("Bonjour., Marie", p.render(fr))
        assertEquals("a, b or c", Phrase.ListOf(listOf("a", "b", "c").map(Phrase::plain), ListKind.OR).en())
        assertEquals("a, b ou c", Phrase.ListOf(listOf("a", "b", "c").map(Phrase::plain), ListKind.OR).render(fr))
    }

    @Test fun aMissingArgumentShowsItsPlaceholder() {
        // CatalogsTest renders every entry with all its arguments; this is what it would catch.
        assertEquals("Dis «${nb}{phrase}$nb».", T.hint().render(fr))
    }

    @Test fun pseudoLocalesMarkOnlyTheEntrysWords() {
        val x = T.hint("phrase" to "hey robot").render(Langs.EN.copy(pseudo = Pseudo.ACCENTS))
        assertTrue(x, x.startsWith("[Šåý “hey robot”.") && x.endsWith("]"))
        assertTrue(x, x.length > "Say “hey robot”.".length + 2)
        val rtl = T.hello().render(Langs.EN.copy(pseudo = Pseudo.BIDI))
        assertTrue(rtl.startsWith("\u200F") && rtl.contains("Hello."))
        assertEquals(Pseudo.ACCENTS, Pseudo.of("en-XA"))
        assertEquals(Pseudo.BIDI, Pseudo.of("ar-XB"))
        assertEquals(null, Pseudo.of("fr-CA"))
    }
}
