package app.heyrobot

import app.heyrobot.core.ControlText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.0.1: the contract between LlamaEngine (Kotlin, :brain) and robot_llama.cpp (the isolated :llama process) for a GGUF
 * prompt's control tokens. Kotlin sends Robot's own system prompt first and as it is, and every other message with the
 * model's control tokens broken (core ControlText); the native side is the backstop: it reads each other message alone,
 * with and without its special tokens, and refuses the whole prompt (PREP_CONTROL_TEXT) when they differ, so a control
 * token's text Kotlin didn't know (shorter than 2 bytes, longer than 1,024, or any it missed) never becomes a token. The
 * library itself runs only on the phone (Robolectric can't load arm64 code): this checks both sides' sources agree.
 */
class LlamaPromptContractTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val cpp = File(root, "brain/src/main/cpp/robot_llama.cpp").readText()
    private val ipc = File(root, "brain/src/main/java/app/heyrobot/brain/LlamaIpc.kt").readText()
    private val engine = Source.strip(File(root, "brain/src/main/java/app/heyrobot/brain/LlamaEngine.kt").readText(), keepStrings = true, kotlin = true)

    private fun constants(text: String, pattern: Regex): Map<String, Int> = pattern.findAll(text).associate { it.groupValues[1] to it.groupValues[2].toInt() }

    @Test fun bothSidesNameTheSameCodes() {
        val native = constants(cpp, Regex("""constexpr jint (PREP_[A-Z_]+) = (-?\d+);"""))
        val kotlin = constants(ipc, Regex("""const val (PREP_[A-Z_]+) = (-?\d+)"""))
        assertEquals(native, kotlin)
        assertEquals(-4, kotlin["PREP_CONTROL_TEXT"])
        assertEquals(kotlin.size, kotlin.values.toSet().size)
    }

    @Test fun theNativeSideReadsEveryMessageButTheSystemPromptAloneFirst() {
        val prepare = cpp.substringAfter("Java_app_heyrobot_brain_LlamaNative_prepare(").substringBefore("\nJNIEXPORT")
        // Before the template is applied: a refused prompt is never formatted or tokenized as a whole.
        val check = prepare.indexOf("controlText(")
        assertTrue(check > 0)
        assertTrue(check < prepare.indexOf("common_chat_templates_apply("))
        assertTrue(prepare.contains("""if (m.role != "system" && controlText(r->vocab, m.content)) return PREP_CONTROL_TEXT;"""))
        // The check: the content alone (no BOS), with and without control and unknown tokens' texts read as tokens.
        val fn = cpp.substringAfter("static bool controlText(").substringBefore("\n}\n")
        assertTrue(fn, fn.contains("tokens(vocab, text, true)") && fn.contains("tokens(vocab, text, false)") && fn.contains("!="))
        val tok = cpp.substringAfter("static std::vector<llama_token> tokens(").substringBefore("\n}\n")
        assertTrue(tok, tok.contains("false, special)"))
    }

    @Test fun theKotlinSideSendsTheSystemPromptAsItIsAndDefusesTheRest() {
        val build = engine.substringAfter("fun add(role: String, text: String)").substringBefore("tokens = try")
        assertTrue(build, build.contains("""add("system", system)"""))
        assertTrue(build, build.contains("""add(if (t.role == PromptRole.USER) "user" else "assistant", controls.defuse(t.text))"""))
        assertTrue(build, build.contains("""add("user", controls.defuse(question))"""))
        assertEquals(3, Regex("""\badd\(""").findAll(build).count())
        // A refusal is the turn's failure, never a prompt to shorten.
        assertTrue(engine.contains("LlamaIpc.PREP_CONTROL_TEXT -> \"control\""))
        // What Kotlin can't break, the native side refuses: ControlText leaves a one-character token's text alone.
        assertEquals("a|b", ControlText(listOf("|")).defuse("a|b"))
        assertFalse(ControlText(listOf("<x>")).defuse("<x>").contains("<x>"))
    }
}
