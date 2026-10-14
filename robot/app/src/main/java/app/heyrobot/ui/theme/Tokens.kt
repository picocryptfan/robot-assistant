package app.heyrobot.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Main and voice processes. The few measures Robot sets itself, in one place. Everything else is the Material 3
// default (colour roles, the type scale on the system font, the shapes 4/8/12/16/28 dp). The settings measures follow
// Android's own Settings app (AOSP SettingsLib Spa: SettingsDimension, SettingsShape, Category, BaseLayout).

object RobotTokens {
    /**
     * Settings pages in rounded groups (Android 16's expressive Settings: a 20 dp group of rows with 4 dp corners, 2 dp
     * apart, on a darker page). False: flat rows on the page, as before Android 16. To be confirmed beside GrapheneOS
     * Settings on the phone.
     */
    const val groupedSettings = true
    val groupCorner = 20.dp
    val itemCorner = 4.dp
    val groupGap = 2.dp

    /** A settings row: at least 72 dp tall, 16 dp in from the sides and 12 dp from top and bottom. */
    val prefMinHeight = 72.dp
    val prefPaddingH = 16.dp
    val prefPaddingV = 12.dp

    /** Space between a page's edge and its content. */
    val pagePadding = 16.dp

    /** The smallest touch target (Android accessibility). */
    val minTouch = 48.dp

    /** A list row with a choice in it (a card's "Mom · mobile"), and a card's padding. */
    val choiceRowHeight = 56.dp
    val cardPadding = 16.dp

    /** Settings' row titles: its titleMedium, 20 sp on 24, regular (SettingsTypography). Summaries are bodyMedium. */
    val prefTitle = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 0.02.em)
}
