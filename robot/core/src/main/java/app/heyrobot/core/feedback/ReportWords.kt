// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

// Pure (1.4, report-a-problem-plan.md). The commonest words of English and French, folded as ReportSort.fold folds them.
// In what you said, a contact's one-word name or a part that is one of these (Mon amour, Grand-maman, Lucie Côté, Tom
// Day, Max) hides only where it stands as the name, so "what day is it" and "appelle mon frère" keep their words.
internal object ReportWords {
    val ORDINARY: Set<String> = listOf(
        "the be to of and a an in that have i it for not on with he as you do at this but his by from they we say her she", // l10n: not text (matched words, folded)
        "or will my one all would there their what so up out if about who get which go me when make can like time no just", // l10n: not text (matched words, folded)
        "him know take people into year your good some could them see other than then now look only come its over think", // l10n: not text (matched words, folded)
        "also back after use two how our work first well way even new want because any these give day most us is are was", // l10n: not text (matched words, folded)
        "were been has had did said says am call phone ring dial text message email send tell ask set turn play open close", // l10n: not text (matched words, folded)
        "stop start show find read check remind remember need help put let keep try leave move pay talk listen hear watch", // l10n: not text (matched words, folded)
        "wait meet bring buy eat drink sleep run walk drive alarm timer minute minutes hour hours second seconds today", // l10n: not text (matched words, folded)
        "tomorrow yesterday tonight morning afternoon evening night week weekend month weather music song songs radio", // l10n: not text (matched words, folded)
        "podcast volume max min loud louder quiet quieter low lower high higher down off yes yeah okay ok please thanks", // l10n: not text (matched words, folded)
        "thank sorry hey hello hi bye home house office school store shop car bus train plane road street city town place", // l10n: not text (matched words, folded)
        "room kitchen bed door mom mum dad mother father brother sister son daughter wife husband baby kids kid family", // l10n: not text (matched words, folded)
        "friend friends boss doctor honey love dear sweetheart darling big little small great grand long short old young", // l10n: not text (matched words, folded)
        "right left next last best better more less much many very really still here where why again away never always", // l10n: not text (matched words, folded)
        "sometimes soon late early white black red green blue brown gray grey gold light dark north south east west summer", // l10n: not text (matched words, folded)
        "winter spring fall rain snow wind sun moon star sky date number name word note notes list news photo picture", // l10n: not text (matched words, folded)
        "camera map battery flashlight screen settings app robot thing things something nothing everything everyone someone", // l10n: not text (matched words, folded)
        "le la les l un une des du de d et au aux en dans sur sous avec pour par pas ne n plus moins tres bien mal oui non", // l10n: not text (matched words, French, folded)
        "merci bonjour bonsoir salut allo je j tu il elle nous vous ils elles m te t se s moi toi lui leur leurs mon ton son", // l10n: not text (matched words, French, folded)
        "ma ta sa mes tes ses notre votre nos vos ce c cet cette ces qui que qu quoi quel quelle ou quand comment pourquoi", // l10n: not text (matched words, French, folded)
        "combien est sont etre ai as avons avez ont avoir fait faire dit dire va vais aller vient venir peux peut veux veut", // l10n: not text (matched words, French, folded)
        "dois doit appelle appeler ecris ecrire envoie envoyer texte mets mettre ouvre ouvrir ferme fermer arrete arreter", // l10n: not text (matched words, French, folded)
        "joue jouer montre montrer dis cherche trouve rappelle alarme minuteur heure heures seconde secondes jour jours", // l10n: not text (matched words, French, folded)
        "journee semaine mois annee aujourd hui demain hier soir soiree matin midi nuit temps meteo musique chanson fort", // l10n: not text (matched words, French, folded)
        "bas haut grande grands petit petite gros grosse bon bonne beau belle nouveau nouvelle vieux vieille jeune premier", // l10n: not text (matched words, French, folded)
        "premiere dernier derniere prochain prochaine cote maison bureau travail ecole magasin auto voiture char autobus", // l10n: not text (matched words, French, folded)
        "route rue ville chambre cuisine porte lit mere pere maman papa frere soeur fils fille femme mari bebe enfant enfants", // l10n: not text (matched words, French, folded)
        "famille cheri cherie amour ami amie amis chum blonde patron docteur madame monsieur blanc blanche noir noire rouge", // l10n: not text (matched words, French, folded)
        "vert verte bleu bleue jaune gris nord sud ouest printemps ete automne hiver pluie neige vent soleil lune etoile ciel", // l10n: not text (matched words, French, folded)
        "tout tous toute toutes rien chose ici maintenant encore deja toujours jamais peu beaucoup trop assez avant apres", // l10n: not text (matched words, French, folded)
        "pendant depuis entre chez vers sans juste vrai droite gauche aussi alors donc mais car si", // l10n: not text (matched words, French, folded)
    ).flatMap { it.split(' ') }.toSet()

    /**
     * Nicknames that are everyday words (Bug, Coach, Tonton), so a contact called one hides only where it stands as the name.
     * None is also a given name (Mo, Nana, Coco): when a name can't be told from a word, hiding wins. Kept apart from
     * [ORDINARY], which the street rule reads too ("12 sugar creek road").
     */
    val NICKNAMES: Set<String> = listOf(
        "bug coach bro bros sis uncle aunt auntie granny grandma grandpa gramps pop pops babe", // l10n: not text (matched words, folded)
        "hon sweetie sugar pumpkin peanut chief champ sport pal dude bestie boo doc kiddo mama", // l10n: not text (matched words, folded)
        "tonton tata tantine mamie mami papi papy meme memere pepere frerot soeurette cousin cousine oncle tante", // l10n: not text (matched words, French, folded)
        "parrain marraine chouchou doudou minou loulou puce choupette fiston", // l10n: not text (matched words, French, folded)
    ).flatMap { it.split(' ') }.toSet()

    /** Words that never stand as a name after "call" or "appelle": "call my mom", "appelle mon frère". */
    val FUNCTION: Set<String> = ("the a an my your his her our their its this that these those me him us them it to of for with at le la " + // l10n: not text (matched words, folded)
        "les l un une des du de d mon ton son ma ta sa mes tes ses notre votre nos vos leur leurs ce cet cette ces moi toi lui nous vous au aux en") // l10n: not text (matched words, French, folded)
        .split(' ').toSet()

    /** In what you said, the word just before a name: "call max", "écris à grand". */
    val TO_NAME: Set<String> = ("call calling phone ring dial text texting message email appelle appeler telephone rappelle rappeler texte textes ecris ecrire " + // l10n: not text (matched words, folded)
        "envoie courriel").split(' ').toSet() // l10n: not text (matched words, French, folded)
    /** What may stand between [TO_NAME] and the name: "text to max", "écris à max". */
    val LINKS: Set<String> = setOf("to", "a", "au") // l10n: not text (matched words, folded)
}
