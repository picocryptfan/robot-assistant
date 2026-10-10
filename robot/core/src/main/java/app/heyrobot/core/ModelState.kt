package app.heyrobot.core

import java.util.Locale

// Pure. The model manager's state: which file, where it stands, what to tell the person, and whether a
// download or import may start. ModelStore (app) gathers the facts from the phone; this decides.

/** Gemma 4 E2B for the Tensor G5 TPU: the same file, URL, size and hash as the probe and the lab. */
object ModelSpec {
    const val NAME = "Gemma 4 E2B (Tensor G5)"
    const val FILE = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
    const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
        "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it_Google_Tensor_G5.litertlm"
    const val BYTES = 3_113_545_589L
    const val SHA256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"
    /** Free space kept beyond the file itself. */
    const val HEADROOM = 1_000_000_000L
}

enum class ModelStatus {
    /** No complete file. */
    MISSING,
    DOWNLOADING,
    IMPORTING,
    VERIFYING,
    /** Complete file whose SHA-256 has not been checked yet. */
    UNVERIFIED,
    /** Checked: ready to load. */
    VERIFIED,
    /** Checked and wrong: delete and fetch again. */
    BAD,
}

data class ModelFacts(
    val fileBytes: Long,
    /** The verified mark matches this file's size and modification time. */
    val verifiedMark: Boolean,
    /** The bad mark matches this file. */
    val badMark: Boolean,
    val downloading: Boolean,
    val importing: Boolean,
    val verifying: Boolean,
)

/** DownloadManager.STATUS_* and its PAUSED_/ERROR_ reasons, as constants so this file stays pure. */
object Download {
    const val STATUS_PENDING = 1
    const val STATUS_RUNNING = 2
    const val STATUS_PAUSED = 4
    const val STATUS_SUCCESSFUL = 8
    const val STATUS_FAILED = 16

    /** Reasons in plain words (and HTTP codes for a failure). */
    fun reasonText(status: Int, reason: Int): String = when (status) {
        STATUS_PAUSED -> when (reason) {
            1 -> "waiting to retry: couldn't reach the server"
            2 -> "waiting for a network connection"
            3 -> "waiting for Wi-Fi (too large for mobile data)"
            else -> "paused"
        }
        STATUS_FAILED -> when (reason) {
            in 100..599 -> "the server answered with error $reason"
            1001 -> "the phone couldn't write the file"
            1004 -> "the connection broke"
            1006 -> "the phone ran out of space"
            1007 -> "no storage was available"
            1008 -> "the download couldn't resume and must start again"
            1009 -> "the file already exists"
            else -> "an unknown error"
        }
        else -> ""
    }
}

object ModelLogic {
    fun status(f: ModelFacts): ModelStatus = when {
        f.importing -> ModelStatus.IMPORTING
        f.downloading -> ModelStatus.DOWNLOADING
        f.fileBytes != ModelSpec.BYTES -> ModelStatus.MISSING
        f.verifying -> ModelStatus.VERIFYING
        f.verifiedMark -> ModelStatus.VERIFIED
        f.badMark -> ModelStatus.BAD
        else -> ModelStatus.UNVERIFIED
    }

    /**
     * Null when [needBytes] (default: the model plus headroom) fits; else why not, in plain words. [freeBytes] < 0 =
     * unknown (allowed). [pendingBytes]: what the voice-model downloads under way will still take (DownloadManager
     * does not reserve their space).
     */
    fun spaceProblem(freeBytes: Long, reclaimableBytes: Long, needBytes: Long = ModelSpec.BYTES + ModelSpec.HEADROOM, pendingBytes: Long = 0): String? {
        if (freeBytes < 0) return null
        val pending = pendingBytes.coerceAtLeast(0)
        val free = freeBytes + reclaimableBytes
        if (free - pending >= needBytes) return null
        val left = if (pending > 0) "only ${gb((free - pending).coerceAtLeast(0))} will be left once the voice models still downloading finish"
        else "${gb(free)} is free"
        return "Not enough free space: Gemma needs ${gb(needBytes)} (the file plus 1 GB to spare) and $left."
    }

    /** Null when a download may start, else why not. */
    fun refuseDownload(status: ModelStatus, freeBytes: Long, existingBytes: Long, pendingBytes: Long = 0): String? = when (status) {
        ModelStatus.DOWNLOADING -> "The download is already running."
        ModelStatus.IMPORTING -> "An import is running."
        ModelStatus.VERIFYING -> "The model is being checked."
        ModelStatus.VERIFIED, ModelStatus.UNVERIFIED -> "The model is already on this phone."
        ModelStatus.MISSING, ModelStatus.BAD -> spaceProblem(freeBytes, existingBytes, pendingBytes = pendingBytes)
    }

    /** Null when an import of [docBytes] (-1 = the picker didn't say) may start, else why not. */
    fun refuseImport(status: ModelStatus, docBytes: Long, freeBytes: Long, reclaimableBytes: Long, pendingBytes: Long = 0): String? = when {
        status == ModelStatus.IMPORTING -> "An import is already running."
        status == ModelStatus.VERIFYING -> "The model is being checked."
        status == ModelStatus.VERIFIED -> "The model is already on this phone and checked."
        docBytes >= 0 && docBytes != ModelSpec.BYTES -> String.format(Locale.US,
            "That file isn't the model: it has %,d bytes and %s has %,d.", docBytes, ModelSpec.FILE, ModelSpec.BYTES)
        else -> spaceProblem(freeBytes, reclaimableBytes, pendingBytes = pendingBytes)
    }

    /** One line for the model card. */
    fun headline(status: ModelStatus): String = when (status) {
        ModelStatus.MISSING -> "Not downloaded"
        ModelStatus.DOWNLOADING -> "Downloading"
        ModelStatus.IMPORTING -> "Copying the file"
        ModelStatus.VERIFYING -> "Checking the file"
        ModelStatus.UNVERIFIED -> "Downloaded, not checked yet"
        ModelStatus.VERIFIED -> "Ready"
        ModelStatus.BAD -> "The file is damaged"
    }

    /** Download progress in plain words from DownloadManager's status, reason and byte count. */
    fun downloadText(status: Int, reason: Int, soFar: Long): String = when (status) {
        Download.STATUS_PENDING -> "Waiting to start"
        Download.STATUS_RUNNING -> String.format(Locale.US, "%s of %s (%d%%)", gb(soFar), gb(ModelSpec.BYTES), percent(soFar))
        Download.STATUS_PAUSED -> String.format(Locale.US, "Paused at %s: %s", gb(soFar), Download.reasonText(status, reason))
        Download.STATUS_FAILED -> "The download failed: ${Download.reasonText(status, reason)}. Tap Download to try again."
        Download.STATUS_SUCCESSFUL -> "Downloaded"
        else -> "Download status unknown"
    }

    fun percent(bytes: Long): Int = (bytes.coerceIn(0, ModelSpec.BYTES) * 100 / ModelSpec.BYTES).toInt()

    fun gb(bytes: Long): String = String.format(Locale.US, "%.2f GB", bytes / 1e9)
}
