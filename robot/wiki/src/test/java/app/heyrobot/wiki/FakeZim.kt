// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

/** A ZimNative without the library: a few articles, a title index by prefix and a full-text index by word. */
internal class FakeZim(
    override val available: Boolean = true,
    private val pages: Map<String, Pair<String, String>> = mapOf(
        "Louis_Riel" to ("Louis Riel" to "<p><b>Louis Riel</b> was a Canadian politician and a founder of Manitoba.</p>"),
        "Riel" to ("Louis Riel" to ""), // a redirect, followed
        "Manitoba" to ("Manitoba" to "<p><b>Manitoba</b> is a province of Canada; Louis Riel led its people.</p>"),
        "Montréal" to ("Montréal" to "<p><b>Montréal</b> est la plus grande ville du Québec.</p>"),
    ),
) : ZimNative {
    var opened = 0
    var closed = 0
    var lastQuery: String? = null
    var raw: List<String>? = null // when set, what titles and search return as is

    override fun open(fd: Int): Long? = if (fd < 0) null else { opened++; 42L }
    override fun info(handle: Long): LongArray? = longArrayOf(pages.size.toLong(), 1, 1)
    override fun meta(handle: Long, name: String): String? = mapOf("Title" to "Wikipedia\u0007 top", "Language" to "eng", "Date" to "2026-09-01")[name]

    override fun titles(handle: Long, query: String, max: Int): List<String>? {
        lastQuery = query
        raw?.let { return it }
        return pages.filter { it.value.first.lowercase().startsWith(query.lowercase()) && it.value.second.isNotEmpty() }
            .flatMap { listOf(it.key, it.value.first) }.take(max * 2)
    }

    override fun search(handle: Long, query: String, max: Int): List<String>? {
        lastQuery = query
        raw?.let { return it }
        val words = query.lowercase().split(' ')
        return pages.filter { p -> p.value.second.isNotEmpty() && words.all { p.value.second.lowercase().contains(it) } }
            .flatMap { listOf(it.key, it.value.first) }.take(max * 2)
    }

    override fun article(handle: Long, path: String, maxBytes: Int): List<String>? {
        val at = if (path == "Riel") "Louis_Riel" else path
        val p = pages[at] ?: return null
        return listOf(at, p.first, p.second.encodeToByteArray().take(maxBytes).toByteArray().decodeToString())
    }

    override fun close(handle: Long) { closed++ }
}
