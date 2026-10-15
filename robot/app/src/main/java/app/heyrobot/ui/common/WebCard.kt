// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.core.web.CardSource
import app.heyrobot.core.web.WikipediaWire
import app.heyrobot.ui.theme.RobotTokens

// Main and voice processes (0.9 web search, design A4.4 and A9). A web answer's card under its bubble (a tonal card,
// ui/common Cards.kt): which service searched and what it was sent, the numbered sources the answer cites (title ·
// domain; a tap opens it in the browser, nothing loads by itself, no image ever), the others it found, the pages skipped
// and why, how many pages were read and from where, and the credits (Wikipedia's licence, Brave's line). Shown in the panel only: History keeps the card's saved line (the domains), never titles or links.

/** The answer card for [card]; [onOpen] gets a source's URL (or Wikipedia's licence) when tapped. */
@Composable
fun WebAnswerCard(card: AnswerCard, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    PanelCard(modifier) {
        CardHeader(
            headline = card.header,
            supporting = "“${card.query}”",
            leading = { CardIcon(RIcons.Search) },
            trailing = card.cost?.let { cost -> { Text(cost, style = MaterialTheme.typography.labelMedium) } },
        )
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            for (s in card.cited) SourceRow(s, numbered = true, onOpen = onOpen)
            if (card.alsoFound.isNotEmpty()) {
                Text("Also found", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
                for (s in card.alsoFound) SourceRow(s, numbered = false, onOpen = onOpen)
            }
        }
        val small = MaterialTheme.typography.bodySmall
        for (line in card.skippedLines) Text(line, style = small, modifier = Modifier.padding(top = 4.dp))
        Text(listOfNotNull(card.readLine, card.sent).joinToString(" · "), style = small, modifier = Modifier.padding(top = 8.dp))
        for (credit in card.credits) {
            if (credit.startsWith("From Wikipedia")) {
                // The reuse page's form: the article (a source above) and a link to the licence.
                LinkRow("$credit (licence)", onClick = { onOpen(WikipediaWire.LICENCE_URL) })
            } else {
                Text(credit, style = small, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** One source: "[1] Title · domain" (or without its number under "Also found"), a 48 dp row that opens it. */
@Composable
private fun SourceRow(s: CardSource, numbered: Boolean, onOpen: (String) -> Unit) {
    val label = if (numbered) s.line else "${s.title} · ${s.domain}"
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = RobotTokens.minTouch)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = "Open in the browser") { onOpen(s.url) }
            .semantics { contentDescription = (if (numbered) "Source ${s.n}: " else "") + "${s.title}, ${s.domain}" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (numbered) {
            Text("[${s.n}]", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.domain, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().heightIn(min = RobotTokens.minTouch).clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = "Open the licence", onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}
