// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.core.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The spoken tests' build type against the code that ships. What a release may hold of it is one empty hook, FrameFeed (and the
 * router's name PanelController keeps for it): tools/verify_apk.sh checks the built APK, this checks the source and the manifests.
 */
class SpokenBuildTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val gradle by lazy { File(root, "app/build.gradle").readText() }
    private val modules = listOf("app", "core", "voice", "brain", "helpers", "mt")

    /** Every source a release build compiles. */
    private val shipped: Map<String, File> by lazy {
        modules.flatMap { m ->
            File(root, "$m/src").listFiles().orEmpty().filter { it.isDirectory && it.name != "spoken" && !it.name.startsWith("test") && !it.name.contains("ndroidTest") && it.name != "debug" }
                .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.map { "$m/${it.relativeTo(set).path}" to it }.toList() }
        }.toMap()
    }

    private fun code(f: File) = Source.strip(f.readText(), keepStrings = false, kotlin = f.extension == "kt")
    private fun users(r: Regex) = shipped.filter { r.containsMatchIn(code(it.value)) }.keys.map { it.substringAfterLast('/') }.sorted()
    private fun manifest(variant: String): String {
        val f = File(root, "app/build/intermediates/merged_manifest/$variant/process${variant.replaceFirstChar(Char::uppercase)}MainManifest/AndroidManifest.xml")
        assertTrue("no merged manifest at $f", f.isFile)
        return f.readText()
    }
    private fun variant() = System.getProperty("robot.variant") ?: "release"
    private fun tag(xml: String, kind: String, name: String) = Regex("""<$kind\b[^>]*>""").findAll(xml).map { it.value }.firstOrNull { "android:name=\"$name\"" in it }
    private fun names(xml: String, kind: String) = Regex("""<$kind\b[^>]*?android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    @Test fun spokenHarnessNeverInRelease() {
        assertTrue("no sources found", shipped.size > 200)
        // The hook: read in one place, declared in one, and nothing that ships can install a feed.
        assertEquals(listOf("FrameFeed.kt", "Listener.kt"), users(Regex("""\bFrameFeed\b""")))
        assertEquals(emptyList<String>(), users(Regex("""\bFrameFeed\.current\s*=[^=]""")))
        val hook = shipped.getValue(shipped.keys.first { it.endsWith("voice/FrameFeed.kt") })
        assertEquals("the hook's only member besides read is the empty slot", 1, Regex("""\bvar\b""").findAll(code(hook)).count())
        assertTrue(Regex("""var current: FrameFeed\? = null""").containsMatchIn(code(hook)))
        val listener = code(shipped.getValue(shipped.keys.first { it.endsWith("voice/Listener.kt") }))
        assertEquals(1, Regex("""\bFrameFeed\b""").findAll(listener).count())
        assertTrue(listener.contains("FrameFeed.current?.read(buf, FRAME) ?: r.read(buf, 0, FRAME)"))
        // Nothing that ships names the test build's code, in code or strings.
        assertEquals(emptyList<String>(), shipped.filter { (_, f) -> Regex("""app\.heyrobot\.spoken|\bSpoken(Receiver|Harness|Results|Recorder|Importer)\b""").containsMatchIn(f.readText()) }.keys.toList())
        // Nor do a release's manifest or its build type.
        val release = manifest(variant())
        for (no in listOf("SpokenReceiver", "SpokenRecorder", "ai.robot_app.spoken")) assertFalse(no, release.contains(no))
        // AndroidX's profile installer is guarded by DUMP in every build (as verify_apk.sh knows); nothing else may be.
        val dump = Regex("""<\w+\b[^>]*"android\.permission\.DUMP"[^>]*>""").findAll(release).map { it.value }.toList()
        // P1: and, in a debug build only, the Wikipedia reader's phone check (wiki/src/debug), sent by the shell alone.
        val debugOnly = if (variant() == "release") emptyList() else listOf("app.heyrobot.wiki.WikiCheckReceiver")
        assertEquals(dump.toString(), emptyList<String>(), dump.filter { d -> "androidx.profileinstaller.ProfileInstallReceiver" !in d && debugOnly.none { it in d } })
        if (variant() == "release") assertFalse(release.contains("WikiCheckReceiver"))
        val builds = gradle.substringAfter("buildTypes {").substringBefore("\n    }\n")
        assertEquals(listOf(".spoken"), Regex("""applicationIdSuffix\s+'([^']+)'""").findAll(gradle).map { it.groupValues[1] }.toList())
        assertTrue(builds.substringAfter("spoken {").substringBefore('}').contains("applicationIdSuffix '.spoken'"))
        assertFalse(builds.substringAfter("release {").substringBefore("spoken {").contains("spoken'"))
        // The test build's folder reaches only its own build and the debug tests.
        assertEquals(setOf("spoken", "testDebug"), Regex("""(\w+) \{ java\.srcDirs \+= 'src/spoken/logic' \}""").findAll(gradle).map { it.groupValues[1] }.toSet())
        assertEquals(2, Regex("""src/spoken""").findAll(gradle.substringAfter("sourceSets {").substringBefore("\n    }\n")).count())
    }

    @Test fun theRouterNameStaysInThePanelAndOutOfEveryLogAndFile() {
        assertEquals(listOf("PanelController.kt"), users(Regex("""\blastRouter\b""")))
        val panel = code(shipped.getValue(shipped.keys.first { it.endsWith("assistant/PanelController.kt") }))
        assertEquals("declared and set, never read: the test build reads it", 2, Regex("""\blastRouter\b""").findAll(panel).count())
    }

    /** ToolRouter and PreRouter don't take a turn for themselves: what they pass on is the model's, in the list as `model`. */
    @Test fun everyRouterTheRunnerNamesIsSaid() {
        val cfg = File(root, "tools/spoken/spoken.json").readText().substringAfter("\"routers\":").substringBefore(']')
        val named = Regex(""""(\w+)"""").findAll(cfg).map { it.groupValues[1] }.toSet() - setOf("ToolRouter", "PreRouter")
        val panel = File(root, "app/src/main/java/app/heyrobot/assistant/PanelController.kt").readText()
        val set = panel.substringAfter("lastRouter = listOf(").substringBefore("offers.drop()")
        val said = Regex(""""(\w+)"""").findAll(set).map { it.groupValues[1] }.toSet()
        assertEquals(named, said)
        // A new command router in send() must be said too: every `xxxCmd` val has its entry.
        val send = panel.substringAfter("fun send(").substringBefore("lastRouter = listOf(")
        for (v in Regex("""\bval (\w+Cmd)\b""").findAll(send).map { it.groupValues[1] }.filter { it != "everydayCmd" }) assertTrue("$v has no router name", Regex("""\b$v to """).containsMatchIn(set))
    }

    @Test fun theTestBuildsManifestIsGuarded() {
        val xml = manifest("spoken")
        assertTrue(xml.contains("package=\"ai.robot_app.spoken\""))
        val r = tag(xml, "receiver", "app.heyrobot.spoken.SpokenReceiver")!!
        assertTrue(r, r.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(r, r.contains("android:process=\":voice\""))
        val a = tag(xml, "activity", "app.heyrobot.spoken.SpokenRecorderActivity")!!
        assertTrue(a, a.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue("a recording in no backup", xml.contains("android:allowBackup=\"false\""))
        // It installs beside Robot only if nothing it declares clashes with Robot's: a clash fails at install, on the phone.
        val theirs = Regex("""android:authorities="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(theirs.isNotEmpty())
        for (a in theirs) assertTrue(a, a.startsWith("ai.robot_app.spoken"))
        for (p in names(xml, "permission")) assertTrue(p, p.startsWith("ai.robot_app.spoken"))
    }

    @Test fun theTestBuildAsksForNoMoreThanRobotDoes() {
        val other = manifest(variant())
        val spoken = manifest("spoken")
        val normal = { s: Set<String> -> s.map { it.replace("ai.robot_app.spoken", "ai.robot_app") }.toSet() }
        assertEquals(names(other, "uses-permission"), normal(names(spoken, "uses-permission")))
        assertFalse(names(spoken, "uses-permission").contains("android.permission.DUMP"))
        // Its only window more than Robot's. It isn't in LockGate, so no name of it reaches a release; this build never has a Phone lock on.
        assertEquals(setOf("app.heyrobot.spoken.SpokenRecorderActivity"), names(spoken, "activity") - names(other, "activity"))
        // P1: a debug build's Wikipedia check (wiki/src/debug) is the test build's too, never a release's.
        val wikiCheck = if (variant() == "release") setOf("app.heyrobot.wiki.WikiCheckReceiver") else emptySet()
        assertEquals(setOf("app.heyrobot.spoken.SpokenReceiver") + wikiCheck, names(spoken, "receiver") - names(other, "receiver"))
    }

    @Test fun theTestBuildsShortcutsAreMainsWithItsId() {
        val strip = { s: String -> s.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""\s+"""), " ").trim() }
        val main = strip(File(root, "app/src/main/res/xml/shortcuts.xml").readText())
        val test = strip(File(root, "app/src/spoken/res/xml/shortcuts.xml").readText())
        assertTrue(main.contains("targetPackage=\"ai.robot_app\""))
        assertEquals(main.replace("targetPackage=\"ai.robot_app\"", "targetPackage=\"ai.robot_app.spoken\""), test)
    }

    @Test fun theUpdaterIsOffOutsideRelease() {
        val up = code(File(root, "app/src/main/java/app/heyrobot/update/Updater.kt"))
        assertEquals("check, download and install each stop", 3, Regex("""AppId\.updatesAllowed\(""").findAll(up).count())
        assertTrue(File(root, "app/src/main/java/app/heyrobot/RobotApp.kt").readText().contains("AppId.bind(BuildConfig.APPLICATION_ID)"))
    }

    /** The guard is the test build's own settings file: a release's defaults stay, and its gate lets them run at once. */
    @Test fun whatTheTestBuildAsksARobotDoesAtOnce() {
        val s = app.heyrobot.core.RobotSettings()
        for (t in listOf(ToolName.SET_TIMER, ToolName.SET_ALARM, ToolName.OPEN_APP, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE)) {
            assertEquals(t.id, app.heyrobot.core.Approval.AUTO, s.approval(t))
            assertEquals(t.id, app.heyrobot.core.GateDecision.RUN, app.heyrobot.core.ApprovalGate.decide(t, locked = false, settings = s, asked = true))
        }
        assertEquals("the guard's name reaches nothing that ships", emptyList<String>(), shipped.filter { (_, f) -> "REACHES_OUT" in f.readText() }.keys.toList())
        val harness = File(root, "app/src/spoken/java/app/heyrobot/spoken/SpokenHarness.kt").readText()
        assertTrue(harness.contains("SettingsStore.save(app, SpokenSettings.guard(new))"))
        assertEquals("every save goes through it", 1, Regex("""SettingsStore\.save\(""").findAll(harness).count())
        assertTrue(harness.contains("it.copy(music = false)"))
    }

    /** The microphone, for the listener and the recorder, and nothing else: your calendar, contacts and calls stay out of its reach. */
    @Test fun theTestBuildAsksForTheMicrophoneOnly() {
        val code = File(root, "app/src/spoken").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertEquals(listOf("RECORD_AUDIO"), Regex("""Manifest\.permission\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSet().toList())
        assertEquals(listOf("android.permission.RECORD_AUDIO"), Regex("""pm", "grant", self\.pkg, "([^"]+)"""").findAll(File(root, "tools/spoken/run.py").readText()).map { it.groupValues[1] }.toList())
        val never = File(root, "tools/spoken/spoken.json").readText().substringAfter("\"neverGranted\"").substringBefore(']')
        for (p in listOf("CALL_PHONE", "READ_CONTACTS", "READ_CALENDAR", "RUN_COMMAND")) assertTrue(p, never.contains(p))
    }

    @Test fun theHarnessOnlyEverDeclinesACard() {
        val harness = Source.strip(File(root, "app/src/spoken/java/app/heyrobot/spoken/SpokenHarness.kt").readText(), keepStrings = false, kotlin = true)
        val confirms = Regex("""PanelController\.confirm\([^)]*\)""").findAll(harness).map { it.value }.toList()
        assertEquals(1, confirms.size)
        assertTrue(confirms[0], confirms[0].endsWith("yes = false)"))
        assertFalse("clips go through the listener, never as typed text", harness.contains("PanelController.send("))
        val receiver = File(root, "app/src/spoken/java/app/heyrobot/spoken/SpokenReceiver.kt").readText()
        assertTrue(receiver.contains("const val MARKER = \"robot-spoken-harness\""))
        assertTrue("every command passes the sender check", receiver.indexOf("senderAllowed(") in 0 until receiver.indexOf("commands.handle("))
    }
}
