// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SettingsStrings

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

    /** 1.2 (every-android-phone, Step 6): what keeps Robot the assistant at the next boot ([kept]). */
    enum class Kept {
        /** Robot's recognizer is set. */
        ROBOT,
        /**
         * Another installed recognizer is set (on a stock phone, its maker's): Android keeps the assistant with it too
         * (INFERRED from Android's code; to check on the phones), so the ADB fix isn't asked for, and would replace it.
         */
        OTHER,
        /** None set, unreadable, or one no longer installed: Android may drop Robot at the next boot; the ADB fix keeps it. */
        NONE,
    }

    /** [current], [robot] and [currentInstalled] as in [decide], which leaves [Kept.OTHER] alone the same way. */
    fun kept(current: String?, robot: String, currentInstalled: Boolean): Kept = when {
        same(current, robot) -> Kept.ROBOT
        !current.isNullOrBlank() && currentInstalled -> Kept.OTHER
        else -> Kept.NONE
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
 * uses it. A new workaround is a new entry in [AdbFixes.ALL]. 1.1: [titleText] and [whyText] are its words without a
 * language yet (the page shows them in the menus' language).
 */
data class AdbFix(
    val id: String,
    val titleText: Phrase,
    val whyText: Phrase,
    val commands: List<String>,
    val check: AdbCheck,
    val sinceVersionCode: Int = 0,
    val sinceVersion: String = "",
)

enum class AdbFixState {
    APPLIED, NOT_APPLIED, NEEDS_NEWER,
    /**
     * 1.2 (every-android-phone, Step 6): the recognizer fix where another installed recognizer is set (a stock phone's
     * own, [RecognizerRepair.Kept.OTHER]): Android keeps the assistant with it too, and the fix would replace it for every
     * app, so the page offers neither its commands nor its run.
     */
    NOT_NEEDED,
}

object AdbFixes {
    /** 1.0: [AppId.PACKAGE] (0.9's grants were for `app.heyrobot` and don't carry over to it). */
    const val PACKAGE = AppId.PACKAGE
    /** The package, then the class (whose own package stays `app.heyrobot`), as Android flattens a component. */
    const val RECOGNIZER = "$PACKAGE/app.heyrobot.assistant.RobotRecognitionService" // l10n: not text (a component's name)

    /** The page's words before the fixes, Android's screens in the phone's language. */
    val headerText: Phrase get() = SettingsStrings.adbHeader("about" to AndroidPath.ABOUT_PHONE, "build" to AndroidPath.BUILD_NUMBER,
            "developer" to AndroidPath.DEVELOPER_OPTIONS, "usb" to AndroidPath.USB_DEBUGGING)

    val ALL: List<AdbFix> = listOf(
        AdbFix(
            id = "recognizer",
            titleText = SettingsStrings.adbRecognizer(),
            whyText = SettingsStrings.adbRecognizerWhy(),
            commands = listOf("adb shell settings put secure ${RecognizerRepair.KEY} $RECOGNIZER"), // l10n: not text (a shell command)
            check = AdbCheck.RECOGNIZER_IS_ROBOT,
        ),
        AdbFix(
            id = "repair",
            titleText = SettingsStrings.adbRepair(),
            whyText = SettingsStrings.adbRepairWhy(),
            commands = listOf("adb shell pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS"), // l10n: not text (a shell command)
            check = AdbCheck.SECURE_SETTINGS_GRANTED,
            sinceVersionCode = 23,
            sinceVersion = "0.2.3",
        ),
    )

    /** [kept]: what keeps Robot the assistant at the next boot now (RecognizerRepair.kept); NONE leaves every fix as before. */
    fun state(fix: AdbFix, applied: Boolean, versionCode: Int, kept: RecognizerRepair.Kept = RecognizerRepair.Kept.NONE): AdbFixState = when {
        versionCode < fix.sinceVersionCode -> AdbFixState.NEEDS_NEWER
        applied -> AdbFixState.APPLIED
        notNeeded(fix, kept) -> AdbFixState.NOT_NEEDED
        else -> AdbFixState.NOT_APPLIED
    }

    /**
     * 1.2: [fix] would replace another installed speech recognizer ([kept] is OTHER): the recognizer fix, whose command
     * sets voice_recognition_service. Neither the page nor "Run with Shizuku through Termux" offers it then.
     */
    fun notNeeded(fix: AdbFix, kept: RecognizerRepair.Kept): Boolean = fix.check == AdbCheck.RECOGNIZER_IS_ROBOT && kept == RecognizerRepair.Kept.OTHER

    /** The fix's state in words, without a language yet. */
    fun labelText(fix: AdbFix, s: AdbFixState): Phrase = when (s) {
        AdbFixState.APPLIED -> SettingsStrings.adbDone()
        AdbFixState.NOT_APPLIED -> SettingsStrings.adbNotDone()
        AdbFixState.NEEDS_NEWER -> SettingsStrings.adbNeedsNewer("version" to fix.sinceVersion)
        AdbFixState.NOT_NEEDED -> SettingsStrings.adbNotNeeded()
    }
}
