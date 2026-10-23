// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.files.TravelMode
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileInCode
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.Intake
import app.heyrobot.core.files.IntakeCopy
import app.heyrobot.core.files.IntakeProblem
import app.heyrobot.core.files.ShareRefusal
import app.heyrobot.core.files.ShareRules
import app.heyrobot.core.files.SharedRef
import app.heyrobot.core.parse.FileType
import java.io.FileNotFoundException
import java.io.InputStream

// MAIN AND VOICE PROCESSES. Getting a file into Robot Files (files-1.4-plan.md §3, F5), for every way in: another app's
// share (main, ui/files/FilesShare.kt), a scan (main), a transcript (main) and the panel's "file this" (:voice). Each add is
// one copy through core IntakeCopy (the cap, the watchdog) into this process's vault (sys/FilesVault.kt: its lock gate),
// then a duplicate check by SHA-256, then the entry in Inbox, or a question when it is a duplicate. Nothing here decodes
// anything: bytes in, sealed bytes out; the type is sniffed from the first bytes.
//
// A shared file is read once, from another app's content provider, through ContentResolver with a CancellationSignal:
// asking its name and size, its type and opening it are each given IntakeLimits.OPEN_MS, and the copy the watchdog. Never
// file: (Robot would open a path with its own rights), never another user's provider, never one of Robot's own; never a
// persistable permission (the window's grant is all Robot uses, and it ends with the window). Logs carry codes only.

/** A file a share names, as its window shows it: every value is the sender's claim (cleaned for showing). */
class SharedFile(val uri: Uri, val name: String?, val size: Long?, val type: String?, val refusal: ShareRefusal?) {
    override fun toString(): String = "SharedFile($refusal)"  // l10n: not text (never the name or the URI)
}

/** Where shared bytes come from: the phone's ContentResolver, or a test's. Every call may block and may throw. */
interface SharedSource {
    /** The display name and size the sender claims (nulls when it says nothing); [signal] cancels the call. */
    fun claims(uri: Uri, signal: CancellationSignal): Pair<String?, Long?>

    /** The type the sender claims for [uri]. */
    fun type(uri: Uri): String?

    /** The bytes, once: a stream, and the length its descriptor declares (-1 when it doesn't). */
    fun open(uri: Uri, signal: CancellationSignal): Opened

    class Opened(val input: InputStream, val length: Long)
}

/** The phone's: ContentResolver. */
class ResolverSource(ctx: Context) : SharedSource {
    private val resolver = ctx.applicationContext.contentResolver

    override fun claims(uri: Uri, signal: CancellationSignal): Pair<String?, Long?> =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && c.getType(it) == Cursor.FIELD_TYPE_STRING }?.let(c::getString)
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && c.getType(it) == Cursor.FIELD_TYPE_INTEGER }?.let(c::getLong)
            n to s
        } ?: (null to null)

    override fun type(uri: Uri): String? = resolver.getType(uri)

    override fun open(uri: Uri, signal: CancellationSignal): SharedSource.Opened {
        val afd = resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw FileNotFoundException()
        // The stream reads only the descriptor's part (its declared length), and closing it closes the descriptor, which
        // also ends a read blocked on a pipe the sender stopped writing to.
        return SharedSource.Opened(afd.createInputStream(), afd.declaredLength)
    }
}

/** What one add came to. */
sealed class IntakeStep {
    /** In Inbox. [type]: sniffed from its bytes. */
    class Added(val entry: FileEntry, val type: FileType) : IntakeStep()

    /** The same bytes are in Robot Files already as [of]: nothing more happens until you answer ([FilesIntake.anyway] or [FilesIntake.drop]). */
    class Duplicate(val ready: Intake.Ready, val of: FileEntry, val intake: FileIntake) : IntakeStep()

    class Failed(val problem: IntakeProblem, val need: Long = 0) : IntakeStep()

    override fun toString(): String = when (this) {
        is Added -> "Added($type)"  // l10n: not text (codes for tests)
        is Duplicate -> "Duplicate"  // l10n: not text (codes for tests)
        is Failed -> "Failed($problem)"  // l10n: not text (codes for tests)
    }
}

/** Adds into [vault]; [source]: where shared files are read from. Every call blocks: never on the main thread. */
class FilesIntake(private val vault: FilesVault, private val source: SharedSource? = null, times: IntakeCopy.Times = IntakeCopy.Times(),
    /** F6: each file added, for the voice log (FILE_IN: how it came, whether it is dangerous; codes only). */
    private val log: ((FileInCode, Int) -> Unit)? = null,
    /** F12: whether a file is out of sight (travel mode): a copy of it isn't "already here" (that would name its drawer). */
    private val outOfSight: (FileEntry) -> Boolean = { false }) {
    private val copier = IntakeCopy({ input, declared -> vault.stage(input, declared) }, vault::discard, times)

    /**
     * What the sender claims of [uri] (its name, size and type, each asked with a deadline), for the window: a file Robot
     * won't take ([refusal]) is asked nothing. A claim that doesn't look like what it claims is left out.
     */
    fun describe(uri: Uri, refusal: ShareRefusal?): SharedFile {
        val src = source
        if (refusal != null || src == null) return SharedFile(uri, null, null, null, refusal)
        val signal = CancellationSignal()
        val claims = try {
            copier.within({ signal.cancel() }, {}) { src.claims(uri, signal) }
        } catch (t: Exception) {
            RLog.w("files_share_claims", t)
            null
        }
        val type = try {
            copier.within({}, {}) { src.type(uri) ?: "" }
        } catch (t: Exception) {
            RLog.w("files_share_type", t)
            null
        }
        val name = claims?.first?.let(app.heyrobot.core.files.FileIndexCodec::cleanName)?.takeIf { it.isNotEmpty() }
        return SharedFile(uri, name, ShareRules.claimedSize(claims?.second), ShareRules.claimedType(type), null)
    }

    /** Copies [f] in from its sender; [intake]: its provenance once its type is sniffed. Never commits a duplicate. */
    fun add(f: SharedFile, intake: (FileType) -> FileIntake): IntakeStep {
        if (f.refusal != null) return IntakeStep.Failed(IntakeProblem.NOT_ALLOWED)
        val src = source ?: return IntakeStep.Failed(IntakeProblem.UNREADABLE)
        val signal = CancellationSignal()
        val opened = try {
            copier.within({ signal.cancel() }, { it.input.close() }) { src.open(f.uri, signal) } ?: return IntakeStep.Failed(IntakeProblem.TIMED_OUT)
        } catch (t: Exception) {
            RLog.w("files_share_open", t)
            return IntakeStep.Failed(IntakeProblem.UNREADABLE)
        }
        val declared = opened.length.takeIf { it >= 0 } ?: f.size
        return settle(copier.copy(opened.input, declared) { signal.cancel() }, intake)
    }

    /** Bytes Robot made itself (a scan's PDF, the panel's picture, a transcript): the same copy and checks. */
    fun add(bytes: ByteArray, intake: (FileType) -> FileIntake): IntakeStep = settle(copier.copy(bytes), intake)

    /** A transcript you saved to Robot Files (§3, its menu): its Markdown export, named as an export is. */
    fun addTranscript(name: String, markdown: String): IntakeStep =
        add(markdown.toByteArray(Charsets.UTF_8)) { t -> FileIntake(name, app.heyrobot.core.files.FileSource.TRANSCRIPT, type = t.mime) }

    /** A duplicate you said to add anyway. */
    fun anyway(d: IntakeStep.Duplicate): IntakeStep = commit(d.ready, d.intake)

    /** A duplicate you didn't add: its staged copy goes. */
    fun drop(d: IntakeStep.Duplicate) = vault.discard(d.ready.staged)

    private fun settle(r: Intake, intake: (FileType) -> FileIntake): IntakeStep = when (r) {
        is Intake.Failed -> IntakeStep.Failed(r.problem, r.needed)
        is Intake.Ready -> {
            val i = intake(r.type)
            val dup = try { vault.duplicateOf(r.staged.sha256)?.takeIf { !outOfSight(it) } } catch (_: Exception) { null }
            if (dup != null) IntakeStep.Duplicate(r, dup, i) else commit(r, i)
        }
    }

    private fun commit(r: Intake.Ready, intake: FileIntake): IntakeStep = try {
        IntakeStep.Added(vault.commit(r.staged, intake), r.type).also { a ->
            log?.invoke(FileInCode.of(intake.source), if (a.entry.kind == app.heyrobot.core.files.FileKind.DANGEROUS) 1 else 0)
        }
    } catch (x: FileException) {
        // The commit discarded the staged file itself (FileStore.commit, or the vault's gate).
        IntakeStep.Failed(IntakeProblem.of(x.problem), x.needed)
    } catch (t: Exception) {
        RLog.w("files_commit", t)
        vault.discard(r.staged)
        IntakeStep.Failed(IntakeProblem.IO)
    }

    /** Whether Robot Files takes a file now: OPEN, or why not. */
    enum class Gate { OPEN, OFF, LOCKED, PHONE_LOCK }

    companion object {
        /** This process's: its vault and, for shares, ContentResolver. */
        fun phone(ctx: Context): FilesIntake = ctx.applicationContext.let { app ->
            // F7: in the main app, Robot Files is used from here on (its index read, what a crash cut short finished).
            RobotFiles.use(app)
            FilesIntake(RobotFiles.vault(app), ResolverSource(app), log = { how, dangerous -> logIn(app, how, dangerous) },
                outOfSight = { e -> TravelMode.hides(e, FilesSettingsStore.current(app), TravelReveal.on()) })
        }

        /** FILE_IN in this process's voice log (main's own file, or :voice's). Codes and counts only. */
        private fun logIn(ctx: Context, how: FileInCode, dangerous: Int) {
            try {
                if (!android.app.Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(ctx))
                VoiceLog.add(VoiceEvent.FILE_IN, how.id, 1, dangerous)
                VoiceLog.flush()
            } catch (t: Throwable) { RLog.w("files_in_log", t) }
        }

        /**
         * Whether a file may come in now ([ctx]'s process): never during a phone lock (someone else may hold the phone),
         * never while the phone is locked (robot_files_v1 needs it unlocked), never with Robot Files off.
         */
        fun gate(ctx: Context): Gate = when {
            app.heyrobot.lock.PhoneLockNow.on(ctx) -> Gate.PHONE_LOCK
            RobotFiles.locked(ctx) -> Gate.LOCKED
            !FilesSettingsStore.current(ctx).on -> Gate.OFF
            else -> Gate.OPEN
        }

        /** What [g] means in words, or null when the way is open. */
        fun gateText(g: Gate): app.heyrobot.core.i18n.Phrase? = when (g) {
            Gate.OPEN -> null
            Gate.OFF -> app.heyrobot.core.i18n.FilesStrings.off()
            Gate.LOCKED -> app.heyrobot.core.i18n.FilesStrings.locked()
            Gate.PHONE_LOCK -> app.heyrobot.core.i18n.LockStrings.closedNow()
        }

        /** Why Robot won't take [uri] (core ShareRules), or null: [own] says which providers are Robot's. */
        fun refusal(uri: Uri, own: (String) -> Boolean): ShareRefusal? = ShareRules.check(SharedRef(uri.scheme, uri.encodedAuthority), own)

        /** Whether [authority] is one of Robot's own providers: by its name, and by the package Android says owns it. */
        fun ownAuthority(ctx: Context, authority: String): Boolean {
            val pkg = ctx.packageName
            if (authority == pkg || authority.startsWith("$pkg.")) return true
            return try {
                ctx.packageManager.resolveContentProvider(authority, 0)?.packageName == pkg
            } catch (_: Exception) {
                false
            }
        }
    }
}
