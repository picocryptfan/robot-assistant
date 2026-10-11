package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Not Claude": ClaudeRouter.declines, the words that keep a conversation with Robot (0.3.1). */
class ClaudeDeclineTest {
    private fun yes(vararg s: String) = s.forEach { assertTrue(it, ClaudeRouter.declines(it)) }
    private fun no(vararg s: String) = s.forEach { assertFalse(it, ClaudeRouter.declines(it)) }

    @Test fun thePhoneTestSentence() {
        // 0.3.1 on the phone: typed after Robot offered Claude twice for a question about the screen.
        yes("I want you to tell me about this screen. Not claude")
        // Spoken, Parakeet may write the name as a word that only sounds like it.
        yes("I want you to tell me about this screen. Not cloud.", "I want you to tell me about this screen, not clod")
    }

    @Test fun english() {
        yes(
            "not Claude", "Not Claude.", "not Claude!", "no Claude", "No Claude please", "no Claude, just you", "Robot, not Claude",
            "I'm asking you, not Claude", "not Claude this time", "not Claude but you", "not to Claude", "not with Claude",
            "don't ask Claude", "Don't ask Claude.", "do not ask Claude", "please don't ask Claude about this", "don't check with Claude",
            "don't check with Claude on this one", "don't send it to Claude", "don't use Claude", "don't bring Claude in", "never mind Claude",
            "stop asking Claude", "no need to ask Claude", "no need to check with Claude", "no need for Claude", "I don't need Claude",
            "I don't want Claude to see this", "without Claude", "can you answer without Claude?", "without asking Claude",
            "answer without checking with Claude", "leave Claude out of it", "leave Claude out of this", "keep Claude out of it",
            "leave out Claude", "leave Claude alone", "forget Claude", "forget about Claude, you answer", "skip Claude",
            "you tell me", "You tell me.", "No, you tell me", "I don't know, you tell me", "hey robot, you tell me", "so you tell me then",
            "you answer it", "you decide", "tell me yourself", "answer it yourself", "answer yourself", "explain it yourself",
            "figure it out yourself", "do it by yourself", "think for yourself", "on your own", "On your own, please",
            "answer this on your own", "can you answer it on your own?", "tell me by yourself", "I'm asking you", "I asked you.",
        )
    }

    @Test fun french() {
        yes(
            "pas Claude", "Pas Claude, toi.", "sans Claude", "sans Claude s'il te plaît", "pas à Claude", "ne demande pas à Claude",
            "Ne demande pas à Claude.", "demande pas à Claude", "ne demande pas ça à Claude", "n'appelle pas Claude", "ne va pas chercher Claude",
            "ne vérifie pas avec Claude", "ne pas demander à Claude", "sans demander à Claude", "sans passer par Claude",
            "pas besoin de Claude", "pas besoin de demander à Claude", "laisse Claude en dehors de ça", "laisse Claude de côté",
            "laisse tomber Claude", "toi-même", "réponds toi-même", "Réponds-moi toi-même", "fais-le toi-même", "répondez vous-même",
            "réponds tout seul", "dis-le-moi toute seule",
        )
    }

    @Test fun mishearingsCountWhereOnlyAPauseOrAPoliteWordFollows() {
        for (a in ClaudeRouter.ALIASES) {
            yes("not $a", "Not $a.", "no $a please", "don't ask $a", "don't check with $a", "pas $a", "ne demande pas à $a", "leave $a out of it",
                "I want you to answer, not $a")
        }
        // A word that only sounds like the name, then more about it: storage, weather, a person.
        no("no cloud today?", "not cloud storage", "I don't need cloud storage", "don't ask Clyde to pick up milk", "don't ask Clyde if he's coming",
            "never mind cloud backups", "forget cloud sync", "don't use the cloud", "does it work without cloud?", "sans cloud",
            "is my phone not cloud-based?", "there's no cloud in the sky", "will there be no cloud tomorrow", "it's not cloud, it's local",
            "pas de cloud", "don't get Clyde wet")
    }

    @Test fun wordsAboutClaudeOrAboutRobotAreNotARefusal() {
        no(
            // About someone (or something) called Claude.
            "is it not Claude's birthday", "Claude is not available", "Claude is not here yet", "why is Claude not answering",
            "Claude did not call me back", "it's not Claude, it's Sam", "that's not Claude's job", "whether or not Claude is coming",
            "not Claude Monet, Manet", "there's no Claude in this house", "I have no Claude key yet", "don't tell Claude about the party",
            "c'est pas Claude qui a appelé", "Claude n'est pas là", "ce n'est pas Claude", "ça va pas, Claude ?", "il va pas à Claude ce pull",
            // Requests to Robot that only look like it.
            "can you tell me the time", "could you tell me a joke", "will you tell me a story", "why don't you tell me a joke",
            "tell me about yourself", "can you set alarms on your own?", "work on your own project", "did you do it yourself?",
            "don't answer yourself, ask Claude", "thank you, tell me more", "I want you to tell me a joke", "I asked you a question earlier",
            // Asking for Claude, and plain questions.
            "ask Claude why the sky is blue", "go get Claude", "Claude, what's the capital of Peru?", "thanks Claude",
            "what's the weather tomorrow", "set a timer for 5 minutes", "is it going to be cloudy or not", "", "   ",
        )
    }

    @Test fun aRefusalIsNeverAClaudeCommand() {
        // The router is anchored at the start: none of these reaches Claude, whatever the detector says.
        for (s in listOf("not Claude", "no Claude", "don't ask Claude", "please don't ask Claude", "you tell me, not Claude", "pas Claude",
            "ne demande pas à Claude", "without asking Claude", "leave Claude out of it")) {
            assertNull(s, ClaudeRouter.route(s))
            assertTrue(s, ClaudeRouter.declines(s))
        }
        // Asking for Claude again is a command and no refusal (the panel clears its "not Claude" then).
        for (s in listOf("ask Claude about this screen", "go get Claude", "ask cloud about black holes", "demande à Claude pourquoi le ciel est bleu")) {
            assertTrue(s, ClaudeRouter.route(s) is ClaudeCommand.Ask || ClaudeRouter.route(s) is ClaudeCommand.Join)
            assertFalse(s, ClaudeRouter.declines(s))
        }
        assertEquals(Attachment.SCREEN, (ClaudeRouter.route("ask Claude about this screen") as ClaudeCommand.Ask).extra)
    }
}
