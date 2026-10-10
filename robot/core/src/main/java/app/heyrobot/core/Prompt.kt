package app.heyrobot.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Pure. What Gemma sees: a short system prompt, the newest turns that fit, and the new question.
// E2B on the TPU has about 4,200 tokens of context (maxNumTokens 4096): the window keeps the system
// prompt plus as many recent exchanges as fit, leaving room for the reply.

enum class PromptRole { USER, MODEL }

/** One turn as sent to the model. [image] marks the turn that carries the picture (only ever the newest). */
data class PromptTurn(val role: PromptRole, val text: String, val image: Boolean = false)

object Prompt {
    /** Kept short: every token here is paid on every rebuilt conversation. */
    fun system(now: ZonedDateTime, locked: Boolean): String {
        val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        val date = now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US))
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
        return buildString {
            append("You are Robot, a private assistant that runs entirely on this phone. ")
            append("Answer in plain, friendly language. Keep answers short (one to three sentences) unless asked for more, ")
            append("because they may be read aloud. Avoid tables. ")
            append("You have no internet access: for news, weather or prices, say you can't check that. ")
            append("Use a tool only when the person clearly asks for that action; otherwise just answer. ")
            append("It is $day, $date, $time (${now.zone.id}).")
            if (locked) append(" The phone is locked: you can answer questions and set timers and alarms only.")
        }
    }

    /** The text of the newest user turn: the question, after the screen's text when the person asked about the screen. */
    fun userText(question: String, screenText: String?, attachment: Attachment): String {
        val q = question.trim().ifEmpty { defaultQuestion(attachment) }
        if (screenText.isNullOrBlank()) return q
        return "Text on my screen:\n\"\"\"\n$screenText\n\"\"\"\n\n$q"
    }

    fun defaultQuestion(attachment: Attachment): String = when (attachment) {
        Attachment.PHOTO -> "What's in this photo?"
        Attachment.SCREEN -> "What's on my screen? Summarise it briefly."
        Attachment.NONE -> ""
    }

    /** How a past turn is replayed when a conversation is rebuilt: images and screen text are not resent. */
    fun replayText(text: String, attachment: Attachment): String = when (attachment) {
        Attachment.PHOTO -> "[I showed you a photo] $text"
        Attachment.SCREEN -> "[I showed you my screen] $text"
        Attachment.NONE -> text
    }.trim()
}

object TokenEstimate {
    /** Conservative for English with Gemma's tokenizer (about 4 characters per token); errs towards dropping a turn. */
    const val CHARS_PER_TOKEN = 3.0
    /** Gemma 4 vision tokens per image as estimated before the engine reports the real count. */
    const val IMAGE_TOKENS = 300
    /** Turn markers and role names the chat template adds. */
    const val TURN_OVERHEAD = 8

    fun text(s: String): Int = kotlin.math.ceil(s.length / CHARS_PER_TOKEN).toInt()
    fun turn(t: PromptTurn): Int = text(t.text) + TURN_OVERHEAD + if (t.image) IMAGE_TOKENS else 0
}

object ContextWindow {
    /** EngineConfig.maxNumTokens on the TPU (as the probe and the lab load it). */
    const val MAX_TOKENS = 4096
    /** Room kept for the reply (Robot also stops a reply after [Limits.MAX_REPLY_CHUNKS] chunks). */
    const val REPLY_RESERVE = 768

    /** Tokens left for past turns once the system prompt, the tools and the new turn are counted. */
    fun historyBudget(fixedTokens: Int, newTurnTokens: Int): Int = MAX_TOKENS - REPLY_RESERVE - fixedTokens - newTurnTokens

    /** True when the live conversation cannot take the new turn plus a reply and must be rebuilt shorter. */
    fun needsRebuild(liveTokens: Int, newTurnTokens: Int): Boolean = liveTokens + newTurnTokens + REPLY_RESERVE > MAX_TOKENS

    /**
     * The newest whole exchanges (a user turn and the model turns after it) whose estimate fits [budget].
     * Never splits an exchange, never starts with a model turn, keeps the order.
     */
    fun fit(history: List<PromptTurn>, budget: Int, cost: (PromptTurn) -> Int = TokenEstimate::turn): List<PromptTurn> {
        if (budget <= 0 || history.isEmpty()) return emptyList()
        // Split into exchanges, each starting at a user turn; model turns before the first user turn are dropped.
        val exchanges = mutableListOf<MutableList<PromptTurn>>()
        for (t in history) {
            if (t.role == PromptRole.USER) exchanges += mutableListOf(t)
            else exchanges.lastOrNull()?.add(t)
        }
        val kept = ArrayDeque<List<PromptTurn>>()
        var used = 0
        for (ex in exchanges.asReversed()) {
            val c = ex.sumOf(cost)
            if (used + c > budget) break
            used += c
            kept.addFirst(ex)
        }
        return kept.flatten()
    }
}

object Limits {
    /** A reply longer than this many streamed chunks is stopped (LiteRT-LM has no output cap of its own on the TPU). */
    const val MAX_REPLY_CHUNKS = 700
    /** Screen text sent with "Ask about this screen": about 1,000 tokens. */
    const val SCREEN_TEXT_CHARS = 3_200
}
