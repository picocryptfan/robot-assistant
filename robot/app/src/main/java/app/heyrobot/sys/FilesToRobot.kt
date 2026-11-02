// Copyright 2026 Robot contributors
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.OutPlan
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.files.PeerOut
import app.heyrobot.core.files.PeerSent
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.XferPaths
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseResult
import java.io.ByteArrayInputStream

// MAIN PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §3.6 step 1; job L8): a file from Robot Files offered to another
// Robot. The copy is :files' stripped one whatever the Share setting says ("sent copies are always made by :files"); a type it
// can't strip goes only after you said so, as with Share. Sealed here, then :link is told: it never reads Robot Files.

/** A file a Robot asked for, on its way ([card]: the request's card, answered when the offer went; [re]: the request's id; [question]: a question's card, the file its answer). */
data class RobotAsk(val card: String, val pairing: String, val re: String, val question: Boolean = false)

object FilesToRobot {
    fun send(ctx: Context, vault: FilesVault, strip: (ByteArray) -> ParseResult, locked: () -> Boolean, e: FileEntry, type: FileType,
        pairing: String, allowOriginal: Boolean, ask: RobotAsk?, folder: String? = null): PeerSent {
        if (ask != null && ask.pairing != pairing) return PeerSent.FAILED
        val app = ctx.applicationContext
        val (copy, name) = when (val plan = FileOut.plan(e, type, stripLocation = true)) {
            is OutPlan.Go -> plan.copy to plan.name
            is OutPlan.AskFirst -> if (allowOriginal) OutgoingCopy.ORIGINAL to plan.name else return PeerSent.ASK_FIRST
        }
        val client = LinkClient(app)
        try {
            var offered: String? = null
            val outgoing = PeerOut({ XferPaths.outgoing(XferPaths.linkDir(app.filesDir), it) }, { v, key -> (if (folder != null) toFolder(client, folder, v, key) else client.sendFile(v, key)).also { if (it) offered = v.offer.file } }, locked)
            val why = when { folder != null -> OfferWhy.FOLDER; ask == null -> OfferWhy.OFFER; ask.question -> OfferWhy.QUESTION; else -> OfferWhy.REQUEST }
            val sent = outgoing.send(pairing, e, type, name, why, ask?.re) {
                if (copy != OutgoingCopy.STRIPPED) vault.open(e.id)
                else {
                    val bytes = vault.open(e.id).use { it.readAll(ParseLimits.MAX_INPUT.toLong()) }
                    val out = (strip(bytes) as? ParseResult.Ok)?.out?.copy
                    bytes.fill(0)
                    out?.let(::ByteArrayInputStream)
                }
            }
            if (sent == PeerSent.OFFERED && ask != null) {
                val file = offered
                if (ask.question && file != null) client.answerWithFile(ask.card, file) else client.answer(ask.card, DeskChoice.YES)
            }
            return sent
        } finally {
            client.close()
        }
    }

    /** L11: the folder's line, then the file; a file that can't go takes its line back out. A dangerous file never goes to a folder: it would arrive with no one's tap. */
    private fun toFolder(client: LinkClient, folder: String, v: TransferView, key: ByteArray): Boolean {
        if (v.offer.kind == OfferKind.DANGEROUS) return false
        val item = client.folderAdd(v.pairing, folder, v.offer.file, v.offer.name, v.offer.size) ?: return false
        return client.sendFile(v, key).also { if (!it) client.folderRemove(v.pairing, folder, item) }
    }
}
