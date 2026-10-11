package app.heyrobot.core

import java.io.File
import java.util.Locale

// Pure. The downloadable voice models (docs/voice-plan.md 2.4): single files from Hugging Face at pinned commits,
// each with its size and SHA-256, no archives. The main app downloads them (DownloadManager) and installs them into
// app-private storage, filesDir/models/<bundle id>/, checking every byte; it then writes a verified mark. The :voice
// process loads a bundle only when that mark vouches for every file (it never re-hashes 650 MB on the way in).

data class VoiceFile(val name: String, val bytes: Long, val sha256: String)

enum class VoiceBundle(
    /** Directory under filesDir/models/ (also the guard and download keys). */
    val id: String,
    val title: String,
    val about: String,
    val repo: String,
    val commit: String,
    val files: List<VoiceFile>,
) {
    STT(
        "stt-parakeet-v3", "Speech recognition",
        "NVIDIA Parakeet TDT 0.6B v3 (int8) turns what you say into text, on this phone. English now, French later.",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78",
        listOf(
            VoiceFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            VoiceFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            VoiceFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            VoiceFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        ),
    ),
    KOKORO(
        "tts-kokoro-v1.0", "Voice",
        "Kokoro 82M v1.0 reads replies aloud in a natural voice (English “Heart”; French “Siwis” for later).",
        "csukuangfj/kokoro-multi-lang-v1_0", "f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b",
        listOf(
            VoiceFile("model.onnx", 325_560_556, "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11"),
            VoiceFile("voices.bin", 28_200_960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
            VoiceFile("tokens.txt", 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
        ),
    ),
    KITTEN(
        "tts-kitten-nano-0.8", "Fast voice (fallback)",
        "Kitten TTS nano 0.8: a small English voice, about four times faster than Kokoro and less natural.",
        "csukuangfj2/kitten-nano-en-v0_8-fp32", "eb4fe4f70801b2eb1c3f45be046ccd553c7575e7",
        listOf(
            VoiceFile("model.fp32.onnx", 56_768_002, "2174dbf67b58b7b50d7b65294f89c2c53c172834533519b853c579879a04cc22"),
            VoiceFile("voices.bin", 3_276_800, "d520519c4a3519d44fcfcd943ed0b1e3c5da5cee0eea501d922fac1a93cd24dc"),
            VoiceFile("tokens.txt", 1_064, "934a4188addc7665dd3410256bb622169242357fbb99d840d9351209b486dabb"),
        ),
    ),
    /**
     * Conversation mode's speaker labels (0.5.0, docs/conversation-mode.md 3.1): NVIDIA TitaNet-S as sherpa-onnx exported
     * it, one 192-number voice embedding per line. Only transcripts use it, and only when "Tell speakers apart" is on.
     */
    SPEAKERS(
        "spk-titanet-small", "Speaker labels",
        "NVIDIA TitaNet-S tells the people in a conversation apart by their voices, on this phone. Only transcripts use it.",
        "csukuangfj/speaker-embedding-models", "0743f301363dec56491a490f6d6cbc9d67f9a3bf",
        listOf(
            VoiceFile("nemo_en_titanet_small.onnx", 40_257_283, "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"),
        ),
    );

    val bytes: Long get() = files.sumOf { it.bytes }

    /** The pinned Hugging Face URL: the commit, not a branch, so the bytes can never change under the hash. */
    fun url(f: VoiceFile): String = "https://huggingface.co/$repo/resolve/$commit/${f.name}"

    companion object {
        /** filesDir/models/<id>: app-private, credential-encrypted, never backed up. */
        fun dir(filesDir: File, b: VoiceBundle) = File(File(filesDir, "models"), b.id)

        fun byId(id: String?): VoiceBundle? = entries.firstOrNull { it.id == id }
    }
}

/** One installed file as it was when its SHA-256 was checked. */
data class InstalledFile(val name: String, val bytes: Long, val modified: Long, val sha256: String)

/** The verified mark (filesDir/models/<id>/.verified): one line per checked file, "name bytes modified sha256". */
object VoiceMark {
    const val FILE = ".verified"

    fun encode(files: List<InstalledFile>): String = files.joinToString("") { "${it.name} ${it.bytes} ${it.modified} ${it.sha256}\n" }

    fun decode(text: String?): List<InstalledFile> = text.orEmpty().lines().mapNotNull { l ->
        val p = l.trim().split(' ')
        if (p.size != 4) return@mapNotNull null
        val bytes = p[1].toLongOrNull() ?: return@mapNotNull null
        val modified = p[2].toLongOrNull() ?: return@mapNotNull null
        InstalledFile(p[0], bytes, modified, p[3])
    }

    /**
     * The files of [b] the mark vouches for: listed with the manifest's size and SHA-256, and still on disk with
     * that size and modification time ([onDisk] gives a file's size and mtime, or null when it is missing).
     */
    fun vouched(b: VoiceBundle, mark: List<InstalledFile>, onDisk: (String) -> Pair<Long, Long>?): Set<String> = vouched(b.files, mark, onDisk)

    /** [vouched] for any list of files (the fast translation engine's too, core MtBundle.kt). */
    fun vouched(files: List<VoiceFile>, mark: List<InstalledFile>, onDisk: (String) -> Pair<Long, Long>?): Set<String> =
        files.filter { f ->
            val m = mark.lastOrNull { it.name == f.name } ?: return@filter false
            m.bytes == f.bytes && m.sha256 == f.sha256 && onDisk(f.name) == (f.bytes to m.modified)
        }.map { it.name }.toSet()

    fun complete(b: VoiceBundle, mark: List<InstalledFile>, onDisk: (String) -> Pair<Long, Long>?): Boolean =
        vouched(b, mark, onDisk).size == b.files.size

    /** [complete] for a real directory. */
    fun complete(b: VoiceBundle, dir: File): Boolean = complete(b, decode(readOrNull(File(dir, FILE)))) { name ->
        File(dir, name).takeIf { it.isFile }?.let { it.length() to it.lastModified() }
    }
}

/** What the main app knows about one bundle; [VoiceModelLogic.status] decides what that means. */
data class VoiceFacts(
    /** Files the verified mark vouches for. */
    val vouched: Int,
    /** A download of one of its files is pending, running or paused. */
    val downloading: Boolean,
    /** Every file not yet installed is downloaded completely and waits to be checked and installed. */
    val downloaded: Boolean,
    val installing: Boolean,
    /** The last install found a file whose SHA-256 was wrong. */
    val bad: Boolean,
    /**
     * The last install could not write the files (an I/O error, a full disk): the downloads are still complete, but
     * nothing installs again by itself (it would rewrite hundreds of MB every second) until the person taps Download.
     */
    val installFailed: Boolean = false,
)

/** One DownloadManager row of a bundle's download ([Download] status and reason codes). */
data class FileDownload(val status: Int, val soFar: Long, val reason: Int)

object VoiceModelLogic {
    /** Kept free beyond the download and its installed copy. */
    const val HEADROOM = 300_000_000L

    fun status(b: VoiceBundle, f: VoiceFacts): ModelStatus = status(b.files.size, f)

    /** The status of a bundle of [files] files (a voice bundle, or the fast translation engine's). */
    fun status(files: Int, f: VoiceFacts): ModelStatus = when {
        f.installing -> ModelStatus.VERIFYING
        f.vouched >= files -> ModelStatus.VERIFIED
        f.downloading -> ModelStatus.DOWNLOADING
        // UNVERIFIED installs by itself (the app checks every second): never after an install that failed to write.
        f.downloaded && !f.installFailed -> ModelStatus.UNVERIFIED
        f.bad -> ModelStatus.BAD
        else -> ModelStatus.MISSING
    }

    /**
     * Space for installing [remaining]: DownloadManager keeps every file in Robot's download folder, then each
     * is copied into app-private storage and its download deleted, so the peak is all of them plus the largest twice.
     * Files named in [staged] are downloaded already (their space is taken): only their copy still counts.
     */
    fun needBytes(remaining: List<VoiceFile>, staged: Set<String> = emptySet()): Long =
        remaining.filter { it.name !in staged }.sumOf { it.bytes } + (remaining.maxOfOrNull { it.bytes } ?: 0) + HEADROOM

    /** A DownloadManager row that will still write: pending, running or paused. */
    fun inFlight(r: FileDownload?): Boolean =
        r != null && (r.status == Download.STATUS_PENDING || r.status == Download.STATUS_RUNNING || r.status == Download.STATUS_PAUSED)

    /**
     * What a bundle's downloads under way will still take from the free space: the rest of each file in flight
     * ([rows] by file name), then the copy of its largest file when it installs. 0 when nothing is in flight.
     */
    fun pendingBytes(remaining: List<VoiceFile>, rows: Map<String, FileDownload>): Long {
        val flying = remaining.filter { inFlight(rows[it.name]) }
        if (flying.isEmpty()) return 0
        return flying.sumOf { (it.bytes - rows.getValue(it.name).soFar.coerceAtLeast(0)).coerceAtLeast(0) } + remaining.maxOf { it.bytes }
    }

    /**
     * Null when [remaining] fits, else why not in plain words. [freeBytes] < 0 = unknown (allowed). [pendingBytes]:
     * what other downloads under way (Gemma, other voice models) will still take, since DownloadManager does not
     * reserve their space up front.
     */
    fun spaceProblem(b: VoiceBundle, remaining: List<VoiceFile>, freeBytes: Long, staged: Set<String> = emptySet(), pendingBytes: Long = 0): String? =
        spaceProblem(b.title, needBytes(remaining, staged), freeBytes, pendingBytes)

    /** Null when [need] bytes fit in [freeBytes] (< 0: unknown, allowed) beside [pendingBytes], else why not, naming [title]. */
    fun spaceProblem(title: String, need: Long, freeBytes: Long, pendingBytes: Long): String? {
        if (freeBytes < 0) return null
        val pending = pendingBytes.coerceAtLeast(0)
        if (freeBytes - pending >= need) return null
        val left = if (pending > 0) "only ${size((freeBytes - pending).coerceAtLeast(0))} will be left once the downloads already under way finish"
        else "${size(freeBytes)} is free"
        return "Not enough free space: “$title” needs ${size(need)} while it downloads and installs, and $left."
    }

    fun refuseDownload(
        b: VoiceBundle, status: ModelStatus, remaining: List<VoiceFile>, freeBytes: Long, staged: Set<String> = emptySet(), pendingBytes: Long = 0,
    ): String? = refuseDownload(status) { spaceProblem(b, remaining, freeBytes, staged, pendingBytes) }

    /** Why a download can't start in [status], or [space]'s answer when nothing is there yet. */
    fun refuseDownload(status: ModelStatus, space: () -> String?): String? = when (status) {
        ModelStatus.DOWNLOADING -> "The download is already running."
        ModelStatus.VERIFYING, ModelStatus.UNVERIFIED -> "The files are being checked and installed."
        ModelStatus.VERIFIED -> "Already on this phone."
        ModelStatus.IMPORTING -> "An import is running."
        ModelStatus.MISSING, ModelStatus.BAD -> space()
    }

    fun headline(status: ModelStatus): String = when (status) {
        ModelStatus.MISSING -> "Not downloaded"
        ModelStatus.DOWNLOADING -> "Downloading"
        ModelStatus.IMPORTING -> "Copying"
        ModelStatus.UNVERIFIED -> "Downloaded, installing next"
        ModelStatus.VERIFYING -> "Checking and installing"
        ModelStatus.VERIFIED -> "Ready"
        ModelStatus.BAD -> "A file was damaged"
    }

    /**
     * The download line and percent (-1 = unknown) from the rows of the files still downloading, with
     * [doneBytes] already installed or downloaded. A failed file is reported first: the rest cannot finish the bundle.
     */
    fun downloadText(b: VoiceBundle, doneBytes: Long, rows: List<FileDownload>): Pair<String, Int> = downloadText(b.bytes, doneBytes, rows)

    /** [downloadText] for a download of [total] bytes in all. */
    fun downloadText(total: Long, doneBytes: Long, rows: List<FileDownload>): Pair<String, Int> {
        val soFar = (doneBytes + rows.sumOf { it.soFar.coerceAtLeast(0) }).coerceIn(0, total)
        val pct = (soFar * 100 / total).toInt()
        val failed = rows.firstOrNull { it.status == Download.STATUS_FAILED }
        val paused = rows.firstOrNull { it.status == Download.STATUS_PAUSED }
        return when {
            failed != null -> "The download failed: ${Download.reasonText(failed.status, failed.reason)}. Tap Download to try again." to -1
            rows.isNotEmpty() && rows.all { it.status == Download.STATUS_SUCCESSFUL } -> "Downloaded. Checking the files next." to 100
            rows.any { it.status == Download.STATUS_RUNNING } -> String.format(Locale.US, "%s of %s (%d%%)", size(soFar), size(total), pct) to pct
            paused != null -> "Paused at ${size(soFar)}: ${Download.reasonText(paused.status, paused.reason)}" to -1
            else -> "Waiting to start" to -1
        }
    }

    fun installText(percent: Int): String = "Checking and installing: $percent%"

    /** "670 MB", "1.02 GB" (decimal units, as the phone's storage settings). */
    fun size(bytes: Long): String = if (bytes >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", bytes / 1e9)
    else String.format(Locale.US, "%d MB", Math.round(bytes / 1e6))
}
