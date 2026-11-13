// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.headset.RouteDecision

// Pure. What car mode learned from a real drive (docs/pm/handoff-car-mode-drive-fixes.md): album art, music or podcasts,
// the way back to the map and what Recents may show.

/**
 * Where a session's picture comes from. Bitmaps first, in the order album art, art, display icon (the caller hands them in
 * that order), then a content: address the app lets Robot open. Never http(s): Robot fetches nothing from the internet for
 * this. The art is shown on the car screen only: never logged, saved, cached on disk or sent, as the title and artist are.
 */
object CarArt {
    /** The longest side the tile keeps, in pixels. */
    const val MAX_SIDE = 512

    /** The first address Robot may open, or null. */
    fun address(uris: List<String?>): String? = uris.firstOrNull { it != null && it.startsWith("content://", ignoreCase = true) }

    /** The first picture that exists, else what [fromAddress] finds for the first address allowed. */
    fun <T : Any> pick(bitmaps: List<T?>, uris: List<String?>, fromAddress: (String) -> T?): T? =
        bitmaps.firstOrNull { it != null } ?: address(uris)?.let(fromAddress)

    /** [w] by [h] brought down to at most [MAX_SIDE] on its longest side, at least 1; a smaller picture stays as it is. */
    fun scaled(w: Int, h: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val side = maxOf(w, h)
        if (side <= max) return w to h
        return (w * max / side).coerceAtLeast(1) to (h * max / side).coerceAtLeast(1)
    }
}

/** The one picture in memory: kept while the song stays, dropped when it changes or nothing shows. */
class CarArtHold<T : Any> {
    private var key: Any? = null
    private var art: T? = null

    /** The picture for the song named [song] (null: none shows, and the one held goes). [load] runs only for a new song. */
    fun next(song: Any?, load: () -> T?): T? {
        if (song == null) { drop(); return null }
        // A song with no picture yet asks again: many apps send the picture after the title.
        if (song != key || art == null) { art = load(); key = song }
        return art
    }

    fun drop() { key = null; art = null }
}

/** What a tap on "your music app" means: ask, open the one set, or offer the list. */
sealed class CarMusicChoice {
    data class Ask(val music: MusicApp, val podcast: MusicApp) : CarMusicChoice()
    data class Open(val app: MusicApp) : CarMusicChoice()
    data object ChooseOne : CarMusicChoice()

    companion object {
        /** From the apps still yours: both set asks "Music or podcasts?", one opens it, none offers the list. */
        fun of(music: MusicApp?, podcast: MusicApp?): CarMusicChoice = when {
            music != null && podcast != null && music.pkg != podcast.pkg -> Ask(music, podcast)
            music != null -> Open(music)
            podcast != null -> Open(podcast)
            else -> ChooseOne
        }

        /**
         * A tap on the tile's app name: null means open the app it names, with no question. That's what's playing, and any
         * other app's paused session (seen this drive); your own apps with nothing playing go as [of] says.
         */
        fun forTile(playing: Boolean, named: String?, music: MusicApp?, podcast: MusicApp?): CarMusicChoice? {
            if (playing) return null
            if (named != null && named != music?.pkg && named != podcast?.pkg) return null
            return of(music, podcast)
        }
    }
}

/** The app Robot last put beside itself this drive, and whether Robot's half needs a way back to the map. */
object CarBeside {
    sealed class Last {
        data object Map : Last()
        data class App(val pkg: String) : Last()
        /** An app Robot put beside itself that it can't name (an open with no package): the Maps button brings the map back. */
        data object Unknown : Last()
    }

    /**
     * fix-car-132: what a tap's open of your app beside Robot records: the app once it opened; [Last.Unknown] when it didn't
     * open, so the Maps button stops offering a swap Robot can't be sure of; nothing when a lock refused it ([blocked]).
     */
    fun appOpen(pkg: String, opened: Boolean, blocked: Boolean, last: Last?): Last? = when {
        opened -> Last.App(pkg)
        blocked -> null
        // fix-car-133: only the map was beside, and Robot can't be sure it still is; another app beside stays recorded.
        last == Last.Map -> Last.Unknown
        else -> null
    }

    /** What an open beside Robot records: your maps app as [Last.Map], an app by its package, or [Last.Unknown] with none. */
    fun opened(pkg: String?, mapsPkg: String?): Last = when {
        pkg == null -> Last.Unknown
        pkg == mapsPkg -> Last.Map
        else -> Last.App(pkg)
    }

    /** [FOURTH]: the fourth button becomes Map, while it's set to your music app. [INFO]: a Map button joins the information area. */
    enum class Back { NONE, FOURTH, INFO }

    /**
     * Nothing while the last app beside Robot is your maps app ([mapsPkg]), while nothing was opened, while your fourth
     * button is Maps already, or while the large panel is your map and in view.
     */
    fun back(last: Last?, mapsPkg: String?, fourth: CarFourth, large: CarLarge, beside: Boolean): Back = when {
        last !is Last.App || last.pkg == mapsPkg -> Back.NONE
        fourth == CarFourth.Action(CarAction.MAPS) -> Back.NONE
        fourth is CarFourth.App && fourth.app.pkg == mapsPkg -> Back.NONE
        !beside && large == CarLarge.MAP -> Back.NONE
        fourth == CarFourth.Action(CarAction.MUSIC_APP) -> Back.FOURTH
        else -> Back.INFO
    }

    /** What a tap on the fourth button does: bring your map back, navigate home, or open what [Follow] names (a sheet for Ask). */
    sealed class Tap {
        data object Maps : Tap()
        data object Home : Tap()
        data class Follow(val choice: CarMusicChoice) : Tap()
    }

    /** The fourth button's tap, from what it shows: [back] and [swap] as the screen has them, [music] from CarMusicChoice.of. */
    fun tap(fourth: CarFourth, back: Back, swap: CarMusicChoice?, music: CarMusicChoice): Tap = when (fourth) {
        is CarFourth.App -> Tap.Follow(CarMusicChoice.Open(fourth.app))
        is CarFourth.Action -> when (fourth.action) {
            CarAction.MUSIC_APP -> if (back == Back.FOURTH) Tap.Maps else Tap.Follow(music)
            CarAction.MAPS -> swap?.let { Tap.Follow(it) } ?: Tap.Maps
            CarAction.NAVIGATE_HOME -> Tap.Home
        }
    }

    /**
     * Both your music and podcast apps set ([music] is Ask): the swap opens the one on the media tile, playing or paused
     * ([tile]), else the one Robot last put beside itself this drive ([recent], oldest first). It asks only when neither
     * was used this drive: a swap is one tap while driving.
     */
    fun pick(music: CarMusicChoice, tile: String?, recent: List<String>): CarMusicChoice {
        if (music !is CarMusicChoice.Ask) return music
        val two = listOf(music.music, music.podcast)
        val used = two.firstOrNull { it.pkg == tile } ?: recent.lastOrNull { r -> two.any { it.pkg == r } }?.let { r -> two.first { it.pkg == r } }
        return used?.let { CarMusicChoice.Open(it) } ?: music
    }

    /**
     * The swap on the Maps button beside the mic (the fourth button set to Maps): while your map is the app beside Robot,
     * the button opens your music app there instead ([music], from CarMusicChoice.of), and while your music app or any
     * other app is there, it brings the map back (null). Null too whenever Robot doesn't know your map is there (nothing
     * opened this drive, or an app it can't name), while Robot isn't beside an app, with no maps app, or with no music app
     * set: the button does what it always did, so a tap always has a sure result.
     */
    fun swap(last: Last?, mapsPkg: String?, fourth: CarFourth, beside: Boolean, music: CarMusicChoice): CarMusicChoice? = when {
        fourth != CarFourth.Action(CarAction.MAPS) || mapsPkg == null || !beside -> null
        last != Last.Map && !(last is Last.App && last.pkg == mapsPkg) -> null
        music == CarMusicChoice.ChooseOne -> null
        else -> music
    }
}

/** The car screen's card in Recents. */
/**
 * The turn's lines in the information panel: the words last worth keeping stay a few seconds after the turn ends.
 */
class CarLineHold<T : Any>(private val holdMs: Long) {
    private var kept: T? = null
    private var until = 0L

    /** What to show now: [live], or the last line [keep] said was a reply, until its time is up. */
    fun next(live: T?, keep: Boolean, nowMs: Long): T? {
        if (live != null) {
            kept = live.takeIf { keep }
            until = nowMs + holdMs
            return live
        }
        if (nowMs >= until) kept = null
        return kept
    }

    fun drop() { kept = null }
}

object CarTurn {
    /** Whether a show opens no panel: car mode on, the car screen in front or split, opened by Talk, the car's button or "hey robot" ([fromCar]), and Android has let this work this drive. */
    /**
     * fix-car-stay: with the car screen in front or split, every show you or Robot started has no panel, and the car screen's
     * Robot pane shows the turn: the assist gesture, the power button, Talk, the car's button, "hey robot", a reminder or an
     * answer arriving, a message read out. [trusted]: Robot's own show or the system's, never another app's showAssist (that
     * one always opens the panel, so nothing listens unseen). [windowNeeded]: Android refused a show with no window this drive.
     */
    fun quiet(on: Boolean, screenUp: Boolean, trusted: Boolean, windowNeeded: Boolean): Boolean = on && screenUp && trusted && !windowNeeded

    /**
     * fix-car-stay-c: whether a [trusted] show may go without a panel: not the system's own ([systemShow]: the power button,
     * the assist gesture) on a [locked] phone, which never listens without a tap, so its panel shows and waits for one.
     */
    fun quietAllowed(trusted: Boolean, systemShow: Boolean, locked: Boolean): Boolean = trusted && !(systemShow && locked)

    /** A show with no panel that no headset session hears ([handsFree] false), from a voice way in ([voice]): the phone's microphone listens. */
    fun listensQuietly(quiet: Boolean, handsFree: Boolean, voice: Boolean): Boolean = quiet && !handsFree && voice

    /**
     * Car mode is on, with no car and no headset connected ([decision]), and Talk, the car's button or "hey robot" asked ([fromCar]):
     * a session has nothing to route, so the panel opens on the phone, as any other way in, and listens. It used to begin a
     * session that ended at once, which left "hey robot" with a panel that didn't listen, and closed Talk's panel as it opened.
     */
    fun listensOnPhone(on: Boolean, fromCar: Boolean, decision: RouteDecision): Boolean = on && fromCar && decision == RouteDecision.NoHeadset
}

object CarTurnEnd {
    /**
     * A show with no panel is over when no headset session holds it, the panel is idle, nothing is being said and Robot waits for
     * nothing ([waits]: a card for a yes, a question asked, a listen due after Robot speaks): it closes, and the mic with it.
     */
    fun quietDone(quietTurn: Boolean, handsFree: Boolean, idle: Boolean, speaking: Boolean, waits: Boolean): Boolean =
        quietTurn && !handsFree && idle && !speaking && !waits

    /** fix-car-stay-d: a session's end waits for Robot's words and for a reply in its 3 seconds or on its way, [tries] polls at most. */
    fun closeWaits(speaking: Boolean, sending: Boolean, tries: Int): Boolean = (speaking || sending) && tries > 0

    /**
     * fix-car-stay-f: Robot's own close of a turn waits one more poll? [words]: for Robot's words too (not the car screen's
     * talk, which stops them); [replyHeld]: a reply held this close, so a question said in its 3 seconds (the turn [busy]) is
     * answered before the panel goes. fix-car-stay-g: once a reply held it, the car screen's talk too waits for the reply's
     * answer line ("Sent." or "Didn't send…") to be said. fix-car-132: not an answer to a question said in its 3 seconds
     * (the turn [busy]): the talk stops that one.
     */
    fun hideWaits(words: Boolean, speaking: Boolean, sending: Boolean, busy: Boolean, replyHeld: Boolean, tries: Int): Boolean =
        closeWaits((words || (replyHeld && !busy)) && speaking, sending || (words && replyHeld && busy), tries)

    /** fix-car-stay-c: 2 polls, 4 s: a listen comes 350 ms after Robot stops talking. EST. */
    const val LISTEN_HOLD_MAX = 2

    /**
     * fix-car-stay-c: the polls in a row [held] only by a listen Robot said would come ([coming]): only polls that would
     * otherwise close count (idle, nothing said), so a long answer read aloud doesn't use them up before its listen.
     */
    fun listenHeld(held: Int, coming: Boolean, idle: Boolean, speaking: Boolean): Int = if (coming && idle && !speaking) held + 1 else 0

    /** The listen to come still holds the turn; by the third idle poll it isn't coming (a flag left set). */
    fun listenHolds(held: Int): Boolean = held in 1..LISTEN_HOLD_MAX

    /** The question is over: a turn with no window always closes (else new messages wait for it all drive); with the car screen up, any panel does too. */
    fun closes(quietTurn: Boolean, on: Boolean, screenUp: Boolean): Boolean = quietTurn || (on && screenUp)

    /**
     * Whether words cut at the cap can wait in the panel's text box: there is a window, and it stays. A session's end closes the
     * panel when [closes] says so ([session]: the question came through one), so with the car screen up nothing promises a box,
     * and on a locked phone the next show would start a new conversation without them: the driver is told to say it again.
     */
    fun boxStays(quietTurn: Boolean, on: Boolean, screenUp: Boolean, session: Boolean): Boolean =
        !quietTurn && !(session && closes(quietTurn, on, screenUp))
}

object CarNotice {
    /** The channel car mode's notice lives on, and the action that ends it: both processes' one way to see and end car mode. */
    const val CHANNEL = "car_mode"
    const val ACTION_END = "app.heyrobot.car.END"
    /** 1.2.4: the app's Car mode button and shortcut: car mode by hand, or its screen when it's on already. */
    const val ACTION_OPEN = "app.heyrobot.car.OPEN"

    /** Car mode is on: its ongoing notice; the offer to start lives on the same channel and isn't ongoing. */
    fun running(channel: String?, ongoing: Boolean): Boolean = channel == CHANNEL && ongoing
}

/** Whether the bottom screen's widget gives way to the media player, with no swap. */
object CarWidgetNow {
    /** Phone lock closes the widget's app: on, and the app on its list. */
    fun closes(lockOn: Boolean, closed: Set<String>, pkg: String): Boolean = lockOn && pkg in closed

    /** Over the lock screen, while Phone lock closes its app, or when its id no longer holds the widget you picked ([made] false). */
    fun off(locked: Boolean, closes: Boolean, made: Boolean): Boolean = locked || closes || !made
}

object CarWidgetSize {
    /** The slot's [w] by [h] pixels in dp, as a widget's size options want them; null while it has no size yet. */
    fun dp(w: Int, h: Int, density: Float): Pair<Float, Float>? = if (w <= 0 || h <= 0 || density <= 0f) null else w / density to h / density
}

object CarRecents {
    /** FLAG_SECURE on the car screen: while a private answer is on show, and until its line has left the screen. */
    fun secure(privateTurn: Boolean, showsTurn: Boolean, was: Boolean): Boolean = privateTurn || (was && showsTurn)

    /**
     * No picture of the screen on the card while it shows a message's words or what you and Robot said, or in the trusted
     * session over the lock screen, so a glance at Recents never shows them. Nor while the bottom screen shows another app's
     * widget ([showsWidget]): its mail or calendar would stay on the card after Phone lock closes that app.
     */
    fun picture(showsMessage: Boolean, showsTurn: Boolean, trusted: Boolean, showsWidget: Boolean): Boolean =
        !showsMessage && !showsTurn && !trusted && !showsWidget
}

/** fix-car-stay: an app Robot opens goes beside the car screen; when Android puts it over the car screen instead, the car screen comes back. */
object CarSplit {
    /** How soon after an app opened beside it the car screen going out of sight means Android didn't split. EST. */
    const val CHECK_MS = 3_000L

    /** The car screen stopped, not in split screen and not closing, [sinceOpen] ms after it opened an app beside itself. */
    fun covered(multiWindow: Boolean, finishing: Boolean, sinceOpen: Long): Boolean = !multiWindow && !finishing && sinceOpen in 0 until CHECK_MS
}
