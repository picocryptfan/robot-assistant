package app.heyrobot.core.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E10: an app already playing that ignores a request hasn't started what was asked for. */
class MusicStartTest {
    private val paused = SessionNow(false, "Old", "Band", 60_000)
    private val playing = paused.copy(playing = true)

    @Test fun anAppThatWasntPlayingAndNowIsHasStarted() {
        assertTrue(MusicStart.started(paused, playing))
        assertTrue(MusicStart.started(null, playing))
        assertFalse(MusicStart.started(paused, paused))
        assertFalse(MusicStart.started(playing, paused))
        assertFalse(MusicStart.started(null, paused))
    }

    @Test fun anAppAlreadyPlayingHasStartedOnlyWhenWhatItPlaysChanged() {
        assertFalse(MusicStart.started(playing, playing))
        assertFalse(MusicStart.started(playing, playing.copy(positionMs = 62_000)))
        assertTrue(MusicStart.started(playing, playing.copy(title = "New")))
        assertTrue(MusicStart.started(playing, playing.copy(artist = "Other")))
        assertTrue(MusicStart.started(playing.copy(title = null, artist = null), playing))
        // The same song asked for again: it goes back to its start.
        assertTrue(MusicStart.started(playing, playing.copy(positionMs = 0)))
        // A second's jitter in what an app reports isn't a restart.
        assertFalse(MusicStart.started(playing, playing.copy(positionMs = 59_000)))
    }
}
