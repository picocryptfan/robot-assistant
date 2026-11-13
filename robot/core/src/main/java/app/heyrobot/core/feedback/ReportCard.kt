// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import org.json.JSONObject

// Pure (1.4, report-a-problem-plan.md "What's new" 2 and 3, "Privacy and the lock"). The card's turn: what you said, my
// answer and what I did (the tool and its outcome code, never its text), with the languages heard and replied in. The
// panel writes it to report_turn.json in Robot's own folder when you ask to report a problem, locked or not; it waits 10
// minutes, and the card takes it once (deleting it) when it opens, so a turn asked about while locked is there after you
// unlock. ReportCard makes what the card shows and what goes: each part only when ticked, names and numbers hidden while
// "Hide names and numbers" is on (ReportHide), exactly as the card shows them.

/**
 * The turn a report is about. [heard], [replied]: language tags (ReportSort's language problem). [at]: when it was kept.
 * [other]: the conversation up to my answer read another app or person (ReportCard.carries), so my answer can carry it.
 */
data class ReportTurn(
    val said: String?,
    val answer: String?,
    val tool: ToolName? = null,
    val outcome: ActionStatus? = null,
    val heard: String? = null,
    val replied: String? = null,
    val at: Long = 0,
    val other: Boolean = false,
    /** Review M2: your words, or my answer, came while the phone was locked (ChatTurn.locked: perhaps someone else's). */
    val locked: Boolean = false,
    /** fix-seal-14 M1: my answer's id, and History's conversation it's in: where the card finds the turn again. */
    val id: String? = null,
    val conv: String? = null,
) {
    /** What I did, as it goes: the tool's id and its outcome code ("set_timer · DONE"), never the tool's own text. */
    val did: String? get() = tool?.let { t -> t.id + (outcome?.let { " · " + it.name } ?: "") }

    /** Still waiting: kept less than 10 minutes ago. */
    fun fresh(now: Long): Boolean = now - at in 0..FRESH_MS

    fun toJson(): String = JSONObject().put("v", 1).put("at", at).apply {
        said?.let { put("said", it) }
        answer?.let { put("answer", it) }
        tool?.let { put("tool", it.id) }
        outcome?.let { put("outcome", it.name) }
        heard?.let { put("heard", it) }
        replied?.let { put("replied", it) }
        if (other) put("other", true)
        if (locked) put("locked", true)
        id?.let { put("id", it) }
        conv?.let { put("conv", it) }
    }.toString()

    companion object {
        const val FILE = "report_turn.json" // l10n: not text (a file name)
        const val FRESH_MS = 600_000L
        const val MAX_TEXT = 2_000

        fun fromJson(text: String?): ReportTurn? {
            val o = try { JSONObject(text ?: return null) } catch (_: Exception) { return null }
            if (o.optInt("v") != 1) return null
            val at = o.optLong("at", 0).takeIf { it > 0 } ?: return null
            fun s(k: String) = (o.opt(k) as? String)?.take(MAX_TEXT)
            val tool = s("tool")?.let { id -> ToolName.entries.firstOrNull { it.id == id } }
            val outcome = s("outcome")?.let { n -> ActionStatus.entries.firstOrNull { it.name == n } }
            return ReportTurn(s("said"), s("answer"), tool, outcome, s("heard")?.take(TAG), s("replied")?.take(TAG), at,
                o.optBoolean("other"), o.optBoolean("locked"), s("id")?.take(128), s("conv")?.take(128))
        }

        private const val TAG = 35

        /**
         * The newest finished turn of [chat]: my last answer (not [exclude], the reply being written now) and the question
         * before it; what I did is its last action. Null when I haven't answered yet. My own reply to a report phrase (a
         * locked ask's "Unlock your phone, then say it again") is never the turn: asking again keeps the turn before it, and
         * then [heard] and [replied], the previous turn's languages, aren't that turn's, so they're left out.
         */
        fun from(chat: List<ChatTurn>, exclude: String?, heard: String?, replied: String?, now: Long): ReportTurn? {
            var skipped = false
            for (i in chat.indices.reversed()) {
                val a = chat[i]
                // A blank reply is still being written, unless you stopped it before any words (review L2: still a turn).
                if (a.role != TurnRole.ASSISTANT || a.id == exclude || (a.text.isBlank() && a.actions.isEmpty() && !a.stopped)) continue
                val q = chat.subList(0, i).lastOrNull { it.role == TurnRole.USER }
                if (q != null && FeedbackRouter.route(q.text) != null) { skipped = true; continue }
                val act = a.actions.lastOrNull()
                return ReportTurn(q?.text?.take(MAX_TEXT), a.text.take(MAX_TEXT).ifBlank { null }, act?.tool, act?.status,
                    heard.takeUnless { skipped }, replied.takeUnless { skipped }, now,
                    ReportCard.carries(chat.subList(0, i + 1)), q?.locked == true || a.locked, id = a.id)
            }
            return null
        }

        /**
         * RP3: the turn that ends with my answer [id] (pressed and held, in the panel or History), or the newest ([from]) when
         * [id] is null (the panel's bug button). [heard] and [replied] are the newest turn's languages, kept only when it is.
         */
        fun of(chat: List<ChatTurn>, id: String?, heard: String?, replied: String?, now: Long): ReportTurn? {
            if (id == null) return from(chat, null, heard, replied, now)
            val i = chat.indexOfLast { it.id == id && it.role == TurnRole.ASSISTANT }.takeIf { it >= 0 } ?: return null
            val a = chat[i]
            if (a.text.isBlank() && a.actions.isEmpty() && !a.stopped) return null
            val q = chat.subList(0, i).lastOrNull { it.role == TurnRole.USER }
            val newest = chat.subList(i + 1, chat.size).none { it.role == TurnRole.ASSISTANT }
            val act = a.actions.lastOrNull()
            return ReportTurn(q?.text?.take(MAX_TEXT), a.text.take(MAX_TEXT).ifBlank { null }, act?.tool, act?.status,
                heard.takeIf { newest }, replied.takeIf { newest }, now, ReportCard.carries(chat.subList(0, i + 1)),
                q?.locked == true || a.locked, id = a.id)
        }
    }
}

/**
 * fix-seal-14 M1: all report_turn.json keeps, where the card finds its turn again: History's conversation [conv] and my
 * answer's [id], when it was kept and the turn's languages. Never your words or my answer: they stay sealed in History.
 */
data class ReportTurnRef(
    val at: Long, val conv: String?, val id: String?, val heard: String? = null, val replied: String? = null,
    /** fix-bug-card-14-b L-a: 128 random bits the panel's hand must give back with the turn (null: an earlier build's ref). */
    val token: String? = null,
) {
    fun fresh(now: Long): Boolean = now - at in 0..ReportTurn.FRESH_MS

    fun toJson(): String = JSONObject().put("v", 2).put("at", at).apply {
        conv?.let { put("conv", it) }
        id?.let { put("id", it) }
        heard?.let { put("heard", it) }
        replied?.let { put("replied", it) }
        token?.let { put("token", it) }
    }.toString()

    /** The turn as History's [turns] hold it now; null when it isn't there (deleted, never saved). */
    fun resolve(turns: List<ChatTurn>): ReportTurn? =
        id?.let { ReportTurn.of(turns, it, null, null, at) }?.copy(heard = heard, replied = replied, conv = conv)

    companion object {
        private const val ID_MAX = 128
        private const val TAG = 35

        fun of(t: ReportTurn, token: String? = null) = ReportTurnRef(t.at, t.conv, t.id, t.heard, t.replied, token)

        /** 128 random bits, as hex. */
        fun newToken(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Only v2: an earlier build's v1 file, which held the words, isn't one (and is deleted as it's read). */
        fun fromJson(text: String?): ReportTurnRef? {
            val o = try { JSONObject(text ?: return null) } catch (_: Exception) { return null }
            if (o.optInt("v") != 2) return null
            val at = o.optLong("at", 0).takeIf { it > 0 } ?: return null
            fun s(k: String, max: Int) = (o.opt(k) as? String)?.take(max)
            return ReportTurnRef(at, s("conv", ID_MAX), s("id", ID_MAX), s("heard", TAG), s("replied", TAG), s("token", ID_MAX))
        }
    }
}

/** What the card got: [turn] null when a turn was kept but History couldn't give it then (locked, deleted): the report says so. */
data class KeptTurn(val turn: ReportTurn?)

/** What the card shows and sends, from the turn, your ticks and the hide switch. */
object ReportCard {
    /** The turn's parts, each with its own tick; with [ReportPart.FACTS], ticked for you when you came from that turn. */
    val TURN_PARTS: Set<ReportPart> = setOf(ReportPart.SAID, ReportPart.ANSWER, ReportPart.ACTIONS)

    /**
     * The tools whose result is another app's content or a place: every one that [ToolName.reads] (the next one added too),
     * and the internal ones that bring back words Robot didn't write (a connection, a script, another Robot, a helper).
     */
    val OTHER_APPS: Set<ToolName> = ToolName.entries.filter { it.reads }.toSet() + setOf(ToolName.CONNECTION, ToolName.TERMUX_SCRIPT,
        ToolName.TERMUX_COMMAND, ToolName.ASK_ROBOT, ToolName.ASK_HELPER)

    /**
     * Whether [turns] (the conversation up to my answer) hold words Robot read, with a tool or none: a reading tool or a
     * content action, a thread read aloud (ChatTurn.messages, no action), an answer from memory, a helper's or the web's,
     * Claude's read from its screen, a file, and what you shared, pictured or showed from your screen (by its attachment too:
     * with "Keep screenshots and photos in history" off, the default, the turn keeps neither), a file you attached, and words
     * said while the phone was locked (perhaps by someone else). Then my answer starts unticked (fail closed: an earlier read
     * counts, since a follow-up can repeat it).
     */
    fun carries(turns: List<ChatTurn>): Boolean = turns.any { t ->
        t.messages || t.fromHelper || t.readBack || t.files || t.shared || t.screenText != null || t.picture != null ||
            t.attachment != Attachment.NONE || t.fileName != null || t.locked ||
            t.memoryIds.isNotEmpty() || t.sources.isNotEmpty() || t.actions.any { it.content || it.tool in OTHER_APPS }
    }

    /**
     * The card's ticks when it opens: every part and facts, but my answer only when the turn read no other app, and what
     * you said only when it wasn't said while locked (perhaps by someone else). You can tick either.
     */
    fun ticked(turn: ReportTurn): Set<ReportPart> {
        val all = TURN_PARTS + ReportPart.FACTS
        val ticks = if (turn.other || turn.tool in OTHER_APPS) all - ReportPart.ANSWER else all
        return if (turn.locked) ticks - ReportPart.SAID else ticks
    }

    /** The voice log's window on the card. */
    const val LOG_MS = 600_000L

    /** Where the report's voice log starts: its last [LOG_MS] with a turn; a crash or About report keeps the whole log (null). */
    fun logSince(withTurn: Boolean, now: Long): Long? = if (withTurn) now - LOG_MS else null

    /** The saved state's turn ([json]) while fresh, else null: your words and my answer leave it once the turn goes stale. */
    fun saved(json: String?, now: Long): String? = json?.takeIf { ReportTurn.fromJson(it)?.fresh(now) == true }

    /** How long until [turn] goes stale (0 once it has). */
    fun staleIn(turn: ReportTurn, now: Long): Long = (turn.at + ReportTurn.FRESH_MS + 1 - now).coerceAtLeast(0L)

    /** What facts say when unticked: the phone, its chip, the model and the look stay on the phone. */
    /** fix-seal-14 M1: the report's turn section when History couldn't give the kept turn. */
    const val NOT_INCLUDED = "The turn wasn't included: History couldn't be read then." // l10n: not text (the report, read by the developer: English)

    const val WITHHELD = "(not shared)" // l10n: not text (the report, read by the developer: English)

    /** The turn as it would go: names, numbers and addresses hidden while [hide] is on (ReportHide), else as said. */
    fun shown(turn: ReportTurn, hide: Boolean, names: Collection<String> = emptyList(), numbers: Collection<String> = emptyList(),
        addresses: Collection<String> = emptyList()): ReportHide.Turn {
        val t = ReportHide.Turn(turn.said, turn.answer, turn.did)
        return if (hide) ReportHide.hide(t, names, numbers, addresses) else t
    }

    /** One part's text as it would go, or null when the turn has none. */
    fun part(shown: ReportHide.Turn, p: ReportPart): String? = when (p) {
        ReportPart.SAID -> shown.said
        ReportPart.ANSWER -> shown.answer
        ReportPart.ACTIONS -> shown.tool
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** The report's turn section: only the ticked parts that have text, in order; null when none. */
    fun section(shown: ReportHide.Turn, ticked: Set<ReportPart>): String? {
        val lines = listOf(ReportPart.SAID to "What you said:", ReportPart.ANSWER to "My answer:", ReportPart.ACTIONS to "What I did:") // l10n: not text (the report, read by the developer: English)
            .mapNotNull { (p, label) -> if (p in ticked) part(shown, p)?.let { "$label\n$it" } else null }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** The parts that go: the ticked turn parts that have text, and facts when ticked. */
    fun parts(shown: ReportHide.Turn?, ticked: Set<ReportPart>): Set<ReportPart> =
        ticked.filter { p -> if (p in TURN_PARTS) shown != null && part(shown, p) != null else p == ReportPart.FACTS }.toSet()

    /** The header's facts: all of them when ticked, else Robot's version and the language only. */
    fun facts(f: FeedbackFacts, ticked: Boolean): FeedbackFacts = if (ticked) f else f.copy(phone = WITHHELD, model = WITHHELD, look = WITHHELD, chip = "")

    /** The facts' lines as the header writes them (what Show shows). */
    fun factsText(f: FeedbackFacts): String = ReportHeader.write(FeedbackKind.BUG, "", f).lines().drop(3).filter { it.isNotEmpty() }.joinToString("\n")

    /** ReportSort's input: the entry, your words, the tool that ran, my answer and the two languages. */
    fun input(entry: FeedbackEntry, turn: ReportTurn?, words: String): ReportSort.Input =
        ReportSort.Input(entry, words, listOfNotNull(turn?.tool), turn?.answer, turn?.heard, turn?.replied)
}

/**
 * The languages of the turn before the one asked now (PanelController): asking a report phrase starts a turn of its own,
 * and the card reports the one before it, a French ask answered in English say.
 */
class ReportLangs {
    @Volatile var heardBefore: String? = null
        private set
    @Volatile var replyBefore: String? = null
        private set

    /** A question heard in [heard], asked while my last reply was in [lastReply]. */
    fun asked(heard: String?, lastReply: String?) {
        replyBefore = lastReply
        heardBefore = heardNow
        heardNow = heard
    }

    @Volatile private var heardNow: String? = null

    /** RP3: the newest question's language, for the bug button (no report phrase asked, so that turn is the one). */
    val heardLast: String? get() = heardNow
}
