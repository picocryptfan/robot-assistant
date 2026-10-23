// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.lock.LockExtras
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Phone lock's service during a lock (§5): when the edit session or car mode your car started ends, the window in front
 * is looked at again; the lock screen gets no curtain and no cover behind it, and the unlock decides; at the unlock, a
 * window in front that the gate covers (Settings › Phone lock after the power button ended its session) gets the cover,
 * whatever app was last allowed; screen-off ends the edit session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockGateAgainTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = ctx as Application
    private lateinit var svc: LockAccessibilityService
    private val settings = "app.heyrobot.ui.MainActivity"
    private val reader = "org.example.reader"
    private val game = "org.example.game"

    @Before fun setUp() {
        // Phone lock on, for you, with a Time of day lock open now that lets the reader through.
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val homework = PhoneLock("lk_hw00", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()),
            extras = LockExtras(apps = listOf(reader)))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true), listOf(homework)) })
        svc = Robolectric.buildService(LockAccessibilityService::class.java).create().get()
        engine()
        assertTrue(LockEngine.snapshot!!.active)
        covered()
    }

    @After fun tearDown() {
        keyguard(false)
        LockEngine.car(ctx, on = false, byCar = true)
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The engine worked out again, and the service told, as LockEngine.refresh tells the connected service. */
    private fun engine() {
        svc.onEngine(LockEngine.refresh(ctx), startedNow = false)
        idle()
    }

    private fun event(pkg: String, cls: String) {
        svc.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = pkg; className = cls })
        idle()
    }

    /** The cover was started since the last look. */
    private fun covered(): Boolean {
        var cover = false
        while (true) {
            val i = shadowOf(app).nextStartedActivity ?: break
            if (i.component?.className == LockSignals.COVER) cover = true
        }
        return cover
    }

    private fun curtains(): Int = Shadow.extract<ShadowWindowManagerImpl>(ctx.getSystemService(WindowManager::class.java)).views.size

    private fun keyguard(locked: Boolean) = shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)

    /** What the system sends the service's receiver; its work runs on "robot-lock" threads, waited for here. */
    private fun screen(action: String) {
        val f = LockAccessibilityService::class.java.getDeclaredField("screen").apply { isAccessible = true }
        (f.get(svc) as BroadcastReceiver).onReceive(ctx, Intent(action))
        val until = System.currentTimeMillis() + 5_000
        while (Thread.getAllStackTraces().keys.any { it.name == "robot-lock" && it.isAlive } && System.currentTimeMillis() < until) Thread.sleep(20)
        idle()
    }

    @Test fun anAppThatIsntAllowedIsCovered() {
        event(game, "$game.Main")
        assertTrue(covered())
        event(reader, "$reader.Main")
        assertFalse(covered())
    }

    @Test fun theEditSessionsEndLooksAgainAtRobotsSettings() {
        LockEngine.openEdit(ctx)
        engine()
        // Settings › Phone lock inside the session, with one of its dialogs, the shade and the keyboard's window above it.
        event(ctx.packageName, settings)
        event(ctx.packageName, "androidx.compose.ui.window.DialogWrapper")
        event("com.android.systemui", "android.widget.FrameLayout")
        assertFalse(covered())
        // The session ends (its 5 minutes, or you left): Settings is covered without a new window event.
        LockEngine.endEdit(ctx)
        engine()
        assertTrue(covered())
    }

    @Test fun carModesEndLooksAgainAtTheApp() {
        LockEngine.car(ctx, on = true, byCar = true)
        engine()
        event(game, "$game.Main")
        assertFalse(covered())
        LockEngine.car(ctx, on = false, byCar = true)
        engine()
        assertTrue(covered())
    }

    @Test fun nothingCoversTheLockScreenAndTheUnlockDecides() {
        // The curtain comes with the cover while the phone is unlocked.
        event(game, "$game.Main")
        assertTrue(covered())
        assertEquals(1, curtains())
        event(reader, "$reader.Main")
        assertEquals(0, curtains())
        // An app over the lock screen: no curtain over it, no cover behind it.
        keyguard(true)
        event(game, "$game.Main")
        assertFalse(covered())
        assertEquals(0, curtains())
        // The unlock decides: the app in front isn't allowed.
        keyguard(false)
        screen(Intent.ACTION_USER_PRESENT)
        assertTrue(covered())
    }

    @Test fun anAllowedAppStaysOpenAtTheUnlock() {
        event(reader, "$reader.Main")
        keyguard(true)
        screen(Intent.ACTION_SCREEN_OFF)
        keyguard(false)
        screen(Intent.ACTION_USER_PRESENT)
        assertFalse(covered())
    }

    @Test fun settingsLeftByThePowerButtonIsCoveredAtTheUnlock() {
        // The reader was the last app allowed; then Settings › Phone lock, inside an edit session.
        event(reader, "$reader.Main")
        LockEngine.openEdit(ctx)
        engine()
        event(ctx.packageName, settings)
        assertFalse(covered())
        // Power: screen-off ends the session; the lock screen is up, so nothing is covered yet.
        keyguard(true)
        screen(Intent.ACTION_SCREEN_OFF)
        assertEquals(0L, LockStore.readState(ctx).edit)
        engine()
        assertFalse(covered())
        assertEquals(0, curtains())
        // Unlocked with someone else's PIN: Settings, still in front, gets the cover.
        keyguard(false)
        screen(Intent.ACTION_USER_PRESENT)
        assertTrue(covered())
    }
}
