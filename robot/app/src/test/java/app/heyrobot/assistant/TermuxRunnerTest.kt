package app.heyrobot.assistant

import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceLogCodec
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.termux.TermuxExitBucket
import app.heyrobot.core.termux.TermuxExtra
import app.heyrobot.core.termux.TermuxFailure
import app.heyrobot.core.termux.TermuxIntents
import app.heyrobot.core.termux.TermuxOutput
import app.heyrobot.core.termux.TermuxResult
import app.heyrobot.core.termux.TermuxRun
import app.heyrobot.core.termux.TermuxRunKind
import app.heyrobot.core.termux.TermuxRunPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One Termux run from sending to its result (B8.2, B8.3): the result for the id it waits for, Termux's own errors in words,
 * the deadline and Stop sending the fixed Stop run for its process group, late and unknown results dropped as TERMUX_LATE,
 * and the logs: a marker planted in the command, a script's name, an argument and the output never reaches RLog or the
 * voice log (B9: codes, buckets, sizes and milliseconds only).
 */
class TermuxRunnerTest {
    private val sent = CopyOnWriteArrayList<TermuxRunPlan>()
    private var refuse: TermuxFailure? = null
    private val transport = TermuxTransport { p -> sent += p; refuse }
    private val logged = CopyOnWriteArrayList<String>()
    private var mark = 0L

    private fun runner(deadlineMs: Long = 5_000) = TermuxRunner(transport, random = Random(7), deadline = { deadlineMs })

    @Before fun hook() {
        RLog.sink = { logged += it }
        mark = VoiceLog.total
    }

    @After fun unhook() { RLog.sink = null }

    /** The voice log's events since the test began, oldest first. */
    private fun events(): List<VoiceLogEntry> = VoiceLog.entries().take((VoiceLog.total - mark).toInt()).reversed()

    private fun ok(out: String = "hello\n") = TermuxResult(out, "", 0)

    private suspend fun waitSent(n: Int) = withTimeout(3_000) { while (sent.size < n) delay(5) }

    @Test fun theResultComesBackForItsRun() = runBlocking {
        val r = runner()
        val plan = TermuxRun.command(r.newId(), "du -sh ~", 60)
        val job = async(Dispatchers.Default) { r.run(plan) }
        waitSent(1)
        assertEquals(1, r.waiting)
        assertTrue(r.deliver(plan.execId, ok()))
        val reply = job.await() as TermuxReply.Answered
        assertEquals(0, reply.result.exitCode)
        assertEquals(0, r.waiting)
        assertEquals(listOf(VoiceEvent.TERMUX_SENT, VoiceEvent.TERMUX_DONE), events().map { it.event })
        assertEquals(TermuxRunKind.CARDED.id, events()[0].a)
        assertEquals(60, events()[0].b)
        assertEquals(TermuxExitBucket.OK.id, events()[1].a)
    }

    @Test fun termuxsOwnErrorsBecomeWords() = runBlocking {
        val r = runner()
        val plan = TermuxRun.command(r.newId(), "ls", 60)
        val job = async(Dispatchers.Default) { r.run(plan) }
        waitSent(1)
        r.deliver(plan.execId, TermuxResult(null, null, null, err = 2, errmsg = "RUN_COMMAND failed since allow-external-apps property is not set to true"))
        assertEquals(TermuxFailure.EXTERNAL_APPS_OFF, (job.await() as TermuxReply.Failed).failure)
        assertEquals(VoiceEvent.TERMUX_FAILED, events().last().event)
        // Android refused to start Termux: said, nothing waits.
        refuse = TermuxFailure.NO_PERMISSION
        assertEquals(TermuxFailure.NO_PERMISSION, (r.run(TermuxRun.command(r.newId(), "ls", 60)) as TermuxReply.Failed).failure)
        assertEquals(0, r.waiting)
    }

    @Test fun theDeadlineStopsTheWholeTreeAndTheLateResultIsDropped() = runBlocking {
        val r = runner(deadlineMs = 200)
        val plan = TermuxRun.command(r.newId(), "sleep 120", 10)
        assertEquals(TermuxFailure.NO_ANSWER, (r.run(plan) as TermuxReply.Failed).failure)
        // The fixed Stop run for that run's process group went at once.
        val stop = sent.last()
        assertEquals(TermuxRunKind.STOP, stop.kind)
        assertEquals(TermuxRun.WRAP_STOP, stop.arguments[3])
        assertEquals("${plan.execId}", stop.arguments.last())
        // Its own result is dropped quietly; the run's late one is TERMUX_LATE.
        assertFalse(r.deliver(stop.execId, ok("")))
        assertFalse(r.deliver(plan.execId, TermuxResult("", "", 143)))
        val e = events()
        assertEquals(VoiceEvent.TERMUX_LATE, e.last().event)
        assertEquals(TermuxExitBucket.STOPPED.id, e.last().a)
        assertEquals(1, e.count { it.event == VoiceEvent.TERMUX_LATE })
    }

    @Test fun stopEndsTheRunsProcessTree() = runBlocking {
        val r = runner()
        val plan = TermuxRun.script(r.newId(), "backup", listOf("--all"), 60)
        val job = async(Dispatchers.Default) { r.run(plan) }
        waitSent(1)
        job.cancelAndJoin()
        waitSent(2)
        assertEquals(TermuxRunKind.STOP, sent[1].kind)
        assertEquals("${plan.execId}", sent[1].arguments.last())
        assertEquals(0, r.waiting)
        // Termux answers 143 after the Stop: nobody waits any more.
        assertFalse(r.deliver(plan.execId, TermuxResult("", "", 143)))
        assertEquals(VoiceEvent.TERMUX_LATE, events().last().event)
    }

    @Test fun aResultForARunRobotNeverSentIsDropped() {
        val r = runner()
        assertFalse(r.deliver(12345, ok()))
        assertEquals(VoiceEvent.TERMUX_LATE, events().last().event)
        // Open in Termux: sent, not waited for, its exit code dropped quietly.
        val t = TermuxRun.terminal(r.newId(), "htop")
        assertEquals(null, r.fire(t))
        assertFalse(r.deliver(t.execId, TermuxResult(null, null, 0)))
        assertEquals(1, events().count { it.event == VoiceEvent.TERMUX_LATE })
    }

    @Test fun idsArePositiveAndUnique() {
        val r = TermuxRunner(transport)
        val ids = (1..2000).map { r.newId() }
        assertTrue(ids.all { it > 0 })
        assertEquals(ids.size, ids.toSet().size)
    }

    /** B9: a marker in the command, a script's name, an argument and the output reaches no log. */
    @Test fun nothingAboutACommandReachesALog() = runBlocking {
        val marker = "zq9marker"
        val r = runner(deadlineMs = 300)
        // A command and its output.
        val p1 = TermuxRun.command(r.newId(), "cat ~/$marker/notes | grep $marker", 60)
        val j1 = async(Dispatchers.Default) { r.run(p1) }
        waitSent(1)
        r.deliver(p1.execId, TermuxResult("$marker\n".repeat(200), "error: $marker", 1, stdoutOriginalLength = "999999"))
        j1.await()
        // A script named with it, with it as an argument, failing with it in Termux's message.
        val p2 = TermuxRun.script(r.newId(), marker, listOf(marker, "--$marker"), 60)
        val j2 = async(Dispatchers.Default) { r.run(p2) }
        waitSent(2)
        r.deliver(p2.execId, TermuxResult(null, null, null, err = 1, errmsg = "No such file: /data/data/com.termux/files/home/robot/$marker $marker"))
        j2.await()
        // Past its deadline (a Stop run), then late.
        val p3 = TermuxRun.command(r.newId(), "sleep 99 # $marker", 10)
        r.run(p3)
        r.deliver(p3.execId, TermuxResult(marker, marker, 137))
        // Stopped by the turn.
        val p4 = TermuxRun.command(r.newId(), "echo $marker", 60)
        val before = sent.size
        val j4 = async(Dispatchers.Default) { r.run(p4) }
        waitSent(before + 1)
        j4.cancelAndJoin()
        // What Robot itself makes of the output for the card and Gemma is no log either: it was prepared, never logged.
        TermuxOutput.prepare(TermuxResult(marker, marker, 0))
        val e = events()
        assertTrue(e.isNotEmpty())
        val texts = logged + VoiceLogCodec.encode(e) + e.map { VoiceLogText.describe(it) }
        assertEquals(emptyList<String>(), texts.filter { it.contains(marker) })
        assertTrue(logged.any { it.startsWith("termux_run ") } && logged.any { it.startsWith("termux_failed ") } && logged.any { it.startsWith("termux_late ") })
        // Termux itself gets the label "Robot" only: never the command (it logs labels and shows them in its notifications).
        for (p in sent) assertEquals(TermuxExtra.Text("Robot"), p.extras()[TermuxIntents.EXTRA_COMMAND_LABEL])
    }
}
