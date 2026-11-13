// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.feedback.ReportFile
import app.heyrobot.core.feedback.ReportKey
import org.signal.libsignal.protocol.IdentityKey

// :LINK PROCESS. 1.4, Report a problem (report-a-problem-plan.md "What's new" 5; the envelope: report-seal-format.md):
// ReportFile's bytes sealed to the developer's public key with libsignal's HPKE (base mode: X25519, HKDF-SHA256,
// AES-256-GCM), as HelloSeal seals a hello. The file is the 5 bytes "RRPT" 0x01, then the sealed bytes, with info
// "robot-report-v1" and those 5 bytes as aad; nothing else, so the reference travels only inside. Only the PC tool
// (tools/reports, the private key) opens it. Robot writes no cryptography of its own here.

object ReportSeal {
    /** HPKE's info: what the sealed bytes are, so they can't be taken for anything else sealed to the same key. */
    internal val INFO = "robot-report-v1".toByteArray(Charsets.US_ASCII) // l10n: not text (HPKE's info string)
    /** The file's magic and version, also the seal's aad. */
    internal val HEAD = byteArrayOf('R'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte(), 1)

    /** A sealer to [key], the 33 serialized bytes of an X25519 public key (ReportKey.decode); anything else is refused. */
    fun sealer(key: ByteArray): ReportFile.Sealer {
        require(ReportKey.valid(key)) { "not a report key" } // l10n: not text (an exception)
        val pk = IdentityKey(key).publicKey
        return ReportFile.Sealer { plain -> HEAD + pk.seal(plain, INFO, HEAD) }
    }
}
