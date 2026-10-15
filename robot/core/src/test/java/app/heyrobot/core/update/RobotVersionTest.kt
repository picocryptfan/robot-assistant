// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Robot's versions as tags and versionName write them, and their order. */
class RobotVersionTest {
    private fun v(s: String) = requireNotNull(RobotVersion.parse(s)) { s }

    @Test fun releasesInOrder() {
        val order = listOf("0.4.4", "0.4.5", "0.4.6", "0.4.10", "0.5.0", "0.6.0-rc1", "0.6.0-rc2", "0.6.0-rc10", "0.6.0", "0.6.1", "1.0.0-alpha",
            "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "2.0.0", "10.0.0")
        for (i in order.indices) for (j in order.indices) {
            val c = v(order[i]).compareTo(v(order[j]))
            assertEquals("${order[i]} vs ${order[j]}", i.compareTo(j), Integer.signum(c))
        }
    }

    @Test fun theOrderTheUpdaterReliesOn() {
        assertTrue(v("0.4.5") < v("0.4.6"))
        assertTrue(v("0.4.6") < v("0.6.0"))
        assertTrue(v("0.6.0") < v("1.0.0"))
        assertTrue(v("0.6.0-rc1") < v("0.6.0"))
        assertTrue(v("0.6.0-rc1") > v("0.5.9"))
        assertTrue(v("0.4.5") > v("0.4.5-rc3"))
    }

    @Test fun aLeadingVAndMissingZerosDontChangeTheVersion() {
        assertEquals(v("0.4.6"), v("v0.4.6"))
        assertEquals(v("0.4.6"), v("V0.4.6"))
        assertEquals(v("1.0"), v("1.0.0"))
        assertEquals(v("1.0").hashCode(), v("1.0.0").hashCode())
        assertEquals(v("0.6.0-RC1"), v("0.6.0-rc1"))
        assertEquals(v("0.6.0-RC1").hashCode(), v("0.6.0-rc1").hashCode())
        assertEquals(v("0.6.0-rc01"), v("0.6.0-rc1"))
        assertEquals(v("0.6.0-rc01").hashCode(), v("0.6.0-rc1").hashCode())
        // The text keeps what was written, without the v: what the APK's name carries.
        assertEquals("0.6.0-rc1", v("v0.6.0-rc1").text)
        assertEquals("0.4.6", v("v0.4.6").toString())
    }

    @Test fun preReleases() {
        assertTrue(v("0.6.0-rc1").isPreRelease)
        assertTrue(v("v1.0.0-beta.2").isPreRelease)
        assertFalse(v("0.6.0").isPreRelease)
        assertTrue(v("1.0.0-rc-1").isPreRelease)
    }

    @Test fun notVersions() {
        for (s in listOf(null, "", "v", "V", "1", "v1", "1.", ".1", "1..2", "1.2.3.4.5", "01.2.3", "1.02.3", "1.2.3-", "1.2.3-rc..1", "1.2.3+build",
            "1.2.3 ", " 1.2.3", "1.2.3\n", "vv1.2.3", "latest", "1.2.x", "1,2,3", "-1.2.3", "1.2.-3", "1.2.3-rc_1", "1.2.3/../4",
            "1234567890.0.0", "1.2.3-" + "a".repeat(30), "1.2.3-" + "a.".repeat(40) + "a",
            // Look-alikes: full-width and Arabic-Indic digits, a Cyrillic "v", a non-breaking hyphen, a Cyrillic "с" in "rc".
            "０.４.６", "٠.٤.٦", "в0.4.6", "ѵ" + "0.4.6", "0.6.0‑rc1", "0.6.0-rс" + "1", "0.4.6​")) {
            assertNull(s.toString(), RobotVersion.parse(s))
        }
        assertNull(RobotVersion.parse("1." + "1".repeat(70)))
    }

    @Test fun fourNumbersAtMost() {
        assertNotNull(RobotVersion.parse("1.2.3.4"))
        assertTrue(v("1.2.3.4") > v("1.2.3"))
        assertEquals(v("1.2.3.0"), v("1.2.3"))
    }
}
