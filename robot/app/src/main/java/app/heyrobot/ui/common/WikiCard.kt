// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.wiki.WikiCard
import app.heyrobot.ui.theme.RobotTokens

// Main and voice processes (Wikipedia P4b). A reply from the copy on this phone's card under its bubble (a tonal card,
// ui/common Cards.kt): "From Wikipedia on this phone" and the copy's month, each article read by its title, and the
// licence. A tap on a title opens that article on Wikipedia in the browser, a tap on the licence opens it: nothing
// loads by itself. Its words are core's (WikiCard, in the reply's language); shown in the panel only, never said or saved.

/** The card for [card]; [onOpen] gets an article's address or the licence's when tapped (WikiCard.links). */
@Composable
fun WikiAnswerCard(card: WikiCard, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    PanelCard(modifier) {
        Text(card.header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        val open = stringResource(R.string.web_card_open)
        for (a in card.articles) Link(a.title, MaterialTheme.typography.bodyMedium, open) { onOpen(a.url) }
        Link(card.licence, MaterialTheme.typography.bodySmall, stringResource(R.string.web_card_open_licence)) { onOpen(card.links.last()) }
    }
}

/** A 48 dp row that opens its link. */
@Composable
private fun Link(text: String, style: androidx.compose.ui.text.TextStyle, label: String, onClick: () -> Unit) {
    Text(
        text, style = style, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().heightIn(min = RobotTokens.minTouch).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}
