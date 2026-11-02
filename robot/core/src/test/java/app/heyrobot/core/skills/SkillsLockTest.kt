// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * skills.lock (connections plan §5.2): SkillStore and ConnStore take the same lock. In one process, two threads wait for
 * each other (a second FileChannel.lock in the same JVM would throw instead), and a store called while the lock is held
 * on the same thread runs inside it.
 */
class SkillsLockTest {
    private val dir: File = Files.createTempDirectory("skillslock").toFile()

    @After fun clean() { dir.deleteRecursively() }

    @Test fun threadsWaitForEachOtherInsteadOfFailing() {
        val inside = AtomicInteger(0)
        val most = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            thread {
                start.await()
                repeat(20) {
                    try {
                        SkillsLock.locked(dir) {
                            val n = inside.incrementAndGet()
                            most.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                            Thread.yield()
                            inside.decrementAndGet()
                        }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(1, most.get())
    }

    @Test fun aNestedCallRunsInsideTheLockItHas() {
        val out = SkillsLock.locked(dir) { SkillsLock.locked(dir) { SkillsLock.locked(dir) { 42 } } + 1 }
        assertEquals(43, out)
        // And the lock is free afterwards: another thread takes it.
        var other = 0
        thread { other = SkillsLock.locked(dir) { 7 } }.join()
        assertEquals(7, other)
    }

    @Test fun aFailureInsideReleasesTheLock() {
        try { SkillsLock.locked(dir) { SkillsLock.locked(dir) { error("boom") } } } catch (_: IllegalStateException) {}
        var other = 0
        thread { other = SkillsLock.locked(dir) { 1 } }.join()
        assertEquals(1, other)
        assertTrue(File(dir, SkillsLock.FILE).isFile)
    }

    @Test fun skillStoreEditsFromManyThreadsAllLand() {
        val store = SkillStore(dir)
        val failures = AtomicInteger(0)
        val threads = (0 until 6).map { t ->
            thread {
                repeat(5) { i ->
                    if (!store.save(SkillFixtures.simple("s-t${t}n${i}aa".take(8), "Timer number $t $i"))) failures.incrementAndGet()
                }
            }
        }
        threads.forEach { it.join() }
        assertEquals(0, failures.get())
        assertEquals(30, store.skills().size)
    }
}
