// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.heyrobot.core.car.CarWidget
import app.heyrobot.core.lock.LockLimits

// The bottom screen's widget (docs/plans/car-screens-plan.md §2 A3 and A5, §3). Settings (main process) gives an id,
// asks Android's bind question and opens the widget's own setup screen; the car screen (:voice) shows the widget
// through assistant/car's CarWidgetHost, listening from onStart to onStop. One host id, the same in both processes:
// Android keys a host by app and host id, not by process, so this shared file (no engine, nothing of the assistant)
// lists, gives, binds and deletes ids, and only the view lives in the :voice process. Robot never reads, logs, saves or
// sends what a widget shows, and an id is deleted when the slot changes. No permission: BIND_APPWIDGET is the system's,
// so Robot asks with bindAppWidgetIdIfAllowed and, when that says no, Android's own question.

/** Both processes: the ids and the questions. */
object CarWidgets {
    /** Robot's one widget host. */
    const val HOST_ID = 0x0CA5

    private fun manager(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx)

    /** The phone's widgets, never Robot's own: seen through the manifest's MAIN/LAUNCHER <queries> entry. */
    fun providers(ctx: Context): List<AppWidgetProviderInfo> = try {
        manager(ctx).installedProviders.filter { it.provider.packageName != ctx.packageName && LockLimits.validPackage(it.provider.packageName) }
    } catch (_: Exception) { emptyList() }

    fun allocate(ctx: Context): Int? = try { AppWidgetHost(ctx.applicationContext, HOST_ID).allocateAppWidgetId() } catch (_: Exception) { null }

    /**
     * Deletes this host's ids but [keep]: one left when Robot closed while Android's question or the widget's setup was up,
     * or brought back by a restore, whose app would keep updating a widget no one sees.
     */
    fun sweep(ctx: Context, keep: Set<Int>) {
        try {
            val h = AppWidgetHost(ctx.applicationContext, HOST_ID)
            CarWidget.leftovers(h.appWidgetIds, keep).forEach { h.deleteAppWidgetId(it) }
        } catch (_: Exception) {}
    }

    fun delete(ctx: Context, id: Int) {
        try { AppWidgetHost(ctx.applicationContext, HOST_ID).deleteAppWidgetId(id) } catch (_: Exception) {}
    }

    /** Bound with no question: you said "Always allow" before. False: [ask] it. */
    fun bindNow(ctx: Context, id: Int, provider: ComponentName): Boolean =
        try { manager(ctx).bindAppWidgetIdIfAllowed(id, provider) } catch (_: Exception) { false }

    /** Android's own question, with its "Always allow". */
    fun ask(id: Int, provider: ComponentName): Intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)

    /** The widget's own setup screen when it needs one; null: none, or it's optional. */
    fun setup(ctx: Context, id: Int): Intent? {
        val info = try { manager(ctx).getAppWidgetInfo(id) } catch (_: Exception) { null } ?: return null
        val screen = info.configure ?: return null
        if (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0) return null
        return Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(screen).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    }

    /** The widget's info while [w]'s id is still bound to the widget you picked; null otherwise. */
    fun bound(ctx: Context, w: CarWidget): AppWidgetProviderInfo? =
        try { manager(ctx).getAppWidgetInfo(w.id) } catch (_: Exception) { null }?.takeIf { it.provider == ComponentName(w.app.pkg, w.provider) }
}
