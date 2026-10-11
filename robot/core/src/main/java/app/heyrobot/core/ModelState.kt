package app.heyrobot.core

import java.util.Locale

// Pure. The model catalogue (0.4.1) and the model manager's state: which file each model is, where each stands, what to
// tell you, and whether a download or import may start; and which model the assistant loads (ModelChoice). ModelStore
// (app) gathers the facts from the phone; this decides.

/**
 * What kind of model file it is, by its first bytes (never its name), and so which engine runs it (0.4.5): LiteRT-LM
 * (Robot's built-in models, and your own .litertlm) or llama.cpp (your own .gguf, on the CPU). [id] is what
 * BrainMsg.Load carries to :brain.
 */
enum class ModelFormat(val id: String, val title: String, val engine: String) {
    LITERT_LM("litertlm", "LiteRT-LM", "LiteRT-LM"),
    GGUF("gguf", "GGUF", "llama.cpp");

    companion object {
        fun of(id: String?): ModelFormat? = entries.firstOrNull { it.id == id }

        /** By the file's magic: "LITERTLM" or "GGUF" at its start; null for anything else. */
        fun sniff(head: ByteArray): ModelFormat? {
            val s = String(head, 0, minOf(head.size, 8), Charsets.ISO_8859_1)
            return when {
                s.startsWith(LiteRtLmCheck.MAGIC) -> LITERT_LM
                s.startsWith(GgufCheck.MAGIC) -> GGUF
                else -> null
            }
        }
    }
}

/** Where a model runs. [id] is what BrainMsg.Load carries to :brain. */
enum class ModelBackend(val id: String, val title: String) {
    /** The Tensor G5 TPU, through LiteRT-LM's NPU backend. */
    TPU("npu", "TPU"),
    /** The CPU (XNNPACK), which writes a weight cache on the first load. */
    CPU("cpu", "CPU"),
    /**
     * 0.4.7: the GPU, through LiteRT-LM's GPU backend (its OpenCL accelerator, linked into liblitertlm_jni.so, with the
     * phone's own libOpenCL.so): only [BrainModel.GENERAL], only where [ModelCatalogue.gpuOffered], and never on a
     * Tensor G5 or G6 (their PowerVR GPU). It writes a program and weight cache on the first load.
     */
    GPU("gpu", "GPU");

    companion object {
        fun of(id: String?): ModelBackend? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The models Robot can run (Settings › Model). The built-in ones download from Hugging Face only when you ask,
 * pinned by commit, size and SHA-256, and the hash is checked before a file is ever loaded. [OWN] is a .litertlm or (0.4.5)
 * .gguf file you import; Robot checks what is inside it (LiteRtLmCheck, GgufCheck), never its name. [FAST] is the model
 * of 0.4.0, byte for byte, and the default on a Tensor G5 phone (a Pixel 10). 0.4.7: [GENERAL] is the same model's general
 * build, which runs on the GPU or the CPU: "Fast" on every other phone (ModelCatalogue decides which a phone gets, and
 * shows only that one).
 *
 * No Gemma 4 E4B build for the Tensor TPU exists (the 0.4.1 research: Google publishes TPU builds of E2B only, and the one
 * "E4B Tensor G5" file on Hugging Face is a generic CPU export renamed), so both E4B entries run on the CPU. A future
 * genuine Google E4B TPU file may be added here only once it is published, pinned and passes the content check.
 *
 * [contextTokens] is the context :brain asks for (maxNumTokens); every entry uses 4,096, the prompt budget
 * ([ContextWindow.MAX_TOKENS]). The CPU files could take more, but prompt reading at about 70 tokens a second makes a
 * longer context slow, so it stays 4,096 until the phone tests say otherwise. [OWN]'s is the most it gets: since 0.4.3 a
 * file built for 2,048 to 4,095 tokens loads with its own context instead, as chat only (LiteRtLmReport.contextTokens).
 */
enum class BrainModel(
    val id: String,
    /** The choice's name in Settings › Model. */
    val title: String,
    val modelName: String,
    /** The file's name in Robot's storage. */
    val file: String,
    val url: String?,
    /** -1: not known in advance ([OWN]). */
    val bytes: Long,
    val sha256: String?,
    /** Where it runs ([OWN]: the CPU unless the content check proves a Tensor G5 build and you allow it). */
    val backend: ModelBackend,
    val contextTokens: Int,
    /** The weight cache the CPU writes on the first load, in the model's own folder (counted in the free-space check). */
    val cacheBytes: Long,
    /** Memory while loaded, about. */
    val memoryBytes: Long,
    /** Who published the file (the Hugging Face account). */
    val publisher: String,
    val repo: String?,
    val commit: String?,
    val licence: String,
    val fromGoogle: Boolean,
) {
    FAST(
        id = "e2b_tpu", title = "Fast", modelName = "Gemma 4 E2B (Tensor G5)",
        file = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        bytes = 3_113_545_589L, sha256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc",
        backend = ModelBackend.TPU, contextTokens = 4096, cacheBytes = 0, memoryBytes = 2_000_000_000L,
        publisher = "litert-community (Google)", repo = "litert-community/gemma-4-E2B-it-litert-lm",
        commit = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", licence = "Apache-2.0", fromGoogle = true,
    ),
    /**
     * 0.4.7: Gemma 4 E2B's general build, from the same repository and commit as [FAST]: Google's file that LiteRT-LM runs
     * on the CPU (XNNPACK) or the GPU (its OpenCL accelerator). Fast on every phone without a Tensor G5 TPU build (a Pixel
     * 9a: Tensor G4). The figures are estimates: Robot's GPU lab ran this very file on a Pixel 10 Pro (CPU: RSS 2,617 MB;
     * GPU: a weight cache of 745 MB and a program cache of 11 MB on the first load), and Google's model card gives 1.7 GB
     * on the CPU and 0.7 GB on the GPU of a Galaxy S26 Ultra. [cacheBytes] is the CPU's (three quarters of the file, the
     * E4B ratio rounded up), the larger of the two.
     */
    GENERAL(
        id = "e2b", title = "Fast", modelName = "Gemma 4 E2B",
        file = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L, sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        backend = ModelBackend.CPU, contextTokens = 4096, cacheBytes = 2_000_000_000L, memoryBytes = 2_600_000_000L,
        publisher = "litert-community (Google)", repo = "litert-community/gemma-4-E2B-it-litert-lm",
        commit = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", licence = "Apache-2.0", fromGoogle = true,
    ),
    SMARTER(
        id = "e4b_cpu", title = "Smarter, slower", modelName = "Gemma 4 E4B (CPU)",
        file = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
        bytes = 3_659_530_240L, sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        // Measured on the Pixel 10 Pro (LiteRT-LM 0.18.0): 2.54 GB of caches on the first load, RSS 4,120 MB.
        backend = ModelBackend.CPU, contextTokens = 4096, cacheBytes = 2_600_000_000L, memoryBytes = 4_100_000_000L,
        publisher = "litert-community (Google)", repo = "litert-community/gemma-4-E4B-it-litert-lm",
        commit = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da", licence = "Apache-2.0", fromGoogle = true,
    ),
    UNCENSORED(
        id = "e4b_cpu_uncensored", title = "Uncensored, slower", modelName = "Gemma 4 E4B abliterated (CPU)",
        file = "gemma-4-E4B-it-abliterated.litertlm",
        url = "https://huggingface.co/olekk/gemma-4-E4B-it-abliterated-litert-lm/resolve/" +
            "a4eecccd3b0ba1777660180cda60a396eedcb8aa/gemma-4-E4B-it-abliterated.litertlm",
        bytes = 3_659_530_240L, sha256 = "f5a6625e0368e853743c91b39fa6ac6a8add1fc4825b18f98f518966610f2a88",
        // Google's E4B file with 2.45% of two kinds of weights changed: the same layout, so the same speed and memory.
        backend = ModelBackend.CPU, contextTokens = 4096, cacheBytes = 2_600_000_000L, memoryBytes = 4_100_000_000L,
        publisher = "olekk (community build, not from Google)", repo = "olekk/gemma-4-E4B-it-abliterated-litert-lm",
        commit = "a4eecccd3b0ba1777660180cda60a396eedcb8aa", licence = "Apache-2.0", fromGoogle = false,
    ),
    OWN(
        id = "own", title = "Your own model file", modelName = "A .litertlm or .gguf file you picked",
        // A fixed name: the picked file's own name is never a path (nor a reason to trust it). A GGUF file is kept as
        // ModelFiles.OWN_GGUF instead (its contents decide which, after the copy).
        file = "own_model.litertlm", url = null, bytes = -1, sha256 = null,
        backend = ModelBackend.CPU, contextTokens = 4096, cacheBytes = -1, memoryBytes = -1,
        publisher = "you", repo = null, commit = null, licence = "the file's own", fromGoogle = false,
    );

    /** Downloaded from a pinned URL (not [OWN]). */
    val builtIn: Boolean get() = url != null

    /** 0.4.7: it may run on the GPU (only [GENERAL]: Google's general E2B file, which LiteRT-LM runs there). */
    val gpu: Boolean get() = this == GENERAL

    /** Where it may run: its own backend, and the GPU for [gpu] (your own file: as its check found, LoadCheck). */
    fun runsOn(b: ModelBackend): Boolean = b == backend || (gpu && b == ModelBackend.GPU)

    /** One line for the choice in Settings › Model: speed, memory, battery, from the 0.4.1 phone measurements. */
    val speed: String get() = when (this) {
        FAST -> "Writes about 17 tokens (word pieces) a second; a new conversation starts answering in about 3 s. " +
            "About 2 GB of memory, light on the battery. Sees photos."
        GENERAL -> "The same model as the Pixel 10's Fast, built to run on the GPU or the CPU. About 2.6 GB of memory. Sees photos."
        SMARTER -> "Better answers, slower: about 8 tokens a second, and about 21 s before the first word of a new " +
            "conversation (later answers about 1 to 3 s). About 4 GB of memory and more battery (about 9 W while it writes, " +
            "against 3.5 W). Sees photos, more slowly."
        UNCENSORED -> "Like Smarter: about 8 tokens a second, about 21 s before the first word of a new conversation, " +
            "about 4 GB of memory and more battery."
        OWN -> "Runs on the CPU (a .gguf with llama.cpp), unless Robot's check proves a .litertlm was compiled for the " +
            "Tensor G5's TPU, the phone has one, and you allow that."
    }

    /** What it is, in plain words. */
    val about: String get() = when (this) {
        FAST -> "Gemma 4 E2B, compiled by Google for the Tensor G5's TPU (the Pixel 10 series). Robot's model since 0.1."
        GENERAL -> "Gemma 4 E2B from Google, the general build of the model the Pixel 10 runs on its TPU: Google " +
            "publishes no TPU build for this phone's chip, so it runs on the GPU or the CPU. The first load writes a cache " +
            "next to it (about 0.8 GB on the GPU, up to 2 GB on the CPU)."
        SMARTER -> "Gemma 4 E4B from Google, the larger of the two small Gemma 4 models, run on the CPU: no Tensor TPU " +
            "build of it exists. The first load writes about 2.5 GB of cache next to it."
        UNCENSORED -> "A community build of Gemma 4 E4B with the model's own refusals removed (olekk; not from Google). " +
            "Robot's own cards and switches still apply: actions still ask you first as Settings › Actions and Safety & privacy " +
            "say. Its maker spot-checked it but ran no benchmark, and it may still refuse " +
            "now and then; the publisher asks that it be used for research and personal experimentation, says you own its outputs, " +
            "and says Google's Gemma Prohibited Use Policy still applies."
        OWN -> "A LiteRT-LM (.litertlm) or GGUF (.gguf) file. Robot copies it into its own storage, shows its size and " +
            "SHA-256, and checks what is inside before it can be chosen. Robot is written for Gemma 4: another model may " +
            "answer oddly and actions may not work."
    }

    companion object {
        val DEFAULT = FAST
        /** Free space kept beyond a model's file and cache. */
        const val HEADROOM = 1_000_000_000L

        /** Unknown or empty ids (older settings) mean the default. */
        fun of(id: String?): BrainModel = entries.firstOrNull { it.id == id } ?: DEFAULT

        fun byId(id: String?): BrainModel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A publisher's own conditions for its model, asked as tick boxes before Robot downloads or imports the file (0.4.5). The
 * Hugging Face repository shows them as an access form ("extra_gated_fields") but doesn't enforce it (it isn't gated), and
 * Robot's one-tap download would skip it, so Robot asks them itself, in the publisher's words, and keeps that you confirmed
 * them ([RobotSettings.confirmedTerms], schema 12). [id] names the model card at the pinned commit: a new commit, which may
 * change the terms, is asked again.
 */
enum class ModelTerms(val id: String, val model: BrainModel, val publisher: String, val prompt: String, val ticks: List<String>) {
    UNCENSORED(
        id = "olekk/gemma-4-E4B-it-abliterated-litert-lm@a4eecccd",
        model = BrainModel.UNCENSORED,
        publisher = "olekk",
        // The card's extra_gated_prompt and extra_gated_fields at commit a4eecccd, word for word.
        prompt = "This is an ABLITERATED model: its trained refusal behavior has been removed and it will attempt to answer harmful " +
            "or unsafe requests. It is released for research and personal experimentation only. By requesting access you confirm " +
            "you are using it responsibly and accept sole responsibility for any outputs and downstream use.",
        ticks = listOf(
            "I use this for research / personal experimentation only",
            "I accept responsibility for the outputs and any downstream use",
        ),
    );

    /** Every box ticked ([ticked]: the indexes of [ticks] ticked): only then may it be confirmed. */
    fun complete(ticked: Set<Int>): Boolean = ticks.indices.all { it in ticked }

    companion object {
        /** Robot's words around the publisher's: where they come from, and what still applies. */
        const val INTRO = "Its publisher asks everyone who downloads it to confirm two things first:"
        const val ALSO = "Google's Gemma Prohibited Use Policy still applies, as its publisher says, and Robot's cards and " +
            "switches still decide what asks you first."
        const val REFUSED = "Tick its publisher's two boxes on its card first."

        fun of(m: BrainModel): ModelTerms? = entries.firstOrNull { it.model == m }

        /** The terms [m] needs you to confirm before it downloads or imports, or null (none, or confirmed in [s]). */
        fun missing(s: RobotSettings, m: BrainModel): ModelTerms? = of(m)?.takeIf { it.id !in s.confirmedTerms }

        /** [s] with [t] confirmed at [now] (wall clock ms). */
        fun confirm(s: RobotSettings, t: ModelTerms, now: Long): RobotSettings = s.copy(confirmedTerms = s.confirmedTerms + (t.id to now))
    }
}

/** Where each model's files live: 0.4.0's names stay [BrainModel.FAST]'s, so an installed model stays checked. */
object ModelFiles {
    /** In Robot's private folder (filesDir/robot), so the voice and brain processes can trust it too. */
    fun verifiedMark(m: BrainModel): String = if (m == BrainModel.FAST) "model_verified.txt" else "model_verified_${m.id}.txt"

    /** The model store's preference keys ("download_id", "bad", "last_failure"). */
    fun prefKey(m: BrainModel, key: String): String = if (m == BrainModel.FAST) key else "${key}_${m.id}"

    /**
     * The CPU's weight cache (0.4.7: and the GPU's program and weight caches, beside it), under filesDir; deleted with the
     * model. Null on the TPU (LiteRT-LM keeps no cache there).
     */
    fun cacheDir(m: BrainModel, backend: ModelBackend = m.backend): String? = if (backend != ModelBackend.TPU) "litert_cache/${m.id}" else null

    /** What the GPU's first load of [GENERAL][BrainModel.GENERAL] writes, about (the lab: 745 MB of weights, 11 MB of programs). */
    const val GPU_CACHE_BYTES = 900_000_000L

    /** The cache [m]'s first load on [backend] writes, about (the free-space check before it). */
    fun cacheBytes(m: BrainModel, backend: ModelBackend, fileBytes: Long = m.bytes): Long = when {
        backend == ModelBackend.TPU -> 0
        backend == ModelBackend.GPU -> GPU_CACHE_BYTES
        m == BrainModel.OWN -> cacheEstimate(fileBytes)
        else -> m.cacheBytes
    }

    /** Your own file's record (core OwnModel), written by the main app after the check. */
    const val OWN_RECORD = "own_model.json"

    /** Your own file when it is a GGUF file (0.4.5); a LiteRT-LM file keeps BrainModel.OWN.file. */
    const val OWN_GGUF = "own_model.gguf"

    /** Your own file's name in Robot's storage for its [format]. */
    fun ownFile(format: ModelFormat): String = if (format == ModelFormat.GGUF) OWN_GGUF else BrainModel.OWN.file

    /** A file's size and modification time: what the verified marks and your own file's record keep of the file checked. */
    fun fingerprint(bytes: Long, modified: Long): String = "$bytes|$modified"

    /** Written by :brain while an engine is loaded (BrainLoaded), so the main app never deletes a model in use. */
    const val LOADED = "brain_loaded.txt"

    /** The CPU cache of a file of [bytes], about (the measured E4B ratio, 2.54 GB for 3.66 GB, rounded up). */
    fun cacheEstimate(bytes: Long): Long = (bytes.coerceAtLeast(0) / 4) * 3

    /**
     * What a model needs free before its download or import: the file, its first-load cache and the headroom. [gguf]: your
     * own file is a GGUF file, which llama.cpp memory-maps without writing a cache.
     */
    fun needBytes(m: BrainModel, fileBytes: Long = m.bytes, gguf: Boolean = false): Long {
        val file = fileBytes.coerceAtLeast(0)
        val cache = if (m == BrainModel.OWN) (if (gguf) 0 else cacheEstimate(file)) else m.cacheBytes.coerceAtLeast(0)
        return file + cache + BrainModel.HEADROOM
    }
}

/** :brain's "loaded" file: "<model id> <backend id> <pid>", or nothing. Numbers and fixed ids only. */
data class BrainLoaded(val model: BrainModel, val backend: ModelBackend, val pid: Int) {
    fun encode(): String = "${model.id} ${backend.id} $pid"

    companion object {
        fun decode(text: String?): BrainLoaded? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 3) return null
            val m = BrainModel.byId(p[0]) ?: return null
            val b = ModelBackend.of(p[1]) ?: return null
            val pid = p[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return BrainLoaded(m, b, pid)
        }
    }
}

enum class ModelStatus {
    /** No complete file. */
    MISSING,
    DOWNLOADING,
    IMPORTING,
    VERIFYING,
    /** Complete file whose SHA-256 (and, for your own file, contents) has not been checked yet. */
    UNVERIFIED,
    /** Checked: ready to load. */
    VERIFIED,
    /** Checked and wrong (or, for your own file, not usable here): delete it. */
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
    /** Your own file is at least this big (a header, a tokenizer and a model). */
    const val OWN_MIN_BYTES = 1_000_000L

    /** The file has every byte: the pinned size, or (your own file, whose size isn't known in advance) any. */
    fun complete(m: BrainModel, bytes: Long): Boolean = if (m.bytes > 0) bytes == m.bytes else bytes > 0

    fun status(m: BrainModel, f: ModelFacts): ModelStatus = when {
        f.importing -> ModelStatus.IMPORTING
        f.downloading -> ModelStatus.DOWNLOADING
        !complete(m, f.fileBytes) -> ModelStatus.MISSING
        f.verifying -> ModelStatus.VERIFYING
        f.verifiedMark -> ModelStatus.VERIFIED
        f.badMark -> ModelStatus.BAD
        else -> ModelStatus.UNVERIFIED
    }

    /**
     * Null when [needBytes] (default: the model's file, its first-load cache and the headroom) fits; else why not, in
     * plain words. [freeBytes] < 0 = unknown (allowed). [pendingBytes]: what downloads under way (voice models, another
     * model) will still take (DownloadManager does not reserve their space).
     */
    fun spaceProblem(
        m: BrainModel, freeBytes: Long, reclaimableBytes: Long, needBytes: Long = ModelFiles.needBytes(m), pendingBytes: Long = 0,
        fileBytes: Long = m.bytes, gguf: Boolean = false,
    ): String? {
        if (freeBytes < 0) return null
        val pending = pendingBytes.coerceAtLeast(0)
        val free = freeBytes + reclaimableBytes
        if (free - pending >= needBytes) return null
        val left = if (pending > 0) "only ${gb((free - pending).coerceAtLeast(0))} will be left once the downloads under way finish"
        else "${gb(free)} is free"
        val cache = if (m == BrainModel.OWN) (if (gguf) 0 else ModelFiles.cacheEstimate(fileBytes)) else m.cacheBytes
        val what = if (cache > 0 && fileBytes > 0) "the ${gb(fileBytes)} file, about ${gb(cache)} for the cache the CPU writes on its first load, and 1 GB to spare"
        else "the file plus 1 GB to spare"
        return "Not enough free space: ${name(m)} needs ${gb(needBytes)} ($what) and $left."
    }

    /** "Gemma 4 E2B (Tensor G5)", "Gemma 4 E2B", or "Your model file". */
    fun name(m: BrainModel): String = if (m == BrainModel.OWN) "Your model file" else m.modelName

    /**
     * Null when a download of [m] may start, else why not. [busyWith]: another model whose download or import is
     * under way (one at a time, so the space check holds).
     */
    fun refuseDownload(m: BrainModel, status: ModelStatus, freeBytes: Long, existingBytes: Long, pendingBytes: Long = 0, busyWith: BrainModel? = null): String? = when {
        !m.builtIn -> "This one has no download: import your file."
        busyWith != null && busyWith != m -> "“${busyWith.title}” is downloading or being copied. Wait for it to finish, or cancel it first."
        else -> when (status) {
            ModelStatus.DOWNLOADING -> "The download is already running."
            ModelStatus.IMPORTING -> "An import is running."
            ModelStatus.VERIFYING -> "The model is being checked."
            ModelStatus.VERIFIED, ModelStatus.UNVERIFIED -> "The model is already on this phone."
            ModelStatus.MISSING, ModelStatus.BAD -> spaceProblem(m, freeBytes, existingBytes, pendingBytes = pendingBytes)
        }
    }

    /**
     * Null when an import of [docBytes] (-1 = the picker didn't say) into [m] may start, else why not. A built-in model
     * takes only its own file (the exact size, then the SHA-256); your own file any size from [OWN_MIN_BYTES]. [gguf]: the
     * picked file starts like a GGUF file (0.4.5), which needs no room for a cache.
     */
    fun refuseImport(m: BrainModel, status: ModelStatus, docBytes: Long, freeBytes: Long, reclaimableBytes: Long, pendingBytes: Long = 0, busyWith: BrainModel? = null,
        gguf: Boolean = false): String? = when {
        busyWith != null && busyWith != m -> "“${busyWith.title}” is downloading or being copied. Wait for it to finish, or cancel it first."
        status == ModelStatus.IMPORTING -> "An import is already running."
        status == ModelStatus.VERIFYING -> "The model is being checked."
        status == ModelStatus.VERIFIED && m.builtIn -> "The model is already on this phone and checked."
        m.builtIn && docBytes >= 0 && docBytes != m.bytes -> String.format(Locale.US,
            "That file isn't the model: it has %,d bytes and %s has %,d.", docBytes, m.file, m.bytes)
        !m.builtIn && docBytes in 0 until OWN_MIN_BYTES -> String.format(Locale.US, "That file is too small to be a model (%,d bytes).", docBytes)
        m.builtIn -> spaceProblem(m, freeBytes, reclaimableBytes, pendingBytes = pendingBytes)
        // Unknown size: the copy watches the free space as it goes.
        docBytes < 0 -> null
        else -> spaceProblem(m, freeBytes, reclaimableBytes, ModelFiles.needBytes(m, docBytes, gguf && !m.builtIn), pendingBytes, fileBytes = docBytes, gguf = gguf && !m.builtIn)
    }

    /** One line for the model card. */
    fun headline(status: ModelStatus, m: BrainModel = BrainModel.FAST): String = when (status) {
        ModelStatus.MISSING -> if (m.builtIn) "Not downloaded" else "No file yet"
        ModelStatus.DOWNLOADING -> "Downloading"
        ModelStatus.IMPORTING -> "Copying the file"
        ModelStatus.VERIFYING -> "Checking the file"
        ModelStatus.UNVERIFIED -> if (m.builtIn) "Downloaded, not checked yet" else "Copied, not checked yet"
        ModelStatus.VERIFIED -> "Ready"
        ModelStatus.BAD -> if (m.builtIn) "The file is damaged" else "Robot can't use this file"
    }

    /** Download progress in plain words from DownloadManager's status, reason and byte count. */
    fun downloadText(m: BrainModel, status: Int, reason: Int, soFar: Long): String = when (status) {
        Download.STATUS_PENDING -> "Waiting to start"
        Download.STATUS_RUNNING -> String.format(Locale.US, "%s of %s (%d%%)", gb(soFar), gb(m.bytes), percent(soFar, m.bytes))
        Download.STATUS_PAUSED -> String.format(Locale.US, "Paused at %s: %s", gb(soFar), Download.reasonText(status, reason))
        Download.STATUS_FAILED -> "The download failed: ${Download.reasonText(status, reason)}. Tap Download to try again."
        Download.STATUS_SUCCESSFUL -> "Downloaded"
        else -> "Download status unknown"
    }

    /** Truncated, never rounded up: 100% only when every byte is there. [total] <= 0: 0. */
    fun percent(bytes: Long, total: Long): Int = if (total <= 0) 0 else (bytes.coerceIn(0, total) * 100 / total).toInt()

    fun gb(bytes: Long): String = String.format(Locale.US, "%.2f GB", bytes / 1e9)
}

/** Whether the voice process can load a model now, as its files say (ModelStore.plan). */
enum class ModelAvailability {
    READY,
    /** No complete file (never downloaded, or a download under way). */
    NOT_DOWNLOADED,
    /** The file is there but not checked yet (open the Robot app). */
    NOT_CHECKED,
    /** Your own file is a Tensor G5 build waiting for you to allow the TPU. */
    NEEDS_TPU_OK,
    /** The file is damaged, or your own file can't run here. */
    UNUSABLE,
}

/**
 * What :brain is asked to load: the file, where it runs, its context, the file's fingerprint (size and time), and its
 * kind (which engine runs it: a GGUF file llama.cpp). [contextTokens] under 4,096 means chat only
 * ([ContextWindow.chatOnly]), whichever engine runs it. [vision]: the file has a vision encoder (every built-in model
 * does; your own file as its check found; never a GGUF file).
 */
data class LoadPlan(
    val model: BrainModel, val path: String, val backend: ModelBackend, val contextTokens: Int, val fingerprint: String,
    val vision: Boolean = true, val format: ModelFormat = ModelFormat.LITERT_LM,
) {
    val chatOnly: Boolean get() = ContextWindow.chatOnly(contextTokens)
}

/**
 * :brain's own say before it loads a model, whatever the assistant asked for (0.4.1): it reads the app's own records
 * again rather than trust the request. A built-in model loads only where it runs, and only from the file whose SHA-256
 * the app checked (its verified mark matches the file now); your own file only as the app recorded it after its check,
 * unchanged since, and on the TPU only once you allowed it there. (The content check then reads the file's header.)
 * 0.4.3: and only with the context Robot's prompts are made for: 4,096 for a built-in model, your own file's
 * ([OwnModel.contextTokens], from its record; :brain compares the header it reads too). And only by the engine for its
 * kind of file (a GGUF file by llama.cpp, on the CPU). 0.4.7: and only where this phone can run it ([phone]): the TPU on
 * a Tensor G5 alone, the GPU only for the general E2B and only where ModelCatalogue offers it.
 */
object LoadCheck {
    const val CHANGED = "The model file changed since Robot checked it. Open the Robot app › Model to check it again."
    const val NO_TPU = "This phone has no TPU Robot can use: the TPU build runs on a Tensor G5 (Pixel 10) only."
    const val NO_GPU = "Robot doesn't use this phone's GPU."

    /** The context asked for isn't the one Robot's prompts are made for with this file. */
    fun wrongContext(expected: Int): String = "Robot's prompts are made for $expected tokens of context with this model."

    /**
     * Null when [m] may load on [backend] with a context of [maxTokens] as a file of [format]; else why not, in plain
     * words. [fingerprintNow]: the file's now ([ModelFiles.fingerprint]); [mark]: the built-in model's verified mark;
     * [ownRecord]: your own file's record.
     */
    fun refuse(m: BrainModel, backend: ModelBackend, fingerprintNow: String, mark: String?, ownRecord: String?,
        maxTokens: Int = ContextWindow.MAX_TOKENS, format: ModelFormat = ModelFormat.LITERT_LM, phone: Phone? = null): String? {
        if (phone != null) {
            if (backend == ModelBackend.TPU && phone.tpu != TpuSupport.SUPPORTED) return NO_TPU
            if (backend == ModelBackend.GPU && !ModelCatalogue.gpuOffered(phone)) return NO_GPU
        }
        if (m.builtIn) {
            if (format != ModelFormat.LITERT_LM) return "“${m.title}” is a LiteRT-LM file."
            if (!m.runsOn(backend)) return "“${m.title}” runs on the ${m.backend.title} only."
            if (maxTokens != m.contextTokens) return wrongContext(m.contextTokens)
            return if (mark?.trim() == "${m.sha256}|$fingerprintNow") null else CHANGED
        }
        val own = OwnModel.fromJson(ownRecord) ?: return CHANGED
        if (own.fingerprint != fingerprintNow) return CHANGED
        // A record of the other kind of file: whatever is there now wasn't checked as what it is.
        if (own.format != format) return CHANGED
        val where = own.backend
        return when {
            where == backend -> if (maxTokens == own.contextTokens) null else wrongContext(own.contextTokens)
            where == null && own.tensorG5Build -> "Your model file waits for you to allow the TPU in the Robot app › Model."
            where == null -> own.unusable ?: "Robot can't run your model file on this phone."
            else -> "Your model file runs on the ${where.title}, not the ${backend.title}."
        }
    }
}

/**
 * One factual line for a model whose name says MedGemma (0.4.3; your own file's name, or a catalogue entry's): shown on
 * Settings › Model, never added to answers (Settings › "Direct answers" stays as you set it). The name is for the
 * screen only: it never decides what Robot trusts or allows.
 */
object ModelNotes {
    const val MEDICAL = "Not a medical device: check health information with a professional."

    /** "MedGemma", "medgemma-1.5-4b-it…", "Med-Gemma", "med_gemma": any case, any separators. */
    fun medical(name: String?): Boolean = name != null && name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.contains("medgemma")

    fun of(name: String?): String? = if (medical(name)) MEDICAL else null
}

/** Why Robot used Fast instead of the model you chose (the voice log stores [id]). */
enum class FallbackWhy(override val id: Int, override val title: String) : LogCode {
    NOT_DOWNLOADED(1, "not downloaded"), NOT_CHECKED(2, "not checked yet"), NEEDS_TPU_OK(3, "waits for you to allow the TPU"),
    UNUSABLE(4, "can't be used"), LOAD_FAILED(5, "didn't load"), FAILED_EARLIER(6, "didn't load earlier"),
    /** Its model process stopped while it was in memory (a native crash, or Android reclaiming its memory). */
    STOPPED(7, "stopped unexpectedly"),
}

/** Why a model didn't load (BrainMsg.Loaded, and the voice log stores [id]). */
enum class LoadFailure(override val id: Int, override val title: String) : LogCode {
    NO_FILE(1, "file missing"), NOT_ALLOWED(2, "refused by Robot's check of the file"), NO_SPACE(3, "no room for its cache"),
    ENGINE(4, "engine error"), STOPPING(5, "the last model was still stopping"), TIMEOUT(6, "no answer in time"),
    PROCESS(7, "the model process stopped"),
}

/**
 * Which model the assistant loads (pure, tested): the one you chose when it is ready, else Fast with a message that
 * says why, so Robot is never left without a model because of a choice. A model that failed to load in this process, or
 * whose model process stopped while it was in memory, is skipped until you choose it again (any save of the settings)
 * or its file changes: never loaded again and again until the crash lockout stops Fast too.
 */
object ModelChoice {
    sealed class Decision {
        /** Load [model]; [instead] and [why] say it replaces the one you chose. */
        data class Load(val model: BrainModel, val instead: BrainModel? = null, val why: FallbackWhy? = null) : Decision()
        /** Nothing can load now: [problem] in plain words; [openApp]: the fix is in the Robot app (a download, a check). */
        data class NoLoad(val problem: String, val openApp: Boolean) : Decision()
    }

    const val NO_MODEL = "Robot's model isn't on this phone yet. Open the Robot app to download it."

    /**
     * [chosenFailedBefore]: [chosen] failed to load earlier in this process, or stopped it ([earlier] says which; Fast is
     * never skipped: it is tried every time, as before 0.4.1).
     */
    fun decide(
        chosen: BrainModel, chosenState: ModelAvailability, fastState: ModelAvailability, chosenFailedBefore: Boolean,
        earlier: FallbackWhy = FallbackWhy.FAILED_EARLIER,
        /** This phone's Fast (0.4.7, ModelCatalogue.fast): the TPU build on a Tensor G5, the general build elsewhere. */
        fast: BrainModel = BrainModel.FAST,
    ): Decision {
        if (chosen == fast) {
            return if (fastState == ModelAvailability.READY) Decision.Load(fast) else Decision.NoLoad(NO_MODEL, openApp = true)
        }
        val why = when {
            chosenState != ModelAvailability.READY -> whyFor(chosenState)
            chosenFailedBefore -> earlier
            else -> return Decision.Load(chosen)
        }
        return if (fastState == ModelAvailability.READY) Decision.Load(fast, chosen, why)
        else Decision.NoLoad(alone(chosen, why), openApp = true)
    }

    /** After [tried] failed to load: Fast instead (when it is ready and wasn't the one), else nothing. */
    fun afterFailure(tried: BrainModel, failure: LoadFailure, fastState: ModelAvailability, detail: String? = null,
        fast: BrainModel = BrainModel.FAST): Decision = when {
        // The last engine is still being let go of: nothing is wrong with the model, ask again in a moment.
        failure == LoadFailure.STOPPING -> Decision.NoLoad(STOPPING, openApp = false)
        tried != fast && fastState == ModelAvailability.READY -> Decision.Load(fast, tried, FallbackWhy.LOAD_FAILED)
        tried == fast -> Decision.NoLoad(detail ?: FAST_FAILED, openApp = false)
        else -> Decision.NoLoad(alone(tried, FallbackWhy.LOAD_FAILED), openApp = true)
    }

    /** A failure worth remembering (so the next question doesn't wait for the same failure). */
    fun remember(failure: LoadFailure): Boolean = failure != LoadFailure.STOPPING

    private fun whyFor(a: ModelAvailability): FallbackWhy = when (a) {
        ModelAvailability.NOT_DOWNLOADED -> FallbackWhy.NOT_DOWNLOADED
        ModelAvailability.NOT_CHECKED -> FallbackWhy.NOT_CHECKED
        ModelAvailability.NEEDS_TPU_OK -> FallbackWhy.NEEDS_TPU_OK
        ModelAvailability.UNUSABLE, ModelAvailability.READY -> FallbackWhy.UNUSABLE
    }

    const val STOPPING = "The model is still stopping. Try again in a moment."
    const val FAST_FAILED = "The model didn't load in time."

    /** "“Smarter, slower” …" with "you" in it: what happened, then what you can do. */
    fun fallbackText(wanted: BrainModel, why: FallbackWhy, detail: String? = null): String {
        val name = "“${wanted.title}”"
        return when (why) {
            FallbackWhy.NOT_DOWNLOADED -> "$name isn't on this phone yet, so Robot is using Fast. " +
                (if (wanted.builtIn) "Download it" else "Import it") + " in the Robot app › Model."
            FallbackWhy.NOT_CHECKED -> "$name hasn't been checked yet, so Robot is using Fast. Open the Robot app › Model to check it."
            FallbackWhy.NEEDS_TPU_OK -> "$name waits for you to allow the TPU in the Robot app › Model, so Robot is using Fast."
            FallbackWhy.UNUSABLE -> "Robot can't use $name (the Robot app › Model says why), so Robot is using Fast."
            FallbackWhy.LOAD_FAILED -> "$name didn't load" + (detail?.let { " ($it)" } ?: "") + ", so Robot is using Fast."
            FallbackWhy.FAILED_EARLIER -> "$name didn't load earlier, so Robot is using Fast. Tap it again in the Robot app › Model to retry."
            FallbackWhy.STOPPED -> "$name stopped unexpectedly, so Robot is using Fast. Tap it again in the Robot app › Model to retry."
        }
    }

    /** No Fast to fall back on either. */
    private fun alone(wanted: BrainModel, why: FallbackWhy): String = fallbackText(wanted, why).substringBefore(", so Robot is using Fast") +
        ", and Fast isn't on this phone either. Open the Robot app › Model."
}
