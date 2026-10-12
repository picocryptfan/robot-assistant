package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import app.heyrobot.assistant.conversation.FastTranslation
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.GemmaTranslator
import app.heyrobot.core.LangCode
import app.heyrobot.core.LangId
import app.heyrobot.core.LangPair
import app.heyrobot.core.LineTranslator
import app.heyrobot.core.MtGuard
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.Person
import app.heyrobot.core.Priority
import app.heyrobot.core.RLog
import app.heyrobot.core.SpeechLang
import app.heyrobot.core.SpeechText
import app.heyrobot.core.TranslatePolicy
import app.heyrobot.core.TranslateUse
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.messages.ActionOrigin
import app.heyrobot.core.messages.AfterReading
import app.heyrobot.core.messages.CardEnd
import app.heyrobot.core.messages.ChatLinks
import app.heyrobot.core.messages.GuardCode
import app.heyrobot.core.messages.Lang
import app.heyrobot.core.messages.LockedMessages
import app.heyrobot.core.messages.MessageAccess
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageCommand
import app.heyrobot.core.messages.MessageLanguage
import app.heyrobot.core.messages.MessagePeople
import app.heyrobot.core.messages.MessagePrompts
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.core.messages.MessageReader
import app.heyrobot.core.messages.MessageReading
import app.heyrobot.core.messages.ReplyKept
import app.heyrobot.core.messages.MessageSummaries
import app.heyrobot.core.messages.MessageSummaryText
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessageTranslation
import app.heyrobot.core.messages.MessageVoices
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.messages.MessagesStatus
import app.heyrobot.core.messages.MsgAppCode
import app.heyrobot.core.messages.MsgRefusal
import app.heyrobot.core.messages.ReadAloud
import app.heyrobot.core.messages.ReadPage
import app.heyrobot.core.messages.RefuseWhy
import app.heyrobot.core.messages.ReplyOutcome
import app.heyrobot.core.messages.ReplyPlan
import app.heyrobot.core.messages.ReplyPlanner
import app.heyrobot.core.messages.ReplyRequest
import app.heyrobot.core.messages.ReplyRouteCode
import app.heyrobot.core.messages.ReplySource
import app.heyrobot.core.messages.ReplyText
import app.heyrobot.core.messages.Spoken
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.CheogramIntents
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.ui.common.ReplyCardState
import app.heyrobot.ui.common.ReplyCardView
import app.heyrobot.ui.common.ReplyOp
import app.heyrobot.voice.ConversationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

// VOICE PROCESS. 0.7, your messages in the panel (design §B7–§B10; core messages decides, this does): "read my
// messages" reads them conversation by conversation, each in its own language's voice, then listens once for "reply",
// "next" or "stop"; "what did Mom say?" by what you call her in People; a summary and a translation are one-shot
// requests to Robot's model with no tools (a translation between French and English may come from the fast engine);
// a reply goes on a card to the conversation's own notification, and is sent by the main process (MessagesBridge) only
// after your tap (a spoken yes only with "Answer reply cards by voice", never for a group, a draft or a translation),
// or after a 3-second stop for a person you chose. A message's words never become an action: one heard back while Robot
// read it is refused, and once one is in the conversation every action asks (core MessageQuarantine, through
// [taint]). Nothing here writes a message anywhere: History keeps [kept]'s placeholders, the logs codes and counts.
// Nothing here keeps a message's words for long either: when the panel hides, or after 2 minutes without a question,
// they go from this process ([dropText]): the conversation's turns show their placeholders, as History does.

object PanelMessages {
    private val scope = MainScope()
    private val ctx: Context get() = VoiceHub.app

    /** The panel conversation the session below belongs to: a new or loaded one starts it over. */
    private var convId: String? = null
    /** Conversations still to read ("next"), by key, and the one read last ("reply …", "mark it as read"). */
    private val queue = ArrayList<String>()
    private var lastRead: String? = null
    /** That conversation as it was read (RAM): a reply to it after its notification went still gets its card, which then offers the chat. */
    private var lastThread: MessageThread? = null
    /** Robot is reading your messages in this conversation: "next", "stop" and "translate it" count. */
    private var reading = false
    private var taint = MessageTaint.NONE
    /** What Robot read aloud of your messages, and when ([Heard]): a message heard back is the message, never a request. */
    private class Heard(val text: String, val at: Long)
    private val readAloud = ArrayList<Heard>()
    /** When Robot's voice last went quiet (PanelController.onSpeakingChanged). */
    @Volatile private var quietAt = 0L
    /** What History keeps of a reply instead of its words, by reply id. */
    private val kept = HashMap<String, String>()

    /**
     * For the router (not a turn): People and the conversations, read when the panel opens and after each command. The
     * conversations' names and keys only: their messages' words are left out ([withoutText]).
     */
    @Volatile private var people: List<Person> = emptyList()
    @Volatile private var threads: List<MessageThread> = emptyList()

    /** Drops what this process holds of your messages' words after [IDLE_DROP_MS] without a question or a tap. */
    private var idle: Job? = null

    /** [t] with its messages' words left out: who, which app, when, never what. */
    private fun withoutText(t: MessageThread): MessageThread = t.copy(messages = t.messages.map { it.copy(text = "") })

    /** Something happened in the panel: the 2 minutes start again. */
    private fun touch() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_DROP_MS)
            // A summary or a draft still being made: wait for it.
            while (PanelController.state.value.busy) delay(1_000)
            dropText()
        }
    }

    /**
     * Every message's words this process holds go now (the panel hid, or 2 minutes passed without a question): the
     * conversation read last as it was read, what was read aloud (the echo check), the words on a reply card's "wrote
     * again" line, and the conversation's turns, which keep their placeholders as History does. A reply card still waits
     * with your words; who it goes to is the notification's conversation, as always.
     */
    internal fun dropText() {
        idle?.cancel()
        idle = null
        lastThread = null
        threads = threads.map(::withoutText)
        readAloud.clear()
        card?.let { p ->
            p.thread = withoutText(p.thread)
            p.choices = p.choices.map(::withoutText)
            // Only while that line still shows (the card waits on it): any later line ("Sending…", "Sent") holds no message.
            if (p.lineHasMessage && p.state == ReplyCardState.WAITING) {
                p.line = waitingLine(p)
                update(p)
            }
            p.lineHasMessage = false
        }
        PanelController.messagesDropText()
    }

    private var link: MessagesLink? = null
    private fun link(): MessagesLink = link ?: MessagesLink(ctx).also { link = it }

    private val langId: LangId by lazy { ConversationEngine.langId(ctx) }

    /** What a question routes to: a command, or a message's own words heard back. */
    sealed class Routed {
        data class Command(val cmd: MessageCommand) : Routed()
        data class Echo(val lang: Lang) : Routed()
    }

    private fun fr(lang: Lang) = lang == Lang.FR

    private fun sync() {
        val id = PanelController.state.value.convId
        if (id == convId) return
        convId = id
        queue.clear()
        lastRead = null
        lastThread = null
        reading = false
        taint = MessageTaint.NONE
        readAloud.clear()
        kept.clear()
        card = null
        window?.cancel()
    }

    // ------------------------------------------------------------------ the panel's hooks

    /** The panel opened: Settings › Messages again (the main app may have changed it), and People and the conversations for the router. */
    fun onShow() {
        val f = try { MessagesStore.reload(ctx) } catch (_: Throwable) { MessagesFile() }
        if (!f.settings.on) return
        scope.launch { refresh() }
    }

    /** The panel closed: a reply in its 3 seconds is not sent, your messages' words go from this process, and the bridge is let go. */
    fun onHide() {
        stopWindow(CardEnd.CLOSED)
        dropText()
        // Not while a reply is on its way: its answer says whether it went.
        if (link?.busy == true) return
        link?.close()
        link = null
    }

    /** Stop (the button, the word, a tap): a reply in its 3 seconds stays a card, and the reading ends. */
    fun stop() {
        stopWindow(CardEnd.STOPPED)
        queue.clear()
    }

    /** Robot's voice went quiet: what it read ends now, for the echo check. */
    fun quiet() {
        quietAt = System.currentTimeMillis()
    }

    /**
     * [text], other people's words or Robot's from them (a message, its translation, a summary, a draft), is being said
     * aloud: what the microphone hears back of it in the next 15 seconds is the message's, never a request.
     */
    private fun saidAloud(text: String) {
        if (PanelController.state.value.speak && text.isNotBlank()) readAloud += Heard(text, System.currentTimeMillis())
    }

    /** What History keeps of [replyId] instead of its words; null: it holds no message. */
    fun kept(replyId: String): String? = kept[replyId]

    /**
     * The messages in the conversation [chat] (the panel's): what this panel read, or, continued from History, that it
     * held some (their senders aren't known any more, so every send asks with a tap).
     */
    fun taint(chat: List<ChatTurn>): MessageTaint {
        sync()
        val t = if (taint.active) taint else if (chat.any { it.messages }) MessageTaint(setOf("history"), emptySet()) else MessageTaint.NONE
        // 1.0: turns said while the phone was locked are in it (ChatTurn.locked): an action you didn't ask for asks first.
        return if (chat.any { it.locked }) t.copy(saidLocked = true) else t
    }

    /** [q] as a messages command, or a message's words heard back; null: not about messages. */
    fun route(q: String, fromVoice: Boolean): Routed? {
        sync()
        touch()
        val now = System.currentTimeMillis()
        if (fromVoice && readAloud.isNotEmpty()) {
            if (MessageQuarantine.originOf(q, heard(now), now) == ActionOrigin.INCOMING_TEXT) return Routed.Echo(if (SpeechLang.french(q)) Lang.FR else Lang.EN)
        }
        // 0.8: an announcement in the headset or the car waits for its answer ("yes", "tell her I'm late", "send"): after the
        // echo check above, the words are its own (assistant/handsfree HandsFreeMessages).
        if (app.heyrobot.assistant.handsfree.HandsFreeMessages.waiting) return null
        val cmd = MessagesRouter.route(q, reading) ?: return null
        // Messages off: "reply in one word: …" or "clear it" is an ordinary question (only "read my messages" hears it's off).
        // On, a reply is about messages only after one was read here, or to someone a conversation or People knows.
        if (!MessagesRouter.applies(cmd, MessagesStore.current(ctx).settings.on, readHere = lastRead != null || reading,
                knows = { MessagePeople.known(it, threads, people) })) return null
        // "What did Einstein say?" stays a question: only someone in People or in a conversation.
        if (cmd is MessageCommand.FromWho && !MessagePeople.known(cmd.who, threads, people)) return null
        return Routed.Command(cmd)
    }

    /**
     * What was read aloud, each ending when Robot's voice last went quiet after it (now, while it still talks); what
     * ended longer ago than core's echo window is forgotten.
     */
    private fun heard(now: Long): List<ReadAloud> {
        val speaking = VoiceHub.tts.isSpeaking
        val out = readAloud.map { h -> ReadAloud(h.text, if (speaking) now else maxOf(h.at, quietAt)) }
        readAloud.removeAll { h -> !speaking && now - maxOf(h.at, quietAt) > MessageQuarantine.ECHO_MS }
        return out
    }

    /** A turn about your messages ([PanelController.send] starts it in the turn slot). */
    suspend fun run(replyId: String, r: Routed) {
        sync()
        when (r) {
            is Routed.Echo -> {
                VoiceLog.add(VoiceEvent.MSG_GUARD, GuardCode.FROM_MESSAGE)
                say(replyId, if (fr(r.lang)) MessageQuarantine.NOT_FROM_MESSAGES_FR else MessageQuarantine.NOT_FROM_MESSAGES, r.lang)
            }
            is Routed.Command -> try {
                command(replyId, r.cmd)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                RLog.w("messages_turn")
                say(replyId, if (fr(r.cmd.lang)) "Robot n'a pas pu lire tes messages. Réessaie." else "Robot couldn't get to your messages. Please try again.", r.cmd.lang)
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private suspend fun refresh(): Pair<MessagesStatus, List<MessageThread>>? {
        val p = withContext(Dispatchers.IO) { try { PeopleFiles.store(ctx).list() } catch (_: Throwable) { emptyList() } }
        people = p
        val r = link().list() ?: return null
        val all = r.second.map { MessagePeople.named(it, p) }
        threads = all.map(::withoutText)
        touch()
        return r.first to all
    }

    private fun refused(why: MsgRefusal) = VoiceLog.add(VoiceEvent.MSG_REFUSED, why)

    private suspend fun command(replyId: String, cmd: MessageCommand) {
        val f = MessagesStore.current(ctx)
        val s = f.settings
        val lang = cmd.lang
        if (!s.on) {
            refused(MsgRefusal.OFF)
            return say(replyId, if (fr(lang)) MessageAccess.OFF_FR else MessageAccess.OFF, lang)
        }
        if (s.allowlist.apps.isEmpty()) {
            refused(MsgRefusal.NO_APPS)
            return say(replyId, MessageReader.noApps(lang), lang)
        }
        when (cmd) {
            is MessageCommand.Stop -> {
                queue.clear()
                reading = false
                return say(replyId, MessageReader.stopped(lang), lang)
            }
            else -> {}
        }
        val locked = Assistant.locked(ctx)
        val access = MessageAccess.whenLocked(locked, s)
        // Locked with "Who messaged", only "read my messages" says who; the rest needs the phone unlocked.
        if (access == LockedMessages.UNLOCK_FIRST || (access == LockedMessages.NAMES_ONLY && cmd !is MessageCommand.Read && cmd !is MessageCommand.Reply)) {
            refused(if (access == LockedMessages.NAMES_ONLY) MsgRefusal.NAMES_ONLY else MsgRefusal.UNLOCK_FIRST)
            return say(replyId, if (fr(lang)) MessageAccess.UNLOCK_FIRST_FR else MessageAccess.UNLOCK_FIRST, lang)
        }
        if (cmd is MessageCommand.Mark) return mark(replyId, cmd)
        val (status, all) = refresh() ?: return say(replyId,
            if (fr(lang)) "Robot n'a pas pu joindre ses messages. Réessaie." else "Robot couldn't reach its messages. Please try again.", lang)
        if (!status.access) {
            refused(MsgRefusal.NO_ACCESS)
            return say(replyId, MessageReader.noAccess(lang), lang)
        }
        when (cmd) {
            is MessageCommand.Read -> read(replyId, all, lang, access == LockedMessages.NAMES_ONLY, locked, s.translate)
            is MessageCommand.FromWho -> {
                val found = MessagePeople.find(cmd.who, all, people)
                if (found.isEmpty()) return say(replyId, MessageReader.noneFrom(cmd.who, lang), lang)
                queue.clear()
                queue.addAll(found.drop(1).map { it.key })
                show(replyId, MessageReader.thread(found.first(), lang, more = found.size > 1, translations = others(found.first(), lang, s.translate),
                    french = MessageLanguage.frenchLine(langId)), locked)
            }
            is MessageCommand.Next -> {
                var t: MessageThread? = null
                while (t == null && queue.isNotEmpty()) { val k = queue.removeAt(0); t = all.firstOrNull { it.key == k } }
                if (t == null) {
                    reading = false
                    return say(replyId, MessageReader.done(lang), lang)
                }
                show(replyId, MessageReader.thread(t, lang, more = queue.isNotEmpty(), translations = others(t, lang, s.translate),
                    french = MessageLanguage.frenchLine(langId)), locked)
            }
            is MessageCommand.Summarize -> summarize(replyId, all, lang)
            is MessageCommand.Translate -> translate(replyId, cmd, all, lang)
            is MessageCommand.Reply -> reply(replyId, cmd, all, f, locked)
            else -> {}
        }
    }

    private fun unread(t: MessageThread): Boolean = t.unread.isNotEmpty() || (t.hidden != null && t.incoming.any { !it.readByRobot })

    private suspend fun read(replyId: String, all: List<MessageThread>, lang: Lang, namesOnly: Boolean, locked: Boolean, translate: Boolean) {
        val unread = all.filter(::unread)
        if (unread.isEmpty()) {
            reading = false
            return say(replyId, MessageReader.nothing(lang), lang)
        }
        queue.clear()
        if (!namesOnly) queue.addAll(unread.drop(1).map { it.key })
        val hint = if (!namesOnly && MessageSummaries.shouldSummarize(unread)) (if (fr(lang)) "Dis « résume » pour un résumé." else "Say “summarize” for a summary.") else null
        val tr = if (namesOnly) emptyMap() else others(unread.first(), lang, translate)
        val page = MessageReader.start(unread, lang, namesOnly, tr, hint, MessageLanguage.frenchLine(langId))
        if (namesOnly) VoiceLog.add(VoiceEvent.MSG_READ, MsgAppCode.idOf(unread.map { it.app }), 0, 1)
        show(replyId, page, locked)
    }

    /** Shows and says [page]; a conversation read is marked read by Robot (RAM), handled as "After Robot reads" says, and taints the conversation. */
    private suspend fun show(replyId: String, page: ReadPage, locked: Boolean) {
        kept[replyId] = page.kept
        PanelController.messagesReply(replyId, page.shown)
        speak(page.spoken)
        val t = page.thread ?: return
        lastRead = t.key
        lastThread = t
        reading = true
        taint = taint.with(t)
        // Said aloud, the microphone may hear it back: that is the message, never a request (translated, too).
        saidAloud(page.heard.joinToString(" "))
        VoiceLog.add(VoiceEvent.MSG_READ, MsgAppCode.of(t.app).id, page.ids.size, if (locked) 1 else 0)
        link().markReadByRobot(page.ids)
        when (MessagesStore.current(ctx).settings.afterReading) {
            AfterReading.LEAVE -> {}
            AfterReading.CLEAR -> link().clear(t.key)
            AfterReading.MARK_READ -> link().markRead(t.key)
        }
        if (PanelController.state.value.speak) PanelController.messagesListen(now = false)
    }

    /** "Mark it as read", "clear it": the conversation read last, once. */
    private suspend fun mark(replyId: String, cmd: MessageCommand.Mark) {
        val lang = cmd.lang
        val key = lastRead ?: return say(replyId, MessageReader.noneRead(lang), lang)
        val name = threads.firstOrNull { it.key == key }?.name ?: if (fr(lang)) "cette conversation" else "that conversation"
        val read = cmd.kind == app.heyrobot.core.messages.MarkKind.READ
        val ok = if (read) link().markRead(key) else link().clear(key)
        say(replyId, when {
            !ok && fr(lang) -> "La notification de $name n'est plus là."
            !ok -> "$name's notification isn't there any more."
            read && fr(lang) -> "Marqué comme lu : $name peut voir que tu l'as lu."
            read -> "Marked as read: $name may see that you read it."
            fr(lang) -> "Notification de $name effacée; la conversation reste non lue dans l'application."
            else -> "Cleared $name's notification; the chat stays unread in the app."
        }, lang)
    }

    // ------------------------------------------------------------------ summaries and translations

    private suspend fun oneShot(kind: OneShotKind, priority: Priority, system: String, text: String, maxChunks: Int): String? {
        val r = try {
            BrainOneShots.run(BrainMsg.OneShot(kind, priority, system, text, maxChunks), OneShotBudget(null, ONE_SHOT_MS)) {}
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        return (r as? OneShotResult.Done)?.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun summarize(replyId: String, all: List<MessageThread>, lang: Lang) {
        val ts = all.filter(::unread).ifEmpty { all.filter { it.incoming.isNotEmpty() } }
        if (ts.isEmpty()) return say(replyId, MessageReader.nothing(lang), lang)
        val zone = ZoneId.systemDefault()
        val reqs = MessageSummaries.requests(ts, lang, zone)
        if (reqs.isEmpty()) return say(replyId, MessageReader.nothing(lang), lang)
        val t0 = System.currentTimeMillis()
        val parts = ArrayList<String>()
        for (r in reqs) {
            parts += oneShot(OneShotKind.SUMMARY_MAP, Priority.SUMMARY, r.system, r.user, SUMMARY_CHUNKS) ?: return say(replyId,
                if (fr(lang)) "Le modèle de Robot n'a pas pu les résumer pour l'instant. Dis « lis mes messages » pour les entendre."
                else "Robot's model couldn't summarize them right now. Say “read my messages” to hear them.", lang)
        }
        val summary = if (parts.size == 1) parts[0] else {
            val m = MessageSummaries.merge(parts, lang)
            oneShot(OneShotKind.SUMMARY_REDUCE, Priority.SUMMARY, m.system, m.user, SUMMARY_CHUNKS) ?: parts.joinToString("\n")
        }
        // Other people's words went into it: the conversation holds messages from here on.
        for (t in ts) taint = taint.with(t)
        reading = true
        val misses = MessageSummaryText.misses(summary, ts, zone)
        kept[replyId] = MessageSummaryText.kept(ts.size, lang)
        PanelController.messagesReply(replyId, MessageSummaryText.shown(summary, misses, lang))
        val french = MessageLanguage.frenchLine(langId)
        speak(listOf(Spoken(MessageSummaries.label(lang), fr(lang))) + summary.lines().map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.isNotEmpty() }
            .map { Spoken(it, french(it)) })
        saidAloud(summary)
        VoiceLog.add(VoiceEvent.MSG_SUMMARY, ts.size, ts.sumOf { MessageReading.toRead(it).size }, ((System.currentTimeMillis() - t0) / 1000).toInt())
    }

    /** "Translate Pierre's message": each message not in the language you asked in, into it. */
    private suspend fun translate(replyId: String, cmd: MessageCommand.Translate, all: List<MessageThread>, lang: Lang) {
        val t = cmd.who?.let { MessagePeople.find(it, all, people).firstOrNull() } ?: lastRead?.let { k -> all.firstOrNull { it.key == k } }
            ?: return say(replyId, cmd.who?.let { MessageReader.noneFrom(it, lang) } ?: MessageReader.noneRead(lang), lang)
        val to = MessageLanguage.code(lang)
        val tr = HashMap<String, MessageTranslation>()
        for (m in MessageReading.toRead(t).takeLast(MessageReading.MAX_PER_THREAD)) {
            if (m.text.isBlank()) continue
            val from = MessageLanguage.of(m.text, langId)
            if (from == to) continue
            translateOne(m.text, from, lang)?.let { tr[m.id] = it }
        }
        if (tr.isEmpty()) {
            val already = if (fr(lang)) "Le message de ${t.name} est déjà en français, ou Robot n'a pas pu le traduire." else
                "${t.name}'s message is already in English, or Robot couldn't translate it."
            return show(replyId, MessageReader.thread(t, lang, more = queue.isNotEmpty(), french = MessageLanguage.frenchLine(langId)).let {
                ReadPage(already + "\n\n" + it.shown, listOf(Spoken(already, fr(lang))) + it.spoken, it.ids, it.thread, it.kept, it.more, it.heard)
            }, Assistant.locked(ctx))
        }
        show(replyId, MessageReader.thread(t, lang, more = queue.isNotEmpty(), translations = tr, french = MessageLanguage.frenchLine(langId)), Assistant.locked(ctx))
    }

    /** While reading, with "Translate messages not in my languages" on: the messages in another language, translated. */
    private suspend fun others(t: MessageThread, lang: Lang, on: Boolean): Map<String, MessageTranslation> {
        if (!on) return emptyMap()
        val out = HashMap<String, MessageTranslation>()
        for (m in MessageReading.toRead(t).takeLast(MessageReading.MAX_PER_THREAD)) {
            if (m.text.isBlank() || MessageLanguage.of(m.text, langId) != MessageLanguage.OTHER) continue
            translateOne(m.text, MessageLanguage.OTHER, lang)?.let { out[m.id] = it }
        }
        return out
    }

    /**
     * [text] from [from] ("fr", "en", another, or not sure) into [to]: between French and English the engines as
     * TranslatePolicy orders them (Robot's model, the fast engine standing in), checked by MtGuard; from another language
     * Robot's model alone, asked to name it. Null when nothing usable came back.
     */
    private suspend fun translateOne(text: String, from: String?, to: Lang): MessageTranslation? {
        val target = MessageLanguage.code(to)
        val t0 = System.currentTimeMillis()
        if (from != null && from in MessageLanguage.YOURS && from != target) {
            val r = FastTranslation.borrow(ctx, LangPair.FR_EN) { fast ->
                val backend = BrainKeeper.state.value.backend ?: VoiceHub.settings.brainModelChoice.backend
                val model = GemmaTranslator(BrainOneShots, Priority.INTERPRETER, TranslatePolicy.budget(TranslateUse.INTERPRETER, backend, fast != null))
                val (lead, other) = TranslatePolicy.order(backend, model, fast)
                LineTranslator(langId, System::currentTimeMillis).firstThen(lead, other, text, from, target)
            }
            VoiceLog.add(VoiceEvent.MSG_TRANSLATE, LangCode.of(target).id, r.engine?.id ?: 0, r.ms.toInt())
            return r.text?.let { MessageTranslation(it, langName(from, to)) }
        }
        val out = oneShot(OneShotKind.TRANSLATE, Priority.INTERPRETER, MessagePrompts.translateAny(to), text, GemmaTranslator.maxChunks(text) + 8)
        val tr = out?.let(MessagePrompts::parseAny)
        VoiceLog.add(VoiceEvent.MSG_TRANSLATE, LangCode.of(target).id, if (tr != null) app.heyrobot.core.MtEngine.GEMMA.id else 0,
            (System.currentTimeMillis() - t0).toInt())
        return tr
    }

    private fun langName(code: String, inLang: Lang): String? = when (code) {
        "fr" -> if (fr(inLang)) "français" else "French"
        "en" -> if (fr(inLang)) "anglais" else "English"
        else -> null
    }

    private fun langOfCode(code: String?): Lang? = when (code) {
        "fr" -> Lang.FR
        "en" -> Lang.EN
        else -> null
    }

    // ------------------------------------------------------------------ replies

    /** The reply card waiting (one at a time), and its 3 seconds. */
    private var card: Pending? = null
    private var window: Job? = null

    /**
     * A reply on its card. [text]: your words (or Robot's draft, [source]); [translation]: them in [toLang], sent when
     * [translate] is ticked; [plan]: core ReplyPlanner's, made again whenever any of these change.
     */
    private class Pending(
        val replyId: String,
        val lang: Lang,
        var thread: MessageThread,
        var text: String,
        var source: ReplySource,
        var inLang: Lang? = null,
        var choices: List<MessageThread> = emptyList(),
    ) {
        var person: Person? = null
        var translation: String? = null
        var toLang: Lang? = null
        var translate = false
        var always = false
        var plan: ReplyPlan.Send? = null
        /** Robot took the newest conversation for a "reply …" that named none, with none read here: never sent without a tap or a yes. */
        var guessed = false
        /** [line] quotes a message ("Mom wrote again: «…»"): it goes with the rest of the message's words ([dropText]). */
        var lineHasMessage = false
        var state = ReplyCardState.WAITING
        var line: String? = null

        val sendText: String get() = if (translate && translation != null) translation!! else text
        val sendSource: ReplySource get() = if (translate && translation != null) ReplySource.TRANSLATION else source
    }

    private suspend fun reply(replyId: String, cmd: MessageCommand.Reply, all: List<MessageThread>, f: MessagesFile, locked: Boolean) {
        val lang = cmd.lang
        var to = cmd.to
        var words = cmd.words
        if (cmd.split && to != null) {
            val (n, w) = MessagesRouter.split(to, MessagePeople.names(all, people))
            to = n
            words = w.ifBlank { null }
        }
        val found = when {
            to != null -> MessagePeople.find(to, all, people)
            // The conversation read last, never another one in its place: gone from the shade meanwhile, its card finds out on
            // Send and offers the chat instead.
            lastRead != null -> all.filter { it.key == lastRead }.ifEmpty { listOfNotNull(lastThread?.takeIf { it.key == lastRead }) }
            else -> listOfNotNull(all.firstOrNull { it.incoming.any { m -> !m.historic } })
        }
        if (found.isEmpty()) {
            return say(replyId, when {
                to != null && fr(lang) -> "Aucun message de $to en ce moment. Pour lui écrire, dis « texte à $to … »."
                to != null -> "No message from $to right now. To write to them, say “text $to …”."
                else -> MessageReader.nothing(lang)
            }, lang)
        }
        card?.let { old -> if (old.state == ReplyCardState.WAITING || old.state == ReplyCardState.PICK) cancel(old, quiet = true) }
        val p = Pending(replyId, lang, found.first(), words?.let(ReplyText::of).orEmpty(), if (words == null) ReplySource.MODEL_DRAFT else ReplySource.DICTATED,
            cmd.inLang, if (found.size > 1) found else emptyList())
        p.guessed = to == null && lastRead == null
        card = p
        kept[replyId] = MessageReading.replyKept(ReplyKept.CARD, p.thread.name, p.thread.app, lang)
        if (p.choices.isNotEmpty()) {
            p.state = ReplyCardState.PICK
            p.line = if (fr(lang)) "Plusieurs conversations correspondent. Touche celle que tu veux." else "Several conversations fit. Tap the one you mean."
            PanelController.messagesReply(replyId, if (fr(lang)) "Répondre à qui ?" else "Reply to whom?")
            PanelController.messagesCard(replyId, view(p))
            speak(listOf(Spoken(p.line!!, fr(lang))))
            return
        }
        prepare(p, f, locked, first = true)
    }

    /** Words (or a draft), the translation, the plan, then the card: shown, said, and listened for or timed as the plan says. */
    private suspend fun prepare(p: Pending, f: MessagesFile, locked: Boolean, first: Boolean) {
        val lang = p.lang
        val t = p.thread
        p.person = MessagePeople.personOf(t, people)
        val theirs = langOfCode(MessageLanguage.ofThread(t, langId))
        if (p.text.isEmpty()) {
            // "Answer her for me": a draft by Robot's model, from the chat's last messages only (no memory, no tools); a tap sends it.
            taint = taint.with(t)
            val draft = oneShot(OneShotKind.SUMMARY_MAP, Priority.INTERPRETER, MessagePrompts.draft(theirs ?: lang), MessagePrompts.draftInput(t, ZoneId.systemDefault()), 160)
                ?.let(MtGuard::clean)?.take(ReplyPlanner.MAX_CHARS)
            if (draft.isNullOrBlank()) {
                card = null
                return say(p.replyId, if (fr(lang)) "Robot n'a pas pu écrire de réponse. Dis ce qu'elle doit dire : « réponds … »." else
                    "Robot couldn't draft a reply. Say what it should say: “reply …”.", lang)
            }
            p.text = draft
            p.source = ReplySource.MODEL_DRAFT
        }
        // "Reply in French: …", or her messages are in the other of your languages: the card offers them translated.
        val words = langOfCode(MessageLanguage.of(p.text, langId)) ?: lang
        val target = p.inLang ?: theirs?.takeIf { f.settings.offerTheirLanguage && it != words && p.source == ReplySource.DICTATED }
        if (target != null && target != words) {
            p.toLang = target
            p.translation = translateOne(p.text, MessageLanguage.code(words), target)?.text
            val remembered = p.person?.id?.let { f.replyLang[it] }
            p.translate = p.translation != null && (p.inLang != null || remembered == target)
            p.always = remembered == target
        }
        if (!plan(p, f, locked)) return
        val plan = p.plan!!
        // 0.8, in the car: a card only a tap sends waits for parking.
        if (plan.confirm == app.heyrobot.core.messages.Confirm.TAP && parkInCar(p)) return
        if (first) PanelController.messagesReply(p.replyId, headline(p, plain = true) + ".")
        lastRead = t.key
        lastThread = t
        when (plan.confirm) {
            app.heyrobot.core.messages.Confirm.STOP_WINDOW -> startWindow(p)
            else -> {
                p.state = ReplyCardState.WAITING
                p.line = waitingLine(p)
                update(p)
                speakCard(p)
                if (plan.voiceYesCounts && PanelController.state.value.speak && VoiceHub.settings.voiceCards) PanelController.messagesListen(now = false)
            }
        }
    }

    /** core ReplyPlanner's plan for the card as it is; false (and the reason said) when it can't be sent this way. */
    private suspend fun plan(p: Pending, f: MessagesFile = MessagesStore.current(ctx), locked: Boolean = Assistant.locked(ctx)): Boolean {
        val lang = p.lang
        // Only to the person's own address: a name the sender chose never sends without a card (core MessagePeople).
        val noCard = MessagePeople.withoutCard(p.thread, people, f.noCard)
        when (val r = ReplyPlanner.plan(p.thread, p.sendText, p.sendSource, f.settings, locked, noCard, sure = !p.guessed)) {
            is ReplyPlan.Send -> { p.plan = r; return true }
            is ReplyPlan.HandOff -> {
                // "Open the chat; I press Send", or no reply action: the chat opens with your words. Nothing is sent by Robot.
                p.plan = null
                // 0.8, in the car: nothing opens while you drive; it waits for parking.
                if (parkInCar(p)) return false
                if (p.state != ReplyCardState.OPENED) PanelController.messagesReply(p.replyId, headline(p, plain = true) + ".")
                openChat(p)
                return false
            }
            is ReplyPlan.Refused -> {
                card = null
                say(p.replyId, when (r.why) {
                    RefuseWhy.OFF -> if (fr(lang)) MessageAccess.OFF_FR else MessageAccess.OFF
                    RefuseWhy.EMPTY -> if (fr(lang)) "Que doit dire la réponse ?" else "What should the reply say?"
                    RefuseWhy.TOO_LONG -> if (fr(lang)) "Cette réponse est trop longue (2 000 caractères au plus)." else "That reply is too long (2,000 characters at most)."
                    RefuseWhy.LOCKED -> if (fr(lang)) MessageAccess.UNLOCK_FIRST_FR else MessageAccess.UNLOCK_FIRST
                    RefuseWhy.AUTH_REQUIRED -> if (fr(lang)) "Déverrouille d'abord : cette application demande le téléphone déverrouillé pour répondre."
                        else "Unlock first: this app needs the phone unlocked to reply."
                }, lang)
                return false
            }
        }
    }

    private fun headline(p: Pending, plain: Boolean = false): String {
        val n = if (plain) p.thread.name else "**${p.thread.name}**"
        val app = p.thread.app.title
        return when {
            fr(p.lang) && p.thread.isGroup -> "Répondre au groupe $n · $app"
            fr(p.lang) -> "Répondre à $n · $app"
            p.thread.isGroup -> "Reply to the group $n · $app"
            else -> "Reply to $n · $app"
        }
    }

    private fun waitingLine(p: Pending): String {
        val yes = p.plan?.voiceYesCounts == true && VoiceHub.settings.voiceCards
        return when {
            p.source == ReplySource.MODEL_DRAFT && fr(p.lang) -> "Robot a écrit ce brouillon. Vérifie-le, puis touche Envoyer."
            p.source == ReplySource.MODEL_DRAFT -> "Robot drafted this. Check it, then tap Send."
            yes && fr(p.lang) -> "Touche Envoyer, ou dis oui."
            yes -> "Tap Send, or say yes."
            fr(p.lang) -> "Touche Envoyer pour l'envoyer."
            else -> "Tap Send to send it."
        }
    }

    private fun view(p: Pending): ReplyCardView {
        val lang = p.lang
        val toLangName = p.toLang?.let { if (it == Lang.FR) (if (fr(lang)) "français" else "French") else (if (fr(lang)) "anglais" else "English") }
        return ReplyCardView(
            headline = if (p.state == ReplyCardState.PICK) (if (fr(lang)) "Répondre à qui ?" else "Reply to whom?") else headline(p),
            state = p.state,
            line = p.line,
            text = p.text,
            translation = p.translation,
            translateLabel = toLangName?.let { if (fr(lang)) "Envoyer en $it" else "Send in $it" },
            translate = p.translate,
            alwaysLabel = p.person?.let { if (fr(lang)) "Toujours pour ${MessagePeople.callName(it)}" else "Always for ${MessagePeople.callName(it)}" },
            always = p.always,
            notes = p.plan?.notes.orEmpty().mapNotNull { ReplyPlanner.note(it, lang) },
            choices = p.choices.map { "${it.name} · ${it.app.title}" },
            openChat = chatIntent(p.thread, p.sendText) != null,
        )
    }

    private fun update(p: Pending) = PanelController.messagesCard(p.replyId, view(p))

    /** The card read aloud: who, your words in their own language's voice, and how to send it. */
    private fun speakCard(p: Pending) {
        val lang = p.lang
        val yes = p.plan?.voiceYesCounts == true && VoiceHub.settings.voiceCards
        val head = if (fr(lang)) "Répondre à ${p.thread.name} :" else "Reply to ${p.thread.name}:"
        val tail = when {
            yes && fr(lang) -> "Je l'envoie ?"
            yes -> "Send it?"
            fr(lang) -> "Touche Envoyer pour l'envoyer."
            else -> "Tap Send to send it."
        }
        speak(listOf(Spoken(head, fr(lang)), Spoken(p.sendText, MessageLanguage.frenchLine(langId)(p.sendText)), Spoken(tail, fr(lang))))
        // A draft is Robot's model's words from the chat's messages: heard back, it is theirs too.
        if (p.sendSource == ReplySource.MODEL_DRAFT) saidAloud(p.sendText)
    }

    /** "Send my replies to Mom without a card": said, then 3 seconds to stop it (a word, the Stop button, closing the panel). */
    private fun startWindow(p: Pending) {
        val lang = p.lang
        p.state = ReplyCardState.STOP_WINDOW
        p.line = if (fr(lang)) "Envoi dans 3 secondes. Dis « arrête » ou touche Arrêter." else "Sending in 3 seconds. Say stop or tap Stop."
        update(p)
        speak(listOf(Spoken(if (fr(lang)) "J'envoie à ${p.thread.name} :" else "Sending to ${p.thread.name}:", fr(lang)),
            Spoken(p.sendText, MessageLanguage.frenchLine(langId)(p.sendText))))
        window?.cancel()
        window = scope.launch {
            val t0 = System.currentTimeMillis()
            while (VoiceHub.tts.isSpeaking && System.currentTimeMillis() - t0 < SPEECH_WAIT_MS) delay(100)
            if (PanelController.state.value.speak) PanelController.messagesListen(now = true)
            delay(ReplyPlanner.STOP_WINDOW_MS)
            // You began saying something ("stop"): wait for its words.
            var waited = 0
            while (waited < 50 && PanelController.state.value.let { it.phase == Phase.LISTENING || it.phase == Phase.TRANSCRIBING } && PanelController.state.value.partial.isNotBlank()) {
                delay(100)
                waited++
            }
            if (card === p && p.state == ReplyCardState.STOP_WINDOW) fire(p, CardEnd.NO_CARD)
        }
    }

    /** A reply in its 3 seconds becomes a card that waits for Send ([why]: the stop, or the panel closing). */
    private fun stopWindow(why: CardEnd) {
        val p = card ?: return
        if (p.state != ReplyCardState.STOP_WINDOW) return
        window?.cancel()
        window = null
        p.state = ReplyCardState.WAITING
        p.line = if (fr(p.lang)) "Arrêté. Rien n'est envoyé ; touche Envoyer pour l'envoyer quand même." else "Stopped. Nothing was sent; tap Send to send it anyway."
        update(p)
        VoiceLog.add(VoiceEvent.MSG_REPLY_CARD, why, if (p.translate) 1 else 0, if (p.thread.isGroup) 1 else 0)
    }

    private fun cancel(p: Pending, quiet: Boolean = false) {
        window?.cancel()
        p.state = ReplyCardState.CANCELLED
        p.line = if (fr(p.lang)) "Annulé. Rien n'a été envoyé." else "Cancelled. Nothing was sent."
        update(p)
        if (card === p) card = null
        VoiceLog.add(VoiceEvent.MSG_REPLY_CARD, CardEnd.CANCELLED, if (p.translate) 1 else 0, if (p.thread.isGroup) 1 else 0)
        if (!quiet) PanelController.messagesKept(p.replyId, MessageReading.replyKept(ReplyKept.CANCELLED, p.thread.name, p.thread.app, p.lang))
    }

    /** Sends the card's reply through the main process, which reads the notification again first (core ReplySend). */
    private suspend fun fire(p: Pending, how: CardEnd) {
        val plan = p.plan ?: return
        // Once per card: a tap and a spoken yes (or two taps) both launched before it left send it once (this runs on the
        // main thread up to the bridge, so the first one here marks it SENDING before any other can look).
        if (p.state != ReplyCardState.WAITING && p.state != ReplyCardState.STOP_WINDOW) return
        val lang = p.lang
        val name = p.thread.name
        p.state = ReplyCardState.SENDING
        p.line = if (fr(lang)) "Envoi…" else "Sending…"
        update(p)
        val a = link().reply(ReplyRequest(p.thread.key, plan.text, plan.newestSeen)) ?: run {
            // No answer from the main process: it may have gone. Never sent again by itself.
            p.state = ReplyCardState.FAILED
            p.line = if (fr(lang)) "Robot ne sait pas si c'est parti. Regarde la conversation avant de renvoyer." else
                "Robot can't tell whether it went. Check the chat before sending it again."
            update(p)
            return
        }
        when (a.outcome) {
            ReplyOutcome.SENT -> {
                p.state = ReplyCardState.SENT
                p.line = if (fr(lang)) "Envoyé dans ${p.thread.app.title}." else "Sent in ${p.thread.app.title}."
                update(p)
                speak(listOf(Spoken(if (fr(lang)) "Envoyé." else "Sent.", fr(lang))))
                VoiceLog.add(VoiceEvent.MSG_REPLY_CARD, how, if (p.translate) 1 else 0, if (p.thread.isGroup) 1 else 0)
                PanelController.messagesKept(p.replyId, MessageReading.replyKept(ReplyKept.SENT, name, p.thread.app, lang))
                if (card === p) card = null
            }
            ReplyOutcome.NEW_MESSAGES -> {
                val fresh = a.thread?.let { MessagePeople.named(it, people) } ?: p.thread
                val seen = plan.newestSeen ?: Long.MIN_VALUE
                val newest = fresh.incoming.filter { !it.historic && it.time > seen }.lastOrNull()
                p.thread = fresh
                taint = taint.with(fresh)
                if (!plan(p)) return
                p.state = ReplyCardState.WAITING
                val said = newest?.let { MessageReading.line(it, fresh, lang) } ?: ""
                p.line = if (fr(lang)) "$name a écrit de nouveau : « $said ». L'envoyer quand même ?" else "$name wrote again: «$said». Still send?"
                p.lineHasMessage = true
                update(p)
                speak(listOf(Spoken(if (fr(lang)) "$name a écrit de nouveau :" else "$name wrote again:", fr(lang)),
                    Spoken(said, MessageLanguage.frenchLine(langId)(said)), Spoken(if (fr(lang)) "Touche Envoyer pour l'envoyer quand même." else "Tap Send to send it anyway.", fr(lang))))
                saidAloud(said)
            }
            ReplyOutcome.GONE, ReplyOutcome.CANCELED_PI, ReplyOutcome.CHANGED -> {
                if (a.outcome == ReplyOutcome.CHANGED) VoiceLog.add(VoiceEvent.MSG_GUARD, GuardCode.RECIPIENT_MISMATCH)
                p.state = ReplyCardState.GONE
                p.line = if (fr(lang)) "La notification de $name n'est plus là. Ouvrir la conversation avec ta réponse ?" else
                    "$name's notification is gone. Open the chat with your reply?"
                update(p)
                speak(listOf(Spoken(p.line!!, fr(lang))))
            }
            ReplyOutcome.LOCKED, ReplyOutcome.AUTH_REQUIRED -> {
                p.state = ReplyCardState.WAITING
                p.line = if (fr(lang)) "Déverrouille le téléphone, puis touche Envoyer." else "Unlock your phone, then tap Send."
                update(p)
            }
            ReplyOutcome.OPEN_CHAT -> openChat(p)
            ReplyOutcome.OFF -> {
                p.state = ReplyCardState.CANCELLED
                p.line = if (fr(lang)) MessageAccess.OFF_FR else MessageAccess.OFF
                update(p)
            }
            ReplyOutcome.FAILED -> {
                p.state = ReplyCardState.FAILED
                p.line = if (fr(lang)) "Pas envoyé. Ouvrir la conversation avec ta réponse ?" else "Didn't send. Open the chat with your reply?"
                update(p)
            }
        }
    }

    /** The app's chat with your words as a draft: Cheogram's own chat by its shortcut; another app gets them shared. */
    private fun chatIntent(t: MessageThread, text: String): Intent? {
        if (t.app == MessageApp.CHEOGRAM) {
            val pkg = HandOffApps.cheogram(ctx) ?: return null
            return ChatLinks.cheogram(t.conversationId)?.let { (account, jid) -> CheogramIntents.chat(pkg, jid, account, text) } ?: CheogramIntents.share(pkg, text)
        }
        val pkg = t.app.packages.firstOrNull { HandOffApps.installed(ctx, it) } ?: return null
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** "Open chat" (or "How replies go: Open the chat; I press Send"): nothing is sent until you press Send there. */
    private fun openChat(p: Pending) {
        val lang = p.lang
        window?.cancel()
        if (Assistant.locked(ctx)) {
            p.line = if (fr(lang)) "Déverrouille le téléphone, puis touche Ouvrir la conversation." else "Unlock your phone, then tap Open chat."
            if (p.state != ReplyCardState.GONE && p.state != ReplyCardState.FAILED) p.state = ReplyCardState.WAITING
            return update(p)
        }
        val i = chatIntent(p.thread, p.sendText)
        if (i == null || !PanelController.messagesLaunch(i)) {
            p.state = ReplyCardState.FAILED
            p.line = if (fr(lang)) "La conversation ne s'est pas ouverte dans ${p.thread.app.title}." else "The chat didn't open in ${p.thread.app.title}."
            return update(p)
        }
        p.state = ReplyCardState.OPENED
        p.line = if (fr(lang)) "Brouillon ouvert : rien n'est envoyé tant que tu n'appuies pas sur Envoyer là-bas." else
            "Opened a draft: nothing is sent until you press Send there."
        update(p)
        VoiceLog.add(VoiceEvent.MSG_REPLY, MsgAppCode.of(p.thread.app).id, ReplyRouteCode.HAND_OFF.id, app.heyrobot.core.messages.ReplyEnd.OPENED.id)
        VoiceLog.add(VoiceEvent.MSG_REPLY_CARD, CardEnd.OPEN_CHAT, if (p.translate) 1 else 0, if (p.thread.isGroup) 1 else 0)
        PanelController.messagesKept(p.replyId, MessageReading.replyKept(ReplyKept.OPENED, p.thread.name, p.thread.app, lang))
        if (card === p) card = null
    }

    /** A tap on the card under [replyId]. Only the card waiting answers; an old one does nothing. */
    fun onCard(replyId: String, op: ReplyOp) {
        val p = card?.takeIf { it.replyId == replyId } ?: return
        touch()
        when (op) {
            ReplyOp.Send -> if (p.state == ReplyCardState.WAITING) scope.launch { fire(p, CardEnd.TAP) }
            ReplyOp.Cancel -> cancel(p)
            ReplyOp.OpenChat -> openChat(p)
            ReplyOp.Stop -> stopWindow(CardEnd.STOPPED)
            is ReplyOp.Edit -> if (p.state == ReplyCardState.WAITING) scope.launch {
                p.text = op.text.trim()
                // Typed by you: your words. A translation shown was of the old words: offered again.
                p.source = ReplySource.DICTATED
                p.translation = null
                p.translate = false
                p.toLang = null
                prepare(p, MessagesStore.current(ctx), Assistant.locked(ctx), first = false)
            }
            is ReplyOp.Translate -> if (p.state == ReplyCardState.WAITING) scope.launch {
                p.translate = op.on && p.translation != null
                if (plan(p)) { p.line = waitingLine(p); update(p) }
            }
            is ReplyOp.Always -> {
                p.always = op.on
                update(p)
                val id = p.person?.id ?: return
                scope.launch { link().replyLang(id, if (op.on) p.toLang else null) }
            }
            is ReplyOp.Pick -> if (p.state == ReplyCardState.PICK) {
                val t = p.choices.getOrNull(op.index) ?: return
                p.thread = t
                p.choices = emptyList()
                p.state = ReplyCardState.WAITING
                scope.launch { prepare(p, MessagesStore.current(ctx), Assistant.locked(ctx), first = false) }
            }
        }
    }

    /**
     * A spoken or typed answer while a reply card waits: "no" cancels it; "yes" sends it only where the card allows a
     * spoken yes ("Answer reply cards by voice", 1:1, your own words), otherwise Robot says to tap Send; in its 3 seconds,
     * anything but a yes stops it. False: not an answer (the question goes on as usual).
     */
    fun answerCard(q: String, fromVoice: Boolean): Boolean {
        sync()
        val p = card ?: return false
        val wakes = VoiceHub.settings.wakePhrases.filter { it.enabled }.map { it.text }
        if (p.state == ReplyCardState.STOP_WINDOW) {
            // Anything said in its 3 seconds but a yes stops it (core ReplyWindow): "no, no, don't send it" as well as "stop".
            return when (app.heyrobot.core.messages.ReplyWindow.answer(q, wakes)) {
                app.heyrobot.core.messages.WindowAnswer.YES -> true
                app.heyrobot.core.messages.WindowAnswer.STOP -> { stopWindow(CardEnd.STOPPED); true }
                app.heyrobot.core.messages.WindowAnswer.OTHER -> { stopWindow(CardEnd.STOPPED); false }
            }
        }
        if (p.state != ReplyCardState.WAITING || p.plan == null) return false
        val kind = CardAnswer.of(q, wakes) ?: return false
        if (kind == CardAnswer.Kind.NO) {
            cancel(p)
            return true
        }
        val yesCounts = p.plan!!.voiceYesCounts && (!fromVoice || VoiceHub.settings.voiceCards)
        if (!yesCounts) {
            val words = if (fr(p.lang)) "Touche Envoyer sur la carte pour l'envoyer." else "Tap Send on the card to send it."
            PanelController.notify(words)
            speak(listOf(Spoken(words, fr(p.lang))))
            return true
        }
        // A yes heard within 3 seconds of Robot saying a message's words may be Robot's own voice: only after that (a tap always works).
        if (fromVoice && !MessageQuarantine.voiceYesNow(heard(System.currentTimeMillis()), System.currentTimeMillis())) {
            val words = if (fr(p.lang)) MessageQuarantine.SAY_YES_AGAIN_FR else MessageQuarantine.SAY_YES_AGAIN
            PanelController.notify(words)
            speak(listOf(Spoken(words, fr(p.lang))))
            if (PanelController.state.value.speak) PanelController.messagesListen(now = false)
            return true
        }
        scope.launch { fire(p, if (fromVoice) CardEnd.VOICE_YES else CardEnd.TAP) }
        return true
    }

    // ------------------------------------------------------------------ saying

    /** A reply in Robot's own words (no message in it). */
    private fun say(replyId: String, text: String, lang: Lang) {
        PanelController.messagesReply(replyId, text)
        speak(listOf(Spoken(text, fr(lang))))
    }

    /**
     * Each piece in its voice (Kokoro's French voice for French), when replies are spoken. 0.8: in a headset or car session
     * each piece passes OutputGuard first as personal (PanelHandsFree.allow: private headphones only, the car's two
     * sentences), unless [guarded] says the caller already asked it (HandsFreeMessages).
     */
    private fun speak(pieces: List<Spoken>, guarded: Boolean = false) {
        if (!PanelController.state.value.speak) return
        VoiceHub.tts.prewarm()
        val frVoice = MessageVoices.french(VoiceHub.settings.voiceChoice)
        for (p in pieces) {
            val clean = SpeechText.clean(p.text)
            if (clean.isEmpty()) continue
            if (!guarded && !app.heyrobot.assistant.handsfree.PanelHandsFree.allow(clean) { true }) continue
            if (p.french && frVoice != null) VoiceHub.tts.speakOther(clean, frVoice.first, frVoice.second) else VoiceHub.tts.speak(clean)
        }
    }

    // ------------------------------------------------------------------ 0.8: headset and car (assistant/handsfree HandsFreeMessages)

    /**
     * [t], announced in the headset or the car, read after your yes (or at once, as Settings › Headset or Car mode say):
     * read as "read my messages" reads a conversation, with its translations, and handled the same way: History keeps
     * [replyId]'s placeholder (null: read at once, before any turn), the conversation is the one "reply …" means, it carries
     * the quarantine, what is said counts for the echo check, Robot marks it read (RAM) and "After Robot reads" applies.
     * The caller speaks [ReadPage.spoken] once OutputGuard allows it ([speakHandsFree]).
     */
    internal suspend fun handsFreeRead(replyId: String?, t: MessageThread, lang: Lang, locked: Boolean): ReadPage {
        sync()
        touch()
        val s = MessagesStore.current(ctx).settings
        val page = MessageReader.thread(t, lang, more = false, translations = others(t, lang, s.translate), french = MessageLanguage.frenchLine(langId))
        replyId?.let { kept[it] = page.kept }
        lastRead = t.key
        lastThread = t
        reading = false
        taint = taint.with(t)
        saidAloud(page.heard.joinToString(" "))
        VoiceLog.add(VoiceEvent.MSG_READ, MsgAppCode.of(t.app).id, page.ids.size, if (locked) 1 else 0)
        link().markReadByRobot(page.ids)
        when (s.afterReading) {
            AfterReading.LEAVE -> {}
            AfterReading.CLEAR -> link().clear(t.key)
            AfterReading.MARK_READ -> link().markRead(t.key)
        }
        return page
    }

    /** A messages phrase the hands-free router heard after 0.7's router let it go ("any messages?"), run as 0.7's own command. */
    internal suspend fun handsFreeCommand(replyId: String, cmd: MessageCommand) = run(replyId, Routed.Command(cmd))

    /** [pieces] in their voices, already allowed by OutputGuard (HandsFreeMessages). */
    internal fun speakHandsFree(pieces: List<Spoken>) = speak(pieces, guarded = true)

    /** A spoken yes counts now: Robot said none of a message's words in the last 3 seconds (core MessageQuarantine). */
    internal fun voiceYesNow(): Boolean = System.currentTimeMillis().let { now -> MessageQuarantine.voiceYesNow(heard(now), now) }

    /** A reply the car read back and you confirmed, sent by the main process to the conversation's own Reply action. */
    internal suspend fun handsFreeReply(r: ReplyRequest): app.heyrobot.core.messages.ReplyAnswer? = link().reply(r)

    /**
     * In the car, a reply only a tap may send (0.7's card), or one Robot never sends itself ("Open the chat; I press Send",
     * no Reply action): kept for when you park instead, with your own words (a draft or a translation without them), and
     * nothing opens while you drive. False outside a car session.
     */
    private fun parkInCar(p: Pending): Boolean {
        if (!app.heyrobot.assistant.handsfree.PanelHandsFree.inCar) return false
        val lang = p.lang
        val name = p.thread.name
        val words = p.text.takeIf { p.source == ReplySource.DICTATED && it.isNotBlank() }
        val summary = if (fr(lang)) "Répondre à $name" else "Reply to $name"
        val ask = if (words == null) summary.replaceFirstChar { it.lowercase() } else if (fr(lang)) "réponds à $name : $words" else "reply to $name: $words"
        app.heyrobot.assistant.handsfree.PanelHandsFree.keepReply(summary, ask)
        card = null
        say(p.replyId, app.heyrobot.core.output.OutputLines.KEEP_FOR_PARK.get(if (fr(lang)) app.heyrobot.core.output.SpokenLang.FR else app.heyrobot.core.output.SpokenLang.EN), lang)
        return true
    }

    /** Your messages' words leave this process after this long without a question or a tap (and when the panel hides). */
    const val IDLE_DROP_MS = 120_000L

    /** A summary's or a draft's one-shot may wait for the model to load. */
    private const val ONE_SHOT_MS = 120_000L
    private const val SUMMARY_CHUNKS = 400
    /** How long Robot may still be talking before a reply's 3 seconds start anyway. */
    private const val SPEECH_WAIT_MS = 15_000L
}
