// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Executed
import app.heyrobot.core.HandOffStepAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.MessageSigners
import app.heyrobot.core.messages.PinVerdict
import app.heyrobot.core.messages.SignerNow
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource

// Pure. A skill's hand-off step (docs/plans/skills-connections-plan.md §3.5, D14, D17; C7), decided here so the panel
// (HandOffRunner, in :voice) and the tests ask the same rules.
//
// Before the card ([HandOffStep.prepare]): the hand-off still exists; its app is installed and signed as when you picked it
// (the signer pinned as Messages pins a messaging app's, MessageSigners: the same key, or a key Android says is in its
// signing lineage); every value fits its slot; the intent builds (IntentSpecs: the action from the short list, the data's
// scheme fixed, typed extras only). An app signed by someone else is never opened: the step stops with "<app> was replaced
// by an app signed by someone else. Pick it again in Settings › Skills › Connections." Then what the card shows, exactly
// what goes: the app and its package, every value with where it came from, the action, the address and each extra, and
// what it does. Its reasons, as a connection's: CONNECTION_TAP when a value isn't yours (written by Robot's model, or read
// on the phone or from an answer) or the step can spend money, deletes or locks; CONNECTION when the model picked a value
// from your list. A spoken yes may answer its card only when all it does is open the app (D14), so a draft for someone
// (SEND, SENDTO, sms:, mailto:) always takes a tap when it shows a card.
//
// A hand-off has no host: no first-use card (D13) and no calls a minute (D16) apply; its skill's runs an hour still count.
// It needs no network. It runs only from the visible panel, never locked (D8) and never from a routine (D9).
//
// After the launch ([HandOffStep.opened]): "Opened Signal." or, for a draft, "Opened Signal with your words. Send it there
// when you're ready.": a draft is opened, never sent. The skill's next steps wait for the continuation's tap (SkillRun).

/**
 * The picked app as Android shows it now. C10: [UNPINNED], a hand-off whose app was never pinned on this phone (it came
 * from a backup that isn't yours, and trust is never restored): never opened until you pick it again.
 */
enum class AppPinState { TRUSTED, MISSING, REPLACED, UNPINNED }

/** What the panel knows when it prepares a hand-off (HandOffRunner reads it in :voice; tests give their own). */
class HandOffContext(
    /** The hand-off the step names, as connections.json holds it now (null: gone). */
    val connection: Connection?,
    /** Its app now. */
    val app: AppPinState,
)

object HandOffStep {
    /**
     * [pinned]: the signer kept when you picked the app; [now]: its signers now (null: not installed); [stillSigns]:
     * Android says [pinned] is in the package's signing lineage (a key rotated with Android's own scheme). C10: no
     * [pinned] signer is [AppPinState.UNPINNED], whatever is installed.
     */
    fun state(pinned: String?, now: SignerNow?, stillSigns: Boolean): AppPinState {
        if (pinned == null) return AppPinState.UNPINNED
        if (now == null) return AppPinState.MISSING
        return when (MessageSigners.verdict(AppPin(setOf(pinned)), now, stillSigns, firstUse = false)) {
            PinVerdict.TRUST -> AppPinState.TRUSTED
            else -> AppPinState.REPLACED
        }
    }

    /** What its card was prepared for: the app, its signer and what it asks. A run of a hand-off changed since is refused. */
    fun origin(c: Connection): String? = c.handOff?.let { ConnectionCodec.encode(listOf(c.copy(tested = null, name = ""))) }

    /** [a] prepared with what [x] knows: ready for the gate with its reasons and card, or refused with Robot's words. */
    fun prepare(a: HandOffStepAction, x: HandOffContext): Prepared {
        val c = x.connection?.takeIf { it.id == a.call.conn && it.kind == ConnKind.HAND_OFF } ?: return ConnStep.refused(ConnFailure.GONE, "")
        val app = c.handOff ?: return ConnStep.refused(ConnFailure.GONE, c.name)
        when (x.app) {
            AppPinState.MISSING -> return ConnStep.refused(ConnFailure.APP_MISSING, c.name)
            AppPinState.REPLACED -> return ConnStep.refused(ConnFailure.APP_REPLACED, c.name)
            AppPinState.UNPINNED -> return ConnStep.refused(ConnFailure.APP_UNPINNED, c.name)
            AppPinState.TRUSTED -> {}
        }
        // Never opened without a signer pinned here, whatever the state said (C10).
        if (!app.pinned) return ConnStep.refused(ConnFailure.APP_UNPINNED, c.name)
        val values = when (val v = ConnStep.values(app.intent.params, a.call.args)) {
            is ConnStep.Values.Ok -> v.values
            is ConnStep.Values.Refused -> return ConnStep.refused(v.why, c.name)
        }
        val intent = IntentSpecs.build(app, values) ?: return ConnStep.refused(ConnFailure.BAD_VALUE, c.name)
        val effects = IntentSpecs.effects(app.intent)
        val why = linkedSetOf<AskWhy>()
        val tap = effects.any { it in SkillEffect.RISKY } || values.values.any(ConnStep::notYours)
        if (tap) why += AskWhy.CONNECTION_TAP
        else if (values.values.any { ValueSource.MODEL in it.sources }) why += AskWhy.CONNECTION
        val spokenYes = c.spokenYes && effects.all { it in ConnStep.SPOKEN_YES }
        val origin = origin(c) ?: return ConnStep.refused(ConnFailure.GONE, c.name)
        val prepared = ConnPrepared(summary(c, intent), body(c, app, intent, values, effects), effects, origin, c.name, read = false, spokenYes = spokenYes)
        val note: Phrase? = when {
            effects.any { it in SkillEffect.RISKY } -> ConnStrings.noteRisky()
            tap -> ConnStrings.noteValue()
            AskWhy.CONNECTION in why -> ConnStrings.notePicked()
            else -> null
        }
        return Prepared.Ready(a.copy(prepared = prepared), why = why, note = note?.en(), noteText = note)
    }

    /** The card's summary and History's record (D20): "Open Signal", or "Open Signal with “On my way.”". */
    fun summary(c: Connection, i: HandOffIntent): Phrase {
        val text = IntentSpecs.shownText(i)?.replace(Regex("\\s+"), " ")?.trim()
        return if (text.isNullOrEmpty()) ConnStrings.handOffCard("name" to c.name)
        else ConnStrings.handOffCardWith("name" to c.name, "text" to (if (text.length > SHOWN) text.take(SHOWN - 1) + "…" else text))
    }

    /** The card's body: the app, the values and their sources, what it asks, what it does. */
    private fun body(c: Connection, app: HandOffApp, i: HandOffIntent, values: Map<String, SkillValue>, effects: Set<SkillEffect>): Phrase {
        val lines = mutableListOf<Phrase>(ConnStrings.handOffApp("name" to c.name, "package" to app.pkg))
        for ((k, v) in values) lines += ConnStrings.valueLine("arg" to k, "value" to IntentSpecs.text(v.value), "source" to ConnStep.source(v))
        lines += ConnStrings.handOffAction("action" to app.intent.action.name)
        i.data?.let { lines += ConnStrings.handOffData("data" to it) }
        for ((name, v) in i.extras) lines += ConnStrings.handOffExtra("extra" to name, "value" to IntentSpecs.text(v).take(ConnStep.PREVIEW_BODY))
        val chips = ConnStep.chips(effects, "")
        if (chips.isNotEmpty()) lines += ConnStrings.effectsLine("list" to Phrase.ListOf(chips))
        if (SkillEffect.MESSAGES_SOMEONE in effects) lines += ConnStrings.handOffSends("name" to c.name)
        return Phrase.Join(lines, "\n")
    }

    /**
     * What Robot says after the launch of [c]'s app: opened (a draft is "opened", never "sent", DRAFT_OPENED), or why
     * not. The skill's next steps wait for the continuation.
     */
    fun opened(c: Connection, launched: Boolean): Executed {
        if (!launched) return Executed(ActionStatus.FAILED, ConnFailure.APP_CANT_OPEN.text(c.name))
        val draft = c.handOff?.let { SkillEffect.MESSAGES_SOMEONE in IntentSpecs.effects(it.intent) } == true
        return if (draft) Executed(ActionStatus.DRAFT_OPENED, ConnStrings.handOffDraft("name" to c.name), launchedApp = true).copy(conn = ConnRan())
        else Executed(ActionStatus.DONE, ConnStrings.handOffOpened("name" to c.name), launchedApp = true).copy(conn = ConnRan())
    }

    /** The voice log's HAND_OFF a: its action (1 VIEW, 2 SEND, 3 SENDTO, 4 DIAL, 5 MAIN). */
    fun logAction(a: HandOffAction): Int = a.ordinal + 1

    /** The most characters of its words a card's summary shows. */
    private const val SHOWN = 80
}

/** Why an app can't be picked for a hand-off (the picker's words, C9 shows them). */
enum class PickProblem { SEVERAL_SIGNERS, NO_SIGNER, CANT_TAKE, NOT_VALID }

/**
 * The app picker's rules (plan §3.5, C7; C9 draws the screen): an app with a launcher icon (already visible to Robot, so
 * no `<queries>` change), never Robot itself; its one signer pinned as Termux's and Cheogram's are (an app signed by
 * several keys can't be pinned); what it is asked to do valid by [IntentSpecs.valid], and taken by one of its activities
 * (the app checks with [probe]).
 */
object HandOffPicker {
    /** The hand-off's name after you pick an app called [picked]: its label, unless you typed one of your own ([was]: the last app's label). */
    fun nameAfterPick(name: String, was: String?, picked: String): String = if (name.isBlank() || name == was) picked.take(ConnCheck.MAX_NAME) else name

    /** Saving [new] over [old] would pin another signer on the same app: only your word may do that, it clears the replaced-app warning. */
    fun signerChanged(old: HandOffApp?, new: HandOffApp?): Boolean =
        old != null && new != null && old.pkg == new.pkg && old.signer != null && old.signer != new.signer

    /** The signer to pin from [now] (the package's signers now), or why none. */
    fun signer(now: SignerNow?): Pair<String?, PickProblem?> {
        val d = now?.digests?.let(MessageSigners::clean).orEmpty()
        return when {
            d.isEmpty() -> null to PickProblem.NO_SIGNER
            d.size > 1 -> null to PickProblem.SEVERAL_SIGNERS
            else -> d.single() to null
        }
    }

    /**
     * The hand-off connection [id] called [name] for package [pkg] signed by [now], asking [spec]; [takes]: one of its
     * activities takes [probe]'s intent. Or why not.
     */
    fun connection(id: String, name: String, pkg: String, now: SignerNow?, spec: IntentSpec, takes: (HandOffIntent) -> Boolean): Pair<Connection?, PickProblem?> {
        val (signer, no) = signer(now)
        if (signer == null) return null to no
        val c = Connection(id, ConnKind.HAND_OFF, name, handOff = HandOffApp(pkg, signer, spec))
        if (!ConnCheck.valid(c)) return null to PickProblem.NOT_VALID
        if (!takes(probe(c.handOff!!))) return null to PickProblem.CANT_TAKE
        return c to null
    }

    /** What the picker asks the app to take: [app]'s action, type and the address's fixed words before its first slot. */
    fun probe(app: HandOffApp): HandOffIntent {
        val data = app.intent.data?.let { d -> (OpTemplate.parts(d)?.firstOrNull() as? OpTemplate.Part.Words)?.text ?: d.substringBefore("{{") }
        return HandOffIntent(app.pkg, app.intent.action.android, data, app.intent.mime, emptyList(), launcher = app.intent.action == HandOffAction.MAIN)
    }

    /** The picker's words for [p], about the app called [name]. */
    fun text(p: PickProblem, name: String): Phrase = when (p) {
        PickProblem.SEVERAL_SIGNERS -> ConnStrings.pickSeveralSigners("name" to name)
        PickProblem.NO_SIGNER -> ConnStrings.pickNoSigner("name" to name)
        PickProblem.CANT_TAKE -> ConnStrings.pickCantTake("name" to name)
        PickProblem.NOT_VALID -> ConnStrings.couldntSave()
    }
}
