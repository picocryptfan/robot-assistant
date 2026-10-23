// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP6's catalog: web search: web/*. What core says or labels about web search: the services' names, the
// first search's card, why a search failed or wasn't sent, what Robot says while it searches, the answer card's lines and
// credits, Settings › Web search's choices and its row's summary. Ids start with "web."; every entry has its French. The
// model's prompts (WebPrompt), the voice log's lines and the words sent to a search service stay English.

object WebStrings : Strings("web", area = "web") {
    // The search services: a name in a list, the same in a sentence ("to your SearXNG"), and at a sentence's start.
    val homeTitle = s("svc_home_title", "Your own SearXNG", "Ton propre SearXNG")
    val homeLabel = s("svc_home_label", "your SearXNG", "ton SearXNG")
    val homeCap = s("svc_home_cap", "Your SearXNG", "Ton SearXNG")
    val braveTitle = s("svc_brave_title", "Brave Search API", "API Brave Search")
    val publicTitle = s("svc_public_title", "A public SearXNG", "Un SearXNG public")
    val publicLabel = s("svc_public_label", "a public SearXNG", "un SearXNG public")
    val wikipediaOnly = s("svc_wikipedia_only", "Wikipedia only", "Wikipédia seulement")
    val ddgTitle = s("svc_ddg_title", "DuckDuckGo Instant Answers", "Réponses instantanées DuckDuckGo")
    val wikipedia = s("wikipedia", "Wikipedia", "Wikipédia")
    val theService = s("the_service", "the search service", "le service de recherche")
    val theServiceCap = s("the_service_cap", "The search service", "Le service de recherche")

    // A search's cost on the card and in Settings.
    val costCents = s("cost_cents", "≈ {n}¢", "≈ {n} ¢")
    val costPence = s("cost_pence", "≈ {n}p", "≈ {n} p")
    val costFree = s("cost_free", "≈ free", "≈ gratuit")

    // How private a service or an engine is (its chip).
    val privacyHigh = s("privacy_high", "High", "Élevé")
    val privacyMedium = s("privacy_medium", "Medium", "Moyen")
    val privacyLow = s("privacy_low", "Low", "Faible")

    // Set-up and consent.
    val notOffered = s("not_offered", "Google, Bing, Startpage and DuckDuckGo have no full search API that Robot can use: they open in your browser instead.",
        "Google, Bing, Startpage et DuckDuckGo n'ont pas d'API de recherche complète que Robot peut utiliser : ils s'ouvrent plutôt dans ton navigateur.")
    // The consent, one sentence per recipient as PRIVACY.md says it (WebSetup.consentText): the service; Wikipedia for a who,
    // what or where question while Encyclopedia lookups are on; Open Library for a question about books while Book lookups
    // are; and the result pages this phone may open for one answer, {n} from Settings (WebGate.pagesFromPhone).
    val consentSends = s("consent_sends", "When you ask, Robot sends your search words to {service}.",
        "Quand tu le demandes, Robot envoie tes mots de recherche à {service}.")
    // The words WebAlso.encyclopedic hears at a question's start: who, what, where; qui, quel, quelle, qu'est-ce, où.
    val consentWikipedia = s("consent_wikipedia", "A who, what or where question also goes to Wikipedia.",
        "Une question qui commence par « qui », « quel », « quelle », « qu'est-ce » ou « où » va aussi à Wikipédia.")
    val consentBooks = s("consent_books", "A question about books also goes to Open Library.", "Une question sur des livres va aussi à Open Library.")
    val consentPages = plural("consent_pages", "n",
        Plural(one = "To answer, it may open {n} result page from this phone, like a browser without cookies: that site sees your IP address.",
            other = "To answer, it may open up to {n} of the result pages from this phone, like a browser without cookies: those sites see your IP address."),
        Plural(one = "Pour répondre, il peut ouvrir {n} page de résultats depuis ce téléphone, comme un navigateur sans témoins : ce site voit ton adresse IP.",
            other = "Pour répondre, il peut ouvrir jusqu'à {n} des pages de résultats depuis ce téléphone, comme un navigateur sans témoins : ces sites voient " +
                "ton adresse IP."))
    val consentNothingElse = s("consent_nothing_else", "Nothing else leaves the phone.", "Rien d'autre ne quitte le téléphone.")

    // Settings › Web search's choices.
    val askFirstTime = s("ask_first_time", "Ask the first time", "Demander la première fois")
    val askAlways = s("ask_always", "Always ask", "Toujours demander")
    val askNever = s("ask_never", "Never ask", "Ne jamais demander")
    val readWhenNeeded = s("read_when_needed", "When needed", "Au besoin")
    val readAlways = s("read_always", "Always", "Toujours")
    val readNever = s("read_never", "Never (snippets only)", "Jamais (extraits seulement)")
    val throughPhone = s("through_phone", "This phone", "Ce téléphone")
    val throughService = s("through_service", "The search service when it can", "Le service de recherche quand il le peut")
    val safeOff = s("safe_off", "Off", "Désactivée")
    val safeModerate = s("safe_moderate", "Moderate", "Modérée")
    val safeStrict = s("safe_strict", "Strict", "Stricte")
    val keepDomains = s("keep_domains", "Domains only", "Domaines seulement")
    val keepLinks = s("keep_links", "Full links", "Liens complets")
    val keepNone = s("keep_none", "None", "Aucune")
    val robotFirst = s("robot_first", "Robot searches first", "Robot cherche d'abord")
    val helperFirst = s("helper_first", "Ask a helper first", "Demander d'abord à une IA externe")

    // Settings' row.
    val summaryNotSetUp = s("summary_not_set_up", "Not set up", "Pas configurée")
    val summaryOn = s("summary_on", "On · {service}", "Activée · {service}")
    val summaryOff = s("summary_off", "Off · {service}", "Désactivée · {service}")

    // The first search's card.
    val justThisTime = s("just_this_time", "Just this time", "Juste cette fois")
    val no = s("no", "No", "Non")
    val dontAsk = s("dont_ask", "Don't ask again", "Ne plus demander")
    val search = s("search", "Search", "Chercher")
    val cardTitle = s("card_title", "Search the web for “{query}”", "Chercher « {query} » sur le web")
    val wouldSend = s("would_send", "Robot would send these words to {to}.", "Robot enverrait ces mots à {to}.")
    val wouldSendPages = plural("would_send_pages", "n",
        Plural("Robot would send these words to {to}. It may also open up to {n} result page from this phone.",
            "Robot would send these words to {to}. It may also open up to {n} result pages from this phone."),
        Plural("Robot enverrait ces mots à {to}. Il pourrait aussi ouvrir jusqu'à {n} page de résultats depuis ce téléphone.",
            "Robot enverrait ces mots à {to}. Il pourrait aussi ouvrir jusqu'à {n} pages de résultats depuis ce téléphone."))

    // Why a search failed: what Robot says and the card shows.
    val failNoInternet = s("fail_no_internet", "No internet: Robot couldn't search the web", "Pas d'Internet : Robot n'a pas pu chercher sur le web")
    val failNoNetwork = s("fail_no_network", "Robot isn't allowed to use the network", "Robot n'a pas le droit d'utiliser le réseau")
    val failNotHome = s("fail_not_home", "Robot can't reach {name} off your home network", "Robot ne peut pas joindre {name} hors de ton réseau domestique")
    val failNotAnswering = s("fail_not_answering", "{name} isn't answering", "{name} ne répond pas")
    val failJsonOff = s("fail_json_off", "{name} answered 403: add json under {path} in settings.yml", "{name} a répondu 403 : ajoute json sous {path} dans settings.yml")
    val failKeyMissing = s("fail_key_missing", "The key for {name} is missing", "La clé pour {name} manque")
    val failKeyRefused = s("fail_key_refused", "{name} refused the key", "{name} a refusé la clé")
    val failOutOfCredit = s("fail_out_of_credit", "The key for {name} is out of credit", "La clé pour {name} n'a plus de crédit")
    val failBusy = s("fail_busy", "{name} is busy just now", "{name} est occupé en ce moment")
    val failService = s("fail_service", "{name} didn't answer properly", "{name} n'a pas répondu correctement")
    val failTimeout = s("fail_timeout", "The search took too long", "La recherche a pris trop de temps")
    val failNoShortAnswer = s("fail_no_short_answer", "DuckDuckGo has no short answer for that", "DuckDuckGo n'a pas de réponse courte pour ça")
    val failNoResults = s("fail_no_results", "No results", "Aucun résultat")
    val failStopped = s("fail_stopped", "Stopped", "Arrêtée")
    val failDailyCap = s("fail_daily_cap", "That's today's limit of web searches", "C'est la limite de recherches web pour aujourd'hui")
    val failQueryRefused = s("fail_query_refused", "That search looked like it contained personal details, so Robot didn't send it.",
        "Cette recherche semblait contenir des renseignements personnels, alors Robot ne l'a pas envoyée.")
    val failOff = s("fail_off", "Web search is off", "La recherche web est désactivée")
    val failKeyOverHttp = s("fail_key_over_http", "Robot won't send a key over plain http: use an https address, or remove the key",
        "Robot n'enverra pas de clé en http non chiffré : utilise une adresse https, ou retire la clé")

    /** A reason above as a sentence of its own. */
    val sentence = s("sentence", "{text}.", "{text}.")

    // The gate's refusals.
    val offTurnOn = s("off_turn_on", "Web search is off: turn it on in Robot's {path}.", "La recherche web est désactivée : active-la dans {path}.")
    val dailyCapRaise = s("daily_cap_raise", "That's today's limit of web searches ({n}). {path} can raise it.",
        "C'est la limite de recherches web pour aujourd'hui ({n}). Tu peux l'augmenter dans {path}.")
    val notSearched = s("not_searched", "Not searched: Robot answers without the web.", "Pas de recherche : Robot répond sans le web.")

    // Why a query shows its card, or never goes.
    val queryPersonal = s("query_personal", "This search uses words from something Robot read on your phone, not from what you said.",
        "Cette recherche utilise des mots tirés de quelque chose que Robot a lu sur ton téléphone, pas de ce que tu as dit.")
    val queryContent = s("query_content", "This search uses words from a web page, your screen or another AI's answer, not from what you said.",
        "Cette recherche utilise des mots tirés d'une page web, de ton écran ou de la réponse d'une autre IA, pas de ce que tu as dit.")
    val queryUnknown = s("query_unknown", "This search uses words you didn't say, in a conversation with content Robot didn't write.",
        "Cette recherche utilise des mots que tu n'as pas dits, dans une conversation avec du contenu que Robot n'a pas écrit.")
    val queryEmpty = s("query_empty", "There was nothing to search for.", "Il n'y avait rien à chercher.")
    val queryTooLong = s("query_too_long", "That search was too long to send.", "Cette recherche était trop longue pour être envoyée.")
    val queryMemory = s("query_memory", "That search used something Robot remembers about you, and what Robot remembers never goes to a search service, so Robot didn't send it.",
        "Cette recherche utilisait quelque chose que Robot retient à ton sujet, et ce que Robot retient ne va jamais à un service de recherche, alors Robot ne l'a pas envoyée.")

    // Why a result page wasn't read ("tsn.ca: not a public address").
    val skipNotFromResults = s("skip_not_from_results", "not one of the search results", "pas un des résultats de la recherche")
    val skipNotWeb = s("skip_not_web", "not a web address", "pas une adresse web")
    val skipNotSecure = s("skip_not_secure", "not secure", "pas sécurisée")
    val skipNotPublic = s("skip_not_public", "not a public address", "pas une adresse publique")
    val skipPort = s("skip_port", "an unusual port", "un port inhabituel")
    val skipRedirects = s("skip_redirects", "too many redirects", "trop de redirections")
    val skipPdf = s("skip_pdf", "a PDF, which Robot can't read", "un PDF, que Robot ne peut pas lire")
    val skipNotHtml = s("skip_not_html", "not a web page Robot can read", "pas une page web que Robot peut lire")
    val skipVideo = s("skip_video", "a video", "une vidéo")
    val skipNeedsJs = s("skip_needs_js", "needs JavaScript", "demande JavaScript")
    val skipTooSlow = s("skip_too_slow", "took too long", "a pris trop de temps")
    val skipFailed = s("skip_failed", "didn't load", "ne s'est pas chargée")
    val skippedLine = s("skipped_line", "{domain}: {why}", "{domain} : {why}")

    // What Robot says while a search works.
    val searching = s("searching", "Searching the web…", "Je cherche sur le web…")
    val reading = plural("reading", "n", Plural("Reading {n} page…", "Reading {n} pages…"), Plural("Je lis {n} page…", "Je lis {n} pages…"))
    val readingDone = s("reading_done", "Reading pages {done}/{n}…", "Je lis les pages {done}/{n}…")

    // A key pasted in the set-up window.
    val keyWhereBrave = s("key_where_brave", "Create a key at api-dashboard.search.brave.com (Search plan; a card on file even for the free credit) and paste it here.",
        "Crée une clé sur api-dashboard.search.brave.com (forfait Search; une carte de crédit enregistrée, même pour le crédit gratuit), puis colle-la ici.")
    val keyWhereKagi = s("key_where_kagi", "Create an API token at kagi.com/settings › API (pay per use) and paste it here.",
        "Crée un jeton d'API sur kagi.com/settings › API (paiement à l'utilisation), puis colle-le ici.")
    val keyWhereMojeek = s("key_where_mojeek", "Mojeek gives keys through its sales contact (mojeek.com/services/search/web-search-api); paste yours here.",
        "Mojeek donne des clés par son contact commercial (mojeek.com/services/search/web-search-api); colle la tienne ici.")
    val keyEmpty = s("key_empty", "Paste the key.", "Colle la clé.")
    val keyCharacters = s("key_characters", "A key has no spaces or special characters: copy it again.",
        "Une clé n'a ni espaces ni caractères spéciaux : copie-la de nouveau.")
    val keyShort = s("key_short", "That's too short for a key.", "C'est trop court pour une clé.")
    val keyLong = s("key_long", "That's too long for a key.", "C'est trop long pour une clé.")

    // The answer card.
    val cardHeader = s("card_header", "Searched the web · {service}", "Recherche web · {service}")
    val cardSent = s("card_sent", "Sent to {to}: “{query}”", "Envoyé à {to} : « {query} »")
    val cardReadThrough = plural("card_read_through", "n", Plural("Read {n} page through {service}", "Read {n} pages through {service}"),
        Plural("{n} page lue par {service}", "{n} pages lues par {service}"))
    val cardReadPhone = plural("card_read_phone", "n", Plural("Read {n} page from this phone", "Read {n} pages from this phone"),
        Plural("{n} page lue depuis ce téléphone", "{n} pages lues depuis ce téléphone"))
    val alsoFound = s("also_found", "Also found", "Trouvé aussi")
    val savedLine = s("saved_line", "Searched the web ({to})", "Recherche web ({to})")
    val savedLineSources = s("saved_line_sources", "Searched the web ({to}) · {sources}", "Recherche web ({to}) · {sources}")
    val creditBrave = s("credit_brave", "Powered by Brave", "Propulsé par Brave")
    val creditWikipediaText = s("credit_wikipedia_text", "Wikipedia text: CC BY-SA 4.0", "Texte de Wikipédia : CC BY-SA 4.0")
    val creditOpenLibrary = s("credit_open_library", "From Open Library", "Tiré d'Open Library")
    val creditDuckDuckGo = s("credit_duckduckgo", "Results from DuckDuckGo", "Résultats de DuckDuckGo")
    val creditWikipedia = s("credit_wikipedia", "From Wikipedia's article “{title}”, {licence}", "Tiré de l'article « {title} » de Wikipédia, {licence}")
    val creditLicence = s("credit_licence", "{credit} (licence)", "{credit} (voir la licence)")

    // Test's line in Settings › Web search.
    val testLine = plural("test_line", "n", Plural("JSON on · {n} result", "JSON on · {n} results"), Plural("JSON activé · {n} résultat", "JSON activé · {n} résultats"))
    val testLineEngines = plural("test_line_engines", "n", Plural("JSON on · {n} result · engines: {engines}", "JSON on · {n} results · engines: {engines}"),
        Plural("JSON activé · {n} résultat · moteurs : {engines}", "JSON activé · {n} résultats · moteurs : {engines}"))
}
