// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * sherpa-onnx v1.13.8's JNI (jni/offline-tts.cc CallCallback) calls the TTS callback through
 * GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;") on the object's own class. Robot 0.1.2 passed Kotlin
 * lambdas, which only have the erased invoke(Object): every downloaded voice was silent. These tests pin the
 * class that replaces them and the rule that every call into sherpa's TTS goes through it.
 */
class TtsCallbackTest {
    private fun descriptor(c: Class<*>): String = when {
        c.isArray -> "[" + descriptor(c.componentType)
        c == Void.TYPE -> "V"
        c == java.lang.Float.TYPE -> "F"
        c == java.lang.Integer.TYPE -> "I"
        c.isPrimitive -> throw IllegalArgumentException(c.name)
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    private fun descriptor(m: Method) = "(" + m.parameterTypes.joinToString("") { descriptor(it) } + ")" + descriptor(m.returnType)

    @Test fun declaresTheMethodTheJniLooksUp() {
        val m = TtsCallback::class.java.getDeclaredMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, m.returnType)
        assertEquals("([F)Ljava/lang/Integer;", descriptor(m))
        assertTrue(Modifier.isPublic(m.modifiers))
        assertFalse(Modifier.isStatic(m.modifiers))
        // A real method, not only the bridge javac adds for the erased Function1.invoke(Object).
        assertFalse(m.isBridge)
        assertFalse(m.isSynthetic)
        // JNI GetMethodID resolves on the runtime class, which is this one (final: no subclass can differ).
        assertTrue(Modifier.isFinal(TtsCallback::class.java.modifiers))
        assertTrue(Modifier.isPublic(TtsCallback::class.java.modifiers))
        // Tts.kt's external generateWithConfigImpl takes ((FloatArray) -> Int)?, i.e. a Function1.
        assertTrue(kotlin.jvm.functions.Function1::class.java.isAssignableFrom(TtsCallback::class.java))
        val descriptors = TtsCallback::class.java.declaredMethods.filter { it.name == "invoke" }.map { descriptor(it) }.toSet()
        assertTrue(descriptors.toString(), "([F)Ljava/lang/Integer;" in descriptors)
    }

    /** Why the class exists: a Kotlin lambda of the same type has no such method (it would fail the JNI lookup). */
    @Test fun aKotlinLambdaLacksTheMethod() {
        val lambda: (FloatArray) -> Int = { 1 }
        val specialised = lambda.javaClass.declaredMethods.filter { it.name == "invoke" && !it.isBridge }.map { descriptor(it) }
        assertFalse(specialised.toString(), "([F)Ljava/lang/Integer;" in specialised)
    }

    @Test fun delegatesToTheSink() {
        val seen = mutableListOf<Int>()
        var keepGoing = true
        val cb = TtsCallback { s -> seen += s.size; keepGoing }
        assertEquals(1, cb.invoke(FloatArray(3)))
        keepGoing = false
        assertEquals(0, cb.invoke(FloatArray(5)))
        assertEquals(listOf(3, 5), seen)
        assertNull(cb.failure())
        // As sherpa sees it: a (FloatArray) -> Int.
        val f: (FloatArray) -> Int = cb
        assertEquals(0, f(FloatArray(1)))
    }

    @Test fun aThrowingSinkStopsWithoutThrowingIntoNativeCode() {
        val boom = IllegalStateException("boom")
        var calls = 0
        val cb = TtsCallback { calls++; throw boom }
        assertEquals(0, cb.invoke(FloatArray(2)))
        assertSame(boom, cb.failure())
        // Once failed it stays stopped without calling the sink again.
        assertEquals(0, cb.invoke(FloatArray(2)))
        assertEquals(1, calls)
    }

    @Test fun anInterruptKeepsTheThreadsFlag() {
        val cb = TtsCallback { throw InterruptedException() }
        try {
            assertEquals(0, cb.invoke(FloatArray(1)))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Every place Robot hands sherpa-onnx something native code calls back goes through TtsCallback. */
    @Test fun everySherpaCallbackGoesThroughTtsCallback() {
        val root = File("src/main/java")
        assertTrue("run from the :voice module directory", root.isDirectory)
        val ours = File(root, "app/heyrobot").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
        val code = ours.associateWith { f ->
            f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "")
        }
        val calls = code.flatMap { (f, c) -> Regex("""\.generateWith\w*Callback\s*\(([^\n]*)""").findAll(c).map { f.name to it.groupValues[1] }.toList() }
        // One call site, given the TtsCallback object (never a trailing lambda or a function reference).
        assertEquals(calls.toString(), 1, calls.size)
        val (file, args) = calls.single()
        assertEquals("SherpaTextToSpeech.kt", file)
        assertTrue(args, Regex("""^[^{}:]*\bcb\)\s*$""").matches(args))
        assertTrue(Regex("""val cb = TtsCallback\(sink\)""").containsMatchIn(code.entries.single { it.key.name == file }.value))
        // sherpa's other native callbacks (speaker diarization's (IIJ)Ljava/lang/Integer;) are not shipped.
        val sherpa = File(root, "com/k2fsa/sherpa/onnx").list()!!.toSet()
        assertFalse(sherpa.toString(), "OfflineSpeakerDiarization.kt" in sherpa)
        // In the shipped sherpa API only OfflineTts takes a function that native code calls.
        val nativeFunctionParams = File(root, "com/k2fsa/sherpa/onnx").listFiles()!!.sortedBy { it.name }.flatMap { f ->
            externalParams(f.readText()).filter { it.second.contains("->") }.map { "${f.name}:${it.first}" }
        }
        assertEquals(listOf("Tts.kt:generateWithCallbackImpl", "Tts.kt:generateWithConfigImpl"), nativeFunctionParams)
    }

    /** (name, parameter list) of each `external fun`, with nested parentheses (function types) balanced. */
    private fun externalParams(src: String): List<Pair<String, String>> = Regex("""external fun (\w+)\(""").findAll(src).map { m ->
        var depth = 1
        var i = m.range.last + 1
        while (i < src.length && depth > 0) {
            when (src[i]) { '(' -> depth++; ')' -> depth-- }
            i++
        }
        m.groupValues[1] to src.substring(m.range.last + 1, i - 1)
    }.toList()
}
