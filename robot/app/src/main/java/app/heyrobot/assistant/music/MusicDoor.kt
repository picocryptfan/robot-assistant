// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.music

import app.heyrobot.assistant.car.CarArtReader
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.heyrobot.core.RLog
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.music.MusicPlayCode
import app.heyrobot.core.music.MusicStart
import app.heyrobot.core.music.SessionNow
import app.heyrobot.core.music.PlayRequest
import app.heyrobot.sys.MusicApps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// VOICE PROCESS. Door 1 (docs/plans/everyday-set-plan.md §3.4, D4): your music app's own media service. Robot connects a
// MediaBrowser to the pinned package's android.media.browse.MediaBrowserService, only after MusicApps.check found it still
// signed by the key you picked it with, and asks its session's TransportControls.playFromSearch with the strings code
// built (core PlayRequest). No screen opens, so it works on the lock screen. The same connection answers "what's
// playing" from the session's metadata: tags written by whoever tagged the song, shown and said as text, never
// instructions (the panel marks the reply quoted). Robot holds no MediaSession of its own, so your headset's button stays
// your music app's (the car design's rule). Many apps accept only the system and car apps as clients (UNVERIFIED per
// app): then Robot says so, and door 2 (C7's play-from-search screen) is where it would try next.

object MusicDoor {
    /** How long the app's media service has to accept Robot, and then to start playing. EST. */
    const val CONNECT_MS = 4_000L
    const val PLAY_MS = 6_000L

    /** What "what's playing" found: the song's own tags, cleaned (no control characters, each at most 120 characters). */
    data class Now(val title: String, val artist: String?, val art: android.graphics.Bitmap? = null)

    /** Whether a media session's PlaybackState state means the app is playing (or about to, or skipping): its own word. */
    fun isPlaying(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    /**
     * Play [r] in [app] through its media service. [before] runs just before the request, once the app accepted Robot (the
     * panel lets go of the question's focus there, so the music you were hearing doesn't come back while Robot connects).
     */
    suspend fun play(ctx: Context, app: MusicApp, r: PlayRequest, before: () -> Unit): MusicPlayCode {
        if (MusicApps.check(ctx, app) != MusicApps.PinNow.OK) return MusicPlayCode.REPLACED
        val service = MusicApps.service(ctx, app.pkg) ?: return MusicPlayCode.NO_SERVICE
        return connected(ctx, service) { c ->
            val state = c.playbackState
            if (state != null && (state.actions and PlaybackState.ACTION_PLAY_FROM_SEARCH) == 0L) return@connected MusicPlayCode.NO_SEARCH
            val started = CompletableDeferred<Boolean>()
            // E10: an app already playing that ignores the request hasn't started it (null: not asked yet).
            var was: SessionNow? = null
            val watch = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(s: PlaybackState?) {
                    val before = was ?: return
                    if (s?.state == PlaybackState.STATE_ERROR) started.complete(false)
                    else if (MusicStart.started(before, sessionNow(s, c.metadata))) started.complete(true)
                }

                override fun onMetadataChanged(m: MediaMetadata?) {
                    val before = was ?: return
                    if (m != null && MusicStart.started(before, sessionNow(c.playbackState, m))) started.complete(true)
                }
            }
            withContext(Dispatchers.Main) { c.registerCallback(watch, Handler(Looper.getMainLooper())) }
            try {
                before()
                val extras = Bundle().apply { for ((k, v) in r.extras()) putString(k, v) }
                val before = sessionNow(c.playbackState, c.metadata)
                withContext(Dispatchers.Main) {
                    was = before
                    c.transportControls.playFromSearch(r.query, extras)
                }
                val ok = withTimeoutOrNull(PLAY_MS) { started.await() }
                    // No word from the app: a change seen now counts (some apps only change the metadata).
                    ?: MusicStart.started(before, sessionNow(c.playbackState, c.metadata))
                if (ok) MusicPlayCode.PLAYING else MusicPlayCode.NO_ANSWER
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { c.unregisterCallback(watch) }
            }
        } ?: MusicPlayCode.REFUSED
    }

    /** "What's playing" in [app]: its session's title and artist, or null when it says nothing (or isn't reached). */
    suspend fun now(ctx: Context, app: MusicApp, withArt: Boolean = false): Pair<MusicPlayCode, Now?> {
        if (MusicApps.check(ctx, app) != MusicApps.PinNow.OK) return MusicPlayCode.REPLACED to null
        val service = MusicApps.service(ctx, app.pkg) ?: return MusicPlayCode.NO_SERVICE to null
        return connected(ctx, service) { c ->
            val m = c.metadata
            val title = clean(m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            val artist = clean(m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            MusicPlayCode.PLAYING to title?.let { Now(it, artist, if (withArt) CarArtReader.of(ctx, m) else null) }
        } ?: (MusicPlayCode.REFUSED to null)
    }

    /** Whether your pinned music app says it's playing, from its own media service; false when it doesn't answer. */
    suspend fun reported(ctx: Context, app: MusicApp): Boolean {
        if (MusicApps.check(ctx, app) != MusicApps.PinNow.OK) return false
        val service = MusicApps.service(ctx, app.pkg) ?: return false
        return connected(ctx, service) { c -> isPlaying(c.playbackState?.state) } ?: false
    }

    /** [block] with the session of [service]'s media browser, connected on the main thread; null when it refused or didn't answer. */
    private suspend fun <T> connected(ctx: Context, service: android.content.ComponentName, block: suspend (MediaController) -> T): T? {
        val result = CompletableDeferred<Boolean>()
        val browser = withContext(Dispatchers.Main) {
            MediaBrowser(ctx.applicationContext, service, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() { result.complete(true) }
                override fun onConnectionFailed() { result.complete(false) }
                override fun onConnectionSuspended() { result.complete(false) }
            }, null).also { it.connect() }
        }
        try {
            if (withTimeoutOrNull(CONNECT_MS) { result.await() } != true) return null
            val controller = withContext(Dispatchers.Main) { MediaController(ctx.applicationContext, browser.sessionToken) }
            return block(controller)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            RLog.w("music_door", t)
            return null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { try { browser.disconnect() } catch (_: Throwable) {} }
        }
    }

    private fun sessionNow(s: PlaybackState?, m: MediaMetadata?) = SessionNow(isPlaying(s?.state),
        m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), s?.position ?: 0L)

    private fun clean(s: String?): String? = s?.filter { it >= ' ' && it != '\u007f' }?.trim()?.take(PlayRequest.MAX_CHARS)?.ifEmpty { null }
}
