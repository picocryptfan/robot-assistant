// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.web.QueryCheck
import app.heyrobot.core.web.WebWords
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

// Pure (org.json, java.security). A `.robotskill` file (skills design §11.1 and §11.2, skills 1.3 plan S3.3): UTF-8 JSON,
// at most 512 KiB, `{"format":"robot.skill.export","v":1,"made_with","exported","skills","needs","sig"?}`, written in
// RFC 8785's canonical form (Jcs). A shared skill keeps only [SkillExport.SHARED] of skills.json's keys: its id, rev,
// level and provenance stay, and so does everything that never was in skills.json (keys, tokens, topics, addresses, Run
// with, last runs and counts, memory, History). A step that reaches one of your connections names a ref ("c-ref001")
// instead of its id, and `needs` says what kind of connection each ref needs and which operations its steps call.
// Signed (ES256, SkillSig), the signature covers the canonical form of the file without "sig", and a signed file must be
// exactly the canonical form of what it holds, so any changed byte is refused (one exception, tested: a "v" changed to a
// higher digit reads as a newer Robot's file). Reading takes everything in the file as untrusted: each skill goes through
// SkillCodec.fromJson (SkillCheck, §3.8) and is refused whole on any problem; a key-shaped value refuses the file. What it
// gives back is ready to save: off, from content, `made: import` with the file's SHA-256 and its signer's fingerprint.

/** A connection a shared skill needs: [ref] is what its steps name, [kind] the kind it needs, [ops] the operations its steps call (none for a hand-off). */
data class ExportNeed(val ref: String, val kind: ConnKind, val ops: List<String>)

/** What the export preview flags in a fixed value (§11.1), with its words ("a person's name"). */
enum class ExportFlagKind(val msg: Msg) {
    PERSON(SkillStrings.flagPerson), MESSAGE(SkillStrings.flagMessage), PRIVATE(SkillStrings.flagPrivate),
    EMAIL(SkillStrings.flagEmail), ADDRESS(SkillStrings.flagAddress), NUMBER(SkillStrings.flagNumber),
}

/** One fixed [value] of [skill] (its name) that looks personal. */
data class ExportFlag(val skill: String, val value: String, val kind: ExportFlagKind)

/** Why a skill can't be shared; [msg] takes the skill's name. */
enum class ExportProblem(val msg: Msg) {
    KEY(SkillStrings.shareKey), CONNECTION(SkillStrings.shareConnection), TOO_LARGE(SkillStrings.shareTooLarge),
}

sealed class ExportWrite {
    /** [bytes]: the file; [signer]: its key's fingerprint, or null when unsigned. */
    class Ok(val bytes: ByteArray, val signer: String?) : ExportWrite()
    data class Refused(val why: ExportProblem, val skill: String) : ExportWrite()
}

/** Why a file is refused, in plain words. */
enum class ExportRefusal(val msg: Msg) {
    TOO_LARGE(SkillStrings.importTooLarge), NOT_SKILLS(SkillStrings.importNotSkills), TOO_NEW(SkillStrings.importTooNew),
    KEY(SkillStrings.importKey), CHANGED(SkillStrings.importChanged), NONE(SkillStrings.importNone),
}

sealed class ExportRead {
    /**
     * [skills]: each ready to save (a new id, off, from content, `made: import`); [refused]: skills refused whole;
     * [needs]: the connections the kept ones need; [signer]: the fingerprint, null when unsigned; [fileSha256]: the
     * file's SHA-256; [madeWith] and [exported] as the file says (untrusted, short).
     */
    class Ok(val skills: List<Skill>, val refused: Int, val needs: List<ExportNeed>, val signer: String?, val fileSha256: String,
        val madeWith: String, val exported: String) : ExportRead()
    data class Refused(val why: ExportRefusal) : ExportRead()
}

object SkillExport {
    const val FORMAT = "robot.skill.export" // l10n: not text (the file's format)
    const val VERSION = 1
    const val MAX_BYTES = 512 * 1024
    const val EXTENSION = "robotskill" // l10n: not text (the file's extension)
    const val MIME = "application/json" // l10n: not text (a MIME type)
    /** The keys of skills.json's skill a shared one keeps. Everything else never leaves the phone. */
    val SHARED = setOf("name", "names", "params", "steps", "limits")
    private val TOP = setOf("format", "v", "made_with", "exported", "skills", "needs")
    private val SIG = setOf("alg", "key", "value")
    private val NEED = setOf("ref", "kind", "ops")
    private const val MAX_LABEL = 64
    private const val MAX_REFS = 999
    private val SIGNED = "\"sig\":{".toByteArray() // l10n: not text (JSON)
    /** A token of key characters, and inside it a run with no separators: an API key, a token, a JWT's part. */
    private val KEY_TOKEN = Regex("[A-Za-z0-9_\\-+/=]{20,}")
    private val KEY_RUN = Regex("[A-Za-z0-9+/=]{16,}")

    /** The ref the [i]th connection gets in a file. */
    fun ref(i: Int): String = "c-ref" + (i + 1).toString().padStart(3, '0') // l10n: not text (an id)

    /**
     * [skills] as one file, signed by [signer] when given. [kindOf]: the kind of your connection with that id, null when it's
     * gone. Refused when a skill holds a key-shaped value (the reader would refuse the file) or names a missing connection,
     * or the file would be over 512 KiB. [signer] may throw (the Keystore); its signature is checked before it's written.
     */
    fun write(skills: List<Skill>, madeWith: String, exportedMs: Long, kindOf: (String) -> ConnKind?, signer: SkillSigner?): ExportWrite {
        require(skills.isNotEmpty() && skills.size <= SkillCheck.MAX_SKILLS)
        val refs = LinkedHashMap<String, String>()
        val needs = LinkedHashMap<String, Pair<ConnKind, MutableSet<String>>>()
        val out = JSONArray()
        for (s in skills) {
            val steps = s.steps.map { st ->
                val c = st.conn ?: return@map st
                val kind = kindOf(c)
                if (kind == null || (st.kind == StepKind.APP) != (kind == ConnKind.HAND_OFF) || (c !in refs && refs.size >= MAX_REFS)) {
                    return ExportWrite.Refused(ExportProblem.CONNECTION, s.name)
                }
                val r = refs.getOrPut(c) { ref(refs.size) }
                needs.getOrPut(r) { kind to sortedSetOf<String>() }.second.apply { st.op?.let(::add) }
                st.copy(conn = r)
            }
            val shared = shared(s.copy(steps = steps))
            if (texts(shared).any(::keyShaped)) return ExportWrite.Refused(ExportProblem.KEY, s.name)
            out.put(shared)
        }
        val file = JSONObject().put("format", FORMAT).put("v", VERSION).put("made_with", madeWith.take(MAX_LABEL))
            .put("exported", Instant.ofEpochSecond(Math.floorDiv(exportedMs, 1000L)).toString())
            .put("skills", out)
            .put("needs", JSONArray().apply { for ((r, n) in needs) put(JSONObject().put("ref", r).put("kind", n.first.id).put("ops", JSONArray(n.second.toList()))) })
        if (signer != null) {
            val key = signer.publicKey
            val signed = Jcs.bytes(file)
            val sig = SkillSig.raw(signer.sign(signed))
            check(sig != null && SkillSig.verify(key, signed, sig)) { "the signer's signature doesn't check" } // l10n: not text (an exception)
            file.put("sig", JSONObject().put("alg", SkillSig.ALG).put("key", b64(key)).put("value", b64(sig)))
        }
        val bytes = Jcs.bytes(file)
        if (bytes.size > MAX_BYTES) return ExportWrite.Refused(ExportProblem.TOO_LARGE, skills.first().name)
        return ExportWrite.Ok(bytes, signer?.let { SkillSig.fingerprint(it.publicKey) })
    }

    /** What the preview asks about before sharing (§11.1): people in person values, fixed messages, and what QueryCheck and SecretDetector find. */
    fun preview(skills: List<Skill>): List<ExportFlag> {
        val out = LinkedHashMap<Pair<String, String>, ExportFlag>()
        fun add(s: Skill, v: String, k: ExportFlagKind) {
            if (v.isNotBlank()) out.putIfAbsent(s.name to v, ExportFlag(s.name, v, k))
        }
        for (s in skills) {
            for (p in s.params) if (p.type == ParamType.PERSON) add(s, p.default, ExportFlagKind.PERSON)
            for (st in s.steps) {
                val tellsPeople = ParamType.PERSON in st.kind.args.values
                for ((k, v) in st.args) {
                    if (v !is String || SkillTemplate.ref(v) != null) continue
                    when (st.kind.args[k]) {
                        ParamType.PERSON -> add(s, v, ExportFlagKind.PERSON)
                        ParamType.TEXT -> if (tellsPeople) add(s, v, ExportFlagKind.MESSAGE)
                        else -> {}
                    }
                }
            }
            for (t in texts(shared(s))) personal(t)?.let { add(s, t, it) }
        }
        return out.values.toList()
    }

    private fun personal(t: String): ExportFlagKind? {
        val f = WebWords.fold(t)
        return when {
            SecretDetector.isSecret(t) -> ExportFlagKind.PRIVATE
            '@' in t || QueryCheck.EMAIL_WORDS.containsMatchIn(f) -> ExportFlagKind.EMAIL
            QueryCheck.STREET.containsMatchIn(f) -> ExportFlagKind.ADDRESS
            QueryCheck.longestDigitRun(f) >= QueryCheck.MAX_DIGITS -> ExportFlagKind.NUMBER
            else -> null
        }
    }

    /**
     * A file read (§11.2). [newId]: a fresh skill id for each skill kept; [now]: its created and edited time. Refused: over
     * 512 KiB, not this format, a newer "v", a signature that doesn't check (or a signed file changed in any byte), a
     * key-shaped value anywhere in a skill, or no skill left.
     */
    fun read(bytes: ByteArray, newId: () -> String, now: Long): ExportRead {
        if (bytes.size > MAX_BYTES) return refused(ExportRefusal.TOO_LARGE)
        val o = utf8(bytes)?.let { t -> try { JSONObject(t) } catch (_: Exception) { null } }
            ?: return refused(if (contains(bytes, SIGNED)) ExportRefusal.CHANGED else ExportRefusal.NOT_SKILLS)
        val v = (o.opt("v") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
        var signer: String? = null
        if (o.has("sig")) {
            signer = signer(o, bytes) ?: return refused(if (v != null && v > VERSION) ExportRefusal.TOO_NEW else ExportRefusal.CHANGED)
        }
        if (o.opt("format") != FORMAT || v == null || v < 1) return refused(ExportRefusal.NOT_SKILLS)
        if (v > VERSION) return refused(ExportRefusal.TOO_NEW)
        if (o.keys().asSequence().toSet() - "sig" != TOP) return refused(ExportRefusal.NOT_SKILLS)
        val madeWith = label(o.opt("made_with")) ?: return refused(ExportRefusal.NOT_SKILLS)
        val exported = label(o.opt("exported")) ?: return refused(ExportRefusal.NOT_SKILLS)
        val (needs, renamed) = needs(o.opt("needs"))?.let(::shaped) ?: return refused(ExportRefusal.NOT_SKILLS)
        val a = o.opt("skills") as? JSONArray ?: return refused(ExportRefusal.NOT_SKILLS)
        if (a.length() > SkillCheck.MAX_SKILLS) return refused(ExportRefusal.NOT_SKILLS)
        for (i in 0 until a.length()) if (texts(a.opt(i)).any(::keyShaped)) return refused(ExportRefusal.KEY)

        val sha = SkillSig.hex(MessageDigest.getInstance("SHA-256").digest(bytes)) // l10n: not text (an algorithm)
        val kept = mutableListOf<Skill>()
        val keys = HashSet<String>()
        var refusedCount = 0
        for (i in 0 until a.length()) {
            val s = skill(a.opt(i), newId(), now, SkillImport(sha, signer))?.let { k -> k.copy(steps = k.steps.map { st -> st.conn?.let(renamed::get)?.let { st.copy(conn = it) } ?: st }) }
            if (s == null || s.keys.any { it in keys } || !linked(s, needs)) {
                refusedCount++
                continue
            }
            keys += s.keys
            kept += s
        }
        if (kept.isEmpty()) return refused(ExportRefusal.NONE)
        val used = kept.flatMap { s -> s.steps.mapNotNull { it.conn } }.toSet()
        return ExportRead.Ok(kept, refusedCount, needs.values.filter { it.ref in used }, signer, sha, madeWith, exported)
    }

    private fun refused(why: ExportRefusal) = ExportRead.Refused(why)

    /** The signer's fingerprint when "sig" checks against the file's canonical form without it, and the file is exactly its own canonical form; else null. */
    private fun signer(o: JSONObject, bytes: ByteArray): String? = try {
        val sig = o.get("sig") as JSONObject
        require(sig.keys().asSequence().toSet() == SIG && sig.get("alg") == SkillSig.ALG)
        val key = unb64(sig.get("key") as String)!!
        val value = unb64(sig.get("value") as String)!!
        require(Jcs.bytes(o).contentEquals(bytes))
        val unsigned = JSONObject(o, o.keys().asSequence().filter { it != "sig" }.toList().toTypedArray())
        if (SkillSig.verify(key, Jcs.bytes(unsigned), value)) SkillSig.fingerprint(key) else null
    } catch (_: Exception) { null }

    /** One shared skill as this Robot saves it, or null when anything in it is refused (SkillCodec.fromJson, SkillCheck). */
    private fun skill(x: Any?, id: String, now: Long, from: SkillImport): Skill? {
        val o = x as? JSONObject ?: return null
        val k = o.keys().asSequence().toSet()
        if (!k.containsAll(SHARED - "limits") || !SHARED.containsAll(k)) return null
        val full = JSONObject(o, k.toTypedArray()).put("id", id).put("rev", 1).put("level", SkillLevel.OFF.id)
            .put("provenance", JSONObject().put("made", SkillMade.IMPORT.id).put("from_content", true).put("created", now).put("edited", now)
                .put("import", JSONObject().put("file_sha256", from.fileSha256).put("signer", from.signer ?: JSONObject.NULL)))
        return SkillCodec.fromJson(full, SkillCodec.VERSION)
    }

    /** Every step that reaches a connection names one of [needs]: an operation it lists, a hand-off by a hand-off. */
    private fun linked(s: Skill, needs: Map<String, ExportNeed>): Boolean = s.steps.all { st ->
        val c = st.conn ?: return@all true
        val n = needs[c] ?: return@all false
        if (st.kind == StepKind.APP) n.kind == ConnKind.HAND_OFF else n.kind != ConnKind.HAND_OFF && st.op in n.ops
    }

    /** [x] is of [ref]'s shape, "c-ref" and three digits: what a shared file's steps name instead of a connection's id. */
    fun isRef(x: String): Boolean = REF_SHAPE.matches(x)
    private val REF_SHAPE = Regex("^c-ref\\d{3}$") // l10n: not text (an id's shape)

    /**
     * fix-skills13-security 3: a ref not of [ref]'s shape (one of your connections' ids, say) is refused as an id and renamed
     * to a free ref, so its steps bind only to a connection you pick (none: the skill needs attention). The needs, and the renames.
     */
    private fun shaped(needs: Map<String, ExportNeed>): Pair<Map<String, ExportNeed>, Map<String, String>> {
        val free = (0 until MAX_REFS).asSequence().map(::ref).filter { it !in needs }.iterator()
        val renamed = needs.keys.filterNot(::isRef).associateWith { free.next() }
        val out = LinkedHashMap<String, ExportNeed>()
        for ((r, n) in needs) (renamed[r] ?: r).let { out[it] = n.copy(ref = it) }
        return out to renamed
    }

    private fun needs(x: Any?): Map<String, ExportNeed>? = try {
        val a = x as JSONArray
        require(a.length() <= MAX_REFS)
        val out = LinkedHashMap<String, ExportNeed>()
        for (i in 0 until a.length()) {
            val n = a.get(i) as JSONObject
            require(n.keys().asSequence().toSet() == NEED)
            val ref = n.get("ref") as String
            val ops = (n.get("ops") as JSONArray).let { o -> (0 until o.length()).map { o.get(it) as String } }
            require(SkillStep.validConnId(ref) && ref !in out && ops.all(SkillStep::validOpId) && ops.toSet().size == ops.size)
            out[ref] = ExportNeed(ref, ConnKind.of(n.get("kind") as String)!!, ops)
        }
        out
    } catch (_: Exception) { null }

    private fun shared(s: Skill): JSONObject = SkillCodec.toJson(s).let { o -> JSONObject(o, SHARED.filter(o::has).toTypedArray()) }

    /** Every string value in [x], keys and step kinds ("use", already an id's shape) left out. */
    private fun texts(x: Any?): List<String> = mutableListOf<String>().also { collect(x, it, 0) }

    private fun collect(x: Any?, out: MutableList<String>, depth: Int) {
        if (depth > 32) return
        when (x) {
            is String -> out += x
            is JSONObject -> for (k in x.keys()) if (k != "use") collect(x.opt(k), out, depth + 1)
            is JSONArray -> for (i in 0 until x.length()) collect(x.opt(i), out, depth + 1)
        }
    }

    /** A value with a token in a key's shape: 16 or more key characters in a run, with at least 2 digits and 2 letters. */
    fun keyShaped(t: String): Boolean = KEY_TOKEN.findAll(t).any { tok ->
        KEY_RUN.findAll(tok.value).any { r -> r.value.count(Char::isDigit) >= 2 && r.value.count(Char::isLetter) >= 2 }
    }

    private fun label(x: Any?): String? = (x as? String)?.takeIf { it.length <= MAX_LABEL && it.none { c -> c < ' ' } }

    private fun utf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: Exception) { null }

    private fun contains(b: ByteArray, part: ByteArray): Boolean =
        (0..b.size - part.size).any { i -> part.indices.all { b[i + it] == part[it] } }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** Base64 that reads back to exactly the same text, so a signature or key has one written form. */
    private fun unb64(s: String): ByteArray? = try { Base64.getDecoder().decode(s).takeIf { b64(it) == s } } catch (_: Exception) { null }
}
