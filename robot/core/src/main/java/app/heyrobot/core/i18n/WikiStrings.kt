// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Wikipedia on this phone's catalog (docs/plans/wikipedia-offline-plan.md; core wiki/*): what Robot says and shows with a
// reply from the copy on this phone. Ids start with "wiki."; every entry has its French. The copy's Settings page has its
// own resources (strings_wiki.xml); the model's prompt lines (WikiAnswer.system) are not text.

object WikiStrings : Strings("wiki", area = "wiki") {
    /** M2: after every reply from the copy, written or spoken. */
    val from = s("from", "From Wikipedia on this phone, {month}.", "Selon Wikipédia sur ce téléphone, {month}.")
    /** P4: the model gave no words from what the copy found. */
    val nothing = s("nothing", "Wikipedia on this phone has nothing on {subject}.", "Wikipédia sur ce téléphone n'a rien sur {subject}.")
    /** M4 (review-wikipedia-p4): the copy's file crashed its reader; said once, then the model answers alone until it's downloaded again. */
    val broken = s("broken", "I couldn't read Wikipedia on this phone. The file may be damaged, so I'll answer without it until you download it again.",
        "Je n'ai pas pu lire Wikipédia sur ce téléphone. Le fichier est peut-être abîmé, alors je réponds sans lui jusqu'à ce que tu le télécharges de nouveau.")
    /** P4b: the card under a reply from the copy (WikiCard), without the copy's month, then with it. */
    val card = s("card", "From Wikipedia on this phone", "Selon Wikipédia sur ce téléphone")
    val cardMonth = s("card_month", "From Wikipedia on this phone, {month}", "Selon Wikipédia sur ce téléphone, {month}")
    /** P4b: the card's licence line, a link to the licence. */
    val licence = s("licence", "Text under {licence}", "Texte sous licence {licence}")
}
