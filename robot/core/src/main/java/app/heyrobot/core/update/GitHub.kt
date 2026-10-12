package app.heyrobot.core.update

import java.net.URI
import java.net.URLEncoder

// Pure. Where Robot's updates come from: one GitHub repository's releases, asked of api.github.com, the files from GitHub's
// download servers. Only these hosts, only HTTPS, at most five redirects, and the access token (for a private repository)
// only ever on a request to api.github.com. The app's GitHubClient (app/update) is the only code that goes online with
// this, and ProcessIsolationTest keeps it so.

/**
 * A GitHub repository, "owner/name". The owner: ASCII letters, digits and single hyphens inside, up to 39 characters (GitHub's
 * rule for user and organisation names); the name: ASCII letters, digits, ".", "-" and "_", up to 100, not "." or "..".
 */
data class GitHubRepo(val owner: String, val name: String) {
    init {
        require(validOwner(owner) && validName(name)) { "not a GitHub repository" }
    }

    val slug: String get() = "$owner/$name"

    /** The newest [perPage] releases, newest first (drafts only with push access, and Robot never offers one). */
    fun releasesUrl(perPage: Int = PER_PAGE): String = "https://${GitHubHosts.API}/repos/$owner/$name/releases?per_page=$perPage"

    /**
     * A release file by its id, as the API serves it to public and private repositories alike (with
     * "Accept: application/octet-stream" it redirects to GitHub's download server). Built from the id, never from a URL in
     * the answer.
     */
    fun assetUrl(id: Long): String {
        require(id > 0)
        return "https://${GitHubHosts.API}/repos/$owner/$name/releases/assets/$id"
    }

    /** The release's page, for "See it on GitHub": built here, never taken from the answer. */
    fun releasePage(tag: String): String = "https://github.com/$owner/$name/releases/tag/" + URLEncoder.encode(tag, "UTF-8").replace("+", "%20")

    /**
     * 0.7, Send feedback's "Open a GitHub issue": this repository's new-issue page with [title] and [body] filled in, opened in
     * the browser, where you sign in (a private repository needs it) and submit it yourself; Robot sends nothing. Built here,
     * where every GitHub address is.
     */
    fun newIssueUrl(title: String, body: String): String = "https://github.com/$owner/$name/issues/new?title=" + encode(title) + "&body=" + encode(body)

    override fun toString(): String = slug

    companion object {
        const val PER_PAGE = 20

        /** [s] as a link's query value: UTF-8, every reserved character escaped, spaces as %20. */
        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /**
         * What you typed or pasted: "owner/name", or a link to it ("https://github.com/owner/name", "github.com/owner/name.git",
         * a trailing "/"). Null when it isn't one.
         */
        fun parse(text: String?): GitHubRepo? {
            var t = text?.trim() ?: return null
            if (t.isEmpty() || t.length > 200) return null
            for (p in listOf("https://", "http://")) if (t.startsWith(p, ignoreCase = true)) t = t.substring(p.length)
            for (p in listOf("www.github.com/", "github.com/")) if (t.startsWith(p, ignoreCase = true)) t = t.substring(p.length)
            t = t.trimEnd('/')
            if (t.endsWith(".git")) t = t.dropLast(4)
            val parts = t.split('/')
            if (parts.size != 2) return null
            return if (validOwner(parts[0]) && validName(parts[1])) GitHubRepo(parts[0], parts[1]) else null
        }

        /**
         * The repository a link on github.com names ("https://github.com/owner/name", as components.json's robot.source has
         * it: Settings › About › Licences' source link), or null for any other link.
         */
        fun fromSourceLink(url: String?): GitHubRepo? {
            val u = url?.trim() ?: return null
            if (!u.startsWith("https://github.com/")) return null
            return parse(u)
        }

        fun validOwner(o: String): Boolean = o.length in 1..39 && o.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' } &&
            !o.startsWith('-') && !o.endsWith('-') && !o.contains("--")

        fun validName(n: String): Boolean = n.length in 1..100 && n != "." && n != ".." &&
            n.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }
    }
}

/** Why a redirect wasn't followed. */
enum class RedirectRefusal { NO_LOCATION, TOO_MANY, NOT_HTTPS, HOST_NOT_ALLOWED, MALFORMED }

/** One step of a redirect: follow it (with the token only when [authorize]), or not. */
sealed class Hop {
    data class Follow(val url: String, val authorize: Boolean) : Hop()
    data class Refuse(val why: RedirectRefusal) : Hop()
}

object GitHubHosts {
    /** GitHub's API: the only host that ever sees the access token. */
    const val API = "api.github.com"

    /**
     * Every host the updater talks to: the API, github.com (a release page's links and renamed-repository redirects) and the
     * two download servers a release file redirects to (objects.githubusercontent.com, and release-assets.githubusercontent.com
     * since 2025).
     */
    val ALLOWED: Set<String> = setOf(API, "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    const val MAX_REDIRECTS = 5

    /** GitHub's REST API version Robot is written against (sent as X-GitHub-Api-Version). */
    const val API_VERSION = "2022-11-28"
    const val ACCEPT_JSON = "application/vnd.github+json"
    const val ACCEPT_FILE = "application/octet-stream"

    /** GitHub requires a User-Agent; Robot's names the app and its version, nothing about the phone. */
    fun userAgent(version: String): String = "Robot/" + version.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '.' || it == '-' }.take(32)

    /** The host of an HTTPS URL Robot may open (lower case), or null: another scheme or host, a user name or a port in it. */
    fun hostOf(url: String): String? {
        val u = try { URI(url) } catch (_: Exception) { return null }
        if (!u.isAbsolute || u.isOpaque || !u.scheme.equals("https", ignoreCase = true)) return null
        if (u.rawUserInfo != null || (u.port != -1 && u.port != 443)) return null
        // A host URI can't parse (non-ASCII, an underscore) is null here: never allowed.
        val h = u.host?.lowercase() ?: return null
        return h.takeIf { it in ALLOWED }
    }

    fun allowed(url: String): Boolean = hostOf(url) != null

    /** The access token may go with a request to [url] only when it is api.github.com, over HTTPS. */
    fun mayAuthorize(url: String): Boolean = hostOf(url) == API

    /** Whether [url] is the API (it gets the API's Accept and version headers). */
    fun isApi(url: String): Boolean = hostOf(url) == API

    /**
     * A redirect from [from] to [location] (relative or absolute), as hop number [hop] (1 for the first): followed only to
     * an allowed HTTPS host, at most [MAX_REDIRECTS] times, and with the token only to the API.
     */
    fun redirect(from: String, location: String?, hop: Int): Hop {
        if (location.isNullOrBlank()) return Hop.Refuse(RedirectRefusal.NO_LOCATION)
        if (hop > MAX_REDIRECTS) return Hop.Refuse(RedirectRefusal.TOO_MANY)
        val next = try { URI(from).resolve(URI(location.trim())) } catch (_: Exception) { return Hop.Refuse(RedirectRefusal.MALFORMED) }
        val url = next.toString()
        if (!next.isAbsolute || !next.scheme.equals("https", ignoreCase = true)) return Hop.Refuse(RedirectRefusal.NOT_HTTPS)
        if (hostOf(url) == null) return Hop.Refuse(RedirectRefusal.HOST_NOT_ALLOWED)
        return Hop.Follow(url, mayAuthorize(url))
    }
}

/** What went wrong with an answer from GitHub, by its HTTP status and headers (pure, for the client). */
object GitHubErrors {
    /**
     * [code] isn't a success: why, and for a rate limit when it lifts (ms since 1970, null if GitHub didn't say).
     * [header] reads a response header (any case). [hasToken]: the request carried the access token. [tokenKept]: it went
     * without one because the saved token couldn't be read just now (it is kept, and the next check reads it again).
     */
    fun classify(code: Int, hasToken: Boolean, header: (String) -> String?, nowMs: Long, tokenKept: Boolean = false): Pair<UpdateFail, Long?> {
        val remaining = header("x-ratelimit-remaining")?.trim()
        val reset = header("x-ratelimit-reset")?.trim()?.toLongOrNull()?.takeIf { it in 1..4_102_444_800L }?.times(1000)
        val retry = header("retry-after")?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }?.let { nowMs + it * 1000 }
        val noToken = if (tokenKept && !hasToken) UpdateFail.TOKEN_UNAVAILABLE else UpdateFail.NOT_FOUND_NO_TOKEN
        return when {
            code == 429 || (code == 403 && (remaining == "0" || retry != null)) -> UpdateFail.RATE_LIMITED to (retry ?: reset)
            code == 401 -> (if (hasToken) UpdateFail.TOKEN_REFUSED else noToken) to null
            code == 403 -> UpdateFail.FORBIDDEN to null
            // GitHub answers 404 for a private repository it won't show without a token.
            code == 404 -> (if (hasToken) UpdateFail.NOT_FOUND else noToken) to null
            code == 416 -> UpdateFail.SIZE_MISMATCH to null
            else -> UpdateFail.SERVICE to null
        }
    }
}

/**
 * The access token for a private repository: a GitHub token ("github_pat_", "ghp_" and the other "gh?_" kinds, or an
 * older 40-character one), ASCII letters, digits and "_" only, so it can't carry anything into a header.
 */
object GitHubToken {
    private val PREFIXES = listOf("github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_")

    fun plausible(t: String): Boolean {
        if (t.length !in 40..255 || !t.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }) return false
        return PREFIXES.any { t.startsWith(it) } || (t.length == 40 && t.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** "github_pat_…AB12": shown in Settings. */
    fun mask(t: String): String = (PREFIXES.firstOrNull { t.startsWith(it) } ?: "") + "…" + t.takeLast(4)
}

/** A download that continues where the last one stopped (an HTTP Range request). */
object ByteRange {
    private val CONTENT_RANGE = Regex("""bytes (\d{1,15})-(\d{1,15})/(\d{1,15}|\*)""")

    /** The "Range" header asking for the rest of a file from byte [from]. */
    fun request(from: Long): String = "bytes=$from-"

    /** Whether a 206 answer's Content-Range ("bytes 100-999/1000") is exactly the rest of a [total]-byte file from [from]. */
    fun continues(contentRange: String?, from: Long, total: Long): Boolean {
        val m = CONTENT_RANGE.matchEntire(contentRange?.trim() ?: return false) ?: return false
        val start = m.groupValues[1].toLong()
        val end = m.groupValues[2].toLong()
        val length = m.groupValues[3]
        return start == from && end == total - 1 && (length == "*" || length.toLong() == total)
    }
}
