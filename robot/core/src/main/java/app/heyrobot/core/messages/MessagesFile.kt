// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Pure (org.json). Settings › Messages in its own small file, files/robot/messages.json, as Settings › Display has
// look.json: settings.json stays at its schema (13). The main app writes it; the main process's listener and bridge read
// it at once (the same process), and the voice process reads it again each time the panel opens. It holds switches,
// People ids and the signing certificates kept for the apps you turned on (MessageSigners): never a message, a name or an
// address. Every write is one change under messages.lock, read from the file and written whole ([update]).

/**
 * Everything Settings › Messages and People's "Send my replies to … without a card" choose. [noCard]: the People ids
 * whose 1:1 replies you dictate go after a 3-second "stop" instead of a card (unlocked phone only). [replyLang]: the
 * language a person's replies go in when the card's "Send in French" was ticked with "Always for …" (by People id).
 * [pins]: by package, what was kept of each installed package of an app when you turned it on (its signers' digests and
 * installer, MessageSigners); [firstUse]: packages of an app that was on before the pins, which are pinned at their
 * first notification (trust on first use; only after an upgrade from a version-1 file). [announcePeople] (0.8): the People ids
 * whose new messages are announced in your headphones or the car when "Announce from" is "People you choose".
 */
data class MessagesFile(
    val settings: MessageSettings = MessageSettings(),
    val noCard: Set<String> = emptySet(),
    val replyLang: Map<String, Lang> = emptyMap(),
    val pins: Map<String, AppPin> = emptyMap(),
    val firstUse: Set<String> = emptySet(),
    val announcePeople: Set<String> = emptySet(),
) {
    /**
     * [app] turned on or off in Settings › Messages. On: each of its packages installed now is pinned to [pinsNow] (what
     * Android says of it now; a package not installed gets none, so one installed later is read only after you turn the
     * app on again). Off: its pins go, so turning it on again trusts what is installed then.
     */
    fun withApp(app: MessageApp, on: Boolean, pinsNow: Map<String, AppPin> = emptyMap()): MessagesFile {
        val mine = app.packages.toSet()
        val kept = pins - mine
        return copy(
            settings = settings.copy(allowlist = settings.allowlist.with(app, on)),
            pins = if (on) kept + pinsNow.filterKeys { it in mine } else kept,
            firstUse = firstUse - mine,
        )
    }

    /** The setup screen's "Turn on": reading on, [apps] ticked, each pinned as [withApp] does. */
    fun turnedOn(apps: Set<MessageApp>, pinsNow: Map<String, AppPin>): MessagesFile {
        var f = copy(settings = settings.copy(on = true))
        for (app in MessageApp.entries) if (app in apps || app in f.settings.allowlist.apps) f = f.withApp(app, app in apps, pinsNow)
        return f
    }

    /** [pkg] pinned at its first notification ([MessageSigners] PIN_NOW): kept, and no longer waiting for a first use. */
    fun pinnedAtFirstUse(pkg: String, pin: AppPin): MessagesFile = copy(pins = pins + (pkg to pin), firstUse = firstUse - pkg)

    /** [firstUse] without the packages [installed] says aren't on the phone (the main process, when its listener connects). */
    fun firstUseInstalled(installed: (String) -> Boolean): MessagesFile = copy(firstUse = firstUse.filter(installed).toSet())

    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        val s = settings
        put("on", s.on)
        put("apps", JSONArray(s.allowlist.apps.map { it.name }.sorted()))
        put("groups", s.allowlist.groups)
        put("keep", s.keep.name)
        put("replyRoute", s.replyRoute.name)
        put("replyCard", s.replyCard)
        put("voiceYesSends", s.voiceYesSends)
        put("afterReading", s.afterReading.name)
        put("translate", s.translate)
        put("offerTheirLanguage", s.offerTheirLanguage)
        put("locked", s.locked.name)
        put("helpersNoCard", s.helpersNoCard)
        put("announce", s.announce)
        put("noCard", JSONArray(noCard.sorted()))
        put("replyLang", JSONObject().apply { replyLang.toSortedMap().forEach { (id, l) -> put(id, l.name) } })
        put("pins", JSONObject().apply {
            pins.toSortedMap().forEach { (pkg, p) ->
                put(pkg, JSONObject().put("sha256", JSONArray(p.digests.sorted())).apply { p.installer?.let { put("installer", it) } })
            }
        })
        put("firstUse", JSONArray(firstUse.sorted()))
        put("announcePeople", JSONArray(announcePeople.sorted()))
    }.toString()

    companion object {
        const val FILE = "messages.json"
        /** The lock every write of [FILE] takes, beside it. */
        const val LOCK = "messages.lock"
        /** 2: the apps' signing certificates ([pins]); a version-1 file's apps that were on are pinned at first use. */
        const val VERSION = 2
        /** People ids kept at most (a person's switch each). */
        const val MAX_PEOPLE = 500

        /**
         * One change to [file]: under [LOCK] (this process's threads and other processes alike), [change] applied to what
         * the file holds now, written whole (a temporary file, fsync, rename) when it changed. The file as it is after.
         * Settings › Messages, People's "without a card" and the bridge's "Always for …" all write this way, so none of
         * them loses another's change.
         */
        fun update(file: File, change: (MessagesFile) -> MessagesFile): MessagesFile = withFileLock(File(file.parentFile, LOCK)) {
            val now = fromJson(readOrNull(file))
            val next = change(now)
            if (next != now) writeAtomic(file, next.toJson())
            next
        }

        /** Missing, empty or damaged text gives the defaults; an unknown key or value, or a wrong type, is that field's default. */
        fun fromJson(text: String?): MessagesFile {
            val o = try { JSONObject(text ?: return MessagesFile()) } catch (_: Exception) { return MessagesFile() }
            val d = MessageSettings()
            fun flag(k: String, def: Boolean) = (o.opt(k) as? Boolean) ?: def
            fun <E : Enum<E>> pick(k: String, entries: List<E>, def: E): E = (o.opt(k) as? String)?.let { v -> entries.firstOrNull { it.name == v } } ?: def
            fun strings(k: String): List<String> = (o.opt(k) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
            val apps = strings("apps").mapNotNull { n -> MessageApp.entries.firstOrNull { it.name == n } }.toSet()
            val settings = MessageSettings(
                on = flag("on", d.on),
                allowlist = MessageAllowlist(apps, flag("groups", d.allowlist.groups)),
                keep = pick("keep", KeepMessages.entries, d.keep),
                replyRoute = pick("replyRoute", ReplyRoute.entries, d.replyRoute),
                replyCard = flag("replyCard", d.replyCard),
                voiceYesSends = flag("voiceYesSends", d.voiceYesSends),
                afterReading = pick("afterReading", AfterReading.entries, d.afterReading),
                translate = flag("translate", d.translate),
                offerTheirLanguage = flag("offerTheirLanguage", d.offerTheirLanguage),
                locked = pick("locked", MessagesLocked.entries, d.locked),
                helpersNoCard = flag("helpersNoCard", d.helpersNoCard),
                announce = flag("announce", d.announce),
            )
            val langs = (o.opt("replyLang") as? JSONObject)?.let { m ->
                m.keys().asSequence().mapNotNull { id -> (m.opt(id) as? String)?.let { v -> Lang.entries.firstOrNull { it.name == v } }?.let { id to it } }.toMap()
            }.orEmpty()
            val ours = { pkg: String -> MessageApp.of(pkg) != null }
            val pins = (o.opt("pins") as? JSONObject)?.let { m ->
                m.keys().asSequence().filter(ours).mapNotNull { pkg ->
                    val p = m.opt(pkg) as? JSONObject ?: return@mapNotNull null
                    val d = (p.opt("sha256") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                    MessageSigners.pinOf(SignerNow(d.toSet(), p.opt("installer") as? String))?.let { pkg to it }
                }.toMap()
            }.orEmpty()
            // Before the pins (version 1, or no version): each package of an app that was on is trusted at its first notification.
            val v = (o.opt("v") as? Int) ?: 1
            val firstUse = if (v < 2) settings.allowlist.apps.flatMap { it.packages }.toSet() - pins.keys else strings("firstUse").filter(ours).toSet() - pins.keys
            return MessagesFile(settings, strings("noCard").filter { it.isNotBlank() }.take(MAX_PEOPLE).toSet(), langs.entries.take(MAX_PEOPLE).associate { it.toPair() },
                pins, firstUse, strings("announcePeople").filter { it.isNotBlank() }.take(MAX_PEOPLE).toSet())
        }
    }
}
