// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebFail

// Tests only: the web search's words in English, each the Phrase's `.en()` (the wrappers l10n WP9 removed from the code the app runs).

fun WebFail.text(service: SearchService?): String = textPhrase(service).en()
fun SearchClient.test(): Pair<Boolean, String> = testText().let { (ok, line) -> ok to line.en() }
