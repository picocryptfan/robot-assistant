// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.guide

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.guide.GuideFeature
import app.heyrobot.core.guide.GuideStep
import app.heyrobot.core.guide.WhatsNew
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS (1.5). Every feature's row on its guide page (core GuideFeature: the page, the version, the Settings
// page), the four pages 1.5 added for them, and What's new: the features added since the version Robot last launched
// as, once after an update (MainActivity), or every version's from Settings › About.

/** A feature's name and what it does (what to say, or where it is). */
internal fun featureText(f: GuideFeature): Pair<Int, Int> = when (f) {
    GuideFeature.FILES -> R.string.guide_f_files to R.string.guide_f_files_d
    GuideFeature.TRAVEL_MODE -> R.string.guide_f_travel_mode to R.string.guide_f_travel_mode_d
    GuideFeature.LANGUAGE -> R.string.guide_f_language to R.string.guide_f_language_d
    GuideFeature.WAIT -> R.string.guide_f_wait to R.string.guide_f_wait_d
    GuideFeature.PHONE_LOCK -> R.string.guide_f_phone_lock to R.string.guide_f_phone_lock_d
    GuideFeature.SKILLS -> R.string.guide_f_skills to R.string.guide_f_skills_d
    GuideFeature.BACKUP -> R.string.guide_f_backup to R.string.guide_f_backup_d
    GuideFeature.SUPPORT -> R.string.guide_f_support to R.string.guide_f_support_d
    GuideFeature.LISTS -> R.string.guide_f_lists to R.string.guide_f_lists_d
    GuideFeature.ATTACH -> R.string.guide_f_attach to R.string.guide_f_attach_d
    GuideFeature.CONNECTIONS -> R.string.guide_f_connections to R.string.guide_f_connections_d
    GuideFeature.ROBOT_TO_ROBOT -> R.string.guide_f_robot_to_robot to R.string.guide_f_robot_to_robot_d
    GuideFeature.LINK_MODES -> R.string.guide_f_link_modes to R.string.guide_f_link_modes_d
    GuideFeature.PUSH_APP -> R.string.guide_f_push_app to R.string.guide_f_push_app_d
    GuideFeature.CAR_SCREEN -> R.string.guide_f_car_screen to R.string.guide_f_car_screen_d
    GuideFeature.TRANSCRIBE_BUTTON -> R.string.guide_f_transcribe_button to R.string.guide_f_transcribe_button_d
    GuideFeature.CAR_SHORTCUT -> R.string.guide_f_car_shortcut to R.string.guide_f_car_shortcut_d
    GuideFeature.TODAY -> R.string.guide_f_today to R.string.guide_f_today_d
    GuideFeature.CALENDAR_TAB -> R.string.guide_f_calendar_tab to R.string.guide_f_calendar_tab_d
    GuideFeature.PRIVATE_EVENTS -> R.string.guide_f_private_events to R.string.guide_f_private_events_d
    GuideFeature.NEWS -> R.string.guide_f_news to R.string.guide_f_news_d
    GuideFeature.WIDGETS -> R.string.guide_f_widgets to R.string.guide_f_widgets_d
    GuideFeature.WEATHER_LINE -> R.string.guide_f_weather_line to R.string.guide_f_weather_line_d
    GuideFeature.LOCK_SUMMARY -> R.string.guide_f_lock_summary to R.string.guide_f_lock_summary_d
    GuideFeature.MCP -> R.string.guide_f_mcp to R.string.guide_f_mcp_d
    GuideFeature.PLANS -> R.string.guide_f_plans to R.string.guide_f_plans_d
    GuideFeature.DRAFTS -> R.string.guide_f_drafts to R.string.guide_f_drafts_d
    GuideFeature.SHARING -> R.string.guide_f_sharing to R.string.guide_f_sharing_d
    GuideFeature.SEALED -> R.string.guide_f_sealed to R.string.guide_f_sealed_d
    GuideFeature.READY_ANSWERS -> R.string.guide_f_ready_answers to R.string.guide_f_ready_answers_d
    GuideFeature.SCAM_TEXTS -> R.string.guide_f_scam_texts to R.string.guide_f_scam_texts_d
    GuideFeature.OPEN_APPS -> R.string.guide_f_open_apps to R.string.guide_f_open_apps_d
    GuideFeature.UNDO -> R.string.guide_f_undo to R.string.guide_f_undo_d
    GuideFeature.BETTER_SEARCH -> R.string.guide_f_better_search to R.string.guide_f_better_search_d
    GuideFeature.SCREENSHOTS -> R.string.guide_f_screenshots to R.string.guide_f_screenshots_d
    GuideFeature.LAST_TIME -> R.string.guide_f_last_time to R.string.guide_f_last_time_d
    GuideFeature.HOME_SCREEN -> R.string.guide_f_home_screen to R.string.guide_f_home_screen_d
    GuideFeature.READ_TO_ME -> R.string.guide_f_read_to_me to R.string.guide_f_read_to_me_d
    GuideFeature.STEP_BY_STEP -> R.string.guide_f_step_by_step to R.string.guide_f_step_by_step_d
    GuideFeature.MOMENT_REMINDERS -> R.string.guide_f_moment_reminders to R.string.guide_f_moment_reminders_d
    GuideFeature.STEP_BY_VOICE -> R.string.guide_f_step_by_voice to R.string.guide_f_step_by_voice_d
    GuideFeature.CAR_WIDGET -> R.string.guide_f_car_widget to R.string.guide_f_car_widget_d
    GuideFeature.HEADS_UP -> R.string.guide_f_heads_up to R.string.guide_f_heads_up_d
    GuideFeature.EARBUDS -> R.string.guide_f_earbuds to R.string.guide_f_earbuds_d
    GuideFeature.HELPER_SEARCH -> R.string.guide_f_helper_search to R.string.guide_f_helper_search_d
    GuideFeature.KEPT_QUESTIONS -> R.string.guide_f_kept_questions to R.string.guide_f_kept_questions_d
    GuideFeature.SUMMARIZE -> R.string.guide_f_summarize to R.string.guide_f_summarize_d
    GuideFeature.WIKIPEDIA -> R.string.guide_f_wikipedia to R.string.guide_f_wikipedia_d
    GuideFeature.TOR_BRIDGES -> R.string.guide_f_tor_bridges to R.string.guide_f_tor_bridges_d
    GuideFeature.REPORT -> R.string.guide_f_report to R.string.guide_f_report_d
    GuideFeature.REPEATING_EVENTS -> R.string.guide_f_repeating_events to R.string.guide_f_repeating_events_d
    GuideFeature.NOTES_SAVED -> R.string.guide_f_notes_saved to R.string.guide_f_notes_saved_d
    GuideFeature.NOTE_OF -> R.string.guide_f_note_of to R.string.guide_f_note_of_d
    GuideFeature.NOTE_QUESTION -> R.string.guide_f_note_question to R.string.guide_f_note_question_d
    GuideFeature.FLASHLIGHT_WORDS -> R.string.guide_f_flashlight_words to R.string.guide_f_flashlight_words_d
    GuideFeature.CAR_SOUND -> R.string.guide_f_car_sound to R.string.guide_f_car_sound_d
    GuideFeature.NO_EMPTY_REPLY -> R.string.guide_f_no_empty_reply to R.string.guide_f_no_empty_reply_d
    GuideFeature.KEPT_QUESTIONS_SURE -> R.string.guide_f_kept_questions_sure to R.string.guide_f_kept_questions_sure_d
}

/** The pages 1.5 added: their title and intro; their rows are the features on them. */
internal val FEATURE_PAGES: Map<GuideStep, Pair<Int, Int>> = mapOf(
    GuideStep.FILES_LISTS to (R.string.guide_files_lists_title to R.string.guide_files_lists_intro),
    GuideStep.YOUR_DAY to (R.string.guide_day_title to R.string.guide_day_intro),
    GuideStep.SKILLS to (R.string.guide_skills_title to R.string.guide_skills_intro),
    GuideStep.LINK to (R.string.guide_link_title to R.string.guide_link_intro),
)

/** A feature as a row: tapped, it opens its Settings page when it has one; [open] otherwise (null: not a button). */
@Composable
private fun FeatureRow(f: GuideFeature, a: AppActions, open: ((GuideFeature) -> Unit)?) {
    val (t, d) = featureText(f)
    val route = f.route
    PreferenceItem(stringResource(t), stringResource(d), onClick = when {
        route != null -> ({ a.openPage(Page.Settings(route)) })
        open != null -> ({ open(f) })
        else -> null
    })
}

/** The rows of [step]'s features, under [header] ("More to try" on a page that has its own choices). */
internal fun LazyListScope.featureRows(step: GuideStep, g: GuideCtx, @StringRes header: Int?) {
    val rows = GuideFeature.on(step)
    if (rows.isEmpty()) return
    group(header) { for (f in rows) FeatureRow(f, g.a, null) }
}

/** One of the pages 1.5 added: a title, what it's about, and its features. */
internal fun LazyListScope.featurePage(step: GuideStep, g: GuideCtx) {
    val (t, intro) = FEATURE_PAGES.getValue(step)
    title(t)
    para(intro)
    featureRows(step, g, null)
    privacy(FEATURE_PRIVACY.getValue(step))
    findIt(R.string.guide_path_guide)
}

/** What stays on the phone for each feature page; each row opens its own Settings page. */
private val FEATURE_PRIVACY = mapOf(
    GuideStep.FILES_LISTS to R.string.guide_files_lists_privacy,
    GuideStep.YOUR_DAY to R.string.guide_day_privacy,
    GuideStep.SKILLS to R.string.guide_skills_privacy,
    GuideStep.LINK to R.string.guide_link_privacy,
)

/**
 * What's new: the features added after [from] up to this version, by version, newest first. A row opens its Settings
 * page, or its guide page ([openGuideAt]) when it has none.
 */
@Composable
fun WhatsNewScreen(from: String, a: AppActions, openGuideAt: (GuideStep?) -> Unit, onClose: () -> Unit) {
    val byVersion = remember(from) { WhatsNew.since(from, BuildConfig.VERSION_NAME).groupBy { it.since } }
    val intro = stringResource(if (WhatsNew.compare(from, WhatsNew.FIRST) < 0) R.string.guide_whats_new_all_intro else R.string.guide_whats_new_intro)
    PreferenceScreen(stringResource(R.string.guide_whats_new_title), onClose, small = true) {
        preferenceGroup(null, "whats-new-intro") { // l10n: not text (a list key)
            Text(intro, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        for ((version, features) in byVersion) {
            preferenceGroup(null, "whats-new-$version") { // l10n: not text (a list key)
                Text(stringResource(R.string.guide_whats_new_version, version), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                for (f in features) FeatureRow(f, a) { openGuideAt(it.step) }
            }
        }
        preferenceGroup(null, "whats-new-end") { // l10n: not text (a list key)
            if (WhatsNew.unkept(from)) PreferenceItem(stringResource(R.string.guide_whats_new_everything), onClick = { openGuideAt(GuideStep.entries.first()) })
            PreferenceItem(stringResource(R.string.guide_whats_new_guide), onClick = { openGuideAt(null) })
            PreferenceItem(stringResource(R.string.guide_whats_new_done), onClick = onClose)
        }
    }
}
