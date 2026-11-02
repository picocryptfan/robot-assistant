// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import java.io.File

// Pure (java.io). 1.5, Robot to Robot (robot-to-robot-plan.md §6.1's "Connection log", D13; job L3): :link's own log,
// codes and small numbers only, as the voice log: what the carriers did (a connection opened, a push posted or refused, Tor
// up, a request in, a request out, an ack, something dropped and why), never a name, an address, a key, a token, a
// request's type text or anything another Robot sent. The recording API takes only [LinkEvent] and Ints (LinkLogTest
// checks its signature and runs a marker through every path that records). Kept in memory in :link (the last
// [LinkLog.CAPACITY] events) and mirrored to files/link/link_log.txt (one writer: :link), so it survives a restart;
// Settings' connection log (L6) reads the file.

/** Every event. [id] is what the file stores (never renumber one); a and b are small numbers each entry states. */
enum class LinkEvent(val id: Int) {
    /** :link started: a = the vault's state (VaultState's code), b = pairings. */
    START(1),
    /** A live connection opened: a = [LinkWay] code, b = 1 we dialled, 0 they did. */
    CONNECT(2),
    /** A live connection closed: a = [LinkWay] code, b = why ([LinkProblem] code, 0 a normal end). */
    DISCONNECT(3),
    /** An envelope dropped: a = [LinkWay] code, b = [LinkProblem] code. */
    DROPPED(4),
    /** A session message didn't decrypt: a = [LinkWay] code, b = the link module's SessionProblem code. */
    UNDECRYPTED(5),
    /** A request came in and was handed on: a = its type's code, b = [LinkWay] code. */
    REQUEST_IN(6),
    /** A request was refused: a = its type's code (0 unknown), b = the ack state's ordinal + 1 (or 0: dropped, no answer). */
    REQUEST_REFUSED(7),
    /** A request went out by a carrier: a = its type's code, b = [LinkWay] code. */
    REQUEST_OUT(8),
    /** A send failed: a = [LinkWay] code, b = the carrier's problem code. */
    SEND_FAILED(9),
    /** An outbox request was given up after its lifetime: a = its type's code. */
    EXPIRED(10),
    /** An ack arrived: a = the ack state's ordinal. */
    ACK_IN(11),
    /**
     * Tor: a = 1 started, 2 bootstrapped, 3 stopped, 4 an onion published, 5 failed; b = TorProblem code on 5. L-bridges: on 2,
     * b = the route it bootstrapped over (TorRoute.code: 1 plain, 2 Snowflake, 3 built-in obfs4, 4 meek, 5 your own bridge).
     */
    TOR(12),
    /** Push: a = 1 registered, 2 a new endpoint, 3 unregistered, 4 a message not decrypted by RFC 8291 (refused), 5 no distributor. */
    PUSH(13),
    /** A pairing hello: a = 1 sent, 2 accepted, 3 refused; b = the CodeAttempt's ordinal, or a [LinkProblem] code. */
    HELLO(14),
    /** Robot's network access is off (GrapheneOS's Network toggle): nothing was sent or listened for. */
    NO_NETWORK(15),
    /** The direct carrier's listener: a = 1 listening, 0 stopped. */
    LISTEN(16),
    /** 1.5 L5, a pairing step: a = [PairStep] code, b = 0 or the step's own number (a CodeAttempt's ordinal, a mode's code). */
    PAIR(17);

    companion object {
        fun of(id: Int): LinkEvent? = entries.firstOrNull { it.id == id }
    }
}

/** What happened to a pairing (L5), as the link log's [LinkEvent.PAIR] code. Codes are stable. */
enum class PairStep(val code: Int) {
    /** In person, a photographed code: your hello went (your key sorts first). */
    SCAN_SENT(1),
    /** In person, a photographed code kept to check their hello (their key sorts first). */
    SCAN_KEPT(2),
    VERIFIED_IN_PERSON(3),
    /** "They match". */
    COMPARED(4),
    /** In person, the Robot that answered isn't the one photographed: refused. */
    MISMATCH(5),
    PERSON_SET(6),
    LEVELS_SET(7),
    RAISED(8),
    RAISED_BY_THEM(9),
    LOWER_ASKED(10),
    LOWER_ASKED_BY_THEM(11),
    LOWERED(12),
    LOWER_DECLINED(13),
    BLOCKED(14),
    UNBLOCKED(15),
    /** A hello from a blocked key: dropped unread, no card. */
    BLOCKED_HELLO(16),
    /** Their Robot unpaired: the note kept (ENDED). */
    ENDED(17),
    MOVED_SENT(18),
    /** A moved hello: the pairing paused, a card. */
    MOVED_IN(19),
    MOVED_ACCEPTED(20),
    MOVED_DECLINED(21),
    /** A moved hello refused: b = a [LinkProblem] code. */
    MOVED_REFUSED(22),
    /** L5-fix: in person, a hello before your photo: held, not answered, the code not spent. */
    HELD(23),
    /** L5-fix: both pasted each other's codes at once; b = 1 this side's hello wins (its key sorts first), 0 theirs does. */
    CROSSED(24);

    companion object {
        fun of(code: Int): PairStep? = entries.firstOrNull { it.code == code }
    }
}

/** Which carrier, as a code. */
enum class LinkWay(val code: Int) {
    DIRECT(1),
    PUSH(2),
    TOR(3),
    /** Push posted through Tor (Anonymous). */
    PUSH_TOR(4);

    companion object {
        fun of(code: Int): LinkWay? = entries.firstOrNull { it.code == code }
    }
}

class LinkLogEntry(val time: Long, val event: LinkEvent, val a: Int, val b: Int) {
    override fun toString(): String = "LinkLogEntry(${event.name}, $a, $b)" // l10n: not text (toString for logs and tests)
}

/** link_log.txt: a header line, then "time event a b" per event, oldest first; numbers only. */
object LinkLogCodec {
    const val HEADER = "robot-link-log 1" // l10n: not text (the file's header)
    const val FILE = "link_log.txt" // l10n: not text (a file name)

    fun encode(list: List<LinkLogEntry>): String = buildString {
        append(HEADER).append('\n')
        for (e in list) append(e.time).append(' ').append(e.event.id).append(' ').append(e.a).append(' ').append(e.b).append('\n')
    }

    /** Oldest first; a damaged line or an event this version doesn't know is skipped, never fatal. */
    fun decode(text: String?, capacity: Int = LinkLog.CAPACITY): List<LinkLogEntry> {
        if (text == null) return emptyList()
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        val out = ArrayList<LinkLogEntry>()
        for (l in lines.drop(1)) {
            val p = l.trim().split(' ')
            if (p.size != 4) continue
            val t = p[0].toLongOrNull() ?: continue
            val e = p[1].toIntOrNull()?.let(LinkEvent::of) ?: continue
            out += LinkLogEntry(t, e, p[2].toIntOrNull() ?: continue, p[3].toIntOrNull() ?: continue)
        }
        return out.takeLast(capacity)
    }
}

/**
 * The link log of one :link process: [file] (null in tests) is read once and written after each event. Thread-safe;
 * [clock] is wall time in ms.
 */
class LinkLog(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val items = ArrayDeque<LinkLogEntry>()

    init {
        file?.let { f -> items.addAll(LinkLogCodec.decode(try { f.takeIf { it.isFile }?.readText() } catch (_: Exception) { null })) }
    }

    /** Records [event] with [a] and [b] as that event documents them. */
    @Synchronized
    fun add(event: LinkEvent, a: Int = 0, b: Int = 0) {
        items.addLast(LinkLogEntry(clock(), event, a, b))
        while (items.size > CAPACITY) items.removeFirst()
        file?.let { f ->
            try {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp") // l10n: not text (a file name)
                tmp.writeText(LinkLogCodec.encode(items.toList()))
                if (!tmp.renameTo(f)) tmp.delete()
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun snapshot(): List<LinkLogEntry> = items.toList()

    companion object {
        const val CAPACITY = 300
    }
}
