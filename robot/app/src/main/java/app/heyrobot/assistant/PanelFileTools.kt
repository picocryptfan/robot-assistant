// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileAsk
import app.heyrobot.core.files.FileFindCode
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FilePick
import app.heyrobot.core.files.FileSearch
import app.heyrobot.core.files.FileTools
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FilesForHelper
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.sys.FilesIntake
import java.time.LocalDate
import java.time.ZoneId

// VOICE PROCESS. 1.4, Robot Files and the model (files-1.4-plan.md §5.1, §5.3, D7; job F9): find_files and read_file as
// ToolExecutor runs them, after the approval gate. Both read the sealed index through this process's vault, behind its lock
// gate (FilesIntake.gate: a phone lock, a locked phone, Robot Files off); ApprovalGate already refused them on a locked phone,
// and this checks again, since the phone may lock while a card waits.
//
// find_files gives the model core FileTools' results (never a file's text) and code's count and totals; the card says how
// many (History keeps that line, never a name) and lists the names in its body (shown, never kept). The numbers it gave
// (1 to 3) name files for read_file in this conversation only: kept in RAM here, ids only, dropped when the panel closes or
// a new conversation begins. read_file ends the model's turn with the file's id and the question (Executed.file); the answer
// is PanelFileAnswer's, a tool-free one-shot over the file's text. Nothing here logs a query, a name or a question.

internal object PanelFileTools {
    /** The files this conversation's last find_files numbered (1 to 3): ids only. */
    @Volatile private var numbered: List<FileId> = emptyList()

    /** Why the file tools can't run now, or null: a phone lock (it closes them), a locked phone, Robot Files off. */
    fun refusal(gate: FilesIntake.Gate): Prepared.Refused? = when (gate) {
        FilesIntake.Gate.OPEN -> null
        FilesIntake.Gate.PHONE_LOCK -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LockStrings.closedNow())
        FilesIntake.Gate.LOCKED -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, FilesStrings.locked())
        FilesIntake.Gate.OFF -> Prepared.Refused(ActionStatus.DISABLED, FilesStrings.toolsOff())
    }

    /** Before the gate's card: refused while the files can't be read. */
    fun prepare(action: Action, gate: FilesIntake.Gate): Prepared = refusal(gate) ?: Prepared.Ready(action)

    /** Whether the file tools may be described to the model now ([ctx]'s process): Robot Files on, unlocked, no phone lock. */
    fun open(ctx: Context): Boolean = try { FilesIntake.gate(ctx) == FilesIntake.Gate.OPEN } catch (t: Exception) { RLog.w("files_tools_gate", t); false }

    private fun refused(r: Prepared.Refused): Executed = Executed(r.status, r.text ?: Phrase.plain(r.detail))

    /**
     * find_files: the matches and code's totals. [gate] and [index] are the vault's (tests give their own); [today] reads
     * "August" and "last month".
     */
    fun find(a: Action.FindFiles, gate: () -> FilesIntake.Gate, index: () -> FileIndex?, today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()): Executed {
        refusal(gate())?.let { return refused(it) }
        val idx = index() ?: return Executed(ActionStatus.NEEDS_UNLOCK, FilesStrings.locked())
        val q = FileSearch.parse(a.query, a.whenText, a.drawer, FileViews.yours(idx), today)
        val all = FileSearch.all(idx, q, zone = zone)
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.TOOL.id, all.size)
        if (all.isEmpty()) {
            numbered = emptyList()
            return Executed(ActionStatus.NOT_FOUND, ToolStrings.noFileMatches(), results = emptyList())
        }
        val shown = all.take(FileTools.SHOWN)
        numbered = shown.map { it.id }
        // The card's body: each name (a private file only as "a private file"), shown in the panel; History keeps only the count.
        val bodyText = Phrase.Join(shown.mapIndexed { i, e ->
            Phrase.Join(listOf(Phrase.plain("${i + 1}. "), if (FilesForHelper.private(e)) FilesStrings.privateFile() else if (e.name.isBlank()) FilesStrings.noName() else Phrase.plain(e.name)), "")
        }, "\n")
        return Executed(ActionStatus.DONE, ToolStrings.foundFiles(all.size.toLong()), results = FileTools.results(shown, zone),
            body = bodyText, forModel = FileTools.summary(all))
    }

    /**
     * read_file: the file it names (a number from this conversation's last find_files, or words naming one), checked for
     * text Robot can quote. The turn ends with it ([Executed.file]); the answer is PanelFileAnswer's.
     */
    fun read(a: Action.ReadFile, gate: () -> FilesIntake.Gate, index: () -> FileIndex?, today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()): Executed {
        refusal(gate())?.let { return refused(it) }
        val idx = index() ?: return Executed(ActionStatus.NEEDS_UNLOCK, FilesStrings.locked())
        val e = when (val p = FileTools.pick(a.file, numbered, idx, yours = FileViews.yours(idx), today = today, zone = zone)) {
            is FilePick.One -> p.entry
            FilePick.NoNumber -> return Executed(ActionStatus.INVALID, ToolStrings.fileFindFirst(), forModel = NO_NUMBER)
            is FilePick.Unclear -> return Executed(ActionStatus.NOT_FOUND, ToolStrings.fileWhich(), forModel = if (p.matches == 0) NONE else SEVERAL)
        }
        if (e.kind == FileKind.DANGEROUS) return Executed(ActionStatus.FAILED, FilesStrings.dangerous())
        if (e.text.isBlank()) return Executed(ActionStatus.NOT_FOUND, ToolStrings.fileNoText())
        return Executed(ActionStatus.DONE, ToolStrings.readFileDone(), file = FileAsk(e.id, a.question, e.kind, FilesForHelper.private(e)))
    }

    /** A new conversation, or the panel closed: no number names a file any more. */
    fun forget() {
        numbered = emptyList()
    }

    // What the model is told when read_file can't pick its file (fixed English, as other tools' details).
    private const val NO_NUMBER = "No file has that number in this conversation: call find_files first and use a number it gave." // l10n: not text (the model's tool result)
    private const val NONE = "No file matches those words: call find_files first." // l10n: not text (the model's tool result)
    private const val SEVERAL = "Several files match those words: call find_files and use a number it gave." // l10n: not text (the model's tool result)
}
