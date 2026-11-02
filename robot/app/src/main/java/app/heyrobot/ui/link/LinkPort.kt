// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import app.heyrobot.sys.AppLanguage
import app.heyrobot.core.link.LinkFolders
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.FileAsk
import app.heyrobot.core.link.requests.LinkRequest
import android.content.Context
import app.heyrobot.core.People
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.PersonSource
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.CodeState
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskView
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.LinkClient
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.LinkStatus
import app.heyrobot.sys.MadeCodeReply
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.UsedCodeReply

// MAIN PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §6.1 to §6.3; job L5): what the Robot to Robot screens ask of
// the phone. The phone's port reaches :link through one LinkClient (bound by name), Settings › Robot to Robot's values in
// link_settings.json, your People, and :files for a photographed code; tests give theirs. Every call blocks: never on the
// main thread. A screen makes one port and closes it when it goes, so :link sleeps again once nothing uses it.

interface LinkPort {
    fun settings(): LinkSettings
    /** Saved to link_settings.json; false for a newer Robot's file or a failed write. */
    fun saveSettings(change: (LinkSettings) -> LinkSettings): Boolean
    fun status(): LinkStatus?
    fun setUp(): Boolean
    fun startOver(): Boolean
    /** F59: something sealed is kept here, so Start over has something to do even with the switch off. */
    fun holdsState(): Boolean = false
    fun pairings(): List<PairingView>?
    fun blocked(): List<BlockedView>?
    /** Your People, sorted as Settings › People sorts them. */
    fun people(): List<Person>
    /** A new person in your People, named [name] (you typed it); null when it couldn't be saved. */
    fun addPerson(name: String): Person?
    fun makeCode(mode: LinkMode, inPerson: Boolean): MadeCodeReply?
    fun useCode(text: String, form: PairCodeForm, mode: LinkMode): UsedCodeReply?
    fun codeState(id: String): Pair<CodeState, String?>?
    fun cancelCode(id: String): Boolean
    fun cancelCodes(): Boolean
    /** The QR code's text in [photo], read by :files (never in this process); null when there is none. */
    fun readQr(photo: ByteArray): String?
    fun safety(pairing: String): String?
    fun verify(pairing: String): Boolean
    fun setPerson(pairing: String, person: String?): Boolean
    fun setPreset(pairing: String, preset: LinkPreset): Boolean
    fun setLevel(pairing: String, cap: LinkCapability, level: LinkLevel): Boolean
    fun raise(pairing: String): Boolean
    fun lower(pairing: String): Boolean
    fun keepMode(pairing: String): Boolean
    fun unpair(pairing: String): Boolean
    fun block(pairing: String, label: String): Boolean
    fun unblock(id: String): Boolean
    fun answerMoved(pairing: String, accept: Boolean): Boolean
    /** L12: "Tell my Robots I moved", for one restored pairing. False: it couldn't go. */
    fun sendMoved(pairing: String): Boolean = false
    /** F27: the Push app row. */
    fun pushApps(): app.heyrobot.core.link.PushApps? = null
    fun choosePush(pkg: String): Boolean = false
    // L6, Requests (§6.4): the desk. Defaults for ports that have none (L5's tests).
    /** The phone is locked, or a phone lock is on: Requests shows nothing then (§4.4). */
    fun locked(): Boolean = false
    fun desk(): DeskView? = null
    fun answer(id: String, choice: DeskChoice): Boolean = false
    /** L8: every file sent and received. */
    fun transfers(): List<app.heyrobot.core.link.TransferView> = emptyList()
    /** L8: asks [pairing]'s Robot for a file ([what]: your words); false when nothing went. */
    fun askFile(pairing: String, what: String): Boolean = false
    fun reply(id: String, text: String): Boolean = false
    fun undo(id: String): Boolean = false
    /** L11: shared folders and group plans (:link keeps them); null while locked or when it doesn't answer. */
    fun folders(): List<app.heyrobot.core.link.FolderRec>? = null
    fun folderInvite(pairing: String, name: String): String? = null
    fun folderRemove(pairing: String, fold: String, item: String): Boolean = false
    fun folderLeave(pairing: String, fold: String): Boolean = false
    fun groups(): app.heyrobot.core.link.GroupsView? = null
    fun groupStart(g: app.heyrobot.core.link.LinkGroup): String? = null
    fun groupPropose(id: String, time: Long): Boolean = false
    fun groupCancel(id: String): Boolean = false
    fun notNow(pairing: String, cap: LinkCapability): Boolean = false
    /** L7: the calendars shown in your calendar apps, for "Calendars other Robots see as busy" (empty without calendar access). */
    fun calendars(): List<app.heyrobot.core.CalendarInfo> = emptyList()
    fun close()
}

/** The phone's. */
class PhoneLinkPort(ctx: Context) : LinkPort {
    private val app = ctx.applicationContext
    private val client = LinkClient(app)
    private val people = PeopleFiles.store(app)

    override fun settings(): LinkSettings = LinkSettingsStore.current(app)
    override fun saveSettings(change: (LinkSettings) -> LinkSettings): Boolean = LinkSettingsStore.update(app, change)
    override fun status() = client.status()
    override fun setUp() = client.setUp()
    override fun startOver() = client.startOver()
    override fun holdsState() = LinkFolders.holdsAnything(app.filesDir)
    override fun pairings() = client.pairings()
    override fun blocked() = client.blocked()
    override fun folders() = client.folders()
    override fun folderInvite(pairing: String, name: String) = client.folderInvite(pairing, name)
    override fun folderRemove(pairing: String, fold: String, item: String) = client.folderRemove(pairing, fold, item)
    override fun folderLeave(pairing: String, fold: String) = client.folderLeave(pairing, fold)
    override fun groups() = client.groups()
    override fun groupStart(g: app.heyrobot.core.link.LinkGroup) = client.groupStart(g)
    override fun groupPropose(id: String, time: Long) = client.groupPropose(id, time)
    override fun groupCancel(id: String) = client.groupCancel(id)
    override fun people(): List<Person> = People.sorted(people.list().filter { it.confirmed })

    override fun addPerson(name: String): Person? {
        val now = System.currentTimeMillis()
        val p = People.clean(Person(PeopleStore.newId(), name.trim(), source = PersonSource.MANUAL, updatedAt = now))
        if (p.name.isBlank()) return null
        return people.update { l -> l + p }?.firstOrNull { it.id == p.id }
    }

    override fun makeCode(mode: LinkMode, inPerson: Boolean) = client.makeCode(mode, inPerson)
    override fun useCode(text: String, form: PairCodeForm, mode: LinkMode) = client.useCode(text, form, mode)
    override fun codeState(id: String) = client.codeState(id)
    override fun cancelCode(id: String) = client.cancelCode(id)
    override fun cancelCodes() = client.cancelCodes()

    override fun readQr(photo: ByteArray): String? {
        val link = FileParseLink(app)
        return try {
            if (!link.start()) null else (link.qr(photo) as? ParseResult.Ok)?.out?.text
        } finally {
            link.end("link_qr") // l10n: not text (a log code)
        }
    }

    override fun safety(pairing: String) = client.safety(pairing)
    override fun verify(pairing: String) = client.verify(pairing)
    override fun setPerson(pairing: String, person: String?) = client.setPerson(pairing, person)
    override fun setPreset(pairing: String, preset: LinkPreset) = client.setPreset(pairing, preset)
    override fun setLevel(pairing: String, cap: LinkCapability, level: LinkLevel) = client.setLevel(pairing, cap, level)
    override fun raise(pairing: String) = client.raise(pairing)
    override fun lower(pairing: String) = client.lower(pairing)
    override fun keepMode(pairing: String) = client.keepMode(pairing)
    override fun unpair(pairing: String) = client.unpair(pairing)
    override fun block(pairing: String, label: String) = client.block(pairing, label)
    override fun unblock(id: String) = client.unblock(id)
    override fun answerMoved(pairing: String, accept: Boolean) = client.answerMoved(pairing, accept)
    override fun sendMoved(pairing: String) = client.sendMoved(pairing)
    override fun pushApps() = client.pushApps()
    override fun choosePush(pkg: String) = client.choosePush(pkg)
    override fun locked(): Boolean = Assistant.locked(app)
    override fun desk(): DeskView? = client.desk()
    override fun answer(id: String, choice: DeskChoice) = client.answer(id, choice)
    override fun transfers() = client.transfers().orEmpty()
    override fun askFile(pairing: String, what: String): Boolean {
        val r = try {
            LinkRequest.of(FileAsk(what), System.currentTimeMillis() / 1000, AppLanguage.langs(app).ui.id)
        } catch (_: IllegalArgumentException) { return false }
        val bytes = LinkRequests.encode(r) ?: return false
        return client.send(pairing, r, bytes) == 0
    }
    override fun reply(id: String, text: String) = client.reply(id, text)
    override fun undo(id: String) = client.undo(id)
    override fun notNow(pairing: String, cap: LinkCapability) = client.notNow(pairing, cap)
    override fun calendars() = Calendars.visible(app)
    override fun close() = client.close()
}

/** The People ids with a pairing (not an ENDED note), for People's "Robot" label. Empty while Robot to Robot is off. Blocks. */
fun linkedPeople(ctx: Context): Set<String> {
    if (!LinkSettingsStore.current(ctx).on) return emptySet()
    val port = LinkPorts.make(ctx)
    return try {
        port.pairings().orEmpty().filter { it.phase != app.heyrobot.core.link.PairingPhase.ENDED }.mapNotNull { it.person }.toSet()
    } finally {
        port.close()
    }
}
