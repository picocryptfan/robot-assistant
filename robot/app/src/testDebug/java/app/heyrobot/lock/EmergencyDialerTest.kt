// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.1, Phone lock §6: the emergency dialer Robot opens (from the cover's Emergency call, or for an emergency
 * number asked during a lock) is a system app's, named outright, never an app that only claims the action. The number
 * here is a fictional 555 one: nothing names or dials a real emergency number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmergencyDialerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pm get() = shadowOf(ctx.packageManager)
    private val number = Uri.fromParts("tel", "5550100", null)

    private fun dialer(pkg: String, system: Boolean): ComponentName {
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
        })
        val c = ComponentName(pkg, "$pkg.EmergencyDialer")
        pm.addActivityIfNotPresent(c)
        pm.addIntentFilterForActivity(c, IntentFilter(LockSignals.EMERGENCY_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        pm.addIntentFilterForActivity(c, IntentFilter(LockSignals.EMERGENCY_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel") })
        return c
    }

    @Test fun theSystemAppsEmergencyDialerIsNamedWithTheNumber() {
        dialer("com.example.claims", system = false)
        val phone = dialer("com.android.phone", system = true)
        val i = LockSignals.emergencyDialer(ctx, number)!!
        assertEquals(phone, i.component)
        assertEquals(LockSignals.EMERGENCY_DIAL, i.action)
        assertEquals("tel:5550100", i.dataString)
        // The cover's button: the same dialer, with no number.
        val cover = LockSignals.emergencyDialer(ctx)!!
        assertEquals(phone, cover.component)
        assertNull(cover.data)
    }

    @Test fun anAppThatOnlyClaimsTheActionIsNeverOpened() {
        dialer("com.example.claims", system = false)
        assertNull(LockSignals.emergencyDialer(ctx, number))
        assertNull(LockSignals.emergencyDialer(ctx))
    }
}
