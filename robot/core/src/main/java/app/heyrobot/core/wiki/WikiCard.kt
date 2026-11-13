// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.WikiStrings
import app.heyrobot.core.web.WikipediaWire

/**
 * Wikipedia P4b: the card under a reply from the copy on this phone, shown and never said or saved: [header] ("From
 * Wikipedia on this phone, May 2026"), each article read by its title with a link to it on Wikipedia, and the licence
 * with a link to it. Its words are the reply's language (the question's), English or French. Not AnswerCard: nothing
 * was sent anywhere. A link opens only on your tap, in the browser ([links]: the only ones PanelController.openSource takes).
 */
data class WikiCard(val header: String, val articles: List<Article>, val licence: String) {
    /** An article read: its [title] and its address on Wikipedia ([url], opened only on your tap). */
    data class Article(val title: String, val url: String)

    /** The addresses a tap on this card may open: its articles' and the licence's. */
    val links: List<String> get() = articles.map { it.url } + WikipediaWire.LICENCE_URL

    companion object {
        /** The card for what the copy [found], in [lang]. */
        fun of(found: WikiAnswer.Found, lang: String = found.lang): WikiCard {
            val langs = Langs.of(Lang.of(lang))
            val m = WikiAnswer.month(found.copy.date, lang)
            val header = (if (m == null) WikiStrings.card() else WikiStrings.cardMonth("month" to m)).render(langs)
            return WikiCard(header, found.articles.map { Article(it.title, WikipediaWire.articleUrl(found.copy.lang, it.title)) },
                WikiStrings.licence("licence" to WikipediaWire.LICENCE).render(langs))
        }
    }
}
