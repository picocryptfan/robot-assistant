// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.3, §4.5, §5.1, §5.4; job L4): the Binder interface between :link
// (app/link LinkParseLink) and :linkparse (app/linkparse LinkParseService), the isolated reader, the one place a request
// another Robot sent is decoded (core LinkRequests.decode, and a contact card's vCard, LinkVCard.read). Written by hand, no
// AIDL, as FileParseIpc is :files': a code per call, two frames encoded and read only here, every length explicit and
// capped, read strictly. What crosses never names a pairing, a person, a key or an address.
//
// DECODE carries the request frame and the plaintext, in a read-only SharedMemory region :link fills; the answer is one
// frame of the request's fields as values (never JSON), which :link reads strictly and checks again (LinkParseWire.check):
// the body through L1's own specs (LinkRequests.rebuild), a card through ContactCard's rules and LinkVCard.write, and then
// the request it rebuilt is written (LinkRequests.wire) and kept only when that is the plaintext it sent, byte for byte. So
// :linkparse can refuse a request, but whatever it answers, it can never put words into one: a request :link keeps is the
// one another Robot sent, with the fields the decoder would have given. :link never parses the request's text itself.
//
//   request  "RLQ" 1 | cap u32 (2,048, 3,584 or 32,768: LinkRequests' caps) | size u32 (the region's, 512 to cap, a multiple of 512)
//   reply    "RLP" 1 | kind u8, then
//              1 read:        type u16 | id 16 bytes | at u64 | ttl u64 | lang: u8 length, ASCII | body: a value (an object)
//                             | card u8 (1 for card.offer, then the card as a value: an object; else 0)
//              2 unsupported: id 16 bytes | newer u8 (0 or 1)
//              3 refused:     problem u8 (a LinkProblem code: what the decoder found)
//   value    tag u8, then
//              1 text:    u16 length, UTF-8 (strict)        2 number: u64 (two's complement)     3 false     4 true
//              5 list:    u16 count, values                 6 object: u8 count, then each key (u8 length, a-z 0-9) and its value
//
// Big-endian, nothing after the last field. Values: at most 5 deep, 512 in a list, 16 keys in an object (each once), text
// of at most 16 KiB; a reply at most [LinkParseIpc.MAX_REPLY]. Anything else is refused whole.

/** The Binder side: the service's name (bound by name, never by class), its descriptors, calls, replies and times. */
object LinkParseIpc {
    const val SERVICE_CLASS = "app.heyrobot.linkparse.LinkParseService" // l10n: not text (a class name)
    const val DESCRIPTOR = "app.heyrobot.linkparse.ILinkParse" // l10n: not text (a Binder descriptor)
    const val CALLBACK = "app.heyrobot.linkparse.ILinkParseCallback" // l10n: not text (a Binder descriptor)

    // :link -> :linkparse (IBinder.FIRST_CALL_TRANSACTION is 1).
    /** In: the link's callback binder; :linkparse calls its CB_HELLO from its own uid. Out: REPLY_OK, then 1 when the link accepted it. */
    const val HELLO = 1
    /** In: the request frame (bytes), 1 and the plaintext's read-only region. Out: REPLY_OK and the reply frame (bytes). */
    const val DECODE = 2
    /** One way: the process ends itself, whatever it is doing. */
    const val EXIT = 3

    // :linkparse -> the link (the callback binder of HELLO).
    /** In: its pid. Out: 1 when the link accepts it (its calling uid is an isolated one). */
    const val CB_HELLO = 1

    /**
     * Every reply starts with one of these ints, never an exception header (Parcel.writeException and readException are
     * never used): OK and its fields; REFUSED (not Robot's own uid calling, a frame broken) with nothing after; NOT_ISOLATED
     * from a service that isn't in an isolated process, which then touched nothing it was given.
     */
    const val REPLY_OK = 0
    const val REPLY_REFUSED = 1
    const val REPLY_NOT_ISOLATED = 2

    /** The request frame's size. */
    const val REQUEST_FRAME = 12
    /** The largest reply frame: a live request's fields as values (LinkParseWireTest's worst cases), with room. */
    const val MAX_REPLY = 96 * 1024

    /** Binding and HELLO (an isolated process starting cold), one request (the link thread waits for it), :linkparse's own watchdog. */
    const val BIND_MS = 8_000L
    const val CALL_MS = 3_000L
    const val SERVICE_MS = 2_000L
    /** After :linkparse couldn't start, the next try waits this long (or for :link's next wake). */
    const val RETRY_MS = 30_000L
}

/** What DECODE asks: the plaintext's [size] (the region's) and the [cap] it may be read under. */
class ParseAsk(val cap: Int, val size: Int)

object LinkParseWire {
    private val REQUEST_MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'Q'.code.toByte(), 1)
    private val REPLY_MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 1)
    private const val READ = 1
    private const val UNSUPPORTED = 2
    private const val REFUSED = 3

    val CAPS = listOf(LinkRequests.PUSH_FIRST, LinkRequests.PUSH, LinkRequests.LIVE)

    /** A plaintext of [size] bytes may be asked about under [cap]: a cap of LinkRequests', padded whole, within it. */
    fun sizeOk(size: Int, cap: Int): Boolean = cap in CAPS && size in LinkRequests.PAD..cap && size % LinkRequests.PAD == 0

    fun encodeRequest(cap: Int, size: Int): ByteArray {
        require(sizeOk(size, cap))
        return BinWriter(LinkParseIpc.REQUEST_FRAME).bytes(REQUEST_MAGIC).u32(cap.toLong()).u32(size.toLong()).toByteArray()
    }

    /** The request in [frame] when it is exactly one and its region is the size it says ([regionSize]); else null. */
    fun decodeRequest(frame: ByteArray, regionSize: Int): ParseAsk? = try {
        if (frame.size != LinkParseIpc.REQUEST_FRAME) null
        else {
            val r = BinReader(frame)
            if (!REQUEST_MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) null
            else {
                val cap = r.u32()
                val size = r.u32()
                r.end()
                if (cap > Int.MAX_VALUE || size > Int.MAX_VALUE || !sizeOk(size.toInt(), cap.toInt()) || size.toInt() != regionSize) null
                else ParseAsk(cap.toInt(), size.toInt())
            }
        }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * :linkparse's answer, [read] as the decoder gave it. A read contact card must carry its card (LinkVCard.read's): one
     * without is refused here as BAD_FIELD, never answered as read.
     */
    fun encodeReply(read: RequestRead): ByteArray {
        val w = BinWriter(4096).bytes(REPLY_MAGIC)
        when (read) {
            is RequestRead.Ok -> {
                val r = read.request
                val body = r.body
                val card = (body as? CardOffer)?.card
                if (body is CardOffer && card == null) return encodeReply(RequestRead.Bad(LinkProblem.BAD_FIELD))
                w.u8(READ).u16(r.type.code).bytes(LinkBytes.unhex(r.id, LinkIds.BYTES)!!).u64(r.at).u64(r.ttl)
                val lang = r.lang.toByteArray(Charsets.US_ASCII)
                w.u8(lang.size).bytes(lang)
                LinkTree.write(bodyTree(body), w)
                if (card != null) {
                    w.u8(1)
                    LinkTree.write(cardTree(card), w)
                } else {
                    w.u8(0)
                }
            }
            is RequestRead.Unsupported -> w.u8(UNSUPPORTED).bytes(LinkBytes.unhex(read.id, LinkIds.BYTES)!!).u8(if (read.newer) 1 else 0)
            is RequestRead.Bad -> w.u8(REFUSED).u8(read.problem.code)
        }
        return w.toByteArray().also { require(it.size <= LinkParseIpc.MAX_REPLY) }
    }

    /** The values a body travels as: what its JSON says, as a tree (the body's own writer's map, never text). */
    private fun bodyTree(b: LinkBody): Map<String, Any?> = b.json()

    private fun cardTree(c: ContactCard): Map<String, Any?> = linkedMapOf("fn" to c.name, "tel" to c.phones, "email" to c.emails, "xmpp" to c.xmpp) // l10n: not text (wire keys)

    private fun cardOf(v: Any?): ContactCard? {
        val m = v as? Map<*, *> ?: return null
        if (m.keys != setOf("fn", "tel", "email", "xmpp")) return null // l10n: not text (wire keys)
        val name = m["fn"] as? String ?: return null
        fun strings(k: String): List<String>? = (m[k] as? List<*>)?.map { it as? String ?: return null }
        val tel = strings("tel") ?: return null // l10n: not text (a wire key)
        val email = strings("email") ?: return null // l10n: not text (a wire key)
        val xmpp = strings("xmpp") ?: return null // l10n: not text (a wire key)
        return try { ContactCard(name, tel, email, xmpp) } catch (_: IllegalArgumentException) { null }
    }

    /**
     * :link's side: what [frame] (:linkparse's answer about [plain], asked under [cap]) says, when it keeps every rule
     * above and, for a read request, the request rebuilt from its fields is [plain] exactly ([LinkRequests.wire]); null for
     * anything else (an answer that broke the rules, or a different request: the link then ends that :linkparse).
     *
     * An unsupported answer's id must be the plaintext's own (`"id":"<id>"` in its bytes, a key no text can spell: a quote
     * inside a string is always escaped) and its "newer" must agree with the plaintext's first bytes. A refusal is taken as
     * said: it only drops the request, as any refusal does.
     */
    fun check(frame: ByteArray, plain: ByteArray, cap: Int): RequestRead? = try {
        if (frame.size > LinkParseIpc.MAX_REPLY || !sizeOk(plain.size, cap)) null else checkOrThrow(frame, plain)
    } catch (_: RuntimeException) {
        null
    }

    private fun checkOrThrow(frame: ByteArray, plain: ByteArray): RequestRead? {
        val r = BinReader(frame)
        if (!REPLY_MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) return null
        return when (r.u8()) {
            READ -> {
                val type = RequestType.ofCode(r.u16()) ?: return null
                val id = LinkBytes.hex(r.bytes(LinkIds.BYTES))
                val at = r.u64()
                val ttl = r.u64()
                val n = r.u8()
                if (n !in 2..3) return null
                val lang = r.bytes(n).let { b -> if (b.all { it in 'a'.code.toByte()..'z'.code.toByte() }) String(b, Charsets.US_ASCII) else return null }
                val body = LinkTree.read(r) as? Map<*, *> ?: return null
                val hasCard = when (r.u8()) { 0 -> false; 1 -> true; else -> return null }
                if (hasCard != (type == RequestType.CARD_OFFER)) return null
                val cardValue = if (hasCard) LinkTree.read(r) else null
                r.end()
                var req = LinkRequests.rebuild(id, at, ttl, lang, type, body) ?: return null
                if (hasCard) {
                    val card = cardOf(cardValue) ?: return null
                    // CardOffer's own rule: LinkVCard.write of the card is the vCard, or it throws (and this is null).
                    req = LinkRequest(req.id, req.at, req.ttl, req.lang, CardOffer((req.body as CardOffer).vcard, card))
                }
                // Write and compare: the request kept is the one that was sent.
                if (!LinkRequests.wire(req).contentEquals(plain)) return null
                RequestRead.Ok(req)
            }
            UNSUPPORTED -> {
                val idBytes = r.bytes(LinkIds.BYTES)
                val newer = when (r.u8()) { 0 -> false; 1 -> true; else -> return null }
                r.end()
                if (LinkBytes.allZero(idBytes)) return null
                val id = LinkBytes.hex(idBytes)
                if (!unsupportedOk(plain, id, newer)) return null
                RequestRead.Unsupported(id, newer)
            }
            REFUSED -> {
                val problem = LinkProblem.of(r.u8()) ?: return null
                r.end()
                // Only what the decoder finds, never a code :link gives its own failures.
                if (problem !in DECODER_PROBLEMS) return null
                RequestRead.Bad(problem)
            }
            else -> null
        }
    }

    /** What LinkRequests.decode refuses a request for. */
    val DECODER_PROBLEMS = setOf(LinkProblem.TOO_LONG, LinkProblem.BAD_PADDING, LinkProblem.NOT_JSON, LinkProblem.BAD_TEXT, LinkProblem.BAD_VERSION,
        LinkProblem.BAD_KEYS, LinkProblem.BAD_FIELD, LinkProblem.BAD_TYPE, LinkProblem.NOT_CANONICAL)

    /**
     * "Unsupported" may be answered about [plain] for [id]: the id is the plaintext's own (`"id":"<id>"` in its bytes, a key
     * no text inside a string can spell, a quote there being always escaped), and [newer] agrees with its first bytes (a
     * version 1 request starts `{"v":1,`). :linkparse answers unsupported only when this holds (a request spelled any other
     * way is refused as not canonical), and :link checks it again.
     */
    fun unsupportedOk(plain: ByteArray, id: String, newer: Boolean): Boolean {
        if (!LinkIds.isId(id)) return false
        if (!contains(plain, "\"id\":\"$id\"".toByteArray(Charsets.US_ASCII))) return false // l10n: not text (JSON)
        return newer != startsWith(plain, V1)
    }

    /** A version 1 request's first bytes. */
    private val V1 = "{\"v\":1,".toByteArray(Charsets.US_ASCII) // l10n: not text (JSON)

    private fun startsWith(b: ByteArray, p: ByteArray) = b.size >= p.size && (p.indices).all { b[it] == p[it] }

    private fun contains(b: ByteArray, p: ByteArray): Boolean {
        outer@ for (i in 0..b.size - p.size) {
            for (j in p.indices) if (b[i + j] != p[j]) continue@outer
            return true
        }
        return false
    }
}

/**
 * The value tree of LinkParseWire's reply: text, whole numbers, booleans, lists and objects, no null, strictly capped. Its
 * reader is the only reader of :linkparse's answer in :link; it never sees JSON.
 */
internal object LinkTree {
    private const val TEXT = 1
    private const val NUMBER = 2
    private const val FALSE = 3
    private const val TRUE = 4
    private const val LIST = 5
    private const val OBJECT = 6
    const val MAX_DEPTH = 5
    const val MAX_LIST = 512
    const val MAX_KEYS = 16
    const val MAX_KEY = 16
    const val MAX_TEXT = 16 * 1024

    fun write(v: Any?, w: BinWriter, depth: Int = 1) {
        require(depth <= MAX_DEPTH)
        when (v) {
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                require(b.size <= MAX_TEXT && LinkBytes.utf8(b) == v)
                w.u8(TEXT).u16(b.size).bytes(b)
            }
            is Long -> w.u8(NUMBER).u64(v)
            is Int -> w.u8(NUMBER).u64(v.toLong())
            is Boolean -> w.u8(if (v) TRUE else FALSE)
            is List<*> -> {
                require(v.size <= MAX_LIST)
                w.u8(LIST).u16(v.size)
                for (x in v) write(x, w, depth + 1)
            }
            is Map<*, *> -> {
                require(v.size <= MAX_KEYS)
                w.u8(OBJECT).u8(v.size)
                for ((k, x) in v) {
                    val key = k as String
                    require(keyOk(key))
                    w.u8(key.length).bytes(key.toByteArray(Charsets.US_ASCII))
                    write(x, w, depth + 1)
                }
            }
            else -> throw IllegalArgumentException()
        }
    }

    /** One value from [r] ([LinkBad] for anything past the rules). Objects keep their keys' order. */
    fun read(r: BinReader, depth: Int = 1): Any {
        if (depth > MAX_DEPTH) throw LinkBad
        return when (r.u8()) {
            TEXT -> {
                val n = r.u16()
                if (n > MAX_TEXT) throw LinkBad
                LinkBytes.utf8(r.bytes(n)) ?: throw LinkBad
            }
            NUMBER -> r.u64()
            FALSE -> false
            TRUE -> true
            LIST -> {
                val n = r.u16()
                if (n > MAX_LIST) throw LinkBad
                // Each value takes at least a byte: a count the frame can't hold is refused before anything is made for it.
                r.need(n)
                List(n) { read(r, depth + 1) }
            }
            OBJECT -> {
                val n = r.u8()
                if (n > MAX_KEYS) throw LinkBad
                val m = LinkedHashMap<String, Any>(n * 2)
                repeat(n) {
                    val len = r.u8()
                    if (len !in 1..MAX_KEY) throw LinkBad
                    val key = String(r.bytes(len), Charsets.US_ASCII)
                    if (!keyOk(key) || m.containsKey(key)) throw LinkBad
                    m[key] = read(r, depth + 1)
                }
                m
            }
            else -> throw LinkBad
        }
    }

    private fun keyOk(k: String) = k.length in 1..MAX_KEY && k.all { it in 'a'..'z' || it in '0'..'9' }
}
