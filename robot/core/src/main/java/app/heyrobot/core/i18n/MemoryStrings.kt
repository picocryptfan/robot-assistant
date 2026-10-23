// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP3's catalog: memory: memory/*: what Robot answers a memory command (MemoryRouting.kt), the lock
// screen's and the settings' words (MemorySettings.kt), what History keeps instead of a secret, an item's source and
// dates as Settings › Memory shows them, and the secret kinds. Ids start with "memory."; the area is "memory". Québec
// French (tu), the glossary's words (robot/l10n/glossary.tsv); render() adds the French spaces.

object MemoryStrings : Strings("memory", area = "memory") {
    // MemoryReplies: what Robot says after "remember that …" (the command's language; a secret is never read back).
    val gotIt = s("got_it", "Got it: {you}.", "C'est noté : {you}.")
    val gotItTonight = s("got_it_tonight", "Got it: {you} until tonight.", "C'est noté : {you}, jusqu'à ce soir.")
    val gotItTomorrow = s("got_it_tomorrow", "Got it: {you} until tomorrow.", "C'est noté : {you}, jusqu'à demain.")
    val gotItWeek = s("got_it_week", "Got it: {you} until the end of the week.", "C'est noté : {you}, jusqu'à la fin de la semaine.")
    val gotItQuoted = s("got_it_quoted", "Got it: “{text}”.", "C'est noté : « {text} ».")
    val gotItSecret = s("got_it_secret", "Got it: {kind}, kept in Memory.", "C'est noté : {kind}, gardé en mémoire.")
    val callYou = s("call_you", "Got it: I'll call you {name}.", "C'est noté : je vais t'appeler {name}.")
    val pinFull = s("pin_full", "Six things are pinned already, so this one isn't used in every answer.",
        "Six choses sont déjà épinglées : celle-ci ne l'est pas.")
    val already = s("already", "I already remember that.", "Je m'en souviens déjà.")
    val full = s("full", "My memory is full. Delete some things in {page} first.", "Ma mémoire est pleine. Efface des choses dans {page} d'abord.")
    val nothingToRemember = s("nothing_to_remember", "There was nothing to remember.", "Il n'y avait rien à retenir.")
    val forgotten = s("forgotten", "Forgotten.", "C'est oublié.")
    val forgottenN = plural("forgotten_n", "n",
        Plural(one = "Forgotten: {n} thing.", other = "Forgotten: {n} things."),
        Plural(one = "C'est oublié : {n} chose.", other = "C'est oublié : {n} choses."))
    val nothingToForget = s("nothing_to_forget", "There's nothing from this conversation to forget.", "Il n'y a rien de cette conversation à oublier.")
    val notFound = s("not_found", "I don't remember anything about that.", "Je ne me souviens de rien à ce sujet.")
    val which = s("which", "Which one should I forget?", "Laquelle dois-je oublier ?")

    // "What do you remember about Julie?", "What do you know about me?": an item quoted, the list, the private ones left out.
    val quoted = s("quoted", "“{text}”", "« {text} »")
    val recall = s("recall", "Here's what I remember about {about}: {list}.", "Voici ce que je retiens sur {about} : {list}.")
    val recallNone = s("recall_none", "I don't remember anything about {about}.", "Je ne me souviens de rien sur {about}.")
    val privateMore = plural("private_more", "n",
        Plural(one = "And {n} private item, in {page}.", other = "And {n} private items, in {page}."),
        Plural(one = "Et {n} chose privée, dans {page}.", other = "Et {n} choses privées, dans {page}."))
    val aboutMeNone = s("about_me_none", "I don't remember anything yet. Say “remember that …” to keep something.",
        "Je ne retiens rien pour l'instant. Dis « souviens-toi que … » pour garder quelque chose.")
    val aboutMeCount = plural("about_me_count", "n",
        Plural(one = "I remember {n} thing.", other = "I remember {n} things."),
        Plural(one = "Je retiens {n} chose.", other = "Je retiens {n} choses."))
    val aboutMePrivate = s("about_me_private", "It's private: open {page}.", "Elle est privée : ouvre {page}.")
    val aboutMeExamples = s("about_me_examples", "For example: {list}.", "Par exemple : {list}.")

    // Memory off, locked, or its file unusable.
    val off = s("off", "Memory is off. Turn it on in Robot's settings, under Memory.",
        "La mémoire est désactivée. Active-la dans les réglages de Robot, sous Mémoire.")
    val unlockFirst = s("unlock_first", "Unlock first: what you asked me to remember stays locked.",
        "Déverrouille d'abord : ce que tu m'as demandé de retenir reste verrouillé.")
    val unlockToSave = s("unlock_to_save", "Unlock first to save to memory.", "Déverrouille d'abord pour l'enregistrer en mémoire.")
    val unlockToChange = s("unlock_to_change", "Unlock first to change what I remember.", "Déverrouille d'abord pour changer ce que je retiens.")
    val forgetAllByVoice = s("forget_all_by_voice", "Open {page} to delete everything.", "Ouvre {page} pour tout effacer.")
    val keyGone = s("key_gone", "I can't open what you asked me to remember: its key is gone. {page} says more.",
        "Je ne peux pas ouvrir ce que tu m'as demandé de retenir : sa clé n'existe plus. {page} en dit plus.")
    val unreadable = s("unreadable", "I can't read my memory file. {page} says more.", "Je n'arrive pas à lire ma mémoire. {page} en dit plus.")
    val tooNew = s("too_new", "My memory was saved by a newer Robot, so I can't change it.",
        "Ma mémoire vient d'une version plus récente de Robot : je ne peux pas la changer.")
    val notNow = s("not_now", "I couldn't open my memory just now. Try again in a moment.", "Je n'ai pas pu ouvrir ma mémoire. Réessaie dans un instant.")

    // The cards of an item saved or forgotten by voice (History keeps their summaries: never an item's words).
    val cardSaved = s("card_saved", "Remembered", "Retenu")
    val cardSavedPinned = s("card_saved_pinned", "Remembered, used in every answer", "Retenu, utilisé dans chaque réponse")
    val cardForgotten = s("card_forgotten", "Forgotten", "Oublié")
    val cardWhich = s("card_which", "Forget which one?", "Oublier laquelle ?")
    val cardSavedDetail = s("card_saved_detail", "{undo}, or change it in {page}.", "Touche {undo}, ou modifie-le dans {page}.")
    val cardForgottenDetail = s("card_forgotten_detail", "{undo} puts it back.", "{undo} le remet en place.")

    // What History keeps instead of a secret (MemoryHygiene), and what Robot says in its place.
    val secretPlaceholder = s("secret_placeholder", "[{kind}, kept in Memory]", "[{kind}, gardé en mémoire]")
    val rememberSecret = s("remember_secret", "Remember: {what}", "Retiens : {what}")
    val notSaved = s("not_saved", "Remember: [not saved, not kept in History]", "Retiens : [pas enregistré, pas gardé dans l'historique]")
    val privateQuestion = s("private_question", "[Something private about memory: not kept in History.]",
        "[Quelque chose de privé sur la mémoire : pas gardé dans l'historique.]")
    val secretAnswer = s("secret_answer", "[An answer with something private from Memory: not kept in History.]",
        "[Une réponse avec quelque chose de privé de la mémoire : pas gardée dans l'historique.]")
    val secretPassword = s("secret_password", "a password", "un mot de passe")
    val secretPin = s("secret_pin", "a PIN", "un NIP")
    val secretCode = s("secret_code", "a code", "un code")
    val secretCard = s("secret_card", "a card number", "un numéro de carte")
    val secretId = s("secret_id", "an ID number", "un numéro d'identité")
    val secretAccount = s("secret_account", "an account number", "un numéro de compte")
    val secretKey = s("secret_key", "a key", "une clé")
    val secretLongNumber = s("secret_long_number", "a long number", "un long numéro")

    // Settings › Memory's list (MemoryDates): "Pinned · Private · saved Oct 9 · until tonight · used in 3 answers".
    val datePinned = s("date_pinned", "Pinned", "Épinglé")
    val datePrivate = s("date_private", "Private", "Privé")
    val dateSavedFirst = s("date_saved_first", "Saved {day}", "Enregistré le {day}")
    val dateSaved = s("date_saved", "saved {day}", "enregistré le {day}")
    val dateEnded = s("date_ended", "ended", "terminé")
    val dateUntil = s("date_until", "until {day}", "jusqu'au {day}")
    val dateUsed = plural("date_used", "n",
        Plural(one = "used in {n} answer", other = "used in {n} answers"),
        Plural(one = "utilisé dans {n} réponse", other = "utilisé dans {n} réponses"))
    val dateDay = s("date_day", "{month} {day}", "{day} {month}")

    // Where an item came from (MemorySource), and the settings' choices (core MemorySettings).
    val sourceSaid = s("source_said", "You said it", "Tu l'as dit")
    val sourceTyped = s("source_typed", "You typed it", "Tu l'as tapé")
    val sourceSuggested = s("source_suggested", "A suggestion you kept", "Une suggestion que tu as gardée")
    val sourceSettings = s("source_settings", "Added in Settings", "Ajouté dans les réglages")
    val sourceContent = s("source_content", "From something Robot read", "De quelque chose que Robot a lu")
    val helpersIncluded = s("helpers_included", "Only when I include it", "Seulement quand je l'inclus")
    val helpersTrusted = s("helpers_trusted", "With helpers I trust", "Avec les IA externes en qui j'ai confiance")
    val suggestQuiet = s("suggest_quiet", "A list in Settings (quiet)", "Une liste dans les réglages (discrète)")
    val suggestEndChip = s("suggest_end_chip", "Ask me at the end of a conversation", "Me demander à la fin d'une conversation")
    val suggestOff = s("suggest_off", "Off", "Désactivées")
    // Settings' Memory row (the app's MemorySettings.kt, memorySummaryText).
    val summaryOff = s("summary_off", "Off", "Désactivée")
    val summaryOn = s("summary_on", "On", "Activée")
    val summaryLockScreen = s("summary_lock_screen", "On · also on the lock screen", "Activée · aussi sur l'écran de verrouillage")
    val summaryNotInAnswers = s("summary_not_in_answers", "On · not used in answers", "Activée · pas utilisée dans les réponses")
    val searchModelTitle = s("search_model_title", "Download the search model (165 MB, Google's EmbeddingGemma 2, Apache-2.0)",
        "Télécharger le modèle de recherche (165 Mo, EmbeddingGemma 2 de Google, Apache-2.0)")
}
