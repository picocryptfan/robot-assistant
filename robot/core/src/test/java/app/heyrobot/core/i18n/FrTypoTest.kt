// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** §6.4: the OQLF's spaces and Robot's apostrophe and ellipsis, applied by code; addresses, times and brands untouched. */
class FrTypoTest {
    private val nb = FrTypo.NBSP
    private val fine = FrTypo.FINE

    private val cases = listOf(
        "Répondre, suivant ou arrêter ?" to "Répondre, suivant ou arrêter$fine?",
        "Répondre, suivant ou arrêter?" to "Répondre, suivant ou arrêter$fine?",
        "Vraiment ?!" to "Vraiment$fine?!",
        "Attention ; c'est fait !" to "Attention$fine; c’est fait$fine!",
        "Note : achète du lait" to "Note$nb: achète du lait",
        "Note: achète du lait" to "Note$nb: achète du lait",
        "Dis « hey robot »" to "Dis «${nb}hey robot$nb»",
        "Dis «hey robot»" to "Dis «${nb}hey robot$nb»",
        "30 % de risque" to "30$nb% de risque",
        "{n} %" to "{n}$nb%",
        "{question} ?" to "{question}$fine?",
        "{a}: {b}" to "{a}$nb: {b}",
        "1,5 Go libres" to "1,5${nb}Go libres",
        "{n} Mo" to "{n}${nb}Mo",
        "Réveille-moi à 7 h" to "Réveille-moi à 7${nb}h",
        "{h} h {m}" to "{h}${nb}h$nb{m}",
        "Comme 6 h 30" to "Comme 6${nb}h${nb}30",
        "il y a {n} min" to "il y a {n}${nb}min",
        "en 3 s, puis 15 ms" to "en 3${nb}s, puis 15${nb}ms",
        "{device} s'est arrêté" to "{device} s’est arrêté",
        "l'étape 2 s'en occupe" to "l’étape 2 s’en occupe",
        "10 $" to "10$nb$",
        "C'est fait" to "C’est fait",
        "Attends..." to "Attends…",
        "Va sur https://robot-app.ai/aide?x=1 : c'est là" to "Va sur https://robot-app.ai/aide?x=1$nb: c’est là",
        "Termux:API est installé" to "Termux:API est installé",
        "Avec un lien claude://" to "Avec un lien claude://",
        "Lance `adb shell dumpsys meminfo {p}:mt` : c'est tout" to "Lance `adb shell dumpsys meminfo {p}:mt`$nb: c’est tout",
        "Le minuteur de 7:02 sonne" to "Le minuteur de 7:02 sonne",
        "Réglages › Mémoire" to "Réglages › Mémoire",
    )

    @Test fun theSpacesGoWhereTheOqlfPutsThem() {
        for ((input, want) in cases) assertEquals(input, want, FrTypo.fix(input))
    }

    @Test fun runningItAgainChangesNothing() {
        for ((input, _) in cases) FrTypo.fix(input).let { assertEquals(input, it, FrTypo.fix(it)) }
    }

    @Test fun theSpacesAreNoBreak() {
        // Never a plain space before ":" ";" "!" "?" or inside « », so the line never breaks there.
        for ((input, _) in cases) {
            val out = FrTypo.fix(input).replace(Regex("https?://\\S+"), "")
            assertFalse(out, Regex(" [:;!?»]|« ").containsMatchIn(out))
        }
    }
}
