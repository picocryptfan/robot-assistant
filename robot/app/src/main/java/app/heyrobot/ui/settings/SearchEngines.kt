// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.core.web.PrivacyLevel
import app.heyrobot.core.web.PublicSearxng
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.level
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.RadioPreference
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.RobotTokens
import app.heyrobot.ui.theme.str

// MAIN PROCESS. 1.0: every search engine and service with its privacy level (core web Engines.kt), as Settings › Web
// search and the setup guide's web page list them: "Robot reads the results" (the services set-up offers) and "Opens in
// your browser" (the engines browser_search opens, the default ticked). Each row: the name, its line (who runs it, whose
// results, what is kept, which country; in strings_web.xml) and its level's chip (core WebStrings).

@StringRes
fun engineLine(e: BrowserEngine): Int = when (e) {
    BrowserEngine.STARTPAGE -> R.string.engine_line_startpage
    BrowserEngine.DUCKDUCKGO -> R.string.engine_line_duckduckgo
    BrowserEngine.BRAVE -> R.string.engine_line_brave
    BrowserEngine.MOJEEK -> R.string.engine_line_mojeek
    BrowserEngine.QWANT -> R.string.engine_line_qwant
    BrowserEngine.ECOSIA -> R.string.engine_line_ecosia
    BrowserEngine.GOOGLE -> R.string.engine_line_google
    BrowserEngine.BING -> R.string.engine_line_bing
}

@StringRes
fun serviceLine(s: SearchService): Int = when (s) {
    SearchService.SEARXNG_HOME -> R.string.service_line_searxng_home
    SearchService.KAGI -> R.string.service_line_kagi
    SearchService.BRAVE -> R.string.service_line_brave
    SearchService.MOJEEK -> R.string.service_line_mojeek
    SearchService.SEARXNG_PUBLIC -> R.string.service_line_searxng_public
    SearchService.WIKIPEDIA_ONLY -> R.string.service_line_wikipedia_only
    SearchService.DDG_INSTANT -> R.string.service_line_ddg_instant
}

/** A level's chip: its name on its own colour (and never the colour alone), read as "Privacy: High". */
@Composable
fun PrivacyChip(level: PrivacyLevel, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (level) {
        PrivacyLevel.HIGH -> c.primaryContainer to c.onPrimaryContainer
        PrivacyLevel.MEDIUM -> c.tertiaryContainer to c.onTertiaryContainer
        PrivacyLevel.LOW -> c.errorContainer to c.onErrorContainer
    }
    val title = level.label().str()
    val desc = stringResource(R.string.privacy_level_desc, title)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp), modifier = modifier.clearAndSetSemantics { contentDescription = desc }) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/**
 * "Robot reads the results" ([title]): [services] in set-up's order, [inUse] ticked; a tap picks one ([onPick] goes on to its
 * set-up). [intro] under it.
 */
fun LazyListScope.readsResultsList(title: String, intro: String, services: List<SearchService>, inUse: SearchService?, onPick: (SearchService) -> Unit) {
    preferenceGroup(title, "reads") {
        for (s in services) {
            val line = stringResource(serviceLine(s))
            RadioPreference(s.titleText.str(), if (s == inUse) stringResource(R.string.web_in_use, line) else line, selected = s == inUse,
                trailing = { PrivacyChip(s.level) }) { onPick(s) }
        }
    }
    item(key = "reads-intro") { IntroPreference(intro) }
}

/** "Opens in your browser" ([title]): every engine, [default] ticked; a tap makes one the default ([onPick]). [intro] under it. */
fun LazyListScope.browserList(title: String, intro: String, default: BrowserEngine, onPick: (BrowserEngine) -> Unit) {
    preferenceGroup(title, "browser") {
        for (e in BrowserEngine.entries) {
            val line = stringResource(engineLine(e))
            RadioPreference(e.title, if (e == default) stringResource(R.string.web_browser_default, line) else line, selected = e == default,
                trailing = { PrivacyChip(e.level) }) { onPick(e) }
        }
    }
    item(key = "browser-intro") { IntroPreference(intro) }
}

/**
 * A public SearXNG: the short list (core PublicSearxng, picked on searx.space) or "Other address" ([onOther]), under the
 * warning that a stranger runs each one.
 */
@Composable
fun PublicSearxngDialog(selected: String?, onDismiss: () -> Unit, onOther: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_public_title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.web_public_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                for (url in PublicSearxng.LIST + "") {
                    val on = url.isNotEmpty() && url == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RobotTokens.choiceRowHeight).clip(MaterialTheme.shapes.small)
                            .selectable(selected = on, role = Role.RadioButton) { if (url.isEmpty()) onOther() else onPick(url) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = on, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(if (url.isEmpty()) stringResource(R.string.web_public_other) else PublicSearxng.host(url), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_public_cancel)) } },
    )
}
