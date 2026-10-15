// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.update.GitHubHosts

// Pure (1.0). What every file Robot asks Android's download manager for carries, and when Robot asks about mobile data
// first. Robot has no network-state permission (ProcessIsolationTest and tools/verify_apk.sh keep it out), so it can't see
// whether the phone is on Wi-Fi or mobile data: before a big download it asks, and "Wait for Wi-Fi" lets the download
// manager, which can see the network, hold the download until an unmetered one.

object DownloadPolicy {
    /** A download of at least this many bytes asks first (the models, speech recognition, the natural voice). */
    const val LARGE_BYTES = 200_000_000L

    /** The User-Agent of every download: Robot and its version, nothing about the phone (Android's own names the model). */
    fun userAgent(version: String): String = GitHubHosts.userAgent(version)

    /** Whether a download of [bytes] asks first. */
    fun asksFirst(bytes: Long): Boolean = bytes >= LARGE_BYTES

    fun title(bytes: Long): String = "Download ${VoiceModelLogic.size(bytes)}?"

    fun text(bytes: Long): String = "Robot can't tell whether you're on Wi-Fi or mobile data. On mobile data, " +
        "${VoiceModelLogic.size(bytes)} may use up your plan or cost you.\n\n" +
        "Wait for Wi-Fi: the download starts once the phone is on Wi-Fi (or another network that isn't metered)."

    const val WAIT = "Wait for Wi-Fi"
    const val ANYWAY = "Download anyway"
}
