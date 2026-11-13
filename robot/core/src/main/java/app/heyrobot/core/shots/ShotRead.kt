// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import app.heyrobot.core.BrainMsg
import app.heyrobot.core.files.FilePrompt

// Pure. One screenshot read by Gemma's vision encoder (a FILE_READ with its picture, as Robot Files reads a page): the
// app or kind, a line of what it shows, and the names, dates and numbers in it. The model is told to write secrets as
// their kind; whatever it writes, ShotSecrets hides them again before the entry is kept. The prompt and its frame are core
// files FilePrompt's (readShot), as every file one-shot's.
object ShotRead {
    const val MAX_TEXT = 800

    /** A skipped screenshot's text (fix-screenshots-d): only that it's there; ShotAsk adds its date. */
    const val SKIPPED = "Not read: it looks like it holds a password, code or card." // l10n: not text (the model reads it)

    fun request(): BrainMsg.OneShot = FilePrompt.readShot()

    /** The model's description as an entry: trimmed, capped, its secrets hidden. Null when it wrote nothing. */
    fun entry(id: Long, at: Long, described: String): ShotEntry? {
        // A secret's label or a card anywhere: nothing of it is kept (ShotSecrets.skips); the line hiding is the second layer.
        if (described.isNotBlank() && ShotSecrets.skips(described)) return ShotEntry(id, at, SKIPPED, skipped = true)
        // Hidden first, then capped: a cut can't leave part of a secret that no longer looks like one.
        val h = ShotSecrets.hide(described.trim())
        val t = h.text.take(MAX_TEXT).trim().ifBlank { return null }
        return ShotEntry(id, at, t, h.kinds)
    }
}
