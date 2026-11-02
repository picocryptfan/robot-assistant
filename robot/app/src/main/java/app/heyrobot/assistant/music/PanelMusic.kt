// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.music

import android.view.KeyEvent
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Choice
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.music.MusicAppCode
import app.heyrobot.core.music.MusicCommand
import app.heyrobot.core.music.MusicContext
import app.heyrobot.core.music.MusicNowCode
import app.heyrobot.core.music.MusicPlayCode
import app.heyrobot.core.music.MusicRouter
import app.heyrobot.core.music.PlayRequest
import app.heyrobot.core.output.MusicAction
import app.heyrobot.core.output.spoken
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.MusicApps
import app.heyrobot.voice.Cues
import app.heyrobot.voice.TurnFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * VOICE PROCESS. The panel's music slot (docs/plans/everyday-set-plan.md §3, D1 to D5): after Files and before hands-free
 * (PanelController.send), in and out of car mode, while Settings › Actions › Music › Music by voice is on.
 *
 * - The keys, in §3.3's order with the question's pause (voice TurnFocus): "pause" goes while Robot still holds the focus,
 *   so the player stays paused when the turn ends; "play", "next song" and "previous song" let go first
 *   (TurnFocus.yieldToPlayer), so the player takes the focus cleanly and the music comes back on the new song.
 * - Play by name through your music app: the card the first time (Q1), then door 1 (MusicDoor: its media service, which
 *   works locked). Door 2, the play-from-search screen through Skills connections' C7, isn't on this base: where it
 *   goes is marked DOOR 2 below.
 * - "What's playing", from your music app's media service only: the song's tags are content (the reply is quoted), and
 *   History keeps a placeholder in their place (§3.7).
 *
 * Replies are a line on screen and a short cue, never speech, so nothing talks over the music; Robot speaks only when
 * something went wrong, when it asks you to pick, and to say what's playing. The voice log gets codes, never a song, an
 * artist or an app.
 */
object PanelMusic {
    /** What the music needs of the phone. Tests give their own. */
    interface Port {
        fun settings(): EverydaySettings
        /** Your music app picked (or forgotten), written to everyday_settings.json. */
        fun save(app: MusicApp?)
        val carMode: Boolean
        val wakePhrases: List<String>
        /** Media was playing as you started asking (TurnFocus's note, or now for a typed question). */
        fun playingAsAsked(): Boolean
        /** Media plays now. */
        fun playingNow(): Boolean
        /** The media key, down and up, to whatever plays; false when Android refused it. */
        fun key(a: MusicAction): Boolean
        /** Lets go of the question's focus for the rest of the turn (TurnFocus.yieldToPlayer). */
        fun yieldFocus()
        fun cue()
        val phoneLockOn: Boolean
        /** The active phone locks leave [pkg] open. */
        fun lockAllows(pkg: String): Boolean
        fun apps(): List<MusicApps.Found>
        fun pin(f: MusicApps.Found): MusicApp?
        fun check(app: MusicApp): MusicApps.PinNow
        suspend fun play(app: MusicApp, r: PlayRequest, before: () -> Unit): MusicPlayCode
        suspend fun now(app: MusicApp): Pair<MusicPlayCode, MusicDoor.Now?>
        /** For the car screen: the same, with the song's picture. */
        suspend fun carNow(app: MusicApp): Pair<MusicPlayCode, MusicDoor.Now?> = now(app)
        suspend fun reported(app: MusicApp): Boolean
        suspend fun wait(ms: Long)
        fun clock(): Long
    }

    /** The phone. */
    private object Phone : Port {
        private val ctx get() = VoiceHub.app
        override fun settings() = EverydaySettingsStore.current(ctx)
        override fun save(app: MusicApp?) {
            EverydaySettingsStore.update(ctx) { it.copy(musicApp = app) }
        }
        override val carMode: Boolean get() = CarModeController.on
        override val wakePhrases: List<String> get() = VoiceHub.settings.wakePhrases.map { it.text }
        override fun playingAsAsked() = TurnFocus.playingAsAsked(ctx)
        override fun playingNow() = TurnFocus.playingNow(ctx)
        override fun key(a: MusicAction): Boolean = try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, a.keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, a.keyCode))
            true
        } catch (t: Throwable) {
            RLog.w("music_key", t)
            false
        }
        override fun yieldFocus() = TurnFocus.yieldToPlayer()
        override fun cue() = Cues.music()
        override val phoneLockOn: Boolean get() = PhoneLockNow.on(ctx)
        override fun lockAllows(pkg: String) = pkg in PhoneLockNow.apps(ctx)
        override fun apps() = MusicApps.find(ctx)
        override fun pin(f: MusicApps.Found) = MusicApps.pin(ctx, f)
        override fun check(app: MusicApp) = MusicApps.check(ctx, app)
        override suspend fun play(app: MusicApp, r: PlayRequest, before: () -> Unit) = MusicDoor.play(ctx, app, r, before)
        override suspend fun now(app: MusicApp) = MusicDoor.now(ctx, app)
        override suspend fun carNow(app: MusicApp) = MusicDoor.now(ctx, app, withArt = true)
        override suspend fun reported(app: MusicApp) = MusicDoor.reported(ctx, app)
        override suspend fun wait(ms: Long) = delay(ms)
        override fun clock() = System.currentTimeMillis()
    }

    @Volatile internal var port: Port = Phone

    /** When Robot itself last paused the music (bare "play" resumes it within MusicContext.RESUME_MS). */
    @Volatile private var pausedAt = Long.MIN_VALUE / 2

    /** "Play music" with nothing to resume asks your music app for something after this long (§3.4). EST. */
    private const val RESUME_CHECK_MS = 1_500L

    /** Your words as music, when "Music by voice" is on (Settings › Actions › Music) and hands-free waits for no answer of its own. */
    fun route(q: String): MusicCommand? = try {
        val s = port.settings()
        if (!s.music || PanelHandsFree.waitsForAnswer) null
        else MusicRouter.route(q, MusicContext(playing = port.playingAsAsked(), robotPaused = port.clock() - pausedAt < MusicContext.RESUME_MS,
            appPicked = s.musicApp != null, carMode = port.carMode, wakePhrases = port.wakePhrases))
    } catch (t: Throwable) {
        RLog.w("music_route", t)
        null
    }

    suspend fun handle(replyId: String, cmd: MusicCommand, host: PanelRoutines.Host) {
        val l = host.langs(null, cmd.lang)
        try {
            when (cmd) {
                is MusicCommand.Key -> key(replyId, cmd.action, host, l)
                is MusicCommand.ByName -> byName(replyId, cmd.request, host, l)
                is MusicCommand.NowPlaying -> nowPlaying(replyId, host, l)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RLog.w("music", t)
            problem(replyId, host, l, EverydayStrings.didntAnswer())
        } finally {
            host.finish(replyId)
        }
    }

    /** A new conversation, or tests: Robot's own pause is forgotten, and nothing waits to be kept as a placeholder. */
    internal fun forget() {
        pausedAt = Long.MIN_VALUE / 2
        synchronized(nowReplies) { nowReplies.clear() }
    }

    // ------------------------------------------------------------------ the keys

    private suspend fun key(replyId: String, a: MusicAction, host: PanelRoutines.Host, l: Langs) {
        val playing = port.playingAsAsked()
        val car = port.carMode
        // §3.3: pause while Robot still holds the question's focus; let go before anything that starts the player.
        if (a != MusicAction.PAUSE) port.yieldFocus()
        val sent = port.key(a)
        VoiceLog.add(VoiceEvent.MUSIC_KEY, a.ordinal + 1, if (playing) 1 else 0, if (car) 1 else 0)
        // Car mode still logs 0.8's event beside the new one (§3.2).
        if (car) VoiceLog.add(VoiceEvent.CAR_MUSIC, a.ordinal + 1)
        if (!sent) return problem(replyId, host, l, EverydayStrings.didntAnswer())
        if (a == MusicAction.PAUSE) pausedAt = port.clock() else if (a == MusicAction.PLAY) pausedAt = Long.MIN_VALUE / 2
        line(replyId, host, l, when (a) {
            MusicAction.PLAY -> EverydayStrings.playing()
            MusicAction.PAUSE -> EverydayStrings.paused()
            MusicAction.NEXT -> EverydayStrings.next()
            MusicAction.PREVIOUS -> EverydayStrings.previous()
        })
        // "Play music" with nothing to resume: your music app plays something (§3.4's last row), when you picked one and the
        // locks leave it open.
        if (a == MusicAction.PLAY && !playing && playPinned() == MusicPlayCode.PLAYING) line(replyId, host, l, EverydayStrings.something(), cue = false)
    }

    /** Your music app plays something when nothing resumed, you picked one and the locks leave it open; null when it wasn't asked. */
    private suspend fun playPinned(): MusicPlayCode? {
        val app = port.settings().musicApp ?: return null
        port.wait(RESUME_CHECK_MS)
        if (port.playingNow() || port.check(app) != MusicApps.PinNow.OK || (port.phoneLockOn && !port.lockAllows(app.pkg))) return null
        // The stream can read silent while the app plays (Do Not Disturb, muted): its queue isn't replaced.
        if (port.reported(app)) return null
        val code = port.play(app, PlayRequest("")) {}
        VoiceLog.add(VoiceEvent.MUSIC_PLAY, 1, code.id, 1)
        return code
    }

    // ------------------------------------------------------------------ car mode's music tile

    /**
     * The car screen's play/pause, next and previous (docs/pm/handoff-car-media-maps.md): the same media keys as "pause"
     * and "next", which Android gives the app that played last; Robot holds no session. Play with nothing to resume plays
     * your music app, as "play music" does. False when Android took no key.
     */
    suspend fun carKey(a: MusicAction): Boolean {
        val playing = port.playingAsAsked()
        if (a != MusicAction.PAUSE) port.yieldFocus()
        val sent = port.key(a)
        VoiceLog.add(VoiceEvent.CAR_MUSIC, a.ordinal + 1)
        if (!sent) return false
        if (a == MusicAction.PAUSE) pausedAt = port.clock() else if (a == MusicAction.PLAY) pausedAt = Long.MIN_VALUE / 2
        if (a == MusicAction.PLAY && !playing) playPinned()
        return true
    }

    /**
     * The tile's Play or Pause: Pause when the app it shows [reported] playing, whatever the stream says (Do Not Disturb or
     * a muted volume may hide it, and Pause must stay in reach); or when the music stream plays, so a state that comes late
     * or never moves doesn't hide Pause either.
     */
    fun carPlaying(reported: Boolean): Boolean = reported || try { port.playingNow() } catch (_: Throwable) { false }

    /** Whether car mode's tile may name [pkg]: not under a phone lock that closes it, nor when that can't be told. */
    fun carMayShow(pkg: String): Boolean = try { !port.phoneLockOn || port.lockAllows(pkg) } catch (_: Throwable) { false }

    /** Whether your pinned music app says it's playing, from its own media service only; false under a lock that closes it. */
    suspend fun carReported(): Boolean {
        val app = port.settings().musicApp ?: return false
        if (port.phoneLockOn && !port.lockAllows(app.pkg)) return false
        return try {
            port.reported(app)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { false }
    }

    /** Your pinned music app's song, from its own media service only (as "what's playing"); none under a lock that closes it. */
    suspend fun carSong(): MusicDoor.Now? {
        val app = port.settings().musicApp ?: return null
        if (port.phoneLockOn && !port.lockAllows(app.pkg)) return null
        return try { port.carNow(app).second } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------------ play by name

    private suspend fun byName(replyId: String, r: PlayRequest, host: PanelRoutines.Host, l: Langs) {
        val s = port.settings()
        var app = s.musicApp
        if (app != null) when (port.check(app)) {
            MusicApps.PinNow.OK -> {}
            // Uninstalled: the card again.
            MusicApps.PinNow.GONE -> {
                VoiceLog.add(VoiceEvent.MUSIC_APP, MusicAppCode.GONE)
                app = null
            }
            // Replaced by an app signed by someone else: never bound, and never picked again for you.
            MusicApps.PinNow.REPLACED -> {
                VoiceLog.add(VoiceEvent.MUSIC_APP, MusicAppCode.REPLACED)
                VoiceLog.add(VoiceEvent.MUSIC_PLAY, 1, MusicPlayCode.REPLACED.id, r.focus.ordinal + 1)
                return problem(replyId, host, l, replaced())
            }
        }
        val picked = app ?: pick(replyId, host, l, changed = s.musicApp != null)
        if (picked == null) {
            VoiceLog.add(VoiceEvent.MUSIC_PLAY, 1, MusicPlayCode.NOT_PICKED.id, r.focus.ordinal + 1)
            return
        }
        // §3.5: during a phone lock, only when the locks leave your music app open.
        if (port.phoneLockOn && !port.lockAllows(picked.pkg)) {
            VoiceLog.add(VoiceEvent.MUSIC_PLAY, 1, MusicPlayCode.CLOSED_BY_LOCK.id, r.focus.ordinal + 1)
            return problem(replyId, host, l, EverydayStrings.closedDuringLock())
        }
        // Door 1. The question's focus goes once the app accepted Robot, just before the request (§3.3).
        val code = port.play(picked, r) { port.yieldFocus() }
        VoiceLog.add(VoiceEvent.MUSIC_PLAY, 1, code.id, r.focus.ordinal + 1)
        when (code) {
            MusicPlayCode.PLAYING -> {
                pausedAt = Long.MIN_VALUE / 2
                line(replyId, host, l, if (r.query.isEmpty()) EverydayStrings.something() else EverydayStrings.byName("what" to r.said))
            }
            // DOOR 2 (C7, §3.4): here the play-from-search screen would be tried, a built-in IntentSpec with string extras
            // only, to the pinned package, from the visible panel only and never locked ("Unlock to play that in your music
            // app."). Until it merges, Robot says the app doesn't take its requests.
            MusicPlayCode.NO_SERVICE, MusicPlayCode.REFUSED, MusicPlayCode.NO_SEARCH -> problem(replyId, host, l, EverydayStrings.noRequests())
            MusicPlayCode.REPLACED -> problem(replyId, host, l, replaced())
            else -> problem(replyId, host, l, EverydayStrings.didntAnswer())
        }
    }

    /**
     * The card that asks which app is your music app (Q1: a tap, once). Never locked or during a phone lock (§3.5). Null:
     * none picked (the reply says why).
     */
    private suspend fun pick(replyId: String, host: PanelRoutines.Host, l: Langs, changed: Boolean): MusicApp? {
        if (host.locked) {
            VoiceLog.add(VoiceEvent.MUSIC_APP, MusicAppCode.LOCKED)
            problem(replyId, host, l, EverydayStrings.pickLocked())
            return null
        }
        if (port.phoneLockOn) {
            VoiceLog.add(VoiceEvent.MUSIC_APP, MusicAppCode.PHONE_LOCK)
            problem(replyId, host, l, EverydayStrings.pickDuringLock())
            return null
        }
        val apps = withContext(Dispatchers.IO) { port.apps() }
        if (apps.isEmpty()) {
            VoiceLog.add(VoiceEvent.MUSIC_APP, MusicAppCode.NONE_FOUND)
            problem(replyId, host, l, EverydayStrings.pickNone())
            return null
        }
        val id = "music-pick-" + UUID.randomUUID() // l10n: not text (a card id)
        val title = EverydayStrings.pickTitle().render(l)
        val detail = EverydayStrings.pickDetail("page" to UiStrings.inSettings(UiStrings.actions, EverydayStrings.page)).render(l)
        val tap = spoken(EverydayStrings.pickTap(), l)
        host.text(replyId, title)
        host.say(tap)
        // Each app a button of its own; only a tap answers (a spoken yes would be the first app).
        val answer = host.pick(replyId, Card(id, null, title, ActionStatus.PENDING, detail,
            choices = apps.map { Choice(it.label, Action.OpenApp(it.label, it.pkg)) }), tap)
        val chosen = apps.getOrNull(answer)
        if (chosen == null) {
            VoiceLog.add(VoiceEvent.MUSIC_APP, MusicAppCode.CANCELLED, apps.size)
            host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, EverydayStrings.notPicked().render(l)))
            host.text(replyId, EverydayStrings.notPicked().render(l))
            return null
        }
        // The phone may have locked while the card waited.
        if (host.locked) {
            host.show(replyId, Card(id, null, title, ActionStatus.NEEDS_UNLOCK, EverydayStrings.pickLocked().render(l)))
            host.text(replyId, EverydayStrings.pickLocked().render(l))
            return null
        }
        val pin = withContext(Dispatchers.IO) { port.pin(chosen) }
        if (pin == null) {
            host.show(replyId, Card(id, null, title, ActionStatus.FAILED, EverydayStrings.didntAnswer().render(l)))
            problem(replyId, host, l, EverydayStrings.didntAnswer())
            return null
        }
        withContext(Dispatchers.IO) { port.save(pin) }
        VoiceLog.add(VoiceEvent.MUSIC_APP, if (changed) MusicAppCode.CHANGED else MusicAppCode.PICKED, apps.size)
        host.show(replyId, Card(id, null, title, ActionStatus.DONE, EverydayStrings.picked("label" to pin.label).render(l)))
        return pin
    }

    // ------------------------------------------------------------------ what's playing

    private suspend fun nowPlaying(replyId: String, host: PanelRoutines.Host, l: Langs) {
        val app = port.settings().musicApp
        if (app == null) {
            VoiceLog.add(VoiceEvent.MUSIC_NOW, MusicNowCode.NO_APP)
            return problem(replyId, host, l, EverydayStrings.cantSee())
        }
        val (code, now) = port.now(app)
        if (code == MusicPlayCode.REPLACED) {
            VoiceLog.add(VoiceEvent.MUSIC_NOW, MusicNowCode.REPLACED)
            return problem(replyId, host, l, replaced())
        }
        if (now == null) {
            VoiceLog.add(VoiceEvent.MUSIC_NOW, if (code == MusicPlayCode.PLAYING) MusicNowCode.NOTHING else MusicNowCode.NOT_REACHED)
            return problem(replyId, host, l, EverydayStrings.cantSee())
        }
        // The song's tags are content: the reply is quoted, and History keeps a placeholder (§3.7).
        host.quoted(replyId)
        synchronized(nowReplies) { nowReplies += replyId }
        val p = if (now.artist != null) EverydayStrings.nowPlaying("title" to now.title, "artist" to now.artist) else EverydayStrings.nowTitle("title" to now.title)
        host.text(replyId, p.render(l))
        host.say(spoken(p, l))
        VoiceLog.add(VoiceEvent.MUSIC_NOW, MusicNowCode.FOUND)
    }

    /** Replies that said what was playing: History keeps [EverydayStrings.saidWhatPlayed] in their place. */
    private val nowReplies = HashSet<String>()

    /** [t] as History and the live conversation keep it: what was playing only as its placeholder, in [l] (PanelController.finishReply). */
    fun forHistory(t: ChatTurn, l: Langs): ChatTurn =
        if (synchronized(nowReplies) { nowReplies.remove(t.id) }) t.copy(text = EverydayStrings.saidWhatPlayed().render(l)) else t

    // ------------------------------------------------------------------ the lines

    private fun replaced(): Phrase = EverydayStrings.replaced("page" to UiStrings.inSettings(UiStrings.actions, EverydayStrings.page))

    /** A line on screen and the cue, never speech (§3.3, D3). */
    private fun line(replyId: String, host: PanelRoutines.Host, l: Langs, p: Phrase, cue: Boolean = true) {
        host.text(replyId, p.render(l))
        if (cue && host.speak) port.cue()
    }

    /** Something went wrong: on screen and said. */
    private fun problem(replyId: String, host: PanelRoutines.Host, l: Langs, p: Phrase) {
        host.text(replyId, p.render(l))
        host.say(spoken(p, l))
    }
}
