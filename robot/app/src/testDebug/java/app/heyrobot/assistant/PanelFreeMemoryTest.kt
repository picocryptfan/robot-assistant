// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.BrainModel
import app.heyrobot.core.DoneReason
import app.heyrobot.core.FreeUp
import app.heyrobot.core.MemoryFacts
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.RecentApp
import app.heyrobot.core.TurnResult
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Free up memory" in the panel (PanelFreeMemory, from PanelController in 1.1): on the lock screen only what is free and
 * the unlock line, and no card; unlocked, Robot's own advice (what unloading its model frees only while it is loaded or
 * loading, with the loaded model's own size, else Robot's usual one) before free_memory's card, then the card's turn ends
 * the reply as it says. No test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelFreeMemoryTest {
    private var locked = false
    private var brain = BrainKeeper.State()
    private var turn = TurnResult(DoneReason.END)
    /** What the port was asked, in order: "reply <text>", "say <text>", "turn <id>", "finish <id> <reason>", "fail <id> <text>". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val free = PanelFreeMemory(object : PanelFreeMemory.Port {
        override val ctx get() = RuntimeEnvironment.getApplication()
        override fun locked() = locked
        override val brain get() = this@PanelFreeMemoryTest.brain
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) {
            assertEquals(text.render(Langs.EN), spoken.render(Langs.EN))
            calls += "reply ${text.render(Langs.EN)}"
        }
        override fun sayInto(replyId: String, text: Phrase) { calls += "say ${text.render(Langs.EN)}" }
        override suspend fun freeMemoryTurn(replyId: String): TurnResult { calls += "turn $replyId"; return turn }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
    })

    private fun size(bytes: Long) = PhoneStatus.sizeText(bytes).render(Langs.EN)

    @Test fun onTheLockScreenOnlyWhatIsFreeAndNoCard() = runBlocking {
        locked = true
        brain = BrainKeeper.State(readiness = BrainKeeper.Readiness.READY, model = BrainModel.SMARTER)
        free.run("r1")
        assertEquals(1, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply ") && calls[0].endsWith(InfoStrings.unlockToFree().render(Langs.EN)))
        assertFalse(calls[0], calls[0].contains(size(4_100_000_000L)))
    }

    @Test fun unlockedTheAdviceComesBeforeTheCardWithTheLoadedModelsSize() = runBlocking {
        brain = BrainKeeper.State(readiness = BrainKeeper.Readiness.READY, model = BrainModel.SMARTER)
        free.run("r1")
        assertEquals(3, calls.size)
        assertTrue(calls[0], calls[0].startsWith("say ") && calls[0].contains(InfoStrings.unloadingFrees("size" to PhoneStatus.sizeText(4_100_000_000L)).render(Langs.EN)))
        assertEquals(listOf("turn r1", "finish r1 END"), calls.drop(1))
        // Loading counts as loaded; a model without its own size is Robot's usual one.
        calls.clear()
        brain = BrainKeeper.State(readiness = BrainKeeper.Readiness.LOADING, model = BrainModel.OWN)
        free.run("r2")
        assertTrue(calls[0], calls[0].contains(InfoStrings.unloadingFrees("size" to PhoneStatus.sizeText(PhoneStatus.ROBOT_MODEL_BYTES)).render(Langs.EN)))
    }

    @Test fun aModelNotLoadedIsSaidSoAndTheCardsEndIsTheReplys() = runBlocking {
        brain = BrainKeeper.State(readiness = BrainKeeper.Readiness.FAILED, model = BrainModel.SMARTER)
        turn = TurnResult(DoneReason.CANCELLED)
        free.run("r1")
        assertTrue(calls[0], calls[0].startsWith("say ") && calls[0].contains(InfoStrings.modelNotLoaded().render(Langs.EN)))
        assertEquals(listOf("turn r1", "finish r1 CANCELLED"), calls.drop(1))
        calls.clear()
        turn = TurnResult(null, failure = "That didn't work.")
        free.run("r2")
        assertEquals(listOf("turn r2", "fail r2 That didn't work."), calls.drop(1))
    }

    /**
     * The apps you used lately are named only on free_memory's card, each on its button: the reply (said aloud, kept in the
     * conversation, perhaps sent on to a helper or a search) says how many, never which.
     */
    @Test fun theReplyCountsYourRecentAppsWithoutNamingThem() {
        val now = 1_800_000_000_000L
        val mem = MemoryFacts(total = 8_000_000_000L, available = 2_000_000_000L, low = false)
        val recent = listOf(RecentApp("Signal", "org.thoughtcrime.securesms", now - 60_000), RecentApp("Proton Mail", "ch.protonmail.android", now - 600_000))
        for (l in listOf(Langs.EN, Langs.of(Lang.FR))) {
            val said = PanelFreeMemory.advice(mem, 2_000_000_000L, recent, now).render(l)
            assertFalse(said, said.contains("Signal") || said.contains("Proton"))
            assertTrue(said, said.contains(InfoStrings.recentAppsOnCard(2).render(l)))
            assertTrue(said, said.contains(InfoStrings.unloadingFrees("size" to PhoneStatus.sizeText(2_000_000_000L)).render(l)))
        }
        assertTrue(PanelFreeMemory.advice(mem, null, recent.take(1), now).render(Langs.EN).contains("1 app you used lately"))
        // Without Usage access (null) or with no app to name: FreeUp's own words, as before.
        for (r in listOf(null, emptyList<RecentApp>())) {
            assertEquals(FreeUp.adviceText(mem, null, r, now).render(Langs.EN), PanelFreeMemory.advice(mem, null, r, now).render(Langs.EN))
        }
    }
}
