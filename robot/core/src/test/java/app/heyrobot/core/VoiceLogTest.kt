package app.heyrobot.core

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
            ScreenData.entries)
        for (c in codes) assertEquals(c.toString(), c.size, c.map { it.id }.toSet().size)
        assertEquals(BrainFail.TIMEOUT, BrainFail.entries.single { it.id == 10 })
        for (e in BrainError.entries) assertTrue(e.name, BrainFail.of(e).name == e.name)
        for (r in DoneReason.entries) assertTrue(r.name, DoneCode.of(r) != DoneCode.OTHER)
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
            assertEquals(VoiceLogEntry(5, VoiceEvent.SCREEN, 1), back.first())
            assertEquals(VoiceLogEntry(10, VoiceEvent.TTS_STOP, TtsStop.STOP_WORD.id, 900), back[2])
            assertEquals(VoiceLogEntry(10, VoiceEvent.LISTEN_START, ListenWhy.RETRY.id, MicKind.RECOGNITION.id, AecState.OFF.id), back[3])
            assertEquals(VoiceEvent.LISTEN_START, VoiceLog.entries().first().event)
        } finally {
            VoiceLog.clock = System::currentTimeMillis
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
        // What Android sent of the screen: codes and flags only.
        assertEquals(60, VoiceEvent.SCREEN_SHARED.id)
        assertEquals("Screen from Android: text nothing · screenshot not sent (asked with: text, screenshot, assist gesture)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 7, ScreenData.EMPTY.id, ScreenData.NOT_SENT.id)))
        assertEquals("Screen from Android: text shared · screenshot shared (asked with: text, screenshot)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 3, ScreenData.SHARED.id, ScreenData.SHARED.id)))
        // Every event has words.
        for (e in VoiceEvent.entries) assertFalse(e.name, VoiceLogText.describe(VoiceLogEntry(0, e)).isBlank())
        assertEquals("now", VoiceLogText.ago(1_000))
        assertEquals("12 s ago", VoiceLogText.ago(12_000))
        assertEquals("3 min ago", VoiceLogText.ago(200_000))
        assertEquals("2 h ago", VoiceLogText.ago(7_300_000))
        assertEquals("1 d ago", VoiceLogText.ago(90_000_000))
        val now = 1_760_000_060_000L
        val text = VoiceLogText.plain(listOf(VoiceLogEntry(now - 12_000, VoiceEvent.SCREEN, 1)), now, "Robot voice log", zone)
        assertEquals("Robot voice log · 1 events, newest first · copied Oct 9 08:54:20\nOct 9 08:54:08  (12 s ago)  Screen on\n", text)
    }
}
