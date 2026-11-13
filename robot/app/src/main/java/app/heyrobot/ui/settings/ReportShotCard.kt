// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.feedback.ReportShot
import app.heyrobot.sys.Assistant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// RP4, the card's screenshot (report-a-problem-plan.md "What's new" 4, "Privacy and the lock"). Only a tap on "Add a screenshot
// of <app>" puts one in the report: the app behind, from the assist screenshot the panel already held (never taken fresh), or
// Robot's own window drawn to a bitmap. Held in memory only, and dropped when the report goes or the card closes.

/** The card's screenshot: what the panel handed over ([offered]), what you added ([added]), its crop and black boxes. */
class ReportShotHold {
    var offered by mutableStateOf<ReportShot?>(null)
        private set
    /** The app [offered] shows, by name; null: unknown. */
    var app by mutableStateOf<String?>(null)
        private set
    var added by mutableStateOf<ReportShot?>(null)
        private set
    var crop by mutableStateOf<ReportShot.Box?>(null)
    val boxes = mutableStateListOf<ReportShot.Box>()

    fun offer(s: ReportShot?, app: String?) {
        offered = s
        this.app = if (s == null) null else app
    }

    /** The tap on "Add a screenshot of <app>". */
    fun addHeld() { offered?.let(::add) }

    /** The tap on "Add a screenshot of Robot", with Robot's window as drawn. */
    fun add(s: ReportShot) {
        added = s
        crop = null
        boxes.clear()
    }

    fun cover(b: ReportShot.Box) { boxes += b }
    fun undo() { if (boxes.isNotEmpty()) boxes.removeAt(boxes.lastIndex) }

    fun remove() {
        added = null
        crop = null
        boxes.clear()
    }

    /** What goes with the report: null unless you added one; covered, cropped, a fresh PNG. */
    fun png(): ByteArray? = added?.edited(crop, boxes.toList())?.png()

    /** The report went or the card closed: nothing of it kept. */
    fun close() {
        offered = null
        app = null
        remove()
    }
}

/** [this] as a report's pixels; null when it can't be read (a protected or recycled picture) or is too big. */
internal fun Bitmap.toReportShot(): ReportShot? = runCatching {
    if (!ReportShot.fits(width, height)) return null
    val b = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false) ?: return null
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    if (b !== this) b.recycle()
    ReportShot(b.width, b.height, px)
}.getOrNull()

/** Robot's own window, drawn as it is now (only Robot's views: never the screen). */
private fun ownWindow(view: View): ReportShot? = runCatching {
    val root = view.rootView
    if (root.width <= 0 || root.height <= 0) return null
    val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    root.draw(Canvas(b))
    b.toReportShot().also { b.recycle() }
}.getOrNull()

@Composable
internal fun ReportShotPart(hold: ReportShotHold) {
    val ctx = LocalContext.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val added = hold.added
        if (added == null) {
            // Locked (Phone lock on, or the lock screen): no screenshot, fail closed.
            if (hold.offered != null) {
                OutlinedButton(onClick = { if (!Assistant.locked(ctx)) hold.addHeld() }) {
                    Text(stringResource(R.string.report_shot_add, hold.app ?: stringResource(R.string.report_shot_app_behind)))
                }
                Text(stringResource(R.string.report_shot_black), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { if (!Assistant.locked(ctx)) ownWindow(view)?.let(hold::add) }) {
                Text(stringResource(R.string.report_shot_add, stringResource(R.string.app_name)))
            }
            return@Column
        }
        var cropping by remember { mutableStateOf(false) }
        var start by remember { mutableStateOf<Offset?>(null) }
        var end by remember { mutableStateOf<Offset?>(null) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val image = remember(added, hold.boxes.size) {
            added.edited(null, hold.boxes.toList()).let { Bitmap.createBitmap(it.argb(), it.width, it.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
        }
        fun box(a: Offset, b: Offset): ReportShot.Box {
            val sx = added.width / size.width.toFloat()
            val sy = added.height / size.height.toFloat()
            return ReportShot.Box((min(a.x, b.x) * sx).toInt(), (min(a.y, b.y) * sy).toInt(), ceil(max(a.x, b.x) * sx).toInt(), ceil(max(a.y, b.y) * sy).toInt())
        }
        Text(stringResource(R.string.report_shot_hint), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().aspectRatio(added.width / added.height.toFloat()).onSizeChanged { size = it }
            .pointerInput(added, cropping) {
                detectDragGestures(
                    onDragStart = { start = it; end = it },
                    onDragEnd = {
                        val a = start
                        val b = end
                        start = null
                        end = null
                        if (a != null && b != null && size.width > 0 && size.height > 0) box(a, b).let { if (cropping) hold.crop = it else hold.cover(it) }
                    },
                    onDragCancel = { start = null; end = null },
                ) { change, d -> change.consume(); end = (end ?: change.position) + d }
            }
            .drawWithContent {
                drawContent()
                val sx = size.width / added.width.toFloat()
                val sy = size.height / added.height.toFloat()
                hold.crop?.let { c -> drawRect(Color.Yellow, Offset(c.left * sx, c.top * sy), Size((c.right - c.left) * sx, (c.bottom - c.top) * sy), style = Stroke(4f)) }
                val a = start
                val b = end
                if (a != null && b != null) drawRect(if (cropping) Color.Yellow else Color.Black, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)),
                    style = if (cropping) Stroke(4f) else Fill)
            }) {
            Image(image, contentDescription = stringResource(R.string.report_shot_picture), modifier = Modifier.fillMaxSize())
        }
        Row {
            @Composable fun mode(label: Int, on: Boolean, click: () -> Unit) =
                if (on) Button(onClick = click) { Text(stringResource(label)) } else OutlinedButton(onClick = click) { Text(stringResource(label)) }
            mode(R.string.report_shot_cover, !cropping) { cropping = false }
            mode(R.string.report_shot_crop, cropping) { cropping = true }
            TextButton(onClick = hold::undo, enabled = hold.boxes.isNotEmpty()) { Text(stringResource(R.string.report_shot_undo)) }
        }
        TextButton(onClick = hold::remove) { Text(stringResource(R.string.report_shot_remove)) }
    }
}
