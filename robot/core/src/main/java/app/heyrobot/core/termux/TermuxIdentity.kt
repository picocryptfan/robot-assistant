package app.heyrobot.core.termux

// Pure. Which Termux is installed (docs/plans/websearch-termux-design.md B2, B5.1, B7.6). :app (main process, with
// <queries> for com.termux) reads the package's signing certificates' SHA-256 (GET_SIGNING_CERTIFICATES), its version,
// whether it declares RunCommandService, and which package defines com.termux.permission.RUN_COMMAND; this decides what
// Settings › Termux says and whether the agent may use it. The pinned signer follows the Cheogram precedent
// (CheogramSigners): files/termux/termux.signer.

/** Where the installed Termux came from, by its signer. */
enum class TermuxSource(val en: String, val fr: String) {
    F_DROID("from F-Droid", "depuis F-Droid"),
    GITHUB("from GitHub (public test key: anyone can make an update that installs over it)",
        "depuis GitHub (clé de test publique : n'importe qui peut faire une mise à jour qui s'installe par-dessus)"),
    GOOGLE_PLAY("from Google Play", "depuis Google Play"),
    TERMUX_DEVS("signed by the Termux developers", "signée par les développeurs de Termux"),
    UNKNOWN("signed by an unknown key", "signée par une clé inconnue"),
}

/** What :app read about the installed com.termux. */
data class TermuxPackage(
    val installed: Boolean,
    val versionName: String? = null,
    /** SHA-256 (hex, any case, colons allowed) of each current signing certificate (signingInfo.apkContentsSigners). */
    val signers: List<String> = emptyList(),
    /** It declares com.termux.app.RunCommandService (the Google Play build doesn't). */
    val hasRunCommandService: Boolean = false,
    /** getPermissionInfo("com.termux.permission.RUN_COMMAND").packageName, or null when no app defines it. */
    val permissionOwner: String? = null,
)

enum class TermuxStatus {
    /** Usable. */
    READY,
    /** Usable, with the warning that GitHub builds share a public test key. */
    TEST_KEY,
    NOT_INSTALLED,
    /** No RunCommandService (the Google Play build): no other app can run commands in it. */
    NOT_SUPPORTED,
    /** Signed by a key Robot doesn't know, or by several: usable only once you trust it (Trust this Termux). */
    UNKNOWN_SIGNER,
    /** Another app defines Termux's RUN_COMMAND permission. */
    PERMISSION_TAKEN,
    /** Older than 0.118.0, which fixed a world-readable files vulnerability. */
    TOO_OLD,
    /** Not the Termux you trusted: reinstalled or replaced. */
    CHANGED,
}

data class TermuxCheck(val status: TermuxStatus, val source: TermuxSource?, /** The single signer, normalized, if there is one. */ val signer: String?, val version: String?) {
    /** Commands may be sent to it (with the set-up checklist's other lines green). */
    val usable: Boolean get() = status == TermuxStatus.READY || status == TermuxStatus.TEST_KEY

    /** Settings › Termux's first line: "Termux 0.118.3 from F-Droid", "Termux from Google Play: this version can't…". */
    fun line(fr: Boolean = false): String {
        val name = if (version.isNullOrBlank()) "Termux" else "Termux $version"
        val from = source?.let { " " + (if (fr) it.fr else it.en) }.orEmpty()
        return when (status) {
            TermuxStatus.NOT_INSTALLED -> if (fr) "Termux n'est pas installé." else "Termux isn't installed."
            TermuxStatus.NOT_SUPPORTED -> if (fr) "$name$from : cette version n'accepte pas de commandes d'autres applications ; installez Termux depuis F-Droid."
                else "$name$from: this version can't take commands from other apps; install Termux from F-Droid."
            TermuxStatus.PERMISSION_TAKEN -> if (fr) "Une autre application définit la permission de Termux : désinstallez-la avant de faire confiance à Termux."
                else "Another app defines Termux's permission: uninstall it before trusting Termux."
            TermuxStatus.TOO_OLD -> if (fr) "$name$from est trop ancien : mettez-le à jour vers ${TermuxIdentity.MIN_VERSION} ou plus récent."
                else "$name$from is too old: update it to ${TermuxIdentity.MIN_VERSION} or newer."
            TermuxStatus.CHANGED -> if (fr) "Termux a été réinstallé ou remplacé. Vérifiez sa provenance, puis Faire confiance à nouveau dans Réglages › Termux."
                else "Termux was reinstalled or replaced. Check where it came from, then Trust again in Settings › Termux."
            else -> "$name$from"
        }
    }
}

/**
 * The signers Termux itself lists: TermuxConstants.APK_RELEASE_FDROID_SIGNING_CERTIFICATE_SHA256_DIGEST,
 * …_GITHUB_…, …_GOOGLE_PLAYSTORE_… and …_TERMUX_DEVS_… (termux-shared/src/main/java/com/termux/shared/termux/
 * TermuxConstants.java lines 482, 488, 494 and 500, "Version: v0.53.0", termux/termux-app master
 * 8629e632fcb95da272221be327db653fb24befe9 of 26 Sep 2026; MIT). The Google Play value may be the older Play app's key
 * (design B13); the Play build is recognized by its missing RunCommandService either way.
 */
object TermuxIdentity {
    const val F_DROID = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
    const val GITHUB = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"
    const val GOOGLE_PLAY = "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9"
    const val TERMUX_DEVS = "F7A038EB551F1BE8FDF388686B784ABAB4552A5D82DF423E3D8F1B5CBE1C69AE"

    val KNOWN: Map<String, TermuxSource> = mapOf(F_DROID to TermuxSource.F_DROID, GITHUB to TermuxSource.GITHUB, GOOGLE_PLAY to TermuxSource.GOOGLE_PLAY,
        TERMUX_DEVS to TermuxSource.TERMUX_DEVS)

    /** 0.118.0 fixed a world-readable files vulnerability (Termux README). */
    const val MIN_VERSION = "0.118.0"

    /** [hex] as 64 uppercase hex digits (colons and spaces dropped), or null when it isn't a SHA-256. */
    fun normalize(hex: String?): String? = TermuxSettings.sha256(hex)

    fun source(digest: String?): TermuxSource = KNOWN[normalize(digest)] ?: TermuxSource.UNKNOWN

    /** [version] (e.g. "0.118.3", "0.119.0-beta.3", "googleplay.2026.10.09") is at least [min]; unreadable: no. */
    fun atLeast(version: String?, min: String = MIN_VERSION): Boolean {
        fun parts(v: String): List<Int>? {
            val core = v.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            if (core.isEmpty()) return null
            return core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        }
        val a = parts(version ?: return false) ?: return false
        val b = parts(min) ?: return false
        for (k in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(k) { 0 }
            val y = b.getOrElse(k) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    /**
     * What Settings › Termux says about [pkg], and whether Robot may use it. [pinned]: the signer recorded at "Trust this
     * Termux" (null before set-up); [pinnedStillSigns]: the system says the pinned certificate is in the package's
     * signing lineage (hasSigningCertificate), so a key rotation isn't a replacement.
     */
    fun check(pkg: TermuxPackage, pinned: String? = null, pinnedStillSigns: Boolean = false): TermuxCheck {
        if (!pkg.installed) return TermuxCheck(TermuxStatus.NOT_INSTALLED, null, null, null)
        val signer = pkg.signers.mapNotNull { normalize(it) }.distinct().singleOrNull()?.takeIf { pkg.signers.size == 1 }
        val source = if (signer == null) TermuxSource.UNKNOWN else source(signer)
        fun result(s: TermuxStatus) = TermuxCheck(s, source, signer, pkg.versionName)
        if (pkg.permissionOwner != null && pkg.permissionOwner != TermuxIntents.PACKAGE) return result(TermuxStatus.PERMISSION_TAKEN)
        if (source == TermuxSource.GOOGLE_PLAY || !pkg.hasRunCommandService || pkg.permissionOwner == null) return result(TermuxStatus.NOT_SUPPORTED)
        val pin = normalize(pinned)
        if (pinned != null && (pin == null || (pin != signer && !pinnedStillSigns))) return result(TermuxStatus.CHANGED)
        if (signer == null) return result(TermuxStatus.UNKNOWN_SIGNER)
        if (!atLeast(pkg.versionName)) return result(TermuxStatus.TOO_OLD)
        return result(when (source) {
            TermuxSource.GITHUB -> TermuxStatus.TEST_KEY
            TermuxSource.F_DROID, TermuxSource.TERMUX_DEVS -> TermuxStatus.READY
            else -> if (pin != null) TermuxStatus.READY else TermuxStatus.UNKNOWN_SIGNER
        })
    }
}
