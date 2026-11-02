// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson

// Pure. A connection's secrets (plan §3, §5.2; C2): its token, ntfy's topic, a webhook's address that is itself the key,
// a header's value you marked secret. They live only in the connection's key file (C3: KeystoreSecret with its own
// Keystore key, `robot_conn_<id>`, decrypted only in :voice just before a request), never in connections.json, a log, an
// intent, History or IPC. Nothing here ever shows one: toString shows which parts are there, [ConnSecrets.mask] the
// last few characters at most. [ConnSecretsCodec] is the key file's plain form, before C3 encrypts it: strict, capped,
// versioned.

/** The secret parts' values. Each is checked for its part ([ConnSecrets.valid]) before it is kept or used. */
class ConnSecrets(val token: String? = null, val topic: String? = null, val address: String? = null, val header: String? = null) {
    operator fun get(p: SecretPart): String? = when (p) {
        SecretPart.TOKEN -> token
        SecretPart.TOPIC -> topic
        SecretPart.ADDRESS -> address
        SecretPart.HEADER -> header
    }

    /** The parts it holds. */
    val parts: Set<SecretPart> get() = SecretPart.entries.filter { get(it) != null }.toSet()

    /** Every part it holds is a value its part takes. */
    val valid: Boolean get() = parts.all { valid(it, get(it)!!) }

    /** "…AB12" for each part, as Settings and a preview show it (key.json keeps them, C3). */
    fun masks(): Map<SecretPart, String> = parts.associateWith { mask(get(it)!!) }

    override fun equals(other: Any?): Boolean = other is ConnSecrets && SecretPart.entries.all { get(it) == other[it] }
    override fun hashCode(): Int = SecretPart.entries.map { get(it) }.hashCode()
    override fun toString(): String = "ConnSecrets(" + parts.joinToString(",") { it.id } + ")" // l10n: not text (toString, for the logs)

    companion object {
        const val MAX = 4096
        private val TOPIC = Regex("^[-_A-Za-z0-9]{1,64}$")

        /**
         * [v] is a value [p] takes: a token or a header's value, 1 to 4,096 printable ASCII characters (an HTTP header's);
         * a topic as ntfy names one (letters, digits, "_" and "-", 1 to 64, VERIFIED in ntfy's docs); an address that is
         * the key, an https address Robot reads whole (ConnUrl, a query allowed).
         */
        fun valid(p: SecretPart, v: String): Boolean = when (p) {
            SecretPart.TOKEN, SecretPart.HEADER -> v.length in 1..MAX && ConnCheck.printable(v) && v.trim() == v
            SecretPart.TOPIC -> TOPIC.matches(v)
            SecretPart.ADDRESS -> v.length <= ConnUrl.MAX_LENGTH && ConnUrl.parse(v, whole = true)?.https == true
        }

        /** Basic's token is "user:password", the user not empty. */
        fun basic(v: String): Boolean = v.indexOf(':') > 0

        /**
         * What a secret shows of itself: "…" and its last characters, at most 4 and at most a quarter of it (so a short
         * topic shows little); "…" alone when it is shorter than 8.
         */
        fun mask(v: String): String {
            val n = minOf(4, v.length / 4)
            return if (v.length < 8 || n < 2) "…" else "…" + v.takeLast(n)
        }
    }
}

/**
 * What a key file's plain form held: the secrets; one from a newer Robot (kept, never used or deleted: a later Robot
 * reads it); or one that can never be read (damaged: C3 removes it, as KeystoreSecret's Invalidated).
 */
sealed class SecretsFile {
    class Ok(val secrets: ConnSecrets) : SecretsFile() {
        override fun toString(): String = "SecretsFile.Ok" // l10n: not text (toString, for the logs)
    }

    data class TooNew(val version: Int) : SecretsFile()
    data object Damaged : SecretsFile()
}

/** The key file's plain form (C3 encrypts it): `{"v":1,"token":…,"topic":…}`. */
object ConnSecretsCodec {
    const val VERSION = 1
    const val MAX_BYTES = 16 * 1024
    private val KEYS = setOf("v") + SecretPart.entries.map { it.id }

    fun encode(s: ConnSecrets): ByteArray {
        require(s.valid)
        val m = linkedMapOf<String, Any?>("v" to VERSION.toLong())
        for (p in SecretPart.entries) s[p]?.let { m[p.id] = it }
        return ConnJson.write(m).toByteArray(Charsets.UTF_8)
    }

    /**
     * The secrets in [bytes]; [SecretsFile.TooNew] for a newer Robot's (v above 1, whatever else it holds); else
     * [SecretsFile.Damaged]: over the cap, not strict JSON, cut short, no whole-number "v" or v below 1, an unknown key,
     * a value its part doesn't take.
     */
    fun read(bytes: ByteArray): SecretsFile {
        if (bytes.size > MAX_BYTES) return SecretsFile.Damaged
        val o = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return SecretsFile.Damaged
        val v = o["v"] as? Long ?: return SecretsFile.Damaged
        if (v > VERSION) return SecretsFile.TooNew(v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (v < VERSION || !KEYS.containsAll(o.keys)) return SecretsFile.Damaged
        fun part(p: SecretPart): String? = o[p.id]?.let { it as? String ?: return null }
        val s = ConnSecrets(part(SecretPart.TOKEN), part(SecretPart.TOPIC), part(SecretPart.ADDRESS), part(SecretPart.HEADER))
        if (SecretPart.entries.any { o.containsKey(it.id) && s[it] == null } || !s.valid) return SecretsFile.Damaged
        return SecretsFile.Ok(s)
    }

    /** [read]'s secrets, or null when there are none to use (newer or damaged). */
    fun decode(bytes: ByteArray): ConnSecrets? = (read(bytes) as? SecretsFile.Ok)?.secrets
}
