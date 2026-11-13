// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.Utterance

// Pure. Where a reading is (docs/plans/read-it-to-me-plan.md §3, §5, §6). A place holds no text and no title: the source's
// id, the piece's number and the piece's start in the text, so an edited note still resumes at the sentence it was on.
// Places live in a [ReadPlaces] store: a memory one here, the sealed store of feature/seal-people once it merges. A
// shared page's place is never kept past its reading.

/** What is read. */
enum class ReadKind(val prefix: String) {
    SHARED("shared"), NOTE("note"), FILE("file"), MESSAGE("message"); // l10n: not text (keys)

    /** A place outlives the reading (a shared page's never does). */
    val keepsPlace: Boolean get() = this != SHARED
}

/** One source: its kind and its own id (a note's or File's id; a random one for a shared page). */
data class ReadSource(val kind: ReadKind, val id: String) {
    val key: String get() = "${kind.prefix}:$id"
}

/** The next piece to speak in [source]: number [piece], which starts at [offset] in the source's text. */
data class ReadPlace(val source: ReadSource, val piece: Int, val offset: Int)

/** Where places are kept. */
interface ReadPlaces {
    fun get(source: ReadSource): ReadPlace?
    fun put(place: ReadPlace)
    fun drop(source: ReadSource)
}

/** Places kept only while Robot runs (and in tests). */
class MemoryReadPlaces : ReadPlaces {
    private val map = HashMap<String, ReadPlace>()
    override fun get(source: ReadSource): ReadPlace? = map[source.key]
    override fun put(place: ReadPlace) { map[place.source.key] = place }
    override fun drop(source: ReadSource) { map.remove(source.key) }
}

/**
 * One reading of [text]. [current] is the piece to speak now; a piece that was cut off is spoken again from its start,
 * so a resumed reading starts at the sentence where it stopped. Not thread-safe: one thread drives it.
 */
class ReadCursor(val source: ReadSource, val text: ReadText, private val places: ReadPlaces) {
    var current: Int = resume()
        private set

    val done: Boolean get() = current >= text.pieces.size

    /** The piece to speak now, or null at the end. */
    fun piece(): ReadPiece? = text.pieces.getOrNull(current)

    /** The piece [index] was spoken to its end: the next one is current, and its place is kept. */
    fun spoken(index: Int) {
        if (index != current || done) return
        current++
        if (done) finish() else keep()
    }

    /** "Go back a bit": at least [MIN_BACK] pieces and about [BACK_CHARS] characters (some 20 seconds) before the current one. */
    fun back() {
        val from = text.pieces.getOrNull(current)?.start ?: text.text.length
        var k = maxOf(0, current - MIN_BACK)
        while (k > 0 && from - text.pieces[k].start < BACK_CHARS) k--
        current = k
        keep()
    }

    /** Stopped before the end ("stop reading", Robot closed): a note's or File's place stays, a shared page's goes. */
    fun stop() = if (source.kind.keepsPlace && !done) keep() else places.drop(source)

    /** Read to the end: nothing is kept. */
    fun finish() = places.drop(source)

    private fun keep() {
        val p = text.pieces.getOrNull(current) ?: return finish()
        if (source.kind.keepsPlace) places.put(ReadPlace(source, current, p.start))
    }

    private fun resume(): Int {
        val p = places.get(source) ?: return 0
        if (text.pieces.isEmpty()) return 0
        return if (text.pieces.getOrNull(p.piece)?.start == p.offset) p.piece else text.pieceAt(p.offset)
    }

    companion object {
        const val MIN_BACK = 2
        const val BACK_CHARS = 300
    }
}

/** The lock rules (plan §6). Fail closed: an unknown lock state ([locked] null) counts as locked. */
object ReadGate {
    /**
     * Whether a reading may start or go on. Notes, Files and messages never while locked; a shared page only with
     * [sharedWhileLocked] (the question in the plan, off by default) and then only in private headphones, which
     * OutputGuard checks for every piece.
     */
    fun mayRead(kind: ReadKind, locked: Boolean?, sharedWhileLocked: Boolean = false): Boolean =
        locked == false || (kind == ReadKind.SHARED && sharedWhileLocked && locked == true)

    /** What OutputGuard is asked for each piece: always personal (a shared page can be anything), as an answer you asked for. */
    fun utterance(piece: ReadPiece): Utterance = Utterance(SpeechClass.PERSONAL, SpeechSource.ANSWER, piece.text.length)
}
