// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.FeedbackPrefill
import app.heyrobot.core.feedback.KeptTurn
import app.heyrobot.core.feedback.ReportTurn
import app.heyrobot.core.feedback.ReportTurnRef
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.feedback.SentReport
import app.heyrobot.core.feedback.SentReports
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Main and voice processes (0.7). Send feedback's files and its ways out. feedback.json holds the number "Text it" opens
// your SMS app with (the main app writes it; not settings.json). The panel hands what you said after a feedback phrase to
// the page through feedback_prefill.json, a file of Robot's own, and opens the app with the kind only. Out, only what you
// tap: Android's share sheet (you pick the app and the person), the clipboard (marked sensitive, cleared 60 s on if it
// still holds the message), your SMS app with the number filled in, or a GitHub issue page in your browser. Robot sends
// nothing itself, has no network code here, and never logs the message: the voice log gets its kind, length and which
// diagnostics it holds.

object FeedbackFiles {
    private fun prefsFile(ctx: Context) = File(Paths.dir(ctx), FeedbackPrefs.FILE)
    private fun prefillFile(ctx: Context) = File(Paths.dir(ctx), FeedbackPrefill.FILE)

    /** [MAIN] The page's own settings (the number to text). */
    fun prefs(ctx: Context): FeedbackPrefs = FeedbackPrefs.fromJson(readOrNull(prefsFile(ctx)))

    /** [MAIN] Background thread. */
    fun savePrefs(ctx: Context, p: FeedbackPrefs) {
        try { writeAtomic(prefsFile(ctx), p.toJson()) } catch (t: Throwable) { RLog.w("feedback_prefs_save", t) }
    }

    /** [VOICE] What you said after the phrase, for the page to start your text with; nothing when you said nothing more. */
    fun prefill(ctx: Context, kind: FeedbackKind, text: String?) {
        val f = prefillFile(ctx)
        try {
            if (text.isNullOrBlank()) f.delete() else writeAtomic(f, FeedbackPrefill(kind, text.take(FeedbackPrefill.MAX_TEXT), System.currentTimeMillis()).toJson())
        } catch (t: Throwable) { RLog.w("feedback_prefill_save", t) }
    }

    /** [MAIN] The panel's words, once and only while fresh: the file is deleted as it is read. */
    fun takePrefill(ctx: Context, now: Long = System.currentTimeMillis()): FeedbackPrefill? {
        val f = prefillFile(ctx)
        val p = FeedbackPrefill.fromJson(readOrNull(f))
        f.delete()
        return p?.takeIf { it.fresh(now) }
    }

    private fun turnFile(ctx: Context) = File(Paths.dir(ctx), ReportTurn.FILE)

    /** [VOICE] 1.4: the turn a report is about, kept 10 minutes for the card (locked too: it opens after you unlock). */
    /**
     * fix-bug-card-14-b: an earlier build's screenshot files, deleted unread: the screenshot now goes to the card in memory only,
     * with the turn's hand ([TurnHand]), and is never written.
     */
    private val oldShotFiles = listOf("report_shot.png", "report_shot.app", "report_shot.tmp") // l10n: not text (file names)

    private fun sentFile(ctx: Context) = File(Paths.dir(ctx), SentReports.FILE)
    private val sentLock = Any()

    /** [MAIN] RP5: Reports you sent, newest first (reference, date, kind, parts; never contents). */
    fun sent(ctx: Context): List<SentReport> = synchronized(sentLock) { SentReports.fromJson(readOrNull(sentFile(ctx))) }

    /** [MAIN] RP5: a report Send handed on, put first in the list (the last 50 kept). */
    fun addSent(ctx: Context, r: SentReport) = synchronized(sentLock) {
        try { writeAtomic(sentFile(ctx), SentReports.toJson(SentReports.add(sent(ctx), r))) } catch (t: Throwable) { RLog.w("reports_sent_save", t) }
    }

    /** [MAIN] RP5: Delete all on Reports you sent. */
    fun clearSent(ctx: Context): Boolean = synchronized(sentLock) { sentFile(ctx).let { !it.exists() || it.delete() } }

    /**
     * fix-seal-14 M1: only where the card finds the turn in History (ReportTurnRef), never its words, which stay in History's
     * vault; read from there when the card opens.
     */
    fun keepTurn(ctx: Context, t: ReportTurn?) {
        val f = turnFile(ctx)
        // fix-bug-card-14-b L-a: with a random token the turn's hand must give back, so no other app's Binder passes for it.
        val ref = t?.let { ReportTurnRef.of(it, ReportTurnRef.newToken()) }
        kept = ref
        try { if (ref == null) f.delete() else writeAtomic(f, ref.toJson()) } catch (t: Throwable) { RLog.w("report_turn_save", t) }
        // Gone 10 awake minutes on even if the page never opens (the uptime clock stops in deep sleep: the card and the hand
        // check the turn's age themselves).
        if (t != null) dropLater(ctx)
    }

    /** [VOICE] The ref last kept: its token goes with the hand for that same turn only. */
    @Volatile private var kept: ReportTurnRef? = null

    private fun dropLater(ctx: Context) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ Thread { dropStaleTurn(app) }.start() }, ReportTurn.FRESH_MS + 1_000)
    }

    /** [MAIN, VOICE] The kept turn deleted once it isn't fresh: 10 minutes after it's kept, and at each start of main and :voice (a reboot too). */
    fun dropStaleTurn(ctx: Context, now: Long = System.currentTimeMillis()) {
        try {
            val f = turnFile(ctx)
            if (f.exists() && ReportTurnRef.fromJson(readOrNull(f))?.fresh(now) != true) f.delete()
            oldShotFiles.forEach { File(Paths.dir(ctx), it).delete() }
        } catch (t: Throwable) { RLog.w("report_turn_drop", t) }
    }

    /**
     * [MAIN] The kept turn, once and only while fresh (the file deleted as it's read, an earlier build's too), read from History
     * now: [KeptTurn.turn] null when History can't give it (locked, deleted, never saved), so the report says it isn't there.
     */
    fun takeTurn(ctx: Context, now: Long = System.currentTimeMillis()): KeptTurn? = takeTurnRef(ctx, now)?.let { KeptTurn(turnFrom(ctx, it)) }

    /** [MAIN] fix-bug-card-14 M2: where the kept turn is (no words), once and only while fresh; the file deleted as it's read. */
    fun takeTurnRef(ctx: Context, now: Long = System.currentTimeMillis()): ReportTurnRef? {
        val f = turnFile(ctx)
        val r = ReportTurnRef.fromJson(readOrNull(f))
        f.delete()
        return r?.takeIf { it.fresh(now) }
    }

    /**
     * [MAIN] fix-bug-card-14 M2: the turn [r] points to, read from History now: when the card opens, and again after Android
     * recreates it (the card keeps only [r] in its saved state, never the words). Null when History can't give it.
     */
    fun turnFrom(ctx: Context, r: ReportTurnRef): ReportTurn? {
        val turns = try { r.conv?.let { ConversationStore.load(ctx, it) }?.turns } catch (t: Throwable) { RLog.w("report_turn_read", t); null }
        return turns?.let(r::resolve)
    }

    /**
     * [VOICE] Robot's app on Send feedback with [kind] (the words, if any, are in the prefill file). fix-bug-card-14 M3: with
     * [turn], the turn also goes with it in memory (a [TurnHand] in the panel's process, never on disk or in saved state),
     * for when History has none: Save conversations off, a conversation that isn't kept, or History not yet written.
     */
    fun openIntent(ctx: Context, kind: FeedbackKind, turn: ReportTurn? = null, shot: ByteArray? = null, shotApp: String? = null): Intent =
        HandOffApps.robotPage(ctx, AppPages.FEEDBACK).putExtra(AppPages.EXTRA_FEEDBACK_KIND, kind.name)
            .also { i -> if (turn != null) hand(turn, shot, shotApp)?.let { h -> i.putExtras(android.os.Bundle().apply { putBinder(HAND, h) }) } }

    private const val HAND = "app.heyrobot.feedback.TURN_HAND" // l10n: not text (an extra's key)
    @Volatile private var handed: TurnHand? = null

    /**
     * [VOICE] One turn handed over at a time, with the screenshot [shot] if any, forgotten 10 minutes on (or when the card sends
     * it, or a newer one comes). None without the token of the ref kept for this turn: fails closed.
     */
    private fun hand(turn: ReportTurn, shot: ByteArray?, shotApp: String?): TurnHand? = synchronized(this) {
        handed?.forget()
        handed = null
        val token = kept?.takeIf { it.at == turn.at }?.token ?: return null
        TurnHand(turn, token, shot, shotApp).also { h -> handed = h; Handler(Looper.getMainLooper()).postDelayed({ h.forget() }, ReportTurn.FRESH_MS) }
    }

    /**
     * [MAIN] L-a: one call to the hand in [i], on a thread of its own and capped at [HAND_CAP_MS], so another app's Binder
     * put under the same key can't stall the card; null when it doesn't answer in time.
     */
    private fun <T> ask(i: Intent?, code: Int, read: (android.os.Parcel) -> T?): T? {
        val b = i?.extras?.getBinder(HAND) ?: return null
        // fix-bug-card-14-c Low 3: a Binder call can't be interrupted, so at most HAND_THREADS wait on one that never answers.
        if (asking.incrementAndGet() > HAND_THREADS) { asking.decrementAndGet(); return null }
        val task = java.util.concurrent.FutureTask {
            val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
            try { if (b.transact(code, data, reply, 0)) read(reply) else null } finally { data.recycle(); reply.recycle() }
        }
        Thread({ try { task.run() } finally { asking.decrementAndGet() } }, "report-hand").apply { isDaemon = true }.start() // l10n: not text (a thread's name)
        return try { task.get(HAND_CAP_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (t: Throwable) { task.cancel(true); RLog.w("report_turn_hand", t); null }
    }

    private const val HAND_CAP_MS = 2_000L
    private const val HAND_THREADS = 2
    private val asking = java.util.concurrent.atomic.AtomicInteger()
    private const val SHOT_MAX = 64 shl 20

    /** The hand gave back the ref's own token (L-a): compared in constant time. */
    private fun ours(got: String?, token: String?) = got != null && token != null &&
        java.security.MessageDigest.isEqual(got.toByteArray(), token.toByteArray())

    /** [MAIN] The turn the panel handed over with [i], while fresh and only with [token]; null with none, the panel's process gone, or forgotten. */
    private fun handedTurn(i: Intent?, token: String?): ReportTurn? =
        ask(i, TurnHand.TAKE) { p -> if (ours(p.readString(), token)) ReportTurn.fromJson(p.readString()) else null }

    /**
     * [MAIN] fix-bug-card-14-b: the screenshot the panel handed over with [i] for [r] (the PNG and its app's package), read from
     * a read-only SharedMemory into the card's memory; null with none, another token, or forgotten. Never on disk.
     */
    fun cardShot(r: ReportTurnRef, i: Intent?): Pair<ByteArray, String?>? = ask(i, TurnHand.SHOT) { p ->
        if (!ours(p.readString(), r.token)) return@ask null
        val app = p.readString()?.take(255)
        if (p.readInt() != 1) return@ask null
        val m = android.os.SharedMemory.CREATOR.createFromParcel(p)
        try {
            if (m.size !in 1..SHOT_MAX) null else m.mapReadOnly().let { b ->
                try { ByteArray(b.remaining()).also { b.get(it) } to app } finally { android.os.SharedMemory.unmap(b) }
            }
        } finally { closeShot(m) }
    }

    /** fix-bug-card-14-c: a screenshot's region closed, through [shotCloser] (a test counts the closes there; a JVM's close() throws). */
    internal fun closeShot(m: android.os.SharedMemory?) { m?.let(shotCloser) }
    @Volatile internal var shotCloser: (android.os.SharedMemory) -> Unit = { it.close() }

    /**
     * [MAIN] fix-bug-card-14-c Low 1: the card left without sending (Back, another page): the hand forgets the turn and the
     * screenshot now, not 10 minutes on. A rotation ([recreating]) isn't leaving.
     */
    fun leftCard(i: Intent?, recreating: Boolean) { if (!recreating) { dropHanded(i); if (i != null && taken === i) taken = null } }

    /** [MAIN] fix-bug-card-14-c Low 2: the hand Robot itself launched the card with, taken out of its intent by MainActivity. */
    @Volatile private var taken: Intent? = null

    /**
     * [MAIN] MainActivity, on each intent it gets: a hand in [i] is taken out (no page reads one from an intent), and kept
     * only when [fromUid], who launched it, is Robot itself. Another app's Binder is never called, and can't swap Robot's.
     */
    fun takeHand(i: Intent?, fromUid: Int) {
        val b = try { i?.extras?.getBinder(HAND) } catch (t: Throwable) { RLog.w("report_turn_hand", t); null } ?: return
        i?.removeExtra(HAND)
        if (fromUid == android.os.Process.myUid()) taken = Intent().putExtras(android.os.Bundle().apply { putBinder(HAND, b) })
    }

    /** [MAIN] The hand the card reads when it opens or is recreated; null when Robot didn't launch it with one. */
    fun cardHand(): Intent? = taken

    /** [MAIN] The panel forgets the turn it handed over with [i] (the report went). */
    fun dropHanded(i: Intent?) {
        try {
            i?.extras?.getBinder(HAND)?.let { b ->
                val data = android.os.Parcel.obtain()
                try { b.transact(TurnHand.FORGET, data, null, android.os.IBinder.FLAG_ONEWAY) } finally { data.recycle() }
            }
        } catch (t: Throwable) { RLog.w("report_turn_hand", t) }
    }

    /**
     * [MAIN] fix-bug-card-14-b L-c: the card recreated by Android with [refJson] still fresh: the turn read again from History or
     * the hand; [KeptTurn.turn] null when neither gives it (the panel's process gone), so the report says it isn't there.
     */
    fun restoredTurn(ctx: Context, refJson: String?, i: Intent?, now: Long = System.currentTimeMillis()): KeptTurn? =
        ReportTurnRef.fromJson(refJson)?.takeIf { it.fresh(now) }?.let { KeptTurn(cardTurn(ctx, it, i)) }

    /** [MAIN] The card's turn for [r]: History's, else the one handed over with [i] for this same turn. */
    fun cardTurn(ctx: Context, r: ReportTurnRef, i: Intent?): ReportTurn? = turnFrom(ctx, r) ?: handedTurn(i, r.token)?.takeIf { it.at == r.at }

    /** [MAIN] A feedback event in the main process's voice log (codes and numbers only). */
    fun log(ctx: Context, e: VoiceEvent, a: Int, b: Int = 0, c: Int = 0) {
        try {
            if (!android.app.Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(ctx))
            VoiceLog.add(e, a, b, c)
            VoiceLog.flush()
        } catch (t: Throwable) { RLog.w("feedback_log", t) }
    }
}

/** [MAIN] The ways a message leaves Robot, each one you tap: none sends anything by itself. */
object FeedbackSend {
    /** Android's share sheet with the message as plain text (no subject: an SMS app would turn it into an MMS), [title] over it. */
    fun shareIntent(text: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** "Text it": your SMS app with [number] and the message filled in; you press Send there. */
    fun textIntent(number: String, text: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A GitHub issue page in your browser (core builds the link). */
    fun browserIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts [i]; false when no app takes it. */
    fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (t: Exception) {
        RLog.w("feedback_start", t)
        false
    }
}

/**
 * [MAIN] Copy: the message on the clipboard, marked sensitive (Android hides its preview), and cleared [CLEAR_MS] later if
 * the clipboard still holds it. Android lets an app read the clipboard only while it has the focus: when the time comes
 * with Robot in the background, the check waits for Robot's window to have the focus again (MainActivity).
 */
object FeedbackClipboard {
    const val CLEAR_MS = 60_000L

    /** The text copied and when, until it is cleared or replaced. */
    @Volatile private var pending: Pair<String, Long>? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun copy(ctx: Context, text: String) {
        val app = ctx.applicationContext
        val clip = ClipData.newPlainText("Robot feedback", text) // l10n: not text (the clip's label, which Android doesn't show)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        pending = text to System.currentTimeMillis()
        main.postDelayed({ check(app) }, CLEAR_MS)
    }

    /** Clears the clipboard once [CLEAR_MS] have passed if it still holds the message; forgets it if something else replaced it. */
    fun check(ctx: Context, now: Long = System.currentTimeMillis()) {
        val (text, at) = pending ?: return
        if (now - at < CLEAR_MS) return
        try {
            val cm = ctx.applicationContext.getSystemService(ClipboardManager::class.java) ?: return
            // Without the focus Android gives no clip (null): wait for the focus.
            if (!cm.hasPrimaryClip()) return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(ctx)?.toString() == text) cm.clearPrimaryClip()
            pending = null
        } catch (t: Throwable) {
            RLog.w("feedback_clip", t)
        }
    }
}

/**
 * fix-bug-card-14 M3: the panel's turn, held in the panel's process (:voice) for the card and given over Binder only to
 * Robot's own uid, while fresh. Nothing is written: the intent that carries it holds only this object's handle.
 */
internal class TurnHand(
    turn: ReportTurn,
    private val token: String,
    png: ByteArray?,
    private val app: String?,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : android.os.Binder() {
    private val made = clock()
    @Volatile private var turn: ReportTurn? = turn
    // fix-bug-card-14-b: the panel's screenshot, held here (never written) and handed over read-only.
    private var shot: android.os.SharedMemory? = png?.let { share(it) }

    fun forget() = synchronized(this) {
        turn = null
        val s = shot
        shot = null
        FeedbackFiles.closeShot(s)
    }

    /** L-b: 10 minutes by the clock that counts deep sleep, checked on each call, as the posted forget runs only once awake. */
    private fun fresh(): Boolean = (clock() - made in 0..ReportTurn.FRESH_MS).also { if (!it) forget() }

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean = when (code) {
        TAKE -> synchronized(this) {
            val ours = getCallingUid() == android.os.Process.myUid() && fresh()
            val t = turn?.takeIf { ours && System.currentTimeMillis() - it.at in 0..ReportTurn.FRESH_MS }
            reply?.writeString(token.takeIf { t != null })
            reply?.writeString(t?.toJson())
            true
        }
        SHOT -> synchronized(this) {
            val ours = getCallingUid() == android.os.Process.myUid() && fresh()
            val s = shot?.takeIf { ours }
            reply?.writeString(token.takeIf { s != null })
            reply?.writeString(app.takeIf { s != null })
            reply?.writeInt(if (s != null) 1 else 0)
            if (reply != null) s?.writeToParcel(reply, 0)
            true
        }
        FORGET -> { forget(); true }
        else -> super.onTransact(code, data, reply, flags)
    }

    companion object {
        const val TAKE = FIRST_CALL_TRANSACTION
        const val FORGET = FIRST_CALL_TRANSACTION + 1
        const val SHOT = FIRST_CALL_TRANSACTION + 2

        /** The PNG in a region of its own, read-only before any parcel. fix-bug-card-14-c Low 4: one whose setup fails is closed at once. */
        internal fun share(png: ByteArray, seal: (android.os.SharedMemory) -> Unit = { it.setProtect(android.system.OsConstants.PROT_READ) }): android.os.SharedMemory? {
            val m = try { android.os.SharedMemory.create("report_shot", png.size) } catch (t: Throwable) { RLog.w("report_shot_share", t); return null } // l10n: not text (a memory region's name)
            return try {
                val b = m.mapReadWrite()
                try { b.put(png) } finally { android.os.SharedMemory.unmap(b) }
                seal(m)
                m
            } catch (t: Throwable) { RLog.w("report_shot_share", t); FeedbackFiles.closeShot(m); null }
        }
    }
}
