// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.car.AppPin
import app.heyrobot.core.car.MediaNow
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.ui.car.CarMusicUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaController
import org.robolectric.shadows.ShadowMediaSessionManager

/** Car mode's reader: the tile names what plays, in any app, and the song goes nowhere but the tile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarSessionsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pinned = MusicApp("org.example.music", "ab".repeat(32), "Pinned Music")
    private val labels = mapOf("org.example.podcasts" to "Podcasts")
    private val self = "app.heyrobot.assistant"

    private fun session(pkg: String, title: String, artist: String, state: Int = PlaybackState.STATE_PLAYING): MediaController {
        val c = MediaController(ctx, MediaSession(ctx, "test").sessionToken)
        val s = Shadow.extract<ShadowMediaController>(c)
        s.setPackageName(pkg)
        s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, artist).build())
        s.setPlaybackState(PlaybackState.Builder().setState(state, 0L, 1f).build())
        Shadow.extract<ShadowMediaSessionManager>(ctx.getSystemService(MediaSessionManager::class.java)).addController(c)
        return c
    }

    @Test fun readsEveryAppsSession() {
        session("org.example.podcasts", "Episode 12", "Host")
        session("org.example.radio", "Morning", "Station", PlaybackState.STATE_PAUSED)
        assertEquals(listOf(MediaNow("org.example.podcasts", true, "Episode 12", "Host"), MediaNow("org.example.radio", false, "Morning", "Station")),
            CarSessions.read(ctx))
    }

    @Test fun theTileNamesTheOtherApp() {
        val s = CarSessions.shown(listOf(MediaNow("org.example.podcasts", true, "Episode 12", "Host")), pinned, AppPin.OK, self) { labels[it] }
        assertEquals(CarShown(CarMusicUi("Podcasts", "Episode 12", true, "Host"), "org.example.podcasts", "org.example.podcasts"), s)
    }

    @Test fun theLabelIsTheOneOnThisPhone() {
        Shadows.shadowOf(ctx.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = "org.example.podcasts"
            applicationInfo = ApplicationInfo().apply { packageName = "org.example.podcasts"; nonLocalizedLabel = "Podcasts\n" }
        })
        assertEquals("Podcasts", CarSessions.label(ctx, "org.example.podcasts"))
        assertNull(CarSessions.label(ctx, "org.example.absent"))
    }

    @Test fun withoutNotificationAccessThereAreNoSessions() {
        assertEquals(emptyList<MediaNow>(), CarSessions.guarded { throw SecurityException("no access") })
    }

    @Test fun noSessionFallsBackToThePinnedApp() {
        assertEquals(CarShown(CarMusicUi("Pinned Music"), pinned.pkg), CarSessions.shown(emptyList(), pinned, AppPin.OK, self) { labels[it] })
        assertEquals(CarShown(CarMusicUi(), null), CarSessions.shown(emptyList(), null, null, self) { labels[it] })
    }

    @Test fun anAppRobotCantNameIsLeftOut() {
        val s = CarSessions.shown(listOf(MediaNow("org.example.hidden", true, "Secret", null)), pinned, AppPin.OK, self) { labels[it] }
        assertEquals(CarShown(CarMusicUi("Pinned Music"), pinned.pkg), s)
    }

    @Test fun aPinnedAppNoLongerYoursIsShownButOpensNothing() {
        val s = CarSessions.shown(listOf(MediaNow(pinned.pkg, true, "Song", null)), pinned, AppPin.REPLACED, self) { null }
        assertEquals(CarShown(CarMusicUi("Pinned Music", "Song", true), null, pinned.pkg), s)
    }

    @Test fun readingLeavesNoLogOrFile() {
        ShadowLog.clear()
        session("org.example.podcasts", "Episode 12", "Host")
        val before = ctx.filesDir.walkTopDown().map { it.path }.toSet() + ctx.dataDir.walkTopDown().map { it.path }
        CarSessions.read(ctx)
        CarSessions.guarded { throw SecurityException("Episode 12") }
        CarSessions.label(ctx, "org.example.podcasts")
        assertFalse(ShadowLog.getLogs().any { it.msg.orEmpty().contains("Episode 12") || it.msg.orEmpty().contains("Host") || it.throwable != null })
        assertEquals(before, ctx.filesDir.walkTopDown().map { it.path }.toSet() + ctx.dataDir.walkTopDown().map { it.path })
    }
}
