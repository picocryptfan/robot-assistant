package app.heyrobot.ui.pref

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.Mark
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTokens

// MAIN PROCESS. Settings pages built like Android's own Settings app (AOSP SettingsLib Spa, which Settings is written
// with): a large top app bar that collapses, rows in groups under a small coloured header, a title with its current
// state on one line under it, switches on the right, a main switch bar for a page that is on or off as a whole, single
// choices in a radio dialog, text in an edit dialog, and the explanations in one footer at the end. Measures in
// RobotTokens. Every row is one node for TalkBack; group headers are headings.

/** Inside a group: rows draw their own background (the expressive grouping). */
private val LocalInGroup = staticCompositionLocalOf { false }

/** The page colour behind settings groups (Settings' surfaceContainer), or the plain surface for flat rows. */
@Composable
fun settingsBackground(): Color =
    if (RobotTokens.groupedSettings) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface

/** Settings' row title (20 sp), or [MaterialTheme]'s bodyLarge size off the settings pages. */
@Composable
fun prefTitleStyle(): TextStyle = MaterialTheme.typography.bodyLarge.merge(RobotTokens.prefTitle)

/**
 * A settings page: a large top app bar with Back (when [onBack]) that collapses as the list scrolls, and [content] as a
 * lazy list 16 dp in from the sides. Build the list with [preferenceGroup], [preferenceFooter] and plain items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferenceScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val bg = settingsBackground()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = bg,
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(RIcons.Back, "Back") } },
                actions = actions,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = bg, scrolledContainerColor = bg),
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
    ) { pad ->
        val dir = LocalLayoutDirection.current
        // Below the top bar, not under it: a row scrolled up is cut at the bar's edge, so its touch area never lies under
        // Back or the bar's actions.
        LazyColumn(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = pad.calculateStartPadding(dir) + RobotTokens.pagePadding,
                end = pad.calculateEndPadding(dir) + RobotTokens.pagePadding,
                bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            content = content,
        )
    }
}

/** A group of rows under [title] (none: just a gap), as one list item with [key]. */
fun LazyListScope.preferenceGroup(title: String?, key: String, content: @Composable ColumnScope.() -> Unit) {
    item(key = key, contentType = "group") { PreferenceGroup(title, content = content) }
}

/** The page's footer: the explanations, after the last group. */
fun LazyListScope.preferenceFooter(text: String, key: String = "footer") {
    item(key = key, contentType = "footer") { FooterPreference(text) }
}

/**
 * A long group (People, a server's models) as lazy rows under [title]: each of [items] is its own list item, drawn as
 * one group (20 dp corners at its ends, 4 dp between, 2 dp apart), so thousands of rows scroll without composing them all.
 */
fun <T> LazyListScope.preferenceItems(
    title: String?,
    key: String,
    items: List<T>,
    itemKey: (T) -> Any,
    content: @Composable (T) -> Unit,
) {
    item(key = key, contentType = "category") { if (title != null) PreferenceCategory(title) else Spacer(Modifier.height(16.dp)) }
    itemsIndexed(items, key = { _, it -> itemKey(it) }, contentType = { _, _ -> "row" }) { i, it ->
        if (RobotTokens.groupedSettings) {
            val top = if (i == 0) RobotTokens.groupCorner else RobotTokens.itemCorner
            val bottom = if (i == items.lastIndex) RobotTokens.groupCorner else RobotTokens.itemCorner
            Box(Modifier.padding(top = if (i == 0) 0.dp else RobotTokens.groupGap).clip(RoundedCornerShape(top, top, bottom, bottom))) {
                CompositionLocalProvider(LocalInGroup provides true) { content(it) }
            }
        } else {
            content(it)
        }
    }
}

/**
 * Rows under a category header. Grouped (Android 16 Settings): a container with 20 dp corners whose rows have 4 dp
 * corners and 2 dp gaps; flat otherwise.
 */
@Composable
fun PreferenceGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) PreferenceCategory(title) else Spacer(Modifier.height(16.dp))
        if (RobotTokens.groupedSettings) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(RobotTokens.groupCorner)),
                verticalArrangement = Arrangement.spacedBy(RobotTokens.groupGap),
            ) {
                CompositionLocalProvider(LocalInGroup provides true) { content() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** A group's header: small, in the theme's primary colour, a heading for TalkBack. */
@Composable
fun PreferenceCategory(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth()
            .padding(start = RobotTokens.prefPaddingH, end = RobotTokens.prefPaddingH, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A row's frame: at least 72 dp, its own background and 4 dp corners inside a group, [interaction] clipped to it. */
@Composable
private fun PrefFrame(
    interaction: Modifier,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val grouped = RobotTokens.groupedSettings && LocalInGroup.current
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = RobotTokens.prefMinHeight)
            .clip(if (grouped) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (grouped) MaterialTheme.colorScheme.surfaceBright else Color.Transparent)
            .then(interaction)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RobotTokens.prefPaddingH, vertical = RobotTokens.prefPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A row of its own making inside a group (a slider with labels, numbered steps, a test's result lines): the row's
 * background, corners and padding, its [content] in a column.
 */
@Composable
fun PreferenceBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val grouped = RobotTokens.groupedSettings && LocalInGroup.current
    Column(
        modifier.fillMaxWidth()
            .clip(if (grouped) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (grouped) MaterialTheme.colorScheme.surfaceBright else Color.Transparent)
            .padding(horizontal = RobotTokens.prefPaddingH, vertical = RobotTokens.prefPaddingV),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The icon, title and summary of a row; [summaryInSemantics] false when the row states its value another way. */
@Composable
private fun RowScope.PrefTexts(
    title: String,
    summary: String?,
    icon: ImageVector?,
    enabled: Boolean,
    leading: (@Composable () -> Unit)? = null,
    summaryInSemantics: Boolean = true,
) {
    val alpha = if (enabled) 1f else DISABLED
    val c = MaterialTheme.colorScheme
    if (leading != null || icon != null) {
        Box(Modifier.defaultMinSize(24.dp, 24.dp), contentAlignment = Alignment.Center) {
            if (leading != null) leading() else Icon(icon!!, null, tint = c.onSurface.copy(alpha = alpha))
        }
        Spacer(Modifier.width(16.dp))
    }
    Column(Modifier.weight(1f)) {
        Text(title, style = prefTitleStyle(), color = c.onSurface.copy(alpha = alpha))
        if (!summary.isNullOrEmpty()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant.copy(alpha = alpha),
                modifier = if (summaryInSemantics) Modifier else Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** The opacity of a disabled row's content (Material's 38 %). */
private const val DISABLED = 0.38f

/**
 * A row: [title], its current state in [summary] (one short line), an optional [icon] (tinted onSurface, as in
 * Settings). With [onClick] the whole row is the target (it opens a page or a dialog). [trailing] sits at the end.
 */
@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    PrefFrame(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier, modifier) {
        PrefTexts(title, summary, icon, enabled, leading = leading)
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** Settings' switch: a check in the thumb when on, a cross when off. [onCheckedChange] null when its row toggles it. */
@Composable
fun PrefSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)? = null, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        thumbContent = { Icon(if (checked) RIcons.Tick else RIcons.Close, null, Modifier.size(SwitchDefaults.IconSize)) },
    )
}

/** A row with a switch at the end; the whole row toggles it (one TalkBack node, role Switch). */
@Composable
fun SwitchPreference(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    PrefFrame(Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange), modifier) {
        PrefTexts(title, summary, icon, enabled)
        Spacer(Modifier.width(16.dp))
        PrefSwitch(checked, enabled)
    }
}

/**
 * Two targets in one row (Settings' "two-target" preference): the text opens something ([onClick]), the switch after the
 * divider toggles, with its own TalkBack label [switchLabel] (unique in a list: "Listen for “hey robot”").
 */
@Composable
fun TwoTargetSwitchPreference(
    title: String,
    summary: String?,
    checked: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    switchLabel: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val grouped = RobotTokens.groupedSettings && LocalInGroup.current
    Row(
        modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = RobotTokens.prefMinHeight)
            .clip(if (grouped) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (grouped) MaterialTheme.colorScheme.surfaceBright else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = RobotTokens.prefPaddingH, vertical = RobotTokens.prefPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) { PrefTexts(title, summary, icon, enabled) }
        VerticalDivider(Modifier.height(32.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier.fillMaxHeight()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics { contentDescription = switchLabel }
                .padding(horizontal = RobotTokens.prefPaddingH),
            contentAlignment = Alignment.Center,
        ) { PrefSwitch(checked, enabled) }
    }
}

/**
 * Two targets in one row, the general case: the text (and [leading]) opens something or picks it ([onClick], as [role]:
 * a radio row passes [selected]), and [trailing] holds targets of its own (Keep and Remove, a details button), each with
 * its own label.
 */
@Composable
fun TwoTargetPreference(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    role: Role = Role.Button,
    selected: Boolean? = null,
    onClickLabel: String? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    val grouped = RobotTokens.groupedSettings && LocalInGroup.current
    Row(
        modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = RobotTokens.prefMinHeight)
            .clip(if (grouped) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (grouped) MaterialTheme.colorScheme.surfaceBright else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val target = if (selected != null) Modifier.selectable(selected, enabled = enabled, role = role, onClick = onClick)
            else Modifier.clickable(enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .then(target)
                .semantics(mergeDescendants = true) {}
                .padding(start = RobotTokens.prefPaddingH, end = 8.dp, top = RobotTokens.prefPaddingV, bottom = RobotTokens.prefPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) { PrefTexts(title, summary, icon, enabled, leading = leading) }
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * A tick box (0.7, Send feedback's diagnostics): the box and the text toggle it (one TalkBack node, role Checkbox), and
 * [trailing] holds a target of its own (Preview), with its own label.
 */
@Composable
fun CheckboxPreference(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val grouped = RobotTokens.groupedSettings && LocalInGroup.current
    Row(
        modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = RobotTokens.prefMinHeight)
            .clip(if (grouped) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (grouped) MaterialTheme.colorScheme.surfaceBright else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
                .semantics(mergeDescendants = true) {}
                .padding(start = RobotTokens.prefPaddingH, end = 8.dp, top = RobotTokens.prefPaddingV, bottom = RobotTokens.prefPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) { PrefTexts(title, summary, null, enabled, leading = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) }) }
        if (trailing != null) Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * The switch for a whole page ("Use Ask Claude"), as Settings' main switch bar: a rounded bar in primaryContainer when
 * on, secondaryContainer when off. Turning it off should disable the rest of the page.
 */
@Composable
fun MainSwitchBar(title: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = if (checked) c.primaryContainer else c.secondaryContainer,
        contentColor = if (checked) c.onPrimaryContainer else c.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = RobotTokens.prefMinHeight)
                .padding(horizontal = 24.dp, vertical = RobotTokens.prefPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = prefTitleStyle(), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            PrefSwitch(checked, enabled)
        }
    }
}

/**
 * A single choice (Settings' ListPreference): the row shows the current value under the title and opens a radio dialog
 * that closes on a pick. For TalkBack the row is a button whose state is the value. Replaces segmented buttons. A
 * [summary] shows (and is read) in place of the value when the row says more.
 */
@Composable
fun <T> ListPreference(
    title: String,
    value: T,
    entries: List<T>,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onPick: (T) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val now = label(value)
    // [summary] says more than the value ("Ask first · works locked"); TalkBack still states the value, then the rest.
    PrefFrame(
        Modifier.clickable(enabled = enabled, role = Role.Button) { open = true }.semantics { stateDescription = summary ?: now },
        modifier,
    ) { PrefTexts(title, summary ?: now, icon, enabled, summaryInSemantics = false) }
    if (open) {
        RadioDialog(title, value, entries, label, detail, onDismiss = { open = false }, onPick = { open = false; onPick(it) })
    }
}

/**
 * A text value (Settings' EditTextPreference): the row shows it ([emptySummary] when there is none) and opens an edit
 * dialog; Save stays off while [validate] gives an error, shown under the field.
 */
@Composable
fun TextPreference(
    title: String,
    value: String,
    emptySummary: String,
    fieldLabel: String,
    supporting: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    validate: (String) -> String? = { null },
    onSave: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceItem(title, value.ifEmpty { emptySummary }, icon, enabled, onClick = { open = true })
    if (open) {
        EditDialog(title, value, fieldLabel, supporting, validate, keyboard = keyboard, onDismiss = { open = false }, onSave = { open = false; onSave(it) })
    }
}

/**
 * A number on a slider under the title, its value at the end of the title line ([valueText], also TalkBack's state).
 * [onChangeFinished] when the finger lifts.
 */
@Composable
fun SliderPreference(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: (Float) -> String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onChangeFinished: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    val alpha = if (enabled) 1f else DISABLED
    val grouped = RobotTokens.groupedSettings && LocalInGroup.current
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = RobotTokens.prefMinHeight)
            .clip(if (grouped) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (grouped) MaterialTheme.colorScheme.surfaceBright else Color.Transparent)
            .padding(horizontal = RobotTokens.prefPaddingH, vertical = RobotTokens.prefPaddingV),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {}) {
            PrefTexts(title, null, icon, enabled)
            Spacer(Modifier.width(16.dp))
            Text(valueText(v), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = { onChangeFinished(v) },
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText(v)
            },
        )
    }
}

/**
 * A status: a symbol for done, to do or a problem (read by TalkBack as "Done", "To do", "Problem"), the title, the state,
 * and an optional [action] at the end ("Allow", "Open"). With [onClick] the row opens the page that fixes it.
 */
@Composable
fun StatusPreference(
    title: String,
    summary: String?,
    status: Mark,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    PrefFrame(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, modifier) {
        PrefTexts(title, summary, null, true, leading = { StatusIcon(status) })
        if (action != null) {
            Spacer(Modifier.width(16.dp))
            action()
        }
    }
}

/** The status symbol alone: check_circle in primary, an empty circle, or error in the error colour; always labelled. */
@Composable
fun StatusIcon(status: Mark, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    when (status) {
        Mark.OK -> Icon(RIcons.Check, "Done", modifier, tint = c.primary)
        Mark.TODO -> Icon(RIcons.Unchecked, "To do", modifier, tint = c.onSurfaceVariant)
        Mark.WARN -> Icon(RIcons.Error, "Problem", modifier, tint = c.error)
    }
}

/** The page's explanations, after the last group: an info symbol over the text (Settings' footer). */
@Composable
fun FooterPreference(text: String, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(start = RobotTokens.prefPaddingH, end = RobotTokens.prefPaddingH, top = 24.dp, bottom = 8.dp),
    ) {
        Icon(RIcons.Info, null, tint = muted)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

/** A short introduction at the top of a page, before the first group. */
@Composable
fun IntroPreference(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = RobotTokens.prefPaddingH, vertical = 8.dp),
    )
}
