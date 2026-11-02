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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.lock.EarlyView
import app.heyrobot.core.lock.EndEarlyRules
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

// :LOCK PROCESS ONLY. The cover: an ordinary full-screen window the accessibility service brings to the
// front over a window that isn't allowed, so the lock screen, the shade, Robot's panel, the keyboard, calls and Clock's
// alarm stay above it. Its own task, out of Recents, never shown over the lock screen; Back does nothing, Home goes to
// the launcher, which is covered too. Plain and left-aligned, no motion: the lock's name, when the phone opens, the
// buttons for what is allowed, and End early as Settings › Phone lock says.

class LockCoverActivity : ComponentActivity() {
    /**
     * What the cover shows, as Compose state passed to [Cover], which reads both: a change draws it again. (A tick Cover
     * never read let Compose skip it, so a cover that stayed up kept its first minutes left and apps.)
     */
    private var shown by mutableStateOf(LockEngine.snapshot)
    private var now by mutableLongStateOf(wallClock())

    /** The engine changed: the cover follows, and goes once no lock is on. */
    private val watcher: () -> Unit = { runOnUiThread(::follow) }

    private var openedAt = -OWN_WINDOW_MS

    /** The engine's last snapshot (memory only, no disk) and the time now. */
    private fun follow() {
        val s = LockEngine.snapshot
        if (s?.active == false) return finish()
        shown = s
        now = wallClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        LockEngine.watch(watcher)
        // The minutes left, while the cover is resumed only: at each change of the number, once a minute at most apart.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { follow(); delay(nextTick(shown, now)) } } }
        setContent {
            RobotTheme { Cover(shown, now) }
        }
    }

    override fun onResume() {
        super.onResume()
        showing = this
        LockAccessibilityService.instance?.coverShown()
        Thread({ LockEngine.current(applicationContext).let { if (!it.active) runOnUiThread { finish() } else watcher() } }, "robot-lock").start()
    }

    /** F13: a window the cover opens itself, as opposed to one that took the front from it. */
    override fun startActivity(intent: Intent?) {
        openedAt = SystemClock.uptimeMillis()
        super.startActivity(intent)
    }

    override fun onStop() {
        LockAccessibilityService.instance?.coverStopped(own = SystemClock.uptimeMillis() - openedAt < OWN_WINDOW_MS)
        super.onStop()
    }

    override fun onDestroy() {
        if (showing === this) showing = null
        LockEngine.unwatch(watcher)
        super.onDestroy()
    }

    private fun background(block: () -> Unit) = Thread({ try { block() } catch (t: Throwable) { RLog.w("lock_cover_act", t) } }, "robot-lock").start()

    private fun emergency() {
        val opened = LockSignals.emergencyDialer(this)?.let { i ->
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (t: Throwable) { RLog.w("lock_sos", t); false }
        } == true
        background { LockEngine.emergencyOpened(applicationContext, dialer = !opened) }
        if (opened) return
        // No emergency dialer, or it wouldn't open: the dialer, open for 3 minutes (§6).
        LockEngine.dialerOpenUntil = System.currentTimeMillis() + 3 * 60_000L
        try { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { RLog.w("lock_dial", t) }
    }

    private fun open(pkg: String) {
        try { packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity) } catch (t: Throwable) { RLog.w("lock_open", t) }
    }

    @Composable
    private fun Cover(s: LockEngine.Snapshot?, now: Long) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s == null || !s.active) {
                    Text(stringResource(R.string.lock_cover_open), style = MaterialTheme.typography.headlineSmall)
                    return@Column
                }
                val names = s.state.active.map { a -> if (a.voice) LockStrings.byVoice().str() else s.file?.lock(a.lock)?.name.orEmpty() }.filter { it.isNotEmpty() }.distinct()
                Text(names.joinToString(" · "), style = MaterialTheme.typography.headlineMedium) // l10n: not text (joins your locks' names)
                s.opensAt?.let { opens ->
                    Text(LockStrings.coverOpens("time" to opens.toLocalTime()).str(), style = MaterialTheme.typography.titleLarge)
                    if (s.file?.settings?.showLeft != false) {
                        val left = ((opens.toInstant().toEpochMilli() - now + 59_999) / 60_000).coerceAtLeast(0)
                        Text(LockStrings.coverLeft(left).str(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Phase B: One app first, "8 of 20 minutes in Libby done" (its app is named: the button below opens it).
                for ((a, r) in LockSchedule.appLocks(s.file, s.state)) {
                    val label = LockNotices.label(this@LockCoverActivity, r.app)
                    Text(LockStrings.coverProgress("done" to a.usedMs / 60_000, "total" to r.minutes, "label" to label).str(),
                        style = MaterialTheme.typography.titleLarge)
                    // Its count stands still while the cover is up: time counts only with the app in front.
                    Text(LockStrings.coverCounts("label" to label).str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val settings = s.settings
                if (settings?.forWho == LockFor.SOMEONE_ELSE) Text(stringResource(R.string.lock_cover_someone), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                for (p in LockSchedule.allowedPackages(s.file, s.state, s.state.mapsApp).sorted()) {
                    val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Throwable) { null } ?: continue
                    OutlinedButton(onClick = { open(p) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(LockStrings.coverOpenApp("label" to label).str()) }
                }
                OutlinedButton(onClick = { try { showAssist(Bundle()) } catch (_: Throwable) {} }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.lock_cover_ask))
                }
                Button(onClick = ::emergency, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_cover_emergency)) }
                Spacer(Modifier.height(8.dp))
                if (settings != null) when (val e = EndEarlyRules.view(settings, s.state.active, now)) {
                    EarlyView.Off -> Text(stringResource(R.string.lock_cover_no_early), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EarlyView.CanStartWait -> OutlinedButton(onClick = { background { LockEngine.startWait(applicationContext) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.lock_cover_end))
                    }
                    is EarlyView.Waiting -> Text(LockStrings.coverWaitUntil("time" to clock(e.until)).str(), style = MaterialTheme.typography.bodyMedium)
                    is EarlyView.Ready -> {
                        Text(LockStrings.coverEndBy("time" to clock(e.until)).str(), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { background { if (LockEngine.endAfterWait(applicationContext)) runOnUiThread { finish() } } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.lock_cover_end_now))
                        }
                    }
                    EarlyView.Code -> {
                        OutlinedButton(onClick = { startActivity(LockSignals.code(this@LockCoverActivity, LockSignals.PURPOSE_END)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.lock_cover_end))
                        }
                        OutlinedButton(onClick = { startActivity(LockSignals.code(this@LockCoverActivity, LockSignals.PURPOSE_CHANGE)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.lock_cover_change))
                        }
                    }
                }
            }
        }
    }

    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZonedDateTime.now().zone).toLocalTime()

    companion object {
        private const val OWN_WINDOW_MS = 5_000L
        /** The cover on the screen, for the service to take off a call's screen (main thread). */
        @Volatile internal var showing: LockCoverActivity? = null
            private set
        /** The time now, wall-clock ms (a test moves it on). */
        internal var wallClock: () -> Long = System::currentTimeMillis

        /**
         * How long until what the cover shows changes with time alone: the minutes left (rounded up, so the number drops as
         * a whole minute passes) or the end of End early's wait or window. 0.25 to 60 s.
         */
        internal fun nextTick(s: LockEngine.Snapshot?, now: Long): Long {
            var wait = 60_000L
            s?.opensAt?.toInstant()?.toEpochMilli()?.let { opens -> if (opens > now) wait = Math.floorMod(opens - now - 1, 60_000L) + 1 }
            s?.settings?.let { settings ->
                val until = when (val e = EndEarlyRules.view(settings, s.state.active, now)) {
                    is EarlyView.Waiting -> e.until
                    is EarlyView.Ready -> e.until
                    else -> null
                }
                if (until != null && until > now) wait = minOf(wait, until - now)
            }
            return wait.coerceIn(250L, 60_000L)
        }
    }
}
