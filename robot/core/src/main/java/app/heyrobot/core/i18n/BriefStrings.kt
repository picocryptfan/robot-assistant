// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP4a's catalog: the morning brief (core Brief.kt: BriefComposer).
// Ids start with "brief."; the area is "routines". Québec French (tu, « Bon matin », « événement »: 1.0's « rendez-vous »
// can't pass GlossaryTest, whose « vous » rule reads it as vouvoiement); render() adds the French spaces and the voice drops them. The brief is composed in one language (its own
// setting, else Robot's reply language), so its times come in as words already said in it (core BriefClock: "noon",
// « midi »); a calendar title, a reminder, a town and an app's name are arguments, never translated.

object BriefStrings : Strings("brief", area = "routines") {
    // The greeting, the time and the closing.
    val goodMorning = s("good_morning", "Good morning.", "Bon matin.")
    val goodAfternoon = s("good_afternoon", "Good afternoon.", "Bon après-midi.")
    val goodEvening = s("good_evening", "Good evening.", "Bonsoir.")
    /** "7 o'clock": the time on the hour as English says it (French says « 7 h »). */
    val oclock = s("oclock", "{h} o'clock", "{h} h")
    val phoneOpens = s("phone_opens", "Your phone opens at {time}.", "Ton téléphone s'ouvre à {time}.")
    val timeAndDate = s("time_and_date", "It's {time}, {date}.", "Il est {time}, {date}.")
    val goodDay = s("good_day", "Have a good day.", "Bonne journée.")
    val goodEveningEnd = s("good_evening_end", "Have a good evening.", "Bonne soirée.")
    val unlockToHear = s("unlock_to_hear", "Unlock to hear your day.", "Déverrouille pour entendre ta journée.")

    // The weather: now, today, the high, the chance of rain or snow.
    val now = s("now", "Right now it's {now}.", "En ce moment, {now}.")
    val nowIn = s("now_in", "Right now in {town} it's {now}.", "En ce moment à {town}, {now}.")
    val nowAndDay = s("now_and_day", "Right now it's {now}; {day}.", "En ce moment, {now}; {day}.")
    val nowInAndDay = s("now_in_and_day", "Right now in {town} it's {now}; {day}.", "En ce moment à {town}, {now}; {day}.")
    val today = s("today", "Today: {day}.", "Aujourd'hui : {day}.")
    val todayIn = s("today_in", "Today in {town}: {day}.", "Aujourd'hui à {town} : {day}.")
    val degrees = plural("degrees", "n", Plural(one = "{n} degree", other = "{n} degrees"), Plural(one = "{n} degré", other = "{n} degrés"))
    val minusDegrees = plural("minus_degrees", "n", Plural(one = "minus {n} degree", other = "minus {n} degrees"),
        Plural(one = "moins {n} degré", other = "moins {n} degrés"))
    val highOf = s("high_of", "high of {n}", "maximum de {n}")
    val highOfMinus = s("high_of_minus", "high of minus {n}", "maximum de moins {n}")
    val chance = s("chance", "{n} percent chance of {kind}", "{n} % de risque de {kind}")
    val chanceWhen = s("chance_when", "{n} percent chance of {kind} {when}", "{n} % de risque de {kind} {when}")
    val rain = s("rain", "rain", "pluie")
    val snow = s("snow", "snow", "neige")
    val rainOrSnow = s("rain_or_snow", "rain or snow", "pluie ou neige")
    val thisMorning = s("this_morning", "this morning", "ce matin")
    val thisAfternoon = s("this_afternoon", "this afternoon", "cet après-midi")
    val thisEvening = s("this_evening", "this evening", "ce soir")
    val tonight = s("tonight", "tonight", "cette nuit")
    val weatherUnavailable = s("weather_unavailable", "The weather isn't available right now.", "La météo n'est pas disponible pour le moment.")
    val weatherOffline = s("weather_offline", "Weather skipped: routines may not go online.", "Météo sautée : les routines ne peuvent pas aller en ligne.")

    // Today's calendar.
    val calendarUnreadable = s("calendar_unreadable", "Robot can't read your calendar.", "Robot ne peut pas lire ton agenda.")
    val calendarEmpty = s("calendar_empty", "Nothing on your calendar today.", "Rien à ton agenda aujourd'hui.")
    /** Today fix: private events sealed while the phone is locked; said instead of an empty day. */
    val privateLocked = s("private_locked", "Unlock your phone to hear your private events.", "Déverrouille ton téléphone pour entendre tes événements privés.")
    /** Today fix: what History keeps in place of a private event's title. */
    val privateEvent = s("private_event", "a private event", "un événement privé")
    val events = plural("events", "n", Plural(one = "You have {n} event today: {list}.", other = "You have {n} events today: {list}."),
        Plural(one = "Tu as {n} événement aujourd'hui : {list}.", other = "Tu as {n} événements aujourd'hui : {list}."))
    val eventsFirst = s("events_first", "You have {n} events today. The first {shown}: {list}.",
        "Tu as {n} événements aujourd'hui. Les {shown} premiers : {list}.")
    val eventAt = s("event_at", "{title} at {time}", "{title} à {time}")
    val eventUntil = s("event_until", "{title} until {time}", "{title} jusqu'à {time}")
    val eventAllDay = s("event_all_day", "{title} all day", "{title} toute la journée")
    val eventOngoing = s("event_ongoing", "{title} ongoing", "{title} en cours")
    val untitled = s("untitled", "an untitled event", "un événement sans titre")

    // Today's day line (core Today.kt, home-design H1): "how's my day", « comment est ma journée ».
    val dayNow = s("day_now", "Now: {title}.", "En ce moment : {title}.")
    val dayNowUntil = s("day_now_until", "Now: {title}, until {time}.", "En ce moment : {title}, jusqu'à {time}.")
    val dayAllDay = s("day_all_day", "All day: {list}.", "Toute la journée : {list}.")
    val dayNext = s("day_next", "Next: {title} at {time}.", "Ensuite : {title} à {time}.")
    val dayNextMore = plural("day_next_more", "n", Plural(one = "Next: {title} at {time}, then {n} more.", other = "Next: {title} at {time}, then {n} more."),
        Plural(one = "Ensuite : {title} à {time}, puis {n} autre.", other = "Ensuite : {title} à {time}, puis {n} autres."))
    val dayReminder = s("day_reminder", "Reminder: {text}.", "Rappel : {text}.")
    val dayReminders = plural("day_reminders", "n", Plural(one = "{n} reminder today: {text}.", other = "{n} reminders today, the first: {text}."),
        Plural(one = "{n} rappel aujourd'hui : {text}.", other = "{n} rappels aujourd'hui, le premier : {text}."))
    val dayTodo = plural("day_todo", "n", Plural(one = "{n} thing on your to-do list.", other = "{n} things on your to-do list."),
        Plural(one = "{n} chose sur ta liste de choses à faire.", other = "{n} choses sur ta liste de choses à faire."))
    val dayTimer = s("day_timer", "Your timer ends at {time}.", "Ta minuterie finit à {time}.")
    val dayTimers = plural("day_timers", "n", Plural(one = "{n} timer running, ending at {time}.", other = "{n} timers running; the first ends at {time}."),
        Plural(one = "{n} minuterie en cours, qui finit à {time}.", other = "{n} minuteries en cours; la première finit à {time}."))
    val dayNothingElse = s("day_nothing_else", "Nothing else today.", "Rien d'autre aujourd'hui.")
    val dayTomorrowAt = s("day_tomorrow_at", "Tomorrow starts with {title} at {time}.", "Demain commence avec {title} à {time}.")
    val dayTomorrowAllDay = s("day_tomorrow_all_day", "Tomorrow: {title}, all day.", "Demain : {title}, toute la journée.")
    val dayTomorrowEmpty = s("day_tomorrow_empty", "Nothing on your calendar tomorrow.", "Rien à ton agenda demain.")

    // Today H9: the lock screen summary and the widget on a locked phone. Counts and times only, never a title.
    val glanceNext = plural("glance_next", "n", Plural(one = "{n} event today, next at {time}.", other = "{n} events today, next at {time}."),
        Plural(one = "{n} événement aujourd'hui, le prochain à {time}.", other = "{n} événements aujourd'hui, le prochain à {time}."))
    val glanceEvents = plural("glance_events", "n", Plural(one = "{n} event today.", other = "{n} events today."),
        Plural(one = "{n} événement aujourd'hui.", other = "{n} événements aujourd'hui."))
    val glancePrivate = s("glance_private", "Private events wait for your unlock.", "Tes événements privés attendent que tu déverrouilles.")
    val glanceTomorrowAt = s("glance_tomorrow_at", "Tomorrow starts at {time}.", "Demain commence à {time}.")
    val glanceTomorrowAllDay = s("glance_tomorrow_all_day", "Tomorrow: an all-day event.", "Demain : un événement toute la journée.")
    val glanceReminders = plural("glance_reminders", "n", Plural(one = "{n} reminder today.", other = "{n} reminders today."),
        Plural(one = "{n} rappel aujourd'hui.", other = "{n} rappels aujourd'hui."))

    // Brief reminders.
    val reminder = s("reminder", "Reminder: {text}.", "Rappel : {text}.")
    val reminders = s("reminders", "Reminders: {list}.", "Rappels : {list}.")
    val remindersMore = plural("reminders_more", "more", Plural(one = "Reminders: {list}; and {more} more.", other = "Reminders: {list}; and {more} more."),
        Plural(one = "Rappels : {list}; et {more} autre.", other = "Rappels : {list}; et {more} autres."))

    // Today H5: the page's lists (names and counts only) and its note.
    val listLeft = s("list_left", "{name}, {n} left.", "{name}, encore {n}.")
    val note = s("note", "Your note: {title}.", "Ta note : {title}.")
    val news = s("news", "In the news: {titles}.", "Dans les nouvelles : {titles}.")
    val newsFrom = s("news_from", "In the news from {day}: {titles}.", "Dans les nouvelles de {day} : {titles}.")
    val noteLine = s("note_line", "Your note: {title}, {line}.", "Ta note : {title}, {line}.")

    // Unread messages: counts and apps only, never who or what.
    val noUnread = s("no_unread", "No unread messages.", "Aucun message non lu.")
    val unread = plural("unread", "n", Plural(one = "You have {n} unread message.", other = "You have {n} unread messages."),
        Plural(one = "Tu as {n} message non lu.", other = "Tu as {n} messages non lus."))
    val unreadIn = plural("unread_in", "n", Plural(one = "You have {n} unread message in {appName}.", other = "You have {n} unread messages in {appName}."),
        Plural(one = "Tu as {n} message non lu dans {appName}.", other = "Tu as {n} messages non lus dans {appName}."))
    val unreadApps = plural("unread_apps", "n", Plural(one = "You have {n} unread message: {list}.", other = "You have {n} unread messages: {list}."),
        Plural(one = "Tu as {n} message non lu : {list}.", other = "Tu as {n} messages non lus : {list}."))
    val inApp = s("in_app", "{n} in {appName}", "{n} dans {appName}")
}
