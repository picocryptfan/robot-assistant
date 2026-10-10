package app.heyrobot.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AppEntry
import app.heyrobot.core.AppMatcher
import app.heyrobot.core.Durations
import app.heyrobot.core.RLog
import java.util.Locale

// VOICE PROCESS (the app side, never :brain). Runs an action that already passed the approval gate,
// with intents that need no new permissions: AlarmClock (GrapheneOS Clock handles SET_TIMER with
// EXTRA_SKIP_UI silently, verified in phase 0), the launcher's app list, Organic Maps' om:// URIs (geo:
// as a fallback) and CameraManager's torch. The log records the tool and the outcome, never arguments.

class ToolExecutor(private val ctx: Context, private val launch: (Intent) -> Boolean) {
    data class Result(val status: ActionStatus, val detail: String, val launchedApp: Boolean = false)

    fun run(action: Action): Result {
        val r = try {
            when (action) {
                is Action.SetTimer -> timer(action.seconds)
                is Action.SetAlarm -> alarm(action.hour, action.minute)
                is Action.OpenApp -> openApp(action.name)
                is Action.MapsSearch -> maps(action.query, "Opened Organic Maps search")
                is Action.MapsNavigate -> maps(action.destination, "Opened Organic Maps on the destination: tap Route for directions")
                is Action.Flashlight -> torch(action.on)
            }
        } catch (t: Throwable) {
            RLog.w("tool_run", t, "tool" to action.tool)
            Result(ActionStatus.FAILED, "Something went wrong.")
        }
        RLog.i("tool_run", "tool" to action.tool, "status" to r.status)
        return r
    }

    private fun start(i: Intent): Boolean {
        if (i.resolveActivity(ctx.packageManager) == null) return false
        return try { launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    private fun timer(seconds: Long): Result {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Result(ActionStatus.DONE, "Timer set for ${Durations.spoken(seconds)}.")
        else Result(ActionStatus.FAILED, "No clock app on this phone takes timers.")
    }

    private fun alarm(hour: Int, minute: Int): Result {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Result(ActionStatus.DONE, String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute))
        else Result(ActionStatus.FAILED, "No clock app on this phone takes alarms.")
    }

    private fun launcherApps(): List<AppEntry> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, PackageManager.MATCH_ALL).map { ri ->
            AppEntry(ri.loadLabel(pm).toString(), "${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
    }

    private fun openApp(name: String): Result {
        val match = AppMatcher.best(name, launcherApps())
            ?: return Result(ActionStatus.FAILED, "No app called “$name” is installed.")
        val (pkg, cls) = match.key.split('/', limit = 2)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return if (start(i)) Result(ActionStatus.DONE, "Opened ${match.label}.", launchedApp = true)
        else Result(ActionStatus.FAILED, "${match.label} didn't open.")
    }

    private fun maps(query: String, doneText: String): Result {
        val q = Uri.encode(query)
        for (uri in listOf("om://search?query=$q", "geo:0,0?q=$q")) {
            if (start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) return Result(ActionStatus.DONE, "$doneText.", launchedApp = true)
        }
        return Result(ActionStatus.FAILED, "Organic Maps isn't installed.")
    }

    private fun torch(on: Boolean): Result {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Result(ActionStatus.FAILED, "This phone has no flashlight.")
        cm.setTorchMode(id, on)
        return Result(ActionStatus.DONE, if (on) "Flashlight on." else "Flashlight off.")
    }
}
