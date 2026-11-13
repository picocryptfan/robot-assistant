// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import app.heyrobot.BuildConfig
import app.heyrobot.core.DoneReason
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReadStrings
import app.heyrobot.core.read.ReadCode
import app.heyrobot.core.read.ReadStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.read.ReadAloud
import app.heyrobot.core.read.ReadImages

// VOICE PROCESS. 1.3, point and read (point-and-read-plan.md, job P2): the panel's READ_ALOUD. The photo (the camera's,
// through CaptureActivity's MODE_READ, or the one the panel holds) is cut into ReadImages' parts, each scaled and made a
// JPEG here, in memory, and sent as a panel photo crosses (BrainOneShots.runWithImage, KEY_IMAGE's read-only
// SharedMemory). No file: CaptureActivity deletes the camera's on every way out, and the bitmap and the bytes are only
// the turn's, dropped when it ends. Nothing here logs content.
object PanelRead {
    /** A page of small print on Fast: it waits for the model to load, and stops after two minutes. */
    val BUDGET = OneShotBudget(startWithinMs = null, totalMs = 120_000L)
    private const val QUALITY = 85
    /** The camera's photo for a reading is decoded at most this big: four tiles' worth, each then sent at ReadImages.SIDE. */
    const val EDGE = ReadImages.SIDE * 2
    /** Debug builds only: the Test Bench's demo picture (from P0's set, in the debug assets), typed as [DEMO_ASK]. */
    private const val DEMO = "read-demo.jpg" // l10n: not text (a file name)
    private const val DEMO_ASK = "read the demo picture" // l10n: not text (debug builds only)

    /** Your words that opened the camera for a reading ("read this"), and the photo it gave, until the panel is back. */
    @Volatile var asked: String? = null
    @Volatile private var held: Bitmap? = null

    /** CaptureActivity's MODE_READ photo, read when the panel shows again. */
    fun hold(bmp: Bitmap) { held = bmp }

    /** The panel shows: what was asked (or [default], for Read aloud's tap) and the photo; both forgotten either way. */
    fun taken(default: () -> String): Pair<String, Bitmap>? {
        val b = held
        val a = asked
        held = null
        asked = null
        return b?.let { (a ?: default()) to it }
    }

    /** True once, for your first reading: the caution goes before it (point-and-read-plan.md, question 4: default yes). */
    fun firstReading(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("point_and_read", Context.MODE_PRIVATE) // l10n: not text (a preferences name)
        if (p.getBoolean("cautioned", false)) return false // l10n: not text (a key)
        p.edit().putBoolean("cautioned", true).apply() // l10n: not text (a key)
        return true
    }

    /** The Test Bench's demo picture, for its exact words, in a debug build alone (a release has neither the words nor the file). */
    fun demo(ctx: Context, q: String): Bitmap? {
        if (!BuildConfig.DEBUG || !q.trim().equals(DEMO_ASK, ignoreCase = true)) return null
        val bytes = try { ctx.assets.open(DEMO).use { it.readBytes() } } catch (_: Exception) { return null }
        return Images.decodeBytes(bytes, EDGE)
    }

    /** What a reading needs of the panel: its card, its voice, its end. */
    interface Port {
        val you: Lang
        val tiles: Boolean
        fun firstReading(): Boolean
        fun show(replyId: String, text: String)
        fun say(replyId: String, s: ReadAloud.Said)
        fun caution(replyId: String, p: Phrase)
        fun refuse(replyId: String, p: Phrase)
        fun finish(replyId: String, reason: DoneReason)
        fun fail(replyId: String)
    }

    /**
     * The reading turn: the caution the first time, the parts made off the main thread, then the words shown and said as
     * they come (ReadStream: the kind first, sentence by sentence; "stop" mutes and cancels it as any reply). VoiceLog
     * keeps its code, the number of pictures and the seconds, never a word.
     */
    suspend fun run(replyId: String, bmp: Bitmap, port: Port) {
        val t0 = SystemClock.elapsedRealtime()
        var code = ReadCode.STOPPED
        var parts = 0
        try {
            if (port.firstReading()) port.caution(replyId, Phrase.M(ReadStrings.caution))
            val jpegs = withContext(Dispatchers.Default) { jpegs(bmp, port.tiles) }
            parts = jpegs.size
            val stream = ReadStream(port.you)
            fun step(text: String, done: Boolean) {
                port.show(replyId, stream.shown(text, done))
                stream.next(text, done).forEach { port.say(replyId, it) }
            }
            when (val r = send(jpegs) { step(it, false) }) {
                is OneShotResult.Done -> {
                    step(r.text, true)
                    code = ReadCode.of(null, false, ReadAloud.parse(r.text))
                    port.finish(replyId, r.reason)
                }
                is OneShotResult.TimedOut -> {
                    step(r.partial, false)
                    code = ReadCode.FAILED
                    port.finish(replyId, DoneReason.LENGTH)
                }
                is OneShotResult.Failed -> {
                    code = r.code?.let { ReadCode.of(it, false, null) } ?: ReadCode.FAILED
                    val no = r.code?.let(ReadAloud::refusal)
                    if (no != null) port.refuse(replyId, no) else port.fail(replyId)
                }
                OneShotResult.Cancelled -> port.finish(replyId, DoneReason.CANCELLED)
                else -> {
                    code = ReadCode.FAILED
                    port.fail(replyId)
                }
            }
        } finally {
            VoiceLog.add(VoiceEvent.READ_ALOUD, code.id, parts, ((SystemClock.elapsedRealtime() - t0) / 1000).toInt())
        }
    }

    /** P3: a Files page's text layer, read as it is: no model, no picture, no caution (nothing was read from pixels). */
    fun runText(replyId: String, words: String, port: Port) {
        try {
            port.show(replyId, words)
            app.heyrobot.core.read.FileRead.said(words, port.you).forEach { port.say(replyId, it) }
            port.finish(replyId, DoneReason.END)
        } finally {
            VoiceLog.add(VoiceEvent.READ_ALOUD, ReadCode.READ.id, 0, 0)
        }
    }

    /** The JPEGs a reading sends: the whole photo, or its four corner tiles; each at most ReadImages.SIDE on its longer side. */
    fun jpegs(bmp: Bitmap, tiles: Boolean): List<ByteArray> = ReadImages.parts(bmp.width, bmp.height, tiles).map { t ->
        val part = if (t.w == bmp.width && t.h == bmp.height) bmp else Bitmap.createBitmap(bmp, t.x, t.y, t.w, t.h)
        Images.jpeg(part, ReadImages.SIDE, QUALITY)
    }

    /** The photo the panel holds (CaptureActivity's JPEG, in memory), as a bitmap to cut; null if it can't be read. */
    fun bitmap(jpeg: ByteArray): Bitmap? = Images.decodeBytes(jpeg)

    /** The reading, its words streamed to [onText] (the reply so far); ReadAloud's request and nothing else. */
    suspend fun send(jpegs: List<ByteArray>, onText: (String) -> Unit): OneShotResult {
        return BrainOneShots.runWithImage(ReadAloud.request(jpegs.size), ReadImages.pack(jpegs), BUDGET, onText)
    }
}
