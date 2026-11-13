// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.ReportPart
import app.heyrobot.core.feedback.SentReport
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.sys.FeedbackFiles
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceGroup
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

// RP5, Settings › About › Reports you sent (also from the card's menu): each report Send handed to an app, its reference
// first so you can quote it, then when, its kind and the parts ticked. Never what it said (core SentReports).

/** [list] null while it's read; [onDeleteAll] after you confirm. */
@Composable
fun SentReportsScreen(list: List<SentReport>?, onDeleteAll: () -> Unit, onBack: () -> Unit, snackbar: SnackbarHostState? = null,
    zone: ZoneId = ZoneId.systemDefault()) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val deleteAll = stringResource(R.string.report_sent_delete_all)
    val intro = stringResource(R.string.report_sent_intro)
    PreferenceScreen(stringResource(R.string.report_sent_title), onBack, snackbar = snackbar) {
        item(key = "intro", contentType = "intro") { IntroPreference(intro) }
        item(key = "list", contentType = "group") {
            PreferenceGroup(null) {
                when {
                    list == null -> PreferenceItem(stringResource(R.string.report_sent_reading))
                    list.isEmpty() -> PreferenceItem(stringResource(R.string.report_sent_empty))
                    else -> list.forEach { r -> PreferenceItem(r.ref, summary(r, zone)) }
                }
            }
        }
        if (!list.isNullOrEmpty()) item(key = "delete", contentType = "group") {
            PreferenceGroup(null) { PreferenceItem(deleteAll, icon = RIcons.Delete, onClick = { confirm = true }) }
        }
    }
    if (confirm) ConfirmDialog(stringResource(R.string.report_sent_delete_title), stringResource(R.string.report_sent_delete_text), deleteAll,
        onDismiss = { confirm = false }, onConfirm = { confirm = false; onDeleteAll() })
}

@Composable
private fun summary(r: SentReport, zone: ZoneId): String {
    val lang = LocalLangs.current.ui
    val t = Instant.ofEpochMilli(r.at).atZone(zone)
    val at = stringResource(R.string.report_sent_when, Fmt.date(t.toLocalDate(), lang), Fmt.clock(t.toLocalTime(), lang))
    val parts = ReportPart.entries.filter { it in r.parts }.map { label(it) }
    val holds = if (parts.isEmpty()) stringResource(R.string.report_sent_words_only)
        else stringResource(R.string.report_sent_parts, parts.joinToString(", ")) // l10n: not text (a list's separator)
    return listOf(at, r.kind.labelText.str(), holds).joinToString(". ") // l10n: not text (the row's separator)
}

@Composable
private fun label(p: ReportPart): String = when (p) {
    ReportPart.SAID -> stringResource(R.string.report_said)
    ReportPart.ANSWER -> stringResource(R.string.report_answer)
    ReportPart.ACTIONS -> stringResource(R.string.report_did)
    ReportPart.FACTS -> stringResource(R.string.report_facts)
    ReportPart.SCREENSHOT -> stringResource(R.string.report_part_screenshot)
    else -> partTitle(p, Diagnostic.entries.associateWith { it.titleText.str() })
}

/** L5: a part's title from the diagnostic that holds it, or its report header when none does (a part added later). */
internal fun partTitle(p: ReportPart, titles: Map<Diagnostic, String>): String =
    titles.entries.firstOrNull { ReportPart.of(it.key) == p }?.value ?: p.header

/** The page with its list, read from Robot's own folder off the main thread; Delete all empties it. */
@Composable
internal fun SentReportsRoute(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SentReport>?>(null) }
    val deleted = stringResource(R.string.report_sent_deleted)
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { FeedbackFiles.sent(app) } }
    SentReportsScreen(list, onDeleteAll = {
        scope.launch {
            list = withContext(Dispatchers.IO) { FeedbackFiles.clearSent(app); FeedbackFiles.sent(app) }
            snackbar.showSnackbar(deleted)
        }
    }, onBack = onBack, snackbar = snackbar)
}
