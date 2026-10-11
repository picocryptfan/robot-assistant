package app.heyrobot.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** free_memory's unload request is taken once, by the end of the turn that asked for it, and lapses if never taken. */
class ModelUnloadTest {
    @Test fun aRequestIsTakenOnceAndLapses() {
        var now = 1_000_000L
        ModelUnload.clock = { now }
        try {
            assertNull(ModelUnload.take())
            ModelUnload.request(4_200_000_000)
            assertEquals(4_200_000_000, ModelUnload.take())
            assertNull(ModelUnload.take())
            // One whose answer never ended in this panel lapses after ten minutes.
            ModelUnload.request(3_000_000_000)
            now += 11 * 60_000L
            assertNull(ModelUnload.take())
        } finally {
            ModelUnload.clock = { android.os.SystemClock.elapsedRealtime() }
        }
    }
}
