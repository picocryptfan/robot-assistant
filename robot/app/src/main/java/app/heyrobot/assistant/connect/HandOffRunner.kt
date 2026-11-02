// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Executed
import app.heyrobot.core.HandOffStepAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.connect.AppPinState
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnStep
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HandOffContext
import app.heyrobot.core.connect.HandOffIntent
import app.heyrobot.core.connect.HandOffStep
import app.heyrobot.core.connect.IntentSpecs
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.SignerNow
import app.heyrobot.people.AndroidSigners
import app.heyrobot.sys.ConnFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS. A skill's hand-off step in the panel (docs/plans/skills-connections-plan.md §3.5, D17; C7): ConnRunner
// hands a HandOffStepAction here, inside TurnRunner, after the message quarantine and the gate and before the card
// (prepare), then after the card and the lock check (run). Every rule is core HandOffStep's and IntentSpecs'; this reads
// what Android says of the app you picked (its signers, and which of its activities take the intent) and opens it.
//
// A launch, in order: the hand-off as it is now, still the one its card showed (the app, its signer and what it asks);
// the app installed and signed by the key you pinned, checked again just before the launch (an app replaced since the card
// is never opened); its values and its intent built again; the intent's activities inside the pinned package, exported
// only: none, and nothing opens ("Signal can't open this."); exactly one, and the intent names it; then the panel's own
// launch, from its visible window. The intent is [intent]'s and nothing else: the package always, an action from the short
// list, the data, text/plain for SEND, extras that are strings, whole numbers or switches, and FLAG_ACTIVITY_NEW_TASK.
// Never a URI grant, ClipData, a selector, a Parcelable, a Bundle or another Intent (handOffsHandOverDataOnly pins it).
// The voice log keeps the action, how it ended and whether one activity was named: never the app, an address or a value.

/** What Android says of a package (the phone's PackageManager; tests give their own). */
interface HandOffSide {
    /** [pkg]'s signers now; null when it isn't installed. */
    fun now(pkg: String): SignerNow?

    /** Android says [signer] is still in [pkg]'s signing lineage (a key rotated with Android's own scheme). */
    fun stillSigns(pkg: String, signer: String): Boolean

    /** The exported activities of [i]'s package that take [i]. */
    fun takers(i: Intent): List<ComponentName>
}

/** The phone's PackageManager, through Messages' signer reader (AndroidSigners). */
class AndroidHandOffSide(private val ctx: Context) : HandOffSide {
    private val signers = AndroidSigners(ctx)

    override fun now(pkg: String): SignerNow? = signers.now(pkg)

    override fun stillSigns(pkg: String, signer: String): Boolean = signers.stillSigns(pkg, AppPin(setOf(signer)))

    override fun takers(i: Intent): List<ComponentName> = try {
        ctx.packageManager.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0L))
            .mapNotNull { r -> r.activityInfo?.takeIf { it.exported && it.packageName == i.`package` }?.let { ComponentName(it.packageName, it.name) } }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}

object HandOffRunner {
    /** Tests only: what Android says of the app (the app always asks the phone's PackageManager). */
    @Volatile internal var side: (Context) -> HandOffSide = { AndroidHandOffSide(it.applicationContext) }

    /** [a] prepared for the gate and its card, or refused in Robot's words. Background thread. */
    fun prepare(ctx: Context, a: HandOffStepAction): Prepared {
        val c = ConnFiles.store(ctx).get(a.call.conn)?.takeIf { it.kind == ConnKind.HAND_OFF }
        return HandOffStep.prepare(a, HandOffContext(c, c?.let { state(side(ctx), it) } ?: AppPinState.MISSING))
    }

    /** The app [c] names, as Android shows it now: installed, and signed by the key you pinned. */
    private fun state(s: HandOffSide, c: Connection): AppPinState {
        val h = c.handOff ?: return AppPinState.MISSING
        // C10: not pinned here (from a backup that isn't yours): nothing to check against, so never opened.
        val pinned = h.signer ?: return AppPinState.UNPINNED
        val now = s.now(h.pkg)
        return HandOffStep.state(pinned, now, now != null && s.stillSigns(h.pkg, pinned))
    }

    /** Opens [a]'s app after its card, through the panel's [launch]: see the file's header. */
    suspend fun run(ctx: Context, a: HandOffStepAction, launch: (Intent) -> Boolean): Executed = withContext(Dispatchers.IO) { open(ctx, a, launch) }

    private fun open(ctx: Context, a: HandOffStepAction, launch: (Intent) -> Boolean): Executed {
        val p = a.prepared ?: return no(ConnFailure.GONE, "", null)
        val c = ConnFiles.store(ctx).get(a.call.conn)?.takeIf { it.kind == ConnKind.HAND_OFF } ?: return no(ConnFailure.GONE, p.name, null)
        // Still the app, the signer and the intent its card showed.
        if (HandOffStep.origin(c) != p.origin) return no(ConnFailure.CHANGED, c.name, null)
        val app = c.handOff ?: return no(ConnFailure.GONE, c.name, null)
        val s = side(ctx)
        // The signer again, just before the launch: an app replaced since the card is never opened.
        when (state(s, c)) {
            AppPinState.MISSING -> return no(ConnFailure.APP_MISSING, c.name, c)
            AppPinState.REPLACED -> return no(ConnFailure.APP_REPLACED, c.name, c)
            AppPinState.UNPINNED -> return no(ConnFailure.APP_UNPINNED, c.name, c)
            AppPinState.TRUSTED -> {}
        }
        val values = when (val v = ConnStep.values(app.intent.params, a.call.args)) {
            is ConnStep.Values.Ok -> v.values
            is ConnStep.Values.Refused -> return no(v.why, c.name, c)
        }
        val h = IntentSpecs.build(app, values) ?: return no(ConnFailure.BAD_VALUE, c.name, c)
        val i = intent(h)
        val takers = s.takers(i)
        if (takers.isEmpty()) return no(ConnFailure.APP_CANT_OPEN, c.name, c)
        if (takers.size == 1) i.component = takers.single()
        val launched = launch(i)
        VoiceLog.add(VoiceEvent.HAND_OFF, HandOffStep.logAction(app.intent.action), if (launched) 0 else ConnFailure.APP_CANT_OPEN.code, if (takers.size == 1) 1 else 0)
        return HandOffStep.opened(c, launched)
    }

    /**
     * [h] as Android's Intent: its package always, its action, its data and type, its extras each as a String, a Long or
     * a Boolean, and FLAG_ACTIVITY_NEW_TASK (the panel isn't an activity). Nothing else, ever.
     */
    internal fun intent(h: HandOffIntent): Intent {
        val i = Intent(h.action)
        if (h.launcher) i.addCategory(Intent.CATEGORY_LAUNCHER)
        val data = h.data?.let(Uri::parse)
        when {
            data != null && h.mime != null -> i.setDataAndType(data, h.mime)
            data != null -> i.setData(data)
            h.mime != null -> i.setType(h.mime)
        }
        i.setPackage(h.pkg)
        for ((name, v) in h.extras) when (v) {
            is String -> i.putExtra(name, v)
            is Long -> i.putExtra(name, v)
            is Boolean -> i.putExtra(name, v)
        }
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    private fun no(why: ConnFailure, name: String, c: Connection?): Executed {
        c?.handOff?.let { VoiceLog.add(VoiceEvent.HAND_OFF, HandOffStep.logAction(it.intent.action), why.code, 0) }
        return Executed(ActionStatus.FAILED, why.text(name))
    }
}
