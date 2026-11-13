package app.heyrobot.core.messages

import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldVoiceTest {
    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
    private val open = PhoneState(locked = false)
    private val privateBuds = RoutineRoute(buds, Trust.PRIVATE)

    private fun says(phone: PhoneState = open, route: RoutineRoute = privateBuds, dnd: Boolean = true, someoneElse: Boolean = false) =
        HoldVoice.speaks(48, phone, route, dnd, someoneElse)

    @Test fun saidOnceInPrivateHeadphones() = assertTrue(says())

    @Test fun quietWhereverOutputGuardDoesNotSaySpeak() {
        assertFalse("phone speaker", says(route = RoutineRoute(null)))
        assertFalse("built-in speaker", says(route = RoutineRoute(SeenDevice(RouteType.SPEAKER))))
        assertFalse("headphones not known private", says(route = RoutineRoute(buds, Trust.NOT_PRIVATE)))
        assertFalse("headphones asked about", says(route = RoutineRoute(buds, Trust.ASK)))
        assertFalse("lock screen", says(phone = PhoneState(locked = true)))
        assertFalse("a call", says(phone = PhoneState(locked = false, callActive = true)))
        assertFalse("Do Not Disturb", says(dnd = false))
        assertFalse("someone else has the phone", says(someoneElse = true))
        assertFalse("car that asks first", says(route = RoutineRoute(car, Trust.NOT_PRIVATE, car, true, ReadAloud.ASK)))
        assertFalse("car that never reads", says(route = RoutineRoute(car, Trust.NOT_PRIVATE, car, true, ReadAloud.NEVER)))
        assertFalse("nothing to say", HoldVoice.speaks(0, open, privateBuds, true, false))
    }

    @Test fun theLineIsAlwaysShownAndSaidOnlyWhereItsPersonalSpeechMayPlay() {
        val line = HoldLine.over(listOf(HeldCount("Shop", 3)), false).render(app.heyrobot.core.i18n.Langs(app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.EN))
        assertTrue(line.isNotEmpty())
        assertTrue("private headphones", HoldVoice.speaks(line.length, open, privateBuds, true, false))
        assertFalse("the phone's speaker: shown only", HoldVoice.speaks(line.length, open, RoutineRoute(null), true, false))
        assertFalse("someone else: shown only, counts only", HoldVoice.speaks(HoldLine.over(listOf(HeldCount("Shop", 3)), true).render(app.heyrobot.core.i18n.Langs(app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.EN)).length, open, privateBuds, true, true))
    }

    @Test fun saidInATrustedCarThatReadsAloud() = assertTrue(says(route = RoutineRoute(car, Trust.NOT_PRIVATE, car, true, ReadAloud.ALWAYS)))
}
