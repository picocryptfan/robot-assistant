// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.regex.Pattern

/** The links that hand drafts to Cheogram, Proton Mail and the SMS app (docs/phase-2.md). */
class HandOffTest {
    // ------------------------------------------------------------------ Cheogram, step by step as its source does it

    /**
     * android.net.Uri.parse(uri).getQuery() for an opaque URI like "xmpp:jid?message;body=…" (StringUri.parseQuery:
     * from the first '?' after the scheme to the '#', then Uri.decode, which is UriCodec.decode with convertPlus false
     * and throwOnFailure false: %XX runs as UTF-8, a malformed byte or a bad escape as U+FFFD, '+' kept).
     */
    private fun androidGetQuery(uri: String): String? {
        val ssi = uri.indexOf(':')
        val qsi = uri.indexOf('?', ssi)
        if (qsi < 0) return null
        val fsi = uri.indexOf('#', ssi)
        val encoded = when {
            fsi < 0 -> uri.substring(qsi + 1)
            fsi < qsi -> return null
            else -> uri.substring(qsi + 1, fsi)
        }
        return uriDecode(encoded)
    }

    /** android.net.Uri.getSchemeSpecificPart() (decoded), as XmppUri.parse reads the address from it. */
    private fun androidSchemeSpecificPart(uri: String): String {
        val ssi = uri.indexOf(':')
        val fsi = uri.indexOf('#', ssi)
        return uriDecode(if (fsi < 0) uri.substring(ssi + 1) else uri.substring(ssi + 1, fsi))
    }

    private fun uriDecode(s: String): String {
        val out = StringBuilder()
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() == 0) return
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("�")
            out.append(dec.decode(ByteBuffer.wrap(bytes.toByteArray())))
            bytes.reset()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                // As libcore's UriCodec (throwOnFailure false): the end of the input ends decoding with U+FFFD; a
                // character that isn't hex is consumed and becomes U+FFFD.
                val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (i + 1 >= s.length || (hi != null && i + 2 >= s.length)) {
                    flush()
                    out.append('�')
                    return out.toString()
                }
                if (hi == null || lo == null) {
                    flush()
                    out.append('�')
                    i += if (hi == null) 2 else 3
                    continue
                }
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                flush()
                out.append(c)
                i++
            }
        }
        flush()
        return out.toString()
    }

    /** eu.siacs.conversations.utils.XmppUri.parseParameters(query, ';'), line by line (Java's split semantics). */
    private fun cheogramParameters(query: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val pairs = if (query == null) emptyArray() else Pattern.compile(";").split(query)
        for (pair in pairs) {
            val parts = Pattern.compile("=").split(pair, 2)
            if (parts.isEmpty()) continue
            val key = parts[0].lowercase(Locale.US)
            val value = if (parts.size == 2) urlDecodeOrEmpty(parts[1]) else ""
            out[key] = value // buildKeepingLast
        }
        return out
    }

    /** de.gultsch.common.MiniUri.urlDecodeOrEmpty. */
    private fun urlDecodeOrEmpty(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: IllegalArgumentException) { "" }

    /** What Cheogram's compose box shows for [uri]: XmppUri(uri).getBody(). */
    private fun cheogramBody(uri: String): String? = cheogramParameters(androidGetQuery(uri))["body"]

    /** XmppUri.parse for "xmpp:" without an authority: the decoded scheme-specific part up to the first '?'. */
    private fun cheogramJid(uri: String): String = androidSchemeSpecificPart(uri).split("?")[0]

    private val hard = listOf(
        "; = & + % # ? / é 😀",
        "I'm running late; see you at 7 = promise & sorry + more",
        "100% sure",
        "%",
        "%25 is a percent sign, %2B a plus",
        "a+b+c",
        "body=evil;body=other",
        "line one\nline two\r\nline three\n",
        "  leading and trailing spaces  ",
        "tab\there",
        "Ünïcödé — “quotes” ‘single’ 東京 привет 👍🏽👨‍👩‍👧",
        "#hashtag ?question /slash \\backslash",
        "message;body=",
        "",
    )

    @Test fun cheogramReadsBackExactlyWhatRobotWrote() {
        for (text in hard) {
            val uri = XmppLink.message("+15145550101@cheogram.com", text)
            assertEquals(text, text, cheogramBody(uri))
            assertEquals("+15145550101@cheogram.com", cheogramJid(uri))
            // It is the message action, and nothing in the text adds a parameter.
            assertEquals(setOf("message", "body"), cheogramParameters(androidGetQuery(uri)).keys)
            // No raw character in the link that Uri, the ';' split or URLDecoder would read specially.
            val query = uri.substringAfter('?')
            assertFalse(uri, uri.contains('#') || uri.contains(' ') || uri.contains('\n'))
            assertFalse(uri, query.substringAfter("body=").any { it == ';' || it == '=' || it == '+' || it == '&' || it == '?' })
        }
    }

    @Test fun aLongMessageSurvivesWhole() {
        val long = (1..400).joinToString(" ") { "word$it;=+%#é😀" }.take(ToolCalls.MAX_MESSAGE)
        assertEquals(long, cheogramBody(XmppLink.message("+33612345678@cheogram.com", long)))
    }

    @Test fun anEmptyBodyIsAnEmptyComposeBox() {
        val uri = XmppLink.message("+15145550101@cheogram.com", "")
        assertEquals("xmpp:+15145550101@cheogram.com?message;body=", uri)
        assertEquals("", cheogramBody(uri))
    }

    @Test fun theLinkHasTheShapeCheogramExpects() {
        assertEquals("xmpp:+15145550101@cheogram.com?message;body=On%2520my%2520way", XmppLink.message("+15145550101@cheogram.com", "On my way"))
        assertEquals("xmpp:mom@example.org?message;body=Hi%2521", XmppLink.message("mom@example.org", "Hi!"))
        // The address goes through Uri.encode(jid, "@+"): a stray character can't open a query or a fragment.
        val odd = XmppLink.message("we?ird#user@example.org", "x")
        assertEquals("we%3Fird%23user@example.org", odd.removePrefix("xmpp:").substringBefore('?'))
        assertEquals("x", cheogramBody(odd))
        // Cheogram reads the address from the decoded part before '?': such an address would open "we" (review A
        // minor 4), so Robot never builds a link for it (Addresses.isJid); real addresses come back whole.
        assertEquals("we", cheogramJid(odd))
        assertFalse(Addresses.isJid("we?ird#user@example.org"))
        for (j in listOf("mom@example.org", "+15145550101@cheogram.com", "first.last+tag@jabber.example.org", "x_y-z@chat.example.co.uk")) {
            assertTrue(j, Addresses.isJid(j))
            assertEquals(j, cheogramJid(XmppLink.message(j, "hi; there")))
        }
    }

    /** Why the body is encoded twice: once is not enough for Cheogram's double decode. */
    @Test fun encodingOnceWouldLoseText() {
        fun once(text: String) = "xmpp:+15145550101@cheogram.com?message;body=" + UriText.strict(text)
        assertEquals("a", cheogramBody(once("a;b")))          // ';' cuts the text short
        assertEquals("1 2", cheogramBody(once("1+2")))         // '+' becomes a space
        assertEquals("", cheogramBody(once("100% sure")))      // a lone '%' empties the body
        assertEquals("é", cheogramBody(once("%C3%A9")))         // a written escape is decoded a second time
        assertEquals("%C3%A9", cheogramBody(XmppLink.message("+15145550101@cheogram.com", "%C3%A9")))
        for (t in listOf("a;b", "1+2", "100% sure")) assertEquals(t, cheogramBody(XmppLink.message("+15145550101@cheogram.com", t)))
    }

    // ------------------------------------------------------------------ the encoders

    @Test fun anXmppLinkGivesItsBareAddress() {
        // People: Cheogram's pick and a contacts file's IMPP lines are xmpp: links.
        assertEquals("+15145550101@cheogram.com", XmppLink.jid("xmpp:+15145550101@cheogram.com"))
        assertEquals("zoé@example.org", XmppLink.jid("xmpp:zo%C3%A9@example.org/phone?message"))
        assertEquals("a+b@example.org", XmppLink.jid("xmpp://a%2Bb@example.org"))
        assertEquals("mom@example.org", XmppLink.jid(" XMPP:mom@example.org?message;body=hi "))
        assertEquals(null, XmppLink.jid("xmpp:friends@conference.example.org?join"))
        assertEquals(null, XmppLink.jid("sip:x@example.org"))
        assertEquals(null, XmppLink.jid("xmpp:"))
        assertEquals(null, XmppLink.jid(null))
        // Robot's own link reads back.
        assertEquals("+15145550101@cheogram.com", XmppLink.jid(XmppLink.message("+15145550101@cheogram.com", "a; b")))
        assertEquals("100%", UriText.decode("100%"))
        assertEquals("%zz é", UriText.decode("%zz %C3%A9"))
    }

    @Test fun uriEncodeMatchesAndroidsRules() {
        // android.net.Uri.encode keeps letters, digits and "_-!.~'()*" (plus what is allowed) and encodes UTF-8 bytes.
        assertEquals("a%20b+c@d", UriText.encode("a b+c@d", "@+"))
        assertEquals("_-!.~'()*", UriText.encode("_-!.~'()*"))
        assertEquals("%2B%40%3B%3D%26%25%23%3F%2F", UriText.encode("+@;=&%#?/"))
        assertEquals("%C3%A9%F0%9F%98%80", UriText.encode("é😀"))
        // Strict: only RFC 3986's unreserved characters stay; a space is %20, never '+'.
        assertEquals("a%20b%21%27%28%29%2A-._~", UriText.strict("a b!'()*-._~"))
        assertEquals("%0A%0D%09", UriText.strict("\n\r\t"))
    }

    // ------------------------------------------------------------------ mailto (RFC 6068) and smsto

    private fun mailtoParams(uri: String): Pair<String, Map<String, String>> {
        val rest = uri.removePrefix("mailto:")
        val to = uriDecode(rest.substringBefore('?'))
        val q = if ('?' in rest) rest.substringAfter('?') else ""
        val params = q.split('&').filter { it.isNotEmpty() }.associate { p -> p.substringBefore('=') to uriDecode(p.substringAfter('=', "")) }
        return to to params
    }

    @Test fun mailtoCarriesSubjectAndBodyForProtonMail() {
        val uri = MailTo.uri("alex@example.org", "Friday's plan & more", "Hi Alex,\nSee you at 7 = sharp + bring 100%.\n\n— Me 😀")
        assertTrue(uri.startsWith("mailto:alex@example.org?subject=Friday%27s%20plan%20%26%20more&body=Hi%20Alex%2C%0D%0ASee"))
        assertFalse(uri.contains('+') || uri.contains(' ') || uri.contains('#'))
        val (to, p) = mailtoParams(uri)
        assertEquals("alex@example.org", to)
        assertEquals("Friday's plan & more", p["subject"])
        // RFC 6068: line breaks in the body are CRLF (%0D%0A).
        assertEquals("Hi Alex,\r\nSee you at 7 = sharp + bring 100%.\r\n\r\n— Me 😀", p["body"])
        // '+' in the address as %2B (review A minor 5): a form-style decoder would make it a space.
        assertEquals("mailto:a.b%2Btag@example.org", MailTo.uri("a.b+tag@example.org", "", ""))
        assertEquals("a.b+tag@example.org", mailtoParams(MailTo.uri("a.b+tag@example.org", "", "")).first)
        assertEquals("mailto:x@example.org?body=only%20a%20body", MailTo.uri("x@example.org", "", "only a body"))
        assertEquals(mapOf("subject" to "?&=#"), mailtoParams(MailTo.uri("x@example.org", "?&=#", "")).second)
    }

    @Test fun smstoKeepsThePlus() {
        assertEquals("smsto:+15145550101", SmsTo.uri("+15145550101"))
        assertEquals("smsto:514%20555%200101", SmsTo.uri("514 555 0101"))
    }
}
