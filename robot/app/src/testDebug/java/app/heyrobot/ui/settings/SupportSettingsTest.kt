// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.heyrobot.core.QrCode
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.1: Settings › About › Support Robot. Ko-fi first, then each address in full with Copy, in the order the user set on
 * 14 Oct 2026; Copy hands over exactly the value support-addresses.txt has (SupportAddressesTest pins SupportLinks to the
 * file, and each address's QR code to the one zbarimg read), and only a tap on Ko-fi's button opens anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SupportSettingsTest {
    @get:Rule val rule = createComposeRule()

    private fun name(k: SupportKind) = when (k) {
        SupportKind.KOFI -> "Ko-fi"
        SupportKind.BITCOIN -> "Bitcoin"
        SupportKind.LIGHTNING -> "Lightning"
        SupportKind.MONERO -> "Monero"
        SupportKind.ETHEREUM -> "Ethereum"
    }

    /** Taps Copy on each address in turn (the two Bitcoin rows share a label: the first, then the second). */
    private fun copyEach() {
        val seen = HashMap<SupportKind, Int>()
        for (link in SupportLinks.ADDRESSES) {
            val i = seen.merge(link.kind, 1, Int::plus)!! - 1
            rule.onAllNodesWithContentDescription("Copy the ${name(link.kind)} address")[i].performScrollTo().performClick()
        }
    }

    @Test fun koFiFirstThenEveryAddressInFullWithCopy() {
        val opened = ArrayList<String>()
        val copied = ArrayList<SupportLink>()
        rule.show { SupportScreen(onOpen = { opened += it }, onCopy = { copied += it }, onBack = {}) }
        rule.onNodeWithText("Robot is free, and I make it alone.", substring = true).assertExists()
        // Top to bottom: Ko-fi, then bc1q, sp1, Lightning, Monero, Ethereum, each value whole.
        val tops = SupportLinks.ALL.map { rule.onNodeWithText(it.value).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        assertEquals(emptyList<String>(), opened)
        assertEquals(emptyList<SupportLink>(), copied)

        rule.onNodeWithText("Open in the browser").performClick()
        assertEquals(listOf("https://ko-fi.com/picocryptfan"), opened)
        copyEach()
        assertEquals(SupportLinks.ADDRESSES, copied)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun eachAddressShowsItsQrCodeWithCopyAndClose() {
        val copied = ArrayList<SupportLink>()
        rule.show { SupportScreen(onOpen = {}, onCopy = { copied += it }, onBack = {}) }
        val seen = HashMap<SupportKind, Int>()
        for (link in SupportLinks.ADDRESSES) {
            val i = seen.merge(link.kind, 1, Int::plus)!! - 1
            rule.onAllNodesWithContentDescription("Show the ${name(link.kind)} address as a QR code")[i].performScrollTo().performClick()
            rule.onNode(isDialog()).assertExists()
            rule.onNodeWithContentDescription("QR code of the ${name(link.kind)} address").assertExists()
            rule.onNode(hasText(link.value) and hasAnyAncestor(isDialog())).assertExists()
            assertEquals(emptyList<String>(), rule.touchTargetProblems())
            rule.onNode(hasText("Copy") and hasAnyAncestor(isDialog())).performClick()
            rule.onNode(isDialog()).assertDoesNotExist()
        }
        assertEquals(SupportLinks.ADDRESSES, copied)
        // Close copies nothing.
        rule.onAllNodesWithContentDescription("Show the Monero address as a QR code")[0].performScrollTo().performClick()
        rule.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(SupportLinks.ADDRESSES, copied)
    }

    /**
     * The image is [code], module for module: every pixel of a dark module black and of a light one white, the 4-module
     * border white, so a scanner sees no seam (zbarimg read the screenshot support_qr.png back to the sp1 address).
     */
    private fun assertDrawn(code: QrCode, b: Bitmap) {
        val modules = code.size + 8
        val m = minOf(b.width, b.height) / modules
        val origin = (minOf(b.width, b.height) - m * modules) / 2 + 4 * m
        assertTrue(m >= 3)
        for (y in -4 until code.size + 4) for (x in -4 until code.size + 4) {
            val want = if (x in 0 until code.size && y in 0 until code.size && code.dark(x, y)) Color.BLACK else Color.WHITE
            for (py in 0 until m) for (px in 0 until m) {
                val got = b.getPixel(origin + x * m + px, origin + y * m + py)
                if (got != want) throw AssertionError("module $x,$y pixel $px,$py is ${Integer.toHexString(got)}")
            }
        }
    }

    /** Drawn as the dialog draws it, 240 dp on a 2x (480 px) and a 2.625x (630 px) screen, each address's image is its code. */
    @Test fun eachQrImageIsItsCodeModuleForModule() {
        for (px in listOf(480, 630)) for (link in SupportLinks.ADDRESSES) {
            val code = QrCode.encode(link.value)!!
            val image = ImageBitmap(px, px)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) { drawQr(code) }
            assertDrawn(code, image.asAndroidBitmap())
        }
    }

    @Test fun copyPutsTheExactAddressOnTheClipboard() {
        lateinit var ctx: Context
        val snackbar = SnackbarHostState()
        rule.show { ctx = LocalContext.current; SupportRoute(snackbar, onBack = {}) }
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        val seen = HashMap<SupportKind, Int>()
        for (link in SupportLinks.ADDRESSES) {
            val i = seen.merge(link.kind, 1, Int::plus)!! - 1
            rule.onAllNodesWithContentDescription("Copy the ${name(link.kind)} address")[i].performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(link.value, clipboard.primaryClip!!.getItemAt(0).text.toString())
            rule.waitUntil(5_000) { snackbar.currentSnackbarData != null }
            assertEquals("Copied. Paste it in your wallet app.", snackbar.currentSnackbarData!!.visuals.message)
            // Out of the way of the next row's Copy, as a swipe or its timeout would.
            rule.runOnIdle { snackbar.currentSnackbarData?.dismiss() }
        }
        // Copying opens nothing.
        assertEquals(null, shadowOf(ctx as Activity).nextStartedActivity)
    }

    @Test fun koFiOpensInTheBrowserOnlyWhenTapped() {
        lateinit var ctx: Context
        rule.show { ctx = LocalContext.current; SupportRoute(SnackbarHostState(), onBack = {}) }
        rule.waitForIdle()
        val shadow = shadowOf(ctx as Activity)
        assertEquals(null, shadow.nextStartedActivity)
        rule.onNodeWithText("Open in the browser").performClick()
        val i = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(SupportLinks.KOFI.value, i.dataString)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(null, shadow.nextStartedActivity)
    }

    @Test fun aboutHasTheSupportRow() {
        val log = ActionLog()
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        rule.onRow("Support Robot").performScrollTo().performClick()
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.SUPPORT)}"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun light() {
        rule.show { SupportScreen(onOpen = {}, onCopy = {}, onBack = {}) }
        rule.shot("support_light")
    }

    @Test fun qrCode() {
        rule.show { SupportScreen(onOpen = {}, onCopy = {}, onBack = {}) }
        rule.onAllNodesWithContentDescription("Show the Bitcoin address as a QR code")[1].performScrollTo().performClick()
        rule.shot("support_qr")
    }

    @Test fun dark() {
        rule.show(dark = true) { SupportScreen(onOpen = {}, onCopy = {}, onBack = {}) }
        rule.shot("support_dark")
    }
}

/** The page in Québec French: the same links, the words from values-fr-rCA. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-$PHONE")
class SupportSettingsFrenchTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theWordsAreFrenchAndTheAddressesTheSame() {
        val copied = ArrayList<SupportLink>()
        rule.show { SupportScreen(onOpen = {}, onCopy = { copied += it }, onBack = {}) }
        rule.onNodeWithText("Soutenir Robot").assertExists()
        rule.onNodeWithText("une fois ou chaque mois", substring = true).assertExists()
        rule.onNodeWithText("Ouvrir dans le navigateur").assertExists()
        for (link in SupportLinks.ALL) rule.onNodeWithText(link.value).assertExists()
        rule.onAllNodesWithContentDescription("Copier l’adresse Monero")[0].performScrollTo().performClick()
        assertEquals(listOf(SupportLinks.ADDRESSES.single { it.kind == SupportKind.MONERO }), copied)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** WP9b: support_light's page in French. */
    @Test fun light() {
        rule.show { SupportScreen(onOpen = {}, onCopy = {}, onBack = {}) }
        rule.shot("support_fr")
    }
}
