// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import java.text.Normalizer
import java.util.Locale

// Pure. 1.4, Robot Files (files-1.4-plan.md §9, D15; job F8): your words about your files, in English and Québec French,
// heard without the model. The panel asks it after skills and before hands-free (PanelController, PanelFiles.route); it only
// claims a phrase that names a kind of file Robot keeps (receipt, bill, invoice, lease, slip, ticket; « reçu », « facture »,
// « bail », « feuillet »…), Robot Files' Inbox, or "file this"; never "files" in free_storage's sense ("delete big files",
// « libère de l'espace »), and never what another router or the model should hear (routineCmd, notes, memory, the web).
//
//   File this     "file this", « classe ça » (FileThis, F5)
//   A rule        "Esso receipts go under Car", "put receipts from Esso in Car", "always file Hydro bills under House";
//                 « les reçus d'Esso vont dans Auto », « mets les reçus d'Esso dans Auto »
//   Move          "move the Costco receipt to Taxes", "move it to Car"; « déplace le reçu de Costco dans Impôts »
//   Find          "find my Costco receipt from August", "show my hydro bills", "where's my lease"; « trouve mon reçu de
//                 Costco d'août », « montre mes factures d'Hydro », « où est mon bail »
//   Inbox         "what's in my Inbox"; « qu'est-ce qu'il y a dans ma boîte de réception »
//   Delete        "delete the Costco receipt"; « efface le reçu de Costco »: only ever a card that a tap answers (D9)
//
// A move and a delete name one file: a kind in the singular and a word more (its merchant, "lease"), so "put my receipt in
// the car" stays talk even with a drawer called Car; and a move names a drawer that exists.
//
// A rule needs a merchant and a kind in the plural ("Esso receipts"), so "put my receipts in the car" stays ordinary talk.
// Find needs "my" (« mon », « ma », « mes »), so "find the cheapest tickets to Montréal" stays the web's.
//
// F10, money and dates (answered by code: FileMoney):
//   Spending      "how much did I spend on gas in August", "how much have I spent at Costco this month"; « combien j'ai
//                 dépensé en essence en août », « combien ai-je dépensé chez Costco ce mois-ci ». Never "how much does gas
//                 cost" (the web's) or "how much do I pay for …" (a habit, not a sum).
//   Coming up     "what bills are due this week", "when is my Hydro bill due", "what documents are expiring soon", "when does
//                 my passport expire", "what can I still return", "what's still under warranty"; « quelles factures sont
//                 dues cette semaine », « quand expire mon passeport », « qu'est-ce que je peux encore retourner », « qu'est-ce
//                 qui est encore sous garantie ». Each names bills, a document or a return; "what's due on my calendar" and
//                 "remind me to pay …" (routines') never.
//   Subscriptions "what subscriptions do I have", « quels sont mes abonnements »: listed, never "cancel my … subscription".

/** What your words ask of Robot Files. [lang]: the language they were said in. */
sealed class FilesCommand {
    abstract val lang: Lang

    /** "File this" and its kin (F5's FileThis): [heard] as FileThis read it. */
    data class File(val heard: FileThis.Heard) : FilesCommand() {
        override val lang: Lang get() = heard.lang
    }

    /** A rule: files of [kind] from [merchant] go in the drawer you named ([drawer]: your words for it). */
    data class Rule(val kind: FileKind, val merchant: String, val drawer: String, override val lang: Lang) : FilesCommand() {
        override fun toString(): String = "Rule($kind, $lang)" // l10n: not text (never your words in a log)
    }

    /** Move the file [query] names (null: the one this panel just filed or moved, "it") to the drawer [drawer] names. */
    data class Move(val query: Query?, val drawer: String, override val lang: Lang) : FilesCommand() {
        override fun toString(): String = "Move(${query != null}, $lang)" // l10n: not text (never your words in a log)
    }

    /** Find the files [query] names. */
    data class Find(val query: Query, override val lang: Lang) : FilesCommand() {
        override fun toString(): String = "Find($lang)" // l10n: not text (never your words in a log)
    }

    /** What's in your Inbox. */
    data class Inbox(override val lang: Lang) : FilesCommand()

    /** F10: how much you spent, by code ([query]: what on, where, when). */
    data class Spend(val query: SpendQuery, override val lang: Lang) : FilesCommand()

    /** F10: the dates coming up of [kinds] until [span]'s end, of files whose words hold [terms] (empty: any). */
    data class Upcoming(val kinds: Set<DateKind>, val span: Span, val terms: String, override val lang: Lang) : FilesCommand() {
        override fun toString(): String = "Upcoming($kinds, $span, ${terms.isNotEmpty()}, $lang)" // l10n: not text (never your words in a log)
    }

    /** F10: your subscriptions, as your bills show them. */
    data class Subscriptions(override val lang: Lang) : FilesCommand()

    /**
     * F12: the tax pack or the expense report for [year] (null: not said, last year). Only shown: the export is your tap in
     * Files, to a folder you pick.
     */
    data class Pack(val kind: PackKind, val year: Int?, override val lang: Lang) : FilesCommand()

    /** Delete the file [query] names: a card that only a tap answers. */
    data class Delete(val query: Query, override val lang: Lang) : FilesCommand() {
        override fun toString(): String = "Delete($lang)" // l10n: not text (never your words in a log)
    }
}

/** F10: how far ahead "what's due" looks: [SOON] (each kind's own window), or a day, a week or a month you named. */
enum class Span { SOON, TODAY, TOMORROW, THIS_WEEK, NEXT_WEEK, THIS_MONTH, NEXT_MONTH, ANY }

/**
 * Which files a phrase names: of [kind] (null: any), with [terms] (the merchant and other words, searched), from [month]
 * and [year] when said.
 */
data class Query(val kind: FileKind?, val terms: String, val month: Int? = null, val year: Int? = null) {
    override fun toString(): String = "Query($kind, ${terms.isNotEmpty()}, $month, $year)" // l10n: not text (never your words in a log)
}

object FilesRouter {
    // Words Robot hears, never shows (folded: lower case, no accents).
    internal val KIND_ONE: Map<String, FileKind> = mapOf(
        "receipt" to FileKind.RECEIPT, "bill" to FileKind.BILL, "invoice" to FileKind.BILL, "statement" to FileKind.BILL, // l10n: not text (words Robot hears)
        "document" to FileKind.DOCUMENT, "lease" to FileKind.DOCUMENT, "warranty" to FileKind.DOCUMENT, "contract" to FileKind.DOCUMENT, // l10n: not text (words Robot hears)
        "tax slip" to FileKind.TAX_SLIP, "slip" to FileKind.TAX_SLIP, "ticket" to FileKind.TICKET, "booking" to FileKind.TICKET, // l10n: not text (words Robot hears)
        "boarding pass" to FileKind.TICKET, // l10n: not text (words Robot hears)
        "recu" to FileKind.RECEIPT, "facture" to FileKind.BILL, "bail" to FileKind.DOCUMENT, "garantie" to FileKind.DOCUMENT, // l10n: not text (words Robot hears)
        "contrat" to FileKind.DOCUMENT, "feuillet" to FileKind.TAX_SLIP, "billet" to FileKind.TICKET, "carte d'embarquement" to FileKind.TICKET, // l10n: not text (words Robot hears)
    )
    internal val KIND_MANY: Map<String, FileKind> = mapOf(
        "receipts" to FileKind.RECEIPT, "bills" to FileKind.BILL, "invoices" to FileKind.BILL, "statements" to FileKind.BILL, // l10n: not text (words Robot hears)
        "documents" to FileKind.DOCUMENT, "leases" to FileKind.DOCUMENT, "warranties" to FileKind.DOCUMENT, "contracts" to FileKind.DOCUMENT, // l10n: not text (words Robot hears)
        "tax slips" to FileKind.TAX_SLIP, "slips" to FileKind.TAX_SLIP, "tickets" to FileKind.TICKET, "bookings" to FileKind.TICKET, // l10n: not text (words Robot hears)
        "recus" to FileKind.RECEIPT, "factures" to FileKind.BILL, "baux" to FileKind.DOCUMENT, "garanties" to FileKind.DOCUMENT, // l10n: not text (words Robot hears)
        "contrats" to FileKind.DOCUMENT, "feuillets" to FileKind.TAX_SLIP, "billets" to FileKind.TICKET, // l10n: not text (words Robot hears)
    )
    private val ANY_KIND = KIND_ONE + KIND_MANY
    private fun alt(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val MANY = alt(KIND_MANY.keys)

    private val LEAD = listOf("hey robot ", "robot ", "please ", "can you ", "could you ", "would you ", "s'il te plait ", "stp ", "peux-tu ", "peux tu ", // l10n: not text (words Robot hears)
        "est-ce que tu peux ", "ok ", "okay ") // l10n: not text (words Robot hears)
    private val TAIL = listOf(" please", " s'il te plait", " stp", " robot", " from now on", " a partir de maintenant", " dorenavant", " for me", " pour moi") // l10n: not text (words Robot hears)

    // A merchant: one to four words of letters and digits ("Esso", "Hydro-Québec", "Canadian Tire").
    private const val WORD = "[\\p{L}\\d][\\p{L}\\d&.'-]*" // l10n: not text (a parser's pattern)
    private const val NAME = "($WORD(?: $WORD){0,3})" // l10n: not text (a parser's pattern)
    private const val DRAWER_EN = "(?:the |my |a |an )?($WORD(?: $WORD){0,3}?)(?: drawer| folder)?" // l10n: not text (a parser's pattern)
    private const val DRAWER_FR = "(?:le tiroir |mon tiroir |le |la |l'|mon |ma |mes |les )?($WORD(?: $WORD){0,3}?)" // l10n: not text (a parser's pattern)
    private const val INTO = "(?:in|into|under|to)" // l10n: not text (a parser's pattern)
    private const val OF_FR = "(?:de la |de |d'|du |des )" // l10n: not text (a parser's pattern)

    private val RULES_EN = listOf(
        // "always file Hydro bills under House", "put Esso receipts in Car"
        Regex("^(?:always )?(?:put|file|keep|send) (?:all )?$NAME ($MANY) (?:always )?$INTO $DRAWER_EN$") to Triple(2, 1, 3), // l10n: not text (a parser's pattern)
        // "put receipts from Esso in Car"
        Regex("^(?:always )?(?:put|file|keep|send) (?:all )?(?:my )?($MANY) from $NAME (?:always )?$INTO $DRAWER_EN$") to Triple(1, 2, 3), // l10n: not text (a parser's pattern)
        // "Esso receipts go under Car"
        Regex("^(?:all )?$NAME ($MANY) (?:always )?(?:go|goes|belong|should go|will go) $INTO $DRAWER_EN$") to Triple(2, 1, 3), // l10n: not text (a parser's pattern)
        // "receipts from Esso go in Car"
        Regex("^(?:all )?(?:my )?($MANY) from $NAME (?:always )?(?:go|belong|should go|will go) $INTO $DRAWER_EN$") to Triple(1, 2, 3), // l10n: not text (a parser's pattern)
    )
    private val RULES_FR = listOf(
        // « mets les reçus d'Esso dans Auto », « classe toujours les factures d'Hydro dans Maison »
        Regex("^(?:toujours )?(?:mets|met|classe|range|place)(?: toujours)? (?:tous |toutes )?les ($MANY) $OF_FR$NAME (?:toujours )?dans $DRAWER_FR$") to Triple(1, 2, 3), // l10n: not text (a parser's pattern)
        // « les reçus d'Esso vont dans Auto »
        Regex("^(?:tous |toutes )?les ($MANY) $OF_FR$NAME (?:vont|va|iront|doivent aller)(?: toujours)? dans $DRAWER_FR$") to Triple(1, 2, 3), // l10n: not text (a parser's pattern)
        // F13: « les reçus d'Esso, c'est dans Auto », « les factures de Bell, ça va dans Maison » (Québec's everyday way).
        // FX2 (files-1.4-review.md R1): this form also asks where things are (« c'est dans quel tiroir? ») and says where
        // they are (« c'est dans la boîte à gants »), so it names a drawer that exists, as a move does ([PLACE_FR]).
        Regex("^(?:tous |toutes )?les ($MANY) $OF_FR$NAME (?:c'est|ca va|ca s'en va)(?: toujours)? dans $DRAWER_FR$") to Triple(1, 2, 3), // l10n: not text (a parser's pattern)
    )
    /** FX2: the rule forms of [RULES_FR] that only name a drawer that exists (the « c'est dans » form, the last). */
    private val PLACE_FR = RULES_FR.last().first

    // Which file: some words with a kind's word in them ("the Costco receipt", "receipt from Costco", « le reçu de Costco »).
    private const val WHAT = "([\\p{L}\\d][\\p{L}\\d&.' -]{0,80})" // l10n: not text (a parser's pattern)
    private val MOVE_EN = Regex("^(?:move|put|file) (?:the|my|that|this) $WHAT $INTO $DRAWER_EN$") // l10n: not text (a parser's pattern)
    private val MOVE_IT_EN = Regex("^move (?:it|this|that|this one|that one|this file|that file) $INTO $DRAWER_EN$") // l10n: not text (a parser's pattern)
    private val MOVE_FR = Regex("^(?:deplace|mets|met|range|classe|bouge) (?:le|la|mon|ma|ce|cette|l') ?$WHAT (?:dans|vers|sous) $DRAWER_FR$") // l10n: not text (a parser's pattern)
    private val MOVE_IT_FR = Regex("^(?:deplace-le|deplace-la|deplace le|deplace la|deplace ca|deplace ce fichier|bouge-le|bouge-la|bouge le|bouge la|bouge ca|" + // l10n: not text (a parser's pattern)
        "mets-le|mets-la|range-le|range-la|classe-le|classe-la) (?:dans|vers) $DRAWER_FR$") // l10n: not text (a parser's pattern)

    private val FIND_EN = Regex("^(?:find|show|show me|open|look for|pull up|get|where is|where's|wheres|where are) (?:me )?(?:all )?my $WHAT$") // l10n: not text (a parser's pattern)
    private val FIND_FR = Regex("^(?:trouve|trouve-moi|montre|montre-moi|ouvre|cherche|cherche-moi|affiche|affiche-moi|retrouve|retrouve-moi|sors-moi|" + // l10n: not text (a parser's pattern)
        "trouver|montrer|ouvrir|chercher|afficher|retrouver|ou est|ou sont|ou j'ai mis|ou est-ce que j'ai mis|ou c'est que j'ai mis|" + // l10n: not text (a parser's pattern)
        "il est ou|elle est ou|ils sont ou|elles sont ou|c'est ou) (?:moi )?(?:tous |toutes )?(?:mon|ma|mes) $WHAT$") // l10n: not text (a parser's pattern)
    private val IN_FILES = Regex(" (?:in|from) (?:my files|robot files)$| dans (?:mes fichiers|les fichiers de robot)$") // l10n: not text (a parser's pattern)

    private val INBOX = setOf("what's in my inbox", "whats in my inbox", "what is in my inbox", "what's in my files inbox", "what's in my robot files inbox", // l10n: not text (words Robot hears)
        "what's waiting in my inbox", "show my files inbox", "open my files inbox") // l10n: not text (words Robot hears)
    private val INBOX_FR = setOf("qu'est-ce qu'il y a dans ma boite de reception", "qu'est-ce qui est dans ma boite de reception", // l10n: not text (words Robot hears)
        "qu'y a-t-il dans ma boite de reception", "quoi de neuf dans ma boite de reception", "ouvre ma boite de reception", // l10n: not text (words Robot hears)
        "montre-moi ma boite de reception", "montre ma boite de reception", "qu'est-ce qui attend dans ma boite de reception", // l10n: not text (words Robot hears)
        "est-ce qu'il y a quelque chose dans ma boite de reception", "y a-t-il quelque chose dans ma boite de reception") // l10n: not text (words Robot hears)

    private val DELETE_EN = Regex("^(?:delete|remove|erase|trash|throw away|throw out|get rid of) (?:the|my|that|this|all my|all the) $WHAT$") // l10n: not text (a parser's pattern)
    private val DELETE_FR = Regex("^(?:efface|supprime|jette|enleve|elimine) (?:le|la|mon|ma|ce|cette|les|mes|l') ?$WHAT$") // l10n: not text (a parser's pattern)

    /**
     * Words a merchant never holds: "remember that Esso receipts go in Car" is memory's, "I think Costco receipts go in the
     * car" is talk.
     */
    private val NOT_IN_MERCHANT = setOf("remember", "remind", "that", "i", "me", "you", "we", "they", "he", "she", "it", "think", "know", "say", "said", // l10n: not text (words Robot hears)
        "tell", "if", "when", "why", "how", "where", "what", "do", "did", "does", "should", "will", "would", "can", "could", "not", "don't", "never", "and", // l10n: not text (words Robot hears)
        "or", "but", "so", "note", "je", "j'ai", "que", "qu'", "qui", "tu", "souviens-toi", "souviens", "toi", "rappelle-toi", "rappelle", "moi", "dis", "pense", // l10n: not text (words Robot hears)
        "si", "quand", "pourquoi", "comment", "ou", "et", "mais", "pas", "jamais", "est-ce", "ca", "c'est") // l10n: not text (words Robot hears)
    /** Words that can't start a merchant ("put my receipts in…", "put the receipts…"). */
    private val NOT_MERCHANT = setOf("my", "the", "these", "those", "this", "that", "all", "every", "your", "our", "some", "any", "old", "new", // l10n: not text (words Robot hears)
        "mes", "les", "ces", "tous", "toutes", "mon", "ma", "ton", "ta") // l10n: not text (words Robot hears)

    private val MONTHS: Map<String, Int> = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december", // l10n: not text (words Robot hears)
    ).withIndex().associate { it.value to it.index + 1 } + listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre", // l10n: not text (words Robot hears)
    ).withIndex().associate { it.value to it.index + 1 }
    private val WHEN = Regex("(?:^| )(?:from|in|of|for|de|d'|en|du mois de|du mois d')? ?(${alt(MONTHS.keys)})(?: (\\d{4}))?(?= |$)") // l10n: not text (a parser's pattern)
    private val YEAR = Regex("(?:^| )(?:from |in |of |for |de |en )?((?:19|20)\\d{2})(?= |$)") // l10n: not text (a parser's pattern)
    /** Kinds' words that say more than the kind: searched as well. */
    internal val SPECIFIC = listOf("lease", "leases", "warranty", "warranties", "contract", "contracts", "boarding pass", "bail", "baux", "garantie", // l10n: not text (words Robot hears)
        "garanties", "contrat", "contrats", "carte d'embarquement") // l10n: not text (words Robot hears)
    /** Small words a search leaves out. */
    internal val FILLER = setOf("from", "of", "the", "my", "for", "at", "in", "a", "an", "all", "de", "d", "du", "des", "le", "la", "les", "mon", "ma", "mes", // l10n: not text (words Robot hears)
        "un", "une", "l", "files", "fichiers", "robot") // l10n: not text (words Robot hears)

    // F10's phrases (folded).
    private val SPEND_EN = Regex("^(?:how much (?:money )?(?:did|have) i (?:spend|spent|pay|paid)|how much (?:money )?i (?:spent|paid)|what did i spend|what have i spent)(?: (.*))?$") // l10n: not text (a parser's pattern)
    private val SPEND_FR = Regex("^(?:combien (?:d'argent )?(?:j'ai|ai-je|est-ce que j'ai|que j'ai) (?:depense|paye)|qu'est-ce que j'ai depense)(?: (.*))?$") // l10n: not text (a parser's pattern)
    /** F13: « combien j'ai mis en gaz », « … chez Costco »: « mis » only before « en » or « chez » (never « combien j'ai mis de temps »). */
    private val SPEND_PUT_FR = Regex("^combien (?:d'argent )?(?:j'ai|ai-je|est-ce que j'ai) mis ((?:en|chez) .*)$") // l10n: not text (a parser's pattern)

    private const val SPAN_EN = "(?: (today|tomorrow|this week|next week|this month|next month|soon|in the next few days))?" // l10n: not text (a parser's pattern)
    private const val SPAN_FR = "(?: (aujourd'hui|demain|cette semaine|la semaine prochaine|ce mois-ci|ce mois ci|ce mois|le mois prochain|bientot|prochainement))?" // l10n: not text (a parser's pattern)
    private val SPANS = mapOf("today" to Span.TODAY, "aujourd'hui" to Span.TODAY, "tomorrow" to Span.TOMORROW, "demain" to Span.TOMORROW, // l10n: not text (words Robot hears)
        "this week" to Span.THIS_WEEK, "cette semaine" to Span.THIS_WEEK, "next week" to Span.NEXT_WEEK, "la semaine prochaine" to Span.NEXT_WEEK, // l10n: not text (words Robot hears)
        "this month" to Span.THIS_MONTH, "ce mois-ci" to Span.THIS_MONTH, "ce mois ci" to Span.THIS_MONTH, "ce mois" to Span.THIS_MONTH, // l10n: not text (words Robot hears)
        "next month" to Span.NEXT_MONTH, "le mois prochain" to Span.NEXT_MONTH) // l10n: not text (words Robot hears)

    /** Documents whose expiry you may ask about by name. */
    private const val DOC_EN = "(passport|licence|license|driver's licence|driver's license|drivers licence|drivers license|driving licence|permit|health card|" + // l10n: not text (a parser's pattern)
        "insurance|insurance card|insurance policy|id|id card|visa|membership|membership card|lease)" // l10n: not text (a parser's pattern)
    private const val DOC_FR = "(passeport|permis|permis de conduire|carte d'assurance maladie|carte soleil|carte d'identite|assurance|police d'assurance|visa|bail|" + // l10n: not text (a parser's pattern)
        "carte de membre)" // l10n: not text (a parser's pattern)

    private val UPCOMING: List<Triple<Regex, Set<DateKind>, Lang>> = listOf(
        // Bills.
        Triple(Regex("^(?:what|which) (?:bills|invoices)(?: do i have)? (?:are|is) (?:due|coming due)$SPAN_EN$"), setOf(DateKind.DUE), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:what|which) (?:bills|invoices) do i (?:have to|need to|still have to) pay$SPAN_EN$"), setOf(DateKind.DUE), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:are there|do i have) (?:any )?(?:bills|invoices) (?:due|to pay)$SPAN_EN$"), setOf(DateKind.DUE), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^quelles factures (?:sont dues|sont a payer|je dois payer|dois-je payer|est-ce que je dois payer|arrivent a echeance|viennent a echeance)$SPAN_FR$"), // l10n: not text (a parser's pattern)
            setOf(DateKind.DUE), Lang.FR),
        Triple(Regex("^(?:est-ce que )?j'ai des factures (?:a payer|dues)$SPAN_FR$"), setOf(DateKind.DUE), Lang.FR), // l10n: not text (a parser's pattern)
        // F13: Québec's « -tu » question and « comptes » for bills (« mon compte d'Hydro »), only with « payer » or « dus »
        Triple(Regex("^j'ai-tu des (?:factures|comptes) (?:a payer|dues|dus)$SPAN_FR$"), setOf(DateKind.DUE), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:qu'est-ce que|quoi) j'ai comme (?:factures|comptes) a payer$SPAN_FR$"), setOf(DateKind.DUE), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^quels comptes (?:sont dus|sont a payer|je dois payer|j'ai a payer|dois-je payer)$SPAN_FR$"), setOf(DateKind.DUE), Lang.FR), // l10n: not text (a parser's pattern)
        // Expiry.
        Triple(Regex("^(?:what|which) (?:documents|ids|papers|of my documents|of my ids|of my papers) (?:are|is) (?:expiring|going to expire|about to expire|due to expire)$SPAN_EN$"), // l10n: not text (a parser's pattern)
            setOf(DateKind.EXPIRY), Lang.EN),
        Triple(Regex("^(?:what's|whats|what is|is anything|is there anything) expiring in my files$SPAN_EN$"), setOf(DateKind.EXPIRY), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^quels (?:documents|papiers) (?:expirent|vont expirer|arrivent a expiration)$SPAN_FR$"), setOf(DateKind.EXPIRY), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^qu'est-ce qui expire dans mes fichiers$SPAN_FR$"), setOf(DateKind.EXPIRY), Lang.FR), // l10n: not text (a parser's pattern)
        // Returns and warranties.
        Triple(Regex("^(?:what|which (?:receipts|purchases|things)) can i still return$"), setOf(DateKind.RETURN), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^what (?:returns|return windows) (?:are )?(?:ending|closing)$SPAN_EN$"), setOf(DateKind.RETURN), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:qu'est-ce que je peux|quels achats je peux|quels achats puis-je) encore (?:retourner|rapporter)$"), setOf(DateKind.RETURN), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:what's|whats|what is) still under warranty$"), setOf(DateKind.WARRANTY), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:what|which) warranties (?:are )?(?:ending|expiring|running out)$SPAN_EN$"), setOf(DateKind.WARRANTY), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^qu'est-ce qui est encore sous garantie$"), setOf(DateKind.WARRANTY), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^quelles garanties (?:finissent|expirent|se terminent|prennent fin)$SPAN_FR$"), setOf(DateKind.WARRANTY), Lang.FR), // l10n: not text (a parser's pattern)
    )

    /** "When is my Hydro bill due", "when does my passport expire": one file's date, by the words that name it. */
    private val NAMED: List<Triple<Regex, Set<DateKind>, Lang>> = listOf(
        Triple(Regex("^when (?:is|are) (?:my|the) ([\\p{L}\\d&.' -]{2,40}?) (?:bill|bills|invoice) due$"), setOf(DateKind.DUE), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^when (?:does|do|will) my $DOC_EN (?:expire|run out)$"), setOf(DateKind.EXPIRY), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^when is my $DOC_EN (?:expiring|up for renewal)$"), setOf(DateKind.EXPIRY), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^when does the warranty (?:on|for) (?:my |the )?([\\p{L}\\d&.' -]{2,40}?) (?:end|expire|run out)$"), setOf(DateKind.WARRANTY), Lang.EN), // l10n: not text (a parser's pattern)
        Triple(Regex("^(?:quand|c'est quand que|quand est-ce que) (?:est due|dois-je payer|est-ce que je dois payer|je dois payer) (?:ma|la) facture (?:d'|de |du |de la )?([\\p{L}\\d&.' -]{2,40})$"), // l10n: not text (a parser's pattern)
            setOf(DateKind.DUE), Lang.FR),
        Triple(Regex("^quand (?:expire|est-ce qu'expire|est-ce que expire) (?:mon|ma) $DOC_FR$"), setOf(DateKind.EXPIRY), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^quand (?:mon|ma) $DOC_FR (?:expire|expire-t-il|expire-t-elle)$"), setOf(DateKind.EXPIRY), Lang.FR), // l10n: not text (a parser's pattern)
        Triple(Regex("^quand (?:finit|se termine|expire) la garantie (?:de |du |de la |d'|sur )?(?:mon |ma |le |la )?([\\p{L}\\d&.' -]{2,40})$"), // l10n: not text (a parser's pattern)
            setOf(DateKind.WARRANTY), Lang.FR),
    )

    private val SUBS_EN = Regex("^(?:(?:what|which) subscriptions (?:do i have|do i pay for|am i paying for)|(?:show|list|find)(?: me)? my subscriptions|what are my subscriptions)$") // l10n: not text (a parser's pattern)
    private val SUBS_FR = Regex("^(?:quels sont mes abonnements|(?:montre|montre-moi|liste|trouve)(?: moi)? mes abonnements|quels abonnements (?:j'ai|ai-je|je paie|je paye|est-ce que j'ai))$") // l10n: not text (a parser's pattern)

    // F12's phrases (folded): "make my 2026 tax pack", "make my expense report for last year"; « fais mon dossier d'impôts
    // 2026 », « prépare mon rapport de dépenses de l'an dernier ». Never "do my taxes" or a tax deadline.
    private const val PACK_YEAR_EN = "((?:19|20)\\d{2}|last year|this year)" // l10n: not text (a parser's pattern)
    private const val PACK_YEAR_FR = "((?:19|20)\\d{2}|l'an dernier|l'an passe|l'annee derniere|l'annee passee|cette annee)" // l10n: not text (a parser's pattern)
    private val PACK_EN = Regex("^(?:make|build|prepare|create|put together|export|get|assemble)(?: me)? (?:my|a|the)(?: ((?:19|20)\\d{2}))? " + // l10n: not text (a parser's pattern)
        "(tax pack|tax package|(?:business )?expense report|(?:business )?expenses report)(?: (?:for|of) $PACK_YEAR_EN)?$") // l10n: not text (a parser's pattern)
    private val PACK_FR = Regex("^(?:fais|fais-moi|prepare|prepare-moi|cree|exporte|monte|sors|sors-moi|genere|compile|rassemble)(?: moi)? " + // l10n: not text (a parser's pattern)
        "(?:mon|ma|le|la|mes|les)(?: ((?:19|20)\\d{2}))? " + // l10n: not text (a parser's pattern)
        "(dossier d'impots|dossier d'impot|dossier fiscal|papiers d'impots|papiers d'impot|rapport de depenses d'affaires|rapport de depenses)" + // l10n: not text (a parser's pattern)
        "(?: (?:de |d'|pour |pour l'annee |de l'annee )?$PACK_YEAR_FR)?$") // l10n: not text (a parser's pattern)

    /** The pack [s] asks for, or null. */
    private fun pack(s: String, today: java.time.LocalDate): FilesCommand? {
        for ((re, lang) in listOf(PACK_EN to Lang.EN, PACK_FR to Lang.FR)) re.matchEntire(s)?.let { m ->
            val kind = if (m.groupValues[2].startsWith("tax") || m.groupValues[2].startsWith("dossier") || m.groupValues[2].startsWith("papiers")) PackKind.TAX else PackKind.EXPENSES
            val said = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val year = when {
                said.isEmpty() -> null
                said == "this year" || said == "cette annee" -> today.year
                said.toIntOrNull() != null -> said.toInt()
                else -> today.year - 1
            }
            return FilesCommand.Pack(kind, year, lang)
        }
        return null
    }

    /** F10's phrases in [s] (folded, [orig] as said): spending, what's coming up, subscriptions; null when none. */
    private fun money(orig: String, s: String, today: java.time.LocalDate): FilesCommand? {
        for ((re, lang) in listOf(SPEND_EN to Lang.EN, SPEND_FR to Lang.FR, SPEND_PUT_FR to Lang.FR)) re.matchEntire(s)?.let { m ->
            val g = m.groups[1]
            val q = if (g == null) SpendQuery() else FileSpending.parse(g.value, part(orig, g.range), today) ?: return null
            // FX2 (files-1.4-review.md R2): « combien j'ai mis en REER », « … en banque », « … en temps » aren't spending: « mis
            // en » only before a category Robot knows (« en gaz », « en épicerie ») or nothing more than days (« en tout ce
            // mois-ci »). « mis chez X » names a merchant, as before.
            if (re === SPEND_PUT_FR && g!!.value.startsWith("en ") && q.category == null && q.merchant != null) return null
            return FilesCommand.Spend(q, lang)
        }
        for ((re, kinds, lang) in UPCOMING) re.matchEntire(s)?.let { m ->
            val span = if (m.groups.size > 1) m.groups[1]?.value?.let { SPANS[it] } else null
            return FilesCommand.Upcoming(kinds, span ?: Span.SOON, "", lang)
        }
        for ((re, kinds, lang) in NAMED) re.matchEntire(s)?.let { m ->
            val terms = m.groupValues[1].split(' ', '\'').filter { it.isNotBlank() && it !in FILLER }.joinToString(" ")
            if (terms.isEmpty()) return null
            return FilesCommand.Upcoming(kinds, Span.ANY, terms, lang)
        }
        if (SUBS_EN.matches(s)) return FilesCommand.Subscriptions(Lang.EN)
        if (SUBS_FR.matches(s)) return FilesCommand.Subscriptions(Lang.FR)
        return null
    }

    /**
     * What [said] asks of Robot Files, or null when it isn't one of these phrases. [recent]: the panel just filed or moved a
     * file, so "move it to Taxes" means that one; without one, "move it to …" isn't Robot Files' ("move it to the left").
     * [yours]: your drawers' names. A move names a drawer that exists, a starter drawer or one of [yours], or it isn't Robot
     * Files' ("put the receipts in the glovebox"); a rule may name a new one, which your words then make.
     */
    fun route(said: String, recent: Boolean = false, yours: Collection<String> = emptyList(), today: java.time.LocalDate = java.time.LocalDate.now(),
        out: Collection<String> = emptySet()): FilesCommand? {
        FileThis.heard(said)?.let { return FilesCommand.File(it) }
        val (orig, s) = clean(said) ?: return null
        if (s in INBOX) return FilesCommand.Inbox(Lang.EN)
        if (s in INBOX_FR) return FilesCommand.Inbox(Lang.FR)
        money(orig, s, today)?.let { return it }
        pack(s, today)?.let { return it }
        for ((re, g) in RULES_EN) re.matchEntire(s)?.let { m -> rule(m, orig, g, Lang.EN)?.let { return it } }
        fun to(r: IntRange) = part(orig, r).takeIf { drawer(it, yours, out) != null }
        for ((re, g) in RULES_FR) re.matchEntire(s)?.let { m ->
            if (re === PLACE_FR && to(m.groups[g.third]!!.range) == null) return@let
            rule(m, orig, g, Lang.FR)?.let { return it }
        }
        if (recent) {
            // "move it to the left" is everyday speech, not a drawer called Left: with "it", a drawer after an article
            // counts only when the words say it is one ("move it to the Car drawer").
            fun bare(m: MatchResult, articles: List<String>, named: List<String>): Boolean {
                val r = m.groups[1]!!.range
                val before = s.substring(0, r.first)
                return articles.none { before.endsWith(it) } || named.any { before.endsWith(it) || s.endsWith(it.trim()) }
            }
            MOVE_IT_EN.matchEntire(s)?.takeIf { bare(it, listOf(" the ", " a ", " an ", " my "), listOf(" drawer", " folder")) }
                ?.let { m -> to(m.groups[1]!!.range)?.let { d -> return FilesCommand.Move(null, d, Lang.EN) } }
            MOVE_IT_FR.matchEntire(s)?.takeIf { bare(it, listOf(" le ", " la ", " l'", " les ", " mon ", " ma ", " mes "), listOf("tiroir ")) }
                ?.let { m -> to(m.groups[1]!!.range)?.let { d -> return FilesCommand.Move(null, d, Lang.FR) } }
        }
        MOVE_EN.matchEntire(s)?.let { m -> query(m.groupValues[1], one = true)?.let { q -> to(m.groups[2]!!.range)?.let { d -> return FilesCommand.Move(q, d, Lang.EN) } } }
        MOVE_FR.matchEntire(s)?.let { m -> query(m.groupValues[1], one = true)?.let { q -> to(m.groups[2]!!.range)?.let { d -> return FilesCommand.Move(q, d, Lang.FR) } } }
        DELETE_EN.matchEntire(s)?.let { m -> query(stripInFiles(m.groupValues[1]), one = true)?.let { q -> return FilesCommand.Delete(q, Lang.EN) } }
        DELETE_FR.matchEntire(s)?.let { m -> query(stripInFiles(m.groupValues[1]), one = true)?.let { q -> return FilesCommand.Delete(q, Lang.FR) } }
        FIND_EN.matchEntire(s)?.let { m -> query(stripInFiles(m.groupValues[1]), IN_FILES.containsMatchIn(s))?.let { q -> return FilesCommand.Find(q, Lang.EN) } }
        FIND_FR.matchEntire(s)?.let { m -> query(stripInFiles(m.groupValues[1]), IN_FILES.containsMatchIn(s))?.let { q -> return FilesCommand.Find(q, Lang.FR) } }
        return null
    }

    private fun rule(m: MatchResult, orig: String, g: Triple<Int, Int, Int>, lang: Lang): FilesCommand? {
        val kind = KIND_MANY[m.groupValues[g.first]] ?: return null
        val merchantRange = m.groups[g.second]!!.range
        val merchant = part(orig, merchantRange)
        val words = m.groupValues[g.second].split(' ')
        if (words.first() in NOT_MERCHANT || words.any { it in ANY_KIND || it in MONTHS || it in NOT_IN_MERCHANT || it.endsWith("'s") }) return null
        val drawer = part(orig, m.groups[g.third]!!.range)
        if (drawer.isBlank()) return null
        // FX2 (R1): "go in which drawer", « dans quel tiroir »: a question, never a drawer's name (nor a new drawer's).
        if (m.groupValues[g.third].split(' ').first() in ASKING) return null
        return FilesCommand.Rule(kind, merchant, drawer, lang)
    }

    /** FX2 (R1): words that ask which drawer, never one's name (folded). */
    private val ASKING = setOf("quel", "quelle", "quels", "quelles", "quoi", "lequel", "laquelle", "lesquels", "lesquelles", "which", "what", "where") // l10n: not text (words Robot hears)

    /** The search a phrase's [what] makes, or null when it names no kind of file Robot keeps (and [inFiles] doesn't say so). */
    private fun query(what: String, inFiles: Boolean = false, one: Boolean = false): Query? =
        found(what, inFiles, one)?.takeIf { !one || it.terms.isNotBlank() }

    private fun found(what: String, inFiles: Boolean, one: Boolean): Query? {
        var w = " $what "
        var kind: FileKind? = null
        for (k in (if (one) KIND_ONE else ANY_KIND).keys.sortedByDescending { it.length }) {
            val at = w.indexOf(" $k ")
            if (at >= 0) { kind = ANY_KIND[k]; w = w.substring(0, at) + " " + w.substring(at + k.length + 1); break }
        }
        if (kind == null && !inFiles) return null
        // A word narrower than its kind ("lease", « bail ») is searched for too.
        SPECIFIC.firstOrNull { (" $what ").contains(" $it ") }?.let { w += " $it " }
        var month: Int? = null
        var year: Int? = null
        WHEN.find(w)?.let { m -> month = MONTHS[m.groupValues[1]]; year = m.groupValues[2].toIntOrNull(); w = w.removeRange(m.range) + " " }
        if (year == null) YEAR.find(w)?.let { m -> year = m.groupValues[1].toInt(); w = w.removeRange(m.range) + " " }
        val terms = w.split(' ', '\'').filter { it.isNotBlank() && it !in FILLER }.joinToString(" ")
        return Query(kind, terms, month, year)
    }

    private fun stripInFiles(what: String): String = IN_FILES.find(" $what")?.let { (" $what").removeRange(it.range).trim() } ?: what

    /** [said] tidied (whitespace, quotes, "please" and "Robot" around it), and the same text folded for matching (as long). */
    private fun clean(said: String): Pair<String, String>? {
        var o = said.replace('’', '\'').replace(' ', ' ').replace(' ', ' ').replace(Regex("[\\s,]+"), " ").trim().trimEnd('.', '!', '?', ' ').trim() // l10n: not text (a parser's pattern)
        if (o.isEmpty() || o.length > 200) return null
        var f = fold(o)
        var changed = true
        while (changed) {
            changed = false
            for (l in LEAD) if (f.startsWith(l)) { o = o.substring(l.length).trim(); f = fold(o); changed = true }
            for (t in TAIL) if (f.endsWith(t)) { o = o.substring(0, o.length - t.length).trim(); f = fold(o); changed = true }
        }
        return o to f
    }

    /** One character for one: lower case, accents off (so a match's range in the folded text is the same in the words said). */
    fun fold(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
            b.append(d.lowercaseChar())
        }
        return b.toString()
    }

    private fun part(orig: String, r: IntRange): String = orig.substring(r.first, r.last + 1).trim()

    // Drawers by name.

    private val FR = Langs(Lang.FR, Lang.FR, Lang.FR)

    /**
     * The drawer [words] name: a starter drawer by its name in English or French (or its first word: "Trips", « Voyages »),
     * else one of [yours] (case and accents aside); null when none. "the", "my" and « le », « mon » before it are ignored.
     * F12: a drawer travel mode hides ([out]) isn't there.
     */
    fun drawer(words: String, yours: Collection<String>, out: Collection<String> = emptySet()): String? {
        val w = key(words.replace(Regex("^(?:(?:the|my|le|la|mon|ma) |l')", RegexOption.IGNORE_CASE), "")) // l10n: not text (a parser's pattern)
        if (w.isEmpty()) return null
        for (id in Drawers.STARTER) {
            if (id in out) continue
            val p = FilesStrings.drawer(id)
            for (name in listOf(p.en(), p.render(FR))) {
                val k = key(name)
                if (w == k || w == key(name.substringBefore(' '))) return id
            }
            if (w == key(id)) return id
        }
        return yours.firstOrNull { key(it) == w && it !in out }
    }

    /** A new drawer's name from your [words]: as said, its first letter in capitals ("car" → "Car"). */
    fun newDrawer(words: String): String? =
        FileViews.drawerName(words.replace(Regex("^(?:(?:the|my|le|la|mon|ma) |l')", RegexOption.IGNORE_CASE), "").replaceFirstChar { it.titlecase(Locale.ROOT) }) // l10n: not text (a parser's pattern)

    private fun key(s: String): String = fold(s).replace(Regex("[^\\p{L}\\d]+"), " ").trim() // l10n: not text (a parser's pattern)
}

/** The files a [Query] names, by code (BM25 over F7's search text, then the kind, month and year it said). */
object FileFind {
    /** Files [q] names in [idx], best first ([hidden]: files waiting for a delete's Undo, left out). */
    fun find(idx: FileIndex, q: Query, hidden: Set<FileId> = emptySet(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<FileEntry> {
        val pool = idx.entries.filter { e ->
            e.id !in hidden && !e.damaged && (q.kind == null || e.kind == q.kind) &&
                (q.month == null || FileViews.day(e, zone).monthValue == q.month) && (q.year == null || FileViews.day(e, zone).year == q.year)
        }.sortedByDescending { FileViews.day(it, zone) }
        if (q.terms.isBlank()) return pool
        val byId = pool.associateBy { it.id.hex }
        return app.heyrobot.core.memory.Bm25Index.build(pool.map { it.id.hex to FileViews.searchText(it) }).search(q.terms, FileViews.MAX_FOUND).mapNotNull { byId[it.id] }
    }

    /**
     * The one file [q] clearly names, or null: the only match, or the only one whose merchant holds every word searched for.
     * Several, and Robot doesn't pick: a move or a delete by voice never guesses (the Files screen lists them).
     */
    fun one(found: List<FileEntry>, q: Query): FileEntry? {
        if (found.size == 1) return found.single()
        if (found.isEmpty() || q.terms.isBlank()) return null
        val words = q.terms.split(' ').map(FilesRouter::fold)
        val exact = found.filter { e -> e.fields["merchant"]?.value?.let { m -> FilesRouter.fold(m).let { f -> words.all { f.contains(it) } } } == true }
        return exact.singleOrNull()
    }
}
