// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.HelperPrompt
import app.heyrobot.core.HelperRouter
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.anthropic.AnthropicClient
import app.heyrobot.helpers.anthropic.AnthropicSession
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

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
    private val modules = listOf("core", "brain", "voice", "helpers", "mt", "app")
    /** Our code (not the copied sherpa-onnx API), as "module/relative/path.kt". */
    private val ours: Map<String, File> by lazy {
        modules.flatMap { m -> sources(File(java(m), "app/heyrobot")).map { "$m/${it.relativeTo(File(java(m), "app/heyrobot")).path}" to it } }.toMap()
    }

    /** Source (Kotlin or Java) without comments and string literals, so only real code references count. */
    private fun code(f: File): String = Source.strip(f.readText(), keepStrings = false, kotlin = f.name.endsWith(".kt"))

    /** Source without comments, string literals kept (a URL in a string is one the code may use). */
    private fun withoutComments(f: File): String = Source.strip(f.readText(), keepStrings = true, kotlin = f.name.endsWith(".kt"))

    private fun usersOf(pattern: Regex, files: Map<String, File> = ours) = files.filter { pattern.containsMatchIn(code(it.value)) }.keys.sorted()

    /** One of our source files, as "module", "path below app/heyrobot". */
    private fun kt(module: String, path: String) = File(java(module), "app/heyrobot/$path")

    // The checks below match code layout-insensitively (Scan): comments gone, whitespace and line breaks ignored. A needle
    // holding a string literal reads the text with its literals (flatText); any other, the code alone (flatCode).
    private fun flatCode(f: File) = Flat(code(f))
    private fun flatText(f: File) = Flat(withoutComments(f))

    /** A Gradle file as it is: what must be absent is looked for here, comments and all, so such a check never narrows. */
    private fun gradleRaw(m: String) = File(root, "$m/build.gradle").readText()
    /** A Gradle file without its comments (a comment marker inside a quoted string, such as a glob, is no comment), flattened. */
    private fun gradle(m: String) = Flat(groovyCode(gradleRaw(m)))
    private fun groovyCode(src: String): String = buildString {
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                c == '\'' || c == '"' -> {
                    val quote = if (src.startsWith("$c$c$c", i)) "$c$c$c" else "$c"
                    var j = i + quote.length
                    while (j < src.length && !src.startsWith(quote, j)) j += if (src[j] == '\\') 2 else 1
                    j = minOf(src.length, j + quote.length)
                    append(src, i, j)
                    i = j
                }
                else -> { append(c); i++ }
            }
        }
    }
    /** A Gradle setting (`name value` or `name = value`) present, whatever the spacing. */
    private fun setting(name: String, value: String) = Regex("""(?<![\w.])${Regex.escape(name)}\s*=?\s*${Regex.escape(value)}(?![\w.])""")
    /** The module's `implementation`/`api` project dependencies. */
    private fun projectDeps(m: String) = Regex("""\b(?:implementation|api)\s*\(?\s*project\s*\(\s*['"]:(\w+)['"]\s*\)""").findAll(gradle(m).text).map { it.groupValues[1] }.toSet()
    /** Every module the Gradle file names (any configuration). */
    private fun projectRefs(m: String) = Regex("""\bproject\s*\(\s*['"]:(\w+)['"]""").findAll(gradleRaw(m)).map { it.groupValues[1] }.toSet()

    private fun xml(f: File) = parse(f).documentElement
    private fun elements(e: Element, tag: String) = e.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }

    /**
     * 0.8: the APK keeps its native libraries and dex files compressed (Android unpacks the libraries into nativeLibraryDir
     * at install: Backend.NPU(nativeLibraryDir), sherpa-onnx, librobotmt and the isolated :llama load them from there), so
     * it stays under GitHub's 100 MB per file; still no minification (JNI looks names up). tools/verify_apk.sh checks the
     * built APK the same way.
     */
    @Test fun theApkKeepsItsLibrariesAndDexCompressed() {
        val gradle = gradle("app")
        val packaging = gradle.body("packaging {")
        assertTrue(packaging.text, packaging.matches(Regex("""(?<!\w)dex\{(?:[^{}]*\b)?${setting("useLegacyPackaging", "true")}""")))
        assertTrue(packaging.text, packaging.matches(Regex("""(?<!\w)jniLibs\{(?:[^{}]*\b)?${setting("useLegacyPackaging", "true")}""")))
        assertTrue(!packaging.matches(setting("useLegacyPackaging", "false")))
        val raw = Flat(gradleRaw("app"))
        assertTrue(gradle.matches(setting("minifyEnabled", "false")) && !raw.matches(setting("minifyEnabled", "true")) &&
            !raw.matches(setting("shrinkResources", "true")))
        assertEquals("true", xml(File(main("app"), "AndroidManifest.xml")).let { elements(it, "application").single().getAttributeNS(ns, "extractNativeLibs") })
        // tools/verify_apk.sh: no library or dex file stored uncompressed, extractNativeLibs set, under 100 MB.
        val verify = File(root, "tools/verify_apk.sh").readText()
        assertTrue(Regex("""\$2\s*==\s*"Stored"""").containsMatchIn(verify) && verify.contains("""lib\/.*\.so$""") && verify.contains("""classes[0-9]*\.dex$"""))
        assertTrue(verify.contains("extractNativeLibs(0x010104ea)=true") && Regex("""-lt\s+100000000\b""").containsMatchIn(verify))
    }

    /**
     * 1.0.1: the APK carries no dependency-info block in its signing block (0x504b4453, which only Google can read) and no
     * META-INF/version-control-info.textproto (the commit it was built from), so a build from a source tarball can match
     * it; tools/verify_apk.sh checks the built APK for both.
     */
    @Test fun theApkCarriesNoDependencyOrVcsInfo() {
        val gradle = gradle("app")
        val deps = gradle.body("dependenciesInfo {")
        assertTrue(deps.text, deps.matches(setting("includeInApk", "false")) && deps.matches(setting("includeInBundle", "false")))
        val release = gradle.body("buildTypes {").body("release {")
        assertTrue(release.text, release.body("vcsInfo {").matches(setting("include", "false")))
        val raw = Flat(gradleRaw("app"))
        assertTrue(!raw.matches(setting("include", "true")) && !raw.matches(setting("includeInApk", "true")) && !raw.matches(setting("includeInBundle", "true")))
        val verify = File(root, "tools/verify_apk.sh").readText()
        assertTrue(Regex("""grep\s+-qw\s+0x504b4453""").containsMatchIn(verify) && verify.contains("'META-INF/version-control-info.textproto'"))
    }

    /**
     * 0.9, the Termux agent: the RUN_COMMAND intent is built and sent only by the
     * :voice process's TermuxSend (startForegroundService, nowhere else), its result receiver is :voice's and not exported,
     * the permission is asked for by the main app only (MainActivity, for Settings › Termux), the runner and its cards
     * stay in the assistant, and no Termux code logs a command, a path, an argument, a name or output.
     */
    @Test fun termuxRunsOnlyFromTheVoiceProcess() {
        assertEquals(listOf("app/assistant/TermuxResultReceiver.kt"), usersOf(Regex("""\bTermuxIntents\.ACTION_RUN_COMMAND\b""")))
        // (0.8: routines' speaking service starts itself the same way.)
        assertEquals(listOf("app/assistant/TermuxResultReceiver.kt", "app/assistant/conversation/ConversationService.kt", "app/assistant/routines/RoutineSpeakingService.kt"),
            usersOf(Regex("""\bstartForegroundService\(""")))
        assertEquals(listOf("app/assistant/TermuxResultReceiver.kt", "app/assistant/TermuxTool.kt"), usersOf(Regex("""\bTermuxSend\b""")))
        // The result PendingIntent: explicit to the receiver, one-shot and mutable (Termux fills the result in), made there only.
        assertEquals(listOf("app/assistant/TermuxResultReceiver.kt"), usersOf(Regex("""\bTermuxResultReceiver\b""")))
        assertHas(flatCode(kt("app", "assistant/TermuxResultReceiver.kt")), "Intent(ctx, TermuxResultReceiver::class.java)",
            "PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE")
        assertEquals(listOf("app/assistant/TermuxRunner.kt", "app/assistant/TermuxTool.kt"), usersOf(Regex("""\bTermuxRunner\b""")))
        // Asked for by MainActivity alone (Settings › Termux's Allow); the main process only checks that it is held.
        assertHas(flatCode(kt("app", "ui/MainActivity.kt")), "launch(arrayOf(app.heyrobot.core.termux.TermuxIntents.PERMISSION_RUN_COMMAND))")
        assertEquals(listOf("app/sys/TermuxFiles.kt", "app/ui/MainActivity.kt", "core/core/termux/TermuxIntents.kt"),
            ours.filter { withoutComments(it.value).contains("PERMISSION_RUN_COMMAND") }.keys.sorted())
        // (1.1: a script named in your own words is PanelTermuxScript's, which says what it printed through TermuxTool.spokenResult.)
        assertEquals(listOf("app/assistant/TermuxPanel.kt", "app/assistant/TermuxTool.kt", "app/assistant/VoiceHub.kt", "app/assistant/PanelActions.kt",
            "app/assistant/PanelController.kt", "app/assistant/PanelTermuxScript.kt").sorted(), usersOf(Regex("""\bTermux(Tool|Panel)\b""")).filter { !it.endsWith("TermuxResultReceiver.kt") && !it.endsWith("TermuxRunner.kt") })
        // 1.0 (injection-termux-policy-unwired): the panel's actions prepare every Termux call through TermuxTool (TermuxPolicy,
        // the lock, the signer, the permission), and TurnRunner never runs one that wasn't (no card, no key: core TurnTest's
        // aTermuxCallNobodyPreparedNeverRuns runs it, and TermuxCalls.prepared below).
        val panel = flatCode(kt("app", "assistant/PanelController.kt"))
        val actions = panel.body("private object PanelActions : TurnActions {")
        assertHas(actions, "override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =" +
            " TermuxTool.prepareTurn(VoiceHub.app, action, untrusted, remote, turnSaid) { prepare(it) }")
        val tool = flatCode(kt("app", "assistant/TermuxTool.kt"))
        assertHas(tool, "if (!action.tool.termux) other(action) else withContext(Dispatchers.IO) { prepare(ctx, action, untrusted, remote, said) }")
        assertEquals(listOf("app/assistant/TermuxTool.kt"), usersOf(Regex("""\bTermuxTool\.facts\b|internal var facts\b""")))
        assertTrue(app.heyrobot.core.TermuxCalls.prepared(app.heyrobot.core.TermuxScriptAction("backup", key = "k", card = app.heyrobot.core.termux.TermuxCardInfo(command = "~/robot/backup"))))
        assertFalse(app.heyrobot.core.TermuxCalls.prepared(app.heyrobot.core.TermuxScriptAction("backup", key = "", card = app.heyrobot.core.termux.TermuxCardInfo(command = "~/robot/backup"))))
        assertFalse(app.heyrobot.core.TermuxCalls.prepared(app.heyrobot.core.TermuxCommandAction("ls", key = "k")))
        // Settings › Termux only asks the voice process for its fixed set-up runs (Cmd.TERMUX), never a command.
        assertHas(flatCode(kt("app", "assistant/VoiceHub.kt")), "Cmd.TERMUX -> TermuxTool.setup(app, i.getStringExtra(Cmd.EXTRA_RUN), i.getStringExtra(Cmd.EXTRA_ID))")
        val toolText = flatText(kt("app", "assistant/TermuxTool.kt"))
        assertHas(toolText.body("fun setup("), "if (what !in setOf(TEST, LIST, FOLDER, SHIZUKU)) return")
        // 1.0, ADB fixes through Shizuku: only a fix's fixed command (core ShizukuPath, by the fix's id), refused first on
        // the lock screen, to a Termux not set up and trusted, the test-key build or one changed since you trusted it, and
        // (1.2) the recognizer fix while another installed recognizer is set, read in :voice itself; the command is never
        // taken from the intent, the panel or the model.
        val shizuku = toolText.body("private suspend fun shizuku(")
        assertInOrder(shizuku, "ShizukuRun.refusal(fixId, check, ready, Assistant.locked(ctx), RecognizerKeeper.kept(ctx))", "runner.run(")
        assertHas(shizuku, "val command = ShizukuPath.command(fix.id) ?: return st", "TermuxRun.command(runner.newId(), command, SHIZUKU_LIMIT_S, TermuxRunKind.SHIZUKU)")
        assertEquals(listOf("app/assistant/TermuxTool.kt", "app/ui/AdbFixesScreen.kt", "core/core/ShizukuFixes.kt", "core/core/termux/TermuxSetup.kt"),
            usersOf(Regex("""\bShizukuPath\b""")))
        assertEquals(listOf("app/ui/AdbFixesScreen.kt"), usersOf(Regex("""\bShizukuPath\.RUN\b"""), ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }))
        // 1.0: the update lock, another signer and the test key are checked as each call is prepared and again as it runs;
        // Robot's own keys by value reach TermuxPolicy (the helpers' and web search's keys :voice already reads, never
        // logged). Never the updater's token, which only GitHubClient reads (above): a GitHub token is refused by its shape.
        val prepare = tool.body("fun prepare(ctx: Context, action: Action")
        assertInOrder(prepare, "check.lock?.let { return lockRefusal(", "TermuxPolicy.command(")
        assertHas(prepare, "TermuxPolicy.command(analysis, source, s, locked, untrusted, own)", "val own = f.ownSecrets(ctx)", "named && action.args.isBlank(), own,")
        val run = tool.body("suspend fun run(ctx: Context, action: Action")
        assertInOrder(run, "check.lock?.let {", "runner.run(plan)")
        assertEquals(listOf("app/assistant/TermuxSecrets.kt", "app/assistant/TermuxTool.kt"), usersOf(Regex("""\bTermuxSecrets\b""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bRLog\.|\bVoiceLog\.|\bUpdateToken\b|\bapp\.heyrobot\.update\b"""),
            ours.filterKeys { it == "app/assistant/TermuxSecrets.kt" }))
        // A GitHub token (the updater's kind) is one of Robot's own keys by its shape, whatever its prefix (core SecretMaskTest too).
        for (t in listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_")) {
            assertTrue(t, app.heyrobot.core.termux.SecretMask.hasRobotKey("echo $t" + "A1b2".repeat(9)))
            assertTrue(t, app.heyrobot.core.termux.SecretMask.hasRobotKey("x=$t" + "z".repeat(30)))
        }
        assertFalse(app.heyrobot.core.termux.SecretMask.hasRobotKey("echo ghp_" + "z".repeat(29)))
        // Content-free logs: no RLog field named for a command's parts (in a call's arguments, however it is wrapped), and
        // the label Termux logs is "Robot".
        val termux = ours.filterKeys { it.contains("/termux/") || Regex("""/Termux[A-Za-z]*\.kt$""").containsMatchIn(it) }
        assertTrue(termux.keys.toString(), termux.size >= 15)
        val leaky = Regex(""""(command|cmd|args|argv|arguments|name|script|path|output|stdout|stderr|errmsg|text)"\s*to\b""")
        assertEquals(emptyList<String>(), termux.filter { (_, f) -> flatText(f).calls(Regex("""\bRLog\.[iw]\(""")).any(leaky::containsMatchIn) }.keys.toList())
        assertEquals("Robot", app.heyrobot.core.termux.TermuxIntents.LABEL)
    }

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
        assertTrue(sherpa.any { Regex("""System\.loadLibrary\s*\(\s*"sherpa-onnx-jni"\s*\)""").containsMatchIn(withoutComments(it)) })
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
        val slimt = flatText(kt("mt", "mt/Slimt.kt"))
        assertHas(slimt, "System.loadLibrary(\"robotmt\")")
        assertEquals(1, slimt.count(Regex("""System\.load""")))
        // The engine's classes are used only inside the module.
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(Slimt|BergamotEngine|MtService)\b|\bapp\.heyrobot\.mt\b"""), ours.filterKeys { !it.startsWith("mt/") }))
        // No network, no file written, no other engine, no app or assistant code.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bSocket\b|okhttp|DownloadManager|\bWebView\b|ConnectivityManager|\bURL\("""), mt))
        assertEquals(emptyList<String>(), usersOf(Regex("""FileOutputStream|RandomAccessFile|Files\.(write|copy|newOutputStream)|writeText|writeBytes|openFileOutput|writeAtomic"""), mt))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.(voice|assistant|ui|sys|brain|claude|people|boot)\b|\bcom\.k2fsa\b|litert|\bcom\.anthropic\b"""), mt))
        // :mt's one dependency is :core (no library, no other module).
        assertEquals(setOf("core"), projectDeps("mt"))
        assertEquals(1, gradle("mt").count(Regex("""(?<![\w.])(?:implementation|api)\b""")))
        assertTrue(!gradleRaw("mt").contains("externalNativeBuild") && !gradleRaw("mt").contains("ndkVersion"))
        assertTrue("mt" in projectDeps("app"))
        for (m in listOf("core", "brain", "voice", "helpers")) assertTrue(m, "mt" !in projectRefs(m))
        // The library is never packaged by another module, and never in git (.gitignore).
        assertTrue(!File(main("voice"), "jniLibs/arm64-v8a/librobotmt.so").exists())
        assertTrue(File(root.parentFile, ".gitignore").readLines().any { it.trim() == "robot/mt/src/main/jniLibs/" })
        // Clients bind it by name, and only through MtLink: the main app's speed test, and the voice process's sessions
        // (phase 5.4) through FastTranslation alone, whose BergamotTranslator core's FastEngineGate makes.
        assertEquals("app.heyrobot.mt.MtService", app.heyrobot.core.MtProtocol.SERVICE_CLASS)
        assertEquals(listOf("app/sys/MtLink.kt"), usersOf(Regex("""\bMtProtocol\.SERVICE_CLASS\b""")))
        assertEquals(listOf("app/assistant/conversation/FastTranslation.kt", "app/sys/MtLink.kt", "app/sys/MtSpeedRun.kt", "app/ui/ConversationSettings.kt"),
            usersOf(Regex("""\bMtLink\b""")))
        // 0.7: a message's translation borrows it (FastTranslation.borrow), as TranslatePolicy orders the engines.
        assertEquals(listOf("app/assistant/PanelMessages.kt", "app/assistant/conversation/ConversationSession.kt", "app/assistant/conversation/ConversationTranslations.kt",
            "app/assistant/conversation/FastTranslation.kt"), usersOf(Regex("""\bFastTranslation\b""")))
        assertEquals(listOf("core/core/FastTranslate.kt"), usersOf(Regex("""(?<!class )\bBergamotTranslator\(""")))
        // The service checks who sent each message, as BrainService does.
        assertHas(flatCode(kt("mt", "mt/MtService.kt")), "msg.sendingUid != Process.myUid()")
        // Robot's own native code in the library (the wrapper and its JNI; robotmt_check.cpp is the build machine's check,
        // never in the library) prints, logs and writes nothing and opens no socket, so no text reaches a log from it.
        val cpp = File(main("mt"), "cpp")
        assertEquals(setOf("CMakeLists.txt", "robotmt.cpp", "robotmt.h", "robotmt_check.cpp", "robotmt_jni.cpp"), cpp.list()!!.toSet())
        val native = listOf("robotmt.cpp", "robotmt.h", "robotmt_jni.cpp").associate { "mt/cpp/$it" to File(cpp, it) }
        assertEquals(emptyList<String>(), usersOf(Regex("""__android_log|\b(f|s|sn|v)?printf\s*\(|\bputs\s*\(|\bstd::(cout|cerr|clog)\b|\bofstream\b|""" +
            """\bfopen\s*\(|\bsocket\s*\(|\bconnect\s*\(|#include\s*<(iostream|fstream|cstdio|stdio\.h|sys/socket\.h|android/log\.h)>"""), native))
        assertTrue(Regex("""add_library\(\s*robotmt\s+SHARED\s+robotmt_jni\.cpp\s*\)""").containsMatchIn(File(cpp, "CMakeLists.txt").readText()))
    }

    /** Ask Claude: the Anthropic SDK lives only in the :helpers module, which only the assistant (the :voice process) uses. */
    @Test fun anthropicSdkOnlyInTheHelpersModule() {
        val users = usersOf(Regex("""\bcom\.anthropic\b"""))
        assertTrue(users.toString(), users.isNotEmpty() && users.all { it.startsWith("helpers/") })
        val anthropicUsers = usersOf(Regex("""\bapp\.heyrobot\.helpers\.anthropic\b"""), ours.filterKeys { !it.startsWith("helpers/") })
        assertTrue(anthropicUsers.toString(), anthropicUsers.isNotEmpty() && anthropicUsers.all { it.startsWith("app/assistant/") })
        // 0.6.0: the other helpers' clients (plain OkHttp, no other SDK) live in the same module, used only by the assistant.
        val helperUsers = usersOf(Regex("""\bapp\.heyrobot\.helpers\b"""), ours.filterKeys { !it.startsWith("helpers/") })
        assertTrue(helperUsers.toString(), helperUsers.isNotEmpty() && helperUsers.all { it.startsWith("app/assistant/") })
        assertEquals(emptyList<String>(), usersOf(Regex("""\bokhttp3\b"""), ours.filterKeys { !it.startsWith("helpers/") }))
        fun deps(m: String) = gradle(m).text
        assertTrue(deps("helpers").contains("com.squareup.okhttp3:okhttp:4.12.0"))
        assertTrue(deps("helpers").contains("com.anthropic:anthropic-java:"))
        for (m in listOf("core", "brain", "voice", "app")) assertTrue(m, !gradleRaw(m).contains("com.anthropic"))
        for (m in listOf("core", "brain", "voice")) assertTrue(m, "helpers" !in projectRefs(m))
        assertTrue("helpers" in projectDeps("app"))
        assertTrue(!gradleRaw("helpers").contains("litert") && "voice" !in projectRefs("helpers") && "brain" !in projectRefs("helpers"))
        // A helper's API key is decrypted in the :voice process only, and handed only to its client: for questions
        // (HelperLink) and for Test connection (the connection window).
        // (1.0: and TermuxSecrets, in :voice too, compares a Termux command with it, so one holding it is refused.)
        // (1.1: and BackupSecrets, in :voice, writes it straight into a backup's encryption when Keys and private codes is on.)
        assertEquals(listOf("app/assistant/HelperConnectionActivity.kt", "app/assistant/HelperLink.kt", "app/assistant/TermuxSecrets.kt",
            "app/assistant/backup/BackupSecrets.kt"), usersOf(Regex("""\bHelperKeys\.read\(""")))
        assertTrue(usersOf(Regex("""\bHelperKeys\b""")).all { it.startsWith("app/assistant/") })
        // Every helper's key is a KeystoreSecret (sys/HelperFiles.kt, shared with the main app for Settings), but only
        // HelperKeys reaches one to read or save it; HelperFiles.kt itself never reads one.
        assertEquals(listOf("app/assistant/HelperKeys.kt"), usersOf(Regex("""\bHelperKeyFiles\.store\b""")))
        val helperFiles = code(kt("app", "sys/HelperFiles.kt"))
        assertTrue(!Regex("""\.(read|save)\s*\(\s*ctx\b""").containsMatchIn(helperFiles) && !helperFiles.contains("clearClipboard"))
        // The SDK never logs (its requests carry the key), and never retries on its own.
        assertHas(flatCode(kt("helpers", "helpers/anthropic/AnthropicClient.kt")), ".logLevel(LogLevel.OFF)", ".maxRetries(0)")
    }

    @Test fun modulesDependOnlyWhereAllowed() {
        assertTrue(!Regex("""\bproject\s*\(""").containsMatchIn(gradleRaw("core")))
        assertTrue(!gradleRaw("core").contains("litert") && !gradleRaw("voice").contains("litert") && !gradleRaw("app").contains("litert"))
        assertTrue(gradle("brain").text.contains("litertlm-android:0.18.0"))
        assertTrue("voice" !in projectRefs("brain") && "brain" !in projectRefs("voice"))
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
        val forbidden = Regex("""\bapp\.heyrobot\.(assistant|voice|brain|claude|mt)\b|\bcom\.k2fsa\b|litertlm|\bcom\.anthropic\b|\b(GemmaEngine|BrainService|WakeWord|Listener|VoiceHub|PanelController|BrainKeeper|BrainClient|VoiceEngines|RobotSession|RobotVoiceService|HelperLink|HelperKeys|AnthropicSession|AnthropicClient|HelperSession|OpenAiSession|HelperHttp|HelperProbe|HelperConnectionActivity|MtService|BergamotEngine|Slimt)\b|\bapp\.heyrobot\.helpers\b|\bokhttp3\b""")
        assertEquals(emptyList<String>(), usersOf(forbidden, mainFiles))
        // And the :voice code must not load LiteRT or the fast translation engine either (it binds :brain and :mt by class name).
        assertEquals(emptyList<String>(), usersOf(Regex("""litertlm|\bGemmaEngine\b|\bBrainService\b|\bapp\.heyrobot\.mt\b|\b(MtService|BergamotEngine|Slimt)\b"""),
            ours.filterKeys { it.startsWith("app/assistant/") || it.startsWith("voice/") }))
    }

    /**
     * 1.1, other apps' sound while you ask: Robot asks Android for audio focus in one file of the :voice process only
     * (TurnFocus, one requester, so the voice's request nests in the question's and letting go of it never hands the focus
     * back mid-turn). A question takes it before its first frame is read and after the microphone checks, Robot's two
     * voices go through it, and the panel ends the turn as it closes, follows its phase, and checks again when a headset
     * session ends.
     */
    /**
     * 1.1 (phone, 22 Oct): Escape cancelled the panel's Dialog and left the session shown, so the panel never came back.
     * The session's window routes Escape to PanelEscape (Back's steps), and a cancel of any other kind hides the session.
     */
    @Test fun escapeAndCancelHideThePanelsSession() {
        val session = flatCode(kt("app", "assistant/RobotSession.kt"))
        assertHas(session.body("override fun onCreateContentView("), "PanelEscape.onKey(keyCode, event, ::keyboardShown, ::hideKeyboard, ::onBackPressed)")
        assertHas(session.body("override fun onCreateContentView("), "window?.setOnCancelListener { hide() }")
    }

    @Test fun audioFocusHasOneRequester() {
        assertEquals(listOf("voice/voice/TurnFocus.kt"), usersOf(Regex("""\b(requestAudioFocus|abandonAudioFocusRequest|abandonAudioFocus|AudioFocusRequest|OnAudioFocusChangeListener)\b""")))
        assertEquals(listOf("voice/voice/SherpaSpeechToText.kt"), usersOf(Regex("""\bTurnFocus\.listen\(""")))
        assertInOrder(flatCode(kt("voice", "voice/SherpaSpeechToText.kt")).body("override fun start("),
            "if (src.silenced == true)", "TurnFocus.listen(ctx)", "src.attach(")
        assertEquals(listOf("voice/voice/SherpaTextToSpeech.kt", "voice/voice/TextToSpeech.kt"), usersOf(Regex("""\bTurnFocus\.speaking\(""")))
        assertEquals(listOf("app/assistant/PanelMediaPause.kt"), usersOf(Regex("""\bTurnFocus\.(keep|end)\(""")))
        val panel = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(panel.body("private fun init("), "mediaPause.start(scope)")
        assertHas(panel.body("fun onHide("), "mediaPause.hidden()")
        assertHas(flatCode(kt("app", "assistant/handsfree/PanelHandsFree.kt")).body("fun sessionEnded("), "PanelController.mediaPause.check()")
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
            // 1.0 (ipc-1): Robot's app opens the panel for Run now, Ready and Continue through this trampoline (not exported).
            "app.heyrobot.assistant.PanelLaunchActivity",
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
            "app.heyrobot.assistant.routines.RoutineAlarmReceiver", "app.heyrobot.assistant.routines.RoutineSpeakingService",
            // 0.8, headset and car mode: the headset button's trampoline and the car screen, where the sessions are.
            "app.heyrobot.assistant.headset.HeadsetEntryActivity", "app.heyrobot.assistant.car.CarModeActivity",
            // 0.9: web search's set-up window, where a search key is pasted and Test runs; Termux's results come back where its
            // commands were sent from.
            "app.heyrobot.assistant.WebSearchActivity", "app.heyrobot.assistant.TermuxResultReceiver",
            // 1.1: the backup window, where keys, the voiceprint and the passphrase are handled (backupsStayInTheirWindow).
            "app.heyrobot.assistant.backup.BackupActivity",
            // 1.1: a skill's "Continue" tap, where its one-time token is kept (skillsStayOnThePhone).
            "app.heyrobot.assistant.skills.SkillContinueReceiver",
            // 1.2: the setup guide's GPU self-test, run by BrainKeeper, the only client of :brain (theSelfTestStaysOnThePhone).
            "app.heyrobot.assistant.GpuCheckService")
        for (n in voice) assertEquals(n, ":voice", proc(n))
        assertEquals("false", exported("app.heyrobot.assistant.PanelLaunchActivity"))
        assertEquals(0, c("app.heyrobot.assistant.PanelLaunchActivity").getElementsByTagName("intent-filter").length)
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
        // 1.4, Robot Files (files-1.4-plan.md §4.3, §5.5): the isolated reader, :files, set up as :llama is: an isolated uid of
        // its own, not exported, no intent filter, never guarded by (or holding) a permission, never sharing its process with
        // another isolated service nor started from the app zygote (filesAreReadOnlyInTheirProcess checks its code).
        val files = c("app.heyrobot.parse.FileParseService")
        assertEquals(":files", proc("app.heyrobot.parse.FileParseService"))
        assertEquals("true", files.getAttributeNS(ns, "isolatedProcess"))
        assertEquals("false", exported("app.heyrobot.parse.FileParseService"))
        assertEquals("", perm("app.heyrobot.parse.FileParseService"))
        for (a in listOf("allowSharedIsolatedProcess", "useAppZygote", "externalService", "foregroundServiceType")) assertEquals(a, "", files.getAttributeNS(ns, a))
        assertEquals(0, files.getElementsByTagName("intent-filter").length)
        // No other component is isolated (1.4: the two isolated services, deliberately), nothing else runs in :llama, and
        // nothing else in :files.
        assertEquals(listOf("app.heyrobot.brain.LlamaService", "app.heyrobot.parse.FileParseService"),
            comps.filter { it.value.getAttributeNS(ns, "isolatedProcess") == "true" }.keys.sorted())
        assertEquals(listOf("app.heyrobot.brain.LlamaService"), comps.filter { it.value.getAttributeNS(ns, "process") == ":llama" }.keys.toList())
        assertEquals(listOf("app.heyrobot.parse.FileParseService"), comps.filter { it.value.getAttributeNS(ns, "process") == ":files" }.keys.toList())
        // 0.5.0: the fast translation engine in a process of its own, bound only by Robot's own processes.
        assertEquals(":mt", proc("app.heyrobot.mt.MtService"))
        assertEquals("false", exported("app.heyrobot.mt.MtService"))
        assertEquals("ai.robot_app.permission.INTERNAL", perm("app.heyrobot.mt.MtService"))
        val main = listOf("app.heyrobot.ui.MainActivity", "app.heyrobot.boot.BootReceiver", "app.heyrobot.boot.BootCheckReceiver",
            "app.heyrobot.people.CheogramListener", "app.heyrobot.sys.ExportProvider", "app.heyrobot.boot.UpdatedReceiver", "app.heyrobot.update.UpdateJobService",
            "app.heyrobot.update.InstallStatusReceiver",
            // 0.7: the panel's way to your messages, beside the listener that holds them.
            "app.heyrobot.people.MessagesBridge",
            // 1.4, Robot Files: the share target and scanning's camera hand-off (theShareTargetOnlyCopies); F7: the copies
            // handed to another app (filesHandOutACopyAndTakeItBack).
            "app.heyrobot.ui.files.FilesShareActivity", "app.heyrobot.sys.ScanProvider", "app.heyrobot.sys.FilesOutProvider")
        for (n in main) assertEquals(n, "", proc(n))
        // 1.1, Phone lock: its own small process, :lock (docs/plans/phone-lock-design.md §4, §13), and nothing else runs there.
        val lock = listOf("app.heyrobot.lock.LockAccessibilityService", "app.heyrobot.lock.LockCoverActivity", "app.heyrobot.lock.LockCodeActivity",
            "app.heyrobot.lock.LockAlarmReceiver")
        for (n in lock) assertEquals(n, ":lock", proc(n))
        assertEquals(lock.toSet(), comps.filter { it.value.getAttributeNS(ns, "process") == ":lock" }.keys)
        // Every Robot component is accounted for (a new one must be placed deliberately).
        val ourComps = comps.keys.filter { it.startsWith("app.heyrobot.") }.toSet()
        assertEquals(main.toSet() + "app.heyrobot.brain.BrainService" + "app.heyrobot.brain.LlamaService" + "app.heyrobot.mt.MtService" +
            voice.filter { it.startsWith("app.heyrobot.") } + lock + "app.heyrobot.parse.FileParseService", ourComps)
        // Exported components are guarded: the system-only BIND_VOICE_INTERACTION, or nothing to guard.
        assertEquals("android.permission.BIND_VOICE_INTERACTION", perm("app.heyrobot.assistant.RobotVoiceService"))
        assertEquals("android.permission.BIND_VOICE_INTERACTION", perm("app.heyrobot.assistant.RobotSessionService"))
        // 0.4.0: the notification listener only the system may bind, named as the main app names it.
        assertEquals("true", exported("app.heyrobot.people.CheogramListener"))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", perm("app.heyrobot.people.CheogramListener"))
        assertHas(flatText(kt("app", "sys/People.kt")), "\"app.heyrobot.people.CheogramListener\"")
        assertEquals("false", exported("app.heyrobot.brain.BrainService"))
        assertEquals("ai.robot_app.permission.INTERNAL", perm("app.heyrobot.brain.BrainService"))
        for (n in listOf("app.heyrobot.assistant.CaptureActivity", "androidx.core.content.FileProvider", "app.heyrobot.boot.BootReceiver", "app.heyrobot.boot.BootCheckReceiver",
            "app.heyrobot.assistant.HelperConnectionActivity", "app.heyrobot.assistant.MediaDeleteActivity",
            "app.heyrobot.assistant.CheogramPickActivity", "app.heyrobot.assistant.conversation.ConversationService",
            "app.heyrobot.assistant.conversation.ConversationActivity", "app.heyrobot.assistant.conversation.TranscriptionCheckActivity",
            "app.heyrobot.assistant.conversation.VoiceprintActivity", "app.heyrobot.assistant.conversation.SummaryActivity", "app.heyrobot.sys.ExportProvider",
            "app.heyrobot.assistant.routines.RoutineAlarmReceiver", "app.heyrobot.assistant.routines.RoutineSpeakingService",
            "app.heyrobot.boot.UpdatedReceiver", "app.heyrobot.update.UpdateJobService", "app.heyrobot.update.InstallStatusReceiver",
            "app.heyrobot.assistant.car.CarModeActivity", "app.heyrobot.assistant.WebSearchActivity", "app.heyrobot.assistant.TermuxResultReceiver",
            "app.heyrobot.assistant.backup.BackupActivity", "app.heyrobot.assistant.skills.SkillContinueReceiver",
            "app.heyrobot.assistant.GpuCheckService", "app.heyrobot.sys.ScanProvider", "app.heyrobot.sys.FilesOutProvider"))
            assertEquals(n, "false", exported(n))
        // 1.4, Robot Files (files-1.4-plan.md §3.1, D3; F5, deliberately): the share target, Robot's first exported component
        // that takes content from any app. Main process; exported only because the share sheet must reach it; one filter of
        // exactly SEND and SEND_MULTIPLE, the DEFAULT category and any type, no scheme or host; no permission; never over the
        // lock screen; out of Recents. What it does with a share is theShareTargetOnlyCopies'.
        val share = c("app.heyrobot.ui.files.FilesShareActivity")
        assertEquals("true", exported("app.heyrobot.ui.files.FilesShareActivity"))
        assertEquals("", perm("app.heyrobot.ui.files.FilesShareActivity"))
        assertEquals(1, share.getElementsByTagName("intent-filter").length)
        assertEquals(listOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"), elements(share, "action").map { it.getAttributeNS(ns, "name") })
        assertEquals(listOf("android.intent.category.DEFAULT"), elements(share, "category").map { it.getAttributeNS(ns, "name") })
        assertEquals(listOf(mapOf("mimeType" to "*/*")), elements(share, "data").map { d ->
            (0 until d.attributes.length).map { d.attributes.item(it) }.filter { it.namespaceURI == ns }.associate { it.localName to it.nodeValue } })
        for (a in listOf("showWhenLocked", "turnScreenOn", "directBootAware", "permission", "process", "documentLaunchMode", "allowEmbedded"))
            assertEquals(a, "", share.getAttributeNS(ns, a))
        assertEquals("true", share.getAttributeNS(ns, "excludeFromRecents"))
        // Its camera hand-off for scanning: the main app's own FileProvider for cache/scan alone, not exported.
        assertEquals("ai.robot_app.scan", c("app.heyrobot.sys.ScanProvider").getAttributeNS(ns, "authorities"))
        assertEquals("ai.robot_app.scan", app.heyrobot.sys.ScanFiles.AUTHORITY)
        val scanPaths = xml(File(main("app"), "res/xml/scan_paths.xml"))
        assertEquals(listOf("cache-path" to ("scan" to "scan/")), (0 until scanPaths.childNodes.length).map { scanPaths.childNodes.item(it) }
            .filterIsInstance<Element>().map { it.tagName to (it.getAttribute("name") to it.getAttribute("path")) })
        // 1.4 F7: Share and Open with another app: the main app's own FileProvider for cache/files_out alone, not exported,
        // grants only (each one-time, read only: filesHandOutACopyAndTakeItBack); never Robot Files' sealed files.
        val out = c("app.heyrobot.sys.FilesOutProvider")
        assertEquals("ai.robot_app.files_out", out.getAttributeNS(ns, "authorities"))
        assertEquals("ai.robot_app.files_out", app.heyrobot.sys.FilesOut.AUTHORITY)
        assertEquals("true", out.getAttributeNS(ns, "grantUriPermissions"))
        assertEquals("", out.getAttributeNS(ns, "permission"))
        assertEquals("", out.getAttributeNS(ns, "process"))
        val outPaths = xml(File(main("app"), "res/xml/files_out_paths.xml"))
        assertEquals(listOf("cache-path" to ("out" to "files_out/")), (0 until outPaths.childNodes.length).map { outPaths.childNodes.item(it) }
            .filterIsInstance<Element>().map { it.tagName to (it.getAttribute("name") to it.getAttribute("path")) })
        // 1.2: the guide's self-test service: bound by class name, by Robot alone (its signature permission), no filter.
        assertEquals("ai.robot_app.permission.INTERNAL", perm("app.heyrobot.assistant.GpuCheckService"))
        assertEquals(0, c("app.heyrobot.assistant.GpuCheckService").getElementsByTagName("intent-filter").length)
        assertEquals("", c("app.heyrobot.assistant.GpuCheckService").getAttributeNS(ns, "foregroundServiceType"))
        // 1.1: the continuation's receiver hears only Robot's own explicit PendingIntent: no filter.
        assertEquals(0, c("app.heyrobot.assistant.skills.SkillContinueReceiver").getElementsByTagName("intent-filter").length)
        // 0.8: the headset button's trampoline is exported (Android starts it for a headset's button) with exactly the three
        // actions core HeadsetEntry lists and no other filter, no history, not in Recents, shown over the lock screen only to
        // pass the press on; no permission guards it (Bluetooth and the system start it).
        val entry = c("app.heyrobot.assistant.headset.HeadsetEntryActivity")
        assertEquals("true", exported("app.heyrobot.assistant.headset.HeadsetEntryActivity"))
        assertEquals("", perm("app.heyrobot.assistant.headset.HeadsetEntryActivity"))
        val entryActions = entry.getElementsByTagName("action").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }
        assertEquals(app.heyrobot.core.headset.HeadsetEntry.ACTIONS, entryActions)
        assertEquals(3, entry.getElementsByTagName("intent-filter").length)
        assertTrue(entry.getElementsByTagName("data").length == 0)
        for (a in listOf("noHistory", "excludeFromRecents", "showWhenLocked")) assertEquals(a, "true", entry.getAttributeNS(ns, a))
        // 1.0.1: its caller is checked as Android recorded it (core EntryCaller): a start naming its own referrer is never
        // believed, and an untrusted one is passed to HeadsetEntry.decide, which then does nothing.
        val hub = flatCode(kt("app", "assistant/handsfree/HandsFreeHub.kt")).body("fun onButton(")
        assertHas(hub, "Intent.EXTRA_REFERRER)", "Intent.EXTRA_REFERRER_NAME)", "if (claimed) null else", "a.launchedFromUid == android.os.Process.myUid()",
            "EntryCaller.trusted(from, claimed, ctx.packageName, fromOwnUid)", "CarModeController.on, mayAck, trusted)", "EntryDecision.NotTrusted -> return")
        assertEquals(0, c("app.heyrobot.assistant.car.CarModeActivity").getElementsByTagName("intent-filter").length)
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
        // The export provider shares the export folder only. 1.0: both providers' authorities are the application ID's (they
        // must be unique on the phone, with 0.9 installed beside it), the same as the code's.
        assertEquals("ai.robot_app.exports", c("app.heyrobot.sys.ExportProvider").getAttributeNS(ns, "authorities"))
        assertEquals("ai.robot_app.capture", c("androidx.core.content.FileProvider").getAttributeNS(ns, "authorities"))
        assertEquals("ai.robot_app.exports", app.heyrobot.sys.Exports.AUTHORITY)
        assertEquals("ai.robot_app.capture", app.heyrobot.assistant.CaptureActivity.AUTHORITY)
        val paths = xml(File(main("app"), "res/xml/export_paths.xml"))
        assertEquals("paths", paths.tagName)
        val shared = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("cache-path" to ("exports" to "exports/")), shared.map { it.tagName to (it.getAttribute("name") to it.getAttribute("path")) })
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
        // 1.1, Phone lock: the accessibility service is exported only because the system binds it, and only the system can
        // (BIND_ACCESSIBILITY_SERVICE); one filter, its action, and its XML (window-state events, no window content, not an
        // accessibility tool, no gestures, no package filter). The cover and the code pad aren't exported and never show on
        // the lock screen; the cover is its own task, out of Recents. The receiver hears only the system's broadcasts that make
        // the exact alarm wrong, as routines' does, after the first unlock, and Robot's own explicit intents.
        val a11y = c("app.heyrobot.lock.LockAccessibilityService")
        assertEquals("true", exported("app.heyrobot.lock.LockAccessibilityService"))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", perm("app.heyrobot.lock.LockAccessibilityService"))
        assertEquals(1, a11y.getElementsByTagName("intent-filter").length)
        assertEquals(setOf("android.accessibilityservice.AccessibilityService"), actions("app.heyrobot.lock.LockAccessibilityService"))
        val a11yMeta = a11y.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.accessibilityservice", a11yMeta.getAttributeNS(ns, "name"))
        assertEquals("@xml/lock_accessibility", a11yMeta.getAttributeNS(ns, "resource"))
        assertEquals("", a11y.getAttributeNS(ns, "foregroundServiceType"))
        val info = xml(File(main("app"), "res/xml/lock_accessibility.xml"))
        assertEquals("accessibility-service", info.tagName)
        assertEquals(mapOf("accessibilityEventTypes" to "typeWindowStateChanged", "accessibilityFeedbackType" to "feedbackGeneric", "notificationTimeout" to "0",
            "canRetrieveWindowContent" to "false", "isAccessibilityTool" to "false", "description" to "@string/lock_a11y_description"),
            (0 until info.attributes.length).map { info.attributes.item(it) }.filter { it.namespaceURI == ns }.associate { it.localName to it.nodeValue })
        // Only the one service in the whole APK takes accessibility events.
        assertEquals(listOf("app.heyrobot.lock.LockAccessibilityService"), comps.filter { (_, e) ->
            e.getElementsByTagName("action").let { nl -> (0 until nl.length).any { (nl.item(it) as Element).getAttributeNS(ns, "name") == "android.accessibilityservice.AccessibilityService" } }
        }.keys.toList())
        for (n in listOf("app.heyrobot.lock.LockCoverActivity", "app.heyrobot.lock.LockCodeActivity", "app.heyrobot.lock.LockAlarmReceiver")) {
            assertEquals(n, "false", exported(n))
            assertEquals(n, "", c(n).getAttributeNS(ns, "showWhenLocked"))
            assertEquals(n, "", c(n).getAttributeNS(ns, "directBootAware"))
        }
        val cover = c("app.heyrobot.lock.LockCoverActivity")
        assertEquals("true", cover.getAttributeNS(ns, "excludeFromRecents"))
        assertEquals("ai.robot_app.lock", cover.getAttributeNS(ns, "taskAffinity"))
        assertEquals("singleTask", cover.getAttributeNS(ns, "launchMode"))
        assertEquals("true", c("app.heyrobot.lock.LockCodeActivity").getAttributeNS(ns, "excludeFromRecents"))
        for (n in listOf("app.heyrobot.lock.LockCoverActivity", "app.heyrobot.lock.LockCodeActivity")) assertEquals(n, 0, c(n).getElementsByTagName("intent-filter").length)
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.MY_PACKAGE_REPLACED"), actions("app.heyrobot.lock.LockAlarmReceiver"))
        // Every Robot component class exists in the sources.
        for (n in ourComps) assertTrue(n, ours.values.any { Regex("""class ${n.substringAfterLast('.')}\b""").containsMatchIn(code(it)) })
    }

    /**
     * 1.1 (phone, 22 Oct: `update_job_schedule error=SecurityException` at every start): Android 14 and later refuse a job with
     * a network constraint (setRequiredNetworkType or setRequiredNetwork) from an app targeting 34 or later that doesn't declare
     * ACCESS_NETWORK_STATE. Every job that sets one is in UpdateJobs, and the merged manifest declares the permission.
     */
    @Test fun aJobWithANetworkConstraintHasItsPermission() {
        val net = Regex("""\.setRequiredNetwork(?:Type)?\s*\(""")
        assertEquals(listOf("app/update/UpdateJobs.kt"), usersOf(net))
        val doc = parse(mergedManifest())
        val used = doc.getElementsByTagName("uses-permission").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }
        assertTrue("a job with a network constraint needs ACCESS_NETWORK_STATE (Android 14+)", "android.permission.ACCESS_NETWORK_STATE" in used)
        // No WorkManager (whose network constraints need the same) and no other scheduler.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bandroidx\.work\b|\bWorkManager\b""")))
    }

    @Test fun mergedManifestIdentityAndPermissions() {
        val doc = parse(mergedManifest())
        val m = doc.documentElement
        // 1.0: the application ID is ai.robot_app (0.9 and earlier: app.heyrobot), and core's copy, which the adb commands
        // and the updater use, is the same; the namespace and the classes stay app.heyrobot.
        assertEquals("ai.robot_app", m.getAttribute("package"))
        assertEquals("ai.robot_app", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, app.heyrobot.core.AppId.PACKAGE)
        assertTrue(gradle("app").matches(Regex("""(?<![\w.])namespace\s*=?\s*['"]app\.heyrobot['"]""")))
        // The recognizer the ADB fixes set is the manifest's, package and class (written in full: the classes aren't in
        // the package, so ".assistant.…" would name nothing).
        val comps = components(doc)
        assertTrue(comps.containsKey(app.heyrobot.core.AdbFixes.RECOGNIZER.substringAfter('/')))
        assertEquals(m.getAttribute("package"), app.heyrobot.core.AdbFixes.RECOGNIZER.substringBefore('/'))
        assertEquals("pm grant ai.robot_app android.permission.WRITE_SECURE_SETTINGS",
            app.heyrobot.core.AdbFixes.ALL.flatMap { it.commands }.single { "pm grant" in it }.removePrefix("adb shell "))
        // Every explicit task affinity is the application ID's, never 0.9's, so 1.0's windows never join 0.9's tasks.
        val affinities = comps.values.map { it.getAttributeNS(ns, "taskAffinity") }.filter { it.isNotEmpty() }
        // 1.1: the cover's own task (ai.robot_app.lock) makes 10.
        assertEquals(10, affinities.size)
        for (a in affinities) assertTrue(a, a.startsWith("ai.robot_app."))
        // The launcher shortcuts target this package (a resource can't take the placeholder) and classes that exist.
        val shortcuts = parse(File(main("app"), "res/xml/shortcuts.xml")).getElementsByTagName("intent")
        assertEquals(2, shortcuts.length)
        for (i in 0 until shortcuts.length) (shortcuts.item(i) as Element).let { e ->
            assertEquals(BuildConfig.APPLICATION_ID, e.getAttributeNS(ns, "targetPackage"))
            assertTrue(comps.containsKey(e.getAttributeNS(ns, "targetClass")))
        }
        // The version is app/build.gradle's alone (tools/bump_version.sh sets it), as written there.
        val gradle = gradle("app").text
        val versionCode = Regex("""(?<![\w.])versionCode\s*=?\s*(\d+)""").findAll(gradle).map { it.groupValues[1] }.toList()
        val versionName = Regex("""(?<![\w.])versionName\s*=?\s*['"]([^'"]+)['"]""").findAll(gradle).map { it.groupValues[1] }.toList()
        assertEquals(listOf(m.getAttributeNS(ns, "versionCode")), versionCode)
        assertEquals(listOf(m.getAttributeNS(ns, "versionName")), versionName)
        assertEquals(BuildConfig.VERSION_NAME, m.getAttributeNS(ns, "versionName"))
        val sdk = doc.getElementsByTagName("uses-sdk").item(0) as Element
        assertEquals("34", sdk.getAttributeNS(ns, "minSdkVersion"))
        assertEquals("36", sdk.getAttributeNS(ns, "targetSdkVersion"))
        val declared = doc.getElementsByTagName("permission").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        val internal = declared.single { it.getAttributeNS(ns, "name") == "ai.robot_app.permission.INTERNAL" }
        assertEquals("signature", internal.getAttributeNS(ns, "protectionLevel"))
        val used = doc.getElementsByTagName("uses-permission").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertEquals(setOf(
            "ai.robot_app.permission.INTERNAL", "ai.robot_app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS",
            // 1.1, deliberately: Android 14+ refuses a job with a network constraint without it (aJobWithANetworkConstraintHasItsPermission).
            "android.permission.ACCESS_NETWORK_STATE",
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
            // (0.7, Send feedback, needs none: the share sheet, the clipboard, your SMS app and your browser take what you send.)
            // 0.8, headset and car mode, each asked for only from its own settings row: Nearby devices (the headset told Robot
            // listens, devices known exactly), the Phone permission ("Call directly after a spoken yes"), and Do Not Disturb
            // access (Robot's own "Driving (Robot)" rule; a normal permission plus the access you grant in Android's settings).
            "android.permission.BLUETOOTH_CONNECT", "android.permission.CALL_PHONE", "android.permission.ACCESS_NOTIFICATION_POLICY",
            // 0.9: Termux's own dangerous permission, asked for from Settings › Termux by the main app only.
            "com.termux.permission.RUN_COMMAND",
            // 1.4 FX1, deliberately: travel mode's confirms (sys/DevicePin) go through BiometricPrompt with the device
            // credential (PIN, pattern or password) as the only authenticator; Android checks USE_BIOMETRIC for every prompt.
            "android.permission.USE_BIOMETRIC",
        ), used)
        // FX1: never the older fingerprint permission, nor a biometric sensor of Robot's own.
        assertTrue(listOf("USE_FINGERPRINT", "MANAGE_BIOMETRIC").none { "android.permission.$it" in used })
        // 0.8: routines need nothing else: USE_EXACT_ALARM (above) rather than SCHEDULE_EXACT_ALARM; car mode reads no phone
        // state, never scans for Bluetooth devices and never sends a text itself (no SEND_SMS, READ_PHONE_STATE, BLUETOOTH_SCAN).
        assertTrue(listOf("SCHEDULE_EXACT_ALARM", "FOREGROUND_SERVICE_SHORT_SERVICE", "READ_PHONE_STATE", "BLUETOOTH_SCAN", "BLUETOOTH",
            "BLUETOOTH_ADMIN", "BLUETOOTH_PRIVILEGED", "MANAGE_OWN_CALLS", "ANSWER_PHONE_CALLS", "READ_CALL_LOG").none { "android.permission.$it" in used })
        // The three are asked only from their rows (Settings › Headset and Car mode, the main app's permission action), never
        // at start: no other code asks for them. 1.1: the Phone permission also from a phone lock's People row (Settings ›
        // Phone lock › a lock), through the same action: during a lock, a call to those People is placed from its card.
        assertEquals(listOf("app/ui/settings/HandsFreeSettings.kt", "app/ui/settings/PhoneLockEditor.kt"),
            usersOf(Regex("""Manifest\.permission\.(CALL_PHONE|BLUETOOTH_CONNECT)\b"""), ours.filterKeys { it.startsWith("app/ui/") }))
        assertFalse(flatCode(kt("app", "ui/settings/PhoneLockEditor.kt")).text.contains("BLUETOOTH_CONNECT"))
        // Never installing or deleting other apps, never asking to update apps it didn't install, and no user-initiated job or
        // foreground service type for the updater's downloads (FOREGROUND_SERVICE itself is conversation mode's, above).
        assertTrue(listOf("INSTALL_PACKAGES", "DELETE_PACKAGES", "REQUEST_DELETE_PACKAGES", "INSTALL_PACKAGE_UPDATES", "INSTALL_SELF_UPDATES",
            "ENFORCE_UPDATE_OWNERSHIP", "RUN_USER_INITIATED_JOBS", "FOREGROUND_SERVICE_DATA_SYNC").none { "android.permission.$it" in used })
        // Never all files, never stopping other apps (Android 14 and later wouldn't let it anyway).
        assertTrue(listOf("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "KILL_BACKGROUND_PROCESSES").none { "android.permission.$it" in used })
        assertTrue("android.permission.ACCESS_FINE_LOCATION" !in used && "android.permission.ACCESS_BACKGROUND_LOCATION" !in used)
        // Package visibility through <queries> only.
        assertTrue("android.permission.QUERY_ALL_PACKAGES" !in used && "android.permission.SEND_SMS" !in used && "android.permission.WRITE_CONTACTS" !in used)
        // Ask Claude needs nothing new: INTERNET stays the only permission that goes online. 1.1, deliberately: ACCESS_NETWORK_STATE
        // alone, for UpdateJobs' network-constrained jobs (aJobWithANetworkConstraintHasItsPermission); nothing that changes the
        // network or reads Wi-Fi.
        assertTrue(used.none { (it.contains("NETWORK_STATE") && it != "android.permission.ACCESS_NETWORK_STATE") || it.contains("WIFI") || it.contains("ACCESSIBILITY") })
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        // 1.1: Android's per-app language from AGP's generated locale config (en-US, fr-CA; no hand-written one, no permission),
        // and layouts that may mirror for a right-to-left language.
        assertEquals("@xml/_generated_res_locale_config", app.getAttributeNS(ns, "localeConfig"))
        val resProps = java.util.Properties().apply { File(main("app"), "res/resources.properties").reader().use(::load) }
        assertTrue(!File(main("app"), "res/xml/locales_config.xml").exists() && resProps.getProperty("unqualifiedResLocale") == "en-US")
        assertEquals("true", app.getAttributeNS(ns, "supportsRtl"))
        // 0.6.0: cleartext only where Robot's own rule allows it (a server on your network), never to a company; no user CAs.
        assertEquals("@xml/network_security_config", app.getAttributeNS(ns, "networkSecurityConfig"))
        val nsc = xml(File(main("app"), "res/xml/network_security_config.xml"))
        // Every company's domain, with its subdomains, in a domain-config that refuses cleartext.
        val noCleartext = elements(nsc, "domain-config").filter { it.getAttribute("cleartextTrafficPermitted") == "false" }
            .flatMap { elements(it, "domain") }.filter { it.getAttribute("includeSubdomains") == "true" }.map { it.textContent.trim() }.toSet()
        for (d in listOf("anthropic.com", "openai.com", "googleapis.com", "mistral.ai", "x.ai", "deepseek.com", "groq.com", "openrouter.ai", "huggingface.co",
            "open-meteo.com", "openstreetmap.org", "github.com", "githubusercontent.com", "f-droid.org"))
            assertTrue(d, d in noCleartext)
        // The system's CAs, never the user's.
        val cas = elements(nsc, "certificates").map { it.getAttribute("src") }
        assertTrue(cas.toString(), "system" in cas && "user" !in cas)
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
        assertHas(flatText(kt("app", "sys/People.kt")), "\"app.heyrobot.ui.NotesLauncher\"")
        // 1.4 F7 (files-1.4-plan.md Q1): "Robot Files" on the home screen, an alias of MainActivity in the main process as Robot
        // Notes is, opening the Files tab; the only other alias, and Robot's only other launcher entries.
        val files = aliases.single { it.getAttributeNS(ns, "name") == "app.heyrobot.ui.FilesLauncher" }
        assertEquals("app.heyrobot.ui.MainActivity", files.getAttributeNS(ns, "targetActivity"))
        assertEquals("true", files.getAttributeNS(ns, "exported"))
        assertEquals("", files.getAttributeNS(ns, "process"))
        assertEquals("", files.getAttributeNS(ns, "permission"))
        assertEquals(listOf("android.intent.action.MAIN"), elements(files, "action").map { it.getAttributeNS(ns, "name") })
        assertEquals(listOf("android.intent.category.LAUNCHER"), elements(files, "category").map { it.getAttributeNS(ns, "name") })
        assertEquals(0, files.getElementsByTagName("data").length)
        assertEquals(listOf("app.heyrobot.ui.FilesLauncher", "app.heyrobot.ui.NotesLauncher"), aliases.map { it.getAttributeNS(ns, "name") }.sorted())
        assertHas(flatText(kt("app", "sys/People.kt")), "\"app.heyrobot.ui.FilesLauncher\"")
        assertHas(flatCode(kt("app", "ui/settings/SettingsNav.kt")), "i.component?.className == AppPages.FILES_ALIAS -> AppPages.FILES_ICON",
            "r == AppPages.FILES_ICON -> Target(tab = Tab.FILES, filesIcon = true)")
        val queries = doc.getElementsByTagName("queries").item(0) as Element
        val packages = queries.getElementsByTagName("package").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertTrue(packages.containsAll(setOf("com.cheogram.android", "com.cheogram.android.playstore", "ch.protonmail.android", "com.anthropic.claude", "com.termux")))
        // 1.0.0: every helper's app Robot can open is visible to it (Android 11+ package visibility).
        assertTrue(packages.containsAll(app.heyrobot.core.helpers.Presets.ALL.mapNotNull { it.app }))
        val schemes = queries.getElementsByTagName("data").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "scheme") } }.toSet()
        assertTrue(schemes.containsAll(setOf("xmpp", "mailto", "smsto", "claude", "https")))
        val hosts = queries.getElementsByTagName("data").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "host") } }.toSet()
        assertTrue("claude.ai" in hosts)
        val mimes = queries.getElementsByTagName("data").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "mimeType") } }.toSet()
        assertTrue("vnd.android.cursor.dir/event" in mimes)
        // 0.8: calls (tel: for the dialer and ACTION_CALL) and the headset button's three intents (who answers them).
        val queried = queries.getElementsByTagName("action").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).getAttributeNS(ns, "name") } }.toSet()
        assertTrue(queried.containsAll(listOf("android.intent.action.DIAL", "android.intent.action.CALL") + app.heyrobot.core.headset.HeadsetEntry.ACTIONS))
        assertTrue("tel" in schemes)
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
    /**
     * 1.0 audit (native-03): what the isolated :llama streams back is capped per GENERATE in bytes (core StreamBudget), not
     * only per piece; past it no piece reaches :brain's sink and the request ends; a stopped run decodes nothing more.
     */
    @Test fun llamaRepliesAreCappedInBytes() {
        // (The budget itself: core StreamBudgetTest. LlamaIpc is internal to :brain, so its cap is read from the source.)
        val link = flatCode(kt("brain", "brain/LlamaLink.kt"))
        assertHas(link, "val more = from == uid && bytes != null && b != null && b.take(bytes.size) && s != null && s(bytes)",
            "val b = StreamBudget(LlamaIpc.MAX_PIECE, LlamaIpc.MAX_REPLY_BYTES)")
        assertHas(flatCode(kt("brain", "brain/LlamaIpc.kt")), "const val MAX_REPLY_BYTES = 256L * 1024")
        assertInOrder(flatCode(kt("brain", "brain/LlamaEngine.kt")), "if (run.stop) return@generate false", "val text = String(bytes, Charsets.UTF_8)")
    }

    /**
     * 1.1: :llama may be a hostile file's by the time it answers, so :brain reads its replies as numbers and bytes only
     * (LlamaIpc.Reply): the header a plain int that must be REPLY_OK, never Parcel.readException (which unparcels and
     * throws the exception class the writer names), nothing read past a reply's end, nothing left over, and a reply that
     * breaks that ends the process as a crash does. Nothing :llama sends is unparcelled as an object, a binder or a
     * descriptor, its callback answers only its own calls, and LlamaService writes the plain header itself. (The replies
     * themselves: LlamaEngineFlowTest.)
     */
    @Test fun llamaRepliesAreReadAsNumbersAndBytesOnly() {
        val brain = ours.filterKeys { it.startsWith("brain/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(readException|readExceptionCode|writeNoException)\b"""), brain))
        val unparcels = Regex("""\b(readException|readExceptionCode|readParcelable|readParcelableArray|readParcelableList|readParcelableCreator|""" +
            """readTypedObject|readTypedList|readTypedArray|createTypedArray|createTypedArrayList|readStrongBinder|readBinderArray|readBinderList|""" +
            """createBinderArray|createBinderArrayList|readInterfaceArray|readInterfaceList|readFileDescriptor|readRawFileDescriptor|""" +
            """readSerializable|readValue|readBundle|readPersistableBundle|readList|readArrayList|readMap|readHashMap|readSparseArray|""" +
            """readArray|readBlob|createFromParcel|unmarshall)\b""")
        val linkCode = Scan.flat(code(kt("brain", "brain/LlamaLink.kt")))
        assertEquals(emptyList<String>(), unparcels.findAll(linkCode).map { it.value }.toList())
        assertTrue(!linkCode.contains("super.onTransact"))
        // What LlamaLink reads of a Parcel: the callback's data (its token, a piece), and the reply only through LlamaIpc.Reply.
        val parcelReads = Regex("""\b(\w+)\.((?:read|create)\w*|enforceInterface)\(""")
        assertEquals(setOf("data.enforceInterface", "data.createByteArray"),
            parcelReads.findAll(linkCode).map { "${it.groupValues[1]}.${it.groupValues[2]}" }.filter { !it.startsWith("LlamaIpc.") }.toSet())
        val link = flatCode(kt("brain", "brain/LlamaLink.kt"))
        val call = link.body("private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (LlamaIpc.Reply) -> T): T")
        assertInOrder(call, "if (ending) throw Gone()", "val answered = b.transact(code, d, r, 0)", "if (!answered) throw LlamaIpc.BadReply()",
            "val reply = LlamaIpc.Reply(r)", "read(reply).also { reply.end() }", "catch (_: LlamaIpc.BadReply) { broken(code) }")
        assertInOrder(flatText(kt("brain", "brain/LlamaLink.kt")).body("private fun broken(code: Int): Nothing"), "end(\"bad_reply\")", "throw Gone()")
        // HELLO's problem is one of its two codes or none, never words of :llama's own.
        assertHas(link, "if (problem != null && problem != LlamaIpc.NOT_IN_BUILD && problem != LlamaIpc.NOT_ISOLATED) throw LlamaIpc.BadReply()")
        assertHas(link.between("val callback = object : Binder()", "fun bind("), "else -> return false")
        // The reader: the header first, then ints, longs, a string and byte arrays only, each while the reply holds it.
        val ipc = flatCode(kt("brain", "brain/LlamaIpc.kt"))
        assertHas(ipc, "const val REPLY_OK = 0")
        val reader = ipc.body("class Reply(private val p: Parcel)")
        assertHas(reader, "init { if (int() != REPLY_OK) throw BadReply() }", "fun end() { if (p.dataAvail() != 0) throw BadReply() }",
            "private fun need(n: Long) { if (p.dataAvail() < n) throw BadReply() }")
        assertEquals(setOf("readInt", "readLong", "readString", "createByteArray", "dataAvail", "dataPosition", "setDataPosition"),
            Regex("""\bp\.(\w+)\(""").findAll(reader.text).map { it.groupValues[1] }.toSet())
        for (read in listOf("readInt", "readLong", "readString")) assertTrue(read, reader.text.contains(Regex("""need\(\d+\)return p\.$read\(\)""")))
        // The service writes the plain header first in each of its four replies (Binder's own, for a call that threw, is refused).
        val service = flatCode(kt("brain", "brain/LlamaService.kt"))
        for (fn in listOf("hello", "load", "prepare", "generate")) {
            val body = service.body("private fun $fn(data: Parcel, reply: Parcel)").text
            val writes = Regex("""\breply\.write\w*\([^)]*\)""").findAll(body).map { it.value }.toList()
            assertEquals(fn, "reply.writeInt(LlamaIpc.REPLY_OK)", writes.first())
            assertEquals(fn, 1, writes.count { it == "reply.writeInt(LlamaIpc.REPLY_OK)" })
        }
    }

    /**
     * 1.0 audit (native-01, ipc-5): your own .litertlm's container is checked strictly before LiteRT-LM reads it, by the
     * app's check and again by :brain before the load; the import warns first.
     */
    @Test fun anImportedLiteRtLmFileIsCheckedStrictlyBeforeLiteRtLmSeesIt() {
        val engine = flatCode(kt("brain", "brain/GemmaEngine.kt"))
        assertHas(engine, "val r = LiteRtLmCheck.check(f, strict = req.model == BrainModel.OWN)")
        assertInOrder(engine, "LiteRtLmCheck.check(f, strict = req.model == BrainModel.OWN)", "e = Engine(config(req")
        assertHas(flatCode(kt("app", "sys/ModelStore.kt")), "else -> LiteRtLmCheck.check(f, strict = true)")
        val pages = flatCode(kt("app", "ui/model/ModelPages.kt"))
        assertHas(pages, "OutlinedButton(onClick = { warnImport = true }")
        // 1.1: the warning's words are in strings_model.xml (WP7), with their French; it says the file runs with Robot's permissions.
        assertHas(pages, "R.string.model_import_warning")
        for (dir in listOf("values", "values-fr-rCA")) {
            val warning = elements(xml(File(main("app"), "res/$dir/strings_model.xml")), "string").single { it.getAttribute("name") == "model_import_warning" }.textContent
            assertTrue(dir, Regex("""Robot\\?'s own permissions|autorisations de Robot""").containsMatchIn(warning))
        }
    }

    @Test fun llamaCppOnlyInTheIsolatedProcess() {
        val native = flatText(kt("brain", "brain/LlamaNative.kt"))
        assertHas(native, "System.loadLibrary(LIB)", "const val LIB = \"robot_llama\"")
        // The library loads only after the process proved isolated.
        assertHas(native, "if (!android.os.Process.isIsolated()) LlamaIpc.NOT_ISOLATED else try { System.loadLibrary(LIB)")
        // (0.5.0: the fast translation engine's JNI class in :mt is the other one: fastEngineOnlyInTheMtProcess.)
        assertEquals(listOf("brain/brain/LlamaNative.kt", "mt/mt/Slimt.kt"), usersOf(Regex("""\bexternal\s+fun\b""")))
        assertEquals(listOf("brain/brain/LlamaNative.kt", "brain/brain/LlamaService.kt"), usersOf(Regex("""\bLlamaNative\b""")))
        // The service's class: referred to by nothing but itself (bound by name, LlamaIpc.SERVICE_CLASS, as in the manifest).
        assertEquals(listOf("brain/brain/LlamaService.kt"), usersOf(Regex("""\bLlamaService\b""")))
        assertHas(flatText(kt("brain", "brain/LlamaIpc.kt")), "const val SERVICE_CLASS = \"app.heyrobot.brain.LlamaService\"")
        assertTrue(elements(xml(File(main("brain"), "AndroidManifest.xml")), "service").any { it.getAttributeNS(ns, "name") == "app.heyrobot.brain.LlamaService" })
        assertEquals(listOf("brain/brain/LlamaEngine.kt", "brain/brain/LlamaLink.kt"), usersOf(Regex("""\bLlamaLink\b""")))
        assertEquals(listOf("brain/brain/LlamaEngine.kt", "brain/brain/LlamaIpc.kt", "brain/brain/LlamaLink.kt", "brain/brain/LlamaNative.kt", "brain/brain/LlamaService.kt"),
            usersOf(Regex("""\bLlamaIpc\b""")))
        assertEquals(listOf("brain/brain/Engine.kt", "brain/brain/LlamaEngine.kt"), usersOf(Regex("""\bLlamaEngine\b""")))
        assertEquals(listOf("brain/brain/BrainService.kt", "brain/brain/Engine.kt"), usersOf(Regex("""\bEngines\b""")))
        // :brain hands the file over only to a process that proved an isolated uid (core IsolatedUid, from Binder's calling uid).
        assertHas(flatCode(kt("brain", "brain/LlamaLink.kt")), "IsolatedUid.of(from)", "Binder.getCallingUid()")
        // The GGUF content check runs where LiteRT-LM's does: the main app's import check and :brain before a load.
        assertEquals(listOf("app/sys/ModelStore.kt", "brain/brain/LlamaEngine.kt"), usersOf(Regex("""\bGgufCheck\.check\(""")))
        // The native sources are :brain's (and, 0.5.0, the fast engine's in :mt); no module builds native code through Gradle.
        for (m in modules) {
            assertTrue(m, !gradleRaw(m).contains("externalNativeBuild"))
            if (m != "brain" && m != "mt") assertTrue(m, !File(main(m), "cpp").exists())
        }
        val cpp = File(main("brain"), "cpp")
        assertEquals(setOf("CMakeLists.txt", "exports.map", "robot_llama.cpp"), cpp.list()!!.toSet())
        // Each external function is a JNI function of the bridge, and the bridge exports nothing else.
        val bridge = File(cpp, "robot_llama.cpp").readText()
        val externals = Regex("""external\s+fun\s+(\w+)""").findAll(native.text).map { it.groupValues[1] }.toSet()
        val jni = Regex("""Java_app_heyrobot_brain_LlamaNative_(\w+)\s*\(""").findAll(bridge).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("load", "free", "cancel", "trainContext", "specials", "prepare", "generate"), externals)
        assertEquals(externals, jni)
        // llama.cpp reads the model only through the descriptor it is given: the isolated process can't open a file by name.
        val bridgeCode = Flat(Source.strip(bridge, keepStrings = false, kotlin = false))
        assertHas(bridgeCode, "llama_model_load_from_file_ptr(file, mp)")
        assertTrue(!Regex("""llama_model_load_from_file\s*\(|llama_model_load_from_splits\s*\(""").containsMatchIn(bridge))
        val exports = File(cpp, "exports.map").readText()
        assertTrue(exports, Regex("""global:[^:]*\bJava_app_heyrobot_brain_LlamaNative_\*\s*;""").containsMatchIn(exports) && Regex("""local:\s*\*\s*;""").containsMatchIn(exports))
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
        val cmake = File(cpp, "CMakeLists.txt").readText().replace(Regex("#[^\n]*"), "")
        val off = Regex("""foreach\s*\(\s*off\s+([^)]*)\)\s*set\s*\(\s*\$\{off}\s+OFF\b""").find(cmake)!!.groupValues[1].split(Regex("""\s+""")).toSet()
        assertTrue(off.toString(), off.containsAll(listOf("GGML_VULKAN", "GGML_OPENCL")))
        assertTrue(Regex("""set\s*\(\s*GGML_CPU_KLEIDIAI\s+ON\b""").containsMatchIn(cmake) && cmake.contains("--version-script"))
        // The voice process and the main app never reach it (they bind :brain by class name).
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(LlamaEngine|LlamaNative|LlamaService|LlamaLink|LlamaIpc|Engines|robot_llama)\b"""),
            ours.filterKeys { !it.startsWith("brain/") }))
    }

    /**
     * 0.4.7, the problem report: only the assistant (BrainKeeper, in the :voice process) notes a model problem, as codes and
     * numbers (core ModelProblem); only the main app builds the report from it (core ProblemReport, whose test checks that
     * nothing personal gets in) and only when you open Settings › Model; and the report's code reads none of your
     * conversations, notes or people. Nothing sends it: copy and share are yours.
     * 0.6.0: nor a helper's name, address or key, a transcript, a voiceprint or the updater's token.
     * 0.7: Send feedback builds the report too, when you add it to a message; a native crash's trace (its tombstone) is read
     * in Problems.kt alone, straight into core Tombstone, which keeps the signal, a safe abort message and library frames.
     */
    @Test fun problemReportsHoldCodesAndAreNeverSent() {
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bProblems\.(note|gpuFailed|gpuTrying)\(""")))
        // (code() reads MainActivity whole since 1.0 rc1: its "any file" type in a string is no longer a comment's start, Source.)
        assertEquals(listOf("app/ui/MainActivity.kt"), usersOf(Regex("""\bProblems\.(copy|share|forgetGpuFailure|dismiss|delete)\(""")))
        assertEquals(listOf("app/ui/MainActivity.kt", "app/ui/settings/FeedbackSettings.kt"), usersOf(Regex("""\bProblems\.report\(""")))
        assertEquals(listOf("app/sys/Problems.kt"), usersOf(Regex("""\bProblemReport\.text\(""")))
        val problemsCode = code(kt("app", "sys/Problems.kt"))
        val problems = Flat(problemsCode)
        for (bad in listOf("ConversationStore", "NotesFiles", "PeopleFiles", "HttpURLConnection")) assertTrue(bad, !problemsCode.contains(bad))
        assertTrue(!problems.matches(Regex("""VoiceLogText\.describe\(e,""")))
        // The tombstone: read once, by Problems.kt, into core Tombstone and nothing else.
        assertEquals(listOf("app/sys/Problems.kt"), usersOf(Regex("""\b(traceInputStream|getTraceInputStream)\b""")))
        assertEquals(1, problems.count(Regex("""traceInputStream""")))
        assertHas(problems, "e.traceInputStream?.use(Tombstone::read)")
        // The voice log goes in through core ProblemReport, which describes wake phrases by number only.
        assertHas(problems, "VoiceLogCodec.decode(readOrNull(Paths.voiceLog(ctx)))")
        assertHas(flatCode(kt("core", "core/ModelProblems.kt")), "VoiceLogText.describe(e)")
        // 0.6.0: nor the helpers (their names, addresses and keys), conversation mode's transcripts, voiceprints or the
        // updater's access token: neither the code that builds the report nor the note and its facts reach them, and the
        // settings (where the helpers' names and addresses are) aren't read either.
        val secrets = Regex("""\b(HelperKeys|HelperKeyFiles|HelperFiles|HelperConfig|KeystoreSecret|UpdateStore|GitHubClient|Transcripts|TranscriptStore|""" +
            """TranscriptCodec|TranscriptLine|VoiceprintFiles|Voiceprints|SettingsStore|RobotSettings|VoiceHub)\b|\bapp\.heyrobot\.(helpers|update|core\.helpers)\b""")
        val reportCode = ours.filterKeys { it == "app/sys/Problems.kt" || it == "core/core/ModelProblems.kt" }
        assertEquals(2, reportCode.size)
        assertEquals(emptyList<String>(), usersOf(secrets, reportCode))
        // The voice process notes a problem with BrainKeeper's own facts only (the plan, the memory, :brain's pid).
        val note = flatCode(kt("app", "assistant/BrainLink.kt")).body("private fun noteProblem(")
        assertHas(note, "Problems.note(ctx, p)")
        assertTrue(note.text, !note.matches(secrets))
    }

    /**
     * 1.2 (design Step 5), the speeds Robot measures on this phone: a reply's numbers cross from :brain inside Done, through
     * the same Messenger message and JSON as before (nothing but numbers rides along); only the panel's replies are kept,
     * by the voice process (BrainKeeper, through SpeedFiles), and only Settings › Model reads them, in the main process.
     * The file is named in core SpeedLog alone, its code has no network and no text of yours, and no backup category names it.
     */
    @Test fun measuredSpeedsStayOnThePhone() {
        // What crosses: Done's keys, all numbers or fixed codes.
        val done = app.heyrobot.core.BrainMsg.Done(app.heyrobot.core.DoneReason.END, 1, 1, 1.0, true, false, 1500, 480.0, 90)
        assertEquals(setOf("reason", "tokens", "ttftMs", "decodeTps", "rebuilt", "textCalls", "prefillTokens", "prefillTps", "decodeTokens"),
            done.toJson().keys().asSequence().toSet())
        assertEquals(done, app.heyrobot.core.BrainMsg.decode(app.heyrobot.core.BrainProtocol.EV_DONE, done.toJson().toString()))
        // Through BrainWire's one JSON string, as every message: no new Bundle key, no other way across.
        assertEquals(1, flatCode(kt("core", "core/BrainWire.kt")).count(Regex("""putString\(""")))
        assertHas(flatText(kt("core", "core/BrainWire.kt")), "putString(BrainProtocol.KEY_JSON, payload.toJson().toString())")
        // The file: named in SpeedLog alone, opened by SpeedFiles alone.
        assertEquals(listOf("core/core/SpeedLog.kt"), ours.filter { withoutComments(it.value).contains("speeds.txt") }.keys.sorted())
        assertEquals(listOf("app/sys/SpeedFiles.kt"), usersOf(Regex("""\bSpeedLog\.FILE\b""")))
        // Written by the voice process alone: the panel's replies (PanelStream) through BrainKeeper.
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bSpeedFiles\.add\(""")))
        assertEquals(listOf("app/assistant/PanelStream.kt"), usersOf(Regex("""\bBrainKeeper\.measured\(""")))
        assertHas(flatCode(kt("app", "assistant/BrainLink.kt")).body("fun measured(plan: LoadPlan?, d: BrainMsg.Done)"),
            "SpeedLog.sample(d)", "SpeedFiles.add(ctx, plan, s)")
        // Read by Settings › Model's snapshot (the main process), and 1.2 (job 4) by the voice process's reading budget alone:
        // one median speed, a number, into core PrefillBudget, which crosses to :brain as a token count and a switch.
        assertEquals(listOf("app/assistant/BrainLink.kt", "app/ui/MainActivity.kt"), usersOf(Regex("""\bSpeedFiles\.read\(""")))
        val link = flatCode(kt("app", "assistant/BrainLink.kt"))
        assertEquals(1, link.count(Regex("""\bSpeedFiles\.read\(""")))
        assertHas(link.body("fun prefill(ctx: Context): PrefillBudget"),
            "if (p.backend != ModelBackend.CPU) return PrefillBudget.NONE",
            "val measured = SpeedLog.measured(SpeedFiles.read(ctx), SpeedFiles.key(ctx, p.model, p.backend))?.readTps",
            "return PrefillBudget.of(p.backend, measured, Speeds.readTps(p.model, p.backend, ModelStore.phone(ctx)))")
        assertEquals(listOf("app/assistant/PanelController.kt"), usersOf(Regex("""\bBrainKeeper\.prefill\(""")))
        val slow = app.heyrobot.core.BrainMsg.Generate("c", "s", emptyList(), 0, "t", false, compact = true, readBudget = 856)
        assertEquals(setOf("convId", "system", "historyCount", "text", "hasImage", "history", "tools", "think", "compact", "readBudget"),
            slow.toJson().keys().asSequence().toSet())
        assertEquals(true, slow.toJson().get("compact"))
        assertEquals(856, slow.toJson().get("readBudget"))
        // No network, no log of the numbers, nothing of your conversations or notes.
        val speeds = ours.filterKeys { it == "core/core/SpeedLog.kt" || it == "app/sys/SpeedFiles.kt" }
        assertEquals(2, speeds.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\.|HttpsURLConnection|openConnection|\bokhttp3\b|\bURL\(|\bandroid\.util\.Log\b|""" +
            """\bprintln\(|\bConversation(Store|Codec)\b|\bNotesFiles\b|\bPeopleFiles\b|\bPromptTurn\b|\bChunk\b"""), speeds))
        for ((k, f) in speeds) for (args in Flat(code(f)).calls(Regex("""\bRLog\.[iw]\("""))) assertTrue("$k: $args", Regex("""^""(,t)?$""").matches(args))
        // Never in a backup: no backup code names the file or SpeedLog.
        val backup = ours.filterKeys { it.startsWith("core/core/backup/") || it.startsWith("app/assistant/backup/") || it == "app/sys/RestoreApply.kt" }
        assertTrue(backup.keys.toString(), backup.size >= 5)
        assertEquals(emptyList<String>(), backup.filter { withoutComments(it.value).contains("speeds") || code(it.value).contains("SpeedLog") }.keys.toList())
    }

    /**
     * 1.2 (design Step 2), the self-test of the GPU (or a beta TPU build): its three prompts are fixed in core GpuCheck and
     * reach :brain only, as a plain Generate through the same JSON as every request; the voice process (BrainKeeper, the
     * only client of :brain) runs them in a conversation of its own and never runs the timer call, keeps nothing of the
     * replies but codes and numbers, and touches nothing of yours (memory, notes, conversations, the measured speeds). Its
     * record is named in core alone, written by BrainKeeper alone (Problems.checked), read by the main app's snapshot, and
     * no backup names it. The guide reaches the voice process through GpuCheckService, bound by class name, which only
     * starts and stops the check.
     */
    @Test fun theSelfTestStaysOnThePhone() {
        // The prompts: constants, and a request built from them and the model's own replies alone.
        val core = flatCode(kt("core", "core/GpuCheck.kt"))
        assertTrue(app.heyrobot.core.GpuCheck.TURNS.size == 3)
        val g = app.heyrobot.core.GpuCheck.request(2, listOf("ready", "5"))
        assertEquals(setOf("convId", "system", "historyCount", "text", "hasImage", "history", "tools", "think"), g.toJson().keys().asSequence().toSet())
        assertEquals(listOf("set_timer"), g.tools)
        assertEquals(app.heyrobot.core.GpuCheck.SYSTEM, g.system)
        // Nothing of yours, no network, no log of the replies, in the check's own code.
        val check = ours.filterKeys { it == "core/core/GpuCheck.kt" || it == "app/assistant/GpuCheckService.kt" || it == "app/sys/GpuCheckLink.kt" }
        assertEquals(3, check.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\.|HttpsURLConnection|openConnection|\bokhttp3\b|\bURL\(|\bandroid\.util\.Log\b|\bprintln\(|""" +
            """\bConversation(Store|Codec)\b|\bNotesFiles\b|\bPeopleFiles\b|\bMemory(Vault|Store|Files)\b|\bSettingsStore\b|\bRobotSettings\b|\bSpeedFiles\b|""" +
            """\bToolExecutor\b|\bPrompt\.system\("""), check))
        assertTrue(!core.matches(Regex("""\bZonedDateTime\b|\bLocalDate\b|\bnow\(""")))
        // Run by BrainKeeper alone, in its own conversation, closed at the end; the timer call is never run, nothing is measured.
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bGpuCheck\.run\b""")))
        val keeper = flatCode(kt("app", "assistant/BrainLink.kt"))
        val selfTest = keeper.body("private suspend fun selfTest(")
        assertHas(selfTest, "GpuCheck.run { _, g -> checkTurn(c, g) }", "c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(GpuCheck.CONV_ID))",
            "Problems.checked(ctx, CheckRecord(key, result.verdict, result.turn,")
        val turn = keeper.body("private suspend fun checkTurn(")
        assertHas(turn, "c.request(BrainProtocol.REQ_GENERATE, g)")
        // Only a fault (core GpuVerdict.fault: the device misbehaved) moves the model off it; a miss stays in the check's record.
        assertEquals(3, keeper.count("afterFailedCheck("))
        assertEquals(2, keeper.count("if (result.verdict.fault) return afterFailedCheck(ctx, c, plan, load, detail, result)"))
        for (b in listOf(selfTest, turn, keeper.body("private suspend fun afterFailedCheck(")))
            assertTrue(b.text, !b.matches(Regex("""\b(ToolExecutor|PanelController|ConversationStore|SpeedFiles|measured|MemoryPanel|PanelStream)\b""")))
        // The record: named in core alone, its file opened by Problems alone, written by BrainKeeper, read by the snapshot.
        assertEquals(listOf("core/core/GpuCheck.kt"), ours.filter { withoutComments(it.value).contains("gpu_check.txt") }.keys.sorted())
        assertEquals(listOf("app/sys/Problems.kt"), usersOf(Regex("""\bCheckLog\.FILE\b""")))
        assertEquals(listOf("app/assistant/BrainLink.kt"), usersOf(Regex("""\bProblems\.(checked|fingerprintFailures)\(""")))
        assertEquals(listOf("app/assistant/BrainLink.kt", "app/ui/MainActivity.kt"), usersOf(Regex("""\bProblems\.checks\(""")))
        // "Try the GPU again" and "Try the TPU again" (the main app) delete it with the failure, so the next load checks again.
        val problems = flatCode(kt("app", "sys/Problems.kt"))
        assertHas(problems.body("fun forgetGpuFailure("), "checkFile(ctx).delete()")
        assertHas(problems.body("fun forgetTpuFailure("), "checkFile(ctx).delete()")
        // Every failure read holds only on its Android build; every one written carries it.
        assertHas(problems.body("fun gpuFailure("), "it.inForce(Build.FINGERPRINT)")
        assertHas(problems.body("fun tpuFailure("), "it.inForce(Build.FINGERPRINT)")
        assertHas(problems.body("fun gpuFailed("), "f.stamped(Build.FINGERPRINT).encode()")
        assertHas(problems.body("fun tpuFailed("), "f.stamped(Build.FINGERPRINT).encode()")
        // Never in a backup.
        val backup = ours.filterKeys { it.startsWith("core/core/backup/") || it.startsWith("app/assistant/backup/") || it == "app/sys/RestoreApply.kt" }
        assertEquals(emptyList<String>(), backup.filter { withoutComments(it.value).contains("gpu_check") || code(it.value).contains("CheckLog") }.keys.toList())
        // The guide's way in: the service by class name from the main app; the service only starts and stops the check.
        assertEquals(listOf("app/sys/GpuCheckLink.kt"), ours.filter { withoutComments(it.value).contains("\"app.heyrobot.assistant.GpuCheckService\"") }.keys.sorted())
        val service = flatCode(kt("app", "assistant/GpuCheckService.kt"))
        assertEquals(listOf("BrainKeeper.guideCheck(this)", "BrainKeeper.guideLeft()"), Regex("""BrainKeeper\.\w+\([^)]*\)""").findAll(service.text).map { it.value }.toList())
        assertEquals(listOf("app/assistant/GpuCheckService.kt"), usersOf(Regex("""\bBrainKeeper\.guide(Check|Left)\(""")))
        // A guide bind with no check due leaves the panel's idle unload alone: touch() only once a check will run.
        val checkFast = keeper.body("private suspend fun checkFast(")
        assertEquals(1, checkFast.count("touch()"))
        assertTrue(checkFast.text, checkFast.at("touch()") > checkFast.at("GpuCheck.needed("))
        // Nothing comes in through the binding: the Intent is never read, the binder is a bare Binder with no transactions of
        // its own, and the service is only bound, never started. The binding gives the voice process (and :brain under it) the
        // app's priority while the guide waits, never the app's capabilities (no BIND_INCLUDE_CAPABILITIES: no microphone,
        // camera or location while in use) and no activity starts.
        assertTrue(service.text, !service.matches(Regex("""\bintent\??\.|getExtra|onTransact|Messenger|\.Stub\b|onRebind|onStartCommand""")))
        assertHas(service, "private val binder = Binder()")
        val link = flatCode(kt("app", "sys/GpuCheckLink.kt"))
        assertEquals(1, link.count("bindService("))
        assertHas(link, "ctx.bindService(Intent().setClassName(ctx.packageName, CLASS), c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)")
        // A death during the check is the check's: never counted toward the crash lockout, never written twice.
        assertHas(keeper.body("private fun onDeath("), "if (!checkUnderWay) synchronized(deaths)")
        assertHas(keeper.body("private fun onEngineError("), "|| checkUnderWay) return")
        assertHas(keeper.body("private fun modelStopped("), "val duringLoad = loadUnderWay || checkUnderWay")
        // LiteRT-LM's version in the check's key is :brain's.
        assertTrue(gradle("brain").text.contains("litertlm-android:" + app.heyrobot.core.GpuCheck.ENGINE))
    }

    /**
     * 0.7, Send feedback: Robot sends nothing itself. Its code (core's feedback package, the page, sys/Feedback.kt) has no
     * network code and no log but RLog's events without the message; the message leaves only through what you tap on the page
     * (the share sheet, the clipboard, your SMS app, your browser), never from the panel, which only opens the page; and the
     * panel's words reach it through Robot's own file, read only by the page. No permission is added for it (the identity
     * test's list is unchanged), and its GitHub link is built in core's GitHub.kt with every other GitHub address.
     */
    @Test fun feedbackSendsNothingItself() {
        val feedback = ours.filterKeys { it.startsWith("core/core/feedback/") || it == "app/sys/Feedback.kt" || it == "app/ui/settings/FeedbackSettings.kt" }
        assertEquals(5, feedback.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\.(URL\b|HttpURLConnection|Socket)|HttpsURLConnection|openConnection|\bokhttp3\b|DownloadManager|""" +
            """\bWebView\b|ConnectivityManager|\bURL\("""), feedback))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bandroid\.util\.Log\b|\bLog\.[a-z]\(|\bprintln\(|System\.(out|err)"""), feedback))
        // Every RLog line names a fixed event and at most the exception's class: never the message.
        for ((k, f) in feedback) for (args in Flat(code(f)).calls(Regex("""\bRLog\.[iw]\("""))) {
            assertTrue("$k: $args", Regex("""^""(,t)?$""").matches(args))
        }
        // 0.7.0: what you asked Robot to remember and your messages are out of its reach: no feedback code opens memory, reads
        // History, or touches the messages the main process (MessageTap) or the panel (PanelMessages) hold in RAM. Only
        // Settings › Memory's and Settings › Messages' switches go into the settings summary (core FeedbackSources).
        assertEquals(emptyList<String>(), usersOf(Regex("""\bMessageTap\b|\bMessageBuffer\b|\bMessageThread\b|\bPanelMessages\b|\bMessagesLink\b|""" +
            """\bMessagesBridge\b|\bMemory(Panel|Files|Vault|Recall|Search|Edits|Codec|Envelope|File|Item)\b|\bFileMemoryVault\b|\bConversation(Store|Codec)\b"""), feedback))
        // The ways out are the page's, one tap each.
        assertEquals(listOf("app/sys/Feedback.kt", "app/ui/settings/FeedbackSettings.kt"), usersOf(Regex("""\bFeedback(Send|Clipboard)\b""")) - "app/ui/MainActivity.kt")
        assertEquals(listOf("app/ui/MainActivity.kt", "app/ui/settings/FeedbackSettings.kt"),
            usersOf(Regex("""\bFeedbackClipboard\b""")).filter { it != "app/sys/Feedback.kt" })
        // The panel only hands over your words and opens the page (1.1: the panel's feedback turn is PanelFeedback's, and its
        // host is the panel's own, through PanelController's port).
        assertEquals(listOf("app/assistant/PanelFeedback.kt"), usersOf(Regex("""\bFeedbackFiles\.(prefill|openIntent)\(""")))
        assertEquals(listOf("app/ui/settings/FeedbackSettings.kt"), usersOf(Regex("""\bFeedbackFiles\.takePrefill\(""")))
        val route = flatCode(kt("app", "assistant/PanelFeedback.kt")).body("suspend fun run(replyId: String, c: FeedbackCommand)")
        assertHas(route, "host?.launch(FeedbackFiles.openIntent(ctx, c.kind))")
        assertHas(flatCode(kt("app", "assistant/PanelFeedback.kt")), "private val host: PanelHost? get() = port.host")
        assertHas(flatCode(kt("app", "assistant/PanelController.kt")), "override val host get() = this@PanelController.host")
        for (bad in listOf("ACTION_SEND", "SENDTO", "Clipboard", "startActivity", "FeedbackSend", "Share")) assertTrue(bad, !route.text.contains(bad))
        // The prefill file is named in core only; the intent carries the kind alone.
        val sys = flatCode(kt("app", "sys/Feedback.kt"))
        assertHas(sys, "putExtra(AppPages.EXTRA_FEEDBACK_KIND, kind.name)")
        assertTrue(!sys.matches(Regex("""putExtra\(Intent\.EXTRA_SUBJECT""")))
    }

    /** Contacts and the calendar are asked for by the main app only: nothing in the voice process requests a permission. */
    @Test fun onlyTheMainAppAsksForPermissions() {
        val askers = usersOf(Regex("""RequestMultiplePermissions|RequestPermission\b|requestPermissions\("""))
        assertEquals(listOf("app/ui/MainActivity.kt"), askers)
    }

    /** The main app opens the panel with Activity.showAssist and plain string keys (it may not import the assistant's constants). */
    @Test fun showAssistArgumentsMatchTheAssistant() {
        val vis = withoutComments(kt("app", "assistant/RobotVoiceService.kt"))
        fun const(name: String) = Regex("""const\s+val\s+$name\s*=\s*"([^"]+)"""").find(vis)!!.groupValues[1]
        val ui = flatText(kt("app", "ui/MainActivity.kt"))
        assertHas(ui, "putString(\"${const("ARG_SOURCE")}\", \"${const("SRC_APP")}\")")
        // 1.0 (ipc-1): Talk to Robot's showAssist carries Robot's single-use nonce; Continue, Run now and Ready go through the
        // :voice trampoline by class name, with the keys the trampoline takes.
        assertEquals(const("ARG_SOURCE"), app.heyrobot.core.ShowTrust.ARG_SOURCE)
        assertEquals(const("ARG_CONVERSATION"), app.heyrobot.core.ShowTrust.ARG_CONVERSATION)
        assertEquals(const("SRC_APP"), app.heyrobot.core.ShowTrust.SRC_APP)
        assertHas(ui, "putString(\"${app.heyrobot.core.ShowTrust.ARG_NONCE}\", ShowNonces.mint(this@MainActivity))")
        assertHas(ui, "panel(\"${const("ARG_CONVERSATION")}\", conversation)")
        assertHas(ui, "setClassName(packageName, \"app.heyrobot.assistant.PanelLaunchActivity\")")
        assertTrue(File(java("app"), "app/heyrobot/assistant/PanelLaunchActivity.kt").isFile)
        // The only showAssist in the app is Talk to Robot's, with the source and the nonce and nothing else. 1.1: the Phone lock
        // cover's Ask Robot is another, with no arguments at all, so it opens only the plain panel (ShowGate).
        assertEquals(listOf("app/lock/LockCoverActivity.kt", "app/ui/MainActivity.kt"), usersOf(Regex("""\bshowAssist\(""")))
        assertEquals(listOf("showAssist(Bundle())"), Regex("""\bshowAssist\([^)]*\)\)?""").findAll(code(kt("app", "lock/LockCoverActivity.kt"))).map { it.value }.toList())
        assertEquals(1, Regex("""\bshowAssist\(""").findAll(code(File(java("app"), "app/heyrobot/ui/MainActivity.kt"))).count())
        val talk = ui.body("showAssist(Bundle().apply {")
        assertEquals(2, talk.count(Regex("""\bputString\(""")))
        // Class names the main and voice processes use as strings.
        assertHas(flatText(kt("app", "sys/Assistant.kt")), "\"app.heyrobot.assistant.RobotVoiceService\"")
        assertTrue(kt("app", "assistant/RobotVoiceService.kt").isFile)
    }

    /**
     * 1.0 audit (ipc-1, ipc-2): any app on top can open the panel with showAssist and arguments of its choosing
     * (SHOW_SOURCE_APPLICATION). The session reads a show's arguments only through ShowGate, which keeps them only when the
     * assistant service made the show (its token) and an app's show at most "from the app" with Robot's nonce; Run now, Ready
     * and Continue go through the non-exported :voice trampoline, which asks the service; the camera's return counts only
     * while the panel is hidden for it.
     */
    @Test fun anotherAppsShowOpensOnlyThePlainPanel() {
        val session = flatCode(kt("app", "assistant/RobotSession.kt"))
        val onShow = session.body("override fun onShow(args: Bundle?, showFlags: Int)")
        assertHas(onShow, "PanelController.onShow(ShowGate.args(context, args, showFlags), Assistant.locked(context), showFlags)")
        assertEquals(1, session.count("PanelController.onShow("))
        val gate = flatCode(kt("app", "assistant/ShowGate.kt"))
        assertHas(gate, "showFlags and VoiceInteractionSession.SHOW_SOURCE_APPLICATION != 0",
            "ShowTrust.from(fromApp, args.getString(ShowTrust.ARG_VIS), RobotVoiceService.TOKEN, args.getString(ShowTrust.ARG_NONCE))",
            "for (k in keys) if (k !in keep) out.remove(k)")
        // The token is put on a show by the service's show() alone, and lives only in :voice's memory.
        assertEquals(listOf("app/assistant/RobotVoiceService.kt"), usersOf(Regex("""putString\(ShowTrust\.ARG_VIS""")))
        assertEquals(listOf("app/assistant/RobotVoiceService.kt", "app/assistant/ShowGate.kt"), usersOf(Regex("""\bRobotVoiceService\.TOKEN\b|\bval TOKEN\b""")))
        assertHas(flatCode(kt("app", "assistant/RobotVoiceService.kt")), "val TOKEN: String = java.util.UUID.randomUUID().toString()")
        // The trampoline asks the service, takes only its three string keys, and is not exported (the manifest test).
        val tramp = flatCode(kt("app", "assistant/PanelLaunchActivity.kt"))
        assertHas(tramp, "RobotVoiceService.instance?.show(RobotVoiceService.SRC_APP, extras)",
            "val KEYS = listOf(RoutineIntents.ARG_ROUTINE, RoutineIntents.ARG_READY, RobotVoiceService.ARG_CONVERSATION)")
        // The panel: a "capture" show counts only while hidden for the camera, and the lock-screen rule has no exception.
        val panel = flatText(kt("app", "assistant/PanelController.kt"))
        val show = panel.body("fun onShow(args: Bundle?, locked: Boolean, showFlags: Int = 0)")
        assertHas(show, "val capturePending = hidingFor == \"capture\"", "val back = source == RobotVoiceService.SRC_CAPTURE && capturePending",
            "startedLocked = PanelContinuity.stillLocked(startedLocked, locked)")
        assertInOrder(show, "PanelContinuity.open(", "if (!returning) {")
        assertEquals(3, panel.count("startedLocked = PanelContinuity.stillLocked("))
        // 1.0, the other way round: an unlocked show after a locked-begun conversation is new, unless its last reply asked
        // for this unlock (then quoted); a question asked unlocked in the panel still open quotes what came before it.
        assertHas(show, "unlockAsked = PanelContinuity.unlockAsked(chat, System.currentTimeMillis())", "PanelContinuity.Open.CONTINUE_QUOTED -> quoteLockedTurns()")
        val send = panel.body("fun send(text: String, fromVoice: Boolean = false): Boolean")
        assertInOrder(send, "quoteLockedTurns()", "startedLocked = PanelContinuity.stillLocked(")
        // History keeps a conversation begun on the lock screen marked, so its Continue quotes those turns too.
        // FX1, deliberately: a conversation continued while travel mode hid Files answers is saved with them as stored.
        assertHas(panel.body("private fun persist()"), "PanelContinuity.stored(PanelFiles.saved(chat.toList()), startedLocked)")
        assertEquals(1, panel.count("StoredConversation("))
    }

    /**
     * 1.0 audit, prompt injection, where the panel meets core: the brief's and "what's next"'s reply carries core Quoted's
     * content record; QueryCheck gets what read tools returned; memory items recalled in answers count. (1.0's parked reply
     * tips, which held a sender's name, are gone with the park list.)
     */
    @Test fun injectionMarksReachThePanel() {
        val panel = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(panel, "actions = records(actions) + if (quoted) listOf(Quoted.record(turnLangs)) else emptyList()",
            "override fun quoted(replyId: String) { synchronized(quotedReplies) { quotedReplies += replyId } }")
        val brief = flatCode(kt("app", "assistant/routines/PanelRoutines.kt")).body("private suspend fun brief(")
        assertInOrder(brief, "host.quoted(replyId)", "RoutineRunner.brief(")
        val hf = flatCode(kt("app", "assistant/handsfree/PanelHandsFree.kt"))
        assertInOrder(hf.body("private suspend fun whatsNext("), "host.quoted(replyId)", "host.text(replyId, line)")
        assertFalse(hf.hasLoose("tips ="))
        // What every read tool returned (not only its card) goes to QueryCheck, until the conversation changes (1.1: the
        // reads and QueryCheck's context are PanelWebSearch's; the panel's actions note each read there, and the panel's
        // two conversation changes forget them).
        val web = flatCode(kt("app", "assistant/PanelWebSearch.kt"))
        assertHas(panel, "override suspend fun run(action: Action): Executed = runAction(action).also(webSearch::noteRead)")
        assertHas(web, "WebQueryContext.of(chat.toList(), turnSaid.orEmpty(), screen, bodies + reads, memory, fresh = readsAllKnown)")
        assertEquals(2, panel.count("forgetReads(allKnown =") + web.count("forgetReads(allKnown ="))
        // Memory: every turn's items, and what a content item leads to is saved as content.
        assertHas(panel, "val given = app.heyrobot.core.memory.MemoryTaint.given(chat)",
            "_state.value.turns.lastOrNull()?.actions?.any { it.content } == true, turnMemContent)")
    }

    /**
     * 1.0 audit (secrets-history-refused-remember, secrets-lockscreen-notification-content, secrets-memory-private-items-not-
     * secure): the panel saves a refused remember's placeholder; the notifications that hold words are secret on the lock
     * screen; Settings › Memory and a panel showing a private answer are FLAG_SECURE, and Memory's fields don't teach the keyboard.
     */
    @Test fun privateWordsStayOffHistoryTheLockScreenAndScreenshots() {
        val panel = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(panel, "memRoute?.let { MemoryPanel.question(it, q) }")
        assertHas(flatCode(kt("app", "assistant/MemoryPanel.kt")), "fun question(route: MemoryRoute, said: String): String = MemoryHygiene.question(route, said)")
        val notices = flatCode(kt("app", "assistant/routines/RoutineSpeakingService.kt"))
        for (f in listOf("fun ready(ctx: Context, cards: List<ReadyCard>)", "fun silent(ctx: Context, r: Routine, text: String)", "fun summary(ctx: Context, text: String?)")) {
            val body = notices.body(f)
            assertTrue(f, body.has(".setVisibility(Notification.VISIBILITY_SECRET)") && !body.text.contains("VISIBILITY_PRIVATE"))
        }
        // Car mode's notifications hold no words of yours (1.0: the "From your drive" list is gone, only ever cancelled).
        val car = flatCode(kt("app", "assistant/car/CarNotices.kt"))
        assertFalse(car.text, car.hasLoose("fun parked(") || car.text.contains("BigTextStyle"))
        assertTrue(car.has("cancel(ctx, LEGACY_PARKED_ID)") && !car.hasLoose("notify(ctx, LEGACY_PARKED_ID"))
        val memory = flatCode(kt("app", "ui/settings/MemorySettings.kt"))
        val screen = memory.body("fun MemorySettingsScreen(")
        assertHas(screen, "SecureWindow() NoImeLearning { PreferenceScreen(\"\", onBack, snackbar = snackbar) {")
        assertHas(memory.body("private fun ItemDialog("), "properties = SecureDialog,")
        assertHas(screen, "secure = true, onDismiss = { adding = false }")
        assertHas(panel, "closeConvAfterTurn = true host?.secure(true)")
        assertEquals(2, panel.count("host?.secure(false)"))
        assertHas(flatCode(kt("app", "assistant/RobotSession.kt")),
            "if (on) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)")
    }

    /**
     * 1.0 audit (physical-routines-writable-locked): the panel refuses routine changes, reminders and the list on the locked
     * phone before anything else, and checks again just before a store write (the phone may lock while a card waits).
     */
    @Test fun theLockedPhoneChangesNoRoutine() {
        val panel = flatCode(kt("app", "assistant/routines/PanelRoutines.kt"))
        assertInOrder(panel.body("suspend fun handle("), "RoutineLockedPhone.refuse(cmd, host.locked", "val done = when (cmd)")
        assertInOrder(panel.body("private suspend fun create("), "if (host.locked) {", "store().save(saved)")
        assertInOrder(panel.body("private suspend fun delete("), "if (host.locked) {", "store().delete(r.id)")
        assertInOrder(panel.body("private suspend fun remind("), "if (host.locked) return", "store().editReminders")
    }

    @Test fun voiceInteractionMetadataNamesTheComponents() {
        val x = xml(File(main("app"), "res/xml/voice_interaction.xml"))
        assertEquals("voice-interaction-service", x.tagName)
        assertEquals("app.heyrobot.assistant.RobotSessionService", x.getAttributeNS(ns, "sessionService"))
        assertEquals("app.heyrobot.assistant.RobotRecognitionService", x.getAttributeNS(ns, "recognitionService"))
        assertEquals("true", x.getAttributeNS(ns, "supportsAssist"))
        assertEquals("true", x.getAttributeNS(ns, "supportsLaunchVoiceAssistFromKeyguard"))
        // The recognizer stays selectable as the default (the attribute absent or true).
        val rec = xml(File(main("app"), "res/xml/recognition_service.xml"))
        assertEquals("recognition-service", rec.tagName)
        assertTrue(rec.getAttributeNS(ns, "selectableAsDefault") in setOf("", "true"))
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
     * Settings › Updates. Main process only: nothing of the voice or model processes (or :helpers) refers to the updater, and
     * its components have no android:process. GitHubClient is the only code that goes to GitHub, over HTTPS to the hosts core's
     * GitHubHosts allows, following redirects itself; the access token is decrypted there alone and set only on a request
     * GitHubHosts.mayAuthorize allows (api.github.com). The checks before an install can't be skipped: Updater hashes the file
     * and runs ArchiveCheck before it hands it to UpdateInstaller, which hashes it again as it writes the session.
     */
    @Test fun theUpdaterStaysInTheMainProcessAndItsTokenGoesOnlyToGitHubsApi() {
        val updater = Regex("""\bapp\.heyrobot\.(update|core\.update)\b|\b(Updater|UpdateStore|UpdateToken|GitHubClient|UpdateInstaller|UpdateJobs|UpdateNotices)\b""")
        assertEquals(emptyList<String>(), usersOf(updater, ours.filterKeys { it.startsWith("voice/") || it.startsWith("brain/") || it.startsWith("helpers/") ||
            it.startsWith("mt/") || it.startsWith("app/assistant/") }))
        // Who uses what.
        // (1.0: and Termux's weekly update check, through publicJson, below.)
        assertEquals(listOf("app/update/GitHubClient.kt", "app/update/TermuxUpdateCheck.kt", "app/update/Updater.kt"), usersOf(Regex("""\bGitHubClient\b""")))
        assertEquals(listOf("app/update/GitHubClient.kt"), usersOf(Regex("""\bUpdateToken\.read\(""")))
        assertEquals(listOf("app/update/UpdateInstaller.kt", "app/update/Updater.kt"), usersOf(Regex("""\bUpdateInstaller\.install\(|\bPackageInstaller\b""")))
        assertEquals(listOf("app/update/UpdateInstaller.kt"), usersOf(Regex("""\bPackageInstaller\b""")))
        assertEquals(listOf("app/update/UpdateInstaller.kt"), usersOf(Regex("""\bcanRequestPackageInstalls\b|getPackageArchiveInfo""")))
        // Only GitHubClient sets an Authorization header with the token, once, on a request GitHubHosts.mayAuthorize allows,
        // and never lets the platform follow a redirect with it. (0.6.0: the only other Authorization header is a helper's
        // own key, set by the :helpers module's HelperHttp in the :voice process, which never refers to the updater: above;
        // and web search's request builder in core, SearchWire, names Kagi's key header, for the :helpers module's
        // WebTransport to send as it is, in the :voice process, 0.9.)
        assertEquals(listOf("app/update/GitHubClient.kt", "core/core/web/SearchWire.kt", "helpers/helpers/http/HelperHttp.kt"),
            ours.filter { withoutComments(it.value).contains("\"Authorization\"") }.keys.sorted())
        val client = flatText(kt("app", "update/GitHubClient.kt"))
        assertEquals(1, client.count(Regex("\"Authorization\"")))
        assertHas(client, "if (token != null && GitHubHosts.mayAuthorize(url)) c.setRequestProperty(\"Authorization\", \"Bearer \$token\")")
        assertHas(client, "c.instanceFollowRedirects = false", "if (!GitHubHosts.allowed(url)) throw", "GitHubHosts.redirect(url, c.getHeaderField(\"Location\"), ++hop)",
            "as? HttpsURLConnection")
        // It opens only the URLs core builds from the repository and ids.
        assertHas(client, "repo.releasesUrl()", "repo.assetUrl(file.id)")
        // 1.0, Termux's update check: only core TermuxUpdates' two fixed addresses, without the token, following no redirect;
        // and the check itself reads two answers and keeps them: no download, no install, no intent, no other request.
        val public = client.body("fun publicJson(")
        assertHas(public, "if (!TermuxUpdates.allowed(url)) throw Failure(UpdateFail.REDIRECT_REFUSED)", "SecretRead.Absent, 0, abort, network, public = true")
        assertHas(client, "val token = if (public) null else (saved as? SecretRead.Present)?.value",
            "if (public) { if (!TermuxUpdates.allowed(url)) throw Failure(UpdateFail.REDIRECT_REFUSED) }",
            "if (public) throw Failure(UpdateFail.REDIRECT_REFUSED, code)")
        val termuxCheck = flatText(kt("app", "update/TermuxUpdateCheck.kt"))
        assertEquals(listOf("TermuxUpdates.FDROID_URL", "TermuxUpdates.GITHUB_URL"),
            Regex("""GitHubClient\.publicJson\(ctx,([A-Za-z_.]+),""").findAll(termuxCheck.text).map { it.groupValues[1] }.toList().sorted())
        assertEquals(listOf("app/update/TermuxUpdateCheck.kt"), usersOf(Regex("""\bGitHubClient\.publicJson\(""")))
        assertTrue(listOf("DownloadManager", "PackageInstaller", "download(", "Intent", "startActivity", "openConnection", "UpdateToken").none { termuxCheck.hasLoose(it) })
        // No cookies: nothing installs a cookie handler for java.net's connections.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bCookieHandler\b|\bjava\.net\.CookieManager\b""")))
        assertEquals(2, client.count("repo.assetUrl(file.id)"))
        // GitHub's URLs are written in one place, core's GitHub.kt (the Licences list's source link is data, not code).
        assertEquals(listOf("core/core/update/GitHub.kt"), ours.filter { Regex("""https://[^"\s]*github""").containsMatchIn(withoutComments(it.value)) }.keys.sorted())
        // Its hosts, and the token only for the API's (core GitHubPolicyTest too).
        val hosts = app.heyrobot.core.update.GitHubHosts
        assertEquals("api.github.com", hosts.API)
        assertEquals(setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"), hosts.ALLOWED)
        assertTrue(hosts.mayAuthorize("https://api.github.com/repos/o/r/releases"))
        for (u in listOf("https://github.com/o/r", "https://objects.githubusercontent.com/x", "https://release-assets.githubusercontent.com/x",
            "https://api.github.com.evil.example/x", "https://evil.example/api.github.com"))
            assertFalse(u, hosts.mayAuthorize(u))
        // The token is kept by KeystoreSecret with its own Keystore key, and the default repository is the one the Licences
        // list names.
        val store = flatText(kt("app", "update/UpdateStore.kt"))
        assertHas(store, "KeystoreSecret(ALIAS, \"github_token\", \"update_token\")", "const val ALIAS = \"robot_github_token\"")
        // The same class keeps every helper's key (sys/HelperFiles.kt, read only by the :voice process's HelperKeys, above);
        // the token's alias and files are named in UpdateStore.kt alone, and no helper's can be them.
        // 0.9: and the web search services' keys (sys/WebFiles.kt, read only by the :voice process's WebKeys: webSearch… below).
        assertEquals(listOf("app/sys/HelperFiles.kt", "app/sys/KeystoreSecret.kt", "app/sys/WebFiles.kt", "app/update/UpdateStore.kt"), usersOf(Regex("""\bKeystoreSecret\(""")))
        assertEquals(listOf("app/update/UpdateStore.kt"), ours.filter { withoutComments(it.value).contains("robot_github_token") || withoutComments(it.value).contains("\"github_token\"") }.keys.sorted())
        assertTrue(listOf("claude", "github_token", "x").none { app.heyrobot.sys.HelperKeyFiles.alias(it) == "robot_github_token" })
        assertHas(store, "GitHubRepo.fromSourceLink(LicenceBook.parse(json).robot.source)")
        // The checks before an install, in order: the hash, then what Android says about the APK, then the session.
        val up = flatCode(kt("app", "update/Updater.kt"))
        assertInOrder(up.body("internal fun install(ctx: Context): String?"), "sha256(file) != d.sha256", "ArchiveCheck.check(", "UpdateInstaller.install(")
        val dl = up.body("internal fun download(")
        assertInOrder(dl, "Sha256Sums.parse(", "GitHubClient.download(")
        assertHas(dl, "ArchiveCheck.check(", "part.renameTo(apk)")
        val installer = flatCode(kt("app", "update/UpdateInstaller.kt"))
        assertHas(installer, "if (hex(md.digest()) != sha256) {")
        assertInOrder(installer, "session.abandon()", "session.commit(")
    }

    /**
     * After an update, as after a restart (README "Updates" › "After an update"): MY_PACKAGE_REPLACED runs the boot path's
     * own routine (afterRestart: the recognizer setting and the assistant check) and then only the updater's part, and
     * neither starts or binds anything. 0.6.0's processes need no step of their own: conversation capture in :voice (a
     * session the update ended is closed when the assistant comes back, with Resume), :mt, :brain and its isolated :llama
     * start when something asks them.
     */
    @Test fun afterAnUpdateRobotDoesWhatItDoesAfterARestart() {
        val boot = flatCode(kt("app", "boot/BootReceivers.kt"))
        assertEquals(2, boot.count("afterRestart(ctx,"))
        assertHas(boot, "afterRestart(ctx, if (locked) RepairWhen.LOCKED_BOOT else RepairWhen.BOOT)", "afterRestart(ctx, RepairWhen.UPDATE) { Updater.afterUpdate(it) }")
        val starts = Regex("""\b(startService|startForegroundService|bindService|startActivity)\b|\bConversation(Service|Session|Tile\w*)\b|""" +
            """\b(MtLink|MtSpeedRun|BrainLink|BrainClient|BrainKeeper|VoiceHub|HelperLink|Transcripts)\b|\bLlama\w*""")
        assertEquals(emptyList<String>(), usersOf(starts, ours.filterKeys { it.startsWith("app/boot/") }))
        val after = flatCode(kt("app", "update/Updater.kt")).body("fun afterUpdate(ctx: Context)")
        assertHas(after, "UpdateJobs.ensure(")
        assertTrue(after.text, !after.matches(starts))
    }

    /**
     * 1.1, Phone lock (docs/plans/phone-lock-design.md §4, §15): the code is checked only in :lock's code pad, its file is
     * named only by its vault, only :lock's files write state.json, the main process reads it only through LockStore, and
     * :lock reaches none of the model, the helpers' keys, the web search keys or the memory vault.
     */
    @Test fun phoneLockKeepsItsBoundaries() {
        // The vault is made only in the code pad (its own declaration aside).
        assertEquals(listOf("app/lock/LockCodeActivity.kt"), usersOf(Regex("""(?<!class )\bLockCodeVault\(""")))
        // code.json is named in code only by the vault; the other processes learn that a code exists from state.codeSet.
        assertEquals(listOf("app/lock/LockCodeVault.kt"), ours.filter { withoutComments(it.value).contains("code.json") }.keys.sorted())
        // Only :lock's files write state.json.
        val lockOnly = setOf("app/lock/LockEngine.kt", "app/lock/LockAlarmReceiver.kt", "app/lock/LockAccessibilityService.kt", "app/lock/LockCoverActivity.kt",
            "app/lock/LockCodeActivity.kt", "app/lock/LockCodeVault.kt", "app/lock/LockNotices.kt", "app/lock/LockClock.kt")
        val writers = usersOf(Regex("""\bLockStore\.editState\("""))
        assertTrue(writers.toString(), writers.isNotEmpty() && lockOnly.containsAll(writers))
        // The main process and :voice read state.json only through LockStore (readState, PhoneLockNow).
        assertEquals(listOf("app/lock/LockStore.kt"), usersOf(Regex("""\bLockCodec\.decodeState\b|\bLockStore\.STATE\b""")))
        // Nothing outside :lock's files names its engine, its windows' classes or its vault (the others use LockSignals' names).
        val lockClasses = Regex("""\b(LockEngine|LockCodeVault|LockAccessibilityService|LockCoverActivity|LockCodeActivity|LockNotices|LockAlarms)\b""")
        assertEquals(emptyList<String>(), usersOf(lockClasses, ours.filterKeys { it !in lockOnly && it != "app/lock/LockAlarmReceiver.kt" }))
        // :lock never names the model, the helpers' or web search's keys, or the memory vault, and never goes online.
        val lockFiles = ours.filterKeys { it.startsWith("app/lock/") }
        assertTrue(lockFiles.size >= 9)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.(assistant|voice|brain|mt|helpers)\b|\b(GemmaEngine|BrainService|BrainLink|LlamaService|HelperKeys|WebKeys|MemoryVault|VoiceHub|PanelController)\b|MemoryFiles\.vault|\bjava\.net\b|HttpURLConnection"""), lockFiles))
        // Phase B: a lock by voice reaches :lock only from the panel's card (PanelLock, after Lock), car mode only from car
        // mode's own start and end; usage events are read only in :lock; and the voice path never handles a code (the code pad does).
        assertEquals(listOf("app/assistant/PanelLock.kt"), usersOf(Regex("""\bLockSignals\.startVoice\(""")))
        assertEquals(listOf("app/assistant/car/CarModeController.kt"), usersOf(Regex("""\bLockSignals\.car\(""")))
        assertEquals(listOf("app/lock/LockClock.kt", "app/lock/LockEngine.kt"), usersOf(Regex("""\b(UsageReader|queryEvents)\b""")).filter { it.startsWith("app/lock/") || it == "app/assistant/PanelLock.kt" })
        // Car mode your car started, during your own lock: the service's own cover paths (a lock starting, the unlock) skip it too.
        assertHas(flatText(kt("app", "lock/LockAccessibilityService.kt")), "if (facts.carOwnLock) return removeCurtain()", "!facts.carOwnLock) showCover()")
        val panelLock = code(kt("app", "assistant/PanelLock.kt"))
        assertFalse(Regex("""\b(LockCodeRules|LockCodeVault|codeSet|digits)\b""").containsMatchIn(panelLock))
        // The lock code goes nowhere near a log or the voice log.
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(RLog\.[iwe]|VoiceLog\.add)\([^)]*\b(code|typed|first)\b"""), lockFiles))
    }

    /**
     * Privacy (spec item 10): no network code but the :helpers module's SDK client and, since 0.4.0, the one Open-Meteo
     * client for the weather (DownloadManager does the model downloads), no audio files, no raw logging, and one
     * accessibility service only, Phone lock's (1.1), which sees which app is in front and drives none (Ask Claude must not
     * drive the Claude app).
     */
    @Test fun noNetworkNoAudioFilesNoRawLogs() {
        // java.net.IDN only spells a host name in ASCII, like URLEncoder and URI (core web WebUrl).
        assertEquals(listOf("app/sys/NominatimClient.kt", "app/sys/OpenMeteoClient.kt", "app/update/GitHubClient.kt"),
            usersOf(Regex("""\bjava\.net\.(?!URLEncoder|URI\b|IDN\b)|HttpURLConnection|HttpsURLConnection|\bSocket\b|okhttp|\bWebView\b|\bopenConnection\("""),
            ours.filterKeys { !it.startsWith("helpers/") }))
        // 0.4.0: the nearest place for the maps goes only to the Nominatim server in Settings (OpenStreetMap's unless you chose
        // another, checked by core NearbyServer: HTTPS only), a second apart at most, its raw request private, and only the
        // maps tools use it (core Nearby.kt builds the URL).
        val osm = flatText(kt("app", "sys/NominatimClient.kt"))
        assertHas(osm, "require(u.protocol == \"https\" && u.host == server.host && u.port == server.port", "c.instanceFollowRedirects = false", "searches.search(")
        assertHas(flatCode(kt("app", "assistant/ToolExecutor.kt")), "val server = settings.nearbyServer")
        // A search server's address is always HTTPS (core NearbyTest checks the rest of NearbyServer).
        for (server in listOf(app.heyrobot.core.NearbyServer.DEFAULT, app.heyrobot.core.NearbyServer.of("nominatim.example.org")))
            assertTrue(server.base, server.base.startsWith("https://"))
        // Its one request function is private.
        assertTrue(osm.has("private fun get(") && osm.count(Regex("""\bfun get\(""")) == osm.count(Regex("""\bprivate fun get\(""")))
        // The pace (a second apart at most) and the cache are core's NearbySearch, every request through it: core
        // NearbySearchTest (nothingInEitherBoxIsNothingNearby, aRecentAnswerIsReusedAndRequestsAreCounted) runs them.
        assertEquals(listOf("app/assistant/ToolExecutor.kt", "app/sys/NominatimClient.kt"), usersOf(Regex("""\bNominatimClient\b""")))
        // It goes only to Open-Meteo's two hosts, over HTTPS (core Weather.kt builds the URLs), its raw request is private, and
        // only the weather tool and Settings › Weather use it.
        val meteo = flatText(kt("app", "sys/OpenMeteoClient.kt"))
        assertHas(meteo, "private val HOSTS = setOf(\"api.open-meteo.com\", \"geocoding-api.open-meteo.com\")")
        assertHas(meteo, "require(u.protocol == \"https\" && u.host in HOSTS)", "c.instanceFollowRedirects = false")
        assertTrue(meteo.has("private fun get(") && meteo.count(Regex("""\bfun get\(""")) == meteo.count(Regex("""\bprivate fun get\(""")))
        assertEquals(listOf("app/assistant/ToolExecutor.kt", "app/sys/OpenMeteoClient.kt", "app/ui/ToolsSettings.kt"), usersOf(Regex("""\bOpenMeteoClient\b""")))
        // 1.1: one accessibility service, Phone lock's, which sees which app is in front and nothing else: from an event it
        // reads the type, the package and the class, never the text, the source node, a description or a window's content,
        // and it never acts on another app (no actions, no global actions, no gestures). Ask Claude still drives no app.
        assertEquals(listOf("app/lock/LockAccessibilityService.kt"), usersOf(Regex("""\bAccessibilityService\b|accessibilityservice""")))
        val a11y = code(kt("app", "lock/LockAccessibilityService.kt"))
        for (bad in listOf("getText", ".text", "getSource", ".source", "getContentDescription", "contentDescription", "getRootInActiveWindow", "rootInActiveWindow",
            "getWindows", ".windows", "findAccessibilityNodeInfos", "performAction", "performGlobalAction", "dispatchGesture", "AccessibilityNodeInfo",
            "takeScreenshot", "getRecords", "getRecord(", "beforeText", "isPassword"))
            assertFalse(bad, a11y.contains(bad))
        assertTrue(Regex("""event\.(eventType|packageName|className)""").findAll(a11y).count() >= 3)
        // 1.2 (job 6b): a lock never opens Android's settings, app stores, installers or the apps that can remove, stop or
        // pause Robot (core LockGate.NEVER_OPEN, pinned in LockGateTest): the editor's picker offers none and saves none, and
        // nothing keeps a list of its own.
        val editor = flatText(kt("app", "ui/settings/PhoneLockEditor.kt"))
        assertHas(editor, "it != ctx.packageName && it !in LockGate.NEVER_OPEN && LockLimits.validPackage(it)", "onSave(picked.filter { it !in LockGate.NEVER_OPEN })")
        assertEquals(listOf("core/core/lock/LockGate.kt"), usersOf(Regex("""\bNEVER_OPEN\s*(:[^=]*)?=""")))
        // The only URLs in code (comments that cite a page don't count): Gemma's and the voice models' pinned Hugging Face
        // files and the fast translation engine's pinned Mozilla files (0.5.0), fetched by DownloadManager, the claude.ai link
        // the Claude app hand-off tries (the API's host is the SDK's own), Open-Meteo's two and Nominatim's (0.4.0), and the
        // helpers' companies' API addresses (0.6.0, core Helpers.kt's presets, used only by the :helpers module's clients in
        // the :voice process, with your key, when you ask), and the example addresses Settings › Helpers shows when you type
        // your server's (text only, never fetched). Licences.kt (0.4.5) fetches none: it only checks that the licence list's
        // links (assets/licenses/components.json) are https. The updater's (GitHub's API, a release's page) are built in
        // core's GitHub.kt alone, from the repository and ids. Web search's request builders (0.9, core web SearchWire,
        // Wikipedia and OpenLibrary: Brave, Kagi, Mojeek, Wikipedia's and Open Library's API addresses) are sent by the
        // :helpers module's WebTransport in the :voice process; Settings › Web search shows an example address (text only).
        // 0.9: Termux's F-Droid page (core termux TermuxSetup.kt), which Settings › Termux opens in the browser; Robot fetches nothing.
        // 1.0: F-Droid's API entry for Termux (core termux TermuxUpdates.kt), which the weekly update check asks.
        // 1.0: DuckDuckGo's Instant Answer API (core web DuckDuckGo.kt), sent like the other services'; and the search engines'
        // own pages and the short list of public SearXNG servers (core web Engines.kt): your browser opens an engine's, Robot
        // fetches none of them, and a public SearXNG is reached only once you pick it at set-up.
        // 1.1: Robot's Ko-fi page (ui/settings/SupportSettings.kt, from support-addresses.txt), which Settings › About › Support
        // Robot opens in the browser when you tap it; Robot fetches nothing.
        assertEquals(listOf("app/ui/HelpersScreens.kt", "app/ui/settings/SupportSettings.kt", "app/ui/settings/WebSearchSettings.kt", "core/core/ClaudeApp.kt", "core/core/Licences.kt",
            "core/core/ModelState.kt", "core/core/MtBundle.kt", "core/core/Nearby.kt", "core/core/VoiceModels.kt", "core/core/Weather.kt",
            "core/core/helpers/Helpers.kt", "core/core/termux/TermuxSetup.kt", "core/core/termux/TermuxUpdates.kt", "core/core/update/GitHub.kt",
            "core/core/web/DuckDuckGo.kt", "core/core/web/Engines.kt", "core/core/web/OpenLibrary.kt", "core/core/web/SearchWire.kt",
            "core/core/web/Wikipedia.kt"),
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
     * 0.9 web search: the HTTP clients, the page fetcher, jsoup and the
     * copy of Readability4J live only in the :helpers module (app.heyrobot.helpers.web), which only the assistant (the :voice
     * process) uses; the main process never refers to them (mainProcessCodeNeverReachesEngines) and its Settings page
     * opens the set-up window by class name. A search key is read only by WebKeys, for a search (WebTool) or Test (the
     * set-up window). Pages are fetched only through PageFetcher's checks (no redirect followed by OkHttp, the public-only
     * DNS filter and socket check, no proxy, no cookies), and nothing in a reply loads a remote image.
     */
    @Test fun webSearchStaysInTheVoiceProcessBehindItsChecks() {
        val jsoup = usersOf(Regex("""\borg\.jsoup\b"""))
        assertTrue(jsoup.toString(), jsoup.isNotEmpty() && jsoup.all { it.startsWith("helpers/helpers/web/") })
        val webUsers = usersOf(Regex("""\bapp\.heyrobot\.helpers\.web\b"""), ours.filterKeys { !it.startsWith("helpers/") })
        assertEquals(listOf("app/assistant/PanelWebSearch.kt", "app/assistant/ToolExecutor.kt", "app/assistant/WebSearchActivity.kt", "app/assistant/WebTool.kt"), webUsers)
        assertTrue(gradle("helpers").text.contains("org.jsoup:jsoup:1.23.2"))
        for (m in listOf("core", "brain", "voice", "mt", "app")) assertTrue(m, !gradleRaw(m).contains("org.jsoup"))
        // Its readability copy logs nothing (no slf4j, no Log).
        val readability = ours.filterKeys { it.startsWith("helpers/helpers/web/readability/") }
        assertTrue(readability.size >= 10)
        assertEquals(emptyList<String>(), usersOf(Regex("""slf4j|LoggerFactory|\bRLog\b|\bprintln\("""), readability))
        // Keys: only WebKeys reads or saves one, only WebTool and the set-up window ask it, and the main process only deletes.
        // (1.0: and TermuxSecrets, to refuse a Termux command holding one.)
        // (1.1: and BackupSecrets, for a backup with Keys and private codes on.)
        assertEquals(listOf("app/assistant/TermuxSecrets.kt", "app/assistant/WebSearchActivity.kt", "app/assistant/WebTool.kt", "app/assistant/backup/BackupSecrets.kt"),
            usersOf(Regex("""\bWebKeys\.read\(""")))
        assertEquals(listOf("app/assistant/WebTool.kt"), usersOf(Regex("""\bWebKeyFiles\.store\(""")))
        val webFiles = code(kt("app", "sys/WebFiles.kt"))
        assertTrue(!Regex("""\.(read|save)\s*\(\s*ctx\b""").containsMatchIn(webFiles))
        assertHas(flatCode(kt("app", "ui/settings/WebSearchSettings.kt")), "setClassName(ctx.packageName, WebWindow.CLASS)")
        // The page fetcher's rules: OkHttp follows no redirect (each hop is checked here), the DNS filter and the socket check
        // are on, never a proxy; the search transport follows none either.
        val fetcher = flatText(kt("helpers", "helpers/web/PageFetcher.kt"))
        assertHas(fetcher, ".followRedirects(false)", ".followSslRedirects(false)", ".dns(PublicOnlyDns(dns, rules.isPublic))",
            ".addNetworkInterceptor(PublicGuard(rules.isPublic))", ".proxy(Proxy.NO_PROXY)", "rules.redirect(url, r.header(\"Location\"), hops)")
        assertHasNot(fetcher, "cookieJar", "Referer", ".cache(")
        val transport = flatCode(kt("helpers", "helpers/web/WebTransport.kt"))
        assertHas(transport, ".followRedirects(false)", "LanDns(dns)", "addNetworkInterceptor(LanGuard)")
        // No remote image in a reply: nothing loads an image from an address.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bAsyncImage\b|\bcoil\b|\bGlide\b|\bPicasso\b|BitmapFactory\.decodeStream\(.*openStream""")))
    }

    /**
     * 1.0, a search in your browser (core web Engines.kt and BrowserSearch.kt, BrowserActions.kt, the :voice process's
     * BrowserSearchTool): Robot hands the engine's address to the browser as a link and goes no further. No HTTP client,
     * socket, search client, web search's network module or key, no INTERNET check; the one intent is ACTION_VIEW with
     * CATEGORY_BROWSABLE, and nothing reads what the browser shows.
     */
    @Test fun aSearchInYourBrowserNeverReachesTheNetwork() {
        val path = ours.filterKeys { it in setOf("core/core/web/Engines.kt", "core/core/web/BrowserSearch.kt", "core/core/BrowserActions.kt", "app/assistant/BrowserSearchTool.kt") }
        assertEquals(4, path.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bSocket\b|okhttp|\bWebView\b|\bopenConnection\(|\bapp\.heyrobot\.helpers\b|""" +
            """\bSearchClient\b|\bWebTransport\b|\bSearchRequest\b|\bWebKeys\b|\bINTERNET\b|\bDownloadManager\b"""), path))
        val tool = flatCode(kt("app", "assistant/BrowserSearchTool.kt"))
        assertEquals(listOf("ACTION_VIEW"), Regex("""Intent\.(ACTION_\w+)""").findAll(tool.text).map { it.groupValues[1] }.toList())
        assertHas(tool, "addCategory(Intent.CATEGORY_BROWSABLE)")
        assertHasNot(tool, "putExtra", "setPackage")
        // Only ToolExecutor opens it (after the gate), and only the panel makes your own phrase's call.
        assertEquals(listOf("app/assistant/ToolExecutor.kt"), usersOf(Regex("""\bBrowserSearchTool\.(open|prepare)\(""")))
        assertEquals(listOf("app/assistant/PanelWebSearch.kt"), usersOf(Regex("""\bBrowserSearchTool\.directCall\(""")))
        // Not an online action: the panel runs it as a local one (no OnlineRun, no web search's one-shot).
        assertHas(flatCode(kt("core", "core/BrowserActions.kt")), "override val online get() = false")
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
        // A helper's API: only the helpers, which act on a SummaryJob (made by the card's Send) and check HelperGate first.
        assertEquals(listOf("app/assistant/conversation/Helpers.kt"), usersOf(Regex("""\bAnthropic(Session|Client)\b|\bHelper(TurnListener|Outcome)\b|\btranscriptSession\b"""), conv))
        val helpers = code(File(java("app"), "app/heyrobot/assistant/conversation/Helpers.kt"))
        assertEquals(2, Regex("""HelperGate\.allowed\(job\)""").findAll(helpers).count())
        // 0.6.0: never another helper's session (any company's API, your own server, Test connection), a key or Robot's
        // actions: HelperLink only for Claude's transcript session (and whether Claude's key is saved, for the card)...
        assertEquals(emptyList<String>(), usersOf(Regex("""\bHelperLink\.session\(|\bHelper(Session|Http|Probe|Keys|KeyFiles)\b|\bOpenAiSession\b|""" +
            """\bapp\.heyrobot\.helpers\.(?!(HelperTurnListener|HelperOutcome)\b)|\bHelperTools\b"""), conv))
        assertEquals(listOf("app/assistant/conversation/Helpers.kt", "app/assistant/conversation/SummaryActivity.kt"), usersOf(Regex("""\bHelperLink\b"""), conv))
        // (and whether Claude's key couldn't be read just now or was removed, for the summary's words), nothing else of it.
        val linkUses = conv.values.flatMap { f -> Regex("""\bHelperLink\.(\w+)""").findAll(code(f)).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(linkUses.toString(), setOf("transcriptSession", "done", "networkAllowed", "keySaved", "unavailable", "unreadable").containsAll(linkUses))
        // ...which is the chosen helper's (1.0.0: any helper with its API; Claude's before), always with its fixed transcript
        // prompt, no tools and no web search, whichever client it uses.
        val link = code(File(java("app"), "app/heyrobot/assistant/HelperLink.kt"))
        val ts = Flat(link).body("fun transcriptSession(ctx: Context, h: HelperConfig): HelperSession?")
        assertHas(ts, "AnthropicSession(held.anthropic!!, AnthropicModel.of(h.model), emptyList(), search = false, conversation = false, system = HelperPrompt.CLAUDE_TRANSCRIPT_SYSTEM)")
        assertTrue(ts.text, ts.matches(Regex(Regex.escape(Scan.flat("OpenAiSession(held.http!!, h.copy(search = false), emptyList(), conversation = false,")) + ".*?" +
            Regex.escape(Scan.flat("system = HelperPrompt.transcriptSystem(h.name))")))))
        assertEquals(2, ts.count(Regex("""(?<!transcript)Session\(""")))
        // The summary screen and the summaries' job are in the voice process (the main app opens the screen by name).
        assertEquals(emptyList<String>(), usersOf(Regex("""\bSummary(Jobs|Activity)\b|\b(ApiHelper|AppShareHelper)\b"""), ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }))
        // The clip reader reads what you picked; nothing in conversation mode writes audio.
        assertEquals(emptyList<String>(), usersOf(Regex("""openOutputStream|FileOutputStream|MediaMuxer|MediaRecorder|AudioRecord\b"""),
            conv.filterKeys { it.startsWith("app/assistant/") || it.startsWith("voice/") }))
        val service = code(kt("app", "assistant/conversation/ConversationService.kt"))
        assertTrue(service.contains("START_NOT_STICKY") && !Regex("""\bSTART_STICKY\b""").containsMatchIn(service))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bConversation(Service|Session)\b"""), ours.filterKeys { it.startsWith("app/boot/") }))
        // Deleting in the app always tells the voice process (so a live or recovering session can't write it back).
        val tx = flatCode(kt("app", "sys/Transcripts.kt"))
        assertHas(tx, "Cmd.send(ctx, Cmd.TRANSCRIPTS_DELETED", "s.deleteAll()", "s.delete(id)")
        assertEquals(emptyList<String>(), usersOf(Regex("""Transcripts\.store\([^)]*\)\s*\??\.(delete|deleteAll)\("""), ours.filterKeys { it.startsWith("app/") }))
        assertHas(flatCode(kt("app", "assistant/VoiceHub.kt")), "Cmd.TRANSCRIPTS_DELETED -> ConversationSession.transcriptsDeleted(")
        // The wake phrases sleep during a session unless the setting says otherwise, and come back after.
        assertHas(flatCode(kt("app", "assistant/conversation/ConversationSession.kt")), "VoiceHub.conversationWake(!s.convWakeDuring)", "VoiceHub.conversationWake(false)")
        // The main app opens the conversation screens by name only.
        val ui = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.assistant\b"""), ui))
        assertHas(flatText(kt("app", "sys/Transcripts.kt")), "\"app.heyrobot.assistant.conversation.ConversationActivity\"")
    }

    /**
     * 0.8, routines and the morning brief: nothing a routine holds or says goes online except the
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
            """\bHelper(Link|Session|Http)\b|\bAnthropic(Session|Client)\b|\bapp\.heyrobot\.helpers\b|\bokhttp3\b"""), routines))
        assertEquals(listOf("app/assistant/routines/RoutineRunner.kt"), usersOf(Regex("""\.briefWeather\(\)"""), routines))
        // Logs: codes and numbers only; never a routine's name, steps, words or a reminder.
        val leaky = Regex("""RLog\.[iw]\([^\n]*\b(name|text|lines|describe|reminder|title|said)\b""")
        assertEquals(emptyList<String>(), usersOf(leaky, routines))
        // (and in a call wrapped over several lines)
        assertEquals(emptyList<String>(), routines.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.[iw]\(""")).any { Regex("""\b(name|text|lines|describe|reminder|title|said)\b""").containsMatchIn(it) } }.keys.toList())
        // The only receiver another app can reach: Clock's ALARM_ALERT and ALARM_DONE, in the trigger hub. 1.0 (ipc-3): the
        // unlock comes from SystemUI, which isn't the system uid, so USER_PRESENT receivers are exported too (it is a
        // protected broadcast: nothing else can send it), all through ProtectedBroadcasts and its protected actions only.
        // 1.1, Phone lock phase B: :lock hears the same two Clock broadcasts for After the alarm, once, and nothing else.
        assertEquals(listOf("app/assistant/routines/RoutineHub.kt", "app/lock/LockClock.kt", "app/sys/ProtectedBroadcasts.kt"), usersOf(Regex("""\bRECEIVER_EXPORTED\b""")))
        val hub = flatText(kt("app", "assistant/routines/RoutineHub.kt"))
        assertEquals(1, hub.count(Regex("""RECEIVER_EXPORTED""")))
        val lockClock = flatText(kt("app", "lock/LockClock.kt"))
        assertEquals(1, lockClock.count(Regex("""RECEIVER_EXPORTED""")))
        assertHas(lockClock, "\"com.android.deskclock.ALARM_ALERT\"", "\"com.android.deskclock.ALARM_DONE\"", "AlarmCheck.admits(", "AlarmWatch.awaitsDone(")
        assertEquals(2, lockClock.count(Regex("""\baddAction\(""")))
        val prot = flatCode(kt("app", "sys/ProtectedBroadcasts.kt"))
        assertEquals(setOf(android.content.Intent.ACTION_USER_PRESENT, android.content.Intent.ACTION_SCREEN_ON, android.content.Intent.ACTION_SCREEN_OFF),
            app.heyrobot.sys.ProtectedBroadcasts.ACTIONS)
        assertEquals(1, prot.count(Regex("""RECEIVER_EXPORTED""")))
        val present = usersOf(Regex("""\bACTION_USER_PRESENT\b"""))
        // 1.1: Phone lock's service watches the unlock too (the cover shows again unless the last app is still allowed).
        // 1.4: and Robot Files' vault, which finishes what a crash cut short after each unlock (filesStayOnThePhone); F6, its
        // filing in :voice (FilingWork), which goes on with what waits in Inbox after each unlock (never before).
        assertEquals(listOf("app/assistant/FilingWork.kt", "app/assistant/car/CarModeController.kt", "app/assistant/conversation/ConversationActivity.kt",
            "app/assistant/routines/RoutineHub.kt", "app/lock/LockAccessibilityService.kt", "app/sys/FilesVault.kt", "app/sys/ProtectedBroadcasts.kt"), present)
        for (f in present) {
            val c = Flat(code(ours.getValue(f)))
            assertTrue(f, !c.text.contains("RECEIVER_NOT_EXPORTED") || c.calls(Regex("""\bregisterReceiver\(""")).none { it.contains("ACTION_USER_PRESENT") })
            if (f != "app/sys/ProtectedBroadcasts.kt") assertHas(c, "ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT")
        }
        assertHas(hub, "\"com.android.deskclock.ALARM_ALERT\"", "\"com.android.deskclock.ALARM_DONE\"")
        // One exact allow-while-idle alarm, never setAlarmClock (it would become Android's "next alarm").
        assertEquals(emptyList<String>(), usersOf(Regex("""\.setAlarmClock\(""")))
        // 1.1: Phone lock keeps its own one alarm in :lock, by the same rule (LockAlarms).
        assertEquals(listOf("app/lock/LockAlarmReceiver.kt", "app/sys/Assistant.kt", "app/sys/Routines.kt"), usersOf(Regex("""\.setExactAndAllowWhileIdle\(""")))
        // The main app and :voice reach :lock's receiver and windows by their class names, which must be the real ones.
        assertHas(flatText(kt("app", "lock/LockSignals.kt")), "\"app.heyrobot.lock.LockAlarmReceiver\"", "\"app.heyrobot.lock.LockCoverActivity\"",
            "\"app.heyrobot.lock.LockCodeActivity\"")
        for (n in listOf("LockAlarmReceiver", "LockCoverActivity", "LockCodeActivity")) assertTrue(n, File(java("app"), "app/heyrobot/lock/$n.kt").isFile)
        // The main app reaches :voice's receiver by its class name, which must be the real one.
        assertHas(flatText(kt("app", "sys/Routines.kt")), "\"app.heyrobot.assistant.routines.RoutineAlarmReceiver\"")
        assertTrue(File(java("app"), "app/heyrobot/assistant/routines/RoutineAlarmReceiver.kt").isFile)
        // The speaking service is never sticky and never started at boot.
        val svc = code(kt("app", "assistant/routines/RoutineSpeakingService.kt"))
        assertTrue(svc.contains("START_NOT_STICKY") && !Regex("""\bSTART_STICKY\b""").containsMatchIn(svc))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bRoutineSpeakingService\b"""), ours.filterKeys { it.startsWith("app/boot/") }))
        // Saving a routine by voice goes through its card (RoutineDraft.save after the card's answer), never straight to the file;
        // a routine from the screen's text needs the tap.
        val panel = flatCode(kt("app", "assistant/routines/PanelRoutines.kt"))
        assertEquals(1, panel.count("draft.save("))
        // The card's question, with "tap only" for a routine from content, is asked before the one save.
        val ask = panel.calls(Regex("""(?<![\w$])val yes=host\.ask\("""))
        assertTrue(ask.toString(), ask.any { it.contains("tapOnly=if(draft.fromContent)") })
        assertInOrder(panel, "val yes = host.ask(", "tapOnly = if (draft.fromContent)", "draft.save(")
        // 1.2 (every-android-phone, Step 6): a routine made to start after an alarm, on a phone without GrapheneOS Clock,
        // says so on its card before the yes, never silently.
        assertInOrder(panel, "ClockNeed.unmet(draft.routine.trigger", "host.append(replyId, RoutineStrings.clockMissing()",
            "host.say(RoutineStrings.clockMissing()", "val yes = host.ask(")
    }

    /**
     * 1.1, Skills (docs/plans/skills-1.1-plan.md §12): a skill's steps run in :voice through the panel's own ports, never a
     * network, a model or a helper of their own; SkillRunner is reached only from the panel's skills code; the continuation's
     * one-time token lives only in assistant/skills/; the screens never name the runner; a skill is saved by voice only after
     * its card's answer, and deleted only after a tap; the logs carry codes, never a skill's name, values or steps.
     */
    @Test fun skillsStayOnThePhone() {
        val skills = ours.filterKeys { it.startsWith("app/assistant/skills/") || it.startsWith("core/core/skills/") || it == "app/sys/Skills.kt" ||
            it == "app/ui/settings/SkillsSettings.kt" }
        assertTrue(skills.keys.toString(), skills.size >= 14)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bOpenMeteoClient\b|\bNominatimClient\b|\bBrain(Client|Keeper|Link|OneShots)\b|""" +
            """\bHelper(Link|Session|Http)\b|\bAnthropic(Session|Client)\b|\bapp\.heyrobot\.helpers\b|\bokhttp3\b"""), skills))
        // SkillRunner: only the panel's skills code (and PanelController, should it ever need it).
        val runner = usersOf(Regex("""\bSkillRunner\b""")) - "app/assistant/skills/SkillRunner.kt"
        assertTrue(runner.toString(), runner.isNotEmpty() && runner.all { it in setOf("app/assistant/skills/PanelSkills.kt", "app/assistant/PanelController.kt") })
        // The token: made, kept and checked only in assistant/skills/ (sys/Skills.kt only names the keys).
        val token = usersOf(Regex("""\bSkillContinue\b|\bSkillIntents\.(ARG_SKILL|EXTRA_TOKEN)\b"""))
        assertTrue(token.toString(), token.isNotEmpty() && token.all { it.startsWith("app/assistant/skills/") })
        // The screens never name the runner or the panel's skills.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bSkillRunner\b|\bPanelSkills\b|\bSkillContinue\b"""), ours.filterKeys { it.startsWith("app/ui/") }))
        // The main app reaches the receiver by its class name, which must be the real one.
        assertHas(flatText(kt("app", "sys/Skills.kt")), "\"app.heyrobot.assistant.skills.SkillContinueReceiver\"")
        assertTrue(File(java("app"), "app/heyrobot/assistant/skills/SkillContinueReceiver.kt").isFile)
        // Logs: codes and numbers only.
        val leaky = Regex("""\b(name|names|text|values|value|steps|said|params|args|default)\b""")
        assertEquals(emptyList<String>(), skills.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.[iw]\(""")).any { leaky.containsMatchIn(it) } }.keys.toList())
        // Saved by voice only after the card's answer; deleted only after a tap on its card.
        val panel = flatCode(kt("app", "assistant/skills/PanelSkills.kt"))
        assertEquals(1, panel.count("store().save("))
        assertInOrder(panel, "val pick = host.pick(", "store().save(skill)")
        assertEquals(1, panel.count("store().delete("))
        assertInOrder(panel, "val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, null, body = body, confirm = UiStrings.delete()",
            "tapOnly = SkillStrings.tapDelete(", "store().delete(s.id)")
        // Each step goes through a TurnRunner with the conversation's taint (core SkillRun), never straight to the executor.
        val run = flatCode(kt("core", "core/skills/SkillRun.kt"))
        assertHas(run, "TurnRunner(StepCall(raw), a, v, confirmations, taint, asked = SkillPolicy.asked(tool, named), messages = messages")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bToolExecutor\b"""), skills))
        // Phase B: a model's skill call reaches the panel's skills code only through TurnActions.runSkill (PanelController's
        // port), and runs through the same SkillRun as a skill you named; a pick your words didn't name always asks first (D18).
        assertEquals(listOf("app/assistant/PanelController.kt"), usersOf(Regex("""\brunForModel\b""")) - "app/assistant/skills/PanelSkills.kt")
        assertHas(flatCode(kt("app", "assistant/skills/SkillRunner.kt")), "SkillRun(s, values, named, ports.actions, ports.view, ports.confirmations")
        assertHas(flatCode(kt("core", "core/skills/SkillRisk.kt")), "if (!named) return ModelGo.Card(tapOnly = tap || tier == SkillTier.RISKY, didYouMean = true)")
        // A helper is never offered skills: its skill call is refused before any port.
        assertHas(flatCode(kt("core", "core/skills/SkillCalls.kt")), "if (remote) return SkillCall.Refused(ToolStrings.notOffered(), SkillWarnCode.NOT_OFFERED)")
        // :brain describes the specs BrainMsg.Generate carries; its logs count skills, never name one (a tool's name is your skill's).
        val brain = File(java("brain"), "app/heyrobot/brain").listFiles()!!.filter { it.name.endsWith(".kt") }
        assertTrue(brain.size >= 5)
        val named = Regex("""SkillToolSpec|openApiJson|\bspec\b|skills\.map|req\.skills|\.name\b""")
        assertEquals(emptyList<String>(), brain.flatMap { f -> Flat(code(f)).calls(Regex("""\bRLog\.[iw]\(""")).filter { named.containsMatchIn(it) } })
    }

    /**
     * 0.8 review: an unattended routine's parts are decided by where Robot's voice goes now (getAudioDevicesForAttributes)
     * and car mode's car with its "Read aloud when others may hear" (core RoutineOutputs).
     */
    @Test fun anUnattendedRoutineSpeaksByItsActualRoute() {
        val voice = flatCode(kt("app", "assistant/routines/RoutineVoice.kt"))
        assertHas(voice, "RoutineOutputs.decide(part, s.routine.output, phone(), route(), s.everything)")
        assertHas(voice.body("private fun route(): RoutineRoute"), "am.getAudioDevicesForAttributes(attrs)", "CarModeController.carSeen()", "HandsFreeHub.car.settings.readAloud")
    }

    /**
     * 0.8 review: Clock's ALARM_ALERT counts only when Clock's own next alarm, read before it (now and at each
     * ACTION_NEXT_ALARM_CLOCK_CHANGED), vouches for it (core AlarmCheck), checked before AlarmWatch sees it.
     */
    @Test fun onlyClocksOwnAlarmReachesTheRoutines() {
        val hub = flatCode(kt("app", "assistant/routines/RoutineHub.kt"))
        val onClock = hub.body("private fun onClock(")
        assertInOrder(onClock, "if (!AlarmCheck.admits(seenAlarms, signal)) {", "step(signal)")
        assertEquals(1, hub.count("step(AlarmSignal.Alert(") + hub.count("step(signal)"))
        assertHas(hub, "IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)", "VoiceLog.add(VoiceEvent.ROUTINE_SKIPPED, SkipWhy.NOT_CLOCKS_ALARM, TriggerKind.ALARM, n)")
        // 1.0 (ipc-4): logged once a minute with a count, and a DONE with nothing ringing is dropped before any work.
        assertHas(onClock, "unverified.admit(now)?.let { n ->")
        assertInOrder(onClock, "AlarmWatch.awaitsDone(", "worker.postDelayed(")
        assertInOrder(onClock, "if (donePending ||", "worker.postDelayed(")
    }

    /** 0.8 review: a new message is announced only when Do Not Disturb's answer was read and lets it through (fails closed). */
    @Test fun doNotDisturbFailsClosedForAnnouncements() {
        val dnd = flatCode(kt("app", "people/MessageTap.kt")).body("private fun dndAllows(")
        assertHas(dnd, "app.heyrobot.core.messages.MessageDnd.allows(found, found == true && r.matchesInterruptionFilter())", "catch (_: Throwable) { false")
        // Unread or unknown is no: MessageDnd fails closed (core MessageHandsFreeTest too).
        assertFalse(app.heyrobot.core.messages.MessageDnd.allows(null, false))
        assertFalse(app.heyrobot.core.messages.MessageDnd.allows(false, false))
        assertHas(flatCode(kt("app", "assistant/VoiceHub.kt")), "i.getBooleanExtra(Cmd.EXTRA_DND, false)")
    }

    /** 0.8 review: the headset whose button sent VOICE_COMMAND (EXTRA_DEVICE) is the one told Robot listens, else the first. */
    @Test fun theHeadsetThatPressedIsTheOneAnswered() {
        assertHas(flatCode(kt("app", "assistant/handsfree/HandsFreeHub.kt")), "if (d.ackBvra) ackVoiceRecognition(ctx, buttonDevice(intent))",
            "intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)",
            "val target = from?.takeIf { f -> connected.any { it.address == f.address } } ?: connected.firstOrNull()")
    }

    /**
     * 0.8, Settings › Voice › Transcription check › Microphone: Headset records through the headset as a session listens
     * (its call route and pinned input, core CheckMics), only once its input is the one capturing, never over a session's
     * or a call's audio, and lets the route go whenever the recording stops.
     */
    @Test fun theTranscriptionCheckRecordsTheHeadsetAsASessionWould() {
        val check = flatCode(kt("app", "assistant/conversation/TranscriptionCheckActivity.kt"))
        val take = check.body("private fun takeHeadset(")
        assertInOrder(take, "if (HandsFreeHub.session != null)", "SessionRoute.take(this, routeOwner, out)")
        assertInOrder(take, "if (HandsFreeHub.callActive())", "SessionRoute.take(this, routeOwner, out)")
        assertHas(take, "CaptureRoute.set(plan.capture == CaptureSource.VOICE_COMMUNICATION, headsetInput(plan))")
        assertHas(check.body("private fun releaseHeadset("), "SessionRoute.release(this, routeOwner)", "CaptureRoute.clear()")
        assertHas(check.body("private fun stopRecording("), "releaseHeadset()")
        assertHas(check, "CheckMics.route(AudioDevices.all(this), HandsFreeFiles.store(this).devices(), CarModeController.carSeen())")
        // The headset's frames only once its input captures: the sink is added after the route check, never before it.
        assertHas(check.body("private fun startRecording("), "if (plan == null) source?.addSink(sink) else waitForHeadset(plan)")
        assertInOrder(check.body("private fun waitForHeadset("), "if (routedTo(plan)) {", "source?.addSink(sink)")
        assertEquals(1, check.count("SessionRoute.take("))
    }

    /**
     * A routine or the brief asked for in a headset or car session speaks through OutputGuard like any answer: the brief's
     * parts in the panel (RoutineVoice's panel surface) line by line with the part's own personal mark, and a routine's
     * own sentences and your brief reminders as personal (never on headphones Robot doesn't know are private).
     */
    @Test fun routineSpeechInASessionPassesOutputGuard() {
        val voice = flatCode(kt("app", "assistant/routines/RoutineVoice.kt"))
        val play = voice.body("private fun playCurrent()")
        assertHas(play, "val lines = if (s.surface == Surface.PANEL) part.lines.filter { l -> PanelHandsFree.allow(SpeechText.clean(l)) { part.personal } } else part.lines",
            "lines.forEach(::say)")
        assertHasNot(play, "part.lines.forEach(::say)")
        val panel = flatCode(kt("app", "assistant/routines/PanelRoutines.kt"))
        assertHas(panel, "is RoutineStep.Say -> { host.append(replyId, step.text); host.say(step.text, personal = true) }",
            "host.append(replyId, line.shown(bl)) host.say(line.said(bl), personal = true)")
        val pc = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(pc, "override fun say(sentence: String, personal: Boolean) { if (_state.value.speak) speakSentence(sentence, personal = personal) }",
            "if (!PanelHandsFree.allow(clean) { personal || personalNow(_state.value.turns.lastOrNull()?.id).isNotEmpty() }) return")
        // "What's next" says your calendar: personal too.
        assertHas(flatCode(kt("app", "assistant/handsfree/PanelHandsFree.kt")),
            "val line = WhatsNext.line(events, now, lang) host.quoted(replyId) host.text(replyId, line) host.say(line, personal = true)")
        // A Ready card's tap on the lock screen keeps a card whose step needs the unlock (checked before it is used up).
        assertInOrder(panel.body("private suspend fun ready("),
            "ReadyCards.keptOnTap(c, RoutineGate.decide(c.step, RunOrigin.READY_TAP, host.locked, routine, VoiceHub.settings, store().settings()))",
            "store().editReady { list -> list.filterNot { it.key == c.key } }")
    }

    /**
     * 0.5.0, phase 5.3 (docs/conversation-mode.md 3, 8): the speaker model is downloaded by the main app only, like every
     * model; the voiceprint is decrypted in the :voice process only, seeds the session and nothing else; nothing about
     * speakers goes online or into a log but numbers.
     */
    @Test fun speakerLabelsAndTheVoiceprintStayOnThePhone() {
        // Downloads: DownloadManager in the main app's model stores (the fast engine's too: modelsDownloadOnlyFromTheMainApp),
        // and those only from the main app's screens.
        val dl = usersOf(Regex("""\bVoiceModelStore\b"""))
        assertTrue(dl.toString(), dl.isNotEmpty() && dl.all { it.startsWith("app/ui/") || it.startsWith("app/sys/") })
        // The voiceprint: decrypted in one place (the session's start), encrypted and read only by the :voice code.
        assertEquals(listOf("app/assistant/conversation/ConversationSession.kt"), usersOf(Regex("""\bVoiceprints\.read\(""")))
        // (1.1: and BackupSecrets, a voiceprint restored from your own backup where this phone has none.)
        assertEquals(listOf("app/assistant/backup/BackupSecrets.kt", "app/assistant/conversation/VoiceprintActivity.kt"), usersOf(Regex("""\bVoiceprints\.save\(""")))
        // 1.1: the voiceprint may go in a backup, off by default; it
        // is read for one only by BackupSecrets, in :voice, straight into the backup's encryption.
        assertEquals(listOf("app/assistant/backup/BackupSecrets.kt"), usersOf(Regex("""\bVoiceprints\.forBackup\(""")))
        assertEquals(listOf("app/assistant/conversation/Voiceprints.kt"), usersOf(Regex("""\bVoiceprintCodec\.(open|seal)\(""")))
        val main = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bVoiceprints\b|\bVoiceprintCodec\.(open|seal|decode)\b"""), main))
        // The only ciphers in those files are KeystoreSecret's (the updater's token in the main process, the helpers' keys in
        // :voice), 0.7 memory's vault (app/sys/MemoryVault.kt, memoryStaysOnThePhone) and 1.4 Robot Files' key, which wraps
        // only each sealed stream's key (app/sys/FilesVault.kt, filesStayOnThePhone); none of them sees a voiceprint.
        assertEquals(listOf("app/sys/FilesVault.kt", "app/sys/KeystoreSecret.kt", "app/sys/MemoryVault.kt"), usersOf(Regex("""\bCipher\b"""), main))
        for (f in listOf("FilesVault.kt", "KeystoreSecret.kt", "MemoryVault.kt")) {
            assertTrue(f, !Regex("""\bVoiceprint""").containsMatchIn(code(File(java("app"), "app/heyrobot/sys/$f"))))
        }
        // Its Keystore key is its own, never a helper's (0.6.0: Claude's key is a helper's key, under its old alias).
        val keys = code(kt("app", "assistant/conversation/Voiceprints.kt"))
        assertTrue(Regex("""\bVoiceprintCodec\.KEY_ALIAS\b""").containsMatchIn(keys) && !Regex("""\bHelperKey(s|Files)\b|\bClaudeKey""").containsMatchIn(keys))
        val alias = app.heyrobot.core.VoiceprintCodec.KEY_ALIAS
        assertTrue(alias != app.heyrobot.sys.HelperKeyFiles.CLAUDE_ALIAS && !alias.startsWith(app.heyrobot.sys.HelperKeyFiles.alias("x").removeSuffix("x")))
        // Never in settings: the setting is a flag.
        val settings = code(kt("core", "core/Settings.kt"))
        assertTrue(Flat(settings).has("val meVoiceprint: Boolean"))
        assertTrue(!Regex("""\bVoiceprint\b|FloatArray""").containsMatchIn(settings))
        // Nothing about speakers goes online, and the logs get numbers only: no name, label, text or voice in an RLog call.
        val spk = ours.filterKeys { it in setOf("core/core/Speakers.kt", "core/core/Voiceprint.kt", "core/core/SpeakerClusterer.kt", "voice/voice/SpeakerEmbedder.kt",
            "voice/voice/ConversationEngine.kt", "app/assistant/conversation/Voiceprints.kt", "app/assistant/conversation/VoiceprintActivity.kt",
            "app/assistant/conversation/ConversationSession.kt", "app/sys/Speakers.kt", "app/ui/SpeakerUi.kt") }
        assertEquals(10, spk.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bINTERNET\b"""), spk))
        val leaky = Regex("""RLog\.[iw]\([^\n]*\b(name|names|label|speaker|vector|embedding|voice|voices|mine|text|heard)\b""")
        assertEquals(emptyList<String>(), usersOf(leaky, spk))
        assertEquals(emptyList<String>(), spk.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.[iw]\(""")).any { Regex("""\b(name|names|label|speaker|vector|embedding|voice|voices|mine|text|heard)\b""").containsMatchIn(it) } }.keys.toList())
        // The speaker model loads with sherpa's debug output off, like every model.
        assertHas(flatCode(kt("voice", "voice/SpeakerEmbedder.kt")), "debug = false")
    }

    /**
     * 0.7 memory: the vault (a Keystore AES-GCM key that needs the phone unlocked) lives in app/sys for
     * both app processes, in files/memory, never backed up; it is opened only by the panel (MemoryPanel, :voice) and
     * Settings › Memory (main). What Robot remembers reaches a model only inside the panel's chat prompt (PanelController's
     * runTurn, through :brain, and llama.cpp's :llama for your own file), never a one-shot, a translation, a summary, :mt,
     * the network or a log; a helper only through its card (core HelperTrust), and memory's tools are never a helper's.
     */
    @Test fun memoryStaysOnThePhone() {
        val appFiles = ours.filterKeys { it.startsWith("app/") }
        // The vault: the Keystore's AES-GCM key, files/memory; Delete all and Start over are the only ways its file goes. Beside
        // it, the main app's only other Cipher is 0.6.0's KeystoreSecret (helpers' keys and the updater's token), which never
        // sees memory (the main app's Cipher users: speakerLabelsAndTheVoiceprintStayOnThePhone).
        assertTrue(!Regex("""\bMemory""").containsMatchIn(code(File(java("app"), "app/heyrobot/sys/KeystoreSecret.kt"))))
        val vault = flatText(kt("app", "sys/MemoryVault.kt"))
        assertHas(vault, "\"AndroidKeyStore\"", "\"AES/GCM/NoPadding\"", ".setUnlockedDeviceRequired(alias == MemoryKeyAliases.UNLOCKED)", "File(ctx.filesDir, \"memory\")",
            "writeAtomicBytes(file, out)", "if (load != MemoryLoad.Unreadable && load != MemoryLoad.KeyGone) return@locked false")
        // Two deletes only: Delete all's, and Start over's older memory.enc.bad (the file itself is renamed, never deleted there).
        val vaultCode = flatCode(kt("app", "sys/MemoryVault.kt"))
        assertEquals(2, vaultCode.count(Regex("""\.delete\(\)""")))
        assertHas(vaultCode, "listOf(MemoryEnvelope.FILE, BAD, INDEX).map { File(dir, it) }.all { !it.exists() || it.delete() }", "bad.delete() file.renameTo(bad)")
        // Opened by the panel and Settings › Memory only; 1.1: and by a backup (BackupSources reads it) and a restore
        // (RestoreWriter writes it through the vault's update, sealed with this phone's key), both in :voice.
        // (And the main app's part of a restore, RestoreApply, seals it again with the key the restored memory.json names.)
        // 1.4 F8, deliberately: Robot Files' filing rules (files-1.4-plan.md §4.5), through sys/FilingMemory alone, in both
        // processes: :voice reads them for filing and saves one from your words; the Files screens save one from your move or tags.
        assertEquals(listOf("app/assistant/MemoryPanel.kt", "app/assistant/backup/BackupSources.kt", "app/assistant/backup/RestoreWriter.kt",
            "app/sys/FilingMemory.kt", "app/sys/RestoreApply.kt", "app/ui/settings/MemorySettings.kt"),
            usersOf(Regex("""\bMemoryFiles\.vault\(""")))
        // What it does with memory: reads the rules (FilingRules.of), adds one (MemoryEdits.addRule), undoes it, follows a
        // drawer's new name, counts them.
        val filingMemory = flatCode(kt("app", "sys/FilingMemory.kt"))
        assertEquals(5, filingMemory.count(Regex("""\bMemoryFiles\.vault\(""")))
        assertHas(filingMemory, "FilingRules.of(f.items)", "FilingRules.renamed(f, old, new)", "MemoryEdits.addRule(f, item)", "MemoryEdits.undoRule(f, saved.item.id, saved.replaced)",
            "if (!on(ctx)) return emptyList()", "if (!on(ctx)) return Saved.Off")
        assertEquals(0, filingMemory.count(Regex("""\bRLog\.|\bVoiceLog\.""")))
        // A rule never reaches a prompt, a reply, a helper or the model's forget: every question's path skips them.
        val recall = flatCode(kt("core", "core/memory/MemoryRecall.kt"))
        assertEquals(4, recall.count("MemoryEdits.forQuestions("))
        assertEquals(0, recall.count("MemoryEdits.live("))
        val packing = flatCode(kt("core", "core/memory/PromptPacking.kt"))
        assertHas(packing.body("fun pinnedLine("), "item.filing != null) continue")
        assertHas(packing.body("fun notesFor("), "it.filing == null")
        assertHas(packing.body("fun pack("), "item.filing != null -> false")
        assertHas(flatCode(kt("app", "assistant/MemoryPanel.kt")).body("fun forWebCheck("), "it.filing == null")
        // Memory's classes stay in the app's two processes: never :brain, :mt, :voice's engines or the helpers' clients.
        val memory = Regex("""\bapp\.heyrobot\.core\.memory\b|\bMemory(Panel|Files|SettingsStore|Turn|Answer)\b|\bFileMemoryVault\b""")
        assertEquals(emptyList<String>(), usersOf(memory, ours.filterKeys { it.startsWith("brain/") || it.startsWith("mt/") || it.startsWith("voice/") || it.startsWith("helpers/") }))
        // 1.0: the setup guide's Memory page writes memory.json's two switches (the key changed first, through PhoneMemoryStore);
        // Settings › Memory › Import's preview shows a file's items (MemoryImportScreen.kt), written by Settings › Memory's store.
        // 1.1: a backup reads Memory in :voice (BackupSources), straight into the backup's encryption.
        // (1.1: the panel's memory commands are PanelMemoryCommand's, the web search's check PanelWebSearch's, a helper joining
        // the conversation PanelHelperTurn's and Robot checking with a helper PanelEscalation's, which read MemoryPanel.toTrusted.)
        // 1.4 F8: a filing rule's value (MemoryFiling) in the panel's files words (PanelFiles) and the Files screens (FilesPort,
        // FilesScreen); only sys/FilingMemory opens memory for them. Settings › Files reads whether Memory is on (its rules line).
        assertEquals(listOf("app/assistant/MemoryPanel.kt", "app/assistant/PanelController.kt", "app/assistant/PanelEscalation.kt", "app/assistant/PanelFiles.kt",
            "app/assistant/PanelHelperTurn.kt",
            "app/assistant/PanelMemoryCommand.kt", "app/assistant/PanelWebSearch.kt",
            "app/assistant/backup/BackupSources.kt",
            "app/assistant/backup/RestoreWriter.kt", "app/sys/FilingMemory.kt", "app/sys/MemoryVault.kt", "app/sys/RestoreApply.kt",
            "app/ui/files/FilesPort.kt", "app/ui/files/FilesScreen.kt", "app/ui/guide/GuideStores.kt", "app/ui/settings/FilesSettings.kt",
            "app/ui/settings/MemoryImportScreen.kt", "app/ui/settings/MemorySettings.kt", "app/ui/settings/SettingsRoot.kt"), usersOf(memory, appFiles))
        // Into a model's prompt only from the panel's chat turn: once, in PanelController; never the one-shot client, conversation
        // mode (translations, summaries) or a helper's request.
        assertEquals(listOf("app/assistant/PanelController.kt"), usersOf(Regex("""\bMemoryPanel\.forTurn\(""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bMemoryPanel\b|\bPromptPacking\b|\bMemoryRecall\b"""),
            ours.filterKeys { it.startsWith("app/assistant/conversation/") || it in setOf("app/assistant/OneShots.kt", "app/assistant/HelperLink.kt", "app/assistant/BrainLink.kt") }))
        val panel = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(panel, "history = history.toPromptTurns(mem.notes)", "pinned = mem.packed.systemLine")
        // History keeps the reply through MemoryPanel.forHistory (all the turn's ids, the pinned ones too, and a secret answer's
        // line); the question keeps only its retrieved ids (1.1).
        // 1.4 F9, deliberately: wrapped by PanelFileAnswer.forHistory, which keeps a private file's answer as its placeholder too.
        assertHas(panel, "chat += PanelFileAnswer.forHistory(MemoryPanel.forHistory(ChatTurn(")
        // A private item never stays in the live conversation: it closes after that answer (rebuilt with History's line).
        assertInOrder(panel, "MemoryPanel.mark(replyId, mem.recall.ids, secret = mem.recall.secret)", "if (mem.recall.secret) { closeConvAfterTurn = true")
        // A helper: memory only through its card (trusted helpers too, unless Settings › Memory says otherwise), never its tools.
        // (1.1: a helper joining is PanelHelperTurn's and Robot checking with a helper PanelEscalation's: the four are counted over
        // exactly PanelController, PanelHelperTurn and PanelEscalation.)
        val trustedMemory = Regex("""HelperTrust\.(conversation|joinWithoutCard)\([^)]*MemoryPanel\.toTrusted\(\)\)""")
        assertEquals(4, panel.count(trustedMemory) + flatCode(kt("app", "assistant/PanelHelperTurn.kt")).count(trustedMemory) +
            flatCode(kt("app", "assistant/PanelEscalation.kt")).count(trustedMemory))
        // A helper's turn asks the lock screen, then the Network toggle, before a session is made, in both of its ways in
        // (Robot checking by itself, and a question for the helper); the port's lock, network and session are the real ones.
        val helperTurn = flatCode(kt("app", "assistant/PanelHelperTurn.kt"))
        assertEquals(2, helperTurn.count("if (port.locked() && (!h.locked || port.someoneElse()))"))
        assertInOrder(helperTurn.body("suspend fun escalate("), "if (port.locked() && (!h.locked || port.someoneElse()))", "if (!port.networkAllowed())",
            "port.session(s, h, conversation = false)")
        assertInOrder(helperTurn.body("suspend fun run("), "if (port.locked() && (!h.locked || port.someoneElse()))", "if (!port.networkAllowed())",
            "if (!askToShare(", "port.session(s, h, conversation = true)")
        assertHas(panel, "override fun locked() = Assistant.locked(VoiceHub.app)", "override fun someoneElse() = PhoneLockNow.someoneElse(VoiceHub.app)",
            "override fun phoneLockOn() = PhoneLockNow.on(VoiceHub.app)", "override fun networkAllowed() = HelperLink.networkAllowed(VoiceHub.app)",
            "override fun session(s: RobotSettings, h: HelperConfig, conversation: Boolean) = HelperLink.session(VoiceHub.app, s, h, conversation)")
        // An offer's tap goes through the panel's one turn slot and the same helper's turn; its app through the panel's tools.
        assertHas(panel, "override fun startTurn(replyId: String, block: suspend () -> Unit) { this@PanelController.startTurn(replyId, block) }",
            "fallBack: Boolean): Boolean = helperTurn.escalate(replyId, question, blocks, voiceAsked, reuseBubble, h, fallBack)",
            "override fun execute(action: Action): Executed = executor().run(action)")
        // Escalation reads the budget, "not Jarvis" and the conversation's personal content from PanelController itself, and
        // its offers are PanelOffers'.
        assertHas(panel, "override suspend fun budgetReached(s: RobotSettings, h: HelperConfig?) = HelperLink.budgetReached(VoiceHub.app, s, h)",
            "override val helperDeclined get() = this@PanelController.helperDeclined", "override fun personalNow(replyId: String?) = this@PanelController.personalNow(replyId)",
            "override fun offer(replyId: String, o: PanelOffers.Pending) = offers.show(replyId, o)")
        assertTrue(app.heyrobot.core.ToolName.REMEMBER !in app.heyrobot.core.HelperTools.OFFERED && app.heyrobot.core.ToolName.FORGET !in app.heyrobot.core.HelperTools.OFFERED)
        // No network, and the logs get codes and counts: no item, text, query or question in an RLog call.
        val mem = ours.filterKeys { it.startsWith("core/core/memory/") || it in setOf("app/assistant/MemoryPanel.kt", "app/sys/MemoryVault.kt",
            "app/ui/settings/MemorySettings.kt", "app/ui/settings/MemoryImportScreen.kt", "core/core/StrictJson.kt") }
        assertTrue(mem.size >= 15)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|okhttp|\bINTERNET\b|DownloadManager"""), mem))
        assertEquals(emptyList<String>(), usersOf(Regex("""RLog\.[iw]\([^\n]*\b(text|item|items|query|note|notes|words|said|question|about|file)\b"""), mem))
        assertEquals(emptyList<String>(), mem.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.[iw]\(""")).any { Regex("""\b(text|item|items|query|note|notes|words|said|question|about|file)\b""").containsMatchIn(it) } }.keys.toList())
        assertEquals(emptyList<String>(), usersOf(Regex("""VoiceLog\.add\([^\n]*\.(text|query|about)\b"""), mem))
        assertEquals(emptyList<String>(), mem.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bVoiceLog\.add\(""")).any { Regex("""\.(text|query|about)\b""").containsMatchIn(it) } }.keys.toList())
        // Never backed up or carried to another phone.
        val application = elements(xml(File(main("app"), "AndroidManifest.xml")), "application").single()
        assertEquals("false", application.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ns, "dataExtractionRules"))
        // Files (where memory is) excluded from both the cloud backup and the device-to-device transfer.
        val rules = xml(File(main("app"), "res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer"))
            assertTrue(section, elements(rules, section).single().let { sec -> elements(sec, "exclude").any { it.getAttribute("domain") == "file" } })
        // Its settings are memory.json's, never settings.json's (whose schema stays 13 for the other 0.7 branch).
        assertEquals(13, app.heyrobot.core.RobotSettings.SCHEMA)
        assertTrue(!code(kt("core", "core/Settings.kt")).contains("memory"))
    }

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2, §5.3, §5.5; F2): the vault in app/sys/FilesVault.kt for the main app and :voice
     * only, on files/files (credential-encrypted storage, never in Android's backup: memoryStaysOnThePhone's rules). Its one
     * Keystore key, robot_files_v1, is named and made only there: AES-256-GCM that needs the phone unlocked, randomized,
     * TEE (not StrongBox, no user authentication), made only when the Keystore has no such alias, deleted only by Delete
     * them all. Core's store and the vault go nowhere online, log codes and counts only, and write nothing of a file in
     * the clear: a file's bytes reach the disk only sealed (FileStore's sealed streams); the other writes are ids (the
     * delete journal), zeros over a deleted file's key, and files_settings.json. While the phone is locked nothing is
     * read, not even the index kept in RAM, which goes at every screen-off; nothing about files is reachable from the
     * lock screen; Settings › Files is FLAG_SECURE. Its settings are never one of the backup's Settings files.
     */
    /**
     * 1.4 F10 (files-1.4-plan.md §7.2, D11): nothing about Robot Files writes the calendar. A reminder from a file is a brief
     * reminder kept in the file's own entry, or add_event's card, which the panel's Add to calendar runs with
     * AskWhy.FROM_CONTENT so it shows whatever Settings › Actions and Safety cards say and only a tap answers it; the Files
     * screen hands the calendar app's own editor the date, and you save it there. The brief's files part is read only
     * while the phone is unlocked.
     */
    @Test fun filesNeverWriteTheCalendar() {
        assertHas(flatCode(kt("app", "assistant/PanelController.kt")).body("private fun filesCalendar("),
            "if (Assistant.locked(VoiceHub.app) || app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app)) return notify(")
        // Calendars.insert (the provider write) has one caller: add_event's ToolExecutor, after its card.
        assertEquals(listOf("app/assistant/ToolExecutor.kt"), usersOf(Regex("""\bCalendars\.insert\(""")))
        // Robot Files' code (core's and the app's) names no calendar write at all.
        val files = ours.filterKeys { it.startsWith("core/core/files/") || it in setOf("app/assistant/PanelFiles.kt", "app/assistant/FilingWork.kt",
            "app/ui/files/FilesScreen.kt", "app/ui/settings/FilesSettings.kt") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bCalendars\b|CalendarContract|ContentResolver|\bToolName\.ADD_EVENT\b"""), files))
        // The Files screen's port opens the calendar app's editor (ACTION_INSERT: nothing is written until you save there).
        val port = flatCode(kt("app", "ui/files/FilesPort.kt"))
        assertHas(port.body("override fun calendar("), "Calendars.insertIntent(title, plan)")
        assertEquals(0, port.count("Calendars.insert("))
        // The panel's Add to calendar: add_event as a call of its own, every card from content, a tap only.
        val pc = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(pc.body("private fun filesCalendar("), "PanelFiles.event(VoiceHub.app, handOff, turnLangs)", "RawToolCall(ToolName.ADD_EVENT.id,",
            "TurnRunner(DirectCall(raw), FromAFile,")
        // 1.1's dated-reminder alert on the event (AskWhy.REMINDER, which a yes alone may answer); FromAFile's FROM_CONTENT keeps the card tap only.
        assertHas(flatText(kt("app", "assistant/PanelController.kt")).body("private fun filesCalendar("), "\"when\" to ask.day, \"alert\" to true)")
        assertHas(pc.body("private object FromAFile"), "is Prepared.Ready -> p.copy(why = p.why + AskWhy.FROM_CONTENT)")
        // The brief reads the files part only when unlocked, with no phone lock, and says it only unlocked (core BriefPrivacy).
        val brief = flatCode(kt("app", "assistant/routines/RoutineRunner.kt"))
        assertHas(brief.body("fun files(ctx: Context, now: ZonedDateTime)"), "if (!filesWanted(ctx) || Assistant.keyguard(ctx) || app.heyrobot.sys.RobotFiles.locked(ctx)) null")
        assertHas(flatCode(kt("core", "core/RoutineGate.kt")).body("fun plan(all: List<BriefItem>"), "val items = if (locked) all.filter { it != BriefItem.FILES } else all")
        // A brief that ran locked leaves only the files part's place (no words), read once the phone is unlocked, else dropped.
        assertHas(brief.body("fun filesNow(ctx: Context, lang: Lang): List<SpokenPart>"), "val f = files(ctx, now) ?: return emptyList()")
        assertHas(brief, "say(before.flatMap { if (it.item == BriefItem.FILES && it.lines.isEmpty()) BriefSource.filesNow(ctx, briefLang) else listOf(it) })",
            "val FILES_LATER = SpokenPart(emptyList(), true, StepCode.BRIEF, BriefItem.FILES)",
            // Only beside parts already held for the unlock: a brief that held nothing never waits for the files part alone.
            "if (!locked || held.isEmpty() || !BriefSource.filesWanted(ctx)) return (greeting + rest) to held")
    }

    @Test fun filesStayOnThePhone() {
        // 1.4 F5: getting files in (the intake, the share target, scanning, the panel's "file this") is in this scope too.
        // 1.4 F6: filing (FilingWork, its notifications) is in it too.
        val files = ours.filterKeys { it.startsWith("core/core/files/") || it.startsWith("app/ui/files/") || it in setOf("app/sys/FilesVault.kt",
            "app/ui/settings/FilesSettings.kt", "app/sys/FilesIntake.kt", "app/sys/ScanProvider.kt", "app/assistant/PanelFiles.kt", "app/assistant/FilingWork.kt",
            "app/assistant/FilingNotices.kt", "app/sys/FilesOut.kt", "app/sys/FilingMemory.kt",
            // F12: a tax pack or an expense report copied to the folder you pick (sys/FilesExport).
            "app/sys/FilesExport.kt") }
        assertTrue(files.keys.toString(), files.size >= 20)
        // No network, no raw log.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|okhttp|\bINTERNET\b|DownloadManager|\bSocket\b|\bWebView\b"""), files))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bandroid\.util\.Log\b|\bLog\.[diwev]\(|\bprintln\(|printStackTrace\("""), files))
        // RLog and the voice log: codes and counts, never a name, text, field, query, tag, path or a file's bytes.
        val leaky = Regex("""\b(name|names|text|query|tag|tags|field|fields|merchant|words|said|bytes|entry|entries|path|uri|intake|json)\b""")
        assertEquals(emptyList<String>(), files.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.[iw]\(""")).any { leaky.containsMatchIn(it) } }.keys.toList())
        assertEquals(emptyList<String>(), files.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bVoiceLog\.add\(""")).any { leaky.containsMatchIn(it) } }.keys.toList())
        // F11: the backup's Files code the same (a backup's own log line counts its size in MiB, BackupJob's, outside this check).
        val backupFiles = ours.filterKeys { it == "app/assistant/backup/BackupSources.kt" || it == "app/assistant/backup/RestoreWriter.kt" }
        assertEquals(2, backupFiles.size)
        for (call in listOf("""\bRLog\.[iw]\(""", """\bVoiceLog\.add\("""))
            assertEquals(emptyList<String>(), backupFiles.filter { (_, f) -> Flat(code(f)).calls(Regex(call)).any { leaky.containsMatchIn(it) } }.keys.toList())
        // What writes to disk: the store's sealed streams and its key overwrite, the delete journal's ids, the settings file;
        // F7, deliberately: a copy handed to another app, in cache/files_out/<random>/ alone (filesHandOutACopyAndTakeItBack).
        // F12, deliberately: a tax pack or an expense report, into a folder Robot makes in the one you picked (sys/FilesExport,
        // only through the picker's one-time grant: filesExportOnlyToTheFolderYouPick).
        val writers = Regex("""FileOutputStream|RandomAccessFile|Files\.(write|copy|newOutputStream)|\bwriteAtomic(Bytes)?\(|\.write(Bytes|Text)\(|openFileOutput|FileWriter|PrintWriter|\.copyTo\(""")
        assertEquals(listOf("app/sys/FilesExport.kt", "app/sys/FilesOut.kt", "app/sys/FilesVault.kt", "core/core/files/FileJournal.kt", "core/core/files/FileStore.kt"),
            usersOf(writers, files))
        val store = flatCode(kt("core", "core/files/FileStore.kt"))
        assertEquals(1, store.count("FileOutputStream("))
        assertHas(store.body("private fun sealTo("), "val out = SyncedOutput(FileOutputStream(f))", "FileSeal.writer(out, purpose, id, keys, create, random)")
        assertEquals(1, store.count("RandomAccessFile("))
        assertHas(store.body("internal fun destroyKey("), "raf.write(ByteArray(2 + FileSeal.WRAPPED))")
        assertEquals(1, flatCode(kt("core", "core/files/FileJournal.kt")).count(Regex("""\bwriteAtomic\(""")))
        val vaultCode = flatCode(kt("app", "sys/FilesVault.kt"))
        // FX1, deliberately: the settings file has two writers, a save and a restore's finish, each under the file's own lock
        // and each writing what core decided (FilesSettingsWrite.save, RestoreSafety.filesSettingsFile).
        assertEquals(2, vaultCode.count(Regex("""\bwriteAtomic\(""")))
        assertEquals(2, vaultCode.count("writeAtomic(f, text)"))
        // FX3, deliberately: the save's text comes from one of core's three deciders (a whole save, a change applied to the
        // file as it is, a hidden drawer renamed), each over the file read under the lock.
        assertHas(vaultCode, "val text = next(readOrNull(f)) ?: return@withFileLock false", "FilesSettingsWrite.save(raw, s, pin)",
            "FilesSettingsWrite.update(raw, pin, change)", "FilesSettingsWrite.rename(raw, old, new)",
            "RestoreSafety.filesSettingsFile(readOrNull(f), staged, mine)")
        assertEquals(2, vaultCode.count("withFileLock(lock("))
        // robot_files_v1: named in the vault alone (core's comments may say it; its additional data is "robot-files-v1|…"),
        // made there alone, as the plan says, and never over an alias the Keystore has.
        assertEquals(listOf("app/sys/FilesVault.kt"), ours.filter { withoutComments(it.value).contains("robot_files_v1") }.keys.sorted())
        assertEquals(listOf("app/sys/FilesVault.kt"), usersOf(Regex("""\bKeystoreFileKeys\(|\bAndroidFileKeystore\(""")))
        val vault = flatText(kt("app", "sys/FilesVault.kt"))
        assertHas(vault, "const val ALIAS = \"robot_files_v1\"", "\"AndroidKeyStore\"", "KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT",
            ".setBlockModes(KeyProperties.BLOCK_MODE_GCM)", ".setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)", ".setKeySize(256)",
            ".setRandomizedEncryptionRequired(true)", ".setUnlockedDeviceRequired(true)", "File(ctx.filesDir, \"files\")")
        assertHasNot(vault, "setIsStrongBoxBacked", "setUserAuthentication", "setUnlockedDeviceRequired(false)", "setRandomizedEncryptionRequired(false)",
            "setUserPresenceRequired", "setUserConfirmationRequired")
        assertInOrder(vaultCode.body("private fun key(create: Boolean)"), "keystore.contains(ALIAS) ->", "create -> keystore.generate(spec())",
            "else -> throw FileKeyException(FileKeyProblem.GONE)")
        assertEquals(1, vaultCode.count("keystore.generate("))
        assertEquals(1, vaultCode.count("generateKey()"))
        // Deleted only by Delete them all (core's deleteAll, after its dialog in Settings › Files).
        assertEquals(1, vaultCode.count("keystore.delete("))
        // F11, deliberately: 3, the third being a restore's start of an empty cabinet (prepareRestore), F5's rule word for word.
        assertEquals(3, store.count("keys.delete()"))
        assertHas(store.body("fun deleteAll()"), "keys.delete()")
        // F5, deliberately: an old robot_files_v1 that is gone over a cabinet with nothing sealed in it (an empty index can't
        // be written with it, and nothing could ever be added) is replaced once, in stage alone, under the lock, only for
        // KEY_GONE (never "not now"). Anything sealed, and it is never replaced. F11: and in prepareRestore, the same way.
        for (f in listOf("fun stage(", "fun prepareRestore()"))
            assertInOrder(store.body(f), "if (load == IndexLoad.Empty && nothingSealed()) {", "try { writeIndex(FileIndex()) }",
                // FX2 (files-1.4-review.md K2), deliberately: the replacement is logged, codes only; leftovers (index.enc.bad, a
                // ".part", a ".tmp", any file in the sealed folders) count as sealed.
                "if (x.problem != FileProblem.KEY_GONE) throw x keys.delete()",
                "app.heyrobot.core.VoiceLog.add(app.heyrobot.core.VoiceEvent.FILE_KEY, FileProblem.KEY_GONE.id, 1) writeIndex(FileIndex())")
        assertInOrder(store, "private fun nothingSealed(): Boolean = !indexFile.exists() && !badFile.exists() && !indexTmp.exists()",
            "listOf(blobs, thumbs).all { anyFile(it).not() } && incomingDirs().all { anyFile(it).not() }",
            // FX3, deliberately: and a restore's staging (a commit waiting there holds files sealed with the key); the phone's
            // vault passes it.
            "&& restoreStaging.let { st -> st == null || st.listFiles().orEmpty().none {")
        assertHas(vaultCode, "restoreStaging = RestoreFiles.journal(app).staging(BackupCategory.FILES)")
        // F11: a restore's own Delete them all (its key gone, the preview counting the files that go, your tap): the vault's,
        // gated, called from RestoreWriter's plan alone.
        assertEquals(listOf("app/assistant/backup/RestoreWriter.kt", "app/sys/FilesVault.kt", "app/ui/settings/FilesSettings.kt"),
            usersOf(Regex("""\.deleteAll\(\)"""), ours.filterKeys { it.startsWith("app/") }).filter { it.contains("Files") || it.contains("Restore") })
        assertHas(flatCode(kt("app", "assistant/backup/RestoreWriter.kt")).body("private fun planFiles("),
            "if (vault.load() == IndexLoad.KeyGone && !vault.deleteAll()) throw FileException(FileProblem.LOCKED)")
        // The failures: invalidated is gone, a failed tag is bad data, anything else not now.
        assertHas(vaultCode.body("fun problemOf(t: Throwable): FileKeyProblem"), "is KeyPermanentlyInvalidatedException -> return FileKeyProblem.GONE",
            "is AEADBadTagException -> return FileKeyProblem.BAD_DATA", "return FileKeyProblem.LOCKED")
        // The main app and :voice only; never :brain, :llama, :mt, :lock or the engines' modules.
        assertHas(vault, "private val PROCESSES = setOf(\"main\", \"voice\")", "FilesVault(dir(app), KeystoreFileKeys(), p,", "val p = checkNotNull(process())")
        val filesCode = Regex("""\bapp\.heyrobot\.core\.files\b|\bRobotFiles\b|\bFilesVault\b|\bKeystoreFileKeys\b|\bFilesSettingsStore\b|\bFilesIntake\b|\bIntakeStep\b""")
        assertEquals(emptyList<String>(), usersOf(filesCode, ours.filterKeys { it.startsWith("brain/") || it.startsWith("mt/") || it.startsWith("voice/") || it.startsWith("helpers/") }))
        // The lock gate: the store is the vault's own, and every read or change goes through it.
        // FX3, deliberately: with the restore's staging, which nothingSealed counts.
        assertHas(vaultCode, "private val store = FileStore(dir, keys, process, space, restoreStaging = restoreStaging)")
        assertHas(vaultCode.body("private inline fun <T> gated("), "if (locked()) { store.drop() return whenLocked }")
        for (f in listOf("fun load(): IndexLoad", "fun update(change: (FileIndex) -> FileIndex): IndexLoad", "fun deleteAll(): Boolean", "fun startOver(): Recovery?"))
            assertTrue(f, vaultCode.has("$f = gated("))
        assertHas(vaultCode, "Assistant.locked(ctx) || try { ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }",
            "locked = { locked(app) }")
        // RAM dropped at every screen-off; recovered at the start (once unlocked since boot) and after each unlock; both hooks
        // through the protected system broadcasts (a receiver not exported never hears the unlock).
        // F7 (F0a's finding), deliberately: the main app reads the index only once Robot Files is used in it (RobotFiles.use: the
        // Files tab, Settings › Files, the intake), never at its start; :voice, which files in the background, as before.
        // FX2 (K3), deliberately: :voice also clears stale copies there (filesHandOutACopyAndTakeItBack's pin).
        assertHas(vaultCode, "Intent.ACTION_SCREEN_OFF -> { vault(app).drop()", "Intent.ACTION_USER_PRESENT -> { if (!main || used) worker.execute { recover(app) }",
            "ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF)", "if (!main) worker.execute { if (userUnlocked(app)) { recover(app); clearStale(app) } }")
        assertInOrder(flatText(kt("app", "sys/FilesVault.kt")).body("fun use(ctx: Context)"), "if (used || process() != \"main\") return", "used = true",
            "worker.execute { if (userUnlocked(app)) recover(app) }")
        assertEquals(listOf("app/sys/FilesIntake.kt", "app/ui/files/FilesPort.kt", "app/ui/settings/FilesSettings.kt"),
            usersOf(Regex("""\bRobotFiles\.use\(""")).filter { it.startsWith("app/") })
        assertHas(flatCode(File(java("app"), "app/heyrobot/RobotApp.kt")), "app.heyrobot.sys.RobotFiles.start(this)")
        // Who reaches Robot Files: the app's start, :voice's Cmd.FILES (it only drops what it holds) and Settings › Files; F5's
        // ways in: the intake they all go through, the share target, scanning, the panel's "file this" and a transcript's menu.
        // F6: filing in :voice (FilingWork, its notifications), which VoiceHub starts and kicks on Cmd.FILES.
        // F7: the Files tab (ui/files FilesScreen and its port) and the copies handed to another app (sys/FilesOut).
        // F9, deliberately: the model's two reads in :voice (ToolExecutor's find_files and read_file through PanelFileTools, behind
        // the vault's gate) and read_file's answer (PanelFileAnswer reads the file's text again behind it).
        // F10, deliberately: the morning brief's files part (RoutineRunner, only while unlocked, filesNeverWriteTheCalendar).
        // F11, deliberately: the one backup, in :voice's backup window: Back up now reads each file through the vault's gate
        // (BackupJob, BackupSources) and a restore seals each one again under this phone's key through it (RestoreWriter).
        // F12, deliberately: the tax pack's page (ui/files FilesPack) and its export (sys/FilesExport, through the port's gate).
        // FX1, deliberately: travel mode's state, read from files_settings.json only (never a file), where History shows
        // (ui/HistoryWatch) and in Settings › Memory's rules; the panel asks PanelFiles.
        // FX2, deliberately: the deletes waiting for their Undo, one holder per process (sys/FileDeletes, core PendingDeletes).
        assertEquals(listOf("app/RobotApp.kt", "app/assistant/FilingNotices.kt", "app/assistant/FilingWork.kt", "app/assistant/PanelFileAnswer.kt",
            "app/assistant/PanelFileTools.kt", "app/assistant/PanelFiles.kt", "app/assistant/ToolExecutor.kt", "app/assistant/VoiceHub.kt",
            "app/assistant/backup/BackupJob.kt", "app/assistant/backup/BackupSources.kt", "app/assistant/backup/RestoreWriter.kt", "app/assistant/routines/RoutineRunner.kt",
            "app/sys/FileDeletes.kt", "app/sys/FilesExport.kt", "app/sys/FilesIntake.kt", "app/sys/FilesOut.kt", "app/sys/FilesVault.kt", "app/sys/FilingMemory.kt",
            "app/ui/HistoryWatch.kt", "app/ui/TranscriptsScreen.kt", "app/ui/files/FilesPack.kt", "app/ui/files/FilesPort.kt", "app/ui/files/FilesScreen.kt", "app/ui/files/FilesShare.kt", "app/ui/files/Scan.kt",
            "app/ui/settings/FilesSettings.kt", "app/ui/settings/MemorySettings.kt"),
            usersOf(filesCode, ours.filterKeys { it.startsWith("app/") }))
        // F6: filing runs on its own thread, never the main one; never while locked or during a phone lock (FilingRun asks
        // before each file and each step, and the vault refuses anyway); its notifications never show on the lock screen,
        // and their actions need the phone unlocked; an action reaches :voice only as Robot's own Cmd, with a random token.
        val work = flatCode(kt("app", "assistant/FilingWork.kt"))
        assertHas(work, "Executors.newSingleThreadScheduledExecutor {", "worker.execute(::loop)",
            "override fun locked(): Boolean = RobotFiles.locked(ctx) || PhoneLockNow.on(ctx)")
        assertEquals(0, work.count(Regex("""Dispatchers\.Main|runOnUiThread|Looper\.getMainLooper""")))
        val runCode = flatCode(kt("core", "core/files/FilingRun.kt"))
        assertInOrder(runCode.body("fun file(id: FileId, yourWords: Boolean = false): FilingOutcome"), "if (ports.locked()) return FilingOutcome.Locked",
            "if (!setTries(id, tries + 1))", "ports.bytes(id,", "ports.parse(bytes,", "if (ports.locked()) {", "ports.read(req.request,")
        val notices = flatCode(kt("app", "assistant/FilingNotices.kt"))
        assertHas(notices, ".setVisibility(Notification.VISIBILITY_SECRET)", ".setPublicVersion(public)", ".setAuthenticationRequired(true)",
            "lockscreenVisibility = Notification.VISIBILITY_SECRET", "Intent(Cmd.ACTION).setPackage(ctx.packageName)")
        assertEquals(1, notices.count(".addAction("))
        // F5's vault calls go through the same gate: stage, open and the thumbnails throw LOCKED while locked; a duplicate is
        // never said while locked; a commit while locked discards what was staged (else the next start would put it in
        // Inbox without your answer). Discarding needs no key and works while locked: deliberately not gated.
        for (f in listOf("fun stage(input: InputStream, declared: Long? = null): Staged", "fun open(id: FileId): FileSealReader", "fun thumb(id: FileId): ByteArray?",
            "fun putThumb(id: FileId, bytes: ByteArray)"))
            assertTrue(f, vaultCode.has("$f = gatedOrThrow {"))
        assertHas(vaultCode.body("private inline fun <T> gatedOrThrow("), "if (locked()) { store.drop() throw FileException(FileProblem.LOCKED) }")
        assertTrue(vaultCode.has("fun duplicateOf(sha256: String): FileEntry? = gated(null) {"))
        assertInOrder(vaultCode.body("fun commit(staged: Staged, intake: FileIntake): FileEntry"), "if (locked()) {", "discard(staged)",
            "throw FileException(FileProblem.LOCKED)", "return store.commit(staged, intake)")
        assertTrue(vaultCode.has("fun discard(staged: Staged) = try { store.discard(staged) }"))
        // F7, deliberately not gated either: a delete you confirmed (the tap gated, filesHandOutACopyAndTakeItBack) still
        // destroys its keys when the phone locks during its 10-second Undo; the RAM copy is dropped first while locked.
        assertInOrder(vaultCode.body("fun delete(ids: Collection<FileId>): Deleted?"), "if (!exists()) return null", "if (locked()) store.drop()", "return store.delete(ids)")
        // F10, deliberately: a clean-up suggestion's Delete in Settings › Files, called only from its own dialog's button after
        // the lock is checked at the tap (FilesSettingsTest.remindersBriefAndCleanUp), as Delete them all is.
        assertEquals(listOf("app/ui/files/FilesPort.kt", "app/ui/settings/FilesSettings.kt"), usersOf(Regex("""\bvault\.delete\(""")).filter { it.startsWith("app/") })
        // FX2 (files-1.4-review.md K1), deliberately: the lock still checked at the tap, then the delete waits 10 seconds for its
        // Undo in this process's holder (core PendingDeletes), which runs it when the Undo runs out or the page goes.
        val settingsDelete = flatCode(kt("app", "ui/settings/FilesSettings.kt")).body("fun FilesSettingsScreen(")
        assertInOrder(settingsDelete, "if (withContext(Dispatchers.IO) { store.locked() }) {", "return@launch",
            "val token = deletes.start(listOf(e.id), undoMs, delete = { ids -> store.delete(ids.single()).also { if (it) store.changed() } })")
        assertEquals(1, flatCode(kt("app", "ui/settings/FilesSettings.kt")).count(Regex("""\bstore\.delete\(""")))
        val hub = flatCode(kt("app", "assistant/VoiceHub.kt"))
        assertEquals(1, hub.count(Regex("""\bRobotFiles\.""")))
        // F6: Cmd.FILES also wakes filing (a file came in); a notification's Undo and File them reach FilingWork with their token.
        // FX3, deliberately: and the panel puts away a conversation holding Files' answers once travel mode hides drawers.
        assertHas(hub, "Cmd.FILES -> { RobotFiles.changed(app); FilingWork.kick(); PanelController.filesChanged() }", "Cmd.FILES_UNDO -> FilingWork.act(app, Cmd.FILES_UNDO, i.getStringExtra(Cmd.EXTRA_KEY))",
            "Cmd.FILES_FILE -> FilingWork.act(app, Cmd.FILES_FILE, i.getStringExtra(Cmd.EXTRA_KEY))", "FilingWork.start(app)")
        assertHas(flatCode(kt("app", "sys/FilesVault.kt")).body("fun changed(ctx: Context)"), "vault(ctx).drop()")
        // Settings' row counts files without the key (FileStore.usage reads sizes only), Settings › Files is FLAG_SECURE.
        assertHas(flatCode(kt("app", "ui/settings/SettingsRoot.kt")), "files ?: filesSummary()")
        // FX1, deliberately: travel mode is read first and the row says nothing until it is known; still nothing while locked.
        assertInOrder(flatCode(kt("app", "ui/settings/FilesSettings.kt")).body("internal fun filesSummary()"), "produceState<FilesRow?>(null)",
            "TravelMode.active(", "RobotFiles.locked(ctx) -> null", "else -> FilesRow.Usage(vault.usage())", "null -> \"\"")
        assertHas(flatCode(kt("app", "ui/settings/FilesSettings.kt")).body("fun FilesSettingsScreen("), "IndexLoad.Locked -> null")
        assertTrue(flatCode(kt("app", "ui/settings/FilesSettings.kt")).body("fun FilesSettingsScreen(").has("SecureWindow()"))
        // Nothing about files on the lock screen: the lock's code, the panel and every other :voice screen name none of it, but
        // for "file this" (F5, deliberately), whose every answer starts with the gate: during a phone lock and while the phone is
        // locked it says so and takes nothing.
        // F6, deliberately: filing in the background (FilingWork: nothing while locked, its changes refused while locked) and
        // its notifications (never on the lock screen, their actions only once unlocked), pinned above.
        // F9, deliberately: the model's find_files and read_file (ToolExecutor, PanelFileTools) and read_file's answer
        // (PanelFileAnswer), each behind the gate first (filesReachTheModelOnlyThroughTwoReads), never described while locked.
        // F10, deliberately: the brief's files part (RoutineRunner: only unlocked), filesNeverWriteTheCalendar.
        // F11, deliberately: the one backup (BackupJob, BackupSources, RestoreWriter), in the backup window, which is never over
        // the lock screen and FLAG_SECURE for its whole life (1.1's pins), refuses to start while locked, and reaches the files
        // through the vault's gate only.
        assertEquals(listOf("app/assistant/FilingNotices.kt", "app/assistant/FilingWork.kt", "app/assistant/PanelFileAnswer.kt",
            "app/assistant/PanelFileTools.kt", "app/assistant/PanelFiles.kt", "app/assistant/ToolExecutor.kt", "app/assistant/VoiceHub.kt",
            "app/assistant/backup/BackupJob.kt", "app/assistant/backup/BackupSources.kt", "app/assistant/backup/RestoreWriter.kt", "app/assistant/routines/RoutineRunner.kt"),
            usersOf(filesCode, ours.filterKeys { it.startsWith("app/assistant/") || it.startsWith("app/lock/") }))
        assertInOrder(flatCode(kt("app", "assistant/backup/BackupJob.kt")).body("fun run(uri: Uri, passphrase: CharSequence, progress: (Progress) -> Unit): Outcome"),
            "if (Assistant.locked(ctx)) return failed(BackupCode.LOCKED, null)", "BackupSources.gather(")
        assertInOrder(flatCode(kt("app", "assistant/backup/RestoreWriter.kt")).body("private fun write(plan: RestorePlan, progress: (BackupCategory) -> Unit): RestoreResult"),
            "if (Assistant.locked(ctx)) {", "planFiles(")
        assertHas(flatCode(kt("app", "assistant/backup/BackupActivity.kt")), "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        assertHas(flatCode(kt("app", "assistant/FilingWork.kt")).body("fun change(ctx: Context, change: (FileIndex) -> FileIndex?): Boolean"), "if (RobotFiles.locked(ctx)) return false")
        val panelFiles = flatCode(kt("app", "assistant/PanelFiles.kt"))
        assertHas(panelFiles, "gate: () -> Phrase? = { FilesIntake.gateText(FilesIntake.gate(ctx)) }")
        // F6: the gate before anything, a move included (and a move's change is refused while locked, FilingWork.change).
        // F8: every one of Robot Files' words waits behind the same gate (a rule, a move, find, Inbox, a delete card).
        assertInOrder(panelFiles.body("fun file(ctx: Context, cmd: FilesCommand"), "gate()?.let { return Reply(it) }", "return when (cmd) {")
        assertInOrder(panelFiles.body("private fun fileThis("), "if (heard.ask == FileThis.Ask.MOVE) return move(", "val intake = intakeOf()")
        assertInOrder(flatCode(kt("app", "sys/FilesIntake.kt")).body("fun gate(ctx: Context): Gate"), "app.heyrobot.lock.PhoneLockNow.on(ctx) -> Gate.PHONE_LOCK",
            "RobotFiles.locked(ctx) -> Gate.LOCKED", "!FilesSettingsStore.current(ctx).on -> Gate.OFF", "else -> Gate.OPEN")
        // The panel names the files code only through PanelFiles.
        assertEquals(emptyList<String>(), usersOf(filesCode, ours.filterKeys { it == "app/assistant/PanelController.kt" || it == "app/assistant/Capture.kt" }))
        // Delete them all: refused while locked (checked again at the tap) and only after its dialog.
        val page = flatCode(kt("app", "ui/settings/FilesSettings.kt"))
        assertEquals(1, page.count("store.deleteAll()"))
        assertInOrder(page, "if (confirmAll) { ConfirmDialog(", "if (store.locked()) null else store.deleteAll()")
        // files_settings.json: never settings.json (schema 13) and never one of the backup's Settings files (a ninth would make
        // a 1.1 reader refuse that category); it goes with the files' own category (F11).
        assertEquals("files_settings.json", app.heyrobot.core.files.FilesSettingsCodec.FILE)
        assertEquals(13, app.heyrobot.core.RobotSettings.SCHEMA)
        assertTrue(!Regex("""\bFilesSettings\b|files_settings|\bcore\.files\b""").containsMatchIn(withoutComments(kt("core", "core/Settings.kt"))))
        for (c in app.heyrobot.core.backup.BackupCategory.entries.filter { it.name != "FILES" })
            assertEquals(c.name, null, c.maxBytes(app.heyrobot.core.files.FilesSettingsCodec.FILE))
    }

    /**
     * 1.4, Robot Files' two ways to the model (files-1.4-plan.md §4.2, §5.1, §5.4, §5.5 "the model"; job F4): FILE_READ and
     * FILE_ANSWER are one-shots, tool-free and chat only, with fixed prompts built in core files FilePrompt alone, and a
     * file's words only after FileQuote's frame. A page's picture is the one new thing that crosses to :brain: a JPEG, in
     * the panel's own way (KEY_IMAGE's read-only SharedMemory, taken by BrainService alone), only on a FILE_READ that says
     * so, checked by OneShotRules.imageCheck before either engine uses it; Gemma passes it to LiteRT-LM as it passes a panel
     * photo, llama.cpp refuses it (NO_VISION), and nothing in :brain decodes it. Nothing outside core reads a reading yet:
     * F6's filing will, and must be added here when it does.
     */
    @Test fun theFileOneShotsAreToolFreeFramedAndSendOnlyAJpeg() {
        // What crosses: the one-shot's JSON as before, plus hasImage only with a picture; the bytes beside it, never in it.
        val text = app.heyrobot.core.files.FilePrompt.readText("TOTAL 66.31 CAD", app.heyrobot.core.files.FileSource.SHARE)!!.request
        val picture = app.heyrobot.core.files.FilePrompt.readPicture(app.heyrobot.core.files.FileSource.SHARE)!!.request
        assertEquals(setOf("kind", "priority", "system", "text", "maxChunks"), text.toJson().keys().asSequence().toSet())
        assertEquals(setOf("kind", "priority", "system", "text", "maxChunks", "hasImage"), picture.toJson().keys().asSequence().toSet())
        assertEquals(1, flatCode(kt("core", "core/BrainWire.kt")).count(Regex("""putString\(""")))
        assertEquals(1, flatCode(kt("core", "core/BrainWire.kt")).count(Regex("""putParcelable\(""")))
        assertHas(flatCode(kt("core", "core/BrainWire.kt")), "image?.let { putParcelable(BrainProtocol.KEY_IMAGE, it) }")
        // Taken by BrainService alone, for a panel question and a one-shot.
        assertEquals(listOf("brain/brain/BrainService.kt"), usersOf(Regex("""\bBrainWire\.takeImage\(""")))
        val service = flatCode(kt("brain", "brain/BrainService.kt"))
        assertEquals(2, service.count("BrainWire.takeImage(msg)"))
        assertHas(service, "BrainProtocol.REQ_ONE_SHOT -> engine.oneShot(key, payload as BrainMsg.OneShot, BrainWire.takeImage(msg), sink)")
        // Gemma: the picture's check before it waits and again as it starts, then the bytes go to LiteRT-LM before the turn's
        // (defused) text, as a panel photo's do. Two places hand LiteRT-LM a picture: the panel's question and this.
        val gemma = flatCode(kt("brain", "brain/GemmaEngine.kt"))
        assertEquals(2, gemma.count("Content.ImageBytes("))
        assertInOrder(gemma.body("override fun oneShot("), "OneShotRules.imageCheck(req, image, vision)", "submit(key, req.priority, sink) { job -> startOneShot(job, req, image, sink) }")
        assertInOrder(gemma.body("private fun startOneShot("), "OneShotRules.imageCheck(req, image, vision)", "OneShotRules.conversation(req)",
            "image?.let { add(Content.ImageBytes(it)) }", "add(Content.Text(controls.defuse(req.text)))")
        // llama.cpp: text only, the picture refused before it waits, never kept.
        val llama = flatCode(kt("brain", "brain/LlamaEngine.kt"))
        assertInOrder(llama.body("override fun oneShot("), "OneShotRules.imageCheck(req, image, vision = false)", "submit(key, req.priority, sink) { job -> startOneShot(job, req, sink) }")
        assertTrue(!llama.body("private fun startOneShot(").matches(Regex("""\bimage\b""")))
        // Nothing in :brain's own code decodes a picture or a document: LiteRT-LM's encoder does, as for the panel's photos.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bImageDecoder\b|\bBitmapFactory\b|\bPdfRenderer\b|\bPdfDocument\b|\bandroid\.graphics\.Bitmap\b"""),
            ours.filterKeys { it.startsWith("brain/") }))
        // The rule itself: only a FILE_READ that says it has one, a JPEG under the cap; NO_VISION only after that.
        val rules = flatCode(kt("core", "core/BrainSchedule.kt"))
        assertInOrder(rules.body("fun imageCheck("), "req.kind != OneShotKind.FILE_READ -> BrainError.BAD_REQUEST", "!isJpeg(image) -> BrainError.BAD_REQUEST", "!vision -> BrainError.NO_VISION")
        assertHas(flatCode(kt("core", "core/BrainProtocol.kt")), ".takeIf { !it.hasImage || it.kind == OneShotKind.FILE_READ }")
        // The voice process sends a picture with a one-shot from one place, BrainOneShots.runWithImage, and F6's filing is its
        // one caller (FilingWork's port, for FilingRun's FILE_READ of a page's picture: the panel's "file this" goes through
        // it too). Filing never sends a file to a helper: FilingWork names no helper's code.
        assertEquals(listOf("app/assistant/OneShots.kt"), usersOf(Regex("""\bREQ_ONE_SHOT\b""")).filter { it.startsWith("app/") })
        assertEquals(1, flatCode(kt("app", "assistant/OneShots.kt")).count("c.request(BrainProtocol.REQ_ONE_SHOT, req, image)"))
        assertEquals(listOf("app/assistant/FilingWork.kt"), usersOf(Regex("""\.runWithImage\(""")))
        val filing = flatCode(kt("app", "assistant/FilingWork.kt"))
        assertEquals(1, filing.count(".runWithImage("))
        assertHas(filing.body("override fun read(req: BrainMsg.OneShot, jpeg: ByteArray?): OneShotResult"),
            "if (jpeg != null) BrainOneShots.runWithImage(req, jpeg, budget) else BrainOneShots.run(req, budget) {}")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bHelper\w*|\bOnlineRun\b|\bAnthropic\w*|\bjava\.net\b|\bHttp\w*"""),
            ours.filterKeys { it in setOf("app/assistant/FilingWork.kt", "app/assistant/FilingNotices.kt", "app/assistant/PanelFiles.kt") }))
        // What FilingRun asks the model: FilePrompt's two FILE_READs alone, never FILE_ANSWER, never a request it builds itself.
        val run = flatCode(kt("core", "core/files/FilingRun.kt"))
        assertHas(run, "FilePrompt.readPicture(entry.from.source, frameKind)", "FilePrompt.readText(it, entry.from.source, frameKind,")
        assertEquals(0, run.count(Regex("""BrainMsg\.OneShot\(|FILE_ANSWER""")))
        // The prompts: fixed constants in FilePrompt, the only place that makes a file one-shot; every file's words after
        // FileQuote's frame, in its quotes.
        assertEquals(listOf("core/core/files/FilePrompt.kt"), usersOf(Regex("""\bOneShotKind\.FILE_ANSWER\b|\bREAD_(PICTURE|TEXT)\b""")))
        val fp = flatCode(kt("core", "core/files/FilePrompt.kt"))
        assertEquals(3, fp.count(Regex("""BrainMsg\.OneShot\(""")))
        assertHas(fp.body("fun readPicture("), "FileQuote.frame(kind, source, picture = true) ?: return null",
            "BrainMsg.OneShot(OneShotKind.FILE_READ, Priority.SUMMARY, READ_PICTURE, frame, PICTURE_CHUNKS, hasImage = true)")
        assertHas(fp.body("fun readText("), "FileQuote.frame(kind, source) ?: return null",
            "BrainMsg.OneShot(OneShotKind.FILE_READ, Priority.SUMMARY, READ_TEXT, FileQuote.block(frame, quoted), TEXT_CHUNKS)")
        assertHas(fp.body("fun answer("), "FileQuote.frame(kind, source) ?: return null", "val q = question(question) ?: return null",
            "BrainMsg.OneShot(OneShotKind.FILE_ANSWER, Priority.PANEL, ANSWER,")
        assertHas(flatText(kt("core", "core/files/FilePrompt.kt")).body("fun answer("), "\"\$head\\n\${FileQuote.block(frame, quoted)}\\nQuestion: \$q\"")
        assertHas(flatCode(kt("core", "core/files/FileQuote.kt")).body("fun clean("), "ScreenText.sanitize(Invisible.strip(raw)")
        // One-shots describe no tool and run chat only, whatever they're for.
        assertHas(rules, "fun conversation(req: BrainMsg.OneShot): Conv = Conv(req.system, emptyList(), ToolMode.CHAT_ONLY, emptyList(), constrained = false)")
        // A reading stays data: F6's filing reads it (FilingRun gets it, FileFields re-reads every value, Filer never sees it)
        // in core alone; only FileFields opens an Unread value (readBy), and none of it logs.
        assertEquals(listOf("core/core/files/FileFields.kt", "core/core/files/FileReading.kt", "core/core/files/FilingRun.kt"),
            usersOf(Regex("""\bFileReading\b|\bFileReadOutcome\b|\bUnread\b""")))
        assertEquals(listOf("core/core/files/FileFields.kt"), usersOf(Regex("""\.readBy\b""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bRLog\b|\bVoiceLog\b|\bandroid\.util\.Log\b|\bprintln\("""),
            ours.filterKeys { it in setOf("core/core/files/FileFields.kt", "core/core/files/FileRules.kt", "core/core/files/Filer.kt", "core/core/files/FilingRun.kt") }))
        val fileCode = ours.filterKeys { it in setOf("core/core/files/FilePrompt.kt", "core/core/files/FileQuote.kt", "core/core/files/FileReading.kt") }
        assertEquals(3, fileCode.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bRLog\b|\bVoiceLog\b|\bandroid\.util\.Log\b|\bprintln\(|\bjava\.net\b"""), fileCode))
    }

    /**
     * 1.4, Robot Files' isolated reader (files-1.4-plan.md §4.3, §5.5, D5, D19; job F3): a file's bytes, and what is made
     * from them, are decoded only in :files (app/parse/), an isolated process (mergedManifestPutsEachComponentInItsProcess)
     * that checks Process.isIsolated() before it reads anything, names nothing of Robot's processes, stores or keys, opens
     * nothing by name, goes nowhere online and logs nothing. Only FileParseLink binds it, a fresh isolated instance each
     * time, and sends a file only once the process proved an isolated uid; it checks every answer. The main app and the
     * panel turn its raw pixels into a Bitmap in FilePixels alone. Nothing calls FileParseLink yet: F5 (intake), F6
     * (filing) and F7 (the screens) add themselves here when they do.
     */
    /**
     * 1.4 F8 (files-1.4-plan.md §4.5, §9, D9, D15): Robot Files' words in the panel. The router is pure and decides nothing
     * that writes; a move by voice needs exactly one file named; a delete is only ever a card, and only a tap on it deletes
     * (AskWhy.DELETE: a spoken yes never confirms it); a rule is made only from your words or your taps, never the model's.
     */
    @Test fun filesWordsActOnlyThroughTheCardOrATap() {
        // The router: pure core, no store, no vault, no delete, no memory.
        val router = flatCode(kt("core", "core/files/FilesRouter.kt"))
        assertHasNot(router, "FileStore", "FilesVault", "MemoryFiles", "MemoryEdits", ".delete(", "VoiceLog", "RLog")
        // In the panel's order after skills and before hands-free (D15), from your own words only.
        val pc = flatCode(kt("app", "assistant/PanelController.kt"))
        assertInOrder(pc, "val skillCmd = ", "val fileCmd = ", "PanelFiles.route(q)", "val handsFreeCmd = ")
        // Delete: one call deletes, in PanelFiles.deleteTapped, which only PanelController.confirm's files branch reaches (the
        // card's Delete button); its card is tap-only, and "no" (or Keep) declines.
        val panel = flatCode(kt("app", "assistant/PanelFiles.kt"))
        assertEquals(1, panel.count(Regex("""\.delete\(listOf\(""")))
        assertHas(panel, "delete: (FileId) -> Boolean = { id -> (app.heyrobot.sys.RobotFiles.vault(ctx).delete(listOf(id))")
        // FX2 (K1), deliberately: the tap's Delete starts the 10 seconds of its Undo (core PendingDeletes); the holder deletes.
        assertInOrder(panel.body("fun deleteTapped("), "if (!yes) {", "gate()?.let { return Tapped(ActionStatus.PENDING, it.render(langs)) }",
            "val waiting = held.start(listOf(id), delete = { ids -> try { delete(ids.single()) }")
        assertHas(panel.body("private fun deleteCard("), "tapFor = app.heyrobot.core.AskWhy.DELETE", "ActionStatus.PENDING")
        assertHasNot(panel.body("private fun deleteCard("), "delete(listOf")
        assertEquals(listOf("app/assistant/PanelController.kt", "app/assistant/PanelFiles.kt"), usersOf(Regex("""\bdeleteTapped\(""")))
        assertInOrder(pc.body("fun confirm(actionId: String, yes: Boolean) {"), "if (PanelFiles.isDelete(actionId)) return filesDeleteTapped(actionId, yes)", "slot.confirmations.answer(actionId, yes)")
        assertEquals(2, pc.count(Regex("""\bfilesDeleteTapped\(""")))
        // A spoken or typed yes answers a card only through PanelCardVoice, which never confirms a tap-only card (core CardAnswer).
        assertHas(flatCode(kt("app", "assistant/PanelState.kt")), "CardAnswer.Waiting(choices.size, tapFor,")
        // A move by voice: exactly one file, or none is moved (the reply counts them).
        assertHas(panel.body("private fun moveNamed("), "FileFind.one(found, q) ?: return several(found, langs)", "FilesRouter.drawer(c.drawer, FileViews.yours(idx), idx.outOfSight) ?:") // F12, deliberately: never a drawer travel mode hides
        // Rules: from your words (PanelFiles.rule, SAID or TYPED) or your taps (the Files screens, SETTINGS), through FilingMemory.
        assertEquals(listOf("app/assistant/PanelFiles.kt", "app/ui/files/FilesPort.kt"), usersOf(Regex("""\bFilingMemory\.save\(""")))
        assertEquals(listOf("app/ui/files/FilesScreen.kt"), usersOf(Regex("""\bFilingRules\.from(Move|Tag)\(""")))
        // The model never makes one: no tool, no prompt, no one-shot names a rule.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bFilingMemory\b|\bMemoryFiling\b|\bFilingRules\b"""),
            ours.filterKeys { it.startsWith("brain/") || it.startsWith("helpers/") || it in setOf("core/core/Prompt.kt", "core/core/Tools.kt", "app/assistant/OneShots.kt") }))
    }

    /**
     * 1.4 F9 (files-1.4-plan.md §5, D7, D8): what of your files the model and helpers can see and do. Two reads, both Robot's
     * own (internal): find_files gives names, drawers, dates, a few one-line fields and code's totals, never a file's text;
     * read_file ends the model's turn, and only FILE_ANSWER, a tool-free one-shot, ever reads a file's text for an answer.
     * Neither runs on the lock screen or during a phone lock, whatever "Works on the lock screen" holds, and neither is
     * described then. No helper can call them, what they found goes to a helper only on a tap, and a medical file, an ID or
     * a secret never reaches a helper at all: its name and fields never reach the model, and its answer isn't kept.
     */
    @Test fun filesReachTheModelOnlyThroughTwoReads() {
        // Both tools: reads, internal, in their own group, never for a helper or a routine or a skill.
        for (t in listOf(app.heyrobot.core.ToolName.FIND_FILES, app.heyrobot.core.ToolName.READ_FILE)) {
            assertTrue(t.name, t.reads && t.internal && t.group == app.heyrobot.core.ToolGroup.FILES && t.forModel && !t.online)
            assertFalse(t.name, t in app.heyrobot.core.HelperTools.OFFERED)
            assertFalse(t.name, t in app.heyrobot.core.LockScreen.CHOICES)
            assertEquals(t.name, app.heyrobot.core.StepClass.NEVER, app.heyrobot.core.RoutineGate.classOf(t))
            assertTrue(t.name, t in app.heyrobot.core.skills.StepKind.NEVER)
            assertEquals(t.name, app.heyrobot.core.Personal.FILES, app.heyrobot.core.Personal.of(t))
            // Locked: never, even listed in "Works on the lock screen" (a restored file can't list them: read back without).
            val listed = app.heyrobot.core.RobotSettings().copy(lockedTools = setOf(t))
            assertEquals(t.name, app.heyrobot.core.GateDecision.NEEDS_UNLOCK, app.heyrobot.core.ApprovalGate.decide(t, locked = true, settings = listed))
            assertTrue(t.name, app.heyrobot.core.ApprovalGate.lockedOut(t, true, listed))
            assertFalse(t.name, t in app.heyrobot.core.RobotSettings.fromJson(listed.toJson().toString()).lockedTools)
        }
        // Described only while Robot Files can be read: on, unlocked, no phone lock (PanelFileTools.open), and not locked now.
        val pc = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(pc, "val filesOpen = withContext(Dispatchers.IO) { PanelFileTools.open(VoiceHub.app) }", "files = filesOpen && !locked)")
        val tools = flatCode(kt("app", "assistant/PanelFileTools.kt"))
        assertHas(tools.body("fun open(ctx: Context): Boolean"), "FilesIntake.gate(ctx) == FilesIntake.Gate.OPEN")
        assertHas(tools.body("fun refusal(gate: FilesIntake.Gate): Prepared.Refused?"), "FilesIntake.Gate.PHONE_LOCK -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LockStrings.closedNow())",
            "FilesIntake.Gate.LOCKED -> Prepared.Refused(ActionStatus.NEEDS_UNLOCK, FilesStrings.locked())")
        // Run: the gate first, every time (the phone may lock while a card waits), then the index.
        assertInOrder(tools.body("fun find(a: Action.FindFiles,"), "refusal(gate())?.let { return refused(it) }", "val idx = index()", "FileTools.results(shown, zone)")
        assertInOrder(tools.body("fun read(a: Action.ReadFile,"), "refusal(gate())?.let { return refused(it) }", "val idx = index()", "file = FileAsk(")
        val executor = flatCode(kt("app", "assistant/ToolExecutor.kt"))
        assertHas(executor, "is Action.FindFiles, is Action.ReadFile -> PanelFileTools.prepare(action, app.heyrobot.sys.FilesIntake.gate(ctx))",
            "is Action.FindFiles -> PanelFileTools.find(action,", "is Action.ReadFile -> PanelFileTools.read(action,")
        // find_files never returns a file's text: its results name no entry's text, and read_file returns no results at all.
        val core = flatText(kt("core", "core/files/FileTools.kt"))
        assertEquals(0, core.body("fun results(").count(Regex("""\.text\b""")))
        assertEquals(0, core.body("fun summary(").count(Regex("""\.text\b""")))
        assertHasNot(tools.body("fun read(a: Action.ReadFile,"), "results =")
        // read_file's answer only through FILE_ANSWER (FilePrompt.answer), from PanelFileAnswer alone, after the model's turn ended.
        assertEquals(listOf("app/assistant/PanelFileAnswer.kt"), usersOf(Regex("""\bFilePrompt\.answer\(""")))
        assertEquals(listOf("app/assistant/PanelController.kt"), usersOf(Regex("""\bfileAnswer\.answer\(""")))
        assertHas(pc, "result.file?.let { return fileAnswer.answer(replyId, it) }")
        val turn = flatCode(kt("core", "core/Turn.kt"))
        assertHas(turn, "if (r.file != null && r.status == ActionStatus.DONE) file = r.file", "file?.let { model.close() return TurnResult(DoneReason.END, file = it) }")
        // A rebuilt conversation reads the answer as information, framed.
        assertHas(flatText(kt("core", "core/Conversation.kt")), "app.heyrobot.core.files.FileQuote.ANSWER_FRAME + \" \" + HelperContext.forLocal(t.text)")
        // Helpers: what the tools found goes only on a tap (no switch, trust or spoken yes), and a private file never at all.
        val rules = flatCode(kt("core", "core/helpers/HelperRules.kt"))
        assertHas(rules, "private fun tapOnly(personal: Set<Personal>): Boolean = Personal.FILES in personal")
        assertHas(rules, "!memoryHeld(h, personal, memoryToTrusted) && !tapOnly(personal) &&",
            "personal.isEmpty() || (!tapOnly(personal) && (s.voiceYesPersonal || (h?.trust == true && !captured(personal))))")
        // Joining a conversation with files in it: the Include card needs a tap (AskWhy.FILES), never a spoken yes.
        assertHas(flatCode(kt("app", "assistant/PanelHelperTurn.kt")), "tapFor = if (app.heyrobot.core.Personal.FILES in personal) app.heyrobot.core.AskWhy.FILES else null)",
            "personal = personal, tapFor = tapFor))")
        assertEquals(setOf(app.heyrobot.core.files.FileKind.MEDICAL, app.heyrobot.core.files.FileKind.ID, app.heyrobot.core.files.FileKind.DANGEROUS),
            app.heyrobot.core.files.FilesForHelper.NEVER)
        // A file's helper card: FilesForHelper.card is the one place a file's words become a helper's block, after the gate.
        assertInOrder(core.body("fun card(e: FileEntry,"), "e.kind == FileKind.DANGEROUS ->", "e.kind in NEVER -> return FileForHelper.Refused(",
            "secret(e) -> return FileForHelper.Refused(", "FileQuote.frame(e.kind, e.from.source)", "FileQuote.block(frame, text.text)")
        assertEquals(listOf("core/core/files/FilePrompt.kt", "core/core/files/FileTools.kt"), usersOf(Regex("""\bFileQuote\.block\(""")))
        // HelperContext and the helpers' code name nothing of Robot Files: no file block can be built there.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.core\.files\b|\bFileEntry\b|\bFileQuote\b|\bFilesForHelper\b"""),
            ours.filterKeys { it == "core/core/HelperContext.kt" || it.startsWith("helpers/") || it in setOf("app/assistant/HelperLink.kt",
                "app/assistant/PanelEscalation.kt", "app/assistant/PanelHelperTurn.kt", "app/assistant/PanelOffers.kt") }))
        // A private file: no name and no field for the model; its answer shown only, never spoken, kept as a placeholder, the
        // conversation closed after it (so no later "include the conversation" can carry it).
        assertInOrder(core.body("fun results("), "val private = FilesForHelper.private(e)", "if (!private) value(e.name, MAX_NAME)", "if (private) put(\"private\", true)")
        val answer = flatCode(kt("app", "assistant/PanelFileAnswer.kt"))
        assertHas(answer, "val speak = state.value.speak && !ask.private", "if (ask.private) port.privateAnswer(replyId)")
        // The answer reads the file's text again only behind the gate (the phone may have locked since the call).
        assertInOrder(answer.body("suspend fun answer(replyId: String, ask: FileAsk)"), "PanelFileTools.refusal(port.gate())", "val idx = port.index()",
            "FilePrompt.answer(ask.question, e.text,")
        assertHas(pc.body("override fun privateAnswer(replyId: String)"), "PanelFileAnswer.markPrivate(replyId)", "closeConvAfterTurn = true", "host?.secure(true)")
        // Rules (F8) never reach a prompt through these either, and nothing here logs a query, a name, a field or a question.
        val f9 = ours.filterKeys { it in setOf("app/assistant/PanelFileTools.kt", "app/assistant/PanelFileAnswer.kt", "core/core/files/FileTools.kt", "core/core/files/FileSearch.kt") }
        assertEquals(4, f9.size)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bFilingMemory\b|\bMemoryFiling\b|\bFilingRules\b|\bRLog\.[iwe]\([^)]*(query|question|name|text)"""), f9))
        for (f in listOf(tools, answer)) for (c in f.calls(Regex("""VoiceLog\.add\("""))) assertTrue(c, c.startsWith("VoiceEvent.FILE_FIND,FileFindCode."))
    }

    @Test fun filesAreReadOnlyInTheirProcess() {
        val parse = ours.filterKeys { it.startsWith("app/parse/") }
        assertEquals(listOf("app/parse/AndroidDecoders.kt", "app/parse/Decoders.kt", "app/parse/FileParseService.kt", "app/parse/FileParser.kt"), parse.keys.sorted())
        // Isolated first: a request's frame and regions are read only after the check, and HELLO says so.
        val service = flatCode(kt("app", "parse/FileParseService.kt"))
        assertInOrder(service.body("private fun parse("), "if (!Process.isIsolated()) {", "reply.writeInt(0) return }", "data.createByteArray()",
            "SharedMemory.CREATOR.createFromParcel(data)", "FileParseWire.decodeRequest(frame, input.size.toLong(), output.size)", "input!!.mapReadOnly()")
        assertHas(service.body("private fun hello("), "val isolated = Process.isIsolated()", "val ok = isolated && cb != null")
        assertHas(service, "FileParseIpc.HELLO -> synchronized(lock)", "FileParseIpc.PARSE -> synchronized(lock)")
        // It ends only itself (EXIT, unbinding, its watchdog of ParseLimits.SERVICE_MS), never another process.
        assertEquals(3, service.count("Process.killProcess(Process.myPid())"))
        assertEquals(3, service.count(Regex("""\bkillProcess\(""")))
        assertInOrder(service.body("private fun parse("), "job.get(ParseLimits.SERVICE_MS, TimeUnit.MILLISECONDS)", "catch (_: TimeoutException) { Process.killProcess(Process.myPid())")
        assertHas(service.body("override fun onUnbind("), "Process.killProcess(Process.myPid())")
        // app/parse names nothing of Robot's other processes, stores, keys or files, and opens nothing by name: its input is
        // the region it is given.
        val forbidden = Regex("""\bapp\.heyrobot\.(sys|assistant|ui|people|lock|boot|update|voice|brain|mt|helpers)\b|\bapp\.heyrobot\.core\.(memory|files|backup)\b|""" +
            """\b(FilesVault|FileStore|FileKeys|FileSeal\w*|MemoryVault|MemoryFiles|RobotFiles|KeystoreFileKeys|KeyStore)\b|\b(filesDir|cacheDir|dataDir|openFileInput|openFileOutput|getExternalFilesDir|getSharedPreferences)\b|""" +
            """\b(ContentResolver|contentResolver)\b|\bjava\.io\.File\b|\b(FileInputStream|FileOutputStream|RandomAccessFile|FileReader|FileWriter)\b|\bParcelFileDescriptor\.open\b|\bOs\.open\b""")
        assertEquals(emptyList<String>(), usersOf(forbidden, parse))
        // No network, no log of any kind (not even codes: the isolated process says nothing).
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|okhttp|\bSocket\b|\bWebView\b|DownloadManager|\bRLog\b|\bVoiceLog\b|""" +
            """\bandroid\.util\.Log\b|\bLog\.[diwev]\(|\bprintln\(|printStackTrace\("""), parse))
        // The decoders: in app/parse alone, but for today's two (the panel's camera and picker in :voice, Chat's base64
        // pictures in the main app), which a file's bytes never reach.
        assertEquals(listOf("app/assistant/Capture.kt", "app/parse/AndroidDecoders.kt"), usersOf(Regex("""\bImageDecoder\b""")))
        assertEquals(listOf("app/ui/common/Chat.kt"), usersOf(Regex("""\bBitmapFactory\b""")))
        assertEquals(listOf("app/parse/AndroidDecoders.kt"), usersOf(Regex("""\bPdfRenderer\b|\bPdfRendererPreV\b|\bPdfDocument\b|\bmemfd_create\b|\bcopyPixelsToBuffer\b""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bExifInterface\b|\bMediaMetadataRetriever\b|\bBitmapRegionDecoder\b|\bAnimatedImageDrawable\b|\bThumbnailUtils\b""")))
        // The memfd PdfRenderer reads holds only the region's bytes and is closed with the renderer.
        val decoders = flatCode(kt("app", "parse/AndroidDecoders.kt"))
        assertInOrder(decoders.body("private fun memfd("), "Os.memfd_create(", "Os.write(fd, chunk, off, n - off)", "ParcelFileDescriptor.dup(fd)", "Os.close(fd)")
        // The target size is set in the header's callback, before any pixel, past a check of the source caps.
        assertInOrder(decoders.body("private fun decodeTo("), "if (!ParseLimits.sourceOk(src)) throw TooLarge()", "d.setTargetSize(t.width, t.height)",
            "d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE")
        // FileParser refuses what isn't read, and a bomb from its header, before any decoder is asked.
        val parser = flatCode(kt("app", "parse/FileParser.kt"))
        assertHas(parser.body("private fun gate("), "FileGroup.DANGEROUS -> throw Refuse(ParseStatus.DANGEROUS)", "if (b.limit() > ParseLimits.MAX_INPUT) throw Refuse(ParseStatus.TOO_LARGE)")
        assertHas(parser.body("private fun declared("), "if (!ParseLimits.sourceOk(d)) throw Refuse(ParseStatus.TOO_LARGE)")
        // The EXIF date and image headers are read in :files (and the header again in the link's checks), nowhere else.
        assertEquals(listOf("app/parse/FileParser.kt"), usersOf(Regex("""\bExifDate\.read\(""")))
        assertEquals(listOf("app/parse/FileParser.kt", "core/core/parse/FileParseWire.kt"), usersOf(Regex("""\bImageHeader\.""")))
        // core's parse code is pure: java.nio only, no Android, no network, no log.
        val core = ours.filterKeys { it.startsWith("core/core/parse/") }
        assertTrue(core.keys.toString(), core.size >= 7)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bandroid\.|\bRLog\b|\bVoiceLog\b|\bjava\.net\b|\bjava\.io\.File\b"""), core))
        // Raw pixels become a Bitmap in one place, the caps checked first; nothing else copies pixels in.
        assertEquals(listOf("app/sys/FilePixels.kt"), usersOf(Regex("""\bcopyPixelsFromBuffer\b""")))
        assertInOrder(flatCode(kt("app", "sys/FilePixels.kt")).body("fun bitmap("),
            "if (!box.holds(Dim(p.width, p.height)) || p.rgba.size.toLong() != p.width.toLong() * p.height * 4) return null", "b.copyPixelsFromBuffer(")
        // The service's class: referred to by nothing but itself, bound by its name (core FileParseIpc), as in the manifest;
        // nothing outside app/parse names app/parse.
        assertEquals(listOf("app/parse/FileParseService.kt"), usersOf(Regex("""\bFileParseService\b""")))
        assertHas(flatText(kt("core", "core/parse/FileParseWire.kt")), "const val SERVICE_CLASS = \"app.heyrobot.parse.FileParseService\"")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.parse\b|\b(FileParser|AndroidDecoders|Decoders)\b"""), ours.filterKeys { !it.startsWith("app/parse/") }))
        assertEquals(listOf("app/parse/FileParseService.kt", "app/sys/FileParseLink.kt", "core/core/parse/FileParseWire.kt"), usersOf(Regex("""\bFileParseIpc\b""")))
        // The link: a fresh isolated instance each time, by name; the isolated uid proven (the kernel's, from its callback)
        // before anything is sent; the input read-only; every answer checked before use.
        val link = flatCode(kt("app", "sys/FileParseLink.kt"))
        assertEquals(1, link.count("bindIsolatedService("))
        // What :files writes is read as plain numbers and bytes: never readException (which unparcels what it names) and
        // never a Parcelable or a descriptor of its making.
        assertEquals(0, link.count(Regex("""\breadException\(|\breadParcelable\b|\bcreateFromParcel\(|\breadFileDescriptor\(|\breadStrongBinder\(""")))
        assertEquals(0, link.count(Regex("""\bbindService\(""")))
        assertHas(link, "ctx.bindIsolatedService(Intent().setClassName(ctx.packageName, FileParseIpc.SERVICE_CLASS), Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,")
        assertHas(link, "val ok = IsolatedUid.of(from) && uid == -1", "val from = Binder.getCallingUid()", "proven = accepted == true && IsolatedUid.of(uid)")
        assertInOrder(link.body("private fun run("), "check(Looper.myLooper() != Looper.getMainLooper())", "ParseResults.before(req, types)?.let { return it }",
            "if (!proven) return", "SharedMemory.create(", "input.setProtect(OsConstants.PROT_READ)", "calls.submit", "job.get(requestMs, TimeUnit.MILLISECONDS)",
            "FileParseWire.decodeReply(frame, req)", "ParseResults.from(req, reply, types[0])")
        // Its logs: codes only, never a file's name, type, text or bytes.
        val leaky = Regex("""\b(name|text|bytes|type|types|frame|reply|req|input|output|out|parts|prefix|blob|mime)\b""")
        assertEquals(emptyList<String>(), link.calls(Regex("""\bRLog\.[iw]\(""")).filter { leaky.containsMatchIn(it) })
        // The callers (F7 adds itself here): F5's scanning, in the main app (the preview's pixels, a page straightened, the
        // scan's PDF), with one link for the screen's life, ended when it goes; and F6's filing in :voice (FilingWork), one
        // link a batch, a fresh one after any failure, ended when the batch ends, its reads off the main thread.
        assertEquals(listOf("app/assistant/FilingWork.kt", "app/sys/FileParseLink.kt", "app/ui/files/FilesPort.kt", "app/ui/files/Scan.kt"),
            usersOf(Regex("""\bFileParseLink\b""")))
        // F7: the Files screens' link, one for the screen while it is in front (ended when it goes, at ON_STOP and at a lock),
        // for pages and thumbnails as pixels and Share's copy without metadata; at most :files' 64 MiB of a file is read for it.
        val screens = flatCode(kt("app", "ui/files/FilesPort.kt"))
        assertEquals(1, screens.count("FileParseLink(ctx.applicationContext)"))
        assertHas(screens.body("fun end()"), "link?.end(")
        assertHas(screens.body("override fun page(e: FileEntry, page: Int): ShownPage?"), "if (locked() || e.from.size !in 1..ParseLimits.MAX_INPUT) return null",
            "it.readAll(ParseLimits.MAX_INPUT.toLong())", "bytes.fill(0)")
        val filingLink = flatCode(kt("app", "assistant/FilingWork.kt"))
        assertEquals(1, filingLink.count("FileParseLink(ctx)"))
        assertHas(filingLink.body("override fun parse(bytes: ByteArray, flags: Int): ParseResult"), "if (r is ParseResult.Failed) { l.end(")
        assertHas(filingLink.body("fun endBatch()"), "link?.end(")
        assertEquals(listOf("app/sys/FilePixels.kt", "app/ui/files/FilesScreen.kt", "app/ui/files/Scan.kt"), usersOf(Regex("""\bFilePixels\b""")))
        val tab = flatCode(kt("app", "ui/files/FilesScreen.kt"))
        assertEquals(1, tab.count("FilePixels.bitmap(it, thumb = true)"))
        assertEquals(1, tab.count("FilePixels.bitmap(p.pixels, thumb = false)"))
        val scan = flatCode(kt("app", "ui/files/Scan.kt"))
        assertEquals(1, scan.count("FileParseLink(ctx.applicationContext)"))
        assertEquals(1, scan.count("FilePixels.bitmap(c.pixels, thumb = false)"))
        assertHas(scan.body("fun close()"), "reader.end()")
        // RobotApp runs in :files too: it starts no vault there (Robot Files' store is main's and :voice's alone).
        assertHas(flatText(kt("app", "sys/FilesVault.kt")), "private val PROCESSES = setOf(\"main\", \"voice\")")
        assertHas(flatCode(kt("app", "sys/FilesVault.kt")).body("fun start(app: Application)"), "if (process() == null || started) return")
    }

    /**
     * 1.4, Robot Files' share target (files-1.4-plan.md §3.1, §5.5, D3; job F5): "Robot Files" in any app's Share copies only.
     * It takes exactly SEND and SEND_MULTIPLE (checked again in code: an exported activity can be started with any action),
     * shows FLAG_SECURE and refuses touches while another window covers it; it asks during a phone lock, while locked and
     * with Robot Files off, and again at your tap, before reading anything; it reads only what the sender claims until that
     * tap; it names the sending app only as Android confirms it (launchedFromPackage), never the referrer a sender writes. It
     * never decodes, never names the vault, the store or the keys (the intake does, through the vault's gate), never takes
     * a persistable permission, never opens file: or one of Robot's own providers (refused before anything is asked of the
     * sender), and copies through core's IntakeCopy: the 200 MiB cap and the deadlines.
     */
    @Test fun theShareTargetOnlyCopies() {
        val share = flatCode(kt("app", "ui/files/FilesShare.kt"))
        val shareText = flatText(kt("app", "ui/files/FilesShare.kt"))
        assertInOrder(share.body("override fun onCreate("), "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)", "window.decorView.filterTouchesWhenObscured = true",
            "if (i?.action != Intent.ACTION_SEND && i?.action != Intent.ACTION_SEND_MULTIPLE) { finish() return }", "m.start()")
        // The gate first, when it opens and at the tap.
        assertInOrder(share.body("fun start()"), "port.gate()?.let { ui = ShareUi.Refused(it); return }", "worker.execute {")
        assertInOrder(share.body("fun add()"), "port.gate()?.let { ui = ShareUi.Refused(it); return }", "worker.execute {")
        assertHas(share, "override fun gate(): Phrase? = FilesIntake.gateText(FilesIntake.gate(app))")
        // Who sent it: Android's word, or nobody's.
        assertHas(share.body("private fun sender()"), "launchedFromPackage")
        assertTrue(!shareText.hasLoose("referrer") && !shareText.hasLoose("REFERRER"))
        // Nothing decoded, nothing written, no store or key named; what it reads of an intent is typed (a bad extra is nothing).
        val noDecode = Regex("""\bImageDecoder\b|\bBitmapFactory\b|\bPdfRenderer\b|\bPdfDocument\b|\bandroid\.graphics\.Bitmap\b|\bopenInputStream\b|""" +
            """\bopenOutputStream\b|\bFileOutputStream\b|\bwriteAtomic\b|\bFilesVault\b|\bFileStore\b|\bFileKeys\b|\bFileSeal\w*|\bRobotFiles\b|\bKeystoreFileKeys\b""")
        assertEquals(emptyList<String>(), usersOf(noDecode, ours.filterKeys { it == "app/ui/files/FilesShare.kt" }))
        assertHas(share.body("fun of(i: Intent, sender: Pair<String, String?>?): ShareRequest"), "i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)",
            "i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)")
        // Never a persistable grant taken, anywhere in Robot (the updater only clears the flag from an intent it sends).
        assertEquals(emptyList<String>(), usersOf(Regex("""\btakePersistableUriPermission\b""")))
        // The intake: refused before anything is asked or opened; another app's provider through ContentResolver alone (no
        // path, no openInputStream), each call with a deadline; the copy through core's IntakeCopy into the vault.
        val intake = flatCode(kt("app", "sys/FilesIntake.kt"))
        assertInOrder(intake.body("fun describe(uri: Uri, refusal: ShareRefusal?): SharedFile"), "if (refusal != null || src == null) return", "copier.within(")
        assertInOrder(intake.body("fun add(f: SharedFile, intake: (FileType) -> FileIntake): IntakeStep"),
            "if (f.refusal != null) return IntakeStep.Failed(IntakeProblem.NOT_ALLOWED)", "copier.within(", "src.open(f.uri, signal)", "copier.copy(opened.input, declared)")
        assertHas(intake, "private val copier = IntakeCopy({ input, declared -> vault.stage(input, declared) }, vault::discard, times)")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.io\.File\b|\bFileInputStream\b|\bopenInputStream\b|\bopenFileDescriptor\b|\bUri\.fromFile\b"""),
            ours.filterKeys { it == "app/sys/FilesIntake.kt" }))
        assertHas(flatText(kt("app", "sys/FilesIntake.kt")).body("override fun open(uri: Uri, signal: CancellationSignal): SharedSource.Opened"),
            "resolver.openAssetFileDescriptor(uri, \"r\", signal)")
        assertHas(intake, "fun refusal(uri: Uri, own: (String) -> Boolean): ShareRefusal? = ShareRules.check(SharedRef(uri.scheme, uri.encodedAuthority), own)")
        assertHas(intake.body("fun ownAuthority(ctx: Context, authority: String): Boolean"), "if (authority == pkg || authority.startsWith(",
            "ctx.packageManager.resolveContentProvider(authority, 0)?.packageName == pkg")
        assertHas(share, "override fun refusal(uri: Uri) = FilesIntake.refusal(uri) { FilesIntake.ownAuthority(app, it) }")
        val rules = flatCode(kt("core", "core/files/Share.kt"))
        assertInOrder(rules.body("fun check(ref: SharedRef, own: (String) -> Boolean): ShareRefusal?"), "ShareRefusal.NOT_CONTENT", "ShareRefusal.NO_PROVIDER",
            "ShareRefusal.OTHER_USER", "own(authority) -> ShareRefusal.OWN")
        // The cap and the deadlines: a claim over 200 MiB refused unread, the store's seal stops at the cap, the watchdog gives
        // up and closes the sender's stream, and what is staged late is discarded.
        val copy = flatCode(kt("core", "core/files/Intake.kt"))
        assertInOrder(copy.body("fun copy(input: InputStream, declared: Long?, stop: () -> Unit = {}): Intake"), "if (claim != null && claim > FileLimits.MAX_FILE) {",
            "done.get(times.tickMs, TimeUnit.MILLISECONDS)",
            "watched.giveUp() try { stop() } catch (_: Exception) {} closeQuietly(input) done.whenComplete { s, _ -> if (s != null) try { discard(s) }")
        // The share window's every way out discards what waits for an answer.
        assertHas(share.body("override fun onDestroy()"), "model?.close()")
        assertHas(share.body("fun close()"), "port.drop(")
        // Who adds files: the intake alone stages and commits, and only the ways in call it.
        assertEquals(listOf("app/sys/FilesIntake.kt", "app/sys/FilesVault.kt"), usersOf(Regex("""\.stage\(|\bvault\.commit\(|\.commit\(r\.staged""")).filter { it.startsWith("app/") })
        assertEquals(listOf("app/assistant/PanelFiles.kt", "app/ui/TranscriptsScreen.kt", "app/ui/files/FilesShare.kt", "app/ui/files/Scan.kt"),
            usersOf(Regex("""\bFilesIntake\.phone\(""")).filter { it.startsWith("app/") })
    }

    /**
     * 1.4, Robot Files' screens (files-1.4-plan.md §6, D17, D19, Q1, Q2; job F7): the Files tab and its pages are FLAG_SECURE,
     * every dialog and menu on them too (each is a window of its own); nothing of a file is shown while the phone is locked or
     * a phone lock is on (the gate checked when the screen comes back, every second, and before every change, Share, Open
     * and Delete); the screen holds nothing in the background; its page stack is saved as ids only (an open dialog keeps
     * what you typed, as Settings › Memory's do).
     */
    @Test fun theFilesScreensAreSecureAndLocked() {
        val tab = flatCode(kt("app", "ui/files/FilesScreen.kt"))
        val tabText = flatText(kt("app", "ui/files/FilesScreen.kt"))
        assertTrue(tab.body("fun FilesScreen(").has("SecureWindow()"))
        assertEquals(tab.count("AlertDialog("), tab.count("properties = SecureDialog"))
        assertTrue(tab.count("AlertDialog(") >= 3)
        assertEquals(tab.count("DropdownMenu("), tab.count("properties = SecureMenu"))
        assertHas(tab, "PopupProperties(focusable = true, securePolicy = SecureFlagPolicy.SecureOn)")
        assertEquals(tab.count("EditDialog("), tab.count("secure = true"))
        // The gate: locked, the index, pages and search leave the screen, :files' link ends, copies come back, deletes waiting go.
        assertInOrder(tab.body("fun FilesScreen("), "if (port.locked()) null else port.load()", "if (l == null || l == IndexLoad.Locked) { if (load != IndexLoad.Locked) close() }")
        assertInOrder(tab.body("fun close()"), "commitDeletes()", "forget(IndexLoad.Locked)", "port.endReader()", "port.takeBack()")
        assertInOrder(tab.body("fun forget(to: IndexLoad?)"), "load = to", "query =", "askOpen = null", "askStrip = null")
        assertHas(tab, "Lifecycle.Event.ON_STOP -> { commitDeletes() forget(null) port.stopped() work.execute { port.endReader() } }")
        assertInOrder(tab.body("fun change("), "if (port.locked()) return@withContext null", "port.update {")
        assertInOrder(tab.body("fun handOut("), "if (port.locked()) return@withContext OutCopy.Failed(OutFailure.LOCKED) to null", "port.copy(")
        // FX2 (files-1.4-review.md K1), deliberately: the delete waits for its Undo in this process's holder (core PendingDeletes,
        // shared with Settings › Files' clean-up), which deletes through the port when it runs out or sooner (commitDeletes).
        assertInOrder(tab.body("fun delete(e: FileEntry)"), "if (withContext(Dispatchers.IO) { port.locked() }) return@launch",
            "val token = deletes.start(listOf(e.id), undoMs, delete = { ids -> port.delete(ids) })", "withTimeoutOrNull(undoMs)", "deletes.undo(token)")
        assertHas(tab.body("fun FilesScreen("), "fun commitDeletes() = deletes.commitAll()")
        assertHas(flatCode(kt("app", "ui/files/FilesPort.kt")), "override fun locked(): Boolean = PhoneLockNow.on(app) || RobotFiles.locked(app)")
        assertEquals(10_000L, app.heyrobot.ui.files.UNDO_DELETE_MS)
        // Saved state: the page stack, as ids (a starter drawer's, a file's); your drawers' names and a search never saved.
        // (EditDialog, shared, keeps an open dialog's text as Settings › Memory's does.)
        assertEquals(1, tab.count("rememberSaveable("))
        assertHas(tabText, "is File -> \"f:${'$'}{v.id.hex}\"", "is Drawer -> if (v.id in Drawers.STARTER) \"d:${'$'}{v.id}\" else null")
        // MainActivity shows the tab through one entry; no other screen names the Files screens.
        assertEquals(1, flatCode(kt("app", "ui/MainActivity.kt")).count("app.heyrobot.ui.files.FilesRoute("))
        assertEquals(listOf("app/ui/MainActivity.kt", "app/ui/files/FilesScreen.kt"), usersOf(Regex("""\bFilesRoute\b|\bFilesScreen\(""")).filter { it.startsWith("app/") })
    }

    /**
     * 1.4 F12 (files-1.4-plan.md §7.2): a tax pack or an expense report leaves only on your tap on its page's Export, into
     * the folder you then pick in Android's folder picker (ACTION_OPEN_DOCUMENT_TREE), with the one-time grant that pick
     * gives: never a persisted grant, never by voice (the panel's card only opens the page), never from the model's tools,
     * never while locked (the tap, then the port, then FilesExport before each file and after each write).
     */
    @Test fun filesExportOnlyToTheFolderYouPick() {
        assertEquals(emptyList<String>(), usersOf(Regex("""takePersistableUriPermission""")))
        assertEquals(listOf("app/ui/files/FilesScreen.kt"), usersOf(Regex("""\bOpenDocumentTree\b|ACTION_OPEN_DOCUMENT_TREE""")))
        assertEquals(listOf("app/ui/files/FilesPort.kt"), usersOf(Regex("""\bFilesExport\.run\(""")))
        assertEquals(listOf("app/ui/files/FilesPort.kt"), usersOf(Regex("""\bTreeExportPlace\(""")) - "app/sys/FilesExport.kt")
        // The port's export: only in the picker's result, which only your tap on Export starts (after the lock is checked).
        val screen = flatCode(kt("app", "ui/files/FilesScreen.kt"))
        assertEquals(1, screen.count(Regex("""\bport\.export\(""")))
        assertInOrder(screen, "val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->",
            "if (tree == null || what == null) return@rememberLauncherForActivityResult", "port.export(what.kind, what.year, tree)")
        assertInOrder(screen.body("fun export(p: FilesView.Pack)"), "if (withContext(Dispatchers.IO) { port.locked() }) return@launch", "exporting = p", "pickFolder.launch(null)")
        assertEquals(1, screen.count(Regex("""\bexport\(view\)""")))
        assertInOrder(flatCode(kt("app", "ui/files/FilesPort.kt")).body("override fun export("), "if (locked()) return ExportResult.Stopped(ExportStop.LOCKED, 0)",
            "val idx = load().current", "FilesExport.run(")
        val run = flatCode(kt("app", "sys/FilesExport.kt")).body("fun run(")
        assertInOrder(run, "if (src.locked()) return ExportResult.Stopped(ExportStop.LOCKED, 0)", "place.folder(", "for ((i, e) in pack.files.withIndex()) {",
            "if (src.locked()) return ExportResult.Stopped(ExportStop.LOCKED, n)", "copy(e, plan, names[i], folder, src)",
            "if (src.locked()) return ExportResult.Stopped(ExportStop.LOCKED, n + if (ok) 1 else 0)", "FilePack.csv(")
        // Never from the panel, the model or :voice: they only name the page's route.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bFilesExport\b|\bport\.export\b|\bExportSource\b"""), ours.filterKeys { it.startsWith("app/assistant/") }))
    }

    /**
     * 1.4, Robot Files: Share and Open with another app (files-1.4-plan.md §5.2, §6.4; job F7). Never the sealed file: a copy
     * written for one hand-off into cache/files_out/<random>/ (Settings › Files' "Strip location" deciding which: :files' copy
     * without metadata, or the original as it streams from the vault), only while unlocked (checked again after it is
     * written), served by the main app's own FileProvider for that folder alone, with a one-time read grant through the
     * system's chooser (never write, persistable or prefix, never Robot's own share target); taken back (revoked, deleted)
     * when you come back to Robot, at screen-off, when the file is deleted, after 15 minutes, and at the next start.
     */
    @Test fun filesHandOutACopyAndTakeItBack() {
        val out = flatCode(kt("app", "sys/FilesOut.kt"))
        val outText = flatText(kt("app", "sys/FilesOut.kt"))
        assertHas(outText, "fun dir(ctx: Context): File = File(ctx.cacheDir, \"files_out\")", "const val AUTHORITY = app.heyrobot.BuildConfig.APPLICATION_ID + \".files_out\"")
        assertInOrder(out.body("fun make("), "if (locked()) return OutCopy.Failed(OutFailure.LOCKED)", "require(copy != OutgoingCopy.CANT_STRIP)",
            "val folder = File(root, FileId.new(random).hex)", "vault.open(id)", "if (locked()) { folder.deleteRecursively()",
            "FileProvider.getUriForFile(app, AUTHORITY, file)")
        assertEquals(2, out.count("FileOutputStream(file)"))
        assertEquals(2, out.count("FileOutputStream("))
        // Nothing of the sealed store, never a path of Robot's files.
        assertHasNot(outText, "RobotFiles.dir(", "filesDir", "\"files\"", "blobs")
        // The grant: read, on the one URI, through the chooser; nothing in Robot grants or takes anything else.
        assertEquals(0, out.count(Regex("""FLAG_GRANT_WRITE_URI_PERMISSION|FLAG_GRANT_PERSISTABLE_URI_PERMISSION|FLAG_GRANT_PREFIX_URI_PERMISSION|\bgrantUriPermission\(""")))
        assertHas(out.body("fun shareIntent("), "Intent(Intent.ACTION_SEND).setType(c.mime).putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)",
            "send.clipData = ClipData.newRawUri(")
        assertHas(out.body("fun openIntent("), "Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, c.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)")
        assertHas(out, "private fun chooser(ctx: Context, target: Intent, title: String): Intent = Intent.createChooser(target, title).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bgrantUriPermission\(|FLAG_GRANT_PERSISTABLE_URI_PERMISSION""")).filter { it.startsWith("app/") && !it.startsWith("app/update/") })
        // Taken back: revoked, then deleted; at screen-off (the main app's hook), at the start, after Delete them all.
        assertInOrder(out.body("private fun revoke(ctx: Context, which: (Live) -> Boolean)"),
            "ctx.applicationContext.revokeUriPermission(l.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)", "l.folder.deleteRecursively()")
        assertInOrder(out.body("fun resumed(ctx: Context)"), "if (!away) return", "revokeAll(ctx)")
        assertHas(out.body("fun make("), "main.postDelayed({ expire(app) }, FileOut.MAX_MS")
        val vault = flatCode(kt("app", "sys/FilesVault.kt"))
        assertHas(vault, "if (main) FilesOut.revokeAll(app)", "ScanFiles.clear(app); FilesOut.clear(app)")
        // FX2 (files-1.4-review.md K3): :voice deletes copies and scans' photos older than 15 minutes at its start and at each
        // unlock, so one left by a main app Android ended doesn't wait for the main app's next start.
        assertHas(vault, "if (!main) worker.execute { if (userUnlocked(app)) { recover(app); clearStale(app) } }", "if (!main) worker.execute { clearStale(app) }",
            "val n = FilesOut.clearStale(app) + ScanFiles.clearStale(app)")
        assertHas(out.body("internal fun stale(root: File, now: Long, maxMs: Long): Int"), "if (now - newest > maxMs && f.deleteRecursively()) n++")
        assertEquals(listOf("app/sys/FilesVault.kt", "app/ui/files/FilesPort.kt", "app/ui/settings/FilesSettings.kt"),
            usersOf(Regex("""\bFilesOut\.""")).filter { it.startsWith("app/") })
        assertEquals(listOf("app/sys/FilesOut.kt"), usersOf(Regex("""\bFilesOutProvider\b""")).filter { it.startsWith("app/") })
        // The screen: a copy per tap, the plan core's (FileOut.plan: the location question before a copy that keeps it);
        // the copy taken back when the file is deleted and when the screen goes.
        val tab = flatCode(kt("app", "ui/files/FilesScreen.kt"))
        assertHas(tab.body("fun handOut("), "when (val plan = FileOut.plan(e, type, port.settings().stripLocation))",
            "is OutPlan.AskFirst -> if (allowOriginal) port.copy(e, OutgoingCopy.ORIGINAL")
        assertHas(tab.body("fun delete(e: FileEntry)"), "port.takeBack(e.id)")
        assertHas(flatCode(kt("app", "ui/files/FilesPort.kt")).body("override fun delete(ids: Collection<FileId>): Boolean"), "for (id in ids) FilesOut.revoke(app, id)")
    }

    /**
     * 1.0, Settings › Memory › Import: a file someone else may have made. It is read by core MemoryImport (strict JSON, 5 MB
     * and 5,000 items at most, its ids never used), shown on a FLAG_SECURE preview, refused on the lock screen before the
     * picker and again before the write, and written only by a tap, through the vault's update (its lock, its key). Its items
     * are content until you tick "These are my own memories" (MemoryImported.CONTENT counts as tainted). Android's picker
     * needs no permission, and the manifest has no new one.
     */
    @Test fun memoryImportIsLockedSecureAndContentByDefault() {
        val settings = flatCode(kt("app", "ui/settings/MemorySettings.kt"))
        assertInOrder(settings.body("fun startImport()"), "store.locked() ->", "else -> pickImport()")
        assertHas(settings, "ActivityResultContracts.OpenDocument()", "importer.launch(IMPORT_TYPES)")
        assertEquals(listOf("application/json", "*" + "/" + "*"), app.heyrobot.ui.settings.IMPORT_TYPES.toList())
        val run = settings.body("fun runImport(pv: ImportPreview, uri: Uri)")
        assertInOrder(run, "if (store.locked()) {", "store.update {")
        assertHas(run, "store.update { f -> MemoryImport.plan(pv.read, f, mine, now())")
        // The preview: secure first, and the only way to it is runImport's tap.
        val screen = flatCode(kt("app", "ui/settings/MemoryImportScreen.kt")).body("fun MemoryImportScreen(")
        assertTrue(screen.text, screen.text.startsWith("{SecureWindow()"))
        // runImport: its definition and the preview's Import button, nothing else.
        assertEquals(2, settings.count("runImport("))
        // (1.1: and a backup's Add to what's here, core MemoryRestore, with Import's own rules: new ids, content by default.)
        assertEquals(listOf("app/ui/settings/MemorySettings.kt", "core/core/memory/MemoryRestore.kt"), usersOf(Regex("""\bMemoryImport\.(read|plan)\(""")))
        // Content until you say otherwise, and the import's mark is taint.
        val item = app.heyrobot.core.memory.MemoryItem("m_aaaaaaaaaaaa", "x", source = app.heyrobot.core.memory.MemorySource.SAID, createdAt = 1,
            imported = app.heyrobot.core.memory.MemoryImported.CONTENT)
        assertTrue(item.tainted)
        assertTrue(!item.copy(imported = app.heyrobot.core.memory.MemoryImported.YOURS).tainted)
    }

    /**
     * 0.7, messages: the listener's MessageTap and MessagesBridge run in the main process, where the
     * notifications' PendingIntents are; the bridge isn't exported, needs Robot's signature permission and answers Robot's
     * own uid only; the panel reaches it by class name through MessagesLink alone. Message text is never written (no file
     * API in the messages code, History keeps a placeholder), never logged (RLog calls carry codes only), never online (no
     * network code, never a helper's session), never in :brain's or :llama's code (it reaches Robot's model only as a
     * one-shot request with no tools); and a reply's recipient is the notification's own conversation, fired only by the
     * main process after core ReplySend read the notification again.
     */
    @Test fun messagesStayOnThePhone() {
        val doc = parse(mergedManifest())
        val comps = components(doc)
        val bridge = comps["app.heyrobot.people.MessagesBridge"] ?: throw AssertionError("MessagesBridge missing")
        assertEquals("", bridge.getAttributeNS(ns, "process"))
        assertEquals("false", bridge.getAttributeNS(ns, "exported"))
        assertEquals("ai.robot_app.permission.INTERNAL", bridge.getAttributeNS(ns, "permission"))
        assertEquals(0, bridge.getElementsByTagName("intent-filter").length)
        // The listener asks for conversations, never ongoing notifications (Silent is yours to allow in Android's page).
        val listener = comps.getValue("app.heyrobot.people.CheogramListener")
        val meta = listener.getElementsByTagName("meta-data").let { nl -> (0 until nl.length).associate { (nl.item(it) as Element).let { e ->
            e.getAttributeNS(ns, "name") to e.getAttributeNS(ns, "value") } } }
        assertEquals("conversations", meta["android.service.notification.default_filter_types"])
        assertEquals("ongoing", meta["android.service.notification.disabled_filter_types"])
        // Main process only: the tap, the buffer and the bridge; the bridge checks each sender's uid.
        assertEquals(listOf("app/people/CheogramListener.kt", "app/people/MessageTap.kt", "app/people/MessagesBridge.kt"), usersOf(Regex("""\bMessageTap\b""")))
        // A package is read only while signed as when you turned its app on (core MessageSigners): every notification the
        // tap reads goes through its check, and the signers are read in the main process only (Settings, the tap).
        val tap = flatCode(kt("app", "people/MessageTap.kt"))
        assertHas(tap, "if (allow.allows(sbn.packageName) == null || !trusted(ctx, sbn.packageName)) null")
        assertEquals(listOf("app/people/MessageAppSigners.kt", "app/people/MessageTap.kt", "app/ui/settings/MessagesSettings.kt"),
            usersOf(Regex("""\bMessageAppSigners\b""")))
        assertHas(flatCode(kt("app", "people/MessagesBridge.kt")), "m.sendingUid != Process.myUid()")
        assertEquals(listOf("app/assistant/MessagesLink.kt"), usersOf(Regex("""\bMessagesProtocol\.SERVICE_CLASS\b""")))
        assertEquals("app.heyrobot.people.MessagesBridge", app.heyrobot.core.messages.MessagesProtocol.SERVICE_CLASS)
        // 0.8: the announcements ask the bridge for the conversation they announce, and the brief for its counts.
        assertEquals(listOf("app/assistant/MessagesLink.kt", "app/assistant/PanelMessages.kt", "app/assistant/handsfree/HandsFreeMessages.kt",
            "app/assistant/routines/RoutineRunner.kt"), usersOf(Regex("""\bMessagesLink\b""")))
        val ui = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bPanelMessages\b|\bMessagesLink\b"""), ui))
        // The messages code: no file written, nothing online, no helper, no raw log, no engine. (messages.json, Settings ›
        // Messages, is written by MessagesFile.update alone: switches, People ids and the apps' signing keys, never a message.)
        val msg = ours.filterKeys { it.startsWith("core/core/messages/") || it in setOf("app/people/MessageTap.kt", "app/people/MessagesBridge.kt",
            "app/assistant/MessagesLink.kt", "app/assistant/PanelMessages.kt", "app/ui/common/ReplyCard.kt", "app/people/MessageAppSigners.kt",
            "app/assistant/handsfree/HandsFreeMessages.kt") }
        assertTrue(msg.keys.toString(), msg.size >= 16)
        val writes = Regex("""FileOutputStream|RandomAccessFile|Files\.(write|copy|newOutputStream)|writeText|writeBytes|openFileOutput|writeAtomic""")
        assertEquals(listOf("core/core/messages/MessagesFile.kt"), usersOf(writes, msg))
        val mf = flatCode(kt("core", "core/messages/MessagesFile.kt"))
        assertEquals(1, mf.count("writeAtomic("))
        assertHas(mf, "withFileLock(File(file.parentFile, LOCK))", "if (next != now) writeAtomic(file, next.toJson())")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|HttpURLConnection|\bSocket\b|okhttp|\bWebView\b|\bHelper(Session|Link|Http)\b|\bAnthropic(Session|Client)\b|\bapp\.heyrobot\.helpers\b"""), msg))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bLlama|\bBrainClient\b|BrainMsg\.Generate|\blitert"""), msg))
        // Logging: a fixed tag, no other argument (no name, no text, no address); the voice log takes codes and counts.
        assertEquals(emptyList<String>(), usersOf(Regex("""RLog\.[iwe]\([^)\n]*,"""), msg))
        assertEquals(emptyList<String>(), msg.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.[iwe]\(""")).any { it.contains(",") } }.keys.toList())
        // messages.json: settings, People ids and signing keys only, written by the main process (Settings, People's switch, the
        // bridge for "Always for …", the tap's first-use pin), each one change under messages.lock, read from the file.
        assertEquals(listOf("app/people/MessageTap.kt", "app/people/MessagesBridge.kt", "app/ui/settings/MessagesSettings.kt", "app/ui/settings/SettingsNav.kt"),
            usersOf(Regex("""\bMessagesStore\.update\(""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bMessagesStore\.save\(""")))
        assertHas(flatCode(kt("app", "sys/MessagesStore.kt")), "MessagesFile.update(file, change)")
        // History: a turn with messages in it is saved as its placeholder, never its words (core ConversationCodec; core
        // MessageTurnTest too).
        val held = app.heyrobot.core.ChatTurn("r1", app.heyrobot.core.TurnRole.ASSISTANT, "Mom wrote: the door code is 4471", 1L, messages = true)
        val saved = app.heyrobot.core.ConversationCodec.toJson(app.heyrobot.core.StoredConversation("c", 0, 1, listOf(held)))
        assertTrue(saved, !saved.contains("4471"))
        assertEquals(app.heyrobot.core.messages.MessageReading.NOT_KEPT, app.heyrobot.core.ConversationCodec.fromJson(saved)!!.turns.single().text)
        val pc = flatCode(kt("app", "assistant/PanelController.kt"))
        assertHas(pc, "messages = kept != null, kept = kept)")
        // A message's words heard back while Robot read it: the question is kept (and replayed) as its placeholder only.
        assertHas(pc, "val heardBack = msgCmd is PanelMessages.Routed.Echo",
            "messages = heardBack, kept = if (heardBack) app.heyrobot.core.messages.MessageReading.HEARD_BACK else null)")
        // A reply: fired only by the bridge, after ReplySend read the notification again; the panel names a conversation by its key, and
        // its words, never an address.
        assertEquals(listOf("app/people/MessageTap.kt", "app/people/MessagesBridge.kt"), usersOf(Regex("""\bMessageTap\.fire\(|\bfun fire\(ctx""")))
        // A mark-as-read the app wants only from an unlocked phone is never fired while it is locked, as a reply isn't.
        assertHas(flatCode(kt("app", "people/MessageTap.kt")), "if (!app.heyrobot.core.messages.ReplyGuard.markable(ref, app.heyrobot.sys.Assistant.locked(ctx))) return false")
        assertHas(flatCode(kt("app", "people/MessagesBridge.kt")), "ReplySend.check(held, fresh?.second, req, s, locked)", "MessageTap.fire(ctx, fresh!!.first, c.target, c.text)")
        val panel = flatCode(kt("app", "assistant/PanelMessages.kt"))
        assertEquals(1, panel.count(Regex("""ReplyRequest\(""")))
        assertHas(panel, "ReplyRequest(p.thread.key, plan.text, plan.newestSeen)")
        // Sent once per card: fire() goes on only from a waiting card (or its 3 seconds), and marks it sending before the bridge.
        val fire = panel.body("private suspend fun fire(")
        assertInOrder(fire, "if (p.state != ReplyCardState.WAITING && p.state != ReplyCardState.STOP_WINDOW) return", "p.state = ReplyCardState.SENDING")
        assertInOrder(panel, "p.state = ReplyCardState.SENDING", "link().reply(ReplyRequest(")
        // A "reply …" that named no one, with nothing read here, went to the newest conversation: never without a tap or a yes.
        assertHas(panel, "p.guessed = to == null && lastRead == null", "noCard, sure = !p.guessed)")
        // A reply is about messages only after one was read here, or to someone a conversation or People knows (core MessagesRouter.applies).
        assertHas(panel, "knows = { MessagePeople.known(it, threads, people) }")
        // A spoken yes on a card waits 3 seconds after Robot said a message's words; a tap doesn't.
        assertHas(panel, "if (fromVoice && !MessageQuarantine.voiceYesNow(heard(System.currentTimeMillis()), System.currentTimeMillis()))")
        // Your messages' words leave this process when the panel hides and after 2 minutes idle; the router keeps names only.
        assertHas(panel, "stopWindow(CardEnd.CLOSED) dropText()", "const val IDLE_DROP_MS = 120_000L", "threads = all.map(::withoutText)", "PanelController.messagesDropText()")
        // In a reply's 3 seconds, anything said but a yes stops it (core ReplyWindow).
        assertHas(panel, "return when (app.heyrobot.core.messages.ReplyWindow.answer(q, wakes))")
        // Robot's model sees messages only through one-shot requests (no tools); every turn of the panel carries the quarantine.
        assertHas(panel, "BrainOneShots.run(BrainMsg.OneShot(")
        // Whatever of a message Robot says aloud counts for the echo check: the words read (or their translation), a summary,
        // "Mom wrote again: «…»", and a draft from her messages.
        assertHas(flatText(kt("app", "assistant/PanelMessages.kt")), "saidAloud(page.heard.joinToString(\"\"))")
        assertHas(panel, "saidAloud(summary)", "saidAloud(said)", "if (p.sendSource == ReplySource.MODEL_DRAFT) saidAloud(p.sendText)")
        // (Seven: the chat turn, a helper's, the Claude app's hand-off, texting without the model, 0.7 memory's "remember
        // that …" in a conversation that holds a message, 0.7.0's "free up memory" card, 0.8's routine steps and calls, and 0.9's
        // "search the web for …" and "run <script>"; 1.4 F10's Add to calendar from a file's date.)
        assertEquals(10, pc.count("messages = PanelMessages.taint(chat)"))
        assertEquals(pc.count(Regex("""TurnRunner\(""")), pc.count("messages = PanelMessages.taint(chat)"))
        // (1.1: a helper's turn and the hand-off to its app are PanelHelperTurn's; their TurnRunners stay here, untrusted and
        // remote with only what HelperTrust lets a read share and the tools the session offered, and the shared port hands
        // PanelHelperTurn exactly these two.)
        assertHas(pc.between("private suspend fun helperRun(", ".run()"), "TurnRunner(rounds, PanelActions, PanelView(replyId, h.name), slot.confirmations, " +
            "untrusted = true, remote = true, asked = asked, trusted = HelperTrust.reads(s, h), offered = session.offered, messages = PanelMessages.taint(chat)")
        assertHas(pc.between("private suspend fun handOffRun(", ".runHandOff()"), "TurnRunner(scripted, PanelActions, PanelView(replyId), slot.confirmations, " +
            "untrusted = false, messages = PanelMessages.taint(chat)")
        assertHas(pc, "asked: Set<ToolName>, session: HelperSession) = this@PanelController.helperRun(replyId, rounds, h, s, asked, session)",
            "override suspend fun handOffRun(replyId: String, model: TurnModel) = this@PanelController.handOffRun(replyId, model)")
        assertHas(flatCode(kt("app", "assistant/PanelHelperTurn.kt")), "port.helperRun(replyId, rounds, h, s, asked, session)", "val r = port.handOffRun(replyId, scripted)")
        // Nothing of messages in :brain or :llama's code.
        assertEquals(emptyList<String>(), usersOf(Regex("""\bapp\.heyrobot\.core\.messages\b"""), ours.filterKeys { it.startsWith("brain/") || it.startsWith("mt/") || it.startsWith("helpers/") }))
    }

    /**
     * 0.8, 0.7's messages in the brief, the headset and the car. The main process tells :voice only which conversation has
     * a new message and whether Do Not Disturb lets it through (never who or what); :voice asks the bridge for it as any
     * read does. 0.7's router keeps its echo check first, then lets an announcement's answer go to HandsFreeMessages; every
     * piece of a message the panel says passes OutputGuard in a session; an announcement plays only after OutputGuard says
     * so; the car sends a reply only after the read-back, only where 0.7's rules let a spoken yes send it, and only through
     * the bridge; any other reply goes to the screen's own reply card, as outside the car; the brief gets counts by app only.
     */
    @Test fun messagesHandsFree() {
        val tap = flatCode(kt("app", "people/MessageTap.kt"))
        // What tells :voice of a new message: the conversation's key and Do Not Disturb's answer, nothing else.
        val sent = tap.body("Cmd.send(ctx, Cmd.MESSAGE_NEW, android.os.Bundle().apply {")
        assertEquals(Scan.flat("{ putString(Cmd.EXTRA_KEY, t.key) putBoolean(Cmd.EXTRA_DND, dndAllows(sbn)) }"), sent.text)
        assertHas(tap, "if (s.announce && app.heyrobot.core.messages.MessageArrival.isNew(before, t))")
        assertEquals(listOf("app/assistant/VoiceHub.kt", "app/people/MessageTap.kt", "app/sys/Stores.kt"), usersOf(Regex("""\bCmd\.MESSAGE_NEW\b|const val MESSAGE_NEW\b""")))
        val pm = flatCode(kt("app", "assistant/PanelMessages.kt"))
        assertInOrder(pm, "if (MessageQuarantine.originOf(q, heard(now), now) == ActionOrigin.INCOMING_TEXT) return Routed.Echo(",
            "if (app.heyrobot.assistant.handsfree.HandsFreeMessages.waiting) return null")
        assertHas(pm, "if (!guarded && !app.heyrobot.assistant.handsfree.PanelHandsFree.allow(clean) { true }) continue")
        assertEquals(1, pm.count(Regex("""speak\([^)]*guarded=true\)""")))
        // 1.0 (the user, 13 Oct 2026: no driving restrictions): in the car a card only a tap sends, or a chat Robot would
        // open, comes now, exactly as outside the car; nothing in 0.7's reply looks at car mode.
        assertFalse(pm.text.contains("parkInCar") || pm.text.contains("inCar") || pm.text.contains("KEEP_FOR_PARK"))
        val hf = flatCode(kt("app", "assistant/handsfree/HandsFreeMessages.kt"))
        assertHas(hf, "if (!s.on || !s.announce) return", "if (phone.locked && s.locked != MessagesLocked.READ_AND_REPLY) return log(AnnounceCode.LOCKED)",
            "val ok = when (val d = OutputGuard.decide(u, ctx, guard))")
        // The car only while it is there (car mode for no car, or its last 30 s after the car went, needs private
        // headphones); the car's "Read it aloud?" never stands in for a headset's "Is it headphones only you can hear?".
        assertHas(hf, "val car = CarModeController.on && CarModeController.carSeen() != null",
            "is OutputDecision.Ask -> f.car && !f.readNow && s.kind == SessionKind.CAR && d.what == AskWhat.READ_ALOUD",
            "HandsFreeReplies.of(held, s, phone.locked, MessagePeople.withoutCard(held, people, f.noCard))",
            "if (st.step == MessageStep.READ_BACK && !PanelMessages.voiceYesNow())")
        // The only reply it sends is the one MessageFlow read back and you confirmed, through the main process's bridge.
        assertEquals(1, hf.count("PanelMessages.handsFreeReply("))
        assertHas(hf, "is FlowEffect.Send -> { val answer = PanelMessages.handsFreeReply(ReplyRequest(e.conversationKey, e.text, f.thread.newestIncoming))")
        assertEquals(listOf("app/assistant/PanelMessages.kt", "app/assistant/handsfree/HandsFreeMessages.kt"), usersOf(Regex("""\bhandsFreeReply\(""")))
        // What the car doesn't send by voice goes to the panel's own reply (its card, tap, chat or refusal) for the
        // conversation the car announced, with exactly your words; it never sends anything itself. So does a reply the
        // Reply action didn't take (but for the lock screen's refusal).
        assertHas(hf, "is FlowEffect.OnScreen -> {")
        assertEquals(2, hf.count("PanelMessages.handsFreeOnScreen(replyId, f.thread, e.text, msgLang(lang))"))
        assertHas(hf, "if (r != ReplyResult.HANDED_OVER && r != ReplyResult.LOCKED) {")
        assertEquals(listOf("app/assistant/PanelMessages.kt", "app/assistant/handsfree/HandsFreeMessages.kt"), usersOf(Regex("""\bhandsFreeOnScreen\(""")))
        assertHas(pm, "run(replyId, Routed.Command(MessageCommand.Reply(null, words, lang)))")
        // New texts in the car go on as outside the car (the panel's own text flow), and a call that can't be decided by
        // voice shows the call tool's own card, as a typed "call Mom".
        val phf = flatCode(kt("app", "assistant/handsfree/PanelHandsFree.kt"))
        assertHasNot(phf, "Cmd.Park", "newText(", "TextCommand")
        assertHas(phf, "is FlowEffect.CallCard -> {", "host.tool(replyId, RoutineStep.Tool(Action.Call(callAsked)))")
        // Nothing anywhere keeps, parks or refuses because you are driving.
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(ParkItem|ParkCode|ParkList|ParkFile|editParked|holdWhileDriving|NewTexts|newTexts|KEEP_FOR_PARK|DRIVING_HOLD|NO_HELPER_APP_DRIVING|ACTION_PARKED|ARG_PARKED|parkInCar|keepReply|ParkNotice)\b""")))
        // The brief: counts by app, never a thread, from the main process.
        assertHas(flatCode(kt("app", "people/MessagesBridge.kt")), "MessagesProtocol.REQ_UNREAD -> app.heyrobot.core.messages.MessageUnread.toJson(")
        val runner = flatCode(kt("app", "assistant/routines/RoutineRunner.kt"))
        assertHas(runner, "link.unread()")
        assertHasNot(runner, "link.list()")
    }

    /**
     * 0.7, memory and messages together (core MessageQuarantine, MemoryAndMessagesTest): message text never becomes memory by
     * itself and memory never goes with a message's one-shot request. The panel's messages code and core's messages never
     * open memory, put it in a prompt or write it; memory is written only by MemoryPanel (the router's "remember that …"
     * and the remember tool after the gate) and Settings › Memory; your "remember that …" in a conversation that holds a
     * message goes through the turn runner as your words before memory's router could save it; a message's words heard back
     * are caught before memory's router sees them; suggestions aren't made from any conversation yet (phase 7.5 takes one
     * through Suggestions.turnsOf, which gives nothing for a conversation that held a message); notes kept from content
     * make a chat turn content.
     */
    @Test fun memoryAndMessagesTogether() {
        val messagesCode = ours.filterKeys { it.startsWith("core/core/messages/") || it in setOf("app/people/MessageTap.kt", "app/people/MessagesBridge.kt",
            "app/assistant/MessagesLink.kt", "app/assistant/PanelMessages.kt", "app/ui/common/ReplyCard.kt", "app/ui/settings/MessagesSettings.kt") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\bMemory(Panel|Files|Vault|SettingsStore|Recall|Prompt|Edits|Search|Turn)\b|\bPromptPacking\b|\bFileMemoryVault\b"""),
            messagesCode))
        // Core's messages use memory's words only to read your command (MemoryRoute, MemoryCommand) and its tokenizer.
        assertEquals(listOf("core/core/messages/MessageTaint.kt"), usersOf(Regex("""\bapp\.heyrobot\.core\.memory\b"""), messagesCode))
        // Memory is written in two places only: the panel's MemoryPanel and Settings › Memory.
        assertEquals(listOf("app/assistant/MemoryPanel.kt", "app/ui/settings/MemorySettings.kt"), usersOf(Regex("""\bMemoryEdits\.(add|update|edit)\(""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bSuggestions\.(request|accept|userWords|turnsOf)\("""), ours.filterKeys { it.startsWith("app/") }))
        val pc = flatCode(kt("app", "assistant/PanelController.kt"))
        // The panel's order: the messages router and its echo check (a message's words heard back, PanelMessages.route)
        // first, then conversation mode, Send feedback's phrases (a message read aloud saying "report a bug: …" is the
        // message's), 0.8's routines and headset and car mode, 0.9's web search ("search the web for …") and Termux ("run
        // <script>"), memory, "free up memory", the helpers, and the model. Each router runs only when the ones before it
        // didn't take the question.
        assertInOrder(pc,
            "val msgCmd = if (q.isNotEmpty() && st.draft == null) PanelMessages.route(q, fromVoice) else null",
            "val convCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null) ConversationRouter.route(q, ConversationSession.running) else null",
            "val fbCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null) FeedbackRouter.route(q) else null",
            "val routineCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !_state.value.inHelperMode) " +
                "PanelRoutines.route(q, TurnRules.untrusted(chat), fresh = chat.none { it.role == TurnRole.USER }) else null",
            // 1.1: skills' phrases right after the routines', under their conditions (and after the phone lock's).
            "val skillCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null && " +
                "!_state.value.inHelperMode) PanelSkills.route(q) else null",
            // 1.4: "file this" (PanelFiles, files-1.4-plan.md D15) after the skills' phrases and before headset and car mode, with an
            // attachment too (it files what the panel holds), and never a helper's conversation's.
            "val fileCmd = if (q.isNotEmpty() && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null && skillCmd == null && " +
                "!_state.value.inHelperMode) PanelFiles.route(q) else null",
            "val handsFreeCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && routineCmd == null && skillCmd == null && " +
                "fileCmd == null && !_state.value.inHelperMode) PanelHandsFree.route(q) else null",
            "val routineOrHandsFree = routineCmd != null || skillCmd != null || handsFreeCmd != null || fileCmd != null",
            "val webQuery = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree && " +
                "!_state.value.inHelperMode) BrowserRouter.route(q) else null",
            "val termux = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && " +
                "!_state.value.inHelperMode) TermuxTool.direct(VoiceHub.app, q, typed = !fromVoice) else null",
            "val memRoute = if (q.isNotEmpty() && st.draft == null && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && " +
                "termux == null) MemoryPanel.route(q, Assistant.locked(VoiceHub.app)) else null",
            "val freeAsk = if (q.isNotEmpty() && st.draft == null && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && " +
                "termux == null && memRoute == null) FreeUp.asked(q) else null",
            "var cmd = if (q.isNotEmpty() && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && " +
                "termux == null && memRoute == null && freeAsk == null) HelperRouter.route(q, names) else null")
        assertInOrder(pc, "msgCmd != null -> PanelMessages.run(replyId, msgCmd)", "convCmd != null -> conversationMode.run(", "fbCmd != null -> feedback.run(replyId, fbCmd)",
            "routineCmd != null -> PanelRoutines.handle(", "skillCmd != null -> {", "PanelSkills.handle(replyId, skillCmd, skillHost",
            "fileCmd != null -> withContext(Dispatchers.IO) { PanelFiles.file(VoiceHub.app, fileCmd, fileWhat!!, turnLangs, spoken = fromVoice) }", "handsFreeCmd != null -> PanelHandsFree.handle(", "webQuery != null -> webSearch.direct(",
            "termux != null -> termuxScript.run(", "memRoute != null -> memoryCommand.run(replyId, memRoute, fromVoice)", "freeAsk != null -> freeMemory.run(")
        // "Remember that …" after a message: the quarantine's card, before memory's router saves anything (1.1: the panel's memory
        // command is PanelMemoryCommand's; the card's turn, with your words' origin, and the lock it reads stay PanelController's).
        val memCmd = flatCode(kt("app", "assistant/PanelMemoryCommand.kt"))
        assertInOrder(memCmd, "MessageQuarantine.rememberAfterMessages(route, PanelMessages.taint(chat))", "port.memoryCardTurn(replyId, after.call)",
            "MemoryPanel.command(route, port.locked(), spoken, langs = turnLangs)")
        assertHas(pc, "override fun locked() = Assistant.locked(VoiceHub.app)",
            "override suspend fun memoryCardTurn(replyId: String, call: RawToolCall) = this@PanelController.memoryCardTurn(replyId, call)")
        assertHas(pc.between("private suspend fun memoryCardTurn(replyId: String, call: RawToolCall): TurnResult =", ".run()"),
            "TurnRunner(DirectCall(call), PanelActions, PanelView(replyId), slot.confirmations, untrusted = true,",
            "asked = setOf(ToolName.REMEMBER), messages = PanelMessages.taint(chat), origin = ActionOrigin.USER_WORDS")
        assertHas(pc, "asked = setOf(ToolName.REMEMBER), messages = PanelMessages.taint(chat), origin = ActionOrigin.USER_WORDS")
        // The only other origin is the model's (the default): nothing else is given your words' standing.
        assertEquals(1, pc.count("origin = ActionOrigin."))
        // Notes kept from content make the chat turn content; a remembered item in a conversation with a message is content.
        assertHas(pc, "untrusted || mem.content,", "== true, turnMemContent), PanelMessages.taint(chat).active)")
        // The turn runner refuses what the quarantine forbids before anything is prepared or asked: core
        // MemoryAndMessagesTest.theQuarantineRefusesBeforeAnythingIsPrepared runs it.
    }

    /**
     * 1.1 backups: Robot's copy of Bouncy Castle's Argon2 (core's
     * app.heyrobot.thirdparty.bouncycastle: the generator, BLAKE2b and their helpers, 20 files; no Bouncy Castle library in
     * any build) is used by core's BackupKdf.kt alone; no code registers a security provider, so every cipher stays the
     * platform's (BackupStream's AES-GCM through JCA); and the backup's core code never goes online, logs or touches Android.
     */
    /**
     * 1.1, Back up now (docs/plans/backup-1.1-design.md §6, §8): the window is in :voice, not exported, never over the lock
     * screen, FLAG_SECURE from onCreate; the passphrase is never saved state; the backup code has no network and writes
     * the log in codes; it never names the updater's token, the apps' signer pins or Cheogram's signer; the main process
     * opens it by class name and never reads a key, the voiceprint or the passphrase.
     */
    @Test fun backupsStayInTheirWindow() {
        val comps = components(parse(mergedManifest()))
        val w = comps.getValue("app.heyrobot.assistant.backup.BackupActivity")
        assertEquals(":voice", w.getAttributeNS(ns, "process"))
        assertEquals("false", w.getAttributeNS(ns, "exported"))
        assertEquals("true", w.getAttributeNS(ns, "excludeFromRecents"))
        assertEquals("", w.getAttributeNS(ns, "showWhenLocked"))
        assertEquals("", w.getAttributeNS(ns, "taskAffinity"))
        assertEquals(0, w.getElementsByTagName("intent-filter").length)
        val window = flatCode(kt("app", "assistant/backup/BackupActivity.kt"))
        assertHas(window.body("override fun onCreate("), "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        assertHas(window, "properties = SecureDialog", "NoImeLearning {")
        // The 15 Oct decision (§16.5): the passphrase fields let a password manager fill and save them, so the window
        // doesn't keep its views out of autofill (as WebSearchActivity's key field does); everything else stays.
        assertHas(window, "semantics { contentType = type }", "ContentType.NewPassword", "autofill?.commit()")
        assertTrue(!Regex("""IMPORTANT_FOR_AUTOFILL_NO""").containsMatchIn(code(kt("app", "assistant/backup/BackupActivity.kt"))))
        val backup = ours.filterKeys { it.startsWith("app/assistant/backup/") }
        assertEquals(listOf("app/assistant/backup/BackupActivity.kt", "app/assistant/backup/BackupJob.kt", "app/assistant/backup/BackupSecrets.kt",
            "app/assistant/backup/BackupSources.kt", "app/assistant/backup/RestoreFlow.kt", "app/assistant/backup/RestoreWriter.kt"), backup.keys.sorted())
        // No saved state, and the only extra any of it sends is the page the app opens on after a restore.
        assertEquals(emptyList<String>(), usersOf(Regex("""\brememberSaveable\b|\bonSaveInstanceState\b|\bputExtra\((?!AppPages\.EXTRA, AppPages\.BACKUP\))"""), backup))
        // The passphrase field of a restore is offered to a password manager too (Password), still without keyboard learning.
        assertHas(flatCode(kt("app", "assistant/backup/RestoreFlow.kt")), "ContentType.Password", "NoImeLearning {")
        // The restart after a restore (§7.12): only the main process and :voice itself end, never :lock. (1.4: :files ends
        // only itself, as :llama does: filesAreReadOnlyInTheirProcess.)
        assertEquals(listOf("app/assistant/backup/RestoreFlow.kt", "app/parse/FileParseService.kt"),
            usersOf(Regex("""\bkillProcess\("""), ours.filterKeys { it.startsWith("app/") }))
        assertHas(flatCode(kt("app", "assistant/backup/RestoreFlow.kt")), "filter { it.processName == ctx.packageName }")
        // Phone lock's code is checked by :lock's code pad only: a restore asks it with its own purpose, which does nothing else.
        assertHas(flatCode(kt("app", "assistant/backup/RestoreFlow.kt")), "LockSignals.code(ctx, LockSignals.PURPOSE_RESTORE)")
        assertEquals(emptyList<String>(), usersOf(Regex("""\bLockCodeVault\b"""), backup))
        val pad = flatCode(kt("app", "lock/LockCodeActivity.kt"))
        assertHas(pad, "LockSignals.PURPOSE_SET, LockSignals.PURPOSE_REMOVE, LockSignals.PURPOSE_RESTORE)")
        assertTrue(!Regex("""PURPOSE_RESTORE""").containsMatchIn(pad.body("private fun right(").text))
        assertHas(flatCode(kt("app", "assistant/backup/RestoreWriter.kt")), "lockCode = LockStore.readState(ctx).codeSet")
        // The updater's settings are merged by the main app (sys/RestoreApply), never read in :voice (theUpdaterStays…).
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|okhttp|\bDownloadManager\b|HttpURLConnection"""), backup))
        assertEquals(emptyList<String>(), usersOf(Regex("""\bUpdateToken\b|robot_github_token|\bMessageSigners\b|\bCheogramSigners\b|\bVoiceprintFiles\.file\b"""), backup))
        // Logs: codes and the exception's class only (RLog.w with a throwable), the voice log's event 240 with numbers.
        assertEquals(emptyList<String>(), backup.filter { (_, f) -> Flat(code(f)).calls(Regex("""\bRLog\.i\(""")).isNotEmpty() }.keys.toList())
        assertHas(flatCode(kt("app", "assistant/backup/BackupJob.kt")), "VoiceLog.add(VoiceEvent.BACKUP, code.id, BackupExport.bits(categories), (bytes / (1024 * 1024)).toInt())")
        // Keys and the voiceprint: only BackupSecrets reads one.
        assertEquals(listOf("app/assistant/backup/BackupSecrets.kt"), usersOf(Regex("""\b(HelperKeys|WebKeys|Voiceprints)\b"""), backup))
        // The main process opens the window by its name, a string (sys/BackupFiles.kt), and never names the backup's classes.
        assertHas(flatText(kt("app", "sys/BackupFiles.kt")), "const val CLASS = \"app.heyrobot.assistant.backup.BackupActivity\"")
        val mainCode = ours.filterKeys { it.startsWith("app/ui/") || it.startsWith("app/sys/") }
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(BackupSources|BackupSecrets|BackupJob|BackupKdf|BackupWriter|RestoreSession|RestoreReader)\b"""), mainCode))
        // The main app writes what :voice staged for it (settings files, locks.json) at its start, and nothing else of a restore.
        assertHas(flatCode(File(java("app"), "app/heyrobot/RobotApp.kt")), "if (!p.contains(':')) app.heyrobot.sys.RestoreApply.recover(this)")
    }

    @Test fun backupsUseBouncyCastlesArgon2OnlyAndNoProvider() {
        val copy = ours.filterKeys { it.startsWith("core/thirdparty/bouncycastle/") }
        assertEquals(20, copy.size)
        assertTrue(copy.keys.toString(), copy.keys.all { it.startsWith("core/thirdparty/bouncycastle/crypto/") || it.startsWith("core/thirdparty/bouncycastle/util/") })
        assertEquals(listOf("core/core/backup/BackupKdf.kt"), usersOf(Regex("""\bapp\.heyrobot\.thirdparty\b"""), ours - copy.keys))
        assertEquals(emptyList<String>(), usersOf(Regex("""\borg\.bouncycastle\b""")))
        assertEquals(emptyList<String>(), usersOf(Regex("""\b(addProvider|insertProviderAt)\(|BouncyCastleProvider|\bSecurity\.""")))
        assertHas(flatCode(kt("core", "core/backup/BackupKdf.kt")), "Argon2BytesGenerator()")
        for (m in modules) assertTrue(m, !gradleRaw(m).contains("bouncycastle"))
        val dir = File(java("core"), "app/heyrobot/thirdparty/bouncycastle")
        assertTrue(File(dir, "LICENSE").isFile && File(dir, "NOTICE").isFile)
        // 1.4 (files-1.4-plan.md §2.3): the STREAM moved to core/seal/SealStream.kt, which Robot Files shares; the backup's
        // writer and reader wrap it (core BackupStreamPinTest: the same bytes and verdicts as 1.1's own code).
        assertHas(flatCode(kt("core", "core/seal/SealStream.kt")), "Cipher.getInstance(")
        assertHas(flatCode(kt("core", "core/backup/BackupStream.kt")), "SealWriter(out, aad, k, allowEmpty = false", "SealReader(input, header.encode(), k, allowEmpty = false")
        val backup = ours.filterKeys { it.startsWith("core/core/backup/") || it.startsWith("core/core/seal/") }
        assertTrue(backup.size >= 11)
        assertEquals(emptyList<String>(), usersOf(Regex("""\bjava\.net\b|okhttp|\bRLog\b|\bVoiceLog\b|\bandroid\."""), backup))
    }
}
