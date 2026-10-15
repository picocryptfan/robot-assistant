// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.Manifest
import android.content.Context
import app.heyrobot.helpers.anthropic.AnthropicClient
import app.heyrobot.helpers.anthropic.AnthropicSession
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.HelperPrompt
import app.heyrobot.core.HelperTools
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SecretRead
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

// VOICE PROCESS. The panel's way to its helpers (the :helpers module): one client per helper and saved key (and, for a
// server, its address), and a session per question or per conversation ("go get …"). A client stays open while a session
// still uses it, so saving a new key doesn't break a conversation under way; it closes when its last session ends.
// Keys are decrypted here only (HelperKeys.read), just before a client is made; one the Keystore can't read just now is
// kept, and the question says so. Nothing here logs content.

object HelperLink {
    /** A helper's client and how many sessions use it; [retired]: its key or address changed, or it was removed. */
    private class Held(val anthropic: AnthropicClient?, val http: HelperHttp?, val stamp: Long, val address: String?) {
        var users = 0
        var retired = false

        fun close() {
            anthropic?.close()
            http?.close()
        }
    }

    private val current = HashMap<String, Held>()
    private val sessions = IdentityHashMap<HelperSession, Held>()

    /** Whether each helper's key is saved, kept in memory: this process saves keys, and the main app says when it deleted one. */
    private val saved = ConcurrentHashMap<String, Boolean>()

    /** Helpers whose saved key could never be decrypted again (Android's keystore lost its key): it was removed, and Robot says why. */
    private val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Helpers whose saved key couldn't be read just now (a passing Keystore error) on the last try: it is kept, file and
     * Keystore key, Robot says so, and the next question reads it again.
     */
    private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun keySaved(ctx: Context, id: String): Boolean = saved[id] ?: HelperKeyFiles.saved(ctx, id).also { saved[id] = it }

    fun keySaved(ctx: Context, h: HelperConfig?): Boolean = h != null && keySaved(ctx, h.id)

    fun unreadable(id: String): Boolean = id in unreadable

    fun unavailable(id: String): Boolean = id in unavailable

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
        class Not(val failure: HelperFailure) : Made()
    }

    /**
     * A new conversation with [h] ([conversation]: "go get …"), or why not: no key where one is needed (or it could never
     * be read again, [unreadable]), a saved key that couldn't be read just now ([unavailable]; NO_KEY too, never a request
     * without it), a server whose port Test connection hasn't found, or an address Robot won't use. Background thread.
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
            AnthropicSession(held.anthropic!!, AnthropicModel.of(h.model), tools, h.searching, conversation)
        } else {
            OpenAiSession(held.http!!, h, tools, conversation, vision = h.catalogModel?.vision ?: true, dropped = facts.dropped(h.model),
                onDropped = { e -> Thread({ HelperFactsStore.update(ctx, h.id) { it.withDropped(h.model, e) } }, "robot-helper-facts").start() })
        }
        sessions[session] = held
        return Made.Ok(session)
    }

    /**
     * 0.5.0 (docs/conversation-mode.md 7.2): a session with a helper's API for one transcript summary, with its own fixed
     * prompt, no tools and no web search, or null without a key (1.0.0: any helper with its API, through its own client
     * and the model chosen for it; Claude's before). Only the summary screen's helper asks for one, after your Send on its
     * card. Background thread.
     */
    @Synchronized
    fun transcriptSession(ctx: Context, h: HelperConfig): HelperSession? {
        val facts = HelperFactsStore.load(ctx, h.id)
        if (held(ctx, h, facts) is Made.Not) return null
        val held = current.getValue(h.id)
        held.users++
        val session: HelperSession = if (h.presetOf.protocol == Protocol.ANTHROPIC) {
            AnthropicSession(held.anthropic!!, AnthropicModel.of(h.model), emptyList(), search = false, conversation = false, system = HelperPrompt.CLAUDE_TRANSCRIPT_SYSTEM)
        } else {
            OpenAiSession(held.http!!, h.copy(search = false), emptyList(), conversation = false, vision = false, dropped = facts.dropped(h.model),
                system = HelperPrompt.transcriptSystem(h.name))
        }
        sessions[session] = held
        return session
    }

    /** Robot's actions for [h]: none when you turned them off for it, or its model can't take them (the server said so). */
    fun tools(s: RobotSettings, h: HelperConfig, facts: HelperFacts): List<ToolSpec> = when {
        !h.actions -> emptyList()
        h.catalogModel?.tools == false -> emptyList()
        facts.noActions(h.model) -> emptyList()
        else -> HelperTools.tools(s)
    }

    /** The client for [h], made (its key decrypted) when there is none or its key or address changed. */
    private fun held(ctx: Context, h: HelperConfig, facts: HelperFacts): Made? {
        unavailable.remove(h.id)
        val needsKey = h.presetOf.keyRequired
        val stamp = HelperKeys.stamp(ctx, h.id)
        if (stamp == 0L && needsKey) {
            saved[h.id] = false
            current.remove(h.id)?.let(::retire)
            return Made.Not(HelperFailure.NO_KEY)
        }
        val address = if (h.presetOf.protocol == Protocol.ANTHROPIC) null else facts.address(h) ?: return Made.Not(HelperFailure.NO_ADDRESS)
        current[h.id]?.takeIf { it.stamp == stamp && it.address == address }?.let { return null }
        current.remove(h.id)?.let(::retire)
        val key = if (stamp == 0L) null else when (val r = HelperKeys.read(ctx, h.id)) {
            is SecretRead.Present -> r.value
            // Deleted just now.
            SecretRead.Absent -> {
                saved[h.id] = false
                if (needsKey) return Made.Not(HelperFailure.NO_KEY)
                null
            }
            is SecretRead.Invalidated -> {
                // Removed as it was read: it reads as no key from now on, and Settings says why.
                saved[h.id] = false
                unreadable += h.id
                RLog.i("helper_key", "op" to RLog.Code("unreadable"))
                if (needsKey) return Made.Not(HelperFailure.NO_KEY)
                null
            }
            is SecretRead.Unavailable -> {
                // Kept, file and Keystore key: this question says so (PanelController.noKey), and the next one reads it
                // again. Nothing goes to the helper without the key you saved, even one that may not need it.
                unavailable += h.id
                RLog.i("helper_key", "op" to RLog.Code("unavailable"))
                return Made.Not(HelperFailure.NO_KEY)
            }
        }
        val made = if (h.presetOf.protocol == Protocol.ANTHROPIC) Held(AnthropicClient.create(key!!), null, stamp, null)
        // 1.0.1: a key saved for an http:// address is kept, and refused here with why (HelperFailure.KEY_OVER_HTTP).
        else Held(null, HelperHttp.create(address!!, key, h.home) ?: return Made.Not(HelperHttp.refusal(address, key, h.home) ?: HelperFailure.NOT_LOCAL),
            stamp, address)
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
     * The panel opened: whether keys are saved and today's spend are read off the main thread, and with the Claude helper's API
     * ready the Anthropic SDK's classes load (no network), once.
     */
    fun warm(ctx: Context, s: RobotSettings) {
        Thread({
            refresh(ctx, s)
            HelperSpendStore.current(ctx)
            val c = s.claude
            if (!warmed && c != null && c.on && saved[c.id] == true) {
                warmed = true
                AnthropicClient.warm()
            }
        }, "robot-helper-warm").apply { isDaemon = true; start() }
    }
}
