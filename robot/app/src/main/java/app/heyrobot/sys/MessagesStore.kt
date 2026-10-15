// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.RLog
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.readOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Settings › Messages (core MessagesFile) in files/robot/messages.json, not in settings.json (which keeps its schema):
 * [MAIN] writes it; the main process's notification listener and MessagesBridge read [current] (the same process, so a
 * change applies at once), and the voice process reads it again each time the panel opens (RobotSession.onShow through
 * PanelMessages), as look.json. Switches, People ids and the apps' signing certificates, never a message. Every write
 * ([update]) is one change, applied here at once and then to the file under messages.lock, read from it and written whole
 * (core MessagesFile.update), so Settings, People's switch, the bridge's "Always for …" and MessageTap's first-use pin
 * never lose each other's changes. [listeners] hear a change in this process.
 */
object MessagesStore {
    val state: MutableState<MessagesFile> = mutableStateOf(MessagesFile())

    @Volatile private var loaded = false
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MessagesFile) -> Unit>()

    /** One writer thread, so two quick taps land on disk in order (as SettingsStore). */
    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-messages-settings").apply { isDaemon = true } } }

    fun file(ctx: Context): File = File(Paths.dir(ctx), MessagesFile.FILE)

    /** What this process holds, read from the file the first time it asks. */
    fun current(ctx: Context): MessagesFile {
        if (!loaded) reload(ctx)
        return state.value
    }

    /** Reads messages.json again (a missing or damaged file gives the defaults: everything off). */
    fun reload(ctx: Context): MessagesFile = read(file(ctx)).also { state.value = it; loaded = true }

    /** Writes on their way to the file (the writer thread's queue). */
    private var pending = 0

    /**
     * [MAIN] One change: applied at once in this process (the page shows it, the listener drops what isn't allowed any
     * more), then on the writer thread to the file as it is on disk, under its lock. When nothing else is on its way and the
     * file held something this process hadn't (another writer's change), this process takes the file's version.
     */
    fun update(ctx: Context, change: (MessagesFile) -> MessagesFile) {
        val file = file(ctx)
        val f = synchronized(this) {
            if (!loaded) reload(ctx)
            val next = change(state.value)
            if (next == state.value && file.exists()) return
            state.value = next
            pending++
            next
        }
        heard(f)
        writer.execute {
            val disk = try { MessagesFile.update(file, change) } catch (t: Throwable) { RLog.w("messages_settings_save", t); null }
            val took = synchronized(this) {
                pending--
                if (pending == 0 && disk != null && disk != state.value) { state.value = disk; true } else false
            }
            if (took) heard(disk!!)
        }
    }

    /** Everything replaced by [f] (a test's starting point; Settings and the bridge use [update]). */
    fun save(ctx: Context, f: MessagesFile) = update(ctx) { f }

    private fun heard(f: MessagesFile) {
        for (l in listeners) try { l(f) } catch (t: Throwable) { RLog.w("messages_settings_listener", t) }
    }

    /** [l] hears every [save] in this process (the main process's MessageTap). */
    fun listen(l: (MessagesFile) -> Unit) { listeners += l }

    /** Waits for the saves asked so far (tests). */
    fun awaitWrites() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }

    internal fun read(f: File): MessagesFile = MessagesFile.fromJson(readOrNull(f))
}
