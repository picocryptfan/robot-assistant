// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.SkillTemplate

// Pure. ntfy (plan §3.2, C2). Checked against ntfy's docs on 22 Oct 2026 (docs.ntfy.sh/publish/ and /config/):
// - VERIFIED: "Publish as JSON": a POST to the server's root URL (never the topic's URL) with a JSON body: `topic`
//   (required), `message`, `title`, `tags` (a list of strings) and `priority` (1 to 5, 3 the default); a message is at most
//   4,096 bytes (a bigger one becomes an attachment, so Robot refuses it); topic names are letters, digits, "_" and "-",
//   up to 64; access tokens go as `Authorization: Bearer tk_…`; a visitor over the request limit gets 429; a health check
//   answers `GET /v1/health` with {"healthy": true, …} (the docs call it "preliminary").
// - A change from the plan (§3.2's `POST <server>/<topic>` with Title, Priority and Tags headers): the JSON publish keeps
//   the topic, a secret, out of the URL (and so out of a proxy's access log), and carries a title in any language (a
//   header takes only ASCII: an HTTP client throws on "Arrivé").
// - C6: a server too old for /v1/health answers 404, which Test takes as "it answers" (NtfyPreset.older); the token is
//   checked before any route (VERIFIED in ntfy's source), so a 404 means it was accepted.

object NtfyPreset {
    /** A message's most bytes (ntfy's docs). */
    const val MAX_MESSAGE_BYTES = 4096

    /** The publish an operation of an ntfy connection makes: the topic goes in its place in the body, last (ConnWire). */
    fun op(op: ConnOp): HttpOp {
        val s = op.spec as? OpSpec.NtfyPublish ?: throw IllegalArgumentException()
        val body = linkedMapOf<String, Any?>(TOPIC to TopicSlot, MESSAGE to s.message)
        s.title?.let { body[TITLE] = it }
        s.priority?.let { body[PRIORITY] = it }
        if (s.tags.isNotEmpty()) body[TAGS] = s.tags
        return HttpOp(HttpMethod.POST, "/", body = OpBody.Json(body), params = op.params)
    }

    /** [r]'s message, filled, fits ntfy's 4,096 bytes (ConnOps.build refuses it otherwise: a bigger one would arrive as a file). */
    fun messageFits(r: HttpRequest, s: OpSpec.NtfyPublish): Boolean {
        val text = StringBuilder()
        for (p in OpTemplate.parts(s.message) ?: return false) when (p) {
            is OpTemplate.Part.Words -> text.append(p.text)
            is OpTemplate.Part.Slot -> text.append(r.values[p.name]?.value?.let(SkillTemplate::text) ?: return false)
        }
        return text.toString().toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES
    }

    /**
     * Test's request (plan §3.2): nothing is published; `GET /v1/health` checks the address, the certificate and the token
     * (a server with users authenticates every request first: [older]'s note).
     */
    fun test(c: Connection): HttpRequest =
        HttpRequest(HttpMethod.GET, c.address!!, false, c.address.path + "/v1/health", emptyList(), emptyList(), null, c.auth, read = true, json = true) // l10n: not text (ntfy's path)

    /**
     * C6, checked in ntfy's source (server/server_auth.go and server.go, main, 26 Oct 2026): every request is authenticated
     * before it is routed (`maybeAuthenticate` in `handle`), so on a server with users a wrong token gets 401 even on
     * `/v1/health`; a server without users ignores the header. So Test's one health check checks the token too, and never
     * puts the topic in a URL. A server older than the health check answers 404 after the token was accepted: [older].
     */
    fun older(r: ReplyRead): Boolean = r is ReplyRead.Failed && r.why == ConnFailure.SAID_NO && r.status == 404

    /** The health check's reply says the server is healthy. */
    fun healthy(json: Any?): Boolean = (json as? Map<*, *>)?.get("healthy") == true

    private const val TOPIC = "topic" // l10n: not text (ntfy's field)
    private const val MESSAGE = "message" // l10n: not text (ntfy's field)
    private const val TITLE = "title" // l10n: not text (ntfy's field)
    private const val PRIORITY = "priority" // l10n: not text (ntfy's field)
    private const val TAGS = "tags" // l10n: not text (ntfy's field)
}
