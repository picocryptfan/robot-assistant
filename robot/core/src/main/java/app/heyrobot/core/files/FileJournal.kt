// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.StrictJson
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Pure (java.io; FileStore holds files.lock around every use). Robot Files' delete journal: files/files/journal.json,
// the ids of the files being deleted and nothing else (ids are random; no name, kind or field is ever in it), so it
// stays plain and a delete cut short can be finished while the phone is locked (destroying keys and bytes needs no key).
//
//   {"v":1,"deleting":["<32 hex>", …]}
//
// An add needs no journal of its own: a file being added is sealed into incoming/<process>/ first, as <id>.rf.part
// while it is written and <id>.rf once whole, and only then given an entry (§2.6); the folder is the journal.

internal class FileJournal(dir: File) {
    private val file = File(dir, FILE)

    /** The ids being deleted. A missing journal is an empty one; so is one that can't be read (it is only ever written atomically). */
    fun deleting(): Set<FileId> {
        val o = readOrNull(file)?.let { StrictJson.parse(it)?.value as? Map<*, *> } ?: return emptySet()
        val l = o["deleting"] as? List<*> ?: return emptySet()
        return l.mapNotNullTo(LinkedHashSet()) { FileId.of(it as? String) }
    }

    fun add(ids: Collection<FileId>) = write(deleting() + ids)

    fun remove(ids: Collection<FileId>) = write(deleting() - ids.toSet())

    private fun write(ids: Set<FileId>) {
        if (ids.isEmpty()) {
            file.delete()
            return
        }
        writeAtomic(file, ids.joinToString(",", "{\"v\":1,\"deleting\":[", "]}") { "\"${it.hex}\"" })  // l10n: not text (the journal's JSON)
    }

    companion object {
        const val FILE = "journal.json"
    }
}
