// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

/**
 * L16: how often the other Robot may ask for one offered file again without having got further. F26: and, however further it
 * says it got, how much of the [total] chunks may go a second time: about three copies more. In memory: a restart starts over.
 */
internal class FileResumes(private val total: Long) {
    private var furthest = -1L
    private var again = 0
    private var reached = 0L
    private var resent = 0L

    @Synchronized fun allow(from: Long): Boolean {
        if (resent >= total * RESEND_COPIES) return false
        if (from > furthest) { furthest = from; again = 0; return true }
        return ++again <= AGAIN
    }

    /** Chunk [i] went out (on the sending thread). Counted when it goes, as a cut connection sends only part of what was asked. */
    @Synchronized fun sent(i: Long) { if (i < reached) resent++ else reached = i + 1 }

    companion object {
        const val AGAIN = 5
        const val RESEND_COPIES = 3L
    }
}
