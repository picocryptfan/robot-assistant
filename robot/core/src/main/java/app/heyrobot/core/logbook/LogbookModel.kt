// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

import app.heyrobot.core.SealedEnvelope
import app.heyrobot.core.SealedOpen
import app.heyrobot.core.memory.MemoryCipher
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

// Pure. The logbook (docs/plans/spoken-log-plan.md): what you log by voice, with when, and a number and unit if you said
// one. Named Logbook so it never reads as VoiceLog (the diagnostic log) or Lists' journal. Nothing here logs an entry's words.

/** One entry: [what] in your words, [at] when it happened (epoch ms), [number] and [unit] if you said one, [added] when logged. */
data class LogEntry(val id: String, val at: Long, val what: String, val number: BigDecimal? = null, val unit: String? = null, val added: Long = at) {
    override fun toString() = "LogEntry(${if (number != null) "number" else "words"})" // l10n: not text (log only: never an entry's words)
}

object LogbookLimits {
    const val MAX_ENTRIES = 10_000
    const val MAX_WHAT = 200
    const val MAX_UNIT = 24
    /** Adds waiting in the lock-screen journal. */
    const val MAX_JOURNAL = 200
    /** "What's in my log?": the newest few. */
    const val RECENT = 5
    /** logbook.json in a backup. */
    const val MAX_FILE = 4L * 1024 * 1024
    private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
    fun validId(id: String): Boolean = ID.matches(id)
}

/** The log's JSON ("v" 1): the whole log in log.enc and in a backup's logbook.json; one entry in a journal file. */
object LogbookCodec {
    const val VERSION = 1

    fun toJson(entries: List<LogEntry>): String {
        val a = JSONArray()
        for (e in entries) a.put(entry(e))
        return JSONObject().put("v", VERSION).put("entries", a).toString()
    }

    fun oneToJson(e: LogEntry): String = JSONObject().put("v", VERSION).put("entry", entry(e)).toString()

    /** The entries, or null when anything in it doesn't read (the caller keeps the file aside, never rewrites it). */
    fun fromJson(s: String): List<LogEntry>? {
        return try {
            val o = JSONObject(s)
            if (o.optInt("v", -1) != VERSION) return null
            val a = o.getJSONArray("entries")
            if (a.length() > LogbookLimits.MAX_ENTRIES) return null
            val out = ArrayList<LogEntry>(a.length())
            for (i in 0 until a.length()) out += read(a.getJSONObject(i)) ?: return null
            if (out.map { it.id }.toSet().size != out.size) return null
            out
        } catch (_: Exception) {
            null
        }
    }

    fun oneFromJson(s: String): LogEntry? {
        return try {
            val o = JSONObject(s)
            if (o.optInt("v", -1) != VERSION) return null
            read(o.getJSONObject("entry"))
        } catch (_: Exception) {
            null
        }
    }

    private fun entry(e: LogEntry): JSONObject = JSONObject().put("id", e.id).put("at", e.at).put("what", e.what).put("added", e.added).apply {
        e.number?.let { put("n", it.toPlainString()) }
        e.unit?.let { put("u", it) }
    }

    private fun read(o: JSONObject): LogEntry? {
        val id = o.optString("id")
        val what = o.optString("what")
        val at = o.optLong("at", -1)
        if (!LogbookLimits.validId(id) || what.isBlank() || what.length > LogbookLimits.MAX_WHAT || at <= 0) return null
        val n = if (o.has("n")) (o.opt("n") as? String)?.toBigDecimalOrNull() ?: return null else null
        val u = if (o.has("u")) (o.opt("u") as? String)?.takeIf { it.isNotBlank() && it.length <= LogbookLimits.MAX_UNIT } ?: return null else null
        return LogEntry(id, at, what, n, u, o.optLong("added", at))
    }
}

/**
 * The sealed framing ("RLOG", core SealedEnvelope): the whole log in log.enc under the unlock-only key, and one entry per
 * journal file (<id>.locked.enc) under the lock-screen key. The app's sys/LogbookVault says which key seals what.
 */
object LogbookEnvelope {
    const val VERSION = 1
    val frame = SealedEnvelope("RLOG", "logbook", VERSION) // l10n: not text (the file's magic and the additional data's kind)

    fun seal(entries: List<LogEntry>, cipher: MemoryCipher, fileName: String): ByteArray =
        frame.seal(LogbookCodec.toJson(entries).toByteArray(Charsets.UTF_8), cipher, fileName)

    fun open(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?, fileName: String): SealedOpen<List<LogEntry>> =
        frame.open(bytes, cipherFor, fileName) { LogbookCodec.fromJson(String(it, Charsets.UTF_8)) }

    fun sealOne(e: LogEntry, cipher: MemoryCipher, fileName: String): ByteArray =
        frame.seal(LogbookCodec.oneToJson(e).toByteArray(Charsets.UTF_8), cipher, fileName)

    fun openOne(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?, fileName: String): SealedOpen<LogEntry> =
        frame.open(bytes, cipherFor, fileName) { LogbookCodec.oneFromJson(String(it, Charsets.UTF_8)) }
}

/** Settings › Logbook: [on]; [addLocked], adding over the lock screen (to the journal). */
data class LogbookSettings(val on: Boolean = true, val addLocked: Boolean = true)

/** files/robot/logbook_settings.json, its own file (settings.json stays at schema 13). */
object LogbookSettingsCodec {
    const val FILE = "logbook_settings.json"
    const val VERSION = 1

    fun encode(s: LogbookSettings): String = JSONObject().put("v", VERSION).put("on", s.on).put("addLocked", s.addLocked).toString()

    /** No file: the defaults. A file that doesn't read: on, but nothing added while locked (fail closed). */
    fun decode(json: String?): LogbookSettings {
        if (json == null) return LogbookSettings()
        return try {
            val o = JSONObject(json)
            if (o.optInt("v", -1) < 1) return LogbookSettings(addLocked = false)
            LogbookSettings(on = o.opt("on") as? Boolean ?: true, addLocked = o.opt("addLocked") as? Boolean ?: false)
        } catch (_: Exception) {
            LogbookSettings(addLocked = false)
        }
    }
}
