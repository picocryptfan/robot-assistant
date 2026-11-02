// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.Paths
import app.heyrobot.sys.VoicesForApps
import app.heyrobot.ui.MainActivity
import app.heyrobot.ui.settings.AppRoutes
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.tts.TtsCheckDataActivity
import app.heyrobot.ui.tts.TtsInstallDataActivity
import app.heyrobot.ui.tts.TtsSampleTextActivity
import app.heyrobot.voice.VoiceEngines
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The everyday set, E6 (everyday-set-plan.md §6.3, D16): Robot as the phone's text-to-speech engine. The voices and
 * languages Android is told (none downloaded here: "missing data", and each voice marked not installed), a request from
 * Robot's own uid refused (no loop), the switch refusing and taking the engine out of Android's list, a missing voice an
 * error the app sees, and the three engine activities' answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotTtsServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var svc: RobotTtsService

    private class Callback : SynthesisCallback {
        val events = mutableListOf<String>()
        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int { events += "start $sampleRateInHz"; return TextToSpeech.SUCCESS }
        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int { events += "audio"; return TextToSpeech.SUCCESS }
        override fun done(): Int { events += "done"; return TextToSpeech.SUCCESS }
        override fun error() { events += "error" }
        override fun error(errorCode: Int) { events += "error $errorCode" }
        override fun getMaxBufferSize(): Int = 8_192
        override fun hasStarted(): Boolean = events.any { it.startsWith("start") }
        override fun hasFinished(): Boolean = events.any { it == "done" || it.startsWith("error") }
    }

    @Before fun setUp() {
        // The engines as :voice makes them (no model downloaded here), without the service's onCreate (VoiceHub's start-up).
        VoiceEngines.init(ctx, Paths.dir(ctx))
        if (VoiceEngines.otherApps == null) VoiceEngines.textToSpeech(ctx)
        // Robolectric attaches the service's context; its onCreate isn't run.
        svc = Robolectric.buildService(RobotTtsService::class.java).get()
    }

    @After fun clean() {
        EverydaySettingsStore.file(ctx).delete()
        EverydaySettingsStore.forget()
    }

    private fun <T> call(name: String, vararg args: Any?): T {
        val m = RobotTtsService::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(svc, *args) as T
    }

    private fun request(text: String, uid: Int, lang: String = "eng"): SynthesisRequest = SynthesisRequest(text, Bundle()).also { r ->
        SynthesisRequest::class.java.getDeclaredMethod("setCallerUid", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(r, uid)
        SynthesisRequest::class.java.getDeclaredMethod("setLanguage", String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(r, lang, "", "")
    }

    private fun speak(r: SynthesisRequest): List<String> = Callback().also { call<Any?>("onSynthesizeText", r, it) }.events

    @Test fun languagesAndVoicesBeforeAnyDownload() {
        assertEquals(TextToSpeech.LANG_MISSING_DATA, call<Int>("onIsLanguageAvailable", "eng", "USA", ""))
        assertEquals(TextToSpeech.LANG_MISSING_DATA, call<Int>("onIsLanguageAvailable", "fra", "CAN", ""))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, call<Int>("onIsLanguageAvailable", "deu", "DEU", ""))
        val voices = call<List<Voice>>("onGetVoices")
        assertEquals(listOf("en-us-x-robot-heart", "en-us-x-robot-kitten", "fr-fr-x-robot-siwis"), voices.map { it.name })
        assertTrue(voices.all { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features && !it.isNetworkConnectionRequired })
        assertEquals("fr-FR", voices.last().locale.toLanguageTag())
        assertEquals("en-us-x-robot-heart", call<String?>("onGetDefaultVoiceNameFor", "eng", "USA", ""))
        assertEquals("fr-fr-x-robot-siwis", call<String?>("onGetDefaultVoiceNameFor", "fra", "CAN", ""))
        assertNull(call<String?>("onGetDefaultVoiceNameFor", "deu", "", ""))
        assertEquals(TextToSpeech.SUCCESS, call<Int>("onIsValidVoiceName", "fr-fr-x-robot-siwis"))
        assertEquals(TextToSpeech.ERROR, call<Int>("onIsValidVoiceName", "en-us-x-someone"))
        assertEquals(TextToSpeech.ERROR, call<Int>("onLoadVoice", "en-us-x-robot-heart"))
        // core LangSupport as Android's codes.
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, RobotTtsService.code(app.heyrobot.core.tts.LangSupport.COUNTRY))
        assertEquals(TextToSpeech.LANG_AVAILABLE, RobotTtsService.code(app.heyrobot.core.tts.LangSupport.LANGUAGE))
    }

    /** D16: Robot never speaks through itself, whatever Android falls back to. */
    @Test fun robotsOwnRequestIsRefused() {
        assertEquals(listOf("error ${TextToSpeech.ERROR_INVALID_REQUEST}"), speak(request("Hello there.", Process.myUid())))
    }

    @Test fun anotherAppIsToldTheVoiceIsMissing() {
        assertEquals(listOf("error ${TextToSpeech.ERROR_NOT_INSTALLED_YET}"), speak(request("Hello there.", Process.myUid() + 1)))
        assertEquals(listOf("error ${TextToSpeech.ERROR_NOT_INSTALLED_YET}"), speak(request("Bonjour.", Process.myUid() + 1, "fra")))
    }

    /** Off: refused in the service, no language offered, and the engine and its activities out of Android's list. */
    @Test fun theSwitchTakesTheEngineAway() {
        val pm = ctx.packageManager
        fun states() = VoicesForApps.COMPONENTS.map { pm.getComponentEnabledSetting(ComponentName(ctx.packageName, it)) }
        assertTrue(states().none(VoicesForApps::disabled))
        EverydaySettingsStore.update(ctx) { it.copy(voices = false) }
        assertEquals(List(4) { PackageManager.COMPONENT_ENABLED_STATE_DISABLED }, states())
        assertEquals(listOf("error ${TextToSpeech.ERROR_SERVICE}"), speak(request("Hello there.", Process.myUid() + 1)))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, call<Int>("onIsLanguageAvailable", "eng", "USA", ""))
        EverydaySettingsStore.update(ctx) { it.copy(voices = true) }
        assertTrue(states().none(VoicesForApps::disabled))
    }

    @Test fun checkDataListsTheLanguagesWithoutAVoice() {
        val a = Robolectric.buildActivity(TtsCheckDataActivity::class.java, Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)).create().get()
        assertEquals(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, shadowOf(a).resultCode)
        val data = shadowOf(a).resultIntent
        assertEquals(emptyList<String>(), data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES))
        assertEquals(listOf("eng-USA", "fra-FRA"), data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES))
        assertTrue(a.isFinishing)
    }

    @Test fun theSampleIsInTheVoicesLanguage() {
        fun sample(lang: String) = Robolectric.buildActivity(TtsSampleTextActivity::class.java,
            Intent(TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT).putExtra("language", lang).putExtra("country", "CA")).create().get()
        val en = sample("en")
        assertEquals(TextToSpeech.LANG_AVAILABLE, shadowOf(en).resultCode)
        assertEquals("This is Robot's voice.", shadowOf(en).resultIntent.getStringExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT))
        assertEquals("Voici la voix de Robot.", shadowOf(sample("fra")).resultIntent.getStringExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, shadowOf(sample("de")).resultCode)
    }

    @Test fun installOpensSettingsVoice() {
        val a = Robolectric.buildActivity(TtsInstallDataActivity::class.java, Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)).create().get()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertEquals(AppPages.VOICE, AppRoutes.of(next))
        assertEquals(SettingsRoutes.VOICE, (AppRoutes.target(AppPages.VOICE)?.page as app.heyrobot.ui.Page.Settings).route)
        assertTrue(a.isFinishing)
    }
}
