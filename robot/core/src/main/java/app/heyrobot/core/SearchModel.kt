// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * Settings › Memory › "Better search": EmbeddingGemma 2 Text 270M (Apache-2.0), the CPU file, pinned by size and SHA-256
 * like the other models (here beside them: ModelState.kt, VoiceModels.kt, MtBundle.kt, so :brain may read it and core memory
 * builds no network address); checked before it is ever loaded. Assistant setup offers it once ([app.heyrobot.core.memory.MemorySettings.searchModelOffer]).
 */
object SearchModel {
    const val FILE = "embeddinggemma-2-text-270m.litertlm"
    const val BYTES = 164_626_432L
    const val SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
    /** The pinned source: this repository at this commit (checked 7 Nov 2026 against the Hugging Face API: size and SHA-256). */
    const val REPO = "litert-community/embeddinggemma-2-text-270m-litert-lm"
    const val REVISION = "9be6e8b90982095dc05c2bd162e4b954ee4dbac7"
    val URL: String get() = "https://huggingface.co/$REPO/resolve/$REVISION/$FILE"
    /** The size Robot asks the engine for (outputSize, normalize = true). */
    const val DIM = 256
    /** maxInputLength for an item; transcript chunks take [MAX_INPUT_CHUNK]. */
    const val MAX_INPUT = 512
    const val MAX_INPUT_CHUNK = 1_024
    /** The least cosine for an item to go into a prompt (no threshold separates "no answer" cleanly: the prompt says "only if they help"). */
    const val MIN_SCORE = 0.60
    val TITLE: String get() = titleText().en()

    /** [TITLE] in words without a language yet. */
    fun titleText(): app.heyrobot.core.i18n.Phrase = app.heyrobot.core.i18n.MemoryStrings.searchModelTitle()
}
