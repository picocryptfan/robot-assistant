// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.linkparse

import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkVCard
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead

// :LINKPARSE PROCESS ONLY. 1.5, Robot to Robot (robot-to-robot-plan.md §3.3, §4.5, §5.1; job L4): what :linkparse does with
// one decrypted request another Robot sent. L1's strict decoder (core LinkRequests.decode: StrictJson, every type's keys,
// types and caps, one spelling) runs here and nowhere else; a contact card's vCard is read here too, as its strict subset
// (core LinkVCard.read: name, phones, emails, XMPP; no photo, no address to fetch). The answer is LinkParseWire's reply,
// which :link checks again and keeps only when it rebuilds exactly this plaintext. Payment requests aren't read: the `pay.`
// family stays reserved and is answered unsupported, as L1 left it (the user, 21 Oct: payments wait until after the full
// test). Nothing here logs, opens a file or reaches the network; ProcessIsolationTest's linkParsesOnlyInIsolation keeps it so.

internal object LinkParser {
    /** The reply frame for [plain], read under [cap]. Whatever happens inside, one answer: a refusal at worst. */
    fun answer(plain: ByteArray, cap: Int): ByteArray = try {
        LinkParseWire.encodeReply(read(plain, cap))
    } catch (_: Exception) {
        LinkParseWire.encodeReply(RequestRead.Bad(LinkProblem.BAD_FIELD))
    }

    fun read(plain: ByteArray, cap: Int): RequestRead {
        val read = LinkRequests.decode(plain, cap)
        if (read is RequestRead.Unsupported) {
            // Answered unsupported only when :link can see it's about this request (its id, its version), else refused.
            return if (LinkParseWire.unsupportedOk(plain, read.id, read.newer)) read else RequestRead.Bad(LinkProblem.NOT_CANONICAL)
        }
        val r = (read as? RequestRead.Ok)?.request ?: return read
        val offer = r.body as? CardOffer ?: return read
        // A contact card is the strict subset, or the whole request is refused (§3.3: a field past its rules drops it).
        val card = LinkVCard.read(offer.vcard) ?: return RequestRead.Bad(LinkProblem.BAD_FIELD)
        return RequestRead.Ok(LinkRequest(r.id, r.at, r.ttl, r.lang, CardOffer(offer.vcard, card)))
    }
}
