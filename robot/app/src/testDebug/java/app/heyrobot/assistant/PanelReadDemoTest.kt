// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.3 point and read (P2): the Test Bench's demo picture, for a phone run without the camera. A debug build alone has it
 * (src/debug/assets), small, a JPEG with nothing of the camera's in it; only its exact words reach it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelReadDemoTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun theDemoPictureIsSmallAndInTheDebugBuildAlone() {
        val bytes = ctx.assets.open("read-demo.jpg").use { it.readBytes() }
        assertTrue(bytes.size in 1..200_000)
        assertEquals(listOf(0xFF, 0xD8, 0xFF), bytes.take(3).map { it.toInt() and 0xFF })
        // No camera metadata (EXIF's APP1).
        assertTrue((0 until bytes.size - 1).none { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xE1.toByte() && it < 64 })
        val src = File(System.getProperty("user.dir")!!, "src")
        assertTrue(File(src, "debug/assets/read-demo.jpg").isFile)
        assertTrue(File(src, "main/assets/read-demo.jpg").let { !it.exists() })
        assertNull(PanelRead.demo(ctx, "read this"))
        assertNull(PanelRead.demo(ctx, "read the demo picture please"))
    }
}
