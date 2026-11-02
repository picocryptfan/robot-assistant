// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnStore
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.core.skills.SkillStep
import org.json.JSONObject
import java.io.File

// Main and voice processes. Skills connections' files (docs/plans/skills-connections-plan.md §3, §5.2; C3), without any
// key: connections.json (core ConnStore, under skills.lock, both processes) and each connection's key file. A key is
// pasted, encrypted and decrypted in the :voice process only (assistant/connect/ConnKeys.kt, through KeystoreSecret);
// the main app reads whether one is saved and its masked form, and deletes it (Remove connection, a changed address),
// never reads one itself.

/** Your connections' file, files/robot/connections.json (core ConnStore). */
object ConnFiles {
    fun store(ctx: Context): ConnStore = ConnStore(Paths.dir(ctx))

    /**
     * C11, Settings › Skills › Connections' Start over (background thread): a connections.json Robot can't read is kept
     * aside as connections.json.bad, and every key it leaves without a connection is deleted with its Keystore key. False
     * when nothing started over (a newer Robot's file stays, with its keys).
     */
    fun startOver(ctx: Context): Boolean {
        val gone = store(ctx).startOver(ConnKeyFiles.ids(ctx)) ?: return false
        for (id in gone) ConnKeyFiles.removeAll(ctx, id)
        return true
    }
}

/**
 * Where a connection's secrets live: files/robot/connections/<id>/key.bin (IV and ciphertext, :voice only) and key.json
 * (each secret part's masked form, "…AB12", and when it was saved), encrypted with the Android Keystore key
 * robot_conn_<id> (a [KeystoreSecret], logged as conn_key). The plain form is core ConnSecretsCodec's (every part in one
 * file). Only ConnKeys (the :voice process) reads or saves through [store] (ProcessIsolationTest).
 */
object ConnKeyFiles {
    /** The log lines' name (conn_key, conn_key_save, conn_key_read, …). */
    const val LOG = "conn_key" // l10n: not text (a log event's name)

    fun alias(id: String): String = "robot_conn_" + checked(id) // l10n: not text (a Keystore alias)

    /** The connection's own folder. */
    fun dir(ctx: Context, id: String): File = File(File(Paths.dir(ctx), DIR), checked(id))

    /** Connection [id]'s key. Only ConnKeys reads, saves or clears the clipboard through it. */
    fun store(id: String): KeystoreSecret = KeystoreSecret(alias(id), "key", LOG) { dir(it, id) } // l10n: not text (a file name)

    fun saved(ctx: Context, id: String): Boolean = store(id).saved(ctx)

    /** A saved key could never be read again and was removed (Settings says so until a new one is saved). */
    fun unreadable(ctx: Context, id: String): Boolean = store(id).unreadable(ctx)

    /** Each saved part's masked form ("…AB12"), as Settings and a preview show it (core ConnPreview); empty when none is saved. */
    fun masks(ctx: Context, id: String): Map<SecretPart, String> = if (!saved(ctx, id)) emptyMap() else readMasks(store(id).masked(ctx))

    /**
     * Delete key (background thread): the files and the Keystore key go, so a copy of the file could never be read again.
     * The main app calls it before a changed address is saved (as for helpers), and for Remove connection ([removeAll]).
     */
    fun delete(ctx: Context, id: String) = store(id).delete(ctx)

    /** The connections with a key folder on this phone, by id (Start over deletes those left without a connection). */
    fun ids(ctx: Context): Set<String> =
        File(Paths.dir(ctx), DIR).list().orEmpty().filterTo(HashSet()) { SkillStep.validConnId(it) }

    /** Remove connection (background thread): its key (crypto-shredded with the Keystore key) and its folder. */
    fun removeAll(ctx: Context, id: String) {
        delete(ctx, id)
        dir(ctx, id).deleteRecursively()
    }

    /** What key.json keeps of [s]: each part's mask only ([ConnSecrets.masks]), never a value. */
    fun masksText(s: ConnSecrets): String = JSONObject().apply { for ((p, m) in s.masks()) put(p.id, m) }.toString()

    /** [text] (key.json's masked form) back to each part's mask; anything else (an older or damaged note) is none. */
    fun readMasks(text: String?): Map<SecretPart, String> = try {
        val o = JSONObject(text ?: return emptyMap())
        SecretPart.entries.mapNotNull { p -> o.optString(p.id).takeIf { MASK.matches(it) }?.let { p to it } }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private const val DIR = "connections" // l10n: not text (a folder name)
    /** What ConnSecrets.mask makes: "…" and at most 4 characters. */
    private val MASK = Regex("^…[\\x21-\\x7E]{0,4}$")

    private fun checked(id: String): String {
        require(SkillStep.validConnId(id)) { "connection id" } // l10n: not text (a developer's error)
        return id
    }
}

/** A connection's window (assistant/connect/ConnectionWindow, in :voice where keys are decrypted), opened by class name. */
object ConnWindow {
    const val CLASS = "app.heyrobot.assistant.connect.ConnectionWindow" // l10n: not text (a class name)
    /** The connection's id ("c-…", not secret). */
    const val EXTRA_CONN = "conn" // l10n: not text (an intent extra)
    const val EXTRA_MODE = "mode" // l10n: not text (an intent extra)
    /** Paste the connection's secrets, then Test. */
    const val MODE_KEY = "key" // l10n: not text (an intent extra's value)
    /** Test only. */
    const val MODE_TEST = "test" // l10n: not text (an intent extra's value)
    /** C9: Test, then pick a Home Assistant operation from what Test read (its pickers live only in :voice). */
    const val MODE_PICK = "pick" // l10n: not text (an intent extra's value)
    /**
     * C9: a new webhook whose address is itself the key: the window takes the whole address and saves the connection with
     * only its origin ([EXTRA_NAME] its name, not secret), so the address never passes through the main app.
     */
    const val MODE_NEW_HOOK = "new_hook" // l10n: not text (an intent extra's value)
    /** The new webhook's name (MODE_NEW_HOOK). */
    const val EXTRA_NAME = "name" // l10n: not text (an intent extra)

    /** Opens connection [id]'s window in [mode] (the main app, by class name: it never refers to the assistant's classes). False when it can't. */
    fun open(ctx: Context, id: String, mode: String, name: String? = null): Boolean = try {
        ctx.startActivity(android.content.Intent().setClassName(ctx.packageName, CLASS).putExtra(EXTRA_CONN, id).putExtra(EXTRA_MODE, mode)
            .apply { if (name != null) putExtra(EXTRA_NAME, name) }
            .apply { if (ctx !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        true
    } catch (_: Exception) {
        false
    }
}
