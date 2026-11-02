// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import app.heyrobot.core.link.HelloBundle
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLog
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.seal.SecretWrap
import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.link.Waiting
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.r2r.DeskBox
import app.heyrobot.r2r.DeskEnv
import app.heyrobot.r2r.LinkDesk
import app.heyrobot.r2r.LinkEnv
import app.heyrobot.r2r.LinkNode
import app.heyrobot.r2r.LinkStateStore
import app.heyrobot.r2r.NodeKeys
import app.heyrobot.r2r.PairingRecord
import app.heyrobot.r2r.PairingState
import app.heyrobot.r2r.SessionBytes
import app.heyrobot.r2r.SessionException
import app.heyrobot.r2r.SessionProblem
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * L5-fix: a real LinkNode for the app's Robolectric tests, which load no libsignal (app/build.gradle): its state in sealed
 * records under a software key, keys that hold no session (every encryption fails, so nothing is ever sent), no carrier.
 * LinkRuntime's test seam (opener) opens it in place of the vault.
 */
class TestLinkNode(dir: File) {
    val records = LinkRecords(File(dir, "state").apply { mkdirs() }, SoftWrap())
    val state = LinkStateStore(records).also { it.load(emptyMap()) }
    /** L6: the phone as the desk sees it (the test sets it). */
    @Volatile var locked = false
    /** L6: the waiting inbox, in memory: it opens only while [locked] is false (as robot_inbox_v1 does). */
    val box = object : DeskBox {
        private val items = LinkedHashMap<String, Waiting>()
        @Synchronized override fun put(pairing: String, at: Long, request: ByteArray): String = LinkIds.newId().also { items[it] = Waiting(it, pairing, at, request) }
        @Synchronized override fun ids(): List<String> = items.keys.toList()
        @Synchronized override fun count(): Int = items.size
        @Synchronized override fun open(id: String): Waiting? { if (locked) throw LinkStateException(LinkStateProblem.LOCKED); return items[id] }
        @Synchronized override fun delete(id: String) { items.remove(id) }
    }
    val pings = java.util.concurrent.atomic.AtomicInteger()
    /** L6: a real desk, its requests read as :linkparse reads them (L1's decoder, LinkParseWire's frame, checked again). */
    val desk = LinkDesk(state, box, object : DeskEnv {
        override fun locked() = this@TestLinkNode.locked
        override fun phoneLock() = false
        override fun settings() = LinkSettings(on = true)
        override fun ping() { pings.incrementAndGet() }
    }, { plain, cap -> LinkParseWire.check(LinkParseWire.encodeReply(LinkRequests.decode(plain, cap)), plain, cap) })
    val node = LinkNode(NoSessions(), state, Env(), { _, _ -> null }, desk, null, null, null, null, LinkLog(null), { "Robot" }) // l10n: not text (a test's name)
        .also { desk.attach(it.deskOut) }

    /** An active Private pairing, as pairing would leave it. */
    fun pairing(): String {
        val id = LinkIds.newId()
        state.put(PairingRecord(id, PairingState.ACTIVE, LinkMode.PRIVATE, System.currentTimeMillis() / 1000, "Sam", ByteArray(LinkCaps.BITMAP))) // l10n: not text (a test's name)
        return id
    }

    private class Env : LinkEnv {
        override fun now(): Long = System.currentTimeMillis()
        override fun networkAllowed(): Boolean = true
    }

    private class NoSessions : NodeKeys {
        override val identityKey: ByteArray = byteArrayOf(5) + ByteArray(32) { 1 }
        override fun newKyberPreKey(now: Long): Int = throw SessionException(SessionProblem.STATE)
        override fun bundle(kyberId: Int, now: Long): HelloBundle = throw SessionException(SessionProblem.NO_PREKEY)
        override fun pin(pairing: String, identity: ByteArray) {}
        override fun identityOf(pairing: String): ByteArray? = null
        override fun startSession(pairing: String, bundle: HelloBundle, now: Long) = throw SessionException(SessionProblem.INVALID)
        override fun replaceSession(pairing: String, identity: ByteArray, bundle: HelloBundle, now: Long) = throw SessionException(SessionProblem.INVALID)
        override fun encrypt(pairing: String, plain: ByteArray, now: Long): SessionBytes = throw SessionException(SessionProblem.NO_SESSION)
        override fun decrypt(pairing: String, message: SessionBytes): ByteArray = throw SessionException(SessionProblem.NO_SESSION)
        override fun openHello(sealed: ByteArray): ByteArray? = null
        override fun signHello(message: ByteArray): ByteArray = ByteArray(64)
        override fun verifies(identity: ByteArray, message: ByteArray, signature: ByteArray): Boolean = false
        override fun safetyNumber(theirs: ByteArray): String? = null
        override fun forget(pairing: String) {}
        override fun prune(now: Long) {}
    }

    /** robot_link_v1 in software: AES-256-GCM with the additional data. */
    private class SoftWrap : SecretWrap {
        private var master: ByteArray? = null

        override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray {
            val m = master ?: if (create) ByteArray(32).also(SecureRandom()::nextBytes).also { master = it } else throw KeyException(KeyProblem.GONE)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(key)
        }

        override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
            val m = master ?: throw KeyException(KeyProblem.GONE)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
            c.updateAAD(aad)
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        }
    }
}
