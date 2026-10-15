// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Notification
import android.app.Person
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.heyrobot.core.CheogramNotices
import app.heyrobot.core.CheogramSender
import app.heyrobot.core.CheogramSigners
import app.heyrobot.core.RLog
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.SettingsStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// MAIN PROCESS. "Learn names from Cheogram notifications" (docs/people.md "Ways in" 4): off until you turn it on in
// Settings › People and allow Robot's notification access. Android then shows Robot every app's notifications (only
// conversations by default, the manifest asks); this service looks at Cheogram's only, and of those only a 1:1 chat's:
// the sender's name and address and your account (core CheogramNotices). It never keeps or logs message text, and never
// logs who. Only the Cheogram build Robot hands texts to counts, posting for itself and signed as it was the first time
// Robot saw it after learning was turned on (core CheogramSigners). Cheap on purpose: no model and no voice process, and the People file is written only when something is new.
// With the switch off it learns nothing from what still arrives.
//
// 0.7: the same listener (the same component, so the grant you gave holds) reads your messages when Settings › Messages
// says so: MessageTap, for the apps you turned on there only, in RAM only. It asks Android to unbind it only when both
// are off.

class CheogramListener : NotificationListenerService() {
    /** Off the main thread (the app's UI thread when it is open): reading the settings and writing People. */
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-people").apply { isDaemon = true } }

    override fun onListenerConnected() {
        MessageTap.connected(this)
        // Turning a switch on asks for this binding before the settings are written: look a little later.
        worker.schedule({ unbindIfIdle() }, OFF_CHECK_S, TimeUnit.SECONDS)
    }

    override fun onListenerDisconnected() {
        MessageTap.disconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // 0.7: an app turned on in Settings › Messages, into RAM; every other package is dropped there at once.
        MessageTap.posted(applicationContext, n)
        if (n.packageName != HandOffApps.CHEOGRAM && n.packageName != HandOffApps.CHEOGRAM_PLAY) return
        worker.execute {
            if (!on()) return@execute unbindIfIdle()
            val opPkg = try { n.opPkg } catch (_: Throwable) { null }
            if (!CheogramSigners.fromCheogram(n.packageName, opPkg, HandOffApps.cheogram(applicationContext))) return@execute
            if (!signedAsFirstSeen(n.packageName)) return@execute
            val who = sender(n) ?: return@execute
            if (!PeopleFiles.store(applicationContext).learn(who, System.currentTimeMillis())) RLog.w("people_learn")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let(MessageTap::removed)
    }

    override fun onDestroy() {
        MessageTap.disconnected(this)
        worker.shutdown()
        super.onDestroy()
    }

    private fun on(): Boolean = try { SettingsStore.load(applicationContext).learnFromCheogram } catch (_: Throwable) { false }

    /** [unbindIfIdle] off the main thread (it reads the settings file). */
    fun checkIdle() = try { worker.execute { unbindIfIdle() } } catch (_: Throwable) {}

    /** Neither name learning nor reading messages is on: Android may unbind the listener (a switch turned on binds it again). */
    private fun unbindIfIdle() {
        if (on() || MessageTap.on(applicationContext)) return
        try { requestUnbind() } catch (_: Throwable) {}
    }

    /** The last answer of [signedAsFirstSeen]: for the package as installed then, and the pins file as it was (worker thread only). */
    private var trust: Pair<String, Boolean>? = null

    /**
     * [pkg] is signed by the certificate kept for it, or (first sight) its one signer's digest is kept now. Worked out
     * again only when the package is updated or reinstalled, or the pins file changes (learning turned on again).
     */
    private fun signedAsFirstSeen(pkg: String): Boolean {
        val pm = packageManager
        val info = try { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES) } catch (_: Exception) { return false }
        val pins = File(PeopleFiles.dir(applicationContext), CheogramSigners.FILE)
        val key = "$pkg:${info.lastUpdateTime}:${pins.lastModified()}"
        trust?.let { (k, ok) -> if (k == key) return ok }
        val signing = info.signingInfo
        val current = signing?.takeIf { !it.hasMultipleSigners() }?.apkContentsSigners?.singleOrNull()?.let {
            CheogramSigners.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
        val kept = try { CheogramSigners.parse(if (pins.exists()) pins.readText() else null) } catch (_: Exception) { return false }
        val pinned = kept[pkg]
        val still = pinned != null && try {
            CheogramSigners.unhex(pinned)?.let { pm.hasSigningCertificate(pkg, it, PackageManager.CERT_INPUT_SHA256) } == true
        } catch (_: Exception) { false }
        val ok = when (CheogramSigners.check(pinned, current, still)) {
            CheogramSigners.Verdict.TRUST -> true
            CheogramSigners.Verdict.PIN -> try { writeAtomic(pins, CheogramSigners.write(kept + (pkg to current!!))); true } catch (_: Exception) { false }
            CheogramSigners.Verdict.REFUSE -> { RLog.w("people_signer"); false }
        }
        // A pin written just now changes the file's time: the next notification works it out once more, then this holds.
        trust = key to ok
        return ok
    }

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
        /** Seconds after connecting before a switch that is off unbinds the listener. */
        const val OFF_CHECK_S = 5L
    }
}
