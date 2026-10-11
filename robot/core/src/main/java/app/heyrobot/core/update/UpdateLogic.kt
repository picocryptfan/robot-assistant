package app.heyrobot.core.update

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
     * version, and exactly its APK and SHA256SUMS attached ([ReleaseAssets.select]). The highest version wins, whatever
     * order GitHub lists them in; between two tags of the same version, the one listed first (the newer).
     */
    fun candidate(releases: List<GitHubRelease>, current: RobotVersion, testBuilds: Boolean): Candidate? {
        var best: Candidate? = null
        for (r in releases) {
            if (r.draft || (r.testBuild && !testBuilds)) continue
            val v = r.version ?: continue
            if (v <= current) continue
            val files = ReleaseAssets.select(r) ?: continue
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
    const val PACKAGE = "app.heyrobot"

    /**
     * The downloaded APK ([apk], null when Android couldn't read it) against the installed Robot: the same package, the
     * version the release says ([expected]), a higher versionCode (never a downgrade or the same one again), and signed by
     * the key the installed Robot is signed with now, or by a newer key whose v3 lineage includes it (a rotated key).
     * Several signers must match exactly. Android refuses another signer anyway; this says why first.
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
        return if (apk.signers.single() == now || now in apk.lineage) ArchiveVerdict.OK else ArchiveVerdict.OTHER_SIGNER
    }
}

/** Why a check, download or install didn't happen. */
enum class UpdateFail {
    NO_NETWORK_PERMISSION, NO_INTERNET, RATE_LIMITED, NOT_FOUND_NO_TOKEN, NOT_FOUND, TOKEN_REFUSED, FORBIDDEN, SERVICE,
    BAD_ANSWER, TOO_BIG, REDIRECT_REFUSED, NO_REPOSITORY, NO_SPACE, STORAGE, SUMS_BAD, SUMS_NO_LINE, CHECKSUM_MISMATCH,
    SIZE_MISMATCH, NOT_AN_APK, WRONG_PACKAGE, WRONG_VERSION, NOT_NEWER, UNSIGNED, OTHER_SIGNER, STOPPED, BUSY,
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

/** Every word the updater shows, in one place. "You" is the person holding the phone. */
object UpdateText {
    const val CHANNEL_NAME = "Updates"
    const val CHANNEL_DESCRIPTION = "Tells you when a new version of Robot is available or ready to install."
    const val UP_TO_DATE = "You're up to date."
    const val NEVER_CHECKED = "Not checked yet"

    fun availableTitle(v: String) = "Robot $v is available"
    fun readyTitle(v: String) = "Robot $v is ready to install"
    const val AVAILABLE_TEXT = "Tap to see what's new in Settings › Updates."
    const val READY_TEXT = "Downloaded and checked. Tap to install it from Settings › Updates."
    fun updatedTitle(v: String) = "Robot updated to $v"
    const val UPDATED_TEXT = "Robot restarted its services after the update."
    const val CONFIRM_TITLE = "Finish updating Robot"
    const val CONFIRM_TEXT = "Tap to confirm the update in Android's window."

    /** One line for a failure; [retryAt] (a rate limit's end) in [zone]. */
    fun fail(f: UpdateFail, retryAt: Long? = null, zone: ZoneId = ZoneId.systemDefault()): String = when (f) {
        UpdateFail.NO_NETWORK_PERMISSION -> "Robot's Network permission is off. Turn it on in Settings › Apps › Robot › Permissions to check for updates."
        UpdateFail.NO_INTERNET -> "No internet. Robot will try again later."
        UpdateFail.RATE_LIMITED -> "GitHub's limit on checks from your network was reached (60 an hour without a token)." +
            (retryAt?.let { " Try again after ${time(it, zone)}." } ?: " Try again in an hour.")
        UpdateFail.NOT_FOUND_NO_TOKEN -> "GitHub didn't find the repository. If it's private, add an access token in Advanced below; otherwise check its name."
        UpdateFail.NOT_FOUND -> "GitHub didn't find the repository or its file. Check the repository's name and that the token can read it."
        UpdateFail.TOKEN_REFUSED -> "GitHub refused the access token. It may have expired: add a new one, or remove it for a public repository."
        UpdateFail.FORBIDDEN -> "GitHub refused the request. If you use an access token, check that it can read this repository's contents."
        UpdateFail.SERVICE -> "GitHub didn't answer properly. Robot will try again later."
        UpdateFail.BAD_ANSWER -> "GitHub's answer couldn't be read. Robot will try again later."
        UpdateFail.TOO_BIG -> "The file is larger than Robot accepts, so it wasn't downloaded."
        UpdateFail.REDIRECT_REFUSED -> "GitHub sent the download somewhere Robot doesn't download from, so it stopped."
        UpdateFail.NO_REPOSITORY -> "No repository to check. Set one in Advanced below."
        UpdateFail.NO_SPACE -> "Not enough free space for the update. Free some space and try again."
        UpdateFail.STORAGE -> "The update couldn't be saved on this phone."
        UpdateFail.SUMS_BAD -> "The release's SHA256SUMS file is missing or damaged, so Robot won't install this update."
        UpdateFail.SUMS_NO_LINE -> "The release's SHA256SUMS file doesn't list the APK, so Robot won't install this update."
        UpdateFail.CHECKSUM_MISMATCH -> "The downloaded file doesn't match its SHA-256 in SHA256SUMS, so Robot deleted it and won't install it."
        UpdateFail.SIZE_MISMATCH -> "The download didn't have the size GitHub gave for it. Try again."
        UpdateFail.NOT_AN_APK -> "The downloaded file isn't an app Android can read, so Robot won't install it."
        UpdateFail.WRONG_PACKAGE -> "The downloaded app isn't Robot, so it won't be installed."
        UpdateFail.WRONG_VERSION -> "The downloaded app's version isn't the release's, so it won't be installed."
        UpdateFail.NOT_NEWER -> "The downloaded app isn't newer than the Robot you have, so it won't be installed."
        UpdateFail.UNSIGNED -> "The downloaded app isn't signed, so it won't be installed."
        UpdateFail.OTHER_SIGNER -> "The downloaded app isn't signed with Robot's key, so it won't be installed. Android would refuse it too."
        UpdateFail.STOPPED -> "Stopped. Robot keeps what it downloaded and continues next time."
        UpdateFail.BUSY -> "Robot is already working on the update."
    }

    fun install(o: InstallOutcome, v: String?): String = when (o) {
        InstallOutcome.SUCCESS -> if (v != null) "Robot $v is installed." else "The update is installed."
        InstallOutcome.CANCELLED -> "The update was cancelled. Robot stays as it is."
        InstallOutcome.CONFLICT -> "Android refused the update: it conflicts with the Robot installed (another key, or another copy of Robot)."
        InstallOutcome.STORAGE -> "Not enough storage to install the update. Free some space and try again."
        InstallOutcome.INCOMPATIBLE -> "Android says this update isn't compatible with this phone."
        InstallOutcome.INVALID -> "Android found the update's file invalid, so it wasn't installed."
        InstallOutcome.BLOCKED -> "Android blocked the update (a policy or another app store keeps Robot's updates)."
        InstallOutcome.TIMEOUT -> "The update took too long and Android stopped it. Try again."
        InstallOutcome.FAILED -> "The update couldn't be installed. Try again."
    }

    /** "Today at 14:05", "Yesterday at 09:10", "12 Oct at 14:05". */
    fun whenText(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (t.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        }
        return "$day at ${time(ms, zone)}"
    }

    /** "Last checked today at 14:05: You're up to date." */
    fun lastCheck(s: UpdateState, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (s.lastCheck <= 0) return NEVER_CHECKED
        val result = when (s.outcome) {
            CheckOutcome.UP_TO_DATE -> UP_TO_DATE
            CheckOutcome.AVAILABLE -> s.found?.let { "Robot ${it.version} is available." } ?: "An update is available."
            CheckOutcome.SKIPPED -> s.found?.let { "Robot ${it.version} is available (you skipped it)." } ?: "You skipped the latest version."
            CheckOutcome.FAILED -> s.fail?.let { fail(it, s.retryAt, zone) } ?: "The check didn't work."
            null -> ""
        }
        return ("Last checked " + whenText(s.lastCheck, now, zone).replaceFirstChar { it.lowercase() } + ". " + result).trim()
    }

    /** "12 October 2026" from GitHub's ISO time, or null. */
    fun published(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? = try {
        iso?.let { Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)) }
    } catch (_: Exception) { null }

    /** "152 MB", "900 KB". */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes >= 1_000 -> "${(bytes + 500) / 1_000} KB"
        else -> "$bytes bytes"
    }

    private fun time(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
}
