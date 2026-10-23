// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.QrCode
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.pref.prefTitleStyle
import app.heyrobot.ui.theme.RobotTokens
import kotlinx.coroutines.launch

// MAIN PROCESS. Settings › About › Support Robot (1.1; the user's decision of 14 Oct 2026, docs/pm/handoff-support-page.md):
// one line, then Ko-fi, then the addresses, each shown in full with Copy and a QR code (core QrCode, drawn here: no
// library). The page makes no network call: Ko-fi opens in the browser only when you tap it. Nothing here asks again,
// reminds you or shows anywhere else, and Settings never shows over the lock screen.

/** What a way to support Robot is called: names of a platform and of currencies, the same in every language. */
enum class SupportKind(@param:StringRes val label: Int) {
    KOFI(R.string.about_support_kofi),
    BITCOIN(R.string.about_support_bitcoin),
    LIGHTNING(R.string.about_support_lightning),
    MONERO(R.string.about_support_monero),
    ETHEREUM(R.string.about_support_ethereum),
}

/** One link or address, [value] exactly as robot/support-addresses.txt has it. */
data class SupportLink(val kind: SupportKind, val value: String)

/**
 * The page's links, in the order it shows them: Ko-fi, then the bc1q address before the sp1 one, Lightning, Monero and
 * Ethereum. The values were copied from robot/support-addresses.txt by a script, never typed, and SupportAddressesTest
 * fails if one of them differs from that file by a character.
 */
object SupportLinks {
    val KOFI = SupportLink(SupportKind.KOFI, "https://ko-fi.com/picocryptfan")
    val ADDRESSES = listOf(
        SupportLink(SupportKind.BITCOIN, "bc1qq9qe5ckwju6dkjddmf2vpfrcu7cakjw7ld2gtk"),
        SupportLink(SupportKind.BITCOIN, "sp1qqf85ltcy8al2flnwm2v8kszm2rplcs8a6xa6uw5rpfqmy7sa7srr6qafj6jwd3jj2fcmq8agna23syl6vjwzpjjg5yhwalyqm7d7ydxjhvqtgt0v"),
        SupportLink(SupportKind.LIGHTNING, "dopeycheese1839@cake.cash"), // l10n: not text (a payment address)
        SupportLink(SupportKind.MONERO, "46t4rJYd7XgXeCPG24izVW3FwKPgrmhg74E6GMuT8VUG68ZJvCnDQLHaMsHGzT6YTJiVwUqA1Qd9yMKkWw96jDXXTHaBaQm"), // l10n: not text (a payment address)
        SupportLink(SupportKind.ETHEREUM, "0x8245306C33c92630Af30aCFc542307dbD17f47D1"),
    )
    val ALL = listOf(KOFI) + ADDRESSES
}

/** The page, wired to the phone: Copy puts the address on the clipboard, Ko-fi opens in the browser. */
@Composable
internal fun SupportRoute(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.about_support_copied)
    val noBrowser = stringResource(R.string.about_support_no_browser)
    SupportScreen(
        onOpen = { url ->
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                scope.launch { snackbar.showSnackbar(noBrowser) }
            }
        },
        onCopy = { link ->
            // A plain clip, not marked sensitive and never cleared: it's public, and you paste it in your wallet app.
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(ctx.getString(link.kind.label), link.value))
            scope.launch { snackbar.showSnackbar(copied) }
        },
        onBack = onBack,
        snackbar = snackbar,
    )
}

/** Support Robot: [onOpen] gets Ko-fi's link when it's tapped, [onCopy] the address whose Copy was tapped. */
@Composable
fun SupportScreen(onOpen: (String) -> Unit, onCopy: (SupportLink) -> Unit, onBack: () -> Unit, snackbar: SnackbarHostState? = null) {
    val intro = stringResource(R.string.about_support_intro)
    val addresses = stringResource(R.string.about_support_addresses)
    var qr by remember { mutableStateOf<SupportLink?>(null) }
    PreferenceScreen(stringResource(R.string.about_support_title), onBack, snackbar = snackbar) {
        item(key = "intro", contentType = "intro") {
            Text(intro, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = RobotTokens.prefPaddingH, vertical = 8.dp))
        }
        preferenceGroup(null, "kofi") {
            LinkBlock(SupportLinks.KOFI) {
                TextButton(onClick = { onOpen(SupportLinks.KOFI.value) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.about_licences_open))
                }
            }
        }
        preferenceGroup(addresses, "addresses") {
            for (link in SupportLinks.ADDRESSES) AddressBlock(link, onCopy, onQr = { qr = link })
        }
    }
    qr?.let { link -> QrDialog(link, onCopy = { onCopy(link); qr = null }, onDismiss = { qr = null }) }
}

/** A link's name, then its value in full (it can be selected), then [actions]. */
@Composable
private fun LinkBlock(link: SupportLink, actions: @Composable () -> Unit) {
    PreferenceBlock {
        Text(stringResource(link.kind.label), style = prefTitleStyle())
        SelectionContainer {
            Text(link.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Row { actions() }
    }
}

@Composable
private fun AddressBlock(link: SupportLink, onCopy: (SupportLink) -> Unit, onQr: () -> Unit) {
    val name = stringResource(link.kind.label)
    val copyLabel = stringResource(R.string.about_support_copy_label, name)
    val qrLabel = stringResource(R.string.about_support_qr_show, name)
    LinkBlock(link) {
        TextButton(onClick = { onCopy(link) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = copyLabel }) {
            Text(stringResource(R.string.about_support_copy))
        }
        TextButton(onClick = onQr, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = qrLabel }) {
            Text(stringResource(R.string.about_support_qr))
        }
    }
}

/** An address's QR code, large, with the address under it and Copy. */
@Composable
private fun QrDialog(link: SupportLink, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val name = stringResource(link.kind.label)
    val code = remember(link.value) { QrCode.encode(link.value) }
    val description = stringResource(R.string.about_support_qr_label, name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (code != null) QrImage(code, description)
                SelectionContainer {
                    Text(link.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_support_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_support_close)) } },
    )
}

/**
 * [code] with its 4-module light border: whole modules of whole pixels, drawn as one path, so no light seam shows between
 * two dark modules (a scanner reads a seam as a light line). Black on white in both themes: that is what wallet apps'
 * scanners read (many can't read a light-on-dark code), so these two colours aren't the theme's.
 */
@Composable
private fun QrImage(code: QrCode, description: String) {
    Canvas(Modifier.size(240.dp).semantics { contentDescription = description; role = Role.Image }) { drawQr(code) }
}

internal fun DrawScope.drawQr(code: QrCode) {
    val modules = code.size + 2 * QUIET
    val m = kotlin.math.floor(size.minDimension / modules)
    val origin = kotlin.math.floor((size.minDimension - m * modules) / 2) + QUIET * m
    val dark = Path()
    for (y in 0 until code.size) {
        var x = 0
        while (x < code.size) {
            if (!code.dark(x, y)) { x++; continue }
            val start = x
            while (x < code.size && code.dark(x, y)) x++
            dark.addRect(Rect(Offset(origin + start * m, origin + y * m), Size((x - start) * m, m)))
        }
    }
    drawRect(Color.White)
    drawPath(dark, Color.Black)
}

/** The light border a QR code needs around it, in modules (ISO/IEC 18004). */
private const val QUIET = 4
