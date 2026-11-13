// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.Person
import org.json.JSONArray
import org.json.JSONObject

/**
 * seal-people-fix 5 (question 47's default): Calls to People you choose while People is sealed (the phone locked). Only
 * the chosen people's numbers, each as (person id, an HMAC of its call key under a Keystore key that never leaves the
 * phone): no names, nothing that gives a number back. Checked against the ids the locks name now, so someone taken off
 * a lock is refused at once, even while locked.
 */
/**
 * seal-small 2: how Phone lock compares a number with a chosen one. Both with a country code (+, 00 or 011 first): every
 * digit after it, so +44 20 7946 0958 is not +1 207 946 0958. Otherwise their last 10 digits, as before (+1 418… and
 * 418… match). As keys, so the call list compares HMACs only: [chosen] for a number in People, [asked] for one called.
 */
object CallKeys {
    /** The digits after a country code's prefix, or null when [n] has none. */
    fun international(n: String): String? {
        val d = n.filter { it.isDigit() }
        return when {
            n.trim().startsWith("+") -> d
            d.startsWith("00") -> d.drop(2)
            d.startsWith("011") -> d.drop(3)
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private fun last10(n: String) = n.filter { it.isDigit() }.takeLast(10)

    /** A chosen number's keys: "n" and its last 10 digits without a country code; with one, "c" and them, and "f" and every digit. */
    fun chosen(n: String): List<String> {
        val l = last10(n).ifEmpty { return emptyList() }
        val full = international(n) ?: return listOf("n$l")
        return listOf("c$l", "f$full")
    }

    /** The keys a called number matches by: any chosen one's last 10 digits without a country code; with one, the full number. */
    fun asked(n: String): List<String> {
        val l = last10(n).ifEmpty { return emptyList() }
        val full = international(n) ?: return listOf("n$l", "c$l")
        return listOf("n$l", "f$full")
    }
}

object LockCalls {
    const val FILE = "lock_calls.json" // l10n: not text (a file name)
    /** 2: seal-small's keys ([CallKeys]); a file of 1 lets nobody through until People's next write or unlock rewrites it. */
    const val VERSION = 2

    data class Entry(val id: String, val mac: String)

    /** The [chosen] people's entries, or null when a number can't be hashed (the list is then cleared: fail closed). */
    fun entries(people: List<Person>, chosen: Set<String>, keys: (String) -> List<String>, mac: (String) -> String?): List<Entry>? {
        val out = ArrayList<Entry>()
        for (p in people) if (p.id in chosen) for (k in p.phones.flatMap { keys(it.number) }.filter { it.isNotEmpty() }.distinct()) out += Entry(p.id, mac(k) ?: return null)
        return out
    }

    fun toJson(entries: List<Entry>): String = JSONObject().put("v", VERSION).put("calls", JSONArray().apply { // l10n: not text (JSON keys)
        for (e in entries) put(JSONObject().put("id", e.id).put("mac", e.mac)) // l10n: not text (JSON keys)
    }).toString()

    /** The entries in [text], or null when it is missing, damaged or a newer Robot's (nobody may be called then). */
    fun decode(text: String?): List<Entry>? = try {
        val o = JSONObject(text ?: return null)
        if (o.optInt("v") != VERSION) null else o.getJSONArray("calls").let { a -> // l10n: not text (JSON keys)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.getString("id"), it.getString("mac")) } } // l10n: not text (JSON keys)
        }
    } catch (_: Exception) {
        null
    }

    /** A number with call keys [numberKeys] may be called during a lock: an entry for an id the locks name now ([ids]) hashes to one. */
    fun allows(entries: List<Entry>?, ids: Set<String>, numberKeys: List<String>, mac: (String) -> String?): Boolean {
        if (entries.isNullOrEmpty() || ids.isEmpty()) return false
        val ms = numberKeys.filter { it.isNotEmpty() }.map { mac(it) ?: return false }.toSet()
        return entries.any { it.id in ids && it.mac in ms }
    }
}
