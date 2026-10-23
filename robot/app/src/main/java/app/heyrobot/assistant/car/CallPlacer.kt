// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Addresses
import app.heyrobot.core.Approval
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.Reachable
import app.heyrobot.core.Recipients
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.car.CallRules
import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CallTarget
import app.heyrobot.core.car.CallWay
import app.heyrobot.core.car.EmergencyNumbers
import app.heyrobot.core.car.PhoneCandidate
import app.heyrobot.core.car.PhoneLabel
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.lock.LockSignals
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Contacts
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.Phones
import java.util.Locale

// VOICE PROCESS (0.8). Calls: who a name is (People and the contacts, numbers
// only, as texts find people), and placing the call. ACTION_DIAL by default: the dialer opens with the number and you
// tap Call, no permission. ACTION_CALL only after car mode's spoken read-back and yes, with "Call directly after a
// spoken yes" and the Phone permission you granted from its row; or (1.1, Phone lock §6) during a lock, after the call
// card's Call, to one of the People the locks let you call: the lock covers the dialer, never the call screen. An
// emergency number (Android's own list, or the region's when Android can't say) only ever opens the dialer: ACTION_CALL
// can't call one, and Robot never tries. During a lock that is the phone's own emergency dialer, which the lock never
// covers (the cover's Emergency call opens the same one); the ordinary dialer would open behind the cover.

object CallPlacer {
    /** The intent's action for a call placed [way]: an [emergency] number is always the dialer, never ACTION_CALL. */
    fun actionFor(way: CallWay, emergency: Boolean): String = if (way == CallWay.CALL && !emergency) Intent.ACTION_CALL else Intent.ACTION_DIAL

    /** How a number is placed: a tel: link with [actionFor]'s action. */
    fun intentFor(number: String, way: CallWay, emergency: Boolean): Intent =
        Intent(actionFor(way, emergency), Uri.fromParts("tel", CallTarget.dialable(number), null))

    /** Android's emergency numbers for this phone, or the region's list when it can't say (errs towards the dialer). */
    fun isEmergency(ctx: Context, number: String, region: String?): Boolean {
        val android = try { ctx.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true } catch (_: Throwable) { false }
        return android || EmergencyNumbers.basic(number, region)
    }

    /** What the call setting and the Phone permission allow now. */
    fun rules(ctx: Context): CallRules {
        val store = HandsFreeFiles.store(ctx)
        return CallRules(store.car().settings.calls, Assistant.granted(ctx, Manifest.permission.CALL_PHONE), store.headset().safety.callAfterSpokenYes)
    }

    /** Everyone [name] may be, with their numbers (People and the contacts; an XMPP-only person as an address). */
    fun candidates(ctx: Context, name: String, settings: RobotSettings): List<PhoneCandidate> {
        val book = PeopleFiles.book(ctx)
        val matches = book.best(name, max = Recipients.MAX_PEOPLE, reach = Reachable.text(xmppOk = true))
        if (matches.isEmpty()) return emptyList()
        val region = settings.regionFor(Phones.deviceRegion(ctx))
        return Recipients.forText(book.cards(matches) { Contacts.cards(ctx, it) }, xmppOk = true).mapNotNull { r ->
            if (r.xmpp) return@mapNotNull PhoneCandidate(r.name, r.address, learned = r.learned, xmpp = true)
            val e164 = Phones.e164(r.address, region) ?: return@mapNotNull null
            PhoneCandidate(r.name, e164, label(r.label), r.learned)
        }.distinctBy { it.digits.ifEmpty { it.number } }
    }

    private fun label(l: String?): PhoneLabel? {
        val k = l?.trim()?.lowercase(Locale.ROOT) ?: return null
        return PhoneLabel.entries.firstOrNull { p -> p.keys.any { k.contains(it) } } ?: PhoneLabel.OTHER
    }

    /**
     * The call tool before its card (the model's, "call Mom" in the panel): the number it will dial, or the people to
     * choose from (Recipients' card), or why not. A typed or spoken number is shown as it is; an emergency one says so.
     */
    fun prepare(ctx: Context, a: Action.Call, settings: RobotSettings): Prepared {
        val region = settings.regionFor(Phones.deviceRegion(ctx))
        val to = a.to.trim()
        if (Addresses.isPhone(to) || to.all { it.isDigit() || it in "+-(). " }) {
            val number = Phones.e164(to, region) ?: CallTarget.dialable(to).takeIf { it.length >= 2 }
                ?: return Prepared.Refused(ActionStatus.INVALID, ToolStrings.badNumber())
            val emergency = isEmergency(ctx, number, region)
            return Prepared.Ready(a.copy(number = number, emergency = emergency, direct = lockDirect(ctx, number, emergency, settings)))
        }
        val all = candidates(ctx, to, settings)
        if (all.isEmpty()) {
            return if (!Contacts.granted(ctx)) Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowContacts())
            else Prepared.Refused(ActionStatus.NOT_FOUND, InfoStrings.noContact("name" to to))
        }
        val phones = all.filter { !it.xmpp }
        if (phones.isEmpty()) return Prepared.Refused(ActionStatus.FAILED, HandsFreeStrings.noXmpp())
        val matches = PeopleFiles.book(ctx).best(to, max = Recipients.MAX_PEOPLE, reach = Reachable.text(xmppOk = false))
        val options = phones.map { p ->
            val emergency = isEmergency(ctx, p.number, region)
            val call = a.copy(number = p.number, who = p.name, label = p.label?.takeIf { it != PhoneLabel.OTHER }?.en?.replaceFirstChar { it.uppercase() },
                learned = p.learned, emergency = emergency, direct = lockDirect(ctx, p.number, emergency, settings))
            Recipients.Option(p.name, call.label, Phones.display(p.number, region), call, learned = p.learned)
        }.let { Recipients.trusted(matches, it) }
        if (options.isEmpty()) return Prepared.Refused(ActionStatus.NOT_FOUND, ToolStrings.noNumber())
        return Recipients.prepare(a, matches, options, 0) { name -> a.copy(to = name) }
    }

    /**
     * 1.1, Phone lock (§6): during a lock with the screen unlocked, a call to one of the People the locks let you call is
     * placed by its card's Call (ACTION_CALL), since the lock covers the dialer and not the call screen. Only where a
     * card comes first (the call tool on Ask first, as it is by default: "Just do it" keeps the dialer, which the lock
     * covers), with the Phone permission (Settings › Phone lock › a lock's People row asks for it), never an emergency
     * number.
     */
    private fun lockDirect(ctx: Context, number: String, emergency: Boolean, settings: RobotSettings): Boolean =
        !emergency && settings.approval(ToolName.CALL) == Approval.ASK && Assistant.granted(ctx, Manifest.permission.CALL_PHONE) &&
            PhoneLockNow.on(ctx) && !Assistant.keyguard(ctx) && PhoneLockNow.callKey(number) in PhoneLockNow.callKeys(ctx)

    /** During a phone lock, only the numbers of the People the locks let you call (checked again as the call starts); any number outside one. */
    private fun lockAllows(ctx: Context, number: String): Boolean =
        !PhoneLockNow.on(ctx) || PhoneLockNow.callKey(number) in PhoneLockNow.callKeys(ctx)

    /**
     * Opens the dialer with the number, or calls it ([Action.Call.direct]: after car mode's spoken yes, or a phone lock's
     * call card). During a lock a direct call goes only to a number the locks still allow; any other opens the dialer.
     */
    fun run(ctx: Context, a: Action.Call, launch: (Intent) -> Boolean): Executed {
        val number = a.number ?: return Executed(ActionStatus.FAILED, ToolStrings.noOneToCall())
        val way = if (a.direct && !a.emergency && Assistant.granted(ctx, Manifest.permission.CALL_PHONE) && lockAllows(ctx, number)) CallWay.CALL else CallWay.DIAL
        // On Android's lock screen too: its emergency dialer shows over the lock, where the dialer would ask for an unlock.
        val locked = a.emergency && PhoneLockNow.on(ctx)
        val sos = (locked || a.emergency && Assistant.keyguard(ctx)) && LockSignals.emergencyDialer(ctx, Uri.fromParts("tel", CallTarget.dialable(number), null))?.let { i ->
            try { launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { RLog.w("call_sos", t); false }
        } == true
        val ok = sos || try { launch(intentFor(number, way, a.emergency).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { RLog.w("call_start", t); false }
        VoiceLog.add(VoiceEvent.CAR_CALL, way, if (a.emergency) 1 else 0)
        if (!ok) return Executed(ActionStatus.NO_APP, ToolStrings.noPhoneApp())
        return Executed(ActionStatus.DONE, when {
            locked && !sos -> ToolStrings.dialerCovered()
            a.emergency -> ToolStrings.dialerWithNumber()
            way == CallWay.CALL -> ToolStrings.calling()
            else -> ToolStrings.dialerOpen()
        }, launchedApp = true)
    }

    /** "Call directly after a spoken yes" can work now: the setting, the Safety row and the Phone permission. */
    fun directAllowed(ctx: Context): Boolean = rules(ctx).let { it.setting == CallSetting.CALL_AFTER_YES && it.way == CallWay.CALL }
}
