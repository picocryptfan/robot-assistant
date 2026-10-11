package app.heyrobot.core.update

import org.json.JSONArray
import org.json.JSONObject

// Pure. GitHub's answer about a repository's releases (GET /repos/{owner}/{repo}/releases, and /releases/latest), read
// defensively: whatever the answer holds, a field of the wrong type or missing makes that release or file count as unusable,
// never as something else. A release is offered only with exactly the APK named after its tag and a SHA256SUMS file
// ("Releasing" in the README).

/** One file of a release. [id] is what the download asks for; the two URLs are kept as GitHub gave them, never opened. */
data class ReleaseAsset(val id: Long, val name: String, val size: Long, val browserDownloadUrl: String?, val url: String?)

data class GitHubRelease(
    val tag: String,
    val name: String,
    /** The release notes as written (Markdown), cut at [GitHubReleases.MAX_BODY]: show them through [ReleaseNotes.plain]. */
    val body: String,
    val draft: Boolean,
    /** GitHub's "pre-release" mark: a test build. */
    val prerelease: Boolean,
    /** ISO 8601 ("2026-10-11T09:30:00Z"), or null. */
    val publishedAt: String?,
    val assets: List<ReleaseAsset>,
) {
    val version: RobotVersion? get() = RobotVersion.parse(tag)

    /** A test build: marked so on GitHub, or a pre-release version ("0.6.0-rc1") whatever the mark says. */
    val testBuild: Boolean get() = prerelease || version?.isPreRelease == true
}

object GitHubReleases {
    /** The answer is read whole up to this size (the client stops at 1 MB). */
    const val MAX_JSON = 1 shl 20
    const val MAX_RELEASES = 100
    const val MAX_ASSETS = 100
    const val MAX_BODY = 20_000
    private const val MAX_NAME = 255
    private const val MAX_DEPTH = 24

    /** The list of releases (newest first, as GitHub gives them), or null when the answer isn't one. */
    fun parseList(json: String?): List<GitHubRelease>? {
        if (!plausible(json)) return null
        val a = try { JSONArray(json) } catch (_: Exception) { return null } catch (_: StackOverflowError) { return null }
        return (0 until minOf(a.length(), MAX_RELEASES)).mapNotNull { i -> (a.opt(i) as? JSONObject)?.let(::release) }
    }

    /** One release (/releases/latest), or null. */
    fun parseOne(json: String?): GitHubRelease? {
        if (!plausible(json)) return null
        return try { release(JSONObject(json)) } catch (_: Exception) { null } catch (_: StackOverflowError) { null }
    }

    /**
     * Not too long, strict JSON as GitHub writes it, and not nested deeper than GitHub's answers ever are (a deep nest would
     * overflow the JSON parser's stack). Strict because org.json also reads single-quoted strings, bare words, comments, "="
     * and ";": with any of those this count could see a string where the parser sees brackets. Here a string is only ever
     * in double quotes, and every value (a string, a number, true, false, null, an array or an object) starts where a value
     * may and is followed by ",", ":", "]", "}" or the end, so the two always agree where strings begin and end.
     */
    private fun plausible(json: String?): Boolean {
        if (json == null || json.length > MAX_JSON) return false
        var depth = 0
        // At the start, and after "[", "{", "," or ":", a value may start; after a value, only a separator or a close.
        var valueMayStart = true
        var i = 0
        val n = json.length
        while (i < n) {
            val c = json[i]
            when (c) {
                ' ', '\t', '\n', '\r' -> {}
                '[', '{' -> {
                    if (!valueMayStart || ++depth > MAX_DEPTH) return false
                }
                ']', '}' -> {
                    if (--depth < 0) return false
                    valueMayStart = false
                }
                ',', ':' -> {
                    if (valueMayStart) return false
                    valueMayStart = true
                }
                '"' -> {
                    if (!valueMayStart) return false
                    i++
                    while (true) {
                        if (i >= n) return false
                        when (json[i]) {
                            '\\' -> i += 2
                            '"' -> break
                            else -> i++
                        }
                    }
                    valueMayStart = false
                }
                else -> {
                    if (!valueMayStart) return false
                    val start = i
                    if (c == '-' || c in '0'..'9') {
                        while (i + 1 < n && json[i + 1].let { it in '0'..'9' || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' }) i++
                    } else {
                        while (i + 1 < n && json[i + 1] in 'a'..'z') i++
                        if (json.substring(start, i + 1) !in LITERALS) return false
                    }
                    valueMayStart = false
                }
            }
            i++
        }
        return depth == 0
    }

    private val LITERALS = setOf("true", "false", "null")

    private fun release(o: JSONObject): GitHubRelease? {
        val tag = str(o, "tag_name")?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return null
        val assets = (o.opt("assets") as? JSONArray)?.let { a -> (0 until minOf(a.length(), MAX_ASSETS)).mapNotNull { (a.opt(it) as? JSONObject)?.let(::asset) } }
            ?: emptyList()
        return GitHubRelease(
            tag = tag,
            name = str(o, "name")?.take(MAX_NAME) ?: "",
            body = str(o, "body")?.take(MAX_BODY) ?: "",
            // Missing or not a boolean: the careful reading (a draft is never offered, a test build only when you ask for them).
            draft = (o.opt("draft") as? Boolean) ?: true,
            prerelease = (o.opt("prerelease") as? Boolean) ?: true,
            publishedAt = str(o, "published_at")?.takeIf { it.length <= 40 },
            assets = assets,
        )
    }

    private fun asset(o: JSONObject): ReleaseAsset? {
        val id = long(o, "id")?.takeIf { it > 0 } ?: return null
        val name = str(o, "name")?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return null
        val size = long(o, "size")?.takeIf { it >= 0 } ?: return null
        return ReleaseAsset(id, name, size, str(o, "browser_download_url"), str(o, "url"))
    }

    private fun str(o: JSONObject, key: String): String? = o.opt(key) as? String

    /** A whole number that fits a Long (GitHub's ids and sizes); 1.5, "12", true or a huge number are not. */
    private fun long(o: JSONObject, key: String): Long? = when (val v = o.opt(key)) {
        is Int -> v.toLong()
        is Long -> v
        else -> null
    }
}

/** The two files a release needs: the APK named after its tag, and SHA256SUMS. */
data class ReleaseFiles(val apk: ReleaseAsset, val sums: ReleaseAsset)

object ReleaseAssets {
    const val SUMS = "SHA256SUMS"
    /** Larger than any Robot APK will be; a larger file is refused before it is downloaded. */
    const val MAX_APK = 300L * 1024 * 1024
    const val MAX_SUMS = 64 * 1024

    /** "Robot-0.4.6.apk" for "v0.4.6". */
    fun apkName(v: RobotVersion): String = "Robot-${v.text}.apk"

    /**
     * Exactly one file named [apkName] for the release's own version (byte for byte: no path, no other case, no look-alike)
     * and exactly one SHA256SUMS, both of a sensible size; null otherwise.
     */
    fun select(r: GitHubRelease): ReleaseFiles? {
        val v = r.version ?: return null
        val apks = r.assets.filter { it.name == apkName(v) }
        val sums = r.assets.filter { it.name == SUMS }
        if (apks.size != 1 || sums.size != 1) return null
        val apk = apks.single()
        val s = sums.single()
        if (apk.size !in 1..MAX_APK || s.size !in 1..MAX_SUMS.toLong()) return null
        return ReleaseFiles(apk, s)
    }
}

/**
 * A SHA256SUMS file as sha256sum writes it: "<64 hex digits>  <file>" (or " *<file>", binary mode), one a line. Hex in
 * either case. Any other line, a file named twice with two sums, a path or a control character in a name: the whole file
 * is refused (null), since a half-understood list must not vouch for anything.
 */
class Sha256Sums private constructor(private val sums: Map<String, String>) {
    /** The lower-case SHA-256 for [file], or null when the list doesn't name it. */
    fun of(file: String): String? = sums[file]

    val files: Set<String> get() = sums.keys

    companion object {
        private const val MAX_LINES = 1000
        private val LINE = Regex("""([0-9A-Fa-f]{64})(?:  | \*)(.+)""")

        fun parse(text: String?): Sha256Sums? {
            if (text == null || text.length > ReleaseAssets.MAX_SUMS) return null
            val lines = text.removePrefix("﻿").split('\n')
            if (lines.size > MAX_LINES) return null
            val out = LinkedHashMap<String, String>()
            for (raw in lines) {
                val line = raw.removeSuffix("\r")
                if (line.isBlank()) continue
                val m = LINE.matchEntire(line) ?: return null
                val hash = m.groupValues[1].lowercase()
                val name = m.groupValues[2]
                if (!fileName(name)) return null
                val had = out.put(name, hash)
                if (had != null && had != hash) return null
            }
            return if (out.isEmpty()) null else Sha256Sums(out)
        }

        /** A plain file name: no folder, not "." or "..", no control or invisible characters, nothing to escape. */
        private fun fileName(n: String): Boolean = n.length in 1..255 && n != "." && n != ".." && n == n.trim() && n.none { c ->
            c == '/' || c == '\\' || c.code < 0x20 || c.code == 0x7f || c.isWhitespace() && c != ' ' || Character.getType(c) == Character.FORMAT.toInt()
        }
    }
}

/**
 * Release notes for the Updates page: plain text, never HTML or links. Tags and comments are dropped, a Markdown link keeps
 * its words, images go, headings and emphasis lose their marks, invisible and direction-changing characters go (they could
 * make the text read differently from what it is), at most four combining marks stay on a character, and it is cut at
 * [MAX_LINES] lines and [MAX_CHARS] characters.
 */
object ReleaseNotes {
    const val MAX_CHARS = 3_000
    const val MAX_LINES = 40
    private val COMMENT = Regex("""<!--.*?(-->|$)""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""</?[A-Za-z][^<>\n]{0,500}>""")
    private val TAG_GAP = Regex("""(?<=[^\s\u0001])\u0001+(?=[^\s\u0001])""")
    private val IMAGE = Regex("""!\[[^\]\n]{0,500}]\([^)\n]{0,2000}\)""")
    private val LINK = Regex("""\[([^\]\n]{0,500})]\([^)\n]{0,2000}\)""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+""")
    private val EMPHASIS = Regex("""\*\*|__|`""")
    /** More than four combining marks on one character ("Zalgo" text, which draws far above and below its line). */
    private val MARK_PILE = Regex("""([\p{Mn}\p{Me}]{4})[\p{Mn}\p{Me}]+""")
    /** Control characters, invisible formatting (zero-width, direction marks and overrides) and line or paragraph separators. */
    private val HIDDEN = setOf(Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.UNASSIGNED, Character.PRIVATE_USE)

    fun plain(body: String?): String {
        if (body.isNullOrBlank()) return ""
        var t = body.take(GitHubReleases.MAX_BODY).replace("\r\n", "\n").replace('\r', '\n')
        t = COMMENT.replace(t, "")
        // A tag between two words leaves a space ("More</summary>hidden"), elsewhere nothing.
        t = TAG.replace(t, "\u0001")
        t = TAG_GAP.replace(t, " ").replace("\u0001", "")
        t = IMAGE.replace(t, "")
        t = LINK.replace(t) { it.groupValues[1] }
        t = t.filter { c -> c == '\n' || c == '\t' || Character.getType(c).toByte() !in HIDDEN }
        t = MARK_PILE.replace(t, "$1")
        val lines = t.split('\n').map { l ->
            BULLET.replace(HEADING.replace(l.replace('\t', ' '), ""), "$1• ").let { EMPHASIS.replace(it, "") }.trimEnd()
        }
        // At most one empty line in a row, none at the start or end.
        val kept = ArrayList<String>()
        for (l in lines) if (l.isNotEmpty() || (kept.isNotEmpty() && kept.last().isNotEmpty())) kept += l
        while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.size - 1)
        var out = kept.take(MAX_LINES).joinToString("\n")
        val cut = kept.size > MAX_LINES || out.length > MAX_CHARS
        if (out.length > MAX_CHARS) {
            out = out.take(MAX_CHARS)
            // Never end inside a surrogate pair.
            if (out.isNotEmpty() && out.last().isHighSurrogate()) out = out.dropLast(1)
        }
        return if (cut) out.trimEnd() + "…" else out
    }
}
