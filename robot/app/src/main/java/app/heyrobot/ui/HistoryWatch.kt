// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.heyrobot.core.StoredConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

// MAIN PROCESS. 0.7.0 (the tester's 0.6.0 report): History shown in the app (the History tab, Home's last three, a
// conversation's page) follows what is on disk. The panel saves conversations from the voice process while the app stays
// open behind it, and pages open and close inside one screen, so neither "resumed" nor a page change says when History
// changed: the conversations folder's stamp (ConversationStore.stamp) is read once a second while the app is in front,
// and the list is read again when it changes, as well as on resume and after a delete here.

/** [stamp] read every [everyMs] off the main thread: emits each time it differs from the last value seen (never the first). */
fun stampChanges(stamp: () -> Long, everyMs: Long = 1_000L): Flow<Long> = flow {
    var last = stamp()
    while (true) {
        delay(everyMs)
        val s = stamp()
        if (s != last) {
            last = s
            emit(s)
        }
    }
}.flowOn(Dispatchers.IO)

/**
 * History as the app lists it, newest first: read with [load] while [active] (the app resumed), again when [tick] changes
 * (a delete here) and whenever [changes] says the folder changed (a save or a delete by either process).
 */
@Composable
fun rememberHistory(active: Boolean, tick: Int, changes: Flow<Long>, load: suspend () -> List<StoredConversation>): List<StoredConversation> {
    var list by remember { mutableStateOf(emptyList<StoredConversation>()) }
    LaunchedEffect(active, tick, changes) {
        if (!active) return@LaunchedEffect
        list = load()
        changes.collect { list = load() }
    }
    return list
}

/**
 * FX1 (files-1.4-review.md finding 3): History as the app shows it while Robot Files' travel mode hides drawers: FX3, a
 * conversation holding Robot Files' answer as placeholders, its title too (core TravelMode.history), in the menus'
 * language; the stored turns are unchanged and
 * show again when travel mode is off or your PIN shows the hidden drawers.
 */
object TravelHistory {
    /** Whether History shows the placeholders now. */
    fun hiding(ctx: android.content.Context): Boolean =
        app.heyrobot.core.files.TravelMode.hidesAny(app.heyrobot.sys.FilesSettingsStore.current(ctx), app.heyrobot.sys.TravelReveal.on())

    /** [list] as History shows it now. */
    fun shown(ctx: android.content.Context, list: List<StoredConversation>): List<StoredConversation> {
        if (!hiding(ctx)) return list
        val langs = app.heyrobot.sys.AppLanguage.langs(ctx)
        val answer = app.heyrobot.core.i18n.FilesStrings.travelAnswer().render(langs)
        val question = app.heyrobot.core.i18n.FilesStrings.travelQuestion().render(langs)
        return list.map { app.heyrobot.core.files.TravelMode.history(it, answer, question) }
    }

    /** The folder's stamp with travel mode's state in it: turning it on or off, or a reveal ending, reads History again. */
    fun stamp(ctx: android.content.Context, folder: Long): Long = folder * 2 + if (hiding(ctx)) 1 else 0
}
