package app.heyrobot.core.update

// Pure. Settings › Updates: Robot's versions as its release tags and versionName write them ("v0.4.6", "0.6.0-rc1"), and
// their order. Nothing here assumes how a version maps to a versionCode: the APK's own versionCode is checked against the
// installed one before an install (ArchiveCheck).

/**
 * A version: two to four numbers ("0.4.5", "1.0"), and an optional pre-release after "-" ("0.6.0-rc1", "1.0.0-beta.2").
 * Order: the numbers first (a missing one counts as 0), then a pre-release comes before the release itself, and
 * pre-releases compare part by part ("rc2" before "rc10": runs of digits compare as numbers, letters ignoring case). Only
 * ASCII is accepted, so a look-alike (full-width digits, a Cyrillic letter) never parses. Build metadata ("+abc") isn't.
 */
class RobotVersion private constructor(
    private val numbers: List<Int>,
    private val pre: List<String>,
    /** As written, without a leading "v" ("0.6.0-rc1"): what a release's APK name carries. */
    val text: String,
) : Comparable<RobotVersion> {
    /** A test build ("0.6.0-rc1"). */
    val isPreRelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: RobotVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePart(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    /** Equal when neither comes first: "0.4" and "0.4.0", "0.6.0-RC1" and "0.6.0-rc1". */
    override fun equals(other: Any?): Boolean = other is RobotVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.dropLastWhile { it == 0 }.hashCode() * 31 +
        pre.map { p -> runs(p).joinToString("|") { r -> if (r[0] in '0'..'9') r.trimStart('0') else r.lowercase() } }.hashCode()

    override fun toString(): String = text

    companion object {
        const val MAX_LENGTH = 64
        private const val MAX_DIGITS = 9
        private const val MAX_PART = 24

        /** [s] as a version ("v" or "V" in front allowed), or null when it isn't one. */
        fun parse(s: String?): RobotVersion? {
            if (s == null || s.isEmpty() || s.length > MAX_LENGTH) return null
            val body = if (s[0] == 'v' || s[0] == 'V') s.substring(1) else s
            if (body.isEmpty() || !body.all { ascii(it) || it == '.' || it == '-' }) return null
            val dash = body.indexOf('-')
            val core = if (dash < 0) body else body.substring(0, dash)
            val nums = core.split('.')
            if (nums.size !in 2..4) return null
            val numbers = nums.map { n ->
                if (n.isEmpty() || n.length > MAX_DIGITS || !n.all { it in '0'..'9' } || (n.length > 1 && n[0] == '0')) return null
                n.toInt()
            }
            val pre = if (dash < 0) emptyList() else {
                val p = body.substring(dash + 1).split('.')
                if (p.any { it.isEmpty() || it.length > MAX_PART }) return null
                p
            }
            return RobotVersion(numbers, pre, body)
        }

        private fun ascii(c: Char) = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

        /** Runs of digits as numbers, letters ignoring case, digits before letters (as SemVer puts numbers first). */
        private fun comparePart(a: String, b: String): Int {
            val ra = runs(a)
            val rb = runs(b)
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val dx = x[0] in '0'..'9'
                val dy = y[0] in '0'..'9'
                val c = when {
                    dx && dy -> {
                        val tx = x.trimStart('0')
                        val ty = y.trimStart('0')
                        if (tx.length != ty.length) tx.length.compareTo(ty.length) else tx.compareTo(ty)
                    }
                    dx -> -1
                    dy -> 1
                    else -> x.lowercase().compareTo(y.lowercase())
                }
                if (c != 0) return c
            }
            return ra.size.compareTo(rb.size)
        }

        private fun runs(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val digit = s[i] in '0'..'9'
                var j = i + 1
                while (j < s.length && (s[j] in '0'..'9') == digit) j++
                out += s.substring(i, j)
                i = j
            }
            return out
        }
    }
}
