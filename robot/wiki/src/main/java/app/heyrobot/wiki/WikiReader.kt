// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

/** What one ZIM file is: its article count, its indexes and its own name, language and date (metadata, as it says). */
data class WikiInfo(val articles: Long, val titleIndex: Boolean, val fullText: Boolean, val title: String?, val language: String?, val date: String?)

/** One article found: its path in the file and its title. */
data class WikiHit(val path: String, val title: String)

/** One article: [html] is the start of its page (a "mini" set holds only the intro and the infobox). */
data class WikiArticle(val path: String, val title: String, val html: String)

/**
 * :WIKI PROCESS ONLY. One open ZIM file and the four questions the :wiki process answers about it. Whatever the library
 * returns is cut down here before it leaves: at most [MAX_HITS] hits, paths and titles of bounded length with no control
 * characters, duplicates dropped, the article's HTML at most [MAX_HTML_BYTES] bytes. Queries are cleaned the same way
 * before they reach the library. Not thread-safe: WikiService calls it under one lock.
 */
internal class WikiReader(private val native: ZimNative) {
    internal companion object {
        const val MAX_HITS = 10
        const val MAX_QUERY = 200
        const val MAX_PATH = 512
        const val MAX_TITLE = 300
        const val MAX_META = 200
        const val MAX_HTML_BYTES = 256 * 1024
        private val CONTROL = Regex("""[\p{Cc}\p{Cf}&&[^\t\n]]""")
        private val SPACES = Regex("""\s+""")

        fun cleanLine(s: String, max: Int): String = SPACES.replace(CONTROL.replace(s, ""), " ").trim().take(max).trim()
    }

    private var handle = 0L

    val isOpen: Boolean get() = handle != 0L

    /** Opens the ZIM file behind [fd] (closing any other first); null when the library is missing or the file isn't one. */
    fun open(fd: Int): WikiInfo? {
        close()
        if (!native.available) return null
        val h = native.open(fd) ?: return null
        val info = native.info(h)
        if (info == null || info.size != 3 || info[0] < 0) {
            native.close(h)
            return null
        }
        handle = h
        fun meta(name: String) = native.meta(h, name)?.let { cleanLine(it, MAX_META) }?.ifEmpty { null }
        return WikiInfo(info[0], info[1] == 1L, info[2] == 1L, meta("Title"), meta("Language"), meta("Date"))
    }

    /** Title suggestions for [query], best first. */
    fun titles(query: String, max: Int = 5): List<WikiHit> = ask(query, max) { h, q, n -> native.titles(h, q, n) }

    /** Full-text matches for [query], best first. */
    fun search(query: String, max: Int = 5): List<WikiHit> = ask(query, max) { h, q, n -> native.search(h, q, n) }

    /** The article at [path] (a hit's), or null when there is none, it isn't a page or nothing is open. */
    fun article(path: String): WikiArticle? {
        val p = cleanPath(path) ?: return null
        if (handle == 0L) return null
        val r = native.article(handle, p, MAX_HTML_BYTES) ?: return null
        if (r.size != 3) return null
        val at = cleanPath(r[0]) ?: return null
        val html = r[2].trimEnd('�') // a cut through a character, at the byte limit
        if (html.isBlank() || html.encodeToByteArray().size > MAX_HTML_BYTES + 16) return null
        return WikiArticle(at, cleanLine(r[1], MAX_TITLE).ifEmpty { titleOf(at) }, html)
    }

    fun close() {
        if (handle != 0L) native.close(handle)
        handle = 0L
    }

    private fun ask(query: String, max: Int, call: (Long, String, Int) -> List<String>?): List<WikiHit> {
        val q = cleanLine(query, MAX_QUERY)
        if (q.isEmpty() || handle == 0L) return emptyList()
        val n = max.coerceIn(1, MAX_HITS)
        val raw = call(handle, q, n) ?: return emptyList()
        if (raw.size % 2 != 0) return emptyList()
        val out = LinkedHashMap<String, WikiHit>()
        for (i in raw.indices step 2) {
            val p = cleanPath(raw[i]) ?: continue
            if (p in out) continue
            out[p] = WikiHit(p, cleanLine(raw[i + 1], MAX_TITLE).ifEmpty { titleOf(p) })
            if (out.size == n) break
        }
        return out.values.toList()
    }

    private fun cleanPath(p: String): String? =
        p.takeIf { it.isNotBlank() && it.length <= MAX_PATH && !CONTROL.containsMatchIn(it) && it.none { c -> c == '\n' || c == '\t' } }

    private fun titleOf(path: String) = cleanLine(path.substringAfterLast('/').replace('_', ' '), MAX_TITLE)
}
