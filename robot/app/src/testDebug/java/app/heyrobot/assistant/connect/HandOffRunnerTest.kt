// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.HandOffCall
import app.heyrobot.core.HandOffStepAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.StepArg
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HandOffAction
import app.heyrobot.core.connect.HandOffApp
import app.heyrobot.core.connect.HandOffExtra
import app.heyrobot.core.connect.IntentSpec
import app.heyrobot.core.connect.IntentSpecs
import app.heyrobot.core.connect.OpParam
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.SignerNow
import app.heyrobot.core.skills.ParamType
import app.heyrobot.sys.ConnFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C7 (plan §3.5, D17): a hand-off step in the panel, with Android's side faked (the app's signers, its activities) and the
 * panel's launch recorded. Prepare and run check the signer you pinned; a replaced app is never opened, even when it was
 * replaced after the card; the intent is explicit and carries data only; the voice log keeps numbers only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandOffRunnerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val logged = mutableListOf<String>()
    private val pinned = "a".repeat(64)
    private val pkg = "org.zq9marker.chat"
    private val share = IntentSpec(HandOffAction.SEND, mime = IntentSpecs.TEXT_PLAIN, extras = listOf(HandOffExtra("android.intent.extra.TEXT", ParamType.TEXT, "{{text}}"),
        HandOffExtra("org.zq9marker.COUNT", ParamType.INTEGER, "3"), HandOffExtra("org.zq9marker.QUIET", ParamType.YES_NO, "true")),
        params = mapOf("text" to OpParam(ParamType.TEXT, maxLen = 200)))
    private val chat = Connection("c-app001", ConnKind.HAND_OFF, "zq9 Chat", handOff = HandOffApp(pkg, pinned, share))

    private inner class Side : HandOffSide {
        var signers: Set<String>? = setOf(pinned)
        var lineage = false
        var takers = listOf(ComponentName(pkg, "$pkg.ShareActivity"))
        val asked = mutableListOf<Intent>()
        override fun now(pkg: String): SignerNow? = signers?.let { SignerNow(it) }
        override fun stillSigns(pkg: String, signer: String): Boolean = lineage
        override fun takers(i: Intent): List<ComponentName> = takers.also { asked += i }
    }

    private val side = Side()
    private val launched = mutableListOf<Intent>()
    private var launches = true
    private val launch: (Intent) -> Boolean = { launched += it; launches }

    private fun step(text: String = "zq9 On my way.") = HandOffStepAction(HandOffCall(chat.id, mapOf("text" to StepArg.Fixed(text)), "Heading home", 1))
    private fun prepared(): HandOffStepAction = ((ConnRunner.prepare(ctx, step()) as Prepared.Ready).action as HandOffStepAction)
    private fun refused(p: Prepared): String = (p as Prepared.Refused).text!!.render(Langs.EN)

    @Before fun up() {
        RLog.sink = { synchronized(logged) { logged += it } }
        HandOffRunner.side = { side }
        assertTrue(ConnFiles.store(ctx).save(chat))
    }

    @After fun down() {
        RLog.sink = null
        HandOffRunner.side = { AndroidHandOffSide(it.applicationContext) }
        File(ctx.filesDir, "robot/connections.json").delete()
        for (l in logged) assertFalse(l, l.contains("zq9"))
        for (e in VoiceLog.entries()) assertFalse(e.toString(), e.toString().contains("zq9"))
    }

    @Test fun opensTheAppWithAnExplicitIntentThatCarriesDataOnly() = runBlocking {
        val a = prepared()
        assertEquals("Open zq9 Chat with “zq9 On my way.”", a.summary().render(Langs.EN))
        val e = ConnRunner.run(ctx, a, launch)
        assertEquals(ActionStatus.DRAFT_OPENED, e.status)
        assertTrue(e.launchedApp)
        val i = launched.single()
        assertEquals(Intent.ACTION_SEND, i.action)
        assertEquals(pkg, i.`package`)
        assertEquals(ComponentName(pkg, "$pkg.ShareActivity"), i.component)
        assertEquals("text/plain", i.type)
        assertNull(i.data)
        assertEquals("zq9 On my way.", i.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(3L, i.getLongExtra("org.zq9marker.COUNT", 0))
        assertTrue(i.getBooleanExtra("org.zq9marker.QUIET", false))
        assertEquals(setOf(Intent.EXTRA_TEXT, "org.zq9marker.COUNT", "org.zq9marker.QUIET"), i.extras!!.keySet())
        // (b) never a URI grant or ClipData; only FLAG_ACTIVITY_NEW_TASK.
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, i.flags)
        assertNull(i.clipData)
        assertNull(i.selector)
        val log = VoiceLog.entries().first { it.event == VoiceEvent.HAND_OFF }
        assertEquals(listOf(2, 0, 1), listOf(log.a, log.b, log.c))
    }

    @Test fun severalActivitiesLeaveThePackageToChooseNoneOpensNothing() = runBlocking {
        side.takers = listOf(ComponentName(pkg, "$pkg.A"), ComponentName(pkg, "$pkg.B"))
        ConnRunner.run(ctx, prepared(), launch)
        assertNull(launched.single().component)
        assertEquals(pkg, launched.single().`package`)
        side.takers = emptyList()
        val e = ConnRunner.run(ctx, prepared(), launch)
        assertEquals(ActionStatus.FAILED, e.status)
        assertEquals(1, launched.size)
        assertEquals(ConnFailure.APP_CANT_OPEN.code, VoiceLog.entries().first { it.event == VoiceEvent.HAND_OFF }.b)
        // Android wouldn't start it.
        side.takers = listOf(ComponentName(pkg, "$pkg.A"))
        launches = false
        assertEquals(ActionStatus.FAILED, ConnRunner.run(ctx, prepared(), launch).status)
    }

    @Test fun aReplacedAppIsNeverOpenedEvenAfterItsCard() = runBlocking {
        side.signers = setOf("b".repeat(64))
        assertEquals("zq9 Chat was replaced by an app signed by someone else. Pick it again in Settings › Skills › Connections.", refused(ConnRunner.prepare(ctx, step())))
        // Prepared while it was yours, replaced before the tap: the run checks again and opens nothing.
        side.signers = setOf(pinned)
        val a = prepared()
        side.signers = setOf("b".repeat(64))
        val e = ConnRunner.run(ctx, a, launch)
        assertEquals(ActionStatus.FAILED, e.status)
        assertTrue(launched.isEmpty())
        assertEquals(ConnFailure.APP_REPLACED.code, VoiceLog.entries().first { it.event == VoiceEvent.HAND_OFF }.b)
        // A key rotated within its lineage is still the app you picked.
        side.lineage = true
        assertEquals(ActionStatus.DRAFT_OPENED, ConnRunner.run(ctx, a, launch).status)
        // Uninstalled.
        side.signers = null
        assertEquals("zq9 Chat isn't installed.", refused(ConnRunner.prepare(ctx, step())))
    }

    @Test fun aHandOffChangedSinceItsCardIsNotSent() = runBlocking {
        val a = prepared()
        assertTrue(ConnFiles.store(ctx).save(chat.copy(handOff = chat.handOff!!.copy(pkg = "org.zq9marker.other"))))
        val e = ConnRunner.run(ctx, a, launch)
        assertEquals(ActionStatus.FAILED, e.status)
        // C13: its own line, not "This connection is gone".
        assertEquals(ConnFailure.CHANGED.text("zq9 Chat").render(Langs.EN), e.text!!.render(Langs.EN))
        assertTrue(launched.isEmpty())
        // Renamed only: still the same hand-off.
        assertTrue(ConnFiles.store(ctx).save(chat.copy(name = "zq9 Chat app")))
        assertEquals(ActionStatus.DRAFT_OPENED, ConnRunner.run(ctx, a, launch).status)
    }

    @Test fun mainOpensTheLauncherActivityAndViewSendsTheAddress() {
        val main = HandOffRunner.intent(IntentSpecs.build(HandOffApp(pkg, pinned), emptyMap())!!)
        assertEquals(Intent.ACTION_MAIN, main.action)
        assertTrue(main.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(pkg, main.`package`)
        val view = HandOffRunner.intent(IntentSpecs.build(HandOffApp(pkg, pinned, IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/x")), emptyMap())!!)
        assertEquals("https://wiki.example.org/x", view.dataString)
        assertNull(view.type)
        assertNull(view.extras)
    }
}
