package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Build check (source, Gradle and merged-manifest scan), as the probe's: LiteRT-LM lives only in :brain's
 * GemmaEngine.kt and runs only in the :brain process (0.4.5: llama.cpp too, loaded only by :brain's LlamaNative.kt and
 * built only by tools/build_llama.sh); sherpa-onnx lives only in the :voice module and runs
 * only in the :voice process; nothing the main (UI) process runs refers to either. Also: the components
 * sit in their processes, the recognizer stays discoverable (the restart finding), exported components
 * are guarded, the permissions are the expected few, nothing but DownloadManager, the Claude SDK, the Open-Meteo client
 * (the weather) and the Nominatim client (the nearest place, 0.4.0) goes online, and those two only from the :voice
 * process's tools, audio is never written to disk and nothing logs except through RLog (which drops content).
 */
class ProcessIsolationTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun main(module: String) = File(root, "$module/src/main")
    private fun java(module: String) = File(main(module), "java")
    /** Kotlin and Java (the TTS callback sherpa's JNI calls is Java), so every scan below covers both. */
    private fun sources(dir: File) = dir.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
    private val modules = listOf("core", "brain", "voice", "claude", "app")
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
        // Only the copied sherpa-onnx API loads its native library (and, 0.4.5, :brain's llama.cpp bridge loads Robot's own).
        assertEquals(listOf("brain/brain/LlamaNative.kt"), usersOf(Regex("""System\.load(Library)?\s*\(""")))
        val sherpa = sources(File(java("voice"), "com/k2fsa"))
        assertTrue(sherpa.any { Regex("""System\.loadLibrary\("sherpa-onnx-jni"\)""").containsMatchIn(it.readText()) })
        for (m in listOf("core", "brain", "app")) assertTrue("$m has sherpa sources", !File(java(m), "com/k2fsa").exists())
    }

    /** Ask Claude: the Anthropic SDK lives only in the :claude module, which only the assistant (the :voice process) uses. */
    @Test fun anthropicSdkOnlyInTheClaudeModule() {
        val users = usersOf(Regex("""\bcom\.anthropic\b"""))
        assertTrue(users.toString(), users.isNotEmpty() && users.all { it.startsWith("claude/") })
        val claudeUsers = usersOf(Regex("""\bapp\.heyrobot\.claude\b"""), ours.filterKeys { !it.startsWith("claude/") })
        assertTrue(claudeUsers.toString(), claudeUsers.isNotEmpty() && claudeUsers.all { it.startsWith("app/assistant/") })
        fun deps(m: String) = File(root, "$m/build.gradle").readText()
        assertTrue(deps("claude").contains("com.anthropic:anthropic-java:"))
        for (m in listOf("core", "brain", "voice", "app")) assertTrue(m, !deps(m).contains("com.anthropic"))
        for (m in listOf("core", "brain", "voice")) assertTrue(m, !deps(m).contains("project(':claude')"))
        assertTrue(deps("app").contains("project(':claude')"))
        assertTrue(!deps("claude").contains("litert") && !deps("claude").contains("project(':voice')") && !deps("claude").contains("project(':brain')"))
        // The API key is decrypted in one place, in the :voice process, and handed only to the SDK client.
        assertEquals(listOf("app/assistant/ClaudeLink.kt"), usersOf(Regex("""\bClaudeKeys\.read\(""")))
        assertTrue(usersOf(Regex("""\bClaudeKeys\b""")).all { it.startsWith("app/assistant/") })
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
                k == "app/RobotApp.kt"
        }
        assertTrue(mainFiles.size >= 25)
        val forbidden = Regex("""\bapp\.heyrobot\.(assistant|voice|brain|claude)\b|\bcom\.k2fsa\b|litertlm|\bcom\.anthropic\b|\b(GemmaEngine|BrainService|WakeWord|Listener|VoiceHub|PanelController|BrainKeeper|BrainClient|VoiceEngines|RobotSession|RobotVoiceService|ClaudeLink|ClaudeKeys|ClaudeSession|ClaudeClient|ClaudeKeyActivity)\b""")
        assertEquals(emptyList<String>(), usersOf(forbidden, mainFiles))
        // And the :voice code must not load LiteRT either (it binds :brain by class name).
        assertEquals(emptyList<String>(), usersOf(Regex("""litertlm|\bGemmaEngine\b|\bBrainService\b"""), ours.filterKeys { it.startsWith("app/assistant/") || it.startsWith("voice/") }))
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
            "app.heyrobot.assistant.ClaudeKeyActivity", "app.heyrobot.assistant.MediaDeleteActivity",
            "app.heyrobot.assistant.CheogramPickActivity")
        for (n in voice) assertEquals(n, ":voice", proc(n))
        assertEquals(":brain", proc("app.heyrobot.brain.BrainService"))
        val main = listOf("app.heyrobot.ui.MainActivity", "app.heyrobot.boot.BootReceiver", "app.heyrobot.boot.BootCheckReceiver",
            "app.heyrobot.people.CheogramListener")
        for (n in main) assertEquals(n, "", proc(n))
        // Every Robot component is accounted for (a new one must be placed deliberately).
        val ourComps = comps.keys.filter { it.startsWith("app.heyrobot.") }.toSet()
        assertEquals(main.toSet() + "app.heyrobot.brain.BrainService" + voice.filter { it.startsWith("app.heyrobot.") }, ourComps)
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
            "app.heyrobot.assistant.ClaudeKeyActivity", "app.heyrobot.assistant.MediaDeleteActivity",
            "app.heyrobot.assistant.CheogramPickActivity"))
            assertEquals(n, "false", exported(n))
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
        assertEquals("46", m.getAttributeNS(ns, "versionCode"))
        assertEquals("0.4.6", m.getAttributeNS(ns, "versionName"))
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
        ), used)
        // Never all files, never stopping other apps (Android 14 and later wouldn't let it anyway).
        assertTrue(listOf("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "KILL_BACKGROUND_PROCESSES").none { "android.permission.$it" in used })
        assertTrue("android.permission.ACCESS_FINE_LOCATION" !in used && "android.permission.ACCESS_BACKGROUND_LOCATION" !in used)
        // Package visibility through <queries> only.
        assertTrue("android.permission.QUERY_ALL_PACKAGES" !in used && "android.permission.SEND_SMS" !in used && "android.permission.WRITE_CONTACTS" !in used)
        // Ask Claude needs nothing new: INTERNET stays the only network permission.
        assertTrue(used.none { it.contains("NETWORK_STATE") || it.contains("WIFI") || it.contains("ACCESSIBILITY") })
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
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
     * 0.4.1, Settings › Model: every model still downloads by DownloadManager from the main app's stores, and only the main
     * app downloads, imports, checks or deletes one; the voice process only asks which file to load (ModelStore.plan), and
     * :brain reads a model's header (core LiteRtLm, no network) before it loads it.
     */
    @Test fun modelsDownloadOnlyFromTheMainApp() {
        assertEquals(listOf("app/sys/ModelStore.kt", "app/sys/VoiceModelStore.kt"), usersOf(Regex("""\bDownloadManager\b""")))
        assertEquals(listOf("app/ui/MainActivity.kt"), usersOf(Regex("""\bModelStore\.(startDownload|import|verify|delete|allowOwnTpu|cancelDownload)\(""")))
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bModelStore\.plan\(""")))
        assertEquals(listOf("brain/brain/GemmaEngine.kt", "app/sys/ModelStore.kt").sorted(), usersOf(Regex("""\bLiteRtLmCheck\.check\(""")))
    }

    /**
     * 0.4.5, your own GGUF file: llama.cpp lives only in the :brain module and runs only in the :brain process. One library,
     * librobot_llama.so, built from the pinned llama.cpp source and Robot's committed JNI bridge by tools/build_llama.sh
     * (never by Gradle), loaded by LlamaNative.kt alone, reached only through LlamaEngine and :brain's Engines; nothing of the
     * voice or main process refers to either. Its JNI functions are exactly LlamaNative's external functions.
     */
    @Test fun llamaCppOnlyInTheBrainProcess() {
        val native = File(java("brain"), "app/heyrobot/brain/LlamaNative.kt").readText()
        assertTrue(native.contains("System.loadLibrary(LIB)") && native.contains("const val LIB = \"robot_llama\""))
        assertEquals(listOf("brain/brain/LlamaNative.kt"), usersOf(Regex("""\bexternal\s+fun\b""")))
        assertEquals(listOf("brain/brain/LlamaEngine.kt", "brain/brain/LlamaNative.kt"), usersOf(Regex("""\bLlamaNative\b""")))
        assertEquals(listOf("brain/brain/Engine.kt", "brain/brain/LlamaEngine.kt"), usersOf(Regex("""\bLlamaEngine\b""")))
        assertEquals(listOf("brain/brain/BrainService.kt", "brain/brain/Engine.kt"), usersOf(Regex("""\bEngines\b""")))
        // The GGUF content check runs where LiteRT-LM's does: the main app's import check and :brain before a load.
        assertEquals(listOf("app/sys/ModelStore.kt", "brain/brain/LlamaEngine.kt"), usersOf(Regex("""\bGgufCheck\.check\(""")))
        // The native sources are :brain's alone; no module builds native code through Gradle.
        for (m in modules) {
            assertTrue(m, !File(root, "$m/build.gradle").readText().contains("externalNativeBuild"))
            if (m != "brain") assertTrue(m, !File(main(m), "cpp").exists())
        }
        val cpp = File(main("brain"), "cpp")
        assertEquals(setOf("CMakeLists.txt", "exports.map", "robot_llama.cpp"), cpp.list()!!.toSet())
        // Each external function is a JNI function of the bridge, and the bridge exports nothing else.
        val bridge = File(cpp, "robot_llama.cpp").readText()
        val externals = Regex("""external\s+fun\s+(\w+)""").findAll(native).map { it.groupValues[1] }.toSet()
        val jni = Regex("""Java_app_heyrobot_brain_LlamaNative_(\w+)\(""").findAll(bridge).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("load", "free", "cancel", "trainContext", "specials", "prepare", "generate"), externals)
        assertEquals(externals, jni)
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
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(LlamaEngine|LlamaNative|Engines|robot_llama)\b"""), ours.filterKeys { !it.startsWith("brain/") }))
    }

    /**
     * 0.4.7, the problem report: only the assistant (BrainKeeper, in the :voice process) notes a model problem, as codes and
     * numbers (core ModelProblem); only the main app builds the report from it (core ProblemReport, whose test checks that
     * nothing personal gets in) and only when you open Settings › Model; and the report's code reads none of your
     * conversations, notes, people or the crash trace (no tombstone in 0.4.7). Nothing sends it: copy and share are yours.
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

    /**
     * Privacy (spec item 10): no network code but the :claude module's SDK client and, since 0.4.0, the one Open-Meteo
     * client for the weather (DownloadManager does the model downloads), no audio files, no raw logging, never an
     * accessibility service (Ask Claude must not drive the Claude app).
     */
    @Test fun noNetworkNoAudioFilesNoRawLogs() {
        assertEquals(listOf("app/sys/NominatimClient.kt", "app/sys/OpenMeteoClient.kt"),
            usersOf(Regex("""\bjava\.net\.(?!URLEncoder|URI\b)|HttpURLConnection|HttpsURLConnection|\bSocket\b|okhttp|\bWebView\b"""),
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
        // The only URLs: Gemma's and the voice models' pinned Hugging Face files, fetched by DownloadManager, the
        // claude.ai link the Claude app hand-off tries (the API's host is the SDK's own), Open-Meteo's two and Nominatim's (0.4.0).
        // Licences.kt (0.4.5) holds none: it only checks that the licence list's links (assets/licenses/components.json) are https.
        assertEquals(listOf("core/core/ClaudeApp.kt", "core/core/Licences.kt", "core/core/ModelState.kt", "core/core/Nearby.kt", "core/core/VoiceModels.kt", "core/core/Weather.kt"),
            ours.filter { it.value.readText().contains("https://") }.keys.sorted())
        val audio = ours.filterKeys { it.startsWith("voice/") || it.startsWith("app/assistant/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""MediaRecorder\(|\.wav\b|\.pcm\b|AudioRecord[^\n]*(FileOutputStream|write\()"""), audio))
        assertEquals(emptyList<String>(), usersOf(Regex("""FileOutputStream|RandomAccessFile|Files\.(write|copy|newOutputStream)"""), ours.filterKeys { it.startsWith("voice/") }))
        // Sherpa's debug output can carry recognised or spoken text: never switched on.
        assertEquals(emptyList<String>(), usersOf(Regex("""debug\s*=\s*true"""), ours))
        // All logging goes through RLog, which keeps numbers, booleans and codes only.
        assertEquals(listOf("core/core/Log.kt"), usersOf(Regex("""\bandroid\.util\.Log\b|\bLog\.[diwev]\(""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bprintln\(|printStackTrace\(""")))
    }
}
