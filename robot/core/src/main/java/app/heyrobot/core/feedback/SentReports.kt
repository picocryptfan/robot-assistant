// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import org.json.JSONArray
import org.json.JSONObject

/**
 * RP5, Reports you sent (report-a-problem-plan.md "What's new" 6): one report that went to the share sheet: its reference
 * (so you can quote it), when, its kind and the parts ticked. Never its words, its turn or its screenshot.
 */
data class SentReport(val ref: String, val at: Long, val kind: FeedbackKind, val parts: Set<ReportPart>)

/**
 * The list, newest first, at most [MAX], in reports_sent.json in Robot's own folder (the main app writes it when Send
 * hands the sealed file on). Not in a backup: the plan names no category for it, and a reference only means something on
 * the phone that sent it.
 */
object SentReports {
    const val FILE = "reports_sent.json" // l10n: not text (a file name)
    const val VERSION = 1
    const val MAX = 50

    /** [r] first, a report of the same reference replaced, the oldest past [MAX] dropped. */
    fun add(list: List<SentReport>, r: SentReport): List<SentReport> = (listOf(r) + list.filter { it.ref != r.ref }).take(MAX)

    fun toJson(list: List<SentReport>): String = JSONObject().put("v", VERSION).put("sent", JSONArray().apply {
        list.take(MAX).forEach { r ->
            put(JSONObject().put("ref", r.ref).put("at", r.at).put("kind", r.kind.name).put("parts", JSONArray(r.parts.map { it.name })))
        }
    }).toString()

    /** The list a file holds, newest first: an entry without a valid reference, a time or a kind is left out. */
    fun fromJson(text: String?): List<SentReport> {
        val a = try { JSONObject(text ?: return emptyList()).optJSONArray("sent") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ref = o.optString("ref").takeIf { FeedbackRef.valid(it) } ?: return@mapNotNull null
            val at = o.optLong("at", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val kind = FeedbackKind.of(o.optString("kind")) ?: return@mapNotNull null
            val parts = o.optJSONArray("parts")?.let { p -> (0 until p.length()).mapNotNull { j -> ReportPart.entries.firstOrNull { it.name == p.optString(j) } } }.orEmpty()
            SentReport(ref, at, kind, parts.toSet())
        }.sortedByDescending { it.at }.distinctBy { it.ref }.take(MAX)
    }
}

/**
 * RP6: what Send's chooser carries back to [app.heyrobot.sys.ReportPicked] when an app is picked, fixed by Robot in the
 * PendingIntent's data, which the system's fill-in can't change: the reference, the kind, the parts ticked and the sealed
 * file's size. Never the app's name.
 */
data class HandedReport(val ref: String, val kind: FeedbackKind, val parts: Set<ReportPart>, val size: Long)

object HandedReports {
    const val SCHEME = "robot-report" // l10n: not text (a URI scheme)
    /** Larger than any sealed report (the screenshot is the most of it). */
    const val MAX_SIZE = 64L * 1024 * 1024

    /** "robot-report:RFB-ABCDEFGH/2/SAID.SCREENSHOT/12345": the reference, the kind's id, the parts, the size in bytes. */
    fun data(h: HandedReport): String =
        "$SCHEME:${h.ref}/${h.kind.id}/${h.parts.sortedBy { it.ordinal }.joinToString(".") { it.name }}/${h.size}"

    /** [data] read back; null for anything else, never guessed. */
    fun of(data: String?): HandedReport? {
        val rest = data?.takeIf { it.startsWith("$SCHEME:") }?.substring(SCHEME.length + 1) ?: return null
        val f = rest.split('/')
        if (f.size != 4 || !FeedbackRef.valid(f[0])) return null
        val kind = FeedbackKind.entries.firstOrNull { it.id.toString() == f[1] } ?: return null
        val parts = if (f[2].isEmpty()) emptySet() else f[2].split('.').map { n -> ReportPart.entries.firstOrNull { it.name == n } ?: return null }.toSet()
        val size = f[3].takeIf { it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it in 0..MAX_SIZE } ?: return null
        return HandedReport(f[0], kind, parts, size)
    }
}
