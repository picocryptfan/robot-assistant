// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.web.WebWords

/**
 * A copy of Wikipedia in memory, searched as a ZIM's indexes are: titles whose words start with each of the query's
 * (shortest first, as suggestions come), and full text by how many of the query's words a page holds.
 */
internal class FakeCopy(override val lang: String, override val date: String? = "2026-09-14", pages: Map<String, String>) : WikiCopy {
    private val pages = pages.map { (title, text) -> WikiCopy.Page("A/" + title.replace(' ', '_'), title, html(title, text)) }
    var asked = 0

    override fun titles(query: String, max: Int): List<WikiCopy.Hit> {
        asked++
        val q = WebWords.words(query)
        if (q.isEmpty()) return emptyList()
        return pages.filter { p -> val t = WebWords.words(p.title); q.all { w -> t.any { it.startsWith(w) } } }
            .sortedBy { it.title.length }.take(max).map { WikiCopy.Hit(it.path, it.title) }
    }

    override fun search(query: String, max: Int): List<WikiCopy.Hit> {
        asked++
        val q = WebWords.words(query).filter { it.length > 2 }.toSet()
        return pages.map { p -> p to WebWords.words(p.title + " " + p.html).count { it in q } }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(max).map { WikiCopy.Hit(it.first.path, it.first.title) }
    }

    override fun article(path: String): WikiCopy.Page? = pages.firstOrNull { it.path == path }

    companion object {
        fun html(title: String, text: String) = "<html><head><title>$title</title></head><body><h1>$title</h1>" +
            text.split("\n\n").joinToString("") { "<p>$it</p>" } + "</body></html>"
    }
}
