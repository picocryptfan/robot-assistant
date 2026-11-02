// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.link.RecordKey
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.seal.SecretWrap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * robot-to-robot-plan.md §2.4, §2.5 and §9.1 (job L2): two Robots in one JVM (Matt's and Sam's), each with its own sealed
 * stores on storage, on libsignal 0.105.2's desktop library. A session started from a pairing hello's bundle, messages
 * both ways with each Robot read back from storage after every message (a :link restart), the Kyber prekey one-time,
 * a tampered or replayed message changing nothing, identities pinned, a storage failure leaving memory and storage alike,
 * prekeys rotated and pruned, unpairing, and nothing in the clear on storage.
 */
class LinkStoresTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_761_080_000_000L
    private val day = 86_400_000L
    /** Sam's pairing id on Matt's phone, and Matt's on Sam's (each side names its pairings its own way). */
    private val samOnMatt = "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"
    private val mattOnSam = "3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a"
    private val random = SecureRandom()

    /** robot_link_v1 in software (core's own fake isn't visible from here): AES-256-GCM with the additional data. */
    private class SoftLinkKey : SecretWrap {
        var master: ByteArray? = null
        var refuse: KeyProblem? = null

        override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray {
            refuse?.let { throw KeyException(it) }
            val m = master ?: if (create) ByteArray(32).also(SecureRandom()::nextBytes).also { master = it } else throw KeyException(KeyProblem.GONE)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(key)
        }

        override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
            refuse?.let { throw KeyException(it) }
            val m = master ?: throw KeyException(KeyProblem.GONE)
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
                c.updateAAD(aad)
                c.doFinal(wrapped, 12, wrapped.size - 12)
            } catch (x: AEADBadTagException) {
                throw KeyException(KeyProblem.BAD_DATA, x)
            }
        }
    }

    /** One Robot: its folder, its key, and its stores as :link would hold them. */
    private inner class Robot(name: String) {
        val dir = File(tmp.root, name)
        val key = SoftLinkKey()
        val records get() = LinkRecords(dir, key, random)
        var stores: LinkStores = LinkStores.create(records, t0, random)

        /** :link starts again: everything read back from storage. */
        fun restart() {
            stores = LinkStores.open(records, random)
        }

        /** Each record file's name and bytes. */
        fun snapshot(): Map<String, String> = dir.listFiles()!!.sortedBy { it.name }.associate { it.name to it.readBytes().contentHashCode().toString() }
    }

    /**
     * FB5: a Start over cut at any point after it began: what's left never opens as a working Robot with part of its
     * state, it reads DAMAGED, which LinkVault.problem turns into Settings' warning and its Start over.
     */
    @Test fun aStartOverCutPartWayDoesNotOpen() {
        fun Robot.paired() = apply { repeat(8) { records.write(RecordKey(LinkPurpose.REPLAY, RecordKey.number(it + 1)), byteArrayOf(1)) } }
        val names = Robot("names").paired().dir.list()!!.size
        assertTrue("$names", names > 8)
        for (cut in 1 until names) {
            val r = Robot("cut$cut").paired()
            var left = cut
            try {
                r.records.deleteAll { f -> if (left-- == 0) throw IllegalStateException(); LinkRecords.destroy(f) }
            } catch (_: IllegalStateException) {}
            assertTrue("something is left at $cut", r.dir.list()!!.isNotEmpty())
            val problem = try { r.restart(); null } catch (x: LinkStateException) { x.problem }
            assertEquals("cut after $cut of $names", LinkStateProblem.DAMAGED, problem)
        }
    }

    private fun problem(block: () -> Unit): SessionProblem? = try {
        block()
        null
    } catch (x: SessionException) {
        x.problem
    }

    private fun pad(text: String) = text.toByteArray().copyOf(512)

    /** Matt scans Sam's code and sends a hello with his bundle; Sam starts the session and sends the first message. */
    private fun pair(matt: Robot, sam: Robot): Pair<Int, SessionBytes> {
        val kyber = matt.stores.newKyberPreKey(t0)
        val hello = matt.stores.bundle(kyber, t0)
        // Matt pins Sam's identity from the code he scanned; Sam pins Matt's from the hello (L5 checks both first).
        matt.stores.pin(samOnMatt, sam.stores.identityKey)
        sam.stores.pin(mattOnSam, hello.identity)
        sam.stores.startSession(mattOnSam, hello, t0)
        return kyber to sam.stores.encrypt(mattOnSam, pad("hello from Sam"), t0)
    }

    @Test fun twoRobotsTalkAndEverySessionSurvivesARestart() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (kyber, first) = pair(matt, sam)
        assertTrue(first.first)
        assertArrayEquals(pad("hello from Sam"), matt.stores.decrypt(samOnMatt, first))
        // The Kyber prekey was used once: gone, from memory and from storage, once the session it started was stored.
        assertFalse(File(matt.dir, RecordKey(LinkPurpose.KYBER_PREKEY, RecordKey.number(kyber)).fileName).exists())
        assertTrue(File(matt.dir, RecordKey(LinkPurpose.SESSION, samOnMatt).fileName).isFile)
        assertEquals(SessionProblem.NO_PREKEY, problem { matt.stores.bundle(kyber, t0) })
        // Forty messages, alternating, each Robot read back from storage before it reads or sends.
        var from = matt
        var to = sam
        for (i in 0 until 40) {
            from.restart()
            val m = from.stores.encrypt(if (from === matt) samOnMatt else mattOnSam, pad("message $i"), t0 + i)
            // Sam's messages carry the prekey until Matt's first answer arrives; after that none does.
            if (i > 1) assertFalse("message $i", m.first)
            to.restart()
            assertArrayEquals(pad("message $i"), to.stores.decrypt(if (to === matt) samOnMatt else mattOnSam, m))
            from = to.also { to = from }
        }
        assertEquals(listOf(samOnMatt), matt.stores.pairings())
        assertEquals(listOf(mattOnSam), sam.stores.pairings())
    }

    /** §2.5 and R0 item 7: a Kyber prekey starts one session, never two. */
    @Test fun theKyberPrekeyIsOneTime() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val ann = Robot("ann")
        val kyber = matt.stores.newKyberPreKey(t0)
        val hello = matt.stores.bundle(kyber, t0)
        val annOnMatt = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        val mattOnAnn = "a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2"
        for ((r, id) in listOf(sam to mattOnSam, ann to mattOnAnn)) {
            r.stores.pin(id, hello.identity)
            r.stores.startSession(id, hello, t0)
        }
        matt.stores.pin(samOnMatt, sam.stores.identityKey)
        matt.stores.pin(annOnMatt, ann.stores.identityKey)
        assertArrayEquals(pad("from Sam"), matt.stores.decrypt(samOnMatt, sam.stores.encrypt(mattOnSam, pad("from Sam"), t0)))
        // Ann used the same hello: the prekey is gone.
        assertEquals(SessionProblem.NO_PREKEY, problem { matt.stores.decrypt(annOnMatt, ann.stores.encrypt(mattOnAnn, pad("from Ann"), t0)) })
        assertFalse(matt.stores.hasSession(annOnMatt))
    }

    /** One libsignal call is one transaction: a message that doesn't check leaves memory and storage exactly as they were. */
    @Test fun aTamperedFirstMessageChangesNothing() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (kyber, first) = pair(matt, sam)
        val before = matt.snapshot()
        // The version byte, the Kyber ciphertext's middle, the inner message and its MAC (the registration id outside the
        // MAC is libsignal's to ignore, so it isn't flipped here).
        for (at in listOf(0, first.bytes.size / 2, first.bytes.size - 20, first.bytes.size - 1)) {
            // (The version byte's high half is the message's version; its low half isn't checked by libsignal.)
            val bad = SessionBytes(true, first.bytes.also { it[at] = (it[at].toInt() xor if (at == 0) 0x30 else 1).toByte() })
            assertNotEquals("byte $at", null, problem { matt.stores.decrypt(samOnMatt, bad) })
            assertEquals("byte $at", before, matt.snapshot())
            assertFalse(matt.stores.hasSession(samOnMatt))
        }
        // Read as a later message, cut, or empty: refused, nothing changed.
        assertEquals(SessionProblem.INVALID, problem { matt.stores.decrypt(samOnMatt, SessionBytes(false, first.bytes)) })
        assertEquals(SessionProblem.INVALID, problem { matt.stores.decrypt(samOnMatt, SessionBytes(true, first.bytes.copyOf(100))) })
        assertEquals(SessionProblem.INVALID, problem { matt.stores.decrypt(samOnMatt, SessionBytes(true, ByteArray(0))) })
        assertEquals(before, matt.snapshot())
        // The Kyber prekey is still there, in memory and on storage, and the real message still opens.
        assertEquals(kyber, matt.stores.bundle(kyber, t0).kyberPreKeyId)
        assertArrayEquals(pad("hello from Sam"), matt.stores.decrypt(samOnMatt, first))
    }

    @Test fun aReplayIsRefusedAndChangesNothing() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (_, first) = pair(matt, sam)
        matt.stores.decrypt(samOnMatt, first)
        val reply = matt.stores.encrypt(samOnMatt, pad("reply"), t0)
        sam.stores.decrypt(mattOnSam, reply)
        val later = sam.stores.encrypt(mattOnSam, pad("later"), t0)
        matt.stores.decrypt(samOnMatt, later)
        val before = matt.snapshot()
        assertEquals(SessionProblem.DUPLICATE, problem { matt.stores.decrypt(samOnMatt, later) })
        assertNotEquals(null, problem { matt.stores.decrypt(samOnMatt, first) })
        assertEquals(before, matt.snapshot())
        matt.restart()
        assertEquals(SessionProblem.DUPLICATE, problem { matt.stores.decrypt(samOnMatt, later) })
    }

    /** Identities are pinned by the pairing, never learned: a session with any other key is refused. */
    @Test fun onlyThePinnedIdentityIsTrusted() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val eve = Robot("eve")
        val kyber = matt.stores.newKyberPreKey(t0)
        val hello = matt.stores.bundle(kyber, t0)
        // Sam starts a session to a bundle whose identity he hasn't pinned: refused, nothing stored.
        assertEquals(SessionProblem.UNTRUSTED, problem { sam.stores.startSession(mattOnSam, hello, t0) })
        assertFalse(sam.stores.hasSession(mattOnSam))
        // ... or pinned as someone else's key.
        sam.stores.pin(mattOnSam, eve.stores.identityKey)
        assertEquals(SessionProblem.UNTRUSTED, problem { sam.stores.startSession(mattOnSam, hello, t0) })
        sam.stores.pin(mattOnSam, hello.identity)
        sam.stores.startSession(mattOnSam, hello, t0)
        val first = sam.stores.encrypt(mattOnSam, pad("hi"), t0)
        // Matt hasn't pinned Sam: the first message is refused and the Kyber prekey stays.
        assertEquals(SessionProblem.UNTRUSTED, problem { matt.stores.decrypt(samOnMatt, first) })
        matt.stores.pin(samOnMatt, eve.stores.identityKey)
        assertEquals(SessionProblem.UNTRUSTED, problem { matt.stores.decrypt(samOnMatt, first) })
        assertEquals(kyber, matt.stores.bundle(kyber, t0).kyberPreKeyId)
        matt.stores.pin(samOnMatt, sam.stores.identityKey)
        assertArrayEquals(pad("hi"), matt.stores.decrypt(samOnMatt, first))
        assertArrayEquals(sam.stores.identityKey, matt.stores.identityOf(samOnMatt))
        // A bundle whose prekey signatures aren't its identity's: libsignal refuses it.
        val forged = hello.let {
            app.heyrobot.core.link.HelloBundle(it.registrationId, eve.stores.identityKey, it.signedPreKeyId, it.signedPreKey,
                it.signedPreKeySignature, it.kyberPreKeyId, it.kyberPreKey, it.kyberPreKeySignature)
        }
        val annOnSam = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        sam.stores.pin(annOnSam, eve.stores.identityKey)
        assertEquals(SessionProblem.INVALID, problem { sam.stores.startSession(annOnSam, forged, t0) })
    }

    /** A write that fails (robot_link_v1 not usable now) fails the call: memory and storage stay as they were. */
    @Test fun aStorageFailureChangesNothing() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (_, first) = pair(matt, sam)
        val before = matt.snapshot()
        matt.key.refuse = KeyProblem.LOCKED
        val x = try { matt.stores.decrypt(samOnMatt, first); null } catch (e: SessionException) { e }
        assertEquals(SessionProblem.STATE, x!!.problem)
        assertEquals(LinkStateProblem.LOCKED, x.state)
        assertEquals(before, matt.snapshot())
        assertFalse(matt.stores.hasSession(samOnMatt))
        assertTrue(matt.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        matt.key.refuse = null
        assertArrayEquals(pad("hello from Sam"), matt.stores.decrypt(samOnMatt, first))
        // Sending: the same.
        matt.key.refuse = KeyProblem.LOCKED
        val sent = matt.snapshot()
        assertEquals(SessionProblem.STATE, problem { matt.stores.encrypt(samOnMatt, pad("x"), t0) })
        assertEquals(sent, matt.snapshot())
    }

    /** §2.5: one signed prekey, made again every 30 days, the replaced ones kept 30 days more; unused Kyber prekeys go after 7. */
    @Test fun prekeysRotateAndArePruned() {
        val matt = Robot("matt")
        val s0 = matt.stores.signedPreKey(t0)
        assertEquals(s0.id, matt.stores.signedPreKey(t0 + 29 * day).id)
        val s1 = matt.stores.signedPreKey(t0 + 30 * day)
        assertNotEquals(s0.id, s1.id)
        matt.restart()
        assertEquals(s1.id, matt.stores.signedPreKey(t0 + 31 * day).id)
        val k = matt.stores.newKyberPreKey(t0)
        val k2 = matt.stores.newKyberPreKey(t0 + 6 * day)
        matt.stores.prune(t0 + 8 * day)
        assertEquals(SessionProblem.NO_PREKEY, problem { matt.stores.bundle(k, t0 + 8 * day) })
        assertEquals(k2, matt.stores.bundle(k2, t0 + 8 * day).kyberPreKeyId)
        fun signed() = matt.dir.list()!!.count { it.startsWith("${LinkPurpose.SIGNED_PREKEY.code}-") }
        assertEquals(2, signed())
        matt.stores.prune(t0 + 59 * day)
        assertEquals(2, signed())
        matt.stores.prune(t0 + 61 * day)
        assertEquals(1, signed())
        assertEquals(s1.id, matt.stores.signedPreKey(t0 + 59 * day).id)
    }

    @Test fun forgettingAPairingRemovesItsSessionAndItsKey() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (_, first) = pair(matt, sam)
        matt.stores.decrypt(samOnMatt, first)
        matt.stores.forget(samOnMatt)
        assertFalse(matt.stores.hasSession(samOnMatt))
        assertNull(matt.stores.identityOf(samOnMatt))
        assertTrue(matt.dir.list()!!.none { it.contains(samOnMatt) })
        matt.restart()
        assertEquals(emptyList<String>(), matt.stores.pairings())
        assertNotEquals(null, problem { matt.stores.decrypt(samOnMatt, sam.stores.encrypt(mattOnSam, pad("still there?"), t0)) })
    }

    /** Never over another identity; a state without one, or under a key that's gone, refused as the vault expects. */
    @Test fun createAndOpenRefuseWhatTheyMustNot() {
        val matt = Robot("matt")
        val x = try { LinkStores.create(matt.records, t0, random); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.DAMAGED, x)
        val gone = try { LinkStores.open(LinkRecords(matt.dir, SoftLinkKey(), random)); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.GONE, gone)
        File(matt.dir, RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO).fileName).delete()
        val noIdentity = try { LinkStores.open(matt.records); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.DAMAGED, noIdentity)
        // Two Robots made the same way are two identities.
        assertFalse(Robot("a").stores.identityKey.contentEquals(Robot("b").stores.identityKey))
    }

    /** On storage, nothing of a message, an identity key or a private key in the clear. */
    @Test fun nothingInTheClearOnStorage() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (_, first) = pair(matt, sam)
        matt.stores.decrypt(samOnMatt, first)
        val secret = pad("zq9marker2 dinner at seven")
        sam.stores.decrypt(mattOnSam, matt.stores.encrypt(samOnMatt, secret, t0))
        for (r in listOf(matt, sam)) for (f in r.dir.listFiles()!!) {
            val b = f.readBytes()
            for (needle in listOf("zq9marker2".toByteArray(), matt.stores.identityKey, sam.stores.identityKey)) {
                assertFalse("${r.dir.name}/${f.name}", indexOf(b, needle) >= 0)
            }
            assertArrayEquals("ROBOTLNK".toByteArray(), b.copyOf(8))
        }
    }

    /** libsignal's skipped keys (2,000 a chain, R0 §2.3) fit a session record's cap; and Robot keeps no sender keys. */
    @Test fun aSessionWithSkippedKeysFitsItsCap() {
        val matt = Robot("matt")
        val sam = Robot("sam")
        val (_, first) = pair(matt, sam)
        matt.stores.decrypt(samOnMatt, first)
        sam.stores.decrypt(mattOnSam, matt.stores.encrypt(samOnMatt, pad("ack"), t0))
        var last: SessionBytes? = null
        repeat(2_001) { last = sam.stores.encrypt(mattOnSam, pad("skipped $it"), t0) }
        assertArrayEquals(pad("skipped 2000"), matt.stores.decrypt(samOnMatt, last!!))
        val size = File(matt.dir, RecordKey(LinkPurpose.SESSION, samOnMatt).fileName).length()
        println("L2 measured: a session record holding 2,000 skipped keys is $size bytes sealed") // l10n: not text (a test's note)
        assertTrue("$size", size < LinkPurpose.SESSION.maxBytes)
        assertFalse(SignalProtocolStore::class.java.isAssignableFrom(LinkProtocolStore::class.java))
        assertFalse(SenderKeyStore::class.java.isAssignableFrom(LinkProtocolStore::class.java))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
