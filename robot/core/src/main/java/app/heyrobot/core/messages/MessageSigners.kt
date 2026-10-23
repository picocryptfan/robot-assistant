// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.MessageStrings
import app.heyrobot.core.i18n.Phrase

import java.util.Locale

// Pure. Which build of an app may hand Robot your messages (0.7): a package name alone proves nothing, since anyone can
// publish an app under "org.thoughtcrime.securesms" outside the store you got Signal from. So, as Cheogram name learning
// already does (core CheogramSigners), turning an app on in Settings › Messages keeps each of its installed packages'
// signing certificates (their SHA-256 digests) and the installer Android names, and MessageTap ignores a notification
// from a package whose signer isn't the one kept. Each package is its own entry, pinned apart: Cheogram's F-Droid build
// and its Play build are two. A messages.json from before the pins (version 1) trusts each package of an app that was
// already on at that package's first notification (trust on first use), and the voice log says so with a code.

/**
 * What Robot kept of one package when you turned its app on: its signers' SHA-256 digests (lower-case hex, one per
 * signer; a key rotated later is still accepted while Android says the kept one is in its signing lineage) and the
 * package that installed it, when Android said ("org.fdroid.fdroid", "com.android.vending").
 */
data class AppPin(val digests: Set<String>, val installer: String? = null) {
    override fun toString(): String = "AppPin(signers=${digests.size}, installer=$installer)" // l10n: not text (toString for logs and tests)
}

/** A package as Android describes it now: its signers' digests (null: unreadable, or not installed) and its installer. */
data class SignerNow(val digests: Set<String>?, val installer: String? = null)

/** Whether a package's notification may be read. */
enum class PinVerdict {
    /** Signed as when you turned its app on: read it. */
    TRUST,
    /** No pin yet, and the app was on before 0.7 kept pins: pin its signer now and read it (trust on first use). */
    PIN_NOW,
    /** No pin: the package was installed after you turned its app on (or Android couldn't say who signed it). */
    NOT_PINNED,
    /** Its signer isn't the one kept: ignored until you turn its app on again. */
    CHANGED,
}

object MessageSigners {
    /** Signers kept per package at most (an app has one; Android allows several). */
    const val MAX_SIGNERS = 8
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * [pin]: what was kept for the package; [now]: its signers now; [pinnedStillSigns]: Android says every kept signer is
     * still in the package's signing lineage (a key rotated with Android's own scheme); [firstUse]: the package may be
     * pinned at this notification (its app was on before the pins, messages.json version 1).
     */
    fun verdict(pin: AppPin?, now: SignerNow?, pinnedStillSigns: Boolean, firstUse: Boolean): PinVerdict {
        val current = now?.digests?.let(::clean)?.takeIf { it.isNotEmpty() }
        if (pin == null) return if (firstUse && current != null) PinVerdict.PIN_NOW else PinVerdict.NOT_PINNED
        return when {
            current != null && current == pin.digests -> PinVerdict.TRUST
            pinnedStillSigns && pin.digests.isNotEmpty() -> PinVerdict.TRUST
            else -> PinVerdict.CHANGED
        }
    }

    /** The pin for a package described by [now], or null when Android gave no signer to keep. */
    fun pinOf(now: SignerNow?): AppPin? {
        val d = now?.digests?.let(::clean)?.takeIf { it.isNotEmpty() && it.size <= MAX_SIGNERS } ?: return null
        return AppPin(d, installer(now.installer))
    }

    /** Valid digests only, lower case. */
    fun clean(digests: Collection<String>): Set<String> = digests.map { it.trim().lowercase(Locale.ROOT) }.filter { HEX64.matches(it) }.toSortedSet()

    /** An installer's package name as kept: a plausible package name, or null. */
    fun installer(s: String?): String? = s?.trim()?.takeIf { it.length in 1..200 && Regex("^[A-Za-z0-9_.]+$").matches(it) }

    /** A package's name for you: the app's title, with which build it is when the app has several. */
    fun label(pkg: String): String = labelText(pkg).en()

    /** [label] in words without a language yet. */
    fun labelText(pkg: String): Phrase {
        val app = MessageApp.of(pkg) ?: return Phrase.plain(pkg)
        if (app.packages.size < 2 || pkg == app.packages.first()) return app.titleText
        return when {
            pkg.endsWith(".playstore") -> MessageStrings.playStoreBuild("appName" to app.titleText)
            else -> Phrase.plain("${app.title} ($pkg)")
        }
    }

    /** What Settings › Messages says about a package of an app you turned on, or null when it is trusted (or not installed). */
    fun problem(pkg: String, verdict: PinVerdict): String? = problemText(pkg, verdict)?.en()

    /** [problem] in words without a language yet (Settings › Messages: the menus' language). */
    fun problemText(pkg: String, verdict: PinVerdict): Phrase? = when (verdict) {
        PinVerdict.CHANGED -> MessageStrings.signerChanged("appName" to labelText(pkg))
        PinVerdict.NOT_PINNED -> MessageStrings.installedAfter("appName" to labelText(pkg))
        PinVerdict.TRUST, PinVerdict.PIN_NOW -> null
    }
}
