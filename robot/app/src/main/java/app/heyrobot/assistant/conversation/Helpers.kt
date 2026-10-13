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
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ConsentCard
import app.heyrobot.core.Disclosure
import app.heyrobot.core.Helper
import app.heyrobot.core.HelperGate
import app.heyrobot.core.HelperListener
import app.heyrobot.core.HelperResult
import app.heyrobot.core.HelperRoute
import app.heyrobot.core.RLog
import app.heyrobot.core.SummaryCost
import app.heyrobot.core.SummaryJob
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.helpers.Protocol
import app.heyrobot.sys.HandOffIntents
import app.heyrobot.sys.HelperSpendStore
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS (helpers' keys and clients live here only). "Summarize with a stronger AI" (docs/conversation-mode.md
// 7.2, phase 5.6): a helper's API (one question to a transcript-only session: its own prompt, no tools, no search,
// 16,000 tokens of room) and a helper's app (a share you send yourself). Both act only on a SummaryJob, which only your
// Send on the consent card makes (HelperGate checks it again before anything leaves). 1.0.0: any helper you set up,
// through its own client, its own model and its own price (the class names are from when Claude was the only one).
// Nothing here logs content.

/** A helper's API ([h]): Robot gets the summary back and keeps it with the transcript. [chars]: what the card sends. */
class ClaudeApiHelper(private val ctx: Context, val h: HelperConfig, chars: Int, budgetUsed: Boolean = false) : Helper {
    override val id: String get() = idFor(h)
    override val title: String get() = "${h.name} (API)"
    /** The card's line, with the price estimated from the text's length and the helper's own price (SummaryCost). */
    override val disclosure: Disclosure = Disclosure.api(h, SummaryCost.line(h, chars, Instant.now()), budgetUsed)
    override val route: HelperRoute get() = HelperRoute.API

    override fun maxInputTokens(): Int = maxInputTokens(h)

    /** Actual spend so far, in dollars (from the stream's usage). */
    var spent = 0.0
        private set

    /** Robot could tell what it cost (false: the helper's price isn't known; nothing is counted). */
    var costKnown = true
        private set

    override suspend fun summarize(job: SummaryJob, listener: HelperListener): HelperResult {
        if (!HelperGate.allowed(job) || job.helper != id) return HelperResult.Failed("Nothing was sent: Robot needs your OK on the card.")
        // More than one request takes: it would leave the phone only to be refused there.
        if (!ConsentCard.apiFits(job.blocks, maxInputTokens())) return HelperResult.Failed("Nothing was sent. " + ConsentCard.TOO_LONG_FOR_API)
        if (!HelperLink.networkAllowed(ctx)) return HelperResult.Failed(ClaudeFailure.NO_INTERNET.text)
        val session = withContext(Dispatchers.IO) { HelperLink.transcriptSession(ctx, h) } ?: return HelperResult.Failed(noKey())
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
                    // The daily budget for all helpers, and this helper's own cap, count it (it warns; it never blocks what you asked for).
                    try { HelperSpendStore.add(ctx, h.id, dollars) } catch (t: Throwable) { RLog.w("summary_spend", t) }
                }
            })
            costKnown = session.lastReport.costKnown
            return when (out) {
                is ClaudeOutcome.Done -> if (text.isBlank()) HelperResult.Failed("${h.name} sent nothing back.")
                    else HelperResult.Done(text.toString().trim(), h.model, (spent * 1_000_000).toLong())
                ClaudeOutcome.Declined -> HelperResult.Failed("${h.name} declined to summarize this conversation.")
                is ClaudeOutcome.Failed -> HelperResult.Failed(out.failure.say(HelperWho.of(h)))
                // No tools were offered: a call can't come; if it did, nothing runs.
                is ClaudeOutcome.Calls -> HelperResult.Failed("${h.name} answered with something Robot can't use.")
            }
        } finally {
            HelperLink.done(session)
        }
    }

    /** Why there was no session: no key, one that couldn't be read just now (kept), or one that was removed. */
    private fun noKey(): String {
        val w = HelperWords(HelperWho.of(h))
        return when {
            HelperLink.unavailable(h.id) -> w.keyUnavailable
            HelperLink.unreadable(h.id) -> w.keyUnreadable
            else -> ClaudeFailure.NO_KEY.say(HelperWho.of(h))
        }
    }

    companion object {
        /** Claude's id as before 1.0.0 (its log code and saved jobs); another helper's from its own id. */
        const val ID = "claude_api"
        fun idFor(h: HelperConfig): String = if (h.id == HelperConfig.CLAUDE_ID) ID else "api_${h.id}"
        const val MAX_TOKENS = 16_000L
        /** A transcript of up to about 150,000 tokens goes to Claude in one request (about 10 hours of talk). */
        const val MAX_INPUT_TOKENS = 150_000
        /** Another company's model or your server: Robot doesn't know its context, so about 100,000 (about 6 hours of talk). */
        const val OTHER_INPUT_TOKENS = 100_000

        fun maxInputTokens(h: HelperConfig): Int = if (h.presetOf.protocol == Protocol.ANTHROPIC) MAX_INPUT_TOKENS else OTHER_INPUT_TOKENS
    }
}

/** A helper's app ([h], one with an app): the transcript goes in a share; you press Send there, and Robot doesn't hear the answer. */
class ClaudeAppHelper(private val ctx: Context, val h: HelperConfig) : Helper {
    private val pkg: String = h.presetOf.app.orEmpty()
    private val appName: String = h.presetOf.defaultName
    override val id: String get() = idFor(h)
    override val title: String get() = "$appName app"
    override val disclosure: Disclosure = if (pkg == ClaudeAppLink.PACKAGE) Disclosure.claudeApp() else Disclosure.app(appName)
    override val route: HelperRoute get() = HelperRoute.APP_SHARE

    override fun maxInputTokens(): Int = ConsentCard.APP_MAX_CHARS / 3

    override suspend fun summarize(job: SummaryJob, listener: HelperListener): HelperResult = HelperResult.Failed("The $appName app gets a share, not a request.")

    /**
     * The share for [job], or the clipboard and the app's launch when the share isn't taken; null when it is too long
     * for the app (the card offers export instead) or the app isn't installed.
     */
    fun intent(job: SummaryJob): Pair<Intent, AppShareCode>? {
        if (!HelperGate.allowed(job) || job.helper != id) return null
        if (pkg.isEmpty() || job.appText.length > ConsentCard.APP_MAX_CHARS || !HandOffIntents.installed(ctx, pkg)) return null
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, job.appText).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (HandOffIntents.resolves(ctx, pkg, ClaudeAppLink.Way.SEND)) return send to AppShareCode.SHARED
        // Fallback: the clipboard (marked sensitive: other people's words), then the app; you paste it there.
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return null
        try {
            val clip = ClipData.newPlainText("Conversation for $appName", job.appText)
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
        fun idFor(h: HelperConfig): String = if (h.id == HelperConfig.CLAUDE_ID) ID else "app_${h.id}"
    }
}
