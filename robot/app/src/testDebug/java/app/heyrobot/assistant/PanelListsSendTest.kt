// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Looper
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.lists.ListQuote
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.memory.MemoryHygiene
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.SoftListsKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * LS10 (N4): Lists through PanelController.send to the end of each turn, as the panel runs it: the router's place in the
 * chain, PanelLists' reply, finishReply's record and the user's turn, then History as saved. A read and a check are saved
 * as list content (ListQuote's record and placeholder, M1, D17); a secret a list refused is saved as memory's private
 * placeholder, never as said (M2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelListsSendTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val ctx = RuntimeEnvironment.getApplication()
    private val vault by lazy { ListsVault(File(tmp.root, "lists"), SoftListsKeys(), settings = { ListsSettings() }, someoneElse = { false }, locked = { false }) }
    private val looper = shadowOf(Looper.getMainLooper())

    @Before fun phone() {
        PanelLists.env = object : PanelLists.Env {
            override fun vault(): ListsVault = this@PanelListsSendTest.vault
            override fun settings(): ListsSettings = ListsSettings()
            override fun locked(): Boolean = false
            override fun someoneElse(): Boolean = false
        }
        VoiceHub.init(ctx)
        PanelController.newConversation(locked = false)
        idle { !PanelController.state.value.busy }
    }

    @After fun clean() {
        PanelController.newConversation(locked = false)
        PanelLists.env = null
    }

    /** The main looper run, and the background threads given time, until [done]. */
    private fun idle(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            looper.idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "timed out" } // l10n: not text (a test's failure)
            Thread.sleep(20)
        }
    }

    /** [q] typed, to the end of its turn: the reply's text. */
    private fun ask(q: String): String {
        val before = PanelController.state.value.turns.size
        idle { PanelController.send(q) }
        idle { PanelController.state.value.let { s -> !s.busy && s.turns.size == before + 2 && !s.turns.last().streaming } }
        return PanelController.state.value.turns.last().text
    }

    /**
     * The conversation as History saved it, once it holds [n] turns. Read through VoiceHub.app, as the panel saves it: VoiceHub
     * keeps the first test's Application, whose files aren't this test's (LS12: a second test here found nothing saved).
     */
    private fun saved(n: Int): StoredConversation {
        val id = PanelController.state.value.convId
        var c: StoredConversation? = null
        idle { ConversationStore.load(VoiceHub.app, id)?.takeIf { it.turns.size == n }?.also { c = it } != null }
        return c!!
    }

    @Test fun aListTurnThroughSendIsKeptAsListContentAndARefusedSecretAsPrivate() {
        assertEquals("Added oat milk to Shopping.", ask("add oat milk to the shopping list"))
        assertEquals("Shopping has 1 thing: oat milk.", ask("what's on the shopping list"))
        assertEquals("Yes, Shopping has oat milk.", ask("is milk on the shopping list"))
        assertEquals("That looks like a code or a card number. Robot keeps those in Memory, not on a list.", ask("add my PIN 4821 to the to-do list"))
        val turns = PanelController.state.value.turns
        assertEquals(MemoryHygiene.PRIVATE, turns[turns.size - 2].text)
        val c = saved(8)
        // The read and the check carry ListQuote's record, their placeholders saved in place of the items.
        assertTrue(ListQuote.of(c.turns[3]))
        assertEquals("Robot read Shopping: 1 item", c.turns[3].text)
        assertTrue(ListQuote.of(c.turns[5]))
        assertEquals("Robot answered from your lists", c.turns[5].text)
        assertEquals(MemoryHygiene.PRIVATE, c.turns[6].text)
        val json = ConversationCodec.toJson(c)
        assertFalse(json, json.contains("4821") || json.contains("Shopping has"))
        // The add is your own words, kept as said.
        assertEquals("add oat milk to the shopping list", c.turns[0].text)
    }

    /**
     * LS12 (LT5 on the Pixel 8): with a list called Invités the panel has seen, a new conversation still hears « ajoute Sam
     * à la liste des invités » as an add to it. The panel forgot your lists' names with each new conversation, so « liste des
     * X » (which names only a default list or one of yours, LS10's L2) reached the model instead.
     */
    @Test fun aListsNameIsStillHeardInANewConversation() {
        vault.update(app.heyrobot.core.lists.ItemHow.TYPED) { f, who ->
            app.heyrobot.core.lists.ListsEdits.make(f, "Invités", null, who) { app.heyrobot.core.lists.ListsId.new(java.security.SecureRandom()) }
        }
        // A list turn: the panel reads your lists' names, as at a show.
        assertEquals("Added oat milk to Shopping.", ask("add oat milk to the shopping list"))
        PanelController.newConversation(locked = false)
        idle { !PanelController.state.value.busy }
        assertEquals("Ajouté à la liste Invités : Sam.", ask("ajoute Sam à la liste des invités").replace('\u00A0', ' ').replace('\u202F', ' '))
        assertEquals(listOf("Sam"), (vault.load() as app.heyrobot.core.lists.ListsLoad.Loaded).value.lists.single { it.name == "Invités" }.open.map { it.text })
    }
}
