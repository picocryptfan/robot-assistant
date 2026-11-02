// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import app.heyrobot.core.RLog
import app.heyrobot.core.SystemVoice
import app.heyrobot.core.TtsEngine
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// VOICE PROCESS. The everyday set, E6 (docs/plans/everyday-set-plan.md §6.3, D16): now that Robot can be the phone's
// text-to-speech engine, Robot's own "System voice" (AndroidTextToSpeech) must never bind Robot, or Robot would wait on
// itself. This names the engine it binds: never Robot's own package (core SystemVoice.forRobot: the default when it's
// another engine, else the one before Robot, else the highest-ranked other one). The installed engines are read through
// the TTS_SERVICE <queries> entry, never by making a TextToSpeech (that would bind the default, which may be Robot). The
// default before Robot is kept in filesDir/robot/tts_engine_before.txt, a package name and nothing else (never in
// settings.json or a backup). RobotTtsService also refuses Robot's own uid, in case Android falls back to it anyway.

internal object OtherEngines {
    /** The engines Android lists (AOSP TtsEngines.getEngines), Robot's own included. */
    fun installed(ctx: Context): List<TtsEngine> = try {
        val pm = ctx.packageManager
        pm.queryIntentServices(Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { r ->
            val si = r.serviceInfo ?: return@mapNotNull null
            TtsEngine(si.packageName, null, (si.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0, r.priority)
        }
    } catch (t: Throwable) {
        RLog.w("tts_engines", t)
        emptyList()
    }

    /**
     * The engine Robot's System voice binds, by package; null when Robot is the only one. Notes the default as the one
     * before Robot ([stateDir]) while it is another engine.
     */
    fun pick(ctx: Context, stateDir: File?): String? {
        val setting = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) } catch (_: Throwable) { null }
        val engines = installed(ctx)
        val own = ctx.packageName
        val file = stateDir?.let { File(it, SystemVoice.BEFORE_FILE) }
        val before = file?.let { readOrNull(it)?.trim() }
        SystemVoice.toRemember(setting, engines, own)?.takeIf { it != before && file != null }?.let { p ->
            try { writeAtomic(file!!, p) } catch (t: Throwable) { RLog.w("tts_before", t) }
        }
        return SystemVoice.forRobot(setting, engines, own, before)?.pkg?.takeIf { it != own }
    }
}
