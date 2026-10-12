package app.heyrobot.ui.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.heyrobot.BuildConfig
import app.heyrobot.core.Approval
import app.heyrobot.core.Escalation
import app.heyrobot.core.LockScreen
import app.heyrobot.core.LookSettings
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.LookStore
import app.heyrobot.sys.WebStore
import app.heyrobot.core.web.WebSettings
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. The Settings tab (0.4.x redesign, replacing one long page of cards): a list of pages, as Android's own
// Settings is, each row's title over its state now. The pages are ui/settings' (by route), the Wake word, Safety,
// Helpers and Conversation pages, Assistant setup and the Model tab.

/** The Settings tab. [look] is Settings › Display's (LookStore; a test passes its own). */
@Composable
fun SettingsRoot(s: RobotSettings, snap: Snapshot, a: AppActions, modifier: Modifier = Modifier, look: LookSettings = LookStore.current(LocalContext.current),
    web: WebSettings = WebStore.current(LocalContext.current)) {
    fun go(route: String) = a.openPage(Page.Settings(route))
    PreferenceScreen("Settings", onBack = null, modifier = modifier) {
        preferenceGroup(null, "voice") {
            PreferenceItem("Wake word", wakeSummary(s), RIcons.Mic, onClick = { a.openPage(Page.WakePhrases) })
            PreferenceItem("Voice", voiceSummary(s), RIcons.RecordVoiceOver, onClick = { go(SettingsRoutes.VOICE) })
            PreferenceItem("Actions", actionsSummary(s), RIcons.TouchApp, onClick = { go(SettingsRoutes.ACTIONS) })
            PreferenceItem("Safety & privacy", safetySummary(s), RIcons.Shield, onClick = { a.openPage(Page.Safety) })
        }
        preferenceGroup(null, "reach") {
            PreferenceItem("People, notes and calendar", peopleSummary(s, snap.facts), RIcons.Contacts, onClick = { go(SettingsRoutes.PEOPLE_NOTES_CALENDAR) })
            PreferenceItem("Weather", s.weatherHome?.let { "Home: ${it.name}" } ?: "No home set", RIcons.Sunny, onClick = { go(SettingsRoutes.WEATHER) })
            PreferenceItem("Web search", webSummary(web, null), RIcons.Search, onClick = { go(SettingsRoutes.WEB_SEARCH) })
            PreferenceItem("Helpers", helpersSummary(s, snap.facts), RIcons.Cloud, onClick = { a.openPage(Page.Helpers) })
            PreferenceItem("Conversation", conversationSummary(s), RIcons.Transcribe, onClick = { a.openPage(Page.ConversationSettings) })
        }
        preferenceGroup(null, "app") {
            PreferenceItem("Model", modelSummary(s, snap), RIcons.Chip, onClick = { a.openTab(Tab.MODEL) })
            PreferenceItem("History", if (s.saveHistory) "Saving conversations" else "Not saving conversations", RIcons.History,
                onClick = { go(SettingsRoutes.HISTORY) })
            PreferenceItem("Display", displaySummary(look), RIcons.Palette, onClick = { go(SettingsRoutes.DISPLAY) })
        }
        preferenceGroup(null, "system") {
            val attention = setupNeedsAttention(snap.facts)
            PreferenceItem("Assistant setup", when (attention) { null -> "Checking…"; true -> "Needs attention"; false -> "Robot is your assistant" },
                if (attention == true) null else RIcons.SettingsVoice, onClick = a.openSetup,
                leading = if (attention == true) ({ Icon(RIcons.Error, "Problem", tint = MaterialTheme.colorScheme.error) }) else null)
            PreferenceItem("Advanced", "ADB fixes, voice log", RIcons.Terminal, onClick = { go(SettingsRoutes.ADVANCED) })
            PreferenceItem("About", "Robot ${BuildConfig.VERSION_NAME}", RIcons.Info, onClick = { go(SettingsRoutes.ABOUT) })
        }
    }
}

/** "On · “hey robot” and 2 others", "On · no phrase on", "Off". */
internal fun wakeSummary(s: RobotSettings): String {
    if (!s.wakeWord) return "Off"
    val on = s.wakePhrases.filter { it.enabled }
    return "On · " + when (on.size) {
        0 -> "no phrase on"
        1 -> "“${on[0].text}”"
        2 -> "“${on[0].text}” and 1 other"
        else -> "“${on[0].text}” and ${on.size - 1} others"
    }
}

/** "Heart · speaks when you talk". */
internal fun voiceSummary(s: RobotSettings): String = s.voiceChoice.title + " · " + when (s.speakReplies) {
    SpeakReplies.VOICE -> "speaks when you talk"
    SpeakReplies.ALWAYS -> "always speaks"
    SpeakReplies.NEVER -> "doesn't speak"
}

/** "5 ask first · 1 off": how many actions ask first and how many are off (the rest just happen). */
internal fun actionsSummary(s: RobotSettings): String {
    val tools = ToolName.entries.filter { !it.internal }
    val ask = tools.count { s.approval(it) == Approval.ASK }
    val off = tools.count { s.approval(it) == Approval.OFF }
    return listOfNotNull(if (ask == 0) "None ask first" else "$ask ask first", if (off > 0) "$off off" else null).joinToString(" · ")
}

/** "When you didn't ask for it · locked: questions, timers and alarms". */
internal fun safetySummary(s: RobotSettings): String = s.safetyCards.title + " · locked: " + LockScreen.allowed(s.lockedTools)

/** "Cheogram · Proton Mail". */
internal fun peopleSummary(s: RobotSettings, f: AssistantFacts?): String =
    s.textAppFor(f?.cheogramInstalled == true).title + " · " + s.emailAppFor(f?.protonInstalled == true).title

/** "Claude · Ask first", "Claude, Jarvis · Automatic", "None on". */
internal fun helpersSummary(s: RobotSettings, f: AssistantFacts?): String {
    val on = s.helpers.filter { it.on }
    if (on.isEmpty()) return if (s.helpers.isEmpty()) "None" else "None on"
    val names = on.take(2).joinToString(", ") { it.name } + if (on.size > 2) " and ${on.size - 2} more" else ""
    val keySaved = { h: app.heyrobot.core.helpers.HelperConfig -> f?.keys?.containsKey(h.id) == true }
    val target = HelperSettings.target(s, keySaved)
    return names + (target?.let { " · " + escalationLabel(HelperSettings.escalation(s, it, keySaved(it))) } ?: "")
}

/** "When Robot needs help" in the app's words (core's titles speak as you: "Ask me first"). */
fun escalationLabel(e: Escalation): String = when (e) {
    Escalation.OFF -> "Off"
    Escalation.ASK -> "Ask first"
    Escalation.AUTO -> "Automatic"
}

/** "Live words on · translating". */
internal fun conversationSummary(s: RobotSettings): String =
    "Live words " + (if (s.convPartials) "on" else "off") + if (s.convTranslate) " · translating" else ""

/** "Fast · stays loaded 5 min"; 0.4.7, the general E2B (Fast without the Tensor G5's TPU): "Fast on the GPU · …". */
internal fun modelSummary(s: RobotSettings, snap: Snapshot): String {
    val m = ModelCatalogue.resolve(s.brainModelChoice, snap.phone)
    val where = if (m.gpu) " on the " + ModelCatalogue.backend(m, snap.phone, s.modelGpu, snap.gpuFailure?.model == m).title else ""
    return m.title + where + " · " + if (s.keepModelMinutes == RobotSettings.KEEP_ALWAYS) "always loaded" else "stays loaded ${s.keepModelMinutes} min"
}

/** Whether Assistant setup has something to fix (null: not known yet). */
internal fun setupNeedsAttention(f: AssistantFacts?): Boolean? =
    f?.let { !(it.active == true && it.recognizerIsRobot && it.micGranted && it.notificationsGranted) }
