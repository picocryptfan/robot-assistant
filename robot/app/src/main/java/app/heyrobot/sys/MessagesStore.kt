package app.heyrobot.sys

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.RLog
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Settings › Messages (core MessagesFile) in files/robot/messages.json, not in settings.json (which keeps its schema):
 * [MAIN] writes it; the main process's notification listener and MessagesBridge read [current] (the same process, so a
 * change applies at once), and the voice process reads it again each time the panel opens (RobotSession.onShow through
 * PanelMessages), as look.json. Switches and People ids only, never a message. [listeners] hear a change in this process.
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

    /** [MAIN] Applies [f] at once in this process (the listener drops what isn't allowed any more) and writes it. */
    fun save(ctx: Context, f: MessagesFile) {
        state.value = f
        loaded = true
        val file = file(ctx)
        writer.execute { write(file, f) }
        for (l in listeners) try { l(f) } catch (t: Throwable) { RLog.w("messages_settings_listener", t) }
    }

    /** [l] hears every [save] in this process (the main process's MessageTap). */
    fun listen(l: (MessagesFile) -> Unit) { listeners += l }

    /** Waits for the saves asked so far (tests). */
    fun awaitWrites() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }

    internal fun read(f: File): MessagesFile = MessagesFile.fromJson(readOrNull(f))

    internal fun write(f: File, m: MessagesFile) {
        try { writeAtomic(f, m.toJson()) } catch (t: Throwable) { RLog.w("messages_settings_save", t) }
    }
}
