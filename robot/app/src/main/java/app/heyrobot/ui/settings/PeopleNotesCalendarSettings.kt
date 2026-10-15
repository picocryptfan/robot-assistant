// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.EmailApp
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TextApp
import app.heyrobot.sys.Phones
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › People, notes and calendar (docs/phase-2.md "Main app"; 0.4.x redesign): the texting app and
// its SMS gateway, the phone region, the email app, the calendar for new events and their length, as radio and edit
// dialogs, and the ways to People, Robot Notes and the permissions.

/** "1 h", "30 min", "1.5 h". */
internal fun eventLength(m: Int): String = if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60}.${m % 60 * 10 / 60} h"

@Composable
fun PeopleNotesCalendarScreen(s: RobotSettings, snap: Snapshot, calendars: List<CalendarInfo>, update: (RobotSettings) -> Unit, a: AppActions,
    snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = snap.facts
    val ctx = LocalContext.current
    // TelephonyManager calls: once per screen, not on every recomposition.
    val device = remember { Phones.deviceRegion(ctx) }
    PreferenceScreen("People, notes and calendar", onBack, snackbar = snackbar) {
        preferenceGroup("Texts", "texts") {
            val cheogram = f?.cheogramInstalled == true
            ListPreference("Texting app", s.textAppFor(cheogram), TextApp.entries, { it.title }, { v ->
                when (v) {
                    TextApp.CHEOGRAM -> if (cheogram) "Numbers go through the SMS gateway; XMPP addresses directly" else "Not installed"
                    TextApp.SMS -> "The phone's SMS app, with the text filled in"
                    TextApp.OFF -> "Robot doesn't write texts"
                }
            }, RIcons.Message) { update(s.copy(textApp = it)) }
            TextPreference("SMS gateway", s.smsGateway, PhoneNumbers.DEFAULT_GATEWAY, "Gateway", "The domain your messaging app texts numbers through (JMP: cheogram.com).",
                keyboard = KeyboardOptions(autoCorrectEnabled = false)) { update(s.copy(smsGateway = PhoneNumbers.gateway(it))) }
            TextPreference("Phone region", s.phoneRegion ?: "", "This phone's own (${device ?: "unknown"})", "Region",
                "Two letters, for numbers without a country code (US, FR, DE…). Empty: this phone's own (${device ?: "unknown"}).",
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                validate = { if (it.isBlank() || PhoneNumbers.region(it) != null) null else "Two letters, such as US or FR" }) {
                update(s.copy(phoneRegion = PhoneNumbers.region(it)))
            }
        }
        preferenceGroup("Email", "email") {
            val proton = f?.protonInstalled == true
            ListPreference("Email app", s.emailAppFor(proton), EmailApp.entries, { it.title }, { v ->
                when (v) {
                    EmailApp.PROTON -> if (proton) "Opens Proton Mail's composer with the draft" else "Not installed: Robot asks each time"
                    EmailApp.ASK -> "Android asks which email app to use"
                }
            }, RIcons.Email) { update(s.copy(emailApp = it)) }
        }
        preferenceGroup("Calendar", "calendar") {
            when {
                f?.calendarGranted != true -> StatusPreference("Allow the calendar", "Then Robot adds events itself", Mark.TODO,
                    action = { FilledTonalButton(onClick = a.askCalendar) { Text("Allow") } })
                calendars.isEmpty() -> PreferenceItem("Calendar for new events", "No calendar here takes new events", RIcons.Event, enabled = false)
                else -> {
                    val auto = CalendarPick.choose(calendars, CalendarPick.AUTO)
                    val chosen = CalendarPick.choose(calendars, s.eventCalendar)
                    val value = if (s.eventCalendar == CalendarPick.AUTO || chosen == null) CalendarPick.AUTO else s.eventCalendar
                    val byId = calendars.associateBy { it.id }
                    ListPreference("Calendar for new events", value, listOf(CalendarPick.AUTO) + calendars.map { it.id },
                        { id -> if (id == CalendarPick.AUTO) "Automatic" else byId[id]?.name ?: "Calendar $id" },
                        { id -> if (id == CalendarPick.AUTO) auto?.let { "Now: ${it.name}" } else byId[id]?.account?.takeIf { a -> a.isNotBlank() && a != byId[id]?.name } },
                        RIcons.Event,
                        summary = if (value == CalendarPick.AUTO) "Automatic" + (auto?.let { " · now ${it.name}" } ?: "") else null) {
                        update(s.copy(eventCalendar = it))
                    }
                }
            }
            ListPreference("Length of a new event", s.eventMinutes, RobotSettings.EVENT_MINUTES.filter { it >= 30 }, ::eventLength, icon = RIcons.Timer,
                summary = eventLength(s.eventMinutes)) { update(s.copy(eventMinutes = it)) }
        }
        preferenceGroup(null, "links") {
            PreferenceItem("People", "Who Robot knows besides your contacts, and what you call them", RIcons.Person, onClick = { a.openPage(Page.People) })
            PreferenceItem("Robot Notes", "Your notes, to read, edit, search and share", RIcons.Note, onClick = { a.openTab(Tab.NOTES) })
            PreferenceItem("Contacts and calendar access", "Allow or check what Robot may read", RIcons.Lock, onClick = { a.openPage(Page.Setup(people = true)) })
        }
        preferenceFooter(
            "Robot never sends anything itself: texts and emails open in the app as drafts, and you press Send there. Until the " +
                "calendar is allowed, or when no calendar on this phone takes new events, your calendar app opens with the event " +
                "filled in. The length is for a new event when you don't say when it ends.",
        )
    }
}
