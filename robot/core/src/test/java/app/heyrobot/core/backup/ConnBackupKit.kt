// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.connect.ConnFixtures
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnSecretsCodec
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConnectionCodec

/**
 * C10: Skills connections in a backup, for the backup tests (demo hosts and made-up keys only, each key holding the
 * marker): Home Assistant with the id the skill fixtures name, ntfy, a webhook whose address is its key, and a hand-off.
 * Kept apart from BackupKit.content(), so the committed fixtures and the writer's pinned bytes stay as they were.
 */
object ConnBackupKit {
    /** SkillFixtures.HA: Good night's steps name it. */
    const val HA = "c-ha1234"
    const val NTFY = "c-ntfy01"
    const val HOOK = "c-disc01"
    const val APP = "c-app001"

    fun connections(): List<Connection> = listOf(
        ConnFixtures.homeAssistant(HA),
        ConnFixtures.ntfy(NTFY),
        ConnFixtures.discord(HOOK),
        ConnFixtures.signal(APP),
    )

    fun secrets(): Map<String, ConnSecrets> = linkedMapOf(
        HA to ConnSecrets(token = "eyJ0eXAiOiJKV1Qi-${BackupKit.MARK}-ha-token"),
        NTFY to ConnSecrets(token = "tk_${BackupKit.MARK}ntfytoken0001", topic = "family_${BackupKit.MARK}_AB12"),
        HOOK to ConnSecrets(address = "https://discord.example.com/api/webhooks/1234/${BackupKit.MARK}-secret-path"),
    )

    /** The two categories' entries, as Back up now writes them. */
    fun content(connections: List<Connection> = connections(), secrets: Map<String, ConnSecrets> = secrets()): Map<BackupCategory, Map<String, ByteArray>> =
        linkedMapOf(
            BackupCategory.CONNECTIONS to mapOf(BackupCategory.CONNECTIONS_FILE to BackupKit.text(ConnectionCodec.encode(connections))),
            BackupCategory.CONN_KEYS to secrets.entries.associate { (id, s) -> BackupCategory.connKey(id) to ConnSecretsCodec.encode(s) },
        )

    /** A backup holding [content] (records in its order), encrypted with BackupKit's test key. */
    fun file(content: Map<BackupCategory, Map<String, ByteArray>> = content()): ByteArray = BackupKit.encrypt(BackupKit.contentRecords(content))

    fun verify(content: Map<BackupCategory, Map<String, ByteArray>> = content()): VerifiedBackup = BackupKit.verify(file(content))
}
