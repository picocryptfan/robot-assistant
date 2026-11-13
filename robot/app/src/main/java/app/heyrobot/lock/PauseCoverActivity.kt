// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.pause.AppToday
import app.heyrobot.core.pause.PauseCount
import app.heyrobot.core.pause.PauseWait
import app.heyrobot.core.lock.LockGate
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

// :LOCK PROCESS ONLY. App pause's cover (docs/plans/app-pause-plan.md): shown by :lock's accessibility service when an app
// you picked comes to the front from another app. It says how often you opened it today and for how long (Android's
// usage history, read now and never kept; nothing without Usage access), and Open works after the wait. It never traps:
// Open always works once the wait is over, Don't pause turns the pause off for that app and opens it at once, and Back
// or Go back goes Home. Its own task, out of Recents, never over the lock screen; a lock covers it (LockGate.ROBOT_COVERED),
// since it opens the paused app.
class PauseCoverActivity : ComponentActivity() {
    private var app by mutableStateOf("")
    private var label by mutableStateOf("")
    private var today by mutableStateOf<AppToday?>(null)
    private var shownAt = 0L
    private var now by mutableLongStateOf(0L)
    private var wait = AppPauseWatchWait

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        shownAt = savedInstanceState?.getLong(SHOWN_AT)?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        if (!take(intent)) return
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } } }
        setContent { RobotTheme { Cover() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Another picked app came up: its own wait, from now.
        shownAt = SystemClock.elapsedRealtime()
        today = null
        take(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(SHOWN_AT, shownAt)
    }

    /** The app to pause; none, or one that may never be paused: nothing to stand in front of. */
    private fun take(i: Intent?): Boolean {
        val pkg = i?.getStringExtra(AppPauseWatch.EXTRA_APP)
        if (pkg == null || pkg == packageName || pkg in LockGate.SYSTEM) { finish(); return false }
        app = pkg
        label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        val ctx = applicationContext
        wait = AppPauseWatch.settings(ctx).waitSeconds
        Thread({
            try {
                val t = System.currentTimeMillis()
                val day = PauseCount.dayStart(t, ZoneId.systemDefault())
                val c = UsageReader.events(ctx, day - PauseCount.LOOK_BACK_MS, t)
                    ?.let { PauseCount.app(it, pkg, day, t, ignore = LockGate.SYSTEM + packageName, openingNow = true) }
                runOnUiThread { if (app == pkg) today = c }
            } catch (t: Throwable) {
                RLog.w("pause_count", t)
            }
        }, "robot-pause").start()
        return true
    }

    private fun open(turnOff: Boolean) {
        val pkg = app
        if (turnOff) Thread({ AppPauseWatch.save(applicationContext, AppPauseWatch.settings(applicationContext).without(pkg)) }, "robot-pause").start()
        else if (!PauseWait.canOpen(shownAt, SystemClock.elapsedRealtime(), wait)) return
        AppPauseWatch.grant(pkg)
        try { packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity) } catch (t: Throwable) { RLog.w("pause_open", t) }
        finish()
    }

    private fun goBack() {
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { RLog.w("pause_home", t) }
        finish()
    }

    @Composable
    private fun Cover() {
        val left = PauseWait.remainingMs(shownAt, now.coerceAtLeast(shownAt), wait)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.lock_pause_title, label), style = MaterialTheme.typography.headlineSmall)
                today?.let { t ->
                    Text(stringResource(R.string.lock_pause_count, pluralStringResource(R.plurals.lock_pause_opens, t.opens, t.opens),
                        pluralStringResource(R.plurals.lock_pause_minutes, t.minutes, t.minutes)), style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.lock_pause_ask), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = ::goBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_pause_back)) }
                OutlinedButton(onClick = { open(turnOff = false) }, enabled = left == 0L, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (left == 0L) stringResource(R.string.lock_pause_open, label) else stringResource(R.string.lock_pause_open_in, ((left + 999) / 1000).toInt()))
                }
                TextButton(onClick = { open(turnOff = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_pause_dont, label)) }
            }
        }
    }

    private companion object {
        const val SHOWN_AT = "shown_at"
        val AppPauseWatchWait = app.heyrobot.core.pause.PauseSettings.DEFAULT_WAIT
    }
}
