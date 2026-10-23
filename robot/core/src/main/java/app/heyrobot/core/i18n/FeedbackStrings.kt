// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP8's catalog: Send feedback's form: the kinds' buttons and prompts and the diagnostics you may tick
// (core feedback/Feedback.kt). The message itself, its header, the diagnostics' lines and the voice log's words stay
// English: the developer reads them. Ids start with "feedback."; the area is "updates". Québec French (tu),
// the glossary's words (robot/l10n/glossary.tsv); render() adds the French spaces. The page's own words are resources
// (values/strings_updates.xml).

object FeedbackStrings : Strings("feedback", area = "updates") {
    // FeedbackKind: the three buttons and the text field's label under them.
    val bug = s("bug", "Bug", "Bogue")
    val idea = s("idea", "Idea", "Idée")
    val other = s("other", "Other", "Autre")
    val bugPrompt = s("bug_prompt", "What happened?", "Qu'est-ce qui s'est passé ?")
    val ideaPrompt = s("idea_prompt", "What would you like Robot to do?", "Qu'aimerais-tu que Robot fasse ?")
    val otherPrompt = s("other_prompt", "What would you like to tell the developer?", "Qu'aimerais-tu dire au développeur ?")

    // Diagnostic: what Robot may add to your words, each a box you tick.
    val problem = s("problem", "Problem report", "Rapport de problème")
    val problemDetail = s("problem_detail", "The model problem's report: codes, this phone and its Android build, the model, how its process ended",
        "Le rapport du problème de modèle : codes, ce téléphone et sa version d'Android, le modèle, comment son processus s'est terminé")
    val voiceLog = s("voice_log", "Voice log", "Journal vocal")
    val voiceLogDetail = s("voice_log_detail", "The last {n} events, codes and numbers only", "Les {n} derniers événements, codes et nombres seulement")
    val settings = s("settings", "Settings summary", "Résumé des réglages")
    val settingsDetail = s("settings_detail", "Switches and levels only: no names, addresses, phrases, server addresses or keys",
        "Interrupteurs et niveaux seulement : aucun nom, aucune adresse, aucune phrase, aucune adresse de serveur ni aucune clé")
    val updater = s("updater", "Updater", "Mises à jour")
    val updaterDetail = s("updater_detail", "The updater's last check, error and install, as codes",
        "La dernière vérification, la dernière erreur et la dernière installation des mises à jour, en codes")
}
