// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link.requests

import app.heyrobot.core.link.ContactCard
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkText
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.LinkVCard
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.PushRoute
import java.time.LocalDate
import java.time.format.DateTimeParseException

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1, lists-plan.md §9; job L1): every request type's body, with its
// keys, their types and caps. A body has exactly the keys its type lists (optional ones may be absent, never null); any
// other key, a wrong type or a value past its cap drops the whole request (§3.3). Reading is the isolated `:linkparse`'s
// job (L4: `LinkRequests.decode(` only there); this file is the one definition both sides use.
//
// Shared rules:
//   - Ids ("ev", "sp", "file", "fold", "item", "list", "re", "grp") are 32 lower-case hex characters (LinkIds).
//   - Times are Unix seconds between 2026 and 2100, whole minutes where a person picked them.
//   - Money is a whole number of minor units (cents) with an ISO 4217 code: never a decimal.
//   - Things that change over time (an event, a split, a folder's item, a list's item) carry their id and "rev", a counter
//     from 1 to 1,000,000,000: the highest (rev, request id) wins, a late older change is ignored (LinkOrder).
//   - Free text is LinkText: NFC, trimmed, none of the characters that hide or reorder text, capped in characters.
//   - No body carries a payment: the `pay.` family is reserved (LinkTypes).

object RequestCaps {
    /** Names: caps' name, a folder's, a list's (Lists §2.1), a file's merchant or title (Files §4.2). */
    const val NAME = 40
    /** A group plan: the row's 8 people are you and these. */
    const val GROUP_OTHERS = 7
    const val TITLE = 60
    const val PLACE = 80
    const val NOTE = 300
    /** An event proposal offers 1 to 3 times. */
    const val TIMES = 3
    const val EVENT_MIN_MINUTES = 5L
    const val EVENT_MAX_MINUTES = 24 * 60L
    /** Free/busy: a window of at most 14 days, busy blocks in 30-minute steps from its start. */
    const val FB_WINDOW_SECONDS = 14 * 24 * 3600L
    const val FB_STEP_SECONDS = 1_800L
    /** Every other half hour of 14 days busy: the most blocks a reply can need. */
    const val FB_BLOCKS = 336
    /** D19: at most 40 lines of a receipt. */
    const val SPLIT_ITEMS = 40
    const val SPLIT_ITEM_TEXT = 60
    const val SPLIT_NOTE = 100
    const val SPLIT_PEOPLE = 20
    /** 100 million in any currency, in minor units. */
    const val MAX_MONEY = 10_000_000_000L
    const val FILE_NAME = 80
    /** Files' cap (§3.7). */
    const val FILE_MAX_BYTES = 200L * 1024 * 1024
    const val FILE_ASK = 120
    const val VCARD_BYTES = 2_048
    const val ETA_MAX_MINUTES = 600L
    const val ETA_PLACE = 40
    const val REMINDER = 120
    const val QUESTION = 200
    const val ANSWER = 2_000
    /** Lists §2.1 and §2.4. */
    const val LIST_ITEM = 120
    const val LIST_QTY = 16
    const val LIST_ITEMS = 500
    /**
     * One `ls.offer` carries at most 40 items, so a list of 500 travels in at most 13 parts, each under 32 KiB even when
     * every item's 120 characters take 4 bytes each (LinkRequestsTest; 50 such items would not fit).
     */
    const val LIST_PART_ITEMS = 40
    const val LIST_PARTS = 13
    /** One `ls.op` carries at most 40 changes, for the same reason. */
    const val LIST_OPS = 40
    const val LIST_POS = 1_000_000_000_000_000L
    const val CAPS_TYPES = 64
    const val ADDRS = 4
    const val MAX_REV = 1_000_000_000L
    /** 2026-01-01 and 2100-01-01, UTC. */
    const val MIN_TIME = 1_767_225_600L
    const val MAX_TIME = 4_102_444_800L
}

private val TIME = NumS(RequestCaps.MIN_TIME, RequestCaps.MAX_TIME)
private val MINUTE = NumS(RequestCaps.MIN_TIME, RequestCaps.MAX_TIME, 60)
private val REV = NumS(1, RequestCaps.MAX_REV)
private val MONEY = NumS(0, RequestCaps.MAX_MONEY)
private val CURRENCY = AsciiS(3) { c -> c.length == 3 && c.all { it in 'A'..'Z' } }
private val SHA256 = HexS(32)
private val DATE = AsciiS(10) { d -> d.length == 10 && try { LocalDate.parse(d).toString() == d } catch (_: DateTimeParseException) { false } }

/** A request's typed body. Never shows its fields in a log line. */
sealed class LinkBody {
    abstract val type: RequestType
    internal abstract fun json(): Map<String, Any?>
    final override fun toString(): String = "LinkBody(${type.wire})" // l10n: not text (toString for logs and tests)
}

// Pairing and control ----------------------------------------------------------------------------------------------------

/**
 * What the sender reads ([types]: type names, sorted, each at most once; a newer Robot's names this one doesn't know are
 * fine) and the name it wants shown ([name], "Name other Robots see"; a claim, never what picks a person).
 */
data class Caps(val types: List<String>, val name: String) : LinkBody() {
    override val type get() = RequestType.CAPS
    override fun json() = obj { put("types", types); put("name", name) }
    /** The types this build knows. */
    val known: Set<RequestType> get() = types.mapNotNull { RequestType.of(it) }.toSet()

    companion object {
        internal val SPEC = ObjS(mapOf("types" to ListS(AsciiS(17) { RequestType.wellFormed(it) }, 0, RequestCaps.CAPS_TYPES, unique = true), "name" to TextS(RequestCaps.NAME)))
        internal fun read(f: Fields) = Caps(f.strings("types"), f.s("name")).takeIf { it.types == it.types.sorted() }

        /** This build's caps. */
        fun of(name: String, types: Set<RequestType> = LinkCaps.SUPPORTED) = Caps(types.map { it.wire }.sorted(), name)
    }
}

/**
 * Where to reach the sender, given only inside the session (§2.2, §4.6): a push address, the onion address with
 * [onionAuth], the client authorization key the sender made for this pairing (Tor's x25519 key, 32 bytes: its onion
 * service answers only Robots holding one, D5; L3, before any release), addresses on a shared network (Private only:
 * LinkPolicy), and [token], the connection token for its next live connection (§3.2).
 *
 * 1.5 L5 (before any release, so no Robot reads the older layout): [moved], the token a moved hello to the sender must
 * carry (§2.7, Q5): 16 random bytes the sender made for this pairing alone, given only here, inside the session, and made
 * again after each move it accepts. A pairing code's token is never used for it: a code can be photographed or forwarded,
 * and must be worth nothing once it has paired.
 *
 * 1.5 L5-fix (before any release, so no Robot reads the older layout): [mode], required, the mode the sender holds the
 * pairing to: "anonymous" unless it is Private or has asked to switch to Private (its `mode.lower` waits for an answer).
 * The stricter wins: "anonymous" raises a Private pairing on the side that reads it, so two sides whose modes drifted apart
 * (a `mode.raise` lost, a pasted code's mode changed on the way) end Anonymous. A hint sent while a switch to Private is
 * being agreed says "private", so it never undoes that switch.
 */
class NetHint(
    val mode: LinkMode,
    val push: PushRoute? = null,
    val onion: OnionKey? = null,
    val addrs: List<LocalAddress> = emptyList(),
    val token: String? = null,
    onionAuth: ByteArray? = null,
    val moved: String? = null,
) : LinkBody() {
    override val type get() = RequestType.NET_HINT
    private val oauth = onionAuth?.copyOf()
    val onionAuth: ByteArray? get() = oauth?.copyOf()

    init {
        require(push != null || onion != null || addrs.isNotEmpty() || token != null || moved != null)
        require(addrs.size <= RequestCaps.ADDRS && addrs.toSet().size == addrs.size && (token == null || LinkIds.isId(token)) && (moved == null || LinkIds.isId(moved)))
        require((onion == null) == (oauth == null) && (oauth == null || OnionKey.authOk(oauth)))
    }

    override fun json() = obj {
        put("mode", mode.wire)
        put("push", push?.let { p -> obj { put("url", p.endpoint); put("key", LinkBytes.hex(p.p256dh)); put("auth", LinkBytes.hex(p.auth)) } })
        put("onion", onion?.let { LinkBytes.hex(it.key) })
        put("oauth", oauth?.let { LinkBytes.hex(it) })
        put("addrs", addrs.map { it.hex() }.takeIf { it.isNotEmpty() })
        put("tok", token)
        put("moved", moved)
    }

    override fun equals(other: Any?) = other is NetHint && LinkJson.write(other.json()) == LinkJson.write(json())
    override fun hashCode() = LinkJson.write(json()).hashCode()

    companion object {
        private val PUSH = ObjS(mapOf("url" to AsciiS(PushRoute.MAX_ENDPOINT) { PushRoute.endpointOk(it) }, "key" to HexS(PushRoute.KEY), "auth" to HexS(PushRoute.AUTH)))
        internal val SPEC = ObjS(mapOf("mode" to OneOfS(*LinkMode.entries.map { it.wire }.toTypedArray())), mapOf(
            "push" to PUSH, "onion" to HexS(OnionKey.SIZE), "oauth" to HexS(OnionKey.SIZE),
            "addrs" to ListS(AsciiS(38) { LocalAddress.ofHex(it) != null }, 1, RequestCaps.ADDRS, unique = true), "tok" to IdS, "moved" to IdS,
        ))

        internal fun read(f: Fields): NetHint? {
            val p = f.objOrNull("push")?.let { x ->
                val k = LinkBytes.unhex(x.s("key"), PushRoute.KEY)!!
                val a = LinkBytes.unhex(x.s("auth"), PushRoute.AUTH)!!
                if (!PushRoute.valid(x.s("url"), k, a) || !PushRoute.peerEndpointOk(x.s("url"))) return null
                PushRoute(x.s("url"), k, a)
            }
            val onion = f.so("onion")?.let { OnionKey(LinkBytes.unhex(it, OnionKey.SIZE)!!) }
            val oauth = f.so("oauth")?.let { LinkBytes.unhex(it, OnionKey.SIZE)!! }
            val addrs = f.stringsOrEmpty("addrs").map { h -> LocalAddress.ofHex(h)!! }
            if (p == null && onion == null && addrs.isEmpty() && f.so("tok") == null && f.so("moved") == null) return null
            if (addrs.toSet().size != addrs.size || (onion == null) != (oauth == null)) return null
            return NetHint(LinkMode.ofWire(f.s("mode"))!!, p, onion, addrs, f.so("tok"), oauth, f.so("moved"))
        }
    }
}

/** "Raise to Anonymous": switches the pairing on both sides at once (§2.2). */
class ModeRaise : LinkBody() {
    override val type get() = RequestType.MODE_RAISE
    override fun json() = obj { put("to", "anonymous") } // l10n: not text (a wire value)
    override fun equals(other: Any?) = other is ModeRaise
    override fun hashCode() = 1

    companion object {
        internal val SPEC = ObjS(mapOf("to" to OneOfS("anonymous"))) // l10n: not text (a wire value)
    }
}

/** "Ask to switch to Private": a card on the other side; only its owner's tap lowers it (§2.2). */
class ModeLower : LinkBody() {
    override val type get() = RequestType.MODE_LOWER
    override fun json() = obj { put("to", "private") } // l10n: not text (a wire value)
    override fun equals(other: Any?) = other is ModeLower
    override fun hashCode() = 2

    companion object {
        internal val SPEC = ObjS(mapOf("to" to OneOfS("private"))) // l10n: not text (a wire value)
    }
}

/** The pairing ended on the sender's side (§2.6): best effort, never acknowledged. */
class Unpair : LinkBody() {
    override val type get() = RequestType.UNPAIR
    override fun json() = obj { }
    override fun equals(other: Any?) = other is Unpair
    override fun hashCode() = 3

    companion object {
        internal val SPEC = ObjS(emptyMap())
    }
}

/** §3.5: one state for the request [re], and nothing else: no content, no read receipt. */
enum class AckState(val wire: String) {
    RECEIVED("received"), HANDLED("handled"), DECLINED("declined"), EXPIRED("expired"), // l10n: not text (wire values)
    UNSUPPORTED("unsupported"), BUSY("busy"), WAITING("waiting"); // l10n: not text (wire values)

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

data class Ack(val re: String, val state: AckState) : LinkBody() {
    override val type get() = RequestType.ACK
    override fun json() = obj { put("re", re); put("state", state.wire) }

    companion object {
        internal val SPEC = ObjS(mapOf("re" to IdS, "state" to OneOfS(*AckState.entries.map { it.wire }.toTypedArray())))
        internal fun read(f: Fields) = Ack(f.s("re"), AckState.of(f.s("state"))!!)
    }
}

// Free/busy --------------------------------------------------------------------------------------------------------------

/** A half-open stretch of time, [start] to [end], Unix seconds. */
data class Span(val start: Long, val end: Long) {
    override fun toString(): String = "Span" // l10n: not text (toString for logs and tests)
}

private fun windowOk(from: Long, to: Long) = from % 60 == 0L && to % 60 == 0L && to > from && to - from <= RequestCaps.FB_WINDOW_SECONDS

/** "Is Sam free Friday evening?": a window of at most 14 days, whole minutes. */
data class FbAsk(val from: Long, val to: Long) : LinkBody() {
    override val type get() = RequestType.FB_ASK
    override fun json() = obj { put("from", from); put("to", to) }

    companion object {
        internal val SPEC = ObjS(mapOf("from" to MINUTE, "to" to MINUTE))
        internal fun read(f: Fields) = FbAsk(f.n("from"), f.n("to")).takeIf { windowOk(it.from, it.to) }
        fun windowOk(from: Long, to: Long) = app.heyrobot.core.link.requests.windowOk(from, to)
    }
}

/**
 * The answer: busy blocks only, never a title, a place or who (§4.1). Each block starts on a 30-minute step from the
 * window's start and ends on one (or at the window's end), inside the window, sorted, apart (touching blocks are one).
 */
data class FbReply(val re: String, val from: Long, val to: Long, val busy: List<Span>) : LinkBody() {
    override val type get() = RequestType.FB_REPLY
    override fun json() = obj { put("re", re); put("from", from); put("to", to); put("busy", busy.map { listOf(it.start, it.end) }) }

    companion object {
        internal val SPEC = ObjS(mapOf("re" to IdS, "from" to MINUTE, "to" to MINUTE, "busy" to ListS(ListS(TIME, 2, 2), 0, RequestCaps.FB_BLOCKS)))
        internal fun read(f: Fields): FbReply? {
            val r = FbReply(f.s("re"), f.n("from"), f.n("to"), f.lists("busy").map { Span(it[0], it[1]) })
            return r.takeIf { blocksOk(it.from, it.to, it.busy) }
        }

        fun blocksOk(from: Long, to: Long, busy: List<Span>): Boolean {
            if (!windowOk(from, to)) return false
            var last = Long.MIN_VALUE
            for (b in busy) {
                if (b.start < from || b.end > to || b.start >= b.end || b.start <= last) return false
                if ((b.start - from) % RequestCaps.FB_STEP_SECONDS != 0L) return false
                if (b.end != to && (b.end - from) % RequestCaps.FB_STEP_SECONDS != 0L) return false
                last = b.end
            }
            return true
        }
    }
}

// Plans and events -------------------------------------------------------------------------------------------------------

private fun timesOk(times: List<Long>) = times.zipWithNext().all { (a, b) -> a < b }

private val EVENT_REQUIRED = mapOf("ev" to IdS, "rev" to REV, "title" to TextS(RequestCaps.TITLE), "times" to ListS(MINUTE, 1, RequestCaps.TIMES),
    "len" to NumS(RequestCaps.EVENT_MIN_MINUTES, RequestCaps.EVENT_MAX_MINUTES))
private val EVENT_OPTIONAL = mapOf("place" to TextS(RequestCaps.PLACE), "note" to TextS(RequestCaps.NOTE, lines = true), "grp" to IdS)

/**
 * An event as proposed or changed: [title] (60), [place] (80), 1 to 3 start [times], its length in minutes, a [note]
 * (300), and for a group plan the group's id ([grp]: group plans travel as events in 1.5, fanned out by the organizer).
 */
data class EventFields(val title: String, val times: List<Long>, val minutes: Long, val place: String? = null, val note: String? = null, val grp: String? = null) {
    override fun toString(): String = "EventFields" // l10n: not text (toString for logs and tests)
}

private fun eventJson(o: JsonObj, ev: String, rev: Long, e: EventFields) {
    o.put("ev", ev); o.put("rev", rev); o.put("title", e.title); o.put("place", e.place); o.put("times", e.times); o.put("len", e.minutes)
    o.put("note", e.note); o.put("grp", e.grp)
}

private fun eventOf(f: Fields) = EventFields(f.s("title"), f.longs("times"), f.n("len"), f.so("place"), f.so("note"), f.so("grp")).takeIf { timesOk(it.times) }

data class EvPropose(val ev: String, val rev: Long, val event: EventFields) : LinkBody() {
    override val type get() = RequestType.EV_PROPOSE
    override fun json() = obj { eventJson(this, ev, rev, event) }

    companion object {
        internal val SPEC = ObjS(EVENT_REQUIRED, EVENT_OPTIONAL)
        internal fun read(f: Fields) = eventOf(f)?.let { EvPropose(f.s("ev"), f.n("rev"), it) }
    }
}

/** Accepts version [rev] of the proposal at one of its [time]s. */
data class EvAccept(val ev: String, val rev: Long, val time: Long) : LinkBody() {
    override val type get() = RequestType.EV_ACCEPT
    override fun json() = obj { put("ev", ev); put("rev", rev); put("time", time) }

    companion object {
        internal val SPEC = ObjS(mapOf("ev" to IdS, "rev" to REV, "time" to MINUTE))
        internal fun read(f: Fields) = EvAccept(f.s("ev"), f.n("rev"), f.n("time"))
    }
}

data class EvDecline(val ev: String, val rev: Long, val note: String? = null) : LinkBody() {
    override val type get() = RequestType.EV_DECLINE
    override fun json() = obj { put("ev", ev); put("rev", rev); put("note", note) }

    companion object {
        internal val SPEC = ObjS(mapOf("ev" to IdS, "rev" to REV), mapOf("note" to TextS(RequestCaps.NOTE, lines = true)))
        internal fun read(f: Fields) = EvDecline(f.s("ev"), f.n("rev"), f.so("note"))
    }
}

/** Reschedules or edits: the whole event again at a higher [rev]. */
data class EvChange(val ev: String, val rev: Long, val event: EventFields) : LinkBody() {
    override val type get() = RequestType.EV_CHANGE
    override fun json() = obj { eventJson(this, ev, rev, event) }

    companion object {
        internal val SPEC = ObjS(EVENT_REQUIRED, EVENT_OPTIONAL)
        internal fun read(f: Fields) = eventOf(f)?.let { EvChange(f.s("ev"), f.n("rev"), it) }
    }
}

data class EvCancel(val ev: String, val rev: Long, val note: String? = null) : LinkBody() {
    override val type get() = RequestType.EV_CANCEL
    override fun json() = obj { put("ev", ev); put("rev", rev); put("note", note) }

    companion object {
        internal val SPEC = ObjS(mapOf("ev" to IdS, "rev" to REV), mapOf("note" to TextS(RequestCaps.NOTE, lines = true)))
        internal fun read(f: Fields) = EvCancel(f.s("ev"), f.n("rev"), f.so("note"))
    }
}

// Splits (a ledger only, never a payment) --------------------------------------------------------------------------------

/** One line of a receipt: its [text], its [amount], and the receiver's [part] of it (minor units). */
data class SplitItem(val text: String, val amount: Long, val part: Long) {
    override fun toString(): String = "SplitItem" // l10n: not text (toString for logs and tests)
}

/**
 * A split sent to one person: the receipt's [total] in [currency], the receiver's [share], which is exactly the sum of
 * their [items]' parts, their [tax] and their [tip] (shares and the tax and tip split are code's, D19), how many [people]
 * share it, the receipt as a file offer ([file]) and a [note] (100).
 */
data class SpRequest(
    val sp: String, val rev: Long, val currency: String, val total: Long, val share: Long, val items: List<SplitItem>,
    val tax: Long, val tip: Long, val people: Long, val file: String? = null, val note: String? = null,
) : LinkBody() {
    override val type get() = RequestType.SP_REQUEST
    override fun json() = obj {
        put("sp", sp); put("rev", rev); put("cur", currency); put("total", total); put("share", share)
        put("items", items.map { i -> obj { put("text", i.text); put("amt", i.amount); put("part", i.part) } })
        put("tax", tax); put("tip", tip); put("people", people); put("file", file); put("note", note)
    }

    /** The sums hold: the share is its parts, tax and tip; no part is more than its line; nothing is more than the total. */
    val consistent: Boolean get() = share == items.sumOf { it.part } + tax + tip && items.all { it.part <= it.amount } &&
        items.sumOf { it.amount } <= total && share <= total

    companion object {
        private val ITEM = ObjS(mapOf("text" to TextS(RequestCaps.SPLIT_ITEM_TEXT), "amt" to MONEY, "part" to MONEY))
        internal val SPEC = ObjS(
            mapOf("sp" to IdS, "rev" to REV, "cur" to CURRENCY, "total" to MONEY, "share" to MONEY, "items" to ListS(ITEM, 0, RequestCaps.SPLIT_ITEMS),
                "tax" to MONEY, "tip" to MONEY, "people" to NumS(2, RequestCaps.SPLIT_PEOPLE.toLong())),
            mapOf("file" to IdS, "note" to TextS(RequestCaps.SPLIT_NOTE)),
        )

        internal fun read(f: Fields) = SpRequest(
            f.s("sp"), f.n("rev"), f.s("cur"), f.n("total"), f.n("share"), f.objs("items").map { SplitItem(it.s("text"), it.n("amt"), it.n("part")) },
            f.n("tax"), f.n("tip"), f.n("people"), f.so("file"), f.so("note"),
        ).takeIf { it.consistent }
    }
}

/** The receiver agrees with its share, or doesn't ([agree]), at version [rev]. */
data class SpReply(val sp: String, val rev: Long, val agree: Boolean, val note: String? = null) : LinkBody() {
    override val type get() = RequestType.SP_REPLY
    override fun json() = obj { put("sp", sp); put("rev", rev); put("agree", agree); put("note", note) }

    companion object {
        internal val SPEC = ObjS(mapOf("sp" to IdS, "rev" to REV, "agree" to BoolS), mapOf("note" to TextS(RequestCaps.SPLIT_NOTE)))
        internal fun read(f: Fields) = SpReply(f.s("sp"), f.n("rev"), f.b("agree"), f.so("note"))
    }
}

/** How a share was settled: recorded by you, never a payment Robot made (§4.7: "settling up records cash, …"). */
enum class SettleHow(val wire: String) {
    CASH("cash"), TRANSFER("transfer"), OTHER("other"), FORGIVEN("forgiven"); // l10n: not text (wire values)

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

data class SpSettle(val sp: String, val rev: Long, val how: SettleHow, val amount: Long, val currency: String) : LinkBody() {
    override val type get() = RequestType.SP_SETTLE
    override fun json() = obj { put("sp", sp); put("rev", rev); put("how", how.wire); put("amt", amount); put("cur", currency) }

    companion object {
        internal val SPEC = ObjS(mapOf("sp" to IdS, "rev" to REV, "how" to OneOfS(*SettleHow.entries.map { it.wire }.toTypedArray()), "amt" to MONEY, "cur" to CURRENCY))
        internal fun read(f: Fields) = SpSettle(f.s("sp"), f.n("rev"), SettleHow.of(f.s("how"))!!, f.n("amt"), f.s("cur"))
    }
}

// Files ------------------------------------------------------------------------------------------------------------------

/** Files' kinds as they travel (core files FileKind, without UNKNOWN: a sender that doesn't know says "other"). */
enum class OfferKind(val wire: String) {
    RECEIPT("receipt"), BILL("bill"), DOCUMENT("document"), ID("id"), TAX_SLIP("tax_slip"), TICKET("ticket"), MEDICAL("medical"), // l10n: not text (wire values)
    PHOTO("photo"), VIDEO("video"), NOTE("note"), TRANSCRIPT("transcript"), OTHER("other"), DANGEROUS("dangerous"); // l10n: not text (wire values)

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/** Why a file was sent (§3.6, Files' `why`). */
enum class OfferWhy(val wire: String) {
    OFFER("offer"), SPLIT("split"), QUESTION("question"), FOLDER("folder"), REQUEST("request"); // l10n: not text (wire values)

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/** The sender's fields, when its Files has them; they arrive as FieldBy.PEER and Robot's own reading re-reads them (§3.6 step 5). */
data class OfferFields(val merchant: String? = null, val title: String? = null, val date: String? = null, val total: Long? = null, val currency: String? = null, val due: String? = null) {
    init {
        require(merchant != null || title != null || date != null || total != null || due != null)
        require(total == null || currency != null)
    }

    internal fun json() = obj { put("merchant", merchant); put("title", title); put("date", date); put("total", total); put("cur", currency); put("due", due) }
    override fun toString(): String = "OfferFields" // l10n: not text (toString for logs and tests)
}

/**
 * A file offered (§3.6 step 1): its [name] (80, no path), Files [kind], [size] (at most 200 MiB), the SHA-256 of its plain
 * bytes, [why], the request it answers ([re]: a `file.ask` or `q.ask`), and the sender's [fields].
 */
data class FileOffer(
    val file: String, val name: String, val kind: OfferKind, val size: Long, val sha256: String, val why: OfferWhy,
    val re: String? = null, val fields: OfferFields? = null,
) : LinkBody() {
    override val type get() = RequestType.FILE_OFFER
    override fun json() = obj {
        put("file", file); put("name", name); put("kind", kind.wire); put("size", size); put("sha256", sha256); put("why", why.wire)
        put("re", re); put("fields", fields?.json())
    }

    companion object {
        private val FIELDS = ObjS(emptyMap(), mapOf("merchant" to TextS(RequestCaps.NAME), "title" to TextS(RequestCaps.NAME), "date" to DATE,
            "total" to MONEY, "cur" to CURRENCY, "due" to DATE))
        internal val SPEC = ObjS(
            mapOf("file" to IdS, "name" to TextS(RequestCaps.FILE_NAME), "kind" to OneOfS(*OfferKind.entries.map { it.wire }.toTypedArray()),
                "size" to NumS(1, RequestCaps.FILE_MAX_BYTES), "sha256" to SHA256, "why" to OneOfS(*OfferWhy.entries.map { it.wire }.toTypedArray())),
            mapOf("re" to IdS, "fields" to FIELDS),
        )

        /** A file's name: no path separator, never "." or "..". */
        fun nameOk(n: String) = LinkText.ok(n, RequestCaps.FILE_NAME) && '/' !in n && '\\' !in n && n != "." && n != ".."

        internal fun read(f: Fields): FileOffer? {
            if (!nameOk(f.s("name"))) return null
            val fields = f.objOrNull("fields")?.let { x ->
                if (x.no("total") != null && x.so("cur") == null) return null
                if (x.so("merchant") == null && x.so("title") == null && x.so("date") == null && x.no("total") == null && x.so("due") == null) return null
                OfferFields(x.so("merchant"), x.so("title"), x.so("date"), x.no("total"), x.so("cur"), x.so("due"))
            }
            return FileOffer(f.s("file"), f.s("name"), OfferKind.of(f.s("kind"))!!, f.n("size"), f.s("sha256"), OfferWhy.of(f.s("why"))!!, f.so("re"), fields)
        }
    }
}

/**
 * §3.6 step 2 and the resume: without [from], "I accept: send the key"; with it (once the key came), "send the chunks from
 * chunk [from]", the first one this Robot doesn't have.
 */
data class FileAccept(val file: String, val from: Long? = null) : LinkBody() {
    override val type get() = RequestType.FILE_ACCEPT
    override fun json() = obj { put("file", file); put("from", from) }

    companion object {
        internal val SPEC = ObjS(mapOf("file" to IdS), mapOf("from" to NumS(0, RequestCaps.FILE_MAX_BYTES / app.heyrobot.core.seal.SealStream.CHUNK)))
        internal fun read(f: Fields) = FileAccept(f.s("file"), f.no("from"))
    }
}

/** §3.6 step 3: a fresh 256-bit key for this transfer, sent only after the offer was accepted. */
class FileKey(val file: String, key: ByteArray) : LinkBody() {
    private val k = key.copyOf()
    val key: ByteArray get() = k.copyOf()
    override val type get() = RequestType.FILE_KEY

    init {
        require(k.size == 32 && !LinkBytes.allZero(k))
    }

    override fun json() = obj { put("file", file); put("key", LinkBytes.hex(k)) }
    override fun equals(other: Any?) = other is FileKey && other.file == file && other.k.contentEquals(k)
    override fun hashCode() = file.hashCode()

    companion object {
        internal val SPEC = ObjS(mapOf("file" to IdS, "key" to HexS(32)))
        internal fun read(f: Fields) = FileKey(f.s("file"), LinkBytes.unhex(f.s("key"), 32)!!)
    }
}

data class FileDecline(val file: String) : LinkBody() {
    override val type get() = RequestType.FILE_DECLINE
    override fun json() = obj { put("file", file) }

    companion object {
        internal val SPEC = ObjS(mapOf("file" to IdS))
        internal fun read(f: Fields) = FileDecline(f.s("file"))
    }
}

/** A transfer finished: [ok] with the SHA-256 the receiver computed (it equals the offer's), or not ok (dropped) with none. */
data class FileDone(val file: String, val ok: Boolean, val sha256: String? = null) : LinkBody() {
    override val type get() = RequestType.FILE_DONE
    override fun json() = obj { put("file", file); put("ok", ok); put("sha256", sha256) }

    companion object {
        internal val SPEC = ObjS(mapOf("file" to IdS, "ok" to BoolS), mapOf("sha256" to SHA256))
        internal fun read(f: Fields) = FileDone(f.s("file"), f.b("ok"), f.so("sha256")).takeIf { it.ok == (it.sha256 != null) }
    }
}

/** "Send me the lasagna recipe": what is asked (120); answered by an offer whose `re` is this request's id. */
data class FileAsk(val what: String) : LinkBody() {
    override val type get() = RequestType.FILE_ASK
    override fun json() = obj { put("what", what) }

    companion object {
        internal val SPEC = ObjS(mapOf("what" to TextS(RequestCaps.FILE_ASK)))
        internal fun read(f: Fields) = FileAsk(f.s("what"))
    }
}

// Group plans --------------------------------------------------------------------------------------------------------------

enum class GrpWho(val wire: String) {
    IN("in"), OUT("out"), WAITING("waiting"); // l10n: not text (wire values)

    companion object { fun of(s: String) = entries.firstOrNull { it.wire == s } }
}

data class GrpPerson(val name: String, val who: GrpWho) {
    internal fun json() = obj { put("name", name); put("is", who.wire) }
}

/**
 * L11: the organizer's word on who else is in group plan [grp], [rev] its counter. Each of them is paired with the organizer and with
 * no one else, so nobody can check it: it's shown as the organizer's word and does nothing else. Names who's in; [out] and
 * [waiting] only count those who aren't, or haven't answered.
 */
data class GrpStatus(val grp: String, val rev: Long, val who: List<GrpPerson>, val out: Int = 0, val waiting: Int = 0) : LinkBody() {
    override val type get() = RequestType.GRP_STATUS
    override fun json() = obj { put("grp", grp); put("rev", rev); put("who", who.map { it.json() }); put("out", out.takeIf { it > 0 }?.toLong()); put("wait", waiting.takeIf { it > 0 }?.toLong()) }

    companion object {
        private val PERSON = ObjS(mapOf("name" to TextS(RequestCaps.NAME), "is" to OneOfS(*GrpWho.entries.map { it.wire }.toTypedArray())))
        internal val SPEC = ObjS(mapOf("grp" to IdS, "rev" to REV, "who" to ListS(PERSON, 0, RequestCaps.GROUP_OTHERS)),
            mapOf("out" to NumS(1, RequestCaps.GROUP_OTHERS.toLong()), "wait" to NumS(1, RequestCaps.GROUP_OTHERS.toLong())))
        internal fun read(f: Fields) = GrpStatus(f.s("grp"), f.n("rev"), f.objs("who").map { GrpPerson(it.s("name"), GrpWho.of(it.s("is"))!!) }, f.no("out")?.toInt() ?: 0, f.no("wait")?.toInt() ?: 0)
    }
}

// Shared folders ---------------------------------------------------------------------------------------------------------

/** An invitation to a shared folder ([name], 40): joining is always asked first. */
data class FoldJoin(val fold: String, val name: String) : LinkBody() {
    override val type get() = RequestType.FOLD_JOIN
    override fun json() = obj { put("fold", fold); put("name", name) }

    companion object {
        internal val SPEC = ObjS(mapOf("fold" to IdS, "name" to TextS(RequestCaps.NAME)))
        internal fun read(f: Fields) = FoldJoin(f.s("fold"), f.s("name"))
    }
}

enum class FoldOpKind(val wire: String) {
    ADD("add"), REMOVE("remove"); // l10n: not text (wire values)

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/** A change to a shared folder's [item]: added (with the file's offer id, [file]) or removed (never your own file elsewhere). */
data class FoldOp(val fold: String, val item: String, val rev: Long, val op: FoldOpKind, val file: String? = null) : LinkBody() {
    override val type get() = RequestType.FOLD_OP
    override fun json() = obj { put("fold", fold); put("item", item); put("rev", rev); put("op", op.wire); put("file", file) }

    companion object {
        internal val SPEC = ObjS(mapOf("fold" to IdS, "item" to IdS, "rev" to REV, "op" to OneOfS("add", "remove")), mapOf("file" to IdS)) // l10n: not text (wire values)
        internal fun read(f: Fields) = FoldOp(f.s("fold"), f.s("item"), f.n("rev"), FoldOpKind.of(f.s("op"))!!, f.so("file"))
            .takeIf { (it.op == FoldOpKind.ADD) == (it.file != null) }
    }
}

data class FoldLeave(val fold: String) : LinkBody() {
    override val type get() = RequestType.FOLD_LEAVE
    override fun json() = obj { put("fold", fold) }

    companion object {
        internal val SPEC = ObjS(mapOf("fold" to IdS))
        internal fun read(f: Fields) = FoldLeave(f.s("fold"))
    }
}

// Contact cards, ETA, reminders, delegated questions --------------------------------------------------------------------

/**
 * A contact card: a vCard of at most 2 KiB, read only as far as its shape here (begins with BEGIN:VCARD and a line break,
 * ends with END:VCARD, no control character but tab, CR and LF). The strict subset (name, phones, emails, XMPP) is read
 * in `:linkparse` (L4).
 *
 * L4: [card] is what `:linkparse` read from [vcard] (core LinkVCard: the canonical subset only), checked again in :link, where
 * it must be exactly what [vcard] says ([LinkVCard.write] of it is [vcard], required here); null until it is read (L1's decoder
 * checks the shape only). Two offers are the same when their vCards are: the card is a function of its vCard. Only
 * [vcard] travels; a sender makes an offer with [of].
 */
class CardOffer(val vcard: String, val card: ContactCard? = null) : LinkBody() {
    override val type get() = RequestType.CARD_OFFER
    override fun json() = obj { put("vcard", vcard) }

    init {
        require(card == null || LinkVCard.write(card) == vcard)
    }

    override fun equals(other: Any?) = other is CardOffer && other.vcard == vcard
    override fun hashCode() = vcard.hashCode()

    companion object {
        /** A sender's offer of [card]: its canonical vCard (LinkVCard.write). */
        fun of(card: ContactCard) = CardOffer(LinkVCard.write(card), card)

        fun shapeOk(v: String): Boolean {
            if (v.toByteArray(Charsets.UTF_8).size > RequestCaps.VCARD_BYTES) return false
            val upper = v.uppercase(java.util.Locale.ROOT)
            if (!upper.startsWith("BEGIN:VCARD\r\n") && !upper.startsWith("BEGIN:VCARD\n")) return false // l10n: not text (vCard syntax)
            if (!upper.trimEnd('\r', '\n').endsWith("END:VCARD")) return false // l10n: not text (vCard syntax)
            var i = 0
            while (i < v.length) {
                val cp = v.codePointAt(i)
                if (Character.isSurrogate(v[i]) && Character.charCount(cp) == 1) return false
                if (cp != 0x09 && cp != 0x0D && LinkText.forbidden(cp, multiline = true)) return false
                i += Character.charCount(cp)
            }
            return true
        }

        internal val SPEC = ObjS(mapOf("vcard" to object : Spec() { override fun ok(v: Any?) = v is String && shapeOk(v) }))
        internal fun read(f: Fields) = CardOffer(f.s("vcard"))
    }
}

/** "Tell Sam I'm 10 minutes away": minutes (1 to 600) and an optional place word (40). Never a location. */
data class EtaTell(val minutes: Long, val place: String? = null) : LinkBody() {
    override val type get() = RequestType.ETA_TELL
    override fun json() = obj { put("min", minutes); put("place", place) }

    companion object {
        internal val SPEC = ObjS(mapOf("min" to NumS(1, RequestCaps.ETA_MAX_MINUTES)), mapOf("place" to TextS(RequestCaps.ETA_PLACE)))
        internal fun read(f: Fields) = EtaTell(f.n("min"), f.so("place"))
    }
}

/** "Remind Sam to bring the charger": [text] (120) and [whenAt], for the other person's brief reminders, never their calendar. */
data class RemOffer(val text: String, val whenAt: Long) : LinkBody() {
    override val type get() = RequestType.REM_OFFER
    override fun json() = obj { put("text", text); put("when", whenAt) }

    companion object {
        internal val SPEC = ObjS(mapOf("text" to TextS(RequestCaps.REMINDER), "when" to MINUTE))
        internal fun read(f: Fields) = RemOffer(f.s("text"), f.n("when"))
    }
}

/** A delegated question (200). The model never writes the answer (D11). */
data class QAsk(val question: String) : LinkBody() {
    override val type get() = RequestType.Q_ASK
    override fun json() = obj { put("q", question) }

    companion object {
        internal val SPEC = ObjS(mapOf("q" to TextS(RequestCaps.QUESTION)))
        internal fun read(f: Fields) = QAsk(f.s("q"))
    }
}

/** The answer to the question [re]: words you typed or a note you picked ([text], 2,000), or a file you picked (its offer id), one of the two. */
data class QReply(val re: String, val text: String? = null, val file: String? = null) : LinkBody() {
    override val type get() = RequestType.Q_REPLY
    override fun json() = obj { put("re", re); put("text", text); put("file", file) }

    companion object {
        internal val SPEC = ObjS(mapOf("re" to IdS), mapOf("text" to TextS(RequestCaps.ANSWER, lines = true), "file" to IdS))
        internal fun read(f: Fields) = QReply(f.s("re"), f.so("text"), f.so("file")).takeIf { (it.text == null) != (it.file == null) }
    }
}

// Shared lists (lists-plan.md §9) ----------------------------------------------------------------------------------------

/** A list item's whole state at version [rev]: Lists' text (120), quantity (16), ticked, position. */
data class ListItem(val item: String, val rev: Long, val text: String, val done: Boolean, val pos: Long, val qty: String? = null) {
    internal fun json() = obj { put("item", item); put("rev", rev); put("text", text); put("qty", qty); put("done", done); put("pos", pos) }
    override fun toString(): String = "ListItem" // l10n: not text (toString for logs and tests)
}

private val LIST_ITEM_REQUIRED = mapOf("item" to IdS, "rev" to REV, "text" to TextS(RequestCaps.LIST_ITEM), "done" to BoolS, "pos" to NumS(-RequestCaps.LIST_POS, RequestCaps.LIST_POS))
private val LIST_ITEM = ObjS(LIST_ITEM_REQUIRED, mapOf("qty" to TextS(RequestCaps.LIST_QTY)))

private fun listItemOf(f: Fields) = ListItem(f.s("item"), f.n("rev"), f.s("text"), f.b("done"), f.n("pos"), f.so("qty"))

/**
 * Sharing a list: its [name] (40) and items, in [part] of [parts] (each part at most 40 items, so a list of 500 fits in
 * 13 requests under 32 KiB). Joining is asked first; after that, changes sync on their own (`ls.op`).
 */
data class LsOffer(val list: String, val name: String, val part: Long, val parts: Long, val items: List<ListItem>) : LinkBody() {
    override val type get() = RequestType.LS_OFFER
    override fun json() = obj { put("list", list); put("name", name); put("part", part); put("parts", parts); put("items", items.map { it.json() }) }

    companion object {
        internal val SPEC = ObjS(mapOf("list" to IdS, "name" to TextS(RequestCaps.NAME), "part" to NumS(1, RequestCaps.LIST_PARTS.toLong()),
            "parts" to NumS(1, RequestCaps.LIST_PARTS.toLong()), "items" to ListS(LIST_ITEM, 0, RequestCaps.LIST_PART_ITEMS)))
        internal fun read(f: Fields) = LsOffer(f.s("list"), f.s("name"), f.n("part"), f.n("parts"), f.objs("items").map(::listItemOf))
            .takeIf { it.part <= it.parts && it.items.map { i -> i.item }.toSet().size == it.items.size }
    }
}

/** One change to a shared list's item: its whole new state ([state]), or its removal ([gone], a tombstone) at version [rev]. */
data class ListOp(val item: String, val rev: Long, val state: ListItem? = null) {
    val gone: Boolean get() = state == null

    init {
        require(state == null || (state.item == item && state.rev == rev))
    }

    internal fun json() = state?.json() ?: obj { put("item", item); put("rev", rev); put("gone", true) }
    override fun toString(): String = "ListOp" // l10n: not text (toString for logs and tests)
}

/** Up to 40 changes to the shared list [list]; Lists' "60 changes an hour" counts each one (LinkLimits). */
data class LsOp(val list: String, val ops: List<ListOp>) : LinkBody() {
    override val type get() = RequestType.LS_OP
    override fun json() = obj { put("list", list); put("ops", ops.map { it.json() }) }

    companion object {
        private val OP = ObjS(mapOf("item" to IdS, "rev" to REV), LIST_ITEM_REQUIRED - "item" - "rev" + mapOf("qty" to TextS(RequestCaps.LIST_QTY), "gone" to BoolS))
        internal val SPEC = ObjS(mapOf("list" to IdS, "ops" to ListS(OP, 1, RequestCaps.LIST_OPS)))

        internal fun read(f: Fields): LsOp? {
            val ops = f.objs("ops").map { o ->
                when (o.bo("gone")) {
                    true -> if (o.so("text") != null || o.bo("done") != null || o.no("pos") != null || o.so("qty") != null) return null else ListOp(o.s("item"), o.n("rev"))
                    false -> return null
                    null -> if (o.so("text") == null || o.bo("done") == null || o.no("pos") == null) return null else ListOp(o.s("item"), o.n("rev"), listItemOf(o))
                }
            }
            if (ops.map { it.item }.toSet().size != ops.size) return null
            return LsOp(f.s("list"), ops)
        }
    }
}

data class LsLeave(val list: String) : LinkBody() {
    override val type get() = RequestType.LS_LEAVE
    override fun json() = obj { put("list", list) }

    companion object {
        internal val SPEC = ObjS(mapOf("list" to IdS))
        internal fun read(f: Fields) = LsLeave(f.s("list"))
    }
}

/** Each type's keys and its reader. Every type in the registry has one (LinkRequestsTest checks). */
internal object Bodies {
    class Codec(val spec: ObjS, val read: (Fields) -> LinkBody?)

    private fun c(spec: ObjS, read: (Fields) -> LinkBody?) = Codec(spec, read)

    val codecs: Map<RequestType, Codec> = mapOf(
        RequestType.CAPS to c(Caps.SPEC, Caps::read),
        RequestType.NET_HINT to c(NetHint.SPEC, NetHint::read),
        RequestType.MODE_RAISE to c(ModeRaise.SPEC) { ModeRaise() },
        RequestType.MODE_LOWER to c(ModeLower.SPEC) { ModeLower() },
        RequestType.UNPAIR to c(Unpair.SPEC) { Unpair() },
        RequestType.ACK to c(Ack.SPEC, Ack::read),
        RequestType.FB_ASK to c(FbAsk.SPEC, FbAsk::read),
        RequestType.FB_REPLY to c(FbReply.SPEC, FbReply::read),
        RequestType.EV_PROPOSE to c(EvPropose.SPEC, EvPropose::read),
        RequestType.EV_ACCEPT to c(EvAccept.SPEC, EvAccept::read),
        RequestType.EV_DECLINE to c(EvDecline.SPEC, EvDecline::read),
        RequestType.EV_CHANGE to c(EvChange.SPEC, EvChange::read),
        RequestType.EV_CANCEL to c(EvCancel.SPEC, EvCancel::read),
        RequestType.SP_REQUEST to c(SpRequest.SPEC, SpRequest::read),
        RequestType.SP_REPLY to c(SpReply.SPEC, SpReply::read),
        RequestType.SP_SETTLE to c(SpSettle.SPEC, SpSettle::read),
        RequestType.FILE_OFFER to c(FileOffer.SPEC, FileOffer::read),
        RequestType.FILE_ACCEPT to c(FileAccept.SPEC, FileAccept::read),
        RequestType.FILE_KEY to c(FileKey.SPEC, FileKey::read),
        RequestType.FILE_DECLINE to c(FileDecline.SPEC, FileDecline::read),
        RequestType.FILE_DONE to c(FileDone.SPEC, FileDone::read),
        RequestType.FILE_ASK to c(FileAsk.SPEC, FileAsk::read),
        RequestType.GRP_STATUS to c(GrpStatus.SPEC, GrpStatus::read),
        RequestType.FOLD_JOIN to c(FoldJoin.SPEC, FoldJoin::read),
        RequestType.FOLD_OP to c(FoldOp.SPEC, FoldOp::read),
        RequestType.FOLD_LEAVE to c(FoldLeave.SPEC, FoldLeave::read),
        RequestType.CARD_OFFER to c(CardOffer.SPEC, CardOffer::read),
        RequestType.ETA_TELL to c(EtaTell.SPEC, EtaTell::read),
        RequestType.REM_OFFER to c(RemOffer.SPEC, RemOffer::read),
        RequestType.Q_ASK to c(QAsk.SPEC, QAsk::read),
        RequestType.Q_REPLY to c(QReply.SPEC, QReply::read),
        RequestType.LS_OFFER to c(LsOffer.SPEC, LsOffer::read),
        RequestType.LS_OP to c(LsOp.SPEC, LsOp::read),
        RequestType.LS_LEAVE to c(LsLeave.SPEC, LsLeave::read),
    )

    /** [type]'s body from [m], or null when its keys, types, caps or sums are wrong. */
    @Suppress("UNCHECKED_CAST")
    fun read(type: RequestType, m: Map<*, *>): LinkBody? {
        val c = codecs[type] ?: return null
        if (!c.spec.ok(m)) return null
        return try {
            c.read(Fields(m as Map<String, Any?>))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
