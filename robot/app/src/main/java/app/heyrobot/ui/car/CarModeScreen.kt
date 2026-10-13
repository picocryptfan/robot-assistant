package app.heyrobot.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heyrobot.core.car.CarScreen
import app.heyrobot.ui.common.RIcons

// The car screen (0.8, design §4.3 "Car screen"): black, high contrast, big buttons (Talk, Call, Navigate home; Messages
// once 0.7's messages are there), a small End car mode, and one status line of at most 40 characters. No bubbles, no
// lists, no scrolling: nothing to read while driving. Used by CarModeActivity in :voice; the drawing is here so the tests
// can take its picture.

/** What the car screen shows. [title]: "Car mode", or "Car mode · unlocked features" with the key in the trusted session. */
data class CarScreenState(
    val title: String = "Car mode",
    val trusted: Boolean = false,
    /** The one status line ("Listening…", "Calling Mom"). */
    val status: String = "",
    val buttons: List<CarScreen.Button> = CarScreen.buttons(messages = false, home = true),
)

class CarScreenActions(
    val talk: () -> Unit = {},
    val messages: () -> Unit = {},
    val call: () -> Unit = {},
    val navigateHome: () -> Unit = {},
    val end: () -> Unit = {},
)

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Muted = Color(0xFFBDBDBD)
private val Tile = Color(0xFF1E1E1E)
private val Accent = Color(0xFF8AB4F8)

@Composable
fun CarModeScreen(s: CarScreenState, a: CarScreenActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Black).systemBarsPadding().padding(16.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.trusted) Icon(RIcons.Key, "Unlocked features on", tint = Accent, modifier = Modifier.size(28.dp).padding(end = 8.dp))
                Text(s.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(CarScreen.status(s.status), color = Muted, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp))
            val big = s.buttons.filter { it != CarScreen.Button.END }
            for (row in big.chunked(2)) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (b in row) BigButton(b, a, Modifier.weight(1f))
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (CarScreen.Button.END in s.buttons) {
                TextButton(onClick = a.end, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                    Text("End car mode", color = Muted, fontSize = 18.sp)
                }
            }
        }
    }
}

private fun label(b: CarScreen.Button): Pair<String, ImageVector> = when (b) {
    CarScreen.Button.TALK -> "Talk" to RIcons.Mic
    CarScreen.Button.MESSAGES -> "Messages" to RIcons.Message
    CarScreen.Button.CALL -> "Call" to RIcons.Call
    CarScreen.Button.NAVIGATE_HOME -> "Navigate home" to RIcons.Navigate
    CarScreen.Button.END -> "End car mode" to RIcons.Close
}

@Composable
private fun BigButton(b: CarScreen.Button, a: CarScreenActions, modifier: Modifier) {
    val (text, icon) = label(b)
    val onClick = when (b) {
        CarScreen.Button.TALK -> a.talk
        CarScreen.Button.MESSAGES -> a.messages
        CarScreen.Button.CALL -> a.call
        CarScreen.Button.NAVIGATE_HOME -> a.navigateHome
        CarScreen.Button.END -> a.end
    }
    // At least 120 dp each way (the design's big buttons), whole tile one target.
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 120.dp).semantics { contentDescription = text },
        shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = if (b == CarScreen.Button.TALK) Accent else Tile,
            contentColor = if (b == CarScreen.Button.TALK) Black else White)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
