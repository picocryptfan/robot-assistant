package app.heyrobot.assistant

import app.heyrobot.Flat
import app.heyrobot.Source
import app.heyrobot.assertHas
import app.heyrobot.assertHasNot
import app.heyrobot.assertInOrder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W4 (review-batch-15 L7): a helper's web_search goes from the panel's actions through ToolExecutor.prepareForHelper to
 * WebGate with helper = true (no town, no home, and the "Let helpers use Robot's search" switch read now); your own goes
 * through the local prepare. Read from the source: PanelActions is the panel's private object over VoiceHub (no Android
 * here), and core WebGateTest checks what WebGate does with helper = true.
 */
class HelperWebSearchPanelTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun flatCode(path: String): Flat {
        val f = File(root, "app/src/main/java/app/heyrobot/$path")
        return Flat(Source.strip(f.readText(), keepStrings = false, kotlin = true))
    }

    @Test fun aRemoteWebSearchGoesThroughPrepareForHelperAndALocalOneDoesNot() {
        val actions = flatCode("assistant/PanelController.kt").body("private object PanelActions : TurnActions {")
        // The remote branch comes before the fallback that ends in the local prepare, and is the only call of prepareForHelper.
        assertInOrder(actions,
            "override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =",
            "else if (remote && action is Action.WebSearch) withContext(Dispatchers.IO) { executor().prepareForHelper(action) }",
            "else TermuxTool.prepareTurn(VoiceHub.app, action, untrusted, remote, turnSaid) { prepare(it) }")
        assertEquals(1, actions.count("prepareForHelper"))
        assertHas(actions, "MemoryPanel.prepare(action, locked) ?: executor().prepare(action)")

        val executor = flatCode("assistant/ToolExecutor.kt")
        assertHas(executor, "fun prepareForHelper(action: Action.WebSearch): Prepared = try { web.prepare(action, helper = true)",
            "is Action.WebSearch -> web.prepare(action)")
        assertEquals(1, executor.count("helper = true"))

        val web = flatCode("assistant/WebTool.kt").body("fun prepare(a: Action.WebSearch, helper: Boolean = false): Prepared {")
        assertHas(web, "settings.weatherHome?.name.takeUnless { helper }", "helper = helper)")
        assertHasNot(web, "helper = false")
    }
}
