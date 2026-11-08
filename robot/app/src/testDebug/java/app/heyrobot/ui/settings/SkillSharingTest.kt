// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.skills.ExportRead
import app.heyrobot.core.skills.ExportWrite
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillExport
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillProvenance
import app.heyrobot.core.skills.SkillSig
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.TrustedKey
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.SkillKeystore
import app.heyrobot.sys.SkillShareFiles
import app.heyrobot.sys.SkillSigning
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.time.ZoneId
import java.time.ZonedDateTime
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/** Skills 1.3 plan S3.4 (M7 on the PC): an export signed by this phone's key; import shows who signed it; it starts off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SkillSharingTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The Keystore's stand-in: a software P-256 pair, made at the first need. */
    private class SoftKeystore : SkillKeystore {
        var pair: KeyPair? = null
        var made = 0
        override fun publicKey(): ByteArray? = pair?.public?.encoded
        override fun privateKey(): PrivateKey? = pair?.private
        override fun generate() {
            made++
            pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        }
    }

    private val pasta = Skill("s-pasta1", 1, "Pasta", listOf("pasta"), emptyList(), emptyList(),
        listOf(SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to 720L))), SkillLevel.JUST_DO_IT, SkillProvenance(SkillMade.VOICE, false, 1L, 1L))

    private fun exported(ks: SkillKeystore): ByteArray {
        SkillSigning.mainProcess = { true }
        val w = SkillExport.write(listOf(pasta), "Robot 1.3.0", 1L, emptyMap<String, ConnKind>()::get, SkillSigning.signer(ks)!!)
        return (w as ExportWrite.Ok).bytes
    }

    @After fun tearDown() {
        SkillShareFiles.clear()
        SkillShareFiles.saveTrusted(ctx, emptyList())
        SkillSigning.mainProcess = { !android.app.Application.getProcessName().contains(':') }
    }

    @Test fun anExportIsSignedByThisPhonesOneKey() {
        val ks = SoftKeystore()
        val r = SkillExport.read(exported(ks), { "s-imp001" }, 5L) as ExportRead.Ok
        assertEquals(SkillSig.fingerprint(ks.publicKey()!!), r.signer)
        // Made once, then the same key each time.
        assertEquals(SkillSig.fingerprint(ks.publicKey()!!), SkillSigning.fingerprint(ks))
        assertEquals(1, ks.made)
        // Any other process gets no signer, so no export.
        SkillSigning.mainProcess = { false }
        assertNull(SkillSigning.signer(ks))
        // The key's alias is named in one file only.
        val users = File("src/main/java").walk().filter { it.isFile && it.readText().contains("\"" + SkillSigning.ALIAS + "\"") }.map { it.name }.toList()
        assertEquals(listOf("SkillSharing.kt"), users)
    }

    @Test fun aFileIsReadOnceUpToOneByteOverTheCap() {
        val big = ByteArray(SkillExport.MAX_BYTES * 2)
        assertEquals(SkillExport.MAX_BYTES + 1, SkillShareFiles.cap(ByteArrayInputStream(big), SkillExport.MAX_BYTES + 1).size)
        assertEquals(10, SkillShareFiles.cap(ByteArrayInputStream(ByteArray(10)), SkillExport.MAX_BYTES + 1).size)
    }

    private fun importScreen(saved: MutableList<Skill>, back: () -> Unit = {}) = rule.show {
        SkillImportScreen(SkillsData(), SkillActions(save = { saved += it; true }), SnackbarHostState(), onBack = back)
    }

    @Test fun importShowsATrustedSignersNameAndTheSkillStartsOff() {
        val ks = SoftKeystore()
        val bytes = exported(ks)
        val fp = SkillSig.fingerprint(ks.publicKey()!!)
        assertTrue(SkillShareFiles.saveTrusted(ctx, listOf(TrustedKey(fp, "Tablet", 1L))))
        SkillShareFiles.hold(bytes)
        val saved = ArrayList<Skill>()
        var back = 0
        importScreen(saved) { back++ }
        rule.onNodeWithText("From a file · signed by your key “Tablet”").assertExists()
        rule.onNodeWithText("Each skill starts off. Turn it on in its page once you've read its steps.").assertExists()
        rule.scrollToText("Import")
        rule.onNodeWithText("Import").performClick()
        rule.waitForIdle()
        val k = saved.single()
        assertEquals("Pasta", k.name)
        assertEquals(SkillLevel.OFF, k.level)
        assertTrue(k.provenance.fromContent)
        assertEquals(SkillMade.IMPORT, k.provenance.made)
        assertEquals(fp, k.provenance.imported!!.signer)
        assertEquals(1, back)
        assertNull(SkillShareFiles.held())
    }

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun importInFrench() {
        val ks = SoftKeystore()
        val bytes = exported(ks)
        assertTrue(SkillShareFiles.saveTrusted(ctx, listOf(TrustedKey(SkillSig.fingerprint(ks.publicKey()!!), "Tablette", 1L))))
        SkillShareFiles.hold(bytes)
        val now = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
        rule.show { SkillImportScreen(SkillsData(), SkillActions(), SnackbarHostState(), onBack = {}, now = now) }
        rule.onNodeWithText("Tablette", substring = true).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("skill_import_fr")
    }

    @Test fun importShowsAnUnknownSignersFingerprint() {
        val ks = SoftKeystore()
        SkillShareFiles.hold(exported(ks))
        importScreen(ArrayList())
        rule.onNodeWithText("From a file · signed by " + SkillSig.shown(SkillSig.fingerprint(ks.publicKey()!!)) + " … (a key you don't trust)").assertExists()
    }

    @Test fun aChangedFileIsRefusedAndNothingIsSaved() {
        val bytes = exported(SoftKeystore())
        val i = String(bytes, Charsets.ISO_8859_1).indexOf("\"Pasta\"") + 1
        SkillShareFiles.hold(bytes.copyOf().also { it[i] = 'Q'.code.toByte() })
        val saved = ArrayList<Skill>()
        importScreen(saved)
        rule.onNodeWithText("The signature doesn't match: the file was changed after it was signed.").assertExists()
        rule.onNodeWithText("Import").assertDoesNotExist()
        assertTrue(saved.isEmpty())
    }

    @Test fun theShareTargetsSkillFileOpensTheImportScreen() {
        assertEquals(AppPages.SKILL_IMPORT, AppRoutes.of(Intent().putExtra(AppPages.EXTRA, AppPages.SKILL_IMPORT)))
        assertEquals(Page.Settings(SkillShareRoutes.IMPORT), AppRoutes.target(AppPages.SKILL_IMPORT)?.page)
        assertNotNull(SkillShareRoutes.of(SkillShareRoutes.IMPORT).takeIf { it })
        assertEquals("Pasta.robotskill", exportFileName(pasta))
        assertEquals("ab.robotskill", exportFileName(pasta.copy(name = "a/b")))
    }
}
