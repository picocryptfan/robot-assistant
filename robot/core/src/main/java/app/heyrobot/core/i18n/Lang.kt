// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import java.util.Locale

// Pure. 1.1's languages: every word Robot shows or says comes from a resource (the
// app's screens) or from core's catalog (what core says or labels), and three languages can be in play at once. A
// sentence is a [Phrase], rendered at the last moment in the [Langs] of the place it goes to: the reply language for
// what Robot says in a turn, the app language for Robot's screens, the phone's language for Android's own screens, and
// English for the model and the logs ([Phrase.en]).

/**
 * Robot's languages: English and Québec French (tu). [id] is the persisted form ("en", "fr": the brief's language in
 * routine_settings.json); the constant names are persisted too (messages.json's per-thread language). The brief, hands-free and messages
 * code all use this one type since 1.1.
 */
enum class Lang(val id: String) {
    EN("en"), FR("fr");

    companion object {
        /** A language tag ("fr-CA", "fr", " FR ", "en-US"); anything not French is English. Every fr-* is core's one French. */
        fun of(tag: String?): Lang = if (tag?.trim()?.lowercase(Locale.ROOT)?.startsWith("fr") == true) FR else EN

        /** A persisted [id], or null when it is none of them. */
        fun ofId(id: String?): Lang? = entries.firstOrNull { it.id == id }
    }
}

/** Where a rendered sentence goes: drawn on a screen, or spoken by Robot's voice ([VoiceForm]). */
enum class Medium { SCREEN, VOICE }

/**
 * The languages a [Phrase] renders in (§4.3, §7.2): [say] is the sentence's own language (the reply language in a turn,
 * the app language on a screen); [ui] is Robot's screens' (a Robot page named inside a sentence: "Settings › Memory");
 * [system] is the phone's (an Android screen named inside a sentence). [medium]: the screen or the voice. [pseudo]: the
 * app runs in a pseudo-locale (debug builds), so catalog words are marked the way aapt2 marks resources.
 */
data class Langs(val say: Lang, val ui: Lang, val system: Lang, val medium: Medium = Medium.SCREEN, val pseudo: Pseudo? = null) {
    /** The same languages, for the voice. */
    val voice: Langs get() = copy(medium = Medium.VOICE)

    companion object {
        /** English everywhere: what core's tests render by default, and what [Phrase.en] gives the model and the logs. */
        val EN = Langs(Lang.EN, Lang.EN, Lang.EN)

        /** [l] for the sentence, Robot's screens and Android's. */
        fun of(l: Lang): Langs = Langs(l, l, l)
    }
}
