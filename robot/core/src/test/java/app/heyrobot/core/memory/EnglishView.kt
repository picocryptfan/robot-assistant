// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.MemoryStrings

// Tests only (see core/EnglishView.kt): Robot's memory replies in English, or in French with `fr`.

private fun langs(fr: Boolean): Langs = Langs.of(if (fr) Lang.FR else Lang.EN)

fun MemoryReplies.saved(c: MemoryCommand.Remember, o: AddOutcome): String = savedText(c, o).render(langs(c.language == "fr"))
fun MemoryReplies.forgotten(n: Int, fr: Boolean): String = forgottenText(n).render(langs(fr))
fun MemoryReplies.nothingToForget(fr: Boolean): String = nothingToForgetText().render(langs(fr))
fun MemoryReplies.notFound(fr: Boolean): String = notFoundText().render(langs(fr))
fun MemoryReplies.which(fr: Boolean): String = whichText().render(langs(fr))
fun MemoryReplies.recall(about: String, found: List<MemoryItem>, private: Int, fr: Boolean): String = recallText(about, found, private, fr).render(langs(fr))
fun MemoryReplies.aboutMe(total: Int, examples: List<MemoryItem>, fr: Boolean): String = aboutMeText(total, examples, fr).render(langs(fr))
fun MemoryReplies.forgetAll(fr: Boolean): String = forgetAllText().render(langs(fr))
fun MemoryReplies.unlock(fr: Boolean, saving: Boolean = false, changing: Boolean = false): String = unlockText(saving, changing).render(langs(fr))
fun MemoryReplies.off(fr: Boolean): String = offText().render(langs(fr))
fun MemoryReplies.cantOpen(load: MemoryLoad, fr: Boolean): String = cantOpenText(load).render(langs(fr))
fun MemoryReplies.savedCard(item: MemoryItem): Pair<String, String> = savedCardText(item).en() to MemoryHygiene.shown(item)
val MemoryReplies.CARD_FORGOTTEN: String get() = MemoryStrings.cardForgotten().en()
val MemoryReplies.CARD_WHICH: String get() = MemoryStrings.cardWhich().en()
val MemoryReplies.CARD_SAVED_DETAIL: String get() = cardSavedDetailText().en()
val MemoryReplies.CARD_FORGOTTEN_DETAIL: String get() = cardForgottenDetailText().en()
val MemoryAccess.UNLOCK_TO_SAVE: String get() = MemoryStrings.unlockToSave().en()
val MemoryAccess.UNLOCK_TO_CHANGE: String get() = MemoryStrings.unlockToChange().en()
