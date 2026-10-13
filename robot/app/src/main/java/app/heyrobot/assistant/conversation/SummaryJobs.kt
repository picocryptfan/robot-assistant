package app.heyrobot.assistant.conversation

import android.content.Context
import android.os.SystemClock
import app.heyrobot.assistant.BrainKeeper
import app.heyrobot.assistant.BrainOneShots
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.ConsentCard
import app.heyrobot.core.ConversationSettings
import app.heyrobot.core.DoneCode
import app.heyrobot.core.HelperCode
import app.heyrobot.core.HelperListener
import app.heyrobot.core.HelperResult
import app.heyrobot.core.LocalSummary
import app.heyrobot.core.RLog
import app.heyrobot.core.SummaryBudget
import app.heyrobot.core.SummaryCheckFor
import app.heyrobot.core.SummaryDraft
import app.heyrobot.core.SummaryMiss
import app.heyrobot.core.SummaryPlanner
import app.heyrobot.core.SummaryRecord
import app.heyrobot.core.HelperEnd
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.Transcripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

// VOICE PROCESS. Summaries being made (docs/conversation-mode.md 7, phase 5.6), one at a time, outliving the summary
// screen (turning the phone, leaving and coming back): Robot's own (core LocalSummary over :brain's one-shot requests at
// SUMMARY priority, with the model held loaded meanwhile, its notes kept as they come so a stopped one goes on), and the
// chosen helper's API after your Send on the card (1.0.0: any helper with its API; Claude's before). Each result is saved in the transcript's folder as summary-<n>.json and
// checked for numbers, names and times the transcript doesn't have. The voice log gets counts and codes only.

object SummaryJobs {
    data class Ui(
        val id: String? = null,
        val running: Boolean = false,
        /** Robot's own: part [step] of about [steps] ([merging]: the notes are being merged). */
        val step: Int = 0,
        val steps: Int = 0,
        val merging: Boolean = false,
        /** A helper's answer as it streams. */
        val streaming: String = "",
        val helper: Boolean = false,
        /** 1.0.0: the helper's name while it summarizes ("ChatGPT is summarizing…"). */
        val helperName: String = "",
        /** The last summary made, its number in the folder, and what the number check found. */
        val record: SummaryRecord? = null,
        val number: Int? = null,
        val misses: List<SummaryMiss> = emptyList(),
        val error: String? = null,
        /** The estimated price of the last helper request, and what it cost ([costKnown] false: the helper's price isn't known). */
        val spentDollars: Double = 0.0,
        val costKnown: Boolean = true,
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private const val MODEL_HOLD = "summary"

    val running: Boolean get() = job?.isActive == true

    /** Robot's own summary of transcript [id]. */
    @Synchronized
    fun local(ctx: Context, id: String) {
        if (running) return
        val app = ctx.applicationContext
        _state.value = Ui(id = id, running = true)
        job = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            var parts = 0
            var code = DoneCode.OTHER
            BrainKeeper.hold(MODEL_HOLD)
            try {
                val store = Transcripts.store(app)
                val meta = store.meta(id)
                val t = store.load(id)
                if (meta == null || t == null) return@launch fail("This transcript was deleted.")
                val zone = ZoneId.systemDefault()
                val lang = ConversationSettings.summaryLang(VoiceHub.settings)
                val lines = SummaryPlanner.lines(meta, t, zone)
                // The loaded model's context sets the parts (0.4.3: a short-context model gets smaller ones).
                BrainKeeper.ensureLoaded(app) ?: return@launch fail(BrainKeeper.state.value.problem ?: "Robot's model isn't ready.")
                val st = BrainKeeper.state.value
                val maxTokens = st.contextTokens
                val key = SummaryDraft.key(lines, lang, maxTokens)
                val draft = store.draft(id)?.takeIf { it.key == key }
                val notes = draft?.notes?.toMutableList() ?: ArrayList()
                parts = SummaryPlanner.plan(lines, lang, maxTokens).parts
                val r = LocalSummary(BrainOneShots, SummaryBudget.of(st.backend)).run(lines, lang, maxTokens, draft?.notes.orEmpty(),
                    onProgress = { p -> _state.update { it.copy(step = p.step, steps = p.steps, merging = p.merging) } },
                    onNote = { k, n ->
                        while (notes.size <= k) notes += null
                        notes[k] = n
                        store.saveDraft(id, SummaryDraft(key, notes))
                    })
                when (r) {
                    is LocalSummary.Outcome.Done -> {
                        val rec = SummaryRecord("robot", (st.model ?: BrainKeeper.modelHere(app)).id, SummaryRecord.VIA_PHONE, System.currentTimeMillis(),
                            r.text, parts = r.parts)
                        val n = store.addSummary(id, rec) ?: return@launch fail("The summary couldn't be saved on this phone.")
                        store.clearDraft(id)
                        val misses = SummaryCheckFor.misses(r.text, meta, t, zone, people(app))
                        code = DoneCode.END
                        _state.update { it.copy(running = false, record = rec, number = n, misses = misses) }
                    }
                    is LocalSummary.Outcome.Failed -> fail(r.why + if (notes.any { it != null }) " What was done is kept: try again to go on." else "")
                }
            } catch (e: CancellationException) {
                code = DoneCode.CANCELLED
                _state.update { it.copy(running = false, error = "Stopped. What was done is kept: summarize again to go on.") }
                throw e
            } catch (t: Throwable) {
                RLog.w("summary_local", t)
                fail("Robot's summary ran into a problem.")
            } finally {
                BrainKeeper.releaseHold(MODEL_HOLD)
                // Event 113: parts, seconds, how it ended. Never a word of it.
                VoiceLog.add(VoiceEvent.SUMMARY_LOCAL, parts, ((SystemClock.elapsedRealtime() - t0) / 1000).toInt(), code.id)
                RLog.i("summary_local", "parts" to parts, "code" to code)
            }
        }
    }

    /** Your Send on [card]: [helper] summarizes it (the API route). The words it sends are exactly the card's. */
    @Synchronized
    fun send(ctx: Context, card: ConsentCard, helper: ApiHelper) {
        if (running) return
        val app = ctx.applicationContext
        val job = card.approve(System.currentTimeMillis())
        val h = helper.h
        _state.value = Ui(id = card.transcriptId, running = true, helper = true, helperName = h.name)
        this.job = scope.launch {
            var end = HelperEnd.OTHER
            try {
                val r = helper.summarize(job, object : HelperListener {
                    override fun text(soFar: String) { _state.update { it.copy(streaming = soFar) } }
                })
                when (r) {
                    is HelperResult.Done -> {
                        val rec = SummaryRecord(r.by, r.by, SummaryRecord.VIA_API, System.currentTimeMillis(), r.text, consent = true, sentAt = job.approvedAt,
                            helperName = h.name, company = helper.disclosure.provider)
                        val store = Transcripts.store(app)
                        val n = store.addSummary(card.transcriptId, rec)
                        val meta = store.meta(card.transcriptId)
                        val t = store.load(card.transcriptId)
                        val misses = if (meta != null && t != null && !card.anonymized) SummaryCheckFor.misses(r.text, meta, t, ZoneId.systemDefault(), people(app))
                            else emptyList()
                        end = HelperEnd.END
                        _state.update { it.copy(running = false, record = rec, number = n, misses = misses, spentDollars = helper.spent, costKnown = helper.costKnown,
                            error = if (n == null) "${h.name}'s summary came, but it couldn't be saved on this phone." else null) }
                    }
                    is HelperResult.Failed -> _state.update { it.copy(running = false, error = r.reason, spentDollars = helper.spent, costKnown = helper.costKnown) }
                    is HelperResult.Shared -> _state.update { it.copy(running = false) }
                }
            } catch (e: CancellationException) {
                end = HelperEnd.STOPPED
                _state.update { it.copy(running = false, error = "Stopped.", spentDollars = helper.spent, costKnown = helper.costKnown) }
                throw e
            } finally {
                // Event 114: which helper, words ÷ 100, how it ended.
                VoiceLog.add(VoiceEvent.SUMMARY_HELPER, (if (helper.id == ApiHelper.ID) HelperCode.CLAUDE_API else HelperCode.HELPER_API).id, card.words / 100, end.id)
            }
        }
    }

    /** A helper's app got the transcript (or the clipboard did): logged, nothing kept. */
    fun shared(card: ConsentCard, how: app.heyrobot.core.AppShareCode) {
        VoiceLog.add(VoiceEvent.SUMMARY_HELPER, (if (card.helper == AppShareHelper.ID) HelperCode.CLAUDE_APP else HelperCode.HELPER_APP).id, card.words / 100, how.id)
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * The app deleted transcript [id] (null: all of them; Cmd.TRANSCRIPTS_DELETED): a summary of it being made stops
     * (Robot's model reads no more of it, Claude's answer isn't waited for), and what was made of it is forgotten.
     */
    @Synchronized
    fun deleted(id: String?) {
        if (id != null && _state.value.id != id) return
        job?.cancel()
        job = null
        _state.value = Ui()
    }

    /** The screen moved on to another transcript, or closed after the job ended: forget the last result. */
    fun clear(id: String?) {
        if (!running && _state.value.id != id) _state.value = Ui(id = id)
    }

    private fun fail(why: String) = _state.update { it.copy(running = false, error = why) }

    /** People's names and what you call them back a name in a summary (the number check). */
    private fun people(ctx: Context): List<String> = try {
        PeopleFiles.store(ctx).list().flatMap { listOf(it.name) + it.aliases + it.nicknames }
    } catch (_: Throwable) { emptyList() }
}
