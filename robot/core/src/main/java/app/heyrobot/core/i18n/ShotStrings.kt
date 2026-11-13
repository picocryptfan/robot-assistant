// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Ask about your screenshots' catalog (docs/plans/screenshots-plan.md; core shots/*): what Robot says with an answer
// from your screenshots. Ids start with "shots."; every entry has its French. The settings page has its own resources
// (strings_shots.xml); the model's prompt lines (FilePrompt.SHOTS, SHOT_READ) are not text.

object ShotStrings : Strings("shots", area = "shots") {
    /** Asked while the phone is locked: the index stays sealed, nothing is shown or spoken. */
    val unlockFirst = s("unlock_first", "Unlock your phone first, then ask me about your screenshots again.",
        "Déverrouille ton téléphone d'abord, puis redemande-moi à propos de tes captures d'écran.")
    /** Unlocked, nothing found, and part of the index doesn't open: says so, never "unlock first". */
    val unreadable = s("unreadable", "Part of what I read from your screenshots won't open. To start fresh, delete the index in Settings.",
        "Une partie de ce que j'ai lu dans tes captures d'écran ne s'ouvre pas. Pour repartir à neuf, supprime l'index dans Réglages.")
    /** Asked while the feature is off. */
    val off = s("off", "Ask about your screenshots is off. You can switch it on in Settings.",
        "Questions sur tes captures d'écran est désactivé. Tu peux l'activer dans les paramètres.")
    /** Nothing in the index matched. */
    val nothing = s("nothing", "I didn't find that in the screenshots I've read.",
        "Je n'ai pas trouvé ça dans les captures d'écran que j'ai lues.")
    /** Nothing matched, and some screenshots were never read since they look like they hold a secret (fix-screenshots-d). */
    val skipped = plural("skipped", "n",
        Plural("I didn't find that in the screenshots I've read. I didn't read {n} more: it looks like it holds a password, code or card.",
            "I didn't find that in the screenshots I've read. I didn't read {n} more: they look like they hold a password, code or card."),
        Plural("Je n'ai pas trouvé ça dans les captures d'écran que j'ai lues. Je n'ai pas lu {n} autre capture : elle semble contenir un mot de passe, un code ou une carte.",
            "Je n'ai pas trouvé ça dans les captures d'écran que j'ai lues. Je n'ai pas lu {n} autres captures : elles semblent contenir un mot de passe, un code ou une carte.",
            "Je n'ai pas trouvé ça dans les captures d'écran que j'ai lues. Je n'ai pas lu {n} autres captures : elles semblent contenir un mot de passe, un code ou une carte."))
    /** What History and the bug card keep in place of an answer from your screenshots (question 4's default). */
    val kept = s("kept", "[An answer from your screenshots: not kept in History.]",
        "[Une réponse tirée de tes captures d'écran : pas gardée dans l'historique.]")
}
