// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File

// Pure (java.io). R1, R3: what a restore tells you, whatever stops it. :voice writes started.json as it begins, with every
// category it means to write marked Cut, and again after each one ends; result.json takes over at the end. Nothing else
// says a restore ran, so one that stops part way (the phone dies, :voice is killed, a throw) leaves started.json, and an
// older restore's result is gone from the moment this one starts. applied.json is the main app's own: which of the categories
// it finishes at its start went in. Three files with one writer each, so no process rewrites another's. Codes only, as result.json.

class RestoreProgress(private val dir: File) {
    private val result get() = File(dir, RestoreResult.FILE)
    private val started get() = File(dir, STARTED)
    private val applied get() = File(dir, APPLIED)

    /** [VOICE] A restore begins: the last one's result goes. Written first: a cut before this point changed nothing. */
    fun begin(r: RestoreResult) {
        note(r)
        result.delete()
        applied.delete()
    }

    /** [VOICE] How far it has come. */
    fun note(r: RestoreResult) {
        try { writeAtomic(started, r.toJson()) } catch (_: Throwable) { /* The last note stands; the backup code logs nothing. */ }
    }

    /** [VOICE] It reached the end. If the result can't be written, started.json stays and says as much as it knows. */
    fun end(r: RestoreResult) {
        try {
            writeAtomic(result, r.toJson())
            started.delete()
        } catch (_: Throwable) {
            // The marker stays and reads as cut, which never claims more than happened.
        }
    }

    /**
     * [VOICE] At its start, after its recovery, with no restore running: a restore that stopped part way becomes a result.
     * [recovered]: how each category that recovery touched ended; [files]: Robot Files' line if its last step still waits. Null:
     * nothing to say (none was cut). A result already there means it ended and only the clean-up was cut.
     */
    fun settle(recovered: Map<BackupCategory, Boolean>, files: CategoryOutcome? = null): RestoreResult? {
        val m = RestoreResult.fromJson(readOrNull(started)) ?: return null.also { started.delete() }
        if (result.isFile) {
            started.delete()
            return null
        }
        val outcomes = LinkedHashMap(m.settled(recovered).outcomes)
        if (files != null && outcomes[BackupCategory.FILES] == CategoryOutcome.Cut) outcomes[BackupCategory.FILES] = files
        val r = m.copy(outcomes = outcomes, pid = 0, cut = true)
        end(r)
        return r
    }

    /** [MAIN] How the main app's categories ended at its start ([RestoreJournal.recover]'s answer). */
    fun applied(lines: List<Recovered>) {
        if (lines.isEmpty()) return
        val all = appliedLines() + lines.associate { it.category to (it.state == JournalState.DONE) }
        try {
            writeAtomic(applied, JSONObject().apply { for ((c, ok) in all) put(c.id, ok) }.toString())
        } catch (_: Throwable) {
            // Without main's word nothing is promoted to restored.
        }
    }

    private fun appliedLines(): Map<BackupCategory, Boolean> {
        val o = try { JSONObject(readOrNull(applied) ?: return emptyMap()) } catch (_: Exception) { return emptyMap() }
        return BackupCategory.entries.mapNotNull { c -> (o.opt(c.id) as? Boolean)?.let { c to it } }.toMap()
    }

    /**
     * What Settings shows: the result, or a restore that stopped and that :voice hasn't settled yet. Nothing while one is being
     * written now ([running]: is this process the one writing it). The main app's own word on its categories goes over either.
     */
    fun read(running: (Int) -> Boolean): RestoreResult? {
        val r = RestoreResult.fromJson(readOrNull(result)) ?: RestoreResult.fromJson(readOrNull(started))?.takeUnless { running(it.pid) } ?: return null
        return r.settled(appliedLines())
    }

    /** You closed the last restore's result. */
    fun dismiss() {
        result.delete()
        started.delete()
        applied.delete()
    }

    companion object {
        const val STARTED = "started.json" // l10n: not text (a file name)
        const val APPLIED = "applied.json" // l10n: not text (a file name)
    }
}
