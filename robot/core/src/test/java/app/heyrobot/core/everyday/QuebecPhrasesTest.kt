// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.everyday

import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.link.LinkRouter
import app.heyrobot.core.lists.ListsRouter
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.music.MusicCommand
import app.heyrobot.core.music.MusicContext
import app.heyrobot.core.music.MusicRouter
import app.heyrobot.core.music.PlayFocus
import app.heyrobot.core.online.OnlineAsk
import app.heyrobot.core.online.OnlineDay
import app.heyrobot.core.online.OnlineRouter
import app.heyrobot.core.output.HandsFreeCommand
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.output.MusicAction
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.sums.SumKind
import app.heyrobot.core.sums.SumsCommand
import app.heyrobot.core.sums.SumsRouter
import app.heyrobot.core.sums.TaxName
import app.heyrobot.core.sums.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZonedDateTime

/**
 * E8: the Québec phrasings added to the three everyday routers (docs/plans/everyday-set-plan.md §12): each routes where it
 * should, reaches its router in the panel's order (§2.1), and never reaches a call or a message; call and message lines,
 * and the English lines, stay where they were. EverydayCorpusTest holds every other phrase of the corpus unchanged.
 */
class QuebecPhrasesTest {
    private val now = ZonedDateTime.parse("2026-10-22T10:00:00-04:00[America/Toronto]")
    private val picked = MusicContext(appPicked = true)
    private val skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())

    private val pause = listOf("ferme la musique", "ferme la toune", "éteins la musique", "stop la musique", "stop la toune", "coupe la chanson", "arrête la chanson",
        "mets la chanson sur pause", "mets la chanson en pause", "pause la toune", "coupe donc la musique", "coupe la musique là", "ferme donc la toune")
    private val play = listOf("repars la toune", "continue la musique", "continue la toune", "mets-moi de la musique", "mets-nous de la musique", "mets-moi donc de la musique",
        "mets donc de la musique", "fais-moi jouer de la musique", "fais-nous jouer de la musique", "joue-moi de la musique", "peux-tu mettre de la musique",
        "peux-tu me mettre de la musique", "tu peux-tu mettre de la musique", "peux-tu faire jouer de la musique", "peux-tu me faire jouer de la musique",
        "peux-tu jouer de la musique", "je veux écouter de la musique", "j'aimerais écouter de la musique", "on écoute de la musique", "eille, mets de la musique")
    private val next = listOf("change de toune", "change de chanson", "change la toune", "change la chanson", "saute cette toune", "saute cette chanson", "saute la toune",
        "saute la chanson", "passe cette toune", "passe cette chanson", "passe à une autre toune", "passe à l'autre toune", "passe à la toune suivante",
        "mets la prochaine toune", "mets la toune suivante", "mets-moi la prochaine toune", "la prochaine toune", "la toune suivante", "la prochaine chanson",
        "la chanson suivante", "joue la prochaine toune", "fais jouer la prochaine toune", "fais jouer la toune suivante", "fais jouer la prochaine chanson",
        "fais jouer la chanson suivante", "skip la toune", "skip cette toune", "skip la chanson", "skip cette chanson", "skippe la toune", "skippe cette toune",
        "change donc de toune")
    private val previous = listOf("la toune d'avant", "la toune précédente", "remets la toune d'avant", "remets la chanson d'avant", "remets la toune précédente",
        "reviens à la toune d'avant", "reviens à la chanson d'avant", "reviens à la toune précédente", "retourne à la toune d'avant", "retourne à la chanson d'avant",
        "retourne à la chanson précédente", "joue la toune d'avant", "fais jouer la toune d'avant", "fais jouer la chanson d'avant", "fais jouer la chanson précédente",
        "mets la toune d'avant", "mets la chanson d'avant", "recule d'une toune", "recule d'une chanson")
    private val nowPlaying = listOf("c'est quoi cette toune-là", "c'est quoi cette chanson-là", "c'est quoi la toune qui joue", "c'est quoi la chanson qui joue",
        "c'est quoi ça cette toune-là", "qu'est-ce qui joue là", "c'est qui qui chante", "c'est qui qui chante ça", "c'est qui qui chante cette toune",
        "c'est qui qui chante cette chanson", "qui chante cette toune-là", "qui chante cette chanson", "comment s'appelle cette toune", "comment s'appelle cette chanson",
        "comment ça s'appelle cette toune", "comment ça s'appelle cette chanson", "ça s'appelle comment cette toune", "ça s'appelle comment cette chanson",
        "qu'est-ce que j'écoute", "c'est quoi que j'écoute", "qu'est-ce que ça joue", "c'est quoi le titre de cette toune", "c'est quoi le titre de cette chanson",
        "coudonc, c'est quoi cette toune")
    /** Taken before any music app is picked: a music word says it's music. */
    private val byNameAlways = mapOf("mets-moi du jazz" to "jazz", "mets-moi donc du jazz" to "jazz", "mets-nous du rock" to "rock", "fais-nous jouer du jazz" to "jazz",
        "fais jouer du country" to "country", "fais donc jouer du blues" to "blues", "peux-tu me mettre du jazz" to "jazz", "peux-tu jouer du rock" to "rock",
         "je veux écouter du jazz" to "jazz", "j'aimerais écouter du classique" to "classique", "on écoute du rock" to "rock",
        "fais-moi écouter du jazz" to "jazz")
    private val bySongAlways = listOf("mets-moi la toune Bohemian Rhapsody", "fais jouer la toune Hexagone", "mets-moi l'album Kid A", "je veux écouter la chanson Hexagone",
        "mets-moi ma liste de lecture du matin", "fais-moi jouer ma liste de lecture", "mets-moi une toune", "mets-moi une bonne toune", "mets-moi de la bonne musique")
    /** Only once your music app is picked. */
    private val byNamePicked = mapOf("fais jouer Harmonium" to "Harmonium", "fais donc jouer Harmonium" to "Harmonium", "peux-tu faire jouer Harmonium" to "Harmonium", "peux-tu jouer Harmonium" to "Harmonium",
        "tu peux-tu faire jouer Beau Dommage" to "Beau Dommage", "fais-nous jouer Beau Dommage" to "Beau Dommage", "fais jouer quelque chose de bon" to "")

    private val days = mapOf("12 fois 7 c'est combien" to SumKind.ARITHMETIC, "12 fois 7, ça fait combien" to SumKind.ARITHMETIC, "12 plus 7 ça donne quoi" to SumKind.ARITHMETIC,
        "ça donne combien 12 fois 7" to SumKind.ARITHMETIC, "c'est combien 12 fois 7" to SumKind.ARITHMETIC, "12 fois 7 ça fait quoi" to SumKind.ARITHMETIC,
        "peux-tu calculer 12 fois 7" to SumKind.ARITHMETIC, "dis-moi 12 fois 7" to SumKind.ARITHMETIC, "12 fois 7 stp" to SumKind.ARITHMETIC,
        "c'est combien 18 % de 64 piasses" to SumKind.PERCENT, "18 % de 64 $, c'est combien" to SumKind.PERCENT,
        "20 % de rabais sur 80 piasses" to SumKind.PERCENT, "80 $ avec 20 % de rabais" to SumKind.PERCENT, "80 $ à 20 % d'escompte" to SumKind.PERCENT,
        "10 % de remise sur 80 $" to SumKind.PERCENT, "c'est combien 15 % de pourboire sur 40 $" to SumKind.TIP, "c'est combien de pourboire sur 40 $ à 15 %" to SumKind.TIP,
        "combien de pourboire sur 40 piasses à 15 %" to SumKind.TIP, "combien je laisse de pourboire sur 40 $ à 15 %" to SumKind.TIP, "un tip de 15 % sur 40 $" to SumKind.TIP,
        "15 % de tip sur 40 piasses" to SumKind.TIP, "pourboire sur 40 $ à 15 %" to SumKind.TIP, "la TPS sur 80 $" to SumKind.TAX, "les taxes sur 80 piasses" to SumKind.TAX,
        "c'est combien les taxes sur 80 $" to SumKind.TAX, "15 % de taxes sur 80 $" to SumKind.TAX, "64 $ entre 4" to SumKind.SPLIT, "64 piasses pour 4 personnes" to SumKind.SPLIT,
        "64 $ divisé par 4 personnes" to SumKind.SPLIT, "64 $ séparé à 4" to SumKind.SPLIT, "64 $ séparé par 4" to SumKind.SPLIT, "64 $ divisé en 4, c'est combien chacun" to SumKind.SPLIT,
        "splitte 64 $ en 4" to SumKind.SPLIT, "c'est combien 5 milles en kilomètres" to SumKind.UNIT, "5 milles ça fait combien de kilomètres" to SumKind.UNIT,
        "350 °F c'est combien en Celsius" to SumKind.UNIT, "combien de kilomètres ça fait 5 milles" to SumKind.UNIT, "ça fait combien de tasses dans un litre" to SumKind.UNIT,
        "il y a combien de tasses dans un litre" to SumKind.UNIT, "y a combien de tasses dans un litre" to SumKind.UNIT, "2 cuillères à table en millilitres" to SumKind.UNIT,
        "combien de dodos avant Noël" to SumKind.DATE, "il reste combien de jours avant Noël" to SumKind.DATE, "reste combien de semaines avant Noël" to SumKind.DATE,
        "ça fait combien de jours depuis le 1er juin" to SumKind.DATE, "Noël, c'est dans combien de jours" to SumKind.DATE, "Noël, c'est quel jour" to SumKind.DATE,
        "c'est quel jour Noël" to SumKind.DATE, "quelle date on va être dans 3 semaines" to SumKind.DATE, "dans 3 semaines, on va être quel jour" to SumKind.DATE)

    private val online = mapOf("c'est quoi qui est allé en ligne" to OnlineDay.TODAY, "c'est quoi que t'as envoyé en ligne" to OnlineDay.TODAY,
        "qu'est-ce que t'as envoyé en ligne hier" to OnlineDay.YESTERDAY, "as-tu envoyé quelque chose en ligne" to OnlineDay.TODAY, "t'as-tu envoyé de quoi en ligne" to OnlineDay.TODAY,
        "es-tu allé sur internet" to OnlineDay.TODAY, "t'es-tu allé en ligne à matin" to OnlineDay.TODAY, "es-tu allé sur le web ce matin" to OnlineDay.TODAY,
        "as-tu été en ligne hier soir" to OnlineDay.YESTERDAY, "t'as-tu été sur internet" to OnlineDay.TODAY, "as-tu utilisé internet ces derniers jours" to OnlineDay.WEEK,
        "à quels serveurs t'es-tu connecté la semaine passée" to OnlineDay.WEEK, "coudonc, es-tu allé en ligne là" to OnlineDay.TODAY, "montre-moi ce qui est allé en ligne la semaine dernière" to OnlineDay.WEEK)

    private fun all(): List<String> = pause + play + next + previous + nowPlaying + byNameAlways.keys + bySongAlways + byNamePicked.keys + days.keys + online.keys

    @Test fun theKeysAreHeard() {
        for ((a, ps) in listOf(MusicAction.PAUSE to pause, MusicAction.PLAY to play, MusicAction.NEXT to next, MusicAction.PREVIOUS to previous)) for (p in ps)
            assertEquals(p, MusicCommand.Key(a, Lang.FR), MusicRouter.route(p))
    }

    @Test fun theBarePausePhrasesCountOnlyWhileSomethingPlays() {
        for (p in listOf("mets sur pause", "mets ça sur pause", "mets ça en pause", "mets donc ça sur pause")) {
            assertNull(p, MusicRouter.route(p))
            assertEquals(p, MusicCommand.Key(MusicAction.PAUSE, Lang.FR), MusicRouter.route(p, MusicContext(playing = true)))
            assertEquals(p, MusicCommand.Key(MusicAction.PAUSE, Lang.FR), MusicRouter.route(p, MusicContext(carMode = true)))
        }
    }

    @Test fun whatsPlayingIsHeard() {
        for (p in nowPlaying) assertEquals(p, MusicCommand.NowPlaying(Lang.FR), MusicRouter.route(p))
    }

    @Test fun playByNameWithAMusicWord() {
        for ((p, genre) in byNameAlways) {
            val c = MusicRouter.route(p) as? MusicCommand.ByName
            if (genre == null) assertNull(p, c) else assertEquals(p, genre, c?.request?.genre)
        }
        for (p in bySongAlways) assertEquals(p, Lang.FR, (MusicRouter.route(p) as? MusicCommand.ByName)?.lang)
        assertEquals(PlayFocus.AUDIO, (MusicRouter.route("mets-moi la toune Hexagone de Renaud") as MusicCommand.ByName).request.focus)
        assertEquals("Renaud", (MusicRouter.route("mets-moi donc la toune Hexagone de Renaud") as MusicCommand.ByName).request.artist)
    }

    @Test fun playByNameOnceAnAppIsPicked() {
        for ((p, q) in byNamePicked) {
            assertEquals(p, q, (MusicRouter.route(p, picked) as? MusicCommand.ByName)?.request?.query)
            assertNull(p, MusicRouter.route(p))
        }
    }

    @Test fun musicMisfiresStayOut() {
        for (p in listOf("mets-moi un rappel pour la toune de Marie", "mets-moi un cadran", "mets-moi une alarme", "mets-moi un minuteur de 10 minutes", "mets-moi ça dans mes notes",
            "mets-moi en contact avec Marie", "mets-moi Marie au téléphone", "mets-moi quelque chose", "mets la table", "je veux écouter mes messages", "on écoute mes messages vocaux",
            "fais jouer mon message vocal", "fais jouer ma messagerie", "je veux jouer aux cartes", "jouer aux cartes", "jouer dehors", "peux-tu jouer au hockey", "jouer du piano",
            "peux-tu jouer avec moi", "la prochaine", "la suivante", "passe à la suivante", "le suivant", "ferme la porte", "continue", "change de poste", "saute",
            "qui chante à la messe", "donc", "mets donc", "c'est quoi ça")) {
            assertNull(p, MusicRouter.route(p, picked))
        }
        // The last word of a name is kept, and « donc » inside a name too.
        assertEquals("Oh la la", (MusicRouter.route("fais jouer Oh la la", picked) as MusicCommand.ByName).request.query)
        assertEquals("Ni oui ni donc", (MusicRouter.route("fais jouer Ni oui ni donc", picked) as MusicCommand.ByName).request.query)
    }

    @Test fun theSumsAreHeard() {
        for ((p, k) in days) assertEquals(p, k, SumsRouter.route(p, now)?.kind)
        for (p in days.keys) assertEquals(p, Lang.FR, SumsRouter.route(p, now)?.lang)
    }

    @Test fun theSumsReadTheRightNumbers() {
        assertEquals(SumsCommand.Tip(BigDecimal("15"), BigDecimal("40"), Lang.FR).toString(), SumsRouter.route("combien de pourboire sur 40 piasses à 15 %", now).toString())
        val tip = SumsRouter.route("c'est combien de pourboire sur 64,50 $ à 15 %", now) as SumsCommand.Tip
        assertEquals(0, BigDecimal("64.50").compareTo(tip.x))
        assertEquals(0, BigDecimal("15").compareTo(tip.p))
        val gst = SumsRouter.route("la TPS sur 80 $", now) as SumsCommand.Tax
        assertEquals(TaxName.GST, gst.tax)
        assertEquals(0, BigDecimal(5).compareTo(gst.p))
        assertNull((SumsRouter.route("les taxes sur 80 $", now) as SumsCommand.Tax).p)
        assertNull((SumsRouter.route("la TVQ sur 80 $", now) as SumsCommand.Tax).p)
        val split = SumsRouter.route("64 piasses pour 4 personnes", now) as SumsCommand.Split
        assertEquals(4, split.n)
        assertTrue(split.money)
        val conv = SumsRouter.route("5 milles ça fait combien de kilomètres", now) as SumsCommand.Convert
        assertEquals(Unit.MILE, conv.from)
        assertEquals(Unit.KM, conv.to)
        assertEquals(Unit.TBSP, (SumsRouter.route("2 cuillères à table en millilitres", now) as SumsCommand.Convert).from)
        assertFalse((SumsRouter.route("combien de dodos avant Noël", now) as SumsCommand.Days).weeks)
    }

    /** Dose questions, and anything that isn't a sum, still go on: the closed vocabulary holds with the new leads and tails. */
    @Test fun sumMisfiresStayOut() {
        for (p in listOf("c'est combien", "ça fait combien", "ça coûte combien", "c'est combien un café", "combien de comprimés d'Advil pour 20 kilos", "c'est combien 2 comprimés",
            "ça fait combien de temps", "il y a 3 jours", "y a 5 km", "c'est quoi ça", "c'est quoi la météo", "dis-moi une blague", "12 fois 7 ou 8", "c'est 12",
            "64 $ pour 4", "64 $ à 4", "peux-tu me dire la météo", "il reste combien de lait", "c'est quel jour ta fête", "on va être quel jour")) {
            assertNull(p, SumsRouter.route(p, now))
        }
    }

    @Test fun theOnlineQuestionsAreHeard() {
        for ((p, d) in online) assertEquals(p, OnlineAsk(Lang.FR, d), OnlineRouter.route(p))
    }

    /** Only the past tense about what Robot did: a question about the state of the network, or a request to go online, stays out. */
    @Test fun onlineMisfiresStayOut() {
        for (p in listOf("es-tu connecté à internet", "t'es-tu connecté à internet", "es-tu en ligne", "as-tu internet", "va en ligne", "va sur internet", "cherche sur internet",
            "Marie est-elle allée en ligne", "es-tu allé à l'épicerie", "as-tu envoyé mon message", "as-tu envoyé quelque chose à Marie", "as-tu été chez Marie")) {
            assertNull(p, OnlineRouter.route(p))
        }
    }

    /** Each phrase is one router's, and the routers before them in the panel (§2.1) leave it alone, so it gets there. */
    @Test fun eachPhraseReachesItsRouter() {
        for (p in all()) {
            assertNull("messages: $p", MessagesRouter.route(p))
            assertNull("conversation: $p", ConversationRouter.route(p, running = false))
            assertNull("feedback: $p", FeedbackRouter.route(p))
            assertNull("lock: $p", LockRouter.route(p, now, listOf("Bedtime")))
            assertNull("routines: $p", RoutineRouter.route(p, now, listOf("Evening routine"), id = "r"))
            assertNull("skills: $p", SkillRouter.route(p, skills, people = listOf("Marie", "Sam")))
            assertNull("files: $p", FilesRouter.route(p))
            assertNull("lists: $p", ListsRouter.route(p))
            assertNull("link: $p", LinkRouter.route(p, now) { false })
            val takers = listOfNotNull(MusicRouter.route(p, picked), SumsRouter.route(p, now), OnlineRouter.route(p))
            assertEquals(p, 1, takers.size)
        }
    }

    /** With Music by voice off a phrase goes on to hands-free and the model: hands-free must not read any as a call or a reply. */
    @Test fun noPhraseIsACallOrAMessage() {
        for (p in all()) for (car in listOf(false, true)) {
            val c = HandsFreeRouter.route(p, carMode = car)
            assertFalse("$p: $c", c is HandsFreeCommand.Call || c is HandsFreeCommand.Reply)
        }
    }

    /** A call or a message in Québec French, with the words the new phrases use in it, is none of the three routers' and still reaches its own. */
    @Test fun callsAndMessagesStayOutOfTheThreeRouters() {
        val lines = listOf("appelle Marie", "appelle la toune", "appelle Marie, c'est combien", "passe-moi Marie", "passe un coup de fil à Marie", "mets-moi en contact avec Sam",
            "texte Marie", "texte à Marie c'est combien", "envoie un message à Sam", "envoie un texto à Sam", "envoie la toune à Marie", "envoie 50 piasses à Sam",
            "écris à Sam que ça fait 12 piasses", "dis à Marie de mettre de la musique", "réponds à Marie", "réponds à Sam, c'est combien", "demande à Sam c'est combien",
            "lis mes messages", "c'est quoi mes messages", "es-tu allé texter Marie", "as-tu envoyé mon message à Marie", "as-tu envoyé un message en ligne à Sam")
        for (p in lines) {
            assertNull(p, MusicRouter.route(p, picked))
            assertNull(p, SumsRouter.route(p, now))
            assertNull(p, OnlineRouter.route(p))
        }
        assertNotNull(HandsFreeRouter.route("appelle Marie", carMode = true))
    }

    /** English lines stay as they were, the ones next to what changed above all. */
    @Test fun englishLinesRouteAsBefore() {
        assertEquals(MusicCommand.Key(MusicAction.PAUSE, Lang.EN), MusicRouter.route("pause the music"))
        assertEquals(MusicCommand.Key(MusicAction.NEXT, Lang.EN), MusicRouter.route("next song please"))
        assertEquals(MusicCommand.NowPlaying(Lang.EN), MusicRouter.route("what's playing"))
        assertEquals("Radiohead", (MusicRouter.route("play Radiohead", picked) as MusicCommand.ByName).request.query)
        // « donc » and « là » are French's alone; "us" isn't skipped as « nous » is.
        assertEquals("donc", (MusicRouter.route("play donc", picked) as MusicCommand.ByName).request.query)
        assertEquals("us", (MusicRouter.route("play us", picked) as MusicCommand.ByName).request.query)
        for (p in listOf("play chess", "play me", "put on a show", "you can play music later", "well play music", "bon voyage", "skip", "next", "stop the car")) assertNull(p, MusicRouter.route(p))
        assertEquals(SumKind.TIP, SumsRouter.route("what's an 18% tip on $64.50", now)?.kind)
        assertEquals(Lang.EN, SumsRouter.route("what's an 18% tip on $64.50", now)?.lang)
        assertEquals(SumKind.ARITHMETIC, SumsRouter.route("100 divided by 4", now)?.kind)
        assertEquals(SumKind.SPLIT, SumsRouter.route("\$100 split between 4 people", now)?.kind)
        assertEquals(SumKind.UNIT, SumsRouter.route("5 miles to km", now)?.kind)
        for (p in listOf("\$100 for 4 people", "\$100 divided by 4 people", "how much is a coffee", "what's the weather")) assertNull(p, SumsRouter.route(p, now))
        assertEquals(OnlineAsk(Lang.EN, OnlineDay.TODAY), OnlineRouter.route("what went online today"))
        assertEquals(OnlineAsk(Lang.EN, OnlineDay.WEEK), OnlineRouter.route("did you go online this week"))
        for (p in listOf("go online", "is the internet down", "is Sam online")) assertNull(p, OnlineRouter.route(p))
    }
}
