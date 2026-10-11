package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.LookStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The panel's card parts (ui/common/Cards.kt): what they say to TalkBack, their sizes, and how they look in both looks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class CardsTest {
    @get:Rule val rule = createComposeRule()

    private val log = ArrayList<String>()

    /** A text waiting for "Do it" with two numbers to choose from, a done timer, Claude's offer and a notice. */
    @Composable
    private fun Cards() {
        Column(Modifier.padding(16.dp).widthIn(max = 411.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeRow("Robot isn't your digital assistant any more.", onDismiss = { log += "dismiss" }, action = "Open settings", onAction = { log += "settings" })
            PanelCard {
                CardHeader(buildAnnotatedString { append("Text "); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Mom") }; append(" in Cheogram") },
                    "Which one?", leading = { CardIcon(RIcons.Message) })
                CardInset("I'm running late, see you at 7.")
                Column(Modifier.padding(top = 8.dp)) {
                    CardChoiceRow("Mom · mobile", onClick = { log += "mobile" }, icon = RIcons.Person)
                    CardChoiceRow("Mom · work", onClick = { log += "work" }, icon = RIcons.Phone)
                }
                CardButtons {
                    TextButton(onClick = { log += "cancel" }) { Text("Cancel") }
                    Button(onClick = { log += "do" }) { Text("Do it") }
                }
            }
            PanelCard {
                CardHeader("Timer for 10 minutes", "Done", leading = { CardIcon(RIcons.Timer) },
                    trailing = { Icon(RIcons.Check, "Done", tint = MaterialTheme.colorScheme.primary) })
            }
            PanelCard {
                CardHeader("Couldn't add the event", "Calendar access isn't allowed", error = true, leading = { CardIcon(RIcons.Event) })
                CardButtons { FilledTonalButton(onClick = { log += "allow" }) { Text("Allow") } }
            }
            PanelCard(style = cardStyle(CardKind.OFFER)) {
                CardHeader("Ask Claude?", "Sends: your question.", leading = { CardIcon(RIcons.Cloud) })
                CardButtons {
                    TextButton(onClick = { log += "not now" }) { Text("Not now") }
                    FilledTonalButton(onClick = { log += "send" }) { Text("Send") }
                }
            }
        }
    }

    @Test fun standardLight() {
        rule.show { Cards() }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("cards_light")
    }

    @Test fun standardDark() {
        rule.show(dark = true) { Cards() }
        rule.shot("cards_dark")
    }

    @Test @Config(fontScale = 2f)
    fun standardAtTwiceTheTextSize() {
        rule.show { Cards() }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("cards_font200")
    }

    @Test fun classicKeepsRobot04sColours() {
        rule.show(classic = true) { Cards() }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("cards_classic")
    }

    @Test fun choicesAreButtonRowsAndEverythingDoesWhatItSays() {
        rule.show { Cards() }
        rule.onNodeWithText("Mom · mobile").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHeightIsAtLeast(56.dp)
        for (t in listOf("Mom · work", "Mom · mobile", "Do it", "Cancel", "Not now", "Send", "Allow", "Open settings")) rule.onNodeWithText(t).performClick()
        val dismiss = rule.onNodeWithContentDescription("Dismiss")
        // A 40 dp icon button whose touch area is 48 dp.
        assertEquals(true, with(rule.density) { dismiss.fetchSemanticsNode().touchBoundsInRoot.height.toDp() } >= 48.dp)
        dismiss.performClick()
        assertEquals(listOf("work", "mobile", "do", "cancel", "not now", "send", "allow", "settings", "dismiss"), log)
        // A finished card says so in words, not only with its symbol.
        rule.onNodeWithContentDescription("Done", useUnmergedTree = true).assertExists()
    }

    @Test fun theStyleFollowsTheLook() {
        var stock: CardStyle? = null
        var classic: CardStyle? = null
        rule.show {
            stock = cardStyle(CardKind.OFFER, LookStyle.STOCK)
            classic = cardStyle(CardKind.OFFER, LookStyle.CLASSIC)
        }
        rule.waitForIdle()
        assertEquals(app.heyrobot.ui.theme.RobotColors.Light.surfaceContainerHighest, stock!!.container)
        assertEquals(app.heyrobot.ui.theme.RobotColors.Light.tertiaryContainer, classic!!.container)
    }
}
