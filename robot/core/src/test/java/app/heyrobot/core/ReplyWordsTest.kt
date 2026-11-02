// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyWordsTest {
    @Test fun aToolIsNeverNamed() {
        for ((said, plain) in listOf(
            "You can free up storage using the `free_storage` tool." to "You can free up storage.",
            "You can use the free_storage tool to clear some space." to "You can clear some space.",
            "I can do that with set_timer." to "I can do that.",
            "The `free_storage` tool can help." to "That can help.",
            "Ask me and free_storage will run." to "Ask me and that will run.",
            "The weather tool needs the internet." to "That needs the internet.",
            "I'd use `calculate` to work it out." to "I'd work it out.",
            "Try the free_up_space function." to "Try that.",
            "That runs skill_tea_time." to "That runs that.",
        )) assertEquals(said, plain, ReplyWords.scrub(said))
    }

    @Test fun inFrench() {
        assertEquals("Tu peux libérer de l'espace.", ReplyWords.scrub("Tu peux libérer de l'espace en utilisant l'outil `free_storage`.", Lang.FR))
        assertEquals("Tu peux libérer de l'espace.", ReplyWords.scrub("Tu peux utiliser l'outil free_storage pour libérer de l'espace.", Lang.FR))
        assertEquals("Ça aide.", ReplyWords.scrub("`free_storage` aide.", Lang.FR))
    }

    @Test fun whatIsNoToolStays() {
        for (s in listOf(
            "Check the weather before you go.", "I can call Mom for you.", "A hammer is a useful tool.", "Use `my_var` in your script.",
            "What's on your calendar today?", "Remember to set a timer.", "The file is notes_2026.txt.",
        )) assertEquals(s, s, ReplyWords.scrub(s))
    }

    /** A reply that is a call written as text is for ToolCalls (text-call mode): it stays whole. */
    @Test fun aCallWrittenAsTextStays() {
        val call = "call:set_timer{seconds:60}"
        assertEquals(call, ReplyWords.scrub(call))
    }
}
