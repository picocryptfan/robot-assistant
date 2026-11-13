// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Download
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/** fix-roaming-download: a download held back because the phone roams says so, not just "waiting for a network". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNetworkTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun network(roaming: Boolean) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!roaming) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun waiting() = DownloadNetwork.reason(ctx, DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_WAITING_FOR_NETWORK)

    @Test fun roamingHoldSaysRoaming() {
        network(roaming = true)
        assertEquals(Download.PAUSED_ROAMING, waiting())
        assertEquals(DownloadManager.PAUSED_QUEUED_FOR_WIFI, DownloadNetwork.reason(ctx, DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_QUEUED_FOR_WIFI))
        assertEquals(404, DownloadNetwork.reason(ctx, DownloadManager.STATUS_FAILED, 404))
    }

    @Test fun homeNetworkKeepsAndroidsReason() {
        network(roaming = false)
        assertEquals(DownloadManager.PAUSED_WAITING_FOR_NETWORK, waiting())
    }
}
