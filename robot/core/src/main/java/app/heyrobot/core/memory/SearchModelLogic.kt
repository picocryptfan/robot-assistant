// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.SearchModel

// Pure. The search model's download states for Settings › Memory (app SearchModelStore does the Android side).

enum class SearchModelState { NONE, WAITING_FOR_WIFI, WAITING_ROAMING, DOWNLOADING, DOWNLOADED, CHECKING, READY, FAILED }

object SearchModelLogic {
    /** DownloadManager's STATUS_ codes (android.app.DownloadManager), here so the logic stays pure. */
    const val STATUS_PENDING = 1
    const val STATUS_RUNNING = 2
    const val STATUS_PAUSED = 4
    const val STATUS_SUCCESSFUL = 8
    const val STATUS_FAILED = 16
    /** Room kept free beyond the file, as the other downloads keep. */
    const val MARGIN = 200L * 1024 * 1024

    fun state(verified: Boolean, checking: Boolean, downloadStatus: Int?, waitingForWifi: Boolean, stagedBytes: Long, failed: Boolean, roaming: Boolean = false): SearchModelState = when {
        verified -> SearchModelState.READY
        checking -> SearchModelState.CHECKING
        downloadStatus == STATUS_SUCCESSFUL && stagedBytes == SearchModel.BYTES -> SearchModelState.DOWNLOADED
        waitingForWifi -> SearchModelState.WAITING_FOR_WIFI
        roaming && downloadStatus == STATUS_PAUSED -> SearchModelState.WAITING_ROAMING
        downloadStatus == STATUS_PENDING || downloadStatus == STATUS_RUNNING || downloadStatus == STATUS_PAUSED -> SearchModelState.DOWNLOADING
        failed || downloadStatus == STATUS_FAILED || downloadStatus == STATUS_SUCCESSFUL -> SearchModelState.FAILED
        else -> SearchModelState.NONE
    }

    fun percent(done: Long): Int = (done.coerceIn(0, SearchModel.BYTES) * 100 / SearchModel.BYTES).toInt()

    /** Enough free space for the download, its checked copy and the margin, after what other downloads will still write. */
    fun room(free: Long, pending: Long): Boolean = free >= 0 && free - pending >= 2 * SearchModel.BYTES + MARGIN

    /** The verified mark: the hash checked, and the file as it was then (size and time), so a changed file is checked again. */
    fun mark(length: Long, modified: Long): String = "${SearchModel.SHA256}|$length|$modified"

    fun vouched(mark: String?, length: Long, modified: Long): Boolean = length == SearchModel.BYTES && mark == mark(length, modified)
}
