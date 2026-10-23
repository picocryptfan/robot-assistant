// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillOutcome
import app.heyrobot.core.skills.SkillRun
import app.heyrobot.core.skills.SkillRunRecord
import app.heyrobot.core.skills.SkillStateFile
import app.heyrobot.sys.SkillFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS (1.1, Skills: plan §7.4 to §7.8, D5 to D7). A skill's steps in the panel: core SkillRun over the
// conversation's own ports (PanelSkills.Host.ports: the panel's actions, its view and cards, the messages in it), each
// step as a typed command with the conversation's real content taint, carried from step to step. Robot's line comes from
// the real statuses. When an app opens with steps left, the rest waits for a tap on a notification (SkillContinue).
// Used only by PanelSkills and PanelController (ProcessIsolationTest).

object SkillRunner {
    private val app get() = VoiceHub.app

    /**
     * Runs [s] from step [from] with [values]. [named]: your words named it. [done]: steps that happened before (a
     * continuation). [untrusted]: the conversation's content when the run started (D5).
     */
    suspend fun run(replyId: String, s: Skill, values: Map<String, String>, named: Boolean, host: PanelSkills.Host, l: Langs, from: Int = 0, done: Int = 0,
        untrusted: Boolean = host.untrusted) {
        host.text(replyId, SkillStrings.running("name" to s.name).render(l))
        val out = steps(replyId, s, values, named, host, l, from, done, untrusted)
        if (out.continueAt != null || out.launched) host.leave()
    }

    /**
     * Phase B: [s] as the model called it, inside the model's turn: the reply's text so far stays (the line is added after
     * it), and the panel doesn't leave here (the turn ends with the app opened, and the panel leaves then).
     */
    suspend fun inTurn(replyId: String, s: Skill, values: Map<String, String>, named: Boolean, host: PanelSkills.Host, l: Langs, untrusted: Boolean): SkillOutcome =
        steps(replyId, s, values, named, host, l, 0, 0, untrusted)

    /** The steps, the last run kept for Settings, Robot's line, and the continuation when an app opened with steps left. */
    private suspend fun steps(replyId: String, s: Skill, values: Map<String, String>, named: Boolean, host: PanelSkills.Host, l: Langs, from: Int, done: Int,
        untrusted: Boolean): SkillOutcome {
        val ports = host.ports(replyId)
        val canWait = SkillContinue.canWait(app)
        val out = SkillRun(s, values, named, ports.actions, ports.view, ports.confirmations, untrusted || host.untrusted, ports.messages, ports.langs,
            ports.newId, canWait, say = { t -> host.append(replyId, t); host.say(t, personal = true) }, done = done).run(from)
        // Settings › Skills' "Today 17:32 · 2 of 2 done": counts and a time only.
        withContext(Dispatchers.IO) {
            try {
                val st = SkillFiles.store(app)
                val ids = st.skills().map { it.id }.toSet()
                st.editState { SkillStateFile.record(it, s.id, SkillRunRecord(System.currentTimeMillis(), out.done, out.total), ids) }
            } catch (t: Throwable) {
                RLog.w("skill_state", t)
            }
        }
        host.append(replyId, out.line.render(l))
        host.say(RoutineRunner.said(out.line, l))
        val next = out.continueAt
        if (next != null) SkillContinue.post(app, s, SkillContinue.Waiting(s.id, s.rev, values, named, next, out.done, out.untrusted), l)
        return out
    }
}
