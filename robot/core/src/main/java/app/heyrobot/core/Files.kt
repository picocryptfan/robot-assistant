package app.heyrobot.core

import java.io.File
import java.io.FileOutputStream

// Pure java.io: storage helpers shared by every process. Each file has one writer process; readers
// see either the old or the new content, never a half-written file.

/** Writes through a temporary file, fsyncs it and renames it over [f]. */
fun writeAtomic(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, ".${f.name}.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!tmp.renameTo(f)) {
        tmp.delete()
        throw java.io.IOException("rename failed")
    }
}

fun readOrNull(f: File): String? = try { if (f.isFile) f.readText(Charsets.UTF_8) else null } catch (_: Exception) { null }

inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
