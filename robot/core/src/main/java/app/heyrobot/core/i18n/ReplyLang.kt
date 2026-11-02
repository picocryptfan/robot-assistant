// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.LangGuess

// Pure. The language Robot answers in, decided once per turn, in place of the five ways each feature
// decides it today (the messages language, the brief's setting, HandsFreeHub's phone language, SpeechLang's word count, a
// router's own guess). Each area package moves its flows onto it; WP0 writes it and its tests only.

/** Settings › Language › "Robot answers in" (settings.json "replyLang"; absent: [SPOKEN]). */
enum class ReplyLangChoice(val id: String) {
    /** The language you speak (the default): what you said, when it is sure. */
    SPOKEN("spoken"),

    /** The menus' language (Robot's app language). */
    MENUS("menus"),
    EN("en"),
    FR("fr");

    companion object {
        /** A saved id; unknown or absent: [SPOKEN]. */
        fun of(id: String?): ReplyLangChoice = entries.firstOrNull { it.id == id } ?: SPOKEN
    }
}

object ReplyLang {
    /** How long a turn's language carries over to a short line that isn't sure ("OK", "5 minutes", a name). EST. */
    const val KEEP_MS = 10 * 60 * 1000L

    /** The previous turn's reply language in this panel session, and when ([atMs], wall clock). */
    data class Last(val lang: Lang, val atMs: Long)

    /**
     * The reply language of a turn. In order:
     * 1. a fixed [setting] wins ([ReplyLangChoice.MENUS] is the app language [ui]);
     * 2. what you said, when it is [sure] (LangId was sure, or a router matched one of its French or English phrases);
     * 3. not sure: the [previous] turn's language when it was less than [KEEP_MS] before [nowMs]; otherwise [ui].
     * Typed questions follow the same rule; a routine or the brief, with nothing heard, passes [heard] null.
     */
    fun decide(heard: Lang?, sure: Boolean, previous: Last?, nowMs: Long, ui: Lang, setting: ReplyLangChoice): Lang = when (setting) {
        ReplyLangChoice.EN -> Lang.EN
        ReplyLangChoice.FR -> Lang.FR
        ReplyLangChoice.MENUS -> ui
        ReplyLangChoice.SPOKEN -> when {
            heard != null && sure -> heard
            previous != null && kept(previous, nowMs) -> previous.lang
            else -> ui
        }
    }

    /**
     * [decide] from LangId's guess for what was heard and a router's own match ([router], which is sure). A line of one or
     * two words ("OK", "Marie-Ève", "5 minutes": [LangGuess.short]) isn't sure, even when LangId is: it keeps the previous
     * turn's language, and has its own only when there is none to keep.
     */
    fun decide(guess: LangGuess?, router: Lang?, previous: Last?, nowMs: Long, ui: Lang, setting: ReplyLangChoice): Lang {
        if (setting != ReplyLangChoice.SPOKEN || router != null) return decide(router, router != null, previous, nowMs, ui, setting)
        val heard = guess?.lang?.let { Lang.of(it) }
        if (heard != null && guess.short && !kept(previous, nowMs)) return heard
        return decide(heard, heard != null && !guess.short, previous, nowMs, ui, setting)
    }

    private fun kept(previous: Last?, nowMs: Long) = previous != null && nowMs - previous.atMs in 0 until KEEP_MS

    /**
     * The language the model is told to answer in (l10n design §4.8, Prompt.answerInLine): the turn's reply language [say]
     * when the [setting] fixes it (English, Français, or the menus' language). With "The language you speak", English after
     * English adds nothing and its prompt stays as it was; 1 Nov (the spoken run: « Combien d'advil… » answered in English, and
     * English stuck in French's wake), a French turn, or the first English one after it ([before]), is told too.
     */
    fun answerIn(setting: ReplyLangChoice, say: Lang, before: Lang? = null): Lang? =
        if (setting != ReplyLangChoice.SPOKEN || say == Lang.FR || before == Lang.FR) say else null

    private val COMMAND_EN = setOf("text", "call", "email", "open", "set", "play", "remind", "add", "send", "turn") // l10n: not text (heard words)
    private val COMMAND_FR = setOf("texte", "appelle", "ouvre", "envoie", "ecris", "ajoute", "mets", "rappelle", "joue", "allume", "eteins") // l10n: not text (heard words)

    /**
     * 1 Nov (the spoken run: "Text mom." right after a French turn was answered in French): a line of up to three words that starts
     * with a verb of one language only says which it's in, where LangId's guess on so few words isn't sure. Null: it doesn't.
     */
    fun commandLang(text: String): Lang? {
        val words = java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^\\p{L}0-9'-]+")).filter { it.isNotEmpty() }
        val first = words.firstOrNull()?.takeIf { words.size <= 3 } ?: return null
        return when (first) {
            in COMMAND_EN -> Lang.EN
            in COMMAND_FR -> Lang.FR
            else -> null
        }
    }

    /** With nothing heard (a routine, the brief): the setting's language, else the app language. */
    fun unheard(ui: Lang, setting: ReplyLangChoice): Lang = decide(null, false, null, 0, ui, setting)
}
