package app.heyrobot.core.output

import app.heyrobot.core.DeviceKind
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.RoutineTriggers
import app.heyrobot.core.RunLedger
import app.heyrobot.core.TriggerEvent
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageBuffer
import app.heyrobot.core.messages.Snapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The headset and car core's hooks against the routines' device triggers and 0.7's messages, as they meet when wired. */
class CoreLinksTest {
    private val buds = RememberedDevice(1, DeviceRole.HEADSET, DeviceFamily.BLUETOOTH, "Pixel Buds Pro", "EE:FF", private = true)
    private val car = RememberedDevice(2, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "Civic", "AA:BB")

    private fun routine(id: String, trigger: RoutineTrigger) = Routine(id, id, trigger, listOf(RoutineStep.Brief), created = 0)

    @Test fun rememberedDevicesStartTheRoutinesDeviceTriggers() {
        assertEquals(DeviceKind.entries.map { it.id }, DeviceRole.entries.map { it.id })
        assertEquals(TriggerEvent.Device(DeviceKind.CAR, "dev-2", true), DeviceTriggers.event(PresenceEvent.Connected(car, 0)))
        assertEquals(TriggerEvent.Device(DeviceKind.HEADSET, "dev-1", false), DeviceTriggers.event(PresenceEvent.Disconnected(buds, 0)))

        val routines = listOf(
            routine("any car", RoutineTrigger.Device(DeviceKind.CAR)),
            routine("this car", RoutineTrigger.Device(DeviceKind.CAR, "dev-2")),
            routine("another car", RoutineTrigger.Device(DeviceKind.CAR, "dev-3")),
            routine("buds off", RoutineTrigger.Device(DeviceKind.HEADSET, connect = false)),
        )
        val now = ZonedDateTime.of(2026, 10, 12, 17, 5, 0, 0, ZoneId.of("America/Toronto"))
        val fired = { e: PresenceEvent -> RoutineTriggers.fired(routines, DeviceTriggers.event(e)!!, now, RunLedger()).fire.map { it.routine.id } }
        assertEquals(listOf("any car", "this car"), fired(PresenceEvent.Connected(car, 0)))
        assertEquals(listOf("buds off"), fired(PresenceEvent.Disconnected(buds, 0)))
        // The routines' own matching and DeviceTriggers agree on every event.
        for (e in listOf(PresenceEvent.Connected(car, 0), PresenceEvent.Disconnected(car, 0), PresenceEvent.Connected(buds, 0), PresenceEvent.Disconnected(buds, 0))) {
            assertEquals(routines.filter { DeviceTriggers.matches(e, it.trigger as RoutineTrigger.Device) }.map { it.id }, fired(e))
        }
    }

    @Test fun aConversationIsTheNoticeTheHeadsetAndTheCarSpeak() {
        val mom = Snapshots.thread(Snapshots.cheogram1to1("Are you coming?", "Call me"))
        val n = mom.notice()!!
        assertEquals(mom.key, n.conversationKey)
        assertEquals("Mom Martin", n.sender)
        assertEquals(mom.messages.last().senderKey, n.senderKey)
        assertEquals("Call me", n.text)
        assertEquals("Cheogram", n.app)
        assertTrue(n.replyable && !n.isGroup && !n.hidden && n.dndAllows)

        val group = Snapshots.thread(Snapshots.cheogramGroup).notice(dndAllows = false)!!
        assertEquals("Famille", group.group)
        assertEquals("Julie", group.sender)
        assertFalse(group.dndAllows)

        // Signal showing only "New message": hidden, and no reply action to fill.
        val hidden = Snapshots.thread(Snapshots.signalNameOnly).notice()!!
        assertTrue(hidden.hidden && !hidden.replyable && hidden.text.isEmpty())

        // Only your own messages: nothing to announce.
        assertNull(mom.copy(messages = mom.messages.map { it.copy(fromMe = true) }, hidden = null).notice())
    }

    @Test fun theBufferIsTheFeed() {
        val buffer = MessageBuffer()
        val mom = Snapshots.thread(Snapshots.cheogram1to1("Are you coming?"))
        buffer.posted(mom)
        buffer.posted(Snapshots.thread(Snapshots.signal))
        val feed: MessageFeed = BufferFeed(buffer, { Snapshots.T + 60 * Snapshots.MIN }, { it != mom.key })
        assertEquals(listOf(MessageApp.SIGNAL.title, MessageApp.CHEOGRAM.title), feed.unread().map { it.app })
        assertFalse(feed.current(mom.key)!!.dndAllows)
        buffer.markReadByRobot(mom.messages.map { it.id })
        assertEquals(listOf(MessageApp.SIGNAL.title), feed.unread().map { it.app })
        buffer.removed(mom.notificationKey, Snapshots.T + 60 * Snapshots.MIN)
        assertNull(feed.current(mom.key))
    }
}
