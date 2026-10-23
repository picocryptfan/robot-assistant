// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.files.FileFindCode
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FilePack
import app.heyrobot.core.files.PackKind
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.sys.ExportResult
import app.heyrobot.sys.ExportStop
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

// MAIN PROCESS. 1.4 F12 (files-1.4-plan.md §7.2): a tax pack's or an expense report's page in the Files tab. It lists what
// would go (FilePack: slips and tax files, or files tagged business, for one year), says the copies aren't encrypted, and
// only your tap on Export, then the folder you pick in Android's folder picker, copies anything out (FilesScreen holds the
// picker; sys/FilesExport writes). The panel's card for "make my 2026 tax pack" opens this page and nothing more.

/** A page the panel asked the Files tab to open (AppPages.FILES_PACK's route), taken once. */
object FilesRequests {
    val pack = MutableStateFlow<String?>(null)

    fun open(route: String) {
        pack.value = route
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackPage(
    idx: FileIndex, kind: PackKind, year: Int, port: FilesPort, modifier: Modifier, busy: Boolean, onBack: () -> Unit, onOpen: (FilesView) -> Unit,
    onYear: (Int) -> Unit, onExport: () -> Unit,
) {
    val pack = remember(idx, kind, year) { FilePack.of(idx, kind, year) }
    val years = remember(idx, kind, year) { (FilePack.years(idx, kind) + year).distinct().sortedDescending() }
    LaunchedEffect(kind, year) { withContext(Dispatchers.IO) { port.logFind(FileFindCode.PACK, pack.files.size) } }
    val y = year.toString()
    val title = (if (kind == PackKind.TAX) FilesStrings.packTitleTax else FilesStrings.packTitleExpenses)("year" to y).str()
    PreferenceScreen(title, onBack, modifier = modifier) {
        if (years.size > 1) item(key = "years") {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in years.take(8)) FilterChip(selected = v == year, onClick = { onYear(v) }, label = { Text(v.toString()) })
            }
        }
        if (pack.empty) {
            item(key = "empty") {
                IntroPreference((if (kind == PackKind.TAX) FilesStrings.packEmptyTax else FilesStrings.packEmptyExpenses)("year" to y).str())
            }
        } else {
            item(key = "what") {
                PreferenceBlock {
                    Text(stringResource(R.string.files_pack_what, FilesStrings.kinds(pack.files.map { it.kind }).str()), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.files_pack_not_encrypted), style = MaterialTheme.typography.bodyLarge)
                    if (pack.damaged > 0) Text(pluralStringResource(R.plurals.files_pack_damaged, pack.damaged, pack.damaged), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onExport, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_pack_export)) }
                }
            }
            preferenceGroup(null, "files") {
                for (e in pack.files) FileRow(e, port, filedSummary(e)) { onOpen(FilesView.File(e.id)) }
            }
        }
    }
}

/** How an export ended, in words (the menus' language). */
internal fun exportText(ctx: Context, r: ExportResult): String = when (r) {
    is ExportResult.Done -> ctx.resources.getQuantityString(R.plurals.files_pack_done, r.copied, r.copied, r.folder) +
        if (r.leftOut > 0) " " + ctx.resources.getQuantityString(R.plurals.files_pack_left_out, r.leftOut, r.leftOut) else "" // l10n: not text (two sentences joined)
    is ExportResult.Stopped -> when (r.why) {
        ExportStop.LOCKED -> ctx.resources.getQuantityString(R.plurals.files_pack_stopped, r.copied, r.copied)
        ExportStop.NO_FOLDER -> ctx.getString(R.string.files_pack_no_folder)
        ExportStop.FAILED -> ctx.getString(R.string.files_pack_failed)
    }
}

