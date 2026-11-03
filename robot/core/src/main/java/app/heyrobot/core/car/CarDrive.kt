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
    fun quiet(on: Boolean, screenUp: Boolean, fromCar: Boolean, windowNeeded: Boolean, route: Boolean = true): Boolean = on && screenUp && fromCar && !windowNeeded && route

    /**
     * Car mode is on, with no car and no headset connected ([decision]), and Talk, the car's button or "hey robot" asked ([fromCar]):
     * a session has nothing to route, so the panel opens on the phone, as any other way in, and listens. It used to begin a
     * session that ended at once, which left "hey robot" with a panel that didn't listen, and closed Talk's panel as it opened.
     */
    fun listensOnPhone(on: Boolean, fromCar: Boolean, decision: RouteDecision): Boolean = on && fromCar && decision == RouteDecision.NoHeadset
}

object CarTurnEnd {
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

    /** Car mode is on: its ongoing notice; the offer to start lives on the same channel and isn't ongoing. */
    fun running(channel: String?, ongoing: Boolean): Boolean = channel == CHANNEL && ongoing
}

object CarRecents {
    /** FLAG_SECURE on the car screen: while a private answer is on show, and until its line has left the screen. */
    fun secure(privateTurn: Boolean, showsTurn: Boolean, was: Boolean): Boolean = privateTurn || (was && showsTurn)

    /**
     * No picture of the screen on the card while it shows a message's words or what you and Robot said, or in the trusted
     * session over the lock screen, so a glance at Recents never shows them.
     */
    fun picture(showsMessage: Boolean, showsTurn: Boolean, trusted: Boolean): Boolean = !showsMessage && !showsTurn && !trusted
}
