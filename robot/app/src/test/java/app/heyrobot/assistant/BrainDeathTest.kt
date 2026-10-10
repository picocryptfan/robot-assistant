package app.heyrobot.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One failure of :brain counts once, though Android may report it twice (disconnected, then binding died). */
class BrainDeathTest {
    @Test fun oneDeathPerConnection() {
        val d = DeathOnce()
        d.connected()
        assertTrue(d.died())
        assertFalse(d.died())
        // Connected again and died again: a second death.
        d.connected()
        assertTrue(d.died())
    }
}
