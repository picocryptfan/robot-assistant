package app.heyrobot.core.termux

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// Pure. Settings › Termux, a type of its own until it joins RobotSettings.
// Wiring, when the agent lands (Settings.kt is shared, so not here): settings.json schema 11 (EST, after web search's 10)
// gets a "termux" object; RobotSettings gains `val termux: TermuxSettings = TermuxSettings()`, its toJson puts
// `termux.toJson()` under "termux" and fromJson reads `TermuxSettings.fromJson(o.optJSONObject("termux"))`. A file
// without it (every older one) reads as all Off. There is deliberately no setting that runs every command: free-form
// commands are Ask first or Off, and only the parsed read-only allowlist and your own scripts can skip the card.

/** One of your scripts in ~/robot: run when named (Just do it), show its card first (the default), or never. */
enum class ScriptLevel(val en: String, val fr: String) {
    AUTO("Just do it", "Sans demander"),
    ASK("Ask first", "Demander d'abord"),
    OFF("Off", "Désactivé"),
}

/** Commands written in full (typed, spoken, Gemma's, a helper's): Ask first or Off. No "Just do it" exists. */
enum class CommandLevel(val en: String, val fr: String) {
    ASK("Ask first", "Demander d'abord"),
    OFF("Off", "Désactivé"),
}

/** "A spoken yes runs a command": only one without warnings (the default), or never (always a tap). It can only be stricter. */
enum class SpokenYes(val en: String, val fr: String) {
    WITHOUT_WARNINGS("Only commands without warnings", "Seulement les commandes sans avertissement"),
    NEVER("Never (tap only)", "Jamais (toucher seulement)"),
}

/** "Termux:API commands": allowed, always with a card (the default), or refused. */
enum class ApiCommands(val en: String, val fr: String) {
    WITH_CARD("Allowed, with a card", "Permises, avec une carte"),
    NOT_ALLOWED("Not allowed", "Interdites"),
}

data class TermuxSettings(
    /** "Let Robot use Termux": the master switch (the set-up checklist must also be green). */
    val enabled: Boolean = false,
    /** Each script's level by its file name in ~/robot; missing: [ScriptLevel.ASK]. */
    val scripts: Map<String, ScriptLevel> = emptyMap(),
    val commands: CommandLevel = CommandLevel.ASK,
    /** "Read-only commands without a card": off by default. */
    val readOnlyWithoutCard: Boolean = false,
    val spokenYes: SpokenYes = SpokenYes.WITHOUT_WARNINGS,
    /** "Time limit", 10 s to 10 min; the card may change it for one run. */
    val limitSeconds: Int = DEFAULT_LIMIT_S,
    /** "Output Robot reads": what Gemma gets. */
    val outputLines: Int = DEFAULT_LINES,
    val outputChars: Int = DEFAULT_CHARS,
    val hideSecrets: Boolean = true,
    /** "Keep commands and output in history": off, only "Ran a command in Termux (exit 0)" is saved. */
    val keepInHistory: Boolean = false,
    /** "Load my shell profile": bash -l instead of --noprofile --norc. */
    val loadProfile: Boolean = false,
    val api: ApiCommands = ApiCommands.WITH_CARD,
    /** "Helpers may ask for Termux commands": off. */
    val helpers: Boolean = false,
    /** Scripts that also work on the lock screen (free-form commands never do). */
    val lockScreenScripts: Set<String> = emptySet(),
    /** The trusted Termux's signing certificate SHA-256 (hex), recorded at set-up; null until then. */
    val trustedSigner: String? = null,
    /**
     * 1.0, the update lock: the trusted Termux's version code and when Android last installed or updated it
     * (PackageInfo.lastUpdateTime), recorded with its signer at Trust; 0 for a 0.9 file, which reads as a change.
     */
    val trustedVersionCode: Long = 0,
    val trustedUpdatedAt: Long = 0,
    /** Its version name, as Settings shows it. */
    val trustedVersion: String? = null,
    /** 1.0: "Run anyway, I understand the risk" for a GitHub build (public test key), for the trusted version only: off. */
    val testKeyRisk: Boolean = false,
    /** 1.0: "Check for Termux updates" once a week (Termux's GitHub releases and F-Droid's entry): on, once Termux is trusted. */
    val updateCheck: Boolean = true,
) {
    fun script(name: String): ScriptLevel = scripts[name] ?: ScriptLevel.ASK

    /** 1.0: the version you trusted, for TermuxIdentity.check (null before you trust a Termux). */
    fun trusted(): TrustedVersion? = trustedSigner?.let { TrustedVersion(trustedVersionCode, trustedUpdatedAt, testKeyRisk) }

    /**
     * 1.0, "Trust" and "Trust this version": [check]'s signer, version code and install time recorded. "Run anyway" goes
     * with the version it was given for, so a new version asks for it again.
     */
    fun trust(check: TermuxCheck): TermuxSettings = copy(trustedSigner = check.signer ?: trustedSigner, trustedVersionCode = check.versionCode,
        trustedUpdatedAt = check.updatedAt, trustedVersion = check.version, testKeyRisk = false)

    /** 1.0, "Set up again" (another signer): nothing trusted any more, and the switch off until the checklist is green again. */
    fun untrust(): TermuxSettings = copy(enabled = false, trustedSigner = null, trustedVersionCode = 0, trustedUpdatedAt = 0, trustedVersion = null,
        testKeyRisk = false)

    /** 1.0: the weekly update check runs (it is on, and there is a trusted Termux to check). */
    val checksUpdates: Boolean get() = updateCheck && trustedSigner != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("scripts", JSONObject().apply { for ((k, v) in scripts.toSortedMap()) put(k, v.name) })
        put("commands", commands.name)
        put("readOnlyWithoutCard", readOnlyWithoutCard)
        put("spokenYes", spokenYes.name)
        put("limitSeconds", limitSeconds)
        put("outputLines", outputLines)
        put("outputChars", outputChars)
        put("hideSecrets", hideSecrets)
        put("keepInHistory", keepInHistory)
        put("loadProfile", loadProfile)
        put("api", api.name)
        put("helpers", helpers)
        put("lockScreenScripts", JSONArray(lockScreenScripts.sorted()))
        trustedSigner?.let { put("trustedSigner", it) }
        if (trustedVersionCode != 0L) put("trustedVersionCode", trustedVersionCode)
        if (trustedUpdatedAt != 0L) put("trustedUpdatedAt", trustedUpdatedAt)
        trustedVersion?.let { put("trustedVersion", it) }
        put("testKeyRisk", testKeyRisk)
        put("updateCheck", updateCheck)
    }

    companion object {
        /** The settings.json schema expected to carry the "termux" object (EST: confirm the number at merge). */
        const val SCHEMA = 11
        const val DEFAULT_LIMIT_S = 60
        const val MIN_LIMIT_S = 10
        const val MAX_LIMIT_S = 600
        const val DEFAULT_LINES = 40
        const val DEFAULT_CHARS = 1500
        /** Your scripts folder: ~/robot. */
        const val SCRIPTS_FOLDER = "robot"

        fun limit(seconds: Int): Int = seconds.coerceIn(MIN_LIMIT_S, MAX_LIMIT_S)

        /** [hex] as 64 uppercase hex digits (colons and spaces dropped), or null when it isn't a SHA-256. */
        fun sha256(hex: String?): String? {
            val h = hex?.filter { it != ':' && it != ' ' }?.uppercase(Locale.ROOT) ?: return null
            return if (h.length == 64 && h.all { it in '0'..'9' || it in 'A'..'F' }) h else null
        }

        /** The "termux" object; null or anything unreadable gives the defaults (all Off). Unknown names keep their default. */
        fun fromJson(o: JSONObject?): TermuxSettings {
            val d = TermuxSettings()
            if (o == null) return d
            val scripts = LinkedHashMap<String, ScriptLevel>()
            o.optJSONObject("scripts")?.let { s ->
                for (k in s.keys()) {
                    val level = ScriptLevel.entries.firstOrNull { it.name == s.optString(k) } ?: continue
                    if (TermuxScripts.validName(k)) scripts[k] = level
                }
            }
            val locked = LinkedHashSet<String>()
            o.optJSONArray("lockScreenScripts")?.let { a -> for (k in 0 until a.length()) a.optString(k).takeIf { TermuxScripts.validName(it) }?.let { locked += it } }
            return TermuxSettings(
                enabled = o.optBoolean("enabled", d.enabled),
                scripts = scripts,
                commands = CommandLevel.entries.firstOrNull { it.name == o.optString("commands") } ?: d.commands,
                readOnlyWithoutCard = o.optBoolean("readOnlyWithoutCard", d.readOnlyWithoutCard),
                spokenYes = SpokenYes.entries.firstOrNull { it.name == o.optString("spokenYes") } ?: d.spokenYes,
                limitSeconds = limit(o.optInt("limitSeconds", d.limitSeconds)),
                outputLines = o.optInt("outputLines", d.outputLines).coerceIn(5, 200),
                outputChars = o.optInt("outputChars", d.outputChars).coerceIn(200, 8000),
                hideSecrets = o.optBoolean("hideSecrets", d.hideSecrets),
                keepInHistory = o.optBoolean("keepInHistory", d.keepInHistory),
                loadProfile = o.optBoolean("loadProfile", d.loadProfile),
                api = ApiCommands.entries.firstOrNull { it.name == o.optString("api") } ?: d.api,
                helpers = o.optBoolean("helpers", d.helpers),
                lockScreenScripts = locked,
                trustedSigner = sha256(o.optString("trustedSigner", "")),
                trustedVersionCode = o.optLong("trustedVersionCode", 0),
                trustedUpdatedAt = o.optLong("trustedUpdatedAt", 0),
                trustedVersion = o.optString("trustedVersion", "").takeIf { it.isNotBlank() }?.take(64),
                testKeyRisk = o.optBoolean("testKeyRisk", d.testKeyRisk),
                updateCheck = o.optBoolean("updateCheck", d.updateCheck),
            )
        }
    }
}

/** Your scripts in ~/robot: their names and paths. */
object TermuxScripts {
    const val FOLDER = "${TermuxPaths.HOME}/${TermuxSettings.SCRIPTS_FOLDER}"
    private const val MAX_NAME = 64

    /** A plain file name: letters, digits, ".", "_" and "-", not starting with "." or "-" (no "/", no ".."). */
    fun validName(name: String): Boolean =
        name.length in 1..MAX_NAME && name[0].let { it.isLetterOrDigit() && it.code < 0x80 } &&
            name.all { (it.isLetterOrDigit() && it.code < 0x80) || it == '.' || it == '_' || it == '-' }

    fun path(name: String): String {
        require(validName(name)) { "not a script name" }
        return "$FOLDER/$name"
    }
}
