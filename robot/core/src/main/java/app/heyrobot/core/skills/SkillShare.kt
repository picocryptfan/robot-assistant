// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.LogCode
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

// Pure (org.json). Skills 1.3 plan S3.4, design §11.2: what Settings › Skills › Sharing and the import screen work out.
// The keys you trust (skill_keys.json: fingerprints and the names you gave them; never a private key), who signed a file
// as the import screen says it, and a file's skills made ready to save: a name that clashes with one of yours (or one
// of Robot's phrases) gets "(imported)" and loses its other names, and each "c-refNNN" a step names is bound to the
// connection you picked of the kind the file says. A ref you didn't pick stays as it is: its step needs attention, so the
// skill can't run until you edit it. Whatever the signer, an import is saved off (SkillExport.read), from content.

/** A key you trust: its fingerprint (64 lowercase hex digits, SkillSig.fingerprint) and the name you gave it. */
data class TrustedKey(val fingerprint: String, val name: String, val added: Long)

/** Who signed a file, as the import screen says it. A trusted key changes only these words, never how a skill starts. */
sealed class SignedBy {
    data object Nobody : SignedBy()
    data object ThisPhone : SignedBy()
    data class Trusted(val name: String) : SignedBy()
    data class Unknown(val fingerprint: String) : SignedBy()
}

/** A skill of the file, ready but for its connections: [renamed] when its name clashed; [needs]: the refs its steps name. */
data class ImportItem(val skill: Skill, val renamed: Boolean, val needs: List<ExportNeed>)

/** SKILL_SHARE's a (voice log 259): b = 0 unsigned, 1 signed, 2 signed by a key you trust; c = 0 done, else the why's code. */
enum class SkillShareCode(override val id: Int, override val title: String) : LogCode {
    EXPORTED(1, "exported"), EXPORT_REFUSED(2, "export refused"), IMPORTED(3, "imported"), IMPORT_REFUSED(4, "import refused"),
}

object SkillShare {
    const val MAX_TRUSTED = 20
    const val MAX_KEY_NAME = 40
    const val FILE = "skill_keys.json" // l10n: not text (a file name)
    const val VERSION = 1
    private const val V = VERSION
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val SEPARATORS = Regex("[\\s:\\-]")
    private val CONTROL = Regex("\\p{Cntrl}")

    /** A fingerprint as you typed or pasted it (spaces, colons and dashes ignored, any case), or null when it isn't one. */
    fun fingerprintOf(input: String): String? = input.replace(SEPARATORS, "").lowercase(java.util.Locale.ROOT).takeIf { HEX64.matches(it) }

    /** All 64 digits, in groups of four, to read out or type on another phone ("5A7A 78CC …"). */
    fun grouped(fp: String): String = fp.uppercase(java.util.Locale.ROOT).chunked(4).joinToString(" ")

    fun validKeyName(n: String): Boolean = n.isNotBlank() && n == n.trim() && n.length <= MAX_KEY_NAME && !CONTROL.containsMatchIn(n)

    /** skill_keys.json's keys; a bad entry is left out, a bad file is none (it never makes a key trusted). */
    fun readTrusted(text: String?): List<TrustedKey> {
        val o = try { JSONObject(text ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        if (o.optInt("v") != V) return emptyList()
        val a = o.optJSONArray("keys") ?: return emptyList()
        val out = LinkedHashMap<String, TrustedKey>()
        for (i in 0 until minOf(a.length(), MAX_TRUSTED)) {
            val k = a.optJSONObject(i) ?: continue
            val fp = (k.opt("fingerprint") as? String)?.takeIf { HEX64.matches(it) } ?: continue
            val name = (k.opt("name") as? String)?.takeIf(::validKeyName) ?: continue
            out.putIfAbsent(fp, TrustedKey(fp, name, (k.opt("added") as? Number)?.toLong() ?: 0L))
        }
        return out.values.toList()
    }

    fun writeTrusted(keys: List<TrustedKey>): String = JSONObject().put("v", V).put("keys", JSONArray().apply {
        for (k in keys) put(JSONObject().put("fingerprint", k.fingerprint).put("name", k.name).put("added", k.added))
    }).toString(2)

    /** Why [input] can't be added as [name], or null when it may (then [add] adds it). */
    fun addProblem(keys: List<TrustedKey>, input: String, name: String, own: String?): Phrase? {
        val fp = fingerprintOf(input) ?: return SkillStrings.keyNotFingerprint()
        return when {
            fp == own -> SkillStrings.keyIsYours()
            keys.any { it.fingerprint == fp } -> SkillStrings.keyAlready("name" to keys.first { it.fingerprint == fp }.name)
            !validKeyName(name.trim()) -> SkillStrings.keyName()
            keys.size >= MAX_TRUSTED -> SkillStrings.keyFull("n" to MAX_TRUSTED)
            else -> null
        }
    }

    fun add(keys: List<TrustedKey>, input: String, name: String, own: String?, now: Long): List<TrustedKey> =
        if (addProblem(keys, input, name, own) != null) keys else keys + TrustedKey(fingerprintOf(input)!!, name.trim(), now)

    fun signedBy(signer: String?, own: String?, trusted: List<TrustedKey>): SignedBy = when {
        signer == null -> SignedBy.Nobody
        signer == own -> SignedBy.ThisPhone
        else -> trusted.firstOrNull { it.fingerprint == signer }?.let { SignedBy.Trusted(it.name) } ?: SignedBy.Unknown(signer)
    }

    /** The import screen's provenance line (§11.2 item 3). */
    fun provenance(b: SignedBy): Phrase = when (b) {
        SignedBy.Nobody -> SkillStrings.signedNobody()
        SignedBy.ThisPhone -> SkillStrings.signedThisPhone()
        is SignedBy.Trusted -> SkillStrings.signedTrusted("name" to b.name)
        is SignedBy.Unknown -> SkillStrings.signedUnknown("key" to SkillSig.shown(b.fingerprint))
    }

    /** SKILL_SHARE's b. */
    fun logSigned(b: SignedBy): Int = when (b) { SignedBy.Nobody -> 0; is SignedBy.Trusted, SignedBy.ThisPhone -> 2; is SignedBy.Unknown -> 1 }

    /**
     * [read]'s skills ready to import beside your own ([ctx]: your skills and Robot's phrases). A name that SkillNames
     * refuses becomes "<name> (imported)" (then "(imported 2)" and on, the name cut to fit), its other names left empty;
     * a skill no such name fits is left out.
     */
    fun prepare(read: ExportRead.Ok, ctx: NameContext, now: ZonedDateTime, langs: Langs): List<ImportItem> {
        var names = ctx
        val out = mutableListOf<ImportItem>()
        for (s in read.skills) {
            val fits = SkillNames.check(s.name, null, names, now) == null &&
                (s.namesEn + s.namesFr + s.namesMore.values.flatten()).all { SkillNames.check(it, null, names, now, other = true) == null }
            val k = if (fits) s else (1..9).asSequence().map { n -> s.copy(name = importedName(s.name, n, langs), namesEn = emptyList(), namesFr = emptyList(), namesMore = emptyMap()) }
                .firstOrNull { SkillCheck.validName(it.name) && SkillNames.check(it.name, null, names, now) == null } ?: continue
            val refs = k.steps.mapNotNull { it.conn }.filter(SkillExport::isRef).toSet()
            out += ImportItem(k, !fits, read.needs.filter { it.ref in refs })
            names = names.copy(skills = names.skills + k)
        }
        return out
    }

    private fun importedName(name: String, n: Int, langs: Langs): String {
        fun with(base: String) = (if (n == 1) SkillStrings.importedName("name" to base) else SkillStrings.importedNameN("name" to base, "n" to n)).render(langs)
        val extra = with("").length
        return with(name.take(maxOf(1, SkillCheck.MAX_NAME - extra)).trimEnd())
    }

    /** Your connections that fit [need]: its kind and, but for a hand-off, every operation its steps call. */
    fun candidates(need: ExportNeed, connections: List<Connection>): List<Connection> = connections.filter { c ->
        c.kind == need.kind && (need.kind == ConnKind.HAND_OFF || need.ops.all { op -> c.ops.any { it.id == op } })
    }

    /** [s] with each ref in [picks] bound to the connection picked for it. */
    fun bind(s: Skill, picks: Map<String, String>): Skill =
        s.copy(steps = s.steps.map { st -> st.conn?.let(picks::get)?.let { st.copy(conn = it) } ?: st })

    /** The refs of [item] with no connection in [picks]: the skill is saved, off, and needs attention until you edit it. */
    fun missing(item: ImportItem, picks: Map<String, String>): List<ExportNeed> = item.needs.filter { picks[it.ref] == null }
}
