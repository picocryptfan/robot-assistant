// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.read

import android.app.KeyguardManager
import android.content.Context
import app.heyrobot.assistant.ask.SharedItem
import app.heyrobot.core.NoteSearch
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.read.ReadChunker
import app.heyrobot.core.read.ReadCommand
import app.heyrobot.core.read.StepRouter
import app.heyrobot.core.read.StepSay
import app.heyrobot.core.read.StepsOf
import app.heyrobot.assistant.steps.StepVoice
import app.heyrobot.core.read.ReadKind
import app.heyrobot.core.read.ReadRouter
import app.heyrobot.core.read.ReadSource
import app.heyrobot.core.read.ReadText
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.RobotFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

// VOICE PROCESS. Read it to me in the panel (read-it-to-me-plan.md §1, §4): core ReadRouter hears the words, after
// Messages and before Lists (PanelController.send); this does them. A note is read whole ("read me my last note": the
// newest; "read me my note about the trip": NoteSearch's best), never opened while the phone is locked. The panel says the
// line this returns; the reading itself is ReadingVoice's. Logs codes and counts, never words.
// A text shared through the share door (AskChip's "Read it to me", or "read me this" while it is attached) is read under a
// random id, its place dropped at the end (ReadKind.SHARED). A File's text comes from :files with PAGE_BREAKS, so a PDF's
// pages lose their headers, footers and page numbers (ReadChunker.pdf); never while the phone or Robot Files is locked.

object PanelRead {
    fun route(q: String): ReadCommand? = StepVoice.route(q) ?: StepRouter.route(q) ?: ReadRouter.route(q, ReadingVoice.state)

    /** [shared]: what another app shared, on this turn (the panel's draft), or null. */
    suspend fun handle(ctx: Context, c: ReadCommand, shared: SharedItem? = null): Phrase = when (c) {
        is ReadCommand.Note -> note(ctx, c.query)
        is ReadCommand.File -> file(ctx, c.query)
        ReadCommand.Shared -> shared(ctx, sharedText(shared))
        is StepsOf -> steps(ctx, c.what, shared)
        is StepSay -> StepVoice.panel(c)
        else -> ReadingVoice.command(c)()
    }

    /** The text Read it to me reads of a shared item: a shared text's, never a photo's or a voice message's. */
    fun sharedText(item: SharedItem?): String? = item?.takeIf { it.kind == SharedKind.TEXT }?.text?.takeIf { it.isNotBlank() }

    /** A File's text as :files gave it with PAGE_BREAKS: a PDF's by page, any other's as plain text. */
    fun fileText(type: FileType, text: String): ReadText =
        if (type == FileType.PDF) ReadChunker.pdf(text.split(ParseRequest.PAGE_BREAK)) else ReadChunker.plain(text)

    private val random = SecureRandom()

    /** The lock screen is up, or it can't be told: fails closed (Assistant.keyguard fails open). */
    private fun keyguard(ctx: Context): Boolean = try { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { true }

    private suspend fun shared(ctx: Context, text: String?): Phrase {
        if (text == null) return HandsFreeStrings.readNothing()
        if (keyguard(ctx)) return HandsFreeStrings.readUnlockFirst()
        val t = withContext(Dispatchers.Default) { ReadChunker.plain(text) }
        val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) } // l10n: not text (a random id)
        return ReadingVoice.start(ReadSource(ReadKind.SHARED, id), t)()
    }

    private suspend fun file(ctx: Context, query: String): Phrase {
        // Fail closed: Robot Files is read only unlocked, with Robot Files open (FilesIntake's gate).
        if (keyguard(ctx)) return HandsFreeStrings.readUnlockFirst()
        FilesIntake.gateText(FilesIntake.gate(ctx))?.let { return it }
        val (id, text) = withContext(Dispatchers.IO) { fileRead(ctx, query) } ?: return HandsFreeStrings.readNoFile()
        if (text == null) return HandsFreeStrings.readNoText()
        return ReadingVoice.start(ReadSource(ReadKind.FILE, id), text)()
    }

    /** Null: no such file. A null text: found, but it won't open or has no text (a scanned PDF). */
    private fun fileRead(ctx: Context, query: String): Pair<String, ReadText?>? = fileOpen(ctx, query, ::fileText)

    /** [fileRead] with the text made by [make]: Read it to me's pieces, or step by step's plain text. */
    private fun <T> fileOpen(ctx: Context, query: String, make: (FileType, String) -> T): Pair<String, T?>? {
        val idx = try { RobotFiles.shown(ctx) } catch (t: Throwable) { RLog.w("read_files", t); null } ?: return null
        val e = FileViews.search(idx, query).firstOrNull() ?: return null
        val bytes = try {
            RobotFiles.vault(ctx).open(e.id).use { it.readAll(ParseLimits.MAX_INPUT.toLong()) }
        } catch (t: Throwable) { RLog.w("read_file_open", t); return e.id.hex to null }
        val link = FileParseLink(ctx)
        try {
            if (!link.start()) return e.id.hex to null
            val r = link.read(bytes, ParseRequest.TEXT or ParseRequest.PAGE_BREAKS) as? ParseResult.Ok ?: return e.id.hex to null
            val text = r.out.text?.takeIf { it.isNotBlank() } ?: return e.id.hex to null
            return e.id.hex to make(r.out.type, text)
        } finally {
            bytes.fill(0)
            link.end("read_done") // l10n: not text (a log code)
        }
    }

    private suspend fun note(ctx: Context, query: String): Phrase {
        // Fail closed: not even the notes' list while locked (ReadGate refuses the reading anyway).
        if (keyguard(ctx)) return HandsFreeStrings.readUnlockFirst()
        val n = withContext(Dispatchers.IO) { findNote(ctx, query) } ?: return HandsFreeStrings.readNoNote()
        val text = withContext(Dispatchers.Default) { ReadChunker.plain(listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString("\n\n")) }
        return ReadingVoice.start(ReadSource(ReadKind.NOTE, n.id), text)()
    }

    private fun findNote(ctx: Context, query: String) = run {
        val all = try { NotesFiles.store(ctx).list() } catch (t: Throwable) { RLog.w("read_notes", t); emptyList() }
        if (query.isBlank()) all.maxByOrNull { it.updated } else NoteSearch.find(query, all, limit = 1).firstOrNull()
    }

    /** Step by step on a shared text or page, a note or a file: their plain text, before Read it to me's chunking. */
    private suspend fun steps(ctx: Context, what: ReadCommand, shared: SharedItem?): Phrase {
        if (keyguard(ctx)) return HandsFreeStrings.readUnlockFirst()
        val text: String = when (what) {
            ReadCommand.Shared -> sharedText(shared) ?: return HandsFreeStrings.readNothing()
            is ReadCommand.Note -> withContext(Dispatchers.IO) { findNote(ctx, what.query) }
                ?.let { n -> listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString("\n\n") } ?: return HandsFreeStrings.readNoNote()
            is ReadCommand.File -> {
                FilesIntake.gateText(FilesIntake.gate(ctx))?.let { return it }
                val (_, t) = withContext(Dispatchers.IO) { fileOpen(ctx, what.query) { _, s -> s } } ?: return HandsFreeStrings.readNoFile()
                t ?: return HandsFreeStrings.readNoText()
            }
            else -> return HandsFreeStrings.readNothing()
        }
        return withContext(Dispatchers.Default) { StepVoice.start(text) }
    }
}
