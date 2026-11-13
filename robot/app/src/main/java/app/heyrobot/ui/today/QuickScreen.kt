// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.QuickArrange
import app.heyrobot.core.QuickButton
import app.heyrobot.core.QuickItem
import app.heyrobot.core.QuickItems
import app.heyrobot.core.QuickPhone
import app.heyrobot.core.QuickState
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.QuickFacts
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.Tab
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// HS3 (home-screens plan, "What's new" 3 and 4): Quick access, big buttons for the features this phone has, in your order.

/** Quick access in [order], wired to the app: each button opens its feature itself, and Back comes here. */
@Composable
fun QuickAccess(order: List<QuickItem>, a: AppActions, modifier: Modifier, onGettingStarted: (() -> Unit)?, onBack: (() -> Unit)? = null,
    onMove: ((item: QuickItem, to: Int, shown: List<QuickItem>) -> Unit)? = null) {
    QuickRead(order, QuickFacts::phone) { buttons ->
        QuickHome(buttons, modifier, onOpen = { open(it, a) }, onHistory = { a.openPage(Page.History) }, onGettingStarted = onGettingStarted, onBack = onBack,
            onMove = onMove?.let { m -> { i: QuickItem, to: Int -> m(i, to, buttons.map { it.item }) } })
    }
}

/** Review L5: nothing until [read] has this phone's facts, then every button at once (none pops in after the first read). */
@Composable
internal fun QuickRead(order: List<QuickItem>, read: (android.content.Context) -> QuickPhone, content: @Composable (List<QuickButton>) -> Unit) {
    val ctx = LocalContext.current
    val phone by produceState<QuickPhone?>(null, ctx) { value = withContext(Dispatchers.IO) { read(ctx) } }
    phone?.let { content(QuickItems.shown(order, it)) }
}

/** Where a button goes (review M2): a page of its own over Quick access (Back comes here), a tab, or the feature itself. */
internal sealed interface QuickGo {
    data class Over(val page: Page) : QuickGo
    data class ToTab(val tab: Tab) : QuickGo
    data class Notes(val lists: Boolean) : QuickGo
    enum class Feature : QuickGo { TALK, CAR, TRANSCRIBE, TRANSCRIPTION_CHECK, INTERPRETER }
}

/**
 * What [b]'s tap opens: its feature, never a Settings tab. Robot to Robot, Skills and Routines: their own page, which
 * SettingsNav draws by its route alone (no Settings page under it), so Back comes to Quick access (QuickTargetTest).
 */
internal fun target(b: QuickButton): QuickGo = when (b.item) {
    QuickItem.TALK -> QuickGo.Feature.TALK
    QuickItem.CAR -> QuickGo.Feature.CAR
    QuickItem.LINK -> QuickGo.Over(Page.Settings(SettingsRoutes.LINK))
    QuickItem.SKILLS -> QuickGo.Over(Page.Settings(SettingsRoutes.SKILLS))
    QuickItem.ROUTINES -> QuickGo.Over(Page.Settings(SettingsRoutes.ROUTINES))
    QuickItem.LISTS -> QuickGo.Notes(lists = true)
    QuickItem.NOTES -> QuickGo.Notes(lists = false)
    QuickItem.FILES -> QuickGo.ToTab(Tab.FILES)
    QuickItem.CALENDAR -> QuickGo.ToTab(Tab.CALENDAR)
    QuickItem.HISTORY -> QuickGo.Over(Page.History)
    QuickItem.TRANSCRIBE -> if (b.state == QuickState.SET_UP) QuickGo.Feature.TRANSCRIPTION_CHECK else QuickGo.Feature.TRANSCRIBE
    // Set up: the translation files' page (Settings › Conversation), where you choose to download them.
    QuickItem.INTERPRETER -> if (b.state == QuickState.SET_UP) QuickGo.Over(Page.ConversationSettings) else QuickGo.Feature.INTERPRETER
}

private fun open(b: QuickButton, a: AppActions) {
    when (val g = target(b)) {
        is QuickGo.Over -> a.openPage(g.page)
        is QuickGo.ToTab -> a.openTab(g.tab)
        is QuickGo.Notes -> a.openNotes(g.lists)
        QuickGo.Feature.TALK -> a.talk(null)
        QuickGo.Feature.CAR -> a.carMode()
        QuickGo.Feature.TRANSCRIBE -> a.transcribe()
        QuickGo.Feature.TRANSCRIPTION_CHECK -> a.openTranscriptionCheck()
        QuickGo.Feature.INTERPRETER -> a.interpret()
    }
}

private fun icon(i: QuickItem): ImageVector = when (i) {
    QuickItem.TALK -> RIcons.Mic; QuickItem.CAR -> RIcons.Car; QuickItem.LINK -> RIcons.Robot; QuickItem.SKILLS -> RIcons.Bookmark
    QuickItem.LISTS -> RIcons.Checklist; QuickItem.NOTES -> RIcons.Note; QuickItem.FILES -> RIcons.Folder; QuickItem.CALENDAR -> RIcons.Event
    QuickItem.HISTORY -> RIcons.History; QuickItem.TRANSCRIBE -> RIcons.RecordVoiceOver; QuickItem.INTERPRETER -> RIcons.Conversation
    QuickItem.ROUTINES -> RIcons.Refresh
}

/**
 * The page: History's clock icon at the top as on the other homes, the "Getting started" link as Today has, then [buttons] in
 * 2 columns (1 at 1.5x font and up), each 96 dp tall with its icon and name, and "Set up" under a missing one you can get.
 * [onMove]: press and hold a button and drag it onto another's place; TalkBack has Move up and Move down on each.
 */
@Composable
fun QuickHome(buttons: List<QuickButton>, modifier: Modifier, onOpen: (QuickButton) -> Unit, onHistory: () -> Unit, onGettingStarted: (() -> Unit)?,
    onBack: (() -> Unit)? = null, onMove: ((QuickItem, Int) -> Unit)? = null) {
    val cols = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
    val bounds = remember { mutableStateMapOf<QuickItem, Rect>() }
    var dragging by remember { mutableStateOf<QuickItem?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(RIcons.Back, stringResource(R.string.today_calendar_back)) } }
            Text(stringResource(R.string.today_quick_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onHistory) { Icon(RIcons.History, UiStrings.history().str()) }
        }
        onGettingStarted?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_getting_started)) } }
        val up = stringResource(R.string.today_move_up)
        val down = stringResource(R.string.today_move_down)
        val setUp = stringResource(R.string.today_quick_set_up)
        buttons.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { b ->
                    val at = buttons.indexOf(b)
                    val name = QuickArrange.name(b.item).str()
                    val moving = dragging == b.item
                    val drag = if (onMove == null) Modifier else Modifier.pointerInput(b.item, buttons) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = b.item; offset = Offset.Zero },
                            onDrag = { c, d -> c.consume(); offset += d },
                            onDragEnd = {
                                val at2 = bounds[b.item]?.center?.plus(offset)
                                val to = buttons.indexOfFirst { o -> at2 != null && bounds[o.item]?.contains(at2) == true }
                                dragging = null; offset = Offset.Zero
                                if (to >= 0 && to != at) onMove(b.item, to)
                            },
                            onDragCancel = { dragging = null; offset = Offset.Zero },
                        )
                    }
                    Surface(onClick = { onOpen(b) }, shape = RoundedCornerShape(20.dp),
                        color = if (b.state == QuickState.READY) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).heightIn(min = 96.dp)
                            .onGloballyPositioned { bounds[b.item] = it.boundsInRoot() }
                            .graphicsLayer { if (moving) { translationX = offset.x; translationY = offset.y; shadowElevation = 8f } }
                            .then(drag)
                            .clearAndSetSemantics {
                                contentDescription = name
                                role = Role.Button
                                if (b.state == QuickState.SET_UP) stateDescription = setUp
                                onClick(name) { onOpen(b); true }
                                if (onMove != null) customActions = listOfNotNull(
                                    CustomAccessibilityAction(up) { onMove(b.item, at - 1); true }.takeIf { at > 0 },
                                    CustomAccessibilityAction(down) { onMove(b.item, at + 1); true }.takeIf { at < buttons.size - 1 },
                                )
                            }) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icon(b.item), null, Modifier.size(32.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            if (b.state == QuickState.SET_UP) Text(setUp, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

/** "Show quick access": the page over whichever home you picked, with Back. */
@Composable
fun QuickPage(a: AppActions, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val order by produceState(QuickItem.DEFAULT, ctx) { value = withContext(Dispatchers.IO) { app.heyrobot.sys.HomeSettingsStore.current(ctx).quick } }
    QuickAccess(order, a, modifier, onGettingStarted = null, onBack = onBack)
}
