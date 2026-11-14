// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.guide

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5: What's new after an update, once, and every feature it names has its row on a guide page. */
class WhatsNewTest {
    @Test fun everyFeaturePointsAtAPageThatShowsIt() {
        assertEquals(GuideFeature.entries.size, GuideFeature.entries.map { it.id }.toSet().size)
        for (f in GuideFeature.entries) {
            assertTrue(f.id, f.step !in setOf(GuideStep.WELCOME, GuideStep.CAN_DO, GuideStep.SUMMARY))
            assertEquals(f.id, f, GuideFeature.of(f.id))
            assertTrue(f.id, WhatsNew.compare(f.since, WhatsNew.FIRST) >= 0)
        }
        // The new pages each have rows.
        for (s in listOf(GuideStep.FILES_LISTS, GuideStep.YOUR_DAY, GuideStep.SKILLS, GuideStep.LINK)) assertTrue(s.id, GuideFeature.on(s).isNotEmpty())
    }

    @Test fun versionsCompareByNumber() {
        assertTrue(WhatsNew.compare("1.10.0", "1.9.9") > 0)
        assertEquals(0, WhatsNew.compare("1.4", "1.4.0"))
        assertEquals(0, WhatsNew.compare("1.5.0-rc1", "1.5.0"))
        assertTrue(WhatsNew.compare("1.4.1", "1.4.0") > 0)
    }

    @Test fun from130To140ListsWhat140Added() {
        val l = WhatsNew.since("1.3.0", "1.4.0")
        assertEquals(GuideFeature.entries.filter { it.since == "1.4.0" }, l)
        assertTrue(GuideFeature.WIKIPEDIA in l)
        assertFalse(GuideFeature.MCP in l)
        val s = GuideState(seen = SetupGuide.VERSION, finished = SetupGuide.VERSION, app = "1.3.0")
        assertEquals(GuideLaunch.WHATS_NEW, WhatsNew.atLaunch(s, "1.4.0"))
    }

    @Test fun aSkippedReleaseIsListedToo() {
        val l = WhatsNew.since("1.2.7", "1.4.1")
        assertTrue(GuideFeature.MCP in l && GuideFeature.WIKIPEDIA in l)
        assertFalse(GuideFeature.TODAY in l)
        // Newest version first.
        assertEquals("1.4.0", l.first().since)
        assertEquals("1.3.0", l.last().since)
    }

    @Test fun nothingAfterAPatchWithNothingNew() {
        assertTrue(WhatsNew.since("1.4.0", "1.4.1").isEmpty())
        val s = GuideState(seen = SetupGuide.VERSION, finished = SetupGuide.VERSION, app = "1.4.0")
        assertEquals(GuideLaunch.NOTHING, WhatsNew.atLaunch(s, "1.4.1"))
        // A downgrade shows nothing either.
        assertEquals(GuideLaunch.NOTHING, WhatsNew.atLaunch(s.copy(app = "1.5.0"), "1.4.1"))
    }

    @Test fun aFreshInstallOpensTheGuideNotWhatsNew() {
        assertEquals(GuideLaunch.GUIDE, WhatsNew.atLaunch(GuideState(), "1.5.0"))
        assertEquals(null, WhatsNew.from(GuideState()))
    }

    @Test fun anUpdateFromAVersionThatKeptNoNumberListsWhatCameAfter140() {
        // 1.0 to 1.4 kept no version: someone who saw or finished the guide gets What's new from 1.4.0, not the whole guide.
        val old = GuideState.fromJson("""{"v":1,"seen":1,"at":null,"finished":1,"skipped":[]}""")
        assertEquals(WhatsNew.UNKEPT, WhatsNew.from(old))
        assertEquals(GuideLaunch.WHATS_NEW, WhatsNew.atLaunch(old, "1.5.0"))
        val l = WhatsNew.since(WhatsNew.from(old)!!, "1.5.0")
        assertTrue(l.isNotEmpty() && l.all { it.since == "1.4.2" }) // release/1.4: 1.4.2 is the only version after 1.4.0
        val after = GuideState.fromJson(WhatsNew.launched(old, "1.5.0", GuideLaunch.WHATS_NEW).toJson())
        assertEquals(GuideLaunch.NOTHING, WhatsNew.atLaunch(after, "1.5.0"))
        assertFalse(SetupGuide.opensAtLaunch(after))
        // Seen and left under way: What's new too (its own button goes through the guide).
        val underWay = GuideState.fromJson("""{"v":1,"seen":1,"at":"people","finished":0,"skipped":[]}""")
        assertEquals(GuideLaunch.WHATS_NEW, WhatsNew.atLaunch(underWay, "1.5.0"))
    }

    @Test fun theNoticeIsPostedOnceAndOnlyWhenWhatsNewWaits() {
        val s = GuideState(seen = 1, finished = 1, app = "1.3.2")
        assertTrue(WhatsNew.notifies(s, "1.5.0"))
        val told = GuideState.fromJson(WhatsNew.notified(s, "1.5.0").toJson())
        assertFalse(WhatsNew.notifies(told, "1.5.0"))
        // The app still shows What's new once.
        assertEquals(GuideLaunch.WHATS_NEW, WhatsNew.atLaunch(told, "1.5.0"))
        assertFalse(WhatsNew.notifies(GuideState(), "1.5.0"))
        assertFalse(WhatsNew.notifies(s.copy(app = "1.4.0"), "1.4.1"))
    }

    @Test fun aLaunchWithNothingNewChangesNothing() {
        val s = GuideState(seen = SetupGuide.VERSION, finished = SetupGuide.VERSION, app = "1.5.0")
        assertEquals(s, WhatsNew.launched(s, "1.5.0", WhatsNew.atLaunch(s, "1.5.0")))
    }

    @Test fun itShowsOnceOnly() {
        val s = GuideState(seen = 1, finished = 1, app = "1.3.2")
        val shown = WhatsNew.atLaunch(s, "1.5.0")
        assertEquals(GuideLaunch.WHATS_NEW, shown)
        val after = GuideState.fromJson(WhatsNew.launched(s, "1.5.0", shown).toJson())
        assertEquals("1.5.0", after.app)
        assertEquals(SetupGuide.VERSION, after.seen)
        assertEquals(GuideLaunch.NOTHING, WhatsNew.atLaunch(after, "1.5.0"))
    }

    /** Each feature's words are in its version's CHANGELOG section (a wrong version fails here). */
    @Test fun eachFeatureIsInItsChangelogSection() {
        val sections = changelog()
        val newest = sections.keys.maxWithOrNull { a, b -> WhatsNew.compare(a, b) }!!
        for (f in GuideFeature.entries) {
            val text = sections[f.since]
            assertNotEquals("${f.id}: CHANGELOG has no ${f.since}", null, text)
            // Only the version in the making may name a feature its branch hasn't written up yet.
            if (f.changelog == null) assertEquals(f.id, newest, f.since)
            else assertTrue("${f.id}: « ${f.changelog} » not in ${f.since}", text!!.contains(f.changelog))
        }
        // Every minor release from 1.1.0 added something What's new lists.
        for (v in sections.keys.filter { WhatsNew.compare(it, WhatsNew.FIRST) >= 0 && it.endsWith(".0") })
            assertTrue(v, GuideFeature.entries.any { it.since == v })
    }

    private fun changelog(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "CHANGELOG.md").exists()) dir = dir.parentFile
        val lines = File(dir!!, "CHANGELOG.md").readLines()
        val out = linkedMapOf<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (l in lines) {
            val m = Regex("""^## (\d+\.\d+\.\d+)""").find(l)
            if (m != null) cur = StringBuilder().also { out[m.groupValues[1]] = it } else if (l.startsWith("## ")) cur = null else cur?.append(l)?.append('\n')
        }
        return out.mapValues { it.value.toString().replace(Regex("""\n\s+"""), " ") }
    }

    @Test fun onlyAnUpdateFromAVersionThatKeptNoNumberEndsWithEverything() {
        org.junit.Assert.assertTrue(WhatsNew.unkept(WhatsNew.from(GuideState(seen = 1))!!))
        org.junit.Assert.assertFalse(WhatsNew.unkept(WhatsNew.from(GuideState(seen = 1, app = "1.5.0"))!!))
        org.junit.Assert.assertFalse(WhatsNew.unkept("1.4.1"))
    }

    /** guide-142: an update from 1.4.1 (it kept no number) shows 1.4.2's entry once, a new install doesn't, About lists it. */
    @Test fun anUpdateFrom141ShowsTheEntryFor142Once() {
        val old = GuideState.fromJson("""{"v":1,"seen":1,"at":null,"finished":1,"skipped":[]}""")
        val new142 = GuideFeature.entries.filter { it.since == "1.4.2" }
        assertTrue(GuideFeature.REPEATING_EVENTS in new142 && GuideFeature.NOTES_SAVED in new142 && new142.size == 8)
        assertEquals(GuideLaunch.WHATS_NEW, WhatsNew.atLaunch(old, "1.4.2"))
        assertEquals(new142, WhatsNew.since(WhatsNew.from(old)!!, "1.4.2"))
        assertTrue(WhatsNew.notifies(old, "1.4.2"))
        val after = GuideState.fromJson(WhatsNew.notified(WhatsNew.launched(old, "1.4.2", GuideLaunch.WHATS_NEW), "1.4.2").toJson())
        assertEquals(GuideLaunch.NOTHING, WhatsNew.atLaunch(after, "1.4.2"))
        assertFalse(WhatsNew.notifies(after, "1.4.2"))
        assertFalse(SetupGuide.opensAtLaunch(after))
        // A new install: the guide, never What's new or its notice.
        assertEquals(GuideLaunch.GUIDE, WhatsNew.atLaunch(GuideState(), "1.4.2"))
        assertFalse(WhatsNew.notifies(GuideState(), "1.4.2"))
        // Settings › About lists every version's, newest first: 1.4.2's entries open the list.
        assertEquals(new142, WhatsNew.since("0", "1.4.2").take(new142.size))
    }
}
