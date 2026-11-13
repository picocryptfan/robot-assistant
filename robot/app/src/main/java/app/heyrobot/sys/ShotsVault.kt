// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.shots.ShotKeys
import app.heyrobot.core.shots.ShotStore
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

// MAIN AND VOICE PROCESSES. Ask about your screenshots' key and folder (screenshots-plan.md, sealing while locked): the
// index (core ShotStore) in files/shots/, sealed to robot_shots_v1. The key is Robot Link's inbox key's kind (RSA-3072,
// decrypt only, OAEP with SHA-256, unlocked-device-required, never exported): the charging pass seals to its public
// key in software, which works while the phone is locked; reading needs the Keystore's private key, only unlocked.
// Made at switch-on, deleted with the folder at switch-off. Nothing here logs content.
object ShotsVault {
    const val DIR = "shots"

    fun store(ctx: Context): ShotStore = ShotStore(File(ctx.filesDir, DIR), KeystoreShotKeys())
}

/** robot_shots_v1 (core ShotKeys): every failure is a [KeyException] ([problemOf]): a lock is LOCKED, "not now". */
class KeystoreShotKeys : ShotKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) } // l10n: not text (a provider name)

    override fun wrap(plain: ByteArray): ByteArray = keyed {
        val pub = store().getCertificate(ALIAS)?.publicKey ?: throw KeyException(KeyProblem.GONE)
        // A plain RSA public key, so the platform's own provider encrypts, never the Keystore (which would ask the TEE).
        val soft = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA).generatePublic(X509EncodedKeySpec(pub.encoded))
        Cipher.getInstance(OAEP).apply { init(Cipher.ENCRYPT_MODE, soft, PARAMS) }.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = keyed {
        val key = store().getKey(ALIAS, null) as? PrivateKey ?: throw KeyException(KeyProblem.GONE)
        Cipher.getInstance(OAEP).apply { init(Cipher.DECRYPT_MODE, key, PARAMS) }.doFinal(wrapped)
    }

    override fun create() = keyed {
        synchronized(LOCK) {
            if (!store().containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec()) }.generateKeyPair() // l10n: not text (a provider name)
            }
        }
    }

    override fun delete() = keyed { store().deleteEntry(ALIAS) }

    private inline fun <T> keyed(block: () -> T): T = try {
        block()
    } catch (x: KeyException) {
        throw x
    } catch (x: Exception) {
        throw KeyException(problemOf(x), x)
    }

    companion object {
        const val ALIAS = "robot_shots_v1" // l10n: not text (a Keystore alias)
        private const val OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" // l10n: not text (a cipher name)
        private val PARAMS = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT) // l10n: not text (a digest name)
        private const val BITS = 3072
        private val LOCK = Any()

        /** Only a lock (or a busy Keystore) is "not now"; a wrapped key that won't open is BAD_DATA, so its batch is read
         *  past and the rest still answers (review-screenshots M3). */
        internal fun problemOf(x: Throwable): KeyProblem = when (x) {
            is javax.crypto.BadPaddingException, is javax.crypto.IllegalBlockSizeException -> KeyProblem.BAD_DATA
            is android.security.keystore.KeyPermanentlyInvalidatedException -> KeyProblem.GONE
            else -> KeyProblem.LOCKED
        }

        fun spec(): KeyGenParameterSpec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4))
            .setKeySize(BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUnlockedDeviceRequired(true)
            .build()
    }
}
