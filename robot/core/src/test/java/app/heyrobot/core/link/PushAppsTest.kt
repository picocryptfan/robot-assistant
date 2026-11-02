// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushAppsTest {
    @Test fun anAppsNameIsOneShortCleanLine() {
        assertEquals("Example Push", PushApps.label("  Example‮\n Push​ ".replace("\n ", " "), "org.example"))
        assertEquals("org.example", PushApps.label("‮\u0000 ", "org.example"))
        assertEquals("org.example", PushApps.label(null, "org.example"))
        assertEquals(60, PushApps.label("x".repeat(500), "org.example").length)
    }

    @Test fun onlyAPackageNameIsAPackage() {
        assertTrue(PushApps.packageOk("org.unifiedpush.distributor.nextpush"))
        assertFalse(PushApps.packageOk(""))
        assertFalse(PushApps.packageOk("nodots"))
        assertFalse(PushApps.packageOk("org/evil"))
        assertFalse(PushApps.packageOk("a." + "b".repeat(300)))
    }
}
