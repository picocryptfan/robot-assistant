// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// 1.4 Lists (lists-plan.md §2.2, §3.6, §5, §8.4, §13): what Robot shows and says about your lists. Ids start with "lists.";
// the area is "lists". The French is Québec French (tu), from the glossary (robot/l10n/glossary.tsv: « liste »,
// « Épicerie », « À faire », « élément », « cocher »). LS2 brings the two default lists' names, which core ListRole compares
// in both languages (ListsStringsTest keeps them in step); LS3 the voice's replies. Settings › Lists' own rows are in
// values/strings_lists.xml.
//
// LS3's replies are built by code from these entries, never by the model. Code can't know an item's number or gender ("eggs",
// « œufs »), so no reply makes a verb or an adjective agree with an item: "Already on Shopping: eggs.", « Ajouté à la liste
// Épicerie : … ». {list} is a list's name; {things} your words as stored, joined ("eggs, bread and oat milk").

object ListsStrings : Strings("lists", area = "lists") {
    /** The shopping list's name until you rename it (it follows the app's language). */
    val shopping = s("shopping", "Shopping", "Épicerie")
    /** The to-do list's name until you rename it. */
    val todo = s("todo", "To-do", "À faire")
    /** "Lists", where a reply has no list's name to give (a list gone meanwhile). Settings › Lists in a path is UiStrings.lists (LS8). */
    val page = s("page", "Lists", "Listes")

    // An item said back: its words and its quantity.
    val itemQty = s("item_qty", "{thing} ({qty})", "{thing} ({qty})")

    // Adds (§3.6).
    val added = s("added", "Added {things} to {list}.", "Ajouté à la liste {list} : {things}.")
    val madeWith = s("made_with", "Made a new list, {list}, with {things}.", "Nouvelle liste {list}, avec {things}.")
    val already = s("already", "Already on {list}: {things}.", "Déjà sur la liste {list} : {things}.")
    val addedToo = s("added_too", "Added {things}.", "Ajouté : {things}.")
    val putBack = s("put_back", "Put {things} back on {list}.", "Remis sur la liste {list} : {things}.")
    val qtyChanged = s("qty_changed", "Changed the quantity on {list}: {things}.", "Quantité changée sur la liste {list} : {things}.")
    /** While locked (§4.6 step 3): the list in your own words ("shopping list"), and nothing more: Robot can't see what's on it. */
    val addedLocked = s("added_locked", "Added {things} to your {list}.", "Ajouté à ta {list} : {things}.")
    /** A list you named without the word "list" ("Costco"), said back as one. */
    val namedList = s("named_list", "{name} list", "liste {name}")

    // Reads and checks.
    val read = plural("read", "n", Plural("{list} has {n} thing: {things}.", "{list} has {n} things: {things}."),
        Plural("La liste {list} a {n} élément : {things}.", "La liste {list} a {n} éléments : {things}.", "La liste {list} a {n} d'éléments : {things}."))
    val more = plural("more", "n", Plural("{n} more", "{n} more"), Plural("{n} autre", "{n} autres", "{n} d'autres"))
    val empty = s("empty", "{list} is empty.", "La liste {list} est vide.")
    val allTicked = s("all_ticked", "Nothing left on {list}: everything is ticked off.", "Plus rien sur la liste {list} : tout est coché.")
    val yes = s("yes", "Yes, {list} has {thing}.", "Oui, c'est sur la liste {list} : {thing}.")
    val yesLists = s("yes_lists", "Yes, {lists} have {thing}.", "Oui, c'est sur les listes {lists} : {thing}.")
    /** LS12: a yes on several lists that found different items names each ("Yes: milk on Shopping and oat milk on Pharmacy."). */
    val yesEach = s("yes_each", "Yes: {things}.", "Oui : {things}.")
    val onList = s("on_list", "{thing} on {list}", "{thing} sur la liste {list}")
    /** lp-fixes: a yes as well, as [yes] (« j'ai-tu des œufs » answered « C'est coché … » with no « Oui »). */
    val yesTicked = s("yes_ticked", "Yes, {list} has {thing}, ticked off.", "Oui, c'est coché sur la liste {list} : {thing}.")
    val no = s("no", "No, {list} doesn't have {thing}.", "Non, ce n'est pas sur la liste {list} : {thing}.")
    val noneHas = s("none_has", "No, none of your lists has {thing}.", "Non, ce n'est sur aucune de tes listes : {thing}.")
    val myLists = plural("my_lists", "n", Plural("You have {n} list: {lists}.", "You have {n} lists: {lists}."),
        Plural("Tu as {n} liste : {lists}.", "Tu as {n} listes : {lists}.", "Tu as {n} de listes : {lists}."))
    val noLists = s("no_lists", "You don't have any lists yet.", "Tu n'as pas encore de listes.")
    /** History keeps this in place of a list read aloud (D17): its items stay off History's plain files. */
    val readKept = plural("read_kept", "n", Plural("Robot read {list}: {n} item", "Robot read {list}: {n} items"),
        Plural("Robot a lu la liste {list} : {n} élément", "Robot a lu la liste {list} : {n} éléments", "Robot a lu la liste {list} : {n} d'éléments"))
    /**
     * LS10 (M1): History keeps this in place of any other answer from your lists (a check, "what lists do I have", "Oat milk or
     * almond milk?", a tick or a put back that names an item you didn't say): list content, as a read is.
     */
    val answered = s("answered", "Robot answered from your lists", "Robot a répondu à partir de tes listes")

    // Ticks and put backs.
    val ticked = s("ticked", "Ticked off {things}.", "Coché : {things}.")
    val alreadyTicked = s("already_ticked", "Already ticked off: {things}.", "Déjà coché : {things}.")
    val alreadyOpen = s("already_open", "Already on {list}, not ticked: {things}.", "Déjà sur la liste {list}, pas coché : {things}.")
    val notOn = s("not_on", "Not on {list}: {things}.", "Pas sur la liste {list} : {things}.")
    val notOnAny = s("not_on_any", "Not on any of your lists: {things}.", "Sur aucune de tes listes : {things}.")
    /** Two items or two lists match: "Oat milk or milk?", "Shopping or Camping?". */
    val which = s("which", "{options}?", "{options}?")

    // Lists.
    val made = s("made", "Made a new list, {list}.", "Nouvelle liste créée : {list}.")
    val exists = s("exists", "You already have a list called {list}.", "Tu as déjà une liste appelée {list}.")
    val peerList = s("peer_list", "{name} came from another person's Robot, so you can only read or change it on its page in Lists.", "{name} vient d’une autre personne : tu peux seulement la lire ou la changer sur sa page dans Listes.")
    val noList = s("no_list", "You don't have a list called {name}.", "Tu n'as pas de liste appelée {name}.")
    val cleared = plural("cleared", "n", Plural("Cleared {n} ticked item from {list}.", "Cleared {n} ticked items from {list}."),
        Plural("{n} élément coché effacé de la liste {list}.", "{n} éléments cochés effacés de la liste {list}.", "{n} d'éléments cochés effacés de la liste {list}."))
    val stillOn = plural("still_on", "n", Plural("{n} is still on it.", "{n} are still on it."), Plural("Il en reste {n}.", "Il en reste {n}.", "Il en reste {n}."))
    val nothingTicked = s("nothing_ticked", "Nothing is ticked off on {list}.", "Rien n'est coché sur la liste {list}.")
    val emptyAsk = s("empty_ask", "Tap Empty to empty {list}.", "Touche Vider pour vider la liste {list}.")
    val emptyCard = plural("empty_card", "n", Plural("Empty {list}? {n} item will go.", "Empty {list}? {n} items will go."),
        Plural("Vider la liste {list}? {n} élément partira.", "Vider la liste {list}? {n} éléments partiront.", "Vider la liste {list}? {n} d'éléments partiront."))
    val emptyButton = s("empty_button", "Empty", "Vider")
    val keepButton = s("keep_button", "Keep", "Garder")
    val emptied = s("emptied", "Emptied {list}.", "La liste {list} est vidée.")
    val kept = s("kept", "Kept {list} as it was.", "La liste {list} reste comme elle était.")
    val alreadyEmpty = s("already_empty", "{list} is already empty.", "La liste {list} est déjà vide.")

    // Undo.
    val undone = s("undone", "Undone.", "Annulé.")
    val notUndone = s("not_undone", "Robot couldn't undo that: the list changed since.", "Robot n'a pas pu annuler ça : la liste a changé depuis.")
    val unlockToUndo = s("unlock_to_undo", "Unlock your phone to undo that.", "Déverrouille ton téléphone pour annuler ça.")

    // Refusals (§2.4, §8.4).
    val secret = s("secret", "That looks like a code or a card number. Robot keeps those in Memory, not on a list.",
        "Ça ressemble à un code ou à un numéro de carte. Robot garde ça dans la Mémoire, pas sur une liste.")
    val tooMany = s("too_many", "That's more than {max} things at once. Add them in smaller groups.", "Ça fait plus de {max} choses d'un coup. Ajoute-les en plus petits groupes.")
    val tooLong = s("too_long", "That's too long for a list: {max} characters at most for an item, {name} for a name.",
        "C'est trop long pour une liste : {max} caractères au plus pour un élément, {name} pour un nom.")
    val listFull = s("list_full", "{list} is full: {max} items. Clear the ticked ones first.", "La liste {list} est pleine : {max} éléments. Efface d'abord ceux qui sont cochés.")
    val listsFull = s("lists_full", "You have {max} lists, the most Robot keeps. Delete one first.", "Tu as {max} listes, le maximum que Robot garde. Supprimes-en une d'abord.")
    val fileFull = s("file_full", "Your lists are full. Clear the ticked items first.", "Tes listes sont pleines. Efface d'abord les éléments cochés.")
    val notNow = s("not_now", "Robot couldn't open your lists just now. Try again in a moment.", "Robot n'a pas pu ouvrir tes listes pour l'instant. Réessaie dans un instant.")
    val unreadable = s("unreadable", "Robot couldn't read your lists. {settings} says what you can do.", "Robot n'a pas pu lire tes listes. {settings} te dit quoi faire.")

    // The lock (§5).
    val unlockToHear = s("unlock_to_hear", "Unlock your phone to hear your lists.", "Déverrouille ton téléphone pour entendre tes listes.")
    val unlockToChange = s("unlock_to_change", "Unlock your phone to change your lists.", "Déverrouille ton téléphone pour changer tes listes.")
    val unlockToMake = s("unlock_to_make", "You don't have a list called {name}. Unlock your phone to make one.",
        "Tu n'as pas de liste appelée {name}. Déverrouille ton téléphone pour en créer une.")
    val lockedFull = s("locked_full", "Robot can't add more while the phone is locked. Unlock to add it.",
        "Robot ne peut plus rien ajouter pendant que le téléphone est verrouillé. Déverrouille pour l'ajouter.")
    val lockedFailed = s("locked_failed", "Robot couldn't add that while the phone is locked.", "Robot n'a pas pu ajouter ça pendant que le téléphone est verrouillé.")
    val someoneElse = s("someone_else", "Robot can't change your lists right now.", "Robot ne peut pas changer tes listes en ce moment.")

    // LS4, the model's add_to_list (§8.2, D6): its card names the list only (History keeps that line); the items are in the card's body.
    // recipe-list: the recipe card's line, and its other replies.
    val recipeGuess = s("recipe_guess", "A usual recipe for {dish}, so a guess. Untick what you have, then tap Add.",
        "Une recette habituelle de {dish}, donc une supposition. Décoche ce que tu as, puis touche Ajouter.")
    val recipeShared = s("recipe_shared", "The ingredients from the recipe you shared. Untick what you have, then tap Add.",
        "Les ingrédients de la recette que tu as partagée. Décoche ce que tu as, puis touche Ajouter.")
    val recipeUnknown = s("recipe_unknown", "I don't know a usual recipe for {dish}. Share the recipe with me and I'll list its ingredients.",
        "Je ne connais pas de recette habituelle de {dish}. Partage-moi la recette et j'en listerai les ingrédients.")
    val recipeNone = s("recipe_none", "I couldn't find the ingredients in what you shared.", "Je n'ai pas trouvé les ingrédients dans ce que tu as partagé.")
    val recipeNoDish = s("recipe_no_dish", "Which dish? Say its name, or share the recipe with me.", "Quel plat ? Dis son nom, ou partage-moi la recette.")
    val recipeUnticked = s("recipe_unticked", "Unticked {things}. Tap Add when it's right.", "Décoché : {things}. Touche Ajouter quand c'est bon.")
    val recipeNoMatch = s("recipe_no_match", "Nothing on the card matches {things}.", "Rien sur la carte ne correspond à {things}.")
    val recipeNothing = s("recipe_nothing", "Nothing is ticked, so nothing was added.", "Rien n'est coché, donc rien n'a été ajouté.")
    val titleAddToList = s("title_add_to_list", "Adding to your lists", "Ajouts à tes listes")
    val addTo = s("add_to", "Add to {list}", "Ajouter à la liste {list}")
    val addToNew = s("add_to_new", "Add to a new list, {list}", "Ajouter à une nouvelle liste, {list}")
    val addButton = s("add_button", "Add", "Ajouter")
    /** The card's line once it ran, counts only: the model is told the same in numbers, never the list. */
    val modelAdded = plural("model_added", "n", Plural("Added {n} item to {list}.", "Added {n} items to {list}."),
        Plural("{n} élément ajouté à la liste {list}.", "{n} éléments ajoutés à la liste {list}.", "{n} d'éléments ajoutés à la liste {list}."))
    val modelAlready = plural("model_already", "n", Plural("{n} was already on {list}.", "{n} were already on {list}."),
        Plural("{n} était déjà sur la liste {list}.", "{n} étaient déjà sur la liste {list}.", "{n} étaient déjà sur la liste {list}."))
    /** Why only a tap answers add_to_list's card (AskWhy.LIST_SUGGESTED): the items aren't all your words, or came with content. */
    val tapSuggested = s("tap_suggested", "Robot suggested these items. Tap Add to put them on the list.",
        "Robot a suggéré ces éléments. Touche Ajouter pour les mettre sur la liste.")

    // LS5, the panel's cards (§7.4): "Open Shopping" opens the list's page in the app.
    val openList = s("open_list", "Open {list}", "Ouvrir la liste {list}")
    val unlockToSee = s("unlock_to_see", "Unlock your phone to see your lists.", "Déverrouille ton téléphone pour voir tes listes.")
}
