// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Pure. Today H7 (home-design §6.1, "Limits and cost"): the strict reader of what a feed's server sent. Anyone's server can
// send anything, so this is a small reader of our own and not an XML library: no DTD at all (a document with "<!DOCTYPE"
// or "<!ENTITY" is refused, so there is no entity to expand and no external file to fetch), only XML's five named entities
// and numeric ones, and hard caps on the input, the depth, the number of elements, the attributes and each element's text.
// What comes out is a small tree of names and text; FeedRead takes titles, links and dates from it and nothing else.

/** One element: its local name in lower case (any prefix dropped), its attributes (local names too), children and text. */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>()
    private val own = StringBuilder()
    val text: String get() = own.toString()

    fun child(n: String): XmlNode? = children.firstOrNull { it.name == n }
    fun all(n: String): List<XmlNode> = children.filter { it.name == n }

    internal fun add(s: CharSequence) {
        val room = FeedXml.MAX_TEXT - own.length
        if (room > 0) own.append(s, 0, minOf(room, s.length))
    }
}

object FeedXml {
    /** A feed's reply past this is refused, not read in part (home-design: 512 KB each). */
    const val MAX_BYTES = 512 * 1024
    const val MAX_DEPTH = 32
    const val MAX_NODES = 20_000
    const val MAX_ATTRS = 32
    const val MAX_NAME = 128
    /** An element's own text kept: a huge title is cut here first, then by FeedText. */
    const val MAX_TEXT = 8_192

    private class Bad : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** [bytes] as a tree, or null: too big, an encoding Robot doesn't read, a DTD, or not well formed. */
    fun parse(bytes: ByteArray): XmlNode? = try {
        if (bytes.size > MAX_BYTES) null else read(decode(bytes) ?: return null)
    } catch (_: Bad) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private val DECLARED = Regex("""^<\?xml[^>]{0,200}?encoding\s*=\s*["']([A-Za-z0-9._-]{1,40})["']""")

    /** The text, by its byte-order mark or its declaration: UTF-8 unless it says one of the few others. */
    internal fun decode(b: ByteArray): String? {
        fun with(cs: Charset, from: Int): String = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(b, from, b.size - from)).toString()
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return with(Charsets.UTF_8, 3)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return with(Charsets.UTF_16BE, 2)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return with(Charsets.UTF_16LE, 2)
        val head = String(b, 0, minOf(b.size, 256), Charsets.ISO_8859_1)
        val named = DECLARED.find(head)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: "utf-8"
        val cs = when (named) {
            "utf-8", "utf8" -> Charsets.UTF_8
            "us-ascii", "ascii" -> Charsets.US_ASCII
            "iso-8859-1", "latin1", "iso8859-1" -> Charsets.ISO_8859_1
            "windows-1252", "cp1252" -> Charset.forName("windows-1252")
            else -> return null
        }
        return with(cs, 0)
    }

    private fun local(qname: String): String = qname.substringAfterLast(':').lowercase(Locale.ROOT)

    private fun nameChar(c: Char): Boolean = !(c.isWhitespace() || c == '/' || c == '>' || c == '<' || c == '=' || c == '"' || c == '\'' || c == '&')

    private fun read(s: String): XmlNode? {
        var i = if (s.startsWith("\uFEFF")) 1 else 0
        val stack = ArrayList<XmlNode>()
        var root: XmlNode? = null
        var nodes = 0
        val n = s.length
        while (i < n) {
            if (s[i] != '<') {
                val e = s.indexOf('<', i).let { if (it < 0) n else it }
                // Text outside the root may only be white space.
                val top = stack.lastOrNull()
                if (top != null) top.add(unescape(s.substring(i, e))) else if (s.substring(i, e).isNotBlank()) throw Bad()
                i = e
                continue
            }
            when {
                s.startsWith("<!--", i) -> i = end(s, "-->", i + 4)
                s.startsWith("<![CDATA[", i) -> { // l10n: not text (XML syntax)
                    val e = s.indexOf("]]>", i + 9)
                    if (e < 0) throw Bad()
                    (stack.lastOrNull() ?: throw Bad()).add(s.substring(i + 9, e))
                    i = e + 3
                }
                s.startsWith("<?", i) -> i = end(s, "?>", i + 2)
                // No DTD, ever: DOCTYPE, ENTITY, ELEMENT and anything else that starts "<!".
                s.startsWith("<!", i) -> throw Bad()
                s.startsWith("</", i) -> {
                    val e = s.indexOf('>', i)
                    if (e < 0) throw Bad()
                    val top = stack.removeLastOrNull() ?: throw Bad()
                    if (top.name != local(s.substring(i + 2, e).trim())) throw Bad()
                    i = e + 1
                }
                else -> {
                    var j = i + 1
                    while (j < n && nameChar(s[j])) j++
                    val qname = s.substring(i + 1, j)
                    if (qname.isEmpty() || qname.length > MAX_NAME) throw Bad()
                    val attrs = LinkedHashMap<String, String>()
                    var empty = false
                    while (true) {
                        while (j < n && s[j].isWhitespace()) j++
                        if (j >= n) throw Bad()
                        if (s[j] == '>') { j++; break }
                        if (s.startsWith("/>", j)) { j += 2; empty = true; break }
                        val a = j
                        while (j < n && nameChar(s[j])) j++
                        val an = s.substring(a, j)
                        if (an.isEmpty() || an.length > MAX_NAME) throw Bad()
                        while (j < n && s[j].isWhitespace()) j++
                        if (j >= n || s[j] != '=') throw Bad()
                        j++
                        while (j < n && s[j].isWhitespace()) j++
                        if (j >= n || (s[j] != '"' && s[j] != '\'')) throw Bad()
                        val q = s[j]
                        val ve = s.indexOf(q, j + 1)
                        if (ve < 0) throw Bad()
                        val raw = s.substring(j + 1, ve)
                        if (raw.contains('<')) throw Bad()
                        if (attrs.size >= MAX_ATTRS) throw Bad()
                        attrs.putIfAbsent(local(an), unescape(raw).take(MAX_TEXT))
                        j = ve + 1
                    }
                    if (++nodes > MAX_NODES) throw Bad()
                    val node = XmlNode(local(qname), attrs)
                    val top = stack.lastOrNull()
                    if (top == null) {
                        if (root != null) throw Bad()
                        root = node
                    } else top.children += node
                    if (!empty) {
                        stack += node
                        if (stack.size > MAX_DEPTH) throw Bad()
                    }
                    i = j
                }
            }
        }
        if (stack.isNotEmpty()) throw Bad()
        return root
    }

    private fun end(s: String, close: String, from: Int): Int {
        val e = s.indexOf(close, from)
        if (e < 0) throw Bad()
        return e + close.length
    }

    /**
     * XML's five named entities and numeric ones; any other "&name;" is dropped (there is no DTD to define it), and a
     * character reference to a control, a surrogate or nothing is dropped too. A lone "&" stays.
     */
    fun unescape(t: String): String {
        if (t.indexOf('&') < 0) return t
        val out = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            val semi = if (c == '&') t.indexOf(';', i + 1).takeIf { it > i && it - i <= 12 } else null
            if (semi == null) { out.append(c); i++; continue }
            val name = t.substring(i + 1, semi)
            when {
                name == "lt" -> out.append('<')
                name == "gt" -> out.append('>')
                name == "amp" -> out.append('&')
                name == "quot" -> out.append('"')
                name == "apos" -> out.append('\'')
                name.startsWith("#") -> {
                    val cp = if (name.startsWith("#x") || name.startsWith("#X")) name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
                    if (cp != null && cp in 0x20..0x10FFFF && cp !in 0xD800..0xDFFF && cp !in 0x7F..0x9F) out.appendCodePoint(cp)
                    else if (cp == 0x9 || cp == 0xA || cp == 0xD) out.append(' ')
                }
                name.isNotEmpty() && name.all { it.isLetterOrDigit() } -> {}
                else -> { out.append(c); i++; continue }
            }
            i = semi + 1
        }
        return out.toString()
    }
}
