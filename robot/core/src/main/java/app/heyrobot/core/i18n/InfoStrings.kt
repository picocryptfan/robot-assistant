// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP1's catalog: the read tools' answers and their cards: the calendar and its dates and times (Calendar.kt, When.kt),
// the weather (Weather.kt), the nearest place (Nearby.kt), the phone's status (PhoneStatus.kt), freeing memory and
// storage (FreeUp.kt), contacts and texting (People.kt, PeopleStore.kt, Texting.kt). Ids start with
// "info."; the area is "cards". Québec French (tu), the glossary's words.

object InfoStrings : Strings("info", area = "cards") {
    // A date, as the cards and the calendar's lines write it: "Tue Oct 14" (1.0's short form), « mardi 14 octobre » (French
    // writes it out: an abbreviation's period would end a sentence twice).
    val dayShort = s("day_short", "{weekday} {month} {day}", "{weekday} {day} {month}")
    val dayShortYear = s("day_short_year", "{weekday} {month} {day} {year}", "{weekday} {day} {month} {year}")
    val firstOfMonth = s("first_of_month", "1", "1er")
    val mon = s("mon", "Mon", "lundi")
    val tue = s("tue", "Tue", "mardi")
    val wed = s("wed", "Wed", "mercredi")
    val thu = s("thu", "Thu", "jeudi")
    val fri = s("fri", "Fri", "vendredi")
    val sat = s("sat", "Sat", "samedi")
    val sun = s("sun", "Sun", "dimanche")
    val jan = s("jan", "Jan", "janvier")
    val feb = s("feb", "Feb", "février")
    val mar = s("mar", "Mar", "mars")
    val apr = s("apr", "Apr", "avril")
    val may = s("may", "May", "mai")
    val jun = s("jun", "Jun", "juin")
    val jul = s("jul", "Jul", "juillet")
    val aug = s("aug", "Aug", "août")
    val sep = s("sep", "Sep", "septembre")
    val oct = s("oct", "Oct", "octobre")
    val nov = s("nov", "Nov", "novembre")
    val dec = s("dec", "Dec", "décembre")

    // A time of day: "07:30" on a 24-hour clock in English as 1.0 wrote it, « 7 h 30 » and « 7 h » in French.
    val clock = s("clock", "{h}:{m}", "{h} h {m}")
    val clockHour = s("clock_hour", "{h}:00", "{h} h")
    val hour0 = s("hour_0", "00", "0")
    val hour1 = s("hour_1", "01", "1")
    val hour2 = s("hour_2", "02", "2")
    val hour3 = s("hour_3", "03", "3")
    val hour4 = s("hour_4", "04", "4")
    val hour5 = s("hour_5", "05", "5")
    val hour6 = s("hour_6", "06", "6")
    val hour7 = s("hour_7", "07", "7")
    val hour8 = s("hour_8", "08", "8")
    val hour9 = s("hour_9", "09", "9")

    // When an event is (an event's card, the calendar's lines).
    val allDay = s("all_day", "all day", "toute la journée")
    val dayAllDay = s("day_all_day", "{day} all day", "{day} toute la journée")
    val daysAllDay = s("days_all_day", "{from} – {to} all day", "{from} – {to} toute la journée")
    val dayAllDayParen = s("day_all_day_paren", "{day} (all day)", "{day} (toute la journée)")
    val daysAllDayParen = s("days_all_day_paren", "{from} – {to} (all day)", "{from} – {to} (toute la journée)")
    val times = s("times", "{from}–{to}", "{from} à {to}")
    val dayTimes = s("day_times", "{day}, {from}–{to}", "{day}, {from} à {to}")
    val daysTimes = s("days_times", "{day}, {from} – {day2}, {to}", "{day}, {from} – {day2}, {to}")
    val dayRange = s("day_range", "{from} – {to}", "{from} – {to}")
    val noTitle = s("no_title", "(no title)", "(sans titre)")
    val eventLine = s("event_line", "{when} · {title}", "{when} · {title}")
    val eventLinePlace = s("event_line_place", "{when} · {title} ({place})", "{when} · {title} ({place})")
    val eventSpokenPlace = s("event_spoken_place", "{when} {title} ({place})", "{when} {title} ({place})")

    // read_calendar's line on its card (what the model reads is the same words in English).
    val nothingOn = s("nothing_on", "Nothing on {when}.", "Rien le {when}.")
    val nothingFrom = s("nothing_from", "Nothing from {from} – {to}.", "Rien du {from} au {to}.")
    val eventsOn = plural("events_on", "n", Plural(one = "{n} event on {when}.", other = "{n} events on {when}."),
        Plural(one = "{n} événement le {when}.", other = "{n} événements le {when}."))
    val eventsFrom = plural("events_from", "n", Plural(one = "{n} event from {from} – {to}.", other = "{n} events from {from} – {to}."),
        Plural(one = "{n} événement du {from} au {to}.", other = "{n} événements du {from} au {to}."))
    val showingOn = s("showing_on", "Showing {shown} of {total} events on {when}.", "{shown} événements affichés sur {total} le {when}.")
    val showingFrom = s("showing_from", "Showing {shown} of {total} events from {from} – {to}.", "{shown} événements affichés sur {total} du {from} au {to}.")

    // When.kt and Calendar.kt: what the model is told when a date can't be read (the card shows it too).
    val sayDate = s("say_date", "say the date like \"tomorrow 3pm\" or \"October 14 at 9\"", "dis la date comme « demain 15 h » ou « 14 octobre à 9 h »")
    val sayMonth = s("say_month", "say the month's name, like \"October 11\"", "dis le nom du mois, comme « 11 octobre »")
    val timePassed = s("time_passed", "that time has passed", "cette heure est passée")

    // PhoneStatus.kt: sizes as Android's storage settings write them ("1.2 GB", « 1,2 Go »).
    val sizeBytes = plural("size_bytes", "n", Plural(one = "{n} bytes", other = "{n} bytes"), Plural(one = "{n} octet", other = "{n} octets"))
    val sizeKb = s("size_kb", "{n} KB", "{n} ko")
    val sizeMb = s("size_mb", "{n} MB", "{n} Mo")
    val sizeGb = s("size_gb", "{n} GB", "{n} Go")

    // PhoneStatus.kt: phone_status's card (its lines are the model's answer too, in English).
    val checkStorage = s("check_storage", "Check your phone's storage", "Vérifier le stockage du téléphone")
    val checkBattery = s("check_battery", "Check your phone's battery", "Vérifier la batterie du téléphone")
    val checkMemory = s("check_memory", "Check your phone's memory", "Vérifier la mémoire du téléphone")
    val checkAll = s("check_all", "Check your phone's storage, battery and memory", "Vérifier le stockage, la batterie et la mémoire du téléphone")
    val statusLine = s("status_line", "{title}: {value}", "{title} : {value}")
    val storage = s("storage", "Storage", "Stockage")
    val battery = s("battery", "Battery", "Batterie")
    val memory = s("memory", "Memory", "Mémoire")
    val largestApps = s("largest_apps", "Largest apps", "Plus grosses applis")
    val media = s("media", "Photos, videos and audio", "Photos, vidéos et audio")
    val storageFree = s("storage_free", "{free} free of {total} ({used}% used)", "{free} libres sur {total} ({used} % utilisés)")
    val percent = s("percent", "{n}%", "{n} %")
    val batteryFull = s("battery_full", "full", "pleine")
    val batteryCharging = s("battery_charging", "charging", "en charge")
    val batteryNotCharging = s("battery_not_charging", "not charging", "pas en charge")
    val fullInMinutes = s("full_in_minutes", "full in about {n} min", "pleine dans environ {n} min")
    val fullInHours = s("full_in_hours", "full in about {n} h", "pleine dans environ {n} h")
    val celsius = s("celsius", "{n}°C", "{n} °C")
    val fahrenheit = s("fahrenheit", "{n}°F", "{n} °F")
    val memoryAvailable = s("memory_available", "{available} available of {total}", "{available} disponibles sur {total}")
    val memoryAvailableLow = s("memory_available_low", "{available} available of {total} (Android says memory is low)",
        "{available} disponibles sur {total} (Android dit que la mémoire est presque pleine)")
    val memoryModel = s("memory_model", "{memory}; Robot's own model holds about {size} while loaded",
        "{memory}; le modèle de Robot en occupe environ {size} quand il est chargé")
    val appSize = s("app_size", "{appName} {size}", "{appName} {size}")
    val appSizeCache = s("app_size_cache", "{appName} {size} (cache {cache})", "{appName} {size} (cache {cache})")
    val mediaSizes = s("media_sizes", "photos {photos}, videos {videos}, audio {audio}", "photos {photos}, vidéos {videos}, audio {audio}")
    val appsUnknown = s("apps_unknown", "unknown: Robot needs Usage access to see which apps use the most space (the card has a button for it)",
        "inconnu : Robot a besoin de l'accès aux données d'utilisation pour voir quelles applis prennent le plus d'espace (la carte a un bouton pour ça)")

    // SettingsPage: the buttons that open a page (the menus' language).
    val openStorageSettings = s("open_storage_settings", "Open storage settings", "Ouvrir les paramètres de stockage")
    val turnOnUsageAccess = s("turn_on_usage_access", "Turn on Usage access", "Activer l'accès aux données d'utilisation")
    val openFreeUpSpace = s("open_free_up_space", "Open Free up space", "Ouvrir Libérer de l'espace")
    val openPage = s("open_page", "Open {page}", "Ouvrir {page}")

    // A decimal as 1.0 wrote it ("1.0", "0.25"): its point is a comma in French. Used through [decimal].
    val decimalPoint = s("decimal_point", ".", ",")

    // Nearby.kt: the nearest place online, and why it couldn't be found (the card's line; fragments of whole sentences).
    val nearbyNoInternet = s("nearby_no_internet", "No internet: Robot couldn't reach {server}", "Pas d'Internet : Robot n'a pas pu joindre {server}")
    val nearbyNoNetwork = s("nearby_no_network", "Robot isn't allowed to use the network", "Robot n'a pas le droit d'utiliser le réseau")
    val nearbyBusy = s("nearby_busy", "OpenStreetMap's search is busy just now", "La recherche d'OpenStreetMap est occupée en ce moment")
    val nearbyServerBusy = s("nearby_server_busy", "{server} is busy just now", "{server} est occupé en ce moment")
    val nearbyService = s("nearby_service", "OpenStreetMap's search didn't answer properly", "La recherche d'OpenStreetMap n'a pas répondu correctement")
    val nearbyServerService = s("nearby_server_service", "{server} didn't answer properly", "{server} n'a pas répondu correctement")
    val nearbyNothing = s("nearby_nothing", "OpenStreetMap knows no such place near you", "OpenStreetMap ne connaît aucun endroit de ce genre près de toi")
    val nearbyNoLocation = s("nearby_no_location", "Robot couldn't tell where you are", "Robot n'a pas pu savoir où tu es")
    val nearbyNotAllowed = s("nearby_not_allowed", "Robot may not see where you are (allow location in the Robot app)",
        "Robot n'a pas le droit de voir où tu es (autorise la localisation dans l'appli Robot)")
    val nearbyTimeout = s("nearby_timeout", "Finding it online took too long", "La recherche en ligne a pris trop de temps")
    val nearbyStopped = s("nearby_stopped", "Stopped", "Arrêté")
    val nominatimOsm = s("nominatim_osm", "OpenStreetMap's Nominatim", "le serveur Nominatim d'OpenStreetMap")
    val nominatimAt = s("nominatim_at", "the Nominatim server at {server}", "le serveur Nominatim à {server}")
    val serverTooLong = s("server_too_long", "That address is too long.", "Cette adresse est trop longue.")
    val serverHttps = s("server_https", "Use an {scheme} address: the words and your area only go encrypted.",
        "Utilise une adresse {scheme} : les mots et ta zone ne partent que chiffrés.")
    val serverSpaces = s("server_spaces", "An address has no spaces.", "Une adresse n'a pas d'espaces.")
    val serverQuery = s("server_query", "Type the server's address without a search (nothing after ? or #).",
        "Tape l'adresse du serveur sans recherche (rien après ? ou #).")
    val serverUser = s("server_user", "Type the server's address without a user name.", "Tape l'adresse du serveur sans nom d'utilisateur.")
    val serverPort = s("server_port", "The port must be a number from 1 to 65535.", "Le port doit être un nombre de 1 à 65535.")
    val serverHost = s("server_host", "That isn't a server address Robot can use (a name like nominatim.example.org, or an IPv4 address).",
        "Ce n'est pas une adresse de serveur que Robot peut utiliser (un nom comme nominatim.example.org, ou une adresse IPv4).")
    val serverPath = s("server_path", "The path after the server's name may hold only letters, digits, dots, dashes and underscores.",
        "Le chemin après le nom du serveur ne peut contenir que des lettres, des chiffres, des points, des tirets et des traits de soulignement.")
    val justThisTime = s("just_this_time", "Just this time", "Juste cette fois")
    val noSearchMap = s("no_search_map", "No, search the map", "Non, chercher sur la carte")
    val dontAskAgain = s("dont_ask_again", "Don't ask again", "Ne plus demander")
    val no = s("no", "No", "Non")
    val nearbyConsent = s("nearby_consent",
        "To find the closest one, Robot would send “{what}” and your area (about 1 km) to {server}. Nothing else leaves the phone. “{no}” uses your maps app's search instead, on the phone.",
        "Pour trouver le plus proche, Robot enverrait « {what} » et ta zone (environ 1 km) à {server}. Rien d'autre ne sort du téléphone. « {no} » utilise plutôt la recherche de ton appli de cartes, sur le téléphone.")
    val nearbyInstead = s("nearby_instead", "{why}, so Robot opened your maps app's search instead.", "{why}, alors Robot a plutôt ouvert la recherche de ton appli de cartes.")
    val nearbyInsteadRoute = s("nearby_instead_route", "{why}, so Robot opened your maps app's search instead: pick the place, then tap Route.",
        "{why}, alors Robot a plutôt ouvert la recherche de ton appli de cartes : choisis l'endroit, puis touche Itinéraire.")
    val nearbyDescribe = s("nearby_describe", "Find the nearest “{what}” online (OpenStreetMap) and open the route in your maps app",
        "Trouver en ligne le lieu « {what} » le plus proche (OpenStreetMap) et ouvrir l'itinéraire dans ton appli de cartes")
    val aboutFeet = s("about_feet", "about {n} ft", "environ {n} pi")
    val aboutMiles = s("about_miles", "about {n} mi", "environ {n} mi")
    val aboutMetres = s("about_metres", "about {n} m", "environ {n} m")
    val aboutKm = s("about_km", "about {n} km", "environ {n} km")

    /** [text], a number as 1.0's English wrote it ("1.0", "12", "-3.25"), with a decimal comma in French. */
    fun decimal(text: String): Phrase {
        val dot = text.indexOf('.')
        if (dot < 0) return Phrase.plain(text)
        return Phrase.Join(listOf(Phrase.plain(text.substring(0, dot)), decimalPoint(), Phrase.plain(text.substring(dot + 1))), "")
    }

    // Weather.kt: why the weather couldn't be had (the card's line and the model's, fixed wording).
    val weatherNoInternet = s("weather_no_internet", "No internet: Robot couldn't reach Open-Meteo, the weather service.",
        "Pas d'Internet : Robot n'a pas pu joindre Open-Meteo, le service météo.")
    val weatherNoNetwork = s("weather_no_network", "Robot isn't allowed to use the network. Turn on Network for Robot in Android's app settings.",
        "Robot n'a pas le droit d'utiliser le réseau. Active Réseau pour Robot dans les paramètres des applis d'Android.")
    val weatherBusy = s("weather_busy", "The weather service is busy just now. Try again in a while.", "Le service météo est occupé en ce moment. Réessaie dans un moment.")
    val weatherService = s("weather_service", "The weather service didn't answer properly. Try again later.",
        "Le service météo n'a pas répondu correctement. Réessaie plus tard.")
    val weatherNoPlace = s("weather_no_place", "Open-Meteo doesn't know that place. Try the town's name, or add the country.",
        "Open-Meteo ne connaît pas cet endroit. Essaie le nom de la ville, ou ajoute le pays.")
    val weatherNoLocation = s("weather_no_location", "Robot couldn't tell where you are just now. Name a town, or set a Home for weather in Robot's settings.",
        "Robot n'a pas pu savoir où tu es en ce moment. Nomme une ville, ou choisis un domicile pour la météo dans les réglages de Robot.")
    val weatherLocationOff = s("weather_location_off", "Location is off on this phone. Turn it on, name a town, or set a Home for weather in Robot's settings.",
        "La localisation est désactivée sur ce téléphone. Active-la, nomme une ville, ou choisis un domicile pour la météo dans les réglages de Robot.")
    val weatherNoDays = s("weather_no_days", "Open-Meteo has forecasts for today and the next 15 days only.",
        "Open-Meteo a des prévisions seulement pour aujourd'hui et les 15 prochains jours.")
    val weatherNoHome = s("weather_no_home", "No Home for weather is set. Set one in Robot's {page}, or name a town.",
        "Aucun domicile pour la météo n'est choisi. Choisis-en un dans {page}, ou nomme une ville.")
    val weatherTimeout = s("weather_timeout", "The weather took too long to come. Try again in a moment.", "La météo a pris trop de temps à arriver. Réessaie dans un instant.")
    val weatherBackground = s("weather_background",
        "Android didn't let Robot see where you are from the assistant. Name a town, or set a Home for weather in Robot's settings.",
        "Android n'a pas laissé Robot voir où tu es depuis l'assistant. Nomme une ville, ou choisis un domicile pour la météo dans les réglages de Robot.")
    val notThere = s("not_there", "Open-Meteo knows places called {name}, but none in {where} among the biggest. Try the nearest larger town.",
        "Open-Meteo connaît des endroits appelés {name}, mais aucun en {where} parmi les plus grands. Essaie la plus grande ville la plus proche.")
    val sayDay = s("say_day", "say the day like today, tomorrow, Friday or this week", "dis le jour, comme aujourd'hui, demain, vendredi ou cette semaine")
    val noPastWeather = s("no_past_weather", "Robot has forecasts only, not past weather", "Robot a seulement des prévisions, pas la météo passée")

    // Weather.kt: WMO weather codes in words.
    val wmoClear = s("wmo_clear", "clear", "dégagé")
    val wmoMostlyClear = s("wmo_mostly_clear", "mostly clear", "généralement dégagé")
    val wmoPartlyCloudy = s("wmo_partly_cloudy", "partly cloudy", "partiellement nuageux")
    val wmoOvercast = s("wmo_overcast", "overcast", "couvert")
    val wmoFog = s("wmo_fog", "fog", "brouillard")
    val wmoFreezingFog = s("wmo_freezing_fog", "freezing fog", "brouillard givrant")
    val wmoLightDrizzle = s("wmo_light_drizzle", "light drizzle", "bruine légère")
    val wmoDrizzle = s("wmo_drizzle", "drizzle", "bruine")
    val wmoHeavyDrizzle = s("wmo_heavy_drizzle", "heavy drizzle", "forte bruine")
    val wmoLightFreezingDrizzle = s("wmo_light_freezing_drizzle", "light freezing drizzle", "bruine verglaçante légère")
    val wmoFreezingDrizzle = s("wmo_freezing_drizzle", "freezing drizzle", "bruine verglaçante")
    val wmoLightRain = s("wmo_light_rain", "light rain", "pluie légère")
    val wmoRain = s("wmo_rain", "rain", "pluie")
    val wmoHeavyRain = s("wmo_heavy_rain", "heavy rain", "forte pluie")
    val wmoLightFreezingRain = s("wmo_light_freezing_rain", "light freezing rain", "pluie verglaçante légère")
    val wmoFreezingRain = s("wmo_freezing_rain", "freezing rain", "pluie verglaçante")
    val wmoLightSnow = s("wmo_light_snow", "light snow", "neige légère")
    val wmoSnow = s("wmo_snow", "snow", "neige")
    val wmoHeavySnow = s("wmo_heavy_snow", "heavy snow", "forte neige")
    val wmoSnowGrains = s("wmo_snow_grains", "snow grains", "neige en grains")
    val wmoLightShowers = s("wmo_light_showers", "light showers", "faibles averses")
    val wmoShowers = s("wmo_showers", "showers", "averses")
    val wmoHeavyShowers = s("wmo_heavy_showers", "heavy showers", "fortes averses")
    val wmoLightSnowShowers = s("wmo_light_snow_showers", "light snow showers", "faibles averses de neige")
    val wmoSnowShowers = s("wmo_snow_showers", "snow showers", "averses de neige")
    val wmoThunderstorms = s("wmo_thunderstorms", "thunderstorms", "orages")
    val wmoThunderstormsHail = s("wmo_thunderstorms_hail", "thunderstorms with hail", "orages avec grêle")
    val wmoThunderstormsHeavyHail = s("wmo_thunderstorms_heavy_hail", "thunderstorms with heavy hail", "orages avec forte grêle")

    // Weather.kt: a day's line on the weather card ("tomorrow, Sun Oct 11: overcast, high 13°C, low 3°C, …").
    val dayLine = s("day_line", "{day}: {weather}", "{day} : {weather}")
    val today = s("today", "today, {day}", "aujourd'hui, {day}")
    val tomorrow = s("tomorrow", "tomorrow, {day}", "demain, {day}")
    val high = s("high", "high {t}", "max. {t}")
    val low = s("low", "low {t}", "min. {t}")
    val windUpTo = s("wind_up_to", "wind up to {n} {unit}", "vent jusqu'à {n} {unit}")
    val windUpToGusts = s("wind_up_to_gusts", "wind up to {n} {unit} (gusts {gusts})", "vent jusqu'à {n} {unit} (rafales {gusts})")
    val cloudCover = s("cloud_cover", "cloud cover {n}%", "couverture nuageuse {n} %")
    val now = s("now", "now {t}", "maintenant {t}")
    val nowAnd = s("now_and", "now {t} and {conditions}", "maintenant {t} et {conditions}")
    val kindRain = s("kind_rain", "rain", "pluie")
    val kindSnow = s("kind_snow", "snow", "neige")
    val kindRainAndSnow = s("kind_rain_and_snow", "rain and snow", "pluie et neige")
    val amount = s("amount", "{n} {unit}", "{n} {unit}")
    val amountOfRain = s("amount_of_rain", "{n} {unit} of rain", "{n} {unit} de pluie")
    val amountOfSnow = s("amount_of_snow", "{n} {unit} of snow", "{n} {unit} de neige")
    val inches = s("inches", "in", "po")
    val about = s("about", "about {amounts}", "environ {amounts}")
    val aboutOf = s("about_of", "about {amounts} of {kind}", "environ {amounts} de {kind}")
    val chanceOf = s("chance_of", "{p}% chance of {kind}", "{p} % de risque de {kind}")
    val chanceOfAbout = s("chance_of_about", "{p}% chance of {kind} ({about})", "{p} % de risque de {kind} ({about})")
    val dry = s("dry", "dry", "sec")

    // FreeUp.kt: free_storage's choices and lines.
    val aVideo = s("a_video", "A video", "Une vidéo")
    val aPhoto = s("a_photo", "A photo", "Une photo")
    val date = s("date", "{month} {day}, {year}", "{day} {month} {year}")
    val deleteVideo = s("delete_video", "Delete a video ({details})", "Supprimer une vidéo ({details})")
    val deletePhoto = s("delete_photo", "Delete a photo ({details})", "Supprimer une photo ({details})")
    val appChoice = s("app_choice", "{appName} · {size}", "{appName} · {size}")
    val appChoiceCache = s("app_choice_cache", "{appName} · {size}, cache {cache}", "{appName} · {size}, cache {cache}")
    val robotModels = s("robot_models", "Robot (models, don't clear)", "Robot (modèles, ne pas vider)")
    val deleteWaiting = s("delete_waiting", "Waiting for your answer in Android's confirmation: it's deleted only if you allow it there.",
        "En attente de ta réponse dans la confirmation d'Android : c'est supprimé seulement si tu l'autorises là.")
    val deleted = s("deleted", "Deleted.", "Supprimé.")
    val notDeleted = s("not_deleted", "Not deleted: you didn't allow it.", "Pas supprimé : tu ne l'as pas autorisé.")

    // FreeUp.kt: free_memory's choices and lines, and "free up memory".
    val freed = s("freed", "Freed about {freed}: {available} of memory available now.", "Environ {freed} libérés : {available} de mémoire disponibles maintenant.")
    val notFreedYet = s("not_freed_yet", "Robot's model is unloaded. Android hasn't counted the memory as free yet ({available} available).",
        "Le modèle de Robot est déchargé. Android n'a pas encore compté la mémoire comme libre ({available} disponibles).")
    val unloading = s("unloading", "Robot's model unloads when this answer ends (about 2 GB, 4 GB for an E4B model).",
        "Le modèle de Robot se décharge à la fin de cette réponse (environ 2 Go, 4 Go pour un modèle E4B).")
    val justNow = s("just_now", "just now", "à l'instant")
    val agoMinutes = s("ago_minutes", "{n} min ago", "il y a {n} min")
    val agoHours = s("ago_hours", "{h} h ago", "il y a {h} h")
    val agoHoursMinutes = s("ago_hours_minutes", "{h} h {m} ago", "il y a {h} h {m}")
    val memoryFree = s("memory_free", "{available} of {total} of memory is free.", "{available} libres sur {total} de mémoire.")
    val memoryFreeLow = s("memory_free_low", "{available} of {total} of memory is free (Android says memory is low).",
        "{available} libres sur {total} de mémoire (Android dit que la mémoire est presque pleine).")
    val unloadNotLoaded = s("unload_not_loaded", "Unload Robot's model (not loaded now)", "Décharger le modèle de Robot (pas chargé en ce moment)")
    val unloadAbout = s("unload_about", "Unload Robot's model · about {size}", "Décharger le modèle de Robot · environ {size}")
    val recentApp = s("recent_app", "{appName} · used {ago}", "{appName} · utilisée {ago}")
    val recentAppOnScreen = s("recent_app_on_screen", "{appName} · used {ago} · {n} min on screen today", "{appName} · utilisée {ago} · {n} min à l'écran aujourd'hui")
    val turnOnUsageAccessToSee = s("turn_on_usage_access_to_see", "Turn on Usage access, to see the apps you used lately",
        "Activer l'accès aux données d'utilisation, pour voir les applis utilisées récemment")
    val pickNote = s("pick_note",
        "Tap one: Robot's model unloads when this answer ends; an app opens its App info, where you tap Force stop. Robot never stops an app itself.",
        "Touche un choix : le modèle de Robot se décharge à la fin de cette réponse; une appli ouvre sa page Infos sur l'appli, où tu touches Forcer l'arrêt. Robot n'arrête jamais une appli lui-même.")
    val modelNotLoaded = s("model_not_loaded", "Robot's model isn't loaded now.", "Le modèle de Robot n'est pas chargé en ce moment.")
    val unloadingFrees = s("unloading_frees", "Unloading Robot's model frees about {size} at once; it loads again for your next question.",
        "Décharger le modèle de Robot libère environ {size} tout de suite; il se recharge à la prochaine question.")
    val withUsageAccess = s("with_usage_access", "With Usage access, Robot could also name the apps you used lately, so you can stop one: tap the button to turn it on.",
        "Avec l'accès aux données d'utilisation, Robot pourrait aussi nommer les applis que tu as utilisées récemment, pour en arrêter une : touche le bouton pour l'activer.")
    val noOtherApp = s("no_other_app", "No other app was used in the last 3 hours.", "Aucune autre appli n'a servi dans les 3 dernières heures.")
    val recentApps = s("recent_apps", "Apps you used lately that are likely still in memory: {appNames}. Tap one to open its App info, where you can force stop it.",
        "Les applis utilisées récemment, sans doute encore en mémoire : {appNames}. Touches-en une pour ouvrir sa page Infos sur l'appli, où tu peux forcer l'arrêt.")
    // The panel's "free up memory" (PanelFreeMemory): the apps are named only on the card's buttons, never in the reply.
    val recentAppsOnCard = plural("recent_apps_on_card", "n",
        Plural(one = "{n} app you used lately is likely still in memory: its button is on the card. Tap it to open its App info, where you can force stop it.",
            other = "{n} apps you used lately are likely still in memory: each has a button on the card. Tap one to open its App info, where you can force stop it."),
        Plural(one = "{n} appli utilisée récemment est sans doute encore en mémoire : son bouton est sur la carte. Touche-le pour ouvrir sa page Infos sur l'appli, où tu peux forcer l'arrêt.",
            other = "{n} applis utilisées récemment sont sans doute encore en mémoire : chacune a son bouton sur la carte. Touches-en une pour ouvrir sa page Infos sur l'appli, où tu peux forcer l'arrêt."))
    val neverStops = s("never_stops", "Robot never stops an app itself, and Android frees memory by itself when it needs to.",
        "Robot n'arrête jamais une appli lui-même, et Android libère de la mémoire tout seul quand il en a besoin.")
    val unlockToFree = s("unlock_to_free", "Unlock your phone to see what you can unload or stop.", "Déverrouille ton téléphone pour voir ce que tu peux décharger ou arrêter.")

    // People.kt and PeopleStore.kt: a text's or an email's card, and where a person came from.
    val learnedNote = s("learned_note",
        "This name was seen in a notification, and whoever sent it chose it. Robot treats them as someone you know only once you tick the box, or tap {keep} in {page}.",
        "Ce nom a été vu dans une notification, et la personne qui l'a envoyée l'a choisi. Robot la traite comme quelqu'un que tu connais seulement quand tu coches la case, ou que tu touches {keep} dans {page}.")
    val alwaysUseFor = s("always_use_for", "Always use this for “{name}”", "Toujours utiliser ceci pour « {name} »")
    val pickNoteCheogram = s("pick_note_cheogram",
        "“{pick}” opens Cheogram's contact list, titled “Create shortcut”: nothing goes on your home screen, and Robot remembers who you pick. “{choose}” opens its share screen instead.",
        "« {pick} » ouvre la liste de contacts de Cheogram, intitulée « Créer un raccourci » : rien ne va sur ton écran d'accueil, et Robot retient qui tu choisis. « {choose} » ouvre plutôt son écran de partage.")
    val allowNote = s("allow_note", "Robot can't read your contacts: allow it to find people there too.",
        "Robot ne peut pas lire tes contacts : autorise-le pour y trouver aussi des gens.")
    val seenInCheogram = s("seen_in_cheogram", "seen in a notification", "vu dans une notification")
    val sourceManual = s("source_manual", "Added by you", "Ajouté par toi")
    val sourceVcard = s("source_vcard", "From a contacts file", "D'un fichier de contacts")
    val sourceCheogramPick = s("source_cheogram_pick", "Picked in Cheogram", "Choisi dans Cheogram")
    val sourcePhonePicker = s("source_phone_picker", "From the phone's contacts", "Des contacts du téléphone")
    val sourceNotification = s("source_notification", "Seen in a notification", "Vu dans une notification")
    val sourceShare = s("source_share", "Shared to Robot", "Partagé avec Robot")
    val sourceRemembered = s("source_remembered", "Remembered from a card", "Retenu depuis une carte")
    val phoneMobile = s("phone_mobile", "Mobile", "Cellulaire")
    val phoneHome = s("phone_home", "Home", "Domicile")
    val phoneWork = s("phone_work", "Work", "Travail")
    val phoneOther = s("phone_other", "Other", "Autre")

    // Texting.kt: texting without the model, and lookup_contact's line when no one matched.
    val noTextTo = s("no_text_to", "Okay, no text to {name}.", "D'accord, pas de texto pour {name}.")
    val noTextToSay = s("no_text_to_say", "Okay, no text to {name}. To send “{message}”, say “text {name} {said}”.",
        "D'accord, pas de texto pour {name}. Pour envoyer « {message} », dis « texte à {name} {said} ».")
    val whatShouldTextSay = s("what_should_text_say", "What should the text to {name} say?", "Qu'est-ce que je mets dans le texto pour {name}?")
    val noContactCheogram = s("no_contact_cheogram", "No contact matches “{name}” on the phone. They may be in Cheogram's own list.",
        "Aucun contact ne correspond à « {name} » sur le téléphone. La personne est peut-être dans la liste de Cheogram.")
    val noContact = s("no_contact", "No contact matches “{name}”.", "Aucun contact ne correspond à « {name} ».")
}
