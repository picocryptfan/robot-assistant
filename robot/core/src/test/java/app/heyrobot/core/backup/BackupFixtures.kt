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
 *   backup-v1-lists.robotbackup   format 1 from 1.4 (light KDF): People, Notes and Robot Lists (lists.json, Settings ›
 *                                 Lists), for BackupDowngradeTest
 *   backup-v1-everyday.robotbackup  format 1 with the everyday set (light KDF): People, Notes and everyday_settings.json,
 *                                 for BackupDowngradeTest
 *   backup-v1-conn.robotbackup    format 1 with Skills connections (C10, light KDF): People, Skills (a v1 skills.json), Keys
 *                                 (helper and search keys, a private code), Connections and Connection keys
 *   backup-v1-conn-v2.robotbackup the same with a v2 skills.json (Good night's connection steps), for BackupDowngradeTest
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
    /** 1.4: a backup with Robot Lists in it ([lists]). */
    const val LISTS = "backup-v1-lists.robotbackup"
    const val LISTS_PASSPHRASE = "robot-lists-fixture-passphrase"
    val LISTS_SALT = ByteArray(BackupFormat.SALT) { (0x60 + it).toByte() }
    /** The everyday set (everyday-set-plan.md §2.4): a backup with its EVERYDAY category ([everyday]). */
    const val EVERYDAY = "backup-v1-everyday.robotbackup"
    const val EVERYDAY_PASSPHRASE = "everyday-set-fixture-passphrase"
    val EVERYDAY_SALT = ByteArray(BackupFormat.SALT) { (0x60 + it).toByte() }
    /** What the everyday fixture's everyday_settings.json holds: every switch but Robot's voices off, the record kept 7 days. */
    val EVERYDAY_SETTINGS = app.heyrobot.core.everyday.EverydaySettings(music = false, listen = false, askRobot = false, online = false, onlineDays = 7)
    /** L12, Robot to Robot: a backup with its LINKS category ([links]). */
    const val LINKS = "backup-v1-links.robotbackup"
    const val LINKS_PASSPHRASE = "robot-to-robot-fixture-passphrase"
    val LINKS_SALT = ByteArray(BackupFormat.SALT) { (0x70 + it).toByte() }
    const val LINKS_PAIRING = "0a0b0c0d0e0f00112233445566778899"
    /** What the links fixture's link_settings.json holds: answering while locked on, as a phone would have it. */
    val LINKS_SETTINGS = app.heyrobot.core.link.LinkSettings(on = true, name = "Jo", whileLocked = true, busyCalendars = setOf(7L))
    /** What the links fixture's links.json holds: Sam on Answer on its own for free/busy, and a line of history. */
    fun linksFile() = app.heyrobot.core.link.LinkBackupFile(
        pairings = listOf(app.heyrobot.core.link.LinkPairingBackup(LINKS_PAIRING, app.heyrobot.core.link.LinkMode.PRIVATE, 1_800_000_000, "Sam",
            ByteArray(app.heyrobot.core.link.requests.LinkCaps.BITMAP), ByteArray(app.heyrobot.core.link.LinkBackupCodec.IDENTITY) { 5 },
            app.heyrobot.core.link.PushRoute("https://push.example.org/robot-fixture", ByteArray(65) { if (it == 0) 4 else 9 }, ByteArray(16) { 3 }),
            null, null, app.heyrobot.core.link.LinkCapability.entries.associateWith { if (it == app.heyrobot.core.link.LinkCapability.FREE_BUSY) app.heyrobot.core.link.LinkLevel.AUTO else app.heyrobot.core.link.LinkLevel.ASK },
            ByteArray(app.heyrobot.core.link.LinkIds.BYTES) { 6 })),
        desk = listOf(app.heyrobot.core.link.DeskEntry("11".repeat(16), LINKS_PAIRING, app.heyrobot.core.link.DeskWay.IN, 1, app.heyrobot.core.link.DeskState.HANDLED, 1_800_000_100, 1_800_000_100, seen = true)))

    /** C10: backups with Skills connections ([connections]). */
    const val CONN = "backup-v1-conn.robotbackup"
    const val CONN_V2 = "backup-v1-conn-v2.robotbackup"
    const val CONN_PASSPHRASE = "robot-connections-fixture-passphrase"
    val CONN_SALT = ByteArray(BackupFormat.SALT) { (0x60 + it).toByte() }
    val CONN_V2_SALT = ByteArray(BackupFormat.SALT) { (0x80 + it).toByte() }

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
     * L12: PEOPLE, NOTES and LINKS, written by a 1.5 Robot: read as a Robot without the category (skipping "links" by name)
     * and as this one.
     */
    fun links(): ByteArray {
        val c = BackupKit.content()
        val content = linkedMapOf(BackupCategory.PEOPLE to c.getValue(BackupCategory.PEOPLE), BackupCategory.NOTES to c.getValue(BackupCategory.NOTES),
            BackupCategory.LINKS to linkedMapOf(
                BackupCategory.LINKS_FILE to BackupKit.text(app.heyrobot.core.link.LinkBackupCodec.encode(linksFile())),
                BackupCategory.LINK_SETTINGS to BackupKit.text(app.heyrobot.core.link.LinkSettingsCodec.encode(LINKS_SETTINGS))))
        return sealed(BackupKit.contentRecords(content, robot = "1.5.0"), BackupHeader(BackupFormat.FORMAT, LIGHT, LINKS_SALT, 1_050_000), LINKS_PASSPHRASE)
    }

    /**
     * Each fixture that isn't there yet (1.4: never one that is: a later release must open those very bytes, and the
     * content that made them has grown since; Robot Files' fixture was added this way).
     */
    fun write() {
        val dir = BackupKit.fixtureDir()
        dir.mkdirs()
        for ((name, make) in listOf<Pair<String, () -> ByteArray>>(BASIC to ::basic, NEWER to ::newer, FORMAT_2 to ::format2, FILES to ::files,
                LISTS to ::lists, EVERYDAY to ::everyday, LINKS to ::links, CONN to { connections(v2 = false) }, CONN_V2 to { connections(v2 = true) })) {
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

    /**
     * 1.4, Robot Lists (LS7): a backup made by 1.4 with People, Notes and Lists (lists.json and Settings › Lists), the light
     * KDF. BackupDowngradeTest reads it as 1.1 to 1.3 would (skipping "lists" by name) and as this Robot does.
     */
    fun lists(): ByteArray {
        val c = BackupKit.content()
        val content = linkedMapOf(BackupCategory.PEOPLE to c.getValue(BackupCategory.PEOPLE), BackupCategory.NOTES to c.getValue(BackupCategory.NOTES),
            BackupCategory.LISTS to c.getValue(BackupCategory.LISTS))
        return sealed(BackupKit.contentRecords(content, robot = "1.4.0"), BackupHeader(BackupFormat.FORMAT, LIGHT, LISTS_SALT, 1_040_099), LISTS_PASSPHRASE)
    }

    /**
     * The everyday set (E0): a backup made by the release that carries it (everything new ships in one release, the user's
     * decision of 22 Oct), with People, Notes and everyday_settings.json, the light KDF. BackupDowngradeTest reads it as a
     * Robot without the EVERYDAY category (skipping "everyday" by name) and as this Robot does.
     */
    fun everyday(): ByteArray {
        val c = BackupKit.content()
        val content = linkedMapOf(BackupCategory.PEOPLE to c.getValue(BackupCategory.PEOPLE), BackupCategory.NOTES to c.getValue(BackupCategory.NOTES),
            BackupCategory.EVERYDAY to mapOf(BackupCategory.EVERYDAY_FILE to BackupKit.text(app.heyrobot.core.everyday.EverydaySettingsCodec.encode(EVERYDAY_SETTINGS))))
        return sealed(BackupKit.contentRecords(content, robot = "1.4.0"), BackupHeader(BackupFormat.FORMAT, LIGHT, EVERYDAY_SALT, 1_040_099), EVERYDAY_PASSPHRASE)
    }

    /**
     * C10, Skills connections (plan D7, §5.7): People, Skills, Keys and private codes (helper and search keys, a code), and
     * both new categories: four connections (ConnBackupKit) and the keys of three, light KDF. [v2]: skills.json holds Good
     * night, whose connection steps make it v2 (D6); otherwise Heading home (v1). BackupDowngradeTest reads each as a 1.1
     * Robot would and as this Robot does.
     */
    fun connections(v2: Boolean): ByteArray {
        val c = BackupKit.content()
        val skills = if (v2) mapOf("skills.json" to BackupKit.text(app.heyrobot.core.skills.SkillCodec.encode(listOf(app.heyrobot.core.skills.SkillFixtures.goodNight()))))
            else c.getValue(BackupCategory.SKILLS)
        val content = linkedMapOf(BackupCategory.PEOPLE to c.getValue(BackupCategory.PEOPLE), BackupCategory.SKILLS to skills,
            BackupCategory.KEYS to c.getValue(BackupCategory.KEYS)) + ConnBackupKit.content()
        return sealed(BackupKit.contentRecords(content, robot = "1.4.0"),
            BackupHeader(BackupFormat.FORMAT, LIGHT, if (v2) CONN_V2_SALT else CONN_SALT, 1_040_099), CONN_PASSPHRASE)
    }
}
