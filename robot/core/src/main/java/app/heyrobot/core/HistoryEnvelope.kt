// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.MemoryCipher
import kotlin.text.Charsets.UTF_8

/** What opening one sealed History file gave (the app's sys/HistoryVault says what each means to you). */
sealed class HistoryOpen {
    /** [base]: in a journal, how many turns of the sealed conversation come before these (0: the whole conversation). */
    data class Ok(val conversation: StoredConversation, val base: Int = 0) : HistoryOpen()
    /** The key needs the phone unlocked, or the Keystore is busy: not now, never a reason to move or drop the file. */
    data object Locked : HistoryOpen()
    /** Android invalidated the key (the screen lock removed, say), or there is no key under the alias: it never opens again. */
    data object KeyGone : HistoryOpen()
    /** Damaged, cut, sealed by another key, or not History's file. */
    data object Unreadable : HistoryOpen()
    /** Written by a newer Robot: left as it is. */
    data class TooNew(val version: Int) : HistoryOpen()
}

/**
 * One conversation sealed on its own (seal-history): "RHIS", a version byte, the key alias's length and the alias, a
 * journal's base (4 bytes, big-endian: the sealed conversation's turns that come before the journal's; 0 otherwise), then
 * the cipher's output (its IV, the AES-256-GCM text and tag) of the conversation's JSON. The additional data binds the
 * file's name, so a sealed conversation renamed to another id, or a journal entry renamed to a sealed file, never opens,
 * and the header before the cipher's output, so a changed alias or version byte fails the tag (seal-history-fix).
 */
object HistoryEnvelope {
    /** 2: the layout with the journal's base (seal-history-fix); 1 was the test builds' before it (seal-history-fix-b 7). */
    const val VERSION = 2
    /** A conversation sealed under History's unlock-only key. */
    const val SEALED = SealedEnvelope.SEALED
    /** The same, written while the phone was locked, under History's lock-screen key: merged at the next unlock. */
    const val LOCKED = SealedEnvelope.LOCKED
    /** The plain file of Robot 1.2 and earlier, sealed at the first unlock and then removed. */
    const val PLAIN = SealedEnvelope.PLAIN
    /** The framing, shared with Notes (seal-notes). */
    val frame = SealedEnvelope("RHIS", "history", VERSION) // l10n: not text (the file's magic and the additional data's kind)

    fun aad(fileName: String, header: ByteArray): ByteArray = frame.aad(fileName, header)

    fun seal(c: StoredConversation, cipher: MemoryCipher, fileName: String, base: Int = 0): ByteArray =
        frame.seal(ConversationCodec.toJson(c).toByteArray(UTF_8), cipher, fileName, base)

    /** The key alias in [bytes]' header, or null when it isn't a History file. */
    fun alias(bytes: ByteArray?): String? = frame.alias(bytes)

    /**
     * [bytes] (a file named [fileName]) opened with the cipher [cipherFor] gives for the header's alias: null, no such key;
     * a [MemoryKeyException] says which problem. Null or empty bytes are [HistoryOpen.Unreadable] (a sealed file is never empty).
     */
    fun open(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?, fileName: String): HistoryOpen =
        when (val o = frame.open(bytes, cipherFor, fileName) { ConversationCodec.fromJson(String(it, UTF_8)) }) {
            is SealedOpen.Ok -> HistoryOpen.Ok(o.value, o.base)
            SealedOpen.Locked -> HistoryOpen.Locked
            SealedOpen.KeyGone -> HistoryOpen.KeyGone
            SealedOpen.Unreadable -> HistoryOpen.Unreadable
            is SealedOpen.TooNew -> HistoryOpen.TooNew(o.version)
        }

    /** The conversation id a History file's [name] holds, and whether it is a journal entry; null for any other name. */
    fun idOf(name: String): Pair<String, Boolean>? = frame.idOf(name)
}
