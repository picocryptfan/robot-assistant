// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import java.text.Normalizer
import java.util.Locale

// Pure. The words of Read it to me (docs/plans/read-it-to-me-plan.md §4), in English and Québec French:
//   Shared  "read me this", "read this article to me"; « lis-moi ça », « peux-tu me lire cet article »
//   Note    "read me my note about the trip", "read me my last note"; « lis-moi ma note sur le voyage »
//   File    "read me the PDF called lease"; « lis-moi le PDF du bail »
//   Pause   "pause reading"; « mets la lecture en pause »          (and "pause", « attends » while reading)
//   Resume  "keep reading"; « continue la lecture »                 (and "continue", « reprends » while paused)
//   Back    "go back a bit"; « reviens un peu en arrière »          (and "back", « recule » while reading)
//   Stop    "stop reading"; « arrête la lecture »
// Matched whole, after the words are folded (lower case, no accents, hyphens and apostrophes as spaces): plurals ("my
// notes", "my messages"), lists ("read me my packing list") and "read that again" are other routers' and never taken
// here (ReadRouterTest walks every routing corpus). A note's or File's words come back folded; NoteSearch folds the same way.

// The rule with point and read (core ReadAloud.ask): a bare "read this", « lis ça », with nothing attached opens the camera,
// or reads the photo held; a request naming text ("read me this article", "my note", « lis-moi cet article ») is this
// router's, which reads it or says there's nothing to read (ReadAskRuleTest).

// The panel asks it after Messages and before Lists (app PanelRead); it is in the spoken scripts' list (spoken.json).

/** What a phrase asks of Read it to me. */
sealed class ReadCommand {
    /** Read what was shared or picked ("Ask Robot"), or the page on screen. */
    object Shared : ReadCommand()
    /** Read a note: [query] names it, blank for the newest. */
    data class Note(val query: String) : ReadCommand() {
        override fun toString(): String = "Note:$query" // l10n: not text (toString, for the tests and logs)
    }
    /** Read a PDF or document in Robot Files: [query] names it. */
    data class File(val query: String) : ReadCommand() {
        override fun toString(): String = "File:$query" // l10n: not text (toString, for the tests and logs)
    }
    object Pause : ReadCommand()
    object Resume : ReadCommand()
    object Back : ReadCommand()
    object Stop : ReadCommand()

    override fun toString(): String = javaClass.simpleName
}

/** Whether a reading is on: bare words ("pause", "continue", "back") are Read it to me's only then. */
enum class ReadState { NONE, PLAYING, PAUSED }

object ReadRouter {
    fun route(text: String, state: ReadState = ReadState.NONE): ReadCommand? {
        val t = fold(text)
        if (t.isEmpty()) return null
        // « lecture » is also music playback (« mets ça en pause », « reprends la lecture »): with no reading on, the
        // French controls are the music's.
        if (state == ReadState.NONE && t in FRENCH) return null
        when (t) {
            in SHARED -> return ReadCommand.Shared
            in PAUSE -> return ReadCommand.Pause
            in RESUME -> return ReadCommand.Resume
            in BACK -> return ReadCommand.Back
            in STOP -> return ReadCommand.Stop
        }
        // "hey robot" pauses a reading first, so a bare "pause" finds it paused: still the reading's, never the music's.
        if (state != ReadState.NONE && t in BARE_PAUSE) return ReadCommand.Pause
        if (state == ReadState.PAUSED && t in BARE_RESUME) return ReadCommand.Resume
        if (state != ReadState.NONE && t in BARE_BACK) return ReadCommand.Back
        NOTE_EN.matchEntire(t)?.let { return ReadCommand.Note(query(it.groupValues[3], it.groupValues[4])) }
        NOTE_FR.matchEntire(t)?.let { return ReadCommand.Note(query(it.groupValues[3], it.groupValues[4])) }
        FILE_EN.matchEntire(t)?.let { return ReadCommand.File(it.groupValues[3].trim()).takeIf { c -> c.query.isNotEmpty() } }
        FILE_FR.matchEntire(t)?.let { return ReadCommand.File(it.groupValues[3].trim()).takeIf { c -> c.query.isNotEmpty() } }
        return null
    }

    /** "the newest" words say which note, not what it is about: a blank query. */
    private fun query(newest: String, rest: String): String = if (newest.isNotBlank() && rest.isBlank()) "" else rest.trim()

    /** Lower case, no accents, hyphens, apostrophes and punctuation as spaces, polite words and "Robot," dropped. */
    internal fun fold(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("""[-‐‑–—'’`«»"“”.,!?;:()\u00A0\u202F\s]+"""), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in LEAD) if (t.startsWith("$p ")) { t = t.removePrefix("$p ").trim(); changed = true }
            for (p in TRAIL) if (t.endsWith(" $p")) { t = t.removeSuffix(" $p").trim(); changed = true }
        }
        return t
    }

    private val LEAD = listOf("hey robot", "ok robot", "robot", "please", "can you", "could you", "would you", "will you", // l10n: not text (words Robot hears)
        "peux tu", "pourrais tu", "veux tu", "est ce que tu peux", "s il te plait", "stp") // l10n: not text (words Robot hears)
    private val TRAIL = listOf("please", "for me", "now", "s il te plait", "stp", "svp", "pour moi") // l10n: not text (words Robot hears)

    private val SHARED = setOf(
        "read me this", "read this to me", "read this", "read it to me", "read me that", "read that to me", "read this out", // l10n: not text (words Robot hears)
        "read this aloud", "read it aloud", "read this out loud", "read it out loud", "read aloud", "read this out to me", // l10n: not text (words Robot hears)
        "read me this article", "read this article", "read this article to me", "read me the article", "read the article to me", // l10n: not text (words Robot hears)
        "read me this page", "read this page", "read this page to me", "read me this text", "read this text to me", // l10n: not text (words Robot hears)
        "lis moi ca", "lis moi ceci", "lis ca", "lis ceci", "lis le moi", "lis la moi", "lis moi ca a voix haute", "lis ca a voix haute", // l10n: not text (words Robot hears)
        "me lire ca", "me lire ceci", "me lire cet article", "me lire cette page", "me lire ce texte", "lire ca", // l10n: not text (words Robot hears)
        "lis moi cet article", "lis moi l article", "lis moi cette page", "lis moi ce texte", "lis cet article", "lis moi ca au complet", // l10n: not text (words Robot hears)
    )
    private val PAUSE_EN = setOf("pause reading", "pause the reading", "pause reading this", "pause the article") // l10n: not text (words Robot hears)
    private val PAUSE_FR = setOf("mets la lecture en pause", "pause la lecture", "fais une pause dans la lecture", "pause lecture", "mets ca en pause") // l10n: not text (words Robot hears)
    private val RESUME_EN = setOf("keep reading", "continue reading", "resume reading", "carry on reading", "go on reading", "read on", // l10n: not text (words Robot hears)
        "start reading again", "pick up where you left off", "keep reading the article", "keep reading my note") // l10n: not text (words Robot hears)
    private val RESUME_FR = setOf("continue la lecture", "continue de lire", "continue a lire", "reprends la lecture", "poursuis la lecture", // l10n: not text (words Robot hears)
        "reprends ou tu etais rendu", "reprends ou tu en etais", "continue a me lire") // l10n: not text (words Robot hears)
    private val BACK_EN = setOf("go back a bit", "go back a little", "go back a little bit", "back up a bit", "back up a little", // l10n: not text (words Robot hears)
        "rewind a bit", "rewind a little", "skip back", "go back a sentence") // l10n: not text (words Robot hears)
    private val BACK_FR = setOf("reviens un peu en arriere", "recule un peu", "retourne un peu en arriere", "reviens une phrase en arriere", "recule un petit peu") // l10n: not text (words Robot hears)
    private val STOP_EN = setOf("stop reading", "stop the reading", "quit reading", "stop reading this", "stop reading to me") // l10n: not text (words Robot hears)
    private val STOP_FR = setOf("arrete la lecture", "arrete de lire", "arrete de me lire", "fin de la lecture") // l10n: not text (words Robot hears)
    private val PAUSE = PAUSE_EN + PAUSE_FR
    private val RESUME = RESUME_EN + RESUME_FR
    private val BACK = BACK_EN + BACK_FR
    private val STOP = STOP_EN + STOP_FR
    private val FRENCH = PAUSE_FR + RESUME_FR + BACK_FR + STOP_FR

    private val BARE_PAUSE = setOf("pause", "wait", "hold on", "attends", "une seconde") // l10n: not text (words Robot hears)
    private val BARE_RESUME = setOf("continue", "resume", "go on", "keep going", "play", "go ahead", "reprends", "vas y", "envoye") // l10n: not text (words Robot hears)
    private val BARE_BACK = setOf("back", "go back", "rewind", "recule", "reviens en arriere", "retour") // l10n: not text (words Robot hears)

    // "read me my note about the trip", "read my last note", "read out the note called packing".
    private val NOTE_EN = Regex("""(read|read me|read out|read out to me) (my|the) ((?:last|latest|newest|most recent|recent) )?note(?: to me)?(?: (?:about|on|called|titled|named|for|with) (.+))?""") // l10n: not text (words Robot hears)
    // « lis-moi ma note sur le voyage », « lis-moi ma dernière note », « lis ma note qui s'appelle épicerie ».
    private val NOTE_FR = Regex("""(lis|lis moi|lire|me lire) (ma|la) ((?:derniere|plus recente) )?note(?: la plus recente)?(?: (?:sur|a propos de|au sujet de|qui s appelle|intitulee|nommee|pour|de|du|des) (.+))?""") // l10n: not text (words Robot hears)
    private val FILE_EN = Regex("""(read|read me) (my|the) (?:pdf|document|file)(?: (?:about|on|called|titled|named))?( .+)""") // l10n: not text (words Robot hears)
    private val FILE_FR = Regex("""(lis|lis moi|me lire) (le|mon) (?:pdf|document|fichier)(?: (?:sur|qui s appelle|intitule|nomme|de|du|des))?( .+)""") // l10n: not text (words Robot hears)
}
