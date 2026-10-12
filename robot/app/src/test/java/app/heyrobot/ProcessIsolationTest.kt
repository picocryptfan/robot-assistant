package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Build check (source, Gradle and merged-manifest scan), as the probe's: LiteRT-LM lives only in :brain's
 * GemmaEngine.kt and runs only in the :brain process (0.4.5: llama.cpp is built only by tools/build_llama.sh and loaded
 * only by LlamaNative.kt, which only LlamaService reaches, in its own isolated ":llama" process); sherpa-onnx lives only
 * in the :voice module and runs only in the :voice process; the fast translation engine (librobotmt.so, 0.5.0) lives only
 * in the :mt module and runs only in the :mt process, which never goes online; nothing the main (UI) process runs refers
 * to any of them. Also: the components
 * sit in their processes, the recognizer stays discoverable (the restart finding), exported components
 * are guarded, the permissions are the expected few, nothing but DownloadManager, the Claude SDK, the Open-Meteo client
 * (the weather) and the Nominatim client (the nearest place, 0.4.0) goes online, and those two only from the :voice
 * process's tools, audio is never written to disk and nothing logs except through RLog (which drops content). The updater
 * (Settings › Updates) runs in the main process only, its GitHub client is the only code that reaches GitHub, and its access
 * token goes to api.github.com only.
 */
class ProcessIsolationTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun main(module: String) = File(root, "$module/src/main")
    private fun java(module: String) = File(main(module), "java")
    /** Kotlin and Java (the TTS callback sherpa's JNI calls is Java), so every scan below covers both. */
    private fun sources(dir: File) = dir.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
    private val modules = listOf("core", "brain", "voice", "claude", "mt", "app")
    /** Our code (not the copied sherpa-onnx API), as "module/relative/path.kt". */
    private val ours: Map<String, File> by lazy {
        modules.flatMap { m -> sources(File(java(m), "app/heyrobot")).map { "$m/${it.relativeTo(File(java(m), "app/heyrobot")).path}" to it } }.toMap()
    }

    /** Source (Kotlin or Java) without comments and string literals, so only real code references count. */
    private fun code(f: File): String = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(\\\\.|[^\"\\\\\n])*\""), "\"\"")

    /** Source without comments, string literals kept (a URL in a string is one the code may use). */
    private fun withoutComments(f: File): String = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)^\\s*//[^\n]*"), "")
        .replace(Regex("\\s// [^\n]*"), "")

    private fun usersOf(pattern: Regex, files: Map<String, File> = ours) = files.filter { pattern.containsMatchIn(code(it.value)) }.keys.sorted()

    @Test fun liteRtOnlyInTheBrainEngineFile() {
        assertEquals(listOf("brain/brain/GemmaEngine.kt"), usersOf(Regex("""\bcom\.google\.ai\.edge\.litert""")))
    }

    @Test fun sherpaOnlyInTheVoiceModule() {
        val users = usersOf(Regex("""\bcom\.k2fsa\b"""))
        assertTrue(users.toString(), users.isNotEmpty() && users.all { it.startsWith("voice/") })
        assertTrue(users.contains("voice/voice/WakeWord.kt"))
        // Speech to text and text to speech (the voice plan's Kotlin API files) are sherpa-onnx too.
        assertTrue(users.toString(), users.containsAll(listOf("voice/voice/SherpaSpeechToText.kt", "voice/voice/SherpaTextToSpeech.kt")))
        for (f in listOf("OfflineRecognizer.kt", "OfflineStream.kt", "Tts.kt")) assertTrue(f, File(java("voice"), "com/k2fsa/sherpa/onnx/$f").isFile)
        // 0.5.0: speaker labels use sherpa-onnx's speaker embedding API (the JNI was already in the library).
        assertTrue(users.toString(), users.contains("voice/voice/SpeakerEmbedder.kt"))
        for (f in listOf("Speaker.kt", "SpeakerEmbeddingExtractorConfig.kt")) assertTrue(f, File(java("voice"), "com/k2fsa/sherpa/onnx/$f").isFile)
        // Only the copied sherpa-onnx API loads its native library (and, 0.4.5, llama.cpp's bridge loads Robot's own, in
        // the isolated :llama process, and, 0.5.0, the :mt module the fast engine's JNI).
        assertEquals(listOf("brain/brain/LlamaNative.kt", "mt/mt/Slimt.kt"), usersOf(Regex("""System\.load(Library)?\s*\(""")))
        val sherpa = sources(File(java("voice"), "com/k2fsa"))
        assertTrue(sherpa.any { Regex("""System\.loadLibrary\("sherpa-onnx-jni"\)""").containsMatchIn(it.readText()) })
        for (m in listOf("core", "brain", "mt", "app")) assertTrue("$m has sherpa sources", !File(java(m), "com/k2fsa").exists())
    }

    /**
     * 0.5.0 (docs/conversation-mode.md 4.5): the fast translation engine. librobotmt.so is loaded by one file of the :mt
     * module, whose classes run only in the :mt process (MtService); that module has no network code, writes no file,
     * reaches no other engine, depends on :core only and never builds native code in Gradle (tools/build_robotmt.sh does).
     * Clients reach it by class name through core MtProtocol.
     */
    @Test fun fastEngineOnlyInTheMtProcess() {
        val mt = ours.filterKeys { it.startsWith("mt/") }
        assertEquals(setOf("mt/mt/BergamotEngine.kt", "mt/mt/MtService.kt", "mt/mt/Slimt.kt"), mt.keys)
        val slimt = File(java("mt"), "app/heyrobot/mt/Slimt.kt").readText()
        assertTrue(slimt.contains("System.loadLibrary(\"robotmt\")"))
        assertEquals(1, Regex("""System\.load""").findAll(slimt).count())
        // The engine's classes are used only inside the module.
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(Slimt|BergamotEngine|MtService)\b|\bapp\.heyrobot\.mt\b"""), ours.filterKeys { !it.startsWith("mt/") }))
        // No network, no file written, no other engine, no app or assistant code.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bSocket\b|okhttp|DownloadManager|\bWebView\b|ConnectivityManager|\bURL\("""), mt))
        assertEquals(emptyList<String>(), usersOf(Regex("""FileOutputStream|RandomAccessFile|Files\.(write|copy|newOutputStream)|writeText|writeBytes|openFileOutput|writeAtomic"""), mt))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.(voice|assistant|ui|sys|brain|claude|people|boot)\b|\bcom\.k2fsa\b|litert|\bcom\.anthropic\b"""), mt))
        fun deps(m: String) = File(root, "$m/build.gradle").readText()
        assertEquals(listOf("project(':core')"), Regex("""^\s*(implementation|api)\s+(.+)$""", RegexOption.MULTILINE).findAll(deps("mt")).map { it.groupValues[2].trim() }.toList())
        assertTrue(!deps("mt").contains("externalNativeBuild") && !deps("mt").contains("ndkVersion"))
        assertTrue(deps("app").contains("implementation project(':mt')"))
        for (m in listOf("core", "brain", "voice", "claude")) assertTrue(m, !deps(m).contains("project(':mt')"))
        // The library is never packaged by another module, and never in git (.gitignore).
        assertTrue(!File(main("voice"), "jniLibs/arm64-v8a/librobotmt.so").exists())
        assertTrue(File(root.parentFile, ".gitignore").readText().contains("robot/mt/src/main/jniLibs/"))
        // Clients bind it by name, and only through MtLink: the main app's speed test, and the voice process's sessions
        // (phase 5.4) through FastTranslation alone, whose BergamotTranslator core's FastEngineGate makes.
        assertEquals("app.heyrobot.mt.MtService", Regex("""const val SERVICE_CLASS = "([^"]+)"""").find(File(java("core"), "app/heyrobot/core/MtProtocol.kt").readText())!!.groupValues[1])
        assertEquals(listOf("app/sys/MtLink.kt"), usersOf(Regex("""\bMtProtocol\.SERVICE_CLASS\b""")))
        assertEquals(listOf("app/assistant/conversation/FastTranslation.kt", "app/sys/MtLink.kt", "app/sys/MtSpeedRun.kt", "app/ui/ConversationSettings.kt"),
            usersOf(Regex("""\bMtLink\b""")))
        assertEquals(listOf("app/assistant/conversation/ConversationSession.kt", "app/assistant/conversation/ConversationTranslations.kt",
            "app/assistant/conversation/FastTranslation.kt"), usersOf(Regex("""\bFastTranslation\b""")))
        assertEquals(listOf("core/core/FastTranslate.kt"), usersOf(Regex("""(?<!class )\bBergamotTranslator\(""")))
        // The service checks who sent each message, as BrainService does.
        assertTrue(code(File(java("mt"), "app/heyrobot/mt/MtService.kt")).contains("msg.sendingUid != Process.myUid()"))
        // Robot's own native code in the library (the wrapper and its JNI; robotmt_check.cpp is the build machine's check,
        // never in the library) prints, logs and writes nothing and opens no socket, so no text reaches a log from it.
        val cpp = File(main("mt"), "cpp")
        assertEquals(setOf("CMakeLists.txt", "robotmt.cpp", "robotmt.h", "robotmt_check.cpp", "robotmt_jni.cpp"), cpp.list()!!.toSet())
        val native = listOf("robotmt.cpp", "robotmt.h", "robotmt_jni.cpp").associate { "mt/cpp/$it" to File(cpp, it) }
        assertEquals(emptyList<String>(), usersOf(Regex("""__android_log|\b(f|s|sn|v)?printf\s*\(|\bputs\s*\(|\bstd::(cout|cerr|clog)\b|\bofstream\b|""" +
            """\bfopen\s*\(|\bsocket\s*\(|\bconnect\s*\(|#include\s*<(iostream|fstream|cstdio|stdio\.h|sys/socket\.h|android/log\.h)>"""), native))
        assertTrue(File(cpp, "CMakeLists.txt").readText().contains("add_library(robotmt SHARED robotmt_jni.cpp)"))
    }

    /** Ask Claude: the Anthropic SDK lives only in the :claude module, which only the assistant (the :voice process) uses. */
    @Test fun anthropicSdkOnlyInTheClaudeModule() {
        val users = usersOf(Regex("""\bcom\.anthropic\b"""))
        assertTrue(users.toString(), users.isNotEmpty() && users.all { it.startsWith("claude/") })
        val claudeUsers = usersOf(Regex("""\bapp\.heyrobot\.claude\b"""), ours.filterKeys { !it.startsWith("claude/") })
        assertTrue(claudeUsers.toString(), claudeUsers.isNotEmpty() && claudeUsers.all { it.startsWith("app/assistant/") })
        // 0.6.0: the other helpers' clients (plain OkHttp, no other SDK) live in the same module, used only by the assistant.
        val helperUsers = usersOf(Regex("""\bapp\.heyrobot\.helpers\b"""), ours.filterKeys { !it.startsWith("claude/") })
        assertTrue(helperUsers.toString(), helperUsers.isNotEmpty() && helperUsers.all { it.startsWith("app/assistant/") })
        assertEquals(emptyList<String>(), usersOf(Regex("""\bokhttp3\b"""), ours.filterKeys { !it.startsWith("claude/") }))
        fun deps(m: String) = File(root, "$m/build.gradle").readText()
        assertTrue(deps("claude").contains("com.squareup.okhttp3:okhttp:4.12.0"))
        assertTrue(deps("claude").contains("com.anthropic:anthropic-java:"))
        for (m in listOf("core", "brain", "voice", "app")) assertTrue(m, !deps(m).contains("com.anthropic"))
        for (m in listOf("core", "brain", "voice")) assertTrue(m, !deps(m).contains("project(':claude')"))
        assertTrue(deps("app").contains("project(':claude')"))
        assertTrue(!deps("claude").contains("litert") && !deps("claude").contains("project(':voice')") && !deps("claude").contains("project(':brain')"))
        // A helper's API key is decrypted in the :voice process only, and handed only to its client: for questions
        // (HelperLink) and for Test connection (the connection window).
        assertEquals(listOf("app/assistant/HelperConnectionActivity.kt", "app/assistant/HelperLink.kt"), usersOf(Regex("""\bHelperKeys\.read\(""")))
        assertTrue(usersOf(Regex("""\bHelperKeys\b""")).all { it.startsWith("app/assistant/") })
        // Every helper's key is a KeystoreSecret (sys/HelperFiles.kt, shared with the main app for Settings), but only
        // HelperKeys reaches one to read or save it; HelperFiles.kt itself never reads one.
        assertEquals(listOf("app/assistant/HelperKeys.kt"), usersOf(Regex("""\bHelperKeyFiles\.store\b""")))
        val helperFiles = code(File(java("app"), "app/heyrobot/sys/HelperFiles.kt"))
        assertTrue(!Regex("""\.(read|save)\(ctx""").containsMatchIn(helperFiles) && !helperFiles.contains("clearClipboard"))
        // The SDK never logs (its requests carry the key), and never retries on its own.
        val client = File(java("claude"), "app/heyrobot/claude/ClaudeClient.kt").readText()
        assertTrue(client.contains(".logLevel(LogLevel.OFF)") && client.contains(".maxRetries(0)"))
    }

    @Test fun modulesDependOnlyWhereAllowed() {
        fun deps(m: String) = File(root, "$m/build.gradle").readText()
        assertTrue(!deps("core").contains("project(':"))
        assertTrue(!deps("core").contains("litert") && !deps("voice").contains("litert") && !deps("app").contains("litert"))
        assertTrue(deps("brain").contains("litertlm-android:0.18.0"))
        assertTrue(!deps("brain").contains("project(':voice')") && !deps("voice").contains("project(':brain')"))
        // The engine modules never reach into each other or into the app.
        val brainBad = usersOf(Regex("""\bapp\.heyrobot\.(voice|assistant|ui)\b|\bcom\.k2fsa\b"""), ours.filterKeys { it.startsWith("brain/") })
        val voiceBad = usersOf(Regex("""\bapp\.heyrobot\.(brain|assistant|ui)\b|litertlm"""), ours.filterKeys { it.startsWith("voice/") })
        assertEquals(emptyList<String>(), brainBad + voiceBad)
    }

    /** Everything the main (UI) process can run must not touch the engines or the assistant's classes. */
    @Test fun mainProcessCodeNeverReachesEngines() {
        val mainFiles = ours.filterKeys { k ->
            k.startsWith("core/") || k.startsWith("app/ui/") || k.startsWith("app/sys/") || k.startsWith("app/boot/") || k.startsWith("app/people/") ||
                k.startsWith("app/update/") || k == "app/RobotApp.kt"
        }
        assertTrue(mainFiles.size >= 25)
        val forbidden = Regex("""\bapp\.heyrobot\.(assistant|voice|brain|claude|mt)\b|\bcom\.k2fsa\b|litertlm|\bcom\.anthropic\b|\b(GemmaEngine|BrainService|WakeWord|Listener|VoiceHub|PanelController|BrainKeeper|BrainClient|VoiceEngines|RobotSession|RobotVoiceService|HelperLink|HelperKeys|ClaudeSession|ClaudeClient|HelperSession|OpenAiSession|HelperHttp|HelperProbe|HelperConnectionActivity|MtService|BergamotEngine|Slimt)\b|\bapp\.heyrobot\.helpers\b|\bokhttp3\b""")
        assertEquals(emptyList<String>(), usersOf(forbidden, mainFiles))
        // And the :voice code must not load LiteRT or the fast translation engine either (it binds :brain and :mt by class name).
        assertEquals(emptyList<String>(), usersOf(Regex("""litertlm|\bGemmaEngine\b|\bBrainService\b|\bapp\.heyrobot\.mt\b|\b(MtService|BergamotEngine|Slimt)\b"""),
            ours.filterKeys { it.startsWith("app/assistant/") || it.startsWith("voice/") }))
    }

    private fun mergedManifest(): File {
        val variant = System.getProperty("robot.variant") ?: "debug"
        val cap = variant.replaceFirstChar { it.uppercase() }
        val f = File(root, "app/build/intermediates/merged_manifest/$variant/process${cap}MainManifest/AndroidManifest.xml")
        assertTrue("merged manifest missing: $f (run the unit test through Gradle)", f.isFile)
        return f
    }

    private fun components(doc: org.w3c.dom.Document): Map<String, Element> = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
        val nl = doc.getElementsByTagName(tag)
        (0 until nl.length).map { nl.item(it) as Element }
    }.associateBy { it.getAttributeNS(ns, "name") }

    private fun parse(f: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)

    @Test fun mergedManifestPutsEachComponentInItsProcess() {
        val doc = parse(mergedManifest())
        val comps = components(doc)
        fun c(n: String) = comps[n] ?: throw AssertionError("$n missing from the merged manifest")
        fun proc(n: String) = c(n).getAttributeNS(ns, "process")
        fun exported(n: String) = c(n).getAttributeNS(ns, "exported")
        fun perm(n: String) = c(n).getAttributeNS(ns, "permission")
        val voice = listOf("app.heyrobot.assistant.RobotVoiceService", "app.heyrobot.assistant.RobotSessionService",
            "app.heyrobot.assistant.RobotRecognitionService", "app.heyrobot.assistant.CaptureActivity", "androidx.core.content.FileProvider",
            "app.heyrobot.assistant.HelperConnectionActivity", "app.heyrobot.assistant.MediaDeleteActivity",
            "app.heyrobot.assistant.CheogramPickActivity",
            // 0.5.0, conversation mode: where the listener and the speech model are.
            "app.heyrobot.assistant.conversation.ConversationService", "app.heyrobot.assistant.conversation.ConversationActivity",
            "app.heyrobot.assistant.conversation.TranscriptionCheckActivity", "app.heyrobot.assistant.conversation.ConversationTileService",
            // 0.5.0, phase 5.3: "Your voice" uses the speaker model and the voiceprint's key, both only in :voice.
            "app.heyrobot.assistant.conversation.VoiceprintActivity",
            // 0.5.0, phase 5.6: summaries reach Robot's model and the Claude key, both only from :voice.
            "app.heyrobot.assistant.conversation.SummaryActivity",
            // 0.8, routines: the exact alarm's receiver and the speaking service, where the voice and the trigger hub are.
            "app.heyrobot.assistant.routines.RoutineAlarmReceiver", "app.heyrobot.assistant.routines.RoutineSpeakingService")
        for (n in voice) assertEquals(n, ":voice", proc(n))
        assertEquals(":brain", proc("app.heyrobot.brain.BrainService"))
        // 0.4.5: llama.cpp's own process, isolated: Android runs it under a uid of its own with no permissions, no network
        // and none of Robot's files. Not exported, no intent filter, never guarded by (or holding) a permission, never
        // sharing its process with another isolated service nor started from the app zygote.
        val llama = c("app.heyrobot.brain.LlamaService")
        assertEquals(":llama", proc("app.heyrobot.brain.LlamaService"))
        assertEquals("true", llama.getAttributeNS(ns, "isolatedProcess"))
        assertEquals("false", exported("app.heyrobot.brain.LlamaService"))
        assertEquals("", perm("app.heyrobot.brain.LlamaService"))
        for (a in listOf("allowSharedIsolatedProcess", "useAppZygote", "externalService", "foregroundServiceType")) assertEquals(a, "", llama.getAttributeNS(ns, a))
        assertEquals(0, llama.getElementsByTagName("intent-filter").length)
        // No other component is isolated, and nothing else runs in :llama.
        assertEquals(listOf("app.heyrobot.brain.LlamaService"), comps.filter { it.value.getAttributeNS(ns, "isolatedProcess") == "true" }.keys.toList())
        assertEquals(listOf("app.heyrobot.brain.LlamaService"), comps.filter { it.value.getAttributeNS(ns, "process") == ":llama" }.keys.toList())
        // 0.5.0: the fast translation engine in a process of its own, bound only by Robot's own processes.
        assertEquals(":mt", proc("app.heyrobot.mt.MtService"))
        assertEquals("false", exported("app.heyrobot.mt.MtService"))
        assertEquals("app.heyrobot.permission.INTERNAL", perm("app.heyrobot.mt.MtService"))
        val main = listOf("app.heyrobot.ui.MainActivity", "app.heyrobot.boot.BootReceiver", "app.heyrobot.boot.BootCheckReceiver",
            "app.heyrobot.people.CheogramListener", "app.heyrobot.sys.ExportProvider", "app.heyrobot.boot.UpdatedReceiver", "app.heyrobot.update.UpdateJobService",
            "app.heyrobot.update.InstallStatusReceiver")
        for (n in main) assertEquals(n, "", proc(n))
        // Every Robot component is accounted for (a new one must be placed deliberately).
        val ourComps = comps.keys.filter { it.startsWith("app.heyrobot.") }.toSet()
        assertEquals(main.toSet() + "app.heyrobot.brain.BrainService" + "app.heyrobot.brain.LlamaService" + "app.heyrobot.mt.MtService" +
            voice.filter { it.startsWith("app.heyrobot.") }, ourComps)
        // Exported components are guarded: the system-only BIND_VOICE_INTERACTION, or nothing to guard.
        assertEquals("android.permission.BIND_VOICE_INTERACTION", perm("app.heyrobot.assistant.RobotVoiceService"))
        assertEquals("android.permission.BIND_VOICE_INTERACTION", perm("app.heyrobot.assistant.RobotSessionService"))
        // 0.4.0: the notification listener only the system may bind, named as the main app names it.
        assertEquals("true", exported("app.heyrobot.people.CheogramListener"))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", perm("app.heyrobot.people.CheogramListener"))
        assertTrue(File(java("app"), "app/heyrobot/sys/People.kt").readText().contains("\"app.heyrobot.people.CheogramListener\""))
        assertEquals("false", exported("app.heyrobot.brain.BrainService"))
        assertEquals("app.heyrobot.permission.INTERNAL", perm("app.heyrobot.brain.BrainService"))
        for (n in listOf("app.heyrobot.assistant.CaptureActivity", "androidx.core.content.FileProvider", "app.heyrobot.boot.BootReceiver", "app.heyrobot.boot.BootCheckReceiver",
            "app.heyrobot.assistant.HelperConnectionActivity", "app.heyrobot.assistant.MediaDeleteActivity",
            "app.heyrobot.assistant.CheogramPickActivity", "app.heyrobot.assistant.conversation.ConversationService",
            "app.heyrobot.assistant.conversation.ConversationActivity", "app.heyrobot.assistant.conversation.TranscriptionCheckActivity",
            "app.heyrobot.assistant.conversation.VoiceprintActivity", "app.heyrobot.assistant.conversation.SummaryActivity", "app.heyrobot.sys.ExportProvider",
            "app.heyrobot.assistant.routines.RoutineAlarmReceiver", "app.heyrobot.assistant.routines.RoutineSpeakingService",
            "app.heyrobot.boot.UpdatedReceiver", "app.heyrobot.update.UpdateJobService", "app.heyrobot.update.InstallStatusReceiver"))
            assertEquals(n, "false", exported(n))
        // 0.8: routines speak from a mediaPlayback foreground service (Android 17's background audio), never a short one;
        // the receiver hears only the system's broadcasts that make the exact alarm wrong, after the first unlock.
        assertEquals("mediaPlayback", c("app.heyrobot.assistant.routines.RoutineSpeakingService").getAttributeNS(ns, "foregroundServiceType"))
        assertEquals("", perm("app.heyrobot.assistant.routines.RoutineSpeakingService"))
        val routineActions = c("app.heyrobot.assistant.routines.RoutineAlarmReceiver").getElementsByTagName("action").let { nl ->
            (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.MY_PACKAGE_REPLACED"), routineActions)
        assertEquals("", c("app.heyrobot.assistant.routines.RoutineAlarmReceiver").getAttributeNS(ns, "directBootAware"))
        // 0.5.0: the session is a microphone foreground service; the tile only the system binds.
        assertEquals("microphone", c("app.heyrobot.assistant.conversation.ConversationService").getAttributeNS(ns, "foregroundServiceType"))
        assertEquals("true", exported("app.heyrobot.assistant.conversation.ConversationTileService"))
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", perm("app.heyrobot.assistant.conversation.ConversationTileService"))
        // The export provider shares the export folder only.
        assertEquals("app.heyrobot.exports", c("app.heyrobot.sys.ExportProvider").getAttributeNS(ns, "authorities"))
        assertEquals("<paths>\n    <cache-path name=\"exports\" path=\"exports/\" />\n</paths>",
            File(main("app"), "res/xml/export_paths.xml").readText().substringAfter("-->").trim().replace("\r", ""))
        // The updater: only the system binds its job service; Android tells Robot alone that it was updated.
        assertEquals("android.permission.BIND_JOB_SERVICE", perm("app.heyrobot.update.UpdateJobService"))
        fun actions(n: String) = c(n).getElementsByTagName("action").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertEquals(setOf("android.intent.action.MY_PACKAGE_REPLACED"), actions("app.heyrobot.boot.UpdatedReceiver"))
        assertEquals(emptySet<String>(), actions("app.heyrobot.update.InstallStatusReceiver"))
        // The recognizer MUST stay discoverable: with no current speech recognizer Android clears a third-party
        // assistant at every boot (VoiceInteractionManagerService.initForUser), which is how probe 1.1 failed.
        val rec = c("app.heyrobot.assistant.RobotRecognitionService")
        assertEquals("true", rec.getAttributeNS(ns, "exported"))
        val filters = rec.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        assertEquals("android.speech.RecognitionService", ((filters.item(0) as Element).getElementsByTagName("action").item(0) as Element).getAttributeNS(ns, "name"))
        val meta = rec.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.speech", meta.getAttributeNS(ns, "name"))
        assertEquals("@xml/recognition_service", meta.getAttributeNS(ns, "resource"))
        // The boot receiver also gets LOCKED_BOOT_COMPLETED (direct-boot aware, 0.2.3) to check the recognizer setting.
        assertEquals("true", c("app.heyrobot.boot.BootReceiver").getAttributeNS(ns, "directBootAware"))
        val bootActions = c("app.heyrobot.boot.BootReceiver").getElementsByTagName("action").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED"), bootActions)
        // Every Robot component class exists in the sources.
        for (n in ourComps) assertTrue(n, ours.values.any { Regex("""class ${n.substringAfterLast('.')}\b""").containsMatchIn(code(it)) })
    }

    @Test fun mergedManifestIdentityAndPermissions() {
        val doc = parse(mergedManifest())
        val m = doc.documentElement
        assertEquals("app.heyrobot", m.getAttribute("package"))
        assertEquals("60", m.getAttributeNS(ns, "versionCode"))
        assertEquals("0.6.0", m.getAttributeNS(ns, "versionName"))
        val sdk = doc.getElementsByTagName("uses-sdk").item(0) as Element
        assertEquals("34", sdk.getAttributeNS(ns, "minSdkVersion"))
        assertEquals("36", sdk.getAttributeNS(ns, "targetSdkVersion"))
        val declared = doc.getElementsByTagName("permission").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        val internal = declared.single { it.getAttributeNS(ns, "name") == "app.heyrobot.permission.INTERNAL" }
        assertEquals("signature", internal.getAttributeNS(ns, "protectionLevel"))
        val used = doc.getElementsByTagName("uses-permission").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertEquals(setOf(
            "app.heyrobot.permission.INTERNAL", "app.heyrobot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.VIBRATE", "com.android.alarm.permission.SET_ALARM",
            "android.permission.USE_EXACT_ALARM",
            // 0.2.3: "Call audio" playback sets the communication mode while Robot speaks.
            "android.permission.MODIFY_AUDIO_SETTINGS",
            // 0.2.3: only ever granted with adb (development permission); Robot then keeps voice_recognition_service set.
            "android.permission.WRITE_SECURE_SETTINGS",
            // Phase 2: asked for at runtime by the main app only.
            "android.permission.READ_CONTACTS", "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
            // 0.4.0: the weather "here", approximate only, asked for at runtime by the main app only.
            "android.permission.ACCESS_COARSE_LOCATION",
            // 0.4.0: Usage access, turned on (or not) in Android's settings: phone_status's largest apps.
            "android.permission.PACKAGE_USAGE_STATS",
            // 0.4.0: photos and videos (or the ones you pick), for free_storage's largest files; asked for by the main app only.
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            // 0.5.0: conversation mode's microphone foreground service, and its wake lock while a session runs.
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE", "android.permission.WAKE_LOCK",
            // 0.8: a routine speaking with the screen off (RoutineSpeakingService). Normal, no prompt.
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            // The updater: installing Robot's own update (a special app access you turn on yourself)...
            "android.permission.REQUEST_INSTALL_PACKAGES",
            // ... and, only for "Install updates without asking" (off by default), asking Android to skip its confirmation for
            // Robot's own update: without it Android ignores USER_ACTION_NOT_REQUIRED. A normal permission.
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
        ), used)
        // 0.8: routines need nothing else: USE_EXACT_ALARM (above) rather than SCHEDULE_EXACT_ALARM, no phone, Bluetooth or
        // Do Not Disturb access (those come with car and headset mode, asked only from their own rows).
        assertTrue(listOf("SCHEDULE_EXACT_ALARM", "CALL_PHONE", "BLUETOOTH_CONNECT", "ACCESS_NOTIFICATION_POLICY", "FOREGROUND_SERVICE_SHORT_SERVICE")
            .none { "android.permission.$it" in used })
        // Never installing or deleting other apps, never asking to update apps it didn't install, and no user-initiated job or
        // foreground service type for the updater's downloads (FOREGROUND_SERVICE itself is conversation mode's, above).
        assertTrue(listOf("INSTALL_PACKAGES", "DELETE_PACKAGES", "REQUEST_DELETE_PACKAGES", "INSTALL_PACKAGE_UPDATES", "INSTALL_SELF_UPDATES",
            "ENFORCE_UPDATE_OWNERSHIP", "RUN_USER_INITIATED_JOBS", "FOREGROUND_SERVICE_DATA_SYNC").none { "android.permission.$it" in used })
        // Never all files, never stopping other apps (Android 14 and later wouldn't let it anyway).
        assertTrue(listOf("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "KILL_BACKGROUND_PROCESSES").none { "android.permission.$it" in used })
        assertTrue("android.permission.ACCESS_FINE_LOCATION" !in used && "android.permission.ACCESS_BACKGROUND_LOCATION" !in used)
        // Package visibility through <queries> only.
        assertTrue("android.permission.QUERY_ALL_PACKAGES" !in used && "android.permission.SEND_SMS" !in used && "android.permission.WRITE_CONTACTS" !in used)
        // Ask Claude needs nothing new: INTERNET stays the only network permission.
        assertTrue(used.none { it.contains("NETWORK_STATE") || it.contains("WIFI") || it.contains("ACCESSIBILITY") })
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        // 0.6.0: cleartext only where Robot's own rule allows it (a server on your network), never to a company; no user CAs.
        assertEquals("@xml/network_security_config", app.getAttributeNS(ns, "networkSecurityConfig"))
        val nsc = File(main("app"), "res/xml/network_security_config.xml").readText()
        assertTrue(Regex("""<domain-config cleartextTrafficPermitted="false">""").containsMatchIn(nsc))
        for (d in listOf("anthropic.com", "openai.com", "googleapis.com", "mistral.ai", "x.ai", "deepseek.com", "groq.com", "openrouter.ai", "huggingface.co",
            "open-meteo.com", "openstreetmap.org", "github.com", "githubusercontent.com"))
            assertTrue(d, nsc.contains("<domain includeSubdomains=\"true\">$d</domain>"))
        assertTrue(!nsc.contains("src=\"user\"") && nsc.contains("<certificates src=\"system\" />"))
    }

    /** Phase 2: the "Robot Notes" launcher entry is an alias of MainActivity in the main process; <queries> names the hand-off apps. */
    @Test fun notesAliasAndHandOffQueries() {
        val doc = parse(mergedManifest())
        val aliases = doc.getElementsByTagName("activity-alias").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        val notes = aliases.single { it.getAttributeNS(ns, "name") == "app.heyrobot.ui.NotesLauncher" }
        assertEquals("app.heyrobot.ui.MainActivity", notes.getAttributeNS(ns, "targetActivity"))
        assertEquals("true", notes.getAttributeNS(ns, "exported"))
        assertEquals("", notes.getAttributeNS(ns, "process"))
        val actions = notes.getElementsByTagName("action").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }
        val categories = notes.getElementsByTagName("category").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }
        assertEquals(listOf("android.intent.action.MAIN"), actions)
        assertEquals(listOf("android.intent.category.LAUNCHER"), categories)
        // MainActivity recognises the alias by this name (sys/People.kt AppPages).
        assertTrue(File(java("app"), "app/heyrobot/sys/People.kt").readText().contains("\"app.heyrobot.ui.NotesLauncher\""))
        val queries = doc.getElementsByTagName("queries").item(0) as Element
        val packages = queries.getElementsByTagName("package").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertTrue(packages.containsAll(setOf("com.cheogram.android", "com.cheogram.android.playstore", "ch.protonmail.android", "com.anthropic.claude")))
        val schemes = queries.getElementsByTagName("data").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "scheme") } }.toSet()
        assertTrue(schemes.containsAll(setOf("xmpp", "mailto", "smsto", "claude", "https")))
        val hosts = queries.getElementsByTagName("data").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "host") } }.toSet()
        assertTrue("claude.ai" in hosts)
        val mimes = queries.getElementsByTagName("data").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "mimeType") } }.toSet()
        assertTrue("vnd.android.cursor.dir/event" in mimes)
    }

    /**
     * 0.4.1, Settings › Model: every model still downloads by DownloadManager from the main app's stores (0.5.0: also the
     * fast translation engine's files, MtModelStore), and only the main app downloads, imports, checks or deletes one; the
     * voice process only asks which file to load (ModelStore.plan), and :brain reads a model's header (core LiteRtLm, no
     * network) before it loads it.
     */
    @Test fun modelsDownloadOnlyFromTheMainApp() {
        assertEquals(listOf("app/sys/ModelStore.kt", "app/sys/MtModelStore.kt", "app/sys/VoiceModelStore.kt"), usersOf(Regex("""\bDownloadManager\b""")))
        assertEquals(listOf("app/ui/MainActivity.kt"), usersOf(Regex("""\bModelStore\.(startDownload|import|verify|delete|allowOwnTpu|cancelDownload)\(""")))
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bModelStore\.plan\(""")))
        assertEquals(listOf("brain/brain/GemmaEngine.kt", "app/sys/ModelStore.kt").sorted(), usersOf(Regex("""\bLiteRtLmCheck\.check\(""")))
    }

    /**
     * 0.4.5, your own GGUF file: llama.cpp lives only in the :brain module and runs only in LlamaService's isolated ":llama"
     * process. One library, librobot_llama.so, built from the pinned llama.cpp source and Robot's committed JNI bridge by
     * tools/build_llama.sh (never by Gradle), loaded by LlamaNative.kt alone, which only LlamaService reaches, and only in an
     * isolated process (Process.isIsolated()); :brain's LlamaEngine talks to it only through LlamaLink's Binder calls
     * (LlamaIpc) and binds it by name, so no :brain code refers to the service or the bridge. Nothing of the voice or main
     * process refers to any of it. Its JNI functions are exactly LlamaNative's external functions.
     */
    @Test fun llamaCppOnlyInTheIsolatedProcess() {
        val native = File(java("brain"), "app/heyrobot/brain/LlamaNative.kt").readText()
        assertTrue(native.contains("System.loadLibrary(LIB)") && native.contains("const val LIB = \"robot_llama\""))
        // The library loads only after the process proved isolated.
        assertTrue(Regex("""if \(!android\.os\.Process\.isIsolated\(\)\) LlamaIpc\.NOT_ISOLATED\s+else try \{\s+System\.loadLibrary\(LIB\)""").containsMatchIn(native))
        // (0.5.0: the fast translation engine's JNI class in :mt is the other one: fastEngineOnlyInTheMtProcess.)
        assertEquals(listOf("brain/brain/LlamaNative.kt", "mt/mt/Slimt.kt"), usersOf(Regex("""\bexternal\s+fun\b""")))
        assertEquals(listOf("brain/brain/LlamaNative.kt", "brain/brain/LlamaService.kt"), usersOf(Regex("""\bLlamaNative\b""")))
        // The service's class: referred to by nothing but itself (bound by name, LlamaIpc.SERVICE_CLASS, as in the manifest).
        assertEquals(listOf("brain/brain/LlamaService.kt"), usersOf(Regex("""\bLlamaService\b""")))
        assertTrue(File(java("brain"), "app/heyrobot/brain/LlamaIpc.kt").readText().contains("const val SERVICE_CLASS = \"app.heyrobot.brain.LlamaService\""))
        assertTrue(File(main("brain"), "AndroidManifest.xml").readText().contains("android:name=\"app.heyrobot.brain.LlamaService\""))
        assertEquals(listOf("brain/brain/LlamaEngine.kt", "brain/brain/LlamaLink.kt"), usersOf(Regex("""\bLlamaLink\b""")))
        assertEquals(listOf("brain/brain/LlamaEngine.kt", "brain/brain/LlamaIpc.kt", "brain/brain/LlamaLink.kt", "brain/brain/LlamaNative.kt", "brain/brain/LlamaService.kt"),
            usersOf(Regex("""\bLlamaIpc\b""")))
        assertEquals(listOf("brain/brain/Engine.kt", "brain/brain/LlamaEngine.kt"), usersOf(Regex("""\bLlamaEngine\b""")))
        assertEquals(listOf("brain/brain/BrainService.kt", "brain/brain/Engine.kt"), usersOf(Regex("""\bEngines\b""")))
        // :brain hands the file over only to a process that proved an isolated uid (core IsolatedUid, from Binder's calling uid).
        val link = File(java("brain"), "app/heyrobot/brain/LlamaLink.kt").readText()
        assertTrue(link.contains("IsolatedUid.of(from)") && link.contains("Binder.getCallingUid()"))
        // The GGUF content check runs where LiteRT-LM's does: the main app's import check and :brain before a load.
        assertEquals(listOf("app/sys/ModelStore.kt", "brain/brain/LlamaEngine.kt"), usersOf(Regex("""\bGgufCheck\.check\(""")))
        // The native sources are :brain's (and, 0.5.0, the fast engine's in :mt); no module builds native code through Gradle.
        for (m in modules) {
            assertTrue(m, !File(root, "$m/build.gradle").readText().contains("externalNativeBuild"))
            if (m != "brain" && m != "mt") assertTrue(m, !File(main(m), "cpp").exists())
        }
        val cpp = File(main("brain"), "cpp")
        assertEquals(setOf("CMakeLists.txt", "exports.map", "robot_llama.cpp"), cpp.list()!!.toSet())
        // Each external function is a JNI function of the bridge, and the bridge exports nothing else.
        val bridge = File(cpp, "robot_llama.cpp").readText()
        val externals = Regex("""external\s+fun\s+(\w+)""").findAll(native).map { it.groupValues[1] }.toSet()
        val jni = Regex("""Java_app_heyrobot_brain_LlamaNative_(\w+)\(""").findAll(bridge).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("load", "free", "cancel", "trainContext", "specials", "prepare", "generate"), externals)
        assertEquals(externals, jni)
        // llama.cpp reads the model only through the descriptor it is given: the isolated process can't open a file by name.
        assertTrue(bridge.contains("llama_model_load_from_file_ptr(file, mp)") && !Regex("""llama_model_load_from_file\(|llama_model_load_from_splits\(""").containsMatchIn(bridge))
        val exports = File(cpp, "exports.map").readText()
        assertTrue(exports.contains("Java_app_heyrobot_brain_LlamaNative_*;") && exports.contains("local:") && exports.contains("*;"))
        // The library, when built, is only :brain's (gitignored there); no other module packages one of that name.
        for (m in modules.filter { it != "brain" }) assertTrue(m, !File(main(m), "jniLibs/arm64-v8a/librobot_llama.so").exists())
        File(main("brain"), "jniLibs").takeIf { it.exists() }?.let { libs ->
            assertEquals(setOf("arm64-v8a/librobot_llama.so"), libs.walkTopDown().filter { it.isFile }.map { it.relativeTo(libs).path }.toSet())
        }
        // The pins the build script checks, as THIRD_PARTY.md states them.
        val script = File(root, "tools/build_llama.sh").readText()
        val third = File(root, "THIRD_PARTY.md").readText()
        for (pin in listOf("b11200", "81bc6b83f827df746eb129235488d325c49cae52", "26aa9265184fb08bf847f3fe843a2d7351985e67757abf270ea366bbe451e07b",
            "0b2ee513397578a272a4e8a6c12589ef6534371c", "9348b969e042d8890a54b01a463dbe71f5a4c074b5329e9c26a85ef3b68aa19b")) {
            assertTrue(pin, script.contains(pin) && third.contains(pin))
        }
        // The CPU only (no Vulkan, no OpenCL), with KleidiAI, and nothing exported but the bridge.
        val cmake = File(cpp, "CMakeLists.txt").readText()
        assertTrue(cmake.contains("GGML_VULKAN GGML_OPENCL") && cmake.contains("set(GGML_CPU_KLEIDIAI ON") && cmake.contains("--version-script"))
        // The voice process and the main app never reach it (they bind :brain by class name).
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(LlamaEngine|LlamaNative|LlamaService|LlamaLink|LlamaIpc|Engines|robot_llama)\b"""),
            ours.filterKeys { !it.startsWith("brain/") }))
    }

    /**
     * 0.4.7, the problem report: only the assistant (BrainKeeper, in the :voice process) notes a model problem, as codes and
     * numbers (core ModelProblem); only the main app builds the report from it (core ProblemReport, whose test checks that
     * nothing personal gets in) and only when you open Settings › Model; and the report's code reads none of your
     * conversations, notes, people or the crash trace (no tombstone in 0.4.7). Nothing sends it: copy and share are yours.
     * 0.6.0: nor a helper's name, address or key, a transcript, a voiceprint or the updater's token.
     */
    @Test fun problemReportsHoldCodesAndAreNeverSent() {
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bProblems\.(note|gpuFailed|gpuTrying)\(""")))
        // (The raw text: MainActivity's "*/*" file type reads as a comment's start to code().)
        assertEquals(listOf("app/ui/MainActivity.kt"),
            ours.filter { Regex("""\bProblems\.(report|copy|share|forgetGpuFailure|dismiss)\(""").containsMatchIn(it.value.readText()) }.keys.sorted())
        assertEquals(listOf("app/sys/Problems.kt"), usersOf(Regex("""\bProblemReport\.text\(""")))
        val problems = code(File(java("app"), "app/heyrobot/sys/Problems.kt"))
        for (bad in listOf("ConversationStore", "NotesFiles", "PeopleFiles", "getTraceInputStream", "VoiceLogText.describe(e, ", "HttpURLConnection")) {
            assertTrue(bad, !problems.contains(bad))
        }
        // The voice log goes in through core ProblemReport, which describes wake phrases by number only.
        assertTrue(problems.contains("VoiceLogCodec.decode(readOrNull(Paths.voiceLog(ctx)))"))
        val report = code(File(java("core"), "app/heyrobot/core/ModelProblems.kt"))
        assertTrue(report.contains("VoiceLogText.describe(e)"))
        // 0.6.0: nor the helpers (their names, addresses and keys), conversation mode's transcripts, voiceprints or the
        // updater's access token: neither the code that builds the report nor the note and its facts reach them, and the
        // settings (where the helpers' names and addresses are) aren't read either.
        val secrets = Regex("""\b(HelperKeys|HelperKeyFiles|HelperFiles|HelperConfig|KeystoreSecret|UpdateStore|GitHubClient|Transcripts|TranscriptStore|""" +
            """TranscriptCodec|TranscriptLine|VoiceprintFiles|Voiceprints|SettingsStore|RobotSettings|VoiceHub)\b|\bapp\.heyrobot\.(helpers|update|core\.helpers)\b""")
        val reportCode = ours.filterKeys { it == "app/sys/Problems.kt" || it == "core/core/ModelProblems.kt" }
        assertEquals(2, reportCode.size)
        assertEquals(emptyList<String>(), usersOf(secrets, reportCode))
        // The voice process notes a problem with BrainKeeper's own facts only (the plan, the memory, :brain's pid).
        val keeper = code(File(java("app"), "app/heyrobot/assistant/BrainLink.kt"))
        val note = keeper.substringAfter("private fun noteProblem(").substringBefore("\n    }\n")
        assertTrue(note, note.contains("Problems.note(ctx, p)") && !secrets.containsMatchIn(note))
    }

    /** Contacts and the calendar are asked for by the main app only: nothing in the voice process requests a permission. */
    @Test fun onlyTheMainAppAsksForPermissions() {
        val askers = usersOf(Regex("""RequestMultiplePermissions|RequestPermission\b|requestPermissions\("""))
        assertEquals(listOf("app/ui/MainActivity.kt"), askers)
    }

    /** The main app opens the panel with Activity.showAssist and plain string keys (it may not import the assistant's constants). */
    @Test fun showAssistArgumentsMatchTheAssistant() {
        val vis = File(java("app"), "app/heyrobot/assistant/RobotVoiceService.kt").readText()
        fun const(name: String) = Regex("""const val $name = "([^"]+)"""").find(vis)!!.groupValues[1]
        val ui = File(java("app"), "app/heyrobot/ui/MainActivity.kt").readText()
        assertTrue(ui.contains("putString(\"${const("ARG_SOURCE")}\", \"${const("SRC_APP")}\")"))
        assertTrue(ui.contains("putString(\"${const("ARG_CONVERSATION")}\", it)"))
        // Class names the main and voice processes use as strings.
        val sys = File(java("app"), "app/heyrobot/sys/Assistant.kt").readText()
        assertTrue(sys.contains("\"app.heyrobot.assistant.RobotVoiceService\"") && File(java("app"), "app/heyrobot/assistant/RobotVoiceService.kt").isFile)
        assertTrue(File(java("app"), "app/heyrobot/ui/MainActivity.kt").isFile)
    }

    @Test fun voiceInteractionMetadataNamesTheComponents() {
        val x = File(main("app"), "res/xml/voice_interaction.xml").readText()
        assertTrue(x.contains("android:sessionService=\"app.heyrobot.assistant.RobotSessionService\""))
        assertTrue(x.contains("android:recognitionService=\"app.heyrobot.assistant.RobotRecognitionService\""))
        assertTrue(x.contains("android:supportsAssist=\"true\""))
        assertTrue(x.contains("android:supportsLaunchVoiceAssistFromKeyguard=\"true\""))
        val rec = File(main("app"), "res/xml/recognition_service.xml").readText()
        assertTrue(rec.contains("<recognition-service") && !rec.contains("selectableAsDefault=\"false\""))
    }

    @Test fun keywordsFileIsTokenisedWithTheModelsTokens() {
        val assets = File(main("voice"), "assets")
        val tokens = File(assets, "kws/tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
        // The spotter's own list is one empty line (0.2.3): every stream gets the wake phrases from Settings at runtime,
        // so a phrase the user removed is never spotted. Not 0 bytes (sherpa's asset reader would take it for a C string).
        assertEquals("\n", File(assets, "kws/keywords.txt").readText())
        // The phrases are spelled on the phone with the model's SentencePiece file (core SentencePieceTest).
        assertTrue("kws/bpe.model missing: run tools/fetch_third_party.sh --install", File(assets, "kws/bpe.model").isFile)
        // "STOP" while Robot speaks (WakeWord adds it to the stream; tools/make_keywords.py writes it).
        val stop = File(assets, "kws/stop.txt").readText().trim()
        assertEquals("▁ST O P @STOP", stop)
        stop.substringBefore(" @").split(' ').forEach { assertTrue("token $it not in tokens.txt", it in tokens) }
        assertTrue("espeak-ng-data missing: run tools/fetch_third_party.sh --install", File(assets, "espeak-ng-data/phontab").isFile)
        assertTrue(File(assets, "bench/test-clip.s16").isFile && File(assets, "bench/test-clip.txt").isFile)
        for (f in listOf("encoder", "decoder", "joiner")) assertTrue("$f missing: run tools/fetch_third_party.sh --install", File(assets, "kws/$f-epoch-12-avg-2-chunk-16-left-64.onnx").isFile)
        assertTrue(File(assets, "vad/silero_vad.onnx").isFile)
        for (l in listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")) assertTrue(l, File(main("voice"), "jniLibs/arm64-v8a/$l").isFile)
    }

    /** Source without comments, string literals kept (host names live in them). */
    private fun codeWithStrings(f: File): String = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)^\\s*//[^\n]*"), "")

    /**
     * Settings › Updates. Main process only: nothing of the voice or model processes (or :claude) refers to the updater, and
     * its components have no android:process. GitHubClient is the only code that goes to GitHub, over HTTPS to the hosts core's
     * GitHubHosts allows, following redirects itself; the access token is decrypted there alone and set only on a request
     * GitHubHosts.mayAuthorize allows (api.github.com). The checks before an install can't be skipped: Updater hashes the file
     * and runs ArchiveCheck before it hands it to UpdateInstaller, which hashes it again as it writes the session.
     */
    @Test fun theUpdaterStaysInTheMainProcessAndItsTokenGoesOnlyToGitHubsApi() {
        val updater = Regex("""\bapp\.heyrobot\.(update|core\.update)\b|\b(Updater|UpdateStore|UpdateToken|GitHubClient|UpdateInstaller|UpdateJobs|UpdateNotices)\b""")
        assertEquals(emptyList<String>(), usersOf(updater, ours.filterKeys { it.startsWith("voice/") || it.startsWith("brain/") || it.startsWith("claude/") ||
            it.startsWith("mt/") || it.startsWith("app/assistant/") }))
        // Who uses what.
        assertEquals(listOf("app/update/GitHubClient.kt", "app/update/Updater.kt"), usersOf(Regex("""\bGitHubClient\b""")))
        assertEquals(listOf("app/update/GitHubClient.kt"), usersOf(Regex("""\bUpdateToken\.read\(""")))
        assertEquals(listOf("app/update/UpdateInstaller.kt", "app/update/Updater.kt"), usersOf(Regex("""\bUpdateInstaller\.install\(|\bPackageInstaller\b""")))
        assertEquals(listOf("app/update/UpdateInstaller.kt"), usersOf(Regex("""\bPackageInstaller\b""")))
        assertEquals(listOf("app/update/UpdateInstaller.kt"), usersOf(Regex("""\bcanRequestPackageInstalls\b|getPackageArchiveInfo""")))
        // Only GitHubClient sets an Authorization header with the token, once, on a request GitHubHosts.mayAuthorize allows,
        // and never lets the platform follow a redirect with it. (0.6.0: the only other Authorization header is a helper's
        // own key, set by the :claude module's HelperHttp in the :voice process, which never refers to the updater: above;
        // and web search's request builder in core, SearchWire, names Kagi's key header for a request it never sends itself,
        // not wired yet.)
        assertEquals(listOf("app/update/GitHubClient.kt", "claude/helpers/http/HelperHttp.kt", "core/core/web/SearchWire.kt"),
            ours.filter { codeWithStrings(it.value).contains("\"Authorization\"") }.keys.sorted())
        val client = codeWithStrings(File(java("app"), "app/heyrobot/update/GitHubClient.kt"))
        val auth = client.lines().filter { it.contains("\"Authorization\"") }
        assertEquals(1, auth.size)
        assertTrue(auth.single(), auth.single().contains("if (token != null && GitHubHosts.mayAuthorize(url)) c.setRequestProperty(\"Authorization\", \"Bearer \$token\")"))
        assertTrue(client.contains("c.instanceFollowRedirects = false") && client.contains("if (!GitHubHosts.allowed(url)) throw") &&
            client.contains("GitHubHosts.redirect(url, c.getHeaderField(\"Location\"), ++hop)") && client.contains("as? HttpsURLConnection"))
        // It opens only the URLs core builds from the repository and ids.
        assertTrue(client.contains("repo.releasesUrl()") && client.contains("repo.assetUrl(file.id)"))
        assertEquals(2, Regex("""repo\.assetUrl\(file\.id\)""").findAll(client).count())
        // GitHub's URLs are written in one place, core's GitHub.kt (the Licences list's source link is data, not code).
        assertEquals(listOf("core/core/update/GitHub.kt"), ours.filter { Regex("""https://[^"\s]*github""").containsMatchIn(codeWithStrings(it.value)) }.keys.sorted())
        val hosts = File(java("core"), "app/heyrobot/core/update/GitHub.kt").readText()
        assertTrue(hosts.contains("const val API = \"api.github.com\"") &&
            hosts.contains("val ALLOWED: Set<String> = setOf(API, \"github.com\", \"objects.githubusercontent.com\", \"release-assets.githubusercontent.com\")") &&
            hosts.contains("fun mayAuthorize(url: String): Boolean = hostOf(url) == API"))
        // The token is kept by KeystoreSecret with its own Keystore key, and the default repository is the one the Licences
        // list names.
        val store = File(java("app"), "app/heyrobot/update/UpdateStore.kt").readText()
        assertTrue(store.contains("KeystoreSecret(ALIAS, \"github_token\", \"update_token\")") && store.contains("const val ALIAS = \"robot_github_token\""))
        // The same class keeps every helper's key (sys/HelperFiles.kt, read only by the :voice process's HelperKeys, above);
        // the token's alias and files are named in UpdateStore.kt alone, and no helper's can be them.
        assertEquals(listOf("app/sys/HelperFiles.kt", "app/sys/KeystoreSecret.kt", "app/update/UpdateStore.kt"), usersOf(Regex("""\bKeystoreSecret\(""")))
        assertEquals(listOf("app/update/UpdateStore.kt"), ours.filter { codeWithStrings(it.value).contains("robot_github_token") || codeWithStrings(it.value).contains("\"github_token\"") }.keys.sorted())
        assertTrue(listOf("claude", "github_token", "x").none { app.heyrobot.sys.HelperKeyFiles.alias(it) == "robot_github_token" })
        assertTrue(store.contains("GitHubRepo.fromSourceLink(LicenceBook.parse(json).robot.source)"))
        // The checks before an install, in order: the hash, then what Android says about the APK, then the session.
        val up = File(java("app"), "app/heyrobot/update/Updater.kt").readText()
        val body = up.substringAfter("internal fun install(ctx: Context): String? {").substringBefore("\n    }\n")
        val hash = body.indexOf("sha256(file) != d.sha256")
        val archive = body.indexOf("ArchiveCheck.check(")
        val session = body.indexOf("UpdateInstaller.install(")
        assertTrue(body, hash in 0 until archive && archive < session)
        val dl = up.substringAfter("internal fun download(").substringBefore("\n    }\n")
        assertTrue(dl.indexOf("Sha256Sums.parse(") in 0 until dl.indexOf("GitHubClient.download(") && dl.contains("ArchiveCheck.check(") && dl.contains("part.renameTo(apk)"))
        val installer = File(java("app"), "app/heyrobot/update/UpdateInstaller.kt").readText()
        assertTrue(installer.contains("if (hex(md.digest()) != sha256) {") && installer.indexOf("session.abandon()") < installer.indexOf("session.commit("))
    }

    /**
     * After an update, as after a restart (README "Updates" › "After an update"): MY_PACKAGE_REPLACED runs the boot path's
     * own routine (afterRestart: the recognizer setting and the assistant check) and then only the updater's part, and
     * neither starts or binds anything. 0.6.0's processes need no step of their own: conversation capture in :voice (a
     * session the update ended is closed when the assistant comes back, with Resume), :mt, :brain and its isolated :llama
     * start when something asks them.
     */
    @Test fun afterAnUpdateRobotDoesWhatItDoesAfterARestart() {
        val boot = code(File(java("app"), "app/heyrobot/boot/BootReceivers.kt"))
        assertEquals(2, Regex("""\bafterRestart\(ctx, """).findAll(boot).count())
        assertTrue(boot.contains("afterRestart(ctx, if (locked) RepairWhen.LOCKED_BOOT else RepairWhen.BOOT)"))
        assertTrue(boot.contains("afterRestart(ctx, RepairWhen.UPDATE) { Updater.afterUpdate(it) }"))
        val starts = Regex("""\b(startService|startForegroundService|bindService|startActivity)\b|\bConversation(Service|Session|Tile\w*)\b|""" +
            """\b(MtLink|MtSpeedRun|BrainLink|BrainClient|BrainKeeper|VoiceHub|HelperLink|Transcripts)\b|\bLlama\w*""")
        assertEquals(emptyList<String>(), usersOf(starts, ours.filterKeys { it.startsWith("app/boot/") }))
        val up = code(File(java("app"), "app/heyrobot/update/Updater.kt"))
        val after = up.substringAfter("fun afterUpdate(ctx: Context) {").substringBefore("\n    }\n")
        assertTrue(after, after.contains("UpdateJobs.ensure(") && !starts.containsMatchIn(after))
    }

    /**
     * Privacy (spec item 10): no network code but the :claude module's SDK client and, since 0.4.0, the one Open-Meteo
     * client for the weather (DownloadManager does the model downloads), no audio files, no raw logging, never an
     * accessibility service (Ask Claude must not drive the Claude app).
     */
    @Test fun noNetworkNoAudioFilesNoRawLogs() {
        // java.net.IDN only spells a host name in ASCII, like URLEncoder and URI (core web WebUrl, not wired yet).
        assertEquals(listOf("app/sys/NominatimClient.kt", "app/sys/OpenMeteoClient.kt", "app/update/GitHubClient.kt"),
            usersOf(Regex("""\bjava\.net\.(?!URLEncoder|URI\b|IDN\b)|HttpURLConnection|HttpsURLConnection|\bSocket\b|okhttp|\bWebView\b|\bopenConnection\("""),
            ours.filterKeys { !it.startsWith("claude/") }))
        // 0.4.0: the nearest place for the maps goes only to the Nominatim server in Settings (OpenStreetMap's unless you chose
        // another, checked by core NearbyServer: HTTPS only), a second apart at most, its raw request private, and only the
        // maps tools use it (core Nearby.kt builds the URL).
        val osm = File(java("app"), "app/heyrobot/sys/NominatimClient.kt").readText()
        assertTrue(osm.contains("require(u.protocol == \"https\" && u.host == server.host && u.port == server.port") &&
            osm.contains("c.instanceFollowRedirects = false") && osm.contains("searches.search("))
        assertTrue(File(java("app"), "app/heyrobot/assistant/ToolExecutor.kt").readText().contains("val server = settings.nearbyServer"))
        assertTrue(File(java("core"), "app/heyrobot/core/Nearby.kt").readText().contains("val base: String get() = \"https://\" + name + path"))
        assertTrue(osm.contains("private fun get(") && !osm.contains("    fun get("))
        // The pace (a second apart at most) and the cache are core's NearbySearch, every request through it.
        val nearby = File(java("core"), "app/heyrobot/core/Nearby.kt").readText()
        assertTrue(nearby.contains("pace(abort)") && nearby.contains("Nearby.MIN_GAP_MS") && nearby.contains("Nearby.CACHE_MS"))
        assertEquals(listOf("app/assistant/ToolExecutor.kt", "app/sys/NominatimClient.kt"), usersOf(Regex("""\bNominatimClient\b""")))
        // It goes only to Open-Meteo's two hosts, over HTTPS (core Weather.kt builds the URLs), its raw request is private, and
        // only the weather tool and Settings › Weather use it.
        val meteo = File(java("app"), "app/heyrobot/sys/OpenMeteoClient.kt").readText()
        assertTrue(Regex("""private val HOSTS = setOf\("api\.open-meteo\.com", "geocoding-api\.open-meteo\.com"\)\n""").containsMatchIn(meteo))
        assertTrue(meteo.contains("require(u.protocol == \"https\" && u.host in HOSTS)") && meteo.contains("c.instanceFollowRedirects = false"))
        assertTrue(meteo.contains("private fun get(") && !meteo.contains("    fun get("))
        assertEquals(listOf("app/assistant/ToolExecutor.kt", "app/sys/OpenMeteoClient.kt", "app/ui/ToolsSettings.kt"), usersOf(Regex("""\bOpenMeteoClient\b""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""AccessibilityService|accessibilityservice""")))
        // The only URLs in code (comments that cite a page don't count): Gemma's and the voice models' pinned Hugging Face
        // files and the fast translation engine's pinned Mozilla files (0.5.0), fetched by DownloadManager, the claude.ai link
        // the Claude app hand-off tries (the API's host is the SDK's own), Open-Meteo's two and Nominatim's (0.4.0), and the
        // helpers' companies' API addresses (0.6.0, core Helpers.kt's presets, used only by the :claude module's clients in
        // the :voice process, with your key, when you ask), and the example addresses Settings › Helpers shows when you type
        // your server's (text only, never fetched). Licences.kt (0.4.5) fetches none: it only checks that the licence list's
        // links (assets/licenses/components.json) are https. The updater's (GitHub's API, a release's page) are built in
        // core's GitHub.kt alone, from the repository and ids. Web search's request builders (core web SearchWire and
        // Wikipedia: Brave, Kagi, Mojeek and Wikipedia's API addresses) are built in core but not wired yet: nothing fetches them.
        assertEquals(listOf("app/ui/HelpersScreens.kt", "core/core/ClaudeApp.kt", "core/core/Licences.kt", "core/core/ModelState.kt", "core/core/MtBundle.kt",
            "core/core/Nearby.kt", "core/core/VoiceModels.kt", "core/core/Weather.kt", "core/core/helpers/Helpers.kt", "core/core/update/GitHub.kt",
            "core/core/web/SearchWire.kt", "core/core/web/Wikipedia.kt"),
            ours.filter { withoutComments(it.value).contains("https://") }.keys.sorted())
        val audio = ours.filterKeys { it.startsWith("voice/") || it.startsWith("app/assistant/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""MediaRecorder\(|\.wav\b|\.pcm\b|AudioRecord[^\n]*(FileOutputStream|write\()"""), audio))
        assertEquals(emptyList<String>(), usersOf(Regex("""FileOutputStream|RandomAccessFile|Files\.(write|copy|newOutputStream)"""), ours.filterKeys { it.startsWith("voice/") }))
        // Sherpa's debug output can carry recognised or spoken text: never switched on.
        assertEquals(emptyList<String>(), usersOf(Regex("""debug\s*=\s*true"""), ours))
        // All logging goes through RLog, which keeps numbers, booleans and codes only.
        assertEquals(listOf("core/core/Log.kt"), usersOf(Regex("""\bandroid\.util\.Log\b|\bLog\.[diwev]\(""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bprintln\(|printStackTrace\(""")))
    }

    /**
     * 0.5.0, conversation mode (docs/conversation-mode.md 8): a transcript reaches Robot's model only through the one-shot
     * client, from the session's translations (5.4) and Robot's own summary (5.6); a helper only behind the consent card
     * (5.6); never the network otherwise. The audio stays in memory (clips you pick are read, never written); the session
     * is never started by Robot on its own (not sticky, not from boot); deleting transcripts tells the voice process.
     */
    @Test fun conversationModeStaysOnThePhone() {
        val conv = ours.filterKeys { it.startsWith("app/assistant/conversation/") || it == "voice/voice/ConversationEngine.kt" ||
            it == "core/core/TranscriptStore.kt" || it == "core/core/TranscriptionCheck.kt" || it == "core/core/ConversationCapture.kt" ||
            it == "app/sys/Transcripts.kt" || it == "app/ui/TranscriptsScreen.kt" }
        assertTrue(conv.keys.toString(), conv.size >= 10)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bBrainClient\b|\bINTERNET\b|\bjava\.net\b"""), conv))
        // Robot's model: the session and a summary hold it loaded; translations and summaries ask it through the one-shot
        // client only.
        assertEquals(listOf("app/assistant/conversation/ConversationSession.kt", "app/assistant/conversation/ConversationTranslations.kt",
            "app/assistant/conversation/SummaryActivity.kt", "app/assistant/conversation/SummaryJobs.kt"),
            usersOf(Regex("""\bBrain(Keeper|Link|OneShots)\b"""), conv))
        assertEquals(listOf("app/assistant/conversation/ConversationTranslations.kt", "app/assistant/conversation/SummaryJobs.kt"),
            usersOf(Regex("""\bBrainOneShots\b"""), conv))
        // Claude: only the helpers, which act on a SummaryJob (made by the card's Send) and check HelperGate first.
        assertEquals(listOf("app/assistant/conversation/Helpers.kt"), usersOf(Regex("""\bClaude(Session|Client|Listener|Outcome)\b|\btranscriptSession\b"""), conv))
        val helpers = code(File(java("app"), "app/heyrobot/assistant/conversation/Helpers.kt"))
        assertEquals(2, Regex("""HelperGate\.allowed\(job\)""").findAll(helpers).count())
        // 0.6.0: never another helper's session (any company's API, your own server, Test connection), a key or Robot's
        // actions: HelperLink only for Claude's transcript session (and whether Claude's key is saved, for the card)...
        assertEquals(emptyList<String>(), usersOf(Regex("""\bHelperLink\.session\(|\bHelper(Session|Http|Probe|Keys|KeyFiles)\b|\bOpenAiSession\b|""" +
            """\bapp\.heyrobot\.helpers\b|\bClaudeTools\b"""), conv))
        assertEquals(listOf("app/assistant/conversation/Helpers.kt", "app/assistant/conversation/SummaryActivity.kt"), usersOf(Regex("""\bHelperLink\b"""), conv))
        // (and whether Claude's key couldn't be read just now or was removed, for the summary's words), nothing else of it.
        val linkUses = conv.values.flatMap { f -> Regex("""\bHelperLink\.(\w+)""").findAll(code(f)).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(linkUses.toString(), setOf("transcriptSession", "done", "networkAllowed", "keySaved", "unavailable", "unreadable").containsAll(linkUses))
        // ...which is Claude's, with its fixed prompt, no tools and no web search.
        val link = code(File(java("app"), "app/heyrobot/assistant/HelperLink.kt"))
        assertTrue(Regex("""fun transcriptSession\([^{]*\{[^}]*HelperConfig\.claude\(\)[^}]*ClaudeSession\([^)]*\bemptyList\(\), search = false, """ +
            """conversation = false, system = ClaudePrompt\.TRANSCRIPT_SYSTEM\)""").containsMatchIn(link))
        // The summary screen and the summaries' job are in the voice process (the main app opens the screen by name).
        assertEquals(emptyList<String>(), usersOf(Regex("""\bSummary(Jobs|Activity)\b|\bClaude(Api|App)Helper\b"""), ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }))
        // The clip reader reads what you picked; nothing in conversation mode writes audio.
        assertEquals(emptyList<String>(), usersOf(Regex("""openOutputStream|FileOutputStream|MediaMuxer|MediaRecorder|AudioRecord\b"""),
            conv.filterKeys { it.startsWith("app/assistant/") || it.startsWith("voice/") }))
        val service = code(File(java("app"), "app/heyrobot/assistant/conversation/ConversationService.kt"))
        assertTrue(service.contains("START_NOT_STICKY") && !service.contains("START_STICKY\n") && !Regex("""return START_STICKY\b""").containsMatchIn(service))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bConversation(Service|Session)\b"""), ours.filterKeys { it.startsWith("app/boot/") }))
        // Deleting in the app always tells the voice process (so a live or recovering session can't write it back).
        val tx = code(File(java("app"), "app/heyrobot/sys/Transcripts.kt"))
        assertTrue(tx.contains("Cmd.send(ctx, Cmd.TRANSCRIPTS_DELETED"))
        assertTrue(tx.contains("s.deleteAll()") && tx.contains("s.delete(id)"))
        assertEquals(emptyList<String>(), usersOf(Regex("""Transcripts\.store\([^)]*\)\.(delete|deleteAll)\("""), ours.filterKeys { it.startsWith("app/") }))
        val hub = code(File(java("app"), "app/heyrobot/assistant/VoiceHub.kt"))
        assertTrue(hub.contains("Cmd.TRANSCRIPTS_DELETED -> ConversationSession.transcriptsDeleted("))
        // The wake phrases sleep during a session unless the setting says otherwise, and come back after.
        val session = code(File(java("app"), "app/heyrobot/assistant/conversation/ConversationSession.kt"))
        assertTrue(session.contains("VoiceHub.conversationWake(!s.convWakeDuring)") && session.contains("VoiceHub.conversationWake(false)"))
        // The main app opens the conversation screens by name only.
        val ui = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.assistant\b"""), ui))
        assertTrue(File(java("app"), "app/heyrobot/sys/Transcripts.kt").readText().contains("\"app.heyrobot.assistant.conversation.ConversationActivity\""))
    }

    /**
     * 0.8, routines and the morning brief (design §2.5, §2.8): nothing a routine holds or says goes online except the
     * weather to Open-Meteo through the weather tool's own code, nothing reaches a model or a helper, no routine's words
     * reach a log line, the one runtime receiver another app may reach is Clock's two alarm broadcasts, the alarm is never
     * Android's "next alarm", and the main app names :voice's receiver by its class only.
     */
    @Test fun routinesStayOnThePhone() {
        val routines = ours.filterKeys { it.startsWith("app/assistant/routines/") || it == "app/sys/Routines.kt" || it.startsWith("app/ui/settings/Routine") ||
            it in setOf("core/core/Routine.kt", "core/core/RoutineGate.kt", "core/core/RoutineRouter.kt", "core/core/RoutineSchedule.kt", "core/core/RoutineStore.kt",
                "core/core/RoutineRuntime.kt", "core/core/Brief.kt", "core/core/AlarmWatch.kt") }
        assertTrue(routines.keys.toString(), routines.size >= 15)
        // No network of their own, no model, no helper; the weather goes through ToolExecutor (Open-Meteo's only client).
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bOpenMeteoClient\b|\bNominatimClient\b|\bBrain(Client|Keeper|Link|OneShots)\b|""" +
            """\bHelper(Link|Session|Http)\b|\bClaude(Session|Client)\b|\bapp\.heyrobot\.helpers\b|\bokhttp3\b"""), routines))
        assertEquals(listOf("app/assistant/routines/RoutineRunner.kt"), usersOf(Regex("""\.briefWeather\(\)"""), routines))
        // Logs: codes and numbers only; never a routine's name, steps, words or a reminder.
        val leaky = Regex("""RLog\.[iw]\([^\n]*\b(name|text|lines|describe|reminder|title|said)\b""")
        assertEquals(emptyList<String>(), usersOf(leaky, routines))
        // The only receiver another app can reach: Clock's ALARM_ALERT and ALARM_DONE, in the trigger hub.
        assertEquals(listOf("app/assistant/routines/RoutineHub.kt"), usersOf(Regex("""\bRECEIVER_EXPORTED\b""")))
        val hub = withoutComments(File(java("app"), "app/heyrobot/assistant/routines/RoutineHub.kt"))
        assertEquals(1, Regex("""RECEIVER_EXPORTED""").findAll(hub).count())
        assertTrue(hub.contains("\"com.android.deskclock.ALARM_ALERT\"") && hub.contains("\"com.android.deskclock.ALARM_DONE\""))
        // One exact allow-while-idle alarm, never setAlarmClock (it would become Android's "next alarm").
        assertEquals(emptyList<String>(), usersOf(Regex("""\.setAlarmClock\(""")))
        assertEquals(listOf("app/sys/Assistant.kt", "app/sys/Routines.kt"), usersOf(Regex("""\.setExactAndAllowWhileIdle\(""")))
        // The main app reaches :voice's receiver by its class name, which must be the real one.
        assertTrue(File(java("app"), "app/heyrobot/sys/Routines.kt").readText().contains("\"app.heyrobot.assistant.routines.RoutineAlarmReceiver\""))
        assertTrue(File(java("app"), "app/heyrobot/assistant/routines/RoutineAlarmReceiver.kt").isFile)
        // The speaking service is never sticky and never started at boot.
        val svc = code(File(java("app"), "app/heyrobot/assistant/routines/RoutineSpeakingService.kt"))
        assertTrue(svc.contains("START_NOT_STICKY") && !Regex("""return START_STICKY\b""").containsMatchIn(svc))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bRoutineSpeakingService\b"""), ours.filterKeys { it.startsWith("app/boot/") }))
        // Saving a routine by voice goes through its card (RoutineDraft.save after the card's answer), never straight to the file;
        // a routine from the screen's text needs the tap.
        val panel = code(File(java("app"), "app/heyrobot/assistant/routines/PanelRoutines.kt"))
        assertEquals(1, Regex("""\bdraft\.save\(""").findAll(panel).count())
        assertTrue(Regex("""val yes = host\.ask\([^\n]*\n[^\n]*tapOnly = if \(draft\.fromContent\)""").containsMatchIn(panel))
        assertTrue(panel.indexOf("val yes = host.ask(") < panel.indexOf("draft.save("))
    }

    /**
     * 0.5.0, phase 5.3 (docs/conversation-mode.md 3, 8): the speaker model is downloaded by the main app only, like every
     * model; the voiceprint is decrypted in the :voice process only, seeds the session and nothing else; nothing about
     * speakers goes online or into a log but numbers.
     */
    @Test fun speakerLabelsAndTheVoiceprintStayOnThePhone() {
        // Downloads: DownloadManager in the main app's model stores (the fast engine's too), and those only from the main app's screens.
        assertEquals(listOf("app/sys/ModelStore.kt", "app/sys/MtModelStore.kt", "app/sys/VoiceModelStore.kt"), usersOf(Regex("""\bDownloadManager\b""")))
        val dl = usersOf(Regex("""\bVoiceModelStore\b"""))
        assertTrue(dl.toString(), dl.isNotEmpty() && dl.all { it.startsWith("app/ui/") || it.startsWith("app/sys/") })
        // The voiceprint: decrypted in one place (the session's start), encrypted and read only by the :voice code.
        assertEquals(listOf("app/assistant/conversation/ConversationSession.kt"), usersOf(Regex("""\bVoiceprints\.read\(""")))
        assertEquals(listOf("app/assistant/conversation/VoiceprintActivity.kt"), usersOf(Regex("""\bVoiceprints\.save\(""")))
        assertEquals(listOf("app/assistant/conversation/Voiceprints.kt"), usersOf(Regex("""\bVoiceprintCodec\.(open|seal)\(""")))
        val main = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bVoiceprints\b|\bVoiceprintCodec\.(open|seal|decode)\b"""), main))
        // The only cipher in those files is KeystoreSecret's (the updater's token in the main process, the helpers' keys in
        // :voice), which never sees a voiceprint.
        assertEquals(listOf("app/sys/KeystoreSecret.kt"), usersOf(Regex("""\bCipher\b"""), main))
        assertTrue(!Regex("""\bVoiceprint""").containsMatchIn(code(File(java("app"), "app/heyrobot/sys/KeystoreSecret.kt"))))
        // Its Keystore key is its own, never a helper's (0.6.0: Claude's key is a helper's key, under its old alias).
        val keys = code(File(java("app"), "app/heyrobot/assistant/conversation/Voiceprints.kt"))
        assertTrue(keys.contains("VoiceprintCodec.KEY_ALIAS") && !Regex("""\bHelperKey(s|Files)\b|\bClaudeKey""").containsMatchIn(keys))
        val alias = app.heyrobot.core.VoiceprintCodec.KEY_ALIAS
        assertTrue(alias != app.heyrobot.sys.HelperKeyFiles.CLAUDE_ALIAS && !alias.startsWith(app.heyrobot.sys.HelperKeyFiles.alias("x").removeSuffix("x")))
        // Never in settings: the setting is a flag.
        val settings = code(File(java("core"), "app/heyrobot/core/Settings.kt"))
        assertTrue(settings.contains("val meVoiceprint: Boolean"))
        assertTrue(!Regex("""\bVoiceprint\b|FloatArray""").containsMatchIn(settings))
        // Nothing about speakers goes online, and the logs get numbers only: no name, label, text or voice in an RLog call.
        val spk = ours.filterKeys { it in setOf("core/core/Speakers.kt", "core/core/Voiceprint.kt", "core/core/SpeakerClusterer.kt", "voice/voice/SpeakerEmbedder.kt",
            "voice/voice/ConversationEngine.kt", "app/assistant/conversation/Voiceprints.kt", "app/assistant/conversation/VoiceprintActivity.kt",
            "app/assistant/conversation/ConversationSession.kt", "app/sys/Speakers.kt", "app/ui/SpeakerUi.kt") }
        assertEquals(10, spk.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bINTERNET\b"""), spk))
        val leaky = Regex("""RLog\.[iw]\([^\n]*\b(name|names|label|speaker|vector|embedding|voice|voices|mine|text|heard)\b""")
        assertEquals(emptyList<String>(), usersOf(leaky, spk))
        // The speaker model loads with sherpa's debug output off, like every model.
        assertTrue(code(File(java("voice"), "app/heyrobot/voice/SpeakerEmbedder.kt")).contains("debug = false"))
    }
}
