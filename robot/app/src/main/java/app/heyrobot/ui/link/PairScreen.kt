// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalLayoutApi::class)

package app.heyrobot.ui.link

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.heyrobot.R
import app.heyrobot.core.QrCode
import app.heyrobot.core.link.CodeState
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.Verified
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.sys.MadeCodeReply
import app.heyrobot.sys.ScanFiles
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.FooterPreference
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioPreference
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.settings.drawQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. 1.5, pairing (robot-to-robot-plan.md §2.3, §6.2; job L5), one screen in steps:
//   1. The mode: Anonymous or Private, one line each; the pairing uses the stricter of the two people's.
//   2. Meet in person: your code large as a QR code, and "Take a photo of their code" (the system camera, as Files' scan,
//      no camera permission; the photo is read by :files, kept in RAM only and deleted at once). Whichever Robot's key sorts
//      first sends the hello, so either order of photos makes one pairing, Verified in person on both phones.
//      Or At a distance: "Share code" (Android's share sheet: works once, for 24 hours, with Cancel), or "I have a code".
//   3. Who is this: from your People or someone you add. The name their Robot sent is only shown as what it says.
//   4. At a distance, the safety number: "They match", or "Compare later".
//   5. A preset, then the permissions page.
// The in-person code is cancelled when you leave its step; a shared code stays open until it is used, cancelled (here, or
// in Settings › Robot to Robot) or 24 hours pass.

private enum class Step { MODE, IN_PERSON, DISTANCE, SHARING, PASTE, WAITING, WHO, SAFETY, PRESET }

@Composable
fun PairScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, pollMs: Long = PAIR_POLL_MS) {
    SecureWindow()
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(Step.MODE) }
    var modeCode by rememberSaveable { mutableStateOf(LinkMode.PRIVATE.code) }
    val mode = LinkMode.of(modeCode) ?: LinkMode.PRIVATE
    var pairing by rememberSaveable { mutableStateOf<String?>(null) }
    /** You made the code they used (the "who is this" words differ). */
    var maker by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf<MadeCodeReply?>(null) }
    var making by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    /** In person: what the photo did (null: none taken yet). */
    var photo by remember { mutableStateOf<UseCodeResult?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<PairingView?>(null) }
    var pasted by rememberSaveable { mutableStateOf("") }
    /** The name step 4 says: your People name for them once picked, else what their Robot says. */
    var whoName by rememberSaveable { mutableStateOf<String?>(null) }

    /** F148: your code ran out of tries: nothing is coming, so the page says so and offers a new one ([tries] makes it). */
    var spent by remember { mutableStateOf(false) }
    var tries by remember { mutableStateOf(0) }

    fun say(id: Int) = a.say(ctx.getString(id))

    // F118: the code you copied leaves the clipboard once it's used, cancelled or expired, and when you leave this screen. Android
    // lets Robot read the clipboard only with the window focused, so one spent while you were elsewhere goes when you come back.
    var copied by remember { mutableStateOf<String?>(null) }
    var codeClosed by remember { mutableStateOf(false) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val clipSpent = PairClip.spent(copied, code?.text, codeClosed)
    LaunchedEffect(clipSpent, focused) {
        if (clipSpent && focused) {
            copied?.let { clearCopied(ctx, it) }
            copied = null
        }
    }
    DisposableEffect(Unit) { onDispose { copied?.let { clearCopied(app, it) } } }

    fun cancelShown(then: () -> Unit) {
        val c = code
        code = null
        if (c?.id == null) return then()
        scope.launch {
            withContext(Dispatchers.IO) { port.cancelCode(c.id) }
            then()
        }
    }

    fun leave() = if (step == Step.IN_PERSON) cancelShown(onBack) else onBack()
    BackHandler(enabled = step == Step.IN_PERSON) { leave() }

    fun afterPaired(id: String) {
        pairing = id
        val shown = code
        // In person, your own code is no longer needed once the pairing is made either way.
        if (step == Step.IN_PERSON && shown?.id != null) scope.launch { withContext(Dispatchers.IO) { port.cancelCode(shown.id) } }
        code = null
        step = Step.WHO
    }

    // Making a code for the step that shows one.
    LaunchedEffect(step, tries) {
        if (step != Step.IN_PERSON && step != Step.SHARING) return@LaunchedEffect
        if (code != null) return@LaunchedEffect
        making = true
        val made = withContext(Dispatchers.IO) { port.makeCode(mode, step == Step.IN_PERSON) }
        making = false
        if (made?.result == MakeCodeResult.OK) code = made
        else {
            say(when (made?.result) {
                MakeCodeResult.NO_NETWORK -> R.string.link_no_network
                MakeCodeResult.TOO_MANY -> R.string.link_too_many_codes
                MakeCodeResult.NO_ROUTE -> R.string.link_no_route
                MakeCodeResult.LIMIT -> R.string.link_limit
                null -> R.string.link_unavailable
                else -> R.string.link_failed
            })
            step = Step.MODE
        }
    }
    // Watching: your code (used by their hello, or refused as a mismatch), and the pairing until it is answered and verified.
    LaunchedEffect(step, code, pairing) {
        while (step == Step.IN_PERSON || step == Step.SHARING || step == Step.WAITING) {
            val c = code
            val (state, used) = if (c?.id != null) withContext(Dispatchers.IO) { port.codeState(c.id) } ?: (null to null) else (null to null)
            codeClosed = state == CodeState.CLOSED
            if (state == CodeState.MISMATCH) { line = ctx.getString(R.string.link_mismatch); code = null; spent = true; return@LaunchedEffect }
            // L5-fix: their hello waits on your Robot for your photo of their code.
            if (state == CodeState.HELD && photo == null) line = ctx.getString(R.string.link_their_hello_first)
            if (state == CodeState.USED && used != null) {
                if (step == Step.SHARING) { maker = true; afterPaired(used); return@LaunchedEffect }
                if (pairing == null) { pairing = used; maker = true }
            }
            val id = pairing
            if (id != null) {
                val v = withContext(Dispatchers.IO) { port.pairings() }?.firstOrNull { it.id == id }
                view = v
                if (v == null && step == Step.WAITING) { line = ctx.getString(R.string.link_failed); return@LaunchedEffect }
                if (v?.phase == PairingPhase.ACTIVE) {
                    if (step == Step.WAITING) { afterPaired(id); return@LaunchedEffect }
                    if (step == Step.IN_PERSON && v.verified != Verified.NO) { say(R.string.link_verified_done); afterPaired(id); return@LaunchedEffect }
                }
            }
            delay(pollMs)
        }
    }

    // In person: the system camera writes into cache/scan (Files' ScanProvider); read into RAM, deleted at once, read by :files.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = ScanFiles.photo(app)
        val bytes = if (ok && f.isFile && f.length() in 1L..ParseLimits.MAX_INPUT.toLong()) try { f.readBytes() } catch (_: Exception) { null } else null
        ScanFiles.clear(app)
        if (bytes == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { port.readQr(bytes) }
            if (text == null) { reading = false; return@launch say(R.string.link_no_code_in_photo) }
            val used = withContext(Dispatchers.IO) { port.useCode(text, PairCodeForm.SCANNED, mode) }
            reading = false
            when (used?.result) {
                UseCodeResult.SENT -> { photo = used.result; pairing = used.pairing; maker = false; line = ctx.getString(R.string.link_saying_hello) }
                UseCodeResult.WAITING -> { photo = used.result; line = ctx.getString(R.string.link_waiting_for_them) }
                UseCodeResult.VERIFIED -> { photo = used.result; say(R.string.link_verified_done); afterPaired(used.pairing!!) }
                else -> say(useProblem(used?.result))
            }
        }
    }

    fun paste() {
        val text = pasted
        scope.launch {
            val used = withContext(Dispatchers.IO) { port.useCode(text, PairCodeForm.PASTED, mode) }
            when (used?.result) {
                UseCodeResult.SENT -> { pairing = used.pairing; maker = false; step = Step.WAITING }
                UseCodeResult.ALREADY, UseCodeResult.VERIFIED -> {
                    say(R.string.link_already)
                    onBack()
                    a.openPage(Page.Settings(LinkRoutes.pairing(used.pairing!!)))
                }
                else -> say(useProblem(used?.result))
            }
        }
    }

    @Composable
    fun Spent() {
        line?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Button(onClick = { spent = false; line = null; photo = null; pairing = null; view = null; tries++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_try_again)) }
    }

    fun finish(id: String) {
        onBack()
        a.openPage(Page.Settings(LinkRoutes.permissions(id)))
    }

    PreferenceScreen(stringResource(R.string.link_pair_title), ::leave, snackbar = snackbar) {
        when (step) {
            Step.MODE -> {
                item(key = "q") { IntroPreference(stringResource(R.string.link_mode_question)) }
                preferenceGroup(null, "mode") {
                    for (m in listOf(LinkMode.ANONYMOUS, LinkMode.PRIVATE)) RadioPreference(modeName(m), modeDetail(m), selected = mode == m, onClick = { modeCode = m.code })
                }
                preferenceGroup(null, "how") {
                    PreferenceItem(stringResource(R.string.link_meet), stringResource(R.string.link_meet_detail), RIcons.Camera, onClick = { line = null; photo = null; step = Step.IN_PERSON })
                    PreferenceItem(stringResource(R.string.link_distance), stringResource(R.string.link_distance_detail), RIcons.Share, onClick = { line = null; step = Step.DISTANCE })
                }
            }
            Step.IN_PERSON -> item(key = "in-person") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val c = code
                    val qr = remember(c?.text) { c?.text?.let { QrCode.encode(it) } }
                    if (spent) { Spent(); return@Column }
                    if (making || qr == null) {
                        Text(stringResource(R.string.link_making_code), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.link_making_code_detail), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.link_your_code), style = MaterialTheme.typography.titleMedium)
                        val description = stringResource(R.string.link_qr_description)
                        Box(Modifier.size(280.dp)) {
                            Canvas(Modifier.size(280.dp).semantics { contentDescription = description; role = Role.Image }) { drawQr(qr) }
                        }
                        Text(stringResource(R.string.link_show_code), style = MaterialTheme.typography.bodyMedium)
                    }
                    line?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = {
                        try {
                            ScanFiles.clear(app)
                            camera.launch(FileProvider.getUriForFile(app, ScanFiles.AUTHORITY, ScanFiles.photo(app)))
                        } catch (_: ActivityNotFoundException) { say(R.string.link_no_camera) }
                    }, enabled = !reading && photo != UseCodeResult.SENT, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_take_photo)) }
                }
            }
            Step.DISTANCE -> preferenceGroup(null, "distance") {
                PreferenceItem(stringResource(R.string.link_share_code), stringResource(R.string.link_code_works), RIcons.Share, onClick = { step = Step.SHARING })
                PreferenceItem(stringResource(R.string.link_have_code), stringResource(R.string.link_paste_help), RIcons.Copy, onClick = { step = Step.PASTE })
            }
            Step.SHARING -> item(key = "sharing") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val c = code
                    if (spent) { Spent(); return@Column }
                    if (making || c?.text == null) {
                        Text(stringResource(R.string.link_making_code), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        return@Column
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer { Text(c.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
                    }
                    Text(stringResource(R.string.link_code_works), style = MaterialTheme.typography.titleSmall)
                    val shareTitle = stringResource(R.string.link_share_chooser)
                    val shareText = stringResource(R.string.link_share_text, c.text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText) // l10n: not text (a MIME type)
                            try { ctx.startActivity(Intent.createChooser(send, shareTitle)) } catch (_: ActivityNotFoundException) {}
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_share_code)) }
                        OutlinedButton(onClick = { copySensitive(ctx, c.text); copied = c.text; say(R.string.link_copied) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_copy)) }
                    }
                    Text(stringResource(R.string.link_waiting_code_used), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { cancelShown { say(R.string.link_code_cancelled); step = Step.MODE } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.link_cancel_code))
                    }
                }
            }
            Step.PASTE -> item(key = "paste") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(pasted, { pasted = it.take(LinkIpcProtocol.MAX_CODE_TEXT) }, label = { Text(stringResource(R.string.link_paste_label)) },
                        supportingText = { Text(stringResource(R.string.link_paste_help)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Button(onClick = ::paste, enabled = pasted.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_pair_button)) }
                }
            }
            Step.WAITING -> item(key = "waiting") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(line ?: stringResource(R.string.link_waiting_answer), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.link_waiting_answer_detail), style = MaterialTheme.typography.bodyMedium)
                    if (line == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Step.WHO -> item(key = "who") {
                val v = view
                val id = pairing
                LaunchedEffect(id) { if (id != null && view?.id != id) view = withContext(Dispatchers.IO) { port.pairings() }?.firstOrNull { it.id == id } }
                if (v != null && id != null) WhoIsThis(port, v, maker) { person ->
                    whoName = person?.name ?: LinkNames.claim(v.claimed).ifEmpty { null }
                    scope.launch {
                        if (person != null && !withContext(Dispatchers.IO) { port.setPerson(id, person.id) }) say(R.string.link_not_saved)
                        step = if (v.verified == Verified.NO) Step.SAFETY else Step.PRESET
                    }
                }
            }
            Step.SAFETY -> item(key = "safety") {
                val v = view
                if (v != null) SafetyNumberBlock(port, v, whoName ?: stringResource(R.string.link_someone), stringResource(R.string.link_they_match), stringResource(R.string.link_compare_later)) { match ->
                    scope.launch {
                        if (match && !withContext(Dispatchers.IO) { port.verify(v.id) }) say(R.string.link_not_saved) else if (match) say(R.string.link_match_saved)
                        step = Step.PRESET
                    }
                }
            }
            Step.PRESET -> {
                item(key = "preset-intro") { IntroPreference(stringResource(R.string.link_preset_title)) }
                preferenceGroup(null, "preset") {
                    PresetRows(null) { p ->
                        val id = pairing ?: return@PresetRows
                        scope.launch {
                            if (!withContext(Dispatchers.IO) { port.setPreset(id, p) }) return@launch say(R.string.link_not_saved)
                            finish(id)
                        }
                    }
                }
                item(key = "preset-note") { FooterPreference(stringResource(R.string.link_preset_note)) }
            }
        }
    }
}

/** What a code you scanned or pasted did, when it didn't pair. */
private fun useProblem(r: UseCodeResult?): Int = when (r) {
    UseCodeResult.EXPIRED -> R.string.link_code_expired
    UseCodeResult.NEWER -> R.string.link_code_newer
    UseCodeResult.BAD -> R.string.link_code_bad
    UseCodeResult.OWN -> R.string.link_code_own
    UseCodeResult.NO_NETWORK -> R.string.link_no_network
    UseCodeResult.BLOCKED -> R.string.link_code_blocked
    UseCodeResult.LIMIT -> R.string.link_limit
    UseCodeResult.NO_ROUTE -> R.string.link_no_route
    UseCodeResult.ALREADY -> R.string.link_already
    null -> R.string.link_unavailable
    else -> R.string.link_failed
}

/** The code on the clipboard, marked sensitive: Android's clipboard preview doesn't show it. */
private fun copySensitive(ctx: Context, text: String) {
    val clip = ClipData.newPlainText("", text) // l10n: not text (an empty clip label)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    try { ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) } catch (_: Exception) {}
}

/** Pure. F118: when a copied pairing code has to leave the clipboard. */
internal object PairClip {
    /** [copied] is no longer the code on screen ([shown]: used, cancelled or refused), or it's [closed] (expired). */
    fun spent(copied: String?, shown: String?, closed: Boolean): Boolean = copied != null && (closed || copied != shown)

    /** Only ever the code Robot put there: anything you copied since stays. */
    fun holds(clip: String?, copied: String): Boolean = clip == copied
}

/** Clears the clipboard if it still holds exactly the code [text]. */
private fun clearCopied(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount > 0 && PairClip.holds(clip.getItemAt(0).coerceToText(ctx)?.toString(), text)) cm.clearPrimaryClip()
    } catch (_: Exception) {}
}

private const val PAIR_POLL_MS = 1_500L
