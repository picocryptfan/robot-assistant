// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.FreeUp
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.link.LinkRouter
import app.heyrobot.core.lists.ListsRouter
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.memory.MemoryRouting
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.music.MusicContext
import app.heyrobot.core.music.MusicRouter
import app.heyrobot.core.online.OnlineRouter
import app.heyrobot.core.output.HandsFreeCommand
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.sums.SumsRouter
import app.heyrobot.core.web.BrowserRouter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/**
 * bug-open-car-mode (the user, 4 Nov 2026: "when i say open carmode the robot thinks it doesnt have a cardmode"): the
 * ways you ask for car mode, typed or said, reach car mode's own phrase, with car mode off (nothing connected) and on,
 * and no router ahead of hands-free in the panel's order (PanelController) takes them first. Off the phrase, the
 * question fell to the model, which has no car mode tool and says so.
 */
class OpenCarModeTest {
    private val now = ZonedDateTime.parse("2026-11-04T10:00:00-04:00[America/Halifax]")
    private val skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())
    private val helpers = listOf(HelperName.CLAUDE, HelperName("jarvis", listOf(listOf("jarvis")), phonetic = true))

    private val asks = listOf(
        "open car mode", "Open car mode.", "turn on car mode", "start car mode", "car mode", "Hey Robot, open car mode",
        "open carmode", "open the car mode", "launch car mode", "open driving mode",
        "ouvre le mode voiture", "Ouvre le mode voiture.", "active le mode voiture", "ouvre le mode auto", "lance le mode voiture",
        "démarre le mode voiture", "mets le mode voiture",
    )

    /** Each router the panel asks before hands-free, by name, with what it made of [t]. */
    private fun earlier(t: String): List<Pair<String, Any?>> = listOf(
        "messages" to MessagesRouter.route(t),
        "conversation" to ConversationRouter.route(t, running = false),
        "feedback" to FeedbackRouter.route(t),
        "lock" to LockRouter.route(t, now, listOf("Bedtime")),
        "routines" to RoutineRouter.route(t, now, listOf("Evening routine"), id = "r"),
        "skills" to SkillRouter.route(t, skills, people = listOf("Marie", "Sam")),
        "files" to FilesRouter.route(t),
        "lists" to ListsRouter.route(t),
        "link" to LinkRouter.route(t, now) { false },
        "music" to MusicRouter.route(t, MusicContext(appPicked = true)),
        "sums" to SumsRouter.route(t, now),
        "online" to OnlineRouter.route(t),
    )

    @Test fun everyWayOfAskingReachesCarModeWithCarModeOff() {
        val wrong = asks.mapNotNull { t ->
            val took = earlier(t).filter { it.second != null }.map { "${it.first}=${it.second}" }
            val hf = HandsFreeRouter.route(t, carMode = false)
            if (took.isEmpty() && hf == HandsFreeCommand.CarModeOn) null else "$t -> $took, hands-free: $hf"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun everyWayOfAskingIsCarModeOnWhileItIsOn() {
        // The panel answers CarModeOn while car mode is on with "Car mode is on." (PanelHandsFree), never the model.
        assertEquals(emptyList<String>(), asks.filter { HandsFreeRouter.route(it, carMode = true) != HandsFreeCommand.CarModeOn })
    }

    @Test fun closingItIsCarModeOff() {
        for (t in listOf("close car mode", "close carmode", "turn off carmode", "ferme le mode voiture", "quitte le mode voiture", "désactive le mode voiture")) {
            assertEquals(t, HandsFreeCommand.CarModeOff, HandsFreeRouter.route(t, carMode = true))
            assertEquals(t, emptyList<String>(), earlier(t).filter { it.second != null }.map { it.first })
        }
    }

    @Test fun questionsAboutCarModeStayQuestions() {
        for (t in listOf("open car mode settings", "how do I open car mode", "my car mode button is broken", "what is car mode",
                "open the car door", "start the car", "ouvre la porte de la voiture", "open maps")) {
            assertEquals(t, null, HandsFreeRouter.route(t, carMode = false).takeIf { it == HandsFreeCommand.CarModeOn || it == HandsFreeCommand.CarModeOff })
        }
    }
}
