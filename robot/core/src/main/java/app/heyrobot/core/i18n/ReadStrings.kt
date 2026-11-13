// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// 1.3, point and read (point-and-read-plan.md): what Robot says around a picture's words read aloud. Ids start with
// "read."; the area is the panel's. The French is Québec French (tu); render() adds its spaces.
object ReadStrings : Strings("read", area = "panel") {
    /** A READ_ALOUD to a model that can't see (NO_VISION): your own GGUF file, say. */
    val needsVision = s("needs_vision", "Reading needs Fast or Smarter.", "Pour lire, il faut Rapide ou Plus futé.")

    // What it is, said first.
    val letter = s("kind_letter", "A letter.", "Une lettre.")
    val label = s("kind_label", "A label.", "Une étiquette.")
    val sign = s("kind_sign", "A sign.", "Un panneau.")
    val receipt = s("kind_receipt", "A receipt.", "Un reçu.")
    val menu = s("kind_menu", "A menu.", "Un menu.")
    val page = s("kind_page", "A page.", "Une page.")
    val note = s("kind_note", "A note.", "Une note.")
    val screen = s("kind_screen", "A screen.", "Un écran.")

    /** Said where the model wrote "[unclear]". */
    val unclear = s("unclear", "I can't read this part.", "Je ne peux pas lire ce passage.")
    /** The picture had no words. */
    val noWords = s("no_words", "I don't see any words in it.", "Je ne vois aucun mot dedans.")
    /** Said last when the words aren't in your language: English for you in English, French for you in French. */
    val offer = s("offer", "Want it in English?", "Tu le veux en français?")
    /** Said and shown once, before your first reading (the plan's question, default yes). */
    val caution = s("caution", "I can misread small print; check numbers that matter.", "Je peux mal lire les petits caractères; vérifie les chiffres importants.")
    /** Your side of a reading started with Read aloud's tap (no words of yours). */
    val asked = s("asked", "Read this.", "Lis ça.")
}
