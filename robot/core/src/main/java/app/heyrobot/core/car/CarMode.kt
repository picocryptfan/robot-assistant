package app.heyrobot.core.car

import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.AskWhy
import app.heyrobot.core.GateDecision
import app.heyrobot.core.LogCode
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.output.BtClass
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SpokenLang

// Pure. Car mode (docs/plans/routines-headset-car-design.md §4): Robot's own hands-free mode over the car's standard
// Bluetooth (HFP for the microphone and Robot's voice, A2DP optionally). It is not Android Auto, which GrapheneOS offers
// only through sandboxed Google Play and which shows only Play apps with Android Auto support (PUBLISHED, GrapheneOS
// usage guide). There is no activity recognition without Google Play services and coarse location gives no speed, so
// being connected to your car counts as driving (the conservative choice); an optional driving signal ([CarInput.Driving])
// can say you stopped.
//
// CarModeController (:voice) keeps a [CarState] and feeds it [CarInput]s: the remembered car's devices coming and going
// (AudioDeviceCallback, raw: the end grace is counted here), unlocks (USER_PRESENT), time, and what you say or tap. It
// carries out the [CarEffect]s: the speaking service, the car screen, Do Not Disturb, the trusted session.

/** Why car mode started or ended. Also the voice log's CAR_MODE b. */
enum class CarWhy(override val id: Int, override val title: String) : LogCode {
    CAR_CONNECTED(1, "your car connected"), ASKED(2, "you asked"), TAPPED(3, "you tapped Start"), CAR_GONE(4, "the car disconnected"),
    ENDED_BY_YOU(5, "you ended it"), TIMEOUT(6, "on for too long without a car"),
}

/** Why the trusted session started or ended. Also the voice log's CAR_TRUSTED b. */
enum class TrustEnd(override val id: Int, override val title: String) : LogCode {
    UNLOCKED_IN_CAR(1, "unlocked in the car"), ALWAYS(2, "set to always"), CAR_GONE(3, "the car disconnected"), EXPIRED(4, "time is up"),
    CAR_MODE_ENDED(5, "car mode ended"),
}

enum class CarPhase {
    OFF,
    /** Your car connected (or you asked) and car mode is starting: the speaking service was asked for. */
    CONNECTED,
    ACTIVE,
    /** The car went away; car mode ends after the grace unless it comes back. */
    ENDING,
}

/**
 * The trusted session (§4.6): after one unlock while your car is connected, car mode's own functions work on the lock
 * screen until the car disconnects (after the grace) or [until]. In memory only: a :voice restart ends it.
 */
data class TrustedSession(val car: Int, val since: Long, val until: Long)

/**
 * [car]: the remembered car (its number) car mode is for; null when you started it without one. [present]: remembered
 * enabled cars connected now. [declined]: you ended car mode while this car stayed connected, so it doesn't start again
 * until the car reconnects. [moving]: the optional driving signal (null: unknown, so driving).
 */
data class CarState(
    val phase: CarPhase = CarPhase.OFF,
    val car: Int? = null,
    val since: Long = 0,
    val endsAt: Long? = null,
    val trusted: TrustedSession? = null,
    val present: Set<Int> = emptySet(),
    val declined: Int? = null,
    val moving: Boolean? = null,
    val startedBy: CarWhy? = null,
) {
    val on: Boolean get() = phase == CarPhase.ACTIVE || phase == CarPhase.ENDING

    /**
     * Car functions may run on the lock screen now: while the car the session was unlocked in is connected. During the
     * grace after it goes ([CarPhase.ENDING]) the phone has left the car, so the session waits: the car coming back
     * within the grace brings it back, otherwise it ends with car mode.
     */
    fun trustedAt(now: Long): Boolean = phase == CarPhase.ACTIVE && trusted != null && now < trusted.until && trusted.car in present
}

sealed class CarInput {
    abstract val at: Long

    /** A remembered car's device appeared (any profile). */
    data class CarSeen(val car: RememberedDevice, override val at: Long) : CarInput()

    /** The last of a remembered car's devices went away. */
    data class CarGone(val car: RememberedDevice, override val at: Long) : CarInput()

    /** The speaking service started (car mode's notification shows). */
    data class Started(override val at: Long) : CarInput()

    /** Android refused to start the service from the background: a notification asks you to tap Start. */
    data class StartRefused(override val at: Long) : CarInput()

    /** "Car mode", "I'm driving", "mode auto", the notification's Start, the app's button. */
    data class Start(override val at: Long) : CarInput()

    /** "End car mode", "fin du mode auto", the car screen's End. */
    data class End(override val at: Long) : CarInput()

    /** USER_PRESENT. */
    data class Unlocked(override val at: Long) : CarInput()

    data class Tick(override val at: Long) : CarInput()

    /** An optional driving signal: [moving] false means stopped (the "When you park" list may be offered). */
    data class Driving(val moving: Boolean, override val at: Long) : CarInput()
}

sealed class CarEffect {
    /** Start SpeakingService (mediaPlayback) with the car notification and End; car mode speaks with the screen off. */
    data object StartService : CarEffect()
    data object StopService : CarEffect()
    /** "Car connected · Start car mode" (the background start was refused). */
    data class Notify(val line: Line) : CarEffect()
    data class Say(val line: Line) : CarEffect()
    /** CarModeActivity, if the screen is on. */
    data object ShowCarScreen : CarEffect()
    /** VoiceHub.carMode: the prompt line, the hidden panel features, spoken cards. */
    data class CarModeFlag(val on: Boolean) : CarEffect()
    /** The "Driving (Robot)" Do Not Disturb rule (setAutomaticZenRuleState). */
    data class SetDnd(val on: Boolean) : CarEffect()
    /** UiModeManager.enableCarMode / disableCarMode. */
    data class AndroidCarMode(val on: Boolean) : CarEffect()
    data class Trusted(val on: Boolean, val why: TrustEnd) : CarEffect()
    /** Car mode ended or you stopped: the "When you park" list's notification ("From your drive: 2 things"). */
    data object ParkNotice : CarEffect()
    /** Voice log CAR_MODE. */
    data class Logged(val on: Boolean, val why: CarWhy) : CarEffect()
    /** Call [CarModeMachine.on] with a Tick at [at]. */
    data class WakeAt(val at: Long) : CarEffect()
}

object CarModeMachine {
    /** Car mode started without a car ends after this long (no disconnect will end it). */
    const val MANUAL_MAX_MS = 4 * 3_600_000L

    val CAR_ON = Line("Car mode on.", "Mode auto activé.")
    val CAR_OFF = Line("Car mode off.", "Mode auto désactivé.")
    val START_NOTICE = Line("Car connected · Start car mode", "Auto connectée · Démarrer le mode auto")

    fun on(s: CarState, input: CarInput, settings: CarSettings): Pair<CarState, List<CarEffect>> {
        val st = when (input) {
            is CarInput.CarSeen -> if (input.car.role == DeviceRole.CAR && input.car.enabled) s.copy(present = s.present + input.car.number) else s
            is CarInput.CarGone -> s.copy(present = s.present - input.car.number, declined = s.declined?.takeIf { it != input.car.number })
            else -> s
        }
        return when (st.phase) {
            CarPhase.OFF -> off(st, input, settings)
            CarPhase.CONNECTED -> connected(st, input, settings)
            CarPhase.ACTIVE -> active(st, input, settings)
            CarPhase.ENDING -> ending(st, input, settings)
        }
    }

    private fun off(s: CarState, input: CarInput, settings: CarSettings): Pair<CarState, List<CarEffect>> = when (input) {
        is CarInput.CarSeen -> if (input.car.number in s.present && settings.startOnConnect && s.declined != input.car.number) {
            s.copy(phase = CarPhase.CONNECTED, car = input.car.number, since = input.at, endsAt = null, startedBy = CarWhy.CAR_CONNECTED) to listOf(CarEffect.StartService)
        } else s to emptyList()
        is CarInput.Start -> s.copy(phase = CarPhase.CONNECTED, car = s.present.minOrNull(), since = input.at, declined = null, startedBy = CarWhy.ASKED) to
            listOf(CarEffect.StartService)
        else -> s to emptyList()
    }

    private fun connected(s: CarState, input: CarInput, settings: CarSettings): Pair<CarState, List<CarEffect>> = when (input) {
        is CarInput.Started -> {
            val trust = if (settings.lockUse == CarLockUse.ALWAYS && s.car != null) TrustedSession(s.car, input.at, input.at + settings.trustedMs) else null
            val why = s.startedBy ?: CarWhy.ASKED
            s.copy(phase = CarPhase.ACTIVE, trusted = trust, since = input.at) to buildList {
                add(CarEffect.Logged(true, why))
                add(CarEffect.CarModeFlag(true))
                add(CarEffect.Say(CAR_ON))
                add(CarEffect.ShowCarScreen)
                if (settings.dndWhileDriving) add(CarEffect.SetDnd(true))
                if (settings.androidCarMode) add(CarEffect.AndroidCarMode(true))
                if (trust != null) { add(CarEffect.Trusted(true, TrustEnd.ALWAYS)); add(CarEffect.WakeAt(trust.until)) }
                if (s.car == null) add(CarEffect.WakeAt(input.at + MANUAL_MAX_MS))
            }
        }
        is CarInput.StartRefused -> s to listOf(CarEffect.Notify(START_NOTICE))
        is CarInput.Start -> s.copy(startedBy = CarWhy.TAPPED) to listOf(CarEffect.StartService)
        is CarInput.CarGone -> if (input.car.number == s.car) CarState(present = s.present) to listOf(CarEffect.StopService) else s to emptyList()
        is CarInput.End -> CarState(present = s.present, declined = s.car) to listOf(CarEffect.StopService)
        else -> s to emptyList()
    }

    private fun active(s: CarState, input: CarInput, settings: CarSettings): Pair<CarState, List<CarEffect>> = when (input) {
        is CarInput.CarGone -> if (input.car.number == s.car) {
            val at = input.at + settings.endGraceMs
            s.copy(phase = CarPhase.ENDING, endsAt = at) to listOf(CarEffect.WakeAt(at))
        } else s to emptyList()
        // Car mode for no car picks up the car that connects.
        is CarInput.CarSeen -> if (s.car == null && input.car.number in s.present) s.copy(car = input.car.number) to emptyList() else s to emptyList()
        is CarInput.Unlocked -> {
            val car = s.car
            if (settings.lockUse == CarLockUse.AFTER_ONE_UNLOCK && car != null && car in s.present && s.trusted == null) {
                val t = TrustedSession(car, input.at, input.at + settings.trustedMs)
                s.copy(trusted = t) to listOf(CarEffect.Trusted(true, TrustEnd.UNLOCKED_IN_CAR), CarEffect.WakeAt(t.until))
            } else s to emptyList()
        }
        is CarInput.Tick -> when {
            s.car == null && input.at - s.since >= MANUAL_MAX_MS -> end(s, CarWhy.TIMEOUT, settings)
            s.trusted != null && input.at >= s.trusted.until -> s.copy(trusted = null) to listOf(CarEffect.Trusted(false, TrustEnd.EXPIRED))
            else -> s to emptyList()
        }
        is CarInput.End -> end(s, CarWhy.ENDED_BY_YOU, settings)
        is CarInput.Driving -> s.copy(moving = input.moving) to if (!input.moving && s.moving != false) listOf(CarEffect.ParkNotice) else emptyList()
        else -> s to emptyList()
    }

    private fun ending(s: CarState, input: CarInput, settings: CarSettings): Pair<CarState, List<CarEffect>> = when (input) {
        is CarInput.CarSeen -> if (input.car.number == s.car) s.copy(phase = CarPhase.ACTIVE, endsAt = null) to emptyList() else s to emptyList()
        is CarInput.Tick -> if (s.endsAt != null && input.at >= s.endsAt) {
            val (off, fx) = end(s, CarWhy.CAR_GONE, settings)
            // Another of your cars is connected: car mode for that one (without the trust: unlock in it).
            val next = off.present.minOrNull()
            if (next != null && settings.startOnConnect) {
                off.copy(phase = CarPhase.CONNECTED, car = next, since = input.at, startedBy = CarWhy.CAR_CONNECTED) to fx + CarEffect.StartService
            } else off to fx
        } else if (s.trusted != null && input.at >= s.trusted.until) s.copy(trusted = null) to listOf(CarEffect.Trusted(false, TrustEnd.EXPIRED)) else s to emptyList()
        is CarInput.End -> end(s, CarWhy.ENDED_BY_YOU, settings)
        else -> s to emptyList()
    }

    private fun end(s: CarState, why: CarWhy, settings: CarSettings): Pair<CarState, List<CarEffect>> {
        val fx = buildList {
            if (s.trusted != null) add(CarEffect.Trusted(false, if (why == CarWhy.CAR_GONE) TrustEnd.CAR_GONE else TrustEnd.CAR_MODE_ENDED))
            add(CarEffect.CarModeFlag(false))
            if (settings.dndWhileDriving) add(CarEffect.SetDnd(false))
            if (settings.androidCarMode) add(CarEffect.AndroidCarMode(false))
            add(CarEffect.StopService)
            if (why == CarWhy.ENDED_BY_YOU) add(CarEffect.Say(CAR_OFF))
            add(CarEffect.ParkNotice)
            add(CarEffect.Logged(false, why))
        }
        val declined = if (why == CarWhy.ENDED_BY_YOU && s.car != null && s.car in s.present) s.car else null
        return CarState(present = s.present, declined = declined) to fx
    }
}

/** Which device is your car (§4.3 "Add your car", §4.1). */
sealed class CarSeen {
    data class Car(val device: RememberedDevice) : CarSeen()

    /** Not remembered, but Bluetooth says car audio or hands-free (with BLUETOOTH_CONNECT): Settings offers it first. */
    data class Suggest(val seen: SeenDevice) : CarSeen()

    data object NotCar : CarSeen()
}

object CarDetect {
    fun of(seen: SeenDevice, memory: DeviceMemory): CarSeen {
        memory.find(seen, DeviceRole.CAR)?.let { return CarSeen.Car(it) }
        if (seen.type.bluetooth && seen.btClass.carLike && memory.find(seen) == null) return CarSeen.Suggest(seen)
        return CarSeen.NotCar
    }

    /** Settings › Car mode › Add your car, while connected: "Robot sees “MyCar” (…EE:FF). Is this your car?" */
    fun question(seen: SeenDevice, lang: SpokenLang): String {
        val name = DeviceMemory.clean(seen.name).ifEmpty { if (lang == SpokenLang.FR) "un appareil" else "a device" }
        val tail = seen.tail?.let { " (…$it)" } ?: ""
        return if (lang == SpokenLang.FR) "Robot voit « $name »$tail. Est-ce ton auto?" else "Robot sees “$name”$tail. Is this your car?"
    }

    /** Bluetooth says it is surely a car (not just hands-free). */
    fun surelyCar(seen: SeenDevice): Boolean = seen.btClass == BtClass.CAR_AUDIO
}

/** Car mode's own functions: what a trusted session allows on the lock screen (§4.6). */
enum class CarFunction(val tools: Set<ToolName>) {
    /** Calls to People and contacts (the call flow, and the call tool). */
    CALL(setOf(ToolName.CALL)),
    /** Messages (0.7: announce, read, reply). */
    MESSAGES(emptySet()),
    /** "What's next": today's next event only. */
    WHATS_NEXT(setOf(ToolName.READ_CALENDAR)),
    /** "Navigate home", "take me to work": saved places only. */
    NAVIGATE_SAVED(setOf(ToolName.MAPS_NAVIGATE)),
    /** The drive brief. */
    BRIEF(setOf(ToolName.READ_CALENDAR, ToolName.WEATHER)),
}

/**
 * ApprovalGate for car mode (an adapter: ApprovalGate itself is unchanged). A car function in a trusted session is
 * decided as if the phone were unlocked; everything else keeps the normal lock rules (§4.6 "Not loosened": asking a helper,
 * notes, email, deleting, opening apps, anything outside car mode). Every other rule (levels, cards, content) applies as
 * always.
 */
object CarGate {
    val UNLOCK_ONCE = Line("Unlock once to use car features.", "Déverrouille une fois pour utiliser les fonctions de l'auto.")
    val INDICATOR = Line("Car mode · unlocked features", "Mode auto · fonctions déverrouillées")

    fun decide(tool: ToolName, function: CarFunction?, locked: Boolean, trusted: Boolean, settings: RobotSettings, why: Set<AskWhy> = emptySet(),
        content: Boolean = false, asked: Boolean = false, remote: Boolean = false): GateDecision {
        val loosened = locked && trusted && function != null && tool in function.tools && !remote
        return ApprovalGate.decide(tool, if (loosened) false else locked, settings, why, content, asked, remote)
    }

    /** A car function may run now: unlocked, or locked in a trusted session. */
    fun allowed(function: CarFunction, locked: Boolean, trusted: Boolean): Boolean = !locked || trusted
}
