// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.OrganicMapsRoute
import app.heyrobot.core.everyday.MapsApp
import app.heyrobot.core.everyday.MusicApp
import java.util.Locale

// Pure. Car mode's music tile and your maps app (docs/pm/handoff-car-media-maps.md). The car screen shows what plays now,
// else your music app, else a line that lets you choose one. Car mode alone reads Android's media sessions
// (app CarSessions; ProcessIsolationTest.musicSendsKeysAndAsksOnlyThePinnedApp); without notification access the session
// shown is your pinned app's, asked of its own media service as "what's playing" is. Every route Robot opens goes to your maps app (Settings › Actions › Maps), and
// the map sits beside car mode in split screen. A song's title and artist are shown and never logged, saved or sent.

/** Whether your pinned app is still installed and signed by the key you picked it with. */
enum class AppPin { OK, GONE, REPLACED }

/** A media session as the car screen sees it: its app, whether it plays now, and the song when the app says it. */
data class MediaNow(val pkg: String, val playing: Boolean, val title: String? = null, val artist: String? = null)

/** What the music tile shows. */
sealed class MusicTile {
    /** A session: the app playing now, or the one that played last. */
    data class Session(override val pkg: String, val playing: Boolean, val title: String?, val artist: String?) : MusicTile()
    /** No session: your pinned music app (E1), which Play starts. */
    data class Pinned(val app: MusicApp) : MusicTile() {
        override val pkg: String get() = app.pkg
    }
    /** Nothing playing and no music app of yours: "Choose your music app", and a tap opens the list. */
    data object Choose : MusicTile() {
        override val pkg: String? get() = null
    }

    /** The app the tile names, or null. */
    abstract val pkg: String?
}

object CarMusicTile {
    const val MAX_TEXT = 80

    /**
     * The tile for [sessions] in Android's order (the most important first): the first playing; else the first paused one
     * car mode saw playing this drive ([seen], packages), so a podcast you paused at a light comes back; else your music
     * app ([pinned], while still yours: [pin] OK) with its paused song if it has one; else Choose. A paused session from
     * any other app stays out, so a voice note from before the drive doesn't take the tile. Robot's own package ([self])
     * never counts (Robot holds no media session). A playing session is always shown, your music app no longer yours included.
     */
    fun of(sessions: List<MediaNow>, pinned: MusicApp?, pin: AppPin?, self: String, seen: Set<String> = emptySet()): MusicTile {
        val others = sessions.filter { it.pkg != self }
        val yours = pinned?.takeIf { pin == AppPin.OK }
        val s = others.firstOrNull { it.playing } ?: others.firstOrNull { it.pkg in seen } ?: others.firstOrNull { it.pkg == yours?.pkg }
        if (s != null) return MusicTile.Session(s.pkg, s.playing, clean(s.title), clean(s.artist)?.takeUnless { it.lowercase() in NO_ARTIST })
        return if (yours != null) MusicTile.Pinned(yours) else MusicTile.Choose
    }

    /** A title or artist fit for one line: no control characters, at most [MAX_TEXT] characters; blank is none. */
    fun clean(s: String?): String? {
        val t = s?.map { if (it < ' ' || it == '\u007f') ' ' else it }?.joinToString("")?.replace(Regex("\\s+"), " ")?.trim()
        if (t.isNullOrEmpty()) return null
        return if (t.length <= MAX_TEXT) t else CarText.cutAtWord(t, MAX_TEXT - 1)
    }

    /** The big line: the title, or the artist when there's no title. */
    fun song(t: MusicTile.Session): String? = t.title ?: t.artist

    /** The line under it, when there's a title above it. */
    fun artist(t: MusicTile.Session): String? = t.artist.takeIf { t.title != null }

    /** What apps send for a track with no artist, which says nothing on the car screen. */
    private val NO_ARTIST = setOf("unknown artist", "<unknown>", "unknown", "artiste inconnu") // l10n: not text (matched against what apps send)
}

/** Text that has to stop on the car screen stops at a whole word. */
object CarText {
    /**
     * [text] up to [end] with an ellipsis, without the word that [end] falls in; a single word longer than that is cut
     * where it is. A trailing comma or dash doesn't stay in front of the ellipsis.
     */
    fun cutAtWord(text: String, end: Int): String {
        val room = text.take(end.coerceIn(0, text.length))
        val space = room.indexOfLast { it.isWhitespace() }
        val whole = if (room.length == text.length || text[room.length].isWhitespace() || space <= 0) room else room.take(space)
        return whole.trimEnd(' ', ',', ';', ':', '·', '-', '–', '—') + "…"
    }
}

/** What opening a maps link means: [uri] in [pkg] (null: the app Android picks for the link). */
data class MapsLink(val uri: String, val pkg: String?)

/** Where Robot's routes go. */
sealed class MapsPlan {
    /** Try [links] in order until one opens. */
    data class Open(val links: List<MapsLink>) : MapsPlan()
    /** Your picked app is now signed by someone else: nothing opens (your position never goes to it); pick it again. */
    data class Repick(val label: String) : MapsPlan()
}

/**
 * Settings › Actions › Maps: Automatic (Organic Maps when it's installed, else the phone's default app for map links) or
 * an app you picked, pinned with its signer. A route goes as the app documents one: Organic Maps' om://route (its
 * map/mwm_url.cpp), Google Maps' Maps URL (developers.google.com/maps/documentation/urls/get-started, "directions":
 * api=1, destination, travelmode; no origin, so Google Maps starts from where it finds you). Any other app gets the
 * geo: link of the place. Never a guessed scheme.
 */
object MapsChoice {
    const val ORGANIC_MAPS = "app.organicmaps"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"

    /**
     * The app routes go to: [picked] while yours; a picked app since uninstalled ([AppPin.GONE]) falls back to Automatic;
     * Organic Maps when [organic] (installed); else null (Android's default app for geo:, [geoDefault] when known).
     * Replaced: none (Repick), so a lock never leaves an app open that someone else signed.
     */
    fun app(picked: MapsApp?, pin: AppPin?, organic: Boolean, geoDefault: String? = null): String? = when {
        picked != null && pin == AppPin.OK -> picked.pkg
        picked != null && pin == AppPin.REPLACED -> null
        organic -> ORGANIC_MAPS
        else -> geoDefault
    }

    /**
     * The route from [from] (where Robot found the phone, or null) to the place [to] named [toName]. [fromName]: "Your
     * position", as Organic Maps shows the start.
     */
    fun route(picked: MapsApp?, pin: AppPin?, organic: Boolean, from: Pair<Double, Double>?, fromName: String, to: Pair<Double, Double>, toName: String): MapsPlan {
        if (picked != null && pin == AppPin.REPLACED) return MapsPlan.Repick(picked.label)
        val geo = OrganicMapsRoute.geo(to.first, to.second, toName)
        val pkg = if (picked != null && pin == AppPin.OK) picked.pkg else if (organic) ORGANIC_MAPS else null
        val links = when (pkg) {
            ORGANIC_MAPS -> listOfNotNull(from?.let { MapsLink(OrganicMapsRoute.uri(it.first, it.second, fromName, to.first, to.second, toName), ORGANIC_MAPS) },
                MapsLink(geo, ORGANIC_MAPS))
            GOOGLE_MAPS -> listOf(MapsLink(googleDirections(to.first, to.second), GOOGLE_MAPS), MapsLink(geo, GOOGLE_MAPS))
            null -> listOf(MapsLink(geo, null))
            else -> listOf(MapsLink(geo, pkg))
        }
        return MapsPlan.Open(links)
    }

    /** A search for [query] (URI-encoded by the caller as [q]) in your maps app: Organic Maps' own search first, else geo:0,0?q=. */
    fun search(picked: MapsApp?, pin: AppPin?, organic: Boolean, q: String): MapsPlan {
        if (picked != null && pin == AppPin.REPLACED) return MapsPlan.Repick(picked.label)
        val pkg = if (picked != null && pin == AppPin.OK) picked.pkg else null
        val geo = "geo:0,0?q=$q"
        return MapsPlan.Open(when {
            pkg == ORGANIC_MAPS || (pkg == null && organic) -> listOf(MapsLink("om://search?query=$q", ORGANIC_MAPS), MapsLink(geo, ORGANIC_MAPS))
            pkg != null -> listOf(MapsLink(geo, pkg))
            else -> listOf(MapsLink(geo, null))
        })
    }

    /**
     * Google Maps' documented Android navigation intent, driving, to a point (six decimals; Google Maps is online anyway).
     * No web address: Robot's code holds none but the ones ProcessIsolationTest lists.
     */
    fun googleDirections(lat: Double, lon: Double): String =
        "google.navigation:q=${String.format(Locale.US, "%.6f,%.6f", lat, lon)}&mode=d" // l10n: not text (an intent address)
}

/** Car mode's maps tile: "Back to directions" when directions are known to run, else "Maps". Either brings the app forward. */
object CarDirections {
    enum class Tile { BACK_TO_DIRECTIONS, MAPS }

    /**
     * [routeThisDrive]: Robot opened a route since car mode came on; [ongoing]: the maps app has an ongoing notification
     * now. The app passes false: Robot's listener never gets ongoing notifications (ProcessIsolationTest.messagesStayOnThePhone).
     */
    fun tile(routeThisDrive: Boolean, ongoing: Boolean): Tile = if (routeThisDrive || ongoing) Tile.BACK_TO_DIRECTIONS else Tile.MAPS
}

/** May the car screen open an app now (the lock screen's and Phone lock's rules, as the spoken "navigate home" has them)? */
object CarOpen {
    enum class Gate { OPEN, PHONE_LOCK, UNLOCK }

    /** Your maps app: on the lock screen only in car mode's trusted session; under Phone lock only when every lock has Maps on. */
    fun maps(keyguard: Boolean, trusted: Boolean, phoneLock: Boolean, lockMaps: Boolean): Gate = gate(keyguard, trusted, phoneLock, lockMaps)

    /** The music app (its name tapped, or Play with nothing playing): under Phone lock only when the locks leave [pkg] open. */
    fun app(pkg: String, keyguard: Boolean, trusted: Boolean, phoneLock: Boolean, lockApps: Set<String>): Gate = gate(keyguard, trusted, phoneLock, pkg in lockApps)

    /** Settings holds choices and places: never over the lock screen, trusted session or not. Phone lock's own gate covers the main app. */
    fun settings(keyguard: Boolean): Gate = if (keyguard) Gate.UNLOCK else Gate.OPEN

    /** The cover leaves your maps app open during your car's car mode, your own lock or someone else's (LockEngine.facts), so the gates do too. */
    fun lockMaps(everyLockHasMaps: Boolean, carOn: Boolean): Boolean = everyLockHasMaps || carOn

    /** Phone lock first, on the lock screen too (fail closed); then the lock screen's trusted session. */
    private fun gate(keyguard: Boolean, trusted: Boolean, phoneLock: Boolean, allowed: Boolean): Gate = when {
        phoneLock && !allowed -> Gate.PHONE_LOCK
        keyguard && !trusted -> Gate.UNLOCK
        else -> Gate.OPEN
    }
}
