// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8

// Pure (org.json). memory.enc's plain content and its frame. The encryption itself is not here: the
// vault in app/sys (both app processes) implements [MemoryCipher] with an Android Keystore AES-256-GCM key made with
// setUnlockedDeviceRequired(true), and [MemoryVault] with the file lock and atomic writes (tmp, fsync, rename), as
// People's store does. Everything that decides (what a damaged, locked or newer file means, and that such a file is
// never written over) is here, so it runs in JVM tests with a fake cipher.

/** What reading memory's JSON gave. */
sealed class MemoryParse {
    /** [dropped]: damaged or duplicate entries left out (the rest is kept). */
    data class Ok(val file: MemoryFile, val dropped: Int = 0) : MemoryParse()
    /** Written by a newer Robot: read nothing, write nothing (a downgrade must not lose what it can't read). */
    data class TooNew(val version: Int) : MemoryParse()
    /** Not memory's JSON at all (cut short, or something else). */
    data object Damaged : MemoryParse()
}

object MemoryCodec {
    const val VERSION = 1

    fun toJson(f: MemoryFile): String = JSONObject().put("v", VERSION).put("items", JSONArray().apply {
        for (i in f.items) put(JSONObject().apply {
            put("id", i.id)
            put("text", i.text)
            put("kind", i.kind.name)
            if (i.secret) put("secret", true)
            if (i.pinned) put("pinned", true)
            put("source", i.source.name)
            i.sourceLabel?.let { put("sourceLabel", it) }
            i.language?.let { put("lang", it) }
            if (i.about.isNotEmpty()) put("about", JSONArray(i.about))
            put("created", i.createdAt)
            put("updated", i.updatedAt)
            i.expiresAt?.let { put("expires", it) }
            if (i.uses > 0) put("uses", i.uses)
            i.usedAt?.let { put("used", it) }
            i.imported?.let { put("imported", it.name) }
            i.filing?.let { put("filing", MemoryFilingCodec.toJson(it)) }
        })
    }).put("suggestions", JSONArray().apply {
        for (s in f.suggestions) put(JSONObject().put("id", s.id).put("text", s.text).put("kind", s.kind.name).put("created", s.createdAt))
    }).put("negatives", JSONArray(f.negatives)).toString()

    /**
     * The file in [text]. No text is an empty file. An entry with a bad id, no text or a repeated id is dropped and
     * counted; text past [MemoryLimits.MAX_CHARS] is cut. An unknown kind reads as a fact, and an unknown source as
     * [MemorySource.CONTENT]: what Robot can't place is never taken for your own words (an unknown import mark, 1.0, as
     * [MemoryImported.CONTENT] too).
     */
    fun read(text: String?): MemoryParse {
        if (text.isNullOrBlank()) return MemoryParse.Ok(MemoryFile())
        val o = try { JSONObject(text) } catch (_: Exception) { return MemoryParse.Damaged }
        val v = o.optInt("v", -1)
        if (v > VERSION) return MemoryParse.TooNew(v)
        if (v < 1) return MemoryParse.Damaged
        val arr = o.optJSONArray("items") ?: return MemoryParse.Damaged
        var dropped = 0
        val seen = HashSet<String>()
        val items = ArrayList<MemoryItem>(arr.length())
        for (k in 0 until arr.length()) {
            val item = arr.optJSONObject(k)?.let(::item)
            if (item == null || !seen.add(item.id)) dropped++ else items += item
        }
        val sArr = o.optJSONArray("suggestions")
        val suggestions = (0 until (sArr?.length() ?: 0)).mapNotNull { k ->
            val s = sArr!!.optJSONObject(k) ?: return@mapNotNull null.also { dropped++ }
            val id = s.optString("id").takeIf { MemoryIds.valid(it) }
            val t = MemoryEdits.clean(s.optString("text"))
            if (id == null || t.isEmpty()) { dropped++; null }
            else MemorySuggestion(id, t, enumOr(s.optString("kind"), MemoryKind.FACT), s.optLong("created"))
        }
        val nArr = o.optJSONArray("negatives")
        val negatives = (0 until (nArr?.length() ?: 0)).mapNotNull { k -> nArr!!.optString(k).takeIf { it.isNotBlank() } }
        return MemoryParse.Ok(MemoryFile(items.take(MemoryLimits.MAX_ITEMS), suggestions, negatives), dropped + (items.size - MemoryLimits.MAX_ITEMS).coerceAtLeast(0))
    }

    private fun item(o: JSONObject): MemoryItem? {
        val id = o.optString("id").takeIf { MemoryIds.valid(it) } ?: return null
        val text = MemoryEdits.clean(o.optString("text"))
        if (text.isEmpty()) return null
        val created = o.optLong("created", 0)
        val secret = o.optBoolean("secret", false)
        // 1.4: a filing rule (MemoryFiling); a damaged one leaves the item a plain fact. A rule is a fact, never pinned.
        val filing = MemoryFilingCodec.read(o.optJSONObject("filing"))
        return MemoryItem(
            id = id,
            text = text,
            kind = if (filing != null) MemoryKind.FACT else enumOr(o.optString("kind"), MemoryKind.FACT),
            secret = secret,
            pinned = o.optBoolean("pinned", false) && !secret && filing == null,
            source = enumOr(o.optString("source"), MemorySource.CONTENT),
            sourceLabel = o.optString("sourceLabel").takeIf { it.isNotBlank() },
            language = o.optString("lang").takeIf { it.isNotBlank() },
            about = o.optJSONArray("about")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty(),
            createdAt = created,
            updatedAt = o.optLong("updated", created),
            expiresAt = if (o.has("expires")) o.optLong("expires") else null,
            uses = o.optInt("uses", 0).coerceAtLeast(0),
            usedAt = if (o.has("used")) o.optLong("used") else null,
            imported = if (o.has("imported")) enumOr(o.optString("imported"), MemoryImported.CONTENT) else null,
            filing = filing,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

/** Why the key couldn't open (or seal) the file; the vault maps Keystore's exceptions to these. */
enum class KeyProblem {
    /** The phone is locked (UserNotAuthenticatedException and the like): "Unlock first". */
    LOCKED,
    /** KeyPermanentlyInvalidatedException, or no key under the alias: "Robot's memory key is gone". */
    GONE,
    /** The tag didn't verify (AEADBadTagException): changed, cut, or sealed by another key. */
    BAD_DATA,
}

class MemoryKeyException(val problem: KeyProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * The cipher the vault implements in app/sys: AES-256-GCM with a Keystore key ("robot_memory_v1", or
 * "robot_memory_lock_v1" with "Use memory on the lock screen"), a random 12-byte IV per seal. [seal] returns IV,
 * ciphertext and tag together; [open] takes the same and throws [MemoryKeyException].
 */
interface MemoryCipher {
    /** The Keystore alias, written in the file's header so a re-key can tell which key opens it. */
    val alias: String
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/** What loading memory gave; Settings › Memory and the voice side say what each means. */
sealed class MemoryLoad {
    data class Loaded(val file: MemoryFile, val dropped: Int = 0) : MemoryLoad()
    /** No file yet. */
    data object Empty : MemoryLoad()
    /** Locked: the key can't be used until you unlock. Retrieval is skipped; a memory question hears "Unlock first". */
    data object Locked : MemoryLoad()
    /** The key is gone (screen lock removed, or reset): the file is kept aside, Settings offers Delete. */
    data object KeyGone : MemoryLoad()
    /** Damaged or not memory: kept as memory.enc.bad, "Robot can't read its memory" with Start over. */
    data object Unreadable : MemoryLoad()
    /** Written by a newer Robot. */
    data class TooNew(val version: Int) : MemoryLoad()

    /** Only after these may anything be written: a file Robot couldn't read is never written over. */
    val writable: Boolean get() = this is Loaded || this == Empty

    /** The file to change, when [writable]. */
    val current: MemoryFile? get() = when (this) {
        is Loaded -> file
        Empty -> MemoryFile()
        else -> null
    }
}

/**
 * The vault (app/sys, both app processes): the file under filesDir/memory/, its lock (memory.lock) and atomic writes.
 * Every change is [update]: load, change, seal, write, under the lock, and nothing at all when the load wasn't
 * [MemoryLoad.writable].
 */
interface MemoryVault {
    fun load(): MemoryLoad
    fun update(change: (MemoryFile) -> MemoryFile): MemoryLoad
    /** "Delete all" in Settings (after unlock and a confirm): the files and the index, not the key. */
    fun deleteAll(): Boolean
}

/**
 * memory.enc's bytes: "RMEM", a version byte, the key alias (a length byte and UTF-8), then the cipher's output. The
 * additional data is "robot-memory-v1|<file name>", so a file renamed into memory.enc doesn't open.
 */
object MemoryEnvelope {
    const val FILE = "memory.enc"
    const val VERSION = 1
    private val MAGIC = "RMEM".toByteArray(UTF_8)
    private const val MAX_ALIAS = 64

    fun aad(fileName: String): ByteArray = "robot-memory-v1|$fileName".toByteArray(UTF_8) // l10n: not text (the cipher's additional data)

    fun seal(file: MemoryFile, cipher: MemoryCipher, fileName: String = FILE): ByteArray {
        val alias = cipher.alias.toByteArray(UTF_8)
        require(alias.isNotEmpty() && alias.size <= MAX_ALIAS) { "key alias" } // l10n: not text (a programming error)
        val body = cipher.seal(MemoryCodec.toJson(file).toByteArray(UTF_8), aad(fileName))
        return MAGIC + byteArrayOf(VERSION.toByte(), alias.size.toByte()) + alias + body
    }

    /** The key alias in [bytes]' header, or null when it isn't memory's file. */
    fun alias(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 7 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val n = bytes[5].toInt() and 0xFF
        if (n == 0 || n > MAX_ALIAS || bytes.size < 6 + n) return null
        return String(bytes, 6, n, UTF_8)
    }

    /** [bytes] read with the cipher [cipherFor] gives for the header's alias (null: no such key). Null or empty bytes: no file. */
    fun open(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?, fileName: String = FILE): MemoryLoad {
        if (bytes == null || bytes.isEmpty()) return MemoryLoad.Empty
        if (bytes.size < 6 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return MemoryLoad.Unreadable
        val version = bytes[4].toInt() and 0xFF
        if (version > VERSION) return MemoryLoad.TooNew(version)
        if (version < 1) return MemoryLoad.Unreadable
        val alias = alias(bytes) ?: return MemoryLoad.Unreadable
        val cipher = cipherFor(alias) ?: return MemoryLoad.KeyGone
        val body = bytes.copyOfRange(6 + alias.toByteArray(UTF_8).size, bytes.size)
        val plain = try {
            cipher.open(body, aad(fileName))
        } catch (e: MemoryKeyException) {
            return when (e.problem) {
                KeyProblem.LOCKED -> MemoryLoad.Locked
                KeyProblem.GONE -> MemoryLoad.KeyGone
                KeyProblem.BAD_DATA -> MemoryLoad.Unreadable
            }
        } catch (_: Exception) {
            return MemoryLoad.Unreadable
        }
        return when (val p = MemoryCodec.read(String(plain, UTF_8))) {
            is MemoryParse.Ok -> MemoryLoad.Loaded(p.file, p.dropped)
            is MemoryParse.TooNew -> MemoryLoad.TooNew(p.version)
            MemoryParse.Damaged -> MemoryLoad.Unreadable
        }
    }

    /**
     * One change: open [bytes], apply [change], seal with [cipher]. The bytes to write, or null when the file couldn't
     * be read and must stay as it is (locked, key gone, damaged, newer). The load says which.
     */
    fun update(bytes: ByteArray?, cipher: MemoryCipher, cipherFor: (String) -> MemoryCipher?, fileName: String = FILE,
        change: (MemoryFile) -> MemoryFile): Pair<MemoryLoad, ByteArray?> {
        val load = open(bytes, cipherFor, fileName)
        val current = load.current ?: return load to null
        val next = change(current)
        return MemoryLoad.Loaded(next) to seal(next, cipher, fileName)
    }
}
