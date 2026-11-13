// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** review-d and review-e Lows: a lock screen Robot can't read counts as up, through keyguard() itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyguardFailsClosedTest {
    @Test fun aKeyguardThatThrowsCountsAsLocked() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val none = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.KEYGUARD_SERVICE) null else super.getSystemService(name)
        }
        assertEquals(true, Assistant.keyguard(none))
        assertEquals(false, Assistant.keyguard(app))
    }
}
