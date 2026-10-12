package app.heyrobot.core

// Pure. Staying the assistant across restarts (0.2.3), and the one-time ADB fixes screen.
//
// At boot, Android 17's VoiceInteractionManagerService.initForUser keeps the current assistant only if
// Settings.Secure voice_recognition_service names an installed recognizer that may be the default ("Current
// interactor/recognizer okay"). Empty, it looks for an interactor among system apps only and clears Robot. GrapheneOS
// has no screen to pick a recognizer, so you set it once with adb; with WRITE_SECURE_SETTINGS (granted with adb
// too: it is a development permission) Robot keeps it set by itself. Robot writes that one key and nothing else: the
// assistant itself (voice_interaction_service, assistant, the role) belongs to RoleManager.

object RecognizerRepair {
    const val KEY = "voice_recognition_service"

    enum class Decision {
        /** Set the key to Robot's recognizer now. */
        WRITE,
        /** Already Robot's: nothing to do. */
        ALREADY_ROBOT,
        /** Another installed recognizer: Android keeps the assistant with it too, so it is left alone. */
        OTHER_RECOGNIZER,
        /** Robot isn't the chosen assistant: not Robot's business. */
        NOT_ASSISTANT,
        /** Robot may not write it (the adb grant wasn't made): the ADB fixes screen says how. */
        NO_PERMISSION,
    }

    /**
     * [roleHeld]: Robot holds ROLE_ASSISTANT; [canWrite]: WRITE_SECURE_SETTINGS is granted; [current]: the key's value
     * (null or empty: unset); [robot]: Robot's recognizer, flattened; [currentInstalled]: [current] names an installed
     * recognition service.
     */
    fun decide(roleHeld: Boolean, canWrite: Boolean, current: String?, robot: String, currentInstalled: Boolean): Decision = when {
        same(current, robot) -> Decision.ALREADY_ROBOT
        !roleHeld -> Decision.NOT_ASSISTANT
        !current.isNullOrBlank() && currentInstalled -> Decision.OTHER_RECOGNIZER
        !canWrite -> Decision.NO_PERMISSION
        else -> Decision.WRITE
    }

    /**
     * "a.b/.X" and "a.b/a.b.X" are the same component (a class name starting with "." is in the package, as Android reads
     * it). 1.0: Robot's package (ai.robot_app) and its classes' package (app.heyrobot) differ, so "ai.robot_app/.assistant.X"
     * would name a class that doesn't exist: [AdbFixes.RECOGNIZER] is written in full.
     */
    fun same(a: String?, b: String?): Boolean {
        fun full(s: String?): String? {
            val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val pkg = t.substringBefore('/', "")
            val cls = t.substringAfter('/', "")
            if (pkg.isEmpty() || cls.isEmpty()) return t
            return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
        }
        val x = full(a) ?: return false
        return x == full(b)
    }
}

/** What the app checks to show an [AdbFix] as applied. */
enum class AdbCheck {
    /** voice_recognition_service is Robot's recognizer. */
    RECOGNIZER_IS_ROBOT,
    /** Robot holds WRITE_SECURE_SETTINGS. */
    SECURE_SETTINGS_GRANTED,
}

/**
 * One platform workaround that needs a computer once (Settings › Advanced › ADB fixes): [why] in one or two plain
 * sentences, the [commands] to run, and how the app tells it is done. [sinceVersionCode]: the first Robot version that
 * uses it. A new workaround is a new entry in [AdbFixes.ALL].
 */
data class AdbFix(
    val id: String,
    val title: String,
    val why: String,
    val commands: List<String>,
    val check: AdbCheck,
    val sinceVersionCode: Int = 0,
    val sinceVersion: String = "",
)

enum class AdbFixState { APPLIED, NOT_APPLIED, NEEDS_NEWER }

object AdbFixes {
    /** 1.0: [AppId.PACKAGE] (0.9's grants were for `app.heyrobot` and don't carry over to it). */
    const val PACKAGE = AppId.PACKAGE
    /** The package, then the class (whose own package stays `app.heyrobot`), as Android flattens a component. */
    const val RECOGNIZER = "$PACKAGE/app.heyrobot.assistant.RobotRecognitionService"

    const val HEADER = "Some things Android only lets a computer change. Each fix below is done once, with adb. " +
        "On the phone: Settings › About phone, tap Build number seven times; then Settings › System › Developer options, turn on " +
        "USB debugging and connect the phone to a computer with adb. Run the commands there, then turn USB debugging off again."

    val ALL: List<AdbFix> = listOf(
        AdbFix(
            id = "recognizer",
            title = "Stay the assistant after a restart",
            why = "At every restart Android keeps a non-system assistant only if a default speech recognizer is set, and GrapheneOS has no " +
                "screen to set one. This sets Robot's.",
            commands = listOf("adb shell settings put secure ${RecognizerRepair.KEY} $RECOGNIZER"),
            check = AdbCheck.RECOGNIZER_IS_ROBOT,
        ),
        AdbFix(
            id = "repair",
            title = "Let Robot repair that itself",
            why = "Lets Robot set the speech recognizer again if it is ever cleared (Robot changes nothing else). Android allows this " +
                "permission only through adb.",
            commands = listOf("adb shell pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS"),
            check = AdbCheck.SECURE_SETTINGS_GRANTED,
            sinceVersionCode = 23,
            sinceVersion = "0.2.3",
        ),
    )

    fun state(fix: AdbFix, applied: Boolean, versionCode: Int): AdbFixState = when {
        versionCode < fix.sinceVersionCode -> AdbFixState.NEEDS_NEWER
        applied -> AdbFixState.APPLIED
        else -> AdbFixState.NOT_APPLIED
    }

    fun label(fix: AdbFix, s: AdbFixState): String = when (s) {
        AdbFixState.APPLIED -> "Done"
        AdbFixState.NOT_APPLIED -> "Not done"
        AdbFixState.NEEDS_NEWER -> "Needs Robot ${fix.sinceVersion}+"
    }
}
