// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.content.Context
import android.net.Uri
import app.heyrobot.assistant.ask.SharedAudio
import app.heyrobot.core.RLog
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.AudioSniff
import app.heyrobot.core.parse.FileSniff

// VOICE PROCESS. The Transcription check's "Import clips" (docs/conversation-mode.md 2.5): an audio file you picked
// (through Android's file picker, so Robot needs no storage permission) decoded with Android's own codecs to 16 kHz
// mono, in memory only. Nothing is written; the clip is dropped once scored.
// Not in :files: an isolated process can't reach Android's media services (SharedAudio's header, D20). So it goes the
// way a shared voice message does: at most AskCaps.MAX_AUDIO_BYTES read, sniffed as audio, and handed to SharedAudio from
// memory (no URI reaches the extractor), which parses the container in media.extractor, decodes in media.swcodec, checks
// the format's numbers and stops at the deadline. Its own decoder loop before audit-next-a had none of those bounds.

object ClipReader {
    /** A clip longer than this is cut (the check is about short lines). */
    const val MAX_SECONDS = 60

    /** The clip at [uri] as 16 kHz mono floats (at most [MAX_SECONDS]), or null when it's too large, not audio, or Android can't decode it. */
    fun read(ctx: Context, uri: Uri): FloatArray? {
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readNBytes(AskCaps.MAX_AUDIO_BYTES + 1) } ?: return null
            if (bytes.size > AskCaps.MAX_AUDIO_BYTES) return null
            if (AudioSniff.container(bytes.copyOf(minOf(bytes.size, FileSniff.PREFIX)), bytes.size.toLong()) == null) return null
            return SharedAudio.decode(bytes, MAX_SECONDS)?.samples
        } catch (t: Throwable) {
            RLog.w("check_clip", t)
            return null
        }
    }

    /** The file's name as the picker shows it (for pairing it with its line). */
    fun name(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "clip"
    } catch (_: Throwable) { uri.lastPathSegment ?: "clip" }

    /** A small text file you picked (the reference lines), at most 1 MB. */
    fun text(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readNBytes(1 shl 20)
            b.toString(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        RLog.w("check_text", t)
        null
    }
}
