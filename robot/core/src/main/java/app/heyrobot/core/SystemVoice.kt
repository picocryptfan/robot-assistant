// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SettingsStrings

// Pure. 1.2 (every-android-phone design, Step 6): the phone's own voice ([VoiceChoice.SYSTEM]) by the name Android gives
// its text-to-speech engine, never one phone's ("GrapheneOS Speech Services" before 1.2): Google's or Samsung's engine
// on other phones. The app reads the installed engines (sys PhoneVoice); this picks the one Android speaks with.

/**
 * One installed text-to-speech engine as Android lists it (TtsEngines.getEngines): [pkg] its package, [label] its
 * service's label (null or blank: none), [system] a system app, [priority] its intent filter's priority (counted for
 * system engines only).
 */
data class TtsEngine(val pkg: String, val label: String?, val system: Boolean, val priority: Int = 0)

object SystemVoice {
    /**
     * The engine Android speaks with, in AOSP TtsEngines.getDefaultEngine's order: the one Settings.Secure
     * tts_default_synth ([setting]) names when it is installed, else the highest-ranked engine if that is a system one
     * (system engines first, then the higher priority), else none.
     */
    fun default(setting: String?, engines: List<TtsEngine>): TtsEngine? {
        setting?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> engines.firstOrNull { it.pkg == s }?.let { return it } }
        val ranked = engines.sortedWith(compareByDescending<TtsEngine> { it.system }.thenByDescending { if (it.system) it.priority else 0 })
        return ranked.firstOrNull()?.takeIf { it.system }
    }

    /**
     * The everyday set, E6 (everyday-set-plan.md §6.3, D16): the engine Robot's own "System voice" speaks with, never Robot
     * itself ([own], Robot's package), now that Robot can be the phone's engine: the default ([setting]) when it is another
     * installed engine; else the one that was the default before Robot ([before], kept by [toRemember]) while it is
     * installed; else the highest-ranked other engine (system engines first, then the higher priority, as [default] ranks
     * them, any engine here). Null when Robot is the only engine: the System voice is then Heart (voice VoiceOutput).
     */
    fun forRobot(setting: String?, engines: List<TtsEngine>, own: String, before: String?): TtsEngine? {
        val others = engines.filter { it.pkg != own }
        setting?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> others.firstOrNull { it.pkg == s }?.let { return it } }
        before?.trim()?.takeIf { it.isNotEmpty() }?.let { b -> others.firstOrNull { it.pkg == b }?.let { return it } }
        return others.sortedWith(compareByDescending<TtsEngine> { it.system }.thenByDescending { it.priority }.thenBy { it.pkg }).firstOrNull()
    }

    /** [forRobot]'s engine's label, or null when there is none or it has no name. */
    fun labelForRobot(setting: String?, engines: List<TtsEngine>, own: String, before: String?): String? =
        forRobot(setting, engines, own, before)?.label?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What to keep as the default before Robot ([BEFORE_FILE]): the default ([setting]) when it names another installed
     * engine; null when it is Robot, unset or not installed (what was kept stays).
     */
    fun toRemember(setting: String?, engines: List<TtsEngine>, own: String): String? =
        setting?.trim()?.takeIf { s -> s.isNotEmpty() && s != own && engines.any { it.pkg == s } }

    /** filesDir/robot/'s note of the default engine before Robot (a package name), never in settings.json or a backup. */
    const val BEFORE_FILE = "tts_engine_before.txt"

    /** The default engine's label, or null when there is none or it has no name. */
    fun label(setting: String?, engines: List<TtsEngine>): String? = default(setting, engines)?.label?.trim()?.takeIf { it.isNotEmpty() }

    /** The phone's voice's line in Settings: the engine's name as the phone gives it, else "The phone's text-to-speech engine". */
    fun detailText(label: String?): Phrase = label?.trim()?.takeIf { it.isNotEmpty() }?.let(Phrase::plain) ?: SettingsStrings.voiceSystemDetail()
}
