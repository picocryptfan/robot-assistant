// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.tts

import android.media.AudioFormat
import android.os.Process
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.tts.CharsBucket
import app.heyrobot.core.tts.EngineVoice
import app.heyrobot.core.tts.EngineVoices
import app.heyrobot.core.tts.LangSupport
import app.heyrobot.core.tts.TtsText
import app.heyrobot.core.tts.VoiceForAppPace
import app.heyrobot.core.tts.VoiceForAppResult
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.voice.AppAudioOut
import app.heyrobot.voice.AppError
import app.heyrobot.voice.VoiceEngines
import java.util.Locale

// VOICE PROCESS (android:process=":voice", exported: Android's text-to-speech clients hold no permission). The everyday
// set, E6 (docs/plans/everyday-set-plan.md §6.3, D16, D17): Robot as the phone's text-to-speech engine, so the screen
// reader and other apps can speak with Robot's voices, Heart and Kitten (English, US) and Siwis (French, from France),
// once you pick Robot in Android's text-to-speech settings. Settings › Voice › "Robot's voices for other apps" turns it
// off (the main app then disables this service and its three activities, sys VoicesForApps); it's checked here too.
//
// Where it runs (D17, E6's spike, 23 Oct 2026). The plan wanted other apps' text spoken in an isolated ":tts" process. It
// can't be, with the sherpa-onnx v1.13.8 library Robot ships: its JNI makes a voice only from file paths or the APK's
// assets (OfflineTts newFromFile and newFromAsset), Kokoro's model opens through ONNX Runtime by path and its voices
// through an ifstream by path, and espeak-ng opens every data file by name under its data folder (synthdata.c,
// dictionary.c, voices.c). An isolated process can open no file of Robot's by name, not even through /proc/self/fd (an
// open again, which Android's policy forbids there; robot/README.md's :llama section). So this runs in :voice, on the
// engine and worker Robot's own voice uses (voice OtherAppsVoice), behind core TtsText: every text into the library goes
// through TtsText.native, and another app's through TtsText.forApp first. Making ":tts" possible needs a sherpa-onnx built
// to load its model and voices from descriptors or memory and an espeak-ng that reads its data from memory (native work,
// like llama.cpp's for :llama). SECURITY.md says so.
//
// Other rules (D16): Robot never speaks through itself (its own "System voice" names another engine, voice OtherEngines,
// and a request from Robot's own uid is refused here); Robot asks for no audio focus (Android plays the audio with the
// app's own settings); Robot's voice for another app counts as Robot speaking for the wake word (VoiceHub.onWake); nothing
// an app asked to say reaches RLog, the voice log or a file: the log gets codes and a length bucket, at most once a minute
// per result (core VoiceForAppPace). Not direct-boot aware (D18): before the first unlock Android uses another engine.

class RobotTtsService : TextToSpeechService() {
    /** Bumped by [onStop]: the request in flight compares its own. */
    @Volatile private var stops = 0
    /** The language last loaded (onLoadLanguage), as Android reads it back. */
    @Volatile private var loaded = arrayOf("eng", "USA", "")
    /** Only the synthesis thread notes results. */
    private val pace = VoiceForAppPace()

    override fun onCreate() {
        // Before Android's own onCreate, which already asks onLoadLanguage for the default language: the engines first.
        VoiceHub.init(this)
        super.onCreate()
    }

    private fun usable(v: EngineVoice): Boolean = VoiceEngines.otherApps?.usable(v.choice) == true

    /** Robot's own voice, whose language's voice comes first (Kitten speaks English when it's Robot's). */
    private fun robotVoice(): VoiceChoice = VoiceChoice.of(VoiceHub.settings.voice)

    private fun on(): Boolean = EverydaySettingsStore.current(this).voices

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (!on()) TextToSpeech.LANG_NOT_SUPPORTED else code(EngineVoices.support(lang, country, ::usable))

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val r = onIsLanguageAvailable(lang, country, variant)
        if (r >= TextToSpeech.LANG_AVAILABLE) loaded = arrayOf(EngineVoices.lang(lang) ?: "eng", EngineVoices.country(country), "")
        return r
    }

    override fun onGetLanguage(): Array<String> = loaded.copyOf()

    override fun onGetVoices(): List<Voice> = EngineVoice.entries.map { v ->
        val features = if (usable(v)) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        Voice(v.voiceName, Locale.forLanguageTag(v.tag), if (v == EngineVoice.KITTEN) Voice.QUALITY_HIGH else Voice.QUALITY_VERY_HIGH,
            if (v == EngineVoice.KITTEN) Voice.LATENCY_LOW else Voice.LATENCY_NORMAL, false, features)
    }

    override fun onIsValidVoiceName(voiceName: String?): Int = if (EngineVoices.byName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = if (EngineVoices.byName(voiceName)?.let(::usable) == true) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        EngineVoices.default(lang, robotVoice(), ::usable)?.voiceName

    override fun onStop() {
        stops++
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val mine = stops
        // The request's text: its length for the log, and the filter. Nothing else reads it.
        val text: CharSequence = request.charSequenceText ?: ""
        val result = synthesize(request, text, callback) { stops != mine }
        pace.note(result, SystemClock.elapsedRealtime())?.let { n -> VoiceLog.add(VoiceEvent.VOICE_FOR_APP, result.id, CharsBucket.of(text.length).id, n) }
    }

    private fun synthesize(request: SynthesisRequest, text: CharSequence, callback: SynthesisCallback, stopped: () -> Boolean): VoiceForAppResult {
        // D16: Robot never speaks through itself. Its System voice names another engine; this catches the rest (Android
        // falling back to the default engine when the named one is gone).
        if (request.callerUid == Process.myUid()) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return VoiceForAppResult.OWN_APP
        }
        if (!on()) {
            callback.error(TextToSpeech.ERROR_SERVICE)
            return VoiceForAppResult.OFF
        }
        val voices = VoiceEngines.otherApps ?: run {
            callback.error(TextToSpeech.ERROR_SERVICE)
            return VoiceForAppResult.FAILED
        }
        val voice = EngineVoices.pick(request.voiceName, request.language, robotVoice(), ::usable) ?: run {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return VoiceForAppResult.NOT_DOWNLOADED
        }
        return voices.speak(voice.choice, EngineVoices.speed(request.speechRate), TtsText.forApp(text), Out(callback), stopped)
    }

    /** The request's SynthesisCallback as the voice module hands audio over: 16-bit mono. */
    private class Out(private val cb: SynthesisCallback) : AppAudioOut {
        override val maxBytes: Int get() = cb.maxBufferSize
        override fun start(rate: Int): Boolean = cb.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS
        override fun write(pcm: ByteArray, length: Int): Boolean = cb.audioAvailable(pcm, 0, length) == TextToSpeech.SUCCESS
        override fun done() {
            cb.done()
        }

        override fun error(e: AppError) = cb.error(when (e) {
            AppError.NOT_INSTALLED -> TextToSpeech.ERROR_NOT_INSTALLED_YET
            AppError.SYNTHESIS -> TextToSpeech.ERROR_SYNTHESIS
        })
    }

    companion object {
        /** core LangSupport as Android's TextToSpeech.LANG_ codes. */
        fun code(s: LangSupport): Int = when (s) {
            LangSupport.COUNTRY -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            LangSupport.LANGUAGE -> TextToSpeech.LANG_AVAILABLE
            LangSupport.MISSING_DATA -> TextToSpeech.LANG_MISSING_DATA
            LangSupport.NOT_SUPPORTED -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }
}
