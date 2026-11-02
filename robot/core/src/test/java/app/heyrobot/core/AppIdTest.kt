// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.update.ArchiveCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdTest {
    @After fun back() = AppId.bind(AppId.RELEASE)

    @Test fun releasesIdIsTheDefault() = assertEquals("ai.robot_app", AppId.PACKAGE)

    @Test fun theFixesNameTheBuildThatsRunning() {
        AppId.bind("ai.robot_app.other")
        assertEquals("ai.robot_app.other", AdbFixes.PACKAGE)
        assertTrue(AdbFixes.RECOGNIZER, AdbFixes.RECOGNIZER.startsWith("ai.robot_app.other/"))
    }

    @Test fun updatesCheckReleasesIdWhicheverBuildIsRunning() {
        AppId.bind("ai.robot_app.other")
        assertEquals("ai.robot_app", ArchiveCheck.PACKAGE)
    }

    @Test fun onlyReleasesIdEverUpdates() {
        assertTrue(AppId.updatesAllowed("ai.robot_app"))
        assertFalse(AppId.updatesAllowed("ai.robot_app.other"))
    }
}
