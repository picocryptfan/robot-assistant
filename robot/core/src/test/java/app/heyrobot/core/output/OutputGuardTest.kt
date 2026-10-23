// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.output.OutputDecision.Ask
import app.heyrobot.core.output.OutputDecision.Hold
import app.heyrobot.core.output.OutputDecision.Speak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OutputGuard's table: each row, then rules that must hold over every combination. */
class OutputGuardTest {
    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val budsSco = buds.copy(type = RouteType.BT_SCO)
    private val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
    private val speaker = OutputGuard.PHONE_SPEAKER
    private val s = GuardSettings()

    private fun u(personal: Boolean, source: SpeechSource, chars: Int = 40, dnd: Boolean = true) =
        Utterance(if (personal) SpeechClass.PERSONAL else SpeechClass.PUBLIC, source, chars, dnd)

    private fun headset(trust: Trust, locked: Boolean = false, routed: SeenDevice? = buds, media: Boolean = false, call: Boolean = false, comm: Boolean = false) =
        OutputContext(SessionKind.HEADSET, routed, trust, PhoneState(locked, mediaPlaying = media, callActive = call), sessionDevice = buds, commRoute = comm)

    private fun inCar(locked: Boolean = false, trusted: Boolean = false, routed: SeenDevice? = car) =
        OutputContext(SessionKind.CAR, routed, Trust.NOT_PRIVATE, PhoneState(locked), sessionDevice = car, commRoute = true, carTrusted = trusted)

    private fun phone(locked: Boolean = false, media: Boolean = false, call: Boolean = false, routed: SeenDevice = speaker, trust: Trust = Trust.NOT_PRIVATE) =
        OutputContext(SessionKind.PHONE, routed, trust, PhoneState(locked, mediaPlaying = media, callActive = call))

    @Test fun headsetRows() {
        // Private earbuds: personal plays; never-asked Bluetooth asks; not private holds (on screen, or until unlock).
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE), s))
        assertEquals(Ask(AskWhat.PRIVATE_DEVICE), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.ASK), s))
        assertEquals(Hold(HoldWhy.NOT_PRIVATE, Keep.SCREEN), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.NOT_PRIVATE), s))
        // Public plays anywhere the headset is.
        for (t in Trust.entries) assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(false, SpeechSource.ANSWER), headset(t), s))
        // "Personal answers only in private headphones" off: personal plays on any headset.
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.NOT_PRIVATE), s.copy(personalOnlyPrivate = false)))
        // Locked: personal waits for the unlock even in private earbuds, and "Unlock to hear it." is said instead.
        assertEquals(Hold(HoldWhy.LOCKED, Keep.UNLOCK, sayUnlock = true), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, locked = true), s))
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(false, SpeechSource.ANSWER), headset(Trust.PRIVATE, locked = true), s))
        // A routine at "Everything" may speak personal items locked, in private earbuds.
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.ROUTINE), headset(Trust.PRIVATE, locked = true).copy(lockAllowsPersonal = true), s))
        // The call route: call audio.
        assertEquals(Speak(VolumePolicy.CALL), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = budsSco, comm = true), s))
    }

    @Test fun theHeadsetGoesAway() {
        // Routed to the speaker now: nothing plays (Stop when the headset disconnects, on by default).
        for (p in listOf(true, false)) for (src in SpeechSource.entries) {
            val d = OutputGuard.decide(u(p, src), headset(Trust.PRIVATE, routed = speaker), s)
            assertTrue("$p $src $d", d is Hold && d.why == HoldWhy.DEVICE_GONE)
        }
        assertEquals(Hold(HoldWhy.DEVICE_GONE, Keep.SCREEN), OutputGuard.decide(u(false, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = speaker), s))
        assertEquals(Hold(HoldWhy.DEVICE_GONE, Keep.UNLOCK), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = speaker, locked = true), s))
        assertEquals(Hold(HoldWhy.DEVICE_GONE, Keep.SCREEN), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = null), s))
        // With the switch off, a public line goes on where sound goes now; personal never does.
        val off = s.copy(stopOnDisconnect = false)
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(false, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = speaker), off))
        assertEquals(Hold(HoldWhy.DEVICE_GONE, Keep.SCREEN), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = speaker), off))
        // Another Bluetooth headset taking the route counts as gone too.
        val other = SeenDevice(RouteType.BT_A2DP, "Other", "XX:XX:XX:XX:00:01")
        assertTrue(OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = other), s) is Hold)
        // The SCO entry of the same earbuds is the same device.
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.ANSWER), headset(Trust.PRIVATE, routed = budsSco), s))
    }

    @Test fun carRows() {
        // You asked: personal plays through the car (unlocked), shortened when long.
        assertEquals(Speak(VolumePolicy.CALL), OutputGuard.decide(u(true, SpeechSource.ANSWER), inCar(), s))
        assertEquals(Speak(VolumePolicy.CALL, shorten = true), OutputGuard.decide(u(false, SpeechSource.ANSWER, chars = 400), inCar(), s))
        assertEquals(Speak(VolumePolicy.CALL), OutputGuard.decide(u(false, SpeechSource.CARD, chars = 400), inCar(), s))
        // It came by itself and others may hear: ask each time (default), always, or never (on the screen, as in a headset that isn't private).
        assertEquals(Ask(AskWhat.READ_ALOUD), OutputGuard.decide(u(true, SpeechSource.ROUTINE), inCar(), s))
        assertEquals(Ask(AskWhat.READ_ALOUD), OutputGuard.decide(u(true, SpeechSource.MESSAGE), inCar(), s))
        assertEquals(Speak(VolumePolicy.CALL), OutputGuard.decide(u(true, SpeechSource.ROUTINE), inCar(), s.copy(readAloud = ReadAloud.ALWAYS)))
        assertEquals(Hold(HoldWhy.NOT_PRIVATE, Keep.SCREEN), OutputGuard.decide(u(true, SpeechSource.ROUTINE), inCar(), s.copy(readAloud = ReadAloud.NEVER)))
        assertEquals(Hold(HoldWhy.NOT_PRIVATE, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), inCar(), s.copy(readAloud = ReadAloud.NEVER)))
        assertEquals(Speak(VolumePolicy.CALL), OutputGuard.decide(u(false, SpeechSource.ROUTINE), inCar(), s))
        // Locked: personal only in a trusted session.
        assertEquals(Hold(HoldWhy.LOCKED, Keep.UNLOCK, sayUnlock = true), OutputGuard.decide(u(true, SpeechSource.ANSWER), inCar(locked = true), s))
        assertEquals(Speak(VolumePolicy.CALL), OutputGuard.decide(u(true, SpeechSource.ANSWER), inCar(locked = true, trusted = true), s))
        assertEquals(Hold(HoldWhy.LOCKED, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), inCar(locked = true), s))
        assertEquals(Ask(AskWhat.READ_ALOUD), OutputGuard.decide(u(true, SpeechSource.MESSAGE), inCar(locked = true, trusted = true), s))
        // The car went away: everything stops, even with "Stop when the headset disconnects" off.
        assertEquals(Hold(HoldWhy.DEVICE_GONE, Keep.SCREEN), OutputGuard.decide(u(false, SpeechSource.ANSWER), inCar(routed = speaker), s.copy(stopOnDisconnect = false)))
        // A trust answer about the car never makes it private: the guard only knows the session is a car.
        assertEquals(Ask(AskWhat.READ_ALOUD), OutputGuard.decide(u(true, SpeechSource.ROUTINE), inCar().copy(trust = Trust.PRIVATE), s))
    }

    @Test fun phoneRows() {
        // No headset involved: today's behaviour (the lock filter still applies).
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.ANSWER), phone(), s))
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.ROUTINE), phone(), s))
        assertEquals(Hold(HoldWhy.LOCKED, Keep.UNLOCK, sayUnlock = true), OutputGuard.decide(u(true, SpeechSource.ROUTINE), phone(locked = true), s))
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(false, SpeechSource.ROUTINE), phone(locked = true), s))
        // Messages are never announced on the phone alone.
        assertEquals(Hold(HoldWhy.NO_ANNOUNCE, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), phone(), s))
        assertEquals(Hold(HoldWhy.NO_ANNOUNCE, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), phone(routed = buds, trust = Trust.PRIVATE), s))
    }

    @Test fun messagesInTheHeadset() {
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.MESSAGE), headset(Trust.PRIVATE), s))
        assertEquals(Hold(HoldWhy.DND, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE, dnd = false), headset(Trust.PRIVATE), s))
        assertEquals(Hold(HoldWhy.LOCKED, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), headset(Trust.PRIVATE, locked = true), s))
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(true, SpeechSource.MESSAGE), headset(Trust.PRIVATE, locked = true), s.copy(announceLocked = true)))
        assertEquals(Hold(HoldWhy.NOT_PRIVATE, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), headset(Trust.NOT_PRIVATE), s))
        assertEquals(Ask(AskWhat.PRIVATE_DEVICE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), headset(Trust.ASK), s))
        // Over music: an announcement ducks it.
        assertEquals(Speak(VolumePolicy.DUCK_OTHERS), OutputGuard.decide(u(true, SpeechSource.MESSAGE, chars = 300), headset(Trust.PRIVATE, media = true), s))
    }

    @Test fun callsAndMusic() {
        // Nothing that came by itself talks over a call; an answer you asked for may, without the call route.
        assertEquals(Hold(HoldWhy.CALL, Keep.SCREEN), OutputGuard.decide(u(false, SpeechSource.ROUTINE), headset(Trust.PRIVATE, call = true), s))
        assertEquals(Hold(HoldWhy.CALL, Keep.UNLOCK), OutputGuard.decide(u(false, SpeechSource.ROUTINE), phone(locked = true, call = true), s))
        assertEquals(Hold(HoldWhy.CALL, Keep.NONE), OutputGuard.decide(u(true, SpeechSource.MESSAGE), headset(Trust.PRIVATE, call = true), s))
        // In the car, as anywhere: on the screen (unlocked), never kept for parking.
        assertEquals(Hold(HoldWhy.CALL, Keep.SCREEN), OutputGuard.decide(u(false, SpeechSource.ROUTINE), inCar().copy(phone = PhoneState(false, callActive = true)), s))
        assertEquals(Speak(VolumePolicy.NORMAL), OutputGuard.decide(u(false, SpeechSource.ANSWER), headset(Trust.PRIVATE, call = true, comm = true), s))
        // Music: short lines duck, long answers pause it.
        assertEquals(Speak(VolumePolicy.DUCK_OTHERS), OutputGuard.decide(u(false, SpeechSource.ANSWER, chars = 30), phone(media = true), s))
        assertEquals(Speak(VolumePolicy.PAUSE_OTHERS), OutputGuard.decide(u(false, SpeechSource.ANSWER, chars = 200), phone(media = true), s))
        assertEquals(Speak(VolumePolicy.DUCK_OTHERS), OutputGuard.decide(u(false, SpeechSource.ROBOT, chars = 200), phone(media = true), s))
        assertEquals(Speak(VolumePolicy.PAUSE_OTHERS), OutputGuard.decide(u(false, SpeechSource.ANSWER, chars = 0), phone(media = true), s))
    }

    /** Every combination: the rules that must never break, whatever the row. */
    @Test fun invariantsOverEveryCombination() {
        var n = 0
        for (session in SessionKind.entries) for (src in SpeechSource.entries) for (personal in listOf(true, false)) for (locked in listOf(true, false))
        for (trust in Trust.entries) for (gone in listOf(true, false)) for (call in listOf(true, false)) for (media in listOf(true, false))
        for (trusted in listOf(true, false)) for (lockAll in listOf(true, false)) for (dnd in listOf(true, false))
        for (only in listOf(true, false)) for (stop in listOf(true, false)) for (announceLocked in listOf(true, false)) for (readAloud in ReadAloud.entries) {
            val dev = when (session) { SessionKind.CAR -> car; SessionKind.HEADSET -> buds; SessionKind.PHONE -> null }
            val routed = if (gone || dev == null) speaker else dev
            val ctx = OutputContext(session, routed, trust, PhoneState(locked, callActive = call, mediaPlaying = media), dev, commRoute = session != SessionKind.PHONE,
                carTrusted = trusted, lockAllowsPersonal = lockAll)
            val gs = GuardSettings(only, stop, announceLocked, readAloud)
            val utt = u(personal, src, 100, dnd)
            val d = OutputGuard.decide(utt, ctx, gs)
            val why = "$session $src personal=$personal locked=$locked $trust gone=$gone call=$call trusted=$trusted lockAll=$lockAll dnd=$dnd only=$only stop=$stop $readAloud -> $d"
            n++
            if (d is Speak) {
                // Personal never plays on a headset that isn't private while the switch is on.
                if (session == SessionKind.HEADSET && personal && only) assertEquals(why, Trust.PRIVATE, trust)
                // Nothing personal plays where the session's device is gone; nothing at all in the car or with the switch on.
                if (ctx.deviceGone) assertTrue(why, !personal && session == SessionKind.HEADSET && !stop && src != SpeechSource.MESSAGE)
                // Locked personal: only car functions in a trusted session, a routine at Everything, or an allowed announcement.
                if (locked && personal) assertTrue(why, (session == SessionKind.CAR && trusted) || lockAll || (src == SpeechSource.MESSAGE && announceLocked))
                // Messages: only in headphones or the car, as DND lets them through.
                if (src == SpeechSource.MESSAGE) assertTrue(why, session != SessionKind.PHONE && dnd)
                // Nothing that came by itself talks over a call.
                if (src.unattended) assertFalse(why, call)
                // In the car, personal content nobody asked for is read only with "always".
                if (session == SessionKind.CAR && personal && src.unattended) assertEquals(why, ReadAloud.ALWAYS, readAloud)
                // The call route only when the session holds it and no other call is on.
                if (d.volume == VolumePolicy.CALL) assertTrue(why, ctx.commRoute && !call && !ctx.deviceGone)
                // Shortened only in the car.
                if (d.shorten) assertEquals(why, SessionKind.CAR, session)
            }
            if (d is Hold) {
                if (d.keep == Keep.SCREEN) assertFalse(why, locked)
                if (src == SpeechSource.MESSAGE) assertEquals(why, Keep.NONE, d.keep)
                if (d.sayUnlock) assertEquals(why, HoldWhy.LOCKED, d.why)
            }
            if (d is Ask && d.what == AskWhat.PRIVATE_DEVICE) assertTrue(why, session == SessionKind.HEADSET && trust == Trust.ASK && personal)
            if (d is Ask && d.what == AskWhat.READ_ALOUD) assertTrue(why, session == SessionKind.CAR && readAloud == ReadAloud.ASK && src.unattended)
        }
        assertEquals(3 * 5 * 2 * 2 * 3 * 2 * 2 * 2 * 2 * 2 * 2 * 2 * 2 * 2 * 3, n)
    }

    @Test fun aRouteChangeWhilePlaying() {
        val personal = u(true, SpeechSource.ANSWER)
        val ctx = headset(Trust.PRIVATE)
        assertTrue(OutputGuard.keepPlaying(personal, ctx, s, RouteSignal.ROUTING_CHANGED))
        // Becoming noisy: the speaker takes over, so it stops.
        assertFalse(OutputGuard.keepPlaying(personal, ctx, s, RouteSignal.BECOMING_NOISY))
        assertFalse(OutputGuard.keepPlaying(u(false, SpeechSource.ANSWER), ctx, s, RouteSignal.BECOMING_NOISY))
        // The earbuds removed while the listener still reports them routed: stop.
        assertFalse(OutputGuard.keepPlaying(personal, ctx, s, RouteSignal.DEVICE_REMOVED, removed = budsSco))
        // Another device removed: goes on.
        assertTrue(OutputGuard.keepPlaying(personal, ctx, s, RouteSignal.DEVICE_REMOVED, removed = car))
        // Routed somewhere not private: stop.
        assertFalse(OutputGuard.keepPlaying(personal, ctx.copy(routed = speaker, trust = Trust.NOT_PRIVATE), s, RouteSignal.ROUTING_CHANGED))
        assertEquals(Keep.SCREEN, OutputGuard.afterStop(PhoneState(false)))
        assertEquals(Keep.UNLOCK, OutputGuard.afterStop(PhoneState(true)))
    }

    @Test fun pinningTheSessionDevice() {
        val other = SeenDevice(RouteType.BT_A2DP, "Other", "XX:XX:XX:XX:00:01")
        val devices = listOf(speaker, other, buds, budsSco, budsSco.copy(sink = false))
        assertEquals(budsSco, OutputGuard.pinTarget(buds, devices, commRoute = true))
        assertEquals(buds, OutputGuard.pinTarget(buds, devices, commRoute = false))
        assertEquals(buds, OutputGuard.pinTarget(buds, listOf(buds), commRoute = true))
        assertEquals(null, OutputGuard.pinTarget(buds, listOf(speaker, other), commRoute = false))
    }

    @Test fun contextReadsTrustFromMemory() {
        val memory = DeviceMemory().remember(buds, DeviceRole.HEADSET, 0, private = true)!!.first
        assertEquals(Trust.PRIVATE, OutputContext.of(SessionKind.HEADSET, budsSco, memory, PhoneState(false), buds).trust)
        assertEquals(Trust.NOT_PRIVATE, OutputContext.of(SessionKind.HEADSET, null, memory, PhoneState(false), buds).trust)
        assertEquals(Trust.ASK, OutputContext.of(SessionKind.HEADSET, car, DeviceMemory(), PhoneState(false), car).trust)
    }

    @Test fun lines() {
        assertEquals("Is “Pixel Buds Pro” headphones only you can hear? Say yes or no.", OutputLines.privateQuestion("Pixel Buds Pro", Lang.EN))
        assertEquals("Are these headphones only you can hear? Say yes or no.", OutputLines.privateQuestion("  ", Lang.EN))
        assertEquals("Est-ce que Pixel Buds Pro, ce sont des écouteurs que toi seul peux entendre? Dis oui ou non.",
            OutputLines.privateQuestion("Pixel Buds Pro", Lang.FR))
        assertEquals("Déverrouille pour l’entendre.", OutputLines.UNLOCK_TO_HEAR.get(Lang.FR))
        assertEquals("I'll keep that for when you can look at the phone.", OutputLines.KEEP_FOR_LOOK.get(Lang.EN))
    }
}
