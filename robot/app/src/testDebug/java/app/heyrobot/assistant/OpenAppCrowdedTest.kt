// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Action
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * review-auto-open-b L1: ToolExecutor reads your own words through AppWords.crowded. "open maps" that the model sent as
 * Organic Maps, with Google Maps installed too, gets the pick card, never Organic Maps by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAppCrowdedTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun install(pkg: String, label: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(ctx.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Main"
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
            }
        })
        shadowOf(ctx.packageManager).addActivityIfNotPresent(ComponentName(pkg, "$pkg.Main"))
    }

    private fun prepare(said: String): Prepared {
        install("app.organicmaps", "Organic Maps")
        install("com.google.android.apps.maps", "Google Maps")
        return ToolExecutor(ctx, RobotSettings(), said = said) { true }.prepare(Action.OpenApp("Organic Maps"))
    }

    @Test fun aWordThatFitsTwoAppsGetsThePickCard() {
        val p = prepare("open maps")
        assertTrue(p.toString(), p is Prepared.Ready)
        p as Prepared.Ready
        assertTrue(p.why.toString(), AskWhy.PICK in p.why)
        assertEquals(setOf("Organic Maps", "Google Maps"), p.choices.map { it.label }.toSet())
    }

    @Test fun theWholeNameInYourWordsHasNoPickCard() {
        val p = prepare("open organic maps")
        assertTrue(p.toString(), p is Prepared.Ready)
        assertTrue((p as Prepared.Ready).why.toString(), AskWhy.PICK !in p.why)
    }
}
