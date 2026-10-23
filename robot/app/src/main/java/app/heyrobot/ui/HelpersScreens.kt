// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.HelperEffortSetting
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.AskRoute
import app.heyrobot.core.CostText
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.HelperName
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.helpers.EffortStyle
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFacts
import app.heyrobot.core.helpers.HelperFormat
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
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.core.helpers.Protocol
import app.heyrobot.core.helpers.Rates
import app.heyrobot.core.helpers.SearchStyle
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Medium
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
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
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.RobotTokens
import app.heyrobot.ui.theme.str
import kotlin.random.Random

// MAIN PROCESS. Settings › Helpers (0.6.0): the other AIs Robot can ask when you say so (Claude, another company's model,
// or a model on your own computer), each with its own page, and "Add a helper". Keys never pass through this process:
// they are pasted in the helper's connection window, in the assistant's process (HelperWindow), which also runs Test
// connection; this screen only shows the masked key and what Test connection found (AssistantFacts). Built like Android's
// Settings (0.4.x redesign): a main switch per helper, radio dialogs for the choices, edit dialogs for the names, the long
// explanations (what goes to a helper, what trust means) in each page's footer, word for word. 1.1: the words are in
// strings_helpers.xml, and core's (the presets, the models, the name rules, the address hints) in core HelperStrings,
// both in the menus' language; English is 1.0's, word for word.

private fun keySaved(f: AssistantFacts?, h: HelperConfig) = f?.keys?.containsKey(h.id) == true

private fun factsOf(f: AssistantFacts?, h: HelperConfig): HelperFacts = f?.helperFacts?.get(h.id) ?: HelperFacts()

/** The words of your wake phrases: a helper can't be called one of them. */
private fun wakeWords(s: RobotSettings): Set<String> = s.wakePhrases.flatMap { HelperName.words(it.text) }.toSet()

/**
 * The screens' words outside composition (a list's labels, a footer, a check in a click): the resources of [ctx], which
 * follow the menus' language, and core's phrases rendered in [langs] (the screen's: LocalLangs).
 */
private class Words(private val ctx: Context, val langs: Langs) {
    val lang: Lang get() = langs.ui
    fun s(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
    fun n(@PluralsRes id: Int, count: Int): String = ctx.resources.getQuantityString(id, count, count)
    fun p(p: Phrase): String = p.render(langs)
    fun label(l: UiLabel): String = l.render(langs.ui, Medium.SCREEN, langs.pseudo)
}

@Composable
private fun rememberWords(): Words {
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    return remember(ctx, langs) { Words(ctx, langs) }
}

/** "Safety & privacy › Send personal content to helpers automatically". */
private fun sendPersonal(w: Words): String = w.label(UiLabel(UiStrings.safety, UiStrings.sendPersonalContent))

/** Whether [h] can be used now, and how it is: for its line in Settings and its page. */
private fun status(h: HelperConfig, f: AssistantFacts?, w: Words): Pair<Mark, String> {
    val p = h.presetOf
    val key = keySaved(f, h)
    val facts = factsOf(f, h)
    return when {
        !h.on -> Mark.TODO to w.s(R.string.helper_status_off)
        f?.keysUnreadable?.contains(h.id) == true -> Mark.WARN to w.s(R.string.helper_status_key_removed)
        p.app != null && HelperSettings.route(h, key) == AskRoute.APP ->
            if (f?.app(p.app)?.installed == false) Mark.TODO to w.s(R.string.helper_status_no_key_no_app, p.defaultName)
            else Mark.OK to w.s(R.string.helper_status_opens_app, p.defaultName)
        p.keyRequired && !key -> Mark.TODO to w.s(R.string.helper_no_key)
        p.protocol == Protocol.ANTHROPIC -> Mark.OK to w.s(R.string.helper_status_ready)
        facts.address(h) == null -> Mark.TODO to w.s(R.string.helper_status_find_server)
        facts.checkedAt == 0L -> Mark.TODO to w.s(R.string.helper_status_not_tested)
        facts.forAddress != null && facts.forAddress != h.baseUrl -> Mark.TODO to w.s(R.string.helper_status_address_changed)
        facts.failure != null -> Mark.WARN to w.p(facts.failure!!.sayText(HelperWho.of(h)))
        facts.answered -> Mark.OK to if (facts.ms > 0) w.s(R.string.helper_status_ready_in, HelperFormat.oneDecimal(facts.ms / 1000.0, w.lang))
            else w.s(R.string.helper_status_ready)
        else -> Mark.OK to w.s(R.string.helper_status_connected)
    }
}

/** "Anthropic · Claude Opus 5.5" or "Your server · qwen3:32b". */
private fun helperLine(h: HelperConfig, w: Words): String {
    val model = h.catalogModel?.title ?: h.model.ifEmpty { w.s(R.string.helper_line_no_model) }
    return listOfNotNull(if (h.home) w.s(R.string.helper_line_your_server) else w.p(h.presetOf.companyText), model,
        w.s(R.string.helper_line_trusted).takeIf { h.trust }).joinToString(" · ")
}

/** A helper in a list: its symbol, name, company and model, and whether it can be used now. */
@Composable
private fun HelperRow(h: HelperConfig, f: AssistantFacts?, w: Words, onClick: () -> Unit) {
    val (_, st) = status(h, f, w)
    PreferenceItem(h.name, listOf(helperLine(h, w), st).joinToString(" · "), if (h.home) RIcons.Home else RIcons.Cloud, onClick = onClick)
}

/** Settings › Helpers: your helpers, "Add a helper", when Robot may ask one by itself, and the budget for all of them. */
@Composable
fun HelpersScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val w = rememberWords()
    val lang = w.lang
    val f = snap.facts
    val target = HelperSettings.target(s) { keySaved(f, it) }
    val help = target?.let { HelperSettings.escalation(s, it, keySaved(f, it)) } ?: s.help ?: Escalation.ASK
    val ready = target != null && HelperSettings.apiReady(target, keySaved(f, target))
    val spend = f?.spend
    val whenTitle = stringResource(R.string.helper_group_when)
    PreferenceScreen(UiStrings.helpers().str(), onBack, snackbar = snackbar) {
        item(key = "intro") {
            IntroPreference(stringResource(R.string.helper_intro))
        }
        preferenceGroup(w.s(R.string.helper_group_yours), "helpers") {
            if (s.helpers.isEmpty()) PreferenceItem(stringResource(R.string.helper_none), stringResource(R.string.helper_none_summary))
            for (h in s.helpers) HelperRow(h, f, w) { a.openPage(Page.Helper(h.id)) }
            PreferenceItem(UiStrings.addAHelper().str(), stringResource(R.string.helper_add_summary), RIcons.Add, onClick = { a.openPage(Page.AddHelper) })
        }
        preferenceGroup(whenTitle, "help") {
            ListPreference(whenTitle, help, Escalation.entries, { w.p(it.labelText) }, { e ->
                listOfNotNull(w.p(e.detailText), if (e == Escalation.AUTO && target != null && !ready)
                    w.s(if (target.presetOf.keyRequired) R.string.helper_auto_needs_key else R.string.helper_auto_needs_test, target.name) else null).joinToString(" ")
            }, summary = listOf(w.p(help.labelText), target?.name ?: w.s(R.string.helper_no_target)).joinToString(" · ")) { update(s.copy(help = it)) }
            val on = s.helpers.filter { it.on }
            if (on.size > 1) {
                ListPreference(stringResource(R.string.helper_ask), target?.id ?: on.first().id, on.map { it.id }, { id -> s.helper(id)?.name ?: id },
                    { id -> s.helper(id)?.let { helperLine(it, w) } }) { update(s.copy(helpDefault = it)) }
            }
            if (target != null && help == Escalation.AUTO && !target.price.known) {
                StatusPreference(stringResource(R.string.helper_prices_unknown_title, target.name), stringResource(R.string.helper_prices_unknown_summary, target.name),
                    Mark.WARN, onClick = { a.openPage(Page.Helper(target.id)) })
            }
        }
        preferenceGroup(w.s(R.string.helper_group_cost), "cost") {
            ListPreference(stringResource(R.string.helper_daily_budget), s.helperBudgetCents, RobotSettings.HELPER_BUDGETS,
                { c -> HelperFormat.budget(c * 10_000L, lang) },
                summary = stringResource(R.string.helper_today_of, HelperFormat.dollars(spend?.total ?: 0, lang), HelperFormat.dollars(s.helperBudgetCents * 10_000L, lang))) {
                update(s.copy(helperBudgetCents = it))
            }
            val by = s.helpers.mapNotNull { h -> spend?.by?.get(h.id)?.takeIf { it > 0 }?.let { w.s(R.string.helper_spent_by, h.name, HelperFormat.dollars(it, lang)) } }
            if (by.isNotEmpty()) PreferenceItem(stringResource(R.string.helper_spent_today), by.joinToString(" · "))
            SwitchPreference(stringResource(R.string.helper_show_cost), stringResource(R.string.helper_show_cost_summary), s.helperShowCost) { update(s.copy(helperShowCost = it)) }
        }
        preferenceFooter(
            listOfNotNull(
                w.s(R.string.helper_footer_offline),
                if (s.personalToHelpers) w.s(R.string.helper_footer_sends_all, sendPersonal(w)) else w.s(R.string.helper_footer_sends_question),
                if (target != null && !target.searching) w.s(R.string.helper_footer_current, target.name) else null,
                w.s(R.string.helper_footer_budget),
            ).joinToString("\n\n"),
        )
    }
}

/** "Add a helper": who (a company, your own server, or another company's compatible API), then its page and the key window. */
@Composable
fun AddHelperScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onAdded: (String) -> Unit, onBack: () -> Unit) {
    val w = rememberWords()
    var asking by remember { mutableStateOf<Preset?>(null) }
    var trustFor by remember { mutableStateOf<String?>(null) }

    fun add(p: Preset, base: String?, trust: Boolean = false) {
        val taken = s.helpers.map { it.id }.toSet()
        // Claude keeps its id (its key file, the Claude app, the settings mirror) when you add it back.
        val id = if (p.kind == HelperKind.ANTHROPIC && HelperConfig.CLAUDE_ID !in taken) HelperConfig.CLAUDE_ID else HelperConfig.newId(taken) { Random.nextInt(it) }
        val wake = wakeWords(s)
        val name = HelperNames.forNew(p, id, s, wake, base)
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

    val many = stringResource(R.string.helper_openrouter_detail)
    PreferenceScreen(UiStrings.addAHelper().str(), onBack, snackbar = snackbar) {
        preferenceGroup(w.s(R.string.helper_group_companies), "companies") {
            for (p in Presets.ALL.filter { !it.needsAddress }) {
                val detail = p.models.take(3).joinToString(", ") { it.title }.ifEmpty { many } +
                    (if (p.app != null) " · " + w.s(R.string.helper_or_app_no_key, p.defaultName) else "")
                PreferenceItem(p.titleText.str(), detail, RIcons.Cloud, onClick = { add(p, null) })
            }
        }
        preferenceGroup(w.s(R.string.helper_group_own), "home") {
            PreferenceItem(Presets.HOME.titleText.str(), stringResource(R.string.helper_home_summary), RIcons.Home, onClick = { asking = Presets.HOME })
        }
        preferenceGroup(w.s(R.string.helper_group_other), "other") {
            PreferenceItem(Presets.OTHER.titleText.str(), stringResource(R.string.helper_other_summary), RIcons.Cloud, onClick = { asking = Presets.OTHER })
        }
        preferenceFooter(w.s(R.string.helper_add_footer))
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
            title = { Text(stringResource(R.string.helper_mine_title)) },
            text = { Text(stringResource(R.string.helper_mine_text, Presets.HOME.defaultName)) },
            confirmButton = { TextButton(onClick = { trustFor = null; add(Presets.HOME, address, trust = true) }) { Text(stringResource(R.string.helper_mine_yes)) } },
            dismissButton = { TextButton(onClick = { trustFor = null; add(Presets.HOME, address) }) { Text(stringResource(R.string.helper_not_now)) } },
        )
    }
}

/** Why Robot won't use [typed] as [p]'s address, or null when it may. */
private fun addressProblem(p: Preset, typed: String, w: Words): String? {
    val t = LanRule.normalize(typed) ?: return w.s(R.string.helper_address_bad)
    val lit = LanRule.literal(t.host)
    return when {
        !p.home && !t.https -> w.s(R.string.helper_address_https_only)
        p.home && !t.https && lit != null && !LanRule.classify(lit).cleartext -> w.p(HelperStrings.notLocal())
        lit != null && LanRule.classify(lit) == LanRule.Range.BLOCKED -> w.s(R.string.helper_address_blocked)
        else -> null
    }
}

/** The address of your server, or of another company's compatible API ([current]: the one set now). */
@Composable
private fun AddressDialog(p: Preset, current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val w = rememberWords()
    var v by remember { mutableStateOf(current.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (p.home) R.string.helper_address_title_home else R.string.helper_address_title_api)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The examples are addresses, the same in every language.
                Text(if (p.home) stringResource(R.string.helper_address_home_hint, "192.168.1.20", "desktop.lan", "https://box.tail1234.ts.net")
                    else stringResource(R.string.helper_address_api_hint, "https://api.example.com/v1"), style = MaterialTheme.typography.bodyMedium, color = muted)
                OutlinedTextField(
                    value = v, onValueChange = { v = it.trim(); problem = null }, singleLine = true, label = { Text(stringResource(R.string.helper_address)) },
                    isError = problem != null, supportingText = { problem?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { problem = addressProblem(p, v, w); if (problem == null) onSave(v) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val t = LanRule.normalize(v)
                if (p.home && t != null && !t.https) {
                    Text(stringResource(R.string.helper_address_cleartext), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                t?.hintText?.let { Text(it.str(), style = MaterialTheme.typography.bodySmall, color = muted) }
                t?.host?.let(LanRule::literal)?.let { LanRule.classify(it).warningText }?.let { Text(it.str(), style = MaterialTheme.typography.bodySmall, color = muted) }
            }
        },
        confirmButton = { TextButton(onClick = { problem = addressProblem(p, v, w); if (problem == null) onSave(v) }) { Text(UiStrings.save().str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
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
    val w = rememberWords()
    val lang = w.lang
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
    val modelTitle = stringResource(R.string.helper_model)
    PreferenceScreen(h.name, onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar(stringResource(R.string.helper_use_name, h.name), on) { set(h.copy(enabled = it)) } }
        preferenceGroup(null, "status") {
            val (mark, st) = status(h, f, w)
            StatusPreference(if (h.home) Presets.HOME.companyText.str() else p.titleText.str(), st, mark)
        }
        preferenceGroup(w.s(R.string.helper_group_connection), "connection") {
            if (p.needsAddress) {
                StatusPreference(stringResource(R.string.helper_address), h.baseUrl ?: stringResource(R.string.helper_address_none), if (facts.address(h) != null) Mark.OK else Mark.TODO,
                    action = { TextButton(onClick = { editAddress = true }, enabled = on) { Text(stringResource(R.string.helper_change)) } })
                if (h.home && LanRule.normalize(h.baseUrl.orEmpty())?.https == false) {
                    StatusPreference(stringResource(R.string.helper_not_encrypted),
                        stringResource(if (h.trust) R.string.helper_not_encrypted_summary_trusted else R.string.helper_not_encrypted_summary, h.name), Mark.WARN)
                }
            }
            val unreadable = f?.keysUnreadable?.contains(h.id) == true
            StatusPreference(f?.keys?.get(h.id) ?: stringResource(if (p.keyRequired) R.string.helper_no_key else R.string.helper_no_key_optional),
                when {
                    unreadable -> stringResource(R.string.helper_key_unreadable)
                    // 1.0's words: "From …. " and the billing, if any.
                    p.keyWhere != null -> listOf(stringResource(R.string.helper_key_from, p.keyWhere!!), p.billingText?.str().orEmpty()).joinToString(" ")
                    else -> p.billingText?.str()
                },
                if (key) Mark.OK else if (unreadable) Mark.WARN else Mark.TODO,
                action = {
                    Row {
                        if (key) {
                            TextButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_KEY) }, enabled = on) { Text(stringResource(R.string.helper_replace)) }
                            TextButton(onClick = { confirmDelete = true }) { Text(UiStrings.delete().str()) }
                        } else {
                            FilledTonalButton(onClick = { a.helperWindow(h.id, HelperWindow.MODE_KEY) }, enabled = on) { Text(stringResource(R.string.helper_add_key)) }
                        }
                    }
                })
            if (p.protocol != Protocol.ANTHROPIC) {
                val n = facts.models.size
                // 1.0.2: a check that failed says so (before, any check without an answer read "the server answered"), and a
                // key refused over http says nothing was asked; the failure in the words the helper's status line uses.
                val checked = if (facts.checkedAt > 0) when {
                    facts.failure == HelperFailure.KEY_OVER_HTTP -> stringResource(R.string.helper_checked_key_not_sent)
                    facts.failure != null -> stringResource(R.string.helper_checked_failed, w.p(facts.failure!!.sayText(HelperWho.of(h))))
                    facts.answered && n > 0 -> pluralStringResource(R.plurals.helper_checked_answered_models, n, n)
                    facts.answered -> stringResource(R.string.helper_checked_answered)
                    n > 0 -> pluralStringResource(R.plurals.helper_checked_server_models, n, n)
                    else -> stringResource(R.string.helper_checked_server)
                } else stringResource(R.string.helper_not_reached, h.name)
                PreferenceItem(UiStrings.testConnection().str(), checked, RIcons.Refresh, enabled = on, onClick = { a.helperWindow(h.id, HelperWindow.MODE_TEST) })
            }
            if (p.quirks.zdr) {
                SwitchPreference(stringResource(R.string.helper_zdr), stringResource(R.string.helper_zdr_summary), h.zdr, enabled = on) { set(h.copy(zdr = it)) }
            }
        }
        if (p.app != null) {
            preferenceGroup(null, "route") {
                val route = HelperSettings.route(h, key)
                val appMissing = stringResource(R.string.helper_app_not_installed, p.defaultName)
                val keyFirst = stringResource(R.string.helper_add_key_first)
                ListPreference(stringResource(R.string.helper_use), route, AskRoute.entries, { w.p(HelperSettings.routeTitleText(p, it)) }, { r ->
                    listOfNotNull(w.p(HelperSettings.routeDetailText(p, r)), when {
                        r == AskRoute.APP && f?.app(p.app)?.installed == false -> appMissing
                        r == AskRoute.API && !key -> keyFirst
                        else -> null
                    }).joinToString(" ")
                }, enabled = on) { set(h.copy(route = it)) }
            }
        }
        preferenceGroup(w.s(R.string.helper_group_names), "names") {
            val wake = wakeWords(s)
            val nameTitle = stringResource(R.string.helper_name)
            TextPreference(nameTitle, h.name, h.name, nameTitle, stringResource(R.string.helper_name_hint, h.name), enabled = on,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                validate = { v -> (HelperNames.check(v, h.id, s, wake) as? NameCheck.Problem)?.let { w.p(it.text) } }) { v ->
                if (HelperNames.check(v, h.id, s, wake) !is NameCheck.Problem && v.trim() != h.name) set(h.copy(name = HelperNames.clean(v)))
            }
            val shown = h.aliases.joinToString(", ")
            val aliasTitle = stringResource(R.string.helper_aliases)
            TextPreference(aliasTitle, shown, stringResource(R.string.helper_aliases_none), aliasTitle,
                stringResource(R.string.helper_aliases_hint, HelperConfig.MAX_ALIASES), enabled = on,
                validate = { v -> aliasProblem(v, h, s, wake, w) }) { v ->
                if (aliasProblem(v, h, s, wake, w) == null) set(h.copy(aliases = aliasNames(v).filter { !it.equals(h.name, ignoreCase = true) }.take(HelperConfig.MAX_ALIASES)))
            }
        }
        preferenceGroup(modelTitle, "model") {
            val shownModel = if (p.protocol == Protocol.ANTHROPIC) AnthropicModel.of(h.model).title
                else h.catalogModel?.title ?: h.model.ifEmpty { w.s(R.string.helper_model_none) }
            PreferenceItem(modelTitle, shownModel, RIcons.Chip, enabled = on, onClick = { pickModel = true })
            if (effortStyle != EffortStyle.NONE || p.protocol == Protocol.ANTHROPIC) {
                val auto = stringResource(R.string.helper_effort_auto)
                ListPreference(stringResource(R.string.helper_effort, h.name), h.effort, HelperEffortSetting.entries, { w.p(it.titleText) }, { e ->
                    if (e == HelperEffortSetting.AUTO) auto else null
                }, enabled = on) { set(h.copy(effort = it)) }
            }
        }
        preferenceGroup(w.s(R.string.helper_group_may, h.name), "may") {
            val canSearch = p.canSearch && (h.catalogModel?.search ?: (p.quirks.search == SearchStyle.OPENROUTER))
            SwitchPreference(stringResource(R.string.helper_search, h.name),
                if (canSearch) searchNote(h, w) else p.searchNoteText?.str() ?: stringResource(R.string.helper_search_cant),
                h.search && canSearch, enabled = on && canSearch) { set(h.copy(search = it)) }
            val noTools = h.catalogModel?.tools == false || facts.noActions(h.model)
            SwitchPreference(stringResource(R.string.helper_actions, h.name), stringResource(if (noTools) R.string.helper_actions_none else R.string.helper_actions_summary),
                h.actions && !noTools, enabled = on && !noTools) {
                set(h.copy(actions = it))
            }
            if (p.protocol != Protocol.ANTHROPIC && h.actions) {
                PreferenceItem(stringResource(R.string.helper_check_actions), stringResource(R.string.helper_check_actions_summary), enabled = on,
                    onClick = { a.helperWindow(h.id, HelperWindow.MODE_ACTIONS) })
            }
            SwitchPreference(stringResource(R.string.helper_locked), stringResource(R.string.helper_locked_summary), h.locked, enabled = on) { set(h.copy(locked = it)) }
        }
        preferenceGroup(w.s(R.string.helper_group_voice), "voice") {
            ListPreference(stringResource(R.string.helper_voice, h.name), HelperVoices.of(h.voice), HelperVoices.KOKORO.map { it.first }, { w.p(HelperVoices.titleText(it)) },
                { name -> s.helpers.firstOrNull { it.id != h.id && it.voice == name }?.let { w.s(R.string.helper_voice_also, it.name) } }, enabled = on) { set(h.copy(voice = it)) }
            SwitchPreference(stringResource(R.string.helper_trust),
                if (s.personalToHelpers) stringResource(R.string.helper_trust_all, w.label(UiLabel(UiStrings.safety)))
                else stringResource(if (h.trust) R.string.helper_trust_on else R.string.helper_trust_off), h.trust, enabled = on) { set(h.copy(trust = it)) }
        }
        preferenceGroup(w.s(R.string.helper_group_cost), "cost") {
            PreferenceBlock { Text(priceText(h, w), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            PreferenceItem(stringResource(if (h.yourPrices != null) R.string.helper_prices_edit else if (h.home) R.string.helper_price_add else R.string.helper_prices_enter),
                h.yourPrices?.let { w.s(R.string.helper_prices_yours_summary, HelperFormat.perMillion(it.input, lang), HelperFormat.perMillion(it.output, lang)) }
                    ?: stringResource(R.string.helper_prices_unit), enabled = on,
                onClick = { editPrices = true })
            if (h.yourPrices != null) {
                PreferenceItem(stringResource(R.string.helper_prices_published), null, RIcons.Refresh, enabled = on, onClick = { set(h.copy(prices = null, pricesFor = null)) })
            }
            if (h.price !is HelperPrice.Free) {
                val none = stringResource(R.string.helper_cap_none)
                val today = HelperFormat.dollars(f?.spend?.by?.get(h.id) ?: 0, lang)
                ListPreference<Int?>(stringResource(R.string.helper_cap, h.name), h.budgetCents, listOf<Int?>(null) + RobotSettings.HELPER_BUDGETS.take(4),
                    { c -> c?.let { HelperFormat.budget(it * 10_000L, lang) } ?: none },
                    summary = h.budgetCents?.let { stringResource(R.string.helper_today_of, today, HelperFormat.dollars(it * 10_000L, lang)) }
                        ?: stringResource(R.string.helper_cap_today_no_cap, today),
                    enabled = on) { set(h.copy(budgetCents = it)) }
            }
        }
        preferenceGroup(null, "remove") {
            PreferenceItem(stringResource(R.string.helper_remove, h.name), stringResource(R.string.helper_remove_summary), RIcons.Delete, onClick = { confirmRemove = true })
        }
        preferenceFooter(helperFooter(h, s, effortStyle, w))
    }
    if (editAddress) {
        AddressDialog(p, h.baseUrl, onDismiss = { editAddress = false }) { address ->
            editAddress = false
            if (address != h.baseUrl) {
                // A new address may be another computer: trust starts over, a key saved for another host never goes to this
                // one (it is removed), and Test connection finds the server.
                val dropKey = key && !LanRule.sameHost(h.baseUrl, address)
                set(h.copy(baseUrl = address, trust = false))
                val trust = w.s(R.string.helper_trust)
                when {
                    h.trust && dropKey -> a.say(w.s(R.string.helper_moved_trust_key, trust))
                    h.trust -> a.say(w.s(R.string.helper_moved_trust, trust))
                    dropKey -> a.say(w.s(R.string.helper_moved_key))
                }
                a.helperMoved(h.id, dropKey)
            }
        }
    }
    if (pickModel) ModelDialog(h, facts, a, onDismiss = { pickModel = false }) { m -> pickModel = false; if (m != h.model) set(h.copy(model = m)) }
    if (editPrices) PricesDialog(h, onDismiss = { editPrices = false }) { r -> editPrices = false; set(h.copy(prices = r, pricesFor = h.model)) }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.helper_delete_key_title, h.name),
            if (p.app != null) stringResource(R.string.helper_delete_key_text_app, h.name, p.defaultName) else stringResource(R.string.helper_delete_key_text),
            UiStrings.delete().str(), onDismiss = { confirmDelete = false }) { confirmDelete = false; a.deleteHelperKey(h.id) }
    }
    if (confirmRemove) {
        ConfirmDialog(stringResource(R.string.helper_remove_title, h.name), stringResource(R.string.helper_remove_text), stringResource(R.string.helper_remove_confirm),
            onDismiss = { confirmRemove = false }) { confirmRemove = false; a.removeHelper(h.id) }
    }
}

/** A helper page's footer: the data use, the name, the effort styles, the voice and what trust means, word for word as before. */
private fun helperFooter(h: HelperConfig, s: RobotSettings, effortStyle: EffortStyle, w: Words): String {
    val p = h.presetOf
    val out = ArrayList<String>()
    p.dataUseText?.let { out += w.p(it) }
    if (p.quirks.zdr) out += w.s(R.string.helper_footer_zdr, h.name)
    if (p.misheard.isNotEmpty() && h.name == p.defaultName) {
        // The words Robot hears, each in the language's quotes: “cloud”, “clod” / « cloud », « clod ».
        val between = if (w.lang == Lang.FR) " », « " else "”, “"
        out += w.s(R.string.helper_footer_misheard, p.misheard.take(3).joinToString(between), h.name)
    }
    out += listOfNotNull(w.s(R.string.helper_footer_effort, h.name), when (effortStyle) {
        EffortStyle.MISTRAL -> w.s(R.string.helper_footer_effort_mistral)
        EffortStyle.DEEPSEEK -> w.s(R.string.helper_footer_effort_deepseek)
        else -> null
    }).joinToString(" ")
    out += w.s(R.string.helper_footer_voice, h.name)
    out += listOfNotNull(w.s(R.string.helper_footer_trust, h.name),
        if (s.personalToHelpers) w.s(R.string.helper_footer_trust_all, sendPersonal(w)) else null).joinToString(" ")
    out += w.s(R.string.helper_footer_budget_where, w.label(UiStrings.inSettings(UiStrings.helpers)))
    return out.joinToString("\n\n")
}

/** Your aliases, cleaned and without repeats. */
private fun aliasNames(v: String): List<String> = v.split(',').map(HelperNames::clean).filter { it.isNotEmpty() }.distinct()

/** Why one of the names typed can't be used, or null. */
private fun aliasProblem(v: String, h: HelperConfig, s: RobotSettings, wake: Set<String>, w: Words): String? =
    aliasNames(v).firstNotNullOfOrNull { n -> (HelperNames.check(n, h.id, s, wake) as? NameCheck.Problem)?.let { w.s(R.string.helper_alias_problem, n, w.p(it.text)) } }

/** Where the price comes from (and when it was read). */
private fun priceText(h: HelperConfig, w: Words): String {
    val lang = w.lang
    fun usd(d: Double) = HelperFormat.perMillion(d, lang)
    return when (val pr = h.price) {
        HelperPrice.Free -> w.s(R.string.helper_price_free)
        is HelperPrice.Yours -> w.s(R.string.helper_price_yours, usd(pr.rates.input), usd(pr.rates.output))
        is HelperPrice.Published -> {
            val c = pr.card
            // English keeps 1.0's day ("2026-12-31"); French writes it out.
            val until = c.until?.let { if (lang == Lang.FR) w.p(Pricing.dateText(it)) else it.toString() }.orEmpty()
            val clauses = listOfNotNull(
                if (c.rates.cacheRead != c.rates.input) w.s(R.string.helper_price_rates_cached, usd(c.rates.input), usd(c.rates.output), usd(c.rates.cacheRead))
                else w.s(R.string.helper_price_rates, usd(c.rates.input), usd(c.rates.output)),
                c.long?.let { w.s(R.string.helper_price_long) },
                c.peak?.let { w.s(R.string.helper_price_peak) },
                c.then?.let { w.s(R.string.helper_price_then, usd(it.rates.input), usd(it.rates.output), until) },
            )
            // One sentence: its parts joined by a semicolon (French puts a narrow no-break space before it).
            val rates = clauses.joinToString(if (lang == Lang.FR) " ; " else "; ", postfix = ".")
            val asOf = c.asOfText?.let(w::p)
            val read = when {
                asOf != null && c.source.isNotEmpty() -> w.s(R.string.helper_prices_as_of_from, asOf, c.source)
                asOf != null -> w.s(R.string.helper_prices_as_of, asOf)
                c.source.isNotEmpty() -> w.s(R.string.helper_prices_from, c.source)
                else -> null
            }
            listOfNotNull(rates, read).joinToString(" ")
        }
        is HelperPrice.Reported -> w.s(R.string.helper_price_reported, w.p(h.presetOf.companyText))
        HelperPrice.Unknown -> if (h.home) w.s(R.string.helper_price_unknown_home, h.name) else w.s(R.string.helper_price_unknown, h.name, w.p(h.presetOf.companyText))
    }
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
        title = { Text(stringResource(if (h.home) R.string.helper_price_of else R.string.helper_prices_for, h.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text(stringResource(R.string.helper_price_in)) },
                        isError = input.isNotEmpty() && i == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = output, onValueChange = { output = it }, singleLine = true, label = { Text(stringResource(R.string.helper_price_out)) },
                        isError = output.isNotEmpty() && o == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Text(if (h.model.isEmpty()) stringResource(R.string.helper_price_unit_this) else stringResource(R.string.helper_price_unit_for, h.model),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Rates(i!!, o!!)) }, enabled = i != null && o != null) { Text(UiStrings.save().str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}

/** What a catalog model can do, as its dialog says it after its price ("(Can use Robot's actions, can see pictures.)"). */
@StringRes
private fun badges(tools: Boolean, search: Boolean, vision: Boolean): Int? = when {
    tools && search && vision -> R.string.helper_badges_tsv
    tools && search -> R.string.helper_badges_ts
    tools && vision -> R.string.helper_badges_tv
    search && vision -> R.string.helper_badges_sv
    tools -> R.string.helper_badges_t
    search -> R.string.helper_badges_s
    vision -> R.string.helper_badges_v
    else -> null
}

/**
 * The model: the catalog's (recommended first, with what each can do), the server's list (searchable when long, 40 shown at
 * most), or any id you type. A pick closes the dialog.
 */
@Composable
private fun ModelDialog(h: HelperConfig, facts: HelperFacts, a: AppActions, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val w = rememberWords()
    val p = h.presetOf
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var query by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf(h.model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.helper_model)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.protocol == Protocol.ANTHROPIC) {
                    for (m in AnthropicModel.entries) ModelChoice(m.title, m.detailText.str(), AnthropicModel.of(h.model) == m) { onPick(m.id) }
                    return@Column
                }
                for (m in p.models) {
                    val detail = listOfNotNull(w.p(m.detailText), badges(m.tools, m.search, m.vision)?.let { w.s(it) }).joinToString(" ")
                    ModelChoice(m.title, detail, h.model == m.id) { onPick(m.id) }
                }
                val listed = (if (facts.forAddress == null || facts.forAddress == h.baseUrl) facts.models else emptyList()).filter { id -> p.models.none { it.id == id } }
                if (listed.size > 8) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(pluralStringResource(R.plurals.helper_models_search, listed.size, listed.size)) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                }
                val matching = listed.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                for (m in matching.take(40)) ModelChoice(m, null, h.model == m) { onPick(m) }
                if (matching.size > 40) Text(stringResource(R.string.helper_models_more), style = MaterialTheme.typography.bodyMedium, color = muted)
                if (h.model.isNotEmpty() && p.models.none { it.id == h.model } && h.model !in listed) {
                    ModelChoice(h.model, stringResource(R.string.helper_model_yours), true) { onPick(h.model) }
                }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it.trim().take(HelperConfig.MAX_MODEL) }, singleLine = true, label = { Text(stringResource(R.string.helper_model_id)) },
                    supportingText = { Text(if (h.home) stringResource(R.string.helper_model_id_hint_home) else stringResource(R.string.helper_model_id_hint, p.companyText.str())) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (typed.isNotEmpty()) onPick(typed) }),
                    trailingIcon = { if (typed != h.model && typed.isNotEmpty()) TextButton(onClick = { onPick(typed) }) { Text(stringResource(R.string.helper_use)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (listed.isEmpty() && p.models.isEmpty()) {
                    Text(stringResource(if (h.home) R.string.helper_models_listed_home else R.string.helper_models_listed), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
        dismissButton = if (p.protocol == Protocol.ANTHROPIC) null else ({
            TextButton(onClick = { onDismiss(); a.helperWindow(h.id, HelperWindow.MODE_TEST) }) { Text(stringResource(R.string.helper_models_refresh)) }
        }),
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
private fun searchNote(h: HelperConfig, w: Words): String {
    val per = if (h.presetOf.protocol == Protocol.ANTHROPIC) 0.01 else PriceTable.of(h.model)?.perSearch
    // The cents as 1.0 wrote them ("1", "0.5"), then in the menus' language ("1¢", « 0,5 ¢ »).
    val price = per?.let { HelperFormat.cents(if (it >= 0.01) (it * 100).let { c -> if (c == Math.floor(c)) c.toInt().toString() else c.toString() } else "${it * 100}", w.lang) }
    val company = w.p(h.presetOf.companyText)
    return when (h.presetOf.quirks.search) {
        SearchStyle.ANTHROPIC -> w.s(R.string.helper_search_anthropic, price.orEmpty())
        SearchStyle.OPENROUTER -> w.s(R.string.helper_search_openrouter)
        else -> price?.let { w.s(R.string.helper_search_priced, company, it) } ?: w.s(R.string.helper_search_unpriced, company)
    }
}
