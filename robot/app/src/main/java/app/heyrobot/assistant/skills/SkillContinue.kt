// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.SkillIntents
import java.security.SecureRandom

// VOICE PROCESS (1.1, Skills D7). A skill's steps after an app opens: a silent notification, "Continue “Heading home”:
// Next: Open the route home. Tap to go on.", whose tap reaches SkillContinueReceiver (in :voice, not exported), which
// opens the panel with a one-time token (RobotVoiceService.show, as Ready cards do). The token is random (128 bits),
// used once, kept only here in :voice's memory, and good for 10 minutes; a tap on a locked phone runs nothing and the
// notification stays until it expires. With Robot's notifications off nothing can wait: SkillRun stops before leaving.

object SkillContinue {
    const val TTL_MS = 10 * 60_000L
    private const val CHANNEL = "skills"
    /** Notification ids and their PendingIntents' request codes: away from Ready cards' (8 411 to 8 675). */
    private const val BASE_ID = 8_700
    private const val SLOTS = 32

    /** What waits: the rest of skill [skillId] (at [rev]) from step [from], with this run's values, named and taint. */
    data class Waiting(val skillId: String, val rev: Int, val values: Map<String, String>, val named: Boolean, val from: Int, val done: Int,
        val untrusted: Boolean, val expires: Long = 0L, val notification: Int = 0)

    private val waiting = HashMap<String, Waiting>()
    private var slot = 0
    private val random = SecureRandom()

    /** Robot's notifications are on (the permission, the app and this channel): steps can wait for a tap. */
    fun canWait(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channel(ctx)
        Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) && nm.areNotificationsEnabled() &&
            nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    } catch (t: Throwable) {
        RLog.w("skill_can_wait", t)
        false
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.skill_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.skill_channel_detail)
            setSound(null, null)
            enableVibration(false)
        })
    }

    /** Posts the notification for [w] (the rest of [s]), its words in [l]. */
    fun post(ctx: Context, s: Skill, w: Waiting, l: Langs) {
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) } // l10n: not text (a random token)
        val now = System.currentTimeMillis()
        val id: Int
        synchronized(waiting) {
            waiting.entries.removeAll { it.value.expires <= now }
            id = BASE_ID + (slot++ % SLOTS)
            waiting.entries.removeAll { it.value.notification == id }
            waiting[token] = w.copy(expires = now + TTL_MS, notification = id)
        }
        try {
            channel(ctx)
            val next = SkillCards.stepText(s, s.steps[w.from], w.values).render(l).replace("*", "")
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(SkillIntents.ACTION_CONTINUE).setClassName(ctx.packageName, SkillIntents.RECEIVER)
                .putExtra(SkillIntents.EXTRA_TOKEN, token), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(SkillStrings.continuePublic().render(l)).build()
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(SkillStrings.continueTitle("name" to s.name).render(l))
                .setContentText(SkillStrings.continueText("step" to next).render(l))
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(TTL_MS)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (t: Throwable) {
            RLog.w("skill_notify", t)
        }
    }

    /** What [token] holds, without using it up; null when it is unknown or past its 10 minutes. */
    fun peek(token: String): Waiting? = synchronized(waiting) { waiting[token]?.takeIf { it.expires > System.currentTimeMillis() } }

    /** What [token] holds, used up (its notification goes); null when it was used, is unknown or expired. */
    fun take(ctx: Context, token: String): Waiting? {
        val w = synchronized(waiting) { waiting.remove(token) } ?: return null
        try { ctx.getSystemService(NotificationManager::class.java).cancel(w.notification) } catch (_: Throwable) {}
        return w.takeIf { it.expires > System.currentTimeMillis() }
    }
}
