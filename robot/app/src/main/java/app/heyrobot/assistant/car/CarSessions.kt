// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import app.heyrobot.core.car.AppPin
import app.heyrobot.core.car.CarMusicTile
import app.heyrobot.core.car.MediaNow
import app.heyrobot.core.car.MusicTile
import app.heyrobot.assistant.music.MusicDoor
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.people.CheogramListener
import app.heyrobot.ui.car.CarMusicUi

// VOICE PROCESS. Car mode's own view of what any app is playing (the user's word, 30 Oct 2026): the app's name, title and
// artist from Android's media sessions, shown on the car screen and nowhere else. Never logged, saved or sent, so nothing
// here logs, even a failure (ProcessIsolationTest.carSessionsReachNoLogOrStore). Android answers only with notification
// access; without it, or with no session, the tile is your pinned app's, with no prompt.

/** What the music tile shows, and the app its name opens (null: none). */
data class CarShown(val ui: CarMusicUi, val opens: String?, val session: String? = null)

object CarSessions {
    /** Android's active sessions in its own order; empty without notification access, with none, or on any failure. */
    fun read(ctx: Context): List<MediaNow> = guarded {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(ctx, CheogramListener::class.java)).map { now(it) }
    }

    /** The picture of [pkg]'s session, for the car screen only; null without one or on any failure. */
    fun art(ctx: Context, pkg: String): Bitmap? = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(ctx, CheogramListener::class.java)).firstOrNull { it.packageName == pkg }?.let { CarArtReader.of(ctx, it.metadata) }
    } catch (_: Exception) { null }

    /** [block], or no sessions when it fails (without notification access Android throws SecurityException). */
    internal fun guarded(block: () -> List<MediaNow>): List<MediaNow> = try { block() } catch (_: Exception) { emptyList() }

    private fun now(c: MediaController): MediaNow {
        val m = c.metadata
        return MediaNow(c.packageName, MusicDoor.isPlaying(c.playbackState?.state), m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
    }

    /** The app's name on this phone, or null when Robot can't see it. */
    fun label(ctx: Context, pkg: String): String? = try {
        val pm = ctx.packageManager
        CarMusicTile.clean(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
    } catch (_: Exception) { null }

    /**
     * The tile for [sessions]. A session is named by its own app, and one Robot can't name is left out. A session of a
     * pinned app that's no longer yours ([pin] not OK) is shown, but its name opens nothing.
     */
    fun shown(sessions: List<MediaNow>, app: MusicApp?, pin: AppPin?, self: String, seen: Set<String> = emptySet(), labelOf: (String) -> String?): CarShown {
        val name = { pkg: String -> labelOf(pkg) ?: app?.label?.takeIf { pkg == app.pkg } }
        return when (val tile = CarMusicTile.of(sessions.filter { name(it.pkg) != null }, app, pin, self, seen)) {
            is MusicTile.Session -> CarShown(CarMusicUi(name(tile.pkg), CarMusicTile.song(tile), tile.playing, CarMusicTile.artist(tile)), tile.pkg.takeIf { it != app?.pkg || pin == AppPin.OK }, tile.pkg)
            is MusicTile.Pinned -> CarShown(CarMusicUi(tile.app.label), tile.app.pkg)
            MusicTile.Choose -> CarShown(CarMusicUi(), null)
        }
    }
}
