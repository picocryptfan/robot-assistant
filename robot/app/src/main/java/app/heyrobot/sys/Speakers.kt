// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceprintCodec
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

// Main and voice processes (0.5.0, phase 5.3). Your "me" voiceprint's file, without the voiceprint: it is encrypted and
// decrypted in the :voice process only (assistant/conversation/Voiceprints.kt). The main app reads only whether there
// is one (Settings › Conversation, and the meVoiceprint flag it keeps in step), and deletes it on your word, Keystore key
// and all, then tells the voice process so a running session stops comparing with it.

object VoiceprintFiles {
    /** IV and ciphertext (core VoiceprintCodec), written and read by the :voice process only. */
    fun file(ctx: Context): File = File(Paths.dir(ctx), VoiceprintCodec.FILE)

    fun saved(ctx: Context): Boolean = file(ctx).isFile

    /** [MAIN or VOICE] "Delete my voiceprint" (background thread): the file and the Keystore key go. */
    fun delete(ctx: Context) {
        file(ctx).delete()
        File(Paths.dir(ctx), ".${VoiceprintCodec.FILE}.tmp").delete()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(VoiceprintCodec.KEY_ALIAS)
        } catch (t: Throwable) {
            RLog.w("voiceprint_delete", t)
        }
        RLog.i("voiceprint", "op" to RLog.Code("deleted"))
    }
}

/**
 * The speaker model's download offered on the transcript screen (once): the :voice process keeps that you answered
 * "Not now", since only the main app writes settings. Your answer in Settings › Conversation counts as well.
 */
object SpeakerOffer {
    private fun file(ctx: Context) = File(Paths.dir(ctx), "speaker_offer.json")

    fun answered(ctx: Context): Boolean = readOrNull(file(ctx)) != null

    /** [VOICE] "Not now". */
    fun answer(ctx: Context) {
        try { writeAtomic(file(ctx), JSONObject().put("at", System.currentTimeMillis()).toString()) } catch (t: Throwable) { RLog.w("speaker_offer", t) }
    }
}
