// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.heyrobot.core.LookSettings
import app.heyrobot.sys.LookStore

// Main and voice processes. Material 3 as Android's own apps use it: the wallpaper's colours (Material You) unless
// Settings › Display turns them off for Robot's own (RobotColors), light or dark as the system is, the system font in
// the default type scale, the default shapes. The assistant panel and History's turns are the classic look (ui/classic),
// in Robot 0.4's type: ClassicTheme.

/** The standard type scale on the system font (FontFamily.Default). */
val StockTypography = Typography()

/** Robot 0.4's type, for the classic panel and History's turns only: SemiBold headlines and a larger, looser bodyLarge. */
val ClassicTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    )
}

/** The colours for [dark]: the wallpaper's when [wallpaper] and Android has them, else Robot's own. */
fun robotColorScheme(ctx: Context, dark: Boolean, wallpaper: Boolean): ColorScheme {
    if (wallpaper) {
        try {
            return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } catch (_: Throwable) {}
    }
    return if (dark) RobotColors.Dark else RobotColors.Light
}

/** Robot's theme. [look] is Settings › Display (look.json, read once per process and again when the panel opens). */
@Composable
fun RobotTheme(
    dark: Boolean = isSystemInDarkTheme(),
    look: LookSettings = LookStore.current(LocalContext.current),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = robotColorScheme(LocalContext.current, dark, look.wallpaperColors),
        typography = StockTypography,
        shapes = Shapes(),
        content = content,
    )
}

/** The classic look's type around [content] (the panel, a turn in History), in the theme's own colours and shapes. */
@Composable
fun ClassicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = ClassicTypography, shapes = MaterialTheme.shapes, content = content)
}
