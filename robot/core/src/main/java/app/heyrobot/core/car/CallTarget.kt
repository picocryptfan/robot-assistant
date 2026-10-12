package app.heyrobot.core.car

import app.heyrobot.core.AskWhy
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.LogCode
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.OutputLines
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.SpokenAnswer
import app.heyrobot.core.output.SpokenCard
import app.heyrobot.core.output.SpokenCards
import app.heyrobot.core.output.SpokenChoice
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.SpokenOption
import app.heyrobot.core.output.SpokenStep
import java.util.Locale

// Pure. "Call Mom" hands-free (design §4.5 flow 2, §4.9 rules 1, 2 and 6). Who: the same who-to resolution as send_text
// (People and contacts, numbers only), done by the caller, which passes the candidates here. Robot has no call tool yet
// (ToolName has none); this is what the new `call` tool (group PEOPLE, schema call(to)) and ToolExecutor.call() will run.
// - VERIFIED (Intent javadoc): ACTION_CALL can't call emergency numbers and needs CALL_PHONE (which the assistant role
//   doesn't grant); ACTION_DIAL needs nothing and shows the number for you to tap Call. So: the dialer by default; a
//   direct call only after a spoken yes, with the Phone permission and both switches on; emergency numbers always the
//   dialer, with a sentence saying so.
// - No call is placed without a spoken yes after a read-back of who and which number ("Call Martine Martin, mobile,
//   ending 0101?"). Several matches: up to three spoken choices, then the read-back. A name learned from Cheogram, or more
//   than three: kept for parking. A Cheogram-only person has an XMPP address, which tel: can't call.
// - Locked: only in a trusted car session ("Unlock to call").

/** How a call was placed. Also the voice log's CAR_CALL a. */
enum class CallWay(override val id: Int, override val title: String) : LogCode {
    DIAL(1, "the dialer (you tap Call)"), CALL(2, "called after a spoken yes"),
}

/** A number's kind, as read and as a choice's words. */
enum class PhoneLabel(val en: String, val fr: String, val keys: Set<String>) {
    MOBILE("mobile", "cellulaire", setOf("mobile", "cell", "cellulaire")),
    WORK("work", "travail", setOf("work", "travail", "bureau")),
    HOME("home", "maison", setOf("home", "maison")),
    OTHER("other", "autre", emptySet());

    fun word(lang: SpokenLang): String = if (lang == SpokenLang.FR) fr else en
}

/**
 * One number someone can be called on. [learned]: the name came from a Cheogram notification (whoever sent it chose it).
 * [xmpp]: a Cheogram-only address, not a phone number. [toString] leaves the name and number out.
 */
data class PhoneCandidate(val name: String, val number: String, val label: PhoneLabel? = null, val learned: Boolean = false, val xmpp: Boolean = false) {
    val digits: String get() = PhoneNumbers.digits(number)

    /**
     * 1.0 audit (physical-call-e164-plus-dropped): what is dialled, with the E.164 "+" kept ("+33612345678"); [digits] alone
     * would call 33612345678, a different or invalid number. [digits] stays for the read-back's last four.
     */
    val dialable: String get() = CallTarget.dialable(number)

    override fun toString(): String = "PhoneCandidate(label=$label, learned=$learned, xmpp=$xmpp)"
}

/** What the call setting and permission allow. */
data class CallRules(val setting: CallSetting = CallSetting.OPEN_DIALER, val hasCallPermission: Boolean = false, val callAfterSpokenYes: Boolean = true) {
    /** A direct call (ACTION_CALL) after the spoken yes, or the dialer. */
    val way: CallWay get() = if (setting == CallSetting.CALL_AFTER_YES && hasCallPermission && callAfterSpokenYes) CallWay.CALL else CallWay.DIAL

    /**
     * What a session's spoken call may do: "Call directly after a spoken yes" is car mode's (Settings › Car mode › Calls);
     * a headset session reads the call back too, then opens the dialer for you to tap Call.
     */
    fun forSession(car: Boolean): CallRules = if (car) this else copy(setting = CallSetting.OPEN_DIALER)
}

enum class CallRefuse { NOT_FOUND, XMPP_ONLY, LOCKED }
enum class CallHold { LEARNED, TOO_MANY }

sealed class CallPlan {
    /** Read back [target] and wait for a yes; then call it [way]. */
    data class Confirm(val target: PhoneCandidate, val card: SpokenCard, val way: CallWay) : CallPlan()

    /** Up to three choices of who, spoken; a choice is then read back ([CallTarget.chosen]). */
    data class Choose(val options: List<PhoneCandidate>, val card: SpokenCard, val way: CallWay) : CallPlan()

    /** An emergency number: the dialer with it (never ACTION_CALL), and [say]. */
    data class Emergency(val number: String, val say: String) : CallPlan()

    /** Kept for parking ([say]). */
    data class Hold(val say: String, val why: CallHold) : CallPlan()

    data class Refuse(val say: String, val why: CallRefuse) : CallPlan()
}

/** After an answer to a call card. */
sealed class CallNext {
    /** Place it: ACTION_CALL for [CallWay.CALL], ACTION_DIAL for [CallWay.DIAL] (you tap Call). */
    data class Place(val number: String, val way: CallWay) : CallNext()
    data class Ask(val plan: CallPlan.Confirm) : CallNext()
    data class Cancelled(val say: String) : CallNext()
    data object Reread : CallNext()
    data class AskAgain(val say: String) : CallNext()
    data class Held(val say: String) : CallNext()
}

object CallTarget {
    /** What confirms a call besides yes (plain words: no accents or apostrophes, as SpokenChoice reads them). */
    val CALL_WORDS = listOf("call", "call her", "call him", "call them", "call it", "dial", "dial it", "place the call", "appelle", "appelle la", "appelle le",
        "appelle les", "compose", "lance lappel", "fais lappel")

    val UNLOCK_TO_CALL = Line("Unlock to call.", "Déverrouille pour appeler.")
    val NO_XMPP = Line("Robot can't call that address.", "Robot ne peut pas appeler cette adresse.")
    val NO_CALL = Line("Okay, no call.", "D'accord, pas d'appel.")
    val TOO_MANY = Line("Several people match. I'll keep that for when you park.", "Plusieurs personnes correspondent. Je garde ça pour quand tu seras stationné.")

    fun notFound(name: String, lang: SpokenLang): String =
        if (lang == SpokenLang.FR) "Je n'ai pas de numéro pour $name." else "I don't have a number for $name."

    /** "I can't place emergency calls. The dialer is open with 911; tap Call." */
    fun emergency(number: String, lang: SpokenLang): String = if (lang == SpokenLang.FR)
        "Je ne peux pas faire d'appels d'urgence. Le composeur est ouvert avec le $number; appuie sur Appeler."
    else "I can't place emergency calls. The dialer is open with $number; tap Call."

    /**
     * The plan for "call [asked]" with [candidates] found for it (People and contacts; empty when [asked] is a number).
     * [isEmergency]: PhoneNumberUtils.isEmergencyNumber in the app, [EmergencyNumbers.basic] where that isn't available.
     */
    fun plan(asked: String, candidates: List<PhoneCandidate>, rules: CallRules, locked: Boolean, trusted: Boolean, lang: SpokenLang,
        isEmergency: (String) -> Boolean): CallPlan {
        val spoken = dialable(asked).takeIf { PhoneNumbers.digits(it).length >= 2 && asked.none { c -> c.isLetter() } }
        val all = if (spoken != null) listOf(PhoneCandidate(spoken, spoken)) else candidates
        // Emergency first, locked or not: the dialer with the number, never a direct call.
        all.firstOrNull { !it.xmpp && it.digits.isNotEmpty() && isEmergency(it.digits) }?.let { return CallPlan.Emergency(it.digits, emergency(it.digits, lang)) }
        if (locked && !trusted) return CallPlan.Refuse(UNLOCK_TO_CALL.get(lang), CallRefuse.LOCKED)
        // 1.0 audit (physical-car-trusted-any-number): the trusted car session calls People and contacts only; a number
        // said aloud (a premium-rate one, abroad) needs the phone unlocked.
        if (locked && spoken != null) return CallPlan.Refuse(UNLOCK_TO_CALL.get(lang), CallRefuse.LOCKED)
        val phones = all.filter { !it.xmpp && it.digits.length >= 3 }
        if (phones.isEmpty()) {
            return if (all.any { it.xmpp }) CallPlan.Refuse(NO_XMPP.get(lang), CallRefuse.XMPP_ONLY)
            else CallPlan.Refuse(notFound(asked.trim(), lang), CallRefuse.NOT_FOUND)
        }
        if (phones.any { it.learned }) return CallPlan.Hold(OutputLines.KEEP_FOR_PARK.get(lang), CallHold.LEARNED)
        if (phones.size > 3) return CallPlan.Hold(TOO_MANY.get(lang), CallHold.TOO_MANY)
        if (phones.size == 1) return confirm(phones[0], rules.way, lang)
        val labels = phones.map { label(it, lang) }
        val options = phones.mapIndexed { i, p ->
            // Two choices that read the same say their last digits.
            val l = if (labels.count { it == labels[i] } > 1) "${labels[i]}, ${ending(p, lang)}" else labels[i]
            SpokenOption(l, p.label?.keys ?: emptySet())
        }
        val action = if (lang == SpokenLang.FR) "Appeler ${asked.trim()}" else "Call ${asked.trim()}"
        val card = SpokenCard(action, options, CardAnswer.Waiting(options.size, AskWhy.WHO), spokenWho = true, confirmWords = CALL_WORDS, personal = true, carFunction = true)
        return CallPlan.Choose(phones, card, rules.way)
    }

    /** The read-back card for [target]. */
    fun confirm(target: PhoneCandidate, way: CallWay, lang: SpokenLang): CallPlan.Confirm =
        CallPlan.Confirm(target, SpokenCard(readBack(target, lang), confirmWords = CALL_WORDS, personal = true, carFunction = true), way)

    /** "Call Martine Martin, mobile, ending 0101" / "Appeler Martine Martin, cellulaire, qui finit par 0101". */
    fun readBack(c: PhoneCandidate, lang: SpokenLang): String {
        val label = c.label?.takeIf { it != PhoneLabel.OTHER }?.let { ", " + it.word(lang) } ?: ""
        val who = c.name.trim().ifEmpty { c.digits }
        return if (lang == SpokenLang.FR) "Appeler $who$label, ${ending(c, lang)}" else "Call $who$label, ${ending(c, lang)}"
    }

    private fun label(c: PhoneCandidate, lang: SpokenLang): String =
        c.name.trim() + (c.label?.takeIf { it != PhoneLabel.OTHER }?.let { ", " + it.word(lang) } ?: "")

    private fun ending(c: PhoneCandidate, lang: SpokenLang): String {
        val last = c.digits.takeLast(4)
        return if (lang == SpokenLang.FR) "qui finit par $last" else "ending $last"
    }

    /** [a], the answer to [plan] (Confirm or Choose), after [tries] unusable answers and [rereads] re-reads. */
    fun answer(plan: CallPlan, a: SpokenAnswer, tries: Int, rereads: Int, lang: SpokenLang): CallNext {
        val choices = (plan as? CallPlan.Choose)?.options?.size ?: 0
        return when (val step = SpokenChoice.next(a, tries, rereads, choices, SessionKind.CAR, lang)) {
            SpokenStep.Confirm -> when (plan) {
                is CallPlan.Confirm -> CallNext.Place(plan.target.dialable, plan.way)
                else -> CallNext.AskAgain(SpokenCards.hint(choices, lang))
            }
            is SpokenStep.Choose -> when (plan) {
                is CallPlan.Choose -> plan.options.getOrNull(step.index)?.let { CallNext.Ask(confirm(it, plan.way, lang)) }
                    ?: CallNext.AskAgain(SpokenCards.hint(choices, lang))
                else -> CallNext.AskAgain(SpokenCards.hint(0, lang))
            }
            SpokenStep.Decline -> CallNext.Cancelled(NO_CALL.get(lang))
            SpokenStep.Reread -> CallNext.Reread
            is SpokenStep.AskAgain -> CallNext.AskAgain(step.say)
            is SpokenStep.Hold -> CallNext.Held(step.say)
        }
    }

    /** A tel: link's number part (digits, with a leading + kept). */
    fun dialable(number: String): String {
        val t = number.trim()
        return (if (t.startsWith("+")) "+" else "") + PhoneNumbers.digits(t)
    }
}

/**
 * A fallback list of emergency numbers by region, for where PhoneNumberUtils.isEmergencyNumber can't be asked (tests, a
 * seam). The platform's answer is the one that counts; this only errs towards the dialer (a number listed here is never
 * called directly).
 */
object EmergencyNumbers {
    private val EVERYWHERE = setOf("112", "911")
    private val BY_REGION: Map<String, Set<String>> = mapOf(
        "CA" to setOf("911"), "US" to setOf("911"),
        "FR" to setOf("15", "17", "18", "112", "114", "115", "119", "191", "196", "197"),
        "GB" to setOf("999", "112"), "AU" to setOf("000", "112"), "NZ" to setOf("111"), "MX" to setOf("911", "065", "066", "060"),
        "DE" to setOf("110", "112"), "BE" to setOf("100", "101", "112"), "CH" to setOf("117", "118", "144", "112"),
    )

    fun basic(number: String, region: String?): Boolean {
        val d = PhoneNumbers.digits(number)
        if (d.isEmpty()) return false
        val list = EVERYWHERE + (BY_REGION[region?.uppercase(Locale.ROOT)] ?: emptySet())
        return d in list
    }
}
