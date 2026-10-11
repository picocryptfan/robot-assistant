package app.heyrobot.core

// Pure. The links that hand a draft to another app (docs/phase-2.md "What the hand-off apps accept"): Cheogram's
// xmpp: message link, the mailto: link Proton Mail reads, the SMS app's smsto:. Nothing is sent: the app opens with
// the draft and the person presses Send there.

object UriText {
    private const val HEX = "0123456789ABCDEF"
    /** What android.net.Uri.encode leaves as it is, besides letters and digits. */
    private const val URI_UNRESERVED = "_-!.~'()*"
    /** RFC 3986's unreserved characters, besides letters and digits. */
    private const val RFC_UNRESERVED = "-._~"

    /**
     * As android.net.Uri.encode(s, allow): ASCII letters, digits, "_-!.~'()*" and the characters in [allow] stay;
     * every other character becomes the %XX of its UTF-8 bytes.
     */
    fun encode(s: String, allow: String = ""): String = escape(s) { c -> c in URI_UNRESERVED || c in allow }

    /** Only RFC 3986's unreserved characters (A-Z a-z 0-9 - . _ ~) stay; a space is %20, never "+". */
    fun strict(s: String): String = escape(s) { c -> c in RFC_UNRESERVED }

    /** %XX sequences as their UTF-8 characters ("%C3%A9" → "é", "%2B" → "+"); a '%' that starts none stays as written. */
    fun decode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private inline fun escape(s: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder(s.length * 3)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || keep(c)) {
                out.append(c)
                i++
                continue
            }
            // One code point at a time, so an emoji's two UTF-16 halves become its four UTF-8 bytes.
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xFF
                out.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
        }
        return out.toString()
    }
}

object XmppLink {
    /**
     * "xmpp:<jid>?message;body=<text>": Cheogram (UriHandlerActivity → StartConversationActivity) opens the
     * conversation with the text in the compose box. The body is encoded twice: Cheogram's XmppUri.parseParameters
     * takes android.net.Uri.getQuery() (already decoded once), splits it on ';' and '=', then runs URLDecoder.decode
     * on the value. Encoded once, a ';' would cut the text short, a '+' would become a space and a lone '%' would empty
     * the whole body. So: every byte that isn't unreserved as %XX (a space as %20), then every '%' as %25.
     */
    fun message(jid: String, body: String): String =
        "xmpp:" + UriText.encode(jid, "@+") + "?message;body=" + UriText.strict(body).replace("%", "%25")

    /**
     * The bare address in an xmpp: link ("xmpp:mom@example.org", "xmpp://mom@example.org/res?message"), or null for
     * anything else: another scheme, nothing usable, a group chat ("?join"). A resource and a query are left aside.
     */
    fun jid(uri: String?): String? {
        val u = uri?.trim() ?: return null
        if (!u.lowercase(java.util.Locale.ROOT).startsWith("xmpp:")) return null
        val rest = u.substring(5)
        if (Regex("""[?;]join\b""").containsMatchIn(rest)) return null
        val bare = UriText.decode(rest.removePrefix("//").substringBefore('?').substringBefore('#')).substringBefore('/').trim()
        return bare.takeIf { Addresses.isAddress(it) }
    }
}

object MailTo {
    /**
     * RFC 6068: "mailto:<address>?subject=…&body=…", with %20 for spaces and line breaks as %0D%0A. Proton Mail reads
     * the link itself (its IntentMapper routes mailto: to the composer); the app also puts the same values in the
     * intent's extras for other email apps.
     */
    fun uri(address: String, subject: String, body: String): String {
        val params = buildList {
            if (subject.isNotEmpty()) add("subject=" + UriText.strict(subject))
            if (body.isNotEmpty()) add("body=" + UriText.strict(body.replace("\r\n", "\n").replace("\n", "\r\n")))
        }
        // '+' as %2B: RFC 6068 allows it raw, but a form-style decoder would read it as a space.
        return "mailto:" + UriText.encode(address, "@") + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }
}

object SmsTo {
    /** "smsto:<number>" for the SMS app (the text goes in the sms_body extra). */
    fun uri(number: String): String = "smsto:" + UriText.encode(number, "+")
}
