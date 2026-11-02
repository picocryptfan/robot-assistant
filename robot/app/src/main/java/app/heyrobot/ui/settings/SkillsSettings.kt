// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.skills.NameContext
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEdit
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillNames
import app.heyrobot.core.skills.SkillRunRecord
import app.heyrobot.core.skills.SkillSettings
import app.heyrobot.core.skills.SkillState
import app.heyrobot.core.skills.SkillStats
import app.heyrobot.core.skills.Slot
import app.heyrobot.sys.SkillContext
import app.heyrobot.sys.SkillFiles
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

// MAIN PROCESS (1.1, Skills: docs/plans/skills-1.1-plan.md §9). Settings › Skills: your skills with their level and last
// run, and a page for each (its name and other names, checked as you type against Robot's own phrases; its level; its
// questions; its steps; how it was made; Delete). Everything is in files/robot (sys SkillFiles, core SkillStore), not in
// settings.json; each change tells :voice (Cmd.SKILLS), and this page reads the file again each time it shows (and every
// 2 s while it does) for what :voice changed: the main process hears no Cmd.

/** What Settings › Skills shows, read from the files now. */
data class SkillsData(
    val skills: List<Skill> = emptyList(),
    /** The skills file can't be read, or a newer Robot wrote it. */
    val problem: SkillFile? = null,
    /** Skills left out because they couldn't be read. */
    val dropped: Int = 0,
    val runs: Map<String, SkillRunRecord> = emptyMap(),
    /** What a name is checked against (core SkillNames). */
    val names: NameContext = NameContext(),
    /** Phase B: skills_state.json (the last runs and the fill samples) and skill_settings.json. */
    val state: SkillState = SkillState(),
    val settings: SkillSettings = SkillSettings(),
    /** C9: your connections and typed scripts, for Add a step, each step's page and the lines a skill's page computes. */
    val connections: List<app.heyrobot.core.connect.Connection> = emptyList(),
    val scripts: List<app.heyrobot.core.termux.TermuxScriptInfo> = emptyList(),
    val masks: Map<String, Map<app.heyrobot.core.connect.SecretPart, String>> = emptyMap(),
) {
    /** What a skill's steps link to (C9: ConnLinks over your connections and typed scripts). */
    val links: app.heyrobot.core.connect.ConnLinks get() = app.heyrobot.core.connect.ConnLinks(connections, app.heyrobot.core.termux.ScriptLinks(scripts))

    companion object {
        fun read(ctx: android.content.Context): SkillsData {
            val store = SkillFiles.store(ctx)
            val file = store.file()
            val skills = (file as? SkillFile.Ok)?.skills.orEmpty()
            val state = store.state()
            val conns = app.heyrobot.sys.ConnFiles.store(ctx).connections()
            return SkillsData(skills, file.takeIf { it !is SkillFile.Ok }, (file as? SkillFile.Ok)?.dropped ?: 0, state.lastRun, SkillContext.of(ctx, skills),
                state, store.settings(), conns, try { app.heyrobot.sys.TermuxStore.status(ctx).scripts } catch (_: Throwable) { emptyList() },
                conns.associate { it.id to app.heyrobot.sys.ConnKeyFiles.masks(ctx, it.id) })
        }
    }
}

/** What the Skills pages can do. */
class SkillActions(
    /** A Skills page by route ("skills/<id>"). */
    val open: (String) -> Unit = {},
    /** Writes a skill (SkillEdit.saved has made its rev and provenance); false when it couldn't be written. */
    val save: (Skill) -> Boolean = { true },
    val delete: (String) -> Unit = {},
    /** The skills file can't be read: keep it aside and start over. */
    val startOver: () -> Unit = {},
    /** Phase B: "Let Robot's model pick skills". */
    val setModelPicks: (Boolean) -> Unit = {},
)

object SkillRoutes {
    fun skill(id: String) = "skills/$id" // l10n: not text (a route)
}

/** The Settings row's state: "4 skills", "None yet", "1 skill couldn't be read". */
@Composable
fun skillsSummary(ctx: android.content.Context): String {
    val file = remember { SkillFiles.store(ctx).file() }
    return when (file) {
        is SkillFile.Ok -> when {
            file.dropped > 0 -> SkillStrings.dropped(file.dropped).str()
            file.skills.isEmpty() -> stringResource(R.string.skill_none_yet)
            else -> pluralStringResource(R.plurals.skills_n, file.skills.size, file.skills.size)
        }
        is SkillFile.TooNew -> SkillStrings.newer().str()
        SkillFile.Damaged -> SkillStrings.damaged().str()
    }
}

/** Settings › Skills and each skill's page. */
@Composable
fun SkillsRoute(route: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    // C9: Settings › Skills › Connections and its pages.
    if (route == ConnRoutes.LIST || route.startsWith(ConnRoutes.LIST + "/")) return ConnectionsRoute(route, a, snackbar, onBack)
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val data = remember(tick) { SkillsData.read(ctx) }
    val store = remember { SkillFiles.store(ctx) }
    fun changed() { SkillFiles.changed(ctx); tick++ }
    val act = SkillActions(
        open = { a.openPage(Page.Settings(it)) },
        save = { s -> store.save(s).also { changed() } },
        delete = { id -> store.delete(id); changed() },
        startOver = { store.reset(); changed() },
        setModelPicks = { on -> store.saveSettings(store.settings().copy(modelPicks = on)); changed() },
    )
    if (route == SettingsRoutes.SKILLS) return SkillsScreen(data, act, snackbar, onBack)
    // C9: a step's page.
    SkillStepRoutes.of(route)?.let { (id, i) ->
        val k = data.skills.firstOrNull { it.id == id }
        if (k == null || i !in k.steps.indices) LaunchedEffect(route) { onBack() } else SkillStepScreen(k, i, data, act, snackbar, onBack)
        return
    }
    val s = data.skills.firstOrNull { it.id == route.removePrefix(SettingsRoutes.SKILLS + "/") }
    if (s == null) LaunchedEffect(route) { onBack() } else SkillPageScreen(s, data, act, snackbar, onBack)
}

@Composable
fun SkillsScreen(d: SkillsData, act: SkillActions, snackbar: SnackbarHostState, onBack: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    var startOver by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf<String?>(null) }
    val langs = LocalLangs.current
    val tYours = stringResource(R.string.skill_yours)
    val footer = stringResource(R.string.skill_footer)
    PreferenceScreen(UiStrings.skills().str(), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.skill_intro)) }
        if (d.problem != null || d.dropped > 0) {
            preferenceGroup(null, "warnings") {
                when (d.problem) {
                    SkillFile.Damaged -> StatusPreference(stringResource(R.string.skill_paused), SkillStrings.damaged().str(), Mark.WARN, action = {
                        TextButton(onClick = { startOver = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.skill_start_over)) }
                    })
                    is SkillFile.TooNew -> StatusPreference(stringResource(R.string.skill_paused), SkillStrings.newer().str(), Mark.WARN)
                    else -> StatusPreference(SkillStrings.dropped(d.dropped).str(), null, Mark.WARN)
                }
            }
        }
        preferenceGroup(tYours, "mine") {
            if (d.skills.isEmpty()) PreferenceItem(stringResource(R.string.skill_none_yet), null, RIcons.Robot)
            for (s in d.skills) PreferenceItem(s.name, SkillCards.row(s, d.runs[s.id], now).str(), RIcons.Robot, onClick = { act.open(SkillRoutes.skill(s.id)) })
            // C9 (plan §6.1): a name, then the first step from Add a step's sheet.
            if (d.problem == null && d.skills.size < app.heyrobot.core.skills.SkillCheck.MAX_SKILLS)
                PreferenceItem(stringResource(R.string.conn_add_skill), stringResource(R.string.conn_add_skill_summary), RIcons.Add, onClick = { naming = true })
        }
        // C9 (plan §6.1): your connections, and how many skills need attention.
        preferenceGroup(null, "connections") {
            val attention = d.skills.count { app.heyrobot.core.skills.SkillCheck.attention(it, d.links).isNotEmpty() }
            PreferenceItem(app.heyrobot.core.i18n.ConnStrings.title().str(), app.heyrobot.core.connect.ConnSetup.summary(d.connections, attention).str(), RIcons.Settings,
                onClick = { act.open(ConnRoutes.LIST) })
        }
        preferenceGroup(null, "model") {
            SwitchPreference(stringResource(R.string.skill_model_picks), stringResource(R.string.skill_model_picks_summary), d.settings.modelPicks) {
                act.setModelPicks(it)
            }
        }
        preferenceFooter(footer)
    }
    if (startOver) ConfirmDialog(stringResource(R.string.skill_start_over_title), stringResource(R.string.skill_start_over_text),
        stringResource(R.string.skill_start_over), onDismiss = { startOver = false }) { startOver = false; act.startOver() }
    if (naming) EditDialog(stringResource(R.string.conn_add_skill), "", stringResource(R.string.skill_name), stringResource(R.string.skill_name_supporting),
        validate = { n -> if (n.isBlank()) null else SkillNames.check(n.trim(), null, d.names, now)?.render(langs) }, canSave = { it.isNotBlank() },
        onDismiss = { naming = false }) { n -> naming = false; newName = n }
    newName?.let { n ->
        StepOffersDialog(app.heyrobot.core.connect.SkillEditor.offers(d.connections, d.scripts), onDismiss = { newName = null }, onConnections = { act.open(ConnRoutes.LIST) }) { o, said ->
            newName = null
            val k = app.heyrobot.core.connect.SkillEditor.newSkill(app.heyrobot.core.skills.SkillIds.make(), n, app.heyrobot.core.connect.SkillEditor.step(o, said),
                System.currentTimeMillis(), langs.ui)
            if (app.heyrobot.core.skills.SkillCheck.valid(k) && act.save(k)) act.open(SkillRoutes.skill(k.id))
        }
    }
}

/** A skill's page: every change is saved at once (SkillEdit.saved), checked first. */
@Composable
fun SkillPageScreen(s: Skill, d: SkillsData, act: SkillActions, snackbar: SnackbarHostState, onBack: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    val langs = LocalLangs.current
    var deleting by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var addingStep by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }
    val links = d.links
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun edit(new: Skill) {
        val problem = SkillCheck.problems(new).firstOrNull()
        if (problem != null) { scope.launch { snackbar.showSnackbar(problem.render(langs)) }; return }
        act.save(SkillEdit.saved(s, new, System.currentTimeMillis()))
    }
    fun nameWhy(n: String, other: Boolean): String? = SkillNames.check(n, s.id, d.names, now, other)?.render(langs)
    val tOther = stringResource(R.string.skill_other_names)
    val tQuestions = stringResource(R.string.skill_questions)
    val tSteps = stringResource(R.string.skill_steps)
    val tDoes = stringResource(R.string.conn_skill_does)
    PreferenceScreen(s.name, onBack, snackbar = snackbar) {
        val shadows = SkillNames.shadows(s, d.names)
        val attention = SkillCheck.attention(s, links)
        if (shadows.isNotEmpty() || s.provenance.fromContent || attention.isNotEmpty()) {
            preferenceGroup(null, "notes") {
                for (w in shadows) StatusPreference(w.str(), null, Mark.TODO)
                // C9: a step whose connection, app, script or routing server is gone or no longer fits.
                for (a in attention) StatusPreference(app.heyrobot.core.connect.ConnSetup.attention(a.step, a.why).str(), null, Mark.WARN,
                    onClick = { act.open(SkillStepRoutes.step(s.id, a.step)) })
                if (s.provenance.fromContent) StatusPreference(SkillStrings.contentCheck().str(), null, Mark.WARN, action = {
                    TextButton(onClick = { act.save(SkillEdit.mine(s)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.skill_mine)) }
                })
            }
        }
        preferenceGroup(null, "name") {
            TextPreference(stringResource(R.string.skill_name), s.name, "", stringResource(R.string.skill_name), stringResource(R.string.skill_name_supporting),
                validate = { nameWhy(it.trim(), other = false) }) { edit(SkillEdit.rename(s, it)) }
            ListPreference(stringResource(R.string.skill_level), s.level, SkillEdit.levels(s), { SkillCards.level(it).render(langs) }) { edit(s.copy(level = it)) }
        }
        preferenceGroup(tOther, "names") {
            for ((lang, list) in listOf(Lang.EN to s.namesEn, Lang.FR to s.namesFr)) {
                val title = stringResource(if (lang == Lang.EN) R.string.skill_in_english else R.string.skill_in_french)
                for (n in list) PreferenceItem(n, title, trailing = {
                    IconButton(onClick = { edit(SkillEdit.removeName(s, n, lang)) }) { Icon(RIcons.Delete, stringResource(R.string.skill_remove_name, n)) }
                })
                if (list.size < SkillCheck.MAX_OTHER_NAMES) PreferenceItem(stringResource(R.string.skill_add_name), title, RIcons.Add, onClick = { adding = lang.name })
            }
        }
        if (s.params.isNotEmpty()) {
            preferenceGroup(tQuestions, "questions") {
                for (p in s.params) {
                    val ask = if (langs.ui == Lang.FR) p.askFr else p.askEn
                    TextPreference(stringResource(R.string.skill_in_english), p.askEn, "", ask, "", validate = { if (it.isBlank() || it.length > SkillCheck.MAX_ASK) "" else null }) {
                        edit(SkillEdit.question(s, p.name, askEn = it))
                    }
                    TextPreference(stringResource(R.string.skill_in_french), p.askFr, "", ask, "", validate = { if (it.isBlank() || it.length > SkillCheck.MAX_ASK) "" else null }) {
                        edit(SkillEdit.question(s, p.name, askFr = it))
                    }
                    val shown = SkillStrings.valueChip("value" to Slot.valueArg(p.type, p.default), "state" to SkillStrings.asked()).str()
                    val hint = when (p.type) {
                        ParamType.DURATION -> stringResource(R.string.skill_default_minutes)
                        ParamType.TIME -> stringResource(R.string.skill_default_time)
                        else -> ""
                    }
                    val invalid = stringResource(R.string.skill_invalid_default)
                    TextPreference(stringResource(R.string.skill_default), SkillEdit.editableDefault(p.type, p.default), shown, ask, hint,
                        validate = { if (SkillEdit.typedDefault(p.type, it) == null) invalid else null }) { typed ->
                        SkillEdit.typedDefault(p.type, typed)?.let { edit(SkillEdit.question(s, p.name, default = it)) }
                    }
                    PreferenceItem(stringResource(R.string.skill_remove), stringResource(R.string.skill_remove_question, ask), RIcons.Delete,
                        onClick = { edit(SkillEdit.removeQuestion(s, p.name)) })
                }
            }
        }
        preferenceGroup(tSteps, "steps") {
            val lines = SkillCards.steps(s, links = links)
            s.steps.forEachIndexed { i, st ->
                // C9: a step opens its page (its values, Only if…, If it doesn't work).
                PreferenceItem(lines[i].str().replace("*", ""), st.cond?.let { app.heyrobot.core.connect.SkillEditor.condText(it).str() }, trailing = {
                    Row {
                        IconButton(onClick = { act.open(SkillStepRoutes.step(s.id, i)) }) { Icon(RIcons.Chevron, stringResource(R.string.conn_open_step, i + 1)) }
                        if (i > 0) IconButton(onClick = { edit(SkillEdit.move(s, i, -1)) }) {
                            Icon(RIcons.Back, stringResource(R.string.skill_move_up, i + 1), Modifier.rotate(90f))
                        }
                        if (i < s.steps.size - 1) IconButton(onClick = { edit(SkillEdit.move(s, i, 1)) }) {
                            Icon(RIcons.Back, stringResource(R.string.skill_move_down, i + 1), Modifier.rotate(-90f))
                        }
                        if (s.steps.size > 1) IconButton(onClick = { edit(SkillEdit.removeStep(s, i)) }) {
                            Icon(RIcons.Delete, stringResource(R.string.skill_remove_step, i + 1))
                        }
                    }
                })
            }
            if (s.steps.size < SkillCheck.MAX_STEPS) PreferenceItem(stringResource(R.string.conn_add_step), null, RIcons.Add, onClick = { addingStep = true })
        }
        // C9 (plan §6.4): computed, read-only.
        preferenceGroup(tDoes, "does") {
            val chips = app.heyrobot.core.connect.SkillEditor.chips(app.heyrobot.core.connect.SkillEditor.effects(s, links))
            PreferenceItem(stringResource(R.string.conn_effects), if (chips.isEmpty()) stringResource(R.string.conn_effects_none)
                else app.heyrobot.core.i18n.Phrase.ListOf(chips).str())
            PreferenceItem(stringResource(R.string.conn_risk), app.heyrobot.core.connect.SkillEditor.risk(s, links).str())
            PreferenceItem(app.heyrobot.core.connect.SkillEditor.model(s).str(), null)
            PreferenceItem(stringResource(R.string.conn_test), stringResource(R.string.conn_test_skill_summary), RIcons.Refresh, onClick = { testing = true })
        }
        preferenceGroup(null, "made") {
            PreferenceItem(stringResource(R.string.skill_made), SkillCards.made(s, ZoneId.systemDefault()).str(), RIcons.History)
            PreferenceItem(stringResource(R.string.skill_last_run), SkillCards.lastRun(d.runs[s.id], now).str(), RIcons.Robot)
            // Phase B: "On Fast: 9 of the last 10 right", once a model has 5 samples.
            val fills = SkillStats.lines(d.state, s)
            if (fills.isNotEmpty()) PreferenceItem(stringResource(R.string.skill_fills), fills.joinToString("\n") { it.render(langs) }, RIcons.Robot)
            PreferenceItem(stringResource(R.string.skill_delete), null, RIcons.Delete, onClick = { deleting = true })
        }
    }
    adding?.let { which ->
        val lang = if (which == Lang.FR.name) Lang.FR else Lang.EN
        EditDialog(stringResource(R.string.skill_add_name), "", stringResource(if (lang == Lang.EN) R.string.skill_in_english else R.string.skill_in_french),
            stringResource(R.string.skill_name_words), validate = { if (it.isBlank()) null else nameWhy(it.trim(), other = true) }, canSave = { it.isNotBlank() },
            onDismiss = { adding = null }) { n ->
            adding = null
            edit(SkillEdit.addName(s, n, lang))
        }
    }
    if (addingStep) StepOffersDialog(app.heyrobot.core.connect.SkillEditor.offers(d.connections, d.scripts), onDismiss = { addingStep = false },
        onConnections = { act.open(ConnRoutes.LIST) }) { o, said ->
        addingStep = false
        app.heyrobot.core.connect.SkillEditor.add(s, app.heyrobot.core.connect.SkillEditor.step(o, said))?.let { edit(it); act.open(SkillStepRoutes.step(s.id, s.steps.size)) }
    }
    if (testing) {
        app.heyrobot.ui.common.SecureWindow()
        val lines = app.heyrobot.core.connect.SkillEditor.dryRun(s, d.connections, links) { d.masks[it].orEmpty() }
        app.heyrobot.ui.common.AlertDialog(onDismissRequest = { testing = false }, properties = app.heyrobot.ui.common.SecureDialog,
            title = { Text(stringResource(R.string.conn_test_skill_title)) },
            text = {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.conn_test_skill_intro))
                    for (l in lines) Text(l.str())
                }
            },
            confirmButton = { TextButton(onClick = { testing = false }) { Text(UiStrings.done().str()) } })
    }
    if (deleting) ConfirmDialog(stringResource(R.string.skill_delete_title, s.name), stringResource(R.string.skill_delete_text), UiStrings.delete().str(),
        onDismiss = { deleting = false }) {
        deleting = false
        act.delete(s.id)
        onBack()
    }
}
