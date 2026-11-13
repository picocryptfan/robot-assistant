// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.heyrobot.core.link.LinkInbox
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.seal.SecretWrap
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

// THE :link PROCESS (from L3, which starts it). 1.5, Robot to Robot's keys (robot-to-robot-plan.md §2.4, Q3's default; job
// L2): the two Keystore keys and the state they seal, with its locked and gone states. The only place either key is made
// (ProcessIsolationTest's linkStaysInItsProcess).
//
//   robot_link_v1    AES-256-GCM, TEE (not StrongBox: slow for this, as Files' key), randomized, never exported, and NOT
//                    unlocked-device-required: Q3's default (a) lets :link answer what you set to Answer on its own while
//                    the phone is locked, after its first unlock since boot (files/ is credential-encrypted storage, as
//                    messaging apps' keys are). Seals libsignal's state (identity, prekeys, sessions; from L3 and L5 the
//                    pairings and the onion service key) in files/link/state/ (core LinkRecords): a fresh key per write.
//   robot_inbox_v1   RSA-3072, decrypt only, OAEP with SHA-256 (MGF1 with SHA-1: choosing MGF1's digest needs API 35, and
//                    minSdk is 34), unlocked-device-required, never exported. Wrap type 2: what arrives while the phone is
//                    locked and must wait (core LinkInbox; FileSeal format 2 for files) is sealed to its public key, in
//                    software, which works while locked; opening it is the Keystore's decryption, only while unlocked.
//
// Either key is made only while nothing is sealed under it (its folder empty); an existing alias is never made again or
// replaced. A key Android invalidated, or one missing while something is sealed under it, is GONE; a record that doesn't
// open is DAMAGED: both are "Robot to Robot's keys are gone: pair again with each person", with Start over (a tap) as the
// only way out. Robot never deletes either key or anything sealed on its own. Anything else a Keystore throws is LOCKED,
// "not now". The vault reads nothing on its own: :link (L3) opens the stores (the link module's LinkStores) over
// [LinkVault.records] once at its start, while [LinkVault.state] says Ready.

/** The Keystore calls the vault makes: the phone's AndroidKeyStore, or a software stand-in in the tests. */
interface LinkKeystore {
    fun contains(alias: String): Boolean
    fun secretKey(alias: String): SecretKey?
    fun privateKey(alias: String): PrivateKey?
    /** The public key of [alias]'s certificate (never a secret: readable while locked). */
    fun publicKey(alias: String): PublicKey?
    fun generateSecret(spec: KeyGenParameterSpec): SecretKey
    fun generatePair(spec: KeyGenParameterSpec): KeyPair
    fun delete(alias: String)
}

/** The Android Keystore. */
class AndroidLinkKeystore : LinkKeystore {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) } // l10n: not text (a provider name)

    override fun contains(alias: String): Boolean = store().containsAlias(alias)
    override fun secretKey(alias: String): SecretKey? = store().getKey(alias, null) as? SecretKey
    override fun privateKey(alias: String): PrivateKey? = store().getKey(alias, null) as? PrivateKey
    override fun publicKey(alias: String): PublicKey? = store().getCertificate(alias)?.publicKey
    override fun generateSecret(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() } // l10n: not text (a provider name)
    override fun generatePair(spec: KeyGenParameterSpec): KeyPair =
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").run { initialize(spec); generateKeyPair() } // l10n: not text (a provider name)
    override fun delete(alias: String) = store().deleteEntry(alias)
}

/** robot_link_v1 (core SecretWrap's contract): every failure is a [KeyException]. */
class KeystoreLinkKeys(private val keystore: LinkKeystore = AndroidLinkKeystore()) : SecretWrap {
    override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray = keyed {
        require(key.size == KEY)
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, key(create))
        c.updateAAD(aad)
        val sealed = c.doFinal(key)
        val iv = c.iv
        // The Keystore's own random 12-byte IV, then the key and the tag: 60 bytes, or nothing (not now).
        check(iv != null && iv.size == IV && sealed.size == KEY + TAG)
        iv + sealed
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        if (wrapped.size != IV + KEY + TAG) throw KeyException(KeyProblem.BAD_DATA)
        return keyed {
            val c = Cipher.getInstance(GCM)
            c.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG * 8, wrapped, 0, IV))
            c.updateAAD(aad)
            c.doFinal(wrapped, IV, wrapped.size - IV).also { if (it.size != KEY) throw KeyException(KeyProblem.BAD_DATA) }
        }
    }

    /** Whether robot_link_v1 exists (nothing decrypted: also while locked). */
    fun present(): Boolean = keyed { keystore.contains(ALIAS) }

    /** Start over (your tap): robot_link_v1 deleted, so the next setup makes a new one. */
    fun delete() = keyed { keystore.delete(ALIAS) }

    /** robot_link_v1: the Keystore's when it has the alias (never made again over it), else a new one only when [create]. */
    private fun key(create: Boolean): SecretKey = synchronized(LOCK) {
        when {
            keystore.contains(ALIAS) -> keystore.secretKey(ALIAS) ?: throw KeyException(KeyProblem.GONE)
            create -> keystore.generateSecret(spec())
            else -> throw KeyException(KeyProblem.GONE)
        }
    }

    companion object {
        const val ALIAS = "robot_link_v1" // l10n: not text (a Keystore alias)
        private const val GCM = "AES/GCM/NoPadding" // l10n: not text (a cipher name)
        private const val IV = 12
        private const val KEY = 32
        private const val TAG = 16
        private val LOCK = Any()

        /**
         * How robot_link_v1 is made (§2.4): AES-256-GCM, randomized, TEE (not StrongBox), no user authentication, and the
         * phone NOT required unlocked (Q3's default "a": :link answers on its own while locked, after the first unlock).
         */
        fun spec(): KeyGenParameterSpec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(false)
            .build()
    }
}

/** L8 (§3.6, §5.4): robot_inbox_v1's use outside :link, to read what arrived once the phone is unlocked; its key stays named and made here. */
fun arrivalKeys(): PublicWrap = KeystoreInboxKeys()

/** robot_inbox_v1 (core PublicWrap's contract): every failure is a [KeyException]. */
class KeystoreInboxKeys(private val keystore: LinkKeystore = AndroidLinkKeystore()) : PublicWrap {
    /** RSA-OAEP with the certificate's public key, in software (not the Keystore): works while the phone is locked. */
    override fun wrap(plain: ByteArray): ByteArray = keyed {
        require(plain.size in 1..PLAIN_MAX)
        val pub = keystore.publicKey(ALIAS) ?: throw KeyException(KeyProblem.GONE)
        // A plain RSA public key, so the platform's own provider encrypts, never the Keystore's (which would ask the TEE).
        val soft = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA).generatePublic(X509EncodedKeySpec(pub.encoded))
        val c = Cipher.getInstance(OAEP)
        c.init(Cipher.ENCRYPT_MODE, soft, PARAMS)
        c.doFinal(plain).also { check(it.size == WRAPPED) }
    }

    /** The Keystore's RSA decryption: only while the phone is unlocked (LOCKED otherwise, never gone). */
    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size != WRAPPED) throw KeyException(KeyProblem.BAD_DATA)
        return keyed {
            val key = keystore.privateKey(ALIAS) ?: throw KeyException(KeyProblem.GONE)
            val c = Cipher.getInstance(OAEP)
            c.init(Cipher.DECRYPT_MODE, key, PARAMS)
            c.doFinal(wrapped)
        }
    }

    /** Whether robot_inbox_v1 exists. */
    fun present(): Boolean = keyed { keystore.contains(ALIAS) }

    /** Makes robot_inbox_v1 when there is none: only while nothing is sealed under it ([LinkVault.setUp] checks). */
    fun create() = keyed { synchronized(LOCK) { if (!keystore.contains(ALIAS)) keystore.generatePair(spec()) } }

    /** Start over (your tap). */
    fun delete() = keyed { keystore.delete(ALIAS) }

    companion object {
        const val ALIAS = "robot_inbox_v1" // l10n: not text (a Keystore alias)
        /** One transformation for both sides, with its parameters named in full: providers differ in what the name implies. */
        private const val OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" // l10n: not text (a cipher name)
        val PARAMS = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT) // l10n: not text (a digest name)
        const val BITS = 3072
        const val WRAPPED = BITS / 8
        /** RSA-OAEP with SHA-256 in one 3072-bit block. */
        const val PLAIN_MAX = WRAPPED - 2 * 32 - 2
        private val LOCK = Any()

        /** How robot_inbox_v1 is made (§2.4): RSA-3072, decrypt only, OAEP with SHA-256, the phone unlocked to use it. */
        fun spec(): KeyGenParameterSpec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4))
            .setKeySize(BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUnlockedDeviceRequired(true)
            .build()
    }
}

/** What :link can do with its state now. */
sealed class VaultState {
    /** Robot to Robot was never set up here (or you started over): no keys, nothing sealed. */
    object Off : VaultState()
    /** Both keys and the state are there: :link opens the stores over [LinkVault.records]. */
    object Ready : VaultState()
    /** The Keystore can't be used now. Not gone: try again later. */
    object Locked : VaultState()
    /** "Robot to Robot's keys are gone: pair again with each person": kept as it is until Start over. */
    data class Gone(val why: LinkStateProblem) : VaultState()
    /** A newer Robot's state: never read or written over. */
    object Newer : VaultState()
}

/**
 * Robot to Robot's keys and sealed state in [dir] (files/link): [records] (state/, robot_link_v1) and [inbox] (inbox/,
 * robot_inbox_v1). Pure java.io and the Keystore through [keystore], so the JVM tests run it with software keys.
 */
class LinkVault(val dir: File, keystore: LinkKeystore = AndroidLinkKeystore(), random: SecureRandom = SecureRandom()) {
    private val linkKeys = KeystoreLinkKeys(keystore)
    private val inboxKeys = KeystoreInboxKeys(keystore)
    /** L8: robot_inbox_v1 as a stream's wrap: an arrival sealed for later (format 2) needs no unlock. */
    val inboxWrap: app.heyrobot.core.seal.PublicWrap get() = inboxKeys
    val records = LinkRecords(File(dir, STATE), linkKeys, random)
    val inbox = LinkInbox(File(dir, INBOX), inboxKeys, random)
    /** 1.5: another Robot's list parts and changes until :voice applies them, sealed under the same key. */
    val listInbox = LinkInbox(File(dir, LIST_INBOX), inboxKeys, random)

    /**
     * Where things stand, without decrypting anything (also while locked): Off when no state is sealed (nothing here, or a
     * setup cut short before the identity was written: [setUp] goes on from there); Gone when a key is missing while
     * something is sealed under it; otherwise Ready (a record that doesn't open shows up when :link reads the state,
     * [problem]).
     */
    fun state(): VaultState = try {
        val sealed = records.anything()
        val waiting = inbox.anything() || listInbox.anything()
        when {
            (sealed && !linkKeys.present()) || (waiting && !inboxKeys.present()) -> VaultState.Gone(LinkStateProblem.GONE)
            !sealed -> VaultState.Off
            else -> VaultState.Ready
        }
    } catch (x: KeyException) {
        if (x.problem == KeyProblem.GONE) VaultState.Gone(LinkStateProblem.GONE) else VaultState.Locked
    }

    /**
     * robot_inbox_v1 ready to seal what waits: made again when it is missing and nothing is sealed under it (nothing is lost
     * then). False when it can't be now (the Keystore busy) or items wait without it (Gone).
     */
    fun inboxReady(): Boolean = try {
        when {
            inboxKeys.present() -> true
            inbox.anything() || listInbox.anything() -> false
            else -> { inboxKeys.create(); true }
        }
    } catch (_: KeyException) {
        false
    }

    /** What a [LinkStateException] from reading the state means for the screens and :link. */
    fun problem(x: LinkStateException): VaultState = when (x.problem) {
        LinkStateProblem.LOCKED, LinkStateProblem.IO -> VaultState.Locked
        LinkStateProblem.NEWER -> VaultState.Newer
        else -> VaultState.Gone(x.problem)
    }

    /**
     * Turning Robot to Robot on: robot_inbox_v1 made now (when there is none), robot_link_v1 with the first record (the
     * identity, which :link makes next with the link module's LinkStores.create). Refused (false) unless the state is Off:
     * never over anything sealed, and an existing alias is used as it is, never made again.
     */
    fun setUp(): Boolean = state() == VaultState.Off && inboxReady()

    /** Start over (your tap, after its dialog): everything sealed destroyed, then both keys deleted. R7: false, a key stayed. */
    fun startOver(): Boolean {
        records.deleteAll()
        inbox.deleteAll()
        listInbox.deleteAll()
        val link = try { linkKeys.delete(); true } catch (_: KeyException) { false }
        val inboxKey = try { inboxKeys.delete(); true } catch (_: KeyException) { false }
        return link && inboxKey
    }

    companion object {
        const val STATE = "state" // l10n: not text (a folder name)
        const val INBOX = "inbox" // l10n: not text (a folder name)
        const val LIST_INBOX = "inbox_lists" // l10n: not text (a folder name)

        /** files/link, :link's own folder (credential-encrypted storage). */
        fun dir(filesDir: File): File = File(filesDir, "link") // l10n: not text (a folder name)
    }
}

/** A Keystore call with its problems as [KeyException]s: invalidated is GONE, a check that fails BAD_DATA, the rest LOCKED. */
private inline fun <T> keyed(block: () -> T): T = try {
    block()
} catch (t: Exception) {
    throw t as? KeyException ?: KeyException(problemOf(t), t)
}

/**
 * What a Keystore failure means, from the exception and its causes (Android may wrap an invalidated key's exception):
 * invalidated is GONE, a tag or padding that fails BAD_DATA, anything else LOCKED ("not now", never a reason to drop anything).
 */
internal fun problemOf(t: Throwable): KeyProblem {
    var c: Throwable? = t
    var depth = 0
    while (c != null && depth++ < 8) {
        when (c) {
            is KeyException -> return c.problem
            is KeyPermanentlyInvalidatedException -> return KeyProblem.GONE
            is AEADBadTagException, is BadPaddingException -> return KeyProblem.BAD_DATA
            // seal-history-fix-b 2: Android 15's getKey rethrows an invalidated key as this, with no cause.
            is java.security.UnrecoverableKeyException -> if (c.cause == null) return KeyProblem.GONE
        }
        c = c.cause
    }
    return KeyProblem.LOCKED
}
