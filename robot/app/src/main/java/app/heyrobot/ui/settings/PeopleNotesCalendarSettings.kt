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
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.PeopleStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str

// MAIN PROCESS. Settings › People, notes and calendar (docs/phase-2.md "Main app"; 0.4.x redesign): the texting app and
// its SMS gateway, the phone region, the email app, the calendar for new events and their length, as radio and edit
// dialogs, and the ways to People, Robot Notes and the permissions.

/** "1 h", "30 min", "1.5 h" (English: the callers not moved to [eventLengthText]). */
internal fun eventLength(m: Int): String = eventLengthText(m).en()

/** [eventLength] in words without a language yet: « 1,5 h » in French. */
internal fun eventLengthText(m: Int): Phrase =
    if (m < 60) PeopleStrings.minutes("n" to m) else PeopleStrings.hours("h" to Arg.Num(m / 60.0, 1))

@Composable
private fun textAppLabel(v: TextApp): String = when (v) {
    TextApp.CHEOGRAM -> v.titleText.str()
    TextApp.SMS -> stringResource(R.string.people_text_sms)
    TextApp.OFF -> stringResource(R.string.people_text_off)
}

@Composable
private fun emailAppLabel(v: EmailApp): String = when (v) {
    EmailApp.PROTON -> v.titleText.str()
    EmailApp.ASK -> stringResource(R.string.people_email_ask)
}

@Composable
fun PeopleNotesCalendarScreen(s: RobotSettings, snap: Snapshot, calendars: List<CalendarInfo>, update: (RobotSettings) -> Unit, a: AppActions,
    snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = snap.facts
    val ctx = LocalContext.current
    // TelephonyManager calls: once per screen, not on every recomposition.
    val device = remember { Phones.deviceRegion(ctx) }
    // The page's words its list builder and the lists' lambdas can't look up themselves (they aren't composable).
    val langs = LocalLangs.current
    val textApps = TextApp.entries.associateWith { textAppLabel(it) }
    val textDetails = mapOf(
        TextApp.CHEOGRAM to stringResource(if (f?.cheogramInstalled == true) R.string.people_text_cheogram_detail else R.string.people_not_installed),
        TextApp.SMS to stringResource(R.string.people_text_sms_detail),
        TextApp.OFF to stringResource(R.string.people_text_off_detail),
    )
    val emailApps = EmailApp.entries.associateWith { emailAppLabel(it) }
    val emailDetails = mapOf(
        EmailApp.PROTON to stringResource(if (f?.protonInstalled == true) R.string.people_email_proton_detail else R.string.people_email_proton_missing),
        EmailApp.ASK to stringResource(R.string.people_email_ask_detail),
    )
    val regionInvalid = stringResource(R.string.people_region_invalid)
    val deviceRegion = device ?: stringResource(R.string.people_region_unknown)
    val automatic = stringResource(R.string.people_automatic)
    val calendarId = stringResource(R.string.people_calendar_id, "%s")
    val nowName = stringResource(R.string.people_now, "%s")
    val automaticNow = stringResource(R.string.people_automatic_now, "%s")
    val texts = stringResource(R.string.people_texts)
    val email = stringResource(R.string.people_email)
    val calendar = stringResource(R.string.people_calendar)
    val footer = stringResource(R.string.people_settings_footer)
    PreferenceScreen(UiStrings.peopleNotesCalendar().str(), onBack, snackbar = snackbar) {
        preferenceGroup(texts, "texts") {
            val cheogram = f?.cheogramInstalled == true
            ListPreference(stringResource(R.string.people_texting_app), s.textAppFor(cheogram), TextApp.entries, { textApps.getValue(it) }, { textDetails[it] },
                RIcons.Message) { update(s.copy(textApp = it)) }
            TextPreference(stringResource(R.string.people_gateway), s.smsGateway, PhoneNumbers.DEFAULT_GATEWAY, stringResource(R.string.people_gateway_field),
                stringResource(R.string.people_gateway_help),
                keyboard = KeyboardOptions(autoCorrectEnabled = false)) { update(s.copy(smsGateway = PhoneNumbers.gateway(it))) }
            TextPreference(stringResource(R.string.people_region), s.phoneRegion ?: "", stringResource(R.string.people_region_own, deviceRegion),
                stringResource(R.string.people_region_field), stringResource(R.string.people_region_help, deviceRegion),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                validate = { if (it.isBlank() || PhoneNumbers.region(it) != null) null else regionInvalid }) {
                update(s.copy(phoneRegion = PhoneNumbers.region(it)))
            }
        }
        preferenceGroup(email, "email") {
            val proton = f?.protonInstalled == true
            ListPreference(stringResource(R.string.people_email_app), s.emailAppFor(proton), EmailApp.entries, { emailApps.getValue(it) }, { emailDetails[it] },
                RIcons.Email) { update(s.copy(emailApp = it)) }
        }
        preferenceGroup(calendar, "calendar") {
            when {
                f?.calendarGranted != true -> StatusPreference(stringResource(R.string.people_allow_calendar), stringResource(R.string.people_allow_calendar_detail),
                    Mark.TODO, action = { FilledTonalButton(onClick = a.askCalendar) { Text(stringResource(R.string.people_allow)) } })
                calendars.isEmpty() -> PreferenceItem(stringResource(R.string.people_event_calendar), stringResource(R.string.people_no_calendar), RIcons.Event,
                    enabled = false)
                else -> {
                    val auto = CalendarPick.choose(calendars, CalendarPick.AUTO)
                    val chosen = CalendarPick.choose(calendars, s.eventCalendar)
                    val value = if (s.eventCalendar == CalendarPick.AUTO || chosen == null) CalendarPick.AUTO else s.eventCalendar
                    val byId = calendars.associateBy { it.id }
                    ListPreference(stringResource(R.string.people_event_calendar), value, listOf(CalendarPick.AUTO) + calendars.map { it.id },
                        { id -> if (id == CalendarPick.AUTO) automatic else byId[id]?.name ?: calendarId.format(id) },
                        { id -> if (id == CalendarPick.AUTO) auto?.let { nowName.format(it.name) } else byId[id]?.account?.takeIf { a -> a.isNotBlank() && a != byId[id]?.name } },
                        RIcons.Event,
                        summary = if (value == CalendarPick.AUTO) auto?.let { automaticNow.format(it.name) } ?: automatic else null) {
                        update(s.copy(eventCalendar = it))
                    }
                }
            }
            ListPreference(stringResource(R.string.people_event_length), s.eventMinutes, RobotSettings.EVENT_MINUTES.filter { it >= 30 },
                { eventLengthText(it).render(langs) }, icon = RIcons.Timer,
                summary = eventLengthText(s.eventMinutes).str()) { update(s.copy(eventMinutes = it)) }
        }
        preferenceGroup(null, "links") {
            PreferenceItem(UiStrings.people().str(), stringResource(R.string.people_people_detail), RIcons.Person, onClick = { a.openPage(Page.People) })
            PreferenceItem(stringResource(R.string.notes_name), stringResource(R.string.people_notes_detail), RIcons.Note, onClick = { a.openTab(Tab.NOTES) })
            PreferenceItem(stringResource(R.string.people_access), stringResource(R.string.people_access_detail), RIcons.Lock,
                onClick = { a.openPage(Page.Setup(people = true)) })
        }
        preferenceFooter(footer)
    }
}
