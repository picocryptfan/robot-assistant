// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.voice.FrameFeed

/**
 * Clips in place of the microphone: in turn, at real-time pace, silence between them, and never a sample from the room while
 * it's installed. [allowed] asks before each frame, so a card that comes up during a clip (the reply was slower than the
 * script) cuts it, and [onCut] says so: the receiver's check at the start can't see that card.
 */
class SpokenFrameFeed(
    private val rate: Int = 16_000,
    private val nanos: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { ns -> if (ns > 0) Thread.sleep(ns / 1_000_000, (ns % 1_000_000).toInt()) },
    private val allowed: (String) -> Boolean,
    private val onCut: (String) -> Unit = {},
    /** A clip's last frame was read: how long it took and how long it lasts, in ms. Longer means the listener fell behind. */
    private val onFed: (Long, Long) -> Unit = { _, _ -> },
) : FrameFeed {
    private class Clip(val name: String, val samples: ShortArray) { var at = 0; var startNanos = 0L }

    private val queue = ArrayDeque<Clip>()
    private var next = 0L

    fun enqueue(name: String, samples: ShortArray) = synchronized(queue) { queue.addLast(Clip(name, samples)) }

    /** Everything still to play is dropped: `end`, `reset`. */
    fun clear() = synchronized(queue) { queue.clear() }

    /** Clips not yet played out. */
    fun pending(): Int = synchronized(queue) { queue.size }

    override fun read(into: ShortArray, count: Int): Int {
        synchronized(queue) {
            var filled = 0
            while (filled < count) {
                val c = queue.firstOrNull() ?: break
                if (!allowed(c.name)) {
                    queue.removeFirst()
                    onCut(c.name)
                    continue
                }
                if (c.at == 0) c.startNanos = nanos()
                val n = minOf(count - filled, c.samples.size - c.at)
                c.samples.copyInto(into, filled, c.at, c.at + n)
                c.at += n
                filled += n
                if (c.at == c.samples.size) {
                    queue.removeFirst()
                    onFed((nanos() - c.startNanos) / 1_000_000, c.samples.size * 1000L / rate)
                }
                // One clip's end is one frame's silence at most before the next check: a frame never holds two clips.
                if (filled > 0) break
            }
            into.fill(0, filled, count)
        }
        pace(count)
        return count
    }

    /** Sleeps until this frame's time. After a stall it starts again from now, rather than rushing to catch up. */
    private fun pace(count: Int) {
        val now = nanos()
        val frame = count * 1_000_000_000L / rate
        if (next == 0L || now - next > 4 * frame) next = now
        next += frame
        sleep(next - now)
    }
}
