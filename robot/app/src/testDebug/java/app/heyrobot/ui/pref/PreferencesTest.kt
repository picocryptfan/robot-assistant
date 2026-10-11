package app.heyrobot.ui.pref

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.Mark
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings building blocks (ui/pref): each is one TalkBack node with the right role, state and label, at least
 * 48 dp, does what it says, and looks like Android's Settings (screenshots: light, dark, text at 200 %).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PreferencesTest {
    @get:Rule val rule = createComposeRule()

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)
    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    private enum class Speak(val title: String, val detail: String) {
        VOICE("Voice only", "When you asked by voice"), ALWAYS("Always", "Also when you typed"), NEVER("Never", "Only the words on screen")
    }

    /** A Voice page as step (b) will build it: every block once, in groups. */
    @Composable
    private fun Gallery() {
        var wake by remember { mutableStateOf(true) }
        var words by remember { mutableStateOf(true) }
        var phrase by remember { mutableStateOf(true) }
        var speak by remember { mutableStateOf(Speak.VOICE) }
        var speed by remember { mutableFloatStateOf(1.2f) }
        PreferenceScreen("Voice", onBack = {}) {
            item(key = "main") { MainSwitchBar("Use wake words", wake) { wake = it } }
            preferenceGroup("Robot's voice", "voice") {
                PreferenceItem("Voice", "Kokoro · Heart", RIcons.RecordVoiceOver, onClick = {})
                SliderPreference("Speed", speed, 0.8f..1.5f, 6, { "%.1f×".format(it) }) { speed = it }
            }
            preferenceGroup("Replies", "replies") {
                ListPreference("Speak replies", speak, Speak.entries, { it.title }, { it.detail }) { speak = it }
                SwitchPreference("Show words while you speak", "On", words) { words = it }
                SwitchPreference("Only while charging", "Turn on wake words first", false, enabled = false) {}
            }
            preferenceGroup("Wake phrases", "phrases") {
                TwoTargetSwitchPreference("“hey robot”", "Sensitivity Medium", phrase, onClick = {}, onCheckedChange = { phrase = it },
                    switchLabel = "Listen for “hey robot”")
            }
            preferenceGroup("Assistant", "status") {
                StatusPreference("Speech recognition", "Ready · 640 MB", Mark.OK)
                StatusPreference("Microphone", "Not allowed yet", Mark.TODO, action = { FilledTonalButton(onClick = {}) { Text("Allow") } })
                StatusPreference("Voice", "The file is damaged", Mark.WARN, onClick = {})
            }
            preferenceFooter("Wake words listen on this phone only; nothing is recorded or saved.")
        }
    }

    @Test fun galleryLight() {
        rule.show { Gallery() }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("prefs_light")
        // The rest of the page: the two-target row, the status rows and the footer.
        rule.onNode(hasScrollToIndexAction()).performScrollToKey("footer")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("prefs_light_end")
    }

    @Test fun galleryDark() {
        rule.show(dark = true) { Gallery() }
        rule.shot("prefs_dark")
    }

    @Test @Config(fontScale = 2f)
    fun galleryAtTwiceTheTextSize() {
        rule.show { Gallery() }
        // Titles wrap rather than being cut, and no two targets crowd each other.
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Use wake words").assertHeightIsAtLeast(72.dp)
        rule.shot("prefs_font200")
    }

    @Test fun groupHeadersAreHeadings() {
        rule.show { Gallery() }
        rule.onNodeWithText("Robot's voice").assert(isHeading())
        rule.onNodeWithText("Replies").assert(isHeading())
    }

    @Test fun aSwitchRowIsOneSwitchThatTheWholeRowToggles() {
        var on = false
        rule.show { var v by remember { mutableStateOf(on) }; PreferenceGroup("Replies") { SwitchPreference("Show words while you speak", "Off", v) { v = it; on = it } } }
        val row = rule.onNodeWithText("Show words while you speak")
        row.assert(role(Role.Switch)).assertIsOff().assertHeightIsAtLeast(72.dp)
        // The summary is part of the same node.
        row.assert(hasText("Off"))
        row.performClick()
        row.assertIsOn()
        assertEquals(true, on)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aDisabledSwitchRowDoesNothing() {
        rule.show { PreferenceGroup(null) { SwitchPreference("Only while charging", null, false, enabled = false) { error("toggled") } } }
        rule.onNodeWithText("Only while charging").assertIsNotEnabled().performClick().assertIsOff()
    }

    @Test fun theMainSwitchBarIsASwitch() {
        rule.show { var v by remember { mutableStateOf(false) }; MainSwitchBar("Use Ask Claude", v) { v = it } }
        rule.onNodeWithText("Use Ask Claude").assert(role(Role.Switch)).assertIsOff().assertHeightIsAtLeast(72.dp).performClick().assertIsOn()
    }

    @Test fun aListPreferenceShowsItsValueAndPicksInARadioDialog() {
        var picked: Speak? = null
        rule.show {
            var v by remember { mutableStateOf(Speak.VOICE) }
            PreferenceGroup(null) { ListPreference("Speak replies", v, Speak.entries, { it.title }, { it.detail }) { v = it; picked = it } }
        }
        val row = rule.onNodeWithText("Speak replies")
        row.assert(role(Role.Button)).assert(state("Voice only"))
        row.performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("Voice only").assert(role(Role.RadioButton)).assertIsSelected()
        rule.onNodeWithText("Always").assertIsNotSelected().assert(hasText("Also when you typed")).assertHeightIsAtLeast(56.dp)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("prefs_radio_dialog")
        rule.onNodeWithText("Always").performClick()
        // A pick closes the dialog.
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(Speak.ALWAYS, picked)
        row.assert(state("Always"))
        // Cancel leaves the value as it was.
        row.performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        row.assert(state("Always"))
    }

    @Test fun aTwoTargetRowHasTwoLabelledTargets() {
        var opened = 0
        rule.show {
            var v by remember { mutableStateOf(true) }
            PreferenceGroup(null) {
                TwoTargetSwitchPreference("“hey robot”", "Sensitivity Medium", v, onClick = { opened++ }, onCheckedChange = { v = it },
                    switchLabel = "Listen for “hey robot”")
            }
        }
        rule.onNodeWithText("“hey robot”").assert(hasText("Sensitivity Medium")).performClick()
        assertEquals(1, opened)
        rule.onNodeWithContentDescription("Listen for “hey robot”").assert(role(Role.Switch)).assertIsOn().performClick().assertIsOff()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    // At 411 dp Robolectric never settles once a text field in any Compose dialog has the focus (a plain M3 AlertDialog
    // with an OutlinedTextField does the same), so this one runs on Robolectric's default 320 dp screen.
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun anEditDialogKeepsSaveOffWhileTheTextIsWrong() {
        var saved: String? = null
        rule.show {
            var v by remember { mutableStateOf("") }
            PreferenceGroup(null) {
                TextPreference("Phone region", v, "From the SIM", "Region", "Two letters, such as CA",
                    validate = { if (it.isEmpty() || Regex("[A-Za-z]{2}").matches(it)) null else "Two letters, such as CA" }) { v = it; saved = it }
            }
        }
        rule.onNodeWithText("Phone region").assert(hasText("From the SIM")).performClick()
        val field = rule.onNode(hasText("Region") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("USA")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        field.performTextReplacement("CA")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("CA", saved)
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithText("Phone region").assert(hasText("CA"))
    }

    @Test fun statusSymbolsAreLabelled() {
        rule.show {
            PreferenceGroup(null) {
                StatusPreference("A", null, Mark.OK)
                StatusPreference("B", null, Mark.TODO)
                StatusPreference("C", null, Mark.WARN, onClick = {})
                StatusPreference("D", "Not allowed yet", Mark.TODO, action = { FilledTonalButton(onClick = {}) { Text("Allow") } })
            }
        }
        for ((label, n) in listOf("Done" to 1, "To do" to 2, "Problem" to 1)) rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).assertCountEquals(n)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("A").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Done")))
    }

    @Test fun aSliderSaysItsValue() {
        var done = 0f
        rule.show { PreferenceGroup(null) { SliderPreference("Speed", 1.2f, 0.8f..1.5f, 6, { "%.1f×".format(it) }) { done = it } } }
        rule.onNodeWithContentDescription("Speed").assert(state("1.2×"))
        assertEquals(0f, done)
    }
}
