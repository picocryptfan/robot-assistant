// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ModelMove
import org.json.JSONObject
import java.io.File

// Pure (java.io and org.json). Wikipedia on this phone, P3 (wikipedia-offline-plan.md §2): the copies Robot offers, each
// pinned here with its file name, size and SHA-256 (Kiwix publishes each .sha256; read 7 Nov 2026), updated with Robot's
// releases and never on their own. You start a download in Settings › Wikipedia on this phone, never Robot. Android's
// download manager fetches it from download.kiwix.org (which sends it on to a mirror) on Wi-Fi or another unmetered
// network only: no question about mobile data, ever. It lands in Robot's outside folder, then is copied into
// filesDir/wiki with every byte hashed, and is kept only when its size and SHA-256 are the pinned ones. What is on the
// phone is in wikipedia.json (files/robot), which no backup takes.

/** One copy (plan §1): intros only ("mini": lead and infobox, no pictures), each with its full-text index. */
enum class WikiSet(val id: String, val lang: String, val file: String, val bytes: Long, val sha256: String, val year: Int, val month: Int, val starter: Boolean) {
    EN_TOP("en_top", "en", "wikipedia_en_top_mini_2026-09.zim", 296_858_160L, // l10n: not text (an id, a language code and a file name)
        "9e75b5f03b4ff61864772d811bd9715c9cdc27a429229abb779aaa3cc3aa8e05", 2026, 9, true), // l10n: not text (a SHA-256)
    FR_TOP("fr_top", "fr", "wikipedia_fr_top_mini_2026-10.zim", 153_149_709L, // l10n: not text (an id, a language code and a file name)
        "a594af26ade685ada0ee8f954ec0c3f15af80afd3e2b5effe982380ffdd6afd6", 2026, 10, true), // l10n: not text (a SHA-256)
    EN_TOP1M("en_top1m", "en", "wikipedia_en_top1m_mini_2026-04.zim", 3_022_411_034L, // l10n: not text (an id, a language code and a file name)
        "56b963ffb5f5246da10cf87d1bdbaaad457b39e3b766d52844ccd1aab3a49a34", 2026, 4, false), // l10n: not text (a SHA-256)
    FR_ALL("fr_all", "fr", "wikipedia_fr_all_mini_2026-05.zim", 3_584_080_182L, // l10n: not text (an id, a language code and a file name)
        "b82d57cbbd19d568a5701eec1b4c96079c20e62a7cec70cdf150c0a20aff5fd1", 2026, 5, false); // l10n: not text (a SHA-256)

    /** Kiwix's address for it; download.kiwix.org answers with a redirect to one of its mirrors. */
    val url: String get() = "$HOST$file"

    companion object {
        const val HOST = "https://download.kiwix.org/zim/wikipedia/" // l10n: not text (an address)
        fun byId(id: String?): WikiSet? = entries.firstOrNull { it.id == id }
    }
}

/** wikipedia.json: the copies on the phone (as checked when they moved in) and the downloads under way (the download manager's ids). */
data class WikiFiles(val sets: Map<String, Installed> = emptyMap(), val downloads: Map<String, Long> = emptyMap()) {
    data class Installed(val file: String, val bytes: Long, val sha256: String)
}

object WikiFilesCodec {
    /** files/robot/wikipedia.json: not one of Settings' files (schema stays 13) and in no backup category. */
    const val FILE = "wikipedia.json" // l10n: not text (a file name)
    const val VERSION = 1

    fun encode(f: WikiFiles): String {
        val sets = JSONObject()
        for ((id, i) in f.sets) sets.put(id, JSONObject().put("file", i.file).put("bytes", i.bytes).put("sha256", i.sha256)) // l10n: not text (JSON keys)
        val dl = JSONObject()
        for ((id, n) in f.downloads) dl.put(id, n)
        return JSONObject().put("v", VERSION).put("sets", sets).put("downloads", dl).toString() // l10n: not text (JSON keys)
    }

    /** Missing or unreadable: nothing on the phone (a file the next check can't vouch for is never opened). */
    fun decode(json: String?): WikiFiles {
        val o = try { JSONObject(json ?: return WikiFiles()) } catch (_: Exception) { return WikiFiles() }
        val sets = mutableMapOf<String, WikiFiles.Installed>()
        o.optJSONObject("sets")?.let { s -> for (id in s.keys()) s.optJSONObject(id)?.let { // l10n: not text (a JSON key)
            sets[id] = WikiFiles.Installed(it.optString("file"), it.optLong("bytes", -1), it.optString("sha256")) } } // l10n: not text (JSON keys)
        val dl = mutableMapOf<String, Long>()
        o.optJSONObject("downloads")?.let { d -> for (id in d.keys()) d.optLong(id, -1).takeIf { it >= 0 }?.let { dl[id] = it } } // l10n: not text (a JSON key)
        return WikiFiles(sets, dl)
    }
}

object WikiDownload {
    /** Under Robot's outside folder (getExternalFilesDir), where the download manager writes. */
    const val STAGING = "wiki-download" // l10n: not text (a folder name)
    /** Under filesDir, which only Robot can write: the copies :wiki opens. */
    const val DIR = "wiki" // l10n: not text (a folder name)

    /** What Robot asks Android's download manager for: never over a metered network (mobile data) or roaming. */
    data class Request(val url: String, val overMetered: Boolean, val overRoaming: Boolean, val path: String)

    fun request(s: WikiSet): Request = Request(s.url, overMetered = false, overRoaming = false, path = "$STAGING/${s.file}")

    /** The free space a download asks for before it starts: twice the file (outside, then copied in) and the move's margin. */
    fun spaceNeeded(s: WikiSet): Long = 2 * s.bytes + ModelMove.MARGIN

    enum class Refusal { NO_ROOM, ALREADY }

    /** Null when the download may start. [free]: free bytes (below 0: unknown, so no); [pending]: what downloads under way still write. */
    fun refuse(s: WikiSet, free: Long, pending: Long, f: WikiFiles, filesDir: File): Refusal? = when {
        installed(filesDir, f, s) || s.id in f.downloads -> Refusal.ALREADY
        free < 0 || free - pending.coerceAtLeast(0) < spaceNeeded(s) -> Refusal.NO_ROOM
        else -> null
    }

    fun target(filesDir: File, s: WikiSet): File = File(File(filesDir, DIR), s.file)

    /** The copies a question may open (PanelWiki): each set wikipedia.json records whose file is in filesDir/wiki. */
    fun copies(filesDir: File, f: WikiFiles): List<Pair<WikiSet, File>> = f.sets.mapNotNull { (id, i) ->
        val s = WikiSet.byId(id) ?: return@mapNotNull null
        File(File(filesDir, DIR), i.file).takeIf { it.isFile }?.let { s to it }
    }

    /** On the phone: its entry is the pinned file, size and SHA-256 and the file is there at that size. */
    fun installed(filesDir: File, f: WikiFiles, s: WikiSet): Boolean {
        val i = f.sets[s.id] ?: return false
        val t = target(filesDir, s)
        return i.file == s.file && i.bytes == s.bytes && i.sha256 == s.sha256 && t.isFile && t.length() == s.bytes
    }

    enum class Moved { OK, WRONG_FILE, NO_ROOM, FAILED }

    /**
     * Copies the finished download [src] into filesDir/wiki, hashing it as it goes; kept only at the pinned size and SHA-256.
     * A wrong file is deleted (never moved); [src] is deleted once the copy inside is complete and checked.
     */
    fun moveIn(src: File, filesDir: File, s: WikiSet, free: () -> Long, go: () -> Boolean = { true }): Moved {
        if (!src.isFile || src.length() != s.bytes) { src.delete(); return Moved.WRONG_FILE }
        return when (ModelMove.copyIn(src, target(filesDir, s), s.sha256, free, go = go)) {
            is ModelMove.Result.Moved -> { src.delete(); Moved.OK }
            is ModelMove.Result.Mismatch -> { src.delete(); Moved.WRONG_FILE }
            ModelMove.Result.NoRoom -> Moved.NO_ROOM
            is ModelMove.Result.Failed -> Moved.FAILED
        }
    }

    fun withInstalled(f: WikiFiles, s: WikiSet): WikiFiles =
        f.copy(sets = f.sets + (s.id to WikiFiles.Installed(s.file, s.bytes, s.sha256)), downloads = f.downloads - s.id)

    /**
     * L2 (review-wikipedia): after a release changes a pin, the old copy (up to 3.6 GB) can't be opened (installed() wants
     * the new pin), so it is deleted, with its entry, rather than left unseen: any file in filesDir/wiki that no set names
     * (nor its copy under way), and any entry that isn't a set's pinned file. The page then offers the new copy.
     */
    fun tidy(filesDir: File, f: WikiFiles): WikiFiles {
        val names = WikiSet.entries.flatMap { s -> target(filesDir, s).let { listOf(it.name, ModelMove.part(it).name) } }.toSet()
        File(filesDir, DIR).listFiles()?.filter { it.isFile && it.name !in names }?.forEach { it.delete() }
        val kept = f.sets.filter { (id, i) -> WikiSet.byId(id)?.file == i.file }
        return if (kept.size == f.sets.size) f else f.copy(sets = kept)
    }

    /** Deletes the copy (and any copy cut short) and returns [f] without its entry or download. */
    fun delete(filesDir: File, f: WikiFiles, s: WikiSet): WikiFiles {
        val t = target(filesDir, s)
        t.delete()
        ModelMove.part(t).delete()
        return f.copy(sets = f.sets - s.id, downloads = f.downloads - s.id)
    }
}
