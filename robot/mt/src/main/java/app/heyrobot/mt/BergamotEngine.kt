// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.mt

import android.os.SystemClock
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtError
import app.heyrobot.core.MtException
import app.heyrobot.core.MtMark
import java.io.File

// :MT PROCESS ONLY, on MtService's worker thread (never two threads in the engine). One engine: the directions of one
// bundle at most (fr↔en: two models of about 140 MB each in memory), loaded only from files the main app verified
// (the bundle's mark vouches for every one), and freed on unload.

internal class BergamotEngine(private val filesDir: File) {
    private var bundle: MtBundle? = null
    private val handles = LinkedHashMap<String, Long>()

    /** The directions in memory ("fr-en"). */
    val loaded: List<String> get() = handles.keys.toList()

    /**
     * Loads every direction of [b] not loaded yet (another bundle goes first), and gives each one's load time in ms.
     * Throws [MtException]: NO_ENGINE without librobotmt.so, NOT_DOWNLOADED when the mark doesn't vouch for a file,
     * LOAD_FAILED when slimt refuses the files.
     */
    fun load(b: MtBundle): Map<String, Long> {
        if (!Slimt.available) throw MtException(MtError.NO_ENGINE)
        if (bundle != null && bundle != b) unload()
        bundle = b
        val dir = MtBundle.dir(filesDir, b)
        val ms = LinkedHashMap<String, Long>()
        for (d in b.directions) {
            if (d.id in handles) continue
            if (!MtMark.ready(b, d, dir)) throw MtException(MtError.NOT_DOWNLOADED)
            val t0 = SystemClock.elapsedRealtime()
            val h = Slimt.open(File(dir, d.model), File(dir, d.vocabulary), File(dir, d.shortlist)) ?: throw MtException(MtError.LOAD_FAILED)
            handles[d.id] = h
            ms[d.id] = SystemClock.elapsedRealtime() - t0
        }
        return ms
    }

    fun has(direction: String): Boolean = direction in handles

    /** One line of a loaded [direction]; null when the engine failed on it. */
    fun translate(direction: String, line: String): String? = handles[direction]?.let { Slimt.translate(it, line) }

    fun unload() {
        for (h in handles.values) Slimt.close(h)
        handles.clear()
        bundle = null
    }
}
