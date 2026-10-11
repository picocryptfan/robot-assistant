package app.heyrobot.assistant

import android.Manifest
import android.content.Context
import app.heyrobot.claude.ClaudeClient
import app.heyrobot.claude.ClaudeSession
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeTools
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SecretRead
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ClaudeKeyFiles
import app.heyrobot.sys.ClaudeSpendStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.IdentityHashMap

// VOICE PROCESS. The panel's way to the Claude API (the :claude module): one client per saved key, and a session per
// question or per Claude mode. A client stays open while a session still uses it, so saving a new key doesn't break a
// Claude-mode conversation under way; it closes when its last session ends. Nothing here logs content.

object ClaudeLink {
    /** A client and how many sessions use it; [retired]: its key was replaced or deleted. */
    private class Held(val client: ClaudeClient, val stamp: Long) {
        var users = 0
        var retired = false
    }

    private var current: Held? = null
    private val sessions = IdentityHashMap<ClaudeSession, Held>()

    /** Whether a key is saved, kept in memory: this process saves keys, and the main app says when it deleted one. */
    @Volatile private var saved: Boolean? = null

    /** The saved key could never be decrypted again (Android's keystore lost its key): it was removed, and Robot says why. */
    @Volatile var unreadable = false
        private set

    /** The last try couldn't read the saved key just now (a passing Keystore error): it is kept, and the next one tries again. */
    @Volatile var unavailable = false
        private set

    /** Whether a key is saved (a file check only the first time; [refresh] does it off the main thread). */
    fun keySaved(ctx: Context): Boolean = saved ?: ClaudeKeyFiles.saved(ctx).also { saved = it }

    /** Background thread: reads again whether a key is saved. */
    fun refresh(ctx: Context) {
        saved = ClaudeKeyFiles.saved(ctx)
        if (saved == true) unreadable = false
    }

    /**
     * Background thread: a key was saved here or deleted by the main app. The old client goes as soon as no session
     * uses it (a deleted key's Claude mode ends first: [PanelController.claudeKeyChanged]).
     */
    @Synchronized
    fun keyChanged(ctx: Context) {
        refresh(ctx)
        current?.let(::retire)
        current = null
    }

    /** The client for the saved key, or null when there is none or it can't be read. Background thread. */
    @Synchronized
    private fun held(ctx: Context): Held? {
        unavailable = false
        val stamp = ClaudeKeys.stamp(ctx)
        if (stamp == 0L) {
            saved = false
            current?.let(::retire)
            current = null
            return null
        }
        current?.takeIf { it.stamp == stamp }?.let { return it }
        current?.let(::retire)
        current = null
        return when (val r = ClaudeKeys.read(ctx)) {
            is SecretRead.Present -> Held(ClaudeClient.create(r.value), stamp).also { current = it }
            SecretRead.Absent -> {
                saved = false
                null
            }
            is SecretRead.Invalidated -> {
                // Removed as it was read: it reads as no key from now on, and Settings says why.
                saved = false
                unreadable = true
                RLog.i("claude_key", "op" to RLog.Code("unreadable"))
                null
            }
            is SecretRead.Unavailable -> {
                // Kept, file and Keystore key: this question says so, and the next one reads it again.
                unavailable = true
                RLog.i("claude_key", "op" to RLog.Code("unavailable"))
                null
            }
        }
    }

    private fun retire(h: Held) {
        h.retired = true
        if (h.users == 0) close(h)
    }

    /** Closing the SDK's client shuts its connections: never on the main thread. */
    private fun close(h: Held) {
        Thread({ h.client.close() }, "robot-claude-close").apply { isDaemon = true; start() }
    }

    /** A new conversation with Claude ([conversation]: Claude mode), or null without a key. Background thread. */
    @Synchronized
    fun session(ctx: Context, s: RobotSettings, conversation: Boolean): ClaudeSession? {
        val h = held(ctx) ?: return null
        h.users++
        return ClaudeSession(h.client, s.claudeModelChoice, ClaudeTools.tools(s), s.claudeSearch, conversation).also { sessions[it] = h }
    }

    /** The panel is done with [session] (a one-shot answered, Claude mode ended). */
    @Synchronized
    fun done(session: ClaudeSession?) {
        val h = sessions.remove(session ?: return) ?: return
        h.users--
        if (h.retired && h.users == 0) close(h)
    }

    /** GrapheneOS's per-app Network toggle revokes INTERNET: then Robot says so instead of "No internet". */
    fun networkAllowed(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.INTERNET)

    /** Today's budget is used up. Kept in memory once read (this process is the only writer); the first read is on IO. */
    suspend fun budgetReached(ctx: Context, s: RobotSettings): Boolean {
        val spend = ClaudeSpendStore.cached() ?: withContext(Dispatchers.IO) { ClaudeSpendStore.current(ctx) }
        return spend.reached(LocalDate.now(), s.claudeBudgetCents)
    }

    @Volatile private var warmed = false

    /**
     * The panel opened: whether a key is saved and today's spend are read off the main thread, and with the API route
     * ready the SDK's classes load (no network), once.
     */
    fun warm(ctx: Context, s: RobotSettings) {
        Thread({
            refresh(ctx)
            ClaudeSpendStore.current(ctx)
            if (!warmed && saved == true && ClaudeSettings.enabled(s)) {
                warmed = true
                ClaudeClient.warm()
            }
        }, "robot-claude-warm").apply { isDaemon = true; start() }
    }
}
