// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.connect.AppPinState
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnCheck
import app.heyrobot.core.connect.ConnFile
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnLinks
import app.heyrobot.core.connect.ConnOp
import app.heyrobot.core.connect.ConnProblem
import app.heyrobot.core.connect.ConnSetup
import app.heyrobot.core.connect.ConnState
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.connect.HandOffAction
import app.heyrobot.core.connect.HandOffExtra
import app.heyrobot.core.connect.HaPick
import app.heyrobot.core.connect.HttpMethod
import app.heyrobot.core.connect.IntentSpec
import app.heyrobot.core.connect.IntentSpecs
import app.heyrobot.core.connect.OpBody
import app.heyrobot.core.connect.OpOutput
import app.heyrobot.core.connect.OpParam
import app.heyrobot.core.connect.OpSpec
import app.heyrobot.core.connect.OpTemplate
import app.heyrobot.core.connect.PickProblem
import app.heyrobot.core.connect.HandOffPicker
import app.heyrobot.core.connect.RoutingKind
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.core.connect.WebhookForm
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillNumbers
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.termux.ScriptLinks
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.ConnWindow
import app.heyrobot.sys.HandOffPicks
import app.heyrobot.sys.SkillFiles
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

// MAIN PROCESS (docs/plans/skills-connections-plan.md §6.1 to §6.3; C9). Settings › Skills › Connections: your connections
// with their state, Add a connection (each kind with what it does and what leaves the phone), and each connection's page
// (its name, its address, its key, Test, its operations, first-use answers, a spoken yes, requests a minute, the skills
// that use it, Remove). The main process never holds a key: "Paste token", Test and Home Assistant's pickers open the
// connection window in :voice (ConnWindow), and a webhook whose address is itself the key is typed there too. This page
// shows addresses, so it is a secure window (no screenshots). connections.json is core ConnStore's (ConnFiles); every save
// goes through ConnCheck. The page reads the files again every 2 s for what the window and :voice changed.

object ConnRoutes {
    const val LIST = "skills/connections" // l10n: not text (a route)
    fun add(kind: ConnKind) = "$LIST/add/${kind.id}" // l10n: not text (a route)
    fun page(id: String) = "$LIST/$id" // l10n: not text (a route)
    fun hook(id: String) = "$LIST/$id/hook" // l10n: not text (a route)
    fun handOff(id: String) = "$LIST/$id/hand_off" // l10n: not text (a route)
}

/** What the connections' pages show, read from the files now. */
data class ConnData(
    val connections: List<Connection> = emptyList(),
    /** connections.json can't be read, or a newer Robot wrote it. */
    val problem: ConnFile? = null,
    val keys: Map<String, Boolean> = emptyMap(),
    val masks: Map<String, Map<SecretPart, String>> = emptyMap(),
    val apps: Map<String, AppPinState> = emptyMap(),
    val skills: List<Skill> = emptyList(),
    val scripts: List<TermuxScriptInfo> = emptyList(),
    /** Settings › Car mode has a Home saved (travel time and a routing server's Test need it). */
    val home: Boolean = false,
    val location: Boolean = false,
) {
    val links: ConnLinks get() = ConnLinks(connections, ScriptLinks(scripts))

    fun state(c: Connection): ConnState = ConnSetup.state(c, keys[c.id] == true, apps[c.id])

    companion object {
        fun read(ctx: android.content.Context): ConnData {
            val file = ConnFiles.store(ctx).file()
            val list = (file as? ConnFile.Ok)?.connections.orEmpty()
            val skills = (SkillFiles.store(ctx).file() as? SkillFile.Ok)?.skills.orEmpty()
            return ConnData(list, file.takeIf { it !is ConnFile.Ok }, list.associate { it.id to (it.secrets.isEmpty() || ConnKeyFiles.saved(ctx, it.id)) },
                list.associate { it.id to ConnKeyFiles.masks(ctx, it.id) },
                list.filter { it.kind == ConnKind.HAND_OFF }.associate { it.id to HandOffPicks.state(ctx, it) }, skills,
                try { app.heyrobot.sys.TermuxStore.status(ctx).scripts } catch (_: Throwable) { emptyList() },
                try { app.heyrobot.sys.HandsFreeFiles.store(ctx).places().home != null } catch (_: Throwable) { false },
                app.heyrobot.sys.CoarseLocation.granted(ctx))
        }
    }
}

/** What the connections' pages can do. */
class ConnSettingsActions(
    /** A Settings page by route. */
    val open: (String) -> Unit = {},
    /** A Settings page by route in place of the one showing: a connection just added opens its page where its form was, so Back and Remove land on the list. */
    val instead: (String) -> Unit = open,
    /** Saves [Connection] (ConnCheck first); false when it couldn't. */
    val save: (Connection) -> Boolean = { true },
    /** A changed address: its key is deleted first, then it is saved. */
    val readdress: (Connection) -> Boolean = { true },
    /** Remove connection: its key and folder, then the file's entry. */
    val remove: (String) -> Unit = {},
    /** Opens the connection window in :voice ([ConnWindow]'s modes). */
    val window: (id: String, mode: String, name: String?) -> Unit = { _, _, _ -> },
    /** Forget a first-use answer. */
    val forget: (id: String, origin: String) -> Unit = { _, _ -> },
    /** The apps with a launcher icon (background thread). */
    val apps: () -> List<HandOffPicks.App> = { emptyList() },
    /** A hand-off pinned to its app now (background thread). */
    val pin: (id: String, name: String, pkg: String, spec: IntentSpec) -> Pair<Connection?, PickProblem?> = { _, _, _, _ -> null to PickProblem.NOT_VALID },
    val askLocation: () -> Unit = {},
    /** C11: connections.json can't be read: keep it aside, delete the keys it leaves without a connection, start over. */
    val startOver: () -> Unit = {},
)

/** Settings › Skills › Connections and its pages. */
@Composable
fun ConnectionsRoute(route: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val data = remember(tick) { ConnData.read(ctx) }
    val store = remember { ConnFiles.store(ctx) }
    val scope = rememberCoroutineScope()
    fun changed() { SkillFiles.changed(ctx); tick++ }
    val act = ConnSettingsActions(
        open = { a.openPage(Page.Settings(it)) },
        instead = { a.replacePage(Page.Settings(it)) },
        save = { c -> store.save(c).also { changed() } },
        // C12's L1: the key goes and the connection changes in one hold of the store's lock (the window checks its key inside it too).
        readdress = { c -> store.locked { ConnKeyFiles.delete(ctx, c.id); store.save(c) }.also { changed() } },
        remove = { id -> scope.launch { withContext(Dispatchers.IO) { store.locked { ConnKeyFiles.removeAll(ctx, id); store.delete(id) } }; changed() } },
        window = { id, mode, name -> if (!ConnWindow.open(ctx, id, mode, name)) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.conn_no_window)) } },
        forget = { id, origin -> store.answer(id, origin, null); changed() },
        apps = { HandOffPicks.apps(ctx) },
        pin = { id, name, pkg, spec -> HandOffPicks.pin(ctx, id, name, pkg, spec) },
        askLocation = a.askLocation,
        startOver = { scope.launch { withContext(Dispatchers.IO) { ConnFiles.startOver(ctx) }; changed() } },
    )
    val rest = route.removePrefix(ConnRoutes.LIST).trimStart('/')
    when {
        rest.isEmpty() -> ConnectionsScreen(data, act, snackbar, onBack)
        rest.startsWith("add/") -> {
            val kind = ConnKind.of(rest.removePrefix("add/"))
            when (kind) {
                null -> LaunchedEffect(route) { onBack() }
                ConnKind.HAND_OFF -> HandOffScreen(null, data, act, snackbar, onBack)
                else -> AddConnectionScreen(kind, data, act, snackbar, onBack)
            }
        }
        else -> {
            val id = rest.substringBefore('/')
            val c = data.connections.firstOrNull { it.id == id }
            when {
                c == null -> LaunchedEffect(route) { onBack() }
                rest.endsWith("/hook") -> WebhookFormScreen(c, act, snackbar, onBack)
                rest.endsWith("/hand_off") -> HandOffScreen(c, data, act, snackbar, onBack)
                else -> ConnectionPageScreen(c, data, act, snackbar, onBack)
            }
        }
    }
}

/** Settings › Skills' row: "Home Assistant · ntfy · 1 needs attention". */
fun connectionsSummary(d: ConnData): app.heyrobot.core.i18n.Phrase =
    ConnSetup.summary(d.connections, d.skills.count { app.heyrobot.core.skills.SkillCheck.attention(it, d.links).isNotEmpty() })

@Composable
fun ConnectionsScreen(d: ConnData, act: ConnSettingsActions, snackbar: SnackbarHostState, onBack: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    val res = LocalContext.current.resources
    val langs = app.heyrobot.ui.theme.LocalLangs.current
    var startOver by rememberSaveable { mutableStateOf(false) }
    SecureWindow()
    PreferenceScreen(app.heyrobot.core.i18n.ConnStrings.title().str(), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.conn_intro)) }
        if (d.problem != null) preferenceGroup(null, "problem") {
            // A newer Robot's file is left as it is (update Robot); one Robot can't read can start over, as Skills' and Routines' can.
            if (d.problem is ConnFile.TooNew) StatusPreference(stringResource(R.string.conn_file_newer), null, Mark.WARN)
            else StatusPreference(stringResource(R.string.conn_file_damaged), null, Mark.WARN, action = {
                TextButton(onClick = { startOver = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_start_over)) }
            })
        }
        preferenceGroup(res.getString(R.string.conn_yours), "mine") {
            if (d.connections.isEmpty()) PreferenceItem(app.heyrobot.core.i18n.ConnStrings.noneYet().str(), null)
            for (c in d.connections) {
                val st = d.state(c)
                val line = ConnSetup.row(c, st, now).str().let { if (app.heyrobot.core.connect.ConnRules.mcpUnencrypted(c)) res.getString(R.string.conn_row_not_encrypted, it) else it }
                if (ConnSetup.needsYou(st)) StatusPreference(c.name, line, Mark.TODO, onClick = { act.open(ConnRoutes.page(c.id)) })
                else PreferenceItem(c.name, line, onClick = { act.open(ConnRoutes.page(c.id)) })
            }
        }
        if (d.problem == null) preferenceGroup(res.getString(R.string.conn_add), "add") {
            for (k in ConnSetup.KINDS) PreferenceItem(ConnSetup.kindName(k).str(), ConnSetup.kindLine(k).str(), RIcons.Add, onClick = { act.open(ConnRoutes.add(k)) })
        }
        preferenceFooter(res.getString(R.string.conn_footer))
    }
    if (startOver) ConfirmDialog(stringResource(R.string.conn_start_over_title), stringResource(R.string.conn_start_over_text),
        stringResource(R.string.conn_start_over), onDismiss = { startOver = false }) { startOver = false; act.startOver() }
}

/** A text field on a page: [label], [supporting] under it, or the error. */
@Composable
private fun Field(label: String, value: String, supporting: String? = null, error: String? = null, singleLine: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = singleLine, isError = error != null, keyboardOptions = keyboard,
        supportingText = (error ?: supporting)?.let { t -> { Text(t) } }, modifier = Modifier.fillMaxWidth(), minLines = if (singleLine) 1 else 3)
}

/** How a key goes, as Add a connection offers it for [kind]. */
private enum class AuthChoice { NONE, TOKEN, HEADER, BASIC }

private fun authLabel(res: android.content.res.Resources, a: AuthChoice): String = res.getString(when (a) {
    AuthChoice.NONE -> R.string.conn_auth_none
    AuthChoice.TOKEN -> R.string.conn_auth_token
    AuthChoice.HEADER -> R.string.conn_auth_header
    AuthChoice.BASIC -> R.string.conn_auth_basic
})

/** Add a connection of [kind] (plan §6.2): its name and address; its key is pasted next, in the window. */
@Composable
fun AddConnectionScreen(kind: ConnKind, d: ConnData, act: ConnSettingsActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val res = LocalContext.current.resources
    val langs = app.heyrobot.ui.theme.LocalLangs.current
    SecureWindow()
    val kindName = ConnSetup.kindName(kind).str()
    var name by rememberSaveable { mutableStateOf(kindName) }
    var address by rememberSaveable { mutableStateOf("") }
    var secretAddress by rememberSaveable { mutableStateOf(false) }
    var auth by rememberSaveable { mutableStateOf(if (kind == ConnKind.HOME_ASSISTANT || kind == ConnKind.MCP) AuthChoice.TOKEN else AuthChoice.NONE) }
    var header by rememberSaveable { mutableStateOf("") }
    var routing by rememberSaveable { mutableStateOf(RoutingKind.OSRM) }
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<ConnProblem?>(null) }
    val ntfyTitle = stringResource(R.string.conn_ntfy_op_title)
    val auths = when (kind) {
        ConnKind.HOME_ASSISTANT -> listOf(AuthChoice.TOKEN)
        ConnKind.NTFY -> listOf(AuthChoice.NONE, AuthChoice.TOKEN)
        ConnKind.ROUTING -> listOf(AuthChoice.NONE, AuthChoice.TOKEN, AuthChoice.HEADER)
        ConnKind.MCP -> listOf(AuthChoice.TOKEN, AuthChoice.NONE)
        else -> AuthChoice.entries
    }
    fun connAuth(): ConnAuth = when (auth) {
        AuthChoice.NONE -> ConnAuth.None
        AuthChoice.TOKEN -> ConnAuth.Bearer
        AuthChoice.HEADER -> ConnAuth.Header(header.trim())
        AuthChoice.BASIC -> ConnAuth.Basic
    }
    // An address that is itself the key sends no other (ConnCheck): no auth rows to choose from.
    val showAuth = !(kind == ConnKind.WEBHOOK && secretAddress)
    PreferenceScreen(stringResource(R.string.conn_add_title, kindName), onBack, snackbar = snackbar) {
        item(key = "line") { IntroPreference(ConnSetup.kindLine(kind).str()) }
        preferenceGroup(null, "form") {
            PreferenceBlock {
                Field(stringResource(R.string.conn_name), name, error = if (name.isNotBlank() && !ConnCheck.validLine(name.trim(), ConnCheck.MAX_NAME))
                    stringResource(R.string.conn_name_rules) else null) { name = it.take(ConnCheck.MAX_NAME + 10) }
                if (kind == ConnKind.WEBHOOK) SwitchPreference(stringResource(R.string.conn_hook_secret), stringResource(R.string.conn_hook_secret_summary), secretAddress) {
                    secretAddress = it
                }
                if (kind == ConnKind.ROUTING) ListPreference(stringResource(R.string.conn_routing_kind), routing, RoutingKind.entries, { routingName(res, it) }) { routing = it }
                if (!(kind == ConnKind.WEBHOOK && secretAddress)) {
                    val label = stringResource(if (kind == ConnKind.NTFY) R.string.conn_server else R.string.conn_address)
                    Field(label, address, supporting = stringResource(when (kind) {
                        ConnKind.ROUTING -> R.string.conn_routing_help
                        ConnKind.WEBHOOK -> R.string.conn_hook_address_help
                        ConnKind.MCP -> R.string.conn_mcp_address_help
                        else -> R.string.conn_https_help
                    }), error = problem?.text(name.trim())?.str(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)) {
                        address = it.trim(); problem = null
                    }
                } else IntroPreference(stringResource(R.string.conn_hook_secret_next))
                if (showAuth && auths.size > 1) ListPreference(stringResource(R.string.conn_auth), auth, auths, { authLabel(res, it) }) { auth = it; problem = null }
                // Question 35 (ConnRules.httpsOnly): an MCP server only over https but on this phone, said here before you save.
                app.heyrobot.core.connect.ConnUrl.parse(address)?.takeIf { kind == ConnKind.MCP && !it.https }?.let { u ->
                    Text(stringResource(if (app.heyrobot.core.connect.ConnRules.onPhone(u)) R.string.conn_mcp_not_encrypted else R.string.conn_mcp_https_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // fix-skills13-security 4: a server on this phone can be stood in for by another app while it is stopped.
                if (kind == ConnKind.MCP && app.heyrobot.core.connect.ConnUrl.parse(address)?.let { u ->
                        u.host.equals("localhost", ignoreCase = true) || u.literal?.let(app.heyrobot.core.connect.ConnRules::loopback) == true } == true) // l10n: not text (a host name)
                    Text(stringResource(R.string.conn_mcp_on_phone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showAuth && auth == AuthChoice.HEADER) Field(stringResource(R.string.conn_header_name), header, supporting = stringResource(R.string.conn_header_name_help)) { header = it.trim() }
                if (kind == ConnKind.NTFY) SwitchPreference(stringResource(R.string.conn_ntfy_only_mine), stringResource(R.string.conn_ntfy_only_mine_summary), onlyMine) { onlyMine = it }
                if (kind == ConnKind.HOME_ASSISTANT || kind == ConnKind.NTFY || (showAuth && auth != AuthChoice.NONE))
                    Text(stringResource(R.string.conn_key_next), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        val id = ConnSetup.newId(d.connections.map { it.id }.toSet())
                        if (kind == ConnKind.WEBHOOK && secretAddress) {
                            act.window(id, ConnWindow.MODE_NEW_HOOK, name.trim())
                            onBack()
                            return@Button
                        }
                        val (c, why) = ConnSetup.create(id, kind, name, address, connAuth(), routing, ntfyTitle, onlyMine)
                        if (c == null || !act.save(c)) { problem = why ?: ConnProblem.COULDNT_SAVE; return@Button }
                        act.instead(ConnRoutes.page(c.id))
                        act.window(c.id, if (c.secrets.isNotEmpty()) ConnWindow.MODE_KEY else ConnWindow.MODE_TEST, null)
                    }, enabled = name.isNotBlank() && (address.isNotBlank() || (kind == ConnKind.WEBHOOK && secretAddress)) &&
                        (!showAuth || auth != AuthChoice.HEADER || ConnCheck.secretHeaderName(header)), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(UiStrings.save().str())
                    }
                }
                if (problem == ConnProblem.COULDNT_SAVE) Text(problem!!.text(name).str(), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun routingName(res: android.content.res.Resources, k: RoutingKind): String = res.getString(when (k) {
    RoutingKind.OSRM -> R.string.conn_routing_osrm
    RoutingKind.VALHALLA -> R.string.conn_routing_valhalla
    RoutingKind.GRAPHHOPPER -> R.string.conn_routing_graphhopper
})

/** A connection's page (plan §6.3). */
@Composable
fun ConnectionPageScreen(c: Connection, d: ConnData, act: ConnSettingsActions, snackbar: SnackbarHostState, onBack: () -> Unit,
    now: ZonedDateTime = ZonedDateTime.now()) {
    val res = LocalContext.current.resources
    val langs = app.heyrobot.ui.theme.LocalLangs.current
    SecureWindow()
    var removing by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val state = d.state(c)
    val masks = d.masks[c.id].orEmpty()
    fun save(n: Connection) { problem = if (act.save(n)) null else "" }
    val couldnt = stringResource(R.string.conn_couldnt_save)
    PreferenceScreen(c.name, onBack, snackbar = snackbar) {
        preferenceGroup(null, "state") {
            StatusPreference(ConnSetup.kindName(c.kind).str(), ConnSetup.stateText(c, state, now).str(), if (ConnSetup.needsYou(state)) Mark.TODO else Mark.OK)
            problem?.let { StatusPreference(couldnt, null, Mark.WARN) }
        }
        preferenceGroup(null, "name") {
            TextPreference(stringResource(R.string.conn_name), c.name, "", stringResource(R.string.conn_name), stringResource(R.string.conn_name_rules),
                validate = { if (ConnCheck.validLine(it.trim(), ConnCheck.MAX_NAME)) null else "" }) { save(c.copy(name = it.trim())) }
            if (c.kind != ConnKind.HAND_OFF) {
                val shown = if (c.secretAddress) (c.address?.origin.orEmpty() + "/" + (masks[SecretPart.ADDRESS] ?: "…")) else c.address?.text.orEmpty()
                if (c.secretAddress) PreferenceItem(stringResource(R.string.conn_address), shown, onClick = { act.window(c.id, ConnWindow.MODE_KEY, null) })
                else PreferenceItem(stringResource(R.string.conn_address), shown, onClick = { moving = c.address?.text.orEmpty() })
                // Question 35: a server on this phone over plain http is said as not encrypted.
                if (app.heyrobot.core.connect.ConnRules.mcpUnencrypted(c)) IntroPreference(stringResource(R.string.conn_mcp_not_encrypted))
            }
        }
        if (c.kind != ConnKind.HAND_OFF) preferenceGroup(null, "key") {
            if (c.secrets.isNotEmpty()) {
                val saved = d.keys[c.id] == true
                val mask = (c.secrets.firstNotNullOfOrNull { masks[it] }).orEmpty()
                PreferenceItem(stringResource(if (saved) R.string.conn_key_change else R.string.conn_paste), if (saved) stringResource(R.string.conn_saved, mask) else
                    stringResource(R.string.conn_key_missing), RIcons.Lock, onClick = { act.window(c.id, ConnWindow.MODE_KEY, null) })
            }
            val tested = c.tested?.let { ConnSetup.stateText(c, ConnState.TESTED, now).str() + when (c.where) {
                ConnWhere.HOME -> " · " + stringResource(R.string.conn_where_home)
                ConnWhere.INTERNET -> " · " + stringResource(R.string.conn_where_internet)
                null -> ""
            } } ?: app.heyrobot.core.i18n.ConnStrings.stateNotTested().str()
            PreferenceItem(stringResource(R.string.conn_test), tested, RIcons.Refresh, onClick = { act.window(c.id, ConnWindow.MODE_TEST, null) })
        }
        when (c.kind) {
            ConnKind.HOME_ASSISTANT -> preferenceGroup(res.getString(R.string.conn_operations), "ops") {
                for (op in c.ops) PreferenceItem(op.title, ConnStepWords.effects(HaPick.effects(c, op)), trailing = {
                    androidx.compose.material3.IconButton(onClick = { save(c.copy(ops = c.ops - op)) }) {
                        androidx.compose.material3.Icon(RIcons.Delete, stringResource(R.string.conn_remove_op, op.title))
                    }
                })
                PreferenceItem(stringResource(R.string.conn_add_op), stringResource(R.string.conn_add_op_summary), RIcons.Add,
                    onClick = { act.window(c.id, ConnWindow.MODE_PICK, null) })
            }
            ConnKind.NTFY -> preferenceGroup(res.getString(R.string.conn_operations), "ops") {
                for (op in c.ops) {
                    TextPreference(stringResource(R.string.conn_op_title), op.title, "", stringResource(R.string.conn_op_title), "",
                        validate = { if (ConnCheck.validLine(it.trim(), ConnCheck.MAX_TITLE)) null else "" }) { t ->
                        save(c.copy(ops = c.ops.map { if (it.id == op.id) it.copy(title = t.trim()) else it }))
                    }
                    val spec = op.spec as? OpSpec.NtfyPublish
                    if (spec != null) SwitchPreference(stringResource(R.string.conn_ntfy_only_mine), stringResource(R.string.conn_ntfy_only_mine_summary), spec.onlyMine) { on ->
                        save(c.copy(ops = c.ops.map { if (it.id == op.id) it.copy(spec = spec.copy(onlyMine = on)) else it }))
                    }
                }
            }
            ConnKind.WEBHOOK -> preferenceGroup(res.getString(R.string.conn_operations), "ops") {
                val op = c.ops.firstOrNull()
                val s = op?.spec as? OpSpec.Webhook
                PreferenceItem(op?.title ?: stringResource(R.string.conn_hook_request), s?.let { "${it.method.id} ${it.path.ifEmpty { "/" }}" }
                    ?: stringResource(R.string.conn_hook_not_set), RIcons.Settings, onClick = { act.open(ConnRoutes.hook(c.id)) })
            }
            ConnKind.ROUTING -> preferenceGroup(res.getString(R.string.conn_routing), "routing") {
                ListPreference(stringResource(R.string.conn_routing_kind), c.routing ?: RoutingKind.OSRM, RoutingKind.entries, { routingName(res, it) }) { save(c.copy(routing = it, tested = null)) }
                StatusPreference(stringResource(R.string.conn_routing_home), stringResource(if (d.home) R.string.conn_routing_home_ok else R.string.conn_routing_home_todo),
                    if (d.home) Mark.OK else Mark.TODO)
                StatusPreference(stringResource(R.string.conn_routing_location), stringResource(if (d.location) R.string.conn_routing_location_ok else R.string.conn_routing_location_todo),
                    if (d.location) Mark.OK else Mark.TODO, action = if (d.location) null else ({
                        TextButton(onClick = act.askLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.allow().str()) }
                    }))
            }
            ConnKind.MCP -> mcpTools(c)
            ConnKind.HAND_OFF -> preferenceGroup(res.getString(R.string.conn_hand_off_app), "app") {
                val h = c.handOff!!
                PreferenceItem(c.name, h.pkg, RIcons.Apps)
                PreferenceItem(stringResource(R.string.conn_hand_off_what), actionLabel(res, h.intent.action), RIcons.Settings, onClick = { act.open(ConnRoutes.handOff(c.id)) })
                if (state == ConnState.APP_REPLACED || state == ConnState.APP_MISSING || state == ConnState.PICK_AGAIN) StatusPreference(ConnSetup.stateText(c, state, now).str(), null, Mark.WARN, action = {
                    TextButton(onClick = { act.open(ConnRoutes.handOff(c.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_pick_again)) }
                })
            }
        }
        if (c.answers.isNotEmpty()) preferenceGroup(res.getString(R.string.conn_answers), "answers") {
            for ((origin, ans) in c.answers) PreferenceItem(origin, stringResource(if (ans == ConsentAnswer.ALLOW) R.string.conn_answer_allow else R.string.conn_answer_once), trailing = {
                TextButton(onClick = { act.forget(c.id, origin) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_forget)) }
            })
        }
        if (c.kind != ConnKind.HAND_OFF) preferenceGroup(null, "limits") {
            val securityOnly = c.ops.isNotEmpty() && c.ops.all { SkillEffect.SECURITY in HaPick.effects(c, it) }
            if (!securityOnly) SwitchPreference(stringResource(R.string.conn_spoken_yes), stringResource(R.string.conn_spoken_yes_summary), c.spokenYes) { save(c.copy(spokenYes = it)) }
            ListPreference(stringResource(R.string.conn_per_minute), c.perMinute, (listOf(5, 10, 20, 30, 45, 60) + c.perMinute).distinct().sorted(), { it.toString() }) {
                save(c.copy(perMinute = it))
            }
        }
        preferenceGroup(res.getString(R.string.conn_used_by), "used") {
            val used = ConnSetup.usedBy(c.id, d.skills, d.connections)
            if (used.isEmpty()) PreferenceItem(stringResource(R.string.conn_used_by_none), null)
            for (s in used) PreferenceItem(s.name, null, RIcons.Robot, onClick = { act.open(SkillRoutes.skill(s.id)) })
        }
        preferenceGroup(null, "remove") {
            PreferenceItem(stringResource(R.string.conn_remove), null, RIcons.Delete, onClick = { removing = true })
        }
        // C13: the help names the user guide's steps for each way (D3); a link in words, never a page opened from the app.
        if (c.kind == ConnKind.HOME_ASSISTANT) preferenceFooter(res.getString(R.string.conn_https_help) + " " + res.getString(R.string.conn_https_guide))
        if (c.kind == ConnKind.ROUTING) preferenceFooter(res.getString(R.string.conn_routing_help))
    }
    moving?.let { now0 ->
        app.heyrobot.ui.pref.EditDialog(stringResource(R.string.conn_address), now0, stringResource(R.string.conn_address),
            stringResource(if (c.secrets.isEmpty()) R.string.conn_https_help else R.string.conn_address_key_goes),
            validate = { t -> if (t.isBlank()) null else ConnSetup.readdress(c, t).second?.text(c.name)?.render(langs) }, canSave = { it.isNotBlank() }, secure = true,
            onDismiss = { moving = null }) { t ->
            moving = null
            val n = ConnSetup.readdress(c, t).first ?: return@EditDialog
            if (n.address != c.address) { if (!act.readdress(n)) problem = "" }
        }
    }
    if (removing) {
        val n = ConnSetup.afterRemove(c.id, d.skills, d.connections, ScriptLinks(d.scripts))
        ConfirmDialog(stringResource(R.string.conn_remove_title, c.name), ConnSetup.removeText(n).str(), stringResource(R.string.conn_remove),
            onDismiss = { removing = false }) {
            removing = false
            act.remove(c.id)
            onBack()
        }
    }
}

/** The effects of an operation as a short line ("locks, unlocks or opens"). */
object ConnStepWords {
    @Composable
    fun effects(e: Set<SkillEffect>): String = app.heyrobot.core.i18n.Phrase.ListOf(app.heyrobot.core.connect.SkillEditor.chips(e)).str()
}

private fun actionLabel(res: android.content.res.Resources, a: HandOffAction): String = res.getString(when (a) {
    HandOffAction.MAIN -> R.string.conn_action_main
    HandOffAction.VIEW -> R.string.conn_action_view
    HandOffAction.SEND -> R.string.conn_action_send
    HandOffAction.SENDTO -> R.string.conn_action_sendto
    HandOffAction.DIAL -> R.string.conn_action_dial
})

/** An argument's type as the forms offer it. */
private val ARG_TYPES = listOf(ParamType.TEXT, ParamType.INTEGER, ParamType.NUMBER, ParamType.YES_NO, ParamType.CHOICE)

fun argTypeLabel(res: android.content.res.Resources, t: ParamType): String = res.getString(when (t) {
    ParamType.INTEGER -> R.string.conn_type_integer
    ParamType.NUMBER -> R.string.conn_type_number
    ParamType.YES_NO -> R.string.conn_type_yes_no
    ParamType.CHOICE -> R.string.conn_type_choice
    else -> R.string.conn_type_text
})

/** One argument's type and limits as typed in a form: "1..30" for numbers, "a|b|c" for a choice. */
private fun opParam(type: ParamType, limits: String): OpParam? = when (type) {
    ParamType.TEXT -> OpParam(ParamType.TEXT)
    ParamType.YES_NO -> OpParam(ParamType.YES_NO)
    ParamType.CHOICE -> limits.split('|').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { OpParam(ParamType.CHOICE, it) }
    ParamType.INTEGER, ParamType.NUMBER -> {
        val m = Regex("^\\s*(-?[0-9.]+)\\s*\\.\\.\\s*(-?[0-9.]+)\\s*$").matchEntire(limits)
        val lo = m?.groupValues?.get(1)?.toDoubleOrNull()
        val hi = m?.groupValues?.get(2)?.toDoubleOrNull()
        if (lo == null || hi == null) null else OpParam(type, min = lo, max = hi)
    }
    else -> null
}

/** [p]'s limits as the form shows them. */
private fun limitsText(p: OpParam): String = when (p.type) {
    ParamType.CHOICE -> p.options.joinToString("|")
    ParamType.INTEGER, ParamType.NUMBER -> "${p.min?.let(SkillNumbers::text).orEmpty()}..${p.max?.let(SkillNumbers::text).orEmpty()}"
    else -> ""
}

/**
 * The webhook's one request (plan §3.3, C6's WebhookForm): method, path, query, headers, body, the arguments a step fills
 * (named `{{…}}` in them, each with a type), the outputs kept from its answer, a check, and your marks. Each field shows
 * its own problem; it is saved only when every rule passes.
 */
@Composable
fun WebhookFormScreen(c: Connection, act: ConnSettingsActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val res = LocalContext.current.resources
    val langs = app.heyrobot.ui.theme.LocalLangs.current
    SecureWindow()
    val op = c.ops.firstOrNull()
    val s = op?.spec as? OpSpec.Webhook
    var title by rememberSaveable { mutableStateOf(op?.title.orEmpty()) }
    var method by rememberSaveable { mutableStateOf(s?.method ?: HttpMethod.POST) }
    var path by rememberSaveable { mutableStateOf(s?.path.orEmpty()) }
    var query by rememberSaveable { mutableStateOf(s?.query.orEmpty().joinToString("\n") { "${it.first}=${it.second}" }) }
    var headers by rememberSaveable { mutableStateOf(s?.headers.orEmpty().joinToString("\n") { "${it.first}: ${it.second}" }) }
    var body by rememberSaveable { mutableStateOf(when (val b = s?.body) { is OpBody.Json -> app.heyrobot.core.connect.ConnJson.write(b.tree); is OpBody.Text -> b.template; null -> "" }) }
    var json by rememberSaveable { mutableStateOf(s?.body !is OpBody.Text) }
    var types by rememberSaveable(stateSaver = ARG_TYPES_SAVER) { mutableStateOf(op?.params.orEmpty().mapValues { it.value.type }) }
    var limits by rememberSaveable(stateSaver = ARG_LIMITS_SAVER) { mutableStateOf(op?.params.orEmpty().mapValues { limitsText(it.value) }) }
    var outputs by rememberSaveable { mutableStateOf(op?.outputs.orEmpty().entries.joinToString("\n") { (n, o) -> "$n ${o.type.id} ${o.pointer}" }) }
    var checkPointer by rememberSaveable { mutableStateOf((op?.check)?.pointer.orEmpty()) }
    var checkEquals by rememberSaveable { mutableStateOf((op?.check)?.equals?.toString().orEmpty()) }
    var marks by rememberSaveable(stateSaver = MARKS_SAVER) { mutableStateOf(WebhookForm.startMarks(c)) }
    var problems by remember { mutableStateOf<Map<WebhookForm.Field, ConnProblem>>(emptyMap()) }
    val q = query.lines().filter { it.isNotBlank() }.map { l -> l.substringBefore('=').trim() to l.substringAfter('=', "").trim() }
    val h = headers.lines().filter { it.isNotBlank() }.map { l -> l.substringBefore(':').trim() to l.substringAfter(':', "").trim() }
    // A method without a body hides the field: what it still holds doesn't count.
    val sent = body.takeIf { method.body && it.isNotBlank() }
    val names = WebhookForm.names(OpSpec.Webhook(method, path, q, h, if (sent == null || json) null else OpBody.Text(sent))) +
        (if (json && sent != null) Regex("\\{\\{([a-z][a-z0-9_]{0,31})\\}\\}").findAll(sent).map { it.groupValues[1] }.toSet() else emptySet())
    fun fields(): WebhookForm.Fields? {
        fun bad(f: WebhookForm.Field): WebhookForm.Fields? { problems = mapOf(f to ConnProblem.COULDNT_SAVE); return null }
        val params = names.associateWith { n -> opParam(types[n] ?: ParamType.TEXT, limits[n].orEmpty()) ?: return bad(WebhookForm.Field.ARGUMENTS) }
        val outs = outputs.lines().filter { it.isNotBlank() }.associate { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size != 3) return bad(WebhookForm.Field.OUTPUTS)
            val t = ParamType.of(p[1])?.takeIf { it in ConnCheck.TYPES && it != ParamType.CHOICE } ?: return bad(WebhookForm.Field.OUTPUTS)
            p[0] to OpOutput(p[2], t)
        }
        val check = if (checkPointer.isBlank()) null else StepCheck.Equals(checkPointer.trim(), WebhookForm.checkValue(checkEquals))
        return WebhookForm.Fields(title.trim(), method.id, path.trim(), q, h, sent, json, params, outs, check, marks)
    }
    val bad = stringResource(R.string.conn_field_bad)
    fun err(f: WebhookForm.Field): String? = problems[f]?.let { if (it == ConnProblem.COULDNT_SAVE) bad else it.text(c.name).render(langs) }
    PreferenceScreen(stringResource(R.string.conn_hook_request), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.conn_hook_intro, c.address?.origin.orEmpty())) }
        preferenceGroup(null, "request") {
            PreferenceBlock {
                Field(stringResource(R.string.conn_op_title), title, supporting = stringResource(R.string.conn_op_title_help), error = err(WebhookForm.Field.TITLE)) { title = it }
                ListPreference(stringResource(R.string.conn_hook_method), method, HttpMethod.entries, { it.id }) { method = it }
                Field(stringResource(R.string.conn_hook_path), path, supporting = stringResource(R.string.conn_hook_path_help), error = err(WebhookForm.Field.PATH)) { path = it }
                Field(stringResource(R.string.conn_hook_query), query, supporting = stringResource(R.string.conn_hook_query_help), error = err(WebhookForm.Field.QUERY),
                    singleLine = false) { query = it }
                Field(stringResource(R.string.conn_hook_headers), headers, supporting = stringResource(R.string.conn_hook_headers_help), error = err(WebhookForm.Field.HEADERS),
                    singleLine = false) { headers = it }
                if (method.body) {
                    SwitchPreference(stringResource(R.string.conn_hook_json), null, json) { json = it }
                    Field(stringResource(R.string.conn_hook_body), body, supporting = stringResource(R.string.conn_hook_body_help), error = err(WebhookForm.Field.BODY),
                        singleLine = false) { body = it }
                }
            }
        }
        if (names.isNotEmpty()) preferenceGroup(res.getString(R.string.conn_hook_arguments), "args") {
            for (n in names.sorted()) {
                val t = types[n] ?: ParamType.TEXT
                ListPreference(n, t, ARG_TYPES, { argTypeLabel(res, it) }) { types = types + (n to it) }
                if (t == ParamType.INTEGER || t == ParamType.NUMBER || t == ParamType.CHOICE) PreferenceBlock {
                    Field(stringResource(if (t == ParamType.CHOICE) R.string.conn_arg_values else R.string.conn_arg_limits), limits[n].orEmpty(),
                        supporting = stringResource(if (t == ParamType.CHOICE) R.string.conn_arg_values_help else R.string.conn_arg_limits_help)) { limits = limits + (n to it) }
                }
            }
        }
        preferenceGroup(res.getString(R.string.conn_hook_answer), "answer") {
            PreferenceBlock {
                Field(stringResource(R.string.conn_hook_outputs), outputs, supporting = stringResource(R.string.conn_hook_outputs_help), error = err(WebhookForm.Field.OUTPUTS),
                    singleLine = false) { outputs = it }
                Field(stringResource(R.string.conn_hook_check), checkPointer, supporting = stringResource(R.string.conn_hook_check_help), error = err(WebhookForm.Field.CHECK)) { checkPointer = it }
                if (checkPointer.isNotBlank()) Field(stringResource(R.string.conn_hook_check_equals), checkEquals) { checkEquals = it }
            }
        }
        preferenceGroup(res.getString(R.string.conn_marks), "marks") {
            for ((m, label) in listOf(SkillEffect.MONEY to R.string.conn_mark_money, SkillEffect.SECURITY to R.string.conn_mark_security,
                SkillEffect.MESSAGES_SOMEONE to R.string.conn_mark_tells)) {
                SwitchPreference(stringResource(label), null, m in marks) { on -> marks = if (on) marks + m else marks - m }
            }
        }
        preferenceGroup(null, "save") {
            // Beside Save, not under Arguments: that section is gone when nothing names one.
            err(WebhookForm.Field.ARGUMENTS)?.let { StatusPreference(it, null, Mark.WARN) }
            PreferenceBlock {
                Button(onClick = {
                    val f = fields() ?: return@Button
                    when (val m = WebhookForm.make(c, f)) {
                        is WebhookForm.Made.Problems -> problems = m.fields
                        is WebhookForm.Made.Ok -> if (act.save(c.copy(ops = listOf(m.op)))) onBack() else problems = mapOf(WebhookForm.Field.ARGUMENTS to ConnProblem.COULDNT_SAVE)
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.save().str()) }
            }
        }
    }
}

/**
 * A hand-off (plan §3.5, §6.2): pick an app with a launcher icon (Robot pins its signer), then what it is asked to do:
 * open it, open an address in it, share text with it, write to someone in it, or dial. A `{{slot}}` in the address or the
 * text is what a skill's step fills. [existing]: an existing hand-off, its app picked again (a replaced app) or what it opens changed.
 */
@Composable
fun HandOffScreen(existing: Connection?, d: ConnData, act: ConnSettingsActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val res = LocalContext.current.resources
    val langs = app.heyrobot.ui.theme.LocalLangs.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<HandOffPicks.App>?>(null) }
    var picked by rememberSaveable { mutableStateOf(existing?.handOff?.pkg) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    val spec = existing?.handOff?.intent
    var action by rememberSaveable { mutableStateOf(spec?.action ?: HandOffAction.MAIN) }
    var data by rememberSaveable { mutableStateOf(spec?.data.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(spec?.extras?.firstOrNull { it.name == EXTRA_TEXT }?.value.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastPkg by rememberSaveable { mutableStateOf(existing?.handOff?.pkg) }
    var replaced by remember { mutableStateOf<Connection?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { act.apps() } }
    val notValid = stringResource(R.string.conn_hand_off_not_valid)
    fun done(keep: Connection?, why: PickProblem?) {
        if (keep == null || !act.save(keep)) {
            problem = why?.let { w -> HandOffPicker.text(w, name.trim()) }?.render(langs).takeIf { why != PickProblem.NOT_VALID } ?: notValid
            return
        }
        if (existing == null) act.instead(ConnRoutes.page(keep.id)) else onBack()
    }
    PreferenceScreen(stringResource(R.string.conn_hand_off_title), onBack, snackbar = snackbar) {
        val pkg = picked
        if (pkg == null) {
            item(key = "intro") { IntroPreference(stringResource(R.string.conn_hand_off_pick)) }
            preferenceGroup(null, "apps") {
                val list = apps
                if (list == null) PreferenceItem(stringResource(R.string.conn_hand_off_loading), null)
                else for (app in list) PreferenceItem(app.label, null, RIcons.Apps, onClick = {
                    name = HandOffPicker.nameAfterPick(name, list.firstOrNull { it.pkg == lastPkg }?.label?.take(ConnCheck.MAX_NAME), app.label)
                    picked = app.pkg; lastPkg = app.pkg
                })
            }
            return@PreferenceScreen
        }
        preferenceGroup(null, "form") {
            PreferenceBlock {
                Field(stringResource(R.string.conn_name), name, supporting = pkg) { name = it }
                ListPreference(stringResource(R.string.conn_hand_off_what), action, HandOffAction.entries, { actionLabel(res, it) }) { action = it; problem = null }
                if (action == HandOffAction.VIEW || action == HandOffAction.SENDTO || action == HandOffAction.DIAL)
                    Field(stringResource(R.string.conn_hand_off_address), data, supporting = stringResource(R.string.conn_hand_off_address_help),
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)) { data = it.trim(); problem = null }
                if (action == HandOffAction.SEND || action == HandOffAction.SENDTO)
                    Field(stringResource(R.string.conn_hand_off_text), text, supporting = stringResource(R.string.conn_hand_off_text_help), singleLine = false) { text = it; problem = null }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val slots = IntentSpecs.slots(action, data, text)
                    val s = IntentSpec(action,
                        data = data.takeIf { action == HandOffAction.VIEW || action == HandOffAction.SENDTO || action == HandOffAction.DIAL }?.ifBlank { null },
                        mime = if (action == HandOffAction.SEND) IntentSpecs.TEXT_PLAIN else null,
                        extras = listOfNotNull(text.trim().takeIf { it.isNotEmpty() && (action == HandOffAction.SEND || action == HandOffAction.SENDTO) }
                            ?.let { HandOffExtra(EXTRA_TEXT, ParamType.TEXT, it) }),
                        params = slots.associateWith { OpParam(ParamType.TEXT) })
                    busy = true
                    scope.launch {
                        val id = existing?.id ?: ConnSetup.newId(d.connections.map { it.id }.toSet())
                        val (made, why) = withContext(Dispatchers.IO) { act.pin(id, name.trim(), pkg, s) }
                        busy = false
                        val keep = made?.let { m -> existing?.let { e -> m.copy(spokenYes = e.spokenYes) } ?: m }
                        if (HandOffPicker.signerChanged(existing?.handOff, keep?.handOff)) replaced = keep else done(keep, why)
                    }
                }, enabled = !busy && ConnCheck.validLine(name.trim(), ConnCheck.MAX_NAME) && (action != HandOffAction.SEND || text.isNotBlank()) &&
                    (action == HandOffAction.MAIN || action == HandOffAction.SEND || action == HandOffAction.SENDTO || data.isNotBlank()),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.save().str()) }
                TextButton(onClick = { picked = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_hand_off_other_app)) }
            }
        }
        preferenceFooter(res.getString(R.string.conn_hand_off_footer))
    }
    replaced?.let { m ->
        app.heyrobot.ui.common.AlertDialog(onDismissRequest = { replaced = null },
            title = { Text(stringResource(R.string.conn_hand_off_signer_title)) },
            text = { Text(stringResource(R.string.conn_hand_off_signer_body, m.name)) },
            confirmButton = {
                TextButton(onClick = { replaced = null; done(m, null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_hand_off_signer_trust)) }
            },
            dismissButton = {
                TextButton(onClick = { replaced = null; done(m.copy(handOff = m.handOff?.copy(signer = existing?.handOff?.signer)), null) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_hand_off_signer_keep)) }
            })
    }
}

/** The webhook form's choices kept when Android recreates the screen (a rotation, a change of language). */
internal val ARG_TYPES_SAVER = androidx.compose.runtime.saveable.Saver<Map<String, ParamType>, ArrayList<String>>(
    save = { m -> ArrayList(m.flatMap { (k, v) -> listOf(k, v.id) }) },
    restore = { l -> l.chunked(2).mapNotNull { (k, v) -> ParamType.of(v)?.let { k to it } }.toMap() })
internal val ARG_LIMITS_SAVER = androidx.compose.runtime.saveable.Saver<Map<String, String>, ArrayList<String>>(
    save = { m -> ArrayList(m.flatMap { (k, v) -> listOf(k, v) }) },
    restore = { l -> l.chunked(2).associate { (k, v) -> k to v } })
internal val MARKS_SAVER = androidx.compose.runtime.saveable.Saver<Set<SkillEffect>, ArrayList<String>>(
    save = { s -> ArrayList(s.map { it.name }) },
    restore = { l -> l.mapNotNull { n -> SkillEffect.entries.firstOrNull { it.name == n } }.toSet() })

/** The text a SEND or SENDTO hands over. */
private const val EXTRA_TEXT = "android.intent.extra.TEXT" // l10n: not text (Android's extra name)
