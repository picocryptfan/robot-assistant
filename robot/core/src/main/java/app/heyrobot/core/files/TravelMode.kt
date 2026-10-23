// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

// Pure. 1.4, Robot Files (files-1.4-plan.md §1's "panic or travel mode", §6.6; job F12): hidden drawers. In Settings › Files
// you mark drawers hidden; while travel mode is on, those drawers and their files appear nowhere: the Files screens and
// their counts and totals, search, find_files and read_file, the panel's answers, the brief and its reminders, the tax
// pack, Settings' counts. Each of those reads the index through [shown], a view without them.
//
// Only the phone's own PIN, pattern or password (KeyguardManager's confirm screen) shows them again: in the Files screens
// for a while (the main app's TravelReveal, [revealed] here), or for good by turning travel mode off. Robot's voice, the
// panel and the brief (:voice) never reveal them while travel mode is on.
//
// A privacy screen, not encryption: the files stay sealed as they always are, under the same key; someone who has your PIN,
// or the phone's storage and a way into it, isn't stopped by this. Filing goes on (a file can land in a hidden drawer and
// not show), and a backup made while travel mode is on leaves Robot Files out (it would carry the hidden files).

//
// FX1: when files_settings.json can't say which drawers are hidden ([FilesSettings.hiddenUnknown], files-1.4-review.md
// finding 5), every drawer counts as hidden while travel mode is on: [active], [hidesAny], [hidden] and [shown] all say so.
// [hiding] names only the drawers it knows, so it is for the settings page; everything that decides what to show asks
// [hidden] or [shown].

object TravelMode {
    /** Travel mode is on and hides something (backups and restores then leave Robot Files out). */
    fun active(s: FilesSettings): Boolean = s.travel && (s.hidden.isNotEmpty() || s.hiddenUnknown)

    /** FX1: something is out of sight now: travel mode [active] and not [revealed] (History's placeholders, Settings' rows). */
    fun hidesAny(s: FilesSettings, revealed: Boolean = false): Boolean = active(s) && !revealed

    /**
     * The drawers known to be out of sight now: the hidden ones while travel mode is on and not [revealed], else none. With
     * the hidden drawers unknown it can't name them all: ask [hidden] or [shown], which can.
     */
    fun hiding(s: FilesSettings, revealed: Boolean = false): Set<String> = if (s.travel && !revealed) s.hidden else emptySet()

    /** Whether [e] is out of sight among [out]: filed (or kept) in a hidden drawer. */
    fun hides(e: FileEntry, out: Set<String>): Boolean = e.drawer != null && e.drawer in out

    /** FX1: whether [e] is out of sight now ([hidden] for its drawer). */
    fun hides(e: FileEntry, s: FilesSettings, revealed: Boolean = false): Boolean = hidden(e.drawer, s, revealed)

    /**
     * [idx] as travel mode shows it: without the hidden drawers' files, their names gone from your drawers' list and from
     * any suggestion that names one, and [FileIndex.outOfSight] set so the screens and the router leave those drawers out
     * too. The same [idx] when nothing is hidden now. Never written back (FileStore refuses a view).
     */
    fun shown(idx: FileIndex, s: FilesSettings, revealed: Boolean = false): FileIndex {
        // FX1: the hidden drawers unknown, every drawer the index names is out of sight (a starter one, yours, or a file's).
        val out = if (s.hiddenUnknown && hidesAny(s, revealed)) Drawers.STARTER.toSet() + FileViews.yours(idx) + hiding(s, revealed) else hiding(s, revealed)
        if (out.isEmpty()) return idx
        val entries = idx.entries.filter { !hides(it, out) }.map { e ->
            if (e.filing?.suggest != null && e.filing.suggest in out) e.copy(filing = e.filing.copy(suggest = null)) else e
        }
        val listed = idx.extra[FileViews.DRAWERS_KEY] as? List<*>
        val extra = if (listed == null) idx.extra else idx.extra + (FileViews.DRAWERS_KEY to listed.filter { it !in out })
        return FileIndex(entries, extra, idx.outOfSight + out)
    }

    /** Whether [drawer] is out of sight now (FX1: any drawer, with the hidden ones unknown). */
    fun hidden(drawer: String?, s: FilesSettings, revealed: Boolean = false): Boolean =
        drawer != null && (drawer in hiding(s, revealed) || (s.hiddenUnknown && hidesAny(s, revealed)))

    /**
     * FX1: [s] as a save without your PIN may write it, over [now] (the file as it is then): while travel mode is on there,
     * it stays on and every drawer hidden there stays hidden (a page's older copy, or one written before a restore's finish,
     * never turns it off). Turning it off, or unhiding a drawer while it is on, goes through your PIN (a confirmed save).
     */
    fun kept(now: FilesSettings, s: FilesSettings): FilesSettings {
        if (!now.travel) return s
        val hidden = LinkedHashSet(now.hidden).apply { for (h in s.hidden) if (size < FilesSettings.MAX_HIDDEN) add(h) }
        return s.copy(travel = true, hidden = hidden, hiddenUnknown = s.hiddenUnknown || now.hiddenUnknown)
    }

    /** [s] with [drawer] hidden (always allowed: hiding needs no PIN), or null when it is already or there's no room. */
    fun hide(s: FilesSettings, drawer: String): FilesSettings? {
        if (drawer in s.hidden || s.hidden.size >= FilesSettings.MAX_HIDDEN || !FileIndexCodec.label(drawer)) return null
        return s.copy(hidden = s.hidden + drawer)
    }

    /** [s] after your drawer [old] was renamed [new]: hidden under its new name, in the old one's place (FilesSettingsWrite.rename). */
    fun renamed(s: FilesSettings, old: String, new: String): FilesSettings =
        if (old !in s.hidden || !FileIndexCodec.label(new)) s else s.copy(hidden = s.hidden - old + new)

    /**
     * FX1 (files-1.4-review.md finding 3): whether [t] is Robot Files' answer: a reply carrying find_files or read_file, or
     * one Files' router gave ([ChatTurn.files]). Replies the router gave before FX1 carry no mark: [holdsFiles] finds them
     * by their question.
     */
    fun fromFiles(t: app.heyrobot.core.ChatTurn): Boolean =
        t.role == app.heyrobot.core.TurnRole.ASSISTANT && (t.files || t.actions.any { it.tool in FILE_TOOLS })

    private val FILE_TOOLS = setOf(app.heyrobot.core.ToolName.FIND_FILES, app.heyrobot.core.ToolName.READ_FILE)

    /**
     * FX3: whether [turns] hold Robot Files' answer: a reply [fromFiles], or a question of yours Files' router takes
     * (FilesRouter.route, as if a file was just filed), which finds the router's replies saved before FX1 marked them, and
     * your questions that name a drawer or a file.
     */
    fun holdsFiles(turns: List<app.heyrobot.core.ChatTurn>): Boolean =
        turns.any(::fromFiles) || turns.any { it.role == app.heyrobot.core.TurnRole.USER && asksFiles(it.text) }

    private fun asksFiles(text: String): Boolean = try { FilesRouter.route(text, recent = true) != null } catch (_: Exception) { false }

    /**
     * FX1, FX3: [c] as History shows it while travel mode hides drawers. A conversation holding Robot Files' answer
     * ([holdsFiles]) shows whole as placeholders: each reply as [answer], each of your turns as [question] (a question can
     * name a hidden drawer or file, and the title is the first question), with no cards, sources, pictures or screen text.
     * Only shown: the stored turns stay as they are, and show again once travel mode is off.
     */
    fun history(c: app.heyrobot.core.StoredConversation, answer: String, question: String): app.heyrobot.core.StoredConversation =
        if (!holdsFiles(c.turns)) c else c.copy(turns = c.turns.map { t -> masked(t, if (t.role == app.heyrobot.core.TurnRole.USER) question else answer) })

    /** [t] as [history] shows it. */
    fun masked(t: app.heyrobot.core.ChatTurn, placeholder: String): app.heyrobot.core.ChatTurn =
        t.copy(text = placeholder, actions = emptyList(), sources = emptyList(), picture = null, screenText = null)

    /**
     * FX1: [c] opened from History to go on with it, travel mode [hiding] drawers or not: [Continued.shown] as History
     * shows it, to the screen and to the model alike (so neither can repeat a hidden drawer's answer), and the stored turns
     * put back by [Continued.saved] whenever the conversation is saved again, so the stored turns never change.
     */
    fun continued(c: app.heyrobot.core.StoredConversation, hiding: Boolean, answer: String, question: String): Continued {
        if (!hiding) return Continued(c, emptyMap())
        val shown = history(c, answer, question)
        return Continued(shown, if (shown === c) emptyMap() else c.turns.associateBy { it.id })
    }

    /** What [continued] gives. */
    class Continued(val shown: app.heyrobot.core.StoredConversation, private val kept: Map<String, app.heyrobot.core.ChatTurn>) {
        /** [turns] as they are saved: each turn shown as a placeholder back as it was stored. */
        fun saved(turns: List<app.heyrobot.core.ChatTurn>): List<app.heyrobot.core.ChatTurn> = if (kept.isEmpty()) turns else turns.map { kept[it.id] ?: it }

        companion object {
            /** Nothing opened from History, or nothing hidden in it. */
            val NONE = Continued(app.heyrobot.core.StoredConversation("", 0, 0, emptyList()), emptyMap())
        }
    }
}
