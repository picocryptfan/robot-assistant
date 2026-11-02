// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.mt

import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtError
import app.heyrobot.core.MtException
import app.heyrobot.core.VoiceMark
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the engine loads and refuses: only files the main app verified, and it frees what it opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BergamotEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val b = MtBundle.FR_EN
    private val slimt = FakeSlimt()
    private val files by lazy { tmp.newFolder("files") }
    private val engine by lazy { BergamotEngine(files, slimt) }
    private val ids get() = b.directions.map { it.id }

    private fun failure(block: () -> Unit) = assertThrows(MtException::class.java) { block() }.error

    @Test fun withoutTheLibraryItSaysSoEvenWithEveryFile() {
        install(b, files)
        slimt.available = false
        assertEquals(MtError.NO_ENGINE, failure { engine.load(b) })
        assertEquals(0, slimt.opened.size)
    }

    @Test fun aBuildWithoutTheLibraryIsNotACrash() {
        assertFalse(Slimt.available)
        install(b, files)
        assertEquals(MtError.NO_ENGINE, failure { BergamotEngine(files).load(b) })
    }

    @Test fun nothingIsOpenedWithoutTheMark() {
        assertEquals(MtError.NOT_DOWNLOADED, failure { engine.load(b) })
        val dir = install(b, files)
        File(dir, VoiceMark.FILE).delete()
        assertEquals(MtError.NOT_DOWNLOADED, failure { engine.load(b) })
        assertEquals(0, slimt.opened.size)
    }

    @Test fun aFileChangedSinceTheMarkIsNotDownloaded() {
        val dir = install(b, files)
        val f = File(dir, b.filesOf(b.directions.first()).first().name)
        RandomAccessFile(f, "rw").use { it.setLength(f.length() - 1) }
        assertEquals(MtError.NOT_DOWNLOADED, failure { engine.load(b) })
        assertEquals(0, slimt.opened.size)
    }

    @Test fun aFileTouchedSinceTheMarkIsNotDownloaded() {
        val dir = install(b, files)
        val f = File(dir, b.filesOf(b.directions.first()).first().name)
        f.setLastModified(f.lastModified() - 60_000)
        assertEquals(MtError.NOT_DOWNLOADED, failure { engine.load(b) })
    }

    @Test fun whatTheLibraryRefusesIsALoadFailure() {
        install(b, files)
        slimt.onOpen = { false }
        assertEquals(MtError.LOAD_FAILED, failure { engine.load(b) })
        assertEquals(emptyList<String>(), engine.loaded)
    }

    @Test fun everyDirectionLoadsWithItsTimeAndItsOwnFiles() {
        val dir = install(b, files)
        val ms = engine.load(b)
        assertEquals(ids, ms.keys.toList())
        assertEquals(ids, engine.loaded)
        assertEquals(b.directions.map { File(dir, it.model) }, slimt.opened.toList())
        assertTrue(ids.all(engine::has))
    }

    @Test fun whatIsLoadedIsNotLoadedAgain() {
        install(b, files)
        engine.load(b)
        val n = slimt.opened.size
        assertEquals(emptyMap<String, Long>(), engine.load(b))
        assertEquals(n, slimt.opened.size)
    }

    @Test fun aHalfLoadKeepsWhatOpenedAndTheNextTryOpensTheRest() {
        install(b, files)
        val second = b.directions[1]
        slimt.onOpen = { it.name != second.model }
        assertEquals(MtError.LOAD_FAILED, failure { engine.load(b) })
        assertEquals(listOf(b.directions[0].id), engine.loaded)
        slimt.onOpen = { true }
        assertEquals(listOf(second.id), engine.load(b).keys.toList())
        assertEquals(ids, engine.loaded)
    }

    @Test fun aLineGoesToItsDirectionAndAFailureIsNull() {
        install(b, files)
        engine.load(b)
        assertEquals("BONJOUR", engine.translate(ids[0], "bonjour"))
        slimt.onTranslate = { null }
        assertNull(engine.translate(ids[0], "bonjour"))
        assertNull("nothing is asked of a direction not in memory", engine.translate("xx-yy", "bonjour"))
        assertEquals(2, slimt.lines.size)
    }

    @Test fun unloadClosesEveryHandleOnce() {
        install(b, files)
        engine.load(b)
        engine.unload()
        assertEquals(ids.size, slimt.closed.toSet().size)
        assertEquals(ids.size, slimt.closed.size)
        assertEquals(emptyList<String>(), engine.loaded)
        assertFalse(engine.has(ids[0]))
        engine.unload()
        assertEquals(ids.size, slimt.closed.size)
    }
}
