// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

// Pure. The first call to a host (connections plan D13, C2): from any skill, the first request a connection sends to its
// host shows a tap-only card first: Allow for this connection, Just this time, No. The answer is kept per connection and
// host in connections.json (ConnStore.answer), by the address's origin (scheme, host and port), so a changed address
// asks again: an answer for another origin never counts, and is dropped when the address changes. "Just this time" is
// kept only so Settings can show it; it never spares a card. "No" keeps nothing. A connection restored from someone
// else's backup keeps no answers (C10).

object ConnConsent {
    /** The origin [c]'s requests go to (what an answer names), or null when it has no address (a hand-off). */
    fun origin(c: Connection): String? = c.address?.origin

    /** [c]'s next request needs the first-use card: no "Allow for this connection" for its origin. */
    fun asks(c: Connection): Boolean {
        val o = origin(c) ?: return true
        return c.answers[o] != ConsentAnswer.ALLOW
    }

    /** [c] with [answer] kept for [origin] (null: forgotten). An answer for an origin [c] doesn't send to now is never kept. */
    fun answered(c: Connection, origin: String, answer: ConsentAnswer?): Connection {
        val kept = c.answers.filterKeys { it == origin(c) && it != origin }
        return c.copy(answers = if (answer != null && origin == origin(c)) kept + (origin to answer) else kept)
    }

    /** [c] with its address changed to [address]: answers for any other origin go (a changed address asks again). */
    fun withAddress(c: Connection, address: ConnUrl?): Connection =
        c.copy(address = address, answers = c.answers.filterKeys { it == address?.origin }, where = if (address?.origin == c.address?.origin) c.where else null,
            tested = if (address == c.address) c.tested else null)

    /**
     * [c] as restored from someone else's backup (plan §5.7): no first-use answers, a spoken yes off, and "Test it again"
     * (C10 applies it; keys never come with it).
     */
    fun fromSomeoneElse(c: Connection): Connection = c.copy(answers = emptyMap(), spokenYes = false, tested = null, where = null)
}
