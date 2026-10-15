// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.StatusIcon
import app.heyrobot.ui.theme.RobotTokens

// MAIN PROCESS. Small building blocks for the app's screens, restyled in place like Android's Settings (0.4.x
// redesign): pages built since use ui/pref.

/**
 * A titled group on the screens not yet built from ui/pref: the title as a settings category header (labelLarge, primary,
 * a heading for TalkBack) above a 20 dp group container, as Android's Settings groups its rows (0.4.x redesign; before
 * it, a 24 dp card with the title inside).
 */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(start = RobotTokens.prefPaddingH, end = RobotTokens.prefPaddingH, bottom = 8.dp)
                    .semantics { heading() },
            )
        }
        Surface(
            shape = RoundedCornerShape(RobotTokens.groupCorner),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(RobotTokens.prefPaddingH), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

enum class Mark { OK, TODO, WARN }

/** A status line: its symbol (labelled "Done", "To do" or "Problem"), title, explanation, optional trailing content (a button). */
@Composable
fun StatusRow(mark: Mark, title: String, detail: String?, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusIcon(mark)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A tappable row that opens a page: a neutral symbol, the title and its state, a chevron; 72 dp, 4 dp corners. */
@Composable
fun NavRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RobotTokens.prefMinHeight).clip(RoundedCornerShape(RobotTokens.itemCorner))
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(RIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(RIcons.Back, "Back") } },
        actions = { actions() },
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val Transparent = Color.Transparent
