// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AppHandOff
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.sys.HandOffIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.0.0: the hand-off to any helper's app (ToolExecutor and HandOffIntents): the app comes from your settings, never from
 * the call; its share of the question when it takes one, else just the app; the question on the clipboard every time; and
 * a card that says what goes and that you press Send in that app. Nothing here goes online.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandOffAnyAppTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pm get() = shadowOf(ctx.packageManager)

    /** Installs [pkg] with a launcher activity and, when [share], one that takes SEND text/plain. */
    private fun install(pkg: String, share: Boolean = true) {
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg } })
        val main = ComponentName(pkg, "$pkg.Main")
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        if (share) {
            val send = ComponentName(pkg, "$pkg.Share")
            pm.addActivityIfNotPresent(send)
            pm.addIntentFilterForActivity(send, IntentFilter(Intent.ACTION_SEND).apply { addDataType("text/plain"); addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private fun clip(): String? = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun executor(s: RobotSettings, sent: MutableList<Intent>) = ToolExecutor(ctx, s) { sent += it; true }

    @Test fun eachAppGetsAShareOfTheQuestionByItsOwnPackage() {
        for (p in Presets.ALL.filter { it.app != null && it.app != ClaudeAppLink.PACKAGE }) {
            val pkg = p.app!!
            install(pkg)
            val i = HandOffIntents.intent(ctx, pkg, AppHandOff.Way.SEND, "why is the sky blue")!!
            assertEquals(Intent.ACTION_SEND, i.action)
            assertEquals("text/plain", i.type)
            assertEquals(pkg, i.`package`)
            assertEquals("why is the sky blue", i.getStringExtra(Intent.EXTRA_TEXT))
            // The Claude app's own links never go to another app.
            assertNull(HandOffIntents.intent(ctx, pkg, AppHandOff.Way.APP_LINK, "x"))
            assertNull(HandOffIntents.intent(ctx, pkg, AppHandOff.Way.WEB_LINK, "x"))
            assertEquals(AppHandOff.Way.SEND, HandOffIntents.choose(ctx, pkg))
            assertTrue(HandOffIntents.facts(ctx, pkg).let { it.installed && it.send && !it.appLink && !it.webLink })
        }
        // Not installed: nothing to choose.
        assertNull(HandOffIntents.choose(ctx, "com.example.nothing"))
    }

    @Test fun theCardSaysWhatGoesAndThatYouSendItThere() {
        install("com.openai.chatgpt")
        val s = RobotSettings(helpers = listOf(HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")))
        val p = executor(s, mutableListOf()).prepare(Action.AskHelper("why is the sky blue")) as Prepared.Ready
        val a = p.action as Action.AskHelper
        assertEquals("com.openai.chatgpt", a.app)
        assertEquals("Open the ChatGPT app with: «why is the sky blue»", a.describe())
        assertEquals("Robot opens the ChatGPT app with this text and copies it. Nothing is sent until you press Send in ChatGPT.", p.note)
        // The app isn't installed: refused before any card, naming it.
        val grok = RobotSettings(helpers = listOf(HelperConfig.of(Presets.XAI, "hgrk", "am_puck")))
        val r = executor(grok, mutableListOf()).prepare(Action.AskHelper("hi")) as Prepared.Refused
        assertEquals(ActionStatus.NO_APP, r.status)
        assertEquals("Install Grok.", r.detail)
        // No helper opens an app (a new install): refused.
        assertEquals(ActionStatus.NO_APP, (executor(RobotSettings(), mutableListOf()).prepare(Action.AskHelper("hi")) as Prepared.Refused).status)
    }

    @Test fun theAppComesFromYourSettingsNeverFromTheCall() {
        install("com.openai.chatgpt")
        install("com.example.evil")
        val s = RobotSettings(helpers = listOf(HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")))
        val sent = mutableListOf<Intent>()
        // A call that names another app (it can't through the parser; if it did): ChatGPT's settings win.
        val r = executor(s, sent).run(Action.AskHelper("why", app = "com.example.evil", appName = "Evil"))
        assertEquals(ActionStatus.DONE, r.status)
        assertEquals("com.openai.chatgpt", sent.single().`package`)
        // A helper named that has no app, or is off: nothing opens.
        val off = s.withHelper(s.helper("hgpt")!!.copy(enabled = false))
        assertEquals(ActionStatus.NO_APP, executor(off, mutableListOf()).run(Action.AskHelper("why")).status)
        assertEquals(ActionStatus.NO_APP, executor(s, mutableListOf()).run(Action.AskHelper("why", helper = "nobody")).status)
    }

    @Test fun theQuestionIsAlwaysOnTheClipboard() {
        install("ai.x.grok")
        install("com.deepseek.chat", share = false)
        val s = RobotSettings(helpers = listOf(HelperConfig.of(Presets.XAI, "hgrk", "am_puck"), HelperConfig.of(Presets.DEEPSEEK, "hdee", "bf_emma")))
        // A share: the app gets the text, and the clipboard has it too.
        val sent = mutableListOf<Intent>()
        val r = executor(s, sent).run(Action.AskHelper("what's a quasar?", helper = "hgrk"))
        assertEquals(ActionStatus.DONE, r.status)
        assertTrue(r.launchedApp)
        assertEquals("Opened Grok with your question: press Send there if it isn't sent. It's on the clipboard too.", r.detail)
        assertEquals(Intent.ACTION_SEND, sent.single().action)
        assertEquals("ai.x.grok", sent.single().`package`)
        assertEquals("what's a quasar?", clip())
        // No share: just the app, and the question to paste.
        val sent2 = mutableListOf<Intent>()
        val r2 = executor(s, sent2).run(Action.AskHelper("what's a pulsar?", helper = "hdee"))
        assertEquals("Opened DeepSeek. Paste your question in DeepSeek: it's on the clipboard.", r2.detail)
        assertEquals(Intent.ACTION_MAIN, sent2.single().action)
        assertEquals("com.deepseek.chat", sent2.single().component?.packageName)
        assertEquals("what's a pulsar?", clip())
        // Nothing to say: the app opens, the clipboard is left alone.
        val r3 = executor(s, mutableListOf()).run(Action.AskHelper("", helper = "hgrk"))
        assertEquals("Opened Grok.", r3.detail)
        assertEquals("what's a pulsar?", clip())
    }

    @Test fun theClaudeAppKeepsItsBehaviour() {
        install(ClaudeAppLink.PACKAGE)
        val s = RobotSettings(helpers = listOf(HelperConfig.claude()))
        val sent = mutableListOf<Intent>()
        val r = executor(s, sent).run(Action.AskHelper("why is the sky blue"))
        assertEquals(ActionStatus.DONE, r.status)
        // Its links don't resolve here (only a share was registered): the share, to the Claude app.
        assertEquals(Intent.ACTION_SEND, sent.single().action)
        assertEquals(ClaudeAppLink.PACKAGE, sent.single().`package`)
        assertEquals("why is the sky blue", clip())
        assertFalse(HandOffIntents.facts(ctx, ClaudeAppLink.PACKAGE).appLink)
    }
}
