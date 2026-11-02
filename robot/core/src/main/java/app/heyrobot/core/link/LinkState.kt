// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.LogCode
import app.heyrobot.core.seal.Envelope
import app.heyrobot.core.seal.EnvelopeException
import app.heyrobot.core.seal.SealKind
import app.heyrobot.core.seal.EnvelopeProblem
import app.heyrobot.core.seal.KindPurpose
import app.heyrobot.core.seal.SecretWrap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

// Pure java.io. 1.5, Robot to Robot (robot-to-robot-plan.md §2.4, job L2): :link's state at rest. Each record (your
// identity, a paired Robot's identity key, a signed prekey, a Kyber prekey, a session; L3: the pairings as the carriers see
// them, their replay windows, the outbox, the onion service's key, open pairing codes and push messages waiting for the
// job) is a file of its own in files/link/state/, sealed by the Files envelope with robot_link_v1
// (core/seal/Envelope.kt's format 1, magic "ROBOTLNK", "robot-link-v1|<purpose>|<id>" as the wrapped key's additional
// data): a fresh key for every write, so a record never reuses one, and a record's file can't stand in for another's.
// The state is read once when :link starts and kept in that process's memory only; each change is written through here.
//
//   files/link/state/<purpose>-<id>.rl     a record: the purpose's code, its 16-byte id as 32 hex digits
//   files/link/state/.<name>.tmp           a write under way (fsynced, then renamed over the record)
//
// robot_link_v1 is made only while this folder holds nothing at all; a missing key with anything here is GONE, never a
// reason to make one (the records would be lost to a new key without a word). Nothing here ever deletes a record on its
// own: a record that can't be read is DAMAGED and stays, as Robot Files' do, until you start over (deleteAll). Deleting
// a record overwrites its wrapped key first, so a copy of the folder taken later can't open it.

/** What a record is. Codes are stable: never renumber one. Each purpose belongs to one envelope format. */
enum class LinkPurpose(val code: Int, val format: Int, val maxBytes: Long) {
    /** Your Robot's identity: libsignal's identity key pair and registration id. */
    IDENTITY(1, Envelope.FORMAT_SECRET, 4L * 1024),
    /** A paired Robot's identity key, as libsignal's identity store keeps it (one per pairing). */
    PEER(2, Envelope.FORMAT_SECRET, 1024),
    /** One of your signed prekeys (one current, the replaced ones kept a while, §2.5). */
    SIGNED_PREKEY(3, Envelope.FORMAT_SECRET, 4L * 1024),
    /** One of your Kyber prekeys: one-time, deleted once the session it started is stored. */
    KYBER_PREKEY(4, Envelope.FORMAT_SECRET, 16L * 1024),
    /** A session with one paired Robot (libsignal's record, its archived states included). EST cap: 4 MiB. */
    SESSION(5, Envelope.FORMAT_SECRET, 4L * 1024 * 1024),
    /** A request waiting for you to unlock (LinkInbox): format 2, robot_inbox_v1. */
    WAITING(6, Envelope.FORMAT_PUBLIC, 64L * 1024),
    // 1.5 L3, the carriers' state (the link module's LinkState records, one file each):
    /** A pairing as the carriers see it: its mode, the other Robot's routes and caps, your push instance, the tokens. */
    PAIRING(7, Envelope.FORMAT_SECRET, 32L * 1024),
    /** One pairing's replay window (core LinkReplay: at most 5,000 ids of 32 bytes each). */
    REPLAY(8, Envelope.FORMAT_SECRET, 192L * 1024),
    /** One request in the outbox: its pairing, its times and tries, and its padded bytes (at most 32 KiB). */
    OUTBOX(9, Envelope.FORMAT_SECRET, 40L * 1024),
    /** Your onion service: its key, as Tor gave it. */
    TOR(10, Envelope.FORMAT_SECRET, 4L * 1024),
    /** A pairing code you made and that is still open: its token, tries and expiry, and its one-time routes. */
    CODE(11, Envelope.FORMAT_SECRET, 8L * 1024),
    /** A push message that arrived and waits for :link's job: the instance it came to and its bytes (one push body). */
    SPOOL(12, Envelope.FORMAT_SECRET, 8L * 1024),
    // 1.5 L5, pairing and People:
    /** A Robot you blocked (§2.6): its identity key, the label you knew it by, and when. Its hellos are dropped unread. */
    BLOCKED(13, Envelope.FORMAT_SECRET, 4L * 1024),
    // 1.5 L6, cards, levels and briefings:
    /**
     * One line of the desk (core LinkDesk's DeskEntry): what became of a request from another Robot or of one you sent, or a
     * "Not now" on Robot's proposal; an incoming one keeps its plaintext for 7 days (at most 32 KiB), every one 30 days.
     */
    DESK(14, Envelope.FORMAT_SECRET, 40L * 1024),
    // 1.5 L7, calendar and sending:
    /**
     * A plan shared with another Robot (core LinkPlan): what it is, its counter, what became of it, and the calendar event
     * Robot added for it (so a cancel or an Undo removes exactly that one). Kept 30 days after it ends.
     */
    PLAN(15, Envelope.FORMAT_SECRET, 4L * 1024),
    // 1.5 L8, files between Robots:
    /** One transfer, either way (link module TransferRecord): the offer, its state, and the key while it is under way. */
    TRANSFER(16, Envelope.FORMAT_SECRET, 12L * 1024),
    /** 1.5: where a list stands with another Robot (core ListShareRec): ids and counts, never its words. */
    LIST_SHARE(40, Envelope.FORMAT_SECRET, 1L * 1024),
    /** L11: what's in a shared folder (core FolderRec): lines and their names, never a file. */
    FOLDER(41, Envelope.FORMAT_SECRET, 640L * 1024),
    /** L11: a group plan you organize (core LinkGroup). */
    GROUP(42, Envelope.FORMAT_SECRET, 16L * 1024),
    /** L11: the organizer's word on who's in a group plan you're in (core GroupSeen). */
    GROUP_SEEN(43, Envelope.FORMAT_SECRET, 2L * 1024),
    /** One pairing's `ls.op` count this hour (core PeerRate): at most 60 times and weights of 16 bytes. */
    LIST_RATE(44, Envelope.FORMAT_SECRET, 2L * 1024),
    /** F73: how many inbox items each person has waiting, so a restart while locked keeps the count (the inbox says who only once it's open). One record. */
    INBOX_SHARE(45, Envelope.FORMAT_SECRET, 4L * 1024);

    companion object {
        fun of(code: Int): LinkPurpose? = entries.firstOrNull { it.code == code }
    }
}

object LinkSeal {
    /** The key's name in format 1's additional data. */
    const val KEY_NAME = "robot-link-v1" // l10n: not text (the cipher's additional data)
    val KIND = SealKind("ROBOTLNK", KEY_NAME, LinkPurpose.entries.map { KindPurpose(it.code, it.format, it.maxBytes) }) // l10n: not text (the file's magic bytes)

    fun purpose(p: LinkPurpose): KindPurpose = KIND.purpose(p.code)!!
}

/** Why :link's sealed state can't be read or written now, one code each (the link log). Codes are stable. */
enum class LinkStateProblem(override val id: Int, override val title: String) : LogCode {
    /** robot_link_v1 (or robot_inbox_v1) can't be used now: the Keystore is busy, or the phone is locked. Not gone. */
    LOCKED(1, "locked"), // l10n: not text (a log code's name)
    /** A key Android invalidated, or missing while something is sealed under it: "pair again with each person". */
    GONE(2, "key gone"), // l10n: not text (a log code's name)
    /** A record that doesn't open (changed, cut, moved, or sealed by another key). Kept, never deleted on its own. */
    DAMAGED(3, "damaged"), // l10n: not text (a log code's name)
    /** A newer Robot's record: never read, never written over. */
    NEWER(4, "newer"), // l10n: not text (a log code's name)
    /** Over its cap. Nothing was written. */
    TOO_BIG(5, "too big"), // l10n: not text (a log code's name)
    /** The storage failed some other way. Nothing half-written is left. */
    IO(6, "io"), // l10n: not text (a log code's name)
    /** The inbox is full (LinkInbox.MAX). Nothing was written. */
    FULL(7, "full"), // l10n: not text (a log code's name)
}

/** No message and no stack trace of its own: nothing of a record or a key is ever kept in one. */
class LinkStateException(val problem: LinkStateProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "LinkStateException(${problem.name})" // l10n: not text (toString for logs and tests)
}

/** A record's place: its [purpose] (format 1) and its 16-byte [id] in hex. */
data class RecordKey(val purpose: LinkPurpose, val id: String) {
    init {
        require(purpose.format == Envelope.FORMAT_SECRET && LinkBytes.unhex(id, Envelope.ID) != null)
    }

    val fileName: String get() = "${purpose.code}-$id${LinkRecords.EXT}" // l10n: not text (a file name)

    override fun toString(): String = "RecordKey($purpose)" // l10n: not text (toString for logs and tests)

    companion object {
        /** A record of a single kind (your identity): the id of zeros. */
        val ZERO: String = "0".repeat(32) // l10n: not text (an id)

        /** A libsignal prekey's id (a 24-bit number) as a record id. */
        fun number(n: Int): String {
            require(n > 0)
            return "%032x".format(n) // l10n: not text (an id)
        }

        fun of(fileName: String): RecordKey? {
            if (!fileName.endsWith(LinkRecords.EXT)) return null
            val stem = fileName.removeSuffix(LinkRecords.EXT)
            val dash = stem.indexOf('-')
            if (dash <= 0) return null
            val code = stem.substring(0, dash).toIntOrNull() ?: return null
            if (stem.substring(0, dash) != code.toString()) return null
            val purpose = LinkPurpose.of(code)?.takeIf { it.format == Envelope.FORMAT_SECRET } ?: return null
            val id = stem.substring(dash + 1).takeIf { LinkBytes.unhex(it, Envelope.ID) != null } ?: return null
            return RecordKey(purpose, id)
        }
    }
}

/**
 * :link's sealed records in [dir] (files/link/state), each sealed by [keys] (robot_link_v1). Every call may throw
 * [LinkStateException]. One instance per process; its calls take turns.
 */
class LinkRecords(val dir: File, private val keys: SecretWrap, private val random: SecureRandom = SecureRandom()) {
    private val lock = Any()

    fun file(key: RecordKey): File = File(dir, key.fileName)

    /** Whether the folder holds anything at all (a record, a write cut short, a name this version doesn't know). */
    fun anything(): Boolean = dir.listFiles().orEmpty().any { it.isFile }

    /** The records on storage, by name; other names are left alone. */
    fun keys(): List<RecordKey> = dir.listFiles().orEmpty().filter { it.isFile }.mapNotNull { RecordKey.of(it.name) }.sortedBy { it.fileName }

    /** [key]'s bytes, or null when there is no such record. */
    fun read(key: RecordKey): ByteArray? = synchronized(lock) {
        val f = file(key)
        if (!f.isFile) return null
        return try {
            Envelope.reader(BufferedInputStream(FileInputStream(f)), LinkSeal.KIND, LinkSeal.purpose(key.purpose), LinkBytes.unhex(key.id, Envelope.ID), secret = keys)
                .use { it.readAll(key.purpose.maxBytes.toInt()) }
        } catch (x: EnvelopeException) {
            // F5: a delete a crash cut short opens for no one and is no damage: it finishes here.
            if (x.problem == EnvelopeProblem.DAMAGED && finishDelete(f)) { syncDir(dir); return null }
            throw LinkStateException(problemOf(x.problem))
        } catch (x: LinkStateException) {
            throw x
        } catch (_: IOException) {
            throw LinkStateException(LinkStateProblem.IO)
        }
    }

    /** Every record, read once (at :link's start). The first that can't be read throws, and nothing is returned. */
    fun readAll(): Map<RecordKey, ByteArray> = synchronized(lock) {
        // Writes a crash cut short: never a record, so they go (each wrapped key destroyed first).
        dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(TMP) }.forEach(::destroy) // l10n: not text (a file name)
        val out = LinkedHashMap<RecordKey, ByteArray>()
        for (k in keys()) out[k] = read(k) ?: continue
        return out
    }

    /**
     * Writes [bytes] as [key]'s record: sealed with a fresh key into a temporary file, fsynced, renamed over the old one.
     * robot_link_v1 is made first only when the folder holds nothing yet.
     */
    fun write(key: RecordKey, bytes: ByteArray) = synchronized(lock) {
        if (bytes.size > key.purpose.maxBytes) throw LinkStateException(LinkStateProblem.TOO_BIG)
        dir.mkdirs()
        val create = !anything()
        val f = file(key)
        val tmp = File(dir, ".${f.name}$TMP") // l10n: not text (a file name)
        try {
            val out = SyncedOut(FileOutputStream(tmp))
            try {
                Envelope.writer(out, LinkSeal.KIND, LinkSeal.purpose(key.purpose), LinkBytes.unhex(key.id, Envelope.ID)!!, secret = keys,
                    create = create, random = random).use { it.write(bytes) }
            } catch (x: Exception) {
                try { out.close() } catch (_: IOException) {}
                throw x
            }
            if (!tmp.renameTo(f)) throw IOException()
            syncDir(dir)
        } catch (x: Exception) {
            destroy(tmp)
            throw when (x) {
                is EnvelopeException -> LinkStateException(problemOf(x.problem))
                is LinkStateException -> x
                else -> LinkStateException(LinkStateProblem.IO)
            }
        }
    }

    /** [key]'s record gone: its wrapped key overwritten first, then the file. No record is no change. */
    fun delete(key: RecordKey) = synchronized(lock) {
        destroy(file(key))
        syncDir(dir)
    }

    /**
     * Start over (your tap): every file here destroyed, then the folder. FB5: your identity first, so a cut part way leaves
     * a state that reads DAMAGED (Settings' warning, and Start over again), never a Robot working with part of its state.
     * [destroyOne] is a test's way to cut it.
     */
    fun deleteAll(destroyOne: (File) -> Unit = ::destroy) = synchronized(lock) {
        val identity = file(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO))
        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it != identity }.forEach(destroyOne)
        dir.delete()
    }

    companion object {
        const val EXT = ".rl" // l10n: not text (a file extension)
        private const val TMP = ".tmp" // l10n: not text (a file extension)

        fun problemOf(p: EnvelopeProblem): LinkStateProblem = when (p) {
            EnvelopeProblem.DAMAGED -> LinkStateProblem.DAMAGED
            EnvelopeProblem.NEWER -> LinkStateProblem.NEWER
            EnvelopeProblem.LOCKED -> LinkStateProblem.LOCKED
            EnvelopeProblem.GONE -> LinkStateProblem.GONE
            EnvelopeProblem.TOO_BIG -> LinkStateProblem.TOO_BIG
        }

        /**
         * Overwrites [f]'s wrapped key (and its size) in place and fsyncs, then deletes it: a sealed file can't be opened
         * again from a copy taken later. A file too short for a header is just deleted.
         */
        fun destroy(f: File) {
            if (!f.exists()) return
            try {
                RandomAccessFile(f, "rw").use { raf -> // l10n: not text (a file mode)
                    if (raf.length() >= 10) {
                        raf.seek(8)
                        val format = raf.readUnsignedShort()
                        val w = Envelope.wrappedSize(format)
                        if (format in 1..Envelope.FORMAT_MAX && raf.length() >= Envelope.headerSize(format)) {
                            raf.seek(WRAPPED_AT.toLong())
                            raf.write(ByteArray(2 + w))
                            raf.fd.sync()
                        }
                    }
                }
            } catch (_: IOException) {}
            f.delete()
        }

        /**
         * F5: [f] is a sealed file [destroy] overwrote and never got to unlink (its whole wrapped key and size zero, what
         * nothing else writes): it goes, and true. Damage that leaves any of that key in place stays damage.
         */
        fun finishDelete(f: File): Boolean {
            val destroyed = try {
                RandomAccessFile(f, "r").use { raf -> // l10n: not text (a file mode)
                    val lead = ByteArray(10)
                    if (raf.length() < lead.size) return false
                    raf.readFully(lead)
                    val format = ((lead[8].toInt() and 0xFF) shl 8) or (lead[9].toInt() and 0xFF)
                    if (!LinkSeal.KIND.isMagic(lead.copyOf(8)) || format !in 1..Envelope.FORMAT_MAX || raf.length() < Envelope.headerSize(format)) return false
                    val key = ByteArray(2 + Envelope.wrappedSize(format))
                    raf.seek(WRAPPED_AT.toLong())
                    raf.readFully(key)
                    key.all { it.toInt() == 0 }
                }
            } catch (_: IOException) {
                false
            }
            if (destroyed) f.delete()
            return destroyed
        }

        /** Where the wrapped key's size field starts, in either format. */
        private const val WRAPPED_AT = 28

        fun syncDir(d: File) {
            try {
                FileChannel.open(d.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {}
        }
    }
}

/** Where :link keeps what it has sealed, under files/. Named here so a screen in the main process can ask, without a key. */
object LinkFolders {
    const val DIR = "link" // l10n: not text (a folder name)
    const val STATE = "state" // l10n: not text (a folder name)
    const val INBOX = "inbox" // l10n: not text (a folder name)
    const val LIST_INBOX = "inbox_lists" // l10n: not text (a folder name)

    /** F59: whether anything sealed is here, from the file names alone (no key, nothing opened, :link not woken). */
    fun holdsAnything(filesDir: File): Boolean =
        listOf(STATE, INBOX, LIST_INBOX).any { d -> File(File(filesDir, DIR), d).listFiles().orEmpty().any { it.isFile } }
}

/** A file written whole: buffered, then flushed and fsynced as it closes (before the rename that puts it in place). */
internal class SyncedOut(private val file: FileOutputStream) : BufferedOutputStream(file, 65_552) {
    override fun close() {
        try {
            flush()
            file.fd.sync()
        } finally {
            file.close()
        }
    }
}
