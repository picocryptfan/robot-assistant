// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class ClaudeAppTest {
    private val W = AppHandOff.Way.entries

    /** Every combination of a, b, c resolving (d, the launch intent, resolves whenever the app is installed). */
    @Test fun routeChooserTakesTheFirstThatResolves() {
        for (mask in 0 until 8) {
            val a = mask and 1 != 0
            val b = mask and 2 != 0
            val c = mask and 4 != 0
            val ok = mapOf(AppHandOff.Way.APP_LINK to a, AppHandOff.Way.WEB_LINK to b, AppHandOff.Way.SEND to c, AppHandOff.Way.LAUNCH to true)
            val expected = when {
                a -> AppHandOff.Way.APP_LINK
                b -> AppHandOff.Way.WEB_LINK
                c -> AppHandOff.Way.SEND
                else -> AppHandOff.Way.LAUNCH
            }
            assertEquals("a=$a b=$b c=$c", expected, ClaudeAppLink.choose(true) { ok.getValue(it) })
            // Not installed: nothing resolves to it, "Install Claude".
            assertNull(ClaudeAppLink.choose(false) { true })
        }
        // Installed but not even a launch intent (disabled): nothing.
        assertNull(ClaudeAppLink.choose(true) { false })
        assertEquals(4, W.size)
    }

    @Test fun questionIsEncodedRfc3986() {
        val q = "Is 2+2=4 & why? 100% #sure ~ok_-. é 😀\nnew line"
        val e = ClaudeAppLink.encode(q)
        assertEquals("Is%202%2B2%3D4%20%26%20why%3F%20100%25%20%23sure%20~ok_-.%20%C3%A9%20%F0%9F%98%80%0Anew%20line", e)
        assertTrue(e.none { it == '+' || it == ' ' || it == '&' || it == '#' || it == '?' })
        // Decoded once, as any URI parser does, it is the question again (a "+" stays a plus).
        assertEquals(q, URLDecoder.decode(e.replace("+", "%2B"), "UTF-8"))
        val link = ClaudeAppLink.appLink(q)
        assertTrue(link.startsWith("claude://claude.ai/new?q="))
        assertEquals(q, URI(link).query.removePrefix("q="))
        assertEquals(q, URI(ClaudeAppLink.webLink(q)).query.removePrefix("q="))
        assertEquals("", ClaudeAppLink.encode(""))
    }

    @Test fun capAtTwoThousandOnASentence() {
        val sentence = "This sentence is exactly fifty characters long ok. "
        val long = sentence.repeat(60)
        val cut = AppHandOff.cap(long)
        assertTrue(cut.length <= AppHandOff.MAX_CHARS)
        assertTrue(cut.endsWith("ok."))
        // No sentence end: a space; never half an emoji.
        val words = "word ".repeat(600)
        assertTrue(AppHandOff.cap(words).let { it.length <= 2000 && it.endsWith("word") })
        val emoji = "a".repeat(1999) + "😀😀"
        val e = AppHandOff.cap(emoji)
        assertTrue(e.length <= 2000 && !Character.isHighSurrogate(e.last()))
        assertEquals("short", AppHandOff.cap("  short "))
        // The link carries the cut question.
        assertEquals(cut, URI(ClaudeAppLink.appLink(long)).query.removePrefix("q="))
    }

    @Test fun summaryOfTheConversationSoFar() {
        val turns = listOf(
            ChatTurn("1", TurnRole.USER, "how long do I boil an egg", 0),
            ChatTurn("2", TurnRole.ASSISTANT, "About **9 minutes** for hard-boiled. Start in cold water.", 0),
            ChatTurn("3", TurnRole.USER, "go get Claude", 0),
        )
        assertEquals("I asked my phone's assistant: “how long do I boil an egg”. It said: “About 9 minutes for hard-boiled”. Can you help with that?",
            AppHandOff.summary(turns))
        // A reply built on the calendar, notes or contacts is not quoted.
        val read = listOf(ChatTurn("1", TurnRole.USER, "what's tomorrow", 0),
            ChatTurn("2", TurnRole.ASSISTANT, "Dentist at 3.", 0, actions = listOf(ActionRecord(ToolName.READ_CALENDAR, "x", ActionStatus.DONE, content = true))))
        assertEquals("I asked my phone's assistant: “what's tomorrow”. Can you help with that?", AppHandOff.summary(read))
        assertNull(AppHandOff.summary(emptyList()))
        assertNull(AppHandOff.summary(listOf(ChatTurn("1", TurnRole.USER, "go get Claude", 0))))
        // One to three sentences.
        assertTrue(AppHandOff.summary(turns)!!.split(Regex("[.?](?=\\s|$)")).filter { it.isNotBlank() }.size <= 3)
    }

    @Test fun answerAfterTheHandedOffQuestion() {
        val nodes = listOf("Claude", "New chat", "Why is the sky blue?",
            "The sky looks blue because air molecules scatter short blue wavelengths of sunlight much more than red ones.",
            "At sunset the light crosses more air, so the blues scatter away and reds remain.",
            "Copy", "Retry", "Reply to Claude", "Claude can make mistakes. Please double-check responses.", "Opus 5.5")
        val a = ClaudeAppAnswer.extract(nodes, asked = "Why is the sky blue?")!!
        assertTrue(a.startsWith("The sky looks blue") && a.endsWith("reds remain."))
        assertTrue(!a.contains("Copy") && !a.contains("mistakes"))
    }

    @Test fun answerWithoutKnowingTheQuestion() {
        val nodes = listOf("Chats", "What's a good name for a grey cat?",
            "Here are a few ideas: Smokey, Ash, Pebble or Earl Grey.",
            "If you want something funny, Sir Fluffington works too.",
            "Copy", "Reply to Claude", "12:41")
        val a = ClaudeAppAnswer.extract(nodes)!!
        assertEquals("Here are a few ideas: Smokey, Ash, Pebble or Earl Grey.\n\nIf you want something funny, Sir Fluffington works too.", a)
        // The newest exchange wins over an older, longer answer further up.
        val older = listOf("Old question?", "x".repeat(10) + " an older long answer that is quite long indeed and goes on and on for a good while.") + List(30) { "Copy" } + nodes
        assertTrue(ClaudeAppAnswer.extract(older)!!.startsWith("Here are a few ideas"))
    }

    @Test fun anAnswerThatNamesAModelIsKept() {
        val nodes = listOf("Claude", "Which model should I use?",
            "Claude Opus 5.5 is the most capable, and Sonnet 5.5 costs half as much for most everyday work.",
            "Haiku 5.5 is the fastest of the three.",
            "Copy", "Reply to Claude", "Sonnet 5.5 · Extended", "Opus 5.5")
        assertEquals("Claude Opus 5.5 is the most capable, and Sonnet 5.5 costs half as much for most everyday work.\n\nHaiku 5.5 is the fastest of the three.",
            ClaudeAppAnswer.extract(nodes, asked = "Which model should I use?"))
    }

    @Test fun nothingUsable() {
        assertNull(ClaudeAppAnswer.extract(emptyList()))
        assertNull(ClaudeAppAnswer.extract(listOf("Claude", "New chat", "Copy", "Reply to Claude", "Opus 5.5", "Claude Sonnet 5.5", "Haiku 5.5 · Extended")))
        // A secure window gives no text at all.
        assertNull(ClaudeAppAnswer.extract(listOf("", "  ")))
        assertNull(ClaudeAppAnswer.extract(listOf("Hi", "OK", "Settings")))
        // The question is found but nothing came after it yet (still answering, or not sent).
        assertNull(ClaudeAppAnswer.extract(listOf("Claude", "Why is the sky blue?", "Reply to Claude"), asked = "Why is the sky blue?"))
    }
}
