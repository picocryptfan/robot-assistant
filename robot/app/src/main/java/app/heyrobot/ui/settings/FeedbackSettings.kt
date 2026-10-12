package app.heyrobot.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.DiagnosticBlock
import app.heyrobot.core.feedback.FeedbackDiagnostics
import app.heyrobot.core.feedback.FeedbackDraft
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackFacts
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.feedback.FeedbackPrivacy
import app.heyrobot.core.feedback.FeedbackRef
import app.heyrobot.core.feedback.FeedbackReport
import app.heyrobot.core.feedback.FeedbackSources
import app.heyrobot.core.feedback.SendTo
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.sys.FeedbackClipboard
import app.heyrobot.sys.FeedbackFiles
import app.heyrobot.sys.FeedbackSend
import app.heyrobot.sys.LookStore
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.Paths
import app.heyrobot.sys.Problems
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.CheckboxPreference
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceGroup
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.update.UpdateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// MAIN PROCESS (0.7). Settings › About › Send feedback, for testers: a bug report, an idea or anything else, in your own
// words, with what Robot may add ticked below (core feedback: the model problem's report, the voice log, a settings summary,
// the updater's codes), each with a Preview of exactly what it adds. Share hands the plain-text message to Android's share
// sheet (SMS, Signal, Molly...), Copy puts it on the clipboard, "Text it" opens your SMS app with the developer's number
// (from the menu), and "Open a GitHub issue" opens one in the browser when the updater has a repository. Robot sends nothing
// itself. Opened from About, the Model tab's problems, the voice log, an update error and the panel's feedback phrases; your
// draft survives a rotation or the app being stopped (saved state) and goes only when you tap Done.

/** Where the page was opened from, the kind it opens with, and a problem whose report to add (its time). */
data class FeedbackArgs(val entry: FeedbackEntry, val kind: FeedbackKind?, val problem: Long?) {
    /** The kind the page opens with: the one asked for, else a bug. */
    val startKind: FeedbackKind get() = kind ?: FeedbackKind.BUG

    companion object {
        /** "about/feedback?e=problem&k=bug&p=1760000000000" (SettingsRoutes.feedback). */
        fun of(route: String): FeedbackArgs {
            val q = route.substringAfter('?', "").split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return FeedbackArgs(FeedbackEntry.of(q["e"]), FeedbackKind.of(q["k"]), q["p"]?.toLongOrNull()?.takeIf { it > 0 })
        }
    }
}

/** What the page shows besides your draft: the header's facts and every diagnostic Robot could add now. */
data class FeedbackUi(
    val facts: FeedbackFacts,
    /** In [Diagnostic] order; a problem report only when there is a problem, the updater's only after an error (or from one). */
    val blocks: List<DiagnosticBlock>,
    /** What the last pass leaves out of the diagnostics (core FeedbackPrivacy). */
    val secrets: List<String> = emptyList(),
    /** The number "Text it" opens your SMS app with. */
    val sendTo: String? = null,
    /** The updater's repository: "Open a GitHub issue" there. */
    val github: GitHubRepo? = null,
    /** The problem report's date ("Thu, 9 Oct 2026, 14:05"). */
    val problemDate: String? = null,
)

/** The message as it leaves: its kind, text and which diagnostics it holds (the voice log's bits). */
data class FeedbackOut(val kind: FeedbackKind, val text: String, val bits: Int)

/** What the page's taps do; each returns whether an app opened (false: it says so). */
class FeedbackActions(
    val share: (FeedbackOut) -> Boolean = { true },
    val copy: (FeedbackOut) -> Unit = {},
    val textIt: (FeedbackOut) -> Boolean = { true },
    val github: (FeedbackKind, FeedbackReport.IssueLink) -> Boolean = { _, _ -> true },
    val saveNumber: (String?) -> Unit = {},
    val say: (String) -> Unit = {},
)

const val FEEDBACK_INTRO = "Tell the developer about a bug or an idea. Robot puts your words and what you tick into one plain-text message, and you " +
    "send it with the app you choose: SMS, Signal, Molly or another. Robot sends nothing itself."

const val FEEDBACK_FOOTER = "What goes is exactly what Preview shows: your words, Robot's version, this phone and its Android build, the model and " +
    "the look, and only what you tick. What Robot adds is codes, numbers and switches: never your conversations or History, transcripts, " +
    "messages, memory, People or contacts, notes, calendar, a wake phrase's words (a phrase is its number), a helper's name, address or key, " +
    "the updater's repository or token, a Wi-Fi or Bluetooth name, or where you are.\n\nShare opens Android's share sheet: pick the app and " +
    "the developer. Long messages may arrive as several texts. Copy marks the message as sensitive and clears it from the clipboard after a " +
    "minute if nothing else replaced it. Your draft stays until you tap Done."

private fun Diagnostic.summary(ui: FeedbackUi): String = when (this) {
    Diagnostic.PROBLEM -> "The model problem" + (ui.problemDate?.let { " of $it" } ?: "") + ": codes, this phone and its Android build, the model, how its process ended"
    else -> detail
}

private val Diagnostic.what: String get() = when (this) {
    Diagnostic.PROBLEM -> "the problem report"
    Diagnostic.VOICE_LOG -> "the voice log"
    Diagnostic.SETTINGS -> "the settings summary"
    Diagnostic.UPDATER -> "the updater's codes"
}

/** The page. Your draft lives in its saved state; [prefill] (the panel's words) starts your text once, when it is empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    args: FeedbackArgs,
    ui: FeedbackUi?,
    a: FeedbackActions,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    prefill: String? = null,
    newRef: () -> String = { FeedbackRef.new() },
) {
    var kindName by rememberSaveable { mutableStateOf(args.startKind.name) }
    val kind = FeedbackKind.of(kindName) ?: FeedbackKind.BUG
    var text by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var expected by rememberSaveable { mutableStateOf("") }
    var ticks by rememberSaveable { mutableIntStateOf(Diagnostic.bits(Diagnostic.defaults(args.startKind, args.entry))) }
    var ref by rememberSaveable { mutableStateOf(newRef()) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var numberOpen by rememberSaveable { mutableStateOf(false) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prefill) {
        if (prefill != null && !prefilled) {
            if (text.isBlank()) text = prefill
            prefilled = true
        }
    }
    val available = ui?.blocks.orEmpty().map { it.diagnostic }.toSet()
    val draft = FeedbackDraft(kind, text, steps, expected, Diagnostic.of(ticks).intersect(available))
    val composed = remember(draft, ref, ui) { ui?.let { FeedbackReport.compose(draft, ref, it.facts, it.blocks, it.secrets) } }
    fun out(): FeedbackOut? = composed?.let { FeedbackOut(kind, it.text, Diagnostic.bits(draft.ticked)) }

    PreferenceScreen("Send feedback", onBack, snackbar = snackbar, actions = {
        // The number to text: in the overflow, as a setting of the page rather than of the message.
        Box {
            IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(ui?.sendTo?.let { "Send to: $it" } ?: "Send to…") }, leadingIcon = { Icon(RIcons.Message, null) },
                    onClick = { menu = false; numberOpen = true })
                if (ui?.sendTo != null) {
                    DropdownMenuItem(text = { Text("Remove the number") }, leadingIcon = { Icon(RIcons.Delete, null) },
                        onClick = { menu = false; a.saveNumber(null) })
                }
            }
        }
    }) {
        item(key = "intro", contentType = "intro") { IntroPreference(FEEDBACK_INTRO) }
        item(key = "kind", contentType = "group") {
            PreferenceGroup(null) {
                PreferenceBlock {
                    // No overlap between the buttons (the row overlaps their borders by default): each is a whole 48 dp target.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
                        FeedbackKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(
                                selected = k == kind,
                                onClick = {
                                    if (k != kind) {
                                        kindName = k.name
                                        // Each kind ticks its own defaults: a bug its diagnostics, an idea none.
                                        ticks = Diagnostic.bits(Diagnostic.defaults(k, args.entry))
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, FeedbackKind.entries.size),
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(k.label) },
                            )
                        }
                    }
                }
            }
        }
        preferenceGroup(null, "words") {
            PreferenceBlock {
                val words = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                OutlinedTextField(text, { text = it.take(MAX_FIELD) }, label = { Text(kind.prompt) }, minLines = 3, keyboardOptions = words,
                    supportingText = if (text.length >= MAX_FIELD) ({ Text("${MAX_FIELD / 1000},000 characters at most") }) else null,
                    modifier = Modifier.fillMaxWidth())
                if (kind == FeedbackKind.BUG) {
                    OutlinedTextField(steps, { steps = it.take(MAX_FIELD) }, label = { Text("Steps (optional)") }, minLines = 2, keyboardOptions = words,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(expected, { expected = it.take(MAX_FIELD) }, label = { Text("What you expected (optional)") }, keyboardOptions = words,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
        preferenceGroup("Add to the message", "diagnostics") {
            if (ui == null) PreferenceItem("Reading Robot's codes…")
            else {
                for (b in ui.blocks) {
                    val d = b.diagnostic
                    CheckboxPreference(d.title, d.summary(ui), d in draft.ticked, onCheckedChange = { on ->
                        ticks = if (on) ticks or d.bit else ticks and d.bit.inv()
                    }) {
                        TextButton(onClick = { preview = d.name }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Preview ${d.what}" }) {
                            Text("Preview")
                        }
                    }
                }
                composed?.let { c ->
                    PreferenceItem("Preview the whole message", "About ${c.text.length} characters" + if (c.cut.isNotEmpty()) ", some codes left out to keep it short" else "",
                        onClick = { preview = ALL })
                }
            }
        }
        preferenceGroup("Send", "send") {
            if (sent) {
                StatusPreference("Sent it?", "When the message is on its way, tap Done to clear this draft.", Mark.OK,
                    action = { Button(onClick = { sent = false; text = ""; steps = ""; expected = ""; ref = newRef(); onBack() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } })
            }
            PreferenceBlock {
                val ready = composed != null
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { out()?.let { if (a.share(it)) sent = true } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
                    OutlinedButton(onClick = { out()?.let { a.copy(it); sent = true } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
                    if (ui?.sendTo != null) {
                        OutlinedButton(onClick = { out()?.let { if (a.textIt(it)) sent = true } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Text it")
                        }
                    }
                }
                Text("Share opens Android's share sheet: pick SMS, Signal, Molly or another app, and the developer." +
                    (ui?.sendTo?.let { " Text it opens your SMS app with $it filled in; you press Send there." } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui?.github?.let { repo ->
                    TextButton(onClick = {
                        val link = FeedbackReport.issue(repo, draft, ref, ui.facts, ui.blocks, ui.secrets)
                        if (a.github(kind, link)) sent = true
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open a GitHub issue") }
                    Text("Opens a new issue in your browser with the message filled in, cut to about 7,000 characters if it is longer. " +
                        "A private repository needs you signed in to GitHub there.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        preferenceFooter(FEEDBACK_FOOTER)
    }
    preview?.let { which ->
        val title: String
        val body: String?
        if (which == ALL) {
            title = "The whole message"
            body = composed?.text
        } else {
            val d = Diagnostic.valueOf(which)
            title = d.title
            // Exactly what ticking it adds: the message composed with it ticked.
            body = ui?.let { FeedbackReport.compose(draft.copy(ticked = draft.ticked + d), ref, it.facts, it.blocks, it.secrets).blocks[d] }
        }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(title) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(body ?: "Nothing to add.", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
    if (numberOpen) {
        EditDialog(
            "Send to", ui?.sendTo.orEmpty(), "Phone number",
            "The developer's number: Text it opens your SMS app with it. Kept on this phone, never in the message.",
            validate = { t -> if (t.isBlank() || SendTo.valid(t)) null else "Not a phone number" },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone),
            onDismiss = { numberOpen = false },
            onSave = { t -> numberOpen = false; a.saveNumber(SendTo.normalize(t)) },
        )
    }
}

private const val ALL = "ALL"
private const val MAX_FIELD = 10_000

/** "Thu, 9 Oct 2026, 14:05". */
private fun dateTime(t: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))

/** Hundreds of characters, rounded up, for the voice log. */
private fun hundreds(text: String): Int = (text.length + 99) / 100

/**
 * The page wired to the phone (SettingsNav's "about/feedback" route): the facts and the diagnostics read off the main
 * thread from Robot's own folder, the panel's words taken once, the number from feedback.json, and the ways out.
 */
@Composable
internal fun FeedbackRoute(route: String, snap: Snapshot, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val args = remember(route) { FeedbackArgs.of(route) }
    var ui by remember { mutableStateOf<FeedbackUi?>(null) }
    var prefill by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    fun say(t: String) { scope.launch { snackbar.showSnackbar(t) } }
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            if (args.entry == FeedbackEntry.VOICE) prefill = withContext(Dispatchers.IO) { FeedbackFiles.takePrefill(app) }?.text
            withContext(Dispatchers.IO) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_OPENED, args.entry.id, args.startKind.id) }
        }
    }
    // Once the app's snapshot has this phone's models, the model line says whether the one you chose is ready.
    LaunchedEffect(route, tick, snap.models.isNotEmpty()) { ui = withContext(Dispatchers.IO) { load(app, args, snap) } }
    FeedbackScreen(args, ui, FeedbackActions(
        share = { o ->
            FeedbackSend.start(ctx, FeedbackSend.shareIntent(o.text)).also { ok ->
                if (ok) FeedbackFiles.log(app, VoiceEvent.FEEDBACK_SHARED, o.kind.id, hundreds(o.text), o.bits) else say("No app on this phone can share text.")
            }
        },
        copy = { o ->
            FeedbackClipboard.copy(ctx, o.text)
            FeedbackFiles.log(app, VoiceEvent.FEEDBACK_COPIED, o.kind.id, hundreds(o.text), o.bits)
            say("Copied. Robot clears it from the clipboard in a minute if nothing else replaced it.")
        },
        textIt = { o ->
            val n = ui?.sendTo
            n != null && FeedbackSend.start(ctx, FeedbackSend.textIntent(n, o.text)).also { ok ->
                if (ok) FeedbackFiles.log(app, VoiceEvent.FEEDBACK_TEXTED, o.kind.id, hundreds(o.text), o.bits) else say("No SMS app on this phone.")
            }
        },
        github = { k, link ->
            FeedbackSend.start(ctx, FeedbackSend.browserIntent(link.url)).also { ok ->
                if (ok) FeedbackFiles.log(app, VoiceEvent.FEEDBACK_GITHUB, k.id, (link.chars + 99) / 100, if (link.cut) 1 else 0) else say("No browser on this phone.")
            }
        },
        saveNumber = { n ->
            scope.launch {
                withContext(Dispatchers.IO) { FeedbackFiles.savePrefs(app, FeedbackPrefs(n)) }
                tick++
                say(if (n == null) "The number was removed." else "Text it now opens your SMS app with $n.")
            }
        },
        say = ::say,
    ), onBack, snackbar, prefill)
}

/** Reads what the page shows (files and Android, off the main thread). */
private fun load(app: android.content.Context, args: FeedbackArgs, snap: Snapshot): FeedbackUi {
    val inputs = FeedbackSources.read(Paths.dir(app))
    val s = inputs.settings
    val phone = ModelStore.phone(app)
    val chosen = ModelCatalogue.resolve(s.brainModelChoice, phone)
    val own = if (chosen == BrainModel.OWN) ModelStore.own(app) else null
    val backend = if (chosen == BrainModel.OWN) own?.backend ?: chosen.backend
        else ModelCatalogue.backend(chosen, phone, s.modelGpu, inputs.gpuFailure?.model == chosen)
    // What Robot uses meanwhile when the model you chose isn't ready (the app's own snapshot knows; before it, not said).
    val using = if (snap.models.isNotEmpty() && !snap.ready(chosen)) snap.fast else null
    val facts = FeedbackFacts(
        robot = FeedbackFacts.robot(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG),
        phone = FeedbackFacts.phone(phone, Build.VERSION.RELEASE.orEmpty(), Build.ID.orEmpty(), Build.VERSION.INCREMENTAL.orEmpty()),
        model = FeedbackFacts.model(chosen, backend, inputs.gpuFailure, own?.format, using),
        look = FeedbackFacts.look(LookStore.current(app).classic),
        language = FeedbackFacts.language(Locale.getDefault().toLanguageTag()),
    )
    // The problem asked about, else the open one, else one from the last day.
    val now = System.currentTimeMillis()
    val listed = Problems.list(app)
    val problem: ModelProblem? = args.problem?.let { t -> listed.firstOrNull { it.time == t } ?: Problems.last(app)?.takeIf { it.time == t } }
        ?: Problems.open(app) ?: listed.firstOrNull { now - it.time in 0..86_400_000L }
    val blocks = listOfNotNull(
        problem?.let { FeedbackDiagnostics.problem(Problems.report(app, it)) },
        FeedbackDiagnostics.voiceLog(inputs.voiceLog),
        FeedbackDiagnostics.settings(s, inputs.look, inputs.updates, inputs.memory, inputs.messages),
        if (args.entry == FeedbackEntry.UPDATER || FeedbackDiagnostics.updaterFailed(inputs.updateState)) FeedbackDiagnostics.updater(inputs.updateState) else null,
    )
    val prefs = FeedbackFiles.prefs(app)
    return FeedbackUi(
        facts = facts,
        blocks = blocks,
        secrets = FeedbackPrivacy.secrets(s, inputs.updates, UpdateStore.defaultRepo(app)?.slug, listOfNotNull(prefs.sendTo)),
        sendTo = prefs.sendTo,
        github = UpdateStore.repo(app),
        problemDate = problem?.let { dateTime(it.time) },
    )
}
