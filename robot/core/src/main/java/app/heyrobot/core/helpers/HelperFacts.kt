// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.HelperFailure
import app.heyrobot.core.helpers.wire.Extra
import org.json.JSONArray
import org.json.JSONObject

// Pure. What Robot found out about a helper (0.6.0), kept in helpers/<id>/facts.json by the :voice process (no key, no
// words): the last Test connection (the address that answered, its models, whether a tiny question worked, whether the
// model used Robot's action), and the optional request parts a server refused, per model (adaptive extras), so the next
// question doesn't pay for them. Settings reads it to show the model list and the status dot.

data class HelperFacts(
    /** The address you had set when the server was found (facts for another address don't count). */
    val forAddress: String? = null,
    /** The address that answered (a bare IP is tried on the usual ports: which one it was). */
    val base: String? = null,
    /** "ollama", "openwebui" or "openai" (a guess, for Settings' words). */
    val server: String? = null,
    val models: List<String> = emptyList(),
    val answered: Boolean = false,
    /** The model the tiny question went to, and whether it called the tool when asked to ([actions], null: not checked). */
    val model: String? = null,
    val actions: Boolean? = null,
    val failure: HelperFailure? = null,
    val needsKey: Boolean = false,
    val checkedAt: Long = 0,
    val ms: Long = 0,
    /** Extras each model's server refused. */
    val dropped: Map<String, Set<Extra>> = emptyMap(),
) {
    fun dropped(model: String): Set<Extra> = dropped[model].orEmpty()

    fun withDropped(model: String, e: Extra): HelperFacts = copy(dropped = dropped + (model to (dropped(model) + e)))

    /**
     * Test connection's line on [name]'s page: what the last check found. 1.0.2: a check that failed says so, and a key
     * refused over http says nothing was asked (before, any check that got no answer read "the server answered").
     */
    fun checkedLine(name: String): String = when {
        checkedAt <= 0 -> "Robot hasn't reached $name yet."
        failure == HelperFailure.KEY_OVER_HTTP -> "Last checked: the key wasn't sent over http, so nothing was asked."
        failure != null -> "Last checked: it didn't work (" + app.heyrobot.core.HelperFail.of(failure).title + ")."
        else -> "Last checked: " + (if (answered) "a test question was answered" else "the server answered") +
            (if (models.isNotEmpty()) ", ${models.size} models listed" else "") + "."
    }

    /** The model can't take Robot's actions (the server refused tools, or Check actions saw it not call one). */
    fun noActions(model: String): Boolean = Extra.TOOLS in dropped(model) || (this.model == model && actions == false)

    /**
     * The address to use for [h]: the company's own; for your server, the one Test connection found when it was for the
     * address set now; otherwise the address you typed, when it names a single place (with a port, or https). Null:
     * Test connection must find the port first.
     */
    fun address(h: HelperConfig): String? {
        h.presetOf.baseUrl?.let { return it }
        val typed = h.baseUrl ?: return null
        if (forAddress == typed && base != null) return base
        return LanRule.normalize(typed)?.candidates?.singleOrNull()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        forAddress?.let { put("forAddress", it) }
        base?.let { put("base", it) }
        server?.let { put("server", it) }
        put("models", JSONArray(models))
        put("answered", answered)
        model?.let { put("model", it) }
        actions?.let { put("actions", it) }
        failure?.let { put("failure", it.name) }
        put("needsKey", needsKey)
        put("checkedAt", checkedAt)
        put("ms", ms)
        put("dropped", JSONObject().apply { for ((m, set) in dropped) put(m, JSONArray(set.map { it.name })) })
    }

    companion object {
        fun fromJson(text: String?): HelperFacts = try {
            val o = JSONObject(text ?: return HelperFacts())
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() }
            HelperFacts(
                forAddress = str("forAddress"), base = str("base"), server = str("server"),
                models = o.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
                answered = o.optBoolean("answered"), model = str("model"),
                actions = if (o.has("actions")) o.optBoolean("actions") else null,
                failure = enumValues<HelperFailure>().firstOrNull { it.name == o.optString("failure") },
                needsKey = o.optBoolean("needsKey"), checkedAt = o.optLong("checkedAt"), ms = o.optLong("ms"),
                dropped = o.optJSONObject("dropped")?.let { d ->
                    d.keys().asSequence().associateWith { m -> d.optJSONArray(m)?.let { a -> (0 until a.length()).mapNotNull { i -> enumValues<Extra>().firstOrNull { it.name == a.optString(i) } }.toSet() }.orEmpty() }
                }.orEmpty(),
            )
        } catch (_: Exception) { HelperFacts() }
    }
}
