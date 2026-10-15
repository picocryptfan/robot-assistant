// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import android.animation.ValueAnimator
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.heyrobot.R
import kotlinx.coroutines.delay

// Main and voice processes. 1.0: the robot in the assistant panel's orb, also on the lock screen. One of four one-colour
// drawings (res/drawable/ic_robot_*), by what Robot is doing; the caller tints it. Decorative: the status line beside it
// says the same in words (a polite live region), so TalkBack hears it once.

/** What the robot in the panel shows (the panel picks it from its phase: assistant Phase.face). */
enum class RobotFace {
    /** Idle, or a card waiting for a tap: the talking head. */
    IDLE,
    /** Listening, or writing down what was said: the head with sound arcs for ears. */
    LISTENING,
    /** Working on the reply: the talking head with three dots. */
    TYPING,
    /** Reading aloud: the head whose mouth is a short voice wave. */
    SPEAKING,
}

@DrawableRes
fun RobotFace.drawable(): Int = when (this) {
    RobotFace.IDLE -> R.drawable.ic_robot_idle
    RobotFace.LISTENING -> R.drawable.ic_robot_listening
    RobotFace.TYPING -> R.drawable.ic_robot_typing
    RobotFace.SPEAKING -> R.drawable.ic_robot_speaking
}

/** The cross-fade from one face to the next. */
private const val FADE_MS = 150

/**
 * How long [to] waits before it replaces [from]. Robot's voice stops for a moment between two sentences: the speaking
 * face waits 400 ms before it turns idle or typing, so it doesn't flicker. Listening, and every other change, shows at
 * once.
 */
fun faceDelayMs(from: RobotFace, to: RobotFace): Long =
    if (from == RobotFace.SPEAKING && (to == RobotFace.IDLE || to == RobotFace.TYPING)) 400L else 0L

/**
 * [face] in [tint], filling [modifier]'s size. It cross-fades in about 150 ms, or changes at once when the system's
 * animations are off (animator duration scale 0, which "Remove animations" sets too).
 */
@Composable
fun RobotFaceIcon(face: RobotFace, tint: Color, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(face) }
    LaunchedEffect(face) {
        delay(faceDelayMs(shown, face))
        shown = face
    }
    val tagged = modifier.testTag("robot-face-" + shown.name.lowercase())
    if (ValueAnimator.areAnimatorsEnabled()) {
        Crossfade(shown, tagged, animationSpec = tween(FADE_MS), label = "robot-face") { f ->
            Icon(painterResource(f.drawable()), contentDescription = null, Modifier.fillMaxSize(), tint = tint)
        }
    } else {
        Box(tagged) { Icon(painterResource(shown.drawable()), contentDescription = null, Modifier.fillMaxSize(), tint = tint) }
    }
}
