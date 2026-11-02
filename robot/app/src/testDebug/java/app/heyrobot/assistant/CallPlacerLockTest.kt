// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.car.CallPlacer
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.TimeWindow
import app.heyrobot.lock.LockEngine
import app.heyrobot.lock.LockSignals
import app.heyrobot.lock.LockStore
import app.heyrobot.lock.PhoneLockNow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** A call during a phone lock, run for real up to the launch: what opens, and what Robot says when nothing does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallPlacerLockTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val launched = ArrayList<Intent>()
    private val launch: (Intent) -> Boolean = { launched += it; true }

    private fun lockOn(who: LockFor = LockFor.ME) {
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val l = PhoneLock("lk_call0", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true, forWho = who), listOf(l)) })
        LockEngine.refresh(ctx)
        assertTrue(PhoneLockNow.on(ctx))
    }

    @After fun lockOff() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
    }

    private fun call(emergency: Boolean = false) = CallPlacer.run(ctx, Action.Call("Sam", number = if (emergency) "911" else "9025550123", emergency = emergency), launch)

    private fun systemEmergencyDialer() {
        val app = ApplicationInfo().apply { packageName = "com.android.phone"; flags = ApplicationInfo.FLAG_SYSTEM }
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = app.packageName; name = "EmergencyDialer"; applicationInfo = app } }
        val pm = shadowOf(ctx.packageManager)
        pm.addActivityIfNotPresent(ComponentName(app.packageName, "EmergencyDialer"))
        pm.addIntentFilterForActivity(ComponentName(app.packageName, "EmergencyDialer"), IntentFilter(LockSignals.EMERGENCY_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel") })
        pm.addResolveInfoForIntent(Intent(LockSignals.EMERGENCY_DIAL, android.net.Uri.fromParts("tel", "911", null)), info)
    }

    @Test fun duringALockAnOrdinaryCallIsRefusedBeforeAnythingOpens() {
        lockOn()
        val r = call()
        assertEquals(emptyList<Intent>(), launched)
        assertEquals(ActionStatus.NEEDS_UNLOCK, r.status)
        assertEquals(ToolStrings.callLockNotAllowed(), r.text)
    }

    @Test fun outsideALockTheDialerOpens() {
        val r = call()
        assertEquals(Intent.ACTION_DIAL, launched.single().action)
        assertEquals(ActionStatus.DONE, r.status)
    }

    /** fb11: your own lock with car mode your car started covers nothing (LockGate carOwnLock): the dialer still opens. */
    @Test fun inTheCarDuringYourOwnLockTheDialerOpens() {
        lockOn()
        LockStore.editState(ctx) { it.copy(car = System.currentTimeMillis() - 1000) }
        LockEngine.refresh(ctx)
        val r = call()
        assertEquals(Intent.ACTION_DIAL, launched.single().action)
        assertEquals(ActionStatus.DONE, r.status)
    }

    @Test fun inTheCarDuringSomeoneElsesLockTheCallIsStillRefused() {
        lockOn(LockFor.SOMEONE_ELSE)
        LockStore.editState(ctx) { it.copy(car = System.currentTimeMillis() - 1000) }
        LockEngine.refresh(ctx)
        val r = call()
        assertEquals(emptyList<Intent>(), launched)
        assertEquals(ActionStatus.NEEDS_UNLOCK, r.status)
    }

    /** Phone lock §6: a lock covers the ordinary dialer but never the phone's own emergency dialer. */
    @Test fun duringALockAnEmergencyNumberOpensTheEmergencyDialer() {
        lockOn()
        systemEmergencyDialer()
        val r = call(emergency = true)
        assertEquals(LockSignals.EMERGENCY_DIAL, launched.single().action)
        assertEquals(ActionStatus.DONE, r.status)
        assertEquals(ToolStrings.dialerWithNumber(), r.text)
    }

    @Test fun withNoEmergencyDialerTheNumberGoesToTheDialerAndRobotSaysItIsCovered() {
        lockOn()
        val r = call(emergency = true)
        assertEquals(Intent.ACTION_DIAL, launched.single().action)
        assertEquals(ToolStrings.dialerCovered(), r.text)
    }
}
