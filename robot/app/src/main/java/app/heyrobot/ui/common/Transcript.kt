package app.heyrobot.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ToolName
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.ui.classic.ClassicBubbleTurn
import app.heyrobot.ui.theme.ClassicTheme

// Main and voice processes. One turn of a conversation, as the panel and History › a conversation show it: 0.6.0's chat
// bubbles (ui/classic), in the classic look's type. 1.0: the only look the panel has.

/** One turn, as both screens hand it over. [helper]: a helper wrote it ([by] its name, [home] a server on your network). */
data class TurnUi(
    val id: String,
    /** Your words (else a reply). */
    val user: Boolean,
    val text: String,
    val attachment: Attachment = Attachment.NONE,
    /** A kept photo or screenshot. */
    val thumb: Bitmap? = null,
    /** The screen's text kept with the question ("Keep screenshots and photos in history"). */
    val screenText: String? = null,
    val actions: List<ActionView> = emptyList(),
    val streaming: Boolean = false,
    /** 1.0: TalkBack reads the answer once it is complete (the panel while replies aren't spoken; [answerSemantics]). */
    val announce: Boolean = false,
    val stopped: Boolean = false,
    val error: Boolean = false,
    val helper: Boolean = false,
    /** Read off the Claude app's screen. */
    val readBack: Boolean = false,
    val by: String? = null,
    val home: Boolean = false,
    /** "Jarvis searched the web · nasa.gov", the cost. */
    val footer: List<String> = emptyList(),
    val offer: OfferView? = null,
    /** 0.7: the reply card under a reply (ui/common ReplyCard). */
    val reply: ReplyCardView? = null,
    /** 0.9: a web answer's card (sources, what was sent, credits), in the panel only. */
    val web: AnswerCard? = null,
) {
    /** The cards to draw: a web answer's own card stands in for its search's action card. */
    val cards: List<ActionView> get() = if (web == null) actions else actions.filter { it.tool != ToolName.WEB_SEARCH || it.status != ActionStatus.DONE }
}

/** What a turn's buttons do. History passes [None]: its cards have no buttons. */
class TurnActions(
    /** A card's "Do it" (true) or "Cancel" (false), by its id. */
    val confirm: ((String, Boolean) -> Unit)? = null,
    val card: CardActions? = null,
    /** The offer's Send and No thanks, by the reply's id. */
    val acceptOffer: (String) -> Unit = {},
    val dismissOffer: (String) -> Unit = {},
    /** 0.7: a reply card's taps, by the reply's id; null (History): no buttons. */
    val reply: ((String, ReplyOp) -> Unit)? = null,
    /** A web answer's source (or a licence) tapped: the reply's id and the URL, to open in the browser. */
    val openSource: (String, String) -> Unit = { _, _ -> },
) {
    companion object {
        val None = TurnActions()
    }
}

/** One turn as a chat bubble (your words, or a reply with its cards), in the classic look's type wherever it is shown. */
@Composable
fun TurnView(t: TurnUi, actions: TurnActions, modifier: Modifier = Modifier) = ClassicTheme { ClassicBubbleTurn(t, actions, modifier) }

/** The space between turns in a list of them. */
val TURN_GAP = 10.dp
