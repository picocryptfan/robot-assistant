package app.heyrobot.core

/**
 * 1.0: Robot's application ID, its package on the phone: robot-app.ai reversed (a package name can't hold the hyphen). 0.9
 * and earlier were `app.heyrobot`, so 1.0 installs beside them as another app (README, "Moving from 0.9 to 1.0"). The
 * Kotlin packages and class names stay `app.heyrobot` (the build's namespace). The app's own code asks Android
 * (`Context.packageName`, `BuildConfig.APPLICATION_ID`); core has neither, so the adb commands it shows, the updater's
 * check and the User-Agents read this, and the app's ProcessIsolationTest holds it to the build's applicationId.
 */
object AppId {
    const val PACKAGE = "ai.robot_app"
}
