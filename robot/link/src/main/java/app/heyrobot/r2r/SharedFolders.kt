// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskNotice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.FolderOfferFit
import app.heyrobot.core.link.FolderOpResult
import app.heyrobot.core.link.FolderRec
import app.heyrobot.core.link.FolderRules
import app.heyrobot.core.link.FolderState
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.FileDecline
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldJoin
import app.heyrobot.core.link.requests.FoldLeave
import app.heyrobot.core.link.requests.FoldOp
import app.heyrobot.core.link.requests.FoldOpKind
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestType

/**
 * 1.5, shared folders (§4.1, D17; L11), on the link thread. Joining is always your tap, on a card that says what you agree to; after
 * that a folder stays in step on its own, and the other Robot is believed in nothing. The rules are core FolderRules.
 *
 *  - No record, no way in: a `fold.op` or a folder's `file.offer` for anything you didn't join is declined, with no card and no file.
 *  - A file for a folder takes L8's one way in ([LinkTransfers.acceptForFolder]), with its day's limit and room: nothing readable until
 *    its hash passes, sealed for Files' next unlock, marked as no one's tap so what can run stays refused (core FilePeer.mayFile).
 *  - This never touches a file: a removal or a leave takes lines out, and your copies stay.
 */
class SharedFolders(private val st: LinkStateStore, private val transfers: LinkTransfers) : DeskHandler {
    override val types = setOf(RequestType.FOLD_JOIN, RequestType.FOLD_OP, RequestType.FOLD_LEAVE)

    /** The desk's notices, set when it's made. */
    var notice: (DeskNotice) -> Unit = {}

    private fun rec(pairing: String, fold: String) = st.folders[FolderRec.key(pairing, fold)]
    private fun save(f: FolderRec): Boolean = try { st.put(f); true } catch (_: LinkStateException) { false }
    private fun full() = st.folders.size >= FolderRules.MAX_FOLDERS

    // Asking to join ---------------------------------------------------------------------------------------------------------

    override fun hold(pairing: String, r: LinkRequest, out: DeskOut, card: () -> AckState): AckState? {
        val b = r.body as? FoldJoin ?: return null
        if (!FolderRules.nameOk(b.name) || rec(pairing, b.fold) != null || full()) return AckState.DECLINED
        return card()
    }

    override fun yes(r: LinkRequest) = if (r.body is FoldJoin) DeskYes.ACCEPT else null

    override fun stale(pairing: String, r: LinkRequest): Boolean = (r.body as? FoldJoin)?.let { rec(pairing, it.fold) != null } == true

    /** Your yes on the card: only now is anything from them taken for this folder. */
    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? {
        val b = r.body as? FoldJoin ?: return null
        if (!FolderRules.nameOk(b.name) || rec(pairing, b.fold) != null || full()) return DeskDone(AckState.DECLINED)
        return if (save(FolderRec(b.fold, pairing, FolderState.JOINED, b.name, false, out.now()))) DeskDone() else null
    }

    /** Your invite's answer: it's only ever joined on their yes. */
    override fun unlisted(pairing: String, requestId: String, state: AckState?) {
        val f = st.folders.values.firstOrNull { it.pairing == pairing && it.state == FolderState.INVITED && it.invite == requestId } ?: return
        when (state) {
            AckState.HANDLED -> save(f.copy(state = FolderState.JOINED, invite = null))
            null, AckState.DECLINED, AckState.EXPIRED -> {
                try { st.deleteFolder(f) } catch (_: LinkStateException) {}
                notice(DeskNotice(DeskNoticeKind.FOLDER_ENDED, pairing))
            }
            else -> {}
        }
    }

    // In step ----------------------------------------------------------------------------------------------------------------

    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState = when (val b = r.body) {
        is FoldOp -> op(pairing, b, out)
        is FoldLeave -> {
            rec(pairing, b.fold)?.let { f ->
                end(f, out)
                if (f.state == FolderState.JOINED) notice(DeskNotice(DeskNoticeKind.FOLDER_ENDED, pairing))
            }
            AckState.RECEIVED
        }
        else -> AckState.RECEIVED
    }

    private fun op(pairing: String, b: FoldOp, out: DeskOut): AckState {
        val f = rec(pairing, b.fold) ?: return AckState.DECLINED
        if (f.state != FolderState.JOINED) return AckState.BUSY // Their yes can pass its own ack on the way.
        val (next, result) = FolderRules.apply(f, b, out.now())
        return when (result) {
            FolderOpResult.ADDED -> if (save(next)) AckState.RECEIVED else AckState.BUSY
            FolderOpResult.REMOVED -> if (save(next)) {
                transfers.cancelIn(pairing, f.item(b.item)!!.file, out)
                AckState.RECEIVED
            } else AckState.BUSY
            FolderOpResult.SAME -> AckState.RECEIVED
            FolderOpResult.WAIT -> AckState.BUSY
            FolderOpResult.REFUSED -> AckState.DECLINED
        }
    }

    /** Their `file.offer` for a folder (LinkDesk, ahead of the Files level, with the Folders level checked). */
    fun offer(pairing: String, o: FileOffer, out: DeskOut): AckState {
        val joined = st.folders.values.filter { it.pairing == pairing && it.state == FolderState.JOINED }
        if (joined.isEmpty()) return AckState.DECLINED
        val f = joined.firstOrNull { x -> x.items.any { it.file == o.file } } ?: return AckState.BUSY // Its `fold.op` goes first and may come later.
        return when (FolderRules.offerFits(f, o)) {
            FolderOfferFit.NOT_YET -> AckState.BUSY
            FolderOfferFit.REPEAT -> AckState.RECEIVED
            FolderOfferFit.REFUSED -> {
                save(FolderRules.refused(f, o.file))
                out.send(pairing, FileDecline(o.file))
                AckState.DECLINED
            }
            FolderOfferFit.FITS -> when {
                !save(FolderRules.offered(f, o)) -> AckState.BUSY
                transfers.acceptForFolder(pairing, o, out) -> AckState.RECEIVED
                else -> { save(f); AckState.BUSY } // The day's limit or the phone's room: it comes again.
            }
        }
    }

    // Yours -----------------------------------------------------------------------------------------------------------------

    /** You invite [pairing]'s Robot: the new folder's id, or null. Nothing's shared until they say yes. */
    fun invite(pairing: String, name: String, out: DeskOut): String? {
        if (!FolderRules.nameOk(name) || full() || st.pairings[pairing]?.state != PairingState.ACTIVE) return null
        val fold = LinkIds.newId()
        val req = out.send(pairing, FoldJoin(fold, name), LinkRequests.MAX_TTL) ?: return null
        return fold.takeIf { save(FolderRec(fold, pairing, FolderState.INVITED, name, true, out.now(), req)) }
    }

    /** A file of yours: the line, then its `fold.op`, ahead of the offer that FilesToRobot sends next. The line's id, or null when it doesn't fit. */
    fun add(pairing: String, fold: String, file: String, name: String, size: Long, out: DeskOut): String? {
        val f = rec(pairing, fold) ?: return null
        if (!FileOffer.nameOk(name)) return null
        val item = LinkIds.newId()
        val next = FolderRules.add(f, item, file, name, size, out.now()) ?: return null
        if (!save(next)) return null
        out.send(pairing, FoldOp(fold, item, 1, FoldOpKind.ADD, file), LinkRequests.MAX_TTL) ?: return null.also { save(f) }
        return item
    }

    /** A line you added comes out, on both sides. Your file stays where it is. */
    fun remove(pairing: String, fold: String, item: String, out: DeskOut): Boolean {
        val f = rec(pairing, fold) ?: return false
        val (next, rev) = FolderRules.remove(f, item) ?: return false
        if (!save(next)) return false
        out.send(pairing, FoldOp(fold, item, rev, FoldOpKind.REMOVE, null), LinkRequests.MAX_TTL)
        return true
    }

    /** You leave: they're told, and what's on its way stops. Your copies stay. */
    fun leave(pairing: String, fold: String, out: DeskOut): Boolean {
        val f = rec(pairing, fold) ?: return false
        out.send(pairing, FoldLeave(fold), LinkRequests.MAX_TTL)
        end(f, out)
        return true
    }

    private fun end(f: FolderRec, out: DeskOut) {
        f.items.filter { !it.mine && !it.gone }.forEach { transfers.cancelIn(f.pairing, it.file, out) }
        try { st.deleteFolder(f) } catch (_: LinkStateException) {}
    }

    /** A pairing moving keeps its folders; one that ended takes them with it. */
    override fun prune(now: Long) {
        for (f in st.folders.values.toList()) {
            val s = st.pairings[f.pairing]?.state
            if (s == null || s == PairingState.ENDED) try { st.deleteFolder(f) } catch (_: LinkStateException) {}
        }
    }

    /** Every folder (LinkService's FOLDERS, unlocked). */
    fun views(): List<FolderRec> = st.folders.values.toList()
}
