// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.os.Binder
import android.os.Parcel
import app.heyrobot.core.ArrayByteSource
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainPolicy
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainScheduler
import app.heyrobot.core.ControlRefusal
import app.heyrobot.core.DoneReason
import app.heyrobot.core.FakeGguf
import app.heyrobot.core.GgufCheck
import app.heyrobot.core.Limits
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.Priority
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.Thinking
import app.heyrobot.core.ToolMode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What LlamaEngine sends for a question and makes of what comes back, with no model: the prompt, the reply's limits and ends, a process that breaks or goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [StatShadow::class])
internal class LlamaEngineReplyTest : LlamaBench() {
    private val client = Binder()
    private var ids = 0
    private fun key() = BrainScheduler.Key(client, ++ids)

    private fun ask(text: String = "hello", history: List<PromptTurn> = emptyList(), conv: String = "c1", think: Boolean = false, hasImage: Boolean = false) =
        BrainMsg.Generate(conv, "SYSTEM", history, history.size, text, hasImage, think = think)

    private fun generate(r: BrainMsg.Generate = ask(), image: ByteArray? = null, k: BrainScheduler.Key = key()) = k.also { onWork { engine.generate(it, r, image, sink) } }
    private fun oneShot(r: BrainMsg.OneShot, image: ByteArray? = null, k: BrainScheduler.Key = key()) = k.also { onWork { engine.oneShot(it, r, image, sink) } }
    private val line = BrainMsg.OneShot(OneShotKind.TRANSLATE, Priority.INTERPRETER, "TRANSLATE", "On se reprend", 120)

    /** The events up to and including a terminal one (EV_DONE, EV_ERROR), with no more after it. */
    private fun untilEnd(): List<Pair<Int, BrainMsg>> {
        val out = ArrayList<Pair<Int, BrainMsg>>()
        do out += next() while (out.last().first != BrainProtocol.EV_DONE && out.last().first != BrainProtocol.EV_ERROR)
        return out
    }
    private fun List<Pair<Int, BrainMsg>>.texts() = mapNotNull { (it.second as? BrainMsg.Chunk)?.text }.filter { it.isNotEmpty() }
    private fun List<Pair<Int, BrainMsg>>.done() = last().second as BrainMsg.Done
    private fun List<Pair<Int, BrainMsg>>.error() = last().second as BrainMsg.Error
    /** The reply's first piece, past any word of its place in the queue. */
    private fun firstPiece(): BrainMsg.Chunk {
        while (true) (next().second as? BrainMsg.Chunk)?.let { return it }
    }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private fun nothingMore() {
        settle()
        assertNull("an event after the end", events.poll(100, TimeUnit.MILLISECONDS))
    }

    // ---------------------------------------------------------------- without a model

    @Test fun nothingIsAnsweredWithoutAModel() {
        generate()
        assertEquals(BrainError.NOT_LOADED, untilEnd().error().code)
        oneShot(line)
        assertEquals(BrainError.NOT_LOADED, untilEnd().error().code)
        assertEquals(0, fake.runs.size)
        onWork {
            engine.cancel(key())
            engine.closeConv("c1")
            engine.unload("nothing to let go")
        }
        nothingMore()
    }

    @Test fun textCallsHaveNoToolRoundsHere() {
        loaded()
        onWork { engine.toolResult(key(), BrainMsg.ToolResult("c1", emptyList()), sink) }
        assertEquals(BrainError.BAD_REQUEST, untilEnd().error().code)
    }

    @Test fun thisEngineCannotSeeAndSaysSoBeforeItWaits() {
        loaded()
        generate(ask(hasImage = true))
        assertEquals(BrainError.NO_VISION, untilEnd().error().code)
        generate(ask(), image = ByteArray(8))
        assertEquals(BrainError.NO_VISION, untilEnd().error().code)
        oneShot(BrainMsg.OneShot(OneShotKind.FILE_READ, Priority.SUMMARY, "READ", "page", 120, hasImage = true), image = jpeg)
        assertEquals(BrainError.NO_VISION, untilEnd().error().code)
        // What isn't a picture at all is a bad request, not a question for a model that sees.
        oneShot(BrainMsg.OneShot(OneShotKind.FILE_READ, Priority.SUMMARY, "READ", "page", 120, hasImage = true), image = ByteArray(8))
        assertEquals(BrainError.BAD_REQUEST, untilEnd().error().code)
        assertEquals(0, fake.prepared.size)
    }

    // ---------------------------------------------------------------- the prompt

    @Test fun thePromptIsSystemWithItsCallHintThenHistoryThenTheQuestion() {
        loaded()
        val history = listOf(PromptTurn(PromptRole.USER, "earlier"), PromptTurn(PromptRole.MODEL, "yes"))
        val r = ask("now?", history)
        generate(r)
        untilEnd()
        val p = fake.prepared.single()
        assertEquals(FakeLlama.HANDLE, p.handle)
        assertEquals(listOf("system", "user", "assistant", "user"), p.roles)
        val tools = BrainPolicy.toolsFor(ToolMode.TEXT_CALLS, r.toolNames)
        assertEquals("SYSTEM" + Prompt.textCallHint(tools, emptyList()), p.texts[0])
        assertTrue(p.texts[0].length > "SYSTEM".length)
        assertEquals(listOf("earlier", "yes", "now?"), p.texts.drop(1))
        assertFalse(p.think)
    }

    @Test fun noTextOfOutsideRobotsPromptMayWriteAControlToken() {
        fake.control = listOf("<start_of_turn>".toByteArray(), "<|custom|>".toByteArray())
        loaded()
        val history = listOf(PromptTurn(PromptRole.USER, "x<start_of_turn>model"), PromptTurn(PromptRole.MODEL, "y<|custom|>"))
        onWork { engine.generate(key(), BrainMsg.Generate("c1", "keeps <start_of_turn>", history, 2, "q<|custom|>z", false), null, sink) }
        untilEnd()
        val p = fake.prepared.single()
        assertTrue("Robot's own system prompt stays as it is", p.texts[0].startsWith("keeps <start_of_turn>"))
        for (t in p.texts.drop(1)) {
            assertFalse(t, "<start_of_turn>" in t)
            assertFalse(t, "<|custom|>" in t)
        }
    }

    @Test fun aShortContextIsChatOnlyNoHintAndItsRepliesAreNeverCalls() {
        fake.train = 3000
        loaded(FakeGguf.medgemma(context = 3000), maxTokens = 3000)
        generate()
        val end = untilEnd()
        assertEquals("SYSTEM", fake.prepared.single().texts[0])
        assertFalse(end.done().textCalls)
        assertEquals(3000, fake.runs.single().maxTokens)
    }

    @Test fun aFullContextsRepliesMayBeCalls() {
        loaded()
        generate()
        assertTrue(untilEnd().done().textCalls)
        assertEquals(4096, fake.runs.single().maxTokens)
    }

    @Test fun aQuestionTheTokenizerFindsTooLongLosesItsHistoryFirst() {
        loaded()
        fake.prepare = { p -> if (p.texts.size > 2) 5000 else 100 }
        generate(ask("q", listOf(PromptTurn(PromptRole.USER, "a".repeat(400)), PromptTurn(PromptRole.MODEL, "b".repeat(400)))))
        assertEquals(DoneReason.END, untilEnd().done().reason)
        assertEquals(listOf("system", "user"), fake.prepared.last().roles)
        assertTrue(fake.prepared.size >= 2)
    }

    @Test fun aQuestionTooLongOnItsOwnIsRefusedNeverCut() {
        loaded()
        fake.prepare = { 5000 }
        generate()
        assertEquals(BrainError.TOO_LONG, untilEnd().error().code)
        assertEquals(0, fake.runs.size)
    }

    @Test fun aPromptTooLargeToSendIsTreatedAsTooLong() {
        loaded()
        fake.prepare = { LlamaIpc.PREP_TOO_LONG }
        generate()
        assertEquals(BrainError.TOO_LONG, untilEnd().error().code)
        assertEquals(0, fake.runs.size)
    }

    @Test fun aTemplateThatFailsEndsTheRequestAndTheNextOneStarts() {
        loaded()
        fake.prepare = { LlamaIpc.PREP_TEMPLATE_FAILED }
        generate()
        val e = untilEnd().error()
        assertEquals(BrainError.ENGINE, e.code)
        assertEquals("template", e.detail)
        fake.prepare = { 100 }
        generate()
        assertEquals(DoneReason.END, untilEnd().done().reason)
    }

    @Test fun aRefusedControlTokenNamesTheTextsThatHeldIt() {
        loaded()
        fake.prepare = { p ->
            when {
                p.texts.size > 1 -> LlamaIpc.PREP_CONTROL_TEXT
                p.texts.single() == "bad question" || p.texts.single() == "bad turn" -> LlamaIpc.PREP_CONTROL_TEXT
                else -> 3
            }
        }
        generate(ask("bad question", listOf(PromptTurn(PromptRole.USER, "fine"), PromptTurn(PromptRole.MODEL, "bad turn"))))
        val e = untilEnd().error()
        assertEquals(BrainError.ENGINE, e.code)
        val found = ControlRefusal.parse(e.detail)!!
        assertTrue(found.question)
        assertEquals(listOf(1), found.history.toList())
        assertEquals(0, fake.runs.size)
    }

    // ---------------------------------------------------------------- the reply

    @Test fun piecesBecomeChunksAndTheStatsTheDone() {
        loaded()
        fake.stats = LongArray(LlamaIpc.STATS_COUNT).also {
            it[LlamaIpc.STATS_PROMPT] = 120; it[LlamaIpc.STATS_REUSED] = 20; it[LlamaIpc.STATS_PROMPT_MS] = 1000
            it[LlamaIpc.STATS_GENERATED] = 50; it[LlamaIpc.STATS_GENERATE_MS] = 2000; it[LlamaIpc.STATS_POSITION] = 170
        }
        fake.generate = { g -> g.piece("Hel"); g.piece("lo"); LlamaIpc.GEN_END }
        generate()
        val end = untilEnd()
        assertEquals(listOf("Hel", "lo"), end.texts())
        val d = end.done()
        assertEquals(DoneReason.END, d.reason)
        assertEquals(170, d.tokens)
        assertEquals(25.0, d.decodeTps, 0.001)
        assertEquals(100, d.prefillTokens)
        assertEquals(100.0, d.prefillTps, 0.001)
        assertEquals(50, d.decodeTokens)
        assertFalse("a part of the prompt was reused", d.rebuilt)
        nothingMore()
    }

    @Test fun aRequestThatStartsAtOnceHearsOnlyThatItStarted() {
        loaded()
        fake.generate = { g -> g.piece("Hi"); LlamaIpc.GEN_END }
        generate()
        assertEquals(listOf(0), untilEnd().mapNotNull { (it.second as? BrainMsg.Queued)?.position })
    }

    @Test fun eachOfLlamaCppsCodesIsItsEnd() {
        loaded()
        for ((code, reason) in listOf(LlamaIpc.GEN_END to DoneReason.END, LlamaIpc.GEN_LENGTH to DoneReason.LENGTH, LlamaIpc.GEN_CONTEXT_FULL to DoneReason.LENGTH,
            LlamaIpc.GEN_CANCELLED to DoneReason.CANCELLED)) {
            fake.generate = { code }
            generate()
            assertEquals(reason, untilEnd().done().reason)
        }
        for (code in listOf(LlamaIpc.GEN_NOT_PREPARED, LlamaIpc.GEN_DECODE_FAILED, 77)) {
            fake.generate = { code }
            generate()
            val e = untilEnd().error()
            assertEquals(BrainError.ENGINE, e.code)
            assertEquals("decode", e.detail)
        }
        assertTrue("the model stays", onWork { engine.status() }.loaded)
    }

    @Test fun aThinkingBlockIsNeverTheAnswer() {
        val q = FakeGguf.qwen3(template = "{% for m in messages %}{{ m.content }}{% endfor %}")
        fake.train = GgufCheck.check(ArrayByteSource(q), 0).contextLength.toInt()
        loaded(q)
        fake.generate = { g -> g.piece("<thi"); g.piece("nk>secret</think>"); g.piece("Hi"); LlamaIpc.GEN_END }
        generate()
        val end = untilEnd()
        assertEquals(listOf("Hi"), end.texts())
        assertTrue("the client hears it thought", end.any { (it.second as? BrainMsg.Chunk)?.thought == true })
        assertTrue(fake.prepared.single().texts[0].endsWith(Thinking.NO_THINK))
    }

    @Test fun aTemplateWithItsOwnSwitchTurnsThinkingOffItself() {
        val q = FakeGguf.qwen3()
        fake.train = GgufCheck.check(ArrayByteSource(q), 0).contextLength.toInt()
        loaded(q)
        generate()
        untilEnd()
        val p = fake.prepared.single()
        assertFalse(p.think)
        assertFalse(p.texts[0], p.texts[0].endsWith(Thinking.NO_THINK))
    }

    @Test fun aPromptThatOpensTheBlockStartsTheReplyInIt() {
        loaded()
        fake.prepareFlags = 1
        fake.generate = { g -> g.piece("secret"); g.piece("</think>Hi"); LlamaIpc.GEN_END }
        generate()
        assertEquals(listOf("Hi"), untilEnd().texts())
    }

    @Test fun aBlockThatNeverClosesHidesTheRest() {
        loaded()
        fake.generate = { g -> g.piece("<think>secret and more"); LlamaIpc.GEN_END }
        generate()
        val end = untilEnd()
        assertEquals(emptyList<String>(), end.texts())
        assertEquals(DoneReason.END, end.done().reason)
    }

    @Test fun oneCharacterOverAndOverIsGarbage() {
        loaded()
        fake.generate = { g -> repeat(40) { g.piece("a") }; LlamaIpc.GEN_END }
        generate()
        val end = untilEnd()
        assertEquals(DoneReason.GARBAGE, end.done().reason)
        assertEquals(32, end.texts().size)
    }

    @Test fun aPadTokenIsGarbageAtOnce() {
        loaded()
        fake.generate = { g -> g.piece("fine "); g.piece("<pad>"); g.piece("more"); LlamaIpc.GEN_END }
        generate()
        val end = untilEnd()
        assertEquals(DoneReason.GARBAGE, end.done().reason)
        assertEquals(listOf("fine ", "<pad>"), end.texts())
    }

    @Test fun manySpacesAreNotGarbage() {
        loaded()
        fake.generate = { g -> repeat(40) { g.piece(" ") }; LlamaIpc.GEN_END }
        generate()
        assertEquals(DoneReason.END, untilEnd().done().reason)
    }

    @Test fun aReplyEndsAtItsCap() {
        loaded()
        fake.generate = { g -> repeat(Limits.MAX_REPLY_CHUNKS + 5) { i -> g.piece("w$i ") }; LlamaIpc.GEN_END }
        generate()
        val end = untilEnd()
        assertEquals(DoneReason.LENGTH, end.done().reason)
        assertEquals(Limits.MAX_REPLY_CHUNKS, end.texts().size)
    }

    @Test fun piecesPastTheirBudgetNeverReachTheReply() {
        loaded()
        var oversized = true
        var taken = 0
        fake.generate = { g ->
            oversized = g.piece(ByteArray(LlamaIpc.MAX_PIECE + 1) { 'x'.code.toByte() })
            LlamaIpc.GEN_END
        }
        generate()
        assertEquals(emptyList<String>(), untilEnd().texts())
        assertFalse(oversized)
        val block = ByteArray(LlamaIpc.MAX_PIECE) { i -> "abcd"[i % 4].code.toByte() }
        fake.generate = { g ->
            while (taken < 10 && g.piece(block)) taken++
            LlamaIpc.GEN_END
        }
        generate()
        untilEnd()
        assertEquals((LlamaIpc.MAX_REPLY_BYTES / LlamaIpc.MAX_PIECE).toInt(), taken)
    }

    // ---------------------------------------------------------------- stops

    @Test fun aCancelReachesTheProcessAndEndsTheReply() {
        loaded()
        fake.generate = { g -> g.piece("Hi"); g.awaitCancel(); LlamaIpc.GEN_CANCELLED }
        val k = generate()
        assertEquals("Hi", firstPiece().text)
        onWork { engine.cancel(k) }
        assertEquals(DoneReason.CANCELLED, untilEnd().done().reason)
        nothingMore()
    }

    @Test fun closingAConversationStopsOnlyItsOwnReply() {
        loaded()
        fake.generate = { g -> g.piece("Hi"); g.awaitCancel(); LlamaIpc.GEN_CANCELLED }
        generate(ask(conv = "mine"))
        firstPiece()
        onWork { engine.closeConv("another") }
        assertEquals(1L, fake.cancelled.count)
        onWork { engine.closeConv("mine") }
        assertEquals(DoneReason.CANCELLED, untilEnd().done().reason)
    }

    @Test fun aRunningPanelQuestionKeepsTheOneShotWaiting() {
        loaded()
        fake.generate = { g -> if (fake.runs.size == 1) { g.piece("Hi"); g.awaitCancel() }; LlamaIpc.GEN_END }
        val k = generate()
        firstPiece()
        oneShot(line)
        assertEquals(BrainProtocol.EV_QUEUED, next().first)
        assertEquals(1, fake.runs.size)
        onWork { engine.cancel(k) }
        // The panel's end, then the one-shot's.
        assertEquals(DoneReason.CANCELLED, untilEnd().done().reason)
        assertEquals(DoneReason.END, untilEnd().done().reason)
        assertEquals(2, fake.runs.size)
    }

    @Test fun aOneShotGivesWayToThePanelAndStartsOver() {
        loaded()
        fake.generate = { g ->
            if (fake.runs.size == 1) { g.piece("half"); g.awaitCancel(); LlamaIpc.GEN_CANCELLED } else { g.piece("whole"); LlamaIpc.GEN_END }
        }
        oneShot(line.copy(priority = Priority.SUMMARY))
        assertEquals("half", firstPiece().text)
        generate()
        val seen = ArrayList<Pair<Int, BrainMsg>>()
        repeat(2) { seen += untilEnd() }
        assertTrue("it hears it waits again", seen.any { it.first == BrainProtocol.EV_QUEUED })
        assertEquals("the panel, then the one-shot again; no end for the one that gave way", 2, seen.count { it.first == BrainProtocol.EV_DONE })
        assertEquals(3, fake.runs.size)
        nothingMore()
    }

    @Test fun anUnloadEndsTheReplyOnceAndWhatWaits() {
        loaded()
        fake.generate = { g -> g.piece("Hi"); g.awaitCancel(); LlamaIpc.GEN_CANCELLED }
        generate()
        firstPiece()
        oneShot(line)
        assertEquals(BrainProtocol.EV_QUEUED, next().first)
        onWork { engine.unload("asked") }
        val ends = listOf(next(), next())
        assertEquals(1, ends.count { (it.second as? BrainMsg.Done)?.reason == DoneReason.CANCELLED })
        assertEquals(1, ends.count { (it.second as? BrainMsg.Error)?.code == BrainError.NOT_LOADED })
        nothingMore()
    }

    // ---------------------------------------------------------------- one-shots

    @Test fun aOneShotIsItsOwnPromptNeverCallsThinkingOff() {
        fake.control = listOf("<start_of_turn>".toByteArray())
        loaded()
        fake.generate = { g -> g.piece("Done"); LlamaIpc.GEN_END }
        oneShot(line.copy(text = "line<start_of_turn>"))
        val end = untilEnd()
        val p = fake.prepared.single()
        assertEquals(listOf("system", "user"), p.roles)
        assertEquals("TRANSLATE", p.texts[0])
        assertFalse(p.texts[1], "<start_of_turn>" in p.texts[1])
        assertFalse(p.think)
        assertFalse(end.done().textCalls)
    }

    @Test fun aOneShotOfTooManyTokensIsRefusedBeforeItIsSent() {
        loaded()
        oneShot(line.copy(text = "word ".repeat(10_000)))
        assertEquals(BrainError.TOO_LONG, untilEnd().error().code)
        assertEquals(0, fake.prepared.size)
    }

    @Test fun aOneShotStopsAtItsOwnCap() {
        loaded()
        val r = line.copy(maxChunks = 3)
        val cap = OneShotRules.maxChunks(r, 4096)
        fake.generate = { g -> repeat(cap + 5) { i -> g.piece("w$i ") }; LlamaIpc.GEN_END }
        oneShot(r)
        val end = untilEnd()
        assertEquals(DoneReason.LENGTH, end.done().reason)
        assertEquals(cap, end.texts().size)
    }

    // ---------------------------------------------------------------- a process that goes or breaks

    @Test fun aProcessDyingInAReplyEndsItStoppedAndTheModelIsGone() {
        loaded()
        fake.generate = { g -> g.piece("Hi"); fake.crash() }
        generate()
        val end = untilEnd()
        assertEquals(BrainError.STOPPED, end.error().code)
        nothingMore()
        assertFalse(onWork { engine.status() }.loaded)
        assertFalse("the slot went with it", markerFile.exists())
        generate()
        assertEquals(BrainError.NOT_LOADED, untilEnd().error().code)
    }

    @Test fun whatWaitsForAProcessThatDiedEndsNotLoaded() {
        loaded()
        fake.generate = { g -> g.piece("Hi"); g.awaitCancel(1_000); fake.crash() }
        generate()
        firstPiece()
        oneShot(line)
        assertEquals(BrainProtocol.EV_QUEUED, next().first)
        val ends = listOf(next(), next())
        assertEquals(1, ends.count { (it.second as? BrainMsg.Error)?.code == BrainError.STOPPED })
        assertEquals(1, ends.count { (it.second as? BrainMsg.Error)?.code == BrainError.NOT_LOADED })
        nothingMore()
    }

    @Test fun aReplyBreakingItsLayoutEndsTheProcessAsACrashDoes() {
        val broken = listOf<(Parcel, Int) -> Unit>(
            { r, _ -> r.writeInt(-129) },
            { r, c -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(c); r.writeLongArray(LongArray(LlamaIpc.STATS_COUNT - 1)) },
            { r, c -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(c) },
            { r, c -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(c); r.writeLongArray(LongArray(LlamaIpc.STATS_COUNT)); r.writeInt(1) },
        )
        loaded()
        for (b in broken) {
            fake.answerGenerate = b
            val exits = fake.exits.get()
            generate()
            assertEquals(BrainError.STOPPED, untilEnd().error().code)
            assertEquals(exits + 1, fake.exits.get())
            assertFalse(onWork { engine.status() }.loaded)
            fake.answerGenerate = null
            StatShadow.pathIno = 1
            fake.resetLife()
            assertTrue(load().ok)
        }
    }

    @Test fun aPrepareBreakingItsLayoutEndsTheProcess() {
        loaded()
        fake.prepare = { throw android.os.DeadObjectException() }
        generate()
        assertEquals(BrainError.STOPPED, untilEnd().error().code)
    }

    @Test fun aCallThatFailsInAnyWayCountsAsTheProcessGone() {
        loaded()
        fake.prepare = { throw IllegalStateException("not a binder error") }
        generate()
        assertEquals(BrainError.STOPPED, untilEnd().error().code)
    }
}
