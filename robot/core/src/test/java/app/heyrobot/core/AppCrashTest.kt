package app.heyrobot.core

import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 1.0: "Robot closed unexpectedly": which of Android's records is told, once, and the crash report Send feedback adds. */
class AppCrashTest {
    private val utc = ZoneId.of("UTC")
    private val t = 1_791_000_000_000L
    private fun exit(process: String, reason: Int, time: Long, pid: Int = 100, crash: NativeCrash? = null) =
        AppExit(process, ExitFacts(time, pid, reason, "R$reason", 0, null, 100, 1024, 2048, crash))

    @Test fun processNames() {
        assertEquals("main", AppCrash.processOf("app.heyrobot", "app.heyrobot"))
        assertEquals("voice", AppCrash.processOf("app.heyrobot:voice", "app.heyrobot"))
        assertEquals("brain", AppCrash.processOf("app.heyrobot:brain", "app.heyrobot"))
        assertNull(AppCrash.processOf("app.heyrobotx", "app.heyrobot"))
        assertNull(AppCrash.processOf("org.example", "app.heyrobot"))
        assertNull(AppCrash.processOf(null, "app.heyrobot"))
    }

    /** Only the app's and the assistant's crashes and "not responding" count, newer than the last one told; the newest wins. */
    @Test fun picksTheNewestUnexpectedExitOfWhatYouSee() {
        val exits = listOf(
            exit("main", AppCrash.REASON_CRASH, t),
            exit("voice", AppCrash.REASON_ANR, t + 10),
            exit("brain", AppCrash.REASON_CRASH_NATIVE, t + 20), // the model's: its own problem report
            exit("main", 3, t + 30), // a memory kill (LOW_MEMORY)
            exit("main", 10, t + 40), // a stop you asked for (USER_REQUESTED)
        )
        assertEquals(exits[1], AppCrash.pick(exits, 0))
        assertEquals(exits[1], AppCrash.pick(exits, t))
        assertNull(AppCrash.pick(exits, t + 10))
        assertNull(AppCrash.pick(emptyList(), 0))
        assertEquals(exit("voice", AppCrash.REASON_CRASH_NATIVE, t + 50), AppCrash.pick(exits + exit("voice", AppCrash.REASON_CRASH_NATIVE, t + 50), t + 10))
        // The first look marks what is already there as told.
        assertEquals(t + 40, AppCrash.firstSeen(exits, t + 99))
        assertEquals(t + 99, AppCrash.firstSeen(emptyList(), t + 99))
        assertNull(AppCrash.pick(exits, AppCrash.firstSeen(exits, t + 99)))
    }

    @Test fun theNoticeSaysWhatClosed() {
        assertEquals("Robot closed unexpectedly.", AppCrash.headline(exit("main", AppCrash.REASON_CRASH, t)))
        assertEquals("Robot's assistant closed unexpectedly.", AppCrash.headline(exit("voice", AppCrash.REASON_CRASH_NATIVE, t)))
        assertEquals("Robot stopped responding, and Android closed it.", AppCrash.headline(exit("main", AppCrash.REASON_ANR, t)))
        val text = AppCrash.text(exit("voice", AppCrash.REASON_CRASH, t), utc)
        assertTrue(text, text.startsWith("Robot's assistant closed unexpectedly. (") && text.contains("Send feedback") && text.contains("sends nothing"))
        assertFalse(text.contains("owner"))
    }

    /** The report: the crash's record (a native crash's frames under it), this phone, the voice log; nothing of yours. */
    @Test fun theReportHoldsCodesOnly() {
        val native = NativeCrash(6, "SIGABRT", 0, "SI_USER", null, true, listOf("#00 libc.so +0x5b2c4 (abort+164)"), 12)
        val e = AppExit("voice", ExitFacts(t, 4242, AppCrash.REASON_CRASH_NATIVE, "CRASH_NATIVE", 6, "crash in /storage/emulated/0/Download/secret notes.txt", 100, 2_048_000, 4_096_000, native))
        val other = ExitFacts(t - 60_000, 4100, AppCrash.REASON_CRASH, "CRASH", 0, null, 100, 1024, 2048)
        val log = listOf(VoiceLogEntry(t - 1_000, VoiceEvent.FEEDBACK_OPENED, FeedbackEntry.CRASH.id, FeedbackKind.BUG.id))
        val facts = ReportFacts("1.0.0", 100, Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5", 16_000_000_000L), "16 (SDK 36)", "google/fp", -1,
            listOf(other), log)
        val r = AppCrash.report(e, facts, t + 5_000, utc)
        for (w in listOf("Robot crash report", "Robot's assistant closed unexpectedly.", "Robot: 1.0.0 (100)", "Pixel 10 Pro", "16 (SDK 36)",
            "Process: the assistant (:voice)", "pid 4242 · reason CRASH_NATIVE (5) · status 6", "    Native crash: SIGABRT (6)", "#00 libc.so +0x5b2c4",
            "pid 4100 (another) · reason CRASH (4)", "Voice log, last 1 events", "Nothing here was sent anywhere")) assertTrue(w + "\n" + r, r.contains(w))
        // Android's free text is scrubbed of paths, as in the problem report.
        assertFalse(r, r.contains("secret") || r.contains("/storage"))
        // Send feedback opens with the crash: a bug ticks the report (the problem diagnostic) by default.
        assertTrue(Diagnostic.PROBLEM in Diagnostic.defaults(FeedbackKind.BUG, FeedbackEntry.CRASH))
        assertEquals(FeedbackEntry.CRASH, FeedbackEntry.of("crash"))
    }
}
