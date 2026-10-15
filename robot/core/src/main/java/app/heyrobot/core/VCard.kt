// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Pure. Contacts files (.vcf) for People (docs/people.md "Import a contacts file", "Export People as .vcf"): vCard 2.1,
// 3.0 and 4.0 in, vCard 3.0 out. Robot keeps names, nicknames, numbers, emails and XMPP addresses only; addresses,
// birthdays, notes, photos and everything else in a card are skipped. Proton Mail exports its contacts this way (on the
// web: Contacts › select all › Export selection as .VCF), so do the phone's Contacts app and most others.

/** One card as Robot keeps it. [name]: the formatted name, else built from N, else the first address. */
data class VCardEntry(
    val name: String,
    val nicknames: List<String> = emptyList(),
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<String> = emptyList(),
    val xmpp: List<XmppAddress> = emptyList(),
) {
    val reachable: Boolean get() = phones.isNotEmpty() || emails.isNotEmpty() || xmpp.isNotEmpty()
}

/** What a file holds, for the import preview: the cards worth keeping and how many were left out (nothing to reach). */
data class VCardFile(val entries: List<VCardEntry>, val skipped: Int) {
    val phones: Int get() = entries.sumOf { it.phones.size }
    val emails: Int get() = entries.sumOf { it.emails.size }
    val xmpp: Int get() = entries.sumOf { it.xmpp.size }
}

object VCard {
    /** Cards read from one file at most. */
    const val MAX_CARDS = People.MAX_PEOPLE
    /** Robot's own parameter on IMPP, so an export keeps the Cheogram account an address was seen in. */
    private const val ACCOUNT_PARAM = "X-CHEOGRAM-ACCOUNT"

    /**
     * A file's bytes as text: UTF-16 when it starts with that byte-order mark (or every other byte of its first
     * characters is zero), else UTF-8 (a byte-order mark aside), or Windows-1252 when they aren't valid UTF-8.
     */
    fun decode(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        val n = bytes.size
        return when {
            n >= 2 && at(0) == 0xFF && at(1) == 0xFE -> String(bytes, 2, n - 2, Charsets.UTF_16LE)
            n >= 2 && at(0) == 0xFE && at(1) == 0xFF -> String(bytes, 2, n - 2, Charsets.UTF_16BE)
            n >= 4 && at(0) != 0 && at(1) == 0 && at(2) != 0 && at(3) == 0 -> String(bytes, Charsets.UTF_16LE)
            n >= 4 && at(0) == 0 && at(1) != 0 && at(2) == 0 && at(3) != 0 -> String(bytes, Charsets.UTF_16BE)
            else -> {
                val b = if (n >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) bytes.copyOfRange(3, n) else bytes
                strict(b, Charsets.UTF_8) ?: String(b, Charset.forName("windows-1252"))
            }
        }
    }

    private fun strict(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) { null }

    /** One content line: its group ("item1"), name (upper case), parameters (names upper case, values as written) and value. */
    private class Line(val group: String?, val name: String, val params: List<Pair<String, String>>, val value: String) {
        fun has(param: String): Boolean = params.any { it.first == param }
        fun param(name: String): String? = params.firstOrNull { it.first == name }?.second
        /** The TYPE values in upper case, from TYPE=a,b, TYPE="a,b" and vCard 2.1's bare parameters (TEL;CELL;HOME:…). */
        val types: Set<String> get() = params.flatMap { (k, v) ->
            when {
                k == "TYPE" -> v.split(',').map { it.trim().uppercase(Locale.ROOT) }
                v.isEmpty() -> listOf(k)
                else -> emptyList()
            }
        }.toSet()
        val qp: Boolean get() = param("ENCODING")?.equals("QUOTED-PRINTABLE", ignoreCase = true) == true || has("QUOTED-PRINTABLE")
    }

    /** Properties whose values Robot never reads and that can be long (base64 pictures, sounds, keys): dropped as read. */
    private val BINARY = setOf("PHOTO", "LOGO", "SOUND", "KEY")

    /** A content line's property name, upper case, without its group ("item1.TEL;TYPE=CELL:…" is TEL). */
    private fun nameOf(raw: String): String = raw.substringBefore(':').substringBefore(';').substringAfterLast('.').trim().uppercase(Locale.ROOT)

    /**
     * The file's physical lines joined into content lines, in one pass: a line starting with a space or a tab continues
     * the one before (the space goes), and a quoted-printable value ending in "=" (a soft line break, vCard 2.1) goes on
     * on the next line, whose leading space, if any, is folding too. The values of [BINARY] properties are dropped on
     * the way, however long.
     */
    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        var cur: StringBuilder? = null
        var qp = false
        var skipping = false
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val folded = raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')
            val c = cur
            when {
                skipping && folded -> continue
                c != null && qp && c.endsWith("=") -> {
                    c.setLength(c.length - 1)
                    c.append(raw, if (folded) 1 else 0, raw.length)
                }
                c != null && folded -> c.append(raw, 1, raw.length)
                else -> {
                    c?.let { out += it.toString() }
                    cur = null
                    skipping = nameOf(raw) in BINARY
                    if (!skipping) {
                        cur = StringBuilder(raw)
                        qp = raw.substringBefore(':', "").uppercase(Locale.ROOT).contains("QUOTED-PRINTABLE")
                    }
                }
            }
        }
        cur?.let { out += it.toString() }
        return out
    }

    /** "item1.TEL;TYPE=CELL:+1 514…": the colon ending the name and parameters is the first one outside quotes. */
    private fun line(s: String): Line? {
        var quoted = false
        var colon = -1
        for (i in s.indices) {
            when (s[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) { colon = i; break }
            }
        }
        if (colon <= 0) return null
        val head = splitUnquoted(s.substring(0, colon), ';')
        val nameAndGroup = head.first().trim()
        val group = nameAndGroup.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        val name = nameAndGroup.substringAfterLast('.').uppercase(Locale.ROOT)
        if (name.isEmpty()) return null
        val params = head.drop(1).map { p ->
            val k = p.substringBefore('=').trim().uppercase(Locale.ROOT)
            val v = if (p.contains('=')) p.substringAfter('=').trim().removeSurrounding("\"") else ""
            k to v
        }
        return Line(group, name, params, s.substring(colon + 1))
    }

    private fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    /** "=C3=A9" as bytes, then as text in [charset] (UTF-8 by default; Windows-1252 when the bytes aren't valid UTF-8). */
    private fun quotedPrintable(v: String, charset: String?): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '=' && i + 2 < v.length && v.substring(i + 1, i + 3).all { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }) {
                bytes.write(v.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        val b = bytes.toByteArray()
        val cs = charset?.let { try { Charset.forName(it) } catch (_: Exception) { null } }
        return if (cs != null && cs != Charsets.UTF_8) String(b, cs) else strict(b, Charsets.UTF_8) ?: String(b, Charset.forName("windows-1252"))
    }

    /** A value's parts split on [sep] where it isn't escaped ("\;"), each unescaped (\n, \, \; \\). */
    private fun parts(v: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c == '\\' && i + 1 < v.length -> {
                    when (val n = v[i + 1]) {
                        'n', 'N' -> cur.append('\n')
                        else -> cur.append(n)
                    }
                    i += 2
                    continue
                }
                c == sep -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun text(v: String): String = parts(v, '\u0000').joinToString("").replace(Regex("\\s+"), " ").trim()

    private fun kind(types: Set<String>): PhoneKind = when {
        "CELL" in types || "MOBILE" in types || "IPHONE" in types -> PhoneKind.MOBILE
        "HOME" in types -> PhoneKind.HOME
        "WORK" in types -> PhoneKind.WORK
        else -> PhoneKind.OTHER
    }

    /** One card's lines (between BEGIN:VCARD and END:VCARD) as Robot keeps it; null if it has no name or address. */
    private fun entry(lines: List<Line>): VCardEntry? {
        var fn: String? = null
        var n: String? = null
        val nicknames = ArrayList<String>()
        val phones = ArrayList<ContactPhone>()
        val emails = ArrayList<String>()
        val xmpp = ArrayList<XmppAddress>()
        // Apple-style labels for a group's number ("item1.X-ABLabel:Maman").
        val labels = lines.filter { it.name == "X-ABLABEL" && it.group != null }.associate { it.group!!.lowercase(Locale.ROOT) to text(value(it)).removeSurrounding("_\$!<", ">!\$_") }
        for (l in lines) {
            val v = value(l)
            when (l.name) {
                "FN" -> text(v).takeIf { it.isNotEmpty() }?.let { if (fn == null) fn = it }
                "N" -> {
                    val p = parts(v, ';').map { it.replace(Regex("\\s+"), " ").trim() }
                    // family; given; additional; prefixes; suffixes
                    val built = listOf(p.getOrNull(1), p.getOrNull(2), p.getOrNull(0)).filter { !it.isNullOrEmpty() }.joinToString(" ")
                    val full = built.ifEmpty { listOf(p.getOrNull(3), p.getOrNull(4)).filter { !it.isNullOrEmpty() }.joinToString(" ") }
                    if (n == null && full.isNotEmpty()) n = full
                }
                "NICKNAME" -> parts(v, ',').map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.forEach { nicknames += it }
                // The phone's Contacts app writes nicknames this way in its own vCard 2.1 export.
                "X-ANDROID-CUSTOM" -> parts(v, ';').let { p ->
                    if (p.firstOrNull() == "vnd.android.cursor.item/nickname") p.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { nicknames += it }
                }
                "TEL" -> {
                    val num = text(v).let { if (it.lowercase(Locale.ROOT).startsWith("tel:")) UriText.decode(it.substring(4)).substringBefore(';') else it }.trim()
                    if (Addresses.isPhone(num)) {
                        val label = l.group?.let { labels[it.lowercase(Locale.ROOT)] }?.takeIf { it.isNotEmpty() }
                        // Apple's "Mobile" (or "iPhone", "Home", "Work") label says the kind when the type doesn't.
                        val k = kind(l.types).let { k -> if (k == PhoneKind.OTHER && label != null) kind(setOf(label.uppercase(Locale.ROOT))) else k }
                        phones += ContactPhone(num, k, label ?: People.label(k))
                    }
                }
                "EMAIL" -> text(v).removePrefix("mailto:").trim().takeIf(Addresses::isAddress)?.let { emails += it }
                "IMPP" -> XmppLink.jid(text(v).let { if (it.lowercase(Locale.ROOT).startsWith("jabber:")) "xmpp:" + it.substring(7) else it })?.let {
                    xmpp += XmppAddress(it, l.param(ACCOUNT_PARAM)?.takeIf(Addresses::isAddress))
                }
                "X-JABBER", "X-XMPP" -> text(v).removePrefix("xmpp:").trim().takeIf(Addresses::isAddress)?.let { xmpp += XmppAddress(it) }
            }
        }
        val name = fn ?: n ?: emails.firstOrNull() ?: xmpp.firstOrNull()?.jid ?: phones.firstOrNull()?.number ?: return null
        val e = VCardEntry(
            name = ToolCalls.safeTake(name, People.MAX_NAME),
            nicknames = nicknames.distinctBy { ContactMatcher.words(it) }.filter { !People.sameName(it, name) },
            phones = phones.distinctBy { PhoneNumbers.digits(it.number).takeLast(10) },
            emails = emails.distinctBy { it.lowercase(Locale.ROOT) },
            // An address written twice (IMPP and X-JABBER) once, with the account if either line had one.
            xmpp = xmpp.groupBy { it.jid.lowercase(Locale.ROOT) }.map { (_, same) -> same.firstOrNull { it.account != null } ?: same.first() },
        )
        return e
    }

    private fun value(l: Line): String = if (l.qp) quotedPrintable(l.value, l.param("CHARSET")) else l.value

    /**
     * Every card in [text], in order, with how many had nothing to reach them by (left out). Cards inside a card (vCard
     * 2.1's AGENT) are skipped with their parent's other properties kept. At most [MAX_CARDS].
     */
    fun parse(text: String): VCardFile {
        val entries = ArrayList<VCardEntry>()
        var skipped = 0
        var depth = 0
        var card = ArrayList<Line>()
        for (raw in unfold(text)) {
            if (raw.isBlank()) continue
            val l = line(raw) ?: continue
            when {
                l.name == "BEGIN" && l.value.trim().equals("VCARD", ignoreCase = true) -> {
                    if (depth == 0) card = ArrayList()
                    depth++
                }
                l.name == "END" && l.value.trim().equals("VCARD", ignoreCase = true) -> {
                    if (depth == 0) continue
                    depth--
                    if (depth == 0) {
                        val e = entry(card)
                        if (e != null && e.reachable) {
                            if (entries.size < MAX_CARDS) entries += e else skipped++
                        } else skipped++
                    }
                }
                depth == 1 -> card += l
            }
        }
        // A last card the file never ended (END:VCARD missing) is kept too.
        if (depth > 0) {
            val e = entry(card)
            if (e != null && e.reachable && entries.size < MAX_CARDS) entries += e else skipped++
        }
        return VCardFile(entries, skipped)
    }

    /** An imported card as a person of People: its nicknames stay nicknames (only names you give become aliases). */
    fun person(e: VCardEntry, id: String, now: Long, source: PersonSource = PersonSource.VCARD): Person =
        Person(id, e.name, nicknames = e.nicknames, phones = e.phones, emails = e.emails, xmpp = e.xmpp, source = source, confirmed = true, updatedAt = now)

    // ------------------------------------------------------------------ writing

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    /** Lines longer than 75 bytes folded (a CRLF and a space), never inside a character. */
    private fun fold(line: String): String {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val n = ch.toByteArray(Charsets.UTF_8).size
            if (bytes + n > 75) {
                out.append("\r\n ")
                bytes = 1
            }
            out.append(ch)
            bytes += n
            i += Character.charCount(cp)
        }
        return out.append("\r\n").toString()
    }

    private fun telType(k: PhoneKind): String = when (k) {
        PhoneKind.MOBILE -> "CELL"
        PhoneKind.HOME -> "HOME"
        PhoneKind.WORK -> "WORK"
        PhoneKind.OTHER -> "VOICE"
    }

    /**
     * People as one vCard 3.0 file (UTF-8, CRLF): FN and N, NICKNAME (what you call them and their other names), TEL, EMAIL, and each XMPP
     * address as IMPP (with the Cheogram account in Robot's own parameter) and as X-JABBER, which the phone's Contacts
     * app reads.
     */
    fun write(people: List<Person>): String = buildString {
        for (p in people) {
            append(fold("BEGIN:VCARD"))
            append(fold("VERSION:3.0"))
            append(fold("FN:" + escape(p.name)))
            append(fold("N:;" + escape(p.name) + ";;;"))
            val nick = p.aliases + p.nicknames
            if (nick.isNotEmpty()) append(fold("NICKNAME:" + nick.joinToString(",") { escape(it) }))
            for (t in p.phones) append(fold("TEL;TYPE=" + telType(t.kind) + ":" + escape(t.number)))
            for (e in p.emails) append(fold("EMAIL;TYPE=INTERNET:" + escape(e)))
            for (x in p.xmpp) {
                val acct = x.account?.takeIf { Addresses.isAddress(it) && !it.contains('"') }?.let { ";$ACCOUNT_PARAM=\"$it\"" } ?: ""
                append(fold("IMPP$acct:xmpp:" + x.jid))
                append(fold("X-JABBER:" + escape(x.jid)))
            }
            append(fold("END:VCARD"))
        }
    }
}
