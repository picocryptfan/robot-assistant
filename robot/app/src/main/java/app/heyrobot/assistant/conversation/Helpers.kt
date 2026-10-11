package app.heyrobot.assistant.conversation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import app.heyrobot.assistant.HelperLink
import app.heyrobot.claude.ClaudeListener
import app.heyrobot.claude.ClaudeOutcome
import app.heyrobot.core.AppShareCode
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeEffort
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ConsentCard
import app.heyrobot.core.Disclosure
import app.heyrobot.core.Helper
import app.heyrobot.core.HelperCost
import app.heyrobot.core.HelperGate
import app.heyrobot.core.HelperListener
import app.heyrobot.core.HelperResult
import app.heyrobot.core.HelperRoute
import app.heyrobot.core.RLog
import app.heyrobot.core.SummaryJob
import app.heyrobot.sys.ClaudeAppIntents
import app.heyrobot.sys.HelperSpendStore
import app.heyrobot.core.helpers.HelperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS (the Claude key and the SDK live here only). "Summarize with a stronger AI" (docs/conversation-mode.md
// 7.2, phase 5.6): the Claude API helper (one question to a transcript-only session: its own prompt, no tools, no
// search, medium effort, 16,000 tokens of room) and the Claude app helper (a share you send yourself). Both act only on
// a SummaryJob, which only your Send on the consent card makes (HelperGate checks it again before anything leaves).
// 0.6.0 adds other helpers behind the same card and interface. Nothing here logs content.

/** The Claude API: Robot gets the summary back and keeps it with the transcript. */
class ClaudeApiHelper(private val ctx: Context, private val model: ClaudeModel, chars: Int) : Helper {
    override val id: String get() = ID
    override val title: String get() = "Claude (API)"
    /** The card's line, with the price estimated from the text's length (HelperCost). */
    override val disclosure: Disclosure = Disclosure.claudeApi(model, HelperCost.estimate(model, chars))
    override val route: HelperRoute get() = HelperRoute.API

    override fun maxInputTokens(): Int = MAX_INPUT_TOKENS

    /** Actual spend so far, in dollars (from the stream's usage). */
    var spent = 0.0
        private set

    override suspend fun summarize(job: SummaryJob, listener: HelperListener): HelperResult {
        if (!HelperGate.allowed(job) || job.helper != ID) return HelperResult.Failed("Nothing was sent: Robot needs your OK on the card.")
        // More than one request takes: it would leave the phone only to be refused there.
        if (!ConsentCard.apiFits(job.blocks, maxInputTokens())) return HelperResult.Failed("Nothing was sent. " + ConsentCard.TOO_LONG_FOR_API)
        if (!HelperLink.networkAllowed(ctx)) return HelperResult.Failed(ClaudeFailure.NO_INTERNET.text)
        val session = withContext(Dispatchers.IO) { HelperLink.transcriptSession(ctx, model) } ?: return HelperResult.Failed(ClaudeFailure.NO_KEY.text)
        val text = StringBuilder()
        try {
            val out = session.ask(job.blocks, ClaudePlan(ClaudeEffort.MEDIUM, MAX_TOKENS), object : ClaudeListener {
                override fun text(delta: String) {
                    text.append(delta)
                    listener.text(text.toString())
                }

                override fun declined() { text.setLength(0) }

                override fun spent(dollars: Double) {
                    spent += dollars
                    // The daily budget for all helpers, and Claude's own cap, count it (it warns; it never blocks what you asked for).
                    try { HelperSpendStore.add(ctx, HelperConfig.CLAUDE_ID, dollars) } catch (t: Throwable) { RLog.w("summary_spend", t) }
                }
            })
            return when (out) {
                is ClaudeOutcome.Done -> if (text.isBlank()) HelperResult.Failed("Claude sent nothing back.")
                    else HelperResult.Done(text.toString().trim(), model.id, (spent * 1_000_000).toLong())
                ClaudeOutcome.Declined -> HelperResult.Failed("Claude declined to summarize this conversation.")
                is ClaudeOutcome.Failed -> HelperResult.Failed(out.failure.text)
                // No tools were offered: a call can't come; if it did, nothing runs.
                is ClaudeOutcome.Calls -> HelperResult.Failed("Claude answered with something Robot can't use.")
            }
        } finally {
            HelperLink.done(session)
        }
    }

    companion object {
        const val ID = "claude_api"
        const val MAX_TOKENS = 16_000L
        /** A transcript of up to about 150,000 tokens goes in one request (about 10 hours of talk). */
        const val MAX_INPUT_TOKENS = 150_000
    }
}

/** The Claude app: the transcript goes in a share; you press Send there, and Robot doesn't hear the answer. */
class ClaudeAppHelper(private val ctx: Context) : Helper {
    override val id: String get() = ID
    override val title: String get() = "Claude app"
    override val disclosure: Disclosure = Disclosure.claudeApp()
    override val route: HelperRoute get() = HelperRoute.APP_SHARE

    override fun maxInputTokens(): Int = ConsentCard.APP_MAX_CHARS / 3

    override suspend fun summarize(job: SummaryJob, listener: HelperListener): HelperResult = HelperResult.Failed("The Claude app gets a share, not a request.")

    /**
     * The share for [job], or the clipboard and the app's launch when the share isn't taken; null when it is too long
     * for the app (the card offers export instead) or the app isn't installed.
     */
    fun intent(job: SummaryJob): Pair<Intent, AppShareCode>? {
        if (!HelperGate.allowed(job) || job.helper != ID) return null
        if (job.appText.length > ConsentCard.APP_MAX_CHARS || !ClaudeAppIntents.installed(ctx)) return null
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, job.appText).setPackage(ClaudeAppLink.PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ClaudeAppIntents.resolves(ctx, ClaudeAppLink.Way.SEND)) return send to AppShareCode.SHARED
        // Fallback: the clipboard (marked sensitive: other people's words), then the app; you paste it there.
        val launch = ctx.packageManager.getLaunchIntentForPackage(ClaudeAppLink.PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return null
        try {
            val clip = ClipData.newPlainText("Conversation for Claude", job.appText)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        } catch (t: Throwable) {
            RLog.w("summary_clip", t)
            return null
        }
        return launch to AppShareCode.CLIPBOARD
    }

    companion object {
        const val ID = "claude_app"
    }
}
