// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import app.heyrobot.assistant.HelperKeys
import app.heyrobot.assistant.WebKeys
import app.heyrobot.assistant.conversation.Voiceprints
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SecretRead
import app.heyrobot.core.VoiceprintCodec
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.helpers.HelperKeyFormat
import app.heyrobot.core.web.WebKeyFormat
import app.heyrobot.core.web.SearchService
import app.heyrobot.assistant.connect.ConnKeyBackup
import app.heyrobot.assistant.connect.ConnKeys
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.HelperKeyFiles
import app.heyrobot.sys.VoiceprintFiles
import app.heyrobot.sys.WebKeyFiles

// :VOICE PROCESS ONLY (docs/plans/backup-1.1-design.md §6.2). The only backup code that reads a key or the voiceprint
// (ProcessIsolationTest): each goes from its Keystore file straight into the backup's encryption, as one entry, and the
// copy here is zeroed once written. Never logged, never in an intent, never on disk in the clear. C10: connections' keys
// too (Connection keys, shown in Keys and private codes' row), through ConnKeys.backup and ConnKeys.restore.

internal object BackupSecrets {
    /** One secret's entry: [name] in [category] (Keys and private codes, or the voiceprint), [bytes] zeroed by [close]. */
    class Secret(val category: BackupCategory, val name: String, val bytes: ByteArray) : AutoCloseable {
        override fun close() = bytes.fill(0)
        override fun toString(): String = "Secret($name, ${bytes.size} bytes)" // l10n: not text (toString for logs and tests)
    }

    /** What [keys] found: the keys read, and the helpers whose key the Keystore couldn't read just now (by name). */
    class Keys(val secrets: List<Secret>, val unreadable: List<String>)

    /** The names of the keys a backup would carry now, without reading one: the helpers' names, then the search services'. */
    fun keyNames(ctx: Context, s: RobotSettings): List<String> =
        s.helpers.filter { BackupCategory.KEYS.validName(BackupCategory.HELPER_KEY + it.id) && HelperKeyFiles.saved(ctx, it.id) }.map { it.name } +
            SearchService.entries.filter { it.keyId != null && WebKeyFiles.saved(ctx, it) }.map { it.label }

    /** C10: your connections that have a key saved now (none decrypted), in connections.json's order. */
    fun connectionsWithKeys(ctx: Context): List<Connection> =
        ConnFiles.store(ctx).connections().filter { it.secrets.isNotEmpty() && ConnKeyFiles.saved(ctx, it.id) }

    /**
     * C10: every connection's key, read now ("conn/<id>", Connection keys). One the Keystore can't read just now is left
     * out and named by your name for the connection.
     */
    fun connectionKeys(ctx: Context): Keys {
        val out = ArrayList<Secret>()
        val unreadable = ArrayList<String>()
        for (c in connectionsWithKeys(ctx)) {
            val name = BackupCategory.connKey(c.id)
            if (!BackupCategory.CONN_KEYS.validName(name)) continue
            when (val r = ConnKeys.backup(ctx, c.id)) {
                is ConnKeyBackup.Plain -> out += Secret(BackupCategory.CONN_KEYS, name, r.bytes)
                ConnKeyBackup.Unavailable -> unreadable += c.name
                ConnKeyBackup.None -> {}
            }
        }
        return Keys(out, unreadable)
    }

    /** Every helper's and search service's key, read now. A key that can't be read just now is left out and named. */
    fun keys(ctx: Context, s: RobotSettings): Keys {
        val out = ArrayList<Secret>()
        val unreadable = ArrayList<String>()
        for (h in s.helpers) {
            val name = BackupCategory.HELPER_KEY + h.id
            if (!BackupCategory.KEYS.validName(name) || !HelperKeyFiles.saved(ctx, h.id)) continue
            when (val r = HelperKeys.read(ctx, h.id)) {
                is SecretRead.Present -> out += Secret(BackupCategory.KEYS, name, r.value.toByteArray(Charsets.UTF_8))
                is SecretRead.Unavailable -> unreadable += h.name
                else -> {}
            }
        }
        for (svc in SearchService.entries) {
            val id = svc.keyId ?: continue
            val name = BackupCategory.SEARCH_KEY + id.removePrefix("search_")
            if (!BackupCategory.KEYS.validName(name) || !WebKeyFiles.saved(ctx, svc)) continue
            when (val r = WebKeys.read(ctx, svc)) {
                is SecretRead.Present -> out += Secret(BackupCategory.KEYS, name, r.value.toByteArray(Charsets.UTF_8))
                is SecretRead.Unavailable -> unreadable += svc.label
                else -> {}
            }
        }
        return Keys(out, unreadable)
    }

    // ------------------------------------------------------------------------------------------------- restore (§6.2)

    private fun service(name: String): SearchService? =
        SearchService.entries.firstOrNull { s -> s.keyId?.let { BackupCategory.SEARCH_KEY + it.removePrefix("search_") } == name }

    /** Whether this phone has a key for the entry [name] ("helper/claude", "search/brave"): a key entered here is never replaced. */
    fun hasKey(ctx: Context, name: String): Boolean = when {
        name.startsWith(BackupCategory.HELPER_KEY) -> HelperKeyFiles.saved(ctx, name.removePrefix(BackupCategory.HELPER_KEY))
        else -> service(name)?.let { WebKeyFiles.saved(ctx, it) } ?: true
    }

    /**
     * Saves the key [bytes] of entry [name] with a new Keystore key on this phone, for a helper of [s] or a search service.
     * The bytes are zeroed by the caller. False when nothing was saved.
     */
    fun saveKey(ctx: Context, s: RobotSettings, name: String, bytes: ByteArray): Boolean {
        val key = String(bytes, Charsets.UTF_8).trim()
        if (key.isEmpty() || hasKey(ctx, name)) return false
        if (name.startsWith(BackupCategory.HELPER_KEY)) {
            val h = s.helpers.firstOrNull { it.id == name.removePrefix(BackupCategory.HELPER_KEY) } ?: return false
            return HelperKeys.save(ctx, h.id, key, HelperKeyFormat.mask(h.presetOf, key))
        }
        val svc = service(name) ?: return false
        return WebKeys.save(ctx, svc, key, WebKeyFormat.mask(key))
    }

    /** C10: whether connection [id] has a key saved here (the file only: nothing is decrypted). */
    fun hasConnectionKey(ctx: Context, id: String): Boolean = ConnKeyFiles.saved(ctx, id)

    /**
     * C10: a connection key of a backup ([bytes], its plain form, zeroed by the caller) saved for connection [id] with a fresh
     * Keystore key, only with exactly [parts] and only where none is saved. False when nothing was saved.
     */
    fun saveConnectionKey(ctx: Context, id: String, bytes: ByteArray, parts: Set<SecretPart>): Boolean = ConnKeys.restore(ctx, id, bytes, parts)

    /** A cut run's keys deleted again (RestoreJournal.recover): only those this run saved, by their entry names. */
    fun deleteKeys(ctx: Context, names: List<String>) {
        for (n in names) {
            val conn = BackupCategory.connOf(n)
            if (conn != null) ConnKeyFiles.delete(ctx, conn)
            else if (n.startsWith(BackupCategory.HELPER_KEY)) HelperKeyFiles.delete(ctx, n.removePrefix(BackupCategory.HELPER_KEY))
            else service(n)?.let { WebKeyFiles.delete(ctx, it) }
        }
    }

    /** The voiceprint of a backup (its plain form), saved only where this phone has none. */
    fun saveVoiceprint(ctx: Context, bytes: ByteArray): Boolean {
        if (VoiceprintFiles.saved(ctx)) return false
        val v = VoiceprintCodec.decode(bytes) ?: return false
        return try { Voiceprints.save(ctx, v) } finally { v.vector.fill(0f) }
    }

    /** Whether a voiceprint is saved here (the file only: nothing is decrypted). */
    fun hasVoiceprint(ctx: Context): Boolean = VoiceprintFiles.saved(ctx)

    /** The voiceprint's plain form (VoiceprintCodec.encode), or null: none, or the Keystore can't open it now. */
    fun voiceprint(ctx: Context): Secret? = Voiceprints.forBackup(ctx)?.let { v ->
        try { Secret(BackupCategory.VOICEPRINT, BackupCategory.VOICEPRINT_FILE, VoiceprintCodec.encode(v)) } finally { v.vector.fill(0f) }
    }
}
