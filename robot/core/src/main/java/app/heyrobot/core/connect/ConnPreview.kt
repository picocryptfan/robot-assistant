// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase

// Pure. A request as a card's Preview shows it (plan §3.1, §5.4; C2): the method, the address, the headers, and the body,
// with "Authorization: your Home Assistant token" where the key goes, never the key: it is built from a request that has
// no secrets (HttpRequest) and the masked forms key.json keeps ("…AB12", ConnSecrets.mask), so it can't hold one. Shown
// on screen only, never logged (toString hides it).

/** [method] and [url] ("POST https://home.example.org/api/services/light/turn_off"), [headers] as "Name: value", [auth] the key's line, [body] as sent. */
class Preview(val method: String, val url: String, val headers: List<String>, val auth: Phrase?, val body: String?) {
    override fun toString(): String = "Preview($method)" // l10n: not text (toString, for the logs)
}

object ConnPreview {
    /** [r] for connection [c], its secret parts shown as [masks] give them ("…" when there is none). */
    fun of(r: HttpRequest, c: Connection, masks: Map<SecretPart, String> = emptyMap()): Preview {
        fun mask(p: SecretPart) = masks[p]?.takeIf { it.startsWith("…") && it.length <= 5 } ?: "…"
        val url = if (r.secretAddress) r.base.origin + "/" + mask(SecretPart.ADDRESS) + r.tail else r.base.origin + r.tail
        val headers = r.headers.map { (n, v) -> "$n: $v" } + listOfNotNull(r.body?.let { "Content-Type: ${it.contentType}" }) // l10n: not text (a header line)
        val n = "name" to c.name
        val auth = when (val a = r.auth) {
            ConnAuth.None -> null
            ConnAuth.Bearer -> ConnStrings.authToken(n, "header" to ConnWire.AUTHORIZATION)
            ConnAuth.Basic -> ConnStrings.authPassword(n, "header" to ConnWire.AUTHORIZATION)
            is ConnAuth.Header -> ConnStrings.authKey(n, "header" to a.name)
        }
        val body = r.body?.parts?.joinToString("") { p ->
            when (p) {
                is BodyPart.Text -> p.text
                is BodyPart.Secret -> ConnJson.string(mask(p.part))
            }
        }
        return Preview(r.method.id, url, headers, auth, body)
    }
}
