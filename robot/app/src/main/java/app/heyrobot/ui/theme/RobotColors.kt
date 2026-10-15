// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Main and voice processes. Robot's own colours, when Settings › Display › "Use wallpaper colours" is off (and should
// the wallpaper's ever be unavailable): every Material 3 colour role, light and dark, generated from Robot's blue
// #2E5F9A with Google's material-color-utilities 0.3.0 (the library behind Material Theme Builder), scheme "tonal spot"
// (the same rule Android uses for wallpaper colours), standard contrast. This is the only file with colour values.

object RobotColors {
    const val SEED = 0xFF2E5F9A

    val Light = lightColorScheme(
        primary = Color(0xFF3C6090),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD4E3FF),
        onPrimaryContainer = Color(0xFF214876),
        inversePrimary = Color(0xFFA5C8FF),
        secondary = Color(0xFF545F71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD8E3F8),
        onSecondaryContainer = Color(0xFF3D4758),
        tertiary = Color(0xFF6D5676),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF7D9FF),
        onTertiaryContainer = Color(0xFF553F5E),
        background = Color(0xFFF9F9FF),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF9F9FF),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE0E2EB),
        onSurfaceVariant = Color(0xFF43474E),
        surfaceTint = Color(0xFF3C6090),
        inverseSurface = Color(0xFF2E3035),
        inverseOnSurface = Color(0xFFF0F0F7),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF74777F),
        outlineVariant = Color(0xFFC3C6CF),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF9F9FF),
        surfaceContainer = Color(0xFFEDEDF4),
        surfaceContainerHigh = Color(0xFFE7E8EE),
        surfaceContainerHighest = Color(0xFFE1E2E9),
        surfaceContainerLow = Color(0xFFF2F3FA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD9DAE0),
    )

    val Dark = darkColorScheme(
        primary = Color(0xFFA5C8FF),
        onPrimary = Color(0xFF00315E),
        primaryContainer = Color(0xFF214876),
        onPrimaryContainer = Color(0xFFD4E3FF),
        inversePrimary = Color(0xFF3C6090),
        secondary = Color(0xFFBCC7DC),
        onSecondary = Color(0xFF263141),
        secondaryContainer = Color(0xFF3D4758),
        onSecondaryContainer = Color(0xFFD8E3F8),
        tertiary = Color(0xFFDABDE2),
        onTertiary = Color(0xFF3D2946),
        tertiaryContainer = Color(0xFF553F5E),
        onTertiaryContainer = Color(0xFFF7D9FF),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE1E2E9),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE1E2E9),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC3C6CF),
        surfaceTint = Color(0xFFA5C8FF),
        inverseSurface = Color(0xFFE1E2E9),
        inverseOnSurface = Color(0xFF2E3035),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8D9199),
        outlineVariant = Color(0xFF43474E),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF37393E),
        surfaceContainer = Color(0xFF1D2024),
        surfaceContainerHigh = Color(0xFF282A2F),
        surfaceContainerHighest = Color(0xFF32353A),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainerLowest = Color(0xFF0C0E13),
        surfaceDim = Color(0xFF111318),
    )
}
