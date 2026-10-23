// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

// Pure. The one key Robot Files keeps, robot_files_v1, behind an interface: the app implements it with the Android
// Keystore (F2, app/sys/FilesVault.kt), the JVM tests with a software fake. It never sees a file's bytes: it wraps and
// unwraps the fresh 32-byte key of each sealed stream (FileSeal), one Keystore operation on 32 bytes each time.

/** Why robot_files_v1 couldn't wrap or unwrap a key. The app's vault maps the Keystore's exceptions to these. */
enum class FileKeyProblem {
    /** The phone is locked (the key needs it unlocked), or the Keystore is busy: "not now", never "gone". */
    LOCKED,
    /** KeyPermanentlyInvalidatedException, or no key under the alias (and [FileKeys.wrap] wasn't told to make one). */
    GONE,
    /** The wrapped key's tag didn't verify: changed, moved to another file or purpose, or wrapped by another key. */
    BAD_DATA,
}

class FileKeyException(val problem: FileKeyProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * robot_files_v1: AES-256-GCM in the Android Keystore (TEE, not StrongBox), made with setUnlockedDeviceRequired(true) and
 * randomized encryption, never exported (files-1.4-plan.md §2.3). Every call may throw [FileKeyException]; anything else a
 * Keystore throws is read by the callers as [FileKeyProblem.LOCKED] ("not now").
 */
interface FileKeys {
    /**
     * [key] (32 bytes) wrapped with robot_files_v1, [aad] as GCM's additional data: the Keystore's own random 12-byte IV,
     * then the ciphertext and the 16-byte tag, [FileSeal.WRAPPED] (60) bytes in all. [create]: make robot_files_v1 first
     * when there is none (only for a cabinet with nothing sealed in it yet); without it, a missing key is
     * [FileKeyProblem.GONE].
     */
    fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray

    /** The 32-byte key in [wrapped], which [wrap] made with the same [aad]. */
    fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray

    /** Deletes robot_files_v1 (Delete them all, after it was lost), so the next sealed stream makes a new one. */
    fun delete()
}
