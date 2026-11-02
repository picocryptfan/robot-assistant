// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.media.MediaBrowserService
import app.heyrobot.core.CheogramSigners
import app.heyrobot.core.RLog
import app.heyrobot.core.everyday.MusicApp
import java.security.MessageDigest

// MAIN PROCESS and :voice. Your music app (docs/plans/everyday-set-plan.md §3.4, D5, Q1): the apps that can play what you
// ask for by name, their signer's SHA-256, and whether the app you picked is still the one signed so. Settings › Actions ›
// Music (main) and the panel's card the first time (:voice) both pick from [find]; the panel checks [check] each time,
// just before it binds the app's media service.
//
// Door 1 only on this branch: the apps with a media service (android.media.browse.MediaBrowserService), found by asking
// the packages Robot already sees (their launchers, the manifest's MAIN/LAUNCHER query), so no new <queries> entry.
// Door 2 (Skills connections' C7: IntentSpec and HandOffRunner, the play-from-search screen) isn't on this base; when it
// merges, the apps that answer MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH join [find] (DOOR 2 below).
//
// Nothing here is a secret: package names, labels and digests. None of them is logged.

object MusicApps {
    /** A music app on the phone: its package, its name as Android shows it, and its media service (door 1). */
    data class Found(val pkg: String, val label: String, val service: ComponentName)

    /** What the pinned app is now. */
    enum class PinNow { OK, GONE, REPLACED }

    /** The music apps Robot can ask by name, Robot itself left out, by name. */
    fun find(ctx: Context): List<Found> {
        val pm = ctx.packageManager
        val services = try {
            pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
        } catch (t: Throwable) {
            RLog.w("music_find", t)
            emptyList()
        }
        // DOOR 2 (C7): add the packages whose activity answers MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH here.
        return services.mapNotNull { r ->
            val s = r.serviceInfo ?: return@mapNotNull null
            if (s.packageName == ctx.packageName || !s.exported) return@mapNotNull null
            Found(s.packageName, label(ctx, s.packageName), ComponentName(s.packageName, s.name))
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    /** [pkg]'s media service (door 1), or null. */
    fun service(ctx: Context, pkg: String): ComponentName? = try {
        ctx.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull { it.serviceInfo?.exported == true }?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    } catch (t: Throwable) {
        RLog.w("music_service", t)
        null
    }

    /** [f] pinned: its package, its signer's SHA-256 now and its name; null when Android gives no signer. */
    fun pin(ctx: Context, f: Found): MusicApp? {
        val sha = signer(ctx, f.pkg) ?: return null
        return if (MusicApp.valid(f.pkg, sha, f.label)) MusicApp(f.pkg, sha, f.label) else null
    }

    /**
     * Is [app] still installed, and still signed by the key you picked it with (Android's own check, so a key rotated with
     * its lineage still counts)?
     */
    fun check(ctx: Context, app: MusicApp): PinNow {
        val pm = ctx.packageManager
        try {
            pm.getPackageInfo(app.pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return PinNow.GONE
        } catch (t: Throwable) {
            RLog.w("music_check", t)
            return PinNow.GONE
        }
        val cert = CheogramSigners.unhex(app.sha256) ?: return PinNow.REPLACED
        val same = try { pm.hasSigningCertificate(app.pkg, cert, PackageManager.CERT_INPUT_SHA256) } catch (_: Throwable) { false }
        return if (same) PinNow.OK else PinNow.REPLACED
    }

    /** The SHA-256 of [pkg]'s signer now (the first of its current signers), lower-case hex. */
    internal fun signer(ctx: Context, pkg: String): String? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { CheogramSigners.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    } catch (t: Throwable) {
        RLog.w("music_signer", t)
        null
    }

    /** The app's name as Android shows it, fit for MusicApp: no control characters, at most its length; the package when it has none. */
    internal fun label(ctx: Context, pkg: String): String {
        val raw = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            ""
        }
        return raw.filter { it >= ' ' && it != '\u007f' }.trim().take(MusicApp.MAX_LABEL).ifEmpty { pkg.take(MusicApp.MAX_LABEL) }
    }
}
