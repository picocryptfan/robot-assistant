package app.heyrobot.assistant

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import androidx.core.graphics.drawable.toBitmap
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AppEntry
import app.heyrobot.core.AppMatcher
import app.heyrobot.core.Durations
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import java.util.Locale

// VOICE PROCESS (the app side, never :brain). Runs an action that already passed the approval gate,
// with intents that need no new permissions: AlarmClock (GrapheneOS Clock handles SET_TIMER with
// EXTRA_SKIP_UI silently, verified in phase 0), the launcher's app list, Organic Maps' om:// URIs (geo:
// as a fallback) and CameraManager's torch. The log records the tool and the outcome, never arguments.

class ToolExecutor(private val ctx: Context, private val launch: (Intent) -> Boolean) {
    /**
     * Before the gate and the card: open_app becomes the installed app it will open (its real label and launcher
     * activity), so the card shows what will actually open; a name that matches no app well enough is refused here.
     */
    fun prepare(action: Action): Prepared = when (action) {
        is Action.OpenApp -> {
            val m = AppMatcher.match(action.name, launcherApps())
            if (m == null) Prepared.Refused(ActionStatus.FAILED, "No app called “${action.name}” is installed.")
            else Prepared.Ready(Action.OpenApp(m.app.label, m.app.key), mustAsk = m.loose)
        }
        else -> Prepared.Ready(action)
    }

    fun run(action: Action): Executed {
        val r = try {
            when (action) {
                is Action.SetTimer -> timer(action.seconds)
                is Action.SetAlarm -> alarm(action.hour, action.minute)
                is Action.OpenApp -> openApp(action)
                is Action.MapsSearch -> maps(action.query, "Opened Organic Maps search")
                is Action.MapsNavigate -> maps(action.destination, "Opened Organic Maps on the destination: tap Route for directions")
                is Action.Flashlight -> torch(action.on)
            }
        } catch (t: Throwable) {
            RLog.w("tool_run", t, "tool" to action.tool)
            Executed(ActionStatus.FAILED, "Something went wrong.")
        }
        RLog.i("tool_run", "tool" to action.tool, "status" to r.status)
        return r
    }

    /** The launcher icon of the activity [key] names, small, for the confirmation card; null if it has none. */
    fun appIcon(key: String): Bitmap? = try {
        val (pkg, cls) = key.split('/', limit = 2)
        ctx.packageManager.getActivityIcon(ComponentName(pkg, cls)).toBitmap(ICON_PX, ICON_PX)
    } catch (_: Exception) {
        null
    }

    private fun start(i: Intent): Boolean {
        if (i.resolveActivity(ctx.packageManager) == null) return false
        return try { launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    private fun timer(seconds: Long): Executed {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Executed(ActionStatus.DONE, "Timer set for ${Durations.spoken(seconds)}.")
        else Executed(ActionStatus.FAILED, "No clock app on this phone takes timers.")
    }

    private fun alarm(hour: Int, minute: Int): Executed {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Executed(ActionStatus.DONE, String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute))
        else Executed(ActionStatus.FAILED, "No clock app on this phone takes alarms.")
    }

    private fun launcherApps(): List<AppEntry> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, PackageManager.MATCH_ALL).map { ri ->
            AppEntry(ri.loadLabel(pm).toString(), "${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
    }

    private fun openApp(a: Action.OpenApp): Executed {
        // Prepared actions carry the activity the card showed; anything else is resolved strictly (no loose match).
        val entry = a.key?.let { AppEntry(a.name, it) }
            ?: AppMatcher.match(a.name, launcherApps())?.takeIf { !it.loose }?.app
            ?: return Executed(ActionStatus.FAILED, "No app called “${a.name}” is installed.")
        val (pkg, cls) = entry.key.split('/', limit = 2)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return if (start(i)) Executed(ActionStatus.DONE, "Opened ${entry.label}.", launchedApp = true)
        else Executed(ActionStatus.FAILED, "${entry.label} didn't open.")
    }

    private fun maps(query: String, doneText: String): Executed {
        val q = Uri.encode(query)
        for (uri in listOf("om://search?query=$q", "geo:0,0?q=$q")) {
            if (start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) return Executed(ActionStatus.DONE, "$doneText.", launchedApp = true)
        }
        return Executed(ActionStatus.FAILED, "Organic Maps isn't installed.")
    }

    private fun torch(on: Boolean): Executed {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Executed(ActionStatus.FAILED, "This phone has no flashlight.")
        cm.setTorchMode(id, on)
        return Executed(ActionStatus.DONE, if (on) "Flashlight on." else "Flashlight off.")
    }

    private companion object {
        const val ICON_PX = 96
    }
}
