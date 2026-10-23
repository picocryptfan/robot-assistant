// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.FitOffer
import app.heyrobot.core.PhoneFit
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.StatusIcon
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.prefTitleStyle
import app.heyrobot.ui.theme.str

// MAIN PROCESS. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 8): the setup guide's warning on a slow or small
// phone (core PhoneFit), on its phone step and again in its summary. What will be slow and how long answers take, what Robot
// does about it, the memory; then a row for each change it offers, its button doing just that: "Use the GPU" back on,
// "Translate transcripts" off, a helper set up. It never holds the guide up, and shows nothing where the phone doesn't need it.

/** The warning as a group of the page, when this phone gets one. */
internal fun LazyListScope.fitWarning(g: GuideCtx) {
    val fit = g.snap.fit(g.s.modelGpu, g.s.convTranslate, helpersSetUp(g))
    if (!fit.shows) return
    group(null) {
        FitBlock(fit)
        if (FitOffer.USE_GPU in fit.offers) FitRow(R.string.guide_fit_gpu, R.string.guide_fit_gpu_detail, R.string.guide_fit_gpu_button,
            R.string.guide_fit_gpu_label) { g.update(g.s.copy(modelGpu = true)) }
        if (FitOffer.TRANSLATE_OFF in fit.offers) FitRow(R.string.guide_fit_translate, R.string.guide_fit_translate_detail,
            R.string.guide_fit_translate_button, R.string.guide_fit_translate_label) { g.update(g.s.copy(convTranslate = false)) }
        if (FitOffer.HELPER in fit.offers) FitRow(R.string.guide_fit_helper, R.string.guide_fit_helper_detail, R.string.guide_fit_helper_button,
            R.string.guide_fit_helper_label) { g.a.openPage(Page.Helpers) }
    }
}

/**
 * A change the warning offers: what it is and what it does, and its button. At large text (one and a half times or more) the
 * button goes under the words, so neither is squeezed to a word a line.
 */
@Composable
private fun FitRow(@StringRes title: Int, @StringRes detail: Int, @StringRes button: Int, @StringRes label: Int, onClick: () -> Unit) {
    if (LocalDensity.current.fontScale < 1.5f) {
        StatusPreference(stringResource(title), stringResource(detail), Mark.TODO, action = { RowButton(button, label, onClick) })
        return
    }
    PreferenceBlock {
        Row(Modifier.semantics(mergeDescendants = true) {}) {
            StatusIcon(Mark.TODO)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(title), style = prefTitleStyle())
                Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { RowButton(button, label, onClick) }
    }
}

/** The warning's words, one TalkBack stop: its title, then each sentence in the main ink. */
@Composable
private fun FitBlock(fit: PhoneFit) {
    PreferenceBlock(Modifier.semantics(mergeDescendants = true) {}) {
        Row {
            StatusIcon(Mark.WARN)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(fit.titleText.str(), style = prefTitleStyle())
                for (l in fit.lines) Text(l.str(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
