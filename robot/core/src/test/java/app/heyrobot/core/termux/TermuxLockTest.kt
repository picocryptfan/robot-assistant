// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0, Termux's update lock and the public test key: the source by signer digest, a new version (or a reinstall) pausing
 * every run until Trust this version, another signer refused until set-up is redone, and the GitHub build blocked by
 * default with an override that holds for one version only.
 */
class TermuxLockTest {
    private val may2025 = 1_747_950_000_000L
    private fun pkg(signer: String = TermuxIdentity.F_DROID, version: String = "0.118.3", code: Long = 1002, at: Long = may2025) =
        TermuxPackage(true, version, listOf(signer), hasRunCommandService = true, permissionOwner = "com.termux", versionCode = code, lastUpdateTime = at)

    /** What the app does: the pinned signer and the version recorded with it. */
    private fun check(p: TermuxPackage, s: TermuxSettings) = TermuxIdentity.check(p, s.trustedSigner, pinnedStillSigns = false, trusted = s.trusted())

    private val tested = TermuxSetupStatus(testAt = 1, testOk = true, testMs = 300, timeout = true, signer = TermuxIdentity.F_DROID)

    @Test fun theSourceComesFromTheSignersDigest() {
        assertEquals(TermuxSource.F_DROID, TermuxIdentity.check(pkg()).source)
        assertEquals(TermuxSource.GITHUB, TermuxIdentity.check(pkg(TermuxIdentity.GITHUB.lowercase().chunked(2).joinToString(":"))).source)
        assertEquals(TermuxSource.TERMUX_DEVS, TermuxIdentity.check(pkg(TermuxIdentity.TERMUX_DEVS)).source)
        assertEquals(TermuxSource.UNKNOWN, TermuxIdentity.check(pkg("CD".repeat(32))).source)
        // The package's version code and install time are carried for Trust to record.
        val c = TermuxIdentity.check(pkg())
        assertEquals(1002L, c.versionCode)
        assertEquals(may2025, c.updatedAt)
    }

    @Test fun aNewVersionPausesEveryRunUntilYouTrustIt() {
        val trusted = TermuxSettings(enabled = true).trust(TermuxIdentity.check(pkg()))
        assertEquals(TermuxIdentity.F_DROID, trusted.trustedSigner)
        assertEquals(1002L, trusted.trustedVersionCode)
        assertEquals(may2025, trusted.trustedUpdatedAt)
        assertEquals("0.118.3", trusted.trustedVersion)
        val now = check(pkg(), trusted)
        assertEquals(TermuxStatus.READY, now.status)
        assertNull(now.lock)
        assertTrue(TermuxSetup.ready(now, true, tested, trusted.trustedSigner))

        // Updated (same signer, another version code), or installed again over itself (same version, a new install time).
        for (changed in listOf(pkg(version = "0.119.0", code = 1030, at = may2025 + 86_400_000), pkg(at = may2025 + 1))) {
            val c = check(changed, trusted)
            assertEquals(TermuxStatus.UPDATED, c.status)
            assertEquals(TermuxLock.UPDATED, c.lock)
            assertFalse(c.usable)
            assertFalse(TermuxSetup.ready(c, true, tested, trusted.trustedSigner))
            // The tools stay described, so a call says what to do instead of Gemma not knowing Termux.
            assertTrue(TermuxSetup.readyButLocked(c, true, tested, trusted.trustedSigner))
            // Trust this version: runs again.
            val again = trusted.trust(c)
            assertEquals(TermuxStatus.READY, check(changed, again).status)
            assertTrue(TermuxSetup.ready(check(changed, again), true, tested, again.trustedSigner))
        }
        // The checklist's trust line warns; nothing new in core's words.
        val line = TermuxSetup.lines(check(pkg(at = 5), trusted), true, tested, trusted.trustedSigner).single { it.step == SetupStep.TRUST }
        assertEquals(SetupMark.WARN, line.mark)
    }

    @Test fun aZeroNineFileWithoutAVersionAsksOnce() {
        // 0.9 pinned the signer only: the first check after the update reads as changed, and Trust this version records it.
        val old = TermuxSettings.fromJson(org.json.JSONObject("""{"enabled":true,"trustedSigner":"${TermuxIdentity.F_DROID}"}"""))
        assertEquals(TermuxStatus.UPDATED, check(pkg(), old).status)
        assertEquals(TermuxStatus.READY, check(pkg(), old.trust(check(pkg(), old))).status)
        // Before anything is trusted there is no version to compare.
        assertEquals(TermuxStatus.READY, check(pkg(), TermuxSettings()).status)
    }

    @Test fun anotherSignerIsRefusedUntilSetUpIsRedone() {
        val trusted = TermuxSettings(enabled = true).trust(TermuxIdentity.check(pkg()))
        val replaced = pkg(TermuxIdentity.GITHUB, at = may2025 + 5)
        val c = check(replaced, trusted)
        assertEquals(TermuxStatus.CHANGED, c.status)
        assertEquals(TermuxLock.SIGNER, c.lock)
        assertFalse(c.usable)
        assertFalse(TermuxSetup.readyButLocked(c, true, tested, trusted.trustedSigner))
        assertEquals(TermuxFailure.NOT_TRUSTED.en, c.line())
        assertTrue(c.line().endsWith("then set it up again in Settings › Termux."))
        // Trust this version doesn't apply: set up again forgets the trust and turns Termux off.
        val reset = trusted.untrust()
        assertFalse(reset.enabled)
        assertNull(reset.trustedSigner)
        assertNull(reset.trusted())
        // The Test made with the Termux that was replaced no longer counts: the checklist starts over.
        val fresh = check(replaced, reset)
        assertEquals(TermuxStatus.TEST_KEY, fresh.status)
        val testLine = TermuxSetup.lines(fresh, true, tested, null).single { it.step == SetupStep.TEST }
        assertEquals(SetupMark.TODO, testLine.mark)
    }

    @Test fun theTestKeyBuildRunsNothingByDefault() {
        val gh = pkg(TermuxIdentity.GITHUB)
        val ghTested = tested.copy(signer = TermuxIdentity.GITHUB)
        val trusted = TermuxSettings(enabled = true).trust(TermuxIdentity.check(gh))
        val c = check(gh, trusted)
        assertEquals(TermuxStatus.TEST_KEY, c.status)
        assertEquals(TermuxLock.TEST_KEY, c.lock)
        assertFalse(c.usable)
        assertFalse(TermuxSetup.ready(c, true, ghTested, trusted.trustedSigner))
        assertTrue(TermuxSetup.readyButLocked(c, true, ghTested, trusted.trustedSigner))

        // "Run anyway, I understand the risk": usable, with the warning, for this version only.
        val risk = trusted.copy(testKeyRisk = true)
        val accepted = check(gh, risk)
        assertTrue(accepted.testKeyRisk)
        assertTrue(accepted.usable)
        assertNull(accepted.lock)
        assertTrue(TermuxSetup.ready(accepted, true, ghTested, risk.trustedSigner))
        // A new version: paused, and trusting it asks for Run anyway again.
        val newer = pkg(TermuxIdentity.GITHUB, "0.119.0-beta.4", 1023, may2025 + 10)
        assertEquals(TermuxStatus.UPDATED, check(newer, risk).status)
        val retrusted = risk.trust(check(newer, risk))
        assertFalse(retrusted.testKeyRisk)
        assertEquals(TermuxLock.TEST_KEY, check(newer, retrusted).lock)
        // The override never reaches another source's check.
        assertFalse(check(pkg(), TermuxSettings(testKeyRisk = true).trust(TermuxIdentity.check(pkg())).copy(testKeyRisk = true)).testKeyRisk)
    }

    @Test fun theRecordAndTheSwitchesKeepInTermuxJson() {
        val s = TermuxSettings(enabled = true, testKeyRisk = true, updateCheck = false).trust(TermuxIdentity.check(pkg())).copy(testKeyRisk = true)
        val back = TermuxSettings.fromJson(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        // Defaults: Run anyway off, the update check on (it runs only once a Termux is trusted).
        val d = TermuxSettings.fromJson(null)
        assertFalse(d.testKeyRisk)
        assertTrue(d.updateCheck)
        assertFalse(d.checksUpdates)
        assertTrue(d.copy(trustedSigner = TermuxIdentity.F_DROID).checksUpdates)
        assertFalse(d.copy(trustedSigner = TermuxIdentity.F_DROID, updateCheck = false).checksUpdates)
    }

    @Test fun theVoiceLogSaysCodesOnly() {
        fun d(e: app.heyrobot.core.VoiceEvent, a: Int, b: Int = 0, c: Int = 0) = app.heyrobot.core.VoiceLogText.describe(app.heyrobot.core.VoiceLogEntry(0, e, a, b, c))
        assertEquals(listOf(174, 175, 176, 177), listOf(app.heyrobot.core.VoiceEvent.TERMUX_UPDATE_CHECK, app.heyrobot.core.VoiceEvent.TERMUX_LOCKED,
            app.heyrobot.core.VoiceEvent.TERMUX_TRUST, app.heyrobot.core.VoiceEvent.TERMUX_ADB_FIX).map { it.id })
        assertEquals("Termux update check: update available · from F-Droid",
            d(app.heyrobot.core.VoiceEvent.TERMUX_UPDATE_CHECK, TermuxUpdateVerdict.AVAILABLE.id, TermuxSource.F_DROID.ordinal + 1))
        assertEquals("Termux call refused: public test key", d(app.heyrobot.core.VoiceEvent.TERMUX_LOCKED, TermuxLock.TEST_KEY.id))
        assertEquals("Termux: trusted this version · from GitHub", d(app.heyrobot.core.VoiceEvent.TERMUX_TRUST, TermuxTrustOp.VERSION.id, TermuxSource.GITHUB.ordinal + 1))
        assertEquals("ADB fix 2 through Shizuku: not sent (phone locked)", d(app.heyrobot.core.VoiceEvent.TERMUX_ADB_FIX, 2,
            app.heyrobot.core.ShizukuOutcome.REFUSED.id, app.heyrobot.core.ShizukuRefusal.LOCKED.id))
        assertEquals("ADB fix 1 through Shizuku: done", d(app.heyrobot.core.VoiceEvent.TERMUX_ADB_FIX, 1, app.heyrobot.core.ShizukuOutcome.APPLIED.id))
        assertEquals("ADB fix 1 through Shizuku: Termux couldn't run it (no answer)", d(app.heyrobot.core.VoiceEvent.TERMUX_ADB_FIX, 1,
            app.heyrobot.core.ShizukuOutcome.TERMUX_FAILED.id, TermuxFailure.NO_ANSWER.id))
        for (codes in listOf(TermuxLock.entries.map { it.id }, TermuxTrustOp.entries.map { it.id }, TermuxUpdateVerdict.entries.map { it.id }))
            assertEquals(codes.size, codes.toSet().size)
    }
}
