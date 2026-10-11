package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** free_memory and free_storage (0.4.0): what the model asks for, what the cards say, and the gate. */
class FreeUpTest {
    private val zone = ZoneId.of("America/Toronto")
    private val video = MediaFile("content://media/external/video/media/41", "VID_20260301_181502.mp4", 1_230_000_000, 1_772_406_902_000, video = true)

    private fun parse(tool: String, args: Map<String, Any?>) = (ToolCalls.parse(RawToolCall(tool, args)) as ParsedCall.Ok).action

    @Test fun freeMemoryUnloadsRobotUnlessAnAppIsNamed() {
        assertEquals(Action.FreeMemory(null), parse("free_memory", emptyMap()))
        assertEquals(Action.FreeMemory(null), parse("free_memory", mapOf("app" to "Robot's model")))
        // "Stop Robot" names an app: the app process refuses it (Robot can't stop itself) rather than unload the model.
        assertEquals(Action.FreeMemory("Robot"), parse("free_memory", mapOf("app" to "Robot")))
        assertEquals(Action.FreeMemory(null), parse("free_memory", mapOf("app" to "background apps")))
        assertEquals(Action.FreeMemory("Chrome"), parse("free_memory", mapOf("app" to " Chrome ")))
        assertEquals("Unload Robot's model to free about 2 GB of memory", Action.FreeMemory(null).describe())
        assertEquals("Unload", Action.FreeMemory(null).confirmLabel)
        assertEquals("Open **Chrome**'s App info to force stop it", Action.FreeMemory("Chrome", "com.android.chrome").describe())
        assertEquals("Open App info", Action.FreeMemory("Chrome").confirmLabel)
        // Honest about what Android allows.
        assertTrue(FreeUp.MEMORY_FOR_MODEL.contains("Android frees memory by itself") && FreeUp.MEMORY_FOR_MODEL.contains("may start again"))
    }

    @Test fun freeStorageReadsItsWay() {
        assertEquals(StorageWay.ANDROID, StorageWay.of(null))
        assertEquals(StorageWay.ANDROID, StorageWay.of("android"))
        assertEquals(StorageWay.ANDROID, StorageWay.of("free up space"))
        assertEquals(StorageWay.APPS, StorageWay.of("apps"))
        assertEquals(StorageWay.APPS, StorageWay.of("app cache"))
        assertEquals(StorageWay.FILES, StorageWay.of("files"))
        assertEquals(StorageWay.FILES, StorageWay.of("big videos"))
        assertEquals(StorageWay.FILES, StorageWay.of("photos"))
        assertEquals(StorageWay.ROBOT, StorageWay.of("robot"))
        assertEquals(StorageWay.ROBOT, StorageWay.of("Robot's cache"))
        // "Big apps" are apps; big or large alone are files; a cache is an app's.
        assertEquals(StorageWay.APPS, StorageWay.of("big apps"))
        assertEquals(StorageWay.APPS, StorageWay.of("the largest apps"))
        assertEquals(StorageWay.FILES, StorageWay.of("largest"))
        assertEquals(StorageWay.APPS, StorageWay.of("cache"))
        // The text-call hint shows no app to copy.
        assertFalse(Prompt.textCallHint(listOf(ToolName.FREE_MEMORY)).contains("app:"))
        // A picked file's card waits for Android's answer, then says what happened.
        assertTrue(FreeUp.DELETE_WAITING.startsWith("Waiting for your answer"))
        assertEquals(Action.FreeStorage(StorageWay.FILES), parse("free_storage", mapOf("what" to "files")))
        assertEquals(Action.FreeStorage(StorageWay.ANDROID), parse("free_storage", emptyMap()))
        assertEquals("Open Android's Free up space screen", Action.FreeStorage(StorageWay.ANDROID).describe())
        assertEquals("Choose an app to clear its cache", Action.FreeStorage(StorageWay.APPS).describe())
        assertEquals("Open **Signal**'s App info to clear its cache", Action.FreeStorage(StorageWay.APPS, "org.thoughtcrime.securesms", "Signal").describe())
        assertEquals("Clear Robot's cache (3.2 MB)", Action.FreeStorage(StorageWay.ROBOT, bytes = 3_200_000).describe())
        assertEquals("Delete", Action.FreeStorage(StorageWay.FILES, video.uri).confirmLabel)
    }

    @Test fun aFilesNameIsOnItsButtonOnly() {
        assertEquals("VID_20260301_181502.mp4 · 1.2 GB · Mar 1, 2026", FreeUp.fileChoice(video, zone))
        val summary = FreeUp.fileSummary(video, zone)
        assertEquals("Delete a video (1.2 GB, Mar 1, 2026)", summary)
        // The picked action's card line (saved with the conversation, replayed to the model) has no name.
        val picked = Action.FreeStorage(StorageWay.FILES, video.uri, "video", video.bytes, summary)
        assertEquals(summary, picked.describe())
        assertFalse(picked.describe().contains("VID_"))
        assertEquals("Choose a large photo or video to delete", Action.FreeStorage(StorageWay.FILES).describe())
        // A long name is cut; no name, no date.
        assertEquals("A photo · 4 MB", FreeUp.fileChoice(MediaFile("content://media/external/images/media/7", "", 4_000_000, null, false), zone))
        assertTrue(FreeUp.fileChoice(video.copy(name = "x".repeat(80)), zone).startsWith("x".repeat(39) + "…"))
    }

    @Test fun appsAndMemoryReadPlainly() {
        assertEquals("Signal · 12 GB, cache 1.2 GB", FreeUp.appChoice(AppSize("Signal", 12_300_000_000, 1_200_000_000, "org.thoughtcrime.securesms")))
        assertEquals("Notes · 90 MB", FreeUp.appChoice(AppSize("Notes", 90_000_000, 0)))
        assertEquals("Chrome, YouTube and Maps", FreeUp.names(listOf("Chrome", "YouTube", "Maps")))
        assertEquals("Chrome", FreeUp.names(listOf("Chrome")))
        assertEquals("Freed about 2.1 GB: 6.3 GB of memory available now.", FreeUp.freedLine(4_200_000_000, 6_300_000_000))
        assertTrue(FreeUp.freedLine(4_200_000_000, 4_250_000_000).startsWith("Robot's model is unloaded."))
        assertTrue("org.thoughtcrime.securesms" in FreeUp.MESSAGING && "com.cheogram.android" in FreeUp.MESSAGING)
    }

    @Test fun theyAskFirstAndNeverRunLocked() {
        val d = RobotSettings()
        for (t in listOf(ToolName.FREE_MEMORY, ToolName.FREE_STORAGE)) {
            assertEquals(t.name, Approval.ASK, d.approval(t))
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, d))
            assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, true, d))
            // They write: screen text or a read tool's findings make even "Just do it" ask.
            val auto = d.copy(approvals = d.approvals + (t to Approval.AUTO))
            assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, auto))
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, auto, mustAsk = true))
            assertFalse(t.reads || t.online || t in ClaudeTools.OFFERED)
            assertEquals(Personal.PHONE, Personal.of(t))
        }
    }

    @Test fun stoppingAnAppAndBigFilesReachThePhoneGroup() {
        val asks = listOf("Stop YouTube", "force stop YouTube", "Stop Robot", "close the Signal app", "kill Chrome", "close Maps",
            "Delete my largest videos", "delete some photos", "what are my biggest files", "arrête YouTube", "ferme Chrome",
            "mon téléphone est plein", "supprime mes plus grosses vidéos", "il n'y a plus d'espace", "ma mémoire est pleine")
        for (q in asks) assertTrue(q, ToolGroup.PHONE in ToolRouter.route(q))
        // "stop" before something that isn't an app is ordinary talk.
        for (q in listOf("stop the timer", "stop talking", "stop it", "close the door", "stop", "please stop that", "arrête de parler")) {
            assertFalse(q, ToolGroup.PHONE in ToolRouter.route(q))
        }
        // "stop Claude" stays Claude's (it ends Claude mode); "stop Robot" is not a Claude command.
        assertEquals(ClaudeCommand.Leave, ClaudeRouter.route("stop Claude"))
        assertNull(ClaudeRouter.route("Stop Robot"))
    }

    @Test fun freeingIsAboutThePhoneNotTheCalendar() {
        for (q in listOf("free up memory", "free up some space", "can you free storage", "free RAM please", "clear the cache")) {
            val g = ToolRouter.route(q)
            assertTrue(q, ToolGroup.PHONE in g)
            assertFalse(q, ToolGroup.CALENDAR in g)
        }
        assertTrue(ToolGroup.CALENDAR in ToolRouter.route("am I free tomorrow"))
        assertTrue(ToolGroup.CALENDAR in ToolRouter.route("am I free at 3"))
        val tools = ToolRouter.tools(setOf(ToolGroup.CORE, ToolGroup.PHONE), RobotSettings())
        assertTrue(tools.containsAll(listOf(ToolName.PHONE_STATUS, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE)))
        assertTrue(Prompt.textCallHint(listOf(ToolName.FREE_STORAGE)).contains("call:free_storage{what:files}"))
    }
}
