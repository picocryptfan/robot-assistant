package app.heyrobot.claude

import app.heyrobot.core.ClaudeFailure
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.Timeout
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration

// VOICE PROCESS ONLY, :claude module only (the official Anthropic Java SDK, OkHttp client). The one module that talks to
// the Claude API; the main (UI) process never loads it (ProcessIsolationTest, tools/verify_apk.sh). The API key is
// decrypted in the :voice process just before a client is made and lives only inside the client.

/** The SDK client for one API key. [baseUrl]: the tests' mock server; null in the app (the SDK's default host). */
class ClaudeClient private constructor(internal val sdk: AnthropicClient) : AutoCloseable {
    override fun close() {
        try { sdk.close() } catch (_: Throwable) {}
    }

    companion object {
        /** Robot's own limits (docs/ask-claude.md "Errors"): 60 s to the first token is watched by [ClaudeSession]. */
        private val TIMEOUT: Timeout = Timeout.builder()
            .connect(Duration.ofSeconds(15))
            // No byte at all for this long (the API pings during long thinking): the connection is gone.
            .read(Duration.ofSeconds(90))
            .write(Duration.ofSeconds(30))
            // No limit on the whole call while the answer keeps arriving.
            .request(Duration.ZERO)
            .build()

        fun create(apiKey: String, baseUrl: String? = null): ClaudeClient = ClaudeClient(
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .apply { if (baseUrl != null) baseUrl(baseUrl) }
                // Robot retries an overloaded or 5xx answer once itself, and never a rate limit (ClaudeSession).
                .maxRetries(0)
                // Never the SDK's own logging: requests carry the key and the owner's words.
                .logLevel(LogLevel.OFF)
                .timeout(TIMEOUT)
                .build(),
        )

        /**
         * Loads the SDK's classes (the request builders, Jackson's readers for the stream's events) without any network,
         * so the first question after the panel opens doesn't pay for it (most of a second on a cold JVM). Background
         * thread only; never fails.
         */
        fun warm() {
            try {
                ClaudeRequests.params(
                    app.heyrobot.core.ClaudeModel.DEFAULT, app.heyrobot.core.ClaudePlan(app.heyrobot.core.ClaudeEffort.LOW, 1),
                    emptyList(), search = true, cacheLast = true, messages = listOf(ClaudeRequests.user(listOf(app.heyrobot.core.ClaudeBlock.Text("hi")))),
                )
                val mapper = com.anthropic.core.jsonMapper()
                for (e in WARM_EVENTS) mapper.readValue(e, com.anthropic.models.beta.messages.BetaRawMessageStreamEvent::class.java).validate()
            } catch (_: Throwable) {}
        }

        private val WARM_EVENTS = listOf(
            """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}""",
        )

        /**
         * The SDK's typed exceptions, most specific first, to Robot's words. Only the HTTP status and the API's error
         * type are read, never a message (it can quote the request).
         */
        fun failure(t: Throwable): ClaudeFailure = when (t) {
            is UnauthorizedException -> ClaudeFailure.KEY_REFUSED
            is PermissionDeniedException -> ClaudeFailure.of(403, type(t))
            is NotFoundException -> ClaudeFailure.MODEL_UNAVAILABLE
            is RateLimitException -> ClaudeFailure.RATE_LIMITED
            is BadRequestException -> ClaudeFailure.of(400, type(t))
            is UnprocessableEntityException -> ClaudeFailure.BAD_REQUEST
            // 500, 503, 529 (overloaded) ...
            is InternalServerException -> ClaudeFailure.of(t.statusCode(), type(t))
            // An "error" event after the stream opened (overloaded_error mid-answer): the type says which.
            is SseException -> ClaudeFailure.of(null, type(t))
            // 402 (billing) and anything else with a status.
            is AnthropicServiceException -> ClaudeFailure.of(t.statusCode(), type(t))
            is AnthropicIoException, is AnthropicRetryableException -> io(t.cause)
            // Something the SDK couldn't read (an answer it doesn't understand).
            is AnthropicException -> ClaudeFailure.UNKNOWN
            is IOException -> io(t)
            else -> ClaudeFailure.UNKNOWN
        }

        /** No network, a refused connection, a DNS failure (also GrapheneOS's Network toggle off): "No internet". */
        private fun io(t: Throwable?): ClaudeFailure = if (t is InterruptedIOException) ClaudeFailure.TIMEOUT else ClaudeFailure.NO_INTERNET

        private fun type(e: AnthropicServiceException): String? = try { e.errorType().orElse(null)?.asString() } catch (_: Throwable) { null }
    }
}
