// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.RLog
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.SkillIntents

// VOICE PROCESS (1.1, Skills D7). The manifest receiver for "Continue “Heading home”" (not exported, in :voice): only
// Robot's own PendingIntent reaches it. A tap on a locked phone does nothing (the notification stays until it expires);
// otherwise the panel opens with the token, which PanelSkills checks and uses up.

class SkillContinueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != SkillIntents.ACTION_CONTINUE) return
        val token = intent.getStringExtra(SkillIntents.EXTRA_TOKEN) ?: return
        val app = ctx.applicationContext
        if (Assistant.locked(app)) {
            RLog.i("skill_continue", "locked" to true)
            return
        }
        val vis = RobotVoiceService.instance ?: return
        VoiceHub.main.post { vis.show(RobotVoiceService.SRC_APP, Bundle().apply { putString(SkillIntents.ARG_SKILL, token) }) }
    }
}
