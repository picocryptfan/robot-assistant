// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// WP3's catalog: people and notes. Their screens' words are resources (strings_people.xml); this holds
// what a screen formats outside composition. Ids start with "people."; the area is "people".

object PeopleStrings : Strings("people", area = "people") {
    // Settings › People, notes and calendar: the length of a new event ("30 min", "1.5 h", « 1,5 h »).
    val minutes = s("minutes", "{n} min", "{n} min")
    val hours = s("hours", "{h} h", "{h} h")
}
