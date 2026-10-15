// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.SecretRead
import app.heyrobot.sys.HelperKeyFiles

// VOICE PROCESS. Each helper's API key (docs/ask-claude.md "Key and settings", every helper since 0.6.0): pasted once
// into HelperConnectionActivity, which runs in the :voice process like the panel, encrypted with an Android Keystore key
// of its own (AES-GCM, not exportable; sys/KeystoreSecret.kt, the class the updater's token shares), and decrypted only
// here, just before the helper's client is made or Test connection runs. It never appears in a log, a crash text, the
// history, an intent or an IPC message: the main app only reads whether one is saved and its masked form
// (sys/HelperFiles.kt). The window is FLAG_SECURE (no screenshots, recordings or task thumbnail), kept out of autofill,
// and clears the clipboard if it still holds the key just saved. Removing a helper deletes its Keystore key, so a copy of
// the file could never be read again. A key the Keystore can't read just now is kept, never removed (only one that can
// never be decrypted again is).

object HelperKeys {
    /** Encrypts and saves [key] for helper [id] ([masked]: what Settings shows); false (nothing saved) if it can't. Background thread. */
    fun save(ctx: Context, id: String, key: String, masked: String): Boolean = HelperKeyFiles.store(id).save(ctx, key, masked)

    /**
     * Helper [id]'s key ([SecretRead.Present]), or none saved. One that can never be decrypted again
     * ([SecretRead.Invalidated]: its Keystore key gone or replaced, the file damaged) is removed as it is read, and Settings
     * says why; one that couldn't be read just now ([SecretRead.Unavailable]: the Keystore busy or locked, an I/O error) is
     * kept, file and Keystore key, and the next question reads it again. Background thread.
     */
    fun read(ctx: Context, id: String): SecretRead = HelperKeyFiles.store(id).read(ctx)

    /** Changes when the key file does: a cached client is rebuilt (or dropped) on the next turn. */
    fun stamp(ctx: Context, id: String): Long = HelperKeyFiles.store(id).stamp(ctx)

    /** Helper [id]'s key was just pasted into a window with the focus: the clipboard is cleared if it still holds exactly it. */
    fun clearClipboard(ctx: Context, id: String, key: String) = HelperKeyFiles.store(id).clearClipboard(ctx, key)
}
