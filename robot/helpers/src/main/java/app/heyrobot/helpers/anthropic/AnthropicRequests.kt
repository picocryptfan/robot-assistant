// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.anthropic

import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperEffort
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.HelperPrompt
import app.heyrobot.core.HelperTools
import app.heyrobot.core.Json
import app.heyrobot.core.ToolSpec
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.util.Base64

// VOICE PROCESS ONLY. The request Robot sends (docs/ask-claude.md "The API call"), with the SDK's beta types: the
// server-side refusal fallback (`fallbacks: "default"`) is a beta parameter. Prompt caching is a prefix match over
// tools, then system, then messages, so the tools and the system prompt are byte-stable: a fixed order, no timestamp,
// nothing per conversation. A breakpoint goes after the system prompt; in Claude mode the request's own automatic
// breakpoint follows the last message (history is append-only, so no earlier message is ever edited).

internal object AnthropicRequests {
    /** Web searches in one request at most. */
    const val SEARCH_MAX_USES = 3L

    private val EPHEMERAL: BetaCacheControlEphemeral = BetaCacheControlEphemeral.builder().build()

    private val SYSTEM: List<BetaTextBlockParam> = system(HelperPrompt.CLAUDE_SYSTEM)
    /** 0.5.0: the transcript summary's own fixed prompt (docs/conversation-mode.md 7.2), cached the same way. */
    private val TRANSCRIPT: List<BetaTextBlockParam> = system(HelperPrompt.CLAUDE_TRANSCRIPT_SYSTEM)

    private fun system(text: String): List<BetaTextBlockParam> = listOf(BetaTextBlockParam.builder().text(text).cacheControl(EPHEMERAL).build())

    /**
     * Thinking is never sent (on Claude Opus 5.5 it is always on, and `disabled` is a 400); effort is always explicit
     * (the API's default differs by model). Forced tool_choice is a 400 on these models: auto.
     */
    fun params(model: AnthropicModel, plan: HelperPlan, tools: List<ToolSpec>, search: Boolean, cacheLast: Boolean, messages: List<BetaMessageParam>,
        system: String = HelperPrompt.CLAUDE_SYSTEM): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(plan.maxTokens)
            // Only Robot's two fixed prompts: the spoken-answer one, or the transcript summary's.
            .systemOfBetaTextBlockParams(if (system == HelperPrompt.CLAUDE_TRANSCRIPT_SYSTEM) TRANSCRIPT else SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(effort(plan.effort)).build())
            .messages(messages)
        for (t in tools) b.addTool(tool(t))
        // The newer web search (dynamic filtering) is documented for Claude Opus 5.5 and Sonnet 5.5; Claude Haiku 5.5 gets the
        // basic one.
        if (search) {
            if (model == AnthropicModel.HAIKU) b.addTool(BetaWebSearchTool20250305.builder().maxUses(SEARCH_MAX_USES).build())
            else b.addTool(BetaWebSearchTool20260209.builder().maxUses(SEARCH_MAX_USES).build())
        }
        if (tools.isNotEmpty() || search) b.toolChoice(BetaToolChoiceAuto.builder().build())
        // A classifier decline is retried on the model Anthropic recommends for its category. Claude Haiku 5.5 has no
        // server-side fallback (a list is a 400 there), so its requests go without.
        if (model.fallback) {
            b.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            b.fallbacks(BetaFallbacksParam.ofDefault())
        }
        if (cacheLast) b.cacheControl(EPHEMERAL)
        return b.build()
    }

    private fun effort(e: HelperEffort): BetaOutputConfig.Effort = when (e) {
        HelperEffort.LOW -> BetaOutputConfig.Effort.LOW
        HelperEffort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
        HelperEffort.HIGH -> BetaOutputConfig.Effort.HIGH
    }

    /**
     * One of Robot's tools with the same schema as Gemma's (ToolSpec): its properties in order, then the required ones,
     * and no others. Strict, so the API only produces input that fits the schema (Robot still checks it: HelperTools.check).
     */
    fun tool(spec: ToolSpec): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        for (p in spec.params) props.putAdditionalProperty(p.name, JsonValue.from(linkedMapOf("type" to p.type, "description" to p.description)))
        return BetaTool.builder()
            .name(spec.tool.id)
            .description(spec.description)
            .inputSchema(BetaTool.InputSchema.builder().properties(props.build()).required(spec.params.filter { it.required }.map { it.name })
                .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
            .strict(true)
            .build()
    }

    /** A user message from the plain blocks HelperContext built; [before]: tool results it must open with. */
    fun user(blocks: List<HelperBlock>, before: List<BetaContentBlockParam> = emptyList()): BetaMessageParam =
        BetaMessageParam.builder()
            .role(BetaMessageParam.Role.USER)
            .contentOfBetaContentBlockParams(before + blocks.map(::block))
            .build()

    private fun block(b: HelperBlock): BetaContentBlockParam = when (b) {
        is HelperBlock.Text -> BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(b.text).build())
        is HelperBlock.Image -> BetaContentBlockParam.ofImage(BetaImageBlockParam.builder().source(
            BetaBase64ImageSource.builder().mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).data(Base64.getEncoder().encodeToString(b.jpeg)).build(),
        ).build())
    }

    /**
     * The results of Claude's calls, one `tool_result` per `tool_use` id in order, all in one user message. The content
     * is Robot's fixed-vocabulary response as JSON; `is_error` when the action failed or couldn't run.
     */
    fun results(ids: List<String>, results: List<Map<String, Any?>>): List<BetaContentBlockParam> = ids.mapIndexed { i, id ->
        val r = results.getOrNull(i) ?: mapOf("status" to "not_done", "detail" to "Robot didn't run this call.")
        BetaContentBlockParam.ofToolResult(
            BetaToolResultBlockParam.builder().toolUseId(id).content(Json.toJson(r).toString()).isError(isError(r)).build(),
        )
    }

    /** Robot's statuses that mean the action didn't happen for a reason other than the user saying no. */
    private val ERRORS = setOf("failed", "invalid_arguments", "phone_locked_user_must_unlock", "turned_off_in_settings", "not_done_too_many_actions",
        "permission_needed_user_must_allow", "not_found", "app_not_installed", "not_done")

    fun isError(r: Map<String, Any?>): Boolean = r["status"] in ERRORS
}
