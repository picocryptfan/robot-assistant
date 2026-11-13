// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * fix-seal-lows-d 2: Today's quick add outside any page. Rotating the phone mid-save rebuilds the page and puts its words
 * back; held here, the save still ends, its words clear only when saved and unchanged, and the box adds once per tap.
 */
internal object TodayQuickAdd {
    /** [words] saving now while [saved] is null; saved or not, kept until a page has seen it. */
    class Sent(val words: String, val saved: Boolean? = null)

    val sent = mutableStateOf<Sent?>(null)
    /** One more each time a save ends, so the open page reads its note again. */
    val landed = mutableIntStateOf(0)
    /** Never cancelled: the save and its done outlive the page and the activity. quickAdd catches everything, so done always runs. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * fix-seal-lows-e 1: the box's add, saving in [scope] and never in the page's own: a page scope is cancelled by a
     * rotation mid-save, done never runs, and Add stays dead. [save] runs off the main thread, then done, then [landed].
     */
    fun adder(save: (String) -> Boolean): (String, (Boolean) -> Unit) -> Unit = { text, done ->
        scope.launch { val saved = withContext(Dispatchers.IO) { save(text) }; done(saved); landed.intValue++ }
    }
}
