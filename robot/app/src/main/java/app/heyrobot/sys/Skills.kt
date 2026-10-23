// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.HelperName
import app.heyrobot.core.skills.NameContext
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillStore
import app.heyrobot.lock.LockStore

// Main and voice processes (1.1, Skills: docs/plans/skills-1.1-plan.md §3). skills.json is core SkillStore's, in
// files/robot beside routines.json, changed under skills.lock, which both processes take: the main app's Settings ›
// Skills and the :voice process (a skill taught, saved or deleted by voice). The receiver for a skill's next step after an
// app opens is in :voice, named by its class (the main process never refers to the assistant's classes).

object SkillFiles {
    fun store(ctx: Context): SkillStore = SkillStore(Paths.dir(ctx))

    /** Skills changed: :voice reads them again (Settings reads the file each time its page shows). */
    fun changed(ctx: Context) = Cmd.send(ctx, Cmd.SKILLS)
}

/** Names shared by the two processes and the notification's tap. */
object SkillIntents {
    /** :voice's manifest receiver for "Continue “Heading home”": not exported, only Robot's own PendingIntent reaches it. */
    const val RECEIVER = "app.heyrobot.assistant.skills.SkillContinueReceiver"
    const val ACTION_CONTINUE = "app.heyrobot.skills.CONTINUE"
    /** The continuation's one-time token, in the notification's intent and the panel's show arguments. */
    const val EXTRA_TOKEN = "token"
    const val ARG_SKILL = "robot_skill"
}

/** What a skill's names are checked against on this phone now (core SkillNames, D15). Either process. */
object SkillContext {
    fun of(ctx: Context, skills: List<Skill>): NameContext {
        val s = SettingsStore.load(ctx)
        return NameContext(
            skills = skills,
            routines = try { RoutineFiles.store(ctx).all().map { it.name } } catch (_: Throwable) { emptyList() },
            locks = try { LockStore.read(ctx).ok?.locks?.map { it.name }.orEmpty() } catch (_: Throwable) { emptyList() },
            helpers = HelperName.all(s),
            helperNames = s.helpers.map { it.name },
            wake = s.wakePhrases.filter { it.enabled }.map { it.text },
            scripts = try { TermuxStore.status(ctx).scripts.map { it.name } } catch (_: Throwable) { emptyList() },
        )
    }
}
