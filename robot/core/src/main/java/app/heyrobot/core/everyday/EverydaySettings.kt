// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.everyday

import org.json.JSONObject

// Pure. The everyday set's switches (docs/plans/everyday-set-plan.md §2.4, D21): everyday_settings.json v1 in files/robot/,
// beside files_settings.json. The main app writes it (sys EverydaySettingsStore, under its lock); :voice reads it and is
// told by Cmd.EVERYDAY. Never in settings.json (its schema stays 13) and never one of the backup's 8 Settings files: a
// ninth would make an older reader refuse the whole Settings category, so it is the EVERYDAY category's one entry.
//
//   {"v":1,"music":true,"listen":true,"voices":true,"askRobot":true,"online":true,"onlineDays":30,
//    "musicApp":{"package":"org.example.music","sha256":"<64 hex>","label":"Music"}}
//
// Every key of the set is here from E0 on, so E1 to E7 read and write their own without a new version. A key this
// version doesn't know is kept and written back as it was (a later job's row). A known key with a wrong type or a value
// out of range makes the file Damaged (the defaults apply; a save writes a new file). A newer version is TooNew: its
// defaults apply and it is never written over.

/** Your music app (§3.4, D5): picked once by a tap; its package and its signer's SHA-256 pinned, its label for the screen. */
data class MusicApp(val pkg: String, val sha256: String, val label: String) {
    init {
        require(valid(pkg, sha256, label))
    }

    companion object {
        const val MAX_LABEL = 100
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun valid(pkg: String, sha256: String, label: String): Boolean =
            pkg.length <= 255 && PACKAGE.matches(pkg) && SHA256.matches(sha256) && label.length in 1..MAX_LABEL && label.none { it < ' ' || it == '\u007f' }
    }
}

/**
 * Your maps app (car-media-maps, Settings › Actions › Maps): picked from the apps that open map links, its package and
 * its signer's SHA-256 pinned as [MusicApp] is; none is Automatic (Organic Maps when installed, else the phone's default).
 */
data class MapsApp(val pkg: String, val sha256: String, val label: String) {
    init {
        require(MusicApp.valid(pkg, sha256, label))
    }
}

/** The set's values, at the plan's defaults (§2.4; Q1 to Q3's defaults). */
data class EverydaySettings(
    /** Music by voice (§3): "pause the music", "play Radiohead" outside car mode. Settings › Actions › Music. */
    val music: Boolean = true,
    /** Your music app (Q1): none until you pick one the first time you play something by name. */
    val musicApp: MusicApp? = null,
    /** Your podcast app (Settings › Actions › Podcasts): car mode asks "Music or podcasts?" once both are set. */
    val podcastApp: MusicApp? = null,
    /** Listening for other apps (§6.2, Q2): their mic buttons get Robot's offline listening. Settings › Voice. */
    val listen: Boolean = true,
    /** Robot's voices for other apps (§6.3): the engine shows in Android's list; it speaks once you pick it there. Settings › Voice. */
    val voices: Boolean = true,
    /** Ask Robot in other apps (§7): the text-selection menu and the share sheet. Off disables the component. Settings › Actions. */
    val askRobot: Boolean = true,
    /** Keep a record of what went online (§5, Q3). Settings › Safety & privacy › What went online. */
    val online: Boolean = true,
    /** How many days the record keeps (Q3: 30). */
    val onlineDays: Int = DEFAULT_DAYS,
    /** Your maps app: null is Automatic. Every route Robot opens goes there. */
    val mapsApp: MapsApp? = null,
    /** 1.3 point and read: a reading sends the photo's four corner tiles, not the whole of it (core read ReadImages). Off
     *  until P0's numbers say tiles read small print better; written only when on. Settings › Actions › Point and read. */
    val readTiles: Boolean = false,
) {
    init {
        require(onlineDays in MIN_DAYS..MAX_DAYS)
    }

    companion object {
        const val DEFAULT_DAYS = 30
        const val MIN_DAYS = 1
        const val MAX_DAYS = 90
    }
}

/** What everyday_settings.json gave. */
sealed class EverydaySettingsFile {
    /** [extra]: the keys this version doesn't know, kept to write back. */
    data class Ok(val read: EverydaySettings, val extra: Map<String, Any?> = emptyMap()) : EverydaySettingsFile()
    /** A newer Robot's file: the defaults apply, and it is never written over. */
    data class TooNew(val v: Int) : EverydaySettingsFile()
    data object Damaged : EverydaySettingsFile()

    /** What applies: the file's, or the defaults when it can't be read. */
    val settings: EverydaySettings get() = (this as? Ok)?.read ?: EverydaySettings()
}

object EverydaySettingsCodec {
    const val FILE = "everyday_settings.json"
    const val VERSION = 1
    private val FLAGS = listOf("music", "listen", "voices", "askRobot", "online", "readTiles")
    private val KNOWN = setOf("v", "musicApp", "podcastApp", "mapsApp", "onlineDays") + FLAGS

    fun encode(s: EverydaySettings, extra: Map<String, Any?> = emptyMap()): String {
        val o = JSONObject()
        for ((k, v) in extra) if (k !in KNOWN) o.put(k, v ?: JSONObject.NULL)
        o.put("v", VERSION)
            .put("music", s.music)
            .put("listen", s.listen)
            .put("voices", s.voices)
            .put("askRobot", s.askRobot)
            .put("online", s.online)
            .put("onlineDays", s.onlineDays)
        s.musicApp?.let { a -> o.put("musicApp", JSONObject().put("package", a.pkg).put("sha256", a.sha256).put("label", a.label)) }
        s.podcastApp?.let { a -> o.put("podcastApp", JSONObject().put("package", a.pkg).put("sha256", a.sha256).put("label", a.label)) }
        if (s.readTiles) o.put("readTiles", true)
        s.mapsApp?.let { a -> o.put("mapsApp", JSONObject().put("package", a.pkg).put("sha256", a.sha256).put("label", a.label)) }
        return o.toString()
    }

    /** None is the defaults. */
    fun decode(json: String?): EverydaySettingsFile {
        json ?: return EverydaySettingsFile.Ok(EverydaySettings())
        val o = try { JSONObject(json) } catch (_: Exception) { return EverydaySettingsFile.Damaged }
        val v = o.opt("v")
        if (v !is Int && v !is Long) return EverydaySettingsFile.Damaged
        val version = (v as Number).toLong()
        if (version > VERSION) return EverydaySettingsFile.TooNew(version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (version < 1) return EverydaySettingsFile.Damaged
        val d = EverydaySettings()
        fun flag(k: String, default: Boolean): Boolean? = if (!o.has(k)) default else o.opt(k) as? Boolean
        val days = if (!o.has("onlineDays")) d.onlineDays else (o.opt("onlineDays") as? Number)
            ?.takeIf { (it is Int || it is Long) && it.toLong() in EverydaySettings.MIN_DAYS..EverydaySettings.MAX_DAYS }?.toInt()
        val app = if (!o.has("musicApp")) Present(null) else musicApp(o.opt("musicApp"))
        val podcast = if (!o.has("podcastApp")) Present(null) else musicApp(o.opt("podcastApp"))
        val maps = if (!o.has("mapsApp")) Present(null) else musicApp(o.opt("mapsApp"))
        val s = EverydaySettings(
            music = flag("music", d.music) ?: return EverydaySettingsFile.Damaged,
            musicApp = (app ?: return EverydaySettingsFile.Damaged).value,
            podcastApp = (podcast ?: return EverydaySettingsFile.Damaged).value,
            listen = flag("listen", d.listen) ?: return EverydaySettingsFile.Damaged,
            voices = flag("voices", d.voices) ?: return EverydaySettingsFile.Damaged,
            askRobot = flag("askRobot", d.askRobot) ?: return EverydaySettingsFile.Damaged,
            online = flag("online", d.online) ?: return EverydaySettingsFile.Damaged,
            onlineDays = days ?: return EverydaySettingsFile.Damaged,
            mapsApp = (maps ?: return EverydaySettingsFile.Damaged).value?.let { MapsApp(it.pkg, it.sha256, it.label) },
            readTiles = flag("readTiles", d.readTiles) ?: return EverydaySettingsFile.Damaged,
        )
        val extra = o.keys().asSequence().filter { it !in KNOWN }.associateWith { o.opt(it) }
        return EverydaySettingsFile.Ok(s, extra)
    }

    private class Present(val value: MusicApp?)

    /** "musicApp" (and "mapsApp", the same shape): exactly package, sha256 and label, each valid; JSON null is none. Null: it isn't one (Damaged). */
    private fun musicApp(x: Any?): Present? {
        if (x == JSONObject.NULL) return Present(null)
        val a = x as? JSONObject ?: return null
        if (a.keys().asSequence().toSet() != setOf("package", "sha256", "label")) return null
        val pkg = a.opt("package") as? String ?: return null
        val sha = a.opt("sha256") as? String ?: return null
        val label = a.opt("label") as? String ?: return null
        return if (MusicApp.valid(pkg, sha, label)) Present(MusicApp(pkg, sha, label)) else null
    }
}

/** What a save makes of everyday_settings.json, decided over the file as it is then (the store reads it under its lock). */
object EverydaySettingsWrite {
    /**
     * [change] applied to the file as it is now ([raw]), so a field another writer just wrote (a restore's finish) stays
     * unless [change] changes it. Null: nothing to write (a newer Robot's file is never written over).
     */
    fun update(raw: String?, change: (EverydaySettings) -> EverydaySettings): String? = when (val now = EverydaySettingsCodec.decode(raw)) {
        is EverydaySettingsFile.TooNew -> null
        is EverydaySettingsFile.Ok -> EverydaySettingsCodec.encode(change(now.read), now.extra)
        EverydaySettingsFile.Damaged -> EverydaySettingsCodec.encode(change(EverydaySettings()))
    }
}
