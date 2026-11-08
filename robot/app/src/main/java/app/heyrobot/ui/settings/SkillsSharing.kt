// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.connect.ConnSetup
import app.heyrobot.core.skills.ExportRead
import app.heyrobot.core.skills.ExportWrite
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.core.skills.SkillExport
import app.heyrobot.core.skills.SkillIds
import app.heyrobot.core.skills.SkillShare
import app.heyrobot.core.skills.SkillShareCode
import app.heyrobot.core.skills.SkillSig
import app.heyrobot.sys.ResolverSource
import app.heyrobot.sys.SkillShareFiles
import app.heyrobot.sys.SkillSigning
import app.heyrobot.ui.Mark
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioDialog
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime

// MAIN PROCESS (1.3, skills plan S3.4, design §11). Settings › Skills › Sharing: this phone's key (robot_skill_signing,
// sys SkillSigning) by its fingerprint, the keys you trust (add, name, remove), Export (a skill, its preview, then
// Android's file picker writes the signed file) and Import (Android's file picker; Robot Files' share target hands a
// `.robotskill` here too). The import screen reads the held file once (core SkillExport.read: untrusted, fail closed),
// shows who signed it, each skill's steps, a renamed name, and a pick of your connections for each one it needs; your
// tap on Import saves each skill off, from content (no Just do it until you edit it), whoever signed it.

object SkillShareRoutes {
    const val SHARING = "skills/sharing" // l10n: not text (a route)
    const val IMPORT = "skills/import" // l10n: not text (a route)
    fun of(route: String): Boolean = route == SHARING || route == IMPORT
}

/** A file name for [s]'s export: its name with the characters a file name can't hold left out. */
internal fun exportFileName(s: Skill): String =
    s.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().ifEmpty { "skill" } + "." + SkillExport.EXTENSION // l10n: not text (a file name)

@Composable
fun SkillsSharingRoute(route: String, d: SkillsData, act: SkillActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    if (route == SkillShareRoutes.IMPORT) SkillImportScreen(d, act, snackbar, onBack) else SkillSharingScreen(d, act, snackbar, onBack)
}

@Composable
fun SkillSharingScreen(d: SkillsData, act: SkillActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var own by remember { mutableStateOf<String?>(null) }
    var keyReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        own = withContext(Dispatchers.IO) { SkillSigning.fingerprint() }
        keyReady = own != null
    }
    val trusted = remember(tick) { SkillShareFiles.trusted(ctx) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val tCopied = stringResource(R.string.skill_key_copied)
    val tExported = stringResource(R.string.skill_exported)
    val tWriteFailed = stringResource(R.string.skill_export_failed)
    val tUnreadable = stringResource(R.string.skill_import_unreadable)
    val tNoKey = stringResource(R.string.skill_key_none)
    val tKeyThis = stringResource(R.string.skill_key_this)
    val tTrusted = stringResource(R.string.skill_keys_trusted)
    val tFooter = stringResource(R.string.skill_sharing_footer)
    val langs = LocalLangs.current
    fun say(t: String) { scope.launch { snackbar.showSnackbar(t) } }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SkillExport.MIME)) { uri: Uri? ->
        val bytes = pending.also { pending = null }
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); true } ?: false } catch (_: Exception) { false }
            }
            SkillShareFiles.log(ctx, SkillShareCode.EXPORTED, 1, if (ok) 0 else 9)
            say(if (ok) tExported else tWriteFailed)
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { SkillShareFiles.read(ResolverSource(ctx), uri) }
            if (bytes == null) say(tUnreadable) else { SkillShareFiles.hold(bytes); act.open(SkillShareRoutes.IMPORT) }
        }
    }

    PreferenceScreen(stringResource(R.string.skill_sharing), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.skill_sharing_intro)) }
        preferenceGroup(tKeyThis, "own") {
            when (keyReady) {
                null -> PreferenceItem(stringResource(R.string.skill_key_making), null, RIcons.Key)
                false -> StatusPreference(tNoKey, null, Mark.WARN)
                true -> PreferenceItem(SkillSig.shown(own!!), SkillShare.grouped(own!!), RIcons.Key, onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tCopied, SkillShare.grouped(own!!)))
                    say(tCopied)
                })
            }
        }
        preferenceGroup(tTrusted, "trusted") {
            if (trusted.isEmpty()) PreferenceItem(stringResource(R.string.skill_keys_none), null, RIcons.Shield)
            for (k in trusted) PreferenceItem(k.name, SkillSig.shown(k.fingerprint), RIcons.Shield, onClick = { removing = k.fingerprint })
            if (trusted.size < SkillShare.MAX_TRUSTED) PreferenceItem(stringResource(R.string.skill_key_add), null, RIcons.Add, onClick = { adding = "" })
        }
        preferenceGroup(null, "files") {
            PreferenceItem(stringResource(R.string.skill_export), stringResource(R.string.skill_export_summary), RIcons.Share,
                enabled = d.skills.isNotEmpty() && keyReady == true, onClick = { picking = true })
            PreferenceItem(stringResource(R.string.skill_import), stringResource(R.string.skill_import_summary), RIcons.Download,
                enabled = d.problem == null, onClick = { opener.launch(arrayOf("*/*")) }) // l10n: not text (a MIME pattern)
        }
        preferenceFooter(tFooter)
    }

    adding?.let { typed ->
        EditDialog(stringResource(R.string.skill_key_add), typed, stringResource(R.string.skill_key_fingerprint), stringResource(R.string.skill_key_fingerprint_supporting),
            validate = { t -> if (t.isBlank()) null else SkillShare.addProblem(trusted, t, "-", own)?.render(langs) }, canSave = { SkillShare.fingerprintOf(it) != null },
            confirm = stringResource(R.string.skill_key_next), onDismiss = { adding = null }) { t -> adding = t; naming = true }
    }
    if (naming) adding?.let { fp ->
        EditDialog(stringResource(R.string.skill_key_add), "", stringResource(R.string.skill_key_name), stringResource(R.string.skill_key_name_supporting),
            validate = { n -> if (n.isBlank()) null else SkillShare.addProblem(trusted, fp, n, own)?.render(langs) }, canSave = { it.isNotBlank() },
            maxLength = SkillShare.MAX_KEY_NAME, onDismiss = { naming = false; adding = null }) { n ->
            naming = false; adding = null
            if (SkillShare.addProblem(trusted, fp, n, own) == null && SkillShareFiles.saveTrusted(ctx, SkillShare.add(trusted, fp, n, own, System.currentTimeMillis()))) tick++
        }
    }
    removing?.let { fp ->
        val k = trusted.firstOrNull { it.fingerprint == fp }
        if (k == null) removing = null else ConfirmDialog(stringResource(R.string.skill_key_remove_title, k.name), stringResource(R.string.skill_key_remove_text),
            stringResource(R.string.skill_key_remove), onDismiss = { removing = null }) {
            removing = null
            if (SkillShareFiles.saveTrusted(ctx, trusted.filter { it.fingerprint != fp })) tick++
        }
    }
    if (picking) RadioDialog(stringResource(R.string.skill_export_pick), null as Skill?, d.skills, label = { it?.name.orEmpty() },
        onDismiss = { picking = false }) { s -> picking = false; exporting = s?.id }
    exporting?.let { id ->
        val s = d.skills.firstOrNull { it.id == id }
        if (s == null) { exporting = null; return@let }
        val flags = remember(s) { SkillExport.preview(listOf(s)) }
        val text = if (flags.isEmpty()) stringResource(R.string.skill_export_clean)
            else stringResource(R.string.skill_export_flags) + "\n" + flags.joinToString("\n") { f -> "• “" + f.value + "”: " + f.kind.msg().render(langs) } // l10n: not text (a list's marks)
        ConfirmDialog(stringResource(R.string.skill_export_title, s.name), text, stringResource(R.string.skill_export_save), onDismiss = { exporting = null }) {
            exporting = null
            val signer = SkillSigning.signer()
            if (signer == null) { say(tNoKey); return@ConfirmDialog }
            val kinds = d.connections.associate { it.id to it.kind }
            when (val w = SkillExport.write(listOf(s), "Robot " + BuildConfig.VERSION_NAME, System.currentTimeMillis(), kinds::get, signer)) { // l10n: not text (the app's name)
                is ExportWrite.Refused -> {
                    SkillShareFiles.log(ctx, SkillShareCode.EXPORT_REFUSED, 1, w.why.ordinal + 1)
                    say(w.why.msg("name" to w.skill).render(langs))
                }
                is ExportWrite.Ok -> { pending = w.bytes; saver.launch(exportFileName(s)) }
            }
        }
    }
}

@Composable
fun SkillImportScreen(d: SkillsData, act: SkillActions, snackbar: SnackbarHostState, onBack: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val bytes = remember { SkillShareFiles.held() }
    if (bytes == null) { LaunchedEffect(Unit) { onBack() }; return }
    val read = remember(bytes) { SkillExport.read(bytes, SkillIds::make, System.currentTimeMillis()) }
    var own by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { own = withContext(Dispatchers.IO) { SkillSigning.fingerprint() } }
    val trusted = remember { SkillShareFiles.trusted(ctx) }
    val picks = remember { mutableStateMapOf<String, String>() }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    fun leave() { SkillShareFiles.clear(); onBack() }
    val tImportFooter = stringResource(R.string.skill_import_footer)
    val tNeedsTitle = stringResource(R.string.skill_import_needs_title)
    PreferenceScreen(stringResource(R.string.skill_import_title), { leave() }, snackbar = snackbar) {
        when (read) {
            is ExportRead.Refused -> {
                item(key = "refused") { StatusPreference(read.why.msg().str(), null, Mark.WARN) }
                item(key = "logged") { LaunchedEffect(Unit) { SkillShareFiles.log(ctx, SkillShareCode.IMPORT_REFUSED, 0, read.why.ordinal + 1) } }
            }
            is ExportRead.Ok -> {
                val by = SkillShare.signedBy(read.signer, own, trusted)
                val items = SkillShare.prepare(read, d.names, now, langs)
                item(key = "from") { StatusPreference(SkillShare.provenance(by).str(), stringResource(R.string.skill_import_starts_off), Mark.OK) }
                if (read.refused > 0) item(key = "left") { StatusPreference(pluralStringResource(R.plurals.skill_import_left_out, read.refused, read.refused), null, Mark.WARN) }
                for ((i, item) in items.withIndex()) preferenceGroup(item.skill.name, "skill$i") { // l10n: not text (a list key)
                    if (item.renamed) PreferenceItem(stringResource(R.string.skill_import_renamed), null, RIcons.Info)
                    PreferenceItem(stringResource(R.string.skill_steps), SkillCards.steps(item.skill).joinToString("\n") { p -> p.render(langs) }, RIcons.Checklist)
                }
                if (read.needs.isNotEmpty()) preferenceGroup(tNeedsTitle, "needs") {
                    for (n in read.needs) {
                        val c = picks[n.ref]?.let { id -> d.connections.firstOrNull { it.id == id } }
                        val fits = SkillShare.candidates(n, d.connections)
                        val summary = when {
                            c != null -> c.name + (c.address?.let { "\n" + ctx.getString(R.string.skill_import_reaches, it.host) } ?: "")
                            fits.isEmpty() -> stringResource(R.string.skill_import_none_fits)
                            else -> stringResource(R.string.skill_import_pick)
                        }
                        PreferenceItem(stringResource(R.string.skill_import_needs, ConnSetup.kindName(n.kind).str()), summary, RIcons.Settings,
                            enabled = fits.isNotEmpty(), onClick = { picking = n.ref })
                    }
                }
                preferenceGroup(null, "do") {
                    PreferenceItem(stringResource(R.string.skill_import_do), if (items.any { SkillShare.missing(it, picks).isNotEmpty() })
                        stringResource(R.string.skill_import_missing) else null, RIcons.Add, enabled = items.isNotEmpty(), onClick = {
                        var saved = 0
                        for (x in items) if (act.save(SkillShare.bind(x.skill, picks.toMap()))) saved++
                        SkillShareFiles.log(ctx, SkillShareCode.IMPORTED, SkillShare.logSigned(by), if (saved == items.size) 0 else 9)
                        SkillShareFiles.clear()
                        onBack()
                    })
                }
            }
        }
        preferenceFooter(tImportFooter)
    }
    picking?.let { ref ->
        val n = (read as? ExportRead.Ok)?.needs?.firstOrNull { it.ref == ref }
        if (n == null) { picking = null; return@let }
        RadioDialog(stringResource(R.string.skill_import_pick), picks[ref] ?: "", SkillShare.candidates(n, d.connections).map { it.id },
            label = { id -> d.connections.firstOrNull { it.id == id }?.name.orEmpty() }, onDismiss = { picking = null }) { id -> picking = null; picks[ref] = id }
    }
}
