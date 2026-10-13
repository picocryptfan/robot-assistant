package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationTest {
    @Test fun preRouterSendsStrongSignalsToClaude() {
        assertEquals(PreRouter.Signal.REASONING, PreRouter.signal("explain why the sky is blue"))
        assertEquals(PreRouter.Signal.REASONING, PreRouter.signal("Explain how a heat pump works"))
        assertEquals(PreRouter.Signal.REASONING, PreRouter.signal("compare the iPhone and the Pixel"))
        assertEquals(PreRouter.Signal.REASONING, PreRouter.signal("walk me through it step by step"))
        assertEquals(PreRouter.Signal.REASONING, PreRouter.signal("help me plan a trip to Japan"))
        // Stories, poems and jokes stay with Robot (0.4.0); a letter or a speech still counts as writing.
        assertEquals(null, PreRouter.signal("write me a poem about autumn"))
        assertEquals(null, PreRouter.signal("write a long story about a dragon"))
        assertEquals(PreRouter.Signal.WRITING, PreRouter.signal("write me a cover letter for a barista job"))
        assertEquals(PreRouter.Signal.WRITING, PreRouter.signal("draft a speech for my sister's wedding"))
        assertEquals(PreRouter.Signal.CODE, PreRouter.signal("how do I reverse a list in Python"))
        assertEquals(PreRouter.Signal.MATH, PreRouter.signal("calculate 17% of 2,340"))
        assertEquals(PreRouter.Signal.MATH, PreRouter.signal("solve x squared minus 4 equals 0"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's the latest on the election"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("any news about the Canadiens"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's today's price of bitcoin"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what was the score of the game last night"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's the weather tomorrow"))
        val long = (1..45).joinToString(" ") { "word$it" }
        assertEquals(PreRouter.Signal.LONG, PreRouter.signal(long))
    }

    @Test fun preRouterIsConservative() {
        // Local actions never escalate, whatever else the words say.
        assertNull(PreRouter.signal("set a timer for 10 minutes"))
        assertNull(PreRouter.signal("set an alarm for 7"))
        assertNull(PreRouter.signal("draft an email to Alex about the latest plan"))
        assertNull(PreRouter.signal("write me a note: buy coffee"))
        assertNull(PreRouter.signal("text Mom the score"))
        assertNull(PreRouter.signal("plan my week in the calendar"))
        assertNull(PreRouter.signal("open the weather app"))
        assertNull(PreRouter.signal("turn on the flashlight"))
        assertNull(PreRouter.signal("navigate to the latest restaurant I saved"))
        // Everyday questions stay with Robot.
        assertNull(PreRouter.signal("what's the capital of France"))
        assertNull(PreRouter.signal("tell me a joke"))
        assertNull(PreRouter.signal("what's the area code for Montreal"))
        // Codes, news and scores from everyday life.
        assertNull(PreRouter.signal("what's the door code"))
        assertNull(PreRouter.signal("what's the wifi code"))
        assertNull(PreRouter.signal("what's the gate code again"))
        assertNull(PreRouter.signal("any news from my sister"))
        assertNull(PreRouter.signal("what's my credit score"))
        // In any word order.
        assertNull(PreRouter.signal("what's the code for the wifi"))
        assertNull(PreRouter.signal("what's the code for the front door"))
        assertNull(PreRouter.signal("what's the code to the garage"))
        assertNull(PreRouter.signal("what's the latest from Sarah"))
        assertNull(PreRouter.signal("any news from mom"))
        assertNull(PreRouter.signal("what's the latest with my brother"))
        assertNull(PreRouter.signal("what was the score of my exam"))
        assertNull(PreRouter.signal("what's the score on the test"))
        // The rest of the question still counts: an everyday code doesn't hide a real signal.
        assertEquals(PreRouter.Signal.CODE, PreRouter.signal("write python to change the wifi code"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what was the score of the game last night"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's the latest on the election"))
        assertEquals(PreRouter.Signal.CODE, PreRouter.signal("why does my code throw a null pointer exception"))
        assertNull(PreRouter.signal("how are you"))
        assertNull(PreRouter.signal(""))
        assertNull(PreRouter.signal((1..40).joinToString(" ") { "w$it" }))
    }

    @Test fun hedgeDetector() {
        for (s in listOf("I don't know.", "I'm not sure about that.", "I can't browse the web, so I can't say.", "As an AI, I don't have opinions.",
            "", "   ", "I can't check that.", "I don't have real-time information about prices.", "Sorry, I don't have internet access.",
            "Je ne sais pas.", "I do not know the answer to that one.")) {
            assertTrue("<$s>", Hedge.detect(s))
        }
        for (s in listOf("The capital of France is Paris.", "Timer set for 5 minutes.", "Sure! Here's a joke: why did the robot cross the road?",
            "I know a good one.", "Not sure? Try the blue one.")) {
            assertFalse(s, Hedge.detect(s))
        }
        // A long answer that hedges in passing, late, is an answer.
        val long = "Paris is the capital of France and has been for centuries. " .repeat(6) + "I'm not sure when exactly it became so."
        assertFalse(Hedge.detect(long))
    }

    /**
     * 0.4.6, from the phone ("Claude with web search", a key saved): "tell me about Donald Trump" got Robot's "I can't…"
     * and no offer. It has no signal of its own, so Robot answers first and its refusal brings Claude in (the hedge check,
     * below); the other two go to Claude before the model is asked.
     */
    @Test fun theQuestionsFromThePhone() {
        assertNull(PreRouter.signal("tell me about Donald Trump"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("What's the latest news on the Artemis program?"))
        // With the weather tool on, "weather" is Robot's, but "according to the news" still asks for today's sources.
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("What's the weather in Tokyo according to the news?", localWeather = true))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("What's the weather in Tokyo according to the news?"))
        // What Robot's model answered, as it might put it: each one brings the offer (or, Automatic, Claude).
        for (r in listOf(
            "I'm sorry, but I can't provide information about political figures.",
            "I don't have access to real-time information, so I can't tell you the latest news about the Artemis program.",
            "I'm unable to browse the internet, but I can tell you about the weather in general.",
        )) assertTrue(r, Hedge.detect(r))
    }

    /** 0.4.6: news, prices, scores and who holds an office now, in English and in Québec French. */
    @Test fun currentEventsInEnglishAndFrench() {
        for (q in listOf("any headlines this morning?", "who is the current prime minister of Canada", "who's the president of France",
            "what happened in Gaza yesterday", "any updates on the Artemis program", "is Paul McCartney still alive",
            "what are the current standings in the NHL", "what's the stock market doing today",
            "Quelles sont les dernières nouvelles sur le programme Artemis?", "C'est quoi l'actualité aujourd'hui?",
            "Les dernières nouvelles d'Artemis?", "Quel est le prix de l'essence à Montréal?", "Qui a gagné la game des Canadiens hier soir?",
            "C'est quoi le pointage du match?", "Qui est le premier ministre du Québec?", "Qu'est-ce qui se passe en Ukraine?",
            "Quel est le taux de change du dollar américain?", "C'est quoi la météo à Tokyo?")) {
            assertEquals(q, PreRouter.Signal.CURRENT_INFO, PreRouter.signal(q))
        }
        // With the weather tool on, the weather alone is Robot's, in French too.
        assertNull(PreRouter.signal("C'est quoi la météo à Tokyo?", localWeather = true))
    }

    @Test fun frenchEverydayNewsAndLocalActionsStayWithRobot() {
        for (q in listOf("As-tu des nouvelles de ma sœur?", "Des nouvelles de Marie?", "Quelle est ma cote de crédit?",
            "Mets une alarme pour les nouvelles de 6 heures", "Rappelle-moi de lire les nouvelles demain", "Ouvre l'appli de météo",
            "Raconte-moi une histoire de dragon", "Tell me a long story about a dragon", "J'aime les nouvelles chaussures")) {
            assertNull(q, PreRouter.signal(q))
        }
    }

    /**
     * 0.4.6: Robot's model refusing, or saying it can't know today's facts, the way it really puts it, in English and in
     * Québec French. Until 0.4.6 "I don't have access to real-time information", "I'm unable to…", "I can't provide…" and
     * every French refusal but "je ne sais pas" went unnoticed.
     */
    @Test fun refusalsAndOutOfDateAnswersAreHedges() {
        for (s in listOf(
            "I don't have access to real-time information.",
            "I'm sorry, but I can't browse the internet.",
            "As a large language model, I don't have access to current events.",
            "I'm unable to provide real-time updates.",
            "I cannot provide information about current political figures.",
            "I can't help with that.",
            "I'm not able to look that up.",
            "I won't be able to check the weather in Tokyo.",
            "I do not have the ability to search the web.",
            "My knowledge cutoff is in 2024, so I may not have the latest information.",
            "That's beyond my capabilities.",
            "Je n'ai pas accès à Internet.",
            "Je ne peux pas faire ça.",
            "Désolé, je peux pas t'aider avec ça.",
            "J'ai pas accès aux nouvelles en temps réel.",
            "Je ne suis pas en mesure de vérifier les dernières nouvelles.",
            "En tant qu'IA, je n'ai pas d'opinions.",
            "Chu pas capable de faire ça.",
            "Je sais pas.",
            "Mes connaissances s'arrêtent en 2024.",
        )) assertTrue("<$s>", Hedge.detect(s))
        // A long answer that ends by saying its facts may be out of date: Claude, with today's sources, can do better.
        val bio = "Donald Trump is an American businessman and politician. ".repeat(10) +
            "For the most up-to-date information, please check a reliable news source."
        assertTrue(Hedge.detect(bio))
        val bioFr = "Le programme Artemis de la NASA vise à ramener des astronautes sur la Lune. ".repeat(8) +
            "Je n'ai pas accès aux informations en temps réel, alors consulte le site de la NASA."
        assertTrue(Hedge.detect(bioFr))
    }

    @Test fun answersAreNotHedges() {
        for (s in listOf(
            "The capital of France is Paris.",
            "Real-time operating systems guarantee that tasks finish on time. They are used in cars and planes.",
            "I can't wait to hear how your trip goes! Here are three things to see in Tokyo: Senso-ji, Shibuya and the Tsukiji market.",
            "Je peux t'aider avec ça : voici la recette.",
            "Voici ce que je sais sur Artemis : la NASA prévoit d'envoyer des astronautes autour de la Lune.",
            "Les systèmes en temps réel sont utilisés dans les avions.",
        )) assertFalse(s, Hedge.detect(s))
        // A refusal deep in a long answer (a story's dialogue) is not one.
        val story = "Once upon a time a dragon lived on a mountain and watched the village below every single day. ".repeat(6) +
            "\"I can't fly today,\" said the dragon."
        assertFalse(Hedge.detect(story))
    }

    private val key = claudeSettings()
    private fun d(s: RobotSettings = key, keySaved: Boolean = true, personal: Boolean = false, locked: Boolean = false, budget: Boolean = false,
        already: Boolean = false, fromClaude: Boolean = false, declined: Boolean = false) =
        EscalationPolicy.decide(s, keySaved, personal, locked, budget, already, fromClaude, declined)

    /** Setting × key present × personal content × locked × budget reached × "not Claude". */
    @Test fun policyMatrix() {
        val settings = listOf(null, Escalation.OFF, Escalation.ASK, Escalation.AUTO)
        for (help in settings) for (keySaved in listOf(false, true)) for (personal in listOf(false, true)) for (locked in listOf(false, true))
            for (allowLocked in listOf(false, true)) for (budget in listOf(false, true)) for (declined in listOf(false, true)) {
                val s = claudeSettings(claudeEnabled = true, claudeHelp = help, claudeLocked = allowLocked)
                val got = d(s, keySaved, personal, locked, budget, declined = declined)
                val mode = help ?: if (keySaved) Escalation.AUTO else Escalation.ASK
                val expected = when {
                    // "Not Claude" in this conversation: neither asked nor offered, locked or not.
                    declined -> EscalationDecision.NONE
                    mode == Escalation.OFF -> EscalationDecision.NONE
                    // The app always needs the phone unlocked; the API only when Claude isn't allowed locked.
                    locked && (!keySaved || !allowLocked) -> EscalationDecision.NEEDS_UNLOCK
                    keySaved && mode == Escalation.AUTO && !personal && !budget -> EscalationDecision.AUTO
                    else -> EscalationDecision.OFFER
                }
                assertEquals("help=$help key=$keySaved personal=$personal locked=$locked allowLocked=$allowLocked budget=$budget declined=$declined", expected, got)
            }
    }

    @Test fun withoutAKeyEscalationAlwaysAsksFirstThroughTheApp() {
        val s = claudeSettings(claudeEnabled = true, claudeHelp = Escalation.AUTO)
        assertEquals(Escalation.ASK, ClaudeSettings.escalation(s, keySaved = false))
        assertEquals(EscalationDecision.OFFER, d(s, keySaved = false))
        // A key but the app route chosen: still asks first (only the API escalates automatically).
        assertEquals(EscalationDecision.OFFER, d(s.withClaude { it.copy(route = ClaudeRoute.APP) }, keySaved = true))
        assertEquals(EscalationDecision.AUTO, d(s.withClaude { it.copy(route = ClaudeRoute.API) }, keySaved = true))
    }

    @Test fun askClaudeOffMeansNoEscalation() {
        assertEquals(EscalationDecision.NONE, d(claudeSettings(claudeEnabled = false), keySaved = true))
        // Claude as set up before 1.0.0 (a new install has no helper): without a key Robot offers the Claude app, with one it checks by itself.
        assertEquals(EscalationDecision.NONE, d(RobotSettings(), keySaved = true))
        assertEquals(EscalationDecision.OFFER, d(claudeSettings(), keySaved = false))
        assertEquals(EscalationDecision.AUTO, d(claudeSettings(), keySaved = true))
        assertTrue(ClaudeSettings.localTool(claudeSettings(), keySaved = false))
        assertTrue(ClaudeSettings.localTool(claudeSettings(), keySaved = true))
        assertFalse(ClaudeSettings.localTool(claudeSettings(claudeHelp = Escalation.OFF), keySaved = true))
    }

    @Test fun oncePerTurnAndNeverFromClaude() {
        // The policy: a turn that already escalated, or a Claude answer, never escalates.
        assertEquals(EscalationDecision.NONE, d(already = true))
        assertEquals(EscalationDecision.NONE, d(fromClaude = true))
        assertEquals(EscalationDecision.NONE, d(fromClaude = true, personal = true))
        // The guard the panel keeps per turn: the first trigger wins, the second (a hedge after the pre-router) doesn't.
        val g = EscalationGuard()
        assertFalse(g.take(fromClaude = true))
        assertTrue(g.take(fromClaude = false))
        assertFalse(g.take(fromClaude = false))
        assertTrue(g.used)
    }

    @Test fun aRefusedClaudeIsNeverBroughtIn() {
        // The phone test's settings (a key, Automatic): asked by itself before, offered for a screen question.
        assertEquals(EscalationDecision.AUTO, d())
        assertEquals(EscalationDecision.OFFER, d(personal = true))
        assertEquals(EscalationDecision.NONE, d(declined = true))
        assertEquals(EscalationDecision.NONE, d(personal = true, declined = true))
        assertEquals(EscalationDecision.NONE, d(keySaved = false, declined = true))
        // Not even the unlock hint.
        assertEquals(EscalationDecision.NONE, d(locked = true, declined = true))
    }

    /** Whether the local model hears of ask_claude: settings, somewhere to go, "not Claude", and the question's screen or photo. */
    @Test fun askClaudeIsLeftOutForRefusalsAndScreenOrPhotoQuestions() {
        fun left(s: RobotSettings = key, keySaved: Boolean = true, reachable: Boolean = true, declined: Boolean = false, a: Attachment = Attachment.NONE) =
            AskClaudeTool.leftOut(s, keySaved, reachable, declined, a)
        assertNull(left())
        assertNull(left(keySaved = false))
        assertTrue(AskClaudeTool.offered(key, true, true, false, Attachment.NONE))
        assertEquals(ClaudeLeftOut.OFF, left(claudeSettings(claudeEnabled = false)))
        assertEquals(ClaudeLeftOut.OFF, left(claudeSettings(claudeHelp = Escalation.OFF)))
        assertEquals(ClaudeLeftOut.NOWHERE, left(reachable = false))
        assertEquals(ClaudeLeftOut.DECLINED, left(declined = true))
        assertEquals(ClaudeLeftOut.SCREEN, left(a = Attachment.SCREEN))
        assertEquals(ClaudeLeftOut.PHOTO, left(a = Attachment.PHOTO))
        // The first reason wins: a refusal says more than the picture.
        assertEquals(ClaudeLeftOut.DECLINED, left(declined = true, a = Attachment.SCREEN))
        assertEquals(ClaudeLeftOut.OFF, left(claudeSettings(claudeEnabled = false), declined = true, a = Attachment.PHOTO))
        for (s in listOf(key, claudeSettings(claudeHelp = Escalation.ASK), claudeSettings(claudeHelp = Escalation.AUTO))) for (keySaved in listOf(false, true))
            for (declined in listOf(false, true)) for (a in Attachment.entries) {
                val offered = AskClaudeTool.offered(s, keySaved, true, declined, a)
                assertEquals("$s $keySaved $declined $a", !declined && a == Attachment.NONE, offered)
            }
        assertEquals(5, ClaudeLeftOut.entries.map { it.id }.toSet().size)
    }

    /** A follow-up in a conversation that showed Robot the screen or a photo is about it too. */
    @Test fun aFollowUpIsAboutTheEarlierScreenOrPhoto() {
        fun user(a: Attachment = Attachment.NONE) = ChatTurn("u", TurnRole.USER, "q", 0, a)
        fun robot() = ChatTurn("r", TurnRole.ASSISTANT, "a", 0)
        assertEquals(Attachment.NONE, AskClaudeTool.picture(Attachment.NONE, emptyList()))
        assertEquals(Attachment.NONE, AskClaudeTool.picture(Attachment.NONE, listOf(user(), robot())))
        assertEquals(Attachment.SCREEN, AskClaudeTool.picture(Attachment.SCREEN, emptyList()))
        assertEquals(Attachment.SCREEN, AskClaudeTool.picture(Attachment.NONE, listOf(user(Attachment.SCREEN), robot(), user(), robot())))
        // The newest one counts; the question's own wins.
        assertEquals(Attachment.PHOTO, AskClaudeTool.picture(Attachment.NONE, listOf(user(Attachment.SCREEN), robot(), user(Attachment.PHOTO), robot())))
        assertEquals(Attachment.SCREEN, AskClaudeTool.picture(Attachment.SCREEN, listOf(user(Attachment.PHOTO), robot())))
        // So the phone test's follow-up keeps the same system prompt and tools as the screen question (the live conversation is reused).
        val history = listOf(user(Attachment.SCREEN), robot())
        assertEquals(ClaudeLeftOut.SCREEN, AskClaudeTool.leftOut(key, true, true, false, AskClaudeTool.picture(Attachment.NONE, history)))
    }

    /** Without ask_claude, neither the tool nor its line in the system prompt reaches the model. */
    @Test fun aTurnWithoutAskClaudeHasNoToolAndNoPromptLine() {
        val now = java.time.ZonedDateTime.of(2026, 10, 11, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Paris"))
        val core = setOf(ToolGroup.CORE)
        for ((declined, a) in listOf(true to Attachment.NONE, false to Attachment.SCREEN, false to Attachment.PHOTO, true to Attachment.SCREEN)) {
            val askClaude = AskClaudeTool.offered(key, true, true, declined, a)
            assertFalse(askClaude)
            assertFalse(ToolName.ASK_CLAUDE in ToolRouter.tools(ToolGroup.entries.toSet(), key, askClaude))
            val system = Prompt.system(now, locked = false, askClaude = askClaude)
            assertFalse(system, system.contains("ask_claude") || system.contains("Claude"))
            assertFalse(Prompt.textCallHint(ToolRouter.tools(core, key, askClaude)).contains("ask_claude"))
        }
        // A plain question still gets both.
        val askClaude = AskClaudeTool.offered(key, true, true, false, Attachment.NONE)
        assertTrue(ToolName.ASK_CLAUDE in ToolRouter.tools(core, key, askClaude))
        assertTrue(Prompt.system(now, locked = false, askClaude = askClaude).contains("call ask_claude instead of guessing"))
    }

    /** Gemma handed over without a word: unless Claude answers right away, Robot's own answer comes first. */
    @Test fun ownAnswerBeforeAnyOffer() {
        for (decision in EscalationDecision.entries) for (handedOver in listOf(false, true)) for (said in listOf(false, true)) {
            val expected = handedOver && !said && decision != EscalationDecision.AUTO
            assertEquals("$decision $handedOver $said", expected, EscalationPolicy.ownAnswerFirst(decision, handedOver, said))
        }
        // The phone test: a screen question (personal), so an offer; Gemma called ask_claude with nothing written.
        assertTrue(EscalationPolicy.ownAnswerFirst(d(personal = true), handedOver = true, said = false))
        // "Not Claude": no offer at all, but still an answer.
        assertTrue(EscalationPolicy.ownAnswerFirst(d(declined = true), handedOver = true, said = false))
        // Automatic: Claude answers, nothing to add first.
        assertFalse(EscalationPolicy.ownAnswerFirst(d(), handedOver = true, said = false))
        // The words around it.
        assertEquals("Want me to check with Claude?", ClaudeWords.OFFER)
        assertEquals("Want Claude's take as well?", ClaudeWords.OFFER_AS_WELL)
        assertEquals("I'd rather check that with Claude.", ClaudeWords.RATHER)
    }

    @Test fun defaultRouteFollowsTheKey() {
        assertEquals(ClaudeRoute.APP, ClaudeSettings.route(claudeSettings(), keySaved = false))
        assertEquals(ClaudeRoute.API, ClaudeSettings.route(claudeSettings(), keySaved = true))
        // Your choice stands either way.
        assertEquals(ClaudeRoute.APP, ClaudeSettings.route(claudeSettings(claudeRoute = ClaudeRoute.APP), keySaved = true))
        assertEquals(ClaudeRoute.API, ClaudeSettings.route(claudeSettings(claudeRoute = ClaudeRoute.API), keySaved = false))
        // Deleting the key doesn't turn Ask Claude off: the Claude app takes the questions.
        assertTrue(ClaudeSettings.enabled(claudeSettings(), keySaved = false))
        assertTrue(ClaudeSettings.enabled(claudeSettings(), keySaved = true))
        assertTrue(ClaudeSettings.enabled(claudeSettings(claudeEnabled = true), keySaved = false))
    }
}
