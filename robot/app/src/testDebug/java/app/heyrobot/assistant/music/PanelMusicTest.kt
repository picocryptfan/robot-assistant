// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.music

import android.content.ComponentName
import android.media.session.PlaybackState
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.music.MusicCommand
import app.heyrobot.core.music.MusicPlayCode
import app.heyrobot.core.music.PlayFocus
import app.heyrobot.core.music.PlayRequest
import app.heyrobot.core.output.MusicAction
import app.heyrobot.sys.MusicApps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The music in the panel (PanelMusic, docs/plans/everyday-set-plan.md §3.3 to §3.5): the order of the keys and the
 * question's focus with a fake player ("pause" while Robot holds the focus, everything that starts the player after Robot
 * let go), the bare words, the card the first time you play by name and your pick saved with its signer, the signer
 * refused, the lock rules, door 1's answers and the line where door 2 will go, what's playing as quoted content with
 * History's placeholder, and lines with a cue, never speech, unless something went wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelMusicTest {
    private val app = MusicApp("org.example.music", "a".repeat(64), "Music Player")
    private val other = MusicApps.Found("org.example.other", "Other Player", ComponentName("org.example.other", "org.example.other.Service"))
    private val found = MusicApps.Found(app.pkg, app.label, ComponentName(app.pkg, "org.example.music.Service"))

    /** What the phone was asked, in order: "yield", "key PAUSE", "play <query>", "cue", "save <pkg>"… */
    private val did = mutableListOf<String>()

    private inner class FakePort : PanelMusic.Port {
        var settings = EverydaySettings()
        var playingAsked = false
        var playingNow = false
        var car = false
        var keyWorks = true
        var lockOn = false
        var lockAllows = setOf<String>()
        var apps = listOf(found, other)
        var pinNow = MusicApps.PinNow.OK
        var door = MusicPlayCode.PLAYING
        var now: MusicDoor.Now? = MusicDoor.Now("Creep", "Radiohead")
        var reported = false
        var clock = 1_000_000L
        override fun settings() = settings
        override fun save(app: MusicApp?) { did += "save ${app?.pkg}"; settings = settings.copy(musicApp = app) }
        override val carMode get() = car
        override val wakePhrases get() = emptyList<String>()
        override fun playingAsAsked() = playingAsked
        override fun playingNow() = playingNow
        override fun key(a: MusicAction): Boolean { did += "key $a"; return keyWorks }
        override fun yieldFocus() { did += "yield" }
        override fun cue() { did += "cue" }
        override val phoneLockOn get() = lockOn
        override fun lockAllows(pkg: String) = pkg in lockAllows
        override fun apps() = apps
        override fun pin(f: MusicApps.Found) = MusicApp(f.pkg, "b".repeat(64), f.label)
        override fun check(app: MusicApp) = pinNow
        override suspend fun play(app: MusicApp, r: PlayRequest, before: () -> Unit): MusicPlayCode {
            before()
            did += "play ${app.pkg} \"${r.query}\" ${r.focus}"
            if (door == MusicPlayCode.PLAYING) playingNow = true
            return door
        }
        override suspend fun now(app: MusicApp) = (if (pinNow == MusicApps.PinNow.REPLACED) MusicPlayCode.REPLACED else MusicPlayCode.PLAYING) to now
        override suspend fun reported(app: MusicApp) = reported
        override suspend fun wait(ms: Long) { clock += ms }
        override fun clock() = clock
    }

    private inner class FakeHost(override val locked: Boolean = false, override val speak: Boolean = true, val l: Lang = Lang.EN) : PanelRoutines.Host {
        var text = ""
        val said = mutableListOf<String>()
        val cards = mutableListOf<Card>()
        var answer = 0
        var quoted = false
        var finished = false
        override fun text(replyId: String, text: String) { this.text = text }
        override fun append(replyId: String, text: String) { this.text += "\n" + text }
        override fun say(sentence: String, personal: Boolean) { said += sentence; did += "say" }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?) = pick(replyId, card, tapOnly) >= 0
        override suspend fun pick(replyId: String, card: Card, tapOnly: String?): Int {
            cards += card
            assertTrue("only a tap answers the picker", tapOnly != null)
            return answer
        }
        override fun show(replyId: String, card: Card) { cards += card }
        override suspend fun tool(replyId: String, step: RoutineStep.Tool) = null
        override fun finish(replyId: String) { finished = true }
        override fun quoted(replyId: String) { quoted = true }
        override suspend fun leave() {}
        override fun langs(said: String?, router: Lang?): Langs = if ((router ?: l) == Lang.FR) Langs(Lang.FR, Lang.FR, Lang.FR) else Langs.EN
    }

    private val port = FakePort()

    @Before fun setUp() {
        PanelMusic.port = port
        PanelMusic.forget()
    }

    @After fun tearDown() = PanelMusic.forget()

    private fun run(cmd: MusicCommand, host: FakeHost = FakeHost()): FakeHost = host.also { runBlocking { PanelMusic.handle("r1", cmd, it) } }

    private fun logged(e: VoiceEvent) = VoiceLog.entries().filter { it.event == e }

    // ------------------------------------------------------------------ the keys and the focus (§3.3)

    @Test fun pauseGoesWhileRobotHoldsTheFocusAndPlayLetsGoFirst() {
        port.playingAsked = true
        val h = run(MusicCommand.Key(MusicAction.PAUSE, Lang.EN))
        assertEquals(listOf("key PAUSE", "cue"), did)
        assertEquals("♪ Paused", h.text)
        assertTrue(h.said.isEmpty())
        assertTrue(h.finished)
        did.clear()
        for (a in listOf(MusicAction.NEXT, MusicAction.PREVIOUS)) {
            run(MusicCommand.Key(a, Lang.EN))
            assertEquals(listOf("yield", "key $a", "cue"), did)
            did.clear()
        }
        val e = logged(VoiceEvent.MUSIC_KEY).first()
        assertEquals(MusicAction.PREVIOUS.ordinal + 1, e.a)
        assertEquals(1, e.b)
    }

    @Test fun theLinesAndTheCueInBothLanguages() {
        assertEquals("♪ Next song", run(MusicCommand.Key(MusicAction.NEXT, Lang.EN)).text)
        assertEquals("♪ Chanson suivante", run(MusicCommand.Key(MusicAction.NEXT, Lang.FR)).text)
        assertEquals("♪ En pause", run(MusicCommand.Key(MusicAction.PAUSE, null), FakeHost(l = Lang.FR)).text)
        // Replies not spoken: the line only, no cue either.
        did.clear()
        run(MusicCommand.Key(MusicAction.NEXT, Lang.EN), FakeHost(speak = false))
        assertEquals(listOf("yield", "key NEXT"), did)
    }

    @Test fun aKeyAndroidRefusedIsSaid() {
        port.keyWorks = false
        val h = run(MusicCommand.Key(MusicAction.NEXT, Lang.EN))
        assertEquals("Your music app didn't answer.", h.text)
        assertEquals(1, h.said.size)
    }

    @Test fun bareWordsFollowWhatPlayedAndRobotsOwnPause() {
        port.settings = EverydaySettings(musicApp = app)
        // Nothing playing as you asked: a bare "pause" isn't the music's.
        assertNull(PanelMusic.route("pause"))
        port.playingAsked = true
        assertEquals(MusicCommand.Key(MusicAction.PAUSE, null), PanelMusic.route("pause"))
        // A bare "play" only after Robot's own pause, for 10 minutes.
        assertNull(PanelMusic.route("play"))
        run(MusicCommand.Key(MusicAction.PAUSE, Lang.EN))
        assertEquals(MusicCommand.Key(MusicAction.PLAY, null), PanelMusic.route("play"))
        port.clock += 10 * 60_000L
        assertNull(PanelMusic.route("play"))
        // Music by voice off: nothing at all.
        port.settings = port.settings.copy(music = false)
        assertNull(PanelMusic.route("pause the music"))
        assertNull(PanelMusic.route("play Radiohead"))
    }

    @Test fun carModeLogsItsOwnEventBesideTheNewOne() {
        port.car = true
        run(MusicCommand.Key(MusicAction.NEXT, Lang.EN))
        assertEquals(MusicAction.NEXT.ordinal + 1, logged(VoiceEvent.CAR_MUSIC).first().a)
        assertEquals(1, logged(VoiceEvent.MUSIC_KEY).first().c)
    }

    @Test fun playMusicWithNothingToResumeAsksYourMusicAppForSomething() {
        port.settings = EverydaySettings(musicApp = app)
        val h = run(MusicCommand.Key(MusicAction.PLAY, Lang.EN))
        assertEquals(listOf("yield", "key PLAY", "cue", "play org.example.music \"\" ANY"), did)
        assertEquals("♪ Something in your music app", h.text)
        // The app says it's playing though the stream reads silent (Do Not Disturb, muted): its queue isn't replaced.
        did.clear()
        port.playingNow = false
        port.reported = true
        run(MusicCommand.Key(MusicAction.PLAY, Lang.EN))
        assertEquals(listOf("yield", "key PLAY", "cue"), did)
        port.reported = false
        // Something came back after the key: nothing more.
        did.clear()
        port.playingNow = true
        run(MusicCommand.Key(MusicAction.PLAY, Lang.EN))
        assertEquals(listOf("yield", "key PLAY", "cue"), did)
    }

    // ------------------------------------------------------------------ play by name (§3.4)

    private val radiohead = MusicCommand.ByName(PlayRequest("Radiohead"), Lang.EN)

    @Test fun theFirstPlayByNameAsksWhichAppAndRemembersIt() {
        val h = FakeHost().apply { answer = 1 }
        run(radiohead, h)
        val card = h.cards.first()
        assertEquals("Pick your music app", card.summary)
        assertEquals(listOf("Music Player", "Other Player"), card.choices.map { it.label })
        assertEquals("Robot plays what you ask for by name in the app you pick. You can change it in Settings › Actions › Music.", card.detail)
        // Your tap: the second app, pinned with its signer, then played, the focus let go just before the request.
        assertEquals(listOf("say", "save org.example.other", "yield", "play org.example.other \"Radiohead\" ANY", "cue"), did)
        assertEquals(MusicApp("org.example.other", "b".repeat(64), "Other Player"), port.settings.musicApp)
        assertEquals(ActionStatus.DONE, h.cards.last().status)
        assertEquals("♪ Radiohead in your music app", h.text)
        // The next time, no card.
        did.clear()
        val h2 = run(MusicCommand.ByName(PlayRequest("workout playlist", said = "my workout playlist"), Lang.EN))
        assertTrue(h2.cards.isEmpty())
        assertEquals(listOf("yield", "play org.example.other \"workout playlist\" ANY", "cue"), did)
        assertEquals("♪ my workout playlist in your music app", h2.text)
    }

    @Test fun cancellingThePickerPlaysNothing() {
        val h = FakeHost().apply { answer = -1 }
        run(radiohead, h)
        assertEquals(ActionStatus.DECLINED, h.cards.last().status)
        assertEquals("No music app picked. Nothing played.", h.text)
        assertNull(port.settings.musicApp)
        assertFalse(did.any { it.startsWith("play") || it.startsWith("save") })
    }

    @Test fun thePickerNeverShowsLockedOrDuringAPhoneLockOrWithNoApps() {
        val locked = run(radiohead, FakeHost(locked = true))
        assertEquals("Unlock to pick your music app.", locked.text)
        assertTrue(locked.cards.isEmpty())
        port.lockOn = true
        assertEquals("You can pick your music app once the phone lock is over.", run(radiohead).text)
        port.lockOn = false
        port.apps = emptyList()
        assertTrue(run(radiohead).text.startsWith("No music app on this phone takes requests from Robot."))
        assertFalse(did.any { it.startsWith("play") || it.startsWith("save") })
    }

    @Test fun anAppSignedBySomeoneElseIsNeverAskedOrPickedAgainForYou() {
        port.settings = EverydaySettings(musicApp = app)
        port.pinNow = MusicApps.PinNow.REPLACED
        val h = run(radiohead)
        assertEquals("Your music app was replaced by an app signed by someone else. Pick it again in Settings › Actions › Music.", h.text)
        assertEquals(1, h.said.size)
        assertTrue(h.cards.isEmpty())
        assertFalse(did.any { it.startsWith("play") || it == "yield" })
        assertEquals(app, port.settings.musicApp)
        // An app no longer installed: the card again.
        port.pinNow = MusicApps.PinNow.GONE
        val h2 = run(radiohead, FakeHost().apply { answer = 0 })
        assertEquals("Pick your music app", h2.cards.first().summary)
    }

    @Test fun lockRules() {
        port.settings = EverydaySettings(musicApp = app)
        // Locked: door 1 works (no screen opens).
        assertEquals("♪ Radiohead in your music app", run(radiohead, FakeHost(locked = true)).text)
        // A phone lock that doesn't leave your music app open: said; the keys still work.
        did.clear()
        port.lockOn = true
        val h = run(radiohead)
        assertEquals("Your music app is closed during this lock.", h.text)
        assertFalse(did.any { it.startsWith("play") })
        assertEquals("♪ Paused", run(MusicCommand.Key(MusicAction.PAUSE, Lang.EN)).text)
        port.lockAllows = setOf(app.pkg)
        assertEquals("♪ Radiohead in your music app", run(radiohead).text)
    }

    @Test fun carModesTileNamesOnlyWhatAPhoneLockLeavesOpen() {
        assertTrue(PanelMusic.carMayShow(app.pkg))
        port.lockOn = true
        assertFalse(PanelMusic.carMayShow(app.pkg))
        port.lockAllows = setOf(app.pkg)
        assertTrue(PanelMusic.carMayShow(app.pkg))
        assertFalse(PanelMusic.carMayShow("org.example.other"))
    }

    @Test fun whatDoor1SaysAndWhereDoor2WillGo() {
        port.settings = EverydaySettings(musicApp = app)
        for (code in listOf(MusicPlayCode.NO_SERVICE, MusicPlayCode.REFUSED, MusicPlayCode.NO_SEARCH)) {
            port.door = code
            val h = run(radiohead)
            assertEquals("Your music app doesn't take requests from Robot. Open it to play that.", h.text)
            assertEquals(1, h.said.size)
        }
        port.door = MusicPlayCode.NO_ANSWER
        assertEquals("Your music app didn't answer.", run(radiohead).text)
        val e = logged(VoiceEvent.MUSIC_PLAY).first()
        assertEquals(1, e.a)
        assertEquals(MusicPlayCode.NO_ANSWER.id, e.b)
        // Nothing of the request is in the log: codes only.
        val music = setOf(VoiceEvent.MUSIC_KEY, VoiceEvent.MUSIC_PLAY, VoiceEvent.MUSIC_APP, VoiceEvent.MUSIC_NOW)
        assertTrue(VoiceLog.entries().filter { it.event in music }.all { it.a in 0..20 && it.b in 0..20 && it.c in 0..20 })
    }

    @Test fun theRequestReachesTheDoorAsCodeBuiltIt() {
        port.settings = EverydaySettings(musicApp = app)
        run(MusicCommand.ByName(PlayRequest("the album Kid A", PlayFocus.ALBUM, album = "Kid A"), Lang.EN))
        assertTrue(did.contains("play org.example.music \"the album Kid A\" ALBUM"))
        assertEquals("♪ Lecture en cours", run(MusicCommand.Key(MusicAction.PLAY, Lang.FR)).text)
        assertEquals("♪ Kid A dans ton appli de musique", run(MusicCommand.ByName(PlayRequest("Kid A"), Lang.FR)).text)
    }

    // ------------------------------------------------------------------ what's playing (§3.4, §3.7)

    @Test fun whatsPlayingIsQuotedContentAndHistoryKeepsAPlaceholder() {
        port.settings = EverydaySettings(musicApp = app)
        val h = run(MusicCommand.NowPlaying(Lang.EN))
        assertEquals("Creep, by Radiohead.", h.text)
        assertEquals(listOf("Creep, by Radiohead."), h.said)
        assertTrue(h.quoted)
        val kept = PanelMusic.forHistory(ChatTurn("r1", TurnRole.ASSISTANT, h.text, 0L), Langs.EN)
        assertEquals("Robot said what was playing.", kept.text)
        // Only once, and only for that reply.
        assertEquals("other", PanelMusic.forHistory(ChatTurn("r1", TurnRole.ASSISTANT, "other", 0L), Langs.EN).text)
        port.now = MusicDoor.Now("Creep", null)
        assertEquals("Creep.", run(MusicCommand.NowPlaying(Lang.EN)).text)
    }

    @Test fun whatsPlayingWithoutAnAppOrAnswer() {
        assertEquals("Robot can't see what your music app is playing.", run(MusicCommand.NowPlaying(Lang.EN)).text)
        port.settings = EverydaySettings(musicApp = app)
        port.now = null
        val h = run(MusicCommand.NowPlaying(Lang.FR))
        assertEquals("Robot ne voit pas ce que ton appli de musique fait jouer.", h.text)
        assertFalse(h.quoted)
        port.now = MusicDoor.Now("Creep", "Radiohead")
        port.pinNow = MusicApps.PinNow.REPLACED
        assertTrue(run(MusicCommand.NowPlaying(Lang.EN)).text.startsWith("Your music app was replaced"))
    }

    // ------------------------------------------------------------------ car mode's Play or Pause

    @Test fun anAppReportingPlayingShowsPauseWhateverTheStreamSays() {
        // Audio focus lost, volume muted or Do Not Disturb: the stream can read silent while the app says it's playing.
        port.playingNow = false
        assertTrue(PanelMusic.carPlaying(reported = true))
        // The stream plays and the state is late or stuck: still Pause.
        port.playingNow = true
        assertTrue(PanelMusic.carPlaying(reported = false))
        // Neither: Play.
        port.playingNow = false
        assertFalse(PanelMusic.carPlaying(reported = false))
    }

    @Test fun whatCountsAsPlayingInAnAppsOwnState() {
        for (s in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)) assertTrue("$s", MusicDoor.isPlaying(s))
        for (s in listOf(null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED, PlaybackState.STATE_ERROR))
            assertFalse("$s", MusicDoor.isPlaying(s))
    }

    @Test fun thePinnedAppIsAskedOnlyWhileTheLockLeavesItOpen() = runBlocking {
        port.reported = true
        assertFalse(PanelMusic.carReported())
        port.settings = EverydaySettings(musicApp = app)
        assertTrue(PanelMusic.carReported())
        port.lockOn = true
        assertFalse(PanelMusic.carReported())
        port.lockAllows = setOf(app.pkg)
        assertTrue(PanelMusic.carReported())
    }
}
