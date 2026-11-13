// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepCheck

// Pure. Skills connections (docs/plans/skills-connections-plan.md §3, §5.2; C2): what you set up once in Settings › Skills
// › Connections, never a model, a draft or a reply. A connection is a server of yours (Home Assistant, ntfy, a webhook, a
// routing server) or an app you picked (a hand-off, C7), with the operations you made for it; a skill's step names one
// by `conn:<id>:<operation>`. Kept in files/robot/connections.json (ConnectionCodec, v1) through ConnStore.
//
// What is never in here: a key, a token, an ntfy topic, an address that is itself the key, a header value you marked
// secret (they live in the connection's key file, ConnSecrets, C3), Test's facts and the pickers' lists. [Connection.secrets]
// says which parts are secret, never their values. Nothing here is ever logged: every toString shows ids and kinds only.

/** What a connection reaches (plan §5.2's `kind`). */
enum class ConnKind(val id: String) {
    HOME_ASSISTANT("home_assistant"), NTFY("ntfy"), WEBHOOK("webhook"), ROUTING("routing"), HAND_OFF("hand_off"),
    /** An MCP server you run (1.3, design §8.5): its tools live in its mcp_tools.json (McpOps). */
    MCP("mcp");

    companion object {
        fun of(id: String?): ConnKind? = entries.firstOrNull { it.id == id }
    }
}

/** Where its addresses are (D1): your own network, or the internet. Test sets it from the addresses it found. */
enum class ConnWhere(val id: String) {
    HOME("home"), INTERNET("internet");

    companion object {
        fun of(id: String?): ConnWhere? = entries.firstOrNull { it.id == id }
    }
}

/** A part of a connection that is a secret (D2): its value is only ever in the key file, and makes the connection https only. */
enum class SecretPart(val id: String) {
    /** A token or password sent in Authorization (Bearer or Basic). */
    TOKEN("token"),
    /** ntfy's topic: "essentially a password" (ntfy's docs). */
    TOPIC("topic"),
    /** A webhook's whole address (Discord, Slack and n8n put a secret in it). */
    ADDRESS("address"),
    /** A header's value you marked secret. */
    HEADER("header");

    companion object {
        fun of(id: String?): SecretPart? = entries.firstOrNull { it.id == id }
    }
}

/** The routing server you run (plan §3.4). */
enum class RoutingKind(val id: String) {
    OSRM("osrm"), VALHALLA("valhalla"), GRAPHHOPPER("graphhopper");

    companion object {
        fun of(id: String?): RoutingKind? = entries.firstOrNull { it.id == id }
    }
}

/** How a connection's key goes with each request: added last, by the client, never in a preview, a card or a log. */
sealed interface ConnAuth {
    /** No key. */
    data object None : ConnAuth

    /** `Authorization: Bearer <token>` (Home Assistant's long-lived token, ntfy's `tk_…`, a webhook's). */
    data object Bearer : ConnAuth

    /** `Authorization: Basic <base64 of user:password>`. */
    data object Basic : ConnAuth

    /** A header of your naming, its value your secret (`X-Api-Key: …`). */
    data class Header(val name: String) : ConnAuth

    /** The secret part it sends, or null. */
    val part: SecretPart? get() = when (this) {
        None -> null
        Bearer, Basic -> SecretPart.TOKEN
        is Header -> SecretPart.HEADER
    }
}

/** A first-use answer for a host (D13): kept per connection and host; a changed address asks again. */
enum class ConsentAnswer(val id: String) {
    /** "Allow for this connection": no card for this host again. */
    ALLOW("allow"),
    /** "Just this time": kept only so Settings can show it; the next call asks again. */
    ONCE("once");

    companion object {
        fun of(id: String?): ConsentAnswer? = entries.firstOrNull { it.id == id }
    }
}

/** When Test last passed (wall-clock ms), and the server's version when it gave one ("2026.10.1"). */
data class ConnTested(val at: Long, val version: String? = null)

/**
 * A hand-off's app (plan §3.5): its package, its signer's SHA-256 (64 lowercase hex digits), pinned when you picked it, and
 * what it is asked to do (C7, IntentSpecs: opening it, unless you set up more). C10: [signer] null is an app not pinned on
 * this phone (a hand-off from a backup that isn't yours: trust is never restored), never opened until you pick it again.
 */
data class HandOffApp(val pkg: String, val signer: String?, val intent: IntentSpec = IntentSpec(HandOffAction.MAIN)) {
    /** Its signer is pinned (picked on this phone, or from your own backup). */
    val pinned: Boolean get() = signer != null

    override fun toString(): String = "HandOffApp(…)" // l10n: not text (toString, for the logs)
}

/** An operation's HTTP method (webhooks, plan §3.3). Effects follow from it (SkillEffects.webhook). */
enum class HttpMethod(val id: String, val body: Boolean) {
    GET("GET", false), POST("POST", true), PUT("PUT", true), PATCH("PATCH", true), DELETE("DELETE", true);

    companion object {
        fun of(id: String?): HttpMethod? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One of an operation's arguments: the slot a step fills (C1's SkillLinks reads [type] and [options]). [options]: a
 * choice's values (for Home Assistant, the entities you picked); [min] and [max]: an integer's or a number's limits;
 * [maxLen]: a text's most characters; [default]: its value when a step doesn't give one (none: the step must).
 */
data class OpParam(val type: ParamType, val options: List<String> = emptyList(), val min: Double? = null, val max: Double? = null,
    val maxLen: Int? = null, val default: String? = null) {
    override fun toString(): String = "OpParam(${type.id})" // l10n: not text (toString, for the logs)
}

/** A declared output (plan §3.3): an RFC 6901 [pointer] into the reply, and its type. Only these are ever kept. */
data class OpOutput(val pointer: String, val type: ParamType, val options: List<String> = emptyList()) {
    override fun toString(): String = "OpOutput(${type.id})" // l10n: not text (toString, for the logs)
}

/** A request body's template: JSON whose strings may be templates, or plain text with templates inside. */
sealed interface OpBody {
    /**
     * [tree]: a JSON value as StrictJson reads one (a Map, a List, a String, a Long, a Double, a Boolean or null). A string
     * that is exactly `{{param}}` becomes the value, typed (a number stays a number); a string with templates inside the
     * words becomes text. Keys are never templates.
     */
    data class Json(val tree: Any?) : OpBody {
        override fun toString(): String = "OpBody.Json(…)" // l10n: not text (toString, for the logs)
    }

    data class Text(val template: String) : OpBody {
        override fun toString(): String = "OpBody.Text(…)" // l10n: not text (toString, for the logs)
    }
}

/** What an operation does, by kind. Each preset turns one into an [HttpOp]. */
sealed interface OpSpec {
    /** Its id in the file ("ha_call"). */
    val id: String

    /**
     * Home Assistant: call a service (plan §3.1), `POST /api/services/<domain>/<service>` with `entity_id` from the
     * `entity` argument and [data] (the service's fields, templates as in [OpBody.Json]). [returns]: the service returns
     * data (`?return_response`; Home Assistant answers 400 when this doesn't fit the service). [classes]: each cover's
     * device class, as the entity picker read it when you made the operation (a cover without one counts as a door).
     */
    data class HaCall(val domain: String, val service: String, val data: Map<String, Any?> = emptyMap(), val returns: Boolean = false,
        val classes: Map<String, String> = emptyMap()) : OpSpec {
        override val id: String get() = "ha_call" // l10n: not text (an operation kind in connections.json)
        override fun toString(): String = "HaCall(…)" // l10n: not text (toString, for the logs)
    }

    /** Home Assistant: read a state, `GET /api/states/<entity_id>`. */
    data object HaRead : OpSpec {
        override val id: String get() = "ha_read" // l10n: not text (an operation kind in connections.json)
    }

    /** Home Assistant: run a script, `POST /api/services/script/turn_on`. Robot can't see what it does. */
    data object HaScript : OpSpec {
        override val id: String get() = "ha_script" // l10n: not text (an operation kind in connections.json)
    }

    /**
     * ntfy: publish (plan §3.2), as JSON to the server's root (ntfy's "Publish as JSON"): [message], [title] (templates),
     * [priority] (1 to 5, or `{{param}}`), [tags] (fixed). [onlyMine]: you marked the topic "only my devices" (it writes
     * online instead of telling people).
     */
    data class NtfyPublish(val message: String, val title: String? = null, val priority: Any? = null, val tags: List<String> = emptyList(),
        val onlyMine: Boolean = false) : OpSpec {
        override val id: String get() = "ntfy_publish" // l10n: not text (an operation kind in connections.json)
        override fun toString(): String = "NtfyPublish(…)" // l10n: not text (toString, for the logs)
    }

    /**
     * A webhook's one request (plan §3.3): [method], a [path] after the connection's address (templates inside segments),
     * [query] and [headers] as (name, template) pairs, and a [body].
     */
    data class Webhook(val method: HttpMethod, val path: String = "", val query: List<Pair<String, String>> = emptyList(),
        val headers: List<Pair<String, String>> = emptyList(), val body: OpBody? = null) : OpSpec {
        override val id: String get() = "webhook" // l10n: not text (an operation kind in connections.json)
        override fun toString(): String = "Webhook(${method.id})" // l10n: not text (toString, for the logs)
    }

    /**
     * An MCP server's tool you turned on (1.3, design §8.5). Never written to connections.json: McpOps makes it from the
     * server's mcp_tools.json each time connections are read. [tool]: its name; [input]: its inputSchema as pinned when you
     * turned it on (a call's arguments are checked against it, never a later list's); [effects]: what it does as you set it
     * (McpPolicy.effects); [reads]: you ticked "This tool only reads" and the server says so too.
     */
    data class McpCall(val tool: String, val input: Map<String, Any?>, val effects: Set<SkillEffect>, val reads: Boolean) : OpSpec {
        override val id: String get() = "mcp_call" // l10n: not text (an operation kind, never in connections.json)
        override fun toString(): String = "McpCall(…)" // l10n: not text (toString, for the logs)
    }
}

/**
 * An operation you made (plan §5.2's `ops`): [id] (what a step's `conn:` names), your [title], what it does, its
 * arguments and declared outputs, an optional [check] (a webhook's: a pointer equals a value), and your [marks] (money,
 * security, tells people), which only ever add to its effects (D12).
 */
data class ConnOp(
    val id: String,
    val title: String,
    val spec: OpSpec,
    val params: Map<String, OpParam> = emptyMap(),
    val outputs: Map<String, OpOutput> = emptyMap(),
    val check: StepCheck.Equals? = null,
    val marks: Set<SkillEffect> = emptySet(),
) {
    override fun toString(): String = "ConnOp($id, ${spec.id})" // l10n: not text (toString, for the logs)

    companion object {
        /** The effects you may add to an operation. */
        val MARKS: Map<SkillEffect, String> = linkedMapOf(SkillEffect.MONEY to "money", SkillEffect.SECURITY to "security", // l10n: not text (ids in connections.json)
            SkillEffect.MESSAGES_SOMEONE to "tells_people", SkillEffect.DELETES to "deletes") // l10n: not text (ids in connections.json)

        /** The argument that holds a Home Assistant operation's entity (SkillLinks' HaCall.entityArg). */
        const val ENTITY = "entity"
    }
}

/**
 * A connection (plan §5.2). [address]: its base address, or for an address that is itself the key ([secretAddress]),
 * only its origin (scheme, host and port: what the card shows and first-use answers name); none for a hand-off.
 * [where]: home or internet, as Test found it (D1). [answers]: first-use answers by origin (D13). [spokenYes]: "A spoken
 * yes may approve its changes" (D14). [perMinute]: its requests a minute (D16). [addressMarks]: what the path of an
 * address that is itself the key hints at (money, WebhookPreset.addressMarks), kept here because the path isn't and a
 * new webhook has no operation yet: its form starts from them.
 */
data class Connection(
    val id: String,
    val kind: ConnKind,
    val name: String,
    val address: ConnUrl? = null,
    val secretAddress: Boolean = false,
    val auth: ConnAuth = ConnAuth.None,
    val where: ConnWhere? = null,
    val routing: RoutingKind? = null,
    val handOff: HandOffApp? = null,
    val ops: List<ConnOp> = emptyList(),
    val answers: Map<String, ConsentAnswer> = emptyMap(),
    val spokenYes: Boolean = true,
    val perMinute: Int = DEFAULT_PER_MINUTE,
    val tested: ConnTested? = null,
    val addressMarks: Set<SkillEffect> = emptySet(),
) {
    /** Which parts are secret (D2): never their values. ntfy's topic always is. */
    val secrets: Set<SecretPart> get() = buildSet {
        auth.part?.let(::add)
        if (kind == ConnKind.NTFY) add(SecretPart.TOPIC)
        if (secretAddress) add(SecretPart.ADDRESS)
    }

    fun op(id: String): ConnOp? = ops.firstOrNull { it.id == id }

    override fun toString(): String = "Connection($id, ${kind.id})" // l10n: not text (toString, for the logs)

    companion object {
        /** D16 (EST): 30 requests a minute unless you set another, 1 to 60. */
        const val DEFAULT_PER_MINUTE = 30
    }
}
