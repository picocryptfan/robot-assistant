// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.PeopleStore

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §2.3, §2.6, §2.7, §6.2, §6.3, D8, D12; job L5): what the main app
// knows of a pairing and asks :link to change, as numbers, strings and bytes across LinkService (core LinkIpcProtocol).
// The pairings themselves (keys, routes, levels, who they are in People) live in :link's sealed state only (D12); main
// reads a [PairingView] of each through LinkService and never keeps one. Strict both ways: a view that doesn't read whole
// is nothing, never a guess.
//
// What a view never holds: a key, a token, an address, a route. The other Robot's name is its claim ("says they're “Sam”"),
// shown as one; the name main shows is your People name for [PairingView.person].

/** Where a pairing stands. Codes are stable (the link module's PairingState has the same ones). */
enum class PairingPhase(val code: Int) {
    /** Your hello went out to someone's code; their Robot hasn't answered yet. */
    SENT(1),
    /** Paired: a session both ways. */
    ACTIVE(2),
    /** Your Robot moved to this phone (a restore) and told them; their answer hasn't come yet (§2.7). Paused. */
    MOVING(3),
    /** Their Robot says it moved to a new phone: paused until you accept or keep the old one (§2.7). */
    MOVED(4),
    /** Their Robot unpaired: nothing is left but this note, for 30 days (§2.6). */
    ENDED(5);

    companion object {
        fun of(code: Int): PairingPhase? = entries.firstOrNull { it.code == code }
    }
}

/** How you know the other Robot's identity key is theirs. Codes are stable. */
enum class Verified(val code: Int) {
    /** Paired at a distance, numbers not compared yet. */
    NO(0),
    /** You photographed their code (§2.3): the key is the one their phone showed you. */
    IN_PERSON(1),
    /** You compared the safety number and tapped "They match". */
    COMPARED(2);

    companion object {
        fun of(code: Int): Verified? = entries.firstOrNull { it.code == code }
    }
}

/** Levels per capability as :link keeps and main shows them (§6.3, D8). Codes are stable: never renumber one. */
object LinkLevels {
    fun code(c: LinkCapability): Int = when (c) {
        LinkCapability.FREE_BUSY -> 1
        LinkCapability.EVENTS -> 2
        LinkCapability.SPLITS -> 3
        LinkCapability.PAYMENTS -> 4
        LinkCapability.FILES -> 5
        LinkCapability.FILE_REQUESTS -> 6
        LinkCapability.FOLDERS -> 7
        LinkCapability.CONTACT_CARDS -> 8
        LinkCapability.ETA -> 9
        LinkCapability.REMINDERS -> 10
        LinkCapability.QUESTIONS -> 11
        LinkCapability.LISTS -> 12
    }

    fun capability(code: Int): LinkCapability? = LinkCapability.entries.firstOrNull { code(it) == code }

    fun levelCode(l: LinkLevel): Int = when (l) { LinkLevel.OFF -> 0; LinkLevel.ASK -> 1; LinkLevel.AUTO -> 2 }

    fun level(code: Int): LinkLevel? = LinkLevel.entries.firstOrNull { levelCode(it) == code }

    fun presetCode(p: LinkPreset): Int = when (p) { LinkPreset.FAMILY_OR_FRIEND -> 1; LinkPreset.SOMEONE_ELSE -> 2 }

    fun preset(code: Int): LinkPreset? = LinkPreset.entries.firstOrNull { presetCode(it) == code }

    /**
     * The rows of a person's permissions page, in order. Payment requests wait until after the full test (the user, 21 Oct):
     * their capability stays at its preset's level, never shown and never changed here. L16: splits the same, until L9 builds them.
     */
    val SHOWN: List<LinkCapability> = listOf(
        LinkCapability.FREE_BUSY, LinkCapability.EVENTS, LinkCapability.REMINDERS, LinkCapability.ETA, LinkCapability.FILES,
        LinkCapability.FILE_REQUESTS, LinkCapability.QUESTIONS, LinkCapability.CONTACT_CARDS,
        LinkCapability.LISTS, LinkCapability.FOLDERS,
    )

    /** Every capability at [preset]'s level (D8: nothing starts at Answer on its own). */
    fun of(preset: LinkPreset): Map<LinkCapability, LinkLevel> = LinkPolicy.preset(preset)

    /** [levels] as bytes: one (capability code, level code) pair each, in code order. */
    fun encode(levels: Map<LinkCapability, LinkLevel>): ByteArray {
        val w = BinWriter(2 * LinkCapability.entries.size)
        for (c in LinkCapability.entries.sortedBy { code(it) }) {
            val l = levels[c] ?: continue
            w.u8(code(c)).u8(levelCode(l))
        }
        return w.toByteArray()
    }

    /**
     * The levels in [b], or null when it isn't exactly every capability once, in code order, each at a level it may have
     * (Answer on its own only where it is allowed: never money or contact cards).
     */
    fun decode(b: ByteArray): Map<LinkCapability, LinkLevel>? {
        if (b.size != 2 * LinkCapability.entries.size) return null
        val out = LinkedHashMap<LinkCapability, LinkLevel>()
        var last = 0
        for (i in b.indices step 2) {
            val code = b[i].toInt() and 0xFF
            val c = capability(code) ?: return null
            val l = level(b[i + 1].toInt() and 0xFF) ?: return null
            if (code <= last || l !in LinkPolicy.levels(c)) return null
            last = code
            out[c] = l
        }
        return out
    }

    /**
     * [current] with [cap] set to [to] by your tap on the permissions page: null when [cap] can't take [to] (Answer on its own
     * where it is never allowed) or isn't one of the page's rows. A tap may loosen as well as tighten (LinkPolicy.change).
     */
    fun set(current: Map<LinkCapability, LinkLevel>, cap: LinkCapability, to: LinkLevel): Map<LinkCapability, LinkLevel>? {
        if (cap !in SHOWN) return null
        val from = current[cap] ?: return null
        if (!LinkPolicy.change(cap, from, to, tap = true)) return null
        return LinkedHashMap(current).also { it[cap] = to }
    }
}

/** One pairing as the main app sees it: no key, token, address or route. */
class PairingView(
    val id: String,
    val phase: PairingPhase,
    val mode: LinkMode,
    /** Unix seconds. */
    val created: Long,
    /** The name their Robot asked to be shown ("says they're “Sam”"): a claim; empty until it sent one. */
    val claimed: String,
    /** Who this is in your People (a People id), or null until you said. */
    val person: String?,
    val verified: Verified,
    /** When it was verified (Unix seconds), 0 when not. */
    val verifiedAt: Long,
    /** The preset you picked (D8), or null: until you pick, "Someone else"'s levels apply. */
    val preset: LinkPreset?,
    val levels: Map<LinkCapability, LinkLevel>,
    /** You asked to switch to Private; their answer hasn't come. */
    val lowerAsked: Boolean,
    /** They ask to switch to Private: your answer waits (§2.2). */
    val lowerOffered: Boolean,
    /** When they last kept Anonymous after you asked (Unix seconds), 0 never. */
    val lowerDeclinedAt: Long,
    /** MOVED: the name the moving Robot asked to be shown, a claim. Empty otherwise. */
    val movedClaimed: String,
    /** When the phase began, for MOVING, MOVED and ENDED (Unix seconds); 0 otherwise. */
    val since: Long,
) {
    init {
        require(LinkIds.isId(id) && created >= 0 && verifiedAt >= 0 && lowerDeclinedAt >= 0 && since >= 0)
        require(claimed.isEmpty() || LinkText.ok(claimed, PairHello.MAX_NAME))
        require(movedClaimed.isEmpty() || LinkText.ok(movedClaimed, PairHello.MAX_NAME))
        require(person == null || PeopleStore.validId(person))
        require(levels.keys == LinkCapability.entries.toSet() && levels.all { (c, l) -> l in LinkPolicy.levels(c) })
        require((verified == Verified.NO) == (verifiedAt == 0L))
    }

    override fun toString(): String = "PairingView(phase=$phase, mode=$mode, verified=$verified)" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1)
        /** People ids are UUIDs (36 characters); more is never one. */
        private const val PERSON_MAX = 64
        /** The pairings in one answer at most (§3.7's 100, and room for ENDED notes). */
        const val MAX = 200

        fun encodeAll(views: List<PairingView>): ByteArray {
            require(views.size <= MAX)
            val w = BinWriter(256 * views.size + 8)
            w.bytes(MAGIC).u16(views.size)
            for (v in views) {
                w.bytes(LinkBytes.unhex(v.id, LinkIds.BYTES)!!).u8(v.phase.code).u8(v.mode.code).u64(v.created)
                text(w, v.claimed)
                val p = (v.person ?: "").toByteArray(Charsets.US_ASCII)
                w.u8(p.size).bytes(p)
                w.u8(v.verified.code).u64(v.verifiedAt).u8(v.preset?.let(LinkLevels::presetCode) ?: 0)
                w.bytes(LinkLevels.encode(v.levels))
                w.u8((if (v.lowerAsked) 1 else 0) or (if (v.lowerOffered) 2 else 0)).u64(v.lowerDeclinedAt)
                text(w, v.movedClaimed)
                w.u64(v.since)
            }
            return w.toByteArray()
        }

        /** The views in [b], or null when it doesn't read whole. */
        fun decodeAll(b: ByteArray): List<PairingView>? = try {
            val r = BinReader(b)
            if (!r.bytes(MAGIC.size).contentEquals(MAGIC)) throw LinkBad
            val n = r.u16()
            if (n > MAX) throw LinkBad
            val out = ArrayList<PairingView>(n)
            repeat(n) {
                val id = LinkBytes.hex(r.bytes(LinkIds.BYTES))
                val phase = PairingPhase.of(r.u8()) ?: throw LinkBad
                val mode = LinkMode.of(r.u8()) ?: throw LinkBad
                val created = r.u64()
                val claimed = text(r)
                val pn = r.u8()
                if (pn > PERSON_MAX) throw LinkBad
                val person = String(r.bytes(pn), Charsets.US_ASCII).ifEmpty { null }
                val verified = Verified.of(r.u8()) ?: throw LinkBad
                val verifiedAt = r.u64()
                val presetCode = r.u8()
                val preset = if (presetCode == 0) null else LinkLevels.preset(presetCode) ?: throw LinkBad
                val levels = LinkLevels.decode(r.bytes(2 * LinkCapability.entries.size)) ?: throw LinkBad
                val flags = r.u8()
                if (flags and 3.inv() != 0) throw LinkBad
                val declined = r.u64()
                val movedClaimed = text(r)
                val since = r.u64()
                out += PairingView(id, phase, mode, created, claimed, person, verified, verifiedAt, preset, levels, flags and 1 != 0, flags and 2 != 0,
                    declined, movedClaimed, since)
            }
            r.end()
            if (out.map { it.id }.toSet().size != out.size) throw LinkBad
            out
        } catch (_: LinkBad) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun text(w: BinWriter, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            w.u8(b.size).bytes(b)
        }

        private fun text(r: BinReader): String {
            val n = r.u8()
            if (n > PairHello.MAX_NAME_BYTES) throw LinkBad
            return LinkBytes.utf8(r.bytes(n)) ?: throw LinkBad
        }
    }
}

/** A Robot you blocked (§2.6, §6.1's Blocked list): [label] is the name you knew it by, [at] when (Unix seconds). */
class BlockedView(val id: String, val label: String, val at: Long) {
    init {
        require(LinkIds.isId(id) && at >= 0 && (label.isEmpty() || LinkText.ok(label, BLOCK_LABEL)))
    }

    override fun toString(): String = "BlockedView" // l10n: not text (toString for logs and tests)

    companion object {
        /** A People name may be longer than a Robot's own name: 80 characters at most are kept. */
        const val BLOCK_LABEL = 80
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), 1)
        const val MAX = 500

        /** [label] as a block keeps it: one clean line of at most [BLOCK_LABEL] characters, or empty. */
        fun label(s: String?): String = s?.let { LinkText.clean(it.replace('\n', ' ').trim(), BLOCK_LABEL) }.orEmpty()

        fun encodeAll(views: List<BlockedView>): ByteArray {
            require(views.size <= MAX)
            val w = BinWriter(64 * views.size + 8)
            w.bytes(MAGIC).u16(views.size)
            for (v in views) {
                val b = v.label.toByteArray(Charsets.UTF_8)
                w.bytes(LinkBytes.unhex(v.id, LinkIds.BYTES)!!).u16(b.size).bytes(b).u64(v.at)
            }
            return w.toByteArray()
        }

        fun decodeAll(b: ByteArray): List<BlockedView>? = try {
            val r = BinReader(b)
            if (!r.bytes(MAGIC.size).contentEquals(MAGIC)) throw LinkBad
            val n = r.u16()
            if (n > MAX) throw LinkBad
            val out = List(n) {
                val id = LinkBytes.hex(r.bytes(LinkIds.BYTES))
                val len = r.u16()
                if (len > BLOCK_LABEL * 4) throw LinkBad
                val label = LinkBytes.utf8(r.bytes(len)) ?: throw LinkBad
                BlockedView(id, label, r.u64())
            }
            r.end()
            out
        } catch (_: LinkBad) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** What became of "Make a code" (LinkIpcProtocol.MAKE_CODE). Codes are stable. */
enum class MakeCodeResult(val code: Int) {
    OK(1),
    /** Robot's network access is off (the Network toggle). */
    NO_NETWORK(2),
    /** Five codes are open already: cancel one first. */
    TOO_MANY(3),
    /** No way for a hello to reach you: no push app, and Tor didn't start. */
    NO_ROUTE(4),
    /** 100 pairings, or 10 new ones today (§2.3's limits). */
    LIMIT(5),
    /** Anything else (the state couldn't be written). */
    FAILED(6);

    companion object {
        fun of(code: Int): MakeCodeResult? = entries.firstOrNull { it.code == code }
    }
}

/** What became of a code you scanned or pasted (LinkIpcProtocol.USE_CODE). Codes are stable. */
enum class UseCodeResult(val code: Int) {
    /** Your hello is on its way: the pairing is SENT until their Robot answers. */
    SENT(1),
    /** In person, their Robot sends the hello (its key sorts first, §2.3): your photo is kept for checking it. */
    WAITING(2),
    /** In person, you're paired already: the photo confirmed their key, so the pairing is Verified in person. */
    VERIFIED(3),
    /** You're paired with this Robot already. */
    ALREADY(4),
    /** "This code has expired. Ask for a new one." */
    EXPIRED(5),
    /** "Update Robot to pair with this code." */
    NEWER(6),
    /** Not a pairing code, or one a messaging app damaged. */
    BAD(7),
    /** Your own code. */
    OWN(8),
    NO_NETWORK(9),
    /** A Robot you blocked: unblock it first. */
    BLOCKED(10),
    /** 100 pairings, or 10 new ones today. */
    LIMIT(11),
    /** No way for its answer to reach you: no push app, and Tor didn't start. */
    NO_ROUTE(12),
    FAILED(13);

    /** Whether a pairing id comes with it. */
    val hasPairing: Boolean get() = this == SENT || this == VERIFIED || this == ALREADY

    companion object {
        fun of(code: Int): UseCodeResult? = entries.firstOrNull { it.code == code }
    }
}

/** Where a code you made stands (LinkIpcProtocol.CODE_STATE). Codes are stable. */
enum class CodeState(val code: Int) {
    /** Open: waiting for someone's hello. */
    OPEN(1),
    /** Someone's hello used it: the pairing id comes with it. */
    USED(2),
    /** In person, the Robot that answered isn't the one whose code you photographed: "This code doesn't match the Robot that answered." */
    MISMATCH(3),
    /** Expired, cancelled, closed after five wrong tries, or gone. */
    CLOSED(4),
    /**
     * L5-fix: open, and in person a hello came before your photo of their code: it waits (not answered, the code not spent)
     * until your photo says whose it is. "Their Robot said hello. Now take a photo of their code."
     */
    HELD(5);

    companion object {
        fun of(code: Int): CodeState? = entries.firstOrNull { it.code == code }
    }
}

/** The safety number (§2.3, §6.2): 60 digits, compared aloud or side by side as 12 groups of 5. */
object SafetyNumber {
    const val DIGITS = 60
    const val GROUP = 5

    fun ok(s: String): Boolean = s.length == DIGITS && s.all { it in '0'..'9' }

    /** [digits] as 12 groups of 5. */
    fun groups(digits: String): List<String> {
        require(ok(digits))
        return digits.chunked(GROUP)
    }
}
