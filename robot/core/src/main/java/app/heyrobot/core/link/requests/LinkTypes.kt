// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link.requests

import app.heyrobot.core.link.LinkCapability

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.3, §4.1, D22; job L1): every request type protocol v1 knows,
// with its name on the wire, a code that never changes (the pairing hello's `caps` is a bitmap of codes), and the
// capability whose level decides it (none for pairing and control). New types are added with new names and codes, never
// by changing a type: a body has exactly the keys its type lists, so a new field means a new type, offered through
// `caps` (a Robot never sends a type the other side didn't list, and an unknown type is answered `unsupported`).
//
// Reserved, refused as `unsupported` in this build and never listed in `caps`: `pay.` (payment requests: the user, 21
// Oct, payments wait until after the full test; codes 110 to 119 are kept for them), `act.` (doing something on your own
// other device, §13.1; codes 120 to 129), `grp.` (group plans travel as events with a group id in 1.5, `ev.propose`'s
// `grp`; L11 added `grp.status` at 130, and the rest of `grp.` stays refused; codes 131 to 139), `skill.` (skill offers, §13.3; codes 140 to 149).

enum class RequestType(val wire: String, val code: Int, val capability: LinkCapability?) {
    // Pairing and control (§4.1's last row). The pairing hello and the moved hello are binary (PairHello), not requests.
    CAPS("caps", 1, null), // l10n: not text (a wire name)
    NET_HINT("net.hint", 2, null), // l10n: not text (a wire name)
    MODE_RAISE("mode.raise", 3, null), // l10n: not text (a wire name)
    MODE_LOWER("mode.lower", 4, null), // l10n: not text (a wire name)
    UNPAIR("unpair", 5, null), // l10n: not text (a wire name)
    ACK("ack", 6, null), // l10n: not text (a wire name)

    FB_ASK("fb.ask", 10, LinkCapability.FREE_BUSY), // l10n: not text (a wire name)
    FB_REPLY("fb.reply", 11, LinkCapability.FREE_BUSY), // l10n: not text (a wire name)

    EV_PROPOSE("ev.propose", 20, LinkCapability.EVENTS), // l10n: not text (a wire name)
    EV_ACCEPT("ev.accept", 21, LinkCapability.EVENTS), // l10n: not text (a wire name)
    EV_DECLINE("ev.decline", 22, LinkCapability.EVENTS), // l10n: not text (a wire name)
    EV_CHANGE("ev.change", 23, LinkCapability.EVENTS), // l10n: not text (a wire name)
    EV_CANCEL("ev.cancel", 24, LinkCapability.EVENTS), // l10n: not text (a wire name)

    SP_REQUEST("sp.request", 30, LinkCapability.SPLITS), // l10n: not text (a wire name)
    SP_REPLY("sp.reply", 31, LinkCapability.SPLITS), // l10n: not text (a wire name)
    SP_SETTLE("sp.settle", 32, LinkCapability.SPLITS), // l10n: not text (a wire name)

    FILE_OFFER("file.offer", 40, LinkCapability.FILES), // l10n: not text (a wire name)
    FILE_ACCEPT("file.accept", 41, LinkCapability.FILES), // l10n: not text (a wire name)
    FILE_KEY("file.key", 42, LinkCapability.FILES), // l10n: not text (a wire name)
    FILE_DECLINE("file.decline", 43, LinkCapability.FILES), // l10n: not text (a wire name)
    FILE_DONE("file.done", 44, LinkCapability.FILES), // l10n: not text (a wire name)
    FILE_ASK("file.ask", 45, LinkCapability.FILE_REQUESTS), // l10n: not text (a wire name)

    GRP_STATUS("grp.status", 130, LinkCapability.EVENTS), // l10n: not text (a wire name)
    FOLD_JOIN("fold.join", 50, LinkCapability.FOLDERS), // l10n: not text (a wire name)
    FOLD_OP("fold.op", 51, LinkCapability.FOLDERS), // l10n: not text (a wire name)
    FOLD_LEAVE("fold.leave", 52, LinkCapability.FOLDERS), // l10n: not text (a wire name)

    CARD_OFFER("card.offer", 60, LinkCapability.CONTACT_CARDS), // l10n: not text (a wire name)
    ETA_TELL("eta.tell", 70, LinkCapability.ETA), // l10n: not text (a wire name)
    REM_OFFER("rem.offer", 80, LinkCapability.REMINDERS), // l10n: not text (a wire name)

    Q_ASK("q.ask", 90, LinkCapability.QUESTIONS), // l10n: not text (a wire name)
    Q_REPLY("q.reply", 91, LinkCapability.QUESTIONS), // l10n: not text (a wire name)

    // Shared lists (lists-plan.md §9, which the user's order of 22 Oct puts in 1.5).
    LS_OFFER("ls.offer", 100, LinkCapability.LISTS), // l10n: not text (a wire name)
    LS_OP("ls.op", 101, LinkCapability.LISTS), // l10n: not text (a wire name)
    LS_LEAVE("ls.leave", 102, LinkCapability.LISTS); // l10n: not text (a wire name)

    /** Pairing and control: handled by code, whatever the levels say. */
    val control: Boolean get() = capability == null

    /** Every request is answered by an `ack` but an ack itself (never answered, so two Robots can't loop) and `unpair` (best effort). */
    val answeredByAck: Boolean get() = this != ACK && this != UNPAIR

    companion object {
        private val byWire = entries.associateBy { it.wire }
        private val byCode = entries.associateBy { it.code }

        fun of(wire: String): RequestType? = byWire[wire]
        fun ofCode(code: Int): RequestType? = byCode[code]

        /** How a type's name is spelled: a family of 1 to 8 letters and a verb of 2 to 8 ("q.ask", "fb.ask"), or one word of 2 to 8 ("caps"). */
        private val NAME = Regex("^([a-z]{1,8}\\.[a-z]{2,8}|[a-z]{2,8})$")
        fun wellFormed(wire: String): Boolean = NAME.matches(wire)

        /** Families refused in this build whatever the verb (see above). */
        val RESERVED_PREFIXES = listOf("pay.", "act.", "skill.") // l10n: not text (wire names)
        fun reserved(wire: String): Boolean = RESERVED_PREFIXES.any { wire.startsWith(it) }

        /** Codes kept for the reserved families; no type here may use one (130 is `grp.status`). */
        val RESERVED_CODES: Set<Int> = (110..149).toSet() - 130
    }
}

/** What one Robot reads, as the pairing hello's bitmap (32 bytes, bit c of byte c / 8, most significant first) and `caps`' names. */
object LinkCaps {
    const val BITMAP = 32

    /**
     * Everything this build reads and answers. L16: splits wait for L9, so their names stay reserved but a hello and `caps`
     * don't claim them, and a peer's `sp.*` is answered `unsupported` (LinkNode).
     */
    val SUPPORTED: Set<RequestType> = RequestType.entries.toSet() - setOf(RequestType.SP_REQUEST, RequestType.SP_REPLY, RequestType.SP_SETTLE)

    fun bitmap(types: Set<RequestType>): ByteArray {
        val b = ByteArray(BITMAP)
        for (t in types) b[t.code / 8] = (b[t.code / 8].toInt() or (0x80 ushr (t.code % 8))).toByte()
        return b
    }

    /** The types in [bitmap] this build knows. Bits it doesn't know are a newer Robot's types: never an error. */
    fun fromBitmap(bitmap: ByteArray): Set<RequestType> {
        require(bitmap.size == BITMAP)
        return RequestType.entries.filter { (bitmap[it.code / 8].toInt() and (0x80 ushr (it.code % 8))) != 0 }.toSet()
    }
}
