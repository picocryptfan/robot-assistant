package app.heyrobot.core.helpers.wire

import app.heyrobot.core.ClaudeFailure
import org.json.JSONArray
import org.json.JSONObject

// Pure. An error answer's body, read for its type and code only. The message is never read past this point and never
// logged: it can quote the request. Every shape the providers document: {"error":{"type","code"}} (OpenAI, Groq,
// DeepSeek, OpenRouter, with OpenRouter's metadata.error_type), [{"error":{...}}] (Google, unverified), {"error":"text"}
// (Ollama), {"detail": ...} (FastAPI servers such as vLLM), and Mistral's {"object":"error","type","code"}.

/** What an error said: its [type] and [code] (either may be missing). */
data class WireError(val type: String?, val code: String?) {
    /** Robot's words for it with HTTP [status] (200 for an error inside a stream). */
    fun failure(status: Int?): ClaudeFailure = ClaudeFailure.of(status, type, code)
}

object WireErrors {
    fun parse(body: String?): WireError {
        val t = body?.trim().orEmpty()
        if (t.isEmpty()) return WireError(null, null)
        return try {
            when {
                t.startsWith("[") -> JSONArray(t).optJSONObject(0)?.let(::fromObject) ?: WireError(null, null)
                t.startsWith("{") -> fromObject(JSONObject(t))
                else -> WireError(null, null)
            }
        } catch (_: Exception) { WireError(null, null) }
    }

    /** One error object, or a body holding one under "error". */
    fun fromObject(o: JSONObject): WireError {
        val e = o.optJSONObject("error")
        if (e != null) {
            val meta = e.optJSONObject("metadata")
            return WireError(str(meta, "error_type") ?: str(e, "type") ?: str(e, "status"), str(e, "code"))
        }
        if (o.optString("object") == "error") return WireError(str(o, "type"), str(o, "code"))
        // {"error":"model not found"} (Ollama) or {"detail":"Not Found"}: no type to read.
        return WireError(str(o, "type"), str(o, "code"))
    }

    /** A string or number value as text; null when missing or empty. */
    private fun str(o: JSONObject?, k: String): String? {
        o ?: return null
        val v = o.opt(k) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString().takeIf { it.isNotEmpty() && it.length <= 80 }
    }
}
