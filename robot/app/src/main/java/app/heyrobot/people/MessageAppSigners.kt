package app.heyrobot.people

import android.content.Context
import android.content.pm.PackageManager
import app.heyrobot.core.CheogramSigners
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSigners
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.PinVerdict
import app.heyrobot.core.messages.SignerNow
import java.security.MessageDigest

// MAIN PROCESS. What Android says of a messaging app's package now, for core MessageSigners (0.7): its signers' SHA-256
// digests and the installer it names. Settings › Messages keeps them when you turn an app on, and MessageTap reads a
// package's notifications only while it is still signed so. Nothing here is a secret: digests and package names only.

/** What Android says of a package: [now], and whether a kept signer is still in its lineage. Tests give their own. */
interface SignerSource {
    /** [pkg]'s signers and installer now; null when it isn't installed. */
    fun now(pkg: String): SignerNow?

    /** Every signer of [pin] is still one of [pkg]'s, by Android's own check (a key rotated with its lineage). */
    fun stillSigns(pkg: String, pin: AppPin): Boolean

    /** A number that changes when [pkg] is installed again or updated (its last update time), or null. */
    fun version(pkg: String): Long?
}

/** The phone's PackageManager. */
class AndroidSigners(private val ctx: Context) : SignerSource {
    private val pm: PackageManager get() = ctx.packageManager

    override fun now(pkg: String): SignerNow? {
        val info = try { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES) } catch (_: Exception) { return null }
        val installer = try { pm.getInstallSourceInfo(pkg).installingPackageName } catch (_: Exception) { null }
        val digests = try {
            info.signingInfo?.apkContentsSigners?.map { CheogramSigners.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet()
        } catch (_: Exception) { null }
        return SignerNow(digests, installer)
    }

    override fun stillSigns(pkg: String, pin: AppPin): Boolean = pin.digests.isNotEmpty() && pin.digests.all { d ->
        try { CheogramSigners.unhex(d)?.let { pm.hasSigningCertificate(pkg, it, PackageManager.CERT_INPUT_SHA256) } == true } catch (_: Exception) { false }
    }

    override fun version(pkg: String): Long? = try { pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { null }
}

object MessageAppSigners {
    /** Tests give their own. */
    @Volatile internal var source: (Context) -> SignerSource = { AndroidSigners(it.applicationContext) }

    /** The pins for [app]'s packages installed now (none for a package Android gives no signer for). */
    fun pinsNow(ctx: Context, app: MessageApp): Map<String, AppPin> {
        val s = source(ctx)
        return app.packages.mapNotNull { pkg -> MessageSigners.pinOf(s.now(pkg))?.let { pkg to it } }.toMap()
    }

    /** [pkg]'s verdict under [f], or null when it isn't installed. */
    fun verdict(ctx: Context, pkg: String, f: MessagesFile): PinVerdict? {
        val s = source(ctx)
        val now = s.now(pkg) ?: return null
        val pin = f.pins[pkg]
        return MessageSigners.verdict(pin, now, pin != null && s.stillSigns(pkg, pin), pkg in f.firstUse)
    }

    /** What Settings › Messages says about the apps you turned on: one line per installed package that isn't trusted. */
    fun problems(ctx: Context, f: MessagesFile): List<String> = MessageApp.entries.filter { it in f.settings.allowlist.apps }.flatMap { app ->
        app.packages.mapNotNull { pkg -> verdict(ctx, pkg, f)?.let { MessageSigners.problem(pkg, it) } }
    }
}
