// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.heyrobot.core.Download

// MAIN PROCESS (1.4, fix-roaming-download). Robot's downloads never go over roaming (each request says
// setAllowedOverRoaming(false); DownloadRoamingTest checks every one), even with "Download on mobile data" on: a roaming
// download can cost real money. Android holds such a download as "waiting for a network"; this says why instead.
object DownloadNetwork {
    /** Whether the phone's network now is roaming (ACCESS_NETWORK_STATE); false when it can't tell. */
    fun roaming(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    } catch (_: Exception) { false }

    /** The download manager's [reason], or Download.PAUSED_ROAMING for one waiting for a network while the phone roams. */
    fun reason(ctx: Context, status: Int, reason: Int): Int =
        if (status == Download.STATUS_PAUSED && reason == Download.PAUSED_NETWORK && roaming(ctx)) Download.PAUSED_ROAMING
        else reason
}
