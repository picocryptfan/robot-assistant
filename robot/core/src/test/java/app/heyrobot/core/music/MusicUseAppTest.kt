// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.music

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// fix-car-media-pick: your music app changed by voice, in English and French, the same pin the car screen's list makes.
class MusicUseAppTest {
    @Test fun yourMusicAppIsChangedByName() {
        assertEquals(MusicCommand.UseApp("spotify", Lang.EN), MusicRouter.route("Use Spotify in the car"))
        assertEquals(MusicCommand.UseApp("youtube music", Lang.EN), MusicRouter.route("set my music app to YouTube Music."))
        assertEquals(MusicCommand.UseApp("pocket casts", Lang.EN), MusicRouter.route("make Pocket Casts my music app"))
        assertEquals(MusicCommand.UseApp("spotify", Lang.FR), MusicRouter.route("Utilise Spotify dans l'auto"))
        assertEquals(MusicCommand.UseApp("deezer", Lang.FR), MusicRouter.route("utilise Deezer pour la musique"))
        assertEquals(MusicCommand.UseApp("deezer", Lang.FR), MusicRouter.route("change mon appli de musique pour Deezer"))
    }

    @Test fun otherWordsAreNotAPick() {
        for (s in listOf("use the timer for ten minutes", "play Spotify", "use my car", "use a b c d e for music"))
            assertFalse(s, MusicRouter.route(s) is MusicCommand.UseApp)
    }

    @Test fun theSpokenNameFindsItsApp() {
        assertEquals(1, MusicRouter.matchApp("youtube music", listOf("YouTube", "YouTube Music")))
        assertEquals(0, MusicRouter.matchApp("youtube", listOf("YouTube", "YouTube Music")))
        assertEquals(0, MusicRouter.matchApp("spotify", listOf("Spotify: Music and Podcasts")))
        assertEquals(0, MusicRouter.matchApp("the radio", listOf("Radio")))
        assertEquals(-1, MusicRouter.matchApp("tidal", listOf("Spotify", "Deezer")))
    }
}
