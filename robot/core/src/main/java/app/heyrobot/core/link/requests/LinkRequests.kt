// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link.requests

import app.heyrobot.core.StrictJson
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkProblem

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.3, §3.7; job L1): the typed request, protocol v1, the plaintext a
// session message carries:
//
//   {"v":1,"id":"<32 hex>","at":1790000000,"ttl":604800,"lang":"en","type":"fb.ask","body":{...}}
//
// then spaces to a multiple of 512 bytes, so sizes say little. Canonical: those keys in that order, no white space, the
// body's keys in its type's order (LinkBodies), and nothing but the 0x20 padding after it (fewer than 512 bytes of it).
// The decoder parses with StrictJson, checks every key, then writes the request again and accepts the bytes only when
// they are exactly what it read: one spelling of each request, so nothing can hide in white space, escapes, key order or
// number forms.
//
// What comes back: [RequestRead.Ok]; [RequestRead.Unsupported] for a well-formed request of a newer protocol version or a
// type this Robot doesn't read (a newer Robot's, or one of the reserved families: `pay.`, `act.`, `grp.`, `skill.`),
// answered `ack` "unsupported" with its id; [RequestRead.Bad] for anything else, dropped and counted by its code, never
// answered. Time checks (`at` in the future, past its `ttl`, a repeat) are the replay window's (LinkReplay), after this.
//
// Size caps on the padded plaintext (§3.7, R0 §9 item 13): 2,048 bytes in a first message by push (it must fit one push
// body with the session's post-quantum overhead), 3,584 in a later one by push, 32 KiB on a live connection.
//
// L4: [LinkRequests.decode] runs only in :linkparse, the isolated reader (app/linkparse, ProcessIsolationTest's
// linkParsesOnlyInIsolation). :link gets each field back (core LinkParseWire), rebuilds the request with [LinkRequests.rebuild]
// and keeps it only when [LinkRequests.wire] of it is the plaintext exactly.

class LinkRequest(
    /** 32 hex characters, random. */
    val id: String,
    /** The sender's clock, Unix seconds. */
    val at: Long,
    /** How long it may wait, in seconds: 7 days by default, at most 30 (D9). */
    val ttl: Long,
    /** The language of its free text: two or three lower-case letters ("en", "fr"); a language this Robot doesn't speak is still read. */
    val lang: String,
    val body: LinkBody,
) {
    val type: RequestType get() = body.type

    init {
        require(LinkIds.isId(id) && at in RequestCaps.MIN_TIME..RequestCaps.MAX_TIME && ttl in LinkRequests.MIN_TTL..LinkRequests.MAX_TTL && LinkRequests.langOk(lang))
    }

    /** When it stops being worth delivering or answering. */
    val expiresAt: Long get() = at + ttl

    override fun equals(other: Any?) = other is LinkRequest && other.id == id && other.at == at && other.ttl == ttl && other.lang == lang && other.body == body
    override fun hashCode() = id.hashCode()
    override fun toString(): String = "LinkRequest(type=${type.wire})" // l10n: not text (toString for logs and tests)

    companion object {
        /** A new request with a fresh id. */
        fun of(body: LinkBody, at: Long, lang: String, ttl: Long = LinkRequests.DEFAULT_TTL) = LinkRequest(LinkIds.newId(), at, ttl, lang, body)
    }
}

sealed class RequestRead {
    class Ok(val request: LinkRequest) : RequestRead() {
        override fun toString(): String = "RequestRead.Ok($request)" // l10n: not text (toString for logs and tests)
    }

    /** Answered `ack` "unsupported" with [id]: [newer], a newer protocol version; else a type this Robot doesn't read. */
    class Unsupported(val id: String, val newer: Boolean) : RequestRead() {
        override fun toString(): String = "RequestRead.Unsupported(newer=$newer)" // l10n: not text (toString for logs and tests)
    }

    data class Bad(val problem: LinkProblem) : RequestRead()
}

object LinkRequests {
    const val VERSION = 1L
    const val PAD = 512
    /** §3.7 and R0 item 13: a first message by push, a later one by push, a live connection. */
    const val PUSH_FIRST = 2_048
    const val PUSH = 3_584
    const val LIVE = 32_768
    const val DEFAULT_TTL = 7 * 24 * 3600L
    const val MAX_TTL = 30 * 24 * 3600L
    const val MIN_TTL = 60L

    private val TOP = listOf("v", "id", "at", "ttl", "lang", "type", "body") // l10n: not text (wire keys)
    private val LANG = Regex("^[a-z]{2,3}$")

    fun langOk(lang: String) = LANG.matches(lang)

    /** The canonical text of [r], without padding. */
    fun json(r: LinkRequest): String = LinkJson.write(linkedMapOf<String, Any?>(
        "v" to VERSION, "id" to r.id, "at" to r.at, "ttl" to r.ttl, "lang" to r.lang, "type" to r.type.wire, "body" to r.body.json(),
    ))

    /**
     * [r] as a session message's plaintext, padded with spaces to a multiple of [PAD]; null when that is more than [cap]
     * bytes (the sender waits for a live connection, or splits it: `ls.offer`'s parts). Content that this Robot itself
     * would refuse to read is a programming error (IllegalArgumentException), never sent.
     */
    fun encode(r: LinkRequest, cap: Int = LIVE): ByteArray? {
        require(cap > 0 && cap % PAD == 0)
        val out = wire(r)
        val back = decodeOrThrow(out, maxOf(cap, out.size))
        require(back is RequestRead.Ok && back.request == r)
        return out.takeIf { out.size <= cap }
    }

    /**
     * L4: [r]'s padded bytes, written and never read back: the canonical text and its spaces, exactly what [encode] sends.
     * :link compares this with the plaintext it decrypted (LinkParseWire.check), and the waiting inbox keeps it, without
     * running the decoder on anything another Robot wrote: [encode] reads its own output back, this doesn't.
     */
    fun wire(r: LinkRequest): ByteArray {
        val text = json(r).toByteArray(Charsets.UTF_8)
        val size = (text.size + PAD - 1) / PAD * PAD
        return ByteArray(size) { if (it < text.size) text[it] else ' '.code.toByte() }
    }

    /**
     * L4: a request rebuilt from the fields :linkparse read (LinkParseWire's reply), checked again here as the decoder checks
     * them, the body through the same specs (Bodies.read). No text is parsed: [body] is already a tree of strings, whole
     * numbers, booleans, lists and objects. Null when anything fails. :link keeps the result only when [wire] of it is the
     * plaintext it decrypted, byte for byte, so the isolated reader can refuse a request but never change one.
     */
    fun rebuild(id: String, at: Long, ttl: Long, lang: String, type: RequestType, body: Map<*, *>): LinkRequest? {
        if (!LinkIds.isId(id) || at !in RequestCaps.MIN_TIME..RequestCaps.MAX_TIME || ttl !in MIN_TTL..MAX_TTL || !langOk(lang)) return null
        val b = try {
            Bodies.read(type, body)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return LinkRequest(id, at, ttl, lang, b)
    }

    /**
     * L5-fix: [bytes] start with the canonical header for [id], [at], [ttl] and [type]:
     * `{"v":1,"id":"…","at":…,"ttl":…,"lang":"…","type":"…","body":` with any language [langOk] allows. A byte compare, no
     * parsing: :link checks what :voice asks it to send this way, so a request's bytes can't say another type, id or time
     * than the fields it was queued with (and never JSON is read in :link).
     */
    fun headerOk(bytes: ByteArray, id: String, at: Long, ttl: Long, type: RequestType): Boolean {
        if (!LinkIds.isId(id)) return false
        val head = "{\"v\":$VERSION,\"id\":\"$id\",\"at\":$at,\"ttl\":$ttl,\"lang\":\"".toByteArray(Charsets.US_ASCII) // l10n: not text (JSON)
        val tail = "\",\"type\":\"${type.wire}\",\"body\":".toByteArray(Charsets.US_ASCII) // l10n: not text (JSON)
        if (bytes.size < head.size + 2 + tail.size || head.indices.any { bytes[it] != head[it] }) return false
        var n = 0
        while (n < 4 && head.size + n < bytes.size && bytes[head.size + n] in 'a'.code.toByte()..'z'.code.toByte()) n++
        if (n !in 2..3) return false
        val at0 = head.size + n
        return bytes.size >= at0 + tail.size && tail.indices.all { bytes[at0 + it] == tail[it] }
    }

    /** The request in a session message's plaintext [bytes], allowed at most [cap] bytes (one of the caps above). */
    fun decode(bytes: ByteArray, cap: Int): RequestRead = try {
        decodeOrThrow(bytes, cap)
    } catch (_: RuntimeException) {
        // A reader bug must never take the parser down: the tests call decodeOrThrow, so such a bug fails them instead.
        RequestRead.Bad(LinkProblem.BAD_FIELD)
    }

    internal fun decodeOrThrow(bytes: ByteArray, cap: Int): RequestRead {
        require(cap > 0 && cap % PAD == 0)
        if (bytes.size > cap) return RequestRead.Bad(LinkProblem.TOO_LONG)
        if (bytes.isEmpty() || bytes.size % PAD != 0) return RequestRead.Bad(LinkProblem.BAD_PADDING)
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == ' '.code.toByte()) end--
        if (end == 0 || bytes.size - end >= PAD) return RequestRead.Bad(LinkProblem.BAD_PADDING)
        if (bytes[end - 1] != '}'.code.toByte() || bytes[0] != '{'.code.toByte()) return RequestRead.Bad(LinkProblem.NOT_JSON)
        val raw = bytes.copyOf(end)
        val text = LinkBytes.utf8(raw) ?: return RequestRead.Bad(LinkProblem.BAD_TEXT)
        val m = StrictJson.parse(text)?.value as? Map<*, *> ?: return RequestRead.Bad(LinkProblem.NOT_JSON)
        val v = m["v"] as? Long ?: return RequestRead.Bad(LinkProblem.BAD_VERSION)
        val id = (m["id"] as? String)?.takeIf(LinkIds::isId)
        if (v != VERSION) return if (v > VERSION && id != null) RequestRead.Unsupported(id, newer = true) else RequestRead.Bad(LinkProblem.BAD_VERSION)
        if (m.keys != TOP.toSet()) return RequestRead.Bad(LinkProblem.BAD_KEYS)
        if (id == null) return RequestRead.Bad(LinkProblem.BAD_FIELD)
        val at = (m["at"] as? Long)?.takeIf { it in RequestCaps.MIN_TIME..RequestCaps.MAX_TIME } ?: return RequestRead.Bad(LinkProblem.BAD_FIELD)
        val ttl = (m["ttl"] as? Long)?.takeIf { it in MIN_TTL..MAX_TTL } ?: return RequestRead.Bad(LinkProblem.BAD_FIELD)
        val lang = (m["lang"] as? String)?.takeIf { langOk(it) } ?: return RequestRead.Bad(LinkProblem.BAD_FIELD)
        val typeName = m["type"] as? String ?: return RequestRead.Bad(LinkProblem.BAD_TYPE)
        val bodyMap = m["body"] as? Map<*, *> ?: return RequestRead.Bad(LinkProblem.BAD_FIELD)
        val type = RequestType.of(typeName)
        if (type == null) return if (RequestType.wellFormed(typeName)) RequestRead.Unsupported(id, newer = false) else RequestRead.Bad(LinkProblem.BAD_TYPE)
        val body = Bodies.read(type, bodyMap) ?: return RequestRead.Bad(LinkProblem.BAD_FIELD)
        val r = LinkRequest(id, at, ttl, lang, body)
        if (!json(r).toByteArray(Charsets.UTF_8).contentEquals(raw)) return RequestRead.Bad(LinkProblem.NOT_CANONICAL)
        return RequestRead.Ok(r)
    }
}
