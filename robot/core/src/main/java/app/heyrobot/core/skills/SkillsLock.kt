// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Pure (java.io, java.nio). skills.lock: the one lock every writer of files/robot/'s skills files takes, in both app
// processes (the main app's Settings, and :voice's runs, teaching and first-use answers): skills.json, its settings and
// state, and connections.json (connections plan §5.2). A file lock alone isn't enough inside one process: a second
// FileChannel.lock on the same file in the same JVM throws (OverlappingFileLockException) instead of waiting, so every
// store goes through this one monitor first, and a store that calls another while holding the lock (on the same thread)
// runs inside the lock it already has instead of taking the file again.

object SkillsLock {
    const val FILE = "skills.lock"

    private val MONITOR = Any()

    /** The lock files this thread holds now, by path, with how deep. */
    private val held = ThreadLocal.withInitial { HashMap<String, Int>() }

    /** [block] under [dir]'s skills.lock, made if it isn't there. Reentrant on one thread. */
    fun <T> locked(dir: File, block: () -> T): T = synchronized(MONITOR) {
        val f = File(dir, FILE)
        val key = f.absolutePath
        val mine = held.get()
        val depth = mine[key] ?: 0
        if (depth > 0) {
            mine[key] = depth + 1
            try { block() } finally { mine[key] = depth }
        } else {
            dir.mkdirs()
            FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                val l = ch.lock()
                mine[key] = 1
                try { block() } finally {
                    mine.remove(key)
                    l.release()
                }
            }
        }
    }
}
