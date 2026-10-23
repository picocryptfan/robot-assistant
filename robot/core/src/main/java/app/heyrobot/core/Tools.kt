// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.i18n.UiStrings
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
enum class ToolName(val id: String, private val titleMsg: Msg, val group: ToolGroup, val reads: Boolean = false, val internal: Boolean = false,
    val online: Boolean = false, val forModel: Boolean = true) {
    SET_TIMER("set_timer", ToolStrings.titleSetTimer, ToolGroup.CORE),
    SET_ALARM("set_alarm", ToolStrings.titleSetAlarm, ToolGroup.CORE),
    OPEN_APP("open_app", ToolStrings.titleOpenApp, ToolGroup.CORE),
    MAPS_SEARCH("maps_search", ToolStrings.titleMapsSearch, ToolGroup.CORE),
    MAPS_NAVIGATE("maps_navigate", ToolStrings.titleMapsNavigate, ToolGroup.CORE),
    FLASHLIGHT("flashlight", ToolStrings.titleFlashlight, ToolGroup.CORE),
    SEND_TEXT("send_text", ToolStrings.titleSendText, ToolGroup.PEOPLE),
    DRAFT_EMAIL("draft_email", ToolStrings.titleDraftEmail, ToolGroup.PEOPLE),
    LOOKUP_CONTACT("lookup_contact", ToolStrings.titleLookupContact, ToolGroup.PEOPLE, reads = true),
    /**
     * 0.8: a phone call. The dialer opens with the number (you tap Call); car mode alone calls after a spoken yes. Never
     * described to the model ([forModel]): "call Mom", "appelle maman" is recognised before it (HandsFreeRouter), so the
     * prompt doesn't grow.
     */
    CALL("call", ToolStrings.titleCall, ToolGroup.PEOPLE, forModel = false),
    ADD_NOTE("add_note", ToolStrings.titleAddNote, ToolGroup.NOTES),
    FIND_NOTES("find_notes", ToolStrings.titleFindNotes, ToolGroup.NOTES, reads = true),
    ADD_EVENT("add_event", ToolStrings.titleAddEvent, ToolGroup.CALENDAR),
    READ_CALENDAR("read_calendar", ToolStrings.titleReadCalendar, ToolGroup.CALENDAR, reads = true),
    /** 0.4.0: Open-Meteo's forecast for a place you name, your Home for weather, or where the phone is. */
    WEATHER("weather", ToolStrings.titleWeather, ToolGroup.WEATHER, reads = true, online = true),
    /** 0.4.0: storage, battery and memory, read on the phone (the apps using the most space with Usage access). */
    PHONE_STATUS("phone_status", ToolStrings.titlePhoneStatus, ToolGroup.PHONE, reads = true),
    /** 0.4.0: unload Robot's own model, or open an app's info page to force stop it (FreeUp.kt). */
    FREE_MEMORY("free_memory", ToolStrings.titleFreeMemory, ToolGroup.PHONE),
    /** 0.4.0: Android's Free up space screen, an app's cache, a large photo or video, Robot's cache. */
    FREE_STORAGE("free_storage", ToolStrings.titleFreeStorage, ToolGroup.PHONE),
    /**
     * 0.9: private web search (core web): your search service's results,
     * answered by a tool-free one-shot. Described only with Settings › Web search on; goes online like the weather.
     */
    WEB_SEARCH("web_search", ToolStrings.titleWebSearch, ToolGroup.WEB, reads = true, online = true),
    /** 1.0: a search in your browser (BrowserActions.kt): Robot sends nothing itself, never sees the results, never on the lock screen. */
    BROWSER_SEARCH("browser_search", ToolStrings.titleBrowserSearch, ToolGroup.WEB, internal = true),
    /**
     * Ask a helper (docs/ask-claude.md "When Robot needs help"): described to Gemma only with a helper on and escalation
     * not Off. Through the API it never runs as a tool (the turn hands over to the helper, EscalationPolicy decides);
     * through its app it is the hand-off, always behind a card. "ask_claude" is the name the local model sees and its
     * approval is saved under: kept from 0.3.0, so the prompt and saved settings stay the same.
     */
    ASK_HELPER("ask_claude", ToolStrings.titleAskClaude, ToolGroup.CORE, internal = true),
    /**
     * 0.7 memory (core memory/): keep something about you for later conversations, or drop it. Robot's own, under
     * Settings › Memory rather than Settings › Actions, described only with Memory on and words that call for them.
     */
    REMEMBER("remember", UiStrings.memory, ToolGroup.MEMORY, internal = true),
    FORGET("forget", UiStrings.memory, ToolGroup.MEMORY, internal = true),
    /** 0.9, the Termux agent (TermuxActions.kt): one of your scripts in ~/robot, and one short command. */
    TERMUX_SCRIPT("termux_script", ToolStrings.titleTermuxScript, ToolGroup.TERMUX, internal = true),
    TERMUX_COMMAND("termux_command", ToolStrings.titleTermuxCommand, ToolGroup.TERMUX, internal = true),
    /**
     * 1.4, Robot Files (files-1.4-plan.md §5.1, D7): two reads, Robot's own (Settings › Files governs them, never Settings ›
     * Actions or the lock screen's list). find_files gives names, drawers, dates, a few fields and totals made by code, never
     * a file's text; read_file ends the model's turn, and a tool-free FILE_ANSWER one-shot answers from one file's text.
     * Nothing a file says can move, delete or send one: those are your words and taps only (FilesRouter, the Files screens).
     */
    FIND_FILES("find_files", ToolStrings.titleFindFiles, ToolGroup.FILES, reads = true, internal = true),
    READ_FILE("read_file", ToolStrings.titleReadFile, ToolGroup.FILES, reads = true, internal = true);

    /** Its title in words without a language yet (1.1: Settings shows it with `str()`, a card's line says it in the reply language). */
    val titleText: Phrase get() = titleMsg()

    /** Its title in English ("Timers"): what the model and History's records read. */
    val title: String get() = titleText.en()

    /** A Termux tool: Settings › Termux and TermuxPolicy decide about it (ApprovalGate's Termux branch). */
    val termux: Boolean get() = group == ToolGroup.TERMUX

    companion object {
        fun of(id: String?): ToolName? = entries.firstOrNull { it.id == id?.trim()?.lowercase(Locale.ROOT) }
    }
}

/**
 * [brief]: 1.2 (design Step 3), the parameter's text in the compact prompt of a slow path ([ToolSpec.compact]): its
 * [description] without the examples, and shorter, keeping what the model must know to fill it (a range, the words it
 * takes, "omit"); null keeps [description] as it is.
 */
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean, val brief: String? = null)

data class ToolSpec(val tool: ToolName, val description: String, val params: List<ToolParam>) {
    init { require(params.size <= MAX_PARAMS) { "tools take at most $MAX_PARAMS arguments" } } // l10n: not text (a programming error)

    companion object {
        /** Phase 1 allowed two; draft_email and add_event need three (to, subject, body; title, when, length). */
        const val MAX_PARAMS = 3
    }

    /**
     * 1.2: this tool as the compact prompt describes it (PrefillBudget.compact): every parameter's text in its [ToolParam.brief]
     * form; the name, the tool's own line, the parameters' names, types, order and required list exactly as they are, so a
     * call is read and checked the same way (HelperTools.check, ToolCalls).
     */
    fun compact(): ToolSpec = copy(params = params.map { it.copy(description = it.brief ?: it.description, brief = null) })

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
            ToolParam("minutes", "integer", "Whole minutes of the timer, e.g. 5", false, "Whole minutes"),
            ToolParam("seconds", "integer", "Extra seconds of the timer, e.g. 30", false, "Extra seconds"),
        )),
        ToolSpec(ToolName.SET_ALARM, "Set an alarm in the Clock app for the next time the clock shows hour:minute.", listOf(
            ToolParam("hour", "integer", "Hour on a 24-hour clock, 0 to 23", true),
            ToolParam("minute", "integer", "Minute, 0 to 59", false),
        )),
        ToolSpec(ToolName.OPEN_APP, "Open an installed app by its name.", listOf(
            ToolParam("name", "string", "The app's name as shown on the home screen, e.g. Signal", true, "The app's name"),
        )),
        ToolSpec(ToolName.MAPS_SEARCH, "Search for a place or a kind of place in Organic Maps.", listOf(
            ToolParam("query", "string", "What to look for, e.g. pharmacy or Central Station", true, "What to look for"),
        )),
        ToolSpec(ToolName.MAPS_NAVIGATE, "Find a destination in Organic Maps to get directions to it.", listOf(
            ToolParam("destination", "string", "Where to go, e.g. an address or a place name", true, "Where to go"),
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
            // 1.0.2: the subject (optional) after the body, which sorts before it, as for the weather.
            ToolParam("to", "string", "Name or email address", true),
            ToolParam("body", "string", "The text", true),
            ToolParam("subject", "string", "Subject", false),
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
            ToolParam("title", "string", "What, e.g. Dentist", true, "What"),
            ToolParam("when", "string", "As said, e.g. Friday 9 to 10:30", true, "As said"),
            ToolParam("duration_minutes", "integer", "Length if no end was said", false),
        )),
        ToolSpec(ToolName.READ_CALENDAR, "List calendar events for a day or a week.", listOf(
            ToolParam("day", "string", "e.g. today, Friday, next week", true, "As said"),
        )),
        // 0.4.0, as short as the others: no place is where you are (or your Home for weather), no day is today. 1.0.2: the
        // day first, as Gemma 4 writes a call's arguments (in alphabetical order): with the place declared first, constrained
        // decoding lost it whenever a day came with it (ActionOutcomeTest).
        ToolSpec(ToolName.WEATHER, "Weather forecast (online).", listOf(
            ToolParam("day", "string", "e.g. tomorrow, Friday, this week", false, "As said"),
            ToolParam("place", "string", "Town, or omit for here", false),
        )),
        ToolSpec(ToolName.PHONE_STATUS, "Read this phone's storage, battery and memory.", listOf(
            ToolParam("about", "string", "storage, battery, memory or all", false),
        )),
        ToolSpec(ToolName.FREE_MEMORY, "Free memory: unload Robot's model, or open an app's info to force stop it.", listOf(
            ToolParam("app", "string", "App to stop; omit to unload Robot's model", false),
        )),
        ToolSpec(ToolName.FREE_STORAGE, "Help free storage.", listOf(
            ToolParam("what", "string", "android (its Free up space), apps (an app's cache), files (big photos, videos) or robot (Robot's cache)", false,
                "android, apps, files or robot"),
        )),
        // 0.9, the design's two parameters: Gemma's turn ends at the call and a one-shot answers from the results.
        ToolSpec(ToolName.WEB_SEARCH, "Search the web (online).", listOf(
            ToolParam("query", "string", "Short search words", true),
            ToolParam("depth", "string", "quick, or read to open pages", false),
        )),
        // The model-facing words are kept from 0.3.0 (changing them would change the local model's prompt).
        ToolSpec(ToolName.ASK_HELPER, "Ask Claude, a stronger AI online, instead of guessing.", listOf(
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
        // 1.4, Robot Files (F9): both reads, as short as find_notes and web_search (every token is paid on every rebuild).
        ToolSpec(ToolName.FIND_FILES, "Find the person's receipts, bills and documents.", listOf(
            ToolParam("query", "string", "What to find, e.g. Costco receipt", true, "What to find"),
            ToolParam("drawer", "string", "A drawer, or omit", false),
            ToolParam("when", "string", "e.g. August, last year", false, "As said"),
        )),
        ToolSpec(ToolName.READ_FILE, "Answer a question from one file's text.", listOf(
            ToolParam("file", "string", "A number find_files gave", true),
            ToolParam("question", "string", "The question", true),
        )),
    ) + BrowserCalls.SPEC + TermuxCalls.SPECS

    fun of(tool: ToolName): ToolSpec = ALL.first { it.tool == tool }

    /** 1.2: [tool]'s description as LiteRT-LM gets it, in the compact form on a slow path ([compact], [ToolSpec.compact]). */
    fun json(tool: ToolName, compact: Boolean = false): String = of(tool).let { if (compact) it.compact() else it }.openApiJson()
}

/**
 * A validated request to do something on the phone. 1.1: its card's words come as words without a language yet
 * ([summary], [bodyText], [confirmText], rendered in the turn's languages); [describe], [body] and [confirmLabel] are
 * their English (History's record of 1.0, the routines' lists). An action of another area that gives only the English
 * shows it as it is until it moves.
 */
sealed class Action {
    abstract val tool: ToolName
    /**
     * Plain language for the confirmation card and the log-free history ("Set a timer for 5 minutes"). The person or
     * thing it is about may be marked **bold** and the calendar *italic* (the card renders light Markdown).
     */
    abstract fun describe(): String
    /** [describe] in words without a language yet: the card's summary, in the reply language. */
    open fun summary(): Phrase = Phrase.plain(describe())
    /** What the card shows under the summary: a message's full text, an email's subject and body, a note. */
    open fun body(): String? = null
    /** [body] in words without a language yet (an email's "Subject:" line; your words stay yours). */
    open fun bodyText(): Phrase? = body()?.let(Phrase::plain)
    /** The card's button when it asks first ("Open in Cheogram"); null = "Do it". */
    open val confirmLabel: String? get() = null
    /** [confirmLabel] in words without a language yet: a button, in the menus' language. */
    open val confirmText: Phrase? get() = confirmLabel?.let(Phrase::plain)
    /**
     * It goes to the internet when it runs: the weather, or a maps request for the nearest place (0.4.0). Stop ends it at
     * once and it has a deadline (the panel's OnlineRun).
     */
    open val online: Boolean get() = tool.online
    /** 0.9: it runs for a while and Stop ends it (a Termux run: Stop ends its whole process tree), like an online lookup. */
    open val stoppable: Boolean get() = false
    /** It may run on the lock screen ([lockedTools]: Settings › Safety & privacy › "Works on the lock screen"). */
    open fun worksLocked(lockedTools: Set<ToolName>): Boolean = tool in lockedTools

    /** A card whose words are this package's: [describe] and [confirmLabel] are [summary]'s and [confirmText]'s English. */
    sealed class Worded : Action() {
        abstract override fun summary(): Phrase
        override fun describe(): String = summary().en()
        override val confirmLabel: String? get() = confirmText?.en()
        override val confirmText: Phrase? get() = null
    }

    data class SetTimer(val seconds: Long) : Worded() {
        override val tool get() = ToolName.SET_TIMER
        override fun summary() = ToolStrings.setTimer("duration" to Arg.Seconds(seconds))
    }

    data class SetAlarm(val hour: Int, val minute: Int) : Worded() {
        override val tool get() = ToolName.SET_ALARM
        override fun summary() = ToolStrings.setAlarm("time" to EventText.clock(hour, minute))
    }

    /**
     * [name] is what the model asked for until the app process resolves it to an installed app: then [name] is
     * that app's real label and [key] its launcher activity (what the card shows is what opens).
     */
    data class OpenApp(val name: String, val key: String? = null) : Worded() {
        override val tool get() = ToolName.OPEN_APP
        override fun summary() = ToolStrings.openApp("appName" to name)
    }

    /**
     * [nearest]: "the nearest/closest …" (0.4.0): with Settings › Actions › "Find the nearest place online" allowing it, Robot
     * finds the closest one with OpenStreetMap and opens Organic Maps' route to it (Nearby); otherwise the search, as before.
     * [consent]: the first-use card's answer it carries (Nearby.prepare), kept when it runs.
     */
    data class MapsSearch(val query: String, val nearest: Boolean = false, val consent: NearestConsent? = null) : Worded() {
        override val tool get() = ToolName.MAPS_SEARCH
        override fun summary() = if (nearest) Nearby.describeText(query) else ToolStrings.mapsSearch("query" to query)
        override val online get() = nearest
    }

    /**
     * Organic Maps can't route to a name: this opens a search for [destination], and you pick it and tap Route; or, for the
     * [nearest] one, the route to the closest place OpenStreetMap knows ([MapsSearch.nearest]).
     */
    data class MapsNavigate(val destination: String, val nearest: Boolean = false, val consent: NearestConsent? = null) : Worded() {
        override val tool get() = ToolName.MAPS_NAVIGATE
        override fun summary() = if (nearest) Nearby.describeText(destination) else when (SavedPlace.of(destination)) {
            // 1.1, D4: the route to your saved Home or Work.
            app.heyrobot.core.car.NavDestination.Home -> ToolStrings.routeHome()
            app.heyrobot.core.car.NavDestination.Work -> ToolStrings.routeWork()
            else -> ToolStrings.mapsNavigate("query" to destination)
        }
        override val online get() = nearest
    }

    data class Flashlight(val on: Boolean) : Worded() {
        override val tool get() = ToolName.FLASHLIGHT
        override fun summary() = if (on) ToolStrings.flashlightOn() else ToolStrings.flashlightOff()
    }

    /** [to] as the model gave it until the app resolves it to a [target] (what the card shows is where it goes). */
    data class SendText(val to: String, val message: String, val target: TextTarget? = null) : Worded() {
        override val tool get() = ToolName.SEND_TEXT
        override fun summary(): Phrase = when {
            target == null -> ToolStrings.textTo("to" to to)
            target.via == TextVia.CHEOGRAM_PICK -> ToolStrings.textChoose("why" to target.shown)
            target.via == TextVia.CHEOGRAM_LIST -> ToolStrings.textPick("why" to target.shown)
            target.name == null -> ToolStrings.textNumber("to" to target.display, "appName" to target.app)
            else -> learned(ToolStrings.textPerson("name" to target.name, "to" to target.display, "appName" to target.app), target.from?.learned == true)
        }
        override fun body() = message
        /** The button names the person too: the card's top line may be out of sight on a long message. */
        override val confirmText get() = target?.let {
            when (it.via) {
                TextVia.CHEOGRAM_PICK -> ToolStrings.chooseInCheogram()
                TextVia.CHEOGRAM_LIST -> ToolStrings.pickInCheogram()
                else -> ToolStrings.openInFor("appName" to it.app, "who" to (it.name ?: it.display))
            }
        }
    }

    data class DraftEmail(val to: String, val subject: String, val text: String, val target: MailTarget? = null) : Worded() {
        override val tool get() = ToolName.DRAFT_EMAIL
        override fun summary(): Phrase {
            val t = target ?: return ToolStrings.emailTo("to" to to)
            val p = when {
                t.name == null && t.appName == null -> ToolStrings.emailTo("to" to t.address)
                t.name == null -> ToolStrings.emailToIn("to" to t.address, "appName" to t.appName)
                t.appName == null -> ToolStrings.emailPerson("name" to t.name, "address" to t.address)
                else -> ToolStrings.emailPersonIn("name" to t.name, "address" to t.address, "appName" to t.appName)
            }
            return learned(p, t.from?.learned == true)
        }
        override fun body() = bodyText()?.en().orEmpty()
        override fun bodyText(): Phrase = Phrase.Join(listOfNotNull(subject.takeIf { it.isNotBlank() }?.let { ToolStrings.subject("subject" to it) },
            text.takeIf { it.isNotBlank() }?.let(Phrase::plain)), "\n\n")
        override val confirmText get() = target?.let { t ->
            val who = t.name ?: t.address
            if (t.appName != null) ToolStrings.openInFor("appName" to t.appName, "who" to who) else ToolStrings.chooseEmailAppFor("who" to who)
        }
    }

    data class LookupContact(val name: String) : Worded() {
        override val tool get() = ToolName.LOOKUP_CONTACT
        override fun summary() = ToolStrings.lookUp("name" to name)
    }

    /**
     * 0.8: a call to [to] (a name or a number, as said). Once the app found it ([number], People and contacts, numbers
     * only): [who], its [label] ("Mobile") and whether the name was [learned] from a Cheogram notification. [emergency]:
     * an emergency number, which only ever opens the dialer. [direct]: car mode's spoken read-back and yes, with the
     * Phone permission and "Call directly after a spoken yes", places the call (ACTION_CALL); otherwise the dialer opens
     * with the number and you tap Call (ACTION_DIAL).
     */
    data class Call(val to: String, val number: String? = null, val who: String? = null, val label: String? = null, val learned: Boolean = false,
        val emergency: Boolean = false, val direct: Boolean = false) : Worded() {
        override val tool get() = ToolName.CALL
        override fun summary(): Phrase {
            val n = number ?: return ToolStrings.callTo("to" to to)
            val name = who?.takeIf { it.isNotBlank() } ?: return learned(ToolStrings.callTo("to" to n), learned)
            return learned(if (label == null) ToolStrings.callPerson("name" to name, "number" to n)
                else ToolStrings.callPersonLabel("name" to name, "label" to label, "number" to n), learned)
        }
        override val confirmText get() = if (direct && !emergency) UiStrings.call() else ToolStrings.openDialer()
    }

    data class AddNote(val text: String, val title: String? = null) : Worded() {
        override val tool get() = ToolName.ADD_NOTE
        override fun summary() = if (title == null) ToolStrings.saveNote() else ToolStrings.saveNoteTitled("title" to title)
        override fun body() = text
    }

    data class FindNotes(val query: String) : Worded() {
        override val tool get() = ToolName.FIND_NOTES
        override fun summary() = ToolStrings.findNotes("query" to query)
    }

    /**
     * [whenText] in the person's words until the app reads it into a [plan] (the dates and the calendar it goes to). 1.1:
     * [alert], a dated reminder ("remind me to … on the 20th"): the event carries an alert (EventPlan.alertAt), and its card
     * always asks first (AskWhy.REMINDER).
     */
    data class AddEvent(val title: String, val whenText: String, val minutes: Int? = null, val plan: EventPlan? = null, val alert: Boolean = false) : Worded() {
        override val tool get() = ToolName.ADD_EVENT
        override fun summary(): Phrase {
            val p = plan ?: return ToolStrings.addEventAsSaid("title" to title, "when" to whenText)
            val span = EventText.spanText(p.start, p.end, p.allDay, p.currentYear)
            val alertAt = p.alertAt?.let { EventText.clock(it.hour, it.minute) }
            return when {
                p.calendarName != null && alertAt != null -> ToolStrings.addEventToAlert("title" to title, "when" to span, "calendar" to p.calendarName, "alert" to alertAt)
                p.calendarName != null -> ToolStrings.addEventTo("title" to title, "when" to span, "calendar" to p.calendarName)
                alertAt != null -> ToolStrings.addEventInAppAlert("title" to title, "when" to span, "alert" to alertAt)
                else -> ToolStrings.addEventInApp("title" to title, "when" to span)
            }
        }
        override val confirmText get() = plan?.let { if (it.calendarId == null) ToolStrings.openCalendarApp() else ToolStrings.addTo("calendar" to it.calendarName) }
    }

    /**
     * The app route: open a helper's app with [text] (your question, or a short summary of the conversation; empty: the
     * app opens without text). You press Send there; Robot steps aside. 1.0.0, any helper with an app: [helper] the one
     * meant (null: the one Robot checks with); [app] its package and [appName] ("ChatGPT"), filled in from your settings
     * when the card is made (ToolExecutor), never by a model.
     */
    data class AskHelper(val text: String, val helper: String? = null, val app: String? = null, val appName: String? = null) : Worded() {
        override val tool get() = ToolName.ASK_HELPER
        override fun summary() = if (text.isBlank()) ToolStrings.openHelperApp("appName" to where) else ToolStrings.openHelperAppWith("appName" to where, "text" to short(text))
        private val where: Phrase get() = appName?.let { ToolStrings.theNamedApp("name" to it) } ?: ToolStrings.theHelpersApp()
        override fun body() = text.takeIf { it.length > SHORT }
        override val confirmText get() = ToolStrings.openTheApp()

        private companion object {
            const val SHORT = 120
            fun short(t: String) = t.replace(Regex("\\s+"), " ").trim().let { if (it.length <= SHORT) it else ToolCalls.safeTake(it, SHORT - 1).trimEnd() + "…" }
        }
    }

    /**
     * [place] as the model gave it (null: where you are). The app reads it into [where] (here, your Home for weather or
     * a named place) and [day] into the [range] of days, before anything goes online.
     */
    data class Weather(val place: String?, val day: String?, val where: WeatherWhere? = null, val range: DayRange? = null) : Worded() {
        override val tool get() = ToolName.WEATHER
        override fun summary(): Phrase {
            val on: Phrase? = range?.let { EventText.rangeText(it) } ?: day?.let(Phrase::plain)
            return when (where ?: WeatherAsk.where(place).first) {
                WeatherWhere.HERE -> if (on == null) ToolStrings.weatherHere() else ToolStrings.weatherHereFor("when" to on)
                WeatherWhere.HOME -> if (on == null) ToolStrings.weatherHome() else ToolStrings.weatherHomeFor("when" to on)
                WeatherWhere.NAMED -> {
                    val named = WeatherAsk.where(place).second ?: place
                    if (on == null) ToolStrings.weatherIn("place" to named) else ToolStrings.weatherInFor("place" to named, "when" to on)
                }
            }
        }
    }

    /**
     * remember (0.7): [text] to keep, a [kind], an end ([until]). The card shows the text in its body only: its summary,
     * which History keeps, never holds it ([MemoryHygiene]).
     */
    data class Remember(val text: String, val kind: MemoryKind = MemoryKind.FACT,
        val until: Until? = null) : Worded() {
        override val tool get() = ToolName.REMEMBER
        override fun summary() = if (kind == MemoryKind.PREFERENCE) ToolStrings.rememberPreference() else ToolStrings.rememberThis()
        override fun body() = text
        override val confirmText get() = UiStrings.remember()
        override fun toString(): String = "Remember(kind=$kind, until=$until, chars=${text.length})" // l10n: not text (log only)
    }

    /**
     * forget (0.7): [query] as the model gave it, until the app finds the items it means ([ids], with their words in
     * [shown] for the card's body only).
     */
    data class Forget(val query: String, val ids: List<String> = emptyList(), val shown: String? = null) : Worded() {
        override val tool get() = ToolName.FORGET
        override fun summary() = if (ids.size > 1) ToolStrings.forgetThings(ids.size) else ToolStrings.forgetOne()
        override fun body() = shown
        override val confirmText get() = ToolStrings.forget()
        override fun toString(): String = "Forget(ids=${ids.size}, chars=${query.length})" // l10n: not text (log only)
    }

    /** phone_status: what was asked about; the app process reads it. */
    data class PhoneCheck(val part: PhonePart) : Worded() {
        override val tool get() = ToolName.PHONE_STATUS
        override fun summary() = PhoneStatus.summaryText(part)
    }

    /**
     * free_memory: [app] as the model named it (null: unload Robot's own model) until the app process finds it among the
     * installed apps ([pkg]); then [app] is its real label.
     */
    data class FreeMemory(val app: String?, val pkg: String? = null) : Worded() {
        override val tool get() = ToolName.FREE_MEMORY
        override fun summary() = when {
            pkg == SettingsPage.USAGE_ACCESS -> ToolStrings.openUsageAccess()
            app == null -> ToolStrings.unloadModel()
            else -> ToolStrings.forceStop("appName" to app)
        }
        override val confirmText get() = when {
            pkg == SettingsPage.USAGE_ACCESS -> UiStrings.open()
            app == null -> ToolStrings.unload()
            else -> ToolStrings.openAppInfo()
        }

        companion object {
            /** 0.7.0: the "free up memory" card's button without Usage access ([pkg] = SettingsPage.USAGE_ACCESS) opens Android's page for it. */
            const val USAGE_ACCESS_LABEL = "Usage access" // l10n: not text (the action's key: its card names the page itself)
        }
    }

    /**
     * free_storage. [target]: the app's package ([StorageWay.APPS]) or the file's content URI ([StorageWay.FILES]) once
     * picked on the card; [label] the app's name; [bytes] Robot's cache ([StorageWay.ROBOT]); [summary] what the card
     * says of a picked file, without its name (FreeUp.fileSummary), and [summaryText] the same words without a language yet.
     */
    data class FreeStorage(val way: StorageWay, val target: String? = null, val label: String? = null, val bytes: Long? = null,
        val summary: String? = null, val summaryText: Phrase? = null) : Worded() {
        override val tool get() = ToolName.FREE_STORAGE
        override fun summary(): Phrase = when (way) {
            StorageWay.ANDROID -> ToolStrings.openFreeUp()
            StorageWay.APPS -> if (target == null) ToolStrings.chooseApp() else ToolStrings.clearAppCache("appName" to label)
            StorageWay.FILES -> summary?.let { s -> summaryText?.takeIf { it.en() == s } ?: Phrase.plain(s) } ?: ToolStrings.chooseFile()
            StorageWay.ROBOT -> bytes?.let { ToolStrings.clearRobotCacheSize("size" to PhoneStatus.sizeText(it)) } ?: ToolStrings.clearRobotCache()
        }
        override val confirmText get() = when (way) {
            StorageWay.ANDROID -> UiStrings.open()
            StorageWay.APPS -> ToolStrings.openAppInfo()
            StorageWay.FILES -> UiStrings.delete()
            StorageWay.ROBOT -> ToolStrings.clear()
        }
    }

    /**
     * web_search (0.9): [query] as the model wrote it (or your own words, "search the web for …"), [depth] "read" to open
     * result pages. [consent]: the first search's card answer it carries (core web WebConsent), kept when it runs; [query]
     * may be changed on the card's way (your Home for weather town for "near me").
     */
    data class WebSearch(val query: String, val depth: String? = null, val consent: app.heyrobot.core.web.WebConsentChoice? = null) : Worded() {
        override val tool get() = ToolName.WEB_SEARCH
        override fun summary() = ToolStrings.searchWeb("query" to query)
        override val confirmText get() = ToolStrings.search()
    }

    /**
     * find_files (1.4, F9): [query] as the model wrote it (or your words), with its [drawer] and [whenText] when given. Its
     * words never reach a log ([toString]).
     */
    data class FindFiles(val query: String, val drawer: String? = null, val whenText: String? = null) : Worded() {
        override val tool get() = ToolName.FIND_FILES
        override fun summary() = ToolStrings.findFiles()
        override fun toString(): String = "FindFiles(chars=${query.length}, drawer=${drawer != null}, when=${whenText != null})" // l10n: not text (log only)
    }

    /**
     * read_file (1.4, F9): the file [file] names (a number find_files gave in this conversation, or words) and the [question]
     * to answer from its text. Its words never reach a log ([toString]).
     */
    data class ReadFile(val file: String, val question: String) : Worded() {
        override val tool get() = ToolName.READ_FILE
        override fun summary() = ToolStrings.readFile()
        override fun toString(): String = "ReadFile(chars=${question.length})" // l10n: not text (log only)
    }

    /** [day] in the person's words until the app reads it into a [range]. */
    data class ReadCalendar(val day: String, val range: DayRange? = null) : Worded() {
        override val tool get() = ToolName.READ_CALENDAR
        override fun summary() = ToolStrings.checkCalendar("when" to (range?.let { EventText.rangeText(it) } ?: Phrase.plain(day)))
    }

    companion object {
        /** After a person whose name was learned from a Cheogram notification: whoever sent it chose that name. */
        fun learned(p: Phrase, learned: Boolean): Phrase = if (learned) ToolStrings.learned("what" to p) else p
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
    /** 1.1: [display] in words without a language yet, when it says why no contact fitted (the card says it in the reply language). */
    val displayText: Phrase? = null,
) {
    /** [display] as the card's summary says it. */
    val shown: Phrase get() = displayText?.takeIf { it.en() == display } ?: Phrase.plain(display)

    /** [appName] as a sentence names it: an app's own name, or "the SMS app" in the reader's language. */
    val app: Phrase get() = if (appName == ToolStrings.theSmsApp().en()) ToolStrings.theSmsApp() else Phrase.plain(appName)

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
    /** 1.1, a dated reminder ([Action.AddEvent.alert]): when its alert rings (EventAlert); null: no alert. */
    val alertAt: ZonedDateTime? = null,
)

sealed class ParsedCall {
    data class Ok(val action: Action) : ParsedCall()
    /** [tool] is the name the model used (maybe unknown); [reason] goes back to the model (English). */
    data class Invalid(val tool: String, val reason: String) : ParsedCall() {
        /** 1.1: [reason] in words without a language yet: the card says it in the reply language. */
        var why: Phrase = Phrase.plain(reason)
            private set

        constructor(tool: String, why: Phrase) : this(tool, why.en()) {
            this.why = why
        }
    }
}

/** A tool call as the model produced it: a name and loosely typed arguments. */
data class RawToolCall(val name: String, val args: Map<String, Any?>)

object Durations {
    const val MAX_TIMER_S = 24 * 3600L

    /** "1 hour and 5 minutes", "2 hours, 5 minutes and 3 seconds" (core Fmt's English; [Arg.Seconds] in a sentence). */
    fun spoken(seconds: Long): String = Fmt.seconds(seconds, Lang.EN)
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
        val tool = ToolName.of(call.name) ?: return ParsedCall.Invalid(call.name.take(40), ToolStrings.unknownTool())
        val a = call.args.mapKeys { it.key.trim().lowercase(Locale.ROOT) }
        fun bad(why: Phrase) = ParsedCall.Invalid(tool.id, why)
        fun number(arg: String) = bad(ToolStrings.mustBeNumber("arg" to arg))
        fun required(arg: String) = bad(ToolStrings.isRequired("arg" to arg))
        return when (tool) {
            ToolName.SET_TIMER -> {
                val min = numberArg(a, "minutes", "minute", "mins", "min") ?: return number("minutes")
                val sec = numberArg(a, "seconds", "second", "secs", "sec") ?: return number("seconds")
                if ((min.value ?: 0.0) < 0 || (sec.value ?: 0.0) < 0) return bad(ToolStrings.timerNegative())
                val total = ((min.value ?: 0.0) * 60 + (sec.value ?: 0.0)).roundToLong()
                when {
                    total <= 0 -> bad(ToolStrings.timerLength())
                    total > Durations.MAX_TIMER_S -> bad(ToolStrings.timerMax())
                    else -> ParsedCall.Ok(Action.SetTimer(total))
                }
            }
            ToolName.SET_ALARM -> {
                val rawHour = a["hour"] ?: a["hours"]
                // "7:30" in the hour argument: small models do this.
                val clock = (rawHour as? String)?.let { Regex("""^\s*(\d{1,2})\s*[:.h]\s*(\d{2})\s*$""").find(it) }
                val minuteArg = numberArg(a, "minute", "minutes", "min", "mins") ?: return number("minute")
                val hour = if (clock != null) clock.groupValues[1].toDouble() else {
                    val h = numberArg(a, "hour", "hours") ?: return number("hour")
                    h.value ?: return required("hour")
                }
                val minute = clock?.groupValues?.get(2)?.toDouble() ?: minuteArg.value ?: 0.0
                if (hour % 1.0 != 0.0 || minute % 1.0 != 0.0) return bad(ToolStrings.wholeHourMinute())
                if (hour !in 0.0..23.0) return bad(ToolStrings.hourRange())
                if (minute !in 0.0..59.0) return bad(ToolStrings.minuteRange())
                ParsedCall.Ok(Action.SetAlarm(hour.toInt(), minute.toInt()))
            }
            ToolName.OPEN_APP -> textArg(a, 60, "name", "app", "app_name")?.let { ParsedCall.Ok(Action.OpenApp(it)) }
                ?: required("name")
            // Without "near me" (MapsQuery): the card, the record and what Organic Maps searches are the same words.
            // "The nearest …" is kept as a flag: the app may find the closest one online (Nearby).
            ToolName.MAPS_SEARCH -> textArg(a, MAX_TEXT, "query", "q", "place")?.let { ParsedCall.Ok(Action.MapsSearch(MapsQuery.clean(it), NearestAsk.said(it))) }
                ?: required("query")
            ToolName.MAPS_NAVIGATE -> textArg(a, MAX_TEXT, "destination", "to", "place")?.let { ParsedCall.Ok(Action.MapsNavigate(MapsQuery.clean(it), NearestAsk.said(it))) }
                ?: required("destination")
            ToolName.FLASHLIGHT -> {
                val present = listOf("on", "state", "enabled").filter { a.containsKey(it) }
                val values = present.map { bool(a[it]) }
                if (values.isEmpty() || values.any { it == null }) bad(ToolStrings.trueOrFalse())
                else if (values.distinct().size > 1) bad(ToolStrings.trueOrFalse())
                else ParsedCall.Ok(Action.Flashlight(values.first()!!))
            }
            ToolName.SEND_TEXT -> {
                val to = recipient(textArg(a, MAX_NAME, "to", "recipient", "contact", "name", "number")) ?: return bad(ToolStrings.noRecipient())
                val message = longArg(a, MAX_MESSAGE, "message", "text", "body") ?: return required("message")
                ParsedCall.Ok(Action.SendText(to, message))
            }
            ToolName.DRAFT_EMAIL -> {
                val to = recipient(textArg(a, MAX_NAME, "to", "recipient", "email", "address")) ?: return bad(ToolStrings.noRecipient())
                val subject = textArg(a, MAX_TEXT, "subject", "title").orEmpty()
                val body = longArg(a, MAX_LONG, "body", "text", "message").orEmpty()
                if (subject.isEmpty() && body.isEmpty()) return bad(ToolStrings.emailBody())
                ParsedCall.Ok(Action.DraftEmail(to, subject, body))
            }
            ToolName.LOOKUP_CONTACT -> textArg(a, MAX_NAME, "name", "query", "contact", "who")?.let { ParsedCall.Ok(Action.LookupContact(it)) }
                ?: required("name")
            ToolName.CALL -> recipient(textArg(a, MAX_NAME, "to", "name", "who", "number", "contact", "recipient"))?.let { ParsedCall.Ok(Action.Call(it)) }
                ?: bad(ToolStrings.noRecipient())
            ToolName.ADD_NOTE -> {
                val text = longArg(a, MAX_LONG, "text", "note", "content", "body") ?: return required("text")
                ParsedCall.Ok(Action.AddNote(text, textArg(a, MAX_TITLE, "title")))
            }
            ToolName.FIND_NOTES -> textArg(a, MAX_NAME, "query", "q", "text", "search")?.let { ParsedCall.Ok(Action.FindNotes(it)) }
                ?: required("query")
            ToolName.ADD_EVENT -> {
                val title = textArg(a, MAX_TITLE, "title", "name", "event", "what") ?: return required("title")
                // Small models split the date and the time ({date: Tuesday, time: 3pm}, {start, end}): all of it counts,
                // in the order date, day, when, start, time, then "to <end>".
                val parts = listOf("date", "day", "when", "start", "time").filter { a.containsKey(it) }.map { text(a[it], MAX_NAME) ?: return bad(ToolStrings.mustBeText("arg" to it)) }
                if (parts.isEmpty()) return required("when")
                val endPart = if (a.containsKey("end")) text(a["end"], MAX_NAME) ?: return bad(ToolStrings.mustBeText("arg" to "end")) else null
                val whenText = (parts.joinToString(" ") + (endPart?.let { " to $it" } ?: "")).take(MAX_NAME)
                val minutes = numberArg(a, "duration_minutes", "duration", "minutes", "length") ?: return number("duration_minutes")
                val m = minutes.value
                if (m != null && (m % 1.0 != 0.0 || m < 1 || m > MAX_EVENT_MINUTES)) return bad(ToolStrings.durationRange("max" to MAX_EVENT_MINUTES.toString()))
                // 1.1: a dated reminder's alert (the panel's own call from your words; a model's only adds a card).
                ParsedCall.Ok(Action.AddEvent(title, whenText, m?.toInt(), alert = bool(a["alert"]) == true))
            }
            // Empty: "go get Jarvis" with nothing to summarise opens the app without text.
            // 1.0.0: [helper] names one of your helpers by id (the panel's hand-off to the one you called); the app it opens is
            // always looked up from your settings when the card is made, never taken from the call.
            ToolName.ASK_HELPER -> ParsedCall.Ok(Action.AskHelper(longArg(a, AppHandOff.MAX_CHARS, "question", "query", "q", "text", "prompt").orEmpty(),
                helper = textArg(a, 16, "helper")?.takeIf { Regex("[a-z0-9_]{1,16}").matches(it) }))
            // Both optional: no place is where you are, no day is today.
            ToolName.WEATHER -> ParsedCall.Ok(Action.Weather(textArg(a, MAX_NAME, "place", "location", "city", "where", "town"),
                textArg(a, MAX_NAME, "day", "date", "when", "time", "days")))
            ToolName.REMEMBER -> {
                val text = longArg(a, MemoryLimits.MAX_CHARS, "text", "fact", "memory", "what", "content", "note")
                    ?.replace(Regex("\\s+"), " ")?.trim() ?: return required("text")
                val kind = when (textArg(a, MAX_NAME, "kind", "type")?.lowercase(Locale.ROOT)) {
                    "preference", "pref", "preferences" -> MemoryKind.PREFERENCE
                    else -> if (MemoryCommands.prefers(text)) MemoryKind.PREFERENCE else MemoryKind.FACT
                }
                ParsedCall.Ok(Action.Remember(text, kind, Until.of(textArg(a, MAX_NAME, "until", "expires", "end"))))
            }
            ToolName.FORGET -> textArg(a, MAX_NAME, "query", "text", "what", "q", "about")?.let { ParsedCall.Ok(Action.Forget(it)) }
                ?: required("query")
            // The query as written (QueryCheck decides later whether it may go), and the depth if it gave one.
            ToolName.WEB_SEARCH -> textArg(a, MAX_TEXT, "query", "q", "search", "text", "question")
                ?.let { ParsedCall.Ok(Action.WebSearch(it, textArg(a, 20, "depth", "mode"))) } ?: required("query")
            // Anything it doesn't name (or nothing) is all of it.
            ToolName.PHONE_STATUS -> ParsedCall.Ok(Action.PhoneCheck(PhonePart.of(textArg(a, MAX_NAME, "about", "part", "what", "topic", "kind"))))
            // Robot's own model when no app (or Robot itself) is named.
            ToolName.FREE_MEMORY -> ParsedCall.Ok(Action.FreeMemory(FreeUp.appOrNull(textArg(a, MAX_NAME, "app", "name", "application", "package"))))
            // Android's own screen unless it says apps, files or Robot.
            ToolName.FREE_STORAGE -> ParsedCall.Ok(Action.FreeStorage(StorageWay.of(textArg(a, MAX_NAME, "what", "way", "kind", "target", "how"))))
            // 0.9: a command's spaces and quotes are kept as written (TermuxActions.kt).
            ToolName.TERMUX_SCRIPT, ToolName.TERMUX_COMMAND -> TermuxCalls.parse(tool, a)
            ToolName.BROWSER_SEARCH -> BrowserCalls.parse(a) { m, max, names -> textArg(m, max, *names) }
            // 1.4, Robot Files (F9): the words as written; FileSearch reads them, and the app resolves a file only in its index.
            ToolName.FIND_FILES -> {
                val query = textArg(a, MAX_TEXT, "query", "q", "search", "what", "text") ?: return required("query")
                ParsedCall.Ok(Action.FindFiles(query, textArg(a, 40, "drawer", "folder"), textArg(a, MAX_NAME, "when", "date", "day", "period", "time")))
            }
            ToolName.READ_FILE -> {
                val file = textArg(a, MAX_NAME, "file", "number", "which", "id", "name") ?: return required("file")
                val question = textArg(a, app.heyrobot.core.files.FilePrompt.QUESTION_CHARS, "question", "q", "ask", "query", "text") ?: return required("question")
                ParsedCall.Ok(Action.ReadFile(file, question))
            }
            // No day said: today (the person asked "what's on my calendar").
            ToolName.READ_CALENDAR -> {
                val present = listOf("day", "date", "when", "range").firstOrNull { a.containsKey(it) }
                val day = if (present == null) "today" else text(a[present], MAX_NAME) ?: return bad(ToolStrings.dayText())
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

    /**
     * 1.0.2: what the model is told when it named no one (or only a placeholder): it asks who, never guesses. 1.1: the
     * English of ToolStrings.noRecipient (the card says it in the reply language).
     */
    val NO_RECIPIENT: String get() = ToolStrings.noRecipient().en()

    private val PLACEHOLDER_WORDS = setOf("recipient", "recipient name", "recipients name", "recipient s name", "contact s name", "person s name", "recipient email", "recipient email address",
        "recipient address", "name", "contact", "contact name", "person", "person name", "someone", "somebody", "unknown", "their name",
        "email", "email address", "address", "number", "phone number", "to", "user", "none", "n a", "na", "tbd", "nom", "destinataire",
        "nom du destinataire", "quelqu un")

    /**
     * 1.0.2: a recipient that is a template's placeholder ("[Recipient Name]", "<name>", "{contact}", "Recipient") is no
     * recipient: a model with no one to address (constrained decoding makes it write something) filled the blank.
     */
    fun placeholder(to: String): Boolean {
        val t = to.trim()
        if (t.length >= 2 && (t.first() == '[' && t.last() == ']' || t.first() == '<' && t.last() == '>' || t.first() == '{' && t.last() == '}' ||
                t.first() == '(' && t.last() == ')')) return true
        val k = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()
        return k.isEmpty() || k in PLACEHOLDER_WORDS
    }

    private fun recipient(to: String?): String? = to?.takeUnless(::placeholder)

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
    /** A skill tool's name in a reply (SkillSpecs: "skill_" and a slug of at most 24 characters). */
    private const val SKILL_NAME = "skill_[a-z0-9_]{1,24}" // l10n: not text (a pattern)

    fun parseText(text: String): List<TextCall> {
        val out = mutableListOf<TextCall>()
        // 1.1, Skills phase B: a skill's tool too (core skills SkillSpecs); TurnRunner refuses one that wasn't described.
        val names = ToolName.entries.joinToString("|") { it.id } + "|" + SKILL_NAME
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
