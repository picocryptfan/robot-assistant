// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.connect.ConnCheck
import app.heyrobot.core.connect.ConnReply
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.McpMark
import app.heyrobot.core.connect.McpOps
import app.heyrobot.core.connect.McpPolicy
import app.heyrobot.core.connect.McpStore
import app.heyrobot.core.connect.McpTool
import app.heyrobot.core.connect.McpToolSetting
import app.heyrobot.core.connect.McpTurnOn
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.sys.Paths
import app.heyrobot.sys.SkillFiles
import app.heyrobot.ui.Mark
import app.heyrobot.ui.pref.PreferenceGroup
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.StatusPreference

// Settings › Skills › Connections, an MCP server's page (1.3, design §8.5; S3.6): each tool its server listed at Test,
// off until you turn it on with your label (what the model and the card call it), a level and marks. The server's own
// description is shown only in a grey box under "The server's words", never as Robot's. What changed on the server since
// you turned a tool on is said here (the tool is off again). Main process: mcp_tools.json only; Test (the connection
// window, :voice) lists the tools; :voice reads the file again on Cmd.SKILLS.

internal fun LazyListScope.mcpTools(c: Connection) {
    item(key = "mcp_tools", contentType = "group") { McpTools(c) } // l10n: not text (a list key)
}

@Composable
private fun McpTools(c: Connection) {
    val ctx = LocalContext.current
    val langs = app.heyrobot.ui.theme.LocalLangs.current
    val store = remember { McpStore(Paths.dir(ctx)) }
    var file by remember(c.id, c.tested) { mutableStateOf(store.read(c.id)) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val f = file
    val tools = f?.listed?.tools.orEmpty()
    PreferenceGroup(stringResource(R.string.conn_mcp_tools)) {
        when {
            f == null -> StatusPreference(stringResource(R.string.conn_mcp_unreadable), null, Mark.WARN)
            f.otherServer -> StatusPreference(stringResource(R.string.conn_mcp_other_server), null, Mark.WARN)
            tools.isEmpty() -> PreferenceItem(stringResource(R.string.conn_mcp_none), stringResource(R.string.conn_mcp_none_summary))
        }
        if (f != null) for (n in f.notes) McpPolicy.text(n.change, n.tool, c.name)?.let { words ->
            StatusPreference(words.joinToString(" ") { it.render(langs) }, null, Mark.WARN)
        }
        if (f != null && !f.otherServer) for (t in tools) {
            val s = f.setting(t.name)
            PreferenceItem(t.name, if (s.level == SkillLevel.OFF) stringResource(R.string.conn_mcp_off)
                else stringResource(R.string.conn_mcp_on, s.label, levelName(s.level)), onClick = { editing = t.name })
        }
    }
    val tool = editing?.let { f?.listed?.tool(it) }
    if (f != null && tool != null) McpToolDialog(tool, f.setting(tool.name), onDismiss = { editing = null }) { s ->
        var why = McpTurnOn.OK
        val saved = store.edit(c.id) { cur -> McpOps.set(cur, s).let { (n, w) -> why = w; n } }
        if (saved != null && why == McpTurnOn.OK) {
            file = saved
            SkillFiles.changed(ctx)
            editing = null
            null
        } else why
    }
}

@Composable
private fun levelName(l: SkillLevel): String = stringResource(when (l) {
    SkillLevel.OFF -> R.string.conn_mcp_level_off
    SkillLevel.ASK_FIRST -> R.string.conn_mcp_level_ask
    SkillLevel.JUST_DO_IT -> R.string.conn_mcp_level_just
})

@Composable
private fun McpToolDialog(t: McpTool, s: McpToolSetting, onDismiss: () -> Unit, onSave: (McpToolSetting) -> McpTurnOn?) {
    var label by rememberSaveable { mutableStateOf(s.label) }
    var level by rememberSaveable { mutableStateOf(if (s.level == SkillLevel.OFF) SkillLevel.ASK_FIRST else s.level) }
    var reads by rememberSaveable { mutableStateOf(s.onlyReads) }
    var marks by remember { mutableStateOf(s.marks) }
    var problem by remember { mutableStateOf<McpTurnOn?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.conn_mcp_server_words), style = MaterialTheme.typography.labelMedium)
                    Text(ConnReply.clean(t.description.orEmpty()).take(DESCRIPTION), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(label, { label = it.take(ConnCheck.MAX_TITLE + 10); problem = null }, singleLine = true,
                label = { Text(stringResource(R.string.conn_mcp_label)) }, supportingText = { Text(stringResource(R.string.conn_mcp_label_help)) })
            for (l in listOf(SkillLevel.ASK_FIRST, SkillLevel.JUST_DO_IT, SkillLevel.OFF)) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(level == l, role = Role.RadioButton) { level = l; problem = null }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(level == l, null)
                Text(levelName(l), Modifier.padding(start = 8.dp))
            }
            Check(stringResource(R.string.conn_mcp_only_reads), reads) { reads = it; problem = null }
            for (m in McpMark.entries) Check(stringResource(when (m) {
                McpMark.MONEY -> R.string.conn_mcp_mark_money
                McpMark.SECURITY -> R.string.conn_mcp_mark_security
                McpMark.TELLS_PEOPLE -> R.string.conn_mcp_mark_people
            }), m in marks) { marks = if (it) marks + m else marks - m; problem = null }
            problem?.let { Text(stringResource(turnOnText(it)), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = { problem = onSave(McpToolSetting(t.name, level, label.trim(), reads, marks, s.gone)) }) { Text(stringResource(R.string.conn_mcp_save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.conn_mcp_cancel)) } })
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(text, Modifier.padding(start = 8.dp))
    }
}

private fun turnOnText(t: McpTurnOn): Int = when (t) {
    McpTurnOn.OK, McpTurnOn.UNUSABLE -> R.string.conn_mcp_unusable
    McpTurnOn.LABEL -> R.string.conn_mcp_label_rules
    McpTurnOn.ONLY_READS -> R.string.conn_mcp_not_only_reads
    McpTurnOn.JUST_DO -> R.string.conn_mcp_not_just_do
}

/** The server's description, as much as the grey box shows. */
private const val DESCRIPTION = 600
