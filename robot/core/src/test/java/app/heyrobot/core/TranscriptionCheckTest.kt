package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Voice › Transcription check (docs/conversation-mode.md 2.5, phase 5.0(a)). */
class TranscriptionCheckTest {
    @Test fun twentyQuebecAndTenEnglishSentencesWithoutNumbers() {
        assertEquals(20, CheckSentences.FR.size)
        assertEquals(10, CheckSentences.EN.size)
        assertEquals(30, CheckSentences.ALL.size)
        assertEquals(CheckSentences.FR.size + CheckSentences.EN.size, CheckSentences.ALL.map { it.second }.distinct().size)
        val numberWords = Regex("""\b(un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|vingt|cent|mille|one|two|three|four|five|seven|eight|nine|ten|twelve|hundred|thousand)\b""",
            RegexOption.IGNORE_CASE)
        for ((lang, s) in CheckSentences.ALL) {
            assertFalse(s, s.any { it.isDigit() })
            // Parakeet writes numbers as digits: a number word would count a right answer as wrong. ("un"/"une" as articles are fine in French.)
            if (lang == "en") assertFalse(s, numberWords.containsMatchIn(s))
            else assertFalse(s, Regex("""\b(deux|trois|quatre|cinq|six|sept|huit|neuf|dix|vingt|cent|mille)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s))
            assertTrue(s, Wer.words(s).size in 5..14)
        }
        // The sentences are labelled as the language ID reads them (a check of the check itself).
        val id = LangId.load(java.io.File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream())
        for ((lang, s) in CheckSentences.ALL) assertEquals(s, lang, id.detect(s).lang)
        // A third of the French ones carry Québec words.
        val quebec = listOf("pogné", "frette", "tuque", "blonde", "pis", "dépanneur", "chum", "magasiné", "tannée", "niaiser", "misère", "char", "ben", "mouille", "brûlé", "-tu")
        assertTrue(CheckSentences.FR.count { s -> quebec.any { s.lowercase().contains(it) } } >= 7)
    }

    private fun line(lang: String, ref: String, heard: String, reader: Int = 1, mode: CheckMode = CheckMode.READ) =
        CheckLine(mode, lang, ref, heard, lang, 400, 3_000, reader)

    @Test fun totalsPerLanguageAndTheGate() {
        val fr = (1..20).map { k -> line("fr", "on se reprend tu demain matin chez nous", if (k <= 2) "on se reprend demain matin chez nous" else "on se reprend tu demain matin chez nous", reader = if (k <= 10) 1 else 2) }
        val en = listOf(line("en", "can you grab some milk", "can you grab some milk"), line("en", "turn down the music", "turn down the music please"))
        val t = CheckReport.totals(en + fr)
        assertEquals(listOf("fr", "en"), t.map { it.lang })
        val f = t[0]
        assertEquals(20, f.lines)
        assertEquals(2, f.readers)
        assertEquals(160, f.result.words)
        assertEquals(2, f.result.errors)
        assertTrue(f.enough && f.passes)
        assertEquals(CheckVerdict.PASSES, f.verdict)
        // English: 1 error in 9 words is 11 %, over the 8 % mark.
        val e = t[1]
        assertEquals(11, e.result.percent)
        assertEquals(CheckVerdict.FAILS, e.verdict)
        // Fewer than 20 lines or one reader: "so far".
        val few = CheckReport.totals(fr.take(19)).single()
        assertEquals(CheckVerdict.SO_FAR_PASSES, few.verdict)
        val oneReader = CheckReport.totals(fr.map { it.copy(reader = 1) }).single()
        assertFalse(oneReader.enough)
        assertTrue(CheckReport.summary(oneReader).contains("The gate needs 20 lines from 2 readers."))
        assertEquals("French (Québec): 1% of words wrong over 20 lines, 2 readers. Pass mark 15%: passes.", CheckReport.summary(f))
    }

    @Test fun accentsAreReportedApart() {
        val t = CheckReport.totals(listOf(line("fr", "il a été déçu", "il a ete déçu"))).single()
        assertEquals("French (Québec): 25% of words wrong (0% with accents not counted) over 1 line, 1 reader. Pass mark 15%: " +
            "over the mark so far. The gate needs 20 lines from 2 readers.", CheckReport.summary(t))
    }

    @Test fun theReportHoldsNumbersUnlessSentencesAreIncluded() {
        val lines = listOf(line("fr", "Peux-tu passer au dépanneur chercher du lait?", "peux-tu passer au dépanneur chercher du lait"),
            CheckLine(CheckMode.FREE, "en", "my secret plan for Marie", "my secret plan for Mary", null, 812, 2_400, 2))
        val plain = CheckReport.text(lines, sentences = false, version = "0.5.0")
        for (w in listOf("dépanneur", "secret", "Marie", "Mary", "lait")) assertFalse(w, plain.contains(w))
        assertTrue(plain.contains("1. FR read · reader 1 · 0 of 8 words wrong (0%) · heard as FR · decoded in 400 ms · 3.0 s"))
        assertTrue(plain.contains("2. EN free · reader 2 · 1 of 5 words wrong (20%) · heard as ? · decoded in 812 ms · 2.4 s"))
        val full = CheckReport.text(lines, sentences = true, version = "0.5.0")
        assertTrue(full.contains("   said: my secret plan for Marie\n   heard: my secret plan for Mary\n"))
        assertEquals("Robot transcription check · Robot 0.5.0 · Parakeet v3 with conversation settings\nNo lines yet.\n", CheckReport.text(emptyList(), true, "0.5.0"))
    }

    /**
     * 0.8: the headset's (or the car's) microphone, measured against the phone's (headset mode's H0.5, car mode's C0.3): its
     * lines say so, and their totals are kept apart from the phone's, so the gate is never met with the two mixed.
     */
    @Test fun theHeadsetsMicrophoneIsScoredApart() {
        val phone = (1..2).map { line("fr", "on se reprend tu demain", "on se reprend tu demain") }
        val headset = listOf(line("fr", "on se reprend tu demain", "on se reprend demain").copy(mic = CheckMic.HEADSET))
        val t = CheckReport.totals(headset + phone)
        assertEquals(listOf(CheckMic.PHONE to 2, CheckMic.HEADSET to 1), t.map { it.mic to it.lines })
        assertEquals(0, t[0].result.percent)
        assertEquals(20, t[1].result.percent)
        assertTrue(CheckReport.summary(t[1]), CheckReport.summary(t[1]).startsWith("French (Québec), headset mic: 20% of words wrong over 1 line"))
        assertTrue(CheckReport.summary(t[0]).startsWith("French (Québec): 0% of words wrong"))
        assertTrue(CheckReport.line(3, headset.single()).startsWith("3. FR read · reader 1 · headset mic · 1 of 5 words wrong (20%)"))
        assertEquals(2, CheckReport.logEvents(headset + phone).size)
    }

    /** Which headset the check records through: the route a headset or car session would take, or none to offer. */
    @Test fun theCheckRecordsThroughTheHeadsetAsASessionWould() {
        val memory = app.heyrobot.core.output.DeviceMemory()
        val sco = app.heyrobot.core.output.SeenDevice(app.heyrobot.core.output.RouteType.BT_SCO, "Buds", "XX:XX:XX:XX:12:34", id = 7)
        val a2dp = sco.copy(type = app.heyrobot.core.output.RouteType.BT_A2DP, id = 8)
        val bt = CheckMics.route(listOf(a2dp, sco), memory)!!
        assertEquals(app.heyrobot.core.headset.MicRoute.SCO, bt.mic)
        assertEquals(sco, bt.communication)
        val usb = app.heyrobot.core.output.SeenDevice(app.heyrobot.core.output.RouteType.USB_HEADSET, "USB-C", id = 9)
        val wired = CheckMics.route(listOf(usb), memory)!!
        assertEquals(app.heyrobot.core.headset.MicRoute.USB, wired.mic)
        assertEquals(null, wired.communication)
        // Headphones without a microphone, a speaker, or nothing: no choice.
        assertEquals(null, CheckMics.route(listOf(a2dp), memory))
        assertEquals(null, CheckMics.route(listOf(app.heyrobot.core.output.SeenDevice(app.heyrobot.core.output.RouteType.SPEAKER, id = 1)), memory))
        assertEquals(null, CheckMics.route(emptyList(), memory))
        // In car mode, the car's call route.
        val car = sco.copy(name = "MyCar", address = "XX:XX:XX:XX:56:78", id = 11)
        val inCar = CheckMics.route(listOf(car), memory, car = car)!!
        assertEquals(car, inCar.communication)
    }

    @Test fun logEventsAreNumbersPerLanguage() {
        val ev = CheckReport.logEvents(listOf(line("en", "a b c d", "a b c"), line("fr", "un deux", "un deux")))
        assertEquals(listOf(Triple(0, 1, LangCode.FR), Triple(25, 1, LangCode.EN)), ev)
    }

    @Test fun clipsPairByOrderOrByName() {
        val byOrder = CheckClips.pair(listOf("b.mp3", "A.mp3"), "première phrase\n\nseconde phrase\n")
        assertEquals(CheckClips.Pairing.Ok(listOf("A.mp3" to "première phrase", "b.mp3" to "seconde phrase")), byOrder)
        val tsv = "path\tsentence\ncommon_voice_fr_2.mp3\tdeuxième\ncommon_voice_fr_1.mp3\tpremière\n"
        assertEquals(CheckClips.Pairing.Ok(listOf("common_voice_fr_1.mp3" to "première", "common_voice_fr_2.mp3" to "deuxième")),
            CheckClips.pair(listOf("common_voice_fr_2.mp3", "common_voice_fr_1.mp3"), tsv))
        // Common Voice's own lists (validated.tsv and the like): the client's id first, the clip under "path", its line under
        // "sentence", and more columns after (some empty).
        val cv = "client_id\tpath\tsentence_id\tsentence\tsentence_domain\tup_votes\tdown_votes\tage\tgender\taccents\tvariant\tlocale\tsegment\n" +
            "9f2c\tcommon_voice_fr_41.mp3\tab12\tIl fait frette à matin.\t\t2\t0\t\t\tQuébec\t\tfr\t\n" +
            "77e1\tcommon_voice_fr_40.mp3\tcd34\tMets ta tuque.\t\t3\t0\tthirties\t\t\t\tfr\t\n"
        assertEquals(CheckClips.Pairing.Ok(listOf("common_voice_fr_40.mp3" to "Mets ta tuque.", "common_voice_fr_41.mp3" to "Il fait frette à matin.")),
            CheckClips.pair(listOf("common_voice_fr_41.mp3", "common_voice_fr_40.mp3"), cv))
        // Without the extension in the list.
        assertEquals(CheckClips.Pairing.Ok(listOf("x.ogg" to "bonjour")), CheckClips.pair(listOf("x.ogg"), "x\tbonjour"))
        assertTrue(CheckClips.pair(listOf("a.mp3", "b.mp3"), "seule ligne") is CheckClips.Pairing.Refused)
        assertTrue(CheckClips.pair(listOf("a.mp3", "b.mp3"), "a\tun\n") is CheckClips.Pairing.Refused)
        assertTrue(CheckClips.pair(emptyList(), "x") is CheckClips.Pairing.Refused)
        assertTrue(CheckClips.pair(listOf("a.mp3"), "\n \n") is CheckClips.Pairing.Refused)
    }

    @Test fun pcmToTheModelsFormat() {
        assertEquals(listOf(0f, 0.5f, -1f), Pcm.fromS16(byteArrayOf(0, 0, 0, 0x40, 0, 0x80.toByte())).toList())
        assertEquals(listOf(0.5f, -0.25f), Pcm.mono(floatArrayOf(1f, 0f, -0.5f, 0f), 2).toList())
        val x = FloatArray(48_000) { kotlin.math.sin(it * 2 * Math.PI * 300 / 48_000).toFloat() }
        val y = Pcm.resample(x, 48_000)
        assertEquals(16_000, y.size)
        // A 300 Hz tone survives: same peak, roughly.
        assertEquals(1.0, y.maxOf { it }.toDouble(), 0.05)
        val up = Pcm.resample(floatArrayOf(0f, 1f), 8_000)
        assertEquals(listOf(0f, 0.5f, 1f, 1f), up.toList())
        assertEquals(1_000L, Pcm.ms(16_000))
    }
}
