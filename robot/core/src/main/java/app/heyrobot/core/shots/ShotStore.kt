// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import app.heyrobot.core.readOrNull
import app.heyrobot.core.seal.Envelope
import app.heyrobot.core.seal.EnvelopeException
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.seal.KindPurpose
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.seal.SealKind
import app.heyrobot.core.writeAtomic
import app.heyrobot.core.writeAtomicBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom

// Pure (a folder and a key). The sealed index (screenshots-plan.md, "The code it reuses": sealing while locked). Each
// batch is sealed on its own in the envelope's format 2 (core seal Envelope): a fresh key per batch, wrapped with
// robot_shots_v1's public key in software, which works while the phone is locked and charging; opening needs the
// Keystore's private key, which only works unlocked. Locked, [load] is Locked: nothing is shown or spoken (fail closed).
// [switchOff] deletes the folder and the key together. The folder (files/shots/) holds the batches and state.json.

/** robot_shots_v1: wrap in software (works locked), unwrap only unlocked; made at switch-on, deleted at switch-off. */
interface ShotKeys : PublicWrap {
    fun create()
    fun delete()
}

class ShotStore(private val dir: File, private val keys: ShotKeys, private val random: SecureRandom = SecureRandom()) {
    sealed class Load {
        /** [unreadable]: batches that didn't open (damaged, or a wrapped key that won't open), read past. */
        data class Ready(val entries: Map<Long, ShotEntry>, val unreadable: Int = 0) : Load()
        /** The phone is locked (or the key is busy): "unlock first". */
        data object Locked : Load()
        /** Switched off, or its key gone: nothing to answer from. */
        data object Off : Load()
    }

    private val stateFile get() = File(dir, STATE)
    /** Made before a switch-off deletes anything, removed after: while it's there the index reads as off, and the next
     *  lock or load finishes the switch-off (a kill part way, review-screenshots-b Low). Beside the folder, as the lock. */
    private val offMark get() = File(dir.parentFile, ".${dir.name}.off")

    fun state(): ShotsState = if (offMark.exists()) ShotsState() else ShotsState.fromJson(readOrNull(stateFile))

    private fun save(s: ShotsState) {
        dir.mkdirs()
        writeAtomic(stateFile, s.toJson())
    }

    /** Held across processes too (the pass runs in the voice process, the settings page in the main one): beside the
     *  folder, since switching off deletes the folder. */
    private inline fun <T> locked(block: () -> T): T = synchronized(JVM_LOCK) {
        dir.parentFile?.mkdirs()
        RandomAccessFile(File(dir.parentFile, ".${dir.name}.lock"), "rw").use { f -> // l10n: not text (a file mode)
            val lock = f.channel.lock()
            try { if (offMark.exists()) off(); block() } finally { lock.release() }
        }
    }

    /** Switched on at [now], with MediaStore's [generation] then: a fresh key, a fresh epoch and an empty index. */
    fun switchOn(now: Long, generation: Long) = locked {
        off()
        keys.create()
        save(ShotsState(on = true, since = now, generation = generation, epoch = random.nextLong().takeIf { it != 0L } ?: 1L))
    }

    /** Switched off: the index, its state and its key deleted. True when nothing is left. */
    fun switchOff(): Boolean = locked { off() }

    private fun off(): Boolean {
        offMark.createNewFile()
        try { keys.delete() } catch (_: Exception) {}
        dir.deleteRecursively()
        return !dir.exists() && offMark.delete()
    }

    /** A pass's batch sealed (needs no unlock) and the state after it saved. Nothing is written while it is off, or when
     *  the index changed since the pass read its state (deleted, switched off and on, or another pass's batch): a delete
     *  during a pass wins, and the pass's batch is dropped. */
    fun append(batch: ShotBatch, after: ShotsState): Boolean = locked {
        val s = state()
        if (!s.on || s.epoch != after.epoch || s.next != after.next) {
            false
        } else {
            if (batch.add.isNotEmpty() || batch.drop.isNotEmpty()) {
                writeAtomicBytes(File(dir, name(s.next)), seal(batch, s.next))
                save(after.copy(next = s.next + 1))
            } else {
                save(after.copy(next = s.next))
            }
            true
        }
    }

    fun load(): Load {
        if (offMark.exists()) locked { }
        if (!state().on) return Load.Off
        val files = dir.listFiles { f -> f.name.startsWith(PREFIX) }.orEmpty().mapNotNull { f -> f.name.removePrefix(PREFIX).toLongOrNull()?.let { it to f } }
            .sortedBy { it.first }
        return try {
            var bad = 0
            val batches = files.mapNotNull { (n, f) ->
                (try { open(f.readBytes(), n) } catch (x: EnvelopeException) { null }) ?: null.also { bad++ }
            }
            Load.Ready(ShotIndex.fold(batches), bad)
        } catch (x: KeyException) {
            if (x.problem == KeyProblem.LOCKED) Load.Locked else Load.Off
        }
    }

    private fun seal(batch: ShotBatch, n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        Envelope.writer(out, KIND, PURPOSE, id(n), public = keys, random = random).use { it.write(batch.toJson().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** One batch opened; a locked key throws [KeyException] (LOCKED) out of the envelope's mapping. */
    private fun open(bytes: ByteArray, n: Long): ShotBatch? {
        val r = try {
            Envelope.reader(bytes.inputStream(), KIND, PURPOSE, id(n), public = keys)
        } catch (x: EnvelopeException) {
            if (x.problem.name == "LOCKED") throw KeyException(KeyProblem.LOCKED)
            if (x.problem.name == "GONE") throw KeyException(KeyProblem.GONE)
            return null
        }
        return r.use { ShotBatch.fromJson(String(it.readAll(MAX_BYTES.toInt()), Charsets.UTF_8)) }
    }

    companion object {
        const val STATE = "state.json"
        const val PREFIX = "b"
        const val MAX_BYTES = 4L shl 20
        private val JVM_LOCK = Any()
        val PURPOSE = KindPurpose(1, Envelope.FORMAT_PUBLIC, MAX_BYTES)
        val KIND = SealKind("ROBOTSHT", null, listOf(PURPOSE)) // l10n: not text (a file's magic)

        fun name(n: Long) = PREFIX + n
        private fun id(n: Long): ByteArray = ByteBuffer.allocate(Envelope.ID).putLong(8, n).array()
    }
}
