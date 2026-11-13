// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.app.NotificationManager
import android.content.Context
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.RLog
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineOutput
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.SpokenPart
import app.heyrobot.core.StepCode
import app.heyrobot.core.headsup.HeadsUp
import app.heyrobot.core.headsup.HeadsUpFacts
import app.heyrobot.core.headsup.HeadsUpSettings
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.Dnd
import app.heyrobot.core.output.spoken
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.HeadsUpAlarms
import app.heyrobot.sys.HeadsUpStore
import app.heyrobot.sys.Paths
import java.io.File
import java.time.ZonedDateTime

// Idea 5's heads-up in your earbuds: "Dentist at 2:30, in 15 minutes", once per event, only in headphones only you can
// hear (core headsup HeadsUp decides). One alarm (RoutineAlarmReceiver's ACTION_HEADSUP, in :voice) at the next event's
// moment, or within the hour to catch new events; armed from Settings › Headset, after each check, at boot and when the
// clock changes. It speaks through RoutineVoice at "Headset only", so OutputGuard checks each line again. Off: no alarm.
object HeadsUpHub {
    /** The alarm rang (RoutineHub's thread): say what's due, if it may be said, then arm the next. */
    fun check(app: Context, nowMs: Long = System.currentTimeMillis()) {
        val s = HeadsUpStore.settings(app)
        if (s.on && Calendars.readGranted(app)) {
            val said = HeadsUpStore.said(app).prune(nowMs)
            val due = HeadsUp.due(HeadsUpAlarms.events(app, nowMs, s), nowMs, s.minutes, said)
            if (due.isNotEmpty()) {
                // One heads-up per event, whatever happens next: a held one isn't tried again.
                HeadsUpStore.saveSaid(app, said.plus(due))
                speak(app, s, due, nowMs)
            }
        }
        HeadsUpAlarms.arm(app, nowMs)
    }

    private fun speak(app: Context, s: HeadsUpSettings, due: List<CalendarEvent>, nowMs: Long) {
        val (phone, route) = RoutineVoice.phoneAndRoute()
        val memory = (DeviceMemory.decode(readOrNull(File(Paths.dir(app), "devices.json"))) as? DeviceFile.Ok)?.memory ?: DeviceMemory()
        val dnd = try { Dnd.of(app.getSystemService(NotificationManager::class.java).currentInterruptionFilter) } catch (_: Throwable) { Dnd.NONE }
        val now = ZonedDateTime.now()
        HeadsUp.hold(s, HeadsUpFacts(route.routed, route.car != null, dnd, phone.callActive, phone.locked), memory, now)?.let {
            RLog.i("headsup_held", "why" to RLog.Code(it.code), "n" to due.size)
            return
        }
        val named = !phone.locked || HeadsUp.namedWhileLocked(route.routed, memory)
        val langs = BriefSource.langs(app)
        val parts = due.map { e ->
            val p = HeadsUp.line(e, nowMs, s.minutes, now, langs.say, named)
            SpokenPart(listOf(spoken(p, langs)), named, StepCode.SAY, shown = listOf(p.render(langs)))
        }
        val r = Routine("headsup", HandsFreeStrings.headsUpName().render(langs), RoutineTrigger.Asked, emptyList(), created = nowMs,
            output = RoutineOutput.HEADSET_ONLY)
        // Held by OutputGuard after all (the route changed): dropped, never said later.
        val session = RoutineVoice.open(r, RoutineVoice.Surface.SERVICE, everything = named && phone.locked, locked = phone.locked) { }
        RoutineVoice.add(session, parts)
        RoutineVoice.close(session)
        RLog.i("headsup_said", "n" to due.size, "named" to named)
    }
}
