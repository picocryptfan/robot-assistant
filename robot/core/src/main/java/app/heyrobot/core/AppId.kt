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

    /**
     * 1.0: "Robot/<version>", the one way every User-Agent of Robot's names it and its version: the downloads and the
     * updater (DownloadPolicy, GitHubHosts), Termux's update check, the search APIs, Wikipedia and Open Library
     * (WebUserAgent) and Nominatim (Nearby). [version] is the build's versionName as Settings › About shows it ("1.0.0",
     * "1.0.0-rc1"): ASCII letters, digits, "." and "-" (all token characters, RFC 9110), at most 32; anything else is
     * dropped, so nothing but the version can reach the header.
     */
    fun product(version: String): String =
        "Robot/" + version.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '.' || it == '-' }.take(32).ifEmpty { "0" }
}
