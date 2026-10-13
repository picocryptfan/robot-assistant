package app.heyrobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.settings.escalationLabel
import app.heyrobot.ui.theme.RobotTokens
import java.util.Locale
import kotlin.random.Random

// MAIN PROCESS. Settings › Helpers (0.6.0): the other AIs Robot can ask when you say so (Claude, another company's model,
// or a model on your own computer), each with its own page, and "Add a helper". Keys never pass through this process:
// they are pasted in the helper's connection window, in the assistant's process (HelperWindow), which also runs Test
// connection; this screen only shows the masked key and what Test connection found (AssistantFacts). Built like Android's
// Settings (0.4.x redesign): a main switch per helper, radio dialogs for the choices, edit dialogs for the names, the long
// explanations (what goes to a helper, what trust means) in each page's footer, word for word.

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

/** A helper in a list: its symbol, name, company and model, and whether it can be used now. */
@Composable
private fun HelperRow(h: HelperConfig, f: AssistantFacts?, onClick: () -> Unit) {
    val (_, st) = status(h, f)
    PreferenceItem(h.name, helperLine(h) + " · " + st, if (h.home) RIcons.Home else RIcons.Cloud, onClick = onClick)
}

/** Settings › Helpers: your helpers, "Add a helper", when Robot may ask one by itself, and the budget for all of them. */
@Composable
fun HelpersScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = snap.facts
    val target = HelperSettings.target(s) { keySaved(f, it) }
    val help = target?.let { HelperSettings.escalation(s, it, keySaved(f, it)) } ?: s.help ?: Escalation.ASK
    val ready = target != null && HelperSettings.apiReady(target, keySaved(f, target))
    val spend = f?.spend
    PreferenceScreen("Helpers", onBack, snackbar = snackbar) {
        item(key = "intro") {
            IntroPreference("Other AIs Robot asks only when you say so (“ask a helper …”, “go get Gemini”) or when you let it check hard questions.")
        }
        preferenceGroup("Your helpers", "helpers") {
            if (s.helpers.isEmpty()) PreferenceItem("No helpers", "Robot answers everything itself")
            for (h in s.helpers) HelperRow(h, f) { a.openPage(Page.Helper(h.id)) }
            PreferenceItem("Add a helper", "A company's model, or one on your own computer", RIcons.Add, onClick = { a.openPage(Page.AddHelper) })
        }
        preferenceGroup("When Robot needs help", "help") {
            ListPreference("When Robot needs help", help, Escalation.entries, ::escalationLabel, { e ->
                e.detail + if (e == Escalation.AUTO && target != null && !ready)
                    " ${target.name} needs ${if (target.presetOf.keyRequired) "an API key" else "Test connection"} first: until then Robot asks first." else ""
            }, summary = escalationLabel(help) + (target?.let { " · ${it.name}" } ?: " · no helper to ask")) { update(s.copy(help = it)) }
            val on = s.helpers.filter { it.on }
            if (on.size > 1) {
                ListPreference("Ask", target?.id ?: on.first().id, on.map { it.id }, { id -> s.helper(id)?.name ?: id },
                    { id -> s.helper(id)?.let(::helperLine) }) { update(s.copy(helpDefault = it)) }
            }
            if (target != null && help == Escalation.AUTO && !target.price.known) {
                StatusPreference("Robot doesn't know ${target.name}'s prices", "So it asks first instead of checking by itself. Enter them on ${target.name}'s page.",
                    Mark.WARN, onClick = { a.openPage(Page.Helper(target.id)) })
            }
        }
        preferenceGroup("Cost", "cost") {
            ListPreference("Daily budget for all helpers", s.helperBudgetCents, RobotSettings.CLAUDE_BUDGETS,
                { c -> ClaudeCost.dollarsText(c * 10_000L).removeSuffix(".00") },
                summary = "Today: ${ClaudeCost.dollarsText(spend?.total ?: 0)} of ${ClaudeCost.dollarsText(s.helperBudgetCents * 10_000L)} (an estimate)") {
                update(s.copy(helperBudgetCents = it))
            }
            val by = s.helpers.mapNotNull { h -> spend?.by?.get(h.id)?.takeIf { it > 0 }?.let { "${h.name}: ${ClaudeCost.dollarsText(it)}" } }
            if (by.isNotEmpty()) PreferenceItem("Spent today", by.joinToString(" · "))
            SwitchPreference("Show cost", "“≈ 1¢” under each helper's reply", s.helperShowCost) { update(s.copy(helperShowCost = it)) }
        }
        preferenceFooter(
            "Robot stays offline unless you ask for a helper (“ask a helper …”, “go get Jarvis”) or let Robot check hard questions with one. " +
                "Only text leaves the phone: your voice is turned into text here, and a helper's answer is read by Robot's voices.\n\n" +
                (if (s.personalToHelpers) "By itself, Robot sends your question and the last two turns of the conversation, with any screen text, " +
                    "photo or what it read (Safety & privacy › Send personal content to helpers automatically is on)."
                else "By itself, Robot sends only your question and the last two turns of plain conversation. Once a conversation has screen text, " +
                    "a photo, or what Robot read from your contacts, calendar or notes, the weather (where you are) or your phone's status, it sends " +
                    "only your question (more for helpers you trust with personal content); anything more goes only after you tap a card that lists it.") +
                (if (target != null && !target.searching) "\n\nQuestions about news, prices or anything current go to ${target.name} by itself only " +
                    "when it can search the web; otherwise Robot answers and offers." else "") +
                "\n\nDaily budget: once it's reached, Robot asks before checking with a helper by itself until tomorrow. A helper whose " +
                "prices Robot doesn't know isn't counted. Show cost: “≈ 1¢” under each helper's reply, estimated from the published " +
                "prices (or the price the company reports); “cost unknown” when Robot can't tell.",
        )
    }
}

/** "Add a helper": who (a company, your own server, or another company's compatible API), then its page and the key window. */
@Composable
fun AddHelperScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onAdded: (String) -> Unit, onBack: () -> Unit) {
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

    PreferenceScreen("Add a helper", onBack, snackbar = snackbar) {
        preferenceGroup("Companies", "companies") {
            for (p in Presets.ALL.filter { !it.needsAddress }) {
                val detail = p.models.take(3).joinToString(", ") { it.title }.ifEmpty { "Hundreds of models from many companies, with one key" }
                PreferenceItem(p.title, detail, RIcons.Cloud, onClick = { add(p, null) })
            }
        }
        preferenceGroup("Your own AI", "home") {
            PreferenceItem(Presets.HOME.title, "A computer on your network, or reached through Tailscale", RIcons.Home, onClick = { asking = Presets.HOME })
        }
        preferenceGroup("Other", "other") {
            PreferenceItem(Presets.OTHER.title, "Any company with an OpenAI-compatible API (https)", RIcons.Cloud, onClick = { asking = Presets.OTHER })
        }
        preferenceFooter(
            "Who should Robot be able to ask? A company's model needs an API key from that company (paid separately from any app " +
                "subscription). A model on your own computer needs no key and costs nothing, and is private when the computer is yours. " +
                "For another company, you enter its OpenAI-compatible address (https).",
        )
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

/**
 * A helper's page: the main switch, its status and connection, name, model, what it may do, its voice, trust, cost, and
 * Remove. Off, the rest of the page is disabled until it is on again (Remove stays).
 */
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
    val on = h.on
    fun set(n: HelperConfig) = update(s.withHelper(n))
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var editAddress by rememberSaveable { mutableStateOf(false) }
    var editPrices by rememberSaveable { mutableStateOf(false) }
    var pickModel by rememberSaveable { mutableStateOf(false) }
    val effortStyle = h.catalogModel?.effort ?: p.quirks.effort
    PreferenceScreen(h.name, onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar("Use ${h.name}", on) { set(h.copy(enabled = it)) } }
        preferenceGroup(null, "status") {
            val (mark, st) = status(h, f)
            StatusPreference(if (h.home) "Your own server" else p.title, st, mark)
        }
        preferenceGroup("Connection", "connection") {
            if (p.needsAddress) {
                StatusPreference("Address", h.baseUrl ?: "None", if (facts.address(h) != null) Mark.OK else Mark.TODO,
                    action = { TextButton(onClick = { editAddress = true }, enabled = on) { Text("Change") } })
                if (h.home && LanRule.normalize(h.baseUrl.orEmpty())?.https == false) {
                    StatusPreference("Not encrypted", "Anyone on the same Wi-Fi can read what Robot sends to ${h.name}" +
                        (if (h.trust) ", including the personal content you trust it with." else ".") +
                        " Use https or Tailscale on a network you share.", Mark.WARN)
                }
            }
            val unreadable = f?.keysUnreadable?.contains(h.id) == true
            StatusPreference(f?.keys?.get(h.id) ?: if (p.keyRequired) "No key" else "No key (not needed unless your server asks)",
                when {
                    unreadable -> "Robot couldn't read the saved key (Android's keystore lost it), so it was removed. Add it again."
                    p.keyWhere != null -> "From ${p.keyWhere}. " + p.billing.orEmpty()
                    else -> p.billing
                },
                if (key) Mark.OK else if (unreadable) Mark.WARN else Mark.TODO,
                action = {
                    Row {
                        if (key) {
                            TextButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_KEY) }, enabled = on) { Text("Replace") }
                            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                        } else {
                            FilledTonalButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_KEY) }, enabled = on) { Text("Add key") }
                        }
                    }
                })
            if (p.protocol != Protocol.ANTHROPIC) {
                val checked = if (facts.checkedAt > 0) "Last checked: " + (if (facts.answered) "a test question was answered" else "the server answered") +
                    (if (facts.models.isNotEmpty()) ", ${facts.models.size} models listed" else "") + "." else "Robot hasn't reached ${h.name} yet."
                PreferenceItem("Test connection", checked, RIcons.Refresh, enabled = on, onClick = { a.helperWindow(h.id, HelperWindow.MODE_TEST) })
            }
            if (p.quirks.zdr) {
                SwitchPreference("Only providers that keep nothing", "Zero data retention", h.zdr, enabled = on) { set(h.copy(zdr = it)) }
            }
        }
        if (p.app != null) {
            preferenceGroup(null, "route") {
                val route = HelperSettings.route(h, key)
                ListPreference("Use", route, ClaudeRoute.entries, { it.title }, { r ->
                    r.detail + when {
                        r == ClaudeRoute.APP && f?.claudeApp?.installed == false -> " The ${p.defaultName} app isn't installed."
                        r == ClaudeRoute.API && !key -> " Add a key first."
                        else -> ""
                    }
                }, enabled = on) { set(h.copy(route = it)) }
            }
        }
        preferenceGroup("Name used by voice", "names") {
            val wake = wakeWords(s)
            TextPreference("Name", h.name, h.name, "Name", "Say “ask ${h.name} …” or “go get ${h.name}”.", enabled = on,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                validate = { v -> (HelperNames.check(v, h.id, s, wake) as? NameCheck.Problem)?.why }) { v ->
                if (HelperNames.check(v, h.id, s, wake) !is NameCheck.Problem && v.trim() != h.name) set(h.copy(name = HelperNames.clean(v)))
            }
            val shown = h.aliases.joinToString(", ")
            TextPreference("Also answers to", shown, "None", "Also answers to",
                "Other names you call it, separated by commas (at most ${HelperConfig.MAX_ALIASES}).", enabled = on,
                validate = { v -> aliasProblem(v, h, s, wake) }) { v ->
                if (aliasProblem(v, h, s, wake) == null) set(h.copy(aliases = aliasNames(v).filter { !it.equals(h.name, ignoreCase = true) }.take(HelperConfig.MAX_ALIASES)))
            }
        }
        preferenceGroup("Model", "model") {
            val shownModel = if (p.protocol == Protocol.ANTHROPIC) ClaudeModel.of(h.model).title
                else h.catalogModel?.title ?: h.model.ifEmpty { "None chosen" }
            PreferenceItem("Model", shownModel, RIcons.Chip, enabled = on, onClick = { pickModel = true })
            if (effortStyle != EffortStyle.NONE || p.protocol == Protocol.ANTHROPIC) {
                ListPreference("How hard ${h.name} thinks", h.effort, ClaudeEffortSetting.entries, { it.title }, { e ->
                    if (e == ClaudeEffortSetting.AUTO) "Low for spoken questions, medium for typed ones, high when you ask for it" else null
                }, enabled = on) { set(h.copy(effort = it)) }
            }
        }
        preferenceGroup("What ${h.name} may do", "may") {
            val canSearch = p.canSearch && (h.catalogModel?.search ?: (p.quirks.search == SearchStyle.OPENROUTER))
            SwitchPreference("Let ${h.name} search the web", if (canSearch) searchNote(h) else p.searchNote ?: "This model can't search the web through this connection.",
                h.search && canSearch, enabled = on && canSearch) { set(h.copy(search = it)) }
            val noTools = h.catalogModel?.tools == false || facts.noActions(h.model)
            SwitchPreference("Let ${h.name} ask Robot to do things", if (noTools) "This model didn't use Robot's actions when Robot checked, so it gets none."
                else "Timers, notes, messages and more, each with a card first", h.actions && !noTools, enabled = on && !noTools) {
                set(h.copy(actions = it))
            }
            if (p.protocol != Protocol.ANTHROPIC && h.actions) {
                PreferenceItem("Check actions", "Whether this model uses Robot's actions", enabled = on, onClick = { a.helperWindow(h.id, HelperWindow.MODE_ACTIONS) })
            }
            SwitchPreference("Allow on lock screen", "A locked phone can't show who is asking", h.locked, enabled = on) { set(h.copy(locked = it)) }
        }
        preferenceGroup("Voice and trust", "voice") {
            ListPreference("${h.name}'s voice", ClaudeVoices.of(h.voice), ClaudeVoices.KOKORO.map { it.first }, ClaudeVoices::title,
                { name -> s.helpers.firstOrNull { it.id != h.id && it.voice == name }?.let { "Also ${it.name}'s" } }, enabled = on) { set(h.copy(voice = it)) }
            SwitchPreference("Trusted with personal content",
                if (s.personalToHelpers) "Every helper gets it already (Safety & privacy)" else if (h.trust) "What Robot read goes without asking"
                else "Off: only your question goes by itself", h.trust, enabled = on) { set(h.copy(trust = it)) }
        }
        preferenceGroup("Cost", "cost") {
            PreferenceBlock { Text(priceText(h), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            PreferenceItem(if (h.yourPrices != null) "Edit your prices" else if (h.home) "Add a price" else "Enter prices",
                h.yourPrices?.let { "${usd(it.input)} in, ${usd(it.output)} out, per million tokens" } ?: "Dollars per million tokens", enabled = on,
                onClick = { editPrices = true })
            if (h.yourPrices != null) {
                PreferenceItem("Use the published prices", null, RIcons.Refresh, enabled = on, onClick = { set(h.copy(prices = null, pricesFor = null)) })
            }
            if (h.price !is HelperPrice.Free) {
                ListPreference<Int?>("Daily cap for ${h.name}", h.budgetCents, listOf<Int?>(null) + RobotSettings.CLAUDE_BUDGETS.take(4),
                    { c -> c?.let { ClaudeCost.dollarsText(it * 10_000L).removeSuffix(".00") } ?: "None" },
                    summary = "Today: ${ClaudeCost.dollarsText(f?.spend?.by?.get(h.id) ?: 0)}" +
                        (h.budgetCents?.let { " of ${ClaudeCost.dollarsText(it * 10_000L)}" } ?: " · no cap") + " (an estimate)",
                    enabled = on) { set(h.copy(budgetCents = it)) }
            }
        }
        preferenceGroup(null, "remove") {
            PreferenceItem("Remove ${h.name}", "Its key and what Robot found about it", RIcons.Delete, onClick = { confirmRemove = true })
        }
        preferenceFooter(helperFooter(h, s, effortStyle))
    }
    if (editAddress) {
        AddressDialog(p, h.baseUrl, onDismiss = { editAddress = false }) { address ->
            editAddress = false
            if (address != h.baseUrl) {
                // A new address may be another computer: trust starts over, a key saved for another host never goes to this
                // one (it is removed), and Test connection finds the server.
                val dropKey = key && !LanRule.sameHost(h.baseUrl, address)
                set(h.copy(baseUrl = address, trust = false))
                when {
                    h.trust && dropKey -> a.say("“Trusted with personal content” was turned off and the key was removed: the address changed. Add the key again if the server needs one.")
                    h.trust -> a.say("“Trusted with personal content” was turned off: the address changed.")
                    dropKey -> a.say("The key was removed: Robot sends a key only to the host it was saved for. Add it again if the server needs one.")
                }
                a.helperMoved(h.id, dropKey)
            }
        }
    }
    if (pickModel) ModelDialog(h, facts, a, onDismiss = { pickModel = false }) { m -> pickModel = false; if (m != h.model) set(h.copy(model = m)) }
    if (editPrices) PricesDialog(h, onDismiss = { editPrices = false }) { r -> editPrices = false; set(h.copy(prices = r, pricesFor = h.model)) }
    if (confirmDelete) {
        ConfirmDialog("Delete the ${h.name} key?", "Robot removes the key from this phone." +
            if (p.app != null) " ${h.name} uses the ${p.defaultName} app until you add a key again." else "", "Delete",
            onDismiss = { confirmDelete = false }) { confirmDelete = false; a.deleteHelperKey(h.id) }
    }
    if (confirmRemove) {
        ConfirmDialog("Remove ${h.name}?", "Robot deletes its key and what it found about it. Conversations in History keep its answers.", "Remove",
            onDismiss = { confirmRemove = false }) { confirmRemove = false; a.removeHelper(h.id) }
    }
}

/** A helper page's footer: the data use, the name, the effort styles, the voice and what trust means, word for word as before. */
private fun helperFooter(h: HelperConfig, s: RobotSettings, effortStyle: EffortStyle): String {
    val p = h.presetOf
    val out = ArrayList<String>()
    p.dataUse?.let(out::add)
    if (p.quirks.zdr) out += "Only providers that keep nothing (zero data retention): OpenRouter sends your questions only to providers that don't " +
        "store them. Some models have none; then ${h.name} says so."
    if (p.misheard.isNotEmpty() && h.name == p.defaultName) {
        out += "Robot also hears “${p.misheard.take(3).joinToString("”, “")}” as ${h.name}, but only after “ask” or “go get”."
    }
    out += "How hard ${h.name} thinks: Automatic is low for spoken questions (fast), medium for typed ones, high when you ask ${h.name} to " +
        "“think hard” or “take your time”." + when (effortStyle) {
            EffortStyle.MISTRAL -> " Mistral thinks either fully or not at all: Low means no thinking."
            EffortStyle.DEEPSEEK -> " For DeepSeek, High means its maximum."
            else -> ""
        }
    out += "${h.name}'s voice is a Kokoro voice, so you hear who is talking. With the Kitten voice helpers use Kitten's other voice; with " +
        "the phone's voice, a lower pitch."
    out += "Trusted with personal content: off (the default), when Robot checks with ${h.name} by itself in a conversation that holds " +
        "something personal (what Robot read from your calendar, notes or contacts, where you are, your phone's status), only your question " +
        "goes; “go get ${h.name}” asks before including the conversation; a spoken yes never sends personal content. On: all of that goes " +
        "to ${h.name} without asking. Your screen and photos still need a tap every time, and so does a conversation that had them. " +
        "Turn it on only for a helper you'd show your calendar to: a server that is yours, or a company whose terms you accept." +
        if (s.personalToHelpers) " Safety & privacy › Send personal content to helpers automatically is on, so every helper gets this already." else ""
    out += "The budget for all helpers is in Settings › Helpers."
    return out.joinToString("\n\n")
}

/** Your aliases, cleaned and without repeats. */
private fun aliasNames(v: String): List<String> = v.split(',').map(HelperNames::clean).filter { it.isNotEmpty() }.distinct()

/** Why one of the names typed can't be used, or null. */
private fun aliasProblem(v: String, h: HelperConfig, s: RobotSettings, wake: Set<String>): String? =
    aliasNames(v).firstNotNullOfOrNull { n -> (HelperNames.check(n, h.id, s, wake) as? NameCheck.Problem)?.let { "$n: ${it.why}" } }

/** Where the price comes from (and when it was read). */
private fun priceText(h: HelperConfig): String = when (val pr = h.price) {
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

/** Your prices for the model in use: dollars per million tokens in and out; Save only with both. */
@Composable
private fun PricesDialog(h: HelperConfig, onDismiss: () -> Unit, onSave: (Rates) -> Unit) {
    var input by rememberSaveable { mutableStateOf(h.yourPrices?.input?.toString().orEmpty()) }
    var output by rememberSaveable { mutableStateOf(h.yourPrices?.output?.toString().orEmpty()) }
    val i = input.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }
    val o = output.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (h.home) "Price of ${h.name}" else "Prices for ${h.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text("$ in") }, isError = input.isNotEmpty() && i == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = output, onValueChange = { output = it }, singleLine = true, label = { Text("$ out") }, isError = output.isNotEmpty() && o == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Text("Dollars per million tokens, for ${h.model.ifEmpty { "this model" }}.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Rates(i!!, o!!)) }, enabled = i != null && o != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The model: the catalog's (recommended first, with what each can do), the server's list (searchable when long, 40 shown at
 * most), or any id you type. A pick closes the dialog.
 */
@Composable
private fun ModelDialog(h: HelperConfig, facts: HelperFacts, a: AppActions, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val p = h.presetOf
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun badges(tools: Boolean, search: Boolean, vision: Boolean) = listOfNotNull(
        "can use Robot's actions".takeIf { tools }, "can search the web".takeIf { search }, "can see pictures".takeIf { vision },
    ).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " (${it.replaceFirstChar { c -> c.uppercase() }}.)" }.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf(h.model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.protocol == Protocol.ANTHROPIC) {
                    for (m in ClaudeModel.entries) ModelChoice(m.title, m.detail, ClaudeModel.of(h.model) == m) { onPick(m.id) }
                    return@Column
                }
                for (m in p.models) ModelChoice(m.title, m.detail + badges(m.tools, m.search, m.vision), h.model == m.id) { onPick(m.id) }
                val listed = (if (facts.forAddress == null || facts.forAddress == h.baseUrl) facts.models else emptyList()).filter { id -> p.models.none { it.id == id } }
                if (listed.size > 8) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search ${listed.size} models") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                }
                val matching = listed.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                for (m in matching.take(40)) ModelChoice(m, null, h.model == m) { onPick(m) }
                if (matching.size > 40) Text("More models match: narrow the search.", style = MaterialTheme.typography.bodyMedium, color = muted)
                if (h.model.isNotEmpty() && p.models.none { it.id == h.model } && h.model !in listed) ModelChoice(h.model, "Entered by you", true) { onPick(h.model) }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it.trim().take(HelperConfig.MAX_MODEL) }, singleLine = true, label = { Text("Model id") },
                    supportingText = { Text("Any model ${if (h.home) "your server has" else p.company + " offers"}, exactly as it's named.") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (typed.isNotEmpty()) onPick(typed) }),
                    trailingIcon = { if (typed != h.model && typed.isNotEmpty()) TextButton(onClick = { onPick(typed) }) { Text("Use") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (listed.isEmpty() && p.models.isEmpty()) {
                    Text("Test connection lists the models ${if (h.home) "your server has" else "you can use"}.", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = if (p.protocol == Protocol.ANTHROPIC) null else ({ TextButton(onClick = { onDismiss(); a.helperWindow(h.id, HelperWindow.MODE_TEST) }) { Text("Refresh list") } }),
    )
}

/** One model in [ModelDialog]: a 56 dp radio row. */
@Composable
private fun ModelChoice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RobotTokens.choiceRowHeight).clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
