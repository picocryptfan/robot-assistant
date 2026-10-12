package app.heyrobot.core.termux

import org.json.JSONArray
import org.json.JSONObject

// Pure. Settings › Termux's set-up checklist (docs/plans/websearch-termux-design.md B5.1): Termux installed (from where),
// opened once, Robot's permission, allow-external-apps, the Test, your scripts folder and trusting this Termux. Nothing
// runs until every line is green (B7.1 rule 1). The Test, the folder and the listing run in the :voice process (where the
// result receiver is); what they found is written to files/robot/termux_status.json ([TermuxSetupStatus]) for the main app.

/** What the :voice process last found with the set-up runs (one writer: :voice). */
data class TermuxSetupStatus(
    /** When the Test last ran (wall clock, ms); 0: never. */
    val testAt: Long = 0,
    val testOk: Boolean = false,
    val testMs: Long = 0,
    val testFailure: TermuxFailure? = null,
    /** GNU timeout was found (command -v timeout printed its path): Stop and the limit can end a run's whole tree. */
    val timeout: Boolean = false,
    /** The signer of the Termux that answered the Test (64 hex digits). */
    val signer: String? = null,
    /** When the scripts were last listed; 0: never. */
    val listAt: Long = 0,
    val folderMissing: Boolean = false,
    val scripts: List<TermuxScriptInfo> = emptyList(),
    val listFailure: TermuxFailure? = null,
    /** A set-up run under way ("test", "list", "folder") and since when, so the page says "Testing…". */
    val running: String? = null,
    val runningAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("testAt", testAt)
        put("testOk", testOk)
        put("testMs", testMs)
        testFailure?.let { put("testFailure", it.name) }
        put("timeout", timeout)
        signer?.let { put("signer", it) }
        put("listAt", listAt)
        put("folderMissing", folderMissing)
        put("scripts", JSONArray().apply {
            for (s in scripts) put(JSONObject().put("name", s.name).apply { s.comment?.let { put("comment", it) }; if (s.aliases.isNotEmpty()) put("aliases", JSONArray(s.aliases)) })
        })
        listFailure?.let { put("listFailure", it.name) }
        running?.let { put("running", it); put("runningAt", runningAt) }
    }

    companion object {
        const val FILE = "termux_status.json"

        fun fromJson(o: JSONObject?): TermuxSetupStatus {
            if (o == null) return TermuxSetupStatus()
            val scripts = ArrayList<TermuxScriptInfo>()
            o.optJSONArray("scripts")?.let { a ->
                for (k in 0 until minOf(a.length(), ScriptRegistry.MAX_SCRIPTS)) {
                    val s = a.optJSONObject(k) ?: continue
                    val name = s.optString("name")
                    if (!TermuxScripts.validName(name) || scripts.any { it.name == name }) continue
                    val aliases = s.optJSONArray("aliases")?.let { al -> (0 until al.length()).map { al.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
                    scripts += TermuxScriptInfo(name, s.optString("comment", "").takeIf { it.isNotBlank() }?.take(ScriptRegistry.MAX_COMMENT), aliases)
                }
            }
            fun failure(k: String) = TermuxFailure.entries.firstOrNull { it.name == o.optString(k) }
            return TermuxSetupStatus(
                testAt = o.optLong("testAt", 0), testOk = o.optBoolean("testOk", false), testMs = o.optLong("testMs", 0), testFailure = failure("testFailure"),
                timeout = o.optBoolean("timeout", false), signer = TermuxSettings.sha256(o.optString("signer", "")),
                listAt = o.optLong("listAt", 0), folderMissing = o.optBoolean("folderMissing", false), scripts = scripts, listFailure = failure("listFailure"),
                running = o.optString("running", "").takeIf { it.isNotEmpty() }, runningAt = o.optLong("runningAt", 0),
            )
        }

        fun parse(text: String?): TermuxSetupStatus = try { fromJson(text?.let(::JSONObject)) } catch (_: Exception) { TermuxSetupStatus() }
    }
}

/** One line of the checklist: done, to do, or a warning (the page draws each as Assistant setup's rows do). */
enum class SetupMark { OK, TODO, WARN }

data class SetupLine(val step: SetupStep, val mark: SetupMark, val text: String)

enum class SetupStep(val title: String) {
    INSTALLED("Termux installed"),
    OPENED("Opened once"),
    PERMISSION("Robot may run commands in Termux"),
    EXTERNAL_APPS("Termux allows other apps"),
    TEST("Test"),
    FOLDER("Your scripts folder"),
    TRUST("Trust this Termux"),
}

object TermuxSetup {
    /** Termux's F-Droid page ("built and signed by F-Droid"): the APK downloads there without the F-Droid app too. */
    const val FDROID_URL = "https://f-droid.org/en/packages/com.termux/"

    /** The line to paste in Termux (B5.1 step 4); termux-reload-settings, because Termux reads its properties once. */
    const val ALLOW_LINE = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"

    const val ALLOW_WARNING = "With this, any app you give Robot's Termux permission to can run commands in Termux. Give it to Robot only."

    /** A set-up run that started this long ago and never wrote its end is over (its process died). */
    const val RUNNING_STALE_MS = 60_000L

    /**
     * The checklist. [check]: TermuxIdentity's verdict on the installed Termux (with the pinned signer); [granted]: Robot
     * holds com.termux.permission.RUN_COMMAND; [status]: what the :voice process last found; [trusted]: the pinned signer.
     */
    fun lines(check: TermuxCheck, granted: Boolean, status: TermuxSetupStatus, trusted: String?): List<SetupLine> {
        val installed = when (check.status) {
            TermuxStatus.READY -> SetupLine(SetupStep.INSTALLED, SetupMark.OK, check.line())
            TermuxStatus.TEST_KEY -> SetupLine(SetupStep.INSTALLED, SetupMark.WARN, check.line())
            TermuxStatus.UNKNOWN_SIGNER -> SetupLine(SetupStep.INSTALLED, if (trusted != null) SetupMark.OK else SetupMark.WARN, check.line())
            TermuxStatus.NOT_INSTALLED -> SetupLine(SetupStep.INSTALLED, SetupMark.TODO, "Not installed. Get it from F-Droid (not Google Play: that version can't take commands from other apps).")
            else -> SetupLine(SetupStep.INSTALLED, SetupMark.WARN, check.line())
        }
        val tested = status.testAt > 0
        val opened = when {
            status.testOk -> SetupLine(SetupStep.OPENED, SetupMark.OK, "Termux is set up")
            status.testFailure == TermuxFailure.NOT_SET_UP -> SetupLine(SetupStep.OPENED, SetupMark.TODO, "Open Termux once so it can finish installing, then Test again.")
            else -> SetupLine(SetupStep.OPENED, SetupMark.TODO, "Open Termux once so it finishes installing. The Test checks it.")
        }
        val permission = if (granted) SetupLine(SetupStep.PERMISSION, SetupMark.OK, "Allowed")
            else SetupLine(SetupStep.PERMISSION, SetupMark.TODO, "Android asks you. If it no longer does: App info › Permissions › Additional permissions.")
        val external = when {
            status.testOk -> SetupLine(SetupStep.EXTERNAL_APPS, SetupMark.OK, "allow-external-apps is on")
            status.testFailure == TermuxFailure.EXTERNAL_APPS_OFF -> SetupLine(SetupStep.EXTERNAL_APPS, SetupMark.TODO,
                "allow-external-apps is off: paste this line in Termux, then Test again.")
            else -> SetupLine(SetupStep.EXTERNAL_APPS, SetupMark.TODO, "Paste this line in Termux:")
        }
        val test = when {
            status.running == "test" -> SetupLine(SetupStep.TEST, SetupMark.TODO, "Testing…")
            status.testOk && !status.timeout -> SetupLine(SetupStep.TEST, SetupMark.WARN,
                "Termux answered in ${status.testMs} ms, but GNU timeout is missing: run pkg install coreutils in Termux.")
            status.testOk -> SetupLine(SetupStep.TEST, SetupMark.OK, "Termux answered in ${status.testMs} ms")
            tested -> SetupLine(SetupStep.TEST, SetupMark.WARN, (status.testFailure ?: TermuxFailure.INTERNAL).en)
            else -> SetupLine(SetupStep.TEST, SetupMark.TODO, "Robot runs: echo robot-ok; command -v timeout")
        }
        val folder = when {
            status.running == "list" || status.running == "folder" -> SetupLine(SetupStep.FOLDER, SetupMark.TODO, "Looking…")
            status.listAt == 0L -> SetupLine(SetupStep.FOLDER, SetupMark.TODO, "~/robot: not listed yet (optional)")
            status.folderMissing -> SetupLine(SetupStep.FOLDER, SetupMark.TODO, "~/robot doesn't exist yet (optional)")
            status.listFailure != null -> SetupLine(SetupStep.FOLDER, SetupMark.WARN, status.listFailure.en)
            status.scripts.isEmpty() -> SetupLine(SetupStep.FOLDER, SetupMark.OK, "~/robot: no scripts yet")
            else -> SetupLine(SetupStep.FOLDER, SetupMark.OK, "~/robot: " + (if (status.scripts.size == 1) "1 script" else "${status.scripts.size} scripts"))
        }
        val signer = check.signer
        val trust = when {
            check.status == TermuxStatus.CHANGED -> SetupLine(SetupStep.TRUST, SetupMark.WARN, "Termux was reinstalled or replaced: check where it came from, then Trust again.")
            trusted != null && trusted == signer -> SetupLine(SetupStep.TRUST, SetupMark.OK, "Trusted · signer " + short(trusted))
            trusted != null -> SetupLine(SetupStep.TRUST, SetupMark.OK, "Trusted · signer " + short(trusted) + " (in its signing history)")
            signer != null -> SetupLine(SetupStep.TRUST, SetupMark.TODO, "Robot records this Termux's signer " + short(signer) + " and checks it before every run.")
            else -> SetupLine(SetupStep.TRUST, SetupMark.TODO, "Install Termux first")
        }
        return listOf(installed, opened, permission, external, test, folder, trust)
    }

    /**
     * Everything Robot needs before it runs anything (rule 1): a usable Termux, trusted, the permission, and a Test that
     * passed with the Termux that is installed now. ~/robot is optional.
     */
    fun ready(check: TermuxCheck, granted: Boolean, status: TermuxSetupStatus, trusted: String?): Boolean =
        check.usable && trusted != null && granted && status.testOk && status.timeout

    /** "228FB2CF…34D41C42". */
    fun short(hex: String): String = if (hex.length <= 16) hex else hex.take(8) + "…" + hex.takeLast(8)

    /** The Test's output read: bash answered ("robot-ok"), and GNU timeout's path. */
    fun testOk(stdout: String?): Boolean = stdout.orEmpty().lineSequence().any { it.trim() == "robot-ok" }

    fun timeoutFound(stdout: String?): Boolean = stdout.orEmpty().lineSequence().any { val l = it.trim(); l.startsWith("/") && l.endsWith("/timeout") }
}
