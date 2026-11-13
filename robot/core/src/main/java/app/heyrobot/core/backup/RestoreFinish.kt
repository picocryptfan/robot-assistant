// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.BriefReminders
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.RoutineStore
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.readOrNull
import app.heyrobot.core.skills.SkillCodec
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillSettingsCodec
import app.heyrobot.core.skills.SkillSettingsFile
import app.heyrobot.core.skills.SkillStats
import app.heyrobot.core.skills.SkillStore
import java.io.File

/**
 * The staged files of Headset and car, Routines and Skills written through their stores (repeatable: a crash repeats them).
 * F46: a store's refusal or a staged file that doesn't read throws [CantFinish], so the category ends as Failed, not Done. A
 * file a restore didn't stage is left alone.
 */
object RestoreFinish {
    /** The staged [name] read, or null when it wasn't staged. */
    private fun <T : Any> staged(staging: File, name: String, decode: (String) -> T?): T? {
        val text = readOrNull(File(staging, name)) ?: return null
        return decode(text) ?: throw CantFinish(name)
    }

    private fun <T : Any> T?.written(what: String): T = this ?: throw CantFinish(what)

    fun handsfree(hf: HandsFreeStore, staging: File) {
        staged(staging, HandsFreeStore.DEVICES) { (DeviceMemory.decode(it) as? DeviceFile.Ok)?.memory }?.let { m -> hf.editDevices { m }.written("devices") }
        staged(staging, HandsFreeStore.HEADSET) { HeadsetFile.decode(it) }?.let { h -> hf.editHeadset { h }.written("headset") }
        staged(staging, HandsFreeStore.CAR) { CarFile.decode(it) }?.let { c -> hf.editCar { c }.written("car") }
        staged(staging, HandsFreeStore.PLACES) { Places.decode(it) }?.let { p -> hf.editPlaces { p }.written("places") }
    }

    fun routines(rs: RoutineStore, staging: File) {
        staged(staging, "routines.json") { (RoutineCodec.decode(it) as? RoutineFile.Ok)?.routines }?.let { l -> rs.edit { l }.written("routines") } // l10n: not text (a file name)
        staged(staging, "routine_settings.json", RoutineSettingsCodec::decode)?.let { s -> rs.editSettings { s }.written("routine settings") } // l10n: not text (a file name)
        staged(staging, "reminders.json", BriefReminders::decode)?.let { l -> rs.editReminders { l }.written("reminders") } // l10n: not text (a file name)
    }

    /**
     * moments-backup: the backup's moments.json merged inside the store's own edit with this phone's as they are then, so a
     * reminder added or fired since the step isn't lost (moments-backup-c, L5); [MOMENTS_MODE] holds the mode (none: Add,
     * which keeps this phone's). Left alone when this phone's is from a newer Robot.
     */
    fun moments(rs: RoutineStore, staging: File) {
        val file = staged(staging, RoutineStore.MOMENTS) { app.heyrobot.core.MomentReminders.decodeAll(it) } ?: return
        val mode = readOrNull(File(staging, MOMENTS_MODE))?.trim()?.let { m -> RestoreMode.entries.firstOrNull { it.name == m } } ?: RestoreMode.ADD
        rs.editMoments { RestoreMerge.moments(it, file, mode) }.written("moments")
    }

    /** The mode a moments restore applies, staged beside moments.json. */
    const val MOMENTS_MODE = "moments_mode.txt" // l10n: not text (a file name)

    fun skills(ss: SkillStore, staging: File) {
        staged(staging, SkillStore.SKILLS) { (SkillCodec.decode(it) as? SkillFile.Ok)?.skills }?.let { l -> ss.edit { l }.written("skills") }
        staged(staging, SkillStore.SETTINGS) { (SkillSettingsCodec.decode(it) as? SkillSettingsFile.Ok)?.read }?.let { if (!ss.saveSettings(it)) throw CantFinish("skill settings") } // l10n: not text (a log's reason)
        // Phase B: the fill samples start over after a restore (plan §4), the safe side.
        ss.editState { SkillStats.cleared(it) }
    }
}
