package app.heyrobot.update

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.LicenceBook
import app.heyrobot.core.RLog
import app.heyrobot.core.SecretRead
import app.heyrobot.core.readOrNull
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.GitHubToken
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateState
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.KeystoreSecret
import app.heyrobot.sys.Paths
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// MAIN PROCESS. The updater's files, beside settings.json but not in it (core UpdateFiles.kt): files/robot/updates.json (your
// choices in Settings › Updates) and update_state.json (what the updater last found and did), and the downloads in
// files/updates/. Only the main process reads or writes any of them; the voice and model processes never touch the updater.

object UpdateStore {
    /** What this process shows now; Compose reads them. */
    val settings: MutableState<UpdateSettings> = mutableStateOf(UpdateSettings())
    val state: MutableState<UpdateState> = mutableStateOf(UpdateState())

    @Volatile private var loaded = false

    /** One writer thread for the settings, so two quick taps land on disk in order (as LookStore). */
    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-updates").apply { isDaemon = true } } }

    fun settingsFile(ctx: Context): File = File(Paths.dir(ctx), UpdateSettings.FILE)
    fun stateFile(ctx: Context): File = File(Paths.dir(ctx), UpdateState.FILE)

    /** The downloads: app-private, never shared, emptied after an update. */
    fun downloads(ctx: Context): File = File(ctx.filesDir, app.heyrobot.core.update.UpdateFilesNames.DIR).apply { mkdirs() }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (loaded) return
        settings.value = UpdateSettings.fromJson(readOrNull(settingsFile(ctx)))
        state.value = UpdateState.fromJson(readOrNull(stateFile(ctx)))
        loaded = true
    }

    fun settings(ctx: Context): UpdateSettings = ensure(ctx).let { settings.value }
    fun state(ctx: Context): UpdateState = ensure(ctx).let { state.value }

    /** Shows [s] at once and writes it on the writer thread. */
    fun saveSettings(ctx: Context, s: UpdateSettings) {
        ensure(ctx)
        settings.value = s
        val f = settingsFile(ctx)
        writer.execute { try { writeAtomic(f, s.toJson()) } catch (t: Throwable) { RLog.w("updates_save", t) } }
    }

    /** Changes the state and writes it (background thread; small file). */
    @Synchronized
    fun update(ctx: Context, change: (UpdateState) -> UpdateState): UpdateState {
        ensure(ctx)
        val s = change(state.value)
        state.value = s
        try { writeAtomic(stateFile(ctx), s.toJson()) } catch (t: Throwable) { RLog.w("update_state_save", t) }
        return s
    }

    /** Waits for the settings saves asked so far (tests). */
    fun awaitWrites() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }

    /** Reads both files again (tests). */
    @Synchronized
    fun reload(ctx: Context) {
        loaded = false
        ensure(ctx)
    }

    @Volatile private var defaultRepo: GitHubRepo? = null

    /**
     * The repository Settings › About › Licences names as Robot's source (components.json's robot.source): the one place it is
     * written, so a renamed repository changes in that file only. Null if the list can't be read.
     */
    fun defaultRepo(ctx: Context): GitHubRepo? = defaultRepo ?: try {
        val json = ctx.assets.open(LicenceBook.FILE).use { it.readBytes().toString(Charsets.UTF_8) }
        GitHubRepo.fromSourceLink(LicenceBook.parse(json).robot.source).also { defaultRepo = it }
    } catch (t: Throwable) {
        RLog.w("update_default_repo", t)
        null
    }

    /** The repository to ask: yours (Advanced), else the default. */
    fun repo(ctx: Context): GitHubRepo? = settings(ctx).repoOr(defaultRepo(ctx))
}

/**
 * The access token for a private repository (Settings › Updates › Advanced): encrypted exactly as a helper's API key is
 * (sys/KeystoreSecret, AES-GCM with its own non-exportable Android Keystore key), entered in a window that can't be
 * captured, decrypted only by GitHubClient just before a request to api.github.com, and sent nowhere else.
 */
object UpdateToken {
    const val ALIAS = "robot_github_token"
    private val store = KeystoreSecret(ALIAS, "github_token", "update_token")

    fun plausible(t: String): Boolean = GitHubToken.plausible(t)

    /** Background thread. False when it isn't a token or couldn't be saved. */
    fun save(ctx: Context, token: String): Boolean = plausible(token) && store.save(ctx, token, GitHubToken.mask(token))

    /**
     * The token ([SecretRead.Present]), or none saved. Only GitHubClient calls this (ProcessIsolationTest), right before its
     * requests. One that can never be decrypted again ([SecretRead.Invalidated]) is removed as it is read, and Settings says
     * so; one that couldn't be read just now ([SecretRead.Unavailable]) is kept, and the next request reads it again.
     */
    fun read(ctx: Context): SecretRead = store.read(ctx).also {
        if (it is SecretRead.Invalidated) RLog.i("update_token", "op" to RLog.Code("unreadable"))
    }

    /** The token was just pasted and saved: the clipboard is cleared if it still holds it. */
    fun clearClipboard(ctx: Context, token: String) = store.clearClipboard(ctx, token)

    fun saved(ctx: Context): Boolean = store.saved(ctx)
    fun masked(ctx: Context): String? = store.masked(ctx)
    fun unreadable(ctx: Context): Boolean = store.unreadable(ctx)

    /** "Remove" (background thread): the files and the Keystore key go. */
    fun delete(ctx: Context) = store.delete(ctx)
}
