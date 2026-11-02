// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.linkparse

import app.heyrobot.core.link.ContactCard
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.link.requests.Ack
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.Caps
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.EvAccept
import app.heyrobot.core.link.requests.EvCancel
import app.heyrobot.core.link.requests.EvChange
import app.heyrobot.core.link.requests.EvDecline
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.FileAccept
import app.heyrobot.core.link.requests.FileAsk
import app.heyrobot.core.link.requests.FileDecline
import app.heyrobot.core.link.requests.FileDone
import app.heyrobot.core.link.requests.FileKey
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldJoin
import app.heyrobot.core.link.requests.FoldLeave
import app.heyrobot.core.link.requests.FoldOp
import app.heyrobot.core.link.requests.FoldOpKind
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.ListItem
import app.heyrobot.core.link.requests.ListOp
import app.heyrobot.core.link.requests.LsLeave
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.ModeLower
import app.heyrobot.core.link.requests.ModeRaise
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.QAsk
import app.heyrobot.core.link.requests.QReply
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.SettleHow
import app.heyrobot.core.link.requests.SpReply
import app.heyrobot.core.link.requests.SpRequest
import app.heyrobot.core.link.requests.SpSettle
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.requests.SplitItem
import app.heyrobot.core.link.requests.Unpair

/** One request of every type protocol v1 reads, as the code that sends it makes it (core's LinkRequestsTest has the same). */
object LinkSamples {
    const val T = 1_790_000_040L
    const val A = "0123456789abcdef0123456789abcdef"
    const val B = "fedcba9876543210fedcba9876543210"
    const val C = "0000000000000000000000000000000c"
    val SHA = "ab".repeat(32)
    val sam = ContactCard("Sam Tremblay", phones = listOf("+1 514 555-0101"), emails = listOf("sam@example.org"))
    private val dinner = EventFields("Dinner at Sam's", listOf(T + 3_600, T + 7_200), 120, place = "Chez Sam", note = "Bring wine\nand bread")

    fun bodies(): List<LinkBody> = listOf(
        Caps.of("Sam"),
        NetHint(LinkMode.PRIVATE, PushRoute("https://ntfy.example.org/upAbC123?up=1", ByteArray(65) { if (it == 0) 4 else it.toByte() }, ByteArray(16) { 9 }), OnionKey(ByteArray(32) { 5 }),
            listOf(LocalAddress(byteArrayOf(10, 0, 0, 7), 40_000)), A, ByteArray(32) { 6 }),
        ModeRaise(), ModeLower(), Unpair(), Ack(A, AckState.HANDLED),
        FbAsk(T, T + 21_600), FbReply(A, T, T + 21_600, listOf(Span(T, T + 1_800), Span(T + 3_600, T + 21_600))),
        EvPropose(A, 1, dinner), EvAccept(A, 1, T + 3_600), EvDecline(A, 1, "Busy that night"), EvChange(A, 2, dinner.copy(times = listOf(T + 90_000))), EvCancel(A, 3),
        SpRequest(A, 1, "CAD", 10_000, 3_450, listOf(SplitItem("Salad", 1_500, 1_500), SplitItem("Wine", 3_000, 1_000)), 450, 500, 3, file = B),
        SpReply(A, 1, true), SpSettle(A, 2, SettleHow.CASH, 3_450, "CAD"),
        FileOffer(A, "Costco receipt.pdf", OfferKind.RECEIPT, 120_000, SHA, OfferWhy.SPLIT, fields = OfferFields(merchant = "Costco", date = "2026-10-21", total = 8_412, currency = "CAD")),
        FileAccept(A), FileKey(A, ByteArray(32) { 3 }), FileDecline(A), FileDone(A, true, SHA), FileAsk("The lasagna recipe"),
        app.heyrobot.core.link.requests.GrpStatus(A, 1, listOf(app.heyrobot.core.link.requests.GrpPerson("Sam", app.heyrobot.core.link.requests.GrpWho.IN))), FoldJoin(A, "Cottage"), FoldOp(A, B, 1, FoldOpKind.ADD, C), FoldLeave(A),
        CardOffer.of(sam),
        EtaTell(10, "the station"), RemOffer("Bring the charger", T + 86_400),
        QAsk("What's the wifi password at the cottage?"), QReply(A, text = "It's on the fridge."),
        LsOffer(A, "Shopping", 1, 1, listOf(ListItem(B, 3, "Oat milk", false, 1_000, "2 L"))),
        LsOp(A, listOf(ListOp(B, 4, ListItem(B, 4, "Oat milk", true, 1_000, "2 L")), ListOp(C, 2))),
        LsLeave(A),
    )

    fun req(body: LinkBody, lang: String = "en") = LinkRequest(A, T, LinkRequests.DEFAULT_TTL, lang, body)

    /** [json] padded with spaces to a multiple of 512 bytes, as a plaintext. */
    fun pad(json: String): ByteArray {
        val b = json.toByteArray(Charsets.UTF_8)
        val size = (b.size + 511) / 512 * 512
        return ByteArray(size) { if (it < b.size) b[it] else ' '.code.toByte() }
    }

    /** A well-formed request of a type this build doesn't read ([type]: a reserved family's, payments included). */
    fun unread(type: String, body: String = "{}") = pad("{\"v\":1,\"id\":\"$A\",\"at\":$T,\"ttl\":604800,\"lang\":\"en\",\"type\":\"$type\",\"body\":$body}")
}
