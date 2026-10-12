package app.heyrobot.core.termux

import app.heyrobot.core.LogCode
import app.heyrobot.core.update.GitHubReleases
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.RobotVersion
import app.heyrobot.core.update.UpdateFail
import org.json.JSONObject
import java.time.Instant

// Pure (1.0). Settings › Termux › "Check for Termux updates": once a week (and Check now), while it is on and a Termux is
// trusted, the main app's GitHubClient asks two fixed addresses, and nothing else: Termux's newest releases on GitHub
// (api.github.com, no token) and F-Droid's entry for com.termux (f-droid.org). Both are public; the request carries
// Robot's User-Agent and nothing about you. What came back is kept in files/robot/termux_updates.json
// ([TermuxUpdateState]). Robot never downloads or installs another app: it says what is newer and where to get it (your
// F-Droid app).

object TermuxUpdates {
    /** Termux's own repository: its releases are what its developers published. */
    val REPO = GitHubRepo("termux", "termux-app")
    const val PER_PAGE = 5

    /** GET, no token: the newest [PER_PAGE] releases (GitHub's URL is built in core's GitHub.kt, with every other). */
    val GITHUB_URL: String = REPO.releasesUrl(PER_PAGE)

    /** F-Droid's public API: the suggested and listed versions of com.termux, a few hundred bytes. */
    const val FDROID_URL = "https://f-droid.org/api/v1/packages/com.termux"
    const val FDROID_ACCEPT = "application/json"

    /** The only two addresses the check opens (no redirect is followed). */
    val URLS: Set<String> = setOf(GITHUB_URL, FDROID_URL)

    fun allowed(url: String): Boolean = url in URLS

    /** GitHub's answer is read up to GitHubReleases' cap; F-Droid's is small. */
    const val MAX_GITHUB = GitHubReleases.MAX_JSON
    const val MAX_FDROID = 64 * 1024

    const val WEEK_MS = 7L * 24 * 3600 * 1000

    /** A weekly run skips the check when the last one is newer than this (the job may run early). */
    const val MIN_GAP_MS = 6L * 24 * 3600 * 1000

    /** "Far behind": the newest release came out this long after the installed one (or after you installed it). */
    const val FAR_BEHIND_MS = 365L * 24 * 3600 * 1000

    /** GitHub's releases answer: the newest release, a newer test version, and each listed version's date; null when it isn't one. */
    fun parseGitHub(json: String?): GitHubLatest? {
        val list = GitHubReleases.parseList(json) ?: return null
        val published = list.filter { !it.draft }
        val dated = published.mapNotNull { r -> val v = RobotVersion.parse(r.tag) ?: return@mapNotNull null; Triple(r, v, time(r.publishedAt)) }
        val stable = dated.filter { !it.first.testBuild }.maxByOrNull { it.second }
        val test = dated.filter { it.first.testBuild }.maxByOrNull { it.second }?.takeIf { t -> stable == null || t.second > stable.second }
        if (stable == null && test == null) return null
        return GitHubLatest(
            version = stable?.second?.text, publishedAt = stable?.third ?: 0L,
            testVersion = test?.second?.text, testAt = test?.third ?: 0L,
            dates = dated.associate { it.second.text to it.third },
        )
    }

    /** F-Droid's entry (`{"packageName":"com.termux","suggestedVersionCode":…,"packages":[{"versionName":…,"versionCode":…}]}`), or null. */
    fun parseFdroid(json: String?): FdroidLatest? {
        if (json == null || json.length > MAX_FDROID) return null
        val o = try { JSONObject(json) } catch (_: Exception) { return null } catch (_: StackOverflowError) { return null }
        if (o.opt("packageName") != TermuxIntents.PACKAGE) return null
        val suggested = (o.opt("suggestedVersionCode") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
        val packages = o.optJSONArray("packages")
        val listed = (0 until minOf(packages?.length() ?: 0, 50)).mapNotNull { i ->
            val p = packages?.optJSONObject(i) ?: return@mapNotNull null
            val code = (p.opt("versionCode") as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = (p.opt("versionName") as? String)?.takeIf { RobotVersion.parse(it) != null } ?: return@mapNotNull null
            code to name
        }
        val newest = listed.maxByOrNull { it.first }
        return FdroidLatest(suggested, listed.firstOrNull { it.first == suggested }?.second, newest?.first ?: suggested, newest?.second)
    }

    /** ISO 8601 ("2025-05-22T21:53:15Z") in ms; 0 when missing or unreadable. */
    fun time(iso: String?): Long = try { if (iso == null) 0 else Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0 }

    /**
     * What Settings › Termux says about the installed Termux ([pkg], from [source]) against the last check ([state]).
     * The F-Droid build is newer only when F-Droid offers a newer build (its version codes are Termux's own); any other
     * build compares its version with the developers' newest release on GitHub.
     */
    fun news(pkg: TermuxPackage, source: TermuxSource?, state: TermuxUpdateState): TermuxNews {
        if (!pkg.installed) return TermuxNews(TermuxUpdateVerdict.UNKNOWN, null)
        if (state.checkedAt == 0L) return TermuxNews(TermuxUpdateVerdict.NOT_CHECKED, null)
        val gh = state.github
        val fd = state.fdroid
        if (gh == null && fd == null) return TermuxNews(TermuxUpdateVerdict.FAILED, null)
        val installed = RobotVersion.parse(pkg.versionName)
        val ghNewer = gh?.version?.let(RobotVersion::parse)?.takeIf { installed != null && it > installed }
        val fdNewer = fd?.takeIf { pkg.versionCode > 0 && it.suggestedCode > pkg.versionCode }
        val newest: String? = if (source == TermuxSource.F_DROID) fdNewer?.let { it.suggested ?: gh?.version } else (ghNewer?.text ?: fdNewer?.suggested)
        if (newest == null) {
            // The F-Droid build: the developers published a newer one that F-Droid hasn't built yet.
            if (source == TermuxSource.F_DROID && ghNewer != null) return TermuxNews(TermuxUpdateVerdict.NOT_ON_FDROID_YET, ghNewer.text)
            return TermuxNews(if (installed == null && pkg.versionCode == 0L) TermuxUpdateVerdict.UNKNOWN else TermuxUpdateVerdict.UP_TO_DATE, null)
        }
        // How far behind: the newest release's date against the installed version's (or, unlisted, when you installed it).
        val newestAt = gh?.dates?.get(newest) ?: gh?.publishedAt?.takeIf { gh.version == newest } ?: 0L
        val installedAt = installed?.text?.let { gh?.dates?.get(it) }?.takeIf { it > 0 } ?: pkg.lastUpdateTime
        val far = newestAt > 0 && installedAt > 0 && newestAt - installedAt >= FAR_BEHIND_MS
        return TermuxNews(if (far) TermuxUpdateVerdict.FAR_BEHIND else TermuxUpdateVerdict.AVAILABLE, newest)
    }
}

/** The developers' releases on GitHub: the newest ([version]), a newer test version if there is one, and each listed version's date (ms). */
data class GitHubLatest(val version: String?, val publishedAt: Long, val testVersion: String? = null, val testAt: Long = 0, val dates: Map<String, Long> = emptyMap())

/** F-Droid's entry: the version it suggests (its code and name) and the newest it lists (a test version may be newer). */
data class FdroidLatest(val suggestedCode: Long, val suggested: String?, val newestCode: Long, val newest: String?)

/** The last check (files/robot/termux_updates.json, written by the main app). */
data class TermuxUpdateState(
    /** When it last ran (wall clock, ms); 0: never. */
    val checkedAt: Long = 0,
    val github: GitHubLatest? = null,
    val fdroid: FdroidLatest? = null,
    /** Why a request failed (each is asked even when the other fails). */
    val githubFailure: UpdateFail? = null,
    val fdroidFailure: UpdateFail? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("checkedAt", checkedAt)
        github?.let { g ->
            put("github", JSONObject().apply {
                g.version?.let { put("version", it) }
                put("publishedAt", g.publishedAt)
                g.testVersion?.let { put("testVersion", it); put("testAt", g.testAt) }
                put("dates", JSONObject().apply { for ((k, v) in g.dates) put(k, v) })
            })
        }
        fdroid?.let { f ->
            put("fdroid", JSONObject().apply {
                put("suggestedCode", f.suggestedCode)
                f.suggested?.let { put("suggested", it) }
                put("newestCode", f.newestCode)
                f.newest?.let { put("newest", it) }
            })
        }
        githubFailure?.let { put("githubFailure", it.name) }
        fdroidFailure?.let { put("fdroidFailure", it.name) }
    }

    companion object {
        const val FILE = "termux_updates.json"

        private fun version(o: JSONObject, k: String): String? = o.optString(k, "").takeIf { RobotVersion.parse(it) != null }

        fun fromJson(o: JSONObject?): TermuxUpdateState {
            if (o == null) return TermuxUpdateState()
            val g = o.optJSONObject("github")?.let { g ->
                val dates = LinkedHashMap<String, Long>()
                g.optJSONObject("dates")?.let { d -> for (k in d.keys()) if (RobotVersion.parse(k) != null && dates.size < 20) dates[k] = d.optLong(k, 0) }
                GitHubLatest(version(g, "version"), g.optLong("publishedAt", 0), version(g, "testVersion"), g.optLong("testAt", 0), dates)
            }
            val f = o.optJSONObject("fdroid")?.let { f ->
                FdroidLatest(f.optLong("suggestedCode", 0), version(f, "suggested"), f.optLong("newestCode", 0), version(f, "newest"))
            }?.takeIf { it.suggestedCode > 0 }
            fun fail(k: String) = UpdateFail.entries.firstOrNull { it.name == o.optString(k) }
            return TermuxUpdateState(o.optLong("checkedAt", 0), g, f, fail("githubFailure"), fail("fdroidFailure"))
        }

        fun parse(text: String?): TermuxUpdateState = try { fromJson(text?.let(::JSONObject)) } catch (_: Exception) { TermuxUpdateState() }
    }
}

/** What Settings › Termux says, and voice log TERMUX_UPDATE_CHECK's a. */
enum class TermuxUpdateVerdict(override val id: Int, override val title: String) : LogCode {
    UP_TO_DATE(1, "up to date"),
    AVAILABLE(2, "update available"),
    FAR_BEHIND(3, "update available, a year or more behind"),
    /** The F-Droid build: the developers published a newer version F-Droid hasn't built yet. */
    NOT_ON_FDROID_YET(4, "newer on GitHub, not yet on F-Droid"),
    /** Neither answer could be read. */
    FAILED(5, "check failed"),
    NOT_CHECKED(6, "not checked yet"),
    /** Not installed, or its version couldn't be read. */
    UNKNOWN(7, "couldn't compare"),
}

/** [verdict], and the version to install when there is one ([newest]; for NOT_ON_FDROID_YET, the developers' newer one). */
data class TermuxNews(val verdict: TermuxUpdateVerdict, val newest: String?)
