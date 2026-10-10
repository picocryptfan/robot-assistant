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
import android.app.Application
import android.os.UserManager
import app.heyrobot.core.AdbFixes
import app.heyrobot.core.BootLogic
import app.heyrobot.core.RLog
import app.heyrobot.core.RecognizerRepair
import app.heyrobot.core.RepairWhen
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
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
    /** Ask Claude: the masked API key (null: none saved), today's spend, the Claude app and which hand-off resolves. */
    val claudeKey: String? = null,
    /** The saved key couldn't be decrypted and was removed: Settings says so. */
    val claudeKeyUnreadable: Boolean = false,
    val claudeSpendMicros: Long = 0,
    val claudeApp: ClaudeAppFacts = ClaudeAppFacts(installed = false, appLink = false, webLink = false, send = false),
    /** GrapheneOS's per-app Network toggle (INTERNET as a runtime permission). */
    val networkGranted: Boolean = true,
    /** 0.2.3: WRITE_SECURE_SETTINGS was granted with adb, so Robot keeps its recognizer set itself. */
    val secureSettingsGranted: Boolean = false,
)

object Assistant {
    /** By name: the main process never refers to the assistant classes. */
    const val VIS_CLASS = "app.heyrobot.assistant.RobotVoiceService"
    const val RECOGNIZER_CLASS = "app.heyrobot.assistant.RobotRecognitionService"

    fun roleHeld(ctx: Context): Boolean = try {
        val rm = ctx.getSystemService(RoleManager::class.java)
        rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (_: Exception) { false }

    fun facts(ctx: Context): AssistantFacts {
        val roleHeld = roleHeld(ctx)
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
            claudeKey = ClaudeKeyFiles.masked(ctx),
            claudeKeyUnreadable = ClaudeKeyFiles.unreadable(ctx),
            claudeSpendMicros = ClaudeSpendStore.today(ctx),
            claudeApp = ClaudeAppIntents.facts(ctx),
            networkGranted = granted(ctx, Manifest.permission.INTERNET),
            secureSettingsGranted = RecognizerKeeper.canWrite(ctx),
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

/**
 * Main and voice processes (0.2.3). Keeps Settings.Secure voice_recognition_service set to Robot's recognizer, which
 * is what keeps Robot the assistant at boot (core RecognizerRepair), when the user granted WRITE_SECURE_SETTINGS with
 * adb and Robot is the chosen assistant. Writes that one key and nothing else, and never replaces another installed
 * recognizer. Checked at boot (also before the first unlock), when the app opens, on Assistant setup and when the
 * assistant starts. Each write goes to the voice log.
 */
object RecognizerKeeper {
    private const val PENDING = "recognizer_set_pending.txt"

    fun current(ctx: Context): String? = try { Settings.Secure.getString(ctx.contentResolver, RecognizerRepair.KEY) } catch (_: Throwable) { null }

    fun canWrite(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)

    fun isRobot(ctx: Context): Boolean = RecognizerRepair.same(current(ctx), AdbFixes.RECOGNIZER)

    /** [flattened] names an installed recognition service (direct-boot unaware ones too: this may run before unlock). */
    private fun installed(ctx: Context, flattened: String?): Boolean {
        val cn = flattened?.let { ComponentName.unflattenFromString(it) } ?: return false
        return try {
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE),
                PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                .any { it.serviceInfo.packageName == cn.packageName && it.serviceInfo.name == cn.className }
        } catch (_: Throwable) { false }
    }

    fun check(ctx: Context, why: RepairWhen): RecognizerRepair.Decision {
        val cur = current(ctx)
        val d = RecognizerRepair.decide(Assistant.roleHeld(ctx), canWrite(ctx), cur, AdbFixes.RECOGNIZER, installed(ctx, cur))
        RLog.i("recognizer_check", "why" to why, "decision" to d)
        if (d != RecognizerRepair.Decision.WRITE) return d
        val ok = try {
            Settings.Secure.putString(ctx.contentResolver, RecognizerRepair.KEY, AdbFixes.RECOGNIZER)
        } catch (t: Throwable) {
            RLog.w("recognizer_set", t)
            false
        }
        log(ctx, why, ok)
        return d
    }

    private fun unlocked(ctx: Context): Boolean = try { ctx.getSystemService(UserManager::class.java).isUserUnlocked } catch (_: Throwable) { true }

    private fun pendingFile(ctx: Context) = File(ctx.createDeviceProtectedStorageContext().filesDir, PENDING)

    private fun log(ctx: Context, why: RepairWhen, ok: Boolean) {
        // Before the first unlock the log's file can't be written: a note in device-protected storage, logged later.
        if (!unlocked(ctx)) {
            try { writeAtomic(pendingFile(ctx), "${System.currentTimeMillis()} ${why.id} ${if (ok) 1 else 0}") } catch (_: Throwable) {}
            return
        }
        ensureLog(ctx)
        VoiceLog.add(VoiceEvent.RECOGNIZER_SET, why, if (ok) 1 else 0)
        VoiceLog.flush()
    }

    /** After the unlock: a write made before it goes to the log now, with its time. */
    fun flushPending(ctx: Context) {
        if (!unlocked(ctx)) return
        val f = pendingFile(ctx)
        val p = readOrNull(f)?.trim()?.split(' ') ?: return
        f.delete()
        val t = p.getOrNull(0)?.toLongOrNull() ?: return
        ensureLog(ctx)
        VoiceLog.addAt(t, VoiceEvent.RECOGNIZER_SET, p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0)
        VoiceLog.flush()
    }

    /** Each process logs to its own file: the voice process to the voice log, the main app to a small one of its own. */
    private fun ensureLog(ctx: Context) {
        val voice = Application.getProcessName().contains(':')
        VoiceLog.init(if (voice) Paths.voiceLog(ctx) else Paths.voiceLogMain(ctx))
    }
}
