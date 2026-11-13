// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.connect.ConnFixtures
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnSecretsCodec
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConnectionCodec
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.SecretPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Skills connections in backups (docs/plans/skills-connections-plan.md D7, §5.7; C10): the two categories' names, caps and
 * order (13 and 14, right after Files), Keys and private codes' row covering Connection keys, what verify reads (every
 * connection whole, each key's parts only, never a key), the plan's defaults and skips, Add by id and Replace with this
 * phone's keys dropped where the address changed, someone else's connections untrusted, and a key only ever going to the
 * address it was saved for.
 */
class BackupConnectionsTest {
    private val conns = ConnBackupKit.connections()

    @Test fun theCategoriesComeRightAfterFilesWithTheirNamesAndCaps() {
        // The voice log's category bits follow this order: Files 12, Connections 13, Connection keys 14, Lists 15, the everyday set 16.
        assertEquals(12, BackupCategory.FILES.ordinal)
        assertEquals(13, BackupCategory.CONNECTIONS.ordinal)
        assertEquals(14, BackupCategory.CONN_KEYS.ordinal)
        assertEquals("connections", BackupCategory.CONNECTIONS.id)
        assertEquals("conn_keys", BackupCategory.CONN_KEYS.id)
        assertTrue(BackupCategory.CONNECTIONS.defaultOn)
        assertFalse(BackupCategory.CONN_KEYS.defaultOn)
        assertEquals(RestoreProcess.VOICE, BackupCategory.CONNECTIONS.writer)
        assertEquals(RestoreProcess.VOICE, BackupCategory.CONN_KEYS.writer)
        assertEquals(1, BackupCategory.CONNECTIONS.maxEntries)
        assertEquals(20, BackupCategory.CONN_KEYS.maxEntries)
        // A Strong passphrase and "This backup is my own", as helper keys; deleted again when a restore is cut short.
        assertTrue(BackupCategory.CONN_KEYS in BackupCategory.SENSITIVE)
        assertFalse(BackupCategory.CONNECTIONS in BackupCategory.SENSITIVE)
        assertEquals(setOf(BackupCategory.KEYS, BackupCategory.CONN_KEYS), BackupCategory.KEY_CATEGORIES)
        // Names and caps: each entry far under what a 1.1 to 1.4 Robot reads through for a category it doesn't know.
        assertEquals(256L * 1024, BackupCategory.CONNECTIONS.maxBytes("connections.json"))
        assertEquals(16L * 1024, BackupCategory.CONN_KEYS.maxBytes("conn/c-ha1234"))
        assertTrue(BackupCategory.CONNECTIONS.maxBytes("connections.json")!! < BackupCaps.UNKNOWN_ENTRY)
        assertEquals(ConnectionCodec.VERSION, BackupCategory.CONNECTIONS.schema("connections.json"))
        assertEquals(ConnSecretsCodec.VERSION, BackupCategory.CONN_KEYS.schema("conn/c-ha1234"))
        assertEquals("c-ha1234", BackupCategory.connOf("conn/c-ha1234"))
        assertEquals("conn/c-ha1234", BackupCategory.connKey("c-ha1234"))
        // Never skills_state.json's counts, Test's facts, the key files themselves, or an id that isn't one.
        for (n in listOf("connections.json.bad", "skills_state.json", "connections/c-ha1234/key.bin", "key.json", "/connections.json")) {
            assertFalse(n, BackupCategory.CONNECTIONS.validName(n))
        }
        for (n in listOf("conn/C-HA1234", "conn/c-ha12345", "conn/../c-ha1234", "conn/", "helper/claude", "c-ha1234", "conn/c-ha1234/key.bin")) {
            assertFalse(n, BackupCategory.CONN_KEYS.validName(n))
        }
        // Keys and Settings never take them: a 1.1 reader refuses a whole known category at a name it doesn't know.
        assertFalse(BackupCategory.KEYS.validName("conn/c-ha1234"))
        assertFalse(BackupCategory.SKILLS.validName("connections.json"))
        assertFalse(BackupCategory.SETTINGS.validName("connections.json"))
    }

    @Test fun keysAndPrivateCodesRowCoversConnectionKeys() {
        assertEquals(BackupCategory.KEYS, BackupCategory.coveredBy(BackupCategory.CONN_KEYS))
        assertEquals(BackupCategory.CONNECTIONS, BackupCategory.coveredBy(BackupCategory.CONNECTIONS))
        assertEquals(setOf(BackupCategory.KEYS, BackupCategory.CONN_KEYS), BackupCategory.covers(BackupCategory.KEYS))
        assertEquals(setOf(BackupCategory.CONNECTIONS), BackupCategory.covers(BackupCategory.CONNECTIONS))
        // skills-backup-b: Skills' row covers the keys you trust and the MCP tool choices too.
        // auto-open-plan §4: Settings' row covers open_apps.json.
        assertEquals(BackupCategory.entries - BackupCategory.CONN_KEYS - BackupCategory.SKILL_TRUST - BackupCategory.OPEN_APPS - BackupCategory.SKILL_OPENS - BackupCategory.MOMENTS, BackupCategory.ROWS)
    }

    @Test fun aBackupWithConnectionsVerifiesKeepingOnlyEachKeysParts() {
        val file = ConnBackupKit.file()
        val v = BackupKit.verify(file)
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(setOf(BackupCategory.CONNECTIONS, BackupCategory.CONN_KEYS), v.found.keys)
        assertEquals(conns, v.connections)
        assertEquals(mapOf(ConnBackupKit.HA to setOf(SecretPart.TOKEN), ConnBackupKit.NTFY to setOf(SecretPart.TOKEN, SecretPart.TOPIC),
            ConnBackupKit.HOOK to setOf(SecretPart.ADDRESS)), v.connKeys)
        // Counts only in what the restore keeps around; never a key, an address or a name.
        assertFalse(v.toString().contains(BackupKit.MARK))
        assertFalse(v.toString().contains("tail1234"))
        val got = LinkedHashMap<String, ByteArray>()
        RestoreReader.read(BackupKit.opener(file), v, BackupKit.key(), setOf(BackupCategory.CONN_KEYS)) { _, n, d -> got[n] = d.readBytes() }
        assertEquals(listOf("conn/c-ha1234", "conn/c-ntfy01", "conn/c-disc01"), got.keys.toList())
        assertEquals(ConnBackupKit.secrets()[ConnBackupKit.NTFY], ConnSecretsCodec.decode(got.getValue("conn/c-ntfy01")))
    }

    private fun skipped(content: Map<BackupCategory, Map<String, ByteArray>>) = ConnBackupKit.verify(content).skipped.map { it.category to it.why }.toSet()

    @Test fun aNewerOrDamagedConnectionsFileIsNeverRestoredInPart() {
        val keys = ConnBackupKit.content().getValue(BackupCategory.CONN_KEYS)
        fun with(text: String) = linkedMapOf(BackupCategory.CONNECTIONS to mapOf("connections.json" to BackupKit.text(text)), BackupCategory.CONN_KEYS to keys)
        assertEquals(setOf(BackupCategory.CONNECTIONS to SkipWhy.NEWER), skipped(with("{\"v\":2,\"connections\":[]}")))
        assertEquals(setOf(BackupCategory.CONNECTIONS to SkipWhy.REFUSED), skipped(with("{\"v\":1}")))
        assertEquals(setOf(BackupCategory.CONNECTIONS to SkipWhy.REFUSED), skipped(with("{\"v\":1,\"connections\":[],\"extra\":1}")))
        // One connection this Robot drops (an unknown key) refuses the whole file: a skill would point at one that didn't come back.
        val one = ConnectionCodec.toJson(ConnFixtures.ntfy()).toMutableMap().apply { put("color", "red") }
        val text = app.heyrobot.core.connect.ConnJson.write(linkedMapOf("v" to 1L, "connections" to listOf(ConnectionCodec.toJson(ConnFixtures.homeAssistant()), one)))
        assertEquals(setOf(BackupCategory.CONNECTIONS to SkipWhy.REFUSED), skipped(with(text)))
        // Keys stay: they are their own category.
        assertTrue(ConnBackupKit.verify(with(text)).has(BackupCategory.CONN_KEYS))
    }

    @Test fun aNewerDamagedEmptyOrRepeatedKeyRefusesConnectionKeysOnly() {
        val c = ConnBackupKit.content().getValue(BackupCategory.CONNECTIONS)
        fun keys(vararg e: Pair<String, String>) = linkedMapOf(BackupCategory.CONNECTIONS to c, BackupCategory.CONN_KEYS to e.associate { (n, t) -> n to BackupKit.text(t) })
        assertEquals(setOf(BackupCategory.CONN_KEYS to SkipWhy.NEWER), skipped(keys("conn/c-ha1234" to "{\"v\":2,\"token\":\"x\"}")))
        assertEquals(setOf(BackupCategory.CONN_KEYS to SkipWhy.REFUSED), skipped(keys("conn/c-ha1234" to "{\"v\":1,\"token\":\" spaced \"}")))
        assertEquals(setOf(BackupCategory.CONN_KEYS to SkipWhy.REFUSED), skipped(keys("conn/c-ha1234" to "{\"v\":1}")))
        assertEquals(setOf(BackupCategory.CONN_KEYS to SkipWhy.REFUSED), skipped(keys("conn/c-ha1234" to "{\"v\":1,\"pin\":\"1234\"}")))
        assertEquals(setOf(BackupCategory.CONN_KEYS to SkipWhy.REFUSED), skipped(keys("conn/c-ha1234" to "not json")))
        // A name outside its rules refuses the category, as every category's does (the writer never makes one: raw records).
        val outside = RawRecords().manifest().entry("conn_keys", "conn/../x", BackupKit.text("{\"v\":1,\"token\":\"x\"}")).end().bytes()
        assertEquals(listOf(CategorySkip("conn_keys", BackupCategory.CONN_KEYS, SkipWhy.REFUSED)), BackupKit.verify(BackupKit.encrypt(outside)).skipped)
        // The same connection's key twice: the container refuses the whole file as damaged (any entry twice), never one picked.
        val twice = RawRecords().manifest()
            .entry("conn_keys", "conn/c-ha1234", BackupKit.text("{\"v\":1,\"token\":\"one\"}"))
            .entry("conn_keys", "conn/c-ha1234", BackupKit.text("{\"v\":1,\"token\":\"two\"}"))
            .end().bytes()
        assertEquals(OpenProblem.DAMAGED, BackupKit.problem { BackupKit.verify(BackupKit.encrypt(twice)) })
        // Over its cap.
        val big = "{\"v\":1,\"token\":\"" + "a".repeat(17 * 1024) + "\"}"
        val over = RawRecords().manifest().entry("conn_keys", "conn/c-ha1234", BackupKit.text(big)).end().bytes()
        assertEquals(listOf(CategorySkip("conn_keys", BackupCategory.CONN_KEYS, SkipWhy.TOO_BIG)), BackupKit.verify(BackupKit.encrypt(over)).skipped)
    }

    @Test fun thePlanOffersConnectionsAndAsksForKeysAndYourOwnBackup() {
        val v = ConnBackupKit.verify()
        val d = RestorePlanner.defaults(v)
        assertTrue(d.getValue(BackupCategory.CONNECTIONS).restore)
        assertFalse(d.getValue(BackupCategory.CONN_KEYS).restore)
        val ticked = d + (BackupCategory.KEYS to CategoryChoice()) + (BackupCategory.CONN_KEYS to CategoryChoice())
        val fresh = RestorePlanner.plan(v, PhoneHas(emptyMap()), ticked, mine = true)
        assertEquals(listOf(BackupCategory.CONNECTIONS, BackupCategory.CONN_KEYS), fresh.restoring)
        assertEquals(RestoreMode.FRESH, fresh.of(BackupCategory.CONNECTIONS).mode)
        assertEquals(RestoreMode.ADD, fresh.of(BackupCategory.CONN_KEYS).mode)
        // Not yours: connections come (untrusted), keys never.
        val theirs = RestorePlanner.plan(v, PhoneHas(emptyMap()), ticked, mine = false)
        assertEquals(listOf(BackupCategory.CONNECTIONS), theirs.restoring)
        assertEquals(PlanSkip.NOT_YOURS, theirs.of(BackupCategory.CONN_KEYS).skip)
        // Connections here: Add by default, Replace when chosen.
        val here = PhoneHas(mapOf(BackupCategory.CONNECTIONS to 2, BackupCategory.CONN_KEYS to 1))
        assertEquals(RestoreMode.ADD, RestorePlanner.plan(v, here, ticked, true).of(BackupCategory.CONNECTIONS).mode)
        assertEquals(RestoreMode.REPLACE, RestorePlanner.plan(v, here, ticked + (BackupCategory.CONNECTIONS to CategoryChoice(replace = true)), true)
            .of(BackupCategory.CONNECTIONS).mode)
        // A connections.json here Robot can't read is never written over: both left out, with their words.
        val unreadable = RestorePlanner.plan(v, here.copy(connections = false), ticked, true)
        assertEquals(PlanSkip.CONNECTIONS_UNREADABLE, unreadable.of(BackupCategory.CONNECTIONS).skip)
        assertEquals(PlanSkip.CONNECTIONS_UNREADABLE, unreadable.of(BackupCategory.CONN_KEYS).skip)
        val en = app.heyrobot.core.i18n.Langs(app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.EN,
            app.heyrobot.core.i18n.Medium.SCREEN)
        assertEquals("Robot can't read the connections on this phone, so it leaves Connections and their keys out.",
            BackupWords.skip(unreadable.of(BackupCategory.CONNECTIONS), "1.4.0")!!.render(en))
        assertEquals("Connections", BackupWords.category(BackupCategory.CONNECTIONS)().render(en))
        assertEquals("Connection keys", BackupWords.category(BackupCategory.CONN_KEYS)().render(en))
    }

    @Test fun addKeepsEveryConnectionHereAndAddsTheRest() {
        val mine = ConnFixtures.homeAssistant(ConnBackupKit.HA).copy(name = "Home", address = ConnFixtures.url("https://ha.example.org"))
        val r = RestoreMerge.connections(listOf(mine), conns, RestoreMode.ADD, mine = true, keyIds = emptySet())
        // Yours stays exactly as it is (its address, its key), whatever the file's of the same id says.
        assertEquals(listOf(mine) + conns.drop(1), r.connections)
        assertEquals(3, r.added)
        assertEquals(emptySet<String>(), r.dropKeys)
        // A second restore adds nothing.
        assertEquals(r.connections, RestoreMerge.connections(r.connections, conns, RestoreMode.ADD, mine = true, keyIds = emptySet()).connections)
        // At most 20.
        val many = (1..19).map { ConnFixtures.ntfy("c-n%05d".format(it)) }
        val capped = RestoreMerge.connections(many, conns, RestoreMode.ADD, mine = true, keyIds = emptySet())
        assertEquals(20, capped.connections.size)
        assertEquals(1, capped.added)
    }

    @Test fun replaceDropsThisPhonesKeyWhereTheConnectionGoesOrMoves() {
        val same = ConnFixtures.homeAssistant(ConnBackupKit.HA).copy(name = "Home", ops = emptyList())
        val moved = ConnFixtures.ntfy(ConnBackupKit.NTFY).copy(address = ConnFixtures.url("https://ntfy.elsewhere.example"))
        val gone = ConnFixtures.webhook("c-gone01")
        val have = listOf(same, moved, gone)
        val r = RestoreMerge.connections(have, conns, RestoreMode.REPLACE, mine = true, keyIds = emptySet())
        assertEquals(conns, r.connections)
        // Kept: the same connection, reaching the same place. Dropped: one that now reaches another address, one that goes.
        assertEquals(setOf(ConnBackupKit.NTFY, "c-gone01"), r.dropKeys)
        // From someone else's backup, every key here goes.
        assertEquals(setOf(ConnBackupKit.HA, ConnBackupKit.NTFY, "c-gone01"), RestoreMerge.connections(have, conns, RestoreMode.REPLACE, mine = false, keyIds = emptySet()).dropKeys)
        // A phone with none: the file's, ids kept.
        assertEquals(conns.map { it.id }, RestoreMerge.connections(emptyList(), conns, RestoreMode.FRESH, mine = true, keyIds = emptySet()).connections.map { it.id })
    }

    /**
     * C12's L1: a key folder here without a connection (a stray) goes before the connections arrive, in every mode and
     * from any backup, so a connection the file brings under its id never gets it; a key of a connection here is no stray.
     */
    @Test fun aStrayKeyGoesBeforeConnectionsArrive() {
        val stray = "c-7k2m9q"
        val theirs = ConnFixtures.homeAssistant(stray)
        val here = ConnFixtures.ntfy(ConnBackupKit.NTFY)
        for (mode in listOf(RestoreMode.ADD, RestoreMode.REPLACE, RestoreMode.FRESH)) for (yours in listOf(false, true)) {
            val none = RestoreMerge.connections(emptyList(), listOf(theirs), mode, yours, keyIds = setOf(stray))
            assertEquals("$mode $yours", setOf(stray), none.dropKeys)
            assertEquals(listOf(stray), none.connections.map { it.id })
            val beside = RestoreMerge.connections(listOf(here), listOf(theirs), mode, yours, keyIds = setOf(stray, here.id))
            assertTrue("$mode $yours", stray in beside.dropKeys)
            // Add (and FRESH beside a connection, which is Add) keeps the key of the connection here.
            if (mode != RestoreMode.REPLACE) assertEquals("$mode $yours", setOf(stray), beside.dropKeys)
        }
        assertEquals(emptySet<String>(), RestoreMerge.connections(listOf(here), listOf(theirs), RestoreMode.ADD, false, keyIds = setOf(here.id)).dropKeys)
    }

    /** release/1.1's rule for a category empty when the preview opened: one added since stays, with its key, as Add keeps it. */
    @Test fun freshKeepsAConnectionAddedSinceThePreview() {
        val since = ConnFixtures.homeAssistant(ConnBackupKit.HA).copy(name = "Home", address = ConnFixtures.url("https://ha.example.org"))
        fun parts(r: ConnectionsRestored) = listOf(r.connections, r.added, r.dropKeys, r.kept)
        for (yours in listOf(false, true)) {
            val fresh = RestoreMerge.connections(listOf(since), conns, RestoreMode.FRESH, yours, keyIds = emptySet())
            assertEquals(parts(RestoreMerge.connections(listOf(since), conns, RestoreMode.ADD, yours, keyIds = emptySet())), parts(fresh))
            assertEquals(since, fresh.connections.first())
            assertEquals(emptySet<String>(), fresh.dropKeys)
            // Still empty: the same as Replace.
            assertEquals(parts(RestoreMerge.connections(emptyList(), conns, RestoreMode.REPLACE, yours, keyIds = emptySet())),
                parts(RestoreMerge.connections(emptyList(), conns, RestoreMode.FRESH, yours, keyIds = emptySet())))
        }
    }

    @Test fun someoneElsesConnectionsArriveUntrusted() {
        val ha = conns[0].copy(spokenYes = true, perMinute = 60)
        val r = RestoreMerge.connections(emptyList(), listOf(ha) + conns.drop(1), RestoreMode.FRESH, mine = false, keyIds = emptySet())
        for (c in r.connections) {
            assertEquals(c.id, emptyMap<String, ConsentAnswer>(), c.answers)
            assertFalse(c.id, c.spokenYes)
            assertNull(c.id, c.tested)
            assertNull(c.id, c.where)
            assertTrue(c.id, c.perMinute <= Connection.DEFAULT_PER_MINUTE)
        }
        // A hand-off comes without its signer: picked again on this phone before it opens anything.
        val hand = r.connections.single { it.id == ConnBackupKit.APP }.handOff!!
        assertNull(hand.signer)
        assertFalse(hand.pinned)
        assertEquals(conns.single { it.id == ConnBackupKit.APP }.handOff!!.copy(signer = null), hand)
        // What it reaches and does comes as the file has it.
        assertEquals(ha.address, r.connections[0].address)
        assertEquals(ha.ops, r.connections[0].ops)
        assertEquals(setOf(SaferField.CONN_ANSWERS, SaferField.CONN_SPOKEN_YES, SaferField.CONN_PER_MINUTE, SaferField.CONN_TESTED,
            SaferField.CONN_WHERE, SaferField.HAND_OFF_SIGNER), r.kept.toSet())
        // Each still reads back as a connection connections.json takes.
        assertTrue(r.connections.all { app.heyrobot.core.connect.ConnCheck.valid(it) })
        assertEquals(r.connections, (ConnectionCodec.decode(ConnectionCodec.encode(r.connections)) as app.heyrobot.core.connect.ConnFile.Ok).connections)
        // Yours: as saved, first-use answers, tests and pins kept.
        val yours = RestoreMerge.connections(emptyList(), conns, RestoreMode.FRESH, mine = true, keyIds = emptySet())
        assertEquals(conns, yours.connections)
        assertEquals(emptyList<SaferField>(), yours.kept)
    }

    @Test fun aKeyGoesOnlyToTheAddressItWasSavedFor() {
        val parts = ConnBackupKit.secrets().mapValues { it.value.parts }
        val none: (String) -> Boolean = { false }
        val all = RestoreMerge.connectionKeys(parts, conns, conns, none, mine = true)
        assertEquals(listOf("conn/c-ha1234", "conn/c-ntfy01", "conn/c-disc01"), all.save)
        assertEquals(emptyMap<String, KeySkip>(), all.skipped)
        // Not yours: none.
        assertEquals(parts.keys.associate { BackupCategory.connKey(it) to KeySkip.NOT_YOURS }, RestoreMerge.connectionKeys(parts, conns, conns, none, mine = false).skipped)
        // A connection here of the same id that reaches another address never gets it.
        val elsewhere = conns.map { if (it.id == ConnBackupKit.HA) it.copy(address = ConnFixtures.url("https://evil.example.org")) else it }
        assertEquals(KeySkip.OTHER_ADDRESS, RestoreMerge.connectionKeys(parts, conns, elsewhere, none, true).skipped["conn/c-ha1234"])
        // Nor one whose key goes another way (a header instead of Bearer), or a webhook whose address stopped being the key.
        val header = conns.map { if (it.id == ConnBackupKit.HA) it.copy(auth = app.heyrobot.core.connect.ConnAuth.Header("X-Api-Key")) else it }
        assertEquals(KeySkip.OTHER_ADDRESS, RestoreMerge.connectionKeys(parts, conns, header, none, true).skipped["conn/c-ha1234"])
        // No connection here (or none in the backup to say what the key was for).
        assertEquals(KeySkip.NO_SUCH_CONNECTION, RestoreMerge.connectionKeys(parts, conns, conns.drop(1), none, true).skipped["conn/c-ha1234"])
        assertEquals(KeySkip.NO_SUCH_CONNECTION, RestoreMerge.connectionKeys(parts, null, conns, none, true).skipped["conn/c-ha1234"])
        // The parts must be exactly those the connection needs.
        val tokenOnly = parts + (ConnBackupKit.NTFY to setOf(SecretPart.TOKEN))
        assertEquals(KeySkip.OTHER_PARTS, RestoreMerge.connectionKeys(tokenOnly, conns, conns, none, true).skipped["conn/c-ntfy01"])
        // A key entered here is never replaced.
        assertEquals(KeySkip.ALREADY_HERE, RestoreMerge.connectionKeys(parts, conns, conns, { it == ConnBackupKit.HOOK }, true).skipped["conn/c-disc01"])
    }

    @Test fun sameOriginIsWhatAKeyIsBoundTo() {
        val ha = conns[0]
        assertTrue(RestoreMerge.sameOrigin(ha, ha.copy(name = "Other", ops = emptyList(), answers = emptyMap(), tested = null, spokenYes = false)))
        assertFalse(RestoreMerge.sameOrigin(ha, ha.copy(address = ConnFixtures.url("https://home.tail1234.ts.net:8443"))))
        assertFalse(RestoreMerge.sameOrigin(ha, ha.copy(auth = app.heyrobot.core.connect.ConnAuth.Basic)))
        assertFalse(RestoreMerge.sameOrigin(conns[2], conns[2].copy(secretAddress = false)))
        assertFalse(RestoreMerge.sameOrigin(ConnFixtures.routing(), ConnFixtures.routing(kind = app.heyrobot.core.connect.RoutingKind.VALHALLA)))
        assertFalse(RestoreMerge.sameOrigin(conns[3], conns[3].copy(handOff = conns[3].handOff!!.copy(pkg = "org.example.other"))))
        // A hand-off's own signer isn't its origin: picking it again pins this phone's.
        assertTrue(RestoreMerge.sameOrigin(conns[3], conns[3].copy(handOff = conns[3].handOff!!.copy(signer = null))))
    }

    @Test fun aCutRunDeletesTheConnectionKeysItSaved() {
        val dir = Files.createTempDirectory("journal").toFile()
        try {
            val j = RestoreJournal(dir)
            j.begin(BackupCategory.CONN_KEYS, RestoreProcess.VOICE)
            j.keySaving("conn/c-ha1234", BackupCategory.CONN_KEYS)
            j.keySaving("conn/c-ntfy01", BackupCategory.CONN_KEYS)
            assertEquals(listOf("conn/c-ha1234", "conn/c-ntfy01"), RestoreJournal.decode(File(dir, RestoreJournal.FILE).readText())[BackupCategory.CONN_KEYS]!!.keys)
            // Only names of its own category.
            assertTrue(runCatching { j.keySaving("helper/claude", BackupCategory.CONN_KEYS) }.isFailure)
            assertTrue(runCatching { j.keySaving("conn/c-ha1234", BackupCategory.NOTES) }.isFailure)
            val deleted = ArrayList<String>()
            j.recover(RestoreProcess.VOICE, { _, _ -> }, { deleted += it })
            assertEquals(listOf("conn/c-ha1234", "conn/c-ntfy01"), deleted)
            assertTrue(j.read().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun backUpNowOffersConnectionsAtTheirDefaultToAnOlderChoice() {
        // A 1.1 file has no "offered": Connections starts on, Connection keys off (as Keys).
        val old = BackupState.fromJson("{\"chosen\":[\"notes\",\"keys\"]}")
        assertTrue(BackupCategory.CONNECTIONS in old.chosen)
        assertFalse(BackupCategory.CONN_KEYS in old.chosen)
        // A file saved before C10 offered them: the same.
        val before = "{\"chosen\":[\"notes\"],\"offered\":" + org.json.JSONArray((BackupCategory.entries - BackupCategory.CONNECTIONS - BackupCategory.CONN_KEYS).map { it.id }) + "}"
        assertEquals(setOf(BackupCategory.NOTES, BackupCategory.CONNECTIONS), BackupState.fromJson(before).chosen)
        // Once offered, unticking it sticks.
        val off = BackupState(chosen = setOf(BackupCategory.NOTES))
        assertEquals(off, BackupState.fromJson(off.toJson()))
        // A backup with connection keys alone has keys in it.
        assertTrue(BackupState().after(1, setOf(BackupCategory.CONNECTIONS, BackupCategory.CONN_KEYS), 10, true).lastKeys)
        assertFalse(BackupState().after(1, setOf(BackupCategory.CONNECTIONS), 10, true).lastKeys)
    }

    @Test fun yourOwnBackupBringsBackTheTestAndTheAnswersAsSaved() {
        val ha = conns[0]
        assertEquals(ConnWhere.HOME, ha.where)
        assertEquals(ConnTested(1_761_000_000_000L, "2026.10.1"), ha.tested)
        assertEquals(ha, RestoreSafety.connection(null, ha, mine = true).value)
        // Someone else's of the same connection here: this phone's facts stay where the file's are looser.
        val here = ha.copy(answers = emptyMap(), spokenYes = false, perMinute = 10)
        val g = RestoreSafety.connection(here, ha.copy(perMinute = 30), mine = false)
        assertEquals(emptyMap<String, ConsentAnswer>(), g.value.answers)
        assertFalse(g.value.spokenYes)
        assertEquals(10, g.value.perMinute)
        assertEquals(ha.tested, g.value.tested)
        assertEquals(setOf(SaferField.CONN_ANSWERS, SaferField.CONN_SPOKEN_YES, SaferField.CONN_PER_MINUTE), g.kept.toSet())
        // Fewer requests a minute is safer, so it comes as the file has it.
        assertEquals(5, RestoreSafety.connection(here, ha.copy(perMinute = 5), mine = false).value.perMinute)
    }

    @Test fun nothingOfAKeyReachesWhatTheRestoreKeeps() {
        val v = ConnBackupKit.verify()
        val plan = RestoreMerge.connectionKeys(v.connKeys, v.connections, v.connections!!, { false }, mine = true)
        val shown = listOf(v.toString(), plan.toString(), RestoreMerge.connections(emptyList(), v.connections!!, RestoreMode.FRESH, true, keyIds = emptySet()).toString())
        for (s in shown) for (secret in ConnBackupKit.secrets().values.flatMap { sec -> sec.parts.map { sec[it]!! } }) assertFalse(s, s.contains(secret))
        for (s in shown) assertFalse(s, s.contains(BackupKit.MARK))
        assertEquals(ConnSecrets(token = "x").parts, setOf(SecretPart.TOKEN))
    }
}
