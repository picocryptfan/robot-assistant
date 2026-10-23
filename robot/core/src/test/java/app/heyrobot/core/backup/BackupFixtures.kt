// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.NoteCodec
import app.heyrobot.core.PeopleCodec
import java.io.ByteArrayOutputStream

/**
 * The committed fixtures (core/src/test/resources/backup/), made once by [write] and never again: a later release must
 * open and restore these very files. Each passphrase is here, as the design says ("passphrase in the test").
 *
 *   backup-v1-basic.robotbackup   format 1, this release's KDF settings: every category, keys and codes included
 *   backup-v1-newer.robotbackup   format 1 from "a newer Robot": settings.json at schema 99 and a "skills" category
 *   backup-v2-format.robotbackup  format 2: refused before the passphrase
 *   backup-v1-files.robotbackup   format 1 from 1.4 (light KDF): People, Notes and Robot Files (two files, the catalogue,
 *                                 Settings › Files), for BackupDowngradeTest
 *
 * To make a new one (only when the format itself changes, or a release adds a category, with a new name):
 * ROBOT_WRITE_FIXTURES=1 and run BackupGoldenTest.writeFixtures, which writes only the fixtures that aren't there.
 */
object BackupFixtures {
    const val BASIC = "backup-v1-basic.robotbackup"
    const val BASIC_PASSPHRASE = "crate-mosaic-unlocked-plaza-humming-oxygen"
    const val NEWER = "backup-v1-newer.robotbackup"
    const val NEWER_PASSPHRASE = "newer-robot-fixture-passphrase"
    const val FORMAT_2 = "backup-v2-format.robotbackup"
    /** 1.4: a backup with Robot Files in it ([files]). */
    const val FILES = "backup-v1-files.robotbackup"
    const val FILES_PASSPHRASE = "robot-files-fixture-passphrase"
    val FILES_SALT = ByteArray(BackupFormat.SALT) { (0x40 + it).toByte() }

    /** The newer fixture's light KDF settings (within bounds, so opening it in every test run stays quick). */
    val LIGHT = KdfParams(BackupFormat.KDF_ARGON2ID, lanes = 1, passes = 1, memoryKiB = 19_456)
    val NEWER_SALT = ByteArray(BackupFormat.SALT) { (0xA0 + it).toByte() }

    fun basic(): ByteArray = sealed(BackupKit.contentRecords(), BackupKit.header(), BASIC_PASSPHRASE)

    fun newer(): ByteArray {
        val notes = BackupKit.notes()
        val r = RawRecords()
            .manifest("{\"robot\":\"1.3.0\",\"code\":1030099,\"made\":${BackupKit.T0},\"format\":1,\"categories\":{\"settings\":{\"entries\":1,\"bytes\":0}," +
                "\"people\":{\"entries\":1,\"bytes\":0},\"notes\":{\"entries\":2,\"bytes\":0},\"skills\":{\"entries\":1,\"bytes\":0}},\"keys\":false," +
                "\"skillsMode\":\"later\"}")
            .entry("settings", "settings.json", BackupKit.text("{\"schema\":99,\"wakeWord\":false,\"newThing\":true}"), v = 99)
            .entry("people", "people.json", BackupKit.text(PeopleCodec.toJson(BackupKit.people())), v = 1)
            .entry("skills", "skills.json", BackupKit.text("{\"v\":1,\"skills\":[]}"), v = 1)
        for (n in notes) r.entry("notes", "${n.id}.json", BackupKit.text(NoteCodec.toJson(n)), v = 1)
        r.end()
        val header = BackupHeader(BackupFormat.FORMAT, LIGHT, NEWER_SALT, 1_030_099)
        return sealed(r.bytes(), header, NEWER_PASSPHRASE)
    }

    /** A format 2 header (KDF settings and all) and bytes this Robot never reads. */
    fun format2(): ByteArray = BackupHeader(2, KdfParams.CURRENT, BackupKit.SALT, 2_000_099).encode() + ByteArray(200) { (it * 31).toByte() }

    fun sealed(plain: ByteArray, header: BackupHeader, passphrase: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupKdf.derive(passphrase, header).use { key -> BackupWriter(out, header, key).use { it.write(plain) } }
        return out.toByteArray()
    }

    /**
     * Each fixture that isn't there yet (1.4: never one that is: a later release must open those very bytes, and the
     * content that made them has grown since; Robot Files' fixture was added this way).
     */
    fun write() {
        val dir = BackupKit.fixtureDir()
        dir.mkdirs()
        for ((name, make) in listOf<Pair<String, () -> ByteArray>>(BASIC to ::basic, NEWER to ::newer, FORMAT_2 to ::format2, FILES to ::files)) {
            val f = dir.resolve(name)
            if (!f.exists()) f.writeBytes(make())
        }
    }

    /**
     * 1.4, Robot Files (F11): a backup made by 1.4 with People, Notes and Files (two files, their catalogue, Settings ›
     * Files), the light KDF so every run opens it quickly. BackupDowngradeTest reads it as 1.1 to 1.3 would (skipping
     * "files" by name) and as this Robot does.
     */
    fun files(): ByteArray {
        val c = BackupKit.content()
        val content = linkedMapOf(BackupCategory.PEOPLE to c.getValue(BackupCategory.PEOPLE), BackupCategory.NOTES to c.getValue(BackupCategory.NOTES),
            BackupCategory.FILES to c.getValue(BackupCategory.FILES))
        return sealed(BackupKit.contentRecords(content, robot = "1.4.0"), BackupHeader(BackupFormat.FORMAT, LIGHT, FILES_SALT, 1_040_099), FILES_PASSPHRASE)
    }
}
