// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.util.Locale

// Pure. Settings › Voice › Transcription check (docs/conversation-mode.md 2.5, phase 5.0(a)): the gate for Québec French
// before conversation mode is trusted. You read the sentences below (or say anything and correct what Robot wrote, or
// import clips with their text), Robot decodes the recording exactly as a conversation would (the 0.25 s segments,
// Parakeet v3) and counts the words it got wrong (Wer). The audio stays in memory; what is kept is numbers only (voice
// log event 117), and "Copy report" copies numbers unless you tick "Include sentences".

/**
 * 0.8: the microphone a line was recorded with: the phone's, or a headset's or the car's (Settings › Voice ›
 * Transcription check › Microphone, offered while one with a microphone is connected), routed as a headset or car session
 * routes it ([CheckMics.route]), so the headset's microphone can be measured against the phone's (headset mode's H0.5,
 * car mode's C0.3).
 */
enum class CheckMic(val title: String) {
    PHONE("phone mic"),
    HEADSET("headset mic"),
}

object CheckMics {
    /**
     * The route the check records through for [CheckMic.HEADSET], as a headset or car session would take it
     * ([RoutePlanner]: the call route and the capture pinned to the headset's microphone, the car's call route for car
     * mode's [car]): a Bluetooth headset with a microphone first, else a wired or USB one. Null when none is connected
     * (or none has a microphone): the choice isn't offered then.
     */
    fun route(devices: List<app.heyrobot.core.output.SeenDevice>, memory: app.heyrobot.core.output.DeviceMemory,
        car: app.heyrobot.core.output.SeenDevice? = null): app.heyrobot.core.headset.RoutePlan? {
        for (entry in listOf(app.heyrobot.core.headset.EntryKind.BVRA, app.heyrobot.core.headset.EntryKind.HOOK)) {
            val d = app.heyrobot.core.headset.RoutePlanner.plan(entry, devices, memory, app.heyrobot.core.headset.HeadsetSettings(),
                callActive = false, car = car, wakeInCar = false)
            val plan = (d as? app.heyrobot.core.headset.RouteDecision.Plan)?.plan ?: continue
            if (plan.mic != app.heyrobot.core.headset.MicRoute.PHONE) return plan
        }
        return null
    }
}

/** How a line of the check was made. */
enum class CheckMode(val title: String) {
    /** One of [CheckSentences], read aloud. */
    READ("read"),
    /** Said freely, then corrected to what was really said. */
    FREE("free"),
    /** An imported clip with its reference line. */
    CLIP("clip"),
}

/**
 * The sentences to read: 20 in Québec French (everyday and colloquial, a third with Québec words) and 10 in English,
 * written for Robot. No digits and no numbers at all: Parakeet writes numbers as digits ("6 h 30"), so a sentence with
 * "six heures et demie" would count right answers as wrong.
 */
object CheckSentences {
    val FR: List<String> = listOf(
        "On se reprend-tu demain?",
        "J'ai pogné un char de trafic sur le pont Champlain.",
        "Peux-tu passer au dépanneur chercher du lait?",
        "Il fait frette à matin, mets ta tuque.",
        "Ma blonde pis moi, on s'en va au chalet en fin de semaine.",
        "C'est correct, inquiète-toi pas avec ça.",
        "Le souper est prêt, venez manger avant que ça refroidisse.",
        "J'ai oublié mon cellulaire dans l'auto.",
        "Ça m'a pris une éternité trouver un stationnement au centre-ville.",
        "Veux-tu une poutine ou ben une pizza ce soir?",
        "Les enfants jouent dehors dans la neige depuis le dîner.",
        "Je suis brûlé, je vais me coucher de bonne heure.",
        "Mon chum a magasiné toute la journée au centre d'achats.",
        "Faut que j'appelle le garage pour mes pneus d'hiver.",
        "Il mouille à boire debout, prends ton parapluie.",
        "Elle est tannée d'attendre l'autobus au coin de la rue.",
        "On va-tu voir la partie de hockey chez ton frère?",
        "Ma mère nous invite à souper dimanche prochain.",
        "J'ai de la misère à dormir quand il fait chaud de même.",
        "Arrête de niaiser pis viens m'aider à pelleter l'entrée.",
    )

    val EN: List<String> = listOf(
        "Can you grab some milk on your way home?",
        "I left my phone charger in the car again.",
        "Let's meet at the coffee shop near the library.",
        "The kids want pancakes for breakfast tomorrow.",
        "It's pouring outside, so bring an umbrella.",
        "I'm running late because the bus never showed up.",
        "Did you remember to call the dentist this morning?",
        "We should clean the garage before the weekend.",
        "My sister is coming over for dinner on Sunday.",
        "Turn down the music, the baby is finally asleep.",
    )

    /** French first, then English, each with its language. */
    val ALL: List<Pair<String, String>> = FR.map { "fr" to it } + EN.map { "en" to it }
}

/**
 * One line of the check: [lang] the reference's language ("fr" or "en"), [reference] what was said, [heard] what Robot
 * wrote, [detected] the language ID of what Robot wrote (null: not sure), [decodeMs] the decode time of its segments,
 * [audioMs] the recording's length, [reader] who read it (1, 2, ...: the gate wants two people).
 */
data class CheckLine(
    val mode: CheckMode,
    val lang: String,
    val reference: String,
    val heard: String,
    val detected: String?,
    val decodeMs: Long,
    val audioMs: Long,
    val reader: Int = 1,
    /** 0.8: the microphone it was recorded with. */
    val mic: CheckMic = CheckMic.PHONE,
) {
    val wer: WerResult by lazy { Wer.score(reference, heard) }
}

/** One language's lines together, recorded with [mic] (0.8). [readers]: different readers among them. */
data class CheckTotal(val lang: String, val lines: Int, val readers: Int, val result: WerResult, val mic: CheckMic = CheckMic.PHONE) {
    val mark: Double get() = if (lang == "fr") Wer.PASS_FR else Wer.PASS_EN

    val passes: Boolean get() = Wer.passes(result, lang)

    /** The gate (phase 5.0(a)): at least 20 French lines from at least 2 readers; English has no minimum. */
    val enough: Boolean get() = if (lang == "fr") lines >= CheckReport.GATE_LINES && readers >= CheckReport.GATE_READERS else lines > 0

    val verdict: CheckVerdict get() = when {
        !enough -> if (passes) CheckVerdict.SO_FAR_PASSES else CheckVerdict.SO_FAR_FAILS
        passes -> CheckVerdict.PASSES
        else -> CheckVerdict.FAILS
    }
}

enum class CheckVerdict(val title: String) {
    PASSES("passes"),
    FAILS("doesn't pass"),
    SO_FAR_PASSES("under the mark so far"),
    SO_FAR_FAILS("over the mark so far"),
}

object CheckReport {
    /** Phase 5.0(a)'s gate for Québec French. */
    const val GATE_LINES = 20
    const val GATE_READERS = 2

    fun langName(lang: String): String = when (lang) {
        "fr" -> "French (Québec)"
        "en" -> "English"
        else -> lang.uppercase(Locale.ROOT)
    }

    /**
     * Per language, French first; languages with no line are left out. 0.8: per microphone too (the phone's first), so
     * the headset's lines are never counted with the phone's.
     */
    fun totals(lines: List<CheckLine>): List<CheckTotal> = lines.groupBy { it.lang to it.mic }.entries
        .sortedWith(compareBy({ (k, _) -> if (k.first == "fr") 0 else if (k.first == "en") 1 else 2 }, { (k, _) -> k.second.ordinal }))
        .map { (k, ls) -> CheckTotal(k.first, ls.size, ls.map { it.reader }.distinct().size, Wer.total(ls.map { it.wer }), k.second) }

    /** "12% of words wrong (9% with accents not counted) over 20 lines, 2 readers. Pass mark 15%: passes." */
    fun summary(t: CheckTotal): String {
        val noAccents = Math.round(t.result.rateWithoutAccents.coerceAtLeast(0.0) * 100).toInt()
        val accents = if (t.result.accents > 0) " ($noAccents% with accents not counted)" else ""
        val readers = if (t.readers == 1) "1 reader" else "${t.readers} readers"
        val need = if (t.lang == "fr" && !t.enough) " The gate needs $GATE_LINES lines from $GATE_READERS readers." else ""
        val mic = if (t.mic == CheckMic.PHONE) "" else ", ${t.mic.title}"
        return "${langName(t.lang)}$mic: ${t.result.percent}% of words wrong$accents over ${t.lines} line${if (t.lines == 1) "" else "s"}, $readers. " +
            "Pass mark ${Math.round(t.mark * 100)}%: ${t.verdict.title}.$need"
    }

    /** One line, numbers only: "3. FR read · 2 of 9 words wrong (22%) · 1 accent · heard as FR · decoded in 412 ms · 3.1 s". */
    fun line(n: Int, l: CheckLine): String {
        val w = l.wer
        val acc = if (w.accents > 0) " · ${w.accents} accent${if (w.accents == 1) "" else "s"}" else ""
        val heardAs = l.detected?.uppercase(Locale.ROOT) ?: "?"
        val mic = if (l.mic == CheckMic.PHONE) "" else " · ${l.mic.title}"
        return "$n. ${l.lang.uppercase(Locale.ROOT)} ${l.mode.title} · reader ${l.reader}$mic · ${w.errors} of ${w.words} words wrong (${w.percent}%)$acc · " +
            "heard as $heardAs · decoded in ${l.decodeMs} ms · " + String.format(Locale.US, "%.1f s", l.audioMs / 1000.0)
    }

    /**
     * "Copy report": the totals and each line in numbers. The words said and heard only with [sentences] ("Include
     * sentences"), since your own words and other people's are personal.
     */
    fun text(lines: List<CheckLine>, sentences: Boolean, version: String): String = buildString {
        append("Robot transcription check · Robot ").append(version).append(" · Parakeet v3 with conversation settings\n")
        if (lines.isEmpty()) {
            append("No lines yet.\n")
            return@buildString
        }
        for (t in totals(lines)) append(summary(t)).append('\n')
        append('\n')
        lines.forEachIndexed { k, l ->
            append(line(k + 1, l)).append('\n')
            if (sentences) {
                append("   said: ").append(l.reference.replace('\n', ' ')).append('\n')
                append("   heard: ").append(l.heard.replace('\n', ' ')).append('\n')
            }
        }
    }

    /** Voice log event 117 (CONV_CHECK) per language: WER %, lines, the language. Numbers only. */
    fun logEvents(lines: List<CheckLine>): List<Triple<Int, Int, LangCode>> =
        totals(lines).map { Triple(it.result.percent.coerceAtMost(999), it.lines, LangCode.of(it.lang)) }
}

/** Imported clips (Settings › Voice › Transcription check › Import clips): audio files and a .txt of their lines. */
object CheckClips {
    sealed class Pairing {
        /** Each clip's name with its reference line, in the order to run them. */
        data class Ok(val pairs: List<Pair<String, String>>) : Pairing()
        data class Refused(val why: String) : Pairing()
    }

    private fun base(name: String): String = name.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)

    /**
     * Pairs clip [names] with the reference [text]. Lines of the form "name<TAB>sentence" (as in a Common Voice list;
     * a header line is skipped) pair by the clip's name, with or without its extension. Otherwise one line per clip,
     * in the order of the clips' names. Blank lines are ignored.
     */
    fun pair(names: List<String>, text: String): Pairing {
        if (names.isEmpty()) return Pairing.Refused("No clips picked.")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Pairing.Refused("The text file has no lines.")
        val byName = names.associateBy { base(it) }
        val tabbed = lines.filter { '\t' in it }
        if (tabbed.isNotEmpty() && tabbed.size >= lines.size - 1) {
            // A Common Voice .tsv names its columns, and the clip isn't the first ("client_id", "path", "sentence_id",
            // "sentence", ...): the clip is "path", the line "sentence". Otherwise "name<TAB>sentence".
            val head = lines.first().split('\t').map { it.trim().lowercase(Locale.ROOT) }
            val named = "path" in head && "sentence" in head
            val nameAt = if (named) head.indexOf("path") else 0
            val textAt = if (named) head.indexOf("sentence") else -1
            val found = tabbed.mapNotNull { l ->
                val cols = l.split('\t')
                val clip = byName[base(cols.getOrNull(nameAt) ?: return@mapNotNull null)] ?: return@mapNotNull null
                val sentence = (if (textAt >= 0) cols.getOrNull(textAt)?.takeIf { it.isNotBlank() } else cols.drop(nameAt + 1).firstOrNull { it.isNotBlank() })
                    ?.trim() ?: return@mapNotNull null
                clip to sentence
            }.distinctBy { it.first }
            if (found.isEmpty()) return Pairing.Refused("None of the names in the text file matches a clip.")
            val missing = names.size - found.size
            if (missing > 0) return Pairing.Refused("$missing clip${if (missing == 1) " has" else "s have"} no line in the text file.")
            return Pairing.Ok(names.sortedBy { it.lowercase(Locale.ROOT) }.map { n -> n to found.first { it.first == n }.second })
        }
        if (lines.size != names.size) return Pairing.Refused("${names.size} clip${if (names.size == 1) "" else "s"} but ${lines.size} line${if (lines.size == 1) "" else "s"}: " +
            "the text file needs one line per clip, in the order of the clips' names.")
        return Pairing.Ok(names.sortedBy { it.lowercase(Locale.ROOT) }.zip(lines))
    }
}
