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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.heyrobot.core.LookSettings
import app.heyrobot.sys.LookStore

// Main and voice processes. Material 3 as Android's own apps use it: the wallpaper's colours (Material You) unless
// Settings › Display turns them off for Robot's own (RobotColors), light or dark as the system is, the system font in
// the default type scale, the default shapes. The Classic look (Settings › Display) brings back Robot 0.4's type.

/** The standard type scale on the system font (FontFamily.Default). */
val StockTypography = Typography()

/** Robot 0.4's type, for the Classic look only: SemiBold headlines and a larger, looser bodyLarge. */
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

/**
 * Robot's theme. [look] is Settings › Display (look.json, read once per process and again when the panel opens); the
 * screens find the look in [LocalLook].
 */
@Composable
fun RobotTheme(
    dark: Boolean = isSystemInDarkTheme(),
    look: LookSettings = LookStore.current(LocalContext.current),
    content: @Composable () -> Unit,
) {
    val scheme = robotColorScheme(LocalContext.current, dark, look.wallpaperColors)
    CompositionLocalProvider(LocalLook provides if (look.classic) LookStyle.CLASSIC else LookStyle.STOCK) {
        MaterialTheme(
            colorScheme = scheme,
            typography = if (look.classic) ClassicTypography else StockTypography,
            shapes = Shapes(),
            content = content,
        )
    }
}
