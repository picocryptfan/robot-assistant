// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase

// Pure. Why a connection's call didn't happen or didn't work (plan §3's shared errors, C2), each said in Robot's fixed
// words with the name you gave the connection, never a server's own text (it could carry anything). [ConnFailure.code]
// is what the voice log keeps (D21, C4): a number, never a host, a path or a reply.

enum class ConnFailure(val code: Int) {
    /** No network at all. */
    OFFLINE(1),
    /** GrapheneOS's Network toggle is off for Robot (D22): nothing is tried. */
    NETWORK_OFF(2),
    /** Couldn't connect. */
    UNREACHABLE(3),
    /** No answer within the call's 10 s (EST). */
    TIMEOUT(4),
    /** A home connection while the phone is away from your network. */
    AWAY(5),
    /** An http address now resolves outside your network (D1): nothing was sent. */
    MOVED(6),
    /**
     * A lookup gave both a home and an internet address (D1, ConnRules.lookup): nothing was sent. The client keeps the home
     * ones of one lookup instead (C3, ConnRules.lookupHomeWins), and says this only when two lookups in one call disagree.
     */
    BOTH_KINDS(7),
    /** The address resolves only to places Robot never connects to (D1: neither home nor internet). */
    NOWHERE(8),
    /** Android doesn't trust the server's certificate (D3). */
    CERTIFICATE(9),
    /** A secret would go over plain http (D2): refused before anything is built. */
    KEY_OVER_HTTP(10),
    /** 401. */
    KEY_REFUSED(11),
    /** 403. */
    NOT_ALLOWED(12),
    /** Another 4xx or 5xx, or a redirect (never followed): said with its status code. */
    SAID_NO(13),
    /** 429. */
    TOO_MANY(14),
    /** Over 256 KiB, not JSON when JSON was needed, or neither JSON nor plain text. */
    UNREADABLE(15),
    /** D16: the connection's requests a minute. */
    LIMIT_MINUTE(16),
    /** D16: the connection's 500 a day. */
    LIMIT_DAY(17),
    /** The connection's key isn't saved. */
    NO_KEY(18),
    /** Test hasn't passed since it was set up or changed. */
    NOT_TESTED(19),
    /** The connection or the operation a step names is gone. */
    GONE(20),
    /** `travel_time` to Home, and no Home is saved in Car mode. */
    NO_HOME(21),
    /** `travel_time` to Work, and no Work is saved in Car mode. */
    NO_WORK(22),
    /** A value doesn't fit what the operation takes (its type, its choices, its limits, its place): nothing was sent. */
    BAD_VALUE(23),
    // C4: a skill's step before its call.
    /** A value the step takes from an earlier step's answer didn't come back: nothing was sent (never guessed or defaulted). */
    NO_VALUE(24),
    /** The key is saved but couldn't be read just now (kept; the next run tries again). */
    KEY_UNAVAILABLE(25),
    /** The key could never be read again and was removed: paste it again. */
    KEY_UNREADABLE(26),
    /** The key was saved by a newer Robot: kept, never used. */
    KEY_NEWER(27),
    /** A step kind a later job builds (hand-offs: C7). */
    NOT_YET(28),
    // C6: travel time, which sends where you are.
    /** A routing server over plain http to anything but a typed loopback or Tailscale address (ConnRules.locationOverHttp). */
    LOCATION_OVER_HTTP(29),
    /** Robot may not see where the phone is (no location permission). */
    LOCATION_NOT_ALLOWED(30),
    /** Location is off in Android's settings. */
    LOCATION_OFF(31),
    /** Android refused the location to the assistant's process, which it counted as in the background. */
    LOCATION_BACKGROUND(32),
    /** No position in time, or only one too old. */
    NO_LOCATION(33),
    // C7: a hand-off to an app you picked.
    /** The app isn't installed now. */
    APP_MISSING(34),
    /** The app is signed by someone else than when you picked it: never opened, pick it again. */
    APP_REPLACED(35),
    /** None of the app's activities takes what the hand-off asks (or Android wouldn't start it). */
    APP_CANT_OPEN(36),
    /** C10: the hand-off came from a backup that isn't yours, so its app isn't pinned here: never opened, pick it again. */
    APP_UNPINNED(37),
    // C13: a step checked again at its run (C12's L3).
    /** The operation or hand-off changed after its card showed (another service, entity, app or intent): nothing was sent. */
    CHANGED(38),
    /** An MCP server on the phone without a token (design §8.5): any app on the phone can reach it. */
    LOOPBACK_NO_KEY(39),
    /** An MCP server reached through a public address (your answer of 4 Nov 2026: never the public internet in 1.3). */
    MCP_PUBLIC(40),
    /** An MCP call whose arguments are longer than its card can show (ConnStep.PREVIEW_BODY): nothing is sent unseen. */
    MCP_TOO_LONG(41),
    /** Question 35: an MCP server over plain http, token or none, wherever it is (ConnRules.httpsOnly). */
    MCP_HTTP(42);

    /**
     * What Robot says, for the connection called [name]. [status]: the HTTP status for [SAID_NO]; [limit]: the number for
     * [LIMIT_MINUTE] and [LIMIT_DAY].
     */
    fun text(name: String, status: Int? = null, limit: Int? = null): Phrase {
        val n = "name" to name
        val page = "page" to ConnStrings.page
        return when (this) {
            OFFLINE -> ConnStrings.offline()
            NETWORK_OFF -> ConnStrings.networkOff()
            UNREACHABLE -> ConnStrings.unreachable(n)
            TIMEOUT -> ConnStrings.timeout(n)
            AWAY -> ConnStrings.away(n)
            MOVED -> ConnStrings.moved(n, page)
            BOTH_KINDS -> ConnStrings.bothKinds()
            NOWHERE -> ConnStrings.nowhere(n, page)
            CERTIFICATE -> ConnStrings.certificate(n, page)
            KEY_OVER_HTTP -> ConnStrings.keyOverHttp(n)
            LOOPBACK_NO_KEY -> ConnStrings.mcpLoopbackKey()
            MCP_PUBLIC -> ConnStrings.mcpPublic(n)
            MCP_TOO_LONG -> ConnStrings.mcpTooLong(n)
            MCP_HTTP -> ConnStrings.mcpHttp(n)
            KEY_REFUSED -> ConnStrings.keyRefused(n, page)
            NOT_ALLOWED -> ConnStrings.notAllowed(n)
            SAID_NO -> ConnStrings.saidNo(n, "code" to (status ?: 0).toString())
            TOO_MANY -> ConnStrings.tooMany(n)
            UNREADABLE -> ConnStrings.unreadable(n)
            LIMIT_MINUTE -> ConnStrings.limitMinute(n, "n" to (limit ?: Connection.DEFAULT_PER_MINUTE))
            LIMIT_DAY -> ConnStrings.limitDay(n, "n" to (limit ?: ConnLimits.PER_DAY))
            NO_KEY -> ConnStrings.noKey(n, page)
            NOT_TESTED -> ConnStrings.notTested(n, page)
            GONE -> ConnStrings.gone("page" to ConnStrings.page)
            NO_HOME -> ConnStrings.noHome("page" to ConnStrings.carPage)
            NO_WORK -> ConnStrings.noWork("page" to ConnStrings.carPage)
            BAD_VALUE -> ConnStrings.badValue(n)
            NO_VALUE -> ConnStrings.noValue(n)
            KEY_UNAVAILABLE -> ConnStrings.keyUnavailable(n)
            KEY_UNREADABLE -> ConnStrings.keyUnreadable(n, page)
            KEY_NEWER -> ConnStrings.keyNewer(n)
            NOT_YET -> ConnStrings.notYet()
            LOCATION_OVER_HTTP -> ConnStrings.locationOverHttp(n)
            LOCATION_NOT_ALLOWED -> ConnStrings.locationNotAllowed()
            LOCATION_OFF -> ConnStrings.locationOff()
            LOCATION_BACKGROUND -> ConnStrings.locationBackground()
            NO_LOCATION -> ConnStrings.noLocation()
            APP_MISSING -> ConnStrings.appMissing(n)
            APP_REPLACED -> ConnStrings.appReplaced(n, page)
            APP_CANT_OPEN -> ConnStrings.appCantOpen(n, page)
            APP_UNPINNED -> ConnStrings.appUnpinned(n, page)
            CHANGED -> ConnStrings.changed()
        }
    }

    companion object {
        fun of(code: Int): ConnFailure? = entries.firstOrNull { it.code == code }
    }
}
