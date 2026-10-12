package app.heyrobot.assistant.handsfree

import app.heyrobot.core.messages.Lang
import app.heyrobot.core.messages.MessageCommand
import app.heyrobot.assistant.PanelMessages
import app.heyrobot.core.output.Line
import app.heyrobot.core.car.NavKind
import app.heyrobot.core.car.PhoneLabel
import app.heyrobot.sys.Phones
import app.heyrobot.core.output.SpokenLang
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CallPlacer
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.headset.HeadsetSession
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.Action
import app.heyrobot.core.Abort
import app.heyrobot.core.RLog
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.TextCommand
import app.heyrobot.core.Tip
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.WhenParser
import app.heyrobot.core.Read
import app.heyrobot.core.car.CallFlow
import app.heyrobot.core.car.CallFlowState
import app.heyrobot.core.car.CallTarget
import app.heyrobot.core.car.CallWay
import app.heyrobot.core.car.CarGate
import app.heyrobot.core.car.CarMessages
import app.heyrobot.core.car.CarNavigate
import app.heyrobot.core.car.CarSpeech
import app.heyrobot.core.car.FlowEffect
import app.heyrobot.core.car.NavDestination
import app.heyrobot.core.car.NavPlan
import app.heyrobot.core.car.NewTexts
import app.heyrobot.core.car.ParkCode
import app.heyrobot.core.car.ParkItem
import app.heyrobot.core.car.WhatsNext
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.output.AnswerCode
import app.heyrobot.core.output.AskWhat
import app.heyrobot.core.output.HandsFreeCommand
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.output.MusicAction
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.OutputLines
import app.heyrobot.core.output.ParkFile
import app.heyrobot.core.output.Parked
import app.heyrobot.core.output.PlaceWord
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.SpokenCard
import app.heyrobot.core.output.SpokenChoice
import app.heyrobot.core.output.Utterance
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.HandsFreeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

// VOICE PROCESS (0.8). Headset and car mode in the panel (design §1.3, §1.4, §3.3, §4.3, §4.5), as routines are in
// PanelRoutines: what the panel asks of them when it opens (a headset's button or car mode: a session routes the
// microphone, then listens), their phrases before the model (HandsFreeRouter: car mode on and off, "repeat", "stop
// listening"; in car mode navigate, call, what's next, music; anywhere "call Mom", the call tool's own words), each
// sentence through OutputGuard while a session runs, and what is said once the answer is over: "Is it headphones only
// you can hear?" the first time a personal sentence would play on a Bluetooth headset, and in the car "Want more?" after
// two sentences. Calls in a session are spoken (CallFlow: choices, read-back, yes); new texts in the car wait for when
// you park. 0.7's messages: a new one announced in private headphones or the car (HandsFreeMessages, which opens the
// panel for it), "read my messages" through 0.7's reading with each piece through OutputGuard, and in the car a reply
// only a tap may send kept for parking. Main thread, except handle (the panel's turn).

object PanelHandsFree {
    /** What a hands-free show asks of the panel: replies spoken or not, and tips to offer (what you kept for parking). */
    data class Show(val speak: Boolean, val tips: List<Tip>? = null, val listenNow: Boolean = false)

    /** A phrase for hands-free, decided before the model. */
    sealed class Cmd {
        data class Voice(val c: HandsFreeCommand) : Cmd()
        data class Private(val yes: Boolean) : Cmd()
        data class More(val yes: Boolean) : Cmd()
        data class CallAnswer(val heard: String) : Cmd()
        /** Kept for parking: [ask] is asked again then. */
        data class Park(val code: ParkCode, val summary: String, val ask: String?) : Cmd()
        /** The answer to an announced message's question ("Read it?", "Reply?", the read-back). */
        data class MessageAnswer(val heard: String) : Cmd()
    }

    private val app get() = VoiceHub.app
    private val lang get() = HandsFreeHub.lang
    private val session: HeadsetSession? get() = HandsFreeHub.session

    /** What this reply said aloud (for "repeat"), the car's sentences so far and what waits for "Want more?". */
    private val spoken = mutableListOf<String>()
    private var carSaid = 0
    private val more = mutableListOf<String>()
    private var awaitingMore = false
    /** Personal sentences held until you say whether the headset is private, and the question's device. */
    private val heldPrivate = mutableListOf<String>()
    private var askPrivate = false
    private var pendingPrivate: Pair<Int, List<String>>? = null
    private var unlockSaid = false
    private var endAfterReply = false
    private var lastPrompt = ""
    /** A spoken call in progress (car or headset): its choices, read-back and yes come with the follow-up. */
    private var callFlow: CallFlowState? = null
    private var callAsked = ""

    // ------------------------------------------------------------------ the panel opens

    /**
     * The panel opened ([source], [args]): a headset's button, "hey robot" with car mode on, or the "From your drive"
     * notification's tap. Null for anything else (the panel as always).
     */
    fun onShow(source: String?, args: Bundle?, locked: Boolean): Show? {
        args?.getString(ARG_PARKED)?.let { return parked() }
        // 0.8: a new message to announce (HandsFreeMessages opened the panel for it): a session, then its question.
        args?.getString(HandsFreeMessages.ARG_ANNOUNCE)?.let { key ->
            reset()
            if (!HandsFreeMessages.opened(key)) return null
            val entry = args.getString(HandsFreeHub.ARG_ENTRY)?.let { e -> EntryKind.entries.firstOrNull { it.name == e } } ?: EntryKind.BVRA
            HandsFreeHub.begin(entry, locked || args.getBoolean(HandsFreeHub.ARG_LOCKED), wake = false)
            return Show(speak = true)
        }
        val headset = source == RobotVoiceService.SRC_HEADSET
        val carWake = CarModeController.on && (source == RobotVoiceService.SRC_WAKE || source == RobotVoiceService.SRC_KEYGUARD)
        if (!headset && !carWake) return null
        reset()
        val entry = args?.getString(HandsFreeHub.ARG_ENTRY)?.let { e -> EntryKind.entries.firstOrNull { it.name == e } } ?: EntryKind.BVRA
        // The car screen's Call: Robot asks who first, and what you say is who to call.
        if (args?.getBoolean(ARG_CALL) == true) {
            intro = CALL_WHO.get(lang)
            callPrefix = true
        }
        HandsFreeHub.begin(entry, locked || args?.getBoolean(HandsFreeHub.ARG_LOCKED) == true, wake = carWake)
        return Show(speak = HandsFreeHub.headset.settings.speakInHeadset || CarModeController.on)
    }

    /** Said before the session first listens (the car screen's Call), once. */
    private var intro: String? = null
    private var callPrefix = false

    fun takeIntro(): String? = intro.also { intro = null } ?: session?.let(HandsFreeMessages::intro)

    /** A headset or car session runs for the car: what the car may hear, and what waits for parking. */
    val inCar: Boolean get() = session?.kind == SessionKind.CAR

    /** "No" to an announcement: nothing more is said, and the session ends once this reply is over. */
    fun endQuietly() {
        endAfterReply = true
    }

    /** A reply kept for when you park ([ask]: asked again then, through every gate). */
    fun keepReply(summary: String, ask: String?) = keep(ParkItem(ParkCode.REPLY, summary, System.currentTimeMillis()), ask)

    /** The panel closed: the session ends and lets go of the headset. */
    fun onHide() {
        session?.cancel()
    }

    fun sessionEnded(s: HeadsetSession) {
        if (session !== s && session != null) return
        pendingPrivate?.let { VoiceLog.add(VoiceEvent.PRIVATE_ASKED, it.first, -1) }
        reset()
        HandsFreeMessages.sessionEnded()
    }

    private fun reset() {
        spoken.clear(); carSaid = 0; more.clear(); awaitingMore = false; heldPrivate.clear(); askPrivate = false; pendingPrivate = null
        unlockSaid = false; endAfterReply = false; callFlow = null; intro = null; callPrefix = false
    }

    /** The panel heard the question ([empty]: nothing came). */
    fun heard(empty: Boolean) {
        session?.heard(empty)
    }

    /** The session waits for the end of the reply: true when something more was said first ("Want more?", the private question). */
    fun atReplyEnd(s: HeadsetSession): Boolean {
        if (endAfterReply) {
            endAfterReply = false
            s.cancel()
            return true
        }
        if (askPrivate && heldPrivate.isNotEmpty() && pendingPrivate == null) {
            askPrivate = false
            val n = s.headsetNumber()
            if (n == 0) { heldPrivate.clear(); return false }
            pendingPrivate = n to heldPrivate.toList()
            heldPrivate.clear()
            lastPrompt = OutputLines.privateQuestion(HandsFreeHub.memory.byNumber(n)?.name.orEmpty(), lang)
            VoiceHub.tts.speak(lastPrompt)
            return true
        }
        if (more.isNotEmpty() && !awaitingMore) {
            awaitingMore = true
            lastPrompt = CarSpeech.WANT_MORE.get(lang)
            VoiceHub.tts.speak(lastPrompt)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ each sentence (OutputGuard)

    /**
     * May [sentence] be said now? Always outside a session. In one: OutputGuard's decision ([personal]: the
     * conversation holds something personal), and in the car at most two sentences, the rest after "Want more?".
     */
    fun allow(sentence: String, personal: () -> Boolean): Boolean {
        val s = session ?: return true
        val cls = if (personal()) SpeechClass.PERSONAL else SpeechClass.PUBLIC
        val ctx = OutputContext.of(s.kind, s.routed(), HandsFreeHub.memory, HandsFreeHub.phone(), s.device, s.commRoute, CarModeController.trusted)
        val guard = HandsFreeHub.headset.settings.guard(HandsFreeHub.headset.safety, HandsFreeHub.car.settings.readAloud)
        return when (val d = OutputGuard.decide(Utterance(cls, SpeechSource.ANSWER, sentence.length), ctx, guard)) {
            is OutputDecision.Speak -> if (s.kind == SessionKind.CAR) car(sentence) else { spoken += sentence; true }
            is OutputDecision.Ask -> {
                if (d.what == AskWhat.PRIVATE_DEVICE) { heldPrivate += sentence; askPrivate = true }
                false
            }
            is OutputDecision.Hold -> {
                VoiceLog.add(VoiceEvent.OUTPUT_HELD, d.why, s.kind.id, s.headsetNumber())
                if (d.sayUnlock && !unlockSaid) {
                    unlockSaid = true
                    VoiceHub.tts.speak(OutputLines.UNLOCK_TO_HEAR.get(lang))
                }
                false
            }
        }
    }

    /** The car's spoken style: two sentences, each cut to the car's length, the rest after "Want more?". */
    private fun car(sentence: String): Boolean {
        if (awaitingMore || carSaid >= CarSpeech.MAX_SENTENCES) { more += sentence; return false }
        carSaid++
        if (sentence.length <= OutputGuard.DRIVING_MAX_CHARS) { spoken += sentence; return true }
        val short = CarSpeech.shorten(sentence, lang)
        val head = short.spoken.removeSuffix(" " + CarSpeech.WANT_MORE.get(lang))
        spoken += head
        VoiceHub.tts.speak(head)
        short.rest?.let { more += it }
        return false
    }

    /** "Repeat": what the last reply said aloud. */
    fun repeat() {
        val again = spoken.toList().ifEmpty { listOfNotNull(lastPrompt.ifEmpty { null }) }
        again.forEach { VoiceHub.tts.speak(it) }
    }

    // ------------------------------------------------------------------ phrases before the model

    /** [q] (said or typed) is a hands-free phrase or an answer Robot waits for; null: the panel goes on as always. */
    fun route(q: String): Cmd? = try {
        spoken.clear()
        carSaid = 0
        val wake = VoiceHub.settings.wakePhrases.map { it.text }
        pendingPrivate?.let { p ->
            pendingPrivate = null
            val a = SpokenChoice.answer(q, SpokenCard(lastPrompt.removeSuffix("?")), lastPrompt, wake)
            if (a.sure && (a.kind == AnswerCode.YES || a.kind == AnswerCode.NO)) return Cmd.Private(a.kind == AnswerCode.YES)
            VoiceLog.add(VoiceEvent.PRIVATE_ASKED, p.first, -1)
        }
        if (callFlow != null) return Cmd.CallAnswer(q)
        if (HandsFreeMessages.waiting) return Cmd.MessageAnswer(q)
        if (callPrefix) {
            callPrefix = false
            val c = HandsFreeRouter.route(q, carMode = true, wakePhrases = wake)
            if (c !is HandsFreeCommand.Call && q.isNotBlank()) return Cmd.Voice(HandsFreeCommand.Call(q.trim().trimEnd('.', '?', '!')))
        }
        if (awaitingMore) {
            awaitingMore = false
            val a = SpokenChoice.answer(q, SpokenCard(lastPrompt.removeSuffix("?")), lastPrompt, wake)
            if (a.sure && (a.kind == AnswerCode.YES || a.kind == AnswerCode.NO)) return Cmd.More(a.kind == AnswerCode.YES)
            more.clear()
        }
        val car = CarModeController.on
        val c = HandsFreeRouter.route(q, car, wake)
        when {
            c == HandsFreeCommand.Repeat || c == HandsFreeCommand.StopListening -> if (session != null) Cmd.Voice(c) else null
            c != null -> Cmd.Voice(c)
            // New texts while driving wait for when you park ("Open Cheogram now": unlocked, they go on as always).
            car && TextCommand.route(q, null, locked = false, typed = false, wakePhrases = wake) == TextCommand.Route.PARSE &&
                CarMessages.newText(HandsFreeHub.car.settings, HandsFreeHub.phone().locked) == NewTexts.KEEP_FOR_PARKING ->
                Cmd.Park(ParkCode.NEW_TEXT, q.trim(), q.trim())
            // The call tool's own words, outside car mode too ("call Mom", "appelle maman"): never the model's.
            else -> (HandsFreeRouter.route(q, carMode = true, wakePhrases = wake) as? HandsFreeCommand.Call)?.let { Cmd.Voice(it) }
        }
    } catch (t: Throwable) {
        RLog.w("handsfree_route", t)
        null
    }

    // ------------------------------------------------------------------ doing it

    suspend fun handle(replyId: String, cmd: Cmd, host: PanelRoutines.Host) {
        try {
            when (cmd) {
                is Cmd.Voice -> voice(replyId, cmd.c, host)
                is Cmd.Private -> private(replyId, cmd.yes, host)
                is Cmd.More -> more(replyId, cmd.yes, host)
                is Cmd.CallAnswer -> callAnswer(replyId, cmd.heard, host)
                is Cmd.Park -> park(replyId, cmd, host)
                is Cmd.MessageAnswer -> HandsFreeMessages.answer(replyId, cmd.heard, host)
            }
        } finally {
            host.finish(replyId)
        }
    }

    private fun reply(replyId: String, host: PanelRoutines.Host, text: String) {
        host.text(replyId, text)
        host.say(text)
    }

    private suspend fun voice(replyId: String, c: HandsFreeCommand, host: PanelRoutines.Host) {
        val fr = lang == SpokenLang.FR
        when (c) {
            HandsFreeCommand.CarModeOn -> when {
                !HandsFreeHub.car.on -> reply(replyId, host, if (fr) "Le mode auto est désactivé dans Réglages › Mode auto." else "Car mode is off in Settings › Car mode.")
                CarModeController.on -> reply(replyId, host, if (fr) "Le mode auto est déjà activé." else "Car mode is already on.")
                else -> {
                    host.text(replyId, if (fr) "Mode auto…" else "Starting car mode…")
                    CarModeController.start()
                }
            }
            HandsFreeCommand.CarModeOff -> if (!CarModeController.on) reply(replyId, host, if (fr) "Le mode auto n'est pas activé." else "Car mode isn't on.")
                else { host.text(replyId, CarModeController.END_TEXT.get(lang)); CarModeController.end() }
            HandsFreeCommand.Repeat -> { host.text(replyId, spoken.joinToString(" ").ifEmpty { "…" }); repeat() }
            HandsFreeCommand.StopListening -> { endAfterReply = true; reply(replyId, host, if (fr) "D'accord." else "Okay.") }
            is HandsFreeCommand.Navigate -> navigate(replyId, c, host)
            is HandsFreeCommand.Call -> call(replyId, c, host)
            HandsFreeCommand.WhatsNext -> whatsNext(replyId, host)
            // 0.7's messages, when their own router let these words go (Settings › Messages off, or nothing read here yet).
            HandsFreeCommand.ReadMessages -> PanelMessages.handsFreeCommand(replyId,
                MessageCommand.Read(if (fr) Lang.FR else Lang.EN))
            is HandsFreeCommand.Reply -> if (!HandsFreeMessages.reply(replyId, c.text, host)) PanelMessages.handsFreeCommand(replyId,
                MessageCommand.Reply(null, c.text, if (fr) Lang.FR else Lang.EN))
            is HandsFreeCommand.Music -> music(replyId, c.action, host)
        }
    }

    // ------------------------------------------------------------------ calls

    private suspend fun call(replyId: String, c: HandsFreeCommand.Call, host: PanelRoutines.Host) {
        val asked = listOfNotNull(c.who, c.label).joinToString(" ")
        // Outside a hands-free session: the call tool's card, as typed (the dialer opens, you tap Call). So too in a headset
        // session where a personal sentence may not play (the read-back names who and their number): headphones Robot doesn't
        // know are private, or the lock screen; the card shows it instead.
        val s0 = session
        if (s0 == null || (!inCar && !personalPlays(s0))) {
            host.tool(replyId, RoutineStep.Tool(Action.Call(asked)))
            return
        }
        val s = VoiceHub.settings
        val region = s.regionFor(Phones.deviceRegion(app))
        val candidates = withContext(Dispatchers.IO) { CallPlacer.candidates(app, c.who, s) }.let { all ->
            // "on her cell", "au bureau": the numbers of that kind first, when there are some.
            val label = c.label?.let { l -> PhoneLabel.entries.firstOrNull { it.en == l || it.name.lowercase() == l } }
            all.filter { label == null || it.label == label }.ifEmpty { all }
        }
        val phone = HandsFreeHub.phone()
        // A direct call after the spoken yes is car mode's alone: a headset session's call opens the dialer.
        val rules = CallPlacer.rules(app).forSession(car = inCar)
        val plan = CallTarget.plan(c.who, candidates, rules, phone.locked, inCar && CarModeController.trusted, lang) { CallPlacer.isEmergency(app, it, region) }
        callAsked = c.who
        val (st, fx) = CallFlow.start(plan, c.who, lang)
        callFlow = st.takeUnless { it.done }
        flow(replyId, fx, host)
    }

    private suspend fun callAnswer(replyId: String, heard: String, host: PanelRoutines.Host) {
        val st = callFlow ?: return
        val (next, fx) = CallFlow.on(st, heard, lang, VoiceHub.settings.wakePhrases.map { it.text })
        callFlow = next.takeUnless { it.done }
        flow(replyId, fx, host)
    }

    private suspend fun flow(replyId: String, fx: List<FlowEffect>, host: PanelRoutines.Host) {
        val text = StringBuilder()
        for (e in fx) when (e) {
            is FlowEffect.Say -> {
                text.append(if (text.isEmpty()) "" else " ").append(e.text)
                lastPrompt = e.text
                // The card's question is Robot's own line: it plays through the call route as a card (never held as an answer).
                VoiceHub.tts.speak(e.text)
            }
            is FlowEffect.Place -> {
                val direct = e.way == CallWay.CALL
                val region = VoiceHub.settings.regionFor(Phones.deviceRegion(app))
                val r = withContext(Dispatchers.IO) {
                    CallPlacer.run(app, Action.Call(callAsked, e.number, emergency = CallPlacer.isEmergency(app, e.number, region), direct = direct)) { i ->
                        try { app.startActivity(i); true } catch (_: Throwable) { false }
                    }
                }
                text.append(if (text.isEmpty()) "" else " ").append(r.detail)
                if (!direct) host.leave()
            }
            is FlowEffect.Park -> keep(ParkItem(e.code, e.summary, System.currentTimeMillis()), "call $callAsked")
            is FlowEffect.Send -> Unit // 0.7's replies: not in this version.
        }
        if (text.isNotEmpty()) host.text(replyId, text.toString())
    }

    // ------------------------------------------------------------------ navigation, what's next, music

    private suspend fun navigate(replyId: String, c: HandsFreeCommand.Navigate, host: PanelRoutines.Host) {
        when (c.place) {
            // The nearest place: the 0.4.0 flow (online, its first-use card), as typed.
            PlaceWord.NEAREST -> host.tool(replyId, RoutineStep.Tool(Action.MapsNavigate(c.what, nearest = true)))
            PlaceWord.OTHER -> {
                VoiceLog.add(VoiceEvent.CAR_NAV, NavKind.SEARCH)
                host.tool(replyId, RoutineStep.Tool(Action.MapsSearch(c.what)))
                host.say(CarNavigate.PICK_WHEN_STOPPED.get(lang))
            }
            PlaceWord.HOME, PlaceWord.WORK -> {
                val phone = HandsFreeHub.phone()
                val here = withContext(Dispatchers.IO) { CoarseLocation.find(app, 3_000L, Abort(4_000L)).fix?.let { it.lat to it.lon } }
                val dest = if (c.place == PlaceWord.HOME) NavDestination.Home else NavDestination.Work
                val plan = CarNavigate.plan(dest, HandsFreeFiles.store(app).carSettings(), here, phone.locked, CarModeController.trusted, lang)
                VoiceLog.add(VoiceEvent.CAR_NAV, plan.kind)
                if (plan is NavPlan.Route) {
                    val opened = try { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(plan.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Throwable) { false }
                    if (!opened) return reply(replyId, host, if (lang == SpokenLang.FR) "Organic Maps n'est pas installé." else "Organic Maps isn't installed.")
                    reply(replyId, host, plan.say)
                    host.leave()
                } else {
                    reply(replyId, host, plan.say)
                }
            }
        }
    }

    private suspend fun whatsNext(replyId: String, host: PanelRoutines.Host) {
        val phone = HandsFreeHub.phone()
        // A car function: on the lock screen only in the trusted session (CarGate), else "Unlock once to use car features".
        if (phone.locked && !CarModeController.trusted) return reply(replyId, host, CarGate.UNLOCK_ONCE.get(lang))
        val now = ZonedDateTime.now()
        val range = (WhenParser.range("today", now) as? Read.Ok)?.value ?: return reply(replyId, host, "…")
        val events = withContext(Dispatchers.IO) { if (Calendars.readGranted(app)) Calendars.events(app, range) else null }
            ?: return reply(replyId, host, if (lang == SpokenLang.FR) "Permets à Robot de lire ton agenda dans l'app Robot." else "Allow Robot to read your calendar in the Robot app.")
        // Your calendar: personal, so OutputGuard holds it where a personal sentence may not play.
        // An event's title, as whoever made the event wrote it: content, and personal (core Quoted).
        val line = WhatsNext.line(events, now, lang)
        host.quoted(replyId)
        host.text(replyId, line)
        host.say(line, personal = true)
    }

    /** A personal answer may play in [s] now (OutputGuard): private headphones or the car, and not held by the lock screen. */
    private fun personalPlays(s: HeadsetSession): Boolean {
        val ctx = OutputContext.of(s.kind, s.routed(), HandsFreeHub.memory, HandsFreeHub.phone(), s.device, s.commRoute, CarModeController.trusted)
        val guard = HandsFreeHub.headset.settings.guard(HandsFreeHub.headset.safety, HandsFreeHub.car.settings.readAloud)
        return OutputGuard.decide(Utterance(SpeechClass.PERSONAL, SpeechSource.ANSWER, 1), ctx, guard) is OutputDecision.Speak
    }

    private fun music(replyId: String, a: MusicAction, host: PanelRoutines.Host) {
        val am = app.getSystemService(android.media.AudioManager::class.java)
        try {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, a.keyCode))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, a.keyCode))
        } catch (t: Throwable) { RLog.w("car_music", t) }
        VoiceLog.add(VoiceEvent.CAR_MUSIC, a.ordinal + 1)
        host.text(replyId, "♪")
    }

    // ------------------------------------------------------------------ the private question, "Want more?", parking

    private fun private(replyId: String, yes: Boolean, host: PanelRoutines.Host) {
        val p = pendingPrivate ?: (session?.headsetNumber() ?: 0).let { it to emptyList<String>() }
        pendingPrivate = null
        if (p.first > 0) HandsFreeHub.editDevices { it.answerPrivate(p.first, yes) }
        VoiceLog.add(VoiceEvent.PRIVATE_ASKED, p.first, if (yes) 1 else 0)
        val fr = lang == SpokenLang.FR
        if (yes) {
            host.text(replyId, if (fr) "D'accord : seulement toi l'entends." else "Okay: only you hear it.")
            p.second.forEach { VoiceHub.tts.speak(it) }
        } else {
            reply(replyId, host, if (fr) "D'accord. Les réponses personnelles restent à l'écran." else "Okay. Personal answers stay on the screen.")
        }
    }

    private fun more(replyId: String, yes: Boolean, host: PanelRoutines.Host) {
        val rest = more.toList()
        more.clear()
        if (!yes) return reply(replyId, host, if (lang == SpokenLang.FR) "D'accord." else "Okay.")
        host.text(replyId, rest.joinToString(" "))
        // Two more at a time, the rest after "Want more?" again.
        rest.forEachIndexed { i, s -> if (i < CarSpeech.MAX_SENTENCES) { spoken += s; VoiceHub.tts.speak(s) } else more += s }
    }

    private fun park(replyId: String, c: Cmd.Park, host: PanelRoutines.Host) {
        keep(ParkItem(c.code, c.summary, System.currentTimeMillis()), c.ask)
        reply(replyId, host, CarMessages.KEEP_TEXT.get(lang))
    }

    internal fun keep(item: ParkItem, ask: String?) {
        HandsFreeFiles.store(app).editParked { ParkFile.add(it, Parked(item, ask)) }
        VoiceLog.add(VoiceEvent.CAR_PARKED, item.code)
        CarModeController.parkChanged()
    }

    /** The "From your drive" notification's tap: what you kept, as tips to ask again (each through every gate). */
    private fun parked(): Show {
        val list = HandsFreeFiles.store(app).parked()
        HandsFreeFiles.store(app).editParked { emptyList() }
        VoiceLog.add(VoiceEvent.CAR_PARKED, 0, list.size)
        // 1.0: a tip only fills the text field: it holds a sender's name (core CarMessages.parkedTips).
        return Show(speak = false, tips = CarMessages.parkedTips(list.map { it.ask }))
    }

    const val ARG_PARKED = "robot_parked"
    /** The car screen's Call: "Who should I call?", then who you say. */
    const val ARG_CALL = "robot_handsfree_call"
    private val CALL_WHO = Line("Who should I call?", "Qui veux-tu appeler?")
}
