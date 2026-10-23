// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP2b's catalog: the helpers: what Robot says about a helper (core helpers HelperWords, ClaudeWords), why a helper
// failed (HelperFailure), and what a conversation holds that is personal (Personal), in the turn's reply language; and the words core gives Settings › Helpers and a helper's connection window (the presets, the models,
// the name and key rules, the address hints), in the menus' language. Ids start with "helper."; the area is "helpers". The French is Québec French (tu), written with the
// glossary (robot/l10n/glossary.tsv): a helper is « une IA externe », Robot's Settings « Réglages ». render() adds its
// spaces. What a helper is sent (its requests, Robot's tool results) stays English.

object HelperStrings : Strings("helper", area = "helpers") {
    // HelperWords: what Robot says about a helper, by its name.
    val declined = s("declined", "{name} declined that one.", "{name} a refusé celle-là.")
    val thinking = s("thinking", "{name} is thinking…", "{name} réfléchit…")
    val checking = s("checking", "Checking with {name}…", "Je vérifie avec {name}…")
    val checkingSaid = s("checking_said", "Checking with {name}.", "Je vérifie avec {name}.")
    val searched = s("searched", "{name} searched the web", "{name} a cherché sur le web")
    val off = s("off", "{name} is off. Turn it on in Robot's settings, under Helpers.",
        "L'IA externe {name} est désactivée. Active-la dans les réglages de Robot, sous IA externes.")
    val locked = s("locked", "Unlock your phone to ask {name}, or allow {name} on the lock screen in Robot's settings.",
        "Déverrouille ton téléphone pour demander à {name}, ou autorise {name} sur l'écran de verrouillage dans les réglages de Robot.")
    val joined = s("joined", "{name} is here. Go ahead.", "{name} est là. Vas-y.")
    val notHere = s("not_here", "{name} isn't in this conversation.", "{name} n'est pas dans cette conversation.")
    val budget = s("budget", "Today's budget for helpers is used up, so Robot will ask before checking with {name} again today.",
        "Le budget du jour pour les IA externes est épuisé, alors Robot demandera avant de vérifier de nouveau avec {name} aujourd'hui.")
    val cutEmpty = s("cut_empty", "{name} ran out of room before it could answer. Ask again, or ask for a shorter answer.",
        "{name} a manqué de place avant de pouvoir répondre. Redemande, ou demande une réponse plus courte.")
    val keyUnreadable = s("key_unreadable", "Robot couldn't read your saved {name} key, so it was removed. Add it again in Robot's settings, under Helpers.",
        "Robot n'a pas pu lire ta clé {name} enregistrée, alors elle a été retirée. Ajoute-la de nouveau dans les réglages de Robot, sous IA externes.")
    val keyUnavailable = s("key_unavailable", "Robot couldn't read your saved {name} key just now. It's still saved, so try again in a moment.",
        "Robot n'a pas pu lire ta clé {name} enregistrée pour l'instant. Elle est toujours enregistrée, alors réessaie dans un moment.")
    val offer = s("offer", "Want me to check with {name}?", "Veux-tu que je vérifie avec {name} ?")
    val offerAsWell = s("offer_as_well", "Want {name}'s take as well?", "Veux-tu aussi l'avis de {name} ?")
    val rather = s("rather", "I'd rather check that with {name}.", "Je préfère vérifier ça avec {name}.")
    val loading = s("loading", "{name} is loading its model…", "{name} charge son modèle…")
    val unlockToCheck = s("unlock_to_check", "Unlock your phone to check with {name}.", "Déverrouille ton téléphone pour vérifier avec {name}.")
    val nothingSent = s("nothing_sent", "Okay, nothing was sent to {name}.", "D'accord, rien n'a été envoyé à {name}.")
    // 1.0.0: another company's app or API (Settings › Helpers › its page › Use).
    val routeOtherApp = s("route_other_app", "{name} app", "Appli {name}")
    val routeOtherApi = s("route_other_api", "{company} API", "API de {company}")
    val routeOtherAppDetail = s("route_other_app_detail",
        "Opens the {name} app with your question; you press Send there, with your own account. Robot doesn't hear the answer.",
        "Ouvre l'appli {name} avec ta question; tu touches Envoyer là-bas, avec ton propre compte. Robot n'entend pas la réponse.")
    val routeOtherApiDetail = s("route_other_api_detail", "Robot asks {name} itself and speaks the answer. Needs an API key with credits.",
        "Robot demande à {name} lui-même et dit la réponse. Il faut une clé d'API avec des crédits.")
    // 1.0.0: "ask the helper …" when none can answer yet.
    val notReady = s("not_ready", "{name} can't answer yet. Add its API key in Robot's settings, under Helpers.",
        "{name} ne peut pas encore répondre. Ajoute sa clé d'API dans les réglages de Robot, sous IA externes.")
    val notReadyApp = s("not_ready_app", "{name} can't answer yet. Add its API key in Robot's settings, under Helpers, or install the {appName} app.",
        "{name} ne peut pas encore répondre. Ajoute sa clé d'API dans les réglages de Robot, sous IA externes, ou installe l'appli {appName}.")
    val tapToInclude = s("tap_to_include", "Tap {button} to include {what}.", "Touche {button} pour inclure {what}.")

    // ClaudeWords: Claude's conversation.
    val backToRobot = s("back_to_robot", "Back to Robot.", "De retour avec Robot.")
    val swipeToRead = s("swipe_to_read", "Open the Claude app, then swipe up from the corner and tap “Read Claude's answer”.",
        "Ouvre l'appli Claude, puis balaie vers le haut depuis le coin et touche « Lire la réponse de Claude ».")

    // HelperFailure: why a helper didn't answer ({name}: the helper's; {company}: its company's).
    val keyRefused = s("key_refused", "Your {name} key was refused. Check it in Robot's settings.",
        "Ta clé {name} a été refusée. Vérifie-la dans les réglages de Robot.")
    val noCredits = s("no_credits", "Your {company} account is out of API credits.", "Ton compte {company} n'a plus de crédits d'API.")
    val noCreditsAt = s("no_credits_at", "Your {company} account is out of API credits. Add credits at {console}.",
        "Ton compte {company} n'a plus de crédits d'API. Ajoutes-en sur {console}.")
    val serverRefused = s("server_refused", "{name} refused that request. Check the server's settings.",
        "{name} a refusé cette demande. Vérifie les paramètres du serveur.")
    val notAllowed = s("not_allowed", "Your {name} key isn't allowed to do that. Check your {company} account.",
        "Ta clé {name} n'a pas le droit de faire ça. Vérifie ton compte {company}.")
    val serverModelGone = s("server_model_gone", "{name} doesn't have that model any more. Pick another one in Robot's settings, under Helpers.",
        "{name} n'a plus ce modèle. Choisis-en un autre dans les réglages de Robot, sous IA externes.")
    val modelUnavailable = s("model_unavailable", "That {name} model isn't available to your key. Pick another one in Robot's settings.",
        "Ce modèle de {name} n'est pas offert à ta clé. Choisis-en un autre dans les réglages de Robot.")
    val rateLimited = s("rate_limited", "{name} is busy, try again in a minute.", "{name} reçoit trop de demandes, réessaie dans une minute.")
    val overloaded = s("overloaded", "{name} is overloaded right now. Try again in a minute.", "Les serveurs de {name} sont surchargés en ce moment. Réessaie dans une minute.")
    val serverError = s("server_error", "{name} isn't answering right now. Try again in a minute.", "{name} ne répond pas en ce moment. Réessaie dans une minute.")
    val badRequest = s("bad_request", "{name} couldn't take that request.", "{name} n'a pas pu prendre cette demande.")
    val tooLong = s("too_long", "That was too long for {name}.", "C'était trop long pour {name}.")
    val noInternet = s("no_internet", "No internet. If you're online, check that Robot's Network permission is on ({path}).",
        "Pas d'Internet. Si tu es en ligne, vérifie que l'autorisation Réseau de Robot est activée ({path}).")
    val noNetworkPermission = s("no_network_permission", "Robot isn't allowed to use the network. Turn on Network for Robot in Android's app settings.",
        "Robot n'a pas le droit d'utiliser le réseau. Active Réseau pour Robot dans les paramètres d'applis d'Android.")
    val timeout = s("timeout", "{name} took too long to answer.", "{name} a pris trop de temps à répondre.")
    val reset = s("reset", "Robot lost track of the conversation with {name}, so {name} mode has ended. Ask again to start fresh.",
        "Robot a perdu le fil de la conversation avec {name}, alors le mode {name} est terminé. Redemande pour repartir à zéro.")
    val noKey = s("no_key", "Add your {name} API key in Robot's settings first.", "Ajoute d'abord ta clé d'API {name} dans les réglages de Robot.")
    val noKeyOrApp = s("no_key_or_app", "Add your {name} API key in Robot's settings first, or choose the {appName} app there.",
        "Ajoute d'abord ta clé d'API {name} dans les réglages de Robot, ou choisis l'appli {appName} là.")
    val unknown = s("unknown", "Something went wrong with {name}.", "Quelque chose n'a pas marché avec {name}.")
    val homeUnreachable = s("home_unreachable", "{name} isn't answering. Is the computer on, and is the phone on the same network?",
        "{name} ne répond pas. L'ordinateur est-il allumé, et le téléphone est-il sur le même réseau ?")
    val unreachable = s("unreachable", "{name} can't be reached right now.", "Impossible de joindre {name} en ce moment.")
    val notLocal = s("not_local", "That address isn't on your home network, so Robot won't send to it without https.",
        "Cette adresse n'est pas sur ton réseau domestique, alors Robot n'y enverra rien sans https.")
    val certificate = s("certificate",
        "{name}'s certificate isn't one Robot can trust. Use https with a real certificate (Tailscale's HTTPS gives one), or http on your home network.",
        "Le certificat de {name} n'est pas un certificat auquel Robot peut se fier. Utilise https avec un vrai certificat (le HTTPS de Tailscale en donne un), ou http sur ton réseau domestique.")
    val noAddress = s("no_address", "Robot doesn't know where {name} is yet. Open its page in Robot's settings, under Helpers, and tap {button}.",
        "Robot ne sait pas encore où trouver {name}. Ouvre sa page dans les réglages de Robot, sous IA externes, et touche {button}.")
    // 1.0.1: a key never goes over http.
    val keyOverHttp = s("key_over_http", "Robot won't send your {name} key over plain http: use an https address, or remove the key.",
        "Robot n'enverra pas ta clé {name} en http non chiffré : utilise une adresse https, ou retire la clé.")

    // Personal: what a conversation holds that is personal ("It contains …", "Tap Send to include …").
    val personalScreen = s("personal_screen", "screen text", "le texte de l'écran")
    val personalPhoto = s("personal_photo", "a photo", "une photo")
    val personalCalendar = s("personal_calendar", "your calendar", "ton agenda")
    val personalNotes = s("personal_notes", "your notes", "tes notes")
    val personalContacts = s("personal_contacts", "your contacts", "tes contacts")
    val personalLocation = s("personal_location", "your location", "ta position")
    val personalPhone = s("personal_phone", "your phone's status", "l'état de ton téléphone")
    val personalClaudeApp = s("personal_claude_app", "text from the Claude app", "du texte de l'appli Claude")
    val personalRead = s("personal_read", "what Robot read on your phone", "ce que Robot a lu sur ton téléphone")
    val personalConversation = s("personal_conversation", "a recorded conversation", "une conversation enregistrée")
    val personalMemory = s("personal_memory", "what Robot remembers about you", "ce que Robot retient de toi")
    val personalMessages = s("personal_messages", "messages you received", "des messages que tu as reçus")
    val personalTermux = s("personal_termux", "your Termux output", "la sortie de Termux")
    val personalFiles = s("personal_files", "your files", "tes fichiers")

    // HelperContext: what an offer's tap sends (its card's "Sends: …"). The first message also carries today's date and
    // your country (HelperPrompt.context), so the first line says so.
    val sendsQuestion = s("sends_question", "Your question, today's date and your country", "Ta question, la date d'aujourd'hui et ton pays")
    val sendsQuestionDate = s("sends_question_date", "Your question and today's date", "Ta question et la date d'aujourd'hui")
    val sendsEarlier = s("sends_earlier", "The last turns of this conversation", "Les derniers échanges de cette conversation")
    val sendsScreen = s("sends_screen", "Your screen's text", "Le texte de ton écran")
    val sendsPhoto = s("sends_photo", "The photo", "La photo")
    val sendsScreenshot = s("sends_screenshot", "A screenshot", "Une capture d'écran")

    // WP2b, second part: the words core gives Settings › Helpers and the connection window (shown in the menus' language).
    // Claude's models (core AnthropicModel): what each is for and its published price per million tokens.
    val modelOpus = s("model_opus", "Recommended for most questions: \$4 per million tokens in, \$20 out.",
        "Recommandé pour la plupart des questions : 4 \$ US par million de jetons en entrée, 20 \$ US en sortie.")
    val modelSonnet = s("model_sonnet", "Fast and capable: \$2 in, \$10 out.", "Rapide et compétent : 2 \$ US en entrée, 10 \$ US en sortie.")
    val modelHaiku = s("model_haiku", "The fastest and cheapest: \$0.10 in, \$0.50 out.",
        "Le plus rapide et le moins cher : 0,10 \$ US en entrée, 0,50 \$ US en sortie.")
    val modelFable = s("model_fable", "For the hardest questions, slower: \$10 in, \$50 out.",
        "Pour les questions les plus difficiles, plus lent : 10 \$ US en entrée, 50 \$ US en sortie.")

    // How hard a helper thinks (HelperEffortSetting).
    val effortAuto = s("effort_auto", "Automatic", "Automatique")
    val effortLow = s("effort_low", "Low", "Faible")
    val effortMedium = s("effort_medium", "Medium", "Moyen")
    val effortHigh = s("effort_high", "High", "Élevé")

    // A helper's Kokoro voice (ClaudeVoices): the voice's own name, then what it sounds like.
    val voiceMichael = s("voice_michael", "Michael (American, male)", "Michael (américain, homme)")
    val voiceFenrir = s("voice_fenrir", "Fenrir (American, male)", "Fenrir (américain, homme)")
    val voicePuck = s("voice_puck", "Puck (American, male)", "Puck (américain, homme)")
    val voiceEmma = s("voice_emma", "Emma (British, female)", "Emma (britannique, femme)")
    val voiceBella = s("voice_bella", "Bella (American, female)", "Bella (américaine, femme)")
    val voiceHeart = s("voice_heart", "Heart (American, female)", "Heart (américaine, femme)")

    // Which way a question to Claude goes (AskRoute).
    val routeApp = s("route_app", "Claude app", "Appli Claude")
    val routeAppDetail = s("route_app_detail", "Opens the Claude app with your question; uses your claude.ai plan. Robot doesn't hear the answer.",
        "Ouvre l'appli Claude avec ta question; utilise ton forfait claude.ai. Robot n'entend pas la réponse.")
    val routeApi = s("route_api", "Claude API", "API Claude")
    val routeApiDetail = s("route_api_detail", "Robot asks Claude itself and speaks the answer. Needs an API key with credits.",
        "Robot demande à Claude lui-même et dit la réponse. Il faut une clé d'API avec des crédits.")

    // When Robot needs help (Escalation): core's titles speak as you ("Ask me first"); [escalationAskFirst] is Settings' word.
    val escalationOff = s("escalation_off", "Off", "Désactivé")
    val escalationOffDetail = s("escalation_off_detail", "Robot never suggests a helper.", "Robot ne suggère jamais d'IA externe.")
    val escalationAsk = s("escalation_ask", "Ask me first", "Me demander d'abord")
    val escalationAskFirst = s("escalation_ask_first", "Ask first", "Demander d'abord")
    val escalationAskDetail = s("escalation_ask_detail", "Robot offers to check with the helper and waits for a yes or a tap.",
        "Robot propose de vérifier avec l'IA externe et attend ton oui ou que tu touches la carte.")
    val escalationAuto = s("escalation_auto", "Automatic", "Automatique")
    val escalationAutoDetail = s("escalation_auto_detail",
        "Robot checks with the helper by itself for hard questions (only through its API, at a price it knows).",
        "Robot vérifie de lui-même avec l'IA externe pour les questions difficiles (seulement par son API, à un prix qu'il connaît).")

    // The presets (core helpers Presets): who a helper is, what its key costs, what the company does with what you send.
    val companyHome = s("company_home", "Your own server", "Ton propre serveur")
    val companyOther = s("company_other", "Another company", "Une autre entreprise")
    val titleOpenRouter = s("title_openrouter", "OpenRouter (hundreds of models)", "OpenRouter (des centaines de modèles)")
    val titleHome = s("title_home", "Your own server (Ollama, LM Studio, llama.cpp, vLLM…)", "Ton propre serveur (Ollama, LM Studio, llama.cpp, vLLM…)")
    val titleOther = s("title_other", "Another company (OpenAI-compatible)", "Une autre entreprise (compatible OpenAI)")
    val billingAnthropic = s("billing_anthropic", "API use is paid with API credits, which a claude.ai Pro or Max plan doesn't include.",
        "L'utilisation de l'API se paie avec des crédits d'API, qu'un forfait Pro ou Max de claude.ai n'inclut pas.")
    val billingOpenAi = s("billing_openai", "API use is paid separately from a ChatGPT subscription.",
        "L'utilisation de l'API se paie à part d'un abonnement ChatGPT.")
    val billingGemini = s("billing_gemini", "API use is paid separately from a Gemini app subscription.",
        "L'utilisation de l'API se paie à part d'un abonnement à l'appli Gemini.")
    val billingMistral = s("billing_mistral",
        "Mistral's per-model prices aren't readable outside a browser, so Robot can't estimate them: enter them below from mistral.ai/pricing.",
        "Les prix de Mistral par modèle ne se lisent que dans un navigateur, alors Robot ne peut pas les estimer : entre-les plus bas à partir de mistral.ai/pricing.")
    val billingDeepSeek = s("billing_deepseek",
        "Pay as you go from a balance you top up. Prices double on weekdays from 01:00 to 04:00 and 06:00 to 10:00 UTC.",
        "Paiement à l'utilisation, à partir d'un solde que tu recharges. Les prix doublent en semaine de 1 h à 4 h et de 6 h à 10 h UTC.")
    val billingOpenRouter = s("billing_openrouter",
        "OpenRouter reports what each answer cost, so the cost line is its own figure. Its web search may use another search company (Exa) when the model has none.",
        "OpenRouter indique le coût de chaque réponse, alors la ligne de coût affiche son propre montant. Sa recherche web peut passer par une autre entreprise de recherche (Exa) quand le modèle n'en a pas.")
    val dataUseGemini = s("data_use_gemini",
        "On Google's free tier, what you send is used to improve Google's products (its pricing page says “Used to improve our products: Yes”); on the paid tier it isn't. Robot's cost line assumes the paid tier.",
        "Avec le niveau gratuit de Google, ce que tu envoies sert à améliorer les produits de Google (sa page de prix le dit); avec le niveau payant, non. La ligne de coût de Robot suppose le niveau payant.")
    val dataUseOpenRouter = s("data_use_openrouter",
        "Robot asks OpenRouter to use only providers that keep nothing (zero data retention); you can turn that off below.",
        "Robot demande à OpenRouter de n'utiliser que des fournisseurs qui ne conservent rien (aucune conservation des données); tu peux désactiver ça plus bas.")
    val searchNoteGemini = s("search_note_gemini", "Google's search grounding isn't available through this connection.",
        "L'ancrage avec la recherche Google n'est pas offert par cette connexion.")
    val searchNoteMistral = s("search_note_mistral", "Mistral's web search works only through its Agents and Conversations APIs, not this connection.",
        "La recherche web de Mistral ne fonctionne que par ses API Agents et Conversations, pas par cette connexion.")
    val searchNoteDeepSeek = s("search_note_deepseek", "DeepSeek's API has no web search.", "L'API de DeepSeek n'a pas de recherche web.")
    val searchNoteGroq = s("search_note_groq",
        "Groq doesn't publish its browser search price where Robot can check it, so Robot leaves search off rather than guess the cost.",
        "Groq ne publie pas le prix de sa recherche par navigateur là où Robot peut le vérifier, alors Robot laisse la recherche désactivée plutôt que de deviner le coût.")
    val searchNoteHome = s("search_note_home", "A server on your network has no web search Robot can use.",
        "Un serveur sur ton réseau n'a pas de recherche web que Robot peut utiliser.")
    val searchNoteOther = s("search_note_other", "Robot doesn't know this company's web search.", "Robot ne connaît pas la recherche web de cette entreprise.")

    // The presets' models: what each is for and its published price per million tokens.
    val modelGptSol = s("model_gpt_sol", "Fast and capable: \$2 per million tokens in, \$10 out.",
        "Rapide et compétent : 2 \$ US par million de jetons en entrée, 10 \$ US en sortie.")
    val modelGptAstra = s("model_gpt_astra", "OpenAI's most capable: \$10 in, \$50 out.", "Le plus compétent d'OpenAI : 10 \$ US en entrée, 50 \$ US en sortie.")
    val modelGptLuna = s("model_gpt_luna", "The fastest and cheapest: \$0.10 in, \$0.50 out.",
        "Le plus rapide et le moins cher : 0,10 \$ US en entrée, 0,50 \$ US en sortie.")
    val modelGeminiFlash = s("model_gemini_flash", "Fast and capable: \$0.75 per million tokens in, \$3.75 out until 31 Dec 2026, then double.",
        "Rapide et compétent : 0,75 \$ US par million de jetons en entrée, 3,75 \$ US en sortie jusqu'au 31 décembre 2026, puis le double.")
    val modelGeminiPro = s("model_gemini_pro", "Google's most capable: \$2 in, \$12 out.", "Le plus compétent de Google : 2 \$ US en entrée, 12 \$ US en sortie.")
    val modelGeminiLite = s("model_gemini_lite", "The cheapest: \$0.30 in, \$2.50 out.", "Le moins cher : 0,30 \$ US en entrée, 2,50 \$ US en sortie.")
    val modelMistralMedium = s("model_mistral_medium", "Mistral's choice for most tasks.", "Le choix de Mistral pour la plupart des tâches.")
    val modelMistralLarge = s("model_mistral_large", "The largest.", "Le plus gros.")
    val modelMistralSmall = s("model_mistral_small", "For cost-sensitive use.", "Pour un usage économique.")
    val modelDeepSeekFlash = s("model_deepseek_flash", "Fast: \$0.15 per million tokens in, \$0.60 out (off-peak).",
        "Rapide : 0,15 \$ US par million de jetons en entrée, 0,60 \$ US en sortie (heures creuses).")
    val modelDeepSeekPro = s("model_deepseek_pro", "More capable: \$0.66 in, \$1.98 out (off-peak).",
        "Plus compétent : 0,66 \$ US en entrée, 1,98 \$ US en sortie (heures creuses).")
    val modelGptOss120 = s("model_gpt_oss_120", "Very fast: \$0.15 per million tokens in, \$0.60 out.",
        "Très rapide : 0,15 \$ US par million de jetons en entrée, 0,60 \$ US en sortie.")
    val modelGptOss20 = s("model_gpt_oss_20", "Faster and cheaper: \$0.075 in, \$0.30 out.", "Plus rapide et moins cher : 0,075 \$ US en entrée, 0,30 \$ US en sortie.")
    val modelQwen = s("model_qwen", "\$0.80 in, \$4 out; can see pictures.", "0,80 \$ US en entrée, 4 \$ US en sortie; voit les images.")
    val modelGrok = s("model_grok", "\$2 per million tokens in, \$6 out; web search \$5 per 1,000.",
        "2 \$ US par million de jetons en entrée, 6 \$ US en sortie; recherche web à 5 \$ US par tranche de mille.")

    // What a helper may be called (core helpers HelperNames): why a name can't be used ({word}: one of its words).
    val nameWords = s("name_words", "Give it a name of one to three words.", "Donne-lui un nom d'un à trois mots.")
    val nameLetters = s("name_letters", "Use letters, digits and spaces only.", "Utilise seulement des lettres, des chiffres et des espaces.")
    val nameWake = s("name_wake", "“{word}” is in a wake phrase: Robot would mix them up.", "« {word} » fait partie d'un de tes mots d'éveil : Robot les confondrait.")
    val nameCommand = s("name_command", "“{word}” is a word Robot listens for in commands.", "« {word} » est un mot que Robot écoute dans les commandes.")
    val nameTaken = s("name_taken", "{name} is already called that.", "{name} porte déjà ce nom.")
    val nameSoundsLike = s("name_sounds_like", "That sounds like {name}: Robot couldn't tell them apart.",
        "Ça ressemble trop à {name} : Robot ne pourrait pas les distinguer.")
    val nameCommon = s("name_common", "“{name}” is a common word or name: “ask {name} …” will go to this helper, never to someone called {name}.",
        "« {name} » est un mot ou un nom courant : « demande à {name} … » ira à cette IA externe, jamais à quelqu'un qui s'appelle {name}.")

    // A key's shape (core helpers HelperKeyFormat), in the connection window.
    val keyPrefix = s("key_prefix", "{company} keys usually start with {prefix}. Robot will try this one anyway.",
        "Les clés {company} commencent habituellement par {prefix}. Robot va quand même essayer celle-ci.")
    val keyNotAnthropic = s("key_not_anthropic", "That doesn't look like an Anthropic API key (sk-ant- followed by letters, digits, - and _).",
        "Ça ne ressemble pas à une clé d'API Anthropic (sk-ant- suivi de lettres, de chiffres, de - et de _).")
    val keyNotKey = s("key_not_key", "That doesn't look like an API key: it should be 8 to 512 letters, digits and symbols, with no spaces.",
        "Ça ne ressemble pas à une clé d'API : elle devrait compter de 8 à 512 lettres, chiffres et symboles, sans espaces.")

    // Your server's address (core helpers LanRule): what kind of address or name it is.
    val lanLinkLocal = s("lan_link_local", "This is a link-local address, which only works on a direct connection.",
        "C'est une adresse link-local, qui ne fonctionne qu'avec une connexion directe.")
    val lanCgnat = s("lan_cgnat", "This is a 100.64–100.127 address: private only through Tailscale (on mobile data without it, this space is the carrier's).",
        "C'est une adresse 100.64–100.127 : privée seulement par Tailscale (sur les données mobiles sans Tailscale, cette plage appartient à l'opérateur).")
    val lanTailscale = s("lan_tailscale", "This is a Tailscale name: it resolves only while Tailscale is on.",
        "C'est un nom Tailscale : il ne se résout que lorsque Tailscale est activé.")
    val lanDotLocal = s("lan_dot_local", "This is a .local name: if it doesn't resolve on this phone, use the server's IP address or its Tailscale name.",
        "C'est un nom en .local : s'il ne se résout pas sur ce téléphone, utilise l'adresse IP du serveur ou son nom Tailscale.")
    val lanRouter = s("lan_router", "This is a name your router gives out: it works only on that network.",
        "C'est un nom donné par ton routeur : il ne fonctionne que sur ce réseau.")

    // Prices (core helpers Pricing): the cost line when Robot can't tell, and the date a price table was read
    // ({day} {month} {year}: "11 Oct 2026", « 11 octobre 2026 »).
    val costUnknown = s("cost_unknown", "cost unknown", "coût inconnu")
    val date = s("date", "{day} {month} {year}", "{day} {month} {year}")
    /** The first of a month: « 1er janvier 2027 ». */
    val dayFirst = s("day_first", "1", "1er")
    val month1 = s("month_1", "Jan", "janvier")
    val month2 = s("month_2", "Feb", "février")
    val month3 = s("month_3", "Mar", "mars")
    val month4 = s("month_4", "Apr", "avril")
    val month5 = s("month_5", "May", "mai")
    val month6 = s("month_6", "Jun", "juin")
    val month7 = s("month_7", "Jul", "juillet")
    val month8 = s("month_8", "Aug", "août")
    val month9 = s("month_9", "Sep", "septembre")
    val month10 = s("month_10", "Oct", "octobre")
    val month11 = s("month_11", "Nov", "novembre")
    val month12 = s("month_12", "Dec", "décembre")
    val months: List<Msg> = listOf(month1, month2, month3, month4, month5, month6, month7, month8, month9, month10, month11, month12)

    // The :claude module's SearchClient: Settings' Test of a search service that isn't a SearXNG.
    val webTestAnswered = plural("web_test_answered", "n", Plural("{service} answered: {n} result", "{service} answered: {n} results"),
        Plural("{service} a répondu : {n} résultat", "{service} a répondu : {n} résultats"))
}
