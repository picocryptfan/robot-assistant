// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.update

import app.heyrobot.core.AppId
import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.UpdateStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. Which release Robot offers, what the daily check does with it, the checks an APK must pass before it is installed,
// and every message the updater shows. The app (app/update) only does what these decide.

/** The release to offer and its two files. */
data class Candidate(val release: GitHubRelease, val version: RobotVersion, val files: ReleaseFiles)

object UpdatePick {
    /**
     * The newest release above [current] that may be offered: not a draft, a test build only with [testBuilds], its tag a
     * version, and exactly its APK and SHA256SUMS attached ([ReleaseAssets.select]), that APK not [barred] (it failed
     * Android's checks before: [UpdateDecision.bars]). The highest version wins, whatever order GitHub lists them in; between
     * two tags of the same version, the one listed first (the newer). So a release that can never be installed (another
     * signer, a typo in its tag) doesn't hide an older one that can.
     */
    fun candidate(releases: List<GitHubRelease>, current: RobotVersion, testBuilds: Boolean, barred: Set<Long> = emptySet()): Candidate? {
        var best: Candidate? = null
        for (r in releases) {
            if (r.draft || (r.testBuild && !testBuilds)) continue
            val v = r.version ?: continue
            if (v <= current) continue
            val files = ReleaseAssets.select(r) ?: continue
            if (files.apk.id in barred) continue
            if (best == null || v > best.version) best = Candidate(r, v, files)
        }
        return best
    }
}

/** A notification about an update. */
enum class Notice(val id: String) { AVAILABLE("available"), READY("ready") }

/** What the daily check does after it asked GitHub: a notification (null: none), and whether to download on Wi-Fi. */
data class AutoPlan(val notify: Notice?, val downloadOnWifi: Boolean)

object UpdateDecision {
    /** A download waiting this long for Wi-Fi: the update is announced anyway. */
    const val WIFI_WAIT_MS = 48L * 3600 * 1000

    /** The daily job only asks GitHub while "Check for updates automatically" is on. */
    fun autoCheckRuns(s: UpdateSettings): Boolean = s.autoCheck

    fun skipped(s: UpdateSettings, v: RobotVersion): Boolean = s.skipped?.let(RobotVersion::parse) == v

    fun noticeKey(v: RobotVersion, n: Notice): String = "${v.text} ${n.id}"

    /**
     * After the daily check found [found] (null: nothing newer to offer). [downloaded]: that version is already downloaded
     * and checked. [notified]: the last notice posted ([noticeKey]); each is posted once. [firstSeen]: when it was first
     * found. A skipped version is never announced; with "Download updates on Wi-Fi" the notice waits until the file is
     * checked and ready, unless Wi-Fi hasn't come for [WIFI_WAIT_MS]. [refused]: its file failed a check before.
     */
    fun afterAutoCheck(s: UpdateSettings, found: RobotVersion?, downloaded: Boolean, notified: String?, firstSeen: Long, now: Long,
                       refused: Boolean = false): AutoPlan {
        if (found == null || skipped(s, found)) return AutoPlan(null, false)
        fun once(n: Notice) = n.takeIf { noticeKey(found, it) != notified }
        if (downloaded) return AutoPlan(once(Notice.READY), false)
        // Its file failed a check before: not downloaded again by itself (the page says why when you try).
        if (!s.wifiDownload || refused) return AutoPlan(once(Notice.AVAILABLE), false)
        val waited = firstSeen > 0 && now - firstSeen >= WIFI_WAIT_MS
        // "Ready" may already have been said for a file that was since deleted: then "available" is new information.
        return AutoPlan(if (waited) once(Notice.AVAILABLE) else null, true)
    }

    /**
     * Whether the download on Wi-Fi runs again soon after it failed with [f]: stopped, cut off, or the token unreadable
     * just now. Each can be different next time; the others wait for the next daily check or for you.
     */
    fun downloadAgain(f: UpdateFail?): Boolean = f == UpdateFail.STOPPED || f == UpdateFail.NO_INTERNET || f == UpdateFail.TOKEN_UNAVAILABLE

    /** After a download on Wi-Fi passed its checks: "ready to install", once (not for a version skipped meanwhile). */
    fun afterAutoDownload(s: UpdateSettings, v: RobotVersion, notified: String?): Notice? =
        Notice.READY.takeIf { !skipped(s, v) && noticeKey(v, it) != notified }

    /**
     * Whether a checked download is still the one to install: the release still offered (same version and file) and newer
     * than what is installed. Otherwise it is deleted.
     */
    fun keepDownload(d: VerifiedDownload?, found: FoundRelease?, installed: RobotVersion): Boolean {
        if (d == null || found == null) return false
        val v = RobotVersion.parse(d.version) ?: return false
        return d.version == found.version && d.apkId == found.apk.id && d.size == found.apk.size && v > installed
    }

    /** The release [state] offers for [repo] above [installed], or null (another repository's, or installed meanwhile). */
    fun offered(state: UpdateState, repo: GitHubRepo?, installed: RobotVersion): FoundRelease? {
        val f = state.found ?: return null
        val v = f.parsedVersion ?: return null
        return f.takeIf { repo != null && it.repo == repo.slug && v > installed }
    }

    /**
     * Free space a download of [size] bytes needs, [have] of it already on the phone: the rest of the file, room for Android's
     * copy of it while it installs, and a margin.
     */
    fun spaceNeeded(size: Long, have: Long): Long = (size - have).coerceAtLeast(0) + size + SPACE_MARGIN

    const val SPACE_MARGIN = 64L * 1024 * 1024

    /** At most this many barred release files are remembered (the newest). */
    const val MAX_BARRED = 16

    /**
     * Whether a release file refused for [f] is never offered again: only when Android's checks refused it (package,
     * version, signer). They ran on the very bytes SHA256SUMS vouched for, so they would fail again; a wrong sum, a cut
     * download or a full phone can be different next time.
     */
    fun bars(f: UpdateFail): Boolean = ArchiveVerdict.entries.any { UpdateFail.of(it) == f }

    /** [barred] with [apkId] added (last, as the newest), at most [MAX_BARRED]. */
    fun bar(barred: List<Long>, apkId: Long): List<Long> = (barred.filter { it != apkId } + apkId).takeLast(MAX_BARRED)

    /** The barred files GitHub still lists in [releases]; one it no longer lists is forgotten. */
    fun stillBarred(barred: List<Long>, releases: List<GitHubRelease>): List<Long> {
        val listed = releases.flatMap { r -> r.assets.map { it.id } }.toSet()
        return barred.filter { it in listed }
    }
}

/** An APK as Android describes it: package, versions and the SHA-256 of each signing certificate. */
data class ApkFacts(
    val packageName: String?,
    val versionCode: Long,
    val versionName: String?,
    /** The certificates that signed it now (one, unless it has several signers). */
    val signers: List<String>,
    /** Its signing lineage (APK Signature Scheme v3 rotation), oldest first, the current one last; empty without one. */
    val lineage: List<String>,
    val multipleSigners: Boolean,
)

enum class ArchiveVerdict { OK, NOT_AN_APK, WRONG_PACKAGE, WRONG_VERSION, NOT_NEWER, UNSIGNED, OTHER_SIGNER }

object ArchiveCheck {
    /**
     * 1.0: [AppId.PACKAGE]. Robot 0.9 (app.heyrobot) refuses 1.0's APK as WRONG_PACKAGE ("The downloaded app isn't Robot, so
     * it won't be installed.") and bars it; 0.9 can't be changed, so README's "Moving from 0.9 to 1.0" and the release
     * notes, which 0.9 shows before it downloads, say to install 1.0 beside it.
     */
    const val PACKAGE = AppId.PACKAGE

    /**
     * The downloaded APK ([apk], null when Android couldn't read it) against the installed Robot: the same package, the
     * version the release says ([expected]), a higher versionCode (never a downgrade or the same one again), and signed by
     * the key the installed Robot is signed with now, or by a newer key whose v3 lineage includes it (a rotated key): a
     * lineage that ends at the APK's own signer, each step signed by the key before it (Android verifies those signatures
     * when it reads the APK, so only the holder of Robot's key can put a new key after it). Several signers must match
     * exactly. Android refuses another signer anyway, and also a lineage that took the old key's right to update installed
     * data away, which Android doesn't show apps: this says why first in every other case.
     */
    fun check(installed: ApkFacts, apk: ApkFacts?, expected: RobotVersion): ArchiveVerdict {
        if (apk == null || apk.packageName == null) return ArchiveVerdict.NOT_AN_APK
        if (apk.packageName != PACKAGE || installed.packageName != PACKAGE) return ArchiveVerdict.WRONG_PACKAGE
        if (RobotVersion.parse(apk.versionName) != expected) return ArchiveVerdict.WRONG_VERSION
        if (apk.versionCode <= installed.versionCode) return ArchiveVerdict.NOT_NEWER
        if (apk.signers.isEmpty() || installed.signers.isEmpty()) return ArchiveVerdict.UNSIGNED
        if (apk.multipleSigners || installed.multipleSigners || apk.signers.size > 1 || installed.signers.size > 1) {
            return if (apk.signers.toSet() == installed.signers.toSet()) ArchiveVerdict.OK else ArchiveVerdict.OTHER_SIGNER
        }
        val now = installed.signers.single()
        val signer = apk.signers.single()
        val rotated = apk.lineage.lastOrNull() == signer && now in apk.lineage
        return if (signer == now || rotated) ArchiveVerdict.OK else ArchiveVerdict.OTHER_SIGNER
    }
}

/** Why a check, download or install didn't happen. */
enum class UpdateFail {
    NO_NETWORK_PERMISSION, NO_INTERNET, RATE_LIMITED, NOT_FOUND_NO_TOKEN, NOT_FOUND, TOKEN_REFUSED, FORBIDDEN, SERVICE,
    BAD_ANSWER, TOO_BIG, REDIRECT_REFUSED, NO_REPOSITORY, NO_SPACE, STORAGE, SUMS_BAD, SUMS_NO_LINE, CHECKSUM_MISMATCH,
    SIZE_MISMATCH, NOT_AN_APK, WRONG_PACKAGE, WRONG_VERSION, NOT_NEWER, UNSIGNED, OTHER_SIGNER, STOPPED, BUSY,
    /** A private repository, and the saved token couldn't be read just now: it is kept, and the next check tries again. */
    TOKEN_UNAVAILABLE,
    ;

    companion object {
        fun of(v: ArchiveVerdict): UpdateFail? = when (v) {
            ArchiveVerdict.OK -> null
            ArchiveVerdict.NOT_AN_APK -> NOT_AN_APK
            ArchiveVerdict.WRONG_PACKAGE -> WRONG_PACKAGE
            ArchiveVerdict.WRONG_VERSION -> WRONG_VERSION
            ArchiveVerdict.NOT_NEWER -> NOT_NEWER
            ArchiveVerdict.UNSIGNED -> UNSIGNED
            ArchiveVerdict.OTHER_SIGNER -> OTHER_SIGNER
        }
    }
}

/** How an install session ended (PackageInstaller's status codes, which Android documents as stable). */
enum class InstallOutcome {
    SUCCESS, CANCELLED, CONFLICT, STORAGE, INCOMPATIBLE, INVALID, BLOCKED, TIMEOUT, FAILED;

    companion object {
        // PackageInstaller.STATUS_*: the app's UpdateTextTest checks these against the SDK.
        const val STATUS_PENDING_USER_ACTION = -1
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILURE = 1
        const val STATUS_FAILURE_BLOCKED = 2
        const val STATUS_FAILURE_ABORTED = 3
        const val STATUS_FAILURE_INVALID = 4
        const val STATUS_FAILURE_CONFLICT = 5
        const val STATUS_FAILURE_STORAGE = 6
        const val STATUS_FAILURE_INCOMPATIBLE = 7
        const val STATUS_FAILURE_TIMEOUT = 8

        fun of(status: Int): InstallOutcome = when (status) {
            STATUS_SUCCESS -> SUCCESS
            STATUS_FAILURE_ABORTED -> CANCELLED
            STATUS_FAILURE_CONFLICT -> CONFLICT
            STATUS_FAILURE_STORAGE -> STORAGE
            STATUS_FAILURE_INCOMPATIBLE -> INCOMPATIBLE
            STATUS_FAILURE_INVALID -> INVALID
            STATUS_FAILURE_BLOCKED -> BLOCKED
            STATUS_FAILURE_TIMEOUT -> TIMEOUT
            else -> FAILED
        }
    }
}

/**
 * Every word the updater shows, in one place. "You" is the person holding the phone. 1.1: the sentences come from core's
 * catalog (UpdateStrings): the `…Text` functions give them without a language yet, the others render them in a language
 * ([Lang.EN] by default, 1.0's words; the app passes the menus'), with times and dates in that language's way ("14:05",
 * « 14 h 05 »). Its String functions (fail, whenText, lastCheck, published, size) stay: English keeps 1.0's "09:05" and
 * "12 Oct", where Fmt's typed time and date arguments write "9:05" and "Monday October 12".
 */
object UpdateText {
    fun availableTitleText(v: String): Phrase = UpdateStrings.availableTitle("version" to v)
    fun readyTitleText(v: String): Phrase = UpdateStrings.readyTitle("version" to v)
    val availableText: Phrase get() = UpdateStrings.availableText("page" to UiStrings.inSettings(UiStrings.updates))
    val readyText: Phrase get() = UpdateStrings.readyText("page" to UiStrings.inSettings(UiStrings.updates))
    fun updatedTitleText(v: String): Phrase = UpdateStrings.updatedTitle("version" to v)

    /** "Robot updated to 1.1.0.": said once in the app when no notification said it. */
    fun updatedLine(v: String): Phrase = UpdateStrings.updatedLine("version" to v)

    /** One line for a failure; [retryAt] (a rate limit's end) in [zone]; in [lang]. */
    fun fail(f: UpdateFail, retryAt: Long? = null, zone: ZoneId = ZoneId.systemDefault(), lang: Lang = Lang.EN): String =
        if (f == UpdateFail.RATE_LIMITED && retryAt != null) UpdateStrings.failRateLimitedAt("time" to time(retryAt, zone, lang)).render(Langs.of(lang))
        else failText(f).render(Langs.of(lang))

    /** A failure's line without a language yet (a rate limit says "in an hour"). */
    fun failText(f: UpdateFail): Phrase = when (f) {
        UpdateFail.NO_NETWORK_PERMISSION -> UpdateStrings.failNoNetwork("path" to AndroidPath.APP_PERMISSIONS)
        UpdateFail.NO_INTERNET -> UpdateStrings.failNoInternet()
        UpdateFail.RATE_LIMITED -> UpdateStrings.failRateLimited()
        UpdateFail.NOT_FOUND_NO_TOKEN -> UpdateStrings.failNotFoundNoToken()
        UpdateFail.NOT_FOUND -> UpdateStrings.failNotFound()
        UpdateFail.TOKEN_REFUSED -> UpdateStrings.failTokenRefused()
        UpdateFail.FORBIDDEN -> UpdateStrings.failForbidden()
        UpdateFail.SERVICE -> UpdateStrings.failService()
        UpdateFail.BAD_ANSWER -> UpdateStrings.failBadAnswer()
        UpdateFail.TOO_BIG -> UpdateStrings.failTooBig()
        UpdateFail.REDIRECT_REFUSED -> UpdateStrings.failRedirectRefused()
        UpdateFail.NO_REPOSITORY -> UpdateStrings.failNoRepository()
        UpdateFail.NO_SPACE -> UpdateStrings.failNoSpace()
        UpdateFail.STORAGE -> UpdateStrings.failStorage()
        UpdateFail.SUMS_BAD -> UpdateStrings.failSumsBad()
        UpdateFail.SUMS_NO_LINE -> UpdateStrings.failSumsNoLine()
        UpdateFail.CHECKSUM_MISMATCH -> UpdateStrings.failChecksum()
        UpdateFail.SIZE_MISMATCH -> UpdateStrings.failSize()
        UpdateFail.NOT_AN_APK -> UpdateStrings.failNotAnApk()
        UpdateFail.WRONG_PACKAGE -> UpdateStrings.failWrongPackage()
        UpdateFail.WRONG_VERSION -> UpdateStrings.failWrongVersion()
        UpdateFail.NOT_NEWER -> UpdateStrings.failNotNewer()
        UpdateFail.UNSIGNED -> UpdateStrings.failUnsigned()
        UpdateFail.OTHER_SIGNER -> UpdateStrings.failOtherSigner()
        UpdateFail.STOPPED -> UpdateStrings.failStopped()
        UpdateFail.BUSY -> UpdateStrings.failBusy()
        UpdateFail.TOKEN_UNAVAILABLE -> UpdateStrings.failTokenUnavailable()
    }

    /** How an install ended, without a language yet. */
    fun installText(o: InstallOutcome, v: String?): Phrase = when (o) {
        InstallOutcome.SUCCESS -> if (v != null) UpdateStrings.installed("version" to v) else UpdateStrings.installedNoVersion()
        InstallOutcome.CANCELLED -> UpdateStrings.installCancelled()
        InstallOutcome.CONFLICT -> UpdateStrings.installConflict()
        InstallOutcome.STORAGE -> UpdateStrings.installStorage()
        InstallOutcome.INCOMPATIBLE -> UpdateStrings.installIncompatible()
        InstallOutcome.INVALID -> UpdateStrings.installInvalid()
        InstallOutcome.BLOCKED -> UpdateStrings.installBlocked()
        InstallOutcome.TIMEOUT -> UpdateStrings.installTimeout()
        InstallOutcome.FAILED -> UpdateStrings.installFailed()
    }

    /** "Today at 14:05", "Yesterday at 09:10", "12 Oct at 14:05"; « Aujourd'hui à 14 h 05 », « lundi 12 octobre à 14 h 05 ». */
    fun whenText(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), lang: Lang = Lang.EN): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day: Phrase = when (t.toLocalDate()) {
            today -> UpdateStrings.today()
            today.minusDays(1) -> UpdateStrings.yesterday()
            else -> Phrase.plain(if (lang == Lang.FR) Fmt.day(t.toLocalDate(), lang) else t.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)))
        }
        return UpdateStrings.dayAt("day" to day, "time" to time(ms, zone, lang)).render(Langs.of(lang))
    }

    /** "Last checked today at 14:05. You're up to date." */
    fun lastCheck(s: UpdateState, now: Long, zone: ZoneId = ZoneId.systemDefault(), lang: Lang = Lang.EN): String {
        val l = Langs.of(lang)
        if (s.lastCheck <= 0) return UpdateStrings.neverChecked().render(l)
        val result: String? = when (s.outcome) {
            CheckOutcome.UP_TO_DATE -> UpdateStrings.upToDate().render(l)
            CheckOutcome.AVAILABLE -> (s.found?.let { UpdateStrings.availableLine("version" to it.version) } ?: UpdateStrings.availableAny()).render(l)
            CheckOutcome.SKIPPED -> (s.found?.let { UpdateStrings.skippedLine("version" to it.version) } ?: UpdateStrings.skippedAny()).render(l)
            CheckOutcome.FAILED -> s.fail?.let { fail(it, s.retryAt, zone, lang) } ?: UpdateStrings.checkFailed().render(l)
            null -> null
        }
        val at = whenText(s.lastCheck, now, zone, lang).replaceFirstChar { it.lowercase() }
        return (if (result == null) UpdateStrings.lastCheckedOnly("when" to at) else UpdateStrings.lastChecked("when" to at, "result" to result)).render(l)
    }

    /** "12 October 2026" (« lundi 12 octobre 2026 ») from GitHub's ISO time, or null. */
    fun published(iso: String?, zone: ZoneId = ZoneId.systemDefault(), lang: Lang = Lang.EN): String? = try {
        iso?.let {
            val d = Instant.parse(it).atZone(zone)
            if (lang == Lang.FR) Fmt.day(d.toLocalDate(), lang) + " " + d.year else d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
        }
    } catch (_: Exception) { null }

    /** "152 MB", "900 KB"; « 152 Mo », « 900 ko ». */
    fun size(bytes: Long, lang: Lang = Lang.EN): String = when {
        lang == Lang.FR -> Fmt.bytes(bytes, lang)
        bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes >= 1_000 -> "${(bytes + 500) / 1_000} KB"
        else -> UpdateStrings.bytes(bytes).en()
    }

    /** "14:05"; « 14 h 05 » in French. */
    private fun time(ms: Long, zone: ZoneId, lang: Lang): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return if (lang == Lang.FR) Fmt.clock(t.toLocalTime(), lang) else t.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    }
}
