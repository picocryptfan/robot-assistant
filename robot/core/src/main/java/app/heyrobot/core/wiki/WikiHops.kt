// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

// Pure. L1 (review-wikipedia): a copy's download never takes a plain-http hop, at any host. download.kiwix.org sends
// Robot on to a mirror that isn't known ahead, so the network security config can't name it: WikiStore follows the
// redirects itself first (HEAD, on Wi-Fi only) and hands Android's download manager the https address they end at. The
// SHA-256 pin still checks what arrives.
object WikiHops {
    /** Redirects followed at most. */
    const val MAX = 5

    private val ORIGIN = Regex("^(https://[^/?#\\s]+)", RegexOption.IGNORE_CASE)

    /** Where [from]'s redirect to [location] goes: an https address at any host, else null (http://, another scheme, none). */
    fun next(from: String, location: String?): String? {
        val l = location?.trim().orEmpty()
        return when {
            l.startsWith("https://", ignoreCase = true) && ORIGIN.containsMatchIn(l) -> l
            l.startsWith("//") && l.length > 2 -> "https:$l"
            l.startsWith("/") -> ORIGIN.find(from)?.groupValues?.get(1)?.plus(l)
            else -> null
        }
    }

    /**
     * The https address [start]'s redirects end at, asking each with [ask] (status, Location; status < 0 when it failed),
     * or null: a plain-http hop anywhere, more than [MAX] hops, an error or a failure.
     */
    fun resolve(start: String, ask: (String) -> Pair<Int, String?>): String? {
        var url = next(start, start) ?: return null
        repeat(MAX + 1) {
            val (code, location) = ask(url)
            if (code !in 300..399) return url.takeIf { code in 200..299 }
            url = next(url, location) ?: return null
        }
        return null
    }
}
