package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Build check (source, Gradle and merged-manifest scan), as the probe's: LiteRT-LM lives only in :brain's
 * GemmaEngine.kt and runs only in the :brain process; sherpa-onnx lives only in the :voice module and runs
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
        // 0.5.0: speaker labels use sherpa-onnx's speaker embedding API (the JNI was already in the library).
        assertTrue(users.toString(), users.contains("voice/voice/SpeakerEmbedder.kt"))
        for (f in listOf("Speaker.kt", "SpeakerEmbeddingExtractorConfig.kt")) assertTrue(f, File(java("voice"), "com/k2fsa/sherpa/onnx/$f").isFile)
        // Only the copied sherpa-onnx API loads its native library.
        assertEquals(emptyList<String>(), usersOf(Regex("""System\.load(Library)?\s*\(""")))
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
            "app.heyrobot.assistant.CheogramPickActivity",
            // 0.5.0, conversation mode: where the listener and the speech model are.
            "app.heyrobot.assistant.conversation.ConversationService", "app.heyrobot.assistant.conversation.ConversationActivity",
            "app.heyrobot.assistant.conversation.TranscriptionCheckActivity", "app.heyrobot.assistant.conversation.ConversationTileService",
            // 0.5.0, phase 5.3: "Your voice" uses the speaker model and the voiceprint's key, both only in :voice.
            "app.heyrobot.assistant.conversation.VoiceprintActivity")
        for (n in voice) assertEquals(n, ":voice", proc(n))
        assertEquals(":brain", proc("app.heyrobot.brain.BrainService"))
        val main = listOf("app.heyrobot.ui.MainActivity", "app.heyrobot.boot.BootReceiver", "app.heyrobot.boot.BootCheckReceiver",
            "app.heyrobot.people.CheogramListener", "app.heyrobot.sys.ExportProvider")
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
            "app.heyrobot.assistant.CheogramPickActivity", "app.heyrobot.assistant.conversation.ConversationService",
            "app.heyrobot.assistant.conversation.ConversationActivity", "app.heyrobot.assistant.conversation.TranscriptionCheckActivity",
            "app.heyrobot.assistant.conversation.VoiceprintActivity", "app.heyrobot.sys.ExportProvider"))
            assertEquals(n, "false", exported(n))
        // 0.5.0: the session is a microphone foreground service; the tile only the system binds.
        assertEquals("microphone", c("app.heyrobot.assistant.conversation.ConversationService").getAttributeNS(ns, "foregroundServiceType"))
        assertEquals("true", exported("app.heyrobot.assistant.conversation.ConversationTileService"))
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", perm("app.heyrobot.assistant.conversation.ConversationTileService"))
        // The export provider shares the export folder only.
        assertEquals("app.heyrobot.exports", c("app.heyrobot.sys.ExportProvider").getAttributeNS(ns, "authorities"))
        assertEquals("<paths>\n    <cache-path name=\"exports\" path=\"exports/\" />\n</paths>",
            File(main("app"), "res/xml/export_paths.xml").readText().substringAfter("-->").trim().replace("\r", ""))
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
        assertEquals("43", m.getAttributeNS(ns, "versionCode"))
        assertEquals("0.4.3", m.getAttributeNS(ns, "versionName"))
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
        // 0.4.0: the nearest place for the maps goes only to OpenStreetMap's Nominatim, over HTTPS, a second apart at most,
        // its raw request private, and only the maps tools use it (core Nearby.kt builds the URL).
        val osm = File(java("app"), "app/heyrobot/sys/NominatimClient.kt").readText()
        assertTrue(osm.contains("private val HOSTS = setOf(Nearby.HOST)") && osm.contains("require(u.protocol == \"https\" && u.host in HOSTS)") &&
            osm.contains("c.instanceFollowRedirects = false") && osm.contains("searches.search("))
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
        assertEquals(listOf("core/core/ClaudeApp.kt", "core/core/ModelState.kt", "core/core/Nearby.kt", "core/core/VoiceModels.kt", "core/core/Weather.kt"),
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

    /**
     * 0.5.0, conversation mode (docs/conversation-mode.md 8): transcripts never reach a model, a helper or the network in
     * this phase; the audio stays in memory (clips you pick are read, never written); the session is never started by
     * Robot on its own (not sticky, not from boot); deleting transcripts tells the voice process.
     */
    @Test fun conversationModeStaysOnThePhone() {
        val conv = ours.filterKeys { it.startsWith("app/assistant/conversation/") || it == "voice/voice/ConversationEngine.kt" ||
            it == "core/core/TranscriptStore.kt" || it == "core/core/TranscriptionCheck.kt" || it == "core/core/ConversationCapture.kt" ||
            it == "app/sys/Transcripts.kt" || it == "app/ui/TranscriptsScreen.kt" }
        assertTrue(conv.keys.toString(), conv.size >= 10)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bClaude(Link|Session|Client)\b|\bBrain(Client|Keeper|Link)\b|\bINTERNET\b|\bjava\.net\b"""), conv))
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
     * 0.5.0, phase 5.3 (docs/conversation-mode.md 3, 8): the speaker model is downloaded by the main app only, like every
     * model; the voiceprint is decrypted in the :voice process only, seeds the session and nothing else; nothing about
     * speakers goes online or into a log but numbers.
     */
    @Test fun speakerLabelsAndTheVoiceprintStayOnThePhone() {
        // Downloads: DownloadManager in the main app's two model stores, and those only from the main app's screens.
        assertEquals(listOf("app/sys/ModelStore.kt", "app/sys/VoiceModelStore.kt"), usersOf(Regex("""\bDownloadManager\b""")))
        val dl = usersOf(Regex("""\bVoiceModelStore\b"""))
        assertTrue(dl.toString(), dl.isNotEmpty() && dl.all { it.startsWith("app/ui/") || it.startsWith("app/sys/") })
        // The voiceprint: decrypted in one place (the session's start), encrypted and read only by the :voice code.
        assertEquals(listOf("app/assistant/conversation/ConversationSession.kt"), usersOf(Regex("""\bVoiceprints\.read\(""")))
        assertEquals(listOf("app/assistant/conversation/VoiceprintActivity.kt"), usersOf(Regex("""\bVoiceprints\.save\(""")))
        assertEquals(listOf("app/assistant/conversation/Voiceprints.kt"), usersOf(Regex("""\bVoiceprintCodec\.(open|seal)\(""")))
        val main = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bVoiceprints\b|\bVoiceprintCodec\.(open|seal|decode)\b|\bCipher\b"""), main.filterKeys { it != "app/sys/ClaudeFiles.kt" }))
        // Its Keystore key is its own, never the Claude key's.
        val keys = code(File(java("app"), "app/heyrobot/assistant/conversation/Voiceprints.kt"))
        assertTrue(keys.contains("VoiceprintCodec.KEY_ALIAS") && !keys.contains("ClaudeKeyFiles"))
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
