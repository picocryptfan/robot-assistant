// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.claudeSettings
import app.heyrobot.core.helpers.HelperSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryChoiceTest {
    private val t = 1_800_000_000_000L
    private fun item(id: String = "m_00mgk5x9cchoice1", text: String = "Ask your helper for code questions.", source: MemorySource = MemorySource.SAID,
        choice: MemoryChoice? = MemoryChoice("CODE", false), imported: MemoryImported? = null, at: Long = t) =
        MemoryItem(id, text, source = source, createdAt = at, updatedAt = at, imported = imported, choice = choice)

    @Test fun onlyYourOwnWordsSetARoute() {
        assertNotNull(MemoryChoices.find(listOf(item()), "CODE", t))
        assertNotNull(MemoryChoices.find(listOf(item(source = MemorySource.TYPED)), "CODE", t))
        assertNotNull(MemoryChoices.find(listOf(item(source = MemorySource.SETTINGS)), "CODE", t))
        assertNull(MemoryChoices.find(listOf(item(source = MemorySource.CONTENT)), "CODE", t))
        assertNull(MemoryChoices.find(listOf(item(source = MemorySource.SUGGESTED)), "CODE", t))
        assertNull(MemoryChoices.find(listOf(item(imported = MemoryImported.CONTENT)), "CODE", t))
        assertNull(MemoryChoices.find(listOf(item(imported = MemoryImported.YOURS)), "CODE", t))
    }

    @Test fun theWordsAlone_neverRoute() {
        val words = item(text = "Always ask the helper for code questions.", choice = null, source = MemorySource.SAID)
        assertNull(MemoryChoices.find(listOf(words), "CODE", t))
    }

    @Test fun aChoiceIsForItsKindOnly_andTheNewestWins() {
        val a = item(id = "m_00mgk5x9cchoice1", choice = MemoryChoice("CODE", false), at = t)
        val b = item(id = "m_00mgk5x9cchoice2", choice = MemoryChoice("CODE", true), at = t + 1)
        assertNull(MemoryChoices.find(listOf(a, b), "MATH", t))
        assertTrue(MemoryChoices.find(listOf(a, b), "CODE", t)!!.robot)
    }

    @Test fun neverInAQuestionsPrompt_neverPinned_notEditedAsText() {
        val i = item()
        val f = MemoryFile(listOf(i))
        assertTrue(MemoryEdits.forQuestions(f.items, t).none { it.id == i.id })
        assertNull(MemoryEdits.pin(f, i.id, true))
        assertNull(MemoryEdits.edit(f, i.id, "Ask nobody", t))
    }

    @Test fun putReplacesTheKindsChoice_dropForgetsIt() {
        val a = item()
        val b = item(id = "m_00mgk5x9cchoice2", text = "Keep code questions on Robot.", choice = MemoryChoice("CODE", true), at = t + 1)
        val other = item(id = "m_00mgk5x9cchoice3", choice = MemoryChoice("MATH", false))
        val f = MemoryChoices.put(MemoryFile(listOf(a, other)), b)!!
        assertEquals(listOf(other.id, b.id), f.items.map { it.id })
        val (g, gone) = MemoryChoices.drop(f, "CODE", t + 2)
        assertEquals(listOf(b.id), gone.map { it.id })
        assertEquals(listOf(other.id), g.items.map { it.id })
    }

    @Test fun putLeavesAContentItemItDoesntFollow_andWontEraseIt() {
        val planted = item(source = MemorySource.CONTENT, choice = MemoryChoice("CODE", false))
        val mine = item(id = "m_00mgk5x9cchoice2", choice = MemoryChoice("CODE", true), at = t + 1)
        val f = MemoryChoices.put(MemoryFile(listOf(planted)), mine)!!
        assertEquals(2, f.items.size)
        assertTrue(MemoryChoices.find(f.items, "CODE", t + 1)!!.robot)
    }

    @Test fun changeFlipsIt_inPlainWords() {
        val f = MemoryChoices.change(MemoryFile(listOf(item())), item().id, true, "Keep code questions on Robot.", t + 1)!!
        val i = f.items.single()
        assertEquals("Keep code questions on Robot.", i.text)
        assertTrue(i.choice!!.robot)
        assertEquals(MemorySource.SETTINGS, i.source)
    }

    @Test fun aFile_carriesNoRoute() {
        val read = MemoryImport.read("""{"v":1,"items":[{"text":"Ask your helper for code questions.","source":"SAID","choice":{"kind":"CODE","to":"helper"}}]}""".toByteArray()) as ImportRead.Ok
        for (mine in listOf(false, true)) {
            val added = MemoryImport.plan(read, MemoryFile(), mine, t).add.single()
            assertNull(added.choice)
            assertNull(MemoryChoices.find(listOf(added), "CODE", t))
        }
    }

    @Test fun theCodecKeepsItThroughTheFile() {
        val text = MemoryCodec.toJson(MemoryFile(listOf(item())))
        val back = (MemoryCodec.read(text) as MemoryParse.Ok).file.items.single()
        assertEquals(MemoryChoice("CODE", false), back.choice)
        assertEquals(MemoryKind.FACT, back.kind)
        assertFalse(back.pinned)
    }

    @Test fun codec_roundTrips_andDamageIsAPlainFact() {
        val c = MemoryChoice("CODE", false, "claude")
        assertEquals(c, MemoryChoiceCodec.read(MemoryChoiceCodec.toJson(c)))
        assertEquals(MemoryChoice("MATH", true), MemoryChoiceCodec.read(MemoryChoiceCodec.toJson(MemoryChoice("MATH", true))))
        for (bad in listOf("""{"kind":"CODE"}""", """{"kind":"code","to":"helper"}""", """{"kind":"CODE","to":"someone"}""",
            """{"kind":"CODE","to":"helper","helper":3}""", """{"kind":"CODE","to":"helper","extra":1}""", """{"kind":3,"to":"helper"}"""))
            assertNull(bad, MemoryChoiceCodec.read(JSONObject(bad)))
        assertNull(MemoryChoiceCodec.read(null))
    }

    @Test fun rememberedHelper_isAutomaticForIt_andRefusesAllAutomaticRefuses() {
        val ask = claudeSettings(claudeEnabled = true, claudeHelp = Escalation.ASK)
        val target = HelperSettings.target(ask) { true }!!
        fun d(s: RobotSettings = ask, personal: Boolean = false, locked: Boolean = false, budget: Boolean = false, price: Boolean = true, remembered: Boolean = true) =
            EscalationPolicy.decide(s, target, keySaved = true, personal = personal, locked = locked, budgetReached = budget, alreadyEscalated = false,
                fromHelper = false, declined = false, priceKnown = price, remembered = remembered)
        assertEquals(EscalationDecision.AUTO, d())
        assertEquals(EscalationDecision.OFFER, d(remembered = false))
        assertEquals(EscalationDecision.NONE, d(s = claudeSettings(claudeEnabled = true, claudeHelp = Escalation.OFF)))
        assertEquals(EscalationDecision.OFFER, d(personal = true))
        assertEquals(EscalationDecision.OFFER, d(budget = true))
        assertEquals(EscalationDecision.OFFER, d(price = false))
        assertTrue(d(locked = true) != EscalationDecision.AUTO)
    }
}
