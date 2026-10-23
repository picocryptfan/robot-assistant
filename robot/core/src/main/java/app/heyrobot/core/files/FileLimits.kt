// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import java.security.SecureRandom

// Pure. Robot Files (1.4, docs/plans/files-1.4-plan.md): its caps and its ids. Every byte cap on a sealed stream is a
// whole number of STREAM chunks (64 KiB), so a stream's chunk cap and its byte cap say the same thing.

/** The caps (§2.5; EST, set again after F0's measurements). */
object FileLimits {
    const val KIB = 1024L
    const val MIB = 1024L * KIB
    const val GIB = 1024L * MIB
    /**
     * One file: 200 MiB (Q4's default, "200 MB", in §2.5's unit). Well under the 1 GiB a 1.1 to 1.3 Robot reads through
     * for an entry of a backup category it doesn't know (BackupCaps.UNKNOWN_ENTRY), so a 1.4 backup still restores there.
     */
    const val MAX_FILE = 200 * MIB
    /** Files in the cabinet: "Robot Files is full: 5,000 files." Robot never drops one to make room. */
    const val MAX_FILES = 5_000
    /** The index, as JSON before it is sealed (and in a backup's index.json). */
    const val MAX_INDEX = 32 * MIB
    /** A thumbnail (at most 320 px, made in :files). */
    const val MAX_THUMB = 64 * KIB
    /**
     * One entry of the index, encoded (its text, fields and everything else): [MAX_FILES] of them at this size and the
     * index's frame fit in [MAX_INDEX] (5,000 × 6 KiB = 29.3 MiB), whatever the script of the text.
     */
    const val MAX_ENTRY = 6 * 1024
    /** A file's search text in the index, in characters (and the whole entry within [MAX_ENTRY]). */
    const val MAX_TEXT = 4_000
    /** A file's fields, encoded. */
    const val MAX_FIELDS = 2 * 1024
    /** Free space Robot leaves on the phone: adding a file needs twice its size plus this (§2.6). */
    const val FREE_MARGIN = 1 * GIB
    /** How often a write that is under way checks the free space again. */
    const val SPACE_CHECK = 8 * MIB

    /** The free space adding [size] bytes needs (§2.6): twice the file plus [FREE_MARGIN]. */
    fun spaceToAdd(size: Long): Long = 2 * size.coerceAtLeast(0) + FREE_MARGIN
}

/** A file's id: 16 random bytes as 32 lower-case hex digits. The only thing of a file that is ever in a path. */
class FileId private constructor(val hex: String) {
    fun bytes(): ByteArray = ByteArray(BYTES) { ((Character.digit(hex[2 * it], 16) shl 4) or Character.digit(hex[2 * it + 1], 16)).toByte() }

    override fun equals(other: Any?): Boolean = other is FileId && other.hex == hex
    override fun hashCode(): Int = hex.hashCode()
    override fun toString(): String = hex

    companion object {
        const val BYTES = 16
        private val HEX = Regex("[0-9a-f]{32}")

        /** [hex] when it is an id, else null: anything that reaches a path goes through here first. */
        fun of(hex: String?): FileId? = hex?.takeIf { it.length == 2 * BYTES && HEX.matches(it) }?.let(::FileId)

        fun of(bytes: ByteArray): FileId {
            require(bytes.size == BYTES)
            return FileId(bytes.joinToString("") { "%02x".format(it) })
        }

        fun new(random: SecureRandom): FileId = of(ByteArray(BYTES).also(random::nextBytes))
    }
}
