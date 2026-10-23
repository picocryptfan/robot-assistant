// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.heyrobot.core.RLog
import app.heyrobot.core.SecretRead
import app.heyrobot.core.SecretReads
import app.heyrobot.core.lock.LockCodeFile
import app.heyrobot.core.lock.LockCodeRules
import app.heyrobot.core.lock.LockMac
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

// :LOCK PROCESS ONLY (ProcessIsolationTest: only LockCodeActivity makes one). The lock code: code.json holds
// a salt and HMAC-SHA256(key, label ‖ salt ‖ digits), never the code. The key is an Android Keystore HMAC key, StrongBox
// when the phone has one (else the TEE), not exportable, made without setUnlockedDeviceRequired as MemoryVault's
// lock-screen key is (a child's phone may have no PIN, and the code is only checked on an unlocked screen anyway). A new
// code gets a new salt and a new key; removing the code deletes the key. The code never goes in a log, a backup or the
// voice pipeline.
//
// When in doubt, the code stays. Only a key that is gone for good counts as lost (the Keystore has nothing under the alias
// code.json names, or Android says the key can never be used again): that turns off locks for someone else (§7.1). Any
// other failure (the Keystore busy, StrongBox not answering, code.json unreadable for a moment) is [Check.NotNow]: nothing
// changes and the pad asks you to try again. A new code's key goes under the alias code.json doesn't name, alternating
// robot_lock_code_v1 and robot_lock_code_v1b, and the old key goes only once code.json names the new one and reads back.

/** The Keystore as the lock code uses it, by alias. Each call may throw; the vault decides what a failure means. */
interface LockCodeKeys {
    /** The key under [alias], or null when the Keystore has none there. */
    fun key(alias: String): SecretKey?

    /** A new key under [alias], replacing whatever was there. */
    fun make(alias: String): SecretKey

    fun delete(alias: String)
}

/** The Android Keystore: HMAC-SHA256 signing keys, StrongBox when the phone has one, else the TEE. */
class AndroidLockCodeKeys : LockCodeKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // An entry that isn't a secret key is an answer Robot doesn't understand, not a key gone: not now.
    override fun key(alias: String): SecretKey? = store().getKey(alias, null)?.let { it as? SecretKey ?: throw KeyStoreException() }

    override fun make(alias: String): SecretKey = try { generate(alias, strongBox = true) } catch (_: StrongBoxUnavailableException) { generate(alias, strongBox = false) }

    override fun delete(alias: String) = store().deleteEntry(alias)

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setIsStrongBoxBacked(strongBox)
            .build())
        return gen.generateKey()
    }
}

class LockCodeVault internal constructor(
    private val file: File,
    private val keys: LockCodeKeys,
    /** HMAC-SHA256 of the input under the key: the Keystore's on the phone. */
    private val hmac: (SecretKey, ByteArray) -> ByteArray = { key, input -> Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input) },
) {
    constructor(ctx: Context) : this(File(LockStore.dir(ctx), FILE), AndroidLockCodeKeys())

    sealed class Check {
        data object Right : Check()
        data object Wrong : Check()
        /** code.json is there but its key is gone for good (the app's Keystore was cleared): the code can't be checked. */
        data object KeyLost : Check()
        /** code.json, the Keystore or the MAC failed in a way that may pass: nothing is known, nothing changes, try again. */
        data object NotNow : Check()
        data object NoCode : Check()
    }

    private sealed interface Read {
        data object None : Read
        /** code.json is there but couldn't be read, or isn't one Robot wrote: never taken for no code or a lost key. */
        data object Failed : Read
        data class Ok(val f: LockCodeFile) : Read
    }

    private fun read(): Read {
        if (!file.exists()) return Read.None
        return try {
            Read.Ok(decode(file.readText(Charsets.UTF_8)))
        } catch (t: Throwable) {
            RLog.w("lock_code_read", t)
            Read.Failed
        }
    }

    private fun mac(key: SecretKey) = LockMac { input -> hmac(key, input) }

    fun isSet(): Boolean = file.exists()

    /** [code] against code.json. Never logged. */
    fun check(code: CharSequence): Check {
        val f = when (val r = read()) {
            Read.None -> return Check.NoCode
            Read.Failed -> return Check.NotNow
            is Read.Ok -> r.f
        }
        return try {
            val key = keys.key(f.alias)
            when {
                key == null -> { RLog.w("lock_key_gone"); Check.KeyLost }
                LockCodeRules.matches(f, code, mac(key)) -> Check.Right
                else -> Check.Wrong
            }
        } catch (t: Throwable) {
            RLog.w("lock_check", t)
            if (gone(t)) Check.KeyLost else Check.NotNow
        }
    }

    /**
     * A new code: a new key under the alias code.json doesn't name and a new salt, then code.json, read back and checked,
     * and only then the old key deleted. False when it couldn't be made; the old code, if there was one, still works.
     */
    fun set(code: CharSequence): Boolean {
        if (!LockCodeRules.valid(code)) return false
        val old = when (val r = read()) {
            Read.None -> null
            // Which alias is in use is unknown: a key made now could replace the one code.json needs.
            Read.Failed -> return false
            is Read.Ok -> r.f
        }
        val alias = other(old?.alias)
        val made = try {
            val salt = ByteArray(LockCodeRules.SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val f = LockCodeRules.make(code, salt, mac(keys.make(alias))).copy(alias = alias)
            // A failed write leaves code.json as it was (writeAtomic renames last).
            writeAtomic(file, encode(f))
            f
        } catch (t: Throwable) {
            RLog.w("lock_set", t)
            drop(alias)
            return false
        }
        if (readsBack(made, code)) {
            // The old key, or one an earlier try left under the other alias.
            drop(other(alias))
            return true
        }
        // Not what was written: the old file goes back (its key is still there), then the new key goes.
        val undone = try {
            if (old != null) {
                writeAtomic(file, encode(old))
                true
            } else {
                file.delete()
                !file.exists()
            }
        } catch (t: Throwable) {
            RLog.w("lock_set_undo", t)
            false
        }
        if (undone) drop(alias)
        return false
    }

    /** code.json reads back as [f], and [code] matches it under the key the Keystore holds for its alias now. */
    private fun readsBack(f: LockCodeFile, code: CharSequence): Boolean = try {
        val r = read()
        r is Read.Ok && r.f == f && keys.key(f.alias)?.let { LockCodeRules.matches(f, code, mac(it)) } == true
    } catch (t: Throwable) {
        RLog.w("lock_set_check", t)
        false
    }

    /**
     * The code gone: code.json, then both keys, but only once the file is gone (a key never goes while a file names it, or
     * the next try would take the code for lost). True when no code is left.
     */
    fun remove(): Boolean {
        file.delete()
        if (file.exists()) {
            RLog.w("lock_code_remove")
            return false
        }
        for (a in ALIASES) drop(a)
        return true
    }

    private fun drop(alias: String) {
        try { keys.delete(alias) } catch (t: Throwable) { RLog.w("lock_key_delete", t) }
    }

    private fun encode(f: LockCodeFile): String = JSONObject().put("v", 1).put("alg", LockCodeRules.ALG).put("alias", f.alias)
        .put("salt", Base64.getEncoder().encodeToString(f.salt)).put("mac", Base64.getEncoder().encodeToString(f.mac))
        .put("digits", f.digits).toString()

    /** code.json as Robot writes it, or an exception: the version, the algorithm, one of the two aliases, the sizes. */
    private fun decode(t: String): LockCodeFile {
        val o = JSONObject(t)
        require(o.getInt("v") == 1 && o.getString("alg") == LockCodeRules.ALG)
        val b64 = Base64.getDecoder()
        val f = LockCodeFile(o.getString("alias"), b64.decode(o.getString("salt")), b64.decode(o.getString("mac")), o.getInt("digits"))
        require(f.alias in ALIASES && f.salt.size == LockCodeRules.SALT_BYTES && f.mac.size == MAC_BYTES &&
            f.digits in LockCodeRules.MIN_DIGITS..LockCodeRules.MAX_DIGITS)
        return f
    }

    internal companion object {
        const val FILE = "code.json"
        /** The alias a new code's key alternates with (LockCodeRules.ALIAS is the other, and every older code's). */
        const val ALIAS_B = "robot_lock_code_v1b"
        val ALIASES = listOf(LockCodeRules.ALIAS, ALIAS_B)
        /** HMAC-SHA256's length. */
        const val MAC_BYTES = 32

        /** The alias that isn't [alias] (for none, the first). */
        fun other(alias: String?): String = ALIASES.first { it != alias }

        /**
         * A failure that means the key is gone for good: Android says it can never be used again (KeyPermanentlyInvalidated,
         * also as a cause, or getKey's UnrecoverableKeyException with no cause). core's SecretReads decides that for every
         * Keystore key Robot keeps; anything else may pass.
         */
        fun gone(t: Throwable): Boolean = SecretReads.classify(t) is SecretRead.Invalidated
    }
}
