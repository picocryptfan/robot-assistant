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
    val events = plural("events", "n", Plural(one = "You have {n} event today: {list}.", other = "You have {n} events today: {list}."),
        Plural(one = "Tu as {n} événement aujourd'hui : {list}.", other = "Tu as {n} événements aujourd'hui : {list}."))
    val eventsFirst = s("events_first", "You have {n} events today. The first {shown}: {list}.",
        "Tu as {n} événements aujourd'hui. Les {shown} premiers : {list}.")
    val eventAt = s("event_at", "{title} at {time}", "{title} à {time}")
    val eventUntil = s("event_until", "{title} until {time}", "{title} jusqu'à {time}")
    val eventAllDay = s("event_all_day", "{title} all day", "{title} toute la journée")
    val eventOngoing = s("event_ongoing", "{title} ongoing", "{title} en cours")
    val untitled = s("untitled", "an untitled event", "un événement sans titre")

    // Brief reminders.
    val reminder = s("reminder", "Reminder: {text}.", "Rappel : {text}.")
    val reminders = s("reminders", "Reminders: {list}.", "Rappels : {list}.")
    val remindersMore = plural("reminders_more", "more", Plural(one = "Reminders: {list}; and {more} more.", other = "Reminders: {list}; and {more} more."),
        Plural(one = "Rappels : {list}; et {more} autre.", other = "Rappels : {list}; et {more} autres."))

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
