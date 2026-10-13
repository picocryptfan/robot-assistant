package app.heyrobot.core

import app.heyrobot.core.memory.MemoryCommands
import app.heyrobot.core.memory.MemoryKind
import app.heyrobot.core.memory.MemoryLimits
import app.heyrobot.core.memory.Until
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToLong

// Pure. The fixed tool set (phase 1 spec item 7, phase 2 "New tools"): what the model is told (OpenAPI JSON, at
// most three arguments each), and the validation of what it asks for. :brain only describes the tools; the app
// process validates and runs them (ToolExecutor), after the approval gate. The model gives names and words ("Mom",
// "tomorrow 3pm"); the app resolves them: the model never builds phone numbers, XMPP addresses or timestamps.

/**
 * [group]: which tool group describes it (ToolRouter). [reads]: it brings content Robot didn't write into the
 * conversation (a note, a calendar invite, a contact): every later action in that conversation asks first.
 * [internal]: Robot's own, not an action on the phone with an approval level (not under Settings › Actions).
 * [online]: it goes to the internet itself (Settings › Actions says so). 0.9: the Termux tools are [internal] too: Settings ›
 * Termux has their levels (TermuxPolicy decides), never Settings › Actions or the lock screen's list.
 */
enum class ToolName(val id: String, val title: String, val group: ToolGroup, val reads: Boolean = false, val internal: Boolean = false,
    val online: Boolean = false, val forModel: Boolean = true) {
    SET_TIMER("set_timer", "Timers", ToolGroup.CORE),
    SET_ALARM("set_alarm", "Alarms", ToolGroup.CORE),
    OPEN_APP("open_app", "Open apps", ToolGroup.CORE),
    MAPS_SEARCH("maps_search", "Maps search", ToolGroup.CORE),
    MAPS_NAVIGATE("maps_navigate", "Maps directions", ToolGroup.CORE),
    FLASHLIGHT("flashlight", "Flashlight", ToolGroup.CORE),
    SEND_TEXT("send_text", "Texts", ToolGroup.PEOPLE),
    DRAFT_EMAIL("draft_email", "Email drafts", ToolGroup.PEOPLE),
    LOOKUP_CONTACT("lookup_contact", "Contact lookups", ToolGroup.PEOPLE, reads = true),
    /**
     * 0.8: a phone call. The dialer opens with the number (you tap Call); car mode alone calls after a spoken yes. Never
     * described to the model ([forModel]): "call Mom", "appelle maman" is recognised before it (HandsFreeRouter), so the
     * prompt doesn't grow.
     */
    CALL("call", "Calls", ToolGroup.PEOPLE, forModel = false),
    ADD_NOTE("add_note", "New notes", ToolGroup.NOTES),
    FIND_NOTES("find_notes", "Note searches", ToolGroup.NOTES, reads = true),
    ADD_EVENT("add_event", "New events", ToolGroup.CALENDAR),
    READ_CALENDAR("read_calendar", "Calendar lookups", ToolGroup.CALENDAR, reads = true),
    /** 0.4.0: Open-Meteo's forecast for a place you name, your Home for weather, or where the phone is. */
    WEATHER("weather", "Weather lookups", ToolGroup.WEATHER, reads = true, online = true),
    /** 0.4.0: storage, battery and memory, read on the phone (the apps using the most space with Usage access). */
    PHONE_STATUS("phone_status", "Phone status checks", ToolGroup.PHONE, reads = true),
    /** 0.4.0: unload Robot's own model, or open an app's info page to force stop it (FreeUp.kt). */
    FREE_MEMORY("free_memory", "Memory clean-ups", ToolGroup.PHONE),
    /** 0.4.0: Android's Free up space screen, an app's cache, a large photo or video, Robot's cache. */
    FREE_STORAGE("free_storage", "Storage clean-ups", ToolGroup.PHONE),
    /**
     * 0.9: private web search (core web, docs/plans/websearch-termux-design.md Part A): your search service's results,
     * answered by a tool-free one-shot. Described only with Settings › Web search on; goes online like the weather.
     */
    WEB_SEARCH("web_search", "Web searches", ToolGroup.WEB, reads = true, online = true),
    /** 1.0: a search in your browser (BrowserActions.kt): Robot sends nothing itself, never sees the results, never on the lock screen. */
    BROWSER_SEARCH("browser_search", "Searches in your browser", ToolGroup.WEB, internal = true),
    /**
     * Ask Claude (docs/ask-claude.md "When Robot needs help"): described to Gemma only with Ask Claude on and escalation
     * not Off. Through the API it never runs as a tool (the turn hands over to Claude, EscalationPolicy decides); through
     * the Claude app it is the hand-off, always behind a card.
     */
    ASK_CLAUDE("ask_claude", "Ask a helper", ToolGroup.CORE, internal = true),
    /**
     * 0.7 memory (core memory/): keep something about you for later conversations, or drop it. Robot's own, under
     * Settings › Memory rather than Settings › Actions, described only with Memory on and words that call for them.
     */
    REMEMBER("remember", "Memory", ToolGroup.MEMORY, internal = true),
    FORGET("forget", "Memory", ToolGroup.MEMORY, internal = true),
    /** 0.9, the Termux agent (TermuxActions.kt): one of your scripts in ~/robot, and one short command. */
    TERMUX_SCRIPT("termux_script", "Your Termux scripts", ToolGroup.TERMUX, internal = true),
    TERMUX_COMMAND("termux_command", "Termux commands", ToolGroup.TERMUX, internal = true);

    /** A Termux tool: Settings › Termux and TermuxPolicy decide about it (ApprovalGate's Termux branch). */
    val termux: Boolean get() = group == ToolGroup.TERMUX

    companion object {
        fun of(id: String?): ToolName? = entries.firstOrNull { it.id == id?.trim()?.lowercase(Locale.ROOT) }
    }
}

data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean)

data class ToolSpec(val tool: ToolName, val description: String, val params: List<ToolParam>) {
    init { require(params.size <= MAX_PARAMS) { "tools take at most $MAX_PARAMS arguments" } }

    companion object {
        /** Phase 1 allowed two; draft_email and add_event need three (to, subject, body; title, when, length). */
        const val MAX_PARAMS = 3
    }

    /** {"name", "description", "parameters": {"type":"object", "properties", "required"}} as LiteRT-LM's OpenApiTool wants it. */
    fun openApiJson(): String = JSONObject().apply {
        put("name", tool.id)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                for (p in params) put(p.name, JSONObject().put("type", p.type).put("description", p.description))
            })
            put("required", JSONArray(params.filter { it.required }.map { it.name }))
        })
    }.toString()
}

object ToolSpecs {
    val ALL: List<ToolSpec> = listOf(
        ToolSpec(ToolName.SET_TIMER, "Start a countdown timer in the Clock app (minutes, seconds or both).", listOf(
            ToolParam("minutes", "integer", "Whole minutes of the timer, e.g. 5", false),
            ToolParam("seconds", "integer", "Extra seconds of the timer, e.g. 30", false),
        )),
        ToolSpec(ToolName.SET_ALARM, "Set an alarm in the Clock app for the next time the clock shows hour:minute.", listOf(
            ToolParam("hour", "integer", "Hour on a 24-hour clock, 0 to 23", true),
            ToolParam("minute", "integer", "Minute, 0 to 59", false),
        )),
        ToolSpec(ToolName.OPEN_APP, "Open an installed app by its name.", listOf(
            ToolParam("name", "string", "The app's name as shown on the home screen, e.g. Signal", true),
        )),
        ToolSpec(ToolName.MAPS_SEARCH, "Search for a place or a kind of place in Organic Maps.", listOf(
            ToolParam("query", "string", "What to look for, e.g. pharmacy or Central Station", true),
        )),
        ToolSpec(ToolName.MAPS_NAVIGATE, "Find a destination in Organic Maps to get directions to it.", listOf(
            ToolParam("destination", "string", "Where to go, e.g. an address or a place name", true),
        )),
        ToolSpec(ToolName.FLASHLIGHT, "Turn the phone's flashlight on or off.", listOf(
            ToolParam("on", "boolean", "true to turn it on, false to turn it off", true),
        )),
        // Phase 2: one sentence each and short parameter text (every token here is paid on every rebuild).
        ToolSpec(ToolName.SEND_TEXT, "Draft a text message; the person sends it.", listOf(
            ToolParam("to", "string", "Name, number or XMPP address", true),
            ToolParam("message", "string", "The text", true),
        )),
        ToolSpec(ToolName.DRAFT_EMAIL, "Draft an email; the person sends it.", listOf(
            ToolParam("to", "string", "Name or email address", true),
            ToolParam("subject", "string", "Subject", false),
            ToolParam("body", "string", "The text", true),
        )),
        ToolSpec(ToolName.LOOKUP_CONTACT, "Find someone's numbers and emails in the contacts.", listOf(
            ToolParam("name", "string", "Their name", true),
        )),
        // 0.8: one short line, as the others in the group.
        ToolSpec(ToolName.CALL, "Phone someone; the person taps Call.", listOf(
            ToolParam("to", "string", "Name or number", true),
        )),
        ToolSpec(ToolName.ADD_NOTE, "Save a note in Robot Notes.", listOf(
            ToolParam("text", "string", "What to note", true),
            ToolParam("title", "string", "Short title", false),
        )),
        ToolSpec(ToolName.FIND_NOTES, "Search Robot Notes.", listOf(
            ToolParam("query", "string", "Words to find", true),
        )),
        ToolSpec(ToolName.ADD_EVENT, "Add an event to the calendar.", listOf(
            ToolParam("title", "string", "What, e.g. Dentist", true),
            ToolParam("when", "string", "As said, e.g. Friday 9 to 10:30", true),
            ToolParam("duration_minutes", "integer", "Length if no end was said", false),
        )),
        ToolSpec(ToolName.READ_CALENDAR, "List calendar events for a day or a week.", listOf(
            ToolParam("day", "string", "e.g. today, Friday, next week", true),
        )),
        // 0.4.0, as short as the others: no place is where you are (or your Home for weather), no day is today.
        ToolSpec(ToolName.WEATHER, "Weather forecast (online).", listOf(
            ToolParam("place", "string", "Town, or omit for here", false),
            ToolParam("day", "string", "e.g. tomorrow, Friday, this week", false),
        )),
        ToolSpec(ToolName.PHONE_STATUS, "Read this phone's storage, battery and memory.", listOf(
            ToolParam("about", "string", "storage, battery, memory or all", false),
        )),
        ToolSpec(ToolName.FREE_MEMORY, "Free memory: unload Robot's model, or open an app's info to force stop it.", listOf(
            ToolParam("app", "string", "App to stop; omit to unload Robot's model", false),
        )),
        ToolSpec(ToolName.FREE_STORAGE, "Help free storage.", listOf(
            ToolParam("what", "string", "android (its Free up space), apps (an app's cache), files (big photos, videos) or robot (Robot's cache)", false),
        )),
        // 0.9, the design's two parameters: Gemma's turn ends at the call and a one-shot answers from the results.
        ToolSpec(ToolName.WEB_SEARCH, "Search the web (online).", listOf(
            ToolParam("query", "string", "Short search words", true),
            ToolParam("depth", "string", "quick, or read to open pages", false),
        )),
        ToolSpec(ToolName.ASK_CLAUDE, "Ask Claude, a stronger AI online, instead of guessing.", listOf(
            ToolParam("question", "string", "The person's question", true),
        )),
        // 0.7: what the person asks Robot to keep about them, as small as a tool can be (the router answers "remember that …"
        // and "forget …" itself; a preference is told by its words, MemoryCommands.prefers).
        ToolSpec(ToolName.REMEMBER, "Remember a fact about the person.", listOf(
            ToolParam("text", "string", "The fact, in their words", true),
        )),
        ToolSpec(ToolName.FORGET, "Forget a remembered fact.", listOf(
            ToolParam("query", "string", "Its words", true),
        )),
    ) + BrowserCalls.SPEC + TermuxCalls.SPECS

    fun of(tool: ToolName): ToolSpec = ALL.first { it.tool == tool }
}

/** A validated request to do something on the phone. */
sealed class Action {
    private companion object {
        /** After a person whose name was learned from a Cheogram notification: whoever sent it chose that name. */
        const val LEARNED = " · name seen in a notification"
    }

    abstract val tool: ToolName
    /**
     * Plain language for the confirmation card and the log-free history ("Set a timer for 5 minutes"). The person or
     * thing it is about may be marked **bold** and the calendar *italic* (the card renders light Markdown).
     */
    abstract fun describe(): String
    /** What the card shows under the summary: a message's full text, an email's subject and body, a note. */
    open fun body(): String? = null
    /** The card's button when it asks first ("Open in Cheogram"); null = "Do it". */
    open val confirmLabel: String? get() = null
    /**
     * It goes to the internet when it runs: the weather, or a maps request for the nearest place (0.4.0). Stop ends it at
     * once and it has a deadline (the panel's OnlineRun).
     */
    open val online: Boolean get() = tool.online
    /** 0.9: it runs for a while and Stop ends it (a Termux run: Stop ends its whole process tree), like an online lookup. */
    open val stoppable: Boolean get() = false
    /** It may run on the lock screen ([lockedTools]: Settings › Safety & privacy › "Works on the lock screen"). */
    open fun worksLocked(lockedTools: Set<ToolName>): Boolean = tool in lockedTools

    data class SetTimer(val seconds: Long) : Action() {
        override val tool get() = ToolName.SET_TIMER
        override fun describe() = "Set a timer for ${Durations.spoken(seconds)}"
    }

    data class SetAlarm(val hour: Int, val minute: Int) : Action() {
        override val tool get() = ToolName.SET_ALARM
        override fun describe() = String.format(Locale.US, "Set an alarm for %02d:%02d", hour, minute)
    }

    /**
     * [name] is what the model asked for until the app process resolves it to an installed app: then [name] is
     * that app's real label and [key] its launcher activity (what the card shows is what opens).
     */
    data class OpenApp(val name: String, val key: String? = null) : Action() {
        override val tool get() = ToolName.OPEN_APP
        override fun describe() = "Open $name"
    }

    /**
     * [nearest]: "the nearest/closest …" (0.4.0): with Settings › Actions › "Find the nearest place online" allowing it, Robot
     * finds the closest one with OpenStreetMap and opens Organic Maps' route to it (Nearby); otherwise the search, as before.
     * [consent]: the first-use card's answer it carries (Nearby.prepare), kept when it runs.
     */
    data class MapsSearch(val query: String, val nearest: Boolean = false, val consent: NearestConsent? = null) : Action() {
        override val tool get() = ToolName.MAPS_SEARCH
        override fun describe() = if (nearest) Nearby.describe(query) else "Search Organic Maps for “$query”"
        override val online get() = nearest
    }

    /**
     * Organic Maps can't route to a name: this opens a search for [destination], and you pick it and tap Route; or, for the
     * [nearest] one, the route to the closest place OpenStreetMap knows ([MapsSearch.nearest]).
     */
    data class MapsNavigate(val destination: String, val nearest: Boolean = false, val consent: NearestConsent? = null) : Action() {
        override val tool get() = ToolName.MAPS_NAVIGATE
        override fun describe() = if (nearest) Nearby.describe(destination) else "Search Organic Maps for “$destination”, then tap Route"
        override val online get() = nearest
    }

    data class Flashlight(val on: Boolean) : Action() {
        override val tool get() = ToolName.FLASHLIGHT
        override fun describe() = if (on) "Turn the flashlight on" else "Turn the flashlight off"
    }

    /** [to] as the model gave it until the app resolves it to a [target] (what the card shows is where it goes). */
    data class SendText(val to: String, val message: String, val target: TextTarget? = null) : Action() {
        override val tool get() = ToolName.SEND_TEXT
        override fun describe() = when {
            target == null -> "Text **$to**"
            target.via == TextVia.CHEOGRAM_PICK -> "${target.display} Choose who to text in Cheogram"
            target.via == TextVia.CHEOGRAM_LIST -> "${target.display} Pick who to text in Cheogram"
            target.name == null -> "Text **${target.display}** in ${target.appName}"
            else -> "Text **${target.name}** (${target.display}) in ${target.appName}" + if (target.from?.learned == true) LEARNED else ""
        }
        override fun body() = message
        /** The button names the person too: the card's top line may be out of sight on a long message. */
        override val confirmLabel get() = target?.let {
            when (it.via) {
                TextVia.CHEOGRAM_PICK -> "Choose in Cheogram"
                TextVia.CHEOGRAM_LIST -> "Pick in Cheogram"
                else -> "Open in ${it.appName} for ${it.name ?: it.display}"
            }
        }
    }

    data class DraftEmail(val to: String, val subject: String, val text: String, val target: MailTarget? = null) : Action() {
        override val tool get() = ToolName.DRAFT_EMAIL
        override fun describe(): String {
            val t = target ?: return "Email **$to**"
            val who = if (t.name == null) "**${t.address}**" else "**${t.name}** (${t.address})"
            return (if (t.appName == null) "Email $who" else "Email $who in ${t.appName}") + if (t.from?.learned == true) LEARNED else ""
        }
        override fun body() = listOfNotNull(subject.takeIf { it.isNotBlank() }?.let { "Subject: $it" }, text.takeIf { it.isNotBlank() }).joinToString("\n\n")
        override val confirmLabel get() = target?.let { t -> "${t.appName?.let { "Open in $it" } ?: "Choose the email app"} for ${t.name ?: t.address}" }
    }

    data class LookupContact(val name: String) : Action() {
        override val tool get() = ToolName.LOOKUP_CONTACT
        override fun describe() = "Look up **$name** in your contacts"
    }

    /**
     * 0.8: a call to [to] (a name or a number, as said). Once the app found it ([number], People and contacts, numbers
     * only): [who], its [label] ("Mobile") and whether the name was [learned] from a Cheogram notification. [emergency]:
     * an emergency number, which only ever opens the dialer. [direct]: car mode's spoken read-back and yes, with the
     * Phone permission and "Call directly after a spoken yes", places the call (ACTION_CALL); otherwise the dialer opens
     * with the number and you tap Call (ACTION_DIAL).
     */
    data class Call(val to: String, val number: String? = null, val who: String? = null, val label: String? = null, val learned: Boolean = false,
        val emergency: Boolean = false, val direct: Boolean = false) : Action() {
        override val tool get() = ToolName.CALL
        override fun describe(): String {
            val n = number ?: return "Call **$to**"
            val name = who?.takeIf { it.isNotBlank() }
            return (if (name == null) "Call **$n**" else "Call **$name**" + (label?.let { " ($it, $n)" } ?: " ($n)")) + if (learned) LEARNED else ""
        }
        override val confirmLabel get() = if (direct && !emergency) "Call" else "Open the dialer"
    }

    data class AddNote(val text: String, val title: String? = null) : Action() {
        override val tool get() = ToolName.ADD_NOTE
        override fun describe() = if (title == null) "Save a note in Robot Notes" else "Save the note **$title** in Robot Notes"
        override fun body() = text
    }

    data class FindNotes(val query: String) : Action() {
        override val tool get() = ToolName.FIND_NOTES
        override fun describe() = "Search your notes for “$query”"
    }

    /** [whenText] in the person's words until the app reads it into a [plan] (the dates and the calendar it goes to). */
    data class AddEvent(val title: String, val whenText: String, val minutes: Int? = null, val plan: EventPlan? = null) : Action() {
        override val tool get() = ToolName.ADD_EVENT
        override fun describe(): String {
            val p = plan ?: return "Add **$title** ($whenText)"
            val span = EventText.span(p.start, p.end, p.allDay, p.currentYear)
            return if (p.calendarName != null) "Add **$title** on $span to *${p.calendarName}*" else "Add **$title** on $span in your calendar app"
        }
        override val confirmLabel get() = plan?.let { if (it.calendarId == null) "Open the calendar app" else "Add to ${it.calendarName}" }
    }

    /**
     * The Claude app route: open the Claude app with [text] (your question, or a short summary of the
     * conversation; empty: the app opens without text). You press Send there; Robot steps aside.
     */
    data class AskClaude(val text: String) : Action() {
        override val tool get() = ToolName.ASK_CLAUDE
        override fun describe() = if (text.isBlank()) "Open the Claude app" else "Open the Claude app with: «${short(text)}»"
        override fun body() = text.takeIf { it.length > SHORT }
        override val confirmLabel get() = "Open the app"

        private companion object {
            const val SHORT = 120
            fun short(t: String) = t.replace(Regex("\\s+"), " ").trim().let { if (it.length <= SHORT) it else ToolCalls.safeTake(it, SHORT - 1).trimEnd() + "…" }
        }
    }

    /**
     * [place] as the model gave it (null: where you are). The app reads it into [where] (here, your Home for weather or
     * a named place) and [day] into the [range] of days, before anything goes online.
     */
    data class Weather(val place: String?, val day: String?, val where: WeatherWhere? = null, val range: DayRange? = null) : Action() {
        override val tool get() = ToolName.WEATHER
        override fun describe(): String {
            val w = where ?: WeatherAsk.where(place).first
            val at = when (w) {
                WeatherWhere.HERE -> " here"
                WeatherWhere.HOME -> " at home"
                WeatherWhere.NAMED -> " in **${WeatherAsk.where(place).second ?: place}**"
            }
            val on = range?.let { " for ${EventText.range(it)}" } ?: day?.let { " for $it" } ?: ""
            return "Check the weather$at$on"
        }
    }

    /**
     * remember (0.7): [text] to keep, a [kind], an end ([until]). The card shows the text in its body only: its summary,
     * which History keeps, never holds it ([MemoryHygiene]).
     */
    data class Remember(val text: String, val kind: MemoryKind = MemoryKind.FACT,
        val until: Until? = null) : Action() {
        override val tool get() = ToolName.REMEMBER
        override fun describe() = if (kind == MemoryKind.PREFERENCE) "Remember a preference" else "Remember this"
        override fun body() = text
        override val confirmLabel get() = "Remember"
        override fun toString(): String = "Remember(kind=$kind, until=$until, chars=${text.length})"
    }

    /**
     * forget (0.7): [query] as the model gave it, until the app finds the items it means ([ids], with their words in
     * [shown] for the card's body only).
     */
    data class Forget(val query: String, val ids: List<String> = emptyList(), val shown: String? = null) : Action() {
        override val tool get() = ToolName.FORGET
        override fun describe() = if (ids.size > 1) "Forget ${ids.size} things from memory" else "Forget from memory"
        override fun body() = shown
        override val confirmLabel get() = "Forget"
        override fun toString(): String = "Forget(ids=${ids.size}, chars=${query.length})"
    }

    /** phone_status: what was asked about; the app process reads it. */
    data class PhoneCheck(val part: PhonePart) : Action() {
        override val tool get() = ToolName.PHONE_STATUS
        override fun describe() = PhoneStatus.summary(part)
    }

    /**
     * free_memory: [app] as the model named it (null: unload Robot's own model) until the app process finds it among the
     * installed apps ([pkg]); then [app] is its real label.
     */
    data class FreeMemory(val app: String?, val pkg: String? = null) : Action() {
        override val tool get() = ToolName.FREE_MEMORY
        override fun describe() = when {
            pkg == SettingsPage.USAGE_ACCESS -> "Open Usage access, so Robot can list the apps you used lately"
            app == null -> "Unload Robot's model to free about 2 GB of memory (4 GB for an E4B model)"
            else -> "Open **$app**'s App info to force stop it"
        }
        override val confirmLabel get() = when {
            pkg == SettingsPage.USAGE_ACCESS -> "Open"
            app == null -> "Unload"
            else -> "Open App info"
        }

        companion object {
            /** 0.7.0: the "free up memory" card's button without Usage access ([pkg] = SettingsPage.USAGE_ACCESS) opens Android's page for it. */
            const val USAGE_ACCESS_LABEL = "Usage access"
        }
    }

    /**
     * free_storage. [target]: the app's package ([StorageWay.APPS]) or the file's content URI ([StorageWay.FILES]) once
     * picked on the card; [label] the app's name; [bytes] Robot's cache ([StorageWay.ROBOT]); [summary] what the card
     * says of a picked file, without its name (FreeUp.fileSummary).
     */
    data class FreeStorage(val way: StorageWay, val target: String? = null, val label: String? = null, val bytes: Long? = null,
        val summary: String? = null) : Action() {
        override val tool get() = ToolName.FREE_STORAGE
        override fun describe() = when (way) {
            StorageWay.ANDROID -> "Open Android's Free up space screen"
            StorageWay.APPS -> if (target == null) "Choose an app to clear its cache" else "Open **$label**'s App info to clear its cache"
            StorageWay.FILES -> summary ?: "Choose a large photo or video to delete"
            StorageWay.ROBOT -> "Clear Robot's cache" + (bytes?.let { " (${PhoneStatus.size(it)})" } ?: "")
        }
        override val confirmLabel get() = when (way) {
            StorageWay.ANDROID -> "Open"
            StorageWay.APPS -> "Open App info"
            StorageWay.FILES -> "Delete"
            StorageWay.ROBOT -> "Clear"
        }
    }

    /**
     * web_search (0.9): [query] as the model wrote it (or your own words, "search the web for …"), [depth] "read" to open
     * result pages. [consent]: the first search's card answer it carries (core web WebConsent), kept when it runs; [query]
     * may be changed on the card's way (your Home for weather town for "near me").
     */
    data class WebSearch(val query: String, val depth: String? = null, val consent: app.heyrobot.core.web.WebConsentChoice? = null) : Action() {
        override val tool get() = ToolName.WEB_SEARCH
        override fun describe() = "Search the web for “$query”"
        override val confirmLabel get() = "Search"
    }

    /** [day] in the person's words until the app reads it into a [range]. */
    data class ReadCalendar(val day: String, val range: DayRange? = null) : Action() {
        override val tool get() = ToolName.READ_CALENDAR
        override fun describe() = "Check your calendar for ${range?.let { EventText.range(it) } ?: day}"
    }
}

/** Where a text goes: a contact's (or the given) number or XMPP address, and the app that opens with the draft. */
data class TextTarget(
    /** The contact's name; null when a number or address was given directly. */
    val name: String?,
    /** For Cheogram the XMPP address (a number goes through the SMS gateway); for the SMS app the E.164 number. */
    val address: String,
    /**
     * What the card shows: "+1 514-555-0101" or the XMPP address; for [TextVia.CHEOGRAM_PICK] and [TextVia.CHEOGRAM_LIST],
     * why no contact fitted.
     */
    val display: String,
    val appName: String,
    val via: TextVia,
    /**
     * The contact's place it came from (its id, the address as stored, the Cheogram account of an XMPP address, whether
     * the name was learned from a notification); null for a number or address given directly.
     */
    val from: Reach? = null,
    /** Tapped with "Always use this for …": Robot remembers this place for the name asked (Action.SendText.to). */
    val remember: Boolean = false,
) {
    /**
     * The Cheogram account to open the chat in, for an address picked in Cheogram's list only: Cheogram then opens that
     * account's chat without asking which account.
     */
    val account: String? get() = from?.account
}

enum class TextVia {
    CHEOGRAM,
    SMS,
    /**
     * No contact fitted: Cheogram's share screen opens with the message, and you pick the person in Cheogram's own list
     * (its roster holds people the phone's contacts don't). No [TextTarget.address].
     */
    CHEOGRAM_PICK,
    /**
     * No contact fitted: Cheogram's contact list opens for a result (its "Create shortcut" screen, ACTION_CREATE_SHORTCUT;
     * nothing goes on the home screen), Robot offers to remember who you picked, then opens their chat with the message.
     * No [TextTarget.address].
     */
    CHEOGRAM_LIST,
}

/**
 * Where an email draft goes. [appName] null: the system chooser asks which app. [from]: the contact's place it came
 * from; [remember]: tapped with "Always use this for …".
 */
data class MailTarget(val name: String?, val address: String, val appName: String?, val from: Reach? = null, val remember: Boolean = false)

/** A resolved add_event: when, and the calendar it goes into ([calendarId] null: the calendar app's editor). */
data class EventPlan(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val calendarId: Long?,
    val calendarName: String?,
    /** The year it is now: the card shows the event's year only when it differs. */
    val currentYear: Int,
)

sealed class ParsedCall {
    data class Ok(val action: Action) : ParsedCall()
    /** [tool] is the name the model used (maybe unknown); [reason] goes back to the model. */
    data class Invalid(val tool: String, val reason: String) : ParsedCall()
}

/** A tool call as the model produced it: a name and loosely typed arguments. */
data class RawToolCall(val name: String, val args: Map<String, Any?>)

object Durations {
    const val MAX_TIMER_S = 24 * 3600L

    fun spoken(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(plural(h, "hour"))
            if (m > 0) add(plural(m, "minute"))
            if (s > 0 || isEmpty()) add(plural(s, "second"))
        }
        return when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> "${parts[0]}, ${parts[1]} and ${parts[2]}"
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

object ToolCalls {
    private const val MAX_TEXT = 200
    private const val MAX_NAME = 120
    private const val MAX_TITLE = 80
    /** A text message: long enough for anything said aloud; the card shows all of it before anything opens. */
    const val MAX_MESSAGE = 2_000
    /** An email body or a note. */
    const val MAX_LONG = 4_000
    const val MAX_EVENT_MINUTES = 24 * 60
    /** A place's name as shown (a Home for weather: "Saint-Jean-sur-Richelieu, Quebec, Canada"). */
    const val MAX_PLACE = 120

    /**
     * Validates one call. Unknown argument names are ignored; missing or out-of-range ones are refused, and so is
     * any argument (under any of its accepted names) whose value can't be read: "30 am" for a minute is refused,
     * never read as 0.
     */
    fun parse(call: RawToolCall): ParsedCall {
        val tool = ToolName.of(call.name) ?: return ParsedCall.Invalid(call.name.take(40), "unknown tool")
        val a = call.args.mapKeys { it.key.trim().lowercase(Locale.ROOT) }
        fun bad(why: String) = ParsedCall.Invalid(tool.id, why)
        return when (tool) {
            ToolName.SET_TIMER -> {
                val min = numberArg(a, "minutes", "minute", "mins", "min") ?: return bad("minutes must be a number")
                val sec = numberArg(a, "seconds", "second", "secs", "sec") ?: return bad("seconds must be a number")
                if ((min.value ?: 0.0) < 0 || (sec.value ?: 0.0) < 0) return bad("the timer cannot be negative")
                val total = ((min.value ?: 0.0) * 60 + (sec.value ?: 0.0)).roundToLong()
                when {
                    total <= 0 -> bad("give the timer's length in minutes or seconds")
                    total > Durations.MAX_TIMER_S -> bad("timers can be at most 24 hours")
                    else -> ParsedCall.Ok(Action.SetTimer(total))
                }
            }
            ToolName.SET_ALARM -> {
                val rawHour = a["hour"] ?: a["hours"]
                // "7:30" in the hour argument: small models do this.
                val clock = (rawHour as? String)?.let { Regex("""^\s*(\d{1,2})\s*[:.h]\s*(\d{2})\s*$""").find(it) }
                val minuteArg = numberArg(a, "minute", "minutes", "min", "mins") ?: return bad("minute must be a number")
                val hour = if (clock != null) clock.groupValues[1].toDouble() else {
                    val h = numberArg(a, "hour", "hours") ?: return bad("hour must be a number")
                    h.value ?: return bad("hour is required")
                }
                val minute = clock?.groupValues?.get(2)?.toDouble() ?: minuteArg.value ?: 0.0
                if (hour % 1.0 != 0.0 || minute % 1.0 != 0.0) return bad("hour and minute must be whole numbers")
                if (hour !in 0.0..23.0) return bad("hour must be 0 to 23")
                if (minute !in 0.0..59.0) return bad("minute must be 0 to 59")
                ParsedCall.Ok(Action.SetAlarm(hour.toInt(), minute.toInt()))
            }
            ToolName.OPEN_APP -> textArg(a, 60, "name", "app", "app_name")?.let { ParsedCall.Ok(Action.OpenApp(it)) }
                ?: bad("name is required")
            // Without "near me" (MapsQuery): the card, the record and what Organic Maps searches are the same words.
            // "The nearest …" is kept as a flag: the app may find the closest one online (Nearby).
            ToolName.MAPS_SEARCH -> textArg(a, MAX_TEXT, "query", "q", "place")?.let { ParsedCall.Ok(Action.MapsSearch(MapsQuery.clean(it), NearestAsk.said(it))) }
                ?: bad("query is required")
            ToolName.MAPS_NAVIGATE -> textArg(a, MAX_TEXT, "destination", "to", "place")?.let { ParsedCall.Ok(Action.MapsNavigate(MapsQuery.clean(it), NearestAsk.said(it))) }
                ?: bad("destination is required")
            ToolName.FLASHLIGHT -> {
                val present = listOf("on", "state", "enabled").filter { a.containsKey(it) }
                val values = present.map { bool(a[it]) }
                if (values.isEmpty() || values.any { it == null }) bad("on must be true or false")
                else if (values.distinct().size > 1) bad("on must be true or false")
                else ParsedCall.Ok(Action.Flashlight(values.first()!!))
            }
            ToolName.SEND_TEXT -> {
                val to = textArg(a, MAX_NAME, "to", "recipient", "contact", "name", "number") ?: return bad("to is required")
                val message = longArg(a, MAX_MESSAGE, "message", "text", "body") ?: return bad("message is required")
                ParsedCall.Ok(Action.SendText(to, message))
            }
            ToolName.DRAFT_EMAIL -> {
                val to = textArg(a, MAX_NAME, "to", "recipient", "email", "address") ?: return bad("to is required")
                val subject = textArg(a, MAX_TEXT, "subject", "title").orEmpty()
                val body = longArg(a, MAX_LONG, "body", "text", "message").orEmpty()
                if (subject.isEmpty() && body.isEmpty()) return bad("give the email a body")
                ParsedCall.Ok(Action.DraftEmail(to, subject, body))
            }
            ToolName.LOOKUP_CONTACT -> textArg(a, MAX_NAME, "name", "query", "contact", "who")?.let { ParsedCall.Ok(Action.LookupContact(it)) }
                ?: bad("name is required")
            ToolName.CALL -> textArg(a, MAX_NAME, "to", "name", "who", "number", "contact", "recipient")?.let { ParsedCall.Ok(Action.Call(it)) }
                ?: bad("to is required")
            ToolName.ADD_NOTE -> {
                val text = longArg(a, MAX_LONG, "text", "note", "content", "body") ?: return bad("text is required")
                ParsedCall.Ok(Action.AddNote(text, textArg(a, MAX_TITLE, "title")))
            }
            ToolName.FIND_NOTES -> textArg(a, MAX_NAME, "query", "q", "text", "search")?.let { ParsedCall.Ok(Action.FindNotes(it)) }
                ?: bad("query is required")
            ToolName.ADD_EVENT -> {
                val title = textArg(a, MAX_TITLE, "title", "name", "event", "what") ?: return bad("title is required")
                // Small models split the date and the time ({date: Tuesday, time: 3pm}, {start, end}): all of it counts,
                // in the order date, day, when, start, time, then "to <end>".
                val parts = listOf("date", "day", "when", "start", "time").filter { a.containsKey(it) }.map { text(a[it], MAX_NAME) ?: return bad("$it must be text") }
                if (parts.isEmpty()) return bad("when is required")
                val endPart = if (a.containsKey("end")) text(a["end"], MAX_NAME) ?: return bad("end must be text") else null
                val whenText = (parts.joinToString(" ") + (endPart?.let { " to $it" } ?: "")).take(MAX_NAME)
                val minutes = numberArg(a, "duration_minutes", "duration", "minutes", "length") ?: return bad("duration_minutes must be a number")
                val m = minutes.value
                if (m != null && (m % 1.0 != 0.0 || m < 1 || m > MAX_EVENT_MINUTES)) return bad("duration_minutes must be a whole number from 1 to $MAX_EVENT_MINUTES")
                ParsedCall.Ok(Action.AddEvent(title, whenText, m?.toInt()))
            }
            // Empty: "go get Claude" with nothing to summarise opens the app without text.
            ToolName.ASK_CLAUDE -> ParsedCall.Ok(Action.AskClaude(longArg(a, ClaudeAppLink.MAX_CHARS, "question", "query", "q", "text", "prompt").orEmpty()))
            // Both optional: no place is where you are, no day is today.
            ToolName.WEATHER -> ParsedCall.Ok(Action.Weather(textArg(a, MAX_NAME, "place", "location", "city", "where", "town"),
                textArg(a, MAX_NAME, "day", "date", "when", "time", "days")))
            ToolName.REMEMBER -> {
                val text = longArg(a, MemoryLimits.MAX_CHARS, "text", "fact", "memory", "what", "content", "note")
                    ?.replace(Regex("\\s+"), " ")?.trim() ?: return bad("text is required")
                val kind = when (textArg(a, MAX_NAME, "kind", "type")?.lowercase(Locale.ROOT)) {
                    "preference", "pref", "preferences" -> MemoryKind.PREFERENCE
                    else -> if (MemoryCommands.prefers(text)) MemoryKind.PREFERENCE else MemoryKind.FACT
                }
                ParsedCall.Ok(Action.Remember(text, kind, Until.of(textArg(a, MAX_NAME, "until", "expires", "end"))))
            }
            ToolName.FORGET -> textArg(a, MAX_NAME, "query", "text", "what", "q", "about")?.let { ParsedCall.Ok(Action.Forget(it)) }
                ?: bad("query is required")
            // The query as written (QueryCheck decides later whether it may go), and the depth if it gave one.
            ToolName.WEB_SEARCH -> textArg(a, MAX_TEXT, "query", "q", "search", "text", "question")
                ?.let { ParsedCall.Ok(Action.WebSearch(it, textArg(a, 20, "depth", "mode"))) } ?: bad("query is required")
            // Anything it doesn't name (or nothing) is all of it.
            ToolName.PHONE_STATUS -> ParsedCall.Ok(Action.PhoneCheck(PhonePart.of(textArg(a, MAX_NAME, "about", "part", "what", "topic", "kind"))))
            // Robot's own model when no app (or Robot itself) is named.
            ToolName.FREE_MEMORY -> ParsedCall.Ok(Action.FreeMemory(FreeUp.appOrNull(textArg(a, MAX_NAME, "app", "name", "application", "package"))))
            // Android's own screen unless it says apps, files or Robot.
            ToolName.FREE_STORAGE -> ParsedCall.Ok(Action.FreeStorage(StorageWay.of(textArg(a, MAX_NAME, "what", "way", "kind", "target", "how"))))
            // 0.9: a command's spaces and quotes are kept as written (TermuxActions.kt).
            ToolName.TERMUX_SCRIPT, ToolName.TERMUX_COMMAND -> TermuxCalls.parse(tool, a)
            ToolName.BROWSER_SEARCH -> BrowserCalls.parse(a) { m, max, names -> textArg(m, max, *names) }
            // No day said: today (the person asked "what's on my calendar").
            ToolName.READ_CALENDAR -> {
                val present = listOf("day", "date", "when", "range").firstOrNull { a.containsKey(it) }
                val day = if (present == null) "today" else text(a[present], MAX_NAME) ?: return bad("day must be text like today or Friday")
                ParsedCall.Ok(Action.ReadCalendar(day))
            }
        }
    }

    /** A numeric argument that may be absent ([value] null), or null itself when a name for it is present but unreadable. */
    private class NumArg(val value: Double?)

    private fun numberArg(a: Map<String, Any?>, vararg names: String): NumArg? {
        var first: Double? = null
        for (n in names) {
            if (!a.containsKey(n)) continue
            val v = number(a[n]) ?: return null
            if (first == null) first = v
        }
        return NumArg(first)
    }

    /** The first present name's text; null when it's missing, blank or not text (a present but empty name is not skipped). */
    private fun textArg(a: Map<String, Any?>, max: Int, vararg names: String): String? =
        names.firstOrNull { a.containsKey(it) }?.let { text(a[it], max) }

    /** Like [textArg] but keeps the line breaks (a message, an email body, a note): spaces within a line are tidied. */
    private fun longArg(a: Map<String, Any?>, max: Int, vararg names: String): String? =
        names.firstOrNull { a.containsKey(it) }?.let { longText(a[it], max) }

    fun number(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> when (v.toDouble()) { 1.0 -> true; 0.0 -> false; else -> null }
        is String -> when (v.trim().lowercase(Locale.ROOT)) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun text(v: Any?, max: Int): String? {
        val s = raw(v)?.replace(Regex("\\s+"), " ")?.trim()?.let(::unquote)?.trim()
        return s?.takeIf { it.isNotEmpty() }?.let { safeTake(it, max) }
    }

    private fun longText(v: Any?, max: Int): String? {
        val s = (raw(v) ?: return null).replace("\r\n", "\n").replace('\r', '\n').lines()
            .joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim().let(::unquote).trim()
        return s.takeIf { it.isNotEmpty() }?.let { safeTake(it, max) }
    }

    /** Text, or a number written out in full (a phone number sent as 5.145550101E9 is "5145550101"). */
    private fun raw(v: Any?): String? = when (v) {
        is String -> v
        is Double, is Float -> v.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        is Number -> v.toString()
        else -> null
    }

    /** One pair of quotes around the whole value comes off; a quote inside the text stays (Tell him "yes", 'sup). */
    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'') && s.count { it == s.first() } == 2) s.substring(1, s.length - 1) else s

    /** At most [max] characters, never half of an emoji (a lone surrogate would reach Cheogram as "?"). */
    fun safeTake(s: String, max: Int): String {
        if (s.length <= max) return s
        val t = s.take(max)
        return if (t.isNotEmpty() && Character.isHighSurrogate(t.last())) t.dropLast(1) else t
    }

    // ------------------------------------------------------------------ text fallback

    /** A tool call written into the reply text, and the part of the text it occupied. */
    data class TextCall(val call: RawToolCall, val range: IntRange)

    /**
     * Tool calls the model wrote as text instead of through LiteRT-LM's tool-call channel (small models
     * sometimes do). Recognises only known tool names, in three shapes:
     * - Gemma's `call:set_timer{minutes:5}` (optionally inside `<start_function_call>`...`<end_function_call>`),
     * - a JSON object `{"name":"set_timer","arguments":{"minutes":5}}` (also "parameters"/"args"),
     * - a Python-style call `set_timer(minutes=5)`, typically in a ```tool_code block.
     */
    fun parseText(text: String): List<TextCall> {
        val out = mutableListOf<TextCall>()
        val names = ToolName.entries.joinToString("|") { it.id }
        // The body runs to the first '}' outside <escape>...<escape>, so a message may contain a brace.
        Regex("""(?:<start_function_call>\s*)?call:($names)\s*\{((?:<escape>.*?<escape>|[^}])*)\}(?:\s*<end_function_call>)?""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            out += TextCall(RawToolCall(m.groupValues[1], gemmaArgs(m.groupValues[2])), m.range)
        }
        if (out.isEmpty()) {
            Regex("""\{[^{}]*"(?:name|tool|function)"\s*:\s*"($names)"[^{}]*\{[^{}]*\}[^{}]*\}|\{[^{}]*"(?:name|tool|function)"\s*:\s*"($names)"[^{}]*\}""").findAll(text).forEach { m ->
                jsonCall(m.value)?.let { out += TextCall(it, m.range) }
            }
        }
        if (out.isEmpty()) {
            Regex("""\b($names)\s*\(([^()]*)\)""").findAll(text).forEach { m ->
                out += TextCall(RawToolCall(m.groupValues[1], pythonArgs(m.groupValues[2])), m.range)
            }
        }
        return out
    }

    /**
     * Text-call mode only (the model's build refused the tool API, :brain says so per reply): the calls in a reply that is
     * nothing but calls, as the text-call hint asks. A reply with any other text (prose that explains, quotes or
     * mentions a call, screen text repeated back) yields no calls: nothing written in passing ever runs.
     */
    fun wholeReplyCalls(text: String): List<TextCall> {
        val found = parseText(text)
        if (found.isEmpty()) return emptyList()
        return if (stripText(text, found).isBlank()) found else emptyList()
    }

    /** The reply with the textual tool calls (and any code fences or markers around them) removed. */
    fun stripText(text: String, calls: List<TextCall>): String {
        var s = text
        for (c in calls.sortedByDescending { it.range.first }) s = s.removeRange(c.range)
        return s.replace(Regex("```[a-zA-Z_]*\\s*```"), "").replace("<start_function_call>", "").replace("<end_function_call>", "").trim()
    }

    private fun gemmaArgs(body: String): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        // key:value pairs; values may be wrapped in <escape>...<escape> or quotes and may contain commas then. A bare
        // value runs to the next ", key:" (not the first comma), so "message:I'm late, sorry" keeps all of it.
        val re = Regex("""(\w+)\s*:\s*(<escape>(.*?)<escape>|"((?:[^"\\]|\\.)*)"|'([^']*)'|(?:(?!,\s*\w+\s*:).)*)""", RegexOption.DOT_MATCHES_ALL)
        re.findAll(body).forEach { r ->
            val whole = r.groupValues[2]
            m[r.groupValues[1]] = when {
                whole.startsWith("<escape>") -> r.groupValues[3]
                whole.startsWith("\"") -> r.groupValues[4]
                whole.startsWith("'") -> r.groupValues[5]
                else -> scalar(whole.trim(), quoted = false)
            }
        }
        return m
    }

    private fun pythonArgs(body: String): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        Regex("""(\w+)\s*=\s*("((?:[^"\\]|\\.)*)"|'([^']*)'|[^,]+)""").findAll(body).forEach { r ->
            val v = r.groupValues[2].trim()
            m[r.groupValues[1]] = when {
                v.startsWith("\"") -> r.groupValues[3]
                v.startsWith("'") -> r.groupValues[4]
                else -> scalar(v, quoted = false)
            }
        }
        return m
    }

    private fun scalar(v: String, quoted: Boolean): Any? {
        if (quoted) return v
        val t = v.trim()
        return when {
            t.equals("true", true) || t == "True" -> true
            t.equals("false", true) -> false
            t.toLongOrNull() != null -> t.toLong()
            t.toDoubleOrNull() != null -> t.toDouble()
            else -> t
        }
    }

    private fun jsonCall(s: String): RawToolCall? = try {
        val o = JSONObject(s)
        val name = o.optString("name").ifEmpty { o.optString("tool") }.ifEmpty { o.optString("function") }
        val args = o.optJSONObject("arguments") ?: o.optJSONObject("parameters") ?: o.optJSONObject("args") ?: JSONObject()
        RawToolCall(name, Json.toMap(args))
    } catch (_: Exception) { null }
}
