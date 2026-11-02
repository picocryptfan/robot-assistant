// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsCodec
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.core.lists.ListsMergeResult
import app.heyrobot.core.lists.ListsParse
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.ListsSettingsCodec
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.NewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * lists-plan.md §6 (LS7): the LISTS category's names and caps (after Files, format 1 unchanged), what verify reads (lists.json
 * by its strict codec, whole or not at all; lists_settings.json by its own; a newer one of either left for a newer Robot),
 * the plan on a phone whose lists can't be read, and Add and Replace through ListsMerge, someone else's lists apart.
 */
class BackupListsTest {
    private val random = SecureRandom()
    private val ids: () -> ListsId = { ListsId.new(random) }
    private val roleName: (ListRole) -> String = { if (it == ListRole.SHOPPING) "Shopping" else "To-do" }

    private fun ok(r: ListsMergeResult): ListsFile = (r as ListsMergeResult.Ok).merged.file

    private fun verify(vararg entries: Pair<String, ByteArray>, v: Int? = null): VerifiedBackup {
        val r = RawRecords().manifest()
        for ((n, b) in entries) r.entry("lists", n, b, v)
        return BackupKit.verify(BackupKit.encrypt(r.end().bytes()))
    }

    @Test fun theCategoryComesAfterConnectionKeysWithItsNamesAndCaps() {
        // Deliberately no longer the last: the everyday set's EVERYDAY comes after it, and its place never changes.
        assertEquals(BackupCategory.LISTS.ordinal + 1, BackupCategory.EVERYDAY.ordinal)
        assertEquals(BackupCategory.CONN_KEYS.ordinal + 1, BackupCategory.LISTS.ordinal)
        assertEquals("lists", BackupCategory.LISTS.id)
        assertTrue(BackupCategory.LISTS.defaultOn)
        assertEquals(RestoreProcess.VOICE, BackupCategory.LISTS.writer)
        assertEquals(2, BackupCategory.LISTS.maxEntries)
        assertEquals(1, BackupFormat.FORMAT)
        // The voice log's bit for it follows the enum's order: nothing was inserted.
        assertEquals(1 shl 15, BackupExport.bits(setOf(BackupCategory.LISTS)))
        assertEquals(ListsLimits.MAX_FILE.toLong(), BackupCategory.LISTS.maxBytes("lists.json"))
        assertEquals(BackupCaps.SMALL_FILE, BackupCategory.LISTS.maxBytes("lists_settings.json"))
        for (n in listOf("lists.json", "lists_settings.json")) assertTrue(n, BackupCategory.LISTS.maxBytes(n)!! < BackupCaps.UNKNOWN_ENTRY)
        assertEquals(ListsCodec.VERSION, BackupCategory.LISTS.schema("lists.json"))
        assertEquals(ListsSettingsCodec.VERSION, BackupCategory.LISTS.schema("lists_settings.json"))
        // Never the sealed file, the journal, the copies kept aside, the widgets' ids, or a path out.
        for (n in listOf("lists.enc", "locked.enc", "lists.enc.bad", "locked.enc.bad", "lists.lock", "widgets.json", "lists/lists.json", "../lists.json",
                "/lists.json", "lists.json.tmp", "Lists.json")) assertFalse(n, BackupCategory.LISTS.validName(n))
        // Settings stays at its 8 files: lists_settings.json is Lists', never a ninth, and no other category names it.
        assertEquals(8, BackupCategory.SETTINGS_FILES.size)
        for (c in BackupCategory.entries - BackupCategory.LISTS) assertNull(c.name, c.maxBytes("lists_settings.json"))
        assertEquals("Lists", BackupWords.category(BackupCategory.LISTS)().render(Langs.EN))
        assertEquals("Listes", BackupWords.category(BackupCategory.LISTS)().render(Langs.of(app.heyrobot.core.i18n.Lang.FR)))
    }

    @Test fun aBackupWithListsVerifies() {
        val v = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords()))
        assertTrue(v.has(BackupCategory.LISTS))
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        // Every list and item whole, the tombstone too.
        assertEquals(BackupKit.lists(), v.lists)
        assertTrue(v.lists!!.lists[0].items.any { !it.live })
        assertEquals(BackupKit.listsSettings(), v.listsSettings!!.read)
        // Counts only in its words.
        assertFalse(v.toString().contains(BackupKit.MARK))
    }

    @Test fun newerOrDamagedListsAreNotRestored() {
        val lists = BackupExport.lists(BackupKit.lists())
        // A newer lists.json (by its record or its own "v"): left for a newer Robot.
        assertEquals(listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.NEWER)), verify("lists.json" to lists, v = 2).skipped)
        assertEquals(listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.NEWER)),
            verify("lists.json" to BackupKit.text("{\"v\":2,\"lists\":[],\"new\":true}")).skipped)
        // One this Robot can't read whole is refused whole: an item left out would be gone.
        for (bad in listOf("{\"v\":1}", "{\"v\":1,\"lists\":[{\"id\":\"zz\",\"role\":\"shopping\",\"made\":1,\"rev\":1,\"from\":\"you\",\"items\":[]}]}",
                String(lists).replace("\"oat milk", "\"oat\\nmilk"))) {
            val v = verify("lists.json" to BackupKit.text(bad))
            assertEquals(bad, listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.REFUSED)), v.skipped)
            assertNull(v.lists)
            assertFalse(v.has(BackupCategory.LISTS))
        }
        // Over 2 MiB: too big, without reading it.
        assertEquals(listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.TOO_BIG)),
            verify("lists.json" to ByteArray(ListsLimits.MAX_FILE + 1) { ' '.code.toByte() }).skipped)
        // lists_settings.json: newer left alone, a wrong type refuses the category, with lists.json and all.
        assertEquals(listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.NEWER)),
            verify("lists.json" to lists, "lists_settings.json" to BackupKit.text("{\"v\":2,\"on\":true}")).skipped)
        val damaged = verify("lists.json" to lists, "lists_settings.json" to BackupKit.text("{\"v\":1,\"addLocked\":\"yes\"}"))
        assertEquals(listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.REFUSED)), damaged.skipped)
        assertNull(damaged.lists)
        // A name it doesn't have refuses it too.
        assertEquals(listOf(CategorySkip("lists", BackupCategory.LISTS, SkipWhy.REFUSED)), verify("lists.json" to lists, "lists.enc" to lists).skipped)
        // Settings alone come back alone.
        val alone = verify("lists_settings.json" to BackupKit.text(ListsSettingsCodec.encode(ListsSettings(on = false))))
        assertTrue(alone.has(BackupCategory.LISTS))
        assertNull(alone.lists)
        assertEquals(ListsSettings(on = false), alone.listsSettings!!.read)
    }

    @Test fun thePlanOnThisPhone() {
        val v = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords()))
        fun plan(phone: PhoneHas, replace: Boolean = false) =
            RestorePlanner.plan(v, phone, RestorePlanner.defaults(v, phone) + (BackupCategory.LISTS to CategoryChoice(replace = replace)), mine = true).of(BackupCategory.LISTS)
        // Nothing here: simply written. Some here: Add, or Replace when chosen.
        assertEquals(RestoreMode.FRESH, plan(PhoneHas(emptyMap())).mode)
        assertEquals(RestoreMode.ADD, plan(PhoneHas(mapOf(BackupCategory.LISTS to 2))).mode)
        assertEquals(RestoreMode.REPLACE, plan(PhoneHas(mapOf(BackupCategory.LISTS to 2)), replace = true).mode)
        assertTrue(RestorePlanner.defaults(v)[BackupCategory.LISTS]!!.restore)
        // Their key gone: Replace only (the file here is kept aside), and on by default: nothing here is deleted.
        val gone = PhoneHas(mapOf(BackupCategory.LISTS to 1), lists = ListsHere.KEY_GONE)
        assertEquals(RestoreMode.REPLACE, plan(gone).mode)
        assertNull(plan(gone).skip)
        assertTrue(RestorePlanner.defaults(v, gone)[BackupCategory.LISTS]!!.restore)
        // Damaged or a newer Robot's here: never written over, left out with its words.
        val unreadable = plan(PhoneHas(mapOf(BackupCategory.LISTS to 1), lists = ListsHere.UNREADABLE))
        assertEquals(PlanSkip.LISTS_UNREADABLE, unreadable.skip)
        assertEquals("Robot can't read the lists on this phone, so it leaves Lists out. Settings › Lists can start over; then restore again.",
            BackupWords.skip(unreadable, "1.4.0")!!.render(Langs.EN))
        assertTrue(BackupWords.skip(unreadable, "1.4.0")!!.render(Langs.of(app.heyrobot.core.i18n.Lang.FR)).startsWith("Robot ne peut pas lire les listes"))
        // Files' state never decides Lists', nor Lists' Files'.
        assertNull(plan(PhoneHas(mapOf(BackupCategory.LISTS to 1), files = FilesHere.UNREADABLE)).skip)
        assertEquals("4 lists here will be replaced.", app.heyrobot.core.i18n.BackupStrings.listsReplaced(4L).render(Langs.EN))
    }

    @Test fun yourBackupIsAddedByIdAndAgainChangesNothing() {
        val file = BackupKit.lists()
        val t = BackupKit.T0 + 100
        // This phone: its first-use lists, an item said on Shopping.
        val here = ListsEdits.firstUse(t, ids).let { f ->
            (ListsEdits.add(f, f.lists[0].id, listOf(NewItem("apples")), ListsWho(ItemBy.YOU, ItemHow.VOICE, t), ids) as ListsEdit.Done).file
        }
        val once = ok(RestoreMerge.lists(here, file, RestoreMode.ADD, mine = true, ids = ids, roleName = roleName))
        // Your Shopping here takes the backup's items (by role); Packing is added; To-do stays.
        assertEquals(3, once.lists.size)
        val shopping = once.withRole(ListRole.SHOPPING)!!
        assertEquals(here.lists[0].id, shopping.id)
        assertEquals(listOf("apples", "oat milk ${BackupKit.MARK}", "eggs", "bread"), shopping.items.map { it.text })
        assertTrue(once.lists.any { it.name == "Packing" && it.yours })
        assertTrue(once.lists.all { it.from == ListFrom.YOU })
        // Restoring the same backup again adds nothing.
        assertEquals(once, ok(RestoreMerge.lists(once, file, RestoreMode.ADD, mine = true, ids = ids, roleName = roleName)))
        // A newer edit of an item here beats the backup's older one; a tombstone in the backup beats an older item here.
        val milk = ListsId.of(BackupKit.ITEM_MILK)!!
        val edited = once.with(shopping.copy(items = shopping.items.map { if (it.id == milk) it.copy(text = "oat milk", rev = 5, upd = t + 5) else it }))
        assertEquals("oat milk", ok(RestoreMerge.lists(edited, file, RestoreMode.ADD, true, ids, roleName)).holding(milk)!!.item(milk)!!.text)
    }

    @Test fun replaceTakesTheBackupsLists() {
        val here = ListsEdits.firstUse(BackupKit.T0, ids)
        val r = RestoreMerge.lists(here, BackupKit.lists(), RestoreMode.REPLACE, mine = true, ids = ids, roleName = roleName)
        assertEquals(BackupKit.lists(), ok(r))
        assertEquals(2, (r as ListsMergeResult.Ok).merged.listsAdded)
        // From a phone with nothing, Fresh is the same as Add onto nothing.
        assertEquals(BackupKit.lists(), ok(RestoreMerge.lists(ListsFile(), BackupKit.lists(), RestoreMode.FRESH, true, ids, roleName)))
    }

    @Test fun someoneElsesListsStayApart() {
        val here = ListsEdits.firstUse(BackupKit.T0, ids)
        val add = ok(RestoreMerge.lists(here, BackupKit.lists(), RestoreMode.ADD, mine = false, ids = ids, roleName = roleName))
        // Yours as they were; theirs separate, marked, with no role (your Shopping still takes your voice adds).
        assertEquals(here.lists, add.lists.take(2))
        val theirs = add.lists.drop(2)
        assertEquals(listOf("Shopping", "Packing"), theirs.map { it.name })
        assertTrue(theirs.all { it.from == ListFrom.BACKUP && it.role == null && !it.yours })
        assertTrue(theirs.flatMap { it.items }.all { it.by == ItemBy.BACKUP && it.hist.all { h -> h.by == ItemBy.BACKUP } })
        assertEquals(here.withRole(ListRole.SHOPPING)!!.id, add.withRole(ListRole.SHOPPING)!!.id)
        // Restoring it again merges into those same lists, never into yours, and adds nothing.
        assertEquals(add, ok(RestoreMerge.lists(add, BackupKit.lists(), RestoreMode.ADD, mine = false, ids = ids, roleName = roleName)))
        // Replace: only theirs, still marked.
        val replaced = ok(RestoreMerge.lists(here, BackupKit.lists(), RestoreMode.REPLACE, mine = false, ids = ids, roleName = roleName))
        assertEquals(2, replaced.lists.size)
        assertTrue(replaced.lists.all { it.from == ListFrom.BACKUP && it.role == null })
        // The French role name when the app speaks French.
        val fr = ok(RestoreMerge.lists(ListsFile(), BackupKit.lists(), RestoreMode.ADD, false, ids) { if (it == ListRole.SHOPPING) "Épicerie" else "À faire" })
        assertEquals("Épicerie", fr.lists[0].name)
    }

    @Test fun theExportIsTheLists() {
        val bytes = BackupExport.lists(BackupKit.lists())
        assertEquals(ListsParse.Ok(BackupKit.lists()), ListsCodec.decode(bytes))
        assertNotNull(app.heyrobot.core.StrictJson.parse(bytes))
    }
}
