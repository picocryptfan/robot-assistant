package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.TermuxCalls
import app.heyrobot.core.TermuxCommandAction
import app.heyrobot.core.TermuxScriptAction
import app.heyrobot.core.ToolName
import app.heyrobot.core.termux.CommandAnalyzer
import app.heyrobot.core.termux.CommandLevel
import app.heyrobot.core.termux.CommandSource
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.ScriptMatch
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.ShellLexer
import app.heyrobot.core.termux.TermuxCardInfo
import app.heyrobot.core.termux.TermuxCards
import app.heyrobot.core.termux.TermuxDecision
import app.heyrobot.core.termux.TermuxFailure
import app.heyrobot.core.termux.TermuxIntents
import app.heyrobot.core.termux.TermuxOutcome
import app.heyrobot.core.termux.TermuxOutput
import app.heyrobot.core.termux.TermuxPolicy
import app.heyrobot.core.termux.TermuxReason
import app.heyrobot.core.termux.TermuxRun
import app.heyrobot.core.termux.TermuxRunKind
import app.heyrobot.core.termux.TermuxRunPlan
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.termux.TermuxSetup
import app.heyrobot.core.termux.TermuxSetupStatus
import app.heyrobot.core.termux.TermuxStatus
import app.heyrobot.core.termux.TermuxTap
import app.heyrobot.core.termux.TermuxVerdict
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.TermuxFacts
import app.heyrobot.sys.TermuxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// VOICE PROCESS. The Termux agent's tools (0.9, docs/plans/websearch-termux-design.md B5, B7, B8): which ones Gemma is told
// about, each call prepared through TermuxPolicy (Settings › Termux, the lock, rules 1 to 6) into a card or a refusal,
// and run through [TermuxRunner] once its card was answered: the wrapper under GNU timeout in its own process group, the
// result cleaned, masked and capped for the card and for Gemma. Settings › Termux's set-up runs (the Test, ~/robot and
// your scripts) run here too, where the result receiver is. Nothing about a command reaches a log.

object TermuxTool {
    @Volatile private var held: TermuxRunner? = null

    fun runner(ctx: Context): TermuxRunner = held ?: synchronized(this) {
        held ?: TermuxRunner(TermuxSend(ctx.applicationContext)).also { held = it }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ what Gemma is told

    /**
     * The Termux tools to describe now: none unless Settings › Termux is on and set up; termux_script when you have a
     * script that isn't Off (only those on "Works on the lock screen" while [locked]), termux_command unless Off (never
     * locked: rule 5).
     */
    fun tools(ctx: Context, locked: Boolean): Set<ToolName> {
        val s = TermuxStore.load(ctx)
        if (!s.enabled || s.trustedSigner == null) return emptySet()
        val scripts = scripts(ctx, s, locked)
        if (!TermuxFacts.ready(ctx, s)) return emptySet()
        return buildSet {
            if (scripts.isNotEmpty()) add(ToolName.TERMUX_SCRIPT)
            if (s.commands != CommandLevel.OFF && !locked) add(ToolName.TERMUX_COMMAND)
        }
    }

    /** The system prompt's Termux line for [tools] (null when none is described). */
    fun promptLine(ctx: Context, tools: Collection<ToolName>, locked: Boolean): String? {
        if (tools.none { it.termux }) return null
        val names = if (ToolName.TERMUX_SCRIPT in tools) scripts(ctx, TermuxStore.load(ctx), locked).map { it.name } else emptyList()
        return TermuxCalls.promptLine(names, ToolName.TERMUX_COMMAND in tools)
    }

    /** Your scripts Robot may run (listed by the last Refresh), without the ones set to Off. */
    private fun scripts(ctx: Context, s: TermuxSettings, locked: Boolean): List<TermuxScriptInfo> =
        TermuxStore.status(ctx).scripts.filter { s.script(it.name) != ScriptLevel.OFF && (!locked || it.name in s.lockScreenScripts) }

    /** "run backup", "lance mon script de sauvegarde": your script named in your own words, run without the model; null otherwise. */
    fun direct(ctx: Context, text: String, typed: Boolean): ScriptMatch? {
        val s = TermuxStore.load(ctx)
        if (!s.enabled || s.trustedSigner == null) return null
        return ScriptRegistry.match(text, scripts(ctx, s, locked = false), typed)
    }

    // ------------------------------------------------------------------ prepare (before the gate and the card)

    /**
     * A Termux call made ready for the gate: refused (in words) or Ready with its card's contents and the reason it shows
     * one ([AskWhy.TERMUX]: a yes may answer it; COMMAND or FROM_CONTENT: only a tap). [untrusted]: the conversation holds
     * content Robot didn't write; [remote]: a helper asked; [said]: your own words this turn (never screen text).
     */
    fun prepare(ctx: Context, action: Action, untrusted: Boolean, remote: Boolean, said: String?): Prepared {
        val f = facts
        val s = f.settings(ctx)
        if (!s.enabled) return Prepared.Refused(ActionStatus.DISABLED, TermuxReason.OFF.en)
        val check = f.check(ctx, s)
        if (check.status == TermuxStatus.CHANGED) return Prepared.Refused(ActionStatus.FAILED, TermuxFailure.NOT_TRUSTED.en)
        val granted = f.granted(ctx)
        if (!granted) return Prepared.Refused(ActionStatus.NEEDS_PERMISSION, TermuxFailure.NO_PERMISSION.en)
        val status = f.status(ctx)
        if (!TermuxSetup.ready(check, granted, status, s.trustedSigner)) return Prepared.Refused(ActionStatus.FAILED, TermuxFailure.NOT_READY.en)
        val locked = f.locked(ctx)
        val key = UUID.randomUUID().toString()
        return when (action) {
            is TermuxCommandAction -> {
                val source = if (remote) CommandSource.HELPER else action.source
                val analysis = CommandAnalyzer.analyze(action.command)
                val d = TermuxPolicy.command(analysis, source, s, locked, untrusted)
                refused(d) ?: ready(action.copy(source = source, key = key,
                    card = TermuxCards.command(analysis, d, action.why, s.limitSeconds, written = source == CommandSource.MODEL || source == CommandSource.HELPER)), d)
            }
            is TermuxScriptAction -> {
                val scripts = status.scripts
                val source = if (remote) CommandSource.HELPER else action.source
                val named = said?.let { ScriptRegistry.match(it, scripts, typed = true) }?.let { it.name == action.name && it.args.isBlank() } == true
                val d = TermuxPolicy.script(action.name, action.args, source, s, locked, untrusted, named && action.args.isBlank(), known = scripts.map { it.name }.toSet())
                if (TermuxReason.NOT_A_SCRIPT in d.reasons && d.verdict == TermuxVerdict.REFUSE) {
                    val yours = scripts.filter { s.script(it.name) != ScriptLevel.OFF }.joinToString(", ") { it.name }
                    return Prepared.Refused(ActionStatus.NOT_FOUND, TermuxReason.NOT_A_SCRIPT.en + if (yours.isEmpty()) " You have none yet." else " Yours: $yours.")
                }
                refused(d) ?: ready(action.copy(source = source, key = key, lockScreen = action.name in s.lockScreenScripts,
                    card = TermuxCards.script(action.name, ShellLexer.argv(action.args).orEmpty(), d, s.limitSeconds)), d)
            }
            else -> Prepared.Refused(ActionStatus.INVALID, "Not a Termux call.")
        }
    }

    /**
     * 1.0 audit (injection-termux-policy-unwired): the panel's three-argument prepare (TurnRunner's), so every Termux call
     * is prepared here, through TermuxPolicy, the lock, the signer and the permission, into its card or a refusal; [other]
     * prepares everything else. Without it a Termux call reached the gate with no reason and no card.
     */
    suspend fun prepareTurn(ctx: Context, action: Action, untrusted: Boolean, remote: Boolean, said: String?, other: suspend (Action) -> Prepared): Prepared =
        if (!action.tool.termux) other(action) else withContext(Dispatchers.IO) { prepare(ctx, action, untrusted, remote, said) }

    /** What [prepare] reads of the phone: Settings › Termux, the installed Termux against your signer, the permission, the set-up, the lock. */
    interface Facts {
        fun settings(ctx: Context): TermuxSettings
        fun check(ctx: Context, s: TermuxSettings): app.heyrobot.core.termux.TermuxCheck
        fun granted(ctx: Context): Boolean
        fun status(ctx: Context): TermuxSetupStatus
        fun locked(ctx: Context): Boolean
    }

    object PhoneFacts : Facts {
        override fun settings(ctx: Context) = TermuxStore.load(ctx)
        override fun check(ctx: Context, s: TermuxSettings) = TermuxFacts.check(ctx, s)
        override fun granted(ctx: Context) = TermuxFacts.granted(ctx)
        override fun status(ctx: Context) = TermuxStore.status(ctx)
        override fun locked(ctx: Context) = Assistant.locked(ctx)
    }

    /** The phone's own; a test's end-to-end run swaps in its own (nothing else sets it). */
    @Volatile internal var facts: Facts = PhoneFacts

    private fun refused(d: TermuxDecision): Prepared.Refused? {
        if (d.verdict != TermuxVerdict.REFUSE) return null
        val r = d.reasons.firstOrNull() ?: TermuxReason.EMPTY
        val status = when (r) {
            TermuxReason.OFF, TermuxReason.COMMANDS_OFF, TermuxReason.SCRIPT_OFF, TermuxReason.HELPERS_OFF, TermuxReason.API_OFF -> ActionStatus.DISABLED
            TermuxReason.LOCKED -> ActionStatus.NEEDS_UNLOCK
            TermuxReason.NOT_A_SCRIPT -> ActionStatus.NOT_FOUND
            TermuxReason.BAD_ARGUMENTS, TermuxReason.EMPTY, TermuxReason.TOO_LONG, TermuxReason.CANT_SEND -> ActionStatus.INVALID
            else -> ActionStatus.FAILED
        }
        RLog.i("termux_refused", "why" to r)
        return Prepared.Refused(status, d.say() ?: r.en)
    }

    private fun ready(a: Action, d: TermuxDecision): Prepared.Ready = when (d.verdict) {
        TermuxVerdict.RUN -> Prepared.Ready(a)
        TermuxVerdict.CARD -> Prepared.Ready(a, why = setOf(AskWhy.TERMUX))
        else -> Prepared.Ready(a, why = setOf(AskWhy.TERMUX, if (d.tap == TermuxTap.FROM_CONTENT) AskWhy.FROM_CONTENT else AskWhy.COMMAND), note = d.say())
    }

    // ------------------------------------------------------------------ the card's live state

    /** What a card asked for besides Run: an edited command, another limit for this run, or Open in Termux instead. */
    data class Edit(val command: String? = null, val limitSeconds: Int? = null, val terminal: Boolean = false)

    /** A card's state by its action's key: running, its outcome, what was edited. The last few only (a conversation's). */
    data class Live(val running: Boolean = false, val outcome: TermuxOutcome? = null, val edit: Edit? = null, val ranCommand: String? = null)

    private val live = object : LinkedHashMap<String, Live>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Live>): Boolean = size > 40
    }

    fun live(key: String): Live? = synchronized(live) { live[key] }

    private fun setLive(key: String, change: (Live) -> Live) {
        synchronized(live) { live[key] = change(live[key] ?: Live()) }
        PanelController.termuxChanged(key)
    }

    /** The card's Run with an edited command or another limit, or its Open in Termux: taken when the action runs. */
    fun edit(key: String, e: Edit) = setLive(key) { it.copy(edit = e) }

    /** New conversation: the cards' state goes. */
    fun forget() = synchronized(live) { live.clear() }

    fun keyOf(a: Action?): String? = (a as? TermuxCommandAction)?.key ?: (a as? TermuxScriptAction)?.key

    fun cardOf(a: Action?): TermuxCardInfo? = (a as? TermuxCommandAction)?.card ?: (a as? TermuxScriptAction)?.card

    // ------------------------------------------------------------------ running

    /**
     * Runs [action] (its card answered, or no card needed): the signer checked again first (B7.6), then the plan sent and
     * its result awaited. Stop cancels this coroutine: the runner stops the run's process tree. [launch] opens Termux for
     * Open in Termux.
     */
    suspend fun run(ctx: Context, action: Action, launch: (Intent) -> Boolean): Executed {
        val s = TermuxStore.load(ctx)
        val key = keyOf(action) ?: return Executed(ActionStatus.FAILED, "Something went wrong.")
        val card = cardOf(action) ?: return Executed(ActionStatus.FAILED, "Something went wrong.")
        val check = TermuxFacts.check(ctx, s)
        if (!check.usable || s.trustedSigner == null) return Executed(ActionStatus.FAILED, TermuxFailure.NOT_TRUSTED.en)
        val edit = live(key)?.edit
        val limit = TermuxSettings.limit(edit?.limitSeconds ?: card.limitSeconds)
        val runner = runner(ctx)
        val id = runner.newId()
        val plan: TermuxRunPlan = when (action) {
            is TermuxCommandAction -> {
                val command = edit?.command?.takeIf { it.isNotBlank() } ?: action.command
                if (command != action.command) {
                    // Edited on the card: yours now, read again; what is refused stays refused (a fork bomb, Robot's own key).
                    val d = TermuxPolicy.command(CommandAnalyzer.analyze(command), CommandSource.TYPED, s, Assistant.locked(ctx), untrustedConversation = false)
                    if (d.verdict == TermuxVerdict.REFUSE) return Executed(ActionStatus.FAILED, d.say() ?: TermuxReason.EMPTY.en)
                }
                if (edit?.terminal == true) return openInTermux(ctx, runner, id, command, launch)
                TermuxRun.command(id, command, limit, if (card.noCard) TermuxRunKind.ALLOWLISTED else TermuxRunKind.CARDED, profile = s.loadProfile)
            }
            is TermuxScriptAction -> {
                val argv = ShellLexer.argv(action.args) ?: return Executed(ActionStatus.INVALID, TermuxReason.BAD_ARGUMENTS.en)
                TermuxRun.script(id, action.name, argv, limit)
            }
            else -> return Executed(ActionStatus.FAILED, "Something went wrong.")
        }
        setLive(key) { it.copy(running = true) }
        val reply = try {
            runner.run(plan)
        } finally {
            setLive(key) { it.copy(running = false) }
        }
        val outcome = when (reply) {
            is TermuxReply.Answered -> TermuxOutcome(reply.result.exitCode, reply.ms,
                TermuxOutput.prepare(reply.result, mask = s.hideSecrets, maxLines = s.outputLines, maxChars = s.outputChars), limitSeconds = limit)
            is TermuxReply.Failed -> TermuxOutcome(null, reply.ms, failure = reply.failure, limitSeconds = limit)
        }
        setLive(key) { it.copy(outcome = outcome) }
        val said = TermuxCards.said(outcome)
        if (outcome.failure != null || outcome.exit == null) return Executed(ActionStatus.FAILED, said)
        return Executed(if (outcome.exit == 0) ActionStatus.DONE else ActionStatus.FAILED, said,
            // What it printed is content Robot didn't write (rule 3: later commands show their card), and personal.
            results = TermuxCards.forModel(outcome), spoken = spoken(said, outcome))
    }

    /** What Robot says without Gemma (a script you named): the result, and the output only when it is one or two short lines. */
    private fun spoken(said: String, o: TermuxOutcome): String {
        val lines = o.output?.model.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size in 1..2 && lines.all { it.length <= 80 }) said.removeSuffix(".") + ": " + lines.joinToString(". ") else said
    }

    /** A spoken sentence for a direct script run's result (the tool response's detail, and its short output). */
    fun spokenResult(response: Map<String, Any?>): String? {
        val detail = response["detail"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val out = ((response["results"] as? List<Map<String, Any?>>)?.firstOrNull()?.get("output") as? String).orEmpty()
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size in 1..2 && lines.all { it.length <= 80 }) detail.removeSuffix(".") + ": " + lines.joinToString(". ") + "." else detail
    }

    /** "Open in Termux": a new terminal session runs it (only its exit code comes back), then Termux opens over the panel. */
    private fun openInTermux(ctx: Context, runner: TermuxRunner, id: Int, command: String, launch: (Intent) -> Boolean): Executed {
        runner.fire(TermuxRun.terminal(id, command))?.let { return Executed(ActionStatus.FAILED, it.en) }
        val open = ctx.packageManager.getLaunchIntentForPackage(TermuxIntents.PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = open != null && try { launch(open) } catch (_: Exception) { false }
        return Executed(ActionStatus.DONE, if (opened) "Opened in Termux." else "Sent to a new Termux session: open Termux to see it.", launchedApp = opened)
    }

    // ------------------------------------------------------------------ Settings › Termux's set-up runs

    const val TEST = "test"
    const val LIST = "list"
    const val FOLDER = "folder"

    /**
     * Settings › Termux asked (Cmd.TERMUX): the Test, creating ~/robot, or listing your scripts. Fixed commands only (the
     * page showed them first); what they found goes to termux_status.json.
     */
    fun setup(ctx: Context, what: String?) {
        val app = ctx.applicationContext
        if (what !in setOf(TEST, LIST, FOLDER)) return
        TermuxStore.saveStatus(app, TermuxStore.status(app).copy(running = what, runningAt = System.currentTimeMillis()))
        scope.launch {
            val before = TermuxStore.status(app)
            val after = try {
                when (what) {
                    TEST -> test(app, before)
                    FOLDER -> list(app, before, makeFolder = true)
                    else -> list(app, before, makeFolder = false)
                }
            } catch (t: Throwable) {
                RLog.w("termux_setup", t)
                before
            }
            TermuxStore.saveStatus(app, after.copy(running = null, runningAt = 0))
        }
    }

    private suspend fun test(ctx: Context, st: TermuxSetupStatus): TermuxSetupStatus {
        val s = TermuxStore.load(ctx)
        val now = System.currentTimeMillis()
        val check = TermuxFacts.check(ctx, s)
        fun failed(f: TermuxFailure) = st.copy(testAt = now, testOk = false, testMs = 0, testFailure = f, timeout = false, signer = check.signer)
        if (!check.usable && check.status != TermuxStatus.UNKNOWN_SIGNER) return failed(if (check.status == TermuxStatus.CHANGED) TermuxFailure.NOT_TRUSTED else TermuxFailure.NOT_INSTALLED)
        if (!TermuxFacts.granted(ctx)) return failed(TermuxFailure.NO_PERMISSION)
        val runner = runner(ctx)
        return when (val r = runner.run(TermuxRun.test(runner.newId()))) {
            is TermuxReply.Failed -> failed(r.failure)
            is TermuxReply.Answered -> {
                val ok = TermuxSetup.testOk(r.result.stdout)
                st.copy(testAt = now, testOk = ok, testMs = r.ms, testFailure = if (ok) null else TermuxFailure.NOT_SET_UP,
                    timeout = TermuxSetup.timeoutFound(r.result.stdout), signer = check.signer)
            }
        }
    }

    private suspend fun list(ctx: Context, st: TermuxSetupStatus, makeFolder: Boolean): TermuxSetupStatus {
        val now = System.currentTimeMillis()
        if (!TermuxFacts.granted(ctx)) return st.copy(listAt = now, listFailure = TermuxFailure.NO_PERMISSION)
        val runner = runner(ctx)
        if (makeFolder) {
            val r = runner.run(TermuxRun.command(runner.newId(), ScriptRegistry.MAKE_FOLDER, SETUP_LIMIT_S, TermuxRunKind.SETUP))
            if (r is TermuxReply.Failed) return st.copy(listAt = now, listFailure = r.failure)
        }
        return when (val r = runner.run(TermuxRun.command(runner.newId(), ScriptRegistry.LIST_SCRIPT, SETUP_LIMIT_S, TermuxRunKind.SETUP))) {
            is TermuxReply.Failed -> st.copy(listAt = now, listFailure = r.failure)
            is TermuxReply.Answered -> {
                val l = ScriptRegistry.parse(r.result.stdout.orEmpty())
                st.copy(listAt = now, folderMissing = l.folderMissing, scripts = l.scripts, listFailure = if (r.result.exitCode == 0 || l.complete) null else TermuxFailure.INTERNAL)
            }
        }
    }

    private const val SETUP_LIMIT_S = 15
}
