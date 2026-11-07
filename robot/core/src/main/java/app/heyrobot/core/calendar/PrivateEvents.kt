// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.CalendarFormat
import app.heyrobot.core.DayRange
import app.heyrobot.core.EventKind
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId

// Pure. The Calendar tab (home-design H3): private events, the ones only Robot knows, kept on the phone and in Robot's
// backup, never in the phone's calendar provider, so no other app sees them. What a file means (codec, edits, the merge of
// a restore, the sealed envelope's layout) is decided here; app/sys/PrivateEventsVault.kt holds the key and the file.
// An all-day event is stored midnight to midnight UTC, as the provider stores its own, so CalendarFormat reads both alike.

/** One private event. [id]: 32 hex characters; [changed]: when it was last saved (a restore's Add keeps the newer). */
data class PrivateEvent(
    val id: String,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val place: String? = null,
    val changed: Long = 0,
) {
    /** As the day views read it: kind PRIVATE, keyed by its id. */
    fun asEvent(): CalendarEvent = CalendarEvent(begin, end, allDay, title, place?.takeIf { it.isNotBlank() }, kind = EventKind.PRIVATE, key = id)
}

/** The private events, with the keys a newer Robot wrote ([extra], kept as they are). */
data class PrivateEventsFile(val events: List<PrivateEvent> = emptyList(), val extra: Map<String, Any?> = emptyMap())

sealed class PrivateRead {
    data class Ok(val file: PrivateEventsFile) : PrivateRead()
    data class TooNew(val v: Int) : PrivateRead()
    data object Damaged : PrivateRead()
}

object PrivateEventsCodec {
    /** The backup's entry name; on the phone the sealed form is files/calendar/events.enc. */
    const val FILE = "private_events.json"
    const val VERSION = 1
    const val MAX_EVENTS = 5_000
    const val MAX_TITLE = 200
    const val MAX_PLACE = 300
    const val MAX_FILE = 2L * 1024 * 1024
    private val ID = Regex("^[0-9a-f]{32}$")

    fun validId(id: String): Boolean = ID.matches(id)

    fun encode(f: PrivateEventsFile): String {
        val o = JSONObject()
        for ((k, v) in f.extra) if (k != "v" && k != "events") o.put(k, v)
        o.put("v", VERSION)
        val a = JSONArray()
        for (e in f.events) a.put(JSONObject().put("id", e.id).put("title", e.title).put("begin", e.begin).put("end", e.end)
            .put("allDay", e.allDay).put("changed", e.changed).also { j -> e.place?.takeIf { it.isNotBlank() }?.let { j.put("place", it) } })
        o.put("events", a)
        return o.toString()
    }

    /** A file that names a newer version is left for a newer Robot; anything malformed is damaged, never half read. */
    fun decode(text: String?): PrivateRead {
        if (text == null || text.length > MAX_FILE) return PrivateRead.Damaged
        val o = try { JSONObject(text) } catch (_: Exception) { return PrivateRead.Damaged }
        val v = (o.opt("v") as? Number)?.toInt() ?: return PrivateRead.Damaged
        if (v > VERSION) return PrivateRead.TooNew(v)
        if (v < 1) return PrivateRead.Damaged
        val a = o.opt("events") as? JSONArray ?: return PrivateRead.Damaged
        if (a.length() > MAX_EVENTS) return PrivateRead.Damaged
        val out = ArrayList<PrivateEvent>(a.length())
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val j = a.opt(i) as? JSONObject ?: return PrivateRead.Damaged
            val id = j.opt("id") as? String ?: return PrivateRead.Damaged
            val title = j.opt("title") as? String ?: return PrivateRead.Damaged
            val begin = (j.opt("begin") as? Number)?.toLong() ?: return PrivateRead.Damaged
            val end = (j.opt("end") as? Number)?.toLong() ?: return PrivateRead.Damaged
            val allDay = j.opt("allDay") as? Boolean ?: return PrivateRead.Damaged
            val place = j.opt("place")?.let { it as? String ?: return PrivateRead.Damaged }
            val changed = (j.opt("changed") as? Number)?.toLong() ?: 0L
            val e = PrivateEvent(id, title, begin, end, allDay, place, changed)
            if (!PrivateEdits.valid(e) || !seen.add(id)) return PrivateRead.Damaged
            out += e
        }
        val extra = LinkedHashMap<String, Any?>()
        for (k in o.keys()) if (k != "v" && k != "events") extra[k] = o.opt(k)
        return PrivateRead.Ok(PrivateEventsFile(out.sortedBy { it.begin }, extra))
    }
}

/** Why an edit was refused; the form says it. */
enum class PrivateRefusal { NO_TITLE, ENDS_BEFORE, FULL, NOT_FOUND, LOCKED, UNREADABLE }

data class PrivateEdit(val file: PrivateEventsFile, val refused: PrivateRefusal? = null)

object PrivateEdits {
    fun newId(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun valid(e: PrivateEvent): Boolean = PrivateEventsCodec.validId(e.id) && e.title.isNotBlank() && e.title.length <= PrivateEventsCodec.MAX_TITLE &&
        (e.place?.length ?: 0) <= PrivateEventsCodec.MAX_PLACE && e.end >= e.begin && (!e.allDay || e.end > e.begin)

    /** The title and place as kept: spaces folded, cut at their caps. */
    fun clean(e: PrivateEvent): PrivateEvent = e.copy(title = fold(e.title).take(PrivateEventsCodec.MAX_TITLE),
        place = e.place?.let(::fold)?.take(PrivateEventsCodec.MAX_PLACE)?.takeIf { it.isNotEmpty() })

    private fun fold(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** [e] added, or put in place of the one with its id. */
    fun save(f: PrivateEventsFile, e: PrivateEvent, now: Long): PrivateEdit {
        val c = clean(e).copy(changed = now)
        if (c.title.isEmpty()) return PrivateEdit(f, PrivateRefusal.NO_TITLE)
        if (c.end < c.begin || (c.allDay && c.end <= c.begin)) return PrivateEdit(f, PrivateRefusal.ENDS_BEFORE)
        val exists = f.events.any { it.id == c.id }
        if (!exists && f.events.size >= PrivateEventsCodec.MAX_EVENTS) return PrivateEdit(f, PrivateRefusal.FULL)
        require(PrivateEventsCodec.validId(c.id))
        val events = (f.events.filter { it.id != c.id } + c).sortedBy { it.begin }
        return PrivateEdit(f.copy(events = events))
    }

    fun delete(f: PrivateEventsFile, id: String): PrivateEdit =
        if (f.events.none { it.id == id }) PrivateEdit(f, PrivateRefusal.NOT_FOUND) else PrivateEdit(f.copy(events = f.events.filter { it.id != id }))
}

/** A restore's private events: Add keeps both sides by id (the newer save of one that is on both), Replace takes the backup's. */
object PrivateMerge {
    fun merge(have: PrivateEventsFile, backup: PrivateEventsFile, replace: Boolean): PrivateEventsFile {
        if (replace) return have.copy(events = backup.events.take(PrivateEventsCodec.MAX_EVENTS))
        val byId = LinkedHashMap<String, PrivateEvent>()
        for (e in have.events) byId[e.id] = e
        for (e in backup.events) { val h = byId[e.id]; if (h == null || e.changed > h.changed) byId[e.id] = e }
        return have.copy(events = byId.values.sortedBy { it.begin }.take(PrivateEventsCodec.MAX_EVENTS))
    }
}

/**
 * The sealed file: "RPE1", the key's alias (one byte of length, then ASCII), then the cipher's IV and ciphertext with the
 * magic and alias as additional data. Only [PrivateEnvelope.ALIAS] is ever asked for; any other name is damaged data.
 */
object PrivateEnvelope {
    const val ALIAS = "robot_calendar_v1"
    private val MAGIC = "RPE1".toByteArray(Charsets.US_ASCII)

    fun seal(plain: ByteArray, c: MemoryCipher): ByteArray {
        val a = c.alias.toByteArray(Charsets.US_ASCII)
        require(a.size in 1..255)
        val head = MAGIC + byteArrayOf(a.size.toByte()) + a
        return head + c.seal(plain, head)
    }

    /** The alias in [bytes]' header, or null when it isn't this envelope or names another key. */
    fun alias(bytes: ByteArray): String? {
        if (bytes.size < MAGIC.size + 2 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val n = bytes[MAGIC.size].toInt() and 0xff
        if (n == 0 || bytes.size <= MAGIC.size + 1 + n) return null
        return String(bytes, MAGIC.size + 1, n, Charsets.US_ASCII).takeIf { it == ALIAS }
    }

    /** The plain bytes; throws MemoryKeyException(BAD_DATA) for anything that isn't this envelope. */
    fun open(bytes: ByteArray, cipher: (String) -> MemoryCipher?): ByteArray {
        val alias = alias(bytes) ?: throw MemoryKeyException(KeyProblem.BAD_DATA)
        val head = MAGIC.size + 1 + alias.length
        val c = cipher(alias) ?: throw MemoryKeyException(KeyProblem.GONE)
        return c.open(bytes.copyOfRange(head, bytes.size), bytes.copyOfRange(0, head))
    }
}

/** The agenda the Calendar tab scrolls: [days] from [from], each with its events (all-day first, then by start). */
data class AgendaDay(val date: LocalDate, val events: List<CalendarEvent>)

object CalendarAgenda {
    const val DAYS = 14

    fun range(from: LocalDate, zone: ZoneId, days: Int = DAYS): DayRange = DayRange(from.atStartOfDay(zone), from.plusDays(days.toLong()).atStartOfDay(zone))

    fun days(events: List<CalendarEvent>, from: LocalDate, zone: ZoneId, days: Int = DAYS): List<AgendaDay> = (0 until days).map { i ->
        val d = from.plusDays(i.toLong())
        AgendaDay(d, CalendarFormat.inRange(events, DayRange(d.atStartOfDay(zone), d.plusDays(1).atStartOfDay(zone)))
            .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.begin }))
    }

    /** Whether Robot may change [e]: a private event, or a phone event Robot added ([robotAdded], their provider ids). */
    fun editable(e: CalendarEvent, robotAdded: Set<Long>): Boolean = e.kind == EventKind.PRIVATE || (e.id > 0 && e.id in robotAdded)

    /** The phone's events and the private ones, in one list by start. */
    fun join(phone: List<CalendarEvent>?, private: List<PrivateEvent>): List<CalendarEvent> =
        (phone.orEmpty() + private.map { it.asEvent() }).sortedBy { it.begin }
}

/**
 * Leave-by (home-design §6.3): the next event's place and one tap to your maps app. Robot computes no time and sends the
 * place nowhere: the geo link goes to the app you pick, on the phone.
 */
object LeaveBy {
    fun place(next: CalendarEvent?): String? = next?.location?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    fun geo(place: String): String = "geo:0,0?q=" + URLEncoder.encode(place, "UTF-8").replace("+", "%20") // l10n: not text (a URI)
}
