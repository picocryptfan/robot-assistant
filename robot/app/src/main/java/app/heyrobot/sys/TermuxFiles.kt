// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.termux.TermuxCheck
import app.heyrobot.core.termux.TermuxIdentity
import app.heyrobot.core.termux.TermuxIntents
import app.heyrobot.core.termux.TermuxLock
import app.heyrobot.core.termux.TermuxPackage
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.termux.TermuxSetup
import app.heyrobot.core.termux.TermuxSetupStatus
import app.heyrobot.core.termux.TermuxSource
import app.heyrobot.core.termux.TermuxUpdateState
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Main and voice processes. The Termux agent's files (0.9): Settings ›
// Termux in files/robot/termux.json of its own (never settings.json, whose schema stays 13), written by the main app only
// and read by the :voice process before each use; and what the :voice process's set-up runs found (the Test, your
// scripts) in files/robot/termux_status.json, written by :voice only. And what Android says about the installed Termux:
// its signer, version, RunCommandService, who defines its permission, and whether Robot holds it (<queries> names
// com.termux; no QUERY_ALL_PACKAGES). 1.0: its version code and install time too (the update lock), and what the weekly
// update check found (termux_updates.json, written by the main app only).

object TermuxStore {
    const val FILE = "termux.json"

    private fun file(ctx: Context) = File(Paths.dir(ctx), FILE)
    private fun statusFile(ctx: Context) = File(Paths.dir(ctx), TermuxSetupStatus.FILE)

    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-termux-settings").apply { isDaemon = true } } }

    /** Settings › Termux; a missing or unreadable file reads as everything Off (TermuxSettings' defaults). */
    fun load(ctx: Context): TermuxSettings = try {
        TermuxSettings.fromJson(readOrNull(file(ctx))?.let(::JSONObject))
    } catch (_: Exception) { TermuxSettings() }

    /** [MAIN] Saved in order on one thread; the :voice process reads the file each time it needs it. */
    fun save(ctx: Context, s: TermuxSettings) {
        val app = ctx.applicationContext
        writer.execute { try { writeAtomic(file(app), s.toJson().toString(2)) } catch (t: Throwable) { RLog.w("termux_settings_save", t) } }
    }

    /** [MAIN] Now, on the calling thread (a test, or before a Cmd that needs it read). */
    fun saveNow(ctx: Context, s: TermuxSettings) {
        try { writeAtomic(file(ctx), s.toJson().toString(2)) } catch (t: Throwable) { RLog.w("termux_settings_save", t) }
    }

    fun status(ctx: Context): TermuxSetupStatus = TermuxSetupStatus.parse(readOrNull(statusFile(ctx)))

    /** [VOICE] What a set-up run found. */
    fun saveStatus(ctx: Context, s: TermuxSetupStatus) {
        try { writeAtomic(statusFile(ctx), s.toJson().toString(2)) } catch (t: Throwable) { RLog.w("termux_status_save", t) }
    }
}

/** 1.0: what "Check for Termux updates" last found (files/robot/termux_updates.json; written by the main app only). */
object TermuxUpdateStore {
    private fun file(ctx: Context) = File(Paths.dir(ctx), TermuxUpdateState.FILE)

    fun load(ctx: Context): TermuxUpdateState = TermuxUpdateState.parse(readOrNull(file(ctx)))

    /** [MAIN] */
    fun save(ctx: Context, s: TermuxUpdateState) {
        try { writeAtomic(file(ctx), s.toJson().toString(2)) } catch (t: Throwable) { RLog.w("termux_updates_save", t) }
    }
}

/** 1.0: the words for the update lock and Termux's sources (res/values/strings.xml), for Settings › Termux and the panel. */
object TermuxWords {
    /** What Robot says while [lock] holds: Settings › Termux's line, or ([card]) the next Termux card's, which says where to go. */
    fun lock(ctx: Context, check: TermuxCheck, lock: TermuxLock, card: Boolean = false): String = when (lock) {
        TermuxLock.UPDATED -> ctx.getString(if (card) R.string.termux_lock_updated_card else R.string.termux_lock_updated, check.version ?: "?", source(ctx, check.source))
        TermuxLock.SIGNER -> ctx.getString(R.string.termux_lock_signer)
        TermuxLock.TEST_KEY -> ctx.getString(R.string.termux_lock_test_key)
    }

    /** "F-Droid", "GitHub, public test key", … */
    fun source(ctx: Context, source: TermuxSource?): String = ctx.getString(when (source) {
        TermuxSource.F_DROID -> R.string.termux_source_fdroid
        TermuxSource.GITHUB -> R.string.termux_source_github
        TermuxSource.GOOGLE_PLAY -> R.string.termux_source_play
        TermuxSource.TERMUX_DEVS -> R.string.termux_source_devs
        TermuxSource.UNKNOWN, null -> R.string.termux_source_unknown
    })
}

object TermuxFacts {
    /** What Android says about com.termux (nothing installed: [TermuxPackage.installed] false). */
    fun pkg(ctx: Context): TermuxPackage {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(TermuxIntents.PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } catch (_: PackageManager.NameNotFoundException) { return TermuxPackage(false) } catch (t: Throwable) {
            RLog.w("termux_facts", t)
            return TermuxPackage(false)
        }
        val si = info.signingInfo
        // The current signers (several: TermuxIdentity calls it unknown).
        val signers = si?.apkContentsSigners.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        val service = try {
            pm.getServiceInfo(ComponentName(TermuxIntents.PACKAGE, TermuxIntents.RUN_COMMAND_SERVICE), PackageManager.ComponentInfoFlags.of(0)); true
        } catch (_: Exception) { false }
        val owner = try { pm.getPermissionInfo(TermuxIntents.PERMISSION_RUN_COMMAND, 0).packageName } catch (_: Exception) { null }
        // 1.0, the update lock: the version code and when Android last installed or updated it, recorded at Trust.
        return TermuxPackage(true, info.versionName, signers, service, owner, info.longVersionCode, info.lastUpdateTime)
    }

    /** The pinned certificate is in com.termux's signing history (a rotated key isn't a replacement). */
    fun stillSigns(ctx: Context, pinned: String?): Boolean {
        val bytes = TermuxSettings.sha256(pinned)?.let(::unhex) ?: return false
        return try { ctx.packageManager.hasSigningCertificate(TermuxIntents.PACKAGE, bytes, PackageManager.CERT_INPUT_SHA256) } catch (_: Exception) { false }
    }

    /** Robot holds com.termux.permission.RUN_COMMAND (you allowed it). */
    fun granted(ctx: Context): Boolean = try {
        ctx.checkSelfPermission(TermuxIntents.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /**
     * What Settings › Termux says about the installed Termux, against the signer you trusted and (1.0, the update lock)
     * the version recorded with it. Read again before every run: an update Android installed since is caught here.
     */
    fun check(ctx: Context, s: TermuxSettings): TermuxCheck = check(ctx, s, pkg(ctx))

    fun check(ctx: Context, s: TermuxSettings, p: TermuxPackage): TermuxCheck =
        TermuxIdentity.check(p, s.trustedSigner, s.trustedSigner != null && p.installed && stillSigns(ctx, s.trustedSigner), s.trusted())

    /** Rule 1: everything set up (a usable, trusted Termux, the permission, a Test that passed). */
    fun ready(ctx: Context, s: TermuxSettings): Boolean = TermuxSetup.ready(check(ctx, s), granted(ctx), TermuxStore.status(ctx), s.trustedSigner)

    /** 1.0: [ready], or set up but paused by the update lock or the test key (the tools are described, a call says why). */
    fun readyOrLocked(ctx: Context, s: TermuxSettings): Boolean {
        val c = check(ctx, s)
        val granted = granted(ctx)
        val status = TermuxStore.status(ctx)
        return TermuxSetup.ready(c, granted, status, s.trustedSigner) || TermuxSetup.readyButLocked(c, granted, status, s.trustedSigner)
    }

    /**
     * The other apps Robot can see that hold Termux's permission (allow-external-apps lets each of them run commands too).
     * Without QUERY_ALL_PACKAGES this list may be incomplete: Settings says so.
     */
    fun otherHolders(ctx: Context): List<String> = try {
        ctx.packageManager.getPackagesHoldingPermissions(arrayOf(TermuxIntents.PERMISSION_RUN_COMMAND), PackageManager.PackageInfoFlags.of(0))
            .map { it.packageName }.filter { it != ctx.packageName && it != TermuxIntents.PACKAGE }
            .map { pkg -> try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg } }
    } catch (_: Exception) { emptyList() }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun unhex(h: String): ByteArray = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
