// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RP5: the report key and its fingerprint as report-seal-format.md says. The key below is the debug build's throwaway,
// made by the PC tool's keygen, which printed this fingerprint for it.
class ReportKeyTest {
    private val pcKey = "BfVCU/Qc7RjyZnhngSKGLtF94ZktzCMPRgHxFP6bRRkt"

    @Test fun theFingerprintIsThePcToolsOne() = assertEquals("554c 3c71 13b0 83ca fdec", ReportKey.fingerprint(ReportKey.decode(pcKey)!!))

    @Test fun onlyA33ByteX25519KeyDecodes() {
        val b = Base64.getEncoder()
        for (bad in listOf(null, "", "  ", "not base64!", b.encodeToString(ByteArray(32) { 5 }), b.encodeToString(ByteArray(33) { 6 }))) assertNull(bad, ReportKey.decode(bad))
        assertEquals(33, ReportKey.decode(" $pcKey\n")!!.size)
    }

    @Test fun theReleaseKeyIsEmptyOrAKey() = assertTrue(ReportKey.KEY.isEmpty() || ReportKey.decode(ReportKey.KEY) != null)
}
