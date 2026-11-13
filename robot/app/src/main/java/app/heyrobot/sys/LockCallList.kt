// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.Person
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.LockCalls
import app.heyrobot.core.writeAtomicBytes
import app.heyrobot.lock.LockStore
import app.heyrobot.lock.PhoneLockNow
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** seal-people-fix 5: the HMAC the call list is made with. */
interface CallMac {
    /** [key] hashed, or null when the key is missing (and not [create]d) or can't be used. */
    fun mac(key: String, create: Boolean): String?
    fun drop()
}

/** A Keystore HMAC key: never exported, and usable while the phone is locked (no unlocked-device requirement). */
class KeystoreCallMac : CallMac {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) } // l10n: not text (a provider name)

    override fun mac(key: String, create: Boolean): String? = try {
        val ks = store()
        val k = ks.getKey(ALIAS, null) as? SecretKey ?: if (!create) null else KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore") // l10n: not text (a provider name)
            .apply { init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build()) }.generateKey()
        k?.let { Mac.getInstance("HmacSHA256").apply { init(it) }.doFinal(key.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) } } // l10n: not text (an algorithm, hex)
    } catch (t: Throwable) {
        RLog.w("lock_calls_mac", t)
        null
    }

    override fun drop() = try { store().deleteEntry(ALIAS) } catch (t: Throwable) { RLog.w("lock_calls_drop", t) }

    companion object { const val ALIAS = "robot_people_calls_v1" } // l10n: not text (a Keystore alias)
}

/**
 * seal-people-fix 5 (question 47's default): files/lock_calls.json, the numbers Phone lock lets you call while People is
 * sealed ([LockCalls]). Rewritten in the same locked pass as every People write, and at each unlock and lock save; cleared
 * when that fails, and with People's Delete all and Start over, along with its key.
 */
object LockCallList {
    /** Tests only (Robolectric has no Android Keystore). */
    @Volatile internal var mac: () -> CallMac = { KeystoreCallMac() }

    fun file(ctx: Context): File = File(ctx.filesDir, LockCalls.FILE)

    /** The people every saved lock names (its Calls to People you choose); empty when locks.json can't be read, and
     *  (seal-people-fix-b 6) while Phone lock is off, so turning it off removes the file. */
    internal fun chosen(f: app.heyrobot.core.lock.LockFile?): Set<String> = f?.takeIf { it.settings.on }?.locks?.flatMap { it.extras.people }?.toSet() ?: emptySet()

    private fun chosen(ctx: Context): Set<String> = chosen(LockStore.read(ctx).ok)

    /** Under People's lock, with [people] as just written: the chosen people's entries, or no file at all (fail closed). */
    fun rewrite(ctx: Context, people: List<Person>) {
        val f = file(ctx)
        try {
            val ids = chosen(ctx)
            val m = mac()
            val e = if (ids.isEmpty()) null else LockCalls.entries(people, ids, app.heyrobot.core.lock.CallKeys::chosen) { m.mac(it, create = true) }
            if (e.isNullOrEmpty()) f.delete() else writeAtomicBytes(f, LockCalls.toJson(e).toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            RLog.w("lock_calls_write", t)
            f.delete()
        }
    }

    /** People's Delete all or Start over: the list and its key go. */
    fun clear(ctx: Context) {
        file(ctx).delete()
        mac().drop()
    }

    /** [number] may be called during a lock that names [ids], from the list (while People is sealed). */
    fun allows(ctx: Context, ids: Set<String>, number: String): Boolean = try {
        val entries = LockCalls.decode(file(ctx).takeIf { it.isFile }?.readText())
        val m = mac()
        LockCalls.allows(entries, ids, PhoneLockNow.callKeys(number)) { m.mac(it, create = false) }
    } catch (t: Throwable) {
        RLog.w("lock_calls_read", t)
        false
    }
}
