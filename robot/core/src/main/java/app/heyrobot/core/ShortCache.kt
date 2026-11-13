// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * A value worked out at most once every [ttlMs] (on [clock]'s time), and worked out again after [clear]. App pause keeps the
 * lock's state and facts in one for :lock's window events (review-after13-a 5), so every event doesn't read them again.
 */
class ShortCache<T : Any>(private val ttlMs: Long, private val clock: () -> Long) {
    private var value: T? = null
    private var at = 0L

    @Synchronized
    fun get(load: () -> T): T {
        val now = clock()
        value?.let { if (now >= at && now - at < ttlMs) return it }
        return load().also { value = it; at = now }
    }

    @Synchronized
    fun clear() { value = null }
}
