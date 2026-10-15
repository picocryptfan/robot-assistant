// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.LogCode
import app.heyrobot.core.OrganicMapsRoute
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.Utterance

// Pure. Car mode's output (design §4.3 "Spoken style" and "Car screen", §4.5 flows 1 and 6): short spoken answers ("Want
// more?" after two sentences), no long text read out, and one status line on the big-button screen. 1.0 (the user, 13
// Oct 2026): nothing is held for when you park; what needs the screen opens as it would outside the car, since a
// passenger may be the one asking. Navigation opens your maps app's route preview (om://route: no link starts navigation
// by itself, so you tap Start). Music is the current player's play, pause, next and previous, sent as media keys.

/** Car mode's spoken style. */
object CarSpeech {
    const val MAX_SENTENCES = 2

    /**
     * The system prompt's car-mode line (about 15 tokens, EST); Prompt.kt adds it while car mode is on (PromptTest limits
     * it). The style only: nothing in it keeps the model from a tool or a card (1.0, no driving restrictions).
     */
    const val PROMPT_LINE = " Car mode is on: answer in one or two short spoken sentences."

    val WANT_MORE = Line("Want more?", "Tu en veux plus?")

    /** [spoken] now, and [rest] after "yes" to "Want more?" (null: nothing more). */
    data class Short(val spoken: String, val rest: String?)

    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    /**
     * [text] for the car: at most [MAX_SENTENCES] sentences and [OutputGuard.DRIVING_MAX_CHARS] characters (cut at a
     * word), then "Want more?" when something was left out.
     */
    fun shorten(text: String, lang: SpokenLang): Short {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return Short("", null)
        val sentences = t.split(SENTENCE_END).filter { it.isNotBlank() }
        var head = sentences.take(MAX_SENTENCES).joinToString(" ")
        var rest = sentences.drop(MAX_SENTENCES).joinToString(" ")
        if (head.length > OutputGuard.DRIVING_MAX_CHARS) {
            val cut = head.lastIndexOf(' ', OutputGuard.DRIVING_MAX_CHARS).takeIf { it > 0 } ?: OutputGuard.DRIVING_MAX_CHARS
            rest = (head.substring(cut).trim() + " " + rest).trim()
            head = head.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
        }
        return if (rest.isEmpty()) Short(head, null) else Short(head + " " + WANT_MORE.get(lang), rest)
    }
}

/** The big-button car screen (CarModeActivity): no bubbles, no lists, no scrolling. */
object CarScreen {
    const val MAX_STATUS = 40

    enum class Button { TALK, MESSAGES, CALL, NAVIGATE_HOME, END }

    /** The screen's buttons: Messages only once 0.7 is there, Navigate home only with a saved home. */
    fun buttons(messages: Boolean, home: Boolean): List<Button> = listOfNotNull(Button.TALK, Button.MESSAGES.takeIf { messages }, Button.CALL,
        Button.NAVIGATE_HOME.takeIf { home }, Button.END)

    /** One status line of at most [MAX_STATUS] characters ("Listening…", "Calling Mom"). */
    fun status(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= MAX_STATUS) return t
        val cut = t.lastIndexOf(' ', MAX_STATUS - 1).takeIf { it > MAX_STATUS / 2 } ?: (MAX_STATUS - 1)
        return t.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** The notification's and screen's title: with the key while trusted. */
    fun title(trusted: Boolean, lang: SpokenLang): String = if (trusted) CarGate.INDICATOR.get(lang) else if (lang == SpokenLang.FR) "Mode auto" else "Car mode"
}

/** Where "navigate …" goes. Also the voice log's CAR_NAV a. */
enum class NavKind(override val id: Int, override val title: String) : LogCode {
    HOME(1, "home"), WORK(2, "work"), NEAREST(3, "the nearest place"), SEARCH(4, "a search"), NO_PLACE(5, "no saved place"), LOCKED(6, "locked"),
}

sealed class NavDestination {
    data object Home : NavDestination()
    data object Work : NavDestination()
    /** "The nearest gas station": the 0.4.0 nearest-place flow (online, its first-use card), then the same preview. */
    data class Nearest(val what: String) : NavDestination()
    data class Other(val query: String) : NavDestination()
}

sealed class NavPlan {
    abstract val say: String
    abstract val kind: NavKind

    /** Open [uri] (Organic Maps' route preview, or the place when Robot doesn't know where you are). */
    data class Route(val uri: String, override val say: String, override val kind: NavKind) : NavPlan()
    /** Hand to the nearest-place flow for [what]. */
    data class Nearest(val what: String, override val say: String) : NavPlan() { override val kind get() = NavKind.NEAREST }
    /** Organic Maps' search for [query]: you pick the place there. */
    data class Search(val query: String, override val say: String) : NavPlan() { override val kind get() = NavKind.SEARCH }
    data class Unlock(override val say: String) : NavPlan() { override val kind get() = NavKind.LOCKED }
    data class NoPlace(override val say: String) : NavPlan() { override val kind get() = NavKind.NO_PLACE }
}

object CarNavigate {
    val ROUTE_READY = Line("Route ready in your maps app. Tap Start to go.", "L'itinéraire est prêt dans ton application de cartes. Appuie sur Démarrer pour partir.")
    val ROUTE_LOCKED = Line("Route ready in your maps app. Unlock to see it, then tap Start.",
        "L'itinéraire est prêt dans ton application de cartes. Déverrouille pour le voir, puis appuie sur Démarrer.")
    val UNLOCK_TO_START = Line("Unlock to start the route.", "Déverrouille pour lancer l'itinéraire.")
    val PICK_IN_MAPS = Line("Pick it in your maps app.", "Choisis-le dans ton application de cartes.")
    val HERE = Line("Here", "Ici")

    fun noPlace(home: Boolean, lang: SpokenLang): String = when (lang) {
        SpokenLang.EN -> "Save your ${if (home) "home" else "work"} address in Settings › Car mode first."
        SpokenLang.FR -> "Enregistre d'abord l'adresse ${if (home) "de la maison" else "du travail"} dans Réglages › Mode auto."
    }

    /**
     * The plan for [dest]. [here]: the approximate position (README 0.4.0), or null. Locked: a saved place opens in a
     * trusted session (Organic Maps' screen then waits behind the lock, EST: phone check C2); anything else says
     * "Unlock to start the route".
     */
    fun plan(dest: NavDestination, s: CarSettings, here: Pair<Double, Double>?, locked: Boolean, trusted: Boolean, lang: SpokenLang): NavPlan {
        val saved = when (dest) {
            NavDestination.Home -> s.home ?: return NavPlan.NoPlace(noPlace(true, lang))
            NavDestination.Work -> s.work ?: return NavPlan.NoPlace(noPlace(false, lang))
            else -> null
        }
        if (locked && (saved == null || !trusted)) return NavPlan.Unlock(UNLOCK_TO_START.get(lang))
        val say = (if (locked) ROUTE_LOCKED else ROUTE_READY).get(lang)
        return when (dest) {
            NavDestination.Home, NavDestination.Work -> {
                val p = saved!!
                val kind = if (dest == NavDestination.Home) NavKind.HOME else NavKind.WORK
                val uri = if (here != null) OrganicMapsRoute.uri(here.first, here.second, HERE.get(lang), p.lat, p.lon, p.label) else OrganicMapsRoute.geo(p.lat, p.lon, p.label)
                NavPlan.Route(uri, say, kind)
            }
            is NavDestination.Nearest -> NavPlan.Nearest(dest.what, say)
            is NavDestination.Other -> NavPlan.Search(dest.query, PICK_IN_MAPS.get(lang))
        }
    }
}

/** The drive brief (§4.4 "Drive brief when the car connects", off): the weather, then what's next. */
enum class DriveItem { WEATHER, NEXT_EVENT }

/** What of the drive brief is said now, asked first ("Read it aloud?"), or held (for the unlock, or the screen). */
data class DriveBriefPlan(val speak: List<DriveItem>, val ask: List<DriveItem>, val held: List<DriveItem>)

object DriveBrief {
    /**
     * The preset routine's parts in the car ([ctx]: a car session; [guard]: the car's read-aloud choice). The weather is
     * said without the town (public); the next event is personal: OutputGuard decides (a trusted session when locked,
     * "Read message text aloud when others may hear" for a routine nobody asked for right then). It becomes a routine:
     * RoutineTrigger.Device(CAR, null, connect = true) with the weather and today's calendar,
     * next event only.
     */
    fun plan(s: CarSettings, ctx: OutputContext, guard: GuardSettings): DriveBriefPlan? {
        if (!s.driveBrief) return null
        val speak = mutableListOf(DriveItem.WEATHER)
        val ask = mutableListOf<DriveItem>()
        val held = mutableListOf<DriveItem>()
        when (OutputGuard.decide(Utterance(SpeechClass.PERSONAL, SpeechSource.ROUTINE, 60), ctx, guard)) {
            is OutputDecision.Speak -> speak += DriveItem.NEXT_EVENT
            is OutputDecision.Ask -> ask += DriveItem.NEXT_EVENT
            is OutputDecision.Hold -> held += DriveItem.NEXT_EVENT
        }
        return DriveBriefPlan(speak, ask, held)
    }
}
