// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-car-stay (the user, 8 Nov 2026): in Car mode an app Robot opened ("find a restaurant": your maps app's search, a route,
 * an app by name) went over the car screen, as the panel's own startActivity had no split. Now each goes through the car
 * screen's opener (CarSplitOpen), and when Android puts it over the car screen anyway, the car screen comes back and says so.
 */
class CarStaysTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.let { if (File(it, "app").isDirectory) it else it.parentFile }
    private fun code(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/assistant/$path").readText(), keepStrings = false)
    /** fix-car-133: with its strings, for a pin that names one ([code] blanks them). */
    private fun withStrings(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/assistant/$path").readText(), keepStrings = true)
    private fun res(path: String) = File(root, "app/src/main/res/$path").readText()

    @Test fun thePanelsAppsOpenBesideTheCarScreen() {
        val launch = code("RobotSession.kt").substringAfter("override fun launch(").substringBefore("override fun secure(")
        assertTrue(launch.contains("CarModeController.openBeside(intent) ?:"))
        assertTrue(code("car/CarModeController.kt").contains("fun openBeside(i: Intent): Boolean? = if (on && screenUp) besideOpener?.invoke(i) else null"))
    }

    @Test fun aRouteByVoiceOpensBesideTheCarScreen() {
        val nav = code("handsfree/PanelHandsFree.kt").substringAfter("MapsApps.route(app, here, CarNavigate.HERE.get(lang), to.lat to to.lon, to.label)").substringBefore("CarModeController.routeOpened()")
        assertTrue(nav.contains("CarModeController.openBeside(i) ?:"))
    }

    @Test fun theCarScreenOpensBesideItselfAndComesBackWhenCovered() {
        val a = code("car/CarModeActivity.kt")
        assertTrue(a.substringAfter("override fun onStart(").substringBefore("override fun onResume(").contains("CarModeController.besideOpener = { i -> CarSplitOpen.start(this, i)"))
        val stop = a.substringAfter("override fun onStop(").substringBefore("super.onStop()")
        assertTrue(stop.contains("CarModeController.besideOpener = null") && stop.contains("CarSplit.covered(isInMultiWindowMode, isFinishing,"))
        assertTrue(a.substringAfter("override fun onResume(").substringBefore("override fun onStop(").contains("R.string.car_split_refused"))
    }

    @Test fun theRefusalIsSaidInEnglishAndFrench() {
        assertTrue(res("values/strings_handsfree.xml").contains("<string name=\"car_split_refused\">Couldn\\'t open that app beside the car screen</string>"))
        assertTrue(res("values-fr-rCA/strings_handsfree.xml").contains("<string name=\"car_split_refused\">Impossible d’ouvrir cette appli à côté de l’écran de l’auto</string>"))
    }

    /** fix-car-stay-b: a power-button or assist-gesture show on a locked phone waits for a tap, as in 1.3.0; unlocked, the mic listens. */
    @Test fun aLockedSystemShowNeverListensWithoutATap() {
        val show = code("PanelController.kt")
        assertTrue(show.contains("CarTurn.listensQuietly(CarModeController.quietTurn, handsFree != null, voice = source == null && !locked))"))
    }

    /** fix-car-stay-b: the lock screen's button keeps its panel and waits for a tap, as in 1.3.0; no quiet turn that hears nothing. */
    @Test fun theLockScreenButtonKeepsItsPanel() {
        val prepare = code("car/CarModeController.kt").substringAfter("fun prepareShow(").substringBefore("notifyWatchers()")
        assertTrue(prepare.contains("source != RobotVoiceService.SRC_KEYGUARD"))
        val show = code("PanelController.kt")
        assertTrue(show.contains("voice = source == null && !locked)) startListening()"))
    }

    /** fix-car-stay-b: the time an app opened beside is read once (a second stop doesn't pull the car screen back again), on the main thread. */
    /** review-car-widget-taps M1: the fourth button and a widget's screen arm the covered check too, not only voice. */
    @Test fun anAppOpenedByATapBringsTheCarScreenBackWhenItCoversIt() {
        val open = code("car/CarModeActivity.kt").substringAfter("private fun openApp(").substringBefore("\n    }\n")
        assertTrue(open.contains("if (opened) besideAt = SystemClock.elapsedRealtime()"))
        assertTrue(open.indexOf("val opened = ") in 0 until open.indexOf("if (opened) besideAt"))
    }

    @Test fun theBesideTimeIsReadOnceOnTheMainThread() {
        val a = code("car/CarModeActivity.kt")
        assertTrue(a.contains("runOnUiThread { besideAt = t }") && a.contains("@Volatile private var besideAt"))
        val stop = a.substringAfter("override fun onStop(").substringBefore("super.onStop()")
        assertTrue(stop.contains("besideAt = Long.MIN_VALUE / 2") && stop.indexOf("besideAt = Long.MIN_VALUE / 2") < stop.indexOf("CarSplit.covered("))
    }

    /** fix-car-stay-b: a quiet show is checked every 2 s and closes only through quietDone, held by a card's ask or a listen to come. */
    @Test fun aQuietTurnClosesThroughQuietDone() {
        val c = code("car/CarModeController.kt")
        assertTrue(c.substringAfter("fun quietShown(").substringBefore("private var listenHeld").contains("main.postDelayed({ quietCheck() }, QUIET_CHECK_MS)"))
        val check = c.substringAfter("private fun quietCheck(").substringBefore("@Volatile var besideOpener")
        // fix-car-stay-c: only idle polls count against a listen to come, and a reply in its 3 seconds or on its way holds the turn.
        assertTrue(check.contains("listenHeld = CarTurnEnd.listenHeld(listenHeld, PanelController.listenComing, idle, speaking)"))
        assertTrue(check.contains("val waits = CarTurnLines.of(s)?.ui?.ask != null || CarTurnEnd.listenHolds(listenHeld) || CarTurnLines.sending(s)"))
        assertTrue(check.contains("if (CarTurnEnd.quietDone(quietTurn, PanelHandsFree.active, idle, speaking, waits)) PanelController.hideAfterAnnouncement()"))
        assertTrue(check.contains("else main.postDelayed({ quietCheck() }, QUIET_CHECK_MS)"))
        assertTrue(code("RobotSession.kt").contains("CarModeController.quietShown()"))
        val speak = code("PanelController.kt").substringAfter("fun onSpeakingChanged(").substringBefore("_state.update {")
        assertTrue(speak.split("listenDue = true").size == 3 && speak.split("listenDue = false; if (shown").size == 3)
    }

    /** fix-car-stay-c: a reply that didn't go, or may not have, says so on its card, on the car screen and out loud. */
    @Test fun aSessionEndWaitsForTheStopWindow() {
        val close = code("car/CarModeController.kt").substringAfter("private fun closeWhenSaid(").substringBefore("private fun notifyWatchers(")
        assertTrue(close.contains("if (CarTurnEnd.closeWaits(VoiceHub.tts.isSpeaking, sending, tries)) main.postDelayed"))
    }

    /** fix-car-stay-e: the one close Robot makes on its own waits for its words and a reply's 3 seconds, this show only. */
    @Test fun robotsOwnCloseWaitsForTheReply() {
        val p = code("PanelController.kt")
        assertTrue(p.contains("internal fun hideAfterAnnouncement(words: Boolean = true, held: Boolean = false) = hideWhenSaid(shownAtE, HIDE_TRIES, words, held)"))
        val w = p.substringAfter("private fun hideWhenSaid(").substringBefore("private const val HIDE_STEP_MS")
        assertTrue(w.contains("if (!shown || shownAtE != at) return"))
        assertTrue(w.contains("CarTurnEnd.hideWaits(words, VoiceHub.tts.isSpeaking, sending, s.busy, held, tries)"))
        assertTrue(w.contains("VoiceHub.main.postDelayed({ hideWhenSaid(at, tries - 1, words, held || sending) }, HIDE_STEP_MS)") && w.contains("} else host?.hidePanel()"))
    }

    /**
     * fix-car-stay-e: every close of a Car mode or hands-free turn goes through hideAfterAnnouncement (a session's end, a quiet
     * turn done, the car screen's talk). The panel's other hidePanel calls follow a tap or an app Robot opened, and a question
     * said in a reply's 3 seconds stops it first (answerCard): a new one here fails until it's listed with its reason.
     */
    @Test fun everyCloseOfATurnWaits() {
        val dir = File(root, "app/src/main/java/app/heyrobot/assistant")
        val hides = dir.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Source.strip(f.readText(), keepStrings = false).lines().filter { it.contains("hidePanel()") && !it.contains("fun hidePanel") }.map { f.name + ": " + it.trim() }
        }.toList()
        assertTrue(hides.toString(), hides.count { it == "PanelController.kt: h.hidePanel()" } == 5)
        assertTrue(hides.toString(), hides.contains("PanelController.kt: if (shown) host?.hidePanel()") && hides.contains("PanelController.kt: } else host?.hidePanel()") && hides.size == 7)
        val p = code("PanelController.kt")
        assertTrue(p.substringAfter("private suspend fun leaveForApp(").substringBefore("}").contains("if (shown) host?.hidePanel()"))
        assertTrue(code("handsfree/HandsFreeMessages.kt").substringAfter("fun sessionEnded(").substringBefore("fun panelHidden(").contains("if (f != null) PanelController.hideAfterAnnouncement()"))
        assertTrue(code("car/CarModeActivity.kt").contains("talk = { if (CarModeController.quietTurn) PanelController.hideAfterAnnouncement(words = false) else CarModeController.talk() }"))
        assertTrue(code("car/CarModeController.kt").substringAfter("private fun closeWhenSaid(").substringBefore("private fun notifyWatchers(").contains("else if (!PanelHandsFree.active) PanelController.hideAfterAnnouncement(held = held)"))
    }

    @Test fun aStopSaidInTheWindowIsAnsweredAloudAndAConversationChangeStopsIt() {
        val m = code("PanelMessages.kt")
        assertTrue(m.substringAfter("private fun stopWindow(").substringBefore("private fun cancel(").contains("if (aloud) speak(listOf(spoken(MessageStrings.stoppedSaid(), p.lang)))"))
        val card = m.substringAfter("fun answerCard(").substringBefore("if (p.state != ReplyCardState.WAITING")
        assertTrue(card.contains("WindowAnswer.STOP -> { stopWindow(CardEnd.STOPPED, aloud = true); true }") && card.contains("WindowAnswer.OTHER -> { stopWindow(CardEnd.STOPPED, aloud = true); false }"))
        assertTrue(m.substringAfter("private fun sync(").substringBefore("fun onShow(").contains("stopWindow(CardEnd.CLOSED)\n        card = null"))
    }

    @Test fun aSendThatFailsSaysSo() {
        val m = code("PanelMessages.kt")
        val failed = m.substringAfter("private fun failed(").substringBefore("\n    }")
        assertTrue(failed.contains("p.state = ReplyCardState.FAILED") && failed.contains("notice(p, line)") && failed.contains("speak(listOf(spoken(why, p.lang)))"))
        val fire = m.substringAfter("private suspend fun fire(").substringBefore("private fun failed(")
        assertTrue(fire.contains("failed(p, MessageStrings.unknownSent())") && fire.contains("ReplyOutcome.FAILED -> failed(p, MessageStrings.notSentOpenChat())"))
        assertTrue(!fire.contains("p.state = ReplyCardState.FAILED"))
    }

    /** fix-car-stay-c: the power button or the assist gesture on a locked phone keeps its panel (CarTurn.quietAllowed). */
    @Test fun aLockedSystemShowKeepsItsPanel() {
        val prepare = code("RobotSession.kt").substringAfter("override fun onPrepareShow(").substringBefore("override fun onShow(")
        assertTrue(prepare.contains("ShowGate.userShow(args, showFlags), ShowGate.source(args, showFlags) == null, Assistant.locked(context))"))
    }

    /** fix-car-stay-f: a tap that opens an app in a reply's 3 seconds leaves it to send when they end, as a session's end does. */
    @Test fun aTapThatOpensAnAppLeavesTheReplyToSend() {
        assertTrue(withStrings("PanelController.kt").contains("PanelMessages.onHide(forApp = hidingFor == \"action\")"))
        val m = code("PanelMessages.kt")
        val hide = m.substringAfter("fun onHide(forApp: Boolean = false) {").substringBefore("stopWindow(CardEnd.CLOSED)")
        assertTrue(hide.contains("if (!hold.hide(forApp, inWindow(), link?.busy == true || card?.state == ReplyCardState.SENDING)) return"))
        val win = m.substringAfter("private fun startWindow(").substringBefore("private suspend fun windowWait(")
        assertTrue(win.contains("fire(p, CardEnd.NO_CARD)") && win.split("afterWindow()").size == 2)
        assertTrue(m.substringAfter("private fun afterWindow(").substringBefore("\n    }").contains("if (hold.windowEnded()) onHide()"))
        assertTrue(m.substringAfter("fun onShow(").substringBefore("\n    }").contains("hold.shown()"))
    }

    /** fix-car-stay-f: the headset gone in a reply's 3 seconds: "Sent" is still said; a result with a name or a message shows only. */
    @Test fun aResultAfterTheHeadsetWentNamesNoOneAloud() {
        val m = code("PanelMessages.kt")
        assertTrue(m.contains("private fun aloud(p: Pending): Boolean = hold.aloud(p.inSession, app.heyrobot.assistant.handsfree.PanelHandsFree.active) && !(CarModeController.quietTurn && !CarModeController.trusted)"))
        assertTrue(m.substringAfter("private fun startWindow(").substringBefore("speak(").contains("p.inSession = app.heyrobot.assistant.handsfree.PanelHandsFree.active"))
        val fire = m.substringAfter("private suspend fun fire(").substringBefore("private fun failed(")
        assertTrue(fire.contains("if (aloud(p)) speak(listOf(spoken(MessageStrings.wroteAgain(") && fire.contains("if (aloud(p)) saidAloud(said)") && fire.contains("if (aloud(p)) speak(listOf(spoken(MessageStrings.goneOpenChat("))
        assertTrue(fire.contains("speak(listOf(spoken(MessageStrings.sentShort(), lang)))") && !fire.contains("aloud(p)) speak(listOf(spoken(MessageStrings.sentShort("))
    }

    /** fix-car-stay-g: every tap that leaves for an app in a reply's 3 seconds leaves it to send, through one path. */
    @Test fun everyTapThatLeavesKeepsTheReply() {
        val p = code("PanelController.kt")
        assertTrue(p.contains("fun stopGenerating(why: TtsStop = TtsStop.BUTTON, forApp: Boolean = false) {"))
        assertTrue(p.substringAfter("fun stopGenerating(why").substringBefore("\n    }").contains("PanelMessages.stop(forApp)"))
        assertTrue(p.split("stopGenerating(TtsStop.OTHER, forApp = true)").size == 4)
        assertTrue(p.substringAfter("private fun leaveForRobot(").substringBefore("\n    }").contains("stopGenerating(TtsStop.OTHER, forApp = true)"))
        assertTrue(p.substringAfter("private fun openList(").substringBefore("\n    }").contains("stopGenerating(TtsStop.OTHER, forApp = true)"))
        assertTrue(p.substringAfter("if (tries.isEmpty())").substringBefore("if (tries.any").contains("stopGenerating(TtsStop.OTHER, forApp = true)"))
        val m = code("PanelMessages.kt")
        assertTrue(m.substringAfter("fun stop(forApp: Boolean = false) {").substringBefore("\n    }").contains("if (hold.stopStops(forApp, inWindow())) stopWindow("))
        assertTrue(m.contains("private fun inWindow() = card?.state == ReplyCardState.STOP_WINDOW && window?.isActive == true"))
    }

    /** fix-car-stay-g: the headset gone, a reply that didn't go says so aloud and as a notice, with no name. */
    @Test fun aReplyThatDidntGoAfterTheHeadsetWentSaysSo() {
        val m = code("PanelMessages.kt")
        val fire = m.substringAfter("private suspend fun fire(").substringBefore("private fun failed(")
        assertTrue(fire.split("else notSentNoName(p)").size == 3)
        val no = m.substringAfter("private fun notSentNoName(").substringBefore("\n    }")
        assertTrue(no.contains("notice(p, MessageStrings.notSentOpenChat().render(langs(p.lang)))") && no.contains("speak(listOf(spoken(MessageStrings.notSentOpenChat(), p.lang)))"))
    }

    /** fix-car-stay-g: Car mode's close keeps what held it; a reply sent in a session knows it; afterWindow always runs. */
    @Test fun theLowsOfReviewF() {
        val close = code("car/CarModeController.kt").substringAfter("private fun closeWhenSaid(").substringBefore("private fun notifyWatchers(")
        assertTrue(close.contains("closeWhenSaid(tries - 1, held || sending)") && close.contains("PanelController.hideAfterAnnouncement(held = held)"))
        val m = code("PanelMessages.kt")
        assertTrue(m.substringAfter("private suspend fun fire(").substringBefore("link().reply(").contains("p.inSession = p.inSession || app.heyrobot.assistant.handsfree.PanelHandsFree.active"))
        assertTrue(m.substringAfter("private fun startWindow(").substringBefore("private suspend fun windowWait(").contains("} finally { afterWindow() }"))
    }

    /** fix-car-132: review-131 Lows 7 to 9, where they live (ReplyHoldTest runs the taps themselves). */
    @Test fun theLowsOfReview131() {
        val m = code("PanelMessages.kt")
        assertTrue(m.substringAfter("ReplyOutcome.LOCKED, ReplyOutcome.AUTH_REQUIRED ->").substringBefore("ReplyOutcome.OPEN_CHAT").let { it.contains("if (hold.away)") && it.contains("notice(p, MessageStrings.unlockThenSend(") })
        assertTrue(m.substringAfter("fun onShow(").substringBefore("\n    }").contains("hold.shown()?.let { PanelController.notify(it) }"))
        // fix-car-133: a notice is held by ReplyHold for its card and state (ReplyHoldTest), and each card change tells it.
        assertTrue(m.substringAfter("private fun notice(p: Pending, text: String) {").substringBefore("\n    }").contains("hold.notice(p.replyId, p.state, text)"))
        assertTrue(m.substringAfter("private fun update(p: Pending) {").substringBefore("\n    }").contains("hold.card(p.replyId, p.state)"))
        assertTrue(m.substringAfter("private fun speak(").substringBefore("\n    }").contains("SpeechText.heardWhole(p.text)"))
        assertTrue(withStrings("PanelController.kt").contains("stopGenerating(TtsStop.CLOSED, forApp = hidingFor == \"action\")"))
        assertTrue(code("car/CarModeActivity.kt").contains("CarBeside.appOpen(app.pkg, opened, no != null, CarModeController.lastBeside)?.let { CarModeController.besideOpened(it) }"))
    }

    /** fix-car-133: the car's read-out of an emoji-only reply ends on the tap line, not "Send it?", as its yes takes a tap. */
    @Test fun aReplyNotHeardWholeIsNotAskedSendIt() {
        assertTrue(code("PanelMessages.kt").contains("val yes = p.plan?.voiceYesCounts == true && VoiceHub.settings.voiceCards && SpeechText.heardWhole(p.sendText)"))
    }

    /**
     * fix-car-133 (long-card-b Low 9), fix-car-134 (review-fix-car-133 Low 3): a quiet turn takes the show's trust, not the car
     * screen's, and with no hands-free session PanelHandsFree.allow lets every line through. So on a car screen that isn't
     * trusted, the reply card's read-out and every result that names someone check the car screen themselves.
     */
    @Test fun anUntrustedQuietTurnWithoutASessionNamesNoOneAloud() {
        val m = code("PanelMessages.kt")
        val gate = "CarModeController.quietTurn && !CarModeController.trusted"
        assertTrue(m.substringAfter("private fun speakCard(").substringBefore("val yes =").contains("if ($gate) return run { p.readOut = null;"))
        assertTrue(m.substringAfter("private fun aloud(p: Pending): Boolean =").substringBefore("\n").endsWith(" && !($gate)"))
        assertTrue(m.split(gate).size - 1 == 2)
        val fire = m.substringAfter("private suspend fun fire(").substringBefore("private fun failed(")
        assertTrue(fire.split("if (aloud(p))").size - 1 == 3)
    }

    /** fix-car-133: a car quiet turn on a screen that isn't trusted reads out no reply card, session or not: the tap line only. */
    @Test fun anUntrustedCarScreenGetsOnlyTheTapLineForAReplyCard() {
        assertTrue(code("PanelMessages.kt").contains("if (CarModeController.quietTurn && !CarModeController.trusted) return run { p.readOut = null; speak(listOf(spoken(TurnStrings.carTap(), lang))) }"))
    }

    /** fix-car-134 (review-fix-car-133 Low 2): the choice card's own words are its read-out in the car, never followed by the tap line; untrusted, they say "here" works by voice and the helper once parked. */
    @Test fun theChoiceCardInTheCarNeverAsksForATap() {
        val w = code("PanelCardVoice.kt").substringAfter("private fun carWaiting(").substringBefore("listenAfterSpeech = true")
        assertTrue(w.contains("val choiceSaid = said && card.id == choiceCard"))
        assertTrue(w.contains("if (!choiceSaid && (card.carTap || CarCardVoice.tapOnly(card) || !port.carTrusted)) port.speakRobot(port.said(TurnStrings.carTap()))"))
        val line = File(root, "core/src/main/java/app/heyrobot/core/i18n/HelperStrings.kt").readText().substringAfter("\"choice_ask_car\"").substringBefore("\n    val ")
        assertTrue(line.contains("once you're parked") && line.contains("l'auto stationnée") && !line.contains("tap") && !line.contains("touche"))
    }

}
