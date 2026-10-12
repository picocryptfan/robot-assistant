package app.heyrobot.core.headset

import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.PresenceEvent
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val budsA2dp = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
private val budsSco = budsA2dp.copy(type = RouteType.BT_SCO)
private val budsMic = budsSco.copy(sink = false)
private val le = SeenDevice(RouteType.BLE_HEADSET, "Galaxy Buds", "XX:XX:XX:XX:AB:CD")
private val usb = SeenDevice(RouteType.USB_HEADSET, "USB-C headset")
private val usbMic = usb.copy(sink = false)
private val wiredPhones = SeenDevice(RouteType.WIRED_HEADPHONES, "Headphones")
private val speaker = SeenDevice(RouteType.SPEAKER)
private val phoneMic = SeenDevice(RouteType.BUILTIN_MIC, sink = false)
private val carSco = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
private val carA2dp = carSco.copy(type = RouteType.BT_A2DP)

/** Which microphone and output a session uses (design §3.1, §3.9 RoutePlan). */
class RoutePlanTest {
    private val s = HeadsetSettings()
    private val memory = DeviceMemory()

    private fun plan(entry: EntryKind, devices: List<SeenDevice>, settings: HeadsetSettings = s, call: Boolean = false, m: DeviceMemory = memory) =
        (RoutePlanner.plan(entry, devices, m, settings, call) as RouteDecision.Plan).plan

    @Test fun bluetoothClassicListensOverSco() {
        val p = plan(EntryKind.BVRA, listOf(speaker, budsA2dp, budsSco, budsMic, phoneMic))
        assertEquals(RoutePlan(budsSco, MicRoute.SCO, CaptureSource.VOICE_COMMUNICATION, RouteType.BT_SCO, budsSco, PlaybackUsage.VOICE_COMMUNICATION), p)
        assertTrue(p.takesCallRoute)
    }

    @Test fun leAudioNeedsVoiceCommunication() {
        val p = plan(EntryKind.BVRA, listOf(speaker, le))
        assertEquals(MicRoute.LE_AUDIO, p.mic)
        assertEquals(CaptureSource.VOICE_COMMUNICATION, p.capture)
        assertEquals(le, p.communication)
    }

    @Test fun wiredAndUsbNeedNoCommunicationDevice() {
        val p = plan(EntryKind.HOOK, listOf(speaker, budsA2dp, budsSco, usb, usbMic))
        assertEquals(RoutePlan(null, MicRoute.USB, CaptureSource.VOICE_RECOGNITION, RouteType.USB_HEADSET, usb, PlaybackUsage.ASSISTANT), p)
        assertFalse(p.takesCallRoute)
        // Headphones without a microphone: the phone listens, Robot plays in them.
        val h = plan(EntryKind.HOOK, listOf(speaker, wiredPhones))
        assertEquals(MicRoute.PHONE, h.mic)
        assertEquals(wiredPhones, h.output)
        // A hook with only Bluetooth connected: the Bluetooth headset.
        assertEquals(MicRoute.SCO, plan(EntryKind.HOOK, listOf(budsA2dp, budsSco)).mic)
    }

    @Test fun phoneMicrophoneKeepsTheMusic() {
        val p = plan(EntryKind.BVRA, listOf(budsA2dp, budsSco), s.copy(listenWith = ListenWith.PHONE_MIC))
        assertEquals(RoutePlan(null, MicRoute.PHONE, CaptureSource.VOICE_RECOGNITION, null, budsA2dp, PlaybackUsage.ASSISTANT), p)
        // An A2DP-only device (no SCO listed): the phone listens.
        assertEquals(MicRoute.PHONE, plan(EntryKind.BVRA, listOf(budsA2dp)).mic)
    }

    @Test fun anEnabledRememberedHeadsetComesFirst() {
        val other = SeenDevice(RouteType.BT_SCO, "Other", "XX:XX:XX:XX:00:01")
        val m = DeviceMemory().remember(budsSco, DeviceRole.HEADSET, 0)!!.first
        assertEquals(budsSco, plan(EntryKind.BVRA, listOf(other, budsSco), m = m).communication)
        assertEquals(other, plan(EntryKind.BVRA, listOf(other, budsSco), m = m.setEnabled(1, false).remember(other, DeviceRole.HEADSET, 0)!!.first).communication)
    }

    @Test fun nothingDuringACallAndNoHeadset() {
        assertEquals(RouteDecision.DuringCall, RoutePlanner.plan(EntryKind.BVRA, listOf(budsSco), memory, s, callActive = true))
        assertEquals(RouteDecision.NoHeadset, RoutePlanner.plan(EntryKind.BVRA, listOf(speaker, phoneMic), memory, s, callActive = false))
        assertEquals(RouteDecision.NoHeadset, RoutePlanner.plan(EntryKind.HOOK, listOf(speaker), memory, s, callActive = false))
    }

    @Test fun theCar() {
        val all = listOf(speaker, carA2dp, carSco)
        // The steering-wheel button: the car's microphone and voice, as a call.
        val button = (RoutePlanner.plan(EntryKind.BVRA, all, memory, s, false, car = carA2dp) as RouteDecision.Plan).plan
        assertEquals(RoutePlan(carSco, MicRoute.SCO, CaptureSource.VOICE_COMMUNICATION, RouteType.BT_SCO, carSco, PlaybackUsage.VOICE_COMMUNICATION), button)
        // "Hey robot": the phone keeps the question; the car's call route opens for the answer.
        val wake = (RoutePlanner.plan(EntryKind.ASSIST, all, memory, s, false, car = carA2dp, wakeInCar = true) as RouteDecision.Plan).plan
        assertEquals(RoutePlan(null, MicRoute.PHONE, CaptureSource.VOICE_RECOGNITION, null, carSco, PlaybackUsage.VOICE_COMMUNICATION, answerCommunication = carSco), wake)
        assertTrue(wake.takesCallRoute)
        // Not as a call: the car's media output.
        val media = (RoutePlanner.plan(EntryKind.ASSIST, all, memory, s, false, car = carA2dp, wakeInCar = true, carAsCall = false) as RouteDecision.Plan).plan
        assertEquals(RoutePlan(null, MicRoute.PHONE, CaptureSource.VOICE_RECOGNITION, null, carA2dp, PlaybackUsage.ASSISTANT, answerCommunication = null), media)
        // An A2DP-only car: the phone listens, Robot plays on the car's media.
        val a2dpOnly = (RoutePlanner.plan(EntryKind.BVRA, listOf(carA2dp), memory, s, false, car = carA2dp) as RouteDecision.Plan).plan
        assertEquals(MicRoute.PHONE, a2dpOnly.mic)
        assertEquals(carA2dp, a2dpOnly.output)
    }

    @Test fun fallbackWhenTheRouteFails() {
        val p = plan(EntryKind.BVRA, listOf(budsA2dp, budsSco))
        val f = RoutePlanner.onFailed(p, RouteFail.TIMEOUT, s, listOf(budsA2dp, budsSco)) as RouteFallback.PhoneMic
        assertEquals(RoutePlan(null, MicRoute.PHONE, CaptureSource.VOICE_RECOGNITION, null, budsA2dp, PlaybackUsage.ASSISTANT), f.plan)
        assertEquals("I'll listen on the phone.", f.say.en)
        assertEquals(RouteFallback.End(RoutePlanner.NO_HEADSET_MIC), RoutePlanner.onFailed(p, RouteFail.TIMEOUT, s.copy(fallbackToPhoneMic = false), listOf(budsA2dp, budsSco)))
        assertEquals(RouteFallback.End(RoutePlanner.NO_HEADSET_MIC), RoutePlanner.onFailed(p, RouteFail.GONE, s, listOf(budsA2dp)))
        // No media output left for it: end.
        assertEquals(RouteFallback.End(RoutePlanner.NO_HEADSET_MIC), RoutePlanner.onFailed(p, RouteFail.TIMEOUT, s, listOf(budsSco)))
        assertEquals("Le micro des écouteurs ne s'est pas connecté.", RoutePlanner.NO_HEADSET_MIC.fr)
    }

    @Test fun theCaptureMustComeFromTheHeadset() {
        val p = plan(EntryKind.BVRA, listOf(budsA2dp, budsSco))
        assertTrue(RoutePlanner.inputOk(p, budsMic))
        assertFalse(RoutePlanner.inputOk(p, phoneMic))
        assertFalse(RoutePlanner.inputOk(p, null))
        assertFalse(RoutePlanner.inputOk(p, SeenDevice(RouteType.BT_SCO, "Other", "XX:XX:XX:XX:00:01", sink = false)))
        val phone = plan(EntryKind.BVRA, listOf(budsA2dp), s.copy(listenWith = ListenWith.PHONE_MIC))
        assertTrue(RoutePlanner.inputOk(phone, phoneMic))
        assertTrue(RoutePlanner.inputOk(phone, null))
        assertFalse(RoutePlanner.inputOk(phone, budsMic))
        assertTrue(RoutePlanner.cost(p).musicPauses)
        assertFalse(RoutePlanner.cost(phone).musicPauses)
    }
}

/** A headset session as a state machine (design §3.3 flow, §3.9 "fake AudioManager" tests). */
class HeadsetSessionTest {
    private val s = HeadsetSettings()
    private val devices = listOf(speaker, budsA2dp, budsSco)
    private val sco = (RoutePlanner.plan(EntryKind.BVRA, devices, DeviceMemory(), s, false) as RouteDecision.Plan).plan

    private fun run(vararg events: SessionEvent, settings: HeadsetSettings = s, start: HeadsetSessionState = HeadsetSessionState()): Pair<HeadsetSessionState, List<List<SessionEffect>>> {
        var st = start
        val out = mutableListOf<List<SessionEffect>>()
        for (e in events) {
            val (n, fx) = HeadsetSessionMachine.on(st, e, settings, devices)
            st = n
            out += fx
        }
        return st to out
    }

    @Test fun aWholeSessionOverSco() {
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(budsMic, 700), SessionEvent.Heard(false), SessionEvent.ReplyDone,
            SessionEvent.FollowUpExpired)
        assertEquals(listOf(SessionEffect.SetCommunication(budsSco), SessionEffect.PinOutput(budsSco), SessionEffect.StartTimer(TimerKind.ROUTE, 3_000)), fx[0])
        assertEquals(listOf(SessionEffect.CancelTimers, SessionEffect.RouteLogged(MicRoute.SCO, 700), SessionEffect.Chime,
            SessionEffect.Listen(CaptureSource.VOICE_COMMUNICATION, MicRoute.SCO)), fx[1])
        assertEquals(emptyList<SessionEffect>(), fx[2])
        assertEquals(listOf(SessionEffect.Chime, SessionEffect.Listen(CaptureSource.VOICE_COMMUNICATION, MicRoute.SCO), SessionEffect.StartTimer(TimerKind.FOLLOW_UP, 6_000)), fx[3])
        assertEquals(listOf(SessionEffect.CancelTimers, SessionEffect.StopListening, SessionEffect.ClearCommunication, SessionEffect.PinOutput(null), SessionEffect.End(EndWhy.DONE)), fx[4])
        assertEquals(SessionPhase.ENDED, st.phase)
    }

    @Test fun aFollowUpQuestionKeepsTheRoute() {
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(budsMic, 500), SessionEvent.Heard(false), SessionEvent.ReplyDone,
            SessionEvent.Heard(false))
        assertEquals(SessionPhase.BUSY, st.phase)
        assertEquals(listOf(SessionEffect.CancelTimers), fx[4])
        // No follow-up window: released after the answer.
        val (st2, fx2) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(budsMic, 500), SessionEvent.Heard(false), SessionEvent.ReplyDone,
            settings = s.copy(followUpSeconds = 0))
        assertEquals(SessionPhase.ENDED, st2.phase)
        assertTrue(SessionEffect.ClearCommunication in fx2[3])
    }

    @Test fun aRouteTimeoutFallsBackToThePhone() {
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteTimeout)
        assertEquals(SessionPhase.LISTENING, st.phase)
        assertTrue(st.fellBack)
        assertEquals(listOf(SessionEffect.CancelTimers, SessionEffect.RouteFailed(RouteFail.TIMEOUT, true), SessionEffect.ClearCommunication,
            SessionEffect.PinOutput(budsA2dp), SessionEffect.Say(RoutePlanner.ON_PHONE), SessionEffect.Chime,
            SessionEffect.Listen(CaptureSource.VOICE_RECOGNITION, MicRoute.PHONE)), fx[1])
        // Fallback off: it says so and ends.
        val (st2, fx2) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteTimeout, settings = s.copy(fallbackToPhoneMic = false))
        assertEquals(SessionPhase.ENDED, st2.phase)
        assertEquals(SessionEffect.End(EndWhy.ROUTE_FAILED), fx2[1].last())
        assertTrue(SessionEffect.Say(RoutePlanner.NO_HEADSET_MIC) in fx2[1])
        // The capture from the phone although the route says the headset: the same fallback.
        val (st3, fx3) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(phoneMic, 900))
        assertEquals(SessionPhase.LISTENING, st3.phase)
        assertTrue(SessionEffect.RouteFailed(RouteFail.WRONG_INPUT, true) in fx3[1])
    }

    @Test fun theHeadsetGoingStopsEverythingAtOnce() {
        for (at in 1..4) {
            val events = listOf(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(budsMic, 500), SessionEvent.Heard(false), SessionEvent.ReplyDone).take(at)
            val (st, fx) = run(*(events + SessionEvent.DeviceGone).toTypedArray())
            assertEquals("$at", SessionPhase.ENDED, st.phase)
            assertEquals("$at", listOf(SessionEffect.CancelTimers, SessionEffect.StopSpeech, SessionEffect.StopListening, SessionEffect.CancelTurn,
                SessionEffect.ClearCommunication, SessionEffect.PinOutput(null), SessionEffect.End(EndWhy.DEVICE_GONE)), fx.last())
        }
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(budsMic, 500), SessionEvent.CallStarted)
        assertEquals(SessionPhase.ENDED, st.phase)
        assertEquals(SessionEffect.End(EndWhy.CALL), fx.last().last())
    }

    @Test fun duringACallNothingHappens() {
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.DuringCall))
        assertEquals(SessionPhase.ENDED, st.phase)
        assertEquals(listOf(SessionEffect.End(EndWhy.CALL)), fx[0])
        assertEquals(listOf(SessionEffect.End(EndWhy.NO_HEADSET)), run(SessionEvent.Begin(RouteDecision.NoHeadset)).second[0])
        // Events before a Begin do nothing.
        assertEquals(emptyList<SessionEffect>(), run(SessionEvent.Heard(false)).second[0])
    }

    @Test fun buttonsDuringASession() {
        val listening = run(SessionEvent.Begin(RouteDecision.Plan(sco)), SessionEvent.RouteUp(budsMic, 500)).first
        val busy = run(SessionEvent.Heard(false), start = listening).first
        // Stop talking: she stops, and the follow-up window comes when the voice reports it ended.
        assertEquals(listOf(SessionEffect.StopSpeech), HeadsetSessionMachine.on(busy, SessionEvent.Button(ButtonAction.STOP_SPEAKING), s).second)
        // Stop and listen: a new question at once.
        val (l2, fx) = HeadsetSessionMachine.on(busy, SessionEvent.Button(ButtonAction.STOP_AND_LISTEN), s)
        assertEquals(SessionPhase.LISTENING, l2.phase)
        assertEquals(listOf(SessionEffect.StopSpeech, SessionEffect.Chime, SessionEffect.Listen(CaptureSource.VOICE_COMMUNICATION, MicRoute.SCO)), fx)
        assertEquals(listOf(SessionEffect.StopSpeech, SessionEffect.Repeat), HeadsetSessionMachine.on(busy, SessionEvent.Button(ButtonAction.REPEAT), s).second)
        assertEquals(listOf(SessionEffect.Next), HeadsetSessionMachine.on(busy, SessionEvent.Button(ButtonAction.NEXT), s).second)
        // Stop listening ends the session and releases the route.
        val (e, efx) = HeadsetSessionMachine.on(listening, SessionEvent.Button(ButtonAction.STOP_LISTENING), s)
        assertEquals(SessionPhase.ENDED, e.phase)
        assertEquals(SessionEffect.End(EndWhy.BY_YOU), efx.last())
        assertTrue(SessionEffect.ClearCommunication in efx)
        // Cancel while thinking.
        assertEquals(SessionEffect.End(EndWhy.BY_YOU), HeadsetSessionMachine.on(busy, SessionEvent.Button(ButtonAction.CANCEL), s).second.last())
        // Nothing heard: ends.
        assertEquals(SessionEffect.End(EndWhy.NOTHING_HEARD), HeadsetSessionMachine.on(listening, SessionEvent.Heard(true), s).second.last())
    }

    @Test fun wiredStartsListeningAtOnce() {
        val p = (RoutePlanner.plan(EntryKind.HOOK, listOf(usb, usbMic), DeviceMemory(), s, false) as RouteDecision.Plan).plan
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.Plan(p)))
        assertEquals(SessionPhase.LISTENING, st.phase)
        assertEquals(listOf(SessionEffect.PinOutput(usb), SessionEffect.RouteLogged(MicRoute.USB, 0), SessionEffect.Chime,
            SessionEffect.Listen(CaptureSource.VOICE_RECOGNITION, MicRoute.USB)), fx[0])
        // Ending it touches no communication device.
        assertFalse(SessionEffect.ClearCommunication in HeadsetSessionMachine.on(st, SessionEvent.Button(ButtonAction.STOP_LISTENING), s).second)
    }

    @Test fun inTheCarTheCallRouteOpensOnlyToAnswer() {
        val wake = (RoutePlanner.plan(EntryKind.ASSIST, listOf(carA2dp, carSco), DeviceMemory(), s, false, car = carA2dp, wakeInCar = true) as RouteDecision.Plan).plan
        val (st, fx) = run(SessionEvent.Begin(RouteDecision.Plan(wake)), SessionEvent.Heard(false), SessionEvent.ReplyDone)
        assertEquals(listOf(SessionEffect.PinOutput(carSco), SessionEffect.RouteLogged(MicRoute.PHONE, 0), SessionEffect.Chime,
            SessionEffect.Listen(CaptureSource.VOICE_RECOGNITION, MicRoute.PHONE)), fx[0])
        assertEquals(listOf(SessionEffect.SetCommunication(carSco)), fx[1])
        // After the answer the car's media comes back while Robot listens for a follow-up on the phone.
        assertEquals(SessionEffect.ClearCommunication, fx[2].first())
        assertEquals(SessionPhase.FOLLOW_UP, st.phase)
    }
}

/** Headset mode on connect and disconnect, and first use (design §3.4, §2.1). */
class HeadsetAutoTest {
    private val memory = DeviceMemory().remember(budsSco, DeviceRole.HEADSET, 0)!!.first.remember(carSco, DeviceRole.CAR, 0)!!.first
    private val buds = memory.byNumber(1)!!
    private val car = memory.byNumber(2)!!

    @Test fun connectAndDisconnect() {
        val s = HeadsetSettings()
        assertEquals(emptyList<HeadsetReaction>(), HeadsetAuto.onPresence(PresenceEvent.Connected(buds, 0), s, null, null))
        assertEquals(listOf(HeadsetReaction.HoldWakeRoute(buds)), HeadsetAuto.onPresence(PresenceEvent.Connected(buds, 0), s.copy(wakeThroughHeadset = true), null, null))
        assertEquals(emptyList<HeadsetReaction>(), HeadsetAuto.onPresence(PresenceEvent.Connected(buds.copy(enabled = false), 0), s.copy(wakeThroughHeadset = true), null, null))
        assertEquals(emptyList<HeadsetReaction>(), HeadsetAuto.onPresence(PresenceEvent.Connected(car, 0), s.copy(wakeThroughHeadset = true), null, null))
        assertEquals(listOf(HeadsetReaction.ReleaseWakeRoute, HeadsetReaction.EndSession), HeadsetAuto.onPresence(PresenceEvent.Disconnected(buds, 0), s, 1, 1))
        assertEquals(emptyList<HeadsetReaction>(), HeadsetAuto.onPresence(PresenceEvent.Disconnected(buds, 0), s, null, null))
        assertTrue(HeadsetAuto.sessionDeviceGone(budsA2dp, budsSco))
        assertFalse(HeadsetAuto.sessionDeviceGone(carSco, budsSco))
        assertFalse(HeadsetAuto.sessionDeviceGone(budsA2dp, null))
    }

    @Test fun firstUseRemembersAHeadset() {
        val m = HeadsetAuto.firstUse(DeviceMemory(), le, 5)
        assertEquals(DeviceRole.HEADSET, m.devices.single().role)
        assertNull(m.devices.single().private)
        assertEquals(m.copy(devices = m.devices.map { it.copy(seen = 9) }), HeadsetAuto.firstUse(m, le, 9))
        assertEquals(DeviceMemory(), HeadsetAuto.firstUse(DeviceMemory(), speaker, 5))
        assertEquals(DeviceMemory(), HeadsetAuto.firstUse(DeviceMemory(), SeenDevice(RouteType.BLE_SPEAKER, "Box", "XX:XX:XX:XX:00:09"), 5))
    }
}

/** Messages announced in the headset (0.7; design §3.7 rule 5). */
class HeadsetAnnounceTest {
    private val marie = MessageNotice("CHEOGRAM:a#b", "Marie", "k1", "I'm on my way", "Cheogram", replyable = true)
    private val on = HeadsetSettings(announceMessages = true)
    private val unlocked = PhoneState(locked = false)

    private fun d(n: MessageNotice = marie, s: HeadsetSettings = on, routed: Boolean = true, trust: Trust = Trust.PRIVATE, phone: PhoneState = unlocked,
        busy: Boolean = false, allowed: Boolean = true, lang: SpokenLang = SpokenLang.EN) = HeadsetAnnounce.decide(n, s, routed, trust, phone, busy, allowed, lang)

    @Test fun rows() {
        assertEquals(AnnounceDecision.Announce("Message from Marie. Read it?", asks = true), d())
        assertEquals(AnnounceDecision.Announce("Message de Marie. Je te le lis?", asks = true), d(lang = SpokenLang.FR))
        assertEquals(AnnounceDecision.ReadNow("Marie says: I'm on my way"), d(s = on.copy(announceRead = AnnounceRead.ALWAYS)))
        assertEquals(AnnounceDecision.ReadNow("Marie dit : I'm on my way"), d(s = on.copy(announceRead = AnnounceRead.ALWAYS), lang = SpokenLang.FR))
        assertEquals(AnnounceDecision.Announce("Message from Marie in Famille. Read it?", asks = true), d(n = marie.copy(group = "Famille")))
        assertEquals(AnnounceDecision.Announce("Message from Marie. The app doesn't show the text.", asks = false), d(n = marie.copy(hidden = true)))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.OFF), d(s = HeadsetSettings(announceMessages = false)))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.NOT_PRIVATE), d(routed = false))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.NOT_PRIVATE), d(trust = Trust.ASK))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.NOT_PRIVATE), d(trust = Trust.NOT_PRIVATE))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.CALL), d(phone = PhoneState(false, callActive = true)))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.LOCKED), d(phone = PhoneState(true)))
        assertEquals(AnnounceDecision.Announce("Message from Marie. Read it?", asks = true), d(s = on.copy(announceLocked = true), phone = PhoneState(true)))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.DND), d(n = marie.copy(dndAllows = false)))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.NOT_ALLOWED), d(allowed = false))
        assertEquals(AnnounceDecision.Announce("Message from Marie. Read it?", asks = true), d(allowed = false, s = on.copy(announceFrom = AnnounceFrom.EVERYONE)))
        assertEquals(AnnounceDecision.Skip(AnnounceCode.BUSY), d(busy = true))
        assertFalse(marie.toString().contains("Marie") || marie.toString().contains("way"))
    }
}
