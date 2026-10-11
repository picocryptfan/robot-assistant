package app.heyrobot.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.core.LicenceBook
import app.heyrobot.core.LicenceWhere
import app.heyrobot.core.ThirdPartyComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › About › Licences (0.4.5): Robot's GPL-3.0 notice (free software, no warranty, the licence a tap
// away), where its source and the source of the GPL native code are, and every third-party part with its version,
// licence, copyright line and notices, each full text on tap. All from assets/licenses/ (core LicenceBook reads
// components.json); nothing goes online, a link opens only if you tap it.

/** Reads the licence list and texts from the APK's assets. */
object LicenceAssets {
    fun book(ctx: Context): LicenceBook? = text(ctx, LicenceBook.FILE)?.let { runCatching { LicenceBook.parse(it) }.getOrNull() }

    fun text(ctx: Context, file: String): String? = try {
        ctx.assets.open(file).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }
}

/** The page id for Robot's own licence (Page.Licence). */
const val ROBOT_LICENCE = "robot"

@Composable
private fun rememberBook(): Pair<LicenceBook?, Boolean> {
    val ctx = LocalContext.current
    var book by remember { mutableStateOf<LicenceBook?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        book = withContext(Dispatchers.IO) { LicenceAssets.book(ctx) }
        done = true
    }
    return book to done
}

@Composable
fun LicencesScreen(onOpen: (Page) -> Unit, onBack: () -> Unit) {
    val (book, done) = rememberBook()
    Scaffold(topBar = { BackBar("Licences", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (book == null) {
                item { Text(if (done) "Robot's licence list is missing from this build." else "Loading…") }
                return@LazyColumn
            }
            item { RobotCard(book, onOpen) }
            for (w in LicenceWhere.entries) {
                val list = book.section(w)
                if (list.isEmpty()) continue
                item(key = "h-${w.id}") {
                    // A small category header, as SectionCard's and Settings' groups have (0.4.x look).
                    Text(w.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                }
                items(list, key = { it.id }) { c -> ComponentRow(book, c) { onOpen(Page.Licence(c.id)) } }
            }
        }
    }
}

/** Robot's own notice: the GPL-3.0 with no warranty, the licence, its source, and the GPL components' source. */
@Composable
private fun RobotCard(book: LicenceBook, onOpen: (Page) -> Unit) {
    val r = book.robot
    val version = BuildConfig.VERSION_NAME
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("${r.name} $version") {
        Text(r.copyright, style = MaterialTheme.typography.bodyMedium)
        Text(LicenceBook.NOTICE, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onOpen(Page.Licence(ROBOT_LICENCE)) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Read the GNU General Public License v3")
        }
        Text("Source code", style = MaterialTheme.typography.titleSmall)
        Link(r.sourceAt(version), "Robot's source code for this version")
        Text("Source for the GPL components", style = MaterialTheme.typography.titleSmall)
        Text("espeak-ng (GPL-3.0-or-later) is built into sherpa-onnx's library, which Robot ships as a ready-made file. Each " +
            "release of Robot comes with the complete source of that library and of what it links (espeak-ng, sherpa-onnx, their " +
            "dependencies and ONNX Runtime), at:", style = MaterialTheme.typography.bodyMedium, color = muted)
        Link(r.bundleAt(version), "The source bundle of this version's GPL components")
    }
}

/** A link shown in full (it can be selected and copied), opened only on a tap. */
@Composable
private fun Link(url: String, what: String) {
    val uri = LocalUriHandler.current
    SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    TextButton(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Open $what in the browser" }) {
        Text("Open in the browser")
    }
}

@Composable
private fun ComponentRow(book: LicenceBook, c: ThirdPartyComponent, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Read its licence", onClick = onClick).padding(vertical = 6.dp),
    ) {
        Text("${c.name} · ${c.version}", style = MaterialTheme.typography.bodyLarge)
        Text(book.licenceOf(c)?.name ?: c.licence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(c.copyright, style = MaterialTheme.typography.bodySmall, color = muted)
        c.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
    }
}

/**
 * One part ([id], or [ROBOT_LICENCE] for Robot itself): what it is, its licence and copyright line, its notices, and the
 * full text of [file] (null: its licence text), read from the assets and shown in pieces (LiteRT-LM's notices are 2.2 MB).
 */
@Composable
fun LicenceScreen(id: String, file: String?, onOpen: (Page) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val (book, done) = rememberBook()
    val c = book?.byId(id)
    val path = file ?: if (id == ROBOT_LICENCE) book?.robot?.text else c?.let { book.textFile(it) }
    var pieces by remember(path) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(path) {
        if (path != null) pieces = withContext(Dispatchers.Default) { LicenceAssets.text(ctx, path)?.let { LicenceBook.chunks(it) } ?: emptyList() }
    }
    val title = when {
        id == ROBOT_LICENCE -> book?.robot?.name ?: "Robot"
        else -> c?.name ?: "Licence"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = pieces
    Scaffold(topBar = { BackBar(title, onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (book == null || (c == null && id != ROBOT_LICENCE)) {
                item { Text(if (!done) "Loading…" else "Robot's licence list doesn't have this part.") }
                return@LazyColumn
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (c != null) {
                        Text(c.version, style = MaterialTheme.typography.bodyMedium, color = muted)
                        Text(book.licenceOf(c)?.name ?: c.licence, style = MaterialTheme.typography.titleSmall)
                        Text(c.copyright, style = MaterialTheme.typography.bodyMedium)
                        c.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                        Link(c.url, "Its page")
                        book.licenceOf(c)?.url?.let { if (book.textFile(c) == null) Link(it, "The licence") }
                        for (n in c.notices) {
                            OutlinedButton(onClick = { onOpen(Page.Licence(c.id, n.file)) }, enabled = n.file != file,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(n.title) }
                        }
                        if (file != null) {
                            book.textFile(c)?.let { OutlinedButton(onClick = { onOpen(Page.Licence(c.id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Its licence") } }
                            Text(c.notices.firstOrNull { it.file == file }?.title ?: file, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() })
                        }
                    } else {
                        Text(book.robot.copyright, style = MaterialTheme.typography.bodyMedium)
                        Text(LicenceBook.NOTICE, style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                }
            }
            when {
                path == null -> item { Text("No licence text ships for this part: see its page and the licence's link above.", color = muted) }
                text == null -> item { Text("Loading…", color = muted) }
                text.isEmpty() -> item { Text("This text is missing from this build.", color = MaterialTheme.colorScheme.error) }
                else -> items(text.size) { i ->
                    SelectionContainer { Text(text[i], style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
