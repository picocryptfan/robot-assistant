// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.content.Context
import android.net.Network
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.BuildConfig
import app.heyrobot.core.Abort
import app.heyrobot.core.RLog
import app.heyrobot.core.hex
import app.heyrobot.core.update.ArchiveCheck
import app.heyrobot.core.update.CheckOutcome
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.GitHubReleases
import app.heyrobot.core.update.InstallOutcome
import app.heyrobot.core.update.Notice
import app.heyrobot.core.update.RobotVersion
import app.heyrobot.core.update.Sha256Sums
import app.heyrobot.core.update.UpdateDecision
import app.heyrobot.core.update.UpdateFail
import app.heyrobot.core.update.UpdateFilesNames
import app.heyrobot.core.update.UpdatePick
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateState
import app.heyrobot.core.update.UpdateText
import app.heyrobot.core.update.VerifiedDownload
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

// MAIN PROCESS. The updater: checks (the daily job, or "Check now"), downloads (the Wi-Fi job, or Download), checks the file
// (SHA256SUMS, then what Android says about the APK: package, version, signer) and installs it (Install). One of these at a
// time. Every decision is core's (app.heyrobot.core.update); this does what they say. Logs carry codes and numbers only.

object Updater {
    /** What the updater is doing now, for the Updates page. */
    sealed interface Work {
        data object Checking : Work
        data class Downloading(val done: Long, val total: Long) : Work
        data object Verifying : Work
        data object Installing : Work
    }

    val work: MutableState<Work?> = mutableStateOf(null)

    /** What the page should say about what you last asked for (null: nothing new). */
    val message: MutableState<String?> = mutableStateOf(null)

    private val running = AtomicBoolean(false)
    @Volatile private var abort: Abort? = null

    /** This Robot's version (BuildConfig; UpdaterTest checks it parses). */
    fun installed(): RobotVersion = requireNotNull(RobotVersion.parse(BuildConfig.VERSION_NAME))

    // ---- What you ask for on the Updates page: each on its own thread, one at a time.

    /** "Check now": its result is the page's last-check line (a failure included), so nothing more to say. */
    fun checkNow(ctx: Context) = launch(Work.Checking) { a -> check(ctx, a, null); null }

    fun downloadNow(ctx: Context) = launch(Work.Downloading(0, 0)) { a -> download(ctx, a, null)?.let { UpdateText.fail(it) } }

    fun installNow(ctx: Context) = launch(Work.Verifying) { install(ctx) }

    /** Stop: a download keeps what it has. */
    fun cancel() {
        abort?.cancel()
    }

    private fun launch(first: Work, job: (Abort) -> String?): Boolean {
        if (!running.compareAndSet(false, true)) {
            message.value = UpdateText.fail(UpdateFail.BUSY)
            return false
        }
        val a = Abort()
        abort = a
        work.value = first
        message.value = null
        Thread({
            try {
                message.value = job(a)
            } catch (t: Throwable) {
                RLog.w("update_work", t)
                message.value = UpdateText.fail(UpdateFail.SERVICE)
            } finally {
                work.value = null
                abort = null
                running.set(false)
            }
        }, "robot-update").start()
        return true
    }

    // ---- The jobs.

    /** The daily check (UpdateJobService). Blocking. */
    fun autoCheck(ctx: Context, a: Abort, network: Network?) {
        val s = UpdateStore.settings(ctx)
        if (!UpdateDecision.autoCheckRuns(s) || !running.compareAndSet(false, true)) return
        abort = a
        try {
            if (check(ctx, a, network) != null) return
            val st = UpdateStore.state(ctx)
            val found = UpdateDecision.offered(st, UpdateStore.repo(ctx), installed()) ?: return
            val v = found.parsedVersion ?: return
            val plan = UpdateDecision.afterAutoCheck(s, v, ready(ctx, st) != null, st.notified, st.firstSeen, System.currentTimeMillis(),
                refused = st.refusedApk == found.apk.id)
            RLog.i("update_auto", "notify" to plan.notify, "wifi" to plan.downloadOnWifi)
            if (plan.downloadOnWifi) UpdateJobs.downloadOnWifi(ctx, found.apk.size)
            plan.notify?.let { notify(ctx, found, it) }
        } finally {
            abort = null
            running.set(false)
        }
    }

    /** The download on Wi-Fi (UpdateJobService). True: run it again later (stopped or cut off). Blocking. */
    fun autoDownload(ctx: Context, a: Abort, network: Network?): Boolean {
        val s = UpdateStore.settings(ctx)
        if (!s.autoCheck || !s.wifiDownload) return false
        if (!running.compareAndSet(false, true)) return true
        abort = a
        work.value = Work.Downloading(0, 0)
        try {
            val fail = download(ctx, a, network)
            if (UpdateDecision.downloadAgain(fail)) return true
            val st = UpdateStore.state(ctx)
            val found = UpdateDecision.offered(st, UpdateStore.repo(ctx), installed()) ?: return false
            val v = found.parsedVersion ?: return false
            if (fail == null) {
                UpdateDecision.afterAutoDownload(s, v, st.notified)?.let { notify(ctx, found, it) }
            } else if (st.refusedApk == found.apk.id && st.refusedWhy?.let(UpdateDecision::bars) != true) {
                // Its file failed a check: say there is an update; the page says what was wrong. Not for a file Android's
                // checks refused: it can never be installed, and the next check passes it over for an older one that can.
                UpdateDecision.afterAutoCheck(s, v, false, st.notified, st.firstSeen, System.currentTimeMillis(), refused = true).notify?.let { notify(ctx, found, it) }
            }
            return false
        } finally {
            work.value = null
            abort = null
            running.set(false)
        }
    }

    private fun notify(ctx: Context, found: FoundRelease, n: Notice) {
        val v = found.parsedVersion ?: return
        val posted = when (n) {
            Notice.AVAILABLE -> UpdateNotices.available(ctx, found.version)
            Notice.READY -> UpdateNotices.ready(ctx, found.version)
        }
        RLog.i("update_notice", "notice" to n, "posted" to posted)
        if (posted) UpdateStore.update(ctx) { it.copy(notified = UpdateDecision.noticeKey(v, n)) }
    }

    // ---- The steps.

    /** Asks GitHub, picks the release to offer and records what it found. The failure, or null. Blocking. */
    internal fun check(ctx: Context, a: Abort, network: Network?): UpdateFail? {
        val s = UpdateStore.settings(ctx)
        val now = System.currentTimeMillis()
        val repo = UpdateStore.repo(ctx)
        try {
            if (repo == null) throw GitHubClient.Failure(UpdateFail.NO_REPOSITORY)
            val list = GitHubReleases.parseList(GitHubClient.releases(ctx, repo, a, network)) ?: throw GitHubClient.Failure(UpdateFail.BAD_ANSWER)
            // A release whose APK failed Android's checks before is passed over, so it can't hide an older one that works.
            val pick = UpdatePick.candidate(list, installed(), s.testBuilds, UpdateStore.state(ctx).barred.toSet())
            val found = pick?.let { FoundRelease.of(repo, it.release, it.files) }
            val st = UpdateStore.update(ctx) { old ->
                val was = old.found
                val same = found != null && was != null && was.repo == found.repo && was.version == found.version && was.apk == found.apk
                old.copy(
                    lastCheck = now,
                    outcome = when {
                        found == null -> CheckOutcome.UP_TO_DATE
                        UpdateDecision.skipped(s, found.parsedVersion!!) -> CheckOutcome.SKIPPED
                        else -> CheckOutcome.AVAILABLE
                    },
                    fail = null, failHttp = 0, retryAt = null,
                    found = found,
                    firstSeen = if (found == null) 0 else if (same && old.firstSeen > 0) old.firstSeen else now,
                    downloaded = old.downloaded.takeIf { UpdateDecision.keepDownload(it, found, installed()) },
                    refusedApk = if (same) old.refusedApk else 0,
                    refusedWhy = if (same) old.refusedWhy else null,
                    barred = UpdateDecision.stillBarred(old.barred, list),
                )
            }
            tidy(ctx, st)
            RLog.i("update_check", "outcome" to st.outcome, "testBuilds" to s.testBuilds)
            return null
        } catch (f: GitHubClient.Failure) {
            RLog.i("update_check", "fail" to f.why, "http" to f.http)
            if (f.why != UpdateFail.STOPPED) {
                UpdateStore.update(ctx) { it.copy(lastCheck = now, outcome = CheckOutcome.FAILED, fail = f.why, failHttp = f.http, retryAt = f.retryAt) }
            }
            return f.why
        }
    }

    /** The checked download of the offered release, if it is there and still the one offered. */
    private fun ready(ctx: Context, st: UpdateState): VerifiedDownload? {
        val d = st.downloaded ?: return null
        if (!UpdateDecision.keepDownload(d, UpdateDecision.offered(st, UpdateStore.repo(ctx), installed()), installed())) return null
        val f = File(UpdateStore.downloads(ctx), d.file)
        return d.takeIf { f.isFile && f.length() == d.size }
    }

    /** Whether the offered release is downloaded and checked (the page shows Install). */
    fun isReady(ctx: Context): Boolean = ready(ctx, UpdateStore.state(ctx)) != null

    /**
     * Downloads the offered release, checks it against SHA256SUMS and then as Android reads it, and keeps it as the checked
     * download. The failure, or null (also when there is nothing to download). Blocking.
     */
    internal fun download(ctx: Context, a: Abort, network: Network?): UpdateFail? {
        val repo = UpdateStore.repo(ctx) ?: return UpdateFail.NO_REPOSITORY
        val st = UpdateStore.state(ctx)
        val found = UpdateDecision.offered(st, repo, installed()) ?: return null
        val v = found.parsedVersion ?: return null
        if (ready(ctx, st) != null) return null
        tidy(ctx, st)
        val dir = UpdateStore.downloads(ctx)
        val part = File(dir, UpdateFilesNames.part(found.version, found.apk.id))
        val apk = File(dir, UpdateFilesNames.apk(found.version))
        val have = if (part.isFile) part.length() else 0L
        if (dir.usableSpace < UpdateDecision.spaceNeeded(found.apk.size, have)) return UpdateFail.NO_SPACE
        fun refuse(f: UpdateFail): UpdateFail {
            part.delete()
            UpdateStore.update(ctx) {
                it.copy(refusedApk = found.apk.id, refusedWhy = f, barred = if (UpdateDecision.bars(f)) UpdateDecision.bar(it.barred, found.apk.id) else it.barred)
            }
            RLog.i("update_refused", "why" to f)
            return f
        }
        try {
            // SHA256SUMS first: without its line for this APK nothing is downloaded at all.
            val sums = Sha256Sums.parse(GitHubClient.sums(ctx, repo, found.sums, a, network)) ?: return refuse(UpdateFail.SUMS_BAD)
            val expected = sums.of(found.apk.name) ?: return refuse(UpdateFail.SUMS_NO_LINE)
            work.value = Work.Downloading(have, found.apk.size)
            try {
                GitHubClient.download(ctx, repo, found.apk, part, a, network) { work.value = Work.Downloading(it, found.apk.size) }
            } catch (f: GitHubClient.Failure) {
                // The server wouldn't continue where the file stopped: once more from the start.
                if (f.why != UpdateFail.SIZE_MISMATCH || have == 0L) throw f
                part.delete()
                GitHubClient.download(ctx, repo, found.apk, part, a, network) { work.value = Work.Downloading(it, found.apk.size) }
            }
            work.value = Work.Verifying
            if (sha256(part) != expected) return refuse(UpdateFail.CHECKSUM_MISMATCH)
            UpdateFail.of(ArchiveCheck.check(ApkInspector.installed(ctx), ApkInspector.archive(ctx, part), v))?.let { return refuse(it) }
            apk.delete()
            if (!part.renameTo(apk)) return UpdateFail.STORAGE
            UpdateStore.update(ctx) { it.copy(downloaded = VerifiedDownload(found.version, apk.name, expected, found.apk.size, found.apk.id), refusedApk = 0, refusedWhy = null) }
            RLog.i("update_download", "op" to RLog.Code("verified"), "bytes" to found.apk.size)
            return null
        } catch (f: GitHubClient.Failure) {
            RLog.i("update_download", "fail" to f.why, "http" to f.http)
            if (f.why == UpdateFail.TOO_BIG) part.delete()
            return f.why
        }
    }

    /**
     * Checks the downloaded APK again as it is now (its SHA-256, then package, version and signer) and hands it to Android.
     * What to tell you, or null once Android's window (or, without asking, the install) takes over. Blocking.
     */
    internal fun install(ctx: Context): String? {
        if (!UpdateInstaller.allowed(ctx)) return INSTALL_NOT_ALLOWED
        val st = UpdateStore.state(ctx)
        val found = UpdateDecision.offered(st, UpdateStore.repo(ctx), installed()) ?: return UpdateText.UP_TO_DATE
        val d = ready(ctx, st) ?: return "Download the update first."
        val file = File(UpdateStore.downloads(ctx), d.file)
        work.value = Work.Verifying
        val again = if (sha256(file) != d.sha256) UpdateFail.CHECKSUM_MISMATCH
            else UpdateFail.of(ArchiveCheck.check(ApkInspector.installed(ctx), ApkInspector.archive(ctx, file), found.parsedVersion!!))
        if (again != null) {
            file.delete()
            UpdateStore.update(ctx) { it.copy(downloaded = null) }
            return UpdateText.fail(again)
        }
        if (UpdateStore.downloads(ctx).usableSpace < d.size + UpdateDecision.SPACE_MARGIN) return UpdateText.fail(UpdateFail.NO_SPACE)
        work.value = Work.Installing
        UpdateInstaller.install(ctx, file, d.sha256, d.version, UpdateStore.settings(ctx).installWithoutAsking)?.let { return UpdateText.fail(it) }
        UpdateNotices.cancel(ctx)
        UpdateStore.update(ctx) { it.copy(install = null, installVersion = d.version) }
        return null
    }

    const val INSTALL_NOT_ALLOWED = "Allow Robot to install apps first (the button above)."

    /** The install session ended (InstallStatusReceiver). Success usually never arrives here: the update ends this process. */
    fun onInstallResult(ctx: Context, outcome: InstallOutcome, version: String?) {
        UpdateStore.update(ctx) { it.copy(install = outcome, installVersion = version ?: it.installVersion) }
        val text = UpdateText.install(outcome, version)
        message.value = text
        RLog.i("update_result", "outcome" to outcome)
        if (outcome != InstallOutcome.SUCCESS && outcome != InstallOutcome.CANCELLED && !UpdateInstaller.inFront()) UpdateNotices.failed(ctx, text)
    }

    // ---- Settings, skipping, and after an update.

    /** A change on the Updates page: saved, and the jobs follow. */
    fun setSettings(ctx: Context, s: UpdateSettings) {
        val old = UpdateStore.settings(ctx)
        UpdateStore.saveSettings(ctx, s)
        if (old.autoCheck != s.autoCheck || old.wifiDownload != s.wifiDownload) UpdateJobs.ensure(ctx, s)
        if (old.repo != s.repo) Thread({ tidy(ctx, UpdateStore.state(ctx)) }, "robot-update-tidy").start()
    }

    /** "Skip this version": not offered or announced again; its download goes. A newer one is offered as usual. */
    fun skip(ctx: Context, version: String) {
        setSettings(ctx, UpdateStore.settings(ctx).copy(skipped = version))
        UpdateNotices.cancel(ctx)
        UpdateJobs.cancelDownload(ctx)
        Thread({
            UpdateStore.update(ctx) { st ->
                st.copy(downloaded = st.downloaded?.takeIf { it.version != version },
                    outcome = if (st.outcome == CheckOutcome.AVAILABLE && st.found?.version == version) CheckOutcome.SKIPPED else st.outcome)
            }
            tidy(ctx, UpdateStore.state(ctx))
        }, "robot-update-skip").start()
    }

    fun unskip(ctx: Context) {
        setSettings(ctx, UpdateStore.settings(ctx).copy(skipped = null))
        Thread({
            UpdateStore.update(ctx) { st -> st.copy(outcome = if (st.outcome == CheckOutcome.SKIPPED) CheckOutcome.AVAILABLE else st.outcome) }
        }, "robot-update-skip").start()
    }

    /** The app opened (background thread): the daily check is scheduled if it should be. */
    fun onAppOpen(ctx: Context) {
        UpdateJobs.ensure(ctx, UpdateStore.settings(ctx))
        // 1.0: and Termux's weekly update check, while it is on.
        TermuxUpdateCheck.ensure(ctx)
    }

    /**
     * MY_PACKAGE_REPLACED, in the new Robot (background thread): the downloads go, what was offered and is now installed is
     * forgotten, the daily check is scheduled again, and "Robot updated to …" is said once (a notification, or in the app).
     */
    fun afterUpdate(ctx: Context) {
        val v = installed()
        UpdateStore.downloads(ctx).listFiles()?.forEach { it.delete() }
        UpdateStore.update(ctx) { st ->
            st.copy(downloaded = null, found = st.found?.takeIf { f -> (f.parsedVersion ?: v) > v }, updatedTo = v.text,
                install = null, notified = null, refusedApk = 0, refusedWhy = null,
                outcome = if (st.found?.parsedVersion?.let { it > v } == true) st.outcome else CheckOutcome.UP_TO_DATE)
        }
        UpdateJobs.ensure(ctx, UpdateStore.settings(ctx))
        UpdateNotices.cancel(ctx)
        if (UpdateNotices.updated(ctx, v.text)) UpdateStore.update(ctx) { it.copy(updatedTo = null) }
        RLog.i("update_done", "op" to RLog.Code("replaced"))
    }

    /** "Robot updated to …" for the app to show, once, when no notification said it. Background thread. */
    fun takeUpdatedNotice(ctx: Context): String? {
        val v = UpdateStore.state(ctx).updatedTo ?: return null
        UpdateStore.update(ctx) { it.copy(updatedTo = null) }
        return UpdateText.updatedTitle(v) + "."
    }

    /** Keeps only the checked download and the partial file of the release offered now; anything else in the folder goes. */
    private fun tidy(ctx: Context, st: UpdateState) {
        val found = UpdateDecision.offered(st, UpdateStore.repo(ctx), installed())
        val keep = buildSet {
            st.downloaded?.takeIf { UpdateDecision.keepDownload(it, found, installed()) }?.let { add(it.file) }
            if (found != null && !UpdateDecision.skipped(UpdateStore.settings(ctx), found.parsedVersion!!)) add(UpdateFilesNames.part(found.version, found.apk.id))
        }
        UpdateStore.downloads(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }
}
