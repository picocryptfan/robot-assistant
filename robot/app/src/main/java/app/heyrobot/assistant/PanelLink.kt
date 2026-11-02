// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.DeskCard
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskProposal
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.LinkCardCode
import app.heyrobot.core.link.LinkInCode
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkCardText
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.LinkRest
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.LinkClient
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.PeopleFiles
import java.security.SecureRandom
import java.time.ZoneId

// VOICE PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §4.2, §4.4, §4.5, §6.5; job L6): another Robot's requests as cards
// in the panel. When the panel shows (unlocked, no phone lock) and when :link says something changed while it shows, the
// cards waiting for you that this conversation hasn't shown yet come as one turn of Robot's: each card's summary drawn by
// code (core LinkCardText) with the other person's own words in its framed box, Yes (what that request's handler does:
// "Got it", "Accept") and Decline. A card no job answers yet offers Open Requests instead of a yes. Robot's proposal ("Let
// Robot accept plans from Sam on its own…?") is a card of its own: Turn on is a tap only, never a spoken yes; Not now.
//
// The turn is content (ChatTurn.messages, History keeps only "2 requests from other Robots."), and the conversation's taint
// gains each pairing (core LinkTaint, through PanelMessages): every later action asks first, and anything going to someone
// other than that Robot needs a tap. A spoken yes answers only free/busy, events, reminders and ETA (LinkPolicy.spokenYes);
// every other card is a tap (AskWhy.FROM_CONTENT). Nothing here reads a request's JSON: :link sends each one as
// :linkparse's frame beside its plaintext, rebuilt and checked by core DeskView.

object PanelLink {
    private const val CARD = "link-card-" // l10n: not text (a card's id prefix)
    private const val PROPOSE = "link-propose-" // l10n: not text (a card's id prefix)
    private const val REPLY = "link-reply-" // l10n: not text (a card's id prefix)

    private val random = SecureRandom()
    /** The conversation the shown cards belong to: a new one shows them again. */
    private var convId: String? = null
    private val shownCards = HashSet<String>()
    private val shownProposals = HashSet<DeskProposal>()
    /** L7: their Robots' answers shown (each said once: marked seen on :link once the panel took it). */
    private val shownReplies = HashSet<String>()
    /** What each panel card answers: its desk card (and pairing), or its proposal; L7: or the reply line it shows. */
    private class Held(val card: DeskCard?, val proposal: DeskProposal?, val reply: String? = null, val handled: Boolean = false)
    private val held = HashMap<String, Held>()
    @Volatile private var client: LinkClient? = null

    fun isCard(actionId: String?): Boolean = actionId != null && (actionId.startsWith(CARD) || actionId.startsWith(PROPOSE))

    /** What a refresh found for the panel: the turn's text and its cards, and the pairings it holds (the taint). */
    class Found(val text: String, val kept: String, val cards: List<UiAction>, val pairings: Set<String>, val translate: List<Translate> = emptyList())

    /** L10, D16: card [actionId]'s free text ([original]) to translate once the turn shows, by [req] (LinkRest.translateRequest). */
    class Translate(val actionId: String, val req: app.heyrobot.core.BrainMsg.OneShot, val original: String)

    /** The panel hid: :link may sleep again. */
    fun onHide() {
        val c = synchronized(this) { client.also { client = null } }
        try { c?.close() } catch (_: Throwable) {}
    }

    private fun client(ctx: Context): LinkClient = synchronized(this) { client ?: LinkClient(ctx.applicationContext).also { client = it } }

    /**
     * On a background thread: the cards and proposals this conversation ([conversation]) hasn't shown yet, or null (Robot to
     * Robot off, the phone locked or under a phone lock, nothing new, :link not answering). Marks nothing shown: [shown] does,
     * once the panel took them.
     */
    fun find(ctx: Context, conversation: String, langs: Langs): Found? {
        if (!LinkSettingsStore.current(ctx).on || Assistant.locked(ctx)) return null
        synchronized(this) {
            if (conversation != convId) { convId = conversation; shownCards.clear(); shownProposals.clear(); held.clear() }
        }
        val c = client(ctx)
        val desk = try { c.desk() } catch (t: Throwable) { RLog.w("link_panel_desk", t); null } ?: return null
        val newCards = synchronized(this) { desk.cards.filter { it.id !in shownCards } }
        val newProps = synchronized(this) { desk.proposals.filter { it !in shownProposals } }
        // L7: their Robots' answers to what you asked (and changes to your plans), not yet said.
        val newReplies = synchronized(this) { desk.lines.filter { it.entry.way == DeskWay.REPLY && !it.entry.seen && it.request != null && it.entry.id !in shownReplies } }
        // L10: an ETA or a reminder handled on its own, said once.
        val newHandled = synchronized(this) { desk.lines.filter { LinkRest.sayHandled(it) && it.entry.id !in shownReplies } }
        if (newCards.isEmpty() && newProps.isEmpty() && newReplies.isEmpty() && newHandled.isEmpty()) return null
        val views = c.pairings().orEmpty()
        val people = try { PeopleFiles.store(ctx).list() } catch (_: Throwable) { emptyList() }
        val hours = LinkSettingsStore.current(ctx).hours
        val zone = ZoneId.systemDefault()
        val out = ArrayList<UiAction>()
        val pairings = LinkedHashSet<String>()
        val tr = ArrayList<Translate>()
        // D16: their free text, in the other of English and French, translated after the turn shows (never without a People person).
        fun translate(id: String, r: app.heyrobot.core.link.requests.LinkRequest, pairing: String) {
            LinkRest.translateRequest(r, LinkNames.person(views.firstOrNull { it.id == pairing }, people), langs.say)
                ?.let { tr += Translate(id, it, LinkCardText.theirWords(r).joinToString("\n")) }
        }
        val now = System.currentTimeMillis() / 1000
        for (card in newCards) {
            val view = views.firstOrNull { it.id == card.pairing }
            val name = LinkNames.of(view, people)
            val sofar = LinkRest.remindersToday(desk.lines.map { it.entry }.filter { it.id != card.id }, card.pairing, now)
            val a = cardAction(card, name, langs, zone, LinkRest.waitsForLimit(card.type, view?.levels?.get(LinkCapability.REMINDERS), sofar))
            out += a
            translate(a.id, card.request, card.pairing)
            pairings += card.pairing
        }
        for ((p, more) in desk.more) {
            if (newCards.none { it.pairing == p }) continue
            val name = LinkNames.of(views.firstOrNull { it.id == p }, people)
            out += UiAction(token(CARD + "more-"), null, LinkStrings.more(more, "name" to name).render(langs), ActionStatus.DONE, LinkStrings.stays().render(langs)) // l10n: not text (an id prefix)
        }
        for (line in newReplies) {
            val r = line.request ?: continue
            val name = LinkNames.of(views.firstOrNull { it.id == line.entry.pairing }, people)
            val text = LinkCardText.reply(r, name, zone) ?: continue
            val words = LinkCardText.theirWords(r)
            val id = token(REPLY)
            synchronized(this) { held[id] = Held(null, null, line.entry.id) }
            out += UiAction(id, null, text.render(langs), ActionStatus.DONE, if (words.isEmpty()) null else LinkStrings.theirWords("name" to name).render(langs),
                body = words.joinToString("\n").ifEmpty { null }, content = true)
            translate(id, r, line.entry.pairing)
            pairings += line.entry.pairing
        }
        for (line in newHandled) {
            val r = line.request ?: continue
            val name = LinkNames.of(views.firstOrNull { it.id == line.entry.pairing }, people)
            val text = LinkCardText.handled(r, name, zone) ?: continue
            val words = LinkCardText.theirWords(r)
            val id = token(REPLY)
            synchronized(this) { held[id] = Held(null, null, line.entry.id, handled = true) }
            out += UiAction(id, null, text.render(langs), ActionStatus.DONE, if (words.isEmpty()) null else LinkStrings.theirWords("name" to name).render(langs),
                body = words.joinToString("\n").ifEmpty { null }, content = true)
            translate(id, r, line.entry.pairing)
            pairings += line.entry.pairing
        }
        for (pr in newProps) {
            val name = LinkNames.of(views.firstOrNull { it.id == pr.pairing }, people)
            val text = LinkCardText.proposal(pr.capability, name, hours) ?: continue
            val id = token(PROPOSE)
            synchronized(this) { held[id] = Held(null, pr) }
            // Loosening a level is a tap, never a spoken yes (§4.2).
            out += UiAction(id, null, text.render(langs), ActionStatus.PENDING, null, confirm = LinkStrings.turnOn().render(langs),
                cancel = LinkStrings.notNow().render(langs), tapFor = AskWhy.FROM_CONTENT)
        }
        if (out.isEmpty()) return null
        val n = newCards.size
        val head = when {
            n > 0 -> LinkStrings.inPanel(n)
            newReplies.isNotEmpty() -> LinkStrings.inPanelReplies(newReplies.size)
            newHandled.isNotEmpty() -> LinkStrings.inPanelHandled(newHandled.size)
            else -> null
        }
        return Found(head?.render(langs) ?: "", (head ?: LinkStrings.inPanel(n)).en(), out, pairings, tr)
    }

    /** The panel took [f]'s cards: they aren't shown again in this conversation; L7: its answers are marked said on :link. */
    fun shown(ctx: Context, f: Found) {
        var handled = 0
        val said = synchronized(this) {
            f.cards.mapNotNull { a -> held[a.id]?.let { h -> h.card?.let { shownCards += it.id }; h.proposal?.let { shownProposals += it }; if (h.handled) handled++; h.reply?.also { shownReplies += it } } }
        }
        if (said.isEmpty()) return
        val c = client(ctx)
        worker.execute { for (id in said) try { c.seen(id) } catch (t: Throwable) { RLog.w("link_panel_seen", t) } }
        if (said.size > handled) VoiceLog.add(VoiceEvent.LINK_IN, LinkInCode.REPLIES.id, said.size - handled)
        if (handled > 0) VoiceLog.add(VoiceEvent.LINK_IN, LinkInCode.HANDLED.id, handled)
    }

    /**
     * L10, D16: [t]'s translation, as the card's box shows it (their words, then Robot's translation under them), or null (no
     * model, too slow, nothing new). The one-shot has no tools and its user turn is PeerQuote.block: their words are data.
     */
    suspend fun translated(t: Translate, langs: Langs): String? {
        val r = try { BrainOneShots.run(t.req, app.heyrobot.core.OneShotBudget(startWithinMs = null, totalMs = TRANSLATE_MS, waitForLoad = false)) {} } catch (e: Throwable) { RLog.w("link_translate", e); null }
        val shown = LinkRest.shown((r as? app.heyrobot.core.OneShotResult.Done)?.text, t.original) ?: return null
        VoiceLog.add(VoiceEvent.LINK_IN, LinkInCode.TRANSLATED.id, 1)
        return LinkRest.withTranslation(t.original, shown, langs)
    }

    private const val TRANSLATE_MS = 60_000L

    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "robot-link-panel").apply { isDaemon = true } } } // l10n: not text (a thread's name)

    private fun cardAction(card: DeskCard, name: app.heyrobot.core.i18n.Phrase, langs: Langs, zone: ZoneId, limit: Boolean = false): UiAction {
        val id = token(CARD)
        synchronized(this) { held[id] = Held(card, null) }
        val words = LinkCardText.theirWords(card.request)
        val yes = LinkCardText.yes(card.yes)
        // A spoken yes only where §4.2 allows it (free/busy, events, reminders, ETA), only for a card with a yes, never Add to People.
        val voice = yes != null && LinkPolicy.spokenYes(card.type, card.yes, panelOpen = true, locked = false)
        val summary = LinkCardText.summary(card.request, name, zone).render(langs)
        return UiAction(id, null, if (limit) summary + " " + LinkStrings.remindersLimit("name" to name).render(langs) else summary, ActionStatus.PENDING,
            if (words.isEmpty()) (if (yes == null) LinkStrings.onlyDecline().render(langs) else null) else LinkStrings.theirWords("name" to name).render(langs),
            body = words.joinToString("\n").ifEmpty { null },
            confirm = (yes ?: LinkStrings.openRequests()).render(langs), cancel = LinkStrings.decline().render(langs),
            content = true, tapFor = if (voice) null else AskWhy.FROM_CONTENT)
    }

    /** What a tap (or an allowed spoken yes) on card [actionId] did. */
    sealed class Tapped {
        class Done(val status: ActionStatus, val line: String) : Tapped()
        /** A card with no yes: open Requests in the app. */
        object OpenRequests : Tapped()
    }

    /** A yes with no answer it can give from here: none at all, or a file, which you pick in Requests (the card closes when it goes). */
    internal fun opensRequests(yes: DeskYes?): Boolean = yes == null || yes == DeskYes.SEND_FILE

    /** Your answer on [actionId] ([yes]), on a background thread. Never while locked (LinkService refuses it too). */
    fun tapped(ctx: Context, actionId: String, yes: Boolean, langs: Langs): Tapped {
        val h = synchronized(this) { held[actionId] } ?: return Tapped.Done(ActionStatus.FAILED, LinkStrings.couldntAnswer().render(langs))
        if (Assistant.locked(ctx)) return Tapped.Done(ActionStatus.NEEDS_UNLOCK, LinkStrings.unlock().render(langs))
        val c = client(ctx)
        h.proposal?.let { p ->
            val ok = if (yes) c.setLevel(p.pairing, p.capability, app.heyrobot.core.link.LinkLevel.AUTO) else c.notNow(p.pairing, p.capability)
            VoiceLog.add(VoiceEvent.LINK_CARD, (if (!ok) LinkCardCode.FAILED else if (yes) LinkCardCode.TURN_ON else LinkCardCode.NOT_NOW).id, 0)
            PanelLinkAsk.forget()
            synchronized(this) { held.remove(actionId) }
            return if (!ok) Tapped.Done(ActionStatus.FAILED, LinkStrings.couldntAnswer().render(langs))
            else Tapped.Done(if (yes) ActionStatus.DONE else ActionStatus.DECLINED, (if (yes) LinkStrings.turnOn() else LinkStrings.notNow()).render(langs))
        }
        val card = h.card ?: return Tapped.Done(ActionStatus.FAILED, LinkStrings.couldntAnswer().render(langs))
        if (yes && opensRequests(card.yes)) return Tapped.OpenRequests
        // F144: asked before the yes, since the answer's only word back is that it went. Unreadable People: as any answer.
        val nobody = yes && (card.request.body as? app.heyrobot.core.link.requests.CardOffer)?.card?.let { app.heyrobot.core.link.LinkRest.addsNobody(it, PeopleFiles.store(ctx).list()) } == true
        val ok = c.answer(card.id, if (yes) DeskChoice.YES else DeskChoice.DECLINE)
        VoiceLog.add(VoiceEvent.LINK_CARD, (if (!ok) LinkCardCode.FAILED else if (yes) LinkCardCode.YES else LinkCardCode.DECLINE).id, card.type.code)
        if (!ok) return Tapped.Done(ActionStatus.FAILED, LinkStrings.couldntAnswer().render(langs))
        synchronized(this) { held.remove(actionId) }
        return Tapped.Done(if (yes) ActionStatus.DONE else ActionStatus.DECLINED, (if (nobody) LinkStrings.alreadyHave() else if (yes) LinkStrings.answered() else LinkStrings.declined()).render(langs))
    }

    private fun token(prefix: String): String = prefix + ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) } // l10n: not text (a random id)

}
