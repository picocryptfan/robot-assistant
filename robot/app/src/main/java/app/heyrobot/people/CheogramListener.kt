package app.heyrobot.people

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.heyrobot.core.CheogramNotices
import app.heyrobot.core.CheogramSender
import app.heyrobot.core.PeopleEdit
import app.heyrobot.core.RLog
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.SettingsStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// MAIN PROCESS. "Learn names from Cheogram notifications" (docs/people.md "Ways in" 4): off until you turn it on in
// Settings › People and allow Robot's notification access. Android then shows Robot every app's notifications (only
// conversations by default, the manifest asks); this service looks at Cheogram's only, and of those only a 1:1 chat's:
// the sender's name and address and your account (core CheogramNotices). It never keeps or logs message text, and never
// logs who. Cheap on purpose: no model and no voice process, and the People file is written only when something is new.
// With the switch off it asks Android to unbind it, and ignores anything that still arrives.

class CheogramListener : NotificationListenerService() {
    /** Off the main thread (the app's UI thread when it is open): reading the settings and writing People. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-people").apply { isDaemon = true } }

    override fun onListenerConnected() {
        worker.execute { if (!on()) unbind() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName != HandOffApps.CHEOGRAM && n.packageName != HandOffApps.CHEOGRAM_PLAY) return
        worker.execute {
            if (!on()) return@execute unbind()
            val who = sender(n) ?: return@execute
            PeopleFiles.store(applicationContext).update { PeopleEdit.learn(it, who.name, who.jid, who.account, System.currentTimeMillis()) }
                ?: RLog.w("people_learn")
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun on(): Boolean = try { SettingsStore.load(applicationContext).learnFromCheogram } catch (_: Throwable) { false }

    private fun unbind() = try { requestUnbind() } catch (_: Throwable) {}

    /**
     * The chat's shortcut id, whether it is a group, and each message's sender (Person name and key): nothing else of the
     * notification is touched.
     */
    private fun sender(sbn: StatusBarNotification): CheogramSender? = try {
        val n = sbn.notification
        val group = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java).orEmpty()
        val senders = messages.mapNotNull { (it as? Bundle)?.getParcelable(KEY_SENDER_PERSON, Person::class.java) }.map { it.name?.toString() to it.key }
        CheogramNotices.sender(n.shortcutId, group, senders)
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Notification.MessagingStyle.Message's bundle key for its sender (API 28+). */
        const val KEY_SENDER_PERSON = "sender_person"
    }
}
