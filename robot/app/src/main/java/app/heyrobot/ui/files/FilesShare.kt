// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.IntakeNames
import app.heyrobot.core.files.ShareRules
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.IntakeStep
import app.heyrobot.sys.SharedFile
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.ui.theme.str
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// MAIN PROCESS. "Robot Files" in another app's Share (files-1.4-plan.md §3.1, D3, F5): Robot's first share target, and its
// first exported component that takes content from any app. Everything a share brings is untrusted: the bytes, the names,
// sizes and types, and who sent it.
//
//   - Exactly SEND and SEND_MULTIPLE (any type), checked again here: an exported activity can be started with any action.
//   - Nothing happens without your tap. The window shows what the sender claims (each file's name, size and type, cleaned,
//     and the sending app only when Android confirms it: launchedFromPackage, never the referrer the sender writes), and
//     Add copies. Until then nothing is read but those claims, and nothing is written: a background app can't fill the
//     cabinet or plant a "receipt".
//   - Copy only: each content:// URI read once through FilesIntake (another app's provider only, never file:, never one of
//     Robot's own or another user's), with the 200 MiB cap and a deadline; never a persistable permission; nothing
//     decoded here. Twenty files at a time; the rest are counted.
//   - Refused during a phone lock, while the phone is locked (no showWhenLocked: the lock screen never shows it), and with
//     Robot Files off; checked when it opens and again at the tap.
//   - A duplicate (the same SHA-256) is asked about, one at a time; nothing more happens to it until you answer. Every file
//     staged and not added is discarded on every way out.
//   - FLAG_SECURE (it shows file names), out of Recents, touches refused while another window covers it (a tap to add
//     must be yours). Everything lands in Inbox; :voice is told (Cmd.FILES).

class FilesShareActivity : ComponentActivity() {
    private var model: ShareModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        val i = intent
        if (i?.action != Intent.ACTION_SEND && i?.action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        val app = applicationContext
        val request = ShareRequest.of(i, sender())
        val m = ShareModel(PhonePort(app), request).also { model = it }
        m.start()
        setContent { RobotTheme { ShareWindow(m.ui, m, onClose = { finish() }) } }
    }

    /** The sending app, only as Android confirms it (the sender opted in to sharing its identity); null otherwise. */
    private fun sender(): Pair<String, String?>? {
        val pkg = try { launchedFromPackage } catch (_: Exception) { null } ?: return null
        // Robot itself, or the system's chooser between the sender and Robot (UNVERIFIED what it reports there: a phone step),
        // isn't the sender.
        if (pkg == packageName || pkg in CHOOSERS || ShareRules.packageName(pkg) == null) return null
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            null
        }
        return pkg to label?.let(FileIndexCodec::cleanName)?.takeIf { it.isNotEmpty() }
    }

    override fun onDestroy() {
        // Leaving (Cancel, Done, Back, the task removed, or made again for a change it doesn't handle): whatever was staged
        // and not added goes; a new window starts from what the share claims.
        model?.close()
        super.onDestroy()
    }

    private companion object {
        val CHOOSERS = setOf("android", "com.android.intentresolver")  // l10n: not text (package names)
    }

    /** The phone's: this process's intake and lock state. */
    private class PhonePort(private val app: android.content.Context) : ShareModel.Port {
        private val intake by lazy { FilesIntake.phone(app) }
        override fun gate(): Phrase? = FilesIntake.gateText(FilesIntake.gate(app))
        override fun refusal(uri: Uri) = FilesIntake.refusal(uri) { FilesIntake.ownAuthority(app, it) }
        override fun describe(uri: Uri, refusal: app.heyrobot.core.files.ShareRefusal?) = intake.describe(uri, refusal)
        override fun add(f: SharedFile, intake: (app.heyrobot.core.parse.FileType) -> FileIntake) = this.intake.add(f, intake)
        override fun add(bytes: ByteArray, intake: (app.heyrobot.core.parse.FileType) -> FileIntake) = this.intake.add(bytes, intake)
        override fun anyway(d: IntakeStep.Duplicate) = intake.anyway(d)
        override fun drop(d: IntakeStep.Duplicate) = intake.drop(d)
        override fun changed() = Cmd.send(app, Cmd.FILES)
        override fun langs(): Langs = AppLanguage.langs(app)
        override fun now(): Long = System.currentTimeMillis()
    }
}

/** What a share brings, read from its intent: every value the sender's. [sender]: the package and label Android confirmed. */
class ShareRequest(val uris: List<Uri>, val text: CharSequence?, val subject: String?, val sender: Pair<String, String?>?) {
    companion object {
        /** EXTRA_STREAM (one, or a list) and the clip's URIs, each once; the text only when no file came. A bad extra is nothing. */
        fun of(i: Intent, sender: Pair<String, String?>?): ShareRequest {
            val uris = ArrayList<Uri>()
            try {
                when (i.action) {
                    Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
                    Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.filterNotNull()?.let(uris::addAll)
                }
            } catch (t: Exception) {
                RLog.w("files_share_extra", t)
            }
            try {
                i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let(uris::add) }
            } catch (t: Exception) {
                RLog.w("files_share_clip", t)
            }
            val text = try { i.getCharSequenceExtra(Intent.EXTRA_TEXT) } catch (_: Exception) { null }
            val subject = try { i.getStringExtra(Intent.EXTRA_SUBJECT) } catch (_: Exception) { null }
            return ShareRequest(uris.distinct(), text, subject, sender)
        }
    }
}

/** One thing the window lists: a shared file, or the shared text. */
class ShareItem(val file: SharedFile?, val text: ByteArray?) {
    val refused: Boolean get() = file?.refusal != null
}

/** What the window shows. */
sealed class ShareUi {
    data object Reading : ShareUi()
    /** Refused before anything: during a phone lock, locked, off, or nothing to add. */
    class Refused(val why: Phrase) : ShareUi()
    class Review(val items: List<ShareItem>, val sender: Phrase, val leftOut: Int) : ShareUi()
    class Adding(val done: Int, val of: Int) : ShareUi()
    /** A duplicate: [where] it is already. */
    class Asking(val name: String, val where: Phrase) : ShareUi()
    class Done(val lines: List<Phrase>) : ShareUi()
}

/**
 * The share window's state and work, on one worker thread (the copies block). [port]: the phone's intake and lock state,
 * or a test's.
 */
class ShareModel(private val port: Port, private val request: ShareRequest, private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "robot-files-share").apply { isDaemon = true }  // l10n: not text (a thread's name)
}) {
    interface Port {
        /** Why no file may come in now, or null. */
        fun gate(): Phrase?
        fun refusal(uri: Uri): app.heyrobot.core.files.ShareRefusal?
        fun describe(uri: Uri, refusal: app.heyrobot.core.files.ShareRefusal?): SharedFile
        fun add(f: SharedFile, intake: (app.heyrobot.core.parse.FileType) -> FileIntake): IntakeStep
        fun add(bytes: ByteArray, intake: (app.heyrobot.core.parse.FileType) -> FileIntake): IntakeStep
        fun anyway(d: IntakeStep.Duplicate): IntakeStep
        fun drop(d: IntakeStep.Duplicate)
        /** Something was added: :voice reads Robot Files again. */
        fun changed()
        /** The menus' languages, for the names Robot gives. */
        fun langs(): Langs
        fun now(): Long
    }

    var ui: ShareUi by mutableStateOf(ShareUi.Reading)
        private set

    private var items: List<ShareItem> = emptyList()
    private var leftOut = 0
    private val lines = ArrayList<Phrase>()
    private var added = 0
    /** Duplicates waiting for your answer, first one asked. */
    private val waiting = ArrayDeque<Pair<String, IntakeStep.Duplicate>>()
    @Volatile private var closed = false
    @Volatile private var started = false

    private val sender: Phrase get() = request.sender?.let { (_, label) -> label?.let { FilesStrings.sharedFrom("sender" to Arg.Plain(it)) } }
        ?: FilesStrings.sharedFromAnother()

    /** Reads what the share claims (nothing of its bytes), or says why it can't be taken now. */
    fun start() {
        port.gate()?.let { ui = ShareUi.Refused(it); return }
        worker.execute {
            val (taken, left) = ShareRules.cap(request.uris)
            leftOut = left
            items = if (taken.isNotEmpty()) taken.map { u -> ShareItem(port.describe(u, port.refusal(u)), null) }
                else listOfNotNull(ShareRules.textBytes(request.text)?.let { ShareItem(null, it) })
            ui = if (items.none { !it.refused }) ShareUi.Refused(if (items.isEmpty()) FilesStrings.nothingShared() else FilesStrings.notAllowed())
                else ShareUi.Review(items, sender, leftOut)
        }
    }

    /** Your tap on Add: the lock checked again, then each file copied in, one at a time. */
    fun add() {
        if (ui !is ShareUi.Review || started) return
        port.gate()?.let { ui = ShareUi.Refused(it); return }
        started = true
        val todo = items.filter { !it.refused }
        ui = ShareUi.Adding(0, todo.size)
        worker.execute {
            for ((k, item) in todo.withIndex()) {
                if (closed) break
                ui = ShareUi.Adding(k, todo.size)
                val name = nameOf(item)
                val step = try {
                    if (item.file != null) port.add(item.file) { t -> intake(name, t.mime) } else port.add(item.text!!) { t -> intake(name, t.mime) }
                } catch (t: Exception) {
                    RLog.w("files_share_add", t)
                    IntakeStep.Failed(app.heyrobot.core.files.IntakeProblem.IO)
                }
                if (closed && step is IntakeStep.Duplicate) {
                    port.drop(step)
                    break
                }
                settle(name, step)
            }
            next()
        }
    }

    /** Your answer to "Add it anyway?". */
    fun answer(anyway: Boolean) {
        if (ui !is ShareUi.Asking) return
        val (name, d) = waiting.removeFirstOrNull() ?: return
        ui = ShareUi.Adding(0, 1)
        worker.execute {
            if (anyway && !closed) settle(name, port.anyway(d)) else {
                port.drop(d)
                lines += FilesStrings.fileLine("name" to Arg.Plain(shown(name)), "what" to Arg.Sub(FilesStrings.notAdded()))
            }
            next()
        }
    }

    /** The window goes (Cancel, Done, Back): nothing more is added, and whatever waits for an answer is discarded. */
    fun close() {
        if (closed) return
        closed = true
        worker.execute {
            while (true) port.drop((waiting.removeFirstOrNull() ?: break).second)
        }
        worker.shutdown()
    }

    private fun settle(name: String, step: IntakeStep) {
        when (step) {
            is IntakeStep.Added -> {
                added++
                if (step.type.group == FileGroup.DANGEROUS)
                    lines += FilesStrings.fileLine("name" to Arg.Plain(shown(name)), "what" to Arg.Sub(FilesStrings.dangerous()))
            }
            is IntakeStep.Duplicate -> waiting.addLast(name to step)
            is IntakeStep.Failed -> lines += FilesStrings.fileLine("name" to Arg.Plain(shown(name)), "what" to Arg.Sub(FilesStrings.problem(step.problem, step.need)))
        }
    }

    /** The next duplicate to ask about, or the end. */
    private fun next() {
        val w = waiting.firstOrNull()
        if (w != null && !closed) {
            ui = ShareUi.Asking(shown(w.first), FilesStrings.where(w.second.of.drawer, w.second.of.name))
            return
        }
        if (added > 0) port.changed()
        val summary = if (added > 0) FilesStrings.added(added) else FilesStrings.noneAdded()
        ui = ShareUi.Done(listOf(summary) + lines + listOfNotNull(leftOut.takeIf { it > 0 }?.let(FilesStrings::leftOutOf)))
    }

    private fun intake(name: String, type: String) = FileIntake(name, FileSource.SHARE, app = request.sender?.first, type = type)

    /** The name a file is kept under: the sender's (cleaned when kept), or none; shared text gets one. */
    private fun nameOf(item: ShareItem): String = item.file?.name
        ?: if (item.text != null) IntakeNames.text(request.subject, FilesStrings.nameSharedText("when" to IntakeNames.stamp(port.now(), ZoneId.systemDefault())).render(port.langs()))
        else ""

    private fun shown(name: String): String = name.ifEmpty { FilesStrings.noName().render(port.langs()) }
}

/** What a file's line under its name says: the sender's size and type, as claimed. */
@Composable
private fun claims(f: SharedFile): String {
    val langs = LocalLangs.current
    val size = f.size?.let { Fmt.bytes(it, langs.ui) } ?: stringResource(R.string.files_share_size_unknown)
    return listOfNotNull(size, f.type).joinToString(" · ")  // l10n: not text (a separator between two values)
}

/** The window (a card over the sending app). Stateless: [ui] is what it shows, [m] what its buttons do. */
@Composable
fun ShareWindow(ui: ShareUi, m: ShareModel?, onClose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.files_share_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                when (ui) {
                    ShareUi.Reading -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        // A sender that doesn't answer about its files: you needn't wait for it.
                        Buttons { TextButton(onClick = { m?.close(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) } }
                    }
                    is ShareUi.Refused -> {
                        Text(ui.why.str(), style = MaterialTheme.typography.bodyLarge)
                        Buttons { Button(onClick = { m?.close(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_share_close)) } }
                    }
                    is ShareUi.Review -> {
                        Text(ui.sender.str(), style = MaterialTheme.typography.bodyMedium, color = muted)
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (item in ui.items) Item(item)
                        }
                        Text(stringResource(R.string.files_share_note), style = MaterialTheme.typography.bodyMedium)
                        if (ui.leftOut > 0) Text(FilesStrings.leftOutOf(ui.leftOut).str(), style = MaterialTheme.typography.bodyMedium, color = muted)
                        Buttons {
                            TextButton(onClick = { m?.close(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) }
                            Button(onClick = { m?.add() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_share_add)) }
                        }
                    }
                    is ShareUi.Adding -> {
                        Text(stringResource(R.string.files_share_adding, ui.done + 1, ui.of), style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(progress = { if (ui.of == 0) 0f else ui.done.toFloat() / ui.of }, modifier = Modifier.fillMaxWidth())
                        // Stops after the file being copied: what was added stays, the rest isn't read.
                        Buttons { TextButton(onClick = { m?.close(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) } }
                    }
                    is ShareUi.Asking -> {
                        Text(ui.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(FilesStrings.duplicate("where" to Arg.Sub(ui.where)).str(), style = MaterialTheme.typography.bodyLarge)
                        Buttons {
                            TextButton(onClick = { m?.answer(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_dont_add)) }
                            Button(onClick = { m?.answer(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_add_anyway)) }
                        }
                    }
                    is ShareUi.Done -> {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((k, line) in ui.lines.withIndex()) Text(line.str(), style = if (k == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                        }
                        Buttons { Button(onClick = { m?.close(); onClose() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_share_done)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Item(item: ShareItem) {
    val f = item.file
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(if (item.refused) RIcons.Warning else RIcons.Note, null, Modifier.size(24.dp), tint = if (item.refused) MaterialTheme.colorScheme.error else muted)
        Column(Modifier.weight(1f)) {
            val name = when {
                f == null -> stringResource(R.string.files_share_text)
                else -> f.name ?: FilesStrings.noName().str()
            }
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                item.refused -> Text(FilesStrings.notAllowed().str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                f != null -> Text(claims(f), style = MaterialTheme.typography.bodyMedium, color = muted)
                else -> Text(Fmt.bytes(item.text?.size?.toLong() ?: 0, LocalLangs.current.ui), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { content() }
}
