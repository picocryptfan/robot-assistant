package app.heyrobot.assistant

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Choice
import app.heyrobot.core.Executed
import app.heyrobot.core.LangCode
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.memory.AddOutcome
import app.heyrobot.core.memory.ForgetLast
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemCommandCode
import app.heyrobot.core.memory.MemForget
import app.heyrobot.core.memory.MemoryAccess
import app.heyrobot.core.memory.MemKindCode
import app.heyrobot.core.memory.MemOrigin
import app.heyrobot.core.memory.MemoryCommand
import app.heyrobot.core.memory.MemoryCommands
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryHygiene
import app.heyrobot.core.memory.MemoryIds
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemoryRecall
import app.heyrobot.core.memory.MemoryReplies
import app.heyrobot.core.memory.MemoryRoute
import app.heyrobot.core.memory.MemoryRouting
import app.heyrobot.core.memory.MemorySearch
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.memory.MemoryVault
import app.heyrobot.core.memory.PackedMemory
import app.heyrobot.core.memory.PromptPacking
import app.heyrobot.core.memory.SecretDetector
import java.time.ZoneId

// VOICE PROCESS. Memory in the assistant panel (0.7, phase 7.1; design §A3, §A6, §A10). The router's commands before the
// model ("remember that …", "forget that", "what do you remember about …"), the remember and forget tools' preparing and
// running, what goes with a panel chat question (pinned preferences in the system prompt, found items before the
// question, within the loaded model's context), Undo, and what History keeps (item ids, never the notes; a secret only as
// a line saying so). The decisions are core's (memory/); this file holds the vault (sys/MemoryVault.kt) and the
// conversation's few facts: what was saved and used last, what Undo puts back. The decrypted file stays in RAM only
// while the panel shows; it goes when it hides, when Settings › Memory changes, and is never read on a locked phone
// unless "Use memory on the lock screen" is on. Nothing here logs an item's words: codes and counts only.

/** What a memory command did: Robot's [text] (also what it says), the card under it, and the items a pick card offers. */
class MemoryAnswer(
    val text: String,
    val card: UiAction? = null,
    /** Several items fit "forget …": the pick card lists them, and [MemoryPanel.forgetPicked] does the rest. */
    val pick: List<MemoryItem> = emptyList(),
    val fr: Boolean = false,
    /** The items the answer used or saved: the reply is personal for helpers (ChatTurn.memoryIds). */
    val ids: List<String> = emptyList(),
)

/** What memory gave a panel chat turn: the packed notes, how to render a past turn's notes again, and whether the tools go. */
class MemoryTurn(
    val recall: MemoryRecall.Recall,
    /** For a conversation rebuilt from History: a past question's notes, from the file as it is now. */
    val notes: (List<String>) -> String?,
    /** Memory is on: remember and forget may be described. */
    val tools: Boolean,
    /** A secret item went in and "Speak secret items aloud" is off: the answer is shown, never spoken, never kept. */
    val quiet: Boolean,
    /**
     * An item that didn't come from your own words (MemorySource.CONTENT: kept from a screen, a read or a conversation that
     * held a message) is in this turn's prompt, found now or given earlier in this conversation: the turn is content, so
     * what the model asks for follows Safety cards, as after Robot reads anything (design §A10).
     */
    val content: Boolean = false,
) {
    val packed: PackedMemory get() = recall.packed

    companion object {
        val NONE = MemoryTurn(MemoryRecall.Recall(PackedMemory.NONE, app.heyrobot.core.memory.RecallBy.WORDS), { null }, tools = false, quiet = false)
    }
}

object MemoryPanel {
    /** Undo keys start so; a note's Undo key never does. */
    private const val UNDO = "memory:"
    private const val SECRET_REFUSAL = "Robot doesn't keep passwords, PINs, codes or card numbers from a conversation. " +
        "To keep one, say “remember that …” yourself: it stays private, never spoken or sent."

    private var vaultRef: MemoryVault? = null
    /** Tests give their own vault and settings (a software key); the phone's are sys/MemoryVault.kt's. */
    internal var vaultFor: () -> MemoryVault = { app.heyrobot.sys.MemoryFiles.vault(VoiceHub.app) }
    internal var settingsFor: () -> MemorySettings = { app.heyrobot.sys.MemorySettingsStore.current(VoiceHub.app) }
    internal var clock: () -> Long = System::currentTimeMillis
    /** The phone is locked now (tests give their own): memory is never changed then (core MemoryAccess.writes). */
    internal var lockedNow: () -> Boolean = { app.heyrobot.sys.Assistant.locked(VoiceHub.app) }

    internal var zone: () -> ZoneId = ZoneId::systemDefault

    private fun vault(): MemoryVault = vaultRef ?: vaultFor().also { vaultRef = it }

    fun settings(): MemorySettings = settingsFor()

    /** "Share memory with helpers: With helpers I trust" (HelperTrust's memoryToTrusted). */
    fun toTrusted(): Boolean = settings().helpers == HelperMemory.TRUSTED_HELPERS

    // ------------------------------------------------------------------ the file, in RAM only while the panel shows

    @Volatile private var cache: Pair<Long, MemoryFile>? = null

    private fun stamp(): Long = (vault() as? app.heyrobot.sys.FileMemoryVault)?.stamp() ?: 0L

    /** The decrypted file, from the copy in RAM while the file on disk hasn't changed. Background thread. */
    @Synchronized
    fun load(): MemoryLoad {
        val s = stamp()
        cache?.let { (at, f) -> if (at == s && s != 0L) return MemoryLoad.Loaded(f) }
        val l = vault().load()
        cache = (l as? MemoryLoad.Loaded)?.let { s to it.file }
        if (l !is MemoryLoad.Loaded && l != MemoryLoad.Empty) app.heyrobot.sys.MemoryFiles.logLocked(l)
        return l
    }

    @Synchronized
    private fun update(change: (MemoryFile) -> MemoryFile): MemoryLoad {
        val l = vault().update(change)
        cache = (l as? MemoryLoad.Loaded)?.let { stamp() to it.file }
        return l
    }

    /** The panel hid, Settings › Memory changed, or a new conversation: what was decrypted goes. */
    @Synchronized
    fun drop() {
        cache = null
        vaultRef = null
    }

    /**
     * Settings › Memory changed what Robot remembers (Delete all, Start over, an edit, a switch): what was decrypted goes,
     * and no card's Undo here puts back what was forgotten any more (it may have been deleted there since). An Undo that
     * forgets what was just saved still works.
     */
    @Synchronized
    fun changedElsewhere() {
        drop()
        undos.entries.removeAll { (key, items) -> items.singleOrNull()?.id != key.removePrefix(UNDO) }
    }

    // ------------------------------------------------------------------ this conversation

    private var savedLast: String? = null
    private var savedAt = 0L
    private var usedLast: List<String> = emptyList()
    private var usedAt = 0L
    /** What each card's Undo does, by its key: an item saved (its id) or items forgotten (to put back). */
    private val undos = HashMap<String, List<MemoryItem>>()
    /** Replies whose History record changes: the items they used or saved, and whether a secret was in them. */
    private val marks = HashMap<String, Pair<List<String>, Boolean>>()

    @Synchronized
    fun newConversation() {
        savedLast = null
        savedAt = 0L
        usedLast = emptyList()
        usedAt = 0L
        undos.clear()
        marks.clear()
    }

    /** [replyId]'s History record: the items it used or saved ([ids]); with [secret], never its words. */
    @Synchronized
    fun mark(replyId: String, ids: List<String>, secret: Boolean = false) {
        if (ids.isNotEmpty() || secret) marks[replyId] = ids to secret
    }

    /** The reply as History keeps it (PanelController.finishReply): its memory ids, and a line in place of a secret answer. */
    @Synchronized
    fun forHistory(t: ChatTurn): ChatTurn {
        if (t.role != TurnRole.ASSISTANT) return t
        val (ids, secret) = marks.remove(t.id) ?: return t
        return t.copy(memoryIds = (t.memoryIds + ids).distinct(), text = if (secret) MemoryHygiene.SECRET_ANSWER else t.text)
    }

    // ------------------------------------------------------------------ the router

    /** The router's route for what you said ([locked]: the phone now), or null: not memory's. */
    fun route(q: String, locked: Boolean): MemoryRoute? = MemoryRouting.route(q, settings(), locked)

    /** What History keeps of the question: a secret is never in it ("Remember: [a code, kept in Memory]"). */
    fun question(route: MemoryRoute, said: String): String =
        ((route as? MemoryRoute.Run)?.command as? MemoryCommand.Remember)?.let { MemoryHygiene.question(it, said) } ?: said

    /** Runs [route] (background thread): the vault changes, the card and Robot's words. [spoken]: the question came by voice. */
    fun command(route: MemoryRoute, locked: Boolean, spoken: Boolean): MemoryAnswer {
        val c = (route as? MemoryRoute.Run)?.command
        val lang = when (route) {
            is MemoryRoute.Run -> route.command.language
            is MemoryRoute.Unlock -> route.language
            is MemoryRoute.Off -> route.language
        }
        val fr = lang == "fr"
        val code = when {
            c != null -> MemCommandCode.of(c)
            route is MemoryRoute.Unlock -> MemCommandCode.UNLOCK
            else -> MemCommandCode.OFF
        }
        val a = when (route) {
            is MemoryRoute.Unlock -> MemoryAnswer(MemoryReplies.unlock(fr, route.saving, route.changing), fr = fr)
            is MemoryRoute.Off -> MemoryAnswer(MemoryReplies.off(fr), fr = fr)
            is MemoryRoute.Run -> when (val cmd = route.command) {
                is MemoryCommand.ForgetEverything -> MemoryAnswer(MemoryReplies.forgetAll(fr), card = openMemoryCard(
                    "Delete everything Robot remembers", ActionStatus.SKIPPED, "Only Settings › Memory deletes everything."), fr = fr)
                // The route already said so; the phone may have locked since.
                is MemoryCommand.Remember -> if (!MemoryAccess.writes(locked || lockedNow())) MemoryAnswer(MemoryReplies.unlock(fr, saving = true), fr = fr)
                    else remember(cmd, spoken, fr)
                is MemoryCommand.Forget, is MemoryCommand.ForgetLast -> if (!MemoryAccess.writes(locked || lockedNow()))
                    MemoryAnswer(MemoryReplies.unlock(fr, changing = true), fr = fr) else withFile(fr) { f -> run(cmd, f, locked, fr) }
                else -> withFile(fr) { f -> run(cmd, f, locked, fr) }
            }
        }
        VoiceLog.add(VoiceEvent.MEM_COMMAND, code.id, LangCode.of(lang).id, a.ids.size + a.pick.size)
        return a
    }

    private fun withFile(fr: Boolean, block: (MemoryFile) -> MemoryAnswer): MemoryAnswer = when (val l = load()) {
        is MemoryLoad.Loaded -> block(l.file)
        MemoryLoad.Empty -> block(MemoryFile())
        else -> MemoryAnswer(MemoryReplies.cantOpen(l, fr), fr = fr)
    }

    private fun run(c: MemoryCommand, f: MemoryFile, locked: Boolean, fr: Boolean): MemoryAnswer {
        val now = clock()
        // Never a secret on the lock screen, and never read aloud here: they show in Settings › Memory.
        fun sayable(items: List<MemoryItem>) = items.filter { !it.secret }
        return when (c) {
            is MemoryCommand.ForgetLast -> {
                val ids = synchronized(this) { ForgetLast.target(savedLast, savedAt, usedLast, usedAt) }
                val items = ids.mapNotNull { id -> f.items.firstOrNull { it.id == id } }
                when (items.size) {
                    0 -> MemoryAnswer(MemoryReplies.nothingToForget(fr), fr = fr)
                    1 -> forget(items, MemForget.LAST, fr)
                    else -> MemoryAnswer(MemoryReplies.which(fr), pick = items, fr = fr)
                }
            }
            is MemoryCommand.Forget -> {
                val found = MemorySearch.clear(c.query, f.items, now).filter { !locked || !it.secret }
                when (found.size) {
                    0 -> MemoryAnswer(MemoryReplies.notFound(fr), fr = fr)
                    1 -> forget(found, MemForget.SEARCH, fr)
                    else -> MemoryAnswer(MemoryReplies.which(fr), pick = found, fr = fr)
                }
            }
            is MemoryCommand.Recall -> {
                val found = MemorySearch.find(c.about, f.items, now)
                val said = sayable(found)
                MemoryAnswer(MemoryReplies.recall(c.about, said, private = found.size - said.size, fr = fr), fr = fr, ids = said.map { it.id })
                    .also { remember(used = said.map { it.id }) }
            }
            is MemoryCommand.AboutMe -> {
                val live = MemoryEdits.live(f.items, now)
                val ex = MemorySearch.examples(live, now)
                MemoryAnswer(MemoryReplies.aboutMe(live.size, ex, fr), fr = fr, ids = ex.map { it.id },
                    card = if (live.isEmpty()) null else openMemoryCard("What Robot remembers", ActionStatus.DONE,
                        if (live.size == 1) "1 thing, in Settings › Memory." else "${live.size} things, in Settings › Memory."))
            }
            is MemoryCommand.Remember, is MemoryCommand.ForgetEverything -> error("handled before")
        }
    }

    @Synchronized
    private fun remember(used: List<String>) {
        if (used.isEmpty()) return
        usedLast = used
        usedAt = clock()
    }

    private fun remember(c: MemoryCommand.Remember, spoken: Boolean, fr: Boolean): MemoryAnswer {
        val now = clock()
        val id = MemoryIds.newId(now)
        val item = MemoryItem(id, c.text, c.kind, secret = c.secret, source = if (spoken) MemorySource.SAID else MemorySource.TYPED,
            language = c.language, createdAt = now, expiresAt = c.until?.expiresAt(now, zone()))
        var added: app.heyrobot.core.memory.Added? = null
        val l = update { f -> MemoryEdits.add(f, item).also { added = it }.file }
        val r = added
        if (l !is MemoryLoad.Loaded || r == null) return MemoryAnswer(MemoryReplies.cantOpen(l, fr), fr = fr)
        val saved = r.item?.takeIf { r.outcome == AddOutcome.SAVED || r.outcome == AddOutcome.SAVED_PIN_FULL }
        val text = MemoryReplies.saved(c, r.outcome)
        if (saved == null) return MemoryAnswer(text, fr = fr, ids = listOfNotNull(r.item?.id))
        logSaved(saved)
        val key = UNDO + saved.id
        synchronized(this) {
            savedLast = saved.id
            savedAt = now
            undos[key] = listOf(saved)
        }
        val (summary, body) = MemoryReplies.savedCard(saved)
        val card = UiAction("", ToolName.REMEMBER, summary, ActionStatus.DONE, MemoryReplies.CARD_SAVED_DETAIL, body = body, undo = key,
            handOff = SettingsPage.MEMORY)
        return MemoryAnswer(text, card = card, fr = fr, ids = listOf(saved.id))
    }

    /** Forgets [items] now, with Undo ([how]: for the voice log). Never on a locked phone. */
    fun forget(items: List<MemoryItem>, how: MemForget, fr: Boolean): MemoryAnswer {
        if (!MemoryAccess.writes(lockedNow())) return MemoryAnswer(MemoryReplies.unlock(fr, changing = true), fr = fr)
        val ids = items.map { it.id }.toSet()
        var gone: List<MemoryItem> = emptyList()
        val l = update { f -> MemoryEdits.forget(f, ids).also { gone = it.second }.first }
        if (l !is MemoryLoad.Loaded) return MemoryAnswer(MemoryReplies.cantOpen(l, fr), fr = fr)
        if (gone.isEmpty()) return MemoryAnswer(MemoryReplies.nothingToForget(fr), fr = fr)
        VoiceLog.add(VoiceEvent.MEM_FORGET, gone.size, how.id)
        val key = UNDO + MemoryIds.newId(clock(), prefix = 's')
        synchronized(this) {
            undos[key] = gone
            if (savedLast in ids) savedLast = null
            usedLast = usedLast - ids
        }
        val card = UiAction("", ToolName.FORGET, MemoryReplies.CARD_FORGOTTEN, ActionStatus.DONE, MemoryReplies.CARD_FORGOTTEN_DETAIL,
            body = MemoryHygiene.list(gone), undo = key)
        return MemoryAnswer(MemoryReplies.forgotten(gone.size, fr), card = card, fr = fr)
    }

    /** After the pick card: the one tapped forgotten, or nothing ([picked] null: Cancel). */
    fun forgetPicked(picked: MemoryItem?, fr: Boolean): MemoryAnswer =
        if (picked == null) MemoryAnswer(if (fr) "D'accord, je garde tout." else "Okay, I'll keep them.", fr = fr) else forget(listOf(picked), MemForget.SEARCH, fr)

    /** The pick card's rows: each item's words (a secret's placeholder). */
    fun pickLabels(items: List<MemoryItem>): List<String> = items.map { MemoryHygiene.shown(it) }

    private fun openMemoryCard(summary: String, status: ActionStatus, detail: String) =
        UiAction("", ToolName.REMEMBER, summary, status, detail, handOff = SettingsPage.MEMORY)

    private fun logSaved(item: MemoryItem) =
        VoiceLog.add(VoiceEvent.MEM_SAVE, MemOrigin.of(item.source).id, MemKindCode.of(item).id, if (item.expiresAt != null) 1 else 0)

    // ------------------------------------------------------------------ a panel chat turn

    /**
     * What memory gives a panel chat question (background thread): pinned and found items within the loaded model's
     * context ([maxTokens]), nothing already in this live conversation ([given]), nothing on a locked phone unless "Use
     * memory on the lock screen" is on (and then never a secret).
     */
    fun forTurn(question: String, locked: Boolean, given: Set<String>, maxTokens: Int): MemoryTurn {
        val s = settings()
        if (!s.on) return MemoryTurn.NONE
        // Locked without "Use memory on the lock screen": nothing is opened, and the tools (which would need unlocking) aren't described.
        if (locked && !s.onLockScreen) return MemoryTurn.NONE
        val t0 = System.nanoTime()
        val f = when (val l = load()) {
            is MemoryLoad.Loaded -> l.file
            else -> return MemoryTurn(MemoryTurn.NONE.recall, { null }, tools = true, quiet = false)
        }
        val now = clock()
        val z = zone()
        // A private item never stays in the live conversation (it closes after its answer, PanelController): named again, it
        // is given again rather than taken as already there.
        val secrets = f.items.filter { it.secret }.mapTo(HashSet()) { it.id }
        val r = MemoryRecall.forTurn(question, f, s, locked, now, given - secrets, maxTokens, zone = z)
        if (r.packed.retrievedIds.isNotEmpty()) {
            VoiceLog.add(VoiceEvent.MEM_RECALL, r.packed.retrievedIds.size, r.by.code, ((System.nanoTime() - t0) / 1_000_000).toInt())
            remember(used = r.packed.retrievedIds)
        }
        val quiet = r.secret && f.items.filter { it.id in r.packed.retrievedIds && it.secret }.any { !app.heyrobot.core.memory.MemoryAccess.speak(it, question, s, locked) }
        // Notes from content, now or in this conversation's earlier questions (a rebuilt conversation gets those again).
        val content = r.packed.content || f.items.any { it.id in given && it.tainted }
        return MemoryTurn(r, { ids -> PromptPacking.notesFor(ids, f.items, now, z) }, tools = true, quiet = quiet, content = content)
    }

    /**
     * 0.9: what the model may know from memory in a conversation that was given [given] (core web QueryContext.memory): those
     * items and, with Memory on, the pinned ones (in every system prompt). Their words never go to a search service.
     * Background thread; nothing when Memory is off or the file isn't open.
     */
    fun forWebCheck(given: Collection<String>): List<String> {
        if (!settings().on && given.isEmpty()) return emptyList()
        val f = (load() as? MemoryLoad.Loaded)?.file ?: return emptyList()
        val ids = given.toSet()
        return f.items.filter { it.id in ids || it.pinned }.map { it.text }
    }

    /** One more answer used [ids] (a counter and a time, no text): background thread, after the answer. Not counted on a locked phone (nothing is written there). */
    fun used(ids: List<String>) {
        if (ids.isEmpty() || !MemoryAccess.writes(lockedNow())) return
        try { update { MemoryEdits.used(it, ids, clock()) } } catch (t: Throwable) { RLog.w("memory_used", t) }
    }

    // ------------------------------------------------------------------ the tools (the local model; never a helper's)

    /** remember and forget made ready for the gate, or null: not memory's action. Background thread. */
    fun prepare(action: Action, locked: Boolean): Prepared? = when (action) {
        is Action.Remember -> {
            val s = settings()
            when {
                !s.on -> Prepared.Refused(ActionStatus.DISABLED, "Memory is off in Robot's settings.")
                // Never saved on the lock screen, "Use memory on the lock screen" or not.
                !MemoryAccess.writes(locked) -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, MemoryAccess.UNLOCK_TO_SAVE)
                // A code, a password or a card number in what the model proposes: refused, with the reason.
                SecretDetector.isSecret(action.text) -> Prepared.Refused(ActionStatus.FAILED, SECRET_REFUSAL)
                MemoryEdits.clean(action.text).isEmpty() -> Prepared.Refused(ActionStatus.INVALID, "There was nothing to remember.")
                // The card shows exactly the words kept (MemoryEdits.add keeps them so cleaned).
                else -> Prepared.Ready(action.copy(text = MemoryEdits.clean(action.text)))
            }
        }
        is Action.Forget -> prepareForget(action, locked)
        else -> null
    }

    private fun prepareForget(a: Action.Forget, locked: Boolean): Prepared {
        val s = settings()
        if (!s.on) return Prepared.Refused(ActionStatus.DISABLED, "Memory is off in Robot's settings.")
        if (!MemoryAccess.writes(locked)) return Prepared.Refused(ActionStatus.NEEDS_UNLOCK, MemoryAccess.UNLOCK_TO_CHANGE)
        val f = when (val l = load()) {
            is MemoryLoad.Loaded -> l.file
            MemoryLoad.Empty -> MemoryFile()
            else -> return Prepared.Refused(ActionStatus.FAILED, MemoryReplies.cantOpen(l, false))
        }
        val found = MemorySearch.clear(a.query, f.items, clock())
        if (found.isEmpty()) return Prepared.Refused(ActionStatus.NOT_FOUND, "Nothing in memory matches that.")
        val all = Action.Forget(a.query, found.map { it.id }, MemoryHygiene.list(found))
        if (found.size == 1) return Prepared.Ready(all)
        return Prepared.Ready(all, choices = found.map { Choice(MemoryHygiene.shown(it), Action.Forget(a.query, listOf(it.id), MemoryHygiene.shown(it))) },
            note = MemoryReplies.which(false))
    }

    /**
     * Runs remember or forget after the gate (background thread), or null: not memory's. [spoken]: the question came by
     * voice; [content]: the conversation holds content (a screen, a photo, a read tool's findings, a helper's words, a
     * message), so a remembered item is content too, with where it came from; [messages]: a message is in it (0.7: only
     * your own "remember that …", kept after its card's tap: MessageQuarantine).
     */
    fun run(action: Action, spoken: Boolean, content: Boolean, messages: Boolean = false): Executed? = when (action) {
        // The phone locked while the card waited: nothing is changed there.
        is Action.Remember, is Action.Forget -> if (!MemoryAccess.writes(lockedNow()))
            Executed(ActionStatus.NEEDS_UNLOCK, if (action is Action.Remember) MemoryAccess.UNLOCK_TO_SAVE else MemoryAccess.UNLOCK_TO_CHANGE) else runWrite(action, spoken, content, messages)
        else -> null
    }

    private fun runWrite(action: Action, spoken: Boolean, content: Boolean, messages: Boolean): Executed? = when (action) {
        is Action.Remember -> {
            val now = clock()
            val item = MemoryItem(MemoryIds.newId(now), action.text, action.kind, source = when {
                content || messages -> MemorySource.CONTENT
                spoken -> MemorySource.SAID
                else -> MemorySource.TYPED
            }, sourceLabel = when {
                messages -> "your words in a conversation that held a message"
                content -> "something Robot read in a conversation"
                else -> null
            }, createdAt = now,
                expiresAt = action.until?.expiresAt(now, zone()))
            var added: app.heyrobot.core.memory.Added? = null
            val l = update { f -> MemoryEdits.add(f, item).also { added = it }.file }
            val r = added
            when {
                l == MemoryLoad.Locked -> Executed(ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
                l !is MemoryLoad.Loaded || r == null -> Executed(ActionStatus.FAILED, MemoryReplies.cantOpen(l, false))
                r.outcome == AddOutcome.FULL -> Executed(ActionStatus.FAILED, "Memory is full: delete some things in Settings › Memory.")
                r.outcome == AddOutcome.ALREADY_KEPT -> Executed(ActionStatus.DONE, "Robot already remembers that.")
                r.outcome == AddOutcome.EMPTY || r.item == null -> Executed(ActionStatus.INVALID, "There was nothing to remember.")
                else -> {
                    val saved = r.item!!
                    logSaved(saved)
                    val key = UNDO + saved.id
                    synchronized(this) {
                        savedLast = saved.id
                        savedAt = now
                        undos[key] = listOf(saved)
                    }
                    Executed(ActionStatus.DONE, if (r.outcome == AddOutcome.SAVED_PIN_FULL) "Remembered. Six things are pinned already, so it isn't pinned."
                        else if (saved.pinned) "Remembered, and used in every answer." else "Remembered.", undo = key)
                }
            }
        }
        is Action.Forget -> forgetIds(action.ids)
        else -> null
    }

    private fun forgetIds(ids: List<String>): Executed {
        if (ids.isEmpty()) return Executed(ActionStatus.NOT_FOUND, "Nothing in memory matches that.")
        var gone: List<MemoryItem> = emptyList()
        val l = update { f -> MemoryEdits.forget(f, ids.toSet()).also { gone = it.second }.first }
        if (l == MemoryLoad.Locked) return Executed(ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
        if (l !is MemoryLoad.Loaded) return Executed(ActionStatus.FAILED, MemoryReplies.cantOpen(l, false))
        if (gone.isEmpty()) return Executed(ActionStatus.NOT_FOUND, "It was already gone.")
        VoiceLog.add(VoiceEvent.MEM_FORGET, gone.size, MemForget.CARD.id)
        val key = UNDO + MemoryIds.newId(clock(), prefix = 's')
        synchronized(this) {
            undos[key] = gone
            if (savedLast in ids) savedLast = null
            usedLast = usedLast - ids.toSet()
        }
        return Executed(ActionStatus.DONE, if (gone.size == 1) "Forgotten." else "Forgotten: ${gone.size} things.", undo = key)
    }

    // ------------------------------------------------------------------ Undo

    fun isUndo(key: String?): Boolean = key?.startsWith(UNDO) == true

    /** Undo on a memory card (background thread): a saved item forgotten again, forgotten ones put back. The card's new line. */
    fun undo(key: String): String {
        if (!MemoryAccess.writes(lockedNow())) return MemoryAccess.UNLOCK_TO_CHANGE
        val items = synchronized(this) { undos.remove(key) } ?: return "Nothing to undo."
        val saved = key.removePrefix(UNDO).let { id -> items.singleOrNull()?.id == id }
        val l = if (saved) update { MemoryEdits.forget(it, items.map { i -> i.id }.toSet()).first } else update { MemoryEdits.restore(it, items) }
        if (l !is MemoryLoad.Loaded) return MemoryReplies.cantOpen(l, false)
        return if (saved) {
            VoiceLog.add(VoiceEvent.MEM_FORGET, items.size, MemForget.UNDO.id)
            synchronized(this) { if (savedLast == items.single().id) savedLast = null }
            "Removed from memory."
        } else {
            items.forEach(::logSaved)
            // One item back is again what "forget that" means.
            items.singleOrNull()?.let { synchronized(this) { savedLast = it.id; savedAt = clock() } }
            "Put back in memory."
        }
    }

    /** For tests: what the panel holds of this conversation. */
    internal fun state(): String = synchronized(this) { "saved=$savedLast used=$usedLast undos=${undos.size}" }

}
