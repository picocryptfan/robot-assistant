package app.heyrobot.core.output

import app.heyrobot.core.LogCode

// Pure. Where a sentence may be spoken (design §1.3 `OutputGuard`, §3.7 rules 1–2, §4.9 rule 3): checked before each
// sentence is queued (SherpaTextToSpeech.write) and again whenever the route changes while it plays. Personal sentences
// play only on a private device when a headset started the session, never on a car or a Bluetooth speaker you didn't
// confirm, and wait for an unlock on a locked phone unless car mode's trusted session (§4.6) or a routine's "Everything"
// lock level says otherwise. There is never a fallback to the phone's speaker for personal content.
//
// The decision table, in order (OutputGuardTest walks every row):
//
// | # | When | Result |
// |---|---|---|
// | 1 | another call is on, and the sentence came by itself (routine, message) | held (CALL) |
// | 2 | the session's headset or car is gone (not the routed device any more) | held (DEVICE_GONE); a public sentence in a headset session plays on only with "Stop when the headset disconnects" off |
// | 3 | a message: on the phone alone / Do Not Disturb doesn't let the sender through / locked (no trusted car session, "Announce while locked" off) | held (NO_ANNOUNCE / DND / LOCKED), nothing kept: the message stays in its app |
// | 4 | locked and personal, outside a trusted car session or a routine at "Everything" | held for the unlock, "Unlock to hear it." said instead |
// | 5 | headset session, personal, "Personal answers only in private headphones" on | private device: plays; never asked (Bluetooth): ask "Is … headphones only you can hear?"; not private: held |
// | 6 | car session, personal, and it came by itself (routine, message) | "Read message text aloud when others may hear": ask (default), always, or never (kept for parking) |
// | 7 | otherwise | plays: over the call route when the session holds it, else ducking short lines and pausing for long ones when other media plays; in the car a long answer is shortened |

/** Whether a sentence is personal (design §1.3): anything from contacts, People, calendar, notes, messages, a place, phone status, or a personal conversation. */
enum class SpeechClass { PUBLIC, PERSONAL }

/** Where a sentence comes from. */
enum class SpeechSource {
    /** A reply to your question: you are there and asked. */
    ANSWER,
    /** A spoken card or spoken choice (§1.4). */
    CARD,
    /** A routine that ran by itself. */
    ROUTINE,
    /** An incoming message's announcement or text (0.7). */
    MESSAGE,
    /** Robot's own short lines: "Car mode on", the private-headphones question, "I'll listen on the phone". */
    ROBOT;

    /** It came without you asking right then. */
    val unattended: Boolean get() = this == ROUTINE || this == MESSAGE
}

/**
 * One sentence to speak. [chars]: its length, for the car's short answers and the ducking of short lines. [dndAllows]: a
 * message whose notification Do Not Disturb lets through (Ranking.matchesInterruptionFilter); true for everything else.
 */
data class Utterance(val cls: SpeechClass, val source: SpeechSource, val chars: Int = 0, val dndAllows: Boolean = true) {
    val personal: Boolean get() = cls == SpeechClass.PERSONAL
}

/** Car mode's "Read message text aloud when others may hear" (§4.4), also used for calendar titles a routine reads in the car. */
enum class ReadAloud(val title: String) {
    ASK("Ask each time"), ALWAYS("Always"), NEVER("Never");
}

/**
 * The moment a sentence would play. [session]: who it's for. [routed]: where it would play now (null: nowhere).
 * [trust]: DeviceMemory.trust of [routed]. [sessionDevice]: the headset or car the session is for (null on the phone).
 * [commRoute]: the session holds the communication route (SCO or LE: call audio). [carTrusted]: car mode's trusted
 * session is on. [lockAllowsPersonal]: a routine whose lock level is "Everything" (RoutineGate said run).
 */
data class OutputContext(
    val session: SessionKind,
    val routed: SeenDevice?,
    val trust: Trust,
    val phone: PhoneState,
    val sessionDevice: SeenDevice? = null,
    val commRoute: Boolean = false,
    val carTrusted: Boolean = false,
    val lockAllowsPersonal: Boolean = false,
) {
    /** The headset or car this session was for is no longer where sound goes. */
    val deviceGone: Boolean get() = session != SessionKind.PHONE &&
        (sessionDevice == null || routed == null || !routed.samePhysical(sessionDevice))

    companion object {
        /** The context with [trust] read from [memory]. */
        fun of(session: SessionKind, routed: SeenDevice?, memory: DeviceMemory, phone: PhoneState, sessionDevice: SeenDevice? = null,
            commRoute: Boolean = false, carTrusted: Boolean = false, lockAllowsPersonal: Boolean = false): OutputContext =
            OutputContext(session, routed, routed?.let(memory::trust) ?: Trust.NOT_PRIVATE, phone, sessionDevice, commRoute, carTrusted, lockAllowsPersonal)
    }
}

/** The settings OutputGuard reads, from Safety & privacy, Settings › Headset and Settings › Car mode. */
data class GuardSettings(
    /** Safety & privacy › "Personal answers only in private headphones" (on). */
    val personalOnlyPrivate: Boolean = true,
    /** Headset › "Stop when the headset disconnects" (on). */
    val stopOnDisconnect: Boolean = true,
    /** Headset › "Announce while locked" (off); car mode uses its trusted session instead. */
    val announceLocked: Boolean = false,
    /** Car mode › "Read message text aloud when others may hear" (ask each time). */
    val readAloud: ReadAloud = ReadAloud.ASK,
)

/** How the sentence plays alongside other sound. */
enum class VolumePolicy {
    /** Nothing else plays: the assistant stream as today. */
    NORMAL,
    /** Short lines over music: the music lowers (transient focus, may duck). */
    DUCK_OTHERS,
    /** Longer answers: the music pauses and resumes after (transient focus). */
    PAUSE_OTHERS,
    /** The session holds the call route (SCO or LE Audio): call audio, at the call volume; a car mutes its radio. */
    CALL,
}

/** Where a held sentence waits. */
enum class Keep {
    /** In the panel now (unlocked). */
    SCREEN,
    /** Until you unlock: the panel says "Unlock to see the answer". */
    UNLOCK,
    /** Nowhere: a message stays in its app. */
    NONE,
}

/** Why a sentence didn't play. Also the voice log's OUTPUT_HELD a. */
enum class HoldWhy(override val id: Int, override val title: String) : LogCode {
    LOCKED(1, "locked"),
    NOT_PRIVATE(2, "not a private device"),
    DEVICE_GONE(3, "the headset or car went away"),
    CALL(4, "a call is on"),
    DND(5, "Do Not Disturb"),
    NO_ANNOUNCE(6, "messages are announced only in headphones or the car"),
}

/** What Robot asks before a held sentence plays. */
enum class AskWhat {
    /** "Is “Pixel Buds Pro” headphones only you can hear?": the answer is kept in DeviceMemory. */
    PRIVATE_DEVICE,
    /** "Others may hear it. Read it aloud?" (the car is never private). */
    READ_ALOUD,
}

sealed class OutputDecision {
    data class Speak(val volume: VolumePolicy, val shorten: Boolean = false) : OutputDecision()

    /** Hold the sentence and ask first; a yes plays it. */
    data class Ask(val what: AskWhat) : OutputDecision()

    /** Not now: [keep] says where it waits; [sayUnlock]: say "Unlock to hear it." instead. */
    data class Hold(val why: HoldWhy, val keep: Keep, val sayUnlock: Boolean = false) : OutputDecision()
}

/** What changed the route while a sentence played (design §1.3 rule 3). */
enum class RouteSignal {
    /** AudioTrack.OnRoutingChangedListener. */
    ROUTING_CHANGED,
    /** AudioManager.ACTION_AUDIO_BECOMING_NOISY: sound is about to move to the phone's speaker. */
    BECOMING_NOISY,
    /** AudioDeviceCallback.onAudioDevicesRemoved. */
    DEVICE_REMOVED,
}

object OutputGuard {
    /** A line this short ducks other sound instead of pausing it. */
    const val SHORT_CHARS = 60
    /** In the car, a spoken answer longer than this is shortened ("Want more?"; car CarSpeech). */
    const val DRIVING_MAX_CHARS = 180

    /** The phone's speaker, as a device: where sound goes once a headset is gone (ACTION_AUDIO_BECOMING_NOISY). */
    val PHONE_SPEAKER = SeenDevice(RouteType.SPEAKER, "", "")

    fun decide(u: Utterance, ctx: OutputContext, s: GuardSettings): OutputDecision {
        val phone = ctx.phone
        // 1. Nothing that came by itself talks over a call (CallAudio: never during another app's call).
        if (phone.callActive && u.source.unattended) return OutputDecision.Hold(HoldWhy.CALL, if (u.source == SpeechSource.MESSAGE) Keep.NONE else screenOrUnlock(phone))
        // 2. The headset or car went away: stop. A public line may go on where sound goes now only with "Stop when the
        //    headset disconnects" off (never in the car: you left it).
        if (ctx.deviceGone) {
            val stop = ctx.session == SessionKind.CAR || s.stopOnDisconnect || u.personal || u.source == SpeechSource.MESSAGE
            if (stop) return OutputDecision.Hold(HoldWhy.DEVICE_GONE, if (u.source == SpeechSource.MESSAGE) Keep.NONE else screenOrUnlock(phone))
            return OutputDecision.Speak(volume(u, ctx.copy(commRoute = false)))
        }
        // 3. Messages: only in headphones or the car, as Do Not Disturb allows, and off the lock screen unless allowed.
        if (u.source == SpeechSource.MESSAGE) {
            if (ctx.session == SessionKind.PHONE) return OutputDecision.Hold(HoldWhy.NO_ANNOUNCE, Keep.NONE)
            if (!u.dndAllows) return OutputDecision.Hold(HoldWhy.DND, Keep.NONE)
            if (phone.locked && !(ctx.session == SessionKind.CAR && ctx.carTrusted) && !s.announceLocked) return OutputDecision.Hold(HoldWhy.LOCKED, Keep.NONE)
        }
        // 4. Locked: personal sentences wait for the unlock (a thief with the phone and their own earbuds, §2.6).
        if (phone.locked && u.personal && !lockedOk(u, ctx, s)) return OutputDecision.Hold(HoldWhy.LOCKED, Keep.UNLOCK, sayUnlock = true)
        // 5 and 6. Where it plays.
        when (ctx.session) {
            SessionKind.HEADSET -> if (u.personal && s.personalOnlyPrivate) when (ctx.trust) {
                Trust.PRIVATE -> Unit
                Trust.ASK -> return OutputDecision.Ask(AskWhat.PRIVATE_DEVICE)
                Trust.NOT_PRIVATE -> return OutputDecision.Hold(HoldWhy.NOT_PRIVATE, if (u.source == SpeechSource.MESSAGE) Keep.NONE else screenOrUnlock(phone))
            }
            SessionKind.CAR -> if (u.personal && u.source.unattended) when (s.readAloud) {
                ReadAloud.ALWAYS -> Unit
                ReadAloud.ASK -> return OutputDecision.Ask(AskWhat.READ_ALOUD)
                ReadAloud.NEVER -> return OutputDecision.Hold(HoldWhy.NOT_PRIVATE, if (u.source == SpeechSource.MESSAGE) Keep.NONE else screenOrUnlock(phone))
            }
            SessionKind.PHONE -> Unit
        }
        // 7. It plays.
        val shorten = ctx.session == SessionKind.CAR && (u.source == SpeechSource.ANSWER || u.source == SpeechSource.ROUTINE) && u.chars > DRIVING_MAX_CHARS
        return OutputDecision.Speak(volume(u, ctx), shorten)
    }

    /** A personal sentence may play while locked: car functions in a trusted session, a routine at "Everything", an allowed announcement. */
    private fun lockedOk(u: Utterance, ctx: OutputContext, s: GuardSettings): Boolean =
        (ctx.session == SessionKind.CAR && ctx.carTrusted) || ctx.lockAllowsPersonal || (u.source == SpeechSource.MESSAGE && s.announceLocked)

    private fun screenOrUnlock(phone: PhoneState): Keep = if (phone.locked) Keep.UNLOCK else Keep.SCREEN

    fun volume(u: Utterance, ctx: OutputContext): VolumePolicy = when {
        ctx.commRoute && !ctx.phone.callActive -> VolumePolicy.CALL
        !ctx.phone.mediaPlaying -> VolumePolicy.NORMAL
        u.source == SpeechSource.MESSAGE || u.source == SpeechSource.ROBOT || u.chars in 1..SHORT_CHARS -> VolumePolicy.DUCK_OTHERS
        else -> VolumePolicy.PAUSE_OTHERS
    }

    /**
     * The route changed while [u] played ([signal]; [now] is the context after it, [removed] the device that went when
     * known): true to keep playing, false to stop at once (pause and flush), keeping the rest per [afterStop]. Becoming
     * noisy means the speaker takes over.
     */
    fun keepPlaying(u: Utterance, now: OutputContext, s: GuardSettings, signal: RouteSignal, removed: SeenDevice? = null): Boolean {
        val ctx = when {
            signal == RouteSignal.BECOMING_NOISY -> now.copy(routed = PHONE_SPEAKER, trust = Trust.NOT_PRIVATE, commRoute = false)
            signal == RouteSignal.DEVICE_REMOVED && removed != null && now.routed != null && removed.samePhysical(now.routed) ->
                now.copy(routed = PHONE_SPEAKER, trust = Trust.NOT_PRIVATE, commRoute = false)
            else -> now
        }
        return decide(u, ctx, s) is OutputDecision.Speak
    }

    /** Where the rest of a stopped answer goes: on screen when unlocked, else "Unlock to see the answer". */
    fun afterStop(phone: PhoneState): Keep = screenOrUnlock(phone)

    /**
     * The output to pin the session's track to (AudioTrack.setPreferredDevice, §1.3 rule 4), so a second Bluetooth
     * device connecting doesn't take it: the session device's own output entry, its call entry (SCO, LE) while the
     * session holds the call route, else its media entry (A2DP, LE, wired, USB). Null when it has none listed.
     */
    fun pinTarget(sessionDevice: SeenDevice, devices: List<SeenDevice>, commRoute: Boolean): SeenDevice? {
        val same = devices.filter { it.sink && it.samePhysical(sessionDevice) }
        val call = same.firstOrNull { it.type == RouteType.BT_SCO || it.type == RouteType.BLE_HEADSET }
        val media = same.firstOrNull { it.type != RouteType.BT_SCO }
        return if (commRoute) call ?: media else media ?: call
    }
}

/** The words OutputGuard's decisions say. */
object OutputLines {
    val UNLOCK_TO_HEAR = Line("Unlock to hear it.", "Déverrouille pour l'entendre.")
    val UNLOCK_TO_SEE = Line("Unlock to see the answer.", "Déverrouille pour voir la réponse.")
    val READ_ALOUD = Line("Others may hear it. Read it aloud?", "D'autres pourraient l'entendre. Je le lis à voix haute?")
    val KEEP_FOR_LOOK = Line("I'll keep that for when you can look at the phone.", "Je garde ça pour quand tu pourras regarder le téléphone.")

    /** "Is “Pixel Buds Pro” headphones only you can hear? Say yes or no." The name is the device's own (never logged). */
    fun privateQuestion(name: String, lang: SpokenLang): String {
        val n = DeviceMemory.clean(name)
        return when (lang) {
            SpokenLang.EN -> if (n.isEmpty()) "Are these headphones only you can hear? Say yes or no."
                else "Is “$n” headphones only you can hear? Say yes or no."
            SpokenLang.FR -> if (n.isEmpty()) "Est-ce que ce sont des écouteurs que toi seul peux entendre? Dis oui ou non."
                else "Est-ce que « $n », ce sont des écouteurs que toi seul peux entendre? Dis oui ou non."
        }
    }
}
