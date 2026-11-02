// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.io.File

/**
 * The libsignal the tests run (robot-to-robot-r0.md §2.6, job L2): 0.105.2's desktop library, loaded from
 * java.library.path (link/build.gradle's libsignalTestNatives), never its testing library, and nothing copied to /tmp.
 * One session with libsignal's own in-memory store: PQXDH, a first message, session version 4.
 */
class LibsignalLoadsTest {
    private fun loaded(): List<String> = File("/proc/self/maps").readLines().map { it.substringAfterLast(' ') }.filter { "signal" in it && Regex("""\.(so|dylib|dll)(\.\d+)*$""").containsMatchIn(it) }.distinct()

    @Test fun theProductionLibraryLoadsAndASessionRuns() {
        val alice = InMemorySignalProtocolStore(IdentityKeyPair.generate(), 1)
        val bobId = IdentityKeyPair.generate()
        val bob = InMemorySignalProtocolStore(bobId, 2)
        val spk = ECKeyPair.generate()
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        bob.storeSignedPreKey(7, SignedPreKeyRecord(7, 0, spk, bobId.privateKey.calculateSignature(spk.publicKey.serialize())))
        bob.storeKyberPreKey(9, KyberPreKeyRecord(9, 0, kyber, bobId.privateKey.calculateSignature(kyber.publicKey.serialize())))
        val a = SignalProtocolAddress("a".repeat(32), 1)
        val b = SignalProtocolAddress("b".repeat(32), 1)
        val bundle = PreKeyBundle(2, 1, PreKeyBundle.NULL_PRE_KEY_ID, null, 7, spk.publicKey,
            bobId.privateKey.calculateSignature(spk.publicKey.serialize()), bobId.publicKey, 9, kyber.publicKey,
            bobId.privateKey.calculateSignature(kyber.publicKey.serialize()))
        SessionBuilder(alice, b, a).process(bundle)
        val first = SessionCipher(alice, a, b).encrypt(ByteArray(512) { 1 })
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        assertArrayEquals(ByteArray(512) { 1 }, SessionCipher(bob, b, a).decrypt(PreKeySignalMessage(first.serialize())))
        assertEquals(4, SessionCipher(bob, b, a).sessionVersion)

        val libs = loaded()
        // AGP adds its own folders to java.library.path; libsignalTestNatives' is the one that holds the library.
        val dir = File(System.getProperty("java.library.path").split(File.pathSeparator).single { it.endsWith("libsignal-test/lib") }).canonicalPath
        assertEquals(listOf("$dir/libsignal_jni.so"), libs.map { File(it).canonicalPath })
        assertTrue(libs.none { "testing" in it || it.startsWith("/tmp") })
    }
}
