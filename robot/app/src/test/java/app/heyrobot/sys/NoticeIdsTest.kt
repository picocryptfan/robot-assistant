package app.heyrobot.sys

import app.heyrobot.assistant.LinkNotices
import app.heyrobot.core.link.DeskNoticeKind
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * fix-notice-ids: every notification id and id range Robot posts under, read from the code, and none may meet another
 * (untagged notices in one package replace or cancel each other: 1.3.0's backup reminder sat in the skills' slots). The
 * list is here, not in a table the code reads, so each notice keeps its id where it's posted; the scan below keeps the
 * list whole: any literal id or base in a file that posts notices must be on it, and may not fall inside another's ids.
 * fix-notice-ids-b (review Low 3): every file in any module that posts a notice or makes a PendingIntent is read, and each
 * id or request code at a call (notify, cancel, startForeground, PendingIntent.get*) must be a listed constant or a call
 * listed in [calls]; a numeric constant there counts whatever its name. LockShade's ids are read from LockShade.
 */
class NoticeIdsTest {
    private class Use(val file: String, val field: String, val ids: IntRange, val what: String = field) {
        override fun toString() = "$file.$what ${ids.first}" + if (ids.last != ids.first) "..${ids.last}" else ""
    }

    private fun value(cls: String, field: String): Int =
        Class.forName(cls).getDeclaredField(field).apply { isAccessible = true }.getInt(null)

    private fun one(cls: String, field: String, file: String = cls.substringAfterLast('.')) =
        value(cls, field).let { Use(file, field, it..it) }

    private fun span(cls: String, field: String, count: Int, file: String = cls.substringAfterLast('.')) =
        value(cls, field).let { Use(file, field, it until it + count) }

    /** A call whose id or request code isn't a bare listed constant: a parameter, an expression or a number. */
    private class Call(val file: String, val kind: String, val arg: String, val why: String)

    private val calls = listOf<Call>(
        Call("Assistant", "code", "request", "BootCheck.arm: REQ_ALARM, REQ_DROP"),
        Call("BackupReminders", "notice", "OLD_ID", "8,701 cancelled once after an update, only on its own channel"),
        Call("CarNotices", "notice", "id", "START_ID, LEGACY_PARKED_ID"),
        Call("CarNotices", "code", "8_442", "the car notice's open"),
        Call("CarNotices", "code", "8_443", "the car notice's stop"),
        Call("CarNotices", "code", "8_444", "the car notice's start"),
        Call("ConversationService", "code", "req", "pi(): 5,001 to 5,005"),
        Call("ConversationService", "code", "5_010", "the ongoing notice's open"),
        Call("ConversationService", "code", "5_020", "the resume notice's open"),
        Call("ConversationTileService", "code", "5_030", "the tile's open"),
        Call("FilesOut", "notice", "alarm", "AlarmManager.cancel, not a notice"),
        Call("HeadsUpStore", "notice", "it", "AlarmManager.cancel, not a notice"),
        Call("HoldTap", "notice", "alarm(ctx)", "AlarmManager.cancel, not a notice"),
        Call("HoldTap", "code", "0", "its one alarm, the hold's end"),
        Call("FilesOut", "code", "0", "its one alarm"),
        Call("ReportOut", "code", "0", "the chooser's one-shot pick (ReportPicked)"),
        Call("ReportOut", "notice", "pi", "AlarmManager.cancel, not a notice"),
        Call("ReadingService", "code", "req", "action(): 8,561 (pause or resume) and 8,562 (stop)"),
        Call("FilingNotices", "notice", "id", "FILED_ID, ASK_ID"),
        Call("FilingNotices", "code", "id", "a filing notice's action, its own id"),
        Call("FilingNotices", "code", "id + 100", "a filing notice's open: 8,860 and 8,861"),
        Call("LaterNotices", "notice", "id(it)", "a kept question's notice: BASE_ID plus its id's hash, 0 to 0xFFF"),
        Call("LaterNotices", "notice", "id(item)", "a kept question's notice: BASE_ID plus its id's hash, 0 to 0xFFF"),
        Call("LaterNotices", "code", "id(item)", "a kept question's notice's open, its own id"),
        Call("LinkNotices", "notice", "id", "HANDLED_ID, the LIST_ ids, newsId"),
        Call("LinkNotices", "code", "id", "a pairing notice's open, its own id"),
        Call("LinkNotices", "code", "id * 10 + i", "a pairing notice's buttons, ten per id"),
        Call("ListsWidget", "code", "widget", "the widget's own id, with setIdentifier"),
        Call("LockAlarmReceiver", "notice", "pi", "AlarmManager.cancel, not a notice"),
        Call("LockShade", "notice", "id", "hide(): READY to SILENT, read from LockShade"),
        Call("LockShade", "notice", "SKILL_BASE + slot", "the skills' slots, SKILL_SLOTS of them"),
        Call("LockShade", "notice", "READY_ANSWER_BASE + slot", "ready answers' slots, READY_ANSWER_SLOTS of them"),
        Call("LockShade", "notice", "SILENT_BASE + slot", "the quiet runs' slots, SILENT_SLOTS of them"),
        Call("LockShade", "notice", "n.tag", "cancel(tag, id): MOMENT_ID's notices, one per tag"),
        Call("OwnTimerRing", "notice", "OwnTimerIntents.NOTE_BASE + it", "an own timer's notice: NOTE_BASE plus its id"),
        Call("OwnTimerRing", "notice", "OwnTimerIntents.NOTE_BASE + t.id", "an own timer's notice: NOTE_BASE plus its id"),
        Call("OwnTimerRing", "code", "OwnTimerIntents.NOTE_BASE + t.id", "an own timer's stop, its notice's id"),
        Call("OwnTimerStore", "notice", "intent(ctx)", "AlarmManager.cancel, not a notice"),
        Call("ReadyAnswerTap", "notice", "id", "a draft's notice: LockShade.READY_ANSWER_BASE's slots"),
        Call("ReadyAnswerTap", "notice", "h.id", "a draft's notice, cancelled or replaced: its own id"),
        Call("ReadyAnswerTap", "code", "code", "build(): a draft's Send and dismiss, id * 10 + 1 and + 2"),
        Call("ReadyAnswerTap", "code", "id * 10 + 3", "a draft's Edit"),
        Call("ReadyAnswerTap", "notice", "LockShade.READY_ANSWER_BASE + slot", "clear(): every draft's slot, under the gate (Low D, and at the main process's start)"),
        Call("RoutineSpeakingService", "notice", "id", "RoutineNotices and LockShade ids"),
        Call("RoutineSpeakingService", "notice", "tag", "notify(tag, id, n): the id is the next argument, MOMENT_ID by its tag"),
        Call("RoutineSpeakingService", "code", "req", "service(): 8,401 to 8,403"),
        Call("RoutineSpeakingService", "code", "8_420 + (key.hashCode() and 0xff)", "a routine's ready card, by its key"),
        Call("RoutineSpeakingService", "code", "8_430", "the speaking notice's open"),
        Call("RoutineSpeakingService", "code", "8_404", "a summary's play"),
        Call("RoutineSpeakingService", "code", "8_405", "a quiet run's play"),
        Call("Routines", "notice", "pi", "AlarmManager.cancel, not a notice"),
        Call("SkillContinue", "notice", "id", "a skill's slot: LockShade.SKILL_BASE + slot"),
        Call("SkillContinue", "notice", "w.notification", "a skill's slot, kept with its step"),
        Call("SkillContinue", "code", "id", "a skill's next step, its own id"),
        Call("TermuxResultReceiver", "code", "execId", "a Termux run's own number, Termux's result intent"),
        Call("TodayWidget", "notice", "refresh(ctx)", "AlarmManager.cancel, not a notice"),
        Call("TodayWidget", "code", "0", "the widget's refresh and open"),
    )

    /** Request codes kept in constants (only listed: a code may repeat another's, the intent tells them apart). */
    private val codes: List<Use> by lazy {
        listOf(
            one("app.heyrobot.sys.BootCheck", "REQ_ALARM", "Assistant"),
            one("app.heyrobot.sys.BootCheck", "REQ_OPEN", "Assistant"),
            one("app.heyrobot.sys.BootCheck", "REQ_DROP", "Assistant"),
            one("app.heyrobot.lock.LockAlarms", "REQ", "LockAlarmReceiver"),
            one("app.heyrobot.sys.RoutineAlarms", "REQ", "Routines"),
            one("app.heyrobot.sys.RoutineAlarms", "MOMENT_REQ", "Routines"),
            one("app.heyrobot.sys.HeadsUpAlarms", "REQ", "HeadsUpStore"),
            one("app.heyrobot.sys.OwnTimerAlarms", "REQ", "OwnTimerStore"),
            one("app.heyrobot.update.UpdateNotices", "REQ_OPEN"),
            one("app.heyrobot.update.UpdateNotices", "REQ_CONFIRM"),
            one("app.heyrobot.update.UpdateInstaller", "REQ_STATUS"),
            one("app.heyrobot.update.BackupReminders", "REQ_OPEN"),
            one("app.heyrobot.sys.ReportOut", "REQ_EXPIRY"),
        )
    }

    /** Numbers in a file that posts, named like an id, that are neither a notice id nor a PendingIntent's code. */
    private val notIds = mapOf<String, String>(
        "AudioRoute.AUDIO_VIDEO" to "a Bluetooth device class bit",
        "LinkNotices.LAST_NEWS_CODE" to "the last DeskNoticeKind code under NEWS_ID, read above",
        "MessageWire.REQ_LIST" to "the messages helper's request kinds",
        "MessageWire.REQ_MARK_ROBOT" to "the messages helper's request kinds",
        "MessageWire.REQ_REPLY" to "the messages helper's request kinds",
        "MessageWire.REQ_MARK_READ" to "the messages helper's request kinds",
        "MessageWire.REQ_CLEAR" to "the messages helper's request kinds",
        "MessageWire.REQ_REPLY_LANG" to "the messages helper's request kinds",
        "MessageWire.REQ_MISSED" to "the messages helper's request kinds",
        "MessageWire.REQ_HOLD" to "the messages helper's request kinds",
        "MessageWire.REQ_UNREAD" to "the messages helper's request kinds",
    )

    /** Every Int constant in LockShade: X_BASE spans X_SLOTS ids, X_SLOTS is a count, the rest are one id each. */
    private val lockShade: List<Use> by lazy {
        val ints = LockShade::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && "$" !in it.name }
            .associate { it.name to it.apply { isAccessible = true }.getInt(null) }
        ints.filterKeys { !it.endsWith("_SLOTS") }.map { (n, v) ->
            if (!n.endsWith("_BASE")) Use("LockShade", n, v..v)
            else Use("LockShade", n, v until v + requireNotNull(ints[n.removeSuffix("_BASE") + "_SLOTS"]) { "LockShade.$n has no _SLOTS" })
        }
    }

    private class Site(val file: String, val kind: String, val arg: String)

    private val sources: List<File> by lazy {
        File("..").listFiles()!!.map { File(it, "src/main/java") }.filter { it.isDirectory }.flatMap { d ->
            d.walkTopDown().filter { f -> f.extension == "kt" && f.readText().let { "NotificationManager" in it || "PendingIntent" in it || "startForeground(" in it } }
        }
    }

    /** The argument at [index] of the call whose "(" is at [open], whitespace collapsed (brackets and strings skipped over). */
    private fun arg(s: String, open: Int, index: Int): String? {
        var depth = 0; var n = 0; var start = open + 1; var i = open + 1; var quote = false
        while (i < s.length) {
            val c = s[i]
            if (quote) { if (c == '\\') i++ else if (c == '"') quote = false }
            else when (c) {
                '"' -> quote = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return if (n == index) s.substring(start, i).trim().replace(Regex("\\s+"), " ") else null else depth--
                ',' -> if (depth == 0) { if (n == index) return s.substring(start, i).trim().replace(Regex("\\s+"), " "); n++; start = i + 1 }
            }
            i++
        }
        return null
    }

    private val sites: List<Site> by lazy {
        val notice = Regex("""(?:\.notify|\.cancel|\bstartForeground)\(""")
        val code = Regex("""PendingIntent\.get(?:Activity|Activities|Broadcast|Service|ForegroundService)\(""")
        sources.flatMap { f ->
            val s = f.readText()
            notice.findAll(s).mapNotNull { m -> arg(s, m.range.last, 0)?.let { Site(f.nameWithoutExtension, "notice", it) } } +
                code.findAll(s).mapNotNull { m -> arg(s, m.range.last, 1)?.let { Site(f.nameWithoutExtension, "code", it) } }
        }.filter { it.arg.isNotEmpty() }
    }

    private val constant = Regex("""(?:[A-Z]\w*\.)*([A-Z][A-Z0-9_]+)""")
    private val plus = Regex("""([A-Z][A-Z0-9_]+) \+ ([0-9]+)""")

    private val uses: List<Use> by lazy {
        val a = "app.heyrobot.assistant"
        val resumeSpan = 1 + sites.mapNotNull { plus.matchEntire(it.arg)?.takeIf { m -> m.groupValues[1] == "RESUME_ID" }?.groupValues?.get(2)?.toInt() }.maxOrNull().let { it ?: 0 }
        listOf(
            one("app.heyrobot.sys.Problems", "NOTICE_ID"),
            one("app.heyrobot.sys.BootCheck", "NOTICE_ID", "Assistant"),
            one("app.heyrobot.update.UpdateNotices", "ID"),
            one("$a.conversation.ConversationNotices", "ONGOING_ID", "ConversationService"),
            span("$a.conversation.ConversationNotices", "RESUME_ID", resumeSpan, "ConversationService"), // RESUME_ID + 1, + 2: read
            one("app.heyrobot.ui.today.TodayGlances", "NOTICE_ID", "TodayWidget"),
            one("$a.routines.RoutineNotices", "SPEAKING_ID", "RoutineSpeakingService"),
            one("$a.routines.RoutineNotices", "NOT_ASSISTANT_ID", "RoutineSpeakingService"),
            one("$a.car.CarNotices", "START_ID"),
            one("$a.car.CarNotices", "LEGACY_PARKED_ID"),
            one("$a.WakeNotices", "ID"),
            one("app.heyrobot.lock.LockNotices", "ID_WARN"),
            one("app.heyrobot.lock.LockNotices", "ID_ON"),
            one("app.heyrobot.lock.LockNotices", "ID_FAILED"),
            one("app.heyrobot.lock.LockNotices", "ID_OPEN"),
            one("app.heyrobot.lock.LockNotices", "ID_UNSAVED"),
            one("$a.FilingNotices", "FILED_ID"),
            one("$a.FilingNotices", "ASK_ID"),
            one("app.heyrobot.sys.LinkShade", "CARDS_ID"),
            one("app.heyrobot.sys.LinkShade", "REPLIED_ID"),
            one("$a.LinkNotices", "HANDLED_ID"),
            one("$a.LinkNotices", "LIST_ENDED_ID"),
            one("$a.LinkNotices", "LIST_NO_ROOM_ID"),
            one("$a.LinkNotices", "LIST_TAKEN_ID"),
            one("$a.LinkNotices", "LIST_FULL_ID"),
            one("app.heyrobot.update.BackupReminders", "ID"),
            one("app.heyrobot.people.HoldTap", "ID"),
            one("$a.read.ReadingService", "NOTICE_ID"),
            one("app.heyrobot.calls.CallerNotice", "ID", "CallerNameService"),
            span("app.heyrobot.sys.OwnTimerIntents", "NOTE_BASE", 1_000, "OwnTimerStore"), // plus the timer's id, 1 to 999
            span("$a.later.LaterNotices", "BASE_ID", 0x1000), // signal-later: plus its id's hash and 0xFFF
        ) + lockShade + DeskNoticeKind.entries.filter { it != DeskNoticeKind.CARDS && it != DeskNoticeKind.HANDLED && it != DeskNoticeKind.REPLIED }
            .map { k ->
                val id = LinkNotices.newsId(k)
                Use("LinkNotices", if (k.code <= value("$a.LinkNotices", "LAST_NEWS_CODE")) "NEWS_ID" else "MORE_NEWS_ID", id..id, "news ${k.name}")
            }
    }

    private fun meets(a: Use, b: Use) = a.ids.first <= b.ids.last && b.ids.first <= a.ids.last

    @Test fun noTwoNoticesShareAnId() {
        val clashes = uses.indices.flatMap { i -> (i + 1 until uses.size).filter { meets(uses[i], uses[it]) }.map { "${uses[i]} and ${uses[it]}" } }
        assertTrue("notification ids that meet: $clashes", clashes.isEmpty())
    }

    @Test fun everyLiteralIdInAFileThatPostsIsListed() {
        val literal = Regex("""const val (\w+)\s*(?::\s*Int\s*)?=\s*([0-9][0-9_]*|0x[0-9A-Fa-f_]+)\s*(?://.*)?$""", RegexOption.MULTILINE)
        val idLike = Regex("ID|BASE|CODE|REQ")
        assertTrue("no sources found", sources.size >= 10)
        val wrong = ArrayList<String>()
        var seen = 0
        for (f in sources) for (m in literal.findAll(f.readText())) {
            val (name, digits) = m.destructured
            val stem = f.nameWithoutExtension
            val atACall = sites.any { it.file == stem && Regex("\\b$name\\b") in it.arg }
            if (!atACall && idLike !in name) continue
            if (name.startsWith("OLD_")) continue // a moved notice's old id: cancelled once, only when its own channel is on it
            if (notIds["$stem.$name"] != null) continue
            seen++
            val v = if (digits.startsWith("0x")) digits.drop(2).replace("_", "").toInt(16) else digits.replace("_", "").toInt()
            if ((uses + codes).none { it.file == stem && it.field == name }) wrong += "$stem.$name = $v isn't listed here"
            if (codes.any { it.file == stem && it.field == name }) continue
            uses.filter { !(it.file == stem && it.field == name) && v in it.ids }.forEach { wrong += "$stem.$name = $v is inside $it" }
        }
        assertTrue("literal ids seen: $seen", seen >= 25)
        assertTrue(wrong.joinToString("; "), wrong.isEmpty())
    }

    @Test fun everyIdAndCodeAtACallIsListed() {
        val named = (uses + codes).map { it.field }.toSet()
        val wrong = ArrayList<String>()
        for (s in sites) {
            val c = constant.matchEntire(s.arg)?.groupValues?.get(1) ?: plus.matchEntire(s.arg)?.groupValues?.get(1)
            if (c != null && c in named && (s.kind == "code" || uses.any { it.field == c })) continue
            if (calls.none { it.file == s.file && it.kind == s.kind && it.arg == s.arg })
                wrong += "Call(\"${s.file}\", \"${s.kind}\", \"${s.arg.replace("\"", "\\\"")}\", \"\"),"
        }
        assertTrue("sites seen: ${sites.size}", sites.size >= 60)
        assertTrue("not listed:\n" + wrong.distinct().joinToString("\n"), wrong.isEmpty())
        val stale = calls.filter { c -> sites.none { it.file == c.file && it.kind == c.kind && it.arg == c.arg } }
        assertTrue("listed calls no longer in the code: " + stale.joinToString { "${it.file} ${it.kind} ${it.arg}" }, stale.isEmpty())
    }

    @Test fun lockShadeHidesEachOfItsIds() {
        val hide = File("src/main/java/app/heyrobot/sys/LockShade.kt").readText().substringAfter("fun hide(")
        assertTrue(lockShade.size >= 7)
        val missed = lockShade.filter { u -> !Regex("\\b${u.field}\\b").containsMatchIn(hide) }
        assertTrue("LockShade.hide doesn't cancel: $missed", missed.isEmpty())
    }
}
