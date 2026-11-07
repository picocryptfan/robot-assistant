// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// H0: "how's my day" and its kin, spoken (lower case, no punctuation) and typed, English and French, route to Today;
// the brief's phrases stay the brief's.
class TodayRouteTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 19, 0, 0, 0, ZoneId.of("America/Halifax"))
    private fun route(s: String) = RoutineRouter.route(s, now, emptyList(), id = "d")

    private val english = listOf("How's my day?", "how's my day", "hows my day", "How is my day", "Hey Robot, how's my day looking?",
        "How does my day look?", "How's today?", "how is today looking", "What's left today?", "what's next today", "how's my day going please")
    private val french = listOf("Comment est ma journée?", "comment est ma journee", "Comment va ma journée", "Comment se présente ma journée?",
        "Comment s'annonce ma journée?", "Qu'est-ce qu'il me reste aujourd'hui?", "Il me reste quoi aujourd'hui?", "Quoi d'autre aujourd'hui?",
        "Dis Robot, ma journée")

    @Test fun englishRoutesToToday() = english.forEach { assertEquals(it, RoutineCommand.Today, route(it)) }

    @Test fun frenchRoutesToTodayAndAnswersInFrench() = french.forEach {
        assertEquals(it, RoutineCommand.Today, route(it))
        assertEquals(it, Lang.FR, RoutineRouter.lang(it, RoutineCommand.Today))
    }

    @Test fun englishIsNotTakenForFrench() = english.forEach { assertEquals(it, null, RoutineRouter.lang(it, RoutineCommand.Today)) }

    @Test fun theBriefKeepsItsPhrases() = listOf("brief me", "What's my day like?", "what does my day look like", "c'est quoi ma journée", "mon résumé")
        .forEach { assertEquals(it, RoutineCommand.BriefNow, route(it)) }

    @Test fun longerTalkIsLeftToTheModel() = listOf("how's my day been so far, tell me a story", "how is my daughter", "ma journée était longue")
        .forEach { assertEquals(it, null, route(it)) }

    @Test fun lockedPhoneIsNotRefusedHere() = assertEquals(null, RoutineLockedPhone.refuseText(RoutineCommand.Today, locked = true))

    @Test fun logCode() = assertEquals(RoutineCommandCode.TODAY, RoutineCommandCode.of(RoutineCommand.Today))
}
