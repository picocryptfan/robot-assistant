// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.heyrobot.core.RLog
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.sys.AppLanguage

// VOICE PROCESS. Pictures for Gemma: downscaled JPEG bytes kept in memory (spec item 5/6). A camera photo
// lands in the app cache only until it is read; it is deleted on every way out (read, cancelled, failed, closed).

object Images {
    /** Long edge sent to the model; Gemma 4's vision encoder resizes anyway. */
    const val MAX_EDGE = 1024
    const val THUMB_EDGE = 320

    fun scaled(src: Bitmap, maxEdge: Int): Bitmap {
        val b = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val edge = max(b.width, b.height)
        if (edge <= maxEdge) return b
        val f = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
    }

    fun jpeg(src: Bitmap, maxEdge: Int = MAX_EDGE, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().use { out ->
            scaled(src, maxEdge).compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /** Decodes an image (EXIF rotation applied by ImageDecoder), already scaled to at most [maxEdge]. */
    fun decode(source: ImageDecoder.Source, maxEdge: Int = MAX_EDGE): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > maxEdge) {
                val f = maxEdge.toFloat() / edge
                decoder.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        }
}

/**
 * The panel's camera and photo buttons. A VoiceInteractionSession cannot start an activity for a result,
 * so the panel starts this invisible activity (startAssistantActivity) and hides; it opens the system
 * camera (ACTION_IMAGE_CAPTURE into a FileProvider file) or the photo picker, hands the picture to the
 * panel in memory, and shows the panel again.
 */
class CaptureActivity : Activity() {
    private val shot: File get() = File(File(cacheDir, "capture").apply { mkdirs() }, "photo.jpg")
    /** The picture is being read on its thread: the file must stay until it is. */
    @Volatile private var decoding = false
    /** The photo's own bytes, as the camera or the picker gave them (at most [MAX_ORIGINAL] + 1). */
    @Volatile private var original: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val ok = try {
            when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_CAMERA -> {
                    shot.delete()
                    val uri = FileProvider.getUriForFile(this, AUTHORITY, shot)
                    val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    i.clipData = ClipData.newRawUri("photo", uri)
                    startActivityForResult(i, REQ_CAMERA)
                    true
                }
                else -> {
                    startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_PICK)
                    true
                }
            }
        } catch (t: Throwable) {
            RLog.w("capture_start", t)
            false
        }
        if (!ok) done(null, PanelStrings.cameraDidntOpen().render(AppLanguage.langs(this)))
    }

    @Deprecated("Deprecated in Java") // l10n: not text (annotation)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return done(null, null)
        val app = applicationContext
        decoding = true
        Thread({
            val (bmp, error) = try {
                // 1.4, "file this": the photo's own bytes, read before anything decodes it, kept in RAM with the panel.
                original = try {
                    when (requestCode) {
                        REQ_CAMERA -> shot.inputStream().use { it.readNBytes(MAX_ORIGINAL + 1) }
                        else -> data?.data?.let { u -> app.contentResolver.openInputStream(u)?.use { it.readNBytes(MAX_ORIGINAL + 1) } }
                    }
                } catch (t: Exception) {
                    RLog.w("capture_original", t)
                    null
                }
                val src = when (requestCode) {
                    REQ_CAMERA -> ImageDecoder.createSource(shot)
                    else -> data?.data?.let { ImageDecoder.createSource(app.contentResolver, it) }
                }
                (src?.let { Images.decode(it) }) to (if (src == null) PanelStrings.noPicture().render(AppLanguage.langs(app)) else null)
            } catch (t: Throwable) {
                RLog.w("capture_decode", t)
                null to PanelStrings.unreadablePicture().render(AppLanguage.langs(app))
            } finally {
                shot.delete()
                decoding = false
            }
            runOnUiThread { done(bmp, error) }
        }, "robot-capture").start()
    }

    private fun done(bmp: Bitmap?, error: String?) {
        // Cancelled, failed or read: the camera's file never outlives the capture.
        if (!decoding) shot.delete()
        if (bmp != null) {
            val o = original
            val tooBig = o != null && o.size > MAX_ORIGINAL
            PanelController.onImagePicked(Images.jpeg(bmp), Images.scaled(bmp, Images.THUMB_EDGE), o?.takeIf { !tooBig }, tooBig)
        } else if (error != null) {
            PanelController.notify(error)
        }
        RobotVoiceService.instance?.show(RobotVoiceService.SRC_CAPTURE)
        finish()
    }

    override fun onDestroy() {
        // Closed some other way (the task removed, the process going): no photo is left behind.
        if (isFinishing && !decoding) shot.delete()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CAMERA = "camera"
        const val MODE_PHOTOS = "photos"
        /** The manifest's `${applicationId}.capture` (unique on the phone, so 1.0 installs beside 0.9). */
        const val AUTHORITY = app.heyrobot.BuildConfig.APPLICATION_ID + ".capture"
        private const val REQ_CAMERA = 1
        /** The largest photo kept for "file this": what Robot Files' reader reads of one file. */
        private const val MAX_ORIGINAL = app.heyrobot.core.parse.ParseLimits.MAX_INPUT
        private const val REQ_PICK = 2

        fun intent(ctx: android.content.Context, mode: String): Intent =
            Intent(ctx, CaptureActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
