// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.lists

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.ListsFiles
import app.heyrobot.ui.common.filterObscuredTouches
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.RobotTheme
import java.util.concurrent.Executors

// MAIN PROCESS. The window that picks a widget's list (lists-plan.md §7.6, D12; LS6): the home-screen app opens it when you
// place a "Robot list" widget, and again from the widget's Reconfigure or its "Tap to pick a list". Exported with the
// APPWIDGET_CONFIGURE filter alone (the home-screen app must reach it), never over the lock screen, FLAG_SECURE (it shows
// your lists' names). The widget id it is given is untrusted: anything that isn't one of Robot's list widgets now closes
// it. Your tap saves only which list the widget shows (ids, widgets.json); nothing about a list changes here.

class ListsWidgetSetup : ComponentActivity() {
    private var widget = AppWidgetManager.INVALID_APPWIDGET_ID
    private var load by mutableStateOf<ListsLoad<ListsFile>?>(null)
    /** LS13: a phone lock is on, so the lists stay shut and the picker says what the widget says ("Robot is locked."). */
    private var robotLocked by mutableStateOf(false)
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.filterObscuredTouches()
        // Cancelled unless a list is picked: the home-screen app then takes a new widget off again.
        setResult(RESULT_CANCELED)
        widget = try { intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) } catch (_: Exception) { null }
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val app = applicationContext
        worker.execute {
            val ours = ListsWidgets.ours(app, widget)
            // LS12: from now on, this widget without a list asks for one plainly (it wasn't Robot's data being cleared).
            if (ours) ListsWidgets.placed(app)
            val lock = ours && PhoneLockNow.on(app)
            val l = if (!ours) null else if (ListsFiles.locked(app)) ListsLoad.Locked else PhoneListsPort(app).load()
            runOnUiThread {
                if (l == null) return@runOnUiThread finish()
                robotLocked = lock
                load = l
            }
        }
        setContent { RobotTheme { PickWindow(load, onPick = ::pick, onClose = { finish() }, robotLocked = robotLocked) } }
    }

    private fun pick(l: ListsList) {
        val app = applicationContext
        val w = widget
        worker.execute {
            val ok = !ListsFiles.locked(app) && ListsWidgets.bind(app, w, l.id)
            if (ok) ListsWidgets.redrawNow(app)
            runOnUiThread {
                if (ok) setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, w))
                finish()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

/** Your lists in the picker's order: Shopping first, then To-do, then the rest as they are kept. */
internal fun pickOrder(f: ListsFile): List<ListsList> =
    f.lists.sortedBy { when (it.role) { ListRole.SHOPPING -> 0; ListRole.TODO -> 1; null -> 2 } }

/**
 * The picker (a card over the home screen). Stateless: [load] is what it shows; [robotLocked], a phone lock is on (LS13:
 * then it says "Robot is locked." as the widget does, not "Unlock your phone", since the phone may be unlocked).
 */
@Composable
fun PickWindow(load: ListsLoad<ListsFile>?, onPick: (ListsList) -> Unit, onClose: () -> Unit, robotLocked: Boolean = false) {
    val langs = LocalLangs.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.lists_widget_pick_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                when (load) {
                    null -> Text(stringResource(R.string.lists_opening), style = MaterialTheme.typography.bodyLarge)
                    is ListsLoad.Loaded -> if (load.value.lists.isEmpty()) Text(stringResource(R.string.lists_none_yet), style = MaterialTheme.typography.bodyLarge)
                    else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(pickOrder(load.value), key = { it.id.hex }) { l ->
                            val n = l.open.size
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(l) }.padding(vertical = 12.dp)) {
                                Text(listLabel(l, langs), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pluralStringResource(R.plurals.lists_left, n, n), style = MaterialTheme.typography.bodyMedium, color = muted)
                                // LS11a (N2): someone else's list from a backup is marked, as on the Lists screens.
                                if (l.from == ListFrom.BACKUP) Text(stringResource(R.string.lists_from_backup), style = MaterialTheme.typography.labelMedium, color = muted)
                                if (l.from == ListFrom.PEER) Text(stringResource(R.string.lists_from_peer), style = MaterialTheme.typography.labelMedium, color = muted)
                            }
                        }
                    }
                    ListsLoad.Locked -> Text(stringResource(if (robotLocked) R.string.lists_widget_robot_locked else R.string.lists_unlock_to_see),
                        style = MaterialTheme.typography.bodyLarge)
                    else -> Text(stringResource(R.string.lists_not_now), style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.lists_cancel)) }
                }
            }
        }
    }
}
