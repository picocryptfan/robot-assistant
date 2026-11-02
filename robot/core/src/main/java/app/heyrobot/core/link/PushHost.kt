// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

// Pure. L16: a peer's push address must name a host out on the internet. Your phone POSTs ciphertext to whatever a peer's code
// or hint says, so a peer must not be able to point that at your own phone or the network you're on. Only the address's spelling
// is checked here; a name that resolves to a private address is Tor's or the network's to refuse.
object PushHost {
    private const val LOCAL = "localhost" // l10n: not text (a host name)

    /** [e] already passed PushRoute.endpointOk: it starts with `https://` and has a host. */
    fun public(e: String): Boolean {
        val start = e.indexOf("//") + 2
        var end = start
        while (end < e.length && e[end] != '/' && e[end] != '?' && e[end] != '#') end++
        val authority = e.substring(start, end)
        if (authority.any { it == '@' || it == '\\' || it == '%' }) return false
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0 || !portOk(authority.substring(close + 1))) return false
            val v6 = v6(authority.substring(1, close)) ?: return false
            return v6Public(v6)
        }
        val colon = authority.indexOf(':')
        val host = if (colon < 0) authority else authority.substring(0, colon)
        if (colon >= 0 && !portOk(authority.substring(colon))) return false
        return hostPublic(host.lowercase().removeSuffix("."))
    }

    private fun portOk(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s[0] != ':' || s.length !in 2..6 || !s.drop(1).all { it in '0'..'9' }) return false
        return s.drop(1).toInt() in 1..65535
    }

    private fun hostPublic(h: String): Boolean {
        if (h.isEmpty() || h == LOCAL || h.endsWith(".$LOCAL")) return false
        val labels = h.split('.')
        if (labels.any { it.isEmpty() || !it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' } }) return false
        // As a browser reads a host: a last part that is a number makes the whole thing an address, however it is spelled.
        val last = labels.last()
        if (!last.all { it in '0'..'9' } && !last.startsWith("0x")) return true
        val v4 = v4(h) ?: return false
        return v4Public(v4)
    }

    /** Exactly four decimal parts, no leading zeros (`0177.0.0.1` is loopback to some readers): anything else isn't one. */
    private fun v4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        return IntArray(4) { i ->
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            p.toInt().also { if (it > 255) return null }
        }
    }

    private fun v4Public(a: IntArray): Boolean {
        val (x, y, z) = a
        return !(x == 0 || x == 10 || x == 127 || x >= 224 ||
            (x == 100 && y in 64..127) || (x == 169 && y == 254) || (x == 172 && y in 16..31) ||
            (x == 192 && y == 168) || (x == 192 && y == 0 && (z == 0 || z == 2)) || (x == 198 && y in 18..19) ||
            (x == 198 && y == 51 && z == 100) || (x == 203 && y == 0 && z == 113))
    }

    /** Eight 16-bit groups from the text between the brackets, or null when it isn't an IPv6 address. */
    private fun v6(s: String): IntArray? {
        if (s.isEmpty() || s.length > 45) return null
        val gap = s.indexOf("::")
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) return null
        val head = groups(if (gap >= 0) s.substring(0, gap) else s) ?: return null
        val tail = if (gap >= 0) groups(s.substring(gap + 2)) ?: return null else IntArray(0)
        val n = head.size + tail.size
        if (if (gap >= 0) n > 7 else n != 8) return null
        return head + IntArray(8 - n) + tail
    }

    private fun groups(s: String): IntArray? {
        if (s.isEmpty()) return IntArray(0)
        val parts = s.split(':')
        val out = ArrayList<Int>()
        for ((i, p) in parts.withIndex()) {
            if ('.' in p) {
                if (i != parts.lastIndex) return null
                val a = v4(p) ?: return null
                out += a[0] * 256 + a[1]
                out += a[2] * 256 + a[3]
            } else {
                if (p.length !in 1..4 || !p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                out += p.toInt(16)
            }
        }
        return out.toIntArray()
    }

    private fun v6Public(g: IntArray): Boolean {
        val first = g[0]
        // Unspecified, loopback and the old IPv4-compatible range; link-local; unique local; multicast; documentation.
        if ((0..5).all { g[it] == 0 }) return false
        if (first and 0xffc0 == 0xfe80 || first and 0xfe00 == 0xfc00 || first and 0xff00 == 0xff00) return false
        if (first == 0x2001 && g[1] == 0xdb8) return false
        // An IPv4 address carried in IPv6 (mapped; NAT64) is judged as that address.
        val mapped = (0..4).all { g[it] == 0 } && g[5] == 0xffff
        val nat64 = first == 0x64 && g[1] == 0xff9b && (2..5).all { g[it] == 0 }
        if (mapped || nat64) return v4Public(intArrayOf(g[6] shr 8, g[6] and 255, g[7] shr 8, g[7] and 255))
        return true
    }
}
