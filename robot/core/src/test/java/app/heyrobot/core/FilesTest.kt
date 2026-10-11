package app.heyrobot.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class FilesTest {
    private val dir = Files.createTempDirectory("files").toFile()
    private val data = ByteArray(3_000_000) { (it * 31).toByte() }
    private val sha = hex(MessageDigest.getInstance("SHA-256").digest(data))

    @After fun cleanUp() { dir.deleteRecursively() }

    private fun leftovers() = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }

    @Test fun installsOnlyTheRightBytes() {
        val dest = File(dir, "a/model.onnx")
        var seen = 0L
        assertEquals(InstallResult.OK, installVerified(ByteArrayInputStream(data), dest, data.size.toLong(), sha) { seen = it })
        assertTrue(dest.readBytes().contentEquals(data))
        assertEquals(data.size.toLong(), seen)
        assertTrue(File(dest.parentFile, ".model.onnx.part").exists().not())
    }

    @Test fun refusesWrongSizeOrHashAndKeepsTheOldFile() {
        val dest = File(dir, "m")
        dest.writeText("old")
        assertEquals(InstallResult.WRONG_SIZE, installVerified(ByteArrayInputStream(data.copyOf(10)), dest, data.size.toLong(), sha))
        assertEquals(InstallResult.WRONG_SIZE, installVerified(ByteArrayInputStream(data + byteArrayOf(1)), dest, data.size.toLong(), sha))
        val tampered = data.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        assertEquals(InstallResult.WRONG_HASH, installVerified(ByteArrayInputStream(tampered), dest, data.size.toLong(), sha))
        assertEquals("old", dest.readText())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun anIoErrorIsReportedNotThrown() {
        val broken = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("x") }
        assertEquals(InstallResult.IO_ERROR, installVerified(broken, File(dir, "x"), 10, sha))
        assertFalse(File(dir, "x").exists())
    }

    @Test fun bytesAreWrittenWholeOrNotAtAll() {
        val f = File(dir, "robot/me.voiceprint")
        writeAtomicBytes(f, byteArrayOf(1, 2, 3))
        writeAtomicBytes(f, byteArrayOf(4, 5))
        assertTrue(f.readBytes().contentEquals(byteArrayOf(4, 5)))
        assertTrue(f.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun hexIsLowerCase() {
        assertEquals("00ff10", hex(byteArrayOf(0, -1, 16)))
    }
}
