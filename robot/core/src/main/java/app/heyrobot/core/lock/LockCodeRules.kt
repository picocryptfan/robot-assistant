// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import java.security.MessageDigest

// Pure. The lock code: 4 to 12 digits (6 suggested), stored only as an HMAC-SHA256 of a fixed label, a
// salt and the digits, under a non-exportable Keystore key (app LockCodeVault, in :lock). The key protects it: a short
// code has too few bits for a slow hash to help once the file is copied, and the key never leaves the phone's security
// chip. After [FREE_TRIES] wrong tries the waits double from 30 s up to an hour; the next try's time is wall-clock, so a
// restart doesn't reset it, and moving the clock back can't strand the pad for longer than the longest wait.

/** A keyed MAC: the Keystore's HMAC-SHA256 on the phone, a plain one in the JVM tests. */
fun interface LockMac {
    fun mac(input: ByteArray): ByteArray
}

/** code.json (only :lock reads or writes it; never in a backup, never logged). */
data class LockCodeFile(val alias: String, val salt: ByteArray, val mac: ByteArray, val digits: Int) {
    override fun equals(other: Any?): Boolean = other is LockCodeFile && other.alias == alias && other.salt.contentEquals(salt) &&
        other.mac.contentEquals(mac) && other.digits == digits
    override fun hashCode(): Int = alias.hashCode() * 31 + digits
    override fun toString(): String = "LockCodeFile($alias, $digits digits)" // l10n: not text (never logged; no value in it)
}

object LockCodeRules {
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 12
    const val SUGGESTED_DIGITS = 6
    const val FREE_TRIES = 5
    const val SALT_BYTES = 16
    const val ALIAS = "robot_lock_code_v1"
    const val ALG = "hmac-sha256-ks"
    private const val LABEL = "robot-lock-code-v1" // l10n: not text (the MAC's label)

    /** The waits after the 5th, 6th, … wrong try (EST): 30 s, then 1, 2, 4, 8, 16, 32 and 60 minutes, 60 from then on. */
    val WAITS_MS: List<Long> = listOf(30_000L) + listOf(1, 2, 4, 8, 16, 32, 60).map { it * 60_000L }

    /** A code can be set: only digits, [MIN_DIGITS] to [MAX_DIGITS] of them. */
    fun valid(code: CharSequence): Boolean = code.length in MIN_DIGITS..MAX_DIGITS && code.all { it in '0'..'9' }

    /** The wait after [fails] wrong tries in a row. */
    fun waitAfter(fails: Int): Long = if (fails < FREE_TRIES) 0 else WAITS_MS[minOf(fails - FREE_TRIES, WAITS_MS.size - 1)]

    /**
     * When the next try is allowed (wall-clock ms; at or before [now]: now). A next try further away than the wait itself
     * means the clock was moved back: it counts from now.
     */
    fun nextTry(t: CodeTries, now: Long): Long {
        val wait = waitAfter(t.fails)
        return if (t.nextTry > now + wait) now + wait else maxOf(t.nextTry, now)
    }

    fun mayTry(t: CodeTries, now: Long): Boolean = nextTry(t, now) <= now

    /** A wrong try at [now]. */
    fun wrong(t: CodeTries, now: Long): CodeTries = CodeTries(t.fails + 1, now + waitAfter(t.fails + 1))

    /** A right try: the count starts over. */
    val RIGHT = CodeTries()

    /** The MAC's input: the label, the salt, then the digits (ASCII). */
    fun input(salt: ByteArray, code: CharSequence): ByteArray =
        LABEL.toByteArray(Charsets.US_ASCII) + salt + code.toString().toByteArray(Charsets.US_ASCII)

    /** A new code's file. [salt]: [SALT_BYTES] random bytes. */
    fun make(code: CharSequence, salt: ByteArray, mac: LockMac): LockCodeFile {
        require(valid(code) && salt.size == SALT_BYTES)
        return LockCodeFile(ALIAS, salt.copyOf(), mac.mac(input(salt, code)), code.length)
    }

    /** [code] is the one in [f], compared in constant time. */
    fun matches(f: LockCodeFile, code: CharSequence, mac: LockMac): Boolean =
        valid(code) && MessageDigest.isEqual(f.mac, mac.mac(input(f.salt, code)))
}
