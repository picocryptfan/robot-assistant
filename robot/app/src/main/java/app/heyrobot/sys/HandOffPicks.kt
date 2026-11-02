// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HandOffIntent
import app.heyrobot.core.connect.HandOffPicker
import app.heyrobot.core.connect.IntentSpec
import app.heyrobot.core.connect.PickProblem
import app.heyrobot.people.AndroidSigners

// MAIN PROCESS. The app picker's phone side for a hand-off (docs/plans/skills-connections-plan.md §3.5, §6.2; C7, the
// screen is C9's): the apps with a launcher icon (visible to Robot through the manifest's MAIN/LAUNCHER query, so no
// `<queries>` change and never QUERY_ALL_PACKAGES), never Robot itself; and a picked app pinned by its one signer (core
// HandOffPicker, read through Messages' AndroidSigners), with what it is asked to do checked against its own activities.
// Package names, labels and signer digests only: nothing here is a secret, and nothing is logged.

object HandOffPicks {
    /** An app you may pick: its package and its label. */
    data class App(val pkg: String, val label: String)

    /** The apps with a launcher icon, Robot left out, by label. Background thread. */
    fun apps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0L))
                .mapNotNull { it.activityInfo?.packageName }.distinct().filter { it != ctx.packageName }
                .map { pkg -> App(pkg, try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }) }
                .sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Hand-off [id] called [name] to [pkg], asking [spec]: its signer read now and pinned, and one of its exported
     * activities taking what it asks; or why not. Background thread. The caller saves it (ConnFiles).
     */
    fun pin(ctx: Context, id: String, name: String, pkg: String, spec: IntentSpec): Pair<Connection?, PickProblem?> {
        if (pkg == ctx.packageName) return null to PickProblem.CANT_TAKE
        return HandOffPicker.connection(id, name, pkg, AndroidSigners(ctx).now(pkg), spec) { takes(ctx, it) }
    }

    /** C9: hand-off [c]'s app as Android shows it now (its row's state). Background thread. */
    fun state(ctx: Context, c: Connection): app.heyrobot.core.connect.AppPinState {
        val h = c.handOff ?: return app.heyrobot.core.connect.AppPinState.MISSING
        // C10: a hand-off from a backup that isn't yours has no signer pinned here: "Pick it again".
        val pinned = h.signer ?: return app.heyrobot.core.connect.AppPinState.UNPINNED
        val s = AndroidSigners(ctx)
        val now = s.now(h.pkg)
        return app.heyrobot.core.connect.HandOffStep.state(pinned, now, now != null && s.stillSigns(h.pkg, app.heyrobot.core.messages.AppPin(setOf(pinned))))
    }

    /** One of [h]'s package's exported activities takes it. */
    private fun takes(ctx: Context, h: HandOffIntent): Boolean = try {
        val i = Intent(h.action).setPackage(h.pkg)
        if (h.launcher) i.addCategory(Intent.CATEGORY_LAUNCHER)
        val data = h.data?.let(Uri::parse)
        when {
            data != null && h.mime != null -> i.setDataAndType(data, h.mime)
            data != null -> i.setData(data)
            h.mime != null -> i.setType(h.mime)
        }
        ctx.packageManager.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0L)).any { it.activityInfo?.exported == true && it.activityInfo.packageName == h.pkg }
    } catch (_: Exception) {
        false
    }
}
