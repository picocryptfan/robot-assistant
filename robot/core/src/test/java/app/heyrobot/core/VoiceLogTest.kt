// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.time.ZoneOffset

/** The voice log: a ring of the last 300 events, its file, and that nothing but codes and numbers can go in. */
class VoiceLogTest {
    @Test fun theRingKeepsTheNewest300() {
        val r = VoiceLogRing(VoiceLog.CAPACITY)
        for (i in 1..350) r.add(i.toLong(), VoiceEvent.GATE_SPEECH, i)
        val s = r.snapshot()
        assertEquals(300, s.size)
        assertEquals(51L, s.first().time)
        assertEquals(350, s.last().a)
        r.replace(listOf(VoiceLogEntry(1, VoiceEvent.WAKE, 1)))
        assertEquals(1, r.size())
    }

    @Test fun theFileRoundTripsAndHoldsOnlyNumbers() {
        val list = listOf(
            VoiceLogEntry(1_760_000_000_000, VoiceEvent.WAKE, 1, -1, WakeAction.OPENED.id),
            VoiceLogEntry(1_760_000_000_500, VoiceEvent.SESSION_SHOWN, ShowSource.WAKE.id),
            VoiceLogEntry(1_760_000_003_000, VoiceEvent.STT_DONE, 23, 2400, 310),
            VoiceLogEntry(1_760_000_009_000, VoiceEvent.TTS_STOP, TtsStop.VOLUME_KEY.id, 3200),
        )
        val text = VoiceLogCodec.encode(list)
        assertEquals(list, VoiceLogCodec.decode(text))
        val body = text.lines().drop(1).filter { it.isNotEmpty() }
        assertEquals(4, body.size)
        assertTrue(body.all { Regex("^-?[0-9]+( -?[0-9]+){4}$").matches(it) })
        assertEquals(VoiceLogCodec.HEADER, text.lines().first())
    }

    @Test fun aDamagedFileLosesOnlyItsBadLines() {
        val text = VoiceLogCodec.HEADER + "\n1 1 1 -1 0\nhello\n2 999 0 0 0\n3 18 1\n4 18 1 0 0\n"
        assertEquals(listOf(VoiceLogEntry(1, VoiceEvent.WAKE, 1, -1, 0), VoiceLogEntry(4, VoiceEvent.SESSION_SHOWN, 1)), VoiceLogCodec.decode(text))
        assertEquals(emptyList<VoiceLogEntry>(), VoiceLogCodec.decode("something else\n1 1 1 1 1\n"))
        assertEquals(emptyList<VoiceLogEntry>(), VoiceLogCodec.decode(null))
        // More than the capacity keeps the newest.
        val many = VoiceLogCodec.HEADER + "\n" + (1..400).joinToString("\n") { "$it 11 100 1 0" }
        assertEquals(101L, VoiceLogCodec.decode(many).first().time)
    }

    @Test fun eventAndCodeIdsAreUniqueAndStable() {
        assertEquals(VoiceEvent.entries.size, VoiceEvent.entries.map { it.id }.toSet().size)
        assertEquals(1, VoiceEvent.WAKE.id)
        assertEquals(27, VoiceEvent.TTS_STOP.id)
        val codes: List<List<LogCode>> = listOf(WakeAction.entries, WakeDrop.entries, ListenWhy.entries, MicKind.entries, AecState.entries, SttEnd.entries,
            ShowSource.entries, BrainFail.entries, DoneCode.entries, PlaybackCode.entries, TtsStop.entries, NoQuestion.entries, NoReply.entries, Cue.entries,
            EchoVoice.entries, KwsRefusal.entries, BargeHitKind.entries, KwsSpotterCode.entries, ScreenData.entries, HelperLeftOut.entries,
            PickOutcome.entries, CheogramRoute.entries, ConvMode.entries, ConvEntry.entries, ConvStop.entries, ConvPause.entries, MtEngine.entries,
            MtVerdict.entries, LangCode.entries, IoCode.entries, MicEvent.entries, ThermalStep.entries, HelperCode.entries, AppShareCode.entries,
            ExportKind.entries, AnthropicStage.entries, AnthropicErrorKind.entries, MtError.entries, ModelCode.entries, BackendCode.entries,
            FallbackWhy.entries, LoadFailure.entries, HelperEnd.entries, HelperFail.entries, AnthropicModelCode.entries, LocationCode.entries,
            NearbyFail.entries, RepairWhen.entries, WeatherFail.entries, WeatherSource.entries, app.heyrobot.core.helpers.HelperKind.entries,
            ProblemStage.entries, TriggerKind.entries, AlarmEnd.entries, SkipWhy.entries, StepCode.entries, StepGo.entries, MissedRun.entries,
            SpeechWay.entries, RoutineCommandCode.entries,
            // 0.8, headset and car mode.
            app.heyrobot.core.headset.EntryKind.entries, app.heyrobot.core.headset.MicRoute.entries, app.heyrobot.core.headset.RouteFail.entries,
            app.heyrobot.core.output.HoldWhy.entries, app.heyrobot.core.output.SessionKind.entries, app.heyrobot.core.headset.Gesture.entries,
            app.heyrobot.core.headset.ButtonAction.entries, app.heyrobot.core.headset.RobotPhase.entries, app.heyrobot.core.headset.AnnounceCode.entries,
            app.heyrobot.core.output.CardVoiceCode.entries, app.heyrobot.core.output.AnswerCode.entries, app.heyrobot.core.car.CarWhy.entries,
            app.heyrobot.core.car.TrustEnd.entries, app.heyrobot.core.car.CallWay.entries,
            app.heyrobot.core.car.ReplyEnd.entries, app.heyrobot.core.car.NavKind.entries, app.heyrobot.core.headset.EndWhy.entries,
            // 0.7, memory and feedback.
            app.heyrobot.core.memory.MemOrigin.entries, app.heyrobot.core.memory.MemKindCode.entries, app.heyrobot.core.memory.MemForget.entries,
            app.heyrobot.core.memory.MemLock.entries, app.heyrobot.core.memory.MemCommandCode.entries, app.heyrobot.core.memory.MemImportCode.entries,
            FeedbackKind.entries, FeedbackEntry.entries)
        for (c in codes) assertEquals(c.toString(), c.size, c.map { it.id }.toSet().size)
        assertEquals(BrainFail.TIMEOUT, BrainFail.entries.single { it.id == 10 })
        for (e in BrainError.entries) assertTrue(e.name, BrainFail.of(e).name == e.name)
        for (r in DoneReason.entries) assertTrue(r.name, DoneCode.of(r) != DoneCode.OTHER)
        // 0.3.1's voice events use 39 to 59 (60 and up are taken by other 0.3.1 work), and the old ones keep theirs.
        for (e in listOf(VoiceEvent.KWS_HIT, VoiceEvent.KWS_SET, VoiceEvent.KWS_REFUSED, VoiceEvent.SPEAK_RUN, VoiceEvent.CAPTURE_INFO, VoiceEvent.BARGE_HIT)) {
            assertTrue(e.name, e.id in 39..59)
        }
        // The "not Claude" and own-answer events (0.3.1, Ask Claude) use 120 to 129.
        for (e in listOf(VoiceEvent.CLAUDE_LEFT_OUT, VoiceEvent.OWN_ANSWER, VoiceEvent.CLAUDE_UNKNOWN, VoiceEvent.CLAUDE_READ)) assertTrue(e.name, e.id in 120..129)
        // 0.4.0's People events use 61 to 79 (80 and up are taken by other 0.4.0 work).
        for (e in listOf(VoiceEvent.CHEOGRAM_PICK, VoiceEvent.CHEOGRAM_HANDOFF)) assertTrue(e.name, e.id in 61..79)
        // 0.5.0's conversation events use 100 to 119, in the order docs/conversation-mode.md 8 lists them.
        val conv = listOf(VoiceEvent.CONV_START, VoiceEvent.CONV_STOP, VoiceEvent.CONV_PAUSE, VoiceEvent.CONV_RESUME, VoiceEvent.CONV_MINUTE,
            VoiceEvent.CONV_LANG, VoiceEvent.CONV_SPEAKERS, VoiceEvent.CONV_TRANSLATE, VoiceEvent.CONV_TTS, VoiceEvent.CONV_SAVE_FAIL,
            VoiceEvent.CONV_RECOVERED, VoiceEvent.CONV_MIC, VoiceEvent.CONV_THERMAL, VoiceEvent.SUMMARY_LOCAL, VoiceEvent.SUMMARY_HELPER,
            VoiceEvent.CONV_EXPORT, VoiceEvent.CONV_DELETE, VoiceEvent.CONV_CHECK, VoiceEvent.MT_LOAD, VoiceEvent.CONV_REHEARD)
        assertEquals((100..119).toList(), conv.map { it.id })
        assertEquals(conv, VoiceEvent.entries.filter { it.id in 100..119 })
        assertEquals(LangCode.FR, LangCode.of("fr"))
        assertEquals(LangCode.UNSURE, LangCode.of(null))
        assertEquals(LangCode.OTHER, LangCode.of("de"))
        // 0.4.1's model choice events use 130 to 139 (100 to 129 are taken by conversation mode and Ask Claude).
        val model = listOf(VoiceEvent.MODEL_CHOSEN, VoiceEvent.MODEL_LOADED, VoiceEvent.MODEL_LOAD_FAILED, VoiceEvent.MODEL_FALLBACK, VoiceEvent.MODEL_SWITCH,
            VoiceEvent.GPU_FALLBACK, VoiceEvent.TPU_FALLBACK, VoiceEvent.MODEL_CHECK)
        for (e in model) assertTrue(e.name, e.id in 130..139)
        // 0.4.7's GPU fallback keeps the id it shipped with: 0.6.0 left 135 free.
        assertEquals(135, VoiceEvent.GPU_FALLBACK.id)
        // 0.8's beta TPU fallback (the Tensor G6 build) is next to it.
        assertEquals(136, VoiceEvent.TPU_FALLBACK.id)
        // 1.2's self-test of the GPU or a beta TPU build, after them.
        assertEquals(137, VoiceEvent.MODEL_CHECK.id)
        assertEquals(model, VoiceEvent.entries.filter { it.id in 130..139 })
        // 0.8's routines use 140 to 159, in the order Routine.kt lists them; never a routine's name or words (the enums
        // and counts only, as the recording API's test checks).
        val routines = listOf(VoiceEvent.ROUTINE_TRIGGER, VoiceEvent.ALARM_END, VoiceEvent.ROUTINE_SKIPPED, VoiceEvent.ROUTINE_RUN,
            VoiceEvent.ROUTINE_STEP, VoiceEvent.BRIEF_SPOKEN, VoiceEvent.READY_TAPPED, VoiceEvent.ROUTINE_SPEECH, VoiceEvent.ROUTINE_ALARM_SET,
            VoiceEvent.ROUTINE_MISSED, VoiceEvent.ROUTINE_COMMAND, VoiceEvent.BRIEF_CONTROL)
        assertEquals((140..151).toList(), routines.map { it.id })
        assertEquals(routines, VoiceEvent.entries.filter { it.id in 140..159 })
        // 0.8's headset mode uses 200 to 209 and car mode 210 to 219 (the design's 150 to 169 went to routines and web search),
        // in the order core output's HandsFree.kt reserved them; 219 is spare.
        val headset = listOf(VoiceEvent.HEADSET_ENTRY, VoiceEvent.HEADSET_ROUTE, VoiceEvent.HEADSET_ROUTE_FAILED, VoiceEvent.OUTPUT_HELD, VoiceEvent.BVRA_ACK,
            VoiceEvent.DEVICE_PRESENCE, VoiceEvent.PRIVATE_ASKED, VoiceEvent.BUTTON, VoiceEvent.ANNOUNCE, VoiceEvent.SPOKEN_CARD)
        assertEquals((200..209).toList(), headset.map { it.id })
        // 212 (CAR_PARKED, 1.0's test builds' park list) is retired, never reused.
        val car = listOf(VoiceEvent.CAR_MODE, VoiceEvent.CAR_TRUSTED, VoiceEvent.CAR_CALL, VoiceEvent.CAR_REPLY, VoiceEvent.CAR_DND,
            VoiceEvent.CAR_NAV, VoiceEvent.CAR_MUSIC, VoiceEvent.HEADSET_END)
        assertEquals((210..218).toList() - 212, car.map { it.id })
        // An old log's 212 line is skipped, never fatal.
        assertEquals(emptyList<VoiceLogEntry>(), VoiceLogCodec.decode(VoiceLogCodec.HEADER + "\n1 212 1 0 0\n"))
        assertEquals(headset + car, VoiceEvent.entries.filter { it.id in 200..219 })
        // 0.7's memory events use 180 to 189; 183 (the search model) and 186 to 187 (transcripts) stay reserved for 7.4 and 7.5.
        val memory = listOf(VoiceEvent.MEM_SAVE, VoiceEvent.MEM_FORGET, VoiceEvent.MEM_RECALL, VoiceEvent.MEM_SUGGEST, VoiceEvent.MEM_LOCKED,
            VoiceEvent.MEM_COMMAND, VoiceEvent.MEM_KEY)
        assertEquals(listOf(180, 181, 182, 184, 185, 188, 189), memory.map { it.id })
        assertEquals(memory.toSet(), VoiceEvent.entries.filter { it.id in 180..189 }.toSet())
        // 1.0's import has no free id there (183, 186 and 187 stay reserved): it is 230, after feedback's 220 to 229.
        assertEquals(230, VoiceEvent.MEM_IMPORT.id)
        assertTrue(VoiceEvent.entries.none { it.name.startsWith("MEM_") && it.id !in 180..189 && it != VoiceEvent.MEM_IMPORT })
        assertEquals("Memory import: 3 items imported as something Robot read · 2 skipped",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_IMPORT, 3, app.heyrobot.core.memory.MemImportCode.CONTENT.id, 2)))
        assertEquals("Memory import: not a memory export",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_IMPORT, 0, app.heyrobot.core.memory.MemImportCode.NOT_EXPORT.id, 0)))
        // Every source, load and command has a code of its own.
        assertEquals(app.heyrobot.core.memory.MemorySource.entries.size,
            app.heyrobot.core.memory.MemorySource.entries.map { app.heyrobot.core.memory.MemOrigin.of(it) }.toSet().size)
        // 0.7's messages use 190 to 199 (180 to 189 are memory's), every code list unique.
        val msg = listOf(VoiceEvent.MSG_READ, VoiceEvent.MSG_SUMMARY, VoiceEvent.MSG_TRANSLATE, VoiceEvent.MSG_REPLY, VoiceEvent.MSG_REPLY_CARD,
            VoiceEvent.MSG_MARK, VoiceEvent.MSG_HIDDEN, VoiceEvent.MSG_LISTENER, VoiceEvent.MSG_GUARD, VoiceEvent.MSG_REFUSED)
        assertEquals((190..199).toList(), msg.map { it.id })
        assertEquals(msg, VoiceEvent.entries.filter { it.id in 190..199 })
        for (c in app.heyrobot.core.messages.MessageLog.codes) assertEquals(c.toString(), c.size, c.map { it.id }.toSet().size)
        assertEquals(app.heyrobot.core.messages.MessageApp.entries.size,
            app.heyrobot.core.messages.MessageApp.entries.map { app.heyrobot.core.messages.MsgAppCode.of(it) }.toSet().size)
        // 0.7's feedback events use 220 to 229 (225 the panel's, the others the main app's Send feedback page).
        val feedback = listOf(VoiceEvent.FEEDBACK_OPENED, VoiceEvent.FEEDBACK_SHARED, VoiceEvent.FEEDBACK_COPIED, VoiceEvent.FEEDBACK_GITHUB,
            VoiceEvent.FEEDBACK_TEXTED, VoiceEvent.FEEDBACK_ASKED)
        assertEquals((220..225).toList(), feedback.map { it.id })
        assertEquals(feedback, VoiceEvent.entries.filter { it.id in 220..229 })
        assertTrue(VoiceEvent.entries.none { it.name.startsWith("FEEDBACK_") && it.id !in 220..229 })
        // 1.4, Robot Files: 270 to 279 (D16), in the plan's order, and nothing else there; each described with codes only.
        val files = listOf(VoiceEvent.FILE_IN, VoiceEvent.FILE_READ, VoiceEvent.FILE_FILED, VoiceEvent.FILE_PARSE, VoiceEvent.FILE_MOVE,
            VoiceEvent.FILE_RULE, VoiceEvent.FILE_FIND, VoiceEvent.FILE_DELETE, VoiceEvent.FILE_KEY, VoiceEvent.FILE_OUT)
        assertEquals((270..279).toList(), files.map { it.id })
        assertEquals(files, VoiceEvent.entries.filter { it.id in 270..279 })
        assertTrue(VoiceEvent.entries.none { it.name.startsWith("FILE_") && it.id !in 270..279 })
        assertEquals("Robot Files: 3 files shared · 1 dangerous", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FILE_IN, 1, 3, 1)))
        assertEquals("Robot Files, filing: waits for you · receipt · total", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FILE_FILED,
            app.heyrobot.core.files.FileFiledCode.ASKED.id, app.heyrobot.core.files.FileKind.RECEIPT.ordinal + 1, app.heyrobot.core.files.FilingWhy.TOTAL.ordinal + 1)))
        for (e in files) assertTrue(e.name, VoiceLogText.describe(VoiceLogEntry(0, e, 1, 1, 1)).startsWith("Robot Files"))
        for (codes in listOf(app.heyrobot.core.files.FileInCode.entries, app.heyrobot.core.files.FileParseCode.entries, app.heyrobot.core.files.FileReadCode.entries,
            app.heyrobot.core.files.FileFiledCode.entries, app.heyrobot.core.files.FileMoveCode.entries, app.heyrobot.core.files.FileDeleteCode.entries,
            app.heyrobot.core.files.FileOutCode.entries))
            assertEquals(codes.size, codes.map { it.id }.toSet().size)
        // F7: a delete you tapped and a copy that left, codes and counts only.
        assertEquals("Robot Files: deleted (its key destroyed) · 2 files", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FILE_DELETE,
            app.heyrobot.core.files.FileDeleteCode.DELETED.id, 2, 0)))
        assertEquals("Robot Files: shared · without its metadata", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FILE_OUT,
            app.heyrobot.core.files.FileOutCode.SHARED.id, 1, 0)))
        // Routines' (140 to 159), memory's (180 to 189), messages' (190 to 199), headset's (200 to 209), car's (210 to 219)
        // and feedback's (220 to 229) events never share an id with each other or with any other event, and no id is used twice.
        assertEquals(VoiceEvent.entries.size, VoiceEvent.entries.map { it.id }.toSet().size)
        // Every model and backend has a code of its own.
        assertEquals(BrainModel.entries.size, BrainModel.entries.map { ModelCode.of(it) }.toSet().size)
        assertEquals(ModelBackend.entries.size, ModelBackend.entries.map { BackendCode.of(it) }.toSet().size)
        assertEquals(4, VoiceEvent.STOP_SUPPRESSED.id)
        assertEquals(31, VoiceEvent.BARGE_TEST.id)
        // MicKind and AecState share KWS_HIT's third number as mic × 10 + aec: both must stay below 10.
        assertTrue(MicKind.entries.all { it.id in 1..9 } && AecState.entries.all { it.id in 1..9 })
    }

    /**
     * Content-free by construction: every way to record an event takes only the fixed event and reason enums and
     * numbers. Nothing that could carry words (String, CharSequence, Any, a collection, a lambda) can be passed.
     */
    @Test fun theRecordingApiTakesOnlyEnumsAndNumbers() {
        val allowed = setOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        fun check(cls: Class<*>, names: Set<String>) {
            val methods = cls.declaredMethods.filter { Modifier.isPublic(it.modifiers) && it.name in names }
            assertTrue("${cls.simpleName} records events", methods.isNotEmpty())
            for (m in methods) for (p in m.parameterTypes) {
                val ok = p in allowed || p == VoiceEvent::class.java || p == VoiceLogEntry::class.java ||
                    (p == Enum::class.java && m.genericParameterTypes.any { it.typeName.contains("LogCode") || it is java.lang.reflect.TypeVariable<*> })
                assertTrue("${cls.simpleName}.${m.name} takes ${p.name}", ok)
            }
        }
        check(VoiceLog::class.java, setOf("add"))
        check(VoiceLogRing::class.java, setOf("add"))
        // The generic overloads are bounded by Enum and LogCode, so only a reason enum fits.
        val generic = VoiceLog::class.java.declaredMethods.filter { it.name == "add" && it.typeParameters.isNotEmpty() }
        assertEquals(2, generic.size)
        for (m in generic) for (tv in m.typeParameters) {
            val bounds = tv.bounds.map { it.typeName }
            assertTrue(bounds.toString(), bounds.any { it.startsWith("java.lang.Enum") } && bounds.any { it == LogCode::class.java.name })
        }
        // An entry holds a time, an event and three numbers: nothing else.
        val fields = VoiceLogEntry::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.type }
        assertEquals(listOf(Long::class.javaPrimitiveType, VoiceEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), fields)
        // And every reason enum's words are fixed by the code.
        for (c in listOf(ListenWhy.entries, TtsStop.entries, NoQuestion.entries)) for (x in c) assertTrue(x.title.isNotBlank())
    }

    @Test fun theFileMirrorsMemoryAndSurvivesARestart() {
        val dir = java.nio.file.Files.createTempDirectory("voicelog").toFile()
        // A log of this test's own: another test's events (the log is the process's) never land in it.
        VoiceLog.resetForTests()
        try {
            val f = File(dir, "voice_log.txt")
            writeAtomic(f, VoiceLogCodec.encode(listOf(VoiceLogEntry(5, VoiceEvent.SCREEN, 1))))
            VoiceLog.clock = { 10 }
            VoiceLog.init(f)
            VoiceLog.add(VoiceEvent.WAKE, 1, -1, WakeAction.OPENED.id)
            VoiceLog.add(VoiceEvent.TTS_STOP, TtsStop.STOP_WORD, 900)
            VoiceLog.add(VoiceEvent.LISTEN_START, ListenWhy.RETRY, MicKind.RECOGNITION, AecState.OFF.id)
            VoiceLog.flush()
            val back = VoiceLogCodec.decode(f.readText())
            assertEquals(4, back.size)
            assertEquals(VoiceLogEntry(5, VoiceEvent.SCREEN, 1), back.first())
            assertEquals(VoiceLogEntry(10, VoiceEvent.TTS_STOP, TtsStop.STOP_WORD.id, 900), back[2])
            assertEquals(VoiceLogEntry(10, VoiceEvent.LISTEN_START, ListenWhy.RETRY.id, MicKind.RECOGNITION.id, AecState.OFF.id), back[3])
            assertEquals(VoiceEvent.LISTEN_START, VoiceLog.entries().first().event)
        } finally {
            // No file for the tests after this one (its folder is about to go), and the system clock again.
            VoiceLog.resetForTests()
            dir.deleteRecursively()
        }
    }

    /** Tests: each starts with an empty log of its own, without a file, while the count goes on. */
    @Test fun aTestsLogHoldsOnlyItsOwnEvents() {
        VoiceLog.resetForTests()
        VoiceLog.add(VoiceEvent.WAKE, 1)
        val total = VoiceLog.total
        VoiceLog.resetForTests()
        assertEquals(emptyList<VoiceLogEntry>(), VoiceLog.entries())
        assertEquals(total, VoiceLog.total)
        VoiceLog.add(VoiceEvent.SCREEN, 2)
        assertEquals(listOf(VoiceEvent.SCREEN), VoiceLog.entries().map { it.event })
        // A file given to an earlier test is gone too: a new init takes this one, and only this log's events go in it.
        val dir = java.nio.file.Files.createTempDirectory("voicelog").toFile()
        try {
            val f = File(dir, "voice_log.txt")
            VoiceLog.init(f)
            VoiceLog.flush()
            assertEquals(listOf(VoiceEvent.SCREEN), VoiceLogCodec.decode(f.readText()).map { it.event })
        } finally {
            VoiceLog.resetForTests()
            dir.deleteRecursively()
        }
    }

    @Test fun eventsReadAsPlainWords() {
        val zone = ZoneOffset.UTC
        val w = VoiceLogEntry(0, VoiceEvent.WAKE, 2, -1, WakeAction.OPENED.id)
        assertEquals("Wake word #2: opened the panel", VoiceLogText.describe(w))
        assertEquals("Wake word #2 (“hey robot”): opened the panel", VoiceLogText.describe(w) { if (it == 2) "hey robot" else null })
        assertEquals("Robot stopped talking: volume key · after 3.2 s", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.TTS_STOP, TtsStop.VOLUME_KEY.id, 3200)))
        assertEquals("Panel opened but no question heard: nothing heard", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.NO_QUESTION, NoQuestion.NO_SPEECH.id)))
        assertEquals("Question heard but no reply: the model failed", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.NO_REPLY, NoReply.FAILED.id)))
        assertEquals("Stopped listening: #99", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.LISTEN_STOP, 99)))
        // Ask Claude kept out, and the model asked again for its own answer: codes and flags only.
        assertEquals("No helper offered to the model: you turned the helper down in this conversation",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_LEFT_OUT, HelperLeftOut.DECLINED.id)))
        assertEquals("No helper offered to the model: a question about the screen: Robot answers it first",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_LEFT_OUT, HelperLeftOut.SCREEN.id)))
        assertEquals("The model handed over to a helper without a word; asked again, it answered itself",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.OWN_ANSWER, 1)))
        assertEquals("The model handed over to a helper without a word; asked again, it still gave no answer",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.OWN_ANSWER, 0)))
        // Where "Something went wrong with Claude" came from, and what Robot read with care: codes and counts only.
        assertEquals("Claude's error came from the SDK reading the stream · data the SDK couldn't read",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_UNKNOWN, AnthropicStage.SDK.id, 0, AnthropicErrorKind.INVALID_DATA.id)))
        assertEquals("Claude's error came from the API's answer · HTTP 418 · an error from the API",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_UNKNOWN, AnthropicStage.HTTP.id, 418, AnthropicErrorKind.API_ERROR.id)))
        assertEquals("Claude's error came from a stream that ended before Claude finished", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_UNKNOWN, AnthropicStage.CLOSED.id)))
        assertEquals("Claude's reply: 2 calls with input in one piece · 1 server call from Claude's code",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_READ, 2, 1, 0)))
        assertEquals("Claude's reply: 1 part left out", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_READ, 0, 0, 1)))
        // What Android sent of the screen: codes and flags only.
        assertEquals(60, VoiceEvent.SCREEN_SHARED.id)
        assertEquals("Screen from Android: text nothing · screenshot not sent (asked with: text, screenshot, assist gesture)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 7, ScreenData.EMPTY.id, ScreenData.NOT_SENT.id)))
        assertEquals("Screen from Android: text shared · screenshot shared (asked with: text, screenshot)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 3, ScreenData.SHARED.id, ScreenData.SHARED.id)))
        // Every event has words.
        for (e in VoiceEvent.entries) assertFalse(e.name, VoiceLogText.describe(VoiceLogEntry(0, e)).isBlank())
        // 0.4.1, Settings › Model: which model, where, why; ids only.
        assertEquals("Model chosen: Smarter (Gemma 4 E4B, CPU) (was Fast (Gemma 4 E2B, TPU))",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MODEL_CHOSEN, ModelCode.SMARTER.id, ModelCode.FAST.id)))
        assertEquals("Model loaded: Smarter (Gemma 4 E4B, CPU) on the CPU in 8.9 s",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MODEL_LOADED, ModelCode.SMARTER.id, BackendCode.CPU.id, 8900)))
        assertEquals("Model didn't load: your own model file (refused by Robot's check of the file)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MODEL_LOAD_FAILED, ModelCode.OWN.id, LoadFailure.NOT_ALLOWED.id)))
        assertEquals("Used Fast (Gemma 4 E2B, TPU) instead of Uncensored (Gemma 4 E4B abliterated, CPU): not downloaded",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MODEL_FALLBACK, ModelCode.UNCENSORED.id, FallbackWhy.NOT_DOWNLOADED.id, ModelCode.FAST.id)))
        assertEquals("Unloaded Fast (Gemma 4 E2B, TPU) to load Smarter (Gemma 4 E4B, CPU)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MODEL_SWITCH, ModelCode.FAST.id, ModelCode.SMARTER.id)))
        // 0.7, memory: kinds, origins and counts, never an item's words.
        assertEquals("Memory: saved something private (you said it), until a set time", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_SAVE,
            app.heyrobot.core.memory.MemOrigin.SAID.id, app.heyrobot.core.memory.MemKindCode.SECRET.id, 1)))
        assertEquals("Memory: forgot 2 items (“forget that”)", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_FORGET, 2, app.heyrobot.core.memory.MemForget.LAST.id)))
        assertEquals("Memory: 3 items went with a question (words, 4 ms)", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_RECALL, 3, 1, 4)))
        assertEquals("Memory couldn't be opened: the phone is locked", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_LOCKED, app.heyrobot.core.memory.MemLock.UNLOCK.id)))
        assertEquals("Memory command: forget something (French) · 1 item", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_COMMAND,
            app.heyrobot.core.memory.MemCommandCode.FORGET.id, LangCode.FR.id, 1)))
        assertEquals("Memory now uses the lock screen's key", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MEM_KEY, 1, 1)))
        // 0.3.1: what the spotter heard before any check, and what came of it.
        val names = { id: Int -> if (id == 2) "hey robot" else null }
        assertEquals("Spotter: “stop” while Robot talked · mic: call (echo cancellation) · echo cancellation on",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_HIT, 0, KwsSpotterCode.TALK.id, MicKind.COMMUNICATION.id * 10 + AecState.ON.id)))
        // Which spotter: the one for her talking wasn't loaded yet.
        assertEquals("Spotter: “stop” while Robot talked, by the idle spotter (the other one wasn't loaded yet) · mic: call (echo cancellation) · echo cancellation on",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_HIT, 0, KwsSpotterCode.IDLE_WHILE_TALKING.id, MicKind.COMMUNICATION.id * 10 + AecState.ON.id)))
        assertEquals("Spotter for while Robot talks ready in 420 ms (waited 1800 ms for another model to load)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_READY, 420, 1800, 1)))
        assertEquals("Spotter for while Robot talks couldn't load: the other one listens while she talks",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_READY, -1, 0, 1)))
        assertEquals("Wake-word model ready in 300 ms", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_READY, 300)))
        assertEquals("“Stop” heard: Robot stopped talking (she said a word like it in the 3 s before)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.STOP_HEARD, 1, 1)))
        assertEquals("“Stop” heard: Robot stopped talking", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.STOP_HEARD, 1)))
        assertEquals("Barge-in test, tricky words: 3 “stop” or wake detections from Robot's own words", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BARGE_TEST, 3, 3)))
        assertEquals("Spotter: wake word #2 (“hey robot”) · mic: speech recognition · echo cancellation off",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_HIT, 2, KwsSpotterCode.IDLE.id, MicKind.RECOGNITION.id * 10 + AecState.OFF.id), names))
        assertEquals("“Stop” ignored: Robot was saying a word like it herself (the phone's voice, 4.2 s into her talking)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.STOP_SUPPRESSED, EchoVoice.PHONE.id, 4200)))
        // A line from before 0.3.1 has no voice.
        assertEquals("“Stop” ignored: Robot was saying a word like it herself", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.STOP_SUPPRESSED)))
        assertEquals("Wake word #2 (“hey robot”) ignored: Robot was saying it herself (Kokoro or Kitten)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.WAKE_IGNORED, WakeDrop.ECHO.id, 2, EchoVoice.MODEL.id), names))
        assertEquals("Wake word #2 ignored: within 2 s of the last one", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.WAKE_IGNORED, WakeDrop.DEBOUNCE.id, 2)))
        assertEquals("Wake phrases set: 2 listened for · 1 the model can't spell", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_SET, 2, 1)))
        assertEquals("Keyword set refused: the keyword model refused “stop”, stop by voice is off (“stop”)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_REFUSED, KwsRefusal.STOP.id, 0, 1)))
        assertEquals("Keyword set refused: the keyword model refused the whole set, nothing is heard until it changes (2 wake phrases and “stop”)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.KWS_REFUSED, KwsRefusal.SET.id, 2, 1)))
        assertEquals("While Robot talked: speech gate open 1.5 s · mic level -58 dBFS · listened for “stop” 100% of the time",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SPEAK_RUN, 1500, 58, 100)))
        assertEquals("Capture opened: not silenced · microphone at 48.0 kHz · echo cancellation on · noise suppression on · Android reports no effects",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CAPTURE_INFO, 0, 480, CaptureFx.AEC or CaptureFx.NS)))
        assertEquals("Capture opened: silencing unknown · no echo cancellation · Android reported nothing",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CAPTURE_INFO, -1, -1, CaptureFx.NO_CONFIG)))
        assertEquals("Capture opened: silenced by Android · microphone at 16.0 kHz · no echo cancellation · Android reports echo cancellation and noise suppression",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CAPTURE_INFO, 1, 160, CaptureFx.SYSTEM_AEC or CaptureFx.SYSTEM_NS)))
        assertEquals("Barge-in test heard wake word #2 (“hey robot”) 3.1 s in: counted",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BARGE_HIT, 2, 3100, BargeHitKind.COUNTED.id), names))
        assertEquals("Barge-in test heard “stop”: before her voice started", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BARGE_HIT, 0, -1, BargeHitKind.EARLY.id)))
        assertEquals("Barge-in test, count run: 4 “stop” or wake words heard", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BARGE_TEST, 2, 4)))
        // 0.5.0: conversation mode, numbers and codes only.
        assertEquals("Conversation started: transcribe (the Quick Settings tile) · phone locked",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_START, ConvMode.TRANSCRIBE.id, ConvEntry.TILE.id, 1)))
        assertEquals("Conversation stopped: storage nearly full · 42 min · about 310 lines",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_STOP, ConvStop.DISK.id, 42, 31)))
        assertEquals("Conversation resumed after a phone call (95 s)", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_RESUME, ConvPause.CALL.id, 95)))
        assertEquals("Line translated by the fast engine in 41 ms: kept, a repeat removed",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_TRANSLATE, MtEngine.BERGAMOT.id, 41, MtVerdict.REPEAT.id)))
        assertEquals("Fast translation loaded in 380 ms (2 directions)", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MT_LOAD, 380, 1, 2)))
        assertEquals("Fast translation didn't load (12 ms): the fast engine isn't in this build",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MT_LOAD, 12, 0, MtError.NO_ENGINE.id)))
        assertEquals("Phone heat severe: fast translation only", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_THERMAL, 3, ThermalStep.FAST_ONLY.id)))
        assertEquals("Summary with the Claude app: about 6100 words · copied for you to paste",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SUMMARY_HELPER, HelperCode.CLAUDE_APP.id, 61, AppShareCode.CLIPBOARD.id)))
        // 0.7, messages: counts and codes, never a name or a word.
        assertEquals("Read 3 messages from Cheogram (phone locked)", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_READ, 1, 3, 1)))
        assertEquals("Read 1 message from several apps", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_READ, 0, 1, 0)))
        assertEquals("Summary of 4 conversations (23 messages) in 11 s", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_SUMMARY, 4, 23, 11)))
        assertEquals("Message translated to English by Gemma in 900 ms", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_TRANSLATE, 2, 1, 900)))
        assertEquals("Reply in Signal through the app's reply action: sent", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_REPLY, 2, 1, 1)))
        assertEquals("Reply card: Send tapped · translated · group", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_REPLY_CARD, 1, 1, 1)))
        assertEquals("After reading, Cheogram: marked as read", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_MARK, 1, 1, 0)))
        assertEquals("Message hidden from Robot in Signal: the app shows only “New message”", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_HIDDEN, 2, 2, 0)))
        assertEquals("Notification listener connected · 5 conversations held", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_LISTENER, 1, 5, 0)))
        assertEquals("Messages guard: a message's own words came back as a request: nothing done", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_GUARD, 3, 0, 0)))
        assertEquals("Messages not read: locked: unlock first", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MSG_REFUSED, 2, 0, 0)))
        assertEquals("Summary with Claude API: about 1200 words · finished",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SUMMARY_HELPER, HelperCode.CLAUDE_API.id, 12, HelperEnd.END.id)))
        assertEquals("Transcription check (French): 12% of words wrong over 20 lines",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_CHECK, 12, 20, LangCode.FR.id)))
        assertEquals("Segment heard again in two halves (4.2 s of speech): 3 words, then 11",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_REHEARD, 4200, 3, 11)))
        assertEquals("Languages this minute: 8 French · 5 English · 1 not sure", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CONV_LANG, 8, 5, 1)))
        // 0.8: routines, codes and counts only.
        assertEquals("Routine trigger: an alarm turned off · 1 routine started · 1 not started",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_TRIGGER, TriggerKind.ALARM.id, 1, 1)))
        assertEquals("Clock alarm ended: snoozed after 2 min", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ALARM_END, AlarmEnd.SNOOZED.id, 2)))
        assertEquals("Routine not run after the first unlock: already ran today",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_SKIPPED, SkipWhy.ALREADY_RAN.id, TriggerKind.UNLOCK.id)))
        // 0.8 review: an ALARM_ALERT Clock's next alarm didn't vouch for.
        assertEquals("Routine not run after an alarm turned off: not Clock's own alarm, ignored",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_SKIPPED, SkipWhy.NOT_CLOCKS_ALARM.id, TriggerKind.ALARM.id)))
        assertEquals("Routine ran: 3 steps · 1 left for you · 2.5 s", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_RUN, 3, 1, 2500)))
        assertEquals("Routine step: open app · ready card", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_STEP, StepCode.OPEN_APP.id, StepGo.READY.id)))
        assertEquals("Routine step: weather · ran · didn't work",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_STEP, StepCode.WEATHER.id, StepGo.RUN.id, 1)))
        assertEquals("Morning brief: 4 lines said · 3 kept for the unlock · 212 characters", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BRIEF_SPOKEN, 4, 3, 212)))
        assertEquals("Ready card tapped: maps search", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.READY_TAPPED, StepCode.MAPS_SEARCH.id)))
        assertEquals("Ready card tapped: a routine that didn't run at its time", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.READY_TAPPED, 0)))
        assertEquals("Routine speech: Android refused it: a notification to tap · phone locked",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_SPEECH, SpeechWay.NOTIFICATION.id, 1)))
        assertEquals("Routine alarm set for 540 min from now (exact) · 2 routines", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_ALARM_SET, 540, 1, 2)))
        assertEquals("Routine alarm: none to set", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_ALARM_SET, -1)))
        assertEquals("Routine time missed by 95 min: offered on a Ready card", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_MISSED, MissedRun.OFFER.id, 95)))
        assertEquals("Routine command: make a routine · done", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.ROUTINE_COMMAND, RoutineCommandCode.CREATE.id, 1)))
        assertEquals("While a routine spoke: repeat", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BRIEF_CONTROL, 3)))
        assertEquals("now", VoiceLogText.ago(1_000))
        assertEquals("12 s ago", VoiceLogText.ago(12_000))
        assertEquals("3 min ago", VoiceLogText.ago(200_000))
        assertEquals("2 h ago", VoiceLogText.ago(7_300_000))
        assertEquals("1 d ago", VoiceLogText.ago(90_000_000))
        val now = 1_760_000_060_000L
        val text = VoiceLogText.plain(listOf(VoiceLogEntry(now - 12_000, VoiceEvent.SCREEN, 1)), now, "Robot voice log", zone)
        assertEquals("Robot voice log · 1 events, newest first · copied Oct 9 08:54:20\nOct 9 08:54:08  (12 s ago)  Screen on\n", text)
        // 0.7, Send feedback: codes and counts only, never the words.
        assertEquals("Send feedback opened from the voice log: bug report",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_OPENED, FeedbackEntry.VOICE_LOG.id, FeedbackKind.BUG.id)))
        assertEquals("Feedback (bug report) handed to the share sheet: about 3400 characters · with the problem report, the voice log, the settings summary",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_SHARED, FeedbackKind.BUG.id, 34, 7)))
        assertEquals("Feedback (idea) copied: about 300 characters · with no diagnostics",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_COPIED, FeedbackKind.IDEA.id, 3, 0)))
        assertEquals("Feedback (bug report) opened as a GitHub issue in the browser: about 6900 characters (cut to fit the link)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_GITHUB, FeedbackKind.BUG.id, 69, 1)))
        assertEquals("Feedback (other) opened in the SMS app with your number: about 200 characters · with the settings summary",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_TEXTED, FeedbackKind.OTHER.id, 2, 4)))
        assertEquals("Feedback asked for by voice: idea · with words after the phrase",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_ASKED, FeedbackKind.IDEA.id, 1, 0)))
        assertEquals("Feedback asked for by voice: bug report · phone locked: nothing opened",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.FEEDBACK_ASKED, FeedbackKind.BUG.id, 0, 1)))
    }

    /**
     * 0.7: the voice log's Copy never holds a wake phrase's words, only its number (the screen alone shows the words beside
     * it): its text is made without any way to name a phrase.
     */
    @Test fun copyGivesWakePhrasesByNumberOnly() {
        val now = 1_760_000_060_000L
        val log = listOf(VoiceLogEntry(now - 2_000, VoiceEvent.WAKE, 2, -1, WakeAction.OPENED.id), VoiceLogEntry(now - 1_000, VoiceEvent.KWS_HIT, 2,
            KwsSpotterCode.IDLE.id, MicKind.RECOGNITION.id * 10 + AecState.OFF.id), VoiceLogEntry(now, VoiceEvent.WAKE_IGNORED, WakeDrop.DEBOUNCE.id, 2))
        val text = VoiceLogText.plain(log, now, "Robot voice log", ZoneOffset.UTC)
        assertTrue(text, text.contains("Wake word #2: opened the panel") && text.contains("Spotter: wake word #2 ·") && text.contains("Wake word #2 ignored"))
        assertFalse(text.contains("“"))
        for (m in VoiceLogText::class.java.declaredMethods.filter { it.name == "plain" }) {
            assertFalse(m.toGenericString(), m.parameterTypes.any { kotlin.jvm.functions.Function1::class.java.isAssignableFrom(it) })
        }
    }
}
