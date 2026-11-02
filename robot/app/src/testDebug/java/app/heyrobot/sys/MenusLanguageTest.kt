// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.assistant.RobotSession
import app.heyrobot.core.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * LS12 (l10n-1.1 §7.2, its phone check P2): with the menus in French on an English phone, the panel's buttons stayed
 * English, because :voice's configuration kept the phone's language. The panel now takes the menus' language itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MenusLanguageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun menus(tags: String?) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales = tags?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }

    @After fun phone() = menus(null)

    @Test fun frenchMenusOnAnEnglishProcess() {
        menus("fr-CA")
        // This process's configuration is still English, as :voice's was on the Pixel 8.
        assertEquals("en", ctx.resources.configuration.locales[0].language)
        assertEquals("Arrêter", AppLanguage.localized(ctx).getString(R.string.panel_stop))
        assertEquals(Lang.FR, AppLanguage.langs(ctx).ui)
    }

    @Test fun thePhonesLanguageLeavesTheContextAlone() {
        menus(null)
        assertSame(ctx, AppLanguage.localized(ctx))
        assertEquals("Stop", ctx.getString(R.string.panel_stop))
        assertEquals(Lang.EN, AppLanguage.langs(ctx).ui)
    }

    /** The panel's window is built on the localized context (RobotSession has no Robolectric controller to run it). */
    @Test fun thePanelUsesIt() {
        val src = File("src/main/java/app/heyrobot/assistant/RobotSession.kt").readText()
        assertTrue(src.contains("ComposeView(AppLanguage.localized(context))"))
    }

    /**
     * lp-check phase 2: with Menus set back to English, the panel stayed French 30 minutes later, from the cover and from
     * Home. Its view was built once per session, and a session outlives its hides. At its next show it is built again.
     */
    @Test fun thePanelFollowsTheMenusAtItsNextShow() {
        menus("fr-CA")
        val session = RobotSession(ctx)
        assertEquals("Demande à Robot…", session.panelView().context.getString(R.string.panel_ask_robot))
        assertFalse(session.menusChanged())
        menus("en-US")
        assertTrue(session.menusChanged())
        assertEquals("Ask Robot…", session.panelView().context.getString(R.string.panel_ask_robot))
        assertFalse(session.menusChanged())
        menus(null)
        assertTrue(session.menusChanged())
    }
}
