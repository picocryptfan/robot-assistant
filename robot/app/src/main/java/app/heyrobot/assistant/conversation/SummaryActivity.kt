package app.heyrobot.assistant.conversation

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.heyrobot.assistant.HelperLink
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.ConsentCard
import app.heyrobot.core.ConversationSettings
import app.heyrobot.core.Disclosure
import app.heyrobot.core.SummaryCheckFor
import app.heyrobot.core.SummaryOffer
import app.heyrobot.core.SummaryPlanner
import app.heyrobot.core.SummaryRecord
import app.heyrobot.core.Transcript
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.TranscriptTitles
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperPrice
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.sys.HandOffIntents
import app.heyrobot.sys.HelperSpendStore
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.Transcripts
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// VOICE PROCESS (Robot's model and the helpers' keys are reached from here only). The summary screen (docs/conversation-mode.md
// 7, phase 5.6), for one transcript, unlocked only: Robot's own summary, made on this phone part by part with a progress
// bar and Cancel, labelled as Robot's, its [14:03] references linked to their lines in Robot › Transcripts, and what the
// number check couldn't find in the transcript listed under it; or "Summarize with a stronger AI": the consent card
// (what goes, to which helper, its price), then your Send. 1.0.0: any helper with its API (the one you chose under "Ask:"
// first, the others a tap away), through its own client, model and price; or a share to a helper's app. A long or important conversation, or a summary whose check flagged
// something, offers the stronger AI first. Nothing leaves the phone before Send.

class SummaryActivity : ComponentActivity() {
    private val id = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        take(intent)
        setContent { RobotTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(i: Intent?) {
        val tid = i?.getStringExtra(Transcripts.EXTRA_ID)?.takeIf { app.heyrobot.core.TranscriptIds.valid(it) }
        id.value = tid
        SummaryJobs.clear(tid)
        // Unlock first: a summary shows what was said (docs/conversation-mode.md 1.7).
        if (Assistant.locked(this)) getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }

    /** Settings › Helpers in Robot's app (0.6.0), where a helper and its key are added. */
    private fun openHelpers() {
        try {
            startActivity(Intent().setClassName(packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.HELPERS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    /** The transcript in Robot's app, at line [line] (a [14:03] link), or at its top. */
    private fun openLine(tid: String, line: Int?) {
        try {
            startActivity(Intent().setClassName(packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.TRANSCRIPTS)
                .putExtra(AppPages.EXTRA_TRANSCRIPT, tid).apply { line?.let { putExtra(AppPages.EXTRA_LINE, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    private sealed class Ask {
        data object Offer : Ask()
        /** [helper]: the helper's id ([others]: the other helpers that could summarize instead). */
        data class Card(val card: ConsentCard, val toApp: Boolean, val helper: String, val others: List<HelperConfig> = emptyList()) : Ask()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val tid by id
        val job by SummaryJobs.state.collectAsState()
        var loaded by remember { mutableStateOf<Pair<TranscriptMeta, Transcript>?>(null) }
        var saved by remember { mutableStateOf<List<Pair<Int, SummaryRecord>>>(emptyList()) }
        var ask by remember { mutableStateOf<Ask?>(null) }
        var note by remember { mutableStateOf<String?>(null) }
        var tick by remember { mutableStateOf(0) }
        val zone = ZoneId.systemDefault()
        val locked = Assistant.locked(this)
        LaunchedEffect(tid, tick, job.record) {
            val t = tid ?: return@LaunchedEffect
            val r = withContext(Dispatchers.IO) {
                val s = Transcripts.store(this@SummaryActivity)
                val m = s.meta(t)
                val tr = s.load(t)
                Triple(m, tr, s.summaries(t))
            }
            loaded = if (r.first != null && r.second != null) r.first!! to r.second!! else null
            saved = r.third
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Summary") }, navigationIcon = { IconButton(onClick = { finish() }) { Icon(RIcons.Back, "Back") } })
        }) { pad ->
            val l = loaded
            val t = tid
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (locked) {
                    item { Text("Unlock your phone to summarize a conversation.", style = MaterialTheme.typography.bodyLarge) }
                    return@LazyColumn
                }
                if (t == null || l == null) {
                    item { Text(if (t == null) "No transcript." else "This transcript was deleted, or can't be read.", style = MaterialTheme.typography.bodyLarge) }
                    return@LazyColumn
                }
                val (meta, tr) = l
                val s = SettingsStore.load(this@SummaryActivity)
                val lang = ConversationSettings.summaryLang(s)
                val parts = SummaryPlanner.plan(SummaryPlanner.lines(meta, tr, zone), lang, BrainKeeperContext.tokens()).parts
                item {
                    Text(TranscriptTitles.shown(meta, zone), style = MaterialTheme.typography.titleLarge)
                    Text("${ConsentCard.minutes(meta, tr)} min · ${tr.lines.size} lines · about $parts part${if (parts == 1) "" else "s"} for Robot's model",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val busy = job.running && job.id == t
                // One summary at a time (Robot's model gives it the time left over).
                if (job.running && job.id != t) item {
                    Text("Robot is summarizing another conversation. This one can start when it's done.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    busy && !job.helper -> item { Progress(job, onCancel = { SummaryJobs.cancel() }) }
                    busy -> item {
                        Card {
                            Text("${job.helperName.ifEmpty { "The helper" }} is summarizing…", style = MaterialTheme.typography.titleMedium)
                            if (job.streaming.isNotEmpty()) Text(job.streaming, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { SummaryJobs.cancel() }) { Text("Stop") }
                        }
                    }
                    ask is Ask.Card -> item {
                        val c = ask as Ask.Card
                        val h = s.helper(c.helper)
                        if (h == null) ask = null else ConsentCardView(c.card, c.toApp, h, onNames = { ask = c.copy(card = c.card.withNames(it)) }, onCancel = { ask = null },
                            onSend = {
                                ask = null
                                if (c.toApp) shareToApp(c.card, h).let { note = it } else {
                                    val chars = c.card.blocks.sumOf { b -> b.text.length }
                                    SummaryJobs.send(this@SummaryActivity, c.card, ClaudeApiHelper(applicationContext, h, chars, budgetUsed(s, h)))
                                }
                            })
                        // Several helpers could summarize: the others, a tap away (each opens its own card, with its own price).
                        if (!c.toApp) for (o in c.others) TextButton(onClick = { ask = cardFor(meta, tr, o) }) { Text("Send to ${o.name} instead") }
                    }
                    ask == Ask.Offer -> item {
                        Card {
                            Text(SummaryOffer.CARD, style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val first = summarizers(s).firstOrNull()
                                Button(onClick = { ask = cardFor(meta, tr) ?: Ask.Offer.also { note = NO_HELPER } }) {
                                    Text(first?.let { "Summarize with ${it.name}" } ?: "Summarize with a stronger AI")
                                }
                                OutlinedButton(onClick = { ask = null; SummaryJobs.local(this@SummaryActivity, t) }) { Text("Robot's summary anyway") }
                            }
                            TextButton(onClick = { ask = null }) { Text("Not now") }
                        }
                    }
                    else -> item {
                        Card {
                            Text("Robot makes its own summary on this phone; a stronger AI summarizes more accurately, but the transcript leaves the phone.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    // A long or important conversation (or the setting) offers the stronger AI first.
                                    if (SummaryOffer.helperFirst(s, parts, meta.important, flagged = false)) ask = Ask.Offer
                                    else SummaryJobs.local(this@SummaryActivity, t)
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Robot's summary") }
                                OutlinedButton(onClick = { ask = cardFor(meta, tr) ?: run { note = NO_HELPER; null } },
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("A stronger AI") }
                            }
                            // A helper's app (installed): a share you send there yourself; Robot doesn't get the summary back.
                            appHelper(s)?.let { a -> TextButton(onClick = { ask = appCard(meta, tr, a) }) { Text("Share it with the ${a.presetOf.defaultName} app instead") } }
                        }
                    }
                }
                note?.let { n -> item {
                    Text(n, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (n == NO_HELPER) TextButton(onClick = ::openHelpers) { Text("Open Settings › Helpers") }
                } }
                job.error?.takeIf { job.id == t && !busy }?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
                // The summary just made, with its check; then the ones kept before.
                val fresh = job.record?.takeIf { job.id == t && !busy }
                if (fresh != null) item {
                    SummaryView(fresh, meta, tr, job.misses, zone, onLine = { openLine(t, it) })
                    // The cost line as under a helper's reply in the panel: "≈ 9¢", "cost unknown", nothing for a free server.
                    val by = s.helpers.firstOrNull { it.name == fresh.helperName }
                    val cost = if (!job.costKnown) "cost unknown" else Pricing.line(job.spentDollars, free = by?.price is HelperPrice.Free || job.spentDollars == 0.0)
                    if (fresh.via == SummaryRecord.VIA_API && s.helperShowCost && cost != null) Text("Cost: $cost",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fresh.via == SummaryRecord.VIA_PHONE && job.misses.isNotEmpty() && s.convAutoOfferHelper) TextButton(onClick = {
                        ask = cardFor(meta, tr) ?: run { note = NO_HELPER; null }
                    }) { Text("Summarize with a stronger AI") }
                }
                val older = saved.filter { (n, _) -> n != job.number || job.id != t }.asReversed()
                if (older.isNotEmpty()) item { Text("Kept with this transcript", style = MaterialTheme.typography.titleSmall) }
                for ((_, r) in older) item { SummaryView(r, meta, tr, emptyList(), zone, onLine = { openLine(t, it) }) }
                item {
                    TextButton(onClick = { openLine(t, null) }) { Text("Open the transcript") }
                }
            }
        }
    }

    private fun keySaved(h: HelperConfig): Boolean = HelperLink.keySaved(this, h)

    /** The helpers that can summarize (their API: a key saved, or none needed), the one you chose under "Ask:" first. */
    private fun summarizers(s: app.heyrobot.core.RobotSettings): List<HelperConfig> = HelperSettings.summarizers(s, ::keySaved)

    /** The helper whose app a share would open, when it is installed (null: none). */
    private fun appHelper(s: app.heyrobot.core.RobotSettings): HelperConfig? =
        HelperSettings.appHelper(s, null, ::keySaved)?.takeIf { h -> h.presetOf.app?.let { HandOffIntents.installed(this, it) } == true }

    /** Today's budget for helpers, or [h]'s own cap, is used up: the card says so (as an ask you make yourself, it still sends). */
    private fun budgetUsed(s: app.heyrobot.core.RobotSettings, h: HelperConfig): Boolean = try {
        (HelperSpendStore.cached() ?: HelperSpendStore.current(this)).reached(LocalDate.now(), s.helperBudgetCents, h.id, h.budgetCents)
    } catch (_: Throwable) { false }

    /**
     * The consent card for sending [tr] to helper [pick] (null: the first that can summarize), through its API, with its
     * model and its price; null when no helper can.
     */
    private fun cardFor(meta: TranscriptMeta, tr: Transcript, pick: HelperConfig? = null): Ask.Card? {
        val s = SettingsStore.load(this)
        val all = summarizers(s)
        val h = pick?.takeIf { p -> all.any { it.id == p.id } } ?: all.firstOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val lang = ConversationSettings.summaryLang(s)
        val region = s.regionFor(app.heyrobot.sys.Phones.deviceRegion(this))
        val id = ClaudeApiHelper.idFor(h)
        // The price on the card is estimated from the very text it would send, at the helper's own price.
        val first = ConsentCard.build(id, h.name, Disclosure(h.presetOf.company, null, ""), meta, tr, ZonedDateTime.now(zone), region, lang, zone)
        val chars = first.blocks.sumOf { it.text.length }
        val card = ConsentCard.build(id, h.name, ClaudeApiHelper(this, h, chars, budgetUsed(s, h)).disclosure, meta, tr, ZonedDateTime.now(zone), region, lang, zone)
        return Ask.Card(card, false, h.id, all.filter { it.id != h.id })
    }

    /** The consent card for sharing [tr] with helper [h]'s app. */
    private fun appCard(meta: TranscriptMeta, tr: Transcript, h: HelperConfig): Ask.Card {
        val s = SettingsStore.load(this)
        val zone = ZoneId.systemDefault()
        val lang = ConversationSettings.summaryLang(s)
        val region = s.regionFor(app.heyrobot.sys.Phones.deviceRegion(this))
        val a = ClaudeAppHelper(this, h)
        return Ask.Card(ConsentCard.build(a.id, "the ${h.presetOf.defaultName} app", a.disclosure, meta, tr, ZonedDateTime.now(zone), region, lang, zone), true, h.id)
    }

    /** A helper's app: its share (or the clipboard and the app); what to tell you. */
    private fun shareToApp(card: ConsentCard, h: HelperConfig): String {
        val name = h.presetOf.defaultName
        if (ConsentCard.appShare(card) == null) {
            SummaryJobs.shared(card, app.heyrobot.core.AppShareCode.TOO_LONG)
            return ConsentCard.TOO_LONG_FOR_APP
        }
        val job = card.approve(System.currentTimeMillis())
        val (i, how) = ClaudeAppHelper(this, h).intent(job) ?: return "The $name app isn't there, or doesn't take a share."
        return try {
            startActivity(i)
            SummaryJobs.shared(card, how)
            if (how == app.heyrobot.core.AppShareCode.CLIPBOARD) "It's copied: paste it in $name and press Send there." else "Press Send in $name. Robot doesn't hear $name's answer."
        } catch (_: Throwable) { "The $name app didn't open." }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }

    @Composable
    private fun Progress(job: SummaryJobs.Ui, onCancel: () -> Unit) {
        Card {
            Text(if (job.merging) "Putting the notes together…" else if (job.steps > 0) "Reading part ${minOf(job.step + 1, job.steps)}…" else "Getting Robot's model ready…",
                style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { if (job.steps > 0) job.step.toFloat() / job.steps else 0f }, modifier = Modifier.fillMaxWidth())
            Text("Robot's model works on it between other things (your questions, translations come first). It can stop and go on later.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    @Composable
    private fun ConsentCardView(card: ConsentCard, toApp: Boolean, h: HelperConfig, onNames: (Boolean) -> Unit, onCancel: () -> Unit, onSend: () -> Unit) {
        Card {
            Text(card.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(card.stats + " " + card.includes, style = MaterialTheme.typography.bodyMedium)
            Text(card.disclosure.line, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onNames(!card.anonymized) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = card.anonymized, onCheckedChange = onNames)
                Text(ConsentCard.TOGGLE, style = MaterialTheme.typography.bodyMedium)
            }
            val tooLong = if (toApp) ConsentCard.appShare(card) == null else !ConsentCard.apiFits(card.blocks, ClaudeApiHelper.maxInputTokens(h))
            if (tooLong) Text(if (toApp) ConsentCard.TOO_LONG_FOR_APP else ConsentCard.TOO_LONG_FOR_API, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = onSend, enabled = !tooLong, modifier = Modifier.heightIn(min = 48.dp)) { Text("Send") }
            }
        }
    }

    /** A summary with its label; its [14:03] references open their lines; what the check couldn't find, under it. */
    @Composable
    private fun SummaryView(r: SummaryRecord, meta: TranscriptMeta, tr: Transcript, misses: List<app.heyrobot.core.SummaryMiss>, zone: ZoneId, onLine: (Int) -> Unit) {
        Card {
            Text(r.label(zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val links = SummaryCheckFor.links(r.text, meta, tr, zone)
            val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
            val text = buildAnnotatedString {
                var at = 0
                for ((range, line) in links) {
                    append(r.text.substring(at, range.first))
                    withLink(LinkAnnotation.Clickable("line$line", linkStyle) { onLine(line) }) { append(r.text.substring(range)) }
                    at = range.last + 1
                }
                append(r.text.substring(at))
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            for (m in misses) Text(m.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    companion object {
        private const val NO_HELPER = "To use a stronger AI, add a helper with its API key (or your own server) in Settings › Helpers."
    }
}

/** The loaded model's context for the plan shown (4,096 when nothing is loaded yet). */
private object BrainKeeperContext {
    fun tokens(): Int = app.heyrobot.assistant.BrainKeeper.state.value.contextTokens
}
