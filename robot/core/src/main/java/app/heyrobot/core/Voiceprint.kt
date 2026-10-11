package app.heyrobot.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Pure. Your "me" voiceprint (docs/conversation-mode.md 3.3, 8; phase 5.3): the average of the voice embeddings of a few
// sentences you read, made by the speaker model on the phone, so your lines in a transcript say "You". It is biometric
// data: optional, made only when you ask (Settings › Conversation › Your voice), kept in one file of its own that only
// the :voice process writes and reads, encrypted with an Android Keystore key (AES-GCM, not exportable), never in
// settings (where only a flag says it exists), never in a log, never sent anywhere, and deleted with one button. This
// file is the format: the plain voiceprint, and the sealed file around it. The encryption itself is the Keystore's
// ([VoiceprintCodec.Sealer], in the :voice process); the tests use a stand-in.

/** Your voiceprint: [vector] (unit length) from [model]'s embeddings (a VoiceBundle id), averaged over [clips] recordings. */
class Voiceprint(val model: String, val vector: FloatArray, val clips: Int) {
    override fun equals(other: Any?): Boolean = other is Voiceprint && other.model == model && other.clips == clips && other.vector.contentEquals(vector)

    override fun hashCode(): Int = (model.hashCode() * 31 + clips) * 31 + vector.contentHashCode()

    /** Never the numbers (this may end up in a crash text). */
    override fun toString(): String = "Voiceprint($model, ${vector.size} dims, $clips clips)"
}

object VoiceprintCodec {
    /** In the voice process's own folder (filesDir/robot), next to the encrypted Claude key. */
    const val FILE = "me.voiceprint"
    /** The Android Keystore key that encrypts it (AES-256-GCM, not exportable), deleted with the file. */
    const val KEY_ALIAS = "robot_me_voiceprint"

    private val PLAIN = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'P'.code.toByte(), 1)
    private val SEALED = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), 1)
    const val MIN_DIM = 16
    const val MAX_DIM = 1024
    private const val MAX_MODEL = 64

    /**
     * The encryption around the plain voiceprint: [seal] gives the IV and the ciphertext (with its tag), [open] gives the
     * plain bytes back or throws when the key or the bytes are wrong. [aad] is authenticated, not encrypted.
     */
    interface Sealer {
        fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>
        fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray
    }

    /**
     * The plain voiceprint: "RVP" 1, the model id (1 byte of length, ASCII), the clip count and the dimension (2 bytes
     * each), then the numbers (float32), big-endian. 192 numbers make 768 bytes of them.
     */
    fun encode(v: Voiceprint): ByteArray {
        val model = v.model.toByteArray(Charsets.US_ASCII)
        require(model.size in 1..MAX_MODEL && v.vector.size in MIN_DIM..MAX_DIM && v.clips in 1..0xFFFF)
        val b = ByteBuffer.allocate(PLAIN.size + 1 + model.size + 4 + 4 * v.vector.size).order(ByteOrder.BIG_ENDIAN)
        b.put(PLAIN).put(model.size.toByte()).put(model).putShort(v.clips.toShort()).putShort(v.vector.size.toShort())
        for (x in v.vector) b.putFloat(x)
        return b.array()
    }

    /** The voiceprint in [bytes], or null when they aren't one (another format, cut short, not finite, not unit length). */
    fun decode(bytes: ByteArray): Voiceprint? = try {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(PLAIN.size).also { b.get(it) }
        if (!magic.contentEquals(PLAIN)) null else {
            val n = b.get().toInt() and 0xFF
            val model = String(ByteArray(n).also { b.get(it) }, Charsets.US_ASCII)
            val clips = b.short.toInt() and 0xFFFF
            val dim = b.short.toInt() and 0xFFFF
            if (n == 0 || dim !in MIN_DIM..MAX_DIM || clips == 0 || b.remaining() != 4 * dim) null else {
                val v = FloatArray(dim) { b.float }
                val norm = Math.sqrt(v.sumOf { it.toDouble() * it })
                if (v.any { !it.isFinite() } || Math.abs(norm - 1.0) > 1e-3) null else Voiceprint(model, v, clips)
            }
        }
    } catch (_: Exception) { null }

    /** The file: "RVE" 1, the IV's length and the IV, then the sealed plain voiceprint. The header is authenticated. */
    fun seal(v: Voiceprint, sealer: Sealer): ByteArray {
        val plain = encode(v)
        try {
            val (iv, ct) = sealer.seal(plain, SEALED)
            require(iv.size in 1..32 && ct.isNotEmpty())
            return SEALED + byteArrayOf(iv.size.toByte()) + iv + ct
        } finally {
            plain.fill(0)
        }
    }

    /** The voiceprint in a sealed [file], or null (not this format, or it won't open: the key is gone or the bytes changed). */
    fun open(file: ByteArray, sealer: Sealer): Voiceprint? = try {
        if (file.size < SEALED.size + 2 || !file.copyOfRange(0, SEALED.size).contentEquals(SEALED)) null else {
            val n = file[SEALED.size].toInt() and 0xFF
            val start = SEALED.size + 1
            if (n == 0 || n > 32 || file.size <= start + n) null else {
                val plain = sealer.open(file.copyOfRange(start, start + n), file.copyOfRange(start + n, file.size), SEALED)
                try { decode(plain) } finally { plain.fill(0) }
            }
        }
    } catch (_: Exception) { null }
}

/** "Your voice": the sentences you read, and whether the recordings make one voiceprint. */
object Enrolment {
    /** Recordings to read. */
    const val CLIPS = 3
    /** A recording shorter than this is read again. */
    const val MIN_CLIP_MS = 3_000
    /** And one is stopped at this length. */
    const val MAX_CLIP_MS = 12_000
    /**
     * Each recording must be at least this close (cosine) to the average of the others, or they don't sound like one
     * voice (someone else spoke, or there was too much noise). The session joins a speaker at 0.45.
     */
    const val AGREE = 0.45f

    val SENTENCES_EN = listOf(
        "On a quiet morning I like to walk to the market and buy fresh bread and a few apples.",
        "My phone keeps what I say on this device, and it writes down who is speaking at the table.",
        "Yesterday the weather changed twice: first a bright blue sky, then heavy rain until the evening.",
    )
    val SENTENCES_FR = listOf(
        "Le matin, j'aime marcher jusqu'au marché pour acheter du pain frais et quelques pommes.",
        "Mon téléphone garde ce que je dis sur l'appareil et note qui parle autour de la table.",
        "Hier, le temps a changé deux fois : d'abord un beau ciel bleu, puis une grosse pluie jusqu'au soir.",
    )

    /** The sentences in your language ("fr" or anything else for English). */
    fun sentences(lang: String): List<String> = if (lang == "fr") SENTENCES_FR else SENTENCES_EN

    sealed class Result {
        data class Ok(val voiceprint: Voiceprint) : Result()
        /** Fewer than [CLIPS] usable recordings. */
        object TooFew : Result()
        /** The recordings don't sound like one voice. */
        object NotAlike : Result()
    }

    /** One voiceprint from [embeddings] (one per recording) of [model]. */
    fun make(model: String, embeddings: List<FloatArray>): Result {
        val es = embeddings.filter { e -> e.size in VoiceprintCodec.MIN_DIM..VoiceprintCodec.MAX_DIM && e.all { it.isFinite() } }
            .map(SpeakerClusterer::unit).filter { e -> e.any { it != 0f } }
        if (es.size < CLIPS || es.any { it.size != es[0].size }) return Result.TooFew
        for (i in es.indices) {
            val rest = mean(es.filterIndexed { j, _ -> j != i })
            if (SpeakerClusterer.dot(es[i], rest) < AGREE) return Result.NotAlike
        }
        return Result.Ok(Voiceprint(model, mean(es), es.size))
    }

    private fun mean(es: List<FloatArray>): FloatArray = SpeakerClusterer.unit(FloatArray(es[0].size) { i -> es.sumOf { it[i].toDouble() }.toFloat() })
}
