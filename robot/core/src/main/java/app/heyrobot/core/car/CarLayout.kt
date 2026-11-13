// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.messages.ScamCheck
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessagesStatus
import app.heyrobot.core.output.enumOr
import org.json.JSONArray
import org.json.JSONObject

// Pure. Car mode's layout (docs/plans/car-layout-design.md): a large panel on half the screen, an information panel on a
// quarter, and on the last quarter the fixed buttons, one of them yours to choose. Set in Settings › Car mode, kept in
// car.json under "layout"; a value this build doesn't know reads as the default.

/** The large panel. [MAP]: your maps app beside the car screen in split screen, once you tap to open it. */
enum class CarLarge { MAP, MEDIA, MESSAGES }

/** The information panel: the bottom screen's slot above the buttons. [WIDGET]: an app's widget you picked (car-screens-plan.md). */
enum class CarInfo { MEDIA, DIRECTIONS, LATEST_MESSAGE, WIDGET }

/**
 * The widget you picked for the bottom screen: its app pinned with its signer, as your music app is, the widget's class
 * in that app ([provider]), and the id Android gave it when you allowed Robot to add it ([id]).
 */
data class CarWidget(val app: MusicApp, val provider: String, val id: Int) {
    companion object {
        private val CLASS = Regex("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*")

        /** This host's [ids] to delete: all but [keep] (the saved widget's, a pick still waiting on its answer). */
        fun leftovers(ids: IntArray, keep: Set<Int>): List<Int> = ids.filter { it !in keep }

        fun valid(app: MusicApp, provider: String, id: Int): Boolean =
            MusicApp.valid(app.pkg, app.sha256, app.label) && id > 0 && provider.length <= 255 && CLASS.matches(provider)
    }
}

/** Robot's own actions for the fourth button. */
enum class CarAction { MUSIC_APP, MAPS, NAVIGATE_HOME }

/** The fourth button: one of Robot's actions, or an app pinned with its signer, as your music app is. */
sealed class CarFourth {
    data class Action(val action: CarAction) : CarFourth()
    data class App(val app: MusicApp) : CarFourth()
}

data class CarLayout(
    val large: CarLarge = CarLarge.MAP,
    val info: CarInfo = CarInfo.MEDIA,
    val fourth: CarFourth = CarFourth.Action(CarAction.MUSIC_APP),
    /** The apps you keep for the car (Settings › Car mode › Apps), each pinned with its signer, at most [MAX_APPS]. */
    val apps: List<MusicApp> = emptyList(),
    /** The bottom screen's widget when [info] is [CarInfo.WIDGET]; [info] reads as the media player without one. */
    val widget: CarWidget? = null,
) {
    /**
     * What the information panel shows. A panel never shows twice. [beside]: the window is in split screen, the other app
     * holds the large half, and what the large panel held moves here, so media or messages you chose stay on the screen.
     */
    fun infoNow(beside: Boolean): CarInfo = when {
        info == CarInfo.WIDGET -> if (widget == null) CarInfo.MEDIA else CarInfo.WIDGET
        beside && large == CarLarge.MEDIA -> CarInfo.MEDIA
        beside && large == CarLarge.MESSAGES -> CarInfo.LATEST_MESSAGE
        large == CarLarge.MEDIA && info == CarInfo.MEDIA -> CarInfo.DIRECTIONS
        large == CarLarge.MESSAGES && info == CarInfo.LATEST_MESSAGE -> CarInfo.MEDIA
        else -> info
    }

    /**
     * What the bottom screen's slot shows now. A widget never shows over the lock screen ([locked]) or while Phone lock
     * closes its app ([phoneLockCloses]): it would show that app's own data and take taps for it. [swapped]: the bottom
     * swap has put the media player in the widget's place. In each case the slot shows the media player, or directions
     * when the large panel already shows media.
     */
    fun slotNow(beside: Boolean, locked: Boolean, phoneLockCloses: Boolean, swapped: Boolean = false): CarInfo {
        val info = infoNow(beside)
        if (info != CarInfo.WIDGET) return info
        if (!locked && !phoneLockCloses && !swapped) return CarInfo.WIDGET
        return if (largeNow(beside) == CarLarge.MEDIA) CarInfo.DIRECTIONS else CarInfo.MEDIA
    }

    /** Whether the bottom screen has a swap: a widget is set and may show now. */
    fun swapsBottom(beside: Boolean, locked: Boolean, phoneLockCloses: Boolean): Boolean =
        infoNow(beside) == CarInfo.WIDGET && !locked && !phoneLockCloses

    /** The large panel now: none in split screen, where the other app is the large half. */
    fun largeNow(beside: Boolean): CarLarge? = large.takeUnless { beside }

    /** Whether the screen shows messages now: only then does it ask for them. */
    fun showsMessages(beside: Boolean): Boolean = largeNow(beside) == CarLarge.MESSAGES || infoNow(beside) == CarInfo.LATEST_MESSAGE

    fun toJson(): JSONObject = JSONObject().apply {
        put("large", large.name)
        put("info", info.name)
        when (fourth) {
            is CarFourth.Action -> put("fourth", fourth.action.name)
            is CarFourth.App -> put("fourthApp", app(fourth.app))
        }
        if (apps.isNotEmpty()) put("apps", JSONArray().apply { apps.forEach { put(app(it)) } })
        widget?.let { put("widget", app(it.app).put("provider", it.provider).put("id", it.id)) }
    }

    private fun app(a: MusicApp) = JSONObject().put("package", a.pkg).put("sha256", a.sha256).put("label", a.label)

    companion object {
        const val MAX_APPS = 8

        private fun appOf(a: JSONObject?): MusicApp? {
            val pkg = a?.optString("package") ?: return null
            val sha = a.optString("sha256")
            val label = a.optString("label")
            return if (MusicApp.valid(pkg, sha, label)) MusicApp(pkg, sha, label) else null
        }

        private fun widgetOf(w: JSONObject?): CarWidget? {
            val app = appOf(w) ?: return null
            val provider = w?.optString("provider").orEmpty()
            val id = w?.optInt("id", 0) ?: 0
            return if (CarWidget.valid(app, provider, id)) CarWidget(app, provider, id) else null
        }

        fun fromJson(o: JSONObject?): CarLayout {
            val d = CarLayout()
            if (o == null) return d
            val app = appOf(o.optJSONObject("fourthApp"))
            val kept = o.optJSONArray("apps")?.let { list -> (0 until list.length()).mapNotNull { appOf(list.optJSONObject(it)) } }.orEmpty()
            val action = CarAction.entries.firstOrNull { it.name == o.optString("fourth") }
            val widget = widgetOf(o.optJSONObject("widget"))
            val info = o.enumOr("info", d.info).takeUnless { it == CarInfo.WIDGET && widget == null } ?: d.info
            return CarLayout(o.enumOr("large", d.large), info,
                app?.let { CarFourth.App(it) } ?: action?.let { CarFourth.Action(it) } ?: d.fourth,
                kept.distinctBy { it.pkg }.take(MAX_APPS), widget)
        }
    }
}

/** One conversation's newest message on the car screen, shown only. [text] null: not to be shown, or there's none to show. */
data class CarMessageLine(val who: String, val text: String?, val scam: Phrase? = null) {
    override fun toString(): String = "CarMessageLine(…)" // l10n: not text (toString, for the logs)
}

/** The messages the car screen shows now, newest first. */
data class CarMessagesNow(val state: State = State.NONE, val lines: List<CarMessageLine> = emptyList(), val locked: Boolean = false) {
    /** [OFF]: Settings › Messages is off, or there's no notification access. [LOCKED]: the phone is locked and its setting shows nothing. */
    enum class State { OFF, NONE, LOCKED, SHOWN }

    companion object {
        /** The most the large panel lists. */
        const val MAX_LINES = 3
        const val MAX_TEXT = 280
        private val HIDDEN_CHARS = Regex("[\\p{Cc}\\p{Cf}]")

        /** From what the bridge gave (it has already applied the settings and the lock: a locked thread's text comes empty). */
        fun of(status: MessagesStatus, threads: List<MessageThread>, max: Int = MAX_LINES): CarMessagesNow {
            if (!status.on || !status.access) return CarMessagesNow(State.OFF)
            val lines = threads.mapNotNull { t ->
                val newest = t.incoming.filter { !it.historic }.maxByOrNull { it.time } ?: return@mapNotNull null
                newest.time to CarMessageLine(clean(t.name), if (t.hidden != null) null else clean(newest.text).take(MAX_TEXT).ifEmpty { null },
                    if (t.hidden != null) null else ScamCheck.line(newest.text))
            }.sortedByDescending { it.first }.map { it.second }.take(max.coerceAtLeast(1))
            return when {
                lines.isNotEmpty() -> CarMessagesNow(State.SHOWN, lines, status.locked)
                status.locked -> CarMessagesNow(State.LOCKED, locked = true)
                else -> CarMessagesNow(State.NONE)
            }
        }

        private fun clean(s: String): String = s.replace(HIDDEN_CHARS, " ").replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * The one line of directions a maps app's navigation notification may give the car screen. Nothing calls this yet: the
 * listener doesn't receive ongoing notifications (the user's decision of 30 Oct 2026, ProcessIsolationTest.messagesStayOnThePhone),
 * so the panel shows its fallback. Null also means the fallback: another app, another kind of notification, no text.
 */
object CarDirectionText {
    const val MAX = 120
    private const val NAVIGATION = "navigation" // Notification.CATEGORY_NAVIGATION; l10n: not text

    fun of(pkg: String?, yourMapsApp: String?, category: String?, title: String?, text: String?): String? {
        if (pkg == null || pkg != yourMapsApp || category != NAVIGATION) return null
        val parts = listOfNotNull(title, text).map { it.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val line = parts.joinToString(" · ") // l10n: not text (a separator)
        return if (line.length <= MAX) line else line.take(MAX - 1).trimEnd() + "…"
    }
}
