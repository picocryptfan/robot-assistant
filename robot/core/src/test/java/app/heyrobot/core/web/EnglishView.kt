// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs

// Tests only (see core/EnglishView.kt): the web search's words in English, each the Phrase's `.en()`.

fun SearxngWire.testLine(answer: SearchAnswer, service: SearchService = SearchService.SEARXNG_HOME): String = testLineText(answer, service).en()
val AnswerCard.readLine: String? get() = readText?.en()
fun AnswerCard.savedLine(keep: KeepSources): String = savedText(keep).en()
fun WebFail.text(service: SearchService?): String = textPhrase(service).en()
val WebOutcome.Failed.text: String get() = sentence.en()
fun WebStatus.searching(lang: String?): String = searchingText.render(Langs.of(Lang.of(lang)))
fun WebStatus.reading(pages: Int, lang: String?, done: Int? = null): String = readingText(pages, done).render(Langs.of(Lang.of(lang)))
fun WebKeyFormat.where(service: SearchService): String = whereText(service)?.en() ?: ""
fun WebKeyFormat.problem(key: String): String? = problemText(key)?.en()
