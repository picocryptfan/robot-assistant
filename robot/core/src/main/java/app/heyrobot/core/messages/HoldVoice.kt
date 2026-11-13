package app.heyrobot.core.messages

import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.SpeechClass
import app.heyrobot.core.output.SpeechSource
import app.heyrobot.core.output.Trust
import app.heyrobot.core.output.Utterance

/**
 * Whether a hold's end line ("Your hold is over. 1 notification came in: 1 in …") is also said, once, as well as shown.
 * It names apps, so it is personal and goes through OutputGuard as an announcement does: only where OutputGuard says it
 * plays now (private headphones, a trusted car that reads aloud), never on the phone's speaker, during a call, on the
 * lock screen, under Do Not Disturb, or while someone else has the phone. Anything but a plain "speak" is quiet.
 */
object HoldVoice {
    fun speaks(chars: Int, phone: PhoneState, route: RoutineRoute, dndAllows: Boolean, someoneElse: Boolean): Boolean {
        if (someoneElse || chars <= 0) return false
        val u = Utterance(SpeechClass.PERSONAL, SpeechSource.MESSAGE, chars, dndAllows)
        val ctx = when {
            route.car != null -> OutputContext(SessionKind.CAR, route.car, Trust.NOT_PRIVATE, phone, route.car, carTrusted = route.carTrusted)
            route.onPhone -> OutputContext(SessionKind.PHONE, null, Trust.NOT_PRIVATE, phone)
            else -> OutputContext(SessionKind.HEADSET, route.routed, route.trust, phone, route.routed)
        }
        return OutputGuard.decide(u, ctx, GuardSettings(readAloud = route.readAloud)) is OutputDecision.Speak
    }
}
