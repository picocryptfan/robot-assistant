package app.heyrobot.core

// Pure. Speaker labels in a transcript (docs/conversation-mode.md 3, 5.3, phase 5.3): the labels themselves (S1, S2…,
// "Me" for an enrolled voice, "?" for a line too short to tell), a live session's speakers (the clusterer plus what you
// changed during it), and the changes you make from a transcript screen as the events the log stores: a name for a
// speaker (kept in that transcript only), two speakers who are one person, "this is me", a line that was someone else,
// and a line split where the other person started. Nothing here ever relabels a line by itself.

object SpeakerLabels {
    /** How "Me" (your enrolled voice, or the speaker you said is you) is shown and exported. */
    const val YOU = "You"
    /** A name you give a speaker, at most. */
    const val MAX_NAME = 40
    /** The one label of a transcript made without speaker labels (and before 0.5.0's phase 5.3). */
    const val SINGLE = ConversationRules.SPEAKER

    private val SHAPE = Regex("^S([1-9][0-9]{0,2})(′{0,9})$")

    /** A label Robot gives: "S1" to "S999" (with "′" marks after a resume), "Me" or "?". Anything else is refused. */
    fun valid(label: String): Boolean = label == SpeakerClusterer.ME || label == SpeakerClusterer.UNKNOWN || SHAPE.matches(label)

    /**
     * The palette slot of [label]'s chip: 0 for you, n for Sn (a resumed session's S1′ further on), -1 for "?" and
     * anything else. The screens take it modulo their palette.
     */
    fun colour(label: String): Int {
        if (label == SpeakerClusterer.ME) return 0
        val m = SHAPE.matchEntire(label) ?: return -1
        return m.groupValues[1].toInt() + 3 * m.groupValues[2].length
    }

    /** A label nobody in [existing] has, for "someone else": one more than the highest Sn (keeping no marks). */
    fun next(existing: Collection<String>): String {
        val top = existing.mapNotNull { SHAPE.matchEntire(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0
        return "S${(top + 1).coerceAtMost(999)}"
    }

    /**
     * The mark for the labels of a session resumed in a transcript that already has [labels] (its voices are gone from
     * memory, so its people get new labels): one "′" more than any of them has.
     */
    fun resumeMark(labels: Collection<String>): String =
        "′".repeat((labels.maxOfOrNull { l -> SHAPE.matchEntire(l)?.groupValues?.get(2)?.length ?: 0 } ?: 0) + 1)

    /** A name as you typed it: one line, at most [MAX_NAME] characters (empty: back to the label). */
    fun cleanName(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()
}

/**
 * One session's speakers, in the :voice process (docs/conversation-mode.md 3.2): the clusterer, each speaker's languages
 * (a short line leans on its speaker's usual language), the voices of the newest lines (so a line you move teaches the
 * speaker it went to), the merges you made, and the merges you turned down. Memory only: it all goes with the session.
 * Thread-safe: the decoder's worker labels lines, the session's thread applies what you change.
 */
class ConversationSpeakers(
    /** Off (no speaker model, or "Tell speakers apart" off): every line is [SpeakerLabels.SINGLE], as before. */
    val enabled: Boolean,
    maxSpeakers: Int = 6,
    /** For a resumed session: its labels get this mark ("S1′"). */
    mark: String = "",
    /** Your enrolled voiceprint (unit vector), which seeds "Me"; used once here and not kept beyond the clusterer. */
    me: FloatArray? = null,
) {
    private val k = SpeakerClusterer(maxSpeakers, mark = mark)
    private val langs = HashMap<String, ArrayDeque<LineLang>>()
    private val voices = LinkedHashMap<Int, FloatArray>()
    private val merged = HashMap<String, String>()
    private val declined = HashSet<Pair<String, String>>()

    init {
        if (enabled && me != null) k.seedMe(me)
    }

    /** "Me" was seeded from your voiceprint. */
    val seeded: Boolean @Synchronized get() = k.seeded

    /** The speakers told apart so far (event 106). */
    val count: Int @Synchronized get() = k.labels.size

    /** The speaker of one segment (see [SpeakerClusterer.assign]), with the merges you made applied. */
    @Synchronized
    fun assign(embedding: FloatArray?, speechMs: Int, startMs: Long, endMs: Long): SpeakerTurn {
        if (!enabled) return SpeakerTurn(SpeakerLabels.SINGLE, SpeakerKind.JOINED, 1f)
        val t = k.assign(embedding, speechMs, startMs, endMs)
        val label = resolve(t.label)
        return if (label == t.label) t else t.copy(label = label)
    }

    private fun resolve(label: String): String {
        var s = label
        repeat(16) { s = merged[s] ?: return s }
        return s
    }

    /** The language most of [speaker]'s lines are in, for the next short line of theirs (null: none clear, or "?"). */
    @Synchronized
    fun usualLang(speaker: String): String? =
        if (speaker == SpeakerClusterer.UNKNOWN) null else langs[resolve(speaker)]?.let { LangLabel.majority(it.toList()) }

    /** Line [i] by [speaker] is in [lang]; its [embedding] (if one was made) is kept a while in case you move the line. */
    @Synchronized
    fun heard(i: Int, speaker: String, lang: LineLang, embedding: FloatArray?) {
        if (!enabled) return
        if (speaker != SpeakerClusterer.UNKNOWN) langs.getOrPut(resolve(speaker)) { ArrayDeque() }.apply {
            addLast(lang)
            while (size > LANG_MEMORY) removeFirst()
        }
        if (embedding != null) {
            voices[i] = embedding
            while (voices.size > VOICE_MEMORY) voices.remove(voices.keys.first())
        }
    }

    /** You fixed line [i]'s language (its speaker's usual language counts it). */
    @Synchronized
    fun corrected(speaker: String, lang: String) {
        if (!enabled || speaker == SpeakerClusterer.UNKNOWN) return
        langs.getOrPut(resolve(speaker)) { ArrayDeque() }.addLast(LineLang(lang, false))
    }

    /** "S3 is S1": later lines that sound like either are [to]'s. */
    @Synchronized
    fun merge(from: String, to: String) {
        if (!enabled || from == to) return
        val a = resolve(from)
        val b = resolve(to)
        if (a == b) return
        // The clusterer merges the voices when it knows both; otherwise (a speaker from before a resume) the label maps. Into
        // your enrolled voice, the label maps too: "Me" never moves, so the other voice stays and keeps learning as you.
        if (b != SpeakerClusterer.ME) k.merge(a, b)
        merged[a] = b
        langs.remove(a)?.let { old -> langs.getOrPut(b) { ArrayDeque() }.apply { addAll(old); while (size > LANG_MEMORY) removeFirst() } }
    }

    /** You moved line [i] to [speaker]: its voice, if still in memory, teaches that speaker. */
    @Synchronized
    fun moved(i: Int, speaker: String) {
        claimed(speaker)
        if (!enabled) return
        voices[i]?.let { k.teach(resolve(speaker), it) }
    }

    /** You gave a line to [label] (a new one for someone else): the clusterer never hands it to another voice. */
    @Synchronized
    fun claimed(label: String) {
        k.reserve(label)
    }

    /** "Not the same person": that pair isn't proposed again in this session. */
    @Synchronized
    fun decline(from: String, to: String) {
        declined += from to to
        declined += to to from
    }

    /**
     * Pairs of speakers who sound alike ("S3 looks like S1 — merge?"), not ones you turned down. Nothing is changed. Their
     * mean voices are compared at [PROPOSE_AT], looser than a line joining a speaker: one person heard near and far
     * from the phone can split in two, and a merge is only offered.
     */
    @Synchronized
    fun proposals(): List<Pair<String, String>> = if (!enabled) emptyList() else
        k.proposals(PROPOSE_AT).map { (a, b) -> resolve(a) to resolve(b) }.filter { (a, b) -> a != b && (a to b) !in declined }.distinct()

    /** The labels the clusterer has handed out (for "someone else" to avoid). */
    val labels: List<String> @Synchronized get() = k.labels

    /** Your voiceprint was deleted during the session: "Me" is no longer seeded. */
    @Synchronized
    fun forgetMe(): Boolean = k.forgetMe()

    /** The session ended: every voice is forgotten. */
    @Synchronized
    fun clear() {
        k.clear()
        langs.clear()
        voices.clear()
        merged.clear()
        declined.clear()
    }

    companion object {
        /** Each speaker's languages: the newest this many lines. */
        const val LANG_MEMORY = 30
        /** The voices of the newest this many lines (192 numbers each), for a line you move. */
        const val VOICE_MEMORY = 200
        /** Two speakers' mean voices at least this close are offered as one person (tuned on the phone, 5.0(d)). */
        const val PROPOSE_AT = 0.40f
    }
}

/**
 * What you change about speakers and lines, from a transcript screen, as the event the log appends (null: nothing to do,
 * or not allowed). Checked against the transcript as it stands, so a screen can never write a label Robot didn't give.
 */
object TranscriptEdits {
    private fun known(t: Transcript, s: String) = SpeakerLabels.valid(s) && s in t.speakers

    /** Name [speaker] (an empty name takes it back to the label). Kept in this transcript only. */
    fun rename(t: Transcript, speaker: String, name: String): TranscriptEvent.Rename? {
        if (!known(t, speaker) || speaker == SpeakerClusterer.UNKNOWN) return null
        val n = SpeakerLabels.cleanName(name)
        if (n == (t.names[speaker] ?: "")) return null
        return TranscriptEvent.Rename(speaker, n)
    }

    /**
     * "[from] is [to]": all of [from]'s lines become [to]'s. Never from or into "?" (move those lines one by one), and
     * never "Me" into someone else (the other way round). Into "Me" also before your enrolled voice has a line.
     */
    fun merge(t: Transcript, from: String, to: String): TranscriptEvent.Merge? {
        if (from == to || !known(t, from) || !(known(t, to) || to == SpeakerClusterer.ME)) return null
        if (from == SpeakerClusterer.UNKNOWN || to == SpeakerClusterer.UNKNOWN || from == SpeakerClusterer.ME) return null
        return TranscriptEvent.Merge(from, to)
    }

    /**
     * "This is me": [speaker] is you. When your enrolled voice ("Me") is in the transcript, or [seeded] in the session
     * recording it, that is a merge into it (one "You", and later lines in your voice go there too).
     */
    fun me(t: Transcript, speaker: String, seeded: Boolean = false): TranscriptEvent? {
        if (!known(t, speaker) || speaker == SpeakerClusterer.UNKNOWN || speaker == SpeakerClusterer.ME || t.me == speaker) return null
        if (seeded || SpeakerClusterer.ME in t.speakers) return merge(t, speaker, SpeakerClusterer.ME)
        return TranscriptEvent.Me(speaker)
    }

    /** Line [i] was [speaker]'s (one in the transcript, or a new label for someone else). */
    fun move(t: Transcript, i: Int, speaker: String): TranscriptEvent.Speaker? {
        val l = t.line(i) ?: return null
        if (!SpeakerLabels.valid(speaker) || speaker == SpeakerClusterer.UNKNOWN || speaker == l.speaker) return null
        return TranscriptEvent.Speaker(i, speaker)
    }

    /**
     * Line [i] splits at character [at]: the rest becomes line [n] (an index never used), [speaker]'s (null: the same).
     * Both parts must keep some words.
     */
    fun split(t: Transcript, i: Int, at: Int, n: Int, speaker: String?): TranscriptEvent.Split? {
        val l = t.line(i) ?: return null
        if (at <= 0 || at >= l.text.length || n < 0 || t.line(n) != null) return null
        if (l.text.substring(0, at).isBlank() || l.text.substring(at).isBlank()) return null
        if (speaker != null && (!SpeakerLabels.valid(speaker) || speaker == SpeakerClusterer.UNKNOWN)) return null
        return TranscriptEvent.Split(i, at, n, speaker?.takeIf { it != l.speaker })
    }

    /** Where each word of [text] starts (character indices): the places a line can be split, after the first word. */
    fun wordStarts(text: String): List<Int> = Regex("\\S+").findAll(text).map { it.range.first }.toList()

    /** The speakers a line or speaker can go to: the transcript's, without "?", yours first. */
    fun targets(t: Transcript): List<String> = targets(t.speakers, t.me)

    /** [targets] from a screen's list of [speakers] and who is you ([me]). */
    fun targets(speakers: List<String>, me: String?): List<String> = speakers.filter { it != SpeakerClusterer.UNKNOWN }.distinct()
        .sortedWith(compareBy<String> { if (it == SpeakerClusterer.ME || it == me) 0 else 1 }.thenBy { SpeakerLabels.colour(it) })
}

/** The audio speaker labels look at (docs/conversation-mode.md 3.2). */
object SpeakerAudio {
    /**
     * The speech of [seg] in its padded [audio] (sliced from seg.start), without the padding: what is embedded. Null
     * under [SpeakerClusterer.MIN_SPEECH_MS] of speech (the line then inherits its speaker, or is "?").
     */
    fun speech(seg: Segment, audio: FloatArray, rate: Int = Pcm.RATE): FloatArray? {
        if (seg.speechMs < SpeakerClusterer.MIN_SPEECH_MS) return null
        val a = (seg.speechStart - seg.start).coerceIn(0, audio.size.toLong()).toInt()
        val b = (seg.speechEnd - seg.start).coerceIn(a.toLong(), audio.size.toLong()).toInt()
        return if (b - a < rate / 2) null else audio.copyOfRange(a, b)
    }

    /** Where [seg]'s speech starts and ends on the session's clock (ms), in a run that began [offsetS] seconds in. */
    fun clock(offsetS: Double, seg: Segment, rate: Int = Pcm.RATE): Pair<Long, Long> =
        Math.round(offsetS * 1000 + seg.speechStart * 1000.0 / rate) to Math.round(offsetS * 1000 + seg.speechEnd * 1000.0 / rate)
}
