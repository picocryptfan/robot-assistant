// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.HelperRouter
import app.heyrobot.core.TextCommand

// Pure. Headset and car mode by voice (design §4.3 "Phrases", §2.3 "repeat", §3.3), English and Québec French, decided by
// code before anything reaches the model and anchored at the start of what you said like HelperRouter: only "hey robot",
// "please", "can you", "peux-tu" and the like in front, and only "please", "now", "merci" after. The short commands must
// be the whole utterance, so "I'm driving to Montreal tomorrow, what's the weather there" and "my car mode button is
// broken" stay questions. Car mode's own phrases (navigate, call, messages, what's next, music) count only while car mode
// is on; outside it they go to the model and the tools as today. While the brief speaks, the routines'
// BriefControl ("next", "skip", "repeat", "stop") is asked first.

/** A media key sent to the current player (AudioManager.dispatchMediaKeyEvent, which needs no permission). */
enum class MusicAction(val keyCode: Int) {
    PLAY(126), PAUSE(127), NEXT(87), PREVIOUS(88);
}

/** Where "navigate …" goes, as said. */
enum class PlaceWord { HOME, WORK, NEAREST, OTHER }

sealed class HandsFreeCommand {
    data object CarModeOn : HandsFreeCommand()
    data object CarModeOff : HandsFreeCommand()
    /** "Read that again", "répète": the last thing Robot said. */
    data object Repeat : HandsFreeCommand()
    /** "Stop listening", "c'est tout": end the follow-up window (and the headset session). */
    data object StopListening : HandsFreeCommand()

    // Car mode only.
    /** [what]: the place as said (the kind of place for [PlaceWord.NEAREST], the search for [PlaceWord.OTHER]). */
    data class Navigate(val place: PlaceWord, val what: String) : HandsFreeCommand()
    /** [who] as said; [label]: "mobile", "work" or "home" when you said which number. */
    data class Call(val who: String, val label: String? = null) : HandsFreeCommand()
    data object ReadMessages : HandsFreeCommand()
    /** "Reply I'm on my way", "dis-lui que j'arrive": [text] without its lead-in (ReplyWords). */
    data class Reply(val text: String) : HandsFreeCommand()
    data object WhatsNext : HandsFreeCommand()
    data class Music(val action: MusicAction) : HandsFreeCommand()
}

object HandsFreeRouter {
    /** A word: plain (no case, accents or apostrophes: "j'ai-tu" is jai, tu) and where it is in what was said. */
    private class Tok(val w: String, val start: Int, val end: Int)

    private fun tokens(text: String): List<Tok> =
        Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*").findAll(text).map { Tok(HelperRouter.plain(it.value), it.range.first, it.range.last + 1) }.toList()

    private fun list(vararg p: String): List<List<String>> = p.map { it.split(' ') }.sortedByDescending { it.size }

    private val HEAD = list("hey robot", "hi robot", "ok robot", "okay robot", "hello robot", "he robot", "dis robot", "robot", "please", "can you", "could you",
        "would you", "will you", "ok", "okay", "so", "um", "uh", "euh", "hey", "peux tu", "pourrais tu", "est ce que tu peux", "sil te plait", "sil vous plait",
        "s il te plait", "s il vous plait", "dac", "alors")
    private val TAIL = list("please", "now", "thanks", "thank you", "merci", "sil te plait", "sil vous plait", "s il te plait", "s il vous plait", "maintenant", "robot")

    private val CAR_ON = list("car mode", "turn on car mode", "start car mode", "car mode on", "switch to car mode", "enable car mode", "go to car mode",
        "driving mode", "start driving mode", "turn on driving mode", "im driving", "i am driving", "mode auto", "mode voiture", "active le mode auto",
        "demarre le mode auto", "mets le mode auto", "lance le mode auto", "passe en mode auto", "je conduis", "active le mode voiture",
        "activer le mode auto", "demarrer le mode auto", "mettre le mode auto")
    private val CAR_OFF = list("end car mode", "stop car mode", "turn off car mode", "car mode off", "exit car mode", "leave car mode", "disable car mode",
        "quit car mode", "end driving mode", "turn off driving mode", "fin du mode auto", "arrete le mode auto", "desactive le mode auto", "quitte le mode auto",
        "sors du mode auto", "ferme le mode auto", "termine le mode auto", "mode auto off", "arrete le mode voiture", "fin du mode voiture",
        "arreter le mode auto", "desactiver le mode auto", "quitter le mode auto")
    private val REPEAT = list("repeat", "repeat that", "repeat it", "repeat that please", "say that again", "say it again", "read that again", "read it again",
        "can you repeat that", "what did you say", "come again", "repete", "repete ca", "repete moi ca", "repete le", "redis le", "redis ca", "redis moi ca",
        "relis le", "relis ca", "relis moi ca", "repeter", "repeter ca", "tu peux repeter", "repete sil te plait")
    private val STOP_LISTENING = list("stop listening", "stop listening to me", "you can stop listening", "thats all", "that is all", "thats all for now",
        "im done", "i am done", "thats it", "thanks thats all", "thank you thats all", "arrete decouter", "arrete de mecouter", "cest tout", "cest tout pour linstant",
        "merci cest tout", "jai fini", "cest beau")
    private val READ_MESSAGES = list("read my messages", "read me my messages", "read messages", "read my new messages", "any messages", "any new messages",
        "do i have messages", "do i have any messages", "do i have new messages", "do i have any new messages", "check my messages", "lis moi mes messages",
        "lis mes messages", "jai tu des messages", "est ce que jai des messages", "ai je des messages", "jai tu des nouveaux messages", "mes messages")
    private val WHATS_NEXT = list("whats next", "what is next", "whats next on my calendar", "whats my next meeting", "what is my next meeting", "my next meeting",
        "next meeting", "whats my next appointment", "my next appointment", "my next event", "whats my next event", "cest quoi la suite", "quest ce qui suit",
        "mon prochain rendez vous", "cest quoi mon prochain rendez vous", "cest quand mon prochain rendez vous", "mon prochain meeting")
    private val MUSIC: Map<List<String>, MusicAction> = buildMap {
        for (p in listOf("play music", "play some music", "play my music", "resume music", "resume the music", "start the music", "unpause", "unpause the music",
                "mets de la musique", "mets la musique", "joue de la musique", "reprends la musique", "remets la musique", "partir la musique", "part la musique")) put(p.split(' '), MusicAction.PLAY)
        for (p in listOf("pause", "pause the music", "pause music", "stop the music", "stop music", "mets la musique sur pause", "pause la musique",
                "arrete la musique", "coupe la musique")) put(p.split(' '), MusicAction.PAUSE)
        for (p in listOf("next song", "next track", "skip this song", "skip song", "skip the song", "chanson suivante", "prochaine chanson", "passe la chanson",
                "toune suivante", "prochaine toune", "passe la toune")) put(p.split(' '), MusicAction.NEXT)
        for (p in listOf("previous song", "previous track", "last song", "go back a song", "chanson precedente", "toune precedente")) put(p.split(' '), MusicAction.PREVIOUS)
    }

    private val NAV = list("navigate to", "navigate", "take me to", "take me", "drive me to", "drive me", "directions to", "get directions to", "give me directions to",
        "route to", "lets go to", "itineraire vers", "itineraire pour", "itineraire a", "itineraire", "amene moi a", "amene moi", "emmene moi a", "emmene moi",
        "conduis moi a", "conduis moi", "direction")
    private val HOME = list("home", "the house", "my house", "maison", "la maison", "chez moi", "chez nous", "a la maison", "a la maison sil te plait")
    private val WORK = list("work", "the office", "my office", "the work", "my work", "office", "travail", "le travail", "au travail", "bureau", "le bureau", "au bureau", "job",
        "la job", "ma job")
    private val CALL = list("call", "phone", "ring", "dial", "appelle", "appeler", "telephone a", "telephone", "compose")
    /** Never who a call is to: "call me later", "call it a day", "call off the meeting", "call back". */
    private val NOT_WHO = setOf("me", "it", "that", "this", "back", "later", "off", "up", "out", "of", "for", "him", "her", "them", "us", "a", "an", "moi", "le", "la",
        "les", "ca", "lui", "leur", "nous", "en", "y", "pour", "de", "du", "des")
    private val REPLY = list("reply", "reply that", "reply to her", "reply to him", "reply to them", "tell her", "tell him", "tell them", "tell her that",
        "tell him that", "tell them that", "respond", "reponds lui que", "reponds leur que", "reponds lui", "reponds que", "dis lui que", "dis leur que", "dis lui")

    /** Labels after a name: "on her cell", "at work", "sur son cell", "au bureau". */
    private val LABEL_LEAD = setOf("on", "at", "sur", "au", "a", "to")
    private val LABEL_FILLER = setOf("her", "his", "their", "the", "my", "son", "sa", "le", "la", "ses")
    private val LABEL_WORD = mapOf("cell" to "mobile", "cellphone" to "mobile", "mobile" to "mobile", "cellulaire" to "mobile", "portable" to "mobile",
        "work" to "work", "office" to "work", "travail" to "work", "bureau" to "work", "job" to "work", "home" to "home", "maison" to "home")
    private val LABEL_TAIL = setOf("phone", "number", "numero", "telephone", "line")

    private fun at(w: List<String>, i: Int, p: List<String>): Boolean = i + p.size <= w.size && p.indices.all { w[i + it] == p[it] }

    /** The command at the start of [text], or null (ordinary talk). [carMode]: car mode is on. [wakePhrases]: yours. */
    fun route(text: String, carMode: Boolean, wakePhrases: List<String> = emptyList()): HandsFreeCommand? {
        val stripped = TextCommand.stripWake(text, wakePhrases, loose = true)
        val toks = tokens(stripped)
        val w = toks.map { it.w }
        var i = 0
        while (true) {
            val h = HEAD.firstOrNull { at(w, i, it) } ?: break
            i += h.size
        }
        var end = w.size
        while (end > i) {
            val t = TAIL.firstOrNull { it.size <= end - i && at(w, end - it.size, it) } ?: break
            end -= t.size
        }
        if (i >= end) return null
        val core = w.subList(i, end)
        // A phrase may end in a tail word of its own ("that's all for now").
        val full = w.subList(i, w.size)
        fun whole(l: List<List<String>>) = l.any { it == core || it == full }
        if (whole(CAR_OFF)) return HandsFreeCommand.CarModeOff
        if (whole(CAR_ON)) return HandsFreeCommand.CarModeOn
        if (whole(REPEAT)) return HandsFreeCommand.Repeat
        if (whole(STOP_LISTENING)) return HandsFreeCommand.StopListening
        if (!carMode) return null
        if (whole(READ_MESSAGES)) return HandsFreeCommand.ReadMessages
        if (whole(WHATS_NEXT)) return HandsFreeCommand.WhatsNext
        (MUSIC[core] ?: MUSIC[full])?.let { return HandsFreeCommand.Music(it) }
        navigate(core) { a, b -> stripped.substring(toks[i + a].start, toks[i + b - 1].end) }?.let { return it }
        // Reply: its words as said (the original text after the lead).
        REPLY.firstOrNull { at(core, 0, it) && core.size > it.size }?.let {
            val body = ReplyWords.body(stripped.substring(toks[i].start))
            return if (body.isEmpty()) null else HandsFreeCommand.Reply(body)
        }
        call(core, stripped, toks, i)?.let { return it }
        return null
    }

    /** "navigate home", "take me to work", "directions to the nearest gas station", "amène-moi à la maison". */
    private fun navigate(core: List<String>, said: (Int, Int) -> String): HandsFreeCommand.Navigate? {
        val verb = NAV.firstOrNull { at(core, 0, it) }?.size ?: 0
        var from = verb
        if (from < core.size && core[from] in setOf("to", "a", "vers", "au", "pour")) from++
        val rest = core.subList(from, core.size)
        // Without a verb, only "the nearest …" or "le … le plus proche" (the whole utterance, in car mode).
        nearest(rest)?.let { (a, b) -> return HandsFreeCommand.Navigate(PlaceWord.NEAREST, said(from + a, from + b)) }
        if (verb == 0 || rest.isEmpty()) return null
        val all = core.subList(verb, core.size)
        if (HOME.any { it == rest || it == all }) return HandsFreeCommand.Navigate(PlaceWord.HOME, "home")
        if (WORK.any { it == rest || it == all }) return HandsFreeCommand.Navigate(PlaceWord.WORK, "work")
        return HandsFreeCommand.Navigate(PlaceWord.OTHER, said(from, core.size))
    }

    /** Where the kind of place is in [w] (from, to), for "the nearest gas station" or "le poste d'essence le plus proche". */
    private fun nearest(w: List<String>): Pair<Int, Int>? {
        var a = 0
        if (w.getOrNull(a) == "the") a++
        if (w.getOrNull(a) in setOf("nearest", "closest") && w.size > a + 1) return (a + 1) to w.size
        val n = w.size
        if (n >= 4 && w[n - 2] == "plus" && w[n - 1] in setOf("proche", "pres") && w[n - 3] in setOf("le", "la", "les") && w[0] in setOf("le", "la", "les", "l")) {
            return if (n - 3 > 1) 1 to n - 3 else null
        }
        return null
    }

    private fun call(core: List<String>, stripped: String, toks: List<Tok>, head: Int): HandsFreeCommand.Call? {
        val verb = CALL.firstOrNull { at(core, 0, it) } ?: return null
        var who = core.drop(verb.size)
        if (who.isEmpty() || who.first() in NOT_WHO) return null
        // "on her cell", "at work", "sur son cell", "au bureau", "à la maison" at the end.
        var label: String? = null
        for (k in 1 until who.size) {
            if (who[k] !in LABEL_LEAD) continue
            var j = k + 1
            while (j < who.size && who[j] in LABEL_FILLER) j++
            val l = who.getOrNull(j)?.let { LABEL_WORD[it] } ?: continue
            var e = j + 1
            while (e < who.size && who[e] in LABEL_TAIL) e++
            if (e == who.size) { label = l; who = who.take(k); break }
        }
        if (who.isEmpty()) return null
        // The name as said (its own case and accents), from the original text.
        val name = stripped.substring(toks[head + verb.size].start, toks[head + verb.size + who.size - 1].end).trim()
        return HandsFreeCommand.Call(name, label)
    }
}
