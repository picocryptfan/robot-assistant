// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.heyrobot.core.connect.NetNow
import app.heyrobot.sys.Assistant

// VOICE PROCESS. CN4's "Robot can't reach your Home Assistant off your home network." (docs/plans/skills-connections-plan.md
// §3, C4): whether the phone is on Wi-Fi, Ethernet or a VPN (Tailscale's), on another network, or on none, as Android
// says. Read only when ACCESS_NETWORK_STATE is granted: the main session decided Robot may use it, and 1.1.0 declares it
// for its update jobs (granted at install; C11's merge of release/1.1 brought it here). Without it every answer is
// "unknown" (null): a call is then tried, and a failure says "can't reach" instead. Nothing else of the network is read
// (no name, no address); nothing is logged. PRIVACY's Network state row says so.
//
// Android's null is unknown too, never "offline" (conn-vpn: with a signed-out VPN app installed, a skill said "Robot can't
// go online right now." on working Wi-Fi). getActiveNetwork is documented to return null "when there is no default
// network, or when the default network is blocked", and capabilities may be null for a network that just went away: no
// answer here tells a phone with no network from one Android is keeping from Robot, so the call is tried and its failure
// speaks. So this source never answers NetNow.NONE.

/** The network now, as ConnRunner asks it (a fake in tests). */
fun interface NetSource {
    fun now(ctx: Context): NetNow?
}

object ConnNetwork : NetSource {
    /** The network now; null when Robot may not ask Android (the permission isn't granted) or Android didn't describe one. */
    override fun now(ctx: Context): NetNow? {
        if (!Assistant.granted(ctx, Manifest.permission.ACCESS_NETWORK_STATE)) return null
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
            val n = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(n) ?: return null
            of(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Wi-Fi, Ethernet or a VPN: your own network may be reached; anything else (mobile data) is away from it. */
    fun of(wifi: Boolean, ethernet: Boolean, vpn: Boolean): NetNow = if (wifi || ethernet || vpn) NetNow.LOCAL else NetNow.AWAY
}
