// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

// Pure: reading a secret Robot keeps (the app's sys/KeystoreSecret: each helper's API key, the updater's GitHub token),
// and which failures mean it is gone for good. Only those remove it. A failure that may pass (the Keystore busy or locked,
// the file unreadable for a moment, anything unexpected) keeps the file and the Keystore key, and the next read tries
// again: when in doubt, the secret is kept.

/** What reading a saved secret gave. */
sealed interface SecretRead {
    /** Decrypted. [toString] never shows it (a log line, a crash text). */
    data class Present(val value: String) : SecretRead {
        override fun toString() = "Present(…)" // l10n: not text (log only)
    }

    /** None is saved. */
    data object Absent : SecretRead

    /**
     * One is saved but couldn't be read just now. Everything is kept, and a later read tries again. [reason]: a code for
     * the log (the failure's class), never a message.
     */
    data class Unavailable(val reason: String) : SecretRead

    /**
     * One is saved but can never be decrypted again: its Keystore key is gone or was replaced, or the file is damaged. It
     * is removed, and you enter it again. [reason] as for [Unavailable].
     */
    data class Invalidated(val reason: String) : SecretRead
}

object SecretReads {
    /** The stored form's IV: AES-GCM's 96 bits, the only length the Android Keystore makes or takes. */
    const val IV_BYTES = 12

    /** AES-GCM's 128-bit tag, at the end of the ciphertext. */
    const val TAG_BYTES = 16

    /** Android's own class, by name (core has none of them): a [java.security.InvalidKeyException]. */
    const val KEY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"

    /**
     * Whether [stored] can be what Robot saved: one byte of IV length ([IV_BYTES]), the IV, then the ciphertext with its
     * tag (at least [TAG_BYTES]). Anything else never decrypts, however often it is read.
     */
    fun wellFormed(stored: ByteArray): Boolean = stored.size >= 1 + IV_BYTES + TAG_BYTES && stored[0].toInt() == IV_BYTES

    /**
     * Why a read failed: [SecretRead.Invalidated] only when every later read would fail the same way, otherwise
     * [SecretRead.Unavailable].
     * - `AEADBadTagException`: the ciphertext doesn't match the key (the file was changed, or the key replaced).
     * - `KeyPermanentlyInvalidatedException`, also as a cause: Android says the key can never be used again.
     * - `UnrecoverableKeyException` without a cause: Android's KeyStore.getKey turns an invalidated key into one with its
     *   message only. With a cause, the keystore failed to answer (busy, restarting), which may pass.
     * - Everything else may pass: `KeyStoreException` and `ProviderException` (the keystore daemon),
     *   `UserNotAuthenticatedException` (the phone locked, for a key that needs it unlocked), `IllegalStateException`,
     *   an I/O error reading the file, and anything unexpected.
     */
    fun classify(t: Throwable): SecretRead {
        val why = t.javaClass.simpleName.ifEmpty { "Throwable" }
        return when {
            t is AEADBadTagException -> SecretRead.Invalidated(why)
            causes(t).any { isA(it, KEY_INVALIDATED) } -> SecretRead.Invalidated(why)
            t is UnrecoverableKeyException && t.cause == null -> SecretRead.Invalidated(why)
            else -> SecretRead.Unavailable(why)
        }
    }

    /**
     * One read, from its parts: [stored] the file's bytes (null: none saved), [key] the Keystore key (null: its alias is
     * gone), [decrypt] the key and the stored bytes to the secret. Each may throw; [classify] says what that means. Nothing
     * here deletes anything: the caller removes an [SecretRead.Invalidated] secret, and only that.
     */
    fun <K : Any> read(stored: () -> ByteArray?, key: () -> K?, decrypt: (K, ByteArray) -> String): SecretRead {
        val bytes = try { stored() } catch (t: Throwable) { return classify(t) } ?: return SecretRead.Absent
        if (!wellFormed(bytes)) return SecretRead.Invalidated("malformed")
        val k = try { key() } catch (t: Throwable) { return classify(t) } ?: return SecretRead.Invalidated("no_key")
        return try { SecretRead.Present(decrypt(k, bytes)) } catch (t: Throwable) { classify(t) }
    }

    private fun causes(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8)

    /** [t] is of the class named [name] or a subclass of it. */
    private fun isA(t: Throwable, name: String): Boolean = generateSequence<Class<*>>(t.javaClass) { it.superclass }.any { it.name == name }
}
