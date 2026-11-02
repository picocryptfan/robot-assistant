// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F37: the answer to "What should the text to Mom say?" goes to the text. After Messages' echo check and a running
 * conversation, no router in the panel's chain gets those words; a new router has to say how it treats them.
 */
class TextingAnswerGateTest {
    private val panel = File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText()

    /** The words between the turn's start and the reply language: where the routers are asked. */
    private val chain = panel.substringAfter("val textingFor = askingTextFor").substringBefore("turnLangs = decideLangs(q")

    @Test fun everyRouterAfterTheEchoAndConversationChecksWaits() {
        val routers = Regex("""(?:val|var) (\w+) = if \(q\.isNotEmpty\(\)([^\n]*)""").findAll(chain).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("found the chain", routers.size >= 12)
        for ((name, rest) in routers) {
            if (name == "msgCmd" || name == "convCmd") continue
            assertTrue("$name leaves a text's answer alone", "!textWaits" in rest || "!hfWaits" in rest)
        }
        assertTrue("!hfWaits" in Regex("""val everydayOpen = q\.isNotEmpty\(\)[^\n]*""").find(chain)!!.value)
        assertTrue("val hfWaits = textWaits ||" in chain)
    }

    @Test fun aHelperDeclineAndTheScreenWaitToo() {
        assertTrue(Regex("""if \(q\.isNotEmpty\(\) && !textWaits && memRoute == null[^\n]*\n\s*HelperRouter\.declines""").containsMatchIn(chain))
        val screen = panel.substringAfter("if (app.heyrobot.core.ScreenQuestion.asks(q)").substringBefore("listOf(msgCmd")
        assertTrue("textingFor == null" in screen)
    }
}
