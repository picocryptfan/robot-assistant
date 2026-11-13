// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.MtBundle
import app.heyrobot.core.QuickPhone

/** HS3: what this phone has, for Quick access's buttons (core QuickItems). Reads files only; nothing goes online. */
object QuickFacts {
    fun phone(ctx: Context): QuickPhone = QuickPhone(
        link = runCatching { LinkSettingsStore.current(ctx).on }.getOrDefault(false),
        skills = runCatching { (SkillFiles.store(ctx).file() as? app.heyrobot.core.skills.SkillFile.Ok)?.skills?.size ?: 0 }.getOrDefault(0),
        // The speech model Transcribe uses ships with Robot (voice assets), so it is always there.
        speech = true,
        translation = runCatching { MtBundle.entries.any { MtModelStore.verified(ctx, it) } }.getOrDefault(false),
        translationToGet = runCatching { MtModelStore.engineInBuild(ctx) }.getOrDefault(false),
    )
}
