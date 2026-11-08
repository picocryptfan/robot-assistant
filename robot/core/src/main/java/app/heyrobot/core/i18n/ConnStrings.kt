// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Skills connections (docs/plans/skills-connections-plan.md §3, §6.5; C2): what Robot says when a connection can't be
// used, the words a request's preview puts where a key would be, and the first-use card. Ids start with "conn."; the
// area is "connections". `{name}` is the name you gave the connection ("Home Assistant", "Jarvis's ntfy"), never
// translated; a server's own error text is never shown or said (it could carry anything). Québec French (tu), with the
// glossary's words (C11: connexion, clé, jeton, adresse, sujet, certificat, temps de trajet, webhook, relais vers une
// appli): « ta connexion {name} » where the English says "your {name}", and a bare {name} as subject or object, so
// nothing agrees with a name whose gender Robot can't know. render() adds the French spaces.

object ConnStrings : Strings("conn", area = "connections") {
    /** Settings › Skills › Connections' own title, for the sentences that send you there ([page]). */
    val title = s("title", "Connections", "Connexions")

    // §3's shared errors, in the order of its table.
    val offline = s("offline", "Robot can't go online right now.", "Robot ne peut pas aller en ligne en ce moment.")
    val networkOff = s("network_off", "Robot's network access is off in Android's settings.",
        "L'accès au réseau de Robot est désactivé dans les paramètres d'Android.")
    val unreachable = s("unreachable", "Robot can't reach your {name} right now.", "Robot ne peut pas joindre {name} en ce moment.")
    val timeout = s("timeout", "Your {name} didn't answer in time.", "{name} n'a pas répondu à temps.")
    val away = s("away", "Robot can't reach your {name} off your home network.", "Robot ne peut pas joindre {name} hors de ton réseau domestique.")
    val moved = s("moved", "Your {name}'s address now points outside your network. Test it again in {page}.",
        "L'adresse de ta connexion {name} pointe maintenant hors de ton réseau. Teste-la de nouveau dans {page}.")
    val bothKinds = s("both_kinds", "This address points both to your network and to the internet. Robot can't tell which you mean.",
        "Cette adresse pointe à la fois vers ton réseau et vers Internet. Robot ne peut pas savoir lequel tu veux dire.")
    val nowhere = s("nowhere", "Your {name}'s address points to a place Robot never connects to. Check it in {page}.",
        "L'adresse de ta connexion {name} pointe vers un endroit où Robot ne se connecte jamais. Vérifie-la dans {page}.")
    val certificate = s("certificate", "Android doesn't trust your {name}'s certificate. {page} says what works.",
        "Android ne fait pas confiance au certificat de ta connexion {name}. {page} dit ce qui fonctionne.")
    val keyOverHttp = s("key_over_http", "Robot won't send your {name} key over plain http: use an https address.",
        "Robot n'enverra pas ta clé {name} en http non chiffré : utilise une adresse https.")
    val keyRefused = s("key_refused", "Your {name} refused Robot's key. Make a new one and paste it in {page}.",
        "{name} a refusé la clé de Robot. Crées-en une nouvelle et colle-la dans {page}.")
    val notAllowed = s("not_allowed", "Your {name} says Robot isn't allowed to do that.", "{name} dit que Robot n'a pas le droit de faire ça.")
    val saidNo = s("said_no", "Your {name} said no (error {code}).", "{name} a dit non (erreur {code}).")
    val tooMany = s("too_many", "Your {name} says Robot sent too many. Try later.", "{name} dit que Robot en a envoyé trop. Réessaie plus tard.")
    val unreadable = s("unreadable", "Your {name}'s answer was too long or unreadable.",
        "La réponse de ta connexion {name} était trop longue ou illisible.")
    val limitMinute = s("limit_minute", "Robot's limit for your {name} was reached: {n} a minute.",
        "La limite de Robot pour {name} est atteinte : {n} par minute.")
    val limitDay = s("limit_day", "Robot's limit for your {name} was reached: {n} a day.",
        "La limite de Robot pour {name} est atteinte : {n} par jour.")
    val checkFailed = s("check_failed", "Step {n} didn't do what it should: {what}. Robot stopped. Steps {next} to {last} didn't run.",
        "L'étape {n} n'a pas fait ce qu'elle devait : {what}. Robot s'est arrêté. Les étapes {next} à {last} ne se sont pas lancées.")
    val checkFailedLast = s("check_failed_last", "Step {n} didn't do what it should: {what}. Robot stopped.",
        "L'étape {n} n'a pas fait ce qu'elle devait : {what}. Robot s'est arrêté.")

    // Before a call: what a connection still needs.
    val noKey = s("no_key", "Your {name} needs its key. Paste it in {page}.", "Ta connexion {name} a besoin de sa clé. Colle-la dans {page}.")
    val notTested = s("not_tested", "Test your {name} first in {page}.", "Teste d'abord ta connexion {name} dans {page}.")
    val gone = s("gone", "This connection is gone. Check the skill's steps in {page}.",
        "Cette connexion n'existe plus. Vérifie les étapes de la compétence dans {page}.")
    val changed = s("changed", "This step changed after its card showed, so Robot didn't run it. Ask again.",
        "Cette étape a changé après l'affichage de sa carte, alors Robot ne l'a pas lancée. Redemande-moi.")
    val noHome = s("no_home", "Save your home in {page} first.", "Enregistre d'abord ton domicile dans {page}.")
    val noWork = s("no_work", "Save your work address in {page} first.", "Enregistre d'abord l'adresse de ton travail dans {page}.")
    val badValue = s("bad_value", "Robot won't send this to your {name}: a value doesn't fit what it takes.",
        "Robot n'enverra pas ceci à {name} : une valeur ne correspond pas à ce que la connexion accepte.")

    // Setting one up (Settings › Skills › Connections, C9 shows them).
    val addressBad = s("address_bad", "Robot can't use this address. Type it the way your server shows it, starting with https.",
        "Robot ne peut pas utiliser cette adresse. Écris-la comme ton serveur l'affiche, en commençant par https.")
    val secretValue = s("secret_value", "This looks like a password or key. Keep it in the connection's key instead.",
        "Ça ressemble à un mot de passe ou à une clé. Garde-le plutôt dans la clé de la connexion.")
    val couldntSave = s("couldnt_save", "Robot couldn't save this connection.", "Robot n'a pas pu enregistrer cette connexion.")

    // A request's preview and card (§3.1, §5.4): what stands where a key goes, never the key.
    val authToken = s("auth_token", "{header}: your {name} token", "{header} : ton jeton {name}")
    val authPassword = s("auth_password", "{header}: your {name} password", "{header} : ton mot de passe {name}")
    val authKey = s("auth_key", "{header}: your {name} key", "{header} : ta clé {name}")

    // The first call to a host (D13): a tap-only card.
    val firstUse = s("first_use", "“{skill}” wants to send to {host} (your {name}): {what}.",
        "« {skill} » veut envoyer à {host} (ta connexion {name}) : {what}.")
    val firstUseLocation = s("first_use_location", "It sends where you are, to about 1 km, to your server.",
        "Elle envoie ta position, à environ 1 km près, à ton serveur.")

    // A skill's step that reaches a connection (C4, plan §5.4, D13, D14, D20): the tools' titles, the step's card and what
    // Robot says after it. {title} is your operation's title, {host} the connection's host, {skill} the skill's name.
    val toolConnection = s("tool_connection", "Your connections", "Tes connexions")
    val toolHandOff = s("tool_hand_off", "App hand-offs", "Relais vers des applis")
    /** Completes "It contains …" (Personal.HOME). */
    val personalHome = s("personal_home", "what your connections answered", "ce que tes connexions ont répondu")
    /** The card's summary and History's record (D20): your operation's title and your connection's name, never an address. */
    val stepCard = s("step_card", "{title} · your {name}", "{title} · ta connexion {name}")
    /** A connection's step on its skill's card. */
    val skillStep = s("skill_step", "{title} · your {name} ({host}) · {effects}", "{title} · ta connexion {name} ({host}) · {effects}")
    val hostLine = s("host_line", "Goes to {host} (your {name})", "Va vers {host} (ta connexion {name})")
    val valueLine = s("value_line", "{arg}: {value} · {source}", "{arg} : {value} · {source}")
    val sourceFixed = s("source_fixed", "saved in the skill", "enregistrée dans la compétence")
    val sourceYours = s("source_yours", "your words", "tes mots")
    val sourcePicked = s("source_picked", "picked by Robot's model", "choisie par le modèle de Robot")
    val sourceWritten = s("source_written", "written by Robot's model", "écrite par le modèle de Robot")
    val sourcePhone = s("source_phone", "read on your phone", "lue sur ton téléphone")
    val sourceOnline = s("source_online", "from an answer Robot read", "tirée d'une réponse que Robot a lue")
    val effectsLine = s("effects_line", "What it does: {list}", "Ce qu'elle fait : {list}")
    val effectHome = s("effect_home", "changes your home", "change quelque chose chez toi")
    val effectReads = s("effect_reads", "reads from {host}", "lit des données sur {host}")
    val effectSecurity = s("effect_security", "locks, unlocks or opens", "verrouille, déverrouille ou ouvre")
    val effectTells = s("effect_tells", "tells people", "prévient des gens")
    val effectWrites = s("effect_writes", "sends data to {host}", "envoie des données à {host}")
    val effectDeletes = s("effect_deletes", "deletes something on {host}", "supprime quelque chose sur {host}")
    val effectCode = s("effect_code", "runs code Robot can't see", "exécute du code que Robot ne voit pas")
    val effectMoney = s("effect_money", "can spend money", "peut dépenser de l'argent")
    val effectApp = s("effect_app", "opens an app", "ouvre une appli")
    val cantSeeScript = s("cant_see_script", "Robot can't see what this does: check it's yours.",
        "Robot ne peut pas voir ce que ça fait : vérifie que c'est bien à toi.")
    val contentTypeBody = s("content_type_body", "A text body goes as text/plain or application/x-www-form-urlencoded, and a JSON body as application/json.",
        "Un corps en texte part en text/plain ou en application/x-www-form-urlencoded, et un corps JSON en application/json.")
    val previewRequest = s("preview_request", "Request: {method} {url}", "Requête : {method} {url}")
    val previewHeader = s("preview_header", "Header: {line}", "En-tête : {line}")
    val previewBody = s("preview_body", "Body: {body}", "Corps : {body}")
    val previewMore = s("preview_more", "(and {n} more characters)", "(et {n} caractères de plus)")
    val allow = s("allow", "Allow for this connection", "Autoriser pour cette connexion")
    val noteRisky = s("note_risky", "This step locks, unlocks or opens something, deletes something or can spend money: tap to go ahead.",
        "Cette étape verrouille, déverrouille ou ouvre quelque chose, supprime quelque chose ou peut dépenser de l'argent : touche pour continuer.")
    val noteValue = s("note_value", "A value in this step was written by Robot's model or read from an answer: tap to go ahead.",
        "Une valeur de cette étape a été écrite par le modèle de Robot ou lue dans une réponse : touche pour continuer.")
    val notePicked = s("note_picked", "Robot's model picked a value in this step: check it before it runs.",
        "Le modèle de Robot a choisi une valeur dans cette étape : vérifie-la avant que l'étape se lance.")
    val tapFirst = s("tap_first", "Tap {allow}, {once} or Cancel: Robot sends nothing to a new address on a spoken yes.",
        "Touche {allow}, {once} ou Annuler : Robot n'envoie rien à une nouvelle adresse sur un oui dit à voix haute.")
    val tapStep = s("tap_step", "Tap the card to go ahead: a spoken yes doesn't run this step.",
        "Touche la carte pour continuer : un oui dit à voix haute ne lance pas cette étape.")
    val ranRead = s("ran_read", "Read from your {name}.", "Lu sur {name}.")
    val ranDone = s("ran_done", "Your {name} said yes.", "{name} a dit oui.")
    val outputLine = s("output_line", "{output}: {value}", "{output} : {value}")
    val checkWhat = s("check_what", "your {name}'s answer wasn't what the step checks for",
        "la réponse de ta connexion {name} n'était pas ce que l'étape vérifie")
    val checkWhatAny = s("check_what_any", "its answer wasn't what the step checks for", "sa réponse n'était pas ce que l'étape vérifie")
    val checkCard = s("check_card", "Your {name}'s answer wasn't what this step checks for.",
        "La réponse de ta connexion {name} n'était pas ce que cette étape vérifie.")
    val mayHaveSent = s("may_have_sent", "It may have reached your {name}, so Robot won't send it again by itself.",
        "Ça a peut-être atteint {name}, alors Robot ne l'enverra pas de nouveau de lui-même.")
    val stoppedMaybe = s("stopped_maybe", "Stopped. It may have reached your {name}.", "Arrêté. Ça a peut-être atteint {name}.")
    val firstUnanswered = s("first_unanswered", "Robot sent nothing: the first use of your {name} needs your answer on its card.",
        "Robot n'a rien envoyé : la première utilisation de ta connexion {name} attend ta réponse sur sa carte.")
    val notYet = s("not_yet", "Robot can't run this kind of step yet.", "Robot ne peut pas encore lancer ce genre d'étape.")
    val noValue = s("no_value", "Robot didn't send this to your {name}: a value from an earlier step didn't come back.",
        "Robot n'a pas envoyé ceci à {name} : une valeur d'une étape précédente n'est pas revenue.")
    val skillLimit = s("skill_limit", "Robot's limit for “{skill}” was reached: {n} runs an hour.",
        "La limite de Robot pour « {skill} » est atteinte : {n} lancements par heure.")
    val keyUnavailable = s("key_unavailable", "Robot couldn't read your {name} key just now. Try again in a moment.",
        "Robot n'a pas pu lire ta clé {name} tout de suite. Réessaie dans un instant.")
    val keyUnreadable = s("key_unreadable", "Robot couldn't read your {name} key anymore, so it removed it. Paste it again in {page}.",
        "Robot ne pouvait plus lire ta clé {name}, alors il l'a retirée. Colle-la de nouveau dans {page}.")
    val keyNewer = s("key_newer", "Your {name} key was saved by a newer Robot. Update Robot to use it.",
        "Ta clé {name} a été enregistrée par un Robot plus récent. Mets Robot à jour pour l'utiliser.")

    // Home Assistant's own check (C5, plan §3.1, §5.5: HaCheck). {things} names entities by their ids ("the downstairs
    // light"), never by a name your Home Assistant gave; a server's state text is never said.
    val haTurnedOn = s("ha_turned_on", "{things} turned on", "{things}, c'est allumé")
    val haTurnedOff = s("ha_turned_off", "{things} turned off", "{things}, c'est éteint")
    val haLocked = s("ha_locked", "{things} locked", "{things}, c'est verrouillé")
    val haUnlocked = s("ha_unlocked", "{things} unlocked", "{things}, c'est déverrouillé")
    val haOpened = s("ha_opened", "{things} opened", "{things}, c'est ouvert")
    val haClosed = s("ha_closed", "{things} closed", "{things}, c'est fermé")
    val haArmed = s("ha_armed", "{things} armed", "{things}, c'est armé")
    val haDisarmed = s("ha_disarmed", "{things} disarmed", "{things}, c'est désarmé")
    val haNotTurnedOn = s("ha_not_turned_on", "{things} didn't turn on", "{things}, ça ne s'est pas allumé")
    val haNotTurnedOff = s("ha_not_turned_off", "{things} didn't turn off", "{things}, ça ne s'est pas éteint")
    val haNotLocked = s("ha_not_locked", "{things} didn't lock", "{things}, ça ne s'est pas verrouillé")
    val haNotUnlocked = s("ha_not_unlocked", "{things} didn't unlock", "{things}, ça ne s'est pas déverrouillé")
    val haNotOpened = s("ha_not_opened", "{things} didn't open", "{things}, ça ne s'est pas ouvert")
    val haNotClosed = s("ha_not_closed", "{things} didn't close", "{things}, ça ne s'est pas fermé")
    val haNotArmed = s("ha_not_armed", "{things} didn't arm", "{things}, ça ne s'est pas armé")
    val haNotDisarmed = s("ha_not_disarmed", "{things} didn't disarm", "{things}, ça ne s'est pas désarmé")
    /** "the downstairs light": an entity's id said as words, with its domain's word. */
    val haTheNoun = s("ha_the_noun", "the {words} {noun}", "{noun} {words}")
    /** "the living room lights": an id that says its domain already. */
    val haThe = s("ha_the", "the {words}", "{words}")
    /** An entity whose id Robot can't say, of a domain without a word. */
    val haIt = s("ha_it", "it", "ça")
    val haLight = s("ha_light", "light", "la lumière")
    val haSwitch = s("ha_switch", "switch", "l'interrupteur")
    val haFan = s("ha_fan", "fan", "le ventilateur")
    val haLock = s("ha_lock", "lock", "la serrure")
    val haValve = s("ha_valve", "valve", "la vanne")
    val haAlarm = s("ha_alarm", "alarm", "l'alarme")
    val haSiren = s("ha_siren", "siren", "la sirène")
    /** A step's card after a passed check: "Your Home Assistant said yes: the downstairs light turned off." */
    val haDone = s("ha_done", "Your {name} said yes: {what}.", "{name} a dit oui : {what}.")
    /** A step whose entity is still on its way ("closing"): Robot doesn't say it's done. */
    val haMoving = s("ha_moving", "Your {name} said yes. It's still on its way, so Robot can't say it's done.",
        "{name} a dit oui. C'est encore en route, alors Robot ne peut pas dire que c'est fait.")

    // Test's line for Home Assistant (C5, plan §6.3): what the pickers found, never a name from it.
    val haCountLights = plural("ha_count_lights", "n", Plural(one = "{n} light", other = "{n} lights"),
        Plural(one = "{n} lumière", other = "{n} lumières"))
    val haCountSwitches = plural("ha_count_switches", "n", Plural(one = "{n} switch", other = "{n} switches"),
        Plural(one = "{n} interrupteur", other = "{n} interrupteurs"))
    val haCountLocks = plural("ha_count_locks", "n", Plural(one = "{n} lock", other = "{n} locks"),
        Plural(one = "{n} serrure", other = "{n} serrures"))
    val haCountCovers = plural("ha_count_covers", "n", Plural(one = "{n} cover", other = "{n} covers"),
        Plural(one = "{n} volet", other = "{n} volets"))
    val haCountScripts = plural("ha_count_scripts", "n", Plural(one = "{n} script", other = "{n} scripts"),
        Plural(one = "{n} script", other = "{n} scripts"))
    val haCountSensors = plural("ha_count_sensors", "n", Plural(one = "{n} sensor", other = "{n} sensors"),
        Plural(one = "{n} capteur", other = "{n} capteurs"))

    /** After "Connected, on your home network.": "It has 34 lights, 3 locks and 2 covers." */
    val haFound = s("ha_found", "It has {list}.", "On y trouve {list}.")
    val haListsUnread = s("ha_lists_unread", "Robot couldn't read its list of devices and services, so there's nothing to pick from yet. Test again later.",
        "Robot n'a pas pu lire sa liste d'appareils et de services, alors il n'y a rien à choisir pour l'instant. Teste de nouveau plus tard.")

    // Travel time (C6, plan §3.4): where you are goes to your routing server, rounded to about 1 km, only when a step runs.
    val locationOverHttp = s("location_over_http", "Robot won't send where you are to your {name} over plain http: use an https address, or your server's Tailscale address.",
        "Robot n'enverra pas ta position à {name} en http non chiffré : utilise une adresse https, ou l'adresse Tailscale de ton serveur.")
    val locationNotAllowed = s("location_not_allowed", "Robot isn't allowed to see where you are. Allow location for Robot in Android's settings.",
        "Robot n'a pas le droit de voir ta position. Autorise la position pour Robot dans les paramètres d'Android.")
    val locationOff = s("location_off", "Location is off on this phone. Turn it on to get a travel time.",
        "La localisation est désactivée sur ce téléphone. Active-la pour avoir un temps de trajet.")
    val locationBackground = s("location_background", "Android didn't let Robot see where you are from the assistant. Open Robot and ask again.",
        "Android n'a pas laissé Robot voir ta position depuis l'assistant. Ouvre Robot et redemande.")
    val noLocation = s("no_location", "Robot couldn't tell where you are just now.", "Robot n'a pas pu savoir où tu es tout de suite.")
    /** Completes "What it does: …" for travel time. */
    val effectLocation = s("effect_location", "sends where you are, to about 1 km", "envoie ta position, à environ 1 km près")
    /** Travel time's card: where it goes ("Travel time to home · your OSRM"). */
    val travelCard = s("travel_card", "Travel time to {place} · your {name}", "Temps de trajet vers {place} · ta connexion {name}")
    /** Travel time's card body: what is sent. */
    val travelSends = s("travel_sends", "Sends where you are, rounded to about 1 km, and your {place}'s place as saved in Car mode.",
        "Envoie ta position, arrondie à environ 1 km, et l'emplacement de {place} tel qu'enregistré dans le mode auto.")
    val travelHome = s("travel_home", "home", "ton domicile")
    val travelWork = s("travel_work", "work", "ton travail")
    /** After it ran: "Your OSRM: about 25 min (18.4 km)." */
    val travelDone = s("travel_done", "Your {name}: about {minutes} min ({km} km).", "{name} : environ {minutes} min ({km} km).")
    /** Test for a routing server: it needs your Home, and sends two points near it, never where you are now. */
    val routingTestSends = s("routing_test_sends", "Test asks for a route between two points near your Home, never where you are now.",
        "Le test demande un itinéraire entre deux points près de ton domicile, jamais là où tu es maintenant.")
    /** Test for a webhook: it sends nothing to your webhook. */
    val webhookTested = s("webhook_tested", "The server answered. Test never runs your webhook, so its first use is a real one.",
        "Le serveur a répondu. Le test ne lance jamais ton webhook, alors sa première utilisation est une vraie.")
    /** Test for an older ntfy without a health check: only that it answers, and that the key wasn't refused. */
    val ntfyOld = s("ntfy_old", "Your server answered, but it's too old to say it's healthy.",
        "Ton serveur a répondu, mais il est trop vieux pour dire s'il va bien.")

    // Hand-offs (C7, plan §3.5): an app you picked, opened from the panel. {name} is the name you gave the hand-off (the
    // app's own name when you picked it: "Signal").
    val appMissing = s("app_missing", "{name} isn't installed.", "L'appli {name} n'est pas installée.")
    val appReplaced = s("app_replaced", "{name} was replaced by an app signed by someone else. Pick it again in {page}.",
        "L'appli {name} a été remplacée par une appli signée par quelqu'un d'autre. Choisis-la de nouveau dans {page}.")
    val appCantOpen = s("app_cant_open", "{name} can't open this. Check the hand-off in {page}.",
        "L'appli {name} ne peut pas ouvrir ceci. Vérifie le relais dans {page}.")
    /** C10: a hand-off from a backup that isn't yours: its app was never pinned on this phone. */
    val appUnpinned = s("app_unpinned", "{name} came from a backup that isn't yours, so Robot won't open it yet. Pick it again in {page}.",
        "L'appli {name} vient d'une sauvegarde qui n'est pas la tienne, alors Robot ne l'ouvrira pas encore. Choisis-la de nouveau dans {page}.")
    /** The card's summary and History's record: "Open Signal". */
    val handOffCard = s("hand_off_card", "Open {name}", "Ouvrir {name}")
    /** With the words it hands over: "Open Signal with “On my way.”". */
    val handOffCardWith = s("hand_off_card_with", "Open {name} with “{text}”", "Ouvrir {name} avec « {text} »")
    val handOffApp = s("hand_off_app", "Opens {name} ({package}), signed by the same key as when you picked it",
        "Ouvre l'appli {name} ({package}), signée par la même clé que quand tu l'as choisie")
    val handOffAction = s("hand_off_action", "Asks it to: {action}", "Lui demande : {action}")
    val handOffData = s("hand_off_data", "Address: {data}", "Adresse : {data}")
    val handOffExtra = s("hand_off_extra", "Hands over {extra}: {value}", "Passe {extra} : {value}")
    val handOffSends = s("hand_off_sends", "You still send it yourself in {name}.", "Tu l'envoies quand même toi-même dans {name}.")
    val handOffOpened = s("hand_off_opened", "Opened {name}.", "J'ai ouvert {name}.")
    val handOffDraft = s("hand_off_draft", "Opened {name} with your words. Send it there when you're ready.",
        "J'ai ouvert {name} avec tes mots. Envoie-les de là quand tu veux.")
    // The app picker (C9 shows it): what can't be picked.
    val pickSeveralSigners = s("pick_several_signers", "Robot can't pin {name}: it's signed by more than one key.",
        "Robot ne peut pas retenir le signataire de {name} : l'appli est signée par plus d'une clé.")
    val pickNoSigner = s("pick_no_signer", "Android didn't say who signed {name}, so Robot can't pin it.",
        "Android n'a pas dit qui a signé {name}, alors Robot ne peut pas retenir son signataire.")
    val pickCantTake = s("pick_cant_take", "{name} doesn't take this kind of hand-off.", "L'appli {name} n'accepte pas ce genre de relais.")

    // Typed scripts (C8, plan §3.6): one of your Termux scripts as a skill's step. {name} is the script's file name.
    /** The step's card: Robot sees only the script's name and what its lines declare. */
    val scriptUnseen = s("script_unseen", "Robot can't see what this script does: check it's yours.",
        "Robot ne peut pas voir ce que fait ce script : vérifie que c'est bien le tien.")
    val scriptModelWords = s("script_model_words", "Robot's model wrote a value for this script: check the card, then tap {run}.",
        "Le modèle de Robot a écrit une valeur pour ce script : vérifie la carte, puis touche {run}.")
    val scriptBadValue = s("script_bad_value", "Robot won't run “{name}”: a value doesn't fit what the script takes.",
        "Robot ne lancera pas « {name} » : une valeur ne correspond pas à ce que le script accepte.")
    val scriptNoValue = s("script_no_value", "Robot didn't run “{name}”: a value from an earlier step didn't come back.",
        "Robot n'a pas lancé « {name} » : une valeur d'une étape précédente n'est pas revenue.")
    /** A failed check's {what} in [checkFailed]. */
    val scriptCheckWhat = s("script_check_what", "“{name}” didn't print what the step checks for",
        "« {name} » n'a pas affiché ce que l'étape vérifie")

    // Settings › Skills › Connections and a skill's page (C9, plan §6): the kinds, a row's state, Remove, why a step needs
    // attention, and what a skill's page computes (Effects, Risk, "Needs a model", Test's dry run).
    val kindHa = s("kind_ha", "Home Assistant", "Home Assistant")
    val kindNtfy = s("kind_ntfy", "ntfy", "ntfy")
    val kindWebhook = s("kind_webhook", "Webhook", "Webhook")
    val kindRouting = s("kind_routing", "Routing server", "Serveur d'itinéraires")
    val kindHandOff = s("kind_hand_off", "App hand-off", "Relais vers une appli")
    val kindHaLine = s("kind_ha_line", "Turns things on and off in your Home Assistant and reads their state. Each request goes to your server's address.",
        "Allume et éteint des choses dans ton Home Assistant et lit leur état. Chaque requête va à l'adresse de ton serveur.")
    val kindNtfyLine = s("kind_ntfy_line", "Sends a notification to your ntfy topic. The words go to your ntfy server.",
        "Envoie une notification à ton sujet ntfy. Les mots vont à ton serveur ntfy.")
    val kindWebhookLine = s("kind_webhook_line", "Sends one request you declare to an address of yours. Only what the request holds leaves the phone.",
        "Envoie une requête que tu décris à une adresse à toi. Seul ce que la requête contient quitte le téléphone.")
    val kindRoutingLine = s("kind_routing_line", "Gets the travel time home or to work from a routing server you run. Where you are goes to it, to about 1 km.",
        "Obtient le temps de trajet vers ton domicile ou ton travail d'un serveur d'itinéraires que tu fais tourner. Ta position y va, à environ 1 km près.")
    val kindHandOffLine = s("kind_hand_off_line", "Opens an app you pick with the words you set up. Nothing leaves the phone until you send it there.",
        "Ouvre une appli que tu choisis avec les mots que tu as préparés. Rien ne quitte le téléphone avant que tu l'envoies de là.")
    val stateTested = s("state_tested", "Tested {day}", "Testée {day}")
    val stateNeedsKey = s("state_needs_key", "Needs its key", "Il lui faut sa clé")
    val stateNotTested = s("state_not_tested", "Not tested yet", "Pas encore testée")
    val stateReplaced = s("state_replaced", "{name} was replaced", "L'appli {name} a été remplacée")
    val stateReady = s("state_ready", "Ready", "Prête")
    // Backups (C10, plan D7, §5.7): the categories' names and what a restore says.
    /** Connection keys' name; Back up now and the preview show it inside Keys and private codes' row. */
    val backupKeys = s("backup_keys", "Connection keys", "Clés des connexions")
    val backupUnreadableHere = s("backup_unreadable_here", "Robot can't read the connections on this phone, so it leaves Connections and their keys out.",
        "Robot ne peut pas lire les connexions de ce téléphone, alors il laisse de côté les Connexions et leurs clés.")
    /** C10: a hand-off restored from a backup that isn't yours. */
    val statePickAgain = s("state_pick_again", "From a backup that isn't yours: pick {name} again",
        "D'une sauvegarde qui n'est pas la tienne : choisis {name} de nouveau")
    val rowLine = s("row_line", "{kind} · {state}", "{kind} · {state}")
    val noneYet = s("none_yet", "None yet", "Aucune pour l'instant")
    val attentionCount = plural("attention_count", "n", Plural(one = "{n} needs attention", other = "{n} need attention"),
        Plural(one = "{n} a besoin de ton attention", other = "{n} ont besoin de ton attention"))
    val removeText = plural("remove_text", "n", Plural(one = "Its key is deleted, and {n} skill will need attention.",
        other = "Its key is deleted, and {n} skills will need attention."),
        Plural(one = "Sa clé est supprimée, et {n} compétence aura besoin de ton attention.", other = "Sa clé est supprimée, et {n} compétences auront besoin de ton attention."))
    val removeTextNone = s("remove_text_none", "Its key is deleted. No skill needs it.", "Sa clé est supprimée. Aucune compétence n'en a besoin.")
    val attentionStep = s("attention_step", "Step {n} needs attention: {why}.", "L'étape {n} a besoin de ton attention : {why}.")
    val whyConnGone = s("why_conn_gone", "its connection or operation is gone", "sa connexion ou son opération n'existe plus")
    val whyAppGone = s("why_app_gone", "its app hand-off is gone", "son relais vers une appli n'existe plus")
    val whyScriptGone = s("why_script_gone", "its script is gone, or Robot can't read its # param: lines",
        "son script n'existe plus, ou Robot ne peut pas lire ses lignes # param")
    val whyNoRouting = s("why_no_routing", "add a routing server in {page}", "ajoute un serveur d'itinéraires dans {page}")
    val whyDoesntFit = s("why_doesnt_fit", "a value is missing or doesn't fit what it takes",
        "une valeur manque ou ne correspond pas à ce que l'étape accepte")
    val effectDeletesOnline = s("effect_deletes_online", "deletes something online", "supprime quelque chose en ligne")
    val effectWritesOnline = s("effect_writes_online", "sends data online", "envoie des données en ligne")
    val effectReadsOnline = s("effect_reads_online", "reads online", "lit des données en ligne")
    val effectPhone = s("effect_phone", "changes something on your phone", "change quelque chose sur ton téléphone")
    val effectReadsPhone = s("effect_reads_phone", "reads your phone", "lit des données de ton téléphone")
    val effectModelText = s("effect_model_text", "may use words Robot's model wrote", "peut utiliser des mots écrits par le modèle de Robot")
    val tierSimple = s("tier_simple", "Simple", "Simple")
    val tierCareful = s("tier_careful", "Careful", "Délicate")
    val tierRisky = s("tier_risky", "Risky", "Risquée")
    val stepsCount = plural("steps_count", "n", Plural(one = "{n} step", other = "{n} steps"), Plural(one = "{n} étape", other = "{n} étapes"))
    val riskLine = s("risk_line", "{tier}: {steps}, {effects}", "{tier} : {steps}, {effects}")
    val riskLineQuiet = s("risk_line_quiet", "{tier}: {steps}", "{tier} : {steps}")
    val modelYes = plural("model_yes", "n", Plural(one = "Needs a model: yes, {n} question", other = "Needs a model: yes, {n} questions"),
        Plural(one = "Besoin d'un modèle : oui, {n} question", other = "Besoin d'un modèle : oui, {n} questions"))
    val modelNo = s("model_no", "Needs a model: no", "Besoin d'un modèle : non")
    val dryLine = s("dry_line", "Step {n}: {what}", "Étape {n} : {what}")
    val dryGone = s("dry_gone", "Step {n}: what it links to is gone.", "Étape {n} : ce à quoi elle renvoie n'existe plus.")
    val dryEarlier = s("dry_earlier", "Step {n}: it uses an earlier step's answer, which only comes when the skill runs.",
        "Étape {n} : elle utilise la réponse d'une étape précédente, qui n'arrive que quand la compétence se lance.")
    val dryMissing = s("dry_missing", "Step {n}: a value is missing or doesn't fit, so Robot can't show it yet.",
        "Étape {n} : une valeur manque ou ne convient pas, alors Robot ne peut pas encore la montrer.")
    val dryScript = s("dry_script", "Step {n}: runs your script “{name}” with {args}", "Étape {n} : lance ton script « {name} » avec {args}")
    val drySay = s("dry_say", "Step {n}: says “{text}”", "Étape {n} : dit « {text} »")
    val ifSet = s("if_set", "Only if {value} came back", "Seulement si on a reçu {value}")
    val ifUnset = s("if_unset", "Only if {value} didn't come back", "Seulement si on n'a pas reçu {value}")
    val ifIs = s("if_is", "Only if {value} is {choice}", "Seulement si {value} vaut {choice}")
    val ifYes = s("if_yes", "Only if {value} is yes", "Seulement si {value} vaut oui")
    val ifNo = s("if_no", "Only if {value} is no", "Seulement si {value} vaut non")
    val ifBelow = s("if_below", "Only if {value} is less than {n}", "Seulement si {value} vaut moins que {n}")
    val ifAbove = s("if_above", "Only if {value} is more than {n}", "Seulement si {value} vaut plus que {n}")
    val scriptTooMany = s("script_too_many", "It has more than 3 # param: lines. Robot can use 3 at most.",
        "Il a plus de 3 lignes # param. Robot peut en utiliser 3 au plus.")
    val scriptUnreadable = s("script_unreadable", "Robot can't read its # param: line {n}.", "Robot ne peut pas lire sa ligne # param numéro {n}.")
    val scriptRepeated = s("script_repeated", "Its # param: line {n} uses the name {name} again.",
        "Sa ligne # param numéro {n} réutilise le nom {name}.")
    val scriptRangeWide = s("script_range_wide", "This question allows {min} to {max}, but the script takes {smin} to {smax}.",
        "Cette question permet de {min} à {max}, mais le script accepte de {smin} à {smax}.")

    val mcpEra = s("mcp_era", "Your {name} uses a version of MCP that Robot can't use.",
        "Ta connexion {name} utilise une version de MCP que Robot ne peut pas utiliser.")
    val mcpNoTools = s("mcp_no_tools", "Your {name} has no tools Robot can use.", "Ta connexion {name} n'a aucun outil que Robot peut utiliser.")
    val mcpMoreInput = s("mcp_more_input", "The server asked for more information, which Robot can't give yet.",
        "Le serveur a demandé plus d'informations, ce que Robot ne peut pas encore donner.")
    val mcpToolFailed = s("mcp_tool_failed", "Your {name} couldn't do that.", "Ta connexion {name} n'a pas pu faire ça.")
    val mcpChanged = s("mcp_changed", "“{tool}” changed on your {name}. Robot turned it off until you look at it.",
        "« {tool} » a changé sur ta connexion {name}. Robot l'a désactivé jusqu'à ce que tu le regardes.")
    val mcpChangedInputs = s("mcp_changed_inputs", "What it takes changed: {names}.", "Ce qu'il accepte a changé : {names}.")
    val mcpChangedOutputs = s("mcp_changed_outputs", "What it gives back changed.", "Ce qu'il renvoie a changé.")
    val mcpChangedClaims = s("mcp_changed_claims", "What it says about itself changed, like whether it only reads.",
        "Ce qu'il dit de lui-même a changé, comme s'il ne fait que lire.")
    val mcpChangedDescription = s("mcp_changed_description", "Its description changed.", "Sa description a changé.")
    val mcpChangedTitle = s("mcp_changed_title", "Its title changed.", "Son titre a changé.")
    val mcpServerChanged = s("mcp_server_changed", "Your {name} is now a different server. Robot turned its tools off until you look at them.",
        "Ta connexion {name} est maintenant un autre serveur. Robot a désactivé ses outils jusqu'à ce que tu les regardes.")
    val mcpGone = s("mcp_gone", "“{tool}” is gone from your {name}. Robot turned it off.",
        "« {tool} » n'est plus sur ta connexion {name}. Robot l'a désactivé.")

    // 1.3, S3.6: the MCP client.
    val mcpLoopbackKey = s("mcp_loopback_key", "A server on this phone needs a token: any app on the phone can reach it.",
        "Un serveur sur ce téléphone a besoin d'un jeton : n'importe quelle appli du téléphone peut le joindre.")
    val mcpPublic = s("mcp_public", "Robot won't reach your {name} over the internet: an MCP server must be on your home network or on this phone, or reached by its Tailscale address.",
        "Robot ne joindra pas {name} par Internet : un serveur MCP doit être sur ton réseau domestique ou sur ce téléphone, ou joint par son adresse Tailscale.")
    val mcpTooLong = s("mcp_too_long", "This is too long for its card to show in full, so Robot won't send it to your {name}.",
        "C'est trop long pour que la carte le montre en entier, alors Robot ne l'enverra pas à {name}.")
    val mcpHttp = s("mcp_http", "Robot reaches your {name} only over https, with a certificate Android trusts: plain http isn't encrypted, even at home. Only a server on this phone may use plain http.",
        "Robot joint {name} seulement par https, avec un certificat auquel Android fait confiance : le http simple n'est pas chiffré, même à la maison. Seul un serveur sur ce téléphone peut utiliser le http simple.")
    val kindMcp = s("kind_mcp", "MCP server", "Serveur MCP")
    val kindMcpLine = s("kind_mcp_line", "Uses the tools of an MCP server you run at home or on this phone. Each tool stays off until you turn it on.",
        "Utilise les outils d'un serveur MCP que tu fais tourner à la maison ou sur ce téléphone. Chaque outil reste désactivé jusqu'à ce que tu l'actives.")
    val mcpCardTool = s("mcp_card_tool", "Tool: {tool}", "Outil : {tool}")
    val mcpCardArgs = s("mcp_card_args", "What it gets: {args}", "Ce qu'il reçoit : {args}")
    val mcpListed = s("mcp_listed", "Found {count} tools. Each stays off until you turn it on.",
        "{count} outils trouvés. Chacun reste désactivé jusqu'à ce que tu l'actives.")

    /** "Settings › Skills › Connections", for {page}. */
    val page: UiLabel get() = UiLabel(UiStrings.settings, UiStrings.skills, title)

    /** "Settings › Car mode", where Home and Work are saved, for [noHome] and [noWork]. */
    val carPage: UiLabel get() = UiStrings.inSettings(UiStrings.carMode)
}
