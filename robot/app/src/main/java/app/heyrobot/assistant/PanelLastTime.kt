// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.os.PowerManager
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.DayRange
import app.heyrobot.core.RLog
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.lasttime.LastAsk
import app.heyrobot.core.lasttime.LastCandidate
import app.heyrobot.core.lasttime.LastFound
import app.heyrobot.core.lasttime.LastRead
import app.heyrobot.core.lasttime.LastSource
import app.heyrobot.core.lasttime.LastTime
import app.heyrobot.core.lasttime.LastTimeRouter
import app.heyrobot.core.lasttime.LastTimeVectors
import app.heyrobot.core.memory.EmbedRole
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.FileDeletes
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.RobotFiles
import app.heyrobot.sys.SearchModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// "When did I last …" (docs/plans/last-time-plan.md; core LastTime): Files' receipts and bills, past calendar events and
// notes, read here only while the phone is unlocked, matched on the phone (the search model's vectors when it's there,
// words always), never a model call. Every candidate gets its vector once, in the background after a question
// (LastTimeVectors, in memory only), so an older one is found by meaning too; a question embeds only itself. Nothing of
// what's read goes in a log: counts only.
object PanelLastTime {
    /** The question's vector waits at most this long; past it the request stops and words answer alone. */
    private const val EMBED_MS = 3_000L
    /** How far back the calendar is read. */
    const val CALENDAR_YEARS = 3L

    private val embedder by lazy { BrainEmbedder(VoiceHub.app) }
    private val vectors = LastTimeVectors()
    private val busy = AtomicBoolean(false)
    /** Questions being answered now: the rebuild waits for them. */
    private val asking = AtomicInteger(0)
    /** How often a paused rebuild looks again, and how long it waits in all before it stops until the next question. */
    private const val PAUSE_STEP_MS = 2_000L
    private const val PAUSE_MAX_MS = 600_000L

    fun route(q: String): LastAsk? = LastTimeRouter.parse(q)

    suspend fun handle(replyId: String, ask: LastAsk, host: PanelRoutines.Host) {
        val l = host.langs(null, ask.lang)
        val p = withContext(Dispatchers.IO) { answer(VoiceHub.app, ask, ZonedDateTime.now()) }
        host.text(replyId, p.render(l))
        host.say(p.render(l.voice), personal = true)
    }

    fun answer(ctx: Context, ask: LastAsk, now: ZonedDateTime): Phrase {
        val today = now.toLocalDate()
        return LastTime.respondRead(Assistant.locked(ctx), today) {
            asking.incrementAndGet()
            val model = SearchModelStore.verifiedPath(ctx) != null
            var past = emptyList<LastCandidate>()
            val found = try {
                val (got, cs) = try { read(ctx, now) } catch (t: Throwable) { RLog.w("last_time_read", t); LastFound(emptyList(), LastRead(unread = LastSource.entries.toSet() - LastSource.LOGBOOK)) to emptyList<LastCandidate>() }
                past = cs.filter { !it.day.isAfter(today) }
                val q = if (model && vectors.size > 0) query(ask.what) else null
                val hits = LastTime.match(ask.what, q, past, if (q == null) emptyMap() else vectors.known(past), today, said = ask.said)
                RLog.i("last_time", "candidates" to cs.size, "hits" to hits.size, "vectors" to (q != null), "calendar" to got.read.calendar, "files" to got.read.files)
                LastFound(hits, got.read)
            } finally {
                asking.decrementAndGet()
            }
            if (model) catchUp(ctx, past) else vectors.clear()
            found
        }
    }

    /** The rebuild runs only while no question is being answered, the phone is unlocked and cool, and battery saver is off. */
    internal fun rebuildMay(locked: Boolean, thermal: Int, powerSave: Boolean, asking: Boolean): Boolean =
        !locked && !asking && !powerSave && thermal < PowerManager.THERMAL_STATUS_MODERATE

    private fun rebuildMay(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        return rebuildMay(Assistant.locked(ctx), pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE, pm?.isPowerSaveMode == true, asking.get() > 0)
    }

    /** The day an event is on: an all-day one is stored at UTC midnight and keeps that date in every zone. */
    internal fun eventDay(begin: Long, allDay: Boolean, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

    /**
     * The candidates from what could be read: [receipts] null when Files isn't set up, [events] null without the
     * calendar permission; and which sources those were. A timed event counts once it has begun (by [now]), so one later
     * today isn't "today" yet; an all-day one keeps its date and is judged by it.
     */
    internal fun gather(receipts: List<LastCandidate>?, events: List<CalendarEvent>?, notes: List<LastCandidate>, zone: ZoneId, now: Long = System.currentTimeMillis(), unread: Set<LastSource> = emptySet()): Pair<LastFound, List<LastCandidate>> {
        val out = ArrayList<LastCandidate>()
        receipts?.let { out += it }
        events?.forEach { ev -> if (ev.title.isNotBlank() && (ev.allDay || ev.begin <= now)) out += LastCandidate(LastSource.CALENDAR, "${ev.id}-${ev.begin}", ev.title, ev.location.orEmpty(), eventDay(ev.begin, ev.allDay, zone)) }
        out += notes
        return LastFound(emptyList(), LastRead(files = receipts != null, calendar = events != null, unread = unread)) to out
    }

    /** Files as read: true read, false never set up (said as such), null couldn't be read now (locked, busy, damaged, newer). */
    internal fun filesState(load: IndexLoad): Boolean? = when (load) {
        is IndexLoad.Loaded -> true
        IndexLoad.Empty -> false
        else -> null
    }

    private fun read(ctx: Context, now: ZonedDateTime): Pair<LastFound, List<LastCandidate>> {
        val zone = ZoneId.systemDefault()
        val unread = HashSet<LastSource>()
        /** One source read; one that throws is named as unread, not counted as searched. */
        fun <T> source(s: LastSource, f: () -> T): T? = try { f() } catch (t: Throwable) { RLog.w("last_time_read_${s.name.lowercase()}", t); unread += s; null }
        // Files never set up: null, said as such; an index that can't be loaded now: unread.
        val load = source(LastSource.RECEIPT) { RobotFiles.shownLoad(ctx) }
        val receipts: List<LastCandidate>? = if (load == null) emptyList() else when (filesState(load.first)) {
            false -> null
            null -> { unread += LastSource.RECEIPT; emptyList() }
            true -> load.second?.let { idx ->
                source(LastSource.RECEIPT) {
                    val gone = FileDeletes.here.hidden()
                    idx.entries.mapNotNull { e ->
                        if (e.kind != FileKind.RECEIPT && e.kind != FileKind.BILL || e.id in gone || e.damaged || e.name.isBlank()) return@mapNotNull null
                        val day = FileViews.dateField(e, "date") ?: return@mapNotNull null
                        LastCandidate(LastSource.RECEIPT, e.id.hex, e.name, (e.tags + e.text.take(500)).joinToString(" "), day)
                    }
                }
            } ?: emptyList()
        }
        // A day past now, so today's all-day event (from UTC midnight, later than now east of UTC) is read; days after today never answer.
        val events = if (Calendars.readGranted(ctx)) source(LastSource.CALENDAR) { Calendars.events(ctx, DayRange(now.minusYears(CALENDAR_YEARS), now.plusDays(1))) } ?: emptyList() else null
        val notes = source(LastSource.NOTE) {
            NotesFiles.store(ctx).list().mapNotNull { n ->
                val title = n.displayTitle.ifBlank { return@mapNotNull null }
                LastCandidate(LastSource.NOTE, n.id, title, n.text.take(500), Instant.ofEpochMilli(n.created).atZone(zone).toLocalDate())
            }
        } ?: emptyList()
        return gather(receipts, events, notes, zone, now.toInstant().toEpochMilli(), unread)
    }

    /** One short text; the time limit cancels the request itself (the embedder's lock and call both stop on it). */
    private fun query(what: String): FloatArray? = try {
        runBlocking { withTimeoutOrNull(EMBED_MS) { embedder.embed(listOf(what), EmbedRole.QUERY)?.singleOrNull() } }
    } catch (t: Throwable) { RLog.w("last_time_embed", t); null }

    /**
     * After the answer, at low priority: the candidates without a vector get one, 8 per :brain request and at most
     * MAX_BATCHES requests per question; the gone are dropped. It pauses between requests for a question, heat, battery
     * saver or the lock, and carries on once that has passed (PAUSE_MAX_MS in all at most).
     */
    private fun catchUp(ctx: Context, cs: List<LastCandidate>) {
        if (!busy.compareAndSet(false, true)) return
        Thread({
            try {
                var ran = 0
                var n = 0
                var waited = 0L
                while (ran < LastTimeVectors.MAX_BATCHES) {
                    var paused = false
                    n += runBlocking {
                        vectors.refresh(cs, embedder, maxBatches = LastTimeVectors.MAX_BATCHES - ran) {
                            if (rebuildMay(ctx)) { ran++; true } else { paused = true; false }
                        }
                    }
                    if (!paused) break
                    while (!rebuildMay(ctx) && waited < PAUSE_MAX_MS) { Thread.sleep(PAUSE_STEP_MS); waited += PAUSE_STEP_MS }
                    if (!rebuildMay(ctx)) break
                }
                RLog.i("last_time_index", "embedded" to n, "size" to vectors.size, "batches" to ran, "paused_ms" to waited)
            } catch (t: Throwable) {
                RLog.w("last_time_index", t)
            } finally {
                busy.set(false)
            }
        }, "robot-lasttime-index").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
