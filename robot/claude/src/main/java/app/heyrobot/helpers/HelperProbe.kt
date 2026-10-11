package app.heyrobot.helpers

import app.heyrobot.claude.ClaudeListener
import app.heyrobot.claude.ClaudeOutcome
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeEffort
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.RLog
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.wire.Extra
import app.heyrobot.core.helpers.wire.WireErrors
import app.heyrobot.helpers.http.HelperHttp
import app.heyrobot.helpers.openai.OpenAiSession
import okhttp3.Dns
import org.json.JSONArray
import org.json.JSONObject

// VOICE PROCESS ONLY, :claude module. "Test connection" for a helper that isn't Claude (0.6.0), run in the helper's
// window in the :voice process (the key is decrypted only there): find the server among the addresses to try (a bare
// IP is tried on the usual servers' ports, never by scanning the network), list its models, and ask a tiny streamed
// question ("Reply with OK.") through the same code a real question takes, so a server that refuses an optional part
// (stream_options, effort, ...) is found out now rather than on the first real question. "Check actions" also offers one
// of Robot's tools (set_timer) and sees whether the model calls it; nothing runs. Nothing about the answer is kept but
// these facts (no words, no key).

/**
 * What Test connection found: [base] (the address that answered), [server] (a guess: "ollama", "openwebui", or
 * "openai"), [models], whether the tiny question worked ([answered]) and came with usage ([usage]), whether the model
 * called the tool ([actions], null: not checked), the extras the server refused ([dropped]), a [failure], and how long
 * the question took ([ms]).
 */
data class ProbeResult(
    val base: String? = null,
    val server: String? = null,
    val models: List<String> = emptyList(),
    val answered: Boolean = false,
    val usage: Boolean = false,
    val actions: Boolean? = null,
    val dropped: Set<Extra> = emptySet(),
    val failure: ClaudeFailure? = null,
    val needsKey: Boolean = false,
    val ms: Long = 0,
) {
    val ok: Boolean get() = failure == null && base != null

    fun toJson(now: Long): JSONObject = JSONObject().apply {
        put("checkedAt", now)
        put("ok", ok)
        base?.let { put("base", it) }
        server?.let { put("server", it) }
        put("models", JSONArray(models))
        put("answered", answered)
        put("usage", usage)
        actions?.let { put("actions", it) }
        put("dropped", JSONArray(dropped.map { it.name }))
        failure?.let { put("failure", it.name) }
        put("needsKey", needsKey)
        put("ms", ms)
    }
}

object HelperProbe {
    /** Each address gets this long to connect while looking for the server (a wrong port fails fast). */
    const val FIND_CONNECT_MS = 2_000L
    const val MAX_MODELS = 2_000

    /**
     * Finds the server at [candidates] (in order), lists its models and, when [question], asks the tiny question with
     * [config]'s model (or the first listed when it has none). [key]: the helper's key, if any. [actions]: also offer one
     * tool. [spent]: what the question cost, for today's spend.
     */
    suspend fun run(config: HelperConfig, key: String?, candidates: List<String>, question: Boolean = true, actions: Boolean = false,
        dns: Dns = Dns.SYSTEM, spent: (Double) -> Unit = {}): ProbeResult {
        var found: Found? = null
        var firstFailure: ClaudeFailure? = null
        for (c in candidates) {
            val connect = when {
                candidates.size > 1 -> FIND_CONNECT_MS
                config.home -> HelperHttp.HOME_CONNECT_MS
                else -> HelperHttp.CLOUD_CONNECT_MS
            }
            val http = HelperHttp.create(c, key, config.home, dns, connect) ?: return ProbeResult(failure = ClaudeFailure.NOT_LOCAL)
            try {
                val f = models(http)
                if (f.base != null) { found = f.copy(http = http); break }
                if (firstFailure == null || firstFailure == ClaudeFailure.UNREACHABLE) firstFailure = f.failure
                http.close()
            } catch (t: Throwable) {
                http.close()
                if (firstFailure == null) firstFailure = OpenAiSession.failure(t, config.home)
            }
        }
        val f = found ?: return ProbeResult(failure = firstFailure ?: ClaudeFailure.UNREACHABLE)
        val http = f.http!!
        try {
            if (f.needsKey && key.isNullOrEmpty()) return ProbeResult(f.base, f.server, f.models, failure = ClaudeFailure.KEY_REFUSED, needsKey = true)
            if (!question) return ProbeResult(f.base, f.server, f.models)
            val model = config.model.ifEmpty { f.models.firstOrNull().orEmpty() }
            if (model.isEmpty()) return ProbeResult(f.base, f.server, f.models)
            val tools = if (actions) listOf(ToolSpecs.of(ToolName.SET_TIMER)) else emptyList()
            val session = OpenAiSession(http, config.copy(model = model, search = false), tools, conversation = false)
            var cost = 0.0
            val listener = object : ClaudeListener {
                override fun text(delta: String) {}
                override fun declined() {}
                override fun spent(dollars: Double) { cost += dollars }
            }
            val t0 = System.currentTimeMillis()
            val words = if (actions) "This is a connection test: call set_timer for 1 minute. Nothing will run." else "Reply with OK."
            val o = session.ask(listOf(ClaudeBlock.Text(words)), ClaudePlan(ClaudeEffort.LOW, if (actions) 400 else 64), listener)
            val ms = System.currentTimeMillis() - t0
            val usage = session.lastReport.usage.output > 0
            if (cost > 0) spent(cost)
            session.abandon(emptyList(), stopped = true)
            RLog.i("helper_probe", "kind" to config.kind, "ok" to (o !is ClaudeOutcome.Failed), "models" to f.models.size, "ms" to ms.toInt())
            return when (o) {
                is ClaudeOutcome.Failed -> ProbeResult(f.base, f.server, f.models, dropped = session.droppedExtras, failure = o.failure, ms = ms)
                is ClaudeOutcome.Calls -> ProbeResult(f.base, f.server, f.models, answered = true, usage = usage, actions = true, dropped = session.droppedExtras, ms = ms)
                // Answered in words: with the tool offered, the model didn't use it (or the server refused tools).
                else -> ProbeResult(f.base, f.server, f.models, answered = true, usage = usage, actions = if (actions) false else null,
                    dropped = session.droppedExtras, ms = ms)
            }
        } finally {
            http.close()
        }
    }

    private data class Found(val base: String? = null, val server: String? = null, val models: List<String> = emptyList(), val needsKey: Boolean = false,
        val failure: ClaudeFailure? = null, val http: HelperHttp? = null)

    /** GET models; on a 404, Ollama's own list, then Open WebUI's. Throws on a connection that failed. */
    private fun models(http: HelperHttp): Found {
        val base = http.base.toString().trimEnd('/')
        http.get("models").execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.isSuccessful -> return Found(base, if (base.endsWith("/api")) "openwebui" else "openai", ids(body))
                r.code == 401 || r.code == 403 -> return Found(base, null, emptyList(), needsKey = true)
                r.code != 404 -> return Found(failure = WireErrors.parse(body).failure(r.code))
            }
        }
        http.get("/api/tags").execute().use { r ->
            if (r.isSuccessful) return Found(base, "ollama", ids(r.body?.string().orEmpty()))
        }
        http.get("/api/models").execute().use { r ->
            if (r.isSuccessful) return Found(base, "openwebui", ids(r.body?.string().orEmpty()))
            if (r.code == 401 || r.code == 403) return Found(base, "openwebui", emptyList(), needsKey = true)
        }
        return Found(failure = ClaudeFailure.UNREACHABLE)
    }

    /** Model ids from {"data":[{"id"}]} (OpenAI style), {"models":[{"name"|"id"|"model"}]} (Ollama, Open WebUI). */
    fun ids(body: String): List<String> = try {
        val o = JSONObject(body)
        val a = o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val m = a.optJSONObject(i) ?: return@mapNotNull a.optString(i).takeIf { it.isNotEmpty() }
            listOf("id", "name", "model").firstNotNullOfOrNull { k -> m.optString(k).takeIf { it.isNotEmpty() } }
        }.map { it.take(HelperConfig.MAX_MODEL) }.distinct().take(MAX_MODELS)
    } catch (_: Exception) { emptyList() }
}
