// SPDX-License-Identifier: GPL-3.0-only
// Vendored from Readability4J 1.0.8 (https://github.com/dankito/Readability4J, commit 170d052e), a Kotlin port of Mozilla's
// Readability.js. Apache-2.0: see NOTICE and LICENSE in this folder. Changed for Robot (0.9 web search): the package, every
// slf4j logging call removed (no logging library may see page text), built against jsoup 1.23.2.
package app.heyrobot.helpers.web.readability.model


open class ReadabilityOptions(val maxElemsToParse: Int = DEFAULT_MAX_ELEMS_TO_PARSE,
                              val nbTopCandidates: Int = DEFAULT_N_TOP_CANDIDATES,
                              val wordThreshold: Int = DEFAULT_WORD_THRESHOLD,
                              val additionalClassesToPreserve: Collection<String> = ArrayList()) {

    companion object {
        // Max number of nodes supported by this parser. Default: 0 (no limit)
        const val DEFAULT_MAX_ELEMS_TO_PARSE = 0

        // The number of top candidates to consider when analysing how
        // tight the competition is among candidates.
        const val DEFAULT_N_TOP_CANDIDATES = 5

        // The default number of words an article must have in order to return a result
        const val DEFAULT_WORD_THRESHOLD = 500
    }

}