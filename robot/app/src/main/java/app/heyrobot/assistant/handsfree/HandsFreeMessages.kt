package app.heyrobot.assistant.handsfree

import app.heyrobot.assistant.conversation.ConversationSession
import android.os.Bundle
import android.os.SystemClock
import app.heyrobot.assistant.MessagesLink
import app.heyrobot.assistant.PanelController
import app.heyrobot.assistant.PanelMessages
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.headset.HeadsetSession
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.assistant.routines.RoutineVoice
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.car.CarAnnounceFrom
import app.heyrobot.core.car.CarAnnouncement
import app.heyrobot.core.car.CarMessages
import app.heyrobot.core.car.FlowEffect
import app.heyrobot.core.car.MessageFlow
import app.heyrobot.core.car.MessageFlowContext
import app.heyrobot.core.car.MessageFlowState
import app.heyrobot.core.car.MessageStep
import app.heyrobot.core.car.ParkCode
import app.heyrobot.core.car.ParkItem
import app.heyrobot.core.car.ReplyEnd
import app.heyrobot.core.headset.AnnounceAnswer
import app.heyrobot.core.headset.AnnounceCode
import app.heyrobot.core.headset.AnnounceDecision
import app.heyrobot.core.headset.AnnounceFrom
import app.heyrobot.core.headset.AnnounceNext
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.headset.HeadsetAnnounce
import app.heyrobot.core.messages.AnnounceSenders
import app.heyrobot.core.messages.HandsFreeReplies
import app.heyrobot.core.messages.Lang
import app.heyrobot.core.messages.MessagePeople
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.ReplyRequest
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.ReplyResult
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.Trust
import app.heyrobot.core.output.Utterance
import app.heyrobot.core.output.handsFree
import app.heyrobot.core.output.notice
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.PeopleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// VOICE PROCESS (0.8). New messages announced hands-free (design §3.8 H3, §4.10 C3), on 0.7's messages and their rules.
// The main process's MessageTap says only that a conversation has a new message (its key, and whether Do Not Disturb
// lets it through: Cmd.MESSAGE_NEW); this asks the bridge for it as any read does, so 0.7's lock rules hold ("Messages on
// the lock screen", Off by default: nothing is announced while locked), and decides with core: Settings › Messages ›
// "Announce new messages" (off) first; then in car mode the car (Settings › Car mode, its trusted session), else private
// headphones connected now (Settings › Headset, HeadsetAnnounce), never the phone's speaker or a Bluetooth speaker. An
// announcement opens the panel for a headset or car session (as the headset's button does): the session routes the
// microphone, Robot asks "Message from Marie. Read it?" (each line through OutputGuard first), and the answer comes back
// here before 0.7's router (after its echo check). "Yes" reads it as "read my messages" would (0.7's reading: History's
// placeholder, the quarantine, the echo check, marked read by Robot and "After Robot reads"); "no" is quiet and the
// session ends. In the car a reply follows core MessageFlow (read back word for word, then "send" or "yes"), and only
// where 0.7's rules let a spoken yes send it (HandsFreeReplies): a reply only a tap may send waits for parking with your
// words. A message's words never act: the words heard while Robot reads them are the message's (0.7's echo check), and
// a "send" within 3 s of them doesn't count. Nothing here writes a message; its words live in RAM until the session ends.
// Main thread, except [answer] (the panel's turn).

object HandsFreeMessages {
    /** The panel's show args: the conversation (its key in RAM) an announcement opened it for. */
    const val ARG_ANNOUNCE = "robot_announce"

    private val app get() = VoiceHub.app
    private val scope = MainScope()
    private val lang: SpokenLang get() = HandsFreeHub.lang
    private fun msgLang(l: SpokenLang) = if (l == SpokenLang.FR) Lang.FR else Lang.EN

    /** An announcement decided, waiting for its panel to open (RAM only). */
    private class Opening(val thread: MessageThread, val notice: MessageNotice, val car: Boolean, val first: String, val readNow: Boolean,
        val asks: Boolean, val context: MessageFlowContext?, val at: Long)

    /** One announcement's conversation, while its session runs. */
    private class Flow(val thread: MessageThread, val notice: MessageNotice, val car: Boolean, val context: MessageFlowContext?) {
        var said = ""
        var tries = 0
        var rereads = 0
        /** The car's reply flow once the message was read (core MessageFlow), or null. */
        var car2: MessageFlowState? = null
        /** Still waiting for an answer (to "Read it?", "Reply?", the dictation or the read-back). */
        var waiting = true
        var readNow = false
        var first: String? = null
    }

    private var opening: Opening? = null
    private var flow: Flow? = null
    /** A new message that came while Robot was busy: announced once she is free, if still recent. */
    private var queued: Triple<String, Boolean, Long>? = null

    /** An announcement waits for your answer: its words come here, after 0.7's echo check (PanelMessages.route). */
    val waiting: Boolean get() = flow?.waiting == true

    // ------------------------------------------------------------------ a message came

    /** Cmd.MESSAGE_NEW: the conversation [key] has a new message; [dnd]: Do Not Disturb lets it through. */
    fun arrived(key: String, dnd: Boolean) {
        scope.launch {
            try { decide(key, dnd) } catch (_: Throwable) { RLog.w("announce") }
        }
    }

    private fun log(code: AnnounceCode, read: Boolean = false) = VoiceLog.add(VoiceEvent.ANNOUNCE, code.id, if (read) 1 else 0)

    /** Robot is talking, listening or showing something: the announcement waits ([queued]). */
    private fun busy(): Boolean = PanelController.shown || HandsFreeHub.session != null || VoiceHub.tts.isSpeaking || RoutineVoice.active ||
        ConversationSession.running

    /** Private headphones Robot's sound goes to now (Trust.PRIVATE: worn, or a headset you said only you hear), or null. */
    private fun privateHeadphones(): SeenDevice? = HandsFreeHub.devices().filter { it.sink && it.type in HEADPHONES }
        .firstOrNull { HandsFreeHub.memory.trust(it) == Trust.PRIVATE }

    private suspend fun decide(key: String, dnd: Boolean) {
        val f = withContext(Dispatchers.IO) { MessagesStore.reload(app) }
        val s = f.settings
        if (!s.on || !s.announce) return
        if (RobotVoiceService.instance == null) return
        val car = CarModeController.on
        val headphones = if (car) null else privateHeadphones()
        if (!car && headphones == null) return log(AnnounceCode.NOT_PRIVATE)
        val phone = HandsFreeHub.phone()
        if (phone.callActive) return log(AnnounceCode.CALL)
        if (busy()) {
            queued = Triple(key, dnd, SystemClock.elapsedRealtime())
            return log(AnnounceCode.BUSY)
        }
        // 0.7's lock rules first: off the lock screen unless "Messages on the lock screen" is "Read and reply".
        if (phone.locked && s.locked != MessagesLocked.READ_AND_REPLY) return log(AnnounceCode.LOCKED)
        val people = withContext(Dispatchers.IO) { try { PeopleFiles.store(app).list() } catch (_: Throwable) { emptyList() } }
        val listed = MessagesLink(app).use { link -> withTimeoutOrNull(LIST_MS) { link.list() } } ?: return
        val held = listed.second.firstOrNull { it.key == key } ?: return
        val thread = MessagePeople.named(held, people)
        val n = thread.notice(dnd) ?: return
        val l = lang
        if (car) {
            val cs = HandsFreeHub.car.settings
            val chosen = cs.announceFrom != CarAnnounceFrom.CHOSEN_PEOPLE || AnnounceSenders.chosen(held, people, f.announcePeople)
            val ctx = MessageFlowContext(cs, HandsFreeHub.headset.safety, phone.locked, CarModeController.trusted, l,
                HandsFreeReplies.of(held, s, phone.locked, MessagePeople.withoutCard(held, people, f.noCard)))
            when (val a = CarMessages.announce(n, cs, phone.locked, CarModeController.trusted, chosen, l)) {
                is CarAnnouncement.Skip -> return log(a.why)
                is CarAnnouncement.Announce -> open(Opening(thread, n, true, a.say, readNow = false, asks = !n.hidden, ctx, SystemClock.elapsedRealtime()), headphones)
                is CarAnnouncement.ReadNow -> open(Opening(thread, n, true, a.say, readNow = true, asks = false, ctx, SystemClock.elapsedRealtime()), headphones)
            }
        } else {
            val hs = HandsFreeHub.headset.settings
            val chosen = hs.announceFrom == AnnounceFrom.EVERYONE || AnnounceSenders.chosen(held, people, f.announcePeople)
            when (val d = HeadsetAnnounce.decide(n, hs, headsetRouted = true, trust = Trust.PRIVATE, phone = phone, robotBusy = false, senderAllowed = chosen, lang = l)) {
                is AnnounceDecision.Skip -> return log(d.why)
                is AnnounceDecision.Announce -> open(Opening(thread, n, false, d.say, readNow = false, asks = d.asks, null, SystemClock.elapsedRealtime()), headphones)
                is AnnounceDecision.ReadNow -> open(Opening(thread, n, false, d.say, readNow = true, asks = false, null, SystemClock.elapsedRealtime()), headphones)
            }
        }
    }

    /** The panel opens for a headset or car session, as the headset's button opens it. */
    private fun open(o: Opening, headphones: SeenDevice?) {
        opening = o
        val wired = headphones?.type in setOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET)
        val shown = RobotVoiceService.instance?.show(RobotVoiceService.SRC_HEADSET, Bundle().apply {
            putString(HandsFreeHub.ARG_ENTRY, (if (wired) EntryKind.HOOK else EntryKind.BVRA).name)
            putBoolean(HandsFreeHub.ARG_LOCKED, HandsFreeHub.phone().locked)
            putString(ARG_ANNOUNCE, o.thread.key)
        }) == true
        if (!shown) opening = null
    }

    // ------------------------------------------------------------------ the panel opened for it

    /** PanelHandsFree.onShow: the panel opened for [key]'s announcement; false when it is no longer the one waiting. */
    fun opened(key: String): Boolean {
        val o = opening?.takeIf { it.thread.key == key && SystemClock.elapsedRealtime() - it.at < OPEN_MS } ?: return false
        opening = null
        flow = Flow(o.thread, o.notice, o.car, o.context).apply {
            first = o.first
            readNow = o.readNow
            waiting = o.asks && !o.readNow
        }
        return true
    }

    /**
     * What the session says before it first listens: the announcement ("Message from Marie. Read it?"), or the message
     * itself when it is read at once; null when OutputGuard says it may not play here now (the session then ends quietly).
     */
    fun intro(s: HeadsetSession): String? {
        val f = flow ?: return null
        val first = f.first ?: return null
        f.first = null
        // The sender's name is personal: only where OutputGuard lets a message play (private headphones, the car).
        val u = Utterance(SpeechClass.PERSONAL, SpeechSource.MESSAGE, first.length, f.notice.dndAllows)
        val ctx = OutputContext.of(s.kind, s.routed(), HandsFreeHub.memory, HandsFreeHub.phone(), s.device, s.commRoute, CarModeController.trusted)
        val guard = HandsFreeHub.headset.settings.guard(HandsFreeHub.headset.safety, HandsFreeHub.car.settings.readAloud)
        val ok = when (val d = OutputGuard.decide(u, ctx, guard)) {
            is OutputDecision.Speak -> true
            // The car's "Others may hear it. Read it aloud?" is what the announcement asks.
            is OutputDecision.Ask -> f.car && !f.readNow
            is OutputDecision.Hold -> { VoiceLog.add(VoiceEvent.OUTPUT_HELD, d.why, s.kind.id, s.headsetNumber()); false }
        }
        if (!ok) {
            // Nothing is said: the session ends, and with it the panel it opened (sessionEnded).
            log(AnnounceCode.NOT_PRIVATE)
            f.waiting = false
            VoiceHub.main.post { s.cancel() }
            return null
        }
        f.said = first
        if (f.readNow) {
            // Read at once ("Read the text: always", the car's "Read the whole message"): said now, and handled as 0.7's
            // reading (the quarantine, the echo check, marked read); then in the car "Reply?".
            scope.launch {
                try { PanelMessages.handsFreeRead(null, f.thread, msgLang(lang), HandsFreeHub.phone().locked) } catch (_: Throwable) { RLog.w("announce_read") }
                log(AnnounceCode.READ, read = true)
                afterReading(f, null, null)
            }
        } else if (!f.waiting) {
            log(AnnounceCode.ANNOUNCED)
        }
        return first
    }

    // ------------------------------------------------------------------ your answer

    /** [heard] ("" for silence) in answer to what the announcement asked; [replyId] is the panel's turn for it. */
    suspend fun answer(replyId: String, heard: String, host: PanelRoutines.Host) {
        val f = flow ?: return
        val wake = VoiceHub.settings.wakePhrases.map { it.text }
        val st = f.car2
        if (st == null) {
            // "Read it?" (the headset, and the car before reading).
            when (val next = AnnounceAnswer.next(heard, f.said, f.tries, f.rereads, lang, wake)) {
                AnnounceNext.Read -> read(replyId, f, host)
                AnnounceNext.Quiet -> {
                    f.waiting = false
                    log(AnnounceCode.ANNOUNCED)
                    host.text(replyId, if (lang == SpokenLang.FR) "D'accord." else "Okay.")
                    PanelHandsFree.endQuietly()
                }
                is AnnounceNext.Ask -> {
                    if (next.say == f.said) f.rereads++ else f.tries++
                    say(replyId, host, next.say, listen = true)
                }
            }
            return
        }
        val c = f.context ?: return
        // A "send" within 3 s of Robot saying the message's words doesn't count (0.7's echo rule): asked again.
        if (st.step == MessageStep.READ_BACK && !PanelMessages.voiceYesNow()) {
            return say(replyId, host, if (lang == SpokenLang.FR) MessageQuarantine.SAY_YES_AGAIN_FR.substringAfter(", ") else "Say send again.", listen = true)
        }
        val (next, fx) = MessageFlow.on(st, heard, c, wake)
        f.car2 = next
        f.waiting = next.step != MessageStep.DONE
        effects(replyId, f, fx, host)
    }

    private suspend fun read(replyId: String, f: Flow, host: PanelRoutines.Host) {
        val s = HandsFreeHub.session
        val page = PanelMessages.handsFreeRead(replyId, f.thread, msgLang(lang), HandsFreeHub.phone().locked)
        host.text(replyId, page.shown)
        // You asked ("yes"): personal, through OutputGuard where it plays now (the headset still private, the car still there).
        val ok = s != null && OutputGuard.decide(Utterance(SpeechClass.PERSONAL, SpeechSource.ANSWER, page.shown.length),
            OutputContext.of(s.kind, s.routed(), HandsFreeHub.memory, HandsFreeHub.phone(), s.device, s.commRoute, CarModeController.trusted),
            HandsFreeHub.headset.settings.guard(HandsFreeHub.headset.safety, HandsFreeHub.car.settings.readAloud)) is OutputDecision.Speak
        if (ok) PanelMessages.speakHandsFree(page.spoken)
        log(AnnounceCode.ANNOUNCED, read = ok)
        afterReading(f, replyId, host)
    }

    /** After reading: in the car, "Reply?" where one may be offered (core MessageFlow); otherwise done. */
    private fun afterReading(f: Flow, replyId: String?, host: PanelRoutines.Host?) {
        val c = f.context
        if (!f.car || c == null || !CarMessages.offersReply(f.notice, c.settings, c.safety)) {
            f.waiting = false
            return
        }
        val ask = CarMessages.ASK_REPLY.get(lang)
        f.car2 = MessageFlowState(f.notice, MessageStep.OFFERED, said = ask)
        f.waiting = true
        if (replyId != null && host != null) host.append(replyId, ask)
        VoiceHub.tts.speak(ask)
    }

    /**
     * "Reply …" in the car for the conversation announced here (HandsFreeRouter.Reply after 0.7's router let it go): the
     * car's read-back. False when no announced conversation is at hand (0.7's reply then).
     */
    suspend fun reply(replyId: String, text: String, host: PanelRoutines.Host): Boolean {
        val f = flow ?: return false
        val c = f.context ?: return false
        if (!f.car || !PanelHandsFree.inCar) return false
        val (next, fx) = MessageFlow.reply(f.notice, text, c)
        f.car2 = next
        f.waiting = next.step != MessageStep.DONE
        effects(replyId, f, fx, host)
        return true
    }

    private suspend fun effects(replyId: String, f: Flow, fx: List<FlowEffect>, host: PanelRoutines.Host) {
        val shown = StringBuilder()
        for (e in fx) when (e) {
            is FlowEffect.Say -> {
                shown.append(if (shown.isEmpty()) "" else " ").append(e.text)
                f.said = e.text
                VoiceHub.tts.speak(e.text)
            }
            is FlowEffect.Send -> {
                val answer = PanelMessages.handsFreeReply(ReplyRequest(e.conversationKey, e.text, f.thread.newestIncoming))
                val r = answer?.outcome?.handsFree() ?: ReplyResult.FAILED
                VoiceLog.add(VoiceEvent.CAR_REPLY, if (r == ReplyResult.HANDED_OVER) 1 else 0, when (r) {
                    ReplyResult.HANDED_OVER -> ReplyEnd.SENT
                    ReplyResult.NEW_MESSAGES -> ReplyEnd.NEW_MESSAGES
                    ReplyResult.LOCKED -> ReplyEnd.LOCKED
                    ReplyResult.NO_REPLY_ACTION, ReplyResult.GONE -> ReplyEnd.NOT_REPLYABLE
                    ReplyResult.FAILED -> ReplyEnd.HELD
                }.id)
                // Not handed over: your words wait for parking, where the panel's card sends them with a tap.
                if (r != ReplyResult.HANDED_OVER) PanelHandsFree.keep(ParkItem(ParkCode.REPLY, replyTo(f), System.currentTimeMillis()),
                    CarMessages.parkedAsk(f.notice, e.text, lang))
                val line = CarMessages.result(r, f.notice.app, lang)
                shown.append(if (shown.isEmpty()) "" else " ").append(line)
                VoiceHub.tts.speak(line)
            }
            is FlowEffect.Park -> {
                PanelHandsFree.keep(ParkItem(e.code, e.summary, System.currentTimeMillis()), e.ask)
                VoiceLog.add(VoiceEvent.CAR_REPLY, 0, ReplyEnd.HELD.id)
            }
            is FlowEffect.Place -> Unit
        }
        if (shown.isNotEmpty()) host.text(replyId, shown.toString())
    }

    private fun replyTo(f: Flow) = (if (lang == SpokenLang.FR) "Répondre à " else "Reply to ") + f.notice.sender

    private fun say(replyId: String, host: PanelRoutines.Host, text: String, listen: Boolean) {
        host.text(replyId, text)
        VoiceHub.tts.speak(text)
        if (listen) flow?.said = text
    }

    // ------------------------------------------------------------------ the session ended

    /**
     * The session ended: the announcement's words go from here (the panel's turns keep 0.7's placeholders), the panel it
     * opened goes once nothing more is said, and a message that came while Robot was busy is announced now if recent.
     */
    fun sessionEnded() {
        val f = flow
        flow = null
        // Asked, and no answer came: the message stays in its app.
        if (f != null && f.waiting && f.car2 == null) log(AnnounceCode.ANNOUNCED)
        if (f != null) PanelController.hideAfterAnnouncement()
        val q = queued ?: return
        queued = null
        if (SystemClock.elapsedRealtime() - q.third < QUEUE_MS) VoiceHub.main.postDelayed({ if (!busy()) arrived(q.first, q.second) }, AGAIN_MS)
    }

    /** The panel hid with no session (Robot was busy on screen): a message that waited is announced now if recent. */
    fun panelHidden() {
        if (HandsFreeHub.session != null) return
        val q = queued ?: return
        queued = null
        if (SystemClock.elapsedRealtime() - q.third < QUEUE_MS) VoiceHub.main.postDelayed({ if (!busy()) arrived(q.first, q.second) }, AGAIN_MS)
    }

    /** Where an announcement may play by type: headphones and headsets (a Bluetooth or LE speaker never). */
    private val HEADPHONES = setOf(RouteType.BT_SCO, RouteType.BT_A2DP, RouteType.BLE_HEADSET, RouteType.HEARING_AID, RouteType.WIRED_HEADSET,
        RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET)

    private const val LIST_MS = 5_000L
    /** An announcement whose panel didn't open within this long is dropped. */
    private const val OPEN_MS = 10_000L
    /** A message that came while Robot was busy is announced afterwards only if it came this recently. */
    private const val QUEUE_MS = 120_000L
    private const val AGAIN_MS = 1_500L
}
