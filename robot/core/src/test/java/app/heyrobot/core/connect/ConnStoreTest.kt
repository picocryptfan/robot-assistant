// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillStore
import app.heyrobot.core.skills.SkillsLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** files/robot/connections.json (plan §5.2): the shared lock, an atomic write, .bad kept, a newer or damaged file never written over. */
class ConnStoreTest {
    private val dir: File = Files.createTempDirectory("conns").toFile()
    private val store = ConnStore(dir)
    private val file get() = File(dir, ConnStore.FILE)

    @After fun clean() { dir.deleteRecursively() }

    @Test fun saveReplaceDelete() {
        assertEquals(ConnFile.Ok(emptyList()), store.file())
        val ha = ConnFixtures.homeAssistant()
        assertTrue(store.save(ha))
        assertTrue(store.save(ConnFixtures.ntfy()))
        assertEquals(listOf("c-ha0001", "c-ntfy01"), store.connections().map { it.id })
        assertTrue(store.save(ha.copy(name = "House")))
        assertEquals("House", store.get(ha.id)!!.name)
        assertTrue(store.delete(ha.id))
        assertNull(store.get(ha.id))
        // Nothing invalid is written.
        assertFalse(store.save(ha.copy(name = "")))
        assertFalse(store.save(ha.copy(address = ConnFixtures.url("http://192.168.1.5:8123"))))
        assertEquals(1, store.connections().size)
        assertEquals(setOf(ConnStore.FILE, SkillsLock.FILE), dir.list()!!.toSet())
    }

    @Test fun atMostTwenty() {
        for (i in 0 until ConnCheck.MAX_CONNECTIONS) assertTrue(store.save(ConnFixtures.ntfy("c-n" + i.toString().padStart(5, '0'))))
        assertFalse(store.save(ConnFixtures.ntfy("c-extra1")))
        assertEquals(ConnCheck.MAX_CONNECTIONS, store.connections().size)
    }

    @Test fun aDamagedFileIsNeverWrittenOver() {
        file.writeText("{\"v\":1,\"connections\":[")
        assertEquals(ConnFile.Damaged, store.file())
        assertFalse(store.save(ConnFixtures.ntfy()))
        assertEquals("{\"v\":1,\"connections\":[", file.readText())
        assertTrue(store.connections().isEmpty())
        // Start over keeps it aside.
        assertTrue(store.reset())
        assertEquals("{\"v\":1,\"connections\":[", File(dir, "${ConnStore.FILE}.bad").readText())
        assertTrue(store.save(ConnFixtures.ntfy()))
    }

    @Test fun aNewerFileIsLeftAlone() {
        file.writeText("{\"v\":2,\"connections\":[],\"later\":true}")
        assertEquals(ConnFile.TooNew(2), store.file())
        assertFalse(store.save(ConnFixtures.ntfy()))
        assertTrue(store.reset())
        assertEquals("{\"v\":2,\"connections\":[],\"later\":true}", file.readText())
    }

    /** C11: Start over keeps a damaged file aside and names the keys it leaves without a connection; a newer file keeps its keys. */
    @Test fun startOverNamesTheKeysLeftWithoutAConnection() {
        val newer = "{\"v\":2,\"connections\":[],\"later\":true}"
        file.writeText(newer)
        assertNull(store.startOver(setOf("c-ha0001")))
        assertEquals(newer, file.readText())
        file.writeText("{\"v\":1,\"connections\":[")
        assertEquals(setOf("c-ha0001", "c-ntfy01"), store.startOver(setOf("c-ha0001", "c-ntfy01")))
        assertEquals(ConnFile.Ok(emptyList()), store.file())
        assertEquals("{\"v\":1,\"connections\":[", File(dir, "${ConnStore.FILE}.bad").readText())
        // A file Robot reads: only the keys of connections it doesn't hold.
        assertTrue(store.save(ConnFixtures.ntfy()))
        assertEquals(setOf("c-ha0001"), store.startOver(setOf("c-ha0001", "c-ntfy01")))
        assertEquals(listOf("c-ntfy01"), store.connections().map { it.id })
    }

    @Test fun aWriteAfterADroppedConnectionKeepsTheFileAside() {
        val good = ConnectionCodec.toJson(ConnFixtures.ntfy())
        val bad = LinkedHashMap(ConnectionCodec.toJson(ConnFixtures.webhook())).also { it["later"] = true }
        val text = ConnJson.write(linkedMapOf("v" to 1L, "connections" to listOf(good, bad)))
        file.writeText(text)
        assertEquals(ConnFile.Ok(listOf(ConnFixtures.ntfy()), 1), store.file())
        assertTrue(store.save(ConnFixtures.routing()))
        assertEquals(text, File(dir, "${ConnStore.FILE}.bad").readText())
        assertEquals(listOf("c-ntfy01", "c-osrm01"), store.connections().map { it.id })
    }

    @Test fun anOversizedFileIsDamagedUnread() {
        file.writeBytes(ByteArray(ConnectionCodec.MAX_BYTES + 1) { ' '.code.toByte() })
        assertEquals(ConnFile.Damaged, store.file())
    }

    @Test fun firstUseAnswers() {
        val ha = ConnFixtures.homeAssistant().copy(answers = emptyMap())
        assertTrue(store.save(ha))
        val origin = "https://home.tail1234.ts.net"
        assertTrue(store.answer(ha.id, origin, ConsentAnswer.ALLOW))
        assertEquals(mapOf(origin to ConsentAnswer.ALLOW), store.get(ha.id)!!.answers)
        // An answer for a host the connection doesn't send to isn't kept.
        assertTrue(store.answer(ha.id, "https://elsewhere.example.org", ConsentAnswer.ALLOW))
        assertEquals(mapOf(origin to ConsentAnswer.ALLOW), store.get(ha.id)!!.answers)
        assertTrue(store.answer(ha.id, origin, null))
        assertEquals(emptyMap<String, ConsentAnswer>(), store.get(ha.id)!!.answers)
        assertFalse(store.answer("c-gone01", origin, ConsentAnswer.ALLOW))
    }

    /** Both stores take skills.lock: edits from many threads, mixing skills and connections, all land. */
    @Test fun skillsAndConnectionsShareTheLock() {
        val skills = SkillStore(dir)
        val failures = AtomicInteger(0)
        val threads = (0 until 4).flatMap { t ->
            listOf(
                thread { repeat(4) { i -> if (!store.save(ConnFixtures.ntfy("c-t${t}n${i}aa"))) failures.incrementAndGet() } },
                thread { repeat(4) { i -> if (!skills.save(SkillFixtures.simple("s-t${t}n${i}aa", "Timer number $t $i"))) failures.incrementAndGet() } },
            )
        }
        threads.forEach { it.join() }
        assertEquals(0, failures.get())
        assertEquals(16, store.connections().size)
        assertEquals(16, skills.skills().size)
        // A store called inside the other's lock runs in it.
        val n = SkillsLock.locked(dir) { store.save(ConnFixtures.routing()); skills.skills().size }
        assertEquals(16, n)
        assertTrue(store.get("c-osrm01") != null)
    }
}
