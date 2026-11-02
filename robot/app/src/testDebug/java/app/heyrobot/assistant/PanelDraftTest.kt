// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.graphics.Bitmap
import app.heyrobot.core.Attachment
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Removing a draft's chip forgets it (PanelAttach's header): "file this" after that has none of its bytes to file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelDraftTest {
    private val thumb get() = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @After fun clean() {
        PanelController.removeDraft()
        PanelFiles.picked(null, false)
    }

    @Test fun aRemovedChipLeavesNothingToFile() {
        PanelController.onImagePicked(byteArrayOf(9), thumb, original = byteArrayOf(1, 2, 3))
        PanelController.removeDraft()
        val what = PanelFiles.what(Attachment.NONE, null)
        assertNull(what.photo)
        assertEquals(false, what.tooBig)
        assertNull(what.name)
    }

    @Test fun aRemovedFileChipLeavesNothingToFileOrSave() {
        PanelController.onFilePicked(PanelAttach.Picked(PanelAttach.Held("notes.txt", "text", false, null), null, byteArrayOf(1, 2, 3), false, null))
        PanelController.removeDraft()
        val what = PanelFiles.what(Attachment.NONE, null)
        assertNull(what.photo)
        assertNull(what.name)
        assertNull(PanelFiles.savable.value)
    }

    @Test fun aRemovedChipTooBigToHoldIsForgottenToo() {
        PanelController.onImagePicked(byteArrayOf(9), thumb, original = null, tooBig = true)
        PanelController.removeDraft()
        assertEquals(false, PanelFiles.what(Attachment.NONE, null).tooBig)
    }

    @Test fun removingAChipThatHoldsNoFileKeepsTheFileAlreadySent() {
        // The file went with its question (its Save to Robot Files still works); the screen's chip, or none, isn't that file.
        PanelFiles.picked(byteArrayOf(1, 2, 3), tooBig = false, name = "sent.pdf")
        PanelController.removeDraft()
        assertArrayEquals(byteArrayOf(1, 2, 3), PanelFiles.what(Attachment.NONE, null).photo)
    }
}
