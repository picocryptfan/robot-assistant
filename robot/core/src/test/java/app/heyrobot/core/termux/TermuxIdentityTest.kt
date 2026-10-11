package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Termux is installed (B2, B7.6), and the strings copied from TermuxConstants.java. */
class TermuxIdentityTest {
    private fun pkg(signer: String? = TermuxIdentity.F_DROID, version: String = "0.118.3", service: Boolean = true, owner: String? = "com.termux") =
        TermuxPackage(true, version, listOfNotNull(signer), service, owner)

    @Test fun knownSignersAsTermuxListsThem() {
        // TermuxConstants.APK_RELEASE_*_SIGNING_CERTIFICATE_SHA256_DIGEST (termux-app 8629e632, lines 482–500).
        assertEquals("228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42", TermuxIdentity.F_DROID)
        assertEquals("B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1", TermuxIdentity.GITHUB)
        assertEquals("738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9", TermuxIdentity.GOOGLE_PLAY)
        assertEquals("F7A038EB551F1BE8FDF388686B784ABAB4552A5D82DF423E3D8F1B5CBE1C69AE", TermuxIdentity.TERMUX_DEVS)
        assertEquals(TermuxSource.F_DROID, TermuxIdentity.source(TermuxIdentity.F_DROID.lowercase()))
        assertEquals(TermuxSource.GITHUB, TermuxIdentity.source(TermuxIdentity.GITHUB.chunked(2).joinToString(":")))
        assertEquals(TermuxSource.UNKNOWN, TermuxIdentity.source("00".repeat(32)))
        assertEquals(TermuxSource.UNKNOWN, TermuxIdentity.source("not hex"))
        assertNull(TermuxIdentity.normalize("ABC"))
    }

    @Test fun whatSettingsSays() {
        val fdroid = TermuxIdentity.check(pkg())
        assertEquals(TermuxStatus.READY, fdroid.status)
        assertTrue(fdroid.usable)
        assertEquals("Termux 0.118.3 from F-Droid", fdroid.line())
        assertEquals("Termux 0.118.3 depuis F-Droid", fdroid.line(fr = true))
        assertEquals(TermuxIdentity.F_DROID, fdroid.signer)

        val github = TermuxIdentity.check(pkg(TermuxIdentity.GITHUB, "0.119.0-beta.3"))
        assertEquals(TermuxStatus.TEST_KEY, github.status)
        assertTrue(github.usable)
        assertEquals("Termux 0.119.0-beta.3 from GitHub (public test key: anyone can make an update that installs over it)", github.line())

        assertEquals(TermuxStatus.READY, TermuxIdentity.check(pkg(TermuxIdentity.TERMUX_DEVS)).status)
    }

    @Test fun thePlayBuildCantTakeCommands() {
        // The Play build has no RunCommandService and doesn't define RUN_COMMAND: not supported, whatever its signer.
        val play = TermuxIdentity.check(pkg(TermuxIdentity.GOOGLE_PLAY, "googleplay.2026.10.09", service = false, owner = null))
        assertEquals(TermuxStatus.NOT_SUPPORTED, play.status)
        assertFalse(play.usable)
        assertEquals(TermuxSource.GOOGLE_PLAY, play.source)
        assertEquals("Termux googleplay.2026.10.09 from Google Play: this version can't take commands from other apps; install Termux from F-Droid.", play.line())
        assertEquals(TermuxStatus.NOT_SUPPORTED, TermuxIdentity.check(pkg("11".repeat(32), service = false, owner = null)).status)
        assertEquals(TermuxStatus.NOT_SUPPORTED, TermuxIdentity.check(pkg(TermuxIdentity.GOOGLE_PLAY)).status)
    }

    @Test fun unknownChangedOldOrTaken() {
        assertEquals(TermuxStatus.NOT_INSTALLED, TermuxIdentity.check(TermuxPackage(false)).status)
        assertEquals("Termux isn't installed.", TermuxIdentity.check(TermuxPackage(false)).line())
        val other = "AB".repeat(32)
        val unknown = TermuxIdentity.check(pkg(other))
        assertEquals(TermuxStatus.UNKNOWN_SIGNER, unknown.status)
        assertFalse(unknown.usable)
        assertEquals("Termux 0.118.3 signed by an unknown key", unknown.line())
        // Once you trust it, your own build is usable.
        assertEquals(TermuxStatus.READY, TermuxIdentity.check(pkg(other), pinned = other).status)
        // Several signers, or none: unknown.
        assertEquals(TermuxStatus.UNKNOWN_SIGNER, TermuxIdentity.check(TermuxPackage(true, "0.118.3", listOf(TermuxIdentity.F_DROID, other), true, "com.termux")).status)
        assertEquals(TermuxStatus.UNKNOWN_SIGNER, TermuxIdentity.check(pkg(null)).status)
        // Not the one you trusted: stop, unless the system says the pinned key is in its signing lineage.
        val changed = TermuxIdentity.check(pkg(TermuxIdentity.GITHUB), pinned = TermuxIdentity.F_DROID)
        assertEquals(TermuxStatus.CHANGED, changed.status)
        assertEquals("Termux was reinstalled or replaced. Check where it came from, then Trust again in Settings › Termux.", changed.line())
        assertEquals(TermuxFailure.NOT_TRUSTED.en, changed.line())
        assertEquals(TermuxFailure.NOT_TRUSTED.fr, changed.line(fr = true))
        assertEquals(TermuxStatus.READY, TermuxIdentity.check(pkg(other), pinned = TermuxIdentity.F_DROID, pinnedStillSigns = true).status)
        assertEquals(TermuxStatus.CHANGED, TermuxIdentity.check(pkg(), pinned = "garbage").status)
        // Another app defines Termux's permission.
        assertEquals(TermuxStatus.PERMISSION_TAKEN, TermuxIdentity.check(pkg(owner = "com.evil")).status)
        // Too old.
        assertEquals(TermuxStatus.TOO_OLD, TermuxIdentity.check(pkg(version = "0.117.0")).status)
        assertEquals("Termux 0.101 from F-Droid is too old: update it to 0.118.0 or newer.", TermuxIdentity.check(pkg(version = "0.101")).line())
    }

    @Test fun versions() {
        for (v in listOf("0.118.0", "0.118.3", "0.119.0-beta.3", "v0.120", "1.0", "0.118")) assertTrue(v, TermuxIdentity.atLeast(v))
        for (v in listOf("0.117.9", "0.101", "", "googleplay.2026.10.09", "beta", "0.x")) assertFalse(v, TermuxIdentity.atLeast(v))
        assertFalse(TermuxIdentity.atLeast(null))
    }

    @Test fun intentStringsAsTermuxDefinesThem() {
        assertEquals("com.termux", TermuxIntents.PACKAGE)
        assertEquals("com.termux.app.RunCommandService", TermuxIntents.RUN_COMMAND_SERVICE)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxIntents.PERMISSION_RUN_COMMAND)
        assertEquals("com.termux.RUN_COMMAND", TermuxIntents.ACTION_RUN_COMMAND)
        assertEquals(listOf("com.termux.RUN_COMMAND_PATH", "com.termux.RUN_COMMAND_ARGUMENTS", "com.termux.RUN_COMMAND_STDIN", "com.termux.RUN_COMMAND_WORKDIR",
            "com.termux.RUN_COMMAND_BACKGROUND", "com.termux.RUN_COMMAND_RUNNER", "com.termux.RUN_COMMAND_SESSION_ACTION", "com.termux.RUN_COMMAND_COMMAND_LABEL",
            "com.termux.RUN_COMMAND_PENDING_INTENT"),
            listOf(TermuxIntents.EXTRA_COMMAND_PATH, TermuxIntents.EXTRA_ARGUMENTS, TermuxIntents.EXTRA_STDIN, TermuxIntents.EXTRA_WORKDIR, TermuxIntents.EXTRA_BACKGROUND,
                TermuxIntents.EXTRA_RUNNER, TermuxIntents.EXTRA_SESSION_ACTION, TermuxIntents.EXTRA_COMMAND_LABEL, TermuxIntents.EXTRA_PENDING_INTENT))
        assertEquals(listOf("result", "stdout", "stdout_original_length", "stderr", "stderr_original_length", "exitCode", "err", "errmsg"),
            listOf(TermuxIntents.RESULT_BUNDLE, TermuxIntents.RESULT_STDOUT, TermuxIntents.RESULT_STDOUT_ORIGINAL_LENGTH, TermuxIntents.RESULT_STDERR,
                TermuxIntents.RESULT_STDERR_ORIGINAL_LENGTH, TermuxIntents.RESULT_EXIT_CODE, TermuxIntents.RESULT_ERR, TermuxIntents.RESULT_ERRMSG))
        assertEquals("app-shell", TermuxIntents.RUNNER_APP_SHELL)
        assertEquals("terminal-session", TermuxIntents.RUNNER_TERMINAL_SESSION)
        assertEquals(102_400, TermuxIntents.RESULT_LIMIT_BYTES)
        assertEquals("/data/data/com.termux/files/home", TermuxPaths.HOME)
        assertEquals("/data/data/com.termux/files/usr", TermuxPaths.PREFIX)
        assertEquals("/data/data/com.termux/files/usr/tmp", TermuxPaths.TMP)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", TermuxPaths.BASH)
    }

    @Test fun pathsAreReadWithoutTheDisk() {
        fun r(s: String) = ShellLexer.parse("x $s").commands[0].words[1].let { TermuxPaths.resolve(it) }
        assertEquals(TermuxPaths.HOME, r("~"))
        assertEquals("${TermuxPaths.HOME}/notes", r("~/notes/"))
        assertEquals("${TermuxPaths.HOME}/notes", r("\$HOME/notes"))
        assertEquals("${TermuxPaths.HOME}/notes", r("\"\${HOME}\"/notes"))
        assertEquals("${TermuxPaths.PREFIX}/etc", r("\$PREFIX/etc"))
        assertEquals("${TermuxPaths.HOME}/a", r("x/../a"))
        assertEquals("/etc/hosts", r("../../../../../../etc/hosts"))
        assertEquals("/", r("/../.."))
        assertEquals("${TermuxPaths.HOME}/x", r("/data/user/0/com.termux/files/home/x"))
        assertNull(r("\$X/a"))
        assertNull(r("\$HOMEX"))
        assertNull(r("~root/x"))
        assertNull(r("\$(pwd)/x"))
        assertTrue(TermuxPaths.writableInside("${TermuxPaths.HOME}/x"))
        assertTrue(TermuxPaths.writableInside("${TermuxPaths.TMP}/x"))
        assertTrue(TermuxPaths.writableInside("/dev/null"))
        assertFalse(TermuxPaths.writableInside("${TermuxPaths.HOME}/storage/shared/x"))
        assertFalse(TermuxPaths.writableInside("/sdcard/x"))
        assertFalse(TermuxPaths.writableInside("${TermuxPaths.PREFIX}/bin/x"))
        assertFalse(TermuxPaths.writableInside("${TermuxPaths.HOME}x"))
    }
}
