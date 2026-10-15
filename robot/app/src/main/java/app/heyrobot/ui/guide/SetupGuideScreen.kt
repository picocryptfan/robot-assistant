// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.guide

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.GuideStep
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.HomeDialog
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceGroup
import app.heyrobot.ui.pref.settingsBackground
import app.heyrobot.ui.settings.BriefCardDialog
import app.heyrobot.ui.theme.RobotTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS (1.0). The setup guide: a full-screen page per feature (core GuideStep), "Step n of N" with a progress bar,
// Back, Skip this step and Next at the bottom, Finish later at the top. Each feature's page says what it does, what stays
// on the phone and what (if anything) goes online, offers the choice with its current value (the default on a new
// install), and where Settings has it. A choice is written at once, through the store its Settings page uses; skipping
// changes nothing. Where you are is kept in setup_guide.json, so Finish later (or leaving the app) resumes there. Every
// word is in res/values/strings.xml; names of values (a voice, an app) are core's, as Settings shows them.

/** What a page needs: the app's settings and facts, the other files, and the actions that leave the page. */
internal class GuideCtx(
    val s: RobotSettings,
    val snap: Snapshot,
    val update: (RobotSettings) -> Unit,
    val a: AppActions,
    val files: GuideFiles,
    val stores: GuideStores,
    /** A file changed through [stores]: read them again. */
    val changed: () -> Unit,
    val editHome: () -> Unit,
    /** The morning brief's card (what it says and what goes online), before it is turned on. */
    val askBrief: () -> Unit,
    /** "Use memory on the lock screen": the key first, then the switch (as Settings › Memory). */
    val memoryLock: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideScreen(
    s: RobotSettings,
    snap: Snapshot,
    update: (RobotSettings) -> Unit,
    a: AppActions,
    stores: GuideStores,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    var guide by remember { mutableStateOf(stores.guide()) }
    val step = guide.at ?: GuideStep.WELCOME
    var tick by remember { mutableIntStateOf(0) }
    val files = remember(tick) { stores.files() }
    val scope = rememberCoroutineScope()
    var editingHome by rememberSaveable { mutableStateOf(false) }
    var briefCard by rememberSaveable { mutableStateOf(false) }
    val keyProblem = stringResource(R.string.guide_memory_key_problem)

    fun save(n: GuideState) {
        guide = n
        stores.saveGuide(n)
    }
    fun go(to: GuideStep) = save(SetupGuide.moved(guide, step, to))
    fun back() { step.previous?.let(::go) ?: onClose() }
    fun next() { step.next?.let(::go) ?: run { save(SetupGuide.finish(guide)); onClose() } }
    fun skip() = save(SetupGuide.skip(guide, step))
    BackHandler { back() }

    val g = GuideCtx(
        s, snap, update, a, files, stores, changed = { tick++ }, editHome = { editingHome = true }, askBrief = { briefCard = true },
        memoryLock = { on ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { stores.rekeyMemory(on) }
                if (ok) { stores.memory(stores.files().memory.copy(onLockScreen = on)); tick++ } else a.say(keyProblem)
            }
        },
    )
    Scaffold(
        containerColor = settingsBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guide_title)) },
                actions = { TextButton(onClick = onClose) { Text(stringResource(R.string.guide_finish_later)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsBackground()),
            )
        },
        bottomBar = { GuideButtons(step, onBack = ::back, onSkip = ::skip, onNext = ::next) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        // Each page opens at its top.
        val list = remember(step) { LazyListState() }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            state = list,
            contentPadding = PaddingValues(start = RobotTokens.pagePadding, end = RobotTokens.pagePadding, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "step") { StepHeader(step) }
            guidePage(step, g)
        }
    }
    if (editingHome) HomeDialog(s, update, a, onClose = { editingHome = false })
    if (briefCard) {
        BriefCardDialog(files.routines, onDismiss = { briefCard = false }) {
            briefCard = false
            stores.routines(files.routines.copy(briefEnabled = true, briefOnline = true))
            tick++
        }
    }
}

/** "Step 3 of 17" over the progress bar, one TalkBack stop. */
@Composable
private fun StepHeader(step: GuideStep) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = RobotTokens.prefPaddingH, vertical = 4.dp)) {
        Text(stringResource(R.string.guide_step, step.number, SetupGuide.COUNT), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        LinearProgressIndicator(progress = { step.number.toFloat() / SetupGuide.COUNT }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** Back, Skip this step and Next (Done on the summary); they wrap onto two lines when the text is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideButtons(step: GuideStep, onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    Surface(color = settingsBackground(), tonalElevation = 2.dp) {
        FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (step.previous != null) OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.guide_back)) }
            if (step.next != null) TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.guide_skip)) }
            Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (step.next == null) R.string.guide_done else R.string.guide_next))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------- a page's parts

/** The page's title, a heading for TalkBack. */
internal fun LazyListScope.title(@StringRes id: Int) = item {
    Text(stringResource(id), style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = RobotTokens.prefPaddingH).semantics { heading() })
}

/** A paragraph of the page ([args] for its placeholders). */
internal fun LazyListScope.para(@StringRes id: Int, vararg args: Any) = item {
    Text(stringResource(id, *args), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = RobotTokens.prefPaddingH))
}

/** What stays on the phone and what goes online, under a shield. */
internal fun LazyListScope.privacy(@StringRes id: Int) = item {
    Surface(shape = RoundedCornerShape(RobotTokens.groupCorner), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(16.dp)) {
            Icon(RIcons.Shield, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.guide_privacy), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** "Find it later: Settings › …". */
internal fun LazyListScope.findIt(@StringRes path: Int) = item {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = RobotTokens.prefPaddingH, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(RIcons.Settings, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.guide_find_it, stringResource(path)), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The page's choices, as Settings' rows in a group under [title]. */
internal fun LazyListScope.group(@StringRes title: Int?, content: @Composable ColumnScope.() -> Unit) = item {
    PreferenceGroup(title?.let { stringResource(it) }, content = content)
}
