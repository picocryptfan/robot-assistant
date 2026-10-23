// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The panel's side of what stays private, where the code needs Android to run (Scan's layout-insensitive checks): a routine's
 * Say and reminder lines are quoted content; a routine that's off runs only after a tap on its card; a town named for the
 * weather passes Nearby's rule before it may go online; "free up memory" says how many recent apps, never which; and web
 * search's consent is made from the settings shown.
 */
class PanelPrivacyPinsTest {
    private val root = File("src/main/java/app/heyrobot")

    private fun flat(path: String): Flat {
        val f = File(root, path)
        assertEquals("run from the :app module directory", true, f.isFile)
        return Flat(Source.strip(f.readText(), keepStrings = false))
    }

    @Test fun aRoutinesOwnLinesAreQuotedAndAnOffRoutineWaitsForATap() {
        val panel = flat("assistant/routines/PanelRoutines.kt")
        // The Say step's sentence and the reminders, as written: the reply carries the content record before either is shown.
        assertInOrder(panel.body("suspend fun runAsked("), "if (step is RoutineStep.Say || step == RoutineStep.Reminders) host.quoted(replyId)",
            "is RoutineStep.Say -> { host.append(replyId, step.text)", "RoutineStep.Reminders -> {")
        // Asked by name, a routine that's off shows its card first (not the built-in brief), and runs only when tapped.
        assertInOrder(panel.body("private suspend fun run("), "if (!r.enabled && r.id != BriefRoutine.ID && !offTapped(replyId, r, host, l)) return false",
            "runAsked(replyId, r, host, RunOrigin.ASKED, l)")
        val off = panel.body("private suspend fun offTapped(")
        // Locked: a line, no card anyone at the lock screen could tap.
        assertInOrder(off, "if (host.locked) return !reply(", "val yes = host.ask(", "tapOnly = RoutineStrings.offTapRunOnCard(", "if (!yes) {", "return false",
            "return true")
        assertEquals(1, off.count("host.ask("))
    }

    @Test fun aFilesAnswerIsPersonalWhileItIsSpoken() {
        // The reply still on screen counts as Robot Files' when its router gave it, so a car or headset session's speech
        // check treats its first sentences as personal before finishReply adds the turn to the chat.
        val now = flat("assistant/PanelController.kt").body("private fun personalNow(")
        assertInOrder(now, "ChatTurn(it.id, TurnRole.ASSISTANT, it.text, 0, actions = records(it.actions), files = PanelFileAnswer.isFiles(it.id))",
            "HelperContext.personal(chat + reply)")
    }

    @Test fun filesCardsAreShownAfterTheirReplyEnds() {
        // finishReply settles any card still waiting as declined; Robot Files' cards (Delete and Keep) come after it.
        val c = flat("assistant/PanelController.kt")
        assertInOrder(c, "PanelFileAnswer.markFiles(replyId)", "localReply(replyId, r.text, r.spoken)",
            "r.cards.forEach { replies.upsertAction(replyId, it) }")
    }

    @Test fun aWeatherPlacePassesNearbysRuleBeforeItIsReady() {
        val weather = flat("assistant/ToolExecutor.kt").body("private fun prepareWeather(")
        assertInOrder(weather, "val (where, named) = WeatherAsk.where(a.place)",
            "if (where == WeatherWhere.NAMED && !Nearby.weatherSendable(named ?: a.place.orEmpty())) {",
            "return Prepared.Refused(ActionStatus.INVALID, ToolStrings.weatherPlaceNotSent())",
            "return Prepared.Ready(a.copy(where = where, range = range))")
    }

    @Test fun freeUpMemorysReplyNeverNamesYourApps() {
        val free = flat("assistant/PanelFreeMemory.kt")
        assertHas(free.body("suspend fun run("), "port.sayInto(replyId, advice(mem, if (loaded) modelBytes else null, recent, System.currentTimeMillis()))")
        assertHasNot(free, "InfoStrings.recentApps(")
        // FreeUp's own words only when there's no app to name.
        assertHas(free, "if (recent.isNullOrEmpty()) FreeUp.adviceText(mem, modelBytes, recent, now)", "InfoStrings.recentAppsOnCard(recent.size)")
    }

    @Test fun webSearchsConsentComesFromTheSettingsShown() {
        val web = flat("ui/settings/WebSearchSettings.kt")
        assertEquals(2, web.count(Regex("""WebSetup\.consentText\(""")))
        assertHas(web, "WebSetup.consentText(it, web)", "WebSetup.consentText(s, web)")
    }
}
