// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.PhoneAccount
import app.heyrobot.R
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.DayRange
import app.heyrobot.core.RLog
import app.heyrobot.core.calls.CallerClue
import app.heyrobot.core.calls.CallerName
import app.heyrobot.core.calls.CallerNameSettings
import app.heyrobot.core.calls.CallerSource
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.TravelMode
import app.heyrobot.core.i18n.CallerStrings
import app.heyrobot.core.lock.SomeoneElseLock
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.people.MessageTap
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.sys.Paths
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.RobotFiles
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// MAIN PROCESS. Caller name (docs/plans/caller-name-plan.md, idea 13). Android binds this only while Robot holds the call
// screening role, and only for calls from numbers not in your contacts. It always lets the call ring as usual (an empty
// CallResponse: never answers, rejects, silences, blocks or keeps a call out of the log) and, with Caller name on, shows
// "Probably …" when your texts, calendar or Robot Files name the number. Nothing is logged: no number, name or match.

class CallerNameService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val at = SystemClock.elapsedRealtime() // the 1.5 s budget starts here, with the call
        // Telecom gets its answer first, so the call rings on time; the search runs after, off this (main) thread.
        try { respondToCall(details, CallResponse.Builder().build()) } catch (t: Throwable) { RLog.w("caller_name_respond", t) }
        try {
            if (details.callDirection == Call.Details.DIRECTION_INCOMING && CallerNameStore.current(applicationContext).on) {
                val number = details.handle?.takeIf { it.scheme == PhoneAccount.SCHEME_TEL }?.schemeSpecificPart
                CallerLookup.start(applicationContext, number, at)
            }
        } catch (t: Throwable) {
            RLog.w("caller_name", t)
        }
    }
}

/** caller_name.json in files/robot (not settings.json): only the switch. */
object CallerNameStore {
    fun file(ctx: Context): File = File(Paths.dir(ctx), CallerNameSettings.FILE)
    fun current(ctx: Context): CallerNameSettings = CallerNameSettings.read(readOrNull(file(ctx)))
    fun save(ctx: Context, s: CallerNameSettings): Boolean =
        try { writeAtomic(file(ctx), s.toJson()); true } catch (t: Throwable) { RLog.w("caller_name_save", t); false }
}

object CallerLookup {
    // One thread, one search at a time: a content query or the vault load can't be cancelled, so a call that comes in
    // while a search still runs is skipped rather than given another thread.
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-caller").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    /** Searches on [pool] and shows the likely name; a name found past [CallerName.BUDGET_MS] is dropped ([CallerName.guess]). */
    fun start(ctx: Context, number: String?, at: Long = SystemClock.elapsedRealtime()) {
        if (!busy.compareAndSet(false, true)) return
        try {
            pool.execute {
                try { guess(ctx, number, at)?.let { CallerNotice.show(ctx, it) } } catch (t: Throwable) { RLog.w("caller_name", t) } finally { busy.set(false) }
            }
        } catch (t: Throwable) { busy.set(false); throw t }
    }

    private fun guess(ctx: Context, number: String?, at: Long): String? {
        val someoneElse = PhoneLockNow.someoneElse(ctx)
        val m = SomeoneElseLock.messages(MessagesStore.current(ctx).settings, someoneElse)
        val scope = CallerName.scope(locked = Assistant.locked(ctx) || RobotFiles.locked(ctx), someoneElse = someoneElse,
            textsOn = m.on && NotificationAccess.granted(ctx), textsLocked = m.locked == MessagesLocked.READ_AND_REPLY)
        val sources = listOf(CallerSource.TEXTS to { texts() }, CallerSource.CALENDAR to { calendar(ctx) }, CallerSource.FILES to { files(ctx) })
        return CallerName.guess(number, sources, scope, SystemClock::elapsedRealtime, known = { known(ctx, number.orEmpty()) }, start = at)
    }

    private fun texts(): List<CallerClue> = MessageTap.buffer.threads(System.currentTimeMillis()).flatMap { t ->
        t.messages.filter { !it.fromMe }.map { CallerClue(CallerSource.TEXTS, it.text, about = it.senderKey, name = it.senderName) }
    }

    private fun calendar(ctx: Context): List<CallerClue> {
        if (!Calendars.readGranted(ctx)) return emptyList()
        val now = ZonedDateTime.now()
        return Calendars.events(ctx, DayRange(now.minusDays(180), now.plusDays(90))).map { e: CalendarEvent ->
            CallerClue(CallerSource.CALENDAR, listOf(e.title, e.location.orEmpty()).joinToString("\n"), title = e.title)
        }
    }

    private fun files(ctx: Context): List<CallerClue> {
        val fs = FilesSettingsStore.current(ctx)
        if (!fs.on) return emptyList()
        val index = (RobotFiles.vault(ctx).load() as? IndexLoad.Loaded)?.index ?: return emptyList()
        // Drawers travel mode hides stay out of sight here too.
        return index.entries.filter { !TravelMode.hidden(it.drawer, fs) }.map { e ->
            CallerClue(CallerSource.FILES, (e.fields.values.map { it.value } + e.text).joinToString("\n"), title = e.name)
        }
    }

    /** In the phone's contacts or Robot's People; true when Robot can't tell (fail closed: nothing shown). */
    private fun known(ctx: Context, number: String): Boolean = try {
        val inContacts = Assistant.granted(ctx, Manifest.permission.READ_CONTACTS) &&
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } != false
        inContacts || PeopleFiles.book(ctx).people.any { p -> p.phones.any { CallerName.same(it.number, number) } }
    } catch (_: Exception) { true }
}

/** The notice: silent, gone after a minute, never on the lock screen (VISIBILITY_SECRET); the name only, never its source. */
object CallerNotice {
    private const val CHANNEL = "caller"
    /** Away from a pairing's RAISED (8,790) and HoldTap's 8,771. */
    private const val ID = 8_773

    fun show(ctx: Context, name: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val l = AppLanguage.langs(ctx)
        if (!Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) || !nm.areNotificationsEnabled()) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, CallerStrings.channel().render(l), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
        }
        val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(CallerStrings.unlock().render(l)).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(CallerStrings.probably("name" to name).render(l))
            .setContentText(CallerStrings.calling().render(l))
            .setCategory(Notification.CATEGORY_STATUS)
            .setLocalOnly(true) // never on a paired watch: nothing leaves the phone
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(60_000L)
            .build()
        try { nm.notify(ID, n) } catch (t: Throwable) { RLog.w("caller_notice_post", t) }
    }
}
