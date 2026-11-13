// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Scam texts (idea 17): the one line Robot says before a text that looks like a scam (messages/ScamCheck.kt), and the
// senders' names where French differs. Three rules only, each about a named sender (fix-scam-texts-c). Ids start with "scam."; the area is "messages". Québec French (tu). It only warns: no line here may call a
// message safe.

object ScamStrings : Strings("scam", area = "messages") {
    val callBack = s("call_back", "This looks like a scam: {name} doesn't text threats with a number to call.",
        "Ça ressemble à une arnaque : {name} n'envoie pas de menaces par texto avec un numéro à appeler.")
    val wrongSender = s("wrong_sender", "This looks like a scam: that link isn't one of {name}'s addresses.",
        "Ça ressemble à une arnaque : ce lien n'est pas une des adresses de {name}.")
    /** A short link under a sender's tag: Robot can't see where it goes, so it says only that. */
    val shortLink = s("short_link", "This looks like a scam: a short link hides where it goes.",
        "Ça ressemble à une arnaque : un lien court cache où il mène.")
    val lookalike = s("lookalike", "This looks like a scam: that link's address imitates {name}.",
        "Ça ressemble à une arnaque : l'adresse de ce lien imite {name}.")
    /** Before the summary of "summarize my messages", in code, never from the model: which conversation and why. */
    val inSummary = s("in_summary", "About {name}'s message: {why}", "Message de {name} : {why}")
    /** Locked: how many conversations, never which one or why (no sender, link or words). */
    val locked = plural("locked", "n",
        Plural(one = "{n} conversation looks like a scam.", other = "{n} conversations look like a scam."),
        Plural(one = "{n} conversation ressemble à une arnaque.", other = "{n} conversations ressemblent à une arnaque."))

    // Senders whose name differs in French; the others are names, said as written.
    val canadaPost = s("canada_post", "Canada Post", "Postes Canada")
    val cra = s("cra", "the CRA", "l'ARC")
    val rcmp = s("rcmp", "the RCMP", "la GRC")
    val nationalBank = s("national_bank", "National Bank", "la Banque Nationale")
    val scotiabank = s("scotiabank", "Scotiabank", "la Banque Scotia")
    val videotron = s("videotron", "Videotron", "Vidéotron")
}
