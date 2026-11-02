// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.music

import app.heyrobot.core.HelperRouter
import app.heyrobot.core.TextCommand
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.output.MusicAction

// Pure. The everyday set's music phrases (docs/plans/everyday-set-plan.md §3.2, D2): "pause the music", "next song",
// "play Radiohead", « fais jouer Radiohead », heard by code, never the model. The panel asks it after Files, Lists and
// Robot to Robot's LinkRouter and before hands-free (§2.1, D1), in and out of car mode: car mode's music phrases moved here
// from HandsFreeRouter (which keeps them only while Music by voice is off), and every one of them is still heard.
//
// Anchored like HandsFreeRouter: the whole utterance, with only "hey robot", "please", « peux-tu » and the like in front
// and "please", "now", « merci » after, so "I remember that song" and "my music is too loud" stay questions.
//
// - The keys ("pause the music", "next song", « coupe la musique ») count always. The bare words count only when they
//   can't surprise you: "pause" when something was playing as you started asking ([MusicContext.playing], §3.3), "play"
//   and « reprends » when Robot itself paused the music in the last 10 minutes ([MusicContext.robotPaused]); both in car
//   mode, as in 0.8.
// - "Play X" by name: before you've picked a music app, only with a music word (song, album, playlist, a genre, "by …"),
//   so "play chess" never brings up the picker; once one is picked, any "play X" that isn't a game, a sport, an
//   instrument, a video, a message, a note, the news or the weather (§3.6, E10). « mets » (and "put on") lead only with a
//   music word, your music app picked or not, because « mets » also sets timers and alarms, lays the table and salts the soup.
// - "What's playing", « c'est quoi cette toune »: always; answered only through your music app's media service (§3.4).
//
// - Whatever word it holds, it isn't "play this now" when it's a negation, a change of volume, an add to a list, for later, or
//   followed by something else to do ([notNow], E10): those words would go to another app as a search, and music would start.
//   When Robot isn't sure it's music, it doesn't start music: the phrase goes on to the rest of the panel.
//
// The request for your music app is built here, by code (§3.4, D4: PlayRequest), never by the model. A song's or an
// artist's name is kept as said, and never logged.

/** What the panel knows as you ask (§3.2, §3.3). */
data class MusicContext(
    /** Media was playing as you started asking (voice TurnFocus's note, taken before Robot's question paused it). */
    val playing: Boolean = false,
    /** Robot itself paused the music in the last [RESUME_MS]. */
    val robotPaused: Boolean = false,
    /** You picked your music app (Settings › Actions › Music, or the card the first time). */
    val appPicked: Boolean = false,
    /** Car mode is on: the bare words count as in 0.8. */
    val carMode: Boolean = false,
    /** Your wake phrases, stripped from the front as hands-free does. */
    val wakePhrases: List<String> = emptyList(),
) {
    companion object {
        /** Bare "play" resumes what Robot paused within this long (EST, §3.2). */
        const val RESUME_MS = 10 * 60_000L
    }
}

/** What [MusicRouter] heard. */
sealed interface MusicCommand {
    /** The language the phrase was heard in, for the reply; null when the words are the same in both ("pause"). */
    val lang: Lang?

    /** A media key for whatever plays now (AudioManager.dispatchMediaKeyEvent). */
    data class Key(val action: MusicAction, override val lang: Lang?) : MusicCommand

    /** Play by name through your music app (§3.4). */
    data class ByName(val request: PlayRequest, override val lang: Lang?) : MusicCommand

    /** "What's playing": your music app's media service says, or Robot says it can't see. */
    data class NowPlaying(override val lang: Lang?) : MusicCommand
}

/** The kind of search your music app is asked for (MediaStore.EXTRA_MEDIA_FOCUS, §3.4). */
enum class PlayFocus(val mime: String) {
    /** Unstructured: the app reads the query as it likes. */
    ANY("vnd.android.cursor.item/*"),
    AUDIO("vnd.android.cursor.item/audio"),
    ALBUM("vnd.android.cursor.item/album"),
    ARTIST("vnd.android.cursor.item/artist"),
    GENRE("vnd.android.cursor.item/genre"),
}

/**
 * Play from search, as code builds it (§3.4, D4): [query] as said (empty: "play something"), the [focus] and the
 * structured fields. Strings only: door 1 hands them to TransportControls.playFromSearch, door 2 (C7) to the
 * play-from-search screen. "Playlist" stays in the query: the playlist extra and focus are deprecated in the SDK.
 */
data class PlayRequest(
    val query: String,
    val focus: PlayFocus = PlayFocus.ANY,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    /** What you asked for, as said, for the line on screen ("♪ Radiohead in your music app"). */
    val said: String = query,
) {
    /** The extras, by MediaStore's names (EXTRA_MEDIA_FOCUS, _TITLE, _ARTIST, _ALBUM, _GENRE), strings only. */
    fun extras(): Map<String, String> = buildMap {
        put(EXTRA_FOCUS, focus.mime)
        title?.let { put(EXTRA_TITLE, it) }
        artist?.let { put(EXTRA_ARTIST, it) }
        album?.let { put(EXTRA_ALBUM, it) }
        genre?.let { put(EXTRA_GENRE, it) }
    }

    companion object {
        // MediaStore's own names (VERIFIED in android-36's MediaStore.java), so core stays free of Android.
        const val EXTRA_FOCUS = "android.intent.extra.focus" // l10n: not text (an extra's name)
        const val EXTRA_TITLE = "android.intent.extra.title" // l10n: not text (an extra's name)
        const val EXTRA_ARTIST = "android.intent.extra.artist" // l10n: not text (an extra's name)
        const val EXTRA_ALBUM = "android.intent.extra.album" // l10n: not text (an extra's name)
        const val EXTRA_GENRE = "android.intent.extra.genre" // l10n: not text (an extra's name)

        /** What you ask for by name is at most this many words; longer is a question, not a song. */
        const val MAX_WORDS = 12
        /** The query, and each field, at most this long. */
        const val MAX_CHARS = 120
    }
}

object MusicRouter {
    /** A word: plain (no case or accents) and where it is in what was said. Apostrophes and hyphens split ("l'album", "mets-moi"). */
    private class Tok(val w: String, val start: Int, val end: Int)

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private fun tokens(text: String): List<Tok> = WORD.findAll(text).map { Tok(HelperRouter.plain(it.value), it.range.first, it.range.last + 1) }.toList()

    private fun words(p: String): List<String> = tokens(p).map { it.w }

    private fun list(vararg p: String): List<List<String>> = p.map(::words).sortedByDescending { it.size }

    private val HEAD = list("hey robot", "hi robot", "ok robot", "okay robot", "hello robot", "he robot", "dis robot", "robot", "please", "can you", "could you",
        "would you", "will you", "can you please", "could you please", "ok", "okay", "so", "um", "uh", "euh", "hey", "peux tu", "pourrais tu", "est ce que tu peux",
        "s il te plait", "s il vous plait", "sil te plait", "stp", "svp", "dac", "alors", "bon", "coudonc", "eille", "heille", "tu peux", "tu peux tu", "tu pourrais",
        "est ce que tu pourrais", "veux tu", "voudrais tu")
    private val TAIL = list("please", "now", "thanks", "thank you", "for me", "merci", "s il te plait", "s il vous plait", "sil te plait", "stp", "svp", "maintenant",
        "robot", "pour moi", "pour nous")

    private fun at(w: List<String>, i: Int, p: List<String>): Boolean = i + p.size <= w.size && p.indices.all { w[i + it] == p[it] }

    // ------------------------------------------------------------------ the keys

    private fun keys(lang: Lang?, a: MusicAction, vararg p: String): List<Pair<List<String>, Pair<MusicAction, Lang?>>> = p.map { words(it) to (a to lang) }

    /** Always heard (§3.2), car mode's own (HandsFreeRouter.MUSIC) among them. */
    private val KEYS: Map<List<String>, Pair<MusicAction, Lang?>> = (
        keys(Lang.EN, MusicAction.PAUSE, "pause the music", "pause music", "stop the music", "stop music", "pause my music", "pause the song", "pause the track") +
            keys(Lang.FR, MusicAction.PAUSE, "mets la musique sur pause", "mets la musique en pause", "pause la musique", "arrête la musique", "coupe la musique",
                "coupe la toune", "arrête la toune", "mets la toune sur pause", "mets la toune en pause", "ferme la musique", "ferme la toune", "éteins la musique",
                "stop la musique", "stop la toune", "coupe la chanson", "arrête la chanson", "mets la chanson sur pause", "mets la chanson en pause", "pause la toune") +
            keys(Lang.EN, MusicAction.PLAY, "play music", "play some music", "play my music", "play the music", "resume music", "resume the music",
                "resume my music", "start the music", "continue the music", "unpause", "unpause the music") +
            keys(Lang.FR, MusicAction.PLAY, "mets de la musique", "mets la musique", "joue de la musique", "fais jouer de la musique", "fais jouer la musique",
                "reprends la musique", "remets la musique", "partir la musique", "part la musique", "repars la musique", "repars la toune", "continue la musique",
                "continue la toune", "mets moi de la musique", "mets nous de la musique", "fais moi jouer de la musique", "fais nous jouer de la musique",
                "joue moi de la musique", "mettre de la musique", "me mettre de la musique", "nous mettre de la musique", "faire jouer de la musique",
                "me faire jouer de la musique", "jouer de la musique", "je veux écouter de la musique", "j'aimerais écouter de la musique", "on écoute de la musique") +
            keys(Lang.EN, MusicAction.NEXT, "next song", "next track", "skip this song", "skip song", "skip the song", "skip this track", "skip track",
                "play the next song", "play next song", "go to the next song") +
            keys(Lang.FR, MusicAction.NEXT, "chanson suivante", "prochaine chanson", "passe la chanson", "toune suivante", "prochaine toune", "passe la toune",
                "passe à la prochaine chanson", "passe à la chanson suivante", "passe à la prochaine toune", "mets la prochaine chanson", "mets la chanson suivante",
                "joue la prochaine chanson", "joue la chanson suivante", "change de toune", "change de chanson", "change la toune", "change la chanson", "saute cette toune",
                "saute cette chanson", "saute la toune", "saute la chanson", "passe cette toune", "passe cette chanson", "passe à une autre toune", "passe à l'autre toune",
                "passe à la toune suivante", "mets la prochaine toune", "mets la toune suivante", "mets moi la prochaine toune", "la prochaine toune", "la toune suivante",
                "la prochaine chanson", "la chanson suivante", "joue la prochaine toune", "fais jouer la prochaine toune", "fais jouer la toune suivante",
                "fais jouer la prochaine chanson", "fais jouer la chanson suivante", "skip la toune", "skip cette toune", "skip la chanson", "skip cette chanson",
                "skippe la toune", "skippe cette toune") +
            keys(Lang.EN, MusicAction.PREVIOUS, "previous song", "previous track", "last song", "go back a song", "go back one song", "play the previous song",
                "play previous song") +
            keys(Lang.FR, MusicAction.PREVIOUS, "chanson précédente", "toune précédente", "la chanson d'avant", "la chanson précédente", "remets la chanson précédente",
                "joue la chanson précédente", "reviens à la chanson précédente", "la toune d'avant", "la toune précédente", "remets la toune d'avant", "remets la chanson d'avant",
                "remets la toune précédente", "reviens à la toune d'avant", "reviens à la chanson d'avant", "reviens à la toune précédente", "retourne à la toune d'avant",
                "retourne à la chanson d'avant", "retourne à la chanson précédente", "joue la toune d'avant", "fais jouer la toune d'avant", "fais jouer la chanson d'avant",
                "fais jouer la chanson précédente", "mets la toune d'avant", "mets la chanson d'avant", "recule d'une toune", "recule d'une chanson")
        ).toMap()

    /** The bare words: only when they can't surprise you (§3.2). */
    private val BARE: Map<List<String>, Pair<MusicAction, Lang?>> = (
        keys(null, MusicAction.PAUSE, "pause") + keys(null, MusicAction.PLAY, "play") + keys(Lang.EN, MusicAction.PLAY, "resume") +
            keys(Lang.FR, MusicAction.PLAY, "joue", "reprends") + keys(Lang.FR, MusicAction.PAUSE, "mets sur pause", "mets ça sur pause", "mets ça en pause")
        ).toMap()

    private val NOW: Map<List<String>, Lang> = (
        list("what's playing", "what is playing", "what's this song", "what is this song", "what song is this", "what song is playing", "what song is that",
            "what's that song", "what is that song", "who sings this", "who sings this song", "who's singing", "who is singing", "what am i listening to",
            "what's the name of this song", "what is the name of this song").map { it to Lang.EN } +
            list("c'est quoi cette toune", "c'est quoi cette chanson", "c'est quoi la toune", "c'est quoi la chanson", "quelle est cette chanson",
                "quelle chanson joue", "qui chante ça", "qui chante", "qu'est-ce qui joue", "c'est quoi qui joue", "c'est quoi le nom de cette chanson",
                "c'est quoi le nom de cette toune", "c'est quoi la toune qui joue", "c'est quoi la chanson qui joue", "c'est quoi ça cette toune", "c'est qui qui chante",
                "c'est qui qui chante ça", "c'est qui qui chante cette toune", "c'est qui qui chante cette chanson", "qui chante cette toune", "qui chante cette chanson",
                "comment s'appelle cette toune", "comment s'appelle cette chanson", "comment ça s'appelle cette toune", "comment ça s'appelle cette chanson",
                "ça s'appelle comment cette toune", "ça s'appelle comment cette chanson", "qu'est-ce que j'écoute", "c'est quoi que j'écoute", "qu'est-ce que ça joue",
                "c'est quoi le titre de cette toune", "c'est quoi le titre de cette chanson").map { it to Lang.FR }
        ).toMap()

    // ------------------------------------------------------------------ play by name

    private enum class Lead { PLAY, MUSIC_WORD_ONLY }

    /** "play" and « fais jouer » lead like play; "put on" and « mets » only with a music word or « du » before a name. */
    private val LEADS: List<Triple<List<String>, Lang, Lead>> = (
        list("play").map { Triple(it, Lang.EN, Lead.PLAY) } + list("put on").map { Triple(it, Lang.EN, Lead.MUSIC_WORD_ONLY) } +
            list("fais jouer", "fais moi jouer", "fais nous jouer", "joue", "jouer", "me jouer", "nous jouer", "faire jouer", "me faire jouer", "nous faire jouer")
                .map { Triple(it, Lang.FR, Lead.PLAY) } +
            list("mets", "mettre", "me mettre", "nous mettre", "je veux écouter", "je voudrais écouter", "j'aimerais écouter", "fais moi écouter", "fais nous écouter",
                "on écoute").map { Triple(it, Lang.FR, Lead.MUSIC_WORD_ONLY) }
        ).sortedByDescending { it.first.size }

    private val MUSIC_WORDS = setOf("music", "song", "songs", "album", "albums", "playlist", "playlists", "track", "tracks", "artist", "band",
        "musique", "chanson", "chansons", "toune", "tounes", "piste", "pistes", "artiste")
    private val LISTE_DE_LECTURE = words("liste de lecture")

    /** A genre from a short list (§3.2): music words of their own. As words, without accents. */
    private val GENRES = list("jazz", "rock", "country", "classical", "classique", "blues", "pop", "hip hop", "rap", "metal", "heavy metal", "folk", "reggae",
        "soul", "funk", "disco", "techno", "house", "electronic", "electro", "électronique", "edm", "punk", "r&b", "rnb", "gospel", "indie", "lofi", "lo fi",
        "ambient", "latin", "latino", "k pop", "kpop", "rock and roll", "trad", "opera", "bluegrass", "grunge", "swing", "salsa")

    /** Genres that are also a game or a word of their own: a genre only with "music" ("play house music", not "play house"). */
    private val GENRES_WITH_MUSIC = list("house", "swing", "latin", "trad")

    /** Never music, anywhere in what follows the lead, unless it names a song, album, track or playlist (§3.6). */
    private val NOT_MUSIC = setOf("game", "games", "jeu", "jeux", "trivia", "quiz", "questions", "voicemail", "voicemails", "messagerie", "message",
        "messages", "recording", "recordings", "enregistrement", "enregistrements", "transcript", "transcription", "video", "videos", "youtube", "movie",
        "movies", "film", "films", "episode", "episodes", "podcast", "podcasts", "balado", "balados", "audiobook", "audiobooks", "alarm", "alarme",
        "ringtone", "sonnerie", "minuteur", "timer", "rappel", "notification", "notifications", "pretend", "role", "tour", "tours", "echecs", "trick",
        "tricks", "joke", "jokes", "prank", "blague", "blagues", "file", "files", "fichier", "fichiers", "attachment", "document", "documents", "pdf",
        "text", "texts", "texto", "textos", "sms", "email", "emails", "courriel", "courriels")

    /** Never music as the first word after the lead (§3.6): idioms, « jouer à » (a game or a sport), the next or last song. */
    private val NOT_FIRST_EN = setOf("with", "along", "dead", "nice", "fair", "dumb", "around", "outside", "next", "previous")
    private val NOT_FIRST_FR = setOf("a", "au", "aux", "avec", "dehors", "cartes", "suivante", "precedente", "prochaine", "bien", "dur", "fort", "mal", "serre", "vite")

    /**
     * First, after any "my", "the" or « ma »: games and sports ("play chess", "play tennis tonight"), idioms, and what's yours
     * on this phone rather than a song ("play my notes", "play the news"), unless a song is named ("play Catch by The Cure").
     */
    private val NOT_FIRST_UNLESS_SONG = setOf("chess", "checkers", "cards", "poker", "tennis", "hockey", "golf", "soccer", "football", "basketball",
        "baseball", "volleyball", "badminton", "sports", "tag", "catch", "hide", "ball", "back", "house",
        "cricket", "rugby", "lacrosse", "softball", "pickleball", "ballon", "minecraft", "fortnite", "roblox", "tetris", "wordle", "sudoku", "scrabble", "monopoly", "bingo",
        "roulette", "dominoes", "dominos", "domino", "solitaire", "hangman", "charades", "uno", "darts", "pool", "billiards", "snooker", "partie", "parties",
        "favourites", "favorites", "favoris", "hooky", "hookey", "fetch", "hardball", "possum", "truant",
        "news", "nouvelles", "weather", "meteo", "note", "notes", "memo", "memos", "reminder", "reminders", "rappels", "vocale")

    /** Whole, after any "my" or "the": "play the victim", "play my voice note", "play my last call". The words alone can be a song ("play The Fool on the Hill"). */
    private val NOT_WHOLE = list("field", "victim", "fool", "hero", "lottery", "market", "voice", "voice note", "voice notes", "voice memo", "voice memos", "voice message",
        "voice messages", "call", "calls", "last call", "latest news", "appel", "appels", "dernier appel")

    /** Starts: "play devil's advocate", "play by ear", "play hard to get". */
    private val NOT_START = list("devil's advocate", "by ear", "hard to get", "second fiddle")

    /** "play what she sent me", « joue ce que maman a dit »: someone's words, which would go to your music app. */
    private val THEIR_WORDS_START = setOf("what", "ce")
    private val THEIR_WORDS = setOf("said", "sent", "told", "wrote", "texted", "left", "dit", "envoye", "ecrit", "laisse")

    /** Idioms, whole: "play it cool", "play it again", "play it by ear". */
    private val NOT_EXACT = list("by", "it", "it cool", "it safe", "it again", "that again", "this again", "ca encore", "ca de nouveau", "it by ear", "it louder", "it back", "nothing", "rien", "this", "that", "ca", "ce", "cette")

    /** Instruments: "play the guitar", « joue du piano » (§3.6), unless a song is named ("play Piano Man by Billy Joel"). */
    private val INSTRUMENTS = setOf("guitar", "guitare", "piano", "drums", "drum", "batterie", "violin", "violon", "ukulele", "flute", "saxophone", "sax",
        "cello", "violoncelle", "harmonica", "trumpet", "trompette", "keyboard", "clavier", "bass", "basse", "accordion", "accordeon")

    /** « mets » with these is never music: « mets la table », « mets le téléphone en silencieux », « mets ça dans mes notes ». */
    private val METS_NOT = setOf("table", "telephone", "cellulaire", "cell", "ca", "silencieux", "notes", "note", "lumiere", "lumieres", "volume", "son",
        "sous", "pause", "en")

    /** First after « mets » and its articles, never music even with a music word later: « mets-moi un rappel pour la toune », « mets-moi un cadran ». */
    private val METS_NEVER_FIRST = setOf("rappel", "rappels", "alarme", "alarmes", "minuteur", "minuteurs", "minuterie", "minuteries", "timer", "timers", "cadran",
        "reveil", "note", "notes", "message", "messages", "reminder", "reminders", "alarm", "alarms")

    // ------------------------------------------------------------------ not a request to play now (E10)

    /** "play it louder", « mets la musique moins fort »: the volume, not a search for those words. */
    private val VOLUME = list("louder", "softer", "quieter", "volume", "plus fort", "moins fort", "trop fort", "plus bas", "moins bas", "plus haut", "moins haut")

    /** What a song goes into, after [TO_A_LIST] (« dans mes favoris », "to my favourites"): an add, which §3.6 leaves to the model. */
    private val A_LIST = setOf("liste", "list", "playlist", "playlists", "favoris", "favorites", "favourites", "bibliotheque", "library", "queue", "file", "collection")
    private val TO_A_LIST = setOf("dans", "aux", "au", "a", "to", "into")
    private val OF_A_LIST = setOf("ma", "mes", "mon", "la", "les", "une", "my", "the")

    /** For later, anywhere but first ("play Tomorrow" is a song; "play jazz tomorrow" isn't now). */
    private val LATER = list("tomorrow", "tonight", "later", "this morning", "this afternoon", "this evening", "in the morning", "in the afternoon", "in the evening",
        "at night", "in a bit", "in a while", "in a sec", "in a moment", "next week", "every morning", "every night", "every evening", "every day", "when i", "when we",
        "once i", "after this", "after that", "demain", "tantôt", "ce matin", "ce soir", "cet après-midi", "à soir", "à matin", "plus tard", "tout à l'heure",
        "dans un instant", "dans un moment", "la semaine prochaine", "chaque matin", "chaque soir", "chaque jour", "tous les matins", "tous les soirs", "tous les jours",
        "quand je", "quand j", "quand on", "quand tu", "après ça")

    /** Only after nothing but a music word ("play jazz after dinner"; "Wake Me Up When September Ends" is a song). */
    private val THEN_WORDS = setOf("when", "until", "after", "before", "once", "quand", "lorsque", "apres", "avant", "jusqu")
    private val WEEKDAYS_EN = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val WEEKDAYS_FR = setOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val NUMBERS = setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "twenty", "thirty", "forty", "fifty",
        "sixty", "un", "une", "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix", "onze", "douze", "quinze", "vingt", "trente", "quarante", "cinquante", "soixante")
    private val SOME = setOf("a", "an", "few", "couple", "half", "quelques", "demi")
    private val UNITS = setOf("second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours", "hr", "hrs", "day", "days", "week", "weeks",
        "seconde", "secondes", "heure", "heures", "h", "jour", "jours", "semaine", "semaines")
    private val CLOCK_AT = setOf("noon", "midnight", "midi", "minuit")
    private val DIGITS = Regex("\\d+(h\\d*)?")

    /** Something else follows ("play music and call mom"): that part would be lost, and it's what you asked for. */
    private val AND_THEN = setOf("and", "then", "et", "puis", "pis", "ensuite")
    private val ALSO_DO = setOf("call", "phone", "dial", "text", "message", "email", "send", "tell", "set", "remind", "add", "open", "read", "write", "navigate", "turn", "lock",
        "answer", "reply", "start", "stop", "play", "put", "appelle", "appeler", "telephone", "compose", "texte", "texter", "ecris", "ecrire", "envoie", "envoyer", "dis",
        "mets", "ajoute", "ouvre", "lis", "rappelle", "reponds", "baisse", "monte", "eteins", "allume", "arrete", "ferme", "joue", "fais")
    private val CALL_OR_TEXT = setOf("call", "text", "appelle", "texte")

    /** Verbs Québec French follows with « donc »: « mets-moi donc du jazz », « coupe donc la musique », « fais donc jouer Radiohead ». */
    private val DONC_AFTER = setOf("mets", "fais", "joue", "coupe", "arrete", "ferme", "passe", "change", "saute", "reprends", "remets", "repars", "continue")

    private val ARTICLES_EN = setOf("some", "the", "my", "me")
    private val ARTICLES_FR = setOf("du", "de", "la", "le", "les", "l", "des", "d", "ma", "mon", "mes", "moi", "un", "une")
    private val PRONOUNS = setOf("me", "you", "us", "him", "her", "them", "it", "myself", "yourself")
    private val SOMETHING = list("something", "anything", "whatever", "something good", "a song", "any song", "a random song", "random songs", "random music",
        "quelque chose", "n'importe quoi", "n'importe quelle chanson", "une chanson", "une toune", "quelque chose de bon", "une bonne toune", "une bonne chanson",
        "de la bonne musique")

    /** What [text] asks of the music, or null (it goes on to hands-free and the model as before). */
    fun route(text: String, ctx: MusicContext = MusicContext()): MusicCommand? {
        val stripped = TextCommand.stripWake(text, ctx.wakePhrases, loose = true)
        val said = tokens(stripped)
        val heard = said.map { it.w }
        var i = 0
        while (true) {
            val h = HEAD.firstOrNull { at(heard, i, it) } ?: break
            i += h.size
        }
        val toks = withoutDonc(said, i)
        val w = toks.map { it.w }
        var end = w.size
        while (end > i) {
            val t = TAIL.firstOrNull { it.size <= end - i && at(w, end - it.size, it) } ?: break
            end -= t.size
        }
        if (i >= end) return null
        val core = w.subList(i, end)
        // A phrase may end in a tail word of its own.
        val full = w.subList(i, w.size)
        // « coupe la musique là », « c'est quoi cette toune-là »: the « là » of everyday speech, for the fixed phrases only (a name keeps its last word).
        val noLa = if (core.size > 1 && core.last() == "la") core.dropLast(1) else core
        (KEYS[core] ?: KEYS[full] ?: KEYS[noLa])?.let { (a, l) -> return MusicCommand.Key(a, l) }
        BARE[core]?.let { (a, l) ->
            val ok = ctx.carMode || when (a) {
                MusicAction.PAUSE -> ctx.playing
                MusicAction.PLAY -> ctx.robotPaused
                else -> false
            }
            return if (ok) MusicCommand.Key(a, l) else null
        }
        (NOW[core] ?: NOW[full] ?: NOW[noLa])?.let { return MusicCommand.NowPlaying(it) }
        return byName(stripped, toks.subList(i, end), ctx)
    }

    /** [t] without the « donc » right after its verb at [i], or after the verb's « moi » or « nous ». */
    private fun withoutDonc(t: List<Tok>, i: Int): List<Tok> {
        if (t.getOrNull(i)?.w !in DONC_AFTER) return t
        val at = if (t.getOrNull(i + 1)?.w in setOf("moi", "nous")) i + 2 else i + 1
        return if (t.getOrNull(at)?.w == "donc") t.filterIndexed { k, _ -> k != at } else t
    }

    /** "play Radiohead", "play the album Kid A", « mets ma liste de lecture d'entraînement »: [t] is the utterance between its head and tail. */
    private fun byName(said: String, t: List<Tok>, ctx: MusicContext): MusicCommand.ByName? {
        val w = t.map { it.w }
        val (lead, lang, kind) = LEADS.firstOrNull { at(w, 0, it.first) } ?: return null
        var from = lead.size
        // "play me some jazz", « joue-moi du jazz », « mets-moi du jazz ».
        while (from < w.size && (w[from] in setOf("me", "moi") || (w[from] == "nous" && lang == Lang.FR && from + 1 < w.size) ||
                (w[from] == "us" && lang == Lang.EN && from + 1 < w.size))) from++
        if (from >= w.size || w.size - from > PlayRequest.MAX_WORDS) return null
        val r = w.subList(from, w.size)
        if (kind == Lead.MUSIC_WORD_ONLY && r.firstOrNull { it !in ARTICLES_FR && it !in ARTICLES_EN && it != "a" && it != "an" } in METS_NEVER_FIRST) return null
        val musicNoun = r.any { it in MUSIC_WORDS } || contains(r, LISTE_DE_LECTURE)
        val by = lang == Lang.EN && r.indices.any { it > 0 && it < r.size - 1 && r[it] == "by" && r[it + 1] !in PRONOUNS }
        val genre = genreOf(r, lang)
        val musicWord = musicNoun || by || genre != null
        if (notNow(r, lang, by)) return null
        // §3.6: games, messages, videos, sports, instruments, idioms. A named song, album or playlist is music still.
        if (!musicNoun && r.any { it in NOT_MUSIC }) return null
        if (NOT_EXACT.any { it == r } || r.first() in (if (lang == Lang.EN) NOT_FIRST_EN else NOT_FIRST_FR)) return null
        val noun = r.dropWhile { it in ARTICLES_EN || it in ARTICLES_FR || it == "a" || it == "an" || it == "this" || it == "that" }
        if (!musicNoun && !by && (r.any { it in INSTRUMENTS } || r.first() in NOT_FIRST_UNLESS_SONG || noun.firstOrNull() in NOT_FIRST_UNLESS_SONG ||
                NOT_WHOLE.any { it == noun } || NOT_START.any { at(r, 0, it) } || (r.first() in THEIR_WORDS_START && r.any { it in THEIR_WORDS }))) return null
        when (kind) {
            Lead.PLAY -> if (!musicWord && !ctx.appPicked) return null
            Lead.MUSIC_WORD_ONLY -> {
                if (!musicNoun && r.any { it in METS_NOT }) return null
                // Not « mets du Radiohead », or « mets du beurre » would search for butter. « fais jouer du Radiohead » still works.
                if (!musicWord) return null
            }
        }
        val src = { a: Int, b: Int -> clip(said.substring(t[from + a].start, t[from + b - 1].end)) }
        return MusicCommand.ByName(request(r, src, lang, genre), lang)
    }

    /** [r], the words after the lead, say something other than "play this now", whatever music word they hold. A title before its "by" says nothing. */
    private fun notNow(r: List<String>, lang: Lang, by: Boolean): Boolean {
        if (negated(r, lang)) return true
        val from = if (by) r.lastIndexOf("by") else 0
        return (from until r.size).any { i -> VOLUME.any { at(r, i, it) } || toAList(r, i) || later(r, i, lang) || alsoDo(r, i) }
    }

    /** « mets pas de musique », "play no music"; "play No Scrubs" is a song. */
    private fun negated(r: List<String>, lang: Lang): Boolean {
        val rest = r.drop(1)
        return if (lang == Lang.FR) r[0] in setOf("pas", "aucun", "aucune") || (r[0] == "plus" && rest.firstOrNull() in setOf("de", "d"))
        else r[0] in setOf("no", "not") && rest.isNotEmpty() && (rest[0] == "more" || rest.any { it in MUSIC_WORDS } || genreOf(rest, lang) != null)
    }

    /** « dans ma liste de lecture », « aux favoris », "to my favorites", "this song in my playlist"; "the songs in my library" is music. */
    private fun toAList(r: List<String>, i: Int): Boolean {
        val into = r[i] in TO_A_LIST || (r[i] == "in" && r.subList(0, i).any { it == "this" || it == "that" }) ||
            (r[i] == "sur" && r.subList(0, i).any { it == "cette" || it == "ce" || it == "cet" || it == "ca" })
        if (!into) return false
        return r.getOrNull(i + 1) in A_LIST || (r.getOrNull(i + 1) in OF_A_LIST && r.getOrNull(i + 2) in A_LIST)
    }

    private fun isNumber(w: String?): Boolean = w != null && (DIGITS.matches(w) || w in NUMBERS)

    private fun later(r: List<String>, i: Int, lang: Lang): Boolean {
        val w = r[i]
        val next = r.getOrNull(i + 1)
        return when {
            // "at 7", "at seven", "at noon", « à 7 h », « à midi », « vers 8 h ».
            (w == "at" || w == "around" || w == "vers" || (w == "a" && lang == Lang.FR)) && (isNumber(next) || next in CLOCK_AT) -> true
            // "in ten minutes", "in a few minutes", « dans une heure », « dans quelques minutes ».
            (w == "in" || w == "dans") && (isNumber(next) || next in SOME) && r.subList(i + 1, minOf(i + 4, r.size)).any { it in UNITS } -> true
            // « 7h », anywhere after the first word.
            i > 0 && w.first().isDigit() && w.contains('h') && DIGITS.matches(w) -> true
            // "on Monday", "next Friday"; « lundi » after a name.
            (w == "on" || w == "next" || w == "this") && next in WEEKDAYS_EN -> true
            i > 0 && w in WEEKDAYS_FR -> true
            i > 0 && LATER.any { at(r, i, it) } -> true
            i > 0 && w in THEN_WORDS && (r.subList(0, i).all { it in MUSIC_WORDS || it in ARTICLES_EN || it in ARTICLES_FR } || genreOf(r.subList(0, i), lang) != null) -> true
            else -> false
        }
    }

    /** "and call mom", « pis appelle maman », "play music call mom" (the comma never reaches here). */
    private fun alsoDo(r: List<String>, i: Int): Boolean =
        (r[i] in AND_THEN && r.getOrNull(i + 1) in ALSO_DO) || (i > 0 && r[i] in CALL_OR_TEXT && r[i - 1] in MUSIC_WORDS)

    /** The request for [r] (the words after the lead), [src] giving the original words of a range. */
    private fun request(r: List<String>, src: (Int, Int) -> String, lang: Lang, genre: Pair<Int, Int>?): PlayRequest {
        val asSaid = src(0, r.size)
        if (SOMETHING.any { it == r }) return PlayRequest("", said = asSaid)
        if (lang == Lang.EN) {
            var a = 0
            while (a < r.size - 1 && r[a] in ARTICLES_EN) a++
            // "the album Kid A [by Radiohead]", "the song Creep [by Radiohead]".
            if (r[a] == "album" || r[a] == "song" || r[a] == "track") {
                val (name, artist) = split(r, a + 1, "by", src)
                if (name != null) {
                    return if (r[a] == "album") PlayRequest(asSaid, PlayFocus.ALBUM, album = name, artist = artist)
                    else PlayRequest(asSaid, PlayFocus.AUDIO, title = name, artist = artist)
                }
            }
            // "music by Radiohead", "songs by Radiohead", "something by Radiohead".
            if (r[a] in setOf("music", "songs", "something", "anything", "stuff") && r.getOrNull(a + 1) == "by" && a + 2 < r.size)
                return src(a + 2, r.size).let { PlayRequest(it, PlayFocus.ARTIST, artist = it, said = asSaid) }
            // "my workout playlist", "the playlist Chill": the playlist's words stay in the query (D4).
            if ("playlist" in r || "playlists" in r) return PlayRequest(src(a, r.size), said = asSaid)
            genre?.let { (g0, g1) -> return src(g0, g1).let { PlayRequest(it, PlayFocus.GENRE, genre = it, said = asSaid) } }
            // "OK Computer by Radiohead": the last "by" (so "Stand by Me by Ben E. King" keeps its title).
            val b = r.lastIndexOf("by")
            if (b > a && b < r.size - 1 && r[b + 1] !in PRONOUNS) return PlayRequest(asSaid, PlayFocus.AUDIO, title = src(a, b), artist = src(b + 1, r.size))
            return PlayRequest(src(a, r.size), said = asSaid)
        }
        var a = 0
        while (a < r.size - 1 && r[a] in ARTICLES_FR) a++
        // « l'album Kid A [de Radiohead] », « la chanson Creep [de Radiohead] ».
        if (r[a] == "album" || r[a] == "chanson" || r[a] == "toune" || r[a] == "piste") {
            val (name, artist) = split(r, a + 1, "de", src)
            if (name != null) {
                return if (r[a] == "album") PlayRequest(asSaid, PlayFocus.ALBUM, album = name, artist = artist)
                else PlayRequest(asSaid, PlayFocus.AUDIO, title = name, artist = artist)
            }
        }
        // « des chansons de Radiohead », « de la musique de Radiohead ».
        if (r[a] in setOf("chansons", "tounes", "musique") && r.getOrNull(a + 1) in setOf("de", "d") && a + 2 < r.size)
            return src(a + 2, r.size).let { PlayRequest(it, PlayFocus.ARTIST, artist = it, said = asSaid) }
        // « ma liste de lecture d'entraînement », « ma playlist du matin ».
        if (contains(r, LISTE_DE_LECTURE) || "playlist" in r) return PlayRequest(src(a, r.size), said = asSaid)
        genre?.let { (g0, g1) -> return src(g0, g1).let { PlayRequest(it, PlayFocus.GENRE, genre = it, said = asSaid) } }
        return PlayRequest(src(a, r.size), said = asSaid)
    }

    /** [r] from [from], cut at the last [word] ("by", « de »): the name, and who by when there is one. */
    private fun split(r: List<String>, from: Int, word: String, src: (Int, Int) -> String): Pair<String?, String?> {
        if (from >= r.size) return null to null
        val b = r.lastIndexOf(word)
        return if (b > from && b < r.size - 1 && r[b + 1] !in PRONOUNS) src(from, b) to src(b + 1, r.size) else src(from, r.size) to null
    }

    /** Where a genre is in [r] ("some jazz", "jazz music", « du jazz », « de la musique classique »), or null. */
    private fun genreOf(r: List<String>, lang: Lang): Pair<Int, Int>? {
        var a = 0
        val skip = if (lang == Lang.EN) setOf("some", "the", "my", "me", "a", "little", "bit", "of") else ARTICLES_FR + setOf("peu", "musique")
        while (a < r.size && r[a] in skip) a++
        var b = r.size
        val music = b > a && r[b - 1] in setOf("music", "musique") || (a > 0 && r[a - 1] == "musique")
        if (b > a && r[b - 1] in setOf("music", "musique")) b--
        if (a >= b) return null
        val g = r.subList(a, b)
        if (!music && GENRES_WITH_MUSIC.any { it == g }) return null
        return GENRES.firstOrNull { it == g }?.let { a to b }
    }

    private fun contains(w: List<String>, p: List<String>): Boolean = (0..w.size - p.size).any { at(w, it, p) }

    /** Words as said, at most [PlayRequest.MAX_CHARS], without control characters. */
    private fun clip(s: String): String = s.filter { it >= ' ' && it != '\u007f' }.trim().take(PlayRequest.MAX_CHARS)
}
