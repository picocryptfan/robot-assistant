package app.heyrobot.assistant

import android.Manifest
import android.content.Context
import app.heyrobot.claude.ClaudeClient
import app.heyrobot.claude.ClaudeSession
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudePrompt
import app.heyrobot.core.ClaudeTools
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFacts
import app.heyrobot.core.helpers.Protocol
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.http.HelperHttp
import app.heyrobot.helpers.openai.OpenAiSession
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.HelperFactsStore
import app.heyrobot.sys.HelperKeyFiles
import app.heyrobot.sys.HelperSpendStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

// VOICE PROCESS. The panel's way to its helpers (the :claude module): one client per helper and saved key (and, for a
// server, its address), and a session per question or per conversation ("go get …"). A client stays open while a session
// still uses it, so saving a new key doesn't break a conversation under way; it closes when its last session ends.
// Keys are decrypted here only (HelperKeys.read), just before a client is made. Nothing here logs content.

object HelperLink {
    /** A helper's client and how many sessions use it; [retired]: its key or address changed, or it was removed. */
    private class Held(val claude: ClaudeClient?, val http: HelperHttp?, val stamp: Long, val address: String?) {
        var users = 0
        var retired = false

        fun close() {
            claude?.close()
            http?.close()
        }
    }

    private val current = HashMap<String, Held>()
    private val sessions = IdentityHashMap<HelperSession, Held>()

    /** Whether each helper's key is saved, kept in memory: this process saves keys, and the main app says when it deleted one. */
    private val saved = ConcurrentHashMap<String, Boolean>()

    /** Helpers whose saved key couldn't be decrypted (Android's keystore lost its key): it was removed, and Robot says why. */
    private val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun keySaved(ctx: Context, id: String): Boolean = saved[id] ?: HelperKeyFiles.saved(ctx, id).also { saved[id] = it }

    fun keySaved(ctx: Context, h: HelperConfig?): Boolean = h != null && keySaved(ctx, h.id)

    fun unreadable(id: String): Boolean = id in unreadable

    /** Background thread: reads again whether each helper's key is saved. */
    fun refresh(ctx: Context, s: RobotSettings) {
        for (h in s.helpers) refresh(ctx, h.id)
    }

    private fun refresh(ctx: Context, id: String) {
        val now = HelperKeyFiles.saved(ctx, id)
        saved[id] = now
        if (now) unreadable.remove(id)
    }

    /**
     * Background thread: helper [id]'s key was saved here or deleted by the main app (or the helper was removed). Its
     * old client goes as soon as no session uses it (a deleted key's conversation ends first: PanelController.helperKeyChanged).
     */
    @Synchronized
    fun keyChanged(ctx: Context, id: String) {
        refresh(ctx, id)
        current.remove(id)?.let(::retire)
    }

    private fun retire(h: Held) {
        h.retired = true
        if (h.users == 0) close(h)
    }

    /** Closing a client shuts its connections: never on the main thread. */
    private fun close(h: Held) {
        Thread({ h.close() }, "robot-helper-close").apply { isDaemon = true; start() }
    }

    /** A new session, or why there can't be one. */
    sealed class Made {
        class Ok(val session: HelperSession) : Made()
        class Not(val failure: ClaudeFailure) : Made()
    }

    /**
     * A new conversation with [h] ([conversation]: "go get …"), or why not: no key where one is needed (or it couldn't be
     * read), a server whose port Test connection hasn't found, or an address Robot won't use. Background thread.
     */
    @Synchronized
    fun session(ctx: Context, s: RobotSettings, h: HelperConfig, conversation: Boolean): Made {
        val facts = HelperFactsStore.load(ctx, h.id)
        val held = when (val r = held(ctx, h, facts)) {
            is Made.Not -> return r
            else -> current.getValue(h.id)
        }
        held.users++
        val tools = tools(s, h, facts)
        val session: HelperSession = if (h.presetOf.protocol == Protocol.ANTHROPIC) {
            ClaudeSession(held.claude!!, ClaudeModel.of(h.model), tools, h.searching, conversation)
        } else {
            OpenAiSession(held.http!!, h, tools, conversation, vision = h.catalogModel?.vision ?: true, dropped = facts.dropped(h.model),
                onDropped = { e -> Thread({ HelperFactsStore.update(ctx, h.id) { it.withDropped(h.model, e) } }, "robot-helper-facts").start() })
        }
        sessions[session] = held
        return Made.Ok(session)
    }

    /**
     * 0.5.0 (docs/conversation-mode.md 7.2): a session with Claude's API (your Claude key) for one transcript summary, with
     * its own fixed prompt, no tools and no web search, or null without a key. Only the summary screen's Claude helper asks
     * for one, after your Send on its card. Background thread.
     */
    @Synchronized
    fun transcriptSession(ctx: Context, model: ClaudeModel): ClaudeSession? {
        val h = HelperConfig.claude()
        if (held(ctx, h, HelperFactsStore.load(ctx, h.id)) is Made.Not) return null
        val held = current.getValue(h.id)
        held.users++
        return ClaudeSession(held.claude!!, model, emptyList(), search = false, conversation = false, system = ClaudePrompt.TRANSCRIPT_SYSTEM)
            .also { sessions[it] = held }
    }

    /** Robot's actions for [h]: none when you turned them off for it, or its model can't take them (the server said so). */
    fun tools(s: RobotSettings, h: HelperConfig, facts: HelperFacts): List<ToolSpec> = when {
        !h.actions -> emptyList()
        h.catalogModel?.tools == false -> emptyList()
        facts.noActions(h.model) -> emptyList()
        else -> ClaudeTools.tools(s)
    }

    /** The client for [h], made (its key decrypted) when there is none or its key or address changed. */
    private fun held(ctx: Context, h: HelperConfig, facts: HelperFacts): Made? {
        val needsKey = h.presetOf.keyRequired
        val stamp = HelperKeys.stamp(ctx, h.id)
        if (stamp == 0L && needsKey) {
            saved[h.id] = false
            current.remove(h.id)?.let(::retire)
            return Made.Not(ClaudeFailure.NO_KEY)
        }
        val address = if (h.presetOf.protocol == Protocol.ANTHROPIC) null else facts.address(h) ?: return Made.Not(ClaudeFailure.NO_ADDRESS)
        current[h.id]?.takeIf { it.stamp == stamp && it.address == address }?.let { return null }
        current.remove(h.id)?.let(::retire)
        val key = if (stamp == 0L) null else HelperKeys.read(ctx, h.id)
        if (stamp != 0L && key == null) {
            // It reads as no key from now on, and Settings says why.
            HelperKeys.forget(ctx, h.id)
            saved[h.id] = false
            unreadable += h.id
            RLog.i("helper_key", "op" to RLog.Code("unreadable"))
            if (needsKey) return Made.Not(ClaudeFailure.NO_KEY)
        }
        val made = if (h.presetOf.protocol == Protocol.ANTHROPIC) Held(ClaudeClient.create(key!!), null, stamp, null)
        else Held(null, HelperHttp.create(address!!, key, h.home) ?: return Made.Not(ClaudeFailure.NOT_LOCAL), stamp, address)
        current[h.id] = made
        return null
    }

    /** The panel is done with [session] (a one-shot answered, a conversation ended). */
    @Synchronized
    fun done(session: HelperSession?) {
        val h = sessions.remove(session ?: return) ?: return
        h.users--
        if (h.retired && h.users == 0) close(h)
    }

    /** GrapheneOS's per-app Network toggle revokes INTERNET: then Robot says so instead of "No internet". */
    fun networkAllowed(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.INTERNET)

    /**
     * Today's budget for all helpers, or [h]'s own cap, is used up. Kept in memory once read (this process is the only
     * writer); the first read is on IO.
     */
    suspend fun budgetReached(ctx: Context, s: RobotSettings, h: HelperConfig?): Boolean {
        val spend = HelperSpendStore.cached() ?: withContext(Dispatchers.IO) { HelperSpendStore.current(ctx) }
        return spend.reached(LocalDate.now(), s.helperBudgetCents, h?.id, h?.budgetCents)
    }

    @Volatile private var warmed = false

    /**
     * The panel opened: whether keys are saved and today's spend are read off the main thread, and with Claude's API
     * ready the SDK's classes load (no network), once.
     */
    fun warm(ctx: Context, s: RobotSettings) {
        Thread({
            refresh(ctx, s)
            HelperSpendStore.current(ctx)
            val c = s.claude
            if (!warmed && c != null && c.on && saved[c.id] == true) {
                warmed = true
                ClaudeClient.warm()
            }
        }, "robot-helper-warm").apply { isDaemon = true; start() }
    }
}
