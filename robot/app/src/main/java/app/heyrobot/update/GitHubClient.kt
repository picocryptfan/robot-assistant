// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.Manifest
import android.content.Context
import android.net.Network
import app.heyrobot.BuildConfig
import app.heyrobot.core.Abort
import app.heyrobot.core.SecretRead
import app.heyrobot.core.update.ByteRange
import app.heyrobot.core.update.FileRef
import app.heyrobot.core.update.GitHubErrors
import app.heyrobot.core.update.GitHubHosts
import app.heyrobot.core.update.GitHubReleases
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.Hop
import app.heyrobot.core.update.ReleaseAssets
import app.heyrobot.core.update.UpdateFail
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.core.termux.TermuxUpdates
import app.heyrobot.sys.Assistant
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

// MAIN PROCESS. The updater's only network code, and the only code in Robot that talks to GitHub: GETs to api.github.com for
// a repository's releases and its files, following redirects to GitHub's download servers by hand (core GitHubHosts: HTTPS,
// four hosts, at most five hops). The access token (UpdateToken) is decrypted here, and only a request to api.github.com
// carries it; a download server never sees it. A saved token that couldn't be read just now is kept, and the request goes
// without it: a public repository still answers, and a private one fails as TOKEN_UNAVAILABLE until a later try reads it.
// Every request names Robot and its version (User-Agent "Robot/0.4.5") and nothing about the phone. Caps: 1 MB of JSON,
// 64 KB of SHA256SUMS, the APK's own size (at most 300 MB). Stop disconnects (Abort). Nothing is logged here, least of all
// a URL with a signature in it or the token.
// 1.0: also Settings › Termux's update check ([publicJson]): two fixed public addresses (core TermuxUpdates: Termux's
// releases on api.github.com, F-Droid's entry on f-droid.org), never with the token, no redirect followed, no cookies.

internal object GitHubClient {
    class Failure(val why: UpdateFail, val http: Int = 0, val retryAt: Long? = null) : Exception(why.name)

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** The newest releases, as GitHub's JSON. Blocking. */
    fun releases(ctx: Context, repo: GitHubRepo, abort: Abort, network: Network?, cause: NetCause): String =
        request(ctx, repo.releasesUrl(), GitHubHosts.ACCEPT_JSON, UpdateToken.read(ctx), 0, abort, network, purpose = NetPurpose.UPDATE_CHECK, cause = cause) { c, _, got ->
            readText(c, GitHubReleases.MAX_JSON, got)
        }

    /**
     * 1.0, "Check for Termux updates": the answer of one of core TermuxUpdates' two fixed public addresses, as text (at most
     * [max] bytes). No token (it is a public request, never the updater's), no redirect followed, and only those URLs.
     * Blocking.
     */
    fun publicJson(ctx: Context, url: String, accept: String, max: Int, abort: Abort, network: Network?, cause: NetCause): String {
        if (!TermuxUpdates.allowed(url)) throw Failure(UpdateFail.REDIRECT_REFUSED)
        return request(ctx, url, accept, SecretRead.Absent, 0, abort, network, public = true, purpose = NetPurpose.TERMUX_CHECK, cause = cause) { c, _, got ->
            readText(c, max, got)
        }
    }

    /** A release's SHA256SUMS. Blocking. */
    fun sums(ctx: Context, repo: GitHubRepo, file: FileRef, abort: Abort, network: Network?, cause: NetCause): String =
        request(ctx, repo.assetUrl(file.id), GitHubHosts.ACCEPT_FILE, UpdateToken.read(ctx), 0, abort, network, purpose = NetPurpose.UPDATE_DOWNLOAD, cause = cause) { c, _, got ->
            readText(c, ReleaseAssets.MAX_SUMS, got)
        }

    /**
     * The APK [file] into [part], continuing what is already there (an HTTP Range request; a server that sends the whole file
     * again starts it over). Exactly [FileRef.size] bytes or it throws; what arrived before a cut stays for next time.
     * Blocking. [progress] gets the bytes so far.
     */
    fun download(ctx: Context, repo: GitHubRepo, file: FileRef, part: File, abort: Abort, network: Network?, cause: NetCause, progress: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > file.size) {
            part.delete()
            have = 0
        }
        if (have == file.size) return
        request(ctx, repo.assetUrl(file.id), GitHubHosts.ACCEPT_FILE, UpdateToken.read(ctx), have, abort, network, purpose = NetPurpose.UPDATE_DOWNLOAD, cause = cause) { c, code, got ->
            val append = when {
                have > 0 && code == 206 && ByteRange.continues(c.getHeaderField("Content-Range"), have, file.size) -> true
                code == 200 -> false
                else -> throw Failure(UpdateFail.SIZE_MISMATCH, code)
            }
            var done = if (append) have else 0L
            val length = c.getHeaderField("Content-Length")?.toLongOrNull()
            if (length != null && done + length > file.size) throw Failure(UpdateFail.TOO_BIG, code)
            try {
                FileOutputStream(part, append).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (abort.cancelled) throw Failure(UpdateFail.STOPPED)
                            val n = input.read(buf)
                            if (n < 0) break
                            // Never more than GitHub said the file has.
                            if (done + n > file.size) throw Failure(UpdateFail.TOO_BIG, code)
                            out.write(buf, 0, n)
                            got[0] += n
                            done += n
                            progress(done)
                        }
                    }
                    out.fd.sync()
                }
            } catch (e: IOException) {
                // Storage (the phone's, not the network's) failing mid-write.
                if (e.message?.contains("ENOSPC") == true) throw Failure(UpdateFail.NO_SPACE)
                throw e
            }
            // Cut off early: what arrived is kept, and the next try continues from there.
            if (done != file.size) throw Failure(UpdateFail.NO_INTERNET, code)
        }
    }

    /**
     * Opens [start] and hands the 2xx answer to [read], following redirects only as core GitHubHosts allows, the token
     * ([saved], as UpdateToken read it) only to the API. A 3xx is never followed by the platform (instanceFollowRedirects
     * off), so a header set here goes to the host it was set for and no other. What went online (everyday-set-plan.md
     * §5.3): each hop that left is recorded here, its host (a redirect's is another), [purpose], [cause], the bytes [read]
     * counts into its third argument, and how it ended; one refused by the Network switch never left, so it isn't.
     */
    private fun <T> request(ctx: Context, start: String, accept: String, saved: SecretRead, from: Long, abort: Abort, network: Network?,
                            public: Boolean = false, purpose: NetPurpose, cause: NetCause, read: (HttpsURLConnection, Int, LongArray) -> T): T {
        // GrapheneOS's Network toggle is the INTERNET permission at run time.
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) throw Failure(UpdateFail.NO_NETWORK_PERMISSION)
        // 1.0: a public request (Termux's update check) never carries the token.
        val token = if (public) null else (saved as? SecretRead.Present)?.value
        // Saved but not readable just now: kept, and GitHub's "not found" for a private repository says so.
        val tokenKept = saved is SecretRead.Unavailable
        var url = start
        var hop = 0
        while (true) {
            if (abort.cancelled) throw Failure(UpdateFail.STOPPED)
            if (public) { if (!TermuxUpdates.allowed(url)) throw Failure(UpdateFail.REDIRECT_REFUSED) }
            else if (!GitHubHosts.allowed(url)) throw Failure(UpdateFail.REDIRECT_REFUSED)
            val u = URL(url)
            val c = (network?.openConnection(u) ?: u.openConnection()) as? HttpsURLConnection ?: throw Failure(UpdateFail.REDIRECT_REFUSED)
            val forget = abort.onCancel { c.disconnect() }
            val got = longArrayOf(0)
            var outcome: NetResult? = NetResult.FAILED
            try {
                c.connectTimeout = CONNECT_MS
                c.readTimeout = READ_MS
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("User-Agent", GitHubHosts.userAgent(BuildConfig.VERSION_NAME))
                c.setRequestProperty("Accept", accept)
                if (GitHubHosts.isApi(url)) c.setRequestProperty("X-GitHub-Api-Version", GitHubHosts.API_VERSION)
                // A file byte for byte (no compression), so its size and a Range mean what they say.
                if (accept == GitHubHosts.ACCEPT_FILE) c.setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) c.setRequestProperty("Range", ByteRange.request(from))
                // The access token: to api.github.com only (HTTPS, no other port or user), never to a download server.
                if (token != null && GitHubHosts.mayAuthorize(url)) c.setRequestProperty("Authorization", "Bearer $token") // l10n: not text (an HTTP header)
                val code = c.responseCode
                // The server answered: a redirect hop is done, an error answer failed (and a read below may fail it again).
                outcome = if (code in REDIRECTS || code in 200..299) NetResult.DONE else NetResult.FAILED
                if (code in REDIRECTS) {
                    if (public) throw Failure(UpdateFail.REDIRECT_REFUSED, code)
                    when (val next = GitHubHosts.redirect(url, c.getHeaderField("Location"), ++hop)) {
                        is Hop.Follow -> url = next.url
                        is Hop.Refuse -> throw Failure(UpdateFail.REDIRECT_REFUSED, code)
                    }
                    continue
                }
                if (code !in 200..299) {
                    val (why, retryAt) = GitHubErrors.classify(code, token != null, { c.getHeaderField(it) }, System.currentTimeMillis(), tokenKept)
                    throw Failure(why, code, retryAt)
                }
                outcome = NetResult.FAILED
                return read(c, code, got).also { outcome = NetResult.DONE }
            } catch (f: Failure) {
                if (f.why == UpdateFail.STOPPED) outcome = NetResult.STOPPED
                throw f
            } catch (e: Exception) {
                if (abort.cancelled) {
                    outcome = NetResult.STOPPED
                    throw Failure(UpdateFail.STOPPED)
                }
                val f = when (e) {
                    is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> Failure(UpdateFail.NO_INTERNET)
                    is SecurityException -> Failure(UpdateFail.NO_NETWORK_PERMISSION)
                    is SSLException -> Failure(UpdateFail.NO_INTERNET)
                    // A socket refused by the Network toggle fails with EACCES; anything else is the connection.
                    is IOException -> Failure(if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) // l10n: not text (the system's error message, matched)
                        UpdateFail.NO_NETWORK_PERMISSION else UpdateFail.NO_INTERNET)
                    else -> Failure(UpdateFail.SERVICE)
                }
                // The Network switch refused the socket: nothing went online.
                if (f.why == UpdateFail.NO_NETWORK_PERMISSION) outcome = null
                throw f
            } finally {
                forget()
                c.disconnect()
                outcome?.let { NetRecord.record(purpose, u.host, cause, 0, got[0], it) }
            }
        }
    }

    /** The body as text, at most [max] bytes (more is refused, not cut), its bytes counted into [got][0]. */
    private fun readText(c: HttpsURLConnection, max: Int, got: LongArray): String {
        c.getHeaderField("Content-Length")?.toLongOrNull()?.let { if (it > max) throw Failure(UpdateFail.TOO_BIG) }
        return c.inputStream.use { read(it, max, got) }
    }

    private fun read(s: InputStream, max: Int, got: LongArray): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            got[0] += n
            if (out.size() > max) throw Failure(UpdateFail.TOO_BIG)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
