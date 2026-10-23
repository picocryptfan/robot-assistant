// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.guide

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.EmailApp
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtModelLogic
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.Phones
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineCards
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.LockScreen
import app.heyrobot.core.Speeds
import app.heyrobot.core.TextApp
import app.heyrobot.core.TpuSupport
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.BrainModel
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.guide.GuideChoices
import app.heyrobot.core.guide.GuideItem
import app.heyrobot.core.guide.GuideStep
import app.heyrobot.core.guide.GuideSummary
import app.heyrobot.core.guide.SummaryLine
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.level
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.RadioPreference
import app.heyrobot.ui.pref.SliderPreference
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.settings.MessagesSetupDialog
import app.heyrobot.ui.settings.PrivacyChip
import app.heyrobot.ui.settings.RoutineRoutes
import app.heyrobot.ui.settings.engineLine
import app.heyrobot.ui.settings.serviceLine
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.settings.menusLabel
import app.heyrobot.core.ToolName
import app.heyrobot.sys.Menus
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import java.util.Locale

// MAIN PROCESS (1.0). The setup guide's pages (SetupGuideScreen draws the frame): each says what the feature does, a
// privacy line, its choices as Settings' rows with what is set now, and where Settings has it.

internal fun LazyListScope.guidePage(step: GuideStep, g: GuideCtx) = when (step) {
    GuideStep.WELCOME -> welcome(g)
    GuideStep.CAN_DO -> canDo()
    GuideStep.PHONE -> phone(g)
    GuideStep.VOICE -> voice(g)
    GuideStep.OPENING -> opening(g)
    GuideStep.ASKS_FIRST -> asksFirst(g)
    GuideStep.PEOPLE -> people(g)
    GuideStep.MESSAGES -> messages(g)
    GuideStep.MEMORY -> memory(g)
    GuideStep.WEATHER -> weather(g)
    GuideStep.CONVERSATION -> conversation(g)
    GuideStep.ROUTINES -> routines(g)
    GuideStep.PHONE_LOCK -> phoneLock(g)
    GuideStep.HEADSET_CAR -> headsetCar(g)
    GuideStep.HELPERS -> helpers(g)
    GuideStep.WEB_TERMUX -> webTermux(g)
    GuideStep.UPDATES -> updates(g)
    GuideStep.SUMMARY -> summary(g)
}

/** A button at the end of a status row, named for TalkBack ([label]: "Allow contacts"). */
@Composable
internal fun RowButton(@StringRes text: Int, @StringRes label: Int, onClick: () -> Unit) {
    val l = stringResource(label)
    FilledTonalButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = l }) { Text(stringResource(text)) }
}

private fun mark(st: ModelStatus?): Mark = when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }

/** A download's row: its state, its progress while it downloads, and Download where it can start. */
@Composable
private fun DownloadRow(title: String, status: ModelStatus?, progress: String?, percent: Int, @StringRes label: Int, onDownload: () -> Unit) {
    StatusPreference(title, when (status) {
        ModelStatus.VERIFIED -> stringResource(R.string.guide_ready)
        ModelStatus.DOWNLOADING, ModelStatus.IMPORTING, ModelStatus.VERIFYING, ModelStatus.UNVERIFIED -> progress ?: stringResource(R.string.guide_downloading)
        else -> stringResource(R.string.guide_not_downloaded)
    }, mark(status), action = if (status == ModelStatus.MISSING || status == ModelStatus.BAD) ({ RowButton(R.string.guide_download, label, onDownload) }) else null)
    if (status == ModelStatus.DOWNLOADING && percent >= 0) {
        PreferenceBlock { LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.semantics { contentDescription = title }) }
    }
}

// ------------------------------------------------------------------------------------------------------- 1. Welcome

private fun LazyListScope.welcome(g: GuideCtx) {
    title(R.string.guide_welcome_title)
    para(R.string.guide_welcome_1)
    para(R.string.guide_welcome_2)
    para(R.string.guide_welcome_3)
    // 1.1: the menus' language, as Settings › Language has it. Choosing it recreates the guide in that
    // language, on this page (the guide's step is in its file).
    group(R.string.lang_title) {
        var menus by remember { mutableStateOf(g.stores.menus()) }
        val labels = Menus.entries.associateWith { menusLabel(it) }
        ListPreference(stringResource(R.string.lang_menus), menus, Menus.entries, { labels.getValue(it) }, icon = RIcons.Message) {
            menus = it
            g.stores.setMenus(it)
        }
    }
    // 1.1: on a phone with nothing of yours yet, a backup made on your other phone.
    if (g.files.freshPhone) group(null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        PreferenceItem(stringResource(R.string.guide_welcome_restore), stringResource(R.string.guide_welcome_restore_detail), RIcons.Folder,
            onClick = { ctx.startActivity(app.heyrobot.sys.BackupWindow.restore(ctx)) })
    }
    privacy(R.string.guide_welcome_privacy)
    findIt(R.string.guide_path_guide)
}

// ---------------------------------------------------------------------------------------------- 2. What Robot can do

/** One example: what you say (and in French where it's natural), what happens, what goes online, where to turn it on. */
internal class Example(
    @StringRes val say: Int,
    @StringRes val fr: Int?,
    @StringRes val does: Int,
    val online: Boolean,
    @StringRes val tag: Int,
    @StringRes val where: Int?,
)

/** Checked against the README: each does what it says in 1.0. The first four are the summary's too. */
internal val EXAMPLES = listOf(
    Example(R.string.guide_can_meeting_say, R.string.guide_can_meeting_fr, R.string.guide_can_meeting_does, false, R.string.guide_tag_offline,
        R.string.guide_can_meeting_where),
    Example(R.string.guide_can_interpreter_say, R.string.guide_can_interpreter_fr, R.string.guide_can_interpreter_does, false, R.string.guide_tag_offline,
        R.string.guide_can_interpreter_where),
    Example(R.string.guide_can_memory_say, R.string.guide_can_memory_fr, R.string.guide_can_memory_does, false, R.string.guide_tag_encrypted,
        R.string.guide_can_memory_where),
    Example(R.string.guide_can_messages_say, R.string.guide_can_messages_fr, R.string.guide_can_messages_does, false, R.string.guide_tag_messages,
        R.string.guide_can_messages_where),
    Example(R.string.guide_can_brief_say, R.string.guide_can_brief_fr, R.string.guide_can_brief_does, true, R.string.guide_tag_brief,
        R.string.guide_can_brief_where),
    Example(R.string.guide_can_screen_say, null, R.string.guide_can_screen_does, false, R.string.guide_tag_screen, R.string.guide_can_screen_where),
    Example(R.string.guide_can_web_say, R.string.guide_can_web_fr, R.string.guide_can_web_does, true, R.string.guide_tag_web, R.string.guide_can_web_where),
    Example(R.string.guide_can_termux_say, null, R.string.guide_can_termux_does, false, R.string.guide_tag_termux, R.string.guide_can_termux_where),
    Example(R.string.guide_can_car_say, null, R.string.guide_can_car_does, false, R.string.guide_tag_phone, R.string.guide_can_car_where),
    Example(R.string.guide_can_helper_say, null, R.string.guide_can_helper_does, true, R.string.guide_tag_helper, R.string.guide_can_helper_where),
)

/** An example as one block, one TalkBack stop. */
@Composable
private fun ExampleBlock(e: Example) {
    val c = MaterialTheme.colorScheme
    PreferenceBlock(Modifier.semantics(mergeDescendants = true) {}) {
        Text(stringResource(e.say), style = MaterialTheme.typography.titleMedium)
        e.fr?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = c.onSurfaceVariant) }
        Text(stringResource(e.does), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (e.online) RIcons.Cloud else RIcons.Phone, null, Modifier.size(18.dp), tint = c.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(e.tag), style = MaterialTheme.typography.labelLarge, color = c.primary)
        }
        e.where?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
    }
}

private fun LazyListScope.canDo() {
    title(R.string.guide_can_title)
    para(R.string.guide_can_intro)
    group(null) { for (e in EXAMPLES) ExampleBlock(e) }
    privacy(R.string.guide_can_privacy)
    findIt(R.string.guide_path_guide)
}

// ------------------------------------------------------------------------------------------ 3. Your phone and Robot's brain

private fun LazyListScope.phone(g: GuideCtx) {
    val snap = g.snap
    val p = snap.phone
    val fast = snap.fast
    val backend = ModelCatalogue.backend(fast, p, g.s.modelGpu, snap.gpuFailure?.model == fast)
    // 1.2: what this phone measured, once it has (Settings › Model's figures), else the estimate.
    val speed = if (fast == BrainModel.GENERAL) Speeds.of(fast, backend, p, app.heyrobot.ui.model.measuredHere(fast, backend, snap))?.textPhrase()
        ?: fast.speedText else fast.speedText
    title(R.string.guide_phone_title)
    para(R.string.guide_phone_intro)
    group(R.string.guide_phone_this) {
        StatusPreference(Phones.phoneText(p).str(), Phones.tpuLineText(p).str(), if (p.tpu != TpuSupport.NONE) Mark.OK else Mark.TODO)
        PreferenceItem(stringResource(R.string.guide_phone_fast, fast.modelName, backend.titleOn(p.chip)),
            stringResource(R.string.guide_phone_size, ModelLogic.gbText(fast.bytes).str(), speed.str()), RIcons.Chip)
        if (fast == BrainModel.FAST_G6) StatusPreference(stringResource(R.string.guide_phone_beta), stringResource(R.string.guide_phone_beta_detail), Mark.TODO)
        CheckRow(g, backend)
    }
    // 1.2 (design Step 8): on a slow or small phone, what to expect and what to change.
    fitWarning(g)
    group(R.string.guide_phone_download) {
        val m = snap.model(fast)
        DownloadRow(stringResource(R.string.guide_phone_brain, ModelLogic.gbText(fast.bytes).str()), m?.status, m?.progress, m?.percent ?: -1,
            R.string.guide_phone_download_label) { g.a.download(fast) }
    }
    para(R.string.guide_phone_later)
    privacy(R.string.guide_phone_privacy)
    findIt(R.string.guide_path_model)
}

/**
 * 1.2 (design Step 2): Robot's self-test of the GPU (or a beta TPU build), asked for as soon as Fast's download is checked
 * and the check is due here (never on the CPU, never on the Tensor G5's TPU): "Checking this phone's GPU" while it runs, then
 * what it found, with its numbers: passed; passed with a note (a miss, core GpuVerdict.miss: an answer that was off or slow,
 * and the GPU stays in use); or failed (a fault: the CPU, or Fast's general build). It never blocks the guide; one the app
 * leaves early runs again at the next load.
 */
@Composable
private fun CheckRow(g: GuideCtx, backend: app.heyrobot.core.ModelBackend) {
    val snap = g.snap
    val chip = snap.phone.chip
    val due = snap.checkDue(g.s.modelGpu)
    androidx.compose.runtime.LaunchedEffect(due, snap.checking) { if (due && !snap.checking) g.a.checkModel() }
    val last = snap.lastCheck
    when {
        due && snap.checking -> StatusPreference(stringResource(R.string.guide_phone_check_running, backend.titleOn(chip)),
            stringResource(R.string.guide_phone_check_running_detail, app.heyrobot.core.GpuCheck.EST_SECONDS), Mark.TODO)
        last == null || due -> {}
        last.verdict.passed -> {
            val secs = Math.round(last.ms / 1000.0).toInt()
            StatusPreference(stringResource(R.string.guide_phone_check_passed, last.key.backend.titleOn(chip)),
                if (last.readTps > 0 && last.writeTps > 0) stringResource(R.string.guide_phone_check_passed_detail, secs, Math.round(last.readTps).toInt(),
                    Math.round(last.writeTps).toInt())
                else stringResource(R.string.guide_phone_check_passed_plain, secs), Mark.OK)
        }
        last.verdict.miss -> {
            val device = last.key.backend.titleOn(chip)
            StatusPreference(stringResource(R.string.guide_phone_check_noted, device, last.verdict.titleText.str()),
                stringResource(R.string.guide_phone_check_noted_detail, Math.round(last.ms / 1000.0).toInt(), device), Mark.OK)
        }
        else -> StatusPreference(stringResource(R.string.guide_phone_check_failed, last.key.backend.titleOn(chip), last.verdict.titleText.str()),
            stringResource(if (last.key.backend == app.heyrobot.core.ModelBackend.TPU) R.string.guide_phone_check_failed_tpu else R.string.guide_phone_check_failed_gpu),
            Mark.WARN)
    }
}

// --------------------------------------------------------------------------------------------------------- 4. Voice

private fun LazyListScope.voice(g: GuideCtx) {
    val s = g.s
    val snap = g.snap
    title(R.string.guide_voice_title)
    para(R.string.guide_voice_intro)
    group(R.string.guide_voice_downloads) {
        for (b in listOf(VoiceBundle.STT, VoiceBundle.KOKORO)) {
            val v = snap.voice(b)
            DownloadRow("${b.titleText.str()} · ${VoiceModelLogic.sizeText(b.bytes).str()}", v?.status, v?.progress, v?.percent ?: -1,
                if (b == VoiceBundle.STT) R.string.guide_voice_download_stt else R.string.guide_voice_download_voice) { g.a.voiceDownload(b) }
        }
    }
    group(R.string.guide_voice_choice) {
        val langs = LocalLangs.current
        ListPreference(stringResource(R.string.guide_voice_voice), s.voiceChoice, VoiceChoice.entries, { it.titleText.render(langs) }, { it.detailText(snap.systemVoice).render(langs) },
            RIcons.RecordVoiceOver) {
            g.update(s.copy(voice = it.id))
        }
        PreferenceItem(stringResource(R.string.guide_voice_sample), stringResource(R.string.guide_voice_sample_detail), RIcons.VolumeUp, onClick = g.a.speakTest)
        SliderPreference(stringResource(R.string.guide_voice_speed), s.speechRate, 0.5f..2.0f, 14, { String.format(Locale.US, "%.1f×", it) }) {
            g.update(s.copy(speechRate = RobotSettings.clampRate(it)))
        }
    }
    privacy(R.string.guide_voice_privacy)
    findIt(R.string.guide_path_voice)
}

// ------------------------------------------------------------------------------------------------- 5. Opening Robot

private fun LazyListScope.opening(g: GuideCtx) {
    val s = g.s
    val f = g.snap.facts
    title(R.string.guide_open_title)
    para(R.string.guide_open_intro)
    group(R.string.guide_open_assistant) {
        StatusPreference(stringResource(R.string.guide_open_role), stringResource(if (f?.active == true) R.string.guide_done_state else R.string.guide_open_role_todo),
            if (f?.active == true) Mark.OK else Mark.TODO,
            action = if (f?.active == true) null else ({ RowButton(R.string.guide_set, R.string.guide_open_role_label, g.a.openAssistantSettings) }))
        val allowed = f?.micGranted == true && f.notificationsGranted
        StatusPreference(stringResource(R.string.guide_open_mic), stringResource(if (allowed) R.string.guide_allowed else R.string.guide_open_mic_todo),
            if (allowed) Mark.OK else Mark.TODO,
            action = if (allowed) null else ({ RowButton(R.string.guide_allow, R.string.guide_open_mic_label, g.a.askPermissions) }))
        // 1.0: the recognizer fix, with a computer or (Shizuku) without one. 1.2: not asked for where another installed
        // recognizer is set (a stock phone's own), which keeps Robot the assistant too and which the fix would replace.
        if (f?.restartKept == app.heyrobot.core.RecognizerRepair.Kept.OTHER)
            StatusPreference(stringResource(R.string.guide_open_fix), stringResource(R.string.guide_open_fix_other), Mark.OK)
        else PreferenceItem(stringResource(R.string.guide_open_fix), stringResource(R.string.guide_open_fix_detail), RIcons.Settings,
            onClick = { g.a.openPage(Page.AdbFixes) })
    }
    group(R.string.guide_open_ways) {
        PreferenceItem(stringResource(R.string.guide_open_swipe), stringResource(R.string.guide_open_swipe_detail), RIcons.TouchApp)
        SwitchPreference(stringResource(R.string.guide_open_wake), stringResource(R.string.guide_open_wake_detail), s.wakeWord, icon = RIcons.Mic) {
            g.update(s.copy(wakeWord = it))
        }
        SwitchPreference(stringResource(R.string.guide_open_charging), stringResource(R.string.guide_open_charging_detail), s.wakeOnlyWhileCharging,
            enabled = s.wakeWord) { g.update(s.copy(wakeOnlyWhileCharging = it)) }
        PreferenceItem(stringResource(R.string.guide_open_locked), LockScreen.allowed(s.lockedTools, LocalLangs.current).replaceFirstChar { it.uppercase() }, RIcons.Lock)
    }
    group(R.string.guide_open_try) {
        PreferenceItem(stringResource(R.string.guide_open_try_row), stringResource(R.string.guide_open_try_detail), RIcons.Robot, onClick = { g.a.talk(null) })
    }
    privacy(R.string.guide_open_privacy)
    findIt(R.string.guide_path_opening)
}

// ------------------------------------------------------------------------------------------- 6. How Robot asks first

private fun LazyListScope.asksFirst(g: GuideCtx) {
    val s = g.s
    val d = RobotSettings()
    title(R.string.guide_ask_title)
    para(R.string.guide_ask_intro)
    group(R.string.guide_ask_cards) {
        val langs = LocalLangs.current
        ListPreference(stringResource(R.string.guide_ask_when), s.safetyCards, SafetyCards.entries, { it.titleText.render(langs) }, { it.detailText.render(langs) },
            RIcons.Shield) {
            g.update(s.copy(safetyCards = it))
        }
        SwitchPreference(stringResource(R.string.guide_ask_voice), stringResource(R.string.guide_ask_voice_detail), s.voiceCards, icon = RIcons.Mic) {
            g.update(s.copy(voiceCards = it))
        }
        PreferenceItem(stringResource(R.string.guide_ask_locked), LockScreen.allowed(s.lockedTools, langs).replaceFirstChar { it.uppercase() }, RIcons.Lock,
            onClick = { g.a.openPage(Page.Safety) })
    }
    group(null) {
        PreferenceItem(stringResource(R.string.guide_ask_review), stringResource(R.string.guide_ask_review_detail), RIcons.TouchApp,
            onClick = { g.a.openPage(Page.Settings(SettingsRoutes.ACTIONS)) })
        val defaults = s.approvals == d.approvals && s.safetyCards == d.safetyCards && s.voiceCards == d.voiceCards && s.lockedTools == d.lockedTools
        if (!defaults) {
            PreferenceItem(stringResource(R.string.guide_ask_defaults), stringResource(R.string.guide_ask_defaults_detail), RIcons.Refresh, onClick = {
                g.update(s.copy(approvals = d.approvals, safetyCards = d.safetyCards, voiceCards = d.voiceCards, lockedTools = d.lockedTools))
            })
        }
    }
    para(R.string.guide_ask_yes)
    privacy(R.string.guide_ask_privacy)
    findIt(R.string.guide_path_asks)
}

// ------------------------------------------------------------------------------ 7. People, texts, email, calendar, notes

private fun LazyListScope.people(g: GuideCtx) {
    val s = g.s
    val f = g.snap.facts
    title(R.string.guide_people_title)
    para(R.string.guide_people_intro)
    group(R.string.guide_people_apps) {
        val cheogram = f?.cheogramInstalled == true
        val langs = LocalLangs.current
        ListPreference(stringResource(R.string.guide_people_text), s.textAppFor(cheogram), TextApp.entries, { it.titleText.render(langs) }, icon = RIcons.Message) {
            g.update(s.copy(textApp = it))
        }
        ListPreference(stringResource(R.string.guide_people_email), s.emailAppFor(f?.protonInstalled == true), EmailApp.entries, { it.titleText.render(langs) }, icon = RIcons.Email) {
            g.update(s.copy(emailApp = it))
        }
    }
    group(R.string.guide_people_access) {
        val contacts = f?.contactsGranted == true
        StatusPreference(stringResource(R.string.guide_people_contacts),
            stringResource(if (contacts) R.string.guide_allowed else R.string.guide_people_contacts_why), if (contacts) Mark.OK else Mark.TODO,
            action = if (contacts) null else ({ RowButton(R.string.guide_allow, R.string.guide_people_contacts_label, g.a.askContacts) }))
        val calendar = f?.calendarGranted == true
        StatusPreference(stringResource(R.string.guide_people_calendar),
            stringResource(if (calendar) R.string.guide_allowed else R.string.guide_people_calendar_why), if (calendar) Mark.OK else Mark.TODO,
            action = if (calendar) null else ({ RowButton(R.string.guide_allow, R.string.guide_people_calendar_label, g.a.askCalendar) }))
        PreferenceItem(stringResource(R.string.guide_people_people), stringResource(R.string.guide_people_people_detail), RIcons.Person,
            onClick = { g.a.openPage(Page.People) })
    }
    para(R.string.guide_people_notes)
    privacy(R.string.guide_people_privacy)
    findIt(R.string.guide_path_people)
}

// ----------------------------------------------------------------------------------------------------- 8. Messages

private fun LazyListScope.messages(g: GuideCtx) {
    val m = g.files.messages
    val access = g.snap.facts?.notificationAccess == true
    title(R.string.guide_messages_title)
    para(R.string.guide_messages_intro)
    group(null) {
        // Turned on with no app yet: Settings › Messages' own window first (what Robot will do, the apps to read); with none
        // of them installed, Messages stays off and the page says so.
        var choose by rememberSaveable { mutableStateOf(false) }
        var noApp by rememberSaveable { mutableStateOf(false) }
        val langs = LocalLangs.current
        val here = remember(choose) { if (choose) g.stores.messageApps() else emptySet() }
        SwitchPreference(stringResource(R.string.guide_messages_read), stringResource(R.string.guide_messages_read_detail), m.on, icon = RIcons.Message) { on ->
            when {
                !on || m.allowlist.apps.isNotEmpty() -> { g.stores.messages(on); g.changed() }
                g.stores.messageApps().isEmpty() -> noApp = true
                else -> { noApp = false; choose = true }
            }
        }
        if (noApp && !m.on) StatusPreference(stringResource(R.string.guide_messages_no_app_title),
            stringResource(R.string.guide_messages_no_app, MessageApp.entries.joinToString(", ") { it.titleText.render(langs) }), Mark.WARN)
        if (choose) MessagesSetupDialog({ it in here }, onDismiss = { choose = false }, needsApp = true) { apps ->
            choose = false
            g.stores.turnMessagesOn(apps); g.changed()
        }
        if (m.on) {
            StatusPreference(stringResource(R.string.guide_messages_access),
                stringResource(if (access) R.string.guide_allowed else R.string.guide_messages_access_todo), if (access) Mark.OK else Mark.TODO,
                onClick = if (access) null else ({ g.a.openPage(Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) }))
            PreferenceItem(stringResource(R.string.guide_messages_apps), m.allowlist.apps.joinToString(", ") { it.titleText.render(langs) }.ifEmpty { stringResource(R.string.guide_none) },
                RIcons.Apps, onClick = { g.a.openPage(Page.Settings(SettingsRoutes.MESSAGES)) })
        }
        SwitchPreference(stringResource(R.string.guide_messages_announce), stringResource(R.string.guide_messages_announce_detail), m.on && m.announce,
            enabled = m.on, icon = RIcons.Headset) { g.stores.announce(it); g.changed() }
    }
    privacy(R.string.guide_messages_privacy)
    findIt(R.string.guide_path_messages)
}

// ------------------------------------------------------------------------------------------------------- 9. Memory

private fun LazyListScope.memory(g: GuideCtx) {
    val m = g.files.memory
    title(R.string.guide_memory_title)
    para(R.string.guide_memory_intro)
    group(null) {
        SwitchPreference(stringResource(R.string.guide_memory_use), stringResource(R.string.guide_memory_use_detail), m.on, icon = RIcons.BookmarkAdded) {
            g.stores.memory(m.copy(on = it)); g.changed()
        }
        SwitchPreference(stringResource(R.string.guide_memory_locked), stringResource(R.string.guide_memory_locked_detail), m.on && m.onLockScreen,
            enabled = m.on, icon = RIcons.Lock) { g.memoryLock(it) }
    }
    privacy(R.string.guide_memory_privacy)
    findIt(R.string.guide_path_memory)
}

// ------------------------------------------------------------------------------------------- 10. Weather and places

private fun LazyListScope.weather(g: GuideCtx) {
    val s = g.s
    val f = g.snap.facts
    title(R.string.guide_weather_title)
    para(R.string.guide_weather_intro)
    group(null) {
        PreferenceItem(stringResource(R.string.guide_weather_home), s.weatherHome?.name ?: stringResource(R.string.guide_not_set), RIcons.Home, onClick = g.editHome)
        val located = f?.locationGranted == true
        StatusPreference(stringResource(R.string.guide_weather_location),
            stringResource(if (located) R.string.guide_weather_location_on else R.string.guide_weather_location_why), if (located) Mark.OK else Mark.TODO,
            action = if (located) null else ({ RowButton(R.string.guide_allow, R.string.guide_weather_location_label, g.a.askLocation) }))
        val langs = LocalLangs.current
        ListPreference(stringResource(R.string.guide_weather_nearest), Nearby.mode(s, g.snap.nearestAnswer), NearestOnline.entries, { it.titleText.render(langs) },
            { it.detailText.render(langs) }, RIcons.Search) { g.update(s.copy(nearestOnline = it, nearestOnlineAt = System.currentTimeMillis())) }
        PreferenceItem(stringResource(R.string.guide_weather_server), s.nearbyServer.labelText.str(), RIcons.Place,
            onClick = { g.a.openPage(Page.Settings(SettingsRoutes.ACTIONS)) })
    }
    privacy(R.string.guide_weather_privacy)
    findIt(R.string.guide_path_weather)
}

// ------------------------------------------------------------------------------------------- 11. Conversation mode

private fun LazyListScope.conversation(g: GuideCtx) {
    val s = g.s
    val snap = g.snap
    title(R.string.guide_conv_title)
    para(R.string.guide_conv_intro)
    group(null) {
        SwitchPreference(stringResource(R.string.guide_conv_translate), stringResource(R.string.guide_conv_translate_detail, s.convPairChoice.titleText.str()),
            s.convTranslate, icon = RIcons.Transcribe) { g.update(s.copy(convTranslate = it)) }
    }
    group(R.string.guide_conv_optional) {
        val sp = snap.voice(VoiceBundle.SPEAKERS)
        if (s.convSpeakers != false) {
            DownloadRow(stringResource(R.string.guide_conv_speakers, VoiceModelLogic.sizeText(VoiceBundle.SPEAKERS.bytes).str()), sp?.status, sp?.progress,
                sp?.percent ?: -1, R.string.guide_conv_speakers_label) { g.a.voiceDownload(VoiceBundle.SPEAKERS) }
        }
        val mt = snap.mt
        DownloadRow(MtModelLogic.labelText(MtBundle.FR_EN).str(), mt?.status, mt?.progress, mt?.percent ?: -1, R.string.guide_conv_mt_label, g.a.mtDownload)
    }
    para(R.string.guide_conv_start)
    privacy(R.string.guide_conv_privacy)
    findIt(R.string.guide_path_conversation)
}

// --------------------------------------------------------------------------------- 12. Routines and the morning brief

private fun LazyListScope.routines(g: GuideCtx) {
    val rs = g.files.routines
    title(R.string.guide_routines_title)
    para(R.string.guide_routines_intro)
    group(null) {
        SwitchPreference(stringResource(R.string.guide_routines_brief),
            RoutineCards.whenPhrase(BriefRoutine.of(rs.copy(briefEnabled = true))).str().replaceFirstChar { it.uppercase() }, rs.briefEnabled, icon = RIcons.Sunny) { on ->
            if (on && !rs.briefOnline) g.askBrief() else { g.stores.routines(rs.copy(briefEnabled = on)); g.changed() }
        }
        // 1.2: "after your alarm" needs GrapheneOS Clock; on a phone without it the brief would never start (core ClockNeed).
        val clock = g.snap.facts?.clockInstalled ?: true
        if (app.heyrobot.core.ClockNeed.unmet(BriefRoutine.of(rs).trigger, clock))
            StatusPreference(stringResource(R.string.routine_alarm_needs_clock), null, Mark.WARN)
        PreferenceItem(stringResource(R.string.guide_routines_change), stringResource(R.string.guide_routines_change_detail), RIcons.Alarm,
            onClick = { g.a.openPage(Page.Settings(RoutineRoutes.BRIEF)) })
    }
    para(R.string.guide_routines_voice)
    privacy(R.string.guide_routines_privacy)
    findIt(R.string.guide_path_routines)
}

// ------------------------------------------------------------------------------------------------ 13. Phone lock (1.1)

/** Phone lock (docs/plans/phone-lock-design.md §11): what it does and that it is off; the setup is in Settings › Phone lock. */
private fun LazyListScope.phoneLock(g: GuideCtx) {
    title(R.string.guide_lock_title)
    para(R.string.guide_lock_intro)
    group(null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val on = androidx.compose.runtime.remember { app.heyrobot.lock.LockStore.read(ctx).ok?.settings?.on == true }
        PreferenceItem(stringResource(R.string.guide_lock_row), stringResource(if (on) R.string.guide_on else R.string.guide_lock_off), RIcons.Lock,
            onClick = { g.a.openPage(Page.Settings(SettingsRoutes.PHONE_LOCK)) })
    }
    privacy(R.string.guide_lock_privacy)
    findIt(R.string.guide_path_lock)
}

// ------------------------------------------------------------------------------------------------ 14. Headset and car

private fun LazyListScope.headsetCar(g: GuideCtx) {
    val h = g.files.headset
    title(R.string.guide_hf_title)
    para(R.string.guide_hf_intro)
    group(null) {
        SwitchPreference(stringResource(R.string.guide_hf_button), stringResource(R.string.guide_hf_button_detail), h.buttonOpensRobot, icon = RIcons.Headset) {
            g.stores.headset(h.copy(buttonOpensRobot = it)); g.changed()
        }
        PreferenceItem(stringResource(R.string.guide_hf_car), stringResource(if (g.files.carOn) R.string.guide_on else R.string.guide_hf_car_later), RIcons.Car,
            onClick = { g.a.openPage(Page.Settings(SettingsRoutes.CAR)) })
    }
    privacy(R.string.guide_hf_privacy)
    findIt(R.string.guide_path_hf)
}

// --------------------------------------------------------------------------------------------------------- 14. Helpers

/** A helper can take a question: its key is saved, it needs none (your own server), or (1.0.0) it opens its app. */
internal fun helpersSetUp(g: GuideCtx): Boolean =
    g.s.helpers.any { h ->
        val key = g.snap.facts?.keys?.containsKey(h.id) == true
        h.on && (HelperSettings.apiReady(h, key) || HelperSettings.route(h, key) == app.heyrobot.core.AskRoute.APP)
    }

private fun LazyListScope.helpers(g: GuideCtx) {
    title(R.string.guide_helpers_title)
    para(R.string.guide_helpers_intro)
    group(null) {
        PreferenceItem(stringResource(R.string.guide_helpers_row), stringResource(if (helpersSetUp(g)) R.string.guide_set_up else R.string.guide_helpers_later),
            RIcons.Cloud, onClick = { g.a.openPage(Page.Helpers) })
    }
    para(R.string.guide_helpers_sent)
    privacy(R.string.guide_helpers_privacy)
    findIt(R.string.guide_path_helpers)
}

// ------------------------------------------------------------------------------------------------ 15. Web and Termux

/** 1.0: what Robot's own web search can use for free (set up in Settings), each with its privacy level. */
private val FREE_SERVICES = listOf(SearchService.DDG_INSTANT, SearchService.WIKIPEDIA_ONLY, SearchService.SEARXNG_PUBLIC, SearchService.BRAVE,
    SearchService.SEARXNG_HOME)

private fun LazyListScope.webTermux(g: GuideCtx) {
    title(R.string.guide_web_title)
    para(R.string.guide_web_intro)
    // 1.0: the engines a search in your browser opens (nothing to set up), the default picked here as in Settings › Web search.
    group(R.string.web_browser_group) {
        for (e in BrowserEngine.entries) {
            val line = stringResource(engineLine(e))
            RadioPreference(e.title, if (e == g.files.browserEngine) stringResource(R.string.web_browser_default, line) else line,
                selected = e == g.files.browserEngine, trailing = { PrivacyChip(e.level) }) { g.stores.browserEngine(e); g.changed() }
        }
    }
    para(R.string.guide_web_free_intro)
    group(R.string.guide_web_free_group) {
        for (s in FREE_SERVICES) PreferenceItem(s.titleText.str(), stringResource(serviceLine(s)), trailing = { PrivacyChip(s.level) })
    }
    group(null) {
        PreferenceItem(stringResource(R.string.guide_web_row), stringResource(if (g.files.webOn) R.string.guide_on else R.string.guide_web_later), RIcons.Search,
            onClick = { g.a.openPage(Page.Settings(SettingsRoutes.WEB_SEARCH)) })
    }
    para(R.string.guide_termux_intro)
    group(null) {
        PreferenceItem(stringResource(R.string.guide_termux_row), stringResource(if (g.files.termuxOn) R.string.guide_on else R.string.guide_termux_later),
            RIcons.Terminal, onClick = { g.a.openPage(Page.Settings(SettingsRoutes.TERMUX)) })
    }
    privacy(R.string.guide_web_privacy)
    findIt(R.string.guide_path_web)
}

// ---------------------------------------------------------------------------------------------- 16. Updates and feedback

/** Robot's public contact (README), the same in every language. */
private const val FEEDBACK_ADDRESS = "hey@robot-app.ai" // l10n: not text (an email address)

private fun LazyListScope.updates(g: GuideCtx) {
    val u = g.files.updates
    title(R.string.guide_updates_title)
    para(R.string.guide_updates_intro)
    group(null) {
        SwitchPreference(stringResource(R.string.guide_updates_auto), stringResource(R.string.guide_updates_auto_detail), u.autoCheck, icon = RIcons.Refresh) {
            g.stores.updates(u.copy(autoCheck = it)); g.changed()
        }
        SwitchPreference(stringResource(R.string.guide_updates_test), stringResource(R.string.guide_updates_test_detail), u.testBuilds) {
            g.stores.updates(u.copy(testBuilds = it)); g.changed()
        }
    }
    // 1.1: the monthly backup reminder, off, as Settings › Back up and restore has it.
    group(null) {
        SwitchPreference(stringResource(R.string.backup_remind), stringResource(R.string.backup_remind_detail), g.files.backupRemind, icon = RIcons.Folder) {
            g.stores.backupReminder(it); g.changed()
        }
    }
    group(R.string.guide_feedback) {
        PreferenceItem(stringResource(R.string.guide_feedback_row), stringResource(R.string.guide_feedback_detail, FEEDBACK_ADDRESS), RIcons.Email,
            onClick = { g.a.openPage(Page.Settings(SettingsRoutes.feedback(FeedbackEntry.ABOUT))) })
    }
    privacy(R.string.guide_updates_privacy)
    findIt(R.string.guide_path_updates)
}

// --------------------------------------------------------------------------------------------------------- 17. Summary

/** What the summary reads now, from the settings, the other files and the phone. */
internal fun choices(g: GuideCtx): GuideChoices {
    val f = g.snap.facts
    val snap = g.snap
    return GuideChoices(
        robot = g.s, messages = g.files.messages, memory = g.files.memory, routines = g.files.routines, headset = g.files.headset, carOn = g.files.carOn,
        updates = g.files.updates, webOn = g.files.webOn, backupRemind = g.files.backupRemind, browserEngine = g.files.browserEngine.title, termuxOn = g.files.termuxOn, helpersSetUp = helpersSetUp(g),
        brainReady = snap.model(snap.fast)?.status == ModelStatus.VERIFIED, voiceReady = snap.voiceReady, assistant = f?.active == true,
        contacts = f?.contactsGranted == true, calendar = f?.calendarGranted == true, location = f?.locationGranted == true,
        fastTranslation = snap.mt?.status == ModelStatus.VERIFIED, cheogramInstalled = f?.cheogramInstalled == true,
        protonInstalled = f?.protonInstalled == true, nearestText = Nearby.mode(g.s, snap.nearestAnswer).titleText,
    )
}

/** The words of a summary line: its title and where Settings has it. */
private fun itemText(i: GuideItem): Pair<Int, Int> = when (i) {
    GuideItem.BRAIN -> R.string.guide_item_brain to R.string.guide_path_model
    GuideItem.VOICE_MODELS -> R.string.guide_item_voice_models to R.string.guide_path_model
    GuideItem.ASSISTANT -> R.string.guide_item_assistant to R.string.guide_path_setup
    GuideItem.ROBOT_VOICE -> R.string.guide_item_robot_voice to R.string.guide_path_voice
    GuideItem.WAKE_WORD -> R.string.guide_item_wake to R.string.guide_path_wake
    GuideItem.ONLY_WHILE_CHARGING -> R.string.guide_item_charging to R.string.guide_path_wake
    GuideItem.ASK_FIRST -> R.string.guide_item_ask to R.string.guide_path_safety
    GuideItem.VOICE_CARDS -> R.string.guide_item_voice_cards to R.string.guide_path_safety
    GuideItem.LOCK_SCREEN -> R.string.guide_item_locked to R.string.guide_path_safety
    GuideItem.TEXT_APP -> R.string.guide_item_text to R.string.guide_path_people
    GuideItem.EMAIL_APP -> R.string.guide_item_email to R.string.guide_path_people
    GuideItem.CONTACTS -> R.string.guide_item_contacts to R.string.guide_path_setup
    GuideItem.CALENDAR -> R.string.guide_item_calendar to R.string.guide_path_setup
    GuideItem.MESSAGES -> R.string.guide_item_messages to R.string.guide_path_messages
    GuideItem.ANNOUNCE -> R.string.guide_item_announce to R.string.guide_path_messages
    GuideItem.MEMORY -> R.string.guide_item_memory to R.string.guide_path_memory
    GuideItem.MEMORY_LOCKED -> R.string.guide_item_memory_locked to R.string.guide_path_memory
    GuideItem.WEATHER_HOME -> R.string.guide_item_home to R.string.guide_path_weather_only
    GuideItem.LOCATION -> R.string.guide_item_location to R.string.guide_path_weather_only
    GuideItem.NEAREST -> R.string.guide_item_nearest to R.string.guide_path_actions
    GuideItem.TRANSLATE_TRANSCRIPTS -> R.string.guide_item_translate to R.string.guide_path_conversation
    GuideItem.FAST_TRANSLATION -> R.string.guide_item_mt to R.string.guide_path_conversation
    GuideItem.BRIEF -> R.string.guide_item_brief to R.string.guide_path_routines
    GuideItem.HEADSET_BUTTON -> R.string.guide_item_headset to R.string.guide_path_headset
    GuideItem.CAR_MODE -> R.string.guide_item_car to R.string.guide_path_car
    GuideItem.HELPERS -> R.string.guide_item_helpers to R.string.guide_path_helpers
    GuideItem.WEB_SEARCH -> R.string.guide_item_web to R.string.guide_path_web_only
    GuideItem.BROWSER_ENGINE -> R.string.guide_item_browser to R.string.guide_path_web_only
    GuideItem.TERMUX -> R.string.guide_item_termux to R.string.guide_path_termux
    GuideItem.AUTO_UPDATES -> R.string.guide_item_updates to R.string.guide_path_updates_only
    GuideItem.BACKUP_REMINDER -> R.string.guide_item_backup to R.string.guide_path_backup
    GuideItem.TEST_BUILDS -> R.string.guide_item_test to R.string.guide_path_updates_only
}

/** "On", "Off", "Ready", "Allowed", a value, "Not set"; [locked]: what works on the lock screen, in the menus' language. */
@Composable
private fun stateText(l: SummaryLine, locked: Set<ToolName>): String {
    if (l.item == GuideItem.LOCK_SCREEN) return LockScreen.allowed(locked, LocalLangs.current)
    l.valueText?.let { return it.str() }
    val on = l.on ?: return stringResource(R.string.guide_not_set)
    return stringResource(when (l.item) {
        GuideItem.BRAIN, GuideItem.VOICE_MODELS, GuideItem.FAST_TRANSLATION -> if (on) R.string.guide_ready else R.string.guide_not_downloaded
        GuideItem.ASSISTANT -> if (on) R.string.guide_done_state else R.string.guide_not_yet
        GuideItem.CONTACTS, GuideItem.CALENDAR, GuideItem.LOCATION -> if (on) R.string.guide_allowed else R.string.guide_not_allowed
        GuideItem.HELPERS, GuideItem.WEB_SEARCH, GuideItem.TERMUX -> if (on) R.string.guide_set_up else R.string.guide_not_set_up
        else -> if (on) R.string.guide_on else R.string.guide_off
    })
}

/** The "Try saying…" lines: English, and French where Robot understands it before the model. */
private val TRY = listOf(
    R.string.guide_try_timer, R.string.guide_try_text, R.string.guide_try_brief, R.string.guide_try_messages, R.string.guide_try_remember,
    R.string.guide_try_interpreter,
)

private fun LazyListScope.summary(g: GuideCtx) {
    val lines = GuideSummary.lines(choices(g))
    title(R.string.guide_summary_title)
    para(R.string.guide_summary_intro)
    fitWarning(g)
    group(R.string.guide_summary_now) {
        for (l in lines) {
            val (t, path) = itemText(l.item)
            PreferenceItem(stringResource(t), stringResource(R.string.guide_summary_line, stateText(l, g.s.lockedTools), stringResource(path)))
        }
    }
    privacy(R.string.guide_summary_privacy)
    group(R.string.guide_summary_try) {
        PreferenceBlock {
            for (t in TRY) Text(stringResource(t), style = MaterialTheme.typography.bodyLarge)
        }
    }
    group(R.string.guide_summary_more) { for (e in EXAMPLES.take(4)) ExampleBlock(e) }
    findIt(R.string.guide_path_guide)
}
