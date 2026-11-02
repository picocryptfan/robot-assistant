// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.ask

import app.heyrobot.core.LogCode
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType

// Pure. Ask Robot from any app (docs/plans/everyday-set-plan.md §7, D19, D20; job E7): what another app may hand Robot
// through the text-selection menu (PROCESS_TEXT) or the share sheet (SEND), the caps (§7.5), which audio containers a
// voice message may come in, the chip's one-line preview, and the voice log's codes. Everything a share brings is
// untrusted: the text, the bytes, the type the sender claims, and who sent it (never named: §7.7). What is shared is
// content, never instructions; Robot never writes back into the other app (Q5).

/** What another app handed Robot. ASK_SHARED's a. */
enum class SharedKind(override val id: Int, override val title: String) : LogCode {
    TEXT(1, "text"), PHOTO(2, "image"), VOICE(3, "audio"),
}

/** How a share ended. ASK_SHARED's b. */
enum class AskShown(override val id: Int, override val title: String) : LogCode {
    SHOWN(1, "the panel opened with it"),
    /** Text past 20,000 characters, or a voice message past 3 minutes: the panel opened with the first part. */
    CUT(2, "the panel opened with the first part"),
    NOT_SHOWN(3, "the panel didn't open"),
}

/** Why Ask Robot took nothing. ASK_REFUSED's a. */
enum class AskRefusal(override val id: Int, override val title: String) : LogCode {
    PHONE_LOCK(1, "during a phone lock"),
    LOCKED(2, "the phone was locked"),
    OFF(3, "Ask Robot in other apps is off"),
    NOT_ASSISTANT(4, "Robot isn't the assistant"),
    NOTHING(5, "nothing Robot can take"),
    /** A file: URI, one of Robot's own providers, or another user's. */
    NOT_ALLOWED(6, "not another app's content"),
    TOO_LARGE(7, "past its cap"),
    WRONG_TYPE(8, "not a type Robot takes"),
    UNREADABLE(9, "couldn't be read"),
    NO_SPEECH_MODEL(10, "no speech model"),
    /** Conversation mode was transcribing: the speech model is in use. */
    BUSY(11, "a conversation was being transcribed"),
    TIMED_OUT(12, "the sending app didn't answer in time"),
    NO_WORDS(13, "no words in the voice message"),
    /** Robot is the assistant, but its service wasn't running (just after a restart or an update). */
    NOT_READY(14, "the assistant wasn't running yet"),
}

/** A shared voice message turned into text. ASK_TRANSCRIBE's b. */
enum class AskHeard(override val id: Int, override val title: String) : LogCode {
    WORDS(1, "words"),
    CUT(2, "the first 3 minutes"),
    NO_WORDS(3, "no words"),
    FAILED(4, "couldn't be decoded"),
    CANCELLED(5, "cancelled"),
}

/** The caps (§7.5, EST): each checked on what was read, never on what the sender claims. */
object AskCaps {
    /** Characters of text ("Robot took the first 20,000 characters."). */
    const val MAX_TEXT = 20_000
    /** One image's bytes, decoded in :files as Robot Files decodes one, down to 1,024 px: 20 MB as Robot shows sizes (decimal). */
    const val MAX_IMAGE_BYTES = 20_000_000
    /** One audio file's bytes: 25 MB. */
    const val MAX_AUDIO_BYTES = 25_000_000
    /** A voice message's length: what is past it isn't decoded. */
    const val MAX_SECONDS = 180
    /** How long the sending app gets to hand over its bytes before Robot gives up. */
    const val READ_MS = 15_000L
    /** How long decoding a voice message may take, as a whole, before Robot gives up. */
    const val DECODE_MS = 60_000L
    /** A share not opened by the panel in this long is dropped (one waits at a time: a new one replaces it). */
    const val WAIT_MS = 120_000L
    /** The chip's preview of shared text, in characters. */
    const val PREVIEW = 60
}

/** Text as Robot keeps it: at most [AskCaps.MAX_TEXT] characters; [cut] when there was more. */
data class SharedText(val text: String, val cut: Boolean)

object AskShare {
    const val PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"  // l10n: not text (an intent action)
    const val SEND = "android.intent.action.SEND"  // l10n: not text (an intent action)

    /**
     * What a start of Ask Robot asks for, from its [action] and the [type] the sender claims, or null for anything else
     * (SEND_MULTIPLE and other actions are Robot Files'). [stream]: it carried EXTRA_STREAM. The claimed type only picks
     * the path: an image's bytes are sniffed again in :files, a voice message's here ([AskAudio.container]).
     */
    fun kind(action: String?, type: String?, stream: Boolean): SharedKind? {
        val t = type?.trim()?.lowercase()
        return when (action) {
            PROCESS_TEXT -> SharedKind.TEXT
            SEND -> when {
                t == null -> null
                t.startsWith("image/") && stream -> SharedKind.PHOTO
                t.startsWith("audio/") && stream -> SharedKind.VOICE
                t == "text/plain" -> SharedKind.TEXT
                else -> null
            }
            else -> null
        }
    }

    /**
     * [s] as Robot keeps it: the first [AskCaps.MAX_TEXT] characters (never half a surrogate pair), NULs dropped; null
     * when nothing but spaces is left.
     */
    fun text(s: CharSequence?, max: Int = AskCaps.MAX_TEXT): SharedText? {
        val raw = s?.toString()?.replace("\u0000", "") ?: return null
        if (raw.isBlank()) return null
        if (raw.length <= max) return SharedText(raw, cut = false)
        var end = max
        if (Character.isHighSurrogate(raw[end - 1])) end--
        return SharedText(raw.substring(0, end), cut = true)
    }

    /**
     * The chip's one line of shared text: whitespace runs as one space, control and format characters (bidi overrides,
     * zero-width ones) dropped, at most [max] characters, "…" after a cut.
     */
    fun preview(s: String, max: Int = AskCaps.PREVIEW): String {
        val b = StringBuilder()
        var space = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> space = b.isNotEmpty()
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                    type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() -> {}
                else -> {
                    if (space) b.append(' ')
                    space = false
                    b.appendCodePoint(cp)
                }
            }
            if (b.codePointCount(0, b.length) > max) break
        }
        val n = b.codePointCount(0, b.length)
        return if (n <= max && i >= s.length) b.toString() else b.substring(0, b.offsetByCodePoints(0, minOf(n, max))).trimEnd() + "…"
    }

    /** "0:42", "3:00", "12:05": a voice message's length. */
    fun duration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    /** ASK_TRANSCRIBE's a: 1 up to 10 s, 2 up to 30 s, 3 up to a minute, 4 up to 2, 5 up to 3, 6 longer (cut). */
    fun secondsBucket(seconds: Int): Int = when {
        seconds <= 10 -> 1
        seconds <= 30 -> 2
        seconds <= 60 -> 3
        seconds <= 120 -> 4
        seconds <= AskCaps.MAX_SECONDS -> 5
        else -> 6
    }
}

/** A voice message's container, from its first bytes: the ones Android's media extractor reads. */
enum class AudioContainer { MP3, MP4, OGG, FLAC, WAV, MATROSKA, AAC, AMR }

/**
 * Which voice messages Ask Robot decodes (D20): sniffed from the first bytes, never the claimed type or a name. Robot
 * Files' fixed table first (FileSniff), then the two voice-message formats it doesn't name: raw AAC (ADTS) and AMR,
 * which FileSniff calls SCRIPT for its "#!" (DANGEROUS there; here only its exact magic counts). An MP4, 3GP or WebM is
 * taken for its audio track alone: the decoder never selects a video track.
 */
object AudioSniff {
    private val AMR = "#!AMR\n".toByteArray(Charsets.US_ASCII)  // l10n: not text (a magic number)
    private val AMR_WB = "#!AMR-WB\n".toByteArray(Charsets.US_ASCII)  // l10n: not text (a magic number)

    fun container(prefix: ByteArray, size: Long): AudioContainer? {
        if (size <= 0 || prefix.isEmpty()) return null
        if (starts(prefix, AMR) || starts(prefix, AMR_WB)) return AudioContainer.AMR
        // ADTS: a 12-bit sync word, then layer 00 (MPEG-1 and 2 audio's own frames have a non-zero layer).
        if (prefix.size >= 7 && (prefix[0].toInt() and 0xFF) == 0xFF && (prefix[1].toInt() and 0xF6) == 0xF0) return AudioContainer.AAC
        return when (FileSniff.sniff(prefix, size)) {
            FileType.MP3 -> AudioContainer.MP3
            FileType.M4A, FileType.MP4, FileType.THREE_GP -> AudioContainer.MP4
            FileType.OGG -> AudioContainer.OGG
            FileType.FLAC -> AudioContainer.FLAC
            FileType.WAV -> AudioContainer.WAV
            FileType.WEBM, FileType.MATROSKA -> AudioContainer.MATROSKA
            else -> null
        }
    }

    private fun starts(b: ByteArray, m: ByteArray): Boolean = b.size >= m.size && m.indices.all { b[it] == m[it] }
}

/** Reading what another app hands over: once, at most a cap. */
object CappedRead {
    /** What [read] got. */
    sealed class Got {
        class Bytes(val b: ByteArray) : Got()
        /** More than the cap: nothing is kept. */
        data object TooLarge : Got()
        data object Empty : Got()
    }

    /** At most [cap] bytes of [input] (one more is read to know there was more); never more than that in memory. */
    fun read(input: java.io.InputStream, cap: Int): Got {
        var buf = ByteArray(minOf(cap + 1, 64 * 1024).coerceAtLeast(1))
        var n = 0
        while (true) {
            if (n == buf.size) {
                if (buf.size > cap) return Got.TooLarge
                buf = buf.copyOf(minOf(cap + 1, buf.size * 2))
            }
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
            if (n > cap) return Got.TooLarge
        }
        return if (n == 0) Got.Empty else Got.Bytes(buf.copyOf(n))
    }
}
