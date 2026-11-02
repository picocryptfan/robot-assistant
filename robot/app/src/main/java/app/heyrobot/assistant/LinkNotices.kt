// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.core.Person
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.files.PeerArrivals
import app.heyrobot.core.link.XferPaths
import app.heyrobot.core.link.XferState
import app.heyrobot.link.arrivalKeys
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.RobotFiles
import app.heyrobot.core.link.DeskCard
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNotice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskReplies
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.LinkCardText
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.LinkInCode
import app.heyrobot.core.link.LinkLimitCode
import app.heyrobot.core.link.LinkModeCode
import app.heyrobot.core.link.LinkPairCode
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.AppPages
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.LinkShade
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.LinkClient
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.sys.ListShareSync
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.ProtectedBroadcasts
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

// VOICE PROCESS. 1.5, Robot to Robot's notifications (robot-to-robot-plan.md §4.2, §4.4, §4.6; job L6). :link pings :voice
// (Cmd.LINK_DESK, nothing in it) when its desk changes; :voice asks LinkService for the notices (kinds, pairings and counts)
// and, once the phone is unlocked, says them:
//   - a card waiting, when the panel isn't open: "Sam's Robot asks you something." with the card's summary, and Yes (what
//     its handler does) and Decline when it's the only one; several: "Robot: 3 requests waiting". A tap opens Requests;
//   - "Robot handled 3 things" (what Robot answered on its own), "For Sam and Mom. Open Requests to see or undo them.";
//   - a pairing's news: paired, unpaired, moved (compare before you accept), asks to switch to Private, switched, paused for
//     too many requests, couldn't reach.
// Nothing about Robot to Robot shows on the lock screen: while locked, the notices wait in :voice's memory and are said at
// the unlock (ACTION_USER_PRESENT); every notification is VISIBILITY_SECRET with a public version that says "Robot" only, and
// its actions need the phone unlocked (setAuthenticationRequired). During a phone lock no card opens. What an action
// answers is held here under a random token for a day; the action reaches :voice as a Cmd (Robot's own broadcast).
// Nothing of a request is logged: codes and counts only.

object LinkNotices {
    private const val CHANNEL = "link" // l10n: not text (a channel id)
    /** Notification ids, and their PendingIntents' request codes: after Files' (8,760) and away from routines' and skills'. */
    private const val CARDS_ID = LinkShade.CARDS_ID
    private const val HANDLED_ID = 8_781
    /** One per kind of pairing news: 8,782 + its code (up to 8,793 for REPLIED, which has its own). */
    private const val NEWS_ID = 8_782
    /** L7: their Robots' answers to what you asked, when the panel isn't open. */
    private const val REPLIED_ID = LinkShade.REPLIED_ID
    private const val LIST_ENDED_ID = 8_796
    private const val LIST_NO_ROOM_ID = 8_797
    private const val LIST_TAKEN_ID = 8_798
    private const val LIST_FULL_ID = 8_799
    private const val TTL_MS = 24 * 3_600_000L

    @Volatile private var app: Context? = null
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-link-notices").apply { isDaemon = true } } } // l10n: not text (a thread's name)
    /** What :link told while the phone was locked: said at the unlock. */
    private val pending = ArrayList<DeskNotice>()
    internal class Held(val card: String, val choice: DeskChoice, val until: Long)
    internal val held = HashMap<String, Held>()
    /** The single card's notice as last posted: its text and buttons, for saying a failed tap over it. */
    private var shown: Pair<String?, List<Pair<String, Held>>>? = null
    private val random = SecureRandom()

    /** At :voice's start (VoiceHub.init): the unlock hook, then a first look. */
    fun start(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        try {
            ProtectedBroadcasts.register(ctx.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_USER_PRESENT) kick() }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT))
        } catch (t: Throwable) { RLog.w("link_notice_hooks", t) }
        kick()
    }

    /** :link's desk changed (Cmd.LINK_DESK), or the phone was unlocked. */
    fun kick() {
        val ctx = app ?: return
        worker.execute { try { pass(ctx) } catch (t: Throwable) { RLog.w("link_notice_pass", t) } }
    }

    /** 1.5: a shared list's changes come in and go out here. Its own failure never stops the notices. */
    private fun shareLists(ctx: Context, client: LinkClient) {
        val pass = try {
            val l = AppLanguage.langs(ctx)
            ListShareSync.run(ctx, client, lang = l.ui.id)
        } catch (t: Throwable) { RLog.w("list_share_pass", t); ListShareSync.Pass() }
        if ((pass.ended.isEmpty() && pass.noRoom.isEmpty() && pass.taken.isEmpty() && pass.full.isEmpty()) || !allowed(ctx)) return
        val views = client.pairings().orEmpty()
        val people = try { PeopleFiles.store(ctx).list() } catch (_: Throwable) { emptyList() }
        val l = AppLanguage.langs(ctx)
        fun name(id: String) = LinkNames.of(views.firstOrNull { it.id == id }, people)
        pass.ended.firstOrNull()?.let { post(ctx, LIST_ENDED_ID, LinkStrings.listEnded("name" to name(it)).render(l), null, emptyList()) }
        pass.noRoom.firstOrNull()?.let { post(ctx, LIST_NO_ROOM_ID, LinkStrings.listNoRoom("name" to name(it), "max" to ListsLimits.MAX_LISTS).render(l), null, emptyList()) }
        pass.taken.firstOrNull()?.let { post(ctx, LIST_TAKEN_ID, LinkStrings.listTaken("name" to name(it)).render(l), null, emptyList()) }
        pass.full.firstOrNull()?.let { post(ctx, LIST_FULL_ID, LinkStrings.listFull("name" to name(it)).render(l), null, emptyList()) }
    }

    private fun pass(ctx: Context) {
        if (!LinkSettingsStore.current(ctx).on) return
        val client = LinkClient(ctx)
        try {
            val n = client.notices() ?: return
            synchronized(pending) { pending += n.notices; while (pending.size > 200) pending.removeAt(0) }
            // §4.4: nothing while locked, and no card during a phone lock; what came waits for the unlock.
            if (Assistant.locked(ctx)) return
            arrivals(ctx, client)
            shareLists(ctx, client)
            val now = synchronized(pending) { pending.toList().also { pending.clear() } }
            val owed = LinkShade.replyOwed(ctx)
            if (now.isEmpty() && n.waiting == 0 && !owed) return
            if (!allowed(ctx)) return
            val l = AppLanguage.langs(ctx)
            val views = client.pairings().orEmpty()
            val people = try { PeopleFiles.store(ctx).list() } catch (_: Throwable) { emptyList() }
            fun name(p: String) = LinkNames.of(views.firstOrNull { it.id == p }, people)
            val handled = now.filter { it.kind == DeskNoticeKind.HANDLED }
            if (handled.isNotEmpty()) {
                val count = handled.sumOf { it.value }.toInt()
                val names = Phrase.ListOf(handled.map { it.pairing }.distinct().map { name(it) })
                post(ctx, HANDLED_ID, LinkStrings.handled(count).render(l), LinkStrings.handledFrom("names" to names).render(l), emptyList())
                VoiceLog.add(VoiceEvent.LINK_AUTO, 0, count)
            }
            for (x in now.filter { it.kind != DeskNoticeKind.HANDLED && it.kind != DeskNoticeKind.CARDS && it.kind != DeskNoticeKind.REPLIED }) {
                log(x)
                val text = news(x, name(x.pairing)) ?: continue
                post(ctx, NEWS_ID + x.kind.code, text.render(l), null, emptyList())
            }
            // L7: their Robots' answers: in the panel when it's open (PanelLink), else one notification, then said.
            if (owed || now.any { it.kind == DeskNoticeKind.REPLIED }) {
                if (PanelController.shown) VoiceHub.main.post { PanelController.linkChanged() } else replies(ctx, client, l, views, people)
            }
            // Cards: in the panel when it's open (PanelLink), else here.
            now.filter { it.kind == DeskNoticeKind.CARDS }.sumOf { it.value }.takeIf { it > 0 }?.let { VoiceLog.add(VoiceEvent.LINK_IN, LinkInCode.CARDS.id, it.toInt()) }
            if (n.waiting > 0 || now.any { it.kind == DeskNoticeKind.CARDS }) {
                if (PanelController.shown) { VoiceHub.main.post { PanelController.linkChanged() }; return }
                cards(ctx, client, l, views, people)
            }
        } finally {
            client.close()
        }
    }

    /** L8 (§3.6): the files that arrived, read and filed now that the phone is unlocked; with Robot Files open only, else they wait. */
    private fun arrivals(ctx: Context, client: LinkClient) {
        if (FilesIntake.gate(ctx) != FilesIntake.Gate.OPEN) return
        val views = client.transfers().orEmpty().filter { !it.out && it.state == XferState.ARRIVED }
        if (views.isEmpty()) return
        val intake = FilesIntake.phone(ctx)
        val dir = RobotFiles.dir(ctx)
        val filed = PeerArrivals({ XferPaths.arrived(dir, it) }, arrivalKeys(), intake::copyArriving, intake::holds, intake::discardArriving, intake::commitArriving)
            .run(views) { id, ok -> client.filed(id, ok) }
        if (filed > 0) FilingWork.kick()
    }

    private fun news(x: DeskNotice, name: Phrase): Phrase? = when (x.kind) {
        DeskNoticeKind.FILES -> LinkStrings.fileArrived("name" to name)
        DeskNoticeKind.FOLDER_ENDED -> LinkStrings.folderEnded("name" to name)
        DeskNoticeKind.FILE_FAILED -> LinkStrings.fileFailed("name" to name)
        DeskNoticeKind.PAIRED -> LinkStrings.paired("name" to name)
        DeskNoticeKind.UNPAIRED -> LinkStrings.unpaired("name" to name)
        DeskNoticeKind.MOVED -> LinkStrings.moved("name" to name)
        DeskNoticeKind.LOWER_ASKED -> LinkStrings.lowerAsked("name" to name)
        DeskNoticeKind.LOWERED -> LinkStrings.lowered("name" to name)
        DeskNoticeKind.RAISED -> LinkStrings.raised("name" to name)
        DeskNoticeKind.BUSY -> LinkStrings.busy("name" to name, "time" to Instant.ofEpochSecond(x.value).atZone(ZoneId.systemDefault()).toLocalTime())
        DeskNoticeKind.GAVE_UP -> LinkStrings.gaveUp("name" to name)
        DeskNoticeKind.CARDS, DeskNoticeKind.HANDLED, DeskNoticeKind.REPLIED -> null
    }

    /** L7: codes only, for the voice log. */
    private fun log(x: DeskNotice) {
        when (x.kind) {
            DeskNoticeKind.PAIRED -> VoiceLog.add(VoiceEvent.LINK_PAIR, LinkPairCode.PAIRED.id, 0)
            DeskNoticeKind.UNPAIRED -> VoiceLog.add(VoiceEvent.LINK_PAIR, LinkPairCode.UNPAIRED.id, 0)
            DeskNoticeKind.MOVED -> VoiceLog.add(VoiceEvent.LINK_PAIR, LinkPairCode.MOVED.id, 0)
            DeskNoticeKind.RAISED -> VoiceLog.add(VoiceEvent.LINK_MODE, LinkModeCode.RAISED.id, 0)
            DeskNoticeKind.LOWER_ASKED -> VoiceLog.add(VoiceEvent.LINK_MODE, LinkModeCode.LOWER_ASKED.id, 0)
            DeskNoticeKind.LOWERED -> VoiceLog.add(VoiceEvent.LINK_MODE, LinkModeCode.LOWERED.id, 0)
            DeskNoticeKind.BUSY -> VoiceLog.add(VoiceEvent.LINK_LIMIT, LinkLimitCode.BUSY.id, 0)
            DeskNoticeKind.GAVE_UP -> VoiceLog.add(VoiceEvent.LINK_LIMIT, LinkLimitCode.GAVE_UP.id, 0)
            DeskNoticeKind.CARDS, DeskNoticeKind.HANDLED, DeskNoticeKind.REPLIED, DeskNoticeKind.FILES, DeskNoticeKind.FILE_FAILED, DeskNoticeKind.FOLDER_ENDED -> {}
        }
    }

    /**
     * L7: their Robots' answers not yet said, as one notification (the newest's words, drawn by code; "Sam's Robot answered"),
     * then marked said. Opens Requests, where Sent shows each.
     */
    private fun replies(ctx: Context, client: LinkClient, l: Langs, views: List<PairingView>, people: List<Person>) {
        val desk = client.desk() ?: return
        val all = desk.lines.filter { it.entry.way == DeskWay.REPLY && it.request != null }
        val lines = all.filter { !it.entry.seen }
        val newest = DeskReplies.due(all, LinkShade.replyOwed(ctx)) ?: return LinkShade.replyPaid(ctx)
        val name = LinkNames.of(views.firstOrNull { it.id == newest.entry.pairing }, people)
        val text = LinkCardText.reply(newest.request!!, name, ZoneId.systemDefault())?.render(l) ?: return LinkShade.replyPaid(ctx)
        // fb13: marked said only once it's up; a lock that stopped it leaves it owed for the pass after that lock.
        if (!post(ctx, REPLIED_ID, LinkStrings.answeredNote("name" to name).render(l), text, emptyList())) return LinkShade.oweReply(ctx)
        LinkShade.replyPaid(ctx)
        for (x in lines) client.seen(x.entry.id)
        if (lines.isNotEmpty()) VoiceLog.add(VoiceEvent.LINK_IN, LinkInCode.REPLIES.id, lines.size)
    }

    /** The cards waiting: one person's ("Sam's Robot asks you something.", with Yes and Decline for a single card), or how many. */
    private fun cards(ctx: Context, client: LinkClient, l: Langs, views: List<PairingView>, people: List<Person>) {
        val desk = client.desk() ?: return
        val total = desk.cards.size + desk.more.values.sum()
        if (total == 0) { cancel(ctx, CARDS_ID); return }
        val from = desk.cards.map { it.pairing }.distinct()
        if (from.size != 1) {
            post(ctx, CARDS_ID, LinkStrings.waiting(total).render(l), null, emptyList())
            return
        }
        val name = LinkNames.of(views.firstOrNull { it.id == from.single() }, people)
        val first: DeskCard = desk.cards.first()
        val title = LinkStrings.cardsFrom(total, "name" to name).render(l)
        val text = LinkCardText.summary(first.request, name, ZoneId.systemDefault()).render(l)
        val actions = if (total == 1) listOfNotNull(
            LinkCardText.yes(buttonYes(first.yes))?.let { it.render(l) to Held(first.id, DeskChoice.YES, System.currentTimeMillis() + TTL_MS) },
            LinkStrings.decline().render(l) to Held(first.id, DeskChoice.DECLINE, System.currentTimeMillis() + TTL_MS)) else emptyList()
        synchronized(held) { shown = text to actions }
        post(ctx, CARDS_ID, title, text, actions)
    }

    private fun allowed(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channel(ctx)
        Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) && nm.areNotificationsEnabled() &&
            nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    } catch (t: Throwable) {
        RLog.w("link_notice_allowed", t)
        false
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, LinkStrings.channel().render(AppLanguage.langs(ctx)), NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }

    /** False: it isn't up. */
    private fun post(ctx: Context, id: Int, title: String, text: String?, actions: List<Pair<String, Held>>): Boolean {
        // F116: a lock that started while this pass ran; LinkShade has just taken these off and they wait for the end of it.
        if ((id == CARDS_ID || id == REPLIED_ID) && PhoneLockNow.on(ctx)) return false
        val open = PendingIntent.getActivity(ctx, id, Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
            .putExtra(AppPages.EXTRA, AppPages.LINK_REQUESTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val l = AppLanguage.langs(ctx)
        val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(LinkStrings.notePublic().render(l)).build()
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TTL_MS)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(public)
        val now = System.currentTimeMillis()
        actions.forEachIndexed { i, (label, h) ->
            val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } // l10n: not text (a random token)
            synchronized(held) { held.entries.removeAll { it.value.until <= now }; held[token] = h }
            val act = PendingIntent.getBroadcast(ctx, id * 10 + i, Intent(Cmd.ACTION).setPackage(ctx.packageName).putExtra("op", Cmd.LINK_ANSWER).putExtra(Cmd.EXTRA_KEY, token), // l10n: not text (an extra's key)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, label, act).setAuthenticationRequired(true).build())
        }
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, b.build()) } catch (t: Throwable) { RLog.w("link_notice_post", t); return false }
        // A lock that started as it went up: LinkShade may have looked before it was there.
        if ((id == CARDS_ID || id == REPLIED_ID) && PhoneLockNow.on(ctx)) { cancel(ctx, id); return false }
        return true
    }

    private fun cancel(ctx: Context, id: Int) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Throwable) {}
    }

    /** A card notification's Yes or Decline (Cmd.LINK_ANSWER with its token): answered through :link. Never while locked. */
    fun act(token: String?) {
        val ctx = app ?: return
        if (token == null) return
        worker.execute {
            // F99: first, so the notice and its token last until the phone is open.
            if (Assistant.locked(ctx)) return@execute
            val client = LinkClient(ctx)
            try { tap(ctx, token) { card, choice -> client.answer(card, choice) } } finally { client.close() }
        }
    }

    /**
     * The tap on [token], on the worker. Its token is spent only by an answer that went: when one doesn't, the notice says so and
     * keeps its buttons, so another tap tries again. True when it was answered.
     */
    internal fun tap(ctx: Context, token: String, answer: (String, DeskChoice) -> Boolean): Boolean {
        val h = synchronized(held) { held[token] } ?: return false
        if (h.until < System.currentTimeMillis()) { synchronized(held) { held.remove(token) }; return false }
        if (answer(h.card, h.choice)) {
            synchronized(held) { held.remove(token) }
            cancel(ctx, CARDS_ID)
            return true
        }
        val (text, actions) = synchronized(held) { shown } ?: (null to emptyList())
        post(ctx, CARDS_ID, LinkStrings.couldntAnswer().render(AppLanguage.langs(ctx)), text, actions)
        return false
    }

    /** The yes a notification's button can give: not a file, which you pick in Requests (a tap on the notice opens it). */
    internal fun buttonYes(yes: DeskYes?): DeskYes? = yes.takeIf { it != DeskYes.SEND_FILE }
}
