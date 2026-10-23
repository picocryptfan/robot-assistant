// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.i18n.HelperStrings

// Tests only (see core/EnglishView.kt): the helpers' words in English, each the Phrase's `.en()`.

fun HelperKeyFormat.warning(p: Preset, k: String): String? = warningText(p, k)?.en()
fun HelperKeyFormat.refusal(p: Preset): String = refusalText(p).en()
val CatalogModel.detail: String get() = detailText.en()
val Preset.company: String get() = companyText.en()
val Preset.title: String get() = titleText.en()
val Preset.billing: String? get() = billingText?.en()
val Preset.dataUse: String? get() = dataUseText?.en()
val Preset.searchNote: String? get() = searchNoteText?.en()
val NameCheck.Problem.why: String get() = text.en()
val NameCheck.Warning.why: String get() = text.en()
val LanRule.Range.warning: String? get() = warningText?.en()
val LanRule.NOT_LOCAL: String get() = HelperStrings.notLocal().en()
val LanRule.Typed.hint: String? get() = hintText?.en()
