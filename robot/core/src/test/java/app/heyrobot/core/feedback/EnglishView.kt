// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

// Tests only (see core/EnglishView.kt): the feedback form's words in English, each the Phrase's `.en()`.

val FeedbackKind.label: String get() = labelText.en()
val FeedbackKind.prompt: String get() = promptText.en()
