// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import java.lang.Character.UnicodeScript

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1, §4.5; job L4): contact cards, `card.offer`. What travels is a
// vCard (RFC 6350) of at most 2 KiB, and only the strict subset the plan names: a name, phones, emails and XMPP addresses.
// Robot writes it one way ([LinkVCard.write]) and reads only that: a card is accepted when it is exactly what the writer makes
// of what was read from it. So there is one spelling of each card, nothing to unfold, no parameter, no group, no other
// property: no PHOTO (no picture is ever decoded from a card: a photo travels as a file, through :files), no URL, LOGO,
// SOUND, KEY or any address Robot could be led to fetch, no ENCODING or CHARSET. Anything else refuses the whole card, as
// any other field past its rules drops a request (§3.3); a newer Robot that wants more sends a new type (D22).
//
//   BEGIN:VCARD CRLF
//   VERSION:4.0 CRLF
//   FN:<name, with \\ \, \; escaped> CRLF
//   TEL;VALUE=text:<phone> CRLF            (0 to 5, in the card's order)
//   EMAIL:<address> CRLF                   (0 to 5)
//   IMPP:xmpp:<address> CRLF               (0 to 3)
//   END:VCARD CRLF
//
// [LinkVCard.read] runs only in :linkparse, the isolated reader (ProcessIsolationTest's linkParsesOnlyInIsolation). :link gets the
// card's fields back and checks them again: [ContactCard]'s rules, and [LinkVCard.write] of them must be the vCard it received
// (CardOffer's own rule). Nothing here logs; a card never shows in a log line.

/**
 * A contact card's fields: [name] (other people's free text, LinkText, at most 80 characters, and no word mixing Latin,
 * Cyrillic or Greek letters, the letters that pass for each other), at most 5 [phones], 5 [emails] and 3 [xmpp] addresses,
 * each once, at least one of them. Lands in People as unconfirmed (§4.1), never changing someone you have.
 */
class ContactCard(val name: String, val phones: List<String> = emptyList(), val emails: List<String> = emptyList(), val xmpp: List<String> = emptyList()) {
    init {
        require(LinkText.ok(name, NAME) && !LinkVCard.mixedScripts(name))
        require(phones.size <= PHONES && emails.size <= EMAILS && xmpp.size <= XMPP && phones.size + emails.size + xmpp.size >= 1)
        require(phones.all(LinkVCard::phoneOk) && emails.all(LinkVCard::addressOk) && xmpp.all(LinkVCard::addressOk))
        require(phones.toSet().size == phones.size && emails.toSet().size == emails.size && xmpp.toSet().size == xmpp.size)
    }

    override fun equals(other: Any?) = other is ContactCard && other.name == name && other.phones == phones && other.emails == emails && other.xmpp == xmpp
    override fun hashCode() = name.hashCode()
    override fun toString(): String = "ContactCard(${phones.size}, ${emails.size}, ${xmpp.size})" // l10n: not text (toString for logs and tests)

    companion object {
        const val NAME = 80
        const val PHONES = 5
        const val EMAILS = 5
        const val XMPP = 3
        /** A phone as written: at most 32 characters. An email or XMPP address: at most 120. */
        const val PHONE_CHARS = 32
        const val ADDRESS_CHARS = 120
    }
}

object LinkVCard {
    private const val CRLF = "\r\n" // l10n: not text (vCard syntax)
    private const val HEAD = "BEGIN:VCARD\r\nVERSION:4.0\r\n" // l10n: not text (vCard syntax)
    private const val TAIL = "END:VCARD\r\n" // l10n: not text (vCard syntax)
    private const val FN = "FN:" // l10n: not text (vCard syntax)
    private const val TEL = "TEL;VALUE=text:" // l10n: not text (vCard syntax)
    private const val EMAIL = "EMAIL:" // l10n: not text (vCard syntax)
    private const val IMPP = "IMPP:xmpp:" // l10n: not text (vCard syntax)
    /** CardOffer's cap (RequestCaps.VCARD_BYTES), in UTF-8 bytes. */
    const val MAX_BYTES = 2_048

    /** [card]'s one spelling. Always within [MAX_BYTES]: ContactCard's caps keep it under (LinkVCardTest's worst case). */
    fun write(card: ContactCard): String = buildString {
        append(HEAD).append(FN).append(escape(card.name)).append(CRLF)
        for (p in card.phones) append(TEL).append(p).append(CRLF)
        for (e in card.emails) append(EMAIL).append(e).append(CRLF)
        for (x in card.xmpp) append(IMPP).append(x).append(CRLF)
        append(TAIL)
    }

    /**
     * The card [v] holds, when [v] is exactly what [write] makes of it (within [MAX_BYTES]); null for anything else. Never
     * throws (LinkVCardTest's damaged and random cards).
     */
    fun read(v: String): ContactCard? = try {
        readOrNull(v)
    } catch (_: RuntimeException) {
        null
    }

    private fun readOrNull(v: String): ContactCard? {
        if (v.length > MAX_BYTES || v.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        if (!v.startsWith(HEAD) || !v.endsWith(TAIL) || v.length < HEAD.length + TAIL.length + FN.length + 3) return null
        val middle = v.substring(HEAD.length, v.length - TAIL.length)
        if (!middle.endsWith(CRLF)) return null
        val lines = middle.substring(0, middle.length - CRLF.length).split(CRLF)
        if (lines.any { '\r' in it || '\n' in it }) return null
        val first = lines[0]
        if (!first.startsWith(FN)) return null
        val name = unescape(first.substring(FN.length)) ?: return null
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        val xmpp = ArrayList<String>()
        for (line in lines.drop(1)) {
            when {
                line.startsWith(TEL) -> phones += line.substring(TEL.length)
                line.startsWith(EMAIL) -> emails += line.substring(EMAIL.length)
                line.startsWith(IMPP) -> xmpp += line.substring(IMPP.length)
                else -> return null
            }
            if (phones.size > ContactCard.PHONES || emails.size > ContactCard.EMAILS || xmpp.size > ContactCard.XMPP) return null
        }
        val card = try {
            ContactCard(name, phones, emails, xmpp)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // One spelling: the lines in the writer's order, nothing escaped that needn't be.
        return card.takeIf { write(it) == v }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            if (c == '\\' || c == ',' || c == ';') append('\\')
            append(c)
        }
    }

    /** A text value's escapes undone: only \\, \, and \; (what [escape] writes); a bare comma or semicolon is refused. */
    private fun unescape(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '\\' -> {
                    if (i + 1 >= s.length) return null
                    val n = s[i + 1]
                    if (n != '\\' && n != ',' && n != ';') return null
                    sb.append(n)
                    i += 2
                    continue
                }
                ',', ';' -> return null
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * A phone as people write one: 3 to 32 characters of digits, spaces, dots, dashes and brackets, an optional + first,
     * at least 3 digits, starting with a digit, + or ( and ending with a digit or ), no two spaces in a row.
     */
    fun phoneOk(p: String): Boolean {
        if (p.length !in 3..ContactCard.PHONE_CHARS) return false
        if (p.count { it in '0'..'9' } < 3) return false
        for ((i, c) in p.withIndex()) {
            val ok = c in '0'..'9' || c == ' ' || c == '.' || c == '-' || c == '(' || c == ')' || (c == '+' && i == 0)
            if (!ok) return false
        }
        if (!(p[0] in '0'..'9' || p[0] == '+' || p[0] == '(')) return false
        if (!(p.last() in '0'..'9' || p.last() == ')')) return false
        return !p.contains("  ") // l10n: not text (two spaces)
    }

    /**
     * An email or bare XMPP address, ASCII only (an international domain in its xn-- form): a local part of 1 to 64 of
     * letters, digits and . _ % + - (no dot first, last or twice in a row), @, then two or more labels of letters, digits
     * and dashes (1 to 63 each, no dash first or last), at most [ContactCard.ADDRESS_CHARS] in all. Nothing a vCard would
     * need to escape, nothing a card could show as something else.
     */
    fun addressOk(a: String): Boolean {
        if (a.length > ContactCard.ADDRESS_CHARS) return false
        val at = a.indexOf('@')
        if (at <= 0 || at != a.lastIndexOf('@')) return false
        val local = a.substring(0, at)
        val domain = a.substring(at + 1)
        if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        if (!local.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '%' || it == '+' || it == '-' }) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (l in labels) {
            if (l.isEmpty() || l.length > 63 || l.startsWith('-') || l.endsWith('-')) return false
            if (!l.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) return false
        }
        return labels.last().any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private val CONFUSABLE = setOf(UnicodeScript.LATIN, UnicodeScript.CYRILLIC, UnicodeScript.GREEK)

    /**
     * A word of [s] (split at white space) has letters of two of Latin, Cyrillic and Greek: "Pаypal" with a Cyrillic а.
     * Other mixes stay allowed (a Japanese name's kanji and kana, Latin initials beside them).
     */
    fun mixedScripts(s: String): Boolean {
        for (word in s.split(' ', ' ', '\t')) {
            val scripts = HashSet<UnicodeScript>()
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                val sc = try { UnicodeScript.of(cp) } catch (_: IllegalArgumentException) { continue }
                if (sc in CONFUSABLE) scripts += sc
            }
            if (scripts.size > 1) return true
        }
        return false
    }
}
