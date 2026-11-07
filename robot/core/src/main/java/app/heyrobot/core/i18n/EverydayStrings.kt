// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// The everyday set's catalog (docs/plans/everyday-set-plan.md): what Robot says for music, sums and What went online, with
// counts, amounts and names in it, shown and said in the turn's language. A screen's fixed words are in
// values/strings_everyday.xml. Ids start with "everyday."; the area is "everyday". Québec French (tu), the glossary's words
// (robot/l10n/glossary.tsv: « chanson », « liste de lecture », « pourboire », « Ce qui est allé en ligne »); render() adds
// the French spaces. E0 registers it empty; each job adds its entries in its own section below, so jobs running side by
// side merge without touching each other's lines. A song's, an artist's or a server's name is an argument, never translated.

object EverydayStrings : Strings("everyday", area = "everyday") {
    // E1: music (§3). Lines on screen with a cue, never spoken, except when something went wrong (§3.3, D3).
    val paused = s("music_paused", "♪ Paused", "♪ En pause")
    val playing = s("music_playing", "♪ Playing", "♪ Lecture en cours")
    val next = s("music_next", "♪ Next song", "♪ Chanson suivante")
    val previous = s("music_previous", "♪ Previous song", "♪ Chanson précédente")
    val byName = s("music_by_name", "♪ {what} in your music app", "♪ {what} dans ton appli de musique")
    val something = s("music_something", "♪ Something in your music app", "♪ Quelque chose dans ton appli de musique")
    // What's playing: the song's own tags (content, shown as text), and History's placeholder in their place (§3.7).
    val nowPlaying = s("music_now", "{title}, by {artist}.", "{title}, de {artist}.")
    val nowTitle = s("music_now_title", "{title}.", "{title}.")
    val cantSee = s("music_cant_see", "Robot can't see what your music app is playing.", "Robot ne voit pas ce que ton appli de musique fait jouer.")
    val saidWhatPlayed = s("music_said_what_played", "Robot said what was playing.", "Robot a dit ce qui jouait.")
    // When something went wrong: said aloud.
    val didntAnswer = s("music_didnt_answer", "Your music app didn't answer.", "Ton appli de musique n'a pas répondu.")
    val noRequests = s("music_no_requests", "Your music app doesn't take requests from Robot. Open it to play that.",
        "Ton appli de musique n'accepte pas les demandes de Robot. Ouvre-la pour faire jouer ça.")
    val replaced = s("music_replaced", "Your music app was replaced by an app signed by someone else. Pick it again in {page}.",
        "Ton appli de musique a été remplacée par une appli signée par quelqu'un d'autre. Choisis-la de nouveau dans {page}.")
    val closedDuringLock = s("music_closed_lock", "Your music app is closed during this lock.", "Ton appli de musique est fermée pendant ce blocage.")
    // The card the first time you play something by name (Q1), and Settings › Actions › Music.
    val page = s("music_page", "Music", "Musique")
    val pickTitle = s("music_pick_title", "Pick your music app", "Choisis ton appli de musique")
    val pickDetail = s("music_pick_detail", "Robot plays what you ask for by name in the app you pick. You can change it in {page}.",
        "Robot fait jouer ce que tu demandes par son nom dans l'appli que tu choisis. Tu peux la changer dans {page}.")
    val pickTap = s("music_pick_tap", "Tap your music app on the card.", "Touche ton appli de musique sur la carte.")
    val pickNone = s("music_pick_none", "No music app on this phone takes requests from Robot. Robot can still pause, skip and play what's there.",
        "Aucune appli de musique de ce téléphone n'accepte les demandes de Robot. Robot peut quand même mettre sur pause, passer à la chanson suivante et faire jouer ce qu'il y a.")
    val pickLocked = s("music_pick_locked", "Unlock to pick your music app.", "Déverrouille pour choisir ton appli de musique.")
    val pickDuringLock = s("music_pick_during_lock", "You can pick your music app once the phone lock is over.",
        "Tu pourras choisir ton appli de musique une fois le blocage du téléphone terminé.")
    val notPicked = s("music_not_picked", "No music app picked. Nothing played.", "Aucune appli de musique choisie. Rien n'a joué.")
    val picked = s("music_picked", "{label} is your music app.", "{label} est ton appli de musique.")

    // E2: sums (§4). Numbers and amounts are arguments (Arg.Dec, Arg.Dollars), formatted for the language and the voice; each
    // piece of a sum is an entry, so "12 × 7 = 84" on screen is "12 times 7 is 84." aloud and « 12 fois 7, ça fait 84. ».
    val number = s("number", "{n}", "{n}")
    val money = s("money", "{m}", "{m}")
    val percent = s("percent", "{n}%", "{n} %", voiceEn = "{n} percent", voiceFr = "{n} pour cent")
    val about = s("about", "about {x}", "environ {x}")
    val plus = s("plus", "{a} + {b}", "{a} + {b}", voiceEn = "{a} plus {b}", voiceFr = "{a} plus {b}")
    val minus = s("minus", "{a} − {b}", "{a} − {b}", voiceEn = "{a} minus {b}", voiceFr = "{a} moins {b}")
    val times = s("times", "{a} × {b}", "{a} × {b}", voiceEn = "{a} times {b}", voiceFr = "{a} fois {b}")
    val divide = s("divide", "{a} ÷ {b}", "{a} ÷ {b}", voiceEn = "{a} divided by {b}", voiceFr = "{a} divisé par {b}")
    val power = s("power", "{a}^{b}", "{a}^{b}", voiceEn = "{a} to the power of {b}", voiceFr = "{a} à la puissance {b}")
    val root = s("root", "√{a}", "√{a}", voiceEn = "the square root of {a}", voiceFr = "la racine carrée de {a}")
    val neg = s("neg", "-{a}", "-{a}", voiceEn = "minus {a}", voiceFr = "moins {a}")
    val group = s("group", "({a})", "({a})", voiceEn = "{a}", voiceFr = "{a}")
    val percentOfExpr = s("percent_of_expr", "{a}%", "{a} %", voiceEn = "{a} percent", voiceFr = "{a} pour cent")
    val equals = s("equals", "{expr} = {result}", "{expr} = {result}", voiceEn = "{expr} is {result}.", voiceFr = "{expr}, ça fait {result}.")
    val divideByZero = s("divide_by_zero", "You can't divide by zero.", "On ne peut pas diviser par zéro.")
    val tooBig = s("too_big", "That number is too big for Robot to say.", "Ce nombre est trop grand pour que Robot le dise.")
    val negativeRoot = s("negative_root", "A negative number has no square root.", "Un nombre négatif n'a pas de racine carrée.")
    val wholePowers = s("whole_powers", "Robot works out whole powers only, up to {max}.", "Robot calcule seulement les puissances entières, jusqu'à {max}.")
    val unreadable = s("unreadable", "Robot couldn't read that sum.", "Robot n'a pas pu lire ce calcul.")
    val percentOf = s("percent_of", "{p} of {x} is {r}.", "{p} de {x}, c'est {r}.")
    val whatPercent = s("what_percent", "{a} is {p} of {b}.", "{a}, c'est {p} de {b}.")
    val plusPercent = s("plus_percent", "{x} plus {p} is {r}.", "{x} plus {p}, c'est {r}.")
    val minusPercent = s("minus_percent", "{x} minus {p} is {r}.", "{x} moins {p}, c'est {r}.")
    val discount = s("discount", "{p} off {x} is {r}. You save {s}.", "{x} moins {p}, c'est {r}. Tu économises {s}.")
    val tipA = s("tip_a", "A {p} tip on {x} is {t}. Total: {total}.", "Un pourboire de {p} sur {x}, c'est {t}. Total : {total}.",
        voiceEn = "A {p} tip on {x} is {t}. The total is {total}.", voiceFr = "Un pourboire de {p} sur {x}, c'est {t}. Le total est de {total}.")
    val tipAn = s("tip_an", "An {p} tip on {x} is {t}. Total: {total}.", "Un pourboire de {p} sur {x}, c'est {t}. Total : {total}.",
        voiceEn = "An {p} tip on {x} is {t}. The total is {total}.", voiceFr = "Un pourboire de {p} sur {x}, c'est {t}. Le total est de {total}.")
    val tax = s("tax", "{x} plus {p} {name} is {total}. That's {t} of {name}.", "{x} plus {p} de {name}, c'est {total}, dont {t} de {name}.")
    val taxHst = s("tax_hst", "HST", "TVH")
    val taxGst = s("tax_gst", "GST", "TPS")
    val taxQst = s("tax_qst", "QST", "TVQ")
    val taxPst = s("tax_pst", "PST", "TVP")
    val taxTax = s("tax_tax", "tax", "taxes")
    val whichHst = s("which_hst", "Which rate? HST is 13% in Ontario, 14% in Nova Scotia, and 15% in New Brunswick, Newfoundland and Labrador, and Prince Edward Island.",
        "Quel taux ? La TVH est de 13 % en Ontario, de 14 % en Nouvelle-Écosse et de 15 % au Nouveau-Brunswick, à Terre-Neuve-et-Labrador et à l'Île-du-Prince-Édouard.")
    val whichRate = s("which_rate", "Which rate? Say it with the amount, like “$80 plus 15% tax”.", "Quel taux ? Dis-le avec le montant, comme « 80 $ plus 15 % de taxes ».",
        voiceEn = "Which rate? Say it with the amount, like 80 dollars plus 15 percent tax.",
        voiceFr = "Quel taux ? Dis-le avec le montant, comme 80 dollars plus 15 pour cent de taxes.")
    val splitEven = s("split_even", "{x} split {n} ways is {each} each.", "{x} divisé en {n}, c'est {each} chacun.")
    val splitList = s("split_list", "{x} split {n} ways: {shares}.", "{x} divisé en {n} : {shares}.")
    val splitGroups = s("split_groups", "{x} split {n} ways: {big} each for {k}, and {small} each for {rest}.",
        "{x} divisé en {n} : {big} chacun pour {k}, et {small} chacun pour {rest}.")
    val convert = s("convert", "{from} is {to}.", "{from}, c'est {to}.")
    val convertUs = s("convert_us", "In US measure, {q}.", "En mesure américaine, {q}.")
    val convertFromUs = s("convert_from_us", "For {q}, {r}.", "Pour {q}, {r}.")
    val weighed = s("weighed", "Robot can't turn a volume into a weight: it depends on what's measured.",
        "Robot ne peut pas convertir un volume en poids : ça dépend de ce qu'on mesure.")
    val days = plural("days", "n", Plural("{n} day", "{n} days"), Plural("{n} jour", "{n} jours"))
    val weeks = plural("weeks", "n", Plural("{n} week", "{n} weeks"), Plural("{n} semaine", "{n} semaines"))
    val day = s("day", "{day}", "{day}")
    val dayYear = s("day_year", "{day}, {year}", "{day} {year}")
    val until = s("until", "{span}: {day}.", "{span} : {day}.")
    val today = s("today", "That's today: {day}.", "C'est aujourd'hui : {day}.")
    val ago = s("ago", "That was {span} ago: {day}.", "C'était il y a {span} : {day}.")
    val since = s("since", "{span} since {day}.", "{span} depuis {day}.")
    val weekday = s("weekday", "It's {day}.", "C'est {day}.")
    val dateIn = s("date_in", "In {span}: {day}.", "Dans {span} : {day}.")
    /** calculate's card (§4.4): the sum was Robot's code's, and what the model asked for after it wasn't done. */
    val calculateTitle = s("calculate_title", "Sums", "Calculs")
    val calculated = s("calculated", "Worked out by Robot", "Calculé par Robot")
    val calcFirst = s("calc_first", "Not done: Robot answered with its sum first.", "Pas fait : Robot a d'abord répondu avec son calcul.")

    /** A unit's amount, "{n} km" / "{n} kilometres" aloud, by its key (core sums Unit.key). */
    private fun u(key: String, en: Plural, fr: Plural, voiceEn: Plural? = null, voiceFr: Plural? = null) = key to plural("unit_$key", "n", en, fr, voiceEn, voiceFr)
    private fun p(one: String, other: String) = Plural("{n} $one", "{n} $other")
    private fun p(both: String) = Plural("{n} $both", "{n} $both")

    private val units: Map<String, Msg> = mapOf(
        u("mm", p("mm"), p("mm"), p("millimetre", "millimetres"), p("millimètre", "millimètres")),
        u("cm", p("cm"), p("cm"), p("centimetre", "centimetres"), p("centimètre", "centimètres")),
        u("m", p("m"), p("m"), p("metre", "metres"), p("mètre", "mètres")),
        u("km", p("km"), p("km"), p("kilometre", "kilometres"), p("kilomètre", "kilomètres")),
        u("inch", p("inch", "inches"), p("pouce", "pouces")),
        u("foot", p("foot", "feet"), p("pied", "pieds")),
        u("yard", p("yard", "yards"), p("verge", "verges")),
        u("mile", p("mile", "miles"), p("mille", "milles")),
        u("nautical_mile", p("nautical mile", "nautical miles"), p("mille marin", "milles marins")),
        u("g", p("g"), p("g"), p("gram", "grams"), p("gramme", "grammes")),
        u("kg", p("kg"), p("kg"), p("kilogram", "kilograms"), p("kilogramme", "kilogrammes")),
        u("lb", p("lb"), p("lb"), p("pound", "pounds"), p("livre", "livres")),
        u("oz", p("oz"), p("oz"), p("ounce", "ounces"), p("once", "onces")),
        u("stone", p("stone"), p("stone")),
        u("tonne", p("tonne", "tonnes"), p("tonne", "tonnes")),
        u("ml", p("mL"), p("mL"), p("millilitre", "millilitres"), p("millilitre", "millilitres")),
        u("l", p("L"), p("L"), p("litre", "litres"), p("litre", "litres")),
        u("tsp", p("teaspoon", "teaspoons"), p("cuillère à thé", "cuillères à thé")),
        u("tsp_us", p("US teaspoon", "US teaspoons"), p("cuillère à thé américaine", "cuillères à thé américaines")),
        u("tbsp", p("tablespoon", "tablespoons"), p("cuillère à soupe", "cuillères à soupe")),
        u("tbsp_us", p("US tablespoon", "US tablespoons"), p("cuillère à soupe américaine", "cuillères à soupe américaines")),
        u("cup", p("cup of 250 mL", "cups of 250 mL"), p("tasse de 250 mL", "tasses de 250 mL"),
            p("cup of 250 millilitres", "cups of 250 millilitres"), p("tasse de 250 millilitres", "tasses de 250 millilitres")),
        u("cup_us", p("US cup", "US cups"), p("tasse américaine", "tasses américaines")),
        u("floz", p("imperial fl oz"), p("once liquide impériale", "onces liquides impériales"), p("imperial fluid ounce", "imperial fluid ounces"),
            p("once liquide impériale", "onces liquides impériales")),
        u("floz_us", p("US fl oz"), p("once liquide américaine", "onces liquides américaines"), p("US fluid ounce", "US fluid ounces"),
            p("once liquide américaine", "onces liquides américaines")),
        u("pint", p("imperial pint", "imperial pints"), p("pinte impériale", "pintes impériales")),
        u("pint_us", p("US pint", "US pints"), p("pinte américaine", "pintes américaines")),
        u("quart", p("imperial quart", "imperial quarts"), p("quart impérial", "quarts impériaux")),
        u("quart_us", p("US quart", "US quarts"), p("quart américain", "quarts américains")),
        u("gallon", p("imperial gallon", "imperial gallons"), p("gallon impérial", "gallons impériaux")),
        u("gallon_us", p("US gallon", "US gallons"), p("gallon américain", "gallons américains")),
        u("celsius", p("°C"), p("°C"), p("degree Celsius", "degrees Celsius"), p("degré Celsius", "degrés Celsius")),
        u("fahrenheit", p("°F"), p("°F"), p("degree Fahrenheit", "degrees Fahrenheit"), p("degré Fahrenheit", "degrés Fahrenheit")),
        u("kelvin", p("K"), p("K"), p("kelvin", "kelvins"), p("kelvin", "kelvins")),
        u("kmh", p("km/h"), p("km/h"), p("kilometre per hour", "kilometres per hour"), p("kilomètre à l'heure", "kilomètres à l'heure")),
        u("mph", p("mph"), p("mi/h"), p("mile per hour", "miles per hour"), p("mille à l'heure", "milles à l'heure")),
        u("mps", p("m/s"), p("m/s"), p("metre per second", "metres per second"), p("mètre par seconde", "mètres par seconde")),
        u("knot", p("knot", "knots"), p("nœud", "nœuds")),
        u("sq_m", p("m²"), p("m²"), p("square metre", "square metres"), p("mètre carré", "mètres carrés")),
        u("sq_km", p("km²"), p("km²"), p("square kilometre", "square kilometres"), p("kilomètre carré", "kilomètres carrés")),
        u("sq_ft", p("sq ft"), p("pi²"), p("square foot", "square feet"), p("pied carré", "pieds carrés")),
        u("sq_mi", p("sq mi"), p("mi²"), p("square mile", "square miles"), p("mille carré", "milles carrés")),
        u("acre", p("acre", "acres"), p("acre", "acres")),
        u("hectare", p("ha"), p("ha"), p("hectare", "hectares"), p("hectare", "hectares")),
        u("l_100km", p("L/100 km"), p("L/100 km"), p("litre per 100 kilometres", "litres per 100 kilometres"),
            p("litre aux 100 kilomètres", "litres aux 100 kilomètres")),
        u("mpg", p("mpg (imperial)"), p("mi/gal (impérial)"), p("mile per imperial gallon", "miles per imperial gallon"),
            p("mille au gallon impérial", "milles au gallon impérial")),
        u("mpg_us", p("mpg (US)"), p("mi/gal (américain)"), p("mile per US gallon", "miles per US gallon"),
            p("mille au gallon américain", "milles au gallon américain")),
        u("second", p("second", "seconds"), p("seconde", "secondes")),
        u("minute", p("minute", "minutes"), p("minute", "minutes")),
        u("hour", p("hour", "hours"), p("heure", "heures")),
        u("day", p("day", "days"), p("jour", "jours")),
        u("week", p("week", "weeks"), p("semaine", "semaines")),
    )

    /** [unit]'s amount entry. */
    fun unit(unit: app.heyrobot.core.sums.Unit): Msg = units.getValue(unit.key)

    // E3: What went online (§5). The page's title (a UiLabel's last part), what each connection was for, what allowed it,
    // how it ended, and the spoken summary (§5.6), all made by code from the record's codes. A server's name is an argument.
    val online = s("online", "What went online", "Ce qui est allé en ligne")
    val onlineWentOnce = s("online_went_once", "Robot went online once {day}: {what}.", "Robot est allé en ligne une fois {day} : {what}.")
    val onlineWent = s("online_went", "Robot went online {n} times {day}: {what}.", "Robot est allé en ligne {n} fois {day} : {what}.")
    val onlineNone = s("online_none", "Robot hasn't gone online {day}.", "Robot n'est pas allé en ligne {day}.")
    val onlineOff = s("online_off", "Robot isn't keeping a record of what went online. You can turn it on in {path}.",
        "Robot ne garde pas de trace de ce qui est allé en ligne. Tu peux l'activer dans {path}.")
    val onlineLocked = s("online_locked", "Unlock your phone to hear what went online.", "Déverrouille ton téléphone pour savoir ce qui est allé en ligne.")
    val onlineToday = s("online_today", "today", "aujourd'hui")
    val onlineYesterday = s("online_yesterday", "yesterday", "hier")
    val onlineWeek = s("online_week", "in the last 7 days", "au cours des 7 derniers jours")
    val onlineTwice = s("online_twice", "{what} twice", "{what} deux fois")
    val onlineTimes = s("online_times", "{what} {n} times", "{what} {n} fois")
    val onlineOthers = plural("online_others", "n", Plural("{n} other thing", "{n} other things"), Plural("{n} autre chose", "{n} autres choses"))
    // What a spoken summary names (OnlineKind).
    val onlineKWeather = s("online_k_weather", "the weather", "la météo")
    val onlineKNearest = s("online_k_nearest", "the nearest place", "le lieu le plus proche")
    val onlineKWeb = s("online_k_web", "web search", "la recherche web")
    val onlineKHelper = s("online_k_helper", "your helper", "ton IA externe")
    val onlineKHelperTest = s("online_k_helper_test", "a helper's connection test", "un test de connexion d'une IA externe")
    val onlineKDownload = s("online_k_download", "a download", "un téléchargement")
    val onlineKDailyCheck = s("online_k_daily_check", "the daily update check", "la vérification quotidienne des mises à jour")
    val onlineKUpdateCheck = s("online_k_update_check", "an update check", "une vérification des mises à jour")
    val onlineKUpdateDownload = s("online_k_update_download", "an update download", "un téléchargement de mise à jour")
    val onlineKTermux = s("online_k_termux", "Termux's update check", "la vérification des mises à jour de Termux")
    val onlineKConnection = s("online_k_connection", "one of your connections", "une de tes connexions")
    val onlineKLink = s("online_k_link", "Robot to Robot", "Robot à Robot")
    val onlineKNews = s("online_k_news", "the news", "les nouvelles")
    // What a row was for (NetPurpose), on the page.
    val onlinePWeather = s("online_p_weather", "Weather forecast", "Prévisions météo")
    val onlinePWeatherPlace = s("online_p_weather_place", "Finding the place you named, for the weather", "Trouver le lieu nommé, pour la météo")
    val onlinePNearest = s("online_p_nearest", "Nearest place", "Lieu le plus proche")
    val onlinePSearch = s("online_p_search", "Web search", "Recherche web")
    val onlinePEncyclopedia = s("online_p_encyclopedia", "Encyclopedia lookup, for a web search", "Recherche encyclopédique, pour une recherche web")
    val onlinePPage = s("online_p_page", "A result page, read for a web search", "Une page de résultats, lue pour une recherche web")
    val onlinePHelper = s("online_p_helper", "A question to your helper", "Une question à ton IA externe")
    val onlinePHelperTest = s("online_p_helper_test", "Test connection, for a helper", "Tester la connexion, pour une IA externe")
    val onlinePModel = s("online_p_model", "Model download", "Téléchargement du modèle")
    val onlinePVoice = s("online_p_voice", "Voice download", "Téléchargement de voix")
    val onlinePTranslation = s("online_p_translation", "Translation engine download", "Téléchargement du moteur de traduction")
    val onlinePUpdateCheck = s("online_p_update_check", "Update check", "Vérification des mises à jour")
    val onlinePUpdateDownload = s("online_p_update_download", "Update download", "Téléchargement de la mise à jour")
    val onlinePTermux = s("online_p_termux", "Termux update check", "Vérification des mises à jour de Termux")
    val onlinePConnection = s("online_p_connection", "One of your connections", "Une de tes connexions")
    val onlinePLinkDirect = s("online_p_link_direct", "Another phone on your network (Robot to Robot)", "Un autre téléphone sur ton réseau (Robot à Robot)")
    val onlinePLinkPush = s("online_p_link_push", "A push server (Robot to Robot)", "Un serveur push (Robot à Robot)")
    val onlinePFeed = s("online_p_feed", "A news feed you added", "Un fil de nouvelles que tu as ajouté")
    val onlinePLinkTor = s("online_p_link_tor", "Tor network (Robot to Robot)", "Réseau Tor (Robot à Robot)")
    // What allowed it (NetCause).
    val onlineCAsked = s("online_c_asked", "You asked", "Tu l'as demandé")
    val onlineCRoutine = s("online_c_routine", "A routine", "Une routine")
    val onlineCTap = s("online_c_tap", "You tapped it", "Tu l'as touché")
    val onlineCSwitch = s("online_c_switch", "Runs by itself · {path}", "Se fait tout seul · {path}")
    val onlineCSkill = s("online_c_skill", "A skill", "Une compétence")
    // How it ended (NetResult).
    val onlineRDone = s("online_r_done", "Done", "Fait")
    val onlineRFailed = s("online_r_failed", "Failed", "Échec")
    val onlineRStopped = s("online_r_stopped", "Stopped", "Arrêté")
    val onlineRStarted = s("online_r_started", "Handed to Android's download service", "Confié au service de téléchargement d'Android")
    // A host the record doesn't name (NetHost's markers).
    val onlineHLan = s("online_h_lan", "your network", "ton réseau")
    val onlineHIp = s("online_h_ip", "an address typed as numbers", "une adresse en chiffres")
    val onlineHUnknown = s("online_h_unknown", "a server", "un serveur")
    val onlineHTor = s("online_h_tor", "the Tor network", "le réseau Tor")
    // The page's lines with counts (§5.4, §5.5).
    val onlineHeader = plural("online_header", "n", Plural("{day}: {n} connection to {servers}.", "{day}: {n} connections to {servers}."),
        Plural("{day} : {n} connexion vers {servers}.", "{day} : {n} connexions vers {servers}."))
    val onlineServers = plural("online_servers", "n", Plural("{n} server", "{n} servers"), Plural("{n} serveur", "{n} serveurs"))
    val onlineHeaderNone = s("online_header_none", "{day}: no connections.", "{day} : aucune connexion.")
    val onlineTodayTitle = s("online_today_title", "Today", "Aujourd'hui")
    val onlineBytes = s("online_bytes", "{out} out · {in} in", "{out} envoyés · {in} reçus")
    val onlineAndroid = s("online_android", "Since the phone started, Android counted {android} sent and received by Robot. Robot's record has {robot}. " +
        "The difference is encryption, headers and lookups.",
        "Depuis le démarrage du téléphone, Android a compté {android} envoyés et reçus par Robot. La trace de Robot en a {robot}. " +
            "La différence vient du chiffrement, des en-têtes et des recherches d'adresses.")
    val onlineAndroidMore = s("online_android_more", "Android counted more than Robot recorded. Please send a problem report.",
        "Android a compté plus que ce que Robot a noté. Envoie un rapport de problème, s'il te plaît.")
    val onlineAndroidNone = s("online_android_none", "Android doesn't give its own count for Robot on this phone.",
        "Android ne donne pas son propre décompte pour Robot sur ce téléphone.")
    // Send feedback's tick (§5.8), never ticked for you.
    val onlineTick = s("online_tick", "What went online (7 days)", "Ce qui est allé en ligne (7 jours)")
    val onlineTickDetail = s("online_tick_detail", "Servers Robot itself uses, kinds and counts only: never your own servers or the pages a search read",
        "Les serveurs que Robot utilise lui-même, les types et les nombres seulement : jamais tes propres serveurs ni les pages lues pour une recherche")
    val onlineMoreDay = plural("online_more_day", "n", Plural("And {n} more connection that day, kept as a count only.",
        "And {n} more connections that day, kept as counts only."),
        Plural("Et {n} autre connexion ce jour-là, gardée seulement dans le décompte.", "Et {n} autres connexions ce jour-là, gardées seulement dans le décompte."))

    // E6: Robot's voices for other apps (§6.3): the sentence Android's text-to-speech settings play as the example
    // (GET_SAMPLE_TEXT), in the voice's language.
    val voiceSample = s("voice_sample", "This is Robot's voice.", "Voici la voix de Robot.")

    // E7: Ask Robot (§7). What another app shared is never named by its app (§7.7), and its words are an argument, as they are.
    /** The chip above the question: {text} is the preview's one line (core AskShare.preview). */
    val askChipText = s("ask_chip_text", "Shared text: “{text}”", "Texte partagé : « {text} »")
    val askChipPhoto = s("ask_chip_photo", "Shared photo", "Photo partagée")
    /** {time}: the message's length, "0:42". */
    val askChipVoice = s("ask_chip_voice", "Shared voice message ({time})", "Message vocal partagé ({time})")
    val askTextCut = s("ask_text_cut", "Robot took the first {n} characters.", "Robot a pris les {n} premiers caractères.")
    val askVoiceCut = s("ask_voice_cut", "Robot wrote down the first {n} minutes.", "Robot a transcrit les {n} premières minutes.")
    /** The model's context couldn't hold all of it (Prompt.fitTurn). */
    val askContextCut = s("ask_context_cut", "Robot read only the start of what you shared.", "Robot a lu seulement le début de ce que tu as partagé.")
    // The buttons under the chip: each sends your request in these words, as if you said them (§7.3).
    val askExplain = s("ask_explain", "Explain this.", "Explique-moi ça.")
    val askTranslate = s("ask_translate", "Translate this.", "Traduis ça.")
    val askSummarize = s("ask_summarize", "Summarize this.", "Résume ça.")
    val askReply = s("ask_reply", "Draft a reply to this.", "Rédige une réponse à ça.")
    val askRemember = s("ask_remember", "Remember this.", "Retiens ça.")
    // Why Robot took nothing (core ask AskRefusal), on Ask Robot's window.
    val askPhoneLock = s("ask_phone_lock", "Robot can't take things from other apps during a phone lock.",
        "Robot ne peut rien prendre des autres applis pendant le blocage du téléphone.")
    val askLocked = s("ask_locked", "Unlock your phone to use Ask Robot.", "Déverrouille ton téléphone pour utiliser Demander à Robot.")
    val askOff = s("ask_off", "Ask Robot in other apps is off. You can turn it on in {page}.",
        "Demander à Robot dans les autres applis est désactivé. Tu peux l'activer dans {page}.")
    val askNotAssistant = s("ask_not_assistant", "Make Robot your assistant to use Ask Robot.", "Fais de Robot ton assistant pour utiliser Demander à Robot.")
    val askNotReady = s("ask_not_ready", "Robot's assistant isn't running yet. Try again in a moment.",
        "L'assistant de Robot ne fonctionne pas encore. Réessaie dans un moment.")
    val askNothing = s("ask_nothing", "There's nothing here Robot can take.", "Il n'y a rien ici que Robot puisse prendre.")
    val askNotAllowed = s("ask_not_allowed", "Robot can't take this from that app.", "Robot ne peut pas prendre ça de cette appli.")
    val askPhotoTooLarge = s("ask_photo_too_large", "This photo is over {size}, more than Robot takes.", "Cette photo dépasse {size}, la taille maximale pour Robot.")
    val askVoiceTooLarge = s("ask_voice_too_large", "This voice message is over {size}, more than Robot takes.",
        "Ce message vocal dépasse {size}, la taille maximale pour Robot.")
    val askWrongType = s("ask_wrong_type", "Robot can't read this kind of file.", "Robot ne peut pas lire ce genre de fichier.")
    val askPhotoUnreadable = s("ask_photo_unreadable", "Robot couldn't read this photo.", "Robot n'a pas pu lire cette photo.")
    val askVoiceUnreadable = s("ask_voice_unreadable", "Robot couldn't play this voice message.", "Robot n'a pas pu lire ce message vocal.")
    val askNoSpeechModel = s("ask_no_speech_model", "Robot needs its speech recognition model to write down a voice message. You can download it in {page}.",
        "Robot a besoin de son modèle de reconnaissance vocale pour transcrire un message vocal. Tu peux le télécharger dans {page}.")
    val askBusy = s("ask_busy", "A transcription is running. Stop it, then share the voice message again.",
        "Une transcription est en cours. Arrête-la, puis partage de nouveau le message vocal.")
    val askTimedOut = s("ask_timed_out", "The other app didn't hand it over in time. Try again.",
        "L'autre appli ne l'a pas remis à temps. Réessaie.")
    val askNoWords = s("ask_no_words", "Robot heard no words in this voice message.", "Robot n'a entendu aucun mot dans ce message vocal.")
    val askNotOpened = s("ask_not_opened", "Robot couldn't open its panel. Try again.", "Robot n'a pas pu ouvrir son panneau. Réessaie.")

    // Car mode's music and maps (docs/pm/handoff-car-media-maps.md): Settings › Actions › Maps.
    val mapsPage = s("maps_page", "Maps", "Cartes")
    val mapsReplaced = s("maps_replaced", "Your maps app was replaced by an app signed by someone else. Pick it again in {page}.",
        "Ton appli de cartes a été remplacée par une appli signée par quelqu'un d'autre. Choisis-la de nouveau dans {page}.")
}
