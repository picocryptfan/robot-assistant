// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.content.Context
import android.os.SystemClock
import app.heyrobot.core.FastEngineGate
import app.heyrobot.core.LangPair
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtError
import app.heyrobot.core.MtException
import app.heyrobot.core.MtMark
import app.heyrobot.core.MtMsg
import app.heyrobot.core.MtPriority
import app.heyrobot.core.MtTransport
import app.heyrobot.core.RLog
import app.heyrobot.core.Translator
import app.heyrobot.sys.MtLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

// VOICE PROCESS. The fast engine for conversation mode (docs/conversation-mode.md 4.1, 4.5): Bergamot in its own :mt
// process, reached through core's BergamotTranslator over one MtLink binding (by class name: this process never loads
// the engine or its library, and a line goes to :mt and nowhere else). Core's FastEngineGate offers it only while this
// build has librobotmt.so and the pair's files are all downloaded and verified; otherwise [translator] is null and
// Robot's model translates alone. :mt is bound only while a session translates: [hold] when it starts translating (both
// directions load at once), [release] when it stops, so :mt frees the engine's memory; a line sent outside a hold is
// cancelled, never a reason to bind again.

object FastTranslation {
    /** The library the :mt process loads (the main app's MtModelStore.LIBRARY). */
    private const val LIBRARY = "librobotmt.so"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var gate: FastEngineGate? = null
    // Guarded by this.
    private var link: MtLink? = null
    private var held = false
    /** 0.7: translations of your messages ([borrow]) using the engine right now. */
    private var lent = 0

    /**
     * The fast engine for this phone now, for [LangPair] at [MtPriority] (interactive: the interpreter's turn; batch: a
     * transcript's line), or null: not in this build, its files not downloaded and verified, or resting after a failure.
     */
    @Volatile var provider: (Context, LangPair, MtPriority) -> Translator? = { ctx, pair, priority ->
        gate(ctx).translator(pair, priority) { transport(ctx) }
    }

    fun translator(ctx: Context, pair: LangPair, priority: MtPriority = MtPriority.INTERACTIVE): Translator? =
        try { provider(ctx, pair, priority) } catch (_: Throwable) { null }

    /** A session starts: look at the files again, and give an engine that rested another try. */
    fun recheck(ctx: Context) {
        gate(ctx).recheck()
    }

    /** A session starts translating: :mt may be bound, and both directions load now rather than at the first line (voice log 118). */
    fun hold(ctx: Context, pair: LangPair) {
        synchronized(this) { held = true }
        val g = gate(ctx)
        if (!g.offers(pair)) return
        val l = try { link(ctx) } catch (_: MtException) { return }
        scope.launch {
            try {
                l.load(pair)
            } catch (e: MtException) {
                g.failed(e.error)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                RLog.w("mt_prewarm", t)
            }
        }
    }

    /** The session stopped translating: unbind :mt, which frees the engine once no one is bound. */
    fun release() {
        val l = synchronized(this) {
            held = false
            if (lent > 0) null else link.also { link = null }
        }
        l?.close()
    }

    /**
     * 0.7: a message's translation (assistant PanelMessages) may use the fast engine for the length of [block], as
     * TranslatePolicy orders it, whether or not a session holds it; :mt is unbound after unless a session still holds it.
     * [block] gets null when the engine isn't offered.
     */
    suspend fun <T> borrow(ctx: Context, pair: LangPair, block: suspend (Translator?) -> T): T {
        val t = translator(ctx, pair, MtPriority.INTERACTIVE) ?: return block(null)
        synchronized(this) { lent++ }
        try {
            return block(t)
        } finally {
            val l = synchronized(this) {
                lent--
                if (lent > 0 || held) null else link.also { link = null }
            }
            l?.close()
        }
    }

    private fun link(ctx: Context): MtLink = synchronized(this) {
        if (!held && lent == 0) throw MtException(MtError.CANCELLED)
        link ?: MtLink(ctx).also { link = it }
    }

    /** The binding as it is when a line is sent (none outside a [hold]), not as it was when its translator was made. */
    private fun transport(ctx: Context): MtTransport = object : MtTransport {
        override suspend fun translate(direction: String, lines: List<String>, deadlineMs: Long, priority: MtPriority): MtMsg.Translated =
            link(ctx).translate(direction, lines, deadlineMs, priority)
    }

    private fun gate(ctx: Context): FastEngineGate = gate ?: synchronized(this) {
        gate ?: run {
            val app = ctx.applicationContext
            FastEngineGate({ SystemClock.elapsedRealtime() }) { pair ->
                val b = MtBundle.of(pair)
                FastEngineGate.availability(engineInBuild(app), b, if (b == null) emptySet() else MtMark.vouched(b, MtBundle.dir(app.filesDir, b)))
            }.also { gate = it }
        }
    }

    /** librobotmt.so is in this APK (the extracted native libraries), as Settings › Conversation checks it. */
    private fun engineInBuild(ctx: Context): Boolean = try {
        File(ctx.applicationInfo.nativeLibraryDir, LIBRARY).isFile
    } catch (_: Exception) { false }
}
