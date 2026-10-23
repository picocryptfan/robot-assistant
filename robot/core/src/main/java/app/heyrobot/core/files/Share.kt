// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.Lang
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Pure. What a share to Robot Files may name (files-1.4-plan.md §3.1, D3): Robot copies a file only from another app's
// content provider, once, while its window is open. Everything a share says is the sender's claim: its files, their names,
// sizes and types, and who sent it. The app reads Android's parse of each URI (scheme and authority) and asks here.

/** One URI a share names, as Android parsed it. */
data class SharedRef(val scheme: String?, val authority: String?)

/** Why Robot doesn't take a URI a share names. */
enum class ShareRefusal {
    /** Not content: (file: would have Robot open a path itself, with its own rights). */
    NOT_CONTENT,
    /** No provider named. */
    NO_PROVIDER,
    /** Another user's provider ("content://10@…"). */
    OTHER_USER,
    /** Robot's own provider (its exports, the panel's camera, scans): a share can't make Robot copy its own storage. */
    OWN,
}

object ShareRules {
    private val MIME = Regex("[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9*][a-z0-9.+*-]{0,63}")

    /** Null when Robot may copy from [ref]; [own]: the authority is one of Robot's own providers. */
    fun check(ref: SharedRef, own: (String) -> Boolean): ShareRefusal? {
        val authority = ref.authority
        return when {
            ref.scheme == null || !ref.scheme.equals("content", ignoreCase = true) -> ShareRefusal.NOT_CONTENT
            authority.isNullOrEmpty() -> ShareRefusal.NO_PROVIDER
            '@' in authority -> ShareRefusal.OTHER_USER
            own(authority) -> ShareRefusal.OWN
            else -> null
        }
    }

    /** The first [IntakeLimits.MAX_ITEMS] of [items] (each once, in order), and how many were left out. */
    fun <T> cap(items: List<T>): Pair<List<T>, Int> {
        val once = items.distinct()
        return once.take(IntakeLimits.MAX_ITEMS) to (once.size - IntakeLimits.MAX_ITEMS).coerceAtLeast(0)
    }

    /** A type the sender claims, shown as it is only when it looks like one ("application/pdf"); else null. */
    fun claimedType(s: String?): String? = s?.trim()?.lowercase()?.takeIf { MIME.matches(it) }

    /** [s] when it is a package name a provenance may keep ("org.example.mail"), else null. */
    fun packageName(s: String?): String? = s?.takeIf { it.length <= FileIndexCodec.MAX_APP && FileIndexCodec.PACKAGE.matches(it) }

    /** A size the sender claims: a whole number of bytes, or null. */
    fun claimedSize(n: Long?): Long? = n?.takeIf { it >= 0 }

    /**
     * Shared text with no file (a link from the browser, §3.1): its bytes as a UTF-8 text file, at most
     * [IntakeLimits.MAX_TEXT], cut on a character's boundary; null when there is nothing but spaces.
     */
    fun textBytes(text: CharSequence?): ByteArray? {
        val s = text?.toString() ?: return null
        if (s.isBlank()) return null
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= IntakeLimits.MAX_TEXT) return bytes
        var end = IntakeLimits.MAX_TEXT
        // Back to the start of a character: UTF-8's continuation bytes are 10xxxxxx.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }
}

/** The names Robot gives what it adds itself (§4.4: F6 renames from what it reads, unless you named it). */
object IntakeNames {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")  // l10n: not text (a date pattern)

    /** "2026-10-22 14.05": when, in [zone], the same in both languages. */
    fun stamp(at: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(at).atZone(zone))

    /** A shared text's name: its subject when the sender gave one (cleaned), else [fallback]; ".txt" either way. */
    fun text(subject: String?, fallback: String): String {
        val s = subject?.let(FileIndexCodec::cleanName)?.take(FileIndexCodec.MAX_NAME - 4)?.trim().orEmpty()
        return (s.ifEmpty { fallback }) + ".txt"  // l10n: not text (a file name's extension)
    }
}

/** The words that ask Robot to file what the panel holds (§3.3, §9): yours alone, typed or said, in either language. */
object FileThis {
    /** FILE: file what the panel holds; ANYWAY: add a duplicate after all; MOVE: your words after Move (F6), in [Heard.words]. */
    enum class Ask { FILE, ANYWAY, MOVE }

    data class Heard(val ask: Ask, val lang: Lang, val words: String? = null) {
        override fun toString(): String = "Heard($ask, $lang)" // l10n: not text (never your words in a log)
    }

    // Words Robot hears, never shows: one line each.
    private val FILE_EN = setOf(
        "file this", "file that", "file it", "file this receipt", "file this photo", "file this picture", "file this screenshot",  // l10n: not text (words Robot hears)
        "file this document", "save this to my files", "save that to my files", "save this to robot files", "keep this receipt",  // l10n: not text (words Robot hears)
        "add this to my files", "add this to robot files",  // l10n: not text (words Robot hears)
    )
    private val FILE_FR = setOf(
        "classe ça", "classe ceci", "classe ce reçu", "classe cette photo", "classe cette capture d'écran", "classe ce document",  // l10n: not text (words Robot hears)
        "garde ça dans mes fichiers", "garde ce reçu", "range ce reçu", "range ça", "enregistre ça dans mes fichiers",  // l10n: not text (words Robot hears)
        "ajoute ça à mes fichiers", "ajoute ça dans mes fichiers",  // l10n: not text (words Robot hears)
        // F13: Québec's everyday ways (« sauver » is to save; « classe-moi ça »).
        "sauve ça dans mes fichiers", "sauvegarde ça dans mes fichiers", "mets ça dans mes fichiers", "classe ça dans mes fichiers",  // l10n: not text (words Robot hears)
        "classe-moi ça", "classe cette facture", "garde cette facture", "range cette facture", "classe ce papier",  // l10n: not text (words Robot hears)
    )
    private val ANYWAY_EN = setOf("file it anyway", "file this anyway", "add it anyway", "keep it anyway", "save it anyway")  // l10n: not text (words Robot hears)
    private val ANYWAY_FR = setOf(
        "classe-le quand même", "classe le quand même", "ajoute-le quand même", "ajoute le quand même", "garde-le quand même",  // l10n: not text (words Robot hears)
        "garde le quand même", "classe-la quand même", "classe la quand même", "ajoute-la quand même", "ajoute la quand même",  // l10n: not text (words Robot hears)
    )
    private val LEAD = listOf("hey robot ", "robot ", "please ", "s'il te plaît ", "stp ")  // l10n: not text (words Robot hears)
    private val TAIL = listOf(" please", " s'il te plaît", " stp", " robot")  // l10n: not text (words Robot hears)

    /** [s] as [heard] compares it: FilesRouter's fold (case and accents off), hyphens as spaces. */
    private fun folded(s: String): String = FilesRouter.fold(s).replace('-', ' ')
    private val FILE_EN_F = FILE_EN.map(::folded).toSet()
    private val FILE_FR_F = FILE_FR.map(::folded).toSet()
    private val ANYWAY_EN_F = ANYWAY_EN.map(::folded).toSet()
    private val ANYWAY_FR_F = ANYWAY_FR.map(::folded).toSet()
    private val LEAD_F = LEAD.map(::folded)
    private val TAIL_F = TAIL.map(::folded)

    /** What [said] asks, or null when it isn't one of these phrases (whole, with "please" or "Robot" around it at most). */
    fun heard(said: String): Heard? {
        var s = said.lowercase().replace('’', '\'').replace(' ', ' ').replace(' ', ' ')
            .replace(Regex("[\\s,]+"), " ").trim().trimEnd('.', '!', '?', ' ').trim()
        // FX2 (files-1.4-review.md R5): accents and case off and hyphens as spaces, so « classe moi ca » is « classe-moi ça ».
        s = folded(s).replace(Regex(" +"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (l in LEAD_F) if (s.startsWith(l)) { s = s.removePrefix(l).trim(); changed = true }
            for (t in TAIL_F) if (s.endsWith(t)) { s = s.removeSuffix(t).trim(); changed = true }
        }
        return when (s) {
            in FILE_EN_F -> Heard(Ask.FILE, Lang.EN)
            in FILE_FR_F -> Heard(Ask.FILE, Lang.FR)
            in ANYWAY_EN_F -> Heard(Ask.ANYWAY, Lang.EN)
            in ANYWAY_FR_F -> Heard(Ask.ANYWAY, Lang.FR)
            else -> null
        }
    }
}
