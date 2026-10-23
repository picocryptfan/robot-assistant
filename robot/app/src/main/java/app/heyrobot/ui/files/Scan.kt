// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.IntakeNames
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParsedPixels
import app.heyrobot.core.parse.ScanGeometry
import app.heyrobot.core.parse.ScanQuad
import app.heyrobot.core.parse.FileType
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilePixels
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.IntakeStep
import app.heyrobot.sys.ScanFiles
import app.heyrobot.ui.BackBar
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.theme.str
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// MAIN PROCESS. Scanning a document (files-1.4-plan.md §3.2, D4, D19, F5): the system camera takes each page, you drag the
// page's four corners by hand (Q5's default: no automatic edge detection), and :files straightens each page and makes one
// PDF of them, which lands in Robot Files' Inbox. Opened by the Files tab's Scan button (F7).
//
//   - The camera writes into cache/scan/ (ScanProvider); the photo is read into RAM and the file deleted at once, and the
//     folder is emptied when this screen opens and goes. A photo, the pages and the PDF are only ever in RAM until sealed.
//   - Nothing is decoded here: the photo's preview comes from :files as raw pixels (FileParseLink.pixels), shown through
//     FilePixels; straightening (STRAIGHTEN) and the PDF (SCAN_PDF) are :files' too. One link for the screen's life.
//   - At most 20 pages, and their JPEGs together within what :files reads at once (64 MiB).
//   - The PDF goes in through FilesIntake (the vault's lock gate, the duplicate check), source SCAN. Refused during a phone
//     lock, while locked or with Robot Files off: checked when the screen opens and again at the end.

/** :files' scanning calls: the phone's link, or a test's. Each blocks: never on the main thread. */
interface ScanReader {
    fun preview(photo: ByteArray): ParseResult
    fun straighten(photo: ByteArray, quad: ScanQuad): ParseResult
    fun pdf(pages: List<ByteArray>): ParseResult
    fun end()
}

/** The phone's: one FileParseLink, made again when the last one ended. */
class LinkScanReader(private val ctx: Context) : ScanReader {
    private var link: FileParseLink? = null

    @Synchronized private fun link(): FileParseLink? {
        link?.takeIf { it.isAlive }?.let { return it }
        val l = FileParseLink(ctx.applicationContext)
        if (!l.start()) return null
        link = l
        return l
    }

    private fun down() = ParseResult.Failed(ParseFailure.UNAVAILABLE)

    override fun preview(photo: ByteArray): ParseResult = link()?.pixels(photo, thumb = false) ?: down()
    override fun straighten(photo: ByteArray, quad: ScanQuad): ParseResult = link()?.straighten(photo, quad) ?: down()
    override fun pdf(pages: List<ByteArray>): ParseResult = link()?.scanPdf(pages) ?: down()

    @Synchronized override fun end() {
        link?.end("scan_done")  // l10n: not text (a log code)
        link = null
    }
}

/** What the scan screen shows. */
sealed class ScanUi {
    /** Waiting for a photo: [pages] taken so far. */
    class Ready(val pages: Int, val full: Boolean) : ScanUi()
    /** A photo's preview, its corners as fractions (clockwise from the top left, x then y). */
    class Corners(val pixels: ParsedPixels, val corners: List<Float>) : ScanUi()
    data object Working : ScanUi()
    /** A scan that is a duplicate: where the same bytes are. */
    class Asking(val where: Phrase) : ScanUi()
    class Done(val said: Phrase) : ScanUi()
    /** Refused when the screen opened (a phone lock, locked, off). */
    class Refused(val why: Phrase) : ScanUi()
}

/** The scan's state and work, on one worker thread. [gate]: why no file may come in now, or null. */
class ScanModel(
    private val reader: ScanReader,
    private val gate: () -> Phrase?,
    private val add: (ByteArray, (FileType) -> FileIntake) -> IntakeStep,
    private val anyway: (IntakeStep.Duplicate) -> IntakeStep,
    private val drop: (IntakeStep.Duplicate) -> Unit,
    private val changed: () -> Unit,
    private val langs: () -> Langs,
    private val now: () -> Long = System::currentTimeMillis,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "robot-files-scan").apply { isDaemon = true }  // l10n: not text (a thread's name)
    },
) {
    var ui: ScanUi by mutableStateOf(gate()?.let { ScanUi.Refused(it) } ?: ScanUi.Ready(0, false))
        private set
    /** A problem to show under what the screen shows (a photo it couldn't read, corners that aren't a page). */
    var note: Phrase? by mutableStateOf(null)
        private set

    private var photo: ByteArray? = null
    private val pages = ArrayList<ByteArray>()
    private var waiting: IntakeStep.Duplicate? = null
    @Volatile private var closed = false

    private val full: Boolean get() = pages.size >= ParseLimits.MAX_SCAN_PAGES || pages.sumOf { it.size.toLong() } + ParseLimits.MAX_SCAN_JPEG > ParseLimits.MAX_INPUT

    /** The camera's photo, read into RAM (the file is already gone): its preview from :files, with the corners near the edges. */
    fun photo(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty() || bytes.size > ParseLimits.MAX_INPUT) {
            note = if (bytes == null) null else FilesStrings.scanUnreadable()
            return
        }
        ui = ScanUi.Working
        note = null
        worker.execute {
            val r = reader.preview(bytes)
            val px = (r as? ParseResult.Ok)?.out?.pixels
            if (px == null) {
                note = if (r is ParseResult.Failed) FilesStrings.scanReaderDown() else FilesStrings.scanUnreadable()
                ui = ScanUi.Ready(pages.size, full)
                return@execute
            }
            photo = bytes
            ui = ScanUi.Corners(px, START)
        }
    }

    /** A corner moved: [i] (0 to 3) to ([x], [y]) as fractions of the photo. */
    fun move(i: Int, x: Float, y: Float) {
        val c = ui as? ScanUi.Corners ?: return
        ui = ScanUi.Corners(c.pixels, c.corners.toMutableList().also { it[2 * i] = x.coerceIn(0f, 1f); it[2 * i + 1] = y.coerceIn(0f, 1f) })
    }

    /** Whether the corners make a page :files can straighten. */
    fun valid(c: ScanUi.Corners): Boolean = ScanQuad.of(*c.corners.toFloatArray())?.let { ScanGeometry.valid(it, Dim(c.pixels.width, c.pixels.height)) } == true

    /** "Use this page": :files straightens it, and it joins the pages; the photo goes. */
    fun use() {
        val c = ui as? ScanUi.Corners ?: return
        val p = photo ?: return
        val quad = ScanQuad.of(*c.corners.toFloatArray())
        if (quad == null || !valid(c)) {
            note = FilesStrings.scanCorners()
            return
        }
        ui = ScanUi.Working
        note = null
        worker.execute {
            val r = reader.straighten(p, quad)
            val page = (r as? ParseResult.Ok)?.out?.scan
            if (page == null) {
                note = if (r is ParseResult.Failed) FilesStrings.scanReaderDown() else FilesStrings.scanCorners()
                ui = c
                return@execute
            }
            photo = null
            pages += page
            ui = ScanUi.Ready(pages.size, full)
        }
    }

    /** "Retake": this photo goes. */
    fun retake() {
        photo = null
        note = null
        ui = ScanUi.Ready(pages.size, full)
    }

    /** Whether the camera may take another page. */
    fun mayTake(): Boolean = (ui as? ScanUi.Ready)?.full == false

    /** "Add to Robot Files": the pages into one PDF (:files), then into Inbox. */
    fun finish() {
        if (ui !is ScanUi.Ready || pages.isEmpty()) return
        gate()?.let { note = it; return }
        ui = ScanUi.Working
        note = null
        worker.execute {
            val r = reader.pdf(pages.toList())
            val pdf = (r as? ParseResult.Ok)?.out?.pdf
            if (pdf == null) {
                note = if (r is ParseResult.Failed) FilesStrings.scanReaderDown() else FilesStrings.scanUnreadable()
                ui = ScanUi.Ready(pages.size, full)
                return@execute
            }
            val name = FilesStrings.nameScan("when" to IntakeNames.stamp(now(), ZoneId.systemDefault())).render(langs()) + ".pdf"  // l10n: not text (a file name's extension)
            settle(add(pdf) { t -> FileIntake(name, FileSource.SCAN, type = t.mime) })
        }
    }

    /** Your answer to "Add it anyway?". */
    fun answer(yes: Boolean) {
        val d = waiting ?: return
        waiting = null
        ui = ScanUi.Working
        worker.execute {
            if (yes) settle(anyway(d)) else {
                drop(d)
                done(FilesStrings.notAdded())
            }
        }
    }

    private fun settle(s: IntakeStep) {
        when (s) {
            is IntakeStep.Added -> {
                changed()
                done(FilesStrings.addedOne())
            }
            is IntakeStep.Duplicate -> {
                if (closed) return drop(s)
                waiting = s
                ui = ScanUi.Asking(FilesStrings.where(s.of.drawer, s.of.name))
            }
            is IntakeStep.Failed -> {
                note = FilesStrings.problem(s.problem, s.need)
                ui = ScanUi.Ready(pages.size, full)
            }
        }
    }

    private fun done(p: Phrase) {
        pages.clear()
        ui = ScanUi.Done(p)
    }

    /** The screen goes: the pages and photo leave RAM, a scan waiting for an answer is discarded, :files ends. */
    fun close() {
        if (closed) return
        closed = true
        worker.execute {
            photo = null
            pages.clear()
            waiting?.let(drop)
            waiting = null
            reader.end()
        }
        worker.shutdown()
    }

    companion object {
        /** Where the corners start: 6 % in from each edge. */
        val START = listOf(0.06f, 0.06f, 0.94f, 0.06f, 0.94f, 0.94f, 0.06f, 0.94f)

        /** The photo the camera wrote, read into RAM (at most what :files reads) and deleted; null when there is none. */
        fun take(f: File): ByteArray? = try {
            if (!f.isFile) null else f.inputStream().use { it.readNBytes(ParseLimits.MAX_INPUT + 1) }
        } catch (t: Exception) {
            RLog.w("files_scan_read", t)
            null
        } finally {
            f.delete()
        }
    }
}

/**
 * The scan under way, kept across MainActivity being made again (a rotation while the camera is in front): its photo and
 * pages stay in RAM until the scan screen really goes. A process that ends loses them; nothing of them was on disk.
 */
private object ScanSession {
    var model: ScanModel? = null
}

private tailrec fun activityOf(c: Context?): android.app.Activity? = when (c) {
    is android.app.Activity -> c
    is android.content.ContextWrapper -> activityOf(c.baseContext)
    else -> null
}

/** Settings › Files › Scan a document, on the phone. */
@Composable
fun ScanRoute(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val model = remember {
        ScanSession.model ?: run {
            val intake = FilesIntake.phone(app)
            ScanModel(LinkScanReader(app), gate = { FilesIntake.gateText(FilesIntake.gate(app)) }, add = intake::add, anyway = intake::anyway,
                drop = intake::drop, changed = { Cmd.send(app, Cmd.FILES) }, langs = { AppLanguage.langs(app) })
        }.also { ScanSession.model = it }
    }
    DisposableEffect(model) {
        onDispose {
            // Made again (the camera's result comes to the new one): the scan, and the photo the camera is writing, stay.
            if (activityOf(ctx)?.isChangingConfigurations != true) {
                model.close()
                ScanSession.model = null
                ScanFiles.clear(app)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = ScanFiles.photo(app)
        if (ok) Thread({ model.photo(ScanModel.take(f)) }, "robot-files-scan-read").start()  // l10n: not text (a thread's name)
        else f.delete()
    }
    var noCamera by remember { mutableStateOf(false) }
    ScanScreen(model, snackbar, onBack, noCamera = noCamera, takePhoto = {
        noCamera = false
        try {
            camera.launch(FileProvider.getUriForFile(app, ScanFiles.AUTHORITY, ScanFiles.photo(app)))
        } catch (t: Exception) {
            RLog.w("files_scan_camera", t)
            noCamera = true
        }
    })
}

/** The scan screen: stateless over [model]'s state. FLAG_SECURE: a page may be anything. */
@Composable
fun ScanScreen(model: ScanModel, snackbar: SnackbarHostState, onBack: () -> Unit, noCamera: Boolean = false, takePhoto: () -> Unit) {
    SecureWindow()
    Scaffold(topBar = { BackBar(stringResource(R.string.files_scan), onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val note = model.note?.str() ?: if (noCamera) stringResource(R.string.files_scan_no_camera) else null
            when (val ui = model.ui) {
                is ScanUi.Refused -> Text(ui.why.str(), style = MaterialTheme.typography.bodyLarge)
                is ScanUi.Ready -> {
                    Text(stringResource(if (ui.pages == 0) R.string.files_scan_start else R.string.files_scan_next), style = MaterialTheme.typography.bodyLarge)
                    if (ui.pages > 0) Text(pluralStringResource(R.plurals.files_scan_pages, ui.pages, ui.pages), style = MaterialTheme.typography.titleMedium)
                    if (ui.full) Text(FilesStrings.scanFull().str(), style = MaterialTheme.typography.bodyMedium)
                    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        if (ui.pages == 0) Button(onClick = takePhoto, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_scan_take)) }
                        else {
                            if (!ui.full) OutlinedButton(onClick = takePhoto, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_scan_add_page)) }
                            Button(onClick = { model.finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_scan_add)) }
                        }
                    }
                }
                is ScanUi.Corners -> {
                    Text(stringResource(R.string.files_scan_corners), style = MaterialTheme.typography.bodyLarge)
                    Corners(ui, model, Modifier.weight(1f, fill = false))
                    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        OutlinedButton(onClick = { model.retake() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_scan_retake)) }
                        Button(onClick = { model.use() }, enabled = model.valid(ui), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_scan_use)) }
                    }
                }
                ScanUi.Working -> {
                    Text(stringResource(R.string.files_scan_working), style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ScanUi.Asking -> AlertDialog(onDismissRequest = {}, text = { Text(FilesStrings.duplicate("where" to Arg.Sub(ui.where)).str()) },
                    confirmButton = { TextButton(onClick = { model.answer(true) }) { Text(stringResource(R.string.files_add_anyway)) } },
                    dismissButton = { TextButton(onClick = { model.answer(false) }) { Text(stringResource(R.string.files_dont_add)) } },
                    properties = app.heyrobot.ui.common.SecureDialog)
                is ScanUi.Done -> {
                    Text(ui.said.str(), style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_share_done)) }
                    }
                }
            }
        }
    }
}

/** The photo as :files gave its pixels, with the four corners to drag. */
@Composable
private fun Corners(c: ScanUi.Corners, model: ScanModel, modifier: Modifier) {
    val bitmap = remember(c.pixels) { FilePixels.bitmap(c.pixels, thumb = false)?.asImageBitmap() }
    val line = MaterialTheme.colorScheme.primary
    val handle = MaterialTheme.colorScheme.onPrimary
    Box(modifier.fillMaxWidth().aspectRatio(c.pixels.width.toFloat() / c.pixels.height), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        var dragging by remember { mutableStateOf(-1) }
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { at ->
                    val k = model.ui as? ScanUi.Corners ?: return@detectDragGestures
                    dragging = (0 until 4).minByOrNull { i -> (Offset(k.corners[2 * i] * size.width, k.corners[2 * i + 1] * size.height) - at).getDistance() } ?: -1
                },
                onDragEnd = { dragging = -1 },
                onDragCancel = { dragging = -1 },
            ) { change, _ ->
                if (dragging >= 0) model.move(dragging, change.position.x / size.width, change.position.y / size.height)
            }
        }) {
            val pts = (0 until 4).map { i -> Offset(c.corners[2 * i] * size.width, c.corners[2 * i + 1] * size.height) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (p in pts.drop(1)) lineTo(p.x, p.y)
                close()
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
            for (p in pts) {
                drawCircle(line, radius = 14.dp.toPx(), center = p)
                drawCircle(handle, radius = 6.dp.toPx(), center = p)
            }
        }
    }
}
