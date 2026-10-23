// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.guide

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.LockScreen
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.update.UpdateSettings
import org.json.JSONArray
import org.json.JSONObject

// Pure (1.0). The setup guide: a page per feature, in order, that says what it does, what stays on the phone, and asks
// what to turn on, writing through the stores Settings already uses (nothing here touches settings.json's schema). Its
// own state is files/robot/setup_guide.json: the guide version you last saw (it opens by itself once per new version),
// where Finish later left it, and the version you finished. The words are the app's (res/values/strings.xml); this file
// has the order, the moves and what the summary lists.

/** The guide's pages, in order. [id] is what setup_guide.json keeps. */
enum class GuideStep(val id: String) {
    WELCOME("welcome"),
    CAN_DO("can-do"),
    PHONE("phone"),
    VOICE("voice"),
    OPENING("opening"),
    ASKS_FIRST("asks-first"),
    PEOPLE("people"),
    MESSAGES("messages"),
    MEMORY("memory"),
    WEATHER("weather"),
    CONVERSATION("conversation"),
    ROUTINES("routines"),
    /** 1.1: Phone lock, optional and off ("Skip this step" leaves it off); the setup itself is in Settings › Phone lock. */
    PHONE_LOCK("phone-lock"),
    HEADSET_CAR("headset-car"),
    HELPERS("helpers"),
    WEB_TERMUX("web-termux"),
    UPDATES("updates"),
    SUMMARY("summary");

    /** 1 to [SetupGuide.COUNT]: "Step 3 of 17". */
    val number: Int get() = ordinal + 1

    val next: GuideStep? get() = entries.getOrNull(ordinal + 1)
    val previous: GuideStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        fun of(id: String?): GuideStep? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What setup_guide.json holds. [seen]: the guide version last opened (at launch, from Home or from Settings); [at]: the
 * page Finish later (or the app closing) left it on, null when it isn't under way; [finished]: the version you went
 * through to Done (0: never); [skipped]: the pages skipped on the way.
 */
data class GuideState(
    val seen: Int = 0,
    val at: GuideStep? = null,
    val finished: Int = 0,
    val skipped: Set<GuideStep> = emptySet(),
) {
    fun toJson(): String = JSONObject()
        .put("v", SetupGuide.FILE_VERSION)
        .put("seen", seen)
        .put("at", at?.id ?: JSONObject.NULL)
        .put("finished", finished)
        .put("skipped", JSONArray().apply { GuideStep.entries.filter { it in skipped }.forEach { put(it.id) } })
        .toString()

    companion object {
        /** Missing, empty or damaged text gives a guide never seen; an unknown page is dropped. */
        fun fromJson(text: String?): GuideState {
            val o = try { JSONObject(text ?: return GuideState()) } catch (_: Exception) { return GuideState() }
            val skipped = o.optJSONArray("skipped")?.let { a -> (0 until a.length()).mapNotNull { GuideStep.of(a.optString(it)) }.toSet() }.orEmpty()
            return GuideState(
                seen = o.optInt("seen", 0).coerceAtLeast(0),
                at = if (o.isNull("at")) null else GuideStep.of(o.optString("at")),
                finished = o.optInt("finished", 0).coerceAtLeast(0),
                skipped = skipped,
            )
        }
    }
}

/** What Home offers: the guide under way ("Continue the setup guide"), or going through it again. */
enum class HomeGuide { CONTINUE, RUN_AGAIN }

object SetupGuide {
    /** The guide's version: a new one (new pages worth a look) opens by itself once more after an update. */
    const val VERSION = 1
    const val FILE = "setup_guide.json"
    const val FILE_VERSION = 1
    val COUNT: Int get() = GuideStep.entries.size

    /** At launch: opens by itself when you haven't seen this version (a new install, or an update that brought a new one). */
    fun opensAtLaunch(s: GuideState): Boolean = s.seen < VERSION

    /** Opened (at launch, from Home or Settings): where it shows. Under way, it resumes; otherwise it starts again from Welcome. */
    fun open(s: GuideState): GuideState =
        if (s.at != null) s.copy(seen = VERSION) else s.copy(seen = VERSION, at = GuideStep.WELCOME, skipped = emptySet())

    /** Next or Back moved to [to]; a page you came back to and went on from is no longer skipped. */
    fun moved(s: GuideState, from: GuideStep, to: GuideStep): GuideState =
        s.copy(at = to, skipped = if (to.ordinal > from.ordinal) s.skipped - from else s.skipped)

    /** "Skip this step": the defaults stay, the next page shows. */
    fun skip(s: GuideState, step: GuideStep): GuideState = s.copy(at = step.next ?: step, skipped = s.skipped + step)

    /** "Done" on the summary: the guide is no longer under way. */
    fun finish(s: GuideState): GuideState = s.copy(at = null, finished = VERSION)

    fun home(s: GuideState): HomeGuide = if (s.at != null) HomeGuide.CONTINUE else HomeGuide.RUN_AGAIN
}

/** A line of the summary: a feature, and where Settings has it. The app gives each its words and path. */
enum class GuideItem {
    BRAIN, VOICE_MODELS, ASSISTANT, ROBOT_VOICE, WAKE_WORD, ONLY_WHILE_CHARGING, ASK_FIRST, VOICE_CARDS, LOCK_SCREEN,
    TEXT_APP, EMAIL_APP, CONTACTS, CALENDAR, MESSAGES, ANNOUNCE, MEMORY, MEMORY_LOCKED, WEATHER_HOME, LOCATION, NEAREST,
    TRANSLATE_TRANSCRIPTS, FAST_TRANSLATION, BRIEF, HEADSET_BUTTON, CAR_MODE, HELPERS, WEB_SEARCH, TERMUX, AUTO_UPDATES, TEST_BUILDS,
    /** 1.0: the engine a search in your browser uses (needs no set-up). */
    BROWSER_ENGINE,
    /** 1.1: "Remind me to back up every month", off. */
    BACKUP_REMINDER,
}

/**
 * [on] for a switch, or [valueText] for a choice (a name from core, as Settings shows it, without a language yet: the
 * summary shows it in the menus' language); [on] null with [valueText] null: not set.
 */
data class SummaryLine(val item: GuideItem, val on: Boolean? = null, val valueText: Phrase? = null)

/** Everything the summary reads: the settings files as they are now, and what Android says about the phone. */
data class GuideChoices(
    val robot: RobotSettings = RobotSettings(),
    val messages: MessageSettings = MessageSettings(),
    val memory: MemorySettings = MemorySettings(),
    val routines: RoutineSettings = RoutineSettings(),
    val headset: HeadsetSettings = HeadsetSettings(),
    val carOn: Boolean = false,
    val updates: UpdateSettings = UpdateSettings(),
    val webOn: Boolean = false,
    /** 1.0: Settings › Web search's default engine for a search in your browser (its name, as Settings shows it). */
    val browserEngine: String = app.heyrobot.core.web.BrowserEngine.DEFAULT.title,
    val termuxOn: Boolean = false,
    /** 1.1: the monthly backup reminder (backup.json). */
    val backupRemind: Boolean = false,
    /** A helper can answer (a key saved, or your own server). */
    val helpersSetUp: Boolean = false,
    val brainReady: Boolean = false,
    val voiceReady: Boolean = false,
    val assistant: Boolean = false,
    val contacts: Boolean = false,
    val calendar: Boolean = false,
    val location: Boolean = false,
    val fastTranslation: Boolean = false,
    val cheogramInstalled: Boolean = false,
    val protonInstalled: Boolean = false,
    /** "Find the nearest place online" as Settings › Actions shows it (a card's lasting answer counts). */
    val nearestText: Phrase = robot.nearestOnline.titleText,
)

object GuideSummary {
    /** What is on and off now, a line each, in the guide's order. */
    fun lines(c: GuideChoices): List<SummaryLine> {
        val r = c.robot
        return listOf(
            SummaryLine(GuideItem.BRAIN, on = c.brainReady),
            SummaryLine(GuideItem.VOICE_MODELS, on = c.voiceReady),
            SummaryLine(GuideItem.ASSISTANT, on = c.assistant),
            SummaryLine(GuideItem.ROBOT_VOICE, valueText = r.voiceChoice.titleText),
            SummaryLine(GuideItem.WAKE_WORD, on = r.wakeWord),
            SummaryLine(GuideItem.ONLY_WHILE_CHARGING, on = r.wakeWord && r.wakeOnlyWhileCharging),
            SummaryLine(GuideItem.ASK_FIRST, valueText = r.safetyCards.titleText),
            SummaryLine(GuideItem.VOICE_CARDS, on = r.voiceCards),
            SummaryLine(GuideItem.LOCK_SCREEN, valueText = Phrase.plain(LockScreen.allowed(r.lockedTools))),
            SummaryLine(GuideItem.TEXT_APP, valueText = r.textAppFor(c.cheogramInstalled).titleText),
            SummaryLine(GuideItem.EMAIL_APP, valueText = r.emailAppFor(c.protonInstalled).titleText),
            SummaryLine(GuideItem.CONTACTS, on = c.contacts),
            SummaryLine(GuideItem.CALENDAR, on = c.calendar),
            SummaryLine(GuideItem.MESSAGES, on = c.messages.on),
            SummaryLine(GuideItem.ANNOUNCE, on = c.messages.on && c.messages.announce),
            SummaryLine(GuideItem.MEMORY, on = c.memory.on),
            SummaryLine(GuideItem.MEMORY_LOCKED, on = c.memory.on && c.memory.onLockScreen),
            SummaryLine(GuideItem.WEATHER_HOME, valueText = r.weatherHome?.name?.let(Phrase::plain)),
            SummaryLine(GuideItem.LOCATION, on = c.location),
            SummaryLine(GuideItem.NEAREST, valueText = c.nearestText),
            SummaryLine(GuideItem.TRANSLATE_TRANSCRIPTS, on = r.convTranslate),
            SummaryLine(GuideItem.FAST_TRANSLATION, on = c.fastTranslation),
            SummaryLine(GuideItem.BRIEF, on = c.routines.briefEnabled),
            SummaryLine(GuideItem.HEADSET_BUTTON, on = c.headset.buttonOpensRobot),
            SummaryLine(GuideItem.CAR_MODE, on = c.carOn),
            SummaryLine(GuideItem.HELPERS, on = c.helpersSetUp),
            SummaryLine(GuideItem.WEB_SEARCH, on = c.webOn),
            SummaryLine(GuideItem.BROWSER_ENGINE, valueText = Phrase.plain(c.browserEngine)),
            SummaryLine(GuideItem.TERMUX, on = c.termuxOn),
            SummaryLine(GuideItem.AUTO_UPDATES, on = c.updates.autoCheck),
            SummaryLine(GuideItem.TEST_BUILDS, on = c.updates.testBuilds),
            SummaryLine(GuideItem.BACKUP_REMINDER, on = c.backupRemind),
        )
    }
}
