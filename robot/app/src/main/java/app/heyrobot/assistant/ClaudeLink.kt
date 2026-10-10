package app.heyrobot.assistant

import android.Manifest
import android.content.Context
import app.heyrobot.claude.ClaudeClient
import app.heyrobot.claude.ClaudeSession
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeTools
import app.heyrobot.core.RobotSettings
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ClaudeKeyFiles
import app.heyrobot.sys.ClaudeSpendStore
import java.time.LocalDate

// VOICE PROCESS. The panel's way to the Claude API (the :claude module): one client per saved key, rebuilt when the key
// file changes and dropped when it is deleted; a session per question or per Claude mode. Nothing here logs content.

object ClaudeLink {
    private var client: ClaudeClient? = null
    private var stamp = 0L

    fun keySaved(ctx: Context): Boolean = ClaudeKeyFiles.saved(ctx)

    /** The client for the saved key, or null when there is none (or it can't be read). Main thread or worker. */
    @Synchronized
    fun client(ctx: Context): ClaudeClient? {
        val now = ClaudeKeys.stamp(ctx)
        if (now == 0L) {
            drop()
            return null
        }
        if (client != null && now == stamp) return client
        drop()
        val key = ClaudeKeys.read(ctx) ?: return null
        client = ClaudeClient.create(key)
        stamp = now
        return client
    }

    @Synchronized
    fun drop() {
        client?.close()
        client = null
        stamp = 0L
    }

    /** A new conversation with Claude ([conversation]: Claude mode), or null without a key. */
    fun session(ctx: Context, s: RobotSettings, conversation: Boolean): ClaudeSession? {
        val c = client(ctx) ?: return null
        return ClaudeSession(c, s.claudeModelChoice, ClaudeTools.tools(s), s.claudeSearch, conversation)
    }

    /** GrapheneOS's per-app Network toggle revokes INTERNET: then Robot says so instead of "No internet". */
    fun networkAllowed(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.INTERNET)

    fun budgetReached(ctx: Context, s: RobotSettings): Boolean = ClaudeSpendStore.load(ctx).reached(LocalDate.now(), s.claudeBudgetCents)

    /** The panel opened with the API route ready: load the SDK's classes off the main thread (no network). */
    fun warm(ctx: Context, s: RobotSettings) {
        if (!keySaved(ctx) || !ClaudeSettings.enabled(s, true)) return
        Thread({ ClaudeClient.warm() }, "robot-claude-warm").apply { isDaemon = true; start() }
    }
}
