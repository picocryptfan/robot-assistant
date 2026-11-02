// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.mt

import app.heyrobot.core.InstalledFile
import app.heyrobot.core.MtBundle
import app.heyrobot.core.VoiceMark
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** The engine's native side with no library and no model: it opens what it's told to and answers by script. */
internal class FakeSlimt : MtNative {
    @Volatile override var available = true
    private val next = AtomicLong(100)
    val opened = CopyOnWriteArrayList<File>()
    val closed = CopyOnWriteArrayList<Long>()
    val lines = CopyOnWriteArrayList<String>()
    /** Opens the file named, or null: the file doesn't load. May throw, as the library's side could. */
    @Volatile var onOpen: (File) -> Boolean = { true }
    @Volatile var onTranslate: (String) -> String? = { it.uppercase() }

    override fun open(model: File, vocabulary: File, shortlist: File): Long? {
        opened += model
        return if (onOpen(model)) next.incrementAndGet() else null
    }

    override fun translate(handle: Long, line: String): String? {
        lines += line
        return onTranslate(line)
    }

    override fun close(handle: Long) { closed += handle }
}

/** [b]'s files as the main app leaves them: full size (sparse, no weights) with the mark that vouches for each. */
internal fun install(b: MtBundle, filesDir: File): File {
    val dir = MtBundle.dir(filesDir, b).apply { mkdirs() }
    val marked = b.files.map { f ->
        val on = File(dir, f.name)
        RandomAccessFile(on, "rw").use { it.setLength(f.bytes) }
        InstalledFile(f.name, f.bytes, on.lastModified(), f.sha256)
    }
    File(dir, VoiceMark.FILE).writeText(VoiceMark.encode(marked))
    return dir
}
