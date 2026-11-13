// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

// DEBUG BUILDS ONLY (src/debug; ReportKeysTest fails if any shipped source set holds it). RP5's throwaway report key, for
// phone row 8 while core's ReportKey.KEY is empty: made with the PC tool's keygen for this test alone, fingerprint
// 554c 3c71 13b0 83ca fdec. Its private half and passphrase stay on the PC, never in git. ReportKeys reads it by name.
object ReportTestKey {
    const val KEY = "BfVCU/Qc7RjyZnhngSKGLtF94ZktzCMPRgHxFP6bRRkt" // l10n: not text (a public key)
}
