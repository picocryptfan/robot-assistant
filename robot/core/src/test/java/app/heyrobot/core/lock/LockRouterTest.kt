// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Phase B: every §9.1 phrasing in both languages, the misfire list, and the times and lengths.
class LockRouterTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 10, 19, h, m), zone)
    private fun route(s: String, now: ZonedDateTime = at(18), names: List<String> = listOf("Bedtime"), phoneLock: Boolean = true, lockOn: Boolean = true) =
        LockRouter.route(s, now, names, phoneLock, lockOn)
    private fun end(s: String, now: ZonedDateTime = at(18)) = (route(s, now) as LockCommand.Start).end(now)

    @Test fun startInEnglish() {
        // "Until 7" is the next 7:00, AM or PM, whichever comes first.
        assertEquals(at(19), end("lock the phone until 7"))
        assertEquals(at(7).plusDays(1), end("Lock the phone until 7", at(20)))
        assertEquals(at(19), end("Hey Robot, lock my phone until 7 pm."))
        assertEquals(at(7).plusDays(1), end("lock the phone until 7 am"))
        assertEquals(at(19, 30), end("please lock the phone until 7:30"))
        assertEquals(at(19), end("lock the phone till 19:00"))
        assertEquals(at(7, 30).plusDays(1), end("lock the phone until 07:30"))
        assertEquals(at(0).plusDays(1), end("lock the phone until midnight"))
        assertEquals(at(19), end("lock my phone for an hour"))
        assertEquals(at(18, 30), end("lock the phone for 30 minutes"))
        assertEquals(at(20), end("lock the phone for two hours"))
        assertEquals(at(18, 30), end("lock the phone for half an hour"))
        assertEquals(at(19, 30), end("lock the phone for an hour and a half"))
        assertEquals(Lang.EN, route("lock the phone for 30 minutes")!!.lang)
        // It is a Start whatever Phone lock is (the panel says to set it up).
        assertTrue(route("lock the phone until 7", phoneLock = false, lockOn = false) is LockCommand.Start)
    }

    @Test fun startInQuebecFrench() {
        assertEquals(at(19), end("barre le téléphone jusqu'à 19 h"))
        assertEquals(at(7).plusDays(1), end("Barre le téléphone jusqu’à 7 h"))
        assertEquals(at(19, 30), end("verrouille mon cell jusqu'à 19 h 30"))
        assertEquals(at(12).plusDays(1), end("bloque le téléphone jusqu'à midi"))
        assertEquals(at(19), end("bloque mon téléphone pour une heure"))
        assertEquals(at(18, 30), end("verrouille le téléphone pendant 30 minutes"))
        assertEquals(at(18, 30), end("Robot, peux-tu barrer le téléphone pendant une demi-heure".replace("barrer", "barre")))
        assertEquals(Lang.FR, route("barre le téléphone jusqu'à 19 h")!!.lang)
    }

    @Test fun askEndAndSkip() {
        for (s in listOf("how long is left", "How much time is left?", "when does my phone open", "is the phone locked", "combien de temps il reste?",
            "Combien de temps il reste", "à quelle heure mon téléphone ouvre?", "est-ce que mon téléphone est barré"))
            assertTrue(s, route(s) is LockCommand.Ask)
        assertEquals(Lang.FR, route("combien de temps il reste?")!!.lang)
        for (s in listOf("end the lock", "stop the phone lock", "unlock the phone", "arrête le blocage", "débarre le téléphone", "Débarre mon téléphone."))
            assertTrue(s, route(s) is LockCommand.End)
        assertEquals(LockCommand.Skip(null, Lang.EN), route("skip tonight's lock"))
        assertEquals(LockCommand.Skip("Bedtime", Lang.EN), route("skip bedtime"))
        assertEquals(LockCommand.Skip(null, Lang.FR), route("saute le blocage de ce soir"))
        assertEquals(LockCommand.Skip("Bedtime", Lang.FR), route("saute bedtime"))
        // "End the lock" means something to Robot only while a lock is on; asking and skipping only with Phone lock on.
        assertNull(route("end the lock", lockOn = false))
        assertNull(route("unlock the phone", lockOn = false))
        assertNull(route("how long is left", phoneLock = false, lockOn = false))
        // "How long is left" alone may be a timer's: Phone lock's only while a lock is on; "when does my phone open" always.
        assertNull(route("how long is left", lockOn = false))
        assertNull(route("combien de temps il reste", lockOn = false))
        assertTrue(route("when does my phone open", lockOn = false) is LockCommand.Ask)
        assertTrue(route("how long is left on the phone lock", lockOn = false) is LockCommand.Ask)
        assertNull(route("skip tonight's lock", phoneLock = false, lockOn = false))
    }

    @Test fun itDoesntMisfire() {
        for (s in listOf("lock the door", "I'm locked out", "the screen is locked", "until 7 I was at work", "barre la porte", "mon téléphone est barré",
            "lock the phone", "lock the phone now", "barre le téléphone", "skip this song", "skip it", "skip to the next track", "how long is left on my timer",
            "lock the phone until later", "lock the phone for a while", "lock the phone until 25", "what time is it", ""))
            assertNull(s, route(s))
    }

    @Test fun timesAndLengths() {
        assertEquals(Duration.ofMinutes(90), LockRouter.length("90 minutes"))
        assertEquals(Duration.ofHours(3), LockRouter.length("trois heures"))
        assertNull(LockRouter.length("0 minutes"))
        assertEquals(true, LockRouter.clock("7", Lang.EN)!!.second)
        assertEquals(false, LockRouter.clock("7", Lang.FR)!!.second)
        assertEquals(false, LockRouter.clock("7 pm", Lang.EN)!!.second)
        assertEquals(19, LockRouter.clock("7 tonight", Lang.EN)!!.first.hour)
        assertNull(LockRouter.clock("7:75", Lang.EN))
        assertNull(LockRouter.clock("13 pm", Lang.EN))
    }
}
