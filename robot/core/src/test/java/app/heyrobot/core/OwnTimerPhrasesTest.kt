// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnTimerPhrasesTest {
    private fun r(s: String, names: List<String> = emptyList(), ringing: List<String> = emptyList()) = OwnTimerPhrases.route(s, names, ringing)

    @Test fun setsNamedAndUnnamedInEnglish() {
        assertEquals(TimerCommand.Set("pasta", 540, false), r("Pasta timer nine minutes"))
        assertEquals(TimerCommand.Set("pasta", 540, false), r("set a pasta timer for 9 minutes"))
        assertEquals(TimerCommand.Set("eggs", 300, false), r("set a timer for 5 minutes for the eggs"))
        assertEquals(TimerCommand.Set("pasta", 540, false), r("set a 9 minute pasta timer"))
        assertEquals(TimerCommand.Set("bread", 5400, false), r("start a timer called bread for an hour and a half"))
        assertEquals(TimerCommand.Set("", 1500, false), r("set a timer for twenty five minutes"))
        assertEquals(TimerCommand.Set("", 5400, false), r("timer 1 hour 30 minutes"))
        assertEquals(TimerCommand.Set("", 300, false), r("set the timer to 5 minutes"))
    }

    @Test fun setsInFrench() {
        assertEquals(TimerCommand.Set("pâtes", 540, true), r("Minuterie pâtes neuf minutes"))
        assertEquals(TimerCommand.Set("pâtes", 540, true), r("mets une minuterie de 9 minutes pour les pâtes"))
        assertEquals(TimerCommand.Set("riz", 1800, true), r("lance une minuterie riz d'une demi-heure"))
        assertEquals(TimerCommand.Set("", 5400, true), r("minuterie de 1 h 30"))
        assertEquals(TimerCommand.Set("", 1260, true), r("mets une minuterie de vingt et une minutes"))
    }

    @Test fun leavesOtherRequestsToTheRest() {
        assertNull(r("set a timer for 5 minutes and turn on the flashlight"))
        assertNull(r("remind me in 5 minutes to check the timer"))
        assertNull(r("how long is the drive to Halifax", listOf("pasta")))
        assertNull(r("stop the music", listOf("pasta")))
        assertNull(r("cancel my alarm", listOf("pasta")))
        assertNull(r("stop", listOf("pasta")))
        assertNull(r("how much time is left on my timer"))
        assertNull(r("set an alarm for 7"))
    }

    @Test fun asksHowLong() {
        val n = listOf("pasta", "pâtes", "")
        assertEquals(TimerCommand.Left("pasta", false), r("How long on the pasta?", n))
        assertEquals(TimerCommand.Left("pasta", false), r("how long until the pasta is done", n))
        assertEquals(TimerCommand.Left("", false), r("how much time is left on my timer", n))
        assertEquals(TimerCommand.Left("pâtes", true), r("il reste combien de temps pour les pâtes ?", n))
        assertEquals(TimerCommand.Left("", true), r("combien de temps reste-t-il à ma minuterie", n))
    }

    @Test fun cancels() {
        val n = listOf("pasta", "rice")
        assertEquals(TimerCommand.Cancel("pasta", false), r("cancel the pasta timer", n))
        assertEquals(TimerCommand.Cancel("rice", false), r("stop the rice", n))
        assertEquals(TimerCommand.Cancel("", false), r("cancel the timer", n))
        assertEquals(TimerCommand.Cancel(TimerCommand.ALL, false), r("cancel all my timers", n))
        assertEquals(TimerCommand.Cancel("bread", false), r("cancel the bread timer", n))
        assertEquals(TimerCommand.Cancel("pâtes", true), r("annule la minuterie des pâtes", listOf("pâtes")))
    }

    @Test fun stopsOnlyWhileOneRings() {
        val n = listOf("pasta", "rice")
        assertEquals(TimerCommand.Stop("", false), r("stop", n, listOf("pasta")))
        assertEquals(TimerCommand.Stop("", false), r("Stop the timer.", n, listOf("pasta")))
        assertEquals(TimerCommand.Stop("pasta", false), r("stop the pasta timer", n, listOf("pasta")))
        assertEquals(TimerCommand.Cancel("rice", false), r("cancel the rice timer", n, listOf("pasta")))
        assertEquals(TimerCommand.Stop("", true), r("Arrête !", n, listOf("pasta")))
        assertEquals(TimerCommand.Stop("", true), r("c'est bon", n, listOf("pasta")))
    }
}
