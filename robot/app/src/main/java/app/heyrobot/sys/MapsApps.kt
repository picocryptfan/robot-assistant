// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.heyrobot.core.RLog
import app.heyrobot.core.car.AppPin
import app.heyrobot.core.car.MapsChoice
import app.heyrobot.core.car.MapsPlan
import app.heyrobot.core.everyday.MapsApp
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.lock.LockLimits

// MAIN PROCESS and :voice. Your maps app (docs/pm/handoff-car-media-maps.md): Settings › Actions › Maps, Automatic
// (Organic Maps when it's installed, else the phone's default app for map links) or an app you picked, pinned with its
// signer as your music app is (MusicApps). Every route and map search Robot opens goes through [open], and Phone lock's
// Maps leaves this app open (LockMaps). The apps listed are the ones that open geo: links, seen through the manifest's
// geo: <queries> entry; Organic Maps through its package entry. Package names, labels and digests only, never logged.
object MapsApps {
    data class Found(val pkg: String, val label: String)

    /** What [open] did: opened, nothing could open it, or your picked app is now signed by someone else ([label]). */
    sealed class Opened {
        data object Done : Opened()
        data object None : Opened()
        data class Repick(val label: String) : Opened()
    }

    private fun geoIntent() = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))

    /** The apps that open map links, by name; never Robot or Android's chooser. */
    fun find(ctx: Context): List<Found> = try {
        ctx.packageManager.queryIntentActivities(geoIntent(), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName && it != "android" && LockLimits.validPackage(it) }
            .map { Found(it, MusicApps.label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    } catch (t: Throwable) {
        RLog.w("maps_find", t)
        emptyList()
    }

    fun pin(ctx: Context, f: Found): MapsApp? {
        val sha = MusicApps.signer(ctx, f.pkg) ?: return null
        return if (MusicApp.valid(f.pkg, sha, f.label)) MapsApp(f.pkg, sha, f.label) else null
    }

    /** Your maps app checked as your music app is (installed, same signer); one that can't be checked counts as replaced. */
    fun check(ctx: Context, app: MapsApp): AppPin = when (
        runCatching { MusicApps.check(ctx, MusicApp(app.pkg, app.sha256, app.label)) }.getOrDefault(MusicApps.PinNow.REPLACED)
    ) {
        MusicApps.PinNow.OK -> AppPin.OK
        MusicApps.PinNow.GONE -> AppPin.GONE
        MusicApps.PinNow.REPLACED -> AppPin.REPLACED
    }

    fun organic(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(MapsChoice.ORGANIC_MAPS, 0); true } catch (_: Throwable) { false }

    /** The phone's default app for geo: links (none when Android would ask). */
    fun geoDefault(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(geoIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" && LockLimits.validPackage(it) }
    } catch (_: Throwable) { null }

    private class Pick(val app: MapsApp?, val pin: AppPin?, val organic: Boolean)

    private fun pick(ctx: Context): Pick {
        val app = EverydaySettingsStore.current(ctx).mapsApp
        return Pick(app, app?.let { check(ctx, it) }, organic(ctx))
    }

    /** The app routes open in now: none when your pick was replaced (fail closed). */
    fun app(ctx: Context): String? {
        val p = pick(ctx)
        return MapsChoice.app(p.app, p.pin, p.organic, geoDefault(ctx))
    }

    fun route(ctx: Context, from: Pair<Double, Double>?, fromName: String, to: Pair<Double, Double>, toName: String): MapsPlan {
        val p = pick(ctx)
        return MapsChoice.route(p.app, p.pin, p.organic, from, fromName, to, toName)
    }

    fun search(ctx: Context, query: String): MapsPlan {
        val p = pick(ctx)
        return MapsChoice.search(p.app, p.pin, p.organic, Uri.encode(query))
    }

    /**
     * Opens [plan]'s first link that an app takes, in its app; [adjacent]: beside Robot in split screen when Android
     * allows it (FLAG_ACTIVITY_LAUNCH_ADJACENT; from full screen a phone may open it full screen instead). [launch] starts
     * the intent (the panel's own launcher, or the context).
     */
    fun open(ctx: Context, plan: MapsPlan, adjacent: Boolean = false, launch: (Intent) -> Boolean = { start(ctx, it) }): Opened {
        if (plan is MapsPlan.Repick) return Opened.Repick(plan.label)
        for (l in (plan as MapsPlan.Open).links) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(l.uri)).addFlags(flags(adjacent))
            l.pkg?.let { i.setPackage(it) }
            if (i.resolveActivity(ctx.packageManager) == null) continue
            if (try { launch(i) } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }) return Opened.Done
        }
        return Opened.None
    }

    /** Brings your maps app forward as it was (its directions, when it shows them), beside Robot when Android allows it. */
    fun bringForward(ctx: Context, adjacent: Boolean): Boolean {
        val pkg = app(ctx) ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(ctx, i.addFlags(flags(adjacent)))
    }

    /** Said when your picked maps app is now signed by someone else: nothing opened. */
    fun replacedText(): Phrase = EverydayStrings.mapsReplaced("page" to UiStrings.inSettings(UiStrings.actions, EverydayStrings.mapsPage))

    private fun flags(adjacent: Boolean) = Intent.FLAG_ACTIVITY_NEW_TASK or (if (adjacent) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0)

    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (t: Throwable) {
        RLog.w("maps_open", t)
        false
    }
}
