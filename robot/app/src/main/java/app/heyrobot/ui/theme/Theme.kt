package app.heyrobot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Main and voice processes. Material 3 with the wallpaper's dynamic colours (Android 12+, always on the
// supported phones), and a calm blue fallback scheme.

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF2E5F9A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF555F71), tertiary = Color(0xFF6E5676),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA7C8FF), onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF0E4782), onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBDC7DC), tertiary = Color(0xFFDABDE2),
)

private val RobotTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    )
}

@Composable
fun RobotTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = try {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } catch (_: Throwable) {
        if (dark) FallbackDark else FallbackLight
    }
    MaterialTheme(colorScheme = scheme, typography = RobotTypography, content = content)
}
