// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import app.heyrobot.core.Phone
import app.heyrobot.core.RobotSettings
import app.heyrobot.ui.AppActions

/**
 * What the screens asked for, by name ("openPage Safety", "say Copied."), so a test can check a tap reached the app and
 * nothing else did.
 */
class ActionLog : ArrayList<String>() {
    val actions: AppActions = AppActions(
        talk = { add("talk $it") }, openSetup = { add("openSetup") }, openTab = { add("openTab $it") },
        openConversation = { add("openConversation $it") }, askPermissions = { add("askPermissions") },
        openAssistantSettings = { add("openAssistantSettings") }, download = { add("download $it") },
        cancelDownload = { add("cancelDownload $it") }, import = { add("import $it") }, verify = { add("verify $it") },
        deleteModel = { add("deleteModel $it") }, allowOwnTpu = { allow, sha -> add("allowOwnTpu $allow $sha") },
        deleteConversation = { add("deleteConversation $it") }, deleteAllConversations = { add("deleteAllConversations") },
        removeSavedMedia = { add("removeSavedMedia") }, speakTest = { add("speakTest") }, say = { add("say $it") },
        voiceDownload = { add("voiceDownload $it") }, voiceCancel = { add("voiceCancel $it") }, voiceDelete = { add("voiceDelete $it") },
        voiceBench = { add("voiceBench") }, voiceReset = { add("voiceReset") }, askContacts = { add("askContacts") },
        askCalendar = { add("askCalendar") }, openAppSettings = { add("openAppSettings") }, openNote = { add("openNote $it") },
        helperWindow = { id, mode -> add("helperWindow $id $mode") }, helperMoved = { id, drop -> add("helperMoved $id $drop") },
        deleteHelperKey = { add("deleteHelperKey $it") }, removeHelper = { add("removeHelper $it") }, openPage = { add("openPage $it") },
        bargeTest = { add("bargeTest $it") }, askLocation = { add("askLocation") }, openUsageAccess = { add("openUsageAccess") },
        askMedia = { add("askMedia") }, transcribe = { add("transcribe") }, interpret = { add("interpret") },
        openTranscriptionCheck = { add("openTranscriptionCheck") }, mtDownload = { add("mtDownload") }, mtCancel = { add("mtCancel") },
        mtDelete = { add("mtDelete") }, openVoiceprint = { add("openVoiceprint") }, deleteVoiceprint = { add("deleteVoiceprint") },
        runRoutine = { add("runRoutine $it") }, openReady = { add("openReady $it") }, askPermission = { add("askPermission $it") },
        checkModel = { add("checkModel") }, tryGpuAgain = { add("tryGpuAgain") }, tryTpuAgain = { add("tryTpuAgain") }, reportText = { "REPORT for ${it.model.id}" }, copyReport = { add("copyReport $it") },
        shareReport = { add("shareReport $it") }, dismissProblem = { add("dismissProblem ${it.time}") },
        deleteProblem = { add("deleteProblem ${it.time}") }, openGuide = { add("openGuide") },
    )
}

/** 0.4.7's phones, as Android's Build fields name them: a Tensor G5 (Fast is the TPU build) and a Pixel 9a (the general E2B, GPU or CPU). */
val PIXEL_10_PRO = Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5", 16_000_000_000L)
val PIXEL_9A = Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_600_000_000L)
/** 0.8: a Pixel 11 (Tensor G6): Fast is the Tensor G6's TPU build, in beta. */
val PIXEL_11 = Phone("Google", "Pixel 11", "Google", "Tensor G6", 12_000_000_000L)
/**
 * 1.2: a Galaxy S25 (Snapdragon 8 Elite) as Android is expected to name it (Build.SOC_MANUFACTURER "QTI", Build.SOC_MODEL
 * "SM8750": UNVERIFIED until the Test Bench reads a real one): the general E2B, GPU or CPU, no NPU build.
 */
val GALAXY_S25 = Phone("samsung", "SM-S931W", "QTI", "SM8750", 11_500_000_000L)

/**
 * Settings as a page holds them: [start], then every copy the page's update wrote, in order ([saved]), the page shown
 * again with the latest as the app does.
 */
class SettingsHost(start: RobotSettings = RobotSettings()) {
    val saved = ArrayList<RobotSettings>()
    var now by mutableStateOf(start)
        private set

    fun update(s: RobotSettings) {
        saved += s
        now = s
    }

    val last: RobotSettings get() = saved.last()
}

/** Shows [page] with [host]'s settings, in Robot's theme. */
fun ComposeContentTestRule.showSettings(host: SettingsHost, dark: Boolean = false, page: @Composable (RobotSettings, (RobotSettings) -> Unit) -> Unit) {
    show(dark = dark) { page(host.now, host::update) }
}

/** Scrolls the page's list until the node with [text] (exactly one of its texts, or part of one) is on screen. */
fun ComposeContentTestRule.scrollToText(text: String, substring: Boolean = false) {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
    waitForIdle()
}

/** What the app knows about the phone, everything allowed and set up unless a test says otherwise. */
fun facts(
    roleHeld: Boolean = true,
    active: Boolean? = true,
    recognizerIsRobot: Boolean = true,
    micGranted: Boolean = true,
    notificationsGranted: Boolean = true,
    listening: Boolean? = true,
): app.heyrobot.sys.AssistantFacts = app.heyrobot.sys.AssistantFacts(
    roleHeld = roleHeld, active = active, recognizer = if (recognizerIsRobot) app.heyrobot.core.AdbFixes.RECOGNIZER else "unset",
    recognizerIsRobot = recognizerIsRobot, recognizersFound = 1, micGranted = micGranted, notificationsGranted = notificationsGranted,
    listening = listening, listeningNote = null,
)

/** The row (anything tappable) whose texts include [title]: not the page's title of the same name. */
fun ComposeContentTestRule.onRow(title: String): SemanticsNodeInteraction = onNode(hasText(title) and hasClickAction())

/** The node with [text] inside the dialog showing (a radio dialog's choice, its buttons). */
fun ComposeContentTestRule.inDialog(text: String, substring: Boolean = false): SemanticsNodeInteraction =
    onNode(hasText(text, substring = substring) and hasAnyAncestor(isDialog()))
