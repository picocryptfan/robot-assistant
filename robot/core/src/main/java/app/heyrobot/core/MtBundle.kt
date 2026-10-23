// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Phrase
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

// Pure. The fast translation engine's files (docs/conversation-mode.md 4.6, docs/translation-plan.md 1.4): Mozilla's
// Bergamot fr-en and en-fr models, single gzip files at pinned URLs (the training run's id is in the path, so the bytes
// can't change under the hash), each with the SHA-256 of the .gz and of what it unpacks to (models.json's
// uncompressedHash for the two models). The main app downloads them when you tap Download (DownloadManager), checks
// each .gz, unpacks it as a stream and checks the result, keeps only the unpacked file, and grows the verified mark
// (VoiceMark's format) in filesDir/models/<id>/ file by file. The :mt process loads a direction only when the mark
// vouches for its three files. The two directions' vocabularies are byte-identical, so only fr-en's is downloaded.
// The same list is in tools/build_robotmt.sh (MtManifestTest keeps them in step) and THIRD_PARTY.md.

/** One file: [name] as installed, fetched from [MtBundle.BASE_URL]/[path] as gzip. */
data class MtFile(val name: String, val path: String, val gzBytes: Long, val gzSha256: String, val bytes: Long, val sha256: String) {
    /** The unpacked file, as the verified mark checks it. */
    val installed: VoiceFile get() = VoiceFile(name, bytes, sha256)
}

/** One translation direction ("fr-en") and the files it loads. */
data class MtDirection(val from: String, val to: String, val model: String, val vocabulary: String, val shortlist: String) {
    val id: String get() = "$from-$to"
}

enum class MtBundle(
    /** Directory under filesDir/models/ (also the download keys). */
    val id: String,
    val pair: LangPair,
    /** Its name ("Fast translation engine", « Moteur de traduction rapide »); [title] its English. */
    val titleText: Phrase,
    val files: List<MtFile>,
    val directions: List<MtDirection>,
) {
    FR_EN(
        "mt-fr-en", LangPair.FR_EN, ConversationStrings.fastEngine(),
        listOf(
            MtFile("model.fren.intgemm.alphas.bin", "fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/model.fren.intgemm.alphas.bin.gz",
                23_175_075, "06b1eeedd3944260d00a393d12ec7192490c5b7c930b97212faf4d5f5c90a2ee",
                31_561_787, "15f997bc0d13808b0b0fbd0786e684a3c8a52adcd8071844b76123fdacbf2b90"),
            MtFile("lex.50.50.fren.s2t.bin", "fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/lex.50.50.fren.s2t.bin.gz",
                2_649_934, "395aa7767220e1bcfc085f2b2787ff98005d0075e55451f1e0832885e3d9642a",
                4_824_120, "87c6752ea908f5f0347c10ac0cf7d80d9c2f4f20c81c90168f3e8230b56d4440"),
            MtFile("vocab.fren.spm", "fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/vocab.fren.spm.gz",
                409_706, "8d15b219ffd32327b4cabf0d94a05a4fecb8923a4f386badbcbe5e86ede453a7",
                814_404, "783abf3abe075afdf8d85d233994bef2c3a064e935ab1bed946820aff6ac002a"),
            MtFile("model.enfr.intgemm.alphas.bin", "en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/model.enfr.intgemm.alphas.bin.gz",
                23_045_432, "22cdaf3dc9a1da6e5818b5576737182467f3cd97e6fd6f94e4273eb7b56d1e3f",
                31_561_787, "6322e296d4fecfe395a8d5723da4ec37ecbe6d7613bb1dfcf4b28e2a47498b68"),
            MtFile("lex.50.50.enfr.s2t.bin", "en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/lex.50.50.enfr.s2t.bin.gz",
                2_297_334, "055987f0b585b61c4914fb4469e2392fda0b25d3f7784275974f920e7b631997",
                4_372_936, "2585ed98d3af0bc949865aedeb390493d591f56870814376e73e4144c41ed059"),
        ),
        listOf(
            MtDirection("fr", "en", "model.fren.intgemm.alphas.bin", "vocab.fren.spm", "lex.50.50.fren.s2t.bin"),
            MtDirection("en", "fr", "model.enfr.intgemm.alphas.bin", "vocab.fren.spm", "lex.50.50.enfr.s2t.bin"),
        ),
    );

    val title: String get() = titleText.en()

    /** What is downloaded (the .gz files): 51,577,481 bytes for fr↔en. */
    val downloadBytes: Long get() = files.sumOf { it.gzBytes }

    /** What is kept on the phone (the unpacked files): 73,135,034 bytes for fr↔en. */
    val bytes: Long get() = files.sumOf { it.bytes }

    fun url(f: MtFile): String = "$BASE_URL/${f.path}"

    fun direction(from: String?, to: String?): MtDirection? = directions.firstOrNull { it.from == from && it.to == to }

    fun direction(id: String?): MtDirection? = directions.firstOrNull { it.id == id }

    /** The files [d] loads, by name. */
    fun filesOf(d: MtDirection): List<MtFile> = listOf(d.model, d.vocabulary, d.shortlist).map { n -> files.first { it.name == n } }

    companion object {
        /** Mozilla's translation models bucket (models.json's baseUrl + "/models"). */
        const val BASE_URL = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models"

        /** filesDir/models/<id>: app-private, credential-encrypted, never backed up. */
        fun dir(filesDir: File, b: MtBundle) = File(File(filesDir, "models"), b.id)

        fun byId(id: String?): MtBundle? = entries.firstOrNull { it.id == id }

        fun of(pair: LangPair): MtBundle? = entries.firstOrNull { it.pair == pair }

        /** The bundle and direction for "fr-en", or null. */
        fun forDirection(id: String?): Pair<MtBundle, MtDirection>? =
            entries.firstNotNullOfOrNull { b -> b.direction(id)?.let { b to it } }
    }
}

/** The verified mark of a bundle (VoiceMark's format, the unpacked files' sizes and hashes). */
object MtMark {
    fun vouched(b: MtBundle, mark: List<InstalledFile>, onDisk: (String) -> Pair<Long, Long>?): Set<String> =
        VoiceMark.vouched(b.files.map { it.installed }, mark, onDisk)

    /** Every file [d] loads is vouched for. */
    fun ready(b: MtBundle, d: MtDirection, mark: List<InstalledFile>, onDisk: (String) -> Pair<Long, Long>?): Boolean =
        vouched(b, mark, onDisk).containsAll(b.filesOf(d).map { it.name })

    /** [vouched] for a real directory. */
    fun vouched(b: MtBundle, dir: File): Set<String> = vouched(b, VoiceMark.decode(readOrNull(File(dir, VoiceMark.FILE)))) { name ->
        File(dir, name).takeIf { it.isFile }?.let { it.length() to it.lastModified() }
    }

    /** [ready] for a real directory: what the :mt process asks before it loads a direction. */
    fun ready(b: MtBundle, d: MtDirection, dir: File): Boolean = vouched(b, dir).containsAll(b.filesOf(d).map { it.name })

    fun complete(b: MtBundle, dir: File): Boolean = b.directions.all { ready(b, it, dir) }
}

/**
 * Checks a downloaded .gz ([gz]: exactly [MtFile.gzBytes] bytes with [MtFile.gzSha256]), then unpacks it as a stream into
 * [dest] through [installVerified], which keeps it only with exactly [MtFile.bytes] bytes and [MtFile.sha256]. Nothing is
 * unpacked from a .gz that fails its own check. [progress] gets the unpacked bytes so far.
 */
fun installGzVerified(gz: File, dest: File, f: MtFile, progress: (Long) -> Unit = {}): InstallResult {
    try {
        if (!gz.isFile || gz.length() != f.gzBytes) return InstallResult.WRONG_SIZE
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(gz).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        if (hex(md.digest()) != f.gzSha256) return InstallResult.WRONG_HASH
    } catch (_: Exception) {
        return InstallResult.IO_ERROR
    }
    return try {
        GZIPInputStream(FileInputStream(gz), 1 shl 16).use { installVerified(it, dest, f.bytes, f.sha256, progress) }
    } catch (_: Exception) {
        InstallResult.IO_ERROR
    }
}

/** The download and install rules for a bundle, with VoiceModelLogic's wording and states. */
object MtModelLogic {
    fun status(b: MtBundle, f: VoiceFacts): ModelStatus = VoiceModelLogic.status(b.files.size, f)

    /**
     * Space for installing [remaining]: every .gz waits in Robot's download folder, then each is unpacked into
     * app-private storage before its .gz goes, so the peak is all the .gz files plus all the unpacked ones. Files named
     * in [staged] are downloaded already (their space is taken).
     */
    fun needBytes(remaining: List<MtFile>, staged: Set<String> = emptySet()): Long =
        remaining.filter { it.name !in staged }.sumOf { it.gzBytes } + remaining.sumOf { it.bytes } + VoiceModelLogic.HEADROOM

    /** What [remaining]'s downloads under way will still take: the rest of each .gz in flight, then the unpacked files. */
    fun pendingBytes(remaining: List<MtFile>, rows: Map<String, FileDownload>): Long {
        val flying = remaining.filter { VoiceModelLogic.inFlight(rows[it.name]) }
        if (flying.isEmpty()) return 0
        return flying.sumOf { (it.gzBytes - rows.getValue(it.name).soFar.coerceAtLeast(0)).coerceAtLeast(0) } + remaining.sumOf { it.bytes }
    }

    fun refuseDownloadText(b: MtBundle, status: ModelStatus, remaining: List<MtFile>, freeBytes: Long, staged: Set<String> = emptySet(), pendingBytes: Long = 0): Phrase? =
        VoiceModelLogic.refuseDownloadText(status) { VoiceModelLogic.spaceProblemText(b.titleText, needBytes(remaining, staged), freeBytes, pendingBytes) }

    /** The download line and percent, counted in .gz bytes ([doneBytes]: those of the files already installed). */
    fun downloadPhrase(b: MtBundle, doneBytes: Long, rows: List<FileDownload>): Pair<Phrase, Int> = VoiceModelLogic.downloadPhrase(b.downloadBytes, doneBytes, rows)

    /** "Fast translation engine (52 MB)", « Moteur de traduction rapide (52 Mo) ». */
    fun labelText(b: MtBundle): Phrase = ConversationStrings.engineWithSize("title" to b.titleText, "size" to sizeText(b.downloadBytes))

    /** [labelText] in English. */
    fun label(b: MtBundle): String = labelText(b).en()

    /** A download's size as the voice models write it: "52 MB", "1.23 GB" / « 52 Mo », « 1,23 Go » (1.1, WP7: VoiceModelLogic.sizeText). */
    fun sizeText(bytes: Long): Phrase = VoiceModelLogic.sizeText(bytes)
}
