// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONObject

// Pure. Phase B (plan §3, §9): skill_settings.json v1, `{"v":1,"modelPicks":true}`, beside skills.json: Settings ›
// Skills' one row, "Let Robot's model pick skills". Main writes it (SkillStore, under skills.lock); :voice reads it at
// each question. It goes into the Skills backup category; a restore that isn't marked yours keeps this phone's value
// when the file's is on and this phone's off (RestoreSafety, SaferField.SKILLS_MODEL_PICKS). Settings stay at schema 13.

/** [modelPicks]: up to 3 of your skills are described to Robot's model when your words fit them. Off: only their names run them. */
data class SkillSettings(val modelPicks: Boolean = true)

object SkillSettingsCodec {
    const val VERSION = 1

    fun encode(s: SkillSettings): String = JSONObject().put("v", VERSION).put("modelPicks", s.modelPicks).toString()

    /**
     * The file's settings: none is the defaults; one from a newer Robot ([TooNew]) or damaged keeps the defaults here and
     * is never written over by a read.
     */
    fun decode(json: String?): SkillSettingsFile {
        json ?: return SkillSettingsFile.Ok(SkillSettings())
        val o = try { JSONObject(json) } catch (_: Exception) { return SkillSettingsFile.Damaged }
        val v = (o.opt("v") as? Number)?.toInt() ?: return SkillSettingsFile.Damaged
        if (v > VERSION) return SkillSettingsFile.TooNew(v)
        if (v < 1 || o.keys().asSequence().any { it != "v" && it != "modelPicks" }) return SkillSettingsFile.Damaged
        val picks = if (o.has("modelPicks")) o.opt("modelPicks") as? Boolean ?: return SkillSettingsFile.Damaged else true
        return SkillSettingsFile.Ok(SkillSettings(picks))
    }
}

sealed class SkillSettingsFile {
    data class Ok(val read: SkillSettings) : SkillSettingsFile()
    data class TooNew(val v: Int) : SkillSettingsFile()
    data object Damaged : SkillSettingsFile()

    /** What applies: the file's, or the defaults when it can't be read. */
    val settings: SkillSettings get() = (this as? Ok)?.read ?: SkillSettings()
}
