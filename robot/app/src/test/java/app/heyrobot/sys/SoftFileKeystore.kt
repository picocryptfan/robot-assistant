// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.security.keystore.KeyGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Android Keystore in software, for [KeystoreFileKeys] in the tests: AES-256 keys by alias, made by the platform's
 * software provider (so the cipher calls are the real code's). [locked]: every key throws when asked, as robot_files_v1
 * can't be used on a locked phone. [failKey]: what asking for a key throws (an invalidated key, a Keystore error).
 * [generated] counts the keys made; [specs] what they were made with.
 */
class SoftFileKeystore(present: Boolean = true) : FileKeystore {
    val keys = HashMap<String, SecretKey>()
    var locked = false
    var failKey: (() -> Exception)? = null
    /** contains() says yes but key() finds nothing (an entry that isn't a secret key, or deleted in between). */
    var hollow = false
    var generated = 0
        private set
    val specs = ArrayList<KeyGenParameterSpec>()
    val deleted = ArrayList<String>()

    init {
        if (present) keys[KeystoreFileKeys.ALIAS] = soft()
    }

    private fun soft(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun contains(alias: String): Boolean = hollow || alias in keys

    override fun key(alias: String): SecretKey? {
        if (locked) throw java.security.KeyStoreException("locked")  // l10n: not text (a test's exception)
        failKey?.let { throw it() }
        return if (hollow) null else keys[alias]
    }

    override fun generate(spec: KeyGenParameterSpec): SecretKey {
        generated++
        specs += spec
        return soft().also { keys[spec.keystoreAlias] = it }
    }

    override fun delete(alias: String) {
        deleted += alias
        keys.remove(alias)
    }
}
