package app.heyrobot.core.messages

import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). Settings › Messages in its own small file, files/robot/messages.json, as Settings › Display has
// look.json: settings.json stays at its schema (13). The main app writes it; the main process's listener and bridge read
// it at once (the same process), and the voice process reads it again each time the panel opens. It holds switches and
// People ids only: never a message, a name or an address.

/**
 * Everything Settings › Messages and People's "Send my replies to … without a card" choose. [noCard]: the People ids
 * whose 1:1 replies you dictate go after a 3-second "stop" instead of a card (unlocked phone only). [replyLang]: the
 * language a person's replies go in when the card's "Send in French" was ticked with "Always for …" (by People id).
 */
data class MessagesFile(
    val settings: MessageSettings = MessageSettings(),
    val noCard: Set<String> = emptySet(),
    val replyLang: Map<String, Lang> = emptyMap(),
) {
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
    }.toString()

    companion object {
        const val FILE = "messages.json"
        const val VERSION = 1
        /** People ids kept at most (a person's switch each). */
        const val MAX_PEOPLE = 500

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
            return MessagesFile(settings, strings("noCard").filter { it.isNotBlank() }.take(MAX_PEOPLE).toSet(), langs.entries.take(MAX_PEOPLE).associate { it.toPair() })
        }
    }
}
