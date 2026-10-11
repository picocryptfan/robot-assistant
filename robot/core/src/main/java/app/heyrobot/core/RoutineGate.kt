package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

// Pure. What a routine's step may do without you (design §2.5 `RoutineGate`), before the existing ApprovalGate, and what
// the morning brief may say on the lock screen (§2.4, §2.6). A routine never asks a model which action to take, so the
// rules depend only on the step's class, who started the run, the lock, and settings.

/** A step's class for a run nobody asked for (design §2.5). The editor shows [badge] beside each step. */
enum class StepClass(val badge: String) {
    /** The brief, a fixed sentence: spoken, with the lock filter. */
    SPEAK("runs by itself"),
    /** The calendar, notes, phone status, brief reminders, unread count (0.7): personal, so locked only at "Everything". */
    READ_LOCAL("runs by itself"),
    /** The weather: only if routines may go online and the save card listed the service. */
    READ_ONLINE("runs by itself, goes online"),
    /** Timers, alarms, the flashlight, a note with fixed text; locked only those that work on the lock screen. */
    LOCAL_ACTION("runs by itself"),
    /** Opening an app or a draft, maps, a new event: never unattended, a Ready card you tap. */
    NEEDS_YOU("waits for you"),
    /** Freeing memory or storage, deleting, Ask Claude, anything that sends personal content to a helper. */
    NEVER("not offered"),
}

/** Who started the run (design §2.5 "Origins"). */
enum class RunOrigin {
    /** A trigger fired. */
    UNATTENDED,
    /** "Run my evening routine", or Run now: exactly what a typed command would get from ApprovalGate. */
    ASKED,
    /** You tapped a Ready card: the same as [ASKED]. */
    READY_TAP,
}

/** What happens to one step. Also a voice log code (144 ROUTINE_STEP, b). */
enum class StepGo(override val id: Int, override val title: String) : LogCode {
    RUN(1, "ran"),
    /** Run, but say only its public part: the weather without the town, the brief at the lock level. */
    RUN_PUBLIC(2, "ran, public part only"),
    /** Held until you unlock ("Unlock to hear your day"). */
    WAIT_UNLOCK(3, "held until unlock"),
    /** A Ready card and a notification; the tap runs it through ApprovalGate. */
    READY(4, "ready card"),
    /** You are there (asked or tapped): ApprovalGate shows its card. */
    CONFIRM(5, "card"),
    /** "Routines may go online by themselves" is off: "Weather skipped: routines may not go online". */
    SKIP_OFFLINE(6, "skipped: may not go online"),
    /** The routine's save card didn't list this online service. */
    SKIP_NO_CONSENT(7, "skipped: online use not agreed"),
    /** Off in Settings › Actions. */
    DISABLED(8, "off in settings"),
    /** Never done by a routine. */
    NEVER(9, "never offered"),
}

/** Why a routine didn't run when its trigger came, for the voice log (142 ROUTINE_SKIPPED, a). */
enum class SkipWhy(override val id: Int, override val title: String) : LogCode {
    LOCKED(1, "locked"), OFFLINE_NOT_ALLOWED(2, "may not go online"), NO_ASSISTANT(3, "Robot isn't the assistant"),
    QUIET_HOURS(4, "outside its hours or days"), ALREADY_RAN(5, "already ran today"), DUPLICATE(6, "same trigger twice"),
    OFF(7, "switched off"), MISSED(8, "missed its time"), OUTSIDE_ALARM_WINDOW(9, "alarm outside its hours"),
}

/** Routines › Morning brief › "On the lock screen" (design §2.3). */
enum class BriefLock(val id: String, val title: String) {
    TIME_WEATHER("time_weather", "Time and weather"),
    EVERYTHING("everything", "Everything"),
    NOTHING("nothing", "Nothing until I unlock");

    companion object {
        fun of(id: String?): BriefLock = entries.firstOrNull { it.id == id } ?: TIME_WEATHER
    }
}

/**
 * The routine and brief settings (design §1.5 and §2.9), with their defaults. They become fields of RobotSettings in
 * settings schema 9 when the Routines screen is wired; Settings.kt is not changed on this branch, so callers pass this
 * with its defaults until then. "Personal answers only in private headphones" comes with headset mode (OutputGuard).
 */
data class RoutineSettings(
    /** Safety & privacy › "Routines can run while the phone is locked" (on). Off: a locked phone holds every step. */
    val runLocked: Boolean = true,
    /** Safety & privacy › "Routines may go online by themselves" (on): only the services each routine's card listed. */
    val online: Boolean = true,
    /** `briefEnabled`: off until you set the brief up. */
    val briefEnabled: Boolean = false,
    /** `briefItems`, in the order spoken. */
    val briefItems: List<BriefItem> = BriefItem.DEFAULTS,
    /** `briefLock`. */
    val briefLock: BriefLock = BriefLock.TIME_WEATHER,
    /** `briefFinishOnUnlock`: the held part continues when you unlock soon after ([RoutineGate.resumes]). */
    val finishOnUnlock: Boolean = true,
    /** `briefLang`: null follows the phone. */
    val briefLang: BriefLang? = null,
    /** `briefPrepare`: "Get ready while the alarm rings" (weather and the voice loaded on ALARM_ALERT). */
    val briefPrepare: Boolean = true,
    /** `clockSilenceMinutes`: Clock's auto-silence (not readable from Clock), for telling a missed alarm. */
    val clockSilenceMinutes: Int = 10,
    /** `alarmWindow`: the default hours of a "when I turn off my alarm" trigger. */
    val alarmWindow: TimeWindow = TimeWindow.ALARM,
)

object RoutineGate {
    /** A held part of the brief continues at an unlock within this long ("Finish when I unlock"). */
    val FINISH: Duration = Duration.ofMinutes(15)

    fun classOf(step: RoutineStep): StepClass = when (step) {
        RoutineStep.Brief, is RoutineStep.Say -> StepClass.SPEAK
        RoutineStep.Reminders, RoutineStep.Messages -> StepClass.READ_LOCAL
        is RoutineStep.Tool -> classOf(step.action.tool)
    }

    fun classOf(tool: ToolName): StepClass = when (tool) {
        ToolName.READ_CALENDAR, ToolName.FIND_NOTES, ToolName.PHONE_STATUS, ToolName.LOOKUP_CONTACT -> StepClass.READ_LOCAL
        ToolName.WEATHER -> StepClass.READ_ONLINE
        ToolName.SET_TIMER, ToolName.SET_ALARM, ToolName.FLASHLIGHT, ToolName.ADD_NOTE -> StepClass.LOCAL_ACTION
        ToolName.OPEN_APP, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE, ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT -> StepClass.NEEDS_YOU
        ToolName.FREE_MEMORY, ToolName.FREE_STORAGE, ToolName.ASK_CLAUDE -> StepClass.NEVER
    }

    /** The steps the editor offers ("Do"): everything but [StepClass.NEVER]. */
    fun offered(tool: ToolName): Boolean = classOf(tool) != StepClass.NEVER

    /**
     * What [step] of [routine] does, run from [origin] with the phone [locked] or not.
     *
     * - [StepClass.NEVER]: never, from any origin. Off in Settings › Actions: not done.
     * - [RunOrigin.ASKED] and [RunOrigin.READY_TAP]: the brief and the routine-only reads follow the brief's lock level;
     *   a tool goes through ApprovalGate with `asked = true` and `content = false`, as a typed command would.
     * - [RunOrigin.UNATTENDED]: [StepClass.NEEDS_YOU] becomes a Ready card. Locked with "Routines can run while the phone
     *   is locked" off, or the routine's "Wait for unlock": held. A tool at "Ask first" becomes a Ready card (nobody is
     *   there to answer a card). Then by class: online reads need the online switch and the service on the save card, and
     *   locked say only their public part (the weather without the town); local reads wait for an unlock unless the lock
     *   level is "Everything"; local actions locked run only if they work on the lock screen (timers and alarms by default);
     *   the brief follows the lock level, a fixed sentence counts as personal.
     */
    fun decide(step: RoutineStep, origin: RunOrigin, locked: Boolean, routine: Routine, settings: RobotSettings,
        rs: RoutineSettings = RoutineSettings()): StepGo {
        val cls = classOf(step)
        if (cls == StepClass.NEVER) return StepGo.NEVER
        val tool = step.tool
        if (tool != null && settings.approval(tool) == Approval.OFF) return StepGo.DISABLED
        if (origin != RunOrigin.UNATTENDED) return asked(step, locked, settings, rs)
        if (cls == StepClass.NEEDS_YOU) return StepGo.READY
        if (locked && (!rs.runLocked || routine.lock == RoutineLock.WAIT_FOR_UNLOCK)) return StepGo.WAIT_UNLOCK
        if (tool != null && settings.approval(tool) == Approval.ASK) return StepGo.READY
        return when (cls) {
            StepClass.READ_ONLINE -> when {
                !rs.online -> StepGo.SKIP_OFFLINE
                step.online !in routine.online -> StepGo.SKIP_NO_CONSENT
                !locked -> StepGo.RUN
                else -> byLevel(rs.briefLock)
            }
            StepClass.READ_LOCAL -> if (!locked || rs.briefLock == BriefLock.EVERYTHING) StepGo.RUN else StepGo.WAIT_UNLOCK
            StepClass.LOCAL_ACTION -> if (locked && tool !in settings.lockedTools) StepGo.WAIT_UNLOCK else StepGo.RUN
            StepClass.SPEAK -> when {
                !locked -> StepGo.RUN
                step == RoutineStep.Brief -> byLevel(rs.briefLock)
                rs.briefLock == BriefLock.EVERYTHING -> StepGo.RUN
                else -> StepGo.WAIT_UNLOCK
            }
            StepClass.NEEDS_YOU, StepClass.NEVER -> error("handled above")
        }
    }

    private fun asked(step: RoutineStep, locked: Boolean, settings: RobotSettings, rs: RoutineSettings): StepGo {
        if (step is RoutineStep.Tool) return when (ApprovalGate.decide(step.action.tool, locked, settings, asked = true, content = false)) {
            GateDecision.RUN -> StepGo.RUN
            GateDecision.CONFIRM -> StepGo.CONFIRM
            GateDecision.NEEDS_UNLOCK -> StepGo.WAIT_UNLOCK
            GateDecision.DISABLED -> StepGo.DISABLED
        }
        return when {
            !locked -> StepGo.RUN
            step == RoutineStep.Brief -> byLevel(rs.briefLock)
            rs.briefLock == BriefLock.EVERYTHING -> StepGo.RUN
            else -> StepGo.WAIT_UNLOCK
        }
    }

    private fun byLevel(level: BriefLock): StepGo = when (level) {
        BriefLock.EVERYTHING -> StepGo.RUN
        BriefLock.TIME_WEATHER -> StepGo.RUN_PUBLIC
        BriefLock.NOTHING -> StepGo.WAIT_UNLOCK
    }

    /**
     * Whether the brief in [routine] may fetch the weather by itself: null when it may, else why not (the brief then says
     * so and goes on). Asked by you, it may (you are there; ApprovalGate's own rules apply to the fetch).
     */
    fun briefWeather(routine: Routine, origin: RunOrigin, rs: RoutineSettings): BriefWeatherMissing? = when {
        origin != RunOrigin.UNATTENDED -> null
        !rs.online -> BriefWeatherMissing.NOT_ONLINE
        OnlineUse.OPEN_METEO !in routine.online -> BriefWeatherMissing.NOT_ONLINE
        else -> null
    }

    /**
     * The held part of a run continues at an unlock at [unlockAt]: with "Finish when I unlock" on, within [FINISH] of
     * [heldAt] for the rest of a brief, or the same day for a whole routine held from the start (a missed alarm: "the
     * first unlock after the alarm time").
     */
    fun resumes(heldAt: ZonedDateTime, unlockAt: ZonedDateTime, finishOnUnlock: Boolean, whole: Boolean = false): Boolean = when {
        !finishOnUnlock || unlockAt.isBefore(heldAt) -> false
        whole -> unlockAt.withZoneSameInstant(heldAt.zone).toLocalDate() == heldAt.toLocalDate()
        else -> Duration.between(heldAt, unlockAt) <= FINISH
    }
}

// ---------------------------------------------------------------------------------------------- the brief on the lock screen

/** The brief's parts, as spoken and as held for an unlock. [placeName]: the weather may name the town. */
data class BriefPlan(val spoken: List<BriefItem>, val held: List<BriefItem>, val placeName: Boolean) {
    /** "Unlock to hear your day" ends the spoken part when something personal waits. */
    val unlockLine: Boolean get() = held.any { it != BriefItem.CLOSING }
}

object BriefPrivacy {
    /** Said on the lock screen at "Time and weather": the greeting with the time and date, the weather without the town. */
    val PUBLIC: Set<BriefItem> = setOf(BriefItem.GREETING, BriefItem.WEATHER)

    /**
     * Which of [items] are spoken now and which wait for an unlock. Unlocked, or at "Everything": all of them, the town
     * included. Locked at "Time and weather": the greeting and the weather without the town; the calendar, reminders and
     * messages wait (the design allows the bare unread count once 0.7 exists; until then it waits too), and the closing
     * goes with the held part. Locked at "Nothing until I unlock": all of it waits.
     */
    fun plan(items: List<BriefItem>, locked: Boolean, level: BriefLock): BriefPlan = when {
        !locked || level == BriefLock.EVERYTHING -> BriefPlan(items, emptyList(), placeName = true)
        level == BriefLock.NOTHING -> BriefPlan(emptyList(), items, placeName = false)
        else -> {
            val now = items.filter { it in PUBLIC }
            val later = items.filter { it !in PUBLIC && it != BriefItem.CLOSING }
            val closing = items.filter { it == BriefItem.CLOSING }
            if (later.isEmpty()) BriefPlan(now + closing, emptyList(), placeName = false)
            else BriefPlan(now, later + closing, placeName = false)
        }
    }
}

// ---------------------------------------------------------------------------------------------- Ready cards

/**
 * A step that waited for you ([StepGo.READY]): `routines/ready.json`. Personal: never shown on the lock screen with
 * details ([ReadyCards.publicLine]); it expires at the end of [day].
 */
data class ReadyCard(val routineId: String, val routineName: String, val step: RoutineStep, val created: Long, val day: LocalDate) {
    /** "Your “Leaving work” routine is ready to: Search Organic Maps for “home”. Tap to do it." */
    fun text(): String = "Your “$routineName” routine is ready to: ${step.describe()}. Tap to do it."
}

object ReadyCards {
    const val MAX = 20
    const val VERSION = 1

    /** The notification's public version (and its text on the lock screen). */
    fun publicLine(n: Int): String = if (n == 1) "Robot has 1 thing ready for you" else "Robot has $n things ready for you"

    /** The cards still due on [today]. */
    fun live(cards: List<ReadyCard>, today: LocalDate): List<ReadyCard> = cards.filter { it.day == today }

    /** [cards] with [card] added (expired ones gone, the same step of the same routine once, at most [MAX]). */
    fun add(cards: List<ReadyCard>, card: ReadyCard, today: LocalDate): List<ReadyCard> =
        (live(cards, today).filterNot { it.routineId == card.routineId && it.step == card.step } + card).takeLast(MAX)

    /**
     * The silent notification after a run, only when a step was held or failed ("Ran “Leaving work” · 2 done, 1 ready");
     * null when everything ran.
     */
    fun runSummary(name: String, done: Int, ready: Int, held: Int, failed: Int): String? {
        if (ready == 0 && held == 0 && failed == 0) return null
        val parts = listOfNotNull("$done done", ready.takeIf { it > 0 }?.let { "$it ready" }, held.takeIf { it > 0 }?.let { "$it waiting for unlock" },
            failed.takeIf { it > 0 }?.let { "$it didn't work" })
        return "Ran “$name” · " + parts.joinToString(", ")
    }

    fun encode(cards: List<ReadyCard>): String = JSONObject().put("v", VERSION).put("cards", JSONArray().apply {
        for (c in cards) put(JSONObject().put("routine", c.routineId).put("name", c.routineName).put("step", RoutineCodec.step(c.step))
            .put("created", c.created).put("day", c.day.toString()))
    }).toString()

    /** The cards that can be read (a damaged one is left out; a damaged or newer file gives none). */
    fun decode(json: String?): List<ReadyCard> = try {
        val o = JSONObject(json ?: return emptyList())
        if ((o.opt("v") as? Number)?.toInt() != VERSION) return emptyList()
        val a = o.getJSONArray("cards")
        (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            val step = c.optJSONObject("step")?.let(RoutineCodec::step) ?: return@mapNotNull null
            if (RoutineGate.classOf(step) == StepClass.NEVER) return@mapNotNull null
            ReadyCard(RoutineCodec.str(c, "routine")?.takeIf(RoutineCheck::validId) ?: return@mapNotNull null,
                RoutineCodec.str(c, "name") ?: return@mapNotNull null, step, RoutineCodec.long(c, "created") ?: return@mapNotNull null,
                RoutineCodec.date(RoutineCodec.str(c, "day")) ?: return@mapNotNull null)
        }
    } catch (_: Exception) { emptyList() }
}
