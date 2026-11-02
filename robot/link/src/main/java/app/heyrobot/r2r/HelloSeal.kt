// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.PairHello
import org.signal.libsignal.protocol.IdentityKey

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.3, §3.8; job L3): the pairing hello travels sealed to the
// maker's identity key, the one its pairing code shows, with libsignal's HPKE (RFC 9180 base mode: X25519, HKDF-SHA256,
// AES-256-GCM; a type byte, the 32-byte encapsulated key and a 16-byte tag, 49 bytes in all). Only the maker opens it
// (LinkStores.openHello): a shared Wi-Fi (the direct carrier, in person), a push server or Tor sees ciphertext, never the
// name, the push address or caps the hello carries. Robot writes no cryptography of its own here: libsignal seals and opens.

object HelloSeal {
    /** HPKE's info: what the sealed bytes are for, so they can't be taken for anything else sealed to the same key. */
    internal val INFO = "robot-link-hello-v1".toByteArray(Charsets.US_ASCII) // l10n: not text (HPKE's info string)
    /** The envelope's own magic, version and kind (3): a sealed hello is a hello only inside such an envelope. */
    internal val AAD = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), 1, 3)

    /** [hello] (PairHello's bytes) sealed to [makerIdentity], the 33-byte identity key from the maker's code. */
    fun seal(makerIdentity: ByteArray, hello: ByteArray): ByteArray {
        require(hello.size <= PairHello.MAX_BYTES)
        return IdentityKey(makerIdentity).publicKey.seal(hello, INFO, AAD).also { check(it.size == hello.size + PairHello.SEAL_OVERHEAD) }
    }
}
