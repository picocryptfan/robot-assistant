package app.heyrobot.helpers.anthropic

import app.heyrobot.core.AnthropicErrorKind
import app.heyrobot.core.HelperFailure
import com.anthropic.client.AnthropicClient as SdkClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.Timeout
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
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

// VOICE PROCESS ONLY, :helpers module only (the official Anthropic Java SDK, OkHttp client). The one module that talks to
// the Claude API; the main (UI) process never loads it (ProcessIsolationTest, tools/verify_apk.sh). The API key is
// decrypted in the :voice process just before a client is made and lives only inside the client.

/** The SDK client for one API key. [baseUrl]: the tests' mock server; null in the app (the SDK's default host). */
class AnthropicClient private constructor(internal val sdk: SdkClient) : AutoCloseable {
    override fun close() {
        try { sdk.close() } catch (_: Throwable) {}
    }

    companion object {
        /** Robot's own limits (docs/ask-claude.md "Errors"); 60 s without any stream event is watched by [AnthropicSession]. */
        private val TIMEOUT: Timeout = Timeout.builder()
            .connect(Duration.ofSeconds(15))
            // No byte at all for this long (the API pings during long thinking): the connection is gone.
            .read(Duration.ofSeconds(90))
            .write(Duration.ofSeconds(30))
            // No limit on the whole call while the answer keeps arriving.
            .request(Duration.ZERO)
            .build()

        /**
         * The SDK describes the device in its default headers (the kernel release with its build tag, the CPU, the Java
         * runtime's version). Robot sends neutral values instead; the SDK's language and version stay.
         */
        val NEUTRAL_HEADERS: Map<String, String> = mapOf(
            "X-Stainless-OS" to "Other",
            "X-Stainless-OS-Version" to "unknown",
            "X-Stainless-Arch" to "unknown",
            "X-Stainless-Runtime-Version" to "unknown",
        )

        fun create(apiKey: String, baseUrl: String? = null): AnthropicClient = AnthropicClient(
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .apply { if (baseUrl != null) baseUrl(baseUrl) }
                .apply { for ((k, v) in NEUTRAL_HEADERS) replaceHeaders(k, v) }
                // Robot retries an overloaded or 5xx answer once itself, and never a rate limit (AnthropicSession).
                .maxRetries(0)
                // Never the SDK's own logging: requests carry the key and the user's words.
                .logLevel(LogLevel.OFF)
                .timeout(TIMEOUT)
                .build(),
        )

        /**
         * Loads the SDK's classes (the request builders, Jackson's readers for the stream's events) and Robot's reply
         * builder without any network, so the first question after the panel opens doesn't pay for it (most of a second
         * on a cold JVM). Background thread only; never fails.
         */
        fun warm() {
            try {
                AnthropicRequests.params(
                    app.heyrobot.core.AnthropicModel.DEFAULT, app.heyrobot.core.HelperPlan(app.heyrobot.core.HelperEffort.LOW, 1),
                    emptyList(), search = true, cacheLast = true, messages = listOf(AnthropicRequests.user(listOf(app.heyrobot.core.HelperBlock.Text("hi")))),
                )
                val mapper = com.anthropic.core.jsonMapper()
                val reply = AnthropicReply()
                for (e in WARM_EVENTS) reply.add(mapper.readValue(e, com.anthropic.models.beta.messages.BetaRawMessageStreamEvent::class.java).validate())
                reply.done().content.forEach { it.toParam() }
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
        fun failure(t: Throwable): HelperFailure = when (t) {
            is UnauthorizedException -> HelperFailure.KEY_REFUSED
            is PermissionDeniedException -> HelperFailure.of(403, type(t))
            is NotFoundException -> HelperFailure.MODEL_UNAVAILABLE
            is RateLimitException -> HelperFailure.RATE_LIMITED
            is BadRequestException -> HelperFailure.of(400, type(t))
            is UnprocessableEntityException -> HelperFailure.BAD_REQUEST
            // 500, 503, 529 (overloaded) ...
            is InternalServerException -> HelperFailure.of(t.statusCode(), type(t))
            // An "error" event after the stream opened (overloaded_error mid-answer): the type says which.
            is SseException -> HelperFailure.of(null, type(t))
            // 402 (billing) and anything else with a status.
            is AnthropicServiceException -> HelperFailure.of(t.statusCode(), type(t))
            is AnthropicIoException, is AnthropicRetryableException -> io(t.cause)
            // Something the SDK couldn't read (an answer it doesn't understand).
            is AnthropicException -> HelperFailure.UNKNOWN
            // OkHttp refuses a header value it can't send ("Unexpected char … in x-api-key value"): the saved key has a
            // character a key never has. The message (which quotes the value) is never read beyond its start or logged.
            is IllegalArgumentException -> if (t.message?.startsWith("Unexpected char") == true) HelperFailure.KEY_REFUSED else HelperFailure.UNKNOWN
            is IOException -> io(t)
            else -> HelperFailure.UNKNOWN
        }

        /**
         * No network, a refused connection, a DNS failure (also GrapheneOS's Network toggle off): "No internet"; a socket
         * that stayed silent (before or during the answer): "Claude took too long to answer".
         */
        private fun io(t: Throwable?): HelperFailure = if (t is InterruptedIOException) HelperFailure.TIMEOUT else HelperFailure.NO_INTERNET

        /** What kind of error [t] is, from its class only (the voice log's sub-code for "Something went wrong"). */
        fun kind(t: Throwable): AnthropicErrorKind = when (t) {
            is AnthropicInvalidDataException -> AnthropicErrorKind.INVALID_DATA
            is AnthropicServiceException -> AnthropicErrorKind.API_ERROR
            // Before IOException: Jackson's errors are IOExceptions.
            is com.fasterxml.jackson.core.JsonProcessingException -> AnthropicErrorKind.JSON
            is AnthropicIoException, is IOException -> AnthropicErrorKind.IO
            is AnthropicException -> AnthropicErrorKind.SDK_OTHER
            is IllegalArgumentException -> AnthropicErrorKind.ILLEGAL_ARGUMENT
            is IllegalStateException -> AnthropicErrorKind.ILLEGAL_STATE
            is NullPointerException -> AnthropicErrorKind.NULL
            is LinkageError -> AnthropicErrorKind.MISSING_CODE
            else -> AnthropicErrorKind.OTHER
        }

        private fun type(e: AnthropicServiceException): String? = try { e.errorType().orElse(null)?.asString() } catch (_: Throwable) { null }
    }
}
