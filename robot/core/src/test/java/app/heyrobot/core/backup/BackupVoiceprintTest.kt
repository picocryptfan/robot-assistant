// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Voiceprint
import app.heyrobot.core.VoiceprintCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1 (decided 15 Oct): the voiceprint is an optional category, off by default, the most
 * sensitive one: unticked on the preview, restored only from a backup marked as yours, never over this phone's own.
 */
class BackupVoiceprintTest {
    private fun print(): ByteArray {
        val v = FloatArray(192) { 0f }.also { it[0] = 1f }
        return VoiceprintCodec.encode(Voiceprint("speaker-v1", v, 3))
    }

    private fun backup(bytes: ByteArray = print()): VerifiedBackup {
        val content = BackupKit.content() + (BackupCategory.VOICEPRINT to mapOf(BackupCategory.VOICEPRINT_FILE to bytes))
        return BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords(content)))
    }

    @Test fun theCategory() {
        val c = BackupCategory.VOICEPRINT
        assertFalse(c.defaultOn)
        assertEquals(RestoreProcess.VOICE, c.writer)
        assertTrue(c.validName("voiceprint.rvp"))
        assertFalse(c.validName("me.voiceprint"))
        assertFalse(c.validName("robot/voiceprint.rvp"))
        assertFalse(c.validName("voiceprint.json"))
        assertTrue(c in BackupCategory.SENSITIVE && BackupCategory.KEYS in BackupCategory.SENSITIVE)
        // Off in Back up now's defaults, like Keys.
        assertFalse(c in BackupState().chosen)
        assertEquals("backup.cat_voiceprint", BackupWords.category(c).id)
    }

    @Test fun verifyKeepsOnlyTheModel() {
        val v = backup()
        assertTrue(v.has(BackupCategory.VOICEPRINT))
        assertEquals("speaker-v1", v.voiceprintModel)
        assertFalse(v.toString().contains("speaker-v1"))
    }

    @Test fun aBrokenVoiceprintIsRefused() {
        val v = backup(byteArrayOf(1, 2, 3, 4))
        assertFalse(v.has(BackupCategory.VOICEPRINT))
        assertNull(v.voiceprintModel)
        assertEquals(SkipWhy.REFUSED, v.skipped.single { it.category == BackupCategory.VOICEPRINT }.why)
        // The rest still restores.
        assertTrue(v.has(BackupCategory.PEOPLE))
    }

    @Test fun uncheckedUntilTickedAndOnlyFromYourOwn() {
        val v = backup()
        val defaults = RestorePlanner.defaults(v)
        assertFalse(defaults.getValue(BackupCategory.VOICEPRINT).restore)
        val fresh = PhoneHas(emptyMap())
        assertEquals(PlanSkip.NOT_CHOSEN, RestorePlanner.plan(v, fresh, defaults, mine = true).of(BackupCategory.VOICEPRINT).skip)
        val ticked = defaults + (BackupCategory.VOICEPRINT to CategoryChoice())
        // Someone else's file: never, ticked or not.
        assertEquals(PlanSkip.NOT_YOURS, RestorePlanner.plan(v, fresh, ticked, mine = false).of(BackupCategory.VOICEPRINT).skip)
        val mine = RestorePlanner.plan(v, fresh, ticked, mine = true).of(BackupCategory.VOICEPRINT)
        assertTrue(mine.restores)
        assertEquals(RestoreMode.FRESH, mine.mode)
        // Never over a voiceprint made on this phone, even with Replace.
        val has = PhoneHas(mapOf(BackupCategory.VOICEPRINT to 1))
        val here = RestorePlanner.plan(v, has, ticked + (BackupCategory.VOICEPRINT to CategoryChoice(replace = true)), mine = true)
        assertEquals(PlanSkip.VOICEPRINT_HERE, here.of(BackupCategory.VOICEPRINT).skip)
        assertTrue(BackupWords.skip(here.of(BackupCategory.VOICEPRINT), "1.1.0") != null)
    }

    @Test fun enrolledOnceRestored() {
        val s = RobotSettings(meVoiceprint = false)
        assertTrue(RestoreRefs.voiceprint(s, restored = true).meVoiceprint)
        assertFalse(RestoreRefs.voiceprint(s, restored = false).meVoiceprint)
        // A restore of Settings alone never says a voiceprint is enrolled (phone-specific).
        assertFalse(RestoreRefs.phoneSpecific(s, s.copy(meVoiceprint = true)).meVoiceprint)
    }

    @Test fun theReadPassHandsTheBytes() {
        val bytes = print()
        val content = BackupKit.content() + (BackupCategory.VOICEPRINT to mapOf(BackupCategory.VOICEPRINT_FILE to bytes))
        val file = BackupKit.encrypt(BackupKit.contentRecords(content))
        val v = BackupKit.verify(file)
        var got: ByteArray? = null
        RestoreReader.read(BackupKit.opener(file), v, BackupKit.key(), setOf(BackupCategory.VOICEPRINT)) { c, n, d ->
            assertEquals(BackupCategory.VOICEPRINT, c); assertEquals("voiceprint.rvp", n); got = d.readBytes()
        }
        assertTrue(bytes.contentEquals(got))
    }
}
