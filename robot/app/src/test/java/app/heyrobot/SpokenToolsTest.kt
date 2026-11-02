package app.heyrobot

import app.heyrobot.core.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The spoken tests' tools (tools/spoken/): they stay out of every release, their safety lists stay in step with the code,
 * and their own tests pass. verify_apk.sh checks the built APK; this fails sooner, on the sources.
 */
class SpokenToolsTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val spoken = File(root, "tools/spoken")
    private val config by lazy { File(spoken, "spoken.json").readText() }
    private val modules = listOf("app", "brain", "core", "helpers", "mt", "voice")

    private fun list(name: String): List<String> {
        val body = Regex(""""$name"\s*:\s*\[([^\]]*)\]""").find(config)?.groupValues?.get(1) ?: error("no $name in spoken.json")
        return Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** Every tool's card is known to be harmless or risky, never both: the runner and the receiver fail closed on any other. */
    @Test fun everyToolsCardIsClassed() {
        val harmless = list("harmlessCards")
        val risky = list("riskyCards")
        assertEquals("on both lists", emptyList<String>(), harmless.intersect(risky.toSet()).toList())
        val unclassed = ToolName.entries.map { it.id }.filter { it !in harmless && it !in risky }
        assertEquals("add each new tool to harmlessCards or riskyCards in tools/spoken/spoken.json", emptyList<String>(), unclassed)
        // A card with no tool (a skill's, a list's) reports "other".
        assertTrue("other" in risky)
        assertEquals("not tools any more", emptyList<String>(),
            (harmless + risky).filter { it != "other" && it !in ToolName.entries.map { t -> t.id } })
        // What a card does that sends, calls or deletes can never be listed as harmless.
        for (id in listOf("send_text", "draft_email", "call", "forget", "free_storage", "termux_command", "termux_script", "ask_claude", "connection", "hand_off", "ask_robot"))
            assertTrue("$id must be risky", id in risky)
    }

    /** The short list of settings holds real ones, and never the switch that lets a spoken yes send personal content. */
    @Test fun theSettingsListIsReal() {
        val body = Regex(""""settings"\s*:\s*\{(.*?)\}\s*,""", RegexOption.DOT_MATCHES_ALL).find(config)!!.groupValues[1]
        val names = Regex(""""(\w+)"\s*:""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(names.size >= 5)
        val settings = File(root, "core/src/main/java/app/heyrobot/core/Settings.kt").readText()
        for (n in names) assertTrue("$n isn't a setting", Regex("""\bval $n\s*:""").containsMatchIn(settings))
        assertTrue("voiceYesPersonal" !in names)
        val speak = Regex("""enum class SpeakReplies[^{]*\{(.*?);""", RegexOption.DOT_MATCHES_ALL).find(settings)!!.groupValues[1]
        val values = Regex("""\b([A-Z]+)\(""").findAll(speak).map { it.groupValues[1] }.toSet()
        assertEquals(values, Regex(""""speakReplies"\s*:\s*\[([^\]]*)]""").find(body)!!.groupValues[1].let { b ->
            Regex(""""(\w+)"""").findAll(b).map { it.groupValues[1] }.toSet() })
    }

    /** The routers a script can check are real classes; what the test build must never be granted is what Robot asks for. */
    @Test fun theRoutersAndPermissionsAreReal() {
        val sources = modules.flatMap { m -> File(root, "$m/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList() }
        val classes = sources.flatMap { f -> Regex("""(?:class|object) (\w+Router)\b""").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals("not classes any more", emptyList<String>(), list("routers").filter { it != "model" && it !in classes })
        assertEquals("add each new router to spoken.json", emptyList<String>(), classes.filter { it !in list("routers") })
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        for (p in list("neverGranted")) assertTrue("$p isn't in the manifest any more", "\"$p\"" in manifest)
    }

    /** Nothing of the tools can reach an APK. Fails if a build file, a main source or an asset comes to hold any of it. */
    @Test fun spokenToolsNeverInRelease() {
        val buildFiles = listOf(File(root, "build.gradle"), File(root, "settings.gradle")) + modules.map { File(root, "$it/build.gradle") }
        for (f in buildFiles) assertTrue("$f names tools/spoken", "tools/spoken" !in f.readText())
        val markers = listOf("ai.robot_app.spoken", "SpokenReceiver", "robot-spoken-harness", "files/spoken", "tools/spoken")
        val audio = Regex("""(?i)\.(s16|pcm|wav|mp3|m4a|aac|ogg|oga|opus|flac|amr|3gp|mid|midi)$""")
        for (m in modules) {
            val main = File(root, "$m/src/main")
            for (f in main.walkTopDown().filter { it.isFile }) {
                val path = f.relativeTo(root).path
                if (f.extension in setOf("kt", "java", "xml", "json", "txt", "pro"))
                    for (w in markers) assertTrue("$path holds $w", !(f.length() < 5_000_000 && w in f.readText()))
                if (audio.containsMatchIn(f.name)) assertEquals("audio in a main source set", "voice/src/main/assets/bench/test-clip.s16", path)
            }
        }
        // The tools folder holds code and text only: never a clip, a recording, a model or an APK.
        val kinds = spoken.walkTopDown().filter { it.isFile && "__pycache__" !in it.path }.map { it.extension }.toSet()
        assertEquals(emptySet<String>(), kinds - setOf("py", "sh", "json", "md"))
        val check = File(root, "tools/verify_apk.sh").readText()
        for (line in listOf("no spoken-test package id", "no DUMP permission", "no spoken-test class in the dex", "no spoken-test string in the dex",
            "no spoken-test script or clip in the APK", "no audio in the APK but the speed test's clip"))
            assertTrue("verify_apk.sh lost: $line", line in check)
    }

    /** The tools' own tests (tools/spoken/selftest.py), on a stand-in for adb. They never touch a phone. */
    @Test fun theToolsOwnTestsPass() {
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor() }.getOrDefault(1) == 0
        assumeTrue("needs python3", python)
        val log = File.createTempFile("spoken-selftest", ".log")
        try {
            val pb = ProcessBuilder("python3", "-I", File(spoken, "selftest.py").path).redirectErrorStream(true).redirectOutput(log)
            pb.environment().keys.removeAll { it.startsWith("ROBOT_") || it == "ANDROID_SERIAL" }
            pb.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            val p = pb.start()
            val done = p.waitFor(5, TimeUnit.MINUTES)
            if (!done) p.destroyForcibly()
            assertTrue("selftest.py: " + log.readText().takeLast(3000), done && p.exitValue() == 0)
        } finally {
            log.delete()
        }
    }
}
