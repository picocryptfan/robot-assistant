// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.SkillsLock
import app.heyrobot.core.writeAtomic
import java.io.File

// Pure (java.io). Where connections live (plan §5.2, C2): files/robot/connections.json, beside skills.json, written whole
// and atomically under skills.lock (SkillsLock), which both app processes take: the main app's Settings › Skills ›
// Connections writes it, and :voice writes a first-use answer (D13). The skills store's pattern: a file that can't be
// read is never written over (changes are refused and no connection is used until Settings' "Start over" keeps it
// aside as connections.json.bad); one from a newer Robot is left as it is; a write after a read that dropped
// connections first keeps the file as connections.json.bad. Keys are never here (ConnSecrets, the key file, C3).

class ConnStore(private val dir: File) {
    private val file get() = File(dir, FILE)

    /** What connections.json holds now: no file is no connections. */
    fun file(): ConnFile {
        val f = file
        if (!f.exists()) return ConnFile.Ok(emptyList())
        val bytes = try {
            if (f.length() > ConnectionCodec.MAX_BYTES) return ConnFile.Damaged
            f.readBytes()
        } catch (_: Exception) {
            return ConnFile.Damaged
        }
        return ConnectionCodec.decode(bytes)
    }

    /** Your connections (none while the file can't be read or is from a newer Robot: none is used then). */
    fun connections(): List<Connection> = (file() as? ConnFile.Ok)?.connections.orEmpty().map { McpStore(dir).attach(it) }

    fun get(id: String): Connection? = connections().firstOrNull { it.id == id }

    /**
     * Reads, changes and writes connections.json under the lock. Null when it couldn't: the file is damaged or newer
     * (never written over), the result can't be written (a connection ConnCheck refuses, two with one id, over
     * [ConnCheck.MAX_CONNECTIONS] or [ConnectionCodec.MAX_BYTES]), or the write failed.
     */
    fun edit(change: (List<Connection>) -> List<Connection>): List<Connection>? = try {
        SkillsLock.locked(dir) {
            when (val now = file()) {
                is ConnFile.Ok -> {
                    val next = change(now.connections)
                    if (next != now.connections) {
                        if (next.size > ConnCheck.MAX_CONNECTIONS || next.map { it.id }.distinct().size != next.size || !next.all(ConnCheck::valid)) return@locked null
                        val text = ConnectionCodec.encode(next)
                        val back = ConnectionCodec.decode(text)
                        // Written only when every connection reads back as it is: nothing is saved that the next read would drop.
                        if (back !is ConnFile.Ok || back.dropped > 0 || back.connections != next || ConnectionCodec.encode(back.connections) != text) return@locked null
                        if (now.dropped > 0) keepAside()
                        writeAtomic(file, text)
                    }
                    next
                }
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * [block] under skills.lock, the lock [edit] takes (reentrant on one thread). C12's L1: Settings deletes a key and
     * changes or removes its connection inside it, and the connection's window checks a key it just saved inside it, so
     * the window sees both changes or neither.
     */
    fun <T> locked(block: () -> T): T = SkillsLock.locked(dir, block)

    /** Adds [c], or replaces the connection with its id. False when it can't be saved or written. */
    fun save(c: Connection): Boolean {
        if (!ConnCheck.valid(c)) return false
        val saved = edit { list ->
            if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it }
            else if (list.size >= ConnCheck.MAX_CONNECTIONS) list
            else list + c
        } ?: return false
        return c in saved
    }

    /** Removes connection [id] (its key file is C3's: Remove connection deletes it and its Keystore key). */
    fun delete(id: String): Boolean = edit { list -> list.filter { it.id != id } } != null

    /**
     * Keeps a first-use answer for connection [id] and host [origin] (D13), from either process; [answer] null forgets it
     * (Settings' Forget). False when the connection is gone or the file can't be written.
     */
    fun answer(id: String, origin: String, answer: ConsentAnswer?): Boolean {
        var found = false
        val ok = edit { list ->
            list.map { c ->
                if (c.id != id) c
                else {
                    found = true
                    ConnConsent.answered(c, origin, answer)
                }
            }
        } != null
        return ok && found
    }

    /** Starts over after a damaged file: kept aside as connections.json.bad, and Robot has no connections. */
    fun reset(): Boolean = try {
        SkillsLock.locked(dir) {
            if (file() is ConnFile.Damaged) {
                val bad = File(dir, "$FILE.bad")
                bad.delete()
                file.renameTo(bad)
            } else true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * C11, Settings' Start over: keeps a damaged file aside ([reset]) and returns which of [keys] (the connections that have
     * a key folder on this phone, by id) have no connection now, for the app to delete. Null when nothing started over: a
     * newer Robot's file is left as it is, with its keys, and so is one that couldn't be moved aside.
     */
    fun startOver(keys: Set<String>): Set<String>? = try {
        SkillsLock.locked(dir) {
            if (!reset()) return@locked null
            val now = file() as? ConnFile.Ok ?: return@locked null
            keys - now.connections.mapTo(HashSet()) { it.id }
        }
    } catch (_: Exception) {
        null
    }

    private fun keepAside() {
        val bad = File(dir, "$FILE.bad")
        if (!bad.exists()) file.copyTo(bad)
    }

    companion object {
        const val FILE = "connections.json"
    }
}
