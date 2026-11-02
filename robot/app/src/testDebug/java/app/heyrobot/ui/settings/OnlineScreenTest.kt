// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetFiles
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.NetLog
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.LocalLangs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings › Safety & privacy › What went online (everyday-set-plan.md §5.5): the record by day with the header, a row's
 * details and the page that controls it, the switch and Delete, and NetLog writing only while the switch is on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class OnlineScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val log = ActionLog()

    @After fun clean() {
        NetLog.dir(ctx).deleteRecursively()
        EverydaySettingsStore.file(ctx).delete()
        EverydaySettingsStore.forget()
        NetRecord.sink = null
        NetRecord.process = "main"
    }

    private fun seed(vararg rows: NetEntry) {
        val d = NetLog.dir(ctx).apply { mkdirs() }
        for ((day, list) in rows.groupBy { it.process to NetFiles.dayOf(it.time) }) {
            File(d, NetFiles.rows(day.first, day.second)).appendText(list.joinToString("") { NetRecord.encode(it) + "\n" })
        }
    }

    private val now = System.currentTimeMillis()

    private fun today() {
        seed(
            NetEntry(now - 3_000, "voice", NetPurpose.WEATHER_PLACE, "geocoding-api.open-meteo.com", NetCause.ASKED, 0, 1_200, NetResult.DONE),
            NetEntry(now - 2_000, "voice", NetPurpose.WEATHER, "api.open-meteo.com", NetCause.ASKED, 0, 14_000, NetResult.DONE),
            NetEntry(now - 1_000, "main", NetPurpose.UPDATE_CHECK, "api.github.com", NetCause.TAP, 0, 9_500, NetResult.FAILED),
        )
    }

    private fun page(fr: Boolean = false) = rule.show {
        CompositionLocalProvider(LocalLangs provides if (fr) Langs.of(Lang.FR) else Langs.EN) { OnlineScreen(log.actions, SnackbarHostState()) {} }
    }

    @Test fun todayWithEachRowAndItsDetails() {
        today()
        page()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Today: 3 connections to 3 servers.")).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToText("api.open-meteo.com · 0 bytes out · 14 KB in · You asked", substring = true)
        rule.scrollToText("api.github.com · 0 bytes out · 9.5 KB in · Failed · You tapped it", substring = true)
        rule.scrollToText("Weather forecast", substring = true)
        rule.onNode(androidx.compose.ui.test.hasText("Weather forecast", substring = true) and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.inDialog("Controlled in Settings › Weather").assertExists()
        rule.inDialog("api.open-meteo.com").assertExists()
        rule.inDialog("The assistant").assertExists()
        rule.inDialog("Open").performClick()
        assertEquals(listOf("openPage " + Page.Settings(SettingsRoutes.WEATHER)), log)
        rule.scrollToText("Every connection Robot made", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun theSwitchAndDelete() {
        today()
        page()
        rule.scrollToText("Keep a record of what went online")
        rule.onRow("Keep a record of what went online").assertIsOn().performClick()
        rule.waitUntil(5_000) { !EverydaySettingsStore.current(ctx).online }
        // The page reads the file back off the main thread, which waitForIdle doesn't wait for: the row settles on what was written.
        rule.waitUntil(5_000) { try { rule.onRow("Keep a record of what went online").assertIsOff(); true } catch (_: AssertionError) { false } }
        rule.onNodeWithText("The record is off, so nothing new is recorded. Android's count still shows.").assertExists()
        rule.onRow("Delete the record").performClick()
        rule.inDialog("Delete").performClick()
        rule.waitUntil(5_000) { NetLog.read(ctx).isEmpty() }
        // The same for the empty record: the file is gone before the page has read it again, and on a busy machine the
        // list still held its old rows here (reproduced by slowing that read: docs/ops/reports/flakes.md).
        rule.waitUntil(5_000) { try { rule.scrollToText("Robot hasn't gone online in the last 30 days."); true } catch (_: AssertionError) { false } }
    }

    @Test fun rowsPastTheDaysKeptGoAtStartAndOnANewDayWithTheRecordOff() {
        val day = 86_400_000L
        val old = NetEntry(now - 40 * day, "main", NetPurpose.WEB_SEARCH, "old.example.org", NetCause.ASKED, 1, 1, NetResult.DONE)
        val oldFile = File(NetLog.dir(ctx), NetFiles.rows("main", NetFiles.dayOf(old.time)))
        EverydaySettingsStore.update(ctx) { it.copy(online = false) }
        seed(old)
        NetLog.start(ctx as Application)
        rule.waitUntil(5_000) { !oldFile.exists() }
        seed(old)
        // A new day's first connection, with the record off: it isn't recorded, and the trim still runs.
        NetRecord.sink!!.add(old.copy(time = now + day, host = "new.example.org"))
        assertFalse(oldFile.exists())
        assertTrue(NetLog.read(ctx).isEmpty())
    }

    @Test fun aRecordTooLongToReadWholeShowsItsNewestDaysAndSaysSo() {
        val day = 86_400_000L
        val row = NetEntry(now, "main", NetPurpose.WEB_SEARCH, "new.example.org", NetCause.ASKED, 1, 1, NetResult.DONE)
        seed(row)
        // Nine older days of a full 2 MiB (empty, so the test stays quick): past the cap, so the rows of the oldest aren't read.
        for (n in 1..9) java.io.RandomAccessFile(File(NetLog.dir(ctx), NetFiles.rows("voice", NetFiles.dayOf(now - n * day))), "rw").use { it.setLength(NetFiles.MAX_DAY_BYTES) }
        assertEquals(true, NetLog.readKept(ctx).second)
        assertEquals(listOf("new.example.org"), NetLog.read(ctx).map { it.host })
        page()
        val line = "This record is too long to show in full. The oldest days are kept but not shown."
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(line)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theIntroSaysASitesNameCanHintAtASearch() {
        page()
        rule.scrollToText("A web search's pages show their sites, and a site's name can hint at what you looked for.", substring = true)
    }

    @Test fun theWriterKeepsOnlyWhileTheSwitchIsOn() {
        NetLog.start(ctx as Application)
        assertEquals("main", NetRecord.process)
        NetRecord.record(NetPurpose.WEB_SEARCH, "api.search.brave.com", NetCause.ASKED, 120, 3_000, NetResult.DONE)
        assertEquals(listOf("api.search.brave.com"), NetLog.read(ctx).map { it.host })
        EverydaySettingsStore.update(ctx) { it.copy(online = false) }
        NetRecord.record(NetPurpose.WEB_SEARCH, "api.search.brave.com", NetCause.ASKED, 120, 3_000, NetResult.DONE)
        assertEquals(1, NetLog.read(ctx).size)
        // The record is the phone's own: not in files/robot, which the backup and Send feedback read.
        assertFalse(NetLog.dir(ctx).absolutePath.contains("/robot/"))
        assertTrue(NetLog.dir(ctx).list()!!.all { NetFiles.parse(it) != null })
    }

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun inFrench() {
        today()
        page(fr = true)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("3 connexions vers 3 serveurs", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToText("Prévisions météo", substring = true)
        rule.scrollToText("Tu l’as demandé", substring = true)
    }
}
