// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.lock.CodeTries
import app.heyrobot.core.lock.LockCodeRules
import app.heyrobot.core.lock.LockState
import app.heyrobot.sys.AppPages
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZonedDateTime

// :LOCK PROCESS ONLY. The code pad: Robot's own digit pad, no keyboard, no autofill, kept out of
// screenshots and Recents' snapshot (FLAG_SECURE). The only place a code is checked: LockCodeVault is made here alone
// (ProcessIsolationTest). Started by the cover (End early, Change locks), a warning (Skip this time) and Settings › Phone
// lock (for its result: an edit session, a code set or removed). The waits after wrong tries live in state.json, and in
// this process too ([CodePad.Floor]) for tries state.json couldn't take. Nothing the pad asks of the engine or the
// Keystore can end :lock: a failure leaves the code, the key and the locks as they were and says so on the pad.

class LockCodeActivity : ComponentActivity() {
    private lateinit var vault: LockCodeVault
    private lateinit var pad: CodePad
    private val purpose get() = intent.getStringExtra(LockSignals.EXTRA_PURPOSE).orEmpty()

    /** The engine and state.json as the pad uses them; CodePad catches what they throw. */
    private val engine = object : CodePad.Engine {
        override fun state(): LockState = LockStore.readState(applicationContext)
        override fun tried(right: Boolean) { LockEngine.tried(applicationContext, right) }
        override fun codeSet(set: Boolean) = LockEngine.codeSet(applicationContext, set)
        override fun keyLost() = LockEngine.keyLost(applicationContext)
        override fun act() = right()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = LockCodeVault(applicationContext)
        pad = CodePad(vault, engine, FLOOR)
        // state.codeSet follows code.json first, so a set or remove whose state write failed is put right here.
        pad.reconcile()
        val session = LockStore.readState(this).editOpen(System.currentTimeMillis())
        when (purpose) {
            !in PURPOSES -> return finish()
            // Removing the code, or setting one over it, only inside an edit session (the code was just typed).
            LockSignals.PURPOSE_REMOVE -> {
                if ((!vault.isSet() || session) && pad.remove()) setResult(Activity.RESULT_OK)
                return finish()
            }
            LockSignals.PURPOSE_SET -> if (vault.isSet() && !session) return finish()
            // No code: nothing to check (Settings asks for one before With the code or Someone else).
            else -> if (!vault.isSet()) return finish()
        }
        setContent { RobotTheme { Pad() } }
    }

    /** The code was right: what [purpose] asked for, saved in state.json (it throws when that failed; CodePad says so). */
    private fun right() {
        val ctx = applicationContext
        when (purpose) {
            LockSignals.PURPOSE_END -> LockEngine.endWithCode(ctx)
            LockSignals.PURPOSE_SKIP -> intent.getStringExtra(LockSignals.EXTRA_LOCK)?.let { LockEngine.skip(ctx, it) }
            LockSignals.PURPOSE_EDIT, LockSignals.PURPOSE_CHANGE -> LockEngine.openEdit(ctx)
        }
    }

    /** Done: Change locks opens Settings › Phone lock, and the caller gets its result. */
    private fun done() {
        if (purpose == LockSignals.PURPOSE_CHANGE) {
            try {
                startActivity(Intent().setClassName(packageName, MAIN).putExtra(AppPages.EXTRA, AppPages.PHONE_LOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    @Composable
    private fun Pad() {
        var typed by remember { mutableStateOf("") }
        var first by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var lost by remember { mutableStateOf(false) }
        val setting = purpose == LockSignals.PURPOSE_SET
        val wrongText = stringResource(R.string.lock_code_wrong)
        val mismatch = stringResource(R.string.lock_code_mismatch)
        val short = stringResource(R.string.lock_code_short)
        val failed = stringResource(R.string.lock_code_failed)
        val notNow = stringResource(R.string.lock_code_not_now)
        val notSaved = stringResource(R.string.lock_code_not_saved)
        // Read again after each try and when a wait ends, so the keys come back on by themselves.
        var stamp by remember { mutableStateOf(0) }
        val tries = remember(stamp, message) { pad.tries() }
        val now = System.currentTimeMillis()
        val waitUntil = LockCodeRules.nextTry(tries, now).takeIf { !setting && it > now }
        LaunchedEffect(waitUntil) { if (waitUntil != null) { delay((waitUntil - System.currentTimeMillis()).coerceAtLeast(0) + 250); stamp++ } }

        fun submit() {
            val code = typed
            typed = ""
            if (!LockCodeRules.valid(code)) { message = short; return }
            if (setting) {
                val f = first
                when {
                    f == null -> { first = code; message = null }
                    f != code -> { first = null; message = mismatch }
                    pad.set(code) -> { setResult(Activity.RESULT_OK); finish() }
                    else -> { first = null; message = failed }
                }
                return
            }
            when (pad.check(code, System.currentTimeMillis())) {
                CodePad.Done.RIGHT -> done()
                CodePad.Done.WRONG -> { message = wrongText; stamp++ }
                CodePad.Done.WAIT -> stamp++
                CodePad.Done.NOT_NOW -> { message = notNow; stamp++ }
                CodePad.Done.NOT_SAVED -> { message = notSaved; stamp++ }
                CodePad.Done.LOST -> lost = true
                CodePad.Done.NO_CODE -> finish()
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lost) {
                    Text(stringResource(R.string.lock_code_lost), style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = { finish() }) { Text(stringResource(R.string.lock_code_ok)) }
                    return@Column
                }
                Text(stringResource(when {
                    setting && first != null -> R.string.lock_code_again
                    setting -> R.string.lock_code_new
                    else -> R.string.lock_code_enter
                }), style = MaterialTheme.typography.headlineSmall)
                if (setting && first == null) Text(stringResource(R.string.lock_code_new_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val dots = "•".repeat(typed.length) // l10n: not text (the typed digits, hidden)
                Text(dots, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { contentDescription = getString(R.string.lock_code_typed, typed.length) })
                when {
                    waitUntil != null -> Text(LockStrings.codeWait("time" to Instant.ofEpochMilli(waitUntil).atZone(ZonedDateTime.now().zone).toLocalTime()).str(),
                        color = MaterialTheme.colorScheme.error)
                    message != null -> Text(message!!, color = MaterialTheme.colorScheme.error)
                }
                val enabled = waitUntil == null
                for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (d in row) Key(d.toString(), enabled && typed.length < LockCodeRules.MAX_DIGITS) { typed += d.toString() }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { typed = typed.dropLast(1) }, enabled = typed.isNotEmpty(), modifier = Modifier.size(80.dp, 64.dp)) {
                        Icon(RIcons.Back, contentDescription = stringResource(R.string.lock_code_delete))
                    }
                    Key(0.toString(), enabled && typed.length < LockCodeRules.MAX_DIGITS) { typed += "0" } // l10n: not text (a digit)
                    FilledTonalButton(onClick = ::submit, enabled = enabled && typed.isNotEmpty(), modifier = Modifier.size(80.dp, 64.dp)) {
                        Text(stringResource(R.string.lock_code_ok))
                    }
                }
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.lock_cancel)) }
            }
        }
    }

    @Composable
    private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(80.dp, 64.dp)) { Text(label, style = MaterialTheme.typography.titleLarge) }
    }

    private companion object {
        /** Wrong tries this process counted (:lock lives as long as the service), for every pad it opens. */
        val FLOOR = CodePad.Floor()
        const val MAIN = "app.heyrobot.ui.MainActivity"
        val PURPOSES = setOf(LockSignals.PURPOSE_EDIT, LockSignals.PURPOSE_CHANGE, LockSignals.PURPOSE_END, LockSignals.PURPOSE_SKIP,
            LockSignals.PURPOSE_SET, LockSignals.PURPOSE_REMOVE, LockSignals.PURPOSE_RESTORE)
    }
}

/**
 * What the pad does with a code, apart from its window, so the JVM tests run every failure path. Nothing here throws. A
 * failure that may pass ([Done.NOT_NOW], [Done.NOT_SAVED]) leaves the code, its key and the locks as they were: only a
 * key gone for good turns off locks for someone else (§7.1), and only once the engine has noted it.
 */
internal class CodePad(private val vault: LockCodeVault, private val engine: Engine, private val floor: Floor) {
    /** LockEngine and state.json, as the pad uses them. Each call may throw (storage full, the file lock). */
    interface Engine {
        fun state(): LockState
        fun tried(right: Boolean)
        fun codeSet(set: Boolean)
        fun keyLost()
        /** What the code was asked for (end the locks, skip one, an edit session), saved. */
        fun act()
    }

    /**
     * The wrong tries this process counted. state.json may not take them (storage full), and an unsaved try must still
     * bring the waits, or a full phone would give endless tries at the code.
     */
    class Floor {
        @Volatile var tries: CodeTries = LockCodeRules.RIGHT
    }

    enum class Done {
        /** Right, and what it was for is saved. */
        RIGHT,
        WRONG,
        /** A wait after wrong tries isn't over: nothing was checked. */
        WAIT,
        /** The code couldn't be checked just now: nothing changed, and it isn't a wrong try. */
        NOT_NOW,
        /** Right (or the key lost), but state.json couldn't take what follows: nothing changed. */
        NOT_SAVED,
        /** The key is gone for good: locks for someone else are off, the code removed. */
        LOST,
        NO_CODE,
    }

    /** The tries that decide the wait: state.json's, or this process's when it counted more. */
    fun tries(): CodeTries {
        val saved = try { engine.state().code } catch (t: Throwable) { RLog.w("lock_pad_state", t); LockCodeRules.RIGHT }
        val counted = floor.tries
        return if (counted.fails > saved.fails) counted else saved
    }

    /** state.codeSet as code.json says (a code set or removed while state.json couldn't take it). */
    fun reconcile() {
        try {
            val set = vault.isSet()
            if (engine.state().codeSet != set) engine.codeSet(set)
        } catch (t: Throwable) {
            RLog.w("lock_pad_reconcile", t)
        }
    }

    /** A typed code at [now], checked for what the pad was opened for. */
    fun check(typed: String, now: Long): Done {
        val before = tries()
        if (!LockCodeRules.mayTry(before, now)) return Done.WAIT
        return when (vault.check(typed)) {
            LockCodeVault.Check.Right -> {
                floor.tries = LockCodeRules.RIGHT
                try { engine.tried(right = true) } catch (t: Throwable) { RLog.w("lock_pad_tried", t) }
                try {
                    engine.act()
                    Done.RIGHT
                } catch (t: Throwable) {
                    RLog.w("lock_pad_act", t)
                    Done.NOT_SAVED
                }
            }
            LockCodeVault.Check.Wrong -> {
                floor.tries = LockCodeRules.wrong(before, now)
                try { engine.tried(right = false) } catch (t: Throwable) { RLog.w("lock_pad_tried", t) }
                Done.WRONG
            }
            LockCodeVault.Check.NotNow -> Done.NOT_NOW
            // The engine notes it (locks for someone else off, codeSet cleared) before the code goes: should that fail,
            // the code, the key and the locks stay, and the next try does it again.
            LockCodeVault.Check.KeyLost -> try {
                engine.keyLost()
                vault.remove()
                Done.LOST
            } catch (t: Throwable) {
                RLog.w("lock_pad_lost", t)
                Done.NOT_SAVED
            }
            LockCodeVault.Check.NoCode -> Done.NO_CODE
        }
    }

    /**
     * A new code, typed twice. False when it couldn't be saved. A code that state.codeSet doesn't show isn't kept: Settings
     * would show no code and never ask for it.
     */
    fun set(typed: String): Boolean {
        if (!vault.set(typed)) return false
        try { engine.codeSet(true) } catch (t: Throwable) { RLog.w("lock_pad_set", t) }
        val shown = try { engine.state().codeSet } catch (t: Throwable) { RLog.w("lock_pad_state", t); false }
        if (!shown) {
            vault.remove()
            return false
        }
        // A new code starts the count over here too, as codeSet does in state.json.
        floor.tries = LockCodeRules.RIGHT
        return true
    }

    /** The code removed. False when code.json couldn't be deleted; a failed state write is put right by [reconcile]. */
    fun remove(): Boolean {
        if (!vault.remove()) return false
        floor.tries = LockCodeRules.RIGHT
        try { engine.codeSet(false) } catch (t: Throwable) { RLog.w("lock_pad_remove", t) }
        return true
    }
}
