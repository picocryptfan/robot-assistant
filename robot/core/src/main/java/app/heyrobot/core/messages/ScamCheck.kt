// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ScamStrings
import java.text.Normalizer
import java.util.Locale

/**
 * A sender scams often name (idea 17): [words] name it in a text (lowercase, accents stripped), [acronyms] as written
 * ("TD", "CRA"; a name that is also a surname or a word, "Rogers", "Bell", only first in the text, "Rogers: …"), [domains] are its own, and [tokens] are what a lookalike address borrows ("canadapost").
 * [padOnly]: a name that is also a word or a surname counts in an address only with padding ("rogers-billing", never "rogers-reunion").
 */
class ScamSender(val id: String, val words: List<String>, val acronyms: List<String>, val domains: List<String>, val tokens: List<String>,
    private val msg: Msg? = null, private val plain: String = id, val padOnly: Boolean = false) {
    /** Its name in the reading's language: a catalog line where French differs, else the name as written. */
    fun name(): Phrase = msg?.invoke() ?: Phrase.plain(plain)

    fun owns(host: String): Boolean = domains.any { host == it || host.endsWith(".$it") }

    override fun toString(): String = "ScamSender($id)" // l10n: not text (toString for logs and tests)
}

/** Why a text looks like a scam, first rule first: [ScamCheck] gives the first that hits. */
enum class ScamWhy { CALL_BACK, WRONG_SENDER, SHORT_LINK, LOOKALIKE }

/** A rule's hit and the sender it's about. */
data class ScamHit(val why: ScamWhy, val sender: ScamSender) {
    /** The one line Robot says before the text. It only warns: there is no line for "no hit". */
    fun text(): Phrase = when (why) {
        ScamWhy.CALL_BACK -> ScamStrings.callBack("name" to sender.name())
        ScamWhy.WRONG_SENDER -> ScamStrings.wrongSender("name" to sender.name())
        ScamWhy.SHORT_LINK -> ScamStrings.shortLink()
        ScamWhy.LOOKALIKE -> ScamStrings.lookalike("name" to sender.name())
    }
}

/**
 * Scam texts (idea 17): code rules on the phone, on a text already in RAM. No network: a link is read as text, never
 * opened or fetched. Nothing blocked, deleted or sent, nothing kept. No hit never means safe. Every sender, People and
 * contacts too, gets the rules: a friend's phone can be stolen.
 */
object ScamCheck {
    val senders: List<ScamSender> = listOf(
        ScamSender("canadapost", listOf("canada post", "canadapost", "postes canada", "postes-canada", "postescanada"), emptyList(),
            listOf("canadapost-postescanada.ca", "canadapost.ca", "postescanada.ca"), listOf("canadapost", "postescanada"), ScamStrings.canadaPost),
        ScamSender("cra", listOf("canada revenue agency", "canada revenue", "agence du revenu du canada", "revenu canada"), listOf("CRA", "ARC"),
            listOf("canada.ca", "gc.ca"), listOf("cra", "arc", "craarc", "canadarevenue", "revenucanada"), ScamStrings.cra),
        ScamSender("rcmp", listOf("royal canadian mounted police", "gendarmerie royale du canada"), listOf("RCMP", "GRC"), listOf("canada.ca", "gc.ca", "rcmp.ca", "grc.ca"),
            listOf("rcmp", "grc", "rcmpgrc"), ScamStrings.rcmp),
        ScamSender("servicecanada", listOf("service canada"), emptyList(), listOf("canada.ca", "gc.ca"), listOf("servicecanada"), plain = "Service Canada"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("interac", listOf("interac", "virement interac"), emptyList(), listOf("interac.ca"), listOf("interac", "etransferinterac", "etransfert", "etransfer"), plain = "Interac"),
        ScamSender("rbc", listOf("royal bank", "banque royale"), listOf("RBC"), listOf("rbc.com", "rbcroyalbank.com", "rbcbank.com"),
            listOf("rbc", "royalbank", "rbcroyalbank"), plain = "RBC"),
        ScamSender("td", listOf("td canada trust", "td bank"), listOf("TD"), listOf("td.com", "tdcanadatrust.com", "tdbank.com"),
            listOf("td", "tdcanadatrust", "tdbank"), plain = "TD"),
        ScamSender("scotiabank", listOf("scotiabank", "banque scotia"), emptyList(), listOf("scotiabank.com", "scotiaonline.com"),
            listOf("scotiabank"), ScamStrings.scotiabank),
        ScamSender("bmo", listOf("bank of montreal", "banque de montreal"), listOf("BMO"), listOf("bmo.com", "bmo.ca"),
            listOf("bmo", "bankofmontreal"), plain = "BMO"),
        ScamSender("cibc", listOf("cibc"), emptyList(), listOf("cibc.com", "cibc.ca"), listOf("cibc"), plain = "CIBC"),
        ScamSender("nationalbank", listOf("national bank", "banque nationale"), listOf("BNC"), listOf("nbc.ca", "bnc.ca"),
            listOf("bnc", "nationalbank", "banquenationale"), ScamStrings.nationalBank),
        ScamSender("tangerine", listOf("tangerine"), emptyList(), listOf("tangerine.ca"), listOf("tangerine"), plain = "Tangerine", padOnly = true), // l10n: not text (a company name, said as written in both languages)
        ScamSender("desjardins", listOf("desjardins"), emptyList(), listOf("desjardins.com"), listOf("desjardins"), plain = "Desjardins", padOnly = true),
        ScamSender("rogers", listOf("rogers wireless", "rogers bank"), listOf("Rogers"), listOf("rogers.com", "rogersbank.com"), listOf("rogers"), plain = "Rogers", padOnly = true),
        ScamSender("bell", listOf("bell canada", "bell mobility", "bell mobilite"), listOf("Bell"), listOf("bell.ca", "bellmts.ca"), listOf("bell"), plain = "Bell"),
        ScamSender("telus", listOf("telus"), emptyList(), listOf("telus.com"), listOf("telus"), plain = "Telus"),
        ScamSender("fido", emptyList(), listOf("Fido"), listOf("fido.ca"), listOf("fido"), plain = "Fido"),
        ScamSender("koodo", listOf("koodo"), emptyList(), listOf("koodomobile.com"), listOf("koodo", "koodomobile"), plain = "Koodo"),
        ScamSender("virginplus", listOf("virgin plus", "virgin mobile"), emptyList(), listOf("virginplus.ca"), listOf("virginplus"), plain = "Virgin Plus"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("freedom", listOf("freedom mobile"), emptyList(), listOf("freedommobile.ca"), listOf("freedommobile"), plain = "Freedom Mobile"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("videotron", listOf("videotron"), emptyList(), listOf("videotron.com"), listOf("videotron"), ScamStrings.videotron),
        ScamSender("407etr", listOf("407 etr", "407etr"), emptyList(), listOf("407etr.com"), listOf("407etr"), plain = "407 ETR"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("purolator", listOf("purolator"), emptyList(), listOf("purolator.com"), listOf("purolator"), plain = "Purolator"),
    )

    /** Brands scams borrow (rule 1): only a lookalike of their address warns, never their name in a text ("Netflix: …" is no tag). */
    val brands: List<ScamSender> = listOf(
        ScamSender("netflix", emptyList(), emptyList(), listOf("netflix.com", "netflix.ca"), listOf("netflix"), plain = "Netflix"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("amazon", emptyList(), emptyList(), listOf("amazon.ca", "amazon.com", "amzn.to", "amzn.com", "a.co", "primevideo.com"), listOf("amazon"), plain = "Amazon"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("paypal", emptyList(), emptyList(), listOf("paypal.com", "paypal.me", "paypal.ca"), listOf("paypal"), plain = "PayPal"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("apple", emptyList(), emptyList(), listOf("apple.com", "apple.ca", "icloud.com", "apple.co"), listOf("apple", "icloud"), plain = "Apple", padOnly = true), // l10n: not text (a company name, said as written in both languages)
        ScamSender("google", emptyList(), emptyList(), listOf("google.com", "google.ca", "goo.gl", "g.co", "gmail.com", "youtube.com"), listOf("google", "gmail"), plain = "Google", padOnly = true), // l10n: not text (a company name, said as written in both languages)
        ScamSender("microsoft", emptyList(), emptyList(), listOf("microsoft.com", "live.com", "outlook.com", "office.com", "microsoft365.com", "microsoftonline.com"), listOf("microsoft", "outlook"), plain = "Microsoft", padOnly = true), // l10n: not text (a company name, said as written in both languages)
        ScamSender("walmart", emptyList(), emptyList(), listOf("walmart.ca", "walmart.com"), listOf("walmart"), plain = "Walmart"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("costco", emptyList(), emptyList(), listOf("costco.ca", "costco.com"), listOf("costco"), plain = "Costco"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("spotify", emptyList(), emptyList(), listOf("spotify.com"), listOf("spotify"), plain = "Spotify"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("canadiantire", emptyList(), emptyList(), listOf("canadiantire.ca", "triangle.com"), listOf("canadiantire"), plain = "Canadian Tire"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("meta", emptyList(), emptyList(), listOf("facebook.com", "fb.com", "instagram.com", "whatsapp.com", "wa.me", "meta.com"), listOf("facebook", "instagram", "whatsapp"), plain = "Meta"), // l10n: not text (a company name, said as written in both languages)
        ScamSender("ebay", emptyList(), emptyList(), listOf("ebay.ca", "ebay.com"), listOf("ebay"), plain = "eBay"), // l10n: not text (a company name, said as written in both languages)
    )

    /** Link shorteners: under a sender's tag, a link that hides where it goes. */
    private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "ow.ly", "is.gd", "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "t.ly", "rb.gy", "tiny.cc")

    /** Endings scams use most (spam and abuse reports); a business's own .com or .ca is never on it. */
    val oddEndings = setOf("top", "xyz", "icu", "cyou", "sbs", "cfd", "buzz", "click", "rest", "monster", "bond", "vip", "lol", "quest",
        "cam", "tk", "ml", "ga", "cf", "gq",
        "club", "fun", "win", "bid", "loan", "work", "date", "party", "review", "stream", "gdn", "beauty", "hair", "mom", "autos", "boats")

    /** Endings a link without "https://" may have (any two letters is a country's). */
    private val endings = oddEndings + setOf("com", "net", "org", "info", "biz", "io", "app", "dev", "online", "site", "shop", "store",
        "support", "help", "link", "live", "today", "life", "world", "website", "tech", "cloud", "pro", "page", "services", "delivery", "express")

    /**
     * Endings that are also words, as when a space is missing after a period ("right away.my phone", "hilarious.lol",
     * "ok.so"): with no "https://", one dot, no path and a plain word before it that borrows no sender's name, that's a
     * sentence, not a link. "cra-refund.us" and "td-verify.me" stay links.
     */
    private val glued = setOf("my", "so", "it", "me", "no", "us", "is", "in", "to", "at", "on", "an", "as", "be", "do", "go", "he", "if", "or",
        "im", "ok", "oh", "lol", "la", "le", "de", "en", "et", "un", "ma", "ta", "sa", "se", "ne", "tu", "je", "il", "va")

    /** Words a lookalike address adds around a sender's name ("interac-deposit", "rbcsecure"), longest first. */
    private val padding = listOf("deverrouillage", "verrouillage", "redelivery", "verification", "remboursement", "etransfer", "livraison", "connexion", "paiement", "tracking", "recovery",
        "customer", "delivery", "security", "services", "shipping", "transfer", "account", "restore", "billing", "canada", "confirm", "deposit", "helpdesk",
        "payment", "rewards", "service", "support", "banking", "access", "mobile", "unlock", "online", "parcel", "portal", "refund", "reward", "secure", "signin",
        "update", "verify", "alerts", "client", "compte", "notice", "securite", "alert", "acces", "claim", "login", "track", "depot", "colis", "suivi",
        "auth", "bank", "help", "info", "home", "post", "ship", "fees", "web", "www", "pay", "app", "fee", "my", "id", "ca", "gc", "en", "fr", "e")
    private val paddingSet = padding.toSet()

    /** Labels under a country's ending that belong to the ending ("amazon.co.uk"), not words of the address. */
    private val secondLevel = setOf("co", "com", "net", "org", "gov", "gc", "ac", "edu")

    /** Names that are also words ("arc-en-ciel", "taco-bell", "etransfer.mycu.ca"): like [ScamSender.padOnly], they count only beside a padding word. */
    private val wordTokens = setOf("arc", "bell", "fido", "outlook", "etransfer", "etransfert")

    /** Names one letter from an everyday word ("transfer", "transfert"): no typo of theirs counts. */
    private val noTypo = setOf("etransfer", "etransfert")

    /** Senders that never text a threat with a number to call: the government. */
    private val noCallBack = setOf("cra", "servicecanada", "rcmp")
    private val phone = Regex("(?<!\\d)(?:\\+?1[ .\\u00A0\\u202F-]?)?\\(?\\d{3}\\)?[ .\\u00A0\\u202F-]?\\d{3}[ .\\u00A0\\u202F-]?\\d{4}(?!\\d)")
    // Rule 3's threat: arrest, a warrant or legal action (« mandat d'arrestation », « poursuite judiciaire »); a fine or a hurry isn't one.
    private val threat = Regex("\\b(warrant|arrest|arrested|lawsuit|legal action|legal proceedings|sued|court|prosecution|mandat|arrestation|" +
        "arrete|poursuites?|tribunal|action en justice)\\b")

    private val marks = Regex("\\p{M}+")
    private val link = Regex("(?i)(?<![@\\w.-])(https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,24}))(?![\\w-])")

    /** The first rule that hits in the newest message a reading shows of [t] (the locked count judges the same), or null. Null never means safe. */
    fun of(t: MessageThread): ScamHit? = MessageReading.toRead(t).takeLast(MessageReading.MAX_PER_THREAD).asReversed().firstNotNullOfOrNull { of(it.text) }

    /** The one line before [text] in every reading (the panel, Car mode, the headset, the car screen, a summary), or null. */
    fun line(text: String): Phrase? = of(text)?.text()

    /** The first rule that hits in [text], or null. Null never means safe. */
    fun of(text: String): ScamHit? {
        if (text.isBlank()) return null
        val hosts = hosts(text)
        val plain = fold(text)
        // A sender the text opens with, as a sender would ("CRA: …"), not inside a link ("interac-deposit.com" names no
        // one; it's a lookalike) or a sentence ("Sent you $20 by Interac", "What a TD!": a friend's text).
        val bare = link.replace(text, " ")
        val folded = fold(bare)
        val named = openers.filter { (_, r, onFolded) -> r.containsMatchIn(if (onFolded) folded else bare) }.map { it.first }.distinct()
        // Rule 3: the government doesn't text a threat of arrest or court with a number to call ("CRA: a warrant… Call 1-888-…").
        // The government's tag may sit anywhere in the first line ("Canada Revenue Agency (CRA): FINAL NOTICE", "CRA FINAL NOTICE:").
        (named.firstOrNull { it.id in noCallBack } ?: govTag(bare))?.let { gov ->
            if (phone.containsMatchIn(text) && threat.containsMatchIn(plain)) return ScamHit(ScamWhy.CALL_BACK, gov)
        }
        // Rule 2: a tagged sender's link that imitates any sender or brand ("Canada Post: … canadapost.ca-redelivery.top"),
        // or a short link. Another company's own address stays silent ("TELUS: … purolator.com", "Bell: … crave.ca").
        if (named.isNotEmpty() && hosts.any { lookalike(it) != null }) return ScamHit(ScamWhy.WRONG_SENDER, named.first())
        if (named.isNotEmpty() && hosts.any { it.removePrefix("www.") in shorteners }) return ScamHit(ScamWhy.SHORT_LINK, named.first())
        // Rule 1: an address that borrows a sender's or a brand's name, whatever the text says.
        return hosts.firstNotNullOfOrNull(::lookalike)?.let { ScamHit(ScamWhy.LOOKALIKE, it) }
    }

    /** Each sender's openers, compiled once: its words are matched in the folded text, its acronyms as written. */
    private val openers: List<Triple<ScamSender, Regex, Boolean>> = senders.flatMap { s ->
        s.words.map { w -> Triple(s, opener(w), true) } + s.acronyms.map { a -> Triple(s, opener(a), false) }
    }

    /** The government's names, compiled once: words matched in the folded text, acronyms as written. */
    private val govNames: List<Triple<ScamSender, Regex, Boolean>> = senders.filter { it.id in noCallBack }.flatMap { s ->
        s.words.map { w -> Triple(s, Regex("\\b${Regex.escape(w)}\\b"), true) } + s.acronyms.map { a -> Triple(s, Regex("\\b${Regex.escape(a)}\\b"), false) } // l10n: not text (regexes of names)
    }

    /**
     * A government sender named before the first ":", "|" or "]" of [bare]'s first line, in at most eight words, with at
     * most one word before its name ("URGENT RCMP:"): a tag, not a sentence ("My accountant re CRA:").
     */
    private fun govTag(bare: String): ScamSender? {
        val first = bare.trimStart().lineSequence().first()
        val end = first.indexOfAny(charArrayOf(':', '|', ']')).takeIf { it >= 0 } ?: return null
        val head = first.substring(0, end)
        if (head.split(' ', '\t', '\u00A0', '\u202F').count { it.isNotBlank() } > 8) return null
        val folded = fold(head)
        return govNames.firstOrNull { (_, r, onFolded) -> val h = if (onFolded) folded else head
            r.find(h)?.let { m -> h.substring(0, m.range.first).split(' ', '\t', '\u00A0', '\u202F').count { it.isNotBlank() } <= 1 } == true }?.first
    }

    /**
     * A text that opens with [name] as a sender tags it: the name, at most three of its tag words and ":", "|" or "]"
     * ("CRA: …", "RBC Royal Bank: …", "Postes Canada : …" with a space or a no-break space, "[Canada Post] …", "RBC-Alert: …", "INTERAC e-Transfer: …").
     * Any other word makes it a sentence ("Canada Post strike: …"), and so does "It's", "This is" or « C'est » before it.
     */
    private fun opener(name: String): Regex = Regex("^\\W*${Regex.escape(name)}\\b(?:[ \\t\\u00A0\\u202F-]+(?i:$TAG)\\b){0,3}[ \\t\\u00A0\\u202F]*[:|\\]]")

    /** The words a sender's own tag adds after its name. */
    private const val TAG = "alerts?|alertes?|notices?|avis|notifications?|info|updates?|messages?|security|s[eé]curit[eé]|fraud|fraude|" + // l10n: not text (a regex of tag words)
        "team|[eé]quipe|services?|support|customer|clients?|care|bank|banking|banque|royal|royale|canada|trust|wireless|mobility|" +
        "mobilit[eé]|mobile|mts|e-?transfer|virement|delivery|livraison|tracking|suivi|online|rewards|billing|facturation|payments?|" +
        "paiement|tolls?|express|revenue|agency|agence|du|de|revenu|arc|cra|grc|rcmp|visa|mastercard|dept|department"

    /** The links' hosts in [text], lowercase, "www." kept as written; an e-mail address is not a link. */
    fun hosts(text: String): List<String> = link.findAll(text).mapNotNull { m ->
        val host = m.groupValues[2].lowercase(Locale.ROOT)
        val end = m.groupValues[3].lowercase(Locale.ROOT)
        val next = text.substring(m.range.last + 1)
        val word = end in glued
        val sentence = host.count { it == '.' } == 1 && word && !next.startsWith("/") &&
            host.substringBefore('.').all { it.isLetter() } && lookalike(host) == null
        host.takeIf { m.groupValues[1].isNotEmpty() || (end.length == 2 || end in endings) && !sentence }
    }.distinct().toList()

    /**
     * The sender whose name [host] borrows: its name as a whole word, or run together with padding ("appleid",
     * "rbconline"), joined by a hyphen or a dot to other words ("td-restore.com", "rbc.secure-login.com"); or a long
     * name one letter off or two swapped ("canadapots", "purolatr"; "interact" passes). Run together with padding, the name
     * counts with nothing else in the address ("rbcverify.com", "canadapostredelivery.com"), but a name of under four
     * letters, a word or a surname then needs four letters of padding ("mytd.com", "bellca.com", "applepay.com" pass). The
     * name alone with an ending is silent ("spotify.link", "telus.net", "amazon.co.uk"): a domain list can't know every
     * address a company owns. A name that is a word or a surname needs a padding word beside it ("rogers-billing",
     * never "rogers-park").
     */
    fun lookalike(host: String): ScamSender? {
        val all = senders + brands
        if (all.any { it.owns(host) }) return null
        val labels = host.split('.').dropLast(1).let { l ->
            if (l.size >= 2 && host.substringAfterLast('.').length == 2 && l.last() in secondLevel) l.dropLast(1) else l }
        val parts = labels.flatMap { it.split('-') }.filter { it.isNotEmpty() && it != "www" }
        // Each run of one to three neighbouring parts ("canada-post"), the parts outside it, and the run with padding off.
        val runs = parts.indices.flatMap { i -> (1..minOf(3, parts.size - i)).map { n ->
            val run = parts.subList(i, i + n).joinToString("")
            Triple(run, parts.filterIndexed { j, _ -> j < i || j >= i + n }, if (n == 1) unpad(run) else setOf(run)) } }
        return all.firstOrNull { s -> s.tokens.any { tok -> runs.any { (run, rest, bare) ->
            val word = s.padOnly || tok in wordTokens
            val padded = tok in bare - run
            rest.isNotEmpty() && (tok == run && (!word || rest.any { it.length >= 4 && it in paddingSet }) || padded) ||
                padded && (run.length - tok.length >= 4 || !word && tok.length >= 4) ||
                tok.length >= 7 && tok !in noTypo && bare.any { c -> c.length <= tok.length && osa(c, tok) == 1 }
        } } }
    }

    /** [part] and what's left of it with padding words taken off either end, a few times over ("e" off the front only: "belle" is no Bell). */
    private fun unpad(part: String): Set<String> {
        val out = linkedSetOf(part)
        var edge = listOf(part)
        repeat(3) {
            edge = edge.flatMap { p -> padding.flatMap { w -> listOfNotNull(
                p.removePrefix(w).takeIf { it != p && it.isNotEmpty() }, p.removeSuffix(w).takeIf { it != p && it.isNotEmpty() && w != "e" }) } }
                .filter { out.add(it) }
        }
        return out
    }

    /** Edit distance with a swap of two neighbours as one edit (optimal string alignment). */
    internal fun osa(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Lowercase, accents off, curly apostrophes straight: "Dernier avis" and "dernier avis" match alike. */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").replace('’', '\'').lowercase(Locale.ROOT)
}
