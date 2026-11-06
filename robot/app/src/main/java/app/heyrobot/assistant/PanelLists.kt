// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ListKind
import app.heyrobot.core.i18n.ListsStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.lists.ItemDone
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ItemOutcome
import app.heyrobot.core.lists.ItemProblem
import app.heyrobot.core.lists.ItemText
import app.heyrobot.core.lists.ItemsRead
import app.heyrobot.core.lists.JournalList
import app.heyrobot.core.lists.JournalRefusal
import app.heyrobot.core.lists.ListAddCode
import app.heyrobot.core.lists.ListEditCode
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ListItem
import app.heyrobot.core.lists.ListQuote
import app.heyrobot.core.lists.ListSplit
import app.heyrobot.core.lists.ListSplitCode
import app.heyrobot.core.lists.ListLockedCode
import app.heyrobot.core.lists.ListMakeCode
import app.heyrobot.core.lists.ListReadCode
import app.heyrobot.core.lists.ListRef
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListTickCode
import app.heyrobot.core.lists.ListsCommand
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsHeard
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsJournal
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsMatch
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.ListsRouter
import app.heyrobot.core.lists.ListsSeen
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListsShared
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.NewItem
import app.heyrobot.core.memory.MemoryHygiene
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ListsSettingsStore
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.ListsWrite
import app.heyrobot.sys.LockedAdd
import app.heyrobot.sys.LockedEdit
import app.heyrobot.ui.common.ListCardOp
import app.heyrobot.ui.common.ListCardRow
import app.heyrobot.ui.common.ListCardView
import java.security.SecureRandom

// VOICE PROCESS. Robot Lists by voice and typed words in the panel (lists-plan.md §3, §5, §8.4; job LS3). The panel asks
// [route] right after Robot Files and before hands-free (D9); core ListsRouter hears the words, and this file does them
// through the vault (sys/ListsVault) and answers by code, from ListsStrings, never through the model:
//   "Added oat milk to Shopping." with a card: the list's name, one line per item, Undo;
//   "Shopping has 5 things: …" (read), "Yes, Shopping has eggs." (check), "Ticked off eggs." (tick, with Undo)…
// The list a turn used becomes the list in focus for the rest of the conversation, so "take eggs off", "add bread too" and
// "undo that" need no list name.
//
// The lock (§5), with "locked" as ListsFiles.locked (the keyguard, a phone lock or the device locked):
//   - during a phone lock for someone else: nothing, "Robot can't change your lists right now.";
//   - locked: an add goes to the sealed journal (ListsVault.addLocked) while "Add to lists while locked" is on, said back as
//     "Added oat milk to your shopping list." and nothing more (Robot can't see the list); a read, a check, a tick or a put
//     back says "Unlock your phone to hear your lists."; the rest "Unlock your phone to change your lists.";
//   - locked with "Hear and tick on the lock screen" on: reads, checks, ticks and put backs as unlocked, and adds into the
//     list itself (ListsVault.lockedEdit) while "Add to lists while locked" is on too (a default: both switches guard an
//     add while locked); never a new list, a clear or an empty.
// While locked there is no card and no Undo (§7.4).
//
// Items are content, never instructions (§8.4): a reply that reads a list carries core ListQuote's content record, so the
// model reads it back framed as information and every later action of its asks first; History keeps only its placeholder
// ("Robot read Shopping: 5 items", D17: ConversationCodec saves the record's words in its place). LS10 (M1): so does every
// other answer from your lists, a check, "what lists do I have", "Oat milk or almond milk?", "already ticked", and a tick or a
// put back unless each item it names is your words ("Robot answered from your lists"). LS4: the model's one list
// tool, add_to_list ([prepareModel], [runModel]), and the split of a run of words with no separators by the model's
// LIST_SPLIT one-shot ([Env.split], core ListSplit: kept only when every item is a run of your words). What's kept in RAM between turns
// ([seen]: your items as folded words, so the router can tell "take eggs off" from "take my shoes off" without opening the
// vault on the main thread) goes when the panel hides, a conversation starts or the phone is locked. The voice log and
// RLog get codes and counts only.

internal object PanelLists {
    /** What the panel's lists need of the phone (the tests give their own). */
    interface Env {
        fun vault(): ListsVault
        fun settings(): ListsSettings
        /** The phone is locked for Robot Lists (ListsFiles.locked). */
        fun locked(): Boolean
        /** A phone lock for someone else is on. */
        fun someoneElse(): Boolean
        /**
         * LS4: the model's LIST_SPLIT answer for [piece] (core ListSplit.request), or null when it can't answer now: no model
         * loaded (a list never loads one), busy past the start deadline, or over ListSplit.TOTAL_MS. A background thread.
         */
        fun split(piece: String): String? = null
        /** 1.5: another person's words were read out: the conversation holds them (core LinkTaint). */
        fun heardPeers(ids: Set<String>) {}
    }

    private class Phone(private val ctx: Context) : Env {
        override fun heardPeers(ids: Set<String>) = PanelMessages.withPairings(ids)
        override fun vault(): ListsVault = ListsFiles.vault(ctx)
        override fun settings(): ListsSettings = ListsSettingsStore.current(ctx)
        override fun locked(): Boolean = ListsFiles.locked(ctx)
        override fun someoneElse(): Boolean = PhoneLockNow.someoneElse(ctx)
        override fun split(piece: String): String? {
            // Only a model already loaded: loading one (seconds, and its memory) for a split isn't worth it (§3.5).
            if (!BrainKeeper.ready) return null
            val budget = app.heyrobot.core.OneShotBudget(ListSplit.START_MS, ListSplit.TOTAL_MS - ListSplit.START_MS, waitForLoad = false)
            return ListSplit.answer(kotlinx.coroutines.runBlocking { BrainOneShots.run(ListSplit.request(piece), budget) {} })
        }
    }

    /** The tests' phone; null on the phone itself. LS12: setting it drops what the panel saw of the last one's lists. */
    @Volatile var env: Env? = null
        set(v) {
            field = v
            names = emptyList()
            seen = null
        }

    private fun env(ctx: Context): Env = env ?: Phone(ctx.applicationContext)

    /**
     * Robot's reply: [text] shown and [spoken]; [card] under it (a change's Undo, or Empty's tap-only card); [kept], what
     * History keeps instead of [text] (a list read aloud, or another answer from your lists: list content, M1); [undid], the Undo key "undo that" used (its card
     * says Undone).
     */
    class Reply(val text: Phrase, val spoken: Phrase = text, val card: UiAction? = null, val kept: Phrase? = null, val clean: Boolean = false, val undid: String? = null,
        /** LS5: a read's or a check's card with tick boxes (§7.4), shown and never saved; never while locked. */
        val list: ListCardView? = null)

    /** Undo keys and the Empty cards' ids start so. */
    private const val UNDO = "lists:"
    private const val EMPTY = "lists-empty-"
    private const val CARD = "lists-"
    /** LS5: a card's "Open Shopping" (its hand-off): this, then the list's id. */
    private const val OPEN = "lists-open:"

    /** The list in focus: its id and how it is called. */
    private class Focus(val id: ListsId, val label: Phrase)

    /** What a card's Undo (or "undo that") puts back: each item's change, on its list; [made], a list this change made. */
    private class Undo(val items: List<Pair<ListsId, ItemDone>>, val made: ListsId? = null)

    @Volatile private var focus: Focus? = null
    /** list-it-fix: the turn just before was Lists' (set by [handle], cleared by [otherTurn] and [forget]): only then is "it" a list. */
    @Volatile private var lastTurn = false
    @Volatile private var seen: ListsSeen? = null
    @Volatile private var names: List<String> = emptyList()
    private val undos = LinkedHashMap<String, Undo>()
    @Volatile private var lastUndo: String? = null
    private val empties = HashMap<String, ListsId>()
    /** Replies that read a list aloud or answered from it (M1), and the placeholder History keeps for each. */
    private val reads = HashMap<String, Phrase>()
    private val peerReads = HashSet<String>()
    /** LS5: each "Open …" hand-off's list name, for its button; each list card's Undo, by its reply. */
    private val opens = HashMap<String, Phrase>()
    private val cardUndos = HashMap<String, String>()
    private val random = SecureRandom()
    private val ids: () -> ListsId = { ListsId.new(random) }

    private fun token(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) } // l10n: not text (a random token)

    // ------------------------------------------------------------------ the panel's life

    /** Your words, when they are about your lists; null with Lists off (they reach the model as before). Main thread: no vault here. */
    fun route(ctx: Context, q: String): ListsCommand? {
        val s = try { env(ctx).settings() } catch (t: Exception) { RLog.w("lists_route", t); return null }
        if (!s.on) return null
        return ListsRouter.route(q, ListsHeard(names = names, focus = focus != null, undo = lastUndo != null, seen = seen, lastTurn = lastTurn))
    }

    /**
     * At a show and after each list turn (a background thread): your lists' names, and their items as folded words while they
     * can be seen (unlocked, or "Hear and tick on the lock screen" on), for [route]. Never makes the first-use lists.
     */
    fun refresh(ctx: Context) {
        try {
            val e = env(ctx)
            val s = e.settings()
            if (!s.on || e.someoneElse()) return drop()
            val locked = e.locked()
            if (!locked || s.lockScreen) {
                when (val l = e.vault().load(firstUse = false)) {
                    is ListsLoad.Loaded -> {
                        val shown = if (locked) ListsShared.forLockScreen(l.value) else l.value
                        names = shown.lists.filter { it.yours }.mapNotNull { it.name }
                        seen = ListsSeen.of(shown, focus?.id)
                    }
                    else -> seen = null
                }
            } else {
                seen = null
                names = (e.vault().lockedLists() as? ListsLoad.Loaded)?.value?.lists?.mapNotNull { it.name }.orEmpty()
            }
        } catch (t: Exception) {
            RLog.w("lists_refresh", t)
            seen = null
        }
    }

    /** Lists is on (Settings › Lists): your lists' words are Lists' (ToolRouter, ChatOnly), not notes'. */
    fun on(ctx: Context): Boolean = try { env(ctx).settings().on } catch (t: Exception) { RLog.w("lists_on", t); false }

    /** LS4: add_to_list may be described to the model: Lists on, the phone unlocked, no phone lock (§5). A background thread. */
    fun open(ctx: Context): Boolean = try {
        env(ctx).let { e -> e.settings().on && !e.locked() && !e.someoneElse() }
    } catch (t: Exception) {
        RLog.w("lists_open", t)
        false
    }

    /** The panel hid: what was seen of your items goes (the list in focus and the cards' Undo stay with the conversation). */
    fun drop() {
        seen = null
    }

    /**
     * LS10 (L1, §3.2): a turn that wasn't Lists' (PanelController.send, your words went elsewhere). "Undo that" works only
     * right after a list change, so the voice's Undo goes; each card's Undo button still works.
     */
    fun otherTurn() {
        lastUndo = null
        lastTurn = false
    }

    /**
     * A new or another conversation: nothing of this one's lists carries over. LS12 (LT5 on the Pixel 8): your lists' names
     * stay, since they are the phone's, not the conversation's (read again at each show, list turn and Cmd.LISTS); without
     * them « ajoute Sam à la liste des invités » in a new conversation reached the model, though you have a list Invités.
     */
    fun forget() {
        synchronized(this) {
            undos.clear()
            empties.clear()
            reads.clear()
            opens.clear()
            cardUndos.clear()
        }
        focus = null
        seen = null
        lastUndo = null
        lastTurn = false
    }

    /**
     * LS10 (M2): your question as the conversation and History keep it, for list command [cmd]: words that hold a secret (a
     * code, a password, a card number), which Lists refuses, are kept only as memory's private placeholder, as a refused
     * "remember that …" is (MemoryHygiene), in [cmd]'s language; anything else as said.
     */
    fun question(cmd: ListsCommand, q: String): String =
        if (!SecretDetector.isSecret(q)) q else if (cmd.lang == Lang.FR) MemoryHygiene.PRIVATE_FR else MemoryHygiene.PRIVATE

    fun isUndo(key: String?): Boolean = key?.startsWith(UNDO) == true
    fun isEmpty(actionId: String?): Boolean = actionId?.startsWith(EMPTY) == true

    /**
     * [turn] as the conversation keeps it: a list read aloud (or another answer from your lists, M1) carries its content record (core ListQuote), with the placeholder
     * History saves in its place (D17), never its items; the live conversation keeps the reply, framed for the model.
     */
    fun forHistory(turn: ChatTurn, langs: Langs): ChatTurn {
        val kept = synchronized(this) { reads.remove(turn.id) } ?: return turn
        val peer = synchronized(this) { peerReads.remove(turn.id) }
        return turn.copy(actions = turn.actions + ListQuote.record(kept.render(langs), peer))
    }

    /** F119: [turn] after a list read in [earlier]: History keeps [ListsStrings.answered], never what the model made of the items. */
    fun followUp(turn: ChatTurn, earlier: List<ChatTurn>, langs: Langs): ChatTurn = ListQuote.followUp(turn, earlier, ListsStrings.answered().render(langs))

    // ------------------------------------------------------------------ a turn

    /** Does [cmd] (a background thread): the reply, its card, and what History keeps. [spoken]: said rather than typed. */
    fun handle(ctx: Context, replyId: String, cmd: ListsCommand, langs: Langs, spoken: Boolean): Reply {
        lastTurn = true
        val e = env(ctx)
        val r = try {
            run(e, cmd, langs, if (spoken) ItemHow.VOICE else ItemHow.TYPED)
        } catch (t: Exception) {
            RLog.w("lists_turn", t)
            Reply(ListsStrings.notNow())
        }
        r.kept?.let { k -> synchronized(this) { reads[replyId] = k } }
        if (r.kept != null && !r.clean) heardPeers(e, replyId)
        refresh(ctx)
        LinkNotices.kick() // 1.5: a shared list's change goes out.
        return r
    }

    /**
     * 1.5: an answer from your lists when any of them holds another person's words: the record says so and the conversation is
     * theirs too. Whole-file on purpose: an answer may come from any list, and a read of a clean list says it is ([Reply.clean]).
     */
    private fun heardPeers(e: Env, replyId: String) {
        val file = (e.vault().load(firstUse = false) as? ListsLoad.Loaded)?.value ?: return
        val lists = file.lists.filter(ListsShared::peerText)
        if (lists.isEmpty()) return
        synchronized(this) { peerReads += replyId }
        e.heardPeers(lists.flatMap(ListsShared::peers).toSet())
    }

    private fun run(e: Env, cmd: ListsCommand, langs: Langs, how: ItemHow): Reply {
        val s = e.settings()
        if (e.someoneElse()) return lockedLine(ListLockedCode.SOMEONE_ELSE, ListsStrings.someoneElse())
        val locked = e.locked()
        if (locked) {
            if (!s.lockScreen) return when (cmd) {
                is ListsCommand.Add -> if (s.addLocked) journal(e, cmd) else lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToChange())
                is ListsCommand.Read, is ListsCommand.Check, is ListsCommand.Tick, is ListsCommand.PutBack, is ListsCommand.MyLists ->
                    lockedLine(ListLockedCode.UNLOCK_TO_HEAR, ListsStrings.unlockToHear())
                is ListsCommand.Undo -> lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToUndo())
                else -> lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToChange())
            }
            when (cmd) {
                is ListsCommand.Make, is ListsCommand.ClearDone, is ListsCommand.Empty, is ListsCommand.Undo ->
                    return lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, if (cmd is ListsCommand.Undo) ListsStrings.unlockToUndo() else ListsStrings.unlockToChange())
                is ListsCommand.Add -> if (!s.addLocked) return lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToChange())
                else -> {}
            }
            VoiceLog.add(VoiceEvent.LIST_LOCKED, ListLockedCode.LOCK_SCREEN.id, 0)
        }
        val file = when (val l = e.vault().load(firstUse = !locked)) {
            is ListsLoad.Loaded -> if (locked) ListsShared.forLockScreen(l.value) else l.value
            ListsLoad.Empty -> ListsFile()
            else -> return problem(l)
        }
        return when (cmd) {
            is ListsCommand.Add -> add(e, file, cmd, locked, langs, how)
            is ListsCommand.Read -> read(file, cmd, locked, langs)
            is ListsCommand.Check -> check(file, cmd, locked, langs)
            is ListsCommand.Tick -> tick(e, file, cmd, locked, langs, how)
            is ListsCommand.PutBack -> putBack(e, file, cmd, locked, langs, how)
            is ListsCommand.Make -> make(e, file, cmd, langs, how)
            is ListsCommand.ClearDone -> clear(e, file, cmd, langs, how)
            is ListsCommand.Empty -> empty(file, cmd, langs)
            is ListsCommand.MyLists -> myLists(file)
            is ListsCommand.Undo -> undoLast(e, how)
        }
    }

    private fun lockedLine(code: ListLockedCode, text: Phrase): Reply {
        VoiceLog.add(VoiceEvent.LIST_LOCKED, code.id, 0)
        return Reply(text)
    }

    /** Lists that couldn't be opened: "not now" for a Keystore that may answer later, else Settings › Lists. */
    private fun problem(l: ListsLoad<*>): Reply {
        VoiceLog.add(VoiceEvent.LIST_LOCKED, ListLockedCode.NOT_NOW.id, 0)
        return Reply(problemText(l))
    }

    private fun problemText(l: ListsLoad<*>): Phrase = when (l) {
        ListsLoad.KeyGone, ListsLoad.Unreadable, is ListsLoad.TooNew -> ListsStrings.unreadable("settings" to UiStrings.inSettings(UiStrings.lists))
        else -> ListsStrings.notNow()
    }

    // ------------------------------------------------------------------ which list

    /** How [l] is called: its name, or its role's name in the reply's language. */
    private fun label(l: ListsList): Phrase = l.name?.let(Phrase::plain) ?: when (l.role) {
        ListRole.SHOPPING -> ListsStrings.shopping()
        ListRole.TODO -> ListsStrings.todo()
        null -> ListsStrings.page()
    }

    private fun yours(file: ListsFile): List<ListsList> = file.lists.filter { it.yours }

    /** The list [ref] names in [file], or null; [Unnamed] is the list in focus (null without one). */
    private fun find(file: ListsFile, ref: ListRef): ListsList? = when (ref) {
        is ListRef.Role -> file.withRole(ref.role)?.takeIf { it.yours }
        is ListRef.Named -> yours(file).firstOrNull { l -> l.names.any { ItemText.same(it, ref.name) } }
            ?: ListRole.entries.firstOrNull { r -> r.names.any { ItemText.same(it, ref.name) } }?.let { file.withRole(it) }
        ListRef.Unnamed, ListRef.It -> focus?.let { f -> file.list(f.id) }?.takeIf { it.yours }
    }

    /** The list [ref] names, else, for "the list" with none in focus, Shopping. */
    private fun findOrShopping(file: ListsFile, ref: ListRef): ListsList? = find(file, ref) ?: if (ref == ListRef.Unnamed) file.withRole(ListRole.SHOPPING) else null

    private fun noList(ref: ListRef, file: ListsFile): Reply {
        VoiceLog.add(VoiceEvent.LIST_READ, ListReadCode.NO_LIST.id, 0)
        // A list you joined keeps out of voice on purpose ("add my PIN to bank" must not reach another person), and the reply says so.
        val joined = ref is ListRef.Named && file.lists.any { l -> l.from == ListFrom.PEER && l.names.any { ItemText.same(it, ref.name) } }
        return Reply(if (joined) ListsStrings.peerList("name" to refName(ref)) else ListsStrings.noList("name" to refName(ref)))
    }

    private fun refName(ref: ListRef): Phrase = when (ref) {
        is ListRef.Named -> Phrase.plain(ref.name)
        is ListRef.Role -> if (ref.role == ListRole.TODO) ListsStrings.todo() else ListsStrings.shopping()
        ListRef.Unnamed, ListRef.It -> ListsStrings.shopping()
    }

    private fun focusOn(l: ListsList) {
        focus = Focus(l.id, label(l))
    }

    // ------------------------------------------------------------------ words said back

    private fun item(x: ListItem): Phrase = x.qty?.let { ListsStrings.itemQty("thing" to x.text, "qty" to it) } ?: Phrase.plain(x.text)
    private fun items(xs: List<ListItem>): Phrase = Phrase.ListOf(xs.map(::item))
    private fun said(words: String): Phrase = Phrase.plain(ItemText.item(words).text.ifEmpty { words })

    // ------------------------------------------------------------------ add

    private fun refusal(p: ItemProblem): Phrase = when (p) {
        ItemProblem.SECRET -> ListsStrings.secret()
        ItemProblem.TOO_MANY -> ListsStrings.tooMany("max" to ListsLimits.MAX_ADD)
        ItemProblem.TOO_LONG, ItemProblem.EMPTY -> ListsStrings.tooLong("max" to ListsLimits.MAX_TEXT, "name" to ListsLimits.MAX_NAME)
    }

    private fun refusal(r: ListsRefusal, list: Phrase): Phrase = when (r) {
        ListsRefusal.SECRET -> ListsStrings.secret()
        ListsRefusal.TOO_MANY -> ListsStrings.tooMany("max" to ListsLimits.MAX_ADD)
        ListsRefusal.TOO_LONG, ListsRefusal.EMPTY -> ListsStrings.tooLong("max" to ListsLimits.MAX_TEXT, "name" to ListsLimits.MAX_NAME)
        ListsRefusal.LIST_FULL -> ListsStrings.listFull("list" to list, "max" to ListsLimits.MAX_ITEMS)
        ListsRefusal.LISTS_FULL -> ListsStrings.listsFull("max" to ListsLimits.MAX_LISTS)
        ListsRefusal.FILE_FULL -> ListsStrings.fileFull()
        ListsRefusal.NAME_TAKEN, ListsRefusal.ROLE_TAKEN -> ListsStrings.exists("list" to list)
        ListsRefusal.PEER_NAME -> ListsStrings.peerList("name" to list)
        ListsRefusal.NO_LIST -> ListsStrings.noList("name" to list)
        ListsRefusal.NO_ITEM, ListsRefusal.ALREADY -> ListsStrings.notUndone()
    }

    private fun refused(why: Phrase, count: Int): Reply {
        VoiceLog.add(VoiceEvent.LIST_ADD, ListAddCode.REFUSED.id, count)
        return Reply(why)
    }

    /**
     * The items in [words] (your words): read by code, a run with no separators split by the model's LIST_SPLIT when it can
     * (§3.5, core ListSplit), else kept as one item. Nothing is sent to the model before code read the words whole: a secret,
     * too many items or none are refused first.
     */
    private fun items(e: Env, words: String): ItemsRead {
        val whole = ItemText.read(words)
        if (whole !is ItemsRead.Ok) return whole
        val pieces = ItemText.split(words)
        if (pieces.none(ListSplit::wants)) return whole
        val out = ArrayList<String>()
        for (p in pieces) {
            if (!ListSplit.wants(p)) {
                out += p
                continue
            }
            val answer = try { e.split(p) } catch (t: Exception) { RLog.w("lists_split", t); null }
            val parts = ListSplit.accept(p, answer)
            val code = when {
                answer == null -> ListSplitCode.NO_ANSWER
                parts == null -> ListSplitCode.REFUSED
                parts.size == 1 -> ListSplitCode.ONE
                else -> ListSplitCode.SPLIT
            }
            VoiceLog.add(VoiceEvent.LIST_SPLIT, code.id, parts?.size ?: 1)
            out += parts ?: listOf(p)
        }
        return ItemText.read(out.joinToString(", ")).takeIf { it is ItemsRead.Ok } ?: whole
    }

    private fun add(e: Env, file: ListsFile, cmd: ListsCommand.Add, locked: Boolean, langs: Langs, how: ItemHow): Reply {
        val news = when (val r = items(e, cmd.words)) {
            is ItemsRead.Ok -> r.items
            is ItemsRead.No -> return refused(refusal(r.why), 0)
        }
        val there = findOrShopping(file, cmd.list)
        if (locked) {
            // "Hear and tick on the lock screen" on: into the list itself, never a new one.
            val l = there ?: return lockedNoList(cmd)
            return added(e.vault().lockedEdit(LockedEdit.Add(l.id, news), how), news, made = false, locked = true, langs, label(l))
        }
        // A list your words named that you don't have yet is made (§2.2): a role list again, or a new one by its name.
        val makeRole = if (there == null) (cmd.list as? ListRef.Role)?.role ?: if (cmd.list == ListRef.Unnamed) ListRole.SHOPPING else null else null
        val makeName = if (there == null && makeRole == null) (cmd.list as? ListRef.Named)?.name?.let(ItemText::shown) else null
        val w = e.vault().update(how) { f, who ->
            val target = findOrShopping(f, cmd.list)
            if (target != null) return@update ListsEdits.add(f, target.id, news, who, ids)
            when (val m = ListsEdits.make(f, makeName, makeRole, who, ids)) {
                is ListsEdit.No -> m
                is ListsEdit.Done -> ListsEdits.add(m.file, m.list, news, who, ids)
            }
        }
        val name = there?.let(::label) ?: makeName?.let(Phrase::plain) ?: refName(ListRef.Role(makeRole ?: ListRole.SHOPPING))
        return added(w, news, made = there == null, locked = false, langs, name)
    }

    /** An add's reply from what the vault did. */
    private fun added(w: ListsWrite, news: List<NewItem>, made: Boolean, locked: Boolean, langs: Langs, target: Phrase): Reply {
        val d = w.done ?: return when (val edit = w.edit) {
            is ListsEdit.No -> refused(refusal(edit.why, target), news.size)
            else -> problem(w.load)
        }
        val list = d.file.list(d.list) ?: return Reply(ListsStrings.notNow())
        focusOn(list)
        fun of(o: ItemOutcome) = d.items.filter { it.outcome == o }.mapNotNull { x -> list.item(x.item) }
        val addedItems = of(ItemOutcome.ADDED)
        val already = of(ItemOutcome.ALREADY)
        val back = of(ItemOutcome.PUT_BACK)
        val qty = of(ItemOutcome.QTY)
        val name = label(list)
        val parts = ArrayList<Phrase>()
        when {
            made -> parts += ListsStrings.madeWith("list" to name, "things" to items(addedItems))
            already.isNotEmpty() -> {
                parts += ListsStrings.already("list" to name, "things" to items(already))
                if (addedItems.isNotEmpty()) parts += ListsStrings.addedToo("things" to items(addedItems))
            }
            addedItems.isNotEmpty() -> parts += ListsStrings.added("things" to items(addedItems), "list" to name)
        }
        if (back.isNotEmpty()) parts += ListsStrings.putBack("things" to items(back), "list" to name)
        if (qty.isNotEmpty()) parts += ListsStrings.qtyChanged("list" to name, "things" to items(qty))
        val code = when {
            made -> ListAddCode.NEW_LIST
            addedItems.isNotEmpty() -> ListAddCode.ADDED
            back.isNotEmpty() -> ListAddCode.PUT_BACK
            qty.isNotEmpty() -> ListAddCode.QTY
            else -> ListAddCode.ALREADY
        }
        VoiceLog.add(VoiceEvent.LIST_ADD, code.id, news.size, if (locked) 1 else 0)
        val text = Phrase.Join(parts)
        // A quantity another person's Robot set is said back here: their words, never kept as Robot's.
        val kept = if ((already + back + qty).any { it.by == ItemBy.PEER }) ListsStrings.answered() else null
        if (locked || !d.changed) return Reply(text, kept = kept)
        val shown = addedItems + back + qty
        return Reply(text, card = card(name, list.id, shown, Undo(d.items.map { d.list to it }, made = if (made) list.id else null), langs), kept = kept)
    }

    /** A change's card: the list's name, one line per item, Undo, and (LS5) "Open Shopping". */
    private fun card(name: Phrase, list: ListsId, shown: List<ListItem>, undo: Undo, langs: Langs): UiAction {
        val t = token()
        val open = OPEN + list.hex
        synchronized(this) {
            undos[UNDO + t] = undo
            opens[open] = name
        }
        lastUndo = UNDO + t
        val body = shown.joinToString("\n") { x -> x.qty?.let { q -> ListsStrings.itemQty("thing" to ItemText.shown(x.text), "qty" to q).render(langs) } ?: ItemText.shown(x.text) }
        return UiAction(CARD + t, null, name.render(langs), ActionStatus.DONE, null, body = body.ifEmpty { null }, undo = UNDO + t, handOff = open, lists = true)
    }

    /** While locked, with the lists sealed: the add waits in the journal (§4.6). */
    private fun journal(e: Env, cmd: ListsCommand.Add): Reply {
        val news = when (val r = items(e, cmd.words)) {
            is ItemsRead.Ok -> r.items
            is ItemsRead.No -> return refused(refusal(r.why), 0)
        }
        val v = e.vault()
        val j = when (val l = v.lockedLists()) {
            is ListsLoad.Loaded -> l.value
            ListsLoad.Empty -> return lockedNoList(cmd)
            ListsLoad.Locked -> return lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToChange())
            else -> return Reply(ListsStrings.lockedFailed()).also { VoiceLog.add(VoiceEvent.LIST_LOCKED, ListLockedCode.NOT_NOW.id, 0) }
        }
        val list: JournalList = when (val ref = cmd.list) {
            is ListRef.Role -> ListsJournal.withRole(j, ref.role)
            is ListRef.Named -> ListsJournal.named(j, ref.name)
            ListRef.Unnamed -> focus?.let { f -> j.lists.firstOrNull { it.id == f.id } } ?: ListsJournal.withRole(j, ListRole.SHOPPING)
            ListRef.It -> focus?.let { f -> j.lists.firstOrNull { it.id == f.id } }
        } ?: return lockedNoList(cmd)
        val listSaid: Phrase = if (Regex("(?i)\\b(list|checklist|liste)\\b").containsMatchIn(cmd.said)) Phrase.plain(cmd.said) // l10n: not text (a parser's pattern)
            else ListsStrings.namedList("name" to cmd.said)
        return when (val r = v.addLocked(list.id, news)) {
            is LockedAdd.Ok -> {
                VoiceLog.add(VoiceEvent.LIST_ADD, ListAddCode.JOURNAL.id, news.size, 1)
                focus = Focus(list.id, listSaid)
                Reply(ListsStrings.addedLocked("things" to Phrase.ListOf(news.map { n -> n.qty?.let { q -> ListsStrings.itemQty("thing" to n.text, "qty" to q) } ?: Phrase.plain(n.text) }),
                    "list" to listSaid))
            }
            is LockedAdd.No -> when (r.why) {
                JournalRefusal.NO_LIST -> lockedNoList(cmd)
                JournalRefusal.FULL -> refused(ListsStrings.lockedFull(), news.size)
                JournalRefusal.SECRET -> refused(ListsStrings.secret(), news.size)
                JournalRefusal.TOO_MANY -> refused(ListsStrings.tooMany("max" to ListsLimits.MAX_ADD), news.size)
                JournalRefusal.TOO_LONG, JournalRefusal.EMPTY -> refused(ListsStrings.tooLong("max" to ListsLimits.MAX_TEXT, "name" to ListsLimits.MAX_NAME), news.size)
            }
            LockedAdd.Off -> lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToChange())
            LockedAdd.Failed -> refused(ListsStrings.lockedFailed(), news.size)
        }
    }

    /** "You don't have a list called camping. Unlock your phone to make one." */
    private fun lockedNoList(cmd: ListsCommand.Add): Reply {
        VoiceLog.add(VoiceEvent.LIST_ADD, ListAddCode.NO_LIST.id, 0, 1)
        return Reply(ListsStrings.unlockToMake("name" to refName(cmd.list)))
    }

    // ------------------------------------------------------------------ read, check, my lists

    private fun read(file: ListsFile, cmd: ListsCommand.Read, locked: Boolean, langs: Langs): Reply {
        val l = findOrShopping(file, cmd.list) ?: return noList(cmd.list, file)
        focusOn(l)
        val open = l.open
        val name = label(l)
        VoiceLog.add(VoiceEvent.LIST_READ, ListReadCode.READ.id, open.size)
        val kept = ListsStrings.readKept(open.size, "list" to name)
        val card = if (locked) null else view(l, langs)
        if (open.isEmpty()) return Reply(if (l.done.isNotEmpty()) ListsStrings.allTicked("list" to name) else ListsStrings.empty("list" to name), kept = kept, list = card, clean = !ListsShared.peerText(l))
        val first = open.take(ListsLimits.MAX_READ_ALOUD).map(::item)
        val more = open.size - ListsLimits.MAX_READ_ALOUD
        val shown = Phrase.ListOf(if (more > 0) first + ListsStrings.more(more) else first)
        return Reply(ListsStrings.read(open.size, "list" to name, "things" to shown), kept = kept, list = card, clean = !ListsShared.peerText(l))
    }

    /**
     * LS10 (M1): an answer from your lists that isn't a read is list content too (§8.4): its placeholder ([ListsStrings.answered])
     * makes [forHistory] add ListQuote's record, so a helper gets it only on a tap, the model reads it framed, and History keeps
     * the placeholder. [noList]'s line holds only your words, as a read's does.
     */
    private fun answer(text: Phrase, list: ListCardView? = null): Reply = Reply(text, kept = ListsStrings.answered(), list = list)

    private fun check(file: ListsFile, cmd: ListsCommand.Check, locked: Boolean, langs: Langs): Reply {
        fun card(l: ListsList) = if (locked) null else view(l, langs)
        val piece = cmd.words
        // LS13 (§3.6, LT3 on the Pixel 8): a check that names no list looks at every list, whichever is in focus. With several
        // lists "my list" is any of them unless you name one ("No, Pharmacy doesn't have milk." though Shopping had Milk).
        // fix-list-it: "is milk on it" is the list in focus only; with none in focus any more, every list.
        val l = if (cmd.list == ListRef.Unnamed) null else find(file, cmd.list)
        if (cmd.list != ListRef.Unnamed && cmd.list != ListRef.It && l == null) return noList(cmd.list, file)
        VoiceLog.add(VoiceEvent.LIST_READ, ListReadCode.CHECK.id, 1)
        val lang = cmd.lang
        if (l != null) {
            focusOn(l)
            ListsMatch.find(l.open, piece, lang).firstOrNull()?.let { return answer(ListsStrings.yes("list" to label(l), "thing" to item(it)), card(l)) }
            ListsMatch.find(l.done, piece, lang).firstOrNull()?.let { return answer(ListsStrings.yesTicked("list" to label(l), "thing" to item(it)), card(l)) }
            return answer(ListsStrings.no("list" to label(l), "thing" to said(piece)), card(l))
        }
        val on = yours(file).mapNotNull { x -> ListsMatch.find(x.open, piece, lang).firstOrNull()?.let { x to it } }
        if (on.size == 1) {
            focusOn(on[0].first)
            return answer(ListsStrings.yes("list" to label(on[0].first), "thing" to item(on[0].second)), card(on[0].first))
        }
        // LS12: a yes names what it found ("Yes, Shopping and Pharmacy have milk." only when both found milk itself).
        if (on.size > 1) {
            val first = on[0].second
            if (on.all { (_, x) -> ItemText.same(x.text, first.text) && x.qty == first.qty }) return answer(ListsStrings.yesLists("lists" to Phrase.ListOf(on.map { label(it.first) }), "thing" to item(on[0].second)))
            return answer(ListsStrings.yesEach("things" to Phrase.ListOf(on.map { (x, it) -> ListsStrings.onList("thing" to item(it), "list" to label(x)) })))
        }
        yours(file).firstNotNullOfOrNull { x -> ListsMatch.find(x.done, piece, lang).firstOrNull()?.let { x to it } }?.let { (x, it) ->
            return answer(ListsStrings.yesTicked("list" to label(x), "thing" to item(it)))
        }
        return answer(ListsStrings.noneHas("thing" to said(piece)))
    }

    private fun myLists(file: ListsFile): Reply {
        // LS13 (§3.6): you asked about all your lists, so none is in focus any more: the next turn names its list.
        focus = null
        val ls = yours(file)
        VoiceLog.add(VoiceEvent.LIST_READ, ListReadCode.MY_LISTS.id, ls.size)
        if (ls.isEmpty()) return Reply(ListsStrings.noLists())
        return answer(ListsStrings.myLists(ls.size, "lists" to Phrase.ListOf(ls.map(::label))))
    }

    // ------------------------------------------------------------------ ticks and put backs

    /** What your words named: items on their lists, or why not. */
    private sealed class Found {
        class Items(val on: List<Pair<ListsList, ListItem>>) : Found()
        class Which(val options: List<Phrase>) : Found()
        /** Not on [list] (null: on none of your lists); [other]: but there the other way (ticked for a tick, open for a put back). */
        class Not(val piece: String, val list: ListsList?, val other: ListItem?) : Found()
    }

    /** The items [words] name, [done] or open, on [list] (else the list in focus, else exactly one of your lists). */
    private fun found(file: ListsFile, ref: ListRef, words: String, done: Boolean, lang: Lang): Found {
        val named = find(file, ref)
        val out = ArrayList<Pair<ListsList, ListItem>>()
        for (piece in ItemText.split(words).ifEmpty { listOf(words) }) {
            fun pick(l: ListsList) = ListsMatch.find(if (done) l.done else l.open, piece, lang)
            if (named != null) {
                val m = pick(named)
                if (m.size > 1) return Found.Which(m.map { Phrase.plain(ItemText.shown(it.text)) })
                if (m.size == 1) { out += named to m[0]; continue }
                // "The list" in focus doesn't have it: any one list that does, as the router heard it.
                if (ref != ListRef.Unnamed && ref != ListRef.It) return Found.Not(piece, named, ListsMatch.find(if (done) named.open else named.done, piece, lang).firstOrNull())
            }
            val lists = yours(file).mapNotNull { l -> pick(l).takeIf { it.isNotEmpty() }?.let { l to it } }
            // fix-list-it: "it" is the list in focus and no other. With none in focus, or the item only on other lists, the
            // reply asks which list (never a guess); on none, the list in focus says it hasn't it.
            if (ref == ListRef.It) {
                if (named != null && lists.isEmpty()) return Found.Not(piece, named, ListsMatch.find(if (done) named.open else named.done, piece, lang).firstOrNull())
                val options = lists.map { it.first }.ifEmpty { yours(file) }
                if (options.isEmpty()) return Found.Not(piece, null, null)
                return Found.Which(options.map(::label))
            }
            when {
                lists.isEmpty() -> return Found.Not(piece, named, yours(file).firstNotNullOfOrNull { l -> ListsMatch.find(if (done) l.open else l.done, piece, lang).firstOrNull() })
                lists.size > 1 -> return Found.Which(lists.map { label(it.first) })
                lists[0].second.size > 1 -> return Found.Which(lists[0].second.map { Phrase.plain(ItemText.shown(it.text)) })
                else -> out += lists[0].first to lists[0].second[0]
            }
        }
        return Found.Items(out.distinctBy { it.second.id })
    }

    private fun tick(e: Env, file: ListsFile, cmd: ListsCommand.Tick, locked: Boolean, langs: Langs, how: ItemHow): Reply {
        if (cmd.list !is ListRef.Unnamed && cmd.list !is ListRef.It && find(file, cmd.list) == null) return noList(cmd.list, file)
        return when (val f = found(file, cmd.list, cmd.words, done = false, cmd.lang)) {
            is Found.Which -> which(f)
            is Found.Not -> {
                if (f.other != null) {
                    VoiceLog.add(VoiceEvent.LIST_TICK, ListTickCode.ALREADY.id, 1)
                    return answer(ListsStrings.alreadyTicked("things" to item(f.other)))
                }
                notFound(f)
            }
            is Found.Items -> {
                val w = change(e, locked, how, f.on.map { (l, x) -> LockedEdit.Tick(l.id, x.id) }) { file0, who ->
                    f.on.fold<Pair<ListsList, ListItem>, ListsEdit>(ListsEdit.Done(file0, f.on[0].first.id, changed = false)) { acc, (l, x) ->
                        chain(acc, l.id) { ListsEdits.tick(it, l.id, x.id, who) }
                    }
                }
                changed(w, f, ListsStrings.ticked("things" to items(f.on.map { it.second })), ListTickCode.TICKED, locked, langs, yourWords(f, cmd.words))
            }
        }
    }

    private fun putBack(e: Env, file: ListsFile, cmd: ListsCommand.PutBack, locked: Boolean, langs: Langs, how: ItemHow): Reply {
        if (cmd.list !is ListRef.Unnamed && cmd.list !is ListRef.It && find(file, cmd.list) == null) return noList(cmd.list, file)
        return when (val f = found(file, cmd.list, cmd.words, done = true, cmd.lang)) {
            is Found.Which -> which(f)
            is Found.Not -> {
                if (f.other != null) {
                    VoiceLog.add(VoiceEvent.LIST_TICK, ListTickCode.ALREADY.id, 1)
                    return answer(ListsStrings.alreadyOpen("list" to (f.list?.let(::label) ?: file.holding(f.other.id)?.let(::label) ?: ListsStrings.page()), "things" to item(f.other)))
                }
                // Not there at all: "put eggs back on the list" adds them (while locked, only with "Add to lists while locked" on).
                if (locked && !e.settings().addLocked) return lockedLine(ListLockedCode.UNLOCK_TO_CHANGE, ListsStrings.unlockToChange())
                add(e, file, ListsCommand.Add(cmd.words, cmd.list, "", cmd.lang), locked, langs, how)
            }
            is Found.Items -> {
                val w = change(e, locked, how, f.on.map { (l, x) -> LockedEdit.Untick(l.id, x.id) }) { file0, who ->
                    f.on.fold<Pair<ListsList, ListItem>, ListsEdit>(ListsEdit.Done(file0, f.on[0].first.id, changed = false)) { acc, (l, x) ->
                        chain(acc, l.id) { ListsEdits.untick(it, l.id, x.id, who) }
                    }
                }
                val name = label(f.on[0].first)
                changed(w, f, ListsStrings.putBack("things" to items(f.on.map { it.second }), "list" to name), ListTickCode.PUT_BACK, locked, langs, yourWords(f, cmd.words))
            }
        }
    }

    private fun which(f: Found.Which): Reply {
        VoiceLog.add(VoiceEvent.LIST_TICK, ListTickCode.WHICH.id, f.options.size)
        return answer(ListsStrings.which("options" to Phrase.ListOf(f.options, ListKind.OR)))
    }

    /** LS10 (M1): every item [f] names is a piece of [words] as you said it (ItemText.same), so its reply says only your words. */
    private fun yourWords(f: Found.Items, words: String): Boolean {
        val pieces = ItemText.split(words).ifEmpty { listOf(words) }.map { ItemText.item(it).text }
        // A peer's quantity is said back too, and it isn't your words.
        return f.on.all { (_, x) -> pieces.any { ItemText.same(it, x.text) } && !(x.by == ItemBy.PEER && x.qty != null) }
    }

    private fun notFound(f: Found.Not): Reply {
        VoiceLog.add(VoiceEvent.LIST_TICK, ListTickCode.NOT_FOUND.id, 1)
        return Reply(f.list?.let { ListsStrings.notOn("list" to label(it), "things" to said(f.piece)) } ?: ListsStrings.notOnAny("things" to said(f.piece)))
    }

    /**
     * One change: unlocked through the vault's update ([edit], all in one write); locked (with "Hear and tick on the lock
     * screen" on) each of [lockedEdits], the only changes a locked phone makes.
     */
    private fun change(e: Env, locked: Boolean, how: ItemHow, lockedEdits: List<LockedEdit>, edit: (ListsFile, ListsWho) -> ListsEdit): ListsWrite {
        if (!locked) return e.vault().update(how, change = edit)
        val done = ArrayList<ItemDone>()
        var last: ListsWrite? = null
        for (x in lockedEdits) {
            val w = e.vault().lockedEdit(x, how)
            last = w
            val d = w.done ?: return w
            done += d.items
        }
        val d = last?.done ?: return last ?: ListsWrite(ListsLoad.Locked)
        return ListsWrite(last.load, d.copy(items = done))
    }

    /** [acc] followed by [next] on its file: a refusal stops it; a change that changes nothing keeps the file. */
    private fun chain(acc: ListsEdit, list: ListsId, next: (ListsFile) -> ListsEdit): ListsEdit {
        val a = acc as? ListsEdit.Done ?: return acc
        return when (val n = next(a.file)) {
            is ListsEdit.No -> n
            is ListsEdit.Done -> ListsEdit.Done(n.file, list, a.items + n.items, a.changed || n.changed)
        }
    }

    /** A tick's or a put back's reply from what the vault did; list content (M1) unless it names [own] words only. */
    private fun changed(w: ListsWrite, f: Found.Items, text: Phrase, code: ListTickCode, locked: Boolean, langs: Langs, own: Boolean): Reply {
        val d = w.done ?: return when (val x = w.edit) {
            is ListsEdit.No -> Reply(refusal(x.why, label(f.on[0].first)))
            else -> problem(w.load)
        }
        val lists = f.on.map { it.first }.distinctBy { it.id }
        d.file.list(lists[0].id)?.let(::focusOn)
        VoiceLog.add(VoiceEvent.LIST_TICK, code.id, f.on.size)
        val kept = if (own) null else ListsStrings.answered()
        if (locked || !d.changed) return Reply(text, kept = kept)
        val byItem = f.on.associate { it.second.id to it.first.id }
        val now = f.on.mapNotNull { (l, x) -> d.file.list(l.id)?.item(x.id) }
        return Reply(text, card = card(label(lists[0]), lists[0].id, now, Undo(d.items.mapNotNull { x -> byItem[x.item]?.let { it to x } }), langs), kept = kept)
    }

    // ------------------------------------------------------------------ make, clear, empty

    private fun make(e: Env, file: ListsFile, cmd: ListsCommand.Make, langs: Langs, how: ItemHow): Reply {
        val existing = cmd.role?.let { file.withRole(it) } ?: cmd.name?.let { n -> find(file, ListRef.Named(n)) }
        if (existing != null) {
            focusOn(existing)
            VoiceLog.add(VoiceEvent.LIST_MAKE, ListMakeCode.EXISTS.id, 0)
            return Reply(ListsStrings.exists("list" to label(existing)))
        }
        val name = cmd.name?.let(ItemText::shown)
        val w = e.vault().update(how) { f, who -> ListsEdits.make(f, name, cmd.role, who, ids) }
        val d = w.done ?: return when (val x = w.edit) {
            is ListsEdit.No -> Reply(refusal(x.why, name?.let(Phrase::plain) ?: refName(ListRef.Role(cmd.role ?: ListRole.SHOPPING)))).also {
                VoiceLog.add(VoiceEvent.LIST_MAKE, ListMakeCode.REFUSED.id, 0)
            }
            else -> problem(w.load)
        }
        val l = d.file.list(d.list) ?: return Reply(ListsStrings.notNow())
        focusOn(l)
        VoiceLog.add(VoiceEvent.LIST_MAKE, ListMakeCode.MADE.id, 0)
        return Reply(ListsStrings.made("list" to label(l)), card = card(label(l), l.id, emptyList(), Undo(emptyList(), made = l.id), langs))
    }

    private fun clear(e: Env, file: ListsFile, cmd: ListsCommand.ClearDone, langs: Langs, how: ItemHow): Reply {
        val l = find(file, cmd.list) ?: return noList(cmd.list, file)
        focusOn(l)
        if (l.done.isEmpty()) return Reply(ListsStrings.nothingTicked("list" to label(l)))
        val w = e.vault().update(how) { f, who -> ListsEdits.clearDone(f, l.id, who) }
        val d = w.done ?: return when (val x = w.edit) {
            is ListsEdit.No -> Reply(refusal(x.why, label(l)))
            else -> problem(w.load)
        }
        val gone = d.items.count { it.outcome == ItemOutcome.REMOVED }
        val left = d.file.list(l.id)?.open?.size ?: 0
        VoiceLog.add(VoiceEvent.LIST_EDIT, ListEditCode.CLEARED.id, gone)
        // LS8: with nothing left open, the count alone ("0 are still on it", « Il en reste 0 » said nothing).
        val text = if (left == 0) ListsStrings.cleared(gone, "list" to label(l)) else Phrase.Join(listOf(ListsStrings.cleared(gone, "list" to label(l)), ListsStrings.stillOn(left)))
        return Reply(text, card = card(label(l), l.id, emptyList(), Undo(d.items.map { l.id to it }), langs))
    }

    /** "Empty the shopping list": a card that only a tap confirms (AskWhy.DELETE: a spoken yes never empties). */
    private fun empty(file: ListsFile, cmd: ListsCommand.Empty, langs: Langs): Reply {
        val l = find(file, cmd.list) ?: return noList(cmd.list, file)
        focusOn(l)
        val n = l.live.size
        if (n == 0) return Reply(ListsStrings.alreadyEmpty("list" to label(l)))
        val t = token()
        synchronized(this) { empties[t] = l.id }
        VoiceLog.add(VoiceEvent.LIST_EDIT, ListEditCode.EMPTY_CARD.id, n)
        val card = UiAction(EMPTY + t, null, label(l).render(langs), ActionStatus.PENDING, ListsStrings.emptyCard(n, "list" to label(l)).render(langs),
            confirm = ListsStrings.emptyButton().render(langs), cancel = ListsStrings.keepButton().render(langs), tapFor = app.heyrobot.core.AskWhy.DELETE, lists = true)
        return Reply(ListsStrings.emptyAsk("list" to label(l)), card = card)
    }

    /** An Empty card's tap: its new status, its line, and the Undo key once it emptied the list. */
    data class Tapped(val status: ActionStatus, val line: String, val undo: String? = null)

    /** Empty or Keep tapped on an Empty card (a background thread; PanelController.confirm, the card's buttons only). */
    fun emptyTapped(ctx: Context, actionId: String, yes: Boolean, langs: Langs, spoken: Boolean = false): Tapped {
        val e = env(ctx)
        val t = actionId.removePrefix(EMPTY)
        if (e.someoneElse()) return Tapped(ActionStatus.PENDING, ListsStrings.someoneElse().render(langs))
        if (e.locked()) return Tapped(ActionStatus.PENDING, ListsStrings.unlockToChange().render(langs))
        val id = synchronized(this) { empties.remove(t) } ?: return Tapped(ActionStatus.DECLINED, ListsStrings.notUndone().render(langs))
        val how = if (spoken) ItemHow.VOICE else ItemHow.CARD
        if (!yes) {
            val name = (e.vault().load(firstUse = false) as? ListsLoad.Loaded)?.value?.list(id)?.let(::label) ?: ListsStrings.page()
            VoiceLog.add(VoiceEvent.LIST_EDIT, ListEditCode.KEPT.id, 0)
            return Tapped(ActionStatus.DECLINED, ListsStrings.kept("list" to name).render(langs))
        }
        val w = e.vault().update(how) { f, who -> ListsEdits.empty(f, id, who) }
        val d = w.done ?: return Tapped(ActionStatus.FAILED, (if (w.edit is ListsEdit.No) ListsStrings.notUndone() else ListsStrings.notNow()).render(langs))
        val l = d.file.list(id)
        val emptied = d.items.size
        VoiceLog.add(VoiceEvent.LIST_EDIT, ListEditCode.EMPTIED.id, emptied)
        val key = UNDO + token()
        synchronized(this) { undos[key] = Undo(d.items.map { id to it }) }
        lastUndo = key
        refresh(ctx)
        LinkNotices.kick() // A shared list's change goes out now, not at the next event.
        return Tapped(ActionStatus.DONE, ListsStrings.emptied("list" to (l?.let(::label) ?: ListsStrings.page())).render(langs), key)
    }

    // ------------------------------------------------------------------ the model's add_to_list (LS4, §8.2, D6)

    /**
     * add_to_list before the gate (a background thread): refused with Lists off, while locked or during a phone lock (§5: the
     * model never changes your lists there); the items read by code (core ItemText: caps, quantities, secrets refused) and the
     * list found (core ListsRouter.named: Shopping when none is named). A card answered by a tap (AskWhy.LIST_SUGGESTED) unless
     * every item is a run of your own words this turn ([said]), the conversation holds no content ([untrusted]) and the list is
     * one you have, whatever Safety cards says.
     */
    fun prepareModel(ctx: Context, a: Action.AddToList, untrusted: Boolean, said: String?): Prepared = try {
        val e = env(ctx)
        when {
            !e.settings().on -> Prepared.Refused(ActionStatus.DISABLED, app.heyrobot.core.i18n.TurnStrings.turnedOff("what" to a.tool.titleText))
            e.someoneElse() -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, ListsStrings.someoneElse())
            e.locked() -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, ListsStrings.unlockToChange())
            else -> when (val r = ItemText.read(a.items)) {
                is ItemsRead.No -> Prepared.Refused(ActionStatus.INVALID, refusal(r.why))
                is ItemsRead.Ok -> when (val l = e.vault().load(firstUse = true)) {
                    is ListsLoad.Loaded, ListsLoad.Empty -> {
                        val file = (l as? ListsLoad.Loaded)?.value ?: ListsFile()
                        val ref = ListsRouter.named(a.list)
                        val there = findOrShopping(file, ref)
                        // 1.5: never without your tap onto a shared list: what lands there goes to another person.
                        val yours = ListSplit.yours(r.items, said) && ListsShared.modelNeedsNoCard(there, untrusted, true)
                        Prepared.Ready(a.copy(news = r.items, listName = there?.let(::label) ?: newName(ref), made = there == null, content = untrusted),
                            why = if (yours) emptySet() else setOf(AskWhy.LIST_SUGGESTED))
                    }
                    else -> Prepared.Refused(ActionStatus.FAILED, problemText(l))
                }
            }
        }
    } catch (t: Exception) {
        RLog.w("lists_model", t)
        Prepared.Refused(ActionStatus.FAILED, ListsStrings.notNow())
    }

    /** A list add_to_list would make: its name as the card shows it. */
    private fun newName(ref: ListRef): Phrase = (ref as? ListRef.Named)?.name?.let { Phrase.plain(ItemText.shown(it)) } ?: refName(ref)

    /**
     * add_to_list after the gate and its card (a background thread): the items [prepareModel] read, added by code through the
     * vault (to a new list only when its card said so), `by: content` when prepared in a conversation with content
     * ([Action.AddToList.content]), `how: model`. The card's line and what the model is told are counts, never an item (the model never gets a list); Undo as
     * on a list card, and the list is in focus.
     */
    fun runModel(ctx: Context, a: Action.AddToList): Executed = try {
        val e = env(ctx)
        if (e.someoneElse()) Executed(ActionStatus.NEEDS_UNLOCK, ListsStrings.someoneElse())
        else if (e.locked() || !e.settings().on) Executed(ActionStatus.NEEDS_UNLOCK, ListsStrings.unlockToChange())
        else modelAdd(e, a, if (a.content) ItemBy.CONTENT else ItemBy.YOU).also { refresh(ctx); LinkNotices.kick() }
    } catch (t: Exception) {
        RLog.w("lists_model", t)
        Executed(ActionStatus.FAILED, ListsStrings.notNow())
    }

    private fun modelAdd(e: Env, a: Action.AddToList, by: ItemBy): Executed {
        val news = a.news.ifEmpty { (ItemText.read(a.items) as? ItemsRead.Ok)?.items.orEmpty() }
        if (news.isEmpty()) return Executed(ActionStatus.INVALID, ListsStrings.tooLong("max" to ListsLimits.MAX_TEXT, "name" to ListsLimits.MAX_NAME))
        val ref = ListsRouter.named(a.list)
        val makeRole = (ref as? ListRef.Role)?.role ?: if (ref == ListRef.Unnamed) ListRole.SHOPPING else null
        val makeName = if (makeRole == null) (ref as? ListRef.Named)?.name?.let(ItemText::shown) else null
        var made = false
        val w = e.vault().update(ItemHow.MODEL, by) { f, who ->
            val target = findOrShopping(f, ref)
            when {
                target != null -> ListsEdits.add(f, target.id, news, who, ids)
                // A list only its card said it would make (the card always shows then).
                !a.made -> ListsEdit.No(ListsRefusal.NO_LIST)
                else -> when (val m = ListsEdits.make(f, makeName, makeRole, who, ids)) {
                    is ListsEdit.No -> m
                    is ListsEdit.Done -> ListsEdits.add(m.file, m.list, news, who, ids).also { made = it is ListsEdit.Done }
                }
            }
        }
        val name = a.listName ?: newName(ref)
        val d = w.done ?: return (w.edit as? ListsEdit.No)?.let {
            VoiceLog.add(VoiceEvent.LIST_ADD, ListAddCode.REFUSED.id, news.size, 2)
            Executed(ActionStatus.FAILED, refusal(it.why, name))
        } ?: Executed(ActionStatus.FAILED, problemText(w.load))
        val list = d.file.list(d.list) ?: return Executed(ActionStatus.FAILED, ListsStrings.notNow())
        focusOn(list)
        val n = ItemOutcome.entries.associateWith { o -> d.items.count { it.outcome == o } }
        val addedN = n.getValue(ItemOutcome.ADDED)
        val code = when {
            made -> ListAddCode.NEW_LIST
            addedN > 0 -> ListAddCode.ADDED
            n.getValue(ItemOutcome.PUT_BACK) > 0 -> ListAddCode.PUT_BACK
            n.getValue(ItemOutcome.QTY) > 0 -> ListAddCode.QTY
            else -> ListAddCode.ALREADY
        }
        VoiceLog.add(VoiceEvent.LIST_ADD, code.id, news.size, 2)
        val label = label(list)
        val changed = addedN + n.getValue(ItemOutcome.PUT_BACK) + n.getValue(ItemOutcome.QTY)
        val already = n.getValue(ItemOutcome.ALREADY)
        val text = Phrase.Join(listOfNotNull(ListsStrings.modelAdded(changed, "list" to label).takeIf { changed > 0 || already == 0 },
            already.takeIf { it > 0 }?.let { ListsStrings.modelAlready(it, "list" to label) }))
        // What the model is told: counts, never an item or what else is on the list.
        val forModel = "added $addedN" + listOf("put back" to ItemOutcome.PUT_BACK, "quantity changed" to ItemOutcome.QTY, // l10n: not text (the model's tool result)
            "already there" to ItemOutcome.ALREADY).filter { n.getValue(it.second) > 0 }.joinToString("") { ", ${it.first} ${n.getValue(it.second)}" } // l10n: not text (the model's tool result)
        var undo: String? = null
        if (d.changed) {
            val key = UNDO + token()
            synchronized(this) { undos[key] = Undo(d.items.map { d.list to it }, made = if (made) list.id else null) }
            lastUndo = key
            undo = key
        }
        return Executed(ActionStatus.DONE, text, body = a.copy(news = news).bodyText(), undo = undo, forModel = forModel)
    }

    // ------------------------------------------------------------------ the card with ticks (LS5, §7.4)

    /** [l] as a read's or a check's card: its name, open items with tick boxes, its done ones; [line], what a tap did. */
    private fun view(l: ListsList, langs: Langs, line: Phrase? = null, undo: Boolean = false): ListCardView {
        fun row(x: ListItem) = ListCardRow(x.id.hex, ItemText.shown(x.text), x.qty)
        return ListCardView(l.id.hex, label(l).render(langs), l.open.map(::row), l.done.map(::row), line?.render(langs), undo, label = label(l))
    }

    /**
     * A tick, a put back or Undo on list card [v] under reply [replyId] (a background thread): through the vault as a tap on a
     * card (ItemHow.CARD), refused during a phone lock and while locked (§5: the card is never shown then). The card as it
     * now is, with what the tap did; a tick's Undo is the card's own and "undo that"'s.
     */
    fun cardTap(ctx: Context, replyId: String, v: ListCardView, op: ListCardOp, langs: Langs): ListCardView {
        val e = env(ctx)
        val list = ListsId.of(v.list) ?: return v
        if (e.someoneElse()) return v.copy(line = ListsStrings.someoneElse().render(langs), undo = false)
        if (e.locked()) return v.copy(line = ListsStrings.unlockToChange().render(langs), undo = false)
        val r = try {
            when (op) {
                is ListCardOp.Tick -> cardTick(e, replyId, list, op, langs)
                ListCardOp.Undo -> cardUndo(e, replyId, list, langs)
                ListCardOp.Open -> v
            }
        } catch (t: Exception) {
            RLog.w("lists_card", t)
            null
        }
        refresh(ctx)
        LinkNotices.kick()
        return r ?: v.copy(line = ListsStrings.notNow().render(langs), undo = false)
    }

    private fun cardTick(e: Env, replyId: String, list: ListsId, op: ListCardOp.Tick, langs: Langs): ListCardView? {
        val item = ListsId.of(op.item) ?: return null
        val w = e.vault().lockedEdit(if (op.done) LockedEdit.Tick(list, item) else LockedEdit.Untick(list, item), ItemHow.CARD)
        val d = w.done ?: return (w.load as? ListsLoad.Loaded)?.value?.list(list)?.let { l ->
            view(l, langs, (w.edit as? ListsEdit.No)?.let { refusal(it.why, label(l)) } ?: problemText(w.load))
        }
        val l = d.file.list(list) ?: return null
        val x = l.item(item) ?: return null
        focusOn(l)
        VoiceLog.add(VoiceEvent.LIST_TICK, (if (op.done) ListTickCode.TICKED else ListTickCode.PUT_BACK).id, 1)
        if (d.changed) {
            val key = UNDO + token()
            synchronized(this) {
                undos[key] = Undo(d.items.map { list to it })
                cardUndos[replyId] = key
            }
            lastUndo = key
        }
        val line = if (op.done) ListsStrings.ticked("things" to item(x)) else ListsStrings.putBack("things" to item(x), "list" to label(l))
        return view(l, langs, line, undo = d.changed)
    }

    private fun cardUndo(e: Env, replyId: String, list: ListsId, langs: Langs): ListCardView? {
        val key = synchronized(this) { cardUndos.remove(replyId) } ?: return null
        val line = undo(e, key, ItemHow.CARD)
        val l = (e.vault().load(firstUse = false) as? ListsLoad.Loaded)?.value?.list(list) ?: return null
        return view(l, langs, line)
    }

    /** Whether [handOff] is a card's "Open Shopping". */
    fun isOpen(handOff: String?): Boolean = handOff?.startsWith(OPEN) == true

    /** "Open Shopping" for a card's [handOff]; null for any other hand-off. */
    fun label(handOff: String?): Phrase? {
        if (!isOpen(handOff)) return null
        return ListsStrings.openList("list" to (synchronized(this) { opens[handOff] } ?: ListsStrings.page()))
    }

    /** The list [handOff] opens: its id, or null. */
    fun openedList(handOff: String): String? = handOff.removePrefix(OPEN).takeIf { isOpen(handOff) && ListsId.of(it) != null }

    /** The app at the list [id]'s page (AppPages.LISTS): an id only. */
    fun openIntent(ctx: Context, id: String): android.content.Intent = android.content.Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
        .putExtra(app.heyrobot.sys.AppPages.EXTRA, app.heyrobot.sys.AppPages.listRoute(id))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ Undo

    /** "Undo that": the last change of this conversation, the card's Undo said aloud. */
    private fun undoLast(e: Env, how: ItemHow): Reply {
        val key = lastUndo ?: return Reply(app.heyrobot.core.i18n.PanelStrings.nothingToUndo())
        val line = undo(e, key, how)
        return Reply(line, undid = key)
    }

    /**
     * Undo on a list card (a background thread): the change put back as it was. The card's new line: none when it was, since
     * the card already says "Undone" (LS12: it read "Undone. Undone."); else why not.
     */
    fun undo(ctx: Context, key: String, langs: Langs): String? {
        val line = undo(env(ctx), key, ItemHow.CARD).also { refresh(ctx); LinkNotices.kick() }
        return if (line == ListsStrings.undone()) null else line.render(langs)
    }

    /** lists-undo-fix: the Undo under [key] can still be tried (one that failed for now, locked, keeps its button). */
    fun canUndo(key: String): Boolean = synchronized(this) { key in undos }

    private fun undo(e: Env, key: String, how: ItemHow): Phrase {
        if (e.someoneElse()) return ListsStrings.someoneElse()
        if (e.locked()) return ListsStrings.unlockToUndo()
        val u = synchronized(this) { undos.remove(key) } ?: return app.heyrobot.core.i18n.PanelStrings.nothingToUndo()
        if (lastUndo == key) lastUndo = null
        val w = e.vault().update(how) { f, who -> reverse(f, u, who) }
        // lists-undo-fix: the lists' key locked since (a race with the lock): nothing written, the Undo kept to try again.
        if (w.load == ListsLoad.Locked) {
            synchronized(this) { undos[key] = u }
            return ListsStrings.unlockToUndo()
        }
        val ok = w.done?.changed == true
        val changes = u.items.size
        VoiceLog.add(VoiceEvent.LIST_EDIT, (if (ok) ListEditCode.UNDONE else ListEditCode.NOT_UNDONE).id, changes)
        return if (ok) ListsStrings.undone() else ListsStrings.notUndone()
    }

    /** [u]'s changes put back on [f], each that still can be: an add removed, a tick unticked, a quantity as it was, a list made deleted while empty. */
    private fun reverse(f: ListsFile, u: Undo, who: ListsWho): ListsEdit {
        var file = f
        var any = false
        fun apply(e: ListsEdit?) {
            if (e is ListsEdit.Done && e.changed) {
                file = e.file
                any = true
            }
        }
        for ((list, d) in u.items.reversed()) {
            val x = file.list(list)?.item(d.item) ?: continue
            when (d.outcome) {
                ItemOutcome.ADDED, ItemOutcome.MOVED -> if (x.live) apply(ListsEdits.remove(file, list, listOf(d.item), who))
                ItemOutcome.QTY -> if (x.live) apply(ListsEdits.quantity(file, list, d.item, d.was, who))
                ItemOutcome.PUT_BACK -> if (x.live) {
                    apply(ListsEdits.tick(file, list, d.item, who))
                    if (file.list(list)?.item(d.item)?.qty != d.was) apply(ListsEdits.quantity(file, list, d.item, d.was, who))
                }
                ItemOutcome.TICKED -> if (x.live) apply(ListsEdits.untick(file, list, d.item, who))
                ItemOutcome.UNTICKED -> if (x.live) apply(ListsEdits.tick(file, list, d.item, who))
                ItemOutcome.REMOVED -> if (!x.live) apply(ListsEdits.restore(file, list, listOf(d.item), who))
                else -> {}
            }
        }
        u.made?.let { id -> file.list(id)?.takeIf { it.live.isEmpty() }?.let { apply(ListsEdits.deleteList(file, id)) } }
        val list = u.items.firstOrNull()?.first ?: u.made ?: f.lists.firstOrNull()?.id ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        return ListsEdit.Done(file, list, changed = any)
    }
}
