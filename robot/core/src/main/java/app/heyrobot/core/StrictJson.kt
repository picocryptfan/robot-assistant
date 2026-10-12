package app.heyrobot.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8

// Pure. JSON exactly as RFC 8259 writes it, for files Robot reads that someone else may have made (1.0: Settings ›
// Memory › Import). org.json reads much more: single-quoted strings, bare words, comments, "=" and ";", a key twice (the
// last one wins), text after the end. Here the bytes must be UTF-8, a string is only ever in double quotes with JSON's
// escapes, a number has JSON's form, a key appears once in an object, nothing follows the value but white space, and
// objects and arrays nest at most [StrictJson.MAX_DEPTH] deep (a deeper file would only be built to overflow a stack).
// Values come back as plain Kotlin: a Map (in the file's order), a List, a String, a Long (a whole number that fits) or a
// Double, a Boolean, or null.

object StrictJson {
    const val MAX_DEPTH = 32

    /** The value in [bytes] (UTF-8, a byte order mark allowed), or null when they aren't strict JSON. */
    fun parse(bytes: ByteArray): Result? {
        val text = try {
            UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return parse(text.removePrefix("﻿"))
    }

    /** The value in [text], or null when it isn't strict JSON. */
    fun parse(text: String): Result? = try {
        val p = Parser(text)
        p.space()
        val v = p.value(0)
        p.space()
        if (p.i != text.length) null else Result(v)
    } catch (_: Bad) {
        null
    }

    /** What [parse] read: [value] may itself be null (the text "null"). */
    class Result(val value: Any?)

    private class Bad : Exception() {
        // No stack trace, no message: nothing of the text is ever kept in one.
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (i >= s.length) throw Bad()
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Bad()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Bad()
            i += w.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            if (depth > MAX_DEPTH) throw Bad()
            i++
            val m = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') throw Bad()
                val k = str()
                space()
                if (i >= s.length || s[i] != ':') throw Bad()
                i++
                space()
                if (m.containsKey(k)) throw Bad()
                m[k] = value(depth)
                space()
                if (i >= s.length) throw Bad()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw Bad()
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            if (depth > MAX_DEPTH) throw Bad()
            i++
            val l = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                space()
                l += value(depth)
                space()
                if (i >= s.length) throw Bad()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw Bad()
                }
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad()
                val c = s[i++]
                when {
                    c == '"' -> break
                    c < ' ' -> throw Bad()
                    c == '\\' -> {
                        if (i >= s.length) throw Bad()
                        when (s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(hex())
                            else -> throw Bad()
                        }
                    }
                    else -> sb.append(c)
                }
            }
            // A surrogate on its own (from a \u escape: the UTF-8 decoding allows none) isn't text.
            for (k in sb.indices) {
                val c = sb[k]
                if (c.isHighSurrogate() && (k + 1 >= sb.length || !sb[k + 1].isLowSurrogate())) throw Bad()
                if (c.isLowSurrogate() && (k == 0 || !sb[k - 1].isHighSurrogate())) throw Bad()
            }
            return sb.toString()
        }

        private fun hex(): Char {
            if (i + 4 > s.length) throw Bad()
            var v = 0
            repeat(4) {
                val d = Character.digit(s[i++], 16)
                if (d < 0) throw Bad()
                v = v * 16 + d
            }
            return v.toChar()
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw Bad()
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else throw Bad()
            var whole = true
            if (i < s.length && s[i] == '.') {
                whole = false
                i++
                if (i >= s.length || s[i] !in '0'..'9') throw Bad()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                whole = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') throw Bad()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            if (whole) t.toLongOrNull()?.let { return it }
            return t.toDouble()
        }
    }
}
