package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** phone_status (0.4.0): what it tells the model, the card, and how the rest of Robot sees it. */
class PhoneToolsTest {
    private val now = ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, ZoneId.of("America/Toronto"))
    private val storage = StorageFacts(total = 238_000_000_000, free = 12_400_000_000)
    private val battery = BatteryFacts(64, charging = true, full = false, tempC = 31.4, minutesToFull = 52)
    private val memory = MemoryFacts(total = 15_600_000_000, available = 3_100_000_000, low = false)
    private val apps = listOf(
        AppSize("Signal", 12_300_000_000, 1_200_000_000), AppSize("Organic Maps", 4_100_000_000, 20_000_000),
        AppSize("Robot", 4_000_000_000, 1_000_000), AppSize("Camera", 300_000_000, 150_000_000), AppSize("Notes", 90_000_000, 0),
        AppSize("Clock", 20_000_000, 0), AppSize("Empty", 0, 0),
    )

    @Test fun sizesReadLikeAndroidsOwnSettings() {
        assertEquals("57 GB", PhoneStatus.size(57_400_000_000))
        assertEquals("238 GB", PhoneStatus.size(238_000_000_000))
        assertEquals("1.2 GB", PhoneStatus.size(1_234_000_000))
        assertEquals("2 GB", PhoneStatus.size(2_000_000_000))
        assertEquals("340 MB", PhoneStatus.size(340_000_000))
        assertEquals("12 KB", PhoneStatus.size(12_000))
        assertEquals("0 bytes", PhoneStatus.size(-5))
    }

    @Test fun theAnswerIsAFewShortLines() {
        val f = PhoneFacts(storage, battery, memory, apps, MediaFacts(10_200_000_000, 21_000_000_000, 1_200_000_000), usageAccess = true, robotModelLoaded = true)
        val r = PhoneStatus.results(f, PhonePart.ALL).single()
        assertEquals("12 GB free of 238 GB (95% used)", r["storage"])
        assertEquals("64%, charging, full in about 52 min, 31°C", r["battery"])
        assertEquals("3.1 GB available of 16 GB; Robot's own model holds about 2 GB while loaded", r["memory"])
        assertEquals("Signal 12 GB (cache 1.2 GB), Organic Maps 4.1 GB, Robot 4 GB, Camera 300 MB (cache 150 MB), Notes 90 MB", r["largest_apps"])
        assertEquals("photos 10 GB, videos 21 GB, audio 1.2 GB", r["media"])
        // Small enough for the 4,096-token context.
        assertTrue(TokenEstimate.text(r.toString()) < 200)
        assertEquals("Storage: 12 GB free of 238 GB (95% used)\nBattery: 64%, charging, full in about 52 min, 31°C\n" +
            "Memory: 3.1 GB available of 16 GB; Robot's own model holds about 2 GB while loaded\n" +
            "Largest apps: Signal 12 GB (cache 1.2 GB), Organic Maps 4.1 GB, Robot 4 GB, Camera 300 MB (cache 150 MB), Notes 90 MB\n" +
            "Photos, videos and audio: photos 10 GB, videos 21 GB, audio 1.2 GB", PhoneStatus.lines(listOf(r)))
    }

    @Test fun robotIsNamedAsNeededAmongTheLargestApps() {
        val line = PhoneStatus.appsLine(listOf(AppSize(FreeUp.ROBOT_LABEL, 4_600_000_000, 1_000_000, "ai.robot_app"), AppSize("Signal", 12_300_000_000, 0)))
        assertEquals("Signal 12 GB, Robot (models, don't clear) 4.6 GB", line)
    }

    @Test fun onlyWhatWasAskedAndUsageAccessIsNamedWhenOff() {
        val f = PhoneFacts(storage, battery, memory, usageAccess = false)
        assertEquals(listOf("storage", "largest_apps"), PhoneStatus.results(f, PhonePart.STORAGE).single().keys.toList())
        assertTrue((PhoneStatus.results(f, PhonePart.STORAGE).single()["largest_apps"] as String).contains("Usage access"))
        assertEquals(listOf("battery"), PhoneStatus.results(f, PhonePart.BATTERY).single().keys.toList())
        assertEquals(listOf("memory"), PhoneStatus.results(f, PhonePart.MEMORY).single().keys.toList())
        assertEquals(emptyList<Map<String, Any?>>(), PhoneStatus.results(PhoneFacts(), PhonePart.BATTERY))
        // Full, not charging, unknown temperature; °F with the US region; low memory said; Robot's model unloaded unsaid.
        assertEquals("100%, full", PhoneStatus.batteryLine(BatteryFacts(100, charging = true, full = true, tempC = null, minutesToFull = 0)))
        assertEquals("18%, not charging, 88°F", PhoneStatus.batteryLine(BatteryFacts(18, false, false, 31.0, null), WeatherUnits.IMPERIAL))
        assertEquals("full in about 2 h", PhoneStatus.batteryLine(battery.copy(minutesToFull = 130)).split(", ")[2])
        assertEquals("400 MB available of 8 GB (Android says memory is low)", PhoneStatus.memoryLine(MemoryFacts(8_000_000_000, 400_000_000, true), false))
        // 0.4.1: the loaded model's own size (about 4 GB for the E4B models).
        assertEquals("3 GB available of 16 GB; Robot's own model holds about 4.1 GB while loaded",
            PhoneStatus.memoryLine(MemoryFacts(16_000_000_000, 3_000_000_000, false), true, BrainModel.SMARTER.memoryBytes))
        assertEquals("3 GB available of 16 GB; Robot's own model holds about 2 GB while loaded",
            PhoneStatus.memoryLine(MemoryFacts(16_000_000_000, 3_000_000_000, false), true))
    }

    @Test fun theAboutArgumentNamesAPart() {
        assertEquals(PhonePart.STORAGE, PhonePart.of("storage"))
        assertEquals(PhonePart.STORAGE, PhonePart.of("free space"))
        assertEquals(PhonePart.BATTERY, PhonePart.of("Battery"))
        assertEquals(PhonePart.BATTERY, PhonePart.of("charging"))
        assertEquals(PhonePart.MEMORY, PhonePart.of("RAM"))
        assertEquals(PhonePart.MEMORY, PhonePart.of("mémoire"))
        assertEquals(PhonePart.STORAGE, PhonePart.of("stockage"))
        assertEquals(PhonePart.ALL, PhonePart.of(null))
        assertEquals(PhonePart.ALL, PhonePart.of("all"))
        assertEquals(PhonePart.ALL, PhonePart.of("battery and storage"))
        fun parse(args: Map<String, Any?>) = (ToolCalls.parse(RawToolCall("phone_status", args)) as ParsedCall.Ok).action
        assertEquals(Action.PhoneCheck(PhonePart.ALL), parse(emptyMap()))
        assertEquals(Action.PhoneCheck(PhonePart.STORAGE), parse(mapOf("about" to "storage")))
        assertEquals(Action.PhoneCheck(PhonePart.BATTERY), parse(mapOf("part" to "battery")))
        assertEquals("Check your phone's storage", Action.PhoneCheck(PhonePart.STORAGE).describe())
        assertEquals("Check your phone's storage, battery and memory", Action.PhoneCheck(PhonePart.ALL).describe())
    }

    @Test fun phoneWordsReachThePhoneGroup() {
        val asks = listOf("how do I free up storage", "my phone is running out of space", "how much battery do I have", "is it charging",
            "how much RAM is free", "what's using my memory", "which apps take the most space", "comment libérer de la place",
            "il me reste combien de batterie", "mon stockage est plein", "clear the cache")
        for (q in asks) assertTrue(q, ToolGroup.PHONE in ToolRouter.route(q))
        for (q in listOf("set a timer for 5 minutes", "what's the weather tomorrow", "text Mom I'm late")) assertFalse(q, ToolGroup.PHONE in ToolRouter.route(q))
        assertTrue(ToolName.PHONE_STATUS in ToolRouter.tools(setOf(ToolGroup.CORE, ToolGroup.PHONE), RobotSettings()))
    }

    @Test fun itJustRunsUnlockedReadsOnlyAndIsPersonal() {
        val d = RobotSettings()
        assertEquals(Approval.AUTO, d.approval(ToolName.PHONE_STATUS))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.PHONE_STATUS, false, d))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.PHONE_STATUS, false, d, content = true))
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.PHONE_STATUS, true, d))
        assertFalse(ToolName.PHONE_STATUS.online || ToolName.PHONE_STATUS in ClaudeTools.OFFERED)
        // An older settings file reads the default.
        assertEquals(Approval.AUTO, RobotSettings.fromJson("""{"schema":4}""").approval(ToolName.PHONE_STATUS))
    }

    @Test fun thePromptAsksForNumbersBeforeAdvice() {
        assertFalse(Prompt.system(now, locked = false).contains("phone_status"))
        assertTrue(Prompt.system(now, locked = false, phone = true).contains(Prompt.PHONE_ADVICE))
        val system = Prompt.system(now, locked = false)
        val cost = Prompt.fixedTokens(system, Prompt.CORE_TOOLS + ToolName.PHONE_STATUS) - Prompt.fixedTokens(system, Prompt.CORE_TOOLS)
        assertTrue("phone_status $cost", cost in 1..120)
        assertTrue(Prompt.textCallHint(listOf(ToolName.PHONE_STATUS)).contains("call:phone_status{about:storage}"))
        // The phone group (phone_status, free_memory, free_storage) costs about 410 tokens, only where it is used (every
        // group at once: PromptTest).
        val phone = Prompt.fixedTokens(system, Prompt.CORE_TOOLS + ToolRouter.tools(setOf(ToolGroup.PHONE), RobotSettings())) - Prompt.fixedTokens(system, Prompt.CORE_TOOLS)
        assertTrue("phone $phone", phone < 430)
    }

    @Test fun thePhonesStatusIsPersonal() {
        val history = listOf(
            ChatTurn("1", TurnRole.USER, "how do I free up storage", 1),
            ChatTurn("2", TurnRole.ASSISTANT, "Signal uses 12 GB.", 2,
                actions = listOf(ActionRecord(ToolName.PHONE_STATUS, "Check your phone's storage", ActionStatus.DONE, "Read on this phone; nothing left it.", content = true))),
        )
        assertEquals(setOf(Personal.PHONE), ClaudeContext.personal(history))
        assertEquals("Include the conversation so far? It contains your phone's status.", ClaudeContext.includeQuestion(history))
        // Robot checking with Claude by itself sends the question only.
        assertFalse(ClaudeContext.escalation("is that normal?", history, now, "CA").filterIsInstance<ClaudeBlock.Text>().any { it.text.contains("Signal") })
        assertEquals("your location and your phone's status", Personal.list(setOf(Personal.PHONE, Personal.LOCATION)))
    }

    @Test fun cardButtonsOpenSettingsPages() {
        assertEquals("Open storage settings", SettingsPage.label(SettingsPage.STORAGE))
        assertEquals("Turn on Usage access", SettingsPage.label(SettingsPage.USAGE_ACCESS))
        assertNull(SettingsPage.label("com.cheogram.android"))
        assertTrue(SettingsPage.isPage(SettingsPage.STORAGE) && !SettingsPage.isPage("com.cheogram.android") && !SettingsPage.isPage(null))
    }
}
