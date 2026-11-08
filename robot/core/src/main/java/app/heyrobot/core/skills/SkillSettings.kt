// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONObject

// Pure. Phase B (plan §3, §9): skill_settings.json v1, `{"v":1,"modelPicks":true}`, beside skills.json: Settings ›
// Skills' one row, "Let Robot's model pick skills". Main writes it (SkillStore, under skills.lock); :voice reads it at
// each question. It goes into the Skills backup category; a restore that isn't marked yours keeps this phone's value
// when the file's is on and this phone's off (RestoreSafety, SaferField.SKILLS_MODEL_PICKS). Settings stay at schema 13.

/**
 * [modelPicks]: up to 3 of your skills are described to Robot's model when your words fit them. Off: only their names run them.
 * 1.3 (plans, design §9 and §12): [planWith], who writes a plan ([PlanWith]); [planApprove], what the plan card's Do the
 * rest approves; [planCap], the most steps in a plan (3 to 8).
 */
data class SkillSettings(val modelPicks: Boolean = true, val planWith: String = PlanWith.SAME, val planApprove: PlanApprove = PlanApprove.ALL_AT_ONCE,
    val planCap: Int = SkillPlans.CAP) {
    /** Every plan setting at its default: the file stays v1, so 1.2 still reads [modelPicks] after a downgrade. */
    val plansAsDefault: Boolean get() = planWith == PlanWith.SAME && planApprove == PlanApprove.ALL_AT_ONCE && planCap == SkillPlans.CAP
}

/**
 * Settings › Skills › Plan with: [SAME], the all-skills default (on the phone, your model); [PHONE], on the phone. A
 * value this Robot doesn't know (a helper's, from a later Robot) is kept as read and plans on the phone: nothing leaves it.
 */
object PlanWith {
    const val SAME = "same" // l10n: not text (a setting's value)
    const val PHONE = "phone" // l10n: not text (a setting's value)
    private val RX = Regex("^[a-z0-9][a-z0-9_:-]{0,63}$")
    fun valid(v: String): Boolean = RX.matches(v)
}

/** Settings › Skills › Approve a plan's actions: Do the rest answers the writes' cards ([ALL_AT_ONCE]), or each shows ([STEP_BY_STEP]). */
enum class PlanApprove(val id: String) {
    ALL_AT_ONCE("all"), STEP_BY_STEP("step");

    companion object {
        fun of(id: String?): PlanApprove? = entries.firstOrNull { it.id == id }
    }
}

object SkillSettingsCodec {
    /** 1.3: v2 adds the plan rows; a file with them at their defaults is still written as v1. */
    const val VERSION = 2
    private val V1_KEYS = setOf("v", "modelPicks")
    private val V2_KEYS = V1_KEYS + setOf("planWith", "planApprove", "planCap")

    fun encode(s: SkillSettings): String = JSONObject().put("v", if (s.plansAsDefault) 1 else VERSION).put("modelPicks", s.modelPicks).apply {
        if (!s.plansAsDefault) put("planWith", s.planWith).put("planApprove", s.planApprove.id).put("planCap", s.planCap)
    }.toString()

    /** The version [json] says it is, or null when it can't be read (a backup records it, BackupCategory.schemaOf). */
    fun versionOf(json: String): Int? = try { (JSONObject(json).opt("v") as? Number)?.toInt() } catch (_: Exception) { null }

    /**
     * The file's settings: none is the defaults; one from a newer Robot ([TooNew]) or damaged keeps the defaults here and
     * is never written over by a read.
     */
    fun decode(json: String?): SkillSettingsFile {
        json ?: return SkillSettingsFile.Ok(SkillSettings())
        val o = try { JSONObject(json) } catch (_: Exception) { return SkillSettingsFile.Damaged }
        val v = (o.opt("v") as? Number)?.toInt() ?: return SkillSettingsFile.Damaged
        if (v > VERSION) return SkillSettingsFile.TooNew(v)
        if (v < 1 || o.keys().asSequence().any { it !in (if (v == 1) V1_KEYS else V2_KEYS) }) return SkillSettingsFile.Damaged
        val picks = if (o.has("modelPicks")) o.opt("modelPicks") as? Boolean ?: return SkillSettingsFile.Damaged else true
        val with = if (o.has("planWith")) (o.opt("planWith") as? String)?.takeIf(PlanWith::valid) ?: return SkillSettingsFile.Damaged else PlanWith.SAME
        val approve = if (o.has("planApprove")) PlanApprove.of(o.opt("planApprove") as? String) ?: return SkillSettingsFile.Damaged else PlanApprove.ALL_AT_ONCE
        val cap = if (o.has("planCap")) (o.opt("planCap") as? Number)?.takeIf { it is Int || it is Long }?.toInt()?.takeIf { it in SkillPlans.CAPS }
            ?: return SkillSettingsFile.Damaged else SkillPlans.CAP
        return SkillSettingsFile.Ok(SkillSettings(picks, with, approve, cap))
    }
}

sealed class SkillSettingsFile {
    data class Ok(val read: SkillSettings) : SkillSettingsFile()
    data class TooNew(val v: Int) : SkillSettingsFile()
    data object Damaged : SkillSettingsFile()

    /** What applies: the file's, or the defaults when it can't be read. */
    val settings: SkillSettings get() = (this as? Ok)?.read ?: SkillSettings()
}
