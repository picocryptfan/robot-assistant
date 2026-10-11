package app.heyrobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ClaudeCost
import app.heyrobot.core.ClaudeEffortSetting
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.HelperName
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.helpers.EffortStyle
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFacts
import app.heyrobot.core.helpers.HelperKind
import app.heyrobot.core.helpers.HelperNames
import app.heyrobot.core.helpers.HelperPrice
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.helpers.NameCheck
import app.heyrobot.core.helpers.Preset
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.helpers.PriceTable
import app.heyrobot.core.helpers.Protocol
import app.heyrobot.core.helpers.Rates
import app.heyrobot.core.helpers.SearchStyle
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.HelperWindow
import app.heyrobot.ui.common.RIcons
import java.util.Locale
import kotlin.random.Random

// MAIN PROCESS. Settings › Helpers (0.6.0): the other AIs Robot can ask when you say so (Claude, another company's model,
// or a model on your own computer), each with its own page, and "Add a helper". Keys never pass through this process:
// they are pasted in the helper's connection window, in the assistant's process (HelperWindow), which also runs Test
// connection; this screen only shows the masked key and what Test connection found (AssistantFacts).

private fun keySaved(f: AssistantFacts?, h: HelperConfig) = f?.keys?.containsKey(h.id) == true

private fun factsOf(f: AssistantFacts?, h: HelperConfig): HelperFacts = f?.helperFacts?.get(h.id) ?: HelperFacts()

/** The words of your wake phrases: a helper can't be called one of them. */
private fun wakeWords(s: RobotSettings): Set<String> = s.wakePhrases.flatMap { HelperName.words(it.text) }.toSet()

/** "$0.15", "$0.075", "$2", "$12.50": a price per million tokens. */
private fun usd(d: Double): String = "$" + when {
    d >= 1 && d == Math.floor(d) -> String.format(Locale.US, "%.0f", d)
    d >= 0.1 -> String.format(Locale.US, "%.2f", d)
    else -> String.format(Locale.US, "%.3f", d).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }
}

/** Whether [h] can be used now, and how it is: for its line in Settings and its page. */
private fun status(h: HelperConfig, f: AssistantFacts?): Pair<Mark, String> {
    val p = h.presetOf
    val key = keySaved(f, h)
    val facts = factsOf(f, h)
    return when {
        !h.on -> Mark.TODO to "Off"
        f?.keysUnreadable?.contains(h.id) == true -> Mark.WARN to "The saved key couldn't be read and was removed: add it again"
        p.app != null && HelperSettings.route(h, key) == ClaudeRoute.APP ->
            if (f?.claudeApp?.installed == false) Mark.TODO to "No key, and the ${p.defaultName} app isn't installed" else Mark.OK to "Opens the ${p.defaultName} app"
        p.keyRequired && !key -> Mark.TODO to "No key"
        p.protocol == Protocol.ANTHROPIC -> Mark.OK to "Ready"
        facts.address(h) == null -> Mark.TODO to "Test connection to find the server"
        facts.checkedAt == 0L -> Mark.TODO to "Not tested yet"
        facts.forAddress != null && facts.forAddress != h.baseUrl -> Mark.TODO to "Test connection again: the address changed"
        facts.failure != null -> Mark.WARN to facts.failure!!.say(HelperWho.of(h))
        facts.answered -> Mark.OK to "Ready" + if (facts.ms > 0) String.format(Locale.US, " · answered in %.1f s", facts.ms / 1000.0) else ""
        else -> Mark.OK to "Connected"
    }
}

/** "Anthropic · Claude Opus 5.5" or "Your server · qwen3:32b". */
private fun helperLine(h: HelperConfig): String {
    val model = h.catalogModel?.title ?: h.model.ifEmpty { "no model chosen" }
    return (if (h.home) "Your server" else h.presetOf.company) + " · " + model + if (h.trust) " · trusted with personal content" else ""
}

@Composable
private fun HelperRow(h: HelperConfig, f: AssistantFacts?, onClick: () -> Unit) {
    val (_, st) = status(h, f)
    Box(Modifier.alpha(if (h.on) 1f else 0.55f)) {
        NavRow(if (h.home) RIcons.Home else RIcons.Cloud, h.name, helperLine(h) + "\n" + st, onClick)
    }
}

/** Settings' Helpers card: each helper on one line, who Robot checks with, today's spend, and the way to Settings › Helpers. */
@Composable
fun HelpersCard(s: RobotSettings, snap: Snapshot, a: AppActions) {
    val f = snap.facts
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("Helpers") {
        Text("Other AIs Robot asks only when you say so (“ask Claude …”, “go get Gemini”) or when you let it check hard questions. " +
            "Only text leaves the phone.", style = MaterialTheme.typography.bodyMedium, color = muted)
        for (h in s.helpers) HelperRow(h, f) { a.openPage(Page.Helper(h.id)) }
        val target = HelperSettings.target(s) { keySaved(f, it) }
        Text("When Robot needs help: " + (target?.let { "${HelperSettings.escalation(s, it, keySaved(f, it)).title} · ${it.name}" } ?: "no helper to ask"),
            style = MaterialTheme.typography.bodyMedium)
        Text("Today: ${ClaudeCost.dollarsText(f?.spend?.total ?: 0)} of ${ClaudeCost.dollarsText(s.helperBudgetCents * 10_000L)} (an estimate)",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        NavRow(RIcons.Settings, "Helpers", "Add a helper, choose who Robot asks, the daily budget and the cost line") { a.openPage(Page.Helpers) }
    }
}

/** Settings › Helpers: your helpers, "Add a helper", when Robot may ask one by itself, and the budget for all of them. */
@Composable
fun HelpersScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = snap.facts
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(topBar = { BackBar("Helpers", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Robot stays offline unless you ask a helper (“ask Claude …”, “go get Jarvis”) or let Robot check hard questions with one. " +
                "Only text leaves the phone: your voice is turned into text here, and a helper's answer is read by Robot's voices.",
                style = MaterialTheme.typography.bodyMedium, color = muted)
            SectionCard("Your helpers") {
                if (s.helpers.isEmpty()) Text("None: Robot answers everything itself.", style = MaterialTheme.typography.bodyMedium, color = muted)
                for (h in s.helpers) HelperRow(h, f) { a.openPage(Page.Helper(h.id)) }
                FilledTonalButton(onClick = { a.openPage(Page.AddHelper) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a helper")
                }
            }
            SectionCard("When Robot needs help") {
                val target = HelperSettings.target(s) { keySaved(f, it) }
                val help = target?.let { HelperSettings.escalation(s, it, keySaved(f, it)) } ?: s.help ?: Escalation.ASK
                val ready = target != null && HelperSettings.apiReady(target, keySaved(f, target))
                for (e in Escalation.entries) {
                    val note = if (e == Escalation.AUTO && target != null && !ready) " ${target.name} needs ${if (target.presetOf.keyRequired) "an API key" else "Test connection"} first: until then Robot asks first." else ""
                    ChoiceRow(e.title, e.detail + note, help == e) { update(s.copy(help = e)) }
                }
                val on = s.helpers.filter { it.on }
                if (on.size > 1) {
                    Text("Ask", style = MaterialTheme.typography.titleSmall)
                    for (h in on) ChoiceRow(h.name, helperLine(h), target?.id == h.id) { update(s.copy(helpDefault = h.id)) }
                }
                if (target != null && help == Escalation.AUTO && !target.price.known) {
                    Text("Robot doesn't know ${target.name}'s prices, so it asks first instead of checking by itself. Enter them on ${target.name}'s page.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (target != null && !target.searching) {
                    Text("Questions about news, prices or anything current go to ${target.name} by itself only when it can search the web; " +
                        "otherwise Robot answers and offers.", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                Text(if (s.personalToHelpers) "By itself, Robot sends your question and the last two turns of the conversation, with any screen text, " +
                    "photo or what it read (Safety & privacy › Send personal content to helpers automatically is on)."
                    else "By itself, Robot sends only your question and the last two turns of plain conversation. Once a conversation has screen text, " +
                    "a photo, or what Robot read from your contacts, calendar or notes, the weather (where you are) or your phone's status, it sends " +
                    "only your question (more for helpers you trust with personal content); anything more goes only after you tap a card that lists it.",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            SectionCard("Daily budget for all helpers") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in RobotSettings.CLAUDE_BUDGETS) {
                        FilterChip(selected = s.helperBudgetCents == c, onClick = { update(s.copy(helperBudgetCents = c)) },
                            label = { Text(ClaudeCost.dollarsText(c * 10_000L).removeSuffix(".00")) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                val spend = f?.spend
                Text("Today: ${ClaudeCost.dollarsText(spend?.total ?: 0)} of ${ClaudeCost.dollarsText(s.helperBudgetCents * 10_000L)} (an estimate). " +
                    "Once it's reached, Robot asks before checking with a helper by itself until tomorrow. A helper whose prices Robot doesn't " +
                    "know isn't counted.", style = MaterialTheme.typography.bodyMedium, color = muted)
                for (h in s.helpers) spend?.by?.get(h.id)?.takeIf { it > 0 }?.let {
                    Text("${h.name}: ${ClaudeCost.dollarsText(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                SwitchRow("Show cost", "“≈ 1¢” under each helper's reply, estimated from the published prices (or the price the company reports); " +
                    "“cost unknown” when Robot can't tell.", s.helperShowCost) { update(s.copy(helperShowCost = it)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** "Add a helper": who (a company, your own server, or another company's compatible API), then its page and the key window. */
@Composable
fun AddHelperScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onAdded: (String) -> Unit, onBack: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var asking by remember { mutableStateOf<Preset?>(null) }
    var trustFor by remember { mutableStateOf<String?>(null) }

    fun add(p: Preset, base: String?, trust: Boolean = false) {
        val taken = s.helpers.map { it.id }.toSet()
        // Claude keeps its id (its key file, the Claude app, the settings mirror) when you add it back.
        val id = if (p.kind == HelperKind.ANTHROPIC && HelperConfig.CLAUDE_ID !in taken) HelperConfig.CLAUDE_ID else HelperConfig.newId(taken) { Random.nextInt(it) }
        val wake = wakeWords(s)
        val name = (listOf(p.defaultName) + (2..9).map { "${p.defaultName} $it" }).firstOrNull { HelperNames.check(it, id, s, wake) !is NameCheck.Problem } ?: p.defaultName
        val robot = s.voiceChoice.takeIf { it.bundle == VoiceBundle.KOKORO }?.speaker
        val h = HelperConfig.of(p, id, HelperSettings.freeVoice(s, robot), name, base).copy(trust = trust)
        update(s.withHelper(h))
        onAdded(id)
        // A company's key window (Test connection follows by itself), or Test connection for your server.
        when {
            p.home -> a.helperWindow(id, HelperWindow.MODE_TEST)
            else -> a.helperWindow(id, HelperWindow.MODE_KEY)
        }
    }

    Scaffold(topBar = { BackBar("Add a helper", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Who should Robot be able to ask? A company's model needs an API key from that company (paid separately from any app " +
                "subscription). A model on your own computer needs no key and costs nothing.", style = MaterialTheme.typography.bodyMedium, color = muted)
            SectionCard("Companies") {
                for (p in Presets.ALL.filter { !it.needsAddress }) {
                    val detail = p.models.take(3).joinToString(", ") { it.title }.ifEmpty { "Hundreds of models from many companies, with one key" }
                    NavRow(RIcons.Cloud, p.title, detail) { add(p, null) }
                }
            }
            SectionCard("Your own AI") {
                NavRow(RIcons.Home, Presets.HOME.title, "A computer on your network, or reached through Tailscale. Free, and private when the computer is yours.") {
                    asking = Presets.HOME
                }
            }
            SectionCard("Other") {
                NavRow(RIcons.Cloud, Presets.OTHER.title, "Any company with an OpenAI-compatible API: you enter its address (https).") { asking = Presets.OTHER }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    asking?.let { p ->
        AddressDialog(p, null, onDismiss = { asking = null }) { address ->
            asking = null
            if (p.home) trustFor = address else add(p, address)
        }
    }
    trustFor?.let { address ->
        AlertDialog(
            onDismissRequest = { trustFor = null; add(Presets.HOME, address) },
            title = { Text("Is this computer yours?") },
            text = {
                Text("Is this computer yours, and does it run the model itself (not passing questions on to a company)? If yes, Robot can include " +
                    "what it read for you (your calendar, notes, contacts) when it asks ${Presets.HOME.defaultName}. Your screen and photos still " +
                    "need a tap every time. You can change this on its page.")
            },
            confirmButton = { TextButton(onClick = { trustFor = null; add(Presets.HOME, address, trust = true) }) { Text("Yes, it's mine") } },
            dismissButton = { TextButton(onClick = { trustFor = null; add(Presets.HOME, address) }) { Text("Not now") } },
        )
    }
}

/** Why Robot won't use [typed] as [p]'s address, or null when it may. */
private fun addressProblem(p: Preset, typed: String): String? {
    val t = LanRule.normalize(typed) ?: return "That isn't an address Robot can use: a name or IP address, with http:// or https:// if you like, and a port."
    val lit = LanRule.literal(t.host)
    return when {
        !p.home && !t.https -> "Robot connects to a company only with https://."
        p.home && !t.https && lit != null && !LanRule.classify(lit).cleartext -> LanRule.NOT_LOCAL
        lit != null && LanRule.classify(lit) == LanRule.Range.BLOCKED -> "That address can't be a server."
        else -> null
    }
}

/** The address of your server, or of another company's compatible API ([current]: the one set now). */
@Composable
private fun AddressDialog(p: Preset, current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(current.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.home) "Your server's address" else "The API's address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (p.home) "The computer running Ollama, LM Studio, llama.cpp, vLLM, LocalAI or Open WebUI: its IP address or name, such as " +
                    "192.168.1.20, desktop.lan or https://box.tail1234.ts.net. Without a port, Robot tries the usual ones."
                    else "Its OpenAI-compatible address, such as https://api.example.com/v1.", style = MaterialTheme.typography.bodyMedium, color = muted)
                OutlinedTextField(
                    value = v, onValueChange = { v = it.trim(); problem = null }, singleLine = true, label = { Text("Address") }, isError = problem != null,
                    supportingText = { problem?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { problem = addressProblem(p, v); if (problem == null) onSave(v) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val t = LanRule.normalize(v)
                if (p.home && t != null && !t.https) {
                    Text("Without https, anyone on the same Wi-Fi can read what Robot sends. Fine on your home network; on a shared one, use " +
                        "Tailscale or https.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                t?.hint?.let { Text("This is $it.", style = MaterialTheme.typography.bodySmall, color = muted) }
                t?.host?.let(LanRule::literal)?.let { LanRule.classify(it).warning }?.let { Text("This is $it.", style = MaterialTheme.typography.bodySmall, color = muted) }
            }
        },
        confirmButton = { TextButton(onClick = { problem = addressProblem(p, v); if (problem == null) onSave(v) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A helper's page: its connection, name, model, what it may do, its voice, trust, cost, and Remove. */
@Composable
fun HelperScreen(id: String, s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val h = s.helper(id)
    if (h == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    val f = snap.facts
    val p = h.presetOf
    val key = keySaved(f, h)
    val facts = factsOf(f, h)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun set(n: HelperConfig) = update(s.withHelper(n))
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editAddress by remember { mutableStateOf(false) }
    Scaffold(topBar = { BackBar(h.name, onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                SwitchRow(h.name, if (h.home) "Your own server" else p.title, h.on) { set(h.copy(enabled = it)) }
                val (mark, st) = status(h, f)
                StatusRow(mark, "Status", st)
            }
            SectionCard("Connection") {
                if (p.needsAddress) {
                    StatusRow(if (facts.address(h) != null) Mark.OK else Mark.TODO, "Address", h.baseUrl ?: "None") {
                        TextButton(onClick = { editAddress = true }) { Text("Change") }
                    }
                    if (h.home && LanRule.normalize(h.baseUrl.orEmpty())?.https == false) {
                        Text("Not encrypted: anyone on the same Wi-Fi can read what Robot sends to ${h.name}" +
                            (if (h.trust) ", including the personal content you trust it with." else ".") +
                            " Use https or Tailscale on a network you share.", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                }
                val unreadable = f?.keysUnreadable?.contains(h.id) == true
                StatusRow(if (key) Mark.OK else if (unreadable) Mark.WARN else Mark.TODO, f?.keys?.get(h.id) ?: if (p.keyRequired) "No key" else "No key (not needed unless your server asks)",
                    when {
                        unreadable -> "Robot couldn't read the saved key (Android's keystore lost it), so it was removed. Add it again."
                        p.keyWhere != null -> "From ${p.keyWhere}. " + p.billing.orEmpty()
                        else -> p.billing
                    }) {
                    Row {
                        if (key) {
                            TextButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_KEY) }) { Text("Replace") }
                            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                        } else {
                            FilledTonalButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_KEY) }) { Text("Add key") }
                        }
                    }
                }
                if (p.protocol != Protocol.ANTHROPIC) {
                    val checked = if (facts.checkedAt > 0) "Last checked: " + (if (facts.answered) "a test question was answered" else "the server answered") +
                        (if (facts.models.isNotEmpty()) ", ${facts.models.size} models listed" else "") + "." else "Robot hasn't reached ${h.name} yet."
                    Text(checked, style = MaterialTheme.typography.bodyMedium, color = muted)
                    OutlinedButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_TEST) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test connection") }
                }
                p.dataUse?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                if (p.quirks.zdr) {
                    SwitchRow("Only providers that keep nothing", "Zero data retention: OpenRouter sends your questions only to providers that don't store " +
                        "them. Some models have none; then ${h.name} says so.", h.zdr) { set(h.copy(zdr = it)) }
                }
            }
            if (p.app != null) {
                SectionCard("Use") {
                    val route = HelperSettings.route(h, key)
                    for (r in ClaudeRoute.entries) {
                        val note = when {
                            r == ClaudeRoute.APP && f?.claudeApp?.installed == false -> " The ${p.defaultName} app isn't installed."
                            r == ClaudeRoute.API && !key -> " Add a key first."
                            else -> ""
                        }
                        ChoiceRow(r.title, r.detail + note, route == r) { set(h.copy(route = r)) }
                    }
                }
            }
            SectionCard("Name used by voice") {
                NameField(h, s) { set(it) }
                AliasesField(h, s) { set(it) }
            }
            SectionCard("Model") { ModelPicker(h, facts, a) { set(it) } }
            val effortStyle = h.catalogModel?.effort ?: p.quirks.effort
            if (p.protocol == Protocol.ANTHROPIC || effortStyle != EffortStyle.NONE) {
                SectionCard("How hard ${h.name} thinks") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ClaudeEffortSetting.entries.forEachIndexed { i, v ->
                            SegmentedButton(selected = h.effort == v, onClick = { set(h.copy(effort = v)) },
                                shape = SegmentedButtonDefaults.itemShape(i, ClaudeEffortSetting.entries.size)) { Text(v.title) }
                        }
                    }
                    Text("Automatic: low for spoken questions (fast), medium for typed ones, high when you ask ${h.name} to “think hard” or “take your time”." +
                        when (effortStyle) {
                            EffortStyle.MISTRAL -> " Mistral thinks either fully or not at all: Low means no thinking."
                            EffortStyle.DEEPSEEK -> " For DeepSeek, High means its maximum."
                            else -> ""
                        }, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            SectionCard("What ${h.name} may do") {
                val canSearch = p.canSearch && (h.catalogModel?.search ?: (p.quirks.search == SearchStyle.OPENROUTER))
                SwitchRow("Let ${h.name} search the web", if (canSearch) searchNote(h) else p.searchNote ?: "This model can't search the web through this connection.",
                    h.search && canSearch, enabled = canSearch) { set(h.copy(search = it)) }
                val noTools = h.catalogModel?.tools == false || facts.noActions(h.model)
                SwitchRow("Let ${h.name} ask Robot to do things", if (noTools) "This model didn't use Robot's actions when Robot checked, so it gets none."
                    else "Timers, notes, messages and Robot's other actions. Each one shows a card first.", h.actions && !noTools, enabled = !noTools) {
                    set(h.copy(actions = it))
                }
                if (p.protocol != Protocol.ANTHROPIC && h.actions) {
                    TextButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_ACTIONS) }) { Text("Check actions") }
                }
                SwitchRow("Allow on lock screen", "Off: a locked phone can't show who is asking.", h.locked) { set(h.copy(locked = it)) }
            }
            SectionCard("${h.name}'s voice") {
                for ((name, _) in ClaudeVoices.KOKORO) ChoiceRow(ClaudeVoices.title(name), s.helpers.firstOrNull { it.id != h.id && it.voice == name }?.let { "Also ${it.name}'s" },
                    ClaudeVoices.of(h.voice) == name) { set(h.copy(voice = name)) }
                Text("A Kokoro voice, so you hear who is talking. With the Kitten voice helpers use Kitten's other voice; with the phone's voice, a lower pitch.",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            SectionCard("Personal content") {
                SwitchRow("Trusted with personal content", "Off (the default): when Robot checks with ${h.name} by itself in a conversation that holds " +
                    "something personal (what Robot read from your calendar, notes or contacts, where you are, your phone's status), only your question " +
                    "goes; “go get ${h.name}” asks before including the conversation; a spoken yes never sends personal content. On: all of that goes " +
                    "to ${h.name} without asking. Your screen and photos still need a tap every time. Turn it on only for a helper you'd show your " +
                    "calendar to: a server that is yours, or a company whose terms you accept.", h.trust) { set(h.copy(trust = it)) }
                if (s.personalToHelpers) {
                    Text("Safety & privacy › Send personal content to helpers automatically is on, so every helper gets this already.",
                        style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            SectionCard("Cost") { CostSection(h, f) { set(it) } }
            OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove ${h.name}") }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (editAddress) {
        AddressDialog(p, h.baseUrl, onDismiss = { editAddress = false }) { address ->
            editAddress = false
            if (address != h.baseUrl) {
                // A new address may be another computer: trust starts over, and Test connection finds the server.
                set(h.copy(baseUrl = address, trust = false))
                if (h.trust) a.say("“Trusted with personal content” was turned off: the address changed.")
                a.helperWindow(h.id, HelperWindow.MODE_TEST)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the ${h.name} key?") },
            text = { Text("Robot removes the key from this phone." + if (p.app != null) " ${h.name} uses the ${p.defaultName} app until you add a key again." else "") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; a.deleteHelperKey(h.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${h.name}?") },
            text = { Text("Robot deletes its key and what it found about it. Conversations in History keep its answers.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; a.removeHelper(h.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** What a search costs with [h], from the provider's published price. */
private fun searchNote(h: HelperConfig): String {
    val per = if (h.presetOf.protocol == Protocol.ANTHROPIC) 0.01 else PriceTable.of(h.model)?.perSearch
    val price = per?.let { if (it >= 0.01) "about ${(it * 100).let { c -> if (c == Math.floor(c)) c.toInt().toString() else c.toString() }}¢ each" else "about ${it * 100}¢ each" }
    return when (h.presetOf.quirks.search) {
        SearchStyle.ANTHROPIC -> "Up to 3 searches per question, $price. The answer lists the sites."
        SearchStyle.OPENROUTER -> "OpenRouter's web search (5 results); its price is part of the cost OpenRouter reports. The answer lists the sites."
        else -> "${h.presetOf.company}'s web search" + (price?.let { ", $it" } ?: "") + ". The answer lists the sites."
    }
}

@Composable
private fun NameField(h: HelperConfig, s: RobotSettings, set: (HelperConfig) -> Unit) {
    var v by remember(h.name) { mutableStateOf(h.name) }
    val check = HelperNames.check(v, h.id, s, wakeWords(s))
    fun save() { if (check !is NameCheck.Problem && v.trim() != h.name) set(h.copy(name = HelperNames.clean(v))) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = v, onValueChange = { v = it }, label = { Text("Name") }, singleLine = true, isError = check is NameCheck.Problem,
            supportingText = {
                Text(when (check) {
                    is NameCheck.Problem -> check.why
                    is NameCheck.Warning -> check.why
                    NameCheck.Ok -> "Say “ask ${v.trim()} …” or “go get ${v.trim()}”."
                })
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.weight(1f),
        )
        if (v.trim() != h.name && check !is NameCheck.Problem) TextButton(onClick = { save() }) { Text("Save") }
    }
    if (h.presetOf.misheard.isNotEmpty() && h.name == h.presetOf.defaultName) {
        Text("Robot also hears “${h.presetOf.misheard.take(3).joinToString("”, “")}” as ${h.name}, but only after “ask” or “go get”.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AliasesField(h: HelperConfig, s: RobotSettings, set: (HelperConfig) -> Unit) {
    val shown = h.aliases.joinToString(", ")
    var v by remember(shown) { mutableStateOf(shown) }
    val wake = wakeWords(s)
    val names = v.split(',').map(HelperNames::clean).filter { it.isNotEmpty() }.distinct()
    val bad = names.firstNotNullOfOrNull { n -> (HelperNames.check(n, h.id, s, wake) as? NameCheck.Problem)?.let { "$n: ${it.why}" } }
    fun save() { if (bad == null) set(h.copy(aliases = names.filter { !it.equals(h.name, ignoreCase = true) }.take(HelperConfig.MAX_ALIASES))) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = v, onValueChange = { v = it }, label = { Text("Also answers to") }, singleLine = true, isError = bad != null,
            supportingText = { Text(bad ?: "Other names you call it, separated by commas (at most ${HelperConfig.MAX_ALIASES}).") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.weight(1f),
        )
        if (v.trim() != shown && bad == null) TextButton(onClick = { save() }) { Text("Save") }
    }
}

/** The model: the catalog's (recommended first, with what each can do), the server's list, or any id you type. */
@Composable
private fun ModelPicker(h: HelperConfig, facts: HelperFacts, a: AppActions, set: (HelperConfig) -> Unit) {
    val p = h.presetOf
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun badges(tools: Boolean, search: Boolean, vision: Boolean) = listOfNotNull(
        "can use Robot's actions".takeIf { tools }, "can search the web".takeIf { search }, "can see pictures".takeIf { vision },
    ).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " (${it.replaceFirstChar { c -> c.uppercase() }}.)" }.orEmpty()
    if (p.protocol == Protocol.ANTHROPIC) {
        for (m in ClaudeModel.entries) ChoiceRow(m.title, m.detail, ClaudeModel.of(h.model) == m) { set(h.copy(model = m.id)) }
        return
    }
    // A change of model: your prices were for the other one, and the server's refusals are per model.
    fun pick(id: String) { if (id != h.model) set(h.copy(model = id)) }
    for (m in p.models) ChoiceRow(m.title, m.detail + badges(m.tools, m.search, m.vision), h.model == m.id) { pick(m.id) }
    val listed = (if (facts.forAddress == null || facts.forAddress == h.baseUrl) facts.models else emptyList()).filter { id -> p.models.none { it.id == id } }
    var query by remember { mutableStateOf("") }
    if (listed.size > 8) {
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search ${listed.size} models") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
    }
    val shown = listed.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.take(40)
    for (m in shown) ChoiceRow(m, null, h.model == m) { pick(m) }
    if (shown.size < listed.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.size) {
        Text("More models match: narrow the search.", style = MaterialTheme.typography.bodyMedium, color = muted)
    }
    if (h.model.isNotEmpty() && p.models.none { it.id == h.model } && h.model !in listed) ChoiceRow(h.model, "Entered by you", true) {}
    var typed by remember(h.model) { mutableStateOf(h.model) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = typed, onValueChange = { typed = it.trim().take(HelperConfig.MAX_MODEL) }, singleLine = true, label = { Text("Model id") },
            supportingText = { Text("Any model ${if (h.home) "your server has" else p.company + " offers"}, exactly as it's named.") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (typed.isNotEmpty()) pick(typed) }),
            modifier = Modifier.weight(1f),
        )
        if (typed != h.model && typed.isNotEmpty()) TextButton(onClick = { pick(typed) }) { Text("Use") }
    }
    if (listed.isEmpty() && p.models.isEmpty()) {
        Text("Test connection lists the models ${if (h.home) "your server has" else "you can use"}.", style = MaterialTheme.typography.bodyMedium, color = muted)
    }
    TextButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_TEST) }) { Text("Refresh list") }
}

/** Where the price comes from (and when it was read), your own prices, this helper's daily cap and today's spend. */
@Composable
private fun CostSection(h: HelperConfig, f: AssistantFacts?, set: (HelperConfig) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = when (val pr = h.price) {
        HelperPrice.Free -> "Free: your own server. If you pay for its electricity or a rented machine, you can add a price."
        is HelperPrice.Yours -> "Your prices: ${usd(pr.rates.input)} per million tokens in, ${usd(pr.rates.output)} out."
        is HelperPrice.Published -> {
            val c = pr.card
            "${usd(c.rates.input)} per million tokens in, ${usd(c.rates.output)} out" +
                (if (c.rates.cacheRead != c.rates.input) ", ${usd(c.rates.cacheRead)} for cached input" else "") +
                (c.long?.let { "; more for very long conversations" } ?: "") + (c.peak?.let { "; double at peak hours" } ?: "") +
                (c.then?.let { "; ${usd(it.rates.input)} in and ${usd(it.rates.output)} out after ${c.until}" } ?: "") +
                ". Prices ${c.line}."
        }
        is HelperPrice.Reported -> "${h.presetOf.company} reports what each answer cost; the cost line shows its figure."
        HelperPrice.Unknown -> "Robot doesn't know this model's prices: replies show “cost unknown”, they aren't counted in today's spend, and Robot " +
            "won't check with ${h.name} by itself. Enter the prices from ${if (h.home) "what you pay" else h.presetOf.company + "'s pricing page"}."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = muted)
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Row {
            TextButton(onClick = { editing = true }) { Text(if (h.yourPrices != null) "Edit my prices" else if (h.home) "Add a price" else "Enter prices") }
            if (h.yourPrices != null) TextButton(onClick = { set(h.copy(prices = null, pricesFor = null)) }) { Text("Use the published prices") }
        }
    } else {
        var input by remember { mutableStateOf(h.yourPrices?.input?.toString().orEmpty()) }
        var output by remember { mutableStateOf(h.yourPrices?.output?.toString().orEmpty()) }
        val i = input.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }
        val o = output.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text("$ in") }, isError = input.isNotEmpty() && i == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(value = output, onValueChange = { output = it }, singleLine = true, label = { Text("$ out") }, isError = output.isNotEmpty() && o == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Text("Dollars per million tokens, for ${h.model.ifEmpty { "this model" }}.", style = MaterialTheme.typography.bodySmall, color = muted)
        Row {
            TextButton(onClick = { editing = false }) { Text("Cancel") }
            TextButton(onClick = { set(h.copy(prices = Rates(i!!, o!!), pricesFor = h.model)); editing = false }, enabled = i != null && o != null) { Text("Save") }
        }
    }
    if (h.price !is HelperPrice.Free) {
        Text("Daily cap for ${h.name}", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = h.budgetCents == null, onClick = { set(h.copy(budgetCents = null)) }, label = { Text("None") }, modifier = Modifier.heightIn(min = 48.dp))
            for (c in RobotSettings.CLAUDE_BUDGETS.take(4)) {
                FilterChip(selected = h.budgetCents == c, onClick = { set(h.copy(budgetCents = c)) },
                    label = { Text(ClaudeCost.dollarsText(c * 10_000L).removeSuffix(".00")) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text("Today: ${ClaudeCost.dollarsText(f?.spend?.by?.get(h.id) ?: 0)}" + (h.budgetCents?.let { " of ${ClaudeCost.dollarsText(it * 10_000L)}" } ?: "") +
            " (an estimate). The budget for all helpers is in Settings › Helpers.", style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}
