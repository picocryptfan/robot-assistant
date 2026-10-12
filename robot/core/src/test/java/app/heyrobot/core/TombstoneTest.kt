package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 0.7: a native crash's tombstone (Android's protobuf, tombstone.proto) read for a problem report: the signal and its code,
 * the abort message only when it can't hold a path or your words, and the crashing thread's top 16 frames as library names
 * with offsets and symbols. The fixture below is written by hand, field by field, with tombstone.proto's numbers, and holds
 * everything a tombstone may: other threads, registers, memory around the fault, memory maps, open files, log lines and the
 * command line, with paths and words of yours in them. None of that comes out.
 */
class TombstoneTest {
    /** A protobuf writer for the fixture: varints and length-delimited fields, as the wire format has them. */
    private class Proto {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7f.inv().toLong() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
        fun int(field: Int, v: Long) = apply { varint((field shl 3 or 0).toLong()); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { varint((field shl 3 or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun msg(field: Int, m: Proto) = bytes(field, m.out.toByteArray())
        fun fixed64(field: Int, v: Long) = apply { varint((field shl 3 or 1).toLong()); for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val secret = "/storage/emulated/0/Documents/jennifer-smith-diary.txt"

    // message BacktraceFrame { uint64 rel_pc = 1; uint64 pc = 2; uint64 sp = 3; string function_name = 4; uint64 function_offset = 5;
    //   string file_name = 6; uint64 file_map_offset = 7; string build_id = 8; }
    private fun frame(relPc: Long, file: String, fn: String?, off: Long) = Proto().int(1, relPc).int(2, 0x7b00000000L + relPc).int(3, 0x7ff0001000L)
        .apply { fn?.let { str(4, it) } }.int(5, off).str(6, file).int(7, 0).str(8, "0123456789abcdef")

    // message Thread { int32 id = 1; string name = 2; repeated Register registers = 3; repeated BacktraceFrame current_backtrace = 4;
    //   repeated MemoryDump memory_dump = 5; ... }
    private fun thread(id: Int, name: String, frames: List<Proto>) = Proto().int(1, id.toLong()).str(2, name)
        .msg(3, Proto().str(1, "x0").int(2, 0x6a656e6e69666572L)).msg(3, Proto().str(1, "lr").int(2, 0x7b00001234L))
        .apply { frames.forEach { msg(4, it) } }
        .msg(5, Proto().str(1, "x0").str(2, "[anon:scudo]").int(3, 0x7c00000000L).bytes(4, "hey jennifer smith".toByteArray()))

    private fun fixture(abort: String?, crashingFrames: Int = 20): ByteArray {
        val frames = listOf(
            frame(0x5b2c4, "/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164),
            frame(0x1a2b3c, "/data/app/~~Xy==/app.heyrobot-Ab==/lib/arm64/liblitertlm_jni.so", "litert::lm::Engine::Run(std::string const&)", 88),
            frame(0x4400, "/data/app/~~Xy==/app.heyrobot-Ab==/base.apk!libLiteRtDispatch_GoogleTensor.so", null, 0),
            frame(0x10, secret, "read_model", 4),
            frame(0x20, "[anon:dalvik-jit-code-cache]", "app.heyrobot.brain.GemmaEngine.run", 12),
            frame(0x30, "/system/lib64/libOpenCL-pixel.so", "clEnqueueNDRangeKernel", 0),
        ) + List(maxOf(0, crashingFrames - 6)) { frame(0x1000L + it, "/system/lib64/libart.so", "art_quick_invoke_stub", 8) }
        // message Signal { int32 number = 1; string name = 2; int32 code = 3; string code_name = 4; bool has_sender = 5; ...
        //   bool has_fault_address = 8; uint64 fault_address = 9; }
        val signal = Proto().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(5, 0).int(8, 1).int(9, 0x6a656e)
        val crashing = thread(4242, "robot-brain", frames)
        val other = thread(4000, "jennifer's thread", listOf(frame(0x99, "/system/lib64/libother.so", "other_secret_fn", 1)))
        // message Tombstone { Architecture arch = 1; string build_fingerprint = 2; string revision = 3; string timestamp = 4;
        //   uint32 pid = 5; uint32 tid = 6; uint32 uid = 7; string selinux_label = 8; repeated string command_line = 9;
        //   Signal signal_info = 10; string abort_message = 14; repeated Cause causes = 15; map<uint32, Thread> threads = 16;
        //   repeated MemoryMapping memory_mappings = 17; repeated LogBuffer log_buffers = 18; repeated FD open_fds = 19;
        //   uint32 process_uptime = 20; }
        return Proto().int(1, 1).str(2, "google/tegu/tegu:16/BP2A.250805.005/2025081900:user/release-keys").str(3, "0").str(4, "2026-10-11 14:05:03")
            .int(5, 4200).int(6, 4242).int(7, 10123).str(8, "u:r:untrusted_app:s0").str(9, "app.heyrobot:brain").str(9, secret)
            .msg(10, signal)
            .apply { abort?.let { str(14, it) } }
            .msg(15, Proto().str(1, "null pointer dereference of jennifer"))
            .msg(16, Proto().int(1, 4000).msg(2, other))
            .msg(16, Proto().int(1, 4242).msg(2, crashing))
            .msg(17, Proto().int(1, 0x7b00000000L).int(2, 0x7b00100000L).str(7, secret))
            .msg(18, Proto().str(1, "main").msg(2, Proto().str(7, "hey jennifer smith, text Anna I'm late")))
            .msg(19, Proto().int(1, 42).str(2, secret))
            .int(20, 31)
            .toByteArray()
    }

    @Test fun aNativeCrashReadsAsSignalCodeAndLibraryFrames() {
        val c = Tombstone.read(fixture("Check failed: status.ok() Failed to create engine").inputStream())!!
        assertEquals(11, c.signal)
        assertEquals("SIGSEGV", c.signalName)
        assertEquals(1, c.code)
        assertEquals("SEGV_MAPERR", c.codeName)
        assertEquals("Check failed: status.ok() Failed to create engine", c.abortMessage)
        assertEquals(20, c.framesTotal)
        assertEquals(Tombstone.FRAMES, c.frames.size)
        assertEquals(listOf(
            "#00 libc.so +0x5b2c4 (abort+164)",
            "#01 liblitertlm_jni.so +0x1a2b3c (litert::lm::Engine::Run(std::string const&)+88)",
            "#02 libLiteRtDispatch_GoogleTensor.so +0x4400",
            "#03 (not a library) +0x10 (read_model+4)",
            "#04 (not a library) +0x20 (app.heyrobot.brain.GemmaEngine.run+12)",
            "#05 libOpenCL-pixel.so +0x30 (clEnqueueNDRangeKernel)",
            "#06 libart.so +0x1000 (art_quick_invoke_stub+8)",
        ), c.frames.take(7))
        val text = c.lines().joinToString("\n")
        assertEquals("Native crash: SIGSEGV (11), code SEGV_MAPERR (1)", c.lines().first())
        assertTrue(text, text.contains("Backtrace, the crashing thread's first 16 of 20 frames:"))
        // Nothing but that: not the other thread, a register, the memory, a map, an open file, the log or the command line.
        for (w in listOf("jennifer", "Jennifer", "Anna", "storage", "diary", "/data/app", "app.heyrobot-Ab", "other_secret_fn", "libother", "6a656e",
            "scudo", "untrusted_app", "BP2A", "app.heyrobot:brain", "dereference")) assertFalse(w, text.contains(w))
    }

    @Test fun anAbortMessageWithAPathOrWordsIsOnlySaidToBeThere() {
        for (bad in listOf("open($secret) failed", "Tokenizer failed on \"text Anna I'm late\"", "FORTIFY: read: count 9223372036854775807 > SSIZE_MAX",
            "Sending to jennifer@example.com", "Échec du chargement", "line one\nline two", "x".repeat(Tombstone.MAX_ABORT + 1))) {
            val c = Tombstone.decode(fixture(bad))!!
            assertNull(bad, c.abortMessage)
            assertTrue(c.abortPresent)
            assertTrue(c.lines().contains("Abort message: present (left out: it may hold a path or words)"))
        }
        val none = Tombstone.decode(fixture(null))!!
        assertFalse(none.abortPresent)
        assertTrue(none.lines().contains("Abort message: none"))
        assertEquals("FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x7b4a2c10)",
            Tombstone.safeAbort("FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x7b4a2c10)"))
    }

    @Test fun aCutOrForeignFileNeverFails() {
        val full = fixture("x", crashingFrames = 8)
        // Cut anywhere: whatever whole fields came first, never an exception.
        for (n in listOf(0, 1, 5, 40, full.size / 3, full.size / 2, full.size - 1)) Tombstone.decode(full.copyOf(n))
        assertNull(Tombstone.decode(ByteArray(0)))
        assertNull(Tombstone.decode("not a tombstone at all, just text".toByteArray()))
        // Fewer frames than the cap: all of them.
        assertEquals(8, Tombstone.decode(full)!!.frames.size)
    }

    @Test fun libraryNamesOnly() {
        assertEquals("liblitertlm_jni.so", Tombstone.library("/data/app/~~a==/app.heyrobot-b==/lib/arm64/liblitertlm_jni.so"))
        assertEquals("libfoo.so", Tombstone.library("/data/app/x/base.apk!libfoo.so"))
        assertEquals("linker64", Tombstone.library("/apex/com.android.runtime/bin/linker64"))
        assertEquals("(not a library)", Tombstone.library("/storage/emulated/0/Download/gemma.litertlm"))
        assertEquals("(not a library)", Tombstone.library("/data/user/0/app.heyrobot/files/models/my secret.so"))
        assertEquals("(not a library)", Tombstone.library(""))
        assertNull(Tombstone.symbol("\u0000\u0001"))
        assertEquals("Foo::bar()", Tombstone.symbol("Foo::bar()\n"))
    }
}
