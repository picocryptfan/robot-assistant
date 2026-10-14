package app.heyrobot.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.heyrobot.core.RLog
import app.heyrobot.core.termux.TermuxExtra
import app.heyrobot.core.termux.TermuxFailure
import app.heyrobot.core.termux.TermuxIntents
import app.heyrobot.core.termux.TermuxResult
import app.heyrobot.core.termux.TermuxRun
import app.heyrobot.core.termux.TermuxRunPlan

// VOICE PROCESS. The Android side of a Termux run: the explicit RUN_COMMAND
// intent to com.termux/.app.RunCommandService, sent with startForegroundService as Termux's own plugins do, and the one-shot
// mutable PendingIntent (explicit, to this receiver, which isn't exported and runs in :voice) that Termux fills with the
// result bundle. The label Termux logs is always "Robot".

/** Termux's result for one run: handed to the runner, which drops it when nobody waits for that id. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = TermuxRun.execId(i.data?.toString())
        if (id == null) {
            RLog.w("termux_result_unknown")
            return
        }
        TermuxTool.runner(ctx).deliver(id, TermuxSend.result(i.getBundleExtra(TermuxIntents.RESULT_BUNDLE)))
    }
}

/** Sends a [TermuxRunPlan] to Termux. */
class TermuxSend(private val ctx: Context) : TermuxTransport {
    override fun send(plan: TermuxRunPlan): TermuxFailure? = try {
        if (ctx.startForegroundService(intent(ctx, plan)) == null) TermuxFailure.NOT_INSTALLED else null
    } catch (e: SecurityException) {
        RLog.w("termux_send", e)
        TermuxFailure.NO_PERMISSION
    } catch (e: Exception) {
        // A background start Android refused (ForegroundServiceStartNotAllowedException), or anything else.
        RLog.w("termux_send", e)
        TermuxFailure.INTERNAL
    }

    companion object {
        /** The RUN_COMMAND intent, its extras exactly as [TermuxRunPlan.extras] lists them. */
        fun intent(ctx: Context, plan: TermuxRunPlan): Intent {
            val i = Intent(TermuxIntents.ACTION_RUN_COMMAND).setClassName(TermuxIntents.PACKAGE, TermuxIntents.RUN_COMMAND_SERVICE)
            for ((k, v) in plan.extras()) when (v) {
                is TermuxExtra.Text -> i.putExtra(k, v.value)
                is TermuxExtra.Texts -> i.putExtra(k, v.values.toTypedArray())
                is TermuxExtra.Flag -> i.putExtra(k, v.value)
                is TermuxExtra.ResultIntent -> i.putExtra(k, resultIntent(ctx, v.execId))
            }
            return i
        }

        /** One-shot, mutable (Termux fills the result in), explicit to Robot's own non-exported receiver, unique per run. */
        fun resultIntent(ctx: Context, execId: Int): PendingIntent = PendingIntent.getBroadcast(ctx, execId,
            Intent(ctx, TermuxResultReceiver::class.java).setData(Uri.parse(TermuxRun.resultUri(execId))),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)

        /** The result bundle as core reads it; a missing bundle is an internal error (no exit code). */
        fun result(b: Bundle?): TermuxResult {
            if (b == null) return TermuxResult(null, null, null, err = 1)
            fun text(k: String): String? = try { b.getString(k) } catch (_: Exception) { null }
            fun anyText(k: String): String? = try { @Suppress("DEPRECATION") b.get(k)?.toString() } catch (_: Exception) { null }
            return TermuxResult(
                stdout = text(TermuxIntents.RESULT_STDOUT),
                stderr = text(TermuxIntents.RESULT_STDERR),
                exitCode = if (b.containsKey(TermuxIntents.RESULT_EXIT_CODE)) b.getInt(TermuxIntents.RESULT_EXIT_CODE) else null,
                err = b.getInt(TermuxIntents.RESULT_ERR, TermuxIntents.ERR_NONE),
                errmsg = text(TermuxIntents.RESULT_ERRMSG),
                stdoutOriginalLength = anyText(TermuxIntents.RESULT_STDOUT_ORIGINAL_LENGTH),
                stderrOriginalLength = anyText(TermuxIntents.RESULT_STDERR_ORIGINAL_LENGTH),
            )
        }
    }
}
