// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

import app.heyrobot.core.read.ReadRouter

/** What you can say while the steps are open, with no wake word. */
enum class StepWord { NEXT, BACK, AGAIN, STOP, YES, NO }

// Step by step's words, English and French, matched whole after ReadRouter.fold (lower case, no accents, "hey robot",
// "please" and « s'il te plaît » dropped): a sentence that only contains one of them is not it.
object StepWords {
    private val WORDS: Map<String, StepWord> = buildMap {
        fun put(w: StepWord, vararg s: String) = s.forEach { put(it, w) }
        put(StepWord.NEXT, "next", "next step", "next one", "ok next", "okay next", "go on", // l10n: not text (words Robot hears)
            "suivant", "suivante", "etape suivante", "la suivante", "prochaine etape", "ok suivant") // l10n: not text (words Robot hears)
        put(StepWord.BACK, "back", "go back", "previous", "previous step", "back one", "last step", // l10n: not text (words Robot hears)
            "retour", "precedent", "precedente", "etape precedente", "reviens", "recule", "reviens en arriere") // l10n: not text (words Robot hears)
        put(StepWord.AGAIN, "again", "repeat", "repeat that", "say that again", "say again", "one more time", "what was that", // l10n: not text (words Robot hears)
            "encore", "repete", "repete ca", "encore une fois", "redis le", "redis ca") // l10n: not text (words Robot hears)
        put(StepWord.STOP, "stop", "stop it", "stop the steps", "that s it", "i m done", "we re done", // l10n: not text (words Robot hears)
            "arrete", "arrete ca", "arrete les etapes", "c est fini", "termine", "j ai fini") // l10n: not text (words Robot hears)
        // Only a yes sets the offered timer: "ok", "sure" or "yeah" from anyone in the room doesn't.
        put(StepWord.YES, "yes", "yes a timer", "yes set it", "yes thanks", "yes thank you", // l10n: not text (words Robot hears)
            "oui", "oui merci", "oui une minuterie") // l10n: not text (words Robot hears)
        put(StepWord.NO, "no", "nope", "no thanks", "no thank you", "not now", // l10n: not text (words Robot hears)
            "non", "non merci", "pas maintenant", "pas besoin") // l10n: not text (words Robot hears)
    }

    fun of(text: String): StepWord? = WORDS[ReadRouter.fold(text)]
}
