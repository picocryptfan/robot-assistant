package app.heyrobot.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.theme.LocalLook
import app.heyrobot.ui.theme.LookStyle
import app.heyrobot.ui.theme.RobotTokens

// Main and voice processes. The panel's cards (an action waiting for "Do it", an offer to ask a helper, a notice) and
// History's, as standard Material parts: a filled card (surfaceContainerHighest, 12 dp corners) whose first row reads
// like a list item (a symbol, what it is, its state under it), an inset for the text it carries, choices as 56 dp rows
// like a system chooser, and the buttons at the end. In the Classic look the same card keeps Robot 0.4's tonal colours
// and 20 dp corners: CardStyle is the only difference, so there is one card, not two.

/** A card's container: its colour, the colour of what is on it, and its shape. */
@Immutable
data class CardStyle(val container: Color, val content: Color, val shape: Shape)

/** What a card is, for its Classic colours (the standard look uses one style for all). */
enum class CardKind { ACTION, OFFER, NOTICE }

/** The style for [kind] in [look] (the theme's look by default). */
@Composable
fun cardStyle(kind: CardKind = CardKind.ACTION, look: LookStyle = LocalLook.current): CardStyle {
    val c = MaterialTheme.colorScheme
    return when (look) {
        LookStyle.STOCK -> CardStyle(c.surfaceContainerHighest, c.onSurface, MaterialTheme.shapes.medium)
        LookStyle.CLASSIC -> when (kind) {
            CardKind.ACTION -> CardStyle(c.secondaryContainer, c.onSecondaryContainer, RoundedCornerShape(20.dp))
            CardKind.OFFER -> CardStyle(c.tertiaryContainer, c.onTertiaryContainer, RoundedCornerShape(20.dp))
            CardKind.NOTICE -> CardStyle(c.tertiaryContainer, c.onTertiaryContainer, RoundedCornerShape(16.dp))
        }
    }
}

/** A filled card, 16 dp padding, its parts in [content] top to bottom (TalkBack reads them in that order). */
@Composable
fun PanelCard(modifier: Modifier = Modifier, style: CardStyle = cardStyle(), content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = style.shape,
        colors = CardDefaults.cardColors(containerColor = style.container, contentColor = style.content),
    ) {
        Column(Modifier.padding(RobotTokens.cardPadding), content = content)
    }
}

/** A card's own symbol: 24 dp, onSurfaceVariant in the standard look (never the primary colour for decoration). */
@Composable
fun CardIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    val tint = if (LocalLook.current == LookStyle.STOCK) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current
    Icon(icon, null, modifier.size(24.dp), tint = tint)
}

/**
 * A card's first row, like a list item: [leading] (a [CardIcon], or an app's 40 dp icon), the [headline] in titleMedium,
 * the state in [supporting] under it (the error colour, after an error symbol, when [error]), and [trailing] (a status
 * symbol with its label).
 */
@Composable
fun CardHeader(
    headline: AnnotatedString,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            if (!supporting.isNullOrEmpty()) {
                val c = MaterialTheme.colorScheme
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (error) {
                        Icon(RIcons.Error, null, Modifier.size(16.dp), tint = c.error)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(supporting, style = MaterialTheme.typography.bodyMedium,
                        color = if (error) c.error else LocalContentColor.current.copy(alpha = 0.8f))
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun CardHeader(
    headline: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = CardHeader(AnnotatedString(headline), supporting, modifier, error, leading, trailing)

/** The text a card carries (a message, a note), in a quieter inset, at most [maxHeight] tall, scrolling inside. */
@Composable
fun CardInset(text: String, modifier: Modifier = Modifier, maxHeight: Dp = CARD_BODY_MAX) {
    val stock = LocalLook.current == LookStyle.STOCK
    Surface(
        color = if (stock) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = if (stock) MaterialTheme.shapes.small else RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/** About 8 lines of a card's text; more scrolls inside it. */
val CARD_BODY_MAX = 176.dp

/** One choice on a card ("Mom · mobile"), a 56 dp row with an optional symbol, as in a system chooser. */
@Composable
fun CardChoiceRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = RobotTokens.choiceRowHeight)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            CardIcon(icon)
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
    }
}

/** A card's buttons, at its end: the dismissive one (a TextButton) first, then the one that acts. */
@Composable
fun CardButtons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A one-line notice above the conversation or a list ("Robot isn't the assistant any more"): an info symbol, the text,
 * an optional action ([action] then [onAction]) and a 48 dp close button labelled "Dismiss".
 */
@Composable
fun NoticeRow(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = RIcons.Info,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val style = cardStyle(CardKind.NOTICE)
    Surface(color = style.container, contentColor = style.content, shape = style.shape, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CardIcon(icon)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (action != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = RobotTokens.minTouch)) { Text(action) }
            IconButton(onClick = onDismiss) { Icon(RIcons.Close, "Dismiss") }
        }
    }
}
