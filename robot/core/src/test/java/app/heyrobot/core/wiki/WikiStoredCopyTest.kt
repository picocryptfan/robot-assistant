// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-wiki-rc1-b: on 1.4.0-rc1 a copy installed on the phone never answered "who was Louis Riel". PanelWiki's checks, in
 * its order, on a copy stored as WikiStore.install stores it (wikipedia.json written by withInstalled, the file at its
 * target), a fresh conversation and the question as typed (lower case, no question mark): each says yes and the turn
 * reaches the copy's open (WikiLink.connect's seam). The bind itself: WikiLinkTest.
 */
class WikiStoredCopyTest {
    private val files: File = Files.createTempDirectory("wiki-stored").toFile().apply { deleteOnExit() }

    private fun install(s: WikiSet): WikiFiles {
        val json = File(files, "robot/${WikiFilesCodec.FILE}").apply { parentFile.mkdirs() }
        json.writeText(WikiFilesCodec.encode(WikiDownload.withInstalled(WikiFilesCodec.decode(null), s)))
        WikiDownload.target(files, s).apply { parentFile.mkdirs() }.writeBytes(ByteArray(16))
        return WikiFilesCodec.decode(json.readText())
    }

    private fun reaches(question: String, s: WikiSet) {
        val copies = WikiDownload.copies(files, install(s))
        assertEquals(listOf(s), copies.map { it.first })
        val chat = listOf(ChatTurn("q", TurnRole.USER, question, 0))
        val untrusted = TurnRules.untrustedForCopy(chat)
        assertNull(WikiTurn.refusal(question, copies.size, attached = false, helperMode = false, untrusted = untrusted))
        var opened: String? = null
        runBlocking {
            WikiTurn.run(question, copies.size, { lang -> opened = lang; emptyList() }, ZonedDateTime.now(), emptyList(), spoken = false,
                untrusted = untrusted) { null }
        }
        assertEquals(s.lang, opened)
    }

    @Test fun anEnglishQuestionReachesTheStoredCopy() = reaches("who was Louis Riel", WikiSet.EN_TOP)

    @Test fun aFrenchQuestionReachesTheStoredCopy() = reaches("qui était Louis Riel", WikiSet.FR_TOP)

    @Test fun eachRefusalIsNamed() {
        assertEquals(WikiTurn.Gate.COPIES, WikiTurn.refusal("who was Louis Riel", 0, false, false, false))
        assertEquals(WikiTurn.Gate.ROUTE, WikiTurn.refusal("set a timer", 1, false, false, false))
        assertEquals(WikiTurn.Gate.ROUTE, WikiTurn.refusal("who was Louis Riel", 1, true, false, false))
        val recalls = object : WikiTurn.Own {
            override fun names() = emptyList<app.heyrobot.core.ContactName>()
            override fun recalls(question: String) = true
        }
        assertEquals(WikiTurn.Gate.RECALLS, WikiTurn.refusal("who was Louis Riel", 1, false, false, false, recalls))
        assertTrue(WikiTurn.asks("who was Louis Riel", 1, false, false, false))
    }
}
