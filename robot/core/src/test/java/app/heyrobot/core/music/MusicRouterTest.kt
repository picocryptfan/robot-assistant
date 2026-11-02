// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.music

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.output.MusicAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The everyday set's music phrases (docs/plans/everyday-set-plan.md §3.2, §3.6, D2): the keys in both languages, the bare
 * words with and without something playing, play by name before and after your music app is picked, what's playing,
 * every misfire of §3.6, and car mode's 0.8 phrases unchanged in effect.
 */
class MusicRouterTest {
    private val none = MusicContext()
    private val picked = MusicContext(appPicked = true)

    private fun key(a: MusicAction, l: Lang?) = MusicCommand.Key(a, l)

    @Test fun theKeysCountAlwaysInBothLanguages() {
        val en = mapOf("pause the music" to MusicAction.PAUSE, "stop the music" to MusicAction.PAUSE, "pause music" to MusicAction.PAUSE,
            "play music" to MusicAction.PLAY, "resume the music" to MusicAction.PLAY, "unpause" to MusicAction.PLAY,
            "next song" to MusicAction.NEXT, "skip this song" to MusicAction.NEXT, "previous song" to MusicAction.PREVIOUS, "go back a song" to MusicAction.PREVIOUS,
            "Hey Robot, pause the music please" to MusicAction.PAUSE, "Next song!" to MusicAction.NEXT, "can you skip this song" to MusicAction.NEXT)
        val fr = mapOf("arrête la musique" to MusicAction.PAUSE, "coupe la musique" to MusicAction.PAUSE, "mets la musique sur pause" to MusicAction.PAUSE,
            "mets de la musique" to MusicAction.PLAY, "reprends la musique" to MusicAction.PLAY, "remets la musique" to MusicAction.PLAY,
            "chanson suivante" to MusicAction.NEXT, "prochaine toune" to MusicAction.NEXT, "passe la toune" to MusicAction.NEXT,
            "chanson précédente" to MusicAction.PREVIOUS, "Peux-tu passer la toune" to null, "peux-tu passe la toune s'il te plaît" to MusicAction.NEXT)
        for (ctx in listOf(none, picked, MusicContext(carMode = true), MusicContext(playing = true))) {
            for ((s, a) in en) assertEquals(s, key(a, Lang.EN), MusicRouter.route(s, ctx))
            for ((s, a) in fr) assertEquals(s, a?.let { key(it, Lang.FR) }, MusicRouter.route(s, ctx))
        }
    }

    @Test fun carModesPhrasesAreAllHeardInAndOutOfCarMode() {
        // Every 0.8 car mode phrase (HandsFreeRouter keeps them only while Music by voice is off) means the same key here.
        assertTrue(HandsFreeRouter.MUSIC.size >= 40)
        for ((words, a) in HandsFreeRouter.MUSIC) {
            val s = words.joinToString(" ")
            assertEquals(s, a, (MusicRouter.route(s, MusicContext(carMode = true)) as MusicCommand.Key).action)
            // "pause" alone is car mode's (or needs something playing); every other phrase counts out of car mode too.
            if (s != "pause") assertEquals(s, a, (MusicRouter.route(s, none) as MusicCommand.Key).action)
        }
    }

    @Test fun theBareWordsCountOnlyWhenTheyCantSurpriseYou() {
        // "pause": only when something was playing as you started asking, or in car mode.
        assertNull(MusicRouter.route("pause", none))
        assertNull(MusicRouter.route("pause", picked))
        assertEquals(key(MusicAction.PAUSE, null), MusicRouter.route("pause", MusicContext(playing = true)))
        assertEquals(key(MusicAction.PAUSE, null), MusicRouter.route("Pause.", MusicContext(carMode = true)))
        // "play", "resume", « joue », « reprends »: only after Robot's own pause (10 minutes), or in car mode.
        for ((s, l) in listOf("play" to null, "resume" to Lang.EN, "joue" to Lang.FR, "reprends" to Lang.FR)) {
            assertNull(s, MusicRouter.route(s, none))
            assertNull(s, MusicRouter.route(s, MusicContext(playing = true, appPicked = true)))
            assertEquals(s, key(MusicAction.PLAY, l), MusicRouter.route(s, MusicContext(robotPaused = true)))
            assertEquals(s, key(MusicAction.PLAY, l), MusicRouter.route(s, MusicContext(carMode = true)))
        }
        // "next" and "skip" alone are never the music's (the brief's "next" comes earlier in the panel, §3.6).
        for (s in listOf("next", "skip", "stop", "previous"))
            for (ctx in listOf(none, MusicContext(playing = true, robotPaused = true, appPicked = true, carMode = true))) assertNull(s, MusicRouter.route(s, ctx))
    }

    @Test fun playByNameNeedsAMusicWordUntilAnAppIsPicked() {
        // §3.2, D2: "play chess" never brings up the picker.
        for (s in listOf("play Radiohead", "fais jouer Radiohead", "mets du Radiohead", "play chess", "play something", "joue Céline Dion")) assertNull(s, MusicRouter.route(s, none))
        for (s in listOf("play OK Computer by Radiohead", "play the album Kid A", "play some jazz", "play my workout playlist", "play songs by Radiohead",
                "joue l'album Kid A", "mets du jazz", "mets ma liste de lecture d'entraînement", "fais jouer la chanson Creep de Radiohead", "put on some jazz"))
            assertTrue(s, MusicRouter.route(s, none) is MusicCommand.ByName)
        for (s in listOf("play Radiohead", "fais jouer Radiohead", "fais jouer du Radiohead", "joue Céline Dion", "play something"))
            assertTrue(s, MusicRouter.route(s, picked) is MusicCommand.ByName)
    }

    @Test fun theRequestIsBuiltByCode() {
        fun r(s: String, ctx: MusicContext = picked) = (MusicRouter.route(s, ctx) as MusicCommand.ByName).request
        assertEquals(PlayRequest("Radiohead"), r("play Radiohead"))
        assertEquals(PlayRequest("OK Computer by Radiohead", PlayFocus.AUDIO, title = "OK Computer", artist = "Radiohead"), r("play OK Computer by Radiohead"))
        assertEquals(PlayRequest("the album Kid A", PlayFocus.ALBUM, album = "Kid A"), r("play the album Kid A"))
        assertEquals(PlayRequest("the album Kid A by Radiohead", PlayFocus.ALBUM, album = "Kid A", artist = "Radiohead"), r("play the album Kid A by Radiohead"))
        assertEquals(PlayRequest("jazz", PlayFocus.GENRE, genre = "jazz", said = "some jazz"), r("play some jazz"))
        assertEquals(PlayRequest("workout playlist", said = "my workout playlist"), r("play my workout playlist"))
        assertEquals(PlayRequest("Radiohead", PlayFocus.ARTIST, artist = "Radiohead", said = "songs by Radiohead"), r("play songs by Radiohead"))
        // "Stand by Me": no artist called "Me"; with one named, the last "by" counts.
        assertEquals(PlayRequest("Stand by Me"), r("play Stand by Me"))
        assertEquals(PlayRequest("Stand by Me by Ben E. King", PlayFocus.AUDIO, title = "Stand by Me", artist = "Ben E. King"), r("play Stand by Me by Ben E. King"))
        assertEquals(PlayRequest("", said = "something"), r("play something"))
        assertEquals(PlayRequest("the album Kid A", PlayFocus.ALBUM, album = "Kid A"), r("hey robot, play the album Kid A please"))
        // French: as said, accents and case kept.
        assertEquals(PlayRequest("Radiohead"), r("fais jouer Radiohead"))
        assertEquals(PlayRequest("Radiohead", said = "du Radiohead"), r("fais jouer du Radiohead"))
        assertEquals(PlayRequest("l'album Kid A", PlayFocus.ALBUM, album = "Kid A"), r("joue l'album Kid A"))
        assertEquals(PlayRequest("jazz", PlayFocus.GENRE, genre = "jazz", said = "du jazz"), r("mets du jazz"))
        assertEquals(PlayRequest("liste de lecture d'entraînement", said = "ma liste de lecture d'entraînement"), r("mets ma liste de lecture d'entraînement"))
        assertEquals(PlayRequest("la chanson Creep de Radiohead", PlayFocus.AUDIO, title = "Creep", artist = "Radiohead"), r("fais jouer la chanson Creep de Radiohead"))
        assertEquals(PlayRequest("Céline Dion", PlayFocus.ARTIST, artist = "Céline Dion", said = "des chansons de Céline Dion"), r("mets des chansons de Céline Dion"))
        assertEquals(PlayRequest("classique", PlayFocus.GENRE, genre = "classique", said = "de la musique classique"), r("mets de la musique classique"))
        // The language of the reply.
        assertEquals(Lang.EN, MusicRouter.route("play Radiohead", picked)?.lang)
        assertEquals(Lang.FR, MusicRouter.route("fais jouer Radiohead", picked)?.lang)
    }

    @Test fun theExtrasAreMediaStoresAndStringsOnly() {
        val r = PlayRequest("OK Computer by Radiohead", PlayFocus.AUDIO, title = "OK Computer", artist = "Radiohead")
        assertEquals(mapOf("android.intent.extra.focus" to "vnd.android.cursor.item/audio", "android.intent.extra.title" to "OK Computer",
            "android.intent.extra.artist" to "Radiohead"), r.extras())
        assertEquals(mapOf("android.intent.extra.focus" to "vnd.android.cursor.item/*"), PlayRequest("workout playlist").extras())
        assertEquals(mapOf("android.intent.extra.focus" to "vnd.android.cursor.item/album", "android.intent.extra.album" to "Kid A"),
            PlayRequest("the album Kid A", PlayFocus.ALBUM, album = "Kid A").extras())
        assertEquals(mapOf("android.intent.extra.focus" to "vnd.android.cursor.item/genre", "android.intent.extra.genre" to "jazz"),
            PlayRequest("jazz", PlayFocus.GENRE, genre = "jazz").extras())
        // No playlist extra or focus: deprecated in the SDK (D4).
        assertTrue(PlayFocus.entries.none { "playlist" in it.mime })
    }

    @Test fun aLongQuestionIsNoSongAndControlCharactersAreDropped() {
        assertNull(MusicRouter.route("play the song that goes la la la la la la la la la la la la la", picked))
        val r = (MusicRouter.route("play Radio\u0007head", picked) as MusicCommand.ByName).request
        assertTrue(r.query.none { it < ' ' })
    }

    @Test fun whatsPlaying() {
        for (s in listOf("what's playing", "what song is this", "who sings this", "What's playing?", "hey robot what is playing"))
            assertEquals(s, MusicCommand.NowPlaying(Lang.EN), MusicRouter.route(s, none))
        for (s in listOf("c'est quoi cette toune", "qui chante ça", "C'est quoi cette chanson?"))
            assertEquals(s, MusicCommand.NowPlaying(Lang.FR), MusicRouter.route(s, none))
        for (s in listOf("what's playing at the cinema tonight", "what song did you play yesterday", "who sings Creep")) assertNull(s, MusicRouter.route(s, picked))
    }

    /** E9's item 4: a music word doesn't make it a request to play now. Asked of every state, so nothing starts music. */
    private fun neverMusic(vararg said: String) {
        val all = MusicContext(playing = true, robotPaused = true, appPicked = true, carMode = true)
        for (s in said) {
            assertNull(s, MusicRouter.route(s, all))
            assertNull(s, MusicRouter.route(s, none))
        }
    }

    private fun stillMusic(vararg said: String) {
        for (s in said) assertTrue(s, MusicRouter.route(s, picked) is MusicCommand.ByName)
    }

    @Test fun aNegationNeverPlays() {
        neverMusic("mets pas de musique", "joue pas de musique", "fais jouer pas de musique", "mets pas de toune", "joue aucune musique", "mets plus de musique",
            "play no music", "play no songs", "play no more music", "play not music", "play no jazz", "put on no music", "joue rien", "mets rien", "play nothing")
        // A song that starts with the word still does.
        stillMusic("play No Scrubs", "play No Woman No Cry", "play No Surprises", "play Nothing Compares 2 U")
    }

    @Test fun louderIsVolumeNotASearchForLouder() {
        neverMusic("mets la musique moins fort", "mets la musique plus fort", "mets la toune plus fort", "joue la chanson moins fort", "mets la musique plus bas",
            "play the music louder", "play the music softer", "play the song quieter", "play louder", "put on the music louder", "play music at volume 5")
        stillMusic("play Louder by Dua Lipa")
    }

    @Test fun addingToAListIsNotPlaying() {
        neverMusic("mets cette chanson dans ma liste de lecture", "mets cette toune dans mes favoris", "mets la toune dans ma playlist", "mets cette chanson aux favoris",
            "play this song in my playlist", "put on this song in my favourites", "play the song to my favorites")
        stillMusic("mets ma liste de lecture d'entraînement", "play my workout playlist", "joue la chanson Dans ma rue")
    }

    @Test fun laterIsNotNow() {
        neverMusic("play music in ten minutes", "play music in 10 minutes", "play music in an hour", "play music in a few minutes", "play music in a bit",
            "play jazz tomorrow morning", "play jazz tomorrow", "play some music tonight", "play music later", "play music at 7", "play music at 7 pm",
            "play music at seven", "play music at noon", "play music this evening", "play music on Monday", "play music every morning", "play jazz in the morning",
            "play music when I get home", "play jazz after dinner", "play music until noon", "put on some jazz at 8", "play Radiohead tomorrow", "play Radiohead when I get home",
            "mets de la musique à 7 h", "mets de la musique à 7h", "mets de la musique à sept heures", "mets de la musique dans dix minutes", "mets du jazz dans une heure",
            "mets du jazz demain matin", "mets du jazz demain", "fais jouer de la musique ce soir", "mets de la musique à soir", "mets de la musique plus tard",
            "mets de la musique tantôt", "mets de la musique tout à l'heure", "mets du jazz à midi", "mets de la musique lundi", "mets du jazz quand j'arrive",
            "mets de la musique après le souper", "joue Radiohead demain")
        // A song named for the time still plays.
        stillMusic("play Tomorrow", "play Tonight Tonight by The Smashing Pumpkins", "play Yesterday", "play Wake Me Up When September Ends", "play Monday Morning",
            "play At Last", "joue Demain", "play Tomorrow Never Knows", "play Seven Nation Army", "play 7 Rings")
    }

    @Test fun somethingElseAfterTheMusicIsNotLost() {
        neverMusic("play music and call mom", "play the song and text Sam", "play music then call mom", "play jazz and set a timer", "play some music and send a message to Sam",
            "play music call mom", "put on my shoes and play music", "put on some music and call mom", "play Radiohead and call mom",
            "mets de la musique et appelle maman", "joue la toune et texte Sam", "mets du jazz pis appelle maman", "mets la musique puis envoie un message à Sam",
            "fais jouer de la musique et mets un minuteur", "mets mes bottes et joue de la musique")
        stillMusic("play Rock and Roll", "play Simon and Garfunkel", "play Me and Bobby McGee", "joue Simon et Garfunkel", "play Stop and Stare by OneRepublic")
    }

    @Test fun aTimerForTheMusicIsATimer() {
        neverMusic("mets un timer pour la musique", "mets un minuteur pour la musique", "mets une minuterie pour la toune", "mets-moi un timer pour la chanson",
            "put on a timer for the music", "put on a reminder for the song")
    }

    @Test fun gamesAndSportsAreNotSongs() {
        neverMusic("play cricket", "play rugby", "play minecraft", "play wordle", "play bingo", "play roulette", "play dominoes", "play solitaire", "play scrabble",
            "play sudoku", "play fortnite", "play lacrosse", "play pickleball", "play darts", "joue au bingo", "joue une partie avec moi", "joue une partie de cartes",
            "fais jouer une partie", "joue au hockey", "joue au ballon")
        stillMusic("play Roulette by System of a Down")
    }

    @Test fun idiomsAreNotSongs() {
        neverMusic("play devil's advocate with my essay", "play favourites", "play favorites", "play hooky", "play fetch with the dog", "play by ear", "play the field",
            "play the victim", "play the fool", "play hard to get", "play second fiddle", "play this", "play that", "joue bien", "joue dur", "joue fort", "joue mal", "joue serré")
        stillMusic("play Hard to Get by Rick Astley")
    }

    @Test fun whatsNotMusicOnThisPhoneIsNotASong() {
        neverMusic("play the news", "play the latest news", "play the weather", "play my notes", "play my note", "play my memo", "play my reminders", "play the reminder",
            "play my voice note", "play my voice memo", "play my voice message", "play my voice", "play my last call", "play my call", "play last call", "play calls",
            "play what she sent me", "play what mom said", "play what he told me", "play this file", "play my file", "play the attachment", "play this document",
            "joue les nouvelles", "joue la météo", "fais jouer ma note vocale", "joue mes notes", "joue mon mémo", "joue mes rappels", "joue mon dernier appel",
            "joue ce qu'elle m'a envoyé", "joue ce que maman a dit", "joue ce fichier")
        // The words alone, in a song: still a song.
        stillMusic("play Call Me Maybe", "play Reminder by The Weeknd", "play The Voice Within", "play What's Going On", "play What a Wonderful World", "play Stormy Weather",
            "play Bad News by Kanye West", "play the song Reminder")
    }

    @Test fun whatIsPutOnNeedsAMusicWord() {
        // « mets » also lays out the kitchen. Without a music word it isn't music, your app picked or not (E9's item 7).
        neverMusic("mets du beurre", "mets du sel", "mets de l'eau", "mets du linge", "mets des bottes", "mets de la crème", "mets du Radiohead", "mets-moi du Harmonium", "mets-nous du Harmonium", "mets-moi du sel")
        stillMusic("mets du jazz", "mets des chansons de Céline Dion", "mets de la musique classique", "fais jouer du Radiohead", "joue du Radiohead", "fais jouer Radiohead")
    }

    @Test fun theSmallCasesOfTheLeadAndTail() {
        // "us" as "me" and French « nous »: a song, not a search for "us a song".
        assertEquals(PlayRequest("", said = "a song"), (MusicRouter.route("play us a song", none) as MusicCommand.ByName).request)
        assertEquals(PlayRequest("jazz", PlayFocus.GENRE, genre = "jazz", said = "some jazz"), (MusicRouter.route("play us some jazz", none) as MusicCommand.ByName).request)
        neverMusic("play by")
        // "Play, thank you" and a song called Thank You sound alike: Robot doesn't search (the title needs its artist).
        assertNull(MusicRouter.route("play thank you", picked))
        assertTrue(MusicRouter.route("play Thank You by Dido", picked) is MusicCommand.ByName)
    }

    @Test fun theMisfiresOfSection3_6NeverReachTheMusic() {
        val all = MusicContext(playing = true, robotPaused = true, appPicked = true, carMode = true)
        for (s in listOf("play a game with me", "play 20 questions", "play trivia", "jouons à un jeu", "play my voicemail", "play my messages", "play the recording",
                "play back the transcript", "role play a job interview", "play along", "play dead", "add this song to my playlist", "remind me to play music at 7",
                "every morning, play music", "play the video", "play YouTube", "add this song to the shopping list", "stop", "next", "play chess",
                "play tennis tonight", "play hockey", "play the guitar", "play piano for me", "mets un minuteur de 5 minutes", "mets une alarme à 7 h", "mets la table",
                "mets le téléphone en silencieux", "mets ça dans mes notes", "joue avec moi", "on joue aux échecs", "joue-moi un tour", "fais jouer ma messagerie vocale",
                "play it cool", "play it by ear", "play house", "play ball", "play with the dog", "play the next episode", "play my podcast", "put on your shoes",
                "mets le mode auto", "fais jouer le jeu", "joue aux cartes", "joue du piano", "I remember that song", "my music is too loud", "Mets en pause",
                "mets en pause", "pause transcribing", "Mets la transcription en pause", "pause for a second and think", "stop listening to that song",
                "mets une minuterie de 12 minutes", "mets le lait dans le frigo", "mets-le dans le coffre", "how do I transcribe a song?", "play a trick on Sam")) {
            assertNull(s, MusicRouter.route(s, all))
            assertNull(s, MusicRouter.route(s, none))
        }
        // A named song is music, even with a game's or an instrument's word in it.
        assertTrue(MusicRouter.route("play Piano Man by Billy Joel", picked) is MusicCommand.ByName)
        assertTrue(MusicRouter.route("play the song Message in a Bottle", none) is MusicCommand.ByName)
        assertTrue(MusicRouter.route("play some house music", none) is MusicCommand.ByName)
    }
}
