// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.Priority
import app.heyrobot.core.TokenEstimate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1 step 2: one picture by default, four tiles behind a flag, and only a reading may send more than one. */
class ReadImagesTest {
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), n.toByte())

    @Test fun oneImageByDefaultFourTilesBehindTheFlag() {
        assertEquals(false, ReadImages.TILES_DEFAULT)
        assertEquals(1, ReadAloud.request().images)
        assertEquals(listOf(ReadImages.Tile(0, 0, 4000, 3000)), ReadImages.parts(4000, 3000))
        // P0's prep.py: 55 % corners, overlapping by a tenth.
        assertEquals(listOf(ReadImages.Tile(0, 0, 2200, 1650), ReadImages.Tile(1800, 0, 2200, 1650), ReadImages.Tile(0, 1350, 2200, 1650),
            ReadImages.Tile(1800, 1350, 2200, 1650)), ReadImages.parts(4000, 3000, tiles = true))
        assertEquals(4, ReadImages.count(tiles = true))
        assertEquals(1024 to 768, ReadImages.fit(4000, 3000))
        assertEquals(576 to 1024, ReadImages.fit(2250, 4000))
        assertEquals(800 to 600, ReadImages.fit(800, 600))
    }

    @Test fun tilesCrossPackedAndEachIsChecked() {
        val parts = (1..4).map { jpeg(it) }
        val packed = ReadImages.pack(parts)
        assertEquals(parts.map { it.toList() }, ReadImages.unpack(packed, 4)!!.map { it.toList() })
        assertArrayEquals(parts[0], ReadImages.pack(listOf(parts[0])))
        assertNull(ReadImages.unpack(packed, 3))
        assertNull(ReadImages.unpack(packed + 0, 4))
        assertNull(ReadImages.unpack(packed.copyOf(packed.size - 1), 4))
        val four = ReadAloud.request(images = 4)
        assertEquals(four, BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, four.toJson().toString()))
        assertEquals(1, BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, ReadAloud.request().toJson().toString()).let { (it as BrainMsg.OneShot).images })
        assertTrue(!ReadAloud.request().toJson().has("images"))
        assertNull(OneShotRules.imageCheck(four, packed, vision = true))
        assertEquals(4, OneShotRules.images(four, packed)!!.size)
        assertEquals(BrainError.NO_VISION, OneShotRules.imageCheck(four, packed, vision = false))
        // A part that isn't a JPEG, a pack that doesn't hold four, one JPEG said to be four.
        assertEquals(BrainError.BAD_REQUEST, OneShotRules.imageCheck(four, ReadImages.pack(parts.take(3) + byteArrayOf(1, 2, 3)), vision = true))
        assertEquals(BrainError.BAD_REQUEST, OneShotRules.imageCheck(four, ReadImages.pack(parts.take(3)), vision = true))
        assertEquals(BrainError.BAD_REQUEST, OneShotRules.imageCheck(four, parts[0], vision = true))
        // Each tile counts in the estimate.
        assertEquals(3 * TokenEstimate.IMAGE_TOKENS, OneShotRules.tokens(four) - OneShotRules.tokens(ReadAloud.request()))
        assertTrue(OneShotRules.fits(four, 4096))
    }

    @Test fun onlyAReadingSendsMoreThanOnePicture() {
        val file = BrainMsg.OneShot(OneShotKind.FILE_READ, Priority.SUMMARY, "s", "t", 700, hasImage = true)
        assertNull(BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, file.toJson().put("images", 4).toString()))
        assertNull(BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, ReadAloud.request().toJson().put("images", 5).toString()))
        assertNull(BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, ReadAloud.request().toJson().put("images", 0).toString()))
        assertNull(BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, ReadAloud.request().toJson().put("hasImage", false).put("images", 2).toString()))
        assertEquals(BrainError.BAD_REQUEST, OneShotRules.imageCheck(file.copy(images = 2), ReadImages.pack(listOf(jpeg(1), jpeg(2))), vision = true))
    }
}
