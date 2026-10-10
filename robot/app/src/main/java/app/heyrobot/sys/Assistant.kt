package app.heyrobot.sys

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionService
import app.heyrobot.R
import app.heyrobot.boot.BootCheckReceiver
import app.heyrobot.core.BootLogic
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import org.json.JSONObject

// Main and voice processes. What the system says about Robot as the assistant, and the restart fallback.

data class AssistantFacts(
    val roleHeld: Boolean,
    /** VoiceInteractionService.isActiveService; null if the system would not say. */
    val active: Boolean?,
    /** Settings.Secure voice_recognition_service: "unset", "unreadable" or the component. */
    val recognizer: String,
    val recognizerIsRobot: Boolean,
    val recognizersFound: Int,
    val micGranted: Boolean,
    val notificationsGranted: Boolean,
    /** From the voice process: listening for "hey robot" right now (null = no report). */
    val listening: Boolean?,
    val listeningNote: String?,
    /** Phase 2: contacts (texts and emails by name, lookups) and the calendar (read and write). */
    val contactsGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val cheogramInstalled: Boolean = false,
    val protonInstalled: Boolean = false,
)

object Assistant {
    /** By name: the main process never refers to the assistant classes. */
    const val VIS_CLASS = "app.heyrobot.assistant.RobotVoiceService"
    const val RECOGNIZER_CLASS = "app.heyrobot.assistant.RobotRecognitionService"

    fun facts(ctx: Context): AssistantFacts {
        val roleHeld = try {
            val rm = ctx.getSystemService(RoleManager::class.java)
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) { false }
        val active = try { VoiceInteractionService.isActiveService(ctx, ComponentName(ctx.packageName, VIS_CLASS)) } catch (_: Throwable) { null }
        val rec = try { Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service") ?: "unset" } catch (_: Throwable) { "unreadable" }
        val found = try { ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).size } catch (_: Throwable) { 0 }
        val status = readOrNull(Paths.voiceStatus(ctx))?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        return AssistantFacts(
            roleHeld = roleHeld,
            active = active,
            recognizer = rec.ifEmpty { "unset" },
            recognizerIsRobot = ComponentName.unflattenFromString(rec)?.let { it.packageName == ctx.packageName && it.className == RECOGNIZER_CLASS } == true,
            recognizersFound = found,
            micGranted = granted(ctx, Manifest.permission.RECORD_AUDIO),
            notificationsGranted = granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
            listening = status?.optBoolean("listening"),
            listeningNote = status?.optString("note")?.takeIf { it.isNotEmpty() },
            contactsGranted = granted(ctx, Manifest.permission.READ_CONTACTS),
            calendarGranted = granted(ctx, Manifest.permission.READ_CALENDAR) && granted(ctx, Manifest.permission.WRITE_CALENDAR),
            cheogramInstalled = HandOffApps.cheogram(ctx) != null,
            protonInstalled = HandOffApps.proton(ctx),
        )
    }

    fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun locked(ctx: Context): Boolean = try { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Exception) { false }

    /** Settings > Apps > Default apps > Digital assistant app (voice input settings first, as in the probe). */
    fun settingsIntent(ctx: Context): Intent? =
        listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { it.resolveActivity(ctx.packageManager) != null }

    fun openSettings(ctx: Context): Boolean = try {
        settingsIntent(ctx)?.let { ctx.startActivity(it); true } ?: false
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}

/**
 * The restart fallback (spec item 9): Android 17 may drop a third-party assistant at boot (see the
 * manifest's recognizer note). 3 minutes after BOOT_COMPLETED, if Robot was the assistant before and has
 * not started in this boot, a high-importance notification takes the person to the assistant setting.
 * It never binds or starts the assistant itself.
 */
object BootCheck {
    const val DELAY_MS = 180_000L
    private const val CHANNEL = "assistant_restart"
    private const val NOTICE_ID = 77
    private const val REQ_ALARM = 7_701
    private const val REQ_OPEN = 7_702

    /** [VOICE] onReady: the assistant runs in this boot; a notice saying otherwise is out of date. */
    fun onVisReady(ctx: Context) {
        try {
            Paths.marker(ctx, "vis_ready_boot").writeText("${Paths.bootCount(ctx)}")
            Paths.marker(ctx, "vis_ever_ready").writeText("1")
        } catch (_: Exception) {}
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID) } catch (_: Exception) {}
    }

    fun readyThisBoot(ctx: Context): Boolean = readOrNull(Paths.marker(ctx, "vis_ready_boot"))?.trim()?.toIntOrNull() == Paths.bootCount(ctx)

    /** [MAIN] From BOOT_COMPLETED: an exact alarm (USE_EXACT_ALARM), inexact if the system refuses. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, REQ_ALARM, Intent(ctx, BootCheckReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = SystemClock.elapsedRealtime() + DELAY_MS
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (t: Throwable) {
            RLog.w("boot_check_alarm", t)
        }
    }

    /** [MAIN] The alarm. */
    fun run(ctx: Context) {
        val ever = Paths.marker(ctx, "vis_ever_ready").isFile
        val ready = readyThisBoot(ctx) || Assistant.facts(ctx).active == true
        val notify = BootLogic.shouldNotify(ever, ready)
        RLog.i("boot_check", "everReady" to ever, "ready" to ready, "notify" to notify)
        if (notify) notifyMissing(ctx)
    }

    private fun notifyMissing(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant after a restart", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when Android turned Robot off as the assistant after a restart."
            })
            val target = Assistant.settingsIntent(ctx)
                ?: Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val open = PendingIntent.getActivity(ctx, REQ_OPEN, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = "Android turned Robot off as your assistant during the restart. Tap, choose Digital assistant app, and pick Robot again."
            nm.notify(NOTICE_ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle("Turn Robot back on")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (t: Throwable) {
            RLog.w("boot_check_notify", t)
        }
    }
}
