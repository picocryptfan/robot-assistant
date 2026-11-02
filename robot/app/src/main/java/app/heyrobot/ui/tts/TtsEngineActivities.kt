// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceMark
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Medium
import app.heyrobot.core.tts.EngineVoice
import app.heyrobot.core.tts.EngineVoices
import app.heyrobot.sys.AppPages
import app.heyrobot.ui.MainActivity

// MAIN PROCESS. The everyday set, E6 (docs/plans/everyday-set-plan.md §6.3): the three small activities Android's
// text-to-speech settings start for an engine (tts/RobotTtsService runs in :voice). Exported, since Android's settings and
// any app may start them; each answers and closes at once, shows nothing of its own, and reads only whether a voice model is
// downloaded (core VoiceMark). They never load an engine (ProcessIsolationTest mainProcessCodeNeverReachesEngines).

/** Whether [v]'s model is downloaded and checked (the verified mark vouches for every file), as Settings › Voice reads it. */
internal fun downloaded(ctx: android.content.Context, v: EngineVoice): Boolean = v.choice.bundle?.let { b ->
    try { VoiceMark.complete(b, VoiceBundle.dir(ctx.filesDir, b)) } catch (_: Exception) { false }
} == true

/** CHECK_TTS_DATA: which of Robot's languages have a downloaded voice ("eng-USA", "fra-FRA") and which don't. */
class TtsCheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (available, missing) = EngineVoices.dataLists { downloaded(this, it) }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(available))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList(missing)))
        finish()
    }
}

/** GET_SAMPLE_TEXT: the example Android plays ("This is Robot's voice.", « Voici la voix de Robot. »). */
class TtsSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android names the language in the intent's "language" extra (ISO 639-2); not documented as a constant.
        val lang = when (EngineVoices.lang(intent?.getStringExtra("language"))) { // l10n: not text (an extra's name)
            "eng" -> Lang.EN
            "fra" -> Lang.FR
            else -> null
        }
        if (lang == null) setResult(TextToSpeech.LANG_NOT_SUPPORTED)
        else setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            EverydayStrings.voiceSample().render(Langs(lang, lang, lang, Medium.VOICE))))
        finish()
    }
}

/**
 * INSTALL_TTS_DATA, and the engine's settings page (res/xml/tts_engine.xml): Settings › Voice, where Robot's voices are
 * downloaded and "Robot's voices for other apps" is.
 */
class TtsInstallDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra(AppPages.EXTRA, AppPages.VOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
