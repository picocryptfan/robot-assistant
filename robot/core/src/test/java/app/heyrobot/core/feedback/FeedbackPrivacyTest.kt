// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import app.heyrobot.core.BrainModel
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.ExitFacts
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.LookSettings
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.NoteStore
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.Phone
import app.heyrobot.core.ProblemList
import app.heyrobot.core.ProblemReport
import app.heyrobot.core.ProblemStage
import app.heyrobot.core.ReportFacts
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceFiles
import app.heyrobot.core.VoiceLogCodec
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.WakeAction
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.update.CheckOutcome
import app.heyrobot.core.update.FileRef
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.UpdateFail
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateState
import app.heyrobot.core.writeAtomic
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.IncomingMessage
import app.heyrobot.core.messages.Lang
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageBuffer
import app.heyrobot.core.messages.MessageReading
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessagesFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.time.ZoneId

/**
 * 0.7, Send feedback's privacy filter: a phone whose every store holds something personal (a marker) — conversations and
 * History, transcripts, messages, memory, People and contacts, notes, the calendar, wake phrases, helpers' names, addresses,
 * models and keys, the updater's repository and token, Wi-Fi and Bluetooth names, places — gives a report, with every
 * diagnostic ticked and made the way the app makes it (FeedbackSources over Robot's folder, the problem report from its note
 * and Android's facts), that holds none of them. Then the last pass alone: a phrase or a name that slipped into a line Robot
 * didn't write is left out.
 */
class FeedbackPrivacyTest {
    private val utc = ZoneId.of("UTC")
    private val t = 1_791_000_000_000L

    private val phrase = "hey zorblax kettering"
    private val helperName = "Zorblax Home Mistral"
    private val alias = "Kettering box"
    private val address = "http://192.168.77.41:8080/v1"
    private val homeModel = "zorblax-llama-70b"
    private val key = "sk-proj-ZQXSECRETKEY0123456789"
    private val token = "github_pat_11ZQXSECRETTOKEN0123456789"
    private val repo = "zorblaxfork/robot-zq"

    /** Everything planted, by where it was planted. */
    private val markers = mapOf(
        "conversation" to "ZQX-CONVERSATION-TEXT", "history title" to "ZQX-HISTORY-TITLE", "transcript" to "ZQX-TRANSCRIPT-LINE",
        "message" to "ZQX-MESSAGE-BODY", "message sender" to "ZQX-SENDER-Annabelle", "memory" to "ZQX-MEMORY-ITEM", "person" to "ZQX-PERSON-Annabelle",
        "contact number" to "+15145550177", "note" to "ZQX-NOTE-TEXT", "calendar" to "ZQX-CALENDAR-EVENT", "wake phrase" to phrase,
        "helper name" to helperName, "helper alias" to alias, "helper address" to "192.168.77.41", "helper model" to homeModel, "helper key" to key,
        "updater token" to token, "updater repository" to repo, "default repository" to "picocryptfan/robot-assistant", "release notes" to "ZQX-NOTES",
        "wifi" to "ZQX-WIFI-HomeNet", "bluetooth" to "ZQX-BT-Headphones", "weather home" to "Rue Zorblax", "latitude" to "45.512345",
        "longitude" to "-73.554321", "sms gateway" to "zorblaxgate.example", "search server" to "nominatim.zorblax.example", "spoken prefill" to "ZQX-PREFILL",
        "send to" to "+15145550199", "own model file" to "zorblax-own-model.gguf", "user path" to "/storage/emulated/0",
        // 0.7.0: memory's items (a private one too), messages' words and senders, People's ids in messages.json, an app's
        // key, and what the main and voice processes hold in RAM (MessageTap's buffer, the panel's conversations).
        "private memory item" to "ZQX-MEMORY-SECRET", "memory in History" to "m_0000000zqx001", "people id for replies" to "zqx-person-annabelle",
        "app key" to "5a".repeat(32), "message in RAM" to "ZQX-RAM-MESSAGE", "sender in RAM" to "ZQX-RAM-SENDER", "sender address" to "annabelle@zqx.example",
        "group title" to "ZQX-GROUP-Famille",
    )

    /** A conversation as MessageTap and the panel hold it, in RAM only. */
    private fun ramThread() = MessageThread(MessageApp.CHEOGRAM, "zqx#annabelle@zqx.example", "0|com.cheogram.android|7|null|1", "ZQX-GROUP-Famille", true,
        listOf(IncomingMessage("m1", MessageApp.CHEOGRAM, "zqx#annabelle@zqx.example", "ZQX-RAM-SENDER", "annabelle@zqx.example", "ZQX-RAM-MESSAGE",
            t, true, true)), null, null, null, t)

    private fun plant(files: File) {
        val robot = File(files, "robot").apply { mkdirs() }
        val helper = HelperConfig.of(Presets.OTHER, "h1", "af_heart", name = helperName, baseUrl = address).copy(aliases = listOf(alias), model = homeModel)
        val s = RobotSettings(
            wakePhrases = listOf(WakePhrase(1, "hey robot"), WakePhrase(2, phrase)),
            helpers = listOf(HelperConfig.claude(), helper),
            weatherHome = WeatherPlace("41 Rue Zorblax, Montréal", 45.512345, -73.554321),
            smsGateway = "zorblaxgate.example",
            nearestServer = "https://nominatim.zorblax.example",
            phoneRegion = "CA",
            brainModel = BrainModel.OWN.id,
        )
        writeAtomic(File(robot, FeedbackSources.SETTINGS), s.toJson().toString(1))
        // A look.json from before 1.0, with the Classic look switch it no longer has.
        writeAtomic(File(robot, LookSettings.FILE), """{"v": 1, "wallpaperColors": false, "classic": true}""")
        writeAtomic(File(robot, UpdateSettings.FILE), UpdateSettings(repo = repo, skipped = "0.6.1").toJson())
        val found = FoundRelease(repo, "v0.7.1", "0.7.1", true, "2026-10-11T09:30:00Z", "ZQX-NOTES for $repo", FileRef(1, "Robot-0.7.1.apk", 9), FileRef(2, "SHA256SUMS", 9))
        writeAtomic(File(robot, UpdateState.FILE), UpdateState(lastCheck = t, outcome = CheckOutcome.FAILED, fail = UpdateFail.TOKEN_REFUSED, failHttp = 401,
            found = found, refusedWhy = UpdateFail.OTHER_SIGNER).toJson())
        writeAtomic(File(robot, "update_token.bin"), token)
        // The voice logs: numbers only; a damaged line with words is skipped when read.
        val log = listOf(VoiceLogEntry(t, VoiceEvent.WAKE, 2, 91, WakeAction.OPENED.id), VoiceLogEntry(t + 1_000, VoiceEvent.STT_DONE, 42, 3_000, 400),
            VoiceLogEntry(t + 2_000, VoiceEvent.KWS_HIT, 2, 1, 11), VoiceLogEntry(t + 3_000, VoiceEvent.SUMMARY_HELPER, 2, 12, 1),
            // 0.7.0: memory's and messages' events, codes and counts only.
            VoiceLogEntry(t + 3_100, VoiceEvent.MEM_SAVE, 1, 1, 0), VoiceLogEntry(t + 3_200, VoiceEvent.MEM_RECALL, 2, 1, 40),
            VoiceLogEntry(t + 3_300, VoiceEvent.MSG_READ, 1, 3, 0), VoiceLogEntry(t + 3_400, VoiceEvent.MSG_REPLY, 2, 1, 1),
            VoiceLogEntry(t + 3_500, VoiceEvent.MSG_LISTENER, 5, 2, 0), VoiceLogEntry(t + 3_600, VoiceEvent.MSG_GUARD, 3, 0, 0))
        writeAtomic(File(robot, VoiceFiles.LOG), VoiceLogCodec.encode(log) + "${t + 4_000} 1 $phrase 0 0\n${t + 5_000} 15 ZQX-CONVERSATION-TEXT 1 2\n")
        writeAtomic(File(robot, VoiceFiles.LOG_MAIN), VoiceLogCodec.encode(listOf(VoiceLogEntry(t + 6_000, VoiceEvent.FEEDBACK_OPENED, 1, 1))))
        writeAtomic(File(robot, GpuFailure.FILE), GpuFailure(BrainModel.GENERAL, t, ProblemStage.WRITING, null).encode())
        writeAtomic(File(robot, ProblemList.FILE), ProblemList.add(null, ModelProblem(t, BrainModel.OWN, ModelBackend.CPU, ProblemStage.WRITING, null, null, null)))
        writeAtomic(File(robot, "nearest_answer.json"), """{"place":"ZQX near 45.512345,-73.554321"}""")
        writeAtomic(File(robot, FeedbackPrefs.FILE), FeedbackPrefs("+15145550199").toJson())
        writeAtomic(File(robot, FeedbackPrefill.FILE), FeedbackPrefill(FeedbackKind.IDEA, "ZQX-PREFILL", t).toJson())
        writeAtomic(File(robot, "voice_status.json"), """{"bluetooth":"ZQX-BT-Headphones","wifi":"ZQX-WIFI-HomeNet"}""")
        // History, transcripts, messages, memory, notes, People, a helper's key, your own model file.
        writeAtomic(File(files, "conversations/c1.json"), """{"title":"ZQX-HISTORY-TITLE","turns":[{"text":"ZQX-CONVERSATION-TEXT on ZQX-WIFI-HomeNet """ +
            """with ZQX-BT-Headphones, lunch ZQX-CALENDAR-EVENT at Rue Zorblax"}]}""")
        writeAtomic(File(files, "transcripts/20261011-1402-7f3a/lines.jsonl"), """{"text":"ZQX-TRANSCRIPT-LINE"}""")
        writeAtomic(File(files, "messages/buffer.json"), """{"from":"ZQX-SENDER-Annabelle","text":"ZQX-MESSAGE-BODY"}""")
        writeAtomic(File(files, "memory/memory.json"), """{"items":[{"text":"ZQX-MEMORY-ITEM"}]}""")
        // 0.7.0's own files: memory (as if its file were never encrypted, and an Export saved in Robot's folder by mistake),
        // Settings › Memory and Settings › Messages, and History with a turn that read a message and one that used memory.
        val mem = MemoryFile(listOf(MemoryItem("m_0000000zqx001", "ZQX-MEMORY-ITEM", createdAt = t),
            MemoryItem("m_0000000zqx002", "ZQX-MEMORY-SECRET is 4512", secret = true, createdAt = t)))
        writeAtomic(File(files, "memory/memory.enc"), MemoryCodec.toJson(mem))
        writeAtomic(File(robot, "memory-export.json"), MemoryCodec.toJson(mem))
        writeAtomic(File(robot, MemorySettings.FILE), MemorySettings(onLockScreen = true, helpers = HelperMemory.TRUSTED_HELPERS).toJson())
        writeAtomic(File(robot, MessagesFile.FILE), MessagesFile(MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM, MessageApp.SIGNAL))),
            noCard = setOf("zqx-person-annabelle"), replyLang = mapOf("zqx-person-annabelle" to Lang.FR),
            pins = mapOf("org.thoughtcrime.securesms" to AppPin(setOf("5a".repeat(32)), "org.fdroid.fdroid"))).toJson())
        val read = ChatTurn("r1", TurnRole.ASSISTANT, "ZQX-SENDER-Annabelle, in Cheogram, 1 message:\n- ZQX-MESSAGE-BODY", t, messages = true,
            kept = MessageReading.historyLine(listOf(ramThread().copy(title = "ZQX-SENDER-Annabelle")), 1))
        writeAtomic(File(files, "conversations/c2.json"), ConversationCodec.toJson(StoredConversation("c2", t, t, listOf(
            ChatTurn("u1", TurnRole.USER, "read my messages", t, memoryIds = listOf("m_0000000zqx001")), read))))
        NoteStore(File(files, "notes").apply { mkdirs() }).add("ZQX-NOTE-TEXT and ZQX-CALENDAR-EVENT", "ZQX-NOTE-TEXT", t)
        PeopleStore(File(files, "people").apply { mkdirs() }).save(Person("p1", "ZQX-PERSON-Annabelle", phones = listOf(ContactPhone("+15145550177", app.heyrobot.core.PhoneKind.MOBILE, "ZQX-LABEL"))))
        writeAtomic(File(files, "helpers/h1/key.bin"), key)
        writeAtomic(File(files, "models/zorblax-own-model.gguf"), "GGUF")
    }

    /** The report the app makes from Robot's folder, every diagnostic ticked, with the facts and the problem report it adds. */
    private fun report(files: File): Triple<FeedbackReport.Composed, FeedbackReport.IssueLink, String> {
        val inputs = FeedbackSources.read(File(files, "robot"))
        // What the main and voice processes hold in RAM meanwhile: a report is made of files and Android's facts only.
        val held = MessageBuffer().apply { posted(ramThread()) }
        assertEquals(1, held.size)
        val problem = ProblemList.decode(app.heyrobot.core.readOrNull(File(files, "robot/${ProblemList.FILE}"))).first()
        // Android's own text in a problem report may hold anything; Robot scrubs paths and the last pass takes out the rest.
        val exits = listOf(
            ExitFacts(t, 4242, 6, "CRASH_NATIVE", 11, "$phrase to $helperName crashed in /data/app/~~x==/ai.robot_app-y==/lib/arm64/liblitertlm_jni.so",
                100, 1_900_000, 2_400_000),
            ExitFacts(t - 1_000, 4100, 6, "CRASH_NATIVE", 11, "reading /storage/emulated/0/Download/zorblax-own-model.gguf for ZQX-CONVERSATION-TEXT",
                100, 1_900_000, 2_400_000),
        )
        val reportText = ProblemReport.text(problem, ReportFacts("0.7.0", 70, Phone("Google", "Pixel 9a", "Google", "Tensor G4"), "16 (SDK 36)",
            "google/tegu/tegu:16/BP2A.250805.005/2025081900:user/release-keys", 1_234_567, exits, inputs.voiceLog), t + 60_000, utc)
        val blocks = listOf(
            FeedbackDiagnostics.problem(reportText),
            FeedbackDiagnostics.voiceLog(inputs.voiceLog, utc),
            FeedbackDiagnostics.settings(inputs.settings, inputs.look, inputs.updates, inputs.memory, inputs.messages),
            FeedbackDiagnostics.updater(inputs.updateState, utc),
        )
        val secrets = FeedbackPrivacy.secrets(inputs.settings, inputs.updates, "picocryptfan/robot-assistant", listOf("+15145550199"))
        val facts = FeedbackFacts(FeedbackFacts.robot("0.7.0", 70, false), FeedbackFacts.phone(Phone("Google", "Pixel 9a", "Google", "Tensor G4"), "16",
            "BP2A.250805.005", "2025081900"), FeedbackFacts.model(BrainModel.OWN, ModelBackend.CPU, inputs.gpuFailure, app.heyrobot.core.ModelFormat.GGUF),
            FeedbackFacts.look(inputs.look.wallpaperColors), "en-CA")
        val draft = FeedbackDraft(FeedbackKind.BUG, "Robot stopped mid-answer.", "Asked a long question.", "A whole answer.", Diagnostic.entries.toSet())
        val composed = FeedbackReport.compose(draft, "RFB-ABCD2345", facts, blocks, secrets)
        val link = FeedbackReport.issue(GitHubRepo("someone", "elsewhere"), draft, "RFB-ABCD2345", facts, blocks, secrets)
        return Triple(composed, link, reportText)
    }

    @Test fun nothingPersonalReachesTheReport() {
        val files = Files.createTempDirectory("feedback-privacy").toFile()
        try {
            plant(files)
            val (c, link, _) = report(files)
            val body = URLDecoder.decode(link.url.substringAfter("&report="), "UTF-8")
            for ((where, m) in markers) {
                assertFalse("$where reached the report", c.text.contains(m, ignoreCase = true))
                assertFalse("$where reached a preview", c.blocks.values.any { it.contains(m, ignoreCase = true) })
                assertFalse("$where reached the GitHub link", body.contains(m, ignoreCase = true))
            }
            // What it does hold: the codes, the switches, the phrase's number.
            for (w in listOf("Kind: Bug report", "Robot stopped mid-answer.", "Your own model file (.gguf) · CPU", "Look: Robot's colours",
                "WAKE 2 91 0  Wake word #2: opened the panel", "phrases=[#1 MEDIUM on, #2 MEDIUM on]", "Helpers: count=2", "OTHER on",
                "smsGateway=another", "home=set", "server=another", "repository=another", "error=TOKEN_REFUSED · http=401", "refusedFile=OTHER_SIGNER",
                "found=0.7.1", "CRASH_NATIVE (6)", "“[left out] to [left out] crashed in …/liblitertlm_jni.so”", "“reading …”",
                // 0.7.0: memory's and messages' switches and counts, and their events by code.
                "Memory: on=on · inAnswers=on · lockScreen=on · sayPrivateAloud=off · helpers=TRUSTED_HELPERS",
                "Messages: on=on · apps=CHEOGRAM,SIGNAL · groups=on", "keysKept=1 · firstUse=0 · withoutCard=1 · replyLanguages=1",
                "MSG_READ 1 3 0  Read 3 messages from Cheogram", "MEM_SAVE 1 1 0", "MSG_LISTENER 5 2 0  Notification listener kept an app's signing key")) {
                assertTrue(w, c.text.contains(w))
            }
            // The voice log's damaged lines with words were skipped, not shown.
            assertEquals(11, c.blocks.getValue(Diagnostic.VOICE_LOG).lines().count { Regex("""^\d\d-\d\d \d\d:\d\d:\d\d  [A-Z_]+ """).containsMatchIn(it) })
        } finally {
            files.deleteRecursively()
        }
    }

    /** The last pass: a line Robot didn't write (Android's text, here) loses any personal text Robot knows of, in any case. */
    @Test fun theLastPassLeavesOutWhatSlippedIn() {
        val s = RobotSettings(wakePhrases = listOf(WakePhrase(1, "hey robot"), WakePhrase(2, phrase)),
            helpers = listOf(HelperConfig.claude(), HelperConfig.of(Presets.OTHER, "h1", "af_heart", name = helperName, baseUrl = address)
                .copy(aliases = listOf(alias), model = homeModel)),
            weatherHome = WeatherPlace("41 Rue Zorblax, Montréal", 45.5, -73.6))
        val secrets = FeedbackPrivacy.secrets(s, UpdateSettings(repo = repo), "picocryptfan/robot-assistant", listOf("+15145550199"))
        assertEquals("said [left out] to [left out] at [left out] ([left out], [left out])",
            FeedbackPrivacy.redact("said HEY ZORBLAX KETTERING to Zorblax Home Mistral at $address ($homeModel, kettering BOX)", secrets))
        assertEquals("[left out] · [left out] · [left out]", FeedbackPrivacy.redact("$repo · picocryptfan/robot-assistant · +15145550199", secrets))
        assertEquals("weather for [left out]", FeedbackPrivacy.redact("weather for 41 Rue Zorblax, Montréal", secrets))
        // Claude's own name, the default wake phrase's words in Robot's own lines, and short words are left alone.
        assertEquals("Question sent to Claude", FeedbackPrivacy.redact("Question sent to Claude", secrets))
        assertFalse(secrets.any { it.length < FeedbackPrivacy.MIN_LENGTH })
        assertFalse("Claude" in secrets)
        // Your own words are never passed through it: the report keeps them as written.
        val c = FeedbackReport.compose(FeedbackDraft(FeedbackKind.IDEA, "When I say $phrase it opens"), "RFB-ABCD2345",
            FeedbackFacts("0.7.0 (70) · release build", "Pixel 9a", "Fast", "stock", "en-CA"), emptyList(), secrets)
        assertTrue(c.text.contains("When I say $phrase it opens"))
    }

    /** The settings summary is made of switches and levels: every value a code, a number, on or off. */
    @Test fun theSettingsSummaryIsSwitchesAndLevels() {
        val b = FeedbackDiagnostics.settings(RobotSettings(wakePhrases = listOf(WakePhrase(4, phrase))), LookSettings(), UpdateSettings(repo = repo))
        val values = b.lines.flatMap { l -> l.substringAfter(": ").split(" · ").map { it.substringAfter('=') } }
        for (v in values) assertTrue(v, Regex("""^[\[\]A-Za-z0-9_:,.' #-]+$""").matches(v) && !v.contains("zorblax", ignoreCase = true))
        assertTrue(b.lines.any { it.contains("phrases=[#4 MEDIUM on]") })
    }
}
