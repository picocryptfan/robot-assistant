// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import java.text.Normalizer

// Pure. What you say to car mode about its two screens (docs/plans/car-screens-plan.md §1): "swap the bottom" and "put
// Spotify's widget on the bottom", in English and French. Heard where "navigate home" is (HandsFreeRouter), car mode only.

sealed class CarHalvesPhrase {
    /** "swap the bottom", « change l'écran du bas »: the widget or the media player, as the bottom swap button does. */
    data object SwapBottom : CarHalvesPhrase()

    /**
     * "put Spotify's widget on the bottom": the widget Robot already has for that app. [app]: the name as heard; empty for
     * "the widget" or "my widget", which mean the one Robot has.
     */
    data class WidgetOnBottom(val app: String) : CarHalvesPhrase() {
        /** Whether this names the widget Robot has, whose app is called [label]. */
        fun means(label: String): Boolean = app.isEmpty() || names(app, label) || elided(app)?.let { names(it, label) } == true
    }

    companion object {
        private val SWAP = setOf(
            "swap the bottom", "swap the bottom screen", "swap bottom screen", "switch the bottom", "switch the bottom screen",
            "change the bottom screen", "swap the bottom half",
            "change lecran du bas", "change le bas", "echange lecran du bas", "change lecran en bas", "inverse lecran du bas",
            "permute lecran du bas", "permute le bas", "permute lecran en bas",
        )
        private val EN = Regex("(?:put|show)(?: the| my)?(?: (.+?))? widget (?:on|at|in) the bottom(?: screen| half)?")
        private val FR = Regex("(?:mets|affiche) (?:le|mon) widget(?: de)?(?: (.+?))? (?:en bas|sur lecran du bas|dans lecran du bas)")
        /** Words that name no app: "the widget", "my widget". */
        private val ANY = setOf("the", "my", "le", "mon")

        /** From the router's words (lower case, no accents, apostrophes gone: "lecran", "spotifys"). */
        fun of(words: List<String>): CarHalvesPhrase? {
            val s = words.joinToString(" ")
            if (s in SWAP) return SwapBottom
            val app = (EN.matchEntire(s) ?: FR.matchEntire(s))?.groupValues?.get(1) ?: return null
            return WidgetOnBottom(app.takeUnless { it in ANY }.orEmpty())
        }

        /** French « le widget d’Apple Music » arrives as "dapple music": the name without its "d". */
        internal fun elided(heard: String): String? = heard.takeIf { it.length > 2 && it[0] == 'd' && it[1] in "aeiouyh" }?.drop(1)

        /** Whether [heard] names the app called [label]: "spotify", "spotifys" (Spotify's), "organic maps". */
        fun names(heard: String, label: String): Boolean {
            val l = plain(label)
            val h = plain(heard)
            return l.isNotEmpty() && (h == l || h == l + "s" || h == "$l s")
        }

        private fun plain(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("['’]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

/** What the car screen did with a [CarHalvesPhrase], for Robot's one-line answer. */
enum class CarBottomSaid { DONE, SET_UP, LOCKED, CLOSED, NOT_SHOWN }
