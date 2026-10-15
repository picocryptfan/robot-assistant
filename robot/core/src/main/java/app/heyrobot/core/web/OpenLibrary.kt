// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

// Pure (org.json). Book lookups through Open Library (design A2.2, A5 "Book lookups", off by default): its search API,
// `GET https://openlibrary.org/search.json?q=…&limit=5&fields=key,title,author_name,first_publish_year`, keyless. Its
// rules as read through a search summary (PUBLISHED; the developers page didn't open): one request a second by default,
// three with a User-Agent naming the app and a contact email; Robot sends at most one per question. Each book becomes a
// SearchResult whose page is the work's ("https://openlibrary.org/works/OL…W") and whose snippet is "by … · first
// published …"; the card says "From Open Library".

object OpenLibraryWire {
    const val SEARCH = "https://openlibrary.org/search.json"
    const val SITE = "https://openlibrary.org"
    const val LIMIT = 5

    /** Words that make a question a book lookup (EN, FR, folded). */
    private val BOOK = Regex("\\b(?:books?|novels?|authors?|written by|wrote|bibliography|isbn|livres?|romans?|auteure?s?|ecrit par|a ecrit|bouquins?)\\b")

    /** The question asks about books ("books by Ursula K. Le Guin", "les romans de Gabrielle Roy"). */
    fun wanted(question: String): Boolean = BOOK.containsMatchIn(WebWords.fold(question))

    fun request(query: String, userAgent: String, limit: Int = LIMIT): SearchRequest =
        SearchRequest("GET", "$SEARCH?q=${SearchWire.encode(query)}&limit=${limit.coerceIn(1, 10)}&fields=key,title,author_name,first_publish_year",
            listOf("User-Agent" to userAgent, "Accept" to "application/json"))

    /** `docs[]` (key, title, author_name[], first_publish_year) as results; a work's key must look like "/works/OL…W". */
    fun parse(status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        if (status != 200) return SearchAnswer.Failed(if (status == 429) WebFail.BUSY else WebFail.SERVICE, SearchWire.retryAfter(retryAfter))
        val o = SearchWire.json(body) ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val docs = o.optJSONArray("docs") ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val results = SearchWire.objects(docs).mapNotNull { d ->
            val key = SearchWire.str(d, "key")?.takeIf { it.matches(Regex("/works/OL[0-9]{1,12}W")) } ?: return@mapNotNull null
            val authors = SearchWire.strings(d.optJSONArray("author_name")).take(3)
            val year = d.optInt("first_publish_year", 0).takeIf { it in 1..2999 }
            val snippet = listOfNotNull(authors.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "by $it" }, year?.let { "first published $it" })
                .joinToString(" · ")
            SearchWire.result(SITE + key, SearchWire.str(d, "title"), snippet, SearchProvider.OPEN_LIBRARY, year?.toString())
        }
        return SearchAnswer.Results(SearchWire.distinct(results).take(LIMIT))
    }

}
