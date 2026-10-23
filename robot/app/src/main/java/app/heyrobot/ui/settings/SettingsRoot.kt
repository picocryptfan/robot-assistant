// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.Approval
import app.heyrobot.core.Escalation
import app.heyrobot.core.LockScreen
import app.heyrobot.core.LookSettings
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SettingsStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.sys.LookStore
import app.heyrobot.sys.MemorySettingsStore
import app.heyrobot.sys.WebStore
import app.heyrobot.core.web.WebSettings
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str

// MAIN PROCESS. The Settings tab (0.4.x redesign, replacing one long page of cards): a list of pages, as Android's own
// Settings is, each row's title over its state now. The pages are ui/settings' (by route), the Wake word, Safety,
// Helpers and Conversation pages, Assistant setup and (1.4) Settings › Model.

/**
 * The Settings tab. [look] is Settings › Display's (LookStore), [memory] Settings › Memory's (0.7); a test passes its own.
 * [routines], [headset], [car] and [files]: their rows' state (null: read from their files, in the menus' language).
 */
@Composable
fun SettingsRoot(s: RobotSettings, snap: Snapshot, a: AppActions, modifier: Modifier = Modifier, look: LookSettings = LookStore.current(LocalContext.current),
    memory: MemorySettings = MemorySettingsStore.current(LocalContext.current), routines: String? = null,
    headset: String? = null, car: String? = null,
    web: WebSettings = WebStore.current(LocalContext.current), files: String? = null) {
    fun go(route: String) = a.openPage(Page.Settings(route))
    // 0.9: Settings › Termux's own file (termux.json), read once per showing.
    val ctx = LocalContext.current
    val termux = androidx.compose.runtime.remember { termuxSummaryText(app.heyrobot.sys.TermuxStore.load(ctx), app.heyrobot.sys.TermuxStore.status(ctx).scripts.size) }
    val langs = LocalLangs.current
    val weather = s.weatherHome?.let { stringResource(R.string.settings_root_weather_home, it.name) } ?: stringResource(R.string.settings_root_weather_none)
    val history = stringResource(if (s.saveHistory) R.string.settings_root_history_on else R.string.settings_root_history_off)
    val attention = setupNeedsAttention(snap.facts)
    val setup = stringResource(when (attention) { null -> R.string.settings_root_setup_checking; true -> R.string.settings_root_setup_attention; false -> R.string.settings_root_setup_ok })
    val problem = stringResource(R.string.settings_problem)
    val advanced = stringResource(R.string.settings_root_advanced)
    val version = "Robot ${BuildConfig.VERSION_NAME}" // l10n: not text (the app's name and version)
    val routinesRow = routines ?: routinesSummaryText(ctx).str()
    val phoneLockRow = phoneLockSummaryText(ctx).str()
    val headsetRow = headset ?: headsetSummaryText(ctx).str()
    val carRow = car ?: carSummaryText(ctx).str()
    PreferenceScreen(UiStrings.settings().str(), onBack = null, modifier = modifier) {
        // 1.4 (files-1.4-plan.md Q1): Model at the top of Settings, now that Files has its tab.
        preferenceGroup(null, "model") {
            PreferenceItem(UiStrings.model().str(), modelSummaryText(s, snap).str(), RIcons.Chip, onClick = { go(SettingsRoutes.MODEL) })
        }
        preferenceGroup(null, "voice") {
            PreferenceItem(UiStrings.wakeWord().str(), wakeSummaryText(s).str(), RIcons.Mic, onClick = { a.openPage(Page.WakePhrases) })
            PreferenceItem(UiStrings.voice().str(), voiceSummaryText(s).str(), RIcons.RecordVoiceOver, onClick = { go(SettingsRoutes.VOICE) })
            PreferenceItem(UiStrings.actions().str(), actionsSummaryText(s).str(), RIcons.TouchApp, onClick = { go(SettingsRoutes.ACTIONS) })
            PreferenceItem(UiStrings.safety().str(), safetySummaryText(s, langs).str(), RIcons.Shield, onClick = { a.openPage(Page.Safety) })
            // 0.8: routines.json and routine_settings.json (RoutineFiles), not settings.json.
            PreferenceItem(UiStrings.routines().str(), routinesRow, RIcons.Alarm, onClick = { go(SettingsRoutes.ROUTINES) })
            // 1.1: skills.json (SkillFiles), not settings.json.
            PreferenceItem(UiStrings.skills().str(), skillsSummary(ctx), RIcons.Robot, onClick = { go(SettingsRoutes.SKILLS) })
            // 1.1: files/robot/lock (LockStore), not settings.json.
            PreferenceItem(UiStrings.phoneLock().str(), phoneLockRow, RIcons.Lock, onClick = { go(SettingsRoutes.PHONE_LOCK) })
            // 0.8: headset.json, car.json and devices.json (HandsFreeFiles), not settings.json.
            PreferenceItem(UiStrings.headset().str(), headsetRow, RIcons.Headset, onClick = { go(SettingsRoutes.HEADSET) })
            PreferenceItem(UiStrings.carMode().str(), carRow, RIcons.Car, onClick = { go(SettingsRoutes.CAR) })
        }
        preferenceGroup(null, "reach") {
            PreferenceItem(UiStrings.peopleNotesCalendar().str(), peopleSummaryText(s, snap.facts).str(), RIcons.Contacts, onClick = { go(SettingsRoutes.PEOPLE_NOTES_CALENDAR) })
            // 0.7: messages.json (MessagesStore), not settings.json.
            PreferenceItem(UiStrings.messages().str(), messagesSummaryText(messagesFile()).str(), RIcons.Message, onClick = { go(SettingsRoutes.MESSAGES) })
            PreferenceItem(UiStrings.weather().str(), weather, RIcons.Sunny, onClick = { go(SettingsRoutes.WEATHER) })
            PreferenceItem(UiStrings.webSearch().str(), webSummaryText(web, null).str(), RIcons.Search, onClick = { go(SettingsRoutes.WEB_SEARCH) })
            PreferenceItem(UiStrings.helpers().str(), helpersSummaryText(s, snap.facts).str(), RIcons.Cloud, onClick = { a.openPage(Page.Helpers) })
            PreferenceItem(UiStrings.conversation().str(), conversationSummaryText(s).str(), RIcons.Transcribe, onClick = { a.openPage(Page.ConversationSettings) })
            PreferenceItem(UiStrings.termux().str(), termux.str(), RIcons.Terminal, onClick = { go(SettingsRoutes.TERMUX) })
        }
        preferenceGroup(null, "app") {
            PreferenceItem(UiStrings.memory().str(), memorySummaryText(memory).str(), RIcons.BookmarkAdded, onClick = { go(SettingsRoutes.MEMORY) })
            // 1.4: Robot Files (files/files, files_settings.json: sys/FilesVault.kt), not settings.json; counted without the key.
            PreferenceItem(UiStrings.files().str(), files ?: filesSummary(), RIcons.Folder, onClick = { go(SettingsRoutes.FILES) })
            PreferenceItem(UiStrings.history().str(), history, RIcons.History, onClick = { go(SettingsRoutes.HISTORY) })
            PreferenceItem(UiStrings.display().str(), displaySummaryText(look).str(), RIcons.Palette, onClick = { go(SettingsRoutes.DISPLAY) })
        }
        preferenceGroup(null, "system") {
            // 1.1: the menus' language (Android's per-app language) and the language Robot answers in.
            PreferenceItem(stringResource(R.string.lang_title), languageSummary(), RIcons.Message, onClick = { go(SettingsRoutes.LANGUAGE) })
            PreferenceItem(UiStrings.assistantSetup().str(), setup,
                if (attention == true) null else RIcons.SettingsVoice, onClick = a.openSetup,
                leading = if (attention == true) ({ Icon(RIcons.Error, problem, tint = MaterialTheme.colorScheme.error) }) else null)
            PreferenceItem(UiStrings.advanced().str(), advanced, RIcons.Terminal, onClick = { go(SettingsRoutes.ADVANCED) })
            // 1.1: its own row (the user, 15 Oct: not under About); backup_state.json, not settings.json.
            PreferenceItem(UiStrings.backup().str(), backupSummaryText(ctx), RIcons.Folder, onClick = { go(SettingsRoutes.BACKUP) })
            PreferenceItem(UiStrings.about().str(), version, RIcons.Info, onClick = { go(SettingsRoutes.ABOUT) })
        }
    }
}

/** [wakeSummary] in words without a language yet. */
internal fun wakeSummaryText(s: RobotSettings): Phrase {
    if (!s.wakeWord) return SettingsStrings.off()
    val on = s.wakePhrases.filter { it.enabled }
    return when (on.size) {
        0 -> SettingsStrings.wakeOnNone()
        1 -> SettingsStrings.wakeOnOne("phrase" to on[0].text)
        else -> SettingsStrings.wakeOnMore(on.size - 1, "phrase" to on[0].text)
    }
}

/** [voiceSummary] in words without a language yet. */
internal fun voiceSummaryText(s: RobotSettings): Phrase {
    val m = when (s.speakReplies) {
        SpeakReplies.VOICE -> SettingsStrings.voiceWhenYouTalk
        SpeakReplies.ALWAYS -> SettingsStrings.voiceAlways
        SpeakReplies.NEVER -> SettingsStrings.voiceNever
    }
    return m("voice" to s.voiceChoice.titleText)
}

/** [actionsSummary] in words without a language yet. */
internal fun actionsSummaryText(s: RobotSettings): Phrase {
    val tools = ToolName.entries.filter { !it.internal }
    val ask = tools.count { s.approval(it) == Approval.ASK }
    val off = tools.count { s.approval(it) == Approval.OFF }
    return Phrase.Join(listOfNotNull(if (ask == 0) SettingsStrings.actionsNoneAsk() else SettingsStrings.actionsAsk(ask),
        if (off > 0) SettingsStrings.actionsOff(off) else null), SEP)
}

/** [safetySummary] in words without a language yet; what works locked is listed in [l] (LockScreen.allowed). */
internal fun safetySummaryText(s: RobotSettings, l: Langs): Phrase =
    SettingsStrings.safety("cards" to s.safetyCards.titleText, "allowed" to LockScreen.allowed(s.lockedTools, l))

/** [peopleSummary] in words without a language yet. */
internal fun peopleSummaryText(s: RobotSettings, f: AssistantFacts?): Phrase =
    Phrase.Join(listOf(s.textAppFor(f?.cheogramInstalled == true).titleText, s.emailAppFor(f?.protonInstalled == true).titleText), SEP)

/** [helpersSummary] in words without a language yet. */
internal fun helpersSummaryText(s: RobotSettings, f: AssistantFacts?): Phrase {
    val on = s.helpers.filter { it.on }
    if (on.isEmpty()) return if (s.helpers.isEmpty()) SettingsStrings.helpersNone() else SettingsStrings.helpersNoneOn()
    val two = Phrase.plain(on.take(2).joinToString(", ") { it.name })
    val names = if (on.size > 2) SettingsStrings.helpersMore(on.size - 2, "names" to two) else two
    val keySaved = { h: app.heyrobot.core.helpers.HelperConfig -> f?.keys?.containsKey(h.id) == true }
    val target = HelperSettings.target(s, keySaved) ?: return names
    return Phrase.Join(listOf(names, escalationText(HelperSettings.escalation(s, target, keySaved(target)))), SEP)
}

/** "When Robot needs help" in the app's words (core's titles speak as you: "Ask me first"), without a language yet (Settings: `escalationText(e).str()`). */
fun escalationText(e: Escalation): Phrase = when (e) {
    Escalation.OFF -> SettingsStrings.escalationOff()
    Escalation.ASK -> SettingsStrings.escalationAsk()
    Escalation.AUTO -> SettingsStrings.escalationAuto()
}

/** [conversationSummary] in words without a language yet. */
internal fun conversationSummaryText(s: RobotSettings): Phrase =
    Phrase.Join(listOfNotNull(if (s.convPartials) SettingsStrings.liveWordsOn() else SettingsStrings.liveWordsOff(),
        if (s.convTranslate) SettingsStrings.translating() else null), SEP)

/** [modelSummary] in words without a language yet (the chip's name stays as it is). */
internal fun modelSummaryText(s: RobotSettings, snap: Snapshot): Phrase {
    val m = snap.resolve(s.brainModelChoice)
    val name: Phrase = if (m.gpu) SettingsStrings.modelOn("model" to m.titleText,
        "backend" to ModelCatalogue.backend(m, snap.phone, s.modelGpu, snap.gpuFailure?.model == m).title) else m.titleText
    return if (s.keepModelMinutes == RobotSettings.KEEP_ALWAYS) SettingsStrings.modelAlways("model" to name)
    else SettingsStrings.modelStays("model" to name, "n" to s.keepModelMinutes)
}

/** Between the parts of a row's state: "Cheogram · Proton Mail". */
private const val SEP = " · "

/** Whether Assistant setup has something to fix (null: not known yet). */
internal fun setupNeedsAttention(f: AssistantFacts?): Boolean? =
    f?.let { !(it.active == true && it.recognizerIsRobot && it.micGranted && it.notificationsGranted) }
