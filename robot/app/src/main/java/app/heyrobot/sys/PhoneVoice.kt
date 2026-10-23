// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import app.heyrobot.core.RLog
import app.heyrobot.core.SystemVoice
import app.heyrobot.core.TtsEngine

// Main process. 1.2: the name of the phone's text-to-speech engine, for Settings › Voice and the guide's voice step
// (core SystemVoice picks the default the way Android does). Read through the TTS_SERVICE <queries> entry the manifest
// already has, without binding to an engine (a TextToSpeech instance would start it).

object PhoneVoice {
    /** The installed engines, as AOSP TtsEngines.getEngines lists them. */
    fun engines(ctx: Context): List<TtsEngine> = try {
        val pm = ctx.packageManager
        pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { r ->
            val si = r.serviceInfo ?: return@mapNotNull null
            val system = (si.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            TtsEngine(si.packageName, try { r.loadLabel(pm)?.toString() } catch (_: Throwable) { null }, system, r.priority)
        }
    } catch (t: Throwable) {
        RLog.w("tts_engines", t)
        emptyList()
    }

    /** The engine Android speaks with by its own name, or null when it can't be read (Settings then says "the phone's"). */
    fun label(ctx: Context): String? {
        val setting = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) } catch (_: Throwable) { null }
        return SystemVoice.label(setting, engines(ctx))
    }
}
