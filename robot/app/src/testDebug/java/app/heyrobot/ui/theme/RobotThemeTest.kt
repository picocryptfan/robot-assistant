package app.heyrobot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.heyrobot.core.LookSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RobotTheme follows Settings › Display (the wallpaper's colours or Robot's own) in the standard type everywhere; the
 * panel and History's turns get Robot 0.4's type from ClassicTheme, in the same colours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotThemeTest {
    @get:Rule val rule = createComposeRule()

    private class Seen(var typography: Typography? = null, var colors: ColorScheme? = null)

    private fun seen(dark: Boolean, look: LookSettings, classic: Boolean = false): Seen {
        val s = Seen()
        rule.setContent {
            RobotTheme(dark = dark, look = look) {
                if (classic) ClassicTheme {
                    s.typography = MaterialTheme.typography
                    s.colors = MaterialTheme.colorScheme
                } else {
                    s.typography = MaterialTheme.typography
                    s.colors = MaterialTheme.colorScheme
                }
            }
        }
        rule.waitForIdle()
        return s
    }

    @Test fun everyScreenUsesTheDefaultTypeScale() {
        assertEquals(Typography(), seen(false, LookSettings()).typography)
    }

    @Test fun thePanelBringsBackRobot04sTypeInTheSameColours() {
        val s = seen(false, LookSettings(wallpaperColors = false), classic = true)
        val t = s.typography!!
        assertEquals(17.sp, t.bodyLarge.fontSize)
        assertEquals(24.sp, t.bodyLarge.lineHeight)
        assertEquals(0.2.sp, t.bodyLarge.letterSpacing)
        assertEquals(FontWeight.SemiBold, t.headlineMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, t.titleLarge.fontWeight)
        assertEquals(RobotColors.Light, s.colors)
    }

    @Test fun wallpaperColoursOffGivesRobotsOwnSchemeLightAndDark() {
        assertEquals(RobotColors.Light, seen(false, LookSettings(wallpaperColors = false)).colors)
    }

    @Test fun robotsDarkScheme() {
        assertEquals(RobotColors.Dark, seen(true, LookSettings(wallpaperColors = false), classic = true).colors)
    }

    @Test fun wallpaperColoursOnStillGivesAFullScheme() {
        // Robolectric has a fixed system palette, not the phone's: only that a scheme comes back.
        assertNotNull(seen(false, LookSettings()).colors?.surfaceContainer)
    }

    @Test fun robotsSchemeKeepsItsBlueAndTheMaterialRoles() {
        assertEquals(0xFF2E5F9A, RobotColors.SEED)
        // Every role is set (none left at the baseline purple): the dark surfaces are Robot's, not Material's defaults.
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1D2024), RobotColors.Dark.surfaceContainer)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF3C6090), RobotColors.Light.primary)
    }
}
