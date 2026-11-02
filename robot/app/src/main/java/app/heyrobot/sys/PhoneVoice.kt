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
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

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

    /**
     * The engine Robot's "System voice" speaks with, by its own name, or null when it can't be read (Settings then says
     * "the phone's"). E6 (everyday-set-plan.md D16): never Robot, now that Robot can be the phone's engine (core
     * SystemVoice.forRobot, as voice OtherEngines picks it, with the default before Robot it noted).
     */
    fun label(ctx: Context): String? {
        val setting = setting(ctx)
        val engines = engines(ctx)
        // While the default is another engine, note it as the one before Robot (the :voice side does too).
        val before = before(ctx)
        SystemVoice.toRemember(setting, engines, ctx.packageName)?.takeIf { it != before }?.let { p ->
            try { writeAtomic(File(Paths.dir(ctx), SystemVoice.BEFORE_FILE), p) } catch (t: Throwable) { RLog.w("tts_before", t) }
        }
        return SystemVoice.labelForRobot(setting, engines, ctx.packageName, before)
    }

    /** E6: Robot is the phone's only text-to-speech engine, so its System voice is Heart (Settings › Voice says why). */
    fun robotOnly(ctx: Context): Boolean = SystemVoice.forRobot(setting(ctx), engines(ctx), ctx.packageName, before(ctx)) == null

    private fun setting(ctx: Context): String? = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) } catch (_: Throwable) { null }

    private fun before(ctx: Context): String? = readOrNull(File(Paths.dir(ctx), SystemVoice.BEFORE_FILE))?.trim()
}
