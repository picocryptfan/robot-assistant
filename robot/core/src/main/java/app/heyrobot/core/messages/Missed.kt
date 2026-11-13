// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.BriefText
import app.heyrobot.core.i18n.MessageStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import org.json.JSONArray
import org.json.JSONObject

// Pure. 1.3, "What did I miss?" (docs/plans/what-did-i-miss-plan.md): one sentence for what came in from the apps you
// picked since you last asked. Its settings are missed.json's (off, no app); what it holds is RAM only, in the main
// process: per notification the app, the kind of sender, a title cut as the brief cuts and when it came. Never a
// message's text. Locked (or someone else using the phone), the answer is counts by app only and the mark stays.

/**
 * missed.json: "What did I miss?" [on] (off until you turn it on) for the packages you picked one by one; [heard], the
 * picked ones Android has delivered from (kept across restarts, so a quiet chat app is "Nothing new").
 */
data class MissedSettings(val on: Boolean = false, val apps: List<String> = emptyList(), val heard: List<String> = emptyList()) {
    /** [pkg]'s notifications may be held now: on, and picked. */
    fun picked(pkg: String?): Boolean = on && pkg != null && pkg in apps

    fun with(pkg: String, pick: Boolean): MissedSettings = copy(apps = if (pick) (apps + pkg).distinct() else apps - pkg, heard = if (pick) heard else heard - pkg)

    companion object {
        const val FILE = "missed.json"
        const val VERSION = 1

        /** A missing or damaged file: the defaults (off, no app). */
        fun parse(text: String?): MissedSettings = try {
            if (text.isNullOrBlank()) MissedSettings() else JSONObject(text).let { o ->
                val a = o.optJSONArray("apps") ?: JSONArray()
                val h = o.optJSONArray("heard") ?: JSONArray()
                val apps = (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.distinct()
                MissedSettings(o.optBoolean("on", false), apps, (0 until h.length()).map { h.optString(it) }.filter { it in apps }.distinct())
            }
        } catch (_: Exception) {
            MissedSettings()
        }

        fun write(s: MissedSettings): String =
            JSONObject().put("version", VERSION).put("on", s.on).put("apps", JSONArray(s.apps))
                .put("heard", JSONArray(s.heard.filter { it in s.apps }.distinct())).toString(2) + "\n"
    }
}

/** Who a notification is from, as far as its kind tells: a person (a 1:1 chat), a group chat, or the app itself. */
enum class SenderKind { PERSON, GROUP, APP;
    companion object {
        /**
         * As Android's MessagingStyle.isGroupConversation decides: its group flag (written on every MessagingStyle since
         * API 28) or, for an app targeting API 27 or older, a conversation title. A newer app that sets a title and says
         * "not a group" is one person's chat with a title.
         */
        fun of(messages: Boolean, group: Boolean, title: Boolean, targetSdk: Int): SenderKind = when {
            group -> GROUP
            messages && title && targetSdk < 28 -> GROUP
            messages -> PERSON
            else -> APP
        }
    }
}

/** One notification as held: [label] the app's name, [title] its title (a sender, a group, a subject), [at] when it came. */
data class MissedNotice(val pkg: String, val label: String, val kind: SenderKind, val title: String, val at: Long)

/** The notifications held for "What did I miss?" (RAM only) and when you last looked. Thread-safe. */
class MissedNotices {
    private val held = LinkedHashMap<String, MissedNotice>()
    /** Picked apps that delivered anything since the listener connected (an app that hasn't may be filtered out by Android). */
    private val heard = HashSet<String>()

    /** When Robot last answered on an unlocked phone (0: since the listener connected). */
    @Volatile var lookedAt: Long = 0L
        private set

    val size: Int @Synchronized get() = held.size

    /** Holds [n] under [key] when [s] picks its app; anything else is dropped (and what was held under [key] goes). */
    @Synchronized fun posted(key: String, n: MissedNotice, s: MissedSettings): Boolean {
        if (!s.picked(n.pkg)) {
            held.remove(key)
            return false
        }
        heard += n.pkg
        held[key] = n.copy(label = BriefText.clean(n.label, LABEL), title = BriefText.clean(n.title, TITLE))
        return true
    }

    /** Android delivered a notification from [pkg] (alerting or not): Robot can hear that app. */
    @Synchronized fun heard(pkg: String, s: MissedSettings) {
        if (s.picked(pkg)) heard += pkg
    }

    /** Picked apps nothing has come from (since the listener connected, or ever: [MissedSettings.heard]), in [s]'s order. */
    @Synchronized fun unheard(s: MissedSettings): List<String> = if (s.on) s.apps.filter { it !in heard && it !in s.heard } else emptyList()

    /** Its notification left the shade (swiped, read in its app, cancelled). */
    @Synchronized fun removed(key: String) {
        held.remove(key)
    }

    /** The listener (re)connected and the shade holds [keys]: anything else went while it was unbound. [lookedAt] stays. */
    @Synchronized fun keep(keys: Set<String>) {
        held.keys.retainAll(keys)
    }

    /** The settings changed: off, everything goes; an app no longer picked, its notifications go. */
    @Synchronized fun settings(s: MissedSettings) {
        if (!s.on) clear() else {
            held.values.removeAll { !s.picked(it.pkg) }
            heard.retainAll(s.apps.toSet())
        }
    }

    @Synchronized fun clear() {
        held.clear()
        heard.clear()
        lookedAt = 0L
    }

    /** What came since you last looked, from apps [s] picks. */
    @Synchronized fun since(s: MissedSettings): List<MissedNotice> = held.values.filter { s.picked(it.pkg) && it.at > lookedAt }

    @Synchronized fun looked(now: Long) {
        lookedAt = maxOf(lookedAt, now)
    }

    companion object {
        /** An app's name, as said. */
        const val LABEL = 30
        /** A title, as the brief cuts a sender's name or an event. */
        const val TITLE = 40
    }
}

/** One app's line: [n] notifications, the newest's kind and title ("" when counts only), when the newest came. */
data class AppMissed(val app: String, val n: Int, val kind: SenderKind = SenderKind.APP, val title: String = "", val at: Long = 0L)

/** What the main process answers: the switch, access, whether it was counts only, and the apps, newest first. */
data class MissedAnswer(val on: Boolean, val apps: Int, val access: Boolean, val countsOnly: Boolean, val list: List<AppMissed>,
    /** Picked apps (by name) nothing has come from yet: the answer says so rather than "nothing new". */
    val unheard: List<String> = emptyList())

object MissedSummary {
    /** Apps named at most (the rest are "in other apps"). */
    const val MAX_APPS = 4
    /** "The latest in …" lines at most. */
    const val MAX_LATEST = 2

    /** [notices] by app, newest app first; [countsOnly]: no kind, no title. Only apps [s] picks. */
    fun of(notices: List<MissedNotice>, s: MissedSettings, countsOnly: Boolean): List<AppMissed> =
        notices.filter { s.picked(it.pkg) }.groupBy { it.pkg }.map { (pkg, ns) ->
            val newest = ns.maxBy { it.at }
            val name = newest.label.ifBlank { pkg.substringAfterLast('.') }
            if (countsOnly) AppMissed(name, ns.size, at = newest.at) else AppMissed(name, ns.size, newest.kind, newest.title, newest.at)
        }.sortedByDescending { it.at }

    /**
     * [MAIN] The answer to "What did I miss?": counts only when [locked] (the mark stays); unlocked, kinds and titles,
     * and the mark moves to [now].
     */
    fun answer(held: MissedNotices, s: MissedSettings, access: Boolean, locked: Boolean, now: Long,
        label: (String) -> String = { it.substringAfterLast('.') }): MissedAnswer {
        val list = if (s.on && access) of(held.since(s), s, countsOnly = locked) else emptyList()
        // A blank label: the app isn't installed any more, so nothing can come from it (left out, not named by its package).
        val unheard = if (s.on && access) held.unheard(s).map { p -> BriefText.clean(label(p), MissedNotices.LABEL) }.filter { it.isNotEmpty() } else emptyList()
        if (s.on && access && !locked) held.looked(now)
        return MissedAnswer(s.on, s.apps.size, access, locked, list, unheard)
    }

    /** What the panel says. */
    fun reply(a: MissedAnswer): Phrase = when {
        !a.on -> MessageStrings.missedOff("page" to page)
        a.apps == 0 -> MessageStrings.missedNoApps("page" to page)
        !a.access -> MessageReader.noAccessText()
        a.unheard.isEmpty() -> text(a.list, a.countsOnly)
        // A picked app Android never delivered from: most likely not a conversation app while only conversations are
        // allowed (the manifest's default filter), so "nothing new" could be wrong. Say so, and how to allow it.
        else -> Phrase.Join(listOfNotNull(text(a.list, a.countsOnly).takeIf { a.list.any { it.n > 0 } },
            MessageStrings.missedUnheard("names" to Phrase.ListOf(a.unheard.take(MAX_APPS).map(Phrase::Plain)), "page" to page)))
    }

    /**
     * What History keeps of [a]'s answer when it names something another app chose (a sender, a group, a title): the
     * counts and app names only, as a message summary keeps its placeholder; null: the answer holds nothing from another app.
     */
    fun kept(a: MissedAnswer): Phrase? {
        val apps = a.list.filter { it.n > 0 }
        if (!a.on || a.apps == 0 || !a.access || a.countsOnly || apps.none { it.title.isNotBlank() }) return null
        return text(apps.map { it.copy(title = "") }, countsOnly = false)
    }

    /** "Since you last looked, 5 notifications: 3 in Signal and 2 in Mail. The latest in Signal is from Alex." */
    fun text(list: List<AppMissed>, countsOnly: Boolean): Phrase {
        val apps = list.filter { it.n > 0 }
        if (apps.isEmpty()) return MessageStrings.missedNone()
        val total = apps.sumOf { it.n }
        val named = apps.take(MAX_APPS).map { MessageStrings.missedIn("n" to it.n, "appName" to it.app) }
        val rest = apps.drop(MAX_APPS).sumOf { it.n }
        val items = if (rest > 0) named + MessageStrings.missedElsewhere("n" to rest) else named
        val parts = mutableListOf(MessageStrings.missedSince(total, "list" to Phrase.ListOf(items)))
        if (countsOnly) {
            parts += MessageStrings.missedUnlock()
        } else {
            for (a in apps.filter { it.title.isNotBlank() }.take(MAX_LATEST)) parts += when (a.kind) {
                SenderKind.PERSON -> MessageStrings.missedLatestPerson("appName" to a.app, "who" to a.title)
                SenderKind.GROUP -> MessageStrings.missedLatestGroup("appName" to a.app, "group" to a.title)
                SenderKind.APP -> MessageStrings.missedLatestApp("appName" to a.app, "title" to a.title)
            }
        }
        return Phrase.Join(parts)
    }

    private val page get() = UiStrings.inSettings(UiStrings.messages)

    fun toJson(a: MissedAnswer): String = JSONObject().put("on", a.on).put("apps", a.apps).put("access", a.access).put("countsOnly", a.countsOnly)
        .put("unheard", JSONArray().apply { a.unheard.forEach { put(it) } })
        .put("list", JSONArray().apply {
            a.list.forEach { put(JSONObject().put("app", it.app).put("n", it.n).put("kind", it.kind.name).put("title", if (a.countsOnly) "" else it.title).put("at", it.at)) }
        }).toString()

    fun fromJson(text: String?): MissedAnswer? = try {
        JSONObject(text ?: return null).let { o ->
            val countsOnly = o.optBoolean("countsOnly", true)
            val l = o.optJSONArray("list") ?: JSONArray()
            MissedAnswer(o.optBoolean("on"), o.optInt("apps"), o.optBoolean("access"), countsOnly, (0 until l.length()).mapNotNull { i ->
                l.optJSONObject(i)?.let { e ->
                    val kind = SenderKind.entries.firstOrNull { it.name == e.optString("kind") } ?: SenderKind.APP
                    AppMissed(BriefText.clean(e.optString("app"), MissedNotices.LABEL), e.optInt("n").coerceAtLeast(0),
                        if (countsOnly) SenderKind.APP else kind, if (countsOnly) "" else BriefText.clean(e.optString("title"), MissedNotices.TITLE), e.optLong("at"))
                }
            }, o.optJSONArray("unheard")?.let { u -> (0 until u.length()).map { BriefText.clean(u.optString(it), MissedNotices.LABEL) }.filter { it.isNotEmpty() } }.orEmpty())
        }
    } catch (_: Exception) {
        null
    }
}
