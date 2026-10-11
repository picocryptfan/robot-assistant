package app.heyrobot.assistant.conversation

import android.content.Context
import app.heyrobot.core.Translator

// VOICE PROCESS. The seam for the fast engine (docs/conversation-mode.md 4.3-4.5): Bergamot in its own :mt process,
// reached through core's BergamotTranslator over an MtLink binding, is built on its own branch. Until it is merged
// here, there is no fast engine: the interpreter and transcript translations use Robot's model alone, and say so.
// The :mt branch sets [provider] when the voice process starts (for example from VoiceHub.init): a translator when the
// fast models are downloaded and checked, else null.

object FastTranslation {
    /** The fast engine for this phone now, or null (not built in, or its models not downloaded). */
    @Volatile var provider: (Context) -> Translator? = { null }

    fun translator(ctx: Context): Translator? = try { provider(ctx) } catch (_: Throwable) { null }
}
