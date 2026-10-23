// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.1 step 5, §4.5; job F6): rules first. Before the model is asked anything,
// Robot's own rules read what they can: the type sniffed from the file's first bytes (never its name or the sender's
// claim), where it came from, and the words in its text ("Receipt", « Reçu », "Invoice", « Facture », "Total", "TPS",
// "Due date", « Date d'échéance », "T4", « Relevé 1 »…). When they settle the kind, and FileFields finds the fields that
// kind needs, no model is asked at all. Your filing rules (FilingRule) come first of all when they match: the most recent
// wins, and a rule never changes a level, never deletes, never sends (§4.5).
//
// Your rules are made only from your words or your taps (D6, D10): F8 keeps them in memory as items with a `filing` field
// and hands them here; until then the list is empty. A rule from content (someone else's backup, a memory import) is
// shown but never applied: [FilingRule.tainted].

/**
 * One of your filing rules (§4.5): when a file of [kind] from [merchant] (or whose text holds [words]) comes in, file it in
 * [drawer], with [tags], named [nameFirst] or date first. [at]: when it was made (the most recent wins). [tainted]: it came
 * from content, and is never applied.
 */
data class FilingRule(
    val kind: FileKind? = null,
    val merchant: String? = null,
    val words: String? = null,
    val drawer: String? = null,
    val tags: List<String> = emptyList(),
    val nameFirst: Boolean? = null,
    val at: Long = 0,
    val tainted: Boolean = false,
) {
    /** A rule matches on something and does something. */
    val usable: Boolean get() = !tainted && (kind != null || merchant != null || words != null) && (drawer != null || tags.isNotEmpty() || nameFirst != null)

    override fun toString(): String = "FilingRule($kind, ${merchant != null}, ${words != null})" // l10n: not text (never a merchant or a word in a log)
}

/**
 * What the rules made of a file: [kind] when they settle it ([settled]), the kinds they weigh in [scores] (the words each
 * kind's list found), and [read]: whether its contents need reading at all (a video, an archive, an audio file are kept by
 * type and never read).
 */
data class KindVote(val kind: FileKind?, val settled: Boolean, val scores: Map<FileKind, Int> = emptyMap(), val read: Boolean = true) {
    /** The kinds the words point to, strongest first (for a question's chips). */
    val leaning: List<FileKind> get() = scores.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key }
}

object FileRules {
    /** A kind settles when its words score at least this and beat every other kind's by [LEAD]. EST, set after F0a's set. */
    const val SETTLE = 3
    const val LEAD = 2

    private fun words(vararg w: String) = Regex("(?<![\\p{L}\\d])(" + w.joinToString("|") + ")(?![\\p{L}\\d])") // l10n: not text (a parser's pattern)

    // Each list: a strong word scores 2, a weak one 1 (folded: lower case, no accents).
    private val SIGNALS: Map<FileKind, Pair<Regex, Regex>> = mapOf(
        FileKind.RECEIPT to (words("receipt", "sales receipt", "recu de caisse", "recu", "subtotal", "sub-total", "sous-total", "sous total", // l10n: not text (words Robot reads)
            "change due", "monnaie", "cashier", "caissier", "caissiere", "approved", "approuve", "transaction approuvee") to // l10n: not text (words Robot reads)
            words("total", "tps", "tvq", "gst", "hst", "qst", "tvh", "pst", "merci", "thank you", "cash", "comptant", "visa", "mastercard", "interac", "debit")), // l10n: not text (words Robot reads)
        FileKind.BILL to (words("invoice", "due date", "date d'echeance", "echeance", "amount due", "montant du", "balance due", "account number", // l10n: not text (words Robot reads)
            "numero de compte", "billing period", "periode de facturation", "pay by", "payable by", "a payer avant", "statement of account", "releve de compte") to // l10n: not text (words Robot reads)
            words("facture", "bill", "statement", "account", "compte", "previous balance", "solde precedent")), // l10n: not text (words Robot reads)
        FileKind.TAX_SLIP to (words("t4", "t4a", "t5", "t2202", "t5008", "rl-1", "rl-2", "releve 1", "releve 2", "statement of remuneration paid", // l10n: not text (words Robot reads)
            "etat de la remuneration payee", "revenu quebec", "canada revenue agency", "agence du revenu du canada") to // l10n: not text (words Robot reads)
            words("feuillet", "slip", "employment income", "revenus d'emploi", "income tax deducted", "impot")), // l10n: not text (words Robot reads)
        FileKind.TICKET to (words("boarding pass", "carte d'embarquement", "booking confirmation", "confirmation de reservation", "itinerary", "itineraire", // l10n: not text (words Robot reads)
            "e-ticket", "billet electronique") to // l10n: not text (words Robot reads)
            words("ticket", "billet", "reservation", "booking", "gate", "porte", "seat", "siege", "flight", "vol", "departure", "depart")), // l10n: not text (words Robot reads)
        FileKind.MEDICAL to (words("ordonnance", "prescription", "patient", "diagnostic", "diagnosis", "lab results", "resultats de laboratoire") to // l10n: not text (words Robot reads)
            words("clinic", "clinique", "doctor", "medecin", "dr", "hospital", "hopital", "ramq")), // l10n: not text (words Robot reads)
        FileKind.ID to (words("passport", "passeport", "driver's licence", "driver's license", "permis de conduire", "health card", // l10n: not text (words Robot reads)
            "carte d'assurance maladie") to words("date of birth", "date de naissance", "expiry", "expiration")), // l10n: not text (words Robot reads)
        FileKind.DOCUMENT to (words("lease", "bail", "contract", "contrat", "agreement", "entente", "warranty", "garantie", "insurance policy", // l10n: not text (words Robot reads)
            "police d'assurance") to words("signature", "parties", "clause", "article", "terms", "conditions")), // l10n: not text (words Robot reads)
    )

    /**
     * The kind the rules give a file of [type] that came by [source], with its [text] (a text layer, a text file's words, a
     * picture's words as the model copied them, or none). Types Robot doesn't read are settled by type alone.
     */
    fun kindOf(type: FileType, source: FileSource, text: String?): KindVote {
        when (type.group) {
            FileGroup.DANGEROUS -> return KindVote(FileKind.DANGEROUS, settled = true, read = false)
            FileGroup.VIDEO -> return KindVote(FileKind.VIDEO, settled = true, read = false)
            // Kept and named, not read in 1.4 (GIF, BMP…): photos by type.
            FileGroup.IMAGE_OTHER -> return KindVote(FileKind.PHOTO, settled = true, read = false)
            FileGroup.AUDIO -> return KindVote(FileKind.OTHER, settled = true, read = false)
            FileGroup.UNKNOWN, FileGroup.EMPTY -> return KindVote(null, settled = false, read = false)
            else -> {}
        }
        // A transcript Robot saved is a transcript, whatever it says.
        if (source == FileSource.TRANSCRIPT && type.group == FileGroup.TEXT) return KindVote(FileKind.TRANSCRIPT, settled = true, read = false)
        val scores = scores(text)
        val top = scores.maxByOrNull { it.value }
        val second = scores.filterKeys { it != top?.key }.values.maxOrNull() ?: 0
        if (top != null && top.value >= SETTLE && top.value - second >= LEAD) return KindVote(top.key, settled = true, scores = scores)
        // Shared text with no kind's words is a note (§3.1).
        if (type.group == FileGroup.TEXT && (top == null || top.value == 0)) return KindVote(FileKind.NOTE, settled = true, scores = scores)
        return KindVote(null, settled = false, scores = scores)
    }

    /** Each kind's score from [text]'s words: 2 for each strong word found, 1 for each weak one (each word once). */
    fun scores(text: String?): Map<FileKind, Int> {
        if (text.isNullOrBlank()) return emptyMap()
        val f = FileFields.fold(text)
        return SIGNALS.mapValues { (_, p) ->
            2 * p.first.findAll(f).map { it.value }.toSet().size + p.second.findAll(f).map { it.value }.toSet().size
        }.filterValues { it > 0 }
    }

    /**
     * Whether the model's [kind] is one the rules can live with: the words don't point clearly elsewhere. A receipt whose
     * text says "Kind: medical" is still a receipt to the rules (its total, taxes and "Merci"), so the model's MEDICAL
     * meets a clear lead for RECEIPT and isn't taken (the file stays in Inbox with the question).
     */
    fun agrees(kind: FileKind, vote: KindVote): Boolean {
        val top = vote.scores.maxByOrNull { it.value } ?: return true
        if (top.key == kind) return true
        val mine = vote.scores[kind] ?: 0
        return top.value - mine < LEAD
    }

    /**
     * Your rules for a file of [kind] from [merchant] with [text], as one: the drawer of the most recent usable rule that
     * matches and gives one, the tags of every one that matches, the name style of the most recent that gives one; null when
     * none matches. A rule for a merchant matches when its words are whole words of the merchant Robot read ("Hydro" matches
     * "Hydro-Québec", "Esso" matches "ESSO 1234"; folded, letters and digits); a rule for words matches the text.
     */
    fun yours(rules: List<FilingRule>, kind: FileKind, merchant: String?, text: String?): FilingRule? {
        val m = merchant?.let { " " + wordsOf(it) + " " }
        val t = text?.let { key(it) }.orEmpty()
        val hits = rules.filter { it.usable }.sortedByDescending { it.at }.filter { r ->
            (r.kind == null || r.kind == kind) &&
                (r.merchant == null || (m != null && wordsOf(r.merchant).let { it.isNotEmpty() && m.contains(" $it ") })) &&
                (r.words == null || key(r.words).let { it.length >= 2 && t.contains(it) })
        }
        if (hits.isEmpty()) return null
        return FilingRule(
            kind = kind, merchant = merchant, drawer = hits.firstOrNull { it.drawer != null }?.drawer,
            tags = hits.flatMap { it.tags }.distinct(), nameFirst = hits.firstOrNull { it.nameFirst != null }?.nameFirst, at = hits.first().at,
        )
    }

    /** [s]'s words, folded, letters and digits, one space between. */
    private fun wordsOf(s: String): String = FileFields.fold(s).replace(Regex("[^\\p{L}\\d]+"), " ").trim()  // l10n: not text (a parser's pattern)

    private fun key(s: String): String = FileFields.fold(s).filter(Char::isLetterOrDigit)
}
