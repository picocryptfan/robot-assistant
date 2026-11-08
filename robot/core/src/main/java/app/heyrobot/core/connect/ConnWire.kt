// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import java.util.Base64

// Pure. The last step before a request leaves (plan §4.2, C2): a built request (HttpRequest) and the connection's
// secrets become what the client sends. Only the client (C3's ConnHttp, in :voice, with the secrets just read from the
// key file) calls it, after every other check, so no preview, card or log can hold a secret. Here, again and last:
// D2 (a secret only over https, the scheme of an address that is itself the key included), that address's origin is the
// one the connection keeps (what the card showed and the first-use answer named), and every header value printable ASCII.

/** What goes on the wire: its URL, headers (the key's last), and body. Never shown, never logged: toString hides it. */
class Wire(val method: HttpMethod, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray?, val contentType: String?) {
    override fun toString(): String = "Wire(${method.id})" // l10n: not text (toString, for the logs)
}

object ConnWire {
    /** What happened putting the secrets in: the wire, or why nothing may be sent. */
    sealed class Result {
        class Ok(val wire: Wire) : Result() {
            override fun toString(): String = "Wire.Ok" // l10n: not text (toString, for the logs)
        }

        data class Refused(val why: ConnFailure) : Result()
    }

    /** [r] with [secrets] in their places, or why not. */
    fun assemble(r: HttpRequest, secrets: ConnSecrets, phoneToken: Boolean = false): Result {
        val need = r.secrets
        if (need.any { secrets[it] == null }) return Result.Refused(ConnFailure.NO_KEY)
        if (need.any { !ConnSecrets.valid(it, secrets[it]!!) }) return Result.Refused(ConnFailure.NO_KEY)
        // D2: any secret goes only over https, whatever else said so before.
        // [phoneToken]: an MCP server on this phone, whose token may go over plain http (ConnRules.mcpUnencrypted).
        if (need.isNotEmpty() && !r.base.https && !(phoneToken && need == setOf(SecretPart.TOKEN))) return Result.Refused(ConnFailure.KEY_OVER_HTTP)
        val url = if (r.secretAddress) {
            val key = ConnUrl.parse(secrets.address!!, whole = true) ?: return Result.Refused(ConnFailure.NO_KEY)
            if (!key.https) return Result.Refused(ConnFailure.KEY_OVER_HTTP)
            // The key's address goes to the origin the connection keeps, never elsewhere.
            if (key.origin != r.base.origin) return Result.Refused(ConnFailure.NOWHERE)
            joined(key.text, r)
        } else r.base.origin + r.tail
        val headers = r.headers.toMutableList()
        when (val a = r.auth) {
            ConnAuth.None -> {}
            ConnAuth.Bearer -> headers += AUTHORIZATION to "Bearer " + secrets.token!! // l10n: not text (an Authorization scheme)
            ConnAuth.Basic -> {
                val t = secrets.token!!
                if (!ConnSecrets.basic(t)) return Result.Refused(ConnFailure.NO_KEY)
                headers += AUTHORIZATION to "Basic " + Base64.getEncoder().encodeToString(t.toByteArray(Charsets.UTF_8)) // l10n: not text (an Authorization scheme)
            }
            is ConnAuth.Header -> headers += a.name to secrets.header!!
        }
        if (headers.any { (n, v) -> !ConnCheck.printable(v) || v.length > MAX_HEADER || n.isEmpty() }) return Result.Refused(ConnFailure.BAD_VALUE)
        val body = r.body?.let { b ->
            b.parts.joinToString("") { p ->
                when (p) {
                    is BodyPart.Text -> p.text
                    is BodyPart.Secret -> ConnJson.string(secrets[p.part]!!)
                }
            }.toByteArray(Charsets.UTF_8)
        }
        return Result.Ok(Wire(r.method, url, headers, body, r.body?.contentType))
    }

    /** The key's address with the request's path and query after it (a key's own query first). */
    private fun joined(key: String, r: HttpRequest): String {
        val q = key.indexOf('?')
        val keyPath = if (q >= 0) key.substring(0, q) else key
        val keyQuery = if (q >= 0) key.substring(q + 1) else null
        val opQuery = r.query.joinToString("&") { (k, v) -> if (v == null) k else "$k=$v" }.ifEmpty { null }
        val query = listOfNotNull(keyQuery, opQuery).joinToString("&")
        return keyPath.removeSuffix("/").let { if (r.path.isEmpty() && keyPath.endsWith("/")) "$it/" else it } + r.path + if (query.isEmpty()) "" else "?$query"
    }

    /** The one place core names the header a connection's key goes in (ConnPreview names it from here). */
    const val AUTHORIZATION = "Authorization" // l10n: not text (a header name)

    /** A header's most characters on the wire: a key's (4,096) with its scheme, or Basic's base64 of it. */
    private const val MAX_HEADER = 8 * 1024
}
