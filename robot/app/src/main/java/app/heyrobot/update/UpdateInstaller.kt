// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import app.heyrobot.core.RLog
import app.heyrobot.core.hex
import app.heyrobot.core.update.ApkFacts
import app.heyrobot.core.update.InstallOutcome
import app.heyrobot.core.update.UpdateFail
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

// MAIN PROCESS. What Android says about an APK (its package, versions and signing certificates), and the install itself: a
// PackageInstaller session that Robot writes the checked APK into, hashing it again on the way and committing only if it is
// still the file SHA256SUMS vouched for. Android's own window asks you to confirm, unless "Install updates without asking"
// is on and Android allows it (Robot updating itself, Android 12 and later, with no other app store keeping Robot's updates).

object ApkInspector {
    /** The installed Robot. */
    fun installed(ctx: Context): ApkFacts =
        facts(ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())))

    /** [apk] as Android reads it (verifying its signature), or null when Android can't read it as an app. */
    fun archive(ctx: Context, apk: File): ApkFacts? = try {
        ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))?.let(::facts)
    } catch (t: Throwable) {
        RLog.w("update_archive", t)
        null
    }

    private fun facts(p: PackageInfo): ApkFacts {
        val si = p.signingInfo
        val multiple = si?.hasMultipleSigners() == true
        val history = if (si != null && !multiple) si.signingCertificateHistory?.map(::digest).orEmpty() else emptyList()
        val current = si?.apkContentsSigners?.map(::digest)?.takeIf { it.isNotEmpty() } ?: listOfNotNull(history.lastOrNull())
        return ApkFacts(p.packageName, p.longVersionCode, p.versionName, current, history, multiple)
    }

    private fun digest(s: Signature): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}

object UpdateInstaller {
    const val ACTION_STATUS = "app.heyrobot.update.INSTALL_STATUS"
    const val EXTRA_VERSION = "robot_update_version"
    private const val REQ_STATUS = 7_801

    /** Settings › Apps › Special app access › Install unknown apps › Robot is on. */
    fun allowed(ctx: Context): Boolean = try { ctx.packageManager.canRequestPackageInstalls() } catch (_: Throwable) { false }

    /** Android's page for that switch, for Robot. */
    fun allowIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The app store that keeps Robot's updates (Android 14 update ownership), if one other than Robot does: then Android asks
     * you to confirm every update Robot installs, switch or not.
     */
    fun otherUpdateOwner(ctx: Context): String? = try {
        ctx.packageManager.getInstallSourceInfo(ctx.packageName).updateOwnerPackageName?.takeIf { it != ctx.packageName }
    } catch (_: Throwable) { null }

    /**
     * Installs [apk], which must still hash to [sha256] (checked while it is copied into the session; the session is
     * abandoned otherwise). Returns null once the session is committed (its result comes to [InstallStatusReceiver]), or why
     * not. Blocking.
     */
    fun install(ctx: Context, apk: File, sha256: String, version: String, withoutAsking: Boolean): UpdateFail? {
        val pi = ctx.packageManager.packageInstaller
        // A session left over from an earlier try (cancelled, or Robot was stopped) goes first.
        for (s in pi.mySessions) if (s.appPackageName == ctx.packageName) try { pi.abandonSession(s.sessionId) } catch (_: Throwable) {}
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(if (withoutAsking) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = try { pi.createSession(params) } catch (t: Throwable) {
            RLog.w("update_session", t)
            return UpdateFail.STORAGE
        }
        try {
            pi.openSession(id).use { session ->
                val md = MessageDigest.getInstance("SHA-256")
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    FileInputStream(apk).use { input ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                if (hex(md.digest()) != sha256) {
                    session.abandon()
                    return UpdateFail.CHECKSUM_MISMATCH
                }
                val status = Intent(ctx, InstallStatusReceiver::class.java).setAction(ACTION_STATUS).putExtra(EXTRA_VERSION, version)
                // Mutable so Android can add the result; explicit (this app's own receiver, not exported).
                val sender = PendingIntent.getBroadcast(ctx, REQ_STATUS, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(sender.intentSender)
            }
            RLog.i("update_install", "op" to RLog.Code("committed"), "withoutAsking" to withoutAsking)
            return null
        } catch (t: Throwable) {
            RLog.w("update_install", t)
            try { pi.abandonSession(id) } catch (_: Throwable) {}
            return if (t is java.io.IOException && t.message?.contains("ENOSPC") == true) UpdateFail.NO_SPACE else UpdateFail.STORAGE
        }
    }

    /** Robot has a window on screen: Android lets it open the confirmation window itself. */
    internal fun inFront(): Boolean = try {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (_: Throwable) { false }

    /**
     * The confirmation Android asks Robot to show: only one of the system's own windows (the package installer), opened
     * without any permission to Robot's files.
     */
    internal fun confirmIntent(ctx: Context, i: Intent?): Intent? {
        i ?: return null
        val info = try { ctx.packageManager.resolveActivity(i, 0)?.activityInfo } catch (_: Throwable) { null } ?: return null
        if (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) return null
        i.flags = i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * The install session's result (MAIN PROCESS; not exported: only Android, through Robot's own PendingIntent, sends it). Android
 * asking for your confirmation opens its window, or, when Robot isn't on screen, a notification that opens it. A successful
 * update ends this process; the new Robot hears of it through MY_PACKAGE_REPLACED (boot/UpdatedReceiver).
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val version = intent.getStringExtra(UpdateInstaller.EXTRA_VERSION)
        RLog.i("update_status", "status" to status)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("UnsafeIntentLaunch")
            val confirm = UpdateInstaller.confirmIntent(ctx, intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            if (confirm == null) {
                Updater.onInstallResult(ctx.applicationContext, InstallOutcome.FAILED, version)
                return
            }
            val shown = UpdateInstaller.inFront() && try { ctx.startActivity(confirm); true } catch (t: Throwable) { RLog.w("update_confirm", t); false }
            if (!shown) UpdateNotices.confirm(ctx, confirm)
            return
        }
        Updater.onInstallResult(ctx.applicationContext, InstallOutcome.of(status), version)
    }
}
