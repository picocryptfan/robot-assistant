// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.wiki.WikiRoute.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiRouteTest {
    @Test
    fun p0TableRoutesFactsAndNothingElse() {
        val wrong = WikiP0.rows.mapNotNull { r ->
            val v = WikiRoute.check(r.question)
            val want = when (r.kind) { "fact" -> Verdict.FACT; "personal" -> Verdict.PERSONAL; "fresh" -> Verdict.FRESH; else -> Verdict.COMMAND }
            "${r.id} ${r.question}: $v, not $want".takeIf { v != want }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun p0TableSubjects() {
        val wrong = WikiP0.rows.filter { it.subject != null }.mapNotNull { r ->
            val s = WikiRoute.subject(r.question)
            "${r.id} ${r.question}: \"$s\"".takeIf { s != r.subject }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun languageOfEachP0Question() {
        WikiP0.rows.filter { it.kind == "fact" }.forEach { assertEquals(it.question, it.lang, WikiRoute.lang(it.question)) }
    }

    /**
     * wiki-fix-stale-card (wiki-p5-measure-b x51 to x53, phone-1.4.0-rc3 item 6): "Which ..." is a question of fact as
     * "What ..." is, and routes the same way; « Quel(le)(s) ... » already did. The must-not rows (P0's 25 to 30, 47 to 50)
     * stay out (p0TableRoutesFactsAndNothingElse runs all 50), and "which" with your own or fresh words stays out too.
     */
    @Test
    fun whichRoutesAsWhat() {
        listOf("Which planet is the largest?", "Which river flows through Paris?", "Which country did Napoleon rule?",
            "Which province joined Canada in 1949?", "Hey robot, which planet is the largest?",
        ).forEach { assertEquals(it, Verdict.FACT, WikiRoute.check(it)) }
        assertEquals("province joined Canada in 1949", WikiRoute.subject("Which province joined Canada in 1949?"))
        listOf("Quelle est la capitale du Canada?", "Quelle planète est la plus grosse?", "Quel explorateur a fondé Québec?",
            "Quels sont les Grands Lacs?",
        ).forEach { assertEquals(it, Verdict.FACT, WikiRoute.check(it)) }
        // As "What ...": the same verdict for each, your own things, fresh ones and follow-ups included.
        listOf("%s of my alarms is set?", "%s is my wife's birthday?", "%s team won last night?", "%s of these is better?",
            "%s one is it?", "%s province joined Canada in 1949?", "%s day is it today?", "%s did Alex say?",
        ).forEach { f ->
            val which = f.format("Which")
            assertEquals(which, WikiRoute.check(f.format("What")), WikiRoute.check(which))
        }
        listOf("Which of my alarms is set?", "Which team won last night?", "Which one is it?").forEach { assertFalse(it, WikiRoute.goes(it)) }

        // H1 (review-wiki-which-pick): one of the phone's things right after the lead is yours, in English and French.
        listOf("Which app uses the most battery?", "Which app is taking up the most space?", "Which note has the gate code?",
            "Which alarm goes off first?", "What app uses the most battery?", "Which apps can use the camera?", "What list has the paint colours?",
            "Which timer is for the rice?", "Which reminder is about the dentist?", "Which contact has a Halifax address?",
            "Which file is the lease?", "What appointments are on Friday?", "Which Wi-Fi network is the fastest?",
            "Quelle application utilise le plus de batterie?", "Quelle appli prend le plus d'espace?", "Quelle note a le code de la barrière?",
            "Quelle alarme sonne en premier?", "Quel rappel parle du dentiste?", "Quelle liste a les couleurs de peinture?",
            "Quels rendez-vous sont vendredi?", "Quel fichier est le bail?",
        ).forEach { assertEquals(it, Verdict.PERSONAL, WikiRoute.check(it)) }
        listOf("Which planet is the largest?", "Which city is the capital of Nova Scotia?", "Quelle est la plus grande planète ?",
            "Which planet is closest to the Sun?", "Quelle ville est la capitale de la Nouvelle-Écosse?",
        ).forEach { assertEquals(it, Verdict.FACT, WikiRoute.check(it)) }
    }

    /** The questions in [qs] whose verdict isn't [want], with the verdict they got. */
    private fun notGiven(want: Verdict, vararg qs: String) = qs.mapNotNull { q -> WikiRoute.check(q).let { v -> "$q: $v".takeIf { v != want } } }

    /** M1 (review-fix-wiki-which-route): a phone thing a few words after the lead is yours too, in English and French. */
    @Test
    fun aPhoneThingAFewWordsInIsYours() {
        assertEquals(emptyList<String>(), notGiven(Verdict.PERSONAL,
            "Which of the apps uses the most battery?", "What's the app that uses the most battery?",
            "What are the apps taking up the most space?", "Which grocery list has the milk?", "Which one of the notes has the gate code?",
            "Which of the timers is for the rice?", "What is the reminder about the dentist?",
            "Quelles sont les applis qui utilisent le plus de batterie?", "Quelle est l'appli qui prend le plus d'espace?",
            "Quelle est la liste d'épicerie qui a le lait?", "Quel est le rappel pour le dentiste?"))
    }

    /** M2 (review-fix-wiki-which-route): what uses, takes up or drains the battery or the space is the phone's question. */
    @Test
    fun whatUsesTheBatteryIsYours() {
        assertEquals(emptyList<String>(), notGiven(Verdict.PERSONAL,
            "What's using the most battery?", "What uses the most battery?", "What's taking up the most space?",
            "What is draining the battery?", "What's eating up all the storage?", "What is hogging the memory?", "What uses the most data?",
            "Qu'est-ce qui utilise le plus de batterie?", "Qu'est-ce qui prend le plus d'espace?", "Qu'est-ce qui vide la batterie?",
            "Qu'est-ce qui consomme le plus de données?", "Qu'est-ce qui occupe tout le stockage?", "Quel processus consomme le plus de données?"))
    }

    /** L2 (review-fix-wiki-which-route): one text, bill or screenshot, which the messages and Robot Files tools read. */
    @Test
    fun oneTextBillOrScreenshotIsYours() {
        assertEquals(emptyList<String>(), notGiven(Verdict.PERSONAL,
            "Which text is from Mom?", "Which bill is the biggest?", "Which screenshot has the receipt?", "What SMS came from the bank?",
            "Which of the bills is due first?", "Quel texto vient de maman?", "Quelle facture est la plus élevée?",
            "Quelle capture d'écran a le reçu?", "Quelle est la facture la plus élevée?"))
    }

    /**
     * The fixes above keep questions of fact with the copy: a lead and an article before a thing that isn't the phone's,
     * a list "of" something, and a phone word on its own ("What is Bluetooth?").
     */
    @Test
    fun factsAroundPhoneWordsStillGoToTheCopy() {
        assertEquals(emptyList<String>(), notGiven(Verdict.FACT,
            "Which planet is the largest?", "What is the capital of Nova Scotia?", "Quelle est la plus grande planète ?",
            "What is the list of prime ministers?", "Quelle est la liste des premiers ministres du Canada?", "What is Bluetooth?",
            "What is a file system?", "What is the setting of The Hobbit?", "Which country has the most lakes?",
            "Quel est le plus long fleuve du Canada?", "What is the largest planet?", "Who invented the battery?"))
        // L1 (review-fix-wiki-which-route): a phone word straight after the lead stays the phone's, trivia included.
        assertEquals(Verdict.PERSONAL, WikiRoute.check("What note is middle C?"))
        // M1 and M2 (review-fix-wiki-phone-nouns): "on the", "in a" and "so much" before a thing that isn't the phone's.
        assertEquals(emptyList<String>(), notGiven(Verdict.FACT,
            "What's on the flag of Canada?", "What's in a black hole?", "What's on the list of endangered species?",
            "What is the history of the Gregorian calendar?", "Quelle est l'histoire du calendrier grégorien?",
            "Which country uses so much oil?"))
    }

    /** M1 (review-fix-wiki-phone-nouns): a thing on, in or from one of yours (« sur », « dans ») is yours too. */
    @Test
    fun aThingOnOrInOneOfYoursIsYours() {
        assertEquals(emptyList<String>(), notGiven(Verdict.PERSONAL,
            "What's on the grocery list?", "What's on the list?", "What's in the note about the gate code?", "What's in the text from Mom?",
            "What's on the bill?", "What's in the screenshot?", "What's in the email from Alex?", "What's the total on the receipt?",
            "C'est quoi le code dans la note?", "Quel est le total sur la facture ?", "Qu'est-ce qu'il y a sur la liste d'épicerie?"))
    }

    /** M2 (review-fix-wiki-phone-nouns): "so much", "a lot of" and « place » are amounts of the battery or the space too. */
    @Test
    fun soMuchBatteryOrPlaceIsYours() {
        assertEquals(emptyList<String>(), notGiven(Verdict.PERSONAL,
            "What's using so much battery?", "What's taking up so much space?", "What is using a lot of data?",
            "Quel jeu prend le plus de place?", "Qu'est-ce qui prend trop de place?"))
    }

    /** L2 (review-fix-wiki-phone-nouns): « du wifi » is the phone's, as "the Wi-Fi" is. */
    @Test
    fun theWifiInFrenchIsYours() {
        assertEquals(emptyList<String>(), notGiven(Verdict.PERSONAL,
            "Quel est le mot de passe du wifi ?", "C'est quoi le nom du wi-fi?", "Quel est le code du bluetooth?"))
    }

    /** L1 (review-fix-wiki-phone-nouns): names and compounds around a phone word, and a cell's space, are questions of fact. */
    @Test
    fun namesAroundPhoneWordsGoToTheCopy() {
        assertEquals(emptyList<String>(), notGiven(Verdict.FACT,
            "What is the App Store?", "What is the NTFS file system?", "What was the first text message?",
            "What was the first email ever sent?", "What is the Bill and Melinda Gates Foundation?", "What is the message in Animal Farm?",
            "What takes up space in a cell?", "What takes up the most space in a plant cell?", "Quels sont les paramètres d'une loi normale?"))
    }

    @Test
    fun politenessAndWakeWordsComeOff() {
        assertEquals("Louis Riel", WikiRoute.subject("Hey robot, can you tell me about Louis Riel?"))
        assertEquals("Révolution tranquille", WikiRoute.subject("Salut robot, parle-moi de la Révolution tranquille"))
        assertEquals("trou noir", WikiRoute.subject("Qu’est-ce qu’un trou noir?"))
        assertEquals("Einstein", WikiRoute.subject("Parle-moi d'Einstein"))
        assertTrue(WikiRoute.goes("Please, what is the Bluenose?"))
    }

    @Test
    fun personalQuestionsNever() {
        listOf("What's your name?", "Who are you?", "What is my wife's birthday?", "Who is our dentist?", "Where did I leave my keys?",
            "Qui est mon médecin?", "C'est quoi ton nom?", "Où est-ce que j'ai mis mes clés?", "Quelle est notre adresse?",
        ).forEach { assertEquals(it, Verdict.PERSONAL, WikiRoute.check(it)) }
        assertTrue(WikiRoute.goes("What was World War I?"))
        assertTrue(WikiRoute.goes("Tell me about Viola Desmond"))
    }

    @Test
    fun freshQuestionsNever() {
        listOf("What's the latest on the election?", "Who won the Stanley Cup?", "What is the price of gold?", "What time is it?",
            "What is the weather in Halifax?", "What's the news?", "Quelle heure est-il?", "Qui a gagné la Coupe Stanley?", "C'est quoi les nouvelles?",
            "Quel est le prix de l'essence?", "Où est la tempête en ce moment?",
            "What's the weather this weekend?", "Qu'est-ce qui joue ce soir?", "Quelles sont les nouvelles cette semaine?",
        ).forEach { assertEquals(it, Verdict.FRESH, WikiRoute.check(it)) }
        // A time doesn't hide your own things: these stay personal.
        listOf("Where is my package right now?", "What's on my calendar this week?", "Où est mon colis en ce moment?",
            "Qu'est-ce que j'ai ce soir?", "Où sont mes clés en ce moment?",
        ).forEach { assertEquals(it, Verdict.PERSONAL, WikiRoute.check(it)) }
    }

    @Test
    fun commandsNever() {
        listOf("Set an alarm for 7", "Call Bob", "Open the camera", "Play some jazz", "Search the web for Louis Riel", "Look up Louis Riel online",
            "Remind me to call Bob", "Envoie un texto à Marie", "Ouvre la caméra", "Cherche sur le web Louis Riel", "Recherche Louis Riel",
            "Ajoute du lait à la liste",
        ).forEach { assertEquals(it, Verdict.COMMAND, WikiRoute.check(it)) }
    }

    @Test
    fun notAQuestionOfFact() {
        listOf("Thanks", "Bonjour", "How are things", "Why", "What?", "").forEach { assertFalse(it, WikiRoute.goes(it)) }
    }

    @Test
    fun aPronounSubjectIsAFollowUpNeverTheCopys() {
        for (q in listOf("What is it?", "When was he born?", "Who was she?", "What was his name?", "Where is that?", "Who are they?",
            "C'est quoi ça?", "C'est quoi ca?", "Qui est-il?", "Quand est-il né?", "Qui était-elle?", "Qu'est-ce que c'est que cela?"))
            assertFalse(q, WikiRoute.goes(q))
        for (q in listOf("What is a fjord?", "Who was Louis Riel?", "C'est quoi un fjord?", "Qui était René Lévesque?"))
            assertEquals(q, Verdict.FACT, WikiRoute.check(q))
    }

    @Test
    fun whatIsSoNowIsFresh() {
        for (q in listOf("Who is the current prime minister of Canada?", "Who is currently the mayor of Halifax?", "Who runs Apple these days?",
            "Is Leonard Cohen still alive?", "Who leads the NDP now?", "Qui est le premier ministre actuel du Canada?",
            "Qui est actuellement le maire de Montréal?", "Qui dirige le parti en ce moment?", "Qui est présentement la gouverneure générale?",
            "Qui est la mairesse de Québec de nos jours?"))
            assertEquals(q, Verdict.FRESH, WikiRoute.check(q))
    }

    /**
     * Every question the two reviews of feature/fix-wiki-phone-nouns tried, phone and fact, English and French, with the verdict
     * it gets. A comment marks a row whose verdict isn't the one its question deserves: a trade-off a review accepted, or left.
     */
    @Test
    fun theReviewsQuestionTable() {
        val wrong = REVIEWS_QUESTIONS.mapNotNull { (q, want) -> WikiRoute.check(q).let { v -> "$q: $v, want $want".takeIf { v != want } } }
        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * The table again as said (fix-wiki-phone-nouns-d). A spoken question reaches [WikiRoute.check] as the speech model
     * wrote it, trimmed and nothing more (SherpaSpeechToText.decode, FinalWords.pick, PanelController's onFinal, sendSpoken,
     * send(fromVoice = true)): Parakeet's own capitals and accents, the same as typed. What can differ is its punctuation:
     * no space before « ? » or « ! », ’ for ', and a full stop at the end, or nothing.
     */
    @Test
    fun theReviewsQuestionTableSpoken() {
        val wrong = REVIEWS_QUESTIONS.flatMap { (q, want) -> spokenForms(q).mapNotNull { s -> WikiRoute.check(s).let { v -> "$s: $v, want $want".takeIf { v != want } } } }
        assertEquals("", wrong.joinToString("\n"))
    }

    private fun spokenForms(q: String): List<String> {
        val said = q.replace(Regex("[\\s\u00A0\u202F]+(?=[?!])"), "").replace('\'', '’')
        return listOf(said.replace(Regex("[?!]$"), "."), said.trimEnd('?', '!'))
    }
}

private val F = Verdict.FACT
private val P = Verdict.PERSONAL
private val O = Verdict.OTHER

/** WikiRouteTest.theReviewsQuestionTable's rows. */
private val REVIEWS_QUESTIONS: List<Pair<String, Verdict>> = listOf(
    // review-fix-wiki-phone-nouns-b: phone questions
    "Which of the apps crashed?" to P,
    "Which of the apps needs an update?" to P,
    "What was the phone bill last month?" to P,
    "What was the hydro bill in October?" to P,
    "What's the grocery list today?" to P,
    "What was the bill last month?" to P,
    "What's the shopping list app called?" to P,
    "Which one of the alarms goes off first?" to P,
    "What's in the note from yesterday?" to P,
    "What's on the list for Costco?" to P,
    "What's the code in the text from Alex?" to P,
    "What's the total on the phone bill?" to P,
    "Which of the lists has eggs?" to P,
    "What's the grocery list now?" to P,
    "What was the text at noon?" to P,
    "How much was the hydro bill?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a sur la liste de courses ?" to P,
    "Qu'est-ce qu'il y a sur la liste des courses ?" to O, // deserves P, left (third review): PHONE_THING_ON keeps out « des », not the lead; « liste de courses » is P
    "Qu'est-ce qu'il y a dans la note de Marie ?" to O, // deserves P, left (third review): PHONE_THING_ON keeps out « de » + a capital, not the lead; « note d'hier » is P
    "Qu'est-ce qu'il y a dans la note d'hier ?" to P,
    "Quel est le total sur la facture de cellulaire ?" to P,
    "Quel est le total sur la facture d'Hydro ?" to P,
    "Qu'est-ce qui utilise autant de batterie ?" to P,
    "Qu'est-ce qui consomme trop de données ?" to P,
    "Qu'est-ce qui prend toute la place ?" to P,
    "Quel est le montant de la facture d'Hydro ?" to F, // left: the thing two words in
    "Quelle est la liste d'épicerie aujourd'hui ?" to P,
    "Quelle est la liste d'épicerie ?" to P,
    "What's the list say?" to P,
    "Quel jeu utilise autant de batterie ?" to P,
    "Quel jeu consomme trop de données ?" to P,
    "Which game is using so much battery?" to P,
    "Quels sont les articles sur la liste de courses ?" to P,
    "What's the text message from Mom?" to P,
    "What was the last text from Mom?" to P,
    "What's the note about the gate code?" to P,
    "Which of the reminders comes next?" to P,
    "What's the Costco list?" to P,
    "What's the store list?" to P,
    "What's the hydro bill in october?" to P,
    "Quelle est la date sur la facture de téléphone ?" to P,
    // review-fix-wiki-phone-nouns-b: questions of fact
    "What's on the flag of Japan?" to F,
    "What's in a hurricane?" to F,
    "Qu'est-ce qu'il y a sur le drapeau du Québec ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Who is on the dollar bill?" to F,
    "Whose face is on the dollar bill?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "What is written on the dollar bill?" to F,
    "Who is on the hundred dollar bill?" to F,
    "What animals are on the endangered list?" to F,
    "Which species are on the endangered species list?" to F,
    "Which countries are on the grey list?" to F,
    "Who is on the sanctions list?" to F,
    "What's on the UNESCO list?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What is on the list of UNESCO sites?" to F,
    "What was in the Arecibo message?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What is in the message from Arecibo?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What was in the Zimmermann note?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What is in the farm bill?" to F,
    "What was decided in the Yalta meeting?" to P, // a name before the thing, left as the Forbes list (review b L2)
    // a name before the thing, left as the Forbes list (review b L2)
    "What happened in the first contact between Europeans and the Mi'kmaq?" to P,
    "What's new in the Bluetooth 5 standard?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What's in the Bill of Rights?" to F,
    "What's on the periodic table?" to F,
    "Which planets are in the solar system?" to F,
    "What's in the Treaty of Versailles?" to F,
    "What came from the Columbian exchange?" to F,
    "Which words come from the Latin language?" to F,
    "What famous line is from the Gettysburg Address?" to F,
    "What songs are on the album Thriller?" to F,
    "Who is in the Hockey Hall of Fame?" to F,
    "What's inside the Great Pyramid?" to F,
    "What takes up the most space in the universe?" to F,
    "Which organ takes up the most space in the human body?" to F,
    "What takes up a lot of space in a cell?" to F,
    "Which country uses a lot of oil?" to F,
    "What's in the JFK file?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What is the text on the Rosetta Stone?" to P, // H1's cost, accepted (review b)
    "What's on the menu in Japan?" to F,
    "Who was in the Beatles?" to F,
    "Which bones are in the human hand?" to F,
    "Who is on the polymer note?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "What is on the back of the dollar bill?" to F,
    "Who signed the note in the Dreyfus affair?" to F,
    "Qui est sur le billet de vingt dollars ?" to F,
    "Quels animaux sont sur la liste rouge ?" to F,
    "Quels pays sont sur la liste noire de l'Union européenne ?" to F,
    "Qu'est-ce qu'il y a dans le message d'Arecibo ?" to P, // a name before the thing, left as the Forbes list (review b L2)
    "Quelles espèces sont sur la liste des espèces menacées ?" to F,
    // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a dans la Charte des droits et libertés ?" to O,
    "Qu'est-ce qu'il y a dans un trou noir ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qui prend le plus de place dans l'univers ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Quel organe prend le plus de place dans le corps humain ?" to F,
    "Qu'est-ce qui prend le plus de place dans une cellule ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Pourquoi le hockey prend autant de place au Canada ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Quelle est l'histoire du Bluetooth ?" to F,
    "Quelle est la portée du Bluetooth ?" to F,
    "Qui est l'inventeur du wifi ?" to F,
    "D'où vient le nom du Bluetooth ?" to P, // « nom du » stays the phone's, as « le nom du wifi »
    "Quelle est la vitesse maximale du wifi 6 ?" to F,
    "Qu'est-ce qu'il y a dans le traité de Versailles ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qui est dans le Temple de la renommée du hockey ?" to F,
    "Qu'est-ce qu'il y a sur la Lune ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'y a-t-il dans la liste de Schindler ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a sur les billets de banque canadiens ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a dans la Déclaration d'indépendance ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a sur la liste grise du GAFI ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a dans le message de Fatima ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Qu'est-ce qu'il y a dans le message secret de Fatima ?" to O, // older: no lead or « il » read as a pronoun, no copy either way
    "Quels oiseaux sont sur la liste rouge du Québec ?" to F,
    "What is the history of Bluetooth?" to F,
    "What is the range of Bluetooth?" to F,
    "What is the app economy?" to P, // H1's cost, accepted (review b)
    "What was the first SMS message?" to F,
    "Qui est sur la liste de Schindler ?" to F,
    "Quelles espèces sont sur la liste de l'UICN ?" to F,
    // review-fix-wiki-phone-nouns-b: either answer will do
    "Why is Chrome using so much memory?" to P,
    "Pourquoi la religion prend autant de place au Québec ?" to O,
    "What is the message in animal farm?" to P,
    // review-fix-wiki-phone-nouns: phone questions
    "What's on the grocery list?" to P,
    "What's on the list?" to P,
    "What's in the note about the gate code?" to P,
    "What's in the text from Mom?" to P,
    "What's on the bill?" to P,
    "What's in the screenshot?" to P,
    "What's in the email from Alex?" to P,
    "What's the code in the note?" to P,
    "Who sent the text?" to F, // first review: left
    "What's the total on the receipt?" to P,
    "When is the Nova Scotia Power bill due?" to F, // first review: left
    "When is the hydro bill due?" to P,
    "Where is the file?" to P,
    "What's using so much battery?" to P,
    "What's taking up so much space?" to P,
    "What is using a lot of data?" to P,
    "What's using the most power?" to F, // first review: left
    "What's using more battery than usual?" to F, // first review: left
    "What's killing the battery?" to P,
    "Which game takes up the most space?" to P,
    "Which game is the biggest?" to F, // first review: left
    "What is the biggest app?" to P,
    "What uses battery in the background?" to P,
    "Quel jeu prend le plus de place?" to P,
    "Quel jeu utilise le plus de batterie?" to P,
    "Qu'est-ce qui prend toute la place?" to P,
    "C'est quoi la liste d'épicerie?" to P,
    "C'est quoi le code dans la note?" to P,
    "Quel est le total sur la facture?" to P,
    "Quelle appli prend autant de place?" to P,
    "Qui a envoyé le texto?" to O, // first review: left
    "Quel est le mot de passe du wifi?" to P,
    "Quelle est la batterie qui reste?" to P,
    "Quel est le niveau de la batterie?" to P,
    "Combien d'espace reste-t-il?" to O, // first review: left
    // review-fix-wiki-phone-nouns: questions of fact
    "What is the Bill and Melinda Gates Foundation?" to F,
    "What was the GI Bill?" to P, // first review's L1, rare, accepted
    "What is the Quebec Bill 21?" to F,
    "What is Bill 101?" to F,
    "What is the Bill of Rights?" to F,
    "What is the App Store?" to F,
    "What was the first text message?" to F,
    "What was the first email ever sent?" to F,
    "When was first contact with the Mi'kmaq?" to P, // first review's L1, rare, accepted
    "What was the first contact between Europeans and the Mi'kmaq?" to P, // first review's L1, rare, accepted
    "What is the setting for The Hobbit?" to P, // first review's L1, rare, accepted
    "What is the message in Animal Farm?" to F,
    "What is the NTFS file system?" to F,
    "What is The Bucket List about?" to P, // first review's L1, rare, accepted
    "What is the Forbes list?" to P, // first review's L1, rare, accepted
    "What is the farm bill?" to P, // first review's L1, rare, accepted
    "Which bill passed the Senate?" to P, // first review's L1, rare, accepted
    "What uses more memory, Chrome or Firefox?" to F,
    "What uses the most memory, Chrome or Firefox?" to P, // first review's L1, rare, accepted
    "What takes up space in a cell?" to F,
    "What takes up the most space in a plant cell?" to F,
    "What takes up most of the space in a cell?" to F,
    "What is anything that takes up space and has mass?" to O,
    "Which countries are using space for weapons?" to P, // first review's L1, rare, accepted
    "Which sorting algorithm uses the most memory?" to P, // first review's L1, rare, accepted
    "Quels sont les paramètres d'une loi normale?" to F,
    "Quelle est la note la plus haute d'un piano?" to P, // first review's L1, rare, accepted
    "Quelle planète occupe le plus d'espace?" to P, // first review's L1, rare, accepted
    "What is the text of the Charter?" to F,
    "What is the musical note A?" to P, // first review's L1, rare, accepted
    "Which calendar did the Romans use?" to F,
    "What is the best contact lens?" to F,
    "Who wrote The Bucket List?" to P, // first review's L1, rare, accepted
    // review-fix-wiki-phone-nouns-c: fresh questions (fresh.tsv), phone ones past DRAIN's and PHONE_THING_IN's exclusions
    "What's taking up so much space in the gallery?" to P,
    "What's taking up so much space in the downloads folder?" to P,
    "Which app takes up the most space in the internal storage?" to P,
    "What's using the most space in the SD card?" to P,
    "Which video takes up the most space in the photos?" to P,
    "What's hogging all the space in the cloud?" to P,
    "Which folder takes up the most space in the Files app?" to P,
    "What's eating up space in the camera roll?" to P,
    "What's taking up so much space in the Pixel?" to P,
    "Which chat takes up the most space in the WhatsApp folder?" to P,
    "What's taking up space in the storage?" to P,
    "What's using so much space in the photo gallery?" to P,
    "Which game takes up the most space in the cell phone?" to P,
    "What's taking up the most space in the drive?" to P,
    "Quel jeu prend le plus de place en ce moment ?" to P,
    "Quel jeu prend le plus de place au total ?" to P,
    "Quel jeu prend autant de place dans le cell ?" to P,
    "Quel jeu prend le plus de place dans la carte SD ?" to P,
    "Quelle vidéo prend le plus de place dans la galerie ?" to P,
    "Quel dossier prend le plus de place dans les téléchargements ?" to P,
    "Quel jeu prend trop de place dans le Pixel ?" to P,
    "Quelle conversation prend le plus de place dans WhatsApp ?" to P,
    "Quel jeu prend le plus de place dans l'espace de stockage ?" to P,
    "Quel jeu prend le plus de place en mémoire ?" to P,
    "Quel jeu prend le plus de place dans le téléphone ?" to P,
    "Quel fichier prend le plus de place dans le nuage ?" to P,
    "Quelle série prend beaucoup de place en ce moment ?" to P,
    "Quel jeu prend autant de place dans le tél ?" to P,
    "Quelles photos prennent le plus de place dans les fichiers ?" to P,
    "Quel jeu prend le plus de place dans le stockage ?" to P,
    "What was the hydro bill 2 months ago?" to P,
    "What was the text 10 minutes ago?" to P,
    "What was the phone bill 3 months ago?" to P,
    "What was the bill 6 weeks ago?" to P,
    "When is the contact lens appointment?" to P,
    "What's the grocery list in Keep?" to P,
    "What was the last text in WhatsApp?" to P,
    "What's the latest email in Gmail?" to P,
    "What's the last message in Signal?" to P,
    "Quelle est la liste de courses ?" to F, // trade-off left (third review): deserves P, de
    "C'est quoi le message de maman ?" to F, // trade-off left (third review): deserves P, de
    "Quelle est l'adresse dans le courriel de Paul ?" to P,
    "Quel est le code dans le texto de Julie ?" to F, // trade-off left (third review): deserves P, de + capital
    "Quelle est la note de Marie ?" to F, // trade-off left (third review): deserves P, de + capital
    "Quel est le dernier message dans WhatsApp ?" to P,
    "Quelle est la facture d'électricité ce mois-ci ?" to P,
    "Quelle était la facture il y a 2 mois ?" to P,
    "Quel est le total sur la facture d'électricité ?" to P,
    "Which planet takes up the most space in the solar system?" to F,
    "Which organ takes up the most space in our body?" to P, // trade-off left (third review): deserves F, space in our
    "What takes up the most space in landfills?" to P, // trade-off left (third review): deserves F, space in
    "Which country takes up the most space on the map?" to P, // trade-off left (third review): deserves F, space on
    "What takes up the most space in the memory of a computer?" to P, // trade-off left (third review): deserves F, space in the memory
    "What takes up the most space in the atmosphere?" to F,
    "Which element takes up the most space in the Earth's crust?" to F,
    "What takes up space and has mass?" to F,
    "Which country uses the most data in the world?" to P, // trade-off left (third review): deserves F, data
    "What takes up most of the space in an atom?" to F,
    "Which organ takes up the most space in the chest?" to F,
    "Which sport takes up the most space in the newspaper?" to F,
    "Quel pays prend le plus de place sur la carte du monde ?" to P, // trade-off left (third review): deserves F, place sur
    "Quelle planète prend le plus de place dans notre système solaire ?" to P, // trade-off left (third review): deserves F, place dans notre
    "Quel organe prend le plus de place dans notre corps ?" to P, // trade-off left (third review): deserves F, place dans notre
    "Quel sport prend le plus de place à la télévision ?" to P, // trade-off left (third review): deserves F, place à la
    "Quelle langue prend le plus de place sur Internet ?" to P, // trade-off left (third review): deserves F, place sur
    "Quel meuble prend le plus de place dans un salon ?" to F,
    "Quel organe prend le plus de place dans l'abdomen ?" to F,
    "Quel animal prend le plus de place dans l'océan ?" to F,
    "Quelle planète prend le plus de place dans le système solaire ?" to F,
    "Quel pays prend le plus de place en Afrique ?" to F,
    "Quel pays prend le plus de place au monde ?" to F,
    "Quelle religion prend le plus de place au Liban ?" to F,
    "Quel pays prend le plus de place sur Terre ?" to P, // trade-off left (third review): deserves F, place sur
    "Quel organe prend le plus de place dans la cage thoracique ?" to F,
    "Quel arbre prend le plus de place dans la forêt boréale ?" to F,
    "Quel sport prend autant de place dans la culture québécoise ?" to F,
    "What is the contact lens made of?" to F,
    "What is the best camera lens for portraits?" to F,
    "What's the contact lens market worth?" to F,
    "What is the Bill 21 in Quebec?" to F,
    "What was the Bill C-11 about?" to P, // trade-off left (third review): deserves F, bill + letter
    "Which bill in Nova Scotia banned plastic bags?" to P, // trade-off left (third review): deserves F, in + Nov
    "Which bill in Ontario raised the minimum wage?" to P, // trade-off left (third review): deserves F, in + capital
    "What was the meeting in Marrakesh about?" to P, // trade-off left (third review): deserves F, in + Mar
    "What was the message in Marconi's first radio transmission?" to F,
    "What is the message in Mark's gospel?" to F,
    "What is the message in Monet's paintings?" to F,
    "What was the meeting in Munich in 1938?" to P, // trade-off left (third review): deserves F, in + capital
    "What is the app 2048?" to F,
    "What was the text of Bill 101?" to F,
    "Who was on the guest list at the royal wedding?" to P, // trade-off left (third review): deserves F, on the X list
    "Who was on the passenger list of the Titanic?" to F,
    "Who is on the Canadian twenty dollar bill?" to F,
    "Which animals are on the IUCN Red List?" to F,
    "Qui est sur le billet de cinq dollars ?" to F,
    "Quels pays sont sur la liste verte ?" to F,
    "Qu'est-ce que la liste de Forbes ?" to F,
    "Quels joueurs sont sur la liste des blessés ?" to F,
)
