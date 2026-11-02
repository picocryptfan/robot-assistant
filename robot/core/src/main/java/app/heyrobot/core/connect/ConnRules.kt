// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.web.PublicAddress

// Pure. Where a connection may send (connections plan D1 and D2, C2), decided here so every client (C3's ConnHttp) and
// every check before one asks the same rules.
//
// D1, home or internet: every address a call uses is of one kind, checked on every lookup and on the socket actually
// connected. Home is your own network as 0.6.0's rule reads it (LanRule: loopback, the RFC 1918 ranges, link-local,
// Tailscale's 100.64/10, unique-local IPv6; an IPv4-mapped address counts as its IPv4 one); internet is a public unicast
// address as web search's guard reads it (PublicAddress). The two never overlap. Everything else (unspecified, multicast
// and broadcast, the documentation and benchmarking ranges, NAT64's 64:ff9b::/96, 6to4, Teredo) is neither: dropped from
// a lookup and never connected to (C12's M3: DNS64 adds a NAT64 address beside an IPv4-only name's real one on IPv6-only
// mobile networks), and a lookup that gives nothing else is refused, as one that gives both kinds is ([lookup]; the
// client, C3, keeps the home ones of a lookup that gives both: [lookupHomeWins]). An address typed as one of them is
// refused ([literal]). http is home only on every call; https may be either kind and may change between
// calls (split DNS), since TLS's name check already stops a rebinding.
//
// D2, secrets over http: a connection with any secret (a token, ntfy's topic, an address that is itself the key, a
// header value you marked secret) is https only: refused when you type the address (ConnCheck) and again before any
// client is built ([refusal]), and an address that is itself the key is checked when it is put into the request
// (ConnWire), since its scheme lives inside the secret.
//
// C6, where you are over http: a routing server learns where the phone is (travel_time) or where your Home is (its
// Test). On someone else's Wi-Fi a private address can be anyone's machine, so a routing connection over plain http is
// allowed only to an address typed as loopback (a server on the phone itself, or the PC through adb reverse) or in
// Tailscale's 100.64.0.0/10, whose devices are yours; anything else needs https. Refused when you type the address
// (ConnCheck), before any card or client ([refusal]), and again on the socket really connected (ConnHttp, [nearOnly]).

object ConnRules {
    /** Where an address is (D1). */
    enum class Place { HOME, INTERNET, NEITHER }

    /** [a]: an address's 4 or 16 bytes, as the client's resolver gives them. */
    fun place(a: ByteArray): Place = when {
        a.size != 4 && a.size != 16 -> Place.NEITHER
        LanRule.classify(a).cleartext -> Place.HOME
        PublicAddress.isPublic(a) -> Place.INTERNET
        else -> Place.NEITHER
    }

    fun place(w: ConnWhere): Place = if (w == ConnWhere.HOME) Place.HOME else Place.INTERNET

    /** What a lookup decided: use [addresses], all of kind [where]; or refused, with why. */
    sealed class Lookup {
        class Ok(val where: ConnWhere, val addresses: List<ByteArray>) : Lookup() {
            override fun toString(): String = "Lookup.Ok(${where.id})" // l10n: not text (toString, for the logs)
        }

        data class Refused(val why: ConnFailure) : Lookup()
    }

    /**
     * D1 on the addresses a name resolved to ([resolved]), for an [https] call or not: those that are neither home nor
     * internet dropped (none left: refused), the rest all home or all internet; http only home. Every address kept stays
     * in order (the client tries them as the system gave them).
     */
    fun lookup(https: Boolean, resolved: List<ByteArray>): Lookup {
        if (resolved.isEmpty()) return Lookup.Refused(ConnFailure.UNREACHABLE)
        val usable = usable(resolved)
        if (usable.isEmpty()) return Lookup.Refused(ConnFailure.NOWHERE)
        val places = usable.map(::place).toSet()
        if (places.size > 1) return Lookup.Refused(ConnFailure.BOTH_KINDS)
        val where = if (places.single() == Place.HOME) ConnWhere.HOME else ConnWhere.INTERNET
        if (!https && where != ConnWhere.HOME) return Lookup.Refused(ConnFailure.MOVED)
        return Lookup.Ok(where, usable.map { it.copyOf() })
    }

    /** C12's M3: [resolved] without the addresses that are neither home nor internet, in order: never connected to. */
    private fun usable(resolved: List<ByteArray>): List<ByteArray> = resolved.filter { place(it) != Place.NEITHER }

    /**
     * D1 as the client applies it to a lookup (C3's ConnHttp, on every lookup): [lookup], except that a lookup giving home
     * and internet addresses (besides any that are neither, dropped as [lookup] drops them) keeps only its home ones.
     * That is a dual-stack server at home: a private IPv4 address beside a global IPv6 one, as many home routers' names
     * give (C2's open risk; refusing it would refuse such a server on every call). It changes nothing D1 protects: over http the internet addresses are the ones
     * it never uses (0.6.0's rule for a helper on your network, LanRule.decide, keeps the private ones the same way), and
     * over https TLS's name check stops a rebinding to your network, as it does for an answer that is all home. So the call
     * stays one kind, home. An http name that resolves only to the internet is still [ConnFailure.MOVED].
     */
    fun lookupHomeWins(https: Boolean, resolved: List<ByteArray>): Lookup {
        val usable = usable(resolved)
        val places = usable.map(::place)
        if (Place.HOME in places && Place.INTERNET in places)
            return lookup(https, usable.filterIndexed { i, _ -> places[i] == Place.HOME })
        return lookup(https, resolved)
    }

    /** D1 on the socket really connected: its address is of the kind its lookup gave (any scheme). */
    fun socket(connected: ByteArray, where: ConnWhere): Boolean = place(connected) == place(where)

    /**
     * D1 for an address typed as a number (never looked up, so checked here): its kind, or why Robot won't use it. A name
     * gives null (its lookup decides).
     */
    fun literal(url: ConnUrl): Lookup? {
        val a = url.literal ?: return null
        return lookup(url.https, listOf(a))
    }

    /** C6: an address a routing server may be reached at over plain http: loopback, or Tailscale's 100.64/10 (an IPv4-mapped one as its IPv4). */
    fun nearOnly(a: ByteArray): Boolean = (a.size == 4 || a.size == 16) && LanRule.classify(a).let { it == LanRule.Range.LOOPBACK || it == LanRule.Range.CGNAT }

    /** C6: [c] sends where you are (a routing server) over plain http to anything but a typed loopback or Tailscale address. */
    fun locationOverHttp(c: Connection): Boolean {
        val url = c.address ?: return false
        if (c.kind != ConnKind.ROUTING || url.https) return false
        return url.literal?.let { !nearOnly(it) } ?: true
    }

    /** D2: [c] holds a secret and its address isn't https (an address that is itself the key is checked again in ConnWire). */
    fun secretOverHttp(c: Connection): Boolean = c.secrets.isNotEmpty() && c.address?.https != true

    /**
     * Why no client may be built for [c] (null: one may): its address is missing or Robot won't use it, a secret over
     * http (D2), where you are over http to anything but loopback or Tailscale (C6), or http to a typed address that isn't on your network (D1). Nothing is connected to find out; a name's
     * lookup is decided by [lookup] when it happens.
     */
    fun refusal(c: Connection): ConnFailure? {
        if (c.kind == ConnKind.HAND_OFF) return ConnFailure.NOWHERE
        val url = c.address ?: return ConnFailure.NOWHERE
        if (secretOverHttp(c)) return ConnFailure.KEY_OVER_HTTP
        (literal(url) as? Lookup.Refused)?.let { return it.why }
        return if (locationOverHttp(c)) ConnFailure.LOCATION_OVER_HTTP else null
    }
}
